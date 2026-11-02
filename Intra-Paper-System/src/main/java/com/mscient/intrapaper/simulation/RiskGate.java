package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.provenance.RunConfig;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Independent entry-limit and per-session loss gate for the offline simulator. */
public final class RiskGate {
    public record Evaluation(boolean newlyHalted, boolean halted, BigDecimal sessionLossInr) { }

    private final int maxPositionLots;
    private final BigDecimal maxNotionalInr;
    private final BigDecimal maxDailyLossInr;
    private LocalDate sessionDate;
    private BigDecimal sessionStartNetPnl = BigDecimal.ZERO;
    private boolean halted;

    public RiskGate(RunConfig config) {
        this.maxPositionLots = config.maxPositionLots();
        this.maxNotionalInr = config.maxNotionalInr();
        this.maxDailyLossInr = config.maxDailyLossInr();
    }

    public void startSession(LocalDate date, BigDecimal priorNetPnl) {
        this.sessionDate = date;
        this.sessionStartNetPnl = priorNetPnl;
        this.halted = false;
    }

    public boolean allowsEntry(int targetLots, Bar bar, BigDecimal modeledFillPrice) {
        if (halted || targetLots < 1 || targetLots > maxPositionLots || modeledFillPrice.signum() <= 0) return false;
        BigDecimal exposure = modeledFillPrice
                .multiply(BigDecimal.valueOf(targetLots))
                .multiply(BigDecimal.valueOf(bar.lotSize()));
        return exposure.compareTo(maxNotionalInr) <= 0;
    }

    public Evaluation markToMarket(BigDecimal netPnlInr) {
        if (sessionDate == null) throw new IllegalStateException("Risk session was not initialized");
        BigDecimal sessionLoss = sessionStartNetPnl.subtract(netPnlInr);
        boolean newlyHalted = !halted && sessionLoss.compareTo(maxDailyLossInr) >= 0;
        if (newlyHalted) halted = true;
        return new Evaluation(newlyHalted, halted, sessionLoss);
    }

    public boolean halted() {
        return halted;
    }
}
