package com.mscient.intrapaper.ops;

/** Ordered escalation levels from pause through process disconnect. */
public enum KillLevel {
    NONE,
    K0_PAUSE,
    K1_CANCEL_AND_BLOCK,
    K2_FLATTEN_AND_HALT,
    K3_DISCONNECT
}
