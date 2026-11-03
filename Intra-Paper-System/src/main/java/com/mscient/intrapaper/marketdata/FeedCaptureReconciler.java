package com.mscient.intrapaper.marketdata;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Cross-checks raw messages against acknowledged subscription windows in a separately captured lifecycle journal.
 * Wall-clock comparison is diagnostic; it cannot establish completeness or explain unsynchronized clocks.
 */
public final class FeedCaptureReconciler {
    private record ConnectionKey(String source, String connectionId) { }
    private record SubscriptionKey(String source, String connectionId, String subscriptionId) { }
    private enum CloseReason { UNSUBSCRIPTION, CONNECTION_LOSS, TIMELINE_ANOMALY }

    private static final class Interval {
        private final Instant start;
        private Instant end;

        private Interval(Instant start) { this.start = start; }
    }

    private static final class SubscriptionTimeline {
        private final List<Interval> intervals = new ArrayList<>();
        private boolean anomalous;
    }

    private FeedCaptureReconciler() { }

    /**
     * Replays and verifies both journals. The tolerance is applied symmetrically around recorded
     * subscription boundaries to account for caller-observed wall-clock skew.
     */
    public static FeedCaptureReconciliationReport reconcile(Path lifecycleJournalPath,
            Path rawCapturePath, Duration allowedWallClockSkew) throws IOException {
        Objects.requireNonNull(lifecycleJournalPath, "lifecycleJournalPath");
        Objects.requireNonNull(rawCapturePath, "rawCapturePath");
        Objects.requireNonNull(allowedWallClockSkew, "allowedWallClockSkew");
        if (allowedWallClockSkew.isNegative()) throw new IllegalArgumentException("allowedWallClockSkew must be non-negative");

        Accumulator accumulator = new Accumulator();
        FeedLifecycleSummary firstPassSummary = FeedLifecycleJournalReader.replay(lifecycleJournalPath, accumulator::accept);
        FeedLifecycleAnalysis analysis = FeedLifecycleAnalyzer.analyze(lifecycleJournalPath);
        if (!firstPassSummary.equals(analysis.journal())) {
            throw new IOException("Feed lifecycle journal changed between reconciliation passes");
        }
        RawCaptureSummary rawSummary = RawCaptureJournalReader.replay(rawCapturePath,
                message -> accumulator.accept(message, allowedWallClockSkew));
        return accumulator.report(analysis, rawSummary, allowedWallClockSkew);
    }

    private static final class Accumulator {
        private final Map<SubscriptionKey, SubscriptionTimeline> timelines = new HashMap<>();
        private final Map<SubscriptionKey, Interval> openIntervals = new HashMap<>();
        private final Map<ConnectionKey, Instant> lastObservedAt = new HashMap<>();
        private final Set<ConnectionKey> uncertainConnections = new HashSet<>();
        private final Set<Interval> intervalsWithMessages = new HashSet<>();
        private final EnumMap<FeedCaptureReconciliationReport.MessageDisposition, Long> messageCounts =
                new EnumMap<>(FeedCaptureReconciliationReport.MessageDisposition.class);
        private long intervalCount;
        private long closedByUnsubscription;
        private long closedByConnectionLoss;
        private long closedByTimelineAnomaly;
        private long timestampRegressions;
        private long timelineAnomalies;

        private Accumulator() {
            for (FeedCaptureReconciliationReport.MessageDisposition disposition
                    : FeedCaptureReconciliationReport.MessageDisposition.values()) {
                messageCounts.put(disposition, 0L);
            }
        }

        private void accept(FeedLifecycleEvent event) {
            ConnectionKey connection = new ConnectionKey(event.source(), event.connectionId());
            Instant prior = lastObservedAt.put(connection, event.observedAtUtc());
            if (prior != null && event.observedAtUtc().isBefore(prior)) {
                timestampRegressions = Math.addExact(timestampRegressions, 1);
                uncertainConnections.add(connection);
            }

            switch (event.type()) {
                case SUBSCRIPTION_ACKNOWLEDGED -> open(event);
                case UNSUBSCRIPTION_ACKNOWLEDGED -> close(event, true);
                case CONNECTION_FAILED, DISCONNECTED -> closeConnection(connection, event.observedAtUtc());
                default -> { }
            }
        }

        private void open(FeedLifecycleEvent event) {
            SubscriptionKey key = key(event);
            SubscriptionTimeline timeline = timelines.computeIfAbsent(key, ignored -> new SubscriptionTimeline());
            if (openIntervals.containsKey(key)) {
                timeline.anomalous = true;
                timelineAnomalies = Math.addExact(timelineAnomalies, 1);
                closeInterval(key, event.observedAtUtc(), CloseReason.TIMELINE_ANOMALY);
            }
            Interval interval = new Interval(event.observedAtUtc());
            timeline.intervals.add(interval);
            openIntervals.put(key, interval);
            intervalCount = Math.addExact(intervalCount, 1);
        }

