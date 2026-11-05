package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.reference.ContractKind;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import static com.mscient.intrapaper.simulation.portfolio.PortfolioSettlementContract.*;

/** Caller-sourced components with explicit bases and independent charge/GST rounding. */
public record PortfolioSettlementCostSchedule(String scheduleId, List<Entry> entries, String sourceFileSha256) {
    public enum PositionSide { LONG, SHORT }
    public enum Category { EXPIRY_SETTLEMENT, OPTION_EXERCISE }
    public enum Basis { FIXED_PER_SETTLEMENT, PER_SETTLED_UNIT, PER_EXERCISED_OPTION_UNIT,
        BPS_OF_SETTLEMENT_VALUE, BPS_OF_CALLER_TURNOVER }
    public static final List<String> HEADERS;
    static {
        var h = new ArrayList<>(List.of("schedule_id", "entry_id", "effective_from", "effective_until"));
        h.addAll(PortfolioSettlementContract.HEADERS);
        h.addAll(List.of("position_side", "charge_category", "component_code", "basis", "rate", "charge_rounding_scale",
                "charge_rounding_mode", "gst_bps", "gst_on_charge", "gst_rounding_scale", "gst_rounding_mode", "source_reference"));
        HEADERS = List.copyOf(h);
    }
    public record Entry(String entryId, LocalDate effectiveFrom, Optional<LocalDate> effectiveUntil,
            PortfolioSettlementContract contract, PositionSide side, Category category, String componentCode,
            Basis basis, BigDecimal rate, int chargeScale, RoundingMode chargeMode, BigDecimal gstBps,
            boolean gstOnCharge, int gstScale, RoundingMode gstMode, String sourceReference) {
        public Entry {
            id(entryId, "entry_id"); id(componentCode, "component_code"); text(sourceReference, "source_reference");
            Objects.requireNonNull(effectiveFrom); Objects.requireNonNull(effectiveUntil); Objects.requireNonNull(contract);
            Objects.requireNonNull(side); Objects.requireNonNull(category); Objects.requireNonNull(basis);
            Objects.requireNonNull(chargeMode); Objects.requireNonNull(gstMode);
            nonnegative(rate, "rate"); nonnegative(gstBps, "gst_bps");
            if (chargeScale < 0 || chargeScale > 18 || gstScale < 0 || gstScale > 18)
                throw new IllegalArgumentException("Rounding scales must be between 0 and 18");
            if (effectiveUntil.isPresent() && !effectiveUntil.orElseThrow().isAfter(effectiveFrom))
                throw new IllegalArgumentException("effective_until must be after effective_from");
            if ((category == Category.OPTION_EXERCISE || basis == Basis.PER_EXERCISED_OPTION_UNIT)
                    && contract.contractKind() != ContractKind.OPTION)
                throw new IllegalArgumentException("Exercise charges require OPTION identity");
            if (basis == Basis.PER_EXERCISED_OPTION_UNIT && category != Category.OPTION_EXERCISE)
                throw new IllegalArgumentException("Exercised-unit basis requires OPTION_EXERCISE category");
        }
        public boolean covers(LocalDate date) {
            return !date.isBefore(effectiveFrom) && (effectiveUntil.isEmpty() || date.isBefore(effectiveUntil.orElseThrow()));
        }
        static Entry parse(Map<String, String> row) {
            String until = optional(row, "effective_until");
            return new Entry(required(row, "entry_id"), LocalDate.parse(required(row, "effective_from")),
                    until.isEmpty() ? Optional.empty() : Optional.of(LocalDate.parse(until)), PortfolioSettlementContract.parse(row),
                    PositionSide.valueOf(required(row, "position_side")), Category.valueOf(required(row, "charge_category")),
                    required(row, "component_code"), Basis.valueOf(required(row, "basis")), new BigDecimal(required(row, "rate")),
                    Integer.parseInt(required(row, "charge_rounding_scale")), RoundingMode.valueOf(required(row, "charge_rounding_mode")),
                    new BigDecimal(required(row, "gst_bps")), bool(row, "gst_on_charge"),
                    Integer.parseInt(required(row, "gst_rounding_scale")), RoundingMode.valueOf(required(row, "gst_rounding_mode")),
                    required(row, "source_reference"));
        }
        List<String> cells(String scheduleId) {
            var values = new ArrayList<>(List.of(scheduleId, entryId, effectiveFrom.toString(), effectiveUntil.map(Object::toString).orElse("")));
            values.addAll(contract.cells());
            values.addAll(List.of(side.name(), category.name(), componentCode, basis.name(), rate.toPlainString(),
                    Integer.toString(chargeScale), chargeMode.name(), gstBps.toPlainString(), Boolean.toString(gstOnCharge),
                    Integer.toString(gstScale), gstMode.name(), sourceReference));
            return List.copyOf(values);
        }
    }
    public record Line(String eventId, String scheduleId, String sourceFileSha256, Entry entry,
            BigDecimal calculationBase, BigDecimal chargeInr, BigDecimal gstTaxableBaseInr, BigDecimal gstInr) {
        public Line {
            id(eventId, "event_id"); id(scheduleId, "schedule_id"); hash(sourceFileSha256); Objects.requireNonNull(entry);
            for (BigDecimal value : List.of(calculationBase, chargeInr, gstTaxableBaseInr, gstInr)) nonnegative(value, "charge line");
        }
        public BigDecimal totalInr() { return chargeInr.add(gstInr); }
    }
    public PortfolioSettlementCostSchedule {
        id(scheduleId, "schedule_id"); hash(sourceFileSha256);
        if (entries.isEmpty()) throw new IllegalArgumentException("Settlement cost schedule must contain rows");
        var ids = new HashSet<String>();
        entries = entries.stream().sorted(Comparator.comparing((Entry e) -> e.contract().instrumentId())
                .thenComparing(Entry::side).thenComparing(Entry::category).thenComparing(Entry::componentCode)
                .thenComparing(Entry::effectiveFrom).thenComparing(Entry::entryId)).toList();
        for (int i = 0; i < entries.size(); i++) {
            Entry a = entries.get(i);
            if (!ids.add(a.entryId())) throw new IllegalArgumentException("Duplicate settlement cost entry ID");
            for (int j = 0; j < i; j++) {
                Entry b = entries.get(j);
                if (sameComponent(a, b) && (a.effectiveUntil().isEmpty() || b.effectiveFrom().isBefore(a.effectiveUntil().orElseThrow()))
                        && (b.effectiveUntil().isEmpty() || a.effectiveFrom().isBefore(b.effectiveUntil().orElseThrow())))
                    throw new IllegalArgumentException("Overlapping settlement component dates: " + a.componentCode());
            }
        }
    }
    private static boolean sameComponent(Entry a, Entry b) {
        return a.contract().equals(b.contract()) && a.side() == b.side() && a.category() == b.category()
                && a.componentCode().equals(b.componentCode());
    }
    public static PortfolioSettlementCostSchedule load(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        if (!table.headers().equals(HEADERS)) throw new IllegalArgumentException("Settlement cost headers must be exactly " + HEADERS);
        var entries = new ArrayList<Entry>(); String scheduleId = null;
        for (int i = 0; i < table.rows().size(); i++) {
            try {
                var row = table.rows().get(i); String rowId = id(required(row, "schedule_id"), "schedule_id");
                if (scheduleId == null) scheduleId = rowId;
                if (!scheduleId.equals(rowId)) throw new IllegalArgumentException("All rows must use one schedule_id");
                entries.add(Entry.parse(row));
            } catch (RuntimeException e) { throw new IllegalArgumentException("Invalid settlement cost row " + (i + 2) + ": " + e.getMessage(), e); }
        }
        return new PortfolioSettlementCostSchedule(scheduleId, entries, table.sha256());
    }
    public List<Line> calculate(PortfolioSettlementEvents.Event event, long signedUnits) {
        if (signedUnits == 0) return List.of();
        long units = Math.absExact(signedUnits);
        PositionSide side = signedUnits > 0 ? PositionSide.LONG : PositionSide.SHORT;
        var lines = new ArrayList<Line>();
        for (Category category : Category.values()) {
            if (category == Category.OPTION_EXERCISE && !event.optionExercise()) continue;
            List<Entry> declared = entries.stream().filter(e -> e.contract().equals(event.contract()) && e.side() == side && e.category() == category).toList();
            if (declared.isEmpty()) throw new IllegalArgumentException("Missing settlement cost coverage: " + event.eventId() + " " + side + " " + category);
            var components = new TreeSet<String>(); declared.forEach(e -> components.add(e.componentCode()));
            for (String component : components) {
                Entry entry = declared.stream().filter(e -> e.componentCode().equals(component) && e.covers(event.settlementDate()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Out-of-date settlement component: " + component));
                BigDecimal base = switch (entry.basis()) {
                    case FIXED_PER_SETTLEMENT -> BigDecimal.ONE;
                    case PER_SETTLED_UNIT, PER_EXERCISED_OPTION_UNIT -> BigDecimal.valueOf(units);
                    case BPS_OF_SETTLEMENT_VALUE -> event.valuePerUnit().abs().multiply(BigDecimal.valueOf(units));
                    case BPS_OF_CALLER_TURNOVER -> event.chargeTurnoverPerUnit().orElseThrow(() ->
                            new IllegalArgumentException("Missing sourced charge turnover for " + event.eventId())).multiply(BigDecimal.valueOf(units));
                };
                BigDecimal raw = base.multiply(entry.rate());
                if (entry.basis() == Basis.BPS_OF_SETTLEMENT_VALUE || entry.basis() == Basis.BPS_OF_CALLER_TURNOVER) raw = raw.movePointLeft(4);
                BigDecimal charge = raw.setScale(entry.chargeScale(), entry.chargeMode());
                BigDecimal gstBase = entry.gstOnCharge() ? charge : BigDecimal.ZERO;
                BigDecimal gst = gstBase.multiply(entry.gstBps()).movePointLeft(4).setScale(entry.gstScale(), entry.gstMode());
                lines.add(new Line(event.eventId(), scheduleId, sourceFileSha256, entry, base, charge, gstBase, gst));
            }
        }
        return List.copyOf(lines);
    }
}
