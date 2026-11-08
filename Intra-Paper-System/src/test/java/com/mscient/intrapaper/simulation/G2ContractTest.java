package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.marketdata.*;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.mscient.intrapaper.provenance.RunConfig.sha256;

/** Synthetic offline boundary/failure fixtures; no source or broker correctness claim. */
public final class G2ContractTest {
    private static int checks;
    private static Path root;
    private static final Instant T = Instant.parse("2030-05-06T03:45:00Z");
    private static final String RUN = "000000000000000000000001";
    private interface Action { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]); Files.createDirectories(root);
        raw(Path.of(args[1]));
        states();
        recovery();
        quotes();
        System.out.println("G2 contracts: " + checks + " checks PASS; SYNTHETIC_OFFLINE_ONLY");
    }
    private static void check(boolean condition, String why) {
        checks++; if (!condition) throw new AssertionError(why);
    }
    private static void reject(Action action, String why) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | java.io.IOException expected) {
            checks++; return;
        }
        throw new AssertionError("Accepted invalid fixture: " + why);
    }
    private static RawMarketMessage message(String source, byte[] payload) {
        return new RawMarketMessage(1, source, "C", "U", OptionalLong.empty(), Instant.EPOCH, 7, payload);
    }
    private static void raw(Path golden) throws Exception {
        byte[] payload = HexFormat.of().parseHex("000aff807f");
        var message = message("S", payload); payload[0] = 99;
        check(message.payload()[0] == 0, "constructor copies exact bytes");
        byte[] returned = message.payload(); returned[0] = 88;
        check(message.payload()[0] == 0, "accessor copies exact bytes");
        Path journal = root.resolve("golden-written.raw");
        try (var w = RawCaptureJournalWriter.create(journal)) { w.append(message); }
        check(Files.mismatch(journal, golden) == -1, "independently laid-out golden framing");
        check(sha256(Files.readAllBytes(journal)).equals("3a968c3d92361a75554a0f927dbd0033f35753b51026cd0ce2fda19721f2197f"), "golden raw bytes hash");
        List<RawMarketMessage> read = new ArrayList<>();
        var summary = RawCaptureJournalReader.replay(journal, read::add);
        check(summary.eventCount() == 1 && summary.upstreamSequencedEventCount() == 0, "unknown source counter remains absent");
        check(Arrays.equals(read.getFirst().payload(), message.payload()), "opaque non-UTF8 payload survives");
        reject(() -> RawCaptureJournalWriter.create(journal), "append-only create");
        byte[] before = Files.readAllBytes(journal);
        reject(() -> message("bad\uD800", new byte[]{1}), "unpaired surrogate");
        reject(() -> message("bad\nsource", new byte[]{1}), "metadata controls");
        reject(() -> message("x".repeat(65537), new byte[]{1}), "metadata byte limit");
        check(message("x".repeat(65536), new byte[]{1}).source().length() == 65536, "metadata equality boundary");
        reject(() -> message("S", new byte[1048577]), "payload size");
        check(message("S", new byte[1048576]).payload().length == 1048576, "payload equality boundary");
        Path bad = root.resolve("tail.raw");
        for (int n = 0; n < before.length; n++) {
            if (n == 12) continue; // A complete header is a valid empty journal, not proof of completeness.
            Files.write(bad, Arrays.copyOf(before, n));
            reject(() -> RawCaptureJournalReader.replay(bad, ignored -> { }), "truncated byte " + n);
            reject(() -> { try (var ignored = RawCaptureJournalWriter.resume(bad)) { } }, "resume damaged byte " + n);
            check(Files.size(bad) == n, "no raw repair");
        }
        Files.write(bad, before); byte[] corrupt = before.clone(); corrupt[30] ^= 1; Files.write(bad, corrupt);
        reject(() -> RawCaptureJournalReader.replay(bad, ignored -> { }), "corruption");
        Path output = root.resolve("must-not-publish.qjr");
        // A good prefix must never be published if the second raw frame fails.
        try (var w = RawCaptureJournalWriter.resume(journal)) { w.append(message); }
        byte[] two = Files.readAllBytes(journal); two[two.length - 1] ^= 1; Files.write(bad, two);
        reject(() -> RawCaptureQuoteImporter.importJournal(bad, output,
                m -> Optional.of(quote(Instant.EPOCH, 10))), "corrupt tail importer");
        check(!Files.exists(output), "failed import publishes no trusted prefix");
        check(Files.mismatch(golden, root.resolve("golden-written.raw")) != -1, "resume appends rather than rewrites");
        Path huge = root.resolve("oversize.raw");
        try (var channel = FileChannel.open(huge, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(256L * 1024 * 1024); channel.write(ByteBuffer.wrap(new byte[]{0}));
        }
        reject(() -> RawCaptureJournalReader.replay(huge, ignored -> { }), "whole-file bound");
        Files.delete(huge);
        reject(() -> quoteWithSource("bad\uD800"), "quote identity Unicode");
        reject(() -> new FeedLifecycleEvent(1, "bad\uD800", "C", FeedLifecycleEvent.Type.HEARTBEAT,
                Optional.empty(), T, 1, Optional.empty()), "lifecycle identity Unicode");
        Path quoteFile = root.resolve("resumed.qjr");
        var q = quote(T, 10);
        try (var w = QuoteJournalWriter.create(quoteFile)) { w.append(q); }
        try (var w = QuoteJournalWriter.resume(quoteFile)) {
            w.append(new MarketQuote(1, 2, q.source(), q.instrumentId(), q.exchangeTimestampUtc(),
                    q.receivedAtUtc(), 8, 100, 10, 101, 10));
        }
        check(QuoteJournalReader.replay(quoteFile, ignored -> { }).eventCount() == 2, "locked-handle quote resume");
        Path lifecycle = root.resolve("resumed.flj");
        var heartbeat = new FeedLifecycleEvent(1, "S", "C", FeedLifecycleEvent.Type.HEARTBEAT,
                Optional.empty(), T, 7, Optional.empty());
        try (var w = FeedLifecycleJournalWriter.create(lifecycle)) { w.append(heartbeat); }
        try (var w = FeedLifecycleJournalWriter.resume(lifecycle)) { w.append(heartbeat); }
        check(FeedLifecycleJournalReader.replay(lifecycle, ignored -> { }).eventCount() == 2, "locked-handle lifecycle resume");
        for (Path path : List.of(quoteFile, lifecycle)) {
            byte[] bytes = Files.readAllBytes(path); bytes[bytes.length - 1] ^= 1; Files.write(path, bytes);
        }
        reject(() -> { try (var ignored = QuoteJournalWriter.resume(quoteFile)) { } }, "damaged quote resume");
        reject(() -> { try (var ignored = FeedLifecycleJournalWriter.resume(lifecycle)) { } }, "damaged lifecycle resume");
    }
    private static void states() throws Exception {
        var path = List.of(OmsOrderState.CREATED, OmsOrderState.PENDING_NEW, OmsOrderState.WORKING,
                OmsOrderState.PARTIALLY_FILLED, OmsOrderState.PENDING_REPLACE, OmsOrderState.UNKNOWN,
                OmsOrderState.WORKING, OmsOrderState.PENDING_CANCEL, OmsOrderState.PARTIALLY_FILLED,
                OmsOrderState.FILLED);
        for (int n = 1; n < path.size(); n++) {
            OmsOrderStateMachine.requireTransition(path.get(n - 1), path.get(n)); checks++;
        }
        for (var state : List.of(OmsOrderState.CREATED, OmsOrderState.PENDING_NEW, OmsOrderState.WORKING,
                OmsOrderState.PENDING_CANCEL, OmsOrderState.PENDING_REPLACE, OmsOrderState.UNKNOWN)) {
            check(OmsOrderStateMachine.remainingReservationUnits(state, 100, 0) == 100, "missed acknowledgement reserves all " + state);
        }
        for (var state : List.of(OmsOrderState.PARTIALLY_FILLED, OmsOrderState.PENDING_CANCEL,
                OmsOrderState.PENDING_REPLACE, OmsOrderState.UNKNOWN)) {
            check(OmsOrderStateMachine.remainingReservationUnits(state, 150, 50) == 100, "partial/replace worst case " + state);
        }
        for (var terminal : List.of(OmsOrderState.FILLED, OmsOrderState.CANCELED, OmsOrderState.REJECTED, OmsOrderState.EXPIRED)) {
            check(OmsOrderStateMachine.remainingReservationUnits(terminal, 100, terminal == OmsOrderState.FILLED ? 100 : 0) == 0, "terminal releases " + terminal);
            reject(() -> OmsOrderStateMachine.requireTransition(terminal, OmsOrderState.WORKING), "terminal resurrection");
        }
        reject(() -> OmsOrderStateMachine.remainingReservationUnits(OmsOrderState.REJECTED, 100, 50), "filled then rejected");
        reject(() -> OmsOrderStateMachine.remainingReservationUnits(OmsOrderState.CREATED, 100, 1), "execution before submission");
        reject(() -> OmsOrderStateMachine.remainingReservationUnits(OmsOrderState.UNKNOWN, 100, 101), "overfill");
        reject(() -> OmsOrderStateMachine.remainingReservationUnits(OmsOrderState.FILLED, 100, 99), "underfilled terminal");
        reject(() -> OmsOrderStateMachine.remainingReservationUnits(OmsOrderState.PARTIALLY_FILLED, 100, 100), "partial at full boundary");
        var ledger = new ExecutionIdempotencyLedger();
        var execution = new ExecutionEvent("E", "O", T, ExecutionEvent.Side.BUY, 50, new BigDecimal("100.00"));
        check(ledger.apply(execution) == ExecutionIdempotencyLedger.ApplyResult.APPLIED, "first execution");
        check(ledger.apply(new ExecutionEvent("E", "O", T, ExecutionEvent.Side.BUY, 50, new BigDecimal("100")))
                == ExecutionIdempotencyLedger.ApplyResult.DUPLICATE_IDENTICAL, "decimal scale idempotency");
        reject(() -> ledger.apply(new ExecutionEvent("E", "OTHER", T, ExecutionEvent.Side.BUY, 50, new BigDecimal("100"))), "conflicting ID");
        check(ledger.events().size() == 1, "conflict preserves state");
    }
    private static void recovery() throws Exception {
        Path run = root.resolve("recovery").resolve(RUN); Files.createDirectories(run);
        String at = T.toString(), later = T.plusSeconds(1).toString();
        String order = "O," + at + "," + at + "," + later + ",BUY,1,100,MARKET,FILLED,fixture\n";
        String events = at + ",O,CREATED,fixture\n" + at + ",O,WORKING,fixture\n" + later + ",O,FILLED,fixture\n";
        String fill = "E,O," + later + ",BUY,100,100,100,0,0,100\n";
        fixture(run, order, events, fill);
        check(OmsRunRecovery.restore(run).finalPositionUnits() == 100, "golden recovery");
        fixture(run, order, events, fill + fill);
        check(OmsRunRecovery.restore(run).duplicateExecutionEventsIgnored() == 1, "persisted duplicate idempotency");
        fixture(run, order, events, fill.replace(later, T.minusSeconds(1).toString()));
        reject(() -> OmsRunRecovery.restore(run), "fill before creation");
        fixture(run, order, events, fill.replace(later, T.plusSeconds(2).toString()));
        reject(() -> OmsRunRecovery.restore(run), "fill after terminal event");
        fixture(run, order.replace(at + "," + at, at + "," + T.minusSeconds(1)), events, fill);
        reject(() -> OmsRunRecovery.restore(run), "submission before decision");
        fixture(run, order, events.replace(at + ",O,CREATED", T.minusSeconds(1) + ",O,CREATED"), fill);
        reject(() -> OmsRunRecovery.restore(run), "created/decision disagreement");
        fixture(run, order.replace("FILLED", "REJECTED"), events.replace("FILLED", "REJECTED"), fill);
        reject(() -> OmsRunRecovery.restore(run), "execution on rejected order");
        fixture(run, order, events, fill.replace("E,O", "E,MISSING"));
        reject(() -> OmsRunRecovery.restore(run), "unknown order reference");
        fixture(run, order, events, fill.replace(",100,100,100,0,0,100", ",101,100,100,0,0,101"));
        reject(() -> OmsRunRecovery.restore(run), "overfill");
        fixture(run, order, events, fill);
        Files.writeString(run.resolve("fills.csv"), "tampered", StandardOpenOption.APPEND);
        reject(() -> OmsRunRecovery.restore(run), "journal hash disagreement");
    }
    private static void fixture(Path run, String order, String events, String fills) throws Exception {
        Map<String, String> files = new TreeMap<>();
        for (String name : List.of("signals.csv", "fee-breakdowns.csv", "trades.csv", "sessions.csv", "settlements.csv",
                "settlement-charges.csv", "settlement-cost-lines.csv", "evaluation-partitions.csv", "summary.json")) files.put(name, "\n");
        files.put("orders.csv", "order_id,decision_timestamp,submit_timestamp,fill_timestamp,side,target_lots,requested_units,order_type,status,reason\n" + order);
        files.put("order-events.csv", "timestamp,order_id,event,detail\n" + events);
        files.put("fills.csv", "fill_id,order_id,timestamp,side,quantity_units,reference_open_price,fill_price,modeled_slippage_inr,fee_inr,position_units_after\n" + fills);
        files.put("equity.csv", "timestamp,session_date,close,position_units,realized_pnl_inr,unrealized_pnl_inr,fees_paid_inr,net_pnl_inr,risk_state\n" + T + ",2030-05-06,100,100,0,0,0,0,fixture\n");
        files.put("settlements.csv", "instrument_id,settlement_date,settlement_timestamp,settlement_kind,settlement_value,exit_value_per_unit,position_units_settled,gross_pnl_inr,source_reference\n");
        List<String> hashes = new ArrayList<>();
        for (var file : files.entrySet()) {
            Files.writeString(run.resolve(file.getKey()), file.getValue());
            hashes.add("\"" + file.getKey() + "\":\"" + sha256(Files.readAllBytes(run.resolve(file.getKey()))) + "\"");
        }
        Files.writeString(run.resolve("run-manifest.json"), "{\"run_id\":\"" + RUN + "\",\"output_file_sha256\":{" + String.join(",", hashes) + "}}\n");
    }
    private static MarketQuote quote(Instant eventAt, long size) {
        return new MarketQuote(1, 1, "S", "I", Optional.of(eventAt), eventAt, 7, 100, size, 101, size);
    }
    private static MarketQuote quoteWithSource(String source) {
        return new MarketQuote(1, 1, source, "I", Optional.of(T), T, 7, 100, 10, 101, 10);
    }
    private static void quotes() throws Exception {
        var policy = new QuoteQualityPolicy(Duration.ofSeconds(1), Duration.ZERO);
        var q = quote(T, 100);
        check(QuoteExecutionGate.assess(q, "I", T.plusSeconds(1), QuoteExecutionGate.Side.BUY, 100, 1, policy).executable(), "stale/size equality");
        check(!QuoteExecutionGate.assess(q, "I", T.plusSeconds(1).plusNanos(1), QuoteExecutionGate.Side.BUY, 100, 1, policy).executable(), "stale boundary +1ns");
        check(!QuoteExecutionGate.assess(q, "I", T.minusNanos(1), QuoteExecutionGate.Side.BUY, 100, 1, policy).executable(), "future boundary +1ns");
        check(!QuoteExecutionGate.assess(q, "WRONG", T, QuoteExecutionGate.Side.BUY, 100, 1, policy).executable(), "contract mismatch");
        check(!QuoteExecutionGate.assess(q, "I", T, QuoteExecutionGate.Side.BUY, 101, 1, policy).executable(), "insufficient size");
        var intent = new QuoteOrderReplay.Intent("O", "S", "I", T, Duration.ZERO, ExecutionEvent.Side.BUY,
                QuoteOrderReplay.Type.MARKET_IOC, 150, OptionalLong.empty(), 1, new BigDecimal("0.05"));
        var outcome = QuoteOrderReplay.execute(List.of(q), intent, policy);
        check(outcome.filledUnits() == 100 && outcome.remainingUnits() == 50 && outcome.finalState() == OmsOrderState.CANCELED, "snapshot partial/residual cancellation");
        check(QuoteOrderReplay.execute(List.of(), intent, policy).filledUnits() == 0, "missing quote stays missed");
    }
}
