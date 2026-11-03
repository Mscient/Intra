package com.mscient.intrapaper.provenance;

import com.mscient.intrapaper.reference.DataRightsStatus;
import com.mscient.intrapaper.reference.TimestampRole;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

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
        Path marketRuleRegimePath,
        Optional<Path> costSchedulePath,
        Optional<Path> componentCostSchedulePath,
        Optional<Path> marginRequirementSchedulePath,
        Optional<Path> expirySettlementPath,
        Optional<Path> settlementCostSchedulePath,
        int fastBars,
        int slowBars,
        int requestedLots,
        int maxPositionLots,
        int checkpointIntervalBars,
        BigDecimal maxNotionalInr,
        BigDecimal marginPerLotInr,
        BigDecimal maxMarginInr,
        BigDecimal maxDailyLossInr,
        long minSecondsBetweenEntryOrders,
        boolean allowNextSessionOrders,
        CrossSessionPositionPolicy crossSessionPositionPolicy,
        boolean allowIntraSessionGaps,
        int maxMissingIntervalsPerSession,
        String costStatus,
        Optional<String> costEvidenceReference,
        int slippageTicks,
        BigDecimal feePerOrderInr,
        BigDecimal feePerUnitInr,
        BigDecimal notionalFeeBps,
        boolean syntheticFixture,
        String commitId,
        ExecutionModel executionModel,
        Optional<Path> quoteJournalPath,
        Optional<String> quoteSourceName,
        Duration decisionToArrivalLatency,
        Duration quoteMaximumAge,
        Duration quoteMaximumFutureSkew,
        Optional<Path> researchTrialLedgerPath,
        Optional<String> researchTrialId,
        Optional<String> researchStrategyVersion,
        Optional<String> researchVariantId,
        Optional<Path> researchStrategyCardPath,
        Optional<Path> evaluationSplitPlanPath,
        String trialParameterConfigSha256) {

    private static final Set<String> ALLOWED = Set.of(
            "dataset.id", "dataset.source_name", "dataset.acquired_at_utc", "dataset.timezone", "dataset.timestamp_role",
            "dataset.interval", "dataset.rights_status", "dataset.rights_reference", "dataset.synthetic_fixture",
            "reference.snapshot_id", "reference.source_name", "reference.retrieved_at_utc", "reference.effective_from",
            "reference.file", "calendar.file", "rules.file", "settlement.file", "strategy.fast_bars", "strategy.slow_bars", "strategy.lots",
            "risk.max_position_lots", "risk.max_notional_inr", "risk.margin_per_lot_inr", "risk.max_margin_inr",
            "risk.margin_schedule_file",
            "risk.max_daily_loss_inr", "risk.min_seconds_between_entry_orders", "cost.status",
            "settlement.cost_schedule_file",
            "recovery.checkpoint_interval_bars",
            "cost.evidence_reference", "cost.slippage_ticks", "cost.fee_per_order_inr", "cost.fee_per_unit_inr",
            "cost.notional_fee_bps", "cost.schedule_file", "cost.components_file",
            "orders.allow_next_session", "position.cross_session_policy", "quality.allow_intrasession_gaps",
            "quality.max_missing_intervals_per_session", "run.commit_id", "execution.model",
            "execution.quote_journal_file", "execution.quote_source_name", "execution.decision_to_arrival_latency_ms",
            "execution.quote_maximum_age_ms", "execution.quote_maximum_future_skew_ms",
            "research.trial_ledger_file", "research.trial_id", "research.strategy_version", "research.variant_id",
            "research.strategy_card_file", "evaluation.split_plan_file");

    public static RunConfig load(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Properties p = new Properties();
        byte[] configBytes = Files.readAllBytes(absolute);
        String configText = new String(configBytes, StandardCharsets.UTF_8);
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
        if (!synthetic && dataStatus != DataRightsStatus.AUTHORIZED_LOCAL_REPLAY) {
            throw new IllegalArgumentException("Non-synthetic replay requires AUTHORIZED_LOCAL_REPLAY and a data-rights reference");
        }
        String allowCrossSessionText = required(p, "orders.allow_next_session").toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("true", "false").contains(allowCrossSessionText)) {
            throw new IllegalArgumentException("orders.allow_next_session must be true or false");
        }
        String crossSessionPolicyText = required(p, "position.cross_session_policy");
        if (!Set.of("ALLOW_OVERNIGHT", "REQUIRE_FLAT", "AUTO_FLATTEN_SESSION_END").contains(crossSessionPolicyText)) {
            throw new IllegalArgumentException("position.cross_session_policy must be ALLOW_OVERNIGHT, REQUIRE_FLAT, or AUTO_FLATTEN_SESSION_END");
        }
        CrossSessionPositionPolicy crossSessionPositionPolicy = CrossSessionPositionPolicy.valueOf(crossSessionPolicyText);
        if (crossSessionPositionPolicy.requiresFlatAtSessionEnd() && Boolean.parseBoolean(allowCrossSessionText)) {
            throw new IllegalArgumentException("A flat-at-session-end position policy cannot be combined with orders.allow_next_session=true");
        }

        String allowGapsText = required(p, "quality.allow_intrasession_gaps").toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("true", "false").contains(allowGapsText)) {
            throw new IllegalArgumentException("quality.allow_intrasession_gaps must be true or false");
        }
        int maxMissingIntervals = Integer.parseInt(required(p, "quality.max_missing_intervals_per_session"));
        if (maxMissingIntervals < 0) {
            throw new IllegalArgumentException("quality.max_missing_intervals_per_session must be non-negative");
        }

        TimestampRole role = TimestampRole.valueOf(required(p, "dataset.timestamp_role"));
        if (role != TimestampRole.BAR_START && role != TimestampRole.BAR_END) {
            throw new IllegalArgumentException("dataset.timestamp_role must be BAR_START or BAR_END");
        }
        String timezone = required(p, "dataset.timezone");
        ZoneId.of(timezone);
        BigDecimal notional = positive(p, "risk.max_notional_inr");
        Optional<Path> marginSchedule = optional(p, "risk.margin_schedule_file").map(value -> resolve(absolute.getParent(), value));
        BigDecimal marginPerLot;
        if (marginSchedule.isPresent()) {
            marginPerLot = optionalNonNegative(p, "risk.margin_per_lot_inr", BigDecimal.ZERO);
            if (marginPerLot.signum() != 0) {
                throw new IllegalArgumentException("risk.margin_per_lot_inr must be omitted or zero when risk.margin_schedule_file is set");
            }
        } else {
            marginPerLot = positive(p, "risk.margin_per_lot_inr");
        }
        BigDecimal maxMargin = positive(p, "risk.max_margin_inr");
        BigDecimal dailyLoss = positive(p, "risk.max_daily_loss_inr");
        long minSecondsBetweenEntryOrders = nonNegativeLong(p, "risk.min_seconds_between_entry_orders");
        int fast = positiveInt(p, "strategy.fast_bars");
        int slow = positiveInt(p, "strategy.slow_bars");
        if (fast < 2 || slow <= fast) throw new IllegalArgumentException("Require 2 <= strategy.fast_bars < strategy.slow_bars");
        int requestedLots = positiveInt(p, "strategy.lots");
        int maxPosition = positiveInt(p, "risk.max_position_lots");
        int checkpointIntervalBars = optionalNonNegativeInt(p, "recovery.checkpoint_interval_bars", 250);
        if (requestedLots > maxPosition) throw new IllegalArgumentException("strategy.lots exceeds risk.max_position_lots");
        Path base = absolute.getParent();
        Optional<Path> legacyCostSchedule = optional(p, "cost.schedule_file").map(value -> resolve(base, value));
        Optional<Path> componentCostSchedule = optional(p, "cost.components_file").map(value -> resolve(base, value));
        if (legacyCostSchedule.isPresent() && componentCostSchedule.isPresent()) {
            throw new IllegalArgumentException("cost.schedule_file and cost.components_file are mutually exclusive");
        }
        boolean componentCosts = componentCostSchedule.isPresent();
        int slippage = componentCosts ? optionalNonNegativeInt(p, "cost.slippage_ticks", 0)
                : Integer.parseInt(required(p, "cost.slippage_ticks"));
        if (slippage < 0) throw new IllegalArgumentException("cost.slippage_ticks must be non-negative");
        BigDecimal feeOrder = componentCosts ? optionalNonNegative(p, "cost.fee_per_order_inr", BigDecimal.ZERO)
                : nonNegative(p, "cost.fee_per_order_inr");
        BigDecimal feeUnit = componentCosts ? optionalNonNegative(p, "cost.fee_per_unit_inr", BigDecimal.ZERO)
                : nonNegative(p, "cost.fee_per_unit_inr");
        BigDecimal notionalFeeBps = componentCosts ? optionalNonNegative(p, "cost.notional_fee_bps", BigDecimal.ZERO)
                : nonNegative(p, "cost.notional_fee_bps");
        if (componentCosts && (slippage != 0 || feeOrder.signum() != 0 || feeUnit.signum() != 0
                || notionalFeeBps.signum() != 0)) {
            throw new IllegalArgumentException("When cost.components_file is set, legacy cost fee/slippage inputs must be omitted or zero");
        }
        ExecutionModel executionModel = ExecutionModel.valueOf(optional(p, "execution.model").orElse("BAR_NEXT_OPEN"));
        Optional<Path> quoteJournalPath = optional(p, "execution.quote_journal_file")
                .map(value -> resolve(base, value));
        Optional<String> quoteSourceName = optional(p, "execution.quote_source_name");
        Duration decisionToArrivalLatency = Duration.ZERO;
        Duration quoteMaximumAge = Duration.ZERO;
        Duration quoteMaximumFutureSkew = Duration.ZERO;
        boolean hasQuoteSettings = quoteJournalPath.isPresent() || quoteSourceName.isPresent()
                || optional(p, "execution.decision_to_arrival_latency_ms").isPresent()
                || optional(p, "execution.quote_maximum_age_ms").isPresent()
                || optional(p, "execution.quote_maximum_future_skew_ms").isPresent();
        if (executionModel != ExecutionModel.BAR_NEXT_OPEN) {
            if (quoteJournalPath.isEmpty() || quoteSourceName.isEmpty()) {
                throw new IllegalArgumentException("L1 quote execution requires execution.quote_journal_file and execution.quote_source_name");
            }
            long latencyMillis = nonNegativeLong(p, "execution.decision_to_arrival_latency_ms");
            long maximumAgeMillis = positiveLong(p, "execution.quote_maximum_age_ms");
            long maximumFutureSkewMillis = optionalNonNegativeLong(p, "execution.quote_maximum_future_skew_ms", 0);
            decisionToArrivalLatency = Duration.ofMillis(latencyMillis);
            quoteMaximumAge = Duration.ofMillis(maximumAgeMillis);
            quoteMaximumFutureSkew = Duration.ofMillis(maximumFutureSkewMillis);
            Duration barInterval = intervalDuration(required(p, "dataset.interval"));
            if (decisionToArrivalLatency.compareTo(barInterval) >= 0) {
                throw new IllegalArgumentException("execution.decision_to_arrival_latency_ms must be shorter than dataset.interval");
            }
            if (quoteSourceName.orElseThrow().isBlank()
                    || !quoteSourceName.orElseThrow().equals(quoteSourceName.orElseThrow().strip())) {
                throw new IllegalArgumentException("execution.quote_source_name must be non-empty and trimmed");
            }
        } else if (hasQuoteSettings) {
            throw new IllegalArgumentException("Quote execution settings require an L1 execution.model");
        }
        Optional<Path> trialLedgerPath = optional(p, "research.trial_ledger_file")
                .map(value -> resolve(base, value));
        Optional<String> trialId = optional(p, "research.trial_id")
                .map(value -> researchIdentifier(value, "research.trial_id"));
        Optional<String> strategyVersion = optional(p, "research.strategy_version")
                .map(value -> researchIdentifier(value, "research.strategy_version"));
        Optional<String> variantId = optional(p, "research.variant_id")
                .map(value -> researchIdentifier(value, "research.variant_id"));
        Optional<Path> strategyCardPath = optional(p, "research.strategy_card_file")
                .map(value -> resolve(base, value));
        Optional<Path> splitPlanPath = optional(p, "evaluation.split_plan_file")
                .map(value -> resolve(base, value));
        int researchSettingCount = (trialLedgerPath.isPresent() ? 1 : 0) + (trialId.isPresent() ? 1 : 0)
                + (strategyVersion.isPresent() ? 1 : 0) + (variantId.isPresent() ? 1 : 0)
                + (strategyCardPath.isPresent() ? 1 : 0) + (splitPlanPath.isPresent() ? 1 : 0);
        if (researchSettingCount != 0 && researchSettingCount != 6) {
            throw new IllegalArgumentException("Research preregistration requires trial ledger, trial ID, strategy version, "
                    + "variant ID, strategy-card file, and evaluation split-plan file together");
        }
        String trialParameterConfigSha256 = trialParameterConfigSha256(p);
        return new RunConfig(
                absolute,
                sha256(configBytes),
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
                resolve(base, required(p, "rules.file")),
                legacyCostSchedule,
                componentCostSchedule,
                marginSchedule,
                optional(p, "settlement.file").map(value -> resolve(base, value)),
                optional(p, "settlement.cost_schedule_file").map(value -> resolve(base, value)),
                fast,
                slow,
                requestedLots,
                maxPosition,
                checkpointIntervalBars,
                notional,
                marginPerLot,
                maxMargin,
                dailyLoss,
                minSecondsBetweenEntryOrders,
                Boolean.parseBoolean(allowCrossSessionText),
                crossSessionPositionPolicy,
                Boolean.parseBoolean(allowGapsText),
                maxMissingIntervals,
                costStatus,
                costEvidence.map(value -> logicalId(value, "cost.evidence_reference")),
                slippage,
                feeOrder,
                feeUnit,
                notionalFeeBps,
                synthetic,
                resolveCommit(optional(p, "run.commit_id").orElse("BUILD_COMMIT"), synthetic),
                executionModel,
                quoteJournalPath,
                quoteSourceName,
                decisionToArrivalLatency,
                quoteMaximumAge,
                quoteMaximumFutureSkew,
                trialLedgerPath,
                trialId,
                strategyVersion,
                variantId,
                strategyCardPath,
                splitPlanPath,
                trialParameterConfigSha256);
    }

    private static String researchIdentifier(String value, String key) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(key + " must be a trimmed alphanumeric identifier");
        }
        return value;
    }

    /** Canonical hash of run settings, excluding registration pointers, split path, and build commit. */
    private static String trialParameterConfigSha256(Properties properties) {
        TreeMap<String, String> canonical = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("research.") || key.equals("evaluation.split_plan_file") || key.equals("run.commit_id")) {
                continue;
            }
            canonical.put(key, properties.getProperty(key));
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeCanonical(output, "INTRA_TRIAL_PARAMETER_CONFIG_V1");
                output.writeInt(canonical.size());
                for (Map.Entry<String, String> entry : canonical.entrySet()) {
                    writeCanonical(output, entry.getKey());
                    writeCanonical(output, entry.getValue());
                }
            }
            return sha256(bytes.toByteArray());
        } catch (IOException impossibleForMemoryStream) {
            throw new IllegalStateException("Unable to hash trial parameter configuration", impossibleForMemoryStream);
        }
    }

    private static void writeCanonical(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static Path resolve(Path base, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : base.resolve(path)).normalize();
    }

    private static Duration intervalDuration(String interval) {
        return switch (interval) {
            case "ONE_MINUTE" -> Duration.ofMinutes(1);
            case "FIVE_MINUTE" -> Duration.ofMinutes(5);
            case "FIFTEEN_MINUTE" -> Duration.ofMinutes(15);
            case "THIRTY_MINUTE" -> Duration.ofMinutes(30);
            case "ONE_HOUR" -> Duration.ofHours(1);
            case "ONE_DAY" -> Duration.ofDays(1);
            default -> throw new IllegalArgumentException("Unsupported interval (no timestamp guessing): " + interval);
        };
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

    private static BigDecimal optionalNonNegative(Properties p, String key, BigDecimal defaultValue) {
        Optional<String> text = optional(p, key);
        if (text.isEmpty()) return defaultValue;
        BigDecimal value = new BigDecimal(text.orElseThrow());
        if (value.signum() < 0) throw new IllegalArgumentException(key + " must be non-negative");
        return value.stripTrailingZeros();
    }

    private static int optionalNonNegativeInt(Properties p, String key, int defaultValue) {
        Optional<String> text = optional(p, key);
        if (text.isEmpty()) return defaultValue;
        int value = Integer.parseInt(text.orElseThrow());
        if (value < 0) throw new IllegalArgumentException(key + " must be non-negative");
        return value;
    }

    private static long nonNegativeLong(Properties p, String key) {
        long value = Long.parseLong(required(p, key));
        if (value < 0) throw new IllegalArgumentException(key + " must be non-negative");
        return value;
    }

    private static long positiveLong(Properties p, String key) {
        long value = Long.parseLong(required(p, key));
        if (value < 1) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }

    private static long optionalNonNegativeLong(Properties p, String key, long defaultValue) {
        Optional<String> text = optional(p, key);
        if (text.isEmpty()) return defaultValue;
        long value = Long.parseLong(text.orElseThrow());
        if (value < 0) throw new IllegalArgumentException(key + " must be non-negative");
        return value;
    }

    private static String logicalId(String value, String key) {
        if (value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\")
                || value.contains("?") || value.contains("#") || value.contains("@")) {
            throw new IllegalArgumentException(key + " must be a logical ID, not a path or URL");
        }
        return value;
    }

    private static String resolveCommit(String requested, boolean synthetic) {
        String resolved = requested.equals("BUILD_COMMIT") ? BuildInfo.commitId() : requested;
        if (!synthetic && !resolved.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("A non-synthetic run requires a full 40-character run.commit_id or a JAR built from a Git checkout");
        }
        if (synthetic && resolved.isBlank()) return "unknown";
        return resolved;
    }

    public static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
