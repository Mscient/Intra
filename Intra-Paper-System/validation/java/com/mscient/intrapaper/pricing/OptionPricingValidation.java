package com.mscient.intrapaper.pricing;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.reference.OptionRight;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic, offline validation runner for checked-in theoretical option-pricer fixtures. */
public final class OptionPricingValidation {
    public record ModelSummary(String model, int cases, double maxPriceAbsError,
            double maxDeltaAbsError, double maxGammaAbsError, double maxVegaRawAbsError,
            double maxThetaYearAbsError, double maxRhoRawAbsError) { }

    public record Report(List<ModelSummary> models, int referenceCases,
            int boundaryChecks, int noArbitrageChecks) {
        public Report { models = List.copyOf(models); }
    }

    private static final List<String> HEADERS = List.of(
            "case_id", "model", "right", "underlying", "strike", "rate", "dividend_yield",
            "volatility", "time_years", "time_text", "provenance", "price_per_underlying_unit",
            "delta_per_underlying_price_unit", "gamma_per_underlying_price_unit_squared",
            "vega_per_1_00_volatility", "theta_per_year", "rho_per_1_00_rate",
            "price_abs_tolerance", "delta_abs_tolerance", "gamma_abs_tolerance",
            "vega_abs_tolerance", "theta_abs_tolerance", "rho_abs_tolerance");
    private static final List<Double> UNDERLYING_GRID = List.of(60.0, 80.0, 100.0, 120.0, 160.0);
    private static final List<Double> STRIKE_GRID = List.of(80.0, 100.0, 120.0);
    private static final List<Double> VOL_GRID = List.of(0.05, 0.2, 0.6);
    private static final List<Double> TIME_GRID = List.of(0.01, 0.25, 1.0);
    private static final List<Double> RATE_GRID = List.of(-0.01, 0.0, 0.06);
    private static final List<Double> YIELD_GRID = List.of(0.0, 0.03);

