package com.mscient.intrapaper.g5;

import com.mscient.intrapaper.data.StrictJsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded local snapshots, strict schemas and immutable publication. No network or processes. */
public final class G5Files {
    private G5Files() { }
    public static final String ZERO = "0".repeat(64);
    static final int MAX_FILE = 16 * 1024 * 1024;
    static IOException invalid() { return new IOException("G5_INVALID_SCHEMA_OR_IDENTITY"); }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static String decode(byte[] bytes) throws IOException {
        String s = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (s.indexOf('\ufeff') >= 0 || s.indexOf('\0') >= 0 || s.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff)) throw invalid();
        return s;
    }
    static Map<String,Object> json(byte[] bytes) throws IOException { return object(StrictJsonParser.parse(decode(bytes))); }
    @SuppressWarnings("unchecked")
    static Map<String,Object> object(Object o) throws IOException {
        if (!(o instanceof Map<?,?>)) throw invalid();
        return (Map<String,Object>) o;
    }
    static void keys(Map<String,Object> m, String... keys) throws IOException {
        if (!m.keySet().equals(new TreeSet<>(List.of(keys)))) throw invalid();
    }
    static String string(Map<String,Object> m, String k) throws IOException {
        if (!(m.get(k) instanceof String s) || s.length() > 4096 || !s.equals(s.strip()) || s.codePoints().anyMatch(c -> c < 32 || c == 127)) throw invalid();
        return s;
    }
    static String digest(String s) throws IOException { if (!s.matches("[0-9a-f]{64}") || s.equals(ZERO)) throw invalid(); return s; }
    static String ref(String s) throws IOException { if (!s.matches("ref-[0-9a-f]{32}")) throw invalid(); return s; }
    static String id(String s) throws IOException { if (!s.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw invalid(); return s; }
    static List<?> list(Map<String,Object> m, String k) throws IOException {
        if (!(m.get(k) instanceof List<?> a) || a.size() > 512) throw invalid(); return a;
    }
    static long integer(Map<String,Object> m, String k) throws IOException {
        if (!(m.get(k) instanceof BigDecimal n)) throw invalid();
        try { long v = n.longValueExact(); if (v < 0) throw invalid(); return v; }
        catch (ArithmeticException e) { throw invalid(); }
    }
    static String relative(String s) throws IOException {
        if (!s.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*") || s.length() > 512) throw invalid();
        for (String part : s.split("/")) if (part.equals(".") || part.equals("..") || part.endsWith(".") ||
                part.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) throw invalid();
        return s;
    }
    static Path safe(Path p, boolean dir) throws IOException {
        Path a = p.toAbsolutePath().normalize();
        for (Path q = a; q != null; q = q.getParent())
            if (Files.isSymbolicLink(q) || Files.exists(q, LinkOption.NOFOLLOW_LINKS) && !q.toRealPath().equals(q)) throw new IOException("G5_UNSAFE_PATH");
        if (dir ? !Files.isDirectory(a, LinkOption.NOFOLLOW_LINKS) : !Files.isRegularFile(a, LinkOption.NOFOLLOW_LINKS)) throw new IOException("G5_MISSING_PATH");
        return a;
    }
    static byte[] read(Path p) throws IOException {
        safe(p, false);
        return readBounded(p);
    }
    private static byte[] readBounded(Path p) throws IOException {
        if (Files.size(p) > MAX_FILE) throw invalid();
        byte[] b;
        try (var in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) { b = in.readNBytes(MAX_FILE + 1); }
        if (b.length > MAX_FILE) throw invalid(); return b;
    }
    static SortedMap<String,byte[]> snapshot(Path root) throws IOException {
        root = safe(root, true);
        SortedMap<String,byte[]> out = new TreeMap<>(); Set<String> folded = new HashSet<>(); long total = 0;
        try (var walk = Files.walk(root)) {
            Iterator<Path> iterator=walk.iterator(); int entries=0;
            while (iterator.hasNext()) {
                if (++entries>2048) throw invalid();
                Path p=iterator.next();
                if (Files.isSymbolicLink(p) || !p.toRealPath().equals(p)) throw new IOException("G5_UNSAFE_PATH");
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) continue;
                String name = relative(root.relativize(p).toString().replace('\\','/'));
                if (!folded.add(name.toLowerCase(Locale.ROOT)) || out.size() >= 1024) throw invalid();
                byte[] b = readBounded(p); total += b.length; if (total > 128L * 1024 * 1024) throw invalid(); out.put(name,b);
            }
        }
        return out;
    }
    static String treeHash(Map<String,byte[]> files) {
        StringBuilder s = new StringBuilder("INTRA_G5_TREE_V1\n");
        new TreeMap<>(files).forEach((k,v) -> s.append(k).append('\t').append(hash(v)).append('\n'));
        return hash(s.toString().getBytes(StandardCharsets.UTF_8));
    }
    /** Canonical ASCII JSON: UTF-8 text bytes never depend on locale, timezone or iteration order. */
    public static byte[] bytes(Object value) throws IOException { return (canonical(value) + "\n").getBytes(StandardCharsets.UTF_8); }
    static String sortKey(Map<String,Object> value) {
        try { return canonical(value); } catch (IOException e) { throw new IllegalStateException(e); }
    }
    static String canonical(Object v) throws IOException {
        if (v == null) return "null";
        if (v instanceof String s) {
            StringBuilder b = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                if (c == '"' || c == '\\') b.append('\\').append(c);
                else if (c < 32 || c > 126) b.append(String.format(Locale.ROOT,"\\u%04x",(int)c)); else b.append(c);
            }
            return b.append('"').toString();
        }
        if (v instanceof Boolean) return v.toString();
        if (v instanceof Number n) return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        if (v instanceof Map<?,?> m) {
            List<String> parts = new ArrayList<>();
            for (var e : new TreeMap<>(m).entrySet()) parts.add(canonical(e.getKey()) + ":" + canonical(e.getValue()));
            return "{" + String.join(",",parts) + "}";
        }
        if (v instanceof Collection<?> a) { List<String> parts = new ArrayList<>(); for (Object o : a) parts.add(canonical(o)); return "[" + String.join(",",parts) + "]"; }
        throw invalid();
    }
    @FunctionalInterface interface Publisher { void move(Path from, Path to) throws IOException; }
    public static void publish(byte[] bytes, Path target) throws IOException {
        publish(bytes,target,(from,to) -> Files.createLink(to,from));
    }
    // An atomic hard-link publication fails if the destination exists, including a racing writer.
    // Filesystems without this capability fail closed; no non-atomic fallback is permitted.
    static void publish(byte[] bytes, Path target, Publisher publication) throws IOException {
        Path a = target.toAbsolutePath().normalize(); Path parent = safe(a.getParent(),true);
        if (Files.exists(a, LinkOption.NOFOLLOW_LINKS)) throw new IOException("G5_REFUSE_OVERWRITE");
        Path temp = Files.createTempFile(parent,".g5-stage-",".tmp");
        try {
            try (FileChannel f = FileChannel.open(temp,StandardOpenOption.WRITE)) {
                ByteBuffer b = ByteBuffer.wrap(bytes); while (b.hasRemaining()) f.write(b); f.force(true);
            }
            publication.move(temp,a);
        } finally { Files.deleteIfExists(temp); }
    }
}
