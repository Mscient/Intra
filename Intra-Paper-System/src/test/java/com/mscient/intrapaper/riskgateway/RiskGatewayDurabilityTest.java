package com.mscient.intrapaper.riskgateway;

import com.mscient.intrapaper.riskgateway.DurableRiskGateway.RecoveryCheckpoint;
import com.mscient.intrapaper.riskgateway.RiskGateway.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free offline verification. Faults use real local files and a package-private write seam. */
public final class RiskGatewayDurabilityTest {
    private static final Instant T = Instant.parse("2026-11-05T04:00:00Z");
    private static final RiskConfiguration CONFIG = config(10, "100");
    private static Path root;
    private static int files, checks, groups;
    private static final List<String> replayHashes = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 21) throw new AssertionError("JDK 21 required");
        if (args.length != 1) throw new IllegalArgumentException("Supply a fresh fixture directory");
        root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectory(root);
        for (String key : List.of("java.version", "java.runtime.version", "java.vendor", "java.vendor.version",
                "java.vm.name", "java.vm.version", "java.home", "os.name", "os.arch")) {
            System.out.println(key + "=" + System.getProperty(key));
        }
        run("append, restart and reservation lifecycle", RiskGatewayDurabilityTest::lifecycle);
        run("accepted decisions and duplicate/conflicting IDs", RiskGatewayDurabilityTest::identities);
        run("ordering, invalid executions and session boundaries", RiskGatewayDurabilityTest::invalidEvents);
        run("daily-loss latch and closeout recovery", RiskGatewayDurabilityTest::halts);
        run("missing/stale inputs and extreme quantities", RiskGatewayDurabilityTest::limits);
        run("same-JVM writer leases and concurrent calls", RiskGatewayDurabilityTest::concurrency);
        run("hash-chain corruption and checkpoint rollback detection", RiskGatewayDurabilityTest::corruption);
        run("canonical payloads and malformed binary inputs", RiskGatewayDurabilityTest::malformedPayloads);
        run("semantic replay errors despite valid hash chains", RiskGatewayDurabilityTest::semanticRecovery);
        run("field, collection, event and journal size limits", RiskGatewayDurabilityTest::sizeLimits);
        run("injected write and force uncertainty", RiskGatewayDurabilityTest::writeFailures);
        run("missing/replaced journals, real I/O failures and rejected IDs", RiskGatewayDurabilityTest::externalFailures);
        run("checkpoint configuration binding and constructor cleanup", RiskGatewayDurabilityTest::checkpointContract);
        System.out.println("REPLAY_SHA256=" + sha(String.join("\n", replayHashes).getBytes(StandardCharsets.UTF_8)));
        System.out.println("PASS: " + checks + " checks in " + groups + " groups. Offline, same-JVM concurrency only.");
    }

    private static void lifecycle() throws Exception {
        Path path = path();
        List<RiskEvent> history = new ArrayList<>();
        RiskGateway live = new RiskGateway(CONFIG);
        List<RiskEvent> events = List.of(start(), accepted("buy", 6, OrderSide.BUY, 1),
                fill("fill-1", "buy", 2, OrderSide.BUY, 2, "101"), mark("mark", 3, "95"),
                cancel("cancel", "buy", 4), accepted("sell", 2, OrderSide.SELL, 5),
                fill("fill-2", "sell", 2, OrderSide.SELL, 6, "94"));
        for (RiskEvent event : events) {
            live.applyEventForPersistence(event);
            history.add(event);
            RiskGatewayJournal.Verification appended = RiskGatewayJournal.append(path, event);
            eq((long) history.size(), appended.eventCount(), "append count");
            eq(history, RiskGatewayJournal.read(path).events(), "typed payload roundtrip");
            RiskState first = RiskGatewayJournal.restore(path, CONFIG).state();
            eq(live.state(), first, "complete state restored at each prefix");
            eq(first, RiskGatewayJournal.restore(path, CONFIG).state(), "repeated recovery deterministic");
            try (var durable = new DurableRiskGateway(path, CONFIG, new RecoveryCheckpoint(CONFIG, appended))) {
                eq(first, durable.state(), "facade recovery agrees at each prefix");
            }
            if (event.eventId().equals("fill-1")) {
                eq(2L, first.executedPositions().get("A"), "partial fill execution retained");
                eq(4L, first.workingOrderReservations().get("buy").remainingQuantity(), "partial residual rebuilt");
                RiskDecision probe = RiskGatewayJournal.restore(path, CONFIG).evaluate(proposal("probe", 1, OrderSide.BUY, 3));
                ExposureSummary exposure = probe.exposure().orElseThrow();
                decimal("200", exposure.executedGrossNotional(), "executed exposure from marks");
                decimal("400", exposure.workingOrderReservedGrossNotional(), "remaining reservation not original size");
                decimal("40", exposure.workingOrderReservedMargin(), "remaining margin rebuilt");
                eq(new PositionRange(2, 7), exposure.projectedPositionRanges().get("A"), "worst-case range rebuilt");
            }
            if (event.eventId().equals("cancel")) {
                eq(Map.of("A", 2L), first.executedPositions(), "cancel never clears executions");
                require(first.workingOrderReservations().isEmpty(), "cancel releases only remainder");
                eq(OrderStatus.CANCELLED, first.orders().get("buy").status(), "cancel lifecycle retained");
            }
            replayHashes.add(appended.finalHash());
        }
        eq(Map.of("A", 0L), live.state().executedPositions(), "fully closed position");
        decimal("-14", live.state().dailyProfitLoss().orElseThrow(), "realized cashflow P&L restored");
        require(live.state().workingOrderReservations().isEmpty(), "full execution releases reservation");
        Path second = path();
        for (RiskEvent event : events) RiskGatewayJournal.append(second, event);
        require(Arrays.equals(Files.readAllBytes(path), Files.readAllBytes(second)), "same events produce identical bytes");
    }

    private static void identities() throws Exception {
        Path path = path();
        RiskDecision original;
        RecoveryCheckpoint checkpoint;
        try (var gateway = new DurableRiskGateway(path, CONFIG)) {
            gateway.apply(start());
            original = gateway.evaluate(proposal("buy", 6, OrderSide.BUY, 1));
            require(original.accepted(), "accepted reservation published");
            byte[] bytes = Files.readAllBytes(path);
            eq(original, gateway.evaluate(proposal("buy", 6, OrderSide.BUY, 1)), "proposal retry returns original decision");
            eq(DecisionReason.CONFLICTING_EVENT_ID, gateway.evaluate(proposal("buy", 5, OrderSide.BUY, 1)).reason(), "conflicting proposal rejected");
            eq(DecisionReason.CONFLICTING_EVENT_ID, gateway.evaluate(proposal("start", 1, OrderSide.BUY, 1)).reason(), "event/proposal collision rejected");
            gateway.apply(start());
            throwsType(RiskEventException.class, () -> gateway.apply(cancel("buy", "buy", 2)), "proposal/event collision rejected");
            require(Arrays.equals(bytes, Files.readAllBytes(path)), "retries/conflicts never append");
            ExecutionEvent execution = fill("fill", "buy", 2, OrderSide.BUY, 2, "100");
            gateway.apply(execution);
            RiskState before = gateway.state();
            gateway.apply(execution);
            eq(before, gateway.state(), "execution retry does not double count");
            throwsType(RiskEventException.class, () -> gateway.apply(fill("fill", "buy", 1, OrderSide.BUY, 2, "100")), "execution ID conflict");
            eq(before, gateway.state(), "conflicting event leaves acknowledged state intact");
            checkpoint = gateway.checkpoint();
        }
        try (var restored = new DurableRiskGateway(path, CONFIG, checkpoint)) {
            eq(original, restored.evaluate(proposal("buy", 6, OrderSide.BUY, 1)), "accepted decision survives later fill and restart");
            eq(4L, restored.state().workingOrderReservations().get("buy").remainingQuantity(), "retry does not reserve twice");
            eq(checkpoint, restored.checkpoint(), "retry keeps checkpoint");
        }
        byte[] bytes = Files.readAllBytes(path);
        eq(checkpoint.journal(), RiskGatewayJournal.append(path, start()), "old timestamp identical append is a no-op");
        throwsType(IOException.class, () -> RiskGatewayJournal.append(path,
                new SessionStartEvent("start", "different", T, Map.of())), "journal conflicting ID");
        throwsType(IOException.class, () -> RiskGatewayJournal.append(path, mark("out-of-order", 1, "100")), "journal append enforces timestamp ordering");
        require(Arrays.equals(bytes, Files.readAllBytes(path)), "journal duplicate/conflict bytes unchanged");
    }

    private static void invalidEvents() throws Exception {
        Path path = path();
        try (var gateway = new DurableRiskGateway(path, CONFIG)) {
            gateway.apply(start());
            gateway.evaluate(proposal("buy", 6, OrderSide.BUY, 2));
            List<RiskEvent> invalid = List.of(
                    fill("early", "buy", 1, OrderSide.BUY, 1, "100"),
                    fill("unknown", "absent", 1, OrderSide.BUY, 3, "100"),
                    fill("side", "buy", 1, OrderSide.SELL, 3, "100"),
                    fill("over", "buy", 7, OrderSide.BUY, 3, "100"),
                    fill("zero", "buy", 0, OrderSide.BUY, 3, "100"),
                    fill("negative", "buy", -1, OrderSide.BUY, 3, "100"),
                    fill("price", "buy", 1, OrderSide.BUY, 3, "0"),
                    new ExecutionEvent("instrument", "S", at(3), "buy", "B", OrderSide.BUY, 1, bd("100")),
                    new ExecutionEvent("session", "other", at(3), "buy", "A", OrderSide.BUY, 1, bd("100")),
                    cancel("unknown-cancel", "absent", 3),
                    new SessionStartEvent("new-session", "S2", at(3), Map.of()),
                    new MarkToMarketEvent("empty", "S", at(3), Map.of()));
            RiskState before = gateway.state();
            byte[] bytes = Files.readAllBytes(path);
            for (RiskEvent event : invalid) {
                throwsType(RiskEventException.class, () -> gateway.apply(event), "invalid event " + event.eventId());
                eq(before, gateway.state(), "invalid event does not mutate state");
                require(Arrays.equals(bytes, Files.readAllBytes(path)), "invalid event does not append");
            }
            eq(DecisionReason.OUT_OF_ORDER, gateway.evaluate(proposal("old-proposal", 1, OrderSide.BUY, 1)).reason(), "proposal time ordering");
            gateway.apply(fill("over", "buy", 2, OrderSide.BUY, 2, "100"));
            eq(2L, gateway.state().executedPositions().get("A"), "rejected event ID not consumed; tied times allowed");
            gateway.apply(cancel("cancel", "buy", 3));
            throwsType(RiskEventException.class, () -> gateway.apply(fill("late", "buy", 1, OrderSide.BUY, 4, "100")), "execution after cancel rejected");
            throwsType(RiskEventException.class, () -> gateway.apply(cancel("again", "buy", 4)), "new cancel ID on terminal order rejected");
            throwsType(RiskEventException.class, () -> gateway.apply(new SessionStartEvent("bad-open", "S2", at(5), Map.of())), "carried position needs opening mark");
            gateway.apply(new SessionStartEvent("next", "S2", at(6), Map.of("A", InstrumentMark.priceOnly(bd("110")))));
            eq(Map.of("A", 2L), gateway.state().executedPositions(), "session rollover carries exposure");
            decimal("0", gateway.state().dailyProfitLoss().orElseThrow(), "session opening basis resets P&L");
            eq(gateway.state(), RiskGatewayJournal.restore(path, CONFIG).state(), "session rollover replays exactly");
        }
    }

    private static void halts() throws Exception {
        Path path = path();
        RecoveryCheckpoint checkpoint;
        try (var gateway = new DurableRiskGateway(path, CONFIG)) {
            gateway.apply(start());
            gateway.evaluate(proposal("buy", 3, OrderSide.BUY, 1));
            gateway.apply(fill("fill", "buy", 2, OrderSide.BUY, 2, "100"));
            gateway.apply(mark("loss", 3, "50"));
            require(gateway.state().dailyLossHalted(), "loss threshold latches at equality");
            gateway.apply(mark("rebound", 4, "100"));
            require(gateway.state().dailyLossHalted(), "mark rebound does not clear halt");
            eq(DecisionReason.SESSION_HALTED, gateway.evaluate(proposal("more", 1, OrderSide.BUY, 5)).reason(), "halt blocks risk increase");
            eq(DecisionReason.SESSION_HALTED, gateway.evaluate(proposal("cross", 3, OrderSide.SELL, 5)).reason(), "halt blocks crossing zero");
            eq(DecisionReason.SESSION_HALTED, gateway.evaluate(proposal("pending", 2, OrderSide.SELL, 5)).reason(), "working order blocks closeout exception");
            gateway.apply(cancel("cancel", "buy", 6));
            checkpoint = gateway.checkpoint();
        }
        try (var gateway = new DurableRiskGateway(path, CONFIG, checkpoint)) {
            require(gateway.state().dailyLossHalted(), "halt rebuilds after rebound and restart");
            RiskDecision closeout = gateway.evaluate(proposal("close", 2, OrderSide.SELL, 7));
            require(closeout.accepted() && closeout.riskReducingHaltException(), "strict closeout allowed after restart");
            gateway.apply(fill("close-fill", "close", 1, OrderSide.SELL, 8, "100"));
            checkpoint = gateway.checkpoint();
        }
        try (var gateway = new DurableRiskGateway(path, CONFIG, checkpoint)) {
            eq(1L, gateway.state().executedPositions().get("A"), "partial closeout position rebuilds");
            eq(1L, gateway.state().workingOrderReservations().get("close").remainingQuantity(), "partial closeout reservation rebuilds");
            eq(DecisionReason.SESSION_HALTED, gateway.evaluate(proposal("new-risk", 1, OrderSide.BUY, 9)).reason(), "recovered partial closeout remains halted");
            gateway.apply(fill("done", "close", 1, OrderSide.SELL, 10, "100"));
            gateway.apply(new SessionStartEvent("next", "S2", at(11), Map.of()));
            require(!gateway.state().dailyLossHalted(), "explicit valid new session clears daily-loss halt");
            eq(gateway.state(), RiskGatewayJournal.restore(path, CONFIG).state(), "halt-reset history replay");
        }
    }

    private static void limits() throws Exception {
        RiskGateway gateway = new RiskGateway(CONFIG);
        eq(DecisionReason.NO_ACTIVE_SESSION, gateway.evaluate(proposal("none", 1, OrderSide.BUY, 1)).reason(), "session needed");
        gateway.startSession(start());
        require(gateway.evaluate(proposal("buy", 10, OrderSide.BUY, 1)).accepted(), "position cap equality accepted");
        eq(DecisionReason.POSITION_LIMIT, gateway.evaluate(proposal("too-many", 1, OrderSide.BUY, 1)).reason(), "reservations count toward cap");
        gateway.applyExecution(fill("fill", "buy", 1, OrderSide.BUY, 2, "100"));
        gateway.applyMarkToMarket(new MarkToMarketEvent("missing", "S", at(3), Map.of("A", InstrumentMark.missing())));
        eq(DecisionReason.MISSING_MARK, gateway.evaluate(proposal("no-mark", 1, OrderSide.SELL, 4)).reason(), "missing P&L fails closed");
        gateway.applyMarkToMarket(mark("mark", 5, "100"));
        eq(DecisionReason.STALE_MARK, gateway.evaluate(proposal("stale", 1, OrderSide.SELL, 3606)).reason(), "stale mark fails closed");
        RiskGateway recovered = RiskGateway.restore(CONFIG, gateway.state().eventHistory());
        eq(gateway.state(), recovered.state(), "missing and refreshed marks replay");

        RiskConfiguration huge = new RiskConfiguration("INR", Map.of("A", new InstrumentRiskSpec("A", "INR", BigDecimal.ONE, Long.MAX_VALUE)),
                bd("1E40"), bd("1E40"), bd("1E40"), Duration.ofHours(1));
        RiskGateway extreme = new RiskGateway(huge);
        extreme.startSession(new SessionStartEvent("start", "S", T, Map.of("A", InstrumentMark.of(bd("100"), margin(0)))));
        require(extreme.evaluate(proposal("max", Long.MAX_VALUE, OrderSide.SELL, 1)).accepted(), "largest representable safe negative position accepted");
        extreme.applyExecution(fill("max-fill", "max", Long.MAX_VALUE, OrderSide.SELL, 2, "100"));
        eq(DecisionReason.POSITION_LIMIT, extreme.evaluate(proposal("min-overflow", 1, OrderSide.SELL, 3)).reason(), "Long.MIN_VALUE cannot evade absolute cap");
        eq(DecisionReason.POSITION_LIMIT, extreme.evaluate(proposal("sum-overflow", 2, OrderSide.SELL, 3)).reason(), "position arithmetic overflow rejected");
        eq(extreme.state(), RiskGateway.restore(huge, extreme.state().eventHistory()).state(), "extreme quantity replay deterministic");
    }

    private static void concurrency() throws Exception {
        Path path = path();
        RecoveryCheckpoint checkpoint;
        try (var gateway = new DurableRiskGateway(path, CONFIG)) {
            gateway.apply(start());
            throwsType(IOException.class, () -> { try (var ignored = new DurableRiskGateway(path, CONFIG)) { } }, "second facade excluded");
            throwsType(IOException.class, () -> RiskGatewayJournal.append(path, accepted("bypass", 1, OrderSide.BUY, 1)), "low-level writer respects lifetime lease");
            CountDownLatch ready = new CountDownLatch(16), go = new CountDownLatch(1);
            try (ExecutorService executor = Executors.newFixedThreadPool(16)) {
                List<Future<RiskDecision>> futures = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    String id = "concurrent-" + i;
                    futures.add(executor.submit(() -> { ready.countDown(); await(go); return gateway.evaluate(proposal(id, 1, OrderSide.BUY, 1)); }));
                }
                await(ready); go.countDown();
                int accepted = 0;
                for (Future<RiskDecision> future : futures) if (future.get(20, TimeUnit.SECONDS).accepted()) accepted++;
                eq(10, accepted, "concurrent calls cannot overreserve");
            }
            eq(10, gateway.state().workingOrderReservations().size(), "all concurrent accepted reservations retained");
            eq(gateway.state(), RiskGatewayJournal.restore(path, CONFIG).state(), "concurrent history determines replay");
            checkpoint = gateway.checkpoint();
        }
        try (var gateway = new DurableRiskGateway(path, CONFIG, checkpoint)) {
            eq(DecisionReason.POSITION_LIMIT, gateway.evaluate(proposal("after", 1, OrderSide.BUY, 2)).reason(), "concurrent reservations survive restart");
        }
        Path low = path();
        RiskGatewayJournal.append(low, start());
        Path leasePath = low.resolveSibling("." + low.getFileName() + ".runtime.lock");
        try (FileChannel channel = FileChannel.open(leasePath, StandardOpenOption.WRITE); FileLock lease = channel.lock()) {
            throwsType(IOException.class, () -> RiskGatewayJournal.append(low, mark("locked", 1, "100")), "held writer lease rejects append deterministically");
        }
        CountDownLatch ready = new CountDownLatch(8), go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger(), busy = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String id = "writer-" + i;
                futures.add(executor.submit(() -> {
                    ready.countDown(); await(go);
                    try { RiskGatewayJournal.append(low, mark(id, 1, "100")); succeeded.incrementAndGet(); }
                    catch (IOException expected) {
                        if (!expected.getMessage().contains("locked")) throw new AssertionError(expected);
                        busy.incrementAndGet();
                    }
                    return null;
                }));
            }
            await(ready); go.countDown();
            for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
        }
        eq(8, succeeded.get() + busy.get(), "every lower-level writer accounted for");
        require(succeeded.get() >= 1, "at least one lower-level append succeeds");
        eq(1L + succeeded.get(), RiskGatewayJournal.verify(low).eventCount(), "no lost/partial successful concurrent writes");
        eq(RiskGatewayJournal.restore(low, CONFIG).state(), RiskGatewayJournal.restore(low, CONFIG).state(), "concurrent append replay deterministic");
    }

    private static void corruption() throws Exception {
        Path original = seeded();
        byte[] good = Files.readAllBytes(original);
        RecoveryCheckpoint checkpoint = new RecoveryCheckpoint(CONFIG, RiskGatewayJournal.verify(original));
        String[] lines = new String(good, StandardCharsets.US_ASCII).stripTrailing().split("\n");
        Map<String, byte[]> variants = new LinkedHashMap<>();
        variants.put("partial final row", Arrays.copyOf(good, good.length - 9));
        variants.put("missing final LF", Arrays.copyOf(good, good.length - 1));
        variants.put("CRLF", new String(good, StandardCharsets.US_ASCII).replace("\n", "\r\n").getBytes(StandardCharsets.US_ASCII));
        variants.put("blank row", (new String(good, StandardCharsets.US_ASCII) + "\n").getBytes(StandardCharsets.US_ASCII));
        variants.put("reordered rows", (lines[1] + "\n" + lines[0] + "\n" + lines[2] + "\n").getBytes(StandardCharsets.US_ASCII));
        variants.put("missing middle row", (lines[0] + "\n" + lines[2] + "\n").getBytes(StandardCharsets.US_ASCII));
        variants.put("sequence spelling", ("01" + new String(good, StandardCharsets.US_ASCII).substring(1)).getBytes(StandardCharsets.US_ASCII));
        byte[] tampered = good.clone(); tampered[tampered.length - 2] = tampered[tampered.length - 2] == '0' ? (byte)'1' : (byte)'0';
        variants.put("changed final hash", tampered);
        String[] row = lines[1].split("\t");
        row[1] = "f".repeat(64);
        variants.put("previous hash", (lines[0] + "\n" + String.join("\t", row) + "\n" + lines[2] + "\n").getBytes(StandardCharsets.US_ASCII));
        row = lines[1].split("\t"); row[2] = "!" + row[2].substring(1);
        variants.put("invalid base64", (lines[0] + "\n" + String.join("\t", row) + "\n" + lines[2] + "\n").getBytes(StandardCharsets.US_ASCII));
        for (var entry : variants.entrySet()) {
            Path damaged = path(); Files.write(damaged, entry.getValue());
            throwsType(IOException.class, () -> RiskGatewayJournal.verify(damaged), entry.getKey() + " verification rejected");
            throwsType(IOException.class, () -> RiskGatewayJournal.append(damaged, mark("extra", 4, "100")), entry.getKey() + " append rejected");
            throwsType(IOException.class, () -> { try (var ignored = new DurableRiskGateway(damaged, CONFIG, checkpoint)) { } }, entry.getKey() + " restart rejected");
            require(Arrays.equals(entry.getValue(), Files.readAllBytes(damaged)), "corruption never repaired by dropping exposure");
        }
        for (byte[] rollback : List.of(new byte[0], (lines[0] + "\n").getBytes(StandardCharsets.US_ASCII))) {
            Path damaged = path(); Files.write(damaged, rollback);
            require(RiskGatewayJournal.verify(damaged).eventCount() < checkpoint.journal().eventCount(), "unanchored prefix can verify: explicit limit");
            throwsType(IOException.class, () -> { try (var ignored = new DurableRiskGateway(damaged, CONFIG, checkpoint)) { } }, "checkpoint detects whole-row rollback");
        }
        Path rewritten = path();
        RiskGatewayJournal.append(rewritten, start());
        RiskGatewayJournal.append(rewritten, accepted("buy", 3, OrderSide.BUY, 1));
        RiskGatewayJournal.append(rewritten, fill("fill", "buy", 1, OrderSide.BUY, 2, "99"));
        eq(3L, RiskGatewayJournal.verify(rewritten).eventCount(), "rewritten valid chain structurally passes");
        throwsType(IOException.class, () -> { try (var ignored = new DurableRiskGateway(rewritten, CONFIG, checkpoint)) { } }, "checkpoint detects rehashed replacement");
    }

    private static void malformedPayloads() throws Exception {
        byte[] good = payload(start());
        List<byte[]> bad = new ArrayList<>();
        byte[] version = good.clone(); ByteBuffer.wrap(version).putInt(2); bad.add(version);
        byte[] type = good.clone(); type[4] = 99; bad.add(type);
        bad.add(Arrays.copyOf(good, good.length - 1));
        bad.add(Arrays.copyOf(good, good.length + 1));
        byte[] fieldLength = good.clone(); ByteBuffer.wrap(fieldLength, 5, 4).putInt(-1); bad.add(fieldLength);
        byte[] tooLong = good.clone(); ByteBuffer.wrap(tooLong, 5, 4).putInt(65_537); bad.add(tooLong);
        byte[] utf8 = good.clone(); utf8[9] = (byte)0xff; bad.add(utf8);
        for (int count : List.of(-1, 100_001)) {
            bad.add(binaryMark(count, false));
        }
        bad.add(binaryMark(2, true));
        // DataInputStream.readBoolean accepts nonzero bytes; canonical re-encoding must reject 2.
        ByteArrayOutputStream noncanonicalBool = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(noncanonicalBool)) {
            out.writeInt(1); out.writeByte(5); field(out, "bool"); field(out, "S"); field(out, T.toString());
            out.writeInt(1); field(out, "A"); out.writeByte(2); field(out, "100"); out.writeBoolean(false);
        }
        bad.add(noncanonicalBool.toByteArray());
        for (byte[] bytes : bad) {
            Path path = path(); writeChain(path, List.of(bytes));
            throwsType(IOException.class, () -> RiskGatewayJournal.read(path), "malformed rehashed payload rejected");
        }
        Path duplicate = path(); writeChain(duplicate, List.of(good, good));
        throwsType(IOException.class, () -> RiskGatewayJournal.read(duplicate), "duplicate ID in rehashed journal rejected");
        Path conflict = path(); writeChain(conflict, List.of(good, payload(new SessionStartEvent("start", "other", T, Map.of()))));
        throwsType(IOException.class, () -> RiskGatewayJournal.read(conflict), "conflicting ID in rehashed journal rejected");
        Path backwards = path(); writeChain(backwards, List.of(good, payload(mark("earlier", -1, "100"))));
        throwsType(IOException.class, () -> RiskGatewayJournal.read(backwards), "decreasing timestamps in rehashed journal rejected");
        Path noncanonical = path();
        String encoded = Base64.getEncoder().encodeToString(payload(cancel("x", "buy", 1)));
        require(encoded.endsWith("="), "base64 fixture has padding");
        encoded = encoded.replace("=", "");
        writeRawRow(noncanonical, encoded);
        throwsType(IOException.class, () -> RiskGatewayJournal.read(noncanonical), "unpadded base64 rejected even with recalculated hash");
    }

    private static void semanticRecovery() throws Exception {
        List<List<RiskEvent>> cases = List.of(
                List.of(accepted("before-start", 1, OrderSide.BUY, 1)),
                List.of(start(), accepted("over-limit", 11, OrderSide.BUY, 1)),
                List.of(start(), fill("unknown", "missing", 1, OrderSide.BUY, 1, "100")),
                List.of(start(), accepted("buy", 3, OrderSide.BUY, 1), fill("overfill", "buy", 4, OrderSide.BUY, 2, "100")),
                List.of(start(), accepted("buy", 3, OrderSide.BUY, 1), cancel("cancel", "buy", 2), fill("late", "buy", 1, OrderSide.BUY, 3, "100")));
        for (List<RiskEvent> events : cases) {
            Path path = path();
            for (RiskEvent event : events) RiskGatewayJournal.append(path, event);
            eq((long) events.size(), RiskGatewayJournal.verify(path).eventCount(), "structurally valid semantic error fixture");
            RecoveryCheckpoint checkpoint = new RecoveryCheckpoint(CONFIG, RiskGatewayJournal.verify(path));
            for (int i = 0; i < 2; i++) {
                throwsType(RiskEventException.class, () -> { try (var ignored = new DurableRiskGateway(path, CONFIG, checkpoint)) { } }, "invalid risk replay fails deterministically");
            }
        }
    }

    private static void sizeLimits() throws Exception {
        Path path = path();
        RiskGatewayJournal.append(path, new SessionStartEvent("x".repeat(65_536), "S", T, Map.of()));
        eq(1L, RiskGatewayJournal.verify(path).eventCount(), "64 KiB field boundary accepted");
        byte[] before = Files.readAllBytes(path);
        throwsType(IOException.class, () -> RiskGatewayJournal.append(path,
                new SessionStartEvent("x".repeat(65_537), "S", T, Map.of())), "field over 64 KiB rejected");
        require(Arrays.equals(before, Files.readAllBytes(path)), "oversize field leaves journal unchanged");
        throwsType(IOException.class, () -> RiskGatewayJournal.append(path,
                new SessionStartEvent("bad-\uD800", "S", T, Map.of())), "malformed Unicode cannot silently change an ID on disk");
        throwsType(IOException.class, () -> RiskGatewayJournal.append(path,
                new SessionStartEvent("\u20B9".repeat(21_846), "S", T, Map.of())), "64 KiB limit counts UTF-8 bytes, not characters");
        TreeMap<String, InstrumentMark> marks = new TreeMap<>();
        for (int i = 0; i < 100_001; i++) marks.put("i" + i, InstrumentMark.missing());
        throwsType(IOException.class, () -> RiskGatewayJournal.append(path,
                new MarkToMarketEvent("many", "S", T, marks)), "collection over 100000 rejected");
        marks.clear();
        for (int i = 0; i < 20; i++) marks.put("k" + i + "x".repeat(60_000), InstrumentMark.missing());
        throwsType(IOException.class, () -> RiskGatewayJournal.append(path,
                new MarkToMarketEvent("large", "S", T, marks)), "encoded event over 1 MiB rejected");
        require(Arrays.equals(before, Files.readAllBytes(path)), "oversize collection/event leave journal unchanged");
        Path largePayload = path(); writeChain(largePayload, List.of(new byte[1_048_577]));
        throwsType(IOException.class, () -> RiskGatewayJournal.read(largePayload), "decoded event size bounded");
        Path hugeFile = path();
        try (FileChannel channel = FileChannel.open(hugeFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(256L * 1024 * 1024); channel.write(ByteBuffer.wrap(new byte[]{'\n'}));
        }
        eq(256L * 1024 * 1024 + 1, Files.size(hugeFile), "oversize file fixture size");
        throwsType(IOException.class, () -> RiskGatewayJournal.read(hugeFile), "file over 256 MiB rejected before allocation");
        throwsType(IOException.class, () -> RiskGatewayJournal.append(hugeFile, start()), "oversize file cannot append");
        // Remove only this known generated large fixture, retaining all small forensic fixtures.
        Files.delete(hugeFile);
    }

    private static void writeFailures() throws Exception {
        for (String operation : List.of("accept", "fill", "cancel", "halt")) {
            for (String stage : List.of("before-write", "partial-row", "before-force", "after-force", "runtime")) {
                Path path = seeded();
                RecoveryCheckpoint checkpoint = new RecoveryCheckpoint(CONFIG, RiskGatewayJournal.verify(path));
                RiskState before = RiskGatewayJournal.restore(path, CONFIG).state();
                RiskEvent attempted = switch (operation) {
                    case "accept" -> accepted("new", 1, OrderSide.BUY, 3);
                    case "fill" -> fill("next-fill", "buy", 1, OrderSide.BUY, 3, "100");
                    case "cancel" -> cancel("cancel", "buy", 3);
                    case "halt" -> mark("loss", 3, "1");
                    default -> throw new AssertionError();
                };
                RiskGateway projected = RiskGatewayJournal.restore(path, CONFIG);
                projected.applyEventForPersistence(attempted);
                if (operation.equals("halt")) require(projected.state().dailyLossHalted(), "fault targets a halting mark");
                AtomicInteger calls = new AtomicInteger();
                RiskGatewayJournal.AppendIO failure = (channel, row) -> {
                    calls.incrementAndGet();
                    if (stage.equals("runtime")) throw new IllegalStateException("injected runtime persistence failure");
                    if (stage.equals("partial-row")) {
                        row.limit(17); while (row.hasRemaining()) channel.write(row);
                    } else if (stage.equals("before-force") || stage.equals("after-force")) {
                        while (row.hasRemaining()) channel.write(row);
                        if (stage.equals("after-force")) channel.force(true);
                    }
                    throw new IOException("injected " + stage);
                };
                try (var gateway = new DurableRiskGateway(path, CONFIG, checkpoint, failure)) {
                    Throwing action = attempted instanceof OrderAcceptedEvent accepted
                            ? () -> gateway.evaluate(accepted.proposal()) : () -> gateway.apply(attempted);
                    throwsType(stage.equals("runtime") ? IllegalStateException.class : IOException.class, action, operation + " " + stage + " never reports success");
                    eq(1, calls.get(), "fault seam reached once");
                    disabled(gateway);
                }
                if (stage.equals("before-write") || stage.equals("runtime")) {
                    eq(before, RiskGatewayJournal.restore(path, CONFIG).state(), "pre-write failure preserves all prior exposure");
                    try (var recovered = new DurableRiskGateway(path, CONFIG, checkpoint)) {
                        eq(before, recovered.state(), "explicit exact-checkpoint restart after zero-byte failure");
                    }
                } else {
                    throwsType(IOException.class, () -> { try (var ignored = new DurableRiskGateway(path, CONFIG, checkpoint)) { } }, "uncertain append cannot restart from old checkpoint");
                    if (!stage.equals("partial-row")) {
                        eq(checkpoint.journal().eventCount() + 1, RiskGatewayJournal.verify(path).eventCount(), "complete uncertain row retained for reconciliation");
                        eq(projected.state(), RiskGatewayJournal.restore(path, CONFIG).state(), "uncertain complete row preserves exact intended execution/reservation/halt outcome");
                    }
                }
            }
        }
    }

    private static void externalFailures() throws Exception {
        for (String mutation : List.of("delete", "empty", "prefix", "replace", "directory")) {
            Path path = seeded();
            RecoveryCheckpoint checkpoint = new RecoveryCheckpoint(CONFIG, RiskGatewayJournal.verify(path));
            byte[] good = Files.readAllBytes(path);
            try (var gateway = new DurableRiskGateway(path, CONFIG, checkpoint)) {
                switch (mutation) {
                    case "delete" -> Files.delete(path);
                    case "empty" -> Files.write(path, new byte[0]);
                    case "prefix" -> Files.writeString(path, new String(good, StandardCharsets.US_ASCII).split("\n")[0] + "\n");
                    case "replace" -> {
                        Files.delete(path); writeChain(path, List.of(payload(start())));
                    }
                    case "directory" -> { Files.delete(path); Files.createDirectory(path); }
                }
                throwsType(IOException.class, () -> gateway.apply(cancel("invalid", "absent", 3)), "invalid-event recovery cannot erase exposure: " + mutation);
                disabled(gateway);
            }
        }
        Path path = seeded();
        RecoveryCheckpoint checkpoint = new RecoveryCheckpoint(CONFIG, RiskGatewayJournal.verify(path));
        try (var gateway = new DurableRiskGateway(path, CONFIG, checkpoint)) {
            Path lockPath = path.resolveSibling("." + path.getFileName() + ".lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE); FileLock lock = channel.lock()) {
                throwsType(IOException.class, () -> gateway.evaluate(proposal("busy", 1, OrderSide.BUY, 3)), "real journal lock I/O failure");
            }
            disabled(gateway);
        }
        Path oversized = seeded();
        checkpoint = new RecoveryCheckpoint(CONFIG, RiskGatewayJournal.verify(oversized));
        try (var gateway = new DurableRiskGateway(oversized, CONFIG, checkpoint)) {
            throwsType(IOException.class, () -> gateway.evaluate(proposal("x".repeat(65_537), 1, OrderSide.BUY, 3)), "real serialization persistence failure");
            disabled(gateway);
        }
        Path rejected = path();
        RecoveryCheckpoint rejectedCheckpoint;
        try (var gateway = new DurableRiskGateway(rejected, CONFIG)) {
            RiskDecision decision = gateway.evaluate(proposal("rejected", 1, OrderSide.BUY, 1));
            eq(DecisionReason.NO_ACTIVE_SESSION, decision.reason(), "rejected ID cached initially");
            gateway.apply(start());
            throwsType(RiskEventException.class, () -> gateway.apply(cancel("invalid", "absent", 2)), "invalid input does not flush rejected ID cache");
            eq(decision, gateway.evaluate(proposal("rejected", 1, OrderSide.BUY, 1)), "rejected decision retained across valid and invalid events");
            rejectedCheckpoint = gateway.checkpoint();
        }
        try (var gateway = new DurableRiskGateway(rejected, CONFIG, rejectedCheckpoint)) {
            require(gateway.evaluate(proposal("rejected", 1, OrderSide.BUY, 1)).accepted(), "rejected decisions are process-local: explicit limitation");
        }
    }

    private static void checkpointContract() throws Exception {
        Path path = seeded();
        RecoveryCheckpoint checkpoint = new RecoveryCheckpoint(CONFIG, RiskGatewayJournal.verify(path));
        byte[] before = Files.readAllBytes(path);
        throwsType(IOException.class, () -> { try (var ignored = new DurableRiskGateway(path, CONFIG)) { } }, "existing journal requires explicit recovery checkpoint");
        require(Arrays.equals(before, Files.readAllBytes(path)), "creation never overwrites existing history");
        throwsType(IOException.class, () -> { try (var ignored = new DurableRiskGateway(path, config(20, "1000"), checkpoint)) { } }, "configuration changes cannot silently clear halt or relax reservations");
        Path missing = path();
        throwsType(IOException.class, () -> { try (var ignored = new DurableRiskGateway(missing, CONFIG, checkpoint)) { } }, "missing recovery file rejected");
        require(!Files.exists(missing), "recovery never recreates missing history");
        try (var gateway = new DurableRiskGateway(path, CONFIG, checkpoint)) {
            eq(checkpoint, gateway.checkpoint(), "failed constructor releases lifetime lease");
            gateway.close(); gateway.close();
            throwsType(IllegalStateException.class, gateway::state, "closed state unavailable");
            throwsType(IllegalStateException.class, () -> gateway.evaluate(proposal("closed", 1, OrderSide.BUY, 3)), "closed evaluate unavailable");
        }
        Path empty = path();
        RecoveryCheckpoint emptyCheckpoint;
        try (var gateway = new DurableRiskGateway(empty, CONFIG)) { emptyCheckpoint = gateway.checkpoint(); }
        try (var gateway = new DurableRiskGateway(empty, CONFIG, emptyCheckpoint)) {
            eq(Optional.empty(), gateway.state().activeSessionId(), "explicit genesis checkpoint permits empty restart");
        }
    }

    private static void disabled(DurableRiskGateway gateway) throws Exception {
        throwsType(IllegalStateException.class, gateway::state, "uncertain state not published as empty or healthy");
        throwsType(IllegalStateException.class, () -> gateway.evaluate(proposal("blocked", 1, OrderSide.BUY, 9)), "uncertainty blocks new risk");
        throwsType(IllegalStateException.class, () -> gateway.apply(cancel("blocked-cancel", "buy", 9)), "uncertainty blocks mutation");
        throwsType(IllegalStateException.class, gateway::checkpoint, "uncertainty cannot mint a checkpoint");
    }

    private static RiskConfiguration config(long position, String loss) {
        return new RiskConfiguration("INR", Map.of("A", new InstrumentRiskSpec("A", "INR", BigDecimal.ONE, position),
                "B", new InstrumentRiskSpec("B", "INR", BigDecimal.ONE, position)),
                bd("1000000"), bd("1000000"), bd(loss), Duration.ofHours(1));
    }
    private static SessionStartEvent start() {
        return new SessionStartEvent("start", "S", T, Map.of("A", InstrumentMark.of(bd("100"), margin(0)), "B", InstrumentMark.priceOnly(bd("200"))));
    }
    private static MarginInput margin(int seconds) { return new MarginInput("A", bd("10"), "synthetic", "offline-fixture", "S", at(seconds)); }
    private static OrderProposal proposal(String id, long quantity, OrderSide side, int seconds) {
        return new OrderProposal(id, "S", at(seconds), "A", side, quantity, Optional.of(bd("100")), Optional.of(margin(seconds)));
    }
    private static OrderAcceptedEvent accepted(String id, long quantity, OrderSide side, int seconds) { return new OrderAcceptedEvent(proposal(id, quantity, side, seconds)); }
    private static ExecutionEvent fill(String id, String order, long quantity, OrderSide side, int seconds, String price) {
        return new ExecutionEvent(id, "S", at(seconds), order, "A", side, quantity, bd(price));
    }
    private static CancellationEvent cancel(String id, String order, int seconds) { return new CancellationEvent(id, "S", at(seconds), order); }
    private static MarkToMarketEvent mark(String id, int seconds, String price) {
        return new MarkToMarketEvent(id, "S", at(seconds), Map.of("A", InstrumentMark.of(bd(price), margin(seconds))));
    }
    private static Instant at(int seconds) { return T.plusSeconds(seconds); }
    private static BigDecimal bd(String text) { return new BigDecimal(text); }
    private static Path path() { return root.resolve(String.format(Locale.ROOT, "case-%03d.journal", ++files)); }
    private static Path seeded() throws Exception {
        Path path = path();
        RiskGatewayJournal.append(path, start());
        RiskGatewayJournal.append(path, accepted("buy", 3, OrderSide.BUY, 1));
        RiskGatewayJournal.append(path, fill("fill", "buy", 1, OrderSide.BUY, 2, "101"));
        return path;
    }
    private static byte[] payload(RiskEvent event) throws Exception {
        Path path = path(); RiskGatewayJournal.append(path, event);
        return Base64.getDecoder().decode(Files.readString(path).split("\t")[2]);
    }
    private static void writeChain(Path path, List<byte[]> payloads) throws Exception {
        StringBuilder text = new StringBuilder(); String head = "0".repeat(64); int sequence = 0;
        for (byte[] payload : payloads) {
            sequence++;
            String encoded = Base64.getEncoder().encodeToString(payload);
            String hash = hash(sequence, head, encoded);
            text.append(sequence).append('\t').append(head).append('\t').append(encoded).append('\t').append(hash).append('\n');
            head = hash;
        }
        Files.writeString(path, text, StandardCharsets.US_ASCII);
    }
    private static void writeRawRow(Path path, String encoded) throws Exception {
        String genesis = "0".repeat(64);
        Files.writeString(path, "1\t" + genesis + "\t" + encoded + "\t" + hash(1, genesis, encoded) + "\n", StandardCharsets.US_ASCII);
    }
    private static String hash(int sequence, String previous, String payload) throws Exception {
        return sha(("INTRA_RISK_GATEWAY_JOURNAL_V1\n" + sequence + "\n" + previous + "\n" + payload + "\n").getBytes(StandardCharsets.US_ASCII));
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static byte[] binaryMark(int count, boolean duplicate) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1); out.writeByte(5); field(out, "raw"); field(out, "S"); field(out, T.toString()); out.writeInt(count);
            if (duplicate) for (int i = 0; i < 2; i++) { field(out, "A"); out.writeBoolean(false); out.writeBoolean(false); }
        }
        return bytes.toByteArray();
    }
    private static void field(DataOutputStream out, String value) throws Exception { byte[] bytes = value.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes); }
    private static void await(CountDownLatch latch) throws InterruptedException { if (!latch.await(20, TimeUnit.SECONDS)) throw new AssertionError("concurrency barrier timed out"); }
    private static void run(String name, Throwing test) throws Exception { test.run(); groups++; System.out.println("PASS " + name); }
    private static void require(boolean condition, String description) { checks++; if (!condition) throw new AssertionError(description); }
    private static void eq(Object expected, Object actual, String description) { require(Objects.equals(expected, actual), description + ": expected=" + expected + ", actual=" + actual); }
    private static void decimal(String expected, BigDecimal actual, String description) { require(bd(expected).compareTo(actual) == 0, description + ": " + actual); }
    private static void throwsType(Class<? extends Throwable> type, Throwing action, String description) throws Exception {
        try { action.run(); }
        catch (Throwable failure) { require(type.isInstance(failure), description + ": unexpected " + failure); return; }
        throw new AssertionError(description + ": expected " + type.getSimpleName());
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
}
