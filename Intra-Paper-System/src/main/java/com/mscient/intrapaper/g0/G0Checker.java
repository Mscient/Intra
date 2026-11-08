package com.mscient.intrapaper.g0;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Standalone offline evidence-pack checker. PASS is never an authorization. */
public final class G0Checker {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final String PACKAGE = "com/mscient/intrapaper/g0/";
    public enum Status { PASS, INCOMPLETE, FAIL }
    public record Result(Status status, byte[] report, String reportSha256, String packSha256) {
        public Result { report = report.clone(); }
        @Override public byte[] report() { return report.clone(); }
    }
    private record Finding(String row, Status status, String code) implements Comparable<Finding> {
        public int compareTo(Finding b) { return (row + "\t" + status + "\t" + code).compareTo(b.row + "\t" + b.status + "\t" + b.code); }
    }
    private record Snapshot(String role, String identity, byte[] bytes) {}
    private final SortedMap<String, Snapshot> snapshots = new TreeMap<>();
    private final SortedSet<Finding> findings = new TreeSet<>();
    private Path root;
    private LocalDate date;
    private LocalDate latestDecision;
    private long totalBytes;
    private Map<String, String> charter;

    private G0Checker() {}
    public static Result evaluate(Path inputs) throws IOException {
        return new G0Checker().assess(inputs);
    }
    private void finding(String row, Status status, String code) { findings.add(new Finding(row, status, code)); }
    private void incomplete(String row, String code) { finding(row, Status.INCOMPLETE, code); }
    private void fail(String row, String code) { finding(row, Status.FAIL, code); }
    private Result assess(Path inputs) throws IOException {
        root = ordinary(inputs, true);
        // Fixed files, no ambient configuration or credential discovery.
        charter = keyFile("charter", G0Schema.CHARTER);
        var owners = table("owners", G0Schema.OWNER_HEADER);
        var sources = table("sources", G0Schema.SOURCE_HEADER);
        var data = table("data", G0Schema.DATA_HEADER);
        date = date(charter.get("assessment_date"), false);
        latestDecision = date;
        checkCharter();
        checkOwners(owners);
        checkSources(sources);
        checkData(data, sources, owners);
        String pack = digestSnapshots(); // Excludes review; includes every referenced source byte.
        String sourceIdentity = sourceIdentity();
        var review = keyFile("review", G0Schema.REVIEW);
        checkReview(review, owners, pack);
        String[] implementation = implementation();
        if ("CALLER_SUPPLIED".equals(charter.get("classification")) && (implementation[1].endsWith(":unknown") || implementation[1].endsWith(":unknown-dirty"))) incomplete("build", "CLEAN_COMMIT_IDENTITY_REQUIRED");
        Status status = findings.stream().anyMatch(f -> f.status == Status.FAIL) ? Status.FAIL
            : findings.stream().anyMatch(f -> f.status == Status.INCOMPLETE) ? Status.INCOMPLETE : Status.PASS;
        StringBuilder out = new StringBuilder("g0-report-v1\n");
        out.append("status\t").append(status).append('\n');
        out.append("assessment_date\t").append(date).append('\n');
        out.append("classification\t").append(charter.getOrDefault("classification", "UNKNOWN")).append('\n');
        out.append("implementation_sha256\t").append(implementation[0]).append('\n');
        out.append("build_identity\t").append(implementation[1]).append('\n');
        out.append("pack_sha256\t").append(pack).append('\n');
        out.append("source_list_identity\t").append(sourceIdentity).append('\n');
        out.append("authorization\tNONE\nlegal_approval\tfalse\ntrading_ready\tfalse\nevidence_authenticated\tfalse\n");
        for (Snapshot s : snapshots.values()) out.append("input\t").append(s.role).append('\t').append(s.identity)
            .append('\t').append(sha256(s.bytes)).append('\n');
        for (Finding f : findings) out.append("finding\t").append(f.row).append('\t').append(f.status).append('\t').append(f.code).append('\n');
        byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
        return new Result(status, bytes, sha256(bytes), pack);
    }
    private void checkCharter() {
        for (String key : G0Schema.CHARTER) if (blank(charter.get(key)) || unknown(charter.get(key))) incomplete("charter." + key, "MISSING_OR_UNKNOWN_VALUE");
        for (var e : Map.of("boundary", "OFFLINE_RESEARCH_PAPER", "order_route", "ABSENT", "account_access", "DISABLED",
            "network_access", "DISABLED", "spending_authorized", "NO", "live_authorized", "NO",
            "limitations_acknowledged", "YES").entrySet()) {
            String actual = charter.get(e.getKey());
            if (!blank(actual) && !unknown(actual) && !e.getValue().equals(actual)) fail("charter." + e.getKey(), "FROZEN_BOUNDARY_VIOLATION");
        }
        if (!blank(charter.get("classification")) && !unknown(charter.get("classification"))) oneOf(charter.get("classification"), "SYNTHETIC", "CALLER_SUPPLIED");
        if (!blank(charter.get("charter_id"))) token(charter.get("charter_id"));
        if (!blank(charter.get("ruleset_id"))) token(charter.get("ruleset_id"));
        tokens(charter.getOrDefault("data_uses", ""), true);
    }
    private void checkOwners(Map<String, Map<String, String>> rows) {
        for (String id : rows.keySet()) if (!G0Schema.DECISIONS.contains(id)) invalid();
        for (String id : G0Schema.DECISIONS) {
            String row = "owner." + id;
            var r = rows.get(id);
            if (r == null) { incomplete(row, "PENDING_OWNER_INPUT"); continue; }
            String state = r.get("status");
            oneOf(state, "PENDING_OWNER_INPUT", "UNRESOLVED", "OWNER_ATTESTED", "CONFIRMED_WITH_EVIDENCE", "NOT_APPLICABLE_WITH_RATIONALE");
            oneOf(r.get("evidence_kind"), "NONE", "CALLER_ATTESTATION");
            if (blank(r.get("reason"))) invalid();
            if (unknown(r.get("value"))) incomplete(row, "OWNER_VALUE_UNKNOWN");
            LocalDate decided = date(r.get("decided"), true), until = date(r.get("valid_until"), true);
            if (decided != null && until != null && until.isBefore(decided)) invalid();
            if (decided != null && decided.isAfter(date)) fail(row, "FUTURE_DECISION");
            if (state.equals("PENDING_OWNER_INPUT") || state.equals("UNRESOLVED")) {
                if (state.equals("PENDING_OWNER_INPUT") && !blank(r.get("value"))) fail(row, "UNAPPROVED_VALUE");
                incomplete(row, state); continue;
            }
            // V1 has no independent trust key. Owner assertions cannot authenticate themselves.
            if (state.equals("CONFIRMED_WITH_EVIDENCE")) { incomplete(row, "INDEPENDENT_AUTHENTICATION_UNCONFIGURED"); continue; }
            if (state.equals("NOT_APPLICABLE_WITH_RATIONALE")) {
                if (!id.matches("A[1-9][0-5]?")) fail(row, "MANDATORY_DECISION_CANNOT_BE_WAIVED");
                if (blank(r.get("value"))) incomplete(row, "MISSING_RATIONALE_DECISION");
            }
            if (blank(r.get("value")) || !r.get("evidence_kind").equals("CALLER_ATTESTATION") || decided == null || until == null)
                incomplete(row, "MISSING_ATTESTATION");
            else if (until.isBefore(date)) incomplete(row, "EXPIRED_DECISION");
            else finding(row, Status.PASS, "CALLER_ATTESTATION_UNVERIFIED");
        }
        for (String id : List.of("risk_capital_value", "annual_spend_ceiling", "live_loss_ceiling")) {
            String value = value(rows, id);
            if (!blank(value) && !unknown(value) && !value.matches("(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?")) fail("owner." + id, "INVALID_LIMIT");
        }
        expect(rows, "risk_capital_is_loss_ceiling", "NO");
        expect(rows, "limits_are_actual", "YES");
        expect(rows, "own_capital_only", "YES");
        expect(rows, "independent_review_completed", "YES");
        expect(rows, "no_order_acknowledgement", "YES");
        expect(rows, "order_activity_scope", "NONE");
        if (!blank(value(rows, "currency")) && !unknown(value(rows, "currency"))) oneOf(value(rows, "currency"), "INR");
        String period = value(rows, "budget_period");
        if (!blank(period) && !unknown(period)) {
            String[] parts = period.split("/", -1);
            if (parts.length != 2) invalid();
            LocalDate start = date(parts[0], false), end = date(parts[1], false);
            if (!end.equals(start.plusYears(1)) || date.isBefore(start) || !date.isBefore(end)) fail("owner.budget_period", "INVALID_ANNUAL_PERIOD");
        }
        for (String id : List.of("operator", "stop_authority", "resume_authority", "human_reviewer", "incident_contact", "escalation_authority"))
            if (!blank(value(rows, id))) token(value(rows, id));
        String capital = value(rows, "risk_capital_value"), loss = value(rows, "live_loss_ceiling");
        if (money(capital) && money(loss) && new java.math.BigDecimal(loss).compareTo(new java.math.BigDecimal(capital)) > 0)
            fail("owner.live_loss_ceiling", "LOSS_EXCEEDS_DECLARED_CAPITAL");
        Set<String> inScope = tokens(value(rows, "instruments_in"), true), excluded = tokens(value(rows, "instruments_out"), true);
        if (!Collections.disjoint(inScope, excluded)) fail("owner.instruments_out", "INCLUDED_AND_EXCLUDED_INSTRUMENT");
    }
    private static boolean money(String v) { return !blank(v) && v.matches("(?:0|[1-9][0-9]{0,11})(?:\\.[0-9]{1,2})?"); }
    private void expect(Map<String, Map<String, String>> rows, String id, String expected) {
        if (!blank(value(rows, id)) && !unknown(value(rows, id)) && !expected.equals(value(rows, id))) fail("owner." + id, "DECLARED_CONSTRAINT_VIOLATION");
    }
    private static String value(Map<String, Map<String, String>> rows, String id) {
        String v = rows.containsKey(id) ? rows.get(id).get("value") : "";
        return unknown(v) ? "" : v;
    }
    private void checkSources(Map<String, Map<String, String>> rows) throws IOException {
        Set<String> covered = new HashSet<>(), references = new HashSet<>();
        for (var r : rows.values()) {
            String id = r.get("id"), row = "source." + id, rule = r.get("rule");
            if (!G0Schema.RULES.contains(rule)) invalid();
            oneOf(r.get("status"), "CURRENT_EVIDENCE", "UNRESOLVED", "CONTRADICTORY");
            oneOf(r.get("authority"), "PRIMARY_ACCEPTED_BY_REVIEWER", "UNRESOLVED");
            oneOf(r.get("applicability"), "APPLICABLE", "NOT_APPLICABLE_WITH_RATIONALE", "UNRESOLVED");
            if (blank(r.get("reason"))) invalid();
            for (String field : List.of("ruleset", "data_source")) if (!blank(r.get(field)) && !unknown(r.get(field))) token(r.get(field));
            for (String field : List.of("title", "publisher", "url", "section", "requirement"))
                if (blank(r.get(field)) || unknown(r.get(field))) incomplete(row, "MISSING_" + field.toUpperCase(Locale.ROOT));
            if (!blank(r.get("url")) && !unknown(r.get("url"))) {
                URI u;
                try { u = URI.create(r.get("url")); } catch (IllegalArgumentException e) { throw new IllegalArgumentException("INVALID_SCHEMA"); }
                if (!"https".equals(u.getScheme()) || u.getHost() == null || u.getUserInfo() != null || u.getFragment() != null || !u.normalize().toASCIIString().equals(r.get("url"))) invalid();
                if (!references.add(rule + "\t" + r.get("url") + "\t" + r.get("section"))) invalid();
            }
            LocalDate pub = date(r.get("published"), true), effective = date(r.get("effective"), true), reviewed = date(r.get("reviewed"), true), until = date(r.get("valid_until"), true);
            if (pub == null || effective == null) incomplete(row, "PUBLICATION_OR_EFFECTIVE_DATE_UNKNOWN");
            if (reviewed == null || until == null) incomplete(row, "FRESHNESS_UNKNOWN");
            if (reviewed != null && until != null && until.isBefore(reviewed)) invalid();
            if ((pub != null && pub.isAfter(date)) || (effective != null && effective.isAfter(date)) || (reviewed != null && reviewed.isAfter(date))) incomplete(row, "FUTURE_SOURCE");
            if (pub != null && reviewed != null && pub.isAfter(reviewed)) fail(row, "REVIEW_PRECEDES_PUBLICATION");
            if (until != null && until.isBefore(date)) incomplete(row, "EXPIRED_SOURCE");
            boolean evidence = attachment(r.get("evidence"), r.get("sha256"), "source-evidence." + id);
            if (!evidence) incomplete(row, "MISSING_SOURCE_BYTES");
            if (blank(r.get("ruleset")) || unknown(r.get("ruleset"))) incomplete(row, "RULESET_UNKNOWN");
            else if (!blank(charter.get("ruleset_id")) && !unknown(charter.get("ruleset_id")) && !r.get("ruleset").equals(charter.get("ruleset_id"))) fail(row, "RULESET_CONFLICT");
            if (r.get("status").equals("CONTRADICTORY")) fail(row, "SOURCE_CONTRADICTS_CHARTER");
            if (!r.get("status").equals("CURRENT_EVIDENCE") || !r.get("authority").equals("PRIMARY_ACCEPTED_BY_REVIEWER") || r.get("applicability").equals("UNRESOLVED")) incomplete(row, "SOURCE_DECISION_UNRESOLVED");
            boolean current = evidence && pub != null && effective != null && reviewed != null && until != null && !pub.isAfter(date) && !effective.isAfter(date) && !reviewed.isAfter(date) && !until.isBefore(date);
            if (current && r.get("status").equals("CURRENT_EVIDENCE") && r.get("authority").equals("PRIMARY_ACCEPTED_BY_REVIEWER") && !r.get("applicability").equals("UNRESOLVED")) {
                covered.add(rule); finding(row, Status.PASS, "REFERENCED_BYTES_AND_CALLER_DECISIONS_ONLY");
            }
        }
        for (String rule : G0Schema.RULES) if (!covered.contains(rule)) incomplete("rule." + rule, "CURRENT_APPLICABILITY_EVIDENCE_REQUIRED");
    }
    private void checkData(Map<String, Map<String, String>> rows, Map<String, Map<String, String>> sources, Map<String, Map<String, String>> owners) {
        Set<String> expected = tokens(charter.getOrDefault("data_uses", ""), true);
        for (String id : expected) if (!rows.containsKey(id)) incomplete("data." + id, "MISSING_USE_DECISION");
        for (var r : rows.values()) {
            String row = "data." + r.get("id");
            if (!blank(charter.get("data_uses")) && !unknown(charter.get("data_uses")) && !expected.contains(r.get("id"))) fail(row, "USE_OUTSIDE_FROZEN_CHARTER");
            oneOf(r.get("status"), "RECORDED", "UNRESOLVED", "DENIED");
            if (blank(r.get("reason"))) invalid();
            if (r.get("status").equals("DENIED")) fail(row, "RIGHTS_DENIED");
            if (!r.get("status").equals("RECORDED")) incomplete(row, "DATA_RIGHTS_UNRESOLVED");
            for (String field : r.keySet()) if (!field.equals("reason") && (blank(r.get(field)) || unknown(r.get(field)))) incomplete(row, "MISSING_" + field.toUpperCase(Locale.ROOT));
            var rights = sources.get(r.get("rights_source"));
            if (rights == null || !rights.get("rule").equals("data_rights") || !rights.get("status").equals("CURRENT_EVIDENCE") || !rights.get("applicability").equals("APPLICABLE")) incomplete(row, "RIGHTS_EVIDENCE_REQUIRED");
            if (rights != null && !blank(r.get("source")) && !unknown(r.get("source")) && !r.get("source").equals(rights.get("data_source"))) fail(row, "RIGHTS_SOURCE_IDENTITY_CONFLICT");
            for (String field : List.of("purpose", "instruments", "storage", "sharing", "publication", "deletion")) {
                Set<String> actual = tokens(r.get(field), true), allowed = tokens(r.get("allowed_" + field), true);
                if (!actual.isEmpty() && !allowed.isEmpty() && !allowed.containsAll(actual)) fail(row, "RIGHTS_SCOPE_" + field.toUpperCase(Locale.ROOT));
            }
            String days = r.get("retention_days"), max = r.get("max_retention_days");
            if (!blank(days) && !unknown(days) && !days.matches("0|[1-9][0-9]{0,8}")) invalid();
            if (!blank(max) && !unknown(max) && !max.matches("0|[1-9][0-9]{0,8}")) invalid();
            if (!blank(days) && !blank(max) && !unknown(days) && !unknown(max) && Long.parseLong(days) > Long.parseLong(max)) fail(row, "RIGHTS_SCOPE_RETENTION");
        }
        for (String field : List.of("source", "purpose", "storage", "sharing", "publication", "deletion")) {
            String ownerId = switch(field) { case "source" -> "data_sources"; case "purpose" -> "data_purposes"; case "storage" -> "data_storage"; case "sharing" -> "data_sharing"; case "publication" -> "data_publication"; default -> "data_deletion"; };
            String owner = value(owners, ownerId);
            Set<String> combined = new TreeSet<>();
            for (var r : rows.values()) combined.addAll(tokens(r.get(field), true));
            boolean complete = rows.keySet().containsAll(expected) && rows.values().stream().noneMatch(r -> blank(r.get(field)) || unknown(r.get(field)));
            if (complete && !blank(owner) && !tokens(owner, false).equals(combined)) fail("owner." + ownerId, "DATA_DECLARATION_CONFLICT");
        }
        Set<String> allowedInstruments = tokens(value(owners, "instruments_in"), true);
        for (var r : rows.values()) if (!allowedInstruments.isEmpty() && !allowedInstruments.containsAll(tokens(r.get("instruments"), true))) fail("data." + r.get("id"), "INSTRUMENT_OUTSIDE_OWNER_SCOPE");
        String retention = value(owners, "data_retention");
        String declaredRetention = rows.values().stream().map(r -> r.get("id") + ":" + r.get("retention_days")).sorted().collect(java.util.stream.Collectors.joining(";"));
        boolean retentionComplete = rows.keySet().containsAll(expected) && rows.values().stream().noneMatch(r -> blank(r.get("retention_days")) || unknown(r.get("retention_days")));
        if (retentionComplete && !blank(retention) && !retention.equals(declaredRetention)) fail("owner.data_retention", "DATA_DECLARATION_CONFLICT");
        if (expected.isEmpty()) incomplete("data", "EXPLICIT_DATA_USE_REQUIRED");
    }
    private void checkReview(Map<String, String> r, Map<String, Map<String, String>> owners, String pack) throws IOException {
        for (String key : G0Schema.REVIEW) if (blank(r.get(key)) || unknown(r.get(key))) incomplete("review." + key, "INDEPENDENT_REVIEW_REQUIRED");
        if (!blank(r.get("decision"))) oneOf(r.get("decision"), "APPROVE_EVIDENCE_PACK", "REJECT", "PENDING");
        if ("REJECT".equals(r.get("decision"))) fail("review", "REVIEWER_REJECTED");
        if (!"APPROVE_EVIDENCE_PACK".equals(r.get("decision"))) incomplete("review", "REVIEW_PENDING");
        if (!blank(r.get("evidence_kind"))) oneOf(r.get("evidence_kind"), "NONE", "CALLER_ATTESTATION");
        if (!blank(r.get("authentication"))) oneOf(r.get("authentication"), "UNVERIFIED");
        if (!"CALLER_ATTESTATION".equals(r.get("evidence_kind"))) incomplete("review", "REVIEW_RECORD_REQUIRED");
        for (String field : List.of("qualified", "independent")) {
            if (!blank(r.get(field))) oneOf(r.get(field), "YES", "NO", "UNKNOWN");
            if ("NO".equals(r.get(field))) fail("review." + field, "REVIEW_CONSTRAINT_VIOLATION");
            else if (!"YES".equals(r.get(field))) incomplete("review." + field, "REVIEW_UNKNOWN");
        }
        if (!blank(r.get("reviewer"))) {
            token(r.get("reviewer"));
            if (r.get("reviewer").equals(value(owners, "operator"))) fail("review", "REVIEWER_IS_OPERATOR");
            if (blank(value(owners, "human_reviewer"))) incomplete("review", "REVIEWER_ASSIGNMENT_UNKNOWN");
            else if (!r.get("reviewer").equals(value(owners, "human_reviewer"))) fail("review", "REVIEWER_IDENTITY_CONFLICT");
        }
        LocalDate reviewed = date(r.get("reviewed"), true), until = date(r.get("valid_until"), true);
        if (reviewed == null || until == null) incomplete("review", "REVIEW_DATE_UNKNOWN");
        else {
            if (until.isBefore(reviewed)) invalid();
            if (reviewed.isAfter(date)) fail("review", "FUTURE_REVIEW");
            if (until.isBefore(date)) incomplete("review", "EXPIRED_REVIEW");
            // The assessment date freezes the complete pack; approval must be on that date.
            if (!reviewed.equals(latestDecision)) incomplete("review", "REVIEW_NOT_AT_FROZEN_ASSESSMENT");
        }
        if (!blank(r.get("pack_sha256"))) { hash(r.get("pack_sha256")); if (!pack.equals(r.get("pack_sha256"))) fail("review", "REVIEW_PACK_BINDING_MISMATCH"); }
        if (!attachment(r.get("evidence"), r.get("sha256"), "review-evidence")) incomplete("review", "MISSING_REVIEW_RECORD_BYTES");
        else {
            byte[] actual = snapshots.get("evidence/" + r.get("evidence")).bytes;
            StringBuilder record = new StringBuilder("g0-review-record-v1\nkey\tvalue\n");
            for (String field : List.of("decision", "reviewer", "qualified", "qualification", "independent", "evidence_kind", "authentication", "reviewed", "valid_until", "pack_sha256")) record.append(field).append('\t').append(r.get(field)).append('\n');
            boolean complete = G0Schema.REVIEW.stream().noneMatch(field -> blank(r.get(field)) || unknown(r.get(field)));
            if (complete && !Arrays.equals(actual, record.toString().getBytes(StandardCharsets.UTF_8))) fail("review", "REVIEW_RECORD_CONTENT_CONFLICT");
        }
        finding("review.authentication", Status.PASS, "CALLER_ATTESTATION_UNVERIFIED_NO_TRUST_KEY");
    }
    private boolean attachment(String name, String expected, String role) throws IOException {
        if (blank(name) && blank(expected)) return false;
        if (blank(name) || blank(expected)) { incomplete(role, "INCOMPLETE_FILE_REFERENCE"); return false; }
        hash(expected);
        if (!name.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}") || name.contains("..")) invalid();
        String key = "evidence/" + name;
        Snapshot snapshot = snapshots.get(key);
        if (snapshot == null) {
            Path p = root.resolve("evidence").resolve(name);
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return false;
            snapshot = read(key, p); snapshots.put(key, snapshot);
        }
        if (snapshot.bytes.length == 0) return false;
        if (!sha256(snapshot.bytes).equals(expected)) { fail(role, "EVIDENCE_HASH_MISMATCH"); return false; }
        return true;
    }
    private Map<String, String> keyFile(String name, List<String> keys) throws IOException {
        var rows = table(name, "key\tvalue");
        Map<String, String> out = new TreeMap<>();
        for (var row : rows.values()) {
            String key = row.get("key");
            if (!keys.contains(key)) invalid();
            out.put(key, row.get("value"));
        }
        return out;
    }
    private Map<String, Map<String, String>> table(String name, String header) throws IOException {
        Path p = root.resolve(name + ".tsv");
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            incomplete("input." + name, "MISSING_FILE"); return Map.of();
        }
        Snapshot snapshot = read(name, p); snapshots.put(name, snapshot);
        String content;
        try { content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(snapshot.bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException("INVALID_SCHEMA"); }
        if (!content.endsWith("\n") || content.indexOf('\r') >= 0 || content.indexOf('\ufeff') >= 0 || content.indexOf('\0') >= 0) invalid();
        String[] lines = content.split("\n", -1), fields = header.split("\t", -1);
        if (lines.length > 4098 || lines.length < 3 || !lines[0].equals("g0-" + name + "-v1") || !lines[1].equals(header)) invalid();
        Map<String, Map<String, String>> out = new TreeMap<>();
        for (int i = 2; i < lines.length - 1; i++) {
            String[] cells = lines[i].split("\t", -1);
            if (cells.length != fields.length) invalid();
            Map<String, String> row = new TreeMap<>();
            for (int j = 0; j < cells.length; j++) {
                if (cells[j].length() > 4096 || !cells[j].equals(cells[j].strip()) || cells[j].codePoints().anyMatch(c -> c < 32 || c == 127)) invalid();
                row.put(fields[j], cells[j]);
            }
            token(cells[0]);
            if (out.put(cells[0], row) != null) invalid();
        }
        return out;
    }
    private static Path ordinary(Path p, boolean directory) throws IOException {
        Path absolute = p.toAbsolutePath().normalize();
        for (Path q = absolute; q != null; q = q.getParent()) if (Files.isSymbolicLink(q) || Files.exists(q, LinkOption.NOFOLLOW_LINKS) && !q.toRealPath().equals(q)) throw new IOException("UNSAFE_PATH");
        if (directory ? !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) : !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IOException("MISSING_OR_UNSAFE_PATH");
        return absolute.toRealPath();
    }
    private Snapshot read(String role, Path path) throws IOException {
        // root ancestry was checked on entry. Only fixed root files or one evidence child
        // are read; validate the remaining path components on each snapshot read.
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root) || Files.isSymbolicLink(absolute) || !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IOException("UNSAFE_PATH");
        Path parent = absolute.getParent();
        if (!parent.equals(root) && (Files.isSymbolicLink(parent) || !parent.toRealPath().equals(parent))) throw new IOException("UNSAFE_PATH");
        Path canonical = absolute.toRealPath();
        if (!canonical.equals(absolute)) throw new IOException("UNSAFE_PATH");
        if (!canonical.startsWith(root) || Files.size(canonical) > MAX_BYTES) throw new IOException("INPUT_LIMIT_OR_PATH");
        byte[] bytes;
        try (var in = Files.newInputStream(canonical)) { bytes = in.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw new IOException("INPUT_LIMIT");
        totalBytes += bytes.length;
        if (totalBytes > 64L * 1024 * 1024 || snapshots.size() >= 256) throw new IOException("PACK_LIMIT");
        String identity = sha256((role + "\n" + canonical.toUri().toASCIIString()).getBytes(StandardCharsets.UTF_8));
        return new Snapshot(role, identity, bytes);
    }
    private String digestSnapshots() {
        StringBuilder b = new StringBuilder();
        for (Snapshot s : snapshots.values()) b.append(s.role).append('\t').append(s.identity).append('\t').append(sha256(s.bytes)).append('\n');
        return sha256(b.toString().getBytes(StandardCharsets.UTF_8));
    }
    private String sourceIdentity() {
        StringBuilder b = new StringBuilder();
        for (var entry : snapshots.entrySet()) if (entry.getKey().equals("sources") || entry.getKey().startsWith("evidence/") && !entry.getKey().equals("review-evidence"))
            b.append(entry.getKey()).append('\t').append(entry.getValue().identity).append('\t').append(sha256(entry.getValue().bytes)).append('\n');
        return sha256(b.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static LocalDate date(String v, boolean nullable) {
        if (blank(v)) { if (nullable) return null; throw new IllegalArgumentException("MISSING_ASSESSMENT_DATE"); }
        if (!v.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) invalid();
        try { LocalDate d = LocalDate.parse(v); if (d.getYear() < 2000 || d.getYear() > 9999) invalid(); return d; }
        catch (java.time.DateTimeException e) { throw new IllegalArgumentException("INVALID_DATE"); }
    }
    private static Set<String> tokens(String s, boolean emptyAllowed) {
        if (blank(s) || unknown(s)) { if (emptyAllowed) return Set.of(); invalid(); }
        Set<String> out = new TreeSet<>();
        for (String t : s.split(";", -1)) { token(t); if (!out.add(t)) invalid(); }
        return out;
    }
    private static void token(String s) { if (s == null || !s.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) invalid(); }
    private static void oneOf(String v, String... choices) { if (!Arrays.asList(choices).contains(v)) invalid(); }
    private static boolean blank(String s) { return s == null || s.isEmpty(); }
    private static boolean unknown(String s) { return s != null && Set.of("UNKNOWN", "UNRESOLVED", "PENDING_OWNER_INPUT", "NOT_SUPPLIED", "TBD", "TBC", "UNDECIDED", "TO_BE_DECIDED", "PLACEHOLDER").contains(s.toUpperCase(Locale.ROOT)); }
    private static void hash(String s) { if (s == null || !s.matches("[0-9a-f]{64}")) invalid(); }
    private static void invalid() { throw new IllegalArgumentException("INVALID_SCHEMA"); }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String[] implementation() throws IOException {
        try {
            Path location = Path.of(G0Checker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            SortedMap<String, byte[]> classes = new TreeMap<>();
            String build;
            if (Files.isDirectory(location)) {
                try (Stream<Path> stream = Files.walk(location.resolve(PACKAGE))) {
                    for (Path p : stream.filter(p -> p.toString().endsWith(".class")).toList()) classes.put(location.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                }
                build = "CLASSES";
            } else {
                try (JarFile jar = new JarFile(location.toFile())) {
                    for (var entry : Collections.list(jar.entries())) if (entry.getName().startsWith(PACKAGE) && entry.getName().endsWith(".class"))
                        try (var in = jar.getInputStream(entry)) { classes.put(entry.getName(), in.readAllBytes()); }
                    String commit = jar.getManifest() == null ? "unknown" : jar.getManifest().getMainAttributes().getValue("Build-Commit");
                    build = "JAR:" + sha256(Files.readAllBytes(location)) + ":" + Objects.toString(commit, "unknown");
                }
            }
            if (classes.isEmpty()) throw new IOException("IMPLEMENTATION_UNAVAILABLE");
            StringBuilder b = new StringBuilder(G0Schema.VERSION + "\n");
            classes.forEach((name, bytes) -> b.append(name).append('\t').append(sha256(bytes)).append('\n'));
            String digest = sha256(b.toString().getBytes(StandardCharsets.UTF_8));
            return new String[] {digest, build.equals("CLASSES") ? "CLASSES:" + digest : build};
        } catch (java.net.URISyntaxException e) { throw new IOException("IMPLEMENTATION_UNAVAILABLE"); }
    }
    public static void publish(Result result, Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize(), parent = ordinary(absolute.getParent(), true);
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IOException("REFUSE_OVERWRITE");
        Path lock = parent.resolve("." + absolute.getFileName() + ".g0-lock");
        Path stage = null;
        boolean owned = false;
        try (FileChannel ignored = FileChannel.open(lock, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            owned = true;
            stage = Files.createTempDirectory(parent, ".g0-stage-");
            try (FileChannel file = FileChannel.open(stage.resolve("report.tsv"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(result.report()); while (buffer.hasRemaining()) file.write(buffer); file.force(true);
            }
            if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) throw new IOException("REFUSE_OVERWRITE");
            Files.move(stage, absolute, StandardCopyOption.ATOMIC_MOVE);
            stage = null;
        } finally {
            if (stage != null) { Files.deleteIfExists(stage.resolve("report.tsv")); Files.deleteIfExists(stage); }
            if (owned) Files.deleteIfExists(lock);
        }
    }
    public static void verify(Path inputs, Path report, String retainedHash) throws IOException {
        hash(retainedHash);
        Path canonical = ordinary(report, false);
        if (Files.size(canonical) > MAX_BYTES) throw new IOException("REPORT_LIMIT");
        byte[] supplied;
        try (var in = Files.newInputStream(canonical)) { supplied = in.readNBytes(MAX_BYTES + 1); }
        if (supplied.length > MAX_BYTES || !sha256(supplied).equals(retainedHash)) throw new IOException("REPORT_HASH_MISMATCH");
        if (!Arrays.equals(evaluate(inputs).report(), supplied)) throw new IOException("REPORT_RECONSTRUCTION_MISMATCH");
    }
    public static int execute(String[] args) {
        try {
            if (args.length == 3 && args[0].equals("run")) {
                Result result = evaluate(Path.of(args[1])); publish(result, Path.of(args[2]));
                System.out.println("G0_EVIDENCE_PACK=" + result.status + " REPORT_SHA256=" + result.reportSha256);
                return result.status == Status.PASS ? 0 : result.status == Status.FAIL ? 1 : 2;
            }
            if (args.length == 4 && args[0].equals("verify")) {
                verify(Path.of(args[1]), Path.of(args[2]), args[3]); System.out.println("G0_VERIFIED_EXACT_RECONSTRUCTION"); return 0;
            }
            System.err.println("USAGE: run INPUT_DIRECTORY NEW_OUTPUT_DIRECTORY | verify INPUT_DIRECTORY REPORT RETAINED_SHA256");
        } catch (IOException | IllegalArgumentException e) { System.err.println("G0_REJECTED_SCHEMA_IDENTITY_OR_IO"); }
        return 3;
    }
    public static void main(String[] args) { System.exit(execute(args)); }
}
