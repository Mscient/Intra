package com.mscient.intrapaper.audit.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.mscient.intrapaper.audit.reconciliation.LedgerSupport.*;

/** Independent domains and external arithmetic, evaluated only against a verified replay baseline. */
final class LedgerReconciler {
    record Result(Status status, Map<String, byte[]> files) { }
    private final VerifiedReplay replay;
    private final LedgerInputs input;
    private final List<Finding> findings;
    private final List<Comparison> comparisons = new ArrayList<>();
    private final LedgerInputs.History history;
    private final Map<Event, Expected> matched = new HashMap<>();
    private final Map<String, Event> byReplay = new HashMap<>();
    private final Map<Event, Map<String, String>> normalized = new HashMap<>();
    private final EnumMap<Kind, Boolean> covered = new EnumMap<>(Kind.class);
    private final StringBuilder totals = header("record_type", "instrument_id", "metric", "expected", "external", "difference", "status");

    private LedgerReconciler(VerifiedReplay replay, LedgerInputs input, List<Finding> findings) {
        this.replay = replay; this.input = input; this.findings = findings; this.history = input.normalize();
    }
    static Result reconcile(VerifiedReplay replay, LedgerInputs input, Snapshots snapshots, List<Finding> findings) {
        LedgerReconciler engine = new LedgerReconciler(replay, input, findings);
        return engine.run(snapshots);
    }

    private Result run(Snapshots snapshots) {
        coverage();
        if (replay != null) {
            if (!replay.completeSessions)
                finding(findings, Kind.INPUT, Severity.INCOMPLETE, "REPLAY_SESSION_COVERAGE_INCOMPLETE", null, "", "replay", "", "", "Replay is verified but its supplied session windows are incomplete");
            for (Kind kind : List.of(Kind.ORDER, Kind.SETTLEMENT, Kind.FILL, Kind.POSITION, Kind.FEE, Kind.CASH)) match(kind);
            arithmetic(); aggregates();
        } else {
            for (Kind kind : DOMAINS)
                finding(findings, kind, Severity.INCOMPLETE, "REPLAY_BASELINE_UNAVAILABLE", null, "", "replay", "", "", "Unverified replay values were not used");
        }
        var files = new TreeMap<String, byte[]>();
        files.put("comparisons.csv", comparisonBytes());
        files.put("exceptions.csv", exceptionBytes());
        files.put("events.csv", eventBytes());
        files.put("coverage.csv", coverageBytes());
        files.put("totals.csv", utf8(totals.toString()));
        StringBuilder provenance = header("input", "reference", "sha256", "declared_sha256", "bytes", "status", "source_id", "source_reference");
        snapshots.provenance.values().forEach(p -> row(provenance, p.role(), p.reference(), p.sha256(), p.declared(), p.bytes(), p.status(), p.sourceId(), p.sourceReference()));
        files.put("inputs.csv", utf8(provenance.toString()));
        Status status = status(findings);
        StringBuilder summary = new StringBuilder("schema_version=1\nclassification=OFFLINE_EXTERNAL_LEDGER_RECONCILIATION\n")
                .append("status=").append(status).append("\nexternal_records=CALLER_SUPPLIED\nsource_authenticity=NOT_ESTABLISHED\nperformance_claim_eligible=false\ng4_gate_closed=false\n")
                .append("replay_verified=").append(replay != null).append("\nreplay_run_id=").append(replay == null ? "" : replay.runId)
                .append("\nreplay_start_utc=").append(replay == null ? "" : replay.start).append("\nreplay_end_utc=").append(replay == null ? "" : replay.end)
                .append("\nexternal_classification=").append(input.metadata.getOrDefault("classification", ""))
                .append("\nexternal_source_id=").append(input.metadata.getOrDefault("source_id", ""))
                .append("\ncomparison=").append(input.metadata.getOrDefault("comparison", ""))
                .append("\nmatching=").append(input.metadata.getOrDefault("matching", ""))
                .append("\ntolerances=").append(input.metadata.getOrDefault("tolerances", ""))
                .append("\npolicy_source_reference=").append(input.metadata.getOrDefault("policy_source_reference", ""))
                .append("\ncash_basis=").append(input.metadata.getOrDefault("cash_basis", ""))
                .append("\nfee_basis=").append(input.metadata.getOrDefault("fee_basis", ""))
                .append("\ncurrency=").append(input.metadata.getOrDefault("currency", ""))
                .append("\nfail_count=").append(findings.stream().filter(f -> f.severity() == Severity.FAIL).count())
                .append("\nincomplete_count=").append(findings.stream().filter(f -> f.severity() == Severity.INCOMPLETE).count())
                .append("\ninfo_count=").append(findings.stream().filter(f -> f.severity() == Severity.INFO).count()).append('\n');
        for (Kind kind : DOMAINS) {
            summary.append(kind).append(".status=").append(status(findings.stream().filter(f -> f.kind() == kind).toList())).append('\n');
            summary.append(kind).append(".expected_count=").append(replay == null ? "" : expected(kind).size()).append('\n');
            summary.append(kind).append(".effective_external_count=").append(active(kind).size()).append('\n');
            summary.append(kind).append(".matched_count=").append(matched.keySet().stream().filter(e -> e.kind() == kind).count()).append('\n');
        }
        files.put("summary.properties", utf8(summary.toString()));
        return new Result(status, Collections.unmodifiableMap(files));
    }

