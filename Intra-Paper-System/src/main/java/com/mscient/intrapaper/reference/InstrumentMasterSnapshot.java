package com.mscient.intrapaper.reference;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Immutable, hashed instrument-master snapshot. It cannot resolve contracts before its effective date. */
public record InstrumentMasterSnapshot(
        int schemaVersion,
        String snapshotId,
        String sourceName,
        Instant retrievedAtUtc,
        LocalDate effectiveFrom,
        String rawFileSha256,
        List<InstrumentReference> instruments) {

    public InstrumentMasterSnapshot {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported InstrumentMasterSnapshot schema version: " + schemaVersion);
        }
        snapshotId = requireNonBlank(snapshotId, "snapshotId");
        sourceName = requireNonBlank(sourceName, "sourceName");
        retrievedAtUtc = Objects.requireNonNull(retrievedAtUtc, "retrievedAtUtc");
        effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        requireSha256(rawFileSha256, "rawFileSha256");
        instruments = List.copyOf(Objects.requireNonNull(instruments, "instruments"));
        if (instruments.isEmpty()) {
            throw new IllegalArgumentException("Instrument master snapshot must not be empty");
        }
    }

    /** Resolves one contract for a session date; unknown, stale, or ambiguous mappings fail closed. */
    public InstrumentReference requireContract(String exchange, String segment, String sourceToken, LocalDate asOf) {
        String requestedExchange = requireNonBlank(exchange, "exchange");
        String requestedSegment = requireNonBlank(segment, "segment");
        String requestedToken = requireNonBlank(sourceToken, "sourceToken");
        Objects.requireNonNull(asOf, "asOf");
        if (asOf.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("Instrument master snapshot is not effective for " + asOf);
        }

        List<InstrumentReference> matches = instruments.stream()
                .filter(item -> item.exchange().equals(requestedExchange))
                .filter(item -> item.segment().equals(requestedSegment))
                .filter(item -> item.sourceToken().equals(requestedToken))
                .filter(item -> item.isEffectiveOn(asOf))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Unknown or ineffective contract mapping for token " + requestedToken);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Ambiguous contract mapping for token " + requestedToken);
        }
        return matches.getFirst();
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(fieldName + " must be non-blank and trimmed");
        }
        return value;
    }

    private static void requireSha256(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(fieldName + " must be a lowercase SHA-256 hex digest");
        }
    }
}
