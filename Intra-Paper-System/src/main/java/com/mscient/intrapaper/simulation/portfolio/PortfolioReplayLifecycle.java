package com.mscient.intrapaper.simulation.portfolio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/** Optional offline fill-model lifecycle. Quantities are signed underlying units, including explicit zeroes. */
public interface PortfolioReplayLifecycle {
    void beforeFrame(Instant timestamp, LocalDate session, Map<String, Long> signedUnits, Map<String, Integer> lotSizes);
    void afterOutcome(PortfolioReplayEngine.OrderOutcome outcome);
    void executionUncertain(PortfolioReplayEngine.OrderIntent intent, RuntimeException failure);
    void afterFrame(Map<String, Long> signedUnits);
}
