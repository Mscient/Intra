package com.mscient.intrapaper.simulation;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record BacktestResult(
        List<StrategySignal> signals,
        List<SimOrder> orders,
        List<SimOrderEvent> orderEvents,
        List<SimFill> fills,
        List<SimTrade> trades,
        List<SimSettlement> settlements,
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
        String resultClassification,
        String strategyId,
        Map<String, String> strategyParameters) {
    public BacktestResult {
        signals = List.copyOf(signals);
        orders = List.copyOf(orders);
        orderEvents = List.copyOf(orderEvents);
        fills = List.copyOf(fills);
        trades = List.copyOf(trades);
        settlements = List.copyOf(settlements);
        sessionAudits = List.copyOf(sessionAudits);
        equityCurve = List.copyOf(equityCurve);
        strategyParameters = Collections.unmodifiableMap(new TreeMap<>(strategyParameters));
    }

    public boolean performanceClaimEligible() {
        return false;
    }
}
