package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.marketdata.MarketQuote;
import com.mscient.intrapaper.marketdata.QuoteJournalReader;
import com.mscient.intrapaper.marketdata.QuoteJournalSummary;
import com.mscient.intrapaper.marketdata.QuoteQualityPolicy;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/** Verifies a quote journal, replays a strict intent CSV, and atomically publishes source-hashed outcomes. */
public final class QuoteOrderReplayCsvRunner {
    public record ReplayResult(QuoteJournalSummary journal, String intentFileSha256,
            long intentCount, long filledCount, long partialCount, long missedCount, Path outputPath) { }

    private static final List<String> INPUT_HEADERS = List.of("order_id", "source", "instrument_id",
            "decision_time_utc", "latency_ms", "side", "type", "requested_units",
            "limit_price_ticks", "adverse_slippage_ticks", "tick_size");
    private static final String OUTPUT_HEADER = "model_id,quote_journal_final_hash,intent_csv_sha256,order_id,source,"
            + "instrument_id,decision_time_utc,arrival_time_utc,latency_ms,side,type,requested_units,"
            + "limit_price_ticks,adverse_slippage_ticks,tick_size,status,final_oms_state,filled_units,"
            + "remaining_units,quote_sequence,quote_received_at_utc,reference_price_ticks,fill_price_ticks,"
            + "execution_id,reason,state_events";

    private QuoteOrderReplayCsvRunner() { }

