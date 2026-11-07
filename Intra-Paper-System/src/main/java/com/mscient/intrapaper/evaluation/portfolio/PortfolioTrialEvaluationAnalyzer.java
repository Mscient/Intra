package com.mscient.intrapaper.evaluation.portfolio;

import com.mscient.intrapaper.evaluation.EvaluationSplitPlan;
import com.mscient.intrapaper.evaluation.ResearchOutcomeLedger;
import com.mscient.intrapaper.evaluation.ResearchTrialLedger;
import com.mscient.intrapaper.evaluation.SessionReturnStatistics;
import com.mscient.intrapaper.simulation.portfolio.PortfolioReplayArtifactVerifier;
import com.mscient.intrapaper.provenance.BuildInfo;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static com.mscient.intrapaper.evaluation.portfolio.PortfolioEvaluationJson.object;
import static com.mscient.intrapaper.evaluation.portfolio.PortfolioTrialIdentity.requireEqual;

/** Separate offline evaluator over registered, verified multi-instrument portfolio replay artifacts. */
public final class PortfolioTrialEvaluationAnalyzer {
    public static final String REPORT_SCHEMA = "INTRA_PORTFOLIO_TRIAL_EVALUATION_V3";

    public record SessionReturn(java.time.LocalDate sessionDate, EvaluationSplitPlan.Partition partition,
            BigDecimal realizedGrossPnlChangeInr, BigDecimal unrealizedPnlChangeInr, BigDecimal feesPaidInr,
            BigDecimal netPnlChangeInr, BigDecimal sessionReturn) { }

    public record TrialReport(ResearchTrialLedger.Entry registration, String runId, String runManifestSha256,
            String runBuildCommit,
            Map<String, String> outputSha256, BigDecimal startingCapitalInr, int instrumentCount,
            int developmentSessionCount, int validationSessionCount, int excludedHoldoutSessionCount,
            BigDecimal totalNetPnlInr, List<SessionReturn> sessions,
            SessionReturnStatistics.TrialStatistics statistics, int includedSettlementCount, int excludedHoldoutSettlementCount,
            PortfolioSharpeHac.Result sharpeHac, String sessionAxisSha256,
            String includedReturnSeriesSha256, List<String> calendarSha256) {
        public TrialReport { outputSha256 = Map.copyOf(outputSha256); sessions = List.copyOf(sessions); calendarSha256 = List.copyOf(calendarSha256); }
        public boolean performanceClaimEligible() { return false; }
    }

    public record OutcomeProvenance(String fileSha256, ResearchOutcomeLedger.Summary summary) { }

