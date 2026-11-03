package com.mscient.intrapaper.provenance;

/** Explicit policy for positions at session and data boundaries in offline replay. */
public enum CrossSessionPositionPolicy {
    ALLOW_OVERNIGHT,
    REQUIRE_FLAT,
    AUTO_FLATTEN_SESSION_END;

    public boolean requiresFlatAtSessionEnd() {
        return this != ALLOW_OVERNIGHT;
    }
}
