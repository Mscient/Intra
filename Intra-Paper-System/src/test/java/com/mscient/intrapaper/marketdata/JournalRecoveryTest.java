package com.mscient.intrapaper.marketdata;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Exact local bytes only. Anchors are synthetic fixtures, not trusted operational storage. */
public final class JournalRecoveryTest {
    private static int checks;
    private static final Instant T = Instant.parse("2030-05-06T03:45:00Z");
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); Files.createDirectory(root);
        raw(root); lifecycle(root);
        System.out.println("JournalRecoveryTest: " + checks + " synthetic anchored-recovery checks passed");
    }
    private static RawMarketMessage message(long seq) {
        return new RawMarketMessage(1,"SYNTHETIC","connection","subscription",OptionalLong.of(seq),
                T.plusMillis(seq),seq,new byte[]{0,(byte)0xff,(byte)seq});
    }
    private static FeedLifecycleEvent event(FeedLifecycleEvent.Type type, long millis) {
        return new FeedLifecycleEvent(1,"SYNTHETIC","connection",type,Optional.empty(),
                T.plusMillis(millis),millis * 1_000_000,Optional.empty());
    }
    private static void raw(Path root) throws Exception {
        Path journal = root.resolve("raw.raw");
        byte[] supplied = message(1).payload();
        try (var writer = RawCaptureJournalWriter.create(journal)) { writer.append(message(1)); }
        var anchor = RawCaptureJournalReader.replay(journal, row -> {
            ok(Arrays.equals(row.payload(),supplied),"exact raw payload survives encoding");
            ok(row.receivedAtUtc().equals(T.plusMillis(1)),"wall clock retained");
            ok(row.receivedAtMonotonicNanos() == 1,"monotonic clock retained");
        });
        byte[] first = Files.readAllBytes(journal);
        try (var writer = RawCaptureJournalWriter.resume(journal,anchor)) { writer.append(message(3)); writer.append(message(3)); }
        var latest = RawCaptureJournalReader.replay(journal, ignored -> {});
        ok(latest.eventCount() == 3 && latest.upstreamSequenceGapCount() == 1
                && latest.nonIncreasingUpstreamSequenceTransitions() == 1,"raw repeats/gaps preserved as diagnostics");
        // Raw captures retain repeated source messages rather than silently deleting evidence.
        Path bad = root.resolve("raw-bad.raw"); byte[] intact = Files.readAllBytes(journal);
        for (int n = 0; n < intact.length; n++) {
            Files.write(bad,Arrays.copyOf(intact,n));
            rejects(() -> { try (var ignored = RawCaptureJournalWriter.resume(bad,latest)) {} },"truncated raw prefix");
        }
        Files.write(bad,first);
        ok(RawCaptureJournalReader.replay(bad,ignored -> {}).equals(anchor),"a complete prefix remains structurally valid");
        rejects(() -> { try (var ignored = RawCaptureJournalWriter.resume(bad,latest)) {} },"whole-frame rollback detected by anchor");
        rejects(() -> { try (var ignored = RawCaptureJournalWriter.resume(journal,anchor)) {} },"stale checkpoint rejects extra tail");
        byte[] changed = intact.clone(); changed[changed.length-1] ^= 1; Files.write(bad,changed);
        rejects(() -> RawCaptureJournalReader.replay(bad,ignored -> {}),"raw hash corruption");
        try (var writer = RawCaptureJournalWriter.resume(journal,latest)) {
            boolean mutatedWhileActive = false;
            try (var channel = FileChannel.open(journal,StandardOpenOption.WRITE)) { channel.truncate(first.length); mutatedWhileActive = true; }
            catch (IOException mandatoryLock) { ok(true,"OS lock prevents raw mutation"); }
            if (mutatedWhileActive) {
                rejects(() -> writer.append(message(4)),"active raw truncation");
                rejects(() -> writer.append(message(4)),"raw writer remains disabled after uncertainty");
            }
        }
        Files.write(journal,intact);
        ok(Arrays.equals(Files.readAllBytes(journal),intact),"rejected raw append does not change recovered bytes");
    }
    private static void lifecycle(Path root) throws Exception {
        Path journal = root.resolve("feed.flj");
        try (var writer = FeedLifecycleJournalWriter.create(journal)) {
            writer.append(event(FeedLifecycleEvent.Type.CONNECTION_REQUESTED,0));
        }
        var anchor = FeedLifecycleJournalReader.replay(journal,ignored -> {}); byte[] first = Files.readAllBytes(journal);
        try (var writer = FeedLifecycleJournalWriter.resume(journal,anchor)) {
            writer.append(event(FeedLifecycleEvent.Type.CONNECTION_ESTABLISHED,1));
            writer.append(event(FeedLifecycleEvent.Type.DISCONNECTED,2));
        }
        var latest = FeedLifecycleJournalReader.replay(journal,ignored -> {}); byte[] intact = Files.readAllBytes(journal);
        ok(latest.eventCount() == 3 && latest.eventCounts().get(FeedLifecycleEvent.Type.DISCONNECTED) == 1,"lifecycle counts retained on restart");
        Path bad = root.resolve("feed-bad.flj");
        for (int n = 0; n < intact.length; n++) {
            Files.write(bad,Arrays.copyOf(intact,n));
            rejects(() -> { try (var ignored = FeedLifecycleJournalWriter.resume(bad,latest)) {} },"truncated lifecycle prefix");
        }
        Files.write(bad,first);
        ok(FeedLifecycleJournalReader.replay(bad,ignored -> {}).equals(anchor),"lifecycle complete prefix is structurally valid");
        rejects(() -> { try (var ignored = FeedLifecycleJournalWriter.resume(bad,latest)) {} },"lifecycle complete-frame rollback");
        rejects(() -> { try (var ignored = FeedLifecycleJournalWriter.resume(journal,anchor)) {} },"lifecycle stale checkpoint");
        byte[] changed = intact.clone(); changed[changed.length-1] ^= 1; Files.write(bad,changed);
        rejects(() -> FeedLifecycleJournalReader.replay(bad,ignored -> {}),"lifecycle hash corruption");
        try (var writer = FeedLifecycleJournalWriter.resume(journal,latest)) {
            boolean mutatedWhileActive = false;
            try { Files.write(journal,new byte[]{1},StandardOpenOption.APPEND); mutatedWhileActive = true; }
            catch (IOException mandatoryLock) { ok(true,"OS lock prevents lifecycle mutation"); }
            if (mutatedWhileActive) {
                rejects(() -> writer.append(event(FeedLifecycleEvent.Type.RECONNECT_SCHEDULED,3)),"active lifecycle changed length");
                rejects(() -> writer.append(event(FeedLifecycleEvent.Type.RECONNECT_SCHEDULED,3)),"lifecycle writer remains disabled");
            }
        }
        Files.write(journal,intact);
        ok(Arrays.equals(Files.readAllBytes(journal),intact),"rejected lifecycle append preserves bytes");
    }
    private static void ok(boolean value,String reason) { checks++; if (!value) throw new AssertionError(reason); }
    private static void rejects(Action action,String reason) throws Exception {
        checks++; try { action.run(); } catch (IOException | IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Expected rejection: " + reason);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
