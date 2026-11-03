package com.mscient.intrapaper.provenance;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.reference.ContractKind;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Caller-sourced, effective-dated settlement charges; this does not infer broker or statutory fees. */
public record SettlementCostSchedule(List<Entry> entries, String sha256) {
    private static final BigDecimal BPS_DENOMINATOR = new BigDecimal("10000");

    public enum Basis {
        FIXED_PER_SETTLEMENT,
        PER_SETTLED_UNIT,
        PER_EXERCISED_OPTION_UNIT,
        BPS_OF_SETTLEMENT_NOTIONAL,
        BPS_OF_OPTION_INTRINSIC
    }

    public record Entry(LocalDate effectiveFrom, LocalDate effectiveThrough, ContractKind contractKind,
            String componentCode, Basis basis, BigDecimal rate, BigDecimal gstBps,
            boolean gstOnCharge, String sourceReference) {
        public Entry {
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            Objects.requireNonNull(contractKind, "contractKind");
            Objects.requireNonNull(basis, "basis");
            Objects.requireNonNull(rate, "rate");
            Objects.requireNonNull(gstBps, "gstBps");
            if (contractKind != ContractKind.FUTURE && contractKind != ContractKind.OPTION) {
                throw new IllegalArgumentException("settlement costs support FUTURE or OPTION contracts only");
            }
            if (effectiveThrough != null && effectiveThrough.isBefore(effectiveFrom)) {
                throw new IllegalArgumentException("effective_through precedes effective_from");
            }
            if (componentCode == null || !componentCode.matches("[A-Z0-9][A-Z0-9_]{0,63}")) {
                throw new IllegalArgumentException("component_code must be uppercase letters, digits, or underscores");
            }
            if (rate.signum() < 0 || gstBps.signum() < 0) {
                throw new IllegalArgumentException("settlement charge rates must be non-negative");
            }
            if ((basis == Basis.PER_EXERCISED_OPTION_UNIT || basis == Basis.BPS_OF_OPTION_INTRINSIC)
                    && contractKind != ContractKind.OPTION) {
                throw new IllegalArgumentException(basis + " is valid only for OPTION rows");
            }
            requireLogicalId(sourceReference, "source_reference");
        }

        public boolean covers(LocalDate date) {
            return !date.isBefore(effectiveFrom) && (effectiveThrough == null || !date.isAfter(effectiveThrough));
        }
    }

    public record ChargeLine(String componentCode, Basis basis, BigDecimal calculationBase,
            BigDecimal rate, BigDecimal chargeInr, BigDecimal gstBps,
            BigDecimal gstTaxableBaseInr, BigDecimal gstInr, String sourceReference) {
        public ChargeLine {
            for (BigDecimal value : List.of(calculationBase, rate, chargeInr, gstBps, gstTaxableBaseInr, gstInr)) {
                if (value.signum() < 0) throw new IllegalArgumentException("settlement charge line values must be non-negative");
            }
        }

        public BigDecimal totalInr() {
            return chargeInr.add(gstInr);
        }
    }

