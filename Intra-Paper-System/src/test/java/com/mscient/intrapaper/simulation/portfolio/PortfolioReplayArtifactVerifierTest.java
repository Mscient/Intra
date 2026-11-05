package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.provenance.RunConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Focused checks that schedule fee artifacts are mandatory and hash verified. */
public final class PortfolioReplayArtifactVerifierTest {
    private static final List<String> OUTPUTS = List.of(
            "datasets.csv", "decisions.csv", "orders.csv", "executions.csv", "execution-fees.csv", "equity.csv");

    private PortfolioReplayArtifactVerifierTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("portfolio-artifact-verifier-");
        try {
            String runId = "a".repeat(64);
            String scheduleHash = "b".repeat(64);
            Path run = Files.createDirectory(root.resolve(runId));
            for (String output : OUTPUTS) Files.writeString(run.resolve(output), "header\n", StandardCharsets.UTF_8);
            Files.writeString(run.resolve("execution-fees.csv"),
                    "execution_id,cost_model,schedule_id,schedule_entry_id,schedule_source_file_sha256,source_reference,execution_date,contract_kind,traded_value_inr,legacy_fill_model_fee_inr,brokerage_inr,exchange_transaction_inr,ipft_contribution_inr,sebi_turnover_inr,stt_inr,stamp_duty_inr,gst_taxable_base_inr,gst_inr,total_fee_inr\n"
                    + "\"PX-1\",\"DATED_SCHEDULE\",\"schedule-1\",\"entry-1\",\"" + scheduleHash
                    + "\",\"source-ref\",\"2026-11-03\",\"FUTURE\",\"2500\",\"0\",\"1\",\"0.03\",\"0.01\",\"0.01\",\"2.5\",\"0\",\"1.05\",\"0.19\",\"3.74\"\n",
                    StandardCharsets.UTF_8);
            TreeMap<String, String> hashes = new TreeMap<>();
            for (String output : OUTPUTS) hashes.put(output, RunConfig.sha256(Files.readAllBytes(run.resolve(output))));
            Files.writeString(run.resolve("run-manifest.properties"), manifest(runId, scheduleHash, hashes),
                    StandardCharsets.UTF_8);
            var verified = PortfolioReplayArtifactVerifier.verify(run);
            require(verified.outputSha256().size() == OUTPUTS.size(), "all outputs must be verified");
            Files.writeString(run.resolve("execution-fees.csv"), "tamper\n", StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            expectFailure(() -> PortfolioReplayArtifactVerifier.verify(run));
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("PortfolioReplayArtifactVerifier checks passed");
    }

    private static String manifest(String runId, String scheduleHash, Map<String, String> hashes) {
        StringBuilder out = new StringBuilder();
        put(out, "schema_version", "2");
        put(out, "run_id", runId);
        put(out, "validated_datasets_sha256", "c".repeat(64));
        put(out, "frame_inputs_sha256", "d".repeat(64));
        put(out, "strategy_id_base64", b64("strategy"));
        put(out, "strategy_fingerprint_sha256", "e".repeat(64));
        put(out, "fill_model_id_base64", b64("fill"));
        put(out, "fill_model_fingerprint_sha256", "f".repeat(64));
        put(out, "execution_cost_schedule_enabled", "true");
        put(out, "execution_cost_schedule_id_base64", b64("schedule-1"));
        put(out, "execution_cost_schedule_source_file_sha256", scheduleHash);
        put(out, "build_commit", "fixture");
        put(out, "classification", "REPLAY_DIAGNOSTIC_ONLY");
        put(out, "performance_claim_eligible", "false");
        put(out, "decision_count", "1");
        put(out, "order_count", "1");
        put(out, "execution_count", "1");
        put(out, "execution_fee_breakdown_count", "1");
        put(out, "snapshot_count", "2");
        put(out, "position_boundary_policy", "ALLOW_OVERNIGHT");
        put(out, "pending_intent_boundary_policy", "CANCEL_AT_SESSION_BOUNDARY");
        put(out, "require_contiguous_frames_within_session", "true");
        put(out, "max_abs_lots_by_instrument_base64", b64("limits"));
        put(out, "strategy_parameters_base64", b64("strategy-parameters"));
        put(out, "fill_model_parameters_base64", b64("fill-parameters"));
        put(out, "output_files", String.join(",", OUTPUTS));
        for (String output : OUTPUTS) put(out, "sha256." + output, hashes.get(output));
        return out.toString();
    }

    private static void expectFailure(Throwing action) throws Exception {
        try { action.run(); } catch (java.io.IOException expected) { return; }
        throw new AssertionError("Expected artifact verification failure");
    }

    private static void put(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value).append('\n');
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
