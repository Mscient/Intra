package com.mscient.intrapaper.ops.drill;

import com.mscient.intrapaper.data.CsvTable;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.mscient.intrapaper.ops.drill.IncidentDrillOrchestrator.Status.*;

/** Adversarial offline acceptance checks; no broker, credentials or network. */
public final class IncidentDrillTest {
    private static int checks;
    private static String metadata;
    private static Path output;
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]); output = Path.of(args[1]); Files.createDirectories(output);
        metadata = Files.readString(fixtures.resolve("feed-loss.drill")).split(DrillScenario.HEADER, -1)[0];
        Map<String, IncidentDrillOrchestrator.Status> expected = new TreeMap<>(Map.of(
                "feed-loss", PASS, "stale-marks", PASS, "uncertain-order", PASS, "limit-breach", PASS,
                "corrupted-checkpoint", PASS, "restart", PASS, "missed-ack-incomplete", INCOMPLETE,
                "lagging-checkpoint-incomplete", INCOMPLETE, "expectation-fail", FAIL));
        for (var entry : expected.entrySet()) {
            var scenario = DrillScenario.load(fixtures.resolve(entry.getKey() + ".drill"));
            var result = IncidentDrillOrchestrator.run(scenario);
            check(result.status() == entry.getValue(), entry.getKey() + " status: " + report(result));
            check(report(result).contains("g4_operating_session=false\nproves_live_recovery=false"), "scope claims");
            var a = DrillEvidence.bundle(result); var b = DrillEvidence.bundle(IncidentDrillOrchestrator.run(scenario));
            check(a.keySet().equals(b.keySet()), "identical file set");
            for (String name : a.keySet()) check(Arrays.equals(a.get(name), b.get(name)), "byte identical " + name);
            Path published = DrillEvidence.publish(result, output.resolve(entry.getKey()));
            String hash = published.getFileName().toString();
            check(DrillEvidence.verify(published, hash) == result.status(), "regeneration verification");
            rejects(() -> DrillEvidence.publish(result, output.resolve(entry.getKey())), "overwrite refused");
            rejects(() -> DrillEvidence.verify(published, "0".repeat(64)), "wrong anchor");
            byte[] original = Files.readAllBytes(published.resolve("report.properties"));
            Files.writeString(published.resolve("report.properties"), "status=PASS\n");
            rejects(() -> DrillEvidence.verify(published, hash), "tampered status refused");
            byte[] manifest = Files.readAllBytes(published.resolve("manifest.properties"));
            String rehashed = new String(manifest, StandardCharsets.UTF_8).replace(DrillEvidence.sha(original),
                    DrillEvidence.sha("status=PASS\n".getBytes(StandardCharsets.UTF_8)));
            Files.writeString(published.resolve("manifest.properties"), rehashed);
            rejects(() -> DrillEvidence.verify(published, DrillEvidence.sha(rehashed.getBytes(StandardCharsets.UTF_8))), "rehashed false report cannot pass regeneration");
            Files.write(published.resolve("manifest.properties"), manifest);
            Files.write(published.resolve("report.properties"), original);
            Files.writeString(published.resolve("extra.txt"), "unbound");
            rejects(() -> DrillEvidence.verify(published, hash), "extra artifact refused");
            Files.delete(published.resolve("extra.txt"));
        }
        var uncertain = IncidentDrillOrchestrator.run(DrillScenario.load(fixtures.resolve("uncertain-order.drill")));
        check(report(uncertain).contains("final_position=2\nremaining_orders=-"), "duplicate fills and retries do not add exposure");
        var rows = csv(uncertain, "events.csv");
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            if (row.get("result").equals("DUPLICATE") || row.get("action").equals("RETRY")) {
                check(i > 0 && row.get("risk_checkpoint_sha256").equals(rows.get(i - 1).get("risk_checkpoint_sha256")), "duplicate/retry state immutable");
            }
        }
        check(csv(uncertain, "risk-decisions.csv").size() == 1, "one reservation decision despite retries across restart");
        check(rows.stream().filter(r -> r.get("event_id").equals("PartialReconcile")).findFirst().orElseThrow()
                .get("missing_acknowledgements").equals("OrderA"), "reconciliation cannot invent missing acknowledgement");
        strictFormatChecks(fixtures);
        gates();
        System.out.println("Incident drill acceptance checks passed: " + checks);
    }
    private static void strictFormatChecks(Path fixtures) throws Exception {
        String valid = Files.readString(fixtures.resolve("feed-loss.drill"));
        for (String bad : List.of(valid.replace("DRILL_V1", "DRILL_V2"), valid.replace("scenario_id=", "extra=x\nscenario_id="),
                valid.replace("max_abs_contracts=10", "max_abs_contracts=0"), valid.replace("margin_per_contract=10", "margin_per_contract=NaN"),
                valid.replace("maximum_age_seconds=5", "maximum_age_seconds=9999999999"),
                valid.replace("event_id,time_utc", "id,time_utc"), valid.replace("FEED_LOSS", "BROKER_LOGIN"),
                valid.replace("\n", "\r\n"), valid.substring(0, valid.length() - 1), valid + "\n",
                valid.replace("2,OrderEvent", "3,OrderEvent"), valid.replace("OrderA,BUY,2", "OrderA,BUY,0"),
                valid.replace("09:00:02Z,FRAME", "08:00:02Z,FRAME"), valid.replace("0,-,,,RECONCILED", "0,B:1;A:2,,,RECONCILED"),
                valid.replace("scenario_id=feed-loss", "scenario_id=../escape"), valid.replace("09:00:00Z", "09:00:00+00:00")))
            rejects(() -> DrillScenario.parse(bad.getBytes(StandardCharsets.UTF_8)), "strict schema rejection");
        byte[] invalidUtf8 = valid.getBytes(StandardCharsets.UTF_8); invalidUtf8[0] = (byte) 0xff;
        rejects(() -> DrillScenario.parse(invalidUtf8), "malformed UTF-8");
        rejects(() -> DrillScenario.parse(new byte[DrillScenario.MAX_BYTES + 1]), "bounded scenario");
        rejects(() -> DrillScenario.reservations("A:1;A:2"), "duplicate reservation");
        rejects(() -> DrillScenario.reservations("A:0"), "zero residual");
        rejects(() -> DrillScenario.parse(new Scenario().frame(0, "RUNNING").add("Lost", 0, "FEED_LOSS", "SAFE_HALT", "FEED_LOSS_BLOCKED")
                .text().getBytes(StandardCharsets.UTF_8)), "frame input collision rejected");
    }
    private static void gates() throws Exception {
        // No incident or reset evidence cannot earn PASS, even with all event assertions matching.
        expect(new Scenario().frame(0, "RUNNING"), INCOMPLETE);
        // Signed positions must be matched exactly; a failed reconciliation cannot discard exposure.
        Scenario conflict = new Scenario().frame(0, "RUNNING")
                .add("Order", 0, "PROPOSE", "RUNNING", "ACCEPTED", "order_id", "A", "side", "SELL", "quantity", "3")
                .add("Fill", 0, "FILL_ACK", "RUNNING", "FILL_APPLIED", "order_id", "A", "quantity", "3", "price", "100")
                .add("Fill", 0, "FILL_ACK", "SAFE_HALT", "EVENT_ID_CONFLICT", "order_id", "A", "quantity", "2", "price", "100")
                .reconcile("Wrong", 0, "3", "-", "SAFE_HALT", "RECONCILIATION_REJECTED")
                .reset("Unsafe", 0, "Wrong", "SAFE_HALT", "RESET_DENIED", "DrillOperator")
                .reconcile("Exact", 0, "-3", "-", "RECONCILED", "RECONCILED")
                .reset("Reset", 0, "Exact", "RUNNING", "RESET_AUTHORIZED", "DrillOperator");
        check(report(expect(conflict, PASS)).contains("final_position=-3"), "conflicting delivery preserves signed position");
        // A second incident invalidates a completed reset and cannot reuse its reference.
        Scenario second = baseRecovery().add("LostAgain", 2, "FEED_LOSS", "SAFE_HALT", "FEED_LOSS_BLOCKED")
                .reset("OldReset", 2, "Exact", "SAFE_HALT", "RESET_DENIED", "DrillOperator");
        expect(second, INCOMPLETE);
        // Health ages using supplied event time; no wall-clock timeout or stale clearance bypass.
        expect(baseHalt().reconcile("Exact", 2, "0", "-", "RECONCILED", "RECONCILED")
                .reset("Expired", 8, "Exact", "RECONCILED", "RESET_DENIED", "DrillOperator"), INCOMPLETE);
        // New market data invalidates the previously bound reconciliation, even if values agree.
        expect(baseHalt().reconcile("Exact", 2, "0", "-", "RECONCILED", "RECONCILED").frame(3, "SAFE_HALT")
                .reset("StaleReference", 3, "Exact", "SAFE_HALT", "RESET_DENIED", "DrillOperator"), INCOMPLETE);
        // Unknown acknowledgements fail closed; missing checkpoints cannot be replaced by an empty ledger.
        expect(new Scenario().frame(0, "RUNNING").add("BadAck", 0, "CANCEL_ACK", "SAFE_HALT", "ACK_REJECTED", "order_id", "Unknown")
                .add("Restart", 0, "RESTART", "SAFE_HALT", "CHECKPOINT_REJECTED", "reference", "Absent")
                .add("NoReanchor", 0, "CHECKPOINT", "SAFE_HALT", "ACTION_REJECTED", "reference", "New"), INCOMPLETE);
        // Wrong actor and reference never authorize reset; proposals remain blocked after reconciliation.
        expect(baseHalt().reconcile("Exact", 2, "0", "-", "RECONCILED", "RECONCILED")
                .add("Blocked", 2, "PROPOSE", "RECONCILED", "BLOCKED_SAFE_HALT", "order_id", "A", "side", "BUY", "quantity", "1")
                .reset("WrongActor", 2, "Exact", "RECONCILED", "RESET_DENIED", "Stranger")
                .reset("WrongRef", 2, "Other", "RECONCILED", "RESET_DENIED", "DrillOperator")
                .reset("Reset", 2, "Exact", "RUNNING", "RESET_AUTHORIZED", "DrillOperator"), PASS);
        // A deliberately under-limit 'breach' must FAIL even when a caller expects the generic rejection.
        expect(new Scenario().frame(0, "RUNNING").add("NotBreach", 0, "LIMIT_BREACH", "SAFE_HALT", "ACTION_REJECTED",
                "order_id", "A", "side", "BUY", "quantity", "1"), FAIL);
        // A daily-loss latch belongs to the underlying gateway; drill reset cannot clear it.
        Scenario loss = new Scenario().frame(0, "RUNNING")
                .add("Order", 0, "PROPOSE", "RUNNING", "ACCEPTED", "order_id", "A", "side", "BUY", "quantity", "2")
                .add("Fill", 0, "FILL_ACK", "RUNNING", "FILL_APPLIED", "order_id", "A", "quantity", "2", "price", "100")
                .add("Loss", 1, "FRAME", "SAFE_HALT", "HALTED", "price", "1")
                .reconcile("Exact", 1, "2", "-", "RECONCILED", "RECONCILED")
                .reset("Reset", 1, "Exact", "RECONCILED", "RESET_DENIED", "DrillOperator");
        String lossText = loss.text().replace("max_daily_loss=1000", "max_daily_loss=100");
        check(IncidentDrillOrchestrator.run(DrillScenario.parse(lossText.getBytes(StandardCharsets.UTF_8))).status() == INCOMPLETE, "daily loss cannot reset");
        Scenario fillLoss = new Scenario().frame(0, "RUNNING")
                .add("Order", 0, "PROPOSE", "RUNNING", "ACCEPTED", "order_id", "A", "side", "SELL", "quantity", "2")
                .add("Fill", 0, "FILL_ACK", "SAFE_HALT", "FILL_APPLIED", "order_id", "A", "quantity", "2", "price", "10")
                .reconcile("Exact", 0, "-2", "-", "RECONCILED", "RECONCILED")
                .reset("Reset", 0, "Exact", "RECONCILED", "RESET_DENIED", "DrillOperator");
        var fillResult = IncidentDrillOrchestrator.run(DrillScenario.parse(fillLoss.text().replace("max_daily_loss=1000", "max_daily_loss=100").getBytes(StandardCharsets.UTF_8)));
        check(fillResult.status() == INCOMPLETE && report(fillResult).contains("failed_check_count=0"), "fill-induced loss halts immediately");
        for (String setting : List.of("max_gross_notional", "max_margin_proxy")) {
            String text = new Scenario().frame(0, "RUNNING")
                    .add("Limit", 0, "LIMIT_BREACH", "SAFE_HALT", "LIMIT_BLOCKED", "order_id", "A", "side", "BUY", "quantity", "2")
                    .reconcile("Exact", 0, "0", "-", "RECONCILED", "RECONCILED")
                    .reset("Reset", 0, "Exact", "RUNNING", "RESET_AUTHORIZED", "DrillOperator").text()
                    .replace(setting + "=10000", setting + "=" + (setting.equals("max_gross_notional") ? "150" : "15"));
            var limitResult = IncidentDrillOrchestrator.run(DrillScenario.parse(text.getBytes(StandardCharsets.UTF_8)));
            check(limitResult.status() == PASS, setting + " exercised through actual gateway");
            check(new String(limitResult.files().get("events.csv"), StandardCharsets.UTF_8).contains(
                    setting.equals("max_gross_notional") ? "GROSS_NOTIONAL_LIMIT" : "MARGIN_LIMIT"), "exact limit reason retained");
        }
        // Compounded incidents retain the original uncertain reservation through feed and mark failures.
        expect(new Scenario().frame(0, "RUNNING")
                .add("Order", 0, "PROPOSE", "RUNNING", "ACCEPTED", "order_id", "A", "side", "BUY", "quantity", "2")
                .add("Uncertain", 0, "UNCERTAIN_ORDER", "SAFE_HALT", "HALTED", "order_id", "A")
                .add("Lost", 1, "FEED_LOSS", "SAFE_HALT", "FEED_LOSS_BLOCKED")
                .add("Stale", 2, "STALE_MARK", "SAFE_HALT", "STALE_MARK_BLOCKED", "price", "100")
                .frame(3, "SAFE_HALT")
                .add("Cancel", 3, "CANCEL_ACK", "SAFE_HALT", "CANCEL_APPLIED", "order_id", "A")
                .reconcile("Exact", 3, "0", "-", "RECONCILED", "RECONCILED")
                .reset("Reset", 3, "Exact", "RUNNING", "RESET_AUTHORIZED", "DrillOperator"), PASS);
    }
    private static Scenario baseHalt() {
        return new Scenario().frame(0, "RUNNING").add("Lost", 1, "FEED_LOSS", "SAFE_HALT", "FEED_LOSS_BLOCKED")
                .frame(2, "SAFE_HALT");
    }
    private static Scenario baseRecovery() {
        return new Scenario().frame(0, "RUNNING").add("Limit", 0, "LIMIT_BREACH", "SAFE_HALT", "LIMIT_BLOCKED",
                "order_id", "TooLarge", "side", "BUY", "quantity", "11")
                .reconcile("Exact", 0, "0", "-", "RECONCILED", "RECONCILED")
                .reset("Reset", 0, "Exact", "RUNNING", "RESET_AUTHORIZED", "DrillOperator");
    }
    private static IncidentDrillOrchestrator.Result expect(Scenario scenario, IncidentDrillOrchestrator.Status status) throws Exception {
        var result = IncidentDrillOrchestrator.run(DrillScenario.parse(scenario.text().getBytes(StandardCharsets.UTF_8)));
        check(result.status() == status, "gate outcome " + status + ": " + report(result) + new String(result.files().get("events.csv"), StandardCharsets.UTF_8));
        return result;
    }
    private static final class Scenario {
        final List<String> rows = new ArrayList<>();
        Scenario add(String id, int seconds, String action, String state, String result, String... pairs) {
            Map<String, String> fields = new HashMap<>(); for (int i = 0; i < pairs.length; i += 2) fields.put(pairs[i], pairs[i + 1]);
            List<String> values = new ArrayList<>(List.of(Integer.toString(rows.size() + 1), id,
                    "2026-11-07T09:00:" + String.format(Locale.ROOT, "%02d", seconds) + "Z", action));
            for (String key : List.of("order_id", "side", "quantity", "price", "position", "reservations", "actor", "reference")) values.add(fields.getOrDefault(key, ""));
            values.add(state); values.add(result); rows.add(String.join(",", values)); return this;
        }
        Scenario frame(int t, String state) { return add("Frame" + t, t, "FRAME", state, "FRAME_APPLIED", "price", "100"); }
        Scenario reconcile(String id, int t, String pos, String remaining, String state, String result) {
            return add(id, t, "RECONCILE", state, result, "position", pos, "reservations", remaining);
        }
        Scenario reset(String id, int t, String ref, String state, String result, String actor) {
            return add(id, t, "RESET", state, result, "reference", ref, "actor", actor);
        }
        String text() { return metadata + DrillScenario.HEADER + "\n" + String.join("\n", rows) + "\n"; }
    }
    private static List<Map<String, String>> csv(IncidentDrillOrchestrator.Result result, String name) throws Exception {
        Path file = output.resolve("inspect-" + name); Files.write(file, result.files().get(name)); return CsvTable.read(file).rows();
    }
    private static String report(IncidentDrillOrchestrator.Result result) { return new String(result.files().get("report.properties"), StandardCharsets.UTF_8); }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    private interface Throwing { void run() throws Exception; }
    private static void rejects(Throwing call, String message) throws Exception {
        try { call.run(); } catch (IllegalArgumentException | java.io.IOException expected) { checks++; return; }
        throw new AssertionError("Expected rejection: " + message);
    }
}
