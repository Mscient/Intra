package com.mscient.intrapaper.audit.reconciliation;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.provenance.*;
import com.mscient.intrapaper.reference.ContractKind;
import com.mscient.intrapaper.reference.TimestampRole;
import com.mscient.intrapaper.simulation.*;
import com.mscient.intrapaper.simulation.portfolio.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.mscient.intrapaper.audit.reconciliation.LedgerSupport.*;

/** Only the public workflow may obtain a baseline: full integrity and deterministic replay are mandatory. */
final class VerifiedReplay {
    final String runId;
    final Instant start, end;
    final List<Expected> records;
    final boolean completeSessions;

    VerifiedReplay(String runId, Instant start, Instant end, List<Expected> records, boolean completeSessions) {
        this.runId = runId; this.start = start; this.end = end; this.records = List.copyOf(records);
        this.completeSessions = completeSessions;
        Set<String> ids = new HashSet<>();
        for (Expected record : records) if (!ids.add(record.kind() + ":" + record.id()))
            throw new IllegalArgumentException("Duplicate internal replay identity");
    }

    static VerifiedReplay load(ExternalLedgerAudit.ReplayKind kind, Path run, Path input, Path settings,
                               Snapshots snapshots, boolean itemized) throws IOException {
        snapshots.read(input, "replay/input", kind == ExternalLedgerAudit.ReplayKind.SINGLE ? "bars.csv" : "portfolio-inputs.csv", "", "", "");
        snapshots.read(settings, "replay/settings", "replay-settings", "", "", "");
        if (!Files.isDirectory(run, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(run))
            throw new IOException("Saved replay must be a regular directory");
        try (var paths = Files.list(run)) {
            for (Path path : paths.sorted().toList())
                snapshots.read(path, "replay/output/" + path.getFileName(), path.getFileName().toString(), "", "", "");
        }
        if (kind == ExternalLedgerAudit.ReplayKind.PORTFOLIO) {
            var replay = PortfolioReplayCsvRunner.replay(input, settings);
            replay.dataset().datasetsByInstrument().forEach((id, data) -> datasetHashes(snapshots, id, data));
            snapshots.hashOnly("replay/frame-inputs", replay.result().frameInputsSha256());
            replay.result().executionCostScheduleSourceFileSha256().ifPresent(h -> snapshots.hashOnly("replay/execution-cost-schedule", h));
            replay.result().settlementEventsSourceFileSha256().ifPresent(h -> snapshots.hashOnly("replay/settlement-events", h));
            replay.result().settlementCostScheduleSourceFileSha256().ifPresent(h -> snapshots.hashOnly("replay/settlement-cost-schedule", h));
            var verified = PortfolioReplayDeterminismVerifier.verify(input, settings, run);
            if (!verified.runId().equals(replay.result().replayFingerprintSha256())
                    || !verified.validatedDatasetsSha256().equals(replay.dataset().datasetsSha256()))
                throw new IOException("Replay inputs changed during reconstruction");
            return portfolio(replay, itemized);
        }
        RunConfig config = RunConfig.load(settings);
        ValidatedDataset data = ValidatedDataset.load(input, config);
        datasetHashes(snapshots, data.bars().getFirst().instrumentId(), data);
        Strategy strategy = new SmaCrossoverStrategy(config);
        var verified = RunReplayVerifier.verify(input, settings, run);
        if (!verified.runId().equals(RunIdentity.contentId(data, config, strategy)))
            throw new IOException("Replay inputs changed during reconstruction");
        BacktestResult result = new BacktestEngine().run(data, config, strategy);
        return single(verified.runId(), data, result);
    }

    static void reverify(ExternalLedgerAudit.ReplayKind kind, Path run, Path input, Path settings, String id) throws IOException {
        String verified = kind == ExternalLedgerAudit.ReplayKind.PORTFOLIO
                ? PortfolioReplayDeterminismVerifier.verify(input, settings, run).runId()
                : RunReplayVerifier.verify(input, settings, run).runId();
        if (!id.equals(verified)) throw new IOException("Replay identity changed during reconciliation");
    }

    private static void datasetHashes(Snapshots snapshots, String id, ValidatedDataset data) {
        String prefix = "replay/dataset/" + id + "/";
        snapshots.hashOnly(prefix + "bars", data.barsSha256());
        snapshots.hashOnly(prefix + "config", data.configSha256());
        snapshots.hashOnly(prefix + "calendar", data.calendarSha256());
        snapshots.hashOnly(prefix + "instrument-master", data.manifest().instrumentMasterSha256());
        snapshots.hashOnly(prefix + "market-rules", data.marketRuleRegimeSchedule().sha256());
        data.manifest().inputFileSha256().forEach((name, h) -> snapshots.hashOnly(prefix + name, h));
        data.costSchedule().ifPresent(s -> snapshots.hashOnly(prefix + "cost_schedule", s.sha256()));
        data.componentCostSchedule().ifPresent(s -> snapshots.hashOnly(prefix + "component_cost_schedule", s.sha256()));
        data.marginRequirementSchedule().ifPresent(s -> snapshots.hashOnly(prefix + "margin_requirement_schedule", s.sha256()));
        data.expirySettlement().ifPresent(s -> snapshots.hashOnly(prefix + "expiry_settlement", s.sha256()));
        data.settlementCostSchedule().ifPresent(s -> snapshots.hashOnly(prefix + "settlement_cost_schedule", s.sha256()));
        data.quoteHistory().ifPresent(s -> snapshots.hashOnly(prefix + "quote_journal", s.fileSha256()));
        data.optionValuationInputs().ifPresent(s -> snapshots.hashOnly(prefix + "option_valuation_inputs", s.sha256()));
    }

    private static VerifiedReplay portfolio(PortfolioReplayCsvRunner.Replay replay, boolean itemized) {
        var result = replay.result(); var records = new ArrayList<Expected>();
        Instant start = replay.dataset().frames().getFirst().timestamp(), end = result.snapshots().getLast().timestamp();
        Map<String, String> orderByFill = new HashMap<>();
        for (var order : result.orders()) {
            var intent = order.intent();
            records.add(new Expected(Kind.ORDER, intent.intentId(), fields("event_time_utc", intent.decisionTimestamp(),
                    "instrument_id", intent.instrumentId(), "side", intent.side(), "status", order.status(),
                    "quantity_units", intent.requestedUnits(), "filled_units", order.filledUnits())));
            order.execution().ifPresent(e -> orderByFill.put(e.executionId(), intent.intentId()));
        }
        for (var fill : result.executions()) {
            records.add(new Expected(Kind.FILL, fill.executionId(), fields("event_time_utc", fill.timestamp(),
                    "instrument_id", fill.instrumentId(), "side", fill.side(), "order_id", orderByFill.get(fill.executionId()),
                    "quantity_units", fill.quantityUnits(), "price_inr", fill.price())));
            BigDecimal notional = fill.price().multiply(BigDecimal.valueOf(fill.quantityUnits()));
            money(records, Kind.FILL, fill.executionId(), fill.instrumentId(), fill.timestamp(),
                    fill.side() == PortfolioExecution.Side.BUY ? notional.negate() : notional, fill.feeInr());
            if (itemized) LedgerChargeItems.execution(records, fill);
        }
        for (var settlement : result.settlements()) {
            var event = settlement.event(); String id = event.eventId(), instrument = event.contract().instrumentId();
            records.add(new Expected(Kind.SETTLEMENT, id, fields("event_time_utc", event.timestamp(), "instrument_id", instrument,
                    "component", event.contract().contractKind() == ContractKind.FUTURE ? "CASH_FUTURES_FINAL_PRICE" : "CASH_OPTION_PAYOFF",
                    "quantity_units", settlement.signedUnits(), "price_inr", event.valuePerUnit(), "amount_inr", settlement.signedProceedsInr())));
            money(records, Kind.SETTLEMENT, id, instrument, event.timestamp(), settlement.signedProceedsInr(),
                    settlement.costModel().equals("NOT_MODELED") ? null : settlement.feesInr());
            if (itemized) LedgerChargeItems.settlement(records, settlement);
        }
        for (String instrument : new TreeSet<>(replay.dataset().datasetsByInstrument().keySet())) {
            positions(records, instrument, start, end, result.snapshots().getLast().positions().get(instrument).signedUnits());
        }
        boolean complete = replay.dataset().datasetsByInstrument().values().stream()
                .allMatch(d -> d.completeSessionCount() == d.openSessionCount());
        return new VerifiedReplay(result.replayFingerprintSha256(), start, end, records, complete);
    }

    private static VerifiedReplay single(String id, ValidatedDataset data, BacktestResult result) {
        var records = new ArrayList<Expected>(); String instrument = data.bars().getFirst().instrumentId();
        Instant start = data.bars().getFirst().timestamp();
        Bar last = data.bars().getLast();
        Instant end = last.timestampRole() == TimestampRole.BAR_START
                ? last.timestamp().plus(IntervalSupport.duration(last.interval())) : last.timestamp();
        for (var settlement : result.settlements()) if (settlement.settlementTimestamp().isAfter(end)) end = settlement.settlementTimestamp();
        Map<String, BigDecimal> filled = new HashMap<>();
        for (var fill : result.fills()) filled.merge(fill.orderId(), BigDecimal.valueOf(fill.quantityUnits()), BigDecimal::add);
        for (var order : result.orders())
            records.add(new Expected(Kind.ORDER, order.orderId(), fields("event_time_utc", order.decisionTimestamp(),
                    "instrument_id", instrument, "side", order.side(), "status", order.status(),
                    "quantity_units", order.requestedUnits(), "filled_units", filled.getOrDefault(order.orderId(), BigDecimal.ZERO))));
        for (var fill : result.fills()) {
            records.add(new Expected(Kind.FILL, fill.fillId(), fields("event_time_utc", fill.timestamp(), "instrument_id", instrument,
                    "side", fill.side(), "order_id", fill.orderId(), "quantity_units", fill.quantityUnits(), "price_inr", fill.fillPrice())));
            BigDecimal notional = fill.fillPrice().multiply(BigDecimal.valueOf(fill.quantityUnits()));
            money(records, Kind.FILL, fill.fillId(), instrument, fill.timestamp(), fill.side().equals("BUY") ? notional.negate() : notional, fill.feeInr());
        }
        for (var settlement : result.settlements()) {
            String settlementId = "SETTLEMENT:" + instrument + ":" + settlement.settlementDate();
            BigDecimal proceeds = settlement.exitValuePerUnit().multiply(BigDecimal.valueOf(settlement.positionUnitsSettled()));
            records.add(new Expected(Kind.SETTLEMENT, settlementId, fields("event_time_utc", settlement.settlementTimestamp(),
                    "instrument_id", instrument, "component", last.contractKind() == ContractKind.FUTURE ? "CASH_FUTURES_FINAL_PRICE" : "CASH_OPTION_PAYOFF",
                    "quantity_units", settlement.positionUnitsSettled(), "price_inr", settlement.exitValuePerUnit(), "amount_inr", proceeds)));
            boolean modeled = result.settlementChargeBreakdowns().stream().anyMatch(c -> c.settlementTimestamp().equals(settlement.settlementTimestamp()) && !c.model().equals("NOT_MODELED"));
            money(records, Kind.SETTLEMENT, settlementId, instrument, settlement.settlementTimestamp(), proceeds,
                    modeled ? settlement.settlementFeesInr() : null);
        }
        positions(records, instrument, start, end, result.finalPositionUnits());
        return new VerifiedReplay(id, start, end, records, data.completeSessionCount() == data.openSessionCount());
    }

    static void positions(List<Expected> records, String instrument, Instant start, Instant end, long units) {
        records.add(new Expected(Kind.POSITION, "OPENING:" + instrument, fields("event_time_utc", start, "instrument_id", instrument, "checkpoint", "OPENING", "quantity_units", 0)));
        records.add(new Expected(Kind.POSITION, "CLOSING:" + instrument, fields("event_time_utc", end, "instrument_id", instrument, "checkpoint", "CLOSING", "quantity_units", units)));
    }
    static void money(List<Expected> records, Kind targetKind, String target, String instrument, Instant timestamp,
                      BigDecimal proceeds, BigDecimal fee) {
        records.add(new Expected(Kind.FEE, target + ":TOTAL", fields("event_time_utc", timestamp, "instrument_id", instrument,
                "related_type", targetKind, "related_id", target, "component", "TOTAL", "amount_inr", fee)));
        boolean fill = targetKind == Kind.FILL;
        String component = fill ? "TRADE_NOTIONAL" : "SETTLEMENT_PROCEEDS";
        records.add(new Expected(Kind.CASH, target + ":" + component, fields("event_time_utc", timestamp, "instrument_id", instrument,
                "related_type", targetKind, "related_id", target, "component", component, "amount_inr", proceeds)));
        String feeComponent = fill ? "FEE" : "SETTLEMENT_FEE";
        records.add(new Expected(Kind.CASH, target + ":" + feeComponent, fields("event_time_utc", timestamp, "instrument_id", instrument,
                "related_type", targetKind, "related_id", target, "component", feeComponent, "amount_inr", fee == null ? null : fee.negate())));
    }

    static String buildHash() throws IOException {
        Path location;
        try { location = Path.of(ExternalLedgerAudit.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Exception invalid) { throw new IOException("Cannot identify audit build", invalid); }
        if (Files.isRegularFile(location, LinkOption.NOFOLLOW_LINKS)) return hash(readFile(location));
        StringBuilder out = new StringBuilder("INTRA_EXTERNAL_LEDGER_CLASS_BUILD_V1\n");
        try (var paths = Files.walk(location)) {
            List<Path> classes = paths.filter(p -> p.toString().endsWith(".class"))
                    .sorted(Comparator.comparing(p -> location.relativize(p).toString().replace('\\', '/'))).toList();
            if (classes.isEmpty()) throw new IOException("Audit class build is empty");
            for (Path path : classes) out.append(location.relativize(path).toString().replace('\\', '/')).append('=')
                    .append(hash(readFile(path))).append('\n');
        }
        return hash(utf8(out.toString()));
    }
}
