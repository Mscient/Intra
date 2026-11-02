package com.mscient.intrapaper.marketdata;

/** Integrity summary returned after a complete journal scan. */
public record QuoteJournalSummary(long eventCount, long lastSequenceNumber, String finalHashHex, long bytesRead) {}
