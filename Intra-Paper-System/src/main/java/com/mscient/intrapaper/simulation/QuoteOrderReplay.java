package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.marketdata.MarketQuote;
import com.mscient.intrapaper.marketdata.QuoteExecutionGate;
import com.mscient.intrapaper.marketdata.QuoteQualityPolicy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Offline L1 quote replay for marketable IOC orders; this class has no network or broker adapter. */
public final class QuoteOrderReplay {
    public static final String MODEL_ID = "L1_TOP_OF_BOOK_IOC_SNAPSHOT_V1";
    public static final String BATCH_MODEL_ID = "L1_TOP_OF_BOOK_IOC_SNAPSHOT_DEPLETION_V1";

    public enum Type { MARKET_IOC, LIMIT_IOC }
    public enum Status { FILLED, PARTIALLY_FILLED, MISSED }

    public record Intent(String orderId, String source, String instrumentId, Instant decisionTimestamp,
            Duration decisionToArrivalLatency, ExecutionEvent.Side side, Type type, int requestedUnits,
            OptionalLong limitPriceTicks, int adverseSlippageTicks, BigDecimal tickSize) {
        public Intent {
            if (orderId == null || !orderId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                throw new IllegalArgumentException("orderId must be a stable 1-128 character token");
            }
            source = requireTrimmed(source, "source");
            instrumentId = requireTrimmed(instrumentId, "instrumentId");
            Objects.requireNonNull(decisionTimestamp, "decisionTimestamp");
            Objects.requireNonNull(decisionToArrivalLatency, "decisionToArrivalLatency");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(type, "type");
            limitPriceTicks = Objects.requireNonNull(limitPriceTicks, "limitPriceTicks");
            tickSize = Objects.requireNonNull(tickSize, "tickSize").stripTrailingZeros();
            if (decisionToArrivalLatency.isNegative()) {
                throw new IllegalArgumentException("decisionToArrivalLatency must be non-negative");
            }
            if (requestedUnits < 1) throw new IllegalArgumentException("requestedUnits must be positive");
            if (adverseSlippageTicks < 0) throw new IllegalArgumentException("adverseSlippageTicks must be non-negative");
            if (tickSize.signum() <= 0) throw new IllegalArgumentException("tickSize must be positive");
            if (type == Type.MARKET_IOC && limitPriceTicks.isPresent()) {
                throw new IllegalArgumentException("MARKET_IOC cannot have a limit price");
            }
            if (type == Type.LIMIT_IOC && (limitPriceTicks.isEmpty() || limitPriceTicks.getAsLong() <= 0)) {
                throw new IllegalArgumentException("LIMIT_IOC requires a positive limit price in ticks");
            }
        }
    }

    public record StateEvent(Instant timestamp, OmsOrderState state, String reason) {
        public StateEvent {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(state, "state");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("state event reason is required");
        }
    }