    public SettlementCostSchedule {
        entries = List.copyOf(entries);
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid settlement cost schedule SHA-256");
        }
    }

    public static SettlementCostSchedule load(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        List<String> expected = List.of("effective_from", "effective_through", "contract_kind", "component_code",
                "basis", "rate", "gst_bps", "gst_on_charge", "source_reference");
        if (!table.headers().equals(expected)) {
            throw new IllegalArgumentException("Settlement cost schedule headers must be exactly: " + String.join(",", expected));
        }
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < table.rows().size(); i++) {
            Map<String, String> row = table.rows().get(i);
            try {
                String throughText = requiredTrimmed(row, "effective_through", true);
                entries.add(new Entry(LocalDate.parse(required(row, "effective_from")),
                        throughText.isBlank() ? null : LocalDate.parse(throughText),
                        ContractKind.valueOf(required(row, "contract_kind")),
                        required(row, "component_code"), Basis.valueOf(required(row, "basis")),
                        nonNegative(row, "rate"), nonNegative(row, "gst_bps"),
                        bool(row, "gst_on_charge"), required(row, "source_reference")));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid settlement cost schedule row " + (i + 2) + ": "
                        + invalid.getMessage(), invalid);
            }
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("Settlement cost schedule contains no rows");
        entries.sort(Comparator.comparing(Entry::contractKind).thenComparing(Entry::componentCode)
                .thenComparing(Entry::effectiveFrom));
        Map<String, Entry> prior = new HashMap<>();
        for (Entry entry : entries) {
            String key = entry.contractKind() + ":" + entry.componentCode();
            Entry previous = prior.get(key);
            if (previous != null && (previous.effectiveThrough() == null
                    || !entry.effectiveFrom().isAfter(previous.effectiveThrough()))) {
                throw new IllegalArgumentException("Overlapping settlement cost schedule ranges for " + key);
            }
            prior.put(key, entry);
        }
        return new SettlementCostSchedule(entries, table.sha256());
    }

    public List<ChargeLine> calculate(LocalDate date, ContractKind kind, BigDecimal settlementValue,
            BigDecimal optionIntrinsicPerUnit, int settledUnits) {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(settlementValue, "settlementValue");
        Objects.requireNonNull(optionIntrinsicPerUnit, "optionIntrinsicPerUnit");
        if (settlementValue.signum() <= 0 || optionIntrinsicPerUnit.signum() < 0 || settledUnits < 0) {
            throw new IllegalArgumentException("invalid settlement cost calculation inputs");
        }
        List<Entry> applicable = entries.stream().filter(entry -> entry.contractKind() == kind && entry.covers(date)).toList();
        if (applicable.isEmpty()) {
            throw new IllegalArgumentException("No " + kind + " settlement cost schedule covers " + date);
        }
        BigDecimal settledUnitBase = BigDecimal.valueOf(settledUnits);
        BigDecimal exercisedUnits = kind == ContractKind.OPTION && optionIntrinsicPerUnit.signum() > 0
                ? settledUnitBase : BigDecimal.ZERO;
        BigDecimal settlementNotional = settlementValue.multiply(settledUnitBase);
        BigDecimal optionIntrinsicNotional = optionIntrinsicPerUnit.multiply(exercisedUnits);
        List<ChargeLine> lines = new ArrayList<>();
        for (Entry entry : applicable) {
            BigDecimal base = switch (entry.basis()) {
                case FIXED_PER_SETTLEMENT -> settledUnits > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
                case PER_SETTLED_UNIT -> settledUnitBase;
                case PER_EXERCISED_OPTION_UNIT -> exercisedUnits;
                case BPS_OF_SETTLEMENT_NOTIONAL -> settlementNotional;
                case BPS_OF_OPTION_INTRINSIC -> optionIntrinsicNotional;
            };
            BigDecimal unrounded = switch (entry.basis()) {
                case FIXED_PER_SETTLEMENT -> entry.rate().multiply(base);
                case PER_SETTLED_UNIT, PER_EXERCISED_OPTION_UNIT -> entry.rate().multiply(base);
                case BPS_OF_SETTLEMENT_NOTIONAL, BPS_OF_OPTION_INTRINSIC -> base.multiply(entry.rate())
                        .divide(BPS_DENOMINATOR, 12, RoundingMode.HALF_UP);
            };
            BigDecimal charge = paise(unrounded);
            BigDecimal taxableBase = entry.gstOnCharge() ? charge : BigDecimal.ZERO.setScale(2);
            BigDecimal gst = paise(taxableBase.multiply(entry.gstBps()).divide(BPS_DENOMINATOR, 12, RoundingMode.HALF_UP));
            lines.add(new ChargeLine(entry.componentCode(), entry.basis(), base, entry.rate(), charge,
                    entry.gstBps(), taxableBase, gst, entry.sourceReference()));
        }
        return List.copyOf(lines);
    }

    public void requireCoverage(LocalDate date, ContractKind kind) {
        if (entries.stream().noneMatch(entry -> entry.contractKind() == kind && entry.covers(date))) {
            throw new IllegalArgumentException("No " + kind + " settlement cost schedule covers " + date);
        }
    }

    private static BigDecimal paise(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private static String required(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(key + " must be non-empty and trimmed");
        }
        return value;
    }

    private static String requiredTrimmed(Map<String, String> row, String key, boolean allowBlank) {
        String value = row.get(key);
        if (value == null || !value.equals(value.strip()) || (!allowBlank && value.isBlank())) {
            throw new IllegalArgumentException(key + " must be trimmed" + (allowBlank ? "" : " and non-empty"));
        }
        return value;
    }

    private static BigDecimal nonNegative(Map<String, String> row, String key) {
        BigDecimal value = new BigDecimal(required(row, key));
        if (value.signum() < 0) throw new IllegalArgumentException(key + " must be non-negative");
        return value.stripTrailingZeros();
    }

    private static boolean bool(Map<String, String> row, String key) {
        String value = required(row, key).toLowerCase(java.util.Locale.ROOT);
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(key + " must be true or false");
        return Boolean.parseBoolean(value);
    }

    private static void requireLogicalId(String value, String key) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.equals(".") || value.equals("..")
                || value.contains("/") || value.contains("\\") || value.contains("?") || value.contains("#")
                || value.contains("@")) {
            throw new IllegalArgumentException(key + " must be a non-empty logical ID, not a path or URL");
        }
    }
}
