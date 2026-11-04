package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.provenance.RunConfig;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Atomically publishes diagnostic-only portfolio replay artifacts under the replay fingerprint. */
public final class PortfolioReplayArtifactWriter {
    private static final List<String> OUTPUT_NAMES = List.of(
            "decisions.csv", "orders.csv", "executions.csv", "equity.csv");

    public record WrittenRun(String runId, Path path) { }

    public WrittenRun write(Path outputRoot, PortfolioReplayEngine.Result result) throws IOException {
        if (outputRoot == null || result == null) throw new NullPointerException("outputRoot/result");
        requireHash(result.validatedDatasetsSha256(), "validated dataset fingerprint");
        requireHash(result.replayFingerprintSha256(), "replay fingerprint");
        if (!result.classification().equals("REPLAY_DIAGNOSTIC_ONLY") || result.performanceClaimEligible()) {
            throw new IllegalArgumentException("Only diagnostic-only, performance-ineligible portfolio replays may be published");
        }

        Path root = outputRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IOException("Portfolio output root must be a real directory, not a symbolic link");
        }
        Path destination = root.resolve(result.replayFingerprintSha256());
        Path lockPath = root.resolve(".portfolio-publish-lock-" + result.replayFingerprintSha256());
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(lockPath) || !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Portfolio publish lock path is not a regular file");
        }
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException alreadyLocked) {
                throw new IOException("Portfolio artifact publication is already active for this replay", alreadyLocked);
            }
            if (lock == null) throw new IOException("Portfolio artifact publication is already active for this replay");
            try (lock) {
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    throw new FileAlreadyExistsException(destination.toString());
                }
                return publish(root, destination, result);
            }
        }
    }

    private static WrittenRun publish(Path root, Path destination, PortfolioReplayEngine.Result result) throws IOException {
        Path staging = root.resolve(".portfolio-staging-" + result.replayFingerprintSha256() + "-" + UUID.randomUUID());
        Files.createDirectory(staging);
        boolean published = false;
        try {
            writeNew(staging.resolve("decisions.csv"), decisionsCsv(result));
            writeNew(staging.resolve("orders.csv"), ordersCsv(result));
            writeNew(staging.resolve("executions.csv"), executionsCsv(result));
            writeNew(staging.resolve("equity.csv"), equityCsv(result));
            Map<String, String> hashes = new TreeMap<>();
            for (String name : OUTPUT_NAMES) hashes.put(name, RunConfig.sha256(Files.readAllBytes(staging.resolve(name))));
            writeNew(staging.resolve("run-manifest.properties"), manifest(result, hashes));
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                throw new IOException("Portfolio artifact publication requires an atomic same-volume move", unsupported);
            }
            published = true;
            return new WrittenRun(result.replayFingerprintSha256(), destination);
        } finally {
            if (!published && Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(staging)) {
                try (var children = Files.list(staging)) {
                    for (Path child : children.toList()) {
                        if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(child)) {
                            Files.deleteIfExists(child);
                        }
                    }
                }
                Files.deleteIfExists(staging);
            }
        }
    }

    private static String manifest(PortfolioReplayEngine.Result result, Map<String, String> hashes) {
        StringBuilder out = new StringBuilder();
        put(out, "schema_version", "1");
        put(out, "run_id", result.replayFingerprintSha256());
        put(out, "validated_datasets_sha256", result.validatedDatasetsSha256());
        put(out, "frame_inputs_sha256", result.frameInputsSha256());
        put(out, "strategy_id_base64", b64(result.strategyId()));
        put(out, "strategy_fingerprint_sha256", result.strategyFingerprintSha256());
        put(out, "fill_model_id_base64", b64(result.fillModelId()));
        put(out, "fill_model_fingerprint_sha256", result.fillModelFingerprintSha256());
        put(out, "build_commit", result.buildCommit());
        put(out, "classification", result.classification());
        put(out, "performance_claim_eligible", "false");
        put(out, "decision_count", Integer.toString(result.decisions().size()));
        put(out, "order_count", Integer.toString(result.orders().size()));
        put(out, "execution_count", Integer.toString(result.executions().size()));
        put(out, "snapshot_count", Integer.toString(result.snapshots().size()));
        put(out, "output_files", String.join(",", OUTPUT_NAMES));
        for (String name : OUTPUT_NAMES) put(out, "sha256." + name, hashes.get(name));
        return out.toString();
    }

    private static String decisionsCsv(PortfolioReplayEngine.Result result) {
        List<String> rows = new ArrayList<>();
        rows.add("frame_index,decision_timestamp_utc,session_date,status,disposition,target_signed_lots,reason");
        for (PortfolioReplayEngine.DecisionRecord decision : result.decisions()) {
            String targets = new TreeMap<>(decision.target().signedLotsByInstrument()).entrySet().stream()
                    .map(entry -> entry.getKey() + ":" + entry.getValue()).collect(java.util.stream.Collectors.joining(";"));
            rows.add(csv(decision.frameIndex(), decision.decisionTimestamp(), decision.sessionDate(), decision.status(),
                    decision.disposition(), targets, decision.target().reason()));
        }
        return String.join("\n", rows) + "\n";
    }

    private static String ordersCsv(PortfolioReplayEngine.Result result) {
        List<String> rows = new ArrayList<>();
        rows.add("sequence,intent_id,decision_timestamp_utc,execution_timestamp_utc,instrument_id,side,current_units,target_units,requested_units,status,filled_units,execution_id,fill_price,fee_inr,reason");
        for (PortfolioReplayEngine.OrderOutcome outcome : result.orders()) {
            PortfolioReplayEngine.OrderIntent intent = outcome.intent();
            PortfolioExecution execution = outcome.execution().orElse(null);
            rows.add(csv(intent.sequence(), intent.intentId(), intent.decisionTimestamp(), intent.executionTimestamp(),
                    intent.instrumentId(), intent.side(), intent.currentUnits(), intent.targetUnits(), intent.requestedUnits(),
                    outcome.status(), outcome.filledUnits(), execution == null ? "" : execution.executionId(),
                    execution == null ? "" : decimal(execution.price()), execution == null ? "" : decimal(execution.feeInr()), intent.reason()));
        }
        return String.join("\n", rows) + "\n";
    }

    private static String executionsCsv(PortfolioReplayEngine.Result result) {
        List<String> rows = new ArrayList<>();
        rows.add("execution_id,sequence,instrument_id,timestamp_utc,side,quantity_units,price,fee_inr");
        for (PortfolioExecution execution : result.executions()) {
            rows.add(csv(execution.executionId(), execution.sequence(), execution.instrumentId(), execution.timestamp(),
                    execution.side(), execution.quantityUnits(), decimal(execution.price()), decimal(execution.feeInr())));
        }
        return String.join("\n", rows) + "\n";
    }

    private static String equityCsv(PortfolioReplayEngine.Result result) {
        List<String> rows = new ArrayList<>();
        rows.add("timestamp_utc,phase,instrument_id,signed_units,average_open_price,realized_gross_pnl_inr,fees_inr,unrealized_pnl_inr,total_realized_gross_pnl_inr,total_fees_inr,total_unrealized_pnl_inr,net_pnl_inr");
        for (PortfolioAccountingLedger.Snapshot snapshot : result.snapshots()) {
            for (Map.Entry<String, PortfolioAccountingLedger.PositionSnapshot> entry : new TreeMap<>(snapshot.positions()).entrySet()) {
                var position = entry.getValue();
                rows.add(csv(snapshot.timestamp(), snapshot.phase(), entry.getKey(), position.signedUnits(),
                        decimal(position.averageOpenPrice()), decimal(position.realizedGrossPnlInr()),
                        decimal(position.feesInr()), decimal(position.unrealizedPnlInr()),
                        decimal(snapshot.realizedGrossPnlInr()), decimal(snapshot.feesInr()),
                        decimal(snapshot.unrealizedPnlInr()), decimal(snapshot.netPnlInr())));
            }
        }
        return String.join("\n", rows) + "\n";
    }

    private static String csv(Object... values) {
        List<String> cells = new ArrayList<>(values.length);
        for (Object value : values) {
            String raw = value == null ? "" : value.toString();
            cells.add('"' + raw.replace("\"", "\"\"") + '"');
        }
        return String.join(",", cells);
    }

    private static String decimal(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static void writeNew(Path path, String content) throws IOException {
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void put(StringBuilder out, String key, String value) { out.append(key).append('=').append(value).append('\n'); }
    private static String b64(String value) { return java.util.Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }

    private static void requireHash(String value, String label) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(label + " must be lowercase SHA-256");
    }
}
