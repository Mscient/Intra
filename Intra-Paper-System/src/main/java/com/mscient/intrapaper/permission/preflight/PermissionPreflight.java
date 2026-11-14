package com.mscient.intrapaper.permission.preflight;

import com.mscient.intrapaper.data.StrictJsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.jar.JarFile;

/** Standalone declaration checker. It never fetches or authenticates a contract or promotes a candidate. */
public final class PermissionPreflight {
    public static final String VERSION = "dataset-permission-preflight/1.0.0";
    private static final int MAX_INPUT_BYTES = 1024 * 1024;
    private PermissionPreflight() { }
    public enum Status {
        ALLOWED(0), DENIED(2), INCOMPLETE(3);
        private final int exitCode;
        Status(int exitCode) { this.exitCode = exitCode; }
        public int exitCode() { return exitCode; }
    }
    public record Reason(Status status, String code, String field, String detail) {
        Map<String, Object> json() { return Map.of("status", status.name(), "code", code, "field", field, "detail", detail); }
    }
    public static final class Decision {
        private final Status status;
        private final List<Reason> reasons;
        private final Map<String, Object> payload;
        private final byte[] bytes;
        private Decision(List<Reason> issues, Map<String, Object> base) {
            reasons = issues.stream().distinct().sorted(Comparator.comparing(Reason::field)
                    .thenComparing(Reason::code).thenComparing(Reason::detail)).toList();
            status = reasons.stream().anyMatch(r -> r.status == Status.DENIED) ? Status.DENIED
                    : reasons.isEmpty() ? Status.ALLOWED : Status.INCOMPLETE;
            payload = new TreeMap<>(base);
            payload.put("status", status.name());
            payload.put("reasons", reasons.stream().map(Reason::json).toList());
            payload.put("decision_id", PermissionJson.hash(PermissionJson.bytes(payload)));
            bytes = PermissionJson.bytes(payload);
        }
        public Status status() { return status; }
        public List<Reason> reasons() { return reasons; }
        public byte[] bytes() { return bytes.clone(); }
        public String id() { return (String)payload.get("decision_id"); }
        private Decision withDataset(String hash, Reason problem) {
            Map<String, Object> next = new TreeMap<>(payload);
            next.remove("decision_id"); next.remove("status"); next.remove("reasons");
            next.put("dataset_verification", hash == null ? "UNVERIFIED" : "EXACT_BYTES");
            next.put("observed_dataset_sha256", hash);
            List<Reason> issues = new ArrayList<>(reasons);
            if (problem != null) issues.add(problem);
            return new Decision(issues, next);
        }
    }
    private record Snapshot(Path path, byte[] bytes, Map<String, Object> object) { }
    private record Evaluation(Decision decision, List<Snapshot> snapshots, Map<String, Object> manifest) { }

    public static Decision check(Path manifest, Path intendedUse, Path permissions, LocalDate asOf) throws IOException {
        return evaluate(manifest, intendedUse, permissions, asOf).decision;
    }

    /** Hash a caller-authorized local dataset only after declared rights pass. No dataset parser is invoked. */
    public static Decision checkDataset(Path manifest, Path intendedUse, Path permissions, LocalDate asOf, Path dataset) throws IOException {
        Evaluation evaluation = evaluate(manifest, intendedUse, permissions, asOf);
        if (evaluation.decision.status != Status.ALLOWED) return evaluation.decision;
        String actual;
        try (InputStream input = regularInput(dataset)) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            actual = java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        catch (IOException unavailable) {
            return evaluation.decision.withDataset(null, new Reason(Status.INCOMPLETE, "DATASET_UNAVAILABLE", "dataset", "Local dataset could not be read"));
        }
        List<Reason> changed = new ArrayList<>(); recheck(evaluation.snapshots, changed);
        Decision result = evaluation.decision.withDataset(actual, actual.equals(evaluation.manifest.get("dataset_sha256")) ? null
                : new Reason(Status.DENIED, "DATASET_HASH_MISMATCH", "dataset", "Observed bytes do not match the manifest"));
        for (Reason reason : changed) result = result.withDataset(actual, reason);
        return result;
    }

