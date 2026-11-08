package com.mscient.intrapaper.marketdata;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/** Scans and verifies each journal frame before passing that quote to a replay consumer. */
public final class QuoteJournalReader {
    private QuoteJournalReader() {}

    /**
     * Replays in sequence order and validates framing, schema, sequence, and hash chain.
     * If the method throws, the caller must discard any state accumulated by the consumer.
     */
    public static QuoteJournalSummary replay(Path path, Consumer<MarketQuote> consumer) throws IOException {
        Objects.requireNonNull(consumer, "consumer");
        try (InputStream raw = Files.newInputStream(path);
             BufferedInputStream buffered = new BufferedInputStream(raw)) {
            return replay(buffered, consumer);
        }
    }

    /** Uses the writer's locked handle without closing its channel. */
    static QuoteJournalSummary replay(FileChannel channel, Consumer<MarketQuote> consumer) throws IOException {
        channel.position(0);
        return replay(new BufferedInputStream(Channels.newInputStream(channel)), consumer);
    }

    private static QuoteJournalSummary replay(InputStream buffered, Consumer<MarketQuote> consumer) throws IOException {
            long bytesRead = readAndVerifyHeader(buffered);
            byte[] previousHash = QuoteHashChain.genesisHash();
            long eventCount = 0;
            long lastSequenceNumber = 0;
            long sequenceGapCount = 0;
            long missingSequenceNumbers = 0;

            while (true) {
                int first = buffered.read();
                if (first == -1) {
                    break;
                }
                byte[] otherLengthBytes = readExactly(buffered, Integer.BYTES - 1, "truncated record length");
                ByteBuffer lengthBuffer = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN);
                lengthBuffer.put((byte) first).put(otherLengthBytes).flip();
                int recordLength = lengthBuffer.getInt();
                if (recordLength < 1 || recordLength > QuoteJournalFormat.MAX_RECORD_BYTES) {
                    throw new IOException("Invalid journal record length: " + recordLength);
                }
                byte[] payload = readExactly(buffered, recordLength, "truncated quote payload");
                byte[] storedHash = readExactly(buffered, QuoteJournalFormat.HASH_BYTES, "truncated record hash");
                bytesRead += Integer.BYTES + recordLength + QuoteJournalFormat.HASH_BYTES;

                MarketQuote quote = CanonicalQuoteDecoder.decode(payload);
                if (!Arrays.equals(payload, CanonicalQuoteEncoder.encode(quote))) {
                    throw new IOException("Journal quote is not canonically encoded");
                }
                if (quote.sequenceNumber() <= lastSequenceNumber) {
                    throw new IOException("Journal sequence numbers are not strictly increasing");
                }
                if (eventCount > 0 && quote.sequenceNumber() - lastSequenceNumber > 1) {
                    sequenceGapCount++;
                    missingSequenceNumbers = Math.addExact(missingSequenceNumbers,
                            quote.sequenceNumber() - lastSequenceNumber - 1);
                }
                byte[] expectedHash = QuoteHashChain.nextHash(previousHash, payload);
                if (!MessageDigest.isEqual(expectedHash, storedHash)) {
                    throw new IOException("Journal hash-chain mismatch at sequence " + quote.sequenceNumber());
                }
                consumer.accept(quote);
                previousHash = expectedHash;
                lastSequenceNumber = quote.sequenceNumber();
                eventCount++;
            }
            return new QuoteJournalSummary(eventCount, lastSequenceNumber, sequenceGapCount,
                    missingSequenceNumbers, QuoteHashChain.hex(previousHash), bytesRead);
    }

    private static long readAndVerifyHeader(InputStream input) throws IOException {
        byte[] expected = QuoteJournalFormat.header();
        byte[] actual = readExactly(input, expected.length, "truncated journal header");
        if (!Arrays.equals(expected, actual)) {
            throw new IOException("Unsupported or invalid quote journal header");
        }
        return expected.length;
    }

    private static byte[] readExactly(InputStream input, int length, String failureMessage) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException(failureMessage);
        }
        return bytes;
    }
}