    public record Outcome(String modelId, Intent intent, Instant arrivalTimestamp, Status status,
            OmsOrderState finalState, int filledUnits, int remainingUnits,
            OptionalLong quoteSequence, Optional<Instant> quoteReceivedAt,
            OptionalLong referencePriceTicks, OptionalLong fillPriceTicks,
            Optional<ExecutionEvent> execution, List<StateEvent> stateEvents, String reason) {
        public Outcome {
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(intent, "intent");
            Objects.requireNonNull(arrivalTimestamp, "arrivalTimestamp");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(finalState, "finalState");
            quoteSequence = Objects.requireNonNull(quoteSequence, "quoteSequence");
            quoteReceivedAt = Objects.requireNonNull(quoteReceivedAt, "quoteReceivedAt");
            referencePriceTicks = Objects.requireNonNull(referencePriceTicks, "referencePriceTicks");
            fillPriceTicks = Objects.requireNonNull(fillPriceTicks, "fillPriceTicks");
            execution = Objects.requireNonNull(execution, "execution");
            stateEvents = List.copyOf(stateEvents);
            if (filledUnits < 0 || remainingUnits < 0
                    || (long) filledUnits + remainingUnits != intent.requestedUnits()) {
                throw new IllegalArgumentException("filled and remaining units must equal the requested amount");
            }
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("outcome reason is required");
            if (execution.isPresent() != (filledUnits > 0)
                    || fillPriceTicks.isPresent() != execution.isPresent()) {
                throw new IllegalArgumentException("execution and fill price must agree with filled quantity");
            }
            if ((status == Status.FILLED) != (finalState == OmsOrderState.FILLED)
                    || status == Status.FILLED && remainingUnits != 0
                    || status == Status.PARTIALLY_FILLED
                            && (finalState != OmsOrderState.CANCELED || filledUnits == 0 || remainingUnits == 0)
                    || status == Status.MISSED && (filledUnits != 0
                            || (finalState != OmsOrderState.CANCELED && finalState != OmsOrderState.REJECTED))) {
                throw new IllegalArgumentException("outcome quantity and final OMS state are inconsistent");
            }
            if (stateEvents.isEmpty() || stateEvents.getLast().state() != finalState) {
                throw new IllegalArgumentException("state event history must end in finalState");
            }
            if (quoteSequence.isPresent() != quoteReceivedAt.isPresent()) {
                throw new IllegalArgumentException("selected quote sequence and receive time must be paired");
            }
            if (quoteSequence.isPresent() && quoteSequence.getAsLong() < 1) {
                throw new IllegalArgumentException("selected quote sequence must be positive");
            }
            if (execution.isPresent()) {
                ExecutionEvent fill = execution.orElseThrow();
                BigDecimal expectedPrice = intent.tickSize().multiply(BigDecimal.valueOf(fillPriceTicks.orElseThrow()));
                if (!fill.orderId().equals(intent.orderId()) || fill.side() != intent.side()
                        || fill.quantityUnits() != filledUnits || !fill.timestamp().equals(arrivalTimestamp)
                        || fill.price().compareTo(expectedPrice) != 0) {
                    throw new IllegalArgumentException("execution details do not match quote replay outcome");
                }
            }
        }
    }

    private QuoteOrderReplay() { }

    /**
     * Replays a batch in arrival-time order and makes each snapshot's displayed side size available
     * only once until another quote snapshot is selected. Input order breaks equal-arrival ties;
     * returned outcomes retain input order. This still does not model hidden liquidity, queueing,
     * market impact, or whether displayed size persists until arrival.
     */
    public static List<Outcome> executeBatch(List<MarketQuote> quoteHistory, List<Intent> intents,
            QuoteQualityPolicy qualityPolicy) {
        Objects.requireNonNull(quoteHistory, "quoteHistory");
        Objects.requireNonNull(intents, "intents");
        Objects.requireNonNull(qualityPolicy, "qualityPolicy");
        List<Intent> stableIntents = List.copyOf(intents);
        Set<String> orderIds = new HashSet<>();
        for (Intent intent : stableIntents) {
            if (!orderIds.add(intent.orderId())) throw new IllegalArgumentException("Batch order IDs must be unique");
        }
        QuoteHistoryIndex quoteIndex = new QuoteHistoryIndex(quoteHistory);
        List<Integer> arrivalOrder = new ArrayList<>(stableIntents.size());
        for (int index = 0; index < stableIntents.size(); index++) arrivalOrder.add(index);
        arrivalOrder.sort(Comparator.comparing((Integer index) -> arrivalTime(stableIntents.get(index)))
                .thenComparingInt(Integer::intValue));

        Outcome[] outcomes = new Outcome[stableIntents.size()];
        Map<QuoteConsumptionKey, Long> remainingByQuote = new HashMap<>();
        for (int index : arrivalOrder) {
            Intent intent = stableIntents.get(index);
            Instant arrival = arrivalTime(intent);
            MarketQuote quote = quoteIndex.latestAsOf(intent, arrival);
            Outcome outcome;
            if (quote == null) {
                outcome = executeSelected(null, intent, qualityPolicy, arrival, null);
            } else {
                QuoteConsumptionKey key = new QuoteConsumptionKey(quote.source(), quote.instrumentId(),
                        quote.sequenceNumber(), intent.side());
                long available = remainingByQuote.computeIfAbsent(key, ignored -> intent.side() == ExecutionEvent.Side.BUY
                        ? quote.askQuantity() : quote.bidQuantity());
                outcome = executeSelected(quote, intent, qualityPolicy, arrival, available);
                remainingByQuote.put(key, available - outcome.filledUnits());
            }
            outcomes[index] = withModelId(outcome, BATCH_MODEL_ID);
        }
        return List.of(outcomes);
    }

