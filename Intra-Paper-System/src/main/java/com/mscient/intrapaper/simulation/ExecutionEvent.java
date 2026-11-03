package com.mscient.intrapaper.simulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Immutable execution identity and economic payload for idempotent OMS ingestion. */
public record ExecutionEvent(
        String executionId,
        String orderId,
        Instant timestamp,
        Side side,
        int quantityUnits,
        BigDecimal price) {

    public enum Side { BUY, SELL }

    public ExecutionEvent {
        executionId = requireNonBlank(executionId, "executionId");
        orderId = requireNonBlank(orderId, "orderId");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(side, "side");
        if (quantityUnits < 1) throw new IllegalArgumentException("quantityUnits must be positive");
        price = Objects.requireNonNull(price, "price").stripTrailingZeros();
        if (price.signum() <= 0) throw new IllegalArgumentException("price must be positive");
    }

    public static ExecutionEvent from(SimFill fill) {
        Objects.requireNonNull(fill, "fill");
        return new ExecutionEvent(fill.fillId(), fill.orderId(), fill.timestamp(),
                Side.valueOf(fill.side()), fill.quantityUnits(), fill.fillPrice());
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must be non-blank and trimmed");
        }
        return value;
    }
}
