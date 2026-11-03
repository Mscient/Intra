package com.mscient.intrapaper.marketdata;

import java.util.Map;
import java.util.EnumMap;

/** Reconciled lifecycle state from a verified event journal; it does not describe a live feed. */
public record FeedLifecycleAnalysis(FeedLifecycleSummary journal, long transitionAnomalyCount,
        long activeSubscriptionCount, long unresolvedSubscriptionCount,
        Map<FeedLifecycleAnalyzer.ConnectionState, Long> connectionStateCounts) {
    public FeedLifecycleAnalysis {
        if (journal == null || transitionAnomalyCount < 0 || activeSubscriptionCount < 0
                || unresolvedSubscriptionCount < 0) {
            throw new IllegalArgumentException("Invalid feed lifecycle analysis counts");
        }
        if (connectionStateCounts == null) throw new NullPointerException("connectionStateCounts");
        EnumMap<FeedLifecycleAnalyzer.ConnectionState, Long> stableStates =
                new EnumMap<>(FeedLifecycleAnalyzer.ConnectionState.class);
        long totalConnections = 0;
        for (FeedLifecycleAnalyzer.ConnectionState state : FeedLifecycleAnalyzer.ConnectionState.values()) {
            Long count = connectionStateCounts.get(state);
            if (count == null || count < 0) throw new IllegalArgumentException("Missing or invalid connection state count: " + state);
            totalConnections = Math.addExact(totalConnections, count);
            stableStates.put(state, count);
        }
        if (connectionStateCounts.size() != FeedLifecycleAnalyzer.ConnectionState.values().length
                || totalConnections != journal.connectionCount()) {
            throw new IllegalArgumentException("Feed lifecycle connection states do not reconcile");
        }
        connectionStateCounts = Map.copyOf(stableStates);
    }
}
