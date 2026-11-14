package com.mscient.intrapaper.ops.operationaldrill;

import com.mscient.intrapaper.ops.drill.DrillEvidence;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Bounded snapshots and portable paths shared by the runner and verifier. */
final class DrillFiles {
    static final long MAX_FILE = 16L * 1024 * 1024, MAX_BUNDLE = 128L * 1024 * 1024;
    private DrillFiles() { }
    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static String sha(byte[] bytes) { return DrillEvidence.sha(bytes); }
    static String ascii(byte[] bytes) {
        if (bytes.length == 0 || bytes[bytes.length - 1] != 10) throw new IllegalArgumentException("Final LF required");
        for (byte b : bytes) if (b != 10 && (b < 32 || b > 126)) throw new IllegalArgumentException("Printable ASCII and LF only");
        String value = new String(bytes, StandardCharsets.US_ASCII);
        if (value.contains("\n\n")) throw new IllegalArgumentException("Blank lines prohibited");
        return value;
    }
    static void portable(String name) {
        if (name.length() > 240 || !name.matches("[a-zA-Z0-9_-][a-zA-Z0-9_./-]*")
                || Arrays.stream(name.split("/", -1)).anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals("..") || s.endsWith(".")))
            throw new IllegalArgumentException("Nonportable relative path");
    }
    static void noLinks(Path path) throws IOException {
        for (Path p = path.toAbsolutePath().normalize(); p != null; p = p.getParent()) {
            if (Files.isSymbolicLink(p) || (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && Files.readAttributes(p,
                    java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()))
                throw new IOException("Linked/special paths prohibited");
        }
    }
    static byte[] read(Path file, long limit) throws IOException {
        noLinks(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > limit)
            throw new IOException("Missing, special or oversized file");
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = in.readNBytes(Math.toIntExact(limit + 1));
            if (bytes.length > limit) throw new IOException("File grew beyond limit");
            return bytes;
        }
    }
    static SortedMap<String, byte[]> snapshot(Path root) throws IOException {
        noLinks(root);
        TreeMap<String, byte[]> files = new TreeMap<>();
        long size = 0;
        try (var walk = Files.walk(root)) {
            var iterator = walk.iterator();
            int entries = 0;
            while (iterator.hasNext()) {
                Path p = iterator.next();
                if (++entries > 8192) throw new IOException("Too many bundle entries");
                noLinks(p);
                if (p.equals(root)) continue;
                String name = root.relativize(p).toString().replace('\\', '/');
                portable(name);
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    try (var children = Files.list(p)) {
                        if (children.findAny().isEmpty()) throw new IOException("Empty artifact directory");
                    }
                    continue;
                }
                byte[] bytes = read(p, MAX_FILE);
                size += bytes.length;
                if (size > MAX_BUNDLE) throw new IOException("Bundle exceeds 128 MiB");
                files.put(name, bytes);
            }
        }
        return files;
    }
    static void write(Path file, byte[] bytes) throws IOException {
        noLinks(file); Files.createDirectories(file.getParent()); Files.write(file, bytes);
    }
    static void write(Path file, String value) throws IOException { write(file, utf8(value)); }
    static void materialize(Path root, Map<String, byte[]> files) throws IOException {
        for (var e : files.entrySet()) { portable(e.getKey()); write(root.resolve(e.getKey()), e.getValue()); }
    }
    static void removeOwned(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize(); noLinks(absolute);
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing owned workspace");
        try (var walk = Files.walk(absolute)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (!p.toAbsolutePath().normalize().startsWith(absolute)) throw new IOException("Cleanup escapes workspace");
                Files.delete(p);
            }
        }
    }
    static String inventory(Map<String, byte[]> files) {
        StringBuilder out = new StringBuilder("path,sha256,bytes\n");
        files.forEach((name, bytes) -> out.append(name).append(',').append(sha(bytes)).append(',').append(bytes.length).append('\n'));
        return out.toString();
    }
}
