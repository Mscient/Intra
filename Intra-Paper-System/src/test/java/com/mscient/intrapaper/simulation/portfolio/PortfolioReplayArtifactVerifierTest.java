package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.data.SynchronizedBarFrameBuilder;
import com.mscient.intrapaper.provenance.RunConfig;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.mscient.intrapaper.simulation.portfolio.PortfolioSettlementFixtures.*;

/** Real v3 artifacts, arithmetic tampering, and frozen-input deterministic replay. */
public final class PortfolioReplayArtifactVerifierTest {
    private PortfolioReplayArtifactVerifierTest() { }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("portfolio-settlement-artifacts-");
        try {
            create(root); var replay = replay(root);
            var written = new PortfolioReplayArtifactWriter().write(root.resolve("runs"), replay.dataset(), replay.result());
            require(PortfolioReplayArtifactVerifier.verify(written.path()).outputSha256().size() == 8, "verify every v3 output");
            PortfolioReplayDeterminismVerifier.verify(root.resolve("inputs.csv"), root.resolve("portfolio.properties"), written.path());
            failure(() -> new PortfolioReplayArtifactWriter().write(root.resolve("runs"), replay.dataset(), replay.result()));
            for (var change : Map.of("signed_units_settled", "2", "released_basis_inr", "1", "signed_proceeds_inr", "1",
                    "realized_gross_pnl_inr", "999", "source_reference", "", "event_source_file_sha256", "b".repeat(64), "total_fee_inr", "999").entrySet())
                tamper(written.path(), "settlements.csv", change.getKey(), change.getValue());
            for (var change : Map.of("calculation_base", "2", "rate", "999", "charge_inr", "999", "gst_taxable_base_inr", "0",
                    "gst_inr", "999", "event_id", "MISSING", "schedule_source_file_sha256", "b".repeat(64)).entrySet())
                tamper(written.path(), "settlement-cost-lines.csv", change.getKey(), change.getValue());
            tamper(written.path(), "execution-fees.csv", "execution_id", "ORPHAN");
            tamper(written.path(), "equity.csv", "total_fees_inr", "999");
            tamper(written.path(), "equity.csv", "signed_units", "1");
            Path settlements = written.path().resolve("settlements.csv"), manifest = written.path().resolve("run-manifest.properties");
            String original = Files.readString(settlements), originalManifest = Files.readString(manifest);
            cell(settlements, 0, "released_basis_inr", "100"); cell(settlements, 0, "realized_gross_pnl_inr", "10");
            refresh(written.path(), "settlements.csv"); failure(() -> PortfolioReplayArtifactVerifier.verify(written.path()));
            Files.writeString(settlements, original); Files.writeString(manifest, originalManifest);
            Files.writeString(settlements, original + "tamper\n"); failure(() -> PortfolioReplayArtifactVerifier.verify(written.path())); Files.writeString(settlements, original);
            Files.writeString(manifest, originalManifest.replace("settlement_instruments=F,O", "settlement_instruments=F"));
            failure(() -> PortfolioReplayArtifactVerifier.verify(written.path())); Files.writeString(manifest, originalManifest);
            Files.writeString(written.path().resolve("unexpected.csv"), "extra"); failure(() -> PortfolioReplayArtifactVerifier.verify(written.path())); Files.delete(written.path().resolve("unexpected.csv"));
            Path charges = written.path().resolve("settlement-cost-lines.csv"); String savedCharges = Files.readString(charges);
            Files.delete(charges); failure(() -> PortfolioReplayArtifactVerifier.verify(written.path())); Files.writeString(charges, savedCharges);
            Path events = root.resolve("settlement-events.csv"); String savedEvents = Files.readString(events);
            cell(events, 0, "source_reference", "changed-caller-source");
            require(!replay(root).result().replayFingerprintSha256().equals(written.runId()), "source must change identity");
            failure(() -> PortfolioReplayDeterminismVerifier.verify(root.resolve("inputs.csv"), root.resolve("portfolio.properties"), written.path()));
            Files.writeString(events, savedEvents);
            Path costs = root.resolve("settlement-costs.csv"); String savedCosts = Files.readString(costs);
            cell(costs, 0, "charge_rounding_mode", "HALF_EVEN");
            require(!replay(root).result().replayFingerprintSha256().equals(written.runId()), "rounding must change identity");
            failure(() -> PortfolioReplayDeterminismVerifier.verify(root.resolve("inputs.csv"), root.resolve("portfolio.properties"), written.path())); Files.writeString(costs, savedCosts);
            cell(events, 1, "settlement_timestamp", "2030-05-06T09:20:00+05:30");
            var staggered = replay(root); require(staggered.result().snapshots().size() == 10, "distinct event times have separate snapshots");
            var staggeredRun = new PortfolioReplayArtifactWriter().write(root.resolve("runs"), staggered.dataset(), staggered.result()); PortfolioReplayArtifactVerifier.verify(staggeredRun.path());
            Files.writeString(events, savedEvents);
            signedPartialEngine(root, replay);
            var eventLines = savedEvents.lines().toList();
            Files.writeString(events, eventLines.get(0) + "\n" + eventLines.get(2) + "\n" + eventLines.get(1) + "\n");
            var reordered = replay(root);
            require(!reordered.result().replayFingerprintSha256().equals(written.runId()), "physical row order changes provenance");
            require(reordered.result().snapshots().equals(replay.result().snapshots()), "row order must not change accounting");
            Files.writeString(events, savedEvents);
            noSettlementRegression(root);
        } finally { cleanup(root); }
        System.out.println("PortfolioReplayArtifactVerifier v3 publication, tamper, signed partial, and deterministic replay checks passed");
    }
    private static void signedPartialEngine(Path root, PortfolioReplayCsvRunner.Replay loaded) throws Exception {
        PortfolioStrategy strategy = new PortfolioStrategy() {
            public String id() { return "synthetic-signed"; }
            public Map<String, String> parameters() { return Map.of(); }
            public PortfolioTarget evaluate(List<SynchronizedBarFrameBuilder.Frame> frames, Instant time, PortfolioAccountingLedger.Snapshot snapshot) {
                return new PortfolioTarget(Map.of("F", frames.size() == 3 ? -2 : 0, "O", frames.size() == 3 ? 2 : 0), "synthetic-target");
            }
        };
        var full = new OpeningPriceIocFillModel(0, BigDecimal.ZERO);
        PortfolioFillModel partial = new PortfolioFillModel() {
            public String id() { return "synthetic-partial"; }
            public Map<String, String> parameters() { return Map.of(); }
            public Optional<Fill> fill(PortfolioReplayEngine.OrderIntent intent, OpeningMarketPoint market) {
                return full.fill(intent, market).map(f -> new Fill(1, f.price(), BigDecimal.ZERO));
            }
        };
        var engine = new PortfolioReplayEngine(loaded.result().config(), strategy, partial, Optional.empty(),
                Optional.of(PortfolioSettlementEvents.load(root.resolve("settlement-events.csv"))), Optional.empty());
        failure(() -> engine.run(loaded.dataset().frames()));
        var result = engine.run(loaded.dataset());
        require(result.settlements().getFirst().signedUnits() == -1 && result.settlements().getLast().signedUnits() == 1, "settle actual partial signed fills");
        equal("-15", result.snapshots().getLast().netPnlInr());
        var run = new PortfolioReplayArtifactWriter().write(root.resolve("runs"), loaded.dataset(), result); PortfolioReplayArtifactVerifier.verify(run.path());
    }
    private static void noSettlementRegression(Path root) throws Exception {
        for (String name : List.of("master.csv", "future.csv", "option.csv")) {
            Path path = root.resolve(name); int count = CsvTable.read(path).rows().size();
            for (int i = 0; i < count; i++) cell(path, i, "expiry", "2030-12-26");
        }
        Files.writeString(root.resolve("portfolio.properties"), BASE_SETTINGS.replace("REQUIRE_FLAT", "ALLOW_OVERNIGHT"));
        var replay = replay(root); require(replay.result().settlements().isEmpty(), "no end-of-data settlement");
        require(replay.result().snapshots().getLast().positions().values().stream().allMatch(p -> p.signedUnits() == 1), "unexpired positions remain open");
        var run = new PortfolioReplayArtifactWriter().write(root.resolve("runs"), replay.dataset(), replay.result());
        PortfolioReplayArtifactVerifier.verify(run.path());
        PortfolioReplayDeterminismVerifier.verify(root.resolve("inputs.csv"), root.resolve("portfolio.properties"), run.path());
        Path events = root.resolve("settlement-events.csv"); cell(events, 0, "expiry", "2030-12-26");
        Files.writeString(events, String.join("\n", Files.readAllLines(events).subList(0, 2)) + "\n");
        Files.writeString(root.resolve("portfolio.properties"), BASE_SETTINGS.replace("REQUIRE_FLAT", "ALLOW_OVERNIGHT")
                + "portfolio.settlement_policy=EXPLICIT_FINAL_SESSION\nportfolio.settlement_instruments=F\nportfolio.settlement_events_csv=settlement-events.csv\n");
        var subset = replay(root); var positions = subset.result().snapshots().getLast().positions();
        require(positions.get("F").signedUnits() == 0 && positions.get("O").signedUnits() == 1, "settle only the explicit subset");
        var subsetRun = new PortfolioReplayArtifactWriter().write(root.resolve("runs"), subset.dataset(), subset.result()); PortfolioReplayArtifactVerifier.verify(subsetRun.path());
    }
    private static void tamper(Path run, String file, String key, String value) throws Exception {
        Path path = run.resolve(file), manifest = run.resolve("run-manifest.properties");
        String original = Files.readString(path), savedManifest = Files.readString(manifest);
        try { cell(path, 0, key, value); refresh(run, file); failure(() -> PortfolioReplayArtifactVerifier.verify(run)); }
        finally { Files.writeString(path, original); Files.writeString(manifest, savedManifest); }
    }
    private static void refresh(Path run, String file) throws Exception {
        Path manifest = run.resolve("run-manifest.properties"); String prefix = "sha256." + file + "=";
        String hash = RunConfig.sha256(Files.readAllBytes(run.resolve(file)));
        var lines = Files.readAllLines(manifest).stream().map(line -> line.startsWith(prefix) ? prefix + hash : line).toList();
        Files.writeString(manifest, String.join("\n", lines) + "\n");
    }
}
