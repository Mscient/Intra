package com.mscient.intrapaper.simulation.portfolio;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Caller-supplied effective-dated portfolio execution costs; no rate is built in. */
public record PortfolioExecutionCostSchedule(String scheduleId, List<Entry> entries,
        String sourceFileSha256) {
    private static final BigDecimal CRORE_INR = new BigDecimal("10000000");
    private static final BigDecimal BPS_DENOMINATOR = new BigDecimal("10000");
    private static final List<String> HEADERS = List.of("schedule_id", "entry_id", "effective_from",
            "effective_until", "instrument_id", "contract_kind", "side",
            "brokerage_per_order_inr", "brokerage_per_unit_inr", "brokerage_bps",
            "exchange_inr_per_crore", "ipft_inr_per_crore", "sebi_inr_per_crore",
            "stt_bps", "stamp_duty_bps", "gst_bps", "gst_on_brokerage",
            "gst_on_exchange", "gst_on_ipft", "gst_on_sebi", "gst_on_stt",
            "gst_on_stamp_duty", "source_reference");

    public record Terms(BigDecimal brokeragePerOrderInr, BigDecimal brokeragePerUnitInr,
            BigDecimal brokerageBps, BigDecimal exchangeInrPerCrore, BigDecimal ipftInrPerCrore,
            BigDecimal sebiInrPerCrore, BigDecimal sttBps, BigDecimal stampDutyBps,
            BigDecimal gstBps, boolean gstOnBrokerage, boolean gstOnExchange,
            boolean gstOnIpft, boolean gstOnSebi, boolean gstOnStt, boolean gstOnStampDuty) {
        public Terms {
            for (BigDecimal value : List.of(brokeragePerOrderInr, brokeragePerUnitInr, brokerageBps,
                    exchangeInrPerCrore, ipftInrPerCrore, sebiInrPerCrore, sttBps,
                    stampDutyBps, gstBps)) {
                Objects.requireNonNull(value, "rate");
                if (value.signum() < 0) throw new IllegalArgumentException("cost schedule rates must be non-negative");
            }
        }

        private Charges calculate(BigDecimal tradedValueInr, long quantityUnits) {
            if (tradedValueInr.signum() < 0 || quantityUnits < 1) {
                throw new IllegalArgumentException("traded value must be non-negative and quantity positive");
            }
            BigDecimal brokerage = paise(brokeragePerOrderInr
                    .add(brokeragePerUnitInr.multiply(BigDecimal.valueOf(quantityUnits)))
                    .add(bpsCharge(tradedValueInr, brokerageBps)));
            BigDecimal exchange = paise(croreCharge(tradedValueInr, exchangeInrPerCrore));
            BigDecimal ipft = paise(croreCharge(tradedValueInr, ipftInrPerCrore));
            BigDecimal sebi = paise(croreCharge(tradedValueInr, sebiInrPerCrore));
            BigDecimal stt = paise(bpsCharge(tradedValueInr, sttBps));
            BigDecimal stamp = paise(bpsCharge(tradedValueInr, stampDutyBps));
            BigDecimal gstBase = BigDecimal.ZERO;
            if (gstOnBrokerage) gstBase = gstBase.add(brokerage);
            if (gstOnExchange) gstBase = gstBase.add(exchange);
            if (gstOnIpft) gstBase = gstBase.add(ipft);
            if (gstOnSebi) gstBase = gstBase.add(sebi);
            if (gstOnStt) gstBase = gstBase.add(stt);
            if (gstOnStampDuty) gstBase = gstBase.add(stamp);
            gstBase = paise(gstBase);
            BigDecimal gst = paise(bpsCharge(gstBase, gstBps));
            return new Charges(brokerage, exchange, ipft, sebi, stt, stamp, gstBase, gst);
        }
    }

    public record Charges(BigDecimal brokerageInr, BigDecimal exchangeInr, BigDecimal ipftInr,
            BigDecimal sebiInr, BigDecimal sttInr, BigDecimal stampDutyInr,
            BigDecimal gstTaxableBaseInr, BigDecimal gstInr) {
        public BigDecimal totalInr() {
            return brokerageInr.add(exchangeInr).add(ipftInr).add(sebiInr)
                    .add(sttInr).add(stampDutyInr).add(gstInr);
        }
    }

    public record Entry(String entryId, LocalDate effectiveFrom, LocalDate effectiveUntil,
            String instrumentId, ContractKind contractKind, PortfolioExecution.Side side,
            Terms terms, String sourceReference) {
        public Entry {
            entryId = identifier(entryId, "entry_id");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            instrumentId = identifier(instrumentId, "instrument_id");
            Objects.requireNonNull(contractKind, "contractKind");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(terms, "terms");
            sourceReference = required(sourceReference, "source_reference");
            if (contractKind != ContractKind.FUTURE && contractKind != ContractKind.OPTION) {
                throw new IllegalArgumentException("portfolio costs support FUTURE or OPTION only");
            }
            if (effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
                throw new IllegalArgumentException("effective_until must be after effective_from");
            }
        }

        public boolean covers(LocalDate date) {
            return !date.isBefore(effectiveFrom) && (effectiveUntil == null || date.isBefore(effectiveUntil));
        }
    }

    public record Resolution(Entry entry, PortfolioExecutionFeeBreakdown breakdown) { }

    public PortfolioExecutionCostSchedule {
        scheduleId = identifier(scheduleId, "schedule_id");
        entries = List.copyOf(entries);
        if (entries.isEmpty()) throw new IllegalArgumentException("Portfolio execution cost schedule contains no rows");
        if (sourceFileSha256 == null || !sourceFileSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid portfolio execution cost source SHA-256");
        }
    }

    public static PortfolioExecutionCostSchedule load(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        if (!table.headers().equals(HEADERS)) {
            throw new IllegalArgumentException("Portfolio execution cost headers must be exactly: "
                    + String.join(",", HEADERS));
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> entryIds = new HashSet<>();
        String scheduleId = null;
        for (int index = 0; index < table.rows().size(); index++) {
            Map<String, String> row = table.rows().get(index);
            try {
                String rowScheduleId = identifier(required(row, "schedule_id"), "schedule_id");
                if (scheduleId == null) scheduleId = rowScheduleId;
                if (!scheduleId.equals(rowScheduleId)) {
                    throw new IllegalArgumentException("all rows must use the same schedule_id");
                }
                String entryId = identifier(required(row, "entry_id"), "entry_id");
                if (!entryIds.add(entryId)) throw new IllegalArgumentException("duplicate entry_id: " + entryId);
                LocalDate from = LocalDate.parse(required(row, "effective_from"));
                String untilText = optional(row, "effective_until");
                LocalDate until = untilText.isEmpty() ? null : LocalDate.parse(untilText);
                ContractKind kind = ContractKind.valueOf(required(row, "contract_kind"));
                PortfolioExecution.Side side = PortfolioExecution.Side.valueOf(required(row, "side"));
                Terms terms = new Terms(decimal(row, "brokerage_per_order_inr"),
                        decimal(row, "brokerage_per_unit_inr"), decimal(row, "brokerage_bps"),
                        decimal(row, "exchange_inr_per_crore"), decimal(row, "ipft_inr_per_crore"),
                        decimal(row, "sebi_inr_per_crore"), decimal(row, "stt_bps"),
                        decimal(row, "stamp_duty_bps"), decimal(row, "gst_bps"),
                        bool(row, "gst_on_brokerage"), bool(row, "gst_on_exchange"),
                        bool(row, "gst_on_ipft"), bool(row, "gst_on_sebi"),
                        bool(row, "gst_on_stt"), bool(row, "gst_on_stamp_duty"));
                if (side == PortfolioExecution.Side.BUY && terms.sttBps().signum() != 0) {
                    throw new IllegalArgumentException("stt_bps must be zero on BUY rows");
                }
                if (side == PortfolioExecution.Side.SELL && terms.stampDutyBps().signum() != 0) {
                    throw new IllegalArgumentException("stamp_duty_bps must be zero on SELL rows");
                }
                entries.add(new Entry(entryId, from, until, required(row, "instrument_id"), kind,
                        side, terms, required(row, "source_reference")));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid portfolio execution cost row " + (index + 2)
                        + ": " + invalid.getMessage(), invalid);
            }
        }
        entries.sort(Comparator.comparing(Entry::instrumentId).thenComparing(Entry::contractKind)
                .thenComparing(Entry::side).thenComparing(Entry::effectiveFrom)
                .thenComparing(Entry::entryId));
        Map<String, Entry> prior = new HashMap<>();
        for (Entry entry : entries) {
            String key = entry.instrumentId() + ":" + entry.contractKind() + ":" + entry.side();
            Entry previous = prior.get(key);
            if (previous != null && (previous.effectiveUntil() == null
                    || entry.effectiveFrom().isBefore(previous.effectiveUntil()))) {
                throw new IllegalArgumentException("Overlapping portfolio execution cost ranges for " + key);
            }
            prior.put(key, entry);
        }
        return new PortfolioExecutionCostSchedule(scheduleId, entries, table.sha256());
    }

    public Resolution calculate(LocalDate date, String instrumentId, ContractKind contractKind,
            PortfolioExecution.Side side, BigDecimal priceInr, long quantityUnits) {
        Objects.requireNonNull(date, "date");
        Entry entry = entries.stream().filter(candidate -> candidate.instrumentId().equals(instrumentId)
                && candidate.contractKind() == contractKind && candidate.side() == side
                && candidate.covers(date)).findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "No unambiguous portfolio execution cost row covers " + instrumentId + " "
                                + contractKind + " " + side + " on " + date));
        BigDecimal tradedValue = Objects.requireNonNull(priceInr, "priceInr")
                .multiply(BigDecimal.valueOf(quantityUnits));
        Charges charges = entry.terms().calculate(tradedValue, quantityUnits);
        PortfolioExecutionFeeBreakdown breakdown = new PortfolioExecutionFeeBreakdown(
                PortfolioExecutionFeeBreakdown.CostModel.DATED_SCHEDULE, scheduleId, entry.entryId(),
                sourceFileSha256, entry.sourceReference(), date, contractKind, tradedValue,
                BigDecimal.ZERO, charges.brokerageInr(), charges.exchangeInr(), charges.ipftInr(),
                charges.sebiInr(), charges.sttInr(), charges.stampDutyInr(),
                charges.gstTaxableBaseInr(), charges.gstInr(), charges.totalInr());
        return new Resolution(entry, breakdown);
    }

    private static BigDecimal croreCharge(BigDecimal base, BigDecimal rate) {
        return base.multiply(rate).divide(CRORE_INR, 12, RoundingMode.HALF_UP);
    }

    private static BigDecimal bpsCharge(BigDecimal base, BigDecimal bps) {
        return base.multiply(bps).divide(BPS_DENOMINATOR, 12, RoundingMode.HALF_UP);
    }

    private static BigDecimal paise(BigDecimal amount) { return amount.setScale(2, RoundingMode.HALF_UP); }

    private static String required(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(key + " must be non-empty and trimmed");
        }
        return value;
    }

    private static String optional(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || !value.equals(value.strip())) throw new IllegalArgumentException(key + " must be trimmed");
        return value;
    }

    private static String identifier(String value, String field) {
        value = required(value, field);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("Invalid " + field + ": " + value);
        }
        return value;
    }

    private static String required(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be non-empty and trimmed");
        }
        return value;
    }

    private static BigDecimal decimal(Map<String, String> row, String key) {
        BigDecimal value = new BigDecimal(required(row, key));
        if (value.signum() < 0) throw new IllegalArgumentException(key + " must be non-negative");
        return value.stripTrailingZeros();
    }

    private static boolean bool(Map<String, String> row, String key) {
        String value = required(row, key);
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException(key + " must be exactly true or false");
        }
        return Boolean.parseBoolean(value);
    }
}
