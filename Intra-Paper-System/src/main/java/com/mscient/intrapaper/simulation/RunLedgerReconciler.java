package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.data.IntervalSupport;
import com.mscient.intrapaper.data.OptionValuationInputSchedule;
import com.mscient.intrapaper.provenance.ComponentCostSchedule;
import com.mscient.intrapaper.provenance.CostSchedule;
import com.mscient.intrapaper.provenance.ExecutionModel;
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
    private record ReconciledSlice(BigDecimal entryPrice, BigDecimal grossPnl, BigDecimal fees) { }
    private static final class ReconciledPosition {
        private final Instant entryTimestamp;
        private BigDecimal remainingBasis;
        private int remainingUnits;
        private BigDecimal remainingEntryFees;
        private boolean exitStarted;

        private ReconciledPosition(SimFill entry) {
            entryTimestamp = entry.timestamp();
            remainingBasis = entry.fillPrice().multiply(BigDecimal.valueOf(entry.quantityUnits()));
            remainingUnits = entry.quantityUnits();
            remainingEntryFees = entry.feeInr();
        }

        private void addEntry(SimFill entry) {
            if (exitStarted) {
                fail("entry fill occurs after the independently reconstructed position began exiting");
            }
            remainingBasis = remainingBasis.add(entry.fillPrice().multiply(BigDecimal.valueOf(entry.quantityUnits())));
            remainingUnits = Math.addExact(remainingUnits, entry.quantityUnits());
            remainingEntryFees = remainingEntryFees.add(entry.feeInr());
        }

        private ReconciledSlice close(BigDecimal exitPrice, int quantity, BigDecimal exitFee) {
            if (quantity < 1 || quantity > remainingUnits) fail("close slice exceeds the independently reconstructed position");
            BigDecimal basis = allocate(remainingBasis, quantity, remainingUnits, 12);
            BigDecimal entryFees = allocate(remainingEntryFees, quantity, remainingUnits, 10);
            BigDecimal averageEntry = basis.divide(BigDecimal.valueOf(quantity), 10, RoundingMode.HALF_UP);
            BigDecimal gross = exitPrice.multiply(BigDecimal.valueOf(quantity)).subtract(basis);
            remainingBasis = remainingBasis.subtract(basis);
            remainingEntryFees = remainingEntryFees.subtract(entryFees);
            remainingUnits -= quantity;
            exitStarted = true;
            return new ReconciledSlice(averageEntry, gross, entryFees.add(exitFee));
        }

        private static BigDecimal allocate(BigDecimal amount, int units, int totalUnits, int scale) {
            if (units == totalUnits) return amount;
            return amount.multiply(BigDecimal.valueOf(units))
                    .divide(BigDecimal.valueOf(totalUnits), scale, RoundingMode.HALF_UP);
        }
    }

    public static void requireConsistent(BacktestResult result) {
        requirePositionFlowConsistent(result);
        requireTradeAccountingConsistent(result);
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
        Map<String, Integer> fillCountByOrder = new HashMap<>();
        Map<String, Integer> filledUnitsByOrder = new HashMap<>();
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
            fillCountByOrder.merge(fill.orderId(), 1, Integer::sum);
            filledUnitsByOrder.merge(fill.orderId(), fill.quantityUnits(), Math::addExact);
        }
        BigDecimal settlementFees = BigDecimal.ZERO;
        for (SimSettlement settlement : result.settlements()) settlementFees = settlementFees.add(settlement.settlementFeesInr());
        requireEqual("fill plus settlement fees vs run fees", fillFees.add(settlementFees), result.feesPaidInr());
        Map<Instant, SimSettlementChargeBreakdown> settlementAuditByTimestamp = new HashMap<>();
        for (SimSettlementChargeBreakdown audit : result.settlementChargeBreakdowns()) {
            if (settlementAuditByTimestamp.putIfAbsent(audit.settlementTimestamp(), audit) != null) {
                fail("duplicate settlement charge breakdown at " + audit.settlementTimestamp());
            }
        }
        if (settlementAuditByTimestamp.size() != result.settlements().size()) {
            fail("settlement charge breakdown count differs from settlement count");
        }
        if (result.settlements().isEmpty() && !result.settlementCostLines().isEmpty()) {
            fail("settlement cost lines exist without a settlement");
        }
        Set<String> settlementCostKeys = new HashSet<>();
        for (SimSettlementCostLine line : result.settlementCostLines()) {
            if (result.settlements().stream().noneMatch(settlement -> settlement.settlementTimestamp()
                    .equals(line.settlementTimestamp()))) {
                fail("settlement cost line refers to an unknown settlement timestamp");
            }
            if (!settlementCostKeys.add(line.settlementTimestamp() + ":" + line.componentCode())) {
                fail("duplicate settlement cost line for " + line.componentCode());
            }
        }
        for (SimSettlement settlement : result.settlements()) {
            SimSettlementChargeBreakdown audit = settlementAuditByTimestamp.get(settlement.settlementTimestamp());
            if (audit == null) fail("missing settlement charge breakdown at " + settlement.settlementTimestamp());
            requireEqual("settlement charges vs audit", settlement.settlementFeesInr(), audit.totalInr());
        }
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
            int fills = fillCountByOrder.getOrDefault(order.orderId(), 0);
            int filledUnits = filledUnitsByOrder.getOrDefault(order.orderId(), 0);
            if (order.status().equals("FILLED")
                    && (fills != 1 || filledUnits != order.requestedUnits())) {
                fail("filled order quantity must equal its request with exactly one fill: " + order.orderId());
            }
            if (order.status().equals("CANCELED")) {
                if (filledUnits < 0 || filledUnits >= order.requestedUnits()
                        || fills > 1 || fills == 1 && filledUnits == 0 || fills == 0 && filledUnits != 0) {
                    fail("canceled order has invalid IOC fill/residual quantities: " + order.orderId());
                }
            } else if (!order.status().equals("FILLED") && fills != 0) {
                fail("non-filled order has a fill: " + order.orderId());
            }
        }
        for (String fillOrderId : fillCountByOrder.keySet()) {
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
        var interval = IntervalSupport.duration(config.interval());
        Map<Instant, Bar> barsByFillTime = new HashMap<>();
        for (Bar bar : dataset.bars()) {
            Instant barStart = config.timestampRole() == com.mscient.intrapaper.reference.TimestampRole.BAR_START
                    ? bar.timestamp() : bar.timestamp().minus(interval);
            barsByFillTime.put(barStart, bar);
        }
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
            SimOrder order = orders.get(fill.orderId());
            if (order == null) fail("risk audit fill has no order: " + fill.fillId());
            Bar bar = config.executionModel() == ExecutionModel.BAR_NEXT_OPEN
                    ? barsByFillTime.get(fill.timestamp())
                    : findQuoteExecutionBar(dataset, order, fill, interval, config.timestampRole());
            if (bar == null) fail("risk audit fill has no matching bar: " + fill.fillId());
            var marginEntry = dataset.marginRequirementSchedule().map(schedule ->
                    schedule.require(bar.sessionDate(), bar.instrumentId(), bar.contractKind()));
            BigDecimal marginPerLot = marginEntry.map(com.mscient.intrapaper.provenance.MarginRequirementSchedule.Entry::marginPerLotInr)
                    .orElse(config.marginPerLotInr());
            String marginSource = marginEntry.map(com.mscient.intrapaper.provenance.MarginRequirementSchedule.Entry::sourceReference)
                    .orElse("FIXED_CONFIG_PROXY");
            requireEqual("recorded margin per lot for " + fill.fillId(), marginPerLot, fill.marginPerLotInr());
            if (!marginSource.equals(fill.marginSourceReference())) fail("recorded margin source differs from frozen input for " + fill.fillId());
            if (!fill.side().equals("BUY")) continue;
            int lots = order.targetLots();
            if (order.requestedUnits() % lotSize != 0 || order.requestedUnits() < 1
                    || lots < 1 || lots > config.maxPositionLots()
                    || fill.quantityUnits() > order.requestedUnits()) {
                fail("entry fill exceeds configured lot limit: " + fill.fillId());
            }
            BigDecimal notional = fill.fillPrice().multiply(BigDecimal.valueOf(fill.quantityUnits()));
            if (notional.compareTo(config.maxNotionalInr()) > 0) fail("entry fill exceeds configured notional limit: " + fill.fillId());
            if (marginPerLot.multiply(BigDecimal.valueOf(lots)).compareTo(config.maxMarginInr()) > 0) {
                fail("entry fill exceeds effective margin requirement cap: " + fill.fillId());
            }
        }
        requireOptionStressChecks(result, config, dataset);

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

    private static void requireOptionStressChecks(BacktestResult result, RunConfig config, ValidatedDataset dataset) {
        List<SimOrderEvent> stressEvents = result.orderEvents().stream()
                .filter(event -> event.event().startsWith("OPTION_STRESS_CHECK_"))
                .toList();
        if (config.optionStressRiskLimits().isEmpty()) {
            if (!stressEvents.isEmpty()) fail("option stress check events exist without configured option stress limits");
            return;
        }
        if (dataset.optionValuationInputs().isEmpty()) fail("option stress limits have no valuation input schedule");
        Map<String, SimOrderEvent> eventsByOrder = new HashMap<>();
        for (SimOrderEvent event : stressEvents) {
            if (eventsByOrder.putIfAbsent(event.orderId(), event) != null) {
                fail("multiple option stress check events for order " + event.orderId());
            }
        }
        Map<String, SimOrder> ordersById = new HashMap<>();
        result.orders().forEach(order -> ordersById.put(order.orderId(), order));
        Map<Instant, Bar> barsByClose = new HashMap<>();
        for (Bar bar : dataset.bars()) {
            barsByClose.put(OptionValuationInputSchedule.closeTimestamp(bar), bar);
        }
        Map<Instant, EquityPoint> equityByTimestamp = new HashMap<>();
        Map<LocalDate, List<EquityPoint>> equityBySession = new java.util.TreeMap<>();
        for (EquityPoint point : result.equityCurve()) {
            equityByTimestamp.put(point.timestamp(), point);
            equityBySession.computeIfAbsent(point.sessionDate(), ignored -> new ArrayList<>()).add(point);
        }
        Map<LocalDate, BigDecimal> sessionStartNetByDate = new HashMap<>();
        BigDecimal priorSessionNet = BigDecimal.ZERO;
        for (Map.Entry<LocalDate, List<EquityPoint>> entry : equityBySession.entrySet()) {
            List<EquityPoint> points = entry.getValue();
            points.sort(Comparator.comparing(EquityPoint::timestamp));
            sessionStartNetByDate.put(entry.getKey(), priorSessionNet);
            if (!points.isEmpty()) priorSessionNet = points.getLast().netPnlInr();
        }
        for (SimOrderEvent event : stressEvents) {
            SimOrder order = ordersById.get(event.orderId());
            if (order == null || !order.side().equals("BUY")) fail("option stress check has no BUY order");
            Bar decisionBar = barsByClose.get(order.decisionTimestamp());
            if (decisionBar == null) fail("option stress check has no decision bar");
            EquityPoint decisionEquity = equityByTimestamp.get(order.decisionTimestamp());
            BigDecimal sessionStartNet = sessionStartNetByDate.get(decisionBar.sessionDate());
            if (decisionEquity == null || sessionStartNet == null) {
                fail("option stress check has no decision-time equity mark or session baseline");
            }
            BigDecimal bookedSessionLoss = sessionStartNet.subtract(decisionEquity.netPnlInr()).max(BigDecimal.ZERO);
            OptionValuationInputSchedule.Input input = dataset.optionValuationInputs().orElseThrow()
                    .inputs().get(order.decisionTimestamp());
            OptionStressRiskGate.Check expected = OptionStressRiskGate.evaluate(decisionBar, input,
                    order.targetLots(), decisionBar.lotSize(), bookedSessionLoss, config.maxDailyLossInr(),
                    config.optionStressRiskLimits().orElseThrow());
            String expectedType = expected.rejected()
                    ? "OPTION_STRESS_CHECK_REJECTED" : "OPTION_STRESS_CHECK_PASSED";
            if (!event.event().equals(expectedType) || !event.detail().equals(expected.detail())) {
                fail("option stress check event differs from independent recomputation for order " + order.orderId());
            }
            boolean hasFill = result.fills().stream().anyMatch(fill -> fill.orderId().equals(order.orderId())
                    && fill.side().equals("BUY"));
            if (expected.rejected() && hasFill) fail("risk-rejected option order has a BUY fill: " + order.orderId());
        }
        for (SimFill fill : result.fills()) {
            if (!fill.side().equals("BUY")) continue;
            SimOrderEvent event = eventsByOrder.get(fill.orderId());
            if (event == null || !event.event().equals("OPTION_STRESS_CHECK_PASSED")) {
                fail("option BUY fill lacks a passing stress check: " + fill.fillId());
            }
        }
    }

    /** Independently replays long-only position flow, including partial fills and expiry settlement. */
    private static void requirePositionFlowConsistent(BacktestResult result) {
        int positionUnits = 0;
        for (SimFill fill : result.fills()) {
            if (fill.quantityUnits() < 1) fail("non-positive fill quantity: " + fill.fillId());
            if (fill.side().equals("BUY")) {
                positionUnits = Math.addExact(positionUnits, fill.quantityUnits());
            } else if (fill.side().equals("SELL")) {
                if (positionUnits <= 0 || fill.quantityUnits() > positionUnits) {
                    fail("SELL fill exceeds the open long position: " + fill.fillId());
                }
                positionUnits -= fill.quantityUnits();
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

    /** Recomputes each closed trade slice from execution prices, quantities, and fees. */
    private static void requireTradeAccountingConsistent(BacktestResult result) {
        ReconciledPosition position = null;
        int tradeIndex = 0;
        String instrumentId = result.trades().isEmpty() ? null : result.trades().getFirst().instrumentId();
        for (SimTrade trade : result.trades()) {
            if (!trade.instrumentId().equals(instrumentId)) fail("single-contract trade ledger changes instrument");
        }
        for (SimFill fill : result.fills()) {
            if (fill.side().equals("BUY")) {
                if (position == null) position = new ReconciledPosition(fill);
                else position.addEntry(fill);
                continue;
            }
            if (position == null) fail("SELL fill has no independently reconstructed entry basis: " + fill.fillId());
            ReconciledSlice slice = position.close(fill.fillPrice(), fill.quantityUnits(), fill.feeInr());
            requireTradeSlice(result, tradeIndex++, position, fill.timestamp(), fill.quantityUnits(),
                    fill.fillPrice(), slice);
            if (position.remainingUnits == 0) position = null;
        }
        for (SimSettlement settlement : result.settlements()) {
            if (position == null) {
                if (settlement.positionUnitsSettled() != 0) {
                    fail("settlement has position units but no independently reconstructed entry basis");
                }
                continue;
            }
            if (settlement.positionUnitsSettled() != position.remainingUnits) {
                fail("settlement quantity differs from independently reconstructed remaining position");
            }
            int quantity = position.remainingUnits;
            ReconciledSlice slice = position.close(settlement.exitValuePerUnit(), quantity,
                    settlement.settlementFeesInr());
            requireTradeSlice(result, tradeIndex++, position, settlement.settlementTimestamp(), quantity,
                    settlement.exitValuePerUnit(), slice);
            position = null;
        }
        if (tradeIndex != result.trades().size()) fail("trade ledger has rows without matching exit fills or settlement");
    }

    private static void requireTradeSlice(BacktestResult result, int tradeIndex, ReconciledPosition position,
            Instant exitTimestamp, int quantity, BigDecimal exitPrice, ReconciledSlice expected) {
        if (tradeIndex >= result.trades().size()) fail("closed position slice is missing from trade ledger");
        SimTrade trade = result.trades().get(tradeIndex);
        if (!trade.entryTimestamp().equals(position.entryTimestamp)
                || !trade.exitTimestamp().equals(exitTimestamp) || trade.quantityUnits() != quantity
                || trade.exitPrice().compareTo(exitPrice) != 0) {
            fail("trade row does not match its independently reconstructed closed position slice: " + trade.tradeId());
        }
        requireEqual("trade entry average for " + trade.tradeId(), expected.entryPrice(), trade.entryPrice());
        requireEqual("trade gross P&L for " + trade.tradeId(), expected.grossPnl(), trade.grossPnlInr());
        requireEqual("trade fees for " + trade.tradeId(), expected.fees(), trade.feesInr());
        requireEqual("trade net P&L for " + trade.tradeId(), expected.grossPnl().subtract(expected.fees()), trade.netPnlInr());
    }

    /** Recomputes adverse tick fills and all configured per-fill charges from frozen inputs. */
    public static void requireFillCostsConsistent(BacktestResult result, RunConfig config, ValidatedDataset dataset) {
        Map<String, SimFeeBreakdown> feeAudits = new HashMap<>();
        for (SimFeeBreakdown audit : result.feeBreakdowns()) {
            if (feeAudits.putIfAbsent(audit.fillId(), audit) != null) fail("duplicate fee breakdown for fill " + audit.fillId());
        }
        if (feeAudits.size() != result.fills().size()) fail("fee breakdown count differs from fill count");
        Map<Instant, Bar> barsByFillTime = new HashMap<>();
        var interval = IntervalSupport.duration(config.interval());
        for (Bar bar : dataset.bars()) {
            Instant barStart = config.timestampRole() == com.mscient.intrapaper.reference.TimestampRole.BAR_START
                    ? bar.timestamp() : bar.timestamp().minus(interval);
            if (barsByFillTime.putIfAbsent(barStart, bar) != null) fail("duplicate bar start in validated dataset: " + barStart);
        }
        Map<String, SimOrder> ordersById = new HashMap<>();
        for (SimOrder order : result.orders()) {
            if (ordersById.putIfAbsent(order.orderId(), order) != null) fail("duplicate order ID: " + order.orderId());
        }
        QuoteExecutionBook quoteAuditBook = config.executionModel() != ExecutionModel.BAR_NEXT_OPEN
                ? new QuoteExecutionBook(dataset.quoteHistory().orElseThrow(), config.quoteSourceName().orElseThrow(),
                        dataset.bars().getFirst().instrumentId(),
                        new com.mscient.intrapaper.marketdata.QuoteQualityPolicy(
                                config.quoteMaximumAge(), config.quoteMaximumFutureSkew()))
                : null;
        for (SimFill fill : result.fills()) {
            if (!fill.side().equals("BUY") && !fill.side().equals("SELL")) fail("invalid fill side in ledger: " + fill.fillId());
            if (fill.quantityUnits() < 1) fail("non-positive fill quantity: " + fill.fillId());
            SimOrder order = ordersById.get(fill.orderId());
            if (order == null) fail("fill has no matching simulated order: " + fill.fillId());
            if (!fill.executionModel().equals(config.executionModel().name())) {
                fail("fill execution model differs from run configuration: " + fill.fillId());
            }
            Bar bar = config.executionModel() == ExecutionModel.BAR_NEXT_OPEN
                    ? barsByFillTime.get(fill.timestamp())
                    : findQuoteExecutionBar(dataset, order, fill, interval, config.timestampRole());
            if (bar == null) fail("fill has no matching dataset bar: " + fill.fillId());
            if (fill.referenceOpenPrice().compareTo(bar.open()) != 0) fail("fill reference price differs from dataset open: " + fill.fillId());
            SimFeeBreakdown feeAudit = feeAudits.get(fill.fillId());
            if (feeAudit == null) fail("missing fee breakdown for fill " + fill.fillId());
            if (feeAudit.contractKind() != bar.contractKind()) fail("fee contract kind differs from fill instrument: " + fill.fillId());
            BigDecimal tradedValue = fill.fillPrice().multiply(BigDecimal.valueOf(fill.quantityUnits()));
            requireEqual("fee turnover for " + fill.fillId(), tradedValue, feeAudit.tradedValueInr());
            CostSchedule.Terms terms = null;
            com.mscient.intrapaper.provenance.ComponentCostSchedule.Terms componentTerms = null;
            int slippageTicks;
            if (dataset.componentCostSchedule().isPresent()) {
                if (!feeAudit.costModel().equals("COMPONENTIZED")) fail("wrong cost model in fee audit for " + fill.fillId());
                componentTerms = dataset.componentCostSchedule().orElseThrow().require(
                        bar.sessionDate(), bar.contractKind(), CostSchedule.Side.valueOf(fill.side()));
                slippageTicks = componentTerms.slippageTicks();
                var charges = componentTerms.calculate(tradedValue, fill.quantityUnits());
                requireEqual("brokerage for " + fill.fillId(), charges.brokerageInr(), feeAudit.brokerageInr());
                requireEqual("exchange transaction charge for " + fill.fillId(), charges.exchangeInr(), feeAudit.exchangeTransactionInr());
                requireEqual("IPFT contribution for " + fill.fillId(), charges.ipftInr(), feeAudit.ipftContributionInr());
                requireEqual("SEBI turnover fee for " + fill.fillId(), charges.sebiInr(), feeAudit.sebiTurnoverInr());
                requireEqual("STT for " + fill.fillId(), charges.sttInr(), feeAudit.sttInr());
                requireEqual("stamp duty for " + fill.fillId(), charges.stampDutyInr(), feeAudit.stampDutyInr());
                requireEqual("GST taxable base for " + fill.fillId(), charges.gstTaxableBaseInr(), feeAudit.gstTaxableBaseInr());
                requireEqual("GST for " + fill.fillId(), charges.gstInr(), feeAudit.gstInr());
                requireEqual("total fee for " + fill.fillId(), charges.totalInr(), fill.feeInr());
            } else {
                if (!feeAudit.costModel().equals("LEGACY_ALL_IN")) fail("wrong cost model in fee audit for " + fill.fillId());
                terms = dataset.costSchedule()
                        .map(schedule -> schedule.require(bar.sessionDate(), CostSchedule.Side.valueOf(fill.side())))
                        .orElseGet(() -> new CostSchedule.Terms(config.feePerOrderInr(), config.feePerUnitInr(),
                                config.notionalFeeBps(), config.slippageTicks()));
                slippageTicks = terms.slippageTicks();
                BigDecimal notionalFee = tradedValue.multiply(terms.notionalFeeBps())
                        .divide(BigDecimal.valueOf(10_000), 2, RoundingMode.HALF_UP);
                BigDecimal expectedFee = terms.feePerOrderInr()
                        .add(terms.feePerUnitInr().multiply(BigDecimal.valueOf(fill.quantityUnits())))
                        .add(notionalFee);
                requireEqual("legacy fee for " + fill.fillId(), expectedFee, feeAudit.legacyFeeInr());
                requireEqual("fill fee for " + fill.fillId(), expectedFee, fill.feeInr());
            }
            if (feeAudit.totalInr().compareTo(fill.feeInr()) != 0) fail("fee breakdown does not sum to fill fee: " + fill.fillId());
            BigDecimal adverseTicks = bar.tickSize().multiply(BigDecimal.valueOf(slippageTicks));
            BigDecimal referenceExecutionPrice;
            if (config.executionModel() == ExecutionModel.BAR_NEXT_OPEN) {
                if (fill.quoteSource() != null || fill.quoteSequence() != 0 || fill.quoteReceivedAt() != null) {
                    fail("bar-open fill unexpectedly references a quote: " + fill.fillId());
                }
                referenceExecutionPrice = bar.open();
            } else {
                Instant arrival = order.decisionTimestamp().plus(config.decisionToArrivalLatency());
                Instant barStart = config.timestampRole() == com.mscient.intrapaper.reference.TimestampRole.BAR_START
                        ? bar.timestamp() : bar.timestamp().minus(interval);
                if (arrival.isBefore(barStart)) arrival = barStart;
                if (!arrival.equals(fill.timestamp())) fail("quote fill timestamp differs from configured arrival: " + fill.fillId());
                QuoteExecutionBook.Decision decision = quoteAuditBook.evaluate(arrival,
                        ExecutionEvent.Side.valueOf(fill.side()), order.requestedUnits(), bar.lotSize(),
                        slippageTicks, config.executionModel());
                if (decision.filledUnits() != fill.quantityUnits() || decision.quote().isEmpty()
                        || decision.assessment().isEmpty()
                        || !decision.assessment().orElseThrow().executable()) {
                    fail("persisted quote fill quantity is not eligible under the frozen quote policy: " + fill.fillId());
                }
                var quote = decision.quote().orElseThrow();
                if (!quote.source().equals(fill.quoteSource())
                        || fill.quoteSequence() != quote.sequenceNumber()
                        || !quote.receivedAtUtc().equals(fill.quoteReceivedAt())) {
                    fail("persisted quote reference differs from the selected quote: " + fill.fillId());
                }
                referenceExecutionPrice = bar.tickSize().multiply(BigDecimal.valueOf(
                        decision.assessment().orElseThrow().referencePriceTicks().orElseThrow()));
                quoteAuditBook.consume(decision, ExecutionEvent.Side.valueOf(fill.side()), fill.quantityUnits());
            }
            requireEqual("reference execution price for " + fill.fillId(), referenceExecutionPrice,
                    fill.referenceExecutionPrice());
            BigDecimal expectedFill = fill.side().equals("BUY")
                    ? referenceExecutionPrice.add(adverseTicks) : referenceExecutionPrice.subtract(adverseTicks);
            requireEqual("modeled adverse fill price for " + fill.fillId(), expectedFill, fill.fillPrice());
            BigDecimal expectedSlippage = adverseTicks.multiply(BigDecimal.valueOf(fill.quantityUnits()));
            requireEqual("modeled slippage for " + fill.fillId(), expectedSlippage, fill.slippageCostInr());
        }
        requireSettlementCostsConsistent(result, dataset);
    }

    private static Bar findQuoteExecutionBar(ValidatedDataset dataset, SimOrder order, SimFill fill,
            Duration interval, com.mscient.intrapaper.reference.TimestampRole timestampRole) {
        for (Bar candidate : dataset.bars()) {
            Instant start = timestampRole == com.mscient.intrapaper.reference.TimestampRole.BAR_START
                    ? candidate.timestamp() : candidate.timestamp().minus(interval);
            Instant end = start.plus(interval);
            if (!start.isBefore(order.decisionTimestamp()) && !fill.timestamp().isBefore(start)
                    && fill.timestamp().isBefore(end)) return candidate;
        }
        return null;
    }

    private static void requireSettlementCostsConsistent(BacktestResult result, ValidatedDataset dataset) {
        if (result.settlements().isEmpty()) {
            if (!result.settlementChargeBreakdowns().isEmpty()) fail("settlement charge exists without a settlement");
            return;
        }
        if (result.settlements().size() != 1) fail("single-contract run has more than one settlement");
        var input = dataset.expirySettlement().orElseThrow(() ->
                new IllegalStateException("settlement output exists without a settlement input"));
        SimSettlement settlement = result.settlements().getFirst();
        Bar lastBar = dataset.bars().getLast();
        if (!settlement.instrumentId().equals(input.instrumentId())
                || !settlement.settlementDate().equals(input.settlementDate())
                || !settlement.settlementTimestamp().equals(input.settlementTimestamp())
                || settlement.kind() != input.kind()
                || settlement.settlementValue().compareTo(input.settlementValue()) != 0) {
            fail("settlement ledger fields differ from the frozen settlement input");
        }
        BigDecimal intrinsic = BigDecimal.ZERO;
        BigDecimal expectedExit;
        if (input.kind() == com.mscient.intrapaper.provenance.ExpirySettlement.Kind.FUTURES_FINAL_PRICE) {
            expectedExit = input.settlementValue();
        } else {
            BigDecimal strike = lastBar.strike().orElseThrow(() -> new IllegalStateException("option has no strike"));
            BigDecimal rawIntrinsic = lastBar.optionRight().orElseThrow() == com.mscient.intrapaper.reference.OptionRight.CALL
                    ? input.settlementValue().subtract(strike) : strike.subtract(input.settlementValue());
            intrinsic = rawIntrinsic.max(BigDecimal.ZERO);
            expectedExit = intrinsic;
        }
        requireEqual("settlement exit value", expectedExit, settlement.exitValuePerUnit());

        SimSettlementChargeBreakdown audit = result.settlementChargeBreakdowns().getFirst();
        if (!audit.settlementTimestamp().equals(settlement.settlementTimestamp())) fail("settlement charge timestamp mismatch");
        if (audit.contractKind() != lastBar.contractKind()) fail("settlement charge contract kind mismatch");
        if (audit.positionUnits() != settlement.positionUnitsSettled()) fail("settlement charge quantity mismatch");
        BigDecimal sebiTurnoverPerUnit = input.sebiExerciseTurnoverValuePerUnitInr().orElse(BigDecimal.ZERO);
        BigDecimal expectedSttBase = intrinsic.multiply(BigDecimal.valueOf(settlement.positionUnitsSettled()));
        BigDecimal expectedSebiBase = sebiTurnoverPerUnit.multiply(BigDecimal.valueOf(settlement.positionUnitsSettled()));
        requireEqual("option exercise intrinsic basis", intrinsic, audit.optionIntrinsicPerUnitInr());
        requireEqual("option exercise STT base", expectedSttBase, audit.exerciseSttBaseInr());
        requireEqual("option exercise SEBI turnover base", expectedSebiBase, audit.sebiExerciseTurnoverBaseInr());

        String expectedModel = "NOT_MODELED";
        ComponentCostSchedule.Charges expectedCharges = zeroCharges();
        if (lastBar.contractKind() == com.mscient.intrapaper.reference.ContractKind.OPTION
                && dataset.componentCostSchedule().isPresent()) {
            var terms = dataset.componentCostSchedule().orElseThrow().require(input.settlementDate(),
                    lastBar.contractKind(), CostSchedule.Side.BUY);
            boolean exercised = settlement.positionUnitsSettled() > 0 && intrinsic.signum() > 0;
            if (exercised && terms.sebiInrPerCrore().signum() > 0
                    && input.sebiExerciseTurnoverValuePerUnitInr().isEmpty()) {
                fail("missing source-supplied SEBI option-exercise turnover base");
            }
            if (exercised) {
                expectedCharges = terms.calculateOptionExercise(intrinsic, sebiTurnoverPerUnit,
                        settlement.positionUnitsSettled());
            }
            expectedModel = "COMPONENTIZED_OPTION_EXERCISE";
        }
        List<com.mscient.intrapaper.provenance.SettlementCostSchedule.ChargeLine> expectedSettlementLines =
                dataset.settlementCostSchedule().isPresent()
                        ? dataset.settlementCostSchedule().orElseThrow().calculate(input.settlementDate(),
                                lastBar.contractKind(), input.settlementValue(), intrinsic,
                                settlement.positionUnitsSettled())
                        : List.of();
        if (expectedSettlementLines.size() != result.settlementCostLines().size()) {
            fail("settlement cost line count differs from the frozen schedule");
        }
        BigDecimal expectedAdditionalSettlementFees = BigDecimal.ZERO;
        for (int i = 0; i < expectedSettlementLines.size(); i++) {
            var expected = expectedSettlementLines.get(i);
            SimSettlementCostLine actual = result.settlementCostLines().get(i);
            if (!actual.settlementTimestamp().equals(settlement.settlementTimestamp())
                    || !actual.componentCode().equals(expected.componentCode())
                    || actual.basis() != expected.basis()
                    || !actual.sourceReference().equals(expected.sourceReference())) {
                fail("settlement cost line identity differs from the frozen schedule");
            }
            requireEqual("settlement cost calculation base", expected.calculationBase(), actual.calculationBase());
            requireEqual("settlement cost rate", expected.rate(), actual.rate());
            requireEqual("settlement cost amount", expected.chargeInr(), actual.chargeInr());
            requireEqual("settlement cost GST rate", expected.gstBps(), actual.gstBps());
            requireEqual("settlement cost GST base", expected.gstTaxableBaseInr(), actual.gstTaxableBaseInr());
            requireEqual("settlement cost GST amount", expected.gstInr(), actual.gstInr());
            expectedAdditionalSettlementFees = expectedAdditionalSettlementFees.add(expected.totalInr());
        }
        if (dataset.settlementCostSchedule().isPresent()) {
            expectedModel = expectedModel.equals("COMPONENTIZED_OPTION_EXERCISE")
                    ? "COMPONENTIZED_OPTION_EXERCISE_AND_SETTLEMENT_COSTS" : "COMPONENTIZED_SETTLEMENT_COSTS";
        }
        if (!audit.model().equals(expectedModel)) fail("settlement charge model differs from frozen inputs");
        requireEqual("settlement SEBI fee", expectedCharges.sebiInr(), audit.sebiTurnoverInr());
        requireEqual("settlement exercise STT", expectedCharges.sttInr(), audit.exerciseSttInr());
        requireEqual("settlement GST taxable base", expectedCharges.gstTaxableBaseInr(), audit.gstTaxableBaseInr());
        requireEqual("settlement GST", expectedCharges.gstInr(), audit.gstInr());
        requireEqual("additional settlement fee total", expectedAdditionalSettlementFees, audit.additionalSettlementFeesInr());
        requireEqual("settlement fee total", expectedCharges.totalInr().add(expectedAdditionalSettlementFees), settlement.settlementFeesInr());
    }

    private static ComponentCostSchedule.Charges zeroCharges() {
        return new ComponentCostSchedule.Charges(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static void requireEqual(String label, BigDecimal left, BigDecimal right) {
        if (left.compareTo(right) != 0) fail(label + " mismatch: " + left.toPlainString() + " != " + right.toPlainString());
    }

    private static void fail(String reason) {
        throw new IllegalStateException("Run ledger reconciliation failed: " + reason);
    }
}