    public record Report(String trialLedgerFileSha256, String trialLedgerFinalSha256, int registeredTrialCount,
            String trialCatalogSha256, int catalogTrialCount, int comparisonFamilyTrialCount,
            String datasetSha256, String splitPlanSha256, BigDecimal startingCapitalInr,
            SessionReturnStatistics.Summary statistics, List<TrialReport> trials, OutcomeProvenance outcomeHistory,
            PortfolioTrialCatalog.ArtifactScope artifactScope) {
        public Report { trials = List.copyOf(trials); }
        public boolean performanceClaimEligible() { return false; }

        /** Includes development/validation values only; hashes cover the complete supplied artifacts. */
        public String toJson() {
            TrialReport first = trials.getFirst();
            var pbo = statistics.pbo();
            var result = object("schema", REPORT_SCHEMA, "classification", "OFFLINE_DIAGNOSTIC_ONLY",
                    "evaluator_build_commit", BuildInfo.commitId(),
                    "trial_ledger_file_sha256", trialLedgerFileSha256,
                    "trial_ledger_final_sha256", trialLedgerFinalSha256,
                    "registered_trial_count", registeredTrialCount,
                    "trial_catalog_sha256", trialCatalogSha256, "catalog_trial_count", catalogTrialCount,
                    "analyzed_trial_count", trials.size(), "comparison_family_trial_count", comparisonFamilyTrialCount,
                    "dataset_sha256", datasetSha256, "split_plan_sha256", splitPlanSha256,
                    "starting_capital_inr", startingCapitalInr,
                    "artifact_scope", artifactScope.name(),
                    "return_formula", "r_t = (N_t - N_previous_session) / starting_capital_inr; N_before_first_session = 0",
                    "portfolio_net_pnl_formula", "N_t = sum_instruments(realized_gross_pnl_inr + unrealized_pnl_inr - fees_inr) at the final checkpoint of the session, including explicit settlements",
                    "return_basis", "fixed starting capital; simple, non-compounded; no capital inferred from P&L",
                    "included_partitions", List.of("DEVELOPMENT", "VALIDATION"),
                    "holdout_included", false, "holdout_data_used_for_calculation", false,
                    "first_evaluation_session", first.sessions().getFirst().sessionDate().toString(),
                    "last_evaluation_session", first.sessions().getLast().sessionDate().toString(),
                    "evaluation_session_count", first.sessions().size(),
                    "excluded_holdout_session_count", first.excludedHoldoutSessionCount(),
                    "periods_per_year", SessionReturnStatistics.PERIODS_PER_YEAR, "risk_free_session_return", 0,
                    "trial_daily_sharpe_sample_stddev", statistics.sampleStandardDeviationAcrossTrialSharpes(),
                    "dsr_expected_max_daily_sharpe_threshold", statistics.expectedMaximumSharpeThresholdDaily(),
                    "dsr_effective_trial_count_sensitivity", object("effective_trial_count_min", 1,
                            "effective_trial_count_max", comparisonFamilyTrialCount,
                            "trial_dependence_estimated", false, "raw_family_count_is_upper_scenario", true,
                            "interpretation", "one-trial and raw-family endpoints are assumptions; no fitted effective count or confidence interval"),
                    "sharpe_serial_dependence_adjusted", false,
                    "sharpe_hac_protocol", object("protocol_id", PortfolioSharpeHac.PROTOCOL_ID,
                            "protocol_sha256", PortfolioSharpeHac.PROTOCOL_SHA256,
                            "estimator_version", PortfolioSharpeHac.ESTIMATOR_VERSION,
                            "minimum_sessions", PortfolioSharpeHac.MINIMUM_SESSIONS,
                            "preregistration_status", "UNVERIFIED", "prior_exposure", "UNKNOWN",
                            "source_review_commit", PortfolioSharpeHac.SOURCE_REVIEW_COMMIT,
                            "source_review_git_blob", PortfolioSharpeHac.SOURCE_REVIEW_BLOB,
                            "analysis_build_commit", BuildInfo.commitId(),
                            "interpretation", "fixed-candidate original-frequency Sharpe standard error only; no interval, p-value, or profitability probability"),
                    "outcome_history", outcomeJson(outcomeHistory, trials),
                    "pbo", object("method", "combinatorially symmetric cross-validation over chronological blocks",
                            "chronological_block_count", pbo.chronologicalBlockCount(),
                            "combinations", pbo.combinations(),
                            "selected_winner_below_oos_median_count", pbo.belowMedianCount(),
                            "probability_backtest_overfitting", pbo.probabilityBacktestOverfitting()),
                    "trials", trials.stream().map(trial -> trialJson(trial, comparisonFamilyTrialCount)).toList(),
                    "performance_claim_eligible", false);
            return PortfolioEvaluationJson.write(result);
        }
    }

    private record ReadTrial(PortfolioTrialCatalog.Entry catalog, ResearchTrialLedger.Entry registration,
            PortfolioTrialIdentity.FrozenRun run, EvaluationSplitPlan split, PortfolioSessionReturns.Series series) { }

    private PortfolioTrialEvaluationAnalyzer() { }

    public static Report analyze(Path registrationLedger, Path catalogPath, Path runsRoot,
            int chronologicalBlockCount) throws IOException {
        return analyze(registrationLedger, catalogPath, runsRoot, chronologicalBlockCount, null);
    }

