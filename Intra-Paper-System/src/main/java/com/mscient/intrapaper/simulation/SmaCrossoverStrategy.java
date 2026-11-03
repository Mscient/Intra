package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.provenance.RunConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Simple deterministic baseline used to exercise the simulator, not a proposed trading edge. */
public final class SmaCrossoverStrategy implements Strategy {
    public static final String ID = "SMA_CROSSOVER_LONG_ONLY_V1";

    private final int fastBars;
    private final int slowBars;
    private final int lots;

    public SmaCrossoverStrategy(RunConfig config) {
        this.fastBars = config.fastBars();
        this.slowBars = config.slowBars();
        this.lots = config.requestedLots();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean supportsDurableCheckpoints() {
        return true;
    }

    @Override
    public Map<String, String> parameters() {
        return Map.of("fast_bars", Integer.toString(fastBars),
                "slow_bars", Integer.toString(slowBars), "lots", Integer.toString(lots));
    }

    @Override
    public StrategySignal evaluate(List<Bar> bars, Instant decisionTimestamp) {
        int index = bars.size() - 1;
        Bar bar = bars.get(index);
        if (index + 1 < slowBars) {
            return new StrategySignal(index + 1, decisionTimestamp, bar.sessionDate(), bar.instrumentId(),
                    null, "WARMING_UP", Map.of());
        }
        BigDecimal fastSum = sumCloses(bars, index - fastBars + 1, index);
        BigDecimal slowSum = sumCloses(bars, index - slowBars + 1, index);
        BigDecimal fastAverage = fastSum.divide(BigDecimal.valueOf(fastBars), 8, RoundingMode.HALF_EVEN);
        BigDecimal slowAverage = slowSum.divide(BigDecimal.valueOf(slowBars), 8, RoundingMode.HALF_EVEN);
        int targetLots = fastSum.multiply(BigDecimal.valueOf(slowBars))
                .compareTo(slowSum.multiply(BigDecimal.valueOf(fastBars))) > 0 ? lots : 0;
        Map<String, String> metrics = Map.of(
                "fast_sma", fastAverage.stripTrailingZeros().toPlainString(),
                "slow_sma", slowAverage.stripTrailingZeros().toPlainString());
        return new StrategySignal(index + 1, decisionTimestamp, bar.sessionDate(), bar.instrumentId(),
                targetLots, "ACTIVE", metrics);
    }

    private static BigDecimal sumCloses(List<Bar> bars, int from, int through) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = from; i <= through; i++) sum = sum.add(bars.get(i).close());
        return sum;
    }
}
