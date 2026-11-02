package com.mscient.intrapaper.data;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

public final class SessionCalendarCsvReader {
    private SessionCalendarCsvReader() { }

    public static Set<LocalDate> read(java.nio.file.Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        table.requireHeaders("session_date", "session_status");
        Set<LocalDate> openSessions = new HashSet<>();
        Set<LocalDate> seen = new HashSet<>();
        for (int i = 0; i < table.rows().size(); i++) {
            var row = table.rows().get(i);
            LocalDate date = LocalDate.parse(required(row, "session_date"));
            if (!seen.add(date)) throw new IllegalArgumentException("Duplicate session calendar date at row " + (i + 2));
            String status = required(row, "session_status");
            if (status.equals("OPEN")) openSessions.add(date);
            else if (!status.equals("CLOSED")) throw new IllegalArgumentException("session_status must be OPEN or CLOSED at row " + (i + 2));
        }
        if (openSessions.isEmpty()) throw new IllegalArgumentException("Session calendar has no OPEN sessions");
        return Set.copyOf(openSessions);
    }

    private static String required(java.util.Map<String, String> row, String name) {
        String value = row.get(name);
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(name + " must be non-empty and trimmed");
        }
        return value;
    }
}
