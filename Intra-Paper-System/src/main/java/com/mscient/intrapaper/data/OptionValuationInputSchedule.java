package com.mscient.intrapaper.data;

import com.mscient.intrapaper.pricing.OptionPricingModel;
import com.mscient.intrapaper.reference.ContractKind;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact-timestamp caller inputs for diagnostic model valuation of one option-premium bar series. */
public record OptionValuationInputSchedule(Map<Instant, Input> inputs, String sha256) {
    private static final List<String> HEADERS = List.of("timestamp", "instrument_id", "model",
            "underlying_price", "time_to_expiry_years", "risk_free_rate", "dividend_yield",
            "annualized_volatility", "source_reference");

    public record Input(Instant timestamp, String instrumentId, OptionPricingModel model,
            double underlyingPrice, double timeToExpiryYears, double riskFreeRate,
            double dividendYield, double annualizedVolatility, String sourceReference) {
        public Input {
            Objects.requireNonNull(timestamp, "timestamp");
            instrumentId = required(instrumentId, "instrument_id");
            Objects.requireNonNull(model, "model");
            finitePositive(underlyingPrice, "underlying_price");
            finitePositive(timeToExpiryYears, "time_to_expiry_years");
            finite(riskFreeRate, "risk_free_rate");
            finite(dividendYield, "dividend_yield");
            finitePositive(annualizedVolatility, "annualized_volatility");
            sourceReference = required(sourceReference, "source_reference");
            if (model == OptionPricingModel.BLACK76_FUTURE && dividendYield != 0.0) {
                throw new IllegalArgumentException("Black-76 rows must have blank dividend_yield");
            }
        }
    }

    public OptionValuationInputSchedule {
        inputs = Map.copyOf(inputs);
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Option valuation input schedule requires a SHA-256");
        }
    }

    public static OptionValuationInputSchedule load(Path path, List<Bar> bars) throws IOException {
        CsvTable table = CsvTable.read(path);
        if (!table.headers().equals(HEADERS)) {
            throw new IllegalArgumentException("Option valuation CSV headers must exactly match: " + HEADERS);
        }
        if (bars.isEmpty() || bars.stream().anyMatch(bar -> bar.contractKind() != ContractKind.OPTION)) {
            throw new IllegalArgumentException("Option valuation inputs require an option-premium bars dataset");
        }
        Map<Instant, Bar> barsByCloseTimestamp = new HashMap<>();
        for (Bar bar : bars) {
            Instant closeTimestamp = closeTimestamp(bar);
            if (barsByCloseTimestamp.putIfAbsent(closeTimestamp, bar) != null) {
                throw new IllegalArgumentException("Option valuation requires unique bar close timestamps");
            }
        }
        Map<Instant, Input> parsed = new HashMap<>();
        for (int index = 0; index < table.rows().size(); index++) {
            Map<String, String> row = table.rows().get(index);
            int line = index + 2;
            Instant timestamp;
            OptionPricingModel model;
            try {
                timestamp = Instant.parse(required(row, "timestamp", line));
                model = OptionPricingModel.valueOf(required(row, "model", line));
            } catch (RuntimeException failure) {
                throw new IllegalArgumentException("Invalid timestamp or model at row " + line, failure);
            }
            String instrumentId = required(row, "instrument_id", line);
            Bar bar = barsByCloseTimestamp.get(timestamp);
            if (bar == null || !bar.instrumentId().equals(instrumentId)) {
                throw new IllegalArgumentException("Option valuation row does not match a dataset bar at row " + line);
            }
            double dividendYield = model == OptionPricingModel.BSM_SPOT
                    ? decimal(row, "dividend_yield", line) : blankDecimal(row, "dividend_yield", line);
            Input input = new Input(timestamp, instrumentId, model,
                    decimal(row, "underlying_price", line), decimal(row, "time_to_expiry_years", line),
                    decimal(row, "risk_free_rate", line), dividendYield,
                    decimal(row, "annualized_volatility", line), required(row, "source_reference", line));
            if (parsed.putIfAbsent(timestamp, input) != null) {
                throw new IllegalArgumentException("Duplicate option valuation timestamp at row " + line);
            }
        }
        if (parsed.size() != bars.size()) {
            throw new IllegalArgumentException("Option valuation CSV must contain exactly one row per option bar");
        }
        return new OptionValuationInputSchedule(parsed, table.sha256());
    }

    public static Instant closeTimestamp(Bar bar) {
        return switch (bar.timestampRole()) {
            case BAR_END -> bar.timestamp();
            case BAR_START -> {
                if (bar.interval().equals("ONE_DAY")) {
                    throw new IllegalArgumentException("Daily BAR_START option valuation requires an explicit close-time source");
                }
                yield bar.timestamp().plus(IntervalSupport.duration(bar.interval()));
            }
            case EVENT_TIME, UNKNOWN -> throw new IllegalArgumentException(
                    "Option valuation requires an explicit BAR_START or BAR_END timestamp role");
        };
    }

    private static String required(Map<String, String> row, String field, int line) {
        String value = row.get(field);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be non-empty and trimmed at row " + line);
        }
        return value;
    }

    private static double decimal(Map<String, String> row, String field, int line) {
        try {
            double value = Double.parseDouble(required(row, field, line));
            if (!Double.isFinite(value)) throw new NumberFormatException("not finite");
            return value;
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(field + " must be a finite number at row " + line, failure);
        }
    }

    private static double blankDecimal(Map<String, String> row, String field, int line) {
        String value = row.get(field);
        if (value != null && !value.isBlank()) {
            throw new IllegalArgumentException(field + " must be blank for BLACK76_FUTURE at row " + line);
        }
        return 0.0;
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be non-empty and trimmed");
        }
        return value;
    }

    private static void finite(double value, String field) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(field + " must be finite");
    }

    private static void finitePositive(double value, String field) {
        finite(value, field);
        if (value <= 0.0) throw new IllegalArgumentException(field + " must be positive");
    }
}
