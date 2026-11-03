package com.mscient.intrapaper.book;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Small deterministic price/time-priority limit book for offline experiments.
 * Prices are positive integer ticks and quantities are positive integer units.
 * This is a toy model, not an exchange matching engine or a claim about NSE rules.
 * Instances are single-writer and are not thread-safe.
 */
public final class ToyLimitOrderBook {
    public enum Side { BUY, SELL }
    public enum OrderType { LIMIT, MARKET_IOC }
    public enum OrderStatus { WORKING, PARTIALLY_FILLED, FILLED, CANCELED }

    public record OrderRequest(String orderId, Side side, OrderType type, long quantity,
            OptionalLong limitPriceTicks) {
        public OrderRequest {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(limitPriceTicks, "limitPriceTicks");
            if (orderId.isBlank() || !orderId.equals(orderId.strip())) {
                throw new IllegalArgumentException("orderId must be non-blank and trimmed");
            }
            if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
            if (type == OrderType.LIMIT) {
                if (limitPriceTicks.isEmpty() || limitPriceTicks.getAsLong() <= 0) {
                    throw new IllegalArgumentException("LIMIT requires a positive limitPriceTicks");
                }
            } else if (limitPriceTicks.isPresent()) {
                throw new IllegalArgumentException("MARKET_IOC must not include a limit price");
            }
        }
    }

    public record OrderSnapshot(String orderId, Side side, OrderType type, OptionalLong limitPriceTicks,
            long originalQuantity, long filledQuantity, long remainingQuantity, OrderStatus status,
            long prioritySequence) {
        public OrderSnapshot {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(limitPriceTicks, "limitPriceTicks");
            Objects.requireNonNull(status, "status");
        }
    }

    public record Trade(long tradeSequence, String buyOrderId, String sellOrderId,
            String aggressorOrderId, long priceTicks, long quantity) {
        public Trade {
            if (tradeSequence <= 0 || priceTicks <= 0 || quantity <= 0) {
                throw new IllegalArgumentException("trade sequence, price, and quantity must be positive");
            }
            Objects.requireNonNull(buyOrderId, "buyOrderId");
            Objects.requireNonNull(sellOrderId, "sellOrderId");
            Objects.requireNonNull(aggressorOrderId, "aggressorOrderId");
        }
    }

    public record Submission(OrderSnapshot order, List<Trade> trades) {
        public Submission {
            Objects.requireNonNull(order, "order");
            trades = List.copyOf(trades);
        }
    }

    public record CancelResult(boolean canceled, Optional<OrderSnapshot> order) {
        public CancelResult {
            order = Objects.requireNonNull(order, "order");
        }
    }

    public record PriceLevel(long priceTicks, long totalQuantity, int orderCount) {
        public PriceLevel {
            if (priceTicks <= 0 || totalQuantity <= 0 || orderCount <= 0) {
                throw new IllegalArgumentException("price level fields must be positive");
            }
        }
    }

    public record BookSnapshot(List<PriceLevel> bids, List<PriceLevel> asks,
            long lastPrioritySequence, long lastTradeSequence) {
        public BookSnapshot {
            bids = List.copyOf(bids);
            asks = List.copyOf(asks);
        }
    }

    private static final class MutableOrder {
        private final String orderId;
        private final Side side;
        private final OrderType type;
        private final OptionalLong limitPriceTicks;
        private final long originalQuantity;
        private final long prioritySequence;
        private long filledQuantity;
        private long remainingQuantity;
        private OrderStatus status;

        private MutableOrder(OrderRequest request, long prioritySequence) {
            this.orderId = request.orderId();
            this.side = request.side();
            this.type = request.type();
            this.limitPriceTicks = request.limitPriceTicks();
            this.originalQuantity = request.quantity();
            this.remainingQuantity = request.quantity();
            this.prioritySequence = prioritySequence;
            this.status = OrderStatus.WORKING;
        }

        private OrderSnapshot snapshot() {
            return new OrderSnapshot(orderId, side, type, limitPriceTicks, originalQuantity,
                    filledQuantity, remainingQuantity, status, prioritySequence);
        }
    }

    private final NavigableMap<Long, ArrayDeque<MutableOrder>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Long, ArrayDeque<MutableOrder>> asks = new TreeMap<>();
    private final Map<String, MutableOrder> ordersById = new LinkedHashMap<>();
    private long lastPrioritySequence;
    private long lastTradeSequence;

    /** Matches an incoming order against the resting opposite side, then rests an unfilled limit remainder. */
    public Submission submit(OrderRequest request) {
        Objects.requireNonNull(request, "request");
        if (ordersById.containsKey(request.orderId())) {
            throw new IllegalArgumentException("Duplicate orderId: " + request.orderId());
        }
        long prioritySequence = Math.incrementExact(lastPrioritySequence);
        MutableOrder incoming = new MutableOrder(request, prioritySequence);
        List<Trade> trades = new ArrayList<>();
        match(incoming, trades);

        if (incoming.remainingQuantity > 0) {
            if (incoming.type == OrderType.MARKET_IOC) {
                incoming.status = OrderStatus.CANCELED;
            } else {
                rest(incoming);
                incoming.status = incoming.filledQuantity > 0
                        ? OrderStatus.PARTIALLY_FILLED : OrderStatus.WORKING;
            }
        } else {
            incoming.status = OrderStatus.FILLED;
        }
        lastPrioritySequence = prioritySequence;
        ordersById.put(incoming.orderId, incoming);
        return new Submission(incoming.snapshot(), trades);
    }

