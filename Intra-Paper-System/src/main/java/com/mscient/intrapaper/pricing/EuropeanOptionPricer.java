package com.mscient.intrapaper.pricing;

import com.mscient.intrapaper.reference.OptionRight;

import java.util.Objects;

/**
 * Caller-input Black-Scholes-Merton value and first/second-order Greeks for European options.
 * Values are per underlying unit. Rates and dividend yield are continuously compounded annual decimals;
 * volatility is annualized standard deviation and time is caller-supplied years (typically ACT/365).
 */
public final class EuropeanOptionPricer {
    public record Inputs(double underlyingPrice, double strikePrice, double timeToExpiryYears,
            double continuouslyCompoundedRiskFreeRate, double continuousDividendYield,
            double annualizedVolatility, OptionRight right) {
        public Inputs {
            requireFinite(underlyingPrice, "underlyingPrice");
            requireFinite(strikePrice, "strikePrice");
            requireFinite(timeToExpiryYears, "timeToExpiryYears");
            requireFinite(continuouslyCompoundedRiskFreeRate, "continuouslyCompoundedRiskFreeRate");
            requireFinite(continuousDividendYield, "continuousDividendYield");
            requireFinite(annualizedVolatility, "annualizedVolatility");
            Objects.requireNonNull(right, "right");
            if (underlyingPrice <= 0) throw new IllegalArgumentException("underlyingPrice must be positive");
            if (strikePrice <= 0) throw new IllegalArgumentException("strikePrice must be positive");
            if (timeToExpiryYears < 0) throw new IllegalArgumentException("timeToExpiryYears must be non-negative");
            if (annualizedVolatility <= 0) throw new IllegalArgumentException("annualizedVolatility must be positive");
        }
    }

    /** Vega/rho are per one percentage point; theta is per calendar day. */
    public record ValueGreeks(double theoreticalValue, double delta, double gamma,
            double vegaPerVolatilityPoint, double thetaPerCalendarDay, double rhoPerRatePoint) {
        public ValueGreeks {
            requireFinite(theoreticalValue, "theoreticalValue");
            requireFinite(delta, "delta");
            requireFinite(gamma, "gamma");
            requireFinite(vegaPerVolatilityPoint, "vegaPerVolatilityPoint");
            requireFinite(thetaPerCalendarDay, "thetaPerCalendarDay");
            requireFinite(rhoPerRatePoint, "rhoPerRatePoint");
        }
    }

    private static final double DAYS_PER_YEAR = 365.0;

    private EuropeanOptionPricer() { }

    public static ValueGreeks value(Inputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        double spot = inputs.underlyingPrice();
        double strike = inputs.strikePrice();
        double time = inputs.timeToExpiryYears();
        boolean call = inputs.right() == OptionRight.CALL;
        if (time == 0.0) {
            double value = call ? Math.max(spot - strike, 0.0) : Math.max(strike - spot, 0.0);
            double delta = call
                    ? (spot > strike ? 1.0 : spot < strike ? 0.0 : 0.5)
                    : (spot < strike ? -1.0 : spot > strike ? 0.0 : -0.5);
            // At expiry gamma is singular at the strike; zero is a reporting convention, not the limit.
            return new ValueGreeks(value, delta, 0.0, 0.0, 0.0, 0.0);
        }

        double volatility = inputs.annualizedVolatility();
        double sqrtTime = Math.sqrt(time);
        double sigmaSqrtTime = volatility * sqrtTime;
        double d1 = (Math.log(spot) - Math.log(strike)
                + (inputs.continuouslyCompoundedRiskFreeRate() - inputs.continuousDividendYield()
                + 0.5 * volatility * volatility) * time) / sigmaSqrtTime;
        double d2 = d1 - sigmaSqrtTime;
        double discountUnderlying = Math.exp(-inputs.continuousDividendYield() * time);
        double discountStrike = Math.exp(-inputs.continuouslyCompoundedRiskFreeRate() * time);
        double density = normalDensity(d1);
        double nD1 = normalCdf(d1);
        double nD2 = normalCdf(d2);
        double price;
        double delta;
        double thetaPerYear;
        double rhoPerRatePoint;
        if (call) {
            price = spot * discountUnderlying * nD1 - strike * discountStrike * nD2;
            delta = discountUnderlying * nD1;
            thetaPerYear = -spot * discountUnderlying * density * volatility / (2.0 * sqrtTime)
                    - inputs.continuouslyCompoundedRiskFreeRate() * strike * discountStrike * nD2
                    + inputs.continuousDividendYield() * spot * discountUnderlying * nD1;
            rhoPerRatePoint = 0.01 * strike * time * discountStrike * nD2;
        } else {
            price = strike * discountStrike * normalCdf(-d2)
                    - spot * discountUnderlying * normalCdf(-d1);
            delta = discountUnderlying * (nD1 - 1.0);
            thetaPerYear = -spot * discountUnderlying * density * volatility / (2.0 * sqrtTime)
                    + inputs.continuouslyCompoundedRiskFreeRate() * strike * discountStrike * normalCdf(-d2)
                    - inputs.continuousDividendYield() * spot * discountUnderlying * normalCdf(-d1);
            rhoPerRatePoint = -0.01 * strike * time * discountStrike * normalCdf(-d2);
        }
        double gamma = discountUnderlying * density / (spot * sigmaSqrtTime);
        double vegaPerVolatilityPoint = 0.01 * spot * discountUnderlying * density * sqrtTime;
        double thetaPerCalendarDay = thetaPerYear / DAYS_PER_YEAR;
        if (!Double.isFinite(price) || !Double.isFinite(delta) || !Double.isFinite(gamma)
                || !Double.isFinite(vegaPerVolatilityPoint) || !Double.isFinite(thetaPerCalendarDay)
                || !Double.isFinite(rhoPerRatePoint)) {
            throw new ArithmeticException("Black-Scholes-Merton result is outside the supported numeric range");
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
