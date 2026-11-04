package com.mscient.intrapaper.marketdata;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import com.mscient.intrapaper.reference.ContractKind;
import com.mscient.intrapaper.reference.DatedInstrumentMappingRegistry;
import com.mscient.intrapaper.reference.InstrumentMasterSnapshot;
import com.mscient.intrapaper.reference.InstrumentReference;

/** Focused checks for instrument-scoped source sequence diagnostics. */
public final class InstrumentSequenceAnalyzerTest {
    private InstrumentSequenceAnalyzerTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("instrument-sequence-check-");
        try {
            Path raw = root.resolve("capture.raw");
            try (RawCaptureJournalWriter writer = RawCaptureJournalWriter.create(raw)) {
                append(writer, "FUT-A", 10, 1);
                append(writer, "FUT-B", 100, 2);
                append(writer, "FUT-A", 12, 3);
                append(writer, "FUT-B", 101, 4);
                append(writer, "FUT-A", 11, 5);
                writer.append(message("heartbeat", OptionalLong.empty(), 6));
                append(writer, "FUT-A", -1, 7);
            }

            RawQuoteDecoder decoder = message -> {
                String instrument = new String(message.payload(), StandardCharsets.UTF_8);
                if (instrument.equals("heartbeat")) return Optional.empty();
                return Optional.of(new MarketQuote(1, 1, message.source(), instrument,
                        Optional.empty(), message.receivedAtUtc(), message.receivedAtMonotonicNanos(),
                        100, 1, 101, 1));
            };
            InstrumentSequenceReport report = InstrumentSequenceAnalyzer.analyze(raw, decoder);
            require(report.rawCapture().eventCount() == 7, "raw journal event count");
            require(report.decodedQuoteCount() == 6 && report.nonQuoteMessageCount() == 1, "decoded message counts");
            require(report.streams().size() == 2, "separate instrument streams");
            var a = report.streams().stream().filter(stream -> stream.instrumentId().equals("FUT-A")).findFirst().orElseThrow();
            var b = report.streams().stream().filter(stream -> stream.instrumentId().equals("FUT-B")).findFirst().orElseThrow();
            require(a.quoteCount() == 4 && a.sequencedQuoteCount() == 3 && a.quotesWithoutSequence() == 1,
                    "instrument A sequence coverage");
            require(a.sequenceGapCount() == 1 && a.missingSequenceNumbers() == 1
                    && a.nonIncreasingTransitions() == 1, "instrument A continuity findings");
            require(a.firstSourceSequence().orElseThrow() == 10 && a.lastSourceSequence().orElseThrow() == 11,
                    "instrument A observed sequence bounds");
            require(b.quoteCount() == 2 && b.sequenceGapCount() == 0 && b.nonIncreasingTransitions() == 0,
                    "instrument B remains an independent sequence stream");
            checkSmartApiEmbeddedSequence(root);
            System.out.println("InstrumentSequenceAnalyzer focused checks passed.");
        } finally {
            Files.deleteIfExists(root.resolve("capture.raw"));
            Files.deleteIfExists(root.resolve("smartapi.raw"));
            Files.deleteIfExists(root.resolve("mapping.csv"));
            Files.deleteIfExists(root);
        }
    }

    private static void checkSmartApiEmbeddedSequence(Path root) throws Exception {
        Path registryPath = root.resolve("mapping.csv");
        String header = String.join(",", DatedInstrumentMappingRegistry.HEADERS);
        String row = "m1,NFO,123,FUT-A,NIFTY-FUT,FUTURE,2026-11-26,,,2026-01-01T00:00:00Z,,fixture,fixture,"
                + "0".repeat(64);
        Files.writeString(registryPath, header + System.lineSeparator() + row + System.lineSeparator(),
                StandardCharsets.UTF_8);
        var registry = DatedInstrumentMappingRegistry.load(registryPath);
        var reference = new InstrumentReference(1, "FUT-A", "NSE", "NFO", "123", "NIFTY-FUT",
                ContractKind.FUTURE, Optional.of("NIFTY"), Optional.of(LocalDate.parse("2026-11-26")),
                Optional.empty(), Optional.empty(), 65, new BigDecimal("0.05"), LocalDate.parse("2026-01-01"),
                Optional.empty());
        var master = new InstrumentMasterSnapshot(1, "master", "fixture",
                Instant.parse("2026-11-05T00:00:00Z"), LocalDate.parse("2026-01-01"), "0".repeat(64),
                List.of(reference));
        var decoder = new SmartApiV2NfoSnapQuoteDecoder(registry, master);
        Path raw = root.resolve("smartapi.raw");
        try (RawCaptureJournalWriter writer = RawCaptureJournalWriter.create(raw)) {
            writer.append(new RawMarketMessage(1, "feed", "conn-2", "sub-2", OptionalLong.empty(),
                    Instant.parse("2026-11-05T04:00:00Z"), 1, smartApiPacket(123, 500)));
            writer.append(new RawMarketMessage(1, "feed", "conn-2", "sub-2", OptionalLong.empty(),
                    Instant.parse("2026-11-05T04:00:01Z"), 2, smartApiPacket(123, 502)));
        }
        var report = InstrumentSequenceAnalyzer.analyze(raw, decoder);
        require(report.sequenceDomain().equals("smartapi.nfo_snap_quote.packet_sequence"),
                "SmartAPI packet sequence domain");
        require(report.streams().size() == 1 && report.streams().getFirst().instrumentId().equals("FUT-A"),
                "SmartAPI packet sequence is attributed to the dated instrument");
        require(report.streams().getFirst().sequenceGapCount() == 1
                && report.streams().getFirst().missingSequenceNumbers() == 1,
                "SmartAPI embedded sequence gap");
    }

    private static byte[] smartApiPacket(long token, long sequence) {
        byte[] packet = new byte[379];
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        packet[0] = 3;
        packet[1] = 2;
        byte[] tokenBytes = Long.toString(token).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tokenBytes, 0, packet, 2, tokenBytes.length);
        buffer.putLong(27, sequence);
        buffer.putLong(35, Instant.parse("2026-11-05T04:00:00Z").toEpochMilli());
        return packet;
    }

    private static void append(RawCaptureJournalWriter writer, String instrument, long sequence, long nanos)
            throws Exception {
        writer.append(message(instrument, sequence < 0 ? OptionalLong.empty() : OptionalLong.of(sequence), nanos));
    }

    private static RawMarketMessage message(String payload, OptionalLong sequence, long nanos) {
        return new RawMarketMessage(1, "feed", "conn-1", "sub-1", sequence,
                Instant.parse("2026-11-05T04:00:00Z").plusNanos(nanos), nanos,
                payload.getBytes(StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
