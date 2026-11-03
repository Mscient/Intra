package com.mscient.intrapaper.simulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One append-only, checksummed engine snapshot at a completed bar boundary. */
public record BacktestCheckpoint(
        int schemaVersion,
        String runId,
        String barsSha256,
        String configSha256,
        String buildCommit,
        String strategyId,
        Map<String, String> strategyParameters,
        int previousNextBarIndex,
        int nextBarIndex,
        String previousCheckpointSha256,
        List<StrategySignal> signalDelta,
        List<SimOrder> orderDelta,
        List<SimOrderEvent> orderEventDelta,
        List<SimFill> fillDelta,
        List<SimFeeBreakdown> feeBreakdownDelta,
        List<SimTrade> tradeDelta,
        List<EquityPoint> equityDelta,
        BigDecimal realizedPnlInr,
        BigDecimal feesPaidInr,
        BigDecimal slippageInr,
        BigDecimal averageEntryPrice,
        int positionLots,
        PendingState pending,
        OpenTradeState openTrade,
        LocalDate activeSession,
        BigDecimal netAtPriorClose,
        int riskHalts,
        RiskGate.Snapshot riskGate,
        BigDecimal highWaterInr,
        BigDecimal maxDrawdownInr) implements java.io.Serializable {

    public record PendingState(int orderIndex, int targetLots, LocalDate decisionSession)
            implements java.io.Serializable {
        public PendingState {
            if (orderIndex < 0 || targetLots < 0 || decisionSession == null) {
                throw new IllegalArgumentException("invalid pending-order checkpoint state");
            }
        }
    }

    public record OpenTradeState(Instant entryTimestamp, BigDecimal entryPrice, int quantity,
            BigDecimal entryFee) implements java.io.Serializable {
        public OpenTradeState {
            if (entryTimestamp == null || entryPrice == null || entryFee == null || quantity < 1
                    || entryPrice.signum() <= 0 || entryFee.signum() < 0) {
                throw new IllegalArgumentException("invalid open-trade checkpoint state");
            }
        }
    }

    public BacktestCheckpoint {
        if (schemaVersion != 1) throw new IllegalArgumentException("unsupported checkpoint schema version");
        if (runId == null || !runId.matches("[0-9a-f]{24}")) throw new IllegalArgumentException("invalid checkpoint run ID");
        requireHash(barsSha256, "barsSha256");
        requireHash(configSha256, "configSha256");
        if (buildCommit == null || buildCommit.isBlank()) throw new IllegalArgumentException("checkpoint build commit is required");
        if (strategyId == null || strategyId.isBlank()) throw new IllegalArgumentException("checkpoint strategy ID is required");
        strategyParameters = Map.copyOf(new TreeMap<>(strategyParameters));
        if (previousNextBarIndex < 0 || nextBarIndex <= previousNextBarIndex) {
            throw new IllegalArgumentException("checkpoint bar indices must increase");
        }
        requireHash(previousCheckpointSha256, "previousCheckpointSha256");
        signalDelta = List.copyOf(signalDelta);
        orderDelta = List.copyOf(orderDelta);
        orderEventDelta = List.copyOf(orderEventDelta);
        fillDelta = List.copyOf(fillDelta);
        feeBreakdownDelta = List.copyOf(feeBreakdownDelta);
        tradeDelta = List.copyOf(tradeDelta);
        equityDelta = List.copyOf(equityDelta);
        if (signalDelta.size() != nextBarIndex - previousNextBarIndex
                || equityDelta.size() != nextBarIndex - previousNextBarIndex) {
            throw new IllegalArgumentException("checkpoint must contain one signal and equity point per processed bar");
        }
        for (int i = 0; i < signalDelta.size(); i++) {
            if (signalDelta.get(i).barNumber() != previousNextBarIndex + i + 1) {
                throw new IllegalArgumentException("checkpoint signal bar numbering is not contiguous");
            }
        }
        if (positionLots < 0 || riskHalts < 0 || activeSession == null || riskGate == null) {
            throw new IllegalArgumentException("invalid checkpoint position or risk state");
        }
        if (realizedPnlInr == null || feesPaidInr == null || slippageInr == null || averageEntryPrice == null
                || netAtPriorClose == null || highWaterInr == null || maxDrawdownInr == null) {
            throw new IllegalArgumentException("checkpoint accounting state is incomplete");
        }
    }

    private static void requireHash(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException(field + " must be SHA-256 hex");
    }
}
