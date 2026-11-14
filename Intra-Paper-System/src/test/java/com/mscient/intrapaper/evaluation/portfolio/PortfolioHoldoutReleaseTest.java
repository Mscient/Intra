package com.mscient.intrapaper.evaluation.portfolio;

import com.mscient.intrapaper.evaluation.ResearchTrialLedger;
import com.mscient.intrapaper.provenance.RunConfig;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.mscient.intrapaper.evaluation.portfolio.PortfolioEvaluationJson.object;
import static com.mscient.intrapaper.evaluation.portfolio.PortfolioHoldoutReleaseController.State;

/** Synthetic custody plumbing only. No market data, performance inference, or blindness certification. */
public final class PortfolioHoldoutReleaseTest {
    private static final String SECRET = "SYNTHETIC_HOLDOUT_VALUE_987654321";
    private static final Instant NOW = Instant.parse("2026-11-07T00:00:00Z");
    private static int checks;
    private PortfolioHoldoutReleaseTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        earlyAccessAndFreeze(root);
        authorizationAndOneShot(root);
        changedInputs(root);
        failuresRetriesAndInterruption(root);
        concurrentAccess(root);
        tamperedHistory(root);
        System.out.println("PortfolioHoldoutRelease: " + checks + " synthetic checks passed; independent custody review remains open");
    }

    private static void earlyAccessAndFreeze(Path root) throws Exception {
        Fixture f = new Fixture(root);
        fails(() -> f.controller.evaluate(f.inputs, "early", f::evaluate), "early evaluation");
        fails(() -> f.controller.readResult("early-read"), "early result access");
        fails(() -> f.controller.status("bad\n" + SECRET), "malformed request safely logged");
        require(f.calls.get() == 0, "no early callback");
        require(f.controller.status("before").state() == State.UNFROZEN, "unfrozen after denied access");
        var receipt = f.freeze();
        require(receipt.state() == State.FROZEN, "explicit frozen state");
        require(receipt.binding().equals(f.inputs.capture()), "freeze binds actual public input bytes");
        require(receipt.toJson().contains("\"performance_claim_eligible\": false")
                && receipt.toJson().contains("\"blindness_claim_eligible\": false"), "no eligibility claims");
        fails(() -> f.controller.freeze(f.inputs, "freeze-again"), "repeated freeze");
        fails(() -> f.controller.evaluate(f.inputs, "frozen-early", f::evaluate), "frozen access needs authorization");
        fails(() -> f.controller.readResult("frozen-read"), "frozen result sealed");
        require(f.calls.get() == 0 && !Files.exists(f.result()), "freeze never opens holdout");
        require(!Files.readString(f.audit()).contains(SECRET), "audit omits malformed request plaintext");
        require(Files.readString(f.audit()).contains("DENIED") && !Files.readString(f.history).contains(SECRET), "attempts recorded without values");
        fails(() -> new PortfolioHoldoutReleaseController(f.history, f.service.getPublic(), f.service), "same authorization/history key rejected");
        // Freeze requires every family registration, even when only one candidate is selected for release.
        Fixture omitted = new Fixture(root); omitted.writeCatalog(1);
        fails(omitted::freeze, "incomplete family cannot freeze");
        Fixture badProtocol = new Fixture(root);
        Files.writeString(badProtocol.protocol, "{\"schema\":\"INTRA_HOLDOUT_PROTOCOL_V1\"}");
        fails(badProtocol::freeze, "protocol missing decisions rejected");
    }

    private static void authorizationAndOneShot(Path root) throws Exception {
        Fixture f = new Fixture(root); var freeze = f.freeze();
        var token = f.token(freeze);
        require(PortfolioHoldoutReleaseController.Authorization.decode(token.encode()).equals(token), "authorization roundtrip");
        var wrong = PortfolioHoldoutReleaseController.Authorization.sign(freeze, "wrong-key", "f".repeat(64),
                NOW.plusSeconds(3600), keys().getPrivate());
        fails(() -> f.controller.authorize(f.inputs, wrong), "wrong custodian denied");
        var tampered = new PortfolioHoldoutReleaseController.Authorization(token.authorizationId(), token.freezeRecordSha256(),
                token.binding(), "e".repeat(64), token.expiresAtUtc(), token.signatureBase64());
        fails(() -> f.controller.authorize(f.inputs, tampered), "review reference covered by signature");
        var expired = PortfolioHoldoutReleaseController.Authorization.sign(freeze, "expired", "f".repeat(64), NOW, f.custodian.getPrivate());
        fails(() -> f.controller.authorize(f.inputs, expired), "expired authorization denied");
        require(f.controller.status("still-frozen").state() == State.FROZEN, "rejected authorization permits valid retry");
        require(f.controller.authorize(f.inputs, token).state() == State.AUTHORIZED, "authorized state");
        fails(() -> f.controller.authorize(f.inputs, token), "repeated authorization rejected");
        fails(() -> f.controller.readResult("authorized-read"), "authorization alone does not expose values");
        var receipt = f.controller.evaluate(f.inputs, "evaluation-one", frozen -> {
            f.calls.incrementAndGet();
            // Windows enforces the held history lock on other file handles; inspect the signed head instead.
            String head = Files.readString(f.head(f.history));
            String[] headFields = head.split("\t", -1);
            require(headFields.length == 4 && headFields[1].equals("3") && SignedHoldoutJournal.verify(
                    f.service.getPublic(), String.join("\t", java.util.Arrays.copyOfRange(headFields, 0, 3)), headFields[3]),
                    "evaluation start anchored before access");
            require(frozen.equals(freeze.binding()), "callback receives frozen binding");
            return SECRET.getBytes(StandardCharsets.UTF_8);
        });
        require(receipt.state() == State.CONSUMED && receipt.outcome().equals("SUCCESS"), "successful evaluation consumed");
        require(receipt.resultSha256().equals(RunConfig.sha256(SECRET.getBytes(StandardCharsets.UTF_8))), "result pinned");
        require(!receipt.toJson().contains(SECRET) && !Files.readString(f.history).contains(SECRET)
                && !Files.readString(f.audit()).contains(SECRET), "status and journals contain no values");
        fails(() -> f.controller.evaluate(f.inputs, "evaluation-one", f::evaluate), "same request retry cannot reevaluate");
        fails(() -> f.controller.evaluate(f.inputs, "evaluation-two", f::evaluate), "new request cannot reevaluate");
        fails(() -> f.controller.authorize(f.inputs, token), "second release cannot authorize");
        fails(() -> f.controller.freeze(f.inputs, "new-freeze"), "consumed family cannot refreeze");
        require(f.calls.get() == 1, "exactly one callback");
        require(new String(f.controller.readResult("delivery-one"), StandardCharsets.UTF_8).equals(SECRET), "cached delivery allowed");
        require(new String(f.controller.readResult("delivery-retry"), StandardCharsets.UTF_8).equals(SECRET), "cached delivery retry allowed");
        require(f.calls.get() == 1, "delivery never invokes evaluator");
        var restarted = f.restart();
        require(restarted.status("restart").state() == State.CONSUMED, "consumption survives controller restart");
        fails(() -> restarted.evaluate(f.inputs, "restart-eval", f::evaluate), "restart cannot reevaluate");
        Files.writeString(f.result(), "tampered result");
        fails(() -> restarted.readResult("bad-result"), "tampered cached result denied");

        Fixture delayed = new Fixture(root); var delayedFreeze = delayed.freeze();
        delayed.controller.authorize(delayed.inputs, delayed.token(delayedFreeze));
        delayed.clock.time = NOW.plusSeconds(7200);
        fails(() -> delayed.controller.evaluate(delayed.inputs, "expired-at-start", delayed::evaluate), "expiry checked before holdout read");
        require(delayed.calls.get() == 0, "expired authorization never reads holdout");

        // Even identical public inputs and keys receive a unique freeze nonce.
        Path alternatePath = f.root.resolve("alternate.history");
        var alternate = new PortfolioHoldoutReleaseController(alternatePath, f.custodian.getPublic(), f.service, f.clock);
        var alternateFreeze = alternate.freeze(f.inputs, "freeze-one");
        require(!freeze.freezeRecordSha256().equals(alternateFreeze.freezeRecordSha256()), "freeze nonce binds a unique release");
        fails(() -> alternate.authorize(f.inputs, token), "token replay to another freeze denied");
    }

    private static void changedInputs(Path root) throws Exception {
        for (String field : List.of("catalog", "code", "split", "protocol", "ledger", "data", "candidates")) {
            Fixture f = new Fixture(root); var freeze = f.freeze(); var token = f.token(freeze);
            Path path = switch (field) {
                case "code" -> f.code; case "split" -> f.split; case "protocol" -> f.protocol;
                case "ledger" -> f.ledger; default -> f.catalog;
            };
            byte[] original = Files.readAllBytes(path);
            mutate(f, field);
            fails(() -> f.controller.authorize(f.inputs, token), "changed " + field + " cannot authorize");
            Files.write(path, original);
            f.controller.authorize(f.inputs, token);
            mutate(f, field);
            fails(() -> f.controller.evaluate(f.inputs, "changed-" + field, f::evaluate), "changed " + field + " cannot evaluate");
            require(f.calls.get() == 0, "changed " + field + " never reads holdout");
            Files.write(path, original);
            require(f.controller.evaluate(f.inputs, "corrected-" + field, f::evaluate).state() == State.CONSUMED,
                    "pre-access correction retry for " + field);
            require(f.calls.get() == 1, "correction permits only one access for " + field);
        }
        Fixture f = new Fixture(root); var freeze = f.freeze(); f.controller.authorize(f.inputs, f.token(freeze));
        fails(() -> f.controller.evaluate(f.inputs, "during-change", binding -> {
            f.calls.incrementAndGet(); Files.writeString(f.code, "changed while evaluating");
            return SECRET.getBytes(StandardCharsets.UTF_8);
        }), "change during evaluation consumes and withholds result");
        require(f.controller.status("changed-consumed").state() == State.CONSUMED && !Files.exists(f.result()),
                "changed in-flight inputs cannot publish");
        fails(() -> f.controller.evaluate(f.inputs, "changed-retry", f::evaluate), "in-flight failure cannot retry");
        require(f.calls.get() == 1, "in-flight failure reads once");
    }

    private static void mutate(Fixture f, String field) throws Exception {
        switch (field) {
            case "catalog" -> Files.writeString(f.catalog, Files.readString(f.catalog) + "\n");
            case "code" -> Files.writeString(f.code, "synthetic code changed");
            case "split" -> Files.writeString(f.split, Files.readString(f.split).replace("\n", "\r\n"));
            case "protocol" -> f.writeProtocol("candidate-b");
            case "ledger" -> ResearchTrialLedger.register(f.ledger, new ResearchTrialLedger.Trial("unrelated", "other", "v1", "v1",
                    "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64)));
            case "data" -> Files.writeString(f.catalog, Files.readString(f.catalog).replace("a".repeat(64), "b".repeat(64)));
            case "candidates" -> Files.writeString(f.catalog, Files.readString(f.catalog).replace("5".repeat(64), "6".repeat(64)));
            default -> throw new AssertionError(field);
        }
    }

    private static void failuresRetriesAndInterruption(Path root) throws Exception {
        Fixture before = new Fixture(root); var freeze = before.freeze(); before.controller.authorize(before.inputs, before.token(freeze));
        Files.writeString(before.result(), "existing output");
        fails(() -> before.controller.evaluate(before.inputs, "preflight", before::evaluate), "output preflight denied before read");
        require(before.calls.get() == 0 && before.controller.status("preflight-state").state() == State.AUTHORIZED,
                "preflight error does not spend release");
        Files.delete(before.result());
        require(before.controller.evaluate(before.inputs, "preflight-retry", before::evaluate).state() == State.CONSUMED,
                "safe retry before start");

        Fixture failed = new Fixture(root); var ff = failed.freeze(); failed.controller.authorize(failed.inputs, failed.token(ff));
        IOException error = fails(() -> failed.controller.evaluate(failed.inputs, "failing", binding -> {
            failed.calls.incrementAndGet(); throw new IOException(SECRET);
        }), "adapter failure consumes");
        require(!error.getMessage().contains(SECRET) && !Files.readString(failed.audit()).contains(SECRET), "failure details redacted");
        var failedReceipt = failed.controller.status("failure-state");
        require(failedReceipt.state() == State.CONSUMED && failedReceipt.outcome().equals("FAILED"), "failure state terminal");
        fails(() -> failed.restart().evaluate(failed.inputs, "failure-retry", failed::evaluate), "failed release cannot retry after restart");
        fails(() -> failed.controller.readResult("failure-read"), "failed result unavailable");
        require(failed.calls.get() == 1, "failure only one read");

        Fixture interrupted = new Fixture(root); var fi = interrupted.freeze(); interrupted.controller.authorize(interrupted.inputs, interrupted.token(fi));
        try {
            interrupted.controller.evaluate(interrupted.inputs, "crashed", binding -> {
                interrupted.calls.incrementAndGet(); throw new SimulatedTermination();
            });
            throw new AssertionError("termination must propagate");
        } catch (SimulatedTermination expected) { checks++; }
        var restarted = interrupted.restart();
        require(restarted.status("interrupted-state").state() == State.EVALUATING, "termination leaves durable evaluating state");
        fails(() -> restarted.evaluate(interrupted.inputs, "crashed", interrupted::evaluate), "same interrupted request cannot resume");
        fails(() -> restarted.evaluate(interrupted.inputs, "crashed-new", interrupted::evaluate), "interrupted request cannot be replaced");
        fails(() -> restarted.consumeInterrupted(interrupted.inputs, "wrong-id"), "closure binds evaluation ID");
        var closed = restarted.consumeInterrupted(interrupted.inputs, "crashed");
        require(closed.state() == State.CONSUMED && closed.outcome().equals("INTERRUPTED"), "interrupted release closed without evaluation");
        fails(() -> restarted.consumeInterrupted(interrupted.inputs, "crashed"), "closure cannot repeat");
        require(interrupted.calls.get() == 1, "interrupted release only one callback");

        Fixture uncertain = new Fixture(root); var fu = uncertain.freeze(); uncertain.controller.authorize(uncertain.inputs, uncertain.token(fu));
        Files.writeString(uncertain.head(uncertain.history).resolveSibling("release.history.head.pending"), "uncertain staged write");
        fails(() -> uncertain.controller.evaluate(uncertain.inputs, "uncertain-write", uncertain::evaluate), "uncertain state-head write denies before callback");
        fails(() -> uncertain.restart().evaluate(uncertain.inputs, "uncertain-retry", uncertain::evaluate), "uncertain state cannot retry");
        require(uncertain.calls.get() == 0, "no access before all durable start writes complete");

        Fixture rollback = new Fixture(root); var fr = rollback.freeze(); rollback.controller.authorize(rollback.inputs, rollback.token(fr));
        byte[] oldHistory = Files.readAllBytes(rollback.history), oldHead = Files.readAllBytes(rollback.head(rollback.history));
        try {
            rollback.controller.evaluate(rollback.inputs, "terminated-for-rollback", binding -> {
                rollback.calls.incrementAndGet(); throw new SimulatedTermination();
            });
            throw new AssertionError("termination must propagate");
        } catch (SimulatedTermination expected) { checks++; }
        Files.write(rollback.history, oldHistory); Files.write(rollback.head(rollback.history), oldHead);
        IOException rejection = fails(() -> rollback.restart().evaluate(rollback.inputs, "rollback-interrupted", rollback::evaluate),
                "intact audit detects rollback of interrupted state and matching head");
        require(rejection.getMessage().contains("HISTORY_ROLLBACK") && rollback.calls.get() == 1,
                "durable audit start anchors interrupted evaluation");
    }

    private static void concurrentAccess(Path root) throws Exception {
        Fixture f = new Fixture(root); var freeze = f.freeze(); f.controller.authorize(f.inputs, f.token(freeze));
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { f.controller.evaluate(f.inputs, "concurrent-one", binding -> {
                f.calls.incrementAndGet(); started.countDown();
                if (!finish.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("test timeout");
                return SECRET.getBytes(StandardCharsets.UTF_8);
            }); } catch (Throwable failure) { error.set(failure); }
        });
        worker.start();
        require(started.await(10, java.util.concurrent.TimeUnit.SECONDS), "concurrent evaluation entered");
        try { fails(() -> f.restart().evaluate(f.inputs, "concurrent-two", f::evaluate), "competing writer fails closed"); }
        finally { finish.countDown(); worker.join(10000); }
        require(!worker.isAlive() && error.get() == null && f.calls.get() == 1, "one callback under contention");
        fails(() -> f.restart().evaluate(f.inputs, "concurrent-three", f::evaluate), "post-lock repeated evaluation denied");
    }

    private static void tamperedHistory(Path root) throws Exception {
        for (String mode : List.of("edit", "rehash", "truncate", "head", "missing-head", "signed-transition", "audit", "audit-truncate", "partial",
                "restore-state-and-head", "delete-state-and-head")) {
            Fixture f = new Fixture(root); var freeze = f.freeze(); f.controller.authorize(f.inputs, f.token(freeze));
            byte[] authorizedHistory = Files.readAllBytes(f.history), authorizedHead = Files.readAllBytes(f.head(f.history));
            f.controller.evaluate(f.inputs, "one", f::evaluate);
            String original = Files.readString(f.history);
            switch (mode) {
                case "edit" -> Files.writeString(f.history, original.replace("FREEZE", "FROZEN"));
                case "rehash" -> Files.writeString(f.history, rehash(original.replace("FREEZE", "FROZEN")));
                case "truncate" -> Files.writeString(f.history, original.substring(0, original.lastIndexOf('\n', original.length() - 2) + 1));
                case "head" -> Files.writeString(f.head(f.history), "tampered anchor");
                case "missing-head" -> Files.delete(f.head(f.history));
                case "signed-transition" -> forgeImpossibleTransition(f);
                case "audit" -> Files.writeString(f.audit(), Files.readString(f.audit()).replace("ALLOWED", "DENIED"));
                case "audit-truncate" -> {
                    String audit = Files.readString(f.audit());
                    Files.writeString(f.audit(), audit.substring(0, audit.lastIndexOf('\n', audit.length() - 2) + 1));
                }
                case "partial" -> Files.writeString(f.history, original.substring(0, original.length() - 4));
                case "restore-state-and-head" -> { Files.write(f.history, authorizedHistory); Files.write(f.head(f.history), authorizedHead); }
                case "delete-state-and-head" -> { Files.delete(f.history); Files.delete(f.head(f.history)); }
                default -> throw new AssertionError(mode);
            }
            IOException rejection = fails(() -> f.restart().status("tampered-status"), mode + " history cannot be trusted");
            if (mode.equals("signed-transition")) require(rejection.getMessage().contains("HISTORY_EVALUATE"), "signed history must obey state transitions");
            if (mode.endsWith("state-and-head")) require(rejection.getMessage().contains("HISTORY_ROLLBACK"), "intact audit anchors restored/deleted state");
            fails(() -> f.restart().evaluate(f.inputs, "tampered-eval", f::evaluate), mode + " history cannot authorize new access");
            require(f.calls.get() == 1, mode + " cannot trigger callback");
            if (!mode.startsWith("audit")) require(Files.readString(f.audit()).contains("tampered-status\tDENIED"), "corrupted state attempt logged: " + mode);
        }
        Fixture f = new Fixture(root); f.freeze();
        var replacedKeys = new PortfolioHoldoutReleaseController(f.history, f.custodian.getPublic(), keys(), f.clock);
        fails(() -> replacedKeys.status("substitute-key"), "journal verification key substitution rejected");
        var replacedAuthority = new PortfolioHoldoutReleaseController(f.history, keys().getPublic(), f.service, f.clock);
        fails(() -> replacedAuthority.status("substitute-authority"), "authorization key bound to freeze");
    }

    private static String rehash(String text) {
        String[] lines = text.split("\n", -1); String previous = "0".repeat(64);
        for (int i = 1; i < lines.length - 1; i++) {
            String[] fields = lines[i].split("\t", -1); int tail = fields.length - 3;
            fields[tail] = previous;
            String signed = String.join("\t", java.util.Arrays.copyOfRange(fields, 0, tail + 1));
            fields[tail + 2] = HoldoutReleaseInputs.digest(signed + "\t" + fields[tail + 1]);
            previous = fields[tail + 2]; lines[i] = String.join("\t", fields);
        }
        return String.join("\n", lines);
    }

    private static void forgeImpossibleTransition(Fixture f) throws Exception {
        // Re-signed syntactically valid history still must obey the state machine.
        String domain = "INTRA_HOLDOUT_RELEASE_HISTORY_V1";
        try (FileChannel channel = FileChannel.open(f.history, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var original = SignedHoldoutJournal.read(channel, domain, f.service.getPublic());
            channel.truncate(0);
            List<SignedHoldoutJournal.Row> rows = new ArrayList<>();
            var first = SignedHoldoutJournal.append(channel, domain, rows, original.getFirst().payload(), f.service.getPrivate(), NOW); rows.add(first);
            var bad = SignedHoldoutJournal.append(channel, domain, rows, original.get(2).payload(), f.service.getPrivate(), NOW);
            String payload = domain + "\t" + bad.sequence() + "\t" + bad.hash();
            Files.writeString(f.head(f.history), payload + "\t" + SignedHoldoutJournal.sign(f.service.getPrivate(), payload));
        }
        // Retain the authentic freeze audit, so the impossible signed transition reaches semantic verification.
        String auditDomain = "INTRA_HOLDOUT_ACCESS_HISTORY_V1";
        try (FileChannel auditChannel = FileChannel.open(f.audit(), StandardOpenOption.READ)) {
            var audit = SignedHoldoutJournal.read(auditChannel, auditDomain, f.service.getPublic());
            String[] lines = Files.readString(f.audit()).split("\n", -1);
            Files.writeString(f.audit(), String.join("\n", java.util.Arrays.copyOfRange(lines, 0, 3)) + "\n");
            var freezeDecision = audit.get(1);
            String payload = auditDomain + "\t" + freezeDecision.sequence() + "\t" + freezeDecision.hash();
            Files.writeString(f.head(f.audit()), payload + "\t" + SignedHoldoutJournal.sign(f.service.getPrivate(), payload));
        }
    }

    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static IOException fails(Checked action, String message) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return expected; }
        throw new AssertionError("Expected rejection: " + message);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
    private static KeyPair keys() throws Exception { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
    private static final class SimulatedTermination extends Error { }
    private static final class MutableClock extends Clock {
        Instant time = NOW;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time; }
    }

    private static final class Fixture {
        final Path root, ledger, catalog, code, split, protocol, history;
        final KeyPair custodian = keys(), service = keys();
        final MutableClock clock = new MutableClock();
        final AtomicInteger calls = new AtomicInteger();
        final List<ResearchTrialLedger.Entry> registrations = new ArrayList<>();
        final HoldoutReleaseInputs inputs;
        final PortfolioHoldoutReleaseController controller;
        Fixture(Path outputRoot) throws Exception {
            root = Files.createTempDirectory(outputRoot, "fixture-");
            ledger = root.resolve("trials.ledger"); catalog = root.resolve("catalog.json"); code = root.resolve("reviewed-code.jar");
            split = root.resolve("split.csv"); protocol = root.resolve("protocol.json"); history = root.resolve("release.history");
            Files.writeString(code, "synthetic frozen code artifact; no profitability claim");
            Files.writeString(split, "session_date,partition\n2026-01-01,DEVELOPMENT\n2026-01-02,VALIDATION\n2026-01-03,HOLDOUT\n");
            String splitHash = PortfolioTrialIdentity.fileSha256(split);
            for (String id : List.of("candidate-a", "candidate-b")) registrations.add(ResearchTrialLedger.register(ledger,
                    new ResearchTrialLedger.Trial(id, "synthetic", "v1", id, "0".repeat(64), "a".repeat(64), splitHash, "5".repeat(64))));
            writeCatalog(2); writeProtocol("candidate-a");
            inputs = new HoldoutReleaseInputs(ledger, catalog, code, split, protocol);
            controller = restart();
        }
        void writeCatalog(int count) throws Exception {
            var trials = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                var registration = registrations.get(i); var t = registration.trial();
                trials.add(object("trial_id", t.trialId(), "strategy_id", t.strategyId(), "strategy_version", t.strategyVersion(),
                        "variant_id", t.variantId(), "strategy_card_sha256", t.strategyCardSha256(), "dataset_sha256", t.datasetSha256(),
                        "split_plan_sha256", t.splitPlanSha256(), "parameter_config_sha256", t.parameterConfigSha256(),
                        "registration_record_sha256", registration.recordHashSha256(), "run_id", (i == 0 ? "c" : "d").repeat(64),
                        "run_manifest_sha256", "e".repeat(64), "split_plan_csv", "split.csv", "starting_capital_inr", "100000"));
            }
            Files.writeString(catalog, PortfolioEvaluationJson.write(object("schema", PortfolioTrialCatalog.SCHEMA, "trials", trials)));
        }
        void writeProtocol(String selected) throws Exception {
            Files.writeString(protocol, PortfolioEvaluationJson.write(object("schema", "INTRA_HOLDOUT_PROTOCOL_V1",
                    "selected_trial_ids", List.of(selected), "evaluation_spec", "Synthetic single callback; do not infer profitability",
                    "failure_policy", "CONSUME_ON_START_NO_RETRY")));
        }
        PortfolioHoldoutReleaseController restart() throws IOException { return new PortfolioHoldoutReleaseController(history, custodian.getPublic(), service, clock); }
        PortfolioHoldoutReleaseController.Receipt freeze() throws IOException { return controller.freeze(inputs, "freeze-one"); }
        PortfolioHoldoutReleaseController.Authorization token(PortfolioHoldoutReleaseController.Receipt frozen) throws IOException {
            return PortfolioHoldoutReleaseController.Authorization.sign(frozen, "authorization-one", "f".repeat(64), NOW.plusSeconds(3600), custodian.getPrivate());
        }
        byte[] evaluate(HoldoutReleaseInputs.Binding binding) { calls.incrementAndGet(); return SECRET.getBytes(StandardCharsets.UTF_8); }
        Path audit() { return root.resolve("release.history.access"); }
        Path result() { return root.resolve("release.history.result"); }
        Path head(Path path) { return path.resolveSibling(path.getFileName() + ".head"); }
    }
}