        private void close(FeedLifecycleEvent event, boolean byUnsubscription) {
            SubscriptionKey key = key(event);
            if (!openIntervals.containsKey(key)) {
                SubscriptionTimeline timeline = timelines.computeIfAbsent(key, ignored -> new SubscriptionTimeline());
                timeline.anomalous = true;
                timelineAnomalies = Math.addExact(timelineAnomalies, 1);
                return;
            }
            closeInterval(key, event.observedAtUtc(), byUnsubscription
                    ? CloseReason.UNSUBSCRIPTION : CloseReason.CONNECTION_LOSS);
        }

        private void closeConnection(ConnectionKey connection, Instant at) {
            List<SubscriptionKey> openOnConnection = openIntervals.keySet().stream()
                    .filter(key -> key.source().equals(connection.source())
                            && key.connectionId().equals(connection.connectionId()))
                    .toList();
            for (SubscriptionKey key : openOnConnection) closeInterval(key, at, CloseReason.CONNECTION_LOSS);
        }

        private void closeInterval(SubscriptionKey key, Instant at, CloseReason reason) {
            Interval interval = openIntervals.remove(key);
            if (interval == null) return;
            if (at.isBefore(interval.start)) {
                timelines.get(key).anomalous = true;
                timelineAnomalies = Math.addExact(timelineAnomalies, 1);
                interval.end = interval.start;
            } else interval.end = at;
            switch (reason) {
                case UNSUBSCRIPTION -> closedByUnsubscription = Math.addExact(closedByUnsubscription, 1);
                case CONNECTION_LOSS -> closedByConnectionLoss = Math.addExact(closedByConnectionLoss, 1);
                case TIMELINE_ANOMALY -> closedByTimelineAnomaly = Math.addExact(closedByTimelineAnomaly, 1);
            }
        }

        private void accept(RawMarketMessage message, Duration tolerance) {
            SubscriptionKey key = new SubscriptionKey(message.source(), message.connectionId(), message.subscriptionId());
            SubscriptionTimeline timeline = timelines.get(key);
            FeedCaptureReconciliationReport.MessageDisposition disposition;
            if (timeline == null) {
                disposition = FeedCaptureReconciliationReport.MessageDisposition.NO_ACKNOWLEDGED_INTERVAL;
            } else {
                List<Interval> matching = timeline.intervals.stream()
                        .filter(interval -> containsWithTolerance(interval, message.receivedAtUtc(), tolerance))
                        .toList();
                intervalsWithMessages.addAll(matching);
                boolean uncertain = uncertainConnections.contains(
                        new ConnectionKey(message.source(), message.connectionId())) || timeline.anomalous;
                if (uncertain || matching.size() > 1) {
                    disposition = FeedCaptureReconciliationReport.MessageDisposition.TIMELINE_UNCERTAIN;
                } else if (timeline.intervals.isEmpty()) {
                    disposition = FeedCaptureReconciliationReport.MessageDisposition.NO_ACKNOWLEDGED_INTERVAL;
                } else if (matching.isEmpty()) {
                    disposition = FeedCaptureReconciliationReport.MessageDisposition.OUTSIDE_ACKNOWLEDGED_INTERVAL;
                } else {
                    disposition = FeedCaptureReconciliationReport.MessageDisposition.MATCHED_ACKNOWLEDGED_INTERVAL;
                }
            }
            messageCounts.put(disposition, Math.addExact(messageCounts.get(disposition), 1));
        }

        private static boolean containsWithTolerance(Interval interval, Instant at, Duration tolerance) {
            if (at.isBefore(interval.start)
                    && Duration.between(at, interval.start).compareTo(tolerance) > 0) return false;
            return interval.end == null || !at.isAfter(interval.end)
                    || Duration.between(interval.end, at).compareTo(tolerance) <= 0;
        }

        private FeedCaptureReconciliationReport report(FeedLifecycleAnalysis lifecycle,
                RawCaptureSummary raw, Duration tolerance) {
            return new FeedCaptureReconciliationReport(lifecycle, raw, tolerance, intervalCount,
                    closedByUnsubscription, closedByConnectionLoss, closedByTimelineAnomaly, openIntervals.size(),
                    intervalsWithMessages.size(), timestampRegressions, timelineAnomalies, messageCounts);
        }

        private static SubscriptionKey key(FeedLifecycleEvent event) {
            return new SubscriptionKey(event.source(), event.connectionId(), event.subscriptionId().orElseThrow());
        }
    }
}
