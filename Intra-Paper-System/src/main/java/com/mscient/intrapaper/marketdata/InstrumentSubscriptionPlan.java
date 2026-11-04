package com.mscient.intrapaper.marketdata;

import com.mscient.intrapaper.data.CsvTable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Caller-declared UTC windows binding stable instrument IDs to local feed subscription IDs. */
public record InstrumentSubscriptionPlan(int schemaVersion, String sha256, List<Window> windows) {
    public static final List<String> HEADERS = List.of("window_id", "source", "connection_id", "subscription_id",
            "instrument_id", "window_start_utc", "window_end_utc");

    public record Window(String windowId, String source, String connectionId, String subscriptionId,
            String instrumentId, Instant startUtc, Instant endUtc) {
        public Window {
            windowId = required(windowId, "window_id");
            source = required(source, "source");
            connectionId = required(connectionId, "connection_id");
            subscriptionId = required(subscriptionId, "subscription_id");
            instrumentId = required(instrumentId, "instrument_id");
            if (startUtc == null || endUtc == null || !startUtc.isBefore(endUtc)) {
                throw new IllegalArgumentException("subscription window must be a non-empty [start, end) interval");
            }
            try { java.time.Duration.between(startUtc, endUtc).toMillis(); }
            catch (ArithmeticException overflow) { throw new IllegalArgumentException("subscription window duration is too large", overflow); }
        }
    }

    public InstrumentSubscriptionPlan {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported instrument subscription plan version");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid plan SHA-256");
        windows = List.copyOf(windows);
        if (windows.isEmpty()) throw new IllegalArgumentException("Instrument subscription plan must contain a window");
        validate(windows);
    }

    public static InstrumentSubscriptionPlan load(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        if (!table.headers().equals(HEADERS)) {
            throw new IllegalArgumentException("Instrument subscription plan CSV headers must exactly match: "
                    + String.join(",", HEADERS));
        }
        List<Window> windows = new ArrayList<>();
        for (int i = 0; i < table.rows().size(); i++) {
            Map<String, String> row = table.rows().get(i);
            try {
                windows.add(new Window(required(row, "window_id"), required(row, "source"),
                        required(row, "connection_id"), required(row, "subscription_id"),
                        required(row, "instrument_id"), parseUtc(required(row, "window_start_utc")),
                        parseUtc(required(row, "window_end_utc"))));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid instrument subscription plan row " + (i + 2)
                        + ": " + invalid.getMessage(), invalid);
            }
        }
        return new InstrumentSubscriptionPlan(1, table.sha256(), windows);
    }

    private static void validate(List<Window> windows) {
        Set<String> ids = new HashSet<>();
        for (Window window : windows) {
            if (!ids.add(window.windowId())) throw new IllegalArgumentException("Duplicate subscription window_id: " + window.windowId());
        }
        var grouped = windows.stream().collect(java.util.stream.Collectors.groupingBy(InstrumentSubscriptionPlan::key));
        for (var entry : grouped.entrySet()) {
            List<Window> sorted = entry.getValue().stream().sorted(Comparator.comparing(Window::startUtc)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).startUtc().isBefore(sorted.get(i - 1).endUtc())) {
                    throw new IllegalArgumentException("Overlapping windows for source/connection/subscription/instrument "
                            + entry.getKey() + ": " + sorted.get(i - 1).windowId() + " and " + sorted.get(i).windowId());
                }
            }
        }
    }

    static String key(Window window) {
        return window.source() + "\u0000" + window.connectionId() + "\u0000" + window.subscriptionId()
                + "\u0000" + window.instrumentId();
    }

    private static Instant parseUtc(String value) {
        if (!value.endsWith("Z")) throw new IllegalArgumentException("timestamps must be UTC instants ending in Z");
        try { return Instant.parse(value); }
        catch (DateTimeParseException invalid) { throw new IllegalArgumentException("invalid UTC instant", invalid); }
    }

    private static String required(Map<String, String> row, String field) { return required(row.get(field), field); }
    private static String required(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be non-empty and trimmed");
        }
        return value;
    }
}
