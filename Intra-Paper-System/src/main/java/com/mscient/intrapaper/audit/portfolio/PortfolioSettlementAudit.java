package com.mscient.intrapaper.audit.portfolio;

import com.mscient.intrapaper.provenance.BuildInfo;
import com.mscient.intrapaper.simulation.portfolio.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Standalone offline sidecar workflow; never writes inside a saved replay. */
public final class PortfolioSettlementAudit {
    public record WrittenAudit(String auditId, Path path) { }
    record Prepared(Map<String, byte[]> files, boolean costsEnabled) { }
    private PortfolioSettlementAudit() { }

    public static WrittenAudit audit(Path run, Path inputs, Path settings, Path events, Path costs, Path output) throws IOException {
        Path target = output.toAbsolutePath().normalize(), ancestor = target;
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        Path resolved = ancestor.toRealPath().resolve(ancestor.relativize(target)).normalize();
        if (resolved.startsWith(run.toRealPath())) throw new IOException("Settlement sidecar output must be outside the replay folder");
        return PortfolioSettlementArtifacts.write(output, prepare(run, inputs, settings, events, costs));
    }

    public static WrittenAudit verify(Path audit, Path run, Path inputs, Path settings, Path events, Path costs) throws IOException {
        WrittenAudit saved = PortfolioSettlementArtifacts.verify(audit);
        Prepared rebuilt = prepare(run, inputs, settings, events, costs);
        byte[] manifest = PortfolioSettlementArtifacts.manifest(rebuilt);
        if (!Arrays.equals(manifest, SettlementInputs.readFile(audit.resolve(PortfolioSettlementArtifacts.MANIFEST))))
            throw new IOException("Settlement manifest differs from deterministic reconstruction");
        for (var entry : rebuilt.files().entrySet())
            if (!Arrays.equals(entry.getValue(), SettlementInputs.readFile(audit.resolve(entry.getKey()))))
                throw new IOException("Settlement report differs from deterministic reconstruction: " + entry.getKey());
        return saved;
    }

