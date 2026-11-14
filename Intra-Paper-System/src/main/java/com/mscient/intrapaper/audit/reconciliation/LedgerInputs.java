package com.mscient.intrapaper.audit.reconciliation;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static com.mscient.intrapaper.audit.reconciliation.LedgerSupport.*;

/** Frozen caller bundle and reference-based correction history; file order never selects a winner. */
final class LedgerInputs {
    static final Set<String> KEYS = Set.of("schema_version", "classification", "replay_kind", "replay_run_id",
            "comparison", "cash_basis", "fee_basis", "currency", "ledger_file", "ledger_sha256",
            "coverage_file", "coverage_sha256", "source_id", "source_reference",
            "matching", "tolerances", "policy_source_reference");
    record Coverage(int line, Kind kind, Instant start, Instant end, String complete, String reference) { }
    record History(Map<String, Event> active, Map<Integer, String> dispositions) { }
    final Map<String, String> metadata = new TreeMap<>();
    final List<Map<String, String>> raw = new ArrayList<>();
    final List<Event> events = new ArrayList<>();
    final List<Coverage> coverage = new ArrayList<>();
    final List<Finding> findings;

    private LedgerInputs(List<Finding> findings) { this.findings = findings; }

    static LedgerInputs load(Path manifest, Snapshots snapshots, List<Finding> findings) {
        var result = new LedgerInputs(findings);
        try {
            result.metadata.putAll(properties(snapshots.read(manifest, "external/manifest", "external-manifest", "", "", "")));
            if (!result.metadata.keySet().equals(KEYS)) throw new IllegalArgumentException("Manifest keys must exactly match the documented schema");
            for (String key : KEYS) required(result.metadata, key);
            for (var expected : Map.of("schema_version", "1", "comparison", "EXACT", "currency", "INR",
                    "cash_basis", "SIGNED_NOTIONAL_EXCLUDING_FEES", "tolerances", "NONE").entrySet())
                if (!result.metadata.get(expected.getKey()).equals(expected.getValue()))
                    throw new IllegalArgumentException("Unsupported manifest policy: " + expected.getKey());
            if (!Set.of("DECLARED_IDS", "UNIQUE_COMPOSITE_KEYS").contains(result.metadata.get("matching")))
                throw new IllegalArgumentException("Unsupported matching policy");
            if (!Set.of("TOTAL_PER_EVENT", "ITEMIZED_AND_TOTAL").contains(result.metadata.get("fee_basis")))
                throw new IllegalArgumentException("Unsupported fee basis");
            if (result.itemized() && !result.metadata.get("replay_kind").equals("PORTFOLIO"))
                throw new IllegalArgumentException("Itemized charges require the portfolio adapter");
            if (!Set.of("SYNTHETIC", "CALLER_SUPPLIED_NORMALIZED").contains(result.metadata.get("classification")))
                throw new IllegalArgumentException("Unsupported external classification");
            ExternalLedgerAudit.ReplayKind.valueOf(result.metadata.get("replay_kind"));
            if (!result.metadata.get("replay_run_id").matches("[0-9a-f]{24}|[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid caller replay run ID");
            id(result.metadata.get("source_id"));
            snapshots.read(manifest, "external/manifest", "external-manifest", "",
                    result.metadata.get("source_id"), result.metadata.get("source_reference"));
            result.readLedger(manifest, snapshots);
            result.readCoverage(manifest, snapshots);
        } catch (IOException | RuntimeException invalid) {
            finding(findings, Kind.INPUT, Severity.FAIL, "INVALID_EXTERNAL_MANIFEST", null, "", "manifest", "", "",
                    "External bundle rejected: " + stableMessage(invalid));
        }
        return result;
    }

    private byte[] input(Path manifest, Snapshots snapshots, String role) throws IOException {
        String file = metadata.get(role + "_file"), declared = metadata.get(role + "_sha256");
        if (!declared.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid declared external input SHA-256");
        byte[] bytes = snapshots.read(relativeFile(manifest, file), "external/" + role, file, declared,
                metadata.get("source_id"), metadata.get("source_reference"));
        if (!hash(bytes).equals(declared)) {
            finding(findings, Kind.INPUT, Severity.FAIL, "INPUT_HASH_MISMATCH", null, "", role, declared, hash(bytes),
                    "Caller-declared hash differs from actual bytes; this file was not consumed");
            return null;
        }
        return bytes;
    }

    private void readLedger(Path manifest, Snapshots snapshots) {
        try {
            byte[] bytes = input(manifest, snapshots, "ledger");
            if (bytes == null) return;
            raw.addAll(csv(bytes, HEADERS));
            for (int i = 0; i < raw.size(); i++) {
                Map<String, String> row = raw.get(i);
                Kind kind = Kind.INPUT;
                try {
                    kind = kind(required(row, "record_type"));
                    String record = required(row, "record_id"), entity = required(row, "entity_id");
                    id(record); id(entity);
                    String action = required(row, "action");
                    if (!Set.of("NEW", "CORRECT", "REVERSE").contains(action)) throw new IllegalArgumentException("Invalid event action");
                    String replaces = row.get("replaces_record_id");
                    if (!replaces.isEmpty()) id(replaces);
                    if (action.equals("NEW") != replaces.isEmpty()) throw new IllegalArgumentException("NEW has no replacement reference; updates require one");
                    long sequence = Long.parseLong(required(row, "event_sequence"));
                    if (sequence < 1 || !row.get("event_sequence").matches("[1-9][0-9]*")) throw new IllegalArgumentException("Event sequence must be a positive integer");
                    Event event = new Event(i + 2, kind, record, entity, action, replaces,
                            time(required(row, "recorded_at_utc")), sequence, row);
                    validate(event);
                    if (kind == Kind.FEE && !itemized() && !Set.of("", "TOTAL").contains(event.get("component")))
                        throw new IllegalArgumentException("Itemized fee requires ITEMIZED_AND_TOTAL policy");
                    events.add(event);
                    if (row.get("source_reference").isEmpty())
                        finding(findings, kind, Severity.INCOMPLETE, "MISSING_SOURCE_REFERENCE", event, "", "source_reference", "", "", "Row attribution is unknown");
                } catch (RuntimeException invalid) {
                    findings.add(new Finding(kind, Severity.FAIL, "INVALID_RECORD", i + 2, row.get("record_id"), "", "record", "", "", stableMessage(invalid)));
                }
            }
        } catch (IOException | RuntimeException invalid) {
            finding(findings, Kind.INPUT, Severity.FAIL, "INVALID_LEDGER", null, "", "ledger", "", "", stableMessage(invalid));
        }
    }

    private void readCoverage(Path manifest, Snapshots snapshots) {
        try {
            byte[] bytes = input(manifest, snapshots, "coverage");
            if (bytes == null) return;
            List<Map<String, String>> rows = csv(bytes, COVERAGE_HEADERS);
            for (int i = 0; i < rows.size(); i++) {
                Map<String, String> row = rows.get(i);
                try {
                    Kind kind = kind(required(row, "record_type"));
                    Instant start = row.get("start_utc").isEmpty() ? null : time(row.get("start_utc"));
                    Instant end = row.get("end_utc").isEmpty() ? null : time(row.get("end_utc"));
                    String complete = row.get("completeness");
                    if (!Set.of("", "COMPLETE", "INCOMPLETE", "UNKNOWN").contains(complete)) throw new IllegalArgumentException("Invalid coverage completeness");
                    if (start != null && end != null && end.isBefore(start)) throw new IllegalArgumentException("Coverage end precedes start");
                    coverage.add(new Coverage(i + 2, kind, start, end, complete, row.get("source_reference")));
                } catch (RuntimeException invalid) {
                    findings.add(new Finding(Kind.INPUT, Severity.FAIL, "INVALID_COVERAGE", i + 2, "", "", "coverage", "", "", stableMessage(invalid)));
                }
            }
        } catch (IOException | RuntimeException invalid) {
            finding(findings, Kind.INPUT, Severity.FAIL, "INVALID_COVERAGE", null, "", "coverage", "", "", stableMessage(invalid));
        }
    }

    private static void validate(Event event) {
        if (event.action().equals("REVERSE")) {
            for (String field : HEADERS.subList(7, HEADERS.size() - 1))
                if (!event.get(field).isEmpty()) throw new IllegalArgumentException("REVERSE payload must be blank; it removes the referenced entity");
            return;
        }
        Set<String> applicable = new HashSet<>(FIELDS.get(event.kind())); applicable.add("replay_record_id");
        for (String field : HEADERS.subList(7, HEADERS.size() - 1))
            if (!applicable.contains(field) && !event.get(field).isEmpty()) throw new IllegalArgumentException("Inapplicable payload field: " + field);
        if (!event.get("event_time_utc").isEmpty() && time(event.get("event_time_utc")).isAfter(event.recorded()))
            throw new IllegalArgumentException("Economic event time follows recorded-at time");
        for (String field : List.of("instrument_id", "order_id", "related_id", "replay_record_id"))
            if (!event.get(field).isEmpty()) id(event.get(field));
        for (String field : NUMBERS) if (!event.get(field).isEmpty()) {
            BigDecimal number = decimal(event.get(field));
            if ((field.equals("quantity_units") || field.equals("filled_units"))
                    && (!event.get(field).matches("-?[0-9]+") || number.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0 || number.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0))
                throw new IllegalArgumentException("Units must be signed 64-bit integers");
            if (field.equals("filled_units") && number.signum() < 0) throw new IllegalArgumentException("Filled units cannot be negative");
            if (field.equals("quantity_units") && (event.kind() == Kind.FILL || event.kind() == Kind.ORDER) && number.signum() <= 0)
                throw new IllegalArgumentException("Order and fill units must be positive");
            if (field.equals("price_inr") && (number.signum() < 0 || event.kind() == Kind.FILL && number.signum() == 0))
                throw new IllegalArgumentException("Fill prices must be positive and settlement values non-negative");
            if (field.equals("amount_inr") && event.kind() == Kind.FEE && number.signum() < 0)
                throw new IllegalArgumentException("Fee totals cannot be negative; use explicit correction/reversal events");
        }
        allowed(event, "side", Set.of("BUY", "SELL"));
        allowed(event, "related_type", Set.of("FILL", "SETTLEMENT"));
        allowed(event, "checkpoint", Set.of("OPENING", "CLOSING"));
        if (!event.get("status").isEmpty() && !event.get("status").matches("[A-Z][A-Z0-9_]{0,63}"))
            throw new IllegalArgumentException("Status must be a normalized uppercase token, compared verbatim to the replay");
        if (event.kind() == Kind.FEE && !event.get("component").isEmpty()) id(event.get("component"));
        if (event.kind() == Kind.CASH) allowed(event, "component", Set.of("TRADE_NOTIONAL", "FEE", "SETTLEMENT_PROCEEDS", "SETTLEMENT_FEE"));
        if (event.kind() == Kind.SETTLEMENT) allowed(event, "component", Set.of("CASH_FUTURES_FINAL_PRICE", "CASH_OPTION_PAYOFF"));
    }
    private static void allowed(Event event, String field, Set<String> allowed) {
        if (!event.get(field).isEmpty() && !allowed.contains(event.get(field))) throw new IllegalArgumentException("Unsupported " + field);
    }

    boolean itemized() { return "ITEMIZED_AND_TOTAL".equals(metadata.get("fee_basis")); }

    History normalize() {
        var byId = new TreeMap<String, Event>();
        var groups = new TreeMap<String, List<Event>>();
        var invalid = new HashSet<String>();
        var sequences = new HashMap<Long, Event>();
        long prior = 0;
        for (Event event : events) {
            groups.computeIfAbsent(event.key(), ignored -> new ArrayList<>()).add(event);
            Event duplicate = byId.putIfAbsent(event.id(), event);
            if (duplicate != null) {
                invalid.add(event.key()); invalid.add(duplicate.key());
                finding(findings, event.kind(), Severity.FAIL, "DUPLICATE_RECORD_ID", event, "", "record_id", duplicate.id(), event.id(), "Repeated event ID; neither payload is silently deduplicated");
            }
            Event sameSequence = sequences.putIfAbsent(event.sequence(), event);
            if (sameSequence != null) {
                invalid.add(event.key()); invalid.add(sameSequence.key());
                finding(findings, event.kind(), Severity.FAIL, "DUPLICATE_SEQUENCE", event, "", "event_sequence", "", Long.toString(event.sequence()), "Sequence is not unique within the caller bundle");
            }
            if (event.sequence() < prior)
                finding(findings, event.kind(), Severity.INFO, "OUT_OF_ORDER_INPUT", event, "", "event_sequence", Long.toString(prior), Long.toString(event.sequence()), "Physical rows are out of source sequence; explicit references govern normalization");
            prior = event.sequence();
        }
        var priorRecorded = new HashMap<Kind, Instant>();
        var priorEconomic = new HashMap<Kind, Instant>();
        for (Event event : events.stream().sorted(Comparator.comparingLong(Event::sequence).thenComparing(Event::id)).toList()) {
            Instant previous = priorRecorded.put(event.kind(), event.recorded());
            if (previous != null && event.recorded().isBefore(previous))
                finding(findings, event.kind(), Severity.INFO, "OUT_OF_ORDER_RECORDED_TIME", event, "", "recorded_at_utc", previous.toString(), event.recorded().toString(), "Recorded time decreases in source sequence");
            if (event.action().equals("NEW") && !event.get("event_time_utc").isEmpty()) {
                Instant time = time(event.get("event_time_utc"));
                Instant earlier = priorEconomic.put(event.kind(), time);
                if (earlier != null && time.isBefore(earlier))
                    finding(findings, event.kind(), Severity.INFO, "OUT_OF_ORDER_EVENT", event, "", "event_time_utc", earlier.toString(), time.toString(), "Economic events arrived out of order; reconciliation uses their economic timestamps");
            }
        }
        var children = new HashMap<String, List<Event>>();
        for (Event event : events) if (!event.action().equals("NEW")) {
            Event target = byId.get(event.replaces());
            if (target == null) {
                invalid.add(event.key());
                finding(findings, event.kind(), Severity.INCOMPLETE, "MISSING_CORRECTION_TARGET", event, "", "replaces_record_id", event.replaces(), "", "Referenced history is absent; no replacement value was invented");
            } else {
                children.computeIfAbsent(target.id(), ignored -> new ArrayList<>()).add(event);
                if (!target.key().equals(event.key()) || target.action().equals("REVERSE")) {
                    invalid.add(event.key()); invalid.add(target.key());
                    finding(findings, event.kind(), Severity.FAIL, "INVALID_CORRECTION_TARGET", event, "", "replaces_record_id", "", target.id(), "Updates must stay on one entity and cannot follow a terminal reversal");
                }
                if (target.sequence() >= event.sequence() || event.recorded().isBefore(target.recorded())) {
                    invalid.add(event.key());
                    finding(findings, event.kind(), Severity.FAIL, "INVALID_CORRECTION_ORDER", event, "", "replaces_record_id", "", target.id(), "An update must follow its target in sequence and recorded time");
                }
            }
            var visited = new HashSet<String>(); Event cursor = event;
            while (cursor != null && !cursor.action().equals("NEW")) {
                if (!visited.add(cursor.id())) {
                    invalid.add(event.key());
                    finding(findings, event.kind(), Severity.FAIL, "CORRECTION_CYCLE", event, "", "replaces_record_id", "", "", "Cyclic correction history has no effective record");
                    break;
                }
                cursor = byId.get(cursor.replaces());
            }
        }
        for (var entry : children.entrySet()) if (entry.getValue().size() > 1) {
            for (Event event : entry.getValue()) {
                invalid.add(event.key());
                finding(findings, event.kind(), Severity.FAIL, "BRANCHED_CORRECTION", event, "", "replaces_record_id", "", entry.getKey(), "Multiple updates replace the same event; no last-row winner is selected");
            }
        }
        var active = new TreeMap<String, Event>();
        var dispositions = new TreeMap<Integer, String>();
        for (var group : groups.entrySet()) {
            List<Event> roots = group.getValue().stream().filter(e -> e.action().equals("NEW")).toList();
            if (roots.size() > 1) {
                invalid.add(group.getKey());
                finding(findings, roots.getLast().kind(), Severity.FAIL, "DUPLICATE_ENTITY_ID", roots.getLast(), "", "entity_id", "", roots.getLast().entity(), "An entity has multiple NEW records");
            }
            if (invalid.contains(group.getKey()) || roots.size() != 1) {
                group.getValue().forEach(e -> dispositions.put(e.line(), "INVALID")); continue;
            }
            Event head = roots.getFirst(); int count = 1;
            while (children.containsKey(head.id())) {
                dispositions.put(head.line(), "SUPERSEDED");
                head = children.get(head.id()).getFirst(); count++;
            }
            if (count != group.getValue().size()) {
                group.getValue().forEach(e -> dispositions.put(e.line(), "INVALID"));
                finding(findings, head.kind(), Severity.FAIL, "DISCONNECTED_HISTORY", head, "", "replaces_record_id", "", "", "History is not one complete replacement chain");
                continue;
            }
            if (head.action().equals("REVERSE")) {
                dispositions.put(head.line(), "REVERSED");
                finding(findings, head.kind(), Severity.INFO, "REVERSAL_APPLIED", head, "", "replaces_record_id", head.replaces(), "", "Terminal reversal removes this entity from effective records; original rows remain in events.csv");
            } else { active.put(group.getKey(), head); dispositions.put(head.line(), "ACTIVE"); }
            for (Event event : group.getValue()) if (event.action().equals("CORRECT"))
                finding(findings, event.kind(), Severity.INFO, "CORRECTION_APPLIED", event, "", "replaces_record_id", event.replaces(), event.id(), "Full replacement payload applied through explicit history");
        }
        return new History(Collections.unmodifiableMap(active), Collections.unmodifiableMap(dispositions));
    }
    static String stableMessage(Exception failure) {
        if (failure instanceof java.nio.charset.CharacterCodingException) return "Malformed UTF-8";
        if (failure instanceof IOException) return "Input could not be read under the regular-file and path contract";
        if (failure instanceof java.time.DateTimeException) return "Invalid UTC timestamp";
        return failure.getMessage() == null ? "Invalid normalized input" : failure.getMessage();
    }
}
