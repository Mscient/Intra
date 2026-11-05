package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.reference.ContractKind;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/** Focused validation and arithmetic checks for portfolio execution cost schedules. */
public final class PortfolioExecutionCostScheduleTest {
    private static final String HEADER = "schedule_id,entry_id,effective_from,effective_until,instrument_id,contract_kind,side,brokerage_per_order_inr,brokerage_per_unit_inr,brokerage_bps,exchange_inr_per_crore,ipft_inr_per_crore,sebi_inr_per_crore,stt_bps,stamp_duty_bps,gst_bps,gst_on_brokerage,gst_on_exchange,gst_on_ipft,gst_on_sebi,gst_on_stt,gst_on_stamp_duty,source_reference";

    private PortfolioExecutionCostScheduleTest() { }

    public static void main(String[] args) throws Exception {
        calculatesItemizedChargesWithHalfOpenCoverage();
        rowOrderDoesNotChangeResolution();
        rejectsDuplicateIdsOverlapsMalformedRowsAndMissingCoverage();
        System.out.println("PortfolioExecutionCostSchedule checks passed");
    }

    private static void calculatesItemizedChargesWithHalfOpenCoverage() throws Exception {
        Path csv = write(List.of(
                row("sell-old", "2026-11-01", "2026-11-04", "SELL", "10", "0"),
                row("sell-new", "2026-11-04", "", "SELL", "20", "0"),
                row("buy", "2026-11-01", "", "BUY", "0", "5")));
        PortfolioExecutionCostSchedule schedule = PortfolioExecutionCostSchedule.load(csv);
        var sell = schedule.calculate(LocalDate.of(2026, 11, 3), "FUT", ContractKind.FUTURE,
                PortfolioExecution.Side.SELL, new BigDecimal("100"), 25).breakdown();
        require(sell.brokerageInr().compareTo(new BigDecimal("1.75")) == 0, "brokerage rounding");
        require(sell.exchangeTransactionInr().compareTo(new BigDecimal("0.03")) == 0, "exchange rounding");
        require(sell.ipftContributionInr().compareTo(new BigDecimal("0.01")) == 0, "IPFT rounding");
        require(sell.sebiTurnoverInr().compareTo(new BigDecimal("0.01")) == 0, "SEBI rounding");
        require(sell.sttInr().compareTo(new BigDecimal("2.50")) == 0, "STT rounding");
        require(sell.gstTaxableBaseInr().compareTo(new BigDecimal("1.80")) == 0, "explicit GST bases");
        require(sell.gstInr().compareTo(new BigDecimal("0.32")) == 0, "GST rounding");
        require(sell.totalFeeInr().compareTo(new BigDecimal("4.62")) == 0, "itemized total");
        var boundary = schedule.calculate(LocalDate.of(2026, 11, 4), "FUT", ContractKind.FUTURE,
                PortfolioExecution.Side.SELL, new BigDecimal("100"), 25).breakdown();
        require(boundary.sttInr().compareTo(new BigDecimal("5.00")) == 0,
                "effective_until must be exclusive");
    }

    private static void rowOrderDoesNotChangeResolution() throws Exception {
        String first = row("sell-old", "2026-11-01", "2026-11-04", "SELL", "10", "0");
        String second = row("sell-new", "2026-11-04", "", "SELL", "20", "0");
        PortfolioExecutionCostSchedule a = PortfolioExecutionCostSchedule.load(write(List.of(first, second)));
        PortfolioExecutionCostSchedule b = PortfolioExecutionCostSchedule.load(write(List.of(second, first)));
        require(a.entries().stream().map(PortfolioExecutionCostSchedule.Entry::entryId).toList()
                        .equals(b.entries().stream().map(PortfolioExecutionCostSchedule.Entry::entryId).toList()),
                "schedule entries must be canonically ordered");
        var aFee = a.calculate(LocalDate.of(2026, 11, 3), "FUT", ContractKind.FUTURE,
                PortfolioExecution.Side.SELL, new BigDecimal("100"), 25).breakdown();
        var bFee = b.calculate(LocalDate.of(2026, 11, 3), "FUT", ContractKind.FUTURE,
                PortfolioExecution.Side.SELL, new BigDecimal("100"), 25).breakdown();
        require(aFee.totalFeeInr().compareTo(bFee.totalFeeInr()) == 0
                        && aFee.scheduleEntryId().equals(bFee.scheduleEntryId()),
                "row order must not affect cost resolution");
        require(!a.sourceFileSha256().equals(b.sourceFileSha256()),
                "exact source-file provenance must still distinguish reordered bytes");
    }

    private static void rejectsDuplicateIdsOverlapsMalformedRowsAndMissingCoverage() throws Exception {
        expectFailure(() -> PortfolioExecutionCostSchedule.load(write(List.of(
                row("same", "2026-11-01", "2026-11-02", "SELL", "10", "0"),
                row("same", "2026-11-02", "", "SELL", "10", "0")))));
        expectFailure(() -> PortfolioExecutionCostSchedule.load(write(List.of(
                row("one", "2026-11-01", "2026-11-05", "SELL", "10", "0"),
                row("two", "2026-11-04", "", "SELL", "10", "0")))));
        expectFailure(() -> PortfolioExecutionCostSchedule.load(write(List.of(
                row("bad", "2026-11-04", "2026-11-04", "SELL", "10", "0")))));
        PortfolioExecutionCostSchedule schedule = PortfolioExecutionCostSchedule.load(write(List.of(
                row("one", "2026-11-01", "2026-11-04", "SELL", "10", "0"))));
        expectFailure(() -> schedule.calculate(LocalDate.of(2026, 11, 4), "FUT", ContractKind.FUTURE,
                PortfolioExecution.Side.SELL, new BigDecimal("100"), 25));
    }

    private static String row(String id, String from, String until, String side, String stt, String stamp) {
        return String.join(",", "portfolio-fixture", id, from, until, "FUT", "FUTURE", side,
                "1", "0.01", "2", "100", "40", "20", stt, stamp, "1800",
                "true", "true", "true", "true", "false", "false", "fixture-tariff-reference");
    }

    private static Path write(List<String> rows) throws Exception {
        Path path = Files.createTempFile("portfolio-cost-schedule-", ".csv");
        Files.writeString(path, HEADER + "\n" + String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
        path.toFile().deleteOnExit();
        return path;
    }

    private static void expectFailure(Throwing action) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected schedule validation failure");
    }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
