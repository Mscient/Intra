package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.provenance.ExpirySettlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Audit record for cash settlement from an explicit final-price input. */
public record SimSettlement(
        String instrumentId,
        LocalDate settlementDate,
        Instant settlementTimestamp,
        ExpirySettlement.Kind kind,
        BigDecimal settlementValue,
        BigDecimal exitValuePerUnit,
        int positionUnitsSettled,
        BigDecimal grossPnlInr,
        BigDecimal settlementFeesInr,
        String sourceReference) { }
