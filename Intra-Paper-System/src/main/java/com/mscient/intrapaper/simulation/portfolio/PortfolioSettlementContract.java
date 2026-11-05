package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.reference.ContractKind;
import com.mscient.intrapaper.reference.OptionRight;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Exact dated identity: no token, expiry, option, lot, or tick fallback. */
public record PortfolioSettlementContract(String instrumentId, String exchange, String segment,
        String sourceToken, String tradingSymbol, ContractKind contractKind, String underlying,
        LocalDate expiry, Optional<BigDecimal> strike, Optional<OptionRight> optionRight,
        int lotSize, BigDecimal tickSize) {
    public static final List<String> HEADERS = List.of("instrument_id", "exchange", "segment", "source_token",
            "trading_symbol", "contract_kind", "underlying", "expiry", "strike", "option_right", "lot_size", "tick_size");

    public PortfolioSettlementContract {
        id(instrumentId, "instrument_id");
        for (String s : List.of(exchange, segment, sourceToken, tradingSymbol, underlying)) text(s, "identity");
        Objects.requireNonNull(expiry, "expiry");
        Objects.requireNonNull(strike, "strike");
        Objects.requireNonNull(optionRight, "optionRight");
        if (contractKind != ContractKind.FUTURE && contractKind != ContractKind.OPTION)
            throw new IllegalArgumentException("Settlement supports FUTURE and OPTION only");
        if ((contractKind == ContractKind.OPTION) != (strike.isPresent() && optionRight.isPresent())
                || (contractKind == ContractKind.FUTURE && (strike.isPresent() || optionRight.isPresent())))
            throw new IllegalArgumentException("Strike/right must both be supplied for OPTION and blank for FUTURE");
        strike = strike.map(value -> positive(value, "strike").stripTrailingZeros());
        tickSize = positive(tickSize, "tick_size").stripTrailingZeros();
        if (lotSize < 1) throw new IllegalArgumentException("lot_size must be positive");
    }

    public static PortfolioSettlementContract from(Bar bar) {
        return new PortfolioSettlementContract(bar.instrumentId(), bar.exchange(), bar.segment(), bar.sourceToken(),
                bar.tradingSymbol(), bar.contractKind(), bar.underlying(), bar.expiry(), bar.strike(),
                bar.optionRight(), bar.lotSize(), bar.tickSize());
    }

    static PortfolioSettlementContract parse(Map<String, String> row) {
        String strike = optional(row, "strike"), right = optional(row, "option_right");
        return new PortfolioSettlementContract(required(row, "instrument_id"), required(row, "exchange"),
                required(row, "segment"), required(row, "source_token"), required(row, "trading_symbol"),
                ContractKind.valueOf(required(row, "contract_kind")), required(row, "underlying"),
                LocalDate.parse(required(row, "expiry")), strike.isEmpty() ? Optional.empty() : Optional.of(new BigDecimal(strike)),
                right.isEmpty() ? Optional.empty() : Optional.of(OptionRight.valueOf(right)),
                Integer.parseInt(required(row, "lot_size")), new BigDecimal(required(row, "tick_size")));
    }

    List<String> cells() {
        return List.of(instrumentId, exchange, segment, sourceToken, tradingSymbol, contractKind.name(), underlying,
                expiry.toString(), strike.map(BigDecimal::toPlainString).orElse(""), optionRight.map(Enum::name).orElse(""),
                Integer.toString(lotSize), tickSize.toPlainString());
    }

    static String required(Map<String, String> row, String key) { return text(row.get(key), key); }
    static String optional(Map<String, String> row, String key) {
        String value = Objects.requireNonNull(row.get(key), key);
        if (!value.equals(value.strip())) throw new IllegalArgumentException(key + " must be trimmed");
        return value;
    }
    static String text(String value, String key) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.contains("\n") || value.contains("\r"))
            throw new IllegalArgumentException(key + " must be non-empty, trimmed, single-line text");
        return value;
    }
    static String id(String value, String key) {
        if (!text(value, key).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
            throw new IllegalArgumentException("Invalid " + key);
        return value;
    }
    static String hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid input SHA-256");
        return value;
    }
    static boolean bool(Map<String, String> row, String key) {
        return switch (required(row, key)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(key + " must be exactly true or false");
        };
    }
    static BigDecimal nonnegative(BigDecimal value, String key) {
        if (Objects.requireNonNull(value, key).signum() < 0) throw new IllegalArgumentException(key + " must be non-negative");
        return value;
    }
    private static BigDecimal positive(BigDecimal value, String key) {
        if (Objects.requireNonNull(value, key).signum() <= 0) throw new IllegalArgumentException(key + " must be positive");
        return value;
    }
}
