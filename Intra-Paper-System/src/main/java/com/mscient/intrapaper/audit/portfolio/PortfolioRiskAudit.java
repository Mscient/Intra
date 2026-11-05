package com.mscient.intrapaper.audit.portfolio;

import com.mscient.intrapaper.provenance.BuildInfo;
import com.mscient.intrapaper.simulation.portfolio.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Offline audit facade. Original inputs are mandatory, including for semantic verification. */
public final class PortfolioRiskAudit {
    public record WrittenAudit(String auditId, Path path, String status) { }
    record Prepared(Map<String, byte[]> files, String status) { }
    private PortfolioRiskAudit() { }

    public static WrittenAudit audit(Path run, Path inputManifest, Path settings, Path riskFolder, Path output) throws IOException {
        Path runPath = run.toRealPath();
        Path outputPath = output.toAbsolutePath().normalize();
        Path existingAncestor = outputPath;
        while (!Files.exists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) existingAncestor = existingAncestor.getParent();
        Path resolvedOutput = existingAncestor.toRealPath().resolve(existingAncestor.relativize(outputPath)).normalize();
        if (resolvedOutput.startsWith(runPath)) throw new IOException("Audit output must be outside the verified portfolio run");
        Prepared prepared = prepare(run, inputManifest, settings, riskFolder);
        return PortfolioRiskAuditArtifacts.write(output, prepared);
    }

    /** Rechecks artifact integrity, original replay determinism and every regenerated audit byte. */
    public static WrittenAudit verify(Path audit, Path run, Path inputManifest, Path settings, Path riskFolder) throws IOException {
        WrittenAudit saved = PortfolioRiskAuditArtifacts.verify(audit);
        Prepared rebuilt = prepare(run, inputManifest, settings, riskFolder);
        String rebuiltId = RiskInputs.hash(PortfolioRiskAuditArtifacts.manifest(rebuilt));
        if (!saved.auditId().equals(rebuiltId)) throw new IOException("Audit differs from regenerated verified inputs");
        for (var entry : rebuilt.files().entrySet())
            if (!Arrays.equals(entry.getValue(), Files.readAllBytes(audit.resolve(entry.getKey()))))
                throw new IOException("Audit output differs from recomputation: " + entry.getKey());
        return saved;
    }

    static Prepared prepare(Path run, Path inputManifest, Path settings, Path riskFolder) throws IOException {
        RiskInputs.requireFile(inputManifest); RiskInputs.requireFile(settings);
        String manifestHash = RiskInputs.hash(Files.readAllBytes(inputManifest));
        String settingsHash = RiskInputs.hash(Files.readAllBytes(settings));
        String runManifestHash = RiskInputs.hash(Files.readAllBytes(run.resolve("run-manifest.properties")));
        var saved = PortfolioReplayDeterminismVerifier.verify(inputManifest, settings, run);
        var replay = PortfolioReplayCsvRunner.replay(inputManifest, settings);
        if (!saved.runId().equals(replay.result().replayFingerprintSha256())
                || !saved.validatedDatasetsSha256().equals(replay.dataset().datasetsSha256()))
            throw new IOException("Replay changed during audit reconstruction");
        // Bind the exact in-memory executions used below, not just a preceding replay invocation.
        Path scratch = Files.createTempDirectory("portfolio-risk-reconstruction-");
        try {
            var regenerated = new PortfolioReplayArtifactWriter().write(scratch, replay.dataset(), replay.result());
            if (!saved.outputSha256().equals(PortfolioReplayArtifactVerifier.verify(regenerated.path()).outputSha256()))
                throw new IOException("Reconstructed replay outputs do not match verified run");
        } finally { cleanup(scratch); }
        RiskInputs risk = RiskInputs.load(riskFolder);
        var report = PortfolioRiskCalculator.calculate(replay.dataset().frames(), replay.result(), risk);
        TreeMap<String, byte[]> files = new TreeMap<>();
        report.files().forEach((name, content) -> files.put(name, utf8(content)));
        risk.files.forEach((name, bytes) -> files.put("risk-" + name, bytes.clone()));
        StringBuilder inputs = PortfolioRiskCalculator.header("input,sha256");
        Map<String, String> hashes = new TreeMap<>();
        hashes.put("portfolio_input_manifest", manifestHash);
        hashes.put("portfolio_settings", settingsHash);
        hashes.put("run_manifest", runManifestHash);
        hashes.put("replay_id", saved.runId());
        hashes.put("validated_datasets", replay.dataset().datasetsSha256());
        hashes.put("frame_inputs", replay.result().frameInputsSha256());
        saved.outputSha256().forEach((name, hash) -> hashes.put("run/" + name, hash));
        risk.files.forEach((name, bytes) -> hashes.put("risk/" + name, RiskInputs.hash(bytes)));
        replay.result().executionCostScheduleSourceFileSha256().ifPresent(hash -> hashes.put("execution_cost_schedule", hash));
        replay.dataset().datasetsByInstrument().forEach((id, data) -> {
            String prefix = "dataset/" + id + "/";
            hashes.put(prefix + "bars", data.barsSha256());
            hashes.put(prefix + "config", data.configSha256());
            hashes.put(prefix + "calendar", data.calendarSha256());
            hashes.put(prefix + "instrument_master", data.manifest().instrumentMasterSha256());
            hashes.put(prefix + "market_rules", data.marketRuleRegimeSchedule().sha256());
            data.manifest().inputFileSha256().forEach((name, hash) -> hashes.put(prefix + "additional/" + name, hash));
        });
        hashes.forEach((name, hash) -> PortfolioRiskCalculator.row(inputs, name, hash));
        files.put("inputs.csv", utf8(inputs.toString()));
        String summary = "schema_version=1\nclassification=OFFLINE_PORTFOLIO_RISK_AUDIT\n"
                + "margin_classification=CALLER_SUPPLIED_PROXIES\nstatus=" + report.status()
                + "\nreplay_reconciliation=PASS\ncoverage_issue_count=" + report.coverageIssues()
                + "\nbreach_count=" + report.breaches() + "\nframe_count=" + replay.dataset().frames().size()
                + "\nexecution_count=" + replay.result().executions().size() + "\naudit_build=" + BuildInfo.commitId() + "\n";
        files.put("summary.properties", utf8(summary));
        risk.unchanged(riskFolder);
        if (!manifestHash.equals(RiskInputs.hash(Files.readAllBytes(inputManifest)))
                || !settingsHash.equals(RiskInputs.hash(Files.readAllBytes(settings)))
                || !runManifestHash.equals(RiskInputs.hash(Files.readAllBytes(run.resolve("run-manifest.properties")))))
            throw new IOException("Original input or run manifest changed during audit");
        var finalVerification = PortfolioReplayArtifactVerifier.verify(run);
        if (!saved.equals(finalVerification)) throw new IOException("Portfolio run changed during audit");
        return new Prepared(Collections.unmodifiableMap(files), report.status());
    }

    static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    static void cleanup(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(absolute))
            throw new IOException("Refusing to clean unexpected audit staging path");
        try (var paths = Files.walk(absolute)) {
            for (Path child : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!child.toAbsolutePath().normalize().startsWith(absolute)) throw new IOException("Invalid staging child");
                Files.delete(child);
            }
        }
    }
}
