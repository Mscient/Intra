package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.data.IntervalSupport;
import com.mscient.intrapaper.data.SynchronizedBarFrameBuilder;
import com.mscient.intrapaper.data.ValidatedPortfolioDataset;
import com.mscient.intrapaper.provenance.BuildInfo;
import com.mscient.intrapaper.provenance.CrossSessionPositionPolicy;
import com.mscient.intrapaper.reference.TimestampRole;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Offline next-frame-open portfolio replay over synchronized BAR_START inputs. */
public final class PortfolioReplayEngine {
    public enum PendingIntentBoundaryPolicy { CANCEL_AT_SESSION_BOUNDARY, ALLOW_NEXT_SESSION_OPEN }
    public enum OrderStatus { FILLED, PARTIAL, MISSED }
    public enum DecisionStatus { SCHEDULED, CANCELLED_AT_SESSION_BOUNDARY }

    public record Config(Map<String, Integer> maxAbsLotsByInstrument,
            PendingIntentBoundaryPolicy pendingIntentBoundaryPolicy,
            CrossSessionPositionPolicy positionBoundaryPolicy,
            boolean requireContiguousFramesWithinSession, PortfolioSettlementPolicy settlementPolicy,
            java.util.Set<String> settlementInstruments) {
        public Config(Map<String, Integer> limits, PendingIntentBoundaryPolicy pending,
                CrossSessionPositionPolicy positions, boolean contiguous) {
            this(limits, pending, positions, contiguous, PortfolioSettlementPolicy.NONE, java.util.Set.of());
        }
        public Config {
            Objects.requireNonNull(maxAbsLotsByInstrument, "maxAbsLotsByInstrument");
            TreeMap<String, Integer> limits = new TreeMap<>();
            maxAbsLotsByInstrument.forEach((instrumentId, maxLots) -> {
                if (instrumentId == null || !instrumentId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                    throw new IllegalArgumentException("Invalid max-lots instrument ID: " + instrumentId);
                }
                if (maxLots == null || maxLots < 1) {
                    throw new IllegalArgumentException("max absolute lots must be positive for " + instrumentId);
                }
                limits.put(instrumentId, maxLots);
            });
            if (limits.size() < 2) throw new IllegalArgumentException("At least two instrument limits are required");
            maxAbsLotsByInstrument = Collections.unmodifiableMap(limits);
            Objects.requireNonNull(settlementPolicy, "settlementPolicy");
            settlementInstruments = Collections.unmodifiableSet(new java.util.TreeSet<>(settlementInstruments));
            if ((settlementPolicy == PortfolioSettlementPolicy.NONE) != settlementInstruments.isEmpty()
                    || !limits.keySet().containsAll(settlementInstruments))
                throw new IllegalArgumentException("Settlement policy requires an explicit non-empty subset of portfolio instruments");
            Objects.requireNonNull(pendingIntentBoundaryPolicy, "pendingIntentBoundaryPolicy");
            Objects.requireNonNull(positionBoundaryPolicy, "positionBoundaryPolicy");
            if (positionBoundaryPolicy == CrossSessionPositionPolicy.AUTO_FLATTEN_SESSION_END) {
                throw new IllegalArgumentException("AUTO_FLATTEN_SESSION_END is not supported by this replay API");
            }
        }
    }

