package com.mscient.intrapaper.audit.feed;

import static com.mscient.intrapaper.audit.feed.AuditJson.*;
import com.mscient.intrapaper.marketdata.*;
import com.mscient.intrapaper.reference.DatedInstrumentMappingRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;

/** Synthetic only. Each case retains exact inputs and two byte-identical, reverified artifacts. */
public final class FeedAuditTest {
    private static final Instant START = Instant.parse("2026-11-06T10:00:00Z");
    private static int checks;
    private static Path output;
    private static boolean drill;
    private static final PrintStream QUIET = new PrintStream(OutputStream.nullOutputStream());
    record Message(long receive, Long event, Long sequence, String token, String instrument, String source, boolean malformed) { }
    static final class Fixture {
        final Path dir;
        final Map<String, Object> scope;
        final Map<String, Object> rule;
        final Map<String, Object> schedule;
        final Map<String, Object> policy;
        final Map<String, Object> binding;
        final Map<String, Object> manifest;
        List<Message> messages = new ArrayList<>(List.of(msg(0, 1), msg(10000, 2), msg(20000, 3)));
        List<FeedLifecycleEvent> lifecycle = new ArrayList<>(baseline());
        String mapping;
        boolean canonical;
        boolean quotes;
        Fixture(String name) throws IOException {
            dir = output.resolve("inputs").resolve(name); Files.createDirectories(dir);
            Files.writeString(dir.resolve("provenance.txt"), "SYNTHETIC caller declaration; not exchange or vendor evidence.\n");
            Files.writeString(dir.resolve("decode.txt"), "AUDIT_EVENT_V1 or CANONICAL_QUOTE_V1 synthetic decoder declaration; see guide.\n");
            scope = m("scope_id", "s1", "source", "SYNTHETIC", "product", "TEST", "channel", "L1", "connection_id", "c1", "subscription_id", "sub1",
                    "exchange", "TEST", "token", "123", "instrument_id", "I1", "session_date", "2026-11-06", "timezone", "UTC",
                    "session_start", ts(0), "session_end", ts(30000), "expected_types", List.of("DATA"), "heartbeat", "NONE",
                    "windows", List.of(window("active", "ACTIVE", 0, 30000)));
            rule = m("scope_id", "s1", "max_event_gap", threshold(10000), "stale_threshold", threshold(20000), "clock_skew", threshold(1000),
                    "heartbeat_gap", null, "sequence_reset", declared("REJECT"), "sequence_domain", declared("RAW_STREAM"));
            schedule = m("schema", "feed-audit-schedule/v1", "schedule_id", "synthetic-schedule", "revision", "1", "provenance", ref("provenance.txt"),
                    "interval_rule", "[start,end)", "scopes", List.of(scope));
            policy = m("schema", "feed-audit-policy/v1", "policy_id", "synthetic-policy", "revision", "1", "provenance", ref("provenance.txt"), "rules", List.of(rule));
            binding = m("scope_id", "s1", "decoder", "AUDIT_EVENT_V1", "decode_reference", ref("decode.txt"), "mapping", null, "quotes", null,
                    "timestamp_roles", m("event", "DECLARED_EVENT_UTC", "receive", "CAPTURE_RECEIVE_UTC", "lifecycle", "LIFECYCLE_OBSERVED_UTC", "monotonic", "JOURNAL_ORDER_DIAGNOSTIC_ONLY"));
            manifest = m("schema", "feed-audit-evidence/v1", "bindings", List.of(binding));
        }
        Map<String, Object> ref(String file) throws IOException { return m("path", file, "sha256", hash(Files.readAllBytes(dir.resolve(file)))); }
        Path write() throws IOException {
            List<MarketQuote> normalized = new ArrayList<>();
            try (var raw = RawCaptureJournalWriter.create(dir.resolve("capture.bin"))) {
                int index = 0;
                for (Message message : messages) {
                    long mono = ++index * 1000L;
                    byte[] payload;
                    if (canonical) {
                        MarketQuote q = new MarketQuote(1, index, message.source, message.instrument,
                                Optional.ofNullable(message.event).map(START::plusMillis), START.plusMillis(message.receive), mono, 10, 2, 11, 3);
                        normalized.add(q); payload = CanonicalQuoteEncoder.encode(q);
                    } else payload = bytes(m("schema", "feed-audit-event/v1", "product", "TEST", "channel", "L1", "exchange", "TEST",
                            "token", message.token, "instrument_id", message.instrument, "type", "DATA", "event_time", message.event == null ? null : ts(message.event)));
                    if (message.malformed) payload = new byte[]{(byte)0xff};
                    raw.append(new RawMarketMessage(1, message.source, "c1", "sub1", message.sequence == null ? OptionalLong.empty() : OptionalLong.of(message.sequence),
                            START.plusMillis(message.receive), mono, payload));
                }
            }
            try (var writer = FeedLifecycleJournalWriter.create(dir.resolve("lifecycle.bin"))) { for (var e : lifecycle) writer.append(e); }
            binding.put("raw", List.of(m("order", 0, "file", ref("capture.bin"))));
            binding.put("lifecycle", List.of(m("order", 0, "file", ref("lifecycle.bin"))));
            if (mapping != null) { Files.writeString(dir.resolve("mapping.csv"), mapping); binding.put("mapping", ref("mapping.csv")); }
            if (canonical) binding.put("decoder", "CANONICAL_QUOTE_V1");
            if (quotes) {
                try (var writer = QuoteJournalWriter.create(dir.resolve("quotes.bin"))) { for (var q : normalized) writer.append(q); }
                binding.put("quotes", ref("quotes.bin"));
            }
            return documents();
        }
        Path documents() throws IOException {
            Files.write(dir.resolve("schedule.json"), bytes(schedule)); Files.write(dir.resolve("policy.json"), bytes(policy));
            manifest.put("schedule", ref("schedule.json")); manifest.put("policy", ref("policy.json"));
            Files.write(dir.resolve("evidence.json"), bytes(manifest)); return dir.resolve("evidence.json");
        }
    }
    public static void main(String[] args) throws Exception {
        output = Path.of(args[0]); Files.createDirectories(output);
        drill = args.length > 1 && args[1].equals("--drill");
        if (args.length > 1 && args[1].equals("--examples")) { examples(); return; }
        accepted("complete-window", f -> {}, "PASS");
        accepted("whole-window-missing", f -> f.messages.clear(), "FAIL", "NO_MESSAGES_OBSERVED", "EVENT_GAP");
        accepted("partial-window-gap", f -> f.messages.remove(1), "FAIL", "EVENT_GAP");
        accepted("leading-gap", f -> f.messages = List.of(msg(11000, 1), msg(21000, 2)), "FAIL", "EVENT_GAP");
        accepted("trailing-gap", f -> f.messages = List.of(msg(0, 1), msg(10000, 2)), "FAIL", "EVENT_GAP");
        accepted("closed-and-boundary", FeedAuditTest::closed, "PASS", "SCHEDULE_EXCLUSION");
        accepted("session-end-exclusive", f -> f.messages.add(msg(30000, 4)), "FAIL", "RECEIVE_TIME_OUT_OF_WINDOW", "EVENT_TIME_OUT_OF_WINDOW");
        accepted("planned-pause", f -> { closed(f); for (Object w : list(f.scope.get("windows"))) if (str(object(w), "kind").equals("CLOSED")) object(w).put("kind", "PAUSE"); }, "PASS");
        accepted("fully-excluded-session", f -> { f.scope.put("windows", List.of(window("holiday", "EXCLUDED", 0, 30000))); f.messages.clear(); f.lifecycle.clear(); }, "PASS");
        accepted("event-in-closure", f -> { closed(f); f.messages = List.of(msg(0, 1), msg(10000, 2), msg(20000, 3)); }, "FAIL", "RECEIVE_TIME_OUT_OF_WINDOW");
        accepted("disconnect-reconnect", FeedAuditTest::reconnect, "FAIL", "UNEXPLAINED_DISCONNECT", "INACTIVE_SUBSCRIPTION");
        accepted("subscription-absent", f -> f.lifecycle = new ArrayList<>(baseline().subList(0, 3)), "FAIL", "INACTIVE_SUBSCRIPTION");
        accepted("subscription-rejected", f -> f.lifecycle.set(4, life(-1000, FeedLifecycleEvent.Type.SUBSCRIPTION_REJECTED)), "FAIL", "SUBSCRIPTION_REJECTED", "INACTIVE_SUBSCRIPTION");
        accepted("sequence-gap", f -> f.messages.set(1, msg(10000, 4)), "FAIL", "SEQUENCE_GAP", "OUT_OF_ORDER_SEQUENCE");
        accepted("sequence-reset", f -> f.messages.set(1, msg(10000, 0)), "FAIL", "SEQUENCE_RESET");
        accepted("sequence-reset-uncertain", f -> { f.messages.set(1, msg(10000, 0)); f.rule.put("sequence_reset", declared("INCOMPLETE")); }, "INCOMPLETE", "SEQUENCE_RESET");
        accepted("reset-allowed-with-reconnect", f -> { reconnect(f); f.messages = List.of(msg(0, 4), msg(10000, 0), msg(20000, 1)); f.rule.put("sequence_reset", declared("ALLOW_ZERO_AFTER_RECONNECT")); }, "FAIL", "SEQUENCE_RESET");
        accepted("reset-not-allowed-without-reconnect", f -> { f.messages.set(1, msg(10000, 0)); f.rule.put("sequence_reset", declared("ALLOW_ZERO_AFTER_RECONNECT")); }, "FAIL", "SEQUENCE_RESET");
        accepted("duplicate-sequence", f -> f.messages.set(1, msg(10000, 1)), "FAIL", "DUPLICATE_SEQUENCE");
        accepted("duplicate-payload", f -> f.messages.set(1, new Message(10000, 0L, 2L, "123", "I1", "SYNTHETIC", false)), "FAIL", "DUPLICATE_PAYLOAD");
        accepted("out-of-order-sequence", f -> f.messages = List.of(msg(0, 5), msg(10000, 3), msg(20000, 4)), "FAIL", "OUT_OF_ORDER_SEQUENCE");
        accepted("receive-regression", f -> f.messages = List.of(msg(10000, 1), new Message(9000, 11000L, 2L, "123", "I1", "SYNTHETIC", false), msg(20000, 3)), "FAIL", "RECEIVE_TIME_REGRESSION");
        accepted("event-regression", f -> f.messages.set(2, new Message(20000, 9000L, 3L, "123", "I1", "SYNTHETIC", false)), "FAIL", "EVENT_TIME_REGRESSION");
        accepted("skew-exact-boundary", f -> f.messages.set(1, new Message(10000, 11000L, 2L, "123", "I1", "SYNTHETIC", false)), "PASS");
        accepted("skew-over-boundary", f -> f.messages.set(1, new Message(10000, 11001L, 2L, "123", "I1", "SYNTHETIC", false)), "FAIL", "CLOCK_SKEW_EXCEEDED");
        accepted("stale-message", f -> f.messages.set(2, new Message(20000, -1L, 3L, "123", "I1", "SYNTHETIC", false)), "FAIL", "STALE_MESSAGE", "STALE_INTERVAL");
        accepted("stale-interval", f -> f.rule.put("stale_threshold", threshold(9999)), "FAIL", "STALE_INTERVAL");
        accepted("stale-exact-boundary", f -> f.rule.put("stale_threshold", threshold(10000)), "PASS");
        accepted("heartbeat-present", FeedAuditTest::heartbeat, "PASS");
        accepted("heartbeat-missing", f -> { heartbeat(f); f.lifecycle.removeIf(e -> e.type() == FeedLifecycleEvent.Type.HEARTBEAT); }, "FAIL", "HEARTBEAT_ABSENT");
        accepted("quiet-market-heartbeats-only", f -> { heartbeat(f); f.scope.put("expected_types", List.of()); f.messages.clear(); }, "PASS", "NO_MESSAGES_OBSERVED");
        accepted("connection-with-no-messages", f -> { heartbeat(f); f.messages.clear(); }, "FAIL", "NO_MESSAGES_OBSERVED");
        accepted("unknown-token", f -> f.messages.set(1, new Message(10000, 10000L, 2L, "999", "I1", "SYNTHETIC", false)), "FAIL", "UNKNOWN_INSTRUMENT_OR_TOKEN");
        accepted("mapping-valid", f -> f.mapping = mapping(false), "PASS", "MAPPING_RESOLVED");
        accepted("mapping-changed", f -> f.mapping = mapping(true), "FAIL", "MAPPING_CHANGED");
        accepted("mapping-uncovered", f -> f.mapping = mapping(false).replace("2026-11-06T09:00:00Z", "2026-11-06T10:00:01Z"), "FAIL", "MAPPING_FAILURE");
        accepted("decode-failure", f -> f.messages.set(1, new Message(10000, 10000L, 2L, "123", "I1", "SYNTHETIC", true)), "FAIL", "DECODE_FAILURE");
        accepted("event-time-missing", f -> f.messages.set(1, new Message(10000, null, 2L, "123", "I1", "SYNTHETIC", false)), "INCOMPLETE", "EVENT_TIME_UNAVAILABLE");
        accepted("sequence-missing", f -> f.messages.set(1, new Message(10000, 10000L, null, "123", "I1", "SYNTHETIC", false)), "INCOMPLETE", "SEQUENCE_UNAVAILABLE");
        accepted("sequence-explicitly-unobserved", f -> { f.messages.set(1, new Message(10000, 10000L, null, "123", "I1", "SYNTHETIC", false)); f.rule.put("sequence_domain", declared("NOT_OBSERVED")); }, "PASS");
        for (String field : List.of("max_event_gap", "stale_threshold", "clock_skew", "sequence_reset", "sequence_domain"))
            accepted("missing-" + field, f -> f.rule.remove(field), "INCOMPLETE", "POLICY_UNRESOLVED");
        accepted("missing-heartbeat-gap", f -> { heartbeat(f); f.rule.remove("heartbeat_gap"); }, "INCOMPLETE", "POLICY_UNRESOLVED");
        accepted("ambiguous-policy", f -> f.policy.put("rules", List.of(f.rule, f.rule)), "INCOMPLETE", "POLICY_UNRESOLVED");
        accepted("no-policy-rule", f -> f.policy.put("rules", List.of()), "INCOMPLETE", "POLICY_UNRESOLVED");
        accepted("lifecycle-regression", f -> f.lifecycle.add(life(-2000, FeedLifecycleEvent.Type.HEARTBEAT)), "INCOMPLETE", "LIFECYCLE_TIME_REGRESSION", "LIFECYCLE_TIMELINE_UNCERTAIN");
        accepted("quote-journal-crosscheck", f -> { f.canonical = true; f.quotes = true; f.scope.put("expected_types", List.of("QUOTE")); f.mapping = mapping(false); }, "PASS");
        rejected("source-mismatch", f -> f.messages.set(1, new Message(10000, 10000L, 2L, "123", "I1", "OTHER", false)));
        rejected("lifecycle-source-mismatch", f -> f.lifecycle.add(new FeedLifecycleEvent(1, "OTHER", "c1", FeedLifecycleEvent.Type.HEARTBEAT, Optional.empty(), START, 10000, Optional.empty())));
        rejected("unknown-schema", f -> f.schedule.put("schema", "feed-audit-schedule/v2"));
        rejected("unknown-field", f -> f.scope.put("default_heartbeat", 1000));
        rejected("implicit-hours", f -> f.scope.remove("session_start"));
        rejected("overlap", f -> f.scope.put("windows", List.of(window("a", "ACTIVE", 0, 20000), window("b", "ACTIVE", 10000, 30000))));
        rejected("unexplained-schedule-hole", f -> f.scope.put("windows", List.of(window("a", "ACTIVE", 1000, 30000))));
        rejected("wrong-timezone-offset", f -> f.scope.put("timezone", "Asia/Kolkata"));
        rejected("negative-policy", f -> f.rule.put("clock_skew", threshold(-1)));
        rejected("wrong-policy-units", f -> object(f.rule.get("clock_skew")).put("unit", "SECONDS"));
        for (String file : List.of("schedule.json", "policy.json", "capture.bin", "lifecycle.bin", "mapping.csv", "quotes.bin", "decode.txt", "provenance.txt")) tampered(file);
        malformedJournal(); artifacts(); determinism(); parts(); multiplexed();
        System.out.println("FeedAuditTest: " + checks + " checks passed; mode=" + (drill ? "packaged synthetic drill" : "acceptance") + "; evidence=" + output);
    }
    private static void accepted(String name, Consumer<Fixture> change, String expected, String... codes) throws Exception {
        Fixture f = new Fixture(name); change.accept(f); Path evidence = f.write();
        Map<String, Object> report = FeedAudit.run(evidence);
        check(report.get("status").equals(expected), name + " expected=" + expected + " actual=" + report.get("status") + " codes=" + codes(report));
        for (String code : codes) check(codes(report).contains(code), name + " missing reason " + code);
        if (name.equals("receive-regression")) check(!codes(report).contains("EVENT_TIME_REGRESSION"), "Receive order must not invent event regression");
        if (name.equals("event-regression")) check(!codes(report).contains("RECEIVE_TIME_REGRESSION"), "Event regression must not invent receive regression");
        if (name.equals("reset-allowed-with-reconnect")) check(list(report.get("rows")).stream().map(AuditJson::object).anyMatch(r -> r.get("reason_code").equals("SEQUENCE_RESET") && r.get("severity").equals("INFO")), "Allowed reset is diagnostic only");
        Path one = output.resolve("reports").resolve(name + "-1"), two = output.resolve("reports").resolve(name + "-2");
        int status = expected.equals("PASS") ? 0 : expected.equals("FAIL") ? 2 : 3;
        if (drill) check(FeedAuditCli.run(new String[]{"run", evidence.toString(), one.toString()}, QUIET, QUIET) == status, name + " run exit");
        else FeedAuditArtifacts.publish(evidence, one, report);
        FeedAuditArtifacts.publish(evidence, two, FeedAudit.run(evidence));
        for (String file : List.of("report.json", "artifact.json")) check(Arrays.equals(Files.readAllBytes(one.resolve(file)), Files.readAllBytes(two.resolve(file))), name + " byte-identical " + file);
        check(FeedAuditCli.run(new String[]{"verify", evidence.toString()}, QUIET, QUIET) == status, name + " verify exit");
        check(FeedAuditCli.run(new String[]{"verify-artifact", evidence.toString(), one.toString()}, QUIET, QUIET) == 0, name + " artifact verification");
        // Existing reader/regression APIs independently check journal chains and lifecycle reports.
        check(RawCaptureJournalReader.replay(f.dir.resolve("capture.bin"), ignored -> {}).eventCount() == f.messages.size(), name + " raw verifier");
        check(FeedLifecycleAnalyzer.analyze(f.dir.resolve("lifecycle.bin")).journal().eventCount() == f.lifecycle.size(), name + " lifecycle report");
        FeedCaptureReconciler.reconcile(f.dir.resolve("lifecycle.bin"), f.dir.resolve("capture.bin"), Duration.ofMillis(1000));
        checks++;
        System.out.println("PASS " + name + " => " + expected);
    }
    private static void rejected(String name, Consumer<Fixture> change) throws Exception {
        Fixture f = new Fixture(name); change.accept(f); Path evidence = f.write(); expectReject(evidence, name);
    }
    private static void expectReject(Path evidence, String name) {
        check(FeedAuditCli.run(new String[]{"run", evidence.toString(), output.resolve("rejected").resolve(name).toString()}, QUIET, QUIET) == 65, name + " rejected");
        check(!Files.exists(output.resolve("rejected").resolve(name)), name + " publishes nothing");
        System.out.println("PASS " + name + " => REJECTED");
    }
    private static void tampered(String file) throws Exception {
        Fixture f = new Fixture("tampered-" + file.replace('.', '-')); f.mapping = mapping(false); f.canonical = true; f.quotes = true; f.scope.put("expected_types", List.of("QUOTE"));
        Path evidence = f.write(); Files.write(f.dir.resolve(file), new byte[]{1}, StandardOpenOption.APPEND); expectReject(evidence, "tampered-" + file);
    }
    private static void malformedJournal() throws Exception {
        for (String file : List.of("capture.bin", "lifecycle.bin", "quotes.bin")) {
            Fixture f = new Fixture("malformed-" + file); f.canonical = true; f.quotes = true; f.scope.put("expected_types", List.of("QUOTE")); Path e = f.write();
            byte[] bytes = Files.readAllBytes(f.dir.resolve(file)); Files.write(f.dir.resolve(file), Arrays.copyOf(bytes, bytes.length - 1));
            if (file.equals("quotes.bin")) f.binding.put("quotes", f.ref(file));
            else f.binding.put(file.equals("capture.bin") ? "raw" : "lifecycle", List.of(m("order", 0, "file", f.ref(file))));
            f.documents(); expectReject(e, "malformed-verified-hash-" + file);
        }
    }
    private static void artifacts() throws Exception {
        Fixture f = new Fixture("artifact-tampering"); Path e = f.write(); Path dir = output.resolve("artifact-tampering"); FeedAuditArtifacts.publish(e, dir, FeedAudit.run(e));
        check(FeedAuditCli.run(new String[]{"run", e.toString(), dir.toString()}, QUIET, QUIET) == 65, "Existing output is preserved");
        byte[] original = Files.readAllBytes(dir.resolve("report.json"));
        Files.write(dir.resolve("report.json"), new byte[]{1}, StandardOpenOption.APPEND);
        check(FeedAuditCli.run(new String[]{"verify-artifact", e.toString(), dir.toString()}, QUIET, QUIET) == 65, "Report tampering rejected");
        Map<String, Object> report = object(com.mscient.intrapaper.data.StrictJsonParser.parse(new String(original, StandardCharsets.UTF_8)));
        report.put("rows", List.of()); byte[] changed = bytes(report); Files.write(dir.resolve("report.json"), changed);
        Map<String, Object> a = parse(dir.resolve("artifact.json")); a.put("report_sha256", hash(changed)); Files.write(dir.resolve("artifact.json"), bytes(a));
        check(FeedAuditCli.run(new String[]{"verify-artifact", e.toString(), dir.toString()}, QUIET, QUIET) == 65, "Rehashed row tampering rejected by recomputation");
        Files.write(dir.resolve("report.json"), original); a.put("report_sha256", hash(original)); a.put("code_sha256", "0".repeat(64)); Files.write(dir.resolve("artifact.json"), bytes(a));
        check(FeedAuditCli.run(new String[]{"verify-artifact", e.toString(), dir.toString()}, QUIET, QUIET) == 65, "Code tampering rejected");
        check(FeedAuditCli.run(new String[]{"verify"}, QUIET, QUIET) == 64, "Usage exit");
    }
    private static void determinism() throws Exception {
        Fixture f = new Fixture("determinism"); closed(f); f.mapping = mapping(true); Path e = f.write(); Map<String, Object> first = FeedAudit.run(e);
        Locale oldLocale = Locale.getDefault(); TimeZone oldZone = TimeZone.getDefault();
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Apia")); check(Arrays.equals(bytes(first), bytes(FeedAudit.run(e))), "Locale/timezone byte determinism"); }
        finally { Locale.setDefault(oldLocale); TimeZone.setDefault(oldZone); }
        List<?> windows = list(f.scope.get("windows")); List<Object> reversed = new ArrayList<>(windows); Collections.reverse(reversed); f.scope.put("windows", reversed);
        String csv = f.mapping; String[] lines = csv.split("\n"); f.mapping = lines[0] + "\n" + lines[2] + "\n" + lines[1] + "\n";
        Files.writeString(f.dir.resolve("mapping.csv"), f.mapping); f.binding.put("mapping", f.ref("mapping.csv")); f.documents();
        Map<String, Object> second = FeedAudit.run(e);
        check(json(first.get("rows")).equals(json(second.get("rows"))), "Row-order semantic determinism");
        check(!first.get("input_sha256").equals(second.get("input_sha256")), "Exact changed file bytes remain hash-distinct");
    }
    private static void parts() throws Exception {
        Fixture f = new Fixture("split-journals"); Path e = f.write();
        List<RawMarketMessage> all = new ArrayList<>(); RawCaptureJournalReader.replay(f.dir.resolve("capture.bin"), all::add);
        try (var a = RawCaptureJournalWriter.create(f.dir.resolve("part0.bin")); var b = RawCaptureJournalWriter.create(f.dir.resolve("part1.bin"))) {
            a.append(all.get(0)); b.append(all.get(1)); b.append(all.get(2));
        }
        f.binding.put("raw", List.of(m("order", 1, "file", f.ref("part1.bin")), m("order", 0, "file", f.ref("part0.bin")))); f.documents();
        check(FeedAudit.run(e).get("status").equals("PASS"), "Part array row order is irrelevant; explicit ordinal carries acquisition order");
        f.binding.put("raw", List.of(m("order", 0, "file", f.ref("part1.bin")), m("order", 0, "file", f.ref("part0.bin")))); f.documents(); expectReject(e, "duplicate-part-order");
    }
    private static void multiplexed() throws Exception {
        Fixture f = new Fixture("multiplexed"); f.messages = List.of(msg(0, 1), new Message(0, 0L, 2L, "456", "I2", "SYNTHETIC", false), msg(10000, 3),
                new Message(10000, 10000L, 4L, "456", "I2", "SYNTHETIC", false), msg(20000, 5), new Message(20000, 20000L, 6L, "456", "I2", "SYNTHETIC", false));
        Path e = f.write(); Map<String, Object> s2 = new TreeMap<>(f.scope); s2.put("scope_id", "s2"); s2.put("token", "456"); s2.put("instrument_id", "I2");
        Map<String, Object> p2 = new TreeMap<>(f.rule); p2.put("scope_id", "s2"); Map<String, Object> b2 = new TreeMap<>(f.binding); b2.put("scope_id", "s2");
        f.schedule.put("scopes", List.of(s2, f.scope)); f.policy.put("rules", List.of(p2, f.rule)); f.manifest.put("bindings", List.of(b2, f.binding)); f.documents();
        var report = FeedAudit.run(e); check(report.get("status").equals("PASS"), "Multiplexed stream sequences do not invent per-instrument gaps");
        f.schedule.put("scopes", List.of(f.scope, s2)); f.policy.put("rules", List.of(f.rule, p2)); f.manifest.put("bindings", List.of(f.binding, b2)); f.documents();
        check(json(report.get("rows")).equals(json(FeedAudit.run(e).get("rows"))), "Scope/policy/binding row order determinism");
    }
    private static void examples() throws Exception {
        for (String name : List.of("complete", "missing-window", "closed-pause", "heartbeat-absence", "missing-policy")) {
            Fixture f = new Fixture(name);
            if (name.equals("missing-window")) f.messages.clear();
            if (name.equals("closed-pause")) closed(f);
            if (name.equals("heartbeat-absence")) { f.scope.put("heartbeat", "LIFECYCLE"); f.rule.put("heartbeat_gap", threshold(10000)); }
            if (name.equals("missing-policy")) f.rule.remove("clock_skew");
            f.write(); System.out.println("Created synthetic example " + f.dir);
        }
    }
    private static void closed(Fixture f) { f.scope.put("windows", List.of(window("am", "ACTIVE", 0, 10000), window("closed", "CLOSED", 10000, 20000), window("pm", "ACTIVE", 20000, 30000))); f.messages = new ArrayList<>(List.of(msg(0, 1), msg(20000, 2))); }
    private static void heartbeat(Fixture f) { f.scope.put("heartbeat", "LIFECYCLE"); f.rule.put("heartbeat_gap", threshold(10000)); for (long t : new long[]{0, 10000, 20000}) f.lifecycle.add(life(t, FeedLifecycleEvent.Type.HEARTBEAT)); }
    private static void reconnect(Fixture f) {
        f.lifecycle.add(life(4000, FeedLifecycleEvent.Type.DISCONNECTED)); f.lifecycle.add(life(5000, FeedLifecycleEvent.Type.RECONNECT_SCHEDULED));
        f.lifecycle.add(life(6000, FeedLifecycleEvent.Type.CONNECTION_REQUESTED)); f.lifecycle.add(life(7000, FeedLifecycleEvent.Type.CONNECTION_ESTABLISHED));
        f.lifecycle.add(life(8000, FeedLifecycleEvent.Type.AUTHENTICATED)); f.lifecycle.add(life(8500, FeedLifecycleEvent.Type.SUBSCRIPTION_REQUESTED)); f.lifecycle.add(life(9000, FeedLifecycleEvent.Type.SUBSCRIPTION_ACKNOWLEDGED));
    }
    private static List<FeedLifecycleEvent> baseline() { return List.of(life(-5000, FeedLifecycleEvent.Type.CONNECTION_REQUESTED), life(-4000, FeedLifecycleEvent.Type.CONNECTION_ESTABLISHED), life(-3000, FeedLifecycleEvent.Type.AUTHENTICATED), life(-2000, FeedLifecycleEvent.Type.SUBSCRIPTION_REQUESTED), life(-1000, FeedLifecycleEvent.Type.SUBSCRIPTION_ACKNOWLEDGED)); }
    private static FeedLifecycleEvent life(long time, FeedLifecycleEvent.Type type) {
        boolean sub = type.name().startsWith("SUBSCRIPTION") || type.name().startsWith("UNSUBSCRIPTION");
        return new FeedLifecycleEvent(1, "SYNTHETIC", "c1", type, sub ? Optional.of("sub1") : Optional.empty(), START.plusMillis(time), (time + 10000) * 1000000, Optional.empty());
    }
    private static Message msg(long time, long seq) { return new Message(time, time, seq, "123", "I1", "SYNTHETIC", false); }
    private static String mapping(boolean changed) {
        return String.join(",", DatedInstrumentMappingRegistry.HEADERS) + "\n" + "m1,TEST,123,I1,I1,EQUITY,,,,2026-11-06T09:00:00Z," + (changed ? "2026-11-06T10:00:10Z" : "2026-11-06T11:00:00Z") + ",SYNTHETIC,provenance.txt," + hash("synthetic".getBytes(StandardCharsets.UTF_8)) + "\n"
                + (changed ? "m2,TEST,123,I2,I2,EQUITY,,,,2026-11-06T10:00:10Z,2026-11-06T11:00:00Z,SYNTHETIC,provenance.txt," + hash("synthetic".getBytes(StandardCharsets.UTF_8)) + "\n" : "");
    }
    private static String ts(long millis) { return START.plusMillis(millis).toString(); }
    private static Map<String, Object> window(String id, String kind, long start, long end) { return m("window_id", id, "kind", kind, "start", ts(start), "end", ts(end), "reason", "Synthetic explicit " + kind); }
    private static Map<String, Object> threshold(long value) { return m("value", value, "unit", "MILLISECONDS", "provenance", "synthetic caller choice"); }
    private static Map<String, Object> declared(String value) { return m("value", value, "unit", "ENUM", "provenance", "synthetic caller choice"); }
    private static Map<String, Object> m(Object... kv) { Map<String, Object> m = new TreeMap<>(); for (int i = 0; i < kv.length; i += 2) m.put((String)kv[i], kv[i + 1]); return m; }
    private static Set<String> codes(Map<String, Object> report) { Set<String> codes = new TreeSet<>(); for (Object r : list(report.get("rows"))) codes.add(str(object(r), "reason_code")); return codes; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
