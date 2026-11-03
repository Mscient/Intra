package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.marketdata.MarketQuote;
import com.mscient.intrapaper.marketdata.QuoteExecutionGate;
import com.mscient.intrapaper.marketdata.QuoteQualityPolicy;
import com.mscient.intrapaper.marketdata.VerifiedQuoteHistory;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Stateful, deterministic L1 IOC consumption for one replay run. */
final class QuoteExecutionBook {
    record Decision(Optional<MarketQuote> quote, Optional<QuoteExecutionGate.Assessment> assessment,
            long filledUnits, String reason) {
        Decision {
            quote = Objects.requireNonNull(quote, "quote");
            assessment = Objects.requireNonNull(assessment, "assessment");
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("reason is required");
            if (quote.isPresent() != assessment.isPresent() || filledUnits < 0
                    || filledUnits > 0 && (quote.isEmpty() || !assessment.orElseThrow().executable())) {
                throw new IllegalArgumentException("quote decision reference and quantity are inconsistent");
            }
        }
    }

    private record StreamKey(String source, String instrumentId) { }
    private record SideKey(String source, String instrumentId, long sequence, ExecutionEvent.Side side) { }

    private static final Comparator<MarketQuote> RECEIVED_ORDER = Comparator
            .comparing(MarketQuote::receivedAtUtc).thenComparingLong(MarketQuote::sequenceNumber);

    private final String source;
    private final String instrumentId;
    private final QuoteQualityPolicy qualityPolicy;
    private final Map<StreamKey, List<MarketQuote>> byStream;
    private final Map<SideKey, Long> remainingByQuoteSide = new HashMap<>();

    QuoteExecutionBook(VerifiedQuoteHistory history, String source, String instrumentId,
            QuoteQualityPolicy qualityPolicy) {
        Objects.requireNonNull(history, "history");
        this.source = requireNonBlank(source, "source");
        this.instrumentId = requireNonBlank(instrumentId, "instrumentId");
        this.qualityPolicy = Objects.requireNonNull(qualityPolicy, "qualityPolicy");
        List<MarketQuote> matching = history.quotes().stream()
                .filter(quote -> quote.source().equals(source) && quote.instrumentId().equals(instrumentId))
                .sorted(RECEIVED_ORDER).toList();
        this.byStream = Map.of(new StreamKey(source, instrumentId), matching);
    }

    Decision evaluate(Instant arrival, ExecutionEvent.Side side, long requestedUnits, int lotSize,
            int adverseSlippageTicks, com.mscient.intrapaper.provenance.ExecutionModel model) {
        Objects.requireNonNull(arrival, "arrival");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(model, "model");
        if (requestedUnits < 1 || lotSize < 1 || requestedUnits % lotSize != 0) {
            throw new IllegalArgumentException("Quote IOC requested size must be a positive whole number of market lots");
        }
        MarketQuote quote = latestAsOf(arrival);
        if (quote == null) return new Decision(Optional.empty(), Optional.empty(), 0, "NO_ELIGIBLE_QUOTE");

        SideKey key = new SideKey(source, instrumentId, quote.sequenceNumber(), side);
        long originalDisplayed = side == ExecutionEvent.Side.BUY ? quote.askQuantity() : quote.bidQuantity();
        long available = remainingByQuoteSide.getOrDefault(key, originalDisplayed);
        MarketQuote availableQuote = withAvailableSideQuantity(quote, side, available);
        long assessmentUnits = requestedUnits;
        if (model == com.mscient.intrapaper.provenance.ExecutionModel.L1_PARTIAL_IOC) {
            long displayedLots = available / lotSize;
            long requestedLots = requestedUnits / lotSize;
            assessmentUnits = Math.min(displayedLots, requestedLots) * lotSize;
            if (assessmentUnits == 0) {
                QuoteExecutionGate.Side noFillSide = side == ExecutionEvent.Side.BUY
                        ? QuoteExecutionGate.Side.BUY : QuoteExecutionGate.Side.SELL;
                QuoteExecutionGate.Assessment noFill = QuoteExecutionGate.assess(availableQuote, instrumentId,
                        arrival, noFillSide, requestedUnits, adverseSlippageTicks, qualityPolicy);
                return new Decision(Optional.of(quote), Optional.of(noFill), 0, noFill.reason());
            }
        }
        QuoteExecutionGate.Side gateSide = side == ExecutionEvent.Side.BUY
                ? QuoteExecutionGate.Side.BUY : QuoteExecutionGate.Side.SELL;
        QuoteExecutionGate.Assessment assessment = QuoteExecutionGate.assess(availableQuote, instrumentId,
                arrival, gateSide, assessmentUnits, adverseSlippageTicks, qualityPolicy);
        long filledUnits = assessment.executable() ? assessmentUnits : 0;
        String reason = filledUnits > 0 && filledUnits < requestedUnits
                ? "PARTIAL_SIZE_TOP_OF_BOOK" : assessment.reason();
        return new Decision(Optional.of(quote), Optional.of(assessment), filledUnits, reason);
    }

