package com.mscient.intrapaper.provenance;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.reference.ContractKind;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Caller-supplied dated per-lot margin requirements; the values are not recalculated or certified. */
public record MarginRequirementSchedule(List<Entry> entries, String sha256) {
    public record Entry(LocalDate effectiveFrom, LocalDate effectiveThrough, String instrumentId,
            ContractKind contractKind, BigDecimal marginPerLotInr, String sourceReference) {
        public Entry {
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            requireTrimmed(instrumentId, "instrument_id");
            Objects.requireNonNull(marginPerLotInr, "marginPerLotInr");
            Objects.requireNonNull(contractKind, "contractKind");
            if (contractKind != ContractKind.FUTURE && contractKind != ContractKind.OPTION) {
                throw new IllegalArgumentException("margin schedule supports FUTURE or OPTION contracts only");
            }
            if (marginPerLotInr.signum() < 0) throw new IllegalArgumentException("margin_per_lot_inr must be non-negative");
            if (effectiveThrough != null && effectiveThrough.isBefore(effectiveFrom)) {
                throw new IllegalArgumentException("effective_through precedes effective_from");
            }
            requireLogicalId(sourceReference, "source_reference");
        }

        public boolean covers(LocalDate date) {
            return !date.isBefore(effectiveFrom) && (effectiveThrough == null || !date.isAfter(effectiveThrough));
        }
    }

    public MarginRequirementSchedule {
        entries = List.copyOf(entries);
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid margin schedule SHA-256");
    }

    public static MarginRequirementSchedule load(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        List<String> expected = List.of("effective_from", "effective_through", "instrument_id", "contract_kind",
                "margin_per_lot_inr", "source_reference");
        if (!table.headers().equals(expected)) {
            throw new IllegalArgumentException("Margin schedule headers must be exactly: " + String.join(",", expected));
        }
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < table.rows().size(); i++) {
            Map<String, String> row = table.rows().get(i);
            try {
                String throughText = row.get("effective_through");
                if (throughText == null || !throughText.equals(throughText.strip())) {
                    throw new IllegalArgumentException("effective_through must be trimmed");
                }
                entries.add(new Entry(LocalDate.parse(required(row, "effective_from")),
                        throughText.isBlank() ? null : LocalDate.parse(throughText),
                        required(row, "instrument_id"), ContractKind.valueOf(required(row, "contract_kind")),
                        nonNegative(row, "margin_per_lot_inr"), required(row, "source_reference")));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid margin schedule row " + (i + 2) + ": "
                        + invalid.getMessage(), invalid);
            }
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("Margin schedule contains no rows");
        entries.sort(Comparator.comparing(Entry::instrumentId).thenComparing(Entry::contractKind)
                .thenComparing(Entry::effectiveFrom));
        Map<String, Entry> prior = new HashMap<>();
        for (Entry entry : entries) {
            String key = entry.instrumentId() + ":" + entry.contractKind();
            Entry previous = prior.put(key, entry);
            if (previous != null && (previous.effectiveThrough() == null
                    || !entry.effectiveFrom().isAfter(previous.effectiveThrough()))) {
                throw new IllegalArgumentException("Overlapping margin schedule ranges for " + key);
            }
        }
        return new MarginRequirementSchedule(entries, table.sha256());
    }

    public Entry require(LocalDate date, String instrumentId, ContractKind contractKind) {
        return entries.stream().filter(entry -> entry.instrumentId().equals(instrumentId)
                        && entry.contractKind() == contractKind && entry.covers(date))
                .map(entry -> entry)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No margin requirement for " + instrumentId
                        + " (" + contractKind + ") covers " + date));
    }

    private static String required(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(key + " must be non-empty and trimmed");
        }
        return value;
    }

    private static BigDecimal nonNegative(Map<String, String> row, String key) {
        BigDecimal value = new BigDecimal(required(row, key));
        if (value.signum() < 0) throw new IllegalArgumentException(key + " must be non-negative");
        return value.stripTrailingZeros();
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
