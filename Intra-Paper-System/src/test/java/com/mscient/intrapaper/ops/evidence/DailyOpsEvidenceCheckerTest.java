package com.mscient.intrapaper.ops.evidence;

import com.mscient.intrapaper.data.StrictJsonParser;
import com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceReport.Status;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Synthetic acceptance and adversarial evidence cases; no transport or live sessions. */
public final class DailyOpsEvidenceCheckerTest {
    private static int assertions;
    private static Path fixtures, output;
    private DailyOpsEvidenceCheckerTest() { }

    public static void main(String[] args) throws Exception {
        fixtures = Path.of(args[0]); output = Path.of(args[1]);
        Files.createDirectories(output.resolve("reports"));
        fixtureMatrix();
        missingEvidence();
        contradictions();
        reconciliation();
        commandAndEncoding();
        System.out.println("Daily operations evidence checks passed: " + assertions + " assertions in 5 synthetic groups.");
    }

    private static void fixtureMatrix() throws Exception {
        List<Object> cases = list(StrictJsonParser.parse(Files.readString(fixtures.resolve("expected-results.json"))));
        for (Object item : cases) {
            Map<String, Object> row = map(item);
            String name = (String) row.get("fixture");
            Path folder = fixtures.resolve(name);
            Map<String, String> before = hashes(folder);
            DailyOpsEvidenceReport report = check(folder);
            equal(report.status().name(), row.get("status"), name + " status");
            equal(report.results().size(), 7, name + " seven domains");
            equal(report.inputHashes().size(), 5, name + " hashes bundle and four artifacts");
            equal(report.inputHashes().get("bundle"), sha(Files.readAllBytes(folder.resolve("session-evidence.json"))), name + " exact bundle hash");
            for (var entry : report.inputHashes().entrySet()) {
                if (!entry.getKey().equals("bundle")) equal(entry.getValue(), sha(Files.readAllBytes(folder.resolve(entry.getKey()))), name + " actual input hash");
            }
            byte[] first = report.bytes();
            for (int repeat = 0; repeat < 3; repeat++) bytes(first, check(folder).bytes(), name + " repeated report bytes");
            Map<String, Object> rendered = map(StrictJsonParser.parse(new String(first, StandardCharsets.UTF_8)));
            equal(rendered.get("evidence_basis"), DailyOpsEvidenceReport.EVIDENCE_BASIS, name + " evidence basis");
            equal(rendered.get("g4_closed"), false, name + " G4 remains open");
            equal(rendered.get("operational_readiness_eligible"), false, name + " operational readiness ineligible");
            for (Object result : list(rendered.get("results"))) {
                equal(map(result).get("input_hashes"), rendered.get("input_hashes"), name + " per-result input hashes");
                equal(map(result).get("evidence_basis"), DailyOpsEvidenceReport.EVIDENCE_BASIS, name + " per-result diagnostic classification");
            }
            Path relocated = copy(name + "-relocated", name);
            Files.setLastModifiedTime(relocated.resolve("artifacts/input.json"), FileTime.fromMillis(1000));
            bytes(first, check(relocated).bytes(), name + " report independent of location and metadata");
            equal(before, hashes(folder), name + " caller evidence remains unchanged");
            Files.write(output.resolve("reports/" + name + ".json"), first);
        }
        domain(check(fixtures.resolve("missing-acknowledgements")), "operator_acknowledgements_incident_stop_records", Status.INCOMPLETE);
        domain(check(fixtures.resolve("stale-inputs")), "input_feed_coverage_stale_handling", Status.FAIL);
        domain(check(fixtures.resolve("open-positions")), "end_positions_pnl_fee_reconciliation", Status.FAIL);
        domain(check(fixtures.resolve("unmatched-events")), "risk_gateway_reservation_reconciliation", Status.FAIL);
        domain(check(fixtures.resolve("hash-tampering")), "replay_run_identity_artifact_integrity", Status.FAIL);
        domain(check(fixtures.resolve("missing-settlement-costs")), "settlement_cost_coverage", Status.INCOMPLETE);
    }

