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

/** Caller-supplied effective-dated itemized execution costs. Rates and GST bases are not verified here. */
public record ComponentCostSchedule(List<Entry> entries, String sha256) {
    private static final BigDecimal CRORE_INR = new BigDecimal("10000000");
    private static final BigDecimal BPS_DENOMINATOR = new BigDecimal("10000");

    public record Charges(BigDecimal brokerageInr, BigDecimal exchangeInr, BigDecimal ipftInr,
            BigDecimal sebiInr, BigDecimal sttInr, BigDecimal stampDutyInr,
            BigDecimal gstTaxableBaseInr, BigDecimal gstInr) {
        public Charges {
            for (BigDecimal value : List.of(brokerageInr, exchangeInr, ipftInr, sebiInr, sttInr,
                    stampDutyInr, gstTaxableBaseInr, gstInr)) {
                if (value.signum() < 0) throw new IllegalArgumentException("fee charges must be non-negative");
            }
        }

        public BigDecimal totalInr() {
            return brokerageInr.add(exchangeInr).add(ipftInr).add(sebiInr)
                    .add(sttInr).add(stampDutyInr).add(gstInr);
        }
    }

    public record Terms(BigDecimal brokeragePerOrderInr, BigDecimal brokeragePerUnitInr,
            BigDecimal brokerageBps, BigDecimal exchangeInrPerCrore, BigDecimal ipftInrPerCrore,
            BigDecimal sebiInrPerCrore, BigDecimal sttBps, BigDecimal stampDutyBps,
            BigDecimal gstBps, boolean gstOnBrokerage, boolean gstOnExchange,
            boolean gstOnIpft, boolean gstOnSebi, boolean gstOnStt,
            boolean gstOnStampDuty, int slippageTicks) {
        public Terms {
            for (BigDecimal value : List.of(brokeragePerOrderInr, brokeragePerUnitInr, brokerageBps,
                    exchangeInrPerCrore, ipftInrPerCrore, sebiInrPerCrore, sttBps, stampDutyBps, gstBps)) {
                if (value.signum() < 0) throw new IllegalArgumentException("component cost rates must be non-negative");
            }
            if (slippageTicks < 0) throw new IllegalArgumentException("slippageTicks must be non-negative");
        }

        public Charges calculate(BigDecimal tradedValueInr, int quantityUnits) {
            if (tradedValueInr.signum() < 0) throw new IllegalArgumentException("traded value must be non-negative");
            if (quantityUnits < 1) throw new IllegalArgumentException("quantity must be positive");
            BigDecimal brokerage = paise(brokeragePerOrderInr
                    .add(brokeragePerUnitInr.multiply(BigDecimal.valueOf(quantityUnits)))
                    .add(bpsCharge(tradedValueInr, brokerageBps)));
            BigDecimal exchange = paise(croreCharge(tradedValueInr, exchangeInrPerCrore));
            BigDecimal ipft = paise(croreCharge(tradedValueInr, ipftInrPerCrore));
            BigDecimal sebi = paise(croreCharge(tradedValueInr, sebiInrPerCrore));
            BigDecimal stt = paise(bpsCharge(tradedValueInr, sttBps));
            BigDecimal stampDuty = paise(bpsCharge(tradedValueInr, stampDutyBps));
            BigDecimal taxableServiceFees = BigDecimal.ZERO;
            if (gstOnBrokerage) taxableServiceFees = taxableServiceFees.add(brokerage);
            if (gstOnExchange) taxableServiceFees = taxableServiceFees.add(exchange);
            if (gstOnIpft) taxableServiceFees = taxableServiceFees.add(ipft);
            if (gstOnSebi) taxableServiceFees = taxableServiceFees.add(sebi);
            if (gstOnStt) taxableServiceFees = taxableServiceFees.add(stt);
            if (gstOnStampDuty) taxableServiceFees = taxableServiceFees.add(stampDuty);
            BigDecimal gst = paise(bpsCharge(taxableServiceFees, gstBps));
            return new Charges(brokerage, exchange, ipft, sebi, stt, stampDuty, paise(taxableServiceFees), gst);
        }

        private static BigDecimal croreCharge(BigDecimal tradedValueInr, BigDecimal inrPerCrore) {
            return tradedValueInr.multiply(inrPerCrore).divide(CRORE_INR, 12, RoundingMode.HALF_UP);
        }

        private static BigDecimal bpsCharge(BigDecimal baseInr, BigDecimal bps) {
            return baseInr.multiply(bps).divide(BPS_DENOMINATOR, 12, RoundingMode.HALF_UP);
        }

        private static BigDecimal paise(BigDecimal amount) {
            return amount.setScale(2, RoundingMode.HALF_UP);
        }
    }

