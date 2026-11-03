package com.mscient.intrapaper.book;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic single-price call auction over caller-supplied integer ticks and units.
 * The caller supplies the order sequence and reference close. This engine has no session
 * clock, exchange adapter, margin gate, or effective-date selection and is not itself an
 * assertion that a given rule applied to a historical session.
 */
public final class CallAuctionEngine {
    public enum Side { BUY, SELL }
    public enum OrderType { LIMIT, MARKET }
    public enum RemainderTreatment {
        CARRY_LIMIT_WITH_PRIORITY,
        CONVERT_MARKET_TO_LIMIT_AT_EQUILIBRIUM,
        UNRESOLVED_MARKET_REMAINDER
    }

    public record Order(String orderId, Side side, OrderType type, long quantity,
            OptionalLong limitPriceTicks, long prioritySequence) {
        public Order {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(limitPriceTicks, "limitPriceTicks");
            if (orderId.isBlank() || !orderId.equals(orderId.strip())) {
                throw new IllegalArgumentException("orderId must be non-blank and trimmed");
            }
            if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
            if (prioritySequence <= 0) throw new IllegalArgumentException("prioritySequence must be positive");
            if (type == OrderType.LIMIT) {
                if (limitPriceTicks.isEmpty() || limitPriceTicks.getAsLong() <= 0) {
                    throw new IllegalArgumentException("LIMIT requires a positive limitPriceTicks");
                }
            } else if (limitPriceTicks.isPresent()) {
                throw new IllegalArgumentException("MARKET must not include a limit price");
            }
        }
    }

    public record Trade(long tradeSequence, String buyOrderId, String sellOrderId,
            long priceTicks, long quantity) {
        public Trade {
            if (tradeSequence <= 0 || priceTicks <= 0 || quantity <= 0) {
                throw new IllegalArgumentException("trade sequence, price, and quantity must be positive");
            }
            Objects.requireNonNull(buyOrderId, "buyOrderId");
            Objects.requireNonNull(sellOrderId, "sellOrderId");
        }
    }

    public record UnmatchedOrder(String orderId, Side side, OrderType submittedType, long quantity,
            OptionalLong continuousLimitPriceTicks, RemainderTreatment treatment,
            OptionalLong retainedPrioritySequence) {
        public UnmatchedOrder {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(submittedType, "submittedType");
            Objects.requireNonNull(continuousLimitPriceTicks, "continuousLimitPriceTicks");
            Objects.requireNonNull(treatment, "treatment");
            Objects.requireNonNull(retainedPrioritySequence, "retainedPrioritySequence");
            if (quantity <= 0) throw new IllegalArgumentException("unmatched quantity must be positive");
            if (continuousLimitPriceTicks.isPresent() && continuousLimitPriceTicks.getAsLong() <= 0) {
                throw new IllegalArgumentException("continuous limit price must be positive when present");
            }
            if (treatment == RemainderTreatment.CARRY_LIMIT_WITH_PRIORITY
                    && (submittedType != OrderType.LIMIT || continuousLimitPriceTicks.isEmpty()
                    || retainedPrioritySequence.isEmpty())) {
                throw new IllegalArgumentException("Carried limit remainder requires its limit price and priority");
            }
            if (treatment == RemainderTreatment.CONVERT_MARKET_TO_LIMIT_AT_EQUILIBRIUM
                    && (submittedType != OrderType.MARKET || continuousLimitPriceTicks.isEmpty()
                    || retainedPrioritySequence.isPresent())) {
                throw new IllegalArgumentException("Converted market remainder requires a price without retaining pre-auction priority");
            }
            if (treatment == RemainderTreatment.UNRESOLVED_MARKET_REMAINDER
                    && (submittedType != OrderType.MARKET || continuousLimitPriceTicks.isPresent()
                    || retainedPrioritySequence.isPresent())) {
                throw new IllegalArgumentException("Unresolved market remainder must not invent price or priority");
            }
        }
    }

    public record Result(OptionalLong equilibriumPriceTicks, long executableQuantity,
            long imbalanceQuantity, List<Trade> trades, List<UnmatchedOrder> unmatchedOrders) {
        public Result {
            Objects.requireNonNull(equilibriumPriceTicks, "equilibriumPriceTicks");
            trades = List.copyOf(trades);
            unmatchedOrders = List.copyOf(unmatchedOrders);
            if (executableQuantity < 0 || imbalanceQuantity < 0) {
                throw new IllegalArgumentException("auction quantities must be non-negative");
            }
        }
    }

    private static final class WorkingOrder {
        private final Order order;
        private long remaining;

