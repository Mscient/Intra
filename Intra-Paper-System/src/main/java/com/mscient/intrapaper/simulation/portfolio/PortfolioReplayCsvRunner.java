package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.data.ValidatedPortfolioDataset;
import com.mscient.intrapaper.provenance.CrossSessionPositionPolicy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Strict local-file CLI workflow for validated multi-contract diagnostic portfolio replay. */
public final class PortfolioReplayCsvRunner {
    private static final List<String> INPUT_HEADERS = List.of("instrument_id", "bars_csv", "run_properties", "max_abs_lots");
    private static final List<String> REQUIRED_SETTING_KEYS = List.of(
            "portfolio.pending_intent_boundary_policy", "portfolio.position_boundary_policy",
            "portfolio.require_contiguous_frames_within_session", "strategy.fast_bars",
            "strategy.slow_bars", "strategy.long_lots", "fill.slippage_ticks",
            "fill.fee_per_execution_inr");
    private static final String COST_SCHEDULE_KEY = "portfolio.execution_cost_schedule_csv";
    private static final List<String> SETTLEMENT_KEYS = List.of("portfolio.settlement_policy", "portfolio.settlement_instruments",
            "portfolio.settlement_events_csv", "portfolio.settlement_cost_schedule_csv");

    public record Replay(ValidatedPortfolioDataset dataset, PortfolioReplayEngine.Result result) { }

    private PortfolioReplayCsvRunner() { }

    public static Replay replay(Path inputManifest, Path propertiesPath) throws IOException {
        return replay(inputManifest, propertiesPath, java.util.function.UnaryOperator.identity());
    }

    /** Allows a separate offline adapter to wrap execution before any fill model is called. */
    public static Replay replay(Path inputManifest, Path propertiesPath,
            java.util.function.UnaryOperator<PortfolioFillModel> fillAdapter) throws IOException {
        Path inputsPath = inputManifest.toAbsolutePath().normalize();
        Path settingsPath = propertiesPath.toAbsolutePath().normalize();
        CsvTable table = CsvTable.read(inputsPath);
        if (!table.headers().equals(INPUT_HEADERS)) {
            throw new IllegalArgumentException("Portfolio input CSV headers must be exactly " + INPUT_HEADERS);
        }
        if (table.rows().size() < 2) throw new IllegalArgumentException("Portfolio input CSV requires at least two instruments");

        TreeMap<String, ValidatedPortfolioDataset.Input> inputs = new TreeMap<>();
        TreeMap<String, Integer> maxLots = new TreeMap<>();
        Path base = inputsPath.getParent();
        for (Map<String, String> row : table.rows()) {
            String instrumentId = required(row, "instrument_id");
            String barsValue = required(row, "bars_csv");
            String configValue = required(row, "run_properties");
            int maxAbsLots = positiveInteger(required(row, "max_abs_lots"), "max_abs_lots");
            Path bars = resolve(base, barsValue);
            Path config = resolve(base, configValue);
            if (inputs.putIfAbsent(instrumentId, new ValidatedPortfolioDataset.Input(bars, config)) != null) {
                throw new IllegalArgumentException("Duplicate portfolio instrument_id: " + instrumentId);
            }
            maxLots.put(instrumentId, maxAbsLots);
        }

        Map<String, String> settings = readStrictSettings(settingsPath);
        int fast = positiveInteger(settings.get("strategy.fast_bars"), "strategy.fast_bars");
        int slow = positiveInteger(settings.get("strategy.slow_bars"), "strategy.slow_bars");
        int longLots = positiveInteger(settings.get("strategy.long_lots"), "strategy.long_lots");
        if (fast < 2 || slow <= fast) throw new IllegalArgumentException("Require strategy.fast_bars >= 2 and strategy.slow_bars > fast_bars");
        for (Map.Entry<String, Integer> entry : maxLots.entrySet()) {
            if (entry.getValue() < longLots) {
                throw new IllegalArgumentException("strategy.long_lots exceeds max_abs_lots for " + entry.getKey());
            }
        }
        int slippageTicks = nonNegativeInteger(settings.get("fill.slippage_ticks"), "fill.slippage_ticks");
        BigDecimal fee = nonNegativeDecimal(settings.get("fill.fee_per_execution_inr"), "fill.fee_per_execution_inr");
        String scheduleSetting = settings.getOrDefault(COST_SCHEDULE_KEY, "NONE");
        Optional<PortfolioExecutionCostSchedule> costSchedule = scheduleSetting.equals("NONE")
                ? Optional.empty()
                : Optional.of(PortfolioExecutionCostSchedule.load(resolve(settingsPath.getParent(), scheduleSetting)));
        if (costSchedule.isPresent() && fee.signum() != 0) {
            throw new IllegalArgumentException("fill.fee_per_execution_inr must be zero when portfolio.execution_cost_schedule_csv is enabled");
        }
        PortfolioReplayEngine.PendingIntentBoundaryPolicy pendingPolicy = enumValue(
                PortfolioReplayEngine.PendingIntentBoundaryPolicy.class,
                settings.get("portfolio.pending_intent_boundary_policy"), "pending intent boundary policy");
        CrossSessionPositionPolicy positionPolicy = enumValue(CrossSessionPositionPolicy.class,
                settings.get("portfolio.position_boundary_policy"), "position boundary policy");
        boolean contiguous = strictBoolean(settings.get("portfolio.require_contiguous_frames_within_session"));

        PortfolioSettlementPolicy settlementPolicy = enumValue(PortfolioSettlementPolicy.class,
                settings.getOrDefault("portfolio.settlement_policy", "NONE"), "settlement policy");
        java.util.Set<String> settlementTargets = new java.util.TreeSet<>();
        String eventsPath = settings.getOrDefault("portfolio.settlement_events_csv", "NONE");
        String settlementCostPath = settings.getOrDefault("portfolio.settlement_cost_schedule_csv", "NONE");
        if (settlementPolicy == PortfolioSettlementPolicy.NONE) {
            if (settings.containsKey("portfolio.settlement_instruments") || !eventsPath.equals("NONE") || !settlementCostPath.equals("NONE"))
                throw new IllegalArgumentException("Settlement targets/files require EXPLICIT_FINAL_SESSION policy");
        } else {
            String declared = settings.get("portfolio.settlement_instruments");
            if (declared == null || eventsPath.equals("NONE")) throw new IllegalArgumentException("Explicit settlement requires instruments and events CSV");
            for (String target : declared.split(",", -1)) {
                PortfolioSettlementContract.id(target, "settlement instrument");
                if (!settlementTargets.add(target)) throw new IllegalArgumentException("Duplicate settlement target: " + target);
            }
        }
        Optional<PortfolioSettlementEvents> settlementEvents = eventsPath.equals("NONE") ? Optional.empty()
                : Optional.of(PortfolioSettlementEvents.load(resolve(settingsPath.getParent(), eventsPath)));
        Optional<PortfolioSettlementCostSchedule> settlementCosts = settlementCostPath.equals("NONE") ? Optional.empty()
                : Optional.of(PortfolioSettlementCostSchedule.load(resolve(settingsPath.getParent(), settlementCostPath)));
        ValidatedPortfolioDataset dataset = ValidatedPortfolioDataset.load(inputs, settlementTargets, settlementEvents);
        PortfolioReplayEngine.Config config = new PortfolioReplayEngine.Config(maxLots, pendingPolicy,
                positionPolicy, contiguous, settlementPolicy, settlementTargets);
        PortfolioStrategy strategy = new PortfolioSmaCrossoverStrategy(fast, slow, longLots);
        PortfolioFillModel fillModel = java.util.Objects.requireNonNull(fillAdapter.apply(new OpeningPriceIocFillModel(slippageTicks, fee)));
        PortfolioReplayEngine.Result result = new PortfolioReplayEngine(config, strategy, fillModel, costSchedule,
                settlementEvents, settlementCosts).run(dataset);
        return new Replay(dataset, result);
    }

