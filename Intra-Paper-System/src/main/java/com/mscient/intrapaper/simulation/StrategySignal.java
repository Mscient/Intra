package com.mscient.intrapaper.simulation;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** One reproducible strategy decision (or warm-up row) at a completed candle. */
public record StrategySignal(
        int barNumber,
        Instant decisionTimestamp,
        LocalDate sessionDate,
        String instrumentId,
        Integer targetLots,
        String status,
        Map<String, String> metrics) implements java.io.Serializable {
    public StrategySignal {
        metrics = Collections.unmodifiableMap(new TreeMap<>(metrics));
    }
}
