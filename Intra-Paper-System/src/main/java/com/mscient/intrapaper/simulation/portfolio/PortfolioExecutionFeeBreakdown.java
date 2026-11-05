package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.reference.ContractKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Immutable audit record for the complete fee applied to one portfolio execution. */
public record PortfolioExecutionFeeBreakdown(
        CostModel costModel,
        String scheduleId,
        String scheduleEntryId,
        String scheduleSourceFileSha256,
        String sourceReference,
        LocalDate executionDate,
        ContractKind contractKind,
        BigDecimal tradedValueInr,
        BigDecimal legacyFillModelFeeInr,
        BigDecimal brokerageInr,
        BigDecimal exchangeTransactionInr,
        BigDecimal ipftContributionInr,
        BigDecimal sebiTurnoverInr,
        BigDecimal sttInr,
        BigDecimal stampDutyInr,
        BigDecimal gstTaxableBaseInr,
        BigDecimal gstInr,
        BigDecimal totalFeeInr) {

    public enum CostModel { CALLER_FILL_MODEL, DATED_SCHEDULE }

    public PortfolioExecutionFeeBreakdown {
        Objects.requireNonNull(costModel, "costModel");
        scheduleId = text(scheduleId, "scheduleId", costModel == CostModel.DATED_SCHEDULE);
        scheduleEntryId = text(scheduleEntryId, "scheduleEntryId", costModel == CostModel.DATED_SCHEDULE);
        scheduleSourceFileSha256 = text(scheduleSourceFileSha256, "scheduleSourceFileSha256",
                costModel == CostModel.DATED_SCHEDULE);
        sourceReference = text(sourceReference, "sourceReference", true);
        Objects.requireNonNull(executionDate, "executionDate");
        Objects.requireNonNull(contractKind, "contractKind");
        for (BigDecimal value : List.of(tradedValueInr, legacyFillModelFeeInr, brokerageInr,
                exchangeTransactionInr, ipftContributionInr, sebiTurnoverInr, sttInr,
                stampDutyInr, gstTaxableBaseInr, gstInr, totalFeeInr)) {
            Objects.requireNonNull(value, "fee value");
            if (value.signum() < 0) throw new IllegalArgumentException("execution fee values must be non-negative");
        }
        if (costModel == CostModel.DATED_SCHEDULE) {
            if (!scheduleSourceFileSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("dated schedule source hash must be lowercase SHA-256");
            }
            if (legacyFillModelFeeInr.signum() != 0) {
                throw new IllegalArgumentException("dated schedule fees cannot include a fill-model fee");
            }
        } else if (!scheduleId.isEmpty() || !scheduleEntryId.isEmpty() || !scheduleSourceFileSha256.isEmpty()) {
            throw new IllegalArgumentException("legacy fill-model fees cannot carry schedule metadata");
        }
        BigDecimal calculated = legacyFillModelFeeInr.add(brokerageInr).add(exchangeTransactionInr)
                .add(ipftContributionInr).add(sebiTurnoverInr).add(sttInr)
                .add(stampDutyInr).add(gstInr);
        if (calculated.compareTo(totalFeeInr) != 0) {
            throw new IllegalArgumentException("execution fee total does not equal its itemized components");
        }
    }

    public static PortfolioExecutionFeeBreakdown legacy(String fillModelId, LocalDate executionDate,
            ContractKind contractKind, BigDecimal tradedValueInr, BigDecimal feeInr) {
        return new PortfolioExecutionFeeBreakdown(CostModel.CALLER_FILL_MODEL, "", "", "",
                "fill-model:" + required(fillModelId, "fillModelId"), executionDate, contractKind,
                tradedValueInr, feeInr, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, feeInr);
    }

    private static String text(String value, String field, boolean required) {
        Objects.requireNonNull(value, field);
        if (!value.equals(value.strip()) || (required && value.isBlank())) {
            throw new IllegalArgumentException(field + " must be " + (required ? "non-empty and " : "") + "trimmed");
        }
        return value;
    }

    private static String required(String value, String field) {
        return text(value, field, true);
    }
}