        private WorkingOrder(Order order) {
            this.order = order;
            this.remaining = order.quantity();
        }
    }

    private record Candidate(long price, long executable, long imbalance) { }

    public Result clear(List<Order> orders, long previousCloseTicks) {
        Objects.requireNonNull(orders, "orders");
        if (previousCloseTicks <= 0) throw new IllegalArgumentException("previousCloseTicks must be positive");
        List<WorkingOrder> working = new ArrayList<>(orders.size());
        Set<String> ids = new HashSet<>();
        Set<Long> priorities = new HashSet<>();
        TreeSet<Long> candidatePrices = new TreeSet<>();
        candidatePrices.add(previousCloseTicks);
        for (Order order : orders) {
            Objects.requireNonNull(order, "order");
            if (!ids.add(order.orderId())) throw new IllegalArgumentException("Duplicate orderId: " + order.orderId());
            if (!priorities.add(order.prioritySequence())) {
                throw new IllegalArgumentException("prioritySequence values must be unique within one auction");
            }
            working.add(new WorkingOrder(order));
            order.limitPriceTicks().ifPresent(candidatePrices::add);
        }

        OptionalLong equilibrium = findEquilibrium(working, candidatePrices, previousCloseTicks);
        List<Trade> trades = new ArrayList<>();
        long imbalance = 0;
        if (equilibrium.isPresent()) {
            Candidate selected = score(working, equilibrium.getAsLong());
            imbalance = selected.imbalance();
            match(working, equilibrium.getAsLong(), trades);
        }

        List<UnmatchedOrder> unmatched = buildRemainders(working, equilibrium);
        long executed = 0;
        for (Trade trade : trades) executed = Math.addExact(executed, trade.quantity());
        if (equilibrium.isPresent()) {
            Candidate selected = score(working, equilibrium.getAsLong());
            if (executed != selected.executable()) {
                throw new IllegalStateException("Auction matching volume disagrees with clearing-price volume");
            }
        } else if (executed != 0) {
            throw new IllegalStateException("Auction without an equilibrium price must not execute trades");
        }
        return new Result(equilibrium, executed, imbalance, trades, unmatched);
    }

    private static OptionalLong findEquilibrium(List<WorkingOrder> orders, Set<Long> prices, long reference) {
        Candidate best = null;
        for (long price : prices) {
            Candidate candidate = score(orders, price);
            if (candidate.executable() == 0) continue;
            if (best == null || better(candidate, best, reference)) best = candidate;
        }
        return best == null ? OptionalLong.empty() : OptionalLong.of(best.price());
    }

    private static Candidate score(List<WorkingOrder> orders, long price) {
        long demand = 0;
        long supply = 0;
        for (WorkingOrder working : orders) {
            if (!eligible(working.order, price)) continue;
            if (working.order.side() == Side.BUY) demand = Math.addExact(demand, working.order.quantity());
            else supply = Math.addExact(supply, working.order.quantity());
        }
        return new Candidate(price, Math.min(demand, supply), Math.abs(demand - supply));
    }

    private static boolean better(Candidate candidate, Candidate current, long reference) {
        if (candidate.executable() != current.executable()) {
            return candidate.executable() > current.executable();
        }
        if (candidate.imbalance() != current.imbalance()) {
            return candidate.imbalance() < current.imbalance();
        }
        long candidateDistance = Math.abs(candidate.price() - reference);
        long currentDistance = Math.abs(current.price() - reference);
        if (candidateDistance != currentDistance) return candidateDistance < currentDistance;
        if ((candidate.price() == reference) != (current.price() == reference)) {
            return candidate.price() == reference;
        }
        return candidate.price() < current.price();
    }

    private static boolean eligible(Order order, long price) {
        if (order.type() == OrderType.MARKET) return true;
        long limit = order.limitPriceTicks().orElseThrow();
        return order.side() == Side.BUY ? limit >= price : limit <= price;
    }

    private static void match(List<WorkingOrder> orders, long price, List<Trade> trades) {
        Comparator<WorkingOrder> time = Comparator.comparingLong(value -> value.order.prioritySequence());
        List<WorkingOrder> marketBuys = orders.stream()
                .filter(value -> value.order.type() == OrderType.MARKET && value.order.side() == Side.BUY)
                .sorted(time).toList();
        List<WorkingOrder> marketSells = orders.stream()
                .filter(value -> value.order.type() == OrderType.MARKET && value.order.side() == Side.SELL)
                .sorted(time).toList();
        long[] sequence = {0};
        matchLists(marketBuys, marketSells, price, trades, sequence);

        List<WorkingOrder> limitBuys = eligibleLimits(orders, Side.BUY, price);
        List<WorkingOrder> limitSells = eligibleLimits(orders, Side.SELL, price);
        matchMarketAgainstLimits(marketBuys, limitSells, price, trades, sequence);
        matchMarketAgainstLimits(marketSells, limitBuys, price, trades, sequence);
        matchLists(limitBuys, limitSells, price, trades, sequence);
    }

