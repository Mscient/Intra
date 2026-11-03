package com.mscient.intrapaper.marketdata;

/** Verification summaries for one completed raw-capture to normalized-quote import. */
public record RawCaptureQuoteImportResult(
        RawCaptureSummary rawCapture,
        long normalizedQuoteCount,
        QuoteJournalSummary normalizedJournal) { }
