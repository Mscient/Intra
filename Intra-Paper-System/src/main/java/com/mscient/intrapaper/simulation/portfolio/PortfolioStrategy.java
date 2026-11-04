package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.SynchronizedBarFrameBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Deterministic portfolio strategy interface; input history ends at the current decision frame. */
public interface PortfolioStrategy {
    String id();

    Map<String, String> parameters();

    PortfolioTarget evaluate(List<SynchronizedBarFrameBuilder.Frame> availableFrames, Instant decisionTimestamp,
            PortfolioAccountingLedger.Snapshot completedBarSnapshot);
}
