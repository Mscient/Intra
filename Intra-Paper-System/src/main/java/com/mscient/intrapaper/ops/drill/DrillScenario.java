package com.mscient.intrapaper.ops.drill;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.riskgateway.RiskGateway.*;
import com.mscient.intrapaper.simulation.portfolio.risk.PortfolioRiskInputs;
import com.mscient.intrapaper.simulation.portfolio.risk.PortfolioRiskInputs.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Strict, bounded, local synthetic inputs. No paths, commands, URLs or credentials in the grammar. */
public final class DrillScenario {
    public static final String MAGIC = "OFFLINE_INCIDENT_DRILL_V1";
    public static final String HEADER = "sequence,event_id,time_utc,action,order_id,side,quantity,price,position,reservations,actor,reference,expected_state,expected_result";
    public static final String INSTRUMENT = "SYNTH-1";
    public static final int MAX_BYTES = 1024 * 1024;
    public enum Action { FRAME, FEED_LOSS, STALE_MARK, PROPOSE, LIMIT_BREACH, ACK_MISSED,
        UNCERTAIN_ORDER, RETRY, FILL_ACK, CANCEL_ACK, CHECKPOINT, CORRUPT_CHECKPOINT,
        RESTART, RECOVER_TRUSTED, RECONCILE, RESET }
    public enum State { RUNNING, SAFE_HALT, RECONCILED }
    public enum Outcome { FRAME_APPLIED, FEED_LOSS_BLOCKED, STALE_MARK_BLOCKED, ACCEPTED,
        RISK_REJECTED, LIMIT_BLOCKED, HALTED, RETRY_ACCEPTED, RETRY_REJECTED, RETRY_UNKNOWN,
        FILL_APPLIED, CANCEL_APPLIED, ACK_REJECTED, CHECKPOINT_SAVED, CHECKPOINT_CORRUPTED,
        CHECKPOINT_REJECTED, RESTARTED_HALTED, RECONCILED, RECONCILIATION_REJECTED,
        RESET_AUTHORIZED, RESET_DENIED, BLOCKED_SAFE_HALT, DUPLICATE, EVENT_ID_CONFLICT,
        ACTION_REJECTED }
    public record Event(int sequence, String id, Instant at, Action action, Map<String, String> fields,
                        State expectedState, Outcome expectedResult) {
        public Event { fields = Collections.unmodifiableMap(new TreeMap<>(fields)); }
        public String get(String key) { return fields.get(key); }
        public long number(String key) { return Long.parseLong(get(key)); }
        public BigDecimal price() { return new BigDecimal(get("price")); }
        // Sequence and expectations describe delivery/testing, not event identity.
        public String payload() { return id + "|" + at + "|" + action + "|" + fields; }
    }
    private final byte[] bytes;
    private final Map<String, String> settings;
    private final List<Event> events;
    private final PortfolioRiskInputs inputs;

