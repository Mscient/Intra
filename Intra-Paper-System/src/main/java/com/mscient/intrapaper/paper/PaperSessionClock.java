package com.mscient.intrapaper.paper;

import java.time.Instant;

/** Receive time drives the decision clock; equal times retain provider input order. */
public final class PaperSessionClock {
    private Instant now;
    private long nanos = Long.MIN_VALUE;
    public String advance(PaperEvent event) {
        String failure = now != null && event.receivedAt().isBefore(now) ? "RECEIVE_TIME_REVERSED"
                : event.receiveNanos() < nanos ? "MONOTONIC_TIME_REVERSED" : null;
        if (now == null || event.receivedAt().isAfter(now)) now = event.receivedAt();
        nanos = Math.max(nanos, event.receiveNanos());
        return failure;
    }
    public Instant now() { return now; }
    public long nanos() { return nanos; }
}
