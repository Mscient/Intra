package com.mscient.intrapaper.audit.portfolio;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.reference.ContractKind;
import com.mscient.intrapaper.reference.TimestampRole;
import com.mscient.intrapaper.provenance.SettlementCostSchedule;
import com.mscient.intrapaper.simulation.portfolio.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import static com.mscient.intrapaper.audit.portfolio.SettlementInputs.checked;

/** Signed-unit reconciliation against the final completed-bar mark; no executions are created. */
final class PortfolioSettlementCalculator {
    record Report(Map<String, String> files, LocalDate finalDate, BigDecimal replayNet,
                  BigDecimal adjustments, BigDecimal costs, BigDecimal finalNet, int settledCount) { }
    private static final BigDecimal ZERO = BigDecimal.ZERO, BPS = new BigDecimal("10000");
    private PortfolioSettlementCalculator() { }

    static Report calculate(List<SynchronizedBarFrameBuilder.Frame> frames, PortfolioReplayEngine.Result replay, SettlementInputs inputs) {
        if (frames.isEmpty() || replay.snapshots().size() != Math.multiplyExact(frames.size(), 2))
            throw new IllegalArgumentException("Replay snapshot/frame count mismatch");
        var finalFrame = frames.getLast();
        var snapshot = replay.snapshots().getLast();
        Set<String> universe = new TreeSet<>(finalFrame.barsByInstrument().keySet());
        if (!snapshot.positions().keySet().equals(universe) || snapshot.phase() != PortfolioAccountingLedger.MarkPhase.BAR_CLOSE)
            throw new IllegalArgumentException("Final replay positions or mark phase mismatch");
        Map<String, Long> units = new TreeMap<>();
        Map<String, BigDecimal> cash = new TreeMap<>(), fees = new TreeMap<>();
        universe.forEach(id -> { units.put(id, 0L); cash.put(id, ZERO); fees.put(id, ZERO); });
        Set<String> executionIds = new HashSet<>();
        Map<java.time.Instant, SynchronizedBarFrameBuilder.Frame> byTime = new HashMap<>();
        for (var frame : frames) {
            if (!frame.barsByInstrument().keySet().equals(universe) || byTime.putIfAbsent(frame.timestamp(), frame) != null)
                throw new IllegalArgumentException("Frame instrument/time mismatch");
        }
        long sequence = 0;
        java.time.Instant priorTime = null;
        for (var execution : replay.executions()) {
            var frame = byTime.get(execution.timestamp());
            if (frame == null || !units.containsKey(execution.instrumentId()) || execution.sequence() <= sequence
                    || !executionIds.add(execution.executionId()) || (priorTime != null && execution.timestamp().isBefore(priorTime)))
                throw new IllegalArgumentException("Replay execution identity/order mismatch");
            sequence = execution.sequence(); priorTime = execution.timestamp();
            var bar = frame.barsByInstrument().get(execution.instrumentId());
            if (execution.quantityUnits() <= 0 || execution.quantityUnits() % bar.lotSize() != 0
                    || execution.price().signum() <= 0 || execution.price().remainder(bar.tickSize()).signum() != 0
                    || execution.feeInr().signum() < 0)
                throw new IllegalArgumentException("Replay execution lot/price/fee mismatch");
            long signed = execution.side() == PortfolioExecution.Side.BUY ? execution.quantityUnits() : Math.negateExact(execution.quantityUnits());
            String id = execution.instrumentId();
            long newUnits = Math.addExact(units.get(id), signed);
            if (newUnits == Long.MIN_VALUE) throw new IllegalArgumentException("Position magnitude overflow");
            units.put(id, newUnits);
            cash.put(id, checked(cash.get(id).subtract(checked(execution.price().multiply(BigDecimal.valueOf(signed))))));
            fees.put(id, checked(fees.get(id).add(checked(execution.feeInr()))));
        }
        StringBuilder settlements = header("instrument_id,exchange,segment,trading_symbol,contract_kind,underlying,expiry,strike,option_right,final_replay_session_date,final_mark_timestamp_utc,final_replay_mark_per_unit_inr,status,settlement_date,settlement_method,settlement_value_per_unit_inr,source_id,source_reference,replay_signed_units,settled_signed_units,post_settlement_signed_units,replay_execution_cash_flow_inr,replay_signed_mark_value_inr,replay_realized_gross_pnl_inr,replay_unrealized_pnl_inr,replay_execution_fees_inr,replay_net_pnl_inr,settlement_minus_mark_per_unit_inr,signed_settlement_value_inr,settlement_adjustment_inr,settlement_cost_inr,post_settlement_realized_gross_pnl_inr,post_settlement_unrealized_pnl_inr,final_net_pnl_inr");
        StringBuilder costLines = header("instrument_id,expiry,settlement_date,contract_kind,absolute_settled_units,settlement_value_per_unit_inr,component_code,effective_from,effective_through,basis,calculation_base,rate,charge_product_inr,unrounded_charge_inr,charge_inr,gst_on_charge,gst_bps,gst_taxable_base_inr,gst_product_inr,unrounded_gst_inr,gst_inr,total_inr,source_reference");
        BigDecimal replayTotal = ZERO, totalFees = ZERO, realizedTotal = ZERO, unrealizedTotal = ZERO;
        BigDecimal adjustments = ZERO, totalCosts = ZERO, finalTotal = ZERO;
        Set<String> used = new TreeSet<>();
        for (String id : universe) {
            Bar bar = finalFrame.barsByInstrument().get(id);
            if (bar == null || !bar.instrumentId().equals(id) || bar.timestampRole() != TimestampRole.BAR_START
                    || !snapshot.timestamp().equals(bar.timestamp().plus(IntervalSupport.duration(bar.interval())))
                    || !bar.sessionDate().equals(finalFrame.sessionDate()))
                throw new IllegalArgumentException("Missing/mismatched final completed-bar mark: " + id);
            BigDecimal mark = checked(bar.close());
            if (mark.signum() <= 0) throw new IllegalArgumentException("Invalid final mark");
            var position = snapshot.positions().get(id);
            long quantity = units.get(id);
            BigDecimal markedValue = checked(mark.multiply(BigDecimal.valueOf(quantity)));
            BigDecimal replayNet = checked(cash.get(id).add(markedValue).subtract(fees.get(id)));
            BigDecimal gross = checked(position.realizedGrossPnlInr().add(position.unrealizedPnlInr()));
            if (position.signedUnits() != quantity || position.feesInr().compareTo(fees.get(id)) != 0
                    || replayNet.compareTo(gross.subtract(position.feesInr())) != 0
                    || (quantity == 0 && position.unrealizedPnlInr().signum() != 0))
                throw new IllegalArgumentException("Independent replay position/P&L reconciliation failed: " + id);
            boolean expired = !bar.expiry().isAfter(finalFrame.sessionDate());
            var event = inputs.events.get(id);
            if (event != null && (quantity == 0 || !expired))
                throw new IllegalArgumentException("Unused event: position flat or expiry after replay final date: " + id);
            if (expired && quantity != 0 && event == null) throw new IllegalArgumentException("Missing settlement for expiring open position: " + id);
            BigDecimal difference = ZERO, adjustment = ZERO, cost = ZERO, signedSettlement = ZERO;
            long after = quantity;
            String status = quantity == 0 ? "FLAT" : "UNEXPIRED";
            if (event != null) {
                if (!event.expiry().equals(bar.expiry()) || event.date().isBefore(bar.expiry()) || event.date().isAfter(finalFrame.sessionDate())
                        || (bar.contractKind() == ContractKind.FUTURE && event.method() != SettlementInputs.Method.CASH_FUTURES_FINAL_PRICE)
                        || (bar.contractKind() == ContractKind.OPTION && event.method() != SettlementInputs.Method.CASH_OPTION_PAYOFF))
                    throw new IllegalArgumentException("Settlement contract/method/date mismatch: " + id);
                used.add(id); status = "SETTLED"; after = 0;
                difference = checked(event.value().subtract(mark));
                adjustment = checked(BigDecimal.valueOf(quantity).multiply(difference));
                signedSettlement = checked(BigDecimal.valueOf(quantity).multiply(event.value()));
                cost = costs(inputs.costs, event, bar.contractKind(), Math.abs(quantity), costLines);
            }
            BigDecimal finalNet = checked(replayNet.add(adjustment).subtract(cost));
            BigDecimal independentlySettledNet = checked(cash.get(id).add(event == null ? markedValue : signedSettlement).subtract(fees.get(id)).subtract(cost));
            if (finalNet.compareTo(independentlySettledNet) != 0) throw new IllegalArgumentException("Settlement P&L reconciliation failed: " + id);
            BigDecimal postRealized = event == null ? position.realizedGrossPnlInr() : checked(gross.add(adjustment));
            BigDecimal postUnrealized = event == null ? position.unrealizedPnlInr() : ZERO;
            row(settlements, id, bar.exchange(), bar.segment(), bar.tradingSymbol(), bar.contractKind(), bar.underlying(), bar.expiry(),
                    bar.strike().orElse(null), bar.optionRight().orElse(null), finalFrame.sessionDate(), snapshot.timestamp(), mark, status,
                    event == null ? null : event.date(), event == null ? null : event.method(), event == null ? null : event.value(),
                    event == null ? null : event.sourceId(), event == null ? null : event.sourceReference(), quantity, event == null ? 0 : quantity,
                    after, cash.get(id), markedValue, position.realizedGrossPnlInr(), position.unrealizedPnlInr(), fees.get(id), replayNet,
                    event == null ? null : difference, event == null ? null : signedSettlement, adjustment, cost, postRealized, postUnrealized, finalNet);
            replayTotal = checked(replayTotal.add(replayNet)); totalFees = checked(totalFees.add(fees.get(id)));
            realizedTotal = checked(realizedTotal.add(position.realizedGrossPnlInr()));
            unrealizedTotal = checked(unrealizedTotal.add(position.unrealizedPnlInr()));
            adjustments = checked(adjustments.add(adjustment)); totalCosts = checked(totalCosts.add(cost)); finalTotal = checked(finalTotal.add(finalNet));
        }
        if (!used.equals(inputs.events.keySet())) throw new IllegalArgumentException("Unknown or unused settlement events");
        if (inputs.costs != null && used.isEmpty()) throw new IllegalArgumentException("Unused settlement costs: no settlements");
        if (replayTotal.compareTo(snapshot.netPnlInr()) != 0 || totalFees.compareTo(snapshot.feesInr()) != 0
                || realizedTotal.compareTo(snapshot.realizedGrossPnlInr()) != 0 || unrealizedTotal.compareTo(snapshot.unrealizedPnlInr()) != 0
                || finalTotal.compareTo(checked(replayTotal.add(adjustments).subtract(totalCosts))) != 0)
            throw new IllegalArgumentException("Portfolio P&L/fee/settlement totals mismatch");
        TreeMap<String, String> files = new TreeMap<>(); files.put("settlements.csv", settlements.toString());
        if (inputs.costs != null) files.put("settlement-cost-lines.csv", costLines.toString());
        return new Report(Map.copyOf(files), finalFrame.sessionDate(), replayTotal, adjustments, totalCosts, finalTotal, used.size());
    }

