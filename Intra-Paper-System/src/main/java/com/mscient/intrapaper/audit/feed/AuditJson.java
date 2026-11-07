package com.mscient.intrapaper.audit.feed;

import com.mscient.intrapaper.data.StrictJsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Small strict schema/canonical JSON primitives; no dependencies or environment defaults. */
final class AuditJson {
    private AuditJson() { }
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected object");
        return (Map<String, Object>) value;
    }
    static Map<String, Object> parse(Path path) throws IOException {
        return object(StrictJsonParser.parse(Files.readString(path, StandardCharsets.UTF_8)));
    }
    static void keys(Map<String, Object> m, String... names) {
        if (!m.keySet().equals(Set.of(names))) throw new IllegalArgumentException("Expected exact fields " + List.of(names));
    }
    static String string(Object value) {
        if (!(value instanceof String s) || s.isBlank() || !s.equals(s.strip())
                || s.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff))
            throw new IllegalArgumentException("Expected nonblank trimmed text without controls or unpaired surrogates");
        return s;
    }
    static String str(Map<String, Object> m, String key) { return string(m.get(key)); }
    static List<?> list(Object value) {
        if (!(value instanceof List<?> l)) throw new IllegalArgumentException("Expected array");
        return l;
    }
    static long number(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Expected JSON number");
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Expected integer", e); }
    }
    static void version(Map<String, Object> m, String schema) {
        if (!schema.equals(str(m, "schema"))) throw new IllegalArgumentException("Unsupported schema; expected " + schema);
    }
    static String choice(Object value, String... options) {
        String s = string(value);
        if (!List.of(options).contains(s)) throw new IllegalArgumentException("Expected one of " + List.of(options));
        return s;
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static String digest(Object value) {
        String s = string(value);
        if (!s.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected lowercase SHA-256");
        return s;
    }
    static byte[] bytes(Object value) { return (json(value) + "\n").getBytes(StandardCharsets.UTF_8); }
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) {
            StringBuilder b = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '\"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> { if (c < 32) b.append(String.format(Locale.ROOT, "\\u%04x", (int)c)); else b.append(c); }
                }
            }
            return b.append('"').toString();
        }
        if (value instanceof Map<?, ?> m) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            m.forEach((k, v) -> sorted.put((String) k, v));
            return "{" + String.join(",", sorted.entrySet().stream().map(e -> json(e.getKey()) + ":" + json(e.getValue())).toList()) + "}";
        }
        if (value instanceof Collection<?> l) return "[" + String.join(",", l.stream().map(AuditJson::json).toList()) + "]";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IllegalArgumentException("Unsupported JSON value");
    }
}
