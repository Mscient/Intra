package com.mscient.intrapaper.marketdata;

import java.util.EnumMap;
import java.util.Map;

/** Integrity summary and event counts from a complete feed lifecycle journal scan. */
public record FeedLifecycleSummary(long eventCount, long bytesRead, String finalHashHex,
        long connectionCount, Map<FeedLifecycleEvent.Type, Long> eventCounts) {
    public FeedLifecycleSummary {
        if (eventCount < 0 || bytesRead < 0 || connectionCount < 0) {
            throw new IllegalArgumentException("Feed lifecycle summary counts must be non-negative");
        }
        if (finalHashHex == null || !finalHashHex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid feed lifecycle hash");
        }
        if (eventCounts == null) throw new NullPointerException("eventCounts");
        EnumMap<FeedLifecycleEvent.Type, Long> stableCounts = new EnumMap<>(FeedLifecycleEvent.Type.class);
        long total = 0;
        for (FeedLifecycleEvent.Type type : FeedLifecycleEvent.Type.values()) {
            Long count = eventCounts.get(type);
            if (count == null || count < 0) throw new IllegalArgumentException("Missing or invalid event count: " + type);
            total = Math.addExact(total, count);
            stableCounts.put(type, count);
        }
        if (eventCounts.size() != FeedLifecycleEvent.Type.values().length || total != eventCount
                || connectionCount > eventCount) {
            throw new IllegalArgumentException("Feed lifecycle event counts do not reconcile");
        }
        eventCounts = Map.copyOf(stableCounts);
    }
}
