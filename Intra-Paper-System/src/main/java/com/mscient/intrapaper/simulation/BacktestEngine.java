package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.data.IntervalSupport;
import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.provenance.CrossSessionPositionPolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic long-only futures simulator. Signals at a completed close can fill only at the next bar open. */
public final class BacktestEngine {
    private record Pending(int orderIndex, int targetLots, LocalDate decisionSession) { }
    private static final class OpenTrade {
        private final Instant entryTimestamp;
        private final BigDecimal entryPrice;
        private final int quantity;
        private final BigDecimal entryFee;

        private OpenTrade(Instant entryTimestamp, BigDecimal entryPrice, int quantity, BigDecimal entryFee) {
            this.entryTimestamp = entryTimestamp;
            this.entryPrice = entryPrice;
            this.quantity = quantity;
            this.entryFee = entryFee;
        }
    }

    public BacktestResult run(ValidatedDataset dataset, RunConfig config) {
        return run(dataset, config, new SmaCrossoverStrategy(config));
    }

    public BacktestResult run(ValidatedDataset dataset, RunConfig config, Strategy strategy) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(strategy, "strategy");
        String strategyId = Objects.requireNonNull(strategy.id(), "strategy.id");
        if (strategyId.isBlank() || !strategyId.equals(strategyId.strip())) {
            throw new IllegalArgumentException("Strategy ID must be non-empty and trimmed");
        }
        Map<String, String> strategyParameters = Map.copyOf(strategy.parameters());
        List<Bar> bars = dataset.bars();
        List<StrategySignal> signals = new ArrayList<>();
        List<SimOrder> orders = new ArrayList<>();
        List<SimOrderEvent> events = new ArrayList<>();
        List<SimFill> fills = new ArrayList<>();
        List<SimTrade> trades = new ArrayList<>();
        List<EquityPoint> curve = new ArrayList<>();
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        BigDecimal slippage = BigDecimal.ZERO;
        BigDecimal averageEntry = BigDecimal.ZERO;
        int positionLots = 0;
        Pending pending = null;
        OpenTrade openTrade = null;
        LocalDate activeSession = null;
        BigDecimal netAtPriorClose = BigDecimal.ZERO;
        int riskHalts = 0;
        RiskGate riskGate = new RiskGate(config);
        BigDecimal highWater = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        var interval = IntervalSupport.duration(config.interval());

