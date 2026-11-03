package com.mscient.intrapaper.provenance;

import com.mscient.intrapaper.data.CsvTable;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** User-supplied effective-dated execution-cost inputs. Values are not independently verified. */
public record CostSchedule(List<Entry> entries, String sha256) {
    public enum Side { BUY, SELL }

    public record Terms(BigDecimal feePerOrderInr, BigDecimal feePerUnitInr,
            BigDecimal notionalFeeBps, int slippageTicks) { }

    public record Entry(LocalDate effectiveFrom, LocalDate effectiveThrough, Side side, Terms terms) {
        public Entry {
            if (effectiveThrough != null && effectiveThrough.isBefore(effectiveFrom)) {
                throw new IllegalArgumentException("cost effective_through precedes effective_from");
            }
        }

        public boolean covers(LocalDate date) {
            return !date.isBefore(effectiveFrom) && (effectiveThrough == null || !date.isAfter(effectiveThrough));
        }
    }

    public CostSchedule {
        entries = List.copyOf(entries);
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid cost schedule SHA-256");
    }

    public static CostSchedule load(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        List<String> expected = List.of("effective_from", "effective_through", "side",
                "fee_per_order_inr", "fee_per_unit_inr", "notional_fee_bps", "slippage_ticks");
        if (!table.headers().equals(expected)) throw new IllegalArgumentException("Cost schedule headers must be exactly: " + String.join(",", expected));
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < table.rows().size(); i++) {
            Map<String, String> row = table.rows().get(i);
            try {
                LocalDate from = LocalDate.parse(required(row, "effective_from"));
                String throughText = row.get("effective_through");
                if (throughText == null || !throughText.equals(throughText.strip())) throw new IllegalArgumentException("effective_through must be trimmed");
                LocalDate through = throughText.isBlank() ? null : LocalDate.parse(throughText);
                Side side = Side.valueOf(required(row, "side"));
                Terms terms = new Terms(nonNegative(row, "fee_per_order_inr"), nonNegative(row, "fee_per_unit_inr"),
                        nonNegative(row, "notional_fee_bps"), Integer.parseInt(required(row, "slippage_ticks")));
                if (terms.slippageTicks() < 0) throw new IllegalArgumentException("slippage_ticks must be non-negative");
                entries.add(new Entry(from, through, side, terms));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid cost schedule row " + (i + 2) + ": " + invalid.getMessage(), invalid);
            }
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("Cost schedule contains no rows");
        entries.sort(Comparator.comparing(Entry::side).thenComparing(Entry::effectiveFrom));
        EnumMap<Side, Entry> prior = new EnumMap<>(Side.class);
        for (Entry entry : entries) {
            Entry previous = prior.get(entry.side());
            if (previous != null && (previous.effectiveThrough() == null
                    || !entry.effectiveFrom().isAfter(previous.effectiveThrough()))) {
                throw new IllegalArgumentException("Overlapping cost schedule ranges for " + entry.side());
            }
            prior.put(entry.side(), entry);
        }
        return new CostSchedule(entries, table.sha256());
    }

    public Terms require(LocalDate date, Side side) {
        return entries.stream().filter(entry -> entry.side() == side && entry.covers(date))
                .map(Entry::terms).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No " + side + " cost schedule covers " + date));
    }

    private static String required(Map<String, String> row, String key) {
        String value = row.get(key);
        if (value == null || value.isBlank() || !value.equals(value.strip())) throw new IllegalArgumentException(key + " must be non-empty and trimmed");
        return value;
    }

    private static BigDecimal nonNegative(Map<String, String> row, String key) {
        BigDecimal value = new BigDecimal(required(row, key));
        if (value.signum() < 0) throw new IllegalArgumentException(key + " must be non-negative");
        return value.stripTrailingZeros();
    }
}
