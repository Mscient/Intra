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

    /** Diagnostic resume; removal of complete trailing frames requires an independent anchor to detect. */
    public static FeedLifecycleJournalWriter resume(Path path) throws IOException {
        return resumeInternal(path, null);
    }

    /** Operational recovery requires an exact separately retained full-journal summary. */
    public static FeedLifecycleJournalWriter resume(Path path, FeedLifecycleSummary expected) throws IOException {
        return resumeInternal(path, java.util.Objects.requireNonNull(expected, "expected"));
    }

    private static FeedLifecycleJournalWriter resumeInternal(Path path, FeedLifecycleSummary expected) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.lock();
            FeedLifecycleSummary summary = FeedLifecycleJournalReader.replay(path, ignored -> { });
            if (expected != null && !expected.equals(summary)) {
                throw new IOException("Feed lifecycle journal does not match the retained recovery checkpoint");
            }
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
        try {
            if (channel.size() != channel.position()) throw new IOException("Feed lifecycle journal length changed while the writer was active");
        } catch (IOException uncertainty) {
            failed = true;
            throw uncertainty;
        }
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
