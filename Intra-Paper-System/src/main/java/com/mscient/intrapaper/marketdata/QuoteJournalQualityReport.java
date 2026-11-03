package com.mscient.intrapaper.marketdata;

import java.time.Duration;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Quality counts and event-time extrema produced by a complete verified quote-journal scan. */
public record QuoteJournalQualityReport(
        QuoteJournalSummary journal,
        long cleanQuoteCount,
        long flaggedQuoteCount,
        long exchangeTimestampCount,
        Duration maximumObservedAge,
        Duration maximumObservedFutureSkew,
        Duration maximumAgeAllowed,
        Duration maximumFutureSkewAllowed,
        Map<QuoteQualityFlag, Long> flagCounts) {

    public QuoteJournalQualityReport {
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(maximumObservedAge, "maximumObservedAge");
        Objects.requireNonNull(maximumObservedFutureSkew, "maximumObservedFutureSkew");
        Objects.requireNonNull(maximumAgeAllowed, "maximumAgeAllowed");
        Objects.requireNonNull(maximumFutureSkewAllowed, "maximumFutureSkewAllowed");
        Objects.requireNonNull(flagCounts, "flagCounts");
        if (cleanQuoteCount < 0 || flaggedQuoteCount < 0 || exchangeTimestampCount < 0
                || Math.addExact(cleanQuoteCount, flaggedQuoteCount) != journal.eventCount()) {
            throw new IllegalArgumentException("Quote-quality counts must be non-negative and cover every journal event");
        }
        if (maximumObservedAge.isNegative() || maximumObservedFutureSkew.isNegative()
                || maximumAgeAllowed.isNegative() || maximumAgeAllowed.isZero()
                || maximumFutureSkewAllowed.isNegative()) {
            throw new IllegalArgumentException("Quote-quality durations are invalid");
        }
        EnumMap<QuoteQualityFlag, Long> copy = new EnumMap<>(QuoteQualityFlag.class);
        for (QuoteQualityFlag flag : QuoteQualityFlag.values()) {
            Long count = flagCounts.get(flag);
            if (count == null || count < 0) {
                throw new IllegalArgumentException("Missing or invalid count for quote-quality flag " + flag);
            }
            copy.put(flag, count);
        }
        if (flagCounts.size() != copy.size()) {
            throw new IllegalArgumentException("Unknown quote-quality flag count");
        }
        flagCounts = Collections.unmodifiableMap(copy);
    }
}
