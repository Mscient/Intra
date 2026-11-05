package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.CsvTable;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import static com.mscient.intrapaper.simulation.portfolio.PortfolioSettlementContract.*;

/** Independent arithmetic reconstruction from artifact rows, without invoking the live ledger or fee calculator. */
final class PortfolioSettlementArtifactVerifier {
    private PortfolioSettlementArtifactVerifier() { }
    private record Dataset(PortfolioSettlementContract contract, LocalDate date, Instant close, ZoneId zone,
            boolean complete, int bars) { }
    private record Execution(long sequence, Instant timestamp, String instrument, long signedUnits, BigDecimal price, BigDecimal fee) { }
    private static final class Position {
        long units;
        BigDecimal basis = BigDecimal.ZERO, realized = BigDecimal.ZERO, fees = BigDecimal.ZERO;
        void execute(Execution e) {
            long next = Math.addExact(units, e.signedUnits()); Math.absExact(next);
            long quantity = Math.absExact(e.signedUnits());
            if (units == 0 || Long.signum(units) == Long.signum(e.signedUnits())) {
                basis = basis.add(e.price().multiply(BigDecimal.valueOf(quantity)));
            } else {
                long closed = Math.min(Math.absExact(units), quantity);
                BigDecimal released = closed == Math.absExact(units) ? basis : basis.multiply(BigDecimal.valueOf(closed))
                        .divide(BigDecimal.valueOf(Math.absExact(units)), 18, RoundingMode.HALF_UP);
                realized = realized.add(e.price().multiply(BigDecimal.valueOf(closed)).subtract(released)
                        .multiply(BigDecimal.valueOf(Long.signum(units))));
                basis = basis.subtract(released);
                if (next == 0) basis = BigDecimal.ZERO;
                else if (Long.signum(next) != Long.signum(units)) basis = e.price().multiply(BigDecimal.valueOf(Math.absExact(next)));
            }
            units = next; fees = fees.add(e.fee());
        }
    }

