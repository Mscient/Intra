package com.mscient.intrapaper.ops.drill;

import com.mscient.intrapaper.simulation.portfolio.PortfolioExecution;
import com.mscient.intrapaper.simulation.portfolio.PortfolioReplayEngine.OrderIntent;
import com.mscient.intrapaper.simulation.portfolio.risk.OfflinePortfolioRiskAdapter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static com.mscient.intrapaper.ops.drill.DrillScenario.*;

/** Offline orchestration only: all effects are on the owned replay risk adapter and byte arrays. */
public final class IncidentDrillOrchestrator {
    public enum Status { PASS, FAIL, INCOMPLETE }
    public record Result(Status status, Map<String, byte[]> files) {
        public Result { files = copy(files); }
        @Override public Map<String, byte[]> files() { return copy(files); }
        private static Map<String, byte[]> copy(Map<String, byte[]> values) {
            Map<String, byte[]> result = new TreeMap<>(); values.forEach((k, v) -> result.put(k, v.clone()));
            return Collections.unmodifiableMap(result);
        }
    }
    private record Saved(byte[] trusted, byte[] candidate, String hash) { }
    private final DrillScenario scenario;
    private OfflinePortfolioRiskAdapter risk;
    private State state = State.RUNNING;
    private Instant frameTime;
    private boolean healthy, checkpointBlocked;
    private int incidents, resets, mismatches;
    private String reconciliation = "", reconciliationHash = "", detail = "";
    private final Map<String, Event> seen = new LinkedHashMap<>();
    private final Map<String, OrderIntent> intents = new TreeMap<>();
    private final Map<String, Saved> saved = new TreeMap<>();
    private final Set<String> missedAcks = new TreeSet<>();
    private final Set<String> coverage = new TreeSet<>();
    private final StringBuilder trace = new StringBuilder("sequence,event_id,time_utc,action,before_state,after_state,result,expected_state,expected_result,check,detail,position,reservations,missing_acknowledgements,uncertainty,checkpoint_blocked,risk_checkpoint_sha256,previous_sha256,event_sha256\n");
    private String chain = "0".repeat(64);

