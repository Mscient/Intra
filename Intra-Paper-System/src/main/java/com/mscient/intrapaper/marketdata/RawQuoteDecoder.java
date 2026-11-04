package com.mscient.intrapaper.marketdata;

import java.util.Optional;
import java.util.OptionalLong;

/** Caller-supplied offline decoder; empty means the raw message is not a quote snapshot. */
@FunctionalInterface
public interface RawQuoteDecoder {
    Optional<MarketQuote> decode(RawMarketMessage message);

    /** Returns the source sequence for this decoded quote, when the decoder can identify one. */
    default OptionalLong sourceSequence(RawMarketMessage message) {
        return message.sourceSequence();
    }

    /** Names the sequence domain so a report does not imply unrelated counters are comparable. */
    default String sequenceDomain() {
        return "capture_metadata.source_sequence";
    }
}
