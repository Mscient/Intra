package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.data.SynchronizedBarFrameBuilder;
import com.mscient.intrapaper.provenance.CrossSessionPositionPolicy;
import com.mscient.intrapaper.reference.ContractKind;
import com.mscient.intrapaper.reference.OptionRight;
import com.mscient.intrapaper.reference.TimestampRole;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Focused checks for deterministic next-frame-open portfolio replay. */
public final class PortfolioReplayEngineTest {
    private static final String HASH = "c".repeat(64);
    private static final LocalDate DAY_ONE = LocalDate.of(2026, 11, 3);
    private static final LocalDate DAY_TWO = LocalDate.of(2026, 11, 4);

    private PortfolioReplayEngineTest() { }

    public static void main(String[] args) {
        executesNextFrameOpenWithoutPassingFutureOhlcToFillModel();
        handlesPartialAndMissedIocOutcomes();
        cancelsOrCarriesPendingTargetsOnlyByExplicitPolicy();
        rejectsMissingUnknownAndOverLimitTargets();
        enforcesFlatSessionAndDataBoundaries();
        rejectsBarEndAndUnapprovedIntrasessionGaps();
        fingerprintsAndSequenceAssignmentAreDeterministic();
        System.out.println("PortfolioReplayEngine checks passed");
    }

    private static void executesNextFrameOpenWithoutPassingFutureOhlcToFillModel() {
        List<SynchronizedBarFrameBuilder.Frame> frames = frames(DAY_ONE,
                "2026-11-03T03:45:00Z", "2026-11-03T03:46:00Z", "2026-11-03T03:47:00Z");
        List<Integer> observedHistory = new ArrayList<>();
        PortfolioStrategy strategy = strategy(index -> {
            observedHistory.add(index + 1);
            return target(index >= 0 ? 1 : 0);
        });
        PortfolioFillModel fillModel = fillModel((intent, open) -> {
            require(open.timestamp().equals(intent.executionTimestamp()), "fill view must share scheduled open time");
            require(open.openPrice().compareTo(new BigDecimal("100.0")) == 0,
                    "fill model sees only the opening price input");
            return Optional.of(new PortfolioFillModel.Fill(intent.requestedUnits(), open.openPrice(), new BigDecimal("1.0")));
        });

        PortfolioReplayEngine.Result result = engine(strategy, fillModel, Map.of("FUT", 2, "CALL", 2),
                PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY,
                CrossSessionPositionPolicy.ALLOW_OVERNIGHT, true).run(frames);
        require(observedHistory.equals(List.of(1, 2)), "strategy must receive only the growing history prefix");
        require(result.orders().size() == 1 && result.orders().getFirst().status() == PortfolioReplayEngine.OrderStatus.FILLED,
                "target must execute at the next eligible frame");
        require(result.orders().getFirst().intent().decisionTimestamp().equals(Instant.parse("2026-11-03T03:46:00Z")),
                "decision must occur after the current BAR_START interval closes");
        require(result.orders().getFirst().intent().executionTimestamp().equals(Instant.parse("2026-11-03T03:46:00Z")),
                "next-frame open must be the fill timestamp");
        require(result.snapshots().get(2).positions().get("FUT").signedUnits() == 25,
                "filled position must appear in portfolio snapshot");
        require(result.snapshots().get(2).netPnlInr().compareTo(new BigDecimal("-1.0")) == 0,
                "caller-supplied fill fee must reduce net P&L");
        require(!result.performanceClaimEligible(), "portfolio replay must remain diagnostic-only");
    }

