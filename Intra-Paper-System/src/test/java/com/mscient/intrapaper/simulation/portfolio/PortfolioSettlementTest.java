package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.provenance.*;
import com.mscient.intrapaper.reference.*;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.mscient.intrapaper.simulation.portfolio.PortfolioSettlementFixtures.*;

public final class PortfolioSettlementTest {
    private static final Instant TIME = Instant.parse("2030-05-06T03:45:00Z");
    private static final String HASH = "a".repeat(64);
    private PortfolioSettlementTest() { }
    public static void main(String[] args) throws Exception {
        signedAccounting(); partialBasisAndFlat(); chargesAndAtomicFailure(); strictInputs();
        System.out.println("PortfolioSettlement signed accounting, inputs, dates, charges, and atomicity checks passed");
    }
    static Bar bar(String id, ContractKind kind, OptionRight right, Instant time) {
        BigDecimal price = new BigDecimal(kind == ContractKind.FUTURE ? "100" : "10");
        return new Bar(time, LocalDate.parse(DAY), id, "SYN", "DERIV", id + "-TOKEN", id, kind, "SYNTHETIC",
                LocalDate.parse("2030-12-26"), kind == ContractKind.OPTION ? Optional.of(new BigDecimal("100")) : Optional.empty(),
                Optional.ofNullable(right), 1, new BigDecimal("0.05"), "ONE_MINUTE", TimestampRole.BAR_START,
                price, price, price, price, 1L, 1L, "synthetic", HASH);
    }
    static SynchronizedBarFrameBuilder.Frame frame(Bar a) {
        return new SynchronizedBarFrameBuilder.Frame(a.timestamp(), a.sessionDate(), Map.of(a.instrumentId(), a, "B", bar("B", ContractKind.FUTURE, null, a.timestamp())));
    }
    static PortfolioExecution fill(String id, long sequence, String instrument, Instant time, long signedUnits, String price) {
        return new PortfolioExecution(id, sequence, instrument, time, signedUnits > 0 ? PortfolioExecution.Side.BUY : PortfolioExecution.Side.SELL,
                Math.abs(signedUnits), new BigDecimal(price), BigDecimal.ZERO);
    }
    static PortfolioSettlementEvents.Event event(Bar bar, String value, boolean exercise) {
        return new PortfolioSettlementEvents.Event("SET-" + bar.instrumentId(), PortfolioSettlementContract.from(bar), bar.sessionDate(),
                bar.timestamp().plusSeconds(60), new BigDecimal(value), exercise, Optional.of(new BigDecimal("100")), Optional.of("synthetic-turnover"), "synthetic-value");
    }
    static PortfolioSettlementEvents events(PortfolioSettlementEvents.Event... events) { return new PortfolioSettlementEvents(List.of(events), HASH); }
    private static void signedAccounting() throws Exception {
        for (int sign : List.of(1, -1)) for (String kind : List.of("FUTURE", "CALL", "PUT")) for (String value : List.of("0", "2", "110")) {
            Bar a = bar("A", kind.equals("FUTURE") ? ContractKind.FUTURE : ContractKind.OPTION,
                    kind.equals("FUTURE") ? null : OptionRight.valueOf(kind), TIME);
            var frame = frame(a); var ledger = new PortfolioAccountingLedger();
            ledger.mark(frame, List.of(fill("FILL", 1, "A", TIME, sign * 25, a.open().toPlainString()))); ledger.markToClose(frame);
            var result = ledger.settleFinalSession(frame, events(event(a, value, false)), Optional.empty());
            BigDecimal expected = new BigDecimal(value).subtract(a.open()).multiply(BigDecimal.valueOf(sign * 25));
            equal(expected.toPlainString(), result.settlements().getFirst().realizedGrossPnlInr());
            equal(expected.toPlainString(), result.snapshots().getLast().netPnlInr());
            require(result.snapshots().getLast().positions().get("A").signedUnits() == 0, "settlement must flatten");
            failure(() -> ledger.settleFinalSession(frame, events(event(a, value, false)), Optional.empty()));
        }
        Bar future = bar("A", ContractKind.FUTURE, null, TIME); var frame = frame(future); var ledger = new PortfolioAccountingLedger();
        ledger.mark(frame, List.of(fill("F", 1, "A", TIME, -25, "100"))); ledger.markToClose(frame);
        equal("2550", ledger.settleFinalSession(frame, events(event(future, "-2", false)), Optional.empty()).settlements().getFirst().realizedGrossPnlInr());
    }
    private static void partialBasisAndFlat() throws Exception {
        Bar a = bar("A", ContractKind.FUTURE, null, TIME); var first = frame(a); var ledger = new PortfolioAccountingLedger();
        ledger.mark(first, List.of(fill("1", 1, "A", TIME, 1, "100.05"), fill("2", 2, "A", TIME, 2, "100.10"))); ledger.markToClose(first);
        Bar next = bar("A", ContractKind.FUTURE, null, TIME.plusSeconds(60)); var last = frame(next);
        ledger.mark(last, List.of(fill("3", 3, "A", next.timestamp(), -1, "110"))); ledger.markToClose(last);
        var result = ledger.settleFinalSession(last, events(event(next, "105", false), event(last.barsByInstrument().get("B"), "100", false)), Optional.empty());
        var settlement = result.settlements().getFirst(); require(settlement.signedUnits() == 2, "only the actual residual settles");
        equal("200.166666666666666667", settlement.releasedBasisInr()); equal("9.833333333333333333", settlement.realizedGrossPnlInr());
        equal("19.75", result.snapshots().getLast().realizedGrossPnlInr());
        require(result.settlements().getLast().signedUnits() == 0, "flat event remains audited");
        equal("0", result.settlements().getLast().feesInr()); require(result.snapshots().size() == 1, "same-time events share one snapshot");
    }
    static PortfolioSettlementCostSchedule.Entry entry(PortfolioSettlementEvents.Event event, String id,
            PortfolioSettlementCostSchedule.Basis basis, PortfolioSettlementCostSchedule.Category category, String rate, RoundingMode mode) {
        return new PortfolioSettlementCostSchedule.Entry(id, LocalDate.parse("2030-01-01"), Optional.of(LocalDate.parse("2031-01-01")), event.contract(),
                PortfolioSettlementCostSchedule.PositionSide.LONG, category, id, basis, new BigDecimal(rate), 2, mode,
                new BigDecimal("1800"), true, 2, RoundingMode.HALF_UP, "synthetic-tariff");
    }
    private static void chargesAndAtomicFailure() throws Exception {
        Bar option = bar("A", ContractKind.OPTION, OptionRight.CALL, TIME); var event = event(option, "2.4", true);
        var expected = List.of("1.19", "2.95", "2.95", "0.71", "2.95");
        var rates = List.of("1.005", "0.1", "0.1", "100", "10"); int index = 0;
        for (var basis : PortfolioSettlementCostSchedule.Basis.values()) {
            var category = basis == PortfolioSettlementCostSchedule.Basis.PER_EXERCISED_OPTION_UNIT
                    ? PortfolioSettlementCostSchedule.Category.OPTION_EXERCISE : PortfolioSettlementCostSchedule.Category.EXPIRY_SETTLEMENT;
            var other = category == PortfolioSettlementCostSchedule.Category.OPTION_EXERCISE
                    ? PortfolioSettlementCostSchedule.Category.EXPIRY_SETTLEMENT : PortfolioSettlementCostSchedule.Category.OPTION_EXERCISE;
            var rows = List.of(entry(event, "ACTUAL", basis, category, rates.get(index), RoundingMode.HALF_UP),
                    entry(event, "ZERO", PortfolioSettlementCostSchedule.Basis.FIXED_PER_SETTLEMENT, other, "0", RoundingMode.HALF_UP));
            var schedule = new PortfolioSettlementCostSchedule("S", rows, HASH);
            equal(expected.get(index++), schedule.calculate(event, 25).stream().map(PortfolioSettlementCostSchedule.Line::totalInr).reduce(BigDecimal.ZERO, BigDecimal::add));
            require(schedule.calculate(event, 0).isEmpty(), "flat events must not charge fixed or exercise fees");
            failure(() -> schedule.calculate(event, -25));
            failure(() -> new PortfolioSettlementCostSchedule("S", List.of(rows.getFirst(), rows.getFirst()), HASH));
        }
        var noTurnover = new PortfolioSettlementEvents.Event(event.eventId(), event.contract(), event.settlementDate(), event.timestamp(), event.valuePerUnit(), true,
                Optional.empty(), Optional.empty(), event.sourceReference());
        var turnover = new PortfolioSettlementCostSchedule("S", List.of(entry(event, "BASE", PortfolioSettlementCostSchedule.Basis.BPS_OF_CALLER_TURNOVER,
                PortfolioSettlementCostSchedule.Category.EXPIRY_SETTLEMENT, "1", RoundingMode.HALF_UP)), HASH);
        failure(() -> turnover.calculate(noTurnover, 1));
        var originalEntry = turnover.entries().getFirst();
        var overlap = new PortfolioSettlementCostSchedule.Entry("OTHER-ID", originalEntry.effectiveFrom(), originalEntry.effectiveUntil(),
                originalEntry.contract(), originalEntry.side(), originalEntry.category(), originalEntry.componentCode(), originalEntry.basis(),
                originalEntry.rate(), originalEntry.chargeScale(), originalEntry.chargeMode(), originalEntry.gstBps(), originalEntry.gstOnCharge(),
                originalEntry.gstScale(), originalEntry.gstMode(), originalEntry.sourceReference());
        failure(() -> new PortfolioSettlementCostSchedule("S", List.of(originalEntry, overlap), HASH));
        var frame = frame(option); Bar b = frame.barsByInstrument().get("B"); var be = event(b, "110", false);
        var ledger = new PortfolioAccountingLedger(); ledger.mark(frame, List.of(fill("1", 1, "A", TIME, 1, "10"), fill("2", 2, "B", TIME, 1, "100"))); ledger.markToClose(frame);
        var schedule = new PortfolioSettlementCostSchedule("S", List.of(
                entry(event, "A1", PortfolioSettlementCostSchedule.Basis.FIXED_PER_SETTLEMENT, PortfolioSettlementCostSchedule.Category.EXPIRY_SETTLEMENT, "0", RoundingMode.HALF_UP),
                entry(event, "A2", PortfolioSettlementCostSchedule.Basis.PER_EXERCISED_OPTION_UNIT, PortfolioSettlementCostSchedule.Category.OPTION_EXERCISE, "0", RoundingMode.HALF_UP),
                entry(be, "B1", PortfolioSettlementCostSchedule.Basis.FIXED_PER_SETTLEMENT, PortfolioSettlementCostSchedule.Category.EXPIRY_SETTLEMENT, "1.005", RoundingMode.UNNECESSARY)), HASH);
        failure(() -> ledger.settleFinalSession(frame, events(event, be), Optional.of(schedule)));
        var retried = ledger.settleFinalSession(frame, events(event, be), Optional.empty());
        require(retried.settlements().stream().allMatch(s -> s.signedUnits() == 1), "failed batch must not mutate first position");
        equal("2.4", retried.snapshots().getLast().netPnlInr());
    }
    private static void strictInputs() throws Exception {
        Path root = Files.createTempDirectory("portfolio-settlement-inputs-");
        try {
            create(root); var valid = replay(root); equal("-4.83", valid.result().snapshots().getLast().netPnlInr());
            Path events = root.resolve("settlement-events.csv"), costs = root.resolve("settlement-costs.csv");
            String original = Files.readString(events), settings = Files.readString(root.resolve("portfolio.properties"));
            Map<String, String> bad = Map.ofEntries(Map.entry("instrument_id", "UNKNOWN"), Map.entry("exchange", "OTHER"), Map.entry("segment", "OTHER"),
                    Map.entry("source_token", "OTHER"), Map.entry("trading_symbol", "OTHER"), Map.entry("underlying", "OTHER"), Map.entry("expiry", "2030-12-26"),
                    Map.entry("lot_size", "2"), Map.entry("tick_size", "0.10"), Map.entry("settlement_date", "2030-05-05"),
                    Map.entry("settlement_timestamp", "2030-05-06T03:48:59Z"), Map.entry("source_reference", ""));
            for (var change : bad.entrySet()) { cell(events, 0, change.getKey(), change.getValue()); failure(() -> replay(root)); Files.writeString(events, original); }
            for (var change : Map.of("strike", "101", "option_right", "PUT", "contract_kind", "FUTURE").entrySet()) {
                cell(events, 1, change.getKey(), change.getValue()); failure(() -> replay(root)); Files.writeString(events, original);
            }
            for (String timestamp : List.of("2030-05-06T09:19:00", "2030-05-07T00:00:00Z")) {
                cell(events, 0, "settlement_timestamp", timestamp); failure(() -> replay(root)); Files.writeString(events, original);
            }
            cell(events, 0, "settlement_date", "2030-05-07"); failure(() -> PortfolioSettlementEvents.load(events)); Files.writeString(events, original);
            cell(events, 0, "event_id", "SET-O"); failure(() -> PortfolioSettlementEvents.load(events)); Files.writeString(events, original);
            Files.writeString(events, original + original.lines().skip(1).findFirst().orElseThrow() + "\n"); failure(() -> PortfolioSettlementEvents.load(events));
            Files.writeString(events, String.join("\n", original.lines().limit(2).toList()) + "\n"); failure(() -> replay(root)); Files.writeString(events, original);
            Files.writeString(events, original.replace("event_id", "wrong_header")); failure(() -> PortfolioSettlementEvents.load(events)); Files.writeString(events, original);
            String originalCosts = Files.readString(costs);
            for (var change : Map.of("effective_until", DAY, "effective_from", "2030-05-07", "source_reference", "", "charge_rounding_mode", "UNKNOWN",
                    "rate", "-1", "charge_rounding_scale", "19", "gst_on_charge", "TRUE", "instrument_id", "UNKNOWN").entrySet()) {
                cell(costs, 0, change.getKey(), change.getValue()); failure(() -> replay(root)); Files.writeString(costs, originalCosts);
            }
            cell(costs, 0, "effective_from", DAY); replay(root); Files.writeString(costs, originalCosts);
            cell(costs, 1, "gst_bps", "1800"); equal("-4.83", replay(root).result().snapshots().getLast().netPnlInr()); Files.writeString(costs, originalCosts);
            cell(costs, 0, "gst_rounding_scale", "0"); cell(costs, 0, "gst_rounding_mode", "DOWN");
            equal("-4.65", replay(root).result().snapshots().getLast().netPnlInr()); Files.writeString(costs, originalCosts);
            Files.writeString(root.resolve("portfolio.properties"), settings.replace("F,O", "F,F")); failure(() -> replay(root));
            Files.writeString(root.resolve("portfolio.properties"), settings.replace("F,O", "F")); failure(() -> replay(root));
            Files.writeString(root.resolve("portfolio.properties"), BASE_SETTINGS); failure(() -> replay(root)); Files.writeString(root.resolve("portfolio.properties"), settings);
            Path bars = root.resolve("future.csv"); String originalBars = Files.readString(bars);
            Files.writeString(bars, String.join("\n", originalBars.lines().filter(s -> !s.startsWith("2030-05-06T09:16")).toList()) + "\n"); failure(() -> replay(root)); Files.writeString(bars, originalBars);
            failure(() -> ValidatedDataset.load(root.resolve("future.csv"), RunConfig.load(root.resolve("future.properties"))));
            Files.writeString(root.resolve("legacy.csv"), "instrument_id,expiry,settlement_date,settlement_timestamp,settlement_kind,settlement_value,source_reference\nF,2030-05-06,2030-05-06,2030-05-06T09:19:00+05:30,FUTURES_FINAL_PRICE,110,synthetic-legacy\n");
            Files.writeString(root.resolve("future.properties"), Files.readString(root.resolve("future.properties")) + "settlement.file=legacy.csv\n");
            var legacy = ValidatedDataset.load(root.resolve("future.csv"), RunConfig.load(root.resolve("future.properties")));
            require(legacy.expirySettlement().isPresent(), "single-contract input remains supported"); failure(() -> replay(root));
        } finally { cleanup(root); }
    }
}
