package com.mscient.intrapaper.evaluation.portfolio;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.simulation.portfolio.PortfolioSettlementArtifacts;
import com.mscient.intrapaper.simulation.portfolio.PortfolioSettlementContract;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Structural settlement linkage only. Never interprets settlement values, P&L, rates, or charges from holdout. */
final class PortfolioSettlementCheckpoints {
    record Event(String id, String instrument, Instant timestamp, long signedUnits, boolean exercise) { }
    private PortfolioSettlementCheckpoints() { }

    static Map<Instant, List<Event>> read(Path folder, PortfolioTrialIdentity.FrozenRun run,
            Map<String, Map<String, String>> datasets, LocalDate finalDate, ZoneId zone) throws IOException {
        try { return readRows(folder, run, datasets, finalDate, zone); }
        catch (RuntimeException invalid) { throw new IOException("Invalid settlement checkpoint metadata", invalid); }
    }

    private static Map<Instant, List<Event>> readRows(Path folder, PortfolioTrialIdentity.FrozenRun run,
            Map<String, Map<String, String>> datasets, LocalDate finalDate, ZoneId zone) throws IOException {
        var manifest = run.manifest();
        var targets = new TreeSet<String>();
        if (!manifest.get("settlement_instruments").isEmpty()) targets.addAll(List.of(manifest.get("settlement_instruments").split(",", -1)));
        check(datasets.keySet().containsAll(targets), "Unknown settlement target");
        boolean enabled = !targets.isEmpty();
        for (var data : datasets.values()) {
            check(finalDate.toString().equals(data.get("final_session_date")), "Dataset final session differs from declared artifact scope");
            if (enabled) check("true".equals(data.get("final_session_complete")), "Incomplete settlement session");
            if (finalDate.toString().equals(data.get("expiry"))) check(targets.contains(data.get("instrument_id")), "Missing expiry settlement");
        }
        CsvTable settlements = table(folder, run, "settlements.csv", PortfolioSettlementArtifacts.SETTLEMENT_HEADERS);
        check(settlements.rows().size() == count(manifest.get("settlement_count")) && settlements.rows().size() == targets.size(), "Settlement count mismatch");
        var events = new HashMap<String, Event>(); var used = new TreeSet<String>();
        var groups = new TreeMap<Instant, List<Event>>();
        Event previous = null;
        String costId = PortfolioTrialIdentity.decode(manifest.get("settlement_cost_schedule_id_base64"));
        for (var row : settlements.rows()) {
            var contract = PortfolioSettlementContract.parse(row);
            var data = datasets.get(contract.instrumentId());
            check(data != null && contract.equals(PortfolioSettlementContract.parse(data)), "Settlement contract mismatch");
            Instant at = Instant.parse(row.get("settlement_timestamp"));
            check(at.toString().equals(row.get("settlement_timestamp")) && at.atZone(zone).toLocalDate().equals(finalDate)
                    && finalDate.toString().equals(row.get("settlement_date")) && !finalDate.isAfter(contract.expiry())
                    && !at.isBefore(Instant.parse(data.get("final_bar_close_timestamp_utc"))), "Settlement date/time mismatch");
            long units = Long.parseLong(row.get("signed_units_settled"));
            check(units != Long.MIN_VALUE && Long.toString(units).equals(row.get("signed_units_settled"))
                    && units % contract.lotSize() == 0, "Invalid settlement quantity");
            String id = row.get("event_id");
            check(id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") && used.add(contract.instrumentId()), "Duplicate/invalid settlement identity");
            check(List.of("true", "false").contains(row.get("option_exercise")), "Invalid exercise declaration");
            boolean exercise = Boolean.parseBoolean(row.get("option_exercise"));
            check(!exercise || contract.contractKind() == com.mscient.intrapaper.reference.ContractKind.OPTION, "Future exercise declaration");
            Event event = new Event(id, contract.instrumentId(), at, units, exercise);
            check(events.putIfAbsent(id, event) == null, "Duplicate settlement event");
            check(previous == null || at.isAfter(previous.timestamp())
                    || (at.equals(previous.timestamp()) && event.instrument().compareTo(previous.instrument()) > 0), "Settlement order mismatch");
            previous = event;
            check(manifest.get("settlement_events_source_file_sha256").equals(row.get("event_source_file_sha256")), "Settlement provenance mismatch");
            check((costId.isEmpty() ? "NOT_MODELED" : "DATED_SCHEDULE").equals(row.get("cost_model")), "Settlement cost model mismatch");
            source(row.get("source_reference"));
            groups.computeIfAbsent(at, ignored -> new ArrayList<>()).add(event);
        }
        check(used.equals(targets), "Settlement target coverage mismatch");
        CsvTable charges = table(folder, run, "settlement-cost-lines.csv", PortfolioSettlementArtifacts.COST_HEADERS);
        check(charges.rows().size() == count(manifest.get("settlement_cost_line_count")), "Settlement charge count mismatch");
        var components = new HashSet<String>();
        for (var row : charges.rows()) {
            Event event = events.get(row.get("event_id"));
            check(event != null && event.signedUnits() != 0 && !costId.isEmpty(), "Orphan/flat settlement charge");
            check(costId.equals(row.get("schedule_id")) && manifest.get("settlement_cost_schedule_source_file_sha256")
                    .equals(row.get("schedule_source_file_sha256")), "Settlement charge provenance mismatch");
            check(PortfolioSettlementContract.parse(row).equals(PortfolioSettlementContract.parse(datasets.get(event.instrument()))), "Settlement charge contract mismatch");
            check((event.signedUnits() > 0 ? "LONG" : "SHORT").equals(row.get("position_side")), "Settlement charge side mismatch");
            check(!LocalDate.parse(row.get("effective_from")).isAfter(finalDate)
                    && (row.get("effective_until").isEmpty() || finalDate.isBefore(LocalDate.parse(row.get("effective_until")))), "Settlement charge date mismatch");
            String category = row.get("charge_category");
            check(category.equals("EXPIRY_SETTLEMENT") || (category.equals("OPTION_EXERCISE") && event.exercise()), "Settlement charge category mismatch");
            check(components.add(event.id() + "/" + category + "/" + row.get("component_code")), "Duplicate settlement charge component");
            source(row.get("source_reference"));
        }
        return Collections.unmodifiableMap(groups);
    }
    private static CsvTable table(Path folder, PortfolioTrialIdentity.FrozenRun run, String name, List<String> headers) throws IOException {
        CsvTable table = CsvTable.read(folder.resolve(name));
        PortfolioTrialIdentity.requireEqual(run.verification().outputSha256().get(name), table.sha256(), name + " read hash");
        check(table.headers().equals(headers), "Unexpected " + name + " schema"); return table;
    }
    private static int count(String value) throws IOException {
        int count = Integer.parseInt(value); check(count >= 0 && Integer.toString(count).equals(value), "Invalid settlement count"); return count;
    }
    private static void source(String value) throws IOException { check(value != null && !value.isBlank() && value.equals(value.strip()), "Invalid settlement source"); }
    private static void check(boolean value, String message) throws IOException { if (!value) throw new IOException(message); }
}