    private void coverage() {
        for (Kind kind : DOMAINS) {
            List<LedgerInputs.Coverage> declarations = input.coverage.stream().filter(c -> c.kind() == kind).toList();
            boolean complete = declarations.size() == 1;
            if (declarations.isEmpty()) finding(findings, kind, Severity.INCOMPLETE, "MISSING_COVERAGE", null, "", "coverage", "", "", "No coverage declaration for this domain");
            if (declarations.size() > 1) finding(findings, kind, Severity.FAIL, "DUPLICATE_COVERAGE", null, "", "coverage", "1", Integer.toString(declarations.size()), "Exactly one coverage declaration is permitted per domain");
            for (var coverage : declarations) {
                boolean known = coverage.start() != null && coverage.end() != null && coverage.complete().equals("COMPLETE") && !coverage.reference().isEmpty();
                if (!known) {
                    complete = false;
                    finding(findings, kind, Severity.INCOMPLETE, "INCOMPLETE_COVERAGE", null, "", "coverage", "", coverage.complete(), "Caller completeness, timestamps or attribution are unknown/incomplete");
                } else if (replay != null && (coverage.start().isAfter(replay.start) || coverage.end().isBefore(replay.end))) {
                    complete = false;
                    finding(findings, kind, Severity.INCOMPLETE, "COVERAGE_GAP", null, "", "coverage", replay.start + "/" + replay.end,
                            coverage.start() + "/" + coverage.end(), "Declared coverage does not contain the entire replay interval");
                }
            }
            covered.put(kind, complete);
        }
    }

    private List<Expected> expected(Kind kind) { return replay.records.stream().filter(e -> e.kind() == kind).sorted(Comparator.comparing(Expected::id)).toList(); }
    private List<Event> active(Kind kind) { return history.active().values().stream().filter(e -> e.kind() == kind).sorted(Comparator.comparing(Event::entity)).toList(); }

