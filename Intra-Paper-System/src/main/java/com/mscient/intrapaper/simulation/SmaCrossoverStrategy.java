package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.provenance.RunConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/** Simple deterministic baseline used to exercise the simulator, not a proposed trading edge. */
public final class SmaCrossoverStrategy {
    public static final String ID = "SMA_CROSSOVER_LONG_ONLY_V1";

    private final int fastBars;
    private final int slowBars;
    private final int lots;

    public SmaCrossoverStrategy(RunConfig config) {
        this.fastBars = config.fastBars();
        this.slowBars = config.slowBars();
        this.lots = config.requestedLots();
    }

    public StrategySignal evaluate(List<Bar> bars, int index, Instant decisionTimestamp) {
        Bar bar = bars.get(index);
        if (index + 1 < slowBars) {
            return new StrategySignal(index + 1, decisionTimestamp, bar.sessionDate(), bar.instrumentId(),
                    null, null, null, "WARMING_UP");
        }
        BigDecimal fastSum = sumCloses(bars, index - fastBars + 1, index);
        BigDecimal slowSum = sumCloses(bars, index - slowBars + 1, index);
        BigDecimal fastAverage = fastSum.divide(BigDecimal.valueOf(fastBars), 8, RoundingMode.HALF_EVEN);
        BigDecimal slowAverage = slowSum.divide(BigDecimal.valueOf(slowBars), 8, RoundingMode.HALF_EVEN);
        int targetLots = fastSum.multiply(BigDecimal.valueOf(slowBars))
                .compareTo(slowSum.multiply(BigDecimal.valueOf(fastBars))) > 0 ? lots : 0;
        return new StrategySignal(index + 1, decisionTimestamp, bar.sessionDate(), bar.instrumentId(),
                fastAverage, slowAverage, targetLots, "ACTIVE");
    }

    private static BigDecimal sumCloses(List<Bar> bars, int from, int through) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = from; i <= through; i++) sum = sum.add(bars.get(i).close());
        return sum;
    }
}
