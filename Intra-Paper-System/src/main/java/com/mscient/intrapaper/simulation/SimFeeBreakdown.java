package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.provenance.ComponentCostSchedule;
import com.mscient.intrapaper.reference.ContractKind;

import java.math.BigDecimal;

/** Auditable executed-fill charge detail; settlement charges are recorded separately. */
public record SimFeeBreakdown(
        String fillId,
        String costModel,
        ContractKind contractKind,
        BigDecimal tradedValueInr,
        BigDecimal legacyFeeInr,
        BigDecimal brokerageInr,
        BigDecimal exchangeTransactionInr,
        BigDecimal ipftContributionInr,
        BigDecimal sebiTurnoverInr,
        BigDecimal sttInr,
        BigDecimal stampDutyInr,
        BigDecimal gstTaxableBaseInr,
        BigDecimal gstInr) {

    public SimFeeBreakdown {
        if (fillId == null || fillId.isBlank()) throw new IllegalArgumentException("fillId is required");
        if (!costModel.equals("LEGACY_ALL_IN") && !costModel.equals("COMPONENTIZED")) {
            throw new IllegalArgumentException("unsupported cost model: " + costModel);
        }
        if (tradedValueInr.signum() < 0 || legacyFeeInr.signum() < 0 || brokerageInr.signum() < 0
                || exchangeTransactionInr.signum() < 0 || ipftContributionInr.signum() < 0
                || sebiTurnoverInr.signum() < 0 || sttInr.signum() < 0
                || stampDutyInr.signum() < 0 || gstTaxableBaseInr.signum() < 0 || gstInr.signum() < 0) {
            throw new IllegalArgumentException("fee breakdown values must be non-negative");
        }
    }

    public BigDecimal totalInr() {
        return legacyFeeInr.add(brokerageInr).add(exchangeTransactionInr).add(ipftContributionInr)
                .add(sebiTurnoverInr).add(sttInr).add(stampDutyInr).add(gstInr);
    }

    public static SimFeeBreakdown legacy(String fillId, ContractKind kind,
            BigDecimal tradedValueInr, BigDecimal totalFeeInr) {
        return new SimFeeBreakdown(fillId, "LEGACY_ALL_IN", kind, tradedValueInr,
                totalFeeInr, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public static SimFeeBreakdown componentized(String fillId, ContractKind kind,
            BigDecimal tradedValueInr, ComponentCostSchedule.Charges charges) {
        return new SimFeeBreakdown(fillId, "COMPONENTIZED", kind, tradedValueInr,
                BigDecimal.ZERO, charges.brokerageInr(), charges.exchangeInr(), charges.ipftInr(),
                charges.sebiInr(), charges.sttInr(), charges.stampDutyInr(), charges.gstTaxableBaseInr(), charges.gstInr());
    }
}
