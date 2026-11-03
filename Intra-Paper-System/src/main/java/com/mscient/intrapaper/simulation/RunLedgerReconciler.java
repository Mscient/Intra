package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.data.IntervalSupport;
import com.mscient.intrapaper.provenance.CostSchedule;
import com.mscient.intrapaper.provenance.RunConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
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
        requirePositionFlowConsistent(result);
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
        ExecutionIdempotencyLedger executionLedger = new ExecutionIdempotencyLedger();
        for (SimFill fill : result.fills()) {
            ExecutionIdempotencyLedger.ApplyResult applyResult;
            try {
                applyResult = executionLedger.apply(ExecutionEvent.from(fill));
            } catch (RuntimeException invalidExecution) {
                fail("invalid or conflicting execution event: " + invalidExecution.getMessage());
                return;
            }
            if (applyResult == ExecutionIdempotencyLedger.ApplyResult.DUPLICATE_IDENTICAL) {
                fail("duplicate execution ID in persisted fill ledger: " + fill.fillId());
            }
            fillFees = fillFees.add(fill.feeInr());
            fillSlippage = fillSlippage.add(fill.slippageCostInr());
            fillsByOrder.merge(fill.orderId(), 1, Integer::sum);
        }
        requireEqual("fill fees vs run fees", fillFees, result.feesPaidInr());
        requireEqual("fill slippage vs run slippage", fillSlippage, result.modeledSlippageInr());

        Map<String, String> lastOrderEvent = new HashMap<>();
        Map<String, Instant> lastOrderEventTimestamp = new HashMap<>();
        Set<String> orderIds = new HashSet<>();
        for (SimOrder order : result.orders()) {
            if (!orderIds.add(order.orderId())) fail("duplicate order ID " + order.orderId());
        }
        for (SimOrderEvent event : result.orderEvents()) {
            if (event.orderId().isBlank()) continue;
            if (!orderIds.contains(event.orderId())) fail("event refers to unknown order " + event.orderId());
            Instant priorTimestamp = lastOrderEventTimestamp.put(event.orderId(), event.timestamp());
            if (priorTimestamp != null && event.timestamp().isBefore(priorTimestamp)) {
                fail("order events are time-reversed for " + event.orderId());
            }
            String priorState = lastOrderEvent.get(event.orderId());
            if (event.event().equals("CREATED")) {
                if (priorState != null) fail("order has multiple CREATED events: " + event.orderId());
            } else {
                if (priorState == null) fail("order event precedes CREATED: " + event.orderId());
                try {
                    SimOrderLifecycle.requireTransition(priorState, event.event());
                } catch (IllegalStateException invalidTransition) {
                    fail("invalid order event transition for " + event.orderId() + ": "
                            + priorState + " -> " + event.event());
                }
            }
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
        Instant priorEntryDecision = null;
        for (SimOrder order : result.orders()) {
            if (!order.side().equals("BUY")) continue;
            Instant currentEntryDecision = order.decisionTimestamp();
            if (priorEntryDecision != null) {
                Duration elapsed = Duration.between(priorEntryDecision, currentEntryDecision);
                if (elapsed.isNegative() || elapsed.compareTo(Duration.ofSeconds(config.minSecondsBetweenEntryOrders())) < 0) {
                    fail("entry order rate is faster than configured risk.min_seconds_between_entry_orders");
                }
            }
            priorEntryDecision = currentEntryDecision;
        }
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
        long recordedHaltCount = haltsByDate.values().stream().mapToLong(List::size).sum();
        if (recordedHaltCount != result.riskHalts()) fail("risk halt counter differs from event ledger");
        haltsByDate.forEach((date, events) -> {
            if (events.size() > 1) fail("multiple risk halts recorded in one session: " + date);
        });
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
                            .anyMatch(event -> event.timestamp().equals(point.timestamp()));
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

    /** Independently replays single-position full-fill flow, including explicit expiry settlement. */
    private static void requirePositionFlowConsistent(BacktestResult result) {
        int positionUnits = 0;
        for (SimFill fill : result.fills()) {
            if (fill.quantityUnits() < 1) fail("non-positive fill quantity: " + fill.fillId());
            if (fill.side().equals("BUY")) {
                if (positionUnits != 0) fail("scale-in fill is unsupported while a position is open: " + fill.fillId());
                positionUnits = fill.quantityUnits();
            } else if (fill.side().equals("SELL")) {
                if (positionUnits <= 0 || fill.quantityUnits() != positionUnits) {
                    fail("SELL fill must close the one complete open position: " + fill.fillId());
                }
                positionUnits = 0;
            } else {
                fail("invalid fill side in ledger: " + fill.fillId());
            }
            if (fill.positionUnitsAfter() != positionUnits) {
                fail("fill position-units-after does not match the independent position flow: " + fill.fillId());
            }
        }
        if (result.settlements().size() > 1) fail("single-contract run has more than one expiry settlement");
        if (!result.settlements().isEmpty()) {
            SimSettlement settlement = result.settlements().getFirst();
            if (settlement.positionUnitsSettled() != positionUnits) {
                fail("expiry settlement units differ from the independently reconstructed position");
            }
            positionUnits = 0;
        }
        if (positionUnits != result.finalPositionUnits()) {
            fail("final position differs from independently reconstructed fill/settlement flow");
        }
    }

    /** Recomputes adverse tick fills and all configured per-fill charges from frozen inputs. */
    public static void requireFillCostsConsistent(BacktestResult result, RunConfig config, ValidatedDataset dataset) {
        Map<Instant, Bar> barsByFillTime = new HashMap<>();
        var interval = IntervalSupport.duration(config.interval());
        for (Bar bar : dataset.bars()) {
            Instant barStart = config.timestampRole() == com.mscient.intrapaper.reference.TimestampRole.BAR_START
                    ? bar.timestamp() : bar.timestamp().minus(interval);
            if (barsByFillTime.putIfAbsent(barStart, bar) != null) fail("duplicate bar start in validated dataset: " + barStart);
        }
        for (SimFill fill : result.fills()) {
            if (!fill.side().equals("BUY") && !fill.side().equals("SELL")) fail("invalid fill side in ledger: " + fill.fillId());
            if (fill.quantityUnits() < 1) fail("non-positive fill quantity: " + fill.fillId());
            Bar bar = barsByFillTime.get(fill.timestamp());
            if (bar == null) fail("fill has no matching dataset bar: " + fill.fillId());
            if (fill.referenceOpenPrice().compareTo(bar.open()) != 0) fail("fill reference price differs from dataset open: " + fill.fillId());
            CostSchedule.Terms terms = dataset.costSchedule()
                    .map(schedule -> schedule.require(bar.sessionDate(), CostSchedule.Side.valueOf(fill.side())))
                    .orElseGet(() -> new CostSchedule.Terms(config.feePerOrderInr(), config.feePerUnitInr(),
                            config.notionalFeeBps(), config.slippageTicks()));
            BigDecimal adverseTicks = bar.tickSize().multiply(BigDecimal.valueOf(terms.slippageTicks()));
            BigDecimal expectedFill = fill.side().equals("BUY")
                    ? bar.open().add(adverseTicks) : bar.open().subtract(adverseTicks);
            requireEqual("modeled adverse fill price for " + fill.fillId(), expectedFill, fill.fillPrice());
            BigDecimal expectedSlippage = adverseTicks.multiply(BigDecimal.valueOf(fill.quantityUnits()));
            requireEqual("modeled slippage for " + fill.fillId(), expectedSlippage, fill.slippageCostInr());
            BigDecimal notional = fill.fillPrice().multiply(BigDecimal.valueOf(fill.quantityUnits()));
            BigDecimal notionalFee = notional.multiply(terms.notionalFeeBps())
                    .divide(BigDecimal.valueOf(10_000), 2, RoundingMode.HALF_UP);
            BigDecimal expectedFee = terms.feePerOrderInr()
                    .add(terms.feePerUnitInr().multiply(BigDecimal.valueOf(fill.quantityUnits())))
                    .add(notionalFee);
            requireEqual("fill fee for " + fill.fillId(), expectedFee, fill.feeInr());
        }
    }

    private static void requireEqual(String label, BigDecimal left, BigDecimal right) {
        if (left.compareTo(right) != 0) fail(label + " mismatch: " + left.toPlainString() + " != " + right.toPlainString());
    }

    private static void fail(String reason) {
        throw new IllegalStateException("Run ledger reconciliation failed: " + reason);
    }
}
