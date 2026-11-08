package com.mscient.intrapaper.marketdata;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable normalized top-of-book snapshot. Prices are integer ticks; sizes are integer units. */
public record MarketQuote(
        int schemaVersion,
        long sequenceNumber,
        String source,
        String instrumentId,
        Optional<Instant> exchangeTimestampUtc,
        Instant receivedAtUtc,
        long receivedAtMonotonicNanos,
        long bidPriceTicks,
        long bidQuantity,
        long askPriceTicks,
        long askQuantity) {

    public MarketQuote {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported MarketQuote schema version: " + schemaVersion);
        }
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("sequenceNumber must be positive");
        }
        source = requireNonBlank(source, "source");
        instrumentId = requireNonBlank(instrumentId, "instrumentId");
        exchangeTimestampUtc = Objects.requireNonNull(exchangeTimestampUtc, "exchangeTimestampUtc");
        receivedAtUtc = Objects.requireNonNull(receivedAtUtc, "receivedAtUtc");
        if (bidPriceTicks < 0 || bidQuantity < 0 || askPriceTicks < 0 || askQuantity < 0) {
            throw new IllegalArgumentException("Prices and quantities must be non-negative");
        }
    }

    /** Quality is recorded alongside the quote; anomalous snapshots are not silently discarded. */
    public Set<QuoteQualityFlag> qualityFlags() {
        EnumSet<QuoteQualityFlag> flags = EnumSet.noneOf(QuoteQualityFlag.class);
        if (exchangeTimestampUtc.isEmpty()) {
            flags.add(QuoteQualityFlag.MISSING_EXCHANGE_TIMESTAMP);
        }
        if (bidQuantity == 0) {
            flags.add(QuoteQualityFlag.NO_BID);
        } else if (bidPriceTicks == 0) {
            flags.add(QuoteQualityFlag.INVALID_BID_PRICE);
        }
        if (askQuantity == 0) {
            flags.add(QuoteQualityFlag.NO_ASK);
        } else if (askPriceTicks == 0) {
            flags.add(QuoteQualityFlag.INVALID_ASK_PRICE);
        }
        if (bidQuantity > 0 && askQuantity > 0 && bidPriceTicks >= askPriceTicks) {
            flags.add(QuoteQualityFlag.CROSSED_OR_LOCKED);
        }
        return Set.copyOf(flags);
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(fieldName + " must be non-blank and trimmed");
        }
        CaptureText.validate(value, fieldName);
        return value;
    }
}
