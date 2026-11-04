package com.mscient.intrapaper.cli;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.evaluation.EvaluationSplitPlan;
import com.mscient.intrapaper.evaluation.OfflineEvaluationAnalyzer;
import com.mscient.intrapaper.evaluation.ResearchTrialBinding;
import com.mscient.intrapaper.evaluation.ResearchTrialLedger;
import com.mscient.intrapaper.evaluation.ResearchOutcomeLedger;
import com.mscient.intrapaper.marketdata.QuoteJournalReader;
import com.mscient.intrapaper.marketdata.QuoteJournalQualityAnalyzer;
import com.mscient.intrapaper.marketdata.QuoteFeatureCsvExporter;
import com.mscient.intrapaper.marketdata.RawCaptureJournalReader;
import com.mscient.intrapaper.marketdata.RawCaptureClockAnalyzer;
import com.mscient.intrapaper.marketdata.QuoteQualityFlag;
import com.mscient.intrapaper.marketdata.QuoteQualityPolicy;
import com.mscient.intrapaper.marketdata.FeedLifecycleAnalyzer;
import com.mscient.intrapaper.marketdata.FeedLifecycleEvent;
import com.mscient.intrapaper.marketdata.FeedCaptureReconciler;
import com.mscient.intrapaper.marketdata.FeedCaptureReconciliationReport;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.pricing.OptionPricingDiagnostics;
import com.mscient.intrapaper.simulation.BacktestEngine;
import com.mscient.intrapaper.simulation.BacktestResult;
import com.mscient.intrapaper.simulation.BacktestCheckpointStore;
import com.mscient.intrapaper.simulation.OmsRunRecovery;
import com.mscient.intrapaper.simulation.QuoteOrderReplayArtifactVerifier;
import com.mscient.intrapaper.simulation.QuoteOrderReplayCsvRunner;
import com.mscient.intrapaper.simulation.RunArtifactWriter;
import com.mscient.intrapaper.simulation.RunArtifactVerifier;
import com.mscient.intrapaper.simulation.RunIdentity;
import com.mscient.intrapaper.simulation.RunReplayVerifier;
import com.mscient.intrapaper.simulation.PortfolioStressCsv;
import com.mscient.intrapaper.simulation.SmaCrossoverStrategy;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.time.Duration;

/** Offline-only command line entry point. There is deliberately no broker or network adapter. */
public final class IntraPaperCli {
    private IntraPaperCli() { }

