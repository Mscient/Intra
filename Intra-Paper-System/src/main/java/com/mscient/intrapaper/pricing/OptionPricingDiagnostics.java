package com.mscient.intrapaper.pricing;

import com.mscient.intrapaper.reference.OptionRight;

import java.util.List;
import java.util.Objects;

/** Numerical consistency diagnostics; these are not an independent pricing oracle. */
public final class OptionPricingDiagnostics {
    public record Check(String name, double observed, double expected, double absoluteError, boolean passed) { }
    public record Report(String model, List<Check> checks, boolean passed,
            String scope) {
        public Report { checks = List.copyOf(checks); }
    }

    private static final double PRICE_ABS_TOLERANCE = 2e-5;
    private static final double GREEK_REL_TOLERANCE = 3e-4;
    private static final double DELTA_SPOT = 0.01;
    private static final double VOL_BUMP = 1e-4;
    private static final double RATE_BUMP = 1e-4;
    private static final double TIME_BUMP = 1.0 / 36500.0;

    private OptionPricingDiagnostics() { }

    public static Report black76(double futures, double strike, double years,
            double rate, double volatility) {
        requireInputs(futures, strike, years, rate, volatility);
        if (years <= 2 * TIME_BUMP || volatility <= VOL_BUMP) {
            throw new IllegalArgumentException("Black-76 diagnostics require time > 2/36500 years and volatility > 0.0001");
        }
        Black76OptionPricer.Inputs callInputs = new Black76OptionPricer.Inputs(
                futures, strike, years, rate, volatility, OptionRight.CALL);
        Black76OptionPricer.Inputs putInputs = new Black76OptionPricer.Inputs(
                futures, strike, years, rate, volatility, OptionRight.PUT);
        var call = Black76OptionPricer.value(callInputs);
        var put = Black76OptionPricer.value(putInputs);
        double discount = Math.exp(-rate * years);
        double lowerCall = Math.max(discount * (futures - strike), 0.0);
        double upperCall = discount * futures;
        double lowerPut = Math.max(discount * (strike - futures), 0.0);
        double upperPut = discount * strike;
        double fdDelta = (black76Price(futures + DELTA_SPOT, strike, years, rate, volatility, OptionRight.CALL)
                - black76Price(futures - DELTA_SPOT, strike, years, rate, volatility, OptionRight.CALL))
                / (2 * DELTA_SPOT);
        double fdGamma = (black76Price(futures + DELTA_SPOT, strike, years, rate, volatility, OptionRight.CALL)
                - 2 * call.theoreticalValue()
                + black76Price(futures - DELTA_SPOT, strike, years, rate, volatility, OptionRight.CALL))
                / (DELTA_SPOT * DELTA_SPOT);
        double fdVega = (black76Price(futures, strike, years, rate, volatility + VOL_BUMP, OptionRight.CALL)
                - black76Price(futures, strike, years, rate, volatility - VOL_BUMP, OptionRight.CALL)) / 2.0;
        double fdRho = (black76Price(futures, strike, years, rate + RATE_BUMP, volatility, OptionRight.CALL)
                - black76Price(futures, strike, years, rate - RATE_BUMP, volatility, OptionRight.CALL)) / 2.0;
        double fdTheta = -(black76Price(futures, strike, years + TIME_BUMP, rate, volatility, OptionRight.CALL)
                - black76Price(futures, strike, years - TIME_BUMP, rate, volatility, OptionRight.CALL))
                / (2 * TIME_BUMP * 365.0);
        List<Check> checks = List.of(
                priceCheck("call_lower_bound", call.theoreticalValue(), lowerCall, PRICE_ABS_TOLERANCE),
                priceCheck("call_upper_bound", call.theoreticalValue(), upperCall, PRICE_ABS_TOLERANCE),
                priceCheck("put_lower_bound", put.theoreticalValue(), lowerPut, PRICE_ABS_TOLERANCE),
                priceCheck("put_upper_bound", put.theoreticalValue(), upperPut, PRICE_ABS_TOLERANCE),
                priceCheck("put_call_parity", call.theoreticalValue() - put.theoreticalValue(),
                        discount * (futures - strike), PRICE_ABS_TOLERANCE),
                greekCheck("delta_finite_difference", call.deltaPerFuturesUnit(), fdDelta),
                greekCheck("gamma_finite_difference", call.gamma(), fdGamma),
                greekCheck("vega_per_volatility_point_finite_difference", call.vegaPerVolatilityPoint(), fdVega * 0.01),
                greekCheck("rho_per_rate_point_finite_difference", call.rhoPerRatePoint(), fdRho * 0.01),
                greekCheck("theta_per_calendar_day_finite_difference", call.thetaPerCalendarDay(), fdTheta));
        return report("BLACK76", checks);
    }