    private static void missingEvidence() throws Exception {
        for (String name : List.of("pre_session", "coverage", "risk", "run", "settlement", "closing", "operator")) {
            mutation("absent-" + name, root -> root.remove(name), Status.INCOMPLETE);
        }
        mutation("missing-schema", root -> root.remove("schema_version"), Status.INCOMPLETE);
        mutation("missing-run-id", root -> root.remove("run_id"), Status.INCOMPLETE);
        mutation("missing-pre-result", root -> map(list(map(root, "pre_session").get("checks")).getFirst()).remove("passed"), Status.INCOMPLETE);
        mutation("null-pre-time", root -> map(list(map(root, "pre_session").get("checks")).getFirst()).put("checked_at_utc", null), Status.INCOMPLETE);
        mutation("blank-pre-time", root -> map(list(map(root, "pre_session").get("checks")).getFirst()).put("checked_at_utc", ""), Status.INCOMPLETE);
        mutation("missing-feed-ack", root -> window(root).remove("acknowledged_at_utc"), Status.INCOMPLETE);
        mutation("missing-age-threshold", root -> window(root).remove("max_age_ms"), Status.INCOMPLETE);
        mutation("missing-receive-time", root -> map(list(window(root).get("observations")).get(3)).remove("observed_at_utc"), Status.INCOMPLETE);
        mutation("empty-feed-events", root -> { window(root).put("observations", new ArrayList<>()); window(root).put("expected_observation_count", 0); }, Status.INCOMPLETE);
        mutation("empty-risk-events", root -> { map(root, "risk").put("events", new ArrayList<>()); map(root, "risk").put("expected_event_count", 0); }, Status.INCOMPLETE);
        mutation("partial-risk-ledger", root -> map(root, "risk").put("complete", false), Status.INCOMPLETE);
        mutation("missing-risk-event-count", root -> map(root, "risk").remove("expected_event_count"), Status.INCOMPLETE);
        mutation("empty-executions", root -> { map(root, "closing").put("executions", new ArrayList<>()); map(root, "closing").put("expected_execution_count", 0); }, Status.INCOMPLETE);
        mutation("missing-end-position", root -> map(root, "closing", "positions").remove("SYNTH-FUT"), Status.INCOMPLETE);
        for (String field : List.of("initial_cash", "gross_pnl", "fees", "net_pnl", "ending_cash")) mutation("missing-" + field,
                root -> map(root, "closing").remove(field), Status.INCOMPLETE);
        mutation("unknown-settlement-applicability", root -> map(root, "settlement").remove("applicable"), Status.INCOMPLETE);
        mutation("unknown-instrument-settlement", root -> map(list(root.get("instruments")).getFirst()).remove("settlement_due"), Status.INCOMPLETE);
        mutation("empty-cost-lines", root -> { map(root, "settlement", "costs").put("charges", new ArrayList<>()); map(root, "settlement", "costs").put("expected_charge_count", 0); }, Status.INCOMPLETE);
        mutation("null-cost-amount", root -> map(list(map(root, "settlement", "costs").get("charges")).getFirst()).put("amount", null), Status.INCOMPLETE);
        mutation("empty-operator-acks", root -> { map(root, "operator").put("acknowledgements", new ArrayList<>()); map(root, "operator").put("expected_acknowledgement_count", 0); }, Status.INCOMPLETE);
        for (String field : List.of("incident_count", "stop_count", "incident_reviewed_at_utc", "stop_reviewed_at_utc", "no_incidents_confirmed", "no_stops_confirmed")) {
            mutation("missing-" + field, root -> map(root, "operator").remove(field), Status.INCOMPLETE);
        }
        mutation("missing-replay-pairs", root -> map(root, "run").remove("replay_pairs"), Status.INCOMPLETE);
        mutation("missing-config-artifact", root -> {
            List<Object> artifacts = list(map(root, "run").get("artifacts"));
            artifacts.removeIf(row -> "CONFIG".equals(map(row).get("role")));
            map(root, "run").put("expected_artifact_count", artifacts.size());
        }, Status.INCOMPLETE);
        mutation("unknown-end-of-earlier-window", root -> {
            Map<String, Object> first = window(root), second = new TreeMap<>(first);
            List<Object> observations = list(first.get("observations"));
            second.put("window_id", "W-2"); second.put("start_utc", "2026-11-02T03:47:00Z");
            second.put("observations", new ArrayList<>(observations.subList(4, 7))); second.put("expected_observation_count", 3);
            first.put("end_utc", null); first.put("observations", new ArrayList<>(observations.subList(0, 4))); first.put("expected_observation_count", 4);
            list(map(root, "coverage").get("windows")).add(second); map(root, "coverage").put("expected_window_count", 2);
            map(list(map(root, "risk").get("events")).get(2)).put("coverage_window_id", "W-2");
        }, Status.INCOMPLETE);
        mutation("missing-expected-hash", root -> map(list(map(root, "run").get("artifacts")).getFirst()).remove("sha256"), Status.INCOMPLETE);
        Path missingInput = copy("missing-input-file", "clean-completion");
        Files.delete(missingInput.resolve("artifacts/input.json"));
        DailyOpsEvidenceReport absent = check(missingInput);
        equal(absent.status(), Status.INCOMPLETE, "missing file is unknown");
        equal(absent.inputHashes().get("artifacts/input.json"), null, "missing input hash remains unknown");
        domain(absent, "input_feed_coverage_stale_handling", Status.INCOMPLETE);
        Path missingOutput = copy("missing-output-file", "clean-completion");
        Files.delete(missingOutput.resolve("artifacts/output.json"));
        equal(check(missingOutput).status(), Status.INCOMPLETE, "missing output file cannot establish success");
        Path missing = output.resolve("missing-bundle/session-evidence.json");
        DailyOpsEvidenceReport unknown = DailyOpsEvidenceChecker.check(missing);
        equal(unknown.status(), Status.INCOMPLETE, "missing bundle");
        equal(unknown.inputHashes().get("bundle"), null, "missing bundle hash unknown");
        for (var result : unknown.results()) equal(result.status(), Status.INCOMPLETE, "missing bundle leaves every domain unknown");
        bytes(unknown.bytes(), DailyOpsEvidenceChecker.check(missing).bytes(), "missing-bundle deterministic bytes");
        Path empty = copy("empty-bundle", "clean-completion");
        Files.writeString(empty.resolve("session-evidence.json"), "{}\n");
        equal(check(empty).status(), Status.INCOMPLETE, "empty object is incomplete");
    }

