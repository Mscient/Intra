package com.mscient.intrapaper.simulation;


import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verifies the output files recorded by one completed run's manifest. */
public final class RunArtifactVerifier {
    public record Verification(String runId, int verifiedFiles) { }

    private static final Set<String> OUTPUT_FILES = Set.of(
            "signals.csv", "orders.csv", "order-events.csv", "fills.csv",
            "trades.csv", "equity.csv", "sessions.csv", "summary.json");
    private static final Pattern RUN_ID = Pattern.compile(
            "\"run_id\"\\s*:\\s*\"([0-9a-f]{24})\"");
    private static final Pattern OUTPUT_SECTION = Pattern.compile(
            "\"output_file_sha256\"\\s*:\\s*\\{([^}]*)\\}", Pattern.DOTALL);
    private static final Pattern OUTPUT_ENTRY = Pattern.compile(
            "\"([A-Za-z0-9._-]+)\"\\s*:\\s*\"([0-9a-f]{64})\"");

    private RunArtifactVerifier() { }

    public static Verification verify(Path requestedFolder) throws IOException {
        Path runFolder = requestedFolder.toAbsolutePath().normalize();
        if (!Files.isDirectory(runFolder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Run folder does not exist or is not a directory: " + runFolder);
        }
        Path folderName = runFolder.getFileName();
        if (folderName == null) throw new IOException("Run folder must have a content ID name");

        Path manifestPath = runFolder.resolve("run-manifest.json");
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Run manifest is missing or is not a regular file");
        }
        String manifest = Files.readString(manifestPath);
        Matcher runIdMatcher = RUN_ID.matcher(manifest);
        if (!runIdMatcher.find()) throw new IOException("Run manifest has no valid run_id");
        String runId = runIdMatcher.group(1);
        if (!runId.equals(folderName.toString())) {
            throw new IOException("Run manifest ID does not match the run folder name");
        }

        Matcher sectionMatcher = OUTPUT_SECTION.matcher(manifest);
        if (!sectionMatcher.find()) throw new IOException("Run manifest has no output_file_sha256 section");
        Matcher entryMatcher = OUTPUT_ENTRY.matcher(sectionMatcher.group(1));
        Map<String, String> expectedHashes = new TreeMap<>();
        while (entryMatcher.find()) {
            String name = entryMatcher.group(1);
            String hash = entryMatcher.group(2);
            if (!OUTPUT_FILES.contains(name)) throw new IOException("Unexpected file in output hash list: " + name);
            if (expectedHashes.putIfAbsent(name, hash) != null) {
                throw new IOException("Duplicate output hash entry: " + name);
            }
        }
        if (!expectedHashes.keySet().equals(OUTPUT_FILES)) {
            throw new IOException("Run manifest output hash list is incomplete");
        }

        for (String name : OUTPUT_FILES) {
            Path output = runFolder.resolve(name);
            if (!output.getParent().equals(runFolder)
                    || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Run output is missing or is not a regular file: " + name);
            }
            String actual = sha256(output);
            if (!actual.equals(expectedHashes.get(name))) {
                throw new IOException("Output checksum mismatch: " + name);
            }
        }
        return new Verification(runId, OUTPUT_FILES.size());
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    if (count > 0) digest.update(buffer, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