    /** Outcome history verifies registration provenance only; it never filters the comparison family. */
    public static Report analyze(Path registrationLedger, Path catalogPath, Path runsRoot,
            int chronologicalBlockCount, Path outcomeLedger) throws IOException {
        SessionReturnStatistics.requireBlockCount(chronologicalBlockCount);
        String ledgerFileHash = PortfolioTrialIdentity.fileSha256(registrationLedger);
        List<ResearchTrialLedger.Entry> registrations = ResearchTrialLedger.entries(registrationLedger);
        String finalLedgerHash = registrations.isEmpty() ? "0".repeat(64)
                : registrations.getLast().recordHashSha256();
        Map<String, ResearchTrialLedger.Entry> byId = new HashMap<>();
        registrations.forEach(entry -> byId.put(entry.trial().trialId(), entry));

        OutcomeProvenance outcome = null;
        if (outcomeLedger != null) {
            String hash = PortfolioTrialIdentity.fileSha256(outcomeLedger);
            var summary = ResearchOutcomeLedger.verify(registrationLedger, outcomeLedger);
            requireEqual(hash, PortfolioTrialIdentity.fileSha256(outcomeLedger), "outcome ledger changed during verification");
            outcome = new OutcomeProvenance(hash, summary);
        }

        PortfolioTrialCatalog catalog = PortfolioTrialCatalog.load(catalogPath);
        if (catalog.trials().size() < 2) throw new IOException("Portfolio evaluation requires at least two registered trials");
        Path root = runsRoot.toAbsolutePath().normalize();
        List<ReadTrial> readTrials = new ArrayList<>();
        for (var row : catalog.trials()) {
            var registration = byId.get(row.trial().trialId());
            if (registration == null) throw new IOException("Trial is not registered: " + row.trial().trialId());
            if (!registration.trial().equals(row.trial())) {
                throw new IOException("Catalog strategy, dataset, split, or parameter identity does not match registration: "
                        + row.trial().trialId());
            }
            requireEqual(registration.recordHashSha256(), row.registrationRecordSha256(), "registration record hash");
            PortfolioTrialIdentity.readRegular(row.splitPlanPath());
            EvaluationSplitPlan split = EvaluationSplitPlan.loadForEvaluation(row.splitPlanPath());
            requireEqual(row.trial().splitPlanSha256(), split.splitFileSha256(), "registered split-plan file");
            Path folder = root.resolve(row.runId()).normalize();
            if (!folder.startsWith(root)) throw new IOException("Portfolio run escaped runs root");
            var run = PortfolioTrialIdentity.inspect(folder);
            requireEqual(row.runId(), run.verification().runId(), "catalog run ID");
            requireEqual(row.runManifestSha256(), run.manifestSha256(), "catalog-pinned run manifest hash");
            requireEqual(row.trial().strategyId(), run.strategyId(), "registered portfolio strategy");
            requireEqual(row.trial().datasetSha256(), run.verification().validatedDatasetsSha256(), "registered portfolio dataset");
            requireEqual(row.trial().parameterConfigSha256(), run.parameterConfigSha256(), "registered portfolio parameters");
            PortfolioSessionReturns.Series series;
            try {
                series = PortfolioSessionReturns.read(folder, run, split, row.startingCapitalInr(), catalog.artifactScope());
            } catch (IOException incompleteSeries) {
                throw new IOException("INCOMPLETE: VERIFIED_SESSION_SERIES_UNAVAILABLE; full-family scores were not published: "
                        + incompleteSeries.getMessage(), incompleteSeries);
            }
            var checkedAgain = PortfolioReplayArtifactVerifier.verifyIntegrity(folder);
            if (!checkedAgain.equals(run.verification())) throw new IOException("Portfolio artifacts changed during evaluation");
            requireEqual(row.runManifestSha256(), PortfolioTrialIdentity.fileSha256(
                    folder.resolve("run-manifest.properties")), "run manifest changed during evaluation");
            readTrials.add(new ReadTrial(row, registration, run, split, series));
        }
        readTrials.sort(Comparator.comparing(trial -> trial.registration().trial().trialId()));
        ReadTrial first = readTrials.getFirst();
        String datasetHash = first.registration().trial().datasetSha256();
        String splitHash = first.split().splitFileSha256();
        for (ReadTrial trial : readTrials) {
            requireEqual(datasetHash, trial.registration().trial().datasetSha256(), "common comparison-family dataset");
            requireEqual(splitHash, trial.split().splitFileSha256(), "common comparison-family split");
            if (!trial.split().sessions().equals(first.split().sessions())) throw new IOException("Trials have different split sessions");
            if (first.catalog().startingCapitalInr().compareTo(trial.catalog().startingCapitalInr()) != 0) {
                throw new IOException("Every comparison-family trial must use the same explicit starting_capital_inr");
            }
        }
        Set<String> analyzedIds = readTrials.stream().map(trial -> trial.registration().trial().trialId())
                .collect(java.util.stream.Collectors.toSet());
        int familyCount = 0;
        for (var entry : registrations) {
            if (datasetHash.equals(entry.trial().datasetSha256()) && splitHash.equals(entry.trial().splitPlanSha256())) {
                familyCount++;
                if (!analyzedIds.contains(entry.trial().trialId())) {
                    throw new IOException("Catalog omits registered comparison-family trial: " + entry.trial().trialId());
                }
            }
        }
        if (familyCount != readTrials.size()) throw new IOException("Catalog must include the complete registered comparison family");

        var statistics = SessionReturnStatistics.analyze(readTrials.stream().map(trial ->
                new SessionReturnStatistics.Series(trial.registration().trial().trialId(), trial.series().returns()))
                .toList(), chronologicalBlockCount);
        List<TrialReport> reports = new ArrayList<>();
        for (int index = 0; index < readTrials.size(); index++) {
            var trial = readTrials.get(index);
            var series = trial.series();
            var sessions = series.sessions().stream().map(session -> new SessionReturn(session.date(),
                    session.partition(), session.realizedGrossPnlChangeInr(), session.unrealizedPnlChangeInr(),
                    session.feesPaidInr(), session.netPnlChangeInr(), session.sessionReturn())).toList();
            reports.add(new TrialReport(trial.registration(), trial.catalog().runId(), trial.run().manifestSha256(),
                    trial.run().manifest().get("build_commit"),
                    trial.run().verification().outputSha256(), trial.catalog().startingCapitalInr(),
                    series.instrumentCount(), series.developmentSessionCount(), series.validationSessionCount(),
                    series.excludedHoldoutSessionCount(), series.totalNetPnlInr(), sessions,
                    statistics.trials().get(index), series.includedSettlementCount(), series.excludedHoldoutSettlementCount(),
                    PortfolioSharpeHac.evaluate(series.returns()), series.sessionAxisSha256(),
                    series.includedReturnSeriesSha256(), series.calendarSha256()));
        }
        requireEqual(ledgerFileHash, PortfolioTrialIdentity.fileSha256(registrationLedger), "registration ledger changed during evaluation");
        requireEqual(catalog.sha256(), PortfolioTrialIdentity.fileSha256(catalogPath), "catalog changed during evaluation");
        for (var trial : readTrials) requireEqual(splitHash, PortfolioTrialIdentity.fileSha256(
                trial.catalog().splitPlanPath()), "split file changed during evaluation");
        if (outcome != null) requireEqual(outcome.fileSha256(), PortfolioTrialIdentity.fileSha256(outcomeLedger),
                "outcome ledger changed during evaluation");
        return new Report(ledgerFileHash, finalLedgerHash, registrations.size(), catalog.sha256(),
                catalog.trials().size(), familyCount, datasetHash, splitHash, first.catalog().startingCapitalInr(),
                statistics, reports, outcome, catalog.artifactScope());
    }

