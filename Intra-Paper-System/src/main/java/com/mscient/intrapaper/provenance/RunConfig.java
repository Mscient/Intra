package com.mscient.intrapaper.provenance;

import com.mscient.intrapaper.reference.DataRightsStatus;
import com.mscient.intrapaper.reference.TimestampRole;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** Frozen, explicit inputs for one offline run. */
public record RunConfig(
        Path configPath,
        String configSha256,
        String datasetId,
        String datasetSourceName,
        Instant datasetAcquiredAt,
        String timezone,
        TimestampRole timestampRole,
        String interval,
        DataRightsStatus dataRightsStatus,
        Optional<String> dataRightsReference,
        String snapshotId,
        String snapshotSourceName,
        Instant snapshotRetrievedAt,
        LocalDate snapshotEffectiveFrom,
        Path instrumentMasterPath,
        Path sessionCalendarPath,
        int fastBars,
        int slowBars,
        int requestedLots,
        int maxPositionLots,
        BigDecimal maxNotionalInr,
        BigDecimal maxDailyLossInr,
        String costStatus,
        Optional<String> costEvidenceReference,
        int slippageTicks,
        BigDecimal feePerOrderInr,
        BigDecimal feePerUnitInr,
        boolean syntheticFixture,
        String commitId) {

    private static final Set<String> ALLOWED = Set.of(
            "dataset.id", "dataset.source_name", "dataset.acquired_at_utc", "dataset.timezone", "dataset.timestamp_role",
            "dataset.interval", "dataset.rights_status", "dataset.rights_reference", "dataset.synthetic_fixture",
            "reference.snapshot_id", "reference.source_name", "reference.retrieved_at_utc", "reference.effective_from",
            "reference.file", "calendar.file", "strategy.fast_bars", "strategy.slow_bars", "strategy.lots",
            "risk.max_position_lots", "risk.max_notional_inr", "risk.max_daily_loss_inr", "cost.status",
            "cost.evidence_reference", "cost.slippage_ticks", "cost.fee_per_order_inr", "cost.fee_per_unit_inr",
            "run.commit_id");

    public static RunConfig load(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Properties p = new Properties();
        String configText = Files.readString(absolute, StandardCharsets.UTF_8);
        if (configText.startsWith("\uFEFF")) configText = configText.substring(1);
        p.load(new StringReader(configText));
        Set<String> unknown = new HashSet<>(p.stringPropertyNames());
        unknown.removeAll(ALLOWED);
        if (!unknown.isEmpty()) throw new IllegalArgumentException("Unknown configuration keys: " + unknown);

        String dataStatusText = required(p, "dataset.rights_status");
        DataRightsStatus dataStatus = DataRightsStatus.valueOf(dataStatusText);
        Optional<String> rightsRef = optional(p, "dataset.rights_reference");
        if (dataStatus == DataRightsStatus.AUTHORIZED_LOCAL_REPLAY && rightsRef.isEmpty()) {
            throw new IllegalArgumentException("dataset.rights_reference is required for AUTHORIZED_LOCAL_REPLAY");
        }
        String costStatus = required(p, "cost.status");
        if (!Set.of("UNVERIFIED", "VERIFIED").contains(costStatus)) {
            throw new IllegalArgumentException("cost.status must be UNVERIFIED or VERIFIED");
        }
        Optional<String> costEvidence = optional(p, "cost.evidence_reference");
        if (costStatus.equals("VERIFIED") && costEvidence.isEmpty()) {
            throw new IllegalArgumentException("cost.evidence_reference is required for VERIFIED costs");
        }
        boolean synthetic = Boolean.parseBoolean(required(p, "dataset.synthetic_fixture"));
        if (!Set.of("true", "false").contains(required(p, "dataset.synthetic_fixture").toLowerCase())) {
            throw new IllegalArgumentException("dataset.synthetic_fixture must be true or false");
        }
        if (synthetic && (dataStatus != DataRightsStatus.UNVERIFIED || !costStatus.equals("UNVERIFIED"))) {
            throw new IllegalArgumentException("Synthetic fixtures must remain UNVERIFIED for data rights and costs");
        }

        TimestampRole role = TimestampRole.valueOf(required(p, "dataset.timestamp_role"));
        if (role != TimestampRole.BAR_START && role != TimestampRole.BAR_END) {
            throw new IllegalArgumentException("dataset.timestamp_role must be BAR_START or BAR_END");
        }
        String timezone = required(p, "dataset.timezone");
        ZoneId.of(timezone);
        BigDecimal notional = positive(p, "risk.max_notional_inr");
        BigDecimal dailyLoss = positive(p, "risk.max_daily_loss_inr");
        int fast = positiveInt(p, "strategy.fast_bars");
        int slow = positiveInt(p, "strategy.slow_bars");
        if (fast < 2 || slow <= fast) throw new IllegalArgumentException("Require 2 <= strategy.fast_bars < strategy.slow_bars");
        int requestedLots = positiveInt(p, "strategy.lots");
        int maxPosition = positiveInt(p, "risk.max_position_lots");
        if (requestedLots > maxPosition) throw new IllegalArgumentException("strategy.lots exceeds risk.max_position_lots");
        int slippage = Integer.parseInt(required(p, "cost.slippage_ticks"));
        if (slippage < 0) throw new IllegalArgumentException("cost.slippage_ticks must be non-negative");
        BigDecimal feeOrder = nonNegative(p, "cost.fee_per_order_inr");
        BigDecimal feeUnit = nonNegative(p, "cost.fee_per_unit_inr");
        Path base = absolute.getParent();
        return new RunConfig(
                absolute,
                sha256(Files.readAllBytes(absolute)),
                logicalId(required(p, "dataset.id"), "dataset.id"),
                required(p, "dataset.source_name"),
                Instant.parse(required(p, "dataset.acquired_at_utc")),
                timezone,
                role,
                required(p, "dataset.interval"),
                dataStatus,
                rightsRef.map(value -> logicalId(value, "dataset.rights_reference")),
                logicalId(required(p, "reference.snapshot_id"), "reference.snapshot_id"),
                required(p, "reference.source_name"),
                Instant.parse(required(p, "reference.retrieved_at_utc")),
                LocalDate.parse(required(p, "reference.effective_from")),
                resolve(base, required(p, "reference.file")),
                resolve(base, required(p, "calendar.file")),
                fast,
                slow,
                requestedLots,
                maxPosition,
                notional,
                dailyLoss,
                costStatus,
                costEvidence.map(value -> logicalId(value, "cost.evidence_reference")),
                slippage,
                feeOrder,
                feeUnit,
                synthetic,
                optional(p, "run.commit_id").orElse("unknown"));
    }

    private static Path resolve(Path base, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : base.resolve(path)).normalize();
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("Missing or invalid configuration key: " + key);
        }
        return value;
    }

    private static Optional<String> optional(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) return Optional.empty();
        if (!value.equals(value.strip())) throw new IllegalArgumentException(key + " must be trimmed");
        return Optional.of(value);
    }

    private static int positiveInt(Properties p, String key) {
        int value = Integer.parseInt(required(p, key));
        if (value < 1) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }

    private static BigDecimal positive(Properties p, String key) {
        BigDecimal value = new BigDecimal(required(p, key));
        if (value.signum() <= 0) throw new IllegalArgumentException(key + " must be positive");
        return value.stripTrailingZeros();
    }

    private static BigDecimal nonNegative(Properties p, String key) {
        BigDecimal value = new BigDecimal(required(p, key));
        if (value.signum() < 0) throw new IllegalArgumentException(key + " must be non-negative");
        return value.stripTrailingZeros();
    }

    private static String logicalId(String value, String key) {
        if (value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\")
                || value.contains("?") || value.contains("#") || value.contains("@")) {
            throw new IllegalArgumentException(key + " must be a logical ID, not a path or URL");
        }
        return value;
    }

    public static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
