package com.mscient.intrapaper.marketdata;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.OptionalLong;
import java.util.function.Consumer;

/** Verifies capture framing and hash chain before delivering records to the caller. */
public final class RawCaptureJournalReader {
    private RawCaptureJournalReader() { }

    public static RawCaptureSummary replay(Path path, Consumer<RawMarketMessage> consumer) throws IOException {
        try (InputStream file = Files.newInputStream(path); BufferedInputStream input = new BufferedInputStream(file)) {
            byte[] header = readExactly(input, RawCaptureFormat.header().length, "truncated raw capture header");
            if (!Arrays.equals(header, RawCaptureFormat.header())) throw new IOException("Unsupported raw capture journal header");
            byte[] previous = QuoteHashChain.genesisHash();
            long count = 0;
            long bytesRead = header.length;
            while (true) {
                int first = input.read();
                if (first < 0) break;
                byte[] remainingLength = readExactly(input, 3, "truncated raw capture frame length");
                ByteBuffer lengthBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
                        .put((byte) first).put(remainingLength);
                lengthBytes.flip();
                int length = lengthBytes.getInt();
                if (length < 1 || length > RawCaptureFormat.MAX_PAYLOAD_BYTES + 65_536) throw new IOException("Invalid raw capture frame length: " + length);
                byte[] encoded = readExactly(input, length, "truncated raw capture record");
                byte[] storedHash = readExactly(input, RawCaptureFormat.HASH_BYTES, "truncated raw capture hash");
                byte[] expectedHash = QuoteHashChain.nextHash(previous, encoded);
                if (!MessageDigest.isEqual(expectedHash, storedHash)) throw new IOException("Raw capture hash-chain mismatch at record " + (count + 1));
                RawMarketMessage message = decode(encoded);
                if (!Arrays.equals(encoded, RawCaptureEncoder.encode(message))) throw new IOException("Raw capture record is not canonically encoded");
                consumer.accept(message);
                previous = expectedHash;
                count++;
                bytesRead += Integer.BYTES + length + RawCaptureFormat.HASH_BYTES;
            }
            return new RawCaptureSummary(count, bytesRead, QuoteHashChain.hex(previous));
        }
    }

    private static RawMarketMessage decode(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readInt();
            String source = readString(in);
            String connection = readString(in);
            String subscription = readString(in);
            OptionalLong sequence = in.readBoolean() ? OptionalLong.of(in.readLong()) : OptionalLong.empty();
            Instant received = Instant.ofEpochSecond(in.readLong(), in.readInt());
            long monotonic = in.readLong();
            int payloadLength = in.readInt();
            if (payloadLength < 1 || payloadLength > RawCaptureFormat.MAX_PAYLOAD_BYTES) throw new IOException("Invalid raw payload length: " + payloadLength);
            byte[] payload = in.readNBytes(payloadLength);
            if (payload.length != payloadLength || in.available() != 0) throw new IOException("Truncated or trailing bytes in raw capture record");
            return new RawMarketMessage(version, source, connection, subscription, sequence, received, monotonic, payload);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid raw capture record: " + invalid.getMessage(), invalid);
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 1 || size > 65_536) throw new IOException("Invalid capture metadata string length");
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new EOFException("Truncated capture metadata string");
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] readExactly(InputStream input, int size, String message) throws IOException {
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new EOFException(message);
        return bytes;
    }
}
