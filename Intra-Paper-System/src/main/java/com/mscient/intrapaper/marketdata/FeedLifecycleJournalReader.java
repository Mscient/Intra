package com.mscient.intrapaper.marketdata;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** Verifies framing, canonical events, and the hash chain before delivering lifecycle records. */
public final class FeedLifecycleJournalReader {
    private record ConnectionKey(String source, String connectionId) { }

    private FeedLifecycleJournalReader() { }

    public static FeedLifecycleSummary replay(Path path, Consumer<FeedLifecycleEvent> consumer) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(consumer, "consumer");
        try (InputStream file = Files.newInputStream(path); BufferedInputStream input = new BufferedInputStream(file)) {
            byte[] header = readExactly(input, FeedLifecycleFormat.header().length, "truncated feed lifecycle journal header");
            if (!Arrays.equals(header, FeedLifecycleFormat.header())) throw new IOException("Unsupported feed lifecycle journal header");
            byte[] previous = QuoteHashChain.genesisHash();
            long count = 0;
            long bytesRead = header.length;
            Set<ConnectionKey> connections = new HashSet<>();
            EnumMap<FeedLifecycleEvent.Type, Long> eventCounts = new EnumMap<>(FeedLifecycleEvent.Type.class);
            for (FeedLifecycleEvent.Type type : FeedLifecycleEvent.Type.values()) eventCounts.put(type, 0L);
            while (true) {
                int first = input.read();
                if (first < 0) break;
                byte[] rest = readExactly(input, Integer.BYTES - 1, "truncated feed lifecycle frame length");
                ByteBuffer frameLength = ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.BIG_ENDIAN)
                        .put((byte) first).put(rest);
                frameLength.flip();
                int length = frameLength.getInt();
                if (length < 1 || length > FeedLifecycleFormat.MAX_RECORD_BYTES) {
                    throw new IOException("Invalid feed lifecycle record length: " + length);
                }
                byte[] encoded = readExactly(input, length, "truncated feed lifecycle record");
                byte[] storedHash = readExactly(input, FeedLifecycleFormat.HASH_BYTES, "truncated feed lifecycle hash");
                byte[] expectedHash = QuoteHashChain.nextHash(previous, encoded);
                if (!MessageDigest.isEqual(expectedHash, storedHash)) {
                    throw new IOException("Feed lifecycle hash-chain mismatch at record " + (count + 1));
                }
                FeedLifecycleEvent event = FeedLifecycleEncoder.decode(encoded);
                if (!Arrays.equals(encoded, FeedLifecycleEncoder.encode(event))) {
                    throw new IOException("Feed lifecycle record is not canonically encoded");
                }
                connections.add(new ConnectionKey(event.source(), event.connectionId()));
                eventCounts.compute(event.type(), (ignored, prior) -> Math.addExact(prior, 1));
                consumer.accept(event);
                previous = expectedHash;
                count = Math.addExact(count, 1);
                bytesRead = Math.addExact(bytesRead, Integer.BYTES + (long) length + FeedLifecycleFormat.HASH_BYTES);
            }
            return new FeedLifecycleSummary(count, bytesRead, QuoteHashChain.hex(previous),
                    connections.size(), eventCounts);
        }
    }

    private static byte[] readExactly(InputStream input, int size, String message) throws IOException {
        byte[] bytes = input.readNBytes(size);
        if (bytes.length != size) throw new EOFException(message);
        return bytes;
    }
}
