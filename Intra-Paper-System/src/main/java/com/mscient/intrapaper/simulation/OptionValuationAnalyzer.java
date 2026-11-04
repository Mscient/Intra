package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.data.OptionValuationInputSchedule;
import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.pricing.Black76OptionPricer;
import com.mscient.intrapaper.pricing.EuropeanOptionPricer;
import com.mscient.intrapaper.pricing.OptionPricingModel;

import java.util.ArrayList;
import java.util.List;

/** Produces diagnostic model values without changing fills, positions, settlement, or P&amp;L. */
public final class OptionValuationAnalyzer {
    private OptionValuationAnalyzer() { }

    public static List<OptionValuationObservation> evaluate(ValidatedDataset dataset) {
        if (dataset.optionValuationInputs().isEmpty()) return List.of();
        OptionValuationInputSchedule schedule = dataset.optionValuationInputs().orElseThrow();
        List<OptionValuationObservation> observations = new ArrayList<>(dataset.bars().size());
        for (Bar bar : dataset.bars()) {
            var closeTimestamp = OptionValuationInputSchedule.closeTimestamp(bar);
            OptionValuationInputSchedule.Input input = schedule.inputs().get(closeTimestamp);
            if (input == null) throw new IllegalStateException("Validated option valuation input is missing at " + closeTimestamp);
            double strike = bar.strike().orElseThrow().doubleValue();
            var right = bar.optionRight().orElseThrow();
            double marketPremium = bar.close().doubleValue();
            if (!Double.isFinite(strike) || !Double.isFinite(marketPremium)) {
                throw new ArithmeticException("Option bar price is outside the supported model range at " + closeTimestamp);
            }
            double theoreticalValue;
            double delta;
            double gamma;
            double vega;
            double theta;
            double rho;
            if (input.model() == OptionPricingModel.BSM_SPOT) {
                EuropeanOptionPricer.ValueGreeks greeks = EuropeanOptionPricer.value(
                        new EuropeanOptionPricer.Inputs(input.underlyingPrice(), strike,
                                input.timeToExpiryYears(), input.riskFreeRate(), input.dividendYield(),
                                input.annualizedVolatility(), right));
                theoreticalValue = greeks.theoreticalValue();
                delta = greeks.delta();
                gamma = greeks.gamma();
                vega = greeks.vegaPerVolatilityPoint();
                theta = greeks.thetaPerCalendarDay();
                rho = greeks.rhoPerRatePoint();
            } else {
                Black76OptionPricer.ValueGreeks greeks = Black76OptionPricer.value(
                        new Black76OptionPricer.Inputs(input.underlyingPrice(), strike,
                                input.timeToExpiryYears(), input.riskFreeRate(),
                                input.annualizedVolatility(), right));
                theoreticalValue = greeks.theoreticalValue();
                delta = greeks.deltaPerFuturesUnit();
                gamma = greeks.gamma();
                vega = greeks.vegaPerVolatilityPoint();
                theta = greeks.thetaPerCalendarDay();
                rho = greeks.rhoPerRatePoint();
            }
            double residual = marketPremium - theoreticalValue;
            if (!Double.isFinite(residual)) {
                throw new ArithmeticException("Option valuation residual exceeds supported numeric range at " + closeTimestamp);
            }
            observations.add(new OptionValuationObservation(closeTimestamp, bar.instrumentId(),
                    input.model().name(), input.sourceReference(), input.underlyingPrice(),
                    input.timeToExpiryYears(), input.riskFreeRate(), input.dividendYield(),
                    input.annualizedVolatility(), marketPremium, theoreticalValue,
                    residual, delta, gamma, vega, theta, rho));
        }
        return List.copyOf(observations);
    }
}
