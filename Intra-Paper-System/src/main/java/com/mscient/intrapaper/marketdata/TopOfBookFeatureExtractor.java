package com.mscient.intrapaper.marketdata;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Derives only current-snapshot L1 features from a normalized quote and explicit decision clock. */
public final class TopOfBookFeatureExtractor {
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private TopOfBookFeatureExtractor() { }

    public static TopOfBookFeatures extract(MarketQuote quote, String expectedInstrumentId,
            Instant decisionTimeUtc, QuoteQualityPolicy qualityPolicy) {
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(decisionTimeUtc, "decisionTimeUtc");
        Objects.requireNonNull(qualityPolicy, "qualityPolicy");
        String expectedInstrument = Objects.requireNonNull(expectedInstrumentId, "expectedInstrumentId");
        if (expectedInstrument.isBlank() || !expectedInstrument.equals(expectedInstrument.strip())) {
            throw new IllegalArgumentException("expectedInstrumentId must be non-blank and trimmed");
        }

        Optional<BigDecimal> spread = Optional.empty();
        Optional<BigDecimal> midpoint = Optional.empty();
        Optional<BigDecimal> microprice = Optional.empty();
        Optional<BigDecimal> imbalance = Optional.empty();
        if (quote.bidPriceTicks() > 0 && quote.askPriceTicks() > 0) {
            BigDecimal bid = BigDecimal.valueOf(quote.bidPriceTicks());
            BigDecimal ask = BigDecimal.valueOf(quote.askPriceTicks());
            spread = Optional.of(ask.subtract(bid));
            midpoint = Optional.of(ask.add(bid).divide(TWO));
            if (quote.bidQuantity() > 0 && quote.askQuantity() > 0) {
                BigDecimal bidSize = BigDecimal.valueOf(quote.bidQuantity());
                BigDecimal askSize = BigDecimal.valueOf(quote.askQuantity());
                BigDecimal totalSize = bidSize.add(askSize);
                microprice = Optional.of(ask.multiply(bidSize).add(bid.multiply(askSize))
                        .divide(totalSize, MathContext.DECIMAL128));
                imbalance = Optional.of(bidSize.subtract(askSize).divide(totalSize, MathContext.DECIMAL128));
            }
        }

        return new TopOfBookFeatures(quote.sequenceNumber(), quote.source(), quote.instrumentId(),
                decisionTimeUtc, quote.receivedAtUtc(), quote.exchangeTimestampUtc(),
                quote.instrumentId().equals(expectedInstrument), !quote.receivedAtUtc().isAfter(decisionTimeUtc),
                qualityPolicy.assess(quote, decisionTimeUtc), spread, midpoint, microprice, imbalance);
    }
}
