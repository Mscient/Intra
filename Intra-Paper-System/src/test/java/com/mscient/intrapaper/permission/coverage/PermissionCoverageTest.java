package com.mscient.intrapaper.permission.coverage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import static com.mscient.intrapaper.permission.coverage.CoverageJson.*;
import static com.mscient.intrapaper.permission.coverage.CoverageSchemas.*;

/** Synthetic, dependency-free adversarial checks. No provider access or real evidence. */
public final class PermissionCoverageTest {
    private static final byte[] DATA = "synthetic-only,no-market-data\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EVIDENCE = "SYNTHETIC ASSERTIONS ONLY. No contract or rights.\n".getBytes(StandardCharsets.UTF_8);
    private static int assertions, scenarios;
    private static Path root;
    private static void check(boolean condition, String name) {
        assertions++;
        if (!condition) throw new AssertionError(name);
    }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, String name) throws Exception {
        boolean failed = false;
        try { action.run(); } catch (IOException expected) { failed = true; }
        check(failed, name);
    }
    private static Map<String, Object> copy(Map<String, Object> value) {
        try { return map(parse(bytes(value))); } catch (IOException impossible) { throw new AssertionError(impossible); }
    }
    private static Map<String, Object> dates(String start, String end) { return object("start", start, "end", end, "boundary", "[start,end)"); }
    private static Map<String, Object> values(String... values) { return object("all", false, "values", new ArrayList<>(List.of(values))); }
    private static Map<String, Object> all() { return object("all", true, "values", new ArrayList<>()); }
    private static Map<String, Object> term(String... permitted) { return object("permitted", values(permitted), "prohibited", values()); }
    private static Map<String, Object> scope(String start, String end, String... instruments) {
        return object("instruments", values(instruments), "all_dates", false, "dates", new ArrayList<>(List.of(dates(start, end))));
    }
    private static Map<String, Object> record() {
        Map<String, Object> terms = object("data_types", term("BARS"), "purposes", term("SIMULATION"), "display_modes", term("NON_DISPLAY"),
                "storage_locations", term("local:lab"), "compute_locations", term("local:lab"), "backup_locations", term("local:backup"),
                "deletion", term("DELETE_AFTER_RETENTION"), "derived_retention", term("YES"), "sharing", term("NO"), "publication", term("NO"), "distribution", term("NO"));
        return object("record_id", "synthetic-permission-a", "revision", 1, "state", "ACTIVE",
                "claims", object("provider_id", "synthetic-provider", "product_id", "synthetic-product", "contracting_party_id", "synthetic-party",
                        "permitted_scope", scope("2025-01-01", "2025-02-01", "SYNTH-FUT-A"), "prohibited_scopes", new ArrayList<>(),
                        "effective_period", dates("2026-01-01", "2027-01-01"), "terms", terms,
                        "retention", object("permitted_max_days", 30, "prohibited_from_days", null)),
                "evidence", object("reference", "private-reference-canary", "path", "synthetic-evidence.txt", "sha256", hash(EVIDENCE),
                        "reviewer_id", "private-reviewer-canary", "reviewer_role", "synthetic-reviewer-role", "review_date", "2026-01-01",
                        "reviewed_fields", new ArrayList<>(DIMENSIONS), "claims_sha256", "0".repeat(64)),
                "revocation_ref", null, "superseded_by", new ArrayList<>(), "amends", new ArrayList<>(), "conflicts_with", new ArrayList<>());
    }
    private static final class Fixture {
        Map<String, Object> d, i, register;
        Fixture() {
            d = object("schema_version", "data-permission-dataset/v1", "dataset_id", "synthetic-dataset", "dataset_version", "v1",
                    "provider_id", "synthetic-provider", "product_id", "synthetic-product", "data_types", new ArrayList<>(List.of("BARS")),
                    "instruments", new ArrayList<>(List.of("SYNTH-FUT-A")), "date_scope", dates("2025-01-01", "2025-02-01"),
                    "files", new ArrayList<>(List.of(object("file_id", "synthetic-file", "path", "synthetic-data.csv", "sha256", hash(DATA)))),
                    "storage_locations", List.of("local:lab"), "compute_locations", List.of("local:lab"), "backup_locations", List.of("local:backup"));
            i = object("schema_version", "data-permission-intent/v1", "policy_version", "declared-coverage/v1", "request_id", "synthetic-request",
                    "dataset_id", "synthetic-dataset", "dataset_version", "v1", "contracting_party_id", "synthetic-party",
                    "assessment_date", "2026-06-01", "use_period", dates("2026-06-01", "2026-07-01"), "purpose", "SIMULATION", "display_mode", "NON_DISPLAY",
                    "instruments", new ArrayList<>(List.of("SYNTH-FUT-A")), "date_scope", dates("2025-01-01", "2025-02-01"),
                    "storage_locations", List.of("local:lab"), "compute_locations", List.of("local:lab"), "backup_locations", List.of("local:backup"),
                    "retention_days", 30, "deletion", "DELETE_AFTER_RETENTION",
                    "derived_outputs", object("retained", true, "shared", false, "published", false, "distributed", false));
            register = object("schema_version", "data-permission-register/v1", "records", new ArrayList<>(List.of(record())));
        }
        Map<String, Object> r() { return map(list(register.get("records")).getFirst()); }
        Map<String, Object> c() { return map(r().get("claims")); }
        Map<String, Object> t() { return map(c().get("terms")); }
        Map<String, Object> s() { return map(c().get("permitted_scope")); }
        Map<String, Object> e() { return map(r().get("evidence")); }
        void seal() {
            for (Object r : list(register.get("records"))) map(map(r).get("evidence")).put("claims_sha256", hash(bytes(normalized(map(r).get("claims")))));
        }
        void write(Path path) throws IOException {
            Files.createDirectories(path);
            Files.write(path.resolve("synthetic-data.csv"), DATA); Files.write(path.resolve("synthetic-evidence.txt"), EVIDENCE);
            Files.write(path.resolve("dataset.json"), bytes(d)); Files.write(path.resolve("intent.json"), bytes(i)); Files.write(path.resolve("register.json"), bytes(register));
        }
    }
    private static Map<String, Object> evaluate(Path p) throws IOException {
        return PermissionCoverage.check(p.resolve("dataset.json"), p.resolve("intent.json"), p.resolve("register.json"));
    }
    private static Map<String, Object> verify(Path report, Path p) throws IOException {
        return PermissionCoverageArtifacts.verify(report, p.resolve("dataset.json"), p.resolve("intent.json"), p.resolve("register.json"));
    }
    private static void labels(Map<String, Object> report) {
        for (String label : List.of("permission_approved", "production_eligible", "evidence_authenticated", "legal_approval", "g2_closed"))
            check(Boolean.FALSE.equals(report.get(label)), "mandatory false: " + label);
        String text = json(report);
        for (String secret : List.of("private-reference-canary", "private-reviewer-canary", "synthetic-reviewer-role", "synthetic-provider", "synthetic-party", "synthetic-evidence.txt", "SYNTHETIC ASSERTIONS ONLY"))
            check(!text.contains(secret), "private value omitted: " + secret);
        check(list(report.get("dimensions")).size() == DIMENSIONS.size(), "all dimensions reported");
    }
    private static Map<String, Object> scenario(String name, String expected, Consumer<Fixture> edit) throws Exception {
        Fixture fixture = new Fixture(); edit.accept(fixture); fixture.seal();
        Path p = root.resolve(name); fixture.write(p);
        Map<String, Object> report = evaluate(p); scenarios++;
        check(expected.equals(report.get("status")), name + ": expected " + expected + ", got " + report.get("status") + " " + report.get("reason_codes"));
        labels(report);
        Path artifact = PermissionCoverageArtifacts.publish(p.resolve("reports"), report);
        check(Arrays.equals(bytes(report), bytes(verify(artifact, p))), name + " verify");
        System.out.println("PASS " + name + " " + expected);
        return report;
    }
    private static void coreCases() throws Exception {
        scenario("complete", "SCOPE_MATCH", f -> { });
        scenario("missing-record", "INCOMPLETE", f -> list(f.register.get("records")).clear());
        for (String field : List.of("provider_id", "product_id", "contracting_party_id")) scenario("wrong-" + field, "OUT_OF_SCOPE", f -> f.c().put(field, "another-id"));
        scenario("wrong-data-type", "OUT_OF_SCOPE", f -> f.t().put("data_types", term("TRADES")));
        scenario("wrong-purpose", "OUT_OF_SCOPE", f -> f.t().put("purposes", term("RESEARCH")));
        scenario("wrong-display", "OUT_OF_SCOPE", f -> f.t().put("display_modes", term("DISPLAY")));
        scenario("wrong-instrument", "OUT_OF_SCOPE", f -> f.s().put("instruments", values("SYNTH-FUT-B")));
        scenario("start-boundary", "OUT_OF_SCOPE", f -> f.s().put("dates", List.of(dates("2025-01-02", "2025-02-01"))));
        scenario("exclusive-end-boundary", "OUT_OF_SCOPE", f -> f.s().put("dates", List.of(dates("2025-01-01", "2025-01-31"))));
        scenario("touching-end-not-covered", "OUT_OF_SCOPE", f -> f.s().put("dates", List.of(dates("2024-12-01", "2025-01-01"))));
        scenario("expired", "INCOMPLETE", f -> f.c().put("effective_period", dates("2026-01-01", "2026-06-01")));
        scenario("future-effective", "INCOMPLETE", f -> f.c().put("effective_period", dates("2026-06-02", "2027-01-01")));
        scenario("future-use-beyond-effective", "OUT_OF_SCOPE", f -> f.i.put("use_period", dates("2026-06-01", "2027-01-02")));
        scenario("effective-end-exact", "SCOPE_MATCH", f -> { f.i.put("use_period", dates("2026-06-01", "2027-01-01")); f.i.put("retention_days", 0); });
        scenario("retention-crosses-effective-end", "OUT_OF_SCOPE", f -> f.i.put("use_period", dates("2026-06-01", "2027-01-01")));
        scenario("retention-end-exact", "SCOPE_MATCH", f -> f.c().put("effective_period", dates("2026-01-01", "2026-07-31")));
        scenario("assessment-after-start", "INCOMPLETE", f -> f.i.put("assessment_date", "2026-06-02"));
        scenario("revoked", "INCOMPLETE", f -> { f.r().put("state", "REVOKED"); f.r().put("revocation_ref", "synthetic-revocation"); });
        scenario("active-with-revocation", "INCOMPLETE", f -> f.r().put("revocation_ref", "synthetic-revocation"));
        scenario("superseded-without-replacement", "INCOMPLETE", f -> f.r().put("state", "SUPERSEDED"));
        scenario("missing-replacement", "INCOMPLETE", f -> { f.r().put("state", "SUPERSEDED"); f.r().put("superseded_by", List.of(object("record_id", "missing", "revision", 2))); });
        scenario("reviewed-full-replacement", "SCOPE_MATCH", f -> {
            Map<String, Object> next = copy(f.r()); next.put("revision", 2);
            next.put("amends", List.of(object("record_id", "synthetic-permission-a", "revision", 1)));
            f.r().put("state", "SUPERSEDED"); f.r().put("superseded_by", List.of(object("record_id", "synthetic-permission-a", "revision", 2)));
            list(f.register.get("records")).add(next);
        });
        scenario("unresolved-amendment", "INCOMPLETE", f -> f.r().put("amends", List.of(object("record_id", "unknown", "revision", 1))));
        for (String term : TERMS) {
            scenario("missing-" + term, "INCOMPLETE", f -> f.t().remove(term));
            scenario("unknown-" + term, "INCOMPLETE", f -> f.t().put(term, term()));
        }
        scenario("missing-retention", "INCOMPLETE", f -> f.c().remove("retention"));
        scenario("unknown-retention", "INCOMPLETE", f -> map(f.c().get("retention")).put("permitted_max_days", null));
        scenario("retention-excess", "OUT_OF_SCOPE", f -> f.i.put("retention_days", 31));
        scenario("retention-prohibited-boundary", "OUT_OF_SCOPE", f -> { map(f.c().get("retention")).put("permitted_max_days", 29); map(f.c().get("retention")).put("prohibited_from_days", 30); });
        scenario("conflicting-retention", "INCOMPLETE", f -> map(f.c().get("retention")).put("prohibited_from_days", 30));
        scenario("conflicting-term", "INCOMPLETE", f -> map(f.t().get("purposes")).put("prohibited", values("SIMULATION")));
        scenario("explicit-purpose-prohibition", "OUT_OF_SCOPE", f -> f.t().put("purposes", object("permitted", values("RESEARCH"), "prohibited", values("SIMULATION"))));
        scenario("remote-storage", "OUT_OF_SCOPE", f -> f.i.put("storage_locations", List.of("remote:region")));
        scenario("manifest-storage-union", "OUT_OF_SCOPE", f -> f.d.put("storage_locations", List.of("remote:region")));
        scenario("remote-compute", "OUT_OF_SCOPE", f -> f.i.put("compute_locations", List.of("remote:region")));
        scenario("remote-backup", "OUT_OF_SCOPE", f -> f.i.put("backup_locations", List.of("remote:backup")));
        for (String output : List.of("shared", "published", "distributed")) scenario("derived-" + output, "OUT_OF_SCOPE", f -> map(f.i.get("derived_outputs")).put(output, true));
        var partial = scenario("partial-dates", "OUT_OF_SCOPE", f -> f.s().put("dates", List.of(dates("2025-01-01", "2025-01-15"))));
        check(list(partial.get("coverage")).size() == 2, "partial dates two ranges");
        check(list(partial.get("coverage")).stream().map(CoverageJson::map).anyMatch(c -> c.get("status").equals("SCOPE_MATCH")), "partial covered range retained");
        check(list(partial.get("coverage")).stream().map(CoverageJson::map).anyMatch(c -> c.get("start").equals("2025-01-15") && c.get("end").equals("2025-02-01") && c.get("status").equals("OUT_OF_SCOPE")), "exact uncovered range");
        scenario("partial-instruments", "OUT_OF_SCOPE", f -> { f.d.put("instruments", List.of("SYNTH-FUT-A", "SYNTH-FUT-B")); f.i.put("instruments", f.d.get("instruments")); });
        scenario("split-date-union", "SCOPE_MATCH", f -> {
            Map<String, Object> second = copy(f.r()); second.put("record_id", "synthetic-permission-b");
            map(map(second.get("claims")).get("permitted_scope")).put("dates", List.of(dates("2025-01-15", "2025-02-01")));
            f.s().put("dates", List.of(dates("2025-01-01", "2025-01-15"))); list(f.register.get("records")).add(second);
        });
        scenario("split-instrument-union", "SCOPE_MATCH", f -> {
            f.d.put("instruments", List.of("SYNTH-FUT-A", "SYNTH-FUT-B")); f.i.put("instruments", f.d.get("instruments"));
            Map<String, Object> second = copy(f.r()); second.put("record_id", "synthetic-permission-b");
            map(map(second.get("claims")).get("permitted_scope")).put("instruments", values("SYNTH-FUT-B")); list(f.register.get("records")).add(second);
        });
        scenario("ambiguous-overlap", "INCOMPLETE", f -> { var second = copy(f.r()); second.put("record_id", "synthetic-permission-b"); list(f.register.get("records")).add(second); });
        scenario("conflicting-records", "INCOMPLETE", f -> {
            var second = copy(f.r()); second.put("record_id", "synthetic-permission-b");
            map(map(map(second.get("claims")).get("terms")).get("purposes")).put("prohibited", values("SIMULATION")); list(f.register.get("records")).add(second);
        });
        scenario("declared-conflict", "INCOMPLETE", f -> f.r().put("conflicts_with", List.of(object("record_id", "unknown", "revision", 1))));
        scenario("no-dimension-stitching", "INCOMPLETE", f -> {
            var second = copy(f.r()); second.put("record_id", "synthetic-permission-b"); map(map(second.get("claims")).get("terms")).put("purposes", term());
            f.t().put("storage_locations", term()); list(f.register.get("records")).add(second);
        });
        var broad = scenario("explicit-broad", "SCOPE_MATCH", f -> {
            f.s().put("instruments", all()); f.s().put("all_dates", true); f.s().put("dates", List.of());
            map(f.t().get("purposes")).put("permitted", all());
        });
        check(list(broad.get("dimensions")).stream().map(CoverageJson::map).filter(d -> Boolean.TRUE.equals(d.get("explicit_broad_scope"))).count() == 3, "broad scopes reported");
        scenario("implicit-broad", "INCOMPLETE", f -> f.s().put("instruments", values()));
        scenario("unknown-date-scope", "INCOMPLETE", f -> f.s().put("dates", List.of()));
        scenario("wildcard-is-not-grant", "INCOMPLETE", f -> f.s().put("instruments", values("*")));
        scenario("ambiguous-all-and-values", "INCOMPLETE", f -> map(f.s().get("instruments")).put("all", true));
        scenario("explicit-scope-exclusion", "OUT_OF_SCOPE", f -> f.c().put("prohibited_scopes", List.of(scope("2025-01-15", "2025-02-01", "SYNTH-FUT-A"))));
        scenario("ambiguous-exclusion-instruments", "INCOMPLETE", f -> f.c().put("prohibited_scopes", List.of(scope("2025-01-15", "2025-02-01"))));
        scenario("ambiguous-exclusion-dates", "INCOMPLETE", f -> { var exclusion = scope("2025-01-15", "2025-02-01", "SYNTH-FUT-A"); exclusion.put("dates", List.of()); f.c().put("prohibited_scopes", List.of(exclusion)); });
        var exclusion = scenario("explicit-broad-exclusion", "OUT_OF_SCOPE", f -> { var s = scope("2025-01-15", "2025-02-01", "SYNTH-FUT-A"); s.put("instruments", all()); f.c().put("prohibited_scopes", List.of(s)); });
        check(list(exclusion.get("dimensions")).stream().map(CoverageJson::map).anyMatch(d -> d.get("dimension").equals("instruments") && Boolean.TRUE.equals(d.get("explicit_broad_scope"))), "broad exclusion reported");
        scenario("public-reference-no-evidence", "INCOMPLETE", f -> { f.e().put("reference", "public-download-success"); f.e().put("path", null); f.e().put("sha256", null); });
        scenario("reviewed-true-not-authentication", "INCOMPLETE", f -> f.r().put("reviewed", true));
        scenario("missing-review-claim", "INCOMPLETE", f -> list(f.e().get("reviewed_fields")).remove("sharing"));
        scenario("future-review", "INCOMPLETE", f -> f.e().put("review_date", "2026-06-02"));
        scenario("duplicate-record", "INCOMPLETE", f -> list(f.register.get("records")).add(copy(f.r())));
        scenario("duplicate-record-id-revision", "INCOMPLETE", f -> { var dup = copy(f.r()); map(dup.get("evidence")).put("reference", "different"); list(f.register.get("records")).add(dup); });
        scenario("duplicate-file-id", "INCOMPLETE", f -> { var dup = copy(map(list(f.d.get("files")).getFirst())); dup.put("path", "second.csv"); list(f.d.get("files")).add(dup); });
        scenario("invalid-date", "INCOMPLETE", f -> map(f.d.get("date_scope")).put("start", "2025-02-30"));
        scenario("reversed-date", "INCOMPLETE", f -> f.s().put("dates", List.of(dates("2025-02-01", "2025-01-01"))));
        scenario("overlapping-dates-in-record", "INCOMPLETE", f -> list(f.s().get("dates")).add(dates("2025-01-15", "2025-02-15")));
        scenario("unsupported-boundary", "INCOMPLETE", f -> map(f.d.get("date_scope")).put("boundary", "[start,end]"));
        scenario("intent-hides-dataset-scope", "INCOMPLETE", f -> f.i.put("date_scope", dates("2025-01-01", "2025-01-15")));
        scenario("intent-wrong-dataset", "INCOMPLETE", f -> f.i.put("dataset_id", "other"));
        scenario("unknown-schema", "INCOMPLETE", f -> f.d.put("schema_version", "data-permission-dataset/v2"));
        scenario("unknown-field", "INCOMPLETE", f -> f.i.put("production_eligible", true));
        scenario("path-traversal", "INCOMPLETE", f -> f.e().put("path", "../secret.txt"));
        scenario("https-evidence", "INCOMPLETE", f -> f.e().put("path", "https://example.invalid/evidence"));
        scenario("missing-data-hash", "INCOMPLETE", f -> map(list(f.d.get("files")).getFirst()).remove("sha256"));
        scenario("wrong-data-hash", "INCOMPLETE", f -> map(list(f.d.get("files")).getFirst()).put("sha256", "0".repeat(64)));
        scenario("wrong-evidence-hash", "INCOMPLETE", f -> f.e().put("sha256", "0".repeat(64)));
        scenario("supersession-different-party", "INCOMPLETE", f -> {
            var next = copy(f.r()); next.put("revision", 2); map(next.get("claims")).put("contracting_party_id", "other-party");
            next.put("amends", List.of(object("record_id", "synthetic-permission-a", "revision", 1)));
            f.r().put("state", "SUPERSEDED"); f.r().put("superseded_by", List.of(object("record_id", "synthetic-permission-a", "revision", 2)));
            list(f.register.get("records")).add(next);
        });
        scenario("empty-backups-explicit-none", "SCOPE_MATCH", f -> { f.i.put("backup_locations", List.of()); f.d.put("backup_locations", List.of()); f.t().put("backup_locations", term()); });
        scenario("missing-intent-sharing", "INCOMPLETE", f -> map(f.i.get("derived_outputs")).remove("shared"));
        scenario("missing-manifest-storage", "INCOMPLETE", f -> f.d.remove("storage_locations"));
    }
    private static void integrityCases() throws Exception {
        Path p = root.resolve("integrity"); Fixture f = new Fixture(); f.seal(); f.write(p);
        var report = evaluate(p); labels(report); Path artifact = PermissionCoverageArtifacts.publish(p.resolve("reports"), report);
        check(artifact.equals(PermissionCoverageArtifacts.publish(p.resolve("reports"), evaluate(p))), "idempotent publication");
        check(Arrays.equals(bytes(report), bytes(evaluate(p))), "repeated byte-identical reports");
        for (String input : List.of("dataset.json", "intent.json", "register.json", "synthetic-data.csv", "synthetic-evidence.txt")) {
            Path path = p.resolve(input); byte[] old = Files.readAllBytes(path);
            Files.writeString(path, "tampered", StandardOpenOption.APPEND);
            rejects(() -> verify(artifact, p), "tampering detected: " + input); Files.write(path, old);
        }
        f.e().put("claims_sha256", "0".repeat(64)); f.write(p);
        check(evaluate(p).get("status").equals("INCOMPLETE"), "review claims identity mismatch"); f.seal(); f.write(p);
        byte[] old = Files.readAllBytes(artifact);
        var forged = copy(report); forged.put("permission_approved", true); Files.write(artifact, bytes(forged));
        rejects(() -> verify(artifact, p), "false approval rejected");
        forged = copy(report); forged.put("report_sha256", "0".repeat(64)); Files.write(artifact, bytes(forged));
        rejects(() -> verify(artifact, p), "incorrect report hash rejected");
        forged = copy(report); forged.put("status", "OUT_OF_SCOPE"); forged.remove("report_sha256"); forged.put("report_sha256", hash(bytes(forged)));
        Path forgedPath = PermissionCoverageArtifacts.publish(p.resolve("forged"), forged);
        rejects(() -> verify(forgedPath, p), "rehashed decision forgery rejected");
        forged = copy(report); map(forged.get("provenance")).put("implementation_sha256", "0".repeat(64));
        forged.remove("report_sha256"); forged.put("report_sha256", hash(bytes(forged)));
        Path provenancePath = PermissionCoverageArtifacts.publish(p.resolve("forged"), forged);
        rejects(() -> verify(provenancePath, p), "rehashed provenance forgery rejected");
        Files.write(artifact, old); Files.writeString(artifact, " ", StandardOpenOption.APPEND);
        rejects(() -> verify(artifact, p), "noncanonical report rejected");
        rejects(() -> PermissionCoverageArtifacts.publish(p.resolve("reports"), report), "existing output never overwritten");
        Files.write(artifact, old);
        Path wrongName = p.resolve("wrong-name.json"); Files.write(wrongName, old);
        rejects(() -> verify(wrongName, p), "wrong artifact name");
        Files.writeString(p.resolve("dataset.json"), "{\"schema_version\":null,\"schema_version\":null}");
        var invalid = evaluate(p); check(invalid.get("status").equals("INCOMPLETE"), "duplicate JSON key"); labels(invalid);
        Path invalidArtifact = PermissionCoverageArtifacts.publish(p.resolve("reports"), invalid); verify(invalidArtifact, p);
        Files.write(p.resolve("dataset.json"), new byte[]{(byte) 0xc3, (byte) 0x28});
        check(evaluate(p).get("status").equals("INCOMPLETE"), "invalid UTF-8");
        f.write(p);
        Files.writeString(p.resolve("dataset.json"), json(f.d).replace("\"data-permission-dataset/v1\"", "1e999999999"));
        invalid = evaluate(p); check(invalid.get("status").equals("INCOMPLETE"), "unbounded numeric exponent rejected"); labels(invalid);
        f.write(p); Files.delete(p.resolve("register.json"));
        invalid = evaluate(p); check(invalid.get("status").equals("INCOMPLETE"), "missing register file"); labels(invalid);
        invalidArtifact = PermissionCoverageArtifacts.publish(p.resolve("reports"), invalid); verify(invalidArtifact, p);
        f.write(p);
        ByteArrayOutputStream capture = new ByteArrayOutputStream(); PrintStream sink = new PrintStream(capture, true, StandardCharsets.UTF_8);
        String[] args = {"check", p.resolve("dataset.json").toString(), p.resolve("intent.json").toString(), p.resolve("register.json").toString(), p.resolve("cli").toString()};
        check(PermissionCoverageCli.run(args, sink, sink) == 0, "CLI check match");
        args[0] = "verify"; args[4] = artifact.toString(); check(PermissionCoverageCli.run(args, sink, sink) == 0, "CLI verify");
        check(PermissionCoverageCli.run(new String[]{"check"}, sink, sink) == 3, "CLI strict argument count");
        check(!capture.toString(StandardCharsets.UTF_8).contains(p.toString()), "CLI does not print paths");
        System.out.println("PASS artifact tampering, canonical hashes, provenance, strict JSON and CLI");
    }
    private static Object reverse(Object value) {
        if (value instanceof Map<?, ?> m) { Map<String, Object> result = new LinkedHashMap<>(); var keys = new ArrayList<>(m.keySet()); Collections.reverse(keys); keys.forEach(k -> result.put((String) k, reverse(m.get(k)))); return result; }
        if (value instanceof List<?> list) { var result = new ArrayList<>(list.stream().map(PermissionCoverageTest::reverse).toList()); Collections.reverse(result); return result; }
        return value;
    }
    private static void determinism() throws Exception {
        Path p = root.resolve("determinism"); Fixture f = new Fixture();
        f.d.put("instruments", List.of("SYNTH-FUT-A", "SYNTH-FUT-B")); f.i.put("instruments", f.d.get("instruments"));
        f.s().put("instruments", values("SYNTH-FUT-A", "SYNTH-FUT-B"));
        var second = copy(f.r()); second.put("record_id", "synthetic-permission-b");
        map(map(second.get("claims")).get("permitted_scope")).put("dates", List.of(dates("2025-01-15", "2025-02-01")));
        f.s().put("dates", List.of(dates("2025-01-01", "2025-01-15"))); list(f.register.get("records")).add(second);
        f.seal(); f.write(p); var original = evaluate(p);
        f.d = map(reverse(f.d)); f.i = map(reverse(f.i)); f.register = map(reverse(f.register)); f.write(p);
        var reordered = evaluate(p);
        check(!original.get("input_hashes").equals(reordered.get("input_hashes")), "raw identities reflect row order");
        for (String name : List.of("dataset", "intent", "register")) check(map(map(original.get("input_hashes")).get(name)).get("canonical_sha256").equals(map(map(reordered.get("input_hashes")).get(name)).get("canonical_sha256")), "canonical input invariant: " + name);
        var comparable = copy(original); comparable.put("input_hashes", reordered.get("input_hashes")); comparable.put("report_sha256", reordered.get("report_sha256"));
        check(json(comparable).equals(json(reordered)), "all decisions row-order independent");
        Locale previous = Locale.getDefault(); TimeZone zone = TimeZone.getDefault();
        try {
            for (String language : List.of("tr-TR", "ar-EG", "ja-JP")) for (String tz : List.of("UTC", "Asia/Kolkata", "Pacific/Apia", "America/Los_Angeles")) {
                Locale.setDefault(Locale.forLanguageTag(language)); TimeZone.setDefault(TimeZone.getTimeZone(tz));
                check(Arrays.equals(bytes(reordered), bytes(evaluate(p))), "locale/timezone " + language + " " + tz);
            }
        } finally { Locale.setDefault(previous); TimeZone.setDefault(zone); }
        Path machineA = p.resolve("jvm-a"), machineB = p.resolve("jvm-b");
        for (Path output : List.of(machineA, machineB)) {
            boolean first = output.equals(machineA);
            var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Duser.language=" + (first ? "en" : "tr"),
                    "-Duser.country=" + (first ? "US" : "TR"), "-Duser.timezone=" + (first ? "UTC" : "Pacific/Apia"), "-cp", System.getProperty("java.class.path"),
                    PermissionCoverageCli.class.getName(), "check", p.resolve("dataset.json").toString(), p.resolve("intent.json").toString(), p.resolve("register.json").toString(), output.toString());
            Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(p.resolve(first ? "a.log" : "b.log").toFile()).start();
            check(child.waitFor() == 0, "separate JVM match");
        }
        String name = reordered.get("report_sha256") + ".json";
        check(Arrays.equals(Files.readAllBytes(machineA.resolve(name)), Files.readAllBytes(machineB.resolve(name))), "separate JVM byte-identical outputs");
        System.out.println("PASS row-order, repeat, locale and separate-JVM timezone determinism");
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--write-fixtures")) { Fixture f = new Fixture(); f.seal(); f.write(Path.of(args[1])); return; }
        Path examples = Path.of(args[0]), schemas = Path.of(args[1]); root = Path.of(args[2]); Files.createDirectories(root);
        for (String name : List.of("dataset", "intent", "register", "report")) check(Arrays.equals(bytes(schema(name)), Files.readAllBytes(schemas.resolve(name + ".schema.json"))), "published schema equals executable contract: " + name);
        var example = evaluate(examples); check(example.get("status").equals("SCOPE_MATCH"), "checked-in synthetic example"); labels(example);
        coreCases(); integrityCases(); determinism();
        System.out.println("PermissionCoverageTest: " + scenarios + " scenarios, " + assertions + " assertions passed");
    }
}
