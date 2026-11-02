package com.mscient.intrapaper.simulation;

import java.math.BigDecimal;
import java.time.Instant;

public record SimFill(
        String fillId,
        String orderId,
        Instant timestamp,
        String side,
        int quantityUnits,
        BigDecimal referenceOpenPrice,
        BigDecimal fillPrice,
        BigDecimal slippageCostInr,
        BigDecimal feeInr,
        int positionUnitsAfter) { }