    public static PortfolioReplayArtifactWriter.WrittenRun run(Path inputManifest, Path propertiesPath,
            Path outputRoot) throws IOException {
        Replay replay = replay(inputManifest, propertiesPath);
        return new PortfolioReplayArtifactWriter().write(outputRoot, replay.dataset(), replay.result());
    }

    private static Map<String, String> readStrictSettings(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8);
        TreeMap<String, String> values = new TreeMap<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int separator = line.indexOf('=');
            if (separator < 1 || separator != line.lastIndexOf('=')) {
                throw new IllegalArgumentException("Portfolio settings line " + (index + 1) + " must have one key=value separator");
            }
            String key = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();
            if (key.isEmpty() || value.isEmpty() || values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Empty or duplicate portfolio setting on line " + (index + 1));
            }
        }
        java.util.TreeSet<String> required = new java.util.TreeSet<>(REQUIRED_SETTING_KEYS);
        java.util.TreeSet<String> withOptionalSchedule = new java.util.TreeSet<>(required);
        withOptionalSchedule.add(COST_SCHEDULE_KEY);
        withOptionalSchedule.addAll(SETTLEMENT_KEYS);
        if (!values.keySet().containsAll(required) || !withOptionalSchedule.containsAll(values.keySet())) {
            throw new IllegalArgumentException("Portfolio settings keys must be exactly " + REQUIRED_SETTING_KEYS
                    + " with optional " + COST_SCHEDULE_KEY + " and " + SETTLEMENT_KEYS + "; found " + values.keySet());
        }
        return Map.copyOf(values);
    }

    private static Path resolve(Path base, String value) {
        Path path = Path.of(value);
        return (path.isAbsolute() ? path : base.resolve(path)).toAbsolutePath().normalize();
    }

    private static String required(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("Portfolio input " + key + " must be non-empty and trimmed");
        }
        return value;
    }

    private static int positiveInteger(String value, String label) {
        int parsed = Integer.parseInt(value);
        if (parsed < 1) throw new IllegalArgumentException(label + " must be positive");
        return parsed;
    }

    private static int nonNegativeInteger(String value, String label) {
        int parsed = Integer.parseInt(value);
        if (parsed < 0) throw new IllegalArgumentException(label + " must be non-negative");
        return parsed;
    }

    private static BigDecimal nonNegativeDecimal(String value, String label) {
        BigDecimal parsed = new BigDecimal(value);
        if (parsed.signum() < 0) throw new IllegalArgumentException(label + " must be non-negative");
        return parsed.stripTrailingZeros();
    }

    private static boolean strictBoolean(String value) {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IllegalArgumentException("Portfolio continuity setting must be exactly true or false");
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String label) {
        try {
            return Enum.valueOf(type, value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid " + label + ": " + value, invalid);
        }
    }
}