    private static void contradictions() throws Exception {
        mutation("failed-pre-check", root -> map(list(map(root, "pre_session").get("checks")).getFirst()).put("passed", false), Status.FAIL);
        mutation("pre-check-too-late", root -> map(list(map(root, "pre_session").get("checks")).getFirst()).put("checked_at_utc", "2026-11-02T04:00:00Z"), Status.FAIL);
        mutation("pre-review-before-checks", root -> map(root, "pre_session").put("checked_at_utc", "2026-11-02T03:43:30Z"), Status.FAIL);
        mutation("pre-reconciliation-before-snapshot", root -> map(root, "risk", "initial").put("timestamp_utc", "2026-11-02T03:44:20Z"), Status.FAIL);
        mutation("closing-review-before-run", root -> map(root, "closing").put("checked_at_utc", "2026-11-02T03:48:05Z"), Status.FAIL);
        mutation("risk-snapshot-after-review", root -> map(root, "risk", "final").put("timestamp_utc", "2026-11-02T03:48:21Z"), Status.FAIL);
        mutation("ack-before-cost-review", root -> map(root, "settlement", "costs").put("checked_at_utc", "2026-11-02T03:48:45Z"), Status.FAIL);
        mutation("close-before-register-review", root -> map(root, "operator").put("stop_reviewed_at_utc", "2026-11-02T03:48:45Z"), Status.FAIL);
        mutation("timestamp-without-utc", root -> window(root).put("acknowledged_at_utc", "2026-11-02T03:44:00+00:00"), Status.FAIL);
        mutation("invalid-date", root -> root.put("session_end_utc", "2026-02-30T03:48:00Z"), Status.FAIL);
        mutation("wrong-field-type", root -> map(root, "closing").put("fees", "2"), Status.FAIL);
        mutation("wrong-run-binding", root -> map(root, "run").put("replay_run_id", "OTHER-RUN"), Status.FAIL);
        mutation("residual-reservation", root -> map(root, "risk", "final").put("reserved_units", 1), Status.FAIL);
        mutation("underreleased-reservation", root -> map(list(map(root, "risk").get("events")).getFirst()).put("quantity", 2), Status.FAIL);
        mutation("duplicate-risk-event", root -> { List<Object> events = list(map(root, "risk").get("events")); events.add(events.getFirst()); map(root, "risk").put("expected_event_count", events.size()); }, Status.FAIL);
        mutation("duplicate-execution", root -> { List<Object> execs = list(map(root, "closing").get("executions")); execs.add(execs.getFirst()); map(root, "closing").put("expected_execution_count", execs.size()); }, Status.FAIL);
        mutation("risk-events-regress", root -> map(list(map(root, "risk").get("events")).get(1)).put("timestamp_utc", "2026-11-02T03:45:00Z"), Status.FAIL);
        mutation("wrong-ending-state", root -> map(root, "risk", "final").put("state", "HALTED"), Status.FAIL);
        mutation("wrong-net-pnl", root -> map(root, "closing").put("net_pnl", 99), Status.FAIL);
        mutation("wrong-gross-pnl", root -> map(root, "closing").put("gross_pnl", 99), Status.FAIL);
        mutation("wrong-fees", root -> map(root, "closing").put("fees", 99), Status.FAIL);
        mutation("wrong-ending-cash", root -> map(root, "closing").put("ending_cash", 99), Status.FAIL);
        mutation("wrong-itemized-fee", root -> map(list(map(root, "settlement", "costs").get("charges")).getFirst()).put("amount", 1), Status.FAIL);
        mutation("charge-outside-date", root -> map(list(map(root, "settlement", "costs").get("charges")).getFirst()).put("effective_to_utc", "2026-11-02T03:45:02Z"), Status.FAIL);
        mutation("unmatched-fee-event", root -> map(list(map(root, "settlement", "costs").get("charges")).getFirst()).put("target_id", "MISSING-X"), Status.FAIL);
        mutation("missing-required-expiry-event", root -> { map(list(root.get("instruments")).getFirst()).put("settlement_due", true); map(root, "settlement").put("applicable", true); }, Status.INCOMPLETE);
        mutation("contradictory-expiry-declaration", root -> map(list(root.get("instruments")).getFirst()).put("settlement_due", true), Status.FAIL);
        mutation("open-severe-incident-count", root -> map(root, "operator").put("open_severe_incident_count", 1), Status.FAIL);
        mutation("traversal-artifact", root -> map(list(map(root, "run").get("artifacts")).getFirst()).put("path", "../outside.json"), Status.FAIL);
        mutation("absolute-artifact", root -> map(list(map(root, "run").get("artifacts")).getFirst()).put("path", "C:/outside.json"), Status.FAIL);
        Path differentReplay = copy("different-replay-bytes", "clean-completion");
        Map<String, Object> root = read(differentReplay);
        Files.writeString(differentReplay.resolve("artifacts/replayed-output.json"), Files.readString(differentReplay.resolve("artifacts/replayed-output.json")) + " \n");
        refreshHashes(differentReplay, root); write(differentReplay, root);
        equal(check(differentReplay).status(), Status.FAIL, "rehashing a different replay cannot make it match");
        Path caseAlias = copy("case-alias-replay", "clean-completion"); root = read(caseAlias);
        map(list(map(root, "run").get("artifacts")).get(3)).put("path", "artifacts/OUTPUT.json");
        map(list(map(root, "run").get("replay_pairs")).getFirst()).put("replayed_path", "artifacts/OUTPUT.json");
        write(caseAlias, root); equal(check(caseAlias).status(), Status.FAIL, "case aliases cannot supply independent artifact files");
        Path linked = copy("hard-linked-replay", "clean-completion"); root = read(linked);
        Files.createLink(linked.resolve("artifacts/replayed-linked.json"), linked.resolve("artifacts/output.json"));
        map(list(map(root, "run").get("artifacts")).get(3)).put("path", "artifacts/replayed-linked.json");
        map(list(map(root, "run").get("replay_pairs")).getFirst()).put("replayed_path", "artifacts/replayed-linked.json");
        write(linked, root); equal(check(linked).status(), Status.FAIL, "hard links cannot supply independent replay evidence");
    }

