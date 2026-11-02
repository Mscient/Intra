package com.mscient.intrapaper.data;

import com.mscient.intrapaper.reference.ContractKind;
import com.mscient.intrapaper.reference.OptionRight;
import com.mscient.intrapaper.reference.TimestampRole;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/** One normalized candle tied to a dated contract reference. */
public record Bar(
        Instant timestamp,
        LocalDate sessionDate,
        String instrumentId,
        String exchange,
        String segment,
        String sourceToken,
        String tradingSymbol,
        ContractKind contractKind,
        String underlying,
        LocalDate expiry,
        Optional<BigDecimal> strike,
        Optional<OptionRight> optionRight,
        int lotSize,
        BigDecimal tickSize,
        String interval,
        TimestampRole timestampRole,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        Long openInterest,
        String source,
        String rawRecordHash) {

    public Bar {
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        sessionDate = Objects.requireNonNull(sessionDate, "sessionDate");
        instrumentId = required(instrumentId, "instrumentId");
        exchange = required(exchange, "exchange");
        segment = required(segment, "segment");
        sourceToken = required(sourceToken, "sourceToken");
        tradingSymbol = required(tradingSymbol, "tradingSymbol");
        contractKind = Objects.requireNonNull(contractKind, "contractKind");
        underlying = required(underlying, "underlying");
        expiry = Objects.requireNonNull(expiry, "expiry");
        strike = Objects.requireNonNull(strike, "strike").map(value -> Objects.requireNonNull(value, "strike value").stripTrailingZeros());
        optionRight = Objects.requireNonNull(optionRight, "optionRight");
        interval = required(interval, "interval");
        timestampRole = Objects.requireNonNull(timestampRole, "timestampRole");
        tickSize = positive(tickSize, "tickSize");
        open = positive(open, "open");
        high = positive(high, "high");
        low = positive(low, "low");
        close = positive(close, "close");
        if (lotSize < 1 || volume < 0 || (openInterest != null && openInterest < 0)) {
            throw new IllegalArgumentException("lot size must be positive; volume/open interest must be non-negative");
        }
        if (contractKind == ContractKind.FUTURE && (strike.isPresent() || optionRight.isPresent())) {
            throw new IllegalArgumentException("Future bars must not have strike or option right");
        }
        if (contractKind == ContractKind.OPTION && (strike.isEmpty() || strike.orElseThrow().signum() <= 0 || optionRight.isEmpty())) {
            throw new IllegalArgumentException("Option bars require a positive strike and call/put right");
        }
        if (contractKind != ContractKind.FUTURE && contractKind != ContractKind.OPTION) {
            throw new IllegalArgumentException("Only futures and option premium bars are supported");
        }
        if (high.compareTo(low) < 0 || high.compareTo(open) < 0 || high.compareTo(close) < 0
                || low.compareTo(open) > 0 || low.compareTo(close) > 0) {
            throw new IllegalArgumentException("OHLC range is inconsistent at " + timestamp);
        }
        for (BigDecimal price : new BigDecimal[]{open, high, low, close}) {
            if (price.remainder(tickSize).signum() != 0) {
                throw new IllegalArgumentException("Price " + price + " is not aligned to tick size " + tickSize);
            }
        }
        source = required(source, "source");
        rawRecordHash = required(rawRecordHash, "rawRecordHash");
        if (!rawRecordHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("rawRecordHash must be lowercase SHA-256 hex");
        }
    }

    private static BigDecimal positive(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value.stripTrailingZeros();
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be non-blank and trimmed");
        }
        return value;
    }
}
