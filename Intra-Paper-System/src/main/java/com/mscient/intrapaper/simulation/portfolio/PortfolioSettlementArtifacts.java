package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.IntervalSupport;
import com.mscient.intrapaper.data.ValidatedDataset;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Canonical v3 settlement artifact layouts. */
final class PortfolioSettlementArtifacts {
    static final List<String> SETTLEMENT_HEADERS = concat(PortfolioSettlementEvents.HEADERS, List.of(
            "event_source_file_sha256", "signed_units_settled", "released_basis_inr", "signed_proceeds_inr",
            "realized_gross_pnl_inr", "cost_model", "expiry_settlement_fees_inr", "option_exercise_fees_inr", "total_fee_inr"));
    static final List<String> COST_HEADERS = concat(List.of("event_id", "schedule_source_file_sha256"),
            concat(PortfolioSettlementCostSchedule.HEADERS, List.of("calculation_base", "charge_inr", "gst_taxable_base_inr", "gst_inr", "total_fee_inr")));
    static final List<String> DATASET_HEADERS = concat(PortfolioSettlementContract.HEADERS.subList(1, PortfolioSettlementContract.HEADERS.size()),
            List.of("final_session_date", "final_bar_close_timestamp_utc", "final_session_complete"));
    private PortfolioSettlementArtifacts() { }
    static List<String> concat(List<String> a, List<String> b) { var r = new ArrayList<>(a); r.addAll(b); return List.copyOf(r); }
    static String csv(List<String> values) { return PortfolioReplayArtifactWriter.csv(values.toArray()); }
    static String datasetTail(ValidatedDataset data) {
        var last = data.bars().getLast(); var identity = PortfolioSettlementContract.from(last).cells();
        var values = new ArrayList<>(identity.subList(1, identity.size()));
        values.addAll(List.of(last.sessionDate().toString(), last.timestamp().plus(IntervalSupport.duration(last.interval())).toString(),
                Boolean.toString(data.finalSessionComplete())));
        return csv(values);
    }
    static String settlementsCsv(PortfolioReplayEngine.Result result) {
        var rows = new ArrayList<>(List.of(String.join(",", SETTLEMENT_HEADERS)));
        for (var settlement : result.settlements()) {
            var cells = new ArrayList<>(settlement.event().cells());
            cells.addAll(List.of(result.settlementEventsSourceFileSha256().orElseThrow(), Long.toString(settlement.signedUnits()),
                    decimal(settlement.releasedBasisInr()), decimal(settlement.signedProceedsInr()), decimal(settlement.realizedGrossPnlInr()),
                    settlement.costModel(), decimal(categoryFees(settlement, PortfolioSettlementCostSchedule.Category.EXPIRY_SETTLEMENT)),
                    decimal(categoryFees(settlement, PortfolioSettlementCostSchedule.Category.OPTION_EXERCISE)), decimal(settlement.feesInr())));
            rows.add(csv(cells));
        }
        return String.join("\n", rows) + "\n";
    }
    static String costsCsv(PortfolioReplayEngine.Result result) {
        var rows = new ArrayList<>(List.of(String.join(",", COST_HEADERS)));
        for (var settlement : result.settlements()) for (var line : settlement.chargeLines()) {
            var cells = new ArrayList<>(List.of(line.eventId(), line.sourceFileSha256()));
            cells.addAll(line.entry().cells(line.scheduleId()));
            cells.addAll(List.of(decimal(line.calculationBase()), decimal(line.chargeInr()), decimal(line.gstTaxableBaseInr()),
                    decimal(line.gstInr()), decimal(line.totalInr())));
            rows.add(csv(cells));
        }
        return String.join("\n", rows) + "\n";
    }
    static BigDecimal categoryFees(PortfolioSettlement settlement, PortfolioSettlementCostSchedule.Category category) {
        return settlement.chargeLines().stream().filter(l -> l.entry().category() == category)
                .map(PortfolioSettlementCostSchedule.Line::totalInr).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    static String decimal(BigDecimal value) { return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString(); }
}