    /**
     * Evaluates a single IOC intent against the latest matching L1 snapshot observable by its
     * caller-supplied arrival time. At most the displayed top-level quantity is filled; any
     * residual is canceled. A non-marketable limit is missed because quote snapshots contain no
     * trade tape from which a passive queue fill could be established. This is a per-intent
     * snapshot calculation: it does not reserve displayed size across separate intents or model
     * quote depletion, so it is not a portfolio-capacity or conservative fill lower-bound model.
     */
    public static Outcome execute(List<MarketQuote> quoteHistory, Intent intent, QuoteQualityPolicy qualityPolicy) {
        Objects.requireNonNull(quoteHistory, "quoteHistory");
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(qualityPolicy, "qualityPolicy");
        Instant arrival = arrivalTime(intent);
        return executeSelected(latestAsOf(quoteHistory, intent, arrival), intent, qualityPolicy, arrival, null);
    }

    private static Outcome executeSelected(MarketQuote quote, Intent intent, QuoteQualityPolicy qualityPolicy,
            Instant arrival, Long displayedUnitsLimit) {
        List<StateEvent> events = new ArrayList<>();
        events.add(new StateEvent(intent.decisionTimestamp(), OmsOrderState.CREATED, "INTENT_CREATED"));
        OmsOrderState current = transition(events, OmsOrderState.CREATED, OmsOrderState.PENDING_NEW,
                intent.decisionTimestamp(), "SIMULATED_SUBMISSION");
        if (quote == null) {
            current = transition(events, current, OmsOrderState.WORKING, arrival, "NO_MATCHING_QUOTE_AT_ARRIVAL");
            current = transition(events, current, OmsOrderState.CANCELED, arrival, "IOC_NO_QUOTE");
            return outcome(intent, arrival, Status.MISSED, current, 0, intent.requestedUnits(), null,
                    OptionalLong.empty(), OptionalLong.empty(), Optional.empty(), events, "NO_MATCHING_QUOTE_AT_ARRIVAL");
        }

        OptionalLong quoteSequence = OptionalLong.of(quote.sequenceNumber());
        Optional<Instant> quoteReceivedAt = Optional.of(quote.receivedAtUtc());
        QuoteExecutionGate.Side gateSide = intent.side() == ExecutionEvent.Side.BUY
                ? QuoteExecutionGate.Side.BUY : QuoteExecutionGate.Side.SELL;
        long referenceTicks = gateSide == QuoteExecutionGate.Side.BUY
                ? quote.askPriceTicks() : quote.bidPriceTicks();
        OptionalLong reference = OptionalLong.of(referenceTicks);
        var qualityFlags = qualityPolicy.assess(quote, arrival);
        if (!qualityFlags.isEmpty()) {
            current = transition(events, current, OmsOrderState.REJECTED, arrival,
                    "QUOTE_QUALITY_FLAGS:" + qualityFlags.stream().map(Enum::name).sorted().toList());
            return outcome(intent, arrival, Status.MISSED, current, 0, intent.requestedUnits(), quote,
                    reference, OptionalLong.empty(), Optional.empty(), events, "QUOTE_QUALITY_FLAGS");
        }

        long displayed = gateSide == QuoteExecutionGate.Side.BUY ? quote.askQuantity() : quote.bidQuantity();
        if (displayedUnitsLimit != null) displayed = Math.min(displayed, displayedUnitsLimit);
        if (intent.type() == Type.LIMIT_IOC && !marketable(intent, referenceTicks)) {
            current = transition(events, current, OmsOrderState.WORKING, arrival, "LIMIT_NOT_MARKETABLE_AT_ARRIVAL");
            current = transition(events, current, OmsOrderState.CANCELED, arrival, "IOC_LIMIT_REMAINDER_CANCELED");
            return outcome(intent, arrival, Status.MISSED, current, 0, intent.requestedUnits(), quote,
                    reference, OptionalLong.empty(), Optional.empty(), events, "LIMIT_NOT_MARKETABLE_AT_ARRIVAL");
        }
        if (displayed < 1) {
            current = transition(events, current, OmsOrderState.REJECTED, arrival, "NO_DISPLAYED_TOP_OF_BOOK_SIZE");
            return outcome(intent, arrival, Status.MISSED, current, 0, intent.requestedUnits(), quote,
                    reference, OptionalLong.empty(), Optional.empty(), events, "NO_DISPLAYED_TOP_OF_BOOK_SIZE");
        }

        int fillUnits = (int) Math.min((long) intent.requestedUnits(), displayed);
        QuoteExecutionGate.Assessment assessment = QuoteExecutionGate.assess(quote, intent.instrumentId(), arrival,
                gateSide, fillUnits, intent.adverseSlippageTicks(), qualityPolicy);
        if (!assessment.executable()) {
            current = transition(events, current, OmsOrderState.REJECTED, arrival, assessment.reason());
            return outcome(intent, arrival, Status.MISSED, current, 0, intent.requestedUnits(), quote,
                    reference, OptionalLong.empty(), Optional.empty(), events, assessment.reason());
        }
        long fillTicks = assessment.modeledFillPriceTicks().orElseThrow();
        if (intent.type() == Type.LIMIT_IOC && violatesLimit(intent, fillTicks)) {
            current = transition(events, current, OmsOrderState.WORKING, arrival, "LIMIT_PRICE_PROTECTION_BLOCKED_SLIPPAGE");
            current = transition(events, current, OmsOrderState.CANCELED, arrival, "IOC_LIMIT_REMAINDER_CANCELED");
            return outcome(intent, arrival, Status.MISSED, current, 0, intent.requestedUnits(), quote,
                    reference, OptionalLong.empty(), Optional.empty(), events, "LIMIT_PRICE_PROTECTION_BLOCKED_SLIPPAGE");
        }

        current = transition(events, current, OmsOrderState.WORKING, arrival, "IOC_ARRIVED_AT_TOP_OF_BOOK");
        BigDecimal fillPrice = intent.tickSize().multiply(BigDecimal.valueOf(fillTicks)).stripTrailingZeros();
        ExecutionEvent execution = new ExecutionEvent(intent.orderId() + "-FILL-1", intent.orderId(), arrival,
                intent.side(), fillUnits, fillPrice);
        if (fillUnits == intent.requestedUnits()) {
            current = transition(events, current, OmsOrderState.FILLED, arrival, "FULL_TOP_OF_BOOK_FILL");
            return outcome(intent, arrival, Status.FILLED, current, fillUnits, 0, quote,
                    reference, OptionalLong.of(fillTicks), Optional.of(execution), events, "FULL_TOP_OF_BOOK_FILL");
        }
        current = transition(events, current, OmsOrderState.PARTIALLY_FILLED, arrival, "DISPLAYED_TOP_SIZE_LIMIT");
        current = transition(events, current, OmsOrderState.CANCELED, arrival, "IOC_REMAINDER_CANCELED");
        return outcome(intent, arrival, Status.PARTIALLY_FILLED, current, fillUnits,
                intent.requestedUnits() - fillUnits, quote, reference, OptionalLong.of(fillTicks),
                Optional.of(execution), events, "TOP_OF_BOOK_PARTIAL_REMAINDER_CANCELED");
    }