    private void match(Kind kind) {
        List<Expected> expected = expected(kind);
        Map<String, List<Event>> proposals = new TreeMap<>();
        Map<Event, String> methods = new HashMap<>();
        for (Event event : active(kind)) {
            Map<String, String> fields = normalized(event);
            String explicit = event.get("replay_record_id");
            List<Expected> candidates;
            if (!explicit.isEmpty()) {
                candidates = expected.stream().filter(e -> e.id().equals(explicit)).toList(); methods.put(event, "EXPLICIT_ID");
            } else {
                if (!"UNIQUE_COMPOSITE_KEYS".equals(input.metadata.get("matching"))) {
                    finding(findings, kind, Severity.INCOMPLETE, "MISSING_DECLARED_ID", event, "", "replay_record_id", "", "",
                            "Declared-ID policy requires a replay record ID; no composite key was inferred");
                    continue;
                }
                String fallback = key(kind, fields);
                if (fallback == null) {
                    finding(findings, kind, Severity.INCOMPLETE, "MISSING_MATCH_KEY", event, "", "fallback_key", "", "", "Explicit replay ID is blank and the documented fallback key is incomplete");
                    continue;
                }
                candidates = expected.stream().filter(e -> fallback.equals(key(kind, e.fields()))).toList(); methods.put(event, "FALLBACK_KEY");
            }
            if (candidates.isEmpty()) {
                finding(findings, kind, Severity.FAIL, explicit.isEmpty() ? "UNMATCHED_EXTERNAL_RECORD" : "UNKNOWN_REPLAY_ID", event, explicit,
                        "replay_record_id", "", explicit, "No replay record matches; an explicit ID is never silently replaced by a fallback");
            } else if (candidates.size() > 1) {
                finding(findings, kind, Severity.FAIL, "AMBIGUOUS_FALLBACK", event, "", "fallback_key", "", "", "Fallback resolves to multiple replay records; no nearest-time or amount guess is made");
            } else proposals.computeIfAbsent(candidates.getFirst().id(), ignored -> new ArrayList<>()).add(event);
        }
        for (Expected record : expected) {
            List<Event> candidates = proposals.getOrDefault(record.id(), List.of());
            if (candidates.size() > 1) {
                for (Event event : candidates)
                    finding(findings, kind, Severity.FAIL, "DUPLICATE_REPLAY_MATCH", event, record.id(), "replay_record_id", record.id(), record.id(), "Multiple effective external entities claim one replay record");
            } else if (candidates.size() == 1) {
                Event event = candidates.getFirst(); matched.put(event, record); byReplay.put(kind + ":" + record.id(), event);
                for (String field : FIELDS.get(kind)) compare(kind, record.id(), event, methods.get(event), field, record.get(field), normalized(event).getOrDefault(field, ""), "VALUE_MISMATCH");
            }
            if (candidates.size() != 1) {
                finding(findings, kind, Severity.INCOMPLETE, "MISSING_EXTERNAL_RECORD", null, record.id(), "record", record.id(), "", "No unique effective external record is available");
                for (String field : FIELDS.get(kind)) comparisons.add(new Comparison(kind, record.id(), "", 0, "MISSING", field, record.get(field), "", "", Status.INCOMPLETE));
            }
        }
    }

    private Map<String, String> normalized(Event event) {
        if (normalized.containsKey(event)) return normalized.get(event);
        Map<String, String> values = new TreeMap<>(event.fields());
        for (String field : NUMBERS) if (!event.get(field).isEmpty()) values.put(field, number(amount(event.get(field))));
        if (!event.get("event_time_utc").isEmpty()) {
            Instant timestamp = time(event.get("event_time_utc")); values.put("event_time_utc", timestamp.toString());
            if (timestamp.isBefore(replay.start) || timestamp.isAfter(replay.end))
                finding(findings, event.kind(), Severity.FAIL, "EVENT_OUTSIDE_REPLAY_SCOPE", event, "", "event_time_utc", replay.start + "/" + replay.end, timestamp.toString(), "Economic record falls outside the replay interval");
        }
        if (event.kind() == Kind.FILL) values.put("order_id", reference(event, Kind.ORDER, event.get("order_id"), "order_id"));
        if (event.kind() == Kind.FEE || event.kind() == Kind.CASH) {
            String type = event.get("related_type");
            values.put("related_id", type.isEmpty() ? "" : reference(event, kind(type), event.get("related_id"), "related_id"));
        }
        normalized.put(event, Collections.unmodifiableMap(values));
        return normalized.get(event);
    }

