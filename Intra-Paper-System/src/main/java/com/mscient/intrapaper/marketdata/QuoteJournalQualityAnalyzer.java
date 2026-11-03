package com.mscient.intrapaper.marketdata;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Objects;

/** Reports quote-quality findings while verifying the complete journal; it does not filter quotes. */
public final class QuoteJournalQualityAnalyzer {
    private QuoteJournalQualityAnalyzer() { }

    public static QuoteJournalQualityReport analyze(Path path, QuoteQualityPolicy policy) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(policy, "policy");
        Accumulator accumulator = new Accumulator();
        QuoteJournalSummary journal = QuoteJournalReader.replay(path, quote -> accumulator.accept(quote, policy));
        return accumulator.report(journal, policy);
    }

    private static final class Accumulator {
        private final EnumMap<QuoteQualityFlag, Long> flagCounts = new EnumMap<>(QuoteQualityFlag.class);
        private long cleanQuoteCount;
        private long flaggedQuoteCount;
        private long exchangeTimestampCount;
        private Duration maximumObservedAge = Duration.ZERO;
        private Duration maximumObservedFutureSkew = Duration.ZERO;

        private Accumulator() {
            for (QuoteQualityFlag flag : QuoteQualityFlag.values()) flagCounts.put(flag, 0L);
        }

        private void accept(MarketQuote quote, QuoteQualityPolicy policy) {
            var flags = policy.assess(quote);
            if (flags.isEmpty()) {
                cleanQuoteCount = Math.addExact(cleanQuoteCount, 1);
            } else {
                flaggedQuoteCount = Math.addExact(flaggedQuoteCount, 1);
            }
            for (QuoteQualityFlag flag : flags) {
                flagCounts.put(flag, Math.addExact(flagCounts.get(flag), 1));
            }
            quote.exchangeTimestampUtc().ifPresent(exchangeTime -> {
                exchangeTimestampCount = Math.addExact(exchangeTimestampCount, 1);
                Duration offset = Duration.between(exchangeTime, quote.receivedAtUtc());
                if (offset.isNegative()) {
                    Duration skew = offset.negated();
                    if (skew.compareTo(maximumObservedFutureSkew) > 0) maximumObservedFutureSkew = skew;
                } else if (offset.compareTo(maximumObservedAge) > 0) {
                    maximumObservedAge = offset;
                }
            });
        }

        private QuoteJournalQualityReport report(QuoteJournalSummary journal, QuoteQualityPolicy policy) {
            return new QuoteJournalQualityReport(journal, cleanQuoteCount, flaggedQuoteCount,
                    exchangeTimestampCount, maximumObservedAge, maximumObservedFutureSkew,
                    policy.maximumAge(), policy.maximumFutureSkew(), flagCounts);
        }
    }
}
