package com.mscient.intrapaper.simulation;

import java.math.BigDecimal;
import java.time.Instant;

public record SimTrade(
        String tradeId,
        String instrumentId,
        Instant entryTimestamp,
        Instant exitTimestamp,
        int quantityUnits,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        BigDecimal grossPnlInr,
        BigDecimal feesInr,
        BigDecimal netPnlInr) { }
