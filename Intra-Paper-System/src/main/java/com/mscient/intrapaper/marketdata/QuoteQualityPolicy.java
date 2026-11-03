package com.mscient.intrapaper.marketdata;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Explicit caller-supplied event-time freshness and future-skew thresholds for quote snapshots. */
public record QuoteQualityPolicy(Duration maximumAge, Duration maximumFutureSkew) {
    public QuoteQualityPolicy {
        Objects.requireNonNull(maximumAge, "maximumAge");
        Objects.requireNonNull(maximumFutureSkew, "maximumFutureSkew");
        if (maximumAge.isNegative() || maximumAge.isZero()) {
            throw new IllegalArgumentException("maximumAge must be positive");
        }
        if (maximumFutureSkew.isNegative()) {
            throw new IllegalArgumentException("maximumFutureSkew must be non-negative");
        }
    }

    /** Returns base quote findings plus configured event-time findings; never filters the quote. */
    public Set<QuoteQualityFlag> assess(MarketQuote quote) {
        Objects.requireNonNull(quote, "quote");
        return assess(quote, quote.receivedAtUtc());
    }

    /** Evaluates event-time freshness at an explicit decision clock, not just when the quote arrived. */
    public Set<QuoteQualityFlag> assess(MarketQuote quote, java.time.Instant evaluatedAtUtc) {
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(evaluatedAtUtc, "evaluatedAtUtc");
        EnumSet<QuoteQualityFlag> flags = EnumSet.noneOf(QuoteQualityFlag.class);
        flags.addAll(quote.qualityFlags());
        quote.exchangeTimestampUtc().ifPresent(exchangeTime -> {
            Duration offset = Duration.between(exchangeTime, evaluatedAtUtc);
            if (offset.compareTo(maximumAge) > 0) flags.add(QuoteQualityFlag.STALE_EXCHANGE_TIMESTAMP);
            if (offset.negated().compareTo(maximumFutureSkew) > 0) flags.add(QuoteQualityFlag.FUTURE_EXCHANGE_TIMESTAMP);
        });
        return Set.copyOf(flags);
    }
}
