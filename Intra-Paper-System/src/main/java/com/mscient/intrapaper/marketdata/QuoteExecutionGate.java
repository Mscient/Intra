package com.mscient.intrapaper.marketdata;

import java.time.Instant;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;

/** Full-size top-of-book eligibility check for future quote-driven simulation callers. */
public final class QuoteExecutionGate {
    public enum Side { BUY, SELL }

    public record Assessment(boolean executable, String reason, long requestedQuantity, long displayedQuantity,
            OptionalLong referencePriceTicks, OptionalLong modeledFillPriceTicks,
            Set<QuoteQualityFlag> qualityFlags) {
        public Assessment {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(referencePriceTicks, "referencePriceTicks");
            Objects.requireNonNull(modeledFillPriceTicks, "modeledFillPriceTicks");
            qualityFlags = Set.copyOf(qualityFlags);
            if (requestedQuantity < 1 || displayedQuantity < 0) {
                throw new IllegalArgumentException("requested quantity must be positive and displayed quantity non-negative");
            }
            if (executable != (referencePriceTicks.isPresent() && modeledFillPriceTicks.isPresent())) {
                throw new IllegalArgumentException("Executable assessments must contain both prices; rejected assessments must contain neither");
            }
        }
    }

    private QuoteExecutionGate() { }

    /**
     * Requires a matching, fresh, structurally sound quote with enough displayed size for a full
     * fill. BUY uses ask and SELL uses bid; adverse slippage is added in integer ticks. No partial,
     * queue, or latency model is implied.
     */
    public static Assessment assess(MarketQuote quote, String expectedInstrumentId, Instant evaluatedAtUtc,
            Side side, long requestedQuantity, int adverseSlippageTicks, QuoteQualityPolicy qualityPolicy) {
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(evaluatedAtUtc, "evaluatedAtUtc");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(qualityPolicy, "qualityPolicy");
        if (expectedInstrumentId == null || expectedInstrumentId.isBlank()
                || !expectedInstrumentId.equals(expectedInstrumentId.strip())) {
            throw new IllegalArgumentException("expectedInstrumentId must be non-empty and trimmed");
        }
        if (requestedQuantity < 1) throw new IllegalArgumentException("requestedQuantity must be positive");
        if (adverseSlippageTicks < 0) throw new IllegalArgumentException("adverseSlippageTicks must be non-negative");

        Set<QuoteQualityFlag> flags = qualityPolicy.assess(quote, evaluatedAtUtc);
        if (!quote.instrumentId().equals(expectedInstrumentId)) {
            return rejected("INSTRUMENT_MISMATCH", requestedQuantity, 0, flags);
        }
        if (quote.receivedAtUtc().isAfter(evaluatedAtUtc)) {
            return rejected("QUOTE_RECEIVED_AFTER_DECISION_TIME", requestedQuantity, 0, flags);
        }
        if (!flags.isEmpty()) return rejected("QUOTE_QUALITY_FLAGS", requestedQuantity, 0, flags);

        long referenceTicks = side == Side.BUY ? quote.askPriceTicks() : quote.bidPriceTicks();
        long displayedQuantity = side == Side.BUY ? quote.askQuantity() : quote.bidQuantity();
        if (displayedQuantity < requestedQuantity) {
            return rejected(side == Side.BUY ? "INSUFFICIENT_ASK_SIZE" : "INSUFFICIENT_BID_SIZE",
                    requestedQuantity, displayedQuantity, flags);
        }
        long fillTicks;
        try {
            fillTicks = side == Side.BUY
                    ? Math.addExact(referenceTicks, adverseSlippageTicks)
                    : Math.subtractExact(referenceTicks, adverseSlippageTicks);
        } catch (ArithmeticException overflow) {
            return rejected("MODELED_PRICE_OVERFLOW", requestedQuantity, displayedQuantity, flags);
        }
        if (fillTicks <= 0) return rejected("NON_POSITIVE_MODELED_FILL_PRICE", requestedQuantity, displayedQuantity, flags);
        return new Assessment(true, "FULL_SIZE_TOP_OF_BOOK", requestedQuantity, displayedQuantity,
                OptionalLong.of(referenceTicks), OptionalLong.of(fillTicks), flags);
    }

    private static Assessment rejected(String reason, long requestedQuantity, long displayedQuantity,
            Set<QuoteQualityFlag> flags) {
        return new Assessment(false, reason, requestedQuantity, displayedQuantity,
                OptionalLong.empty(), OptionalLong.empty(), flags);
    }
}
