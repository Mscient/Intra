package com.mscient.intrapaper.permission.coverage;

import com.mscient.intrapaper.data.StrictJsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Strict UTF-8 input and canonical, locale-independent JSON. Never prints input diagnostics. */
final class CoverageJson {
    private CoverageJson() { }
    static Map<String, Object> object(Object... fields) {
        Map<String, Object> result = new TreeMap<>();
        for (int i = 0; i < fields.length; i += 2) result.put((String) fields[i], fields[i + 1]);
        return result;
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) { return (List<Object>) value; }
    static String str(Map<String, Object> value, String field) { return (String) value.get(field); }
    static Object parse(byte[] bytes) throws IOException {
        return StrictJsonParser.parse(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
    }
    static byte[] read(Path path) throws IOException {
        return read(path, 8_388_608);
    }
    static byte[] readReport(Path path) throws IOException {
        return read(path, 134_217_728);
    }
    private static byte[] read(Path path, int limit) throws IOException {
        requireLocal(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit)
            throw new IOException("INPUT_UNREADABLE_OR_TOO_LARGE");
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("INPUT_TOO_LARGE");
            return bytes;
        }
    }
    static void requireLocal(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        // UNC/device/network syntax and all symlink ancestors are excluded. No network APIs are used.
        if (absolute.toString().startsWith("\\\\")) throw new IOException("NONLOCAL_PATH");
        for (Path part = absolute; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part) || (Files.exists(part, LinkOption.NOFOLLOW_LINKS)
                    && Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).isOther())) throw new IOException("INDIRECT_PATH");
        }
    }
    static Path resolve(Path input, String relative) throws IOException {
        if (relative == null || !relative.matches("[A-Za-z0-9_./-]+")) throw new IOException("INVALID_LOCAL_REFERENCE");
        Path p = Path.of(relative);
        if (p.isAbsolute() || Arrays.asList(relative.split("/")).contains("..")) throw new IOException("INVALID_LOCAL_REFERENCE");
        Path base = input.toAbsolutePath().normalize().getParent();
        Path resolved = base.resolve(p).normalize();
        if (!resolved.startsWith(base)) throw new IOException("INVALID_LOCAL_REFERENCE");
        requireLocal(resolved);
        return resolved;
    }
    static String hashFile(Path path) throws IOException {
        requireLocal(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("INPUT_UNREADABLE");
        MessageDigest digest = digest();
        try (var stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = stream.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String hash(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    static String token(String domain, String value) {
        return hash((domain + ":" + value).getBytes(StandardCharsets.UTF_8));
    }
    static byte[] bytes(Object value) { return (json(value) + "\n").getBytes(StandardCharsets.UTF_8); }
    /** All arrays in these contracts are sets. Sort recursively, but preserve duplicates for validation. */
    static Object normalized(Object value) {
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> result = new TreeMap<>();
            m.forEach((k, v) -> result.put((String) k, normalized(v)));
            return result;
        }
        if (value instanceof Collection<?> c)
            return c.stream().map(CoverageJson::normalized).sorted(Comparator.comparing(CoverageJson::json)).toList();
        return value;
    }
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) {
            StringBuilder out = new StringBuilder("\"");
            for (char ch : s.toCharArray()) {
                if (ch == '"' || ch == '\\') out.append('\\').append(ch);
                else if (ch < 32 || Character.isSurrogate(ch)) {
                    out.append("\\u");
                    String hex = Integer.toHexString(ch);
                    out.append("0".repeat(4 - hex.length())).append(hex);
                } else out.append(ch);
            }
            return out.append('"').toString();
        }
        if (value instanceof BigDecimal n) {
            if (n.precision() > 1024 || Math.abs((long) n.scale()) > 1024) throw new IllegalArgumentException("NUMBER_TOO_LARGE");
            return n.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> sorted = new TreeMap<>();
            m.forEach((k, v) -> sorted.put((String) k, v));
            return "{" + String.join(",", sorted.entrySet().stream().map(e -> json(e.getKey()) + ":" + json(e.getValue())).toList()) + "}";
        }
        if (value instanceof Collection<?> c) return "[" + String.join(",", c.stream().map(CoverageJson::json).toList()) + "]";
        throw new IllegalArgumentException("UNSUPPORTED_JSON_TYPE");
    }
}