    private static void handlesPartialAndMissedIocOutcomes() {
        List<SynchronizedBarFrameBuilder.Frame> frames = frames(DAY_ONE,
                "2026-11-03T03:45:00Z", "2026-11-03T03:46:00Z", "2026-11-03T03:47:00Z");
        PortfolioStrategy twoLots = strategy(index -> target(index >= 0 ? 2 : 0));
        PortfolioFillModel oneLot = fillModel((intent, open) -> Optional.of(new PortfolioFillModel.Fill(
                open.lotSize(), open.openPrice(), BigDecimal.ZERO)));
        var partial = engine(twoLots, oneLot, Map.of("FUT", 2, "CALL", 2),
                PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY,
                CrossSessionPositionPolicy.ALLOW_OVERNIGHT, true).run(frames);
        require(partial.orders().getFirst().status() == PortfolioReplayEngine.OrderStatus.PARTIAL,
                "partial IOC fill must be surfaced as partial");
        require(partial.snapshots().get(2).positions().get("FUT").signedUnits() == 25,
                "partial fill must update only the filled units");

        PortfolioFillModel miss = fillModel((intent, open) -> Optional.empty());
        var missed = engine(strategy(index -> target(index >= 0 ? 1 : 0)), miss, Map.of("FUT", 2, "CALL", 2),
                PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY,
                CrossSessionPositionPolicy.ALLOW_OVERNIGHT, true).run(frames);
        require(missed.orders().getFirst().status() == PortfolioReplayEngine.OrderStatus.MISSED,
                "empty fill-model response must be an IOC miss");
        require(missed.snapshots().get(2).positions().get("FUT").signedUnits() == 0,
                "missed fill must not change position");
    }

    private static void cancelsOrCarriesPendingTargetsOnlyByExplicitPolicy() {
        List<SynchronizedBarFrameBuilder.Frame> frames = List.of(
                frame(DAY_ONE, "2026-11-03T03:45:00Z"),
                frame(DAY_ONE, "2026-11-03T03:46:00Z"),
                frame(DAY_TWO, "2026-11-04T03:45:00Z"));
        PortfolioStrategy enterOnlyAtPriorClose = strategy(index -> target(index == 1 ? 1 : 0));
        PortfolioFillModel fullFill = fillModel((intent, open) -> Optional.of(new PortfolioFillModel.Fill(
                intent.requestedUnits(), open.openPrice(), BigDecimal.ZERO)));

        var cancelled = engine(enterOnlyAtPriorClose, fullFill, Map.of("FUT", 2, "CALL", 2),
                PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY,
                CrossSessionPositionPolicy.ALLOW_OVERNIGHT, true).run(frames);
        require(cancelled.decisions().getLast().status() == PortfolioReplayEngine.DecisionStatus.CANCELLED_AT_SESSION_BOUNDARY,
                "pending target must be explicitly cancelled across a session boundary");
        require(cancelled.orders().isEmpty(), "cancelled pending decision must not produce an order intent");

        var carried = engine(enterOnlyAtPriorClose, fullFill, Map.of("FUT", 2, "CALL", 2),
                PortfolioReplayEngine.PendingIntentBoundaryPolicy.ALLOW_NEXT_SESSION_OPEN,
                CrossSessionPositionPolicy.ALLOW_OVERNIGHT, true).run(frames);
        require(carried.orders().size() == 1, "explicit allow policy must execute at the next session open");
        require(carried.orders().getFirst().intent().executionTimestamp().equals(Instant.parse("2026-11-04T03:45:00Z")),
                "cross-session intent must execute at the actual next frame timestamp");
    }

    private static void rejectsMissingUnknownAndOverLimitTargets() {
        List<SynchronizedBarFrameBuilder.Frame> frames = frames(DAY_ONE,
                "2026-11-03T03:45:00Z", "2026-11-03T03:46:00Z");
        expectFailure(() -> engine(strategy(index -> new PortfolioTarget(Map.of("FUT", 1), "missing")),
                fillModel((intent, open) -> Optional.empty()), Map.of("FUT", 2, "CALL", 2),
                PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY,
                CrossSessionPositionPolicy.ALLOW_OVERNIGHT, true).run(frames),
                "target must include every instrument");
        expectFailure(() -> engine(strategy(index -> new PortfolioTarget(Map.of("FUT", 1, "CALL", 0, "OTHER", 0), "unknown")),
                fillModel((intent, open) -> Optional.empty()), Map.of("FUT", 2, "CALL", 2),
                PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY,
                CrossSessionPositionPolicy.ALLOW_OVERNIGHT, true).run(frames),
                "target must not include unknown instruments");
        expectFailure(() -> engine(strategy(index -> target(3)), fillModel((intent, open) -> Optional.empty()),
                Map.of("FUT", 2, "CALL", 2), PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY,
                CrossSessionPositionPolicy.ALLOW_OVERNIGHT, true).run(frames),
                "target must obey explicit caller position limits");
    }

