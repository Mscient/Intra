package com.mscient.intrapaper.simulation;

import java.time.Instant;

public record SimOrder(
        String orderId,
        Instant decisionTimestamp,
        Instant submitTimestamp,
        Instant fillTimestamp,
        String side,
        int targetLots,
        int requestedUnits,
        String orderType,
        String status,
        String reason) { }
