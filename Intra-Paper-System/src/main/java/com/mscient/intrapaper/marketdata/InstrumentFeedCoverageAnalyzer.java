package com.mscient.intrapaper.marketdata;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Joins a caller-declared instrument/subscription plan with verified lifecycle and raw journals.
 * Output is diagnostic: neither the plan nor the journals establish expected market activity.
 */
public final class InstrumentFeedCoverageAnalyzer {
    private record ConnectionKey(String source, String connectionId) { }
    private record SubscriptionKey(String source, String connectionId, String subscriptionId) { }
    private record Span(Instant start, Instant end) { }

    private static final class Interval {
        private final Instant start;
        private Instant end;
        private Interval(Instant start) { this.start = start; }
    }

    private static final class Timeline {
        private final List<Interval> intervals = new ArrayList<>();
        private Interval open;
        private boolean uncertain;
    }

    private static final class WindowAccumulator {
        private final InstrumentSubscriptionPlan.Window plan;
        private long quotes;
        private long quotesWithAck;
        private long quotesWithoutAck;
        private long quotesWithUncertainTimeline;
        private Instant first;
        private Instant last;

        private WindowAccumulator(InstrumentSubscriptionPlan.Window plan) { this.plan = plan; }

        private void accept(MarketQuote quote, Timeline timeline, Duration skew, boolean connectionUncertain) {
            quotes = Math.addExact(quotes, 1);
            if (first == null || quote.receivedAtUtc().isBefore(first)) first = quote.receivedAtUtc();
            if (last == null || quote.receivedAtUtc().isAfter(last)) last = quote.receivedAtUtc();
            if (connectionUncertain || (timeline != null && timeline.uncertain)) {
                quotesWithUncertainTimeline = Math.addExact(quotesWithUncertainTimeline, 1);
            } else if (timeline != null && timeline.intervals.stream()
                    .anyMatch(interval -> containsWithTolerance(interval, quote.receivedAtUtc(), skew))) {
                quotesWithAck = Math.addExact(quotesWithAck, 1);
            } else {
                quotesWithoutAck = Math.addExact(quotesWithoutAck, 1);
            }
        }

        private InstrumentFeedCoverageReport.WindowResult result(Timeline timeline, boolean connectionUncertain) {
            List<Interval> overlapping = timeline == null ? List.of() : timeline.intervals.stream()
                    .filter(interval -> overlaps(plan.startUtc(), plan.endUtc(), interval.start,
                            interval.end == null ? plan.endUtc() : interval.end))
                    .toList();
            long acknowledgedMillis = unionDurationMillis(plan.startUtc(), plan.endUtc(), overlapping);
            long totalMillis = Duration.between(plan.startUtc(), plan.endUtc()).toMillis();
            boolean uncertain = connectionUncertain || (timeline != null && timeline.uncertain);
            return new InstrumentFeedCoverageReport.WindowResult(plan.windowId(), plan.source(), plan.connectionId(),
                    plan.subscriptionId(), plan.instrumentId(), plan.startUtc(), plan.endUtc(), overlapping.size(),
                    acknowledgedMillis, Math.subtractExact(totalMillis, acknowledgedMillis), quotes, quotesWithAck,
                    quotesWithoutAck, quotesWithUncertainTimeline, Optional.ofNullable(first), Optional.ofNullable(last),
                    uncertain);
        }
    }

    private InstrumentFeedCoverageAnalyzer() { }

