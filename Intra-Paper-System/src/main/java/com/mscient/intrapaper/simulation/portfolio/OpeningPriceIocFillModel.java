package com.mscient.intrapaper.simulation.portfolio;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/** Full-quantity IOC plumbing fill at the next bar open with explicit adverse tick slippage and fee. */
public final class OpeningPriceIocFillModel implements PortfolioFillModel {
    private final int slippageTicks;
    private final BigDecimal feePerExecutionInr;
    private final Map<String, String> parameters;

    public OpeningPriceIocFillModel(int slippageTicks, BigDecimal feePerExecutionInr) {
        if (slippageTicks < 0 || feePerExecutionInr == null || feePerExecutionInr.signum() < 0) {
            throw new IllegalArgumentException("Slippage ticks and execution fee must be non-negative");
        }
        this.slippageTicks = slippageTicks;
        this.feePerExecutionInr = feePerExecutionInr.stripTrailingZeros();
        this.parameters = Map.of("fee_per_execution_inr", canonical(this.feePerExecutionInr),
                "slippage_ticks", Integer.toString(slippageTicks));
    }

    @Override public String id() { return "OPEN_PRICE_FULL_IOC_V1"; }
    @Override public Map<String, String> parameters() { return parameters; }

    @Override
    public Optional<Fill> fill(PortfolioReplayEngine.OrderIntent intent, OpeningMarketPoint marketAtOpen) {
        BigDecimal adverseSlippage = marketAtOpen.tickSize().multiply(BigDecimal.valueOf(slippageTicks));
        BigDecimal price = intent.side() == PortfolioExecution.Side.BUY
                ? marketAtOpen.openPrice().add(adverseSlippage)
                : marketAtOpen.openPrice().subtract(adverseSlippage);
        if (price.signum() <= 0) throw new IllegalArgumentException("Adverse sell slippage would make fill price non-positive");
        return Optional.of(new Fill(intent.requestedUnits(), price, feePerExecutionInr));
    }

    private static String canonical(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
}