    private static MarketQuote latestAsOf(List<MarketQuote> history, Intent intent, Instant arrival) {
        MarketQuote latest = null;
        for (MarketQuote quote : history) {
            Objects.requireNonNull(quote, "quoteHistory element");
            if (!quote.source().equals(intent.source()) || !quote.instrumentId().equals(intent.instrumentId())
                    || quote.receivedAtUtc().isAfter(arrival)
                    || quote.exchangeTimestampUtc().filter(time -> time.isAfter(arrival)).isPresent()) {
                continue;
            }
            if (latest == null || quote.receivedAtUtc().isAfter(latest.receivedAtUtc())
                    || quote.receivedAtUtc().equals(latest.receivedAtUtc())
                            && quote.sequenceNumber() > latest.sequenceNumber()) {
                latest = quote;
            }
        }
        return latest;
    }

    private static Instant arrivalTime(Intent intent) {
        try {
            return intent.decisionTimestamp().plus(intent.decisionToArrivalLatency());
        } catch (RuntimeException invalidLatency) {
            throw new IllegalArgumentException("decision-to-arrival time is outside the supported Instant range", invalidLatency);
        }
    }

    private static Outcome withModelId(Outcome outcome, String modelId) {
        return new Outcome(modelId, outcome.intent(), outcome.arrivalTimestamp(), outcome.status(), outcome.finalState(),
                outcome.filledUnits(), outcome.remainingUnits(), outcome.quoteSequence(), outcome.quoteReceivedAt(),
                outcome.referencePriceTicks(), outcome.fillPriceTicks(), outcome.execution(), outcome.stateEvents(),
                outcome.reason());
    }

