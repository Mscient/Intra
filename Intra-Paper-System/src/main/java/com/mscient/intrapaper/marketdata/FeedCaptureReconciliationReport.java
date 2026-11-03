package com.mscient.intrapaper.marketdata;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/** Diagnostic cross-check between a verified raw capture and its feed-lifecycle journal. */
public record FeedCaptureReconciliationReport(FeedLifecycleAnalysis lifecycle,
        RawCaptureSummary rawCapture, Duration allowedWallClockSkew,
        long acknowledgedIntervalCount, long closedByUnsubscriptionCount,
        long closedByConnectionLossCount, long closedByTimelineAnomalyCount, long stillOpenIntervalCount,
        long intervalsWithCapturedMessages, long lifecycleTimestampRegressionCount,
        long lifecycleTimelineAnomalyCount,
        Map<MessageDisposition, Long> messageCounts) {

    public enum MessageDisposition {
        MATCHED_ACKNOWLEDGED_INTERVAL,
        NO_ACKNOWLEDGED_INTERVAL,
        OUTSIDE_ACKNOWLEDGED_INTERVAL,
        TIMELINE_UNCERTAIN
    }

    public FeedCaptureReconciliationReport {
        if (lifecycle == null || rawCapture == null || allowedWallClockSkew == null
                || allowedWallClockSkew.isNegative() || messageCounts == null
                || acknowledgedIntervalCount < 0 || closedByUnsubscriptionCount < 0
                || closedByConnectionLossCount < 0 || closedByTimelineAnomalyCount < 0 || stillOpenIntervalCount < 0
                || intervalsWithCapturedMessages < 0 || lifecycleTimestampRegressionCount < 0
                || lifecycleTimelineAnomalyCount < 0) {
            throw new IllegalArgumentException("Invalid feed/capture reconciliation report");
        }
        long accountedIntervals = Math.addExact(Math.addExact(closedByUnsubscriptionCount,
                closedByConnectionLossCount), Math.addExact(closedByTimelineAnomalyCount, stillOpenIntervalCount));
        if (accountedIntervals != acknowledgedIntervalCount || intervalsWithCapturedMessages > acknowledgedIntervalCount) {
            throw new IllegalArgumentException("Subscription interval counts do not reconcile");
        }
        EnumMap<MessageDisposition, Long> stableCounts = new EnumMap<>(MessageDisposition.class);
        long total = 0;
        for (MessageDisposition disposition : MessageDisposition.values()) {
            Long count = messageCounts.get(disposition);
            if (count == null || count < 0) throw new IllegalArgumentException("Missing or invalid message count: " + disposition);
            stableCounts.put(disposition, count);
            total = Math.addExact(total, count);
        }
        if (messageCounts.size() != MessageDisposition.values().length || total != rawCapture.eventCount()) {
            throw new IllegalArgumentException("Raw-message dispositions do not reconcile");
        }
        messageCounts = Map.copyOf(stableCounts);
    }
}