    public static InstrumentFeedCoverageReport analyze(Path lifecyclePath, Path rawCapturePath,
            InstrumentSubscriptionPlan plan, RawQuoteDecoder decoder, Duration allowedWallClockSkew) throws IOException {
        Objects.requireNonNull(lifecyclePath, "lifecyclePath");
        Objects.requireNonNull(rawCapturePath, "rawCapturePath");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(allowedWallClockSkew, "allowedWallClockSkew");
        if (allowedWallClockSkew.isNegative()) throw new IllegalArgumentException("allowedWallClockSkew must be non-negative");

        Map<SubscriptionKey, Timeline> timelines = new HashMap<>();
        Map<ConnectionKey, Instant> lastObserved = new HashMap<>();
        Map<ConnectionKey, Boolean> uncertainConnections = new HashMap<>();
        Map<String, List<WindowAccumulator>> byStreamInstrument = new HashMap<>();
        List<WindowAccumulator> windows = plan.windows().stream().map(WindowAccumulator::new).toList();
        for (WindowAccumulator window : windows) {
            byStreamInstrument.computeIfAbsent(planKey(window.plan), ignored -> new ArrayList<>()).add(window);
        }
        byStreamInstrument.values().forEach(items -> items.sort(Comparator.comparing(item -> item.plan.startUtc())));

        FeedLifecycleSummary firstLifecyclePass = FeedLifecycleJournalReader.replay(lifecyclePath, event -> {
            ConnectionKey connection = new ConnectionKey(event.source(), event.connectionId());
            Instant prior = lastObserved.put(connection, event.observedAtUtc());
            if (prior != null && event.observedAtUtc().isBefore(prior)) uncertainConnections.put(connection, true);
            switch (event.type()) {
                case SUBSCRIPTION_ACKNOWLEDGED -> {
                    SubscriptionKey key = subscriptionKey(event);
                    Timeline timeline = timelines.computeIfAbsent(key, ignored -> new Timeline());
                    if (timeline.open != null) {
                        timeline.uncertain = true;
                        close(timeline, event.observedAtUtc());
                    }
                    timeline.open = new Interval(event.observedAtUtc());
                    timeline.intervals.add(timeline.open);
                }
                case UNSUBSCRIPTION_ACKNOWLEDGED -> {
                    Timeline timeline = timelines.get(subscriptionKey(event));
                    if (timeline == null || timeline.open == null) {
                        if (timeline == null) timelines.put(subscriptionKey(event), new Timeline());
                        timelines.computeIfAbsent(subscriptionKey(event), ignored -> new Timeline()).uncertain = true;
                    } else close(timeline, event.observedAtUtc());
                }
                case CONNECTION_FAILED, DISCONNECTED -> {
                    List<Map.Entry<SubscriptionKey, Timeline>> open = timelines.entrySet().stream()
                            .filter(entry -> entry.getKey().source().equals(connection.source())
                                    && entry.getKey().connectionId().equals(connection.connectionId())
                                    && entry.getValue().open != null).toList();
                    for (var entry : open) close(entry.getValue(), event.observedAtUtc());
                }
                default -> { }
            }
        });
        FeedLifecycleAnalysis lifecycle = FeedLifecycleAnalyzer.analyze(lifecyclePath);
        if (!firstLifecyclePass.equals(lifecycle.journal())) {
            throw new IOException("Feed lifecycle journal changed between analysis passes");
        }
        boolean lifecycleGloballyUncertain = lifecycle.transitionAnomalyCount() > 0;

        long[] nonQuotes = {0};
        long[] unplannedQuotes = {0};
        long[] outsideWindows = {0};
        RawCaptureSummary raw = RawCaptureJournalReader.replay(rawCapturePath, message -> {
            var decoded = Objects.requireNonNull(decoder.decode(message), "RawQuoteDecoder returned null");
            if (decoded.isEmpty()) {
                nonQuotes[0] = Math.addExact(nonQuotes[0], 1);
                return;
            }
            MarketQuote quote = decoded.orElseThrow();
            if (!quote.source().equals(message.source())
                    || !quote.receivedAtUtc().equals(message.receivedAtUtc())
                    || quote.receivedAtMonotonicNanos() != message.receivedAtMonotonicNanos()) {
                throw new IllegalArgumentException("Decoded quote source and receive clocks must match raw metadata");
            }
            String key = planKey(message.source(), message.connectionId(), message.subscriptionId(), quote.instrumentId());
            List<WindowAccumulator> candidates = byStreamInstrument.get(key);
            if (candidates == null) {
                unplannedQuotes[0] = Math.addExact(unplannedQuotes[0], 1);
                return;
            }
            WindowAccumulator matched = findWindow(candidates, quote.receivedAtUtc());
            if (matched == null) {
                outsideWindows[0] = Math.addExact(outsideWindows[0], 1);
                return;
            }
            SubscriptionKey subscription = new SubscriptionKey(message.source(), message.connectionId(), message.subscriptionId());
            matched.accept(quote, timelines.get(subscription), allowedWallClockSkew,
                    lifecycleGloballyUncertain || uncertainConnections.getOrDefault(
                            new ConnectionKey(message.source(), message.connectionId()), false));
        });

        List<InstrumentFeedCoverageReport.WindowResult> results = windows.stream().map(window -> {
            SubscriptionKey key = new SubscriptionKey(window.plan.source(), window.plan.connectionId(),
                    window.plan.subscriptionId());
            return window.result(timelines.get(key), uncertainConnections.getOrDefault(
                    new ConnectionKey(window.plan.source(), window.plan.connectionId()), false)
                    || lifecycleGloballyUncertain);
        }).toList();
        return new InstrumentFeedCoverageReport(raw, lifecycle, plan.sha256(), allowedWallClockSkew,
                nonQuotes[0], unplannedQuotes[0], outsideWindows[0], results);
    }

