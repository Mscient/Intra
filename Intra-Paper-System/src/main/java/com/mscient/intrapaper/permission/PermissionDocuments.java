package com.mscient.intrapaper.permission;

import com.mscient.intrapaper.data.StrictJsonParser;
import com.mscient.intrapaper.provenance.RunConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Strict, closed schemas. No clause, wildcard, or review decision is inferred. */
final class PermissionDocuments {
    private PermissionDocuments() { }
    enum Kind { OWNER_REVIEWED, SYNTHETIC_TEST }
    enum Right { ALLOWED, DENIED, UNKNOWN }
    enum Decision { APPROVED, DENIED, REVOKED, PENDING }
    static final Set<String> OUTPUT_USES = Set.of("INTERNAL_RESEARCH", "PUBLICATION", "REDISTRIBUTION", "COMMERCIAL");

    record Manifest(String id, String source, String product, Set<String> instruments,
                    LocalDate dataFrom, LocalDate dataThrough, Instant acquiredAt,
                    Instant useFrom, Instant useUntil, Instant retainUntil, String storage,
                    Set<String> outputUses, Map<String, String> files, String permissionId,
                    String permissionHash, Kind kind) { }
    record Grant(String id, String source, String product, Set<String> instruments,
                 LocalDate dataFrom, LocalDate dataThrough, Right simulation, Right retention,
                 Instant retainUntil, Set<String> storage, Right derived, Set<String> outputUses,
                 String reference, String evidenceHash, String owner, String ownerReference,
                 String reviewer, String qualificationReference, String reviewReference,
                 Instant reviewedAt, Decision decision, Instant effectiveFrom, Instant effectiveUntil,
                 Kind kind) { }
    record Document(Map<String, Object> fields, String sha256) { }

