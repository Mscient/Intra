package com.mscient.intrapaper.reference;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.provenance.RunConfig;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Local-only exchange/token resolution using explicit half-open UTC timestamp ranges. */
public final class DatedInstrumentMappingRegistry {
    public static final List<String> HEADERS = List.of("mapping_id", "exchange", "token", "instrument_id",
            "trading_symbol", "instrument_type", "expiry", "strike", "option_right",
            "effective_from_utc", "effective_until_utc", "source", "source_reference", "source_file_sha256");
    private static final Comparator<DatedInstrumentMapping> ORDER = Comparator
            .comparing(DatedInstrumentMapping::exchange).thenComparing(DatedInstrumentMapping::token)
            .thenComparing(DatedInstrumentMapping::effectiveFromUtc).thenComparing(DatedInstrumentMapping::mappingId);
    private record LocatedMapping(DatedInstrumentMapping mapping, int csvRowNumber) { }

    private final List<DatedInstrumentMapping> mappings;
    private final Map<String, List<DatedInstrumentMapping>> byToken;
    private final String registrySha256;

    private DatedInstrumentMappingRegistry(List<LocatedMapping> rows) {
        List<LocatedMapping> sorted = rows.stream().sorted(Comparator.comparing(LocatedMapping::mapping, ORDER)).toList();
        Map<String, LocatedMapping> ids = new TreeMap<>();
        TreeMap<String, List<LocatedMapping>> groups = new TreeMap<>();
        for (LocatedMapping located : sorted) {
            DatedInstrumentMapping row = located.mapping();
            LocatedMapping previous = ids.putIfAbsent(row.mappingId(), located);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate mapping_id '" + row.mappingId() + "' at CSV rows "
                        + previous.csvRowNumber() + " and " + located.csvRowNumber());
            }
            groups.computeIfAbsent(key(row.exchange(), row.token()), ignored -> new ArrayList<>()).add(located);
        }
        for (List<LocatedMapping> group : groups.values()) {
            for (int i = 1; i < group.size(); i++) {
                LocatedMapping previousLocated = group.get(i - 1);
                LocatedMapping currentLocated = group.get(i);
                DatedInstrumentMapping previous = previousLocated.mapping();
                DatedInstrumentMapping current = currentLocated.mapping();
                if (previous.effectiveUntilUtc().isEmpty()
                        || current.effectiveFromUtc().isBefore(previous.effectiveUntilUtc().orElseThrow())) {
                    throw new IllegalArgumentException("Overlapping mappings for " + current.exchange() + " token "
                            + current.token() + ": mapping_id='" + previous.mappingId() + "' CSV row "
                            + previousLocated.csvRowNumber() + " conflicts with mapping_id='" + current.mappingId()
                            + "' CSV row " + currentLocated.csvRowNumber());
                }
            }
        }
        this.mappings = sorted.stream().map(LocatedMapping::mapping).toList();
        TreeMap<String, List<DatedInstrumentMapping>> immutableGroups = new TreeMap<>();
        groups.forEach((groupKey, values) -> immutableGroups.put(groupKey,
                values.stream().map(LocatedMapping::mapping).toList()));
        this.byToken = Map.copyOf(immutableGroups);
        this.registrySha256 = canonicalHash(this.mappings);
    }

    /** Loads UTF-8 CSV bytes from the given local path; this class performs no network access. */
    public static DatedInstrumentMappingRegistry load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        CsvTable csv = CsvTable.read(path);
        if (!csv.headers().equals(HEADERS)) {
            throw new IllegalArgumentException("Instrument mapping CSV headers must be exactly: " + String.join(",", HEADERS));
        }
        List<LocatedMapping> rows = new ArrayList<>();
        for (int i = 0; i < csv.rows().size(); i++) {
            Map<String, String> row = csv.rows().get(i);
            int csvRow = i + 2;
            try {
                rows.add(new LocatedMapping(parseRow(row), csvRow));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid instrument mapping CSV row " + csvRow + ": "
                        + invalid.getMessage(), invalid);
            }
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("Instrument mapping CSV contains no data rows: " + path);
        return new DatedInstrumentMappingRegistry(rows);
    }

    /** Resolves exactly one exchange/token mapping valid at the supplied UTC instant. */
    public InstrumentMappingResolution resolve(String exchange, String token, Instant marketTimestampUtc) {
        String checkedExchange = required(exchange, "exchange");
        String checkedToken = required(token, "token");
        Objects.requireNonNull(marketTimestampUtc, "marketTimestampUtc");
        List<DatedInstrumentMapping> candidates = byToken.getOrDefault(key(checkedExchange, checkedToken), List.of());
        List<DatedInstrumentMapping> matches = candidates.stream().filter(row -> row.covers(marketTimestampUtc)).toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No instrument mapping for exchange='" + checkedExchange + "', token='"
                    + checkedToken + "' at UTC timestamp " + marketTimestampUtc);
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("Ambiguous instrument mappings for exchange='" + checkedExchange
                    + "', token='" + checkedToken + "' at UTC timestamp " + marketTimestampUtc);
        }
        return new InstrumentMappingResolution(matches.getFirst(), registrySha256);
    }

    public List<DatedInstrumentMapping> mappings() { return mappings; }
    public String registrySha256() { return registrySha256; }

    private static DatedInstrumentMapping parseRow(Map<String, String> row) {
        String typeText = required(row, "instrument_type");
        ContractKind type;
        try {
            type = ContractKind.valueOf(typeText);
        } catch (IllegalArgumentException invalidType) {
            throw new IllegalArgumentException("instrument_type must be one of " + java.util.Arrays.toString(ContractKind.values()));
        }
        return new DatedInstrumentMapping(required(row, "mapping_id"), required(row, "exchange"),
                required(row, "token"), required(row, "instrument_id"), required(row, "trading_symbol"), type,
                optional(row, "expiry").map(value -> date(value, "expiry")),
                optional(row, "strike").map(value -> decimal(value, "strike")),
                optional(row, "option_right").map(value -> enumValue(value, "option_right", OptionRight.class)),
                utc(required(row, "effective_from_utc"), "effective_from_utc"),
                optional(row, "effective_until_utc").map(value -> utc(value, "effective_until_utc")),
                required(row, "source"), required(row, "source_reference"),
                required(row, "source_file_sha256"));
    }

    private static String canonicalHash(List<DatedInstrumentMapping> rows) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.write("DATED_INSTRUMENT_MAPPING_REGISTRY_V1".getBytes(StandardCharsets.UTF_8));
                for (DatedInstrumentMapping row : rows) {
                    for (String value : canonicalFields(row)) {
                        byte[] field = value.getBytes(StandardCharsets.UTF_8);
                        out.writeInt(field.length);
                        out.write(field);
                    }
                }
            }
            return RunConfig.sha256(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot fingerprint in-memory registry", impossible);
        }
    }

    private static List<String> canonicalFields(DatedInstrumentMapping row) {
        return List.of(row.mappingId(), row.exchange(), row.token(), row.instrumentId(), row.tradingSymbol(),
                row.instrumentType().name(), row.expiry().map(LocalDate::toString).orElse(""),
                row.strike().map(BigDecimal::toPlainString).orElse(""), row.optionRight().map(Enum::name).orElse(""),
                row.effectiveFromUtc().toString(), row.effectiveUntilUtc().map(Instant::toString).orElse(""),
                row.source(), row.sourceReference(), row.sourceFileSha256());
    }

    private static String key(String exchange, String token) { return exchange + "\u0000" + token; }

    private static String required(Map<String, String> row, String column) {
        return required(row.get(column), column);
    }

    private static String required(String value, String column) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(column + " must be non-empty and trimmed");
        }
        return value;
    }

    private static Optional<String> optional(Map<String, String> row, String column) {
        String value = row.get(column);
        if (value == null || value.isBlank()) return Optional.empty();
        if (!value.equals(value.strip())) throw new IllegalArgumentException(column + " must be trimmed");
        return Optional.of(value);
    }

    private static Instant utc(String value, String field) {
        if (!value.endsWith("Z")) throw new IllegalArgumentException(field + " must be an ISO-8601 UTC timestamp ending in Z");
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException(field + " must be an ISO-8601 UTC timestamp ending in Z", invalid);
        }
    }

    private static LocalDate date(String value, String field) {
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException invalid) { throw new IllegalArgumentException(field + " must be YYYY-MM-DD", invalid); }
    }

    private static BigDecimal decimal(String value, String field) {
        try { return new BigDecimal(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException(field + " must be a decimal number", invalid); }
    }

    private static <E extends Enum<E>> E enumValue(String value, String field, Class<E> enumType) {
        try { return Enum.valueOf(enumType, value); }
        catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + " must be one of " + java.util.Arrays.toString(enumType.getEnumConstants()), invalid);
        }
    }
}