    static Prepared prepare(Path run, Path inputs, Path settings, Path eventsPath, Path costsPath) throws IOException {
        byte[] inputBytes = SettlementInputs.readFile(inputs), settingsBytes = SettlementInputs.readFile(settings);
        byte[] runManifestBytes = SettlementInputs.readFile(run.resolve("run-manifest.properties"));
        var saved = PortfolioReplayArtifactVerifier.verify(run);
        var replay = PortfolioReplayCsvRunner.replay(inputs, settings);
        if (!saved.runId().equals(replay.result().replayFingerprintSha256()) || !saved.validatedDatasetsSha256().equals(replay.dataset().datasetsSha256()))
            throw new IOException("Original replay identity differs; the same replay build and validated inputs are required");
        Path scratch = Files.createTempDirectory("portfolio-settlement-reconstruction-");
        try {
            var regenerated = new PortfolioReplayArtifactWriter().write(scratch, replay.dataset(), replay.result());
            if (!saved.equals(PortfolioReplayArtifactVerifier.verify(regenerated.path())))
                throw new IOException("Reconstructed replay outputs differ from saved run");
        } finally { cleanup(scratch); }
        SettlementInputs settlement = SettlementInputs.load(eventsPath, costsPath);
        var report = PortfolioSettlementCalculator.calculate(replay.dataset().frames(), replay.result(), settlement);
        TreeMap<String, byte[]> files = new TreeMap<>();
        report.files().forEach((name, content) -> files.put(name, utf8(content)));
        Map<String, String> hashes = new TreeMap<>();
        hashes.put("portfolio_input_manifest", SettlementInputs.hash(inputBytes));
        hashes.put("portfolio_settings", SettlementInputs.hash(settingsBytes));
        hashes.put("run_manifest", SettlementInputs.hash(runManifestBytes));
        hashes.put("replay_id", saved.runId()); hashes.put("validated_datasets", saved.validatedDatasetsSha256());
        hashes.put("frame_inputs", replay.result().frameInputsSha256());
        hashes.put("settlement_events", SettlementInputs.hash(settlement.eventBytes));
        if (settlement.costBytes != null) hashes.put("settlement_cost_schedule", SettlementInputs.hash(settlement.costBytes));
        hashes.put("audit_build_artifact", buildArtifactHash());
        saved.outputSha256().forEach((name, hash) -> hashes.put("run/" + name, hash));
        replay.result().executionCostScheduleSourceFileSha256().ifPresent(hash -> hashes.put("replay_execution_cost_schedule", hash));
        replay.dataset().datasetsByInstrument().forEach((id, data) -> {
            String prefix = "dataset/" + id + "/";
            hashes.put(prefix + "bars", data.barsSha256()); hashes.put(prefix + "config", data.configSha256());
            hashes.put(prefix + "calendar", data.calendarSha256()); hashes.put(prefix + "instrument_master", data.manifest().instrumentMasterSha256());
            hashes.put(prefix + "market_rules", data.marketRuleRegimeSchedule().sha256());
            data.manifest().inputFileSha256().forEach((name, hash) -> hashes.put(prefix + "additional/" + name, hash));
        });
        StringBuilder provenance = PortfolioSettlementCalculator.header("input,sha256");
        hashes.forEach((name, hash) -> PortfolioSettlementCalculator.row(provenance, name, hash));
        files.put("inputs.csv", utf8(provenance.toString()));
        String summary = "schema_version=1\nclassification=OFFLINE_PORTFOLIO_SETTLEMENT_AUDIT\nperformance_claim_eligible=false\n"
                + "replay_reconciliation=PASS\nsettlement_reconciliation=PASS\nreplay_id=" + saved.runId()
                + "\nreplay_build=" + replay.result().buildCommit() + "\naudit_build=" + BuildInfo.commitId()
                + "\naudit_build_artifact_sha256=" + hashes.get("audit_build_artifact")
                + "\nsettlement_events_sha256=" + hashes.get("settlement_events")
                + "\nsettlement_cost_schedule_sha256=" + hashes.getOrDefault("settlement_cost_schedule", "NONE")
                + "\nsettlement_costs_enabled=" + (settlement.costs != null) + "\nsettlement_cost_status=" + (settlement.costs == null ? "NOT_MODELED" : "CALLER_SUPPLIED")
                + "\nfinal_replay_session_date=" + report.finalDate() + "\ninstrument_count=" + replay.dataset().datasetsByInstrument().size()
                + "\nsettlement_count=" + report.settledCount() + "\nreplay_final_net_pnl_inr=" + PortfolioSettlementCalculator.number(report.replayNet())
                + "\nsettlement_adjustments_inr=" + PortfolioSettlementCalculator.number(report.adjustments())
                + "\nsettlement_costs_inr=" + PortfolioSettlementCalculator.number(report.costs())
                + "\nfinal_net_pnl_inr=" + PortfolioSettlementCalculator.number(report.finalNet()) + "\n";
        files.put("summary.properties", utf8(summary));
        settlement.unchanged(eventsPath, costsPath);
        if (!Arrays.equals(inputBytes, SettlementInputs.readFile(inputs)) || !Arrays.equals(settingsBytes, SettlementInputs.readFile(settings))
                || !Arrays.equals(runManifestBytes, SettlementInputs.readFile(run.resolve("run-manifest.properties"))))
            throw new IOException("Replay inputs or manifest changed during settlement audit");
        // Revalidate every original input, including indirect dated references, before publication.
        if (!saved.equals(PortfolioReplayDeterminismVerifier.verify(inputs, settings, run))) throw new IOException("Replay changed during settlement audit");
        return new Prepared(Collections.unmodifiableMap(files), settlement.costs != null);
    }

    /** Bind the complete runnable JAR, or sorted class bytes for an unpackaged diagnostic build. */
    static String buildArtifactHash() throws IOException {
        Path location;
        try { location = Path.of(PortfolioSettlementAudit.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Exception invalid) { throw new IOException("Cannot identify local audit build", invalid); }
        if (Files.isRegularFile(location, LinkOption.NOFOLLOW_LINKS)) return SettlementInputs.hash(SettlementInputs.readFile(location));
        StringBuilder classes = new StringBuilder("INTRA_SETTLEMENT_CLASS_BUILD_V1\n");
        try (var paths = Files.walk(location)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                classes.append(location.relativize(path).toString().replace('\\', '/')).append('=')
                        .append(SettlementInputs.hash(SettlementInputs.readFile(path))).append('\n');
            }
        }
        return SettlementInputs.hash(utf8(classes.toString()));
    }
    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static void cleanup(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(absolute)) throw new IOException("Unexpected staging path");
        try (var paths = Files.walk(absolute)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(absolute)) throw new IOException("Cleanup outside staging root");
                Files.delete(path);
            }
        }
    }
}