    static void verify(Path root, Map<String, String> manifest) throws IOException {
        try { verifyRows(root, manifest); }
        catch (RuntimeException e) { throw new IOException("Invalid portfolio settlement/accounting artifact: " + e.getMessage(), e); }
    }
    private static void verifyRows(Path root, Map<String, String> m) throws IOException {
        var policy = PortfolioSettlementPolicy.valueOf(m.get("settlement_policy"));
        Set<String> targets = new TreeSet<>();
        if (!m.get("settlement_instruments").isEmpty()) for (String target : m.get("settlement_instruments").split(",", -1)) {
            id(target, "settlement target"); check(targets.add(target), "Duplicate settlement target");
        }
        check(String.join(",", targets).equals(m.get("settlement_instruments")), "Settlement targets are not canonical");
        boolean enabled = policy != PortfolioSettlementPolicy.NONE;
        check(enabled != targets.isEmpty(), "Settlement policy/targets mismatch");
        if (enabled) hash(m.get("settlement_events_source_file_sha256"));
        else check("NONE".equals(m.get("settlement_events_source_file_sha256")), "Disabled event hash must be NONE");
        String costId = decoded(m.get("settlement_cost_schedule_id_base64"));
        boolean costs = !costId.isEmpty();
        if (costs) { id(costId, "settlement schedule ID"); hash(m.get("settlement_cost_schedule_source_file_sha256")); check(enabled, "Costs without events"); }
        else check("NONE".equals(m.get("settlement_cost_schedule_source_file_sha256")), "Disabled cost hash must be NONE");
        TreeMap<String, String> limits = decodeMap(m.get("max_abs_lots_by_instrument_base64"));
        limits.forEach((key, value) -> { id(key, "instrument"); check(Integer.parseInt(value) > 0, "Invalid lot limit"); });
        check(limits.keySet().containsAll(targets), "Settlement targets outside portfolio");
        String replay = PortfolioReplayEngine.hashStrings("INTRA_PORTFOLIO_REPLAY_V3", List.of(m.get("build_commit"),
                m.get("validated_datasets_sha256"), m.get("frame_inputs_sha256"), m.get("strategy_fingerprint_sha256"),
                m.get("fill_model_fingerprint_sha256"), Boolean.parseBoolean(m.get("execution_cost_schedule_enabled"))
                        ? decoded(m.get("execution_cost_schedule_id_base64")) : "NO_EXECUTION_COST_SCHEDULE",
                Boolean.parseBoolean(m.get("execution_cost_schedule_enabled")) ? m.get("execution_cost_schedule_source_file_sha256") : "NO_EXECUTION_COST_SCHEDULE",
                limits.toString(), m.get("pending_intent_boundary_policy"), m.get("position_boundary_policy"),
                m.get("require_contiguous_frames_within_session"), policy.name(), String.join(",", targets),
                m.get("settlement_events_source_file_sha256"), costs ? costId : "NONE", m.get("settlement_cost_schedule_source_file_sha256")));
        check(replay.equals(m.get("run_id")), "Replay fingerprint does not bind manifest inputs/policies");

        var datasetHeaders = PortfolioSettlementArtifacts.concat(List.of("instrument_id", "dataset_id", "source_name", "acquired_at_utc", "timezone",
                "data_rights_status", "data_rights_reference", "bars_sha256", "config_sha256", "calendar_sha256", "instrument_master_snapshot_id",
                "instrument_master_sha256", "market_rule_sha256", "input_file_hashes_base64", "validated_bar_count", "complete_session_count"), PortfolioSettlementArtifacts.DATASET_HEADERS);
        CsvTable datasetTable = table(root, "datasets.csv", datasetHeaders);
        Map<String, Dataset> datasets = new TreeMap<>();
        for (var row : datasetTable.rows()) {
            var identity = PortfolioSettlementContract.parse(row);
            Dataset d = new Dataset(identity, LocalDate.parse(required(row, "final_session_date")),
                    Instant.parse(required(row, "final_bar_close_timestamp_utc")), ZoneId.of(required(row, "timezone")),
                    bool(row, "final_session_complete"), Integer.parseInt(required(row, "validated_bar_count")));
            check(d.bars() > 0 && !d.date().isAfter(identity.expiry()), "Invalid dataset boundary");
            check(d.close().atZone(d.zone()).toLocalDate().equals(d.date()), "Dataset close/date mismatch");
            check(datasets.putIfAbsent(identity.instrumentId(), d) == null, "Duplicate dataset identity");
            if (enabled) check(d.complete(), "Settlement requires complete final session");
            if (d.date().equals(identity.expiry())) check(targets.contains(identity.instrumentId()), "Missing expiry settlement target");
        }
        check(datasets.size() >= 2 && datasets.keySet().equals(limits.keySet()), "Dataset/portfolio universe mismatch");
        Dataset firstDataset = datasets.values().iterator().next();
        for (Dataset d : datasets.values()) check(d.bars() == firstDataset.bars() && d.close().equals(firstDataset.close())
                && d.date().equals(firstDataset.date()), "Unsynchronized dataset boundary");
        CsvTable settlementTable = table(root, "settlements.csv", PortfolioSettlementArtifacts.SETTLEMENT_HEADERS);
        check(settlementTable.rows().size() == count(m, "settlement_count") && settlementTable.rows().size() == targets.size(), "Settlement count mismatch");
        var events = new LinkedHashMap<String, PortfolioSettlementEvents.Event>();
        Set<String> eventInstruments = new TreeSet<>();
        for (var row : settlementTable.rows()) {
            var event = PortfolioSettlementEvents.Event.parse(row); Dataset d = datasets.get(event.contract().instrumentId());
            check(d != null && d.contract().equals(event.contract()), "Settlement/dataset identity mismatch");
            check(event.settlementDate().equals(d.date()) && !event.timestamp().isBefore(d.close())
                    && event.timestamp().atZone(d.zone()).toLocalDate().equals(d.date()), "Settlement date/timestamp mismatch");
            check(m.get("settlement_events_source_file_sha256").equals(row.get("event_source_file_sha256")), "Event provenance mismatch");
            check(events.putIfAbsent(event.eventId(), event) == null && eventInstruments.add(event.contract().instrumentId()), "Duplicate event");
        }
        check(eventInstruments.equals(targets), "Settlement coverage mismatch");
        List<PortfolioSettlementEvents.Event> orderedEvents = List.copyOf(events.values());
        if (!orderedEvents.isEmpty()) check(new PortfolioSettlementEvents(orderedEvents, m.get("settlement_events_source_file_sha256"))
                .events().equals(orderedEvents), "Settlement events not canonically ordered");
        CsvTable chargeTable = table(root, "settlement-cost-lines.csv", PortfolioSettlementArtifacts.COST_HEADERS);
        check(chargeTable.rows().size() == count(m, "settlement_cost_line_count"), "Charge count mismatch");
        Map<String, List<Map<String, String>>> charges = new HashMap<>();
        int previousEventIndex = -1;
        for (var row : chargeTable.rows()) {
            String eventId = required(row, "event_id"); check(events.containsKey(eventId), "Orphan settlement charge");
            int index = orderedEvents.indexOf(events.get(eventId)); check(index >= previousEventIndex, "Charge event order mismatch"); previousEventIndex = index;
            check(costs && costId.equals(row.get("schedule_id")) && m.get("settlement_cost_schedule_source_file_sha256")
                    .equals(row.get("schedule_source_file_sha256")), "Charge schedule provenance mismatch");
            charges.computeIfAbsent(eventId, ignored -> new ArrayList<>()).add(row);
        }
        var settlements = new ArrayList<PortfolioSettlement>();
        for (var row : settlementTable.rows()) {
            var event = events.get(row.get("event_id")); long units = Long.parseLong(required(row, "signed_units_settled"));
            var lines = verifyCharges(event, units, costId, m.get("settlement_cost_schedule_source_file_sha256"), charges.getOrDefault(event.eventId(), List.of()));
            check((costs ? "DATED_SCHEDULE" : "NOT_MODELED").equals(row.get("cost_model")), "Settlement cost model mismatch");
            var s = new PortfolioSettlement(event, units, decimal(row, "released_basis_inr"), decimal(row, "signed_proceeds_inr"),
                    decimal(row, "realized_gross_pnl_inr"), row.get("cost_model"), lines);
            equal(s.feesInr(), decimal(row, "total_fee_inr"), "Settlement total fees");
            equal(PortfolioSettlementArtifacts.categoryFees(s, PortfolioSettlementCostSchedule.Category.EXPIRY_SETTLEMENT), decimal(row, "expiry_settlement_fees_inr"), "Expiry fee total");
            equal(PortfolioSettlementArtifacts.categoryFees(s, PortfolioSettlementCostSchedule.Category.OPTION_EXERCISE), decimal(row, "option_exercise_fees_inr"), "Exercise fee total");
            settlements.add(s);
        }
        verifyEquity(root, m, datasets, settlements);
        for (String name : List.of("decisions", "orders")) {
            String countName = name.equals("decisions") ? "decision_count" : "order_count";
            check(CsvTable.read(root.resolve(name + ".csv")).rows().size() == count(m, countName), name + " count mismatch");
        }
    }

