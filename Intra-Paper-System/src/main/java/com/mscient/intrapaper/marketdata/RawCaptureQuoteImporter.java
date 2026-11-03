package com.mscient.intrapaper.marketdata;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Converts a verified local raw capture into a normalized quote journal using a caller-supplied decoder. */
public final class RawCaptureQuoteImporter {
    private RawCaptureQuoteImporter() { }

    /**
     * Imports quotes to a new journal, validating the raw hash chain and publishing only after the
     * complete normalized journal has also been verified. Decoders may return empty for non-quote
     * messages. No feed connection, source adapter, or order routing is provided.
     */
    public static RawCaptureQuoteImportResult importJournal(Path rawCapturePath, Path normalizedJournalPath,
            RawQuoteDecoder decoder) throws IOException {
        Objects.requireNonNull(rawCapturePath, "rawCapturePath");
        Objects.requireNonNull(normalizedJournalPath, "normalizedJournalPath");
        Objects.requireNonNull(decoder, "decoder");
        Path rawPath = rawCapturePath.toAbsolutePath().normalize();
        Path destination = normalizedJournalPath.toAbsolutePath().normalize();
        if (rawPath.equals(destination)) throw new IllegalArgumentException("Raw and normalized journal paths must differ");
        if (Files.exists(destination)) throw new java.nio.file.FileAlreadyExistsException(destination.toString());
        Path parent = destination.getParent();
        if (parent == null) throw new IOException("Normalized journal path must have a parent directory");
        Files.createDirectories(parent);
        Path staging = parent.resolve("." + destination.getFileName() + ".import-" + UUID.randomUUID());
        long[] normalizedCount = {0};
        boolean published = false;
        try {
            RawCaptureSummary rawSummary;
            try (QuoteJournalWriter writer = QuoteJournalWriter.create(staging)) {
                rawSummary = RawCaptureJournalReader.replay(rawPath, message -> {
                    Optional<MarketQuote> decoded = Objects.requireNonNull(decoder.decode(message),
                            "RawQuoteDecoder returned null instead of Optional");
                    if (decoded.isEmpty()) return;
                    MarketQuote quote = decoded.orElseThrow();
                    if (!quote.source().equals(message.source())
                            || !quote.receivedAtUtc().equals(message.receivedAtUtc())
                            || quote.receivedAtMonotonicNanos() != message.receivedAtMonotonicNanos()) {
                        throw new IllegalArgumentException("Decoded quote source and receive timestamps must match the raw capture metadata");
                    }
                    normalizedCount[0] = Math.addExact(normalizedCount[0], 1);
                    MarketQuote sequenced = new MarketQuote(quote.schemaVersion(), normalizedCount[0], quote.source(),
                            quote.instrumentId(), quote.exchangeTimestampUtc(), quote.receivedAtUtc(),
                            quote.receivedAtMonotonicNanos(), quote.bidPriceTicks(), quote.bidQuantity(),
                            quote.askPriceTicks(), quote.askQuantity());
                    try {
                        writer.append(sequenced);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                });
            } catch (UncheckedIOException failure) {
                throw failure.getCause();
            }
            if (normalizedCount[0] == 0) throw new IOException("Raw capture produced no normalized quote snapshots");
            QuoteJournalSummary normalizedSummary = QuoteJournalReader.replay(staging, ignored -> { });
            if (normalizedSummary.eventCount() != normalizedCount[0]) {
                throw new IOException("Normalized quote journal count differs from imported quote count");
            }
            try {
                Files.createLink(destination, staging);
            } catch (UnsupportedOperationException unsupported) {
                throw new IOException("Atomic no-overwrite publication requires filesystem hard-link support", unsupported);
            }
            Files.delete(staging);
            published = true;
            return new RawCaptureQuoteImportResult(rawSummary, normalizedCount[0], normalizedSummary);
        } finally {
            if (!published) Files.deleteIfExists(staging);
        }
    }
}
