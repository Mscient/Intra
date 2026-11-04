package com.mscient.intrapaper.simulation;

import java.time.Instant;

/** Caller-input model valuation aligned to one observed option-premium bar close. */
public record OptionValuationObservation(
        Instant timestamp,
        String instrumentId,
        String model,
        String sourceReference,
        double underlyingPrice,
        double timeToExpiryYears,
        double riskFreeRate,
        double dividendYield,
        double annualizedVolatility,
        double marketPremiumClose,
        double theoreticalValue,
        double marketMinusModelPremium,
        int positionUnitsAtClose,
        double positionMarketValue,
        double positionTheoreticalValue,
        double positionMarketMinusModelValue,
        double delta,
        double gamma,
        double vegaPerVolatilityPoint,
        double thetaPerCalendarDay,
        double rhoPerRatePoint,
        double positionDeltaEquivalentUnits,
        double positionGamma,
        double positionVegaPerVolatilityPoint,
        double positionThetaPerCalendarDay,
        double positionRhoPerRatePoint) { }
