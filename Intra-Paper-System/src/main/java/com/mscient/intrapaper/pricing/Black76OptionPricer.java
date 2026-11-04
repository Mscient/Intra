package com.mscient.intrapaper.pricing;

import com.mscient.intrapaper.reference.OptionRight;

import java.util.Objects;

/**
 * Caller-input Black-76 valuation and Greeks for European options on futures.
 * Values and Greeks are per underlying futures unit. The futures price is treated as
 * independent of the continuously compounded discount rate supplied by the caller.
 */
public final class Black76OptionPricer {
    public record Inputs(double futuresPrice, double strikePrice, double timeToExpiryYears,
            double continuouslyCompoundedRiskFreeRate, double annualizedVolatility, OptionRight right) {
        public Inputs {
            requireFinite(futuresPrice, "futuresPrice");
            requireFinite(strikePrice, "strikePrice");
            requireFinite(timeToExpiryYears, "timeToExpiryYears");
            requireFinite(continuouslyCompoundedRiskFreeRate, "continuouslyCompoundedRiskFreeRate");
            requireFinite(annualizedVolatility, "annualizedVolatility");
            Objects.requireNonNull(right, "right");
            if (futuresPrice <= 0) throw new IllegalArgumentException("futuresPrice must be positive");
            if (strikePrice <= 0) throw new IllegalArgumentException("strikePrice must be positive");
            if (timeToExpiryYears < 0) throw new IllegalArgumentException("timeToExpiryYears must be non-negative");
            if (annualizedVolatility <= 0) throw new IllegalArgumentException("annualizedVolatility must be positive");
        }
    }

    /** Vega/rho are per one percentage point; theta is per 365-day calendar day. */
    public record ValueGreeks(double theoreticalValue, double deltaPerFuturesUnit, double gamma,
            double vegaPerVolatilityPoint, double thetaPerCalendarDay, double rhoPerRatePoint) {
        public ValueGreeks {
            requireFinite(theoreticalValue, "theoreticalValue");
            requireFinite(deltaPerFuturesUnit, "deltaPerFuturesUnit");
            requireFinite(gamma, "gamma");
            requireFinite(vegaPerVolatilityPoint, "vegaPerVolatilityPoint");
            requireFinite(thetaPerCalendarDay, "thetaPerCalendarDay");
            requireFinite(rhoPerRatePoint, "rhoPerRatePoint");
        }
    }

    private static final double DAYS_PER_YEAR = 365.0;

    private Black76OptionPricer() { }

    public static ValueGreeks value(Inputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        double futures = inputs.futuresPrice();
        double strike = inputs.strikePrice();
        double time = inputs.timeToExpiryYears();
        boolean call = inputs.right() == OptionRight.CALL;
        if (time == 0.0) {
            double value = call ? Math.max(futures - strike, 0.0) : Math.max(strike - futures, 0.0);
            double delta = call
                    ? (futures > strike ? 1.0 : futures < strike ? 0.0 : 0.5)
                    : (futures < strike ? -1.0 : futures > strike ? 0.0 : -0.5);
            // At expiry gamma is singular at the strike; zero is a reporting convention, not the limit.
            return new ValueGreeks(value, delta, 0.0, 0.0, 0.0, 0.0);
        }

        double volatility = inputs.annualizedVolatility();
        double sqrtTime = Math.sqrt(time);
        double sigmaSqrtTime = volatility * sqrtTime;
        double d1 = (Math.log(futures) - Math.log(strike) + 0.5 * volatility * volatility * time)
                / sigmaSqrtTime;
        double d2 = d1 - sigmaSqrtTime;
        double discount = Math.exp(-inputs.continuouslyCompoundedRiskFreeRate() * time);
        double density = normalDensity(d1);
        double nD1 = normalCdf(d1);
        double nD2 = normalCdf(d2);
        double undiscountedValue;
        double delta;
        if (call) {
            undiscountedValue = futures * nD1 - strike * nD2;
            delta = discount * nD1;
        } else {
            undiscountedValue = strike * normalCdf(-d2) - futures * normalCdf(-d1);
            delta = discount * (nD1 - 1.0);
        }
        double price = discount * undiscountedValue;
        double gamma = discount * density / (futures * sigmaSqrtTime);
        double vegaPerVolatilityPoint = 0.01 * discount * futures * density * sqrtTime;
        double thetaPerCalendarDay = (inputs.continuouslyCompoundedRiskFreeRate() * price
                - discount * futures * density * volatility / (2.0 * sqrtTime)) / DAYS_PER_YEAR;
        // The futures price is held fixed as the discount rate changes, so dV/dr = -T * V.
        double rhoPerRatePoint = -0.01 * time * price;
        if (!Double.isFinite(price) || !Double.isFinite(delta) || !Double.isFinite(gamma)
                || !Double.isFinite(vegaPerVolatilityPoint) || !Double.isFinite(thetaPerCalendarDay)
                || !Double.isFinite(rhoPerRatePoint)) {
            throw new ArithmeticException("Black-76 result is outside the supported numeric range");
        }
        return new ValueGreeks(Math.max(0.0, price), delta, gamma, vegaPerVolatilityPoint,
                thetaPerCalendarDay, rhoPerRatePoint);
    }

    private static double normalDensity(double value) {
        return NormalDistribution.density(value);
    }

    private static double normalCdf(double value) {
        return NormalDistribution.cdf(value);
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
}
