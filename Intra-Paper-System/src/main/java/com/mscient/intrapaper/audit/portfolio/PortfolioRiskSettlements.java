package com.mscient.intrapaper.audit.portfolio;

import com.mscient.intrapaper.data.SynchronizedBarFrameBuilder;
import com.mscient.intrapaper.simulation.portfolio.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.mscient.intrapaper.audit.portfolio.PortfolioRiskCalculator.*;

/** Independent settlement bridge. Historical close values are reconciliation inputs, not new quotes. */
final class PortfolioRiskSettlements {
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    final NavigableMap<Instant, List<PortfolioSettlement>> groups = new TreeMap<>();
    final StringBuilder events = header("frame_index,session_date,timestamp_utc,event_id,instrument_id,contract_kind,pre_signed_units,pre_close_timestamp_utc,pre_close_price,pre_signed_exposure_inr,pre_gross_notional_inr,released_basis_inr,caller_settlement_value_per_unit_inr,signed_proceeds_inr,settlement_realized_gross_pnl_inr,settlement_mark_adjustment_inr,modeled_charges_inr,settlement_charges_inr,modeled_net_adjustment_inr,net_adjustment_inr,post_signed_units,post_signed_exposure_inr,post_gross_notional_inr,cost_model,charge_coverage,value_classification,source_reference,event_source_sha256,cost_source_sha256,reconciliation");
    final StringBuilder charges = header("event_id,instrument_id,schedule_id,entry_id,position_side,charge_category,component_code,basis,calculation_base,rate,charge_rounding_scale,charge_rounding_mode,charge_inr,gst_taxable_base_inr,gst_bps,gst_rounding_scale,gst_rounding_mode,gst_inr,total_inr,source_reference,source_file_sha256");
    private final PortfolioReplayEngine.Result replay;

    PortfolioRiskSettlements(PortfolioReplayEngine.Result replay) {
        this.replay = replay;
        Set<String> ids = new HashSet<>(), instruments = new TreeSet<>();
        Instant previous = null;
        String previousInstrument = "";
        for (var settlement : replay.settlements()) {
            var event = settlement.event();
            String instrument = event.contract().instrumentId();
            if (!ids.add(event.eventId()) || !instruments.add(instrument)
                    || (previous != null && (event.timestamp().isBefore(previous)
                    || (event.timestamp().equals(previous) && instrument.compareTo(previousInstrument) <= 0))))
                throw new IllegalArgumentException("Duplicate or out-of-order settlement event");
            groups.computeIfAbsent(event.timestamp(), ignored -> new ArrayList<>()).add(settlement);
            previous = event.timestamp(); previousInstrument = instrument;
        }
        if (!instruments.equals(replay.config().settlementInstruments()))
            throw new IllegalArgumentException("Unmatched settlement targets/events");
    }

    String reconcile(int frameIndex, SynchronizedBarFrameBuilder.Frame frame, Instant closeAt,
            PortfolioSettlement settlement, long units, BigDecimal basis) {
        var event = settlement.event();
        String id = event.contract().instrumentId();
        var bar = frame.barsByInstrument().get(id);
        if (bar == null || !event.contract().equals(PortfolioSettlementContract.from(bar))
                || !event.settlementDate().equals(frame.sessionDate()) || event.timestamp().isBefore(closeAt)
                || units != settlement.signedUnits())
            throw new IllegalArgumentException("Settlement identity/date/position mismatch");
        BigDecimal proceeds = event.valuePerUnit().multiply(BigDecimal.valueOf(units));
        BigDecimal gross = proceeds.subtract(basis.multiply(BigDecimal.valueOf(Long.signum(units))));
        if (basis.compareTo(settlement.releasedBasisInr()) != 0 || proceeds.compareTo(settlement.signedProceedsInr()) != 0
                || gross.compareTo(settlement.realizedGrossPnlInr()) != 0)
            throw new IllegalArgumentException("Independent settlement basis/proceeds/P&L reconciliation failed: " + id);
        String coverage = reconcileCharges(settlement);
        BigDecimal preValue = bar.close().multiply(BigDecimal.valueOf(units));
        BigDecimal adjustment = proceeds.subtract(preValue), modeledCharges = settlement.feesInr();
        boolean complete = !coverage.equals("MISSING_SETTLEMENT_CHARGES");
        row(events, frameIndex, frame.sessionDate(), event.timestamp(), event.eventId(), id, event.contract().contractKind(),
                units, closeAt, bar.close(), preValue, preValue.abs(), basis, event.valuePerUnit(), proceeds, gross,
                adjustment, modeledCharges, complete ? modeledCharges : null, adjustment.subtract(modeledCharges),
                complete ? adjustment.subtract(modeledCharges) : null, 0, ZERO, ZERO, settlement.costModel(), coverage,
                "CALLER_SUPPLIED_UNAUTHENTICATED", event.sourceReference(), replay.settlementEventsSourceFileSha256().orElse(""),
                replay.settlementCostScheduleSourceFileSha256().orElse(""), "PASS");
        return coverage;
    }