    private IncidentDrillOrchestrator(DrillScenario scenario) {
        this.scenario = scenario; risk = new OfflinePortfolioRiskAdapter(scenario.inputs());
    }
    public static Result run(DrillScenario scenario) { return new IncidentDrillOrchestrator(scenario).execute(); }
    private Result execute() {
        for (Event e : scenario.events()) {
            State before = state; detail = "";
            Outcome result;
            Event previous = seen.get(e.id());
            if (previous != null) {
                if (previous.payload().equals(e.payload())) result = Outcome.DUPLICATE;
                else { halt("EVENT_ID_CONFLICT"); result = Outcome.EVENT_ID_CONFLICT; }
            } else {
                seen.put(e.id(), e);
                try { result = apply(e); }
                catch (RuntimeException unexpected) {
                    // Unexpected component exceptions can never be made PASS by an expectation.
                    halt("COMPONENT_ERROR"); detail = "COMPONENT_ERROR:" + unexpected.getClass().getSimpleName();
                    result = Outcome.ACTION_REJECTED; mismatches++;
                }
            }
            boolean match = state == e.expectedState() && result == e.expectedResult();
            if (!match) mismatches++;
            String row = DrillEvidence.csv(Integer.toString(e.sequence()), e.id(), e.at().toString(), e.action().name(),
                    before.name(), state.name(), result.name(), e.expectedState().name(), e.expectedResult().name(),
                    match ? "PASS" : "FAIL", detail, Long.toString(position()), reservationsText(),
                    String.join(";", missedAcks), risk.uncertainty(), Boolean.toString(checkpointBlocked), riskHash(), chain);
            chain = DrillEvidence.sha(row.getBytes(StandardCharsets.UTF_8));
            trace.append(row).append(',').append(chain).append('\n');
        }
        boolean complete = incidents > 0 && resets > 0 && state == State.RUNNING && !checkpointBlocked
                && missedAcks.isEmpty() && risk.reservations().isEmpty() && risk.uncertainty().isEmpty()
                && isHealthy(scenario.events().getLast().at()) && !risk.state().dailyLossHalted();
        Status status = mismatches > 0 ? Status.FAIL : complete ? Status.PASS : Status.INCOMPLETE;
        Map<String, byte[]> files = new TreeMap<>();
        files.put("scenario.drill", scenario.bytes());
        put(files, "events.csv", trace.toString());
        put(files, "risk-decisions.csv", risk.decisionsCsv());
        put(files, "risk-events.csv", risk.traceCsv());
        files.put("risk-checkpoint.txt", risk.checkpoint());
        saved.forEach((id, cp) -> {
            files.put("checkpoints/" + id + "-trusted.txt", cp.trusted());
            files.put("checkpoints/" + id + "-candidate.txt", cp.candidate());
        });
        String report = "format=OFFLINE_INCIDENT_EVIDENCE_V1\nscenario_id=" + scenario.id()
                + "\nstatus=" + status + "\nscope=SYNTHETIC_OFFLINE_DRILL_ONLY\ng4_operating_session=false"
                + "\nproves_live_recovery=false\nauthorization=SYNTHETIC_ALLOWLIST_NOT_AUTHENTICATION"
                + "\nrestart=IN_PROCESS_CHECKPOINT_REPLAY_NOT_PROCESS_OR_POWER_FAILURE"
                + "\nreplay=OFFLINE_PORTFOLIO_RISK_ADAPTER_NOT_FULL_STRATEGY_ACCOUNTING_RESUME"
                + "\nmargin=CALLER_SUPPLIED_SYNTHETIC_PROXY_NOT_EXCHANGE_MARGIN"
                + "\nfinal_state=" + state + "\nincident_count=" + incidents + "\nauthorized_reset_count=" + resets
                + "\nfailed_check_count=" + mismatches + "\ncoverage=" + String.join(",", coverage)
                + "\nfinal_position=" + position() + "\nremaining_orders=" + reservationsText()
                + "\nmissing_acknowledgements=" + String.join(";", missedAcks)
                + "\ncheckpoint_blocked=" + checkpointBlocked + "\nevent_chain_sha256=" + chain
                + "\nscenario_sha256=" + DrillEvidence.sha(scenario.bytes())
                + "\nrisk_inputs_sha256=" + scenario.inputs().fingerprint() + "\n";
        put(files, "report.properties", report);
        return new Result(status, files);
    }
    private Outcome apply(Event e) {
        return switch (e.action()) {
            case FRAME -> frame(e, false);
            case STALE_MARK -> frame(e, true);
            case FEED_LOSS -> {
                if (!advanceFrame(e)) yield rejectAction();
                healthy = false;
                var probe = risk.evaluate(intent(e, "Probe_" + e.id(), PortfolioExecution.Side.BUY, 1));
                if (probe.accepted() || !(probe.reason().equals("MISSING_FRAME_INPUTS") || probe.reason().startsWith("EXECUTION_UNCERTAIN:"))) {
                    mismatches++; halt("FEED_LOSS_NOT_BLOCKED"); yield Outcome.ACTION_REJECTED;
                }
                detail = probe.reason(); coverage.add("FEED_LOSS"); halt("FEED_LOSS"); yield Outcome.FEED_LOSS_BLOCKED;
            }
            case PROPOSE, LIMIT_BREACH -> propose(e);
            case ACK_MISSED, UNCERTAIN_ORDER -> {
                if (!risk.reservations().containsKey(e.get("order_id"))) { halt("UNKNOWN_ORDER"); yield Outcome.ACK_REJECTED; }
                missedAcks.add(e.get("order_id"));
                risk.uncertain("Uncertain_" + e.id(), e.action().name());
                coverage.add(e.action().name()); halt(e.action().name()); yield Outcome.HALTED;
            }
            case RETRY -> {
                OrderIntent original = intents.get(e.get("order_id"));
                if (original == null) yield Outcome.RETRY_UNKNOWN;
                String before = riskHash(); var decision = risk.evaluate(original);
                if (!before.equals(riskHash())) throw new IllegalStateException("Retry changed risk state");
                detail = "HISTORICAL_DECISION_ONLY_NO_SECOND_EXECUTION";
                yield decision.accepted() ? Outcome.RETRY_ACCEPTED : Outcome.RETRY_REJECTED;
            }
            case FILL_ACK, CANCEL_ACK -> acknowledge(e);
            case CHECKPOINT -> {
                if (checkpointBlocked || saved.size() >= 16 || saved.containsKey(e.get("reference"))) yield rejectAction();
                byte[] bytes = risk.checkpoint();
                saved.put(e.get("reference"), new Saved(bytes, bytes.clone(), DrillEvidence.sha(bytes)));
                yield Outcome.CHECKPOINT_SAVED;
            }
            case CORRUPT_CHECKPOINT -> {
                Saved cp = saved.get(e.get("reference"));
                if (cp == null) yield rejectAction();
                byte[] corrupt = cp.candidate().clone(); corrupt[0] ^= 1;
                saved.put(e.get("reference"), new Saved(cp.trusted(), corrupt, cp.hash()));
                checkpointBlocked = true; coverage.add("CORRUPTED_CHECKPOINT"); halt("CORRUPTED_CHECKPOINT");
                yield Outcome.CHECKPOINT_CORRUPTED;
            }
            case RESTART, RECOVER_TRUSTED -> restart(e);
            case RECONCILE -> reconcile(e);
            case RESET -> reset(e);
        };
    }
    private Outcome frame(Event e, boolean stale) {
        if (!advanceFrame(e)) return rejectAction();
        healthy = !stale;
        if (state == State.RECONCILED) invalidateReconciliation();
        if (!stale) {
            if (risk.state().dailyLossHalted()) { halt("DAILY_LOSS_LIMIT"); coverage.add("LIMIT_BREACH"); return Outcome.HALTED; }
            return Outcome.FRAME_APPLIED;
        }
        var probe = risk.evaluate(intent(e, "Probe_" + e.id(), PortfolioExecution.Side.BUY, 1));
        if (probe.accepted() || !(probe.reason().equals("STALE_MARK:" + INSTRUMENT) || probe.reason().startsWith("EXECUTION_UNCERTAIN:"))) {
            mismatches++; halt("STALE_MARK_NOT_BLOCKED"); return Outcome.ACTION_REJECTED;
        }
        detail = probe.reason(); coverage.add("STALE_MARK"); halt("STALE_MARK"); return Outcome.STALE_MARK_BLOCKED;
    }
    private boolean advanceFrame(Event e) {
        if (frameTime != null && !e.at().isAfter(frameTime)) return false;
        risk.beginFrame(e.at(), scenario.session(), risk.positions(), scenario.inputs().lotSizes());
        frameTime = e.at(); return true;
    }
    private Outcome propose(Event e) {
        if (state != State.RUNNING || checkpointBlocked) return Outcome.BLOCKED_SAFE_HALT;
        if (!isHealthy(e.at()) || !e.at().equals(frameTime)) { halt("UNHEALTHY_FRAME"); return Outcome.ACTION_REJECTED; }
        if (intents.containsKey(e.get("order_id"))) { halt("ORDER_ID_REUSE"); return Outcome.EVENT_ID_CONFLICT; }
        OrderIntent order = intent(e, e.get("order_id"), PortfolioExecution.Side.valueOf(e.get("side")), e.number("quantity"));
        var decision = risk.evaluate(order); intents.put(order.intentId(), order); detail = decision.reason();
        if (e.action() == Action.LIMIT_BREACH) {
            if (decision.accepted() || !Set.of("POSITION_LIMIT", "GROSS_NOTIONAL_LIMIT", "MARGIN_LIMIT", "SESSION_HALTED").contains(decision.reason())) {
                mismatches++; halt("EXPECTED_LIMIT_NOT_OBSERVED"); return Outcome.ACTION_REJECTED;
            }
            coverage.add("LIMIT_BREACH"); halt("LIMIT_BREACH"); return Outcome.LIMIT_BLOCKED;
        }
        if (!decision.accepted()) { halt("RISK_REJECTED"); return Outcome.RISK_REJECTED; }
        return Outcome.ACCEPTED;
    }
    private OrderIntent intent(Event e, String id, PortfolioExecution.Side side, long units) {
        long target = side == PortfolioExecution.Side.BUY ? Math.addExact(position(), units) : Math.subtractExact(position(), units);
        return new OrderIntent(e.sequence(), id, e.at(), frameTime, INSTRUMENT, side, position(), target, units, "SYNTHETIC_DRILL");
    }
    private Outcome acknowledge(Event e) {
        String order = e.get("order_id"); Long residual = risk.reservations().get(order);
        if (checkpointBlocked || residual == null || !e.at().equals(frameTime)
                || (e.action() == Action.FILL_ACK && (e.number("quantity") > residual
                || e.price().compareTo(risk.state().orders().get(order).limitPrice()) > 0))) {
            halt("ACK_REJECTED"); return Outcome.ACK_REJECTED;
        }
        if (e.action() == Action.FILL_ACK) risk.execution("Ack_" + e.id(), order, frameTime, e.number("quantity"), e.price());
        else risk.cancel("Ack_" + e.id(), order, frameTime);
        if (!risk.reservations().containsKey(order)) missedAcks.remove(order);
        if (state == State.RECONCILED) invalidateReconciliation();
        if (risk.state().dailyLossHalted()) { coverage.add("LIMIT_BREACH"); halt("DAILY_LOSS_LIMIT"); }
        return e.action() == Action.FILL_ACK ? Outcome.FILL_APPLIED : Outcome.CANCEL_APPLIED;
    }
    private Outcome restart(Event e) {
        Saved cp = saved.get(e.get("reference"));
        halt("RESTART"); coverage.add("RESTART");
        if (cp == null || !cp.hash().equals(riskHash())) {
            checkpointBlocked = true; detail = cp == null ? "MISSING_TRUSTED_CHECKPOINT" : "LAGGING_CHECKPOINT";
            return Outcome.CHECKPOINT_REJECTED;
        }
        byte[] candidate = e.action() == Action.RECOVER_TRUSTED ? cp.trusted() : cp.candidate();
        try {
            var recovered = OfflinePortfolioRiskAdapter.restore(scenario.inputs(), candidate, cp.hash());
            if (!risk.positions().equals(recovered.positions()) || !risk.reservations().equals(recovered.reservations())
                    || !risk.uncertainty().equals(recovered.uncertainty())) throw new IllegalStateException("Restored state mismatch");
            risk = recovered; checkpointBlocked = false;
            detail = risk.uncertainty().isEmpty() ? "EXACT_ACKNOWLEDGED_HEAD_REPLAYED" : "RESTORED_WITH_UNCERTAINTY";
            return Outcome.RESTARTED_HALTED;
        } catch (IllegalArgumentException rejected) {
            checkpointBlocked = true; detail = "CHECKPOINT_INTEGRITY_REJECTED"; return Outcome.CHECKPOINT_REJECTED;
        }
    }
    private Outcome reconcile(Event e) {
        if (state != State.SAFE_HALT || checkpointBlocked || !isHealthy(e.at())) {
            detail = "HALT_HEALTH_AND_TRUSTED_STATE_REQUIRED"; return Outcome.RECONCILIATION_REJECTED;
        }
        try { risk.reconcile("Reconcile_" + e.id(), Map.of(INSTRUMENT, e.number("position")), reservations(e.get("reservations"))); }
        catch (IllegalStateException mismatch) { detail = "POSITION_OR_RESERVATION_MISMATCH"; return Outcome.RECONCILIATION_REJECTED; }
        state = State.RECONCILED; reconciliation = e.id(); reconciliationHash = riskHash();
        return Outcome.RECONCILED;
    }
    private Outcome reset(Event e) {
        if (state != State.RECONCILED || !e.get("actor").equals(scenario.operator())
                || !e.get("reference").equals(reconciliation) || !riskHash().equals(reconciliationHash)
                || !isHealthy(e.at()) || checkpointBlocked || !missedAcks.isEmpty()
                || !risk.reservations().isEmpty() || !risk.uncertainty().isEmpty() || risk.state().dailyLossHalted()) {
            detail = "RESET_GATE_DENIED"; return Outcome.RESET_DENIED;
        }
        state = State.RUNNING; reconciliation = ""; reconciliationHash = ""; resets++;
        return Outcome.RESET_AUTHORIZED;
    }
    private Outcome rejectAction() { halt("ACTION_REJECTED"); return Outcome.ACTION_REJECTED; }
    private void halt(String reason) {
        incidents++; state = State.SAFE_HALT; reconciliation = ""; reconciliationHash = "";
        if (detail.isEmpty()) detail = reason;
    }
    private void invalidateReconciliation() { state = State.SAFE_HALT; reconciliation = ""; reconciliationHash = ""; }
    private boolean isHealthy(Instant at) {
        return healthy && frameTime != null && !at.isBefore(frameTime)
                && Duration.between(frameTime, at).compareTo(scenario.inputs().configuration().maximumInputAge()) <= 0;
    }
    private long position() { return risk.positions().get(INSTRUMENT); }
    private String riskHash() { return DrillEvidence.sha(risk.checkpoint()); }
    private String reservationsText() {
        return risk.reservations().isEmpty() ? "-" : risk.reservations().entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue()).collect(java.util.stream.Collectors.joining(";"));
    }
    private static void put(Map<String, byte[]> files, String name, String text) { files.put(name, text.getBytes(StandardCharsets.UTF_8)); }
}
