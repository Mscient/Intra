package com.mscient.intrapaper.paper;

import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.data.IntervalSupport;
import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.reference.TimestampRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Frozen local source. No network, account, authentication, or broker-order operations. */
public record LocalMarketDataProvider(List<PaperEvent> events) implements MarketDataProvider {
    public LocalMarketDataProvider { events = List.copyOf(events); }

    public static LocalMarketDataProvider recorded(ValidatedDataset data) {
        List<PaperEvent> events = new ArrayList<>();
        data.quoteHistory().ifPresent(history -> history.quotes().forEach(q -> events.add(new PaperEvent(1,
                PaperEvent.Kind.QUOTE, q.exchangeTimestampUtc().orElse(q.receivedAtUtc()), q.receivedAtUtc(),
                q.receivedAtMonotonicNanos(), Optional.empty(), Optional.of(q)))));
        var interval = IntervalSupport.duration(data.bars().getFirst().interval());
        for (var bar : data.bars()) {
            Instant start = bar.timestampRole() == TimestampRole.BAR_START ? bar.timestamp() : bar.timestamp().minus(interval);
            Instant close = start.plus(interval);
            events.add(new PaperEvent(1, PaperEvent.Kind.OPEN, start, start, 0, Optional.of(opening(bar)), Optional.empty()));
            events.add(new PaperEvent(1, PaperEvent.Kind.BAR, close, close, 0, Optional.of(bar), Optional.empty()));
        }
        // Received quotes, prior completed bars, then new opens. Input order breaks remaining ties.
        events.sort(Comparator.comparing(PaperEvent::receivedAt)
                .thenComparingInt(e -> switch (e.kind()) { case QUOTE -> 0; case BAR -> 1; default -> 2; })
                .thenComparingLong(e -> e.quote().map(q -> q.sequenceNumber()).orElse(0L)));
        List<PaperEvent> sequenced = new ArrayList<>();
        long sequence = 0, nanos = Long.MIN_VALUE;
        for (var e : events) {
            nanos = Math.max(nanos, e.receiveNanos());
            sequenced.add(new PaperEvent(++sequence, e.kind(), e.sourceTime(), e.receivedAt(),
                    e.kind() == PaperEvent.Kind.QUOTE ? e.receiveNanos() : nanos, e.bar(), e.quote()));
        }
        Instant end = sequenced.getLast().receivedAt();
        sequenced.add(PaperEvent.control(++sequence, PaperEvent.Kind.SESSION_CLOSE, end, nanos));
        return new LocalMarketDataProvider(sequenced);
    }

    /** Strip all future OHLC/volume information from a recorded opening event. */
    public static Bar opening(Bar b) {
        return new Bar(b.timestamp(), b.sessionDate(), b.instrumentId(), b.exchange(), b.segment(), b.sourceToken(),
                b.tradingSymbol(), b.contractKind(), b.underlying(), b.expiry(), b.strike(), b.optionRight(),
                b.lotSize(), b.tickSize(), b.interval(), b.timestampRole(), b.open(), b.open(), b.open(), b.open(),
                null, null, b.source(), com.mscient.intrapaper.provenance.RunConfig.sha256(
                        (b.instrumentId() + "|" + b.timestamp() + "|" + b.open()).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
