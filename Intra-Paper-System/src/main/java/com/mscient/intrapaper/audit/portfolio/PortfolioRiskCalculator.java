package com.mscient.intrapaper.audit.portfolio;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.simulation.portfolio.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Independent cash-flow/marked-value arithmetic; does not invoke the replay accounting ledger. */
final class PortfolioRiskCalculator {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    record Report(Map<String, String> files, String status, int coverageIssues, int breaches) { }
    record Mark(BigDecimal price, Instant observedAt) { }
    static String markCoverage(Mark mark, Instant at, long maxAgeSeconds) {
        if (mark == null || mark.price() == null || mark.observedAt() == null) return "MISSING_MARK";
        if (mark.price().signum() <= 0 || mark.observedAt().isAfter(at)) return "INVALID_MARK";
        return Duration.between(mark.observedAt(), at).compareTo(Duration.ofSeconds(maxAgeSeconds)) > 0
                ? "STALE_MARK" : "COMPLETE";
    }
    static boolean exceeds(BigDecimal value, BigDecimal cap) { return value.compareTo(cap) > 0; }

    static Report calculate(List<SynchronizedBarFrameBuilder.Frame> frames, PortfolioReplayEngine.Result replay,
            RiskInputs risk) {
        if (frames.isEmpty() || replay.snapshots().size() != frames.size() * 2)
            throw new IllegalArgumentException("Replay snapshot/frame count mismatch");
        Set<String> universe = new TreeSet<>(frames.getFirst().barsByInstrument().keySet());
        risk.requireUniverse(universe);
        TreeMap<String, Long> units = new TreeMap<>();
        TreeMap<String, BigDecimal> cash = new TreeMap<>(), fees = new TreeMap<>();
        universe.forEach(id -> { units.put(id, 0L); cash.put(id, ZERO); fees.put(id, ZERO); });
        StringBuilder exposures = header("frame_index,session_date,timestamp_utc,phase,instrument_id,signed_units,mark_price,signed_exposure_inr,gross_notional_inr,caller_supplied_margin_proxy_inr,max_abs_units,max_gross_notional_inr,limit_source_id,margin_proxy_source_id,mark_coverage,limit_coverage,margin_proxy_coverage");
        StringBuilder totals = header("frame_index,session_date,timestamp_utc,phase,signed_exposure_inr,gross_notional_inr,caller_supplied_margin_proxy_inr,net_pnl_inr,session_start_net_pnl_inr,session_pnl_inr,max_gross_notional_inr,max_session_loss_inr,max_caller_supplied_margin_proxy_inr,budget_source_id,budget_coverage");
        StringBuilder coverage = header("frame_index,session_date,timestamp_utc,phase,instrument_id,issue");
        StringBuilder breaches = header("frame_index,session_date,timestamp_utc,phase,instrument_id,limit,actual,cap");
        StringBuilder reconciliation = header("frame_index,timestamp_utc,phase,positions_match,instrument_net_pnl_match,total_fees_match,total_net_pnl_match,recomputed_net_pnl_inr,replay_net_pnl_inr");
        int executionIndex = 0, issueCount = 0, breachCount = 0;
        long previousSequence = 0;
        Set<String> executionIds = new HashSet<>();
        BigDecimal previousNet = ZERO, sessionBase = ZERO;
        LocalDate session = null;
        for (int i = 0; i < frames.size(); i++) {
            var frame = frames.get(i);
            if (!universe.equals(frame.barsByInstrument().keySet())) throw new IllegalArgumentException("Missing frame marks");
            Duration interval = IntervalSupport.duration(frame.barsByInstrument().values().iterator().next().interval());
            if (!frame.sessionDate().equals(session)) { sessionBase = previousNet; session = frame.sessionDate(); }
            if (i > 0 && frames.get(i - 1).sessionDate().equals(session)
                    && !frames.get(i - 1).timestamp().plus(interval).equals(frame.timestamp())) {
                row(coverage, i, session, frame.timestamp(), "BAR_OPEN", "PORTFOLIO", "MISSING_FRAME_MARKS"); issueCount++;
            }
            while (executionIndex < replay.executions().size()) {
                var execution = replay.executions().get(executionIndex);
                if (execution.timestamp().isAfter(frame.timestamp())) break;
                if (!execution.timestamp().equals(frame.timestamp()) || !units.containsKey(execution.instrumentId())
                        || execution.sequence() <= previousSequence || !executionIds.add(execution.executionId()))
                    throw new IllegalArgumentException("Execution/frame ordering or identity mismatch");
                previousSequence = execution.sequence();
                long signed = execution.side() == PortfolioExecution.Side.BUY ? execution.quantityUnits() : -execution.quantityUnits();
                String id = execution.instrumentId();
                long position = Math.addExact(units.get(id), signed);
                if (position == Long.MIN_VALUE) throw new IllegalArgumentException("Position overflow");
                units.put(id, position);
                cash.put(id, cash.get(id).subtract(execution.price().multiply(BigDecimal.valueOf(signed))));
                fees.put(id, fees.get(id).add(execution.feeInr()));
                executionIndex++;
            }
            for (int phaseIndex = 0; phaseIndex < 2; phaseIndex++) {
                String phase = phaseIndex == 0 ? "BAR_OPEN" : "BAR_CLOSE";
                Instant at = phaseIndex == 0 ? frame.timestamp() : frame.timestamp().plus(interval);
                var snapshot = replay.snapshots().get(i * 2 + phaseIndex);
                if (!snapshot.timestamp().equals(at) || !snapshot.phase().name().equals(phase)
                        || !snapshot.positions().keySet().equals(universe))
                    throw new IllegalArgumentException("Replay snapshot identity mismatch");
                var budget = risk.at("budgets.csv", "PORTFOLIO", session);
                String budgetCoverage = risk.coverage("budgets.csv", "PORTFOLIO", session, budget);
                if (!budgetCoverage.equals("COMPLETE")) {
                    row(coverage, i, session, at, phase, "PORTFOLIO", budgetCoverage); issueCount++;
                }
                boolean marginComplete = true, marksComplete = true;
                BigDecimal signedTotal = ZERO, grossTotal = ZERO, marginTotal = ZERO, net = ZERO, totalFees = ZERO;
                for (String id : universe) {
                    Bar bar = frame.barsByInstrument().get(id);
                    Mark mark = new Mark(phaseIndex == 0 ? bar.open() : bar.close(), at);
                    String markStatus = budget == null ? "MISSING_MARK_FRESHNESS_BUDGET"
                            : markCoverage(mark, at, RiskInputs.integer(budget.get("max_mark_age_seconds")));
                    String limitStatus = risk.coverage("limits.csv", id, session, budget);
                    String marginStatus = risk.coverage("margin-proxies.csv", id, session, budget);
                    for (String status : List.of(markStatus, limitStatus, marginStatus)) {
                        if (!status.equals("COMPLETE")) { row(coverage, i, session, at, phase, id, status); issueCount++; }
                    }
                    var limit = risk.at("limits.csv", id, session);
                    var margin = risk.at("margin-proxies.csv", id, session);
                    long quantity = units.get(id);
                    BigDecimal signedValue = mark.price().multiply(BigDecimal.valueOf(quantity));
                    BigDecimal gross = signedValue.abs();
                    BigDecimal instrumentNet = cash.get(id).add(signedValue).subtract(fees.get(id));
                    var position = snapshot.positions().get(id);
                    if (position.signedUnits() != quantity || position.feesInr().compareTo(fees.get(id)) != 0
                            || instrumentNet.compareTo(position.realizedGrossPnlInr().add(position.unrealizedPnlInr()).subtract(position.feesInr())) != 0)
                        throw new IllegalArgumentException("Independent position/P&L reconciliation failed for " + id + " at " + at);
                    net = net.add(instrumentNet); totalFees = totalFees.add(fees.get(id));
                    boolean markOk = markStatus.equals("COMPLETE");
                    marksComplete &= markOk;
                    signedTotal = signedTotal.add(signedValue); grossTotal = grossTotal.add(gross);
                    BigDecimal proxy = null;
                    if (marginStatus.equals("COMPLETE")) {
                        proxy = RiskInputs.decimal(margin.get(quantity < 0 ? "short_proxy_inr_per_unit" : "long_proxy_inr_per_unit"))
                                .multiply(BigDecimal.valueOf(Math.abs(quantity)));
                        marginTotal = marginTotal.add(proxy);
                    } else marginComplete = false;
                    if (limitStatus.equals("COMPLETE")) {
                        breachCount += breach(breaches, i, session, at, phase, id, "ABS_UNITS", BigDecimal.valueOf(Math.abs(quantity)), limit.get("max_abs_units"));
                        if (markOk) breachCount += breach(breaches, i, session, at, phase, id, "GROSS_NOTIONAL_INR", gross, limit.get("max_gross_notional_inr"));
                    }
                    row(exposures, i, session, at, phase, id, quantity, markOk ? mark.price() : null,
                            markOk ? signedValue : null, markOk ? gross : null, proxy,
                            cell(limit, "max_abs_units"), cell(limit, "max_gross_notional_inr"), cell(limit, "source_id"),
                            cell(margin, "source_id"), markStatus, limitStatus, marginStatus);
                }
                if (net.compareTo(snapshot.netPnlInr()) != 0 || totalFees.compareTo(snapshot.feesInr()) != 0
                        || net.compareTo(snapshot.realizedGrossPnlInr().add(snapshot.unrealizedPnlInr()).subtract(snapshot.feesInr())) != 0)
                    throw new IllegalArgumentException("Independent total P&L/fee reconciliation failed at " + at);
                row(reconciliation, i, at, phase, true, true, true, true, net, snapshot.netPnlInr());
                BigDecimal sessionPnl = net.subtract(sessionBase);
                if (budgetCoverage.equals("COMPLETE")) {
                    if (marksComplete) {
                        breachCount += breach(breaches, i, session, at, phase, "PORTFOLIO", "GROSS_NOTIONAL_INR", grossTotal, budget.get("max_gross_notional_inr"));
                        breachCount += breach(breaches, i, session, at, phase, "PORTFOLIO", "SESSION_LOSS_INR", sessionPnl.negate().max(ZERO), budget.get("max_session_loss_inr"));
                    }
                    if (marginComplete) breachCount += breach(breaches, i, session, at, phase, "PORTFOLIO", "CALLER_SUPPLIED_MARGIN_PROXY_INR", marginTotal, budget.get("max_caller_supplied_margin_proxy_inr"));
                }
                row(totals, i, session, at, phase, marksComplete ? signedTotal : null, marksComplete ? grossTotal : null,
                        marginComplete ? marginTotal : null, marksComplete ? net : null, sessionBase,
                        marksComplete ? sessionPnl : null, cell(budget, "max_gross_notional_inr"), cell(budget, "max_session_loss_inr"),
                        cell(budget, "max_caller_supplied_margin_proxy_inr"), cell(budget, "source_id"), budgetCoverage);
                previousNet = net;
            }
        }
        if (executionIndex != replay.executions().size()) throw new IllegalArgumentException("Unmatched replay executions");
        String status = issueCount > 0 ? "INCOMPLETE" : breachCount > 0 ? "BREACH" : "PASS";
        return new Report(Map.of("exposures.csv", exposures.toString(), "checkpoints.csv", totals.toString(),
                "coverage.csv", coverage.toString(), "breaches.csv", breaches.toString(),
                "reconciliation.csv", reconciliation.toString()), status, issueCount, breachCount);
    }

    private static int breach(StringBuilder out, int frame, LocalDate session, Instant at, String phase,
            String instrument, String name, BigDecimal actual, String cap) {
        if (!exceeds(actual, RiskInputs.decimal(cap))) return 0;
        row(out, frame, session, at, phase, instrument, name, actual, RiskInputs.decimal(cap));
        return 1;
    }
    private static String cell(Map<String, String> values, String key) { return values == null ? "" : values.get(key); }
    static StringBuilder header(String value) { return new StringBuilder(value).append('\n'); }
    static void row(StringBuilder out, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(',');
            Object value = cells[i];
            String text = value == null ? "" : value instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : value.toString();
            out.append(CsvTable.escape(text));
        }
        out.append('\n');
    }
}
