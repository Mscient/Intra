package com.mscient.intrapaper.simulation;

import java.util.Map;
import java.util.Set;

/** Legal OMS state changes and remaining-order reservation rules for offline reconciliation. */
public final class OmsOrderStateMachine {
    private static final Map<OmsOrderState, Set<OmsOrderState>> ALLOWED = Map.ofEntries(
            Map.entry(OmsOrderState.CREATED, Set.of(OmsOrderState.PENDING_NEW, OmsOrderState.WORKING,
                    OmsOrderState.REJECTED, OmsOrderState.EXPIRED, OmsOrderState.UNKNOWN)),
            Map.entry(OmsOrderState.PENDING_NEW, Set.of(OmsOrderState.WORKING, OmsOrderState.PARTIALLY_FILLED,
                    OmsOrderState.FILLED, OmsOrderState.REJECTED, OmsOrderState.EXPIRED, OmsOrderState.UNKNOWN)),
            Map.entry(OmsOrderState.WORKING, Set.of(OmsOrderState.PARTIALLY_FILLED, OmsOrderState.FILLED,
                    OmsOrderState.PENDING_CANCEL, OmsOrderState.PENDING_REPLACE, OmsOrderState.CANCELED,
                    OmsOrderState.REJECTED, OmsOrderState.EXPIRED, OmsOrderState.UNKNOWN)),
            Map.entry(OmsOrderState.PARTIALLY_FILLED, Set.of(OmsOrderState.WORKING, OmsOrderState.FILLED,
                    OmsOrderState.PENDING_CANCEL, OmsOrderState.PENDING_REPLACE, OmsOrderState.CANCELED,
                    OmsOrderState.EXPIRED, OmsOrderState.UNKNOWN)),
            Map.entry(OmsOrderState.PENDING_CANCEL, Set.of(OmsOrderState.WORKING, OmsOrderState.PARTIALLY_FILLED,
                    OmsOrderState.FILLED, OmsOrderState.CANCELED, OmsOrderState.UNKNOWN)),
            Map.entry(OmsOrderState.PENDING_REPLACE, Set.of(OmsOrderState.WORKING, OmsOrderState.PARTIALLY_FILLED,
                    OmsOrderState.FILLED, OmsOrderState.CANCELED, OmsOrderState.REJECTED, OmsOrderState.UNKNOWN)),
            Map.entry(OmsOrderState.UNKNOWN, Set.of(OmsOrderState.PENDING_NEW, OmsOrderState.WORKING,
                    OmsOrderState.PARTIALLY_FILLED, OmsOrderState.PENDING_CANCEL,
                    OmsOrderState.PENDING_REPLACE, OmsOrderState.FILLED, OmsOrderState.CANCELED,
                    OmsOrderState.REJECTED, OmsOrderState.EXPIRED)),
            Map.entry(OmsOrderState.FILLED, Set.of()),
            Map.entry(OmsOrderState.CANCELED, Set.of()),
            Map.entry(OmsOrderState.REJECTED, Set.of()),
            Map.entry(OmsOrderState.EXPIRED, Set.of()));

    private OmsOrderStateMachine() { }

    public static boolean canTransition(OmsOrderState from, OmsOrderState to) {
        if (from == null || to == null) return false;
        return from == to || ALLOWED.get(from).contains(to);
    }

    public static void requireTransition(OmsOrderState from, OmsOrderState to) {
        if (!canTransition(from, to)) {
            throw new IllegalStateException("Invalid OMS order transition: " + from + " -> " + to);
        }
    }

    /** Remaining worst-case units reserved in addition to already-applied executions. */
    public static int remainingReservationUnits(OmsOrderState state,
            int requestedWorstCaseUnits, int executedUnits) {
        if (state == null) throw new NullPointerException("state");
        if (requestedWorstCaseUnits < 1 || executedUnits < 0 || executedUnits > requestedWorstCaseUnits) {
            throw new IllegalArgumentException("Order quantities must satisfy 0 <= executed <= requested worst case and requested worst case > 0");
        }
        if (state == OmsOrderState.FILLED && executedUnits != requestedWorstCaseUnits) {
            throw new IllegalArgumentException("Filled order must have all requested units executed");
        }
        if (state == OmsOrderState.PARTIALLY_FILLED
                && (executedUnits == 0 || executedUnits == requestedWorstCaseUnits)) {
            throw new IllegalArgumentException("Partially filled order must have both filled and remaining units");
        }
        if ((state == OmsOrderState.CREATED || state == OmsOrderState.REJECTED) && executedUnits != 0) {
            throw new IllegalArgumentException("Created/rejected order cannot have executions; a replace rejection must preserve the original order state");
        }
        return switch (state) {
            case FILLED, CANCELED, REJECTED, EXPIRED -> 0;
            case CREATED, PENDING_NEW, WORKING, PARTIALLY_FILLED,
                    PENDING_CANCEL, PENDING_REPLACE, UNKNOWN -> requestedWorstCaseUnits - executedUnits;
        };
    }
}
