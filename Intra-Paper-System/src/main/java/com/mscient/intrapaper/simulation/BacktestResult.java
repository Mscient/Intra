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
        List<SimFeeBreakdown> feeBreakdowns,
        List<SimTrade> trades,
        List<SimSettlement> settlements,
        List<SimSettlementChargeBreakdown> settlementChargeBreakdowns,
        List<SimSettlementCostLine> settlementCostLines,
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
        feeBreakdowns = List.copyOf(feeBreakdowns);
        trades = List.copyOf(trades);
        settlements = List.copyOf(settlements);
        settlementChargeBreakdowns = List.copyOf(settlementChargeBreakdowns);
        settlementCostLines = List.copyOf(settlementCostLines);
        sessionAudits = List.copyOf(sessionAudits);
        equityCurve = List.copyOf(equityCurve);
        strategyParameters = Collections.unmodifiableMap(new TreeMap<>(strategyParameters));
    }

    public boolean performanceClaimEligible() {
        return false;
    }
}
