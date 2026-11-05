package com.mscient.intrapaper.evaluation.portfolio;

import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.simulation.portfolio.PortfolioExecutionCostSchedule;
import com.mscient.intrapaper.simulation.portfolio.PortfolioFillModel;
import com.mscient.intrapaper.simulation.portfolio.PortfolioReplayArtifactVerifier;
import com.mscient.intrapaper.simulation.portfolio.PortfolioReplayEngine;
import com.mscient.intrapaper.simulation.portfolio.PortfolioStrategy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Registration parameter identity for v3 portfolio artifacts with bar-only evaluation boundaries. */
public final class PortfolioTrialIdentity {
    private static final List<String> PARAMETER_KEYS = List.of(
            "strategy_id_base64", "strategy_fingerprint_sha256", "strategy_parameters_base64",
            "fill_model_id_base64", "fill_model_fingerprint_sha256", "fill_model_parameters_base64",
            "execution_cost_schedule_enabled", "execution_cost_schedule_id_base64",
            "execution_cost_schedule_source_file_sha256", "max_abs_lots_by_instrument_base64",
            "pending_intent_boundary_policy", "position_boundary_policy",
            "require_contiguous_frames_within_session");

    public record FrozenRun(PortfolioReplayArtifactVerifier.Verification verification,
            String manifestSha256, String strategyId, String parameterConfigSha256,
            Map<String, String> manifest) {
        public FrozenRun { manifest = Map.copyOf(manifest); }
    }

    private PortfolioTrialIdentity() { }

    /** Can be called before replay to register the exact strategy, fill, cost, and boundary inputs. */
    public static String parameterConfigSha256(PortfolioReplayEngine.Config config,
            PortfolioStrategy strategy, PortfolioFillModel fillModel,
            Optional<PortfolioExecutionCostSchedule> costSchedule) {
        if (config.settlementPolicy() != com.mscient.intrapaper.simulation.portfolio.PortfolioSettlementPolicy.NONE)
            throw new IllegalArgumentException("Portfolio trial evaluation does not yet support settlement checkpoints");
        TreeMap<String, String> values = new TreeMap<>();
        values.put("strategy_id_base64", b64(strategy.id()));
        values.put("strategy_fingerprint_sha256", identityHash(
                "INTRA_PORTFOLIO_STRATEGY_V1", strategy.id(), strategy.parameters()));
        values.put("strategy_parameters_base64", b64(canonicalMap(strategy.parameters())));
        values.put("fill_model_id_base64", b64(fillModel.id()));
        values.put("fill_model_fingerprint_sha256", identityHash(
                "INTRA_PORTFOLIO_FILL_MODEL_V1", fillModel.id(), fillModel.parameters()));
        values.put("fill_model_parameters_base64", b64(canonicalMap(fillModel.parameters())));
        values.put("execution_cost_schedule_enabled", Boolean.toString(costSchedule.isPresent()));
        values.put("execution_cost_schedule_id_base64", b64(costSchedule.map(
                PortfolioExecutionCostSchedule::scheduleId).orElse("")));
        values.put("execution_cost_schedule_source_file_sha256", costSchedule.map(
                PortfolioExecutionCostSchedule::sourceFileSha256).orElse("NONE"));
        values.put("max_abs_lots_by_instrument_base64", b64(canonicalMap(config.maxAbsLotsByInstrument())));
        values.put("pending_intent_boundary_policy", config.pendingIntentBoundaryPolicy().name());
        values.put("position_boundary_policy", config.positionBoundaryPolicy().name());
        values.put("require_contiguous_frames_within_session",
                Boolean.toString(config.requireContiguousFramesWithinSession()));
        return parameterHash(values);
    }