    public static Report bsm(double spot, double strike, double years,
            double rate, double dividendYield, double volatility) {
        requireInputs(spot, strike, years, rate, volatility);
        if (!Double.isFinite(dividendYield)) throw new IllegalArgumentException("dividend yield must be finite");
        if (years <= 2 * TIME_BUMP || volatility <= VOL_BUMP) {
            throw new IllegalArgumentException("BSM diagnostics require time > 2/36500 years and volatility > 0.0001");
        }
        var call = EuropeanOptionPricer.value(new EuropeanOptionPricer.Inputs(
                spot, strike, years, rate, dividendYield, volatility, OptionRight.CALL));
        var put = EuropeanOptionPricer.value(new EuropeanOptionPricer.Inputs(
                spot, strike, years, rate, dividendYield, volatility, OptionRight.PUT));
        double discountQ = Math.exp(-dividendYield * years);
        double discountR = Math.exp(-rate * years);
        double callLower = Math.max(spot * discountQ - strike * discountR, 0.0);
        double callUpper = spot * discountQ;
        double putLower = Math.max(strike * discountR - spot * discountQ, 0.0);
        double putUpper = strike * discountR;
        double fdDelta = (bsmPrice(spot + DELTA_SPOT, strike, years, rate, dividendYield, volatility, OptionRight.CALL)
                - bsmPrice(spot - DELTA_SPOT, strike, years, rate, dividendYield, volatility, OptionRight.CALL))
                / (2 * DELTA_SPOT);
        double fdGamma = (bsmPrice(spot + DELTA_SPOT, strike, years, rate, dividendYield, volatility, OptionRight.CALL)
                - 2 * call.theoreticalValue()
                + bsmPrice(spot - DELTA_SPOT, strike, years, rate, dividendYield, volatility, OptionRight.CALL))
                / (DELTA_SPOT * DELTA_SPOT);
        double fdVega = (bsmPrice(spot, strike, years, rate, dividendYield, volatility + VOL_BUMP, OptionRight.CALL)
                - bsmPrice(spot, strike, years, rate, dividendYield, volatility - VOL_BUMP, OptionRight.CALL)) / 2.0;
        double fdRho = (bsmPrice(spot, strike, years, rate + RATE_BUMP, dividendYield, volatility, OptionRight.CALL)
                - bsmPrice(spot, strike, years, rate - RATE_BUMP, dividendYield, volatility, OptionRight.CALL)) / 2.0;
        double fdTheta = -(bsmPrice(spot, strike, years + TIME_BUMP, rate, dividendYield, volatility, OptionRight.CALL)
                - bsmPrice(spot, strike, years - TIME_BUMP, rate, dividendYield, volatility, OptionRight.CALL))
                / (2 * TIME_BUMP * 365.0);
        List<Check> checks = List.of(
                priceCheck("call_lower_bound", call.theoreticalValue(), callLower, PRICE_ABS_TOLERANCE),
                priceCheck("call_upper_bound", call.theoreticalValue(), callUpper, PRICE_ABS_TOLERANCE),
                priceCheck("put_lower_bound", put.theoreticalValue(), putLower, PRICE_ABS_TOLERANCE),
                priceCheck("put_upper_bound", put.theoreticalValue(), putUpper, PRICE_ABS_TOLERANCE),
                priceCheck("put_call_parity", call.theoreticalValue() - put.theoreticalValue(),
                        spot * discountQ - strike * discountR, PRICE_ABS_TOLERANCE),
                greekCheck("delta_finite_difference", call.delta(), fdDelta),
                greekCheck("gamma_finite_difference", call.gamma(), fdGamma),
                greekCheck("vega_per_volatility_point_finite_difference", call.vegaPerVolatilityPoint(), fdVega * 0.01),
                greekCheck("rho_per_rate_point_finite_difference", call.rhoPerRatePoint(), fdRho * 0.01),
                greekCheck("theta_per_calendar_day_finite_difference", call.thetaPerCalendarDay(), fdTheta));
        return report("BSM", checks);
    }

    private static double black76Price(double futures, double strike, double years, double rate,
            double volatility, OptionRight right) {
        return Black76OptionPricer.value(new Black76OptionPricer.Inputs(
                futures, strike, years, rate, volatility, right)).theoreticalValue();
    }

    private static double bsmPrice(double spot, double strike, double years, double rate,
            double dividendYield, double volatility, OptionRight right) {
        return EuropeanOptionPricer.value(new EuropeanOptionPricer.Inputs(
                spot, strike, years, rate, dividendYield, volatility, right)).theoreticalValue();
    }

    private static Check priceCheck(String name, double observed, double expected, double tolerance) {
        double error = Math.abs(observed - expected);
        return new Check(name, observed, expected, error, error <= tolerance);
    }

    private static Check greekCheck(String name, double observed, double expected) {
        double error = Math.abs(observed - expected);
        double tolerance = Math.max(2e-6, Math.max(Math.abs(observed), Math.abs(expected)) * GREEK_REL_TOLERANCE);
        return new Check(name, observed, expected, error, error <= tolerance);
    }

    private static Report report(String model, List<Check> checks) {
        return new Report(model, checks, checks.stream().allMatch(Check::passed),
                "NUMERICAL_CONSISTENCY_ONLY_NOT_INDEPENDENT_MARKET_VALIDATION");
    }

    private static void requireInputs(double underlying, double strike, double years, double rate, double volatility) {
        Objects.requireNonNull(Double.valueOf(underlying));
        if (!Double.isFinite(underlying) || !Double.isFinite(strike) || !Double.isFinite(years)
                || !Double.isFinite(rate) || !Double.isFinite(volatility)
                || underlying <= 0 || strike <= 0 || years <= 0 || volatility <= 0) {
            throw new IllegalArgumentException("underlying, strike, time and volatility must be finite and positive; rate must be finite");
        }
    }
}