    public record OrderIntent(long sequence, String intentId, Instant decisionTimestamp,
            Instant executionTimestamp, String instrumentId, PortfolioExecution.Side side,
            long currentUnits, long targetUnits, long requestedUnits, String reason) {
        public OrderIntent {
            if (sequence < 1 || requestedUnits < 1) throw new IllegalArgumentException("intent sequence and quantity must be positive");
            requireIdentifier(intentId, "intent ID");
            requireIdentifier(instrumentId, "intent instrument ID");
            Objects.requireNonNull(decisionTimestamp, "decisionTimestamp");
            Objects.requireNonNull(executionTimestamp, "executionTimestamp");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(reason, "reason");
            long delta = Math.subtractExact(targetUnits, currentUnits);
            if (delta == 0 || Math.abs(delta) != requestedUnits
                    || (delta > 0 && side != PortfolioExecution.Side.BUY)
                    || (delta < 0 && side != PortfolioExecution.Side.SELL)) {
                throw new IllegalArgumentException("intent side and quantity must match current-to-target exposure");
            }
            if (executionTimestamp.isBefore(decisionTimestamp)) {
                throw new IllegalArgumentException("execution timestamp must not precede decision timestamp");
            }
            if (reason.isBlank() || !reason.equals(reason.strip())) throw new IllegalArgumentException("intent reason must be trimmed");
        }
    }

    public record OrderOutcome(OrderIntent intent, OrderStatus status, long filledUnits,
            Optional<PortfolioExecution> execution) {
        public OrderOutcome {
            Objects.requireNonNull(intent, "intent");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(execution, "execution");
            if (filledUnits < 0) throw new IllegalArgumentException("filledUnits must be non-negative");
            if ((status == OrderStatus.MISSED) != execution.isEmpty()) {
                throw new IllegalArgumentException("missed status must correspond to an absent execution");
            }
            if (execution.isPresent() && execution.orElseThrow().quantityUnits() != filledUnits) {
                throw new IllegalArgumentException("outcome quantity must match its execution");
            }
            if ((status == OrderStatus.MISSED && filledUnits != 0)
                    || (status == OrderStatus.FILLED && filledUnits != intent.requestedUnits())
                    || (status == OrderStatus.PARTIAL
                            && (filledUnits < 1 || filledUnits >= intent.requestedUnits()))) {
                throw new IllegalArgumentException("order status does not match filled quantity");
            }
        }
    }

    public record DecisionRecord(int frameIndex, Instant decisionTimestamp,
            java.time.LocalDate sessionDate, PortfolioTarget target, DecisionStatus status,
            String disposition) {
        public DecisionRecord {
            if (frameIndex < 0) throw new IllegalArgumentException("frameIndex must be non-negative");
            Objects.requireNonNull(decisionTimestamp, "decisionTimestamp");
            Objects.requireNonNull(sessionDate, "sessionDate");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(status, "status");
            requireIdentifier(disposition, "decision disposition");
        }
    }

