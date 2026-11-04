package com.mscient.intrapaper.evaluation;

import com.mscient.intrapaper.data.CsvTable;
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
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.math.BigDecimal;
import java.math.MathContext;

/** Offline statistics over verified, preregistered run artifacts. HOLDOUT P&L never enters the calculations. */
public final class OfflineEvaluationAnalyzer {
    private static final int PERIODS_PER_YEAR = 252;
    private static final int MAX_CHRONOLOGICAL_BLOCKS = 12;
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
            double deflatedSharpeProbability) { }

    public record PboSummary(int chronologicalBlockCount, int combinations,
            int belowMedianCount, double probabilityBacktestOverfitting) { }

    public record Report(String trialLedgerFinalHash, int registeredTrialCount, int comparisonFamilyTrialCount,
            String runIndexSha256,
            String datasetSha256, String splitPlanSha256, BigDecimal analysisCapitalInr,
            LocalDate firstEvaluationSession, LocalDate lastEvaluationSession,
            int evaluationSessionCount, int excludedHoldoutSessionCount,
            double sampleStandardDeviationAcrossTrialSharpes, double expectedMaximumSharpeThresholdDaily,
            List<TrialSummary> trials, PboSummary pbo) {
        public Report {
            trials = List.copyOf(trials);
        }

        /** Stable, self-describing JSON output. It intentionally contains no holdout performance values. */
        public String toJson() {
            StringBuilder json = new StringBuilder(2048);
            json.append("{\n")
                    .append("  \"schema\": \"INTRA_OFFLINE_STATISTICAL_EVALUATION_V1\",\n")
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
                    .append("  \"pbo\": {\n")
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
    private record Moments(double mean, double sampleStandardDeviation,
            double skewness, double kurtosis) { }

    private OfflineEvaluationAnalyzer() { }

    /**
     * Verifies each referenced run and binds it to the append-only trial ledger before computing statistics.
     * The only return observations used are DEVELOPMENT and VALIDATION session P&L values.
     */
    public static Report analyze(Path trialLedgerPath, Path runIndexPath, Path splitPlanPath,
            Path runsRoot, int chronologicalBlockCount) throws IOException {
        if (chronologicalBlockCount < 4 || chronologicalBlockCount > MAX_CHRONOLOGICAL_BLOCKS
                || chronologicalBlockCount % 2 != 0) {
            throw new IllegalArgumentException("chronological block count must be even and between 4 and "
                    + MAX_CHRONOLOGICAL_BLOCKS);
        }

        ResearchTrialLedger.Summary ledgerSummary = ResearchTrialLedger.verify(trialLedgerPath);
        List<ResearchTrialLedger.Entry> registrations = ResearchTrialLedger.entries(trialLedgerPath);
        if (ledgerSummary.entryCount() != registrations.size()
                || (!registrations.isEmpty()
                && !ledgerSummary.finalHashSha256().equals(registrations.getLast().recordHashSha256()))) {
            throw new IOException("Research trial ledger changed while it was being read");
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

        List<Moments> trialMoments = new ArrayList<>(runs.size());
        double[] dailySharpes = new double[runs.size()];
        double sumDailySharpe = 0.0;
        for (RunData run : runs) {
            Moments moments = moments(run.returns());
            double dailySharpe = moments.mean() / moments.sampleStandardDeviation();
            if (!Double.isFinite(dailySharpe)) {
                throw new IOException("Daily Sharpe is outside the supported numeric range for "
                        + run.registration().trial().trialId());
            }
            dailySharpes[trialMoments.size()] = dailySharpe;
            sumDailySharpe += dailySharpe;
            trialMoments.add(moments);
        }
        double meanTrialSharpe = sumDailySharpe / dailySharpes.length;
        double sumSquaredSharpeDeviation = 0.0;
        for (double sharpe : dailySharpes) {
            double deviation = sharpe - meanTrialSharpe;
            sumSquaredSharpeDeviation += deviation * deviation;
        }
        double trialSharpeSampleStdDev = Math.sqrt(sumSquaredSharpeDeviation / (dailySharpes.length - 1.0));
        if (!Double.isFinite(trialSharpeSampleStdDev)) {
            throw new IOException("Cross-trial Sharpe dispersion is outside the supported numeric range");
        }
        double expectedMaxDailySharpe = expectedMaxStandardNormal(familyTrialCount) * trialSharpeSampleStdDev;
        List<TrialSummary> trialSummaries = new ArrayList<>(runs.size());
        for (int runNumber = 0; runNumber < runs.size(); runNumber++) {
            RunData run = runs.get(runNumber);
            Moments moments = trialMoments.get(runNumber);
            double dailySharpe = dailySharpes[runNumber];
            double standardError = sharpeStandardError(dailySharpe, moments, run.returns().length);
            double dsr = normalCdf((dailySharpe - expectedMaxDailySharpe) / standardError);
            double cumulativeReturn = run.totalNetPnlInr()
                    .divide(run.analysisCapitalInr(), MathContext.DECIMAL128).doubleValue();
            double annualizedSharpe = dailySharpe * Math.sqrt(PERIODS_PER_YEAR);
            if (!Double.isFinite(dailySharpe) || !Double.isFinite(annualizedSharpe)
                    || !Double.isFinite(cumulativeReturn) || !Double.isFinite(dsr)) {
                throw new IOException("Evaluation statistic is outside the supported numeric range for "
                        + run.registration().trial().trialId());
            }
            trialSummaries.add(new TrialSummary(run.registration(), run.runId(), run.returns().length,
                    run.totalNetPnlInr(), cumulativeReturn, moments.mean(), dailySharpe, annualizedSharpe,
                    moments.skewness(), moments.kurtosis(), dsr));
        }

        PboSummary pbo = probabilityBacktestOverfitting(runs, chronologicalBlockCount);
        return new Report(ledgerSummary.finalHashSha256(), Math.toIntExact(ledgerSummary.entryCount()), familyTrialCount,
                runIndex.sha256(), first.datasetSha256(), first.splitPlanSha256(), first.analysisCapitalInr(),
                first.evaluationDates().getFirst(), first.evaluationDates().getLast(),
                first.evaluationDates().size(), first.holdoutSessionCount(), trialSharpeSampleStdDev,
                expectedMaxDailySharpe, trialSummaries, pbo);
    }

    private static RunData readRun(Path runFolder, IndexRow indexRow,
            ResearchTrialLedger.Entry registration, EvaluationSplitPlan splitPlan) throws IOException {
        RunArtifactVerifier.Verification verification = RunArtifactVerifier.verify(runFolder);
        if (!verification.runId().equals(indexRow.runId())) throw new IOException("Run ID mismatch for " + indexRow.trialId());
        String manifestText = decodeUtf8(Files.readAllBytes(runFolder.resolve("run-manifest.json")), "run manifest");
        Map<String, Object> manifest = object(JsonParser.parse(manifestText), "run manifest");
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

    private static PboSummary probabilityBacktestOverfitting(List<RunData> runs, int blockCount) throws IOException {
        int observationCount = runs.getFirst().returns().length;
        if (runs.size() < 2) throw new IOException("PBO requires at least two analyzed trials");
        List<int[]> blocks = new ArrayList<>(blockCount);
        for (int block = 0; block < blockCount; block++) {
            int start = block * observationCount / blockCount;
            int end = (block + 1) * observationCount / blockCount;
            if (end - start < 2) throw new IOException("Each PBO chronological block must contain at least two sessions");
            int[] indices = new int[end - start];
            for (int i = 0; i < indices.length; i++) indices[i] = start + i;
            blocks.add(indices);
        }

        int half = blockCount / 2;
        int combinations = 0;
        int belowMedian = 0;
        int limit = 1 << blockCount;
        for (int mask = 0; mask < limit; mask++) {
            if (Integer.bitCount(mask) != half) continue;
            combinations++;
            boolean[] inSample = new boolean[observationCount];
            for (int block = 0; block < blockCount; block++) {
                if ((mask & (1 << block)) == 0) continue;
                for (int observation : blocks.get(block)) inSample[observation] = true;
            }
            int selectedWinner = -1;
            double bestInSampleSharpe = Double.NEGATIVE_INFINITY;
            for (int trial = 0; trial < runs.size(); trial++) {
                double score = sharpe(runs.get(trial).returns(), inSample);
                if (score > bestInSampleSharpe) {
                    bestInSampleSharpe = score;
                    selectedWinner = trial;
                }
            }
            boolean[] outOfSample = new boolean[observationCount];
            for (int i = 0; i < observationCount; i++) outOfSample[i] = !inSample[i];
            double winnerOutOfSample = sharpe(runs.get(selectedWinner).returns(), outOfSample);
            int less = 0;
            int equal = 0;
            for (RunData run : runs) {
                double score = sharpe(run.returns(), outOfSample);
                int comparison = Double.compare(score, winnerOutOfSample);
                if (comparison < 0) less++;
                else if (comparison == 0) equal++;
            }
            double averageRank = less + (equal + 1.0) / 2.0;
            double relativeRank = averageRank / (runs.size() + 1.0);
            double lambda = Math.log(relativeRank / (1.0 - relativeRank));
            if (lambda < 0.0) belowMedian++;
        }
        if (combinations == 0) throw new IOException("No valid PBO block combinations");
        return new PboSummary(blockCount, combinations, belowMedian, (double) belowMedian / combinations);
    }

    private static double sharpe(double[] values, boolean[] selection) throws IOException {
        int count = 0;
        for (boolean included : selection) if (included) count++;
        if (count < 2) throw new IOException("PBO fold contains fewer than two sessions");
        double[] selected = new double[count];
        int cursor = 0;
        for (int index = 0; index < selection.length; index++) {
            if (selection[index]) selected[cursor++] = values[index];
        }
        Moments moments = moments(selected);
        if (moments.sampleStandardDeviation() == 0.0) {
            throw new IOException("Sharpe is undefined for a zero-variance PBO fold");
        }
        return moments.mean() / moments.sampleStandardDeviation();
    }

    private static Moments moments(double[] values) throws IOException {
        if (values.length < 2) throw new IOException("At least two return observations are required");
        double sum = 0.0;
        double compensation = 0.0;
        for (double value : values) {
            if (!Double.isFinite(value)) throw new IOException("Non-finite return observation");
            double adjusted = value - compensation;
            double next = sum + adjusted;
            compensation = (next - sum) - adjusted;
            sum = next;
        }
        double mean = sum / values.length;
        double second = 0.0;
        double third = 0.0;
        double fourth = 0.0;
        for (double value : values) {
            double delta = value - mean;
            double square = delta * delta;
            second += square;
            third += square * delta;
            fourth += square * square;
        }
        double populationVariance = second / values.length;
        double sampleDeviation = Math.sqrt(second / (values.length - 1.0));
        if (populationVariance == 0.0 || sampleDeviation == 0.0) {
            throw new IOException("Sharpe is undefined for a zero-variance return series");
        }
        double skewness = (third / values.length) / Math.pow(populationVariance, 1.5);
        double kurtosis = (fourth / values.length) / (populationVariance * populationVariance);
        if (!Double.isFinite(mean) || !Double.isFinite(sampleDeviation)
                || !Double.isFinite(skewness) || !Double.isFinite(kurtosis)) {
            throw new IOException("Return moments are outside the supported numeric range");
        }
        return new Moments(mean, sampleDeviation, skewness, kurtosis);
    }

    private static double sharpeStandardError(double sharpe, Moments moments, int observations) throws IOException {
        double variance = (1.0 - moments.skewness() * sharpe
                + ((moments.kurtosis() - 1.0) / 4.0) * sharpe * sharpe) / (observations - 1.0);
        if (!(variance > 0.0) || !Double.isFinite(variance)) {
            throw new IOException("Deflated Sharpe variance estimate is invalid");
        }
        return Math.sqrt(variance);
    }

    private static double expectedMaxStandardNormal(long trialCount) {
        if (trialCount <= 1) return 0.0;
        double firstQuantile = inverseNormal(1.0 - 1.0 / trialCount);
        double secondQuantile = inverseNormal(1.0 - 1.0 / (trialCount * Math.E));
        double eulerMascheroni = 0.5772156649015329;
        return (1.0 - eulerMascheroni) * firstQuantile + eulerMascheroni * secondQuantile;
    }

    /** Acklam's rational approximation for the standard normal inverse CDF. */
    private static double inverseNormal(double probability) {
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
                3.754408661907416e+00};
        double low = 0.02425;
        double high = 1.0 - low;
        if (probability < low) {
            double q = Math.sqrt(-2.0 * Math.log(probability));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
        }
        if (probability > high) {
            double q = Math.sqrt(-2.0 * Math.log(1.0 - probability));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
        }
        double q = probability - 0.5;
        double r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0);
    }

    /** Numerical normal CDF approximation with absolute error below 1e-7 for ordinary finite inputs. */
    private static double normalCdf(double value) {
        if (value == Double.POSITIVE_INFINITY) return 1.0;
        if (value == Double.NEGATIVE_INFINITY) return 0.0;
        double x = Math.abs(value) / Math.sqrt(2.0);
        double t = 1.0 / (1.0 + 0.5 * x);
        double tau = t * Math.exp(-x * x - 1.26551223 + t * (1.00002368 + t * (0.37409196
                + t * (0.09678418 + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398
                + t * (1.48851587 + t * (-0.82215223 + t * 0.17087277)))))))));
        double erf = value >= 0.0 ? 1.0 - tau : tau - 1.0;
        return 0.5 * (1.0 + erf);
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

    /** Small strict JSON reader: rejects duplicate object keys, malformed escapes, and trailing content. */
    private static final class JsonParser {
        private final String source;
        private int position;

        private JsonParser(String source) { this.source = source; }

        static Object parse(String source) throws IOException {
            JsonParser parser = new JsonParser(source);
            Object value = parser.value(0);
            parser.whitespace();
            if (parser.position != source.length()) throw parser.error("Trailing JSON content");
            return value;
        }

        private Object value(int depth) throws IOException {
            if (depth > 64) throw error("JSON nesting exceeds 64 levels");
            whitespace();
            if (position >= source.length()) throw error("Unexpected end of JSON");
            return switch (source.charAt(position)) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) throws IOException {
            position++;
            whitespace();
            Map<String, Object> result = new TreeMap<>();
            if (take('}')) return result;
            while (true) {
                whitespace();
                if (position >= source.length() || source.charAt(position) != '"') throw error("Object key must be a string");
                String key = string();
                whitespace();
                require(':');
                Object value = value(depth);
                if (result.containsKey(key)) throw error("Duplicate JSON object key: " + key);
                result.put(key, value);
                whitespace();
                if (take('}')) return result;
                require(',');
            }
        }

        private List<Object> array(int depth) throws IOException {
            position++;
            whitespace();
            List<Object> result = new ArrayList<>();
            if (take(']')) return result;
            while (true) {
                result.add(value(depth));
                whitespace();
                if (take(']')) return result;
                require(',');
            }
        }

        private String string() throws IOException {
            require('"');
            StringBuilder result = new StringBuilder();
            while (position < source.length()) {
                char character = source.charAt(position++);
                if (character == '"') return result.toString();
                if (character < 0x20) throw error("Unescaped control character in string");
                if (character != '\\') {
                    result.append(character);
                    continue;
                }
                if (position >= source.length()) throw error("Incomplete JSON escape");
                char escaped = source.charAt(position++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(unicodeEscape());
                    default -> throw error("Invalid JSON escape");
                }
            }
            throw error("Unterminated JSON string");
        }

        private char unicodeEscape() throws IOException {
            if (position + 4 > source.length()) throw error("Incomplete Unicode escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(source.charAt(position++), 16);
                if (digit < 0) throw error("Invalid Unicode escape");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Object number() throws IOException {
            int start = position;
            if (take('-') && position >= source.length()) throw error("Incomplete JSON number");
            if (take('0')) {
                if (position < source.length() && Character.isDigit(source.charAt(position))) throw error("Leading zero in JSON number");
            } else {
                digits(true);
            }
            if (take('.')) digits(true);
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                digits(true);
            }
            try { return new BigDecimal(source.substring(start, position)); }
            catch (NumberFormatException invalid) { throw error("Invalid JSON number"); }
        }

        private void digits(boolean required) throws IOException {
            int start = position;
            while (position < source.length() && source.charAt(position) >= '0' && source.charAt(position) <= '9') position++;
            if (required && position == start) throw error("Expected JSON digits");
        }

        private Object literal(String literal, Object value) throws IOException {
            if (!source.startsWith(literal, position)) throw error("Invalid JSON literal");
            position += literal.length();
            return value;
        }

        private void whitespace() {
            while (position < source.length()) {
                char character = source.charAt(position);
                if (character == ' ' || character == '\t' || character == '\n' || character == '\r') position++;
                else break;
            }
        }

        private boolean take(char expected) {
            if (position < source.length() && source.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void require(char expected) throws IOException {
            if (!take(expected)) throw error("Expected '" + expected + "'");
        }

        private IOException error(String message) {
            return new IOException(message + " at JSON offset " + position);
        }
    }
}