    private String reference(Event event, Kind kind, String entity, String field) {
        Event target = history.active().get(kind + ":" + entity);
        Expected record = target == null ? null : matched.get(target);
        if (record == null) {
            finding(findings, event.kind(), Severity.INCOMPLETE, "MISSING_RELATED_RECORD", event, "", field, "", entity, "Related external entity is absent, reversed, invalid or lacks a unique replay match");
            return "";
        }
        return record.id();
    }

    private static String key(Kind kind, Map<String, String> fields) {
        List<String> columns = switch (kind) {
            case ORDER -> List.of("instrument_id", "event_time_utc", "side");
            case FILL -> List.of("order_id", "instrument_id", "event_time_utc", "side");
            case POSITION -> List.of("instrument_id", "event_time_utc", "checkpoint");
            case SETTLEMENT -> List.of("instrument_id", "event_time_utc", "component");
            case FEE, CASH -> List.of("related_type", "related_id", "component");
            default -> throw new IllegalArgumentException("No fallback for INPUT");
        };
        List<String> values = columns.stream().map(c -> fields.getOrDefault(c, "")).toList();
        return values.stream().anyMatch(String::isEmpty) ? null : String.join("\u0000", values);
    }

    private void compare(Kind kind, String replayId, Event event, String method, String field, String expected, String actual, String mismatchCode) {
        boolean numeric = NUMBERS.contains(field) || method.equals("EXTERNAL_ARITHMETIC");
        Status status; String difference = "";
        if (expected.isEmpty() || actual.isEmpty()) {
            status = Status.INCOMPLETE;
            finding(findings, kind, Severity.INCOMPLETE, expected.isEmpty() ? "UNKNOWN_EXPECTED_VALUE" : "UNKNOWN_EXTERNAL_VALUE", event,
                    replayId, field, expected, actual, "Comparison is unknown; absent amounts and quantities remain blank");
        } else {
            boolean equal = numeric ? amount(expected).compareTo(amount(actual)) == 0 : expected.equals(actual);
            if (numeric) difference = number(amount(actual).subtract(amount(expected)));
            status = equal ? Status.PASS : Status.FAIL;
            if (!equal) finding(findings, kind, Severity.FAIL, mismatchCode, event, replayId, field, expected, actual, "Exact comparison differs under the caller-declared policy");
        }
        comparisons.add(new Comparison(kind, replayId, event.id(), event.line(), method, field, expected, actual, difference, status));
    }
    private void derived(Event event, String field, BigDecimal expected, BigDecimal actual, String code) {
        Expected replayRecord = matched.get(event);
        compare(event.kind(), replayRecord == null ? "" : replayRecord.id(), event, "EXTERNAL_ARITHMETIC", field,
                number(expected), number(actual), code);
    }