    private static boolean marketable(Intent intent, long referenceTicks) {
        long limit = intent.limitPriceTicks().orElseThrow();
        return intent.side() == ExecutionEvent.Side.BUY ? referenceTicks <= limit : referenceTicks >= limit;
    }

    private static boolean violatesLimit(Intent intent, long fillTicks) {
        long limit = intent.limitPriceTicks().orElseThrow();
        return intent.side() == ExecutionEvent.Side.BUY ? fillTicks > limit : fillTicks < limit;
    }

    private static OmsOrderState transition(List<StateEvent> events, OmsOrderState from,
            OmsOrderState to, Instant timestamp, String reason) {
        OmsOrderStateMachine.requireTransition(from, to);
        events.add(new StateEvent(timestamp, to, reason));
        return to;
    }

    private static Outcome outcome(Intent intent, Instant arrival, Status status, OmsOrderState state,
            int filled, int remaining, MarketQuote quote, OptionalLong reference, OptionalLong fillPrice,
            Optional<ExecutionEvent> execution, List<StateEvent> events, String reason) {
        return new Outcome(MODEL_ID, intent, arrival, status, state, filled, remaining,
                quote == null ? OptionalLong.empty() : OptionalLong.of(quote.sequenceNumber()),
                quote == null ? Optional.empty() : Optional.of(quote.receivedAtUtc()),
                reference, fillPrice, execution, events, reason);
    }

    private static String requireTrimmed(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " must be non-empty, trimmed, and free of control characters");
        }
        return value;
    }

    private record QuoteConsumptionKey(String source, String instrumentId, long sequence,
            ExecutionEvent.Side side) { }

    private record QuoteStreamKey(String source, String instrumentId) { }

    /** Binary searchable journal-order-independent quote index used for multi-intent replay. */
    private static final class QuoteHistoryIndex {
        private static final Comparator<MarketQuote> RECEIVED_ORDER = Comparator
                .comparing(MarketQuote::receivedAtUtc).thenComparingLong(MarketQuote::sequenceNumber);

        private final Map<QuoteStreamKey, List<MarketQuote>> byStream;

        private QuoteHistoryIndex(List<MarketQuote> history) {
            Map<QuoteStreamKey, List<MarketQuote>> grouped = new HashMap<>();
            for (MarketQuote quote : history) {
                Objects.requireNonNull(quote, "quoteHistory element");
                QuoteStreamKey key = new QuoteStreamKey(quote.source(), quote.instrumentId());
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(quote);
            }
            grouped.values().forEach(quotes -> quotes.sort(RECEIVED_ORDER));
            Map<QuoteStreamKey, List<MarketQuote>> stable = new HashMap<>();
            grouped.forEach((key, quotes) -> stable.put(key, List.copyOf(quotes)));
            byStream = Map.copyOf(stable);
        }

        private MarketQuote latestAsOf(Intent intent, Instant arrival) {
            List<MarketQuote> quotes = byStream.get(new QuoteStreamKey(intent.source(), intent.instrumentId()));
            if (quotes == null) return null;
            int low = 0;
            int high = quotes.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (!quotes.get(middle).receivedAtUtc().isAfter(arrival)) low = middle + 1;
                else high = middle;
            }
            for (int index = low - 1; index >= 0; index--) {
                MarketQuote candidate = quotes.get(index);
                if (candidate.exchangeTimestampUtc().filter(time -> time.isAfter(arrival)).isEmpty()) {
                    return candidate;
                }
            }
            return null;
        }
    }
}
