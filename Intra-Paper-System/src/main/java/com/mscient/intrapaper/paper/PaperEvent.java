package com.mscient.intrapaper.paper;

import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.marketdata.MarketQuote;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Normalized local input with preserved clocks. Malformed payloads are represented by a typed finding. */
public record PaperEvent(long sequence, Kind kind, Instant sourceTime, Instant receivedAt,
        long receiveNanos, Optional<Bar> bar, Optional<MarketQuote> quote) {
    public enum Kind { OPEN, BAR, QUOTE, DISCONNECT, RECONNECT, RECOVER, HEARTBEAT, MALFORMED, OPERATOR_STOP, SESSION_CLOSE }
    public PaperEvent {
        if (sequence < 1) throw new IllegalArgumentException("Event sequence must be positive");
        Objects.requireNonNull(kind); Objects.requireNonNull(sourceTime); Objects.requireNonNull(receivedAt);
        Objects.requireNonNull(bar); Objects.requireNonNull(quote);
        if (bar.isPresent() != (kind == Kind.BAR || kind == Kind.OPEN) || quote.isPresent() != (kind == Kind.QUOTE))
            throw new IllegalArgumentException("Event kind and normalized payload disagree");
        if (quote.isPresent() && (!quote.orElseThrow().receivedAtUtc().equals(receivedAt)
                || quote.orElseThrow().receivedAtMonotonicNanos() != receiveNanos
                || !quote.orElseThrow().exchangeTimestampUtc().orElse(receivedAt).equals(sourceTime)))
            throw new IllegalArgumentException("Quote envelope must preserve original clocks");
    }
    public static PaperEvent control(long sequence, Kind kind, Instant time, long nanos) {
        return new PaperEvent(sequence, kind, time, time, nanos, Optional.empty(), Optional.empty());
    }
}