    private void arithmetic() {
        for (Event event : matched.keySet().stream().sorted(Comparator.comparing(Event::key)).toList()) {
            if (event.kind() == Kind.ORDER) {
                Expected order = matched.get(event);
                BigDecimal total = sumExternal(Kind.FILL, "quantity_units", e -> e.get("order_id").equals(order.id()));
                derived(event, "filled_units_from_fills", total, amount(event.get("filled_units")), "ORDER_FILL_TOTAL_MISMATCH");
                BigDecimal requested = amount(event.get("quantity_units")), filled = amount(event.get("filled_units"));
                if (requested != null && filled != null && filled.compareTo(requested) > 0)
                    finding(findings, Kind.ORDER, Severity.FAIL, "OVERFILLED_ORDER", event, order.id(), "filled_units", number(requested), number(filled), "Declared filled units exceed requested units");
                if (requested != null && total != null && total.compareTo(requested) > 0)
                    finding(findings, Kind.ORDER, Severity.FAIL, "OVERFILLED_ORDER", event, order.id(), "filled_units_from_fills", number(requested), number(total), "Effective child fills exceed requested units");
                if (event.get("status").equals("FILLED")) derived(event, "filled_status_units", requested, filled, "INVALID_FILLED_STATUS");
                if (Set.of("PARTIAL", "PARTIALLY_FILLED").contains(event.get("status")) && requested != null && filled != null
                        && (filled.signum() <= 0 || filled.compareTo(requested) >= 0))
                    finding(findings, Kind.ORDER, Severity.FAIL, "INVALID_PARTIAL_STATUS", event, order.id(), "filled_units", "", number(filled), "Partial status requires a positive fill strictly smaller than the order quantity");
            } else if (event.kind() == Kind.FEE && input.itemized() && event.get("component").equals("TOTAL")) {
                Map<String, String> payload = normalized(event);
                java.util.function.Predicate<Expected> items = e -> e.get("related_type").equals(payload.get("related_type"))
                        && e.get("related_id").equals(payload.get("related_id")) && !e.get("component").equals("TOTAL");
                BigDecimal itemTotal = expected(Kind.FEE).stream().noneMatch(items) ? null : sumExternal(Kind.FEE, "amount_inr", items);
                derived(event, "fee_total_from_items", itemTotal, amount(event.get("amount_inr")), "CHARGE_ITEM_TOTAL_MISMATCH");
            } else if (event.kind() == Kind.SETTLEMENT) {
                derived(event, "settlement_proceeds_from_units_price", product(event.get("quantity_units"), event.get("price_inr")),
                        amount(event.get("amount_inr")), "SETTLEMENT_ARITHMETIC_MISMATCH");
            } else if (event.kind() == Kind.CASH) {
                String type = event.get("related_type");
                Event target = history.active().get(type + ":" + event.get("related_id"));
                BigDecimal value = null;
                if (target != null && matched.containsKey(target)) {
                    switch (event.get("component")) {
                        case "TRADE_NOTIONAL" -> {
                            if (target.kind() == Kind.FILL && !target.get("side").isEmpty()) {
                                value = product(target.get("quantity_units"), target.get("price_inr"));
                                if (value != null && target.get("side").equals("BUY")) value = value.negate();
                            }
                        }
                        case "SETTLEMENT_PROCEEDS" -> { if (target.kind() == Kind.SETTLEMENT) value = amount(target.get("amount_inr")); }
                        case "FEE", "SETTLEMENT_FEE" -> {
                            Event fee = byReplay.get("FEE:" + matched.get(target).id() + ":TOTAL");
                            if (fee != null && type.equals(event.get("component").equals("FEE") ? "FILL" : "SETTLEMENT")) {
                                BigDecimal charge = amount(fee.get("amount_inr")); value = charge == null ? null : charge.negate();
                            }
                        }
                        default -> { }
                    }
                }
                derived(event, "cash_from_related_record", value, amount(event.get("amount_inr")), "CASH_ARITHMETIC_MISMATCH");
            }
        }
    }
    private static BigDecimal product(String quantity, String price) {
        BigDecimal q = amount(quantity), p = amount(price); return q == null || p == null ? null : q.multiply(p);
    }

