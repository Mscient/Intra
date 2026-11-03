package com.mscient.intrapaper.simulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

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
        BigDecimal marginPerLotInr,
        String marginSourceReference,
        int positionUnitsAfter) implements java.io.Serializable {
    public SimFill {
        Objects.requireNonNull(marginPerLotInr, "marginPerLotInr");
        Objects.requireNonNull(marginSourceReference, "marginSourceReference");
        if (marginPerLotInr.signum() < 0) throw new IllegalArgumentException("marginPerLotInr must be non-negative");
        if (marginSourceReference.isBlank() || !marginSourceReference.equals(marginSourceReference.strip())) {
            throw new IllegalArgumentException("marginSourceReference must be non-empty and trimmed");
        }
    }
}
