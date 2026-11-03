package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.provenance.ComponentCostSchedule;
import com.mscient.intrapaper.reference.ContractKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Expiry-level option-exercise and source-supplied additional settlement charge audit. */
public record SimSettlementChargeBreakdown(
        Instant settlementTimestamp,
        String model,
        ContractKind contractKind,
        int positionUnits,
        BigDecimal optionIntrinsicPerUnitInr,
        BigDecimal exerciseSttBaseInr,
        BigDecimal sebiExerciseTurnoverBaseInr,
        BigDecimal sebiTurnoverInr,
        BigDecimal exerciseSttInr,
        BigDecimal gstTaxableBaseInr,
        BigDecimal gstInr,
        BigDecimal additionalSettlementFeesInr) {

    public SimSettlementChargeBreakdown {
        if (settlementTimestamp == null || contractKind == null) throw new IllegalArgumentException("settlement identity is required");
        if (positionUnits < 0) throw new IllegalArgumentException("positionUnits must be non-negative");
        if (!List.of("NOT_MODELED", "COMPONENTIZED_OPTION_EXERCISE", "COMPONENTIZED_SETTLEMENT_COSTS",
                "COMPONENTIZED_OPTION_EXERCISE_AND_SETTLEMENT_COSTS").contains(model)) {
            throw new IllegalArgumentException("unsupported settlement cost model: " + model);
        }
        for (BigDecimal value : List.of(optionIntrinsicPerUnitInr, exerciseSttBaseInr,
                sebiExerciseTurnoverBaseInr, sebiTurnoverInr, exerciseSttInr, gstTaxableBaseInr, gstInr,
                additionalSettlementFeesInr)) {
            if (value.signum() < 0) throw new IllegalArgumentException("settlement charge values must be non-negative");
        }
    }

    public BigDecimal totalInr() {
        return sebiTurnoverInr.add(exerciseSttInr).add(gstInr).add(additionalSettlementFeesInr);
    }

    public static SimSettlementChargeBreakdown notModeled(Instant timestamp, ContractKind kind, int units,
            BigDecimal optionIntrinsicPerUnitInr, BigDecimal sebiTurnoverPerUnitInr) {
        BigDecimal exerciseSttBase = optionIntrinsicPerUnitInr.multiply(BigDecimal.valueOf(units));
        BigDecimal sebiBase = sebiTurnoverPerUnitInr.multiply(BigDecimal.valueOf(units));
        return new SimSettlementChargeBreakdown(timestamp, "NOT_MODELED", kind, units,
                optionIntrinsicPerUnitInr, exerciseSttBase, sebiBase, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public static SimSettlementChargeBreakdown componentized(Instant timestamp, ContractKind kind, int units,
            BigDecimal optionIntrinsicPerUnitInr, BigDecimal sebiTurnoverPerUnitInr,
            ComponentCostSchedule.Charges charges) {
        BigDecimal exerciseSttBase = optionIntrinsicPerUnitInr.multiply(BigDecimal.valueOf(units));
        BigDecimal sebiBase = sebiTurnoverPerUnitInr.multiply(BigDecimal.valueOf(units));
        return new SimSettlementChargeBreakdown(timestamp, "COMPONENTIZED_OPTION_EXERCISE", kind, units,
                optionIntrinsicPerUnitInr, exerciseSttBase, sebiBase, charges.sebiInr(), charges.sttInr(),
                charges.gstTaxableBaseInr(), charges.gstInr(), BigDecimal.ZERO);
    }

    public SimSettlementChargeBreakdown withAdditionalSettlementFees(BigDecimal additionalFeesInr,
            boolean settlementSchedulePresent) {
        String updatedModel = model;
        if (settlementSchedulePresent) {
            updatedModel = model.equals("COMPONENTIZED_OPTION_EXERCISE")
                    ? "COMPONENTIZED_OPTION_EXERCISE_AND_SETTLEMENT_COSTS" : "COMPONENTIZED_SETTLEMENT_COSTS";
        }
        return new SimSettlementChargeBreakdown(settlementTimestamp, updatedModel, contractKind, positionUnits,
                optionIntrinsicPerUnitInr, exerciseSttBaseInr, sebiExerciseTurnoverBaseInr, sebiTurnoverInr,
                exerciseSttInr, gstTaxableBaseInr, gstInr, additionalFeesInr);
    }
}