    private static void enforcesFlatSessionAndDataBoundaries() {
        List<SynchronizedBarFrameBuilder.Frame> frames = frames(DAY_ONE,
                "2026-11-03T03:45:00Z", "2026-11-03T03:46:00Z");
        expectFailure(() -> engine(strategy(index -> target(1)),
                fillModel((intent, open) -> Optional.of(new PortfolioFillModel.Fill(
                        intent.requestedUnits(), open.openPrice(), BigDecimal.ZERO))),
                Map.of("FUT", 2, "CALL", 2), PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY,
                CrossSessionPositionPolicy.REQUIRE_FLAT, true).run(frames),
                "REQUIRE_FLAT must reject a residual position at the final data boundary");
    }

    private static void rejectsBarEndAndUnapprovedIntrasessionGaps() {
        List<SynchronizedBarFrameBuilder.Frame> gapFrames = List.of(
                frame(DAY_ONE, "2026-11-03T03:45:00Z"), frame(DAY_ONE, "2026-11-03T03:47:00Z"));
        expectFailure(() -> engine(strategy(index -> target(0)), fillModel((intent, open) -> Optional.empty()),
                Map.of("FUT", 2, "CALL", 2), PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY,
                CrossSessionPositionPolicy.ALLOW_OVERNIGHT, true).run(gapFrames),
                "irregular in-session gaps must fail when contiguous frames are required");

        List<SynchronizedBarFrameBuilder.Frame> overlappingFrames = List.of(
                frame(DAY_ONE, "2026-11-03T03:45:00Z"), frame(DAY_ONE, "2026-11-03T03:45:30Z"));
        expectFailure(() -> engine(strategy(index -> target(0)), fillModel((intent, open) -> Optional.empty()),
                Map.of("FUT", 2, "CALL", 2), PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY,
                CrossSessionPositionPolicy.ALLOW_OVERNIGHT, false).run(overlappingFrames),
                "BAR_START frames must not overlap the preceding interval even when gaps are allowed");

        List<SynchronizedBarFrameBuilder.Frame> barEndFrames = List.of(
                frame(DAY_ONE, "2026-11-03T03:45:00Z", TimestampRole.BAR_END),
                frame(DAY_ONE, "2026-11-03T03:46:00Z", TimestampRole.BAR_END));
        expectFailure(() -> engine(strategy(index -> target(0)), fillModel((intent, open) -> Optional.empty()),
                Map.of("FUT", 2, "CALL", 2), PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY,
                CrossSessionPositionPolicy.ALLOW_OVERNIGHT, true).run(barEndFrames),
                "BAR_END must fail until next-open timestamp semantics are implemented");
    }

    private static void fingerprintsAndSequenceAssignmentAreDeterministic() {
        List<SynchronizedBarFrameBuilder.Frame> frames = frames(DAY_ONE,
                "2026-11-03T03:45:00Z", "2026-11-03T03:46:00Z");
        PortfolioStrategy strategy = strategy(index -> target(1));
        PortfolioFillModel model = fillModel((intent, open) -> Optional.of(new PortfolioFillModel.Fill(
                intent.requestedUnits(), open.openPrice(), BigDecimal.ZERO)));
        PortfolioReplayEngine engine = engine(strategy, model, Map.of("FUT", 2, "CALL", 2),
                PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY,
                CrossSessionPositionPolicy.ALLOW_OVERNIGHT, true);
        var first = engine.run(frames);
        var second = engine.run(frames);
        require(first.replayFingerprintSha256().equals(second.replayFingerprintSha256()),
                "same frozen inputs and component identities must produce the same replay fingerprint");
        require(first.executions().getFirst().executionId().equals("PX-1")
                && first.executions().getFirst().sequence() == 1,
                "execution identity and sequence assignment must be deterministic");
    }

    private static PortfolioReplayEngine engine(PortfolioStrategy strategy, PortfolioFillModel fillModel,
            Map<String, Integer> maxLots, PortfolioReplayEngine.PendingIntentBoundaryPolicy pendingPolicy,
            CrossSessionPositionPolicy positionPolicy, boolean requireContiguous) {
        return new PortfolioReplayEngine(new PortfolioReplayEngine.Config(maxLots, pendingPolicy,
                positionPolicy, requireContiguous), strategy, fillModel);
    }

    private static PortfolioTarget target(int futureLots) {
        return new PortfolioTarget(Map.of("FUT", futureLots, "CALL", 0), "fixture decision");
    }

