package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.marketdata.*;
import com.mscient.intrapaper.provenance.BuildInfo;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Test-only synthetic capture generator/decoder, never a vendor adapter. */
public final class G2FrozenFixture {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("decode")) {
            RawCaptureQuoteImporter.importJournal(Path.of(args[1]), Path.of(args[2]), message -> {
                try { return Optional.of(CanonicalQuoteDecoder.decode(message.payload())); }
                catch (java.io.IOException invalid) { throw new UncheckedIOException(invalid); }
            });
            return;
        }
        Path examples = Path.of(args[1]), out = Path.of(args[2]); Files.createDirectory(out);
        for (String name : List.of("synthetic-futures-bars.csv", "synthetic-instrument-master.csv",
                "synthetic-session-calendar.csv", "synthetic-market-rule-regimes.csv", "synthetic-cost-schedule.csv")) {
            Files.copy(examples.resolve(name), out.resolve(name));
        }
        String base = Files.readString(examples.resolve("synthetic-run.properties"))
                .replace("\r\n", "\n").replace("run.commit_id=synthetic-fixture-v1", "run.commit_id=BUILD_COMMIT");
        Files.writeString(out.resolve("bar.properties"), base);
        for (String model : List.of("L1_FULL_SIZE_IOC", "L1_PARTIAL_IOC")) {
            Files.writeString(out.resolve(model + ".properties"), base + "\nexecution.model=" + model
                    + "\nexecution.quote_journal_file=quotes.qjr\nexecution.quote_source_name=SYNTHETIC_FIXTURE"
                    + "\nexecution.decision_to_arrival_latency_ms=0\nexecution.quote_maximum_age_ms=1000"
                    + "\nexecution.quote_maximum_future_skew_ms=0\n");
        }
        var rows = CsvTable.read(out.resolve("synthetic-futures-bars.csv")).rows();
        Instant first = OffsetDateTime.parse(rows.getFirst().get("timestamp")).toInstant();
        try (var raw = RawCaptureJournalWriter.create(out.resolve("capture.raw"));
             var life = FeedLifecycleJournalWriter.create(out.resolve("capture.flj"))) {
            int step = 0;
            for (var type : List.of(FeedLifecycleEvent.Type.CONNECTION_REQUESTED, FeedLifecycleEvent.Type.CONNECTION_ESTABLISHED,
                    FeedLifecycleEvent.Type.AUTHENTICATED, FeedLifecycleEvent.Type.SUBSCRIPTION_REQUESTED,
                    FeedLifecycleEvent.Type.SUBSCRIPTION_ACKNOWLEDGED)) {
                boolean subscription = type == FeedLifecycleEvent.Type.SUBSCRIPTION_REQUESTED || type == FeedLifecycleEvent.Type.SUBSCRIPTION_ACKNOWLEDGED;
                life.append(new FeedLifecycleEvent(1, "SYNTHETIC_FIXTURE", "capture-C", type,
                        subscription ? Optional.of("capture-U") : Optional.empty(), first.minusSeconds(5 - step), step++, Optional.empty()));
            }
            long seq = 0;
            for (var row : rows) {
                Instant at = OffsetDateTime.parse(row.get("timestamp")).toInstant();
                long openTicks = new BigDecimal(row.get("open")).divide(new BigDecimal(row.get("tick_size"))).longValueExact();
                long monotonic = ++seq * 60_000_000_000L;
                var quote = new MarketQuote(1, seq, "SYNTHETIC_FIXTURE", row.get("instrument_id"), Optional.of(at), at,
                        monotonic, openTicks - 1, 1, openTicks + 1, 1);
                raw.append(new RawMarketMessage(1, quote.source(), "capture-C", "capture-U", OptionalLong.of(seq), at,
                        monotonic, CanonicalQuoteEncoder.encode(quote)));
                life.append(new FeedLifecycleEvent(1, quote.source(), "capture-C", FeedLifecycleEvent.Type.HEARTBEAT,
                        Optional.empty(), at, monotonic, Optional.empty()));
            }
        }
        Files.writeString(out.resolve("capture-contract.txt"), "SYNTHETIC_OFFLINE_ONLY\ncapture_id=G2-FROZEN-v1\nprocess_epoch=fixture-epoch-1\n"
                + "decoder=G2FrozenFixture/CANONICAL_QUOTE_V1\nnormalization_revision=1\nchangelog=initial synthetic projection\n"
                + "event_time=synthetic bar-open instant\nmonotonic=synthetic same-epoch counter, not host nanoTime\n"
                + "source_sequence=synthetic contiguous counter; no vendor guarantee\nbuild_commit=" + BuildInfo.commitId() + "\n"
                + "source_authenticity=NOT_APPLICABLE_SYNTHETIC\nrights=SYNTHETIC_AUTHORING_ONLY\n");
    }
}
