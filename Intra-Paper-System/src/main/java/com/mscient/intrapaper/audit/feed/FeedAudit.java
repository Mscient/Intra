package com.mscient.intrapaper.audit.feed;

import static com.mscient.intrapaper.audit.feed.AuditJson.*;
import static com.mscient.intrapaper.audit.feed.AuditInputs.*;
import com.mscient.intrapaper.data.StrictJsonParser;
import com.mscient.intrapaper.marketdata.*;
import com.mscient.intrapaper.reference.DatedInstrumentMappingRegistry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** Offline observations only. A successful configured audit is never a completeness assertion. */
public final class FeedAudit {
    public static final String VERSION = "feed-completeness-gap-audit/1";
    public static final String LIMITS = "Caller-supplied unauthenticated evidence. PASS means only no configured anomaly detected. "
            + "No exchange/vendor guarantee, packet-loss proof, market-activity inference, or feed-completeness claim. G2/G4 remain open.";
    record Located<T>(String file, int row, T value) {
        Map<String, Object> reference() { return Map.of("file", file, "row", row); }
    }
    record Event(Located<RawMarketMessage> raw, String type, Instant time, String instrument, String token, String exchange) { }
    record State(Instant start, Instant end, boolean ready, List<Map<String, Object>> evidence) { }
    private final AuditInputs inputs;
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private FeedAudit(AuditInputs inputs) { this.inputs = inputs; }

