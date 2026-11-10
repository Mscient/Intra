package com.mscient.intrapaper.ops.governance;

import com.mscient.intrapaper.data.StrictJsonParser;
import com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceReport;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;

/** The shared parser and canonical writer, with bounded numbers and Unicode scalar validation. */
final class G7Json {
    private G7Json() { }
    static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    static Object parse(byte[] bytes) throws IOException {
        Object value = StrictJsonParser.parse(utf8(bytes));
        scalars(value, new int[1]);
        return value;
    }
    private static void scalars(Object v, int[] nodes) throws IOException {
        if (++nodes[0] > 200_000) throw new IOException("JSON resource limit exceeded");
        if (v instanceof String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= s.length() || !Character.isLowSurrogate(s.charAt(i))) throw new IOException("Unpaired Unicode surrogate");
                } else if (Character.isLowSurrogate(c)) throw new IOException("Unpaired Unicode surrogate");
            }
        } else if (v instanceof BigDecimal n) {
            if (n.precision() > 128 || Math.abs((long) n.scale()) > 128) throw new IOException("Numeric resource limit exceeded");
        } else if (v instanceof Map<?, ?> m) {
            if (m.size() > 10_000) throw new IOException("Object resource limit exceeded");
            for (var e : m.entrySet()) { scalars(e.getKey(), nodes); scalars(e.getValue(), nodes); }
        } else if (v instanceof List<?> a) {
            if (a.size() > 100_000) throw new IOException("Array resource limit exceeded");
            for (Object x : a) scalars(x, nodes);
        }
    }
    static byte[] bytes(Object value) { return (DailyOpsEvidenceReport.json(value) + "\n").getBytes(StandardCharsets.UTF_8); }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @SuppressWarnings("unchecked") static Map<String, Object> obj(Object x) { return (Map<String, Object>) x; }
    @SuppressWarnings("unchecked") static List<Object> list(Object x) { return (List<Object>) x; }
    static String str(Map<String, Object> m, String k) { return (String) m.get(k); }
    static Instant time(Object x) {
        Instant t = Instant.parse((String) x);
        if (!t.toString().equals(x)) throw new IllegalArgumentException("Timestamps must use canonical UTC Instant spelling: " + x);
        return t;
    }
    static long integer(Object x) {
        if (x instanceof BigDecimal n) return n.longValueExact();
        if (x instanceof Integer || x instanceof Long) return ((Number) x).longValue();
        throw new IllegalArgumentException("Exact integer required");
    }
    /** Public references carry no caller text, filenames, account IDs or financial values. */
    static String reference(String kind, Object value) {
        return value == null ? null : kind + "-" + hash(bytes(value));
    }
    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new TreeMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }
    static String signature(Map<String, Object> record) throws IOException {
        Object declaration = record.get("signature");
        if (declaration == null) return "ABSENT_UNAUTHENTICATED";
        Map<String, Object> s = obj(declaration);
        Map<String, Object> signed = new TreeMap<>(record); signed.remove("signature");
        try {
            byte[] keyBytes = Base64.getDecoder().decode(str(s, "public_key"));
            byte[] signatureBytes = Base64.getDecoder().decode(str(s, "value"));
            if (keyBytes.length != 44 || signatureBytes.length != 64) throw new IOException("Invalid Ed25519 lengths");
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key); verifier.update(bytes(signed));
            if (!verifier.verify(signatureBytes)) throw new IOException("Invalid Ed25519 signature");
            return "VALID_SIGNATURE_UNTRUSTED_KEY_IDENTITY_AND_TIME";
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            throw new IOException("Invalid Ed25519 signature declaration", invalid);
        }
    }
}
