package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.provenance.RunConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Verifies a published portfolio artifact's fixed layout, fingerprint binding, and output hashes. */
public final class PortfolioReplayArtifactVerifier {
    private static final List<String> OUTPUT_NAMES = List.of(
            "datasets.csv", "decisions.csv", "orders.csv", "executions.csv", "equity.csv");
    private static final List<String> BASE_KEYS = List.of(
            "schema_version", "run_id", "validated_datasets_sha256", "frame_inputs_sha256",
            "strategy_id_base64", "strategy_fingerprint_sha256", "fill_model_id_base64",
            "fill_model_fingerprint_sha256", "build_commit", "classification",
            "performance_claim_eligible", "decision_count", "order_count", "execution_count",
            "snapshot_count", "position_boundary_policy", "pending_intent_boundary_policy",
            "require_contiguous_frames_within_session", "max_abs_lots_by_instrument_base64",
            "strategy_parameters_base64", "fill_model_parameters_base64", "output_files");

    public record Verification(String runId, String validatedDatasetsSha256,
            Map<String, String> outputSha256) {
        public Verification { outputSha256 = Map.copyOf(outputSha256); }
    }

    private PortfolioReplayArtifactVerifier() { }

    public static Verification verify(Path folder) throws IOException {
        Path runFolder = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(runFolder, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(runFolder)) {
            throw new IOException("Portfolio run folder must be a real directory");
        }
        String folderName = runFolder.getFileName().toString();
        if (!folderName.matches("[0-9a-f]{64}")) throw new IOException("Portfolio run folder name is not a replay fingerprint");
        Path manifest = runFolder.resolve("run-manifest.properties");
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(manifest)) {
            throw new IOException("Portfolio run manifest is missing or not a regular file");
        }
        List<String> lines = Files.readAllLines(manifest, java.nio.charset.StandardCharsets.UTF_8);
        if (lines.size() != BASE_KEYS.size() + OUTPUT_NAMES.size()) {
            throw new IOException("Portfolio run manifest has an unexpected number of rows");
        }
        List<String> expectedKeys = new ArrayList<>(BASE_KEYS);
        OUTPUT_NAMES.forEach(name -> expectedKeys.add("sha256." + name));
        TreeMap<String, String> values = new TreeMap<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int separator = line.indexOf('=');
            if (separator < 1) throw new IOException("Malformed portfolio run manifest row");
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!expectedKeys.get(index).equals(key) || values.putIfAbsent(key, value) != null) {
                throw new IOException("Portfolio run manifest keys are duplicated or out of canonical order");
            }
        }
        if (!"1".equals(values.get("schema_version"))) throw new IOException("Unsupported portfolio artifact schema");
        if (!folderName.equals(values.get("run_id"))) throw new IOException("Portfolio run folder and manifest IDs differ");
        requireHash(values.get("validated_datasets_sha256"), "validated datasets hash");
        requireHash(values.get("frame_inputs_sha256"), "frame inputs hash");
        requireHash(values.get("strategy_fingerprint_sha256"), "strategy fingerprint");
        requireHash(values.get("fill_model_fingerprint_sha256"), "fill-model fingerprint");
        if (!"REPLAY_DIAGNOSTIC_ONLY".equals(values.get("classification"))
                || !"false".equals(values.get("performance_claim_eligible"))) {
            throw new IOException("Portfolio artifacts must remain diagnostic-only and performance-ineligible");
        }
        if (!List.of("ALLOW_OVERNIGHT", "REQUIRE_FLAT").contains(values.get("position_boundary_policy"))
                || !List.of("CANCEL_AT_SESSION_BOUNDARY", "ALLOW_NEXT_SESSION_OPEN")
                        .contains(values.get("pending_intent_boundary_policy"))
                || !List.of("true", "false").contains(values.get("require_contiguous_frames_within_session"))) {
            throw new IOException("Invalid portfolio boundary-policy metadata");
        }
        try {
            java.util.Base64.getDecoder().decode(values.get("strategy_id_base64"));
            java.util.Base64.getDecoder().decode(values.get("fill_model_id_base64"));
            java.util.Base64.getDecoder().decode(values.get("max_abs_lots_by_instrument_base64"));
            java.util.Base64.getDecoder().decode(values.get("strategy_parameters_base64"));
            java.util.Base64.getDecoder().decode(values.get("fill_model_parameters_base64"));
            for (String key : List.of("decision_count", "order_count", "execution_count", "snapshot_count")) {
                if (Integer.parseInt(values.get(key)) < 0) throw new NumberFormatException("negative");
            }
        } catch (RuntimeException invalidMetadata) {
            throw new IOException("Invalid portfolio replay metadata", invalidMetadata);
        }
        if (!String.join(",", OUTPUT_NAMES).equals(values.get("output_files"))) {
            throw new IOException("Portfolio output file declaration is not canonical");
        }
        try (var children = Files.list(runFolder)) {
            List<String> actualChildren = children.map(path -> path.getFileName().toString()).sorted().toList();
            List<String> expectedChildren = new ArrayList<>(OUTPUT_NAMES);
            expectedChildren.add("run-manifest.properties");
            expectedChildren.sort(String::compareTo);
            if (!actualChildren.equals(expectedChildren)) throw new IOException("Portfolio run folder has missing or unexpected files");
        }
        TreeMap<String, String> outputHashes = new TreeMap<>();
        for (String name : OUTPUT_NAMES) {
            Path output = runFolder.resolve(name);
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(output)) {
                throw new IOException("Portfolio output is missing or not a regular file: " + name);
            }
            String expectedHash = values.get("sha256." + name);
            requireHash(expectedHash, "output hash for " + name);
            String actualHash = RunConfig.sha256(Files.readAllBytes(output));
            if (!expectedHash.equals(actualHash)) throw new IOException("Portfolio output hash mismatch: " + name);
            outputHashes.put(name, actualHash);
        }
        return new Verification(folderName, values.get("validated_datasets_sha256"), outputHashes);
    }

    private static void requireHash(String value, String label) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IOException("Invalid " + label);
    }
}
