package com.mscient.intrapaper.marketdata;

import com.mscient.intrapaper.data.CsvTable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Streams verified quote snapshots into an atomic, no-overwrite L1 feature CSV. */
public final class QuoteFeatureCsvExporter {
    public record ExportResult(QuoteJournalSummary journal, long featureRows, Path outputPath) { }

    private static final String HEADER = "sequence_number,source,instrument_id,expected_instrument_id,"
            + "source_journal_final_hash,decision_time_utc,received_at_utc,exchange_timestamp_utc,"
            + "maximum_age_allowed,maximum_future_skew_allowed,"
            + "decision_eligible,has_prior_snapshot,sequence_continuous,same_source_as_prior,"
            + "missing_sequence_numbers,spread_ticks,mid_price_ticks,microprice_ticks,size_imbalance,"
            + "l1_order_flow_imbalance_units,quality_flags";

    private QuoteFeatureCsvExporter() { }

    /** Uses each quote's receive clock as its virtual replay decision clock. */
    public static ExportResult export(Path journalPath, String expectedInstrumentId,
            QuoteQualityPolicy qualityPolicy, Path requestedOutputPath) throws IOException {
        Objects.requireNonNull(journalPath, "journalPath");
        Objects.requireNonNull(qualityPolicy, "qualityPolicy");
        Objects.requireNonNull(requestedOutputPath, "requestedOutputPath");
        Path journal = journalPath.toAbsolutePath().normalize();
        Path output = requestedOutputPath.toAbsolutePath().normalize();
        if (journal.equals(output)) throw new IllegalArgumentException("Feature output path must differ from quote journal path");
        Path parent = output.getParent();
        Path fileName = output.getFileName();
        if (parent == null || fileName == null) throw new IOException("Feature output path must name a file");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(output.toString());
        }
        QuoteJournalSummary verifiedSummary = QuoteJournalReader.replay(journal, ignored -> { });
        Files.createDirectories(parent);
        Path staging = Files.createTempFile(parent, "." + fileName + ".features-" + UUID.randomUUID(), ".tmp");
        boolean published = false;
        try {
            TopOfBookFeatureStream featureStream = new TopOfBookFeatureStream(expectedInstrumentId, qualityPolicy);
            long[] featureRows = {0};
            QuoteJournalSummary journalSummary;
            try (BufferedWriter writer = Files.newBufferedWriter(staging, StandardCharsets.UTF_8)) {
                writer.write(HEADER);
                writer.write('\n');
                try {
                    journalSummary = QuoteJournalReader.replay(journal, quote -> {
                        TopOfBookFeatureFrame frame = featureStream.accept(quote, quote.receivedAtUtc());
                        try {
                            writer.write(row(frame, expectedInstrumentId, qualityPolicy,
                                    verifiedSummary.finalHashHex()));
                            writer.write('\n');
                            featureRows[0] = Math.addExact(featureRows[0], 1);
                        } catch (IOException writeFailure) {
                            throw new UncheckedIOException(writeFailure);
                        }
                    });
                } catch (UncheckedIOException writeFailure) {
                    throw writeFailure.getCause();
                }
                if (!journalSummary.equals(verifiedSummary)) {
                    throw new IOException("Quote journal changed between verification and feature export replay");
                }
            }
            try {
                Files.createLink(output, staging);
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("Atomic no-overwrite feature publication requires filesystem hard-link support", unsupported);
            }
            published = true;
            try {
                Files.deleteIfExists(staging);
            } catch (IOException ignored) {
                // The published output is complete; an extra staging link is safe to remove later.
            }
            return new ExportResult(journalSummary, featureRows[0], output);
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

    private static String row(TopOfBookFeatureFrame frame, String expectedInstrumentId,
            QuoteQualityPolicy qualityPolicy, String sourceJournalFinalHash) {
        TopOfBookFeatures features = frame.snapshot();
        List<String> values = new ArrayList<>(21);
        values.add(Long.toString(features.sequenceNumber()));
        values.add(features.source());
        values.add(features.instrumentId());
        values.add(expectedInstrumentId);
        values.add(sourceJournalFinalHash);
        values.add(features.decisionTimeUtc().toString());
        values.add(features.receivedAtUtc().toString());
        values.add(features.exchangeTimestampUtc().map(Object::toString).orElse(""));
        values.add(qualityPolicy.maximumAge().toString());
        values.add(qualityPolicy.maximumFutureSkew().toString());
        values.add(Boolean.toString(features.decisionEligible()));
        values.add(Boolean.toString(frame.hasPriorSnapshot()));
        values.add(Boolean.toString(frame.sequenceContinuous()));
        values.add(Boolean.toString(frame.sameSourceAsPrior()));
        values.add(Long.toString(frame.missingSequenceNumbers()));
        values.add(decimal(features.spreadTicks().orElse(null)));
        values.add(decimal(features.midPriceTicks().orElse(null)));
        values.add(decimal(features.micropriceTicks().orElse(null)));
        values.add(decimal(features.sizeImbalance().orElse(null)));
        values.add(decimal(frame.l1OrderFlowImbalanceUnits().orElse(null)));
        values.add(flags(features.qualityFlags()));
        return String.join(",", values.stream().map(CsvTable::escape).toList());
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static String flags(Set<QuoteQualityFlag> flags) {
        List<String> ordered = new ArrayList<>();
        for (QuoteQualityFlag flag : QuoteQualityFlag.values()) {
            if (flags.contains(flag)) ordered.add(flag.name());
        }
        return String.join("|", ordered);
    }
}
