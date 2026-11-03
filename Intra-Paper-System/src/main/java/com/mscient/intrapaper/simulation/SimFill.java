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
        int positionUnitsAfter,
        String executionModel,
        String quoteSource,
        long quoteSequence,
        Instant quoteReceivedAt,
        BigDecimal referenceExecutionPrice) implements java.io.Serializable {
    public SimFill {
        Objects.requireNonNull(marginPerLotInr, "marginPerLotInr");
        Objects.requireNonNull(marginSourceReference, "marginSourceReference");
        Objects.requireNonNull(executionModel, "executionModel");
        Objects.requireNonNull(referenceExecutionPrice, "referenceExecutionPrice");
        if (marginPerLotInr.signum() < 0) throw new IllegalArgumentException("marginPerLotInr must be non-negative");
        if (marginSourceReference.isBlank() || !marginSourceReference.equals(marginSourceReference.strip())) {
            throw new IllegalArgumentException("marginSourceReference must be non-empty and trimmed");
        }
        if (!executionModel.equals("BAR_NEXT_OPEN") && !executionModel.equals("L1_FULL_SIZE_IOC")) {
            throw new IllegalArgumentException("Unsupported simulated execution model: " + executionModel);
        }
        boolean hasQuoteReference = quoteSource != null && !quoteSource.isBlank()
                && quoteSequence > 0 && quoteReceivedAt != null;
        boolean hasAnyQuoteReference = quoteSource != null || quoteSequence != 0 || quoteReceivedAt != null;
        if (executionModel.equals("L1_FULL_SIZE_IOC") != hasQuoteReference
                || hasAnyQuoteReference && !hasQuoteReference) {
            throw new IllegalArgumentException("Quote execution fills require a complete quote source/sequence/receive-time reference");
        }
        if (referenceExecutionPrice.signum() <= 0) {
            throw new IllegalArgumentException("referenceExecutionPrice must be positive");
        }
    }

    /** Backward-compatible constructor for legacy bar-open replay fills. */
    public SimFill(String fillId, String orderId, Instant timestamp, String side, int quantityUnits,
            BigDecimal referenceOpenPrice, BigDecimal fillPrice, BigDecimal slippageCostInr, BigDecimal feeInr,
            BigDecimal marginPerLotInr, String marginSourceReference, int positionUnitsAfter) {
        this(fillId, orderId, timestamp, side, quantityUnits, referenceOpenPrice, fillPrice, slippageCostInr,
                feeInr, marginPerLotInr, marginSourceReference, positionUnitsAfter, "BAR_NEXT_OPEN",
                null, 0, null, referenceOpenPrice);
    }
}
