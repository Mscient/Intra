package com.mscient.intrapaper.data;

import java.time.Duration;
import java.util.Map;

/** Intervals with a defined duration for timestamp and next-bar semantics. */
public final class IntervalSupport {
    private static final Map<String, Duration> KNOWN = Map.of(
            "ONE_MINUTE", Duration.ofMinutes(1),
            "FIVE_MINUTE", Duration.ofMinutes(5),
            "FIFTEEN_MINUTE", Duration.ofMinutes(15),
            "THIRTY_MINUTE", Duration.ofMinutes(30),
            "ONE_HOUR", Duration.ofHours(1),
            "ONE_DAY", Duration.ofDays(1));

    private IntervalSupport() { }

    public static Duration duration(String value) {
        Duration duration = KNOWN.get(value);
        if (duration == null) throw new IllegalArgumentException("Unsupported interval (no timestamp guessing): " + value);
        return duration;
    }
}
