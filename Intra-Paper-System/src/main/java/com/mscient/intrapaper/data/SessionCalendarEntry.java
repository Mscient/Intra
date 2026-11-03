package com.mscient.intrapaper.data;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/** User-reviewed expected bar boundaries for one local calendar date. */
public record SessionCalendarEntry(
        LocalDate sessionDate,
        boolean open,
        Optional<Instant> firstBarTimestamp,
        Optional<Instant> lastBarTimestamp,
        String marketRuleRegimeId,
        String regimeSourceReference) {

    public SessionCalendarEntry {
        Objects.requireNonNull(sessionDate, "sessionDate");
        firstBarTimestamp = Objects.requireNonNull(firstBarTimestamp, "firstBarTimestamp");
        lastBarTimestamp = Objects.requireNonNull(lastBarTimestamp, "lastBarTimestamp");
        Objects.requireNonNull(marketRuleRegimeId, "marketRuleRegimeId");
        Objects.requireNonNull(regimeSourceReference, "regimeSourceReference");
        if (open != firstBarTimestamp.isPresent() || open != lastBarTimestamp.isPresent()) {
            throw new IllegalArgumentException("Open dates require first/last bar timestamps; closed dates require blanks");
        }
        if (marketRuleRegimeId.isBlank() != regimeSourceReference.isBlank()) {
            throw new IllegalArgumentException("Market-rule regime ID and source reference must both be present or both be blank");
        }
        if (open && marketRuleRegimeId.isBlank()) {
            throw new IllegalArgumentException("Open dates require a market-rule regime ID and source reference");
        }
        if (!marketRuleRegimeId.equals(marketRuleRegimeId.strip())
                || !regimeSourceReference.equals(regimeSourceReference.strip())) {
            throw new IllegalArgumentException("Market-rule regime ID and source reference must be trimmed");
        }
        if (!marketRuleRegimeId.isBlank()
                && !marketRuleRegimeId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("market_rule_regime_id must use letters, digits, dot, underscore, or hyphen");
        }
        if (regimeSourceReference.length() > 1000) {
            throw new IllegalArgumentException("regime_source_reference must be at most 1000 characters");
        }
        if (open && lastBarTimestamp.orElseThrow().isBefore(firstBarTimestamp.orElseThrow())) {
            throw new IllegalArgumentException("last bar timestamp must not precede first bar timestamp");
        }
    }
}