    private static void close(Timeline timeline, Instant at) {
        Interval interval = timeline.open;
        timeline.open = null;
        if (interval == null) return;
        if (at.isBefore(interval.start)) {
            timeline.uncertain = true;
            interval.end = interval.start;
        } else interval.end = at;
    }

    private static boolean containsWithTolerance(Interval interval, Instant at, Duration skew) {
        if (at.isBefore(interval.start) && Duration.between(at, interval.start).compareTo(skew) > 0) return false;
        if (interval.end == null || at.isBefore(interval.end)) return true;
        return !skew.isZero() && Duration.between(interval.end, at).compareTo(skew) <= 0;
    }

    private static boolean overlaps(Instant start, Instant end, Instant otherStart, Instant otherEnd) {
        return start.isBefore(otherEnd) && otherStart.isBefore(end);
    }

    private static long unionDurationMillis(Instant start, Instant end, List<Interval> intervals) {
        List<Span> clipped = intervals.stream().map(interval -> {
            Instant clippedStart = interval.start.isAfter(start) ? interval.start : start;
            Instant actualEnd = interval.end == null ? end : interval.end;
            Instant clippedEnd = actualEnd.isBefore(end) ? actualEnd : end;
            return new Span(clippedStart, clippedEnd);
        }).filter(interval -> interval.start().isBefore(interval.end()))
                .sorted(Comparator.comparing(Span::start)).toList();
        if (clipped.isEmpty()) return 0;
        Instant mergedStart = clipped.getFirst().start();
        Instant mergedEnd = clipped.getFirst().end();
        long total = 0;
        for (int i = 1; i < clipped.size(); i++) {
            Span next = clipped.get(i);
            if (!next.start().isAfter(mergedEnd)) {
                if (next.end().isAfter(mergedEnd)) mergedEnd = next.end();
            } else {
                total = Math.addExact(total, Duration.between(mergedStart, mergedEnd).toMillis());
                mergedStart = next.start();
                mergedEnd = next.end();
            }
        }
        return Math.addExact(total, Duration.between(mergedStart, mergedEnd).toMillis());
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

    private static String planKey(InstrumentSubscriptionPlan.Window window) {
        return planKey(window.source(), window.connectionId(), window.subscriptionId(), window.instrumentId());
    }

    private static String planKey(String source, String connection, String subscription, String instrument) {
        return source + "\u0000" + connection + "\u0000" + subscription + "\u0000" + instrument;
    }

    private static SubscriptionKey subscriptionKey(FeedLifecycleEvent event) {
        return new SubscriptionKey(event.source(), event.connectionId(), event.subscriptionId().orElseThrow());
    }
}
