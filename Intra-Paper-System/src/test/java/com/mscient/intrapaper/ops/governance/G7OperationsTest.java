package com.mscient.intrapaper.ops.governance;

import static com.mscient.intrapaper.ops.governance.G7Json.*;
import static com.mscient.intrapaper.ops.governance.G7SyntheticFixtures.*;
import com.mscient.intrapaper.audit.portfolio.PortfolioRiskAudit;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** SYNTHETIC ONLY. Tests software evidence handling; never operational readiness or human authentication. */
public final class G7OperationsTest {
    private static Path project, root, jar;
    private static int checks, scenarios, processes;
    private static final List<String> limitations = new ArrayList<>();
    @FunctionalInterface private interface Edit { void apply(Fixture f) throws Exception; }
    @FunctionalInterface private interface Attempt { void run() throws Exception; }
    private record Invocation(int code, Map<String, Object> value) { }
    private G7OperationsTest() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) throw new IllegalArgumentException("Synthetic test project/output [commit] required");
        project = G7Files.local(Path.of(args[0])); root = G7Files.local(Path.of(args[1])).resolve("synthetic-cases"); Files.createDirectory(root);
        jar = Path.of(G7Cli.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (args.length == 3) check(args[2].equals(G7Cli.buildCommit()), "packaged Build-Commit equals expected tested revision");
        prerequisiteCases(); coverageCases(); driftCases(); adapterCases(); reconciliationCases(); incidentCases();
        historyCases(); decisionCases(); fileCases(); cliCases(); publishedContracts();
        Map<String, Object> result = map("schema", "g7-synthetic-test-result/v1", "classification", "SYNTHETIC_TESTS_ONLY", "checks", checks,
                "scenarios", scenarios, "child_processes", processes, "build_commit", G7Cli.buildCommit(), "code_sha256", G7Cli.codeHash(),
                "limitations", limitations, "g7_closed", false, "operation_authorized", false, "scale_authorized", false);
        Files.write(root.getParent().resolve("focused-result.json"), bytes(result), StandardOpenOption.CREATE_NEW);
        System.out.println("G7 SYNTHETIC checks passed: " + checks + "; scenarios=" + scenarios + "; child_processes=" + processes);
        for (String limit : limitations) System.out.println("SYNTHETIC test limitation: " + limit);
    }
    private static Fixture fixture(String name) throws Exception {
        scenarios++; System.out.println("SYNTHETIC " + name); return new Fixture(root.resolve(name));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void rejects(Attempt action, String message) throws Exception {
        boolean rejected = false; try { action.run(); } catch (IOException | IllegalArgumentException | IllegalStateException e) { rejected = true; }
        check(rejected, message);
    }
    private static void authority(Map<String, Object> value) {
        check(Boolean.FALSE.equals(value.get("g7_closed")), "proposal never closes G7");
        check(Boolean.FALSE.equals(value.get("operation_authorized")), "no operation authority");
        check(Boolean.FALSE.equals(value.get("scale_authorized")), "no scale authority");
        check(Set.of("FAIL", "BLOCKED", "INCOMPLETE", "ELIGIBLE_FOR_HUMAN_REVIEW").contains(value.get("status")), "only specified statuses");
        check(!"ELIGIBLE_FOR_HUMAN_REVIEW".equals(value.get("status")), "eligibility unavailable without provisioned authentication");
    }
    private static Map<String, Object> evaluate(Fixture f) throws Exception { return evaluate(f, null); }
    private static Map<String, Object> evaluate(Fixture f, byte[] history) throws Exception {
        Map<String, Object> result = G7Cli.evaluate(f.root, history); authority(result); return result;
    }
    private static Map<String, Object> accepted(String name, Edit edit, String status, String... codes) throws Exception {
        Fixture f = fixture(name); edit.apply(f); f.write(); Map<String, Object> result = evaluate(f);
        Files.write(root.resolve(name + "-diagnostic.json"), bytes(result), StandardOpenOption.CREATE_NEW);
        check(status.equals(result.get("status")), name + " status: " + result.get("status")); for (String code : codes) finding(result, code); return result;
    }
    private static Map<String, Object> finding(Map<String, Object> report, String code) {
        List<Map<String, Object>> matching = list(report.get("findings")).stream().map(G7Json::obj).filter(f -> code.equals(f.get("code"))).toList();
        check(!matching.isEmpty(), "finding required: " + code); return matching.getFirst();
    }
    private static boolean has(Map<String, Object> report, String code) { return list(report.get("findings")).stream().map(G7Json::obj).anyMatch(f -> code.equals(f.get("code"))); }
    private static void observation(Map<String, Object> report, String kind) {
        check(list(report.get("observations")).stream().map(G7Json::obj).anyMatch(o -> kind.equals(o.get("kind"))), "existing verifier observation: " + kind);
    }
    private static Invocation invoke(String... args) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); int code;
        try (PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8)) { code = G7Cli.run(args, stream); }
        Map<String, Object> value = obj(parse(output.toByteArray())); authority(value); return new Invocation(code, value);
    }
    private static void rejectedEvaluation(Fixture f, String name) throws Exception {
        Path destination = root.resolve(name + "-rejected-output"); Invocation result = invoke("evaluate", f.root.toString(), destination.toString());
        check(result.code == 1 && "FAIL".equals(result.value.get("status")), "invalid evidence rejected"); check(!Files.exists(destination), "rejected input is never published");
    }

    private static void prerequisiteCases() throws Exception {
        Map<String, Object> clean = accepted("clean-looking", f -> {}, "BLOCKED", "AUTHENTICATION_ADAPTERS_UNAVAILABLE", "HUMAN_DECISIONS_PENDING");
        check(!list(clean.get("findings")).stream().map(G7Json::obj).anyMatch(f -> "FAIL".equals(f.get("status"))), "clean-looking synthetic fixture has no hidden passing gate");
        for (String gate : G7Schema.GATES) {
            int index = G7Schema.GATES.indexOf(gate);
            accepted("missing-" + gate, f -> list(f.manifest.get("gates")).remove(index), "BLOCKED", "GATE_APPROVAL_ABSENT");
            accepted("expired-" + gate, f -> obj(list(f.manifest.get("gates")).get(index)).put("expires", AS_OF.toString()), "BLOCKED", "GATE_EXPIRED_OR_LATE");
            accepted("mismatched-" + gate, f -> obj(list(f.manifest.get("gates")).get(index)).put("envelope_sha256", "a".repeat(64)), "BLOCKED", "GATE_ENVELOPE_MISMATCH", "GATE_DECISION_MISMATCH");
        }
        accepted("incomplete-gate-originals", f -> obj(list(f.manifest.get("gates")).getFirst()).put("complete", null), "BLOCKED", "GATE_EVIDENCE_INCOMPLETE");
        Fixture absent = fixture("missing-original-bytes"); absent.write(); Files.delete(absent.root.resolve("gates/G0-original.txt"));
        Map<String, Object> missing = evaluate(absent); finding(missing, "ARTIFACT_MISSING"); finding(missing, "GATE_AUTHENTICATION_UNESTABLISHED");
        Fixture mismatch = fixture("mismatched-original-bytes"); mismatch.write(); Files.writeString(mismatch.root.resolve("gates/G6-original.txt"), "SYNTHETIC changed original\n");
        check("FAIL".equals(evaluate(mismatch).get("status")), "byte mismatch takes precedence over block/incomplete");
        accepted("identity-and-review-absent", f -> {
            for (String field : List.of("id", "identity_evidence", "qualification_evidence", "independence_evidence")) obj(f.manifest.get("reviewer")).put(field, null);
            obj(f.manifest.get("owner")).put("id", null);
        }, "BLOCKED", "HUMAN_IDENTITY_UNKNOWN", "REVIEWER_QUALIFICATIONS_UNAUTHENTICATED", "MISSING_EVIDENCE");
        accepted("self-review", f -> obj(f.manifest.get("reviewer")).put("id", obj(f.manifest.get("owner")).get("id")), "FAIL", "SELF_OR_AI_REVIEW");
        accepted("ai-review", f -> obj(f.manifest.get("reviewer")).put("kind", "AI"), "FAIL", "SELF_OR_AI_REVIEW");
        accepted("ai-gate-decision", f -> { var d = read(f, "gates/G0-decision.json"); d.put("signer_kind", "AI"); f.replaceBytes("gates/G0-decision.json", d); }, "BLOCKED", "GATE_NONHUMAN_OR_UNKNOWN_DECISION");
        accepted("signed-unprovisioned-identity", f -> {
            var d = read(f, "gates/G0-decision.json"); sign(d); f.replaceBytes("gates/G0-decision.json", d); sign(f.a(f.id("roles/reviewer-identity.txt")));
        }, "BLOCKED", "GATE_AUTHENTICATION_UNESTABLISHED", "AUTHENTICATION_ADAPTERS_UNAVAILABLE");
        Fixture badSignature = fixture("invalid-signature");
        fakedSignature(badSignature.a(badSignature.id("roles/owner.txt"))); badSignature.write(); rejectedEvaluation(badSignature, "invalid-signature");
        accepted("synthetic-operational-assertion", f -> f.manifest.put("mode", "CLAIMED_OPERATIONAL"), "FAIL", "SYNTHETIC_IN_OPERATIONAL_BUNDLE");
        accepted("relabelled-synthetic", f -> { f.manifest.put("mode", "CLAIMED_OPERATIONAL"); for (Object a : list(f.bundle.get("artifacts"))) obj(a).put("classification", "CLAIMED_OPERATIONAL"); }, "FAIL", "SYNTHETIC_MISREPRESENTED");
    }
    private static void sign(Map<String, Object> record) throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); Map<String, Object> unsigned = new TreeMap<>(record); unsigned.remove("signature");
        Signature signer = Signature.getInstance("Ed25519"); signer.initSign(pair.getPrivate()); signer.update(bytes(unsigned));
        record.put("signature", map("algorithm", "Ed25519", "public_key", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()), "value", Base64.getEncoder().encodeToString(signer.sign())));
    }
    private static void fakedSignature(Map<String, Object> record) throws Exception {
        sign(record); obj(record.get("signature")).put("value", Base64.getEncoder().encodeToString(new byte[64]));
    }

    private static void coverageCases() throws Exception {
        Map<String, Object> missing = accepted("explicit-missing-period", G7SyntheticFixtures::missingPeriod, "BLOCKED", "PERIOD_MISSING");
        check("INCOMPLETE".equals(finding(missing, "PERIOD_MISSING").get("status")), "missing periods stay incomplete");
        accepted("unaccounted-period", f -> list(f.bundle.get("periods")).clear(), "BLOCKED", "PERIOD_UNACCOUNTED");
        accepted("partial-period", f -> f.p().put("observed_start", START.plusSeconds(1).toString()), "BLOCKED", "PARTIAL_PERIOD_COVERAGE");
        accepted("missing-category", f -> list(f.p().get("evidence")).remove(f.id("observations/ORDERS.txt")), "BLOCKED", "CATEGORY_EVIDENCE_MISSING");
        accepted("stale-artifact", f -> f.a(f.id("observations/FEED.txt")).put("observed_at", AS_OF.minusSeconds(172800).toString()), "BLOCKED", "STALE_EVIDENCE");
        accepted("unknown-coverage", f -> { f.a(f.id("observations/CASH.txt")).put("complete", null); f.a(f.id("observations/CASH.txt")).put("coverage", null); }, "BLOCKED", "ARTIFACT_COVERAGE_UNKNOWN", "ARTIFACT_WINDOW_UNKNOWN");
        accepted("partial-artifact", f -> f.a(f.id("observations/MARGIN.txt")).put("coverage", map("start", START.plusSeconds(1).toString(), "end", END.toString())), "BLOCKED", "ARTIFACT_PARTIAL_COVERAGE");
        accepted("cadence-exceeded", f -> obj(f.manifest.get("rules")).put("cadence_seconds", 120), "BLOCKED", "CADENCE_COVERAGE_EXCEEDED");
        accepted("review-stale", f -> f.manifest.put("as_of", AS_OF.plusSeconds(172800).toString()), "BLOCKED", "REVIEW_STALE");
        accepted("rules-and-unknowns", f -> { f.manifest.put("rules", null); obj(f.p().get("envelope")).put("BUILD", null); }, "BLOCKED", "OWNER_RULES_ABSENT", "ENVELOPE_IDENTITY_UNKNOWN");
        accepted("excluded-period", f -> { var s = obj(list(f.bundle.get("schedule")).getFirst()); s.put("status", "EXCLUDED"); s.put("reason", "SYNTHETIC explicit exclusion"); }, "BLOCKED", "PERIOD_EXCLUDED");
        accepted("missing-period-with-observations", f -> { f.p().put("status", "MISSING"); f.p().put("reason", "SYNTHETIC contradictory record"); }, "FAIL", "INVALID_MISSING_PERIOD", "MISSING_PERIOD_CONTAINS_OBSERVATIONS");
    }
    private static void driftCases() throws Exception {
        for (String domain : G7Schema.DOMAINS) {
            Map<String, Object> result = accepted("drift-" + domain, f -> drift(f, domain, true), "BLOCKED", "ENVELOPE_DRIFT", "MATERIAL_CHANGE", "HUMAN_STOP_REQUIRED", "REQUALIFICATION_REQUIRED");
            check(!list(finding(result, "REQUALIFICATION_REQUIRED").get("required_action_refs")).isEmpty(), "requalification points to exact declared human procedure");
        }
        accepted("unlogged-drift", f -> drift(f, "BUILD", false), "FAIL", "UNLOGGED_DRIFT");
        accepted("estimate-in-observations", f -> f.a(f.id("observations/CASH.txt")).put("classification", "ESTIMATE"), "FAIL", "ESTIMATE_USED_AS_OBSERVATION");
    }
    private static void adapterCases() throws Exception {
        Map<String, Object> feedClean = accepted("feed-reperformed", f -> feed(f, false, false), "BLOCKED"); observation(feedClean, "FEED_EXISTING_VERIFIER"); check(!has(feedClean, "ADAPTER_REJECTED"), "feed adapter performs existing verifier");
        accepted("feed-gap", f -> feed(f, true, false), "FAIL", "FEED_EVENT_GAP");
        Map<String, Object> unknownFeed = accepted("feed-unknown-policy", f -> feed(f, false, true), "BLOCKED");
        check(list(unknownFeed.get("findings")).stream().map(G7Json::obj).anyMatch(x -> str(x, "code").startsWith("FEED_") && "INCOMPLETE".equals(x.get("status"))), "feed unknown thresholds retained");
        accepted("feed-missing-universe", f -> { feed(f, false, false); f.p().put("feed_scope_ids", null); }, "BLOCKED", "FEED_UNIVERSE_UNKNOWN");
        accepted("feed-wrong-universe", f -> { feed(f, false, false); f.p().put("feed_scope_ids", List.of("missing-source")); }, "FAIL", "FEED_UNIVERSE_MISMATCH");
        accepted("feed-path-escape", f -> { feed(f, false, false); var m = read(f, "feed/evidence.json"); obj(m.get("schedule")).put("path", "../schedule.json"); f.replaceBytes("feed/evidence.json", m); }, "FAIL", "ADAPTER_REJECTED");
        Map<String, Object> dailyClean = accepted("daily-reperformed", f -> daily(f, project), "BLOCKED"); observation(dailyClean, "DAILY_EXISTING_VERIFIER"); check(!has(dailyClean, "ADAPTER_REJECTED"), "daily adapter performs existing verifier");
        accepted("daily-cash-mismatch", f -> { daily(f, project); var d = read(f, "daily/session-evidence.json"); obj(d.get("closing")).put("ending_cash", 1009); f.replaceBytes("daily/session-evidence.json", d); }, "FAIL", "DAILY_END_POSITIONS_PNL_FEE_RECONCILIATION");
        accepted("daily-configuration-mismatch", f -> { daily(f, project); var c = read(f, "configuration.json"); c.put("daily_ops_configuration", f.id("roles/owner.txt")); f.replaceBytes("configuration.json", c); f.refreshGates(); }, "FAIL", "DAILY_CONFIGURATION_MISMATCH");
        Fixture portfolio = fixture("portfolio-risk-settlement-reperformed"); G7SyntheticFixtures.portfolio(portfolio, project); portfolio.write(); Map<String, Object> complete = evaluate(portfolio);
        observation(complete, "PORTFOLIO_ACCOUNTING_SETTLEMENT"); observation(complete, "RISK_EXISTING_VERIFIER"); observation(complete, "SETTLEMENT_EXISTING_VERIFIER");
        check(!has(complete, "ADAPTER_REJECTED") && !has(complete, "ADAPTER_INPUTS_MISSING"), "all original replay/risk/settlement inputs re-performed");
        finding(complete, "REPLAY_OPERATIONAL_COVERAGE_UNESTABLISHED"); finding(complete, "SETTLEMENT_SOURCE_AUTHENTICATION_UNAVAILABLE");
        Fixture missingReplay = fixture("replay-originals-missing"); G7SyntheticFixtures.portfolio(missingReplay, project); missingReplay.p().put("portfolio_inputs", null); missingReplay.write(); finding(evaluate(missingReplay), "ADAPTER_INPUTS_MISSING");
        Fixture tamperedReplay = fixture("replay-accounting-tampered"); G7SyntheticFixtures.portfolio(tamperedReplay, project);
        String runFile = str(tamperedReplay.a(tamperedReplay.p().get("portfolio_run")), "path"); String runFolder = runFile.substring(0, runFile.lastIndexOf('/') + 1);
        String target = tamperedReplay.byPath.keySet().stream().filter(p -> p.startsWith(runFolder) && p.endsWith("executions.csv")).findFirst().orElseThrow();
        tamperedReplay.replaceBytes(target, utf8(Files.readAllBytes(tamperedReplay.root.resolve(target))) + "\n"); tamperedReplay.write(); finding(evaluate(tamperedReplay), "ADAPTER_REJECTED");
        Fixture breach = fixture("risk-breach"); G7SyntheticFixtures.portfolio(breach, project); riskVariant(breach, true); breach.write(); finding(evaluate(breach), "RISK_BREACH");
        Fixture unknownRisk = fixture("risk-unknown-coverage"); G7SyntheticFixtures.portfolio(unknownRisk, project); riskVariant(unknownRisk, false); unknownRisk.write(); finding(evaluate(unknownRisk), "RISK_INCOMPLETE");
        Fixture missingSettlement = fixture("settlement-originals-missing"); G7SyntheticFixtures.portfolio(missingSettlement, project); missingSettlement.p().put("settlement_events", null); missingSettlement.write(); finding(evaluate(missingSettlement), "ADAPTER_INPUTS_MISSING");
        Fixture wrongSettings = fixture("replay-config-member-mismatch"); G7SyntheticFixtures.portfolio(wrongSettings, project);
        var config = read(wrongSettings, "configuration.json"); config.put("portfolio_settings", wrongSettings.id("roles/owner.txt")); wrongSettings.replaceBytes("configuration.json", config); wrongSettings.refreshGates(); wrongSettings.write(); finding(evaluate(wrongSettings), "REPLAY_CONFIGURATION_MISMATCH");
    }
    private static void riskVariant(Fixture f, boolean breach) throws Exception {
        String header = "instrument_id,effective_from,effective_to,max_abs_units,max_gross_notional_inr,source_id\n";
        f.replaceBytes("risk-inputs/limits.csv", header + "F,2030-05-06,2030-05-07," + (breach ? "1,1" : "100,1000000") + ",s\n" + (breach ? "O,2030-05-06,2030-05-07,1,1,s\n" : ""));
        Path run = f.root.resolve(str(f.a(f.p().get("portfolio_run")), "path")).getParent();
        var audit = PortfolioRiskAudit.audit(run, f.root.resolve("portfolio-inputs/inputs.csv"), f.root.resolve("portfolio-inputs/portfolio.properties"), f.root.resolve("risk-inputs"), f.root.resolveSibling(f.root.getFileName() + "-variant"));
        try (var files = Files.list(audit.path())) { for (Path file : files.sorted().toList()) f.add("risk-variants/" + audit.auditId() + "/" + file.getFileName(), "RISK", utf8(Files.readAllBytes(file))); }
        f.p().put("risk_audit", f.id("risk-variants/" + audit.auditId() + "/audit-manifest.properties"));
    }

    private static Map<String, Object> stream(Map<String, Object> ledger, String kind) { return list(ledger.get("streams")).stream().map(G7Json::obj).filter(s -> kind.equals(s.get("kind"))).findFirst().orElseThrow(); }
    private static void reconciliationCases() throws Exception {
        Map<String, Object> result = accepted("exact-normalized-ledgers", G7SyntheticFixtures::reconciliation, "BLOCKED", "EXTERNAL_LEDGER_ADAPTER_UNAVAILABLE");
        check(!has(result, "EXTERNAL_LEDGER_MISMATCH") && !has(result, "ADAPTER_REJECTED"), "exact ordered declared ledger comparison");
        for (String kind : G7Schema.STREAMS) accepted("ledger-mismatch-" + kind, f -> {
            reconciliation(f); reconcileEdit(f, ledger -> { var values = obj(obj(list(stream(ledger, kind).get("records")).getFirst()).get("values")); String key = new TreeSet<>(values.keySet()).first(); values.put(key, key.equals("amount") || key.equals("price") ? "123.00" : key.equals("units") || key.equals("cancelled_units") ? "2" : "SYNTHETIC_CHANGED"); });
        }, "FAIL", "EXTERNAL_LEDGER_MISMATCH");
        Map<String, Object> unknown = accepted("ledger-unknown-value-count", f -> {
            reconciliation(f); reconcileEdit(f, ledger -> { var s = stream(ledger, "CASH"); s.put("count", null); s.put("complete", null); obj(obj(list(s.get("records")).getFirst()).get("values")).put("amount", null); });
        }, "FAIL", "RECONCILIATION_COVERAGE_UNKNOWN", "NORMALIZED_VALUE_UNKNOWN");
        check(list(unknown.get("observations")).stream().map(G7Json::obj).anyMatch(o -> "CASH".equals(o.get("stream")) && o.get("external_count") == null), "unknown ledger count never becomes zero");
        accepted("ledger-unknown-window", f -> { reconciliation(f); reconcileEdit(f, ledger -> ledger.put("coverage", null)); }, "BLOCKED", "RECONCILIATION_WINDOW_UNKNOWN");
        accepted("ledger-missing-stream", f -> { reconciliation(f); var d = read(f, "ledger/reconciliation.json"); list(d.get("streams")).removeIf(s -> "FEES".equals(obj(s).get("kind"))); f.replaceBytes("ledger/reconciliation.json", d); }, "BLOCKED", "RECONCILIATION_STREAM_MISSING");
        accepted("ledger-exact-decimal-spelling", f -> { reconciliation(f); reconcileEdit(f, ledger -> obj(obj(list(stream(ledger, "FEES").get("records")).getFirst()).get("values")).put("amount", "0.0")); }, "FAIL", "EXTERNAL_LEDGER_MISMATCH");
        accepted("ledger-bad-correction", f -> { reconciliation(f); reconcileEdit(f, ledger -> obj(list(stream(ledger, "CORRECTIONS").get("records")).getFirst()).put("prior_record_id", "omitted-original")); }, "FAIL", "INVALID_EXTERNAL_CORRECTION");
        accepted("ledger-duplicate-record", f -> { reconciliation(f); reconcileEdit(f, ledger -> { var s = stream(ledger, "ORDERS"); var rows = new ArrayList<>(list(s.get("records"))); rows.add(rows.getFirst()); s.put("records", rows); s.put("count", 2); }); }, "FAIL", "ADAPTER_REJECTED");
        accepted("ledger-self-comparison", f -> { reconciliation(f); f.replaceBytes("ledger/external-raw.txt", utf8(Files.readAllBytes(f.root.resolve("ledger/internal-raw.txt")))); }, "FAIL", "SELF_RECONCILIATION");
    }

    private static Map<String, Object> incident(Fixture f) {
        String evidence = f.id("observations/INCIDENTS.txt"); var e = event("incident-01", "INCIDENT", str(f.a(evidence), "sha256"), ID, AS_OF);
        e.put("occurred_at", START.plusSeconds(10).toString()); e.put("scope", "RISK"); e.put("severity", "SEVERE"); e.put("evidence", List.of(evidence)); e.put("response", "SYNTHETIC operator response");
        e.put("stop_status", "REQUIRED"); e.put("required_rereview", List.of("G2", "G4")); f.extraEvents.add(e); return e;
    }
    private static Map<String, Object> stop(Fixture f) {
        String evidence = f.id("observations/INCIDENTS.txt"); var e = event("stop-01", "STOP", str(f.a(evidence), "sha256"), ID, AS_OF);
        e.put("occurred_at", START.plusSeconds(20).toString()); e.put("scope", "RISK"); e.put("evidence", List.of(evidence)); e.put("response", "SYNTHETIC separate stop evidence"); e.put("stop_status", "RECORDED"); e.put("prior_event_id", "incident-01"); f.extraEvents.add(e); return e;
    }
    private static Map<String, Object> recovery(Fixture f, boolean resolution) {
        String evidence = f.id("observations/RECOVERY.txt"); var e = event("recovery-01", "RECOVERY", str(f.a(evidence), "sha256"), ID, AS_OF);
        e.put("occurred_at", START.plusSeconds(30).toString()); e.put("scope", "RISK"); e.put("evidence", List.of(evidence)); e.put("response", "SYNTHETIC recovery assertion; not permission to resume"); e.put("stop_status", "RECORDED"); e.put("prior_event_id", "incident-01");
        e.put("resolution_evidence", resolution ? List.of(evidence) : List.of()); f.extraEvents.add(e); return e;
    }
    private static void incidentCases() throws Exception {
        accepted("open-incident-missing-stop", f -> incident(f), "FAIL", "INCIDENT_UNRESOLVED", "REQUIRED_STOP_ABSENT");
        accepted("open-incident-stopped", f -> { incident(f); stop(f); }, "BLOCKED", "INCIDENT_UNRESOLVED", "HUMAN_STOP_REQUIRED");
        accepted("invalid-closure-assertion", f -> { incident(f); stop(f); recovery(f, false); }, "FAIL", "INVALID_INCIDENT_CLOSURE");
        accepted("recovery-remains-blocked", f -> { incident(f); stop(f); recovery(f, true); }, "BLOCKED", "RECOVERY_REVIEW_UNAUTHENTICATED", "RECOVERY_REVIEW_REQUIRED");
        Fixture noStop = fixture("recovery-without-stop"); incident(noStop); recovery(noStop, true); noStop.write(); rejectedEvaluation(noStop, "recovery-without-stop");
        accepted("uncertain-recovery-stop", f -> { incident(f); stop(f); recovery(f, true).put("stop_status", "UNKNOWN"); }, "FAIL", "RECOVERY_STOP_UNCERTAIN");
        Map<String, Object> scoped = accepted("severity-wrong-policy-scope", f -> {
            incident(f).put("scope", "FEED");
            for (Object t : list(obj(f.manifest.get("rules")).get("triggers"))) if (obj(t).get("kind").equals("INCIDENT")) obj(t).put("scope", "RISK");
        }, "FAIL", "TRIGGER_UNDEFINED", "INCIDENT_UNRESOLVED");
        check(finding(scoped, "INCIDENT_UNRESOLVED").get("declared_policy_severity_ref") == null, "wrong-scope policy cannot supply incident severity");
        Map<String, Object> applicable = accepted("severity-applicable-declaration", f -> { incident(f); stop(f); }, "BLOCKED", "INCIDENT_UNRESOLVED");
        check(reference("severity", "SEVERE").equals(finding(applicable, "INCIDENT_UNRESOLVED").get("declared_policy_severity_ref")), "applicable declared severity retained as a private reference");
        check(finding(applicable, "INCIDENT_UNRESOLVED").get("severity") == null, "unauthenticated policy cannot establish reviewed severity");
        check(Boolean.FALSE.equals(finding(applicable, "INCIDENT_UNRESOLVED").get("severity_authenticated")), "declared policy has no authenticated identity");
    }
    private static void historyCases() throws Exception {
        corruptHistory("history-tamper", events -> obj(events.get(2)).put("response", "SYNTHETIC changed bytes"), false);
        corruptHistory("history-reordered", events -> Collections.swap(events, 2, 3), false);
        corruptHistory("history-duplicate-rechained", events -> events.add(3, cloneUnchecked(events.get(2))), true);
        corruptHistory("history-registration-replay", events -> events.add(cloneUnchecked(events.getFirst())), true);
        corruptHistory("history-future-event", events -> obj(events.getLast()).put("recorded_at", AS_OF.plusSeconds(1).toString()), true);
        corruptHistory("history-chronology", events -> obj(events.getLast()).put("occurred_at", AS_OF.plusSeconds(1).toString()), true);
        Fixture noncanonical = fixture("history-noncanonical"); noncanonical.write(); Files.writeString(noncanonical.root.resolve("history.json"), utf8(Files.readAllBytes(noncanonical.root.resolve("history.json"))) + "\n"); rejectedEvaluation(noncanonical, "history-noncanonical");
        Fixture corrected = fixture("valid-artifact-correction"); String oldId = corrected.id("roles/owner.txt");
        String newId = corrected.add("corrections/owner.txt", "PROVENANCE", "SYNTHETIC correction; original identity/qualification remains untrusted.\n"); corrected.correctionArtifacts.add(newId); byte[] old = corrected.write();
        Map<String, Object> prior = list(obj(parse(old)).get("events")).stream().map(G7Json::obj).filter(e -> ("evidence-" + oldId).equals(e.get("event_id"))).findFirst().orElseThrow();
        var e = event("correction-01", "CORRECTION", hash(bytes(corrected.a(newId))), null, AS_OF); e.put("prior_event_id", prior.get("event_id")); e.put("supersedes_sha256", hash(bytes(prior))); e.put("artifact_record", corrected.a(newId)); e.put("evidence", List.of(newId)); e.put("response", "SYNTHETIC explicit correction"); corrected.extraEvents.add(e); corrected.write();
        Map<String, Object> result = evaluate(corrected); observation(result, "APPENDED_CORRECTION"); check(Files.exists(corrected.root.resolve("roles/owner.txt")), "prior artifact retained");
        byte[] correctionHistory = Files.readAllBytes(corrected.root.resolve("history.json"));
        var duplicateCorrection = cloneMap(e); duplicateCorrection.put("event_id", "second-correction-of-original"); link(correctionHistory, duplicateCorrection);
        rejectedAppend(corrected, correctionHistory, duplicateCorrection, "double-correction-of-original");
        var wrongCorrection = cloneMap(e); wrongCorrection.put("event_id", "wrong-correction-binding"); wrongCorrection.put("supersedes_sha256", "5".repeat(64)); link(old, wrongCorrection);
        rejects(() -> new G7Ledger(extended(old, wrongCorrection), "SYNTHETIC_REVIEW_01", SCOPE, hash(bytes(corrected.manifest)), AS_OF), "wrong prior-event correction hash rejected");
        Fixture badCorrection = fixture("bad-correction-hash"); var c = event("bad-correction", "CORRECTION", "1".repeat(64), null, AS_OF); c.put("prior_event_id", "registration"); c.put("supersedes_sha256", "2".repeat(64)); c.put("evidence", List.of(badCorrection.id("roles/owner.txt"))); c.put("response", "SYNTHETIC illegal registration correction"); badCorrection.extraEvents.add(c); badCorrection.write(); rejectedEvaluation(badCorrection, "bad-correction-hash");
        Fixture incidentCorrection = fixture("incident-correction-retains-findings"); String correctedEvidence = incidentCorrection.add("corrections/incident.txt", "INCIDENTS", "SYNTHETIC changed incident description; not closure.\n"); incident(incidentCorrection); stop(incidentCorrection); byte[] first = incidentCorrection.write();
        var priorIncident = list(obj(parse(first)).get("events")).stream().map(G7Json::obj).filter(x -> x.get("event_id").equals("incident-01")).findFirst().orElseThrow();
        var ci = event("incident-correction", "CORRECTION", str(incidentCorrection.a(correctedEvidence), "sha256"), ID, AS_OF); ci.put("occurred_at", START.plusSeconds(40).toString()); ci.put("scope", "RISK"); ci.put("prior_event_id", "incident-01"); ci.put("supersedes_sha256", hash(bytes(priorIncident))); ci.put("evidence", List.of(correctedEvidence)); ci.put("response", "SYNTHETIC corrected record"); ci.put("severity", "SEVERE"); ci.put("required_rereview", List.of("G2", "G4")); ci.put("stop_status", "REQUIRED"); incidentCorrection.extraEvents.add(ci); incidentCorrection.write();
        var preserved = evaluate(incidentCorrection); finding(preserved, "INCIDENT_UNRESOLVED"); finding(preserved, "REQUIRED_STOP_ABSENT"); observation(preserved, "APPENDED_CORRECTION");
        Fixture periodEdited = fixture("in-place-period-edit"); periodEdited.write(); periodEdited.p().put("observed_start", START.plusSeconds(1).toString()); Files.write(periodEdited.root.resolve("bundle.json"), bytes(periodEdited.bundle)); finding(evaluate(periodEdited), "PERIOD_RECORD_HISTORY_MISMATCH");
        appendEvidenceAndPeriod();
    }
    private static void appendEvidenceAndPeriod() throws Exception {
        Fixture f = fixture("append-evidence-generation"); String id = f.add("later/evidence.txt", "PROVENANCE", "SYNTHETIC later evidence; no source authentication.\n");
        f.correctionArtifacts.add(id); byte[] anchor = f.write(); finding(evaluate(f), "ARTIFACT_NOT_REGISTERED");
        Path oldReport = root.resolve("before-evidence-report"); check(invoke("evaluate", f.root.toString(), oldReport.toString()).code == 3, "incomplete registration report published as blocked");
        var addition = event("later-evidence", "EVIDENCE", hash(bytes(f.a(id))), null, AS_OF); addition.put("artifact_record", f.a(id)); addition.put("evidence", List.of(id)); link(anchor, addition);
        byte[] added = append(f, anchor, addition, "later-evidence-generation", 3); var after = evaluate(f, added);
        check(!has(after, "ARTIFACT_NOT_REGISTERED"), "append registers exact later artifact"); finding(after, "BUNDLE_NOT_FINALIZED");
        var finalBundle = event("bundle-final-2", "BUNDLE", hash(bytes(f.bundle)), null, AS_OF); link(added, finalBundle);
        byte[] finalized = append(f, added, finalBundle, "later-finalized-generation", 3); check(!has(evaluate(f, finalized), "BUNDLE_NOT_FINALIZED"), "new final bundle binds exact inventory");
        Path latest = root.resolve("later-evidence-latest.json"); Files.write(latest, finalized);
        check(invoke("verify", f.root.toString(), oldReport.toString(), latest.toString()).code == 1, "later evidence requires a new review/report");
        Fixture period = fixture("append-period-correction"); byte[] original = period.write();
        var prior = list(obj(parse(original)).get("events")).stream().map(G7Json::obj).filter(x -> "PERIOD_EVIDENCE".equals(x.get("kind"))).findFirst().orElseThrow();
        period.p().put("observed_start", START.plusSeconds(1).toString()); Files.write(period.root.resolve("bundle.json"), bytes(period.bundle));
        var correction = event("period-correction", "CORRECTION", hash(bytes(period.p())), ID, AS_OF);
        correction.put("prior_event_id", prior.get("event_id")); correction.put("supersedes_sha256", hash(bytes(prior))); correction.put("period_record", period.p()); correction.put("evidence", period.p().get("evidence")); correction.put("response", "SYNTHETIC preserve original period and correct partial coverage"); link(original, correction);
        byte[] revised = append(period, original, correction, "period-corrected-generation", 3); var review = evaluate(period, revised);
        check(!has(review, "PERIOD_RECORD_HISTORY_MISMATCH"), "appended period correction matches active immutable record"); finding(review, "PARTIAL_PERIOD_COVERAGE"); observation(review, "APPENDED_CORRECTION");
        check(Arrays.equals(original, Files.readAllBytes(period.root.resolve("history.json"))), "period correction retains original history anchor");
    }
    private static Map<String, Object> cloneUnchecked(Object value) { try { return cloneMap(value); } catch (IOException e) { throw new IllegalStateException(e); } }
    private static void corruptHistory(String name, Consumer<List<Object>> edit, boolean rechain) throws Exception {
        Fixture f = fixture(name); var history = obj(parse(f.write())); List<Object> events = list(history.get("events")); edit.accept(events); Files.write(f.root.resolve("history.json"), rechain ? chain(events) : bytes(history)); rejectedEvaluation(f, name);
    }
    private static byte[] extended(byte[] source, Map<String, Object> event) throws Exception {
        List<Object> events = new ArrayList<>(list(obj(parse(source)).get("events"))); events.add(event); return chain(events);
    }
    private static Map<String, Object> decision(Fixture f, byte[] history, Map<String, Object> target, boolean reviewer, Map<String, Object> priorReview) throws Exception {
        var designated = obj(f.manifest.get(reviewer ? "reviewer" : "owner"));
        var d = map("schema", "g7-decision/v1", "decision_id", reviewer ? "review-01" : "owner-01", "role", reviewer ? "INDEPENDENT_REVIEWER" : "OWNER", "actor_id", designated.get("id"), "actor_kind", "HUMAN", "scope", SCOPE,
                "at", AS_OF.toString(), "expires", AS_OF.plusSeconds(60).toString(), "outcome", "APPROVE", "reservations", List.of(),
                "reviewed_hashes", cloneMap(target.get("input_hashes")), "reviewed_head", target.get("history_head"), "report_sha256", hash(bytes(target)),
                "reviewer_decision_sha256", priorReview == null ? null : hash(bytes(priorReview)), "qualification_evidence", reviewer ? designated.get("qualification_evidence") : null,
                "independence_evidence", reviewer ? designated.get("independence_evidence") : null, "authority_evidence", reviewer ? null : designated.get("authority_evidence"), "identity_evidence", designated.get("identity_evidence"), "signature", null);
        var e = event(reviewer ? "review-event" : "owner-event", reviewer ? "REVIEWER_DECISION" : "OWNER_DECISION", hash(bytes(d)), null, AS_OF); e.put("decision", d); link(history, e); return e;
    }
    private static void link(byte[] history, Map<String, Object> event) throws Exception {
        var events = list(obj(parse(history)).get("events")); event.put("sequence", events.size()); event.put("previous_sha256", hash(bytes(events.getLast())));
    }
    private static void decisionCases() throws Exception {
        Fixture f = fixture("hash-bound-decisions-and-revocation"); byte[] original = f.write(); var target = evaluate(f);
        Path publication = root.resolve("decision-original-report"); check(invoke("evaluate", f.root.toString(), publication.toString()).code == 3, "diagnostic publication remains blocked");
        var reviewer = decision(f, original, target, true, null); sign(obj(reviewer.get("decision"))); reviewer.put("subject_sha256", hash(bytes(reviewer.get("decision"))));
        byte[] reviewed = append(f, original, reviewer, "reviewer-generation", 3); check(Arrays.equals(original, Files.readAllBytes(f.root.resolve("history.json"))), "append preserves original history bytes");
        var afterReview = evaluate(f, reviewed); finding(afterReview, "DECISION_UNAUTHENTICATED"); check(!has(afterReview, "DECISION_REVIEWED_BYTES_MISMATCH"), "review binds exact report/input hashes");
        var owner = decision(f, reviewed, target, false, reviewer); byte[] owned = append(f, reviewed, owner, "owner-generation", 3);
        var afterOwner = evaluate(f, owned); check(!has(afterOwner, "HUMAN_DECISIONS_PENDING"), "both assertions recorded"); check("BLOCKED".equals(afterOwner.get("status")), "signed human assertions cannot unlock eligibility");
        Path latest = root.resolve("decisions-latest.json"); Files.write(latest, owned); check(invoke("verify", f.root.toString(), publication.toString(), latest.toString()).code == 0, "verify decision suffix establishes bytes only");
        var registration = obj(list(obj(parse(original)).get("events")).getFirst()); var revocation = event("revocation-01", "REVOCATION", hash(bytes(registration)), null, AS_OF); revocation.put("prior_event_id", registration.get("event_id")); link(owned, revocation);
        byte[] revoked = append(f, owned, revocation, "revocation-generation", 3); Files.write(latest, revoked);
        Invocation verification = invoke("verify", f.root.toString(), publication.toString(), latest.toString()); check(verification.code == 3, "terminal revocation remains visible to verify"); finding(verification.value, "REVIEW_REVOKED");
        var replayRevocation = cloneMap(revocation); replayRevocation.put("event_id", "revocation-02"); link(revoked, replayRevocation); rejectedAppend(f, revoked, replayRevocation, "revocation-replay");
        var afterRevocation = decision(f, revoked, target, false, reviewer); obj(afterRevocation.get("decision")).put("decision_id", "owner-after-revocation"); afterRevocation.put("event_id", "decision-after-revocation"); afterRevocation.put("subject_sha256", hash(bytes(afterRevocation.get("decision")))); rejectedAppend(f, revoked, afterRevocation, "decision-after-revocation");
        for (String variant : List.of("wrong-hash", "expired", "wrong-actor", "ai", "reservations", "excess-expiry")) {
            Fixture other = fixture("decision-" + variant); byte[] anchor = other.write(); var base = evaluate(other); var e = decision(other, anchor, base, true, null); var d = obj(e.get("decision"));
            switch (variant) {
                case "wrong-hash" -> d.put("report_sha256", "3".repeat(64)); case "expired" -> d.put("expires", AS_OF.toString());
                case "wrong-actor" -> d.put("actor_id", "other-human"); case "ai" -> d.put("actor_kind", "AI");
                case "reservations" -> d.put("reservations", List.of("SYNTHETIC unresolved reservation")); case "excess-expiry" -> d.put("expires", AS_OF.plusSeconds(172800).toString());
            }
            e.put("subject_sha256", hash(bytes(d))); var report = evaluate(other, extended(anchor, e));
            finding(report, switch (variant) { case "wrong-hash" -> "DECISION_REVIEWED_BYTES_MISMATCH"; case "expired" -> "DECISION_EXPIRED"; case "wrong-actor", "ai" -> "DECISION_IDENTITY_MISMATCH"; case "reservations" -> "DECISION_RESERVATION_OR_REJECTION"; default -> "DECISION_EXPIRY_EXCEEDS_POLICY"; });
            if (Set.of("wrong-hash", "wrong-actor", "ai").contains(variant)) rejectedAppend(other, anchor, e, "rejected-decision-" + variant);
        }
        Fixture order = fixture("owner-before-reviewer"); byte[] anchor = order.write(); var base = evaluate(order); var ownerFirst = decision(order, anchor, base, false, null); finding(evaluate(order, extended(anchor, ownerFirst)), "OWNER_REVIEW_BINDING_MISMATCH");
        Fixture sealed = fixture("evidence-after-decision"); byte[] prior = sealed.write(); var baseSealed = evaluate(sealed); var r = decision(sealed, prior, baseSealed, true, null); byte[] seal = extended(prior, r);
        var addition = event("extra-evidence", "BUNDLE", hash(bytes(sealed.bundle)), null, AS_OF); link(seal, addition); rejectedAppend(sealed, seal, addition, "sealed-evidence-addition");
        var duplicate = cloneMap(r); duplicate.put("event_id", "duplicate-decision"); link(seal, duplicate); rejectedAppend(sealed, seal, duplicate, "decision-identity-replay");
    }
    private static byte[] append(Fixture f, byte[] source, Map<String, Object> event, String name, int expected) throws Exception {
        Path history = root.resolve(name + "-source.json"), draft = root.resolve(name + "-draft.json"), destination = root.resolve(name); Files.write(history, source); Files.write(draft, bytes(event));
        Invocation result = invoke("append", f.root.toString(), history.toString(), draft.toString(), destination.toString()); check(result.code == expected, "append diagnostic exit " + name); check(Arrays.equals(source, Files.readAllBytes(history)), "append never rewrites source generation");
        check(Files.exists(destination.resolve("history.json")), "valid append publishes complete new generation"); return Files.readAllBytes(destination.resolve("history.json"));
    }
    private static void rejectedAppend(Fixture f, byte[] source, Map<String, Object> event, String name) throws Exception {
        Path history = root.resolve(name + "-source.json"), draft = root.resolve(name + "-draft.json"), destination = root.resolve(name); Files.write(history, source); Files.write(draft, bytes(event));
        check(invoke("append", f.root.toString(), history.toString(), draft.toString(), destination.toString()).code == 1, "invalid append rejected: " + name); check(!Files.exists(destination), "invalid append not published"); check(Arrays.equals(source, Files.readAllBytes(history)), "rejected append retains prior generation");
    }

    private static void fileCases() throws Exception {
        for (String path : List.of("../outside", "x/../outside", "/absolute", "C:/private", "\\\\host\\share", "x\\file", "x//file", "CON.txt", "x/aux", "x/COM1.dat", "x/.", "x/..", "x/trailing.", "x/file:stream", "file\n")) rejects(() -> G7Files.relative(path), "portable path rejected: " + path.replace('\n', '_'));
        rejects(() -> G7Files.local(Path.of("\\\\synthetic-invalid-host\\share\\input")), "UNC rejected before network access");
        rejects(() -> G7Files.local(Path.of("x/../escape")), "native traversal rejected");
        for (String spelling : List.of("{\"x\":1,\"x\":2}", "{\"x\":1e129}", "{\"x\":\"\\ud800\"}", "{\"x\":NaN}")) rejects(() -> parse(spelling.getBytes(StandardCharsets.UTF_8)), "malformed JSON rejected");
        rejects(() -> parse(new byte[]{(byte) 0xc0, (byte) 0xaf}), "malformed UTF-8 rejected");
        Fixture malformed = fixture("malformed-artifact-json"); malformed.replaceBytes("gates/G0-decision.json", new byte[]{(byte)0xff}); malformed.write(); rejectedEvaluation(malformed, "malformed-artifact-json");
        accepted("artifact-size-mismatch", f -> { var a = f.a(f.id("roles/owner.txt")); a.put("size_bytes", integer(a.get("size_bytes")) + 1); }, "FAIL", "ARTIFACT_BYTE_IDENTITY_MISMATCH");
        Fixture unsupported = fixture("unsupported-schema"); unsupported.manifest.put("schema", "g7-manifest/v999"); unsupported.write(); rejectedEvaluation(unsupported, "unsupported-schema");
        Fixture extra = fixture("unknown-schema-field"); extra.manifest.put("hidden_override", true); extra.write(); rejectedEvaluation(extra, "unknown-schema-field");
        Fixture fractional = fixture("fractional-cadence"); obj(fractional.manifest.get("rules")).put("cadence_seconds", new java.math.BigDecimal("1.5")); fractional.write(); rejectedEvaluation(fractional, "fractional-cadence");
        Fixture absentNull = fixture("omitted-unknown-field"); obj(absentNull.manifest.get("owner")).remove("identity_evidence"); absentNull.write(); rejectedEvaluation(absentNull, "omitted-unknown-field");
        var definitions = obj(G7Schema.document("manifest").get("$defs")); var props = obj(obj(definitions.get("manifest")).get("properties")); String idPattern = str(obj(props.get("review_id")), "pattern");
        check(!Pattern.compile(idPattern).matcher("safe\n").find(), "exported regex rejects trailing newline under search semantics");
        check(Pattern.compile(idPattern).matcher("safe").find(), "exported regex accepts safe ID");
        Fixture collision = fixture("output-collision"); collision.write(); Path target = root.resolve("existing-output"); Files.createDirectory(target); Files.writeString(target.resolve("sentinel.txt"), "SYNTHETIC retain me");
        check(invoke("evaluate", collision.root.toString(), target.toString()).code == 1, "output collision rejected"); check("SYNTHETIC retain me".equals(Files.readString(target.resolve("sentinel.txt"))), "collision cannot overwrite prior bytes");
        check(invoke("evaluate", collision.root.toString(), collision.root.resolve("nested-output").toString()).code == 1, "nested output rejected");
        Path failed = root.resolve("atomic-failure"); rejects(() -> G7Files.publish(failed, collision.root, Map.of("report.json", bytes(map("synthetic", true))), () -> { throw new IOException("SYNTHETIC injected before atomic publication"); }), "atomic-publication failure rejected");
        check(!Files.exists(failed), "failed publication leaves no partial output");
        try (var files = Files.list(root)) { check(files.noneMatch(p -> p.getFileName().toString().startsWith(".g7-stage-")), "failed publication removes owned staging only"); }
        Path appeared = root.resolve("destination-appeared"); rejects(() -> G7Files.publish(appeared, collision.root, Map.of("report.json", new byte[]{1}), () -> { Files.createDirectory(appeared); Files.writeString(appeared.resolve("sentinel"), "SYNTHETIC race winner"); }), "concurrent destination creation rejected"); check(Files.exists(appeared.resolve("sentinel")), "destination race winner retained");
        Path concurrent = root.resolve("concurrent-publication"); var start = new CountDownLatch(1); var success = new java.util.concurrent.atomic.AtomicInteger(); var failure = new java.util.concurrent.atomic.AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) futures.add(pool.submit(() -> { try { start.await(); G7Files.publish(concurrent, collision.root, Map.of("report.json", new byte[]{1}, "artifact.json", new byte[]{2})); success.incrementAndGet(); } catch (IOException e) { failure.incrementAndGet(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); } }));
            start.countDown(); for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        }
        check(success.get() == 1 && failure.get() == 1, "cooperating publications have one winner"); check(Files.exists(concurrent.resolve("report.json")) && Files.exists(concurrent.resolve("artifact.json")), "publication winner has complete bytes");
        Fixture mutation = fixture("input-mutation"); mutation.write();
        try (G7Files snapshot = G7Files.load(mutation.root)) {
            boolean prevented = false; try { Files.writeString(mutation.root.resolve("roles/owner.txt"), "SYNTHETIC concurrent mutation"); } catch (IOException busy) { prevented = true; }
            if (!prevented) rejects(snapshot::unchanged, "input content mutation detected"); else check(true, "shared read lock prevents mutation on this host");
        }
        Fixture added = fixture("input-added-during-review"); added.write(); try (G7Files snapshot = G7Files.load(added.root)) { Files.writeString(added.root.resolve("unregistered.txt"), "SYNTHETIC concurrent addition"); rejects(snapshot::unchanged, "directory/input mutation detected"); }
        Fixture frozen = fixture("frozen-input-mutation"); frozen.write(); try (G7Files snapshot = G7Files.load(frozen.root)) { Path file = snapshot.frozen("roles/owner.txt"); Files.writeString(file, "SYNTHETIC changed frozen adapter input"); rejects(snapshot::unchanged, "frozen adapter mutation detected"); }
        Path external = root.resolve("external-mutation.json"); Files.writeString(external, "SYNTHETIC external input"); var captured = new G7Files.External(external); Files.writeString(external, "SYNTHETIC external replacement"); rejects(captured::unchanged, "external history/draft mutation detected");
        Files.write(external, captured.bytes); Files.setLastModifiedTime(external, FileTime.from(Instant.parse("2000-01-01T00:00:00Z"))); rejects(captured::unchanged, "restored bytes with changed metadata rejected");
        Fixture hardLink = fixture("hardlink-alias"); hardLink.write(); Files.createLink(hardLink.root.resolve("alias.txt"), hardLink.root.resolve("roles/owner.txt")); rejectedEvaluation(hardLink, "hardlink-alias");
        Fixture linked = fixture("linked-input-escape"); linked.write(); Path outside = root.resolve("synthetic-link-target"); Files.createDirectory(outside); Files.writeString(outside.resolve("private.txt"), "SYNTHETIC outside dossier");
        Path link = linked.root.resolve("escape");
        try { Files.createSymbolicLink(link, outside); check(true, "native symlink fixture created"); }
        catch (IOException | UnsupportedOperationException unavailable) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw unavailable;
            child(List.of("C:\\Program Files\\PowerShell\\7\\pwsh.exe", "-NoProfile", "-NonInteractive", "-Command", "New-Item -ItemType Junction -Path '" + link.toString().replace("'", "''") + "' -Target '" + outside.toString().replace("'", "''") + "' | Out-Null"), 0);
            limitations.add("Native symlink creation unavailable; Windows junction escape and ancestor rejection tested.");
        }
        rejectedEvaluation(linked, "linked-input-escape"); rejects(() -> G7Files.local(link.resolve("private.txt")), "linked ancestor rejected");
        check(invoke("evaluate", collision.root.toString(), link.resolve("new-output").toString()).code == 1, "linked output ancestor rejected");
        if (System.getProperty("os.name").startsWith("Windows")) {
            Fixture junction = fixture("junction-input-escape"); junction.write(); Path junctionPath = junction.root.resolve("escape");
            child(List.of("C:\\Program Files\\PowerShell\\7\\pwsh.exe", "-NoProfile", "-NonInteractive", "-Command", "New-Item -ItemType Junction -Path '" + junctionPath.toString().replace("'", "''") + "' -Target '" + outside.toString().replace("'", "''") + "' | Out-Null"), 0);
            rejectedEvaluation(junction, "junction-input-escape"); rejects(() -> G7Files.local(junctionPath.resolve("private.txt")), "junction ancestor rejected");
        }
    }

    private static void cliCases() throws Exception {
        check(invoke("explain").code == 2, "explain lists requirements without eligibility");
        for (String[] args : List.of(new String[]{}, new String[]{"approve"}, new String[]{"evaluate"}, new String[]{"verify", "x"}, new String[]{"append", "x", "y", "z"}, new String[]{"explain", "x", "y"})) check(invoke(args).code == 1, "strict command/argument validation");
        Fixture f = fixture("packaged-cli-repeatability"); byte[] history = f.write(); Path first = root.resolve("cli-report-first");
        check(invoke("evaluate", f.root.toString(), first.toString()).code == 3, "evaluate publishes blocked diagnostic");
        var verified = invoke("verify", f.root.toString(), first.toString()); check(verified.code == 0 && "BLOCKED".equals(verified.value.get("status")), "byte verification is independent of readiness");
        check(invoke("explain", f.root.toString()).code == 3, "explain dossier includes blocking findings");
        byte[] expected = Files.readAllBytes(first.resolve("report.json"));
        for (String[] settings : List.of(new String[]{"en", "US", "UTC"}, new String[]{"tr", "TR", "Pacific/Auckland"}, new String[]{"hi", "IN", "Asia/Kolkata"})) {
            Path result = root.resolve("jvm-report-" + settings[0]); List<String> args = javaArgs("-Duser.language=" + settings[0], "-Duser.country=" + settings[1], "-Duser.timezone=" + settings[2], "-cp", jar.toString(), G7Cli.class.getName(), "evaluate", f.root.toString(), result.toString()); child(args, 3);
            check(Arrays.equals(expected, Files.readAllBytes(result.resolve("report.json"))), "report byte identity across separate JVM/locale/timezone " + settings[0]);
            check(Arrays.equals(Files.readAllBytes(first.resolve("artifact.json")), Files.readAllBytes(result.resolve("artifact.json"))), "publication metadata byte identity " + settings[0]);
            child(javaArgs("-cp", jar.toString(), G7Cli.class.getName(), "verify", f.root.toString(), result.toString()), 0);
        }
        Path selected = root.resolve("selected-original-history.json"); Files.write(selected, history); check(invoke("verify", f.root.toString(), first.toString(), selected.toString()).code == 0, "explicit canonical history verified");
        var truncated = new ArrayList<>(list(obj(parse(history)).get("events"))); truncated.removeLast(); Files.write(selected, chain(truncated));
        check(invoke("verify", f.root.toString(), first.toString(), selected.toString()).code == 1, "history truncation rejected"); Files.write(selected, history);
        Files.writeString(first.resolve("report.json"), utf8(expected) + " "); check(invoke("verify", f.root.toString(), first.toString()).code == 1, "report tampering rejected"); Files.write(first.resolve("report.json"), expected);
        Files.writeString(first.resolve("extra.txt"), "SYNTHETIC unexpected file"); check(invoke("verify", f.root.toString(), first.toString()).code == 1, "unlisted report bytes rejected"); Files.delete(first.resolve("extra.txt"));
        var metadata = obj(parse(Files.readAllBytes(first.resolve("artifact.json")))); byte[] metadataOriginal = bytes(metadata); metadata.put("code_sha256", "4".repeat(64)); Files.write(first.resolve("artifact.json"), bytes(metadata)); check(invoke("verify", f.root.toString(), first.toString()).code == 1, "code identity tampering rejected"); Files.write(first.resolve("artifact.json"), metadataOriginal);
        Path shadow = root.resolve("shadow-classes"); Path target = shadow.resolve("com/mscient/intrapaper/ops/governance/G7Schema.class"); Files.createDirectories(target.getParent());
        try (var source = G7Schema.class.getResourceAsStream("G7Schema.class")) { Files.write(target, source.readAllBytes()); }
        child(javaArgs("-cp", shadow + File.pathSeparator + jar, G7Cli.class.getName(), "evaluate", f.root.toString(), root.resolve("mixed-origin-report").toString()), 1); check(!Files.exists(root.resolve("mixed-origin-report")), "mixed class origins cannot publish a trusted code identity");
        child(List.of("C:\\Program Files\\PowerShell\\7\\pwsh.exe", "-NoProfile", "-NonInteractive", "-File", project.resolve("g7-operations.ps1").toString(), "-Command", "explain", "-JarPath", jar.toString()), 2);
        Path wrapper = root.resolve("wrapper-report"); child(List.of("C:\\Program Files\\PowerShell\\7\\pwsh.exe", "-NoProfile", "-NonInteractive", "-File", project.resolve("g7-operations.ps1").toString(), "-Command", "evaluate", "-JarPath", jar.toString(), "-Dossier", f.root.toString(), "-Output", wrapper.toString()), 3);
        check(Arrays.equals(expected, Files.readAllBytes(wrapper.resolve("report.json"))), "packaged wrapper produces identical diagnostic");
        child(List.of("C:\\Program Files\\PowerShell\\7\\pwsh.exe", "-NoProfile", "-NonInteractive", "-File", project.resolve("g7-operations.ps1").toString(), "-Command", "evaluate", "-JarPath", jar.toString(), "-Dossier", f.root.toString(), "-Output", wrapper.toString()), 1);
        Fixture privacy = fixture("private-values-redacted"); privacy.a(privacy.id("observations/CASH.txt")).put("provenance", "PRIVATE_ACCOUNT_123 PRIVATE_LIMIT_987 PRIVATE_CREDENTIAL_456"); privacy.write(); String report = utf8(bytes(evaluate(privacy)));
        check(!report.contains("PRIVATE_ACCOUNT_123") && !report.contains("PRIVATE_LIMIT_987") && !report.contains("PRIVATE_CREDENTIAL_456") && !report.contains(privacy.root.toString()), "public diagnostics omit caller/private path/value text");
    }
    private static List<String> javaArgs(String... args) {
        List<String> result = new ArrayList<>(); result.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString()); result.addAll(List.of(args)); return result;
    }
    private static void publishedContracts() throws Exception {
        for (String name : G7Schema.names()) check(Arrays.equals(G7Schema.bytes(name), Files.readAllBytes(project.resolve("schemas/g7/v1/" + name + ".schema.json"))), "published schema equals executable version: " + name);
        for (String name : List.of("clean-looking", "missing-period", "build-drift")) {
            scenarios++; Path dossier = project.resolve("examples/g7-operations/dossiers/" + name), output = root.resolve("published-example-" + name);
            check(invoke("evaluate", dossier.toString(), output.toString()).code == 3, "published synthetic example remains blocked: " + name);
            check(invoke("verify", dossier.toString(), output.toString()).code == 0, "published example report is byte-verifiable only: " + name);
        }
    }
    private static void child(List<String> args, int expected) throws Exception {
        Path log = root.resolve("synthetic-child-" + (++processes) + ".log"); Process process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("synthetic child process timeout"); }
        check(process.exitValue() == expected, "synthetic child exit=" + process.exitValue() + " expected=" + expected + ": " + Files.readString(log));
    }
}
