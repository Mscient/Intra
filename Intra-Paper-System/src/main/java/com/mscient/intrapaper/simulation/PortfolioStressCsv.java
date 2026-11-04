package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.reference.OptionRight;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Strict CSV adapter for offline caller-supplied portfolio stress legs. */
public final class PortfolioStressCsv {
    private static final List<String> HEADERS = List.of(
            "leg_type", "instrument_id", "model", "right", "underlying_price", "strike",
            "time_to_expiry_years", "risk_free_rate", "dividend_yield", "annualized_volatility",
            "signed_contracts", "units_per_contract", "option_exit_haircut_per_unit",
            "signed_units", "future_exit_haircut_percent");

    public record Result(PortfolioStressAnalyzer.Report report, String positionsSha256) { }

    private PortfolioStressCsv() { }

    public static Result analyze(Path path, double underlyingShockPercent,
            double volatilityShockPoints) throws IOException {
        CsvTable table = CsvTable.read(path);
        if (!table.headers().equals(HEADERS)) {
            throw new IllegalArgumentException("Portfolio stress CSV headers must exactly match: " + HEADERS);
        }
        List<PortfolioStressAnalyzer.OptionLeg> options = new ArrayList<>();
        List<PortfolioStressAnalyzer.FutureLeg> futures = new ArrayList<>();
        for (int index = 0; index < table.rows().size(); index++) {
            Map<String, String> row = table.rows().get(index);
            int line = index + 2;
            String type = required(row, "leg_type", line);
            String id = required(row, "instrument_id", line);
            if (type.equals("OPTION")) {
                requireBlank(row, List.of("signed_units", "future_exit_haircut_percent"), line);
                PortfolioStressAnalyzer.OptionModel model = PortfolioStressAnalyzer.OptionModel.valueOf(
                        required(row, "model", line));
                double dividendYield = model == PortfolioStressAnalyzer.OptionModel.BSM_SPOT
                        ? decimal(row, "dividend_yield", line) : optionalDecimal(row, "dividend_yield", 0.0, line);
                options.add(new PortfolioStressAnalyzer.OptionLeg(id, model,
                        OptionRight.valueOf(required(row, "right", line)), decimal(row, "underlying_price", line),
                        decimal(row, "strike", line), decimal(row, "time_to_expiry_years", line),
                        decimal(row, "risk_free_rate", line), dividendYield,
                        decimal(row, "annualized_volatility", line), integer(row, "signed_contracts", line),
                        integer(row, "units_per_contract", line), decimal(row, "option_exit_haircut_per_unit", line)));
            } else if (type.equals("FUTURE")) {
                requireBlank(row, List.of("model", "right", "strike", "time_to_expiry_years", "risk_free_rate",
                        "dividend_yield", "annualized_volatility", "signed_contracts", "units_per_contract",
                        "option_exit_haircut_per_unit"), line);
                futures.add(new PortfolioStressAnalyzer.FutureLeg(id, decimal(row, "underlying_price", line),
                        integer(row, "signed_units", line), decimal(row, "future_exit_haircut_percent", line)));
            } else {
                throw new IllegalArgumentException("leg_type must be OPTION or FUTURE at row " + line);
            }
        }
        if (table.rows().isEmpty()) throw new IllegalArgumentException("Portfolio stress CSV has no position legs");
        return new Result(PortfolioStressAnalyzer.analyze(options, futures,
                underlyingShockPercent, volatilityShockPoints), table.sha256());
    }

    private static String required(Map<String, String> row, String field, int line) {
        String value = row.get(field);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be non-empty and trimmed at row " + line);
        }
        return value;
    }

    private static double decimal(Map<String, String> row, String field, int line) {
        try { return Double.parseDouble(required(row, field, line)); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException(field + " must be numeric at row " + line, failure); }
    }

    private static double optionalDecimal(Map<String, String> row, String field, double fallback, int line) {
        String value = row.get(field);
        if (value == null || value.isBlank()) return fallback;
        return decimal(row, field, line);
    }

    private static long integer(Map<String, String> row, String field, int line) {
        try { return Long.parseLong(required(row, field, line)); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException(field + " must be an integer at row " + line, failure); }
    }

    private static void requireBlank(Map<String, String> row, List<String> fields, int line) {
        for (String field : fields) {
            String value = row.get(field);
            if (value != null && !value.isBlank()) {
                throw new IllegalArgumentException(field + " must be blank for this leg type at row " + line);
            }
        }
    }
}
