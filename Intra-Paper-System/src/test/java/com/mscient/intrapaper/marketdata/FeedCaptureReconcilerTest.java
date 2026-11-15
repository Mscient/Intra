package com.mscient.intrapaper.marketdata;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

import static com.mscient.intrapaper.marketdata.FeedCaptureReconciliationReport.MessageDisposition.*;
import static com.mscient.intrapaper.marketdata.FeedLifecycleEvent.Type.*;

/** Synthetic, local journals only: matching capture metadata never establishes feed completeness. */
public final class FeedCaptureReconcilerTest {
    private static final Instant T = Instant.parse("2030-05-06T03:45:00Z");
    private static final String SOURCE = "SYNTHETIC";
    private static final Duration SKEW = Duration.ofMillis(250);
    private static int checks;

    private FeedCaptureReconcilerTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Files.createDirectory(root);
        boundaries(root);
        identityIsolation(root);
        toleranceOverlap(root);
        regressedOverlap(root);
        duplicateAcknowledgement(root);
        emptyWindows(root);
        System.out.println("FeedCaptureReconcilerTest: " + checks + " synthetic reconciliation checks passed");
    }

    private static void boundaries(Path root) throws Exception {
        for (var close : new FeedLifecycleEvent.Type[]{UNSUBSCRIPTION_ACKNOWLEDGED, DISCONNECTED, CONNECTION_FAILED}) {
            Fixture fixture = new Fixture(root, "boundaries-" + close);
            try (var lifecycle = fixture.lifecycle()) {
                lifecycle.connect("conn-1");
                lifecycle.subscribe("conn-1", "sub-1", T.plusSeconds(10));
                if (close == UNSUBSCRIPTION_ACKNOWLEDGED) {
                    lifecycle.unsubscribe("conn-1", "sub-1", T.plusSeconds(20));
                } else lifecycle.event(close, "conn-1", null, T.plusSeconds(20));
            }
            Instant start = T.plusSeconds(10), end = T.plusSeconds(20);
            // Raw reconciliation includes both endpoints (unlike planned instrument coverage).
            Instant[] times = {start.minus(SKEW).minusNanos(1), start.minus(SKEW), start.minusNanos(1),
                    start, end, end.plusNanos(1), end.plus(SKEW), end.plus(SKEW).plusNanos(1)};
            var messages = new ArrayList<RawMarketMessage>();
            for (int i = 0; i < times.length; i++) {
                var message = message(SOURCE, "conn-1", "sub-1", times[i]);
                messages.add(message);
                Path raw = fixture.raw("edge-" + i, message);
                boolean atBoundary = i == 3 || i == 4;
                boolean withinSkew = i >= 1 && i <= 6;
                counts(reconcile(fixture, raw, Duration.ZERO), atBoundary ? 1 : 0, 0, atBoundary ? 0 : 1, 0);
                counts(reconcile(fixture, raw, SKEW), withinSkew ? 1 : 0, 0, withinSkew ? 0 : 1, 0);
            }
            Path raw = fixture.raw("all-edges", messages.toArray(RawMarketMessage[]::new));
            for (Duration tolerance : new Duration[]{Duration.ZERO, SKEW}) {
                var report = reconcile(fixture, raw, tolerance);
                counts(report, tolerance.isZero() ? 2 : 6, 0, tolerance.isZero() ? 6 : 2, 0);
                boolean unsubscribe = close == UNSUBSCRIPTION_ACKNOWLEDGED;
                intervals(report, 1, unsubscribe ? 1 : 0, unsubscribe ? 0 : 1, 0, 0, 1);
                anomalies(report, 0, 0, 0);
                deterministicCli(fixture, raw, report);
            }
        }
    }

    private static void identityIsolation(Path root) throws Exception {
        Fixture fixture = new Fixture(root, "identity-isolation");
        try (var lifecycle = fixture.lifecycle()) {
            lifecycle.connect("conn-1");
            lifecycle.connect("conn-2");
            lifecycle.subscribe("conn-1", "sub-1", T.plusSeconds(10));
            lifecycle.subscribe("conn-2", "sub-2", T.plusSeconds(10));
            lifecycle.unsubscribe("conn-1", "sub-1", T.plusSeconds(20));
            lifecycle.unsubscribe("conn-2", "sub-2", T.plusSeconds(20));
        }
        Instant inside = T.plusSeconds(15);
        // Both IDs exist, but these combinations were never acknowledged. Source alone is insufficient.
        RawMarketMessage[] wrongIds = {message(SOURCE, "conn-2", "sub-1", inside),
                message(SOURCE, "conn-1", "sub-2", inside),
                message("OTHER-SYNTHETIC", "conn-1", "sub-1", inside)};
        for (int i = 0; i < wrongIds.length; i++) {
            var report = reconcile(fixture, fixture.raw("wrong-" + i, wrongIds[i]), SKEW);
            counts(report, 0, 1, 0, 0);
            intervals(report, 2, 2, 0, 0, 0, 0);
        }
        Path wrong = fixture.raw("wrong-only", wrongIds);
        var wrongReport = reconcile(fixture, wrong, SKEW);
        counts(wrongReport, 0, 3, 0, 0);
        intervals(wrongReport, 2, 2, 0, 0, 0, 0);
        anomalies(wrongReport, 0, 0, 0);
        deterministicCli(fixture, wrong, wrongReport);

        var messages = new ArrayList<>(Arrays.asList(wrongIds));
        messages.add(message(SOURCE, "conn-1", "sub-1", inside));
        messages.add(message(SOURCE, "conn-2", "sub-2", inside));
        Path mixed = fixture.raw("mixed", messages.toArray(RawMarketMessage[]::new));
        var report = reconcile(fixture, mixed, SKEW);
        counts(report, 2, 3, 0, 0);
        intervals(report, 2, 2, 0, 0, 0, 2);
        anomalies(report, 0, 0, 0); // Concurrent windows on different keys are not ambiguous.
        deterministicCli(fixture, mixed, report);

        Path allMatched = fixture.raw("all-matched", message(SOURCE, "conn-1", "sub-1", inside),
                message(SOURCE, "conn-2", "sub-2", inside));
        var allMatchedReport = reconcile(fixture, allMatched, SKEW);
        counts(allMatchedReport, 2, 0, 0, 0);
        intervals(allMatchedReport, 2, 2, 0, 0, 0, 2);
        anomalies(allMatchedReport, 0, 0, 0);
        deterministicCli(fixture, allMatched, allMatchedReport);
    }

    private static void toleranceOverlap(Path root) throws Exception {
        Fixture fixture = new Fixture(root, "tolerance-overlap");
        try (var lifecycle = fixture.lifecycle()) {
            lifecycle.connect("conn-1");
            lifecycle.subscribe("conn-1", "sub-1", T.plusSeconds(10));
            lifecycle.unsubscribe("conn-1", "sub-1", T.plusSeconds(20));
            lifecycle.subscribe("conn-1", "sub-1", T.plusMillis(20_400));
            lifecycle.unsubscribe("conn-1", "sub-1", T.plusSeconds(30));
        }
        // With 250 ms skew the two otherwise disjoint windows overlap at [20.150, 20.250].
        Instant lower = T.plusMillis(20_150), upper = T.plusMillis(20_250);
        Instant[] times = {lower.minusNanos(1), lower, T.plusMillis(20_200), upper, upper.plusNanos(1)};
        var messages = new ArrayList<RawMarketMessage>();
        for (int i = 0; i < times.length; i++) {
            var message = message(SOURCE, "conn-1", "sub-1", times[i]);
            messages.add(message);
            var report = reconcile(fixture, fixture.raw("overlap-" + i, message), SKEW);
            boolean ambiguous = i >= 1 && i <= 3;
            counts(report, ambiguous ? 0 : 1, 0, 0, ambiguous ? 1 : 0);
            intervals(report, 2, 2, 0, 0, 0, ambiguous ? 2 : 1);
            anomalies(report, 0, 0, 0);
        }
        Path raw = fixture.raw("overlap", messages.toArray(RawMarketMessage[]::new));
        var tolerant = reconcile(fixture, raw, SKEW);
        counts(tolerant, 2, 0, 0, 3);
        intervals(tolerant, 2, 2, 0, 0, 0, 2);
        anomalies(tolerant, 0, 0, 0);
        deterministicCli(fixture, raw, tolerant);
        var exact = reconcile(fixture, raw, Duration.ZERO);
        counts(exact, 0, 0, 5, 0);
        intervals(exact, 2, 2, 0, 0, 0, 0);
        anomalies(exact, 0, 0, 0);
        deterministicCli(fixture, raw, exact);
    }

    private static void regressedOverlap(Path root) throws Exception {
        Fixture fixture = new Fixture(root, "regressed-overlap");
        try (var lifecycle = fixture.lifecycle()) {
            lifecycle.connect("conn-1");
            lifecycle.subscribe("conn-1", "sub-1", T.plusSeconds(10));
            lifecycle.unsubscribe("conn-1", "sub-1", T.plusSeconds(20));
            // Append order is preserved while the wall clock regresses into the previous window.
            lifecycle.subscribe("conn-1", "sub-1", T.plusSeconds(15));
            lifecycle.unsubscribe("conn-1", "sub-1", T.plusSeconds(30));
        }
        Path raw = fixture.raw("overlap", message(SOURCE, "conn-1", "sub-1", T.plusSeconds(12)),
                message(SOURCE, "conn-1", "sub-1", T.plusSeconds(17)),
                message(SOURCE, "conn-1", "sub-1", T.plusSeconds(25)));
        var report = reconcile(fixture, raw, Duration.ZERO);
        counts(report, 0, 0, 0, 3);
        intervals(report, 2, 2, 0, 0, 0, 2);
        anomalies(report, 0, 1, 0);
        deterministicCli(fixture, raw, report);
    }

    private static void duplicateAcknowledgement(Path root) throws Exception {
        Fixture fixture = new Fixture(root, "duplicate-acknowledgement");
        try (var lifecycle = fixture.lifecycle()) {
            lifecycle.connect("conn-1");
            lifecycle.subscribe("conn-1", "sub-1", T.plusSeconds(10));
            lifecycle.event(SUBSCRIPTION_ACKNOWLEDGED, "conn-1", "sub-1", T.plusSeconds(15));
            lifecycle.unsubscribe("conn-1", "sub-1", T.plusSeconds(20));
        }
        Path raw = fixture.raw("shared-boundary", message(SOURCE, "conn-1", "sub-1", T.plusSeconds(15)));
        var report = reconcile(fixture, raw, Duration.ZERO);
        counts(report, 0, 0, 0, 1);
        intervals(report, 2, 1, 0, 1, 0, 2);
        anomalies(report, 1, 0, 1);
        deterministicCli(fixture, raw, report);
        Path empty = fixture.raw("empty");
        var emptyReport = reconcile(fixture, empty, Duration.ZERO);
        counts(emptyReport, 0, 0, 0, 0);
        intervals(emptyReport, 2, 1, 0, 1, 0, 0);
        anomalies(emptyReport, 1, 0, 1); // Empty capture must not erase lifecycle ambiguity.
        deterministicCli(fixture, empty, emptyReport);
    }

    private static void emptyWindows(Path root) throws Exception {
        Fixture fixture = new Fixture(root, "empty-windows");
        try (var lifecycle = fixture.lifecycle()) {
            lifecycle.connect("conn-1");
            lifecycle.subscribe("conn-1", "sub-1", T.plusSeconds(10));
            lifecycle.unsubscribe("conn-1", "sub-1", T.plusSeconds(20));
            lifecycle.subscribe("conn-1", "sub-1", T.plusSeconds(30));
            lifecycle.unsubscribe("conn-1", "sub-1", T.plusSeconds(40));
            lifecycle.subscribe("conn-1", "sub-empty", T.plusSeconds(50));
        }
        Path partial = fixture.raw("partial", message(SOURCE, "conn-1", "sub-1", T.plusSeconds(15)));
        var partialReport = reconcile(fixture, partial, SKEW);
        counts(partialReport, 1, 0, 0, 0);
        intervals(partialReport, 3, 2, 0, 0, 1, 1);
        anomalies(partialReport, 0, 0, 0);
        deterministicCli(fixture, partial, partialReport);
        Path empty = fixture.raw("empty"); // A valid header-only raw journal, not a missing/corrupt file.
        var report = reconcile(fixture, empty, SKEW);
        counts(report, 0, 0, 0, 0);
        intervals(report, 3, 2, 0, 0, 1, 0);
        anomalies(report, 0, 0, 0);
        deterministicCli(fixture, empty, report);
    }

    private static FeedCaptureReconciliationReport reconcile(Fixture fixture, Path raw, Duration tolerance)
            throws Exception {
        var report = FeedCaptureReconciler.reconcile(fixture.feed(), raw, tolerance);
        equal(report, FeedCaptureReconciler.reconcile(fixture.feed(), raw, tolerance), "repeatable report: " + raw);
        equal(report.rawCapture(), RawCaptureJournalReader.replay(raw, ignored -> { }), "verified raw identity");
        equal(report.lifecycle().journal(), FeedLifecycleJournalReader.replay(fixture.feed(), ignored -> { }),
                "verified lifecycle identity");
        equal(tolerance, report.allowedWallClockSkew(), "explicit clock tolerance");
        return report;
    }

    private static void counts(FeedCaptureReconciliationReport report, long matched, long noAck,
            long outside, long uncertain) {
        equal(Map.of(MATCHED_ACKNOWLEDGED_INTERVAL, matched, NO_ACKNOWLEDGED_INTERVAL, noAck,
                OUTSIDE_ACKNOWLEDGED_INTERVAL, outside, TIMELINE_UNCERTAIN, uncertain),
                report.messageCounts(), "exclusive message dispositions: " + report);
        equal(matched + noAck + outside + uncertain, report.rawCapture().eventCount(), "each raw message counted once");
    }

    private static void intervals(FeedCaptureReconciliationReport report, long total, long unsubscribe,
            long loss, long anomaly, long open, long withMessages) {
        equal(total, report.acknowledgedIntervalCount(), "acknowledged windows");
        equal(unsubscribe, report.closedByUnsubscriptionCount(), "unsubscription closures");
        equal(loss, report.closedByConnectionLossCount(), "connection-loss closures");
        equal(anomaly, report.closedByTimelineAnomalyCount(), "anomalous closures");
        equal(open, report.stillOpenIntervalCount(), "still-open windows");
        equal(withMessages, report.intervalsWithCapturedMessages(), "windows containing evidence");
    }

    private static void anomalies(FeedCaptureReconciliationReport report, long transitions, long regressions, long timeline) {
        equal(transitions, report.lifecycle().transitionAnomalyCount(), "transition anomalies");
        equal(regressions, report.lifecycleTimestampRegressionCount(), "wall-clock regressions");
        equal(timeline, report.lifecycleTimelineAnomalyCount(), "timeline anomalies");
    }

    private static void deterministicCli(Fixture fixture, Path raw, FeedCaptureReconciliationReport report)
            throws Exception {
        // Pin the entire output, including zero buckets, ordering, hashes and the diagnostic-only label.
        // In particular, even all-matched messages or zero empty intervals must never imply completeness.
        String expected = "FEED/CAPTURE RECONCILIATION (DIAGNOSTIC ONLY): raw_messages=" + report.rawCapture().eventCount()
                + "; acknowledged_intervals=" + report.acknowledgedIntervalCount()
                + "; intervals_with_messages=" + report.intervalsWithCapturedMessages()
                + "; empty_intervals=" + (report.acknowledgedIntervalCount() - report.intervalsWithCapturedMessages())
                + "; closed_by_unsubscribe=" + report.closedByUnsubscriptionCount()
                + "; closed_by_connection_loss=" + report.closedByConnectionLossCount()
                + "; closed_by_timeline_anomaly=" + report.closedByTimelineAnomalyCount()
                + "; still_open_intervals=" + report.stillOpenIntervalCount()
                + "; allowed_wall_clock_skew=" + report.allowedWallClockSkew()
                + "; lifecycle_transition_anomalies=" + report.lifecycle().transitionAnomalyCount()
                + "; lifecycle_timestamp_regressions=" + report.lifecycleTimestampRegressionCount()
                + "; lifecycle_timeline_anomalies=" + report.lifecycleTimelineAnomalyCount()
                + "; raw_final_hash=" + report.rawCapture().finalHashHex()
                + "; lifecycle_final_hash=" + report.lifecycle().journal().finalHashHex() + System.lineSeparator()
                + "MESSAGE MATCHED_ACKNOWLEDGED_INTERVAL: " + report.messageCounts().get(MATCHED_ACKNOWLEDGED_INTERVAL) + System.lineSeparator()
                + "MESSAGE NO_ACKNOWLEDGED_INTERVAL: " + report.messageCounts().get(NO_ACKNOWLEDGED_INTERVAL) + System.lineSeparator()
                + "MESSAGE OUTSIDE_ACKNOWLEDGED_INTERVAL: " + report.messageCounts().get(OUTSIDE_ACKNOWLEDGED_INTERVAL) + System.lineSeparator()
                + "MESSAGE TIMELINE_UNCERTAIN: " + report.messageCounts().get(TIMELINE_UNCERTAIN) + System.lineSeparator();
        byte[] first = cli(fixture, raw, report.allowedWallClockSkew(), "first");
        byte[] second = cli(fixture, raw, report.allowedWallClockSkew(), "second");
        equal(expected, new String(first, StandardCharsets.UTF_8), "complete diagnostic CLI report: " + raw);
        equal(true, Arrays.equals(first, second), "byte-identical output across fresh JVMs: " + raw);
    }

    private static byte[] cli(Fixture fixture, Path raw, Duration tolerance, String run) throws Exception {
        Path output = fixture.root().resolve(raw.getFileName() + "-" + tolerance.toMillis() + "-" + run + ".log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-cp", System.getProperty("java.class.path"), "com.mscient.intrapaper.cli.IntraPaperCli",
                "feed-capture-reconcile", fixture.feed().toString(), raw.toString(), Long.toString(tolerance.toMillis()))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) throw new AssertionError("Reconciliation CLI timed out: " + raw);
            byte[] bytes = Files.readAllBytes(output);
            equal(0, process.exitValue(), "CLI succeeds: " + new String(bytes, StandardCharsets.UTF_8));
            return bytes;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private record Fixture(Path root) {
        Fixture(Path parent, String name) throws Exception { this(Files.createDirectory(parent.resolve(name))); }
        Path feed() { return root.resolve("feed.flj"); }
        Lifecycle lifecycle() throws Exception { return new Lifecycle(FeedLifecycleJournalWriter.create(feed())); }
        Path raw(String name, RawMarketMessage... messages) throws Exception {
            Path path = root.resolve(name + ".raw");
            try (var writer = RawCaptureJournalWriter.create(path)) {
                for (var message : messages) writer.append(message);
            }
            return path;
        }
    }

    private static final class Lifecycle implements AutoCloseable {
        private final FeedLifecycleJournalWriter writer;
        private long monotonic;
        Lifecycle(FeedLifecycleJournalWriter writer) { this.writer = writer; }
        void event(FeedLifecycleEvent.Type type, String connection, String subscription, Instant at) throws Exception {
            writer.append(new FeedLifecycleEvent(1, SOURCE, connection, type, Optional.ofNullable(subscription),
                    at, ++monotonic, Optional.empty()));
        }
        void connect(String connection) throws Exception {
            event(CONNECTION_REQUESTED, connection, null, T);
            event(CONNECTION_ESTABLISHED, connection, null, T.plusSeconds(1));
            event(AUTHENTICATED, connection, null, T.plusSeconds(2));
        }
        void subscribe(String connection, String subscription, Instant at) throws Exception {
            event(SUBSCRIPTION_REQUESTED, connection, subscription, at.minusNanos(1));
            event(SUBSCRIPTION_ACKNOWLEDGED, connection, subscription, at);
        }
        void unsubscribe(String connection, String subscription, Instant at) throws Exception {
            event(UNSUBSCRIPTION_REQUESTED, connection, subscription, at.minusNanos(1));
            event(UNSUBSCRIPTION_ACKNOWLEDGED, connection, subscription, at);
        }
        @Override public void close() throws Exception { writer.close(); }
    }

    private static RawMarketMessage message(String source, String connection, String subscription, Instant at) {
        return new RawMarketMessage(1, source, connection, subscription, OptionalLong.empty(), at,
                Duration.between(T, at).toNanos(), new byte[]{0, (byte) 0xff, 42});
    }

    private static void equal(Object expected, Object actual, String reason) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(reason + ": expected " + expected + ", got " + actual);
    }
}
