package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.marketdata.QuoteJournalSummary;
import com.mscient.intrapaper.marketdata.QuoteQualityPolicy;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Checks a saved quote replay artifact's fixed manifest, content ID, and output-file hash. */
public final class QuoteOrderReplayArtifactVerifier {
    public record Verification(String runId, String buildCommit, long intentCount,
            long filledCount, long partialCount, long missedCount) { }

    private record OutcomeCounts(long total, long filled, long partial, long missed) { }

    private static final List<String> KEYS = List.of("schema_version", "run_id", "model_id", "build_commit",
            "quote_journal_sha256", "quote_journal_final_hash", "quote_journal_events", "quote_journal_bytes",
            "intent_csv_sha256", "maximum_age", "maximum_future_skew", "intent_count", "filled_count",
            "partial_count", "missed_count", "outcomes_sha256");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern RUN_ID = Pattern.compile("[0-9a-f]{24}");
    private static final Pattern BUILD = Pattern.compile("(?:[0-9a-f]{40}|unknown|unknown-dirty)");

    private QuoteOrderReplayArtifactVerifier() { }

    public static Verification verify(Path requestedFolder) throws IOException {
        Objects.requireNonNull(requestedFolder, "requestedFolder");
        Path folder = requestedFolder.toAbsolutePath().normalize();
        if (folder.getFileName() == null) throw new IOException("Quote replay artifact path must name a run folder");
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Quote replay artifact folder is missing or is a symbolic link");
        }
        requireExactFiles(folder);
        Path manifestPath = folder.resolve("run-manifest.properties");
        if (Files.size(manifestPath) > 8192) throw new IOException("Quote replay manifest exceeds size limit");
        Map<String, String> values = parseManifest(Files.readAllLines(manifestPath));
        if (!"1".equals(values.get("schema_version"))) throw new IOException("Unsupported quote replay manifest version");
        if (!QuoteOrderReplay.BATCH_MODEL_ID.equals(values.get("model_id"))) {
            throw new IOException("Unexpected quote replay model ID");
        }
        String runId = requireMatch(values, "run_id", RUN_ID);
        if (!runId.equals(folder.getFileName().toString())) throw new IOException("Folder name does not match quote replay run ID");
        String buildCommit = requireMatch(values, "build_commit", BUILD);
        String journalHash = requireMatch(values, "quote_journal_sha256", HASH);
        String journalFinalHash = requireMatch(values, "quote_journal_final_hash", HASH);
        String intentHash = requireMatch(values, "intent_csv_sha256", HASH);
        String outcomesHash = requireMatch(values, "outcomes_sha256", HASH);
        long journalEvents = nonNegative(values, "quote_journal_events");
        long journalBytes = nonNegative(values, "quote_journal_bytes");
        long intentCount = nonNegative(values, "intent_count");
        long filled = nonNegative(values, "filled_count");
        long partial = nonNegative(values, "partial_count");
        long missed = nonNegative(values, "missed_count");
        long classified;
        try {
            classified = Math.addExact(Math.addExact(filled, partial), missed);
        } catch (ArithmeticException overflow) {
            throw new IOException("Quote replay outcome counts overflow", overflow);
        }
        if (classified != intentCount) {
            throw new IOException("Quote replay outcome counts do not reconcile to intent count");
        }
        QuoteQualityPolicy policy;
        try {
            policy = new QuoteQualityPolicy(Duration.parse(values.get("maximum_age")),
                    Duration.parse(values.get("maximum_future_skew")));
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid quote replay quality policy in manifest", invalid);
        }
        QuoteJournalSummary summary = new QuoteJournalSummary(journalEvents, 0, 0, 0,
                journalFinalHash, journalBytes);
        String expectedRunId = QuoteOrderReplayCsvRunner.contentId(journalHash, summary.finalHashHex(),
                intentHash, policy, buildCommit);
        if (!runId.equals(expectedRunId)) throw new IOException("Quote replay content ID does not match manifest inputs");
        Path outcomesPath = folder.resolve("outcomes.csv");
        if (!QuoteOrderReplayCsvRunner.sha256(outcomesPath).equals(outcomesHash)) {
            throw new IOException("Quote replay outcome file hash mismatch");
        }
        OutcomeCounts actual = countOutcomes(outcomesPath);
        if (actual.total() != intentCount || actual.filled() != filled
                || actual.partial() != partial || actual.missed() != missed) {
            throw new IOException("Quote replay manifest counts do not match outcome rows");
        }
        return new Verification(runId, buildCommit, intentCount, filled, partial, missed);
    }

    private static OutcomeCounts countOutcomes(Path path) throws IOException {
        long total = 0;
        long filled = 0;
        long partial = 0;
        long missed = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            if (!QuoteOrderReplayCsvRunner.OUTPUT_HEADER.equals(reader.readLine())) {
                throw new IOException("Quote replay outcomes CSV header mismatch");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> fields;
                try {
                    fields = CsvTable.parseLine(line);
                } catch (IllegalArgumentException malformed) {
                    throw new IOException("Malformed quote replay outcome CSV row", malformed);
                }
                if (fields.size() != 26 || !QuoteOrderReplay.BATCH_MODEL_ID.equals(fields.getFirst())) {
                    throw new IOException("Quote replay outcome CSV row has invalid schema or model ID");
                }
                total = Math.addExact(total, 1);
                switch (fields.get(15)) {
                    case "FILLED" -> filled = Math.addExact(filled, 1);
                    case "PARTIALLY_FILLED" -> partial = Math.addExact(partial, 1);
                    case "MISSED" -> missed = Math.addExact(missed, 1);
                    default -> throw new IOException("Quote replay outcome row has invalid status");
                }
            }
        } catch (ArithmeticException overflow) {
            throw new IOException("Quote replay outcome rows exceed supported count range", overflow);
        }
        return new OutcomeCounts(total, filled, partial, missed);
    }

    private static Map<String, String> parseManifest(List<String> lines) throws IOException {
        if (lines.size() != KEYS.size()) throw new IOException("Quote replay manifest has an unexpected number of rows");
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i < KEYS.size(); i++) {
            String prefix = KEYS.get(i) + "=";
            String line = lines.get(i);
            if (!line.startsWith(prefix) || line.length() == prefix.length()) {
                throw new IOException("Malformed quote replay manifest row for " + KEYS.get(i));
            }
            values.put(KEYS.get(i), line.substring(prefix.length()));
        }
        return Map.copyOf(values);
    }

    private static void requireExactFiles(Path folder) throws IOException {
        List<String> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder)) {
            for (Path entry : stream) entries.add(entry.getFileName().toString());
        }
        if (!entries.stream().sorted().toList().equals(List.of("outcomes.csv", "run-manifest.properties"))) {
            throw new IOException("Quote replay artifact folder contains missing or unexpected files");
        }
        for (String file : List.of("outcomes.csv", "run-manifest.properties")) {
            if (!Files.isRegularFile(folder.resolve(file), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Quote replay artifact contains a missing file or symbolic link: " + file);
            }
        }
    }

    private static String requireMatch(Map<String, String> values, String key, Pattern pattern) throws IOException {
        String value = values.get(key);
        if (value == null || !pattern.matcher(value).matches()) throw new IOException("Invalid manifest value: " + key);
        return value;
    }

    private static long nonNegative(Map<String, String> values, String key) throws IOException {
        try {
            long parsed = Long.parseLong(values.get(key));
            if (parsed < 0) throw new NumberFormatException("negative");
            return parsed;
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid manifest count: " + key, invalid);
        }
    }
}
