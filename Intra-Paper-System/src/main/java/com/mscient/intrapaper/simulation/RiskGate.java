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
    private final BigDecimal marginPerLotInr;
    private final BigDecimal maxMarginInr;
    private final BigDecimal maxDailyLossInr;
    private LocalDate sessionDate;
    private BigDecimal sessionStartNetPnl = BigDecimal.ZERO;
    private boolean halted;

    public RiskGate(RunConfig config) {
        this.maxPositionLots = config.maxPositionLots();
        this.maxNotionalInr = config.maxNotionalInr();
        this.marginPerLotInr = config.marginPerLotInr();
        this.maxMarginInr = config.maxMarginInr();
        this.maxDailyLossInr = config.maxDailyLossInr();
    }

    public void startSession(LocalDate date, BigDecimal priorNetPnl) {
        this.sessionDate = date;
        this.sessionStartNetPnl = priorNetPnl;
        this.halted = false;
    }

    public String entryRejectionReason(int targetLots, Bar bar, BigDecimal modeledFillPrice) {
        if (halted) return "SESSION_LOSS_GATE_HALTED";
        if (targetLots < 1 || targetLots > maxPositionLots) return "MAX_POSITION_LOTS";
        if (modeledFillPrice.signum() <= 0) return "NON_POSITIVE_MODELED_FILL_PRICE";
        BigDecimal exposure = modeledFillPrice
                .multiply(BigDecimal.valueOf(targetLots))
                .multiply(BigDecimal.valueOf(bar.lotSize()));
        if (exposure.compareTo(maxNotionalInr) > 0) return "MAX_NOTIONAL_INR";
        if (marginPerLotInr.multiply(BigDecimal.valueOf(targetLots)).compareTo(maxMarginInr) > 0) {
            return "MAX_MARGIN_PROXY_INR";
        }
        return null;
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
