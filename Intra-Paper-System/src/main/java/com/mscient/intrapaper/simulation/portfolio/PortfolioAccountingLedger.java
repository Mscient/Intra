package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.data.IntervalSupport;
import com.mscient.intrapaper.data.SynchronizedBarFrameBuilder;
import com.mscient.intrapaper.reference.TimestampRole;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Deterministic, offline multi-instrument position and mark-to-market ledger.
 * It consumes caller-supplied simulated executions and exact-time synchronized bars only.
 */
public final class PortfolioAccountingLedger {
    public enum MarkPhase { BAR_OPEN, BAR_CLOSE }

    public record PositionSnapshot(long signedUnits, BigDecimal averageOpenPrice,
            BigDecimal realizedGrossPnlInr, BigDecimal feesInr, BigDecimal unrealizedPnlInr) { }

    public record Snapshot(Instant timestamp, MarkPhase phase, Map<String, PositionSnapshot> positions,
            BigDecimal realizedGrossPnlInr, BigDecimal feesInr,
            BigDecimal unrealizedPnlInr, BigDecimal netPnlInr) {
        public Snapshot {
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(phase, "phase");
            positions = Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(positions)));
        }
    }

    private static final class Position {
        private long signedUnits;
        private BigDecimal remainingBasis = BigDecimal.ZERO;
        private BigDecimal realizedGrossPnlInr = BigDecimal.ZERO;
        private BigDecimal feesInr = BigDecimal.ZERO;
    }

    private final Map<String, Position> positions = new TreeMap<>();
    private final Map<String, PortfolioExecution> executionsById = new TreeMap<>();
    private final Map<String, Bar> contractReferences = new TreeMap<>();
    private long lastExecutionSequence;
    private Instant lastOpenedFrameTimestamp;
    private Instant lastMarkTimestamp;
    private MarkPhase lastMarkPhase;

    /**
     * Applies executions at the frame timestamp and marks positions at a contemporaneously available price:
     * BAR_START frames use open; BAR_END frames use close. No future intrabar close is consulted.
     */
    public Snapshot mark(SynchronizedBarFrameBuilder.Frame frame,
            java.util.List<PortfolioExecution> executionsAtFrame) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(executionsAtFrame, "executionsAtFrame");
        if (lastOpenedFrameTimestamp != null && !frame.timestamp().isAfter(lastOpenedFrameTimestamp)) {
            throw new IllegalArgumentException("Portfolio frames must be strictly chronological");
        }
        if (lastMarkTimestamp != null && frame.timestamp().isBefore(lastMarkTimestamp)) {
            throw new IllegalArgumentException("Portfolio frame precedes the latest mark");
        }
        if (lastMarkTimestamp != null && frame.timestamp().equals(lastMarkTimestamp)
                && lastMarkPhase != MarkPhase.BAR_CLOSE) {
            throw new IllegalArgumentException("Only a next-frame open may share the preceding frame-close timestamp");
        }

        Map<String, Bar> bars = frame.barsByInstrument();
        if (!contractReferences.isEmpty() && !contractReferences.keySet().equals(bars.keySet())) {
            throw new IllegalArgumentException("Synchronized frame instrument set changed during portfolio accounting");
        }
        Map<String, Bar> stagedReferences = new TreeMap<>(contractReferences);
        for (Map.Entry<String, Bar> entry : bars.entrySet()) {
            Bar priorReference = stagedReferences.putIfAbsent(entry.getKey(), entry.getValue());
            if (priorReference != null && !sameContractIdentity(priorReference, entry.getValue())) {
                throw new IllegalArgumentException("Dated contract identity changed across portfolio frames: "
                        + entry.getKey());
            }
        }
        Map<String, Position> stagedPositions = copyPositions(positions);
        Map<String, PortfolioExecution> stagedExecutions = new TreeMap<>(executionsById);
        long stagedSequence = lastExecutionSequence;
        for (PortfolioExecution execution : executionsAtFrame) {
            Objects.requireNonNull(execution, "execution");
            if (!execution.timestamp().equals(frame.timestamp())) {
                throw new IllegalArgumentException("Execution timestamp must exactly match its synchronized frame: "
                        + execution.executionId());
            }
            Bar bar = bars.get(execution.instrumentId());
            if (bar == null) throw new IllegalArgumentException("Execution instrument is absent from frame: " + execution.instrumentId());
            if (execution.quantityUnits() % bar.lotSize() != 0) {
                throw new IllegalArgumentException("Execution quantity must be a whole number of lots for "
                        + execution.instrumentId());
            }
            if (execution.price().remainder(bar.tickSize()).signum() != 0) {
                throw new IllegalArgumentException("Execution price must align to the dated instrument tick for "
                        + execution.instrumentId());
            }
            PortfolioExecution prior = stagedExecutions.get(execution.executionId());
            if (prior != null) {
                if (!prior.equals(execution)) {
                    throw new IllegalArgumentException("Execution ID was reused with conflicting payload: "
                            + execution.executionId());
                }
                continue;
            }
            if (execution.sequence() <= stagedSequence) {
                throw new IllegalArgumentException("New execution sequence must increase strictly: " + execution.sequence());
            }
            apply(stagedPositions, execution);
            stagedExecutions.put(execution.executionId(), execution);
            stagedSequence = execution.sequence();
        }

        MarkPhase phase = bars.values().iterator().next().timestampRole() == TimestampRole.BAR_START
                ? MarkPhase.BAR_OPEN : MarkPhase.BAR_CLOSE;
        Snapshot snapshot = buildSnapshot(frame, frame.timestamp(), phase, stagedPositions);
        positions.clear();
        positions.putAll(stagedPositions);
        executionsById.clear();
        executionsById.putAll(stagedExecutions);
        contractReferences.clear();
        contractReferences.putAll(stagedReferences);
        lastExecutionSequence = stagedSequence;
        lastOpenedFrameTimestamp = frame.timestamp();
        lastMarkTimestamp = frame.timestamp();
        lastMarkPhase = phase;
        return snapshot;
    }

    /** Marks a BAR_START frame at its close after all that bar's OHLC data becomes observable. */
    public Snapshot markToClose(SynchronizedBarFrameBuilder.Frame frame) {
        Objects.requireNonNull(frame, "frame");
        if (lastOpenedFrameTimestamp == null || !frame.timestamp().equals(lastOpenedFrameTimestamp)
                || lastMarkPhase != MarkPhase.BAR_OPEN || !lastMarkTimestamp.equals(frame.timestamp())) {
            throw new IllegalStateException("Only the most recently opened BAR_START frame can be marked to close");
        }
        Bar firstBar = frame.barsByInstrument().values().iterator().next();
        if (firstBar.timestampRole() != TimestampRole.BAR_START) {
            throw new IllegalArgumentException("markToClose requires BAR_START bars");
        }
        Duration interval = IntervalSupport.duration(firstBar.interval());
        Instant closeTimestamp = frame.timestamp().plus(interval);
        Snapshot snapshot = buildSnapshot(frame, closeTimestamp, MarkPhase.BAR_CLOSE, positions);
        lastMarkTimestamp = closeTimestamp;
        lastMarkPhase = MarkPhase.BAR_CLOSE;
        return snapshot;
    }

    private static Snapshot buildSnapshot(SynchronizedBarFrameBuilder.Frame frame, Instant timestamp,
            MarkPhase phase, Map<String, Position> positions) {
        BigDecimal totalRealized = BigDecimal.ZERO;
        BigDecimal totalFees = BigDecimal.ZERO;
        BigDecimal totalUnrealized = BigDecimal.ZERO;
        LinkedHashMap<String, PositionSnapshot> snapshotPositions = new LinkedHashMap<>();
        for (Map.Entry<String, Bar> entry : new TreeMap<>(frame.barsByInstrument()).entrySet()) {
            String instrumentId = entry.getKey();
            Bar bar = entry.getValue();
            Position position = positions.getOrDefault(instrumentId, new Position());
            BigDecimal markPrice = phase == MarkPhase.BAR_OPEN && bar.timestampRole() == TimestampRole.BAR_START
                    ? bar.open() : bar.close();
            BigDecimal unrealized = position.signedUnits == 0 ? BigDecimal.ZERO
                    : markPrice.multiply(BigDecimal.valueOf(Math.abs(position.signedUnits)))
                            .subtract(position.remainingBasis)
                            .multiply(BigDecimal.valueOf(Long.signum(position.signedUnits)));
            BigDecimal averageOpenPrice = position.signedUnits == 0 ? BigDecimal.ZERO
                    : position.remainingBasis.divide(BigDecimal.valueOf(Math.abs(position.signedUnits)), 12,
                            java.math.RoundingMode.HALF_UP);
            totalRealized = totalRealized.add(position.realizedGrossPnlInr);
            totalFees = totalFees.add(position.feesInr);
            totalUnrealized = totalUnrealized.add(unrealized);
            snapshotPositions.put(instrumentId, new PositionSnapshot(position.signedUnits,
                    averageOpenPrice, position.realizedGrossPnlInr, position.feesInr, unrealized));
        }
        return new Snapshot(timestamp, phase, snapshotPositions, totalRealized, totalFees,
                totalUnrealized, totalRealized.add(totalUnrealized).subtract(totalFees));
    }

    private static void apply(Map<String, Position> positions, PortfolioExecution execution) {
        Position position = positions.computeIfAbsent(execution.instrumentId(), ignored -> new Position());
        long signedFill = execution.side() == PortfolioExecution.Side.BUY
                ? execution.quantityUnits() : -execution.quantityUnits();
        long oldUnits = position.signedUnits;
        long newUnits = Math.addExact(oldUnits, signedFill);
        if (newUnits == Long.MIN_VALUE) {
            throw new IllegalArgumentException("Signed position magnitude exceeds supported range");
        }

        if (oldUnits == 0 || Long.signum(oldUnits) == Long.signum(signedFill)) {
            position.remainingBasis = position.remainingBasis.add(
                    execution.price().multiply(BigDecimal.valueOf(execution.quantityUnits())));
        } else {
            long closedUnits = Math.min(Math.abs(oldUnits), execution.quantityUnits());
            BigDecimal allocatedBasis = closedUnits == Math.abs(oldUnits) ? position.remainingBasis
                    : position.remainingBasis.multiply(BigDecimal.valueOf(closedUnits))
                            .divide(BigDecimal.valueOf(Math.abs(oldUnits)), 18, java.math.RoundingMode.HALF_UP);
            BigDecimal realized = execution.price().multiply(BigDecimal.valueOf(closedUnits))
                    .subtract(allocatedBasis)
                    .multiply(BigDecimal.valueOf(Long.signum(oldUnits)));
            position.realizedGrossPnlInr = position.realizedGrossPnlInr.add(realized);
            position.remainingBasis = position.remainingBasis.subtract(allocatedBasis);
            if (newUnits == 0) {
                position.remainingBasis = BigDecimal.ZERO;
            } else if (Long.signum(newUnits) != Long.signum(oldUnits)) {
                position.remainingBasis = execution.price().multiply(BigDecimal.valueOf(Math.abs(newUnits)));
            }
        }
        position.signedUnits = newUnits;
        position.feesInr = position.feesInr.add(execution.feeInr());
    }

    private static Map<String, Position> copyPositions(Map<String, Position> source) {
        Map<String, Position> copy = new TreeMap<>();
        source.forEach((instrumentId, position) -> {
            Position cloned = new Position();
            cloned.signedUnits = position.signedUnits;
            cloned.remainingBasis = position.remainingBasis;
            cloned.realizedGrossPnlInr = position.realizedGrossPnlInr;
            cloned.feesInr = position.feesInr;
            copy.put(instrumentId, cloned);
        });
        return copy;
    }

    private static boolean sameContractIdentity(Bar left, Bar right) {
        if (!left.instrumentId().equals(right.instrumentId())
                || !left.exchange().equals(right.exchange())
                || !left.segment().equals(right.segment())
                || !left.tradingSymbol().equals(right.tradingSymbol())
                || left.contractKind() != right.contractKind()
                || !left.underlying().equals(right.underlying())
                || !left.expiry().equals(right.expiry())
                || !left.interval().equals(right.interval())
                || left.timestampRole() != right.timestampRole()
                || !left.optionRight().equals(right.optionRight())) return false;
        if (left.strike().isEmpty() || right.strike().isEmpty()) {
            return left.strike().isEmpty() == right.strike().isEmpty();
        }
        return left.strike().orElseThrow().compareTo(right.strike().orElseThrow()) == 0;
    }

    private static BigDecimal markPrice(Bar bar) {
        return bar.timestampRole() == TimestampRole.BAR_START ? bar.open() : bar.close();
    }
}