    public record Result(String strategyId, Map<String, String> strategyParameters,
            String fillModelId, Map<String, String> fillModelParameters,
            String buildCommit, Config config,
            String validatedDatasetsSha256,
            Optional<String> executionCostScheduleId,
            Optional<String> executionCostScheduleSourceFileSha256,
            String frameInputsSha256, String strategyFingerprintSha256,
            String fillModelFingerprintSha256, String replayFingerprintSha256,
            List<DecisionRecord> decisions, List<OrderOutcome> orders,
            List<PortfolioExecution> executions, List<PortfolioAccountingLedger.Snapshot> snapshots,
            String classification, Optional<String> settlementEventsSourceFileSha256,
            Optional<String> settlementCostScheduleId, Optional<String> settlementCostScheduleSourceFileSha256,
            List<PortfolioSettlement> settlements) {
        public Result {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(executionCostScheduleId, "executionCostScheduleId");
            Objects.requireNonNull(executionCostScheduleSourceFileSha256, "executionCostScheduleSourceFileSha256");
            if (executionCostScheduleId.isPresent() != executionCostScheduleSourceFileSha256.isPresent()) {
                throw new IllegalArgumentException("cost schedule identity and source hash must be present together");
            }
            executionCostScheduleId.ifPresent(id -> requireIdentifier(id, "execution cost schedule ID"));
            executionCostScheduleSourceFileSha256.ifPresent(hash -> {
                if (!hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid cost schedule source hash");
            });
            strategyParameters = immutableSorted(strategyParameters);
            fillModelParameters = immutableSorted(fillModelParameters);
            decisions = List.copyOf(decisions);
            orders = List.copyOf(orders);
            executions = List.copyOf(executions);
            snapshots = List.copyOf(snapshots);
            settlements = List.copyOf(settlements);
            Objects.requireNonNull(settlementEventsSourceFileSha256);
            Objects.requireNonNull(settlementCostScheduleId);
            Objects.requireNonNull(settlementCostScheduleSourceFileSha256);
            if ((config.settlementPolicy() != PortfolioSettlementPolicy.NONE) != settlementEventsSourceFileSha256.isPresent()
                    || settlementCostScheduleId.isPresent() != settlementCostScheduleSourceFileSha256.isPresent()
                    || (settlementCostScheduleId.isPresent() && settlementEventsSourceFileSha256.isEmpty()))
                throw new IllegalArgumentException("Settlement policy and input provenance disagree");
            settlementEventsSourceFileSha256.ifPresent(PortfolioSettlementContract::hash);
            settlementCostScheduleSourceFileSha256.ifPresent(PortfolioSettlementContract::hash);
            var settled = new java.util.TreeSet<String>();
            for (var settlement : settlements) if (!settled.add(settlement.event().contract().instrumentId()))
                throw new IllegalArgumentException("Duplicate result settlement instrument");
            if (!settled.equals(config.settlementInstruments())) throw new IllegalArgumentException("Result settlement coverage differs from config");
        }

        public boolean performanceClaimEligible() { return false; }
    }

    private record Pending(PortfolioTarget target, Instant decisionTimestamp,
            java.time.LocalDate decisionSessionDate) { }

    private final Config config;
    private final PortfolioStrategy strategy;
    private final PortfolioFillModel fillModel;
    private final Optional<PortfolioExecutionCostSchedule> executionCostSchedule;
    private final Optional<PortfolioSettlementEvents> settlementEvents;
    private final Optional<PortfolioSettlementCostSchedule> settlementCostSchedule;

    public PortfolioReplayEngine(Config config, PortfolioStrategy strategy, PortfolioFillModel fillModel) {
        this(config, strategy, fillModel, Optional.empty());
    }

    public PortfolioReplayEngine(Config config, PortfolioStrategy strategy, PortfolioFillModel fillModel,
            Optional<PortfolioExecutionCostSchedule> executionCostSchedule) {
        this(config, strategy, fillModel, executionCostSchedule, Optional.empty(), Optional.empty());
    }

    public PortfolioReplayEngine(Config config, PortfolioStrategy strategy, PortfolioFillModel fillModel,
            Optional<PortfolioExecutionCostSchedule> executionCostSchedule,
            Optional<PortfolioSettlementEvents> settlementEvents, Optional<PortfolioSettlementCostSchedule> settlementCostSchedule) {
        this.config = Objects.requireNonNull(config, "config");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.fillModel = Objects.requireNonNull(fillModel, "fillModel");
        this.executionCostSchedule = Objects.requireNonNull(executionCostSchedule, "executionCostSchedule");
        this.settlementEvents = Objects.requireNonNull(settlementEvents);
        this.settlementCostSchedule = Objects.requireNonNull(settlementCostSchedule);
        if ((config.settlementPolicy() != PortfolioSettlementPolicy.NONE) != settlementEvents.isPresent()
                || (settlementEvents.isEmpty() && settlementCostSchedule.isPresent()))
            throw new IllegalArgumentException("Settlement inputs conflict with settlement policy");
        requireIdentifier(strategy.id(), "strategy ID");
        requireIdentifier(fillModel.id(), "fill model ID");
        immutableSorted(strategy.parameters());
        immutableSorted(fillModel.parameters());
    }

    public Result run(List<SynchronizedBarFrameBuilder.Frame> inputFrames) {
        if (settlementEvents.isPresent()) throw new IllegalArgumentException("Settlement requires ValidatedPortfolioDataset input");
        return run(inputFrames, "");
    }

    /** Replays only frames backed by individually validated datasets and records their provenance hash. */
    public Result run(ValidatedPortfolioDataset dataset) {
        Objects.requireNonNull(dataset, "dataset");
        PortfolioSettlementEvents.validateSelection(dataset, config.settlementInstruments(), settlementEvents);
        return run(dataset.frames(), dataset.datasetsSha256());
    }

    private Result run(List<SynchronizedBarFrameBuilder.Frame> inputFrames, String validatedDatasetsSha256) {
        List<SynchronizedBarFrameBuilder.Frame> frames = List.copyOf(
                Objects.requireNonNull(inputFrames, "inputFrames"));
        if (frames.isEmpty()) throw new IllegalArgumentException("Portfolio replay requires frames");
        List<String> instruments = validateFrames(frames);
        if (!config.maxAbsLotsByInstrument().keySet().equals(new java.util.TreeSet<>(instruments))) {
            throw new IllegalArgumentException("Per-instrument lot limits must exactly match the frame universe");
        }

        Map<String, String> strategyParameters = immutableSorted(strategy.parameters());
        Map<String, String> fillParameters = immutableSorted(fillModel.parameters());
        String framesHash = hashFrames(frames);
        String strategyHash = hashIdentity("INTRA_PORTFOLIO_STRATEGY_V1", strategy.id(), strategyParameters);
        String fillHash = hashIdentity("INTRA_PORTFOLIO_FILL_MODEL_V1", fillModel.id(), fillParameters);
        String buildCommit = BuildInfo.commitId();
        String scheduleId = executionCostSchedule.map(PortfolioExecutionCostSchedule::scheduleId)
                .orElse("NO_EXECUTION_COST_SCHEDULE");
        String scheduleHash = executionCostSchedule.map(PortfolioExecutionCostSchedule::sourceFileSha256)
                .orElse("NO_EXECUTION_COST_SCHEDULE");
        String replayHash = hashStrings("INTRA_PORTFOLIO_REPLAY_V3", List.of(buildCommit,
                validatedDatasetsSha256, framesHash, strategyHash,
                fillHash, scheduleId, scheduleHash, config.maxAbsLotsByInstrument().toString(), config.pendingIntentBoundaryPolicy().name(),
                config.positionBoundaryPolicy().name(), Boolean.toString(config.requireContiguousFramesWithinSession()),
                config.settlementPolicy().name(), String.join(",", config.settlementInstruments()),
                settlementEvents.map(PortfolioSettlementEvents::sourceFileSha256).orElse("NONE"),
                settlementCostSchedule.map(PortfolioSettlementCostSchedule::scheduleId).orElse("NONE"),
                settlementCostSchedule.map(PortfolioSettlementCostSchedule::sourceFileSha256).orElse("NONE")));

        PortfolioAccountingLedger ledger = new PortfolioAccountingLedger();
        List<DecisionRecord> decisions = new ArrayList<>();
        List<OrderOutcome> orderOutcomes = new ArrayList<>();
        List<PortfolioExecution> executions = new ArrayList<>();
        List<PortfolioSettlement> settlements = new ArrayList<>();
        List<PortfolioAccountingLedger.Snapshot> snapshots = new ArrayList<>(frames.size());
        PortfolioAccountingLedger.Snapshot previousSnapshot = null;
        Pending pending = null;
        long intentSequence = 0;
        long executionSequence = 0;
        Duration interval = IntervalSupport.duration(frames.getFirst().barsByInstrument()
                .values().iterator().next().interval());

        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            SynchronizedBarFrameBuilder.Frame frame = frames.get(frameIndex);
            List<PortfolioExecution> frameExecutions = new ArrayList<>();
            if (pending != null) {
                if (!pending.decisionSessionDate().equals(frame.sessionDate())
                        && config.pendingIntentBoundaryPolicy() == PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY) {
                    throw new IllegalStateException("A cross-session intent was scheduled despite the cancellation policy");
                }
                for (String instrumentId : instruments) {
                    Bar bar = frame.barsByInstrument().get(instrumentId);
                    int targetLots = pending.target().signedLotsByInstrument().get(instrumentId);
                    validateTargetLots(instrumentId, targetLots, bar.lotSize());
                    long targetUnits = Math.multiplyExact((long) targetLots, bar.lotSize());
                    long currentUnits = previousSnapshot.positions().get(instrumentId).signedUnits();
                    long maxUnits = Math.multiplyExact((long) config.maxAbsLotsByInstrument().get(instrumentId), bar.lotSize());
                    if (Math.abs(currentUnits) > maxUnits) {
                        throw new IllegalStateException("Existing position exceeds configured per-instrument limit: " + instrumentId);
                    }
                    if (currentUnits % bar.lotSize() != 0) {
                        throw new IllegalStateException("Existing position is not a whole number of current lots: " + instrumentId);
                    }
                    long deltaUnits = Math.subtractExact(targetUnits, currentUnits);
                    if (deltaUnits == 0) continue;
                    long requestedUnits = Math.abs(deltaUnits);
                    PortfolioExecution.Side side = deltaUnits > 0
                            ? PortfolioExecution.Side.BUY : PortfolioExecution.Side.SELL;
                    long nextIntent = ++intentSequence;
                    String intentId = "PO-" + String.format(java.util.Locale.ROOT, "%08d", nextIntent);
                    OrderIntent intent = new OrderIntent(nextIntent, intentId, pending.decisionTimestamp(),
                            frame.timestamp(), instrumentId, side, currentUnits, targetUnits,
                            requestedUnits, pending.target().reason());
                    PortfolioFillModel.OpeningMarketPoint opening = new PortfolioFillModel.OpeningMarketPoint(
                            instrumentId, frame.timestamp(), frame.sessionDate(), bar.contractKind(),
                            bar.open(), bar.tickSize(), bar.lotSize());
                    Optional<PortfolioFillModel.Fill> fill = Objects.requireNonNull(
                            fillModel.fill(intent, opening), "fill model result");
                    if (fill.isEmpty()) {
                        orderOutcomes.add(new OrderOutcome(intent, OrderStatus.MISSED, 0, Optional.empty()));
                        continue;
                    }
                    PortfolioFillModel.Fill filled = fill.orElseThrow();
                    if (filled.quantityUnits() > requestedUnits
                            || filled.quantityUnits() % bar.lotSize() != 0
                            || filled.price().remainder(bar.tickSize()).signum() != 0) {
                        throw new IllegalArgumentException("Fill model returned a quantity or price outside the dated contract constraints");
                    }
                    long signedFilled = side == PortfolioExecution.Side.BUY
                            ? filled.quantityUnits() : -filled.quantityUnits();
                    long resultingUnits = Math.addExact(currentUnits, signedFilled);
                    if (Math.abs(resultingUnits) > maxUnits) {
                        throw new IllegalArgumentException("Fill would exceed configured per-instrument position limit: " + instrumentId);
                    }
                    BigDecimal tradedValue = filled.price().multiply(BigDecimal.valueOf(filled.quantityUnits()));
                    PortfolioExecutionFeeBreakdown feeBreakdown;
                    if (executionCostSchedule.isPresent()) {
                        if (filled.feeInr().signum() != 0) {
                            throw new IllegalArgumentException("Fill-model fee must be zero when a dated execution cost schedule is enabled");
                        }
                        feeBreakdown = executionCostSchedule.orElseThrow().calculate(frame.sessionDate(), instrumentId,
                                bar.contractKind(), side, filled.price(), filled.quantityUnits()).breakdown();
                    } else {
                        feeBreakdown = PortfolioExecutionFeeBreakdown.legacy(fillModel.id(), frame.sessionDate(),
                                bar.contractKind(), tradedValue, filled.feeInr());
                    }
                    PortfolioExecution execution = new PortfolioExecution("PX-" + (++executionSequence),
                            executionSequence, instrumentId, frame.timestamp(), side,
                            filled.quantityUnits(), filled.price(), feeBreakdown.totalFeeInr(), Optional.of(feeBreakdown));
                    frameExecutions.add(execution);
                    executions.add(execution);
                    OrderStatus status = filled.quantityUnits() == requestedUnits
                            ? OrderStatus.FILLED : OrderStatus.PARTIAL;
                    orderOutcomes.add(new OrderOutcome(intent, status, filled.quantityUnits(), Optional.of(execution)));
                }
            }

            PortfolioAccountingLedger.Snapshot snapshot = ledger.mark(frame, frameExecutions);
            snapshots.add(snapshot);
            PortfolioAccountingLedger.Snapshot closeSnapshot = ledger.markToClose(frame);
            snapshots.add(closeSnapshot);
            previousSnapshot = closeSnapshot;

            if (frameIndex + 1 == frames.size()) {
                if (settlementEvents.isPresent()) {
                    var batch = ledger.settleFinalSession(frame, settlementEvents.orElseThrow(), settlementCostSchedule);
                    settlements.addAll(batch.settlements()); snapshots.addAll(batch.snapshots());
                    previousSnapshot = batch.snapshots().getLast();
                } else if (frame.barsByInstrument().values().stream().anyMatch(b -> b.sessionDate().equals(b.expiry()))) {
                    throw new IllegalArgumentException("Expiry-date replay requires explicit settlement events");
                }
            }

            boolean sessionEndsHere = frameIndex + 1 == frames.size()
                    || !frame.sessionDate().equals(frames.get(frameIndex + 1).sessionDate());
            if (sessionEndsHere && config.positionBoundaryPolicy() == CrossSessionPositionPolicy.REQUIRE_FLAT
                    && previousSnapshot.positions().values().stream().anyMatch(position -> position.signedUnits() != 0)) {
                throw new IllegalStateException("Portfolio must be flat at the session/data boundary under REQUIRE_FLAT");
            }

            if (frameIndex + 1 < frames.size()) {
                Instant decisionTimestamp = frame.timestamp().plus(interval);
                PortfolioTarget target = Objects.requireNonNull(strategy.evaluate(
                        List.copyOf(frames.subList(0, frameIndex + 1)), decisionTimestamp, closeSnapshot), "strategy target");
                if (!target.signedLotsByInstrument().keySet().equals(new java.util.TreeSet<>(instruments))) {
                    throw new IllegalArgumentException("Strategy target must contain exactly every frame instrument");
                }
                for (String instrumentId : instruments) {
                    validateTargetLots(instrumentId, target.signedLotsByInstrument().get(instrumentId),
                            frame.barsByInstrument().get(instrumentId).lotSize());
                }
                boolean crossesSession = !frame.sessionDate().equals(frames.get(frameIndex + 1).sessionDate());
                boolean scheduled = !crossesSession || config.pendingIntentBoundaryPolicy()
                        == PendingIntentBoundaryPolicy.ALLOW_NEXT_SESSION_OPEN;
                decisions.add(new DecisionRecord(frameIndex, decisionTimestamp, frame.sessionDate(), target,
                        scheduled ? DecisionStatus.SCHEDULED : DecisionStatus.CANCELLED_AT_SESSION_BOUNDARY,
                        scheduled ? "NEXT_FRAME_OPEN" : "PENDING_INTENT_CANCELLED_AT_SESSION_BOUNDARY"));
                pending = scheduled ? new Pending(target, decisionTimestamp, frame.sessionDate()) : null;
            }
        }

        return new Result(strategy.id(), strategyParameters, fillModel.id(), fillParameters, buildCommit, config,
                validatedDatasetsSha256,
                executionCostSchedule.map(PortfolioExecutionCostSchedule::scheduleId),
                executionCostSchedule.map(PortfolioExecutionCostSchedule::sourceFileSha256),
                framesHash, strategyHash, fillHash, replayHash, decisions, orderOutcomes,
                executions, snapshots, "REPLAY_DIAGNOSTIC_ONLY", settlementEvents.map(PortfolioSettlementEvents::sourceFileSha256),
                settlementCostSchedule.map(PortfolioSettlementCostSchedule::scheduleId),
                settlementCostSchedule.map(PortfolioSettlementCostSchedule::sourceFileSha256), settlements);
    }