    /** Cancels only the remaining quantity of a working order. Filled and unknown orders are unchanged. */
    public CancelResult cancel(String orderId) {
        Objects.requireNonNull(orderId, "orderId");
        MutableOrder order = ordersById.get(orderId);
        if (order == null) return new CancelResult(false, Optional.empty());
        if (order.status != OrderStatus.WORKING && order.status != OrderStatus.PARTIALLY_FILLED) {
            return new CancelResult(false, Optional.of(order.snapshot()));
        }
        NavigableMap<Long, ArrayDeque<MutableOrder>> levels = levelsFor(order.side);
        long price = order.limitPriceTicks.orElseThrow();
        ArrayDeque<MutableOrder> queue = levels.get(price);
        if (queue == null || !queue.remove(order)) {
            throw new IllegalStateException("Working order is missing from its price level: " + orderId);
        }
        if (queue.isEmpty()) levels.remove(price);
        order.status = OrderStatus.CANCELED;
        return new CancelResult(true, Optional.of(order.snapshot()));
    }

    public Optional<OrderSnapshot> order(String orderId) {
        Objects.requireNonNull(orderId, "orderId");
        return Optional.ofNullable(ordersById.get(orderId)).map(MutableOrder::snapshot);
    }

    public BookSnapshot snapshot() {
        return new BookSnapshot(snapshotLevels(bids), snapshotLevels(asks),
                lastPrioritySequence, lastTradeSequence);
    }

    private void match(MutableOrder incoming, List<Trade> trades) {
        NavigableMap<Long, ArrayDeque<MutableOrder>> opposite = levelsFor(opposite(incoming.side));
        while (incoming.remainingQuantity > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, ArrayDeque<MutableOrder>> best = opposite.firstEntry();
            long restingPrice = best.getKey();
            if (incoming.type == OrderType.LIMIT && !crosses(incoming, restingPrice)) break;

            ArrayDeque<MutableOrder> queue = best.getValue();
            MutableOrder resting = queue.peekFirst();
            if (resting == null || resting.remainingQuantity <= 0) {
                throw new IllegalStateException("Book contains an empty or exhausted resting queue");
            }
            long quantity = Math.min(incoming.remainingQuantity, resting.remainingQuantity);
            incoming.remainingQuantity = Math.subtractExact(incoming.remainingQuantity, quantity);
            incoming.filledQuantity = Math.addExact(incoming.filledQuantity, quantity);
            resting.remainingQuantity = Math.subtractExact(resting.remainingQuantity, quantity);
            resting.filledQuantity = Math.addExact(resting.filledQuantity, quantity);
            resting.status = resting.remainingQuantity == 0
                    ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;

            long tradeSequence = Math.incrementExact(lastTradeSequence);
            lastTradeSequence = tradeSequence;
            String buyId = incoming.side == Side.BUY ? incoming.orderId : resting.orderId;
            String sellId = incoming.side == Side.SELL ? incoming.orderId : resting.orderId;
            trades.add(new Trade(tradeSequence, buyId, sellId, incoming.orderId, restingPrice, quantity));

            if (resting.remainingQuantity == 0) {
                queue.removeFirst();
                if (queue.isEmpty()) opposite.remove(restingPrice);
            }
        }
    }

    private void rest(MutableOrder order) {
        long price = order.limitPriceTicks.orElseThrow();
        levelsFor(order.side).computeIfAbsent(price, ignored -> new ArrayDeque<>()).addLast(order);
    }

    private boolean crosses(MutableOrder incoming, long restingPrice) {
        long limit = incoming.limitPriceTicks.orElseThrow();
        return incoming.side == Side.BUY ? restingPrice <= limit : restingPrice >= limit;
    }

    private NavigableMap<Long, ArrayDeque<MutableOrder>> levelsFor(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private static Side opposite(Side side) {
        return side == Side.BUY ? Side.SELL : Side.BUY;
    }

    private static List<PriceLevel> snapshotLevels(NavigableMap<Long, ArrayDeque<MutableOrder>> levels) {
        List<PriceLevel> result = new ArrayList<>(levels.size());
        for (Map.Entry<Long, ArrayDeque<MutableOrder>> level : levels.entrySet()) {
            long total = 0;
            int count = 0;
            for (MutableOrder order : level.getValue()) {
                total = Math.addExact(total, order.remainingQuantity);
                count = Math.incrementExact(count);
            }
            result.add(new PriceLevel(level.getKey(), total, count));
        }
        return List.copyOf(result);
    }
}
