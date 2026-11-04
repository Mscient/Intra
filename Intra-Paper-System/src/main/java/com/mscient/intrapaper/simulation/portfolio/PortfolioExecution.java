package com.mscient.intrapaper.simulation.portfolio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/** Caller-supplied simulated fill for portfolio accounting; it cannot submit an order. */
public record PortfolioExecution(
        String executionId,
        long sequence,
        String instrumentId,
        Instant timestamp,
        Side side,
        long quantityUnits,
        BigDecimal price,
        BigDecimal feeInr) {

    public enum Side { BUY, SELL }

    public PortfolioExecution {
        executionId = required(executionId, "executionId");
        instrumentId = required(instrumentId, "instrumentId");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be positive");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(side, "side");
        if (quantityUnits < 1) throw new IllegalArgumentException("quantityUnits must be positive");
        price = Objects.requireNonNull(price, "price").stripTrailingZeros();
        feeInr = Objects.requireNonNull(feeInr, "feeInr").stripTrailingZeros();
        if (price.signum() <= 0) throw new IllegalArgumentException("price must be positive");
        if (feeInr.signum() < 0) throw new IllegalArgumentException("feeInr must be non-negative");
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be non-empty and trimmed");
        }
        return value;
    }
}
