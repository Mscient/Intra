package com.mscient.intrapaper.marketdata;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.OptionalLong;

/** Exact received payload and capture metadata; payload bytes are defensively copied. */
public record RawMarketMessage(
        int schemaVersion,
        String source,
        String connectionId,
        String subscriptionId,
        OptionalLong sourceSequence,
        Instant receivedAtUtc,
        long receivedAtMonotonicNanos,
        byte[] payload) {

    public RawMarketMessage {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported raw message schema version: " + schemaVersion);
        source = required(source, "source");
        connectionId = required(connectionId, "connectionId");
        subscriptionId = required(subscriptionId, "subscriptionId");
        checkLength(source, "source");
        checkLength(connectionId, "connectionId");
        checkLength(subscriptionId, "subscriptionId");
        sourceSequence = Objects.requireNonNull(sourceSequence, "sourceSequence");
        if (sourceSequence.isPresent() && sourceSequence.getAsLong() < 0) throw new IllegalArgumentException("sourceSequence must be non-negative");
        receivedAtUtc = Objects.requireNonNull(receivedAtUtc, "receivedAtUtc");
        payload = Objects.requireNonNull(payload, "payload").clone();
        if (payload.length == 0 || payload.length > RawCaptureFormat.MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload size is outside the supported range");
        }
    }

    @Override
    public byte[] payload() { return payload.clone(); }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || !value.equals(value.strip())) throw new IllegalArgumentException(field + " must be non-empty and trimmed");
        return value;
    }

    private static void checkLength(String value, String field) {
        if (value.getBytes(StandardCharsets.UTF_8).length > 65_536) throw new IllegalArgumentException(field + " exceeds 65536 UTF-8 bytes");
    }
}
