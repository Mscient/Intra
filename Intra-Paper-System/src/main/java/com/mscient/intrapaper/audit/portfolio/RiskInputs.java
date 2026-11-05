package com.mscient.intrapaper.audit.portfolio;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.provenance.RunConfig;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Strict caller-owned, inclusive session-date schedules. No inferred limits or margin values. */
final class RiskInputs {
    static final List<String> FILES = List.of("limits.csv", "margin-proxies.csv", "sources.csv", "budgets.csv");
    static final Map<String, String> HEADERS = Map.of(
            "limits.csv", "instrument_id,effective_from,effective_to,max_abs_units,max_gross_notional_inr,source_id",
            "margin-proxies.csv", "instrument_id,effective_from,effective_to,long_proxy_inr_per_unit,short_proxy_inr_per_unit,source_id",
            "sources.csv", "source_id,effective_from,effective_to,observed_date,reference,reference_sha256",
            "budgets.csv", "effective_from,effective_to,max_gross_notional_inr,max_session_loss_inr,max_caller_supplied_margin_proxy_inr,max_mark_age_seconds,max_source_age_days,source_id");
    final Map<String, byte[]> files = new TreeMap<>();
    private final Map<String, List<Map<String, String>>> rows = new TreeMap<>();

    static RiskInputs load(Path folder) throws IOException {
        RiskInputs result = new RiskInputs();
        for (String name : FILES) {
            Path path = folder.resolve(name);
            requireFile(path);
            byte[] bytes = Files.readAllBytes(path);
            // Decode strictly, including rejecting invalid UTF-8 rather than replacing bytes.
            String content = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            List<String> lines = content.lines().toList();
            if (lines.isEmpty() || !lines.getFirst().equals(HEADERS.get(name)))
                throw new IllegalArgumentException("Exact header required in " + name + ": " + HEADERS.get(name));
            List<String> headers = List.of(HEADERS.get(name).split(","));
            List<Map<String, String>> entries = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                List<String> cells = CsvTable.parseLine(lines.get(i));
                if (cells.size() != headers.size()) throw new IllegalArgumentException("Invalid row " + (i + 1) + " in " + name);
                Map<String, String> row = new TreeMap<>();
                for (int j = 0; j < cells.size(); j++) {
                    String value = cells.get(j);
                    if (value.isBlank() || !value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl))
                        throw new IllegalArgumentException("Empty, untrimmed or control-containing cell in " + name);
                    row.put(headers.get(j), value);
                }
                LocalDate from = date(row, "effective_from"), to = date(row, "effective_to");
                if (to.isBefore(from)) throw new IllegalArgumentException("Reversed effective dates in " + name);
                for (String key : List.of("instrument_id", "source_id")) {
                    if (row.containsKey(key) && !row.get(key).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))
                        throw new IllegalArgumentException("Invalid " + key);
                }
                for (String key : headers) {
                    if (key.startsWith("max_") || key.endsWith("_per_unit")) {
                        decimal(row.get(key));
                        if (key.equals("max_abs_units") || key.equals("max_mark_age_seconds") || key.equals("max_source_age_days"))
                            integer(row.get(key));
                    }
                }
                if (name.equals("sources.csv")) {
                    if (date(row, "observed_date").isAfter(from))
                        throw new IllegalArgumentException("Source observed_date must not follow effective_from");
                    if (!row.get("reference_sha256").matches("[0-9a-f]{64}"))
                        throw new IllegalArgumentException("Invalid caller-declared reference_sha256");
                }
                String key = key(name, row);
                for (Map<String, String> previous : entries) {
                    if (key.equals(key(name, previous)) && !from.isAfter(date(previous, "effective_to"))
                            && !to.isBefore(date(previous, "effective_from")))
                        throw new IllegalArgumentException("Overlapping/duplicate effective intervals in " + name + " for " + key);
                }
                entries.add(Map.copyOf(row));
            }
            if (entries.isEmpty()) throw new IllegalArgumentException("Empty risk schedule: " + name);
            result.files.put(name, bytes);
            result.rows.put(name, List.copyOf(entries));
        }
        Set<String> sourceIds = new HashSet<>();
        result.rows.get("sources.csv").forEach(r -> sourceIds.add(r.get("source_id")));
        for (String name : List.of("limits.csv", "margin-proxies.csv", "budgets.csv"))
            for (var row : result.rows.get(name))
                if (!sourceIds.contains(row.get("source_id"))) throw new IllegalArgumentException("Unknown source_id in " + name);
        return result;
    }

    Map<String, String> at(String file, String id, LocalDate date) {
        return rows.get(file).stream().filter(r -> key(file, r).equals(id)
                && !date.isBefore(date(r, "effective_from")) && !date.isAfter(date(r, "effective_to")))
                .findFirst().orElse(null);
    }

    String coverage(String file, String id, LocalDate day, Map<String, String> budget) {
        Map<String, String> row = at(file, id, day);
        if (row == null) return rows.get(file).stream().anyMatch(r -> key(file, r).equals(id)
                && date(r, "effective_to").isBefore(day)) ? "EXPIRED_" + file : "MISSING_" + file;
        Map<String, String> source = at("sources.csv", row.get("source_id"), day);
        if (source == null) return "MISSING_OR_EXPIRED_SOURCE";
        if (budget == null) return "MISSING_FRESHNESS_BUDGET";
        if (ChronoUnit.DAYS.between(date(source, "observed_date"), day) > integer(budget.get("max_source_age_days")))
            return "STALE_SOURCE";
        return "COMPLETE";
    }

    void requireUniverse(Set<String> instruments) {
        for (String file : List.of("limits.csv", "margin-proxies.csv"))
            for (var row : rows.get(file))
                if (!instruments.contains(row.get("instrument_id")))
                    throw new IllegalArgumentException("Unknown risk instrument: " + row.get("instrument_id"));
    }

    void unchanged(Path root) throws IOException {
        for (String file : FILES) {
            requireFile(root.resolve(file));
            if (!Arrays.equals(files.get(file), Files.readAllBytes(root.resolve(file))))
                throw new IOException("Risk input changed while auditing: " + file);
        }
    }

    private static String key(String file, Map<String, String> row) {
        return file.equals("budgets.csv") ? "PORTFOLIO" : row.get(file.equals("sources.csv") ? "source_id" : "instrument_id");
    }
    static LocalDate date(Map<String, String> row, String key) {
        String value = row.get(key);
        LocalDate date = LocalDate.parse(value);
        if (!date.toString().equals(value)) throw new IllegalArgumentException("Noncanonical date: " + value);
        return date;
    }
    static BigDecimal decimal(String value) {
        if (value == null || !value.matches("(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{1,12})?"))
            throw new IllegalArgumentException("Expected nonnegative plain decimal with at most 18 integer/12 fractional digits: " + value);
        return new BigDecimal(value);
    }
    static long integer(String value) {
        if (!value.matches("(?:0|[1-9][0-9]*)")) throw new IllegalArgumentException("Expected nonnegative integer: " + value);
        return Long.parseLong(value);
    }
    static void requireFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            throw new IOException("Required regular file missing: " + path);
    }
    static String hash(byte[] bytes) { return RunConfig.sha256(bytes); }
}
