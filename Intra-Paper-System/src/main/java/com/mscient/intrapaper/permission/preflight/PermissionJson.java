package com.mscient.intrapaper.permission.preflight;

import com.mscient.intrapaper.data.StrictJsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Exact UTF-8 input snapshots and stable, sorted-key UTF-8/LF output. */
final class PermissionJson {
    private PermissionJson() { }
    static Object parse(byte[] bytes) throws IOException {
        return StrictJsonParser.parse(StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString());
    }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static byte[] bytes(Object value) { return (encode(value) + "\n").getBytes(StandardCharsets.UTF_8); }
    static String encode(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) {
            StringBuilder out = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                if (c == '"' || c == '\\') out.append('\\').append(c);
                else if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int)c));
                else out.append(c);
            }
            return out.append('"').toString();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put((String)k, v));
            return "{" + String.join(",", sorted.entrySet().stream()
                    .map(e -> encode(e.getKey()) + ":" + encode(e.getValue())).toList()) + "}";
        }
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(PermissionJson::encode).toList()) + "]";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IllegalArgumentException("Unsupported JSON value");
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>)value : null;
    }
}
