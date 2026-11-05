package com.mscient.intrapaper.simulation.portfolio;

/** Settlement is performed only for explicitly declared contracts and sourced events. */
public enum PortfolioSettlementPolicy {
    NONE,
    EXPLICIT_FINAL_SESSION
}
