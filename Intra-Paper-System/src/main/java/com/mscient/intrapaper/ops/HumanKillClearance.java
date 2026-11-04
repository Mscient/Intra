package com.mscient.intrapaper.ops;

import java.util.Objects;

/** Caller attestation for a human-only K1-K3 reset; identifiers are provenance, not authentication. */
public record HumanKillClearance(String operatorId, String reference,
        boolean ordersReconciled, boolean positionsReconciled, boolean incidentReviewed,
        boolean controlsHealthy, boolean processRestartVerified) {
    public HumanKillClearance {
        operatorId = requireText(operatorId, "operatorId");
        reference = requireText(reference, "reference");
    }

    public boolean satisfies(KillLevel level) {
        if (level == KillLevel.NONE) return false;
        return ordersReconciled && positionsReconciled && incidentReviewed && controlsHealthy
                && (level != KillLevel.K3_DISCONNECT || processRestartVerified);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be non-empty, trimmed, and control-free");
        }
        return value;
    }
}