    /** Missing/ambiguous records or incomplete coverage make the total unknown, even if observed rows sum to zero. */
    private BigDecimal sumExternal(Kind kind, String field, java.util.function.Predicate<Expected> select) {
        if (!covered.getOrDefault(kind, false) || active(kind).stream().anyMatch(e -> !matched.containsKey(e))
                || input.events.stream().anyMatch(e -> e.kind() == kind && "INVALID".equals(history.dispositions().get(e.line())))
                || findings.stream().anyMatch(f -> (f.kind() == Kind.INPUT || f.kind() == kind) && Set.of("INVALID_RECORD", "INVALID_LEDGER", "INPUT_HASH_MISMATCH", "INVALID_EXTERNAL_MANIFEST", "INVALID_COVERAGE").contains(f.code())))
            return null;
        for (Expected record : expected(kind).stream().filter(select).toList()) {
            Event event = byReplay.get(kind + ":" + record.id());
            if (event == null || event.get(field).isEmpty()) return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Event event : active(kind)) {
            Map<String, String> payload = normalized(event);
            if (payload.getOrDefault("instrument_id", "").isEmpty() || key(kind, payload) == null) return null;
            // Group by the caller's effective payload, not by the matched replay's economics.
            if (select.test(new Expected(kind, matched.get(event).id(), payload))) {
                if (event.get(field).isEmpty()) return null;
                total = total.add(amount(event.get(field)));
            }
        }
        return total;
    }
    private BigDecimal sumExpected(Kind kind, String field, java.util.function.Predicate<Expected> select) {
        BigDecimal total = BigDecimal.ZERO;
        for (Expected record : expected(kind).stream().filter(select).toList()) {
            BigDecimal value = amount(record.get(field)); if (value == null) return null;
            total = total.add(value);
        }
        return total;
    }
    private void total(Kind kind, String instrument, String metric, BigDecimal expected, BigDecimal actual) {
        Status status = expected == null || actual == null ? Status.INCOMPLETE : expected.compareTo(actual) == 0 ? Status.PASS : Status.FAIL;
        String difference = expected == null || actual == null ? "" : number(actual.subtract(expected));
        row(totals, kind, instrument, metric, number(expected), number(actual), difference, status);
        if (status != Status.PASS) finding(findings, kind, status == Status.FAIL ? Severity.FAIL : Severity.INCOMPLETE,
                status == Status.FAIL ? "TOTAL_MISMATCH" : "UNKNOWN_TOTAL", null, "", metric, number(expected), number(actual), "Totals reconcile separately; incomplete inputs do not become zero");
    }

    private void aggregates() {
        Set<String> instruments = new TreeSet<>(); replay.records.forEach(e -> instruments.add(e.get("instrument_id")));
        for (String instrument : instruments) {
            for (String side : List.of("BUY", "SELL")) {
                java.util.function.Predicate<Expected> select = e -> e.get("instrument_id").equals(instrument) && e.get("side").equals(side);
                total(Kind.FILL, instrument, side + "_units", sumExpected(Kind.FILL, "quantity_units", select), sumExternal(Kind.FILL, "quantity_units", select));
            }
            java.util.function.Predicate<Expected> select = e -> e.get("instrument_id").equals(instrument);
            java.util.function.Predicate<Expected> feeTotals = e -> select.test(e) && e.get("component").equals("TOTAL");
            total(Kind.FEE, instrument, "total_fee_inr", sumExpected(Kind.FEE, "amount_inr", feeTotals), sumExternal(Kind.FEE, "amount_inr", feeTotals));
            if (input.itemized()) {
                var components = new TreeSet<String>();
                expected(Kind.FEE).stream().filter(select).filter(e -> !e.get("component").equals("TOTAL"))
                        .forEach(e -> components.add(e.get("component")));
                for (String component : components) {
                    java.util.function.Predicate<Expected> item = e -> select.test(e) && e.get("component").equals(component);
                    total(Kind.FEE, instrument, "component:" + component + "_inr", sumExpected(Kind.FEE, "amount_inr", item), sumExternal(Kind.FEE, "amount_inr", item));
                }
            }
            total(Kind.SETTLEMENT, instrument, "signed_units", sumExpected(Kind.SETTLEMENT, "quantity_units", select), sumExternal(Kind.SETTLEMENT, "quantity_units", select));
            total(Kind.SETTLEMENT, instrument, "proceeds_inr", sumExpected(Kind.SETTLEMENT, "amount_inr", select), sumExternal(Kind.SETTLEMENT, "amount_inr", select));
            for (String component : List.of("TRADE_NOTIONAL", "FEE", "SETTLEMENT_PROCEEDS", "SETTLEMENT_FEE")) {
                java.util.function.Predicate<Expected> cash = e -> select.test(e) && e.get("component").equals(component);
                total(Kind.CASH, instrument, component + "_inr", sumExpected(Kind.CASH, "amount_inr", cash), sumExternal(Kind.CASH, "amount_inr", cash));
            }
            Event opening = byReplay.get("POSITION:OPENING:" + instrument), closing = byReplay.get("POSITION:CLOSING:" + instrument);
            BigDecimal start = opening == null ? null : amount(opening.get("quantity_units"));
            BigDecimal buys = sumExternal(Kind.FILL, "quantity_units", e -> select.test(e) && e.get("side").equals("BUY"));
            BigDecimal sells = sumExternal(Kind.FILL, "quantity_units", e -> select.test(e) && e.get("side").equals("SELL"));
            BigDecimal settled = sumExternal(Kind.SETTLEMENT, "quantity_units", select);
            BigDecimal flow = !covered.getOrDefault(Kind.POSITION, false) || start == null || buys == null || sells == null || settled == null ? null : start.add(buys).subtract(sells).subtract(settled);
            BigDecimal expectedClose = sumExpected(Kind.POSITION, "quantity_units", e -> select.test(e) && e.get("checkpoint").equals("CLOSING"));
            total(Kind.POSITION, instrument, "closing_units_from_flows", expectedClose, flow);
            if (closing != null) derived(closing, "closing_units_from_flows", flow, amount(closing.get("quantity_units")), "POSITION_FLOW_MISMATCH");
        }
    }

