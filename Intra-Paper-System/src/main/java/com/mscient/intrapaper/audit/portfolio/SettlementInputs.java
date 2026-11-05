package com.mscient.intrapaper.audit.portfolio;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.provenance.SettlementCostSchedule;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Frozen, strict caller-supplied cash events and compatible dated charge terms. */
final class SettlementInputs {
    static final String EVENTS_HEADER = "instrument_id,expiry,settlement_date,settlement_method,settlement_value_per_unit_inr,source_id,source_reference";
    static final String COST_HEADER = "effective_from,effective_through,contract_kind,component_code,basis,rate,gst_bps,gst_on_charge,source_reference";
    enum Method { CASH_FUTURES_FINAL_PRICE, CASH_OPTION_PAYOFF }
    record Event(String instrumentId, LocalDate expiry, LocalDate date, Method method,
                 BigDecimal value, String sourceId, String sourceReference) { }
    final Map<String, Event> events;
    final byte[] eventBytes;
    final byte[] costBytes;
    final SettlementCostSchedule costs;

    private SettlementInputs(Map<String, Event> events, byte[] eventBytes, byte[] costBytes, SettlementCostSchedule costs) {
        this.events = Collections.unmodifiableMap(new TreeMap<>(events));
        this.eventBytes = eventBytes; this.costBytes = costBytes; this.costs = costs;
    }

    static SettlementInputs load(Path eventsPath, Path costsPath) throws IOException {
        byte[] eventBytes = readFile(eventsPath);
        TreeMap<String, Event> events = new TreeMap<>();
        for (var row : rows(eventBytes, EVENTS_HEADER, Set.of())) {
            String id = logicalId(row.get("instrument_id"));
            Method method = Method.valueOf(row.get("settlement_method"));
            BigDecimal value = decimal(row.get("settlement_value_per_unit_inr"));
            if (value.signum() < 0 || (method == Method.CASH_FUTURES_FINAL_PRICE && value.signum() == 0))
                throw new IllegalArgumentException("Futures final price must be positive; option cash payoff must be non-negative");
            Event event = new Event(id, date(row.get("expiry")), date(row.get("settlement_date")), method,
                    value, logicalId(row.get("source_id")), row.get("source_reference"));
            if (events.putIfAbsent(id, event) != null) throw new IllegalArgumentException("Duplicate settlement event: " + id);
        }
        byte[] costBytes = costsPath == null ? null : readFile(costsPath);
        SettlementCostSchedule costs = null;
        if (costBytes != null) {
            for (var row : rows(costBytes, COST_HEADER, Set.of("effective_through"))) {
                date(row.get("effective_from"));
                if (!row.get("effective_through").isEmpty()) date(row.get("effective_through"));
                decimal(row.get("rate")); decimal(row.get("gst_bps"));
                if (!List.of("true", "false").contains(row.get("gst_on_charge")))
                    throw new IllegalArgumentException("gst_on_charge must be exactly true or false");
                if (!List.of("FIXED_PER_SETTLEMENT", "PER_SETTLED_UNIT", "BPS_OF_SETTLEMENT_NOTIONAL").contains(row.get("basis")))
                    throw new IllegalArgumentException("Exercise/intrinsic cost bases are not supported by a caller cash-payoff audit");
            }
            costs = SettlementCostSchedule.load(costsPath);
            if (!hash(costBytes).equals(costs.sha256())) throw new IOException("Settlement cost input changed while loading");
        }
        return new SettlementInputs(events, eventBytes, costBytes, costs);
    }

    void unchanged(Path eventsPath, Path costsPath) throws IOException {
        if (!Arrays.equals(eventBytes, readFile(eventsPath))
                || (costBytes != null && !Arrays.equals(costBytes, readFile(costsPath))))
            throw new IOException("Settlement inputs changed during audit");
    }

    static List<Map<String, String>> rows(byte[] bytes, String header, Set<String> optional) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().equals(header)) throw new IllegalArgumentException("Exact CSV header required: " + header);
        List<String> columns = List.of(header.split(","));
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> cells = CsvTable.parseLine(lines.get(i));
            if (cells.size() != columns.size()) throw new IllegalArgumentException("Malformed CSV row " + (i + 1));
            Map<String, String> row = new TreeMap<>();
            for (int j = 0; j < columns.size(); j++) {
                String value = cells.get(j), key = columns.get(j);
                if (!value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)
                        || (!optional.contains(key) && value.isBlank()))
                    throw new IllegalArgumentException("Empty, untrimmed or control-containing " + key + " on row " + (i + 1));
                row.put(key, value);
            }
            rows.add(row);
        }
        return rows;
    }

    static LocalDate date(String text) {
        if (!text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("ISO yyyy-MM-dd date required");
        try { return LocalDate.parse(text); }
        catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("Invalid ISO settlement date: " + text, invalid); }
    }
    static String logicalId(String id) {
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) throw new IllegalArgumentException("Invalid logical ID: " + id);
        return id;
    }
    static BigDecimal decimal(String text) {
        if (text.length() > 64 || !text.matches("-?[0-9]+(?:\\.[0-9]{1,12})?"))
            throw new IllegalArgumentException("Plain decimal with at most 12 fractional places required");
        return checked(new BigDecimal(text));
    }
    // Bound both magnitude and scale before formatting; reject rather than silently rounding P&L.
    static BigDecimal checked(BigDecimal value) {
        if (value == null || value.scale() < -30 || value.scale() > 24 || value.precision() > 64
                || value.abs().compareTo(BigDecimal.TEN.pow(30)) >= 0)
            throw new IllegalArgumentException("Missing or unsupported decimal magnitude/scale (overflow)");
        return value;
    }
    static byte[] readFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            throw new IOException("Input must be a regular non-symlink file: " + path);
        return Files.readAllBytes(path);
    }
    static String hash(byte[] bytes) { return RunConfig.sha256(bytes); }
}
