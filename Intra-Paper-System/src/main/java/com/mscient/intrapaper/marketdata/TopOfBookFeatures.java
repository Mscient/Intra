package com.mscient.intrapaper.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** L1 snapshot metrics in ticks/units; this is not a depth or queue-position model. */
public record TopOfBookFeatures(
        long sequenceNumber,
        String source,
        String instrumentId,
        Instant decisionTimeUtc,
        Instant receivedAtUtc,
        Optional<Instant> exchangeTimestampUtc,
        boolean expectedInstrumentMatch,
        boolean receivedByDecisionTime,
        Set<QuoteQualityFlag> qualityFlags,
        Optional<BigDecimal> spreadTicks,
        Optional<BigDecimal> midPriceTicks,
        Optional<BigDecimal> micropriceTicks,
        Optional<BigDecimal> sizeImbalance) {

    public TopOfBookFeatures {
        if (sequenceNumber < 1) throw new IllegalArgumentException("sequenceNumber must be positive");
        source = requireNonBlank(source, "source");
        instrumentId = requireNonBlank(instrumentId, "instrumentId");
        Objects.requireNonNull(decisionTimeUtc, "decisionTimeUtc");
        Objects.requireNonNull(receivedAtUtc, "receivedAtUtc");
        exchangeTimestampUtc = Objects.requireNonNull(exchangeTimestampUtc, "exchangeTimestampUtc");
        qualityFlags = Set.copyOf(Objects.requireNonNull(qualityFlags, "qualityFlags"));
        spreadTicks = Objects.requireNonNull(spreadTicks, "spreadTicks");
        midPriceTicks = Objects.requireNonNull(midPriceTicks, "midPriceTicks");
        micropriceTicks = Objects.requireNonNull(micropriceTicks, "micropriceTicks");
        sizeImbalance = Objects.requireNonNull(sizeImbalance, "sizeImbalance");
        if (sizeImbalance.isPresent()
                && (sizeImbalance.orElseThrow().compareTo(BigDecimal.ONE.negate()) < 0
                || sizeImbalance.orElseThrow().compareTo(BigDecimal.ONE) > 0)) {
            throw new IllegalArgumentException("sizeImbalance must be between -1 and 1");
        }
    }

    /** True only for an expected, already-received snapshot with no structural or freshness flags. */
    public boolean decisionEligible() {
        return expectedInstrumentMatch && receivedByDecisionTime && qualityFlags.isEmpty();
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(fieldName + " must be non-blank and trimmed");
        }
        return value;
    }
}
