package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.provenance.RunConfig;

import java.io.IOException;
import java.math.BigDecimal;
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
            "datasets.csv", "decisions.csv", "orders.csv", "executions.csv", "execution-fees.csv", "equity.csv",
            "settlements.csv", "settlement-cost-lines.csv");
    private static final List<String> BASE_KEYS = List.of(
            "schema_version", "run_id", "validated_datasets_sha256", "frame_inputs_sha256",
            "strategy_id_base64", "strategy_fingerprint_sha256", "fill_model_id_base64",
            "fill_model_fingerprint_sha256", "execution_cost_schedule_enabled",
            "execution_cost_schedule_id_base64", "execution_cost_schedule_source_file_sha256",
            "build_commit", "classification",
            "performance_claim_eligible", "decision_count", "order_count", "execution_count",
            "execution_fee_breakdown_count", "snapshot_count", "position_boundary_policy", "pending_intent_boundary_policy",
            "require_contiguous_frames_within_session", "max_abs_lots_by_instrument_base64",
            "strategy_parameters_base64", "fill_model_parameters_base64", "settlement_policy", "settlement_instruments",
            "settlement_events_source_file_sha256", "settlement_cost_schedule_id_base64", "settlement_cost_schedule_source_file_sha256",
            "settlement_count", "settlement_cost_line_count", "output_files");

    public record Verification(String runId, String validatedDatasetsSha256,
            Map<String, String> outputSha256) {
        public Verification { outputSha256 = Map.copyOf(outputSha256); }
    }

    private PortfolioReplayArtifactVerifier() { }

    public static Verification verify(Path folder) throws IOException {
        return verify(folder, true);
    }

    /** Layout, manifest, output hashes and execution-fee linkage only. Does not read equity P&L.
     * Partitioned evaluation must validate its selected financial rows separately; this is not
     * a substitute for full settlement/accounting verification or deterministic replay. */
    public static Verification verifyIntegrity(Path folder) throws IOException {
        return verify(folder, false);
    }

    private static Verification verify(Path folder, boolean reconcileAccounting) throws IOException {
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
        if (lines.isEmpty() || !lines.getFirst().equals("schema_version=3"))
            throw new IOException("Unsupported portfolio artifact schema; use the original build to verify older artifacts");
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
        if (!"3".equals(values.get("schema_version"))) throw new IOException("Unsupported portfolio artifact schema; use the original build to verify older artifacts");
        if (!folderName.equals(values.get("run_id"))) throw new IOException("Portfolio run folder and manifest IDs differ");
        requireHash(values.get("validated_datasets_sha256"), "validated datasets hash");
        requireHash(values.get("frame_inputs_sha256"), "frame inputs hash");
        requireHash(values.get("strategy_fingerprint_sha256"), "strategy fingerprint");
        requireHash(values.get("fill_model_fingerprint_sha256"), "fill-model fingerprint");
        boolean scheduleEnabled;
        if ("true".equals(values.get("execution_cost_schedule_enabled"))) scheduleEnabled = true;
        else if ("false".equals(values.get("execution_cost_schedule_enabled"))) scheduleEnabled = false;
        else throw new IOException("Invalid portfolio execution cost schedule enabled flag");
        if (scheduleEnabled) requireHash(values.get("execution_cost_schedule_source_file_sha256"),
                "execution cost schedule source-file hash");
        else if (!"NONE".equals(values.get("execution_cost_schedule_source_file_sha256"))) {
            throw new IOException("Disabled portfolio execution cost schedule must use NONE source hash");
        }
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
            String scheduleId = new String(java.util.Base64.getDecoder().decode(
                    values.get("execution_cost_schedule_id_base64")), java.nio.charset.StandardCharsets.UTF_8);
            if (scheduleEnabled != !scheduleId.isEmpty()) throw new IllegalArgumentException("schedule ID presence mismatch");
            java.util.Base64.getDecoder().decode(values.get("max_abs_lots_by_instrument_base64"));
            java.util.Base64.getDecoder().decode(values.get("strategy_parameters_base64"));
            java.util.Base64.getDecoder().decode(values.get("fill_model_parameters_base64"));
            for (String key : List.of("decision_count", "order_count", "execution_count",
                    "execution_fee_breakdown_count", "snapshot_count")) {
                if (Integer.parseInt(values.get(key)) < 0) throw new NumberFormatException("negative");
            }
            if (!values.get("execution_count").equals(values.get("execution_fee_breakdown_count"))) {
                throw new IllegalArgumentException("fee breakdown count mismatch");
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
        Map<String, BigDecimal> executionFees = readExecutionFees(runFolder.resolve("executions.csv"));
        verifyFeeBreakdowns(runFolder.resolve("execution-fees.csv"), values, scheduleEnabled, executionFees);
        if (reconcileAccounting) PortfolioSettlementArtifactVerifier.verify(runFolder, values);
        return new Verification(folderName, values.get("validated_datasets_sha256"), outputHashes);
    }

    private static void verifyFeeBreakdowns(Path path, Map<String, String> manifest,
            boolean scheduleEnabled, Map<String, BigDecimal> executionFees) throws IOException {
        CsvTable table = CsvTable.read(path);
        List<String> expectedHeaders = List.of("execution_id", "cost_model", "schedule_id",
                "schedule_entry_id", "schedule_source_file_sha256", "source_reference",
                "execution_date", "contract_kind", "traded_value_inr", "legacy_fill_model_fee_inr",
                "brokerage_inr", "exchange_transaction_inr", "ipft_contribution_inr",
                "sebi_turnover_inr", "stt_inr", "stamp_duty_inr", "gst_taxable_base_inr",
                "gst_inr", "total_fee_inr");
        if (!table.headers().equals(expectedHeaders)) throw new IOException("Unexpected execution fee breakdown schema");
        if (table.rows().size() != Integer.parseInt(manifest.get("execution_fee_breakdown_count"))) {
            throw new IOException("Execution fee breakdown row count does not match manifest");
        }
        String scheduleId = new String(java.util.Base64.getDecoder().decode(
                manifest.get("execution_cost_schedule_id_base64")), java.nio.charset.StandardCharsets.UTF_8);
        java.util.Set<String> seenExecutionIds = new java.util.HashSet<>();
        for (Map<String, String> row : table.rows()) {
            try {
                String executionId = row.get("execution_id");
                if (executionId == null || executionId.isBlank() || !executionId.equals(executionId.strip())
                        || !seenExecutionIds.add(executionId)) {
                    throw new IllegalArgumentException("execution ID is blank, untrimmed, or duplicated");
                }
                boolean dated = "DATED_SCHEDULE".equals(row.get("cost_model"));
                if (!dated && !"CALLER_FILL_MODEL".equals(row.get("cost_model"))) {
                    throw new IllegalArgumentException("unknown cost model");
                }
                if (dated != scheduleEnabled) throw new IllegalArgumentException("cost model disagrees with manifest");
                if (dated) {
                    if (!scheduleId.equals(row.get("schedule_id"))
                            || !manifest.get("execution_cost_schedule_source_file_sha256")
                                    .equals(row.get("schedule_source_file_sha256"))
                            || row.get("schedule_entry_id").isBlank() || row.get("source_reference").isBlank()) {
                        throw new IllegalArgumentException("dated schedule provenance mismatch");
                    }
                } else if (!row.get("schedule_id").isEmpty() || !row.get("schedule_entry_id").isEmpty()
                        || !row.get("schedule_source_file_sha256").isEmpty()) {
                    throw new IllegalArgumentException("fill-model fee row carries schedule provenance");
                }
                BigDecimal calculated = decimal(row, "legacy_fill_model_fee_inr")
                        .add(decimal(row, "brokerage_inr")).add(decimal(row, "exchange_transaction_inr"))
                        .add(decimal(row, "ipft_contribution_inr")).add(decimal(row, "sebi_turnover_inr"))
                        .add(decimal(row, "stt_inr")).add(decimal(row, "stamp_duty_inr"))
                        .add(decimal(row, "gst_inr"));
                if (calculated.compareTo(decimal(row, "total_fee_inr")) != 0) {
                    throw new IllegalArgumentException("itemized fee total mismatch");
                }
                BigDecimal executionFee = executionFees.get(executionId);
                if (executionFee == null || executionFee.compareTo(decimal(row, "total_fee_inr")) != 0) {
                    throw new IllegalArgumentException("fee breakdown does not match its execution ledger row");
                }
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid execution fee breakdown row for " + row.get("execution_id"), invalid);
            }
        }
        if (!seenExecutionIds.equals(executionFees.keySet())) {
            throw new IOException("Execution ledger and fee breakdown execution IDs do not match");
        }
    }

    private static Map<String, BigDecimal> readExecutionFees(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        List<String> expectedHeaders = List.of("execution_id", "sequence", "instrument_id", "timestamp_utc",
                "side", "quantity_units", "price", "fee_inr");
        if (!table.headers().equals(expectedHeaders)) throw new IOException("Unexpected portfolio execution schema");
        Map<String, BigDecimal> feesByExecution = new TreeMap<>();
        for (Map<String, String> row : table.rows()) {
            try {
                String executionId = row.get("execution_id");
                if (executionId == null || executionId.isBlank() || !executionId.equals(executionId.strip())) {
                    throw new IllegalArgumentException("execution ID must be non-empty and trimmed");
                }
                BigDecimal fee = new BigDecimal(row.get("fee_inr"));
                if (fee.signum() < 0) throw new IllegalArgumentException("execution fee cannot be negative");
                if (feesByExecution.putIfAbsent(executionId, fee) != null) {
                    throw new IllegalArgumentException("duplicate execution ID");
                }
            } catch (RuntimeException invalid) {
                throw new IOException("Invalid portfolio execution row for " + row.get("execution_id"), invalid);
            }
        }
        return feesByExecution;
    }

    private static BigDecimal decimal(Map<String, String> row, String key) {
        BigDecimal value = new BigDecimal(row.get(key));
        if (value.signum() < 0) throw new IllegalArgumentException("negative " + key);
        return value;
    }

    private static void requireHash(String value, String label) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IOException("Invalid " + label);
    }
}