    private static BigDecimal costs(SettlementCostSchedule schedule, SettlementInputs.Event event, ContractKind kind, long units, StringBuilder out) {
        if (schedule == null) return ZERO;
        var declared = schedule.entries().stream().filter(e -> e.contractKind() == kind).map(SettlementCostSchedule.Entry::componentCode).collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        var applicable = schedule.entries().stream().filter(e -> e.contractKind() == kind && e.covers(event.date())).toList();
        if (declared.isEmpty() || applicable.size() != declared.size())
            throw new IllegalArgumentException("Incomplete dated settlement cost component coverage for " + kind + " on " + event.date());
        BigDecimal total = ZERO;
        for (var entry : applicable) {
            BigDecimal base = checked(switch (entry.basis()) {
                case FIXED_PER_SETTLEMENT -> BigDecimal.ONE;
                case PER_SETTLED_UNIT -> BigDecimal.valueOf(units);
                case BPS_OF_SETTLEMENT_NOTIONAL -> checked(event.value().multiply(BigDecimal.valueOf(units)));
                default -> throw new IllegalArgumentException("Unsupported exercise/intrinsic cost basis");
            });
            BigDecimal product = checked(base.multiply(entry.rate()));
            BigDecimal unrounded = entry.basis() == SettlementCostSchedule.Basis.BPS_OF_SETTLEMENT_NOTIONAL
                    ? checked(product.divide(BPS, 12, RoundingMode.HALF_UP)) : product;
            BigDecimal charge = checked(unrounded.setScale(2, RoundingMode.HALF_UP));
            BigDecimal taxable = entry.gstOnCharge() ? charge : ZERO.setScale(2);
            BigDecimal gstProduct = checked(taxable.multiply(entry.gstBps()));
            BigDecimal unroundedGst = checked(gstProduct.divide(BPS, 12, RoundingMode.HALF_UP));
            BigDecimal gst = checked(unroundedGst.setScale(2, RoundingMode.HALF_UP));
            BigDecimal lineTotal = checked(charge.add(gst)); total = checked(total.add(lineTotal));
            row(out, event.instrumentId(), event.expiry(), event.date(), kind, units, event.value(), entry.componentCode(),
                    entry.effectiveFrom(), entry.effectiveThrough(), entry.basis(), base, entry.rate(), product, unrounded, charge,
                    entry.gstOnCharge(), entry.gstBps(), taxable, gstProduct, unroundedGst, gst, lineTotal, entry.sourceReference());
        }
        return total;
    }
    static StringBuilder header(String header) { return new StringBuilder(header).append('\n'); }
    static String number(BigDecimal value) { return checked(value).stripTrailingZeros().toPlainString(); }
    static void row(StringBuilder out, Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(','); Object cell = cells[i];
            String text = cell == null ? "" : cell instanceof BigDecimal value ? number(value) : cell.toString();
            out.append(CsvTable.escape(text));
        }
        out.append('\n');
    }
}
