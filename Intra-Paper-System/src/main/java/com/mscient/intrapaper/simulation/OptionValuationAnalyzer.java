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

    public static List<OptionValuationObservation> evaluate(ValidatedDataset dataset,
            List<SimFill> fills, List<SimSettlement> settlements) {
        if (dataset.optionValuationInputs().isEmpty()) return List.of();
        OptionValuationInputSchedule schedule = dataset.optionValuationInputs().orElseThrow();
        List<OptionValuationObservation> observations = new ArrayList<>(dataset.bars().size());
        int fillIndex = 0;
        int settlementIndex = 0;
        int positionUnits = 0;
        for (Bar bar : dataset.bars()) {
            var closeTimestamp = OptionValuationInputSchedule.closeTimestamp(bar);
            OptionValuationInputSchedule.Input input = schedule.inputs().get(closeTimestamp);
            if (input == null) throw new IllegalStateException("Validated option valuation input is missing at " + closeTimestamp);
            while (fillIndex < fills.size() && !fills.get(fillIndex).timestamp().isAfter(closeTimestamp)) {
                SimFill fill = fills.get(fillIndex++);
                positionUnits = switch (fill.side()) {
                    case "BUY" -> Math.addExact(positionUnits, fill.quantityUnits());
                    case "SELL" -> Math.subtractExact(positionUnits, fill.quantityUnits());
                    default -> throw new IllegalStateException("Unknown fill side in option valuation position flow");
                };
                if (positionUnits < 0) throw new IllegalStateException("Option valuation position flow became short");
            }
            while (settlementIndex < settlements.size()
                    && !settlements.get(settlementIndex).settlementTimestamp().isAfter(closeTimestamp)) {
                SimSettlement settlement = settlements.get(settlementIndex++);
                if (settlement.positionUnitsSettled() > positionUnits) {
                    throw new IllegalStateException("Option valuation settlement exceeds reconstructed position");
                }
                positionUnits -= settlement.positionUnitsSettled();
            }
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
            double positionMarketValue = marketPremium * positionUnits;
            double positionTheoreticalValue = theoreticalValue * positionUnits;
            double positionResidual = residual * positionUnits;
            double positionDelta = delta * positionUnits;
            double positionGamma = gamma * positionUnits;
            double positionVega = vega * positionUnits;
            double positionTheta = theta * positionUnits;
            double positionRho = rho * positionUnits;
            if (!Double.isFinite(positionMarketValue) || !Double.isFinite(positionTheoreticalValue)
                    || !Double.isFinite(positionResidual) || !Double.isFinite(positionDelta)
                    || !Double.isFinite(positionGamma) || !Double.isFinite(positionVega)
                    || !Double.isFinite(positionTheta) || !Double.isFinite(positionRho)) {
                throw new ArithmeticException("Option position valuation exceeds supported numeric range at " + closeTimestamp);
            }
            observations.add(new OptionValuationObservation(closeTimestamp, bar.instrumentId(),
                    input.model().name(), input.sourceReference(), input.underlyingPrice(),
                    input.timeToExpiryYears(), input.riskFreeRate(), input.dividendYield(),
                    input.annualizedVolatility(), marketPremium, theoreticalValue,
                    residual, positionUnits, positionMarketValue, positionTheoreticalValue, positionResidual,
                    delta, gamma, vega, theta, rho, positionDelta, positionGamma, positionVega,
                    positionTheta, positionRho));
        }
        return List.copyOf(observations);
    }
}
