package com.mscient.intrapaper.simulation.portfolio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;

/** Replays frozen portfolio inputs and compares regenerated output bytes with a published artifact. */
public final class PortfolioReplayDeterminismVerifier {
    private PortfolioReplayDeterminismVerifier() { }

    public static PortfolioReplayArtifactVerifier.Verification verify(Path inputManifest,
            Path propertiesPath, Path runFolder) throws IOException {
        PortfolioReplayArtifactVerifier.Verification saved = PortfolioReplayArtifactVerifier.verify(runFolder);
        PortfolioReplayCsvRunner.Replay replay = PortfolioReplayCsvRunner.replay(inputManifest, propertiesPath);
        if (!saved.runId().equals(replay.result().replayFingerprintSha256())
                || !saved.validatedDatasetsSha256().equals(replay.dataset().datasetsSha256())) {
            throw new IOException("Replayed portfolio identity differs from the published run; check input files, settlement events/costs/policy, build, strategy, fill, or settings");
        }
        Path tempRoot = Files.createTempDirectory("intra-portfolio-replay-verify-");
        try {
            if (Files.isSymbolicLink(tempRoot) || !Files.isDirectory(tempRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Temporary portfolio verification root is not a real directory");
            }
            PortfolioReplayArtifactWriter.WrittenRun regenerated = new PortfolioReplayArtifactWriter()
                    .write(tempRoot.resolve("runs"), replay.dataset(), replay.result());
            PortfolioReplayArtifactVerifier.Verification rebuilt = PortfolioReplayArtifactVerifier.verify(regenerated.path());
            if (!saved.outputSha256().equals(rebuilt.outputSha256())) {
                throw new IOException("Published portfolio outputs differ from deterministic replay output");
            }
            for (String name : saved.outputSha256().keySet()) {
                if (Files.mismatch(runFolder.resolve(name), regenerated.path().resolve(name)) != -1)
                    throw new IOException("Portfolio deterministic replay bytes differ: " + name);
            }
            return saved;
        } finally {
            if (Files.exists(tempRoot, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(tempRoot) || !Files.isDirectory(tempRoot, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Refusing to clean an unexpected portfolio verification path");
                }
                try (var entries = Files.walk(tempRoot)) {
                    for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                        if (!entry.toAbsolutePath().normalize().startsWith(tempRoot.toAbsolutePath().normalize())) {
                            throw new IOException("Refusing to clean outside the portfolio verification root");
                        }
                        Files.deleteIfExists(entry);
                    }
                }
            }
        }
    }
}
