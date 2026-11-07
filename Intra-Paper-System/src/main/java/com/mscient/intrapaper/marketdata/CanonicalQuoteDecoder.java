package com.mscient.intrapaper.marketdata;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

/** Decoder paired with the version-1 canonical quote encoder. */
public final class CanonicalQuoteDecoder {
    private static final int MAX_STRING_BYTES = 65_536;

    private CanonicalQuoteDecoder() {}

    public static MarketQuote decode(byte[] encoded) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int schemaVersion = in.readInt();
            long sequence = in.readLong();
            String source = readUtf8(in);
            String instrumentId = readUtf8(in);
            Optional<Instant> exchangeTimestamp = in.readBoolean()
                    ? Optional.of(readInstant(in))
                    : Optional.empty();
            Instant receivedAtUtc = readInstant(in);
            long receivedAtMonotonicNanos = in.readLong();
            long bidPriceTicks = in.readLong();
            long bidQuantity = in.readLong();
            long askPriceTicks = in.readLong();
            long askQuantity = in.readLong();
            if (in.read() != -1) {
                throw new IOException("Canonical quote contains trailing bytes");
            }
            try {
                return new MarketQuote(schemaVersion, sequence, source, instrumentId, exchangeTimestamp,
                        receivedAtUtc, receivedAtMonotonicNanos, bidPriceTicks, bidQuantity, askPriceTicks,
                        askQuantity);
            } catch (IllegalArgumentException | NullPointerException invalidQuote) {
                throw new IOException("Invalid quote in journal", invalidQuote);
            }
        } catch (EOFException truncated) {
            throw new IOException("Truncated canonical quote", truncated);
        }
    }

    private static String readUtf8(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("Invalid UTF-8 field length: " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new IOException("Invalid UTF-8 field", invalidUtf8);
        }
    }

    private static Instant readInstant(DataInputStream in) throws IOException {
        long seconds = in.readLong();
        int nanos = in.readInt();
        if (nanos < 0 || nanos >= 1_000_000_000) {
            throw new IOException("Invalid nanosecond field in journal timestamp");
        }
        try {
            return Instant.ofEpochSecond(seconds, nanos);
        } catch (RuntimeException invalidInstant) {
            throw new IOException("Invalid timestamp in journal", invalidInstant);
        }
    }
}