    private OptionPricingValidation() { }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: OptionPricingValidation <r11-reference-cases.csv>");
            System.exit(2);
        }
        try {
            Report report = validate(Path.of(args[0]));
            for (ModelSummary model : report.models()) {
                System.out.printf("%s R11: cases=%d; max_abs_error price=%.3e delta=%.3e gamma=%.3e "
                                + "vega_raw=%.3e theta_year=%.3e rho_raw=%.3e%n",
                        model.model(), model.cases(), model.maxPriceAbsError(), model.maxDeltaAbsError(),
                        model.maxGammaAbsError(), model.maxVegaRawAbsError(), model.maxThetaYearAbsError(),
                        model.maxRhoRawAbsError());
            }
            System.out.printf("JAVA PRICER VALIDATION PASSED: R11=%d; boundary=%d; no_arbitrage=%d; "
                            + "QuantLib cross-check is separate; validation harness is outside the runtime JAR%n",
                    report.referenceCases(), report.boundaryChecks(), report.noArbitrageChecks());
        } catch (Exception error) {
            System.err.println("Option-pricer validation failed: " + error.getMessage());
            System.exit(1);
        }
    }

    public static Report validate(Path fixtureCsv) throws IOException {
        CsvTable table = CsvTable.read(fixtureCsv);
        if (!table.headers().equals(HEADERS)) {
            throw new IOException("R11 fixture CSV headers do not match the required schema");
        }
        Set<String> expectedIds = expectedCaseIds();
        Set<String> seenIds = new HashSet<>();
        List<Case> cases = new ArrayList<>(22);
        for (int index = 0; index < table.rows().size(); index++) {
            Map<String, String> row = table.rows().get(index);
            int rowNumber = index + 2;
            String id = required(row, "case_id", rowNumber);
            if (!seenIds.add(id)) throw new IOException("Duplicate fixture case_id at row " + rowNumber + ": " + id);
            if (!expectedIds.contains(id)) throw new IOException("Unexpected fixture case_id at row " + rowNumber + ": " + id);
            cases.add(parseCase(row, rowNumber));
        }
        if (!seenIds.equals(expectedIds)) {
            Set<String> missing = new TreeSet<>(expectedIds);
            missing.removeAll(seenIds);
            throw new IOException("Missing R11 fixture rows: " + missing);
        }

        MutableSummary bsm = new MutableSummary("BSM");
        MutableSummary black76 = new MutableSummary("B76");
        for (Case testCase : cases) {
            Value actual = price(testCase);
            MutableSummary summary = testCase.model().equals("BSM") ? bsm : black76;
            summary.add(testCase, actual);
        }
        int boundaries = validateBoundaryCases();
        int noArbitrage = validateNoArbitrageSweeps();
        return new Report(List.of(bsm.freeze(), black76.freeze()), cases.size(), boundaries, noArbitrage);
    }

    private static Case parseCase(Map<String, String> row, int rowNumber) throws IOException {
        String id = required(row, "case_id", rowNumber);
        String model = required(row, "model", rowNumber);
        if (!model.equals("BSM") && !model.equals("B76")) throw new IOException("Unknown model at row " + rowNumber + ": " + model);
        String rightText = required(row, "right", rowNumber);
        OptionRight right = switch (rightText) {
            case "C" -> OptionRight.CALL;
            case "P" -> OptionRight.PUT;
            default -> throw new IOException("right must be C or P at row " + rowNumber);
        };
        String expectedModel = id.startsWith("B") ? "BSM" : "B76";
        OptionRight expectedRight = id.endsWith("C") ? OptionRight.CALL : OptionRight.PUT;
        if (!model.equals(expectedModel) || right != expectedRight) {
            throw new IOException("Fixture identity does not match its case_id at row " + rowNumber + ": " + id);
        }
        required(row, "time_text", rowNumber);
        required(row, "provenance", rowNumber);
        double underlying = finite(row, "underlying", rowNumber);
        double strike = finite(row, "strike", rowNumber);
        double rate = finite(row, "rate", rowNumber);
        double dividend = optionalFinite(row, "dividend_yield", rowNumber, 0.0);
        if (model.equals("BSM") && row.get("dividend_yield").isEmpty()) {
            throw new IOException("BSM dividend_yield must be present at row " + rowNumber);
        }
        if (model.equals("B76") && !row.get("dividend_yield").isEmpty()) {
            throw new IOException("Black-76 dividend_yield must be blank at row " + rowNumber);
        }
        double volatility = finite(row, "volatility", rowNumber);
        double time = finite(row, "time_years", rowNumber);
        if (underlying <= 0.0 || strike <= 0.0 || volatility <= 0.0 || time <= 0.0) {
            throw new IOException("Fixture inputs outside supported R11 range at row " + rowNumber);
        }
        double[] expected = {
                finite(row, "price_per_underlying_unit", rowNumber),
                finite(row, "delta_per_underlying_price_unit", rowNumber),
                finite(row, "gamma_per_underlying_price_unit_squared", rowNumber),
                finite(row, "vega_per_1_00_volatility", rowNumber),
                finite(row, "theta_per_year", rowNumber),
                finite(row, "rho_per_1_00_rate", rowNumber),
        };
        double[] tolerances = {
                tolerance(row, "price_abs_tolerance", rowNumber),
                tolerance(row, "delta_abs_tolerance", rowNumber),
                tolerance(row, "gamma_abs_tolerance", rowNumber),
                tolerance(row, "vega_abs_tolerance", rowNumber),
                tolerance(row, "theta_abs_tolerance", rowNumber),
                tolerance(row, "rho_abs_tolerance", rowNumber),
        };
        return new Case(id, model, right,
                underlying, strike, rate, dividend, volatility, time, expected, tolerances);
    }

    private static Value price(Case testCase) {
        if (testCase.model().equals("BSM")) {
            var value = EuropeanOptionPricer.value(new EuropeanOptionPricer.Inputs(
                    testCase.underlying(), testCase.strike(), testCase.time(), testCase.rate(),
                    testCase.dividendYield(), testCase.volatility(), testCase.right()));
            return new Value(value.theoreticalValue(), value.delta(), value.gamma(),
                    value.vegaPerVolatilityPoint() * 100.0, value.thetaPerCalendarDay() * 365.0,
                    value.rhoPerRatePoint() * 100.0);
        }
        var value = Black76OptionPricer.value(new Black76OptionPricer.Inputs(
                testCase.underlying(), testCase.strike(), testCase.time(), testCase.rate(),
                testCase.volatility(), testCase.right()));
        return new Value(value.theoreticalValue(), value.deltaPerFuturesUnit(), value.gamma(),
                value.vegaPerVolatilityPoint() * 100.0, value.thetaPerCalendarDay() * 365.0,
                value.rhoPerRatePoint() * 100.0);
    }

    private static int validateBoundaryCases() throws IOException {
        int checks = 0;
        checks += checkClose("BSM expiry ITM call", new EuropeanOptionPricer.Inputs(
                110, 100, 0, 0.05, 0.02, 0.2, OptionRight.CALL), 10.0, 1e-14);
        checks += checkClose("BSM expiry ITM put", new EuropeanOptionPricer.Inputs(
                90, 100, 0, 0.05, 0.02, 0.2, OptionRight.PUT), 10.0, 1e-14);
        checks += checkClose("B76 expiry OTM call", new Black76OptionPricer.Inputs(
                90, 100, 0, 0.05, 0.2, OptionRight.CALL), 0.0, 1e-14);
        checks += checkClose("B76 expiry ITM put", new Black76OptionPricer.Inputs(
                90, 100, 0, 0.05, 0.2, OptionRight.PUT), 10.0, 1e-14);

        checks += checkNear("BSM tiny-time ATM", new EuropeanOptionPricer.Inputs(
                100, 100, 1e-12, 0, 0, 0.2, OptionRight.CALL),
                100.0 * 0.2 * Math.sqrt(1e-12) / Math.sqrt(2.0 * Math.PI), 2e-6);
        checks += checkNear("B76 tiny-time ATM", new Black76OptionPricer.Inputs(
                100, 100, 1e-12, 0, 0.2, OptionRight.CALL),
                100.0 * 0.2 * Math.sqrt(1e-12) / Math.sqrt(2.0 * Math.PI), 2e-6);
        checks += checkNear("BSM low-vol ATM", new EuropeanOptionPricer.Inputs(
                100, 100, 0.5, 0, 0, 1e-8, OptionRight.CALL),
                100.0 * 1e-8 * Math.sqrt(0.5) / Math.sqrt(2.0 * Math.PI), 2e-8);
        checks += checkClose("BSM low-vol ITM", new EuropeanOptionPricer.Inputs(
                110, 100, 0.5, 0, 0, 1e-8, OptionRight.CALL), 10.0, 1e-12);
        double bsmLowVolOtm = EuropeanOptionPricer.value(new EuropeanOptionPricer.Inputs(
                90, 100, 0.5, 0, 0, 0.01, OptionRight.CALL)).theoreticalValue();
        if (bsmLowVolOtm < 0 || bsmLowVolOtm > 1e-48) {
            throw new IOException("BSM low-vol OTM tail exceeded the expected near-zero envelope: " + bsmLowVolOtm);
        }
        checks++;
        checks += checkNear("B76 low-vol ATM", new Black76OptionPricer.Inputs(
                100, 100, 0.5, 0.05, 1e-8, OptionRight.CALL),
                Math.exp(-0.05 * 0.5) * 100.0 * 1e-8 * Math.sqrt(0.5) / Math.sqrt(2.0 * Math.PI), 2e-8);
        checks += checkClose("B76 low-vol ITM", new Black76OptionPricer.Inputs(
                110, 100, 0.5, 0.05, 1e-8, OptionRight.CALL),
                Math.exp(-0.05 * 0.5) * 10.0, 1e-12);
        double lowVolOtm = price(new Case("lowvol_otm", "B76", OptionRight.CALL,
                90, 100, 0.05, 0, 0.01, 0.5, new double[6], new double[6])).price();
        if (lowVolOtm < 0 || lowVolOtm > 1e-48) {
            throw new IOException("B76 low-vol OTM tail exceeded the expected near-zero envelope: " + lowVolOtm);
        }
        checks++;

        double deepOtm = price(new Case("deep_otm", "BSM", OptionRight.CALL,
                100, 1000, 0.05, 0, 0.2, 0.5, new double[6], new double[6])).price();
        if (deepOtm < 0 || deepOtm > 1e-55) throw new IOException("BSM deep-OTM call is outside expected tail range: " + deepOtm);
        checks++;
        checks += checkClose("BSM deep ITM call", new EuropeanOptionPricer.Inputs(
                1000, 100, 0.5, 0.05, 0, 0.2, OptionRight.CALL), 902.469008797, 6e-10);

        checks += expectRejected("BSM zero-volatility policy", () -> new EuropeanOptionPricer.Inputs(
                110, 100, 0.5, 0.05, 0.02, 0, OptionRight.CALL));
        checks += expectRejected("B76 zero-volatility policy", () -> new Black76OptionPricer.Inputs(
                110, 100, 0.5, 0.05, 0, OptionRight.CALL));
        return checks;
    }

    private static int validateNoArbitrageSweeps() throws IOException {
        int checks = 0;
        for (double spot : UNDERLYING_GRID) {
            for (double strike : STRIKE_GRID) {
                for (double volatility : VOL_GRID) {
                    for (double time : TIME_GRID) {
                        for (double rate : RATE_GRID) {
                            for (double yield : YIELD_GRID) {
                                double call = bsm(spot, strike, time, rate, yield, volatility, OptionRight.CALL);
                                double put = bsm(spot, strike, time, rate, yield, volatility, OptionRight.PUT);
                                double discountYield = Math.exp(-yield * time);
                                double discountRate = Math.exp(-rate * time);
                                double callLower = Math.max(spot * discountYield - strike * discountRate, 0.0);
                                double putLower = Math.max(strike * discountRate - spot * discountYield, 0.0);
                                requireAtLeast("BSM call lower bound", call, callLower);
                                requireAtMost("BSM call upper bound", call, spot * discountYield);
                                requireAtLeast("BSM put lower bound", put, putLower);
                                requireAtMost("BSM put upper bound", put, strike * discountRate);
                                requireNear("BSM put-call parity", call - put,
                                        spot * discountYield - strike * discountRate);
                                checks += 5;
                                if (strike < 120.0) {
                                    double higherStrikeCall = bsm(spot, strike + 20.0, time, rate, yield,
                                            volatility, OptionRight.CALL);
                                    double spread = call - higherStrikeCall;
                                    requireAtLeast("BSM call-spread lower bound", spread, 0.0);
                                    requireAtMost("BSM call-spread upper bound", spread,
                                            20.0 * discountRate);
                                    checks++;
                                }
                            }
                        }
                    }
                }
            }
        }
        for (double futures : UNDERLYING_GRID) {
            for (double strike : STRIKE_GRID) {
                for (double volatility : VOL_GRID) {
                    for (double time : TIME_GRID) {
                        for (double rate : RATE_GRID) {
                            double discount = Math.exp(-rate * time);
                            double call = b76(futures, strike, time, rate, volatility, OptionRight.CALL);
                            double put = b76(futures, strike, time, rate, volatility, OptionRight.PUT);
                            requireAtLeast("B76 call lower bound", call, discount * Math.max(futures - strike, 0.0));
                            requireAtMost("B76 call upper bound", call, discount * futures);
                            requireNear("B76 put-call parity", call - put, discount * (futures - strike));
                            checks += 3;
                            double left = b76(futures, strike - 10.0, time, rate, volatility, OptionRight.CALL);
                            double middle = call;
                            double right = b76(futures, strike + 10.0, time, rate, volatility, OptionRight.CALL);
                            requireAtLeast("B76 butterfly convexity", left - 2.0 * middle + right, 0.0);
                        }
                    }
                }
            }
        }
        return checks;
    }

    private static double bsm(double spot, double strike, double time, double rate,
            double yield, double volatility, OptionRight right) {
        return EuropeanOptionPricer.value(new EuropeanOptionPricer.Inputs(
                spot, strike, time, rate, yield, volatility, right)).theoreticalValue();
    }

    private static double b76(double futures, double strike, double time,
            double rate, double volatility, OptionRight right) {
        return Black76OptionPricer.value(new Black76OptionPricer.Inputs(
                futures, strike, time, rate, volatility, right)).theoreticalValue();
    }

    private static int checkClose(String name, EuropeanOptionPricer.Inputs inputs,
            double expected, double tolerance) throws IOException {
        return checkClose(name, EuropeanOptionPricer.value(inputs).theoreticalValue(), expected, tolerance);
    }

    private static int checkClose(String name, Black76OptionPricer.Inputs inputs,
            double expected, double tolerance) throws IOException {
        return checkClose(name, Black76OptionPricer.value(inputs).theoreticalValue(), expected, tolerance);
    }

    private static int checkClose(String name, double observed, double expected, double tolerance) throws IOException {
        if (!Double.isFinite(observed) || Math.abs(observed - expected) > tolerance) {
            throw new IOException(name + " mismatch: observed=" + observed + ", expected=" + expected
                    + ", tolerance=" + tolerance);
        }
        return 1;
    }

    private static int checkNear(String name, EuropeanOptionPricer.Inputs inputs,
            double expected, double relativeTolerance) throws IOException {
        double observed = EuropeanOptionPricer.value(inputs).theoreticalValue();
        if (!Double.isFinite(observed) || Math.abs(observed - expected) > Math.abs(expected) * relativeTolerance) {
            throw new IOException(name + " mismatch: observed=" + observed + ", expected=" + expected);
        }
        return 1;
    }

    private static int checkNear(String name, Black76OptionPricer.Inputs inputs,
            double expected, double relativeTolerance) throws IOException {
        double observed = Black76OptionPricer.value(inputs).theoreticalValue();
        if (!Double.isFinite(observed) || Math.abs(observed - expected) > Math.abs(expected) * relativeTolerance) {
            throw new IOException(name + " mismatch: observed=" + observed + ", expected=" + expected);
        }
        return 1;
    }

    private static int expectRejected(String name, Runnable inputConstruction) throws IOException {
        try {
            inputConstruction.run();
        } catch (IllegalArgumentException expected) {
            return 1;
        }
        throw new IOException(name + " unexpectedly accepted zero volatility");
    }

    private static void requireAtLeast(String name, double value, double lowerBound) throws IOException {
        requireFinite(name, value);
        double tolerance = roundoffTolerance(value, lowerBound);
        if (value < lowerBound - tolerance) {
            throw new IOException(name + " violated: value=" + value + ", lower=" + lowerBound);
        }
    }

    private static void requireAtMost(String name, double value, double upperBound) throws IOException {
        requireFinite(name, value);
        double tolerance = roundoffTolerance(value, upperBound);
        if (value > upperBound + tolerance) {
            throw new IOException(name + " violated: value=" + value + ", upper=" + upperBound);
        }
    }

    private static void requireNear(String name, double actual, double expected) throws IOException {
        requireFinite(name, actual);
        double tolerance = roundoffTolerance(actual, expected);
        if (Math.abs(actual - expected) > tolerance) {
            throw new IOException(name + " violated: actual=" + actual + ", expected=" + expected
                    + ", tolerance=" + tolerance);
        }
    }

    private static void requireFinite(String name, double value) throws IOException {
        if (!Double.isFinite(value)) throw new IOException(name + " produced a non-finite result");
    }

    private static double roundoffTolerance(double first, double second) {
        return 64.0 * Math.ulp(Math.max(1.0, Math.max(Math.abs(first), Math.abs(second))));
    }

    private static String required(Map<String, String> row, String name, int rowNumber) throws IOException {
        String value = row.get(name);
        if (value == null || value.isEmpty() || !value.equals(value.strip())) {
            throw new IOException("Missing or untrimmed " + name + " at row " + rowNumber);
        }
        return value;
    }

    private static double finite(Map<String, String> row, String name, int rowNumber) throws IOException {
        String text = required(row, name, rowNumber);
        try {
            double value = Double.parseDouble(text);
            if (!Double.isFinite(value)) throw new IOException(name + " must be finite at row " + rowNumber);
            return value;
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid numeric " + name + " at row " + rowNumber, invalid);
        }
    }

    private static double optionalFinite(Map<String, String> row, String name,
            int rowNumber, double defaultValue) throws IOException {
        String text = row.get(name);
        if (text == null || text.isEmpty()) return defaultValue;
        return finite(row, name, rowNumber);
    }

    private static double tolerance(Map<String, String> row, String name, int rowNumber) throws IOException {
        double value = finite(row, name, rowNumber);
        if (value <= 0.0) throw new IOException(name + " must be positive at row " + rowNumber);
        return value;
    }

    private static Set<String> expectedCaseIds() {
        Set<String> ids = new HashSet<>();
        for (String base : List.of("B01", "B02", "B03", "B04", "B05", "B06",
                "F01", "F02", "F03", "F04", "F05")) {
            ids.add(base + "C");
            ids.add(base + "P");
        }
        return Set.copyOf(ids);
    }

    private record Case(String id, String model, OptionRight right, double underlying,
            double strike, double rate, double dividendYield, double volatility, double time,
            double[] expected, double[] tolerances) { }

    private record Value(double price, double delta, double gamma,
            double vegaRaw, double thetaYear, double rhoRaw) { }

    private static final class MutableSummary {
        private final String model;
        private int count;
        private final double[] maxAbs = new double[6];

        private MutableSummary(String model) { this.model = model; }

        private void add(Case testCase, Value actual) throws IOException {
            double[] observed = {actual.price(), actual.delta(), actual.gamma(),
                    actual.vegaRaw(), actual.thetaYear(), actual.rhoRaw()};
            String[] metricNames = {"price", "delta", "gamma", "vega", "theta", "rho"};
            for (int metric = 0; metric < observed.length; metric++) {
                if (!Double.isFinite(observed[metric])) {
                    throw new IOException("Non-finite " + metricNames[metric] + " for " + testCase.id());
                }
                double error = Math.abs(observed[metric] - testCase.expected()[metric]);
                maxAbs[metric] = Math.max(maxAbs[metric], error);
                if (error > testCase.tolerances()[metric]) {
                    throw new IOException(testCase.id() + " " + metricNames[metric] + " mismatch: observed="
                            + observed[metric] + ", expected=" + testCase.expected()[metric]
                            + ", abs_error=" + error + ", tolerance=" + testCase.tolerances()[metric]);
                }
            }
            count++;
        }

        private ModelSummary freeze() {
            return new ModelSummary(model, count, maxAbs[0], maxAbs[1], maxAbs[2],
                    maxAbs[3], maxAbs[4], maxAbs[5]);
        }
    }
}
