package com.mscient.intrapaper.data;

import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.reference.ContractKind;
import com.mscient.intrapaper.reference.OptionRight;
import com.mscient.intrapaper.reference.TimestampRole;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class BarsCsvReader {
    public record ReadResult(List<Bar> bars, String sha256) {
        public ReadResult {
            bars = List.copyOf(bars);
        }
    }

    private static final String[] REQUIRED = {
            "timestamp", "instrument_id", "exchange", "segment", "source_token", "trading_symbol",
            "contract_kind", "underlying", "expiry", "strike", "option_right", "lot_size", "tick_size", "interval",
            "timestamp_role", "open", "high", "low", "close", "volume", "open_interest",
            "source", "raw_record_hash"
    };

    private BarsCsvReader() { }

    public static List<Bar> read(java.nio.file.Path path, RunConfig config) throws IOException {
        return readWithHash(path, config).bars();
    }

    public static ReadResult readWithHash(java.nio.file.Path path, RunConfig config) throws IOException {
        CsvTable table = CsvTable.read(path);
        table.requireHeaders(REQUIRED);
        List<Bar> bars = new ArrayList<>();
        Instant priorTimestamp = null;
        for (int i = 0; i < table.rows().size(); i++) {
            var row = table.rows().get(i);
            int rowNumber = i + 2;
            try {
                Instant timestamp = OffsetDateTime.parse(required(row, "timestamp")).toInstant();
                if (priorTimestamp != null && !timestamp.isAfter(priorTimestamp)) {
                    throw new IllegalArgumentException("timestamps must be strictly increasing");
                }
                priorTimestamp = timestamp;
                ZoneId timezone = ZoneId.of(config.timezone());
                TimestampRole role = TimestampRole.valueOf(required(row, "timestamp_role"));
                if (role != config.timestampRole() || role == TimestampRole.UNKNOWN || role == TimestampRole.EVENT_TIME) {
                    throw new IllegalArgumentException("row timestamp_role must match configured BAR_START or BAR_END role");
                }
                bars.add(new Bar(
                        timestamp,
                        timestamp.atZone(timezone).toLocalDate(),
                        required(row, "instrument_id"),
                        required(row, "exchange"),
                        required(row, "segment"),
                        required(row, "source_token"),
                        required(row, "trading_symbol"),
                        ContractKind.valueOf(required(row, "contract_kind")),
                        required(row, "underlying"),
                        LocalDate.parse(required(row, "expiry")),
                        optional(row, "strike").map(BigDecimal::new),
                        optional(row, "option_right").map(OptionRight::valueOf),
                        Integer.parseInt(required(row, "lot_size")),
                        new BigDecimal(required(row, "tick_size")),
                        required(row, "interval"),
                        role,
                        new BigDecimal(required(row, "open")),
                        new BigDecimal(required(row, "high")),
                        new BigDecimal(required(row, "low")),
                        new BigDecimal(required(row, "close")),
                        optionalLong(row.get("volume"), "volume"),
                        optionalLong(row.get("open_interest"), "open_interest"),
                        required(row, "source"),
                        required(row, "raw_record_hash")));
            } catch (RuntimeException invalidRow) {
                throw new IllegalArgumentException("Invalid bars CSV row " + rowNumber + ": " + invalidRow.getMessage(), invalidRow);
            }
        }
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("Bars CSV contains no data rows");
        }
        return new ReadResult(bars, table.sha256());
    }

    private static String required(java.util.Map<String, String> row, String name) {
        String value = row.get(name);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must be non-empty and trimmed");
        }
        return value;
    }

    private static Long optionalLong(String value, String name) {
        if (value == null || value.isBlank()) return null;
        if (!value.equals(value.strip())) throw new IllegalArgumentException(name + " must be trimmed");
        long parsed = Long.parseLong(value);
        if (parsed < 0) throw new IllegalArgumentException(name + " must be non-negative or blank");
        return parsed;
    }

    private static Optional<String> optional(java.util.Map<String, String> row, String name) {
        String value = row.get(name);
        if (value == null || value.isBlank()) return Optional.empty();
        if (!value.equals(value.strip())) throw new IllegalArgumentException(name + " must be trimmed");
        return Optional.of(value);
    }
}