    private static List<PortfolioSettlementCostSchedule.Line> verifyCharges(PortfolioSettlementEvents.Event event, long signedUnits,
            String scheduleId, String scheduleHash, List<Map<String, String>> rows) {
        long units = Math.absExact(signedUnits); var result = new ArrayList<PortfolioSettlementCostSchedule.Line>();
        Set<PortfolioSettlementCostSchedule.Category> categories = new HashSet<>(); String previousKey = null;
        for (var row : rows) {
            check(units > 0, "Flat settlement has charges"); var entry = PortfolioSettlementCostSchedule.Entry.parse(row);
            check(entry.contract().equals(event.contract()) && entry.covers(event.settlementDate()), "Charge identity/date mismatch");
            check(entry.side() == (signedUnits > 0 ? PortfolioSettlementCostSchedule.PositionSide.LONG : PortfolioSettlementCostSchedule.PositionSide.SHORT), "Charge position side mismatch");
            check(entry.category() != PortfolioSettlementCostSchedule.Category.OPTION_EXERCISE || event.optionExercise(), "Undeclared exercise charge");
            String key = entry.category().ordinal() + ":" + entry.componentCode();
            check(previousKey == null || key.compareTo(previousKey) > 0, "Duplicate or noncanonical charge component"); previousKey = key;
            categories.add(entry.category());
            BigDecimal base = switch (entry.basis()) {
                case FIXED_PER_SETTLEMENT -> BigDecimal.ONE;
                case PER_SETTLED_UNIT, PER_EXERCISED_OPTION_UNIT -> BigDecimal.valueOf(units);
                case BPS_OF_SETTLEMENT_VALUE -> event.valuePerUnit().multiply(BigDecimal.valueOf(signedUnits)).abs();
                case BPS_OF_CALLER_TURNOVER -> event.chargeTurnoverPerUnit().orElseThrow().multiply(BigDecimal.valueOf(units));
            };
            BigDecimal amount = base.multiply(entry.rate());
            if (entry.basis() == PortfolioSettlementCostSchedule.Basis.BPS_OF_SETTLEMENT_VALUE
                    || entry.basis() == PortfolioSettlementCostSchedule.Basis.BPS_OF_CALLER_TURNOVER) amount = amount.divide(new BigDecimal("10000"));
            amount = amount.setScale(entry.chargeScale(), entry.chargeMode());
            BigDecimal gstBase = entry.gstOnCharge() ? amount : BigDecimal.ZERO;
            BigDecimal gst = gstBase.multiply(entry.gstBps()).divide(new BigDecimal("10000")).setScale(entry.gstScale(), entry.gstMode());
            equal(base, decimal(row, "calculation_base"), "Charge calculation base"); equal(amount, decimal(row, "charge_inr"), "Rounded charge");
            equal(gstBase, decimal(row, "gst_taxable_base_inr"), "GST base"); equal(gst, decimal(row, "gst_inr"), "GST amount");
            equal(amount.add(gst), decimal(row, "total_fee_inr"), "Charge total");
            result.add(new PortfolioSettlementCostSchedule.Line(event.eventId(), scheduleId, scheduleHash, entry, base, amount, gstBase, gst));
        }
        if (units > 0 && !scheduleId.isEmpty()) {
            check(categories.contains(PortfolioSettlementCostSchedule.Category.EXPIRY_SETTLEMENT), "Missing expiry charge coverage");
            if (event.optionExercise()) check(categories.contains(PortfolioSettlementCostSchedule.Category.OPTION_EXERCISE), "Missing exercise charge coverage");
        }
        return List.copyOf(result);
    }