    private static void reconciliation() throws Exception {
        Path shortSession = copy("short-round-trip", "clean-completion");
        Map<String, Object> root = read(shortSession);
        List<Object> execs = list(map(root, "closing").get("executions"));
        map(execs.get(0)).put("side", "SELL"); map(execs.get(0)).put("price", 110);
        map(execs.get(1)).put("side", "BUY"); map(execs.get(1)).put("price", 100);
        List<Object> events = list(map(root, "risk").get("events"));
        for (int i = 0; i < events.size(); i++) map(events.get(i)).put("side", i < 2 ? "SELL" : "BUY");
        write(shortSession, root);
        equal(check(shortSession).status(), Status.PASS, "signed short execution and P&L reconciliation");
        Path partial = copy("partial-fill-cancel", "clean-completion"); root = read(partial);
        events = list(map(root, "risk").get("events")); map(events.getFirst()).put("quantity", 2);
        Map<String, Object> cancel = new TreeMap<>(map(events.get(1))); cancel.remove("execution_id");
        cancel.put("event_id", "C-REMAINDER"); cancel.put("kind", "CANCEL"); cancel.put("timestamp_utc", "2026-11-02T03:45:03Z");
        events.add(2, cancel); map(root, "risk").put("expected_event_count", 5); write(partial, root);
        equal(check(partial).status(), Status.PASS, "partial fill and cancel release reconcile");
        Path negativeSettlement = copy("signed-short-settlement", "settled-session"); root = read(negativeSettlement);
        map(list(map(root, "closing").get("executions")).getFirst()).put("side", "SELL");
        map(list(map(root, "closing").get("executions")).getFirst()).put("price", 110);
        events = list(map(root, "risk").get("events")); map(events.get(0)).put("side", "SELL"); map(events.get(1)).put("side", "SELL");
        map(events.get(2)).put("signed_quantity", -1);
        Map<String, Object> settlement = map(list(map(root, "settlement").get("events")).getFirst());
        settlement.put("signed_quantity", -1); settlement.put("price", 100); settlement.put("cash_amount", -100);
        write(negativeSettlement, root);
        equal(check(negativeSettlement).status(), Status.PASS, "signed short cash settlement reconciles");
        Path halted = copy("halt-with-missing-stop", "halted-session"); root = read(halted);
        map(root, "operator").put("complete", false); map(root, "operator").put("stops", new ArrayList<>());
        write(halted, root); equal(check(halted).status(), Status.INCOMPLETE, "halt cannot pass without stop evidence");
        Path badStop = copy("halt-action-not-completed", "halted-session"); root = read(badStop);
        map(list(map(root, "operator").get("stops")).getFirst()).put("positions_reconciled", false);
        write(badStop, root); equal(check(badStop).status(), Status.FAIL, "unsuccessful halt reconciliation fails");
        Path wrongKind = copy("halt-bound-to-pause", "halted-session"); root = read(wrongKind);
        map(list(map(root, "operator").get("stops")).getFirst()).put("kind", "PAUSE");
        write(wrongKind, root); domain(check(wrongKind), "risk_gateway_reservation_reconciliation", Status.FAIL);

        Path paused = copy("newer-pause-observation", "clean-completion"); root = read(paused);
        Map<String, Object> sample = new TreeMap<>(map(list(window(root).get("observations")).getFirst()));
        sample.put("event_id", "Q-PAUSE"); sample.put("observed_at_utc", "2026-11-02T03:45:00.500Z"); sample.put("input_at_utc", "2026-11-02T03:45:00.500Z");
        sample.put("new_risk_allowed", false); sample.put("action", "PAUSE"); sample.put("incident_id", "INC-PAUSE"); sample.put("stop_id", "STOP-PAUSE");
        list(window(root).get("observations")).add(1, sample); window(root).put("expected_observation_count", 8);
        Map<String, Object> haltTemplate = read(fixtures.resolve("halted-session"));
        Map<String, Object> incident = new TreeMap<>(map(list(map(haltTemplate, "operator").get("incidents")).getFirst()));
        incident.put("incident_id", "INC-PAUSE"); incident.put("opened_at_utc", "2026-11-02T03:45:00.500Z");
        incident.put("acknowledged_at_utc", "2026-11-02T03:45:00.750Z"); incident.put("resolved_at_utc", "2026-11-02T03:45:30Z");
        Map<String, Object> stop = new TreeMap<>(map(list(map(haltTemplate, "operator").get("stops")).getFirst()));
        stop.put("stop_id", "STOP-PAUSE"); stop.put("kind", "PAUSE"); stop.put("incident_id", "INC-PAUSE");
        stop.put("requested_at_utc", "2026-11-02T03:45:00.500Z"); stop.put("effective_at_utc", "2026-11-02T03:45:00.500Z"); stop.put("acknowledged_at_utc", "2026-11-02T03:45:00.750Z");
        map(root, "operator").put("incidents", new ArrayList<>(List.of(incident))); map(root, "operator").put("incident_count", 1); map(root, "operator").put("no_incidents_confirmed", false);
        map(root, "operator").put("stops", new ArrayList<>(List.of(stop))); map(root, "operator").put("stop_count", 1); map(root, "operator").put("no_stops_confirmed", false);
        Map<String, Object> input = map(StrictJsonParser.parse(Files.readString(paused.resolve("artifacts/input.json"))));
        input.put("observations", window(root).get("observations")); input.put("expected_observation_count", 8);
        Files.writeString(paused.resolve("artifacts/input.json"), DailyOpsEvidenceReport.json(input) + "\n");
        refreshHashes(paused, root); write(paused, root);
        domain(check(paused), "risk_gateway_reservation_reconciliation", Status.FAIL);
        domain(check(paused), "input_feed_coverage_stale_handling", Status.PASS);
        map(root, "coverage").put("complete", false); write(paused, root);
        domain(check(paused), "risk_gateway_reservation_reconciliation", Status.INCOMPLETE);
    }

