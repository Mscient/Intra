package com.mscient.intrapaper.ops;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** One caller-supplied health sample; this type contains no source adapter or hidden clock read. */
public record SafetyObservation(String observationId, Instant observedAtUtc,
        long observedAtMonotonicNanos, Set<KillTrigger> activeTriggers) {
    public SafetyObservation {
        observationId = requireText(observationId, "observationId");
        Objects.requireNonNull(observedAtUtc, "observedAtUtc");
        if (observedAtMonotonicNanos < 0) throw new IllegalArgumentException("monotonic time must be non-negative");
        activeTriggers = Set.copyOf(Objects.requireNonNull(activeTriggers, "activeTriggers"));
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be non-empty, trimmed, and control-free");
        }
        return value;
    }
}
