package com.mscient.intrapaper.audit.reconciliation;

import com.mscient.intrapaper.simulation.portfolio.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static com.mscient.intrapaper.audit.reconciliation.LedgerSupport.*;

/** Exposes verified charge amounts without allocating totals or reconstructing missing tariff terms. */
final class LedgerChargeItems {
    private LedgerChargeItems() { }

    static void execution(List<Expected> records, PortfolioExecution fill) {
        var breakdown = fill.feeBreakdown().orElse(null);
        if (breakdown == null) {
            item(records, Kind.FILL, fill.executionId(), fill.instrumentId(), fill.timestamp(), "UNITEMIZED", null);
        } else if (breakdown.costModel() == PortfolioExecutionFeeBreakdown.CostModel.CALLER_FILL_MODEL) {
            item(records, Kind.FILL, fill.executionId(), fill.instrumentId(), fill.timestamp(), "FILL_MODEL", breakdown.legacyFillModelFeeInr());
        } else {
            String[] codes = {"BROKERAGE", "EXCHANGE", "IPFT", "SEBI", "STT", "STAMP_DUTY", "GST"};
            BigDecimal[] amounts = {breakdown.brokerageInr(), breakdown.exchangeTransactionInr(),
                    breakdown.ipftContributionInr(), breakdown.sebiTurnoverInr(), breakdown.sttInr(),
                    breakdown.stampDutyInr(), breakdown.gstInr()};
            for (int i = 0; i < codes.length; i++)
                item(records, Kind.FILL, fill.executionId(), fill.instrumentId(), fill.timestamp(), codes[i], amounts[i]);
        }
    }

    static void settlement(List<Expected> records, PortfolioSettlement settlement) {
        var event = settlement.event();
        String instrument = event.contract().instrumentId();
        if (settlement.costModel().equals("NOT_MODELED")) {
            item(records, Kind.SETTLEMENT, event.eventId(), instrument, event.timestamp(), "UNITEMIZED", null);
        } else if (settlement.signedUnits() == 0) {
            // A verified flat event has explicitly zero fees and no applicable schedule lines.
            item(records, Kind.SETTLEMENT, event.eventId(), instrument, event.timestamp(), "FLAT_NO_CHARGE", BigDecimal.ZERO);
        } else for (var line : settlement.chargeLines()) {
            item(records, Kind.SETTLEMENT, event.eventId(), instrument, event.timestamp(), "CHARGE:" + line.entry().entryId(), line.chargeInr());
            item(records, Kind.SETTLEMENT, event.eventId(), instrument, event.timestamp(), "GST:" + line.entry().entryId(), line.gstInr());
        }
    }

    private static void item(List<Expected> records, Kind type, String target, String instrument,
                             Instant timestamp, String component, BigDecimal amount) {
        records.add(new Expected(Kind.FEE, target + ":" + component, fields("event_time_utc", timestamp,
                "instrument_id", instrument, "related_type", type, "related_id", target,
                "component", component, "amount_inr", amount)));
    }
}