    /**
     * The complete verified quote history is held in memory because current order selection may
     * depend on receive and exchange timestamps that are not guaranteed to be monotonic in journal order.
     */
    public static ReplayResult replay(Path journalPath, Path intentCsvPath, QuoteQualityPolicy qualityPolicy,
            Path requestedOutputPath) throws IOException {
        Objects.requireNonNull(journalPath, "journalPath");
        Objects.requireNonNull(intentCsvPath, "intentCsvPath");
        Objects.requireNonNull(qualityPolicy, "qualityPolicy");
        Objects.requireNonNull(requestedOutputPath, "requestedOutputPath");
        Path journal = journalPath.toAbsolutePath().normalize();
        Path intentsFile = intentCsvPath.toAbsolutePath().normalize();
        Path output = requestedOutputPath.toAbsolutePath().normalize();
        if (output.equals(journal) || output.equals(intentsFile)) {
            throw new IllegalArgumentException("Replay output must differ from both input files");
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(output.toString());

        CsvTable intentTable = CsvTable.read(intentsFile);
        if (!intentTable.headers().equals(INPUT_HEADERS)) {
            throw new IllegalArgumentException("Intent CSV headers must exactly match: " + String.join(",", INPUT_HEADERS));
        }
        List<QuoteOrderReplay.Intent> intents = parseIntents(intentTable);

        List<MarketQuote> quoteHistory = new ArrayList<>();
        QuoteJournalSummary journalSummary = QuoteJournalReader.replay(journal, quoteHistory::add);
        if (journalSummary.eventCount() != quoteHistory.size()) {
            throw new IOException("Verified quote count differs from replay history count");
        }

        Path parent = output.getParent();
        Path fileName = output.getFileName();
        if (parent == null || fileName == null) throw new IOException("Replay output path must name a file");
        Files.createDirectories(parent);
        Path staging = Files.createTempFile(parent, "." + fileName + ".quote-replay-" + UUID.randomUUID(), ".tmp");
        boolean published = false;
        long filled = 0;
        long partial = 0;
        long missed = 0;
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(staging, StandardCharsets.UTF_8)) {
                writer.write(OUTPUT_HEADER);
                writer.write('\n');
                List<QuoteOrderReplay.Outcome> outcomes = QuoteOrderReplay.executeBatch(quoteHistory, intents, qualityPolicy);
                for (QuoteOrderReplay.Outcome outcome : outcomes) {
                    switch (outcome.status()) {
                        case FILLED -> filled = Math.addExact(filled, 1);
                        case PARTIALLY_FILLED -> partial = Math.addExact(partial, 1);
                        case MISSED -> missed = Math.addExact(missed, 1);
                    }
                    writer.write(encodeRow(outcome, journalSummary.finalHashHex(), intentTable.sha256()));
                    writer.write('\n');
                }
            }
            try {
                Files.createLink(output, staging);
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("Atomic no-overwrite quote replay publication requires filesystem hard-link support", unsupported);
            }
            published = true;
            try {
                Files.deleteIfExists(staging);
            } catch (IOException ignored) {
                // The published output is complete; a second staging link is safe to remove later.
            }
            return new ReplayResult(journalSummary, intentTable.sha256(), intents.size(), filled, partial, missed, output);
        } finally {
            if (!published) {
                try {
                    Files.deleteIfExists(staging);
                } catch (IOException ignored) {
                    // Preserve the original verification or write failure.
                }
            }
        }
    }

    private static List<QuoteOrderReplay.Intent> parseIntents(CsvTable table) {
        List<QuoteOrderReplay.Intent> intents = new ArrayList<>(table.rows().size());
        Set<String> seenOrderIds = new HashSet<>();
        int rowNumber = 1;
        for (var row : table.rows()) {
            rowNumber++;
            try {
                String orderId = row.get("order_id");
                if (!seenOrderIds.add(orderId)) throw new IllegalArgumentException("duplicate order_id");
                OptionalLong limitPrice = row.get("limit_price_ticks").isBlank()
                        ? OptionalLong.empty() : OptionalLong.of(Long.parseLong(row.get("limit_price_ticks")));
                intents.add(new QuoteOrderReplay.Intent(orderId, row.get("source"), row.get("instrument_id"),
                        Instant.parse(row.get("decision_time_utc")),
                        Duration.ofMillis(Long.parseLong(row.get("latency_ms"))),
                        ExecutionEvent.Side.valueOf(row.get("side")), QuoteOrderReplay.Type.valueOf(row.get("type")),
                        Integer.parseInt(row.get("requested_units")), limitPrice,
                        Integer.parseInt(row.get("adverse_slippage_ticks")), new BigDecimal(row.get("tick_size"))));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid intent CSV row " + rowNumber + ": " + invalid.getMessage(), invalid);
            }
        }
        return List.copyOf(intents);
    }

    private static String encodeRow(QuoteOrderReplay.Outcome outcome, String journalHash, String intentHash) {
        QuoteOrderReplay.Intent intent = outcome.intent();
        List<String> values = new ArrayList<>(26);
        values.add(outcome.modelId());
        values.add(journalHash);
        values.add(intentHash);
        values.add(intent.orderId());
        values.add(intent.source());
        values.add(intent.instrumentId());
        values.add(intent.decisionTimestamp().toString());
        values.add(outcome.arrivalTimestamp().toString());
        values.add(Long.toString(intent.decisionToArrivalLatency().toMillis()));
        values.add(intent.side().name());
        values.add(intent.type().name());
        values.add(Integer.toString(intent.requestedUnits()));
        values.add(intent.limitPriceTicks().isPresent() ? Long.toString(intent.limitPriceTicks().getAsLong()) : "");
        values.add(Integer.toString(intent.adverseSlippageTicks()));
        values.add(intent.tickSize().toPlainString());
        values.add(outcome.status().name());
        values.add(outcome.finalState().name());
        values.add(Integer.toString(outcome.filledUnits()));
        values.add(Integer.toString(outcome.remainingUnits()));
        values.add(optionalLong(outcome.quoteSequence()));
        values.add(outcome.quoteReceivedAt().map(Instant::toString).orElse(""));
        values.add(optionalLong(outcome.referencePriceTicks()));
        values.add(optionalLong(outcome.fillPriceTicks()));
        values.add(outcome.execution().map(ExecutionEvent::executionId).orElse(""));
        values.add(outcome.reason());
        values.add(outcome.stateEvents().stream()
                .map(event -> event.timestamp() + "|" + event.state() + "|" + event.reason())
                .reduce((left, right) -> left + ";" + right).orElse(""));
        return String.join(",", values.stream().map(CsvTable::escape).toList());
    }

    private static String optionalLong(OptionalLong value) {
        return value.isPresent() ? Long.toString(value.getAsLong()) : "";
    }
}
