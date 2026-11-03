package com.mscient.intrapaper.marketdata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Creates a new journal and appends normalized quotes; existing files are never overwritten. */
public final class QuoteJournalWriter implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private byte[] previousHash = QuoteHashChain.genesisHash();
    private long lastSequenceNumber;
    private boolean failed;
    private boolean closed;

    private QuoteJournalWriter(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static QuoteJournalWriter create(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.lock();
            writeFully(channel, ByteBuffer.wrap(QuoteJournalFormat.header()));
            channel.force(true);
            return new QuoteJournalWriter(channel, lock);
        } catch (IOException failure) {
            channel.close();
            throw failure;
        }
    }

    /**
     * Reopens a journal only after verifying every existing frame and reconstructing its chain state.
     * The exclusive file lock prevents concurrent writers that honor this journal API.
     */
    public static QuoteJournalWriter resume(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.lock();
            QuoteJournalSummary summary = QuoteJournalReader.replay(path, ignored -> { });
            if (summary.bytesRead() != channel.size()) {
                throw new IOException("Quote journal changed while it was being verified");
            }
            channel.position(channel.size());
            QuoteJournalWriter writer = new QuoteJournalWriter(channel, lock);
            writer.previousHash = java.util.HexFormat.of().parseHex(summary.finalHashHex());
            writer.lastSequenceNumber = summary.lastSequenceNumber();
            return writer;
        } catch (IOException | RuntimeException failure) {
            channel.close();
            throw failure;
        }
    }

    public synchronized String append(MarketQuote quote) throws IOException {
        ensureWritable();
        if (quote.sequenceNumber() <= lastSequenceNumber) {
            throw new IllegalArgumentException("Quote sequence numbers must increase strictly");
        }
        byte[] payload = CanonicalQuoteEncoder.encode(quote);
        if (payload.length > QuoteJournalFormat.MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("Encoded quote exceeds journal record limit");
        }
        byte[] nextHash = QuoteHashChain.nextHash(previousHash, payload);
        ByteBuffer frame = ByteBuffer.allocate(Integer.BYTES + payload.length + nextHash.length)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(payload.length)
                .put(payload)
                .put(nextHash);
        frame.flip();
        try {
            writeFully(channel, frame);
            channel.force(true);
        } catch (IOException failure) {
            failed = true;
            throw failure;
        }
        previousHash = nextHash;
        lastSequenceNumber = quote.sequenceNumber();
        return QuoteHashChain.hex(nextHash);
    }

    private void ensureWritable() throws IOException {
        if (closed) {
            throw new IOException("Journal is closed");
        }
        if (failed) {
            throw new IOException("Journal writer failed and cannot continue");
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            if (channel.write(bytes) <= 0) {
                throw new IOException("Journal write made no progress");
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }
}