    static Document read(Path path) throws IOException {
        // Read and hash the same bytes. The size bound also limits deeply nested/untrusted input.
        byte[] bytes;
        try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(1_048_577); }
        if (bytes.length > 1_048_576) throw new IOException("Permission document exceeds 1 MiB");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        return new Document(object(StrictJsonParser.parse(text)), RunConfig.sha256(bytes));
    }

    static Manifest manifest(Document document) {
        var p = document.fields();
        keys(p, "schema", "dataset_id", "source", "product", "instruments", "data_from", "data_through",
                "acquired_at_utc", "intended_use", "use_from_utc", "use_until_utc", "retain_until_utc",
                "storage_location", "derived_output_uses", "input_file_sha256", "permission_record_id",
                "permission_record_sha256", "evidence_kind");
        equal(p, "schema", "DATA_PERMISSION_MANIFEST_V1");
        equal(p, "intended_use", "RESEARCH_SIMULATION");
        var from = date(p, "data_from");
        var through = date(p, "data_through");
        dateRange(from, through);
        var start = instant(p, "use_from_utc");
        var end = instant(p, "use_until_utc");
        timeRange(start, end);
        var retain = instant(p, "retain_until_utc");
        var acquired = instant(p, "acquired_at_utc");
        if (acquired.isAfter(start) || retain.isBefore(end)) invalid("manifest time order");
        Map<String, String> files = new TreeMap<>();
        object(p.get("input_file_sha256")).forEach((k, v) -> {
            id(k); files.put(k, hash(v));
        });
        if (files.isEmpty()) invalid("empty input hashes");
        return new Manifest(id(text(p, "dataset_id")), text(p, "source"), text(p, "product"),
                ids(p, "instruments"), from, through, acquired, start, end, retain,
                text(p, "storage_location"), uses(p), Map.copyOf(files), id(text(p, "permission_record_id")),
                hash(p.get("permission_record_sha256")), Kind.valueOf(text(p, "evidence_kind")));
    }

    static Grant grant(Document document) {
        var p = document.fields();
        keys(p, "schema", "record_id", "source", "product", "instruments", "data_from", "data_through",
                "simulation_right", "retention_right", "retain_until_utc", "storage_locations",
                "derived_output_right", "derived_output_uses", "permission_reference", "permission_evidence_sha256",
                "owner", "owner_attestation_reference", "reviewer", "reviewer_qualification_reference",
                "review_approval_reference", "reviewed_at_utc", "decision", "effective_from_utc",
                "effective_until_utc", "evidence_kind");
        equal(p, "schema", "DATA_PERMISSION_RECORD_V1");
        var from = date(p, "data_from");
        var through = date(p, "data_through");
        dateRange(from, through);
        var start = instant(p, "effective_from_utc");
        var end = instant(p, "effective_until_utc");
        timeRange(start, end);
        var retain = instant(p, "retain_until_utc");
        if (retain.isBefore(start)) invalid("retention precedes grant");
        return new Grant(id(text(p, "record_id")), text(p, "source"), text(p, "product"),
                ids(p, "instruments"), from, through, Right.valueOf(text(p, "simulation_right")),
                Right.valueOf(text(p, "retention_right")), retain, strings(p, "storage_locations"),
                Right.valueOf(text(p, "derived_output_right")), uses(p), text(p, "permission_reference"),
                hash(p.get("permission_evidence_sha256")), text(p, "owner"), text(p, "owner_attestation_reference"),
                text(p, "reviewer"), text(p, "reviewer_qualification_reference"), text(p, "review_approval_reference"),
                instant(p, "reviewed_at_utc"), Decision.valueOf(text(p, "decision")), start, end,
                Kind.valueOf(text(p, "evidence_kind")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) invalid("expected object");
        return (Map<String, Object>) value;
    }
    private static void keys(Map<String, Object> p, String... keys) {
        if (!p.keySet().equals(Set.of(keys))) invalid("missing or unknown fields");
    }
    private static String text(Map<String, Object> p, String key) { return string(p.get(key)); }
    private static String string(Object value) {
        if (!(value instanceof String s) || s.isBlank() || !s.equals(s.strip()) || s.length() > 2048
                || s.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '*' || c == '?' || (c >= 0xD800 && c <= 0xDFFF))) {
            invalid("empty, untrimmed, wildcard, or invalid text");
        }
        return (String) value;
    }
    private static String id(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) invalid("invalid logical ID");
        return value;
    }
    static String hash(Object value) {
        String s = string(value);
        if (!s.matches("[0-9a-f]{64}")) invalid("invalid SHA-256");
        return s;
    }
    private static Set<String> strings(Map<String, Object> p, String key) {
        if (!(p.get(key) instanceof java.util.List<?>)) invalid("expected array");
        var values = (java.util.List<?>) p.get(key);
        var result = new TreeSet<String>();
        for (var value : values) if (!result.add(string(value))) invalid("duplicate value");
        if (result.isEmpty()) invalid("empty scope");
        return Set.copyOf(result);
    }
    private static Set<String> ids(Map<String, Object> p, String key) {
        var result = strings(p, key); result.forEach(PermissionDocuments::id); return result;
    }
    private static Set<String> uses(Map<String, Object> p) {
        var result = strings(p, "derived_output_uses");
        if (!OUTPUT_USES.containsAll(result)) invalid("unknown derived-output use");
        return result;
    }
    private static LocalDate date(Map<String, Object> p, String key) {
        String s = text(p, key); var value = LocalDate.parse(s);
        if (!value.toString().equals(s)) invalid("noncanonical date"); return value;
    }
    private static Instant instant(Map<String, Object> p, String key) {
        String s = text(p, key); var value = Instant.parse(s);
        if (!value.toString().equals(s)) invalid("timestamp must be canonical UTC"); return value;
    }
    private static void dateRange(LocalDate from, LocalDate through) {
        if (from.isAfter(through)) invalid("reversed date scope");
    }
    private static void timeRange(Instant from, Instant until) {
        if (!from.isBefore(until)) invalid("empty/reversed effective period");
    }
    private static void equal(Map<String, Object> p, String key, String expected) {
        if (!expected.equals(text(p, key))) invalid("unsupported " + key);
    }
    private static void invalid(String reason) { throw new IllegalArgumentException(reason); }
}
