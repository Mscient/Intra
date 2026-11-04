package com.mscient.intrapaper.marketdata;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Diagnostic comparison of observed quote receive times against caller-declared instrument windows. */
public record QuoteCoverageReport(
        QuoteJournalSummary journal,
        String planSha256,
        List<WindowResult> windows,
        long quotesForUnplannedInstruments,
        long quotesFromUnplannedSources,
        long quotesOutsidePlannedWindows) {

    public record WindowResult(
            String windowId,
            String source,
            String instrumentId,
            Instant expectedStartUtc,
            Instant expectedEndUtc,
            long maximumSilenceMillis,
            long quoteCount,
            Optional<Instant> firstQuoteReceivedAtUtc,
            Optional<Instant> lastQuoteReceivedAtUtc,
            long excessiveSilenceIntervals,
            Optional<Duration> maximumObservedSilence,
            long nonIncreasingReceiveTransitions,
            long quotesMissingExchangeTimestamp) { }

    public QuoteCoverageReport {
        windows = List.copyOf(windows);
        if (journal == null || planSha256 == null || !planSha256.matches("[0-9a-f]{64}")
                || quotesForUnplannedInstruments < 0 || quotesFromUnplannedSources < 0
                || quotesOutsidePlannedWindows < 0) {
            throw new IllegalArgumentException("Invalid quote coverage report");
        }
        long plannedCount = 0;
        for (WindowResult window : windows) plannedCount = Math.addExact(plannedCount, window.quoteCount());
        long reconciledCount = Math.addExact(Math.addExact(Math.addExact(plannedCount, quotesForUnplannedInstruments),
                quotesFromUnplannedSources), quotesOutsidePlannedWindows);
        if (reconciledCount != journal.eventCount()) {
            throw new IllegalArgumentException("Quote coverage counts do not reconcile to journal event count");
        }
    }
}
