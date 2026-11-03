package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.marketdata.MarketQuote;
import com.mscient.intrapaper.marketdata.QuoteExecutionGate;
import com.mscient.intrapaper.marketdata.QuoteQualityPolicy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Offline L1 quote replay for marketable IOC orders; this class has no network or broker adapter. */
public final class QuoteOrderReplay {
    public static final String MODEL_ID = "L1_TOP_OF_BOOK_IOC_SNAPSHOT_V1";

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
        Instant arrival;
        try {
            arrival = intent.decisionTimestamp().plus(intent.decisionToArrivalLatency());
        } catch (RuntimeException invalidLatency) {
            throw new IllegalArgumentException("decision-to-arrival time is outside the supported Instant range", invalidLatency);
        }

        List<StateEvent> events = new ArrayList<>();
        events.add(new StateEvent(intent.decisionTimestamp(), OmsOrderState.CREATED, "INTENT_CREATED"));
        OmsOrderState current = transition(events, OmsOrderState.CREATED, OmsOrderState.PENDING_NEW,
                intent.decisionTimestamp(), "SIMULATED_SUBMISSION");
        MarketQuote quote = latestAsOf(quoteHistory, intent, arrival);
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
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must be non-empty and trimmed");
        }
        return value;
    }
}
