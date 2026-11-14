package com.mscient.intrapaper.paper;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.simulation.BacktestEngine;
import com.mscient.intrapaper.simulation.SmaCrossoverStrategy;
import java.nio.file.*;
import java.util.*;

/** Offline acceptance of the local provider and the shared replay/paper core. No G4 evidence. */
public final class PaperRuntimeIntegrationTest {
    private static int checks;
    private static Path output;
    private static ValidatedDataset data;
    private static RunConfig config;
    private static LocalMarketDataProvider provider;

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[0]); output = Path.of(args[1]); Files.createDirectory(output);
        config = RunConfig.load(project.resolve("examples/synthetic-run.properties"));
        data = ValidatedDataset.load(project.resolve("examples/synthetic-futures-bars.csv"), config);
        provider = LocalMarketDataProvider.recorded(data);
        replayAndRestart(); controls(); corruption();
        System.out.println("PaperRuntimeIntegrationTest: " + checks + " synthetic local-provider checks passed; G0/G4 remain open");
    }

    private static PaperRuntime open(String name, LocalMarketDataProvider source) throws Exception {
        return new PaperRuntime(output.resolve(name), data, config, new SmaCrossoverStrategy(config),
                data.bars().getFirst().sessionDate(), source);
    }

    private static void replayAndRestart() throws Exception {
        String full;
        var historical = new BacktestEngine().run(data, config);
        try (var runtime = open("full", provider)) {
            runtime.run(Integer.MAX_VALUE); runtime.verifyReport();
            full = runtime.report();
            ok(runtime.state() == PaperRuntime.State.STOPPED, "session close stops local mode");
            ok(runtime.result().fills().equals(historical.fills()), "same observed opens produce identical historical/paper fills");
            ok(runtime.result().feesPaidInr().compareTo(historical.feesPaidInr()) == 0, "fees agree with historical core");
            ok(runtime.result().netPnlInr().compareTo(historical.netPnlInr()) == 0, "ending P&L agrees with historical core");
            ok(full.contains("performance_claim_eligible=false") && full.contains("external_connection=false"), "offline diagnostic classification");
        }
        try (var runtime = open("resumed", provider)) {
            runtime.run(17);
            ok(runtime.consumedEvents() == 17, "bounded processing");
            fails(() -> { try (var other = open("resumed", provider)) { } }, "exclusive session ownership");
        }
        try (var runtime = open("resumed", provider)) {
            ok(runtime.consumedEvents() == 17, "restart reconstructs acknowledged prefix");
            runtime.run(Integer.MAX_VALUE); runtime.verifyReport();
            ok(full.equals(runtime.report()), "restart report byte-identical to uninterrupted session");
        }
        try (var runtime = open("full", provider)) {
            runtime.run(Integer.MAX_VALUE);
            ok(full.equals(runtime.report()), "completed restart does not replay fills twice");
        }
        var changed = new ArrayList<>(provider.events()); changed.removeLast();
        fails(() -> { try (var ignored = open("full", new LocalMarketDataProvider(changed))) { } }, "changed input list cannot resume");
        for (PaperEvent e : provider.events()) if (e.kind() == PaperEvent.Kind.OPEN) {
            var b = e.bar().orElseThrow();
            ok(b.open().equals(b.high()) && b.open().equals(b.low()) && b.open().equals(b.close())
                    && b.volume() == null && b.openInterest() == null, "opening event has no future OHLC or volume");
        }
    }

    private static void controls() throws Exception {
        try (var runtime = open("operator-stop", provider)) {
            runtime.run(17);
            int fills = runtime.result().fills().size();
            Files.writeString(output.resolve("operator-stop/STOP"), "OPERATOR_STOP\n", StandardOpenOption.CREATE_NEW);
            runtime.run(Integer.MAX_VALUE);
            ok(runtime.state() == PaperRuntime.State.STOPPED && runtime.reason().equals("OPERATOR_STOP"), "operator stop applied");
            ok(runtime.result().fills().size() == fills, "operator stop produces no new fills");
            ok(runtime.report().contains("settlement=NOT_PERFORMED"), "stop does not invent settlement");
        }
        try (var runtime = open("operator-stop", provider)) {
            ok(runtime.state() == PaperRuntime.State.STOPPED, "stop survives restart");
        }
        var first = provider.events().getFirst();
        for (var kind : List.of(PaperEvent.Kind.DISCONNECT, PaperEvent.Kind.MALFORMED)) {
            var control = PaperEvent.control(1, kind, first.receivedAt(), first.receiveNanos());
            try (var runtime = open("control-" + kind, new LocalMarketDataProvider(List.of(control)))) {
                runtime.run(1);
                ok(runtime.state() == PaperRuntime.State.DEGRADED, "bad feed disables entries: " + kind);
                ok(runtime.result().fills().isEmpty(), "bad feed creates no fills: " + kind);
            }
        }
        var gap = new PaperEvent(2, first.kind(), first.sourceTime(), first.receivedAt(),
                first.receiveNanos(), first.bar(), first.quote());
        try (var runtime = open("gap", new LocalMarketDataProvider(List.of(gap)))) {
            runtime.run(1);
            ok(runtime.state() == PaperRuntime.State.HALTED, "sequence gap halts");
        }
        try (var runtime = open("duplicate", new LocalMarketDataProvider(List.of(first, first)))) {
            runtime.run(2);
            ok(runtime.report().contains("duplicates_ignored=1"), "identical repeated observation deduplicated");
            ok(runtime.result().fills().isEmpty(), "duplicate open causes no execution");
        }
        for (int offset : List.of(-1, 600)) {
            var late = new PaperEvent(1, first.kind(), first.sourceTime(), first.receivedAt().plusSeconds(offset),
                    first.receiveNanos(), first.bar(), first.quote());
            try (var runtime = open("clock-" + offset, new LocalMarketDataProvider(List.of(late)))) {
                runtime.run(1);
                ok(runtime.state() != PaperRuntime.State.RUNNING, "future or stale source rejected");
                ok(runtime.result().fills().isEmpty(), "invalid clock cannot fill");
            }
        }
    }

    private static void corruption() throws Exception {
        for (String name : List.of("checkpoint", "journal", "tail", "report")) {
            Path dir = output.resolve("corrupt-" + name);
            try (var runtime = open("corrupt-" + name, provider)) { runtime.run(17); }
            if (name.equals("report")) {
                Files.writeString(dir.resolve("session-report.txt"), "corrupt");
                try (var runtime = open("corrupt-" + name, provider)) {
                    fails(runtime::verifyReport, "tampered published report rejected");
                }
            } else {
                Path file = dir.resolve(name.equals("checkpoint") ? "checkpoint.txt" : "paper.journal");
                byte[] bytes = Files.readAllBytes(file);
                if (name.equals("tail")) bytes = Arrays.copyOf(bytes, bytes.length - 1);
                else bytes[bytes.length / 2] ^= 1;
                Files.write(file, bytes);
                fails(() -> { try (var runtime = open("corrupt-" + name, provider)) { } }, "corrupt " + name + " cannot resume");
            }
        }
    }
    private static void ok(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void fails(Checked action, String message) throws Exception {
        checks++;
        try { action.run(); } catch (java.io.IOException | IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Expected rejection: " + message);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