    private static Map<String, Object> trialJson(TrialReport report, int familyCount) {
        var trial = report.registration().trial();
        var score = report.statistics();
        return object("trial_id", trial.trialId(), "run_id", report.runId(),
                "run_build_commit", report.runBuildCommit(),
                "registration_sequence", report.registration().sequence(),
                "registration_record_sha256", report.registration().recordHashSha256(),
                "strategy_id", trial.strategyId(), "strategy_version", trial.strategyVersion(), "variant_id", trial.variantId(),
                "strategy_card_sha256", trial.strategyCardSha256(), "dataset_sha256", trial.datasetSha256(),
                "split_plan_sha256", trial.splitPlanSha256(), "parameter_config_sha256", trial.parameterConfigSha256(),
                "run_manifest_sha256", report.runManifestSha256(), "portfolio_output_sha256", new TreeMap<>(report.outputSha256()),
                "starting_capital_inr", report.startingCapitalInr(), "instrument_count", report.instrumentCount(),
                "development_session_count", report.developmentSessionCount(), "validation_session_count", report.validationSessionCount(),
                "excluded_holdout_session_count", report.excludedHoldoutSessionCount(), "session_count", score.sessionCount(),
                "included_settlement_count", report.includedSettlementCount(), "excluded_holdout_settlement_count", report.excludedHoldoutSettlementCount(),
                "total_net_pnl_inr", report.totalNetPnlInr(),
                "validated_calendar_sha256", report.calendarSha256(),
                "session_axis_sha256", report.sessionAxisSha256(),
                "included_session_return_series_sha256", report.includedReturnSeriesSha256(),
                "sharpe_hac", sharpeHacJson(report.sharpeHac()),
                "cumulative_simple_return", report.totalNetPnlInr().divide(report.startingCapitalInr(), MathContext.DECIMAL128),
                "mean_session_return", score.meanSessionReturn(),
                "sample_session_return_stddev", score.sampleSessionReturnStandardDeviation(),
                "daily_sharpe", score.dailySharpe(), "annualized_sharpe_252", score.annualizedSharpe(),
                "return_skewness", score.skewness(), "return_kurtosis", score.kurtosis(),
                "deflated_sharpe_probability", score.deflatedSharpeProbability(),
                "dsr_probability_at_one_effective_trial", score.dsrProbabilityAtOneEffectiveTrial(),
                "dsr_probability_at_raw_family_trial_count", score.dsrProbabilityAtRawFamilyTrialCount(),
                "dsr_multiplicity_in_dataset_split_family", familyCount,
                "session_returns", report.sessions().stream().map(session -> object(
                        "session_date", session.sessionDate().toString(), "partition", session.partition().name(),
                        "realized_gross_pnl_change_inr", session.realizedGrossPnlChangeInr(),
                        "unrealized_pnl_change_inr", session.unrealizedPnlChangeInr(), "fees_paid_inr", session.feesPaidInr(),
                        "net_pnl_change_inr", session.netPnlChangeInr(), "session_return", session.sessionReturn())).toList(),
                "performance_claim_eligible", false);
    }

