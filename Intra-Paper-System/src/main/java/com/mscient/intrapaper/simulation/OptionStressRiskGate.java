package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.data.OptionValuationInputSchedule;
import com.mscient.intrapaper.provenance.RunConfig;

import java.math.BigDecimal;
import java.util.List;

/** Stateless pre-entry stress-loss cap using only decision-time option inputs. */
public final class OptionStressRiskGate {
    public record Check(boolean rejected, String reason, String detail) { }

    private OptionStressRiskGate() { }

    public static Check evaluate(Bar decisionBar, OptionValuationInputSchedule.Input input,
            int projectedLots, int unitsPerContract, BigDecimal bookedSessionLossInr,
            BigDecimal maxDailyLossInr,
            RunConfig.OptionStressRiskLimits limits) {
        if (decisionBar == null || input == null || projectedLots < 1 || unitsPerContract < 1) {
            return reject("OPTION_STRESS_INPUT_MISSING", "decision-bar valuation input or projected quantity is missing");
        }
        if (!decisionBar.instrumentId().equals(input.instrumentId())
                || decisionBar.contractKind() != com.mscient.intrapaper.reference.ContractKind.OPTION) {
            return reject("OPTION_STRESS_INPUT_MISMATCH", "decision-bar option contract differs from valuation input");
        }
        double underlyingShock = limits.underlyingShockPercent().doubleValue();
        double volatilityShockPoints = limits.volatilityShockPoints().doubleValue();
        if (input.timeToExpiryYears() > 0.0
                && input.annualizedVolatility() - volatilityShockPoints / 100.0 <= 0.0) {
            return reject("OPTION_STRESS_IV_NON_POSITIVE",
                    "downward volatility shock makes decision-time IV non-positive; order rejected");
        }
        long contracts = projectedLots;
        var option = new PortfolioStressAnalyzer.OptionLeg(input.instrumentId(),
                PortfolioStressAnalyzer.OptionModel.valueOf(input.model().name()),
                decisionBar.optionRight().orElseThrow(), input.underlyingPrice(),
                decisionBar.strike().orElseThrow().doubleValue(), input.timeToExpiryYears(),
                input.riskFreeRate(), input.dividendYield(), input.annualizedVolatility(),
                contracts, unitsPerContract, limits.exitHaircutPerUnitInr().doubleValue());
        PortfolioStressAnalyzer.Report report = PortfolioStressAnalyzer.analyze(
                List.of(option), List.of(), underlyingShock, volatilityShockPoints);
        double worstLoss = Math.max(0.0, -report.worstStressedExitPnl());
        BigDecimal worstLossInr = BigDecimal.valueOf(worstLoss);
        BigDecimal bookedLoss = bookedSessionLossInr.max(BigDecimal.ZERO);
        BigDecimal remainingDailyLossBudget = maxDailyLossInr.subtract(bookedLoss).max(BigDecimal.ZERO);
        boolean exceedsStressCap = worstLossInr.compareTo(limits.maxLossInr()) > 0;
        boolean exceedsDailyLossBudget = worstLossInr.compareTo(remainingDailyLossBudget) > 0;
        boolean rejected = exceedsStressCap || exceedsDailyLossBudget;
        String reason = exceedsDailyLossBudget ? "OPTION_STRESS_DAILY_LOSS_BUDGET" :
                exceedsStressCap ? "OPTION_STRESS_LOSS_LIMIT" : "";
        String detail = "model=" + input.model() + "; decision_timestamp=" + input.timestamp()
                + "; source_reference=" + input.sourceReference()
                + "; worst_scenario=" + report.worstScenarioId()
                + "; worst_stress_loss_inr=" + worstLossInr.toPlainString()
                + "; limit_inr=" + limits.maxLossInr().toPlainString()
                + "; booked_session_loss_inr=" + bookedLoss.toPlainString()
                + "; remaining_daily_loss_budget_inr=" + remainingDailyLossBudget.toPlainString()
                + "; underlying_shock_percent=" + limits.underlyingShockPercent().toPlainString()
                + "; volatility_shock_points=" + limits.volatilityShockPoints().toPlainString();
        return new Check(rejected, reason, detail);
    }

    private static Check reject(String reason, String detail) {
        return new Check(true, reason, detail);
    }
}
