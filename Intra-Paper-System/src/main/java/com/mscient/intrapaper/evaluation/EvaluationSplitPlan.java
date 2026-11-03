package com.mscient.intrapaper.evaluation;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.data.ValidatedDataset;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Validates non-overlapping chronological development, validation, and holdout session assignments. */
public record EvaluationSplitPlan(Map<Partition, List<LocalDate>> sessions, String splitFileSha256) {
    public enum Partition { DEVELOPMENT, VALIDATION, HOLDOUT }

    public record Summary(int sessionCount, LocalDate firstSession, LocalDate lastSession) { }

    public EvaluationSplitPlan {
        EnumMap<Partition, List<LocalDate>> copy = new EnumMap<>(Partition.class);
        sessions.forEach((partition, dates) -> copy.put(partition, List.copyOf(dates)));
        sessions = Map.copyOf(copy);
        if (!splitFileSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("splitFileSha256 must be lowercase SHA-256 hex");
        }
    }

    public static EvaluationSplitPlan load(Path splitPath, ValidatedDataset dataset) throws IOException {
        CsvTable table = CsvTable.read(splitPath);
        if (!table.headers().equals(List.of("session_date", "partition"))) {
            throw new IllegalArgumentException("Evaluation split CSV headers must be exactly: session_date,partition");
        }
        TreeMap<LocalDate, Partition> assignments = new TreeMap<>();
        for (int i = 0; i < table.rows().size(); i++) {
            Map<String, String> row = table.rows().get(i);
            String dateText = required(row.get("session_date"), "session_date", i + 2);
            String partitionText = required(row.get("partition"), "partition", i + 2);
            LocalDate date = LocalDate.parse(dateText);
            Partition partition = Partition.valueOf(partitionText);
            if (assignments.putIfAbsent(date, partition) != null) {
                throw new IllegalArgumentException("Duplicate evaluation-split session date: " + date);
            }
        }

        LocalDate first = dataset.bars().getFirst().sessionDate();
        LocalDate last = dataset.bars().getLast().sessionDate();
        List<LocalDate> expected = dataset.sessionCalendar().values().stream()
                .filter(entry -> entry.open() && !entry.sessionDate().isBefore(first) && !entry.sessionDate().isAfter(last))
                .map(entry -> entry.sessionDate()).sorted().toList();
        if (!assignments.keySet().equals(new TreeSet<>(expected))) {
            throw new IllegalArgumentException("Evaluation split must assign every and only open session in the dataset date range");
        }

        EnumMap<Partition, List<LocalDate>> splitSessions = new EnumMap<>(Partition.class);
        for (Partition partition : Partition.values()) splitSessions.put(partition, new ArrayList<>());
        int lastRank = -1;
        for (LocalDate date : expected) {
            Partition partition = assignments.get(date);
            if (partition.ordinal() < lastRank) {
                throw new IllegalArgumentException("Evaluation partitions must be contiguous and chronological; found " + date);
            }
            lastRank = partition.ordinal();
            splitSessions.get(partition).add(date);
        }
        for (Partition partition : Partition.values()) {
            if (splitSessions.get(partition).isEmpty()) {
                throw new IllegalArgumentException("Evaluation split must contain at least one " + partition + " session");
            }
        }
        return new EvaluationSplitPlan(splitSessions, table.sha256());
    }

    public Summary summary(Partition partition) {
        List<LocalDate> dates = sessions.get(partition);
        if (dates == null || dates.isEmpty()) throw new IllegalArgumentException("Missing split partition: " + partition);
        return new Summary(dates.size(), dates.getFirst(), dates.getLast());
    }

    private static String required(String value, String name, int rowNumber) {
        if (value == null || value.isBlank() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("Evaluation split row " + rowNumber + ": " + name + " must be non-empty and trimmed");
        }
        return value;
    }
}