    private List<String> validateFrames(List<SynchronizedBarFrameBuilder.Frame> frames) {
        SynchronizedBarFrameBuilder.Frame first = Objects.requireNonNull(frames.getFirst(), "frame");
        if (first.barsByInstrument().size() < 2) throw new IllegalArgumentException("At least two instruments are required");
        List<String> instruments = List.copyOf(new java.util.TreeSet<>(first.barsByInstrument().keySet()));
        String interval = null;
        String exchange = null;
        String segment = null;
        Instant previousTimestamp = null;
        java.time.LocalDate previousSession = null;
        for (int frameIndex = 0; frameIndex < frames.size(); frameIndex++) {
            SynchronizedBarFrameBuilder.Frame frame = Objects.requireNonNull(frames.get(frameIndex), "frame");
            if (!frame.barsByInstrument().keySet().equals(new java.util.TreeSet<>(instruments))) {
                throw new IllegalArgumentException("Portfolio frame universe changes at index " + frameIndex);
            }
            if (previousTimestamp != null && !frame.timestamp().isAfter(previousTimestamp)) {
                throw new IllegalArgumentException("Portfolio frame timestamps must be strictly increasing");
            }
            if (previousSession != null && frame.sessionDate().isBefore(previousSession)) {
                throw new IllegalArgumentException("Portfolio session dates must be non-decreasing");
            }
            for (String instrumentId : instruments) {
                Bar bar = frame.barsByInstrument().get(instrumentId);
                if (bar.sessionDate().isAfter(bar.expiry())) throw new IllegalArgumentException("Portfolio bars cannot follow supplied expiry");
                if (bar.timestampRole() != TimestampRole.BAR_START) {
                    throw new IllegalArgumentException("Portfolio next-frame-open replay requires BAR_START inputs");
                }
                if (interval == null) {
                    interval = bar.interval();
                    exchange = bar.exchange();
                    segment = bar.segment();
                } else if (!interval.equals(bar.interval()) || !exchange.equals(bar.exchange())
                        || !segment.equals(bar.segment())) {
                    throw new IllegalArgumentException("Portfolio inputs must share exchange, segment, and interval");
                }
            }
            if (previousTimestamp != null) {
                Instant previousClose = previousTimestamp.plus(IntervalSupport.duration(interval));
                if (frame.timestamp().isBefore(previousClose)) {
                    throw new IllegalArgumentException("Portfolio BAR_START frames overlap before the preceding bar closes at "
                            + previousClose);
                }
            }
            if (config.requireContiguousFramesWithinSession() && previousTimestamp != null
                    && frame.sessionDate().equals(previousSession)) {
                Duration spacing = IntervalSupport.duration(interval);
                if (!frame.timestamp().equals(previousTimestamp.plus(spacing))) {
                    throw new IllegalArgumentException("Missing or irregular frame inside session at " + frame.timestamp());
                }
            }
            previousTimestamp = frame.timestamp();
            previousSession = frame.sessionDate();
        }
        return instruments;
    }

