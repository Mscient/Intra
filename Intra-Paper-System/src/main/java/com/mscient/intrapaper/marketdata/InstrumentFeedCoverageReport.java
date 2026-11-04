package com.mscient.intrapaper.marketdata;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Diagnostic result joining caller-declared instrument subscriptions, lifecycle acknowledgements, and raw quotes. */
public record InstrumentFeedCoverageReport(RawCaptureSummary rawCapture, FeedLifecycleAnalysis lifecycle,
        String planSha256, Duration allowedWallClockSkew, long nonQuoteMessages,
        long unplannedDecodedQuotes, long decodedQuotesOutsidePlannedWindows, List<WindowResult> windows) {
    public InstrumentFeedCoverageReport {
        windows = List.copyOf(windows);
    }

    public record WindowResult(String windowId, String source, String connectionId, String subscriptionId,
            String instrumentId, Instant expectedStartUtc, Instant expectedEndUtc,
            long acknowledgedIntervalsOverlapping, long acknowledgedDurationMillis,
            long unacknowledgedDurationMillis, long quoteCount, long quotesWithinAcknowledgedInterval,
            long quotesOutsideAcknowledgedInterval, long quotesWithUncertainLifecycle,
            Optional<Instant> firstQuoteReceivedAtUtc, Optional<Instant> lastQuoteReceivedAtUtc,
            boolean lifecycleTimelineUncertain) { }
}
