package com.mscient.intrapaper.ops;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Pure recommendation from the control state machine; it does not execute any response. */
public record KillDecision(String observationId, LocalDate sessionDate, KillLevel previousLevel,
        KillLevel level, boolean newlyEscalated, boolean automaticallyCleared,
        boolean riskIncreasingActionsAllowed, Set<KillTrigger> activeTriggers,
        List<Response> requiredResponses) {
    public enum Response {
        CONTINUE_MONITORING,
        PAUSE_NEW_RISK,
        CANCEL_WORKING_ORDERS_AND_BLOCK_NEW_RISK,
        REQUEST_PROTECTED_RISK_REDUCTION_AND_HALT,
        DISCONNECT_PROCESS_AND_USE_HUMAN_MANUAL_PATH
    }

    public KillDecision {
        activeTriggers = Set.copyOf(activeTriggers);
        requiredResponses = List.copyOf(requiredResponses);
        if (level == null || previousLevel == null || sessionDate == null) {
            throw new IllegalArgumentException("Kill decision state is incomplete");
        }
        if (riskIncreasingActionsAllowed != (level == KillLevel.NONE)) {
            throw new IllegalArgumentException("Risk permission must match the latched kill level");
        }
    }
}
