package com.mscient.intrapaper.ops;

import java.time.Duration;
import java.util.Objects;

/** Explicit, caller-reviewed state-machine timings; no operational threshold is silently defaulted. */
public record KillPolicy(Duration k0HealthyClearDuration, Duration maximumHealthyObservationGap,
        int k1IncidentsBeforeK2) {
    public KillPolicy {
        Objects.requireNonNull(k0HealthyClearDuration, "k0HealthyClearDuration");
        Objects.requireNonNull(maximumHealthyObservationGap, "maximumHealthyObservationGap");
        if (k0HealthyClearDuration.isNegative() || k0HealthyClearDuration.isZero() || maximumHealthyObservationGap.isNegative()
                || maximumHealthyObservationGap.isZero() || k1IncidentsBeforeK2 < 1) {
            throw new IllegalArgumentException("Invalid K0-K3 operational policy");
        }
        try {
            k0HealthyClearDuration.toNanos();
            maximumHealthyObservationGap.toNanos();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("K0-K3 durations must fit a signed 64-bit nanosecond counter", overflow);
        }
    }
}
