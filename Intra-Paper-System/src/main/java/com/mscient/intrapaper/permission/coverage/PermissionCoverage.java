package com.mscient.intrapaper.permission.coverage;

import com.mscient.intrapaper.data.StrictJsonParser;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import static com.mscient.intrapaper.permission.coverage.CoverageJson.*;
import static com.mscient.intrapaper.permission.coverage.CoverageSchemas.*;

/** Offline comparison of caller-declared scope. No authentication or legal interpretation. */
public final class PermissionCoverage {
    private PermissionCoverage() { }
    enum Status { SCOPE_MATCH, OUT_OF_SCOPE, INCOMPLETE }
    private record Interval(LocalDate start, LocalDate end) {
        boolean contains(LocalDate a, LocalDate b) { return !a.isBefore(start) && !b.isAfter(end); }
    }
    private record Record(Map<String, Object> value, Map<String, Object> claims, Map<String, Object> ref,
                          SortedSet<String> problems, boolean replaced) { }
    private static final class Invalid extends RuntimeException {
        final String code;
        Invalid(String code) { super(code); this.code = code; }
    }
    private static void require(boolean condition, String code) { if (!condition) throw new Invalid(code); }
    private static Interval interval(Object value) {
        Map<String, Object> m = map(value);
        LocalDate a = LocalDate.parse(str(m, "start")), b = LocalDate.parse(str(m, "end"));
        require(a.isBefore(b), "INVALID_DATE_INTERVAL");
        return new Interval(a, b);
    }
    private static Map<String, Object> ref(Map<String, Object> record) {
        // Stable report pseudonyms: no raw provider, party, reviewer, evidence or record identifiers.
        return object("permission_record_id", token("permission-record", str(record, "record_id")), "revision", record.get("revision"));
    }
    private static String key(Map<String, Object> r) { return str(r, "record_id") + ":" + json(r.get("revision")); }
    private static boolean broad(Object selector) { return Boolean.TRUE.equals(map(selector).get("all")); }
    private static boolean selected(Object selector, String value) {
        Map<String, Object> m = map(selector);
        return broad(m) || list(m.get("values")).contains(value);
    }
    private static boolean unknown(Object selector) { return !broad(selector) && list(map(selector).get("values")).isEmpty(); }
    private static void validateSelector(Object value) {
        Map<String, Object> s = map(value);
        require(!broad(s) || list(s.get("values")).isEmpty(), "AMBIGUOUS_BROAD_SCOPE");
    }
    private static void validateScope(Object value) {
        Map<String, Object> s = map(value); validateSelector(s.get("instruments"));
        List<Interval> dates = list(s.get("dates")).stream().map(PermissionCoverage::interval).sorted(Comparator.comparing(Interval::start)).toList();
        require(!Boolean.TRUE.equals(s.get("all_dates")) || dates.isEmpty(), "AMBIGUOUS_BROAD_SCOPE");
        for (int i = 1; i < dates.size(); i++) require(!dates.get(i).start.isBefore(dates.get(i - 1).end), "AMBIGUOUS_DATE_OVERLAP");
    }
    private static boolean overlaps(Object left, Object right) {
        if (unknown(left) || unknown(right)) return false;
        return broad(left) || broad(right) || list(map(left).get("values")).stream().anyMatch(v -> selected(right, (String) v));
    }
    private static boolean dateSelected(Map<String, Object> scope, LocalDate a, LocalDate b) {
        return Boolean.TRUE.equals(scope.get("all_dates")) || list(scope.get("dates")).stream().map(PermissionCoverage::interval).anyMatch(i -> i.contains(a, b));
    }
    private static boolean scopeSelected(Object value, String instrument, LocalDate a, LocalDate b) {
        Map<String, Object> scope = map(value);
        return selected(scope.get("instruments"), instrument) && dateSelected(scope, a, b);
    }
    private static boolean possiblySelected(Object value, String instrument, LocalDate a, LocalDate b) {
        Map<String, Object> s = map(value);
        return (unknown(s.get("instruments")) || selected(s.get("instruments"), instrument))
                && (list(s.get("dates")).isEmpty() || dateSelected(s, a, b));
    }
    private static Status combine(Collection<Status> statuses) {
        return statuses.contains(Status.INCOMPLETE) ? Status.INCOMPLETE
                : statuses.contains(Status.OUT_OF_SCOPE) ? Status.OUT_OF_SCOPE : Status.SCOPE_MATCH;
    }
    private static Status status(Map<String, Object> result) { return Status.valueOf(str(result, "status")); }
    private static List<Object> unique(Collection<?> values) {
        Map<String, Object> sorted = new TreeMap<>();
        for (Object value : values) sorted.put(json(value), value);
        return new ArrayList<>(sorted.values());
    }
    private static Map<String, Object> result(String dim, Status s, String code, List<?> refs, boolean broad) {
        return object("dimension", dim, "status", s.name(), "reason_codes", List.of(code), "permission_records", unique(refs), "explicit_broad_scope", broad);
    }
    private static Map<String, Object> aggregate(String dim, List<Map<String, Object>> items) {
        List<Object> refs = new ArrayList<>(), reasons = new ArrayList<>();
        for (var item : items) { refs.addAll(list(item.get("permission_records"))); reasons.addAll(list(item.get("reason_codes"))); }
        return object("dimension", dim, "status", combine(items.stream().map(PermissionCoverage::status).toList()).name(),
                "reason_codes", unique(reasons), "permission_records", unique(refs),
                "explicit_broad_scope", items.stream().anyMatch(x -> Boolean.TRUE.equals(x.get("explicit_broad_scope"))));
    }
    private static Map<String, Object> term(String dimension, Object term, List<String> requested, Map<String, Object> ref) {
        Map<String, Object> t = map(term);
        Object permit = t.get("permitted"), prohibit = t.get("prohibited");
        boolean all = broad(permit) || broad(prohibit);
        if (requested.stream().anyMatch(v -> selected(prohibit, v))) return result(dimension, Status.OUT_OF_SCOPE, "EXPLICIT_PROHIBITION", List.of(ref), all);
        if (!requested.isEmpty() && unknown(permit)) return result(dimension, Status.INCOMPLETE, "MISSING_PERMITTED_TERM", List.of(ref), all);
        if (!requested.stream().allMatch(v -> selected(permit, v))) return result(dimension, Status.OUT_OF_SCOPE, "TERM_NOT_PERMITTED", List.of(ref), all);
        return result(dimension, Status.SCOPE_MATCH, requested.isEmpty() ? "NO_REQUESTED_ACTION" : "EXPLICIT_COVERAGE", List.of(ref), all);
    }
    private static Map<String, List<String>> requests(Map<String, Object> dataset, Map<String, Object> intent) {
        Map<String, List<String>> r = new TreeMap<>();
        r.put("data_types", list(dataset.get("data_types")).stream().map(Object::toString).sorted().toList());
        r.put("purposes", List.of(str(intent, "purpose"))); r.put("display_modes", List.of(str(intent, "display_mode")));
        for (String dim : List.of("storage_locations", "compute_locations", "backup_locations")) {
            Set<String> values = new TreeSet<>();
            list(dataset.get(dim)).forEach(v -> values.add((String) v)); list(intent.get(dim)).forEach(v -> values.add((String) v));
            r.put(dim, new ArrayList<>(values));
        }
        r.put("deletion", List.of(str(intent, "deletion")));
        Map<String, Object> derived = map(intent.get("derived_outputs"));
        Map<String, String> names = Map.of("derived_retention", "retained", "sharing", "shared", "publication", "published", "distribution", "distributed");
        names.forEach((dim, field) -> r.put(dim, List.of(Boolean.TRUE.equals(derived.get(field)) ? "YES" : "NO")));
        return r;
    }
    private static List<Map<String, Object>> compare(Record r, Map<String, Object> d, Map<String, Object> intent,
            Map<String, List<String>> requests, String instrument, LocalDate a, LocalDate b) {
        if (!r.problems.isEmpty()) return DIMENSIONS.stream().map(dim -> result(dim, Status.INCOMPLETE, r.problems.first(), List.of(r.ref), false)).toList();
        Map<String, Map<String, Object>> results = new TreeMap<>();
        Map<String, Object> c = r.claims, scope = map(c.get("permitted_scope"));
        for (String dim : List.of("provider", "product", "contracting_party")) {
            String requested = str(dim.equals("contracting_party") ? intent : d, dim + "_id");
            boolean match = requested.equals(c.get(dim + "_id"));
            results.put(dim, result(dim, match ? Status.SCOPE_MATCH : Status.OUT_OF_SCOPE, match ? "EXPLICIT_COVERAGE" : "IDENTIFIER_MISMATCH", List.of(r.ref), false));
        }
        boolean prohibited = list(c.get("prohibited_scopes")).stream().anyMatch(s -> scopeSelected(s, instrument, a, b));
        for (String dim : List.of("instruments", "dates")) {
            boolean all = dim.equals("instruments") ? broad(scope.get("instruments")) : Boolean.TRUE.equals(scope.get("all_dates"));
            boolean missing = dim.equals("instruments") ? unknown(scope.get("instruments")) : !all && list(scope.get("dates")).isEmpty();
            boolean match = dim.equals("instruments") ? selected(scope.get("instruments"), instrument) : dateSelected(scope, a, b);
            Status s = prohibited ? Status.OUT_OF_SCOPE : missing ? Status.INCOMPLETE : match ? Status.SCOPE_MATCH : Status.OUT_OF_SCOPE;
            boolean broadExclusion = list(c.get("prohibited_scopes")).stream().map(CoverageJson::map)
                    .anyMatch(x -> scopeSelected(x, instrument, a, b) && (dim.equals("instruments")
                            ? broad(x.get("instruments")) : Boolean.TRUE.equals(x.get("all_dates"))));
            results.put(dim, result(dim, s, prohibited ? "EXPLICIT_SCOPE_PROHIBITION" : missing ? "MISSING_SCOPE" : match ? "EXPLICIT_COVERAGE" : "SCOPE_NOT_PERMITTED", List.of(r.ref), all || broadExclusion));
        }
        Interval use = interval(intent.get("use_period")), effective = interval(c.get("effective_period"));
        // Retention begins when active use ends and includes every copy and derived output.
        LocalDate retentionEnd = use.end.plusDays(((Number) intent.get("retention_days")).intValue());
        boolean effectiveMatch = effective.contains(use.start, retentionEnd);
        results.put("effective_period", result("effective_period", effectiveMatch ? Status.SCOPE_MATCH : Status.OUT_OF_SCOPE,
                effectiveMatch ? "EXPLICIT_COVERAGE" : "USE_PERIOD_NOT_PERMITTED", List.of(r.ref), false));
        Map<String, Object> retention = map(c.get("retention"));
        int days = ((Number) intent.get("retention_days")).intValue();
        Object max = retention.get("permitted_max_days"), prohibit = retention.get("prohibited_from_days");
        Status retentionStatus = prohibit != null && days >= ((Number) prohibit).intValue() ? Status.OUT_OF_SCOPE
                : max == null ? Status.INCOMPLETE : days <= ((Number) max).intValue() ? Status.SCOPE_MATCH : Status.OUT_OF_SCOPE;
        results.put("retention", result("retention", retentionStatus, retentionStatus == Status.SCOPE_MATCH ? "EXPLICIT_COVERAGE"
                : retentionStatus == Status.INCOMPLETE ? "MISSING_RETENTION_TERM" : "RETENTION_NOT_PERMITTED", List.of(r.ref), false));
        for (String dim : TERMS) results.put(dim, term(dim, map(c.get("terms")).get(dim), requests.get(dim), r.ref));
        return new ArrayList<>(results.values());
    }
    private static boolean sameParties(Record r, Map<String, Object> d, Map<String, Object> intent) {
        return Objects.equals(r.claims.get("provider_id"), d.get("provider_id")) && Objects.equals(r.claims.get("product_id"), d.get("product_id"))
                && Objects.equals(r.claims.get("contracting_party_id"), intent.get("contracting_party_id"));
    }
    private static Map<String, Object> cell(List<Record> records, Map<String, Object> d, Map<String, Object> intent,
            Map<String, List<String>> requests, String instrument, LocalDate a, LocalDate b) {
        List<Record> active = records.stream().filter(r -> !r.replaced).toList();
        List<Record> relevant = active.stream().filter(r -> sameParties(r, d, intent) && (possiblySelected(r.claims.get("permitted_scope"), instrument, a, b)
                || list(r.claims.get("prohibited_scopes")).stream().anyMatch(s -> scopeSelected(s, instrument, a, b)))).toList();
        List<Map<String, Object>> dimensions;
        String reason;
        Status cellStatus;
        List<Object> refs;
        if (active.isEmpty()) {
            reason = "MISSING_PERMISSION_RECORD"; cellStatus = Status.INCOMPLETE; refs = List.of();
            dimensions = DIMENSIONS.stream().map(dim -> result(dim, Status.INCOMPLETE, "MISSING_PERMISSION_RECORD", List.of(), false)).toList();
        } else if (relevant.size() > 1) {
            List<List<Map<String, Object>>> comparisons = relevant.stream().map(r -> compare(r, d, intent, requests, instrument, a, b)).toList();
            boolean differing = comparisons.stream().map(ds -> ds.stream().map(PermissionCoverage::status).toList()).distinct().count() > 1;
            reason = differing ? "CONFLICTING_RECORDS" : "AMBIGUOUS_OVERLAP";
            cellStatus = Status.INCOMPLETE; refs = unique(relevant.stream().map(Record::ref).toList());
            String code = reason;
            dimensions = DIMENSIONS.stream().map(dim -> result(dim, Status.INCOMPLETE, code, refs,
                    comparisons.stream().flatMap(Collection::stream).anyMatch(x -> dim.equals(x.get("dimension")) && Boolean.TRUE.equals(x.get("explicit_broad_scope"))))).toList();
        } else {
            List<Record> candidates = relevant.isEmpty() ? active : relevant;
            List<List<Map<String, Object>>> comparisons = candidates.stream().map(r -> compare(r, d, intent, requests, instrument, a, b)).toList();
            // With no applicable rectangle, show each dimension but never assemble a full grant from partial records.
            dimensions = new ArrayList<>();
            for (String dim : DIMENSIONS) {
                var items = comparisons.stream().flatMap(Collection::stream).filter(x -> dim.equals(x.get("dimension"))).toList();
                if (relevant.isEmpty()) {
                    var matches = items.stream().filter(x -> status(x) == Status.SCOPE_MATCH).toList();
                    dimensions.add(aggregate(dim, matches.isEmpty() ? items : matches));
                } else dimensions.add(aggregate(dim, items));
            }
            cellStatus = combine(dimensions.stream().map(PermissionCoverage::status).toList());
            if (relevant.isEmpty() && cellStatus == Status.SCOPE_MATCH) cellStatus = Status.OUT_OF_SCOPE;
            reason = cellStatus == Status.SCOPE_MATCH ? "EXPLICIT_COVERAGE" : relevant.isEmpty() ? "NO_COMPLETE_SCOPE_RECORD" : "UNCOVERED_SCOPE";
            refs = unique(candidates.stream().map(Record::ref).toList());
        }
        return object("instrument_id", token("instrument", instrument), "start", a.toString(), "end", b.toString(), "status", cellStatus.name(),
                "reason_codes", List.of(reason), "permission_records", refs, "dimensions", dimensions);
    }
    private static void fileHash(Path input, String kind, String reference, String path, String declared,
            List<Object> files, Set<String> issues) {
        String observed = null;
        try {
            if (path == null || declared == null) issues.add(kind + "_FILE_UNVERIFIABLE");
            if (path != null) observed = hashFile(resolve(input, path));
            if (observed != null && declared != null && !observed.equals(declared)) issues.add(kind + "_HASH_MISMATCH");
        } catch (IOException | RuntimeException invalid) { issues.add(kind + "_FILE_UNVERIFIABLE"); }
        files.add(object("kind", kind, "reference_id", reference, "declared_sha256", declared, "observed_sha256", observed));
    }
    private static List<Record> records(Map<String, Object> register, Path path, LocalDate asOf, List<Object> files, List<Object> findings, Set<String> issues) {
        List<Map<String, Object>> raw = list(register.get("records")).stream().map(CoverageJson::map).sorted(Comparator.comparing(PermissionCoverage::key)).toList();
        Map<String, Map<String, Object>> byKey = new TreeMap<>();
        for (var r : raw) {
            require(byKey.put(key(r), r) == null, "DUPLICATE_PERMISSION_RECORD");
        }
        List<Record> records = new ArrayList<>();
        for (var r : raw) {
            SortedSet<String> problems = new TreeSet<>(); boolean replaced = false;
            Map<String, Object> c = map(r.get("claims")), e = map(r.get("evidence"));
            validateScope(c.get("permitted_scope"));
            for (Object exclusion : list(c.get("prohibited_scopes"))) {
                validateScope(exclusion);
                Map<String, Object> s = map(exclusion);
                if (unknown(s.get("instruments")) || (!Boolean.TRUE.equals(s.get("all_dates")) && list(s.get("dates")).isEmpty()))
                    problems.add("AMBIGUOUS_PROHIBITED_SCOPE");
            }
            for (Object t : map(c.get("terms")).values()) {
                Object permitted = map(t).get("permitted"), prohibited = map(t).get("prohibited");
                validateSelector(permitted); validateSelector(prohibited);
                if (overlaps(permitted, prohibited)) problems.add("CONFLICTING_PERMITTED_PROHIBITED_TERM");
            }
            Map<String, Object> retention = map(c.get("retention"));
            if (retention.get("permitted_max_days") instanceof Number max && retention.get("prohibited_from_days") instanceof Number min
                    && max.intValue() >= min.intValue()) problems.add("CONFLICTING_RETENTION_TERMS");
            Interval effective = interval(c.get("effective_period"));
            if (!asOf.isBefore(effective.end)) problems.add("EXPIRED_RECORD");
            if (asOf.isBefore(effective.start)) problems.add("NOT_YET_EFFECTIVE");
            if (LocalDate.parse(str(e, "review_date")).isAfter(asOf)) problems.add("FUTURE_REVIEW_DATE");
            for (String field : List.of("reference", "reviewer_id", "reviewer_role")) if (str(e, field).isBlank() || !str(e, field).equals(str(e, field).strip())) problems.add("MISSING_REVIEW_ASSERTION");
            if (!new TreeSet<>(list(e.get("reviewed_fields"))).equals(new TreeSet<>(DIMENSIONS))) problems.add("INCOMPLETE_REVIEWED_CLAIMS");
            if (!hash(bytes(normalized(c))).equals(e.get("claims_sha256"))) problems.add("REVIEWED_CLAIMS_HASH_MISMATCH");
            fileHash(path, "EVIDENCE", token("permission-record", str(r, "record_id")), str(e, "path"), str(e, "sha256"), files, problems);
            if (r.get("revocation_ref") != null || r.get("state").equals("REVOKED")) problems.add("REVOKED_RECORD");
            if (!list(r.get("conflicts_with")).isEmpty()) problems.add("DECLARED_CONFLICT");
            for (String relation : List.of("superseded_by", "amends", "conflicts_with")) {
                for (Object target : list(r.get(relation))) if (!byKey.containsKey(key(map(target))) || key(r).equals(key(map(target)))) problems.add("INVALID_RELATION_REFERENCE");
            }
            if (r.get("state").equals("SUPERSEDED")) {
                List<Object> links = list(r.get("superseded_by"));
                Map<String, Object> replacement = links.size() == 1 ? byKey.get(key(map(links.getFirst()))) : null;
                if (replacement == null || !replacement.get("state").equals("ACTIVE") || list(replacement.get("amends")).stream().noneMatch(x -> key(map(x)).equals(key(r))))
                    problems.add("SUPERSEDED_WITHOUT_REPLACEMENT");
                else if (!Objects.equals(c.get("provider_id"), map(replacement.get("claims")).get("provider_id"))
                        || !Objects.equals(c.get("product_id"), map(replacement.get("claims")).get("product_id"))
                        || !Objects.equals(c.get("contracting_party_id"), map(replacement.get("claims")).get("contracting_party_id")))
                    problems.add("REPLACEMENT_IDENTITY_MISMATCH");
                else {
                    replaced = true;
                    // Historical effective periods are not used after an explicit complete replacement.
                    problems.remove("EXPIRED_RECORD"); problems.remove("NOT_YET_EFFECTIVE");
                }
            } else if (!list(r.get("superseded_by")).isEmpty()) problems.add("INVALID_SUPERSESSION_STATE");
            for (Object oldLink : list(r.get("amends"))) {
                Map<String, Object> old = byKey.get(key(map(oldLink)));
                if (old == null || !old.get("state").equals("SUPERSEDED") || list(old.get("superseded_by")).stream().noneMatch(x -> key(map(x)).equals(key(r)))) problems.add("UNRESOLVED_AMENDMENT");
            }
            for (String problem : problems) findings.add(object("permission_records", List.of(ref(r)), "reason_code", problem));
            issues.addAll(problems);
            records.add(new Record(r, c, ref(r), problems, replaced));
        }
        return records;
    }
    private static Map<String, Object> provenance() throws IOException {
        Map<String, Object> schemas = new TreeMap<>();
        for (String name : List.of("dataset", "intent", "register", "report")) schemas.put(name, schema(name));
        Map<String, Object> classes = new TreeMap<>();
        for (Class<?> type : List.of(CoverageJson.class, CoverageSchemas.class, PermissionCoverage.class, PermissionCoverageArtifacts.class,
                PermissionCoverageCli.class, StrictJsonParser.class)) classHashes(type, classes);
        return object("checker", "intra-data-permission-coverage/v1", "implementation_sha256", hash(bytes(classes)),
                "schema_sha256", hash(bytes(schemas)), "verification", "RECOMPUTE_FROM_LOCAL_INPUTS_NO_AUTHENTICATION");
    }
    private static void classHashes(Class<?> type, Map<String, Object> hashes) throws IOException {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (var stream = type.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("IMPLEMENTATION_IDENTITY_UNAVAILABLE");
            hashes.put(type.getName(), hash(stream.readAllBytes()));
        }
        for (Class<?> nested : type.getDeclaredClasses()) classHashes(nested, hashes);
    }
    /** Input failures become deterministic INCOMPLETE reports; output I/O failures remain errors. */
    public static Map<String, Object> check(Path dataset, Path intent, Path register) throws IOException {
        Map<String, Object> inputs = new TreeMap<>(), hashes = new TreeMap<>();
        SortedSet<String> issues = new TreeSet<>(); List<Object> files = new ArrayList<>(), findings = new ArrayList<>(), cells = new ArrayList<>();
        Map<String, Path> paths = Map.of("dataset", dataset, "intent", intent, "register", register);
        for (String name : List.of("dataset", "intent", "register")) {
            String rawHash = null, canonical = null;
            try {
                byte[] raw = read(paths.get(name)); rawHash = hash(raw);
                Object parsed = parse(raw); validate(name, parsed);
                canonical = hash(bytes(normalized(parsed))); inputs.put(name, parsed);
            } catch (IOException | RuntimeException invalid) { issues.add("INVALID_OR_MISSING_" + name.toUpperCase(Locale.ROOT)); }
            hashes.put(name, object("raw_sha256", rawHash, "canonical_sha256", canonical));
        }
        String datasetId = null, requestId = null, assessment = null;
        if (issues.isEmpty()) {
            try {
                Map<String, Object> d = map(inputs.get("dataset")), i = map(inputs.get("intent"));
                datasetId = token("dataset", str(d, "dataset_id")); requestId = token("request", str(i, "request_id"));
                LocalDate asOf = LocalDate.parse(str(i, "assessment_date")); assessment = asOf.toString();
                Interval scope = interval(d.get("date_scope")); interval(i.get("date_scope"));
                Interval use = interval(i.get("use_period"));
                require(!asOf.isAfter(use.start), "ASSESSMENT_AFTER_USE_START");
                require(d.get("dataset_id").equals(i.get("dataset_id")) && d.get("dataset_version").equals(i.get("dataset_version")), "DATASET_INTENT_IDENTITY_MISMATCH");
                require(json(normalized(d.get("instruments"))).equals(json(normalized(i.get("instruments")))) && json(d.get("date_scope")).equals(json(i.get("date_scope"))), "DATASET_INTENT_SCOPE_MISMATCH");
                require(!i.get("deletion").equals("DELETE_AT_END") || ((Number) i.get("retention_days")).intValue() == 0, "CONFLICTING_RETENTION_DECLARATION");
                Set<String> fileIds = new HashSet<>(), filePaths = new HashSet<>();
                for (Object f : list(d.get("files"))) {
                    Map<String, Object> file = map(f);
                    require(fileIds.add(str(file, "file_id")) && filePaths.add(str(file, "path")), "DUPLICATE_DATA_FILE");
                    fileHash(dataset, "DATA", token("data-file", str(file, "file_id")), str(file, "path"), str(file, "sha256"), files, issues);
                }
                List<Record> records = records(map(inputs.get("register")), register, asOf, files, findings, issues);
                SortedSet<LocalDate> points = new TreeSet<>(List.of(scope.start, scope.end));
                for (Record r : records) {
                    List<Object> scopes = new ArrayList<>(list(r.claims.get("prohibited_scopes"))); scopes.add(r.claims.get("permitted_scope"));
                    for (Object s : scopes) for (Object date : list(map(s).get("dates"))) {
                        Interval range = interval(date);
                        if (range.start.isAfter(scope.start) && range.start.isBefore(scope.end)) points.add(range.start);
                        if (range.end.isAfter(scope.start) && range.end.isBefore(scope.end)) points.add(range.end);
                    }
                }
                long cellCount = (long) list(d.get("instruments")).size() * (points.size() - 1);
                require(cellCount <= 10000 && cellCount * Math.max(1, records.size()) <= 10000, "SCOPE_COMPLEXITY_LIMIT");
                List<LocalDate> boundaries = new ArrayList<>(points); Map<String, List<String>> requested = requests(d, i);
                for (Object instrument : list(d.get("instruments"))) for (int n = 1; n < boundaries.size(); n++)
                    cells.add(cell(records, d, i, requested, (String) instrument, boundaries.get(n - 1), boundaries.get(n)));
            } catch (Invalid invalid) { issues.add(invalid.code); }
            catch (RuntimeException invalid) { issues.add("INVALID_SEMANTIC_INPUT"); }
        }
        List<Object> dimensions = new ArrayList<>();
        for (String dim : DIMENSIONS) {
            List<Map<String, Object>> results = cells.stream().map(CoverageJson::map).flatMap(c -> list(c.get("dimensions")).stream())
                    .map(CoverageJson::map).filter(r -> dim.equals(r.get("dimension"))).toList();
            dimensions.add(results.isEmpty() ? result(dim, Status.INCOMPLETE, issues.isEmpty() ? "MISSING_PERMISSION_RECORD" : issues.first(), List.of(), false) : aggregate(dim, results));
        }
        List<Status> statuses = cells.stream().map(CoverageJson::map).map(PermissionCoverage::status).toList();
        Status overall = !issues.isEmpty() || cells.isEmpty() ? Status.INCOMPLETE : combine(statuses);
        SortedSet<String> reasons = new TreeSet<>(issues);
        for (Object cell : cells) if (status(map(cell)) != Status.SCOPE_MATCH) list(map(cell).get("reason_codes")).forEach(x -> reasons.add((String) x));
        for (Object dim : dimensions) if (status(map(dim)) != Status.SCOPE_MATCH) list(map(dim).get("reason_codes")).forEach(x -> reasons.add((String) x));
        if (reasons.isEmpty()) reasons.add("EXPLICIT_COVERAGE");
        Map<String, Object> report = object("schema_version", "data-permission-report/v1", "policy_version", "declared-coverage/v1",
                "status", overall.name(), "reason_codes", new ArrayList<>(reasons), "permission_approved", false, "production_eligible", false,
                "evidence_authenticated", false, "legal_approval", false, "g2_closed", false,
                "evidence_basis", "CALLER_SUPPLIED_UNAUTHENTICATED_ASSERTIONS", "hash_meaning", "FILE_IDENTITY_ONLY",
                "dataset_id", datasetId, "request_id", requestId, "assessment_date", assessment, "input_hashes", hashes,
                "file_hashes", unique(files), "record_findings", unique(findings), "dimensions", dimensions, "coverage", unique(cells), "provenance", provenance());
        report.put("report_sha256", hash(bytes(report)));
        validate("report", report);
        return report;
    }
}
