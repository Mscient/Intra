package com.mscient.intrapaper.marketdata;

import com.mscient.intrapaper.data.CsvTable;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Caller-supplied UTC windows against which an offline quote capture can be checked. */
public record QuoteCoveragePlan(int schemaVersion, String sha256, List<Window> windows) {
    private static final List<String> HEADERS = List.of("window_id", "source", "instrument_id",
            "window_start_utc", "window_end_utc", "max_silence_ms");

    public record Window(String windowId, String source, String instrumentId, Instant startUtc,
            Instant endUtc, long maximumSilenceMillis) {
        public Window {
            windowId = required(windowId, "window_id");
            source = required(source, "source");
            instrumentId = required(instrumentId, "instrument_id");
            if (startUtc == null || endUtc == null || !startUtc.isBefore(endUtc)) {
                throw new IllegalArgumentException("coverage window must be a non-empty [start, end) interval");
            }
            if (maximumSilenceMillis < 1) throw new IllegalArgumentException("max_silence_ms must be positive");
        }
    }

    public QuoteCoveragePlan {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported QuoteCoveragePlan schema version");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid coverage plan SHA-256");
        windows = List.copyOf(windows);
        if (windows.isEmpty()) throw new IllegalArgumentException("Coverage plan must contain at least one window");
        validateWindows(windows);
    }

    public static QuoteCoveragePlan load(Path path) throws IOException {
        CsvTable table = CsvTable.read(path);
        if (!table.headers().equals(HEADERS)) {
            throw new IllegalArgumentException("Quote coverage CSV headers must exactly match: " + String.join(",", HEADERS));
        }
        List<Window> windows = new ArrayList<>();
        for (int i = 0; i < table.rows().size(); i++) {
            Map<String, String> row = table.rows().get(i);
            try {
                windows.add(new Window(required(row, "window_id"), required(row, "source"),
                        required(row, "instrument_id"), parseUtc(required(row, "window_start_utc")),
                        parseUtc(required(row, "window_end_utc")), Long.parseLong(required(row, "max_silence_ms"))));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("Invalid quote coverage CSV row " + (i + 2) + ": " + invalid.getMessage(), invalid);
            }
        }
        if (windows.isEmpty()) throw new IllegalArgumentException("Quote coverage CSV contains no windows");
        return new QuoteCoveragePlan(1, table.sha256(), windows);
    }

    private static void validateWindows(List<Window> windows) {
        Set<String> ids = new HashSet<>();
        for (Window window : windows) {
            if (!ids.add(window.windowId())) throw new IllegalArgumentException("Duplicate quote coverage window_id: " + window.windowId());
        }
        Map<Key, List<Window>> grouped = windows.stream().collect(java.util.stream.Collectors.groupingBy(
                item -> new Key(item.source(), item.instrumentId())));
        for (var entry : grouped.entrySet()) {
            List<Window> sorted = entry.getValue().stream().sorted(Comparator.comparing(Window::startUtc)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).startUtc().isBefore(sorted.get(i - 1).endUtc())) {
                    throw new IllegalArgumentException("Overlapping quote coverage windows for source "
                            + entry.getKey().source() + " and instrument " + entry.getKey().instrumentId()
                            + ": " + sorted.get(i - 1).windowId() + " and " + sorted.get(i).windowId());
                }
            }
        }
    }

    private static Instant parseUtc(String value) {
        if (!value.endsWith("Z")) throw new IllegalArgumentException("coverage timestamps must be explicit UTC instants ending in Z");
        return Instant.parse(value);
    }

    private static String required(Map<String, String> row, String field) { return required(row.get(field), field); }

    private static String required(String value, String field) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException(field + " must be non-empty and trimmed");
        }
        return value;
    }

    private record Key(String source, String instrumentId) { }
}
