package com.mscient.intrapaper.marketdata;

/** Data-quality findings retained with normalized quote snapshots. */
public enum QuoteQualityFlag {
    MISSING_EXCHANGE_TIMESTAMP,
    NO_BID,
    NO_ASK,
    INVALID_BID_PRICE,
    INVALID_ASK_PRICE,
    CROSSED_OR_LOCKED
}