    private void validateTargetLots(String instrumentId, Integer targetLots, int lotSize) {
        if (targetLots == null) throw new IllegalArgumentException("Missing target for instrument " + instrumentId);
        if (Math.abs((long) targetLots) > config.maxAbsLotsByInstrument().get(instrumentId)) {
            throw new IllegalArgumentException("Strategy target exceeds configured lot limit for " + instrumentId);
        }
        Math.multiplyExact((long) targetLots, lotSize);
    }

    private static Map<String, String> immutableSorted(Map<String, String> values) {
        Objects.requireNonNull(values, "parameters");
        TreeMap<String, String> ordered = new TreeMap<>();
        values.forEach((key, value) -> {
            if (key == null || key.isBlank() || !key.equals(key.strip()) || value == null) {
                throw new IllegalArgumentException("Strategy and fill-model parameters require non-empty keys and non-null values");
            }
            ordered.put(key, value);
        });
        return Collections.unmodifiableMap(ordered);
    }

    private static String requireIdentifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isBlank() || !value.equals(value.strip())) throw new IllegalArgumentException(label + " must be non-empty and trimmed");
        return value;
    }

    private static String hashFrames(List<SynchronizedBarFrameBuilder.Frame> frames) {
        return hash("INTRA_PORTFOLIO_FRAMES_V1", output -> {
            output.writeInt(frames.size());
            for (SynchronizedBarFrameBuilder.Frame frame : frames) {
                write(output, frame.timestamp().toString());
                write(output, frame.sessionDate().toString());
                output.writeInt(frame.barsByInstrument().size());
                for (Map.Entry<String, Bar> entry : new TreeMap<>(frame.barsByInstrument()).entrySet()) {
                    Bar bar = entry.getValue();
                    write(output, entry.getKey());
                    write(output, bar.exchange());
                    write(output, bar.segment());
                    write(output, bar.sourceToken());
                    write(output, bar.tradingSymbol());
                    write(output, bar.contractKind().name());
                    write(output, bar.underlying());
                    write(output, bar.expiry().toString());
                    write(output, bar.strike().map(PortfolioReplayEngine::decimal).orElse(""));
                    write(output, bar.optionRight().map(Enum::name).orElse(""));
                    output.writeInt(bar.lotSize());
                    write(output, decimal(bar.tickSize()));
                    write(output, bar.interval());
                    write(output, bar.timestampRole().name());
                    write(output, decimal(bar.open()));
                    write(output, decimal(bar.high()));
                    write(output, decimal(bar.low()));
                    write(output, decimal(bar.close()));
                    writeNullableLong(output, bar.volume());
                    writeNullableLong(output, bar.openInterest());
                    write(output, bar.source());
                    write(output, bar.rawRecordHash());
                }
            }
        });
    }

    private static String hashIdentity(String domain, String id, Map<String, String> values) {
        return hash(domain, output -> {
            write(output, id);
            output.writeInt(values.size());
            for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
                write(output, entry.getKey());
                write(output, entry.getValue());
            }
        });
    }

    static String hashStrings(String domain, List<String> values) {
        return hash(domain, output -> {
            output.writeInt(values.size());
            for (String value : values) write(output, value);
        });
    }

    @FunctionalInterface
    private interface HashWriter { void write(DataOutputStream output) throws IOException; }

    private static String hash(String domain, HashWriter writer) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                write(output, domain);
                writer.write(output);
            }
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes.toByteArray()));
        } catch (IOException impossibleForMemoryStream) {
            throw new IllegalStateException("Unable to serialize portfolio replay identity", impossibleForMemoryStream);
        } catch (java.security.NoSuchAlgorithmException impossibleWithoutSha256) {
            throw new IllegalStateException("SHA-256 is unavailable", impossibleWithoutSha256);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] utf8 = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(utf8.length);
        output.write(utf8);
    }

    private static void writeNullableLong(DataOutputStream output, Long value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) output.writeLong(value);
    }

    private static String decimal(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