    private static void verifyEquity(Path root, Map<String, String> m, Map<String, Dataset> datasets,
            List<PortfolioSettlement> settlements) throws IOException {
        CsvTable executionTable = table(root, "executions.csv", List.of("execution_id", "sequence", "instrument_id", "timestamp_utc", "side", "quantity_units", "price", "fee_inr"));
        check(executionTable.rows().size() == count(m, "execution_count"), "Execution count mismatch");
        var executions = new ArrayList<Execution>(); long previousSequence = 0; Instant previousExecutionTime = Instant.MIN;
        for (var row : executionTable.rows()) {
            long sequence = Long.parseLong(required(row, "sequence")), quantity = Long.parseLong(required(row, "quantity_units"));
            Instant timestamp = Instant.parse(required(row, "timestamp_utc")); String instrument = required(row, "instrument_id");
            BigDecimal price = decimal(row, "price"), fee = decimal(row, "fee_inr");
            check(sequence > previousSequence && quantity > 0 && !timestamp.isBefore(previousExecutionTime)
                    && datasets.containsKey(instrument) && price.signum() > 0 && fee.signum() >= 0, "Invalid execution sequence/quantity/price");
            var side = PortfolioExecution.Side.valueOf(required(row, "side"));
            executions.add(new Execution(sequence, timestamp, instrument, side == PortfolioExecution.Side.BUY ? quantity : -quantity, price, fee));
            previousSequence = sequence; previousExecutionTime = timestamp;
        }
        CsvTable equity = table(root, "equity.csv", List.of("timestamp_utc", "phase", "instrument_id", "signed_units", "average_open_price",
                "realized_gross_pnl_inr", "fees_inr", "unrealized_pnl_inr", "total_realized_gross_pnl_inr", "total_fees_inr", "total_unrealized_pnl_inr", "net_pnl_inr"));
        int width = datasets.size(), count = count(m, "snapshot_count");
        check(count > 0 && equity.rows().size() == Math.multiplyExact(count, width), "Equity snapshot count mismatch");
        Map<String, Position> positions = new TreeMap<>(); datasets.keySet().forEach(id -> positions.put(id, new Position()));
        Map<String, BigDecimal> previousUnrealized = new HashMap<>();
        int ei = 0, si = 0, opens = 0, closes = 0; String phaseBefore = ""; Instant timeBefore = Instant.MIN;
        Instant finalClose = datasets.values().iterator().next().close();
        for (int offset = 0; offset < equity.rows().size(); offset += width) {
            var first = equity.rows().get(offset); Instant timestamp = Instant.parse(required(first, "timestamp_utc"));
            String phase = required(first, "phase"); check(!timestamp.isBefore(timeBefore), "Equity time reversal");
            Set<String> justSettled = new HashSet<>();
            switch (phase) {
                case "BAR_OPEN" -> {
                    check(phaseBefore.isEmpty() || phaseBefore.equals("BAR_CLOSE"), "Invalid opening phase"); opens++;
                    while (ei < executions.size() && !executions.get(ei).timestamp().isAfter(timestamp)) {
                        Execution e = executions.get(ei++); check(e.timestamp().equals(timestamp), "Execution has no matching opening snapshot");
                        positions.get(e.instrument()).execute(e);
                    }
                }
                case "BAR_CLOSE" -> { check(phaseBefore.equals("BAR_OPEN") && timestamp.isAfter(timeBefore), "Invalid closing phase"); closes++; }
                case "SETTLEMENT" -> {
                    check((phaseBefore.equals("BAR_CLOSE") && timeBefore.equals(finalClose))
                            || (phaseBefore.equals("SETTLEMENT") && timestamp.isAfter(timeBefore)), "Settlement is not after final close");
                    check(ei == executions.size(), "Unapplied executions at settlement");
                    while (si < settlements.size() && !settlements.get(si).event().timestamp().isAfter(timestamp)) {
                        var s = settlements.get(si++); check(s.event().timestamp().equals(timestamp), "Settlement snapshot time mismatch");
                        String id = s.event().contract().instrumentId(); Position state = positions.get(id);
                        check(state.units == s.signedUnits(), "Settlement signed quantity differs from executions");
                        equal(state.basis, s.releasedBasisInr(), "Settlement remaining basis differs from executions");
                        state.realized = state.realized.add(s.realizedGrossPnlInr()); state.fees = state.fees.add(s.feesInr());
                        state.units = 0; state.basis = BigDecimal.ZERO; justSettled.add(id);
                    }
                    check(!justSettled.isEmpty(), "Empty or repeated settlement snapshot");
                }
                default -> throw new IllegalArgumentException("Unknown equity phase");
            }
            var ids = new TreeSet<String>(); BigDecimal totalRealized = BigDecimal.ZERO, totalFees = BigDecimal.ZERO, totalUnrealized = BigDecimal.ZERO;
            for (int i = offset; i < offset + width; i++) {
                var row = equity.rows().get(i); String id = required(row, "instrument_id"); Position state = positions.get(id);
                check(timestamp.toString().equals(row.get("timestamp_utc")) && phase.equals(row.get("phase"))
                        && state != null && ids.add(id), "Malformed equity group");
                check(state.units == Long.parseLong(required(row, "signed_units")), "Equity signed position mismatch");
                equal(state.realized, decimal(row, "realized_gross_pnl_inr"), "Equity realized P&L mismatch");
                equal(state.fees, decimal(row, "fees_inr"), "Equity fee mismatch");
                equal(state.units == 0 ? BigDecimal.ZERO : state.basis.divide(BigDecimal.valueOf(Math.absExact(state.units)), 12, RoundingMode.HALF_UP),
                        decimal(row, "average_open_price"), "Equity basis mismatch");
                BigDecimal unrealized = decimal(row, "unrealized_pnl_inr");
                if (state.units == 0) equal(BigDecimal.ZERO, unrealized, "Flat position unrealized P&L");
                if (phase.equals("SETTLEMENT") && !justSettled.contains(id)) equal(previousUnrealized.get(id), unrealized, "Settlement changed untouched mark");
                previousUnrealized.put(id, unrealized); totalRealized = totalRealized.add(state.realized);
                totalFees = totalFees.add(state.fees); totalUnrealized = totalUnrealized.add(unrealized);
            }
            check(ids.equals(datasets.keySet()), "Equity universe mismatch");
            for (int i = offset; i < offset + width; i++) {
                var row = equity.rows().get(i); equal(totalRealized, decimal(row, "total_realized_gross_pnl_inr"), "Aggregate realized mismatch");
                equal(totalFees, decimal(row, "total_fees_inr"), "Aggregate fees mismatch");
                equal(totalUnrealized, decimal(row, "total_unrealized_pnl_inr"), "Aggregate unrealized mismatch");
                equal(totalRealized.add(totalUnrealized).subtract(totalFees), decimal(row, "net_pnl_inr"), "Net P&L mismatch");
            }
            phaseBefore = phase; timeBefore = timestamp;
        }
        check(ei == executions.size() && si == settlements.size(), "Unreconciled executions or settlements");
        check(opens == datasets.values().iterator().next().bars() && closes == opens, "Bar snapshot count mismatch");
        check(timeBefore.equals(settlements.isEmpty() ? finalClose : settlements.getLast().event().timestamp()), "Final equity timestamp mismatch");
        if (m.get("position_boundary_policy").equals("REQUIRE_FLAT")) check(positions.values().stream().allMatch(p -> p.units == 0), "Final flat policy violated");
    }

