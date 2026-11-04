package com.mscient.intrapaper.reference;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/** One half-open, UTC-validity mapping from a venue token to a stable contract identity. */
public record DatedInstrumentMapping(
        String mappingId,
        String exchange,
        String token,
        String instrumentId,
        String tradingSymbol,
        ContractKind instrumentType,
        Optional<LocalDate> expiry,
        Optional<BigDecimal> strike,
        Optional<OptionRight> optionRight,
        Instant effectiveFromUtc,
        Optional<Instant> effectiveUntilUtc,
        String source,
        String sourceReference,
        String sourceFileSha256) {

    public DatedInstrumentMapping {
        mappingId = required(mappingId, "mappingId");
        exchange = required(exchange, "exchange");
        token = required(token, "token");
        instrumentId = required(instrumentId, "instrumentId");
        tradingSymbol = required(tradingSymbol, "tradingSymbol");
        instrumentType = Objects.requireNonNull(instrumentType, "instrumentType");
        expiry = Objects.requireNonNull(expiry, "expiry");
        strike = Objects.requireNonNull(strike, "strike")
                .map(value -> Objects.requireNonNull(value, "strike value").stripTrailingZeros());
        optionRight = Objects.requireNonNull(optionRight, "optionRight");
        effectiveFromUtc = Objects.requireNonNull(effectiveFromUtc, "effectiveFromUtc");
        effectiveUntilUtc = Objects.requireNonNull(effectiveUntilUtc, "effectiveUntilUtc");
        source = required(source, "source");
        sourceReference = required(sourceReference, "sourceReference");
        sourceFileSha256 = required(sourceFileSha256, "sourceFileSha256");
        if (!sourceFileSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("source_file_sha256 must be a lowercase SHA-256 digest");
        }
        if (effectiveUntilUtc.isPresent() && !effectiveUntilUtc.orElseThrow().isAfter(effectiveFromUtc)) {
            throw new IllegalArgumentException("effective_until_utc must be after effective_from_utc");
        }
        switch (instrumentType) {
            case OPTION -> {
                if (expiry.isEmpty() || strike.isEmpty() || strike.orElseThrow().signum() <= 0 || optionRight.isEmpty()) {
                    throw new IllegalArgumentException("OPTION mappings require expiry, positive strike, and CALL or PUT right");
                }
            }
            case FUTURE -> {
                if (expiry.isEmpty() || strike.isPresent() || optionRight.isPresent()) {
                    throw new IllegalArgumentException("FUTURE mappings require expiry and no strike or option right");
                }
            }
            case INDEX, EQUITY -> {
                if (expiry.isPresent() || strike.isPresent() || optionRight.isPresent()) {
                    throw new IllegalArgumentException(instrumentType + " mappings do not accept derivative fields");
                }
            }
        }
    }

    public boolean covers(Instant timestampUtc) {
        Objects.requireNonNull(timestampUtc, "timestampUtc");
        return !timestampUtc.isBefore(effectiveFromUtc)
                && effectiveUntilUtc.map(timestampUtc::isBefore).orElse(true);
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be non-blank and trimmed");
        }
        return value;
    }
}
