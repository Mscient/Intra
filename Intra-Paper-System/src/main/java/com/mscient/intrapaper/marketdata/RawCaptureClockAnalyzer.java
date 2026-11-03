package com.mscient.intrapaper.marketdata;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Compares wall-clock and monotonic elapsed time while verifying and streaming a raw capture. */
public final class RawCaptureClockAnalyzer {
    private record ConnectionKey(String source, String connectionId) { }
    private record Previous(Instant wallClock, long monotonicNanos) { }

    private final Map<ConnectionKey, Previous> previousByConnection = new HashMap<>();
    private final Duration maximumAllowedDivergence;
    private long intervalsCompared;
    private long nonIncreasingMonotonicIntervals;
    private long wallClockRegressions;
    private long intervalsOverDivergenceLimit;
    private long arithmeticOverflowIntervals;
    private Duration maximumAbsoluteDivergence = Duration.ZERO;

    private RawCaptureClockAnalyzer(Duration maximumAllowedDivergence) {
        this.maximumAllowedDivergence = Objects.requireNonNull(maximumAllowedDivergence, "maximumAllowedDivergence");
        if (maximumAllowedDivergence.isNegative()) {
            throw new IllegalArgumentException("maximumAllowedDivergence must be non-negative");
        }
    }

    /** The threshold is diagnostic and caller-supplied; it is not an NTP or UTC-accuracy guarantee. */
    public static RawCaptureClockReport analyze(Path rawCapturePath, Duration maximumAllowedDivergence)
            throws IOException {
        Objects.requireNonNull(rawCapturePath, "rawCapturePath");
        RawCaptureClockAnalyzer analyzer = new RawCaptureClockAnalyzer(maximumAllowedDivergence);
        RawCaptureSummary summary = RawCaptureJournalReader.replay(rawCapturePath, analyzer::accept);
        return new RawCaptureClockReport(summary, maximumAllowedDivergence,
                analyzer.previousByConnection.size(), analyzer.intervalsCompared,
                analyzer.nonIncreasingMonotonicIntervals, analyzer.wallClockRegressions,
                analyzer.intervalsOverDivergenceLimit, analyzer.arithmeticOverflowIntervals,
                analyzer.maximumAbsoluteDivergence);
    }

    private void accept(RawMarketMessage message) {
        ConnectionKey key = new ConnectionKey(message.source(), message.connectionId());
        Previous previous = previousByConnection.put(key,
                new Previous(message.receivedAtUtc(), message.receivedAtMonotonicNanos()));
        if (previous == null) return;

        intervalsCompared = Math.addExact(intervalsCompared, 1);
        Duration wallDelta = Duration.between(previous.wallClock(), message.receivedAtUtc());
        if (wallDelta.isNegative()) wallClockRegressions = Math.addExact(wallClockRegressions, 1);

        long monotonicDelta;
        try {
            monotonicDelta = Math.subtractExact(message.receivedAtMonotonicNanos(), previous.monotonicNanos());
        } catch (ArithmeticException overflow) {
            arithmeticOverflowIntervals = Math.addExact(arithmeticOverflowIntervals, 1);
            return;
        }
        if (monotonicDelta <= 0) {
            nonIncreasingMonotonicIntervals = Math.addExact(nonIncreasingMonotonicIntervals, 1);
            return;
        }

        try {
            Duration divergence = wallDelta.minusNanos(monotonicDelta);
            if (divergence.isNegative()) divergence = divergence.negated();
            if (divergence.compareTo(maximumAbsoluteDivergence) > 0) {
                maximumAbsoluteDivergence = divergence;
            }
            if (divergence.compareTo(maximumAllowedDivergence) > 0) {
                intervalsOverDivergenceLimit = Math.addExact(intervalsOverDivergenceLimit, 1);
            }
        } catch (ArithmeticException overflow) {
            arithmeticOverflowIntervals = Math.addExact(arithmeticOverflowIntervals, 1);
        }
    }
}
