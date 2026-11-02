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
        Optional<Instant> lastBarTimestamp) {

    public SessionCalendarEntry {
        Objects.requireNonNull(sessionDate, "sessionDate");
        firstBarTimestamp = Objects.requireNonNull(firstBarTimestamp, "firstBarTimestamp");
        lastBarTimestamp = Objects.requireNonNull(lastBarTimestamp, "lastBarTimestamp");
        if (open != firstBarTimestamp.isPresent() || open != lastBarTimestamp.isPresent()) {
            throw new IllegalArgumentException("Open dates require first/last bar timestamps; closed dates require blanks");
        }
        if (open && lastBarTimestamp.orElseThrow().isBefore(firstBarTimestamp.orElseThrow())) {
            throw new IllegalArgumentException("last bar timestamp must not precede first bar timestamp");
        }
    }
}
