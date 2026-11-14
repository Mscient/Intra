package com.mscient.intrapaper.paper;

import com.mscient.intrapaper.marketdata.*;
import com.mscient.intrapaper.provenance.RunConfig;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/** Paper transaction envelope over the existing forced, hash-chained raw journal. */
final class PaperSessionStore implements AutoCloseable {
    private final Path directory;
    private final String identity;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private RawCaptureJournalWriter writer;
    private final List<RawMarketMessage> restored = new ArrayList<>();
    private String reportHash;

    PaperSessionStore(Path directory, String identity, String initialReport) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.identity = identity;
        Files.createDirectories(this.directory);
        lockChannel = FileChannel.open(this.directory.resolve(".session.lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("Paper session is already in use");
        } catch (IOException | RuntimeException failure) { lockChannel.close(); throw failure; }
        try {
            Path journal = this.directory.resolve("paper.journal");
            Path checkpoint = this.directory.resolve("checkpoint.txt");
            if (Files.exists(this.directory.resolve(".checkpoint.pending")))
                throw new IOException("Ambiguous checkpoint publication; inspect offline before recovery");
            if (Files.exists(checkpoint)) {
                String text = Files.readString(checkpoint, StandardCharsets.UTF_8);
                int boundary = text.lastIndexOf("sha256=");
                if (boundary < 0 || !text.substring(boundary).equals("sha256=" + hash(text.substring(0, boundary)) + "\n"))
                    throw new IOException("Paper checkpoint hash mismatch");
                String[] fields = text.substring(0, boundary).split("\n");
                if (fields.length != 7 || !fields[0].equals("PAPER_CHECKPOINT_V1") || !fields[1].equals(identity))
                    throw new IOException("Paper checkpoint frozen source, code, strategy or configuration mismatch");
                var summary = RawCaptureJournalReader.replay(journal, restored::add);
                if (!fields[2].equals(Long.toString(summary.eventCount()))
                        || !fields[3].equals(Long.toString(summary.bytesRead()))
                        || !fields[4].equals(summary.finalHashHex())
                        || !fields[5].equals(Integer.toString(restored.size() / 2)) || restored.size() % 2 != 0)
                    throw new IOException("Paper journal differs from checkpoint (including ambiguous uncommitted tail)");
                reportHash = fields[6];
                writer = RawCaptureJournalWriter.resume(journal, summary);
            } else {
                if (Files.exists(journal)) throw new IOException("Paper journal exists without a verified checkpoint");
                writer = RawCaptureJournalWriter.create(journal);
                checkpoint(initialReport, 0);
            }
        } catch (IOException | RuntimeException failure) { close(); throw failure; }
    }

    List<RawMarketMessage> restored() { return List.copyOf(restored); }
    void requireReport(String report) throws IOException {
        if (!reportHash.equals(hash(report))) throw new IOException("Reconstructed paper ledger differs from checkpoint");
    }

    void input(PaperEvent event) throws IOException {
        writer.append(message(event, "INPUT", event.toString()));
    }
    void commit(PaperEvent event, String report, int consumed) throws IOException {
        writer.append(message(event, "LEDGER", report));
        checkpoint(report, consumed);
    }
    private void checkpoint(String report, int consumed) throws IOException {
        var summary = writer.verifiedSummary();
        reportHash = hash(report);
        String payload = "PAPER_CHECKPOINT_V1\n" + identity + "\n" + summary.eventCount() + "\n"
                + summary.bytesRead() + "\n" + summary.finalHashHex() + "\n" + consumed + "\n" + reportHash + "\n";
        atomic(directory.resolve("checkpoint.txt"), directory.resolve(".checkpoint.pending"),
                payload + "sha256=" + hash(payload) + "\n");
    }
    void writeReport(String report) throws IOException {
        requireReport(report);
        atomic(directory.resolve("session-report.txt"), directory.resolve(".report.pending"), report);
    }
    void verifyPublishedReport(String report) throws IOException {
        requireReport(report);
        if (!Files.readString(directory.resolve("session-report.txt"), StandardCharsets.UTF_8).equals(report))
            throw new IOException("Published paper report differs from verified journal ledger");
    }
    static RawMarketMessage message(PaperEvent e, String kind, String payload) {
        return new RawMarketMessage(1, "PAPER_LOCAL", "SESSION", kind, OptionalLong.of(e.sequence()),
                e.receivedAt(), e.receiveNanos(), payload.getBytes(StandardCharsets.UTF_8));
    }
    static String hash(String text) { return RunConfig.sha256(text.getBytes(StandardCharsets.UTF_8)); }
    static void atomic(Path target, Path temporary, String text) throws IOException {
        try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
            while (bytes.hasRemaining()) out.write(bytes);
            out.force(true);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    @Override public void close() throws IOException {
        try { if (writer != null) writer.close(); }
        finally { try { if (lock.isValid()) lock.release(); } finally { lockChannel.close(); } }
    }
}
