package com.mscient.intrapaper.simulation;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Independent consistency checks across the simulated OMS, fills, trades, sessions, and equity ledgers. */
public final class RunLedgerReconciler {
    private RunLedgerReconciler() { }

    public static void requireConsistent(BacktestResult result) {
        BigDecimal tradeGross = BigDecimal.ZERO;
        for (SimTrade trade : result.trades()) tradeGross = tradeGross.add(trade.grossPnlInr());
        requireEqual("trade gross P&L vs realized P&L", tradeGross, result.realizedPnlInr());

        BigDecimal fillFees = BigDecimal.ZERO;
        BigDecimal fillSlippage = BigDecimal.ZERO;
        Map<String, Integer> fillsByOrder = new HashMap<>();
        for (SimFill fill : result.fills()) {
            fillFees = fillFees.add(fill.feeInr());
            fillSlippage = fillSlippage.add(fill.slippageCostInr());
            fillsByOrder.merge(fill.orderId(), 1, Integer::sum);
        }
        requireEqual("fill fees vs run fees", fillFees, result.feesPaidInr());
        requireEqual("fill slippage vs run slippage", fillSlippage, result.modeledSlippageInr());

        Map<String, String> lastOrderEvent = new HashMap<>();
        Set<String> orderIds = new HashSet<>();
        for (SimOrder order : result.orders()) {
            if (!orderIds.add(order.orderId())) fail("duplicate order ID " + order.orderId());
        }
        for (SimOrderEvent event : result.orderEvents()) {
            if (event.orderId().isBlank()) continue;
            if (!orderIds.contains(event.orderId())) fail("event refers to unknown order " + event.orderId());
            lastOrderEvent.put(event.orderId(), event.event());
        }
        for (SimOrder order : result.orders()) {
            if (!order.status().equals(lastOrderEvent.get(order.orderId()))) {
                fail("final order status/event mismatch for " + order.orderId());
            }
            int fills = fillsByOrder.getOrDefault(order.orderId(), 0);
            if (order.status().equals("FILLED") && fills != 1) {
                fail("filled order must have exactly one fill: " + order.orderId());
            }
            if (!order.status().equals("FILLED") && fills != 0) {
                fail("non-filled order has a fill: " + order.orderId());
            }
        }
        for (String fillOrderId : fillsByOrder.keySet()) {
            if (!orderIds.contains(fillOrderId)) fail("fill refers to unknown order " + fillOrderId);
        }

        BigDecimal sessionNet = BigDecimal.ZERO;
        BigDecimal sessionFees = BigDecimal.ZERO;
        BigDecimal sessionSlippage = BigDecimal.ZERO;
        for (SessionAudit session : result.sessionAudits()) {
            sessionNet = sessionNet.add(session.netPnlChangeInr());
            sessionFees = sessionFees.add(session.feesPaidInr());
            sessionSlippage = sessionSlippage.add(session.modeledSlippageInr());
        }
        requireEqual("session net changes vs run net P&L", sessionNet, result.netPnlInr());
        requireEqual("session fees vs run fees", sessionFees, result.feesPaidInr());
        requireEqual("session slippage vs run slippage", sessionSlippage, result.modeledSlippageInr());

        if (result.equityCurve().isEmpty()) fail("equity ledger is empty");
        EquityPoint last = result.equityCurve().getLast();
        requireEqual("final equity net P&L", last.netPnlInr(), result.netPnlInr());
        requireEqual("final equity realized P&L", last.realizedPnlInr(), result.realizedPnlInr());
        requireEqual("final equity unrealized P&L", last.unrealizedPnlInr(), result.unrealizedPnlInr());
        requireEqual("final equity fees", last.feesPaidInr(), result.feesPaidInr());
        if (last.positionUnits() != result.finalPositionUnits()) fail("final equity position does not match run position");
    }

    private static void requireEqual(String label, BigDecimal left, BigDecimal right) {
        if (left.compareTo(right) != 0) fail(label + " mismatch: " + left.toPlainString() + " != " + right.toPlainString());
    }

    private static void fail(String reason) {
        throw new IllegalStateException("Run ledger reconciliation failed: " + reason);
    }
}
