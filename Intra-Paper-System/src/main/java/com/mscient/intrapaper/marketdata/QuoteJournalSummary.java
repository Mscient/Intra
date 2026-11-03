package com.mscient.intrapaper.marketdata;

/** Integrity and sequence-continuity summary returned after a complete journal scan. */
public record QuoteJournalSummary(long eventCount, long lastSequenceNumber, long sequenceGapCount,
        long missingSequenceNumbers, String finalHashHex, long bytesRead) { }
