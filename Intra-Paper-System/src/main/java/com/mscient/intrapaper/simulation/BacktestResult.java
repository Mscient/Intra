package com.mscient.intrapaper.simulation;

import java.math.BigDecimal;
import java.util.List;

public record BacktestResult(
        List<StrategySignal> signals,
        List<SimOrder> orders,
        List<SimOrderEvent> orderEvents,
        List<SimFill> fills,
        List<SimTrade> trades,
        List<SessionAudit> sessionAudits,
        List<EquityPoint> equityCurve,
        BigDecimal realizedPnlInr,
        BigDecimal unrealizedPnlInr,
        BigDecimal feesPaidInr,
        BigDecimal modeledSlippageInr,
        BigDecimal netPnlInr,
        BigDecimal maxDrawdownInr,
        int finalPositionUnits,
        int riskHalts,
        String resultClassification) {
    public BacktestResult {
        signals = List.copyOf(signals);
        orders = List.copyOf(orders);
        orderEvents = List.copyOf(orderEvents);
        fills = List.copyOf(fills);
        trades = List.copyOf(trades);
        sessionAudits = List.copyOf(sessionAudits);
        equityCurve = List.copyOf(equityCurve);
    }

    public boolean performanceClaimEligible() {
        return false;
    }
}