        for (int i = 0; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            Instant barStart = config.timestampRole() == com.mscient.intrapaper.reference.TimestampRole.BAR_START
                    ? bar.timestamp() : bar.timestamp().minus(interval);
            Instant barEnd = config.timestampRole() == com.mscient.intrapaper.reference.TimestampRole.BAR_END
                    ? bar.timestamp() : bar.timestamp().plus(interval);
            boolean sameSessionNextBar = i + 1 < bars.size() && bars.get(i + 1).sessionDate().equals(bar.sessionDate());
            boolean nextBarEligible = i + 1 < bars.size() && (sameSessionNextBar || config.allowNextSessionOrders());

            if (activeSession == null || !activeSession.equals(bar.sessionDate())) {
                if (activeSession != null && config.crossSessionPositionPolicy() == CrossSessionPositionPolicy.REQUIRE_FLAT && positionLots != 0) {
                    throw new IllegalStateException("Open position crossed session boundary under REQUIRE_FLAT: " + activeSession);
                }
                if (pending != null && !config.allowNextSessionOrders()) {
                    expire(orders, events, pending, barStart, "SESSION_BOUNDARY");
                    pending = null;
                }
                activeSession = bar.sessionDate();
                riskGate.startSession(activeSession, netAtPriorClose);
                events.add(new SimOrderEvent(barStart, "", "RISK_SESSION_RESET", "session=" + activeSession));
            }

            if (pending != null) {
                int targetLots = pending.targetLots();
                int deltaLots = targetLots - positionLots;
                SimOrder order = orders.get(pending.orderIndex());
                updateOrder(orders, pending.orderIndex(), barStart, null, "ACKNOWLEDGED_BY_SIMULATOR", "local simulated market order");
                events.add(new SimOrderEvent(barStart, order.orderId(), "ACKNOWLEDGED_BY_SIMULATOR", "local simulated market order"));
                if (deltaLots == 0) {
                    updateOrder(orders, pending.orderIndex(), null, null, "REJECTED_NO_OP", "position already at target");
                    events.add(new SimOrderEvent(barStart, order.orderId(), "REJECTED_NO_OP", "position already at target"));
                } else {
                    String side = deltaLots > 0 ? "BUY" : "SELL";
                    int quantity = Math.abs(deltaLots) * bar.lotSize();
                    BigDecimal tickSlippage = bar.tickSize().multiply(BigDecimal.valueOf(config.slippageTicks()));
                    BigDecimal fillPrice = side.equals("BUY") ? bar.open().add(tickSlippage) : bar.open().subtract(tickSlippage);
                    String riskRejection = fillPrice.signum() <= 0 ? "NON_POSITIVE_MODELED_FILL_PRICE"
                            : side.equals("BUY") ? riskGate.entryRejectionReason(targetLots, bar, fillPrice) : null;
                    if (riskRejection != null) {
                        String reason = riskRejection;
                        updateOrder(orders, pending.orderIndex(), barStart, null, "REJECTED_RISK", reason);
                        events.add(new SimOrderEvent(barStart, order.orderId(), "REJECTED_RISK", reason));
                    } else {
                        BigDecimal fillNotional = fillPrice.multiply(BigDecimal.valueOf(quantity));
                        BigDecimal notionalFee = fillNotional.multiply(config.notionalFeeBps())
                                .divide(BigDecimal.valueOf(10_000), 2, RoundingMode.HALF_UP);
                        BigDecimal fee = config.feePerOrderInr()
                                .add(config.feePerUnitInr().multiply(BigDecimal.valueOf(quantity)))
                                .add(notionalFee);
                        BigDecimal slippageCost = tickSlippage.multiply(BigDecimal.valueOf(quantity));
                        int oldUnits = positionLots * bar.lotSize();
                        int newUnits = targetLots * bar.lotSize();
                        if (side.equals("BUY")) {
                            if (positionLots != 0) throw new IllegalStateException("Simulator supports one entry and one exit per trade");
                            averageEntry = fillPrice;
                            openTrade = new OpenTrade(barStart, fillPrice, quantity, fee);
                        } else {
                            if (positionLots <= 0 || openTrade == null) throw new IllegalStateException("Invalid long-only exit state");
                            realized = realized.add(fillPrice.subtract(averageEntry).multiply(BigDecimal.valueOf(quantity)));
                            BigDecimal tradeGross = fillPrice.subtract(openTrade.entryPrice).multiply(BigDecimal.valueOf(quantity));
                            BigDecimal tradeFees = openTrade.entryFee.add(fee);
                            String tradeId = String.format("T%07d", trades.size() + 1);
                            trades.add(new SimTrade(tradeId, bar.instrumentId(), openTrade.entryTimestamp, barStart,
                                    quantity, openTrade.entryPrice, fillPrice, tradeGross, tradeFees, tradeGross.subtract(tradeFees)));
                            openTrade = null;
                            averageEntry = BigDecimal.ZERO;
                        }
                        positionLots = targetLots;
                        fees = fees.add(fee);
                        slippage = slippage.add(slippageCost);
                        String fillId = String.format("F%07d", fills.size() + 1);
                        fills.add(new SimFill(fillId, order.orderId(), barStart, side, quantity,
                                bar.open(), fillPrice, slippageCost, fee, newUnits));
                        updateOrder(orders, pending.orderIndex(), barStart, barStart, "FILLED", "full simulated market fill");
                        events.add(new SimOrderEvent(barStart, order.orderId(), "FILLED", "fill_id=" + fillId));
                    }
                }
                pending = null;
            }

            BigDecimal unrealized = averageEntry.signum() == 0 ? BigDecimal.ZERO
                    : bar.close().subtract(averageEntry).multiply(BigDecimal.valueOf(positionLots * bar.lotSize()));
            BigDecimal net = realized.add(unrealized).subtract(fees);
            List<Bar> availableBars = Collections.unmodifiableList(bars.subList(0, i + 1));
            StrategySignal signal = Objects.requireNonNull(strategy.evaluate(availableBars, barEnd),
                    "Strategy returned a null signal");
            if (signal.barNumber() != i + 1 || !signal.decisionTimestamp().equals(barEnd)
                    || !signal.sessionDate().equals(bar.sessionDate())
                    || !signal.instrumentId().equals(bar.instrumentId())) {
                throw new IllegalStateException("Strategy signal metadata does not match its decision bar");
            }
            if (signal.targetLots() != null && signal.targetLots() < 0) {
                throw new IllegalStateException("Strategy targetLots cannot be negative in the long-only simulator");
            }
            signals.add(signal);
            if (net.compareTo(highWater) > 0) highWater = net;
            BigDecimal drawdown = highWater.subtract(net);
            if (drawdown.compareTo(maxDrawdown) > 0) maxDrawdown = drawdown;

            RiskGate.Evaluation riskEvaluation = riskGate.markToMarket(net);
            int targetLots;
            String reason;
            if (riskEvaluation.newlyHalted()) {
                riskHalts++;
                targetLots = 0;
                reason = "DAILY_LOSS_LIMIT";
                events.add(new SimOrderEvent(barEnd, "", "RISK_HALT", reason + "; session_loss_inr=" + riskEvaluation.sessionLossInr().toPlainString()));
            } else if (riskGate.halted()) {
                targetLots = 0;
                reason = "RISK_FLATTEN";
            } else {
                targetLots = signal.targetLots() == null ? positionLots : signal.targetLots();
                reason = signal.status().equals("ACTIVE") ? "SMA_CROSSOVER_TARGET" : "STRATEGY_WARMING_UP";
            }

            curve.add(new EquityPoint(barEnd, bar.sessionDate(), bar.close(), positionLots * bar.lotSize(),
                    realized, unrealized, fees, net, riskGate.halted() ? "HALTED_SESSION" : "OPEN"));

            if (targetLots != positionLots) {
                Pending created = createOrder(orders, events, bar, barEnd, targetLots, positionLots, bar.lotSize(), reason);
                if (nextBarEligible) {
                    pending = created;
                } else {
                    String expiryReason = i + 1 < bars.size() ? "SESSION_BOUNDARY" : "NO_NEXT_BAR";
                    expire(orders, events, created, barEnd, expiryReason);
                }
            }
            netAtPriorClose = net;
        }

