package com.mscient.intrapaper.simulation.portfolio.risk;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.provenance.*;
import com.mscient.intrapaper.reference.*;
import com.mscient.intrapaper.riskgateway.RiskGateway.*;
import com.mscient.intrapaper.simulation.portfolio.*;
import com.mscient.intrapaper.simulation.portfolio.risk.PortfolioRiskInputs.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Standalone acceptance checks; no test framework, network, account or licensed dataset required. */
public final class PortfolioRiskReplayTest {
    private static final Instant T = Instant.parse("2030-05-06T03:45:00Z");
    private static final String DAY = "2030-05-06";
    private static final Map<String, Integer> LOTS = Map.of("F", 25, "O", 25);
    private static int checks;
    public static void main(String[] args) throws Exception {
        reservationAndSignedFillLifecycle();
        lossHaltAndRiskReduction();
        duplicatesUncertaintyAndRecovery();
        missingStaleAndWrongInputs();
        replayIntegration();
        sessionBoundaries();
        artifacts(Path.of(args.length == 0 ? "." : args[0]));
        System.out.println("Portfolio risk replay: " + checks + " checks passed");
    }
    private static void reservationAndSignedFillLifecycle() {
        var input = inputs("250", "100", 100); var a = new OfflinePortfolioRiskAdapter(input);
        begin(a, T, 0, 0);
        var first = a.evaluate(intent("one", T, "F", 0, 50));
        check(first.accepted(), "risk-increasing proposal accepted");
        check(a.reservations().equals(Map.of("one", 50L)), "full acceptance reserves units");
        check(first.gatewayDecision().orElseThrow().exposure().orElseThrow().projectedSuppliedMargin().compareTo(bd("200")) == 0, "proxy margin counts whole contracts");
        check(!a.evaluate(intent("over", T, "O", 0, 25)).accepted(), "cross-instrument reservation consumes portfolio margin");
        a.execution("partial", "one", T, 25, bd("100"));
        check(a.positions().equals(units(25, 0)) && a.reservations().equals(Map.of("one", 25L)), "partial fill leaves exact residual");
        a.cancel("cancel", "one", T);
        check(a.reservations().isEmpty(), "confirmed cancellation releases residual");
        check(a.state().orders().get("one").filledQuantity() == 1, "cancellation preserves filled contracts");
        check(a.evaluate(intent("close", T, "F", 25, 0)).accepted(), "risk-reducing close accepted");
        a.execution("close-fill", "close", T, 25, bd("100"));
        check(a.positions().equals(units(0, 0)), "signed long close flattens");
        check(a.evaluate(intent("short", T, "O", 0, -25)).accepted(), "short proposal accepted");
        a.execution("short-fill", "short", T, 25, bd("10"));
        check(a.positions().equals(units(0, -25)), "sell execution establishes signed short");
        check(a.evaluate(intent("cover", T, "O", -25, 0)).accepted(), "short reduction accepted");
        a.execution("cover-fill", "cover", T, 25, bd("10"));
        a.assertReconciled(units(0, 0), Map.of()); checks++;
        var cap = new OfflinePortfolioRiskAdapter(input); begin(cap, T, 0, 0);
        check(cap.evaluate(intent("position-cap", T, "F", 0, 100)).reason().equals("POSITION_LIMIT"), "gateway position cap used");
        RiskConfiguration c = new RiskConfiguration("INR", input.configuration().instruments(), bd("100"), bd("1000"), bd("100"), Duration.ofSeconds(60));
        var gross = new OfflinePortfolioRiskAdapter(new PortfolioRiskInputs(c, LOTS, input.frames(), Map.of())); begin(gross, T, 0, 0);
        check(gross.evaluate(intent("gross-cap", T, "F", 0, 25)).reason().equals("GROSS_NOTIONAL_LIMIT"), "gateway gross-notional cap used");
    }
    private static void lossHaltAndRiskReduction() {
        var a = new OfflinePortfolioRiskAdapter(inputs("1000", "100", 90)); begin(a, T, 0, 0);
        a.evaluate(intent("entry", T, "F", 0, 25)); a.execution("entry-fill", "entry", T, 25, bd("100"));
        begin(a, T.plusSeconds(60), 25, 0);
        check(a.state().dailyLossHalted(), "caller marks latch daily-loss halt");
        check(a.evaluate(intent("increase", T.plusSeconds(60), "O", 0, 25)).reason().equals("SESSION_HALTED"), "halt rejects increase");
        check(!a.evaluate(intent("cross-zero", T.plusSeconds(60), "F", 25, -25)).accepted(), "halt rejects reversal through zero");
        var reduce = a.evaluate(intent("reduce", T.plusSeconds(60), "F", 25, 0));
        check(reduce.accepted() && reduce.gatewayDecision().orElseThrow().riskReducingHaltException(), "strict reducing close passes halt exception");
        a.execution("reduce-fill", "reduce", T.plusSeconds(60), 25, bd("90"));
        check(a.state().dailyLossHalted(), "flat state does not clear session loss halt");
    }
    private static void duplicatesUncertaintyAndRecovery() {
        var input = inputs("1000", "100", 100); var a = new OfflinePortfolioRiskAdapter(input); begin(a, T, 0, 0);
        var order = intent("pending", T, "F", 0, 50); var decision = a.evaluate(order); byte[] accepted = a.checkpoint();
        check(a.evaluate(order).equals(decision) && Arrays.equals(accepted, a.checkpoint()), "duplicate proposal is idempotent");
        fails(() -> a.evaluate(intent("pending", T, "F", 0, 25)), "conflicting proposal ID");
        fails(() -> a.execution("bad-fill", "pending", T, 75, bd("100")), "overfill rejected");
        fails(() -> a.execution("bad-fill", "pending", T.plusSeconds(1), 25, bd("100")), "wrong-time fill rejected");
        fails(() -> a.execution("bad-fill", "pending", T, 25, bd("1000")), "risk price ceiling enforced");
        check(Arrays.equals(accepted, a.checkpoint()), "invalid execution leaves checkpoint/state unchanged");
        a.uncertain("unknown", "SIMULATED_ACK_MISSING");
        check(!a.evaluate(intent("blocked", T, "O", 0, 25)).accepted(), "uncertainty blocks even unrelated proposals");
        var restored = restore(input, a);
        check(!restored.uncertainty().isEmpty() && restored.reservations().equals(Map.of("pending", 50L)), "recovery retains uncertainty and reservation");
        check(restored.decisionsCsv().equals(a.decisionsCsv()), "recovery preserves rejected and accepted decisions");
        fails(() -> restored.reconcile("wrong", units(0, 0), Map.of()), "reconciliation cannot erase reservations");
        restored.execution("partial", "pending", T, 25, bd("100"));
        byte[] partial = restored.checkpoint(); restored.execution("partial", "pending", T, 25, bd("100"));
        check(Arrays.equals(partial, restored.checkpoint()), "duplicate execution does not double-fill");
        fails(() -> restored.execution("partial", "pending", T, 50, bd("100")), "conflicting execution ID rejected");
        restored.cancel("cancel", "pending", T); byte[] cancelled = restored.checkpoint(); restored.cancel("cancel", "pending", T);
        check(Arrays.equals(cancelled, restored.checkpoint()), "duplicate cancellation is idempotent");
        restored.reconcile("confirmed", units(25, 0), Map.of());
        check(restored.uncertainty().isEmpty(), "exact explicit reconciliation clears uncertainty");
        check(restore(input, restored).state().equals(restored.state()), "recovery reconstructs exact gateway state");
        byte[] truncated = Arrays.copyOf(cancelled, cancelled.length - 12);
        fails(() -> OfflinePortfolioRiskAdapter.restore(input, truncated, RunConfig.sha256(truncated)), "truncated checkpoint rejected even with replacement digest");
        byte[] tampered = cancelled.clone(); tampered[30] ^= 1;
        fails(() -> OfflinePortfolioRiskAdapter.restore(input, tampered, RunConfig.sha256(cancelled)), "checkpoint digest detects corruption");
        fails(() -> OfflinePortfolioRiskAdapter.restore(inputs("999", "100", 100), cancelled, RunConfig.sha256(cancelled)), "recovery refuses changed risk limits");
        var again = restore(input, a); var rejected = a.decisions().getLast();
        check(again.evaluate(rejected.intent()).equals(rejected), "rejected proposal deduplication survives recovery");
        var reserved = new OfflinePortfolioRiskAdapter(inputs("250", "100", 100)); begin(reserved, T, 0, 0);
        reserved.evaluate(intent("reservation", T, "F", 0, 50)); reserved.uncertain("uncertain", "PENDING_CONFIRMATION");
        reserved.reconcile("still-working", units(0, 0), Map.of("reservation", 50L));
        check(reserved.uncertainty().isEmpty() && reserved.reservations().get("reservation") == 50, "explicit confirmation can retain a working reservation");
        check(reserved.evaluate(intent("still-counted", T, "O", 0, 25)).reason().equals("MARGIN_LIMIT"), "reconciled working reservation still consumes risk");
        begin(reserved, T.plusSeconds(120), 0, 0);
        check(reserved.evaluate(intent("stale-reserve", T.plusSeconds(120), "O", 0, 25)).reason().equals("STALE_MARGIN"), "fresh frame margins never replace stale reserved proxy provenance");
    }
    private static void missingStaleAndWrongInputs() {
        var base = inputs("1000", "100", 100); Sample s = base.frames().get(T).samples().get("F");
        checkRejected(withSample(base, Optional.empty()), "MISSING_MARK", "missing instrument sample");
        checkRejected(withSample(base, Optional.of(new Sample(Optional.empty(), Optional.empty(), "", "", s.margin(), s.buyLimit(), s.sellLimit()))), "MISSING_MARK", "missing mark");
        checkRejected(withSample(base, Optional.of(new Sample(s.mark(), Optional.of(T.minusSeconds(61)), "S", "R", s.margin(), s.buyLimit(), s.sellLimit()))), "STALE_MARK", "stale mark");
        checkRejected(withSample(base, Optional.of(new Sample(s.mark(), Optional.of(T.plusSeconds(1)), "S", "R", s.margin(), s.buyLimit(), s.sellLimit()))), "FUTURE_MARK", "future mark");
        checkRejected(withSample(base, Optional.of(new Sample(s.mark(), s.markAsOf(), "S", "R", Optional.empty(), s.buyLimit(), s.sellLimit()))), "MISSING_MARGIN", "missing margin");
        MarginInput oldMargin = new MarginInput("F", bd("100"), "S", "R", DAY, T.minusSeconds(61));
        checkRejected(withSample(base, Optional.of(new Sample(s.mark(), s.markAsOf(), "S", "R", Optional.of(oldMargin), s.buyLimit(), s.sellLimit()))), "STALE_MARGIN", "stale margin");
        MarginInput futureMargin = new MarginInput("F", bd("100"), "S", "R", DAY, T.plusSeconds(1));
        checkRejected(withSample(base, Optional.of(new Sample(s.mark(), s.markAsOf(), "S", "R", Optional.of(futureMargin), s.buyLimit(), s.sellLimit()))), "INVALID_MARGIN", "future margin");
        checkRejected(withSample(base, Optional.of(new Sample(s.mark(), s.markAsOf(), "S", "R", s.margin(), Optional.empty(), s.sellLimit()))), "MISSING_PRICE", "missing risk price");
        var frames = new TreeMap<>(base.frames()); frames.remove(T);
        checkRejected(new PortfolioRiskInputs(base.configuration(), LOTS, frames, Map.of()), "MISSING_FRAME_INPUTS", "no previous/open-bar defaults");
        var boundary = new OfflinePortfolioRiskAdapter(withSample(base, Optional.of(new Sample(s.mark(), Optional.of(T.minusSeconds(60)), "S", "R", s.margin(), s.buyLimit(), s.sellLimit()))));
        begin(boundary, T, 0, 0); check(boundary.evaluate(intent("inclusive", T, "F", 0, 25)).accepted(), "maximum input age inclusive");
        var mismatch = new OfflinePortfolioRiskAdapter(base); begin(mismatch, T, 25, 0);
        check(!mismatch.evaluate(intent("wrong-pos", T, "F", 25, 50)).accepted(), "caller position mismatch fails closed");
        check(mismatch.positions().equals(units(0, 0)), "caller snapshot never overwrites gateway ledger");
        fails(() -> new OfflinePortfolioRiskAdapter(base).beginFrame(T, DAY, Map.of("F", 0L), LOTS), "missing signed position is not zero-filled");
        fails(() -> new OfflinePortfolioRiskAdapter(base).beginFrame(T, DAY, units(0, 0), Map.of("F", 1, "O", 25)), "explicit risk lot sizes must match dated replay metadata");
        var at = new OfflinePortfolioRiskAdapter(base); begin(at, T, 0, 0);
        fails(() -> at.evaluate(intent("late", T.plusSeconds(60), "F", 0, 25)), "wrong-frame proposal cannot execute");
        check(!base.fingerprint().equals(withSample(base, Optional.of(new Sample(s.mark(), s.markAsOf(), "DIFFERENT_SOURCE", "R", s.margin(), s.buyLimit(), s.sellLimit()))).fingerprint()), "mark provenance changes risk identity");
        var changedMargin = new MarginInput("F", bd("100"), "DIFFERENT_SOURCE", "R", DAY, T);
        check(!base.fingerprint().equals(withSample(base, Optional.of(new Sample(s.mark(), s.markAsOf(), s.markSource(), s.markReference(), Optional.of(changedMargin), s.buyLimit(), s.sellLimit()))).fingerprint()), "margin provenance changes risk identity");
        // A later missing sample cannot leave a prior fresh mark eligible for a new proposal.
        frames = new TreeMap<>(base.frames()); var later = frames.get(T.plusSeconds(60));
        frames.put(later.timestamp(), new FrameInputs(later.timestamp(), DAY, Map.of("O", later.samples().get("O"))));
        var gap = new OfflinePortfolioRiskAdapter(new PortfolioRiskInputs(base.configuration(), LOTS, frames, Map.of()));
        begin(gap, T, 0, 0); gap.evaluate(intent("gap-entry", T, "F", 0, 25)); gap.execution("gap-fill", "gap-entry", T, 25, bd("100"));
        begin(gap, T.plusSeconds(60), 25, 0);
        check(!gap.evaluate(intent("gap-close", T.plusSeconds(60), "F", 25, 0)).accepted(), "reducing intent also needs fresh complete risk inputs");
    }
    private static void replayIntegration() {
        var input = inputs("1000", "100", 100); var a = new OfflinePortfolioRiskAdapter(input);
        var result = replay(a, fillModel((intent, open) -> intent.instrumentId().equals("O") ? Optional.empty()
                : Optional.of(new PortfolioFillModel.Fill(25, open.openPrice(), BigDecimal.ZERO))));
        check(result.orders().getFirst().status() == PortfolioReplayEngine.OrderStatus.PARTIAL, "replay partial IOC surfaced");
        check(result.orders().stream().anyMatch(o -> o.status() == PortfolioReplayEngine.OrderStatus.MISSED), "replay missed IOC surfaced");
        check(a.reservations().isEmpty() && a.positions().equals(units(0, 0)), "all IOC residuals cancelled and ledger reconciled");
        check(a.decisions().size() == result.orders().size(), "each replay order has risk decision");
        check(a.decisions().get(1).before().executedPositions().get("F") == 1, "next proposal binds current signed contracts");
        check(result.fillModelParameters().get("risk_inputs_sha256").equals(input.fingerprint()), "risk inputs appear in replay provenance");
        var b = new OfflinePortfolioRiskAdapter(input);
        var repeated = replay(b, fillModel((intent, open) -> intent.instrumentId().equals("O") ? Optional.empty()
                : Optional.of(new PortfolioFillModel.Fill(25, open.openPrice(), BigDecimal.ZERO))));
        check(result.equals(repeated), "repeated replay is deterministic");
        check(a.decisionsCsv().equals(b.decisionsCsv()) && a.traceCsv().equals(b.traceCsv()) && Arrays.equals(a.checkpoint(), b.checkpoint()), "risk evidence byte deterministic");
        var reject = new OfflinePortfolioRiskAdapter(inputs("1", "100", 100)); int[] calls = {0};
        var denied = replay(reject, fillModel((intent, open) -> { calls[0]++; return Optional.empty(); }));
        check(calls[0] == 0 && denied.executions().isEmpty(), "risk rejection happens before delegate execution");
        check(!denied.replayFingerprintSha256().equals(result.replayFingerprintSha256()), "configured limits change replay ID");
        var uncertain = new OfflinePortfolioRiskAdapter(input);
        fails(() -> replay(uncertain, fillModel((intent, open) -> { throw new IllegalStateException("synthetic lost response"); })), "uncertain delegate aborts replay");
        check(!uncertain.uncertainty().isEmpty() && !uncertain.reservations().isEmpty(), "failed delegate retains reservation and uncertainty");
        check(!restore(input, uncertain).uncertainty().isEmpty(), "failed replay checkpoint retains uncertainty");
        var invalid = new OfflinePortfolioRiskAdapter(input);
        fails(() -> replay(invalid, fillModel((intent, open) -> Optional.of(new PortfolioFillModel.Fill(500, bd("100"), BigDecimal.ZERO)))), "invalid simulated quantity aborts before accounting");
        check(invalid.positions().equals(units(0, 0)) && !invalid.reservations().isEmpty(), "invalid fill cannot mutate gateway positions");
    }
    private static void sessionBoundaries() {
        var a = new OfflinePortfolioRiskAdapter(inputs("1000", "100", 100)); begin(a, T, 0, 0);
        a.evaluate(intent("carry", T, "F", 0, 25)); a.execution("carry-fill", "carry", T, 25, bd("100"));
        Instant next = T.plusSeconds(86400); a.beginFrame(next, "2030-05-07", units(25, 0), LOTS);
        check(a.state().activeSessionId().orElseThrow().equals("2030-05-07") && a.positions().get("F") == 25, "new session preserves signed carry and uses new opening marks");
        check(a.state().dailyProfitLoss().orElseThrow().signum() == 0, "new session rebases gross daily P&L");
        var pending = new OfflinePortfolioRiskAdapter(inputs("1000", "100", 100)); begin(pending, T, 0, 0); pending.evaluate(intent("pending", T, "F", 0, 25));
        pending.beginFrame(next, "2030-05-07", units(0, 0), LOTS);
        check(!pending.evaluate(intent("new-session", next, "O", 0, 25)).accepted(), "unresolved reservation blocks session rollover");
    }
    private static void artifacts(Path project) throws Exception {
        Path fixture = project.resolve("examples/portfolio-risk-replay");
        var input = PortfolioRiskInputs.load(fixture.resolve("limits.csv"), fixture.resolve("instruments.csv"), fixture.resolve("samples.csv"));
        Path output = Files.createTempDirectory("risk-replay-test-");
        var bundle = PortfolioRiskReplayRunner.run(fixture.resolve("inputs.csv"), fixture.resolve("portfolio.properties"), input, output);
        check(PortfolioRiskReplayRunner.verifyArtifact(bundle.path()).equals(bundle), "standalone bundle integrity verifies");
        check(PortfolioRiskReplayRunner.verify(fixture.resolve("inputs.csv"), fixture.resolve("portfolio.properties"), input, bundle.path()).equals(bundle), "standalone deterministic runner regenerates bundle");
        String decisions = Files.readString(bundle.path().resolve("risk-decisions.csv"));
        check(decisions.contains("ACCEPTED") && decisions.contains("MARGIN_LIMIT"), "fixture includes accepted and rejected risk decisions");
        check(Files.readAllLines(bundle.path().resolve("risk-decisions.csv")).size() == 3, "fixture has two risk decisions");
        Files.writeString(bundle.path().resolve("risk-events.csv"), "tampered", StandardOpenOption.APPEND);
        fails(() -> PortfolioRiskReplayRunner.verifyArtifact(bundle.path()), "tampered risk evidence rejected");
        Path bad = output.resolve("missing-limits.csv"); Files.writeString(bad, "currency,max_gross_notional_inr,max_margin_proxy_inr,max_daily_loss_inr,maximum_input_age_seconds\nINR,10000,,100,60\n");
        fails(() -> PortfolioRiskInputs.load(bad, fixture.resolve("instruments.csv"), fixture.resolve("samples.csv")), "missing limits never defaulted");
        System.out.println("Fixture check output: " + output);
    }
    private interface Checked { void run() throws Exception; }
    private static void fails(Checked operation, String label) {
        try { operation.run(); } catch (Exception expected) { checks++; return; }
        throw new AssertionError("Expected failure: " + label);
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    private static Map<String, Long> units(long f, long o) { return Map.of("F", f, "O", o); }
    private static void begin(OfflinePortfolioRiskAdapter a, Instant t, long f, long o) { a.beginFrame(t, DAY, units(f, o), LOTS); }
    private static OfflinePortfolioRiskAdapter restore(PortfolioRiskInputs input, OfflinePortfolioRiskAdapter a) {
        byte[] checkpoint = a.checkpoint(); return OfflinePortfolioRiskAdapter.restore(input, checkpoint, RunConfig.sha256(checkpoint));
    }
    private static PortfolioReplayEngine.OrderIntent intent(String id, Instant t, String instrument, long from, long to) {
        return new PortfolioReplayEngine.OrderIntent(1, id, t, t, instrument, to > from ? PortfolioExecution.Side.BUY : PortfolioExecution.Side.SELL,
                from, to, Math.abs(to - from), "SYNTHETIC_TEST");
    }
    private static PortfolioRiskInputs inputs(String maxMargin, String loss, int nextMark) {
        Map<String, InstrumentRiskSpec> specs = Map.of("F", new InstrumentRiskSpec("F", "INR", bd("25"), 3), "O", new InstrumentRiskSpec("O", "INR", bd("25"), 3));
        RiskConfiguration config = new RiskConfiguration("INR", specs, bd("100000"), bd(maxMargin), bd(loss), Duration.ofSeconds(60));
        Map<Instant, FrameInputs> frames = new TreeMap<>();
        for (long seconds : new long[]{0, 60, 120, 180, 86400}) {
            Instant at = T.plusSeconds(seconds); String session = seconds == 86400 ? "2030-05-07" : DAY;
            Map<String, Sample> samples = new TreeMap<>();
            for (String id : LOTS.keySet()) samples.put(id, new Sample(Optional.of(bd(id.equals("F") ? (seconds == 0 ? "100" : Integer.toString(nextMark)) : "10")),
                    Optional.of(at), "SYNTHETIC_MARK", "fixture", Optional.of(new MarginInput(id, bd("100"), "SYNTHETIC_PROXY", "fixture-margin", session, at)),
                    Optional.of(bd("110")), Optional.of(bd("110"))));
            frames.put(at, new FrameInputs(at, session, samples));
        }
        return new PortfolioRiskInputs(config, LOTS, frames, Map.of());
    }
    private static PortfolioRiskInputs withSample(PortfolioRiskInputs base, Optional<Sample> sample) {
        var frames = new TreeMap<>(base.frames()); var samples = new TreeMap<>(frames.get(T).samples());
        if (sample.isPresent()) samples.put("F", sample.orElseThrow()); else samples.remove("F");
        frames.put(T, new FrameInputs(T, DAY, samples)); return new PortfolioRiskInputs(base.configuration(), LOTS, frames, Map.of());
    }
    private static void checkRejected(PortfolioRiskInputs input, String prefix, String label) {
        var a = new OfflinePortfolioRiskAdapter(input); begin(a, T, 0, 0); var d = a.evaluate(intent("reject", T, "F", 0, 25));
        check(!d.accepted() && d.reason().startsWith(prefix) && a.reservations().isEmpty(), label + ": " + d.reason());
    }
    private interface FillFn { Optional<PortfolioFillModel.Fill> fill(PortfolioReplayEngine.OrderIntent intent, PortfolioFillModel.OpeningMarketPoint open); }
    private static PortfolioFillModel fillModel(FillFn fn) {
        return new PortfolioFillModel() {
            public String id() { return "SYNTHETIC_IOC"; }
            public Map<String, String> parameters() { return Map.of("fixture", "v1"); }
            public Optional<Fill> fill(PortfolioReplayEngine.OrderIntent i, OpeningMarketPoint p) { return fn.fill(i, p); }
        };
    }
    private static PortfolioReplayEngine.Result replay(OfflinePortfolioRiskAdapter a, PortfolioFillModel model) {
        PortfolioStrategy strategy = new PortfolioStrategy() {
            public String id() { return "SYNTHETIC_TARGETS"; }
            public Map<String, String> parameters() { return Map.of(); }
            public PortfolioTarget evaluate(List<SynchronizedBarFrameBuilder.Frame> history, Instant timestamp, PortfolioAccountingLedger.Snapshot portfolio) {
                return new PortfolioTarget(history.size() == 1 ? Map.of("F", 2, "O", 0) : Map.of("F", 0, "O", 1), "SYNTHETIC_TEST");
            }
        };
        List<SynchronizedBarFrameBuilder.Frame> frames = new ArrayList<>();
        for (int n = 0; n < 3; n++) {
            Map<String, List<Bar>> bars = new TreeMap<>();
            for (String id : LOTS.keySet()) {
                BigDecimal price = bd(id.equals("F") ? "100" : "10");
                Bar bar = new Bar(T.plusSeconds(60L * n), LocalDate.parse(DAY), id, "SYN", "DERIV", id, id,
                        ContractKind.FUTURE, "SYNTHETIC", LocalDate.parse("2030-05-30"), Optional.empty(), Optional.empty(),
                        25, bd("0.05"), "ONE_MINUTE", TimestampRole.BAR_START, price, price, price, price, 100L, 100L, "SYNTHETIC", "a".repeat(64));
                bars.put(id, List.of(bar));
            }
            frames.add(SynchronizedBarFrameBuilder.build(bars).getFirst());
        }
        var config = new PortfolioReplayEngine.Config(Map.of("F", 3, "O", 3), PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY,
                CrossSessionPositionPolicy.ALLOW_OVERNIGHT, true);
        return new PortfolioReplayEngine(config, strategy, new PortfolioRiskFillModel(model, a)).run(frames);
    }
}
