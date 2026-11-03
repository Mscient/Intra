package com.mscient.intrapaper.cli;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.evaluation.EvaluationSplitPlan;
import com.mscient.intrapaper.marketdata.QuoteJournalReader;
import com.mscient.intrapaper.marketdata.QuoteJournalQualityAnalyzer;
import com.mscient.intrapaper.marketdata.RawCaptureJournalReader;
import com.mscient.intrapaper.marketdata.QuoteQualityFlag;
import com.mscient.intrapaper.marketdata.QuoteQualityPolicy;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.simulation.BacktestEngine;
import com.mscient.intrapaper.simulation.BacktestResult;
import com.mscient.intrapaper.simulation.RunArtifactWriter;
import com.mscient.intrapaper.simulation.RunArtifactVerifier;
import com.mscient.intrapaper.simulation.RunIdentity;
import com.mscient.intrapaper.simulation.RunReplayVerifier;
import com.mscient.intrapaper.simulation.SmaCrossoverStrategy;

import java.nio.file.Path;
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
            BacktestResult result = new BacktestEngine().run(dataset, config, strategy);
            String runId = RunIdentity.contentId(dataset, config, strategy);
            String configuredRunsRoot = System.getenv("INTRA_PAPER_RUNS_DIR");
            String localAppData = System.getenv("LOCALAPPDATA");
            Path runsRoot = configuredRunsRoot != null && !configuredRunsRoot.isBlank()
                    ? Path.of(configuredRunsRoot)
                    : Path.of(localAppData == null || localAppData.isBlank() ? System.getProperty("user.home") : localAppData,
                            "Intra-Paper-System", "runs");
            RunArtifactWriter.WrittenRun written = new RunArtifactWriter().write(runsRoot, runId, dataset, config, result);
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
                + "  java -jar intra-paper-system.jar journal-verify <quote-journal>\n"
                + "  java -jar intra-paper-system.jar quote-quality-report <quote-journal> <maximum-age-ms> <maximum-future-skew-ms>\n"
                + "  java -jar intra-paper-system.jar raw-capture-verify <raw-capture-journal>\n"
                + "  java -jar intra-paper-system.jar replay-verify <bars.csv> <run.properties> <run-folder>\n"
                + "  java -jar intra-paper-system.jar split-validate <bars.csv> <run.properties> <splits.csv>\n\n"
                + "Run artifacts default under %LOCALAPPDATA%\\Intra-Paper-System\\runs\\<content-id> or INTRA_PAPER_RUNS_DIR.\n"
                + "verify recomputes output hashes from run-manifest.json.\n"
                + "journal-verify replays and checks quote framing, canonical records, sequence, and the SHA-256 chain.\n"
                + "quote-quality-report checks journal integrity and counts quote flags using caller-supplied millisecond thresholds.\n"
                + "raw-capture-verify checks exact-payload capture framing and its SHA-256 chain; it makes no network connection.\n"
                + "replay-verify rebuilds all outputs from supplied inputs and compares them byte-for-byte.\n"
                + "split-validate checks chronological development/validation/holdout assignments; it does not score strategies.\n"
                + "One dated futures contract or one option-premium series per run. There is no broker connectivity or live-order route.");
    }
}
