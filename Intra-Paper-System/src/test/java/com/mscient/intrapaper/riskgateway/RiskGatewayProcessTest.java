package com.mscient.intrapaper.riskgateway;

import com.mscient.intrapaper.riskgateway.DurableRiskGateway.RecoveryCheckpoint;
import com.mscient.intrapaper.riskgateway.RiskGateway.*;
import com.mscient.intrapaper.riskgateway.RiskGatewayJournal.Verification;

import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Offline child-JVM ownership/recovery tests. Checkpoint files and IPC are test fixtures only. */
public final class RiskGatewayProcessTest {
    private static final Instant T = Instant.parse("2026-11-06T04:00:00Z");
    private static final RiskConfiguration CONFIG = configuration("base");
    private static final long TIMEOUT_SECONDS = 30;
    private static final int REJECTED = 23;
    private static final BufferedReader INPUT = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    private static final List<Process> CHILDREN = new ArrayList<>();
    private static Path root;
    private static int checks, groups, cases, launches, kills;

    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 21) throw new AssertionError("JDK 21 required in every JVM");
        if (args.length > 0 && args[0].equals("--child")) {
            int status = child(Arrays.copyOfRange(args, 1, args.length));
            System.exit(status);
        }
        if (args.length != 1) throw new IllegalArgumentException("Supply a fresh fixture directory");
        root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectory(root);
        for (String key : List.of("java.version", "java.runtime.version", "java.vendor", "java.vendor.version",
                "java.home", "os.name", "os.version", "os.arch")) {
            System.out.println(key + "=" + System.getProperty(key));
        }
        System.out.println("filesystem.type=" + Files.getFileStore(root).type());
        System.out.println("filesystem.name=" + Files.getFileStore(root).name());
        try {
            run("concurrent child JVMs, exclusive creation and recovery leases", RiskGatewayProcessTest::ownership);
            run("matching checkpoint, complete state and idempotent accepted IDs", RiskGatewayProcessTest::restartAndRetry);
            run("stale/mismatched checkpoint and configuration fail closed", RiskGatewayProcessTest::checkpointRejections);
            run("truncated, malformed, reordered and corrupted tails", RiskGatewayProcessTest::tails);
            run("forced process termination at controlled append boundaries", RiskGatewayProcessTest::crashes);
            System.out.println("PASS: " + checks + " checks in " + groups + " groups; " + launches
                    + " child JVMs, " + kills + " forced terminations. Process kills do not prove power-loss durability.");
        } finally {
            // Only terminate processes started by this harness. Retain every fixture and transcript.
            for (Process process : CHILDREN) {
                if (process.isAlive()) process.destroyForcibly();
            }
            for (Process process : CHILDREN) {
                if (process.isAlive() && !process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new AssertionError("Child cleanup timed out: " + process.pid());
                }
            }
        }
    }

    private static void ownership() throws Exception {
        for (String mode : List.of("create", "recover")) {
            for (int round = 0; round < 3; round++) {
                Path dir = fresh("ownership-" + mode);
                if (mode.equals("recover")) seed(dir);
                Worker a = launch(dir, "contend", mode, "a");
                Worker b = launch(dir, "contend", mode, "b");
                awaitMarker(a, "ready-a");
                awaitMarker(b, "ready-b");
                send(a, "GO");
                send(b, "GO");
                // Both were ready before release; the winner cannot release its lease until both report.
                awaitMarker(a, "status-a");
                awaitMarker(b, "status-b");
                String sa = Files.readString(dir.resolve("status-a"));
                String sb = Files.readString(dir.resolve("status-b"));
                require(Set.of(sa, sb).equals(Set.of("OWNED", "DENIED")), "exactly one cross-process owner");
                Worker owner = sa.equals("OWNED") ? a : b;
                Worker loser = owner == a ? b : a;
                String ownerId = owner == a ? "a" : "b";
                finish(loser, REJECTED);
                byte[] bytes = journalBytes(dir);
                finish(launch(dir, "append-reject"), REJECTED);
                require(Arrays.equals(bytes, journalBytes(dir)), "public append cannot bypass facade lease");
                send(owner, "RELEASE");
                finish(owner, 0);
                List<RiskEvent> expected = new ArrayList<>(mode.equals("create") ? List.of(start()) : history());
                expected.add(new OrderAcceptedEvent(proposal("owner-" + ownerId, 1, 4)));
                verify(dir, expected);
                eq(1L, RiskGatewayJournal.read(journal(dir)).events().stream()
                        .filter(e -> e.eventId().startsWith("owner-")).count(), "only owner reserved");
                recover(dir, dir.resolve("owner.checkpoint"), expected);
            }
        }
    }

    private static void restartAndRetry() throws Exception {
        Path dir = fresh("retry");
        seed(dir);
        byte[] before = journalBytes(dir);
        Path checkpoint = dir.resolve("checkpoint-3");
        for (int retry = 0; retry < 3; retry++) {
            finish(launch(dir, "retry", checkpoint.toString()), 0);
            require(Arrays.equals(before, journalBytes(dir)), "cross-process accepted/fill retry does not append");
            eq(Files.readString(dir.resolve("accepted-decision")), Files.readString(dir.resolve("retry-decision")),
                    "original accepted decision survives later execution and restart");
            eq(Files.readString(dir.resolve("state-3")), Files.readString(dir.resolve("retry-state")),
                    "retry leaves complete state unchanged");
            eq(readCheckpoint(checkpoint), readCheckpoint(dir.resolve("retry.checkpoint")), "retry keeps checkpoint");
        }
        finish(launch(dir, "append-retry"), 0);
        require(Arrays.equals(before, journalBytes(dir)), "low-level identical accepted event is idempotent across JVMs");
        recover(dir, checkpoint, history());
        RiskState state = RiskGateway.restore(CONFIG, history()).state();
        eq(Map.of("A", 1L), state.executedPositions(), "one execution retained");
        eq(2L, state.workingOrderReservations().get("buy").remainingQuantity(), "one residual reservation retained");
    }

    private static void checkpointRejections() throws Exception {
        Path dir = fresh("checkpoints");
        seed(dir);
        rejectRecovery(dir, dir.resolve("checkpoint-1"), "base");
        rejectRecovery(dir, dir.resolve("checkpoint-2"), "base");
        RecoveryCheckpoint good = readCheckpoint(dir.resolve("checkpoint-3"));
        List<Verification> wrong = List.of(
                new Verification(2, good.journal().finalHash(), good.journal().lastEventAt()),
                new Verification(3, "f".repeat(64), good.journal().lastEventAt()),
                new Verification(3, good.journal().finalHash(), Optional.of(T.plusSeconds(9))));
        for (int i = 0; i < wrong.size(); i++) {
            Path checkpoint = dir.resolve("wrong-" + i);
            writeCheckpoint(checkpoint, new RecoveryCheckpoint(CONFIG, wrong.get(i)));
            rejectRecovery(dir, checkpoint, "base");
        }
        for (String variant : List.of("currency", "position", "multiplier", "gross", "margin", "loss", "age", "instruments")) {
            rejectRecovery(dir, dir.resolve("checkpoint-3"), variant);
        }
        finish(launch(dir, "create-reject"), REJECTED);
        recover(dir, dir.resolve("checkpoint-3"), history()); // Repeated constructor failures release their leases.
    }

    private static void tails() throws Exception {
        Path seed = fresh("tail-source");
        seed(seed);
        byte[] original = journalBytes(seed);
        List<String> rows = Files.readAllLines(journal(seed), StandardCharsets.US_ASCII);
        String last = rows.getLast();
        String[] fields = last.split("\t", -1);
        Map<String, byte[]> invalid = new LinkedHashMap<>();
        invalid.put("partial-row", Arrays.copyOf(original, original.length - 31));
        invalid.put("missing-lf", Arrays.copyOf(original, original.length - 1));
        invalid.put("malformed-fields", bytes(rows.get(0) + "\n" + rows.get(1) + "\n3\tbad\n"));
        invalid.put("blank-tail", bytes(new String(original, StandardCharsets.US_ASCII) + "\n"));
        invalid.put("crlf-tail", bytes(rows.get(0) + "\n" + rows.get(1) + "\n" + last + "\r\n"));
        invalid.put("reordered-tail", bytes(rows.get(0) + "\n" + rows.get(2) + "\n" + rows.get(1) + "\n"));
        invalid.put("corrupt-hash", bytes(rows.get(0) + "\n" + rows.get(1) + "\n"
                + String.join("\t", fields[0], fields[1], fields[2], flip(fields[3])) + "\n"));
        invalid.put("corrupt-payload", bytes(rows.get(0) + "\n" + rows.get(1) + "\n"
                + String.join("\t", fields[0], fields[1], flip(fields[2]), fields[3]) + "\n"));
        invalid.put("broken-link", bytes(rows.get(0) + "\n" + rows.get(1) + "\n"
                + String.join("\t", fields[0], flip(fields[1]), fields[2], fields[3]) + "\n"));
        invalid.put("rehashed-invalid-base64", replaceLast(rows, "!!!!"));
        byte[] payload = Base64.getDecoder().decode(fields[2]);
        payload[3] = 2; // Unsupported binary schema, with a valid recomputed row hash.
        invalid.put("rehashed-invalid-schema", replaceLast(rows, Base64.getEncoder().encodeToString(payload)));
        for (Map.Entry<String, byte[]> mutation : invalid.entrySet()) {
            Path dir = tailCase(seed, mutation.getKey(), mutation.getValue());
            finish(launch(dir, "verify-reject"), REJECTED);
            rejectRecovery(dir, dir.resolve("checkpoint-3"), "base");
            finish(launch(dir, "append-corrupt-reject"), REJECTED);
            finish(launch(dir, "create-reject"), REJECTED);
            require(Arrays.equals(mutation.getValue(), journalBytes(dir)), "invalid tail remains unchanged: " + mutation.getKey());
        }
        for (int count : List.of(0, 1, 2)) {
            byte[] rollback = bytes(String.join("\n", rows.subList(0, count)) + (count == 0 ? "" : "\n"));
            Path dir = tailCase(seed, "complete-row-rollback-" + count, rollback);
            verify(dir, history().subList(0, count)); // Structurally valid rollback still cannot authorize restart.
            rejectRecovery(dir, dir.resolve("checkpoint-3"), "base");
            require(Arrays.equals(rollback, journalBytes(dir)), "rollback not silently repaired");
        }
        Path missing = tailCase(seed, "missing-journal", original);
        Files.delete(journal(missing));
        rejectRecovery(missing, missing.resolve("checkpoint-3"), "base");
        require(!Files.exists(journal(missing)), "recovery never recreates a missing acknowledged journal");
    }

    private static void crashes() throws Exception {
        for (String operation : List.of("accept", "fill", "cancel", "halt")) {
            for (String stage : List.of("before-write", "prefix", "without-lf", "complete", "forced", "acknowledged")) {
                Path dir = fresh("kill-" + operation + "-" + stage);
                seed(dir);
                byte[] before = journalBytes(dir);
                byte[] checkpointBefore = Files.readAllBytes(dir.resolve("checkpoint-3"));
                Worker writer = launch(dir, "crash", operation, stage);
                awaitMarker(writer, "append-boundary");
                require(writer.process().isAlive(), "kill target is paused at an explicit boundary");
                byte[] atBoundary = journalBytes(dir);
                // Append-in-progress probes prove both locks are held; post-ack keeps only the lifetime lease.
                finish(launch(dir, "append-reject"), REJECTED);
                if (!stage.equals("acknowledged")) finish(launch(dir, "verify-locked"), REJECTED);
                kill(writer);
                require(Arrays.equals(atBoundary, journalBytes(dir)), "termination did not secretly complete append");
                require(Arrays.equals(checkpointBefore, Files.readAllBytes(dir.resolve("checkpoint-3"))),
                        "previous independent acknowledgement anchor survives termination");
                List<RiskEvent> attempted = new ArrayList<>(history());
                attempted.add(attempt(operation));
                RiskState attemptedState = RiskGateway.restore(CONFIG, attempted).state();
                switch (operation) {
                    case "accept" -> eq(2L, attemptedState.workingOrderReservations().get("next").remainingQuantity(), "new reservation modeled");
                    case "fill" -> eq(2L, attemptedState.executedPositions().get("A"), "second execution modeled");
                    case "cancel" -> require(attemptedState.workingOrderReservations().isEmpty(), "cancel releases only residual");
                    case "halt" -> require(attemptedState.dailyLossHalted(), "attempted mark latches loss at equality");
                    default -> throw new AssertionError(operation);
                }
                if (stage.equals("acknowledged")) {
                    require(Files.exists(dir.resolve("acknowledged")), "successful call recorded before kill");
                    verify(dir, attempted);
                    recover(dir, dir.resolve("post.checkpoint"), attempted);
                    rejectRecovery(dir, dir.resolve("checkpoint-3"), "base");
                    if (operation.equals("accept")) {
                        byte[] accepted = journalBytes(dir);
                        finish(launch(dir, "post-retry"), 0);
                        require(Arrays.equals(accepted, journalBytes(dir)), "acknowledged event retry after kill cannot reserve twice");
                    }
                } else {
                    require(!Files.exists(dir.resolve("acknowledged")), "no append-in-progress call reported success");
                    require(!Files.exists(dir.resolve("post.checkpoint")), "uncertain outcome did not mint a checkpoint");
                    if (stage.equals("before-write")) {
                        require(Arrays.equals(before, journalBytes(dir)), "zero-byte kill preserves prior history");
                        verify(dir, history());
                        recover(dir, dir.resolve("checkpoint-3"), history());
                    } else if (stage.equals("prefix") || stage.equals("without-lf")) {
                        eq(before.length + (stage.equals("prefix") ? 17 : expectedRow(dir, attempted).length - 1),
                                journalBytes(dir).length, "controlled partial append length");
                        require(Arrays.equals(before, Arrays.copyOf(journalBytes(dir), before.length)), "acknowledged prefix intact");
                        finish(launch(dir, "verify-reject"), REJECTED);
                        rejectRecovery(dir, dir.resolve("checkpoint-3"), "base");
                        finish(launch(dir, "append-corrupt-reject"), REJECTED);
                    } else {
                        verify(dir, attempted); // Inspect uncertain exposure; never promote it into a trusted checkpoint.
                        rejectRecovery(dir, dir.resolve("checkpoint-3"), "base");
                    }
                }
                require(Arrays.equals(atBoundary, journalBytes(dir)), "verification/restart never trims or invents journal state");
                System.out.println("PASS kill " + operation + " at " + stage);
            }
        }
    }

    private static int child(String[] args) throws Exception {
        String mode = args[0];
        Path dir = Path.of(args[1]);
        Path journal = journal(dir);
        switch (mode) {
            case "seed" -> {
                try (var gateway = new DurableRiskGateway(journal, CONFIG)) {
                    gateway.apply(start());
                    save(gateway, dir, "1");
                    RiskDecision decision = gateway.evaluate(proposal("buy", 3, 1));
                    if (!decision.accepted()) throw new AssertionError("Seed reservation rejected");
                    Files.writeString(dir.resolve("accepted-decision"), decision.toString());
                    save(gateway, dir, "2");
                    gateway.apply(fill());
                    save(gateway, dir, "3");
                }
            }
            case "contend" -> {
                String kind = args[2], id = args[3];
                marker(dir, "ready-" + id, "READY");
                input("GO");
                DurableRiskGateway gateway;
                try {
                    gateway = kind.equals("create") ? new DurableRiskGateway(journal, CONFIG)
                            : new DurableRiskGateway(journal, CONFIG, readCheckpoint(dir.resolve("checkpoint-3")));
                } catch (IOException failure) {
                    requireLocked(failure);
                    marker(dir, "status-" + id, "DENIED");
                    System.out.println("DENIED " + failure.getMessage());
                    return REJECTED;
                }
                try (gateway) {
                    if (kind.equals("create")) gateway.apply(start());
                    if (!gateway.evaluate(proposal("owner-" + id, 1, 4)).accepted()) throw new AssertionError("Owner rejected");
                    writeCheckpoint(dir.resolve("owner.checkpoint"), gateway.checkpoint());
                    marker(dir, "status-" + id, "OWNED");
                    input("RELEASE");
                }
            }
            case "recover" -> {
                try (var gateway = new DurableRiskGateway(journal, CONFIG, readCheckpoint(Path.of(args[2])))) {
                    eq(Files.readString(Path.of(args[3])), gateway.state().toString(), "child complete recovered state");
                    eq(readCheckpoint(Path.of(args[2])), gateway.checkpoint(), "child recovered checkpoint");
                }
            }
            case "recover-reject" -> {
                RecoveryCheckpoint checkpoint = readCheckpoint(Path.of(args[2]));
                RiskConfiguration config = configuration(args[3]);
                try (var gateway = new DurableRiskGateway(journal, config, checkpoint)) {
                    gateway.evaluate(proposal("unsafe-recovery-probe", 1, 9));
                    throw new AssertionError("Recovery admitted a mismatched/invalid journal or configuration");
                } catch (IOException expected) {
                    System.out.println("RECOVERY_REJECTED " + expected.getMessage());
                    return REJECTED;
                }
            }
            case "retry" -> {
                try (var gateway = new DurableRiskGateway(journal, CONFIG, readCheckpoint(Path.of(args[2])))) {
                    RiskState before = gateway.state();
                    RiskDecision original = gateway.evaluate(proposal("buy", 3, 1));
                    if (!original.accepted()) throw new AssertionError("Accepted ID lost during recovery");
                    eq(DecisionReason.CONFLICTING_EVENT_ID, gateway.evaluate(proposal("buy", 2, 1)).reason(), "conflict rejected");
                    gateway.apply(new OrderAcceptedEvent(proposal("buy", 3, 1)));
                    gateway.apply(fill());
                    eq(before, gateway.state(), "no duplicate execution or reservation");
                    Files.writeString(dir.resolve("retry-decision"), original.toString());
                    Files.writeString(dir.resolve("retry-state"), gateway.state().toString());
                    writeCheckpoint(dir.resolve("retry.checkpoint"), gateway.checkpoint());
                }
            }
            case "append-retry" -> {
                Verification before = readCheckpoint(dir.resolve("checkpoint-3")).journal();
                eq(before, RiskGatewayJournal.append(journal, new OrderAcceptedEvent(proposal("buy", 3, 1))), "low-level accepted retry");
                eq(before, RiskGatewayJournal.append(journal, fill()), "low-level executed retry");
            }
            case "append-reject", "append-corrupt-reject" -> {
                try {
                    RiskGatewayJournal.append(journal, new OrderAcceptedEvent(proposal("bypass", 1, 8)));
                    throw new AssertionError("Unexpected successful append");
                } catch (IOException expected) {
                    if (mode.equals("append-reject")) requireLocked(expected);
                    System.out.println("APPEND_REJECTED " + expected.getMessage());
                    return REJECTED;
                }
            }
            case "create-reject" -> {
                try (var ignored = new DurableRiskGateway(journal, CONFIG)) {
                    throw new AssertionError("Creation reset an existing journal");
                } catch (FileAlreadyExistsException expected) {
                    System.out.println("CREATE_REJECTED existing journal");
                    return REJECTED;
                }
            }
            case "verify" -> {
                RiskGatewayJournal.ReadResult read = RiskGatewayJournal.read(journal);
                eq(Files.readString(Path.of(args[2])), RiskGateway.restore(CONFIG, read.events()).state().toString(),
                        "child verified complete replay state");
                eq(read.events().size(), new HashSet<>(read.events().stream().map(RiskEvent::eventId).toList()).size(),
                        "no duplicate accepted event IDs on disk");
                System.out.println("VERIFIED " + read.verification());
            }
            case "verify-reject", "verify-locked" -> {
                try {
                    RiskGatewayJournal.verify(journal);
                    throw new AssertionError("Unexpected successful verification");
                } catch (IOException expected) {
                    if (mode.equals("verify-locked")) requireLocked(expected);
                    System.out.println("VERIFY_REJECTED " + expected.getMessage());
                    return REJECTED;
                }
            }
            case "crash" -> crashChild(dir, args[2], args[3]);
            case "post-retry" -> {
                try (var gateway = new DurableRiskGateway(journal, CONFIG, readCheckpoint(dir.resolve("post.checkpoint")))) {
                    RiskState before = gateway.state();
                    if (!gateway.evaluate(proposal("next", 2, 4)).accepted()) throw new AssertionError("Accepted ID lost after kill");
                    gateway.apply(new OrderAcceptedEvent(proposal("next", 2, 4)));
                    eq(before, gateway.state(), "accepted retry after kill is a no-op");
                }
            }
            default -> throw new IllegalArgumentException("Unknown child mode: " + mode);
        }
        System.out.println("CHILD_PASS " + mode);
        return 0;
    }

    private static void crashChild(Path dir, String operation, String stage) throws Exception {
        RiskGatewayJournal.AppendIO io = (channel, row) -> {
            switch (stage) {
                case "before-write" -> { }
                case "prefix", "without-lf" -> {
                    int length = stage.equals("prefix") ? 17 : row.remaining() - 1;
                    ByteBuffer part = row.slice();
                    part.limit(length);
                    while (part.hasRemaining()) channel.write(part);
                }
                case "complete", "forced", "acknowledged" -> {
                    while (row.hasRemaining()) channel.write(row);
                    if (!stage.equals("complete")) channel.force(true);
                }
                default -> throw new IllegalArgumentException("Unknown crash stage");
            }
            if (!stage.equals("acknowledged")) {
                marker(dir, "append-boundary", operation + " " + stage);
                input("NEVER_RELEASE"); // Parent must kill with both channels/locks open, not return or close.
                throw new AssertionError("Kill boundary resumed");
            }
        };
        RecoveryCheckpoint previous = readCheckpoint(dir.resolve("checkpoint-3"));
        try (var gateway = stage.equals("acknowledged")
                ? new DurableRiskGateway(journal(dir), CONFIG, previous)
                : new DurableRiskGateway(journal(dir), CONFIG, previous, io)) {
            RiskEvent event = attempt(operation);
            if (event instanceof OrderAcceptedEvent accepted) {
                if (!gateway.evaluate(accepted.proposal()).accepted()) throw new AssertionError("Crash acceptance rejected");
            } else gateway.apply(event);
            writeCheckpoint(dir.resolve("post.checkpoint"), gateway.checkpoint());
            Files.writeString(dir.resolve("post-state"), gateway.state().toString());
            marker(dir, "acknowledged", "SUCCESS");
            marker(dir, "append-boundary", operation + " " + stage);
            input("NEVER_RELEASE");
            throw new AssertionError("Acknowledged kill boundary resumed");
        }
    }

    private record Worker(Process process, Path log, Path dir) { }

    private static Worker launch(Path dir, String mode, String... args) throws IOException {
        String binary = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", binary).toString(),
                "-ea", "-Xmx256m", "-cp", System.getProperty("java.class.path"),
                RiskGatewayProcessTest.class.getName(), "--child", mode, dir.toString()));
        command.addAll(List.of(args));
        Path log = dir.resolve(String.format(Locale.ROOT, "child-%03d-%s.log", ++launches, mode));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        CHILDREN.add(process);
        return new Worker(process, log, dir);
    }

    private static void finish(Worker worker, int expected) throws Exception {
        if (!worker.process().waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("Child timeout: " + worker.log() + "\n" + Files.readString(worker.log()));
        }
        eq(expected, worker.process().exitValue(), "child status: " + worker.log() + "\n" + Files.readString(worker.log()));
    }

    private static void send(Worker worker, String message) throws IOException {
        worker.process().getOutputStream().write((message + "\n").getBytes(StandardCharsets.UTF_8));
        worker.process().getOutputStream().flush();
    }

    private static void awaitMarker(Worker worker, String marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!Files.exists(worker.dir().resolve(marker))) {
            if (!worker.process().isAlive() || System.nanoTime() >= deadline) {
                throw new AssertionError("Child failed to reach " + marker + ": " + worker.log() + "\n" + Files.readString(worker.log()));
            }
            Thread.sleep(10); // Poll IPC, never select a winner or kill point by elapsed time.
        }
    }

    private static void kill(Worker worker) throws Exception {
        worker.process().destroyForcibly();
        require(worker.process().waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), "forced termination completed");
        require(worker.process().exitValue() != 0, "process did not exit normally");
        kills++;
        Files.writeString(worker.dir().resolve("kill-result"), "pid=" + worker.process().pid()
                + " exit=" + worker.process().exitValue() + "\n");
    }

    private static void input(String expected) throws IOException {
        String value = INPUT.readLine();
        if (!expected.equals(value)) throw new IOException("Expected IPC " + expected + ", received " + value);
    }

    private static void marker(Path dir, String name, String value) throws IOException {
        // Atomic publication: existence means the complete marker is visible, including on Windows.
        Path staging = dir.resolve(name + ".tmp");
        Files.writeString(staging, value, StandardOpenOption.CREATE_NEW);
        Files.move(staging, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
    }

    private static void requireLocked(IOException failure) {
        if (!failure.getMessage().contains("already locked by another process")) {
            throw new AssertionError("Expected cross-process lock rejection, got " + failure, failure);
        }
    }

    private static void seed(Path dir) throws Exception {
        finish(launch(dir, "seed"), 0);
        for (int count = 1; count <= history().size(); count++) {
            eq(RiskGateway.restore(CONFIG, history().subList(0, count)).state().toString(),
                    Files.readString(dir.resolve("state-" + count)), "acknowledged child prefix agrees with reference");
        }
    }

    private static void save(DurableRiskGateway gateway, Path dir, String suffix) throws IOException {
        writeCheckpoint(dir.resolve("checkpoint-" + suffix), gateway.checkpoint());
        Files.writeString(dir.resolve("state-" + suffix), gateway.state().toString());
    }

    private static void recover(Path dir, Path checkpoint, List<RiskEvent> expected) throws Exception {
        Path state = expectedState(dir, expected);
        finish(launch(dir, "recover", checkpoint.toString(), state.toString()), 0);
    }

    private static void verify(Path dir, List<RiskEvent> expected) throws Exception {
        finish(launch(dir, "verify", expectedState(dir, expected).toString()), 0);
        eq(expected, RiskGatewayJournal.read(journal(dir)).events(), "every expected event present exactly once");
    }

    private static Path expectedState(Path dir, List<RiskEvent> events) throws IOException {
        Path path = dir.resolve("expected-state");
        Files.writeString(path, RiskGateway.restore(CONFIG, events).state().toString());
        return path;
    }

    private static void rejectRecovery(Path dir, Path checkpoint, String variant) throws Exception {
        byte[] before = Files.exists(journal(dir)) ? journalBytes(dir) : null;
        for (int retry = 0; retry < 2; retry++) finish(launch(dir, "recover-reject", checkpoint.toString(), variant), REJECTED);
        require(before == null ? !Files.exists(journal(dir)) : Arrays.equals(before, journalBytes(dir)),
                "rejected recovery preserves journal bytes");
    }

    private static Path tailCase(Path source, String name, byte[] bytes) throws IOException {
        Path dir = fresh(name);
        Files.write(journal(dir), bytes);
        Files.copy(source.resolve("checkpoint-3"), dir.resolve("checkpoint-3"));
        return dir;
    }

    private static byte[] expectedRow(Path dir, List<RiskEvent> events) throws IOException {
        // Generate a separate reference journal through public serialization; never touch the crash journal.
        Path reference = dir.resolve("reference.journal");
        for (RiskEvent event : events) RiskGatewayJournal.append(reference, event);
        List<String> rows = Files.readAllLines(reference, StandardCharsets.US_ASCII);
        return bytes(rows.getLast() + "\n");
    }

    private static byte[] replaceLast(List<String> rows, String payload) throws Exception {
        String previous = rows.get(1).split("\t")[3];
        String canonical = "INTRA_RISK_GATEWAY_JOURNAL_V1\n3\n" + previous + "\n" + payload + "\n";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(canonical)));
        return bytes(rows.get(0) + "\n" + rows.get(1) + "\n3\t" + previous + "\t" + payload + "\t" + hash + "\n");
    }

    /** Test-only checkpoint codec: full exact configuration and acknowledged verification, no Java serialization. */
    private static void writeCheckpoint(Path path, RecoveryCheckpoint checkpoint) throws IOException {
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            out.writeUTF("RISK_PROCESS_TEST_CHECKPOINT_V1");
            RiskConfiguration config = checkpoint.configuration();
            out.writeUTF(config.currency());
            out.writeUTF(config.maxGrossNotional().toString());
            out.writeUTF(config.maxSuppliedMargin().toString());
            out.writeUTF(config.maxDailyLoss().toString());
            out.writeUTF(config.maximumInputAge().toString());
            out.writeInt(config.instruments().size());
            for (InstrumentRiskSpec spec : new TreeMap<>(config.instruments()).values()) {
                out.writeUTF(spec.instrumentId()); out.writeUTF(spec.currency());
                out.writeUTF(spec.contractMultiplier().toString()); out.writeLong(spec.maxAbsolutePosition());
            }
            out.writeLong(checkpoint.journal().eventCount());
            out.writeUTF(checkpoint.journal().finalHash());
            out.writeUTF(checkpoint.journal().lastEventAt().map(Instant::toString).orElse(""));
        }
    }

    private static RecoveryCheckpoint readCheckpoint(Path path) throws IOException {
        if (Files.size(path) > 8192) throw new IOException("Oversized test checkpoint");
        try (DataInputStream in = new DataInputStream(Files.newInputStream(path))) {
            if (!in.readUTF().equals("RISK_PROCESS_TEST_CHECKPOINT_V1")) throw new IOException("Invalid test checkpoint");
            String currency = in.readUTF();
            BigDecimal gross = bd(in.readUTF()), margin = bd(in.readUTF()), loss = bd(in.readUTF());
            Duration age = Duration.parse(in.readUTF());
            int count = in.readInt();
            if (count < 1 || count > 10) throw new IOException("Invalid test instrument count");
            Map<String, InstrumentRiskSpec> instruments = new TreeMap<>();
            for (int i = 0; i < count; i++) {
                String id = in.readUTF();
                InstrumentRiskSpec spec = new InstrumentRiskSpec(id, in.readUTF(), bd(in.readUTF()), in.readLong());
                if (instruments.put(id, spec) != null) throw new IOException("Duplicate test instrument");
            }
            long events = in.readLong();
            String hash = in.readUTF(), time = in.readUTF();
            if (events < 0 || !hash.matches("[0-9a-f]{64}") || in.read() != -1) throw new IOException("Invalid test checkpoint tail");
            return new RecoveryCheckpoint(new RiskConfiguration(currency, instruments, gross, margin, loss, age),
                    new Verification(events, hash, time.isEmpty() ? Optional.empty() : Optional.of(Instant.parse(time))));
        }
    }

    private static RiskConfiguration configuration(String variant) {
        String currency = variant.equals("currency") ? "USD" : "INR";
        InstrumentRiskSpec a = new InstrumentRiskSpec("A", currency,
                variant.equals("multiplier") ? bd("2") : BigDecimal.ONE, variant.equals("position") ? 20 : 10);
        Map<String, InstrumentRiskSpec> specs = new TreeMap<>(Map.of("A", a));
        if (variant.equals("instruments")) specs.put("B", new InstrumentRiskSpec("B", currency, BigDecimal.ONE, 10));
        return new RiskConfiguration(currency, specs, bd(variant.equals("gross") ? "2000000" : "1000000"),
                bd(variant.equals("margin") ? "2000000" : "1000000"), bd(variant.equals("loss") ? "200" : "100"),
                variant.equals("age") ? Duration.ofHours(2) : Duration.ofHours(1));
    }

    private static SessionStartEvent start() {
        return new SessionStartEvent("start", "S", T, Map.of("A", InstrumentMark.of(bd("100"), margin(0))));
    }
    private static MarginInput margin(int seconds) {
        return new MarginInput("A", bd("10"), "synthetic", "offline-process-fixture", "S", T.plusSeconds(seconds));
    }
    private static OrderProposal proposal(String id, long quantity, int seconds) {
        return new OrderProposal(id, "S", T.plusSeconds(seconds), "A", OrderSide.BUY, quantity,
                Optional.of(bd("100")), Optional.of(margin(seconds)));
    }
    private static ExecutionEvent fill() {
        return new ExecutionEvent("fill", "S", T.plusSeconds(2), "buy", "A", OrderSide.BUY, 1, bd("101"));
    }
    private static List<RiskEvent> history() { return List.of(start(), new OrderAcceptedEvent(proposal("buy", 3, 1)), fill()); }
    private static RiskEvent attempt(String operation) {
        return switch (operation) {
            case "accept" -> new OrderAcceptedEvent(proposal("next", 2, 4));
            case "fill" -> new ExecutionEvent("next-fill", "S", T.plusSeconds(4), "buy", "A", OrderSide.BUY, 1, bd("102"));
            case "cancel" -> new CancellationEvent("cancel", "S", T.plusSeconds(4), "buy");
            case "halt" -> new MarkToMarketEvent("loss", "S", T.plusSeconds(4), Map.of("A", InstrumentMark.of(bd("1"), margin(4))));
            default -> throw new IllegalArgumentException("Unknown crash operation: " + operation);
        };
    }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static String flip(String value) { return (value.charAt(0) == 'a' ? "b" : "a") + value.substring(1); }
    private static Path journal(Path dir) { return dir.resolve("risk.journal"); }
    private static byte[] journalBytes(Path dir) throws IOException { return Files.readAllBytes(journal(dir)); }
    private static Path fresh(String name) throws IOException {
        Path dir = root.resolve(String.format(Locale.ROOT, "%03d-%s", ++cases, name));
        Files.createDirectory(dir);
        return dir;
    }
    private static void run(String name, Checked test) throws Exception { test.run(); groups++; System.out.println("PASS " + name); }
    private static void require(boolean value, String description) { checks++; if (!value) throw new AssertionError(description); }
    private static void eq(Object expected, Object actual, String description) {
        require(Objects.equals(expected, actual), description + ": expected=" + expected + ", actual=" + actual);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
