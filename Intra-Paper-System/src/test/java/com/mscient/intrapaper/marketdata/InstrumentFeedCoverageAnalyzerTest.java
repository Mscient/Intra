package com.mscient.intrapaper.marketdata;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/** Focused checks for joining expected instrument subscriptions to lifecycle and capture evidence. */
public final class InstrumentFeedCoverageAnalyzerTest {
    private InstrumentFeedCoverageAnalyzerTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("instrument-feed-coverage-check-");
        try {
            Instant start = Instant.parse("2026-11-05T04:00:00Z");
            Path planPath = root.resolve("subscription-plan.csv");
            String planCsv = String.join(",", InstrumentSubscriptionPlan.HEADERS) + "\n"
                    + "w1,feed,conn-1,sub-1,FUT-A," + start + "," + start.plusSeconds(60) + "\n";
            Files.writeString(planPath, planCsv, StandardCharsets.UTF_8);
            InstrumentSubscriptionPlan plan = InstrumentSubscriptionPlan.load(planPath);

            Path lifecycle = root.resolve("feed.flj");
            try (FeedLifecycleJournalWriter writer = FeedLifecycleJournalWriter.create(lifecycle)) {
                appendLifecycle(writer, FeedLifecycleEvent.Type.CONNECTION_REQUESTED, Optional.empty(), start);
                appendLifecycle(writer, FeedLifecycleEvent.Type.CONNECTION_ESTABLISHED, Optional.empty(), start.plusSeconds(1));
                appendLifecycle(writer, FeedLifecycleEvent.Type.AUTHENTICATED, Optional.empty(), start.plusSeconds(2));
                appendLifecycle(writer, FeedLifecycleEvent.Type.SUBSCRIPTION_REQUESTED, Optional.of("sub-1"), start.plusSeconds(3));
                appendLifecycle(writer, FeedLifecycleEvent.Type.SUBSCRIPTION_ACKNOWLEDGED, Optional.of("sub-1"), start.plusSeconds(10));
                appendLifecycle(writer, FeedLifecycleEvent.Type.UNSUBSCRIPTION_REQUESTED, Optional.of("sub-1"), start.plusSeconds(39));
                appendLifecycle(writer, FeedLifecycleEvent.Type.UNSUBSCRIPTION_ACKNOWLEDGED, Optional.of("sub-1"), start.plusSeconds(40));
            }

            Path raw = root.resolve("capture.raw");
            try (RawCaptureJournalWriter writer = RawCaptureJournalWriter.create(raw)) {
                appendRaw(writer, "FUT-A", start.plusSeconds(20), 1);
                appendRaw(writer, "OTHER", start.plusSeconds(25), 2);
                appendRaw(writer, "heartbeat", start.plusSeconds(26), 3);
                appendRaw(writer, "FUT-A", start.plusSeconds(40), 4);
                appendRaw(writer, "FUT-A", start.plusSeconds(45), 5);
                appendRaw(writer, "FUT-A", start.plusSeconds(70), 6);
            }

            RawQuoteDecoder decoder = message -> {
                String instrument = new String(message.payload(), StandardCharsets.UTF_8);
                if (instrument.equals("heartbeat")) return Optional.empty();
                return Optional.of(new MarketQuote(1, 1, message.source(), instrument, Optional.empty(),
                        message.receivedAtUtc(), message.receivedAtMonotonicNanos(), 100, 1, 101, 1));
            };
            var report = InstrumentFeedCoverageAnalyzer.analyze(lifecycle, raw, plan, decoder, Duration.ZERO);
            require(report.rawCapture().eventCount() == 6, "raw event count");
            require(report.nonQuoteMessages() == 1 && report.unplannedDecodedQuotes() == 1
                    && report.decodedQuotesOutsidePlannedWindows() == 1, "unplanned and non-quote classifications");
            require(report.windows().size() == 1, "expected window count");
            var window = report.windows().getFirst();
            require(window.acknowledgedIntervalsOverlapping() == 1, "acknowledged interval overlap");
            require(window.acknowledgedDurationMillis() == 30_000 && window.unacknowledgedDurationMillis() == 30_000,
                    "acknowledged and unacknowledged durations");
            require(window.quoteCount() == 3 && window.quotesWithinAcknowledgedInterval() == 1
                    && window.quotesOutsideAcknowledgedInterval() == 2, "half-open acknowledgement boundary and quote classification");
            require(window.quotesWithUncertainLifecycle() == 0 && !window.lifecycleTimelineUncertain(),
                    "certain lifecycle timeline");

            Path noAck = root.resolve("no-ack.flj");
            try (FeedLifecycleJournalWriter writer = FeedLifecycleJournalWriter.create(noAck)) {
                appendLifecycle(writer, FeedLifecycleEvent.Type.CONNECTION_REQUESTED, Optional.empty(), start);
            }
            var noAckReport = InstrumentFeedCoverageAnalyzer.analyze(noAck, raw, plan, decoder, Duration.ZERO);
            require(noAckReport.windows().getFirst().acknowledgedDurationMillis() == 0
                    && noAckReport.windows().getFirst().unacknowledgedDurationMillis() == 60_000,
                    "missing acknowledgement covers no planned duration");

            Path regressed = root.resolve("regressed.flj");
            try (FeedLifecycleJournalWriter writer = FeedLifecycleJournalWriter.create(regressed)) {
                appendLifecycle(writer, FeedLifecycleEvent.Type.CONNECTION_REQUESTED, Optional.empty(), start.plusSeconds(2));
                appendLifecycle(writer, FeedLifecycleEvent.Type.CONNECTION_ESTABLISHED, Optional.empty(), start.plusSeconds(1));
                appendLifecycle(writer, FeedLifecycleEvent.Type.AUTHENTICATED, Optional.empty(), start.plusSeconds(3));
                appendLifecycle(writer, FeedLifecycleEvent.Type.SUBSCRIPTION_REQUESTED, Optional.of("sub-1"), start.plusSeconds(4));
                appendLifecycle(writer, FeedLifecycleEvent.Type.SUBSCRIPTION_ACKNOWLEDGED, Optional.of("sub-1"), start.plusSeconds(5));
            }
            var regressedReport = InstrumentFeedCoverageAnalyzer.analyze(regressed, raw, plan, decoder, Duration.ZERO);
            require(regressedReport.windows().getFirst().lifecycleTimelineUncertain()
                    && regressedReport.windows().getFirst().quotesWithUncertainLifecycle() == 3,
                    "non-monotonic wall-clock timeline is marked uncertain for matching quotes");
            System.out.println("InstrumentFeedCoverageAnalyzer focused checks passed.");
        } finally {
            Files.deleteIfExists(root.resolve("capture.raw"));
            Files.deleteIfExists(root.resolve("feed.flj"));
            Files.deleteIfExists(root.resolve("no-ack.flj"));
            Files.deleteIfExists(root.resolve("regressed.flj"));
            Files.deleteIfExists(root.resolve("subscription-plan.csv"));
            Files.deleteIfExists(root);
        }
    }

    private static void appendLifecycle(FeedLifecycleJournalWriter writer, FeedLifecycleEvent.Type type,
            Optional<String> subscription, Instant at) throws Exception {
        writer.append(new FeedLifecycleEvent(1, "feed", "conn-1", type, subscription, at,
                at.toEpochMilli() * 1_000_000L, Optional.empty()));
    }

    private static void appendRaw(RawCaptureJournalWriter writer, String payload, Instant at, long nanos)
            throws Exception {
        writer.append(new RawMarketMessage(1, "feed", "conn-1", "sub-1", OptionalLong.empty(), at, nanos,
                payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
