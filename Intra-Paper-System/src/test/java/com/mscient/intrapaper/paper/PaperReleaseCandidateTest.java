package com.mscient.intrapaper.paper;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.marketdata.*;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.simulation.*;
import com.mscient.intrapaper.evaluation.EvaluationPartitionReport;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Synthetic release boundaries: inspectable provenance, partial recovery and byte replay. */
public final class PaperReleaseCandidateTest {
    private static Path project, output;
    private static RunConfig config;
    private static ValidatedDataset data;
    private static LocalMarketDataProvider source;
    private static int checks;
    public static void main(String[] args) throws Exception {
        project = Path.of(args[0]); output = Path.of(args[1]); Files.createDirectory(output);
        config = RunConfig.load(project.resolve("examples/synthetic-run.properties"));
        data = ValidatedDataset.load(project.resolve("examples/synthetic-futures-bars.csv"), config);
        source = LocalMarketDataProvider.recorded(data);
        manifestsAndReplay(); partialRecovery(); admission(); storage();
        System.out.println("PaperReleaseCandidateTest: " + checks + " synthetic release checks passed; G0/G4 OPEN");
    }
    private static PaperRuntime open(Path dir, ValidatedDataset d, RunConfig c, LocalMarketDataProvider p) throws Exception {
        return new PaperRuntime(dir, d, c, new SmaCrossoverStrategy(c), d.bars().getFirst().sessionDate(), p);
    }
    private static void manifestsAndReplay() throws Exception {
        Path full = output.resolve("full"), resume = output.resolve("resume");
        try (var p = open(full, data, config, source)) {
            p.run(Integer.MAX_VALUE); p.verifyReport();
            ok(p.verifyReplay(new SmaCrossoverStrategy(config)) == 5, "five artifacts replay byte-for-byte");
        }
        String inputs = Files.readString(full.resolve("input-manifest.json"));
        var json = (Map<?, ?>) StrictJsonParser.parse(inputs);
        var hashes = (Map<?, ?>)json.get("input_file_sha256");
        for (var e : data.manifest().inputFileSha256().entrySet())
            ok(e.getValue().equals(hashes.get(e.getKey())), "every admitted input exposed: " + e.getKey());
        ok(config.configSha256().equals(hashes.get("run-config")), "properties byte hash exposed");
        ok(hashes.containsKey("frozen-local-events") && json.get("code_sha256").toString().matches("[0-9a-f]{64}"), "event/code hashes exposed");
        try (var p = open(resume, data, config, source)) {
            p.run(17);
            ok(p.verifyReplay(new SmaCrossoverStrategy(config)) == 5, "bounded prefix replay");
            ok(Files.readString(resume.resolve("session-manifest.json")).contains("\"session_complete\": false"), "prefix explicitly incomplete");
        }
        try (var p = open(resume, data, config, source)) { p.run(Integer.MAX_VALUE); p.verifyReport(); }
        for (String file : List.of("input-manifest.json", "checkpoint.txt", "session-report.txt", "session-manifest.json", "paper.journal"))
            ok(Files.mismatch(full.resolve(file), resume.resolve(file)) == -1, "resumed bytes equal uninterrupted: " + file);
        Path stopped = output.resolve("stop");
        try (var p = open(stopped, data, config, source)) {
            p.run(6); p.requestStop();
            ok(p.verifyReplay(new SmaCrossoverStrategy(config)) == 5, "operator stop replay");
            ok(p.result().fills().isEmpty(), "stop creates no fill");
        }
        for (String file : List.of("input-manifest.json", "session-manifest.json")) {
            Path dir = output.resolve("tamper-" + file);
            try (var p = open(dir, data, config, source)) { p.run(17); }
            Files.writeString(dir.resolve(file), Files.readString(dir.resolve(file)) + "{}\n");
            fails(() -> { try (var p = open(dir, data, config, source)) { p.verifyReport(); } }, "changed manifest rejected: " + file);
        }
        Path dir = output.resolve("promoted");
        try (var p = open(dir, data, config, source)) { p.run(17); }
        Path m = dir.resolve("session-manifest.json");
        Files.writeString(m, Files.readString(m).replace("\"performance_claim_eligible\": false", "\"performance_claim_eligible\": true"));
        fails(() -> { try (var p = open(dir, data, config, source)) { p.verifyReport(); } }, "eligibility promotion rejected");
        Path fixture = fixture("cost-binding"), session = output.resolve("cost-session");
        var c = RunConfig.load(fixture.resolve("synthetic-run.properties"));
        var d = ValidatedDataset.load(fixture.resolve("synthetic-futures-bars.csv"), c);
        try (var p = open(session, d, c, LocalMarketDataProvider.recorded(d))) { p.run(7); }
        Path cost = fixture.resolve("synthetic-cost-schedule.csv");
        Files.writeString(cost, Files.readString(cost).replace("1.00", "1.01"));
        var changed = ValidatedDataset.load(fixture.resolve("synthetic-futures-bars.csv"), c);
        ok(!d.manifest().inputFileSha256().equals(changed.manifest().inputFileSha256()), "cost bytes changed with fixed properties");
        fails(() -> { try (var p = open(session, changed, c, LocalMarketDataProvider.recorded(changed))) { } }, "cost mutation cannot resume");
    }
    private static void partialRecovery() throws Exception {
        Path fixture = fixture("partial");
        set(fixture, "strategy.lots", "2"); set(fixture, "risk.max_position_lots", "2");
        set(fixture, "risk.max_notional_inr", "10000000"); set(fixture, "risk.max_margin_inr", "10000");
        set(fixture, "execution.model", "L1_PARTIAL_IOC");
        set(fixture, "execution.quote_journal_file", "quotes.journal");
        set(fixture, "execution.decision_to_arrival_latency_ms", "0");
        set(fixture, "execution.quote_source_name", config.datasetSourceName());
        set(fixture, "execution.quote_maximum_age_ms", "120000");
        var b = data.bars().getFirst();
        try (var writer = QuoteJournalWriter.create(fixture.resolve("quotes.journal"))) {
            long seq = 0;
            for (Instant t = source.events().getFirst().receivedAt(); t.isBefore(source.events().getLast().receivedAt()); t = t.plusSeconds(30))
                writer.append(new MarketQuote(1, ++seq, config.datasetSourceName(), b.instrumentId(), Optional.of(t), t,
                        seq, 2000, b.lotSize(), 2002, b.lotSize()));
        }
        var c = RunConfig.load(fixture.resolve("synthetic-run.properties"));
        var d = ValidatedDataset.load(fixture.resolve("synthetic-futures-bars.csv"), c);
        var events = new ArrayList<>(LocalMarketDataProvider.recorded(d).events());
        events.add(30, events.getFirst()); // Exact old event redelivered after the first fill/restart; original clocks retained.
        var provider = new LocalMarketDataProvider(events);
        Path full = output.resolve("partial-full"), resume = output.resolve("partial-resume");
        try (var p = open(full, d, c, provider)) { p.run(Integer.MAX_VALUE); p.verifyReport(); }
        int prefix;
        try (var p = open(resume, d, c, provider)) {
            while (p.result().fills().isEmpty() && p.step()) { }
            prefix = p.consumedEvents(); p.run(0);
            ok(prefix < 30, "checkpoint precedes delayed duplicate");
            ok(p.result().finalPositionUnits() == b.lotSize(), "one displayed lot leaves a partial position");
            ok(p.result().orders().getFirst().requestedUnits() == 2 * b.lotSize(), "partial intent requested two lots");
            ok(p.verifyReplay(new SmaCrossoverStrategy(c)) == 5, "partial-fill checkpoint replay");
        }
        try (var p = open(resume, d, c, provider)) {
            ok(p.consumedEvents() == prefix && p.result().finalPositionUnits() == b.lotSize(), "partial basis/fees/position restored");
            p.run(Integer.MAX_VALUE); p.verifyReport();
            ok(p.report().contains("duplicates_ignored=1"), "old duplicate ignored without reversing decision clock");
            ok(p.state() == PaperRuntime.State.STOPPED, "duplicate delivery permits deterministic completion");
            ok(p.verifyReplay(new SmaCrossoverStrategy(c)) == 5, "partial session replay");
        }
        for (String file : List.of("paper.journal", "session-report.txt", "checkpoint.txt", "session-manifest.json"))
            ok(Files.mismatch(full.resolve(file), resume.resolve(file)) == -1, "partial resume no duplicate effects: " + file);
    }
    private static void admission() throws Exception {
        Path f = fixture("incomplete"), bars = f.resolve("synthetic-futures-bars.csv");
        var lines = new ArrayList<>(Files.readAllLines(bars)); lines.remove(3); Files.write(bars, lines);
        set(f, "quality.allow_intrasession_gaps", "true"); set(f, "quality.max_missing_intervals_per_session", "1");
        var c = RunConfig.load(f.resolve("synthetic-run.properties")); var d = ValidatedDataset.load(bars, c);
        ok(d.completeSessionCount() == 0, "diagnostic inspection preserves incomplete data");
        fails(() -> new BacktestEngine().paperSession(d, c, new SmaCrossoverStrategy(c)), "paper core admission rejects gaps");
        fails(() -> new BacktestEngine().run(d, c), "historical admission rejects gaps");
        fails(() -> { try (var p = open(output.resolve("gap-session"), d, c, LocalMarketDataProvider.recorded(d))) { } }, "runtime preopen rejects gaps");
        var valid = new BacktestEngine().run(data, config);
        fails(() -> new RunArtifactWriter().write(output.resolve("gap-publish"), "0".repeat(24), d, c, valid), "diagnostic data cannot publish simulated artifacts");
        fails(() -> EvaluationPartitionReport.create(c, d, valid.sessionAudits()), "diagnostic data cannot score");
        ok(!Files.exists(output.resolve("gap-session/paper.journal")) && !Files.exists(output.resolve("gap-publish")), "rejected inputs create no session or run artifacts");
    }
    private static void storage() throws Exception {
        for (String file : List.of("input-manifest.json", "checkpoint.txt")) {
            Path active = output.resolve("active-tamper-" + file);
            try (var p = open(active, data, config, source)) {
                p.run(6); Files.writeString(active.resolve(file), "changed while open");
                fails(p::verifyReport, "active anchor mutation detected: " + file);
                fails(p::step, "changed anchor cannot be overwritten by a new event: " + file);
                ok(p.result().fills().isEmpty() && p.state() == PaperRuntime.State.HALTED, "changed anchor halts before pending fill");
            }
        }
        Path dir = output.resolve("storage");
        try (var p = open(dir, data, config, source)) {
            p.run(6); Files.writeString(dir.resolve(".checkpoint.pending"), "ambiguous");
            fails(p::step, "uncertain checkpoint stops instance");
            int fills = p.result().fills().size(); fails(p::step, "failed instance cannot continue");
            ok(p.result().fills().size() == fills && p.state() == PaperRuntime.State.HALTED, "storage failure latches halt");
        }
        fails(() -> { try (var p = open(dir, data, config, source)) { } }, "ambiguous transaction cannot resume");
        Path publish = output.resolve("publication-failure");
        try (var p = open(publish, data, config, source)) {
            p.run(6); Files.writeString(publish.resolve(".report.pending"), "ambiguous");
            fails(() -> p.run(0), "report storage failure surfaced");
            fails(p::step, "publication failure also disables later fills");
        }
    }
    private static Path fixture(String name) throws Exception {
        Path dir = output.resolve(name); Files.createDirectory(dir);
        for (String file : List.of("synthetic-run.properties", "synthetic-futures-bars.csv", "synthetic-instrument-master.csv",
                "synthetic-session-calendar.csv", "synthetic-market-rule-regimes.csv", "synthetic-cost-schedule.csv"))
            Files.copy(project.resolve("examples").resolve(file), dir.resolve(file));
        return dir;
    }
    private static void set(Path dir, String key, String value) throws Exception {
        Path p = dir.resolve("synthetic-run.properties");
        Files.writeString(p, Files.readString(p).replaceAll("(?m)^" + java.util.regex.Pattern.quote(key) + "=.*$", "") + "\n" + key + "=" + value + "\n");
    }
    private static void ok(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void fails(Checked action, String message) throws Exception {
        checks++; try { action.run(); } catch (java.io.IOException | IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Expected rejection: " + message);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
