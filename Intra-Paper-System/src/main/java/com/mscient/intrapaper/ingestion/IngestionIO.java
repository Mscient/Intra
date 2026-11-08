package com.mscient.intrapaper.ingestion;

import com.mscient.intrapaper.data.StrictJsonParser;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.regex.Pattern;

/** Bounded local snapshots and deterministic JSON; errors never contain caller text. */
final class IngestionIO {
    static final int LIMIT = 16 * 1024 * 1024;
    private IngestionIO() { }
    static final class Invalid extends RuntimeException {
        Invalid(String code) { super(code); }
    }
    static void require(boolean value, String code) { if (!value) throw new Invalid(code); }
    static Map<String,Object> obj(Object... pairs) {
        Map<String,Object> m = new TreeMap<>();
        for (int i=0;i<pairs.length;i+=2) m.put((String)pairs[i], pairs[i+1]);
        return m;
    }
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object v) {
        require(v instanceof Map<?,?>, "OBJECT_REQUIRED"); return (Map<String,Object>)v;
    }
    static List<?> list(Object v) { require(v instanceof List<?>, "ARRAY_REQUIRED"); return (List<?>)v; }
    static String str(Map<String,Object> m, String key) {
        require(m.get(key) instanceof String, "STRING_REQUIRED"); return (String)m.get(key);
    }
    static void keys(Map<String,Object> m, String... fields) {
        require(m.keySet().equals(Set.of(fields)), "SCHEMA_FIELDS_INVALID");
    }
    static long integer(Object v, long min, long max) {
        require(v instanceof BigDecimal, "INTEGER_REQUIRED");
        try { long n=((BigDecimal)v).longValueExact(); require(n>=min && n<=max,"INTEGER_RANGE"); return n; }
        catch (ArithmeticException e) { throw new Invalid("INTEGER_RANGE"); }
    }
    static String id(String v) {
        require(v.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"), "IDENTIFIER_INVALID");
        safeText(v); return v;
    }
    // Raw and copied metadata have closed schemas too. Reject secret markers even in comments/references.
    private static final Pattern SECRET = Pattern.compile(
        "(?i)(authorization|bearer\\s|jwt|feed.?token|api.?key|totp|password|secret|cookie|(?:^|[^a-z])pin(?:[^a-z]|$)|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)");
    static void safeText(String text) {
        require(!SECRET.matcher(text).find(), "SECRET_LIKE_CONTENT");
    }
    static String utf8(byte[] bytes) throws IOException {
        try {
            String s=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            require(!s.startsWith("\ufeff"),"BOM_FORBIDDEN"); return s;
        } catch (CharacterCodingException e) { throw new IOException("INVALID_UTF8"); }
    }
    static Object parse(byte[] bytes) throws IOException { return StrictJsonParser.parse(utf8(bytes)); }
    static byte[] bytes(Object value) { return (json(value)+"\n").getBytes(StandardCharsets.UTF_8); }
    static String json(Object value) {
        if (value==null) return "null";
        if (value instanceof String s) {
            StringBuilder b=new StringBuilder("\"");
            for (int i=0;i<s.length();i++) {
                char c=s.charAt(i);
                if (c=='"' || c=='\\') b.append('\\').append(c);
                else if (c<32) b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
                else b.append(c);
            }
            return b.append('"').toString();
        }
        if (value instanceof Map<?,?> m) {
            List<String> fields=new ArrayList<>();
            for (String k: new TreeSet<>(map(m).keySet())) fields.add(json(k)+":"+json(m.get(k)));
            return "{"+String.join(",",fields)+"}";
        }
        if (value instanceof Collection<?> a) return "["+String.join(",",a.stream().map(IngestionIO::json).toList())+"]";
        if (value instanceof BigDecimal n) {
            require(n.precision()<=128 && Math.abs((long)n.scale())<=128,"NUMBER_RANGE_INVALID");
            return n.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        throw new IllegalArgumentException("SERIALIZATION_TYPE");
    }
    static String hash(byte[] b) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    static Path local(Path p) throws IOException {
        String s=p.toString();
        require(!s.startsWith("\\\\") && !s.startsWith("//") && !s.contains("://"),"NONLOCAL_PATH");
        for (Path part:p) require(!part.toString().equals("..") && !part.toString().contains(":"),"INDIRECT_PATH");
        Path a=p.toAbsolutePath().normalize();
        for (Path part=a;part!=null;part=part.getParent()) {
            if (Files.exists(part,LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes at=Files.readAttributes(part,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                require(!at.isSymbolicLink() && !at.isOther(),"INDIRECT_PATH");
            }
        }
        return a;
    }
    static byte[] read(Path p) throws IOException {
        return read(p,LIMIT);
    }
    static byte[] read(Path p,int limit) throws IOException {
        local(p); require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS),"INPUT_NOT_REGULAR");
        require(Files.size(p)<=limit,"INPUT_TOO_LARGE");
        try (var in=Files.newInputStream(p)) {
            byte[] b=in.readNBytes(limit+1); require(b.length<=limit,"INPUT_TOO_LARGE"); return b;
        }
    }
    static void removeStage(Path stage) throws IOException {
        // Only our fresh flat staging directories. Never traverse an input or published directory.
        try (var files=Files.list(stage)) { for (Path p:files.toList()) Files.delete(p); }
        Files.delete(stage);
    }
}
