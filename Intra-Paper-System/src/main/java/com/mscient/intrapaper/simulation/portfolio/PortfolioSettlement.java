package com.mscient.intrapaper.simulation.portfolio;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Audited extinguishment of the actual signed residual position, separate from executions. */
public record PortfolioSettlement(PortfolioSettlementEvents.Event event, long signedUnits,
        BigDecimal releasedBasisInr, BigDecimal signedProceedsInr, BigDecimal realizedGrossPnlInr,
        String costModel, List<PortfolioSettlementCostSchedule.Line> chargeLines) {
    public PortfolioSettlement {
        Objects.requireNonNull(event); Objects.requireNonNull(releasedBasisInr);
        Objects.requireNonNull(signedProceedsInr); Objects.requireNonNull(realizedGrossPnlInr);
        Math.absExact(signedUnits);
        if (releasedBasisInr.signum() < 0 || (signedUnits == 0 && releasedBasisInr.signum() != 0))
            throw new IllegalArgumentException("Invalid released settlement basis");
        if (signedUnits % event.contract().lotSize() != 0) throw new IllegalArgumentException("Settlement position is not whole dated lots");
        if (signedProceedsInr.compareTo(event.valuePerUnit().multiply(BigDecimal.valueOf(signedUnits))) != 0
                || realizedGrossPnlInr.compareTo(signedProceedsInr.subtract(releasedBasisInr.multiply(BigDecimal.valueOf(Long.signum(signedUnits))))) != 0)
            throw new IllegalArgumentException("Settlement proceeds/basis/P&L do not reconcile");
        if (!List.of("NOT_MODELED", "DATED_SCHEDULE").contains(costModel)) throw new IllegalArgumentException("Unknown settlement cost model");
        chargeLines = List.copyOf(chargeLines);
        if ((signedUnits == 0 || costModel.equals("NOT_MODELED")) && !chargeLines.isEmpty())
            throw new IllegalArgumentException("Flat or unmodeled settlement cannot have charge lines");
        for (var line : chargeLines) if (!line.eventId().equals(event.eventId()) || !line.entry().contract().equals(event.contract()))
            throw new IllegalArgumentException("Settlement charge line identity mismatch");
    }
    public BigDecimal feesInr() { return chargeLines.stream().map(PortfolioSettlementCostSchedule.Line::totalInr).reduce(BigDecimal.ZERO, BigDecimal::add); }
}
