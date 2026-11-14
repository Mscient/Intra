package com.mscient.intrapaper.evaluation;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.simulation.SessionAudit;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Session-close diagnostics grouped by a validated chronological split plan. */
public record EvaluationPartitionReport(String splitPlanSha256, List<PartitionSummary> partitions) {
    public record PartitionSummary(
            EvaluationSplitPlan.Partition partition,
            LocalDate firstSession,
            LocalDate lastSession,
            int sessionCount,
            int completeSessionCount,
            int positionUnitsAtStart,
            int positionUnitsAtEnd,
            boolean flatAtPartitionBoundaries,
            BigDecimal realizedPnlChangeInr,
            BigDecimal unrealizedPnlChangeInr,
            BigDecimal netPnlChangeInr,
            BigDecimal maxSessionCloseDrawdownInr,
            BigDecimal feesPaidInr,
            BigDecimal modeledSlippageInr,
            int ordersCreated,
            int fills,
            int completedTrades,
            int riskHalts,
            String status) {
        public PartitionSummary {
            Objects.requireNonNull(partition, "partition");
            Objects.requireNonNull(firstSession, "firstSession");
            Objects.requireNonNull(lastSession, "lastSession");
            Objects.requireNonNull(realizedPnlChangeInr, "realizedPnlChangeInr");
            Objects.requireNonNull(unrealizedPnlChangeInr, "unrealizedPnlChangeInr");
            Objects.requireNonNull(netPnlChangeInr, "netPnlChangeInr");
            Objects.requireNonNull(maxSessionCloseDrawdownInr, "maxSessionCloseDrawdownInr");
            Objects.requireNonNull(feesPaidInr, "feesPaidInr");
            Objects.requireNonNull(modeledSlippageInr, "modeledSlippageInr");
            Objects.requireNonNull(status, "status");
            if (sessionCount < 1 || completeSessionCount < 0 || completeSessionCount > sessionCount
                    || firstSession.isAfter(lastSession) || ordersCreated < 0 || fills < 0
                    || completedTrades < 0 || riskHalts < 0 || maxSessionCloseDrawdownInr.signum() < 0) {
                throw new IllegalArgumentException("invalid partition diagnostic summary");
            }
        }
    }

    public EvaluationPartitionReport {
        if (splitPlanSha256 == null || !splitPlanSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("splitPlanSha256 must be lowercase SHA-256 hex");
        }
        partitions = List.copyOf(partitions);
        if (partitions.size() != EvaluationSplitPlan.Partition.values().length) {
            throw new IllegalArgumentException("partition report must contain development, validation, and holdout");
        }
        for (int index = 0; index < partitions.size(); index++) {
            if (partitions.get(index).partition() != EvaluationSplitPlan.Partition.values()[index]) {
                throw new IllegalArgumentException("partition report order must be development, validation, holdout");
            }
        }
    }

    public static Optional<EvaluationPartitionReport> create(
            RunConfig config, ValidatedDataset dataset, List<SessionAudit> sessionAudits) throws IOException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(sessionAudits, "sessionAudits");
        if (config.evaluationSplitPlanPath().isEmpty()) return Optional.empty();

        dataset.requireSimulationReady(config);

        Path splitPath = config.evaluationSplitPlanPath().orElseThrow();
        EvaluationSplitPlan split = EvaluationSplitPlan.load(splitPath, dataset);
        Map<LocalDate, SessionAudit> auditsByDate = new HashMap<>();
        for (SessionAudit audit : sessionAudits) {
            if (auditsByDate.putIfAbsent(audit.sessionDate(), audit) != null) {
                throw new IOException("Duplicate session audit for evaluation report: " + audit.sessionDate());
            }
        }

        List<PartitionSummary> summaries = new ArrayList<>();
        try {
            for (EvaluationSplitPlan.Partition partition : EvaluationSplitPlan.Partition.values()) {
                List<LocalDate> dates = split.sessions().get(partition);
                List<SessionAudit> partitionAudits = dates.stream().map(date -> {
                    SessionAudit audit = auditsByDate.get(date);
                    if (audit == null) throw new MissingSessionAuditException(date);
                    return audit;
                }).toList();
                summaries.add(summarize(partition, partitionAudits));
            }
        } catch (MissingSessionAuditException missing) {
            throw new IOException("Missing session audit for split date: " + missing.sessionDate, missing);
        }

        if (auditsByDate.size() != sessionAudits.size()
                || !auditsByDate.keySet().equals(split.sessions().values().stream().flatMap(List::stream)
                        .collect(java.util.stream.Collectors.toSet()))) {
            throw new IOException("Session audit dates do not match the validated evaluation split");
        }
        return Optional.of(new EvaluationPartitionReport(split.splitFileSha256(), summaries));
    }

    private static PartitionSummary summarize(
            EvaluationSplitPlan.Partition partition, List<SessionAudit> audits) {
        SessionAudit first = audits.getFirst();
        SessionAudit last = audits.getLast();
        int completeSessions = 0;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        BigDecimal slippage = BigDecimal.ZERO;
        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal highWater = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        int orders = 0;
        int fills = 0;
        int completedTrades = 0;
        int halts = 0;
        for (SessionAudit audit : audits) {
            if (audit.complete()) completeSessions++;
            realized = realized.add(audit.realizedPnlChangeInr());
            unrealized = unrealized.add(audit.unrealizedPnlChangeInr());
            net = net.add(audit.netPnlChangeInr());
            fees = fees.add(audit.feesPaidInr());
            slippage = slippage.add(audit.modeledSlippageInr());
            orders = Math.addExact(orders, audit.ordersCreated());
            fills = Math.addExact(fills, audit.fills());
            completedTrades = Math.addExact(completedTrades, audit.completedTrades());
            halts = Math.addExact(halts, audit.riskHalts());
            equity = equity.add(audit.netPnlChangeInr());
            if (equity.compareTo(highWater) > 0) highWater = equity;
            BigDecimal drawdown = highWater.subtract(equity);
            if (drawdown.compareTo(maxDrawdown) > 0) maxDrawdown = drawdown;
        }

        boolean flatAtBoundaries = first.positionUnitsAtStart() == 0 && last.positionUnitsAtEnd() == 0;
        boolean complete = completeSessions == audits.size();
        String status = complete && flatAtBoundaries ? "DIAGNOSTIC_ONLY"
                : !complete && !flatAtBoundaries ? "INCOMPLETE_AND_POSITION_CROSSES_PARTITION_BOUNDARY"
                : !complete ? "INCOMPLETE_SESSIONS" : "POSITION_CROSSES_PARTITION_BOUNDARY";
        return new PartitionSummary(partition, first.sessionDate(), last.sessionDate(), audits.size(),
                completeSessions, first.positionUnitsAtStart(), last.positionUnitsAtEnd(), flatAtBoundaries,
                realized, unrealized, net, maxDrawdown, fees, slippage, orders, fills, completedTrades, halts, status);
    }

    private static final class MissingSessionAuditException extends RuntimeException {
        private final LocalDate sessionDate;

        private MissingSessionAuditException(LocalDate sessionDate) {
            this.sessionDate = sessionDate;
        }
    }
}
