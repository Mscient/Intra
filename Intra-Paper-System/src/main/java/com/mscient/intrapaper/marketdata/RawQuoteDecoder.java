package com.mscient.intrapaper.marketdata;

import java.util.Optional;

/** Caller-supplied offline decoder; empty means the raw message is not a quote snapshot. */
@FunctionalInterface
public interface RawQuoteDecoder {
    Optional<MarketQuote> decode(RawMarketMessage message);
}
