package com.mscient.intrapaper.cli;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.marketdata.QuoteJournalReader;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.simulation.BacktestEngine;
import com.mscient.intrapaper.simulation.BacktestResult;
import com.mscient.intrapaper.simulation.RunArtifactWriter;
import com.mscient.intrapaper.simulation.RunArtifactVerifier;
import com.mscient.intrapaper.simulation.RunReplayVerifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

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
                System.out.printf("VERIFIED JOURNAL: events=%d; last_sequence=%d; bytes=%d; final_hash=%s%n",
                        summary.eventCount(), summary.lastSequenceNumber(), summary.bytesRead(), summary.finalHashHex());
                return;
            }
            if (args.length == 4 && args[0].equals("replay-verify")) {
                var verification = RunReplayVerifier.verify(
                        Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
                System.out.printf("VERIFIED REPLAY: %s; artifacts=%d; byte-for-byte output match%n",
                        verification.runId(), verification.comparedFiles());
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
            BacktestResult result = new BacktestEngine().run(dataset, config);
            String runId = RunConfig.sha256((dataset.barsSha256() + dataset.configSha256()
                    + dataset.instrumentMaster().rawFileSha256() + dataset.calendarSha256() + config.commitId()).getBytes(StandardCharsets.UTF_8))
                    .substring(0, 24);
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
                + "  java -jar intra-paper-system.jar replay-verify <bars.csv> <run.properties> <run-folder>\n\n"
                + "Run artifacts default under %LOCALAPPDATA%\\Intra-Paper-System\\runs\\<content-id> or INTRA_PAPER_RUNS_DIR.\n"
                + "verify recomputes output hashes from run-manifest.json.\n"
                + "journal-verify replays and checks quote framing, canonical records, sequence, and the SHA-256 chain.\n"
                + "replay-verify rebuilds all outputs from supplied inputs and compares them byte-for-byte.\n"
                + "One dated futures contract or one option-premium series per run. There is no broker connectivity or live-order route.");
    }
}
