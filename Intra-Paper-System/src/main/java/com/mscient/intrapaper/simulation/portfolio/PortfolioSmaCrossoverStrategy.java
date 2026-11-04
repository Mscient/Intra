package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.SynchronizedBarFrameBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Long-only per-instrument SMA plumbing strategy for the offline portfolio CLI. */
public final class PortfolioSmaCrossoverStrategy implements PortfolioStrategy {
    private final int fastBars;
    private final int slowBars;
    private final int longLots;
    private final Map<String, String> parameters;

    public PortfolioSmaCrossoverStrategy(int fastBars, int slowBars, int longLots) {
        if (fastBars < 2 || slowBars <= fastBars || longLots < 1) {
            throw new IllegalArgumentException("Require 2 <= fastBars < slowBars and positive longLots");
        }
        this.fastBars = fastBars;
        this.slowBars = slowBars;
        this.longLots = longLots;
        this.parameters = Map.of("fast_bars", Integer.toString(fastBars),
                "long_lots", Integer.toString(longLots), "slow_bars", Integer.toString(slowBars));
    }

    @Override public String id() { return "PORTFOLIO_LONG_ONLY_SMA_CROSSOVER_V1"; }
    @Override public Map<String, String> parameters() { return parameters; }

    @Override
    public PortfolioTarget evaluate(List<SynchronizedBarFrameBuilder.Frame> availableFrames,
            Instant decisionTimestamp, PortfolioAccountingLedger.Snapshot completedBarSnapshot) {
        if (availableFrames.isEmpty()) throw new IllegalArgumentException("SMA strategy requires completed frames");
        Map<String, Integer> targets = new LinkedHashMap<>();
        for (String instrumentId : new TreeMap<>(availableFrames.getLast().barsByInstrument()).keySet()) {
            int lots = 0;
            if (availableFrames.size() >= slowBars) {
                BigDecimal fastSum = BigDecimal.ZERO;
                BigDecimal slowSum = BigDecimal.ZERO;
                for (int index = availableFrames.size() - fastBars; index < availableFrames.size(); index++) {
                    fastSum = fastSum.add(availableFrames.get(index).barsByInstrument().get(instrumentId).close());
                }
                for (int index = availableFrames.size() - slowBars; index < availableFrames.size(); index++) {
                    slowSum = slowSum.add(availableFrames.get(index).barsByInstrument().get(instrumentId).close());
                }
                BigDecimal fastScaled = fastSum.multiply(BigDecimal.valueOf(slowBars));
                BigDecimal slowScaled = slowSum.multiply(BigDecimal.valueOf(fastBars));
                if (fastScaled.compareTo(slowScaled) > 0) lots = longLots;
            }
            targets.put(instrumentId, lots);
        }
        return new PortfolioTarget(targets, "per-instrument long-only SMA crossover");
    }
}
