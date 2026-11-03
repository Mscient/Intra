package com.mscient.intrapaper.simulation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Descriptive run statistics; these values are never marked eligible for performance claims. */
public record DiagnosticMetrics(
        int completedTrades,
        int winningTrades,
        int losingTrades,
        int breakevenTrades,
        BigDecimal completedTradeNetPnlInr,
        Optional<BigDecimal> winRate,
        Optional<BigDecimal> averageNetPnlPerTradeInr,
        Optional<BigDecimal> netProfitFactor,
        Optional<BigDecimal> averageHoldingSeconds,
        long filledUnits,
        BigDecimal filledTurnoverInr) {

    public static DiagnosticMetrics from(BacktestResult result) {
        List<SimTrade> trades = result.trades();
        int winners = 0;
        int losers = 0;
        int breakeven = 0;
        BigDecimal netTrades = BigDecimal.ZERO;
        BigDecimal netWinners = BigDecimal.ZERO;
        BigDecimal netLosers = BigDecimal.ZERO;
        long totalHoldingSeconds = 0;
        for (SimTrade trade : trades) {
            BigDecimal net = trade.netPnlInr();
            netTrades = netTrades.add(net);
            if (net.signum() > 0) {
                winners++;
                netWinners = netWinners.add(net);
            } else if (net.signum() < 0) {
                losers++;
                netLosers = netLosers.add(net);
            } else {
                breakeven++;
            }
            totalHoldingSeconds = Math.addExact(totalHoldingSeconds,
                    Duration.between(trade.entryTimestamp(), trade.exitTimestamp()).getSeconds());
        }

        long units = 0;
        BigDecimal turnover = BigDecimal.ZERO;
        for (SimFill fill : result.fills()) {
            units = Math.addExact(units, fill.quantityUnits());
            turnover = turnover.add(fill.fillPrice().multiply(BigDecimal.valueOf(fill.quantityUnits())));
        }

        int count = trades.size();
        Optional<BigDecimal> winRate = count == 0 ? Optional.empty()
                : Optional.of(BigDecimal.valueOf(winners).divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_EVEN));
        Optional<BigDecimal> averageNet = count == 0 ? Optional.empty()
                : Optional.of(netTrades.divide(BigDecimal.valueOf(count), 8, RoundingMode.HALF_EVEN));
        Optional<BigDecimal> profitFactor = netLosers.signum() == 0 ? Optional.empty()
                : Optional.of(netWinners.divide(netLosers.abs(), 8, RoundingMode.HALF_EVEN));
        Optional<BigDecimal> averageHolding = count == 0 ? Optional.empty()
                : Optional.of(BigDecimal.valueOf(totalHoldingSeconds)
                        .divide(BigDecimal.valueOf(count), 3, RoundingMode.HALF_EVEN));
        return new DiagnosticMetrics(count, winners, losers, breakeven, netTrades,
                winRate, averageNet, profitFactor, averageHolding, units, turnover);
    }
}
