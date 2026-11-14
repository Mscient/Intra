package com.mscient.intrapaper.audit.reconciliation;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.provenance.RunConfig;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** The audit's byte, decimal and normalized-record contracts. No inferred monetary inputs. */
final class LedgerSupport {
    enum Kind { ORDER, FILL, FEE, POSITION, CASH, SETTLEMENT, INPUT }
    enum Status { PASS, INCOMPLETE, FAIL }
    enum Severity { INFO, INCOMPLETE, FAIL }
    static final List<Kind> DOMAINS = List.of(Kind.ORDER, Kind.FILL, Kind.FEE, Kind.POSITION, Kind.CASH, Kind.SETTLEMENT);
    static final List<String> HEADERS = List.of("record_id", "record_type", "entity_id", "action", "replaces_record_id",
            "recorded_at_utc", "event_sequence", "event_time_utc", "replay_record_id", "instrument_id", "order_id",
            "related_type", "related_id", "side", "status", "quantity_units", "filled_units", "price_inr", "amount_inr",
            "component", "checkpoint", "source_reference");
    static final List<String> COVERAGE_HEADERS = List.of("record_type", "start_utc", "end_utc", "completeness", "source_reference");
    static final Map<Kind, List<String>> FIELDS = Map.of(
            Kind.ORDER, List.of("event_time_utc", "instrument_id", "side", "status", "quantity_units", "filled_units"),
            Kind.FILL, List.of("event_time_utc", "instrument_id", "side", "order_id", "quantity_units", "price_inr"),
            Kind.FEE, List.of("event_time_utc", "instrument_id", "related_type", "related_id", "component", "amount_inr"),
            Kind.POSITION, List.of("event_time_utc", "instrument_id", "checkpoint", "quantity_units"),
            Kind.CASH, List.of("event_time_utc", "instrument_id", "related_type", "related_id", "component", "amount_inr"),
            Kind.SETTLEMENT, List.of("event_time_utc", "instrument_id", "component", "quantity_units", "price_inr", "amount_inr"));
    static final Set<String> NUMBERS = Set.of("quantity_units", "filled_units", "price_inr", "amount_inr");

    record Expected(Kind kind, String id, Map<String, String> fields) {
        Expected { fields = Collections.unmodifiableMap(new TreeMap<>(fields)); }
        String get(String name) { return fields.getOrDefault(name, ""); }
    }
    record Event(int line, Kind kind, String id, String entity, String action, String replaces,
                 Instant recorded, long sequence, Map<String, String> fields) {
        Event { fields = Collections.unmodifiableMap(new TreeMap<>(fields)); }
        String get(String name) { return fields.getOrDefault(name, ""); }
        String key() { return kind + ":" + entity; }
    }
    record Finding(Kind kind, Severity severity, String code, int line, String recordId, String replayId,
                   String field, String expected, String actual, String detail) { }
    record Comparison(Kind kind, String replayId, String recordId, int line, String method, String field,
                      String expected, String actual, String difference, Status status) { }
    record Provenance(String role, String reference, String sha256, String declared, String bytes,
                      String status, String sourceId, String sourceReference) { }

    static final class Snapshots {
        final Map<Path, byte[]> bytes = new LinkedHashMap<>();
        final Map<String, Provenance> provenance = new TreeMap<>();
        byte[] read(Path path, String role, String reference, String declared, String source, String attribution) throws IOException {
            Path absolute = path.toAbsolutePath().normalize();
            byte[] content = bytes.get(absolute);
            if (content == null) {
                try { content = readFile(absolute); bytes.put(absolute, content); }
                catch (IOException unreadable) {
                    provenance.put(role, new Provenance(role, reference, "", declared, "", "INCOMPLETE", source, attribution));
                    throw unreadable;
                }
            }
            String hash = hash(content);
            provenance.put(role, new Provenance(role, reference, hash, declared, Integer.toString(content.length),
                    declared.isEmpty() || hash.equals(declared) ? "PASS" : "FAIL", source, attribution));
            return content;
        }
        void hashOnly(String role, String hash) {
            provenance.put(role, new Provenance(role, "", hash, "", "", "CALCULATED_HASH", "", ""));
        }
        void unchanged() throws IOException {
            for (var entry : bytes.entrySet())
                if (!Arrays.equals(entry.getValue(), readFile(entry.getKey())))
                    throw new IOException("An input changed during reconciliation; no report was published");
        }
    }