    public static FrozenRun inspect(Path runFolder) throws IOException {
        // Holdout equity payloads must not be interpreted before the split-aware reader excludes them.
        var verification = PortfolioReplayArtifactVerifier.verifyIntegrity(runFolder);
        Path path = runFolder.resolve("run-manifest.properties");
        byte[] bytes = readRegular(path);
        TreeMap<String, String> values = new TreeMap<>();
        for (String line : utf8(bytes).split("\n")) {
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            int separator = line.indexOf('=');
            if (separator < 1 || values.putIfAbsent(line.substring(0, separator),
                    line.substring(separator + 1)) != null) {
                throw new IOException("Malformed portfolio manifest");
            }
        }
        String strategyId = decode(values.get("strategy_id_base64"));
        if (!"NONE".equals(values.get("settlement_policy")))
            throw new IOException("Portfolio trial evaluation does not yet support settlement checkpoints");
        if (!values.get("settlement_instruments").isEmpty()
                || !"NONE".equals(values.get("settlement_events_source_file_sha256"))
                || !decode(values.get("settlement_cost_schedule_id_base64")).isEmpty()
                || !"NONE".equals(values.get("settlement_cost_schedule_source_file_sha256"))
                || !"0".equals(values.get("settlement_count")) || !"0".equals(values.get("settlement_cost_line_count")))
            throw new IOException("Disabled settlement metadata is inconsistent");
        Map<String, String> strategyParameters = parseMap(decode(values.get("strategy_parameters_base64")));
        String fillId = decode(values.get("fill_model_id_base64"));
        Map<String, String> fillParameters = parseMap(decode(values.get("fill_model_parameters_base64")));
        requireEqual(values.get("strategy_fingerprint_sha256"),
                identityHash("INTRA_PORTFOLIO_STRATEGY_V1", strategyId, strategyParameters),
                "strategy fingerprint");
        requireEqual(values.get("fill_model_fingerprint_sha256"),
                identityHash("INTRA_PORTFOLIO_FILL_MODEL_V1", fillId, fillParameters), "fill-model fingerprint");
        Map<String, String> limits = parseMap(decode(values.get("max_abs_lots_by_instrument_base64")));
        TreeMap<String, Integer> numericLimits = new TreeMap<>();
        for (var entry : limits.entrySet()) {
            String value = entry.getValue();
            try {
                if (Integer.parseInt(value) <= 0 || !Integer.toString(Integer.parseInt(value)).equals(value)) {
                    throw new NumberFormatException("noncanonical or nonpositive");
                }
                numericLimits.put(entry.getKey(), Integer.parseInt(value));
            } catch (NumberFormatException invalid) {
                throw new IOException("Invalid portfolio lot limit", invalid);
            }
        }
        String scheduleId = decode(values.get("execution_cost_schedule_id_base64"));
        boolean scheduleEnabled = "true".equals(values.get("execution_cost_schedule_enabled"));
        requireEqual(verification.validatedDatasetsSha256(), values.get("validated_datasets_sha256"),
                "verified manifest dataset");
        requireEqual(verification.runId(), values.get("run_id"), "verified manifest run ID");
        List<String> replayValues = List.of(values.get("build_commit"), values.get("validated_datasets_sha256"),
                values.get("frame_inputs_sha256"), values.get("strategy_fingerprint_sha256"),
                values.get("fill_model_fingerprint_sha256"), scheduleEnabled ? scheduleId : "NO_EXECUTION_COST_SCHEDULE",
                scheduleEnabled ? values.get("execution_cost_schedule_source_file_sha256") : "NO_EXECUTION_COST_SCHEDULE",
                numericLimits.toString(), values.get("pending_intent_boundary_policy"),
                values.get("position_boundary_policy"), values.get("require_contiguous_frames_within_session"),
                values.get("settlement_policy"), values.get("settlement_instruments"), values.get("settlement_events_source_file_sha256"),
                decode(values.get("settlement_cost_schedule_id_base64")).isEmpty() ? "NONE" : decode(values.get("settlement_cost_schedule_id_base64")),
                values.get("settlement_cost_schedule_source_file_sha256"));
        String replayHash = hash(output -> {
            write(output, "INTRA_PORTFOLIO_REPLAY_V3");
            output.writeInt(replayValues.size());
            for (String value : replayValues) write(output, value);
        });
        requireEqual(verification.runId(), replayHash, "portfolio replay fingerprint");
        return new FrozenRun(verification, RunConfig.sha256(bytes), strategyId, parameterHash(values), values);
    }

