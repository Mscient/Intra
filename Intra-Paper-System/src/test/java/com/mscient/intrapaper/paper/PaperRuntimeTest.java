package com.mscient.intrapaper.paper;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.marketdata.*;
import com.mscient.intrapaper.provenance.*;
import com.mscient.intrapaper.simulation.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Deterministic offline plumbing drills. No strategy-performance or G0 evidence. */
public final class PaperRuntimeTest {
    private static Path project, root;
    private static RunConfig config;
    private static ValidatedDataset data;
    private static LocalMarketDataProvider normal;
    private static int checks, serial;
    public static void main(String[] args) throws Exception {
        project = Path.of(args[0]); root = Path.of(args[1]); Files.createDirectories(root);
        config = RunConfig.load(project.resolve("examples/synthetic-run.properties"));
        data = ValidatedDataset.load(project.resolve("examples/synthetic-futures-bars.csv"), config);
        normal = LocalMarketDataProvider.recorded(data);
        parityAndRestart(); safety(); integrity(); processRestart(); l1(); remainingSafety();
        System.out.println("PaperRuntimeTest: " + checks + " synthetic plumbing checks passed; external_connection=false; G0=OPEN");
    }
    private static PaperRuntime open(Path path, LocalMarketDataProvider source) throws Exception {
        return new PaperRuntime(path, data, config, new SmaCrossoverStrategy(config), data.bars().getFirst().sessionDate(), source);
    }
    private static Path fresh(String name) { return root.resolve(name + "-" + serial++); }
    private static void parityAndRestart() throws Exception {
        String expected;
        var replay = new BacktestEngine().run(data, config);
        try (var paper = open(fresh("normal"), normal)) {
            ok(paper.state() == PaperRuntime.State.READY, "preopen ready");
            paper.run(Integer.MAX_VALUE); var result = paper.result(); expected = paper.report(); paper.verifyReport();
            ok(paper.state() == PaperRuntime.State.STOPPED, "session closes");
            ok(result.signals().equals(replay.signals()), "same existing strategy decisions");
            ok(result.fills().equals(replay.fills()), "same existing fills and position updates");
            ok(result.feeBreakdowns().equals(replay.feeBreakdowns()), "same fee accounting");
            ok(result.trades().equals(replay.trades()) && result.netPnlInr().equals(replay.netPnlInr()), "same trade/P&L accounting");
            ok(expected.contains("reconciliation=PASS") && expected.contains("external_connection=false"), "report verifies");
        }
        for (int cut : new int[]{0, 1, 6, 7, 17, 40, 60}) {
            Path path = fresh("restart");
            try (var paper = open(path, normal)) { paper.run(cut); }
            try (var paper = open(path, normal)) {
                ok(paper.consumedEvents() == cut, "verified restart cursor " + cut);
                paper.run(Integer.MAX_VALUE);
                ok(paper.report().equals(expected), "byte-identical report after restart " + cut);
                paper.verifyReport();
            }
        }
        Path twice = fresh("closed-resume");
        try (var paper = open(twice, normal)) { paper.run(Integer.MAX_VALUE); }
        byte[] journal = Files.readAllBytes(twice.resolve("paper.journal"));
        try (var paper = open(twice, normal)) { paper.run(Integer.MAX_VALUE); }
        ok(Arrays.equals(journal, Files.readAllBytes(twice.resolve("paper.journal"))), "no duplicate transactions after completed restart");
    }
    private static void safety() throws Exception {
        var first = normal.events().getFirst(); Instant time = first.receivedAt();
        for (var kind : List.of(PaperEvent.Kind.MALFORMED, PaperEvent.Kind.DISCONNECT, PaperEvent.Kind.OPERATOR_STOP)) {
            var events = new ArrayList<>(normal.events()); events.add(6, PaperEvent.control(1, kind, events.get(5).receivedAt(), 0));
            try (var p = open(fresh(kind.name()), renumber(events))) {
                p.run(Integer.MAX_VALUE);
                ok(p.result().fills().isEmpty(), kind + " blocks pending entry");
                ok(p.report().contains(kind == PaperEvent.Kind.DISCONNECT ? "FEED_DISCONNECTED" : kind.name()), "record stop reason " + kind);
            }
        }
        // Control at a clean bar boundary, then explicit recovery before the next observed opening.
        var events = new ArrayList<>(normal.events());
        events.addAll(6, List.of(PaperEvent.control(1, PaperEvent.Kind.DISCONNECT, events.get(5).receivedAt(), 0),
                PaperEvent.control(1, PaperEvent.Kind.RECONNECT, events.get(5).receivedAt(), 0),
                PaperEvent.control(1, PaperEvent.Kind.RECOVER, events.get(5).receivedAt(), 0)));
        try (var p = open(fresh("reconnect"), renumber(events))) {
            p.run(Integer.MAX_VALUE);
            ok(p.report().contains("recoveries=1") && p.state() == PaperRuntime.State.STOPPED, "explicit reconnect resumes clean boundary");
            ok(!p.result().fills().isEmpty(), "later fresh decisions execute after reconnect");
        }
        for (String mode : List.of("stale", "future", "malformed", "unknown", "out-of-order", "gap", "closed", "receive-reversal", "monotonic-reversal")) {
            List<PaperEvent> bad = new ArrayList<>();
            Instant receive = mode.equals("closed") ? time.minusSeconds(1) : time;
            Instant exchange = mode.equals("stale") ? receive.minusSeconds(100000) : mode.equals("future") ? receive.plusSeconds(1) : receive;
            String instrument = mode.equals("unknown") ? "UNKNOWN" : data.bars().getFirst().instrumentId();
            var quote = new MarketQuote(1, mode.equals("gap") ? 2 : 1, config.datasetSourceName(), instrument,
                    Optional.of(exchange), receive, 10, 2000, 100, mode.equals("malformed") ? 1999 : 2002, 100);
            bad.add(qevent(1, quote));
            if (mode.equals("out-of-order")) bad.add(qevent(2, new MarketQuote(1, 2, quote.source(), instrument,
                    Optional.of(time.minusSeconds(1)), time, 11, 2000, 100, 2002, 100)));
            if (mode.equals("receive-reversal")) bad.add(PaperEvent.control(2, PaperEvent.Kind.HEARTBEAT, time.minusNanos(1), 11));
            if (mode.equals("monotonic-reversal")) bad.add(PaperEvent.control(2, PaperEvent.Kind.HEARTBEAT, time, 9));
            try (var p = open(fresh(mode), new LocalMarketDataProvider(bad))) {
                p.run(Integer.MAX_VALUE);
                ok(p.state() == PaperRuntime.State.HALTED || p.state() == PaperRuntime.State.DEGRADED, "unsafe data stops " + mode);
                ok(p.result().orders().isEmpty() && p.result().fills().isEmpty(), "unsafe data cannot create simulated entries " + mode);
            }
        }
        var duplicate = new ArrayList<PaperEvent>(); duplicate.add(first); duplicate.add(first);
        duplicate.addAll(normal.events().subList(1, normal.events().size()));
        try (var p = open(fresh("duplicate"), new LocalMarketDataProvider(duplicate))) {
            p.run(Integer.MAX_VALUE);
            ok(p.report().contains("duplicates_ignored=1"), "identical event duplicate detected");
            ok(p.result().signals().size() == data.bars().size(), "duplicate has no strategy decision");
        }
        var gap = new ArrayList<>(normal.events()); gap.remove(5);
        try (var p = open(fresh("sequence-gap"), new LocalMarketDataProvider(gap))) {
            p.run(Integer.MAX_VALUE); ok(p.reason().equals("EVENT_SEQUENCE_GAP_OR_REORDER"), "broken sequence halts");
        }
        var future = normal.events().get(1);
        try (var p = open(fresh("future-bar"), new LocalMarketDataProvider(List.of(first,
                new PaperEvent(2, future.kind(), future.sourceTime(), time, 0, future.bar(), future.quote()))))) {
            p.run(Integer.MAX_VALUE); ok(p.result().signals().isEmpty(), "completed future bar unavailable to strategy");
        }
        Path operator = fresh("operator");
        try (var p = open(operator, normal)) { p.run(6); p.requestStop(); ok(p.result().fills().isEmpty(), "operator stops pending before fill"); }
        try (var p = open(operator, normal)) { p.run(Integer.MAX_VALUE); ok(p.reason().equals("OPERATOR_STOP"), "operator stop persists through restart"); }
        Path marker = fresh("marker");
        try (var p = open(marker, normal)) { p.run(6); }
        Files.writeString(marker.resolve("STOP"), "OPERATOR_STOP\n");
        try (var p = open(marker, normal)) { p.run(Integer.MAX_VALUE); ok(p.reason().equals("OPERATOR_STOP"), "operator marker polled before next event"); }
        // Explicit early close captures a pending intent and then a genuinely open position.
        for (int cut : new int[]{6, 7}) {
            var partial = new ArrayList<>(normal.events().subList(0, cut));
            partial.add(PaperEvent.control(cut + 1, PaperEvent.Kind.SESSION_CLOSE, partial.getLast().receivedAt(), 0));
            try (var p = open(fresh("early-close"), new LocalMarketDataProvider(partial))) {
                p.run(Integer.MAX_VALUE);
                ok(p.result().settlements().isEmpty(), "early close invents no settlement");
                ok(cut == 6 ? p.report().contains("pending_at_close=1") : p.result().finalPositionUnits() > 0, "explicit pending/open close state");
                p.verifyReport();
            }
        }
        // Existing risk gate rejects an otherwise valid entry.
        Path riskDir = fixture("risk");
        set(riskDir, "risk.max_notional_inr", "1");
        var c = RunConfig.load(riskDir.resolve("synthetic-run.properties"));
        var d = ValidatedDataset.load(riskDir.resolve("synthetic-futures-bars.csv"), c);
        try (var p = new PaperRuntime(fresh("risk-session"), d, c, new SmaCrossoverStrategy(c),
                d.bars().getFirst().sessionDate(), LocalMarketDataProvider.recorded(d))) {
            p.run(Integer.MAX_VALUE);
            ok(p.result().fills().isEmpty() && p.report().contains("MAX_NOTIONAL_INR"), "existing risk-limit rejection reused");
        }
        fails(() -> new PaperRuntime(fresh("closed-preopen"), data, config, new SmaCrossoverStrategy(config),
                data.bars().getFirst().sessionDate().plusDays(1), normal), "calendar preopen");
    }
    private static void integrity() throws Exception {
        for (String mode : List.of("checkpoint", "journal", "tail", "missing", "pending", "report")) {
            Path dir = fresh(mode); try (var p = open(dir, normal)) { p.run(10); }
            Path file = dir.resolve(mode.equals("report") ? "session-report.txt" : mode.equals("checkpoint") || mode.equals("missing") ? "checkpoint.txt" : "paper.journal");
            if (mode.equals("missing")) Files.delete(file);
            else if (mode.equals("pending")) Files.writeString(dir.resolve(".checkpoint.pending"), "partial");
            else if (mode.equals("tail")) Files.write(file, new byte[]{0}, StandardOpenOption.APPEND);
            else { byte[] bytes = Files.readAllBytes(file); bytes[bytes.length / 2] ^= 1; Files.write(file, bytes); }
            fails(() -> { try (var p = open(dir, normal)) { p.verifyReport(); } }, "detect " + mode + " changes");
        }
        Path source = fresh("changed-source"); try (var p = open(source, normal)) { p.run(10); }
        fails(() -> { try (var p = open(source, new LocalMarketDataProvider(normal.events().subList(0, 15)))) { } }, "changed frozen source");
        Path concurrent = fresh("lock");
        try (var p = open(concurrent, normal)) { fails(() -> { try (var other = open(concurrent, normal)) { } }, "single session owner"); }
        Path truncated = fresh("whole-frame-truncation");
        try (var p = open(truncated, normal)) { p.run(10); }
        List<RawMarketMessage> records = new ArrayList<>();
        RawCaptureJournalReader.replay(truncated.resolve("paper.journal"), records::add);
        Path replacement = truncated.resolve("shorter.journal");
        try (var writer = RawCaptureJournalWriter.create(replacement)) {
            for (var record : records.subList(0, records.size() - 2)) writer.append(record);
        }
        Files.move(replacement, truncated.resolve("paper.journal"), StandardCopyOption.REPLACE_EXISTING);
        fails(() -> { try (var p = open(truncated, normal)) { } }, "valid whole-frame truncation detected against checkpoint");
    }
    private static void processRestart() throws Exception {
        Path dir = fresh("process"); runCli(dir, "17"); runCli(dir, null);
        try (var p = open(dir, normal)) { p.verifyReport(); ok(p.state() == PaperRuntime.State.STOPPED, "separate JVM restart verifies ledger"); }
    }
    private static void runCli(Path dir, String count) throws Exception {
        String exe = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> args = new ArrayList<>(List.of(exe, "-cp", System.getProperty("java.class.path"),
                "com.mscient.intrapaper.cli.IntraPaperCli", "paper-run", project.resolve("examples/synthetic-futures-bars.csv").toString(),
                project.resolve("examples/synthetic-run.properties").toString(), dir.toString()));
        if (count != null) args.add(count);
        Path log = fresh("process-log");
        var process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Paper process timed out"); }
        ok(process.exitValue() == 0, "process: " + Files.readString(log));
    }
    private static void l1() throws Exception {
        Path dir = fixture("l1"); Path journal = dir.resolve("quotes.journal");
        var b = data.bars().getFirst();
        try (var writer = QuoteJournalWriter.create(journal)) {
            long seq = 0;
            Instant end = normal.events().getLast().receivedAt();
            for (Instant t = normal.events().getFirst().receivedAt(); t.isBefore(end); t = t.plusSeconds(30))
                writer.append(new MarketQuote(1, ++seq, config.datasetSourceName(), b.instrumentId(), Optional.of(t), t,
                        seq, 2000, 100, 2002, 100));
        }
        set(dir, "execution.model", "L1_PARTIAL_IOC"); set(dir, "execution.quote_journal_file", "quotes.journal");
        set(dir, "execution.decision_to_arrival_latency_ms", "0");
        set(dir, "execution.quote_source_name", config.datasetSourceName());
        set(dir, "execution.quote_maximum_age_ms", "120000");
        var c = RunConfig.load(dir.resolve("synthetic-run.properties"));
        var d = ValidatedDataset.load(dir.resolve("synthetic-futures-bars.csv"), c);
        try (var p = new PaperRuntime(fresh("l1-session"), d, c, new SmaCrossoverStrategy(c), b.sessionDate(), LocalMarketDataProvider.recorded(d))) {
            p.run(Integer.MAX_VALUE);
            ok(!p.result().fills().isEmpty(), "existing L1 IOC model exercised");
            ok(p.result().fills().stream().allMatch(f -> !f.quoteReceivedAt().isAfter(f.timestamp())), "L1 fills never use later quotes");
            ok(p.state() == PaperRuntime.State.STOPPED, "L1 completes session"); p.verifyReport();
        }
        set(dir, "execution.decision_to_arrival_latency_ms", "500");
        var delayedConfig = RunConfig.load(dir.resolve("synthetic-run.properties"));
        var delayedData = ValidatedDataset.load(dir.resolve("synthetic-futures-bars.csv"), delayedConfig);
        try (var p = new PaperRuntime(fresh("l1-latency"), delayedData, delayedConfig, new SmaCrossoverStrategy(delayedConfig),
                b.sessionDate(), LocalMarketDataProvider.recorded(delayedData))) {
            p.run(Integer.MAX_VALUE);
            ok(!p.result().fills().isEmpty(), "nonzero L1 latency executes on later observed quote");
            for (var fill : p.result().fills()) {
                var order = p.result().orders().stream().filter(o -> o.orderId().equals(fill.orderId())).findFirst().orElseThrow();
                ok(!fill.timestamp().isBefore(order.decisionTimestamp().plusMillis(500)), "fill waits for latency maturity");
                ok(!fill.quoteReceivedAt().isAfter(fill.timestamp()), "matured execution uses already received quote");
            }
            p.verifyReport();
        }
    }
    private static void remainingSafety() throws Exception {
        var first = normal.events().getFirst();
        try (var p = open(fresh("stale-heartbeat"), new LocalMarketDataProvider(List.of(first,
                PaperEvent.control(2, PaperEvent.Kind.HEARTBEAT, first.receivedAt().plusSeconds(61), 0))))) {
            p.run(Integer.MAX_VALUE); ok(p.reason().equals("STALE_FEED_HEARTBEAT"), "silence stops entries on heartbeat");
        }
        // No reconnect acknowledgement may clear an explicit disconnect by itself.
        var events = new ArrayList<>(normal.events());
        events.addAll(6, List.of(PaperEvent.control(1, PaperEvent.Kind.DISCONNECT, events.get(5).receivedAt(), 0),
                PaperEvent.control(1, PaperEvent.Kind.RECONNECT, events.get(5).receivedAt(), 0)));
        try (var p = open(fresh("no-auto-reconnect"), renumber(events))) {
            p.run(Integer.MAX_VALUE); ok(p.result().fills().isEmpty(), "reconnect alone cannot resume entries");
        }
        Path lossDir = fixture("loss"); set(lossDir, "risk.max_daily_loss_inr", "1");
        var c = RunConfig.load(lossDir.resolve("synthetic-run.properties"));
        var d = ValidatedDataset.load(lossDir.resolve("synthetic-futures-bars.csv"), c);
        try (var p = new PaperRuntime(fresh("loss-session"), d, c, new SmaCrossoverStrategy(c),
                d.bars().getFirst().sessionDate(), LocalMarketDataProvider.recorded(d))) {
            p.run(Integer.MAX_VALUE); ok(p.state() == PaperRuntime.State.HALTED && p.result().riskHalts() == 1, "daily-loss latch halts runtime");
            ok(p.result().finalPositionUnits() > 0 && p.result().settlements().isEmpty(), "risk halt records unliquidated exposure"); p.verifyReport();
        }
        Path badOutput = fresh("unwritable-journal"); Files.writeString(badOutput, "not a directory");
        fails(() -> { try (var p = open(badOutput, normal)) { } }, "journal writability preopen");
        Path changed = fixture("config-identity");
        var originalConfig = RunConfig.load(changed.resolve("synthetic-run.properties"));
        var originalData = ValidatedDataset.load(changed.resolve("synthetic-futures-bars.csv"), originalConfig);
        Path session = fresh("changed-config-session");
        try (var p = new PaperRuntime(session, originalData, originalConfig, new SmaCrossoverStrategy(originalConfig),
                originalData.bars().getFirst().sessionDate(), LocalMarketDataProvider.recorded(originalData))) { p.run(7); }
        set(changed, "risk.max_daily_loss_inr", "1001");
        var changedConfig = RunConfig.load(changed.resolve("synthetic-run.properties"));
        var changedData = ValidatedDataset.load(changed.resolve("synthetic-futures-bars.csv"), changedConfig);
        fails(() -> { try (var p = new PaperRuntime(session, changedData, changedConfig, new SmaCrossoverStrategy(changedConfig),
                changedData.bars().getFirst().sessionDate(), LocalMarketDataProvider.recorded(changedData))) { } }, "changed risk/config cannot resume");
        ok(MarketDataProvider.class.isSealed() && Arrays.equals(MarketDataProvider.class.getPermittedSubclasses(),
                new Class<?>[]{LocalMarketDataProvider.class}), "provider boundary admits local source only");
        try (var files = Files.list(project.resolve("src/main/java/com/mscient/intrapaper/paper"))) {
            for (Path file : files.toList()) {
                String source = Files.readString(file);
                ok(!source.contains("java.net.http") && !source.contains("java.net.Socket") && !source.contains("placeOrder("), "no network or broker routing in " + file.getFileName());
            }
        }
        List<List<Bar>> retained = new ArrayList<>();
        Strategy guarded = new Strategy() {
            private final Strategy delegate = new SmaCrossoverStrategy(config);
            public String id() { return "VISIBILITY_DRILL"; }
            public Map<String, String> parameters() { return delegate.parameters(); }
            public boolean supportsDurableCheckpoints() { return true; }
            public StrategySignal evaluate(List<Bar> bars, Instant decision) {
                ok(bars.stream().noneMatch(b -> b.timestamp().plusSeconds(60).isAfter(decision)), "strategy cannot see later bars");
                retained.add(bars); return delegate.evaluate(bars, decision);
            }
        };
        try (var p = new PaperRuntime(fresh("visibility"), data, config, guarded, data.bars().getFirst().sessionDate(), normal)) {
            p.run(Integer.MAX_VALUE);
            ok(retained.getFirst().size() == 1 && retained.getLast().size() == data.bars().size(), "retained decision history never grows");
            fails(() -> retained.getFirst().clear(), "strategy history immutable");
        }
    }
    private static PaperEvent qevent(long sequence, MarketQuote q) {
        return new PaperEvent(sequence, PaperEvent.Kind.QUOTE, q.exchangeTimestampUtc().orElse(q.receivedAtUtc()),
                q.receivedAtUtc(), q.receivedAtMonotonicNanos(), Optional.empty(), Optional.of(q));
    }
    private static LocalMarketDataProvider renumber(List<PaperEvent> input) {
        List<PaperEvent> result = new ArrayList<>(); long sequence = 0;
        for (var e : input) result.add(new PaperEvent(++sequence, e.kind(), e.sourceTime(), e.receivedAt(), e.receiveNanos(), e.bar(), e.quote()));
        return new LocalMarketDataProvider(result);
    }
    private static Path fixture(String name) throws Exception {
        Path dir = fresh(name); Files.createDirectories(dir);
        for (String file : List.of("synthetic-run.properties", "synthetic-futures-bars.csv", "synthetic-instrument-master.csv",
                "synthetic-session-calendar.csv", "synthetic-market-rule-regimes.csv", "synthetic-cost-schedule.csv"))
            Files.copy(project.resolve("examples").resolve(file), dir.resolve(file));
        return dir;
    }
    private static void set(Path dir, String key, String value) throws Exception {
        Path p = dir.resolve("synthetic-run.properties");
        Files.writeString(p, Files.readString(p).replaceAll("(?m)^" + java.util.regex.Pattern.quote(key) + "=.*$", "") + "\n" + key + "=" + value + "\n");
    }
    private static void ok(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private interface Checked { void run() throws Exception; }
    private static void fails(Checked action, String label) throws Exception {
        try { action.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError("Expected rejection: " + label);
    }
}