    static byte[] readFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path))
            throw new IOException("Required input is absent or is not a regular non-symlink file");
        if (Files.size(path) > 16 * 1024 * 1024) throw new IOException("Audit input exceeds the 16 MiB limit");
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > 16 * 1024 * 1024) throw new IOException("Audit input exceeds the 16 MiB limit");
        return bytes;
    }
    static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    static List<Map<String, String>> csv(byte[] bytes, List<String> headers) throws IOException {
        String text = decode(bytes);
        if (text.indexOf('\r') >= 0 && text.replace("\r\n", "").indexOf('\r') >= 0)
            throw new IllegalArgumentException("CSV requires LF or CRLF line endings");
        List<String> lines = text.lines().toList();
        if (lines.isEmpty() || !lines.getFirst().equals(String.join(",", headers)))
            throw new IllegalArgumentException("CSV header does not match the documented ordered schema");
        var rows = new ArrayList<Map<String, String>>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> cells = CsvTable.parseLine(lines.get(i));
            if (cells.size() != headers.size()) throw new IllegalArgumentException("Wrong CSV field count on row " + (i + 1));
            var row = new TreeMap<String, String>();
            for (int j = 0; j < cells.size(); j++) {
                text(cells.get(j)); row.put(headers.get(j), cells.get(j));
            }
            rows.add(Collections.unmodifiableMap(row));
        }
        return List.copyOf(rows);
    }
    static Map<String, String> properties(byte[] bytes) throws IOException {
        var result = new TreeMap<String, String>();
        for (String line : decode(bytes).lines().toList()) {
            int split = line.indexOf('=');
            if (split < 1) throw new IllegalArgumentException("Manifest requires one key=value per line without comments or blank lines");
            String key = line.substring(0, split), value = line.substring(split + 1);
            text(key); text(value);
            if (result.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate manifest key: " + key);
        }
        return result;
    }
    static Path relativeFile(Path manifest, String name) throws IOException {
        if (!name.matches("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")
                || Arrays.stream(name.split("/")).anyMatch(p -> p.equals(".") || p.equals("..")))
            throw new IllegalArgumentException("External file paths must be portable relative paths without traversal");
        Path parent = manifest.toAbsolutePath().normalize().getParent();
        if (!parent.toRealPath().equals(parent)) throw new IOException("External bundle path contains an alias or symlink");
        Path candidate = parent;
        for (String segment : name.split("/")) {
            candidate = candidate.resolve(segment);
            if (Files.isSymbolicLink(candidate)) throw new IOException("External bundle paths cannot contain symlinks");
        }
        if (candidate.equals(manifest.toAbsolutePath().normalize())) throw new IOException("External manifest cannot reference itself");
        return candidate;
    }
    static void text(String value) {
        if (!value.equals(value.strip()) || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Cells must be trimmed and contain no control characters");
    }
    static String required(Map<String, String> row, String field) {
        String value = row.getOrDefault(field, "");
        if (value.isEmpty()) throw new IllegalArgumentException("Missing required " + field);
        return value;
    }
    static void id(String value) {
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")) throw new IllegalArgumentException("Invalid normalized identifier");
    }
    static Instant time(String value) {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z"))
            throw new IllegalArgumentException("Timestamps require ISO UTC seconds with a literal Z");
        return Instant.parse(value);
    }
    static BigDecimal decimal(String value) {
        if (value.isEmpty()) return null;
        if (value.length() > 64 || !value.matches("-?[0-9]+(?:\\.[0-9]{1,12})?"))
            throw new IllegalArgumentException("Amounts require plain decimals with at most 12 fractional digits");
        BigDecimal result = new BigDecimal(value);
        if (result.abs().compareTo(BigDecimal.TEN.pow(30)) >= 0) throw new IllegalArgumentException("Amount magnitude exceeds the audit contract");
        return result;
    }
    static String number(BigDecimal value) {
        return value == null ? "" : value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }
    static BigDecimal amount(String value) { return value.isEmpty() ? null : new BigDecimal(value); }
    static Kind kind(String value) {
        Kind kind = Kind.valueOf(value);
        if (kind == Kind.INPUT) throw new IllegalArgumentException("INPUT is not an external record type");
        return kind;
    }
    static Map<String, String> fields(Object... pairs) {
        var map = new TreeMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            map.put(pairs[i].toString(), value instanceof BigDecimal n ? number(n) : value == null ? "" : value.toString());
        }
        return map;
    }
    static StringBuilder header(String... columns) { return new StringBuilder(String.join(",", columns)).append('\n'); }
    static void row(StringBuilder out, Object... cells) {
        var values = new ArrayList<String>();
        for (Object cell : cells) values.add(CsvTable.escape(cell == null ? "" : cell.toString()));
        out.append(String.join(",", values)).append('\n');
    }
    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static String hash(byte[] bytes) { return RunConfig.sha256(bytes); }
    static Status status(Collection<Finding> findings) {
        if (findings.stream().anyMatch(f -> f.severity == Severity.FAIL)) return Status.FAIL;
        if (findings.stream().anyMatch(f -> f.severity == Severity.INCOMPLETE)) return Status.INCOMPLETE;
        return Status.PASS;
    }
    static void finding(List<Finding> findings, Kind kind, Severity severity, String code, Event event,
                        String replay, String field, String expected, String actual, String detail) {
        findings.add(new Finding(kind, severity, code, event == null ? 0 : event.line,
                event == null ? "" : event.id, replay, field, expected, actual, detail));
    }
    private LedgerSupport() { }
}
