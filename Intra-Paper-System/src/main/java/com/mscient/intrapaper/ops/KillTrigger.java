package com.mscient.intrapaper.ops;

/** Fixed diagnostic signal vocabulary mapped to the plan's K0-K3 responses. */
public enum KillTrigger {
    SOFT_DAILY_LOSS(KillLevel.K0_PAUSE),
    STALE_FEED(KillLevel.K0_PAUSE),
    SEQUENCE_GAP(KillLevel.K0_PAUSE),
    CLOCK_DIVERGENCE(KillLevel.K0_PAUSE),
    UNKNOWN_INSTRUMENT(KillLevel.K0_PAUSE),
    REJECT_OR_OPS_STORM(KillLevel.K1_CANCEL_AND_BLOCK),
    RISK_STATE_MISMATCH(KillLevel.K1_CANCEL_AND_BLOCK),
    WATCHDOG_HEARTBEAT_LOSS(KillLevel.K1_CANCEL_AND_BLOCK),
    BROAD_FEED_FAILURE(KillLevel.K1_CANCEL_AND_BLOCK),
    HARD_DAILY_LOSS(KillLevel.K2_FLATTEN_AND_HALT),
    HARD_MARGIN_LIMIT(KillLevel.K2_FLATTEN_AND_HALT),
    STRESSED_LOSS_LIMIT(KillLevel.K2_FLATTEN_AND_HALT),
    REPEATED_K1_ESCALATION(KillLevel.K2_FLATTEN_AND_HALT),
    HUMAN_K2_TRIGGER(KillLevel.K2_FLATTEN_AND_HALT),
    SUSPECTED_COMPROMISE(KillLevel.K3_DISCONNECT),
    RUNAWAY_PROCESS(KillLevel.K3_DISCONNECT),
    HUMAN_K3_TRIGGER(KillLevel.K3_DISCONNECT);

    private final KillLevel level;
    KillTrigger(KillLevel level) { this.level = level; }
    public KillLevel level() { return level; }
}