        if (config.crossSessionPositionPolicy() == CrossSessionPositionPolicy.REQUIRE_FLAT && positionLots != 0) {
            throw new IllegalStateException("Open position remains at end of replay under REQUIRE_FLAT");
        }
        Bar last = bars.getLast();
        BigDecimal unrealized = averageEntry.signum() == 0 ? BigDecimal.ZERO
                : last.close().subtract(averageEntry).multiply(BigDecimal.valueOf(positionLots * last.lotSize()));
        BigDecimal net = realized.add(unrealized).subtract(fees);
        String classification = config.syntheticFixture() ? "SYNTHETIC_PLUMBING_ONLY" : "REPLAY_DIAGNOSTIC_ONLY";
        List<SessionAudit> sessionAudits = reconcileSessions(dataset, config, curve, events, fills, trades);
        BacktestResult result = new BacktestResult(signals, orders, events, fills, trades, sessionAudits, curve, realized, unrealized, fees, slippage,
                net, maxDrawdown, positionLots * last.lotSize(), riskHalts, classification, strategyId, strategyParameters);
        RunLedgerReconciler.requireConsistent(result);
        return result;
    }

    private static List<SessionAudit> reconcileSessions(ValidatedDataset dataset, RunConfig config,
            List<EquityPoint> curve, List<SimOrderEvent> events, List<SimFill> fills, List<SimTrade> trades) {
        Map<LocalDate, List<Bar>> barsByDate = new HashMap<>();
        Map<LocalDate, List<EquityPoint>> pointsByDate = new HashMap<>();
        for (Bar bar : dataset.bars()) barsByDate.computeIfAbsent(bar.sessionDate(), ignored -> new ArrayList<>()).add(bar);
        for (EquityPoint point : curve) pointsByDate.computeIfAbsent(point.sessionDate(), ignored -> new ArrayList<>()).add(point);
        ZoneId timezone = ZoneId.of(config.timezone());
        var spacing = IntervalSupport.duration(config.interval());
        List<SessionAudit> audits = new ArrayList<>();
        BigDecimal priorNet = BigDecimal.ZERO;
        BigDecimal priorRealized = BigDecimal.ZERO;
        BigDecimal priorUnrealized = BigDecimal.ZERO;
        BigDecimal priorFees = BigDecimal.ZERO;
        int priorPosition = 0;

        LocalDate first = dataset.bars().getFirst().sessionDate();
        LocalDate last = dataset.bars().getLast().sessionDate();
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
            LocalDate sessionDate = date;
            var calendarEntry = dataset.sessionCalendar().get(date);
            if (!calendarEntry.open()) continue;
            List<Bar> sessionBars = barsByDate.get(date);
            List<EquityPoint> points = pointsByDate.get(date);
            EquityPoint end = points.getLast();
            long expected = java.time.Duration.between(calendarEntry.firstBarTimestamp().orElseThrow(),
                    calendarEntry.lastBarTimestamp().orElseThrow()).toNanos() / spacing.toNanos() + 1;
            boolean aligned = true;
            for (int i = 1; i < sessionBars.size(); i++) {
                if (!java.time.Duration.between(sessionBars.get(i - 1).timestamp(), sessionBars.get(i).timestamp()).equals(spacing)) {
                    aligned = false;
                    break;
                }
            }
            int created = (int) events.stream().filter(event -> event.event().equals("CREATED"))
                    .filter(event -> !event.orderId().isBlank())
                    .filter(event -> event.timestamp().atZone(timezone).toLocalDate().equals(sessionDate)).count();
            int sessionFills = (int) fills.stream()
                    .filter(fill -> fill.timestamp().atZone(timezone).toLocalDate().equals(sessionDate)).count();
            int closedTrades = (int) trades.stream()
                    .filter(trade -> trade.exitTimestamp().atZone(timezone).toLocalDate().equals(sessionDate)).count();
            BigDecimal sessionSlippage = fills.stream()
                    .filter(fill -> fill.timestamp().atZone(timezone).toLocalDate().equals(sessionDate))
                    .map(SimFill::slippageCostInr).reduce(BigDecimal.ZERO, BigDecimal::add);
            int halts = (int) events.stream().filter(event -> event.event().equals("RISK_HALT"))
                    .filter(event -> event.timestamp().atZone(timezone).toLocalDate().equals(sessionDate)).count();
            audits.add(new SessionAudit(date, expected, sessionBars.size(),
                    expected == sessionBars.size() && aligned, priorPosition, end.positionUnits(),
                    end.realizedPnlInr().subtract(priorRealized), end.unrealizedPnlInr().subtract(priorUnrealized),
                    end.feesPaidInr().subtract(priorFees), sessionSlippage,
                    end.netPnlInr().subtract(priorNet), created, sessionFills, closedTrades, halts, end.riskState()));
            priorNet = end.netPnlInr();
            priorRealized = end.realizedPnlInr();
            priorUnrealized = end.unrealizedPnlInr();
            priorFees = end.feesPaidInr();
            priorPosition = end.positionUnits();
        }
        return List.copyOf(audits);
    }

    private static Pending createOrder(List<SimOrder> orders, List<SimOrderEvent> events, Bar bar,
            Instant decisionTimestamp, int targetLots, int currentLots, int lotSize, String reason) {
        int deltaLots = targetLots - currentLots;
        String side = deltaLots > 0 ? "BUY" : "SELL";
        int units = Math.abs(deltaLots) * lotSize;
        String orderId = String.format("O%07d", orders.size() + 1);
        int index = orders.size();
        orders.add(new SimOrder(orderId, decisionTimestamp, null, null, side, targetLots,
                units, "MARKET", "CREATED", reason));
        events.add(new SimOrderEvent(decisionTimestamp, orderId, "CREATED",
                "target_lots=" + targetLots + "; next_eligible_bar_open"));
        return new Pending(index, targetLots, bar.sessionDate());
    }

    private static void expire(List<SimOrder> orders, List<SimOrderEvent> events, Pending pending,
            Instant timestamp, String reason) {
        SimOrder prior = orders.get(pending.orderIndex());
        String status = reason.equals("NO_NEXT_BAR") ? "EXPIRED_NO_NEXT_BAR" : "EXPIRED_SESSION_BOUNDARY";
        updateOrder(orders, pending.orderIndex(), null, null, status, reason);
        events.add(new SimOrderEvent(timestamp, prior.orderId(), status, reason));
    }

    private static void updateOrder(List<SimOrder> orders, int index, Instant submit, Instant fill,
            String status, String reason) {
        SimOrder prior = orders.get(index);
        SimOrderLifecycle.requireTransition(prior.status(), status);
        orders.set(index, new SimOrder(prior.orderId(), prior.decisionTimestamp(),
                submit == null ? prior.submitTimestamp() : submit,
                fill == null ? prior.fillTimestamp() : fill,
                prior.side(), prior.targetLots(), prior.requestedUnits(), prior.orderType(), status, reason));
    }
}
