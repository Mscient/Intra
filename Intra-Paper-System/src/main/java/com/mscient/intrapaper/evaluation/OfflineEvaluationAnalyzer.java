package com.mscient.intrapaper.evaluation;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.data.StrictJsonParser;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.simulation.RunArtifactVerifier;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.math.BigDecimal;
import java.math.MathContext;

/** Offline statistics over verified, preregistered run artifacts. HOLDOUT P&L never enters the calculations. */
public final class OfflineEvaluationAnalyzer {
    private static final int PERIODS_PER_YEAR = SessionReturnStatistics.PERIODS_PER_YEAR;
    private static final int MAX_INDEX_BYTES = 16 * 1024 * 1024;
    private static final Pattern TRIAL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern RUN_ID = Pattern.compile("[0-9a-f]{24}");
    private static final Pattern DECIMAL = Pattern.compile("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
    private static final List<String> SESSION_HEADERS = List.of(
            "session_date", "market_rule_regime_id", "regime_source_reference", "expected_bars", "observed_bars",
            "complete", "position_units_at_start", "position_units_at_end", "realized_pnl_change_inr",
            "unrealized_pnl_change_inr", "fees_paid_inr", "modeled_slippage_inr", "net_pnl_change_inr",
            "orders_created", "fills", "completed_trades", "risk_halts", "ending_risk_state");
    private static final List<String> PARTITION_HEADERS = List.of(
            "partition", "first_session", "last_session", "session_count", "complete_session_count",
            "position_units_at_start", "position_units_at_end", "flat_at_partition_boundaries",
            "realized_pnl_change_inr", "unrealized_pnl_change_inr", "net_pnl_change_inr",
            "max_session_close_drawdown_inr", "fees_paid_inr", "modeled_slippage_inr", "orders_created",
            "fills", "completed_trades", "risk_halts", "status", "performance_claim_eligible");

    public record TrialSummary(ResearchTrialLedger.Entry registration, String runId, int sessionCount,
            BigDecimal totalNetPnlInr, double cumulativeSimpleReturn, double meanSessionReturn,
            double dailySharpe, double annualizedSharpe, double skewness, double kurtosis,
            double deflatedSharpeProbability, double dsrProbabilityAtOneEffectiveTrial,
            double dsrProbabilityAtRawFamilyTrialCount) { }

    public record PboSummary(int chronologicalBlockCount, int combinations,
            int belowMedianCount, double probabilityBacktestOverfitting) { }

    public record Report(String trialLedgerFinalHash, int registeredTrialCount, int comparisonFamilyTrialCount,
            String runIndexSha256,
            String datasetSha256, String splitPlanSha256, BigDecimal analysisCapitalInr,
            LocalDate firstEvaluationSession, LocalDate lastEvaluationSession,
            int evaluationSessionCount, int excludedHoldoutSessionCount,
            double sampleStandardDeviationAcrossTrialSharpes, double expectedMaximumSharpeThresholdDaily,
            List<TrialSummary> trials, PboSummary pbo, ResearchOutcomeLedger.Summary outcomeHistory) {
        public Report(String trialLedgerFinalHash, int registeredTrialCount, int comparisonFamilyTrialCount,
                String runIndexSha256, String datasetSha256, String splitPlanSha256,
                BigDecimal analysisCapitalInr, LocalDate firstEvaluationSession, LocalDate lastEvaluationSession,
                int evaluationSessionCount, int excludedHoldoutSessionCount,
                double sampleStandardDeviationAcrossTrialSharpes, double expectedMaximumSharpeThresholdDaily,
                List<TrialSummary> trials, PboSummary pbo) {
            this(trialLedgerFinalHash, registeredTrialCount, comparisonFamilyTrialCount, runIndexSha256,
                    datasetSha256, splitPlanSha256, analysisCapitalInr, firstEvaluationSession,
                    lastEvaluationSession, evaluationSessionCount, excludedHoldoutSessionCount,
                    sampleStandardDeviationAcrossTrialSharpes, expectedMaximumSharpeThresholdDaily,
                    trials, pbo, null);
        }
        public Report {
            trials = List.copyOf(trials);
        }

        /** Stable, self-describing JSON output. It intentionally contains no holdout performance values. */
        public String toJson() {
            StringBuilder json = new StringBuilder(2048);
            json.append("{\n")
                    .append("  \"schema\": \"INTRA_OFFLINE_STATISTICAL_EVALUATION_V2\",\n")
                    .append("  \"trial_ledger_final_sha256\": ").append(q(trialLedgerFinalHash)).append(",\n")
                    .append("  \"registered_trial_count\": ").append(registeredTrialCount).append(",\n")
                    .append("  \"comparison_family_trial_count\": ").append(comparisonFamilyTrialCount).append(",\n")
                    .append("  \"run_index_sha256\": ").append(q(runIndexSha256)).append(",\n")
                    .append("  \"dataset_sha256\": ").append(q(datasetSha256)).append(",\n")
                    .append("  \"split_plan_sha256\": ").append(q(splitPlanSha256)).append(",\n")
                    .append("  \"analysis_capital_inr\": ").append(decimal(analysisCapitalInr)).append(",\n")
                    .append("  \"return_basis\": \"session net P&L divided by fixed analysis capital; simple, non-compounded\",\n")
                    .append("  \"periods_per_year\": ").append(PERIODS_PER_YEAR).append(",\n")
                    .append("  \"included_partitions\": [\"DEVELOPMENT\", \"VALIDATION\"],\n")
                    .append("  \"holdout_included\": false,\n")
                    .append("  \"holdout_data_used_for_calculation\": false,\n")
                    .append("  \"first_evaluation_session\": ").append(q(firstEvaluationSession.toString())).append(",\n")
                    .append("  \"last_evaluation_session\": ").append(q(lastEvaluationSession.toString())).append(",\n")
                    .append("  \"evaluation_session_count\": ").append(evaluationSessionCount).append(",\n")
                    .append("  \"excluded_holdout_session_count\": ").append(excludedHoldoutSessionCount).append(",\n")
                    .append("  \"trial_daily_sharpe_sample_stddev\": ")
                    .append(number(sampleStandardDeviationAcrossTrialSharpes)).append(",\n")
                    .append("  \"dsr_expected_max_daily_sharpe_threshold\": ")
                    .append(number(expectedMaximumSharpeThresholdDaily)).append(",\n")
                    .append("  \"dsr_effective_trial_count_sensitivity\": {\n")
                    .append("    \"method\": \"expected-maximum DSR approximation evaluated at N_eff=1 and the raw family count\",\n")
                    .append("    \"effective_trial_count_min\": 1,\n")
                    .append("    \"effective_trial_count_max\": ").append(comparisonFamilyTrialCount).append(",\n")
                    .append("    \"raw_family_count_is_upper_scenario\": true,\n")
                    .append("    \"trial_dependence_estimated\": false,\n")
                    .append("    \"interpretation\": \"scenario endpoints only; not an estimated effective count or confidence interval\"\n")
                    .append("  },\n")
                    .append("  \"sharpe_serial_dependence_adjusted\": false,\n");
            if (outcomeHistory != null) {
                json.append("  \"outcome_history\": {\n")
                        .append("    \"ledger_final_sha256\": ").append(q(outcomeHistory.finalHashSha256())).append(",\n")
                        .append("    \"event_count\": ").append(outcomeHistory.eventCount()).append(",\n")
                        .append("    \"event_type_counts\": {");
                int eventTypeIndex = 0;
                for (ResearchOutcomeLedger.EventType type : ResearchOutcomeLedger.EventType.values()) {
                    if (eventTypeIndex++ > 0) json.append(',');
                    json.append("\n      ").append(q(type.name())).append(": ")
                            .append(outcomeEventCount(outcomeHistory, type));
                }
                json.append("\n    },\n")
                        .append("    \"exposure_state_trial_counts\": {");
                int stateIndex = 0;
                for (ResearchOutcomeLedger.ExposureState state : ResearchOutcomeLedger.ExposureState.values()) {
                    if (stateIndex++ > 0) json.append(',');
                    long count = outcomeHistory.trials().stream()
                            .filter(trial -> trial.exposureState() == state).count();
                    json.append("\n      ").append(q(state.name())).append(": ").append(count);
                }
                json.append("\n    }\n")
                        .append("  },\n");
            }
            json.append("  \"pbo\": {\n")
                    .append("    \"method\": \"combinatorially symmetric cross-validation over chronological blocks\",\n")
                    .append("    \"chronological_block_count\": ").append(pbo.chronologicalBlockCount()).append(",\n")
                    .append("    \"combinations\": ").append(pbo.combinations()).append(",\n")
                    .append("    \"selected_winner_below_oos_median_count\": ").append(pbo.belowMedianCount()).append(",\n")
                    .append("    \"probability_backtest_overfitting\": ")
                    .append(number(pbo.probabilityBacktestOverfitting())).append("\n")
                    .append("  },\n")
                    .append("  \"trials\": [\n");
            for (int index = 0; index < trials.size(); index++) {
                TrialSummary summary = trials.get(index);
                ResearchTrialLedger.Trial trial = summary.registration().trial();
                json.append("    {\n")
                        .append("      \"trial_id\": ").append(q(trial.trialId())).append(",\n")
                        .append("      \"run_id\": ").append(q(summary.runId())).append(",\n")
                        .append("      \"strategy_id\": ").append(q(trial.strategyId())).append(",\n")
                        .append("      \"strategy_version\": ").append(q(trial.strategyVersion())).append(",\n")
                        .append("      \"variant_id\": ").append(q(trial.variantId())).append(",\n")
                        .append("      \"registration_record_sha256\": ").append(q(summary.registration().recordHashSha256())).append(",\n")
                        .append("      \"strategy_card_sha256\": ").append(q(trial.strategyCardSha256())).append(",\n")
                        .append("      \"dataset_sha256\": ").append(q(trial.datasetSha256())).append(",\n")
                        .append("      \"split_plan_sha256\": ").append(q(trial.splitPlanSha256())).append(",\n")
                        .append("      \"parameter_config_sha256\": ").append(q(trial.parameterConfigSha256())).append(",\n")
                        .append("      \"session_count\": ").append(summary.sessionCount()).append(",\n")
                        .append("      \"total_net_pnl_inr\": ").append(decimal(summary.totalNetPnlInr())).append(",\n")
                        .append("      \"cumulative_simple_return\": ").append(number(summary.cumulativeSimpleReturn())).append(",\n")
                        .append("      \"mean_session_return\": ").append(number(summary.meanSessionReturn())).append(",\n")
                        .append("      \"daily_sharpe\": ").append(number(summary.dailySharpe())).append(",\n")
                        .append("      \"annualized_sharpe_252\": ").append(number(summary.annualizedSharpe())).append(",\n")
                        .append("      \"return_skewness\": ").append(number(summary.skewness())).append(",\n")
                        .append("      \"return_kurtosis\": ").append(number(summary.kurtosis())).append(",\n")
                        .append("      \"deflated_sharpe_probability\": ").append(number(summary.deflatedSharpeProbability())).append(",\n")
                        .append("      \"dsr_probability_at_one_effective_trial\": ")
                        .append(number(summary.dsrProbabilityAtOneEffectiveTrial())).append(",\n")
                        .append("      \"dsr_probability_at_raw_family_trial_count\": ")
                        .append(number(summary.dsrProbabilityAtRawFamilyTrialCount())).append(",\n")
                        .append("      \"dsr_multiplicity_in_dataset_split_family\": ").append(comparisonFamilyTrialCount).append(",\n")
                        .append("      \"performance_claim_eligible\": false\n")
                        .append("    }");
                if (index + 1 < trials.size()) json.append(',');
                json.append('\n');
            }
            json.append("  ],\n")
                    .append("  \"performance_claim_eligible\": false\n")
                    .append("}\n");
            return json.toString();
        }
    }

    private record IndexRow(String trialId, String runId, BigDecimal analysisCapitalInr) { }
    private record RunIndex(List<IndexRow> rows, String sha256) { }
    private record SessionRow(LocalDate date, boolean complete, int positionAtStart,
            int positionAtEnd, String netPnlInr) { }
    private record PartitionAudit(int sessionCount, int completeSessionCount,
            LocalDate firstSession, LocalDate lastSession, int positionAtStart,
            int positionAtEnd, boolean flat, String status, BigDecimal netPnlInr) { }
    private record RunData(ResearchTrialLedger.Entry registration, String runId,
            BigDecimal analysisCapitalInr, String datasetSha256, String splitPlanSha256,
            List<LocalDate> allSessionDates, List<LocalDate> evaluationDates,
            double[] returns, BigDecimal totalNetPnlInr, int holdoutSessionCount) { }

    private OfflineEvaluationAnalyzer() { }

    private static long outcomeEventCount(ResearchOutcomeLedger.Summary summary,
            ResearchOutcomeLedger.EventType type) {
        return switch (type) {
            case ATTEMPT_STARTED -> summary.attemptsStarted();
            case OUTCOME_PRODUCED -> summary.outcomesProduced();
            case OUTCOME_VIEWED -> summary.outcomeViews();
            case MECHANICAL_FAILURE_NO_OUTCOME -> summary.mechanicalFailuresWithoutOutcome();
            case ABANDONED -> summary.abandonedAttempts();
        };
    }

    /**
     * Verifies each referenced run and binds it to the append-only trial ledger before computing statistics.
     * The only return observations used are DEVELOPMENT and VALIDATION session P&L values.
     */
    public static Report analyze(Path trialLedgerPath, Path runIndexPath, Path splitPlanPath,
            Path runsRoot, int chronologicalBlockCount) throws IOException {
        return analyze(trialLedgerPath, runIndexPath, splitPlanPath, runsRoot, chronologicalBlockCount, null);
    }

    /** Optional outcome-history verification adds provenance only; it does not select trials or change scores. */
    public static Report analyze(Path trialLedgerPath, Path runIndexPath, Path splitPlanPath,
            Path runsRoot, int chronologicalBlockCount, Path outcomeHistoryLedgerPath) throws IOException {
        SessionReturnStatistics.requireBlockCount(chronologicalBlockCount);

        ResearchTrialLedger.Summary ledgerSummary = ResearchTrialLedger.verify(trialLedgerPath);
        List<ResearchTrialLedger.Entry> registrations = ResearchTrialLedger.entries(trialLedgerPath);
        if (ledgerSummary.entryCount() != registrations.size()
                || (!registrations.isEmpty()
                && !ledgerSummary.finalHashSha256().equals(registrations.getLast().recordHashSha256()))) {
            throw new IOException("Research trial ledger changed while it was being read");
        }
        ResearchOutcomeLedger.Summary outcomeHistory = null;
        if (outcomeHistoryLedgerPath != null) {
            outcomeHistory = ResearchOutcomeLedger.verify(trialLedgerPath, outcomeHistoryLedgerPath);
            ResearchTrialLedger.Summary ledgerAfterOutcomeRead = ResearchTrialLedger.verify(trialLedgerPath);
            if (!ledgerSummary.finalHashSha256().equals(ledgerAfterOutcomeRead.finalHashSha256())) {
                throw new IOException("Research trial ledger changed while outcome history was being verified");
            }
        }
        Map<String, ResearchTrialLedger.Entry> registrationById = new HashMap<>();
        for (ResearchTrialLedger.Entry registration : registrations) {
            registrationById.put(registration.trial().trialId(), registration);
        }

        RunIndex runIndex = readRunIndex(runIndexPath);
        EvaluationSplitPlan splitPlan = EvaluationSplitPlan.loadForEvaluation(splitPlanPath);
        if (runIndex.rows().size() < 2) {
            throw new IOException("Statistical evaluation requires at least two preregistered trial runs for PBO");
        }
        Path absoluteRunsRoot = runsRoot.toAbsolutePath().normalize();
        List<RunData> runs = new ArrayList<>(runIndex.rows().size());
        Set<String> analyzedTrialIds = new HashSet<>();
        Set<String> analyzedRunIds = new HashSet<>();
        for (IndexRow row : runIndex.rows()) {
            if (!analyzedTrialIds.add(row.trialId())) throw new IOException("Duplicate trial_id in run index: " + row.trialId());
            if (!analyzedRunIds.add(row.runId())) throw new IOException("Duplicate run_id in run index: " + row.runId());
            ResearchTrialLedger.Entry registration = registrationById.get(row.trialId());
            if (registration == null) throw new IOException("Trial is not preregistered: " + row.trialId());
            Path runFolder = absoluteRunsRoot.resolve(row.runId()).normalize();
            if (!runFolder.startsWith(absoluteRunsRoot)) throw new IOException("Run ID escaped the configured runs root");
            runs.add(readRun(runFolder, row, registration, splitPlan));
        }
        runs.sort(Comparator.comparing(run -> run.registration().trial().trialId()));

        RunData first = runs.getFirst();
        for (RunData run : runs) {
            if (!first.datasetSha256().equals(run.datasetSha256())) {
                throw new IOException("All analyzed trials must use the same preregistered dataset hash");
            }
            if (!first.splitPlanSha256().equals(run.splitPlanSha256())) {
                throw new IOException("All analyzed trials must use the same preregistered split-plan hash");
            }
            if (!first.analysisCapitalInr().equals(run.analysisCapitalInr())) {
                throw new IOException("All trials must use the same analysis_capital_inr");
            }
            if (!first.allSessionDates().equals(run.allSessionDates())) {
                throw new IOException("All analyzed runs must contain the same chronological session rows");
            }
            if (!first.evaluationDates().equals(run.evaluationDates())) {
                throw new IOException("All analyzed runs must contain the same development/validation sessions");
            }
        }
        Set<String> analyzedIds = runs.stream().map(run -> run.registration().trial().trialId())
                .collect(java.util.stream.Collectors.toSet());
        int familyTrialCount = 0;
        for (ResearchTrialLedger.Entry registration : registrations) {
            ResearchTrialLedger.Trial trial = registration.trial();
            if (first.datasetSha256().equals(trial.datasetSha256())
                    && first.splitPlanSha256().equals(trial.splitPlanSha256())) {
                familyTrialCount++;
                if (!analyzedIds.contains(trial.trialId())) {
                    throw new IOException("Run index omits preregistered comparison-family trial: " + trial.trialId());
                }
            }
        }
        if (familyTrialCount != runs.size()) {
            throw new IOException("Run index must contain every registration sharing its dataset and split-plan hashes");
        }
        if (first.evaluationDates().size() < chronologicalBlockCount * 2) {
            throw new IOException("Development and validation must contain at least two sessions per chronological block");
        }

        SessionReturnStatistics.Summary statistics = SessionReturnStatistics.analyze(
                runs.stream().map(run -> new SessionReturnStatistics.Series(
                        run.registration().trial().trialId(), run.returns())).toList(), chronologicalBlockCount);
        List<TrialSummary> trialSummaries = new ArrayList<>(runs.size());
        for (int index = 0; index < runs.size(); index++) {
            RunData run = runs.get(index);
            SessionReturnStatistics.TrialStatistics summary = statistics.trials().get(index);
            double cumulativeReturn = run.totalNetPnlInr()
                    .divide(run.analysisCapitalInr(), MathContext.DECIMAL128).doubleValue();
            if (!Double.isFinite(cumulativeReturn)) {
                throw new IOException("Cumulative return is outside the supported numeric range");
            }
            trialSummaries.add(new TrialSummary(run.registration(), run.runId(), summary.sessionCount(),
                    run.totalNetPnlInr(), cumulativeReturn, summary.meanSessionReturn(), summary.dailySharpe(),
                    summary.annualizedSharpe(), summary.skewness(), summary.kurtosis(),
                    summary.deflatedSharpeProbability(), summary.dsrProbabilityAtOneEffectiveTrial(),
                    summary.dsrProbabilityAtRawFamilyTrialCount()));
        }
        SessionReturnStatistics.PboSummary sharedPbo = statistics.pbo();
        PboSummary pbo = new PboSummary(sharedPbo.chronologicalBlockCount(), sharedPbo.combinations(),
                sharedPbo.belowMedianCount(), sharedPbo.probabilityBacktestOverfitting());
        double trialSharpeSampleStdDev = statistics.sampleStandardDeviationAcrossTrialSharpes();
        double expectedMaxDailySharpe = statistics.expectedMaximumSharpeThresholdDaily();
        return new Report(ledgerSummary.finalHashSha256(), Math.toIntExact(ledgerSummary.entryCount()), familyTrialCount,
                runIndex.sha256(), first.datasetSha256(), first.splitPlanSha256(), first.analysisCapitalInr(),
                first.evaluationDates().getFirst(), first.evaluationDates().getLast(),
                first.evaluationDates().size(), first.holdoutSessionCount(), trialSharpeSampleStdDev,
                expectedMaxDailySharpe, trialSummaries, pbo, outcomeHistory);
    }

    private static RunData readRun(Path runFolder, IndexRow indexRow,
            ResearchTrialLedger.Entry registration, EvaluationSplitPlan splitPlan) throws IOException {
        RunArtifactVerifier.Verification verification = RunArtifactVerifier.verify(runFolder);
        if (!verification.runId().equals(indexRow.runId())) throw new IOException("Run ID mismatch for " + indexRow.trialId());
        String manifestText = decodeUtf8(Files.readAllBytes(runFolder.resolve("run-manifest.json")), "run manifest");
        Map<String, Object> manifest = object(StrictJsonParser.parse(manifestText), "run manifest");
        if (!verification.runId().equals(string(manifest.get("run_id"), "run_id"))) {
            throw new IOException("Run manifest identity mismatch for " + indexRow.trialId());
        }
        if (!Boolean.FALSE.equals(manifest.get("performance_claim_eligible"))) {
            throw new IOException("Run artifact does not declare performance_claim_eligible=false");
        }

        ResearchTrialLedger.Trial trial = registration.trial();
        requireEqual(trial.splitPlanSha256(), splitPlan.splitFileSha256(), "supplied split-plan hash");
        Map<String, Object> recordedTrial = object(manifest.get("research_trial"), "research_trial");
        requireEqual(trial.trialId(), string(recordedTrial.get("trial_id"), "research_trial.trial_id"), "trial_id");
        requireEqual(trial.strategyId(), string(recordedTrial.get("strategy_id"), "research_trial.strategy_id"), "strategy_id");
        requireEqual(trial.strategyVersion(), string(recordedTrial.get("strategy_version"), "research_trial.strategy_version"), "strategy_version");
        requireEqual(trial.variantId(), string(recordedTrial.get("variant_id"), "research_trial.variant_id"), "variant_id");
        requireEqual(trial.strategyCardSha256(), string(recordedTrial.get("strategy_card_sha256"), "research_trial.strategy_card_sha256"), "strategy-card hash");
        requireEqual(trial.datasetSha256(), string(recordedTrial.get("dataset_sha256"), "research_trial.dataset_sha256"), "dataset hash");
        requireEqual(trial.splitPlanSha256(), string(recordedTrial.get("split_plan_sha256"), "research_trial.split_plan_sha256"), "split-plan hash");
        requireEqual(trial.parameterConfigSha256(), string(recordedTrial.get("parameter_config_sha256"), "research_trial.parameter_config_sha256"), "parameter-config hash");
        requireEqual(registration.recordHashSha256(), string(recordedTrial.get("registration_record_sha256"),
                "research_trial.registration_record_sha256"), "registration record hash");
        if (integer(recordedTrial.get("registration_sequence"), "research_trial.registration_sequence")
                != registration.sequence()) {
            throw new IOException("Registered sequence does not match run identity for " + trial.trialId());
        }

        Map<String, Object> strategy = object(manifest.get("strategy"), "strategy");
        requireEqual(trial.strategyId(), string(strategy.get("id"), "strategy.id"), "strategy identity");
        Map<String, Object> inputs = object(manifest.get("input_file_sha256"), "input_file_sha256");
        requireEqual(trial.splitPlanSha256(), string(inputs.get("evaluation_split_plan"),
                "input_file_sha256.evaluation_split_plan"), "input split-plan hash");
        Map<String, Object> partitionReport = object(manifest.get("evaluation_partition_report"), "evaluation_partition_report");
        requireEqual(trial.splitPlanSha256(), string(partitionReport.get("split_plan_sha256"),
                "evaluation_partition_report.split_plan_sha256"), "partition-report split hash");
        requireEqual("evaluation-partitions.csv", string(partitionReport.get("file"), "evaluation_partition_report.file"),
                "partition-report file");
        requireEqual("SESSION_NET_PNL_CHANGE_SUM", string(partitionReport.get("aggregation"),
                "evaluation_partition_report.aggregation"), "partition aggregation");
        if (!Boolean.FALSE.equals(partitionReport.get("performance_claim_eligible"))) {
            throw new IOException("Partition report does not declare performance_claim_eligible=false");
        }

        Map<EvaluationSplitPlan.Partition, PartitionAudit> partitions = readPartitions(runFolder.resolve("evaluation-partitions.csv"));
        List<SessionRow> sessionRows = readSessions(runFolder.resolve("sessions.csv"));
        List<LocalDate> allDates = new ArrayList<>(sessionRows.size());
        Set<LocalDate> uniqueDates = new HashSet<>();
        LocalDate previousDate = null;
        for (SessionRow session : sessionRows) {
            if (!uniqueDates.add(session.date())) throw new IOException("Duplicate session row: " + session.date());
            if (previousDate != null && !session.date().isAfter(previousDate)) {
                throw new IOException("Session rows are not strictly chronological");
            }
            if (!session.complete()) throw new IOException("Incomplete session row: " + session.date());
            allDates.add(session.date());
            previousDate = session.date();
        }

        List<SessionRow> evaluationRows = new ArrayList<>();
        int cursor = 0;
        for (EvaluationSplitPlan.Partition partition : EvaluationSplitPlan.Partition.values()) {
            PartitionAudit audit = partitions.get(partition);
            List<LocalDate> expectedDates = splitPlan.sessions().get(partition);
            int end = cursor + audit.sessionCount();
            if (end > sessionRows.size()) throw new IOException("Partition counts exceed session rows");
            List<SessionRow> rows = sessionRows.subList(cursor, end);
            if (audit.sessionCount() != expectedDates.size()
                    || !rows.stream().map(SessionRow::date).toList().equals(expectedDates)) {
                throw new IOException("Session rows do not exactly match the registered " + partition + " split dates");
            }
            if (!rows.getFirst().date().equals(audit.firstSession()) || !rows.getLast().date().equals(audit.lastSession())) {
                throw new IOException("Session date range does not match " + partition + " partition report");
            }
            if (audit.completeSessionCount() != audit.sessionCount()
                    || !"DIAGNOSTIC_ONLY".equals(audit.status()) || !audit.flat()
                    || audit.positionAtStart() != 0 || audit.positionAtEnd() != 0) {
                throw new IOException("Incomplete or position-contaminated " + partition + " partition");
            }
            if (rows.getFirst().positionAtStart() != audit.positionAtStart()
                    || rows.getLast().positionAtEnd() != audit.positionAtEnd()) {
                throw new IOException("Partition boundary positions do not match session ledger");
            }
            if (partition != EvaluationSplitPlan.Partition.HOLDOUT) {
                BigDecimal partitionPnl = BigDecimal.ZERO;
                for (SessionRow session : rows) {
                    try {
                        partitionPnl = partitionPnl.add(new BigDecimal(session.netPnlInr()));
                    } catch (NumberFormatException invalidPnl) {
                        throw new IOException("Invalid net P&L for evaluation session " + session.date(), invalidPnl);
                    }
                }
                if (partitionPnl.compareTo(audit.netPnlInr()) != 0) {
                    throw new IOException("Session net P&L does not reconcile to " + partition + " partition report");
                }
                evaluationRows.addAll(rows);
            }
            cursor = end;
        }
        if (cursor != sessionRows.size()) throw new IOException("Session rows do not match the three partition reports");
        List<LocalDate> plannedDates = Arrays.stream(EvaluationSplitPlan.Partition.values())
                .flatMap(partition -> splitPlan.sessions().get(partition).stream()).toList();
        if (!allDates.equals(plannedDates)) throw new IOException("Session rows are missing from or extra to the registered split plan");
        if (evaluationRows.isEmpty()) throw new IOException("Development and validation partitions are empty");

        double[] returns = new double[evaluationRows.size()];
        BigDecimal totalPnl = BigDecimal.ZERO;
        for (int index = 0; index < evaluationRows.size(); index++) {
            SessionRow session = evaluationRows.get(index);
            BigDecimal netPnl;
            try {
                netPnl = new BigDecimal(session.netPnlInr());
            } catch (NumberFormatException invalidPnl) {
                throw new IOException("Invalid net P&L for evaluation session " + session.date(), invalidPnl);
            }
            totalPnl = totalPnl.add(netPnl);
            double sessionReturn = netPnl.divide(indexRow.analysisCapitalInr(), MathContext.DECIMAL128).doubleValue();
            if (!Double.isFinite(sessionReturn)) throw new IOException("Non-finite session return for " + session.date());
            returns[index] = sessionReturn;
        }
        return new RunData(registration, indexRow.runId(), indexRow.analysisCapitalInr(), trial.datasetSha256(),
                trial.splitPlanSha256(), List.copyOf(allDates),
                evaluationRows.stream().map(SessionRow::date).toList(), returns, totalPnl,
                splitPlan.sessions().get(EvaluationSplitPlan.Partition.HOLDOUT).size());
    }

    private static Map<EvaluationSplitPlan.Partition, PartitionAudit> readPartitions(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        if (!table.headers().equals(PARTITION_HEADERS)) throw new IOException("Unexpected evaluation-partitions.csv header");
        if (table.rows().size() != EvaluationSplitPlan.Partition.values().length) {
            throw new IOException("Evaluation partition report must contain exactly three partitions");
        }
        EnumMap<EvaluationSplitPlan.Partition, PartitionAudit> partitions = new EnumMap<>(EvaluationSplitPlan.Partition.class);
        for (Map<String, String> row : table.rows()) {
            EvaluationSplitPlan.Partition partition;
            try {
                partition = EvaluationSplitPlan.Partition.valueOf(row.get("partition"));
            } catch (RuntimeException invalidPartition) {
                throw new IOException("Invalid partition name in evaluation-partitions.csv", invalidPartition);
            }
            int sessionCount = integer(row.get("session_count"), "session_count");
            int completeCount = integer(row.get("complete_session_count"), "complete_session_count");
            int positionAtStart = integer(row.get("position_units_at_start"), "position_units_at_start");
            int positionAtEnd = integer(row.get("position_units_at_end"), "position_units_at_end");
            boolean flat = bool(row.get("flat_at_partition_boundaries"), "flat_at_partition_boundaries");
            boolean eligible = bool(row.get("performance_claim_eligible"), "performance_claim_eligible");
            if (sessionCount < 1 || completeCount < 0 || completeCount > sessionCount || eligible) {
                throw new IOException("Invalid/ineligible partition row: " + partition);
            }
            LocalDate first = date(row.get("first_session"), "first_session");
            LocalDate last = date(row.get("last_session"), "last_session");
            if (first.isAfter(last)) throw new IOException("Invalid partition date range: " + partition);
            BigDecimal netPnl = partition == EvaluationSplitPlan.Partition.HOLDOUT
                    ? null : parseDecimal(row.get("net_pnl_change_inr"), "net_pnl_change_inr");
            PartitionAudit audit = new PartitionAudit(sessionCount, completeCount, first, last,
                    positionAtStart, positionAtEnd, flat, row.get("status"), netPnl);
            if (partitions.putIfAbsent(partition, audit) != null) throw new IOException("Duplicate partition row: " + partition);
        }
        for (EvaluationSplitPlan.Partition partition : EvaluationSplitPlan.Partition.values()) {
            if (!partitions.containsKey(partition)) throw new IOException("Missing partition row: " + partition);
        }
        return partitions;
    }

    private static List<SessionRow> readSessions(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        if (!table.headers().equals(SESSION_HEADERS)) throw new IOException("Unexpected sessions.csv header");
        List<SessionRow> result = new ArrayList<>(table.rows().size());
        for (Map<String, String> row : table.rows()) {
            result.add(new SessionRow(date(row.get("session_date"), "session_date"),
                    bool(row.get("complete"), "complete"), integer(row.get("position_units_at_start"), "position_units_at_start"),
                    integer(row.get("position_units_at_end"), "position_units_at_end"), row.get("net_pnl_change_inr")));
        }
        if (result.isEmpty()) throw new IOException("Run has no session rows");
        return List.copyOf(result);
    }

    private static RunIndex readRunIndex(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0 || bytes.length > MAX_INDEX_BYTES) throw new IOException("Run index is empty or exceeds 16 MiB");
        String text = decodeUtf8(bytes, "evaluation run index");
        if (text.startsWith("\uFEFF")) throw new IOException("Run index must not contain a UTF-8 BOM");
        if (text.indexOf('\r') >= 0) throw new IOException("Run index must use LF line endings");
        if (!text.endsWith("\n")) throw new IOException("Run index must end with a newline");
        String[] lines = text.split("\n", -1);
        if (!lines[0].equals("trial_id,run_id,analysis_capital_inr")) {
            throw new IOException("Run index header must be exactly: trial_id,run_id,analysis_capital_inr");
        }
        List<IndexRow> rows = new ArrayList<>();
        Set<String> trialIds = new HashSet<>();
        Set<String> runIds = new HashSet<>();
        for (int index = 1; index < lines.length - 1; index++) {
            String line = lines[index];
            if (line.isEmpty() || line.indexOf('"') >= 0) {
                throw new IOException("Run index row " + (index + 1) + " must be non-empty and unquoted");
            }
            String[] fields = line.split(",", -1);
            if (fields.length != 3) throw new IOException("Run index row " + (index + 1) + " must contain exactly three fields");
            if (!fields[0].equals(fields[0].strip()) || !TRIAL_ID.matcher(fields[0]).matches()) {
                throw new IOException("Invalid trial_id at run index row " + (index + 1));
            }
            if (!fields[1].equals(fields[1].strip()) || !RUN_ID.matcher(fields[1]).matches()) {
                throw new IOException("Invalid run_id at run index row " + (index + 1));
            }
            if (!fields[2].equals(fields[2].strip()) || !DECIMAL.matcher(fields[2]).matches()) {
                throw new IOException("analysis_capital_inr must be a plain decimal at run index row " + (index + 1));
            }
            BigDecimal capital = new BigDecimal(fields[2]);
            if (capital.signum() <= 0) throw new IOException("analysis_capital_inr must be positive at row " + (index + 1));
            if (!trialIds.add(fields[0])) throw new IOException("Duplicate trial_id at run index row " + (index + 1));
            if (!runIds.add(fields[1])) throw new IOException("Duplicate run_id at run index row " + (index + 1));
            rows.add(new IndexRow(fields[0], fields[1], capital.stripTrailingZeros()));
        }
        if (rows.isEmpty()) throw new IOException("Run index must contain at least one trial row");
        return new RunIndex(List.copyOf(rows), RunConfig.sha256(bytes));
    }