    public static void main(String[] args) {
        try {
            if (args.length == 1 && (args[0].equals("--help") || args[0].equals("help"))) {
                usage();
                return;
            }
            if (args.length == 2 && args[0].equals("verify")) {
                RunArtifactVerifier.Verification verification = RunArtifactVerifier.verify(Path.of(args[1]));
                System.out.printf("VERIFIED: %s; output files=%d; SHA-256 hashes match%n",
                        verification.runId(), verification.verifiedFiles());
                return;
            }
            if (args.length == 2 && args[0].equals("oms-recover")) {
                var recovered = OmsRunRecovery.restore(Path.of(args[1]));
                for (var entry : recovered.orderStates().entrySet()) {
                    System.out.printf("ORDER %s: state=%s; reserved_units=%d%n", entry.getKey(), entry.getValue(),
                            recovered.reservedUnitsByOrder().getOrDefault(entry.getKey(), 0));
                }
                System.out.printf("RECONSTRUCTED VERIFIED OMS: run=%s; orders=%d; unique_executions=%d; "
                                + "duplicate_execution_events_ignored=%d; reserved_orders=%d; reserved_units=%d; "
                                + "final_position_units=%d%n",
                        recovered.runId(), recovered.orderStates().size(), recovered.uniqueExecutions().size(),
                        recovered.duplicateExecutionEventsIgnored(), recovered.reservedUnitsByOrder().size(),
                        recovered.totalReservedUnits(), recovered.finalPositionUnits());
                return;
            }
            if (args.length == 2 && args[0].equals("journal-verify")) {
                var summary = QuoteJournalReader.replay(Path.of(args[1]), quote -> { });
                System.out.printf("VERIFIED JOURNAL: events=%d; last_sequence=%d; sequence_gaps=%d; missing_sequence_numbers=%d; bytes=%d; final_hash=%s%n",
                        summary.eventCount(), summary.lastSequenceNumber(), summary.sequenceGapCount(),
                        summary.missingSequenceNumbers(), summary.bytesRead(), summary.finalHashHex());
                return;
            }
            if (args.length == 4 && args[0].equals("quote-quality-report")) {
                var policy = new QuoteQualityPolicy(Duration.ofMillis(Long.parseLong(args[2])),
                        Duration.ofMillis(Long.parseLong(args[3])));
                var report = QuoteJournalQualityAnalyzer.analyze(Path.of(args[1]), policy);
                var journal = report.journal();
                System.out.printf("VERIFIED QUOTE QUALITY: events=%d; clean=%d; flagged=%d; exchange_timestamps=%d; "
                                + "max_observed_age=%s; max_observed_future_skew=%s; max_age_allowed=%s; "
                                + "max_future_skew_allowed=%s%n",
                        journal.eventCount(), report.cleanQuoteCount(), report.flaggedQuoteCount(),
                        report.exchangeTimestampCount(), report.maximumObservedAge(),
                        report.maximumObservedFutureSkew(), report.maximumAgeAllowed(),
                        report.maximumFutureSkewAllowed());
                for (QuoteQualityFlag flag : QuoteQualityFlag.values()) {
                    System.out.printf("FLAG %s: %d%n", flag, report.flagCounts().get(flag));
                }
                System.out.printf("JOURNAL: last_sequence=%d; sequence_gaps=%d; missing_sequence_numbers=%d; "
                                + "bytes=%d; final_hash=%s%n",
                        journal.lastSequenceNumber(), journal.sequenceGapCount(), journal.missingSequenceNumbers(),
                        journal.bytesRead(), journal.finalHashHex());
                return;
            }
            if (args.length == 6 && args[0].equals("quote-feature-export")) {
                var policy = new QuoteQualityPolicy(Duration.ofMillis(Long.parseLong(args[3])),
                        Duration.ofMillis(Long.parseLong(args[4])));
                var exported = QuoteFeatureCsvExporter.export(
                        Path.of(args[1]), args[2], policy, Path.of(args[5]));
                System.out.printf("EXPORTED VERIFIED QUOTE FEATURES: instrument=%s; max_age=%s; max_future_skew=%s; "
                                + "rows=%d; sequence_gaps=%d; missing_sequence_numbers=%d; final_hash=%s; output=%s%n",
                        args[2], policy.maximumAge(), policy.maximumFutureSkew(),
                        exported.featureRows(), exported.journal().sequenceGapCount(),
                        exported.journal().missingSequenceNumbers(), exported.journal().finalHashHex(),
                        exported.outputPath());
                return;
            }
            if (args.length == 6 && args[0].equals("quote-order-replay")) {
                var policy = new QuoteQualityPolicy(Duration.ofMillis(Long.parseLong(args[3])),
                        Duration.ofMillis(Long.parseLong(args[4])));
                var replay = QuoteOrderReplayCsvRunner.replay(
                        Path.of(args[1]), Path.of(args[2]), policy, Path.of(args[5]));
                System.out.printf("COMPLETED OFFLINE QUOTE ORDER REPLAY: run=%s; model=%s; journal_events=%d; "
                                + "intents=%d; filled=%d; partial=%d; missed=%d; quote_journal_final_hash=%s; "
                                + "intent_csv_sha256=%s; output=%s%n",
                        replay.runId(),
                        com.mscient.intrapaper.simulation.QuoteOrderReplay.BATCH_MODEL_ID,
                        replay.journal().eventCount(), replay.intentCount(), replay.filledCount(),
                        replay.partialCount(), replay.missedCount(), replay.journal().finalHashHex(),
                        replay.intentFileSha256(), replay.outputPath());
                return;
            }
            if (args.length == 2 && args[0].equals("quote-order-replay-verify")) {
                var verification = QuoteOrderReplayArtifactVerifier.verify(Path.of(args[1]));
                System.out.printf("VERIFIED QUOTE REPLAY ARTIFACT: run=%s; intents=%d; filled=%d; partial=%d; missed=%d; build=%s%n",
                        verification.runId(), verification.intentCount(), verification.filledCount(),
                        verification.partialCount(), verification.missedCount(), verification.buildCommit());
                return;
            }
            if (args.length == 2 && args[0].equals("raw-capture-verify")) {
                var summary = RawCaptureJournalReader.replay(Path.of(args[1]), message -> { });
                System.out.printf("VERIFIED RAW CAPTURE: events=%d; sequenced_events=%d; sequence_streams=%d; "
                                + "sequence_gaps=%d; missing_sequence_numbers=%d; non_increasing_transitions=%d; "
                                + "bytes=%d; final_hash=%s%n",
                        summary.eventCount(), summary.upstreamSequencedEventCount(), summary.upstreamSequenceStreamCount(),
                        summary.upstreamSequenceGapCount(), summary.missingUpstreamSequenceNumbers(),
                        summary.nonIncreasingUpstreamSequenceTransitions(), summary.bytesRead(), summary.finalHashHex());
                return;
            }
            if (args.length == 3 && args[0].equals("raw-capture-clock-report")) {
                var report = RawCaptureClockAnalyzer.analyze(Path.of(args[1]),
                        Duration.ofMillis(Long.parseLong(args[2])));
                System.out.printf("RAW CAPTURE CLOCK REPORT (DIAGNOSTIC ONLY; NOT UTC ACCURACY): messages=%d; "
                                + "connection_streams=%d; intervals_compared=%d; "
                                + "non_increasing_monotonic=%d; wall_clock_regressions=%d; "
                                + "intervals_over_limit=%d; arithmetic_overflow_intervals=%d; "
                                + "maximum_allowed_divergence=%s; maximum_absolute_divergence=%s; "
                                + "raw_final_hash=%s%n",
                        report.rawCapture().eventCount(), report.connectionStreams(), report.intervalsCompared(),
                        report.nonIncreasingMonotonicIntervals(), report.wallClockRegressions(),
                        report.intervalsOverDivergenceLimit(), report.arithmeticOverflowIntervals(),
                        report.maximumAllowedDivergence(), report.maximumAbsoluteDivergence(),
                        report.rawCapture().finalHashHex());
                return;
            }
            if (args.length == 2 && args[0].equals("feed-lifecycle-report")) {
                var analysis = FeedLifecycleAnalyzer.analyze(Path.of(args[1]));
                var journal = analysis.journal();
                System.out.printf("VERIFIED FEED LIFECYCLE: events=%d; connections=%d; transition_anomalies=%d; "
                                + "active_subscriptions=%d; unresolved_subscriptions=%d; bytes=%d; final_hash=%s%n",
                        journal.eventCount(), journal.connectionCount(), analysis.transitionAnomalyCount(),
                        analysis.activeSubscriptionCount(), analysis.unresolvedSubscriptionCount(),
                        journal.bytesRead(), journal.finalHashHex());
                for (FeedLifecycleEvent.Type type : FeedLifecycleEvent.Type.values()) {
                    System.out.printf("EVENT %s: %d%n", type, journal.eventCounts().getOrDefault(type, 0L));
                }
                for (FeedLifecycleAnalyzer.ConnectionState state : FeedLifecycleAnalyzer.ConnectionState.values()) {
                    System.out.printf("CONNECTION_STATE %s: %d%n", state,
                            analysis.connectionStateCounts().getOrDefault(state, 0L));
                }
                return;
            }
            if (args.length == 4 && args[0].equals("feed-capture-reconcile")) {
                var report = FeedCaptureReconciler.reconcile(Path.of(args[1]), Path.of(args[2]),
                        Duration.ofMillis(Long.parseLong(args[3])));
                System.out.printf("FEED/CAPTURE RECONCILIATION (DIAGNOSTIC ONLY): raw_messages=%d; "
                                + "acknowledged_intervals=%d; intervals_with_messages=%d; empty_intervals=%d; "
                                + "closed_by_unsubscribe=%d; closed_by_connection_loss=%d; "
                                + "closed_by_timeline_anomaly=%d; still_open_intervals=%d; "
                                + "allowed_wall_clock_skew=%s; lifecycle_transition_anomalies=%d; "
                                + "lifecycle_timestamp_regressions=%d; lifecycle_timeline_anomalies=%d; "
                                + "raw_final_hash=%s; lifecycle_final_hash=%s%n",
                        report.rawCapture().eventCount(), report.acknowledgedIntervalCount(),
                        report.intervalsWithCapturedMessages(),
                        report.acknowledgedIntervalCount() - report.intervalsWithCapturedMessages(),
                        report.closedByUnsubscriptionCount(), report.closedByConnectionLossCount(),
                        report.closedByTimelineAnomalyCount(), report.stillOpenIntervalCount(),
                        report.allowedWallClockSkew(), report.lifecycle().transitionAnomalyCount(),
                        report.lifecycleTimestampRegressionCount(), report.lifecycleTimelineAnomalyCount(),
                        report.rawCapture().finalHashHex(), report.lifecycle().journal().finalHashHex());
                for (FeedCaptureReconciliationReport.MessageDisposition disposition
                        : FeedCaptureReconciliationReport.MessageDisposition.values()) {
                    System.out.printf("MESSAGE %s: %d%n", disposition, report.messageCounts().get(disposition));
                }
                return;
            }
            if (args.length == 4 && args[0].equals("replay-verify")) {
                var verification = RunReplayVerifier.verify(
                        Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
                System.out.printf("VERIFIED REPLAY: %s; artifacts=%d; byte-for-byte output match%n",
                        verification.runId(), verification.comparedFiles());
                return;
            }
            if (args.length == 4 && args[0].equals("split-validate")) {
                Path barsPath = Path.of(args[1]).toAbsolutePath().normalize();
                RunConfig config = RunConfig.load(Path.of(args[2]));
                ValidatedDataset dataset = ValidatedDataset.load(barsPath, config);
                EvaluationSplitPlan split = EvaluationSplitPlan.load(Path.of(args[3]), dataset);
                for (EvaluationSplitPlan.Partition partition : EvaluationSplitPlan.Partition.values()) {
                    var summary = split.summary(partition);
                    System.out.printf("%s: sessions=%d; start=%s; end=%s%n", partition,
                            summary.sessionCount(), summary.firstSession(), summary.lastSession());
                }
                System.out.printf("VALID SPLIT PLAN: sha256=%s; metrics not evaluated%n", split.splitFileSha256());
                return;
            }
            if (args.length == 3 && args[0].equals("trial-ledger-register")) {
                Path barsPath = Path.of(args[1]).toAbsolutePath().normalize();
                RunConfig config = RunConfig.load(Path.of(args[2]));
                ValidatedDataset dataset = ValidatedDataset.load(barsPath, config);
                SmaCrossoverStrategy strategy = new SmaCrossoverStrategy(config);
                var trial = ResearchTrialBinding.expectedTrial(config, dataset, strategy);
                var entry = ResearchTrialLedger.register(config.researchTrialLedgerPath().orElseThrow(), trial);
                System.out.printf("REGISTERED RESEARCH TRIAL: sequence=%d; trial_id=%s; strategy=%s@%s; variant=%s; "
                                + "timestamp=%s; dataset_sha256=%s; split_sha256=%s; parameter_config_sha256=%s; chain_hash=%s%n",
                        entry.sequence(), trial.trialId(), trial.strategyId(), trial.strategyVersion(),
                        trial.variantId(), entry.registeredAtUtc(), trial.datasetSha256(),
                        trial.splitPlanSha256(), trial.parameterConfigSha256(), entry.recordHashSha256());
                return;
            }
            if (args.length == 2 && args[0].equals("trial-ledger-verify")) {
                var summary = ResearchTrialLedger.verify(Path.of(args[1]));
                System.out.printf("VERIFIED RESEARCH TRIAL LEDGER: entries=%d; strategy_versions=%d; final_hash=%s%n",
                        summary.entryCount(), summary.distinctStrategyVersions(), summary.finalHashSha256());
                return;
            }
            if (args.length == 9 && args[0].equals("trial-outcome-record")) {
                var event = ResearchOutcomeLedger.append(Path.of(args[1]), Path.of(args[2]), args[3], args[4],
                        args[5], ResearchOutcomeLedger.EventType.valueOf(args[6]), args[7], args[8]);
                System.out.printf("RECORDED TRIAL OUTCOME EVENT: sequence=%d; event_id=%s; trial_id=%s; attempt_id=%s; "
                                + "event_type=%s; timestamp=%s; registration_hash=%s; chain_hash=%s%n",
                        event.sequence(), event.eventId(), event.trialId(), event.attemptId(), event.eventType(),
                        event.occurredAtUtc(), event.registrationHashSha256(), event.recordHashSha256());
                return;
            }
            if (args.length == 3 && args[0].equals("trial-outcome-verify")) {
                var summary = ResearchOutcomeLedger.verify(Path.of(args[1]), Path.of(args[2]));
                System.out.printf("VERIFIED TRIAL OUTCOME LEDGER: events=%d; attempts=%d; outcomes_produced=%d; "
                                + "outcome_views=%d; mechanical_failures_without_outcome=%d; abandoned=%d; final_hash=%s%n",
                        summary.eventCount(), summary.attemptsStarted(), summary.outcomesProduced(),
                        summary.outcomeViews(), summary.mechanicalFailuresWithoutOutcome(),
                        summary.abandonedAttempts(), summary.finalHashSha256());
                for (var trial : summary.trials()) {
                    System.out.printf("TRIAL OUTCOME STATE: trial_id=%s; attempts=%d; outcomes=%d; views=%d; "
                                    + "mechanical_failures=%d; abandoned=%d; exposure=%s%n",
                            trial.trialId(), trial.attemptCount(), trial.outcomesProduced(), trial.outcomesViewed(),
                            trial.mechanicalFailuresWithoutOutcome(), trial.abandonedAttempts(), trial.exposureState());
                }
                return;
            }
            if (args.length == 7 && args[0].equals("statistical-evaluate")) {
                var report = OfflineEvaluationAnalyzer.analyze(Path.of(args[1]), Path.of(args[2]),
                        Path.of(args[3]), Path.of(args[4]), Integer.parseInt(args[6]));
                Path output = Path.of(args[5]).toAbsolutePath().normalize();
                Files.writeString(output, report.toJson(), java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
                System.out.printf("OFFLINE STATISTICAL EVALUATION: trials=%d; evaluation_sessions=%d; "
                                + "blocks=%d; PBO=%.6f; holdout_included=false; performance_claim_eligible=false; output=%s%n",
                        report.trials().size(), report.evaluationSessionCount(),
                        report.pbo().chronologicalBlockCount(), report.pbo().probabilityBacktestOverfitting(), output);
                return;
            }
            if (args.length == 7 && args[0].equals("pricing-diagnostic")) {
                String model = args[1].toUpperCase(java.util.Locale.ROOT);
                var report = switch (model) {
                    case "BLACK76" -> OptionPricingDiagnostics.black76(
                            Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]),
                            Double.parseDouble(args[5]), Double.parseDouble(args[6]));
                    default -> throw new IllegalArgumentException(
                            "pricing-diagnostic BLACK76 requires futures strike years rate volatility; use pricing-diagnostic BSM <spot> <strike> <years> <rate> <dividend-yield> <volatility>");
                };
                printPricingDiagnostic(report);
                if (!report.passed()) System.exit(3);
                return;
            }
            if (args.length == 8 && args[0].equals("pricing-diagnostic")
                    && args[1].equalsIgnoreCase("BSM")) {
                var report = OptionPricingDiagnostics.bsm(
                        Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]),
                        Double.parseDouble(args[5]), Double.parseDouble(args[6]), Double.parseDouble(args[7]));
                printPricingDiagnostic(report);
                if (!report.passed()) System.exit(3);
                return;
            }
            if (args.length == 4 && args[0].equals("portfolio-stress")) {
                var result = PortfolioStressCsv.analyze(Path.of(args[1]),
                        Double.parseDouble(args[2]), Double.parseDouble(args[3]));
                System.out.printf("PORTFOLIO STRESS: legs_file_sha256=%s; worst_scenario=%s; "
                                + "worst_stressed_exit_pnl=%.2f; short_options=%s; scope=%s%n",
                        result.positionsSha256(), result.report().worstScenarioId(),
                        result.report().worstStressedExitPnl(), result.report().hasShortOptions(),
                        result.report().scope());
                var exposure = result.report().baseExposure();
                System.out.printf("BASE EXPOSURE: signed_option_theoretical_value=%.6f; "
                                + "model_delta_equivalent_units=%.6f; gamma_per_underlying_unit=%.9f; "
                                + "vega_per_volatility_point=%.6f; theta_per_calendar_day=%.6f; "
                                + "rho_per_rate_point=%.6f%n",
                        exposure.netOptionTheoreticalValue(), exposure.deltaEquivalentUnits(),
                        exposure.gammaPerUnderlyingUnit(), exposure.vegaPerVolatilityPoint(),
                        exposure.thetaPerCalendarDay(), exposure.rhoPerRatePoint());
                for (var scenario : result.report().scenarios()) {
                    System.out.printf("SCENARIO %s: underlying_shock_pct=%.4f; iv_shock_points=%.4f; "
                                    + "repriced_pnl=%.2f; exit_haircut=%.2f; stressed_exit_pnl=%.2f%n",
                            scenario.id(), scenario.underlyingShockPercent(), scenario.volatilityShockPoints(),
                            scenario.repricedPnl(), scenario.exitHaircut(), scenario.stressedExitPnl());
                }
                return;
            }
            if (args.length != 3 || !(args[0].equals("validate") || args[0].equals("run"))) {
                usage();
                System.exit(2);
            }
            Path barsPath = Path.of(args[1]).toAbsolutePath().normalize();
            RunConfig config = RunConfig.load(Path.of(args[2]));
            ValidatedDataset dataset = ValidatedDataset.load(barsPath, config);
            if (args[0].equals("validate")) {
                System.out.printf("VALID: %d bars; dataset=%s; instrument=%s; rights=%s%n",
                        dataset.bars().size(), config.datasetId(), dataset.bars().getFirst().instrumentId(),
                        config.dataRightsStatus());
                return;
            }
            SmaCrossoverStrategy strategy = new SmaCrossoverStrategy(config);
            String runId = RunIdentity.contentId(dataset, config, strategy);
            String configuredRunsRoot = System.getenv("INTRA_PAPER_RUNS_DIR");
            String localAppData = System.getenv("LOCALAPPDATA");
            Path runsRoot = configuredRunsRoot != null && !configuredRunsRoot.isBlank()
                    ? Path.of(configuredRunsRoot)
                    : Path.of(localAppData == null || localAppData.isBlank() ? System.getProperty("user.home") : localAppData,
                            "Intra-Paper-System", "runs");
            Path publishedRun = runsRoot.toAbsolutePath().normalize().resolve(runId);
            if (Files.exists(publishedRun)) throw new FileAlreadyExistsException(publishedRun.toString());
            BacktestEngine engine = new BacktestEngine();
            RunArtifactWriter writer = new RunArtifactWriter();
            BacktestResult result;
            RunArtifactWriter.WrittenRun written;
            if (config.checkpointIntervalBars() > 0) {
                try (BacktestCheckpointStore checkpoints = new BacktestCheckpointStore(
                        runsRoot.resolve(".checkpoints"), runId)) {
                    if (Files.exists(publishedRun)) throw new FileAlreadyExistsException(publishedRun.toString());
                    result = engine.run(dataset, config, strategy, checkpoints);
                    written = writer.write(runsRoot, runId, dataset, config, result);
                    try {
                        checkpoints.clearAfterPublish();
                    } catch (java.io.IOException cleanupFailure) {
                        System.err.println("Run was published; checkpoint cleanup needs review: " + cleanupFailure.getMessage());
                    }
                }
            } else {
                result = engine.run(dataset, config, strategy);
                written = writer.write(runsRoot, runId, dataset, config, result);
            }
            System.out.printf("RUN: %s%nCLASSIFICATION: %s%nNET P&L (diagnostic only): %s INR%nOUTPUT: %s%n",
                    runId, result.resultClassification(), result.netPnlInr().stripTrailingZeros().toPlainString(), written.path());
        } catch (Exception error) {
            System.err.println("Intra Paper System: " + error.getMessage());
            System.exit(2);
        }
    }

    private static void usage() {
        System.out.println("Intra Paper System (offline only)\n"
                + "  java -jar intra-paper-system.jar validate <bars.csv> <run.properties>\n"
                + "  java -jar intra-paper-system.jar run <bars.csv> <run.properties>\n"
                + "  java -jar intra-paper-system.jar verify <run-folder>\n"
                + "  java -jar intra-paper-system.jar oms-recover <run-folder>\n"
                + "  java -jar intra-paper-system.jar journal-verify <quote-journal>\n"
                + "  java -jar intra-paper-system.jar quote-quality-report <quote-journal> <maximum-age-ms> <maximum-future-skew-ms>\n"
                + "  java -jar intra-paper-system.jar quote-feature-export <quote-journal> <instrument-id> <maximum-age-ms> <maximum-future-skew-ms> <output.csv>\n"
                + "  java -jar intra-paper-system.jar quote-order-replay <quote-journal> <intents.csv> <maximum-age-ms> <maximum-future-skew-ms> <output-root>\n"
                + "  java -jar intra-paper-system.jar quote-order-replay-verify <run-folder>\n"
                + "  java -jar intra-paper-system.jar raw-capture-verify <raw-capture-journal>\n"
                + "  java -jar intra-paper-system.jar raw-capture-clock-report <raw-capture-journal> <maximum-wall-monotonic-divergence-ms>\n"
                + "  java -jar intra-paper-system.jar feed-lifecycle-report <feed-lifecycle-journal>\n"
                + "  java -jar intra-paper-system.jar feed-capture-reconcile <feed-lifecycle-journal> <raw-capture-journal> <allowed-wall-clock-skew-ms>\n"
                + "  java -jar intra-paper-system.jar replay-verify <bars.csv> <run.properties> <run-folder>\n"
                + "  java -jar intra-paper-system.jar split-validate <bars.csv> <run.properties> <splits.csv>\n\n"
                + "  java -jar intra-paper-system.jar trial-ledger-register <bars.csv> <run.properties>\n"
                + "  java -jar intra-paper-system.jar trial-ledger-verify <ledger.tsv>\n"
                + "  java -jar intra-paper-system.jar statistical-evaluate <ledger.tsv> <run-index.csv> <split-plan.csv> <runs-root> <report.json> <even-block-count>\n"
                + "  java -jar intra-paper-system.jar trial-outcome-record <trial-ledger.tsv> <outcome-ledger.tsv> <trial-id> <attempt-id> <event-id> <event-type> <reference> <reason>\n"
                + "  java -jar intra-paper-system.jar trial-outcome-verify <trial-ledger.tsv> <outcome-ledger.tsv>\n"
                + "  java -jar intra-paper-system.jar pricing-diagnostic BLACK76 <future> <strike> <years> <rate> <volatility>\n"
                + "  java -jar intra-paper-system.jar pricing-diagnostic BSM <spot> <strike> <years> <rate> <dividend-yield> <volatility>\n"
                + "  java -jar intra-paper-system.jar portfolio-stress <positions.csv> <underlying-shock-percent> <volatility-shock-points>\n"
                + "Run artifacts default under %LOCALAPPDATA%\\Intra-Paper-System\\runs\\<content-id> or INTRA_PAPER_RUNS_DIR.\n"
                + "verify recomputes output hashes from run-manifest.json.\n"
                + "oms-recover verifies a completed run and reconstructs its simulated order/execution state from saved ledgers.\n"
                + "run resumes a deterministic built-in SMA replay from the last verified checkpoint; set recovery.checkpoint_interval_bars=0 to disable.\n"
                + "journal-verify replays and checks quote framing, canonical records, sequence, and the SHA-256 chain.\n"
                + "quote-quality-report checks journal integrity and counts quote flags using caller-supplied millisecond thresholds.\n"
                + "quote-feature-export verifies a quote journal and atomically writes sequence-aware L1 features without overwriting output.\n"
                + "quote-order-replay verifies a quote journal, replays strict IOC intents offline, and atomically writes a content-addressed run folder.\n"
                + "quote-order-replay-verify checks the saved manifest content ID and outcome CSV hash; source inputs are not bundled.\n"
                + "raw-capture-verify checks exact-payload capture framing and its SHA-256 chain; it makes no network connection.\n"
                + "raw-capture-clock-report compares wall and monotonic elapsed time per connection using a caller threshold; it does not verify UTC offset or feed completeness.\n"
                + "feed-lifecycle-report verifies a separate connection/subscription lifecycle journal and reports state anomalies; it makes no network connection.\n"
                + "feed-capture-reconcile cross-checks raw message receive times against acknowledged subscription windows using a caller-supplied skew tolerance; it is diagnostic and does not certify completeness.\n"
                + "replay-verify rebuilds all outputs from supplied inputs and compares them byte-for-byte.\n"
                + "split-validate checks chronological development/validation/holdout assignments; it does not score strategies.\n"
                + "trial-ledger-register appends a unique variant registration bound to strategy-card, dataset, split, and parameter hashes; it does not run or score a strategy.\n"
                + "trial-ledger-verify checks the append-only trial ledger's sequence and SHA-256 chain; keep an external copy of the final hash because a local chain is not tamper-proof.\n"
                + "trial-outcome-record appends a registered-trial attempt/exposure event; event types: ATTEMPT_STARTED, OUTCOME_PRODUCED, OUTCOME_VIEWED, MECHANICAL_FAILURE_NO_OUTCOME, ABANDONED.\n"
                + "trial-outcome-verify checks outcome-event integrity, registration binding, state transitions, and per-trial exposure summaries; it cannot prove who viewed a file or discover unlogged trials.\n"
                + "statistical-evaluate verifies registered run artifacts and scores only DEVELOPMENT/VALIDATION sessions; HOLDOUT is excluded and every report remains performance_claim_eligible=false.\n"
                + "pricing-diagnostic checks bounds, put-call parity, and finite-difference Greeks; it is not an independent pricing oracle.\n"
                + "portfolio-stress fully reprices a caller-supplied options/futures portfolio in four shock scenarios; it is not account state, exchange margin, or a trading control.\n"
                + "One dated futures contract or one option-premium series per run. There is no broker connectivity or live-order route.");
    }

    private static void printPricingDiagnostic(OptionPricingDiagnostics.Report report) {
        System.out.printf("PRICING DIAGNOSTIC: model=%s; passed=%s; scope=%s%n",
                report.model(), report.passed(), report.scope());
        for (var check : report.checks()) {
            System.out.printf("%s %s: observed=%.12g; expected=%.12g; abs_error=%.6g%n",
                    check.passed() ? "PASS" : "FAIL", check.name(), check.observed(),
                    check.expected(), check.absoluteError());
        }
    }
}
