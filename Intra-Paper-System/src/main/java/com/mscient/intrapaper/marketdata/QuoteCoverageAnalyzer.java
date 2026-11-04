package com.mscient.intrapaper.marketdata;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Verifies a quote journal and reports gaps against caller-declared per-instrument receive windows. */
public final class QuoteCoverageAnalyzer {
    private record Key(String source, String instrumentId) { }

    private static final class WindowAccumulator {
        private final QuoteCoveragePlan.Window plan;
        private long quoteCount;
        private Instant first;
        private Instant last;
        private long excessiveSilenceIntervals;
        private Duration maximumSilence;
        private long nonIncreasingReceiveTransitions;
        private long quotesMissingExchangeTimestamp;

        private WindowAccumulator(QuoteCoveragePlan.Window plan) { this.plan = plan; }

        private void accept(MarketQuote quote) {
            quoteCount = Math.addExact(quoteCount, 1);
            if (first == null) {
                first = quote.receivedAtUtc();
                recordSilence(plan.startUtc(), first);
            }
            if (last != null) {
                int order = quote.receivedAtUtc().compareTo(last);
                if (order <= 0) {
                    nonIncreasingReceiveTransitions = Math.addExact(nonIncreasingReceiveTransitions, 1);
                } else {
                    recordSilence(last, quote.receivedAtUtc());
                }
            }
            last = quote.receivedAtUtc();
            if (quote.exchangeTimestampUtc().isEmpty()) {
                quotesMissingExchangeTimestamp = Math.addExact(quotesMissingExchangeTimestamp, 1);
            }
        }

        private QuoteCoverageReport.WindowResult result() {
            if (last == null) recordSilence(plan.startUtc(), plan.endUtc());
            else recordSilence(last, plan.endUtc());
            return new QuoteCoverageReport.WindowResult(plan.windowId(), plan.source(), plan.instrumentId(),
                    plan.startUtc(), plan.endUtc(), plan.maximumSilenceMillis(), quoteCount,
                    Optional.ofNullable(first), Optional.ofNullable(last), excessiveSilenceIntervals,
                    Optional.ofNullable(maximumSilence), nonIncreasingReceiveTransitions,
                    quotesMissingExchangeTimestamp);
        }

        private void recordSilence(Instant start, Instant end) {
            Duration silence;
            try {
                silence = Duration.between(start, end);
            } catch (ArithmeticException overflow) {
                silence = Duration.ofSeconds(Long.MAX_VALUE);
            }
            if (maximumSilence == null || silence.compareTo(maximumSilence) > 0) maximumSilence = silence;
            if (silence.compareTo(Duration.ofMillis(plan.maximumSilenceMillis())) > 0) {
                excessiveSilenceIntervals = Math.addExact(excessiveSilenceIntervals, 1);
            }
        }
    }

    private QuoteCoverageAnalyzer() { }

    public static QuoteCoverageReport analyze(Path journalPath, QuoteCoveragePlan plan) throws IOException {
        Objects.requireNonNull(journalPath, "journalPath");
        Objects.requireNonNull(plan, "plan");
        Map<Key, List<WindowAccumulator>> grouped = new HashMap<>();
        Set<String> plannedInstruments = new HashSet<>();
        List<WindowAccumulator> accumulators = new ArrayList<>();
        for (QuoteCoveragePlan.Window window : plan.windows()) {
            WindowAccumulator accumulator = new WindowAccumulator(window);
            plannedInstruments.add(window.instrumentId());
            grouped.computeIfAbsent(new Key(window.source(), window.instrumentId()), ignored -> new ArrayList<>())
                    .add(accumulator);
            accumulators.add(accumulator);
        }
        grouped.values().forEach(windows -> windows.sort(Comparator.comparing(item -> item.plan.startUtc())));
        long[] unplanned = {0};
        long[] unplannedSource = {0};
        long[] outside = {0};
        QuoteJournalSummary journal = QuoteJournalReader.replay(journalPath, quote -> {
            List<WindowAccumulator> candidates = grouped.get(new Key(quote.source(), quote.instrumentId()));
            if (candidates == null) {
                if (plannedInstruments.contains(quote.instrumentId())) {
                    unplannedSource[0] = Math.addExact(unplannedSource[0], 1);
                } else {
                    unplanned[0] = Math.addExact(unplanned[0], 1);
                }
                return;
            }
            WindowAccumulator match = findWindow(candidates, quote.receivedAtUtc());
            if (match == null) outside[0] = Math.addExact(outside[0], 1);
            else match.accept(quote);
        });
        List<QuoteCoverageReport.WindowResult> results = accumulators.stream()
                .map(WindowAccumulator::result).toList();
        return new QuoteCoverageReport(journal, plan.sha256(), results, unplanned[0], unplannedSource[0], outside[0]);
    }

    private static WindowAccumulator findWindow(List<WindowAccumulator> windows, Instant at) {
        int low = 0;
        int high = windows.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            WindowAccumulator candidate = windows.get(middle);
            if (at.isBefore(candidate.plan.startUtc())) high = middle - 1;
            else if (!at.isBefore(candidate.plan.endUtc())) low = middle + 1;
            else return candidate;
        }
        return null;
    }
}