    void consume(Decision decision, ExecutionEvent.Side side, long filledUnits) {
        if (decision.quote().isEmpty() || decision.assessment().isEmpty()
                || !decision.assessment().orElseThrow().executable()) {
            throw new IllegalArgumentException("Only an executable quote decision can consume displayed liquidity");
        }
        if (filledUnits != decision.filledUnits()) {
            throw new IllegalArgumentException("Consumed quantity must equal the evaluated IOC fill quantity");
        }
        MarketQuote quote = decision.quote().orElseThrow();
        SideKey key = new SideKey(source, instrumentId, quote.sequenceNumber(), side);
        long displayed = side == ExecutionEvent.Side.BUY ? quote.askQuantity() : quote.bidQuantity();
        long remaining = remainingByQuoteSide.getOrDefault(key, displayed);
        if (filledUnits < 1 || filledUnits > remaining) {
            throw new IllegalArgumentException("filled quantity exceeds remaining displayed quote size");
        }
        remainingByQuoteSide.put(key, remaining - filledUnits);
    }

    void restoreConsumed(List<SimFill> fills) {
        Map<Long, MarketQuote> bySequence = new HashMap<>();
        for (MarketQuote quote : byStream.getOrDefault(new StreamKey(source, instrumentId), List.of())) {
            bySequence.put(quote.sequenceNumber(), quote);
        }
        for (SimFill fill : fills) {
            if (fill.executionModel().equals("BAR_NEXT_OPEN")) continue;
            String fillSource = fill.quoteSource();
            if (fillSource == null) throw new IllegalStateException("Quote-model checkpoint fill has no source");
            if (!fillSource.equals(source)) throw new IllegalStateException("Checkpoint quote source differs from configured source");
            long sequence = fill.quoteSequence();
            if (sequence < 1) throw new IllegalStateException("Quote-model checkpoint fill has no sequence");
            MarketQuote quote = bySequence.get(sequence);
            if (quote == null) throw new IllegalStateException("Checkpoint fill references a missing quote sequence " + sequence);
            ExecutionEvent.Side side = ExecutionEvent.Side.valueOf(fill.side());
            SideKey key = new SideKey(source, instrumentId, sequence, side);
            long displayed = side == ExecutionEvent.Side.BUY ? quote.askQuantity() : quote.bidQuantity();
            long remaining = remainingByQuoteSide.getOrDefault(key, displayed);
            if (fill.quantityUnits() > remaining) {
                throw new IllegalStateException("Checkpoint fills over-consume quote sequence " + sequence);
            }
            remainingByQuoteSide.put(key, remaining - fill.quantityUnits());
        }
    }

    private MarketQuote latestAsOf(Instant arrival) {
        List<MarketQuote> quotes = byStream.getOrDefault(new StreamKey(source, instrumentId), List.of());
        int low = 0;
        int high = quotes.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (!quotes.get(middle).receivedAtUtc().isAfter(arrival)) low = middle + 1;
            else high = middle;
        }
        Instant latestAllowedExchangeTime = arrival.plus(qualityPolicy.maximumFutureSkew());
        for (int index = low - 1; index >= 0; index--) {
            MarketQuote candidate = quotes.get(index);
            if (candidate.exchangeTimestampUtc().filter(time -> time.isAfter(latestAllowedExchangeTime)).isEmpty()) {
                return candidate;
            }
        }
        return null;
    }

    private static MarketQuote withAvailableSideQuantity(MarketQuote quote, ExecutionEvent.Side side, long available) {
        return side == ExecutionEvent.Side.BUY
                ? new MarketQuote(quote.schemaVersion(), quote.sequenceNumber(), quote.source(), quote.instrumentId(),
                        quote.exchangeTimestampUtc(), quote.receivedAtUtc(), quote.receivedAtMonotonicNanos(),
                        quote.bidPriceTicks(), quote.bidQuantity(), quote.askPriceTicks(), available)
                : new MarketQuote(quote.schemaVersion(), quote.sequenceNumber(), quote.source(), quote.instrumentId(),
                        quote.exchangeTimestampUtc(), quote.receivedAtUtc(), quote.receivedAtMonotonicNanos(),
                        quote.bidPriceTicks(), available, quote.askPriceTicks(), quote.askQuantity());
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must be non-empty and trimmed");
        }
        return value;
    }
}
