package com.mscient.intrapaper.simulation;

/** Venue-neutral OMS states. These types do not create a broker or exchange adapter. */
public enum OmsOrderState {
    CREATED,
    PENDING_NEW,
    WORKING,
    PARTIALLY_FILLED,
    PENDING_CANCEL,
    PENDING_REPLACE,
    FILLED,
    CANCELED,
    REJECTED,
    EXPIRED,
    UNKNOWN
}