    private String reconcileCharges(PortfolioSettlement settlement) {
        boolean dated = replay.settlementCostScheduleId().isPresent();
        if (dated != settlement.costModel().equals("DATED_SCHEDULE"))
            throw new IllegalArgumentException("Settlement cost model/provenance mismatch");
        if (settlement.signedUnits() == 0) return "NOT_REQUIRED_FLAT";
        if (!dated) return "MISSING_SETTLEMENT_CHARGES";
        var event = settlement.event();
        var side = settlement.signedUnits() > 0 ? PortfolioSettlementCostSchedule.PositionSide.LONG
                : PortfolioSettlementCostSchedule.PositionSide.SHORT;
        Set<String> entries = new HashSet<>(), components = new HashSet<>();
        Set<PortfolioSettlementCostSchedule.Category> categories = new HashSet<>();
        BigDecimal quantity = BigDecimal.valueOf(Math.absExact(settlement.signedUnits()));
        for (var line : settlement.chargeLines()) {
            var entry = line.entry();
            if (!line.eventId().equals(event.eventId()) || !entry.contract().equals(event.contract())
                    || !line.scheduleId().equals(replay.settlementCostScheduleId().orElseThrow())
                    || !line.sourceFileSha256().equals(replay.settlementCostScheduleSourceFileSha256().orElseThrow())
                    || entry.side() != side || !entry.covers(event.settlementDate()) || !entries.add(entry.entryId())
                    || !components.add(entry.category() + "/" + entry.componentCode())
                    || (entry.category() == PortfolioSettlementCostSchedule.Category.OPTION_EXERCISE && !event.optionExercise()))
                throw new IllegalArgumentException("Settlement charge identity/coverage mismatch");
            categories.add(entry.category());
            BigDecimal base = switch (entry.basis()) {
                case FIXED_PER_SETTLEMENT -> BigDecimal.ONE;
                case PER_SETTLED_UNIT, PER_EXERCISED_OPTION_UNIT -> quantity;
                case BPS_OF_SETTLEMENT_VALUE -> event.valuePerUnit().abs().multiply(quantity);
                case BPS_OF_CALLER_TURNOVER -> event.chargeTurnoverPerUnit().orElseThrow(() ->
                        new IllegalArgumentException("Missing caller settlement turnover")).multiply(quantity);
            };
            BigDecimal raw = base.multiply(entry.rate());
            if (entry.basis() == PortfolioSettlementCostSchedule.Basis.BPS_OF_SETTLEMENT_VALUE
                    || entry.basis() == PortfolioSettlementCostSchedule.Basis.BPS_OF_CALLER_TURNOVER) raw = raw.movePointLeft(4);
            BigDecimal charge = raw.setScale(entry.chargeScale(), entry.chargeMode());
            BigDecimal gstBase = entry.gstOnCharge() ? charge : ZERO;
            BigDecimal gst = gstBase.multiply(entry.gstBps()).movePointLeft(4).setScale(entry.gstScale(), entry.gstMode());
            if (base.compareTo(line.calculationBase()) != 0 || charge.compareTo(line.chargeInr()) != 0
                    || gstBase.compareTo(line.gstTaxableBaseInr()) != 0 || gst.compareTo(line.gstInr()) != 0)
                throw new IllegalArgumentException("Independent settlement charge reconciliation failed");
            row(charges, event.eventId(), event.contract().instrumentId(), line.scheduleId(), entry.entryId(), side,
                    entry.category(), entry.componentCode(), entry.basis(), base, entry.rate(), entry.chargeScale(), entry.chargeMode(),
                    charge, gstBase, entry.gstBps(), entry.gstScale(), entry.gstMode(), gst, charge.add(gst),
                    entry.sourceReference(), line.sourceFileSha256());
        }
        if (!categories.contains(PortfolioSettlementCostSchedule.Category.EXPIRY_SETTLEMENT)
                || (event.optionExercise() && !categories.contains(PortfolioSettlementCostSchedule.Category.OPTION_EXERCISE)))
            return "MISSING_SETTLEMENT_CHARGES";
        return "COMPLETE_CALLER_SCHEDULE";
    }
}
