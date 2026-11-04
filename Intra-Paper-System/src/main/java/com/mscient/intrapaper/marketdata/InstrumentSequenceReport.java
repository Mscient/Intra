package com.mscient.intrapaper.marketdata;

import java.util.List;
import java.util.OptionalLong;

/** Instrument-attributed source-sequence diagnostics over a verified saved raw capture. */
public record InstrumentSequenceReport(RawCaptureSummary rawCapture, String sequenceDomain,
        long nonQuoteMessageCount, long decodedQuoteCount, List<StreamResult> streams) {
    public InstrumentSequenceReport {
        streams = List.copyOf(streams);
    }

    public record StreamResult(String source, String connectionId, String subscriptionId, String instrumentId,
            long quoteCount, long sequencedQuoteCount, long quotesWithoutSequence,
            OptionalLong firstSourceSequence, OptionalLong lastSourceSequence,
            long sequenceGapCount, long missingSequenceNumbers, long nonIncreasingTransitions) { }
}