    private static void commandAndEncoding() throws Exception {
        Path clean = fixtures.resolve("clean-completion"), reportFile = output.resolve("cli-report.json");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream(), stderr = new ByteArrayOutputStream();
        try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8); PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            equal(DailyOpsEvidenceCli.run(new String[]{"check", clean.resolve("session-evidence.json").toString(), reportFile.toString()}, out, err), 0, "CLI PASS exit");
            bytes(Files.readAllBytes(reportFile), check(clean).bytes(), "CLI report bytes");
            equal(DailyOpsEvidenceCli.run(new String[]{"check", fixtures.resolve("unmatched-events/session-evidence.json").toString()}, out, err), 1, "CLI FAIL exit");
            equal(DailyOpsEvidenceCli.run(new String[]{"check", fixtures.resolve("missing-acknowledgements/session-evidence.json").toString()}, out, err), 2, "CLI INCOMPLETE exit");
            equal(DailyOpsEvidenceCli.run(new String[]{"check", clean.resolve("session-evidence.json").toString(), clean.resolve("session-evidence.json").toString()}, out, err), 74, "CLI refuses input overwrite");
            equal(DailyOpsEvidenceCli.run(new String[]{"check"}, out, err), 64, "CLI usage exit");
        }
        Path malformed = copy("malformed-utf8", "clean-completion");
        byte[] badBytes = {(byte) 0xc3, (byte) 0x28}; Files.write(malformed.resolve("session-evidence.json"), badBytes);
        DailyOpsEvidenceReport bad = check(malformed); equal(bad.status(), Status.FAIL, "malformed UTF-8 fails");
        equal(bad.inputHashes().get("bundle"), sha(badBytes), "malformed input still records exact hash");
        Path duplicate = copy("duplicate-json-key", "clean-completion"); Files.writeString(duplicate.resolve("session-evidence.json"), "{\"schema_version\":1,\"schema_version\":1}");
        equal(check(duplicate).status(), Status.FAIL, "duplicate JSON keys fail");
        Locale priorLocale = Locale.getDefault(); TimeZone priorZone = TimeZone.getDefault();
        try {
            byte[] baseline = check(clean).bytes(); Locale.setDefault(Locale.FRANCE); TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            bytes(baseline, check(clean).bytes(), "report independent of locale and timezone");
        } finally { Locale.setDefault(priorLocale); TimeZone.setDefault(priorZone); }
    }

    private static void mutation(String name, Consumer<Map<String, Object>> mutate, Status expected) throws Exception {
        Path folder = copy(name, "clean-completion"); Map<String, Object> root = read(folder); mutate.accept(root); write(folder, root);
        DailyOpsEvidenceReport result = check(folder); equal(result.status(), expected, name);
        bytes(result.bytes(), check(folder).bytes(), name + " deterministic report");
        Files.write(output.resolve("reports/" + name + ".json"), result.bytes());
    }
    private static Path copy(String name, String fixture) throws Exception {
        Path source = fixtures.resolve(fixture), destination = output.resolve("bundles").resolve(name); Files.createDirectories(destination);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
            }
        }
        return destination;
    }
    private static DailyOpsEvidenceReport check(Path folder) { return DailyOpsEvidenceChecker.check(folder.resolve("session-evidence.json")); }
    private static Map<String, Object> read(Path folder) throws Exception { return map(StrictJsonParser.parse(Files.readString(folder.resolve("session-evidence.json")))); }
    private static void write(Path folder, Map<String, Object> root) throws Exception { Files.writeString(folder.resolve("session-evidence.json"), DailyOpsEvidenceReport.json(root) + "\n"); }
    private static void refreshHashes(Path folder, Map<String, Object> root) throws Exception {
        for (Object entry : list(map(root, "run").get("artifacts"))) {
            Map<String, Object> artifact = map(entry); String hash = sha(Files.readAllBytes(folder.resolve((String) artifact.get("path"))));
            artifact.put("sha256", hash); if ("CONFIG".equals(artifact.get("role"))) map(root, "risk").put("config_sha256", hash);
        }
    }
    private static Map<String, Object> window(Map<String, Object> root) { return map(list(map(root, "coverage").get("windows")).getFirst()); }
    @SuppressWarnings("unchecked") private static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    private static Map<String, Object> map(Map<String, Object> root, String... keys) { Map<String, Object> value = root; for (String key : keys) value = map(value.get(key)); return value; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>) value; }
    private static Map<String, String> hashes(Path root) throws Exception {
        Map<String, String> hashes = new TreeMap<>(); try (var paths = Files.walk(root)) { for (Path path : paths.filter(Files::isRegularFile).toList()) hashes.put(root.relativize(path).toString(), sha(Files.readAllBytes(path))); } return hashes;
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static void domain(DailyOpsEvidenceReport report, String name, Status expected) { equal(report.results().stream().filter(result -> result.check().equals(name)).findFirst().orElseThrow().status(), expected, name); }
    private static void bytes(byte[] expected, byte[] actual, String message) { assertions++; if (!Arrays.equals(expected, actual)) throw new AssertionError(message); }
    private static void equal(Object actual, Object expected, String message) { assertions++; if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(message + ": expected=" + expected + " actual=" + actual); }
}