    private static Map<String, Object> sharpeHacJson(PortfolioSharpeHac.Result result) {
        return object("status", result.status(), "reason", result.reason(),
                "session_count", result.sessionCount(), "minimum_sessions", PortfolioSharpeHac.MINIMUM_SESSIONS,
                "frequency", "ORIGINAL_EXCHANGE_SESSION", "risk_free_session_return", 0,
                "sharpe_point_estimate", Double.isFinite(result.sharpePointEstimate()) ? result.sharpePointEstimate() : null,
                "bandwidth_grid", result.bandwidthGrid(),
                "bandwidths", result.bandwidths().stream().map(bandwidth -> object(
                        "lag", bandwidth.lag(), "status", bandwidth.status(), "reason", bandwidth.reason(),
                        "projected_long_run_variance", bandwidth.projectedLongRunVariance(),
                        "standard_error", bandwidth.standardError(),
                        "scalar_projected_long_run_variance", bandwidth.scalarProjectedLongRunVariance())).toList(),
                "autocorrelation", result.acf().stream().map(acf -> object("lag", acf.lag(),
                        "pair_count", acf.pairCount(), "return_acf", acf.returnAcf(),
                        "squared_centered_return_acf", acf.squaredCenteredReturnAcf(),
                        "influence_acf", acf.influenceAcf(), "undefined_reason", acf.undefinedReason())).toList(),
                "selection_adjusted", false);
    }

    private static Map<String, Object> outcomeJson(OutcomeProvenance provenance, List<TrialReport> trials) {
        if (provenance == null) return object("supplied", false);
        var summary = provenance.summary();
        var eventCounts = object("ATTEMPT_STARTED", summary.attemptsStarted(), "OUTCOME_PRODUCED", summary.outcomesProduced(),
                "OUTCOME_VIEWED", summary.outcomeViews(), "MECHANICAL_FAILURE_NO_OUTCOME", summary.mechanicalFailuresWithoutOutcome(),
                "ABANDONED", summary.abandonedAttempts());
        Set<String> familyIds = trials.stream().map(trial -> trial.registration().trial().trialId())
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Object> allStates = object();
        Map<String, Object> familyStates = object();
        for (ResearchOutcomeLedger.ExposureState state : ResearchOutcomeLedger.ExposureState.values()) {
            allStates.put(state.name(), summary.trials().stream().filter(trial -> trial.exposureState() == state).count());
            familyStates.put(state.name(), summary.trials().stream().filter(trial ->
                    familyIds.contains(trial.trialId()) && trial.exposureState() == state).count());
        }
        return object("supplied", true, "file_sha256", provenance.fileSha256(), "ledger_final_sha256", summary.finalHashSha256(),
                "event_count", summary.eventCount(), "registered_trial_count", summary.trials().size(),
                "comparison_family_trial_count", familyIds.size(), "event_type_counts", eventCounts,
                "exposure_state_trial_counts", allStates, "comparison_family_exposure_state_trial_counts", familyStates,
                "used_for_selection_or_scoring", false,
                "completeness_independently_proven", false);
    }
}
