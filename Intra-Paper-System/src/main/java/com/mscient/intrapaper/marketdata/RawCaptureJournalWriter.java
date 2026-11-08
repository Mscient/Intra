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

/** Local-only append writer for exact inbound payload bytes; it has no network/feed adapter. */
public final class RawCaptureJournalWriter implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private byte[] previousHash = QuoteHashChain.genesisHash();
    private boolean closed;
    private boolean failed;

    private RawCaptureJournalWriter(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }

    public static RawCaptureJournalWriter create(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.lock();
            writeFully(channel, ByteBuffer.wrap(RawCaptureFormat.header()));
            channel.force(true);
            return new RawCaptureJournalWriter(channel, lock);
        } catch (IOException | RuntimeException failure) {
            try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            try { Files.deleteIfExists(path); } catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    /** Verifies the complete existing capture before restoring its hash state and resuming append. */
    public static RawCaptureJournalWriter resume(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.lock();
            RawCaptureSummary summary = RawCaptureJournalReader.replay(channel, ignored -> { });
            if (summary.bytesRead() != channel.size()) throw new IOException("Raw capture journal changed while it was being verified");
            channel.position(channel.size());
            RawCaptureJournalWriter writer = new RawCaptureJournalWriter(channel, lock);
            writer.previousHash = HexFormat.of().parseHex(summary.finalHashHex());
            return writer;
        } catch (IOException | RuntimeException failure) {
            try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    public synchronized String append(RawMarketMessage message) throws IOException {
        if (closed || failed) throw new IOException(closed ? "Raw capture journal is closed" : "Raw capture journal failed and cannot continue");
        byte[] payload = RawCaptureEncoder.encode(message);
        if (payload.length > RawCaptureFormat.MAX_PAYLOAD_BYTES + 65_536) throw new IllegalArgumentException("Encoded capture record exceeds limit");
        if (channel.size() > RawCaptureFormat.MAX_JOURNAL_BYTES - Integer.BYTES - payload.length - RawCaptureFormat.HASH_BYTES) {
            throw new IOException("Raw capture journal exceeds 256 MiB; rotate to a new caller-identified capture part");
        }
        byte[] nextHash = QuoteHashChain.nextHash(previousHash, payload);
        ByteBuffer frame = ByteBuffer.allocate(Integer.BYTES + payload.length + nextHash.length)
                .order(ByteOrder.BIG_ENDIAN).putInt(payload.length).put(payload).put(nextHash);
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
        while (data.hasRemaining()) if (channel.write(data) <= 0) throw new IOException("Raw journal write made no progress");
    }

    @Override public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            try { lock.release(); } finally { channel.close(); }
        }
    }
}
