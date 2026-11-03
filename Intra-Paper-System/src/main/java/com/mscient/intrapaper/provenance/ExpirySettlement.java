package com.mscient.intrapaper.provenance;

import com.mscient.intrapaper.data.CsvTable;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Explicit, source-attributed final settlement input for the one contract in a replay. */
public record ExpirySettlement(
        String instrumentId,
        LocalDate expiry,
        LocalDate settlementDate,
        Instant settlementTimestamp,
        Kind kind,
        BigDecimal settlementValue,
        Optional<BigDecimal> sebiExerciseTurnoverValuePerUnitInr,
        String sourceReference,
        String sha256) {

    public enum Kind { FUTURES_FINAL_PRICE, UNDERLYING_FINAL_CLOSE }

    public ExpirySettlement {
        requireTrimmed(instrumentId, "instrument_id");
        if (settlementDate.isAfter(expiry)) throw new IllegalArgumentException("settlement_date must not be after expiry");
        if (settlementValue.signum() <= 0) throw new IllegalArgumentException("settlement_value must be positive");
        sebiExerciseTurnoverValuePerUnitInr = Optional.ofNullable(sebiExerciseTurnoverValuePerUnitInr)
                .orElseThrow(() -> new IllegalArgumentException("SEBI exercise turnover value option is required"));
        sebiExerciseTurnoverValuePerUnitInr.ifPresent(value -> {
            if (value.signum() <= 0) throw new IllegalArgumentException("sebi_exercise_turnover_value_per_unit_inr must be positive when supplied");
        });
        if (kind != Kind.UNDERLYING_FINAL_CLOSE && sebiExerciseTurnoverValuePerUnitInr.isPresent()) {
            throw new IllegalArgumentException("SEBI exercise turnover basis is only valid for option settlement");
        }
        requireLogicalId(sourceReference, "source_reference");
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid settlement input SHA-256");
    }

    public static ExpirySettlement load(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        List<String> baseHeaders = List.of("instrument_id", "expiry", "settlement_date", "settlement_timestamp",
                "settlement_kind", "settlement_value", "source_reference");
        List<String> extendedHeaders = List.of("instrument_id", "expiry", "settlement_date", "settlement_timestamp",
                "settlement_kind", "settlement_value", "source_reference", "sebi_exercise_turnover_value_per_unit_inr");
        boolean extended = table.headers().equals(extendedHeaders);
        if (!extended && !table.headers().equals(baseHeaders)) {
            throw new IllegalArgumentException("Settlement CSV headers must be exactly: " + String.join(",", baseHeaders)
                    + " or " + String.join(",", extendedHeaders));
        }
        if (table.rows().size() != 1) {
            throw new IllegalArgumentException("Settlement CSV must contain exactly one row for the one-contract replay");
        }
        Map<String, String> row = table.rows().getFirst();
        try {
            String instrumentId = required(row, "instrument_id");
            LocalDate expiry = LocalDate.parse(required(row, "expiry"));
            LocalDate date = LocalDate.parse(required(row, "settlement_date"));
            Instant timestamp = OffsetDateTime.parse(required(row, "settlement_timestamp")).toInstant();
            Kind kind = Kind.valueOf(required(row, "settlement_kind"));
            BigDecimal value = new BigDecimal(required(row, "settlement_value"));
            Optional<BigDecimal> sebiExerciseTurnover = extended
                    ? optionalPositive(row, "sebi_exercise_turnover_value_per_unit_inr") : Optional.empty();
            String source = required(row, "source_reference");
            return new ExpirySettlement(instrumentId, expiry, date, timestamp, kind, value, sebiExerciseTurnover, source,
                    table.sha256());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid expiry settlement row 2: " + invalid.getMessage(), invalid);
        }
    }

    private static String required(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(key + " must be non-empty and trimmed");
        }
        return value;
    }

    private static Optional<BigDecimal> optionalPositive(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || !value.equals(value.strip())) throw new IllegalArgumentException(key + " must be trimmed");
        if (value.isBlank()) return Optional.empty();
        BigDecimal parsed = new BigDecimal(value);
        if (parsed.signum() <= 0) throw new IllegalArgumentException(key + " must be positive when supplied");
        return Optional.of(parsed.stripTrailingZeros());
    }

    private static void requireTrimmed(String value, String key) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(key + " must be non-empty and trimmed");
        }
    }

    private static void requireLogicalId(String value, String key) {
        requireTrimmed(value, key);
        if (value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\")
                || value.contains("?") || value.contains("#") || value.contains("@")) {
            throw new IllegalArgumentException(key + " must be a logical ID, not a path or URL");
        }
    }
}
