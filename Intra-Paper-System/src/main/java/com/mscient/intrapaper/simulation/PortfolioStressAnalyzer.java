package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.pricing.Black76OptionPricer;
import com.mscient.intrapaper.pricing.EuropeanOptionPricer;
import com.mscient.intrapaper.reference.OptionRight;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Caller-input full-revaluation spot/futures and volatility stress; not a margin model. */
public final class PortfolioStressAnalyzer {
    public enum OptionModel { BSM_SPOT, BLACK76_FUTURE }

    public record OptionLeg(String instrumentId, OptionModel model, OptionRight right,
            double underlyingPrice, double strike, double timeToExpiryYears,
            double riskFreeRate, double dividendYield, double annualizedVolatility,
            long signedContracts, long unitsPerContract, double exitHaircutPerUnit) {
        public OptionLeg {
            requireText(instrumentId, "instrumentId");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(right, "right");
            finitePositive(underlyingPrice, "underlyingPrice");
            finitePositive(strike, "strike");
            finitePositive(timeToExpiryYears, "timeToExpiryYears");
            finite(riskFreeRate, "riskFreeRate");
            finite(dividendYield, "dividendYield");
            finitePositive(annualizedVolatility, "annualizedVolatility");
            finiteNonNegative(exitHaircutPerUnit, "exitHaircutPerUnit");
            if (signedContracts == 0 || unitsPerContract < 1) {
                throw new IllegalArgumentException("option leg requires nonzero signed contracts and positive units per contract");
            }
            if (model == OptionModel.BLACK76_FUTURE && dividendYield != 0.0) {
                throw new IllegalArgumentException("dividendYield must be zero for a Black-76 futures leg");
            }
        }
    }

    public record FutureLeg(String instrumentId, double price, long signedUnits,
            double exitHaircutPercent) {
        public FutureLeg {
            requireText(instrumentId, "instrumentId");
            finitePositive(price, "price");
            finiteNonNegative(exitHaircutPercent, "exitHaircutPercent");
            if (signedUnits == 0) throw new IllegalArgumentException("future leg requires nonzero signed units");
        }
    }

    public record Scenario(String id, double underlyingShockPercent, double volatilityShockPoints,
            double repricedPnl, double exitHaircut, double stressedExitPnl) { }

    public record Report(List<Scenario> scenarios, String worstScenarioId, double worstStressedExitPnl,
            boolean hasShortOptions, String scope) {
        public Report { scenarios = List.copyOf(scenarios); }
    }

    private PortfolioStressAnalyzer() { }

    /** Shocks are caller-supplied (the plan's initial proposal is +/-2% and +/-3 volatility points). */
    public static Report analyze(List<OptionLeg> optionLegs, List<FutureLeg> futureLegs,
            double underlyingShockPercent, double volatilityShockPoints) {
        Objects.requireNonNull(optionLegs, "optionLegs");
        Objects.requireNonNull(futureLegs, "futureLegs");
        finitePositive(underlyingShockPercent, "underlyingShockPercent");
        finitePositive(volatilityShockPoints, "volatilityShockPoints");
        if (underlyingShockPercent >= 100.0) throw new IllegalArgumentException("underlying shock must be below 100 percent");
        List<OptionLeg> options = List.copyOf(optionLegs);
        List<FutureLeg> futures = List.copyOf(futureLegs);
        if (options.isEmpty() && futures.isEmpty()) throw new IllegalArgumentException("portfolio has no stressable legs");

        List<Scenario> scenarios = new ArrayList<>(4);
        for (int underlyingSign : new int[]{-1, 1}) {
            for (int volatilitySign : new int[]{-1, 1}) {
                double underlyingShock = underlyingSign * underlyingShockPercent;
                double volatilityShock = volatilitySign * volatilityShockPoints;
                double pnl = 0.0;
                double haircut = 0.0;
                for (OptionLeg leg : options) {
                    double stressedUnderlying = leg.underlyingPrice() * (1.0 + underlyingShock / 100.0);
                    double stressedVolatility = Math.max(0.0001,
                            leg.annualizedVolatility() + volatilityShock / 100.0);
                    double baseValue = optionValue(leg, leg.underlyingPrice(), leg.annualizedVolatility());
                    double stressedValue = optionValue(leg, stressedUnderlying, stressedVolatility);
                    double signedUnits = Math.multiplyExact(leg.signedContracts(), leg.unitsPerContract());
                    pnl += (stressedValue - baseValue) * signedUnits;
                    haircut += leg.exitHaircutPerUnit() * Math.abs((double) signedUnits);
                }
                for (FutureLeg leg : futures) {
                    double stressedPrice = leg.price() * (1.0 + underlyingShock / 100.0);
                    pnl += (stressedPrice - leg.price()) * leg.signedUnits();
                    haircut += leg.price() * leg.exitHaircutPercent() / 100.0 * Math.abs((double) leg.signedUnits());
                }
                if (!Double.isFinite(pnl) || !Double.isFinite(haircut)) {
                    throw new ArithmeticException("portfolio stress result exceeds supported numeric range");
                }
                String id = (underlyingSign < 0 ? "UNDERLYING_DOWN" : "UNDERLYING_UP") + "_IV_"
                        + (volatilitySign < 0 ? "DOWN" : "UP");
                scenarios.add(new Scenario(id, underlyingShock, volatilityShock, pnl, haircut, pnl - haircut));
            }
        }
        Scenario worst = scenarios.stream().min(java.util.Comparator.comparingDouble(Scenario::stressedExitPnl)).orElseThrow();
        boolean shortOptions = options.stream().anyMatch(leg -> leg.signedContracts() < 0);
        return new Report(scenarios, worst.id(), worst.stressedExitPnl(), shortOptions,
                "FOUR_SCENARIO_FULL_REVALUATION_CALLER_INPUTS_NOT_EXCHANGE_MARGIN_OR_LOSS_LIMIT");
    }

    private static double optionValue(OptionLeg leg, double underlying, double volatility) {
        return switch (leg.model()) {
            case BSM_SPOT -> EuropeanOptionPricer.value(new EuropeanOptionPricer.Inputs(underlying,
                    leg.strike(), leg.timeToExpiryYears(), leg.riskFreeRate(), leg.dividendYield(),
                    volatility, leg.right())).theoreticalValue();
            case BLACK76_FUTURE -> Black76OptionPricer.value(new Black76OptionPricer.Inputs(underlying,
                    leg.strike(), leg.timeToExpiryYears(), leg.riskFreeRate(), volatility,
                    leg.right())).theoreticalValue();
        };
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must be non-empty and trimmed");
        }
    }

    private static void finite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }

    private static void finitePositive(double value, String name) {
        finite(value, name);
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void finiteNonNegative(double value, String name) {
        finite(value, name);
        if (value < 0) throw new IllegalArgumentException(name + " must be non-negative");
    }
}
