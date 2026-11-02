package com.mscient.intrapaper.simulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record EquityPoint(
        Instant timestamp,
        LocalDate sessionDate,
        BigDecimal close,
        int positionUnits,
        BigDecimal realizedPnlInr,
        BigDecimal unrealizedPnlInr,
        BigDecimal feesPaidInr,
        BigDecimal netPnlInr,
        String riskState) { }