    private static List<WorkingOrder> eligibleLimits(List<WorkingOrder> orders, Side side, long price) {
        Comparator<WorkingOrder> comparator = side == Side.BUY
                ? Comparator.<WorkingOrder>comparingLong(value -> value.order.limitPriceTicks().orElseThrow())
                        .reversed().thenComparingLong(value -> value.order.prioritySequence())
                : Comparator.<WorkingOrder>comparingLong(value -> value.order.limitPriceTicks().orElseThrow())
                        .thenComparingLong(value -> value.order.prioritySequence());
        return orders.stream()
                .filter(value -> value.order.type() == OrderType.LIMIT && value.order.side() == side
                        && eligible(value.order, price))
                .sorted(comparator).toList();
    }

    private static void matchMarketAgainstLimits(List<WorkingOrder> markets, List<WorkingOrder> limits,
            long price, List<Trade> trades, long[] sequence) {
        int limitIndex = 0;
        for (WorkingOrder market : markets) {
            while (market.remaining > 0 && limitIndex < limits.size()) {
                WorkingOrder limit = limits.get(limitIndex);
                if (limit.remaining == 0) {
                    limitIndex++;
                    continue;
                }
                matchPair(market, limit, price, trades, sequence);
            }
        }
    }

    private static void matchLists(List<WorkingOrder> buys, List<WorkingOrder> sells, long price,
            List<Trade> trades, long[] sequence) {
        int buyIndex = 0;
        int sellIndex = 0;
        while (buyIndex < buys.size() && sellIndex < sells.size()) {
            WorkingOrder buy = buys.get(buyIndex);
            WorkingOrder sell = sells.get(sellIndex);
            if (buy.remaining == 0) {
                buyIndex++;
                continue;
            }
            if (sell.remaining == 0) {
                sellIndex++;
                continue;
            }
            matchPair(buy, sell, price, trades, sequence);
        }
    }

    private static void matchPair(WorkingOrder first, WorkingOrder second, long price,
            List<Trade> trades, long[] sequence) {
        WorkingOrder buy = first.order.side() == Side.BUY ? first : second;
        WorkingOrder sell = first.order.side() == Side.SELL ? first : second;
        if (buy.order.side() != Side.BUY || sell.order.side() != Side.SELL) {
            throw new IllegalArgumentException("Auction matching requires one buy and one sell order");
        }
        long quantity = Math.min(buy.remaining, sell.remaining);
        buy.remaining = Math.subtractExact(buy.remaining, quantity);
        sell.remaining = Math.subtractExact(sell.remaining, quantity);
        trades.add(new Trade(Math.incrementExact(sequence[0]), buy.order.orderId(), sell.order.orderId(),
                price, quantity));
    }

    private static List<UnmatchedOrder> buildRemainders(List<WorkingOrder> orders, OptionalLong equilibrium) {
        List<WorkingOrder> inInputOrder = orders.stream()
                .sorted(Comparator.comparingLong(value -> value.order.prioritySequence())).toList();
        List<UnmatchedOrder> result = new ArrayList<>();
        for (WorkingOrder working : inInputOrder) {
            if (working.remaining == 0) continue;
            Order order = working.order;
            if (order.type() == OrderType.LIMIT) {
                result.add(new UnmatchedOrder(order.orderId(), order.side(), order.type(), working.remaining,
                        order.limitPriceTicks(), RemainderTreatment.CARRY_LIMIT_WITH_PRIORITY,
                        OptionalLong.of(order.prioritySequence())));
            } else if (equilibrium.isPresent()) {
                result.add(new UnmatchedOrder(order.orderId(), order.side(), order.type(), working.remaining,
                        equilibrium, RemainderTreatment.CONVERT_MARKET_TO_LIMIT_AT_EQUILIBRIUM,
                        OptionalLong.empty()));
            } else {
                result.add(new UnmatchedOrder(order.orderId(), order.side(), order.type(), working.remaining,
                        OptionalLong.empty(), RemainderTreatment.UNRESOLVED_MARKET_REMAINDER,
                        OptionalLong.empty()));
            }
        }
        return List.copyOf(result);
    }
}
