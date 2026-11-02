package com.mscient.intrapaper.data;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class SessionCalendarCsvReader {
    private SessionCalendarCsvReader() { }

    public static Map<LocalDate, SessionCalendarEntry> read(java.nio.file.Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        table.requireHeaders("session_date", "session_status", "first_bar_timestamp", "last_bar_timestamp");
        Map<LocalDate, SessionCalendarEntry> sessions = new HashMap<>();
        for (int i = 0; i < table.rows().size(); i++) {
            var row = table.rows().get(i);
            LocalDate date = LocalDate.parse(required(row, "session_date"));
            String status = required(row, "session_status");
            boolean open;
            if (status.equals("OPEN")) open = true;
            else if (status.equals("CLOSED")) open = false;
            else throw new IllegalArgumentException("session_status must be OPEN or CLOSED at row " + (i + 2));
            Optional<Instant> first = optionalTimestamp(row.get("first_bar_timestamp"));
            Optional<Instant> last = optionalTimestamp(row.get("last_bar_timestamp"));
            SessionCalendarEntry entry = new SessionCalendarEntry(date, open, first, last);
            if (sessions.putIfAbsent(date, entry) != null) {
                throw new IllegalArgumentException("Duplicate session calendar date at row " + (i + 2));
            }
        }
        if (sessions.isEmpty() || sessions.values().stream().noneMatch(SessionCalendarEntry::open)) {
            throw new IllegalArgumentException("Session calendar has no OPEN sessions");
        }
        return Map.copyOf(sessions);
    }

    private static String required(Map<String, String> row, String name) {
        String value = row.get(name);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must be non-empty and trimmed");
        }
        return value;
    }

    private static Optional<Instant> optionalTimestamp(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        if (!value.equals(value.strip())) throw new IllegalArgumentException("calendar timestamps must be trimmed");
        return Optional.of(OffsetDateTime.parse(value).toInstant());
    }
}
