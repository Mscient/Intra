package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.provenance.RunConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Re-runs a saved simulation in an isolated temporary folder and compares every published artifact. */
public final class RunReplayVerifier {
    public record Verification(String runId, int comparedFiles) { }

    private static final List<String> ARTIFACTS = List.of(
            "signals.csv", "orders.csv", "order-events.csv", "fills.csv", "trades.csv",
            "equity.csv", "sessions.csv", "summary.json", "run-manifest.json");

    private RunReplayVerifier() { }

    public static Verification verify(Path barsPath, Path configPath, Path requestedRunFolder) throws IOException {
        RunConfig config = RunConfig.load(configPath);
        return verify(barsPath, config, requestedRunFolder, new SmaCrossoverStrategy(config));
    }

    public static Verification verify(Path barsPath, RunConfig config,
            Path requestedRunFolder, Strategy strategy) throws IOException {
        Path runFolder = requestedRunFolder.toAbsolutePath().normalize();
        RunArtifactVerifier.verify(runFolder);

        Path normalizedBarsPath = barsPath.toAbsolutePath().normalize();
        ValidatedDataset dataset = ValidatedDataset.load(normalizedBarsPath, config);
        String runId = RunIdentity.contentId(dataset, config, strategy);
        Path folderName = runFolder.getFileName();
        if (folderName == null || !folderName.toString().equals(runId)) {
            throw new IOException("Supplied bars/configuration resolve to run ID " + runId
                    + ", which does not match the saved run folder");
        }

        BacktestResult result = new BacktestEngine().run(dataset, config, strategy);
        Path temporaryRoot = Files.createTempDirectory("intra-paper-replay-verify-");
        Verification verification;
        try {
            RunArtifactWriter.WrittenRun replay = new RunArtifactWriter()
                    .write(temporaryRoot, runId, dataset, config, result);
            for (String name : ARTIFACTS) {
                Path saved = runFolder.resolve(name);
                Path reproduced = replay.path().resolve(name);
                if (Files.mismatch(saved, reproduced) != -1L) {
                    throw new IOException("Replay artifact differs from the saved run: " + name);
                }
            }
            verification = new Verification(runId, ARTIFACTS.size());
        } catch (IOException | RuntimeException | Error failure) {
            try {
                deleteTree(temporaryRoot);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        deleteTree(temporaryRoot);
        return verification;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
