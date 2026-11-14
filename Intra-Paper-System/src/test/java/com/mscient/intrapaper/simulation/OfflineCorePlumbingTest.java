package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.marketdata.*;
import com.mscient.intrapaper.provenance.RunConfig;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Synthetic plumbing only: these checks provide no data rights or performance evidence. */
public final class OfflineCorePlumbingTest {
    private static int checks;
    private static Path root, project;
    public static void main(String[] args) throws Exception {
        project = Path.of(args[0]); root = Path.of(args[1]); Files.createDirectories(root);
        csvAndConfiguration(); dataAndIdentity(); replayAndRecovery(); riskAndLifecycle(); quoteJournal(); rawAndLifecycleJournals(); l1Audit();
        System.out.println("OfflineCorePlumbingTest: " + checks + " synthetic plumbing checks passed");
    }
    private static Path fixture(String name) throws Exception {
        Path dir = root.resolve(name); Files.createDirectories(dir);
        for (String file : List.of("synthetic-run.properties", "synthetic-futures-bars.csv",
                "synthetic-instrument-master.csv", "synthetic-session-calendar.csv",
                "synthetic-market-rule-regimes.csv", "synthetic-cost-schedule.csv"))
            Files.copy(project.resolve("examples").resolve(file), dir.resolve(file));
        return dir;
    }
    private static RunConfig config(Path dir) throws Exception { return RunConfig.load(dir.resolve("synthetic-run.properties")); }
    private static ValidatedDataset data(Path dir) throws Exception {
        return ValidatedDataset.load(dir.resolve("synthetic-futures-bars.csv"), config(dir));
    }
    private static void set(Path dir, String key, String value) throws Exception {
        Path file = dir.resolve("synthetic-run.properties"); String text = Files.readString(file);
        String line = key + "=" + value;
        text = text.replaceAll("(?m)^" + java.util.regex.Pattern.quote(key) + "=.*$", "");
        Files.writeString(file, text + "\n" + line + "\n");
    }
    private static void cell(Path file, int rowIndex, String field, String value) throws Exception {
        CsvTable table = CsvTable.read(file); List<String> lines = new ArrayList<>(); lines.add(String.join(",", table.headers()));
        for (int i = 0; i < table.rows().size(); i++) {
            Map<String,String> row = new HashMap<>(table.rows().get(i)); if (i == rowIndex) row.put(field, value);
            lines.add(String.join(",", table.headers().stream().map(k -> CsvTable.escape(row.get(k))).toList()));
        }
        Files.writeString(file, String.join("\n", lines) + "\n");
    }
    private static void csvAndConfiguration() throws Exception {
        Path csv = root.resolve("strict.csv");
        Files.writeString(csv, "\uFEFFa,b\r\n\"हिन्दी,one\",\"two\"\"quotes\"\r\n");
        CsvTable table = CsvTable.read(csv);
        ok(table.rows().getFirst().get("a").equals("हिन्दी,one"), "BOM, CRLF and Unicode preserved");
        ok(table.sha256().equals(RunConfig.sha256(Files.readAllBytes(csv))), "exact byte hash");
        for (String invalid : List.of("a,a\nx,y\n", "a,b\nx\n", "a,b\n\"x\"tail,y\n", "a,b\nx\"z,y\n", "a,b\n\"x,y\n")) {
            Files.writeString(csv, invalid); fails(() -> CsvTable.read(csv), "malformed CSV");
        }
        Files.write(csv, new byte[]{'a','\n',(byte)0xc3,0x28,'\n'});
        fails(() -> CsvTable.read(csv), "malformed UTF-8");
        String[][] invalid = {{"dataset.synthetic_fixture","maybe"}, {"strategy.fast_bars","1"},
                {"risk.max_notional_inr","0"}, {"orders.allow_next_session","true"},
                {"dataset.timestamp_role","UNKNOWN"}, {"cost.status","VERIFIED"}};
        int i = 0;
        for (String[] pair : invalid) { Path dir = fixture("config-" + i++); set(dir,pair[0],pair[1]); fails(() -> config(dir), pair[0]); }
        Path denied = fixture("rights-flag"); set(denied,"dataset.synthetic_fixture","false");
        set(denied,"dataset.rights_status","AUTHORIZED_LOCAL_REPLAY"); set(denied,"dataset.rights_reference","self-declared");
        fails(() -> config(denied), "rights flag cannot substitute permission evidence");
    }
    private static void dataAndIdentity() throws Exception {
        Path dir = fixture("identity"); var c = config(dir); var d = data(dir); var strategy = new SmaCrossoverStrategy(c);
        ok(d.completeSessionCount() == 1 && d.bars().size() == 30, "complete supplied session");
        String id = RunIdentity.contentId(d,c,strategy);
        ok(id.equals(RunIdentity.contentId(data(dir),config(dir),strategy)), "deterministic identity");
        Path rules = dir.resolve("synthetic-market-rule-regimes.csv");
        cell(rules,0,"verification_status","UNVERIFIED");
        ok(!id.equals(RunIdentity.contentId(data(dir),c,strategy)), "changed rules bytes change run ID with unchanged config");
        String[][] invalid = {{"timestamp","2030-05-06T09:15:00+05:30"}, {"timestamp","2030-05-06T09:14:00+05:30"},
                {"timestamp","2030-05-06T09:16:00"}, {"source_token","UNMAPPED"}, {"lot_size","2"},
                {"open","100.01"}, {"raw_record_hash","bad"}, {"volume","-1"}, {"source","OTHER"}};
        int i = 0;
        for (String[] pair : invalid) {
            Path bad = fixture("bar-" + i++); cell(bad.resolve("synthetic-futures-bars.csv"),1,pair[0],pair[1]);
            fails(() -> data(bad), "invalid bar " + pair[0]);
        }
        Path closed = fixture("closed"); cell(closed.resolve("synthetic-session-calendar.csv"),0,"session_status","CLOSED");
        fails(() -> data(closed), "closed-session bar");
        Path mismatch = fixture("calendar-mismatch"); cell(mismatch.resolve("synthetic-session-calendar.csv"),0,"last_bar_timestamp","2030-05-06T09:43:00+05:30");
        fails(() -> data(mismatch), "calendar/rule boundary mismatch");
        Path overlap = fixture("cost-overlap"); Path costs = overlap.resolve("synthetic-cost-schedule.csv");
        Files.writeString(costs, Files.readString(costs) + Files.readAllLines(costs).get(1) + "\n");
        fails(() -> data(overlap), "overlapping costs");
        Path missing = fixture("cost-missing"); cell(missing.resolve("synthetic-cost-schedule.csv"),0,"effective_from","2030-05-07");
        fails(() -> data(missing), "missing cost coverage");
        Path gap = fixture("gap"); Path bars = gap.resolve("synthetic-futures-bars.csv");
        List<String> lines = new ArrayList<>(Files.readAllLines(bars)); lines.remove(10); Files.write(bars,lines);
        fails(() -> data(gap), "missing interval blocked by default");
        set(gap,"quality.allow_intrasession_gaps","true"); set(gap,"quality.max_missing_intervals_per_session","1");
        var diagnostic = data(gap); ok(diagnostic.completeSessionCount() == 0 && diagnostic.estimatedMissingIntervals() == 1, "explicit incomplete diagnostic");
        fails(() -> new BacktestEngine().run(diagnostic,config(gap)), "incomplete simulation blocked");
    }
    private static void replayAndRecovery() throws Exception {
        Path dir = fixture("replay"); set(dir,"recovery.checkpoint_interval_bars","2");
        var c = config(dir); var d = data(dir); var strategy = new SmaCrossoverStrategy(c);
        var engine = new BacktestEngine(); var expected = engine.run(d,c,strategy);
        ok(!expected.performanceClaimEligible() && expected.resultClassification().equals("SYNTHETIC_PLUMBING_ONLY"), "diagnostic classification");
        ok(expected.finalPositionUnits() == 0 && !expected.fills().isEmpty(), "completed synthetic trade");
        for (var fill : expected.fills()) {
            var order = expected.orders().stream().filter(o -> o.orderId().equals(fill.orderId())).findFirst().orElseThrow();
            ok(!fill.timestamp().isBefore(order.decisionTimestamp()), "next-open fill timing");
            ok(d.bars().stream().anyMatch(b -> b.timestamp().equals(fill.timestamp())), "fill uses supplied bar open");
        }
        String id = RunIdentity.contentId(d,c,strategy); Path checkpointRoot = dir.resolve("checkpoints");
        Strategy interrupted = new Strategy() {
            public String id() { return strategy.id(); }
            public Map<String,String> parameters() { return strategy.parameters(); }
            public boolean supportsDurableCheckpoints() { return true; }
            public StrategySignal evaluate(List<Bar> available, Instant time) {
                if (available.size() == 11) throw new IllegalStateException("synthetic interruption");
                return strategy.evaluate(available,time);
            }
        };
        try (var store = new BacktestCheckpointStore(checkpointRoot,id)) {
            fails(() -> engine.run(d,c,interrupted,store), "interruption before publication");
            fails(() -> new BacktestCheckpointStore(checkpointRoot,id), "concurrent checkpoint writer");
        }
        try (var store = new BacktestCheckpointStore(checkpointRoot,id)) {
            ok(engine.run(d,c,strategy,store).equals(expected), "resumed ledgers/accounting equal uninterrupted result");
        }
        var written = new RunArtifactWriter().write(dir.resolve("runs"),id,d,c,expected);
        ok(RunArtifactVerifier.verify(written.path()).verifiedFiles() == 13, "verify all output ledgers");
        ok(RunReplayVerifier.verify(dir.resolve("synthetic-futures-bars.csv"),c,written.path(),strategy).comparedFiles() == 14, "byte-for-byte replay");
        ok(OmsRunRecovery.restore(written.path()).finalPositionUnits() == 0, "OMS/accounting reconstruction");
        Path manifestFile = written.path().resolve("run-manifest.json");
        String manifest = Files.readString(manifestFile);
        ok(manifest.contains("\"schema_version\": 15") && manifest.contains("\"partial_fills_supported\": false"),
                "versioned bar fill manifest");
        for (String invalid : List.of(manifest + "trailing",
                manifest.replaceFirst("\\{", "{\"run_id\":\"" + id + "\","),
                manifest.replace("\"schema_version\": 15", "\"schema_version\": 999"),
                manifest.replace("\"schema_version\": 15", "\"schema_version\": 1.5"),
                manifest.replace("\"performance_claim_eligible\": false", "\"performance_claim_eligible\": true"),
                manifest.replace("\"performance_claim_eligible\": false", "\"performance_claim_eligible\": \"false\""),
                manifest.replace("\"output_file_sha256\": {", "\"output_file_sha256\": {\"ignored.bin\": null,"),
                manifest.replace("\"run_id\": \"" + id + "\"", "\"nested\": {\"run_id\": \"" + id + "\"}"))) {
            Files.writeString(manifestFile, invalid);
            fails(() -> RunArtifactVerifier.verify(written.path()), "malformed or ambiguous manifest rejected");
        }
        Files.writeString(manifestFile, manifest);
        fails(() -> new RunArtifactWriter().write(dir.resolve("runs"),id,d,c,expected), "no output overwrite");
        Path extra = written.path().resolve("extra.csv"); Files.writeString(extra,"unexpected");
        fails(() -> RunArtifactVerifier.verify(written.path()), "extra output blocked"); Files.delete(extra);
        Path fills = written.path().resolve("fills.csv"); byte[] original = Files.readAllBytes(fills);
        Files.writeString(fills,Files.readString(fills) + "tamper\n");
        fails(() -> RunArtifactVerifier.verify(written.path()), "changed ledger hash"); Files.write(fills,original);
        Path checkpoint;
        try (var files = Files.list(checkpointRoot.resolve(id))) { checkpoint = files.filter(p -> !p.getFileName().toString().startsWith(".")).findFirst().orElseThrow(); }
        byte[] bytes = Files.readAllBytes(checkpoint); bytes[bytes.length - 1] ^= 1; Files.write(checkpoint,bytes);
        try (var store = new BacktestCheckpointStore(checkpointRoot,id)) {
            fails(() -> engine.run(d,c,strategy,store), "corrupt checkpoint blocks recovery");
        }
        set(dir,"position.cross_session_policy","ALLOW_OVERNIGHT");
        Strategy alwaysLong = new Strategy() {
            public String id() { return "SYNTHETIC_ALWAYS_LONG"; }
            public Map<String,String> parameters() { return Map.of(); }
            public StrategySignal evaluate(List<Bar> available, Instant time) {
                Bar b = available.getLast(); return new StrategySignal(available.size(),time,b.sessionDate(),b.instrumentId(),1,"TEST",Map.of());
            }
        };
        ok(engine.run(data(dir),config(dir),alwaysLong).finalPositionUnits() == 1, "explicit overnight policy");
        set(dir,"position.cross_session_policy","REQUIRE_FLAT");
        fails(() -> engine.run(data(dir),config(dir),alwaysLong), "flat policy rejects residual position");
        set(dir,"position.cross_session_policy","AUTO_FLATTEN_SESSION_END");
        ok(engine.run(data(dir),config(dir),alwaysLong).finalPositionUnits() == 0, "reviewable final-bar-open flatten");
    }
    private static void riskAndLifecycle() throws Exception {
        Path dir = fixture("risk"); set(dir,"risk.max_entry_orders_per_session","1"); var c = config(dir);
        Bar bar = data(dir).bars().getFirst(); var gate = new RiskGate(c); gate.startSession(bar.sessionDate(),BigDecimal.ZERO);
        ok("MAX_POSITION_LOTS".equals(gate.entryRejectionReason(2,bar,new BigDecimal("100"))), "position risk rejection");
        ok("MAX_NOTIONAL_INR".equals(gate.entryRejectionReason(1,bar,new BigDecimal("10001"))), "notional risk rejection");
        ok("MAX_MARGIN_REQUIREMENT_INR".equals(gate.entryRejectionReason(1,bar,new BigDecimal("100"),new BigDecimal("1001"))), "margin input limit");
        ok(gate.registerEntryOrder(bar.timestamp()) == null, "first entry registered");
        ok("MAX_ENTRY_ORDERS_PER_SESSION".equals(gate.registerEntryOrder(bar.timestamp().plusSeconds(60))), "entry frequency cap");
        var snapshot = gate.snapshot(); var restored = new RiskGate(c); restored.restore(snapshot);
        ok(restored.snapshot().equals(snapshot), "risk restart preserves cap");
        ok(gate.markToMarket(new BigDecimal("-1000")).newlyHalted(), "loss halt at exact threshold");
        ok(!gate.markToMarket(new BigDecimal("-1001")).newlyHalted(), "loss halt latched");
        ok("SESSION_LOSS_GATE_HALTED".equals(gate.entryRejectionReason(1,bar,new BigDecimal("100"))), "halt rejects subsequent entry");
        OmsOrderStateMachine.requireTransition(OmsOrderState.WORKING,OmsOrderState.PARTIALLY_FILLED);
        OmsOrderStateMachine.requireTransition(OmsOrderState.PARTIALLY_FILLED,OmsOrderState.CANCELED);
        ok(OmsOrderStateMachine.remainingReservationUnits(OmsOrderState.PARTIALLY_FILLED,10,4) == 6, "partial reservation");
        ok(OmsOrderStateMachine.remainingReservationUnits(OmsOrderState.CANCELED,10,4) == 0, "canceled residual released");
        fails(() -> OmsOrderStateMachine.requireTransition(OmsOrderState.FILLED,OmsOrderState.WORKING), "terminal order cannot reopen");
        fails(() -> OmsOrderStateMachine.remainingReservationUnits(OmsOrderState.FILLED,10,4), "false full fill rejected");
    }
    private static MarketQuote quote(long sequence) {
        Instant time = Instant.parse("2030-05-06T03:45:00Z");
        return new MarketQuote(1,sequence,"SYNTHETIC","FUT",Optional.of(time),time,sequence,2000,10,2001,20);
    }
    private static void quoteJournal() throws Exception {
        Path journal = root.resolve("quotes.qjr");
        try (var writer = QuoteJournalWriter.create(journal)) {
            writer.append(quote(1)); fails(() -> writer.append(quote(1)), "duplicate quote sequence"); writer.append(quote(3));
        }
        var summary = QuoteJournalReader.replay(journal,q -> {});
        ok(summary.eventCount() == 2 && summary.sequenceGapCount() == 1 && summary.missingSequenceNumbers() == 1, "explicit sequence discontinuity");
        try (var writer = QuoteJournalWriter.resume(journal,summary)) { writer.append(quote(4)); }
        var expected = QuoteJournalReader.replay(journal,q -> {}); byte[] intact = Files.readAllBytes(journal);
        Path bad = root.resolve("corrupt.qjr");
        for (int length = 0; length < intact.length; length++) {
            Files.write(bad,Arrays.copyOf(intact,length));
            fails(() -> { try (var ignored = QuoteJournalWriter.resume(bad,expected)) {} }, "every truncated prefix fails anchored recovery");
        }
        for (int index : List.of(0,12,intact.length - 1)) {
            byte[] changed = intact.clone(); changed[index] ^= 1; Files.write(bad,changed);
            fails(() -> QuoteJournalReader.replay(bad,q -> {}), "framing/hash corruption");
        }
        try (var writer = QuoteJournalWriter.resume(journal,expected)) {
            boolean changed = false;
            try (var channel = java.nio.channels.FileChannel.open(journal,StandardOpenOption.WRITE)) {
                channel.truncate(intact.length - 1); changed = true;
            } catch (java.io.IOException lockedOnWindows) { ok(true,"mandatory lock prevents active file mutation"); }
            if (changed) {
                fails(() -> writer.append(quote(5)), "changed active file length");
                fails(() -> writer.append(quote(5)), "uncertain writer stays failed");
            }
        }
        var policy = new QuoteQualityPolicy(Duration.ofSeconds(1),Duration.ZERO);
        ok(policy.assess(quote(1),quote(1).receivedAtUtc().plusSeconds(2)).contains(QuoteQualityFlag.STALE_EXCHANGE_TIMESTAMP), "stale quote");
        ok(policy.assess(quote(1),quote(1).receivedAtUtc().minusSeconds(1)).contains(QuoteQualityFlag.FUTURE_EXCHANGE_TIMESTAMP), "future quote");
    }
    private static void ok(boolean value,String reason) { checks++; if (!value) throw new AssertionError(reason); }
    private static void l1Audit() throws Exception {
        Path dir = fixture("l1-audit"); var original = data(dir);
        try (var writer = QuoteJournalWriter.create(dir.resolve("quotes.qjr"))) {
            long sequence = 0;
            for (Bar bar : original.bars()) {
                long ticks = bar.open().divide(bar.tickSize()).longValueExact();
                writer.append(new MarketQuote(1,++sequence,"SYNTHETIC",bar.instrumentId(),Optional.of(bar.timestamp()),
                        bar.timestamp(),sequence,ticks - 1,1,ticks + 1,1));
            }
        }
        set(dir,"execution.model","L1_PARTIAL_IOC"); set(dir,"execution.quote_journal_file","quotes.qjr");
        set(dir,"execution.quote_source_name","SYNTHETIC"); set(dir,"execution.decision_to_arrival_latency_ms","0");
        set(dir,"execution.quote_maximum_age_ms","1000");
        var c = config(dir); var d = data(dir); var strategy = new SmaCrossoverStrategy(c);
        var result = new BacktestEngine().run(d,c,strategy); ok(!result.fills().isEmpty(),"L1 audit fixture fills");
        var run = new RunArtifactWriter().write(dir.resolve("runs"),RunIdentity.contentId(d,c,strategy),d,c,result);
        ok(Files.readString(run.path().resolve("run-manifest.json")).contains("\"partial_fills_supported\": true"),
                "partial IOC capability reported accurately");
        ok(OmsRunRecovery.restore(run.path()).finalPositionUnits() == result.finalPositionUnits(), "L1 OMS recovery with quote audit");
        for (var row : CsvTable.read(run.path().resolve("fills.csv")).rows()) {
            ok(row.get("quote_age_ms").equals("0") && row.get("quote_receive_age_ms").equals("0"),"explicit quote ages");
            ok(row.get("quote_spread_ticks").equals("2") && row.get("configured_latency_ms").equals("0"),"spread and latency audit");
            ok(row.get("execution_assumptions_source").equals("CALLER_CONFIG_UNVERIFIED:" + c.configSha256()),"assumption source bound to config");
        }
        ok(RunReplayVerifier.verify(dir.resolve("synthetic-futures-bars.csv"),c,run.path(),strategy).comparedFiles() == 14,"L1 audit deterministic rerun");
    }
    private static void rawAndLifecycleJournals() throws Exception {
        Instant time = Instant.parse("2030-05-06T03:45:00Z");
        Path raw = root.resolve("capture.raw"), lifecycle = root.resolve("feed.flj"), bad = root.resolve("bad-journal");
        RawMarketMessage message = new RawMarketMessage(1,"SYNTHETIC","C","S",OptionalLong.of(1),time,1,new byte[]{1,2,3});
        FeedLifecycleEvent event = new FeedLifecycleEvent(1,"SYNTHETIC","C",FeedLifecycleEvent.Type.CONNECTION_REQUESTED,
                Optional.empty(),time,1,Optional.empty());
        try (var writer = RawCaptureJournalWriter.create(raw)) { writer.append(message); }
        try (var writer = FeedLifecycleJournalWriter.create(lifecycle)) { writer.append(event); }
        var rawSummary = RawCaptureJournalReader.replay(raw,m -> ok(Arrays.equals(m.payload(),new byte[]{1,2,3}),"raw bytes preserved"));
        var feedSummary = FeedLifecycleJournalReader.replay(lifecycle,e -> ok(e.equals(event),"lifecycle fact preserved"));
        try (var writer = RawCaptureJournalWriter.resume(raw,rawSummary)) { writer.append(message); }
        try (var writer = FeedLifecycleJournalWriter.resume(lifecycle,feedSummary)) { writer.append(event); }
        rawSummary = RawCaptureJournalReader.replay(raw,m -> {}); feedSummary = FeedLifecycleJournalReader.replay(lifecycle,e -> {});
        var expectedRaw = rawSummary; var expectedFeed = feedSummary;
        for (Path source : List.of(raw,lifecycle)) {
            byte[] intact = Files.readAllBytes(source);
            for (int length = 0; length < intact.length; length++) {
                Files.write(bad,Arrays.copyOf(intact,length));
                if (source.equals(raw)) fails(() -> { try (var ignored = RawCaptureJournalWriter.resume(bad,expectedRaw)) {} },"raw truncated prefix");
                else fails(() -> { try (var ignored = FeedLifecycleJournalWriter.resume(bad,expectedFeed)) {} },"lifecycle truncated prefix");
            }
            intact[intact.length - 1] ^= 1; Files.write(bad,intact);
            if (source.equals(raw)) fails(() -> RawCaptureJournalReader.replay(bad,m -> {}),"raw chain corruption");
            else fails(() -> FeedLifecycleJournalReader.replay(bad,e -> {}),"lifecycle chain corruption");
        }
        ok(rawSummary.eventCount() == 2,"raw resume appended");
        ok(feedSummary.eventCount() == 2,"lifecycle resume appended");
    }
    private static void fails(Throwing action,String reason) throws Exception {
        checks++; try { action.run(); } catch (IllegalArgumentException | IllegalStateException | java.io.IOException | java.io.UncheckedIOException expected) { return; }
        throw new AssertionError("Expected fail-closed rejection: " + reason);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
