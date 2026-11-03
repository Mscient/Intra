package com.mscient.intrapaper.marketdata;

import java.time.Duration;

/** Read-only wall/monotonic capture-clock consistency summary for one verified raw journal. */
public record RawCaptureClockReport(
        RawCaptureSummary rawCapture,
        Duration maximumAllowedDivergence,
        long connectionStreams,
        long intervalsCompared,
        long nonIncreasingMonotonicIntervals,
        long wallClockRegressions,
        long intervalsOverDivergenceLimit,
        long arithmeticOverflowIntervals,
        Duration maximumAbsoluteDivergence) {

    public RawCaptureClockReport {
        if (rawCapture == null || maximumAllowedDivergence == null || maximumAbsoluteDivergence == null
                || maximumAllowedDivergence.isNegative() || maximumAbsoluteDivergence.isNegative()
                || connectionStreams < 0 || intervalsCompared < 0
                || nonIncreasingMonotonicIntervals < 0 || wallClockRegressions < 0
                || intervalsOverDivergenceLimit < 0 || arithmeticOverflowIntervals < 0
                || nonIncreasingMonotonicIntervals > intervalsCompared
                || wallClockRegressions > intervalsCompared
                || intervalsOverDivergenceLimit > intervalsCompared
                || arithmeticOverflowIntervals > intervalsCompared) {
            throw new IllegalArgumentException("Invalid raw-capture clock report");
        }
    }
}
