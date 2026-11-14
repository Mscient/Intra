package com.mscient.intrapaper.permission.preflight;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static com.mscient.intrapaper.permission.preflight.PermissionPreflight.Status.*;

/** Synthetic-only behavioral checks, including every missing field and blocked research callbacks. */
public final class PermissionPreflightTest {
    private static int checks;
    private static Path fixture, output;
    private static final LocalDate AS_OF = LocalDate.of(2026, 11, 7);
    private static final class Bundle {
        final Path root, m, u, p;
        final Map<String, Object> manifest, use, permissions;
        Bundle(String name) throws IOException {
            root = output.resolve(name); Files.createDirectories(root);
            m = root.resolve("manifest.json"); u = root.resolve("intended-use.json"); p = root.resolve("permission-evidence.json");
            for (Path path : List.of(m, u, p)) Files.write(path, Files.readAllBytes(fixture.resolve(path.getFileName())));
            manifest = object(m); use = object(u); permissions = object(p);
        }
        Map<String, Object> grant() { return PermissionJson.object(((List<?>)permissions.get("records")).getFirst()); }
        void write() throws IOException { Files.write(m, PermissionJson.bytes(manifest)); Files.write(u, PermissionJson.bytes(use)); Files.write(p, PermissionJson.bytes(permissions)); }
        void bind() throws IOException {
            Files.write(m, PermissionJson.bytes(manifest)); String hash = PermissionJson.hash(Files.readAllBytes(m));
            use.put("manifest_sha256", hash);
            for (Object record : (List<?>)permissions.get("records")) PermissionJson.object(record).put("manifest_sha256", hash);
            write();
        }
        PermissionPreflight.Decision check() throws IOException { return PermissionPreflight.check(m, u, p, AS_OF); }
    }
    public static void main(String[] args) throws Exception {
        fixture = Path.of(args[0]).resolve("examples/dataset-permission-preflight"); output = Path.of(args[1]); Files.createDirectories(output);
        Bundle valid = new Bundle("valid");
        expect(valid.check(), ALLOWED, null);
        var declared = PermissionJson.object(PermissionJson.parse(valid.check().bytes()));
        ok(Boolean.FALSE.equals(declared.get("production_approved")), "No production approval");
        ok(Boolean.FALSE.equals(declared.get("g2_closed")), "G2 remains open");
        ok(Boolean.FALSE.equals(declared.get("contract_validity_verified")), "References are not contract proof");
        for (String schema : List.of("dataset-manifest", "intended-use", "permission-evidence")) {
            ok(Arrays.equals(PermissionSchemas.schema(schema), Files.readAllBytes(Path.of(args[0]).resolve("schemas/dataset-intended-use-preflight/" + schema + ".schema.json"))), "Published schema matches runtime: " + schema);
        }
        // Exact product/source/type/instrument matching; neither fuzzy matching nor grants from another product.
        for (String field : List.of("source_id", "product_id", "data_type")) {
            scenario("permission-wrong-" + field, b -> b.grant().put(field, "WRONG"), DENIED, "SCOPE_MISMATCH");
            scenario("use-wrong-" + field, b -> b.use.put(field, "WRONG"), DENIED, "SCOPE_MISMATCH");
        }
        scenario("wrong-instrument", b -> b.grant().put("instruments", List.of("WRONG-CONTRACT")), DENIED, "RIGHT_NOT_GRANTED");
        scenario("use-outside-dataset", b -> b.use.put("instruments", List.of("WRONG-CONTRACT")), DENIED, "RIGHT_NOT_GRANTED");
        scenario("wrong-data-dates", b -> b.grant().put("data_end", "2026-11-05"), DENIED, "DATA_DATE_OUT_OF_SCOPE");
        scenario("use-data-outside-manifest", b -> b.use.put("data_start", "2026-10-31"), DENIED, "DATA_DATE_OUT_OF_SCOPE");
        scenario("missing-backtest-right", b -> b.grant().put("purposes", List.of("SIMULATION")), DENIED, "RIGHT_NOT_GRANTED");
        scenario("missing-use-rights", b -> b.grant().remove("purposes"), INCOMPLETE, "SCHEMA_INCOMPLETE");
        scenario("empty-use-rights", b -> b.grant().put("purposes", List.of()), INCOMPLETE, "SCHEMA_INCOMPLETE");
        scenario("ambiguous-record", b -> b.grant().put("record_state", "AMBIGUOUS"), INCOMPLETE, "AMBIGUOUS_RECORD");
        scenario("incomplete-set", b -> b.permissions.put("completeness", "INCOMPLETE"), INCOMPLETE, "EVIDENCE_SET_INCOMPLETE");
        scenario("unknown-set", b -> b.permissions.put("completeness", "UNKNOWN"), INCOMPLETE, "EVIDENCE_SET_INCOMPLETE");
        scenario("revoked", b -> b.grant().put("record_state", "REVOKED"), DENIED, "PERMISSION_REVOKED");
        scenario("explicit-deny", b -> b.grant().put("effect", "DENY"), DENIED, "EXPLICIT_DENIAL");
        scenario("owner-pending", b -> b.grant().put("owner_approval", "PENDING"), INCOMPLETE, "REVIEW_PENDING");
        scenario("review-pending", b -> b.grant().put("review_status", "PENDING"), INCOMPLETE, "REVIEW_PENDING");
        scenario("review-rejected", b -> b.grant().put("review_status", "REJECTED"), DENIED, "REVIEW_REJECTED");
        scenario("future-review", b -> b.grant().put("reviewed_on", "2026-11-08"), INCOMPLETE, "REVIEW_NOT_YET_EFFECTIVE");
        scenario("missing-review-evidence", b -> b.grant().put("review_reference", ""), INCOMPLETE, "SCHEMA_INCOMPLETE");
        scenario("wrong-classification", b -> b.permissions.put("classification", "CALLER_DECLARED"), DENIED, "CLASSIFICATION_MISMATCH");
        // Inclusive end boundary is allowed; a single day beyond it is denied.
        expect(PermissionPreflight.check(valid.m, valid.u, valid.p, LocalDate.of(2026, 11, 8)), ALLOWED, null);
        expect(PermissionPreflight.check(valid.m, valid.u, valid.p, LocalDate.of(2026, 11, 9)), DENIED, "PERMISSION_NOT_EFFECTIVE");
        expect(PermissionPreflight.check(valid.m, valid.u, valid.p, LocalDate.of(2026, 11, 6)), DENIED, "PERMISSION_NOT_EFFECTIVE");
        scenario("expired-before-run-end", b -> b.grant().put("effective_through", "2026-11-07"), DENIED, "USE_DATE_OUT_OF_SCOPE");
        scenario("retention-days-conflict", b -> b.grant().put("max_retention_days", 2), DENIED, "RETENTION_CONFLICT");
        scenario("retention-date-conflict", b -> b.grant().put("retain_until", "2026-11-08"), DENIED, "RETENTION_CONFLICT");
        scenario("storage-conflict", b -> b.grant().put("storage_locations", List.of("SYNTHETIC-LOCAL-RESEARCH")), DENIED, "RIGHT_NOT_GRANTED");
        scenario("derived-conflict", b -> b.grant().put("derived_outputs", List.of("AGGREGATE_METRICS")), DENIED, "RIGHT_NOT_GRANTED");
        scenario("sharing-conflict", b -> {
            b.use.put("sharing_mode", "EXTERNAL"); b.use.put("sharing_recipients", List.of("RESEARCHER-B")); b.use.put("shared_material", List.of("CHARTS"));
        }, DENIED, "RIGHT_NOT_GRANTED");
        scenario("sharing-exact", b -> {
            b.use.put("sharing_mode", "INTERNAL"); b.use.put("sharing_recipients", List.of("RESEARCHER-A")); b.use.put("shared_material", List.of("CHARTS"));
            b.grant().put("sharing_modes", List.of("INTERNAL")); b.grant().put("sharing_recipients", List.of("RESEARCHER-A")); b.grant().put("shared_material", List.of("CHARTS"));
        }, ALLOWED, null);
        scenario("contradictory-none", b -> b.use.put("derived_outputs", List.of("NONE", "CHARTS")), DENIED, "CONTRADICTORY_USE");
        scenario("undeclared-shared-output", b -> b.use.put("shared_material", List.of("SIGNALS")), DENIED, "CONTRADICTORY_USE");
        scenario("reversed-dates", b -> b.use.put("use_end", "2026-11-06"), DENIED, "CONTRADICTORY_DATES");
        scenario("bad-calendar-date", b -> b.use.put("use_end", "2026-02-30"), INCOMPLETE, "SCHEMA_INCOMPLETE");
        scenario("wildcard", b -> b.grant().put("instruments", List.of("*")), INCOMPLETE, "SCHEMA_INCOMPLETE");
        scenario("unknown-matching-identifiers", b -> {
            b.manifest.put("source_id", "unknown"); b.use.put("source_id", "unknown"); b.grant().put("source_id", "unknown");
        }, INCOMPLETE, "SCHEMA_INCOMPLETE");
        scenario("unknown-reference", b -> b.grant().put("evidence_reference", "TBD"), INCOMPLETE, "SCHEMA_INCOMPLETE");
        scenario("duplicate-set-member", b -> b.grant().put("purposes", List.of("SIMULATION", "SIMULATION")), INCOMPLETE, "SCHEMA_INCOMPLETE");
        scenario("unsupported-purpose", b -> b.use.put("purposes", List.of("LIVE_TRADING")), INCOMPLETE, "SCHEMA_INCOMPLETE");
        scenario("promotion-refused", b -> b.manifest.put("candidate_status", "PRODUCTION_APPROVED"), INCOMPLETE, "SCHEMA_INCOMPLETE");
        scenario("contradictory-records", b -> {
            var deny = new TreeMap<>(b.grant()); deny.put("record_id", "SYNTHETIC-DENIAL"); deny.put("effect", "DENY");
            b.permissions.put("records", List.of(b.grant(), deny));
        }, DENIED, "CONTRADICTORY_RECORDS");
        scenario("reordered-contradictory-records", b -> {
            var deny = new TreeMap<>(b.grant()); deny.put("record_id", "SYNTHETIC-DENIAL"); deny.put("effect", "DENY");
            b.permissions.put("records", List.of(deny, b.grant()));
        }, DENIED, "CONTRADICTORY_RECORDS");
        scenario("duplicate-record-id", b -> b.permissions.put("records", List.of(b.grant(), b.grant())), DENIED, "DUPLICATE_RECORD");
        scenario("two-complete-grants", b -> {
            var second = new TreeMap<>(b.grant()); second.put("record_id", "SECOND");
            b.permissions.put("records", List.of(b.grant(), second));
        }, ALLOWED, null);
        scenario("no-union-of-incomplete-scopes", b -> {
            var second = new TreeMap<>(b.grant()); second.put("record_id", "SECOND"); second.put("purposes", List.of("BACKTEST"));
            b.grant().put("purposes", List.of("SIMULATION")); b.permissions.put("records", List.of(b.grant(), second));
        }, DENIED, "RIGHT_NOT_GRANTED");
        scenario("full-dataset-must-be-covered", b -> b.manifest.put("instruments", List.of("SYNTH-FUT-202611", "UNPERMITTED-CONTRACT")), DENIED, "RIGHT_NOT_GRANTED");
        // No missing field in any schema may result in permission, even on synthetic input.
        for (String kind : List.of("manifest", "use", "permissions", "grant")) {
            Map<String, Object> original = select(valid, kind);
            for (String key : new ArrayList<>(original.keySet())) {
                Bundle b = new Bundle("missing-" + kind + "-" + key);
                b.bind(); select(b, kind).remove(key); b.write();
                var decision = b.check(); ok(decision.status() != ALLOWED, "Missing " + kind + "." + key + " blocks permission");
                ok(decision.reasons().stream().anyMatch(r -> r.field().endsWith("." + key)), "Specific missing-field reason: " + key);
            }
        }
        scenario("unknown-field", b -> b.grant().put("implicit_permission", true), INCOMPLETE, "SCHEMA_INCOMPLETE");
        scenario("null-reviewer", b -> b.grant().put("reviewer", null), INCOMPLETE, "SCHEMA_INCOMPLETE");
        scenario("noninteger-retention", b -> b.grant().put("max_retention_days", 2.5), INCOMPLETE, "SCHEMA_INCOMPLETE");
        Bundle changed = new Bundle("changed-manifest"); changed.manifest.put("dataset_id", "CHANGED"); changed.write();
        expect(changed.check(), DENIED, "MANIFEST_HASH_MISMATCH");
        malformed(); deterministic(valid); cli(valid); guard(valid);
        System.out.println("PASS " + checks + " synthetic dataset-permission preflight checks; G2 remains open");
    }
    private static void malformed() throws Exception {
        for (String name : List.of("duplicate-key", "bom", "trailing", "invalid-utf8", "missing-file", "oversized", "deep-json", "root-null")) {
            Bundle b = new Bundle(name); byte[] original = Files.readAllBytes(b.p);
            switch (name) {
                case "duplicate-key" -> Files.writeString(b.p, new String(original, StandardCharsets.UTF_8).replace("\"schema_version\": \"1\",", "\"schema_version\": \"1\", \"schema_version\": \"1\","));
                case "bom" -> Files.writeString(b.p, "\ufeff" + new String(original, StandardCharsets.UTF_8));
                case "trailing" -> Files.writeString(b.p, new String(original, StandardCharsets.UTF_8) + " false");
                case "invalid-utf8" -> Files.write(b.p, new byte[]{(byte)0xc3, 0x28});
                case "missing-file" -> Files.delete(b.p);
                case "oversized" -> Files.write(b.p, new byte[1024 * 1024 + 1]);
                case "deep-json" -> Files.writeString(b.p, "[".repeat(70) + "0" + "]".repeat(70));
                case "root-null" -> Files.writeString(b.p, "null");
            }
            expect(b.check(), INCOMPLETE, name.equals("missing-file") || name.equals("oversized") ? "INPUT_UNAVAILABLE" : name.equals("root-null") ? "SCHEMA_INCOMPLETE" : "MALFORMED_INPUT");
        }
    }
    private static void deterministic(Bundle b) throws Exception {
        var a = b.check(); var repeat = b.check(); ok(Arrays.equals(a.bytes(), repeat.bytes()), "Repeated byte-identical reports");
        var previousLocale = java.util.Locale.getDefault(); var previousZone = java.util.TimeZone.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR")); java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Pacific/Honolulu"));
            ok(Arrays.equals(a.bytes(), b.check().bytes()), "Locale/timezone-independent bytes");
        } finally { java.util.Locale.setDefault(previousLocale); java.util.TimeZone.setDefault(previousZone); }
        Bundle relocated = new Bundle("relocated"); ok(Arrays.equals(a.bytes(), relocated.check().bytes()), "Relocated identical input bytes");
        byte[] returned = a.bytes(); returned[0] = 0; ok(a.bytes()[0] == '{', "Immutable returned report bytes");
        Map<String, Object> json = PermissionJson.object(PermissionJson.parse(a.bytes())); String id = (String)json.remove("decision_id");
        ok(id.equals(PermissionJson.hash(PermissionJson.bytes(json))), "Decision ID hashes full decision payload");
        Map<String, Object> hashes = PermissionJson.object(json.get("input_sha256"));
        for (var input : Map.of("dataset-manifest", b.m, "intended-use", b.u, "permission-evidence", b.p).entrySet())
            ok(hashes.get(input.getKey()).equals(PermissionJson.hash(Files.readAllBytes(input.getValue()))), "Exact bytes bound: " + input.getKey());
        ok(json.get("checker_version_sha256").equals(PermissionJson.hash(PermissionPreflight.VERSION.getBytes(StandardCharsets.UTF_8))), "Checker version hash");
        ok(json.get("checker_code_sha256").equals(PermissionPreflight.codeHash()), "Checker code hash");
        for (String field : List.of("intended-use", "permission-evidence")) {
            Bundle altered = new Bundle("changed-" + field); Path file = field.equals("intended-use") ? altered.u : altered.p;
            Files.writeString(file, Files.readString(file) + "\n");
            expect(altered.check(), ALLOWED, null); ok(!altered.check().id().equals(a.id()), "Whitespace changes bound input hash: " + field);
        }
        ok(!PermissionPreflight.check(b.m, b.u, b.p, AS_OF.plusDays(1)).id().equals(a.id()), "Assessment date bound");
        Files.write(output.resolve("reference-decision.json"), a.bytes());
    }
    private static void cli(Bundle b) throws Exception {
        Path report = output.resolve("cli-report.json");
        ok(command("check", b, report) == 0, "CLI allowed exit"); ok(command("check", b, report) == 0, "Idempotent CLI publication");
        ok(command("verify", b, report) == 0, "CLI full reproduction");
        Bundle changed = new Bundle("cli-changed"); changed.use.put("use_id", "NEW-USE"); changed.bind();
        ok(command("verify", changed, report) == 4, "Stale decision fails reproduction");
        ok(command("check", changed, report) == 74, "Immutable output refuses replacement");
        Files.writeString(report, "corrupt"); ok(command("verify", b, report) == 4, "Tampered report rejected");
        Bundle denied = new Bundle("cli-denied"); denied.grant().put("effect", "DENY"); denied.bind();
        Path deniedReport = output.resolve("denied-report.json"); ok(command("check", denied, deniedReport) == 2, "CLI denied exit");
        ok(command("verify", denied, deniedReport) == 2, "Verifying a denial never yields allowed exit");
        Bundle missing = new Bundle("cli-missing"); Files.delete(missing.p);
        ok(command("check", missing, output.resolve("incomplete-report.json")) == 3, "CLI incomplete exit");
        byte[] before = Files.readAllBytes(b.m); ok(command("check", b, b.m) == 74, "Output/input alias rejected");
        ok(Arrays.equals(before, Files.readAllBytes(b.m)), "Input not overwritten");
        var sink = new PrintStream(new ByteArrayOutputStream());
        Path datasetReport = output.resolve("cli-dataset-report.json");
        String[] datasetArgs = {"check-dataset", b.m.toString(), b.u.toString(), b.p.toString(), AS_OF.toString(), datasetReport.toString(), fixture.resolve("synthetic.csv").toString()};
        ok(PermissionPreflightCli.run(datasetArgs, sink, sink) == 0, "Dataset CLI allowed");
        datasetArgs[0] = "verify-dataset";
        ok(PermissionPreflightCli.run(datasetArgs, sink, sink) == 0, "Dataset CLI reproduction");
        Path changedData = output.resolve("cli-changed.csv"); Files.writeString(changedData, "changed"); datasetArgs[6] = changedData.toString();
        ok(PermissionPreflightCli.run(datasetArgs, sink, sink) == 4, "Dataset CLI rejects stale data");
        ok(PermissionPreflightCli.run(new String[]{}, sink, sink) == 64, "Usage exit is blocking");
        ok(PermissionPreflightCli.run(new String[]{"check", b.m.toString(), b.u.toString(), b.p.toString(), "2026-02-30", report.toString()}, sink, sink) == 74, "Invalid assessment date blocks");
    }
    private static void guard(Bundle valid) throws Exception {
        byte[] data = Files.readAllBytes(fixture.resolve("synthetic.csv")); AtomicInteger calls = new AtomicInteger();
        int length = PermissionPreflight.withAllowedDataset(valid.m, valid.u, valid.p, AS_OF, data, checked -> { calls.incrementAndGet(); return checked.length; });
        ok(length == data.length && calls.get() == 1, "Only authorized matching bytes reach research callback");
        for (String name : List.of("denied", "incomplete", "changed-dataset")) {
            Bundle b = new Bundle("guard-" + name);
            if (name.equals("denied")) { b.grant().put("effect", "DENY"); b.bind(); }
            if (name.equals("incomplete")) Files.delete(b.p);
            byte[] input = data.clone(); if (name.equals("changed-dataset")) input[0] ^= 1;
            boolean blocked = false;
            try { PermissionPreflight.withAllowedDataset(b.m, b.u, b.p, AS_OF, input, checked -> calls.incrementAndGet()); }
            catch (IOException expected) { blocked = true; }
            ok(blocked && calls.get() == 1, "Research callback never invoked: " + name);
        }
        var verified = PermissionPreflight.checkDataset(valid.m, valid.u, valid.p, AS_OF, fixture.resolve("synthetic.csv"));
        expect(verified, ALLOWED, null);
        ok(PermissionJson.object(PermissionJson.parse(verified.bytes())).get("observed_dataset_sha256").equals(PermissionJson.hash(data)), "Observed dataset hash recorded");
        Path changed = output.resolve("changed.csv"); Files.writeString(changed, "different");
        expect(PermissionPreflight.checkDataset(valid.m, valid.u, valid.p, AS_OF, changed), DENIED, "DATASET_HASH_MISMATCH");
        expect(PermissionPreflight.checkDataset(valid.m, valid.u, valid.p, AS_OF, output.resolve("missing.csv")), INCOMPLETE, "DATASET_UNAVAILABLE");
        Bundle denied = new Bundle("dataset-unread-before-permission"); denied.grant().put("effect", "DENY"); denied.bind();
        var blocked = PermissionPreflight.checkDataset(denied.m, denied.u, denied.p, AS_OF, output.resolve("missing.csv"));
        expect(blocked, DENIED, "EXPLICIT_DENIAL");
        ok(blocked.reasons().stream().noneMatch(r -> r.code().equals("DATASET_UNAVAILABLE")), "Dataset is not opened before declared rights pass");
    }
    private static int command(String mode, Bundle b, Path report) {
        var sink = new PrintStream(new ByteArrayOutputStream());
        return PermissionPreflightCli.run(new String[]{mode, b.m.toString(), b.u.toString(), b.p.toString(), AS_OF.toString(), report.toString()}, sink, sink);
    }
    private static Map<String, Object> select(Bundle b, String kind) {
        return switch (kind) { case "manifest" -> b.manifest; case "use" -> b.use; case "permissions" -> b.permissions; default -> b.grant(); };
    }
    private static Map<String, Object> object(Path path) throws IOException { return PermissionJson.object(PermissionJson.parse(Files.readAllBytes(path))); }
    private static void scenario(String name, Consumer<Bundle> change, PermissionPreflight.Status status, String code) throws Exception {
        Bundle b = new Bundle(name); change.accept(b); b.bind(); expect(b.check(), status, code); Files.write(b.root.resolve("decision.json"), b.check().bytes());
    }
    private static void expect(PermissionPreflight.Decision decision, PermissionPreflight.Status status, String code) {
        ok(decision.status() == status, "Expected " + status + ", found " + decision.status() + ": " + decision.reasons());
        if (code != null) ok(decision.reasons().stream().anyMatch(r -> r.code().equals(code)), "Missing reason " + code + ": " + decision.reasons());
    }
    private static void ok(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
