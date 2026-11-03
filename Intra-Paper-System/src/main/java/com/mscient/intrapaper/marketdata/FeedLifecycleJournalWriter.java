package com.mscient.intrapaper.marketdata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HexFormat;

/** Append-only, locally persisted connection/subscription lifecycle journal; it creates no connections. */
public final class FeedLifecycleJournalWriter implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private byte[] previousHash = QuoteHashChain.genesisHash();
    private boolean closed;
    private boolean failed;

    private FeedLifecycleJournalWriter(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static FeedLifecycleJournalWriter create(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.lock();
            writeFully(channel, ByteBuffer.wrap(FeedLifecycleFormat.header()));
            channel.force(true);
            return new FeedLifecycleJournalWriter(channel, lock);
        } catch (IOException | RuntimeException failure) {
            try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            try { Files.deleteIfExists(path); } catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    /** Verifies all existing lifecycle events before restoring hash state for append. */
    public static FeedLifecycleJournalWriter resume(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.lock();
            FeedLifecycleSummary summary = FeedLifecycleJournalReader.replay(path, ignored -> { });
            if (summary.bytesRead() != channel.size()) throw new IOException("Feed lifecycle journal changed while it was being verified");
            channel.position(channel.size());
            FeedLifecycleJournalWriter writer = new FeedLifecycleJournalWriter(channel, lock);
            writer.previousHash = HexFormat.of().parseHex(summary.finalHashHex());
            return writer;
        } catch (IOException | RuntimeException failure) {
            try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    public synchronized String append(FeedLifecycleEvent event) throws IOException {
        if (closed || failed) throw new IOException(closed ? "Feed lifecycle journal is closed" : "Feed lifecycle journal failed and cannot continue");
        byte[] encoded = FeedLifecycleEncoder.encode(event);
        if (encoded.length < 1 || encoded.length > FeedLifecycleFormat.MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("Encoded feed lifecycle event exceeds record limit");
        }
        byte[] nextHash = QuoteHashChain.nextHash(previousHash, encoded);
        ByteBuffer frame = ByteBuffer.allocate(Integer.BYTES + encoded.length + nextHash.length)
                .order(ByteOrder.BIG_ENDIAN).putInt(encoded.length).put(encoded).put(nextHash);
        frame.flip();
        try {
            writeFully(channel, frame);
            channel.force(true);
        } catch (IOException failure) {
            failed = true;
            throw failure;
        }
        previousHash = nextHash;
        return QuoteHashChain.hex(nextHash);
    }

    private static void writeFully(FileChannel channel, ByteBuffer data) throws IOException {
        while (data.hasRemaining()) if (channel.write(data) <= 0) throw new IOException("Feed lifecycle journal write made no progress");
    }

    @Override public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            try { lock.release(); } finally { channel.close(); }
        }
    }
}
