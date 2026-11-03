package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.provenance.RunConfig;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Independent consistency checks across the simulated OMS, fills, trades, sessions, and equity ledgers. */
public final class RunLedgerReconciler {
    private RunLedgerReconciler() { }

    public static void requireConsistent(BacktestResult result) {
        BigDecimal tradeGross = BigDecimal.ZERO;
        for (SimTrade trade : result.trades()) tradeGross = tradeGross.add(trade.grossPnlInr());
        requireEqual("trade gross P&L vs realized P&L", tradeGross, result.realizedPnlInr());

        if (result.settlements().size() > 1) fail("single-contract run has more than one expiry settlement");
        for (SimSettlement settlement : result.settlements()) {
            if (settlement.positionUnitsSettled() < 0) fail("negative settled position units");
            if (settlement.positionUnitsSettled() == 0) {
                requireEqual("flat settlement gross P&L", BigDecimal.ZERO, settlement.grossPnlInr());
            } else {
                long matchingTrades = result.trades().stream()
                        .filter(trade -> trade.exitTimestamp().equals(settlement.settlementTimestamp()))
                        .filter(trade -> trade.quantityUnits() == settlement.positionUnitsSettled())
                        .filter(trade -> trade.exitPrice().compareTo(settlement.exitValuePerUnit()) == 0)
                        .filter(trade -> trade.grossPnlInr().compareTo(settlement.grossPnlInr()) == 0)
                        .count();
                if (matchingTrades != 1) fail("settlement does not reconcile to exactly one completed trade");
            }
        }

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

    /** Independently rechecks simulated entry limits and daily-loss halt behavior from saved ledgers. */
    public static void requireRiskLimitsConsistent(BacktestResult result, RunConfig config, ValidatedDataset dataset) {
        int lotSize = dataset.bars().getFirst().lotSize();
        Map<String, SimOrder> orders = new HashMap<>();
        result.orders().forEach(order -> orders.put(order.orderId(), order));
        for (SimFill fill : result.fills()) {
            if (!fill.side().equals("BUY")) continue;
            SimOrder order = orders.get(fill.orderId());
            if (order == null) fail("risk audit fill has no order: " + fill.fillId());
            int lots = order.requestedUnits() / lotSize;
            if (order.requestedUnits() % lotSize != 0 || lots < 1 || lots > config.maxPositionLots()) {
                fail("entry fill exceeds configured lot limit: " + fill.fillId());
            }
            BigDecimal notional = fill.fillPrice().multiply(BigDecimal.valueOf(fill.quantityUnits()));
            if (notional.compareTo(config.maxNotionalInr()) > 0) fail("entry fill exceeds configured notional limit: " + fill.fillId());
            if (config.marginPerLotInr().multiply(BigDecimal.valueOf(lots)).compareTo(config.maxMarginInr()) > 0) {
                fail("entry fill exceeds configured margin proxy: " + fill.fillId());
            }
        }

        ZoneId zone = ZoneId.of(config.timezone());
        Map<LocalDate, List<SimOrderEvent>> haltsByDate = new HashMap<>();
        for (SimOrderEvent event : result.orderEvents()) {
            if (event.event().equals("RISK_HALT")) {
                haltsByDate.computeIfAbsent(event.timestamp().atZone(zone).toLocalDate(), ignored -> new ArrayList<>()).add(event);
            }
        }
        Map<LocalDate, List<EquityPoint>> pointsByDate = new java.util.TreeMap<>();
        for (EquityPoint point : result.equityCurve()) {
            pointsByDate.computeIfAbsent(point.sessionDate(), ignored -> new ArrayList<>()).add(point);
        }
        BigDecimal priorSessionNet = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, List<EquityPoint>> entry : pointsByDate.entrySet()) {
            List<EquityPoint> points = entry.getValue();
            points.sort(Comparator.comparing(EquityPoint::timestamp));
            boolean breached = false;
            for (EquityPoint point : points) {
                if (priorSessionNet.subtract(point.netPnlInr()).compareTo(config.maxDailyLossInr()) >= 0) {
                    breached = true;
                    boolean hasHalt = haltsByDate.getOrDefault(entry.getKey(), List.of()).stream()
                            .anyMatch(event -> !event.timestamp().isBefore(point.timestamp()));
                    if (!hasHalt) fail("daily loss limit breached without a recorded risk halt on " + entry.getKey());
                    break;
                }
            }
            if (breached) {
                for (SimFill fill : result.fills()) {
                    if (!fill.side().equals("BUY") || !fill.timestamp().atZone(zone).toLocalDate().equals(entry.getKey())) continue;
                    boolean afterHalt = haltsByDate.getOrDefault(entry.getKey(), List.of()).stream()
                            .anyMatch(event -> !fill.timestamp().isBefore(event.timestamp()));
                    if (afterHalt) fail("new entry fill occurred at/after a session risk halt: " + fill.fillId());
                }
            }
            if (!points.isEmpty()) priorSessionNet = points.getLast().netPnlInr();
        }
    }

    private static void requireEqual(String label, BigDecimal left, BigDecimal right) {
        if (left.compareTo(right) != 0) fail(label + " mismatch: " + left.toPlainString() + " != " + right.toPlainString());
    }

    private static void fail(String reason) {
        throw new IllegalStateException("Run ledger reconciliation failed: " + reason);
    }
}
