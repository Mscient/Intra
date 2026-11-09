package com.mscient.intrapaper.admission;

import com.mscient.intrapaper.data.StrictJsonParser;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.time.*;
import java.util.*;

/** Strict deterministic JSON and local-path helpers for the G2 admission sidecar. */
final class AdmissionJson {
    static final int MAX_MANIFEST_BYTES = 1_048_576;
    static final int MAX_REPORT_BYTES = 16_777_216;
    private static final String ID = "[A-Za-z0-9][A-Za-z0-9_.-]{0,127}";
    private static final String RELATIVE = "[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*";
    private AdmissionJson() { }

    static Map<String, Object> parse(Path path) throws IOException {
        return object(parse(readBounded(path, MAX_MANIFEST_BYTES)));
    }

    static Object parse(byte[] bytes) throws IOException {
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return StrictJsonParser.parse(decoder.decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException | IllegalArgumentException invalid) {
            throw new IOException("Expected strict UTF-8 JSON", invalid);
        }
    }

    static byte[] readBounded(Path path, long maximum) throws IOException {
        if (maximum < 0 || maximum >= Integer.MAX_VALUE) throw new IllegalArgumentException("Unsupported read bound");
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            throw new IOException("Expected a regular local file");
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes((int) maximum + 1);
            if (bytes.length > maximum) throw new IOException("Input exceeds declared byte limit");
            return bytes;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) value;
    }

    static List<?> list(Object value) {
        if (!(value instanceof List<?> result)) throw new IllegalArgumentException("Expected JSON array");
        return result;
    }

    static void keys(Map<String, Object> value, String... expected) {
        if (!value.keySet().equals(Set.of(expected)))
            throw new IllegalArgumentException("Expected exact fields " + List.of(expected));
    }

    static String string(Object value) {
        if (!(value instanceof String text) || text.isBlank() || !text.equals(text.strip())
                || text.codePoints().anyMatch(c -> Character.isISOControl(c) || c >= 0xd800 && c <= 0xdfff))
            throw new IllegalArgumentException("Expected nonblank trimmed text without controls");
        return text;
    }

    static String optionalString(Object value) { return value == null ? null : string(value); }

    static String id(Object value) {
        String text = string(value);
        if (!text.matches(ID)) throw new IllegalArgumentException("Expected path-safe logical identifier");
        return text;
    }

    static String optionalId(Object value) { return value == null ? null : id(value); }

    static String choice(Object value, String... choices) {
        String text = string(value);
        if (!List.of(choices).contains(text)) throw new IllegalArgumentException("Expected one of " + List.of(choices));
        return text;
    }

    static String digest(Object value) {
        String text = string(value);
        if (!text.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected lowercase SHA-256");
        return text;
    }

    static String optionalDigest(Object value) { return value == null ? null : digest(value); }

    static long integer(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("Expected integer");
        try { return new BigDecimal(value.toString()).longValueExact(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Expected finite signed 64-bit integer", invalid); }
    }

    static Instant instant(Object value) {
        String text = string(value);
        try {
            Instant parsed = Instant.parse(text);
            if (!parsed.toString().equals(text)) throw new IllegalArgumentException("Timestamp must use canonical UTC form");
            return parsed;
        } catch (DateTimeException invalid) { throw new IllegalArgumentException("Expected canonical UTC timestamp", invalid); }
    }

    static Instant optionalInstant(Object value) { return value == null ? null : instant(value); }

    static LocalDate date(Object value) {
        String text = string(value);
        try {
            LocalDate parsed = LocalDate.parse(text);
            if (!parsed.toString().equals(text)) throw new IllegalArgumentException("Date must be canonical ISO form");
            return parsed;
        } catch (DateTimeException invalid) { throw new IllegalArgumentException("Expected ISO calendar date", invalid); }
    }

    static String relative(Object value) {
        String text = string(value);
        if (!text.matches(RELATIVE) || Arrays.asList(text.split("/")).contains(".")
                || Arrays.asList(text.split("/")).contains("..") || text.contains(":"))
            throw new IllegalArgumentException("Expected a safe relative local path");
        for (String part : text.split("/")) if (part.endsWith(".")
                || part.matches("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:\\..*)?"))
            throw new IllegalArgumentException("Windows alias/device path is unsupported");
        return text;
    }

    static Path unresolved(Path root, String relative) {
        String checked = relative(relative);
        Path result = root.resolve(checked).normalize();
        if (!result.startsWith(root.normalize())) throw new IllegalArgumentException("Path escapes input bundle");
        return result;
    }

    static Path safeFile(Path root, String relative, long maximum) throws IOException {
        Path candidate = unresolved(root, relative);
        rejectLinks(candidate);
        Path realRoot = root.toRealPath();
        Path real = candidate.toRealPath();
        if (!real.startsWith(realRoot) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Input escapes bundle or is not a regular file");
        if (Files.size(candidate) > maximum) throw new IOException("Input exceeds declared byte limit");
        return candidate;
    }

    static Path safeDirectory(Path root, String relative) throws IOException {
        Path candidate = unresolved(root, relative);
        rejectLinks(candidate);
        Path realRoot = root.toRealPath();
        Path real = candidate.toRealPath();
        if (!real.startsWith(realRoot) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Artifact directory escapes bundle or is not a directory");
        return candidate;
    }

    static void rejectLinks(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Linked inputs are unsupported");
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isOther()) throw new IOException("Reparse/device inputs are unsupported");
            }
        }
    }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static byte[] bytes(Object value) { return (json(value) + "\n").getBytes(StandardCharsets.UTF_8); }

    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) {
            StringBuilder output = new StringBuilder("\"");
            for (char c : text.toCharArray()) {
                switch (c) {
                    case '\"' -> output.append("\\\"");
                    case '\\' -> output.append("\\\\");
                    case '\n' -> output.append("\\n");
                    case '\r' -> output.append("\\r");
                    case '\t' -> output.append("\\t");
                    default -> { if (c < 32) output.append(String.format(Locale.ROOT, "\\u%04x", (int)c)); else output.append(c); }
                }
            }
            return output.append('"').toString();
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put((String)key, item));
            return "{" + String.join(",", sorted.entrySet().stream()
                    .map(entry -> json(entry.getKey()) + ":" + json(entry.getValue())).toList()) + "}";
        }
        if (value instanceof Collection<?> values)
            return "[" + String.join(",", values.stream().map(AdmissionJson::json).toList()) + "]";
        if (value instanceof Number number) {
            BigDecimal finite = new BigDecimal(number.toString());
            return finite.toPlainString();
        }
        if (value instanceof Boolean) return value.toString();
        throw new IllegalArgumentException("Unsupported JSON value");
    }
}
