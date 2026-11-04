package com.mscient.intrapaper.marketdata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/** Decodes a saved capture and attributes each decoder-defined sequence stream to its dated instrument. */
public final class InstrumentSequenceAnalyzer {
    private record Key(String source, String connectionId, String subscriptionId, String instrumentId) { }

    private static final class Accumulator {
        private long quoteCount;
        private long sequencedQuoteCount;
        private long quotesWithoutSequence;
        private OptionalLong first = OptionalLong.empty();
        private OptionalLong last = OptionalLong.empty();
        private long gaps;
        private long missing;
        private long nonIncreasing;

        private void accept(OptionalLong sequence) {
            quoteCount = Math.addExact(quoteCount, 1);
            if (sequence.isEmpty()) {
                quotesWithoutSequence = Math.addExact(quotesWithoutSequence, 1);
                return;
            }
            long current = sequence.getAsLong();
            if (current < 0) throw new IllegalArgumentException("Decoded source sequence must be non-negative");
            sequencedQuoteCount = Math.addExact(sequencedQuoteCount, 1);
            if (first.isEmpty()) first = OptionalLong.of(current);
            if (last.isPresent()) {
                long prior = last.getAsLong();
                if (current > prior && current - prior > 1) {
                    gaps = Math.addExact(gaps, 1);
                    missing = Math.addExact(missing, current - prior - 1);
                } else if (current <= prior) {
                    nonIncreasing = Math.addExact(nonIncreasing, 1);
                }
            }
            last = OptionalLong.of(current);
        }

        private InstrumentSequenceReport.StreamResult result(Key key) {
            return new InstrumentSequenceReport.StreamResult(key.source(), key.connectionId(), key.subscriptionId(),
                    key.instrumentId(), quoteCount, sequencedQuoteCount, quotesWithoutSequence, first, last,
                    gaps, missing, nonIncreasing);
        }
    }

    private InstrumentSequenceAnalyzer() { }

    /**
     * Verifies the raw capture hash chain while decoding each message. Sequence values are compared
     * only within the same source, connection, subscription, and resolved instrument.
     */
    public static InstrumentSequenceReport analyze(Path rawCapturePath, RawQuoteDecoder decoder) throws IOException {
        Objects.requireNonNull(rawCapturePath, "rawCapturePath");
        Objects.requireNonNull(decoder, "decoder");
        Map<Key, Accumulator> streams = new HashMap<>();
        long[] quoteCount = {0};
        long[] nonQuoteCount = {0};
        RawCaptureSummary raw = RawCaptureJournalReader.replay(rawCapturePath, message -> {
            var decoded = Objects.requireNonNull(decoder.decode(message), "RawQuoteDecoder returned null");
            if (decoded.isEmpty()) {
                nonQuoteCount[0] = Math.addExact(nonQuoteCount[0], 1);
                return;
            }
            MarketQuote quote = decoded.orElseThrow();
            if (!quote.source().equals(message.source())
                    || !quote.receivedAtUtc().equals(message.receivedAtUtc())
                    || quote.receivedAtMonotonicNanos() != message.receivedAtMonotonicNanos()) {
                throw new IllegalArgumentException("Decoded quote source and receive timestamps must match raw capture metadata");
            }
            quoteCount[0] = Math.addExact(quoteCount[0], 1);
            Key key = new Key(message.source(), message.connectionId(), message.subscriptionId(), quote.instrumentId());
            OptionalLong sequence = Objects.requireNonNull(decoder.sourceSequence(message),
                    "RawQuoteDecoder.sourceSequence returned null");
            streams.computeIfAbsent(key, ignored -> new Accumulator()).accept(sequence);
        });
        var results = new ArrayList<>(streams.entrySet().stream().map(entry -> entry.getValue().result(entry.getKey())).toList());
        results.sort(Comparator.comparing(InstrumentSequenceReport.StreamResult::source)
                .thenComparing(InstrumentSequenceReport.StreamResult::connectionId)
                .thenComparing(InstrumentSequenceReport.StreamResult::subscriptionId)
                .thenComparing(InstrumentSequenceReport.StreamResult::instrumentId));
        return new InstrumentSequenceReport(raw, decoder.sequenceDomain(), nonQuoteCount[0], quoteCount[0], results);
    }
}
