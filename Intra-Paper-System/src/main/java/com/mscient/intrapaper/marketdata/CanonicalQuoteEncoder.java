package com.mscient.intrapaper.marketdata;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Stable version-1 binary encoding used as the input to event hashes. */
public final class CanonicalQuoteEncoder {
    private CanonicalQuoteEncoder() {}

    public static byte[] encode(MarketQuote quote) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(160);
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(quote.schemaVersion());
                out.writeLong(quote.sequenceNumber());
                writeUtf8(out, quote.source());
                writeUtf8(out, quote.instrumentId());
                out.writeBoolean(quote.exchangeTimestampUtc().isPresent());
                if (quote.exchangeTimestampUtc().isPresent()) {
                    writeInstant(out, quote.exchangeTimestampUtc().orElseThrow());
                }
                writeInstant(out, quote.receivedAtUtc());
                out.writeLong(quote.receivedAtMonotonicNanos());
                out.writeLong(quote.bidPriceTicks());
                out.writeLong(quote.bidQuantity());
                out.writeLong(quote.askPriceTicks());
                out.writeLong(quote.askQuantity());
            }
            return bytes.toByteArray();
        } catch (IOException impossibleForMemoryStream) {
            throw new IllegalStateException("Unable to encode quote", impossibleForMemoryStream);
        }
    }

    private static void writeUtf8(DataOutputStream out, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static void writeInstant(DataOutputStream out, Instant value) throws IOException {
        out.writeLong(value.getEpochSecond());
        out.writeInt(value.getNano());
    }
}