    static TreeMap<String, String> decodeMap(String encoded) {
        String value = decoded(encoded); var result = new TreeMap<String, String>(); int offset = 0; String prior = null;
        while (offset < value.length()) {
            int colon = value.indexOf(':', offset), length = Integer.parseInt(value.substring(offset, colon));
            check(length > 0, "Invalid map key length"); String key = value.substring(colon + 1, colon + 1 + length); offset = colon + 1 + length;
            colon = value.indexOf(':', offset); length = Integer.parseInt(value.substring(offset, colon)); check(length >= 0, "Invalid map value length");
            String item = value.substring(colon + 1, colon + 1 + length); offset = colon + 1 + length;
            check(prior == null || prior.compareTo(key) < 0, "Noncanonical map keys"); prior = key; result.put(key, item);
        }
        return result;
    }
    private static CsvTable table(Path root, String name, List<String> headers) throws IOException {
        CsvTable table = CsvTable.read(root.resolve(name)); check(table.headers().equals(headers), "Unexpected " + name + " schema"); return table;
    }
    private static int count(Map<String, String> m, String key) { int value = Integer.parseInt(required(m, key)); check(value >= 0, "Negative " + key); return value; }
    private static String decoded(String value) { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
    private static BigDecimal decimal(Map<String, String> row, String key) { return new BigDecimal(required(row, key)); }
    private static void equal(BigDecimal a, BigDecimal b, String message) { check(a.compareTo(b) == 0, message); }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