    static Map<String, String> lotLimits(FrozenRun run) throws IOException {
        return parseMap(decode(run.manifest().get("max_abs_lots_by_instrument_base64")));
    }

    static byte[] readRegular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("Input must be a regular file: " + path.getFileName());
        }
        return Files.readAllBytes(path);
    }

    static String fileSha256(Path path) throws IOException { return RunConfig.sha256(readRegular(path)); }

    static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    static void requireEqual(String expected, String actual, String label) throws IOException {
        if (expected == null || !expected.equals(actual)) throw new IOException("Identity mismatch: " + label);
    }

    private static String parameterHash(Map<String, String> values) {
        return hash(output -> {
            write(output, "INTRA_PORTFOLIO_TRIAL_PARAMETERS_V1");
            output.writeInt(PARAMETER_KEYS.size());
            for (String key : PARAMETER_KEYS) {
                String value = values.get(key);
                if (value == null) throw new IllegalArgumentException("Missing parameter identity field: " + key);
                write(output, key);
                write(output, value);
            }
        });
    }

    private static String identityHash(String domain, String id, Map<String, String> parameters) {
        return hash(output -> {
            write(output, domain);
            write(output, id);
            output.writeInt(parameters.size());
            for (var entry : new TreeMap<>(parameters).entrySet()) {
                write(output, entry.getKey());
                write(output, entry.getValue());
            }
        });
    }

    @FunctionalInterface private interface HashWriter { void write(DataOutputStream output) throws IOException; }

    private static String hash(HashWriter writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) { writer.write(output); }
            return RunConfig.sha256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode portfolio identity", impossible);
        }
    }

    private static void write(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    static String decode(String text) throws IOException {
        if (text == null) throw new IOException("Missing portfolio base64 metadata");
        try {
            byte[] bytes = Base64.getDecoder().decode(text);
            if (!Base64.getEncoder().encodeToString(bytes).equals(text)) {
                throw new IOException("Noncanonical portfolio base64 metadata");
            }
            return utf8(bytes);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid portfolio base64 metadata", invalid);
        }
    }

    private static String canonicalMap(Map<?, ?> values) {
        TreeMap<String, String> sorted = new TreeMap<>();
        values.forEach((key, value) -> sorted.put(key.toString(), value.toString()));
        StringBuilder result = new StringBuilder();
        sorted.forEach((key, value) -> result.append(key.length()).append(':').append(key)
                .append(value.length()).append(':').append(value));
        return result.toString();
    }

    static Map<String, String> parseMap(String text) throws IOException {
        TreeMap<String, String> values = new TreeMap<>();
        int[] cursor = {0};
        while (cursor[0] < text.length()) {
            String key = field(text, cursor);
            String value = field(text, cursor);
            if (key.isEmpty() || values.putIfAbsent(key, value) != null) {
                throw new IOException("Empty or duplicate portfolio parameter key");
            }
        }
        if (!canonicalMap(values).equals(text)) throw new IOException("Noncanonical portfolio parameter map");
        return Map.copyOf(values);
    }

    private static String field(String text, int[] cursor) throws IOException {
        int colon = text.indexOf(':', cursor[0]);
        if (colon < 0) throw new IOException("Missing portfolio parameter length");
        String lengthText = text.substring(cursor[0], colon);
        try {
            int length = Integer.parseInt(lengthText);
            if (length < 0 || !Integer.toString(length).equals(lengthText)
                    || length > text.length() - colon - 1) throw new NumberFormatException("invalid length");
            String value = text.substring(colon + 1, colon + 1 + length);
            cursor[0] = colon + 1 + length;
            return value;
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid portfolio parameter length", invalid);
        }
    }
}
