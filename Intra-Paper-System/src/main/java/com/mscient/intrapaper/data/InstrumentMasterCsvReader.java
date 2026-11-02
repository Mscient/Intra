package com.mscient.intrapaper.data;

import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.reference.ContractKind;
import com.mscient.intrapaper.reference.InstrumentMasterSnapshot;
import com.mscient.intrapaper.reference.InstrumentReference;
import com.mscient.intrapaper.reference.OptionRight;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class InstrumentMasterCsvReader {
    private InstrumentMasterCsvReader() { }

    public static InstrumentMasterSnapshot read(RunConfig config) throws IOException {
        CsvTable table = CsvTable.read(config.instrumentMasterPath());
        table.requireHeaders("instrument_id", "exchange", "segment", "source_token", "trading_symbol",
                "contract_kind", "underlying", "expiry", "strike", "option_right", "lot_size", "tick_size",
                "effective_from", "effective_through");
        List<InstrumentReference> instruments = new ArrayList<>();
        for (int i = 0; i < table.rows().size(); i++) {
            var row = table.rows().get(i);
            try {
                instruments.add(new InstrumentReference(
                        1,
                        required(row, "instrument_id"),
                        required(row, "exchange"),
                        required(row, "segment"),
                        required(row, "source_token"),
                        required(row, "trading_symbol"),
                        ContractKind.valueOf(required(row, "contract_kind")),
                        optional(row, "underlying"),
                        optional(row, "expiry").map(LocalDate::parse),
                        optional(row, "strike").map(BigDecimal::new),
                        optional(row, "option_right").map(OptionRight::valueOf),
                        Integer.parseInt(required(row, "lot_size")),
                        new BigDecimal(required(row, "tick_size")),
                        LocalDate.parse(required(row, "effective_from")),
                        optional(row, "effective_through").map(LocalDate::parse)));
            } catch (RuntimeException invalidRow) {
                throw new IllegalArgumentException("Invalid instrument master CSV row " + (i + 2) + ": " + invalidRow.getMessage(), invalidRow);
            }
        }
        if (instruments.isEmpty()) throw new IllegalArgumentException("Instrument master CSV contains no records");
        String masterHash = RunConfig.sha256(Files.readAllBytes(config.instrumentMasterPath()));
        return new InstrumentMasterSnapshot(1, config.snapshotId(), config.snapshotSourceName(),
                config.snapshotRetrievedAt(), config.snapshotEffectiveFrom(), masterHash, instruments);
    }

    private static String required(java.util.Map<String, String> row, String name) {
        String value = row.get(name);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must be non-empty and trimmed");
        }
        return value;
    }

    private static Optional<String> optional(java.util.Map<String, String> row, String name) {
        String value = row.get(name);
        if (value == null || value.isBlank()) return Optional.empty();
        if (!value.equals(value.strip())) throw new IllegalArgumentException(name + " must be trimmed");
        return Optional.of(value);
    }
}
