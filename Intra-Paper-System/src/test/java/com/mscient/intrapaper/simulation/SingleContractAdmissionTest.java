package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.evaluation.EvaluationPartitionReport;
import com.mscient.intrapaper.provenance.DatasetManifest;
import com.mscient.intrapaper.provenance.RunConfig;
import java.nio.file.*;
import java.util.*;

/** Synthetic plumbing only; no performance, source authenticity, or operating evidence. */
public final class SingleContractAdmissionTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[0]), root = Path.of(args[1]);
        Files.createDirectories(root);
        for (String name : List.of("synthetic-run.properties", "synthetic-futures-bars.csv",
                "synthetic-instrument-master.csv", "synthetic-session-calendar.csv",
                "synthetic-market-rule-regimes.csv", "synthetic-cost-schedule.csv")) {
            Files.copy(project.resolve("examples").resolve(name), root.resolve(name));
        }
        Path configPath = root.resolve("synthetic-run.properties"), bars = root.resolve("synthetic-futures-bars.csv");
        RunConfig config = RunConfig.load(configPath);
        ValidatedDataset data = ValidatedDataset.loadForSimulation(bars, config);
        Strategy strategy = new SmaCrossoverStrategy(config);
        String id = RunIdentity.contentId(data, config, strategy);
        check(id.equals(RunIdentity.contentId(ValidatedDataset.loadForSimulation(bars, RunConfig.load(configPath)),
                RunConfig.load(configPath), new SmaCrossoverStrategy(config))), "identical reloaded inputs have identical identity");

        Map<String, String> hashes = new TreeMap<>(data.manifest().inputFileSha256());
        List<String> reversedKeys = new ArrayList<>(hashes.keySet()); Collections.reverse(reversedKeys);
        Map<String, String> reordered = new LinkedHashMap<>(); reversedKeys.forEach(k -> reordered.put(k, hashes.get(k)));
        check(id.equals(RunIdentity.contentId(withInputs(data, reordered), config, strategy)), "input map order is canonical");
        for (String key : hashes.keySet()) {
            Map<String, String> changed = new TreeMap<>(hashes);
            changed.put(key, "0".repeat(64));
            check(!id.equals(RunIdentity.contentId(withInputs(data, changed), config, strategy)), "identity binds recorded input " + key);
        }

        BacktestEngine engine = new BacktestEngine();
        BacktestResult result = engine.run(data, config, strategy);
        var written = new RunArtifactWriter().write(root.resolve("runs"), id, data, config, result);
        String manifest = Files.readString(written.path().resolve("run-manifest.json"));
        check(manifest.contains("\"run_identity_format\": \"INTRA_RUN_ID_V12\""), "manifest declares identity format");
        check(manifest.contains("\"dataset_input_file_sha256\""), "manifest retains complete identity input map");
        for (var input : hashes.entrySet()) {
            check(manifest.contains("\"" + input.getKey() + "\": \"" + input.getValue() + "\""), "manifest retains " + input.getKey());
        }
        check(RunArtifactVerifier.verify(written.path()).verifiedFiles() == 13, "saved manifest artifact verification");
        check(RunReplayVerifier.verify(bars, config, written.path(), strategy).comparedFiles() == 14, "saved manifest deterministic replay");

        Path rules = root.resolve("synthetic-market-rule-regimes.csv");
        Files.writeString(rules, Files.readString(rules) + "\n");
        ValidatedDataset changedRules = ValidatedDataset.loadForSimulation(bars, config);
        check(!data.marketRuleRegimeSchedule().sha256().equals(changedRules.marketRuleRegimeSchedule().sha256()), "market rule byte input changed");
        check(!id.equals(RunIdentity.contentId(changedRules, config, strategy)), "market rules change identity with unchanged properties");
        try {
            RunReplayVerifier.verify(bars, config, written.path(), strategy);
            throw new AssertionError("changed rules must reject saved-run replay");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("run ID"), "changed-rule replay rejects input identity mismatch");
        }

        List<String> lines = new ArrayList<>(Files.readAllLines(bars)); lines.remove(10); Files.write(bars, lines);
        String properties = Files.readString(configPath).replace("quality.allow_intrasession_gaps=false", "quality.allow_intrasession_gaps=true")
                .replace("quality.max_missing_intervals_per_session=0", "quality.max_missing_intervals_per_session=1");
        Files.writeString(configPath, properties);
        config = RunConfig.load(configPath);
        ValidatedDataset diagnostic = ValidatedDataset.load(bars, config);
        check(!diagnostic.simulationReady() && diagnostic.completeSessionCount() == 0, "diagnostic data remains explicitly incomplete");
        check(diagnostic.bars().size() == 29 && diagnostic.expectedBarCount() == 30
                && diagnostic.estimatedMissingIntervals() == 1 && diagnostic.timestampSpacingAnomalies() == 1,
                "diagnostic retains counts without fabricating bars");
        final RunConfig gapConfig = config;
        blocked(() -> ValidatedDataset.loadForSimulation(bars, gapConfig));
        blocked(() -> engine.run(diagnostic, gapConfig));
        Strategy sentinel = new Strategy() {
            public String id() { throw new AssertionError("strategy accessed before admission"); }
            public Map<String,String> parameters() { throw new AssertionError("parameters accessed before admission"); }
            public boolean supportsDurableCheckpoints() { return true; }
            public StrategySignal evaluate(List<com.mscient.intrapaper.data.Bar> available, java.time.Instant time) {
                throw new AssertionError("strategy evaluated before admission");
            }
        };
        blocked(() -> engine.run(diagnostic, gapConfig, sentinel));
        try (var checkpoints = new BacktestCheckpointStore(root.resolve("checkpoints"), id)) {
            blocked(() -> engine.run(diagnostic, gapConfig, sentinel, checkpoints));
        }
        blocked(() -> new RunArtifactWriter().write(root.resolve("blocked-output"), id, diagnostic, gapConfig, result));
        check(!Files.exists(root.resolve("blocked-output")), "incomplete inputs cannot publish scored artifacts");

        // Admission precedes split loading: even an unavailable plan cannot conceal incomplete input.
        Files.writeString(configPath, properties + "\nresearch.trial_ledger_file=unused.tsv\nresearch.trial_id=GAP_TEST\n"
                + "research.strategy_version=v1\nresearch.variant_id=base\nresearch.strategy_card_file=unused.md\n"
                + "evaluation.split_plan_file=unused.csv\n");
        RunConfig scoringConfig = RunConfig.load(configPath);
        blocked(() -> EvaluationPartitionReport.create(scoringConfig, diagnostic, List.of()));
        System.out.println("SingleContractAdmissionTest: " + checks + " synthetic checks passed");
    }

    private static ValidatedDataset withInputs(ValidatedDataset d, Map<String,String> hashes) {
        DatasetManifest m = d.manifest();
        DatasetManifest replacement = new DatasetManifest(m.schemaVersion(), m.datasetId(), m.sourceName(), m.acquiredAtUtc(),
                m.timezone(), m.timestampRole(), m.dataRightsStatus(), m.dataRightsReference(), m.instrumentMasterSnapshotId(),
                m.instrumentMasterSha256(), m.sessionCalendarSha256(), hashes);
        return new ValidatedDataset(d.bars(), d.instrumentMaster(), d.sessionCalendar(), d.marketRuleRegimeSchedule(), replacement,
                d.barsSha256(), d.configSha256(), d.calendarSha256(), d.costSchedule(), d.componentCostSchedule(), d.marginRequirementSchedule(),
                d.expirySettlement(), d.settlementCostSchedule(), d.quoteHistory(), d.optionValuationInputs(), d.timestampSpacingAnomalies(),
                d.estimatedMissingIntervals(), d.expectedBarCount(), d.openSessionCount(), d.completeSessionCount());
    }
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static void blocked(Action action) throws Exception {
        try { action.run(); throw new AssertionError("incomplete simulation/scoring was admitted"); }
        catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("INCOMPLETE_DATA") && expected.getMessage().contains("missing_intervals=1")
                    && expected.getMessage().contains("quality.allow_intrasession_gaps=true")
                    && expected.getMessage().contains("quality.max_missing_intervals_per_session=1"), "rejection retains gap and override diagnostics");
        }
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
