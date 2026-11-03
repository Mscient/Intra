package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.provenance.SettlementCostSchedule;

import java.math.BigDecimal;
import java.time.Instant;

/** One source-attributed additional settlement charge calculated from the frozen caller schedule. */
public record SimSettlementCostLine(Instant settlementTimestamp, String componentCode,
        SettlementCostSchedule.Basis basis, BigDecimal calculationBase, BigDecimal rate,
        BigDecimal chargeInr, BigDecimal gstBps, BigDecimal gstTaxableBaseInr,
        BigDecimal gstInr, String sourceReference) {

    public BigDecimal totalInr() {
        return chargeInr.add(gstInr);
    }
}
