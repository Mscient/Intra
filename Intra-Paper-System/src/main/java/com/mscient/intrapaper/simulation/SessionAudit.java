package com.mscient.intrapaper.simulation;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Per-session simulated ledger reconciliation and input coverage. */
public record SessionAudit(
        LocalDate sessionDate,
        long expectedBars,
        long observedBars,
        boolean complete,
        int positionUnitsAtStart,
        int positionUnitsAtEnd,
        BigDecimal realizedPnlChangeInr,
        BigDecimal unrealizedPnlChangeInr,
        BigDecimal feesPaidInr,
        BigDecimal modeledSlippageInr,
        BigDecimal netPnlChangeInr,
        int ordersCreated,
        int fills,
        int completedTrades,
        int riskHalts,
        String endingRiskState) { }