    public record Entry(LocalDate effectiveFrom, LocalDate effectiveThrough,
            ContractKind contractKind, CostSchedule.Side side, Terms terms) {
        public Entry {
            Objects.requireNonNull(effectiveFrom, "effectiveFrom");
            Objects.requireNonNull(contractKind, "contractKind");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(terms, "terms");
            if (contractKind != ContractKind.FUTURE && contractKind != ContractKind.OPTION) {
                throw new IllegalArgumentException("component costs support FUTURE or OPTION contracts only");
            }
            if (effectiveThrough != null && effectiveThrough.isBefore(effectiveFrom)) {
                throw new IllegalArgumentException("cost effective_through precedes effective_from");
            }
        }

        public boolean covers(LocalDate date) {
            return !date.isBefore(effectiveFrom) && (effectiveThrough == null || !date.isAfter(effectiveThrough));
        }
    }

    public ComponentCostSchedule {
        entries = List.copyOf(entries);
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid component cost schedule SHA-256");
    }

    public static ComponentCostSchedule load(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        List<String> expected = List.of("effective_from", "effective_through", "contract_kind", "side",
                "brokerage_per_order_inr", "brokerage_per_unit_inr", "brokerage_bps",
                "exchange_inr_per_crore", "ipft_inr_per_crore", "sebi_inr_per_crore", "stt_bps",
                "stamp_duty_bps", "gst_bps", "gst_on_brokerage", "gst_on_exchange", "gst_on_ipft",
                "gst_on_sebi", "gst_on_stt", "gst_on_stamp_duty", "slippage_ticks");
        if (!table.headers().equals(expected)) {
            throw new IllegalArgumentException("Component cost schedule headers must be exactly: " + String.join(",", expected));
        }
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < table.rows().size(); i++) {
            Map<String, String> row = table.rows().get(i);
            try {
                LocalDate from = LocalDate.parse(required(row, "effective_from"));
                String throughText = row.get("effective_through");
                if (throughText == null || !throughText.equals(throughText.strip())) {
                    throw new IllegalArgumentException("effective_through must be trimmed");
                }
                LocalDate through = throughText.isBlank() ? null : LocalDate.parse(throughText);
                ContractKind kind = ContractKind.valueOf(required(row, "contract_kind"));
                CostSchedule.Side side = CostSchedule.Side.valueOf(required(row, "side"));
                Terms terms = new Terms(nonNegative(row, "brokerage_per_order_inr"),
                        nonNegative(row, "brokerage_per_unit_inr"), nonNegative(row, "brokerage_bps"),
                        nonNegative(row, "exchange_inr_per_crore"), nonNegative(row, "ipft_inr_per_crore"),
                        nonNegative(row, "sebi_inr_per_crore"), nonNegative(row, "stt_bps"),
                        nonNegative(row, "stamp_duty_bps"), nonNegative(row, "gst_bps"),
                        bool(row, "gst_on_brokerage"), bool(row, "gst_on_exchange"),
                        bool(row, "gst_on_ipft"), bool(row, "gst_on_sebi"),
                        bool(row, "gst_on_stt"), bool(row, "gst_on_stamp_duty"),
                        Integer.parseInt(required(row, "slippage_ticks")));
                if (terms.slippageTicks() < 0) throw new IllegalArgumentException("slippage_ticks must be non-negative");
                if (side == CostSchedule.Side.BUY && terms.sttBps().signum() != 0) {
                    throw new IllegalArgumentException("stt_bps must be zero on BUY rows");
                }
                if (side == CostSchedule.Side.SELL && terms.stampDutyBps().signum() != 0) {
                    throw new IllegalArgumentException("stamp_duty_bps must be zero on SELL rows");
                }
                entries.add(new Entry(from, through, kind, side, terms));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid component cost schedule row " + (i + 2) + ": "
                        + invalid.getMessage(), invalid);
            }
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("Component cost schedule contains no rows");
        entries.sort(Comparator.comparing(Entry::contractKind).thenComparing(Entry::side)
                .thenComparing(Entry::effectiveFrom));
        Map<String, Entry> prior = new HashMap<>();
        for (Entry entry : entries) {
            String key = entry.contractKind() + ":" + entry.side();
            Entry previous = prior.get(key);
            if (previous != null && (previous.effectiveThrough() == null
                    || !entry.effectiveFrom().isAfter(previous.effectiveThrough()))) {
                throw new IllegalArgumentException("Overlapping component cost schedule ranges for " + key);
            }
            prior.put(key, entry);
        }
        return new ComponentCostSchedule(entries, table.sha256());
    }

    public Terms require(LocalDate date, ContractKind kind, CostSchedule.Side side) {
        return entries.stream().filter(entry -> entry.contractKind() == kind && entry.side() == side && entry.covers(date))
                .map(Entry::terms).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No " + kind + " " + side
                        + " component cost schedule covers " + date));
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

    private static boolean bool(Map<String, String> row, String key) {
        String value = required(row, key).toLowerCase(java.util.Locale.ROOT);
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }
}
