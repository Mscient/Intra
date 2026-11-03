package com.mscient.intrapaper.simulation;

/** Allowed local-only order transitions; this does not model broker or exchange state. */
public final class SimOrderLifecycle {
    private SimOrderLifecycle() { }

    public static void requireTransition(String from, String to) {
        OmsOrderStateMachine.requireTransition(mapSimulatorState(from), mapSimulatorState(to));
    }

    static OmsOrderState toOmsState(String state) {
        return mapSimulatorState(state);
    }

    private static OmsOrderState mapSimulatorState(String state) {
        return switch (state) {
            case "ACKNOWLEDGED_BY_SIMULATOR" -> OmsOrderState.WORKING;
            case "REJECTED_RISK", "REJECTED_NO_OP" -> OmsOrderState.REJECTED;
            case "EXPIRED_SESSION_BOUNDARY", "EXPIRED_NO_NEXT_BAR" -> OmsOrderState.EXPIRED;
            default -> OmsOrderState.valueOf(state);
        };
    }
}
