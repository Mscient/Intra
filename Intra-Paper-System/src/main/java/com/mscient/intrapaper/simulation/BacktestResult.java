package com.mscient.intrapaper.simulation;

import java.math.BigDecimal;
import java.util.List;

public record BacktestResult(
        List<SimOrder> orders,
        List<SimOrderEvent> orderEvents,
        List<SimFill> fills,
        List<SimTrade> trades,
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
        orders = List.copyOf(orders);
        orderEvents = List.copyOf(orderEvents);
        fills = List.copyOf(fills);
        trades = List.copyOf(trades);
        equityCurve = List.copyOf(equityCurve);
    }

    public boolean performanceClaimEligible() {
        return false;
    }
}
