package com.mscient.intrapaper.simulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One reproducible strategy decision (or warm-up row) at a completed candle. */
public record StrategySignal(
        int barNumber,
        Instant decisionTimestamp,
        LocalDate sessionDate,
        String instrumentId,
        BigDecimal fastAverage,
        BigDecimal slowAverage,
        Integer targetLots,
        String status) { }