    private byte[] comparisonBytes() {
        StringBuilder out = header("record_type", "replay_record_id", "external_record_id", "external_line", "match_method", "field", "expected", "external", "difference", "status");
        comparisons.stream().sorted(Comparator.comparing(Comparison::kind).thenComparing(Comparison::replayId).thenComparing(Comparison::recordId)
                .thenComparing(Comparison::method).thenComparing(Comparison::field).thenComparingInt(Comparison::line))
                .forEach(c -> row(out, c.kind(), c.replayId(), c.recordId(), c.line() == 0 ? "" : c.line(), c.method(), c.field(), c.expected(), c.actual(), c.difference(), c.status()));
        return utf8(out.toString());
    }
    private byte[] exceptionBytes() {
        StringBuilder out = header("record_type", "severity", "code", "external_line", "external_record_id", "replay_record_id", "field", "expected", "external", "detail");
        findings.stream().sorted(Comparator.comparing(Finding::kind).thenComparing(Finding::code).thenComparing(Finding::replayId)
                .thenComparingInt(Finding::line).thenComparing(Finding::field).thenComparing(Finding::detail))
                .forEach(f -> row(out, f.kind(), f.severity(), f.code(), f.line() == 0 ? "" : f.line(), f.recordId(), f.replayId(), f.field(), f.expected(), f.actual(), f.detail()));
        return utf8(out.toString());
    }
    private byte[] eventBytes() {
        StringBuilder out = header("external_line", "disposition", String.join(",", HEADERS));
        for (int i = 0; i < input.raw.size(); i++) {
            var values = new ArrayList<Object>(); values.add(i + 2); values.add(history.dispositions().getOrDefault(i + 2, "INVALID"));
            for (String header : HEADERS) values.add(input.raw.get(i).get(header)); row(out, values.toArray());
        }
        return utf8(out.toString());
    }
    private byte[] coverageBytes() {
        StringBuilder out = header("record_type", "start_utc", "end_utc", "completeness", "status", "source_reference");
        for (Kind kind : DOMAINS) {
            List<LedgerInputs.Coverage> rows = input.coverage.stream().filter(c -> c.kind() == kind).toList();
            if (rows.isEmpty()) row(out, kind, "", "", "", Status.INCOMPLETE, "");
            else for (var c : rows) row(out, kind, c.start(), c.end(), c.complete(), covered.get(kind) ? Status.PASS : rows.size() > 1 ? Status.FAIL : Status.INCOMPLETE, c.reference());
        }
        return utf8(out.toString());
    }
}
