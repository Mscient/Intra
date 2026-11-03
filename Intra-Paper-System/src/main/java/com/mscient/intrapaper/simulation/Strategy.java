package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.Bar;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Deterministic strategy contract for historical replay. The supplied immutable bar list
 * ends at the decision bar, so strategy code cannot inspect future bars through this API.
 */
public interface Strategy {
    String id();

    /** Stable parameters included in the run identity and manifest. */
    Map<String, String> parameters();

    /** True only when repeated evaluation from the full input history is deterministic across process restarts. */
    default boolean supportsDurableCheckpoints() { return false; }

    StrategySignal evaluate(List<Bar> availableBars, Instant decisionTimestamp);
}