    private static String decodeUtf8(byte[] bytes, String description) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException(description + " is not valid UTF-8", invalid);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) throws IOException {
        if (!(value instanceof Map<?, ?> map)) throw new IOException(name + " must be a JSON object");
        return (Map<String, Object>) map;
    }

    private static String string(Object value, String name) throws IOException {
        if (!(value instanceof String text)) throw new IOException(name + " must be a JSON string");
        return text;
    }

    private static long integer(Object value, String name) throws IOException {
        if (value instanceof String text) {
            try { return Long.parseLong(text); }
            catch (NumberFormatException invalid) { throw new IOException(name + " must be an integer", invalid); }
        }
        if (value instanceof BigDecimal number) {
            try { return number.longValueExact(); }
            catch (ArithmeticException invalid) { throw new IOException(name + " must be an integer", invalid); }
        }
        throw new IOException(name + " must be an integer");
    }

    private static int integer(String value, String name) throws IOException {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException invalid) { throw new IOException(name + " must be an integer", invalid); }
    }

    private static BigDecimal parseDecimal(String value, String name) throws IOException {
        try { return new BigDecimal(value); }
        catch (NumberFormatException invalid) { throw new IOException(name + " must be a decimal", invalid); }
    }

    private static boolean bool(Object value, String name) throws IOException {
        if (!(value instanceof Boolean result)) throw new IOException(name + " must be a JSON boolean");
        return result;
    }

    private static boolean bool(String value, String name) throws IOException {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IOException(name + " must be exactly true or false");
    }

    private static LocalDate date(String value, String name) throws IOException {
        try {
            LocalDate date = LocalDate.parse(value);
            if (!date.toString().equals(value)) throw new IOException(name + " must use YYYY-MM-DD");
            return date;
        } catch (DateTimeParseException invalid) {
            throw new IOException(name + " must use YYYY-MM-DD", invalid);
        }
    }

    private static void requireEqual(String expected, String actual, String name) throws IOException {
        if (!expected.equals(actual)) throw new IOException("Run artifact " + name + " does not match its preregistration");
    }

    private static String q(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    private static String decimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String number(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Cannot serialize a non-finite statistic");
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

}
