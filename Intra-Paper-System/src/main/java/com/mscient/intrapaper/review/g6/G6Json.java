package com.mscient.intrapaper.review.g6;

import com.mscient.intrapaper.data.StrictJsonParser;
import com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceReport;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Strict, bounded, local-only byte inputs. No source authentication is implied by a digest. */
final class G6Json {
    private G6Json() { }
    static final String ZERO = "0".repeat(64);
    static final long MAX_BYTES = 16L * 1024 * 1024;
    static byte[] bytes(Object o) { return (DailyOpsEvidenceReport.json(o) + "\n").getBytes(StandardCharsets.UTF_8); }
    static String hash(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static Map<String,Object> parse(byte[] b) throws IOException {
        Map<String,Object> parsed = obj(StrictJsonParser.parse(StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b)).toString()));
        bounded(parsed); return parsed;
    }
    private static void bounded(Object value) throws IOException {
        if(value instanceof Number) number(value);
        else if(value instanceof String s) require(s.length()<=16384,"String exceeds diagnostic resource bound");
        else if(value instanceof Map<?,?> m) for(var entry:m.entrySet()) { bounded(entry.getKey()); bounded(entry.getValue()); }
        else if(value instanceof List<?> a) { require(a.size()<=100000,"Array exceeds diagnostic resource bound"); for(Object v:a) bounded(v); }
    }
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object o) throws IOException {
        if (!(o instanceof Map<?,?>)) throw new IOException("Expected object");
        return (Map<String,Object>)o;
    }
    static List<?> list(Object o) throws IOException {
        if (!(o instanceof List<?> v)) throw new IOException("Expected array");
        if (v.size() > 100000) throw new IOException("Array exceeds diagnostic resource bound");
        return v;
    }
    static String str(Object o) throws IOException {
        if (!(o instanceof String s) || s.isBlank() || s.length() > 16384) throw new IOException("Expected nonempty bounded string");
        return s;
    }
    static String str(Map<String,Object> m, String k) throws IOException { return str(m.get(k)); }
    static void keys(Map<String,Object> m, String... fields) throws IOException {
        if (!m.keySet().equals(new HashSet<>(List.of(fields)))) throw new IOException("Exact fields required: " + String.join(",", fields));
    }
    static void schema(Map<String,Object> m, String type) throws IOException {
        require(("g6-" + type + "/v1").equals(m.get("schema")), "Unsupported " + type + " schema");
    }
    static void require(boolean ok, String reason) throws IOException { if (!ok) throw new IOException(reason); }
    static String digest(Object o) throws IOException {
        String s = str(o); require(s.matches("[0-9a-f]{64}"), "Expected lowercase SHA-256"); return s;
    }
    static BigDecimal number(Object o) throws IOException {
        if (!(o instanceof Number)) throw new IOException("Expected decimal number");
        BigDecimal n = new BigDecimal(o.toString());
        require(n.precision() <= 40 && Math.abs((long)n.scale()) <= 20, "Decimal exceeds bounded precision/scale"); return n;
    }
    static String choice(Object o, String... choices) throws IOException {
        String s = str(o); require(List.of(choices).contains(s), "Invalid enum: " + s); return s;
    }
    static void noLinks(Path p) throws IOException {
        localDisk(p);
        for (Path part = p.toAbsolutePath().normalize(); part != null; part = part.getParent()) {
            try {
                BasicFileAttributes a = Files.readAttributes(part, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                require(!a.isSymbolicLink() && !a.isOther(), "Symlink/reparse paths are forbidden");
            } catch(NoSuchFileException absent) { /* Existing ancestors still need validation. */ }
        }
    }
    /** Reject remote/UNC/device paths before any filesystem operation can contact a share. */
    static void localDisk(Path p) throws IOException {
        String raw=p.toString().replace('\\','/');
        require(!raw.startsWith("//") && !raw.startsWith("/??/") && !raw.contains("://"),"Only local disk paths are supported; network/device paths are forbidden");
        for(Path part:p) require(!part.toString().equals(".."),"Parent traversal in command paths is forbidden");
        String absolute=p.toAbsolutePath().toString().replace('\\','/');
        require(!absolute.startsWith("//"),"Network working directory is forbidden");
        if(java.io.File.separatorChar=='\\') {
            require(absolute.matches("[A-Za-z]:/.*"),"Local absolute drive path required");
            require(absolute.indexOf(':',2)<0,"Alternate data streams are forbidden");
        }
    }
    static Path local(Path root, String name) throws IOException {
        require(name.matches("[A-Za-z0-9][A-Za-z0-9._/-]*"), "Portable relative path required");
        for (String part : name.split("/", -1)) {
            require(!part.isEmpty() && !part.equals(".") && !part.equals("..") && !part.endsWith("."), "Path traversal/alias forbidden");
            String stem=part.split("\\.",2)[0].toUpperCase(Locale.ROOT);
            require(!stem.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]"),"Reserved device path forbidden");
        }
        Path p = root.resolve(name).normalize(); require(p.startsWith(root) && !p.equals(root), "Path escapes dossier");
        noLinks(p); return p;
    }
    static byte[] read(Path p) throws IOException {
        noLinks(p);
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) throw new NoSuchFileException(p.getFileName().toString());
        require(Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS), "Missing regular source: " + p.getFileName());
        require(Files.size(p) <= MAX_BYTES, "Source exceeds 16 MiB resource bound");
        try (var in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
            byte[] b = in.readNBytes((int)MAX_BYTES + 1); require(b.length <= MAX_BYTES, "Source exceeds 16 MiB resource bound"); return b;
        }
    }

    static final class Sources {
        final Path root;
        final Map<String,String> hashes = new TreeMap<>();
        final Map<String,byte[]> snapshots = new TreeMap<>();
        final Map<Object,String> fileKeys = new HashMap<>();
        final Map<String,String> expectedHashes = new TreeMap<>();
        long total;
        Sources(Path root) throws IOException { localDisk(root); this.root = root.toAbsolutePath().normalize(); noLinks(this.root); }
        byte[] load(String name, String expected) throws IOException {
            if(expected!=null) {
                digest(expected);
                String previous=expectedHashes.putIfAbsent(name,expected);
                require(previous==null || previous.equals(expected),"Contradictory source hashes: "+name);
            }
            if(snapshots.containsKey(name)) {
                if(expected!=null) require(expected.equals(hashes.get(name)),"Source digest mismatch: "+name);
                return snapshots.get(name);
            }
            Path p = local(root, name);
            hashes.putIfAbsent(name,null);
            if (!snapshots.containsKey(name)) {
                for (String old : snapshots.keySet()) require(!name.equalsIgnoreCase(old), "Aliased source names");
                Object key = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
                if (key != null) require(fileKeys.putIfAbsent(key,name) == null, "Hard-linked source aliases");
                else for (String old : snapshots.keySet()) require(!Files.isSameFile(p, local(root, old)), "Aliased source files");
                byte[] b = read(p); total += b.length; require(total <= 256L * 1024 * 1024, "Dossier exceeds 256 MiB diagnostic bound");
                snapshots.put(name, b); hashes.put(name, hash(b));
            }
            if (expected != null) require(digest(expected).equals(hashes.get(name)), "Source digest mismatch: " + name);
            return snapshots.get(name);
        }
        byte[] ref(Object value) throws IOException {
            Map<String,Object> r = obj(value); keys(r, "path", "sha256"); return load(str(r,"path"), digest(r.get("sha256")));
        }
        Map<String,Object> document(Object value) throws IOException { return parse(ref(value)); }
        void unchanged() throws IOException {
            for (var entry : hashes.entrySet()) {
                Path path=local(root,entry.getKey());
                if(entry.getValue()==null) require(!Files.exists(path,LinkOption.NOFOLLOW_LINKS),"Missing source appeared during review: "+entry.getKey());
                else require(hash(read(path)).equals(entry.getValue()), "Source changed during review: "+entry.getKey());
            }
        }
    }
}
