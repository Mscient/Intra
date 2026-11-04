package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.reference.ContractKind;
import com.mscient.intrapaper.simulation.portfolio.PortfolioReplayEngine.OrderIntent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Caller-defined offline fill assumption; it has no order-submission or broker capability. */
public interface PortfolioFillModel {
    record OpeningMarketPoint(String instrumentId, Instant timestamp, LocalDate sessionDate,
            ContractKind contractKind, BigDecimal openPrice, BigDecimal tickSize, int lotSize) {
        public OpeningMarketPoint {
            Objects.requireNonNull(instrumentId, "instrumentId");
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(sessionDate, "sessionDate");
            Objects.requireNonNull(contractKind, "contractKind");
            Objects.requireNonNull(openPrice, "openPrice");
            Objects.requireNonNull(tickSize, "tickSize");
            if (instrumentId.isBlank() || openPrice.signum() <= 0 || tickSize.signum() <= 0 || lotSize < 1) {
                throw new IllegalArgumentException("Invalid opening market point");
            }
        }
    }

    record Fill(long quantityUnits, BigDecimal price, BigDecimal feeInr) {
        public Fill {
            if (quantityUnits < 1) throw new IllegalArgumentException("fill quantity must be positive");
            Objects.requireNonNull(price, "price");
            Objects.requireNonNull(feeInr, "feeInr");
            if (price.signum() <= 0 || feeInr.signum() < 0) {
                throw new IllegalArgumentException("fill price must be positive and fee non-negative");
            }
            price = price.stripTrailingZeros();
            feeInr = feeInr.stripTrailingZeros();
        }
    }

    String id();

    Map<String, String> parameters();

    /** Empty denotes an IOC miss. Returned partial fills are treated as cancelled IOC residuals. */
    Optional<Fill> fill(OrderIntent intent, OpeningMarketPoint marketAtOpen);
}
