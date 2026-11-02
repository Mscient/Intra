package com.mscient.intrapaper.simulation;

import java.util.Map;
import java.util.Set;

/** Allowed local-only order transitions; this does not model broker or exchange state. */
public final class SimOrderLifecycle {
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "CREATED", Set.of("ACKNOWLEDGED_BY_SIMULATOR", "EXPIRED_SESSION_BOUNDARY", "EXPIRED_NO_NEXT_BAR"),
            "ACKNOWLEDGED_BY_SIMULATOR", Set.of("FILLED", "REJECTED_RISK", "REJECTED_NO_OP"));

    private SimOrderLifecycle() { }

    public static void requireTransition(String from, String to) {
        if (!ALLOWED.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException("Invalid simulated order transition: " + from + " -> " + to);
        }
    }
}