    private DrillScenario(byte[] bytes, Map<String, String> settings, List<Event> events) {
        this.bytes = bytes.clone(); this.settings = Map.copyOf(settings); this.events = List.copyOf(events);
        var config = new RiskConfiguration("INR", Map.of(INSTRUMENT,
                new InstrumentRiskSpec(INSTRUMENT, "INR", BigDecimal.ONE, Long.parseLong(settings.get("max_abs_contracts")))),
                new BigDecimal(settings.get("max_gross_notional")), new BigDecimal(settings.get("max_margin_proxy")),
                new BigDecimal(settings.get("max_daily_loss")), Duration.ofSeconds(Long.parseLong(settings.get("maximum_age_seconds"))));
        Map<Instant, FrameInputs> frames = new TreeMap<>();
        Map<Instant, String> frameIdentities = new HashMap<>();
        for (Event e : events) if (Set.of(Action.FRAME, Action.STALE_MARK, Action.FEED_LOSS).contains(e.action())) {
            String prior = frameIdentities.putIfAbsent(e.at(), e.payload());
            if (prior != null && !prior.equals(e.payload())) throw invalid("Each distinct frame requires a strictly later timestamp");
        }
        for (Event e : events) if (e.action() == Action.FRAME || e.action() == Action.STALE_MARK) {
            Instant asOf = e.action() == Action.STALE_MARK ? e.at().minus(config.maximumInputAge()).minusSeconds(1) : e.at();
            var margin = new MarginInput(INSTRUMENT, new BigDecimal(settings.get("margin_per_contract")),
                    "SYNTHETIC", "DRILL_ONLY", session(), e.at());
            var sample = new Sample(Optional.of(e.price()), Optional.of(asOf), "SYNTHETIC", "DRILL_ONLY",
                    Optional.of(margin), Optional.of(e.price()), Optional.of(e.price()));
            FrameInputs frame = new FrameInputs(e.at(), session(), Map.of(INSTRUMENT, sample));
            FrameInputs prior = frames.putIfAbsent(e.at(), frame);
            if (prior != null && !prior.equals(frame)) throw invalid("Conflicting frame inputs at " + e.at());
        }
        inputs = new PortfolioRiskInputs(config, Map.of(INSTRUMENT, 1), frames,
                Map.of("synthetic_scenario", DrillEvidence.sha(bytes)));
    }
    public byte[] bytes() { return bytes.clone(); }
    public String id() { return settings.get("scenario_id"); }
    public String operator() { return settings.get("authorized_operator"); }
    public String session() { return "SYNTHETIC_DRILL"; }
    public List<Event> events() { return events; }
    public PortfolioRiskInputs inputs() { return inputs; }
    public static DrillScenario load(Path file) throws IOException {
        if (Files.size(file) > MAX_BYTES) throw invalid("Scenario exceeds 1 MiB");
        try (var in = Files.newInputStream(file)) { return parse(in.readNBytes(MAX_BYTES + 1)); }
    }
    public static DrillScenario parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw invalid("Scenario exceeds 1 MiB");
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { throw invalid("Invalid UTF-8"); }
        if (!text.endsWith("\n") || text.indexOf('\r') >= 0 || text.indexOf('\0') >= 0)
            throw invalid("Use LF-terminated UTF-8 without CR or NUL");
        String[] lines = text.split("\n", -1);
        List<String> keys = List.of("scenario_id", "authorized_operator", "max_abs_contracts", "max_gross_notional",
                "max_margin_proxy", "max_daily_loss", "maximum_age_seconds", "margin_per_contract");
        if (lines.length < keys.size() + 4 || !lines[0].equals(MAGIC)) throw invalid("Missing version/settings/events");
        Map<String, String> settings = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            String prefix = keys.get(i) + "=";
            if (!lines[i + 1].startsWith(prefix)) throw invalid("Expected exactly " + prefix);
            String value = lines[i + 1].substring(prefix.length());
            if (i < 2) identifier(value); else if (i == 2 || i == 6) positiveInteger(value); else positiveDecimal(value);
            settings.put(keys.get(i), value);
        }
        int header = keys.size() + 1;
        if (!lines[header].equals(HEADER)) throw invalid("Unexpected event header");
        String[] columns = HEADER.split(",");
        List<Event> events = new ArrayList<>(); Instant last = null;
        for (int n = header + 1; n < lines.length - 1; n++) {
            if (events.size() == 2000) throw invalid("At most 2000 deliveries");
            List<String> values = CsvTable.parseLine(lines[n]);
            if (values.size() != columns.length) throw invalid("Wrong field count at line " + (n + 1));
            for (String value : values) if (value.length() > 4096 || !value.equals(value.strip())
                    || value.chars().anyMatch(c -> c < 32 || c > 126)) throw invalid("Noncanonical event field");
            if (!values.get(0).equals(Integer.toString(events.size() + 1))) throw invalid("Sequence must start at one without gaps");
            identifier(values.get(1));
            Instant at = Instant.parse(values.get(2));
            if (!at.toString().equals(values.get(2)) || (last != null && at.isBefore(last)))
                throw invalid("Canonical UTC timestamps must be nondecreasing");
            last = at;
            Action action = Action.valueOf(values.get(3));
            Map<String, String> fields = new TreeMap<>();
            for (int c = 4; c < 12; c++) fields.put(columns[c], values.get(c));
            Set<String> required = switch (action) {
                case FRAME, STALE_MARK -> Set.of("price");
                case PROPOSE, LIMIT_BREACH -> Set.of("order_id", "side", "quantity");
                case ACK_MISSED, UNCERTAIN_ORDER, RETRY, CANCEL_ACK -> Set.of("order_id");
                case FILL_ACK -> Set.of("order_id", "quantity", "price");
                case CHECKPOINT, CORRUPT_CHECKPOINT, RESTART, RECOVER_TRUSTED -> Set.of("reference");
                case RECONCILE -> Set.of("position", "reservations");
                case RESET -> Set.of("actor", "reference");
                case FEED_LOSS -> Set.of();
            };
            fields.forEach((key, value) -> {
                if (required.contains(key) == value.isEmpty()) throw invalid("Incorrect fields for " + action + ": " + key);
                if (value.isEmpty()) return;
                switch (key) {
                    case "order_id", "actor", "reference" -> identifier(value);
                    case "side" -> { if (!Set.of("BUY", "SELL").contains(value)) throw invalid("Invalid side"); }
                    case "quantity" -> positiveInteger(value);
                    case "price" -> positiveDecimal(value);
                    case "position" -> signedInteger(value);
                    case "reservations" -> reservations(value);
                    default -> throw invalid("Unknown column");
                }
            });
            events.add(new Event(events.size() + 1, values.get(1), at, action, fields,
                    State.valueOf(values.get(12)), Outcome.valueOf(values.get(13))));
        }
        if (events.isEmpty() || events.getFirst().action() != Action.FRAME) throw invalid("First event must be FRAME");
        return new DrillScenario(bytes, settings, events);
    }
    public static Map<String, Long> reservations(String value) {
        Map<String, Long> map = new TreeMap<>();
        if (value.equals("-")) return map;
        String last = "";
        for (String entry : value.split(";", -1)) {
            String[] pair = entry.split(":", -1);
            if (pair.length != 2) throw invalid("Reservations use sorted order-id:positive-quantity pairs or -");
            identifier(pair[0]); positiveInteger(pair[1]);
            if (pair[0].compareTo(last) <= 0) throw invalid("Unsorted/duplicate reservation");
            map.put(pair[0], Long.parseLong(pair[1])); last = pair[0];
        }
        return map;
    }
    private static void identifier(String s) {
        if (!s.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) throw invalid("Invalid identifier");
    }
    private static void positiveInteger(String s) {
        if (!s.matches("[1-9][0-9]{0,8}")) throw invalid("Positive integer outside 1..999999999");
    }
    private static void signedInteger(String s) {
        if (!s.matches("0|-?[1-9][0-9]{0,8}")) throw invalid("Invalid signed quantity");
    }
    private static void positiveDecimal(String s) {
        if (!s.matches("(0|[1-9][0-9]{0,11})(\\.[0-9]{1,6})?") || new BigDecimal(s).signum() <= 0)
            throw invalid("Invalid positive decimal (12 integer/6 fractional digits maximum)");
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
