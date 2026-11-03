package com.mscient.intrapaper.marketdata;

public record RawCaptureSummary(
        long eventCount,
        long bytesRead,
        String finalHashHex,
        long upstreamSequencedEventCount,
        long upstreamSequenceStreamCount,
        long upstreamSequenceGapCount,
        long missingUpstreamSequenceNumbers,
        long nonIncreasingUpstreamSequenceTransitions) { }