    public static Map<String, Object> run(Path manifest) throws IOException {
        try (AuditInputs inputs = AuditInputs.load(manifest)) {
            FeedAudit audit = new FeedAudit(inputs);
            for (Scope scope : inputs.scopes) audit.scope(scope, inputs.bindings.get(scope.id()), inputs.policies.get(scope.id()));
            audit.rows.sort(Comparator.comparing(AuditJson::json));
            String status = audit.rows.stream().anyMatch(r -> r.get("severity").equals("INCOMPLETE")) ? "INCOMPLETE"
                    : audit.rows.stream().anyMatch(r -> r.get("severity").equals("FAIL")) ? "FAIL" : "PASS";
            List<Object> scopes = inputs.scopes.stream().map(s -> (Object) Map.ofEntries(
                    Map.entry("scope_id", s.id()), Map.entry("source", s.source()), Map.entry("product", s.product()),
                    Map.entry("channel", s.channel()), Map.entry("connection_id", s.connection()), Map.entry("subscription_id", s.subscription()),
                    Map.entry("exchange", s.exchange()), Map.entry("token", s.token()), Map.entry("instrument_id", s.instrument()),
                    Map.entry("session_date", s.date().toString()), Map.entry("timezone", s.zone().getId()))).toList();
            return Map.ofEntries(Map.entry("schema", "feed-audit-report/v1"), Map.entry("audit_version", VERSION),
                    Map.entry("code_sha256", FeedAuditArtifacts.codeHash()), Map.entry("schedule_id", inputs.scheduleId),
                    Map.entry("schedule_revision", inputs.revision), Map.entry("input_sha256", new TreeMap<>(inputs.hashes)),
                    Map.entry("scopes", scopes), Map.entry("status", status), Map.entry("limits", LIMITS), Map.entry("rows", audit.rows));
        }
    }
    private void row(Scope s, String window, String severity, String code, Instant start, Instant end,
                     List<Map<String, Object>> evidence, String detail) {
        Map<String, Object> r = new TreeMap<>();
        r.put("scope_id", s.id()); r.put("window_id", window); r.put("severity", severity); r.put("reason_code", code);
        r.put("start_utc", start == null ? null : start.toString()); r.put("end_utc", end == null ? null : end.toString());
        r.put("evidence", evidence); r.put("detail", detail); rows.add(r);
    }
    private void fact(Scope s, String severity, String code, Located<?> e, Instant time, String detail) {
        row(s, null, severity, code, time, time, List.of(e.reference()), detail);
    }
    private void scope(Scope s, Binding b, Policy p) throws IOException {
        for (String problem : p.problems()) row(s, null, "INCOMPLETE", "POLICY_UNRESOLVED", s.start(), s.end(), List.of(), problem);
        List<Located<RawMarketMessage>> raw = new ArrayList<>();
        List<Located<FeedLifecycleEvent>> life = new ArrayList<>();
        // No report can escape until all journal readers have finished verifying all frames.
        for (Part part : b.raw()) {
            int[] index = {0}; RawCaptureJournalReader.replay(inputs.frozen.get(part.path()),
                    m -> raw.add(new Located<>(part.path(), ++index[0], m)));
        }
        for (Part part : b.lifecycle()) {
            int[] index = {0}; FeedLifecycleJournalReader.replay(inputs.frozen.get(part.path()),
                    e -> life.add(new Located<>(part.path(), ++index[0], e)));
        }
        for (Located<RawMarketMessage> r : raw) {
            RawMarketMessage m = r.value();
            if (!s.source().equals(m.source()) || !s.connection().equals(m.connectionId()) || !s.subscription().equals(m.subscriptionId()))
                throw new IllegalArgumentException("Raw source/connection/subscription identity mismatch: " + r.file() + ":" + r.row());
        }
        for (Located<FeedLifecycleEvent> l : life) {
            FeedLifecycleEvent e = l.value();
            if (!s.source().equals(e.source()) || !s.connection().equals(e.connectionId()) || e.subscriptionId().isPresent()
                    && inputs.scopes.stream().noneMatch(x -> x.source().equals(e.source()) && x.connection().equals(e.connectionId())
                    && x.subscription().equals(e.subscriptionId().get())))
                throw new IllegalArgumentException("Lifecycle identity mismatch: " + l.file() + ":" + l.row());
        }
        List<State> states = lifecycle(s, life);
        DatedInstrumentMappingRegistry mapping = b.mapping() == null ? null : DatedInstrumentMappingRegistry.load(inputs.frozen.get(b.mapping()));
        List<Event> events = new ArrayList<>();
        List<MarketQuote> decodedQuotes = new ArrayList<>();
        Located<RawMarketMessage> previous = null; Instant lastEventTime = null;
        Set<Long> seenSequences = new HashSet<>(); Set<String> seenPayloads = new HashSet<>();
        for (Located<RawMarketMessage> located : raw) {
            RawMarketMessage m = located.value();
            if (previous != null) {
                if (m.receivedAtUtc().isBefore(previous.value().receivedAtUtc()))
                    fact(s, "FAIL", "RECEIVE_TIME_REGRESSION", located, m.receivedAtUtc(), "previous=" + previous.value().receivedAtUtc());
                if (m.receivedAtMonotonicNanos() < previous.value().receivedAtMonotonicNanos())
                    fact(s, "FAIL", "MONOTONIC_REGRESSION", located, m.receivedAtUtc(), "Monotonic capture order regressed; no clock conversion attempted");
            }
            sequences(s, p, located, previous, seenSequences, life);
            previous = located;
            String payloadHash = hash(m.payload());
            if (!seenPayloads.add(payloadHash)) fact(s, "FAIL", "DUPLICATE_PAYLOAD", located, m.receivedAtUtc(), "Identical payload bytes observed; not a packet-loss conclusion");
            Event event;
            try {
                event = decode(s, b, located, decodedQuotes);
            } catch (IOException | IllegalArgumentException e) {
                fact(s, "FAIL", "DECODE_FAILURE", located, m.receivedAtUtc(), "Payload does not satisfy declared " + b.decoder()); continue;
            }
            boolean declared = inputs.scopes.stream().anyMatch(x -> x.stream().equals(s.stream()) && x.exchange().equals(event.exchange)
                    && x.token().equals(event.token) && x.instrument().equals(event.instrument));
            if (!declared) {
                fact(s, "FAIL", "UNKNOWN_INSTRUMENT_OR_TOKEN", located, m.receivedAtUtc(),
                        json(List.of(event.exchange, event.token, event.instrument))); continue;
            }
            if (!s.instrument().equals(event.instrument) || !s.token().equals(event.token) || !s.exchange().equals(event.exchange)) continue;
            if (event.time == null) fact(s, "INCOMPLETE", "EVENT_TIME_UNAVAILABLE", located, m.receivedAtUtc(), "Event-time audit and staleness cannot be evaluated");
            else {
                if (lastEventTime != null && event.time.isBefore(lastEventTime))
                    fact(s, "FAIL", "EVENT_TIME_REGRESSION", located, m.receivedAtUtc(), "event=" + event.time + "; previous=" + lastEventTime);
                lastEventTime = event.time;
                if (p.skew() != null && Duration.between(m.receivedAtUtc(), event.time).compareTo(Duration.ofMillis(p.skew())) > 0)
                    fact(s, "FAIL", "CLOCK_SKEW_EXCEEDED", located, m.receivedAtUtc(), "event=" + event.time);
                if (p.stale() != null && Duration.between(event.time, m.receivedAtUtc()).compareTo(Duration.ofMillis(p.stale())) > 0)
                    row(s, null, "FAIL", "STALE_MESSAGE", event.time, m.receivedAtUtc(), List.of(located.reference()), "Receive minus event time exceeds stale threshold; skew is not subtracted");
                if (!s.active(event.time)) fact(s, "FAIL", "EVENT_TIME_OUT_OF_WINDOW", located, event.time, "Event time falls outside scheduled active intervals");
            }
            if (mapping != null) {
                if (event.time == null) fact(s, "INCOMPLETE", "MAPPING_TIME_UNAVAILABLE", located, m.receivedAtUtc(), "Mapping requires event time");
                else try {
                    var resolution = mapping.resolve(event.exchange, event.token, event.time);
                    if (!resolution.instrumentId().equals(event.instrument))
                        fact(s, "FAIL", "MAPPING_CHANGED", located, event.time, "resolved=" + resolution.instrumentId() + "; mapping_id=" + resolution.mappingId());
                    else fact(s, "INFO", "MAPPING_RESOLVED", located, event.time, "mapping_id=" + resolution.mappingId());
                } catch (IllegalArgumentException e) { fact(s, "FAIL", "MAPPING_FAILURE", located, event.time, "No dated token mapping covers event time"); }
            }
            if (!s.active(m.receivedAtUtc())) fact(s, "FAIL", "RECEIVE_TIME_OUT_OF_WINDOW", located, m.receivedAtUtc(), "Receive time falls outside scheduled active intervals");
            if (!s.types().contains(event.type)) fact(s, "FAIL", "UNEXPECTED_EVENT_TYPE", located, m.receivedAtUtc(), event.type);
            fact(s, "INFO", "OBSERVED_MESSAGE", located, m.receivedAtUtc(), "type=" + event.type + "; event_time=" + event.time + "; payload_sha256=" + payloadHash);
            events.add(event);
        }
        if (b.quotes() != null) {
            if (!b.decoder().equals("CANONICAL_QUOTE_V1")) throw new IllegalArgumentException("Quote cross-check requires CANONICAL_QUOTE_V1");
            List<MarketQuote> quotes = new ArrayList<>(); QuoteJournalReader.replay(inputs.frozen.get(b.quotes()), quotes::add);
            if (!quotes.equals(decodedQuotes)) throw new IllegalArgumentException("Normalized quote journal does not match decoded raw records exactly");
        }
        for (Window w : s.windows()) {
            if (!w.kind().equals("ACTIVE")) {
                row(s, w.id(), "INFO", "SCHEDULE_EXCLUSION", w.start(), w.end(), List.of(), w.kind() + ": " + w.reason()); continue;
            }
            List<Event> observed = events.stream().filter(e -> w.contains(e.raw.value().receivedAtUtc())).toList();
            row(s, w.id(), "INFO", "WINDOW_OBSERVATION", w.start(), w.end(), observed.stream().map(e -> e.raw.reference()).toList(),
                    "Decoded scoped messages=" + observed.size() + "; count is not completeness proof");
            if (observed.isEmpty()) row(s, w.id(), s.types().isEmpty() ? "INFO" : "FAIL", "NO_MESSAGES_OBSERVED", w.start(), w.end(), List.of(), "Market activity is unknown; a continuing connection does not explain absent messages");
            for (String type : s.types()) {
                List<Event> typed = observed.stream().filter(e -> e.type.equals(type)).sorted(Comparator.comparing(e -> e.raw.value().receivedAtUtc())).toList();
                gap(s, w, typed.stream().map(e -> e.raw.value().receivedAtUtc()).toList(), typed.stream().map(e -> e.raw.reference()).toList(), p.gap(), "EVENT_GAP", type);
                // Age of last event continues to advance between receipts; only within this window.
                for (int i = 0; i < typed.size(); i++) {
                    Event e = typed.get(i); if (e.time == null || p.stale() == null) continue;
                    Instant until = i + 1 < typed.size() ? typed.get(i + 1).raw.value().receivedAtUtc() : w.end();
                    Instant begin = safePlus(e.time, p.stale());
                    if (begin.isBefore(e.raw.value().receivedAtUtc())) begin = e.raw.value().receivedAtUtc();
                    if (begin.isBefore(until)) row(s, w.id(), "FAIL", "STALE_INTERVAL", begin, until, List.of(e.raw.reference()), type + ": age exceeds threshold after interval start; right endpoint excluded");
                }
            }
            if (s.heartbeat().equals("LIFECYCLE")) {
                List<Located<FeedLifecycleEvent>> beats = life.stream().filter(l -> l.value().type() == FeedLifecycleEvent.Type.HEARTBEAT
                        && w.contains(l.value().observedAtUtc())).sorted(Comparator.comparing(l -> l.value().observedAtUtc())).toList();
                gap(s, w, beats.stream().map(l -> l.value().observedAtUtc()).toList(), beats.stream().map(Located::reference).toList(), p.heartbeatGap(), "HEARTBEAT_ABSENT", "connection lifecycle heartbeat");
            }
            for (State state : states) if (!state.ready && state.end.isAfter(w.start()) && state.start.isBefore(w.end()))
                row(s, w.id(), "FAIL", "INACTIVE_SUBSCRIPTION", later(state.start, w.start()), earlier(state.end, w.end()), state.evidence, "No authenticated acknowledged subscription observed for this interval");
        }
    }
    private Event decode(Scope s, Binding b, Located<RawMarketMessage> r, List<MarketQuote> quotes) throws IOException {
        RawMarketMessage raw = r.value();
        if (b.decoder().equals("CANONICAL_QUOTE_V1")) {
            MarketQuote q = CanonicalQuoteDecoder.decode(raw.payload());
            if (!Arrays.equals(raw.payload(), CanonicalQuoteEncoder.encode(q)) || !q.source().equals(raw.source())
                    || !q.receivedAtUtc().equals(raw.receivedAtUtc()) || q.receivedAtMonotonicNanos() != raw.receivedAtMonotonicNanos())
                throw new IllegalStateException("Canonical quote/capture metadata mismatch");
            quotes.add(q);
            List<Scope> candidates = inputs.scopes.stream().filter(x -> x.stream().equals(s.stream()) && x.instrument().equals(q.instrumentId())).toList();
            if (candidates.size() != 1) throw new IllegalArgumentException("Canonical quote needs exactly one declared token binding");
            Scope declared = candidates.getFirst();
            return new Event(r, "QUOTE", q.exchangeTimestampUtc().orElse(null), q.instrumentId(), declared.token(), declared.exchange());
        }
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw.payload())).toString();
        Map<String, Object> m = object(StrictJsonParser.parse(text));
        keys(m, "schema", "product", "channel", "exchange", "token", "instrument_id", "type", "event_time");
        version(m, "feed-audit-event/v1");
        if (!str(m, "product").equals(s.product()) || !str(m, "channel").equals(s.channel()))
            throw new IllegalStateException("Decoded product/channel mismatch");
        Instant time = null;
        if (m.get("event_time") != null) {
            String t = str(m, "event_time"); if (!t.endsWith("Z")) throw new IllegalArgumentException("Event time requires UTC Z");
            time = Instant.parse(t);
        }
        return new Event(r, choice(m.get("type"), "DATA", "QUOTE", "TRADE"), time, str(m, "instrument_id"), str(m, "token"), str(m, "exchange"));
    }
    private void sequences(Scope s, Policy p, Located<RawMarketMessage> current, Located<RawMarketMessage> previous,
                           Set<Long> seen, List<Located<FeedLifecycleEvent>> life) {
        RawMarketMessage m = current.value();
        if (p.sequenceDomain() == null || p.reset() == null || p.sequenceDomain().equals("NOT_OBSERVED")) return;
        if (m.sourceSequence().isEmpty()) { fact(s, "INCOMPLETE", "SEQUENCE_UNAVAILABLE", current, m.receivedAtUtc(), "Declared RAW_STREAM lacks sequence"); return; }
        long n = m.sourceSequence().getAsLong();
        if (previous != null && previous.value().sourceSequence().isPresent()) {
            long prior = previous.value().sourceSequence().getAsLong();
            if (n > prior && n - prior > 1) fact(s, "FAIL", "SEQUENCE_GAP", current, m.receivedAtUtc(), "previous=" + prior + "; current=" + n + "; not proven packet loss");
            if (n < prior && n == 0) {
                boolean reconnect = life.stream().anyMatch(l -> l.value().type() == FeedLifecycleEvent.Type.CONNECTION_ESTABLISHED
                        && l.value().observedAtUtc().isAfter(previous.value().receivedAtUtc()) && !l.value().observedAtUtc().isAfter(m.receivedAtUtc()));
                String severity = p.reset().equals("INCOMPLETE") ? "INCOMPLETE"
                        : p.reset().equals("ALLOW_ZERO_AFTER_RECONNECT") && reconnect ? "INFO" : "FAIL";
                fact(s, severity, "SEQUENCE_RESET", current, m.receivedAtUtc(), "Zero reset candidate; reconnect_observed=" + reconnect + "; policy=" + p.reset());
                seen.clear();
            } else if (seen.contains(n)) fact(s, "FAIL", "DUPLICATE_SEQUENCE", current, m.receivedAtUtc(), "sequence=" + n);
            else if (n < prior) fact(s, "FAIL", "OUT_OF_ORDER_SEQUENCE", current, m.receivedAtUtc(), "previous=" + prior + "; current=" + n);
        }
        seen.add(n);
    }
    private List<State> lifecycle(Scope s, List<Located<FeedLifecycleEvent>> events) {
        List<State> states = new ArrayList<>(); Instant cursor = s.start(), previousTime = null;
        long monotonic = Long.MIN_VALUE; boolean uncertain = false, connected = false, authenticated = false, active = false, requested = false;
        String connection = "NEW"; List<Map<String, Object>> evidence = List.of();
        for (Located<FeedLifecycleEvent> located : events) {
            FeedLifecycleEvent e = located.value(); Instant time = e.observedAtUtc();
            if (previousTime != null && time.isBefore(previousTime)) {
                fact(s, "FAIL", "LIFECYCLE_TIME_REGRESSION", located, time, "previous=" + previousTime); uncertain = true;
            }
            if (e.observedAtMonotonicNanos() < monotonic) fact(s, "FAIL", "LIFECYCLE_MONOTONIC_REGRESSION", located, time, "Lifecycle monotonic timestamp regressed");
            previousTime = time; monotonic = e.observedAtMonotonicNanos();
            if (time.isAfter(cursor) && cursor.isBefore(s.end())) {
                states.add(new State(cursor, earlier(time, s.end()), active && authenticated, evidence)); cursor = earlier(time, s.end());
            }
            boolean own = e.subscriptionId().isEmpty() || e.subscriptionId().get().equals(s.subscription());
            if (!own) continue;
            boolean valid = true;
            switch (e.type()) {
                case CONNECTION_REQUESTED -> { valid = Set.of("NEW", "DOWN", "RECONNECT").contains(connection); connection = "REQUESTED"; connected = false; authenticated = false; active = false; requested = false; }
                case CONNECTION_ESTABLISHED -> { valid = connection.equals("REQUESTED"); connection = "UP"; connected = true; authenticated = false; active = false; requested = false; }
                case AUTHENTICATED -> { valid = connected && !authenticated; authenticated = true; }
                case SUBSCRIPTION_REQUESTED -> { valid = authenticated && !active && !requested; requested = true; }
                case SUBSCRIPTION_ACKNOWLEDGED -> { valid = authenticated && requested; active = valid; requested = false; }
                case SUBSCRIPTION_REJECTED -> { valid = requested; active = false; requested = false;
                    fact(s, "FAIL", "SUBSCRIPTION_REJECTED", located, time, "Explicit rejection observed"); }
                case UNSUBSCRIPTION_REQUESTED -> { valid = active; connection = "CANCEL_PENDING"; }
                case UNSUBSCRIPTION_ACKNOWLEDGED -> { valid = connection.equals("CANCEL_PENDING"); active = false; connection = "UP"; }
                case CONNECTION_FAILED, DISCONNECTED -> {
                    valid = connected || connection.equals("REQUESTED"); connected = false; authenticated = false; active = false; requested = false; connection = "DOWN";
                    if (s.active(time)) fact(s, "FAIL", "UNEXPLAINED_DISCONNECT", located, time, e.type() + "; detail=" + e.detailCode().map(Enum::name).orElse("NONE"));
                }
                case RECONNECT_SCHEDULED -> { valid = connection.equals("DOWN"); connection = "RECONNECT"; }
                case HEARTBEAT -> valid = connected;
            }
            if (!valid) { fact(s, "FAIL", "LIFECYCLE_TRANSITION_ANOMALY", located, time, e.type().name()); active = false; }
            if (e.type() != FeedLifecycleEvent.Type.HEARTBEAT) evidence = List.of(located.reference());
        }
        if (cursor.isBefore(s.end())) states.add(new State(cursor, s.end(), active && authenticated, evidence));
        if (uncertain) {
            row(s, null, "INCOMPLETE", "LIFECYCLE_TIMELINE_UNCERTAIN", s.start(), s.end(), List.of(), "Regressed lifecycle times prevent reliable interval reconstruction; journal order was preserved");
            return List.of();
        }
        return states;
    }
    private void gap(Scope s, Window w, List<Instant> times, List<Map<String, Object>> refs, Long threshold, String code, String detail) {
        if (threshold == null) return;
        Instant previous = w.start();
        for (int i = 0; i <= times.size(); i++) {
            Instant next = i < times.size() ? times.get(i) : w.end();
            if (Duration.between(previous, next).compareTo(Duration.ofMillis(threshold)) > 0 || times.isEmpty()) {
                List<Map<String, Object>> evidence = new ArrayList<>();
                if (i > 0) evidence.add(refs.get(i - 1)); if (i < refs.size()) evidence.add(refs.get(i));
                row(s, w.id(), "FAIL", code, previous, next, evidence, detail + "; receive-time silence; strict greater-than threshold; market activity unknown");
            }
            previous = next;
        }
    }
    private static Instant safePlus(Instant t, long millis) {
        try { return t.plusMillis(millis); } catch (DateTimeException | ArithmeticException e) { return Instant.MAX; }
    }
    private static Instant earlier(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
    private static Instant later(Instant a, Instant b) { return a.isAfter(b) ? a : b; }
}