    private static PortfolioStrategy strategy(java.util.function.IntFunction<PortfolioTarget> byLastIndex) {
        return new PortfolioStrategy() {
            @Override public String id() { return "FIXTURE_PORTFOLIO_STRATEGY"; }
            @Override public Map<String, String> parameters() { return Map.of("fixture", "true"); }
            @Override public PortfolioTarget evaluate(List<SynchronizedBarFrameBuilder.Frame> availableFrames,
                    Instant decisionTimestamp, PortfolioAccountingLedger.Snapshot completedBarSnapshot) {
                require(completedBarSnapshot.phase() == PortfolioAccountingLedger.MarkPhase.BAR_CLOSE,
                        "strategy must receive a completed-bar account snapshot");
                require(completedBarSnapshot.timestamp().equals(decisionTimestamp),
                        "decision time must match the completed-bar mark");
                return byLastIndex.apply(availableFrames.size() - 1);
            }
        };
    }

    @FunctionalInterface
    private interface FillFunction {
        Optional<PortfolioFillModel.Fill> fill(PortfolioReplayEngine.OrderIntent intent,
                PortfolioFillModel.OpeningMarketPoint open);
    }

    private static PortfolioFillModel fillModel(FillFunction function) {
        return new PortfolioFillModel() {
            @Override public String id() { return "FIXTURE_OPEN_FILL"; }
            @Override public Map<String, String> parameters() { return Map.of("fixture", "true"); }
            @Override public Optional<Fill> fill(PortfolioReplayEngine.OrderIntent intent,
                    OpeningMarketPoint marketAtOpen) { return function.fill(intent, marketAtOpen); }
        };
    }

    private static List<SynchronizedBarFrameBuilder.Frame> frames(LocalDate day, String... timestamps) {
        List<Bar> futures = new ArrayList<>();
        List<Bar> options = new ArrayList<>();
        for (int index = 0; index < timestamps.length; index++) {
            futures.add(bar(day, timestamps[index], "FUT", ContractKind.FUTURE, Optional.empty(), Optional.empty(), index));
            options.add(bar(day, timestamps[index], "CALL", ContractKind.OPTION,
                    Optional.of(new BigDecimal("100")), Optional.of(OptionRight.CALL), index));
        }
        return SynchronizedBarFrameBuilder.build(Map.of("FUT", futures, "CALL", options));
    }

    private static SynchronizedBarFrameBuilder.Frame frame(LocalDate day, String timestamp) {
        return frame(day, timestamp, TimestampRole.BAR_START);
    }

    private static SynchronizedBarFrameBuilder.Frame frame(LocalDate day, String timestamp, TimestampRole role) {
        Bar future = bar(day, timestamp, "FUT", ContractKind.FUTURE, Optional.empty(), Optional.empty(), 0, role);
        Bar option = bar(day, timestamp, "CALL", ContractKind.OPTION,
                Optional.of(new BigDecimal("100")), Optional.of(OptionRight.CALL), 0, role);
        return SynchronizedBarFrameBuilder.build(Map.of("FUT", List.of(future), "CALL", List.of(option))).getFirst();
    }

    private static Bar bar(LocalDate day, String timestamp, String instrumentId, ContractKind kind,
            Optional<BigDecimal> strike, Optional<OptionRight> right, int index) {
        return bar(day, timestamp, instrumentId, kind, strike, right, index, TimestampRole.BAR_START);
    }

    private static Bar bar(LocalDate day, String timestamp, String instrumentId, ContractKind kind,
            Optional<BigDecimal> strike, Optional<OptionRight> right, int index, TimestampRole role) {
        BigDecimal open = instrumentId.equals("FUT") ? new BigDecimal("100.0") : new BigDecimal("10.0");
        BigDecimal close = instrumentId.equals("FUT") ? new BigDecimal("150.0") : new BigDecimal("15.0");
        BigDecimal high = close;
        BigDecimal low = open;
        return new Bar(Instant.parse(timestamp), day, instrumentId, "NSE", "NFO", instrumentId,
                instrumentId, kind, "NIFTY", LocalDate.of(2026, 11, 26), strike, right,
                25, new BigDecimal("0.05"), "ONE_MINUTE", role, open, high, low, close,
                100L, 100L, "fixture", HASH);
    }

    private static void expectFailure(Runnable operation, String message) {
        try {
            operation.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
