package com.mscient.intrapaper.audit.feed;

import static com.mscient.intrapaper.audit.feed.AuditJson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Validates exact versioned fields and freezes every hash-checked file before any audit uses it. */
final class AuditInputs implements AutoCloseable {
    record Window(String id, String kind, Instant start, Instant end, String reason) {
        boolean contains(Instant t) { return !t.isBefore(start) && t.isBefore(end); }
    }
    record Scope(String id, String source, String product, String channel, String connection,
                 String subscription, String exchange, String token, String instrument, LocalDate date,
                 ZoneId zone, Instant start, Instant end, List<String> types, String heartbeat,
                 List<Window> windows) {
        boolean active(Instant t) { return windows.stream().anyMatch(w -> w.kind.equals("ACTIVE") && w.contains(t)); }
        String stream() { return json(List.of(source, product, channel, connection, subscription, date.toString())); }
    }
    record Policy(Long gap, Long stale, Long skew, Long heartbeatGap, String reset, String sequenceDomain,
                  List<String> problems) { }
    record Part(int order, String path) { }
    record Binding(String scope, List<Part> raw, List<Part> lifecycle, String decoder,
                   String mapping, String quotes) { }

    final Path root;
    final Path temporary;
    final Map<String, Path> frozen = new TreeMap<>();
    final Map<String, String> hashes = new TreeMap<>();
    final List<Scope> scopes = new ArrayList<>();
    final Map<String, Policy> policies = new TreeMap<>();
    final Map<String, Binding> bindings = new TreeMap<>();
    String scheduleId;
    String revision;

