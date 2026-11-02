package com.mscient.intrapaper.reference;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/** Effective-dated contract metadata resolved from one immutable instrument-master snapshot. */
public record InstrumentReference(
        int schemaVersion,
        String instrumentId,
        String exchange,
        String segment,
        String sourceToken,
        String tradingSymbol,
        ContractKind contractKind,
        Optional<String> underlying,
        Optional<LocalDate> expiry,
        Optional<BigDecimal> strike,
        Optional<OptionRight> optionRight,
        int lotSize,
        BigDecimal tickSize,
        LocalDate effectiveFrom,
        Optional<LocalDate> effectiveThrough) {

    public InstrumentReference {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported InstrumentReference schema version: " + schemaVersion);
        }
        instrumentId = requireNonBlank(instrumentId, "instrumentId");
        exchange = requireNonBlank(exchange, "exchange");
        segment = requireNonBlank(segment, "segment");
        sourceToken = requireNonBlank(sourceToken, "sourceToken");
        tradingSymbol = requireNonBlank(tradingSymbol, "tradingSymbol");
        contractKind = Objects.requireNonNull(contractKind, "contractKind");
        underlying = Objects.requireNonNull(underlying, "underlying")
                .map(value -> requireNonBlank(value, "underlying"));
        expiry = Objects.requireNonNull(expiry, "expiry");
        strike = Objects.requireNonNull(strike, "strike")
                .map(value -> Objects.requireNonNull(value, "strike value").stripTrailingZeros());
        optionRight = Objects.requireNonNull(optionRight, "optionRight");
        tickSize = Objects.requireNonNull(tickSize, "tickSize").stripTrailingZeros();
        effectiveFrom = Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        effectiveThrough = Objects.requireNonNull(effectiveThrough, "effectiveThrough");

        if (tickSize.signum() <= 0) {
            throw new IllegalArgumentException("tickSize must be positive");
        }
        if (effectiveThrough.isPresent() && effectiveThrough.orElseThrow().isBefore(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveThrough must not precede effectiveFrom");
        }
        validateKindFields(contractKind, lotSize, underlying, expiry, strike, optionRight);
    }

    public boolean isEffectiveOn(LocalDate date) {
        Objects.requireNonNull(date, "date");
        return !date.isBefore(effectiveFrom)
                && effectiveThrough.map(through -> !date.isAfter(through)).orElse(true);
    }

    private static void validateKindFields(
            ContractKind kind,
            int lotSize,
            Optional<String> underlying,
            Optional<LocalDate> expiry,
            Optional<BigDecimal> strike,
            Optional<OptionRight> optionRight) {
        switch (kind) {
            case INDEX -> {
                if (lotSize != 0 || expiry.isPresent() || strike.isPresent() || optionRight.isPresent()) {
                    throw new IllegalArgumentException("Index references require lot size 0 and no expiry/strike/right");
                }
            }
            case EQUITY -> {
                if (lotSize < 1 || expiry.isPresent() || strike.isPresent() || optionRight.isPresent()) {
                    throw new IllegalArgumentException("Equity references require positive lot size and no derivative fields");
                }
            }
            case FUTURE -> {
                if (lotSize < 1 || underlying.isEmpty() || expiry.isEmpty()
                        || strike.isPresent() || optionRight.isPresent()) {
                    throw new IllegalArgumentException("Future references require underlying, expiry, and positive lot size");
                }
            }
            case OPTION -> {
                if (lotSize < 1 || underlying.isEmpty() || expiry.isEmpty()
                        || strike.isEmpty() || strike.orElseThrow().signum() <= 0 || optionRight.isEmpty()) {
                    throw new IllegalArgumentException("Option references require underlying, expiry, positive strike, right, and lot size");
                }
            }
        }
    }

    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(fieldName + " must be non-blank and trimmed");
        }
        return value;
    }
}
