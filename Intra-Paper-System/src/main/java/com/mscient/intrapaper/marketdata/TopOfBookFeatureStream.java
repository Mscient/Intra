package com.mscient.intrapaper.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Stateful, single-instrument feature replay over normalized snapshots; it never infers L3 queue flow. */
public final class TopOfBookFeatureStream {
    private final String expectedInstrumentId;
    private final QuoteQualityPolicy qualityPolicy;
    private MarketQuote previousQuote;
    private TopOfBookFeatures previousFeatures;
    private Instant priorDecisionTimeUtc;

    public TopOfBookFeatureStream(String expectedInstrumentId, QuoteQualityPolicy qualityPolicy) {
        this.expectedInstrumentId = Objects.requireNonNull(expectedInstrumentId, "expectedInstrumentId");
        if (expectedInstrumentId.isBlank() || !expectedInstrumentId.equals(expectedInstrumentId.strip())) {
            throw new IllegalArgumentException("expectedInstrumentId must be non-blank and trimmed");
        }
        this.qualityPolicy = Objects.requireNonNull(qualityPolicy, "qualityPolicy");
    }

    /** Adds one quote in local journal sequence order and returns current-snapshot and L1 delta features. */
    public synchronized TopOfBookFeatureFrame accept(MarketQuote quote, Instant decisionTimeUtc) {
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(decisionTimeUtc, "decisionTimeUtc");
        if (priorDecisionTimeUtc != null && decisionTimeUtc.isBefore(priorDecisionTimeUtc)) {
            throw new IllegalArgumentException("Decision time moved backwards during quote-feature replay");
        }
        if (previousQuote != null && quote.sequenceNumber() <= previousQuote.sequenceNumber()) {
            throw new IllegalArgumentException("Quote sequence must increase during feature replay");
        }

        TopOfBookFeatures snapshot = TopOfBookFeatureExtractor.extract(
                quote, expectedInstrumentId, decisionTimeUtc, qualityPolicy);
        boolean hasPrior = previousQuote != null;
        long missing = hasPrior
                ? quote.sequenceNumber() - previousQuote.sequenceNumber() - 1
                : 0;
        boolean continuous = hasPrior && missing == 0;
        boolean sameSource = hasPrior && previousQuote.source().equals(quote.source());
        Optional<BigDecimal> ofi = continuous && sameSource && previousFeatures.decisionEligible()
                && snapshot.decisionEligible() ? l1OrderFlowImbalance(previousQuote, quote) : Optional.empty();
        TopOfBookFeatureFrame frame = new TopOfBookFeatureFrame(
                snapshot, hasPrior, continuous, sameSource, missing, ofi);
        previousQuote = quote;
        previousFeatures = snapshot;
        priorDecisionTimeUtc = decisionTimeUtc;
        return frame;
    }

    private static Optional<BigDecimal> l1OrderFlowImbalance(MarketQuote prior, MarketQuote current) {
        if (prior.bidQuantity() == 0 || prior.askQuantity() == 0
                || current.bidQuantity() == 0 || current.askQuantity() == 0) {
            return Optional.empty();
        }
        BigDecimal flow = BigDecimal.ZERO;
        if (current.bidPriceTicks() >= prior.bidPriceTicks()) {
            flow = flow.add(BigDecimal.valueOf(current.bidQuantity()));
        }
        if (current.bidPriceTicks() <= prior.bidPriceTicks()) {
            flow = flow.subtract(BigDecimal.valueOf(prior.bidQuantity()));
        }
        if (current.askPriceTicks() <= prior.askPriceTicks()) {
            flow = flow.subtract(BigDecimal.valueOf(current.askQuantity()));
        }
        if (current.askPriceTicks() >= prior.askPriceTicks()) {
            flow = flow.add(BigDecimal.valueOf(prior.askQuantity()));
        }
        return Optional.of(flow);
    }
}