    /**
     * In-process before-use gate for a caller's already-authorized, frozen dataset bytes.
     * The callback receives a private copy of the exact checked bytes, never an unchecked path.
     * No callback on DENIED/INCOMPLETE, hash mismatch, or changing permission inputs.
     */
    public static <T> T withAllowedDataset(Path manifest, Path intendedUse, Path permissions, LocalDate asOf,
                                          byte[] frozenDataset, Function<byte[], T> research) throws IOException {
        Evaluation e = evaluate(manifest, intendedUse, permissions, asOf);
        if (e.decision.status != Status.ALLOWED) throw new IOException("Permission preflight: " + e.decision.status + " " + e.decision.id());
        byte[] snapshot = frozenDataset.clone();
        if (!PermissionJson.hash(snapshot).equals(e.manifest.get("dataset_sha256"))) throw new IOException("Permission preflight: DATASET_HASH_MISMATCH");
        List<Reason> changed = new ArrayList<>(); recheck(e.snapshots, changed);
        if (!changed.isEmpty()) throw new IOException("Permission preflight: INPUT_CHANGED");
        return research.apply(snapshot);
    }

    private static Evaluation evaluate(Path manifest, Path use, Path evidence, LocalDate asOf) throws IOException {
        if (asOf == null || asOf.getYear() < 1 || asOf.getYear() > 9999) throw new IllegalArgumentException("Explicit as-of date is required");
        List<Reason> reasons = new ArrayList<>(); Map<String, Object> hashes = new TreeMap<>();
        List<Snapshot> snapshots = new ArrayList<>();
        Snapshot m = read(manifest, "dataset-manifest", reasons, hashes);
        Snapshot u = read(use, "intended-use", reasons, hashes);
        Snapshot p = read(evidence, "permission-evidence", reasons, hashes);
        for (Snapshot s : new Snapshot[]{m, u, p}) if (s != null) snapshots.add(s);
        boolean mv = validate("dataset-manifest", m, reasons);
        boolean uv = validate("intended-use", u, reasons);
        boolean pv = validate("permission-evidence", p, reasons);
        if (mv && uv) matchUse(m.object, u.object, (String)hashes.get("dataset-manifest"), asOf, reasons);
        if (mv && pv) {
            Map<String, Object> bundle = p.object;
            if (!bundle.get("classification").equals(m.object.get("classification"))) deny(reasons, "CLASSIFICATION_MISMATCH", "permission-evidence.classification", "Evidence classification must match the dataset");
            if (!bundle.get("completeness").equals("COMPLETE")) incomplete(reasons, "EVIDENCE_SET_INCOMPLETE", "permission-evidence.completeness", "Owner has not declared all applicable records supplied");
            List<Map<String, Object>> records = records(bundle);
            TreeSet<String> ids = new TreeSet<>(); TreeSet<String> effects = new TreeSet<>();
            for (int i = 0; i < records.size(); i++) {
                Map<String, Object> record = records.get(i); String prefix = "permission-evidence.records[" + i + "]";
                if (!ids.add(s(record, "record_id"))) deny(reasons, "DUPLICATE_RECORD", prefix + ".record_id", "Duplicate record ID; no precedence is inferred");
                effects.add(s(record, "effect"));
                binding(record, (String)hashes.get("dataset-manifest"), prefix, reasons);
                if (s(record, "effect").equals("DENY")) deny(reasons, "EXPLICIT_DENIAL", prefix + ".effect", "Applicable record explicitly denies use");
                if (s(record, "record_state").equals("REVOKED")) deny(reasons, "PERMISSION_REVOKED", prefix + ".record_state", "Record is revoked");
                if (s(record, "record_state").equals("AMBIGUOUS")) incomplete(reasons, "AMBIGUOUS_RECORD", prefix + ".record_state", "Owner/reviewer must resolve ambiguity");
                for (String approval : List.of("owner_approval", "review_status")) {
                    if (s(record, approval).equals("REJECTED")) deny(reasons, "REVIEW_REJECTED", prefix + "." + approval, "Approval was rejected");
                    if (s(record, approval).equals("PENDING")) incomplete(reasons, "REVIEW_PENDING", prefix + "." + approval, "Explicit owner and qualified-reviewer approval declarations are required");
                }
                range(record, "data_start", "data_end", prefix, reasons);
                range(record, "effective_from", "effective_through", prefix, reasons);
                range(record, "effective_from", "retain_until", prefix, reasons);
                if (date(record, "reviewed_on").isAfter(asOf)) incomplete(reasons, "REVIEW_NOT_YET_EFFECTIVE", prefix + ".reviewed_on", "Review date follows the explicit assessment date");
                if (asOf.isBefore(date(record, "effective_from")) || asOf.isAfter(date(record, "effective_through"))) deny(reasons, "PERMISSION_NOT_EFFECTIVE", prefix, "Assessment date falls outside inclusive permission effective dates");
                if (uv) matchPermission(m.object, u.object, record, prefix, reasons);
            }
            if (effects.size() > 1) deny(reasons, "CONTRADICTORY_RECORDS", "permission-evidence.records", "ALLOW and DENY coexist; no latest-record or grant-wins rule");
        }
        recheck(snapshots, reasons);
        Map<String, Object> report = new TreeMap<>();
        report.put("schema_version", "1"); report.put("checker_version", VERSION);
        report.put("checker_version_sha256", PermissionJson.hash(VERSION.getBytes(StandardCharsets.UTF_8)));
        report.put("checker_code_sha256", codeHash()); report.put("input_sha256", hashes);
        report.put("as_of", asOf.toString()); report.put("evidence_basis", "CALLER_DECLARED_PERMISSION_POINTERS");
        report.put("contract_validity_verified", false); report.put("production_approved", false); report.put("g2_closed", false);
        report.put("dataset_verification", "DECLARATION_ONLY"); report.put("observed_dataset_sha256", null);
        return new Evaluation(new Decision(reasons, report), snapshots, mv ? m.object : null);
    }
    private static void matchUse(Map<String, Object> m, Map<String, Object> u, String hash, LocalDate asOf, List<Reason> out) {
        binding(u, hash, "intended-use", out);
        for (String field : List.of("source_id", "product_id", "data_type")) equal(m, u, field, "intended-use", out);
        subset(list(m, "instruments"), list(u, "instruments"), "intended-use.instruments", out);
        range(m, "data_start", "data_end", "dataset-manifest", out);
        range(u, "data_start", "data_end", "intended-use", out);
        range(u, "use_start", "use_end", "intended-use", out);
        range(u, "retention_start", "use_start", "intended-use", out);
        range(u, "use_end", "delete_by", "intended-use", out);
        covers(m, "data_start", "data_end", u, "data_start", "data_end", "intended-use.data_dates", "DATA_DATE_OUT_OF_SCOPE", out);
        if (asOf.isBefore(date(u, "use_start")) || asOf.isAfter(date(u, "use_end"))) deny(out, "ASSESSMENT_OUTSIDE_USE", "intended-use", "Assessment date must be within the declared research-use interval");
        for (String field : List.of("derived_outputs", "sharing_recipients", "shared_material")) noneExclusive(u, field, "intended-use", out);
        boolean noSharing = s(u, "sharing_mode").equals("NONE");
        for (String field : List.of("sharing_recipients", "shared_material"))
            if (noSharing != list(u, field).equals(List.of("NONE"))) deny(out, "CONTRADICTORY_USE", "intended-use." + field, "NONE sharing requires only NONE recipients/material; actual sharing requires explicit recipients/material");
        for (String material : list(u, "shared_material")) if (!material.equals("NONE") && !material.equals("RAW_DATA") && !list(u, "derived_outputs").contains(material))
            deny(out, "CONTRADICTORY_USE", "intended-use.shared_material", "Shared derived material must be declared in derived_outputs: " + material);
    }
    private static void matchPermission(Map<String, Object> m, Map<String, Object> u, Map<String, Object> p, String prefix, List<Reason> out) {
        for (String field : List.of("source_id", "product_id", "data_type")) equal(m, p, field, prefix, out);
        // Permission must cover the entire frozen dataset, even when the strategy requests a subset.
        subset(list(p, "instruments"), list(m, "instruments"), prefix + ".instruments", out);
        covers(p, "data_start", "data_end", m, "data_start", "data_end", prefix + ".data_dates", "DATA_DATE_OUT_OF_SCOPE", out);
        covers(p, "effective_from", "effective_through", u, "retention_start", "use_end", prefix + ".effective_dates", "USE_DATE_OUT_OF_SCOPE", out);
        if (date(p, "reviewed_on").isAfter(date(u, "use_start"))) incomplete(out, "REVIEW_AFTER_USE_START", prefix + ".reviewed_on", "Review must precede or coincide with research use");
        for (String field : List.of("purposes", "storage_locations", "derived_outputs", "sharing_recipients", "shared_material")) subset(list(p, field), list(u, field), prefix + "." + field, out);
        subset(list(p, "sharing_modes"), List.of(s(u, "sharing_mode")), prefix + ".sharing_modes", out);
        long days = ChronoUnit.DAYS.between(date(u, "retention_start"), date(u, "delete_by")) + 1;
        if (days > ((java.math.BigDecimal)p.get("max_retention_days")).longValueExact() || date(u, "delete_by").isAfter(date(p, "retain_until")))
            deny(out, "RETENTION_CONFLICT", prefix + ".retention", "Inclusive retention span or deletion deadline exceeds permission");
    }
    private static void noneExclusive(Map<String, Object> obj, String field, String prefix, List<Reason> out) {
        if (list(obj, field).contains("NONE") && list(obj, field).size() != 1) deny(out, "CONTRADICTORY_USE", prefix + "." + field, "NONE cannot be combined with an activity");
    }
    private static void range(Map<String, Object> obj, String from, String to, String prefix, List<Reason> out) {
        if (date(obj, from).isAfter(date(obj, to))) deny(out, "CONTRADICTORY_DATES", prefix, from + " follows " + to);
    }
    private static void covers(Map<String, Object> grant, String a, String b, Map<String, Object> need, String c, String d, String field, String code, List<Reason> out) {
        if (date(need, c).isBefore(date(grant, a)) || date(need, d).isAfter(date(grant, b))) deny(out, code, field, "Requested inclusive date interval is outside the supplied scope");
    }
    private static void binding(Map<String, Object> record, String hash, String prefix, List<Reason> out) {
        if (!hash.equals(record.get("manifest_sha256"))) deny(out, "MANIFEST_HASH_MISMATCH", prefix + ".manifest_sha256", "Evidence/use declaration is not bound to these exact manifest bytes");
    }
    private static void equal(Map<String, Object> a, Map<String, Object> b, String field, String prefix, List<Reason> out) {
        if (!a.get(field).equals(b.get(field))) deny(out, "SCOPE_MISMATCH", prefix + "." + field, "Exact " + field + " identifier does not match the manifest");
    }
    private static void subset(List<String> scope, List<String> requested, String field, List<Reason> out) {
        for (String value : new TreeSet<>(requested)) if (!scope.contains(value)) deny(out, "RIGHT_NOT_GRANTED", field, "No explicit right for " + value);
    }
    private static String s(Map<String, Object> obj, String key) { return (String)obj.get(key); }
    private static LocalDate date(Map<String, Object> obj, String key) { return LocalDate.parse(s(obj, key)); }
    @SuppressWarnings("unchecked") private static List<String> list(Map<String, Object> obj, String key) { return (List<String>)obj.get(key); }
    @SuppressWarnings("unchecked") private static List<Map<String, Object>> records(Map<String, Object> obj) { return (List<Map<String, Object>>)obj.get("records"); }
    private static void deny(List<Reason> out, String code, String field, String detail) { out.add(new Reason(Status.DENIED, code, field, detail)); }
    private static void incomplete(List<Reason> out, String code, String field, String detail) { out.add(new Reason(Status.INCOMPLETE, code, field, detail)); }
    private static boolean validate(String name, Snapshot s, List<Reason> out) { return s != null && s.object != null && PermissionSchemas.validate(name, s.object, name, out); }
    private static Snapshot read(Path path, String name, List<Reason> out, Map<String, Object> hashes) {
        hashes.put(name, null);
        try {
            byte[] bytes = boundedRead(path); hashes.put(name, PermissionJson.hash(bytes));
            try {
                Object value = PermissionJson.parse(bytes);
                Map<String, Object> obj = PermissionJson.object(value);
                if (obj == null) incomplete(out, "SCHEMA_INCOMPLETE", name, "Expected a complete JSON object");
                return new Snapshot(path, bytes, obj);
            } catch (IOException invalid) {
                incomplete(out, "MALFORMED_INPUT", name, "Input is not strict UTF-8 JSON (duplicate keys, BOM, invalid syntax, or excessive nesting)");
                return new Snapshot(path, bytes, null);
            }
        } catch (IOException unavailable) { incomplete(out, "INPUT_UNAVAILABLE", name, "Required regular local input is missing, unreadable, or exceeds 1 MiB"); return null; }
    }
    private static InputStream regularInput(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) throw new IOException("Not a regular local file");
        return Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
    }
    private static byte[] boundedRead(Path path) throws IOException {
        try (InputStream input = regularInput(path)) {
            byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
            if (bytes.length > MAX_INPUT_BYTES) throw new IOException("Input too large");
            return bytes;
        }
    }
    private static void recheck(List<Snapshot> snapshots, List<Reason> out) {
        for (int i = 0; i < snapshots.size(); i++) {
            try { if (Arrays.equals(snapshots.get(i).bytes, boundedRead(snapshots.get(i).path))) continue; }
            catch (IOException changed) { /* Same deterministic failure for replacement/removal. */ }
            deny(out, "INPUT_CHANGED", "input_snapshot[" + i + "]", "Input changed while the preflight was running");
        }
    }
    /** Class-byte fingerprint includes the checker, schemas, serializer, CLI and shared strict parser. */
    static String codeHash() throws IOException {
        try {
            Path location = Path.of(PermissionPreflight.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            String prefix = "com/mscient/intrapaper/permission/preflight/";
            String parser = StrictJsonParser.class.getName().replace('.', '/') + ".class";
            Map<String, String> hashes = new TreeMap<>();
            if (Files.isDirectory(location)) {
                try (var files = Files.walk(location.resolve(prefix))) {
                    for (Path file : files.filter(f -> f.toString().endsWith(".class")).sorted().toList())
                        hashes.put(location.relativize(file).toString().replace('\\', '/'), PermissionJson.hash(Files.readAllBytes(file)));
                }
                hashes.put(parser, PermissionJson.hash(Files.readAllBytes(location.resolve(parser))));
            } else {
                try (JarFile jar = new JarFile(location.toFile())) {
                    for (var entry : jar.stream().filter(e -> e.getName().endsWith(".class") && (e.getName().startsWith(prefix) || e.getName().equals(parser))).toList())
                        try (InputStream input = jar.getInputStream(entry)) { hashes.put(entry.getName(), PermissionJson.hash(input.readAllBytes())); }
                }
            }
            if (hashes.size() < 5 || !hashes.containsKey(parser)) throw new IOException("Checker code identity is incomplete");
            return PermissionJson.hash(PermissionJson.bytes(hashes));
        } catch (java.net.URISyntaxException invalid) { throw new IOException("Checker code identity unavailable", invalid); }
    }
}