    private AuditInputs(Path manifest) throws IOException {
        root = manifest.toAbsolutePath().normalize().getParent();
        temporary = Files.createTempDirectory("feed-audit-inputs-");
    }
    static AuditInputs load(Path manifest) throws IOException {
        AuditInputs in = new AuditInputs(manifest);
        try { in.read(manifest); return in; }
        catch (IOException | RuntimeException e) { in.close(); throw e; }
    }
    private void read(Path manifest) throws IOException {
        String name = manifest.getFileName().toString();
        Path original = safe(name);
        byte[] content = Files.readAllBytes(original);
        Path snapshot = temporary.resolve("manifest.json");
        Files.write(snapshot, content);
        hashes.put(name, hash(content)); frozen.put(name, snapshot);
        Map<String, Object> m = parse(snapshot);
        keys(m, "schema", "schedule", "policy", "bindings"); version(m, "feed-audit-evidence/v1");
        readSchedule(parse(file(m.get("schedule"))));
        readPolicy(parse(file(m.get("policy"))));
        for (Object value : list(m.get("bindings"))) {
            Map<String, Object> b = object(value);
            keys(b, "scope_id", "raw", "lifecycle", "decoder", "decode_reference", "timestamp_roles", "mapping", "quotes");
            String id = str(b, "scope_id");
            if (scopes.stream().noneMatch(s -> s.id.equals(id))) throw new IllegalArgumentException("Unknown evidence scope");
            String decoder = choice(b.get("decoder"), "AUDIT_EVENT_V1", "CANONICAL_QUOTE_V1");
            file(b.get("decode_reference"));
            Map<String, Object> roles = object(b.get("timestamp_roles"));
            keys(roles, "event", "receive", "lifecycle", "monotonic");
            if (!roles.equals(Map.of("event", "DECLARED_EVENT_UTC", "receive", "CAPTURE_RECEIVE_UTC",
                    "lifecycle", "LIFECYCLE_OBSERVED_UTC", "monotonic", "JOURNAL_ORDER_DIAGNOSTIC_ONLY")))
                throw new IllegalArgumentException("Unsupported timestamp roles");
            String mapping = optionalFile(b.get("mapping"));
            String quotes = optionalFile(b.get("quotes"));
            Binding binding = new Binding(id, parts(b.get("raw")), parts(b.get("lifecycle")), decoder, mapping, quotes);
            if (bindings.putIfAbsent(id, binding) != null) throw new IllegalArgumentException("Duplicate evidence scope");
        }
        if (bindings.size() != scopes.size()) throw new IllegalArgumentException("Every schedule scope requires verified raw and lifecycle journals");
        // Multiplexed instruments share the same explicitly declared journal stream, never guessed identity.
        for (Scope a : scopes) for (Scope b : scopes) if (!a.id.equals(b.id) && a.stream().equals(b.stream())) {
            Binding x = bindings.get(a.id), y = bindings.get(b.id);
            if (!x.raw.equals(y.raw) || !x.lifecycle.equals(y.lifecycle) || !x.decoder.equals(y.decoder)
                    || !Objects.equals(x.mapping, y.mapping)) throw new IllegalArgumentException("Inconsistent shared stream evidence");
        }
    }
    private String optionalFile(Object ref) throws IOException {
        if (ref == null) return null;
        Map<String, Object> m = object(ref); file(m); return str(m, "path");
    }
    private List<Part> parts(Object value) throws IOException {
        List<Part> parts = new ArrayList<>(); Set<String> names = new HashSet<>();
        for (Object v : list(value)) {
            Map<String, Object> p = object(v); keys(p, "order", "file");
            String path = str(object(p.get("file")), "path"); file(p.get("file"));
            if (!names.add(path)) throw new IllegalArgumentException("Repeated journal part");
            parts.add(new Part(Math.toIntExact(number(p.get("order"))), path));
        }
        parts.sort(Comparator.comparingInt(Part::order));
        if (parts.isEmpty()) throw new IllegalArgumentException("At least one journal required (header-only is valid)");
        for (int i = 0; i < parts.size(); i++) if (parts.get(i).order != i) throw new IllegalArgumentException("Part orders must be unique contiguous zero-based ordinals");
        return List.copyOf(parts);
    }
    Path file(Object value) throws IOException {
        Map<String, Object> ref = object(value); keys(ref, "path", "sha256");
        String path = str(ref, "path"), expected = digest(ref.get("sha256"));
        if (hashes.containsKey(path)) {
            if (!hashes.get(path).equals(expected)) throw new IllegalArgumentException("Conflicting file hashes: " + path);
            return frozen.get(path);
        }
        byte[] bytes = Files.readAllBytes(safe(path));
        if (!hash(bytes).equals(expected)) throw new IOException("Input hash mismatch: " + path);
        Path snapshot = temporary.resolve("input-" + frozen.size());
        Files.write(snapshot, bytes); hashes.put(path, expected); frozen.put(path, snapshot);
        return snapshot;
    }
    private Path safe(String name) throws IOException {
        if (!name.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
                || Arrays.asList(name.split("/")).contains("..") || Arrays.asList(name.split("/")).contains("."))
            throw new IOException("Expected local relative file path");
        Path path = root.resolve(name).normalize();
        for (Path p = path; p != null; p = p.getParent())
            if (Files.isSymbolicLink(p) || Files.exists(p, LinkOption.NOFOLLOW_LINKS) && Files.readAttributes(p,
                    java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther())
                throw new IOException("Linked/reparse inputs are unsupported");
        if (!path.toRealPath().startsWith(root.toRealPath()) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Input escapes bundle or is not a regular file");
        return path;
    }
    private void readSchedule(Map<String, Object> m) throws IOException {
        keys(m, "schema", "schedule_id", "revision", "provenance", "interval_rule", "scopes");
        version(m, "feed-audit-schedule/v1"); scheduleId = str(m, "schedule_id"); revision = str(m, "revision");
        file(m.get("provenance")); choice(m.get("interval_rule"), "[start,end)");
        Set<String> ids = new HashSet<>(), identities = new HashSet<>();
        for (Object value : list(m.get("scopes"))) {
            Map<String, Object> s = object(value);
            keys(s, "scope_id", "source", "product", "channel", "connection_id", "subscription_id", "exchange",
                    "token", "instrument_id", "session_date", "timezone", "session_start", "session_end", "expected_types", "heartbeat", "windows");
            String id = str(s, "scope_id"); if (!ids.add(id)) throw new IllegalArgumentException("Duplicate scope ID");
            ZoneId zone = ZoneId.of(str(s, "timezone")); LocalDate date = LocalDate.parse(str(s, "session_date"));
            Instant start = timestamp(s.get("session_start"), zone), end = timestamp(s.get("session_end"), zone);
            if (!start.isBefore(end) || !start.atZone(zone).toLocalDate().equals(date)
                    || end.isAfter(date.plusDays(1).atStartOfDay(zone).toInstant()))
                throw new IllegalArgumentException("Session must lie within the explicitly named local date (end may be next midnight)");
            List<String> types = new ArrayList<>();
            for (Object t : list(s.get("expected_types"))) types.add(choice(t, "DATA", "QUOTE", "TRADE"));
            if (new HashSet<>(types).size() != types.size()) throw new IllegalArgumentException("Duplicate event type");
            Collections.sort(types);
            String heartbeat = choice(s.get("heartbeat"), "NONE", "LIFECYCLE");
            List<Window> windows = new ArrayList<>(); Set<String> windowIds = new HashSet<>();
            for (Object wv : list(s.get("windows"))) {
                Map<String, Object> w = object(wv); keys(w, "window_id", "kind", "start", "end", "reason");
                String wid = str(w, "window_id"); if (!windowIds.add(wid)) throw new IllegalArgumentException("Duplicate window ID");
                windows.add(new Window(wid, choice(w.get("kind"), "ACTIVE", "CLOSED", "PAUSE", "EXCLUDED"),
                        timestamp(w.get("start"), zone), timestamp(w.get("end"), zone), str(w, "reason")));
            }
            windows.sort(Comparator.comparing(Window::start)); Instant cursor = start;
            for (Window w : windows) {
                if (!w.start.equals(cursor) || !w.start.isBefore(w.end)) throw new IllegalArgumentException("Windows must partition session without overlaps or implicit exclusions");
                cursor = w.end;
                if (w.kind.equals("ACTIVE") && types.isEmpty() && heartbeat.equals("NONE"))
                    throw new IllegalArgumentException("Active window needs declared event or heartbeat expectation");
            }
            if (!cursor.equals(end)) throw new IllegalArgumentException("Windows must cover entire explicit session");
            Scope scope = new Scope(id, str(s, "source"), str(s, "product"), str(s, "channel"), str(s, "connection_id"),
                    str(s, "subscription_id"), str(s, "exchange"), str(s, "token"), str(s, "instrument_id"), date,
                    zone, start, end, List.copyOf(types), heartbeat, List.copyOf(windows));
            if (!identities.add(scope.stream() + json(List.of(scope.exchange, scope.token)))) throw new IllegalArgumentException("Duplicate effective scope");
            scopes.add(scope);
        }
        if (scopes.isEmpty()) throw new IllegalArgumentException("No schedule scopes");
        scopes.sort(Comparator.comparing(Scope::id));
    }
    static Instant timestamp(Object value, ZoneId zone) {
        OffsetDateTime time = OffsetDateTime.parse(string(value));
        if (!zone.getRules().getValidOffsets(time.toLocalDateTime()).contains(time.getOffset()))
            throw new IllegalArgumentException("Timestamp offset disagrees with declared timezone");
        return time.toInstant();
    }
    private void readPolicy(Map<String, Object> m) throws IOException {
        keys(m, "schema", "policy_id", "revision", "provenance", "rules"); version(m, "feed-audit-policy/v1");
        str(m, "policy_id"); str(m, "revision"); file(m.get("provenance"));
        Map<String, List<Map<String, Object>>> byScope = new TreeMap<>();
        for (Object value : list(m.get("rules"))) {
            Map<String, Object> r = object(value);
            Set<String> allowed = Set.of("scope_id", "max_event_gap", "stale_threshold", "clock_skew", "heartbeat_gap", "sequence_reset", "sequence_domain");
            if (!allowed.containsAll(r.keySet())) throw new IllegalArgumentException("Unknown policy field");
            String id = str(r, "scope_id");
            if (scopes.stream().noneMatch(s -> s.id.equals(id))) throw new IllegalArgumentException("Unknown policy scope");
            byScope.computeIfAbsent(id, ignored -> new ArrayList<>()).add(r);
        }
        for (Scope scope : scopes) {
            List<Map<String, Object>> candidates = byScope.getOrDefault(scope.id, List.of());
            List<String> problems = new ArrayList<>();
            if (candidates.size() != 1) {
                problems.add(candidates.isEmpty() ? "MISSING_POLICY" : "AMBIGUOUS_POLICY");
                policies.put(scope.id, new Policy(null, null, null, null, null, null, List.copyOf(problems))); continue;
            }
            Map<String, Object> r = candidates.getFirst();
            Long gap = threshold(r.get("max_event_gap"), "max_event_gap", problems);
            Long stale = threshold(r.get("stale_threshold"), "stale_threshold", problems);
            Long skew = threshold(r.get("clock_skew"), "clock_skew", problems);
            Long heartbeat = scope.heartbeat.equals("LIFECYCLE") || r.get("heartbeat_gap") != null
                    ? threshold(r.get("heartbeat_gap"), "heartbeat_gap", problems) : null;
            String reset = declared(r.get("sequence_reset"), "sequence_reset", problems, "REJECT", "ALLOW_ZERO_AFTER_RECONNECT", "INCOMPLETE");
            String domain = declared(r.get("sequence_domain"), "sequence_domain", problems, "RAW_STREAM", "NOT_OBSERVED");
            policies.put(scope.id, new Policy(gap, stale, skew, heartbeat, reset, domain, List.copyOf(problems)));
        }
    }
    private static Long threshold(Object value, String field, List<String> problems) {
        if (value == null) { problems.add("MISSING_" + field); return null; }
        Map<String, Object> m = object(value);
        if (!Set.of("value", "unit", "provenance").containsAll(m.keySet())) throw new IllegalArgumentException("Unknown threshold field");
        if (m.get("value") == null || m.get("unit") == null || m.get("provenance") == null) { problems.add("MISSING_" + field); return null; }
        str(m, "provenance"); choice(m.get("unit"), "MILLISECONDS");
        long n = number(m.get("value")); if (n < 0) throw new IllegalArgumentException("Negative threshold");
        return n;
    }
    private static String declared(Object value, String field, List<String> problems, String... choices) {
        if (value == null) { problems.add("MISSING_" + field); return null; }
        Map<String, Object> m = object(value);
        if (!Set.of("value", "unit", "provenance").containsAll(m.keySet())) throw new IllegalArgumentException("Unknown policy declaration field");
        if (m.get("value") == null || m.get("unit") == null || m.get("provenance") == null) { problems.add("MISSING_" + field); return null; }
        str(m, "provenance"); choice(m.get("unit"), "ENUM"); return choice(m.get("value"), choices);
    }
    @Override public void close() throws IOException {
        try (var files = Files.list(temporary)) { for (Path p : files.toList()) Files.delete(p); }
        Files.delete(temporary);
    }
}
