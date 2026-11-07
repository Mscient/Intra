package com.mscient.intrapaper.permission.coverage;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static com.mscient.intrapaper.permission.coverage.CoverageJson.*;

/** Executable strict contracts; exported JSON Schemas are generated from these same definitions. */
public final class CoverageSchemas {
    static final List<String> TERMS = List.of("data_types", "purposes", "display_modes", "storage_locations",
            "compute_locations", "backup_locations", "deletion", "derived_retention", "sharing", "publication", "distribution");
    static final List<String> DIMENSIONS = java.util.stream.Stream.concat(
            List.of("provider", "product", "contracting_party", "instruments", "dates", "effective_period", "retention").stream(), TERMS.stream()).sorted().toList();
    static final List<String> DATA_TYPES = List.of("BARS", "TRADES", "QUOTES", "DEPTH", "INSTRUMENT_MASTER", "SETTLEMENT", "MARGIN");
    static final List<String> PURPOSES = List.of("RESEARCH", "BACKTEST", "SIMULATION", "INTERNAL_ANALYSIS", "EDUCATION");
    private CoverageSchemas() { }
    static Map<String, Object> text() { return object("type", "string", "minLength", 1, "maxLength", 1024); }
    static Map<String, Object> pattern(String p) { return object("type", "string", "pattern", p, "maxLength", 256); }
    static Map<String, Object> id() { return pattern("^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$"); }
    static Map<String, Object> sha() { return pattern("^[0-9a-f]{64}$"); }
    static Map<String, Object> date() { return pattern("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"); }
    static Map<String, Object> bool() { return object("type", "boolean"); }
    static Map<String, Object> fixed(Object value) { return object("const", value); }
    static Map<String, Object> integer(int min, int max) { return object("type", "integer", "minimum", min, "maximum", max); }
    static Map<String, Object> enumeration(List<String> values) { return object("type", "string", "enum", values); }
    static Map<String, Object> nullable(Map<String, Object> schema) { return object("anyOf", List.of(schema, object("type", "null"))); }
    static Map<String, Object> array(Map<String, Object> items, int min) {
        return array(items, min, 1000);
    }
    static Map<String, Object> array(Map<String, Object> items, int min, int max) {
        return object("type", "array", "items", items, "minItems", min, "maxItems", max, "uniqueItems", true);
    }
    static Map<String, Object> obj(Object... fields) {
        Map<String, Object> properties = object(fields);
        return object("type", "object", "properties", properties, "required", new ArrayList<>(properties.keySet()), "additionalProperties", false);
    }
    static Map<String, Object> interval() { return obj("start", date(), "end", date(), "boundary", fixed("[start,end)")); }
    static Map<String, Object> selector(Map<String, Object> item) { return obj("all", bool(), "values", array(item, 0)); }
    static Map<String, Object> locations() { return pattern("^(local|remote):[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}$"); }
    static Map<String, Object> termItem(String key) {
        return switch (key) {
            case "data_types" -> enumeration(DATA_TYPES);
            case "purposes" -> enumeration(PURPOSES);
            case "display_modes" -> enumeration(List.of("DISPLAY", "NON_DISPLAY"));
            case "deletion" -> enumeration(List.of("DELETE_AT_END", "DELETE_AFTER_RETENTION"));
            case "storage_locations", "compute_locations", "backup_locations" -> locations();
            default -> enumeration(List.of("YES", "NO"));
        };
    }
    static Map<String, Object> scope() { return obj("instruments", selector(id()), "all_dates", bool(), "dates", array(interval(), 0)); }
    static Map<String, Object> claims() {
        Map<String, Object> terms = new TreeMap<>();
        for (String key : TERMS) terms.put(key, obj("permitted", selector(termItem(key)), "prohibited", selector(termItem(key))));
        return obj("provider_id", id(), "product_id", id(), "contracting_party_id", id(),
                "permitted_scope", scope(), "prohibited_scopes", array(scope(), 0),
                "effective_period", interval(), "terms", obj(terms.entrySet().stream().flatMap(e -> java.util.stream.Stream.of(e.getKey(), e.getValue())).toArray()),
                "retention", obj("permitted_max_days", nullable(integer(0, 36500)), "prohibited_from_days", nullable(integer(0, 36500))));
    }
    static Map<String, Object> link() { return obj("record_id", id(), "revision", integer(1, 1_000_000)); }
    static Map<String, Object> file() { return obj("file_id", id(), "path", pattern("^[A-Za-z0-9_./-]+$"), "sha256", sha()); }
    static Map<String, Object> schema(String name) {
        Map<String, Object> s = switch (name) {
            case "dataset" -> obj("schema_version", fixed("data-permission-dataset/v1"), "dataset_id", id(), "dataset_version", id(),
                    "provider_id", id(), "product_id", id(), "data_types", array(enumeration(DATA_TYPES), 1),
                    "instruments", array(id(), 1), "date_scope", interval(), "files", array(file(), 1),
                    "storage_locations", array(locations(), 1), "compute_locations", array(locations(), 1), "backup_locations", array(locations(), 0));
            case "intent" -> obj("schema_version", fixed("data-permission-intent/v1"), "policy_version", fixed("declared-coverage/v1"),
                    "request_id", id(), "dataset_id", id(), "dataset_version", id(), "contracting_party_id", id(),
                    "assessment_date", date(), "use_period", interval(), "purpose", enumeration(PURPOSES),
                    "display_mode", enumeration(List.of("DISPLAY", "NON_DISPLAY")), "instruments", array(id(), 1), "date_scope", interval(),
                    "storage_locations", array(locations(), 1), "compute_locations", array(locations(), 1), "backup_locations", array(locations(), 0),
                    "retention_days", integer(0, 36500), "deletion", termItem("deletion"),
                    "derived_outputs", obj("retained", bool(), "shared", bool(), "published", bool(), "distributed", bool()));
            case "register" -> obj("schema_version", fixed("data-permission-register/v1"), "records", array(obj(
                    "record_id", id(), "revision", integer(1, 1_000_000), "state", enumeration(List.of("ACTIVE", "REVOKED", "SUPERSEDED")),
                    "claims", claims(), "evidence", obj("reference", text(), "path", nullable(pattern("^[A-Za-z0-9_./-]+$")),
                            "sha256", nullable(sha()), "reviewer_id", text(), "reviewer_role", text(), "review_date", date(),
                            "reviewed_fields", array(enumeration(DIMENSIONS), 1), "claims_sha256", sha()),
                    "revocation_ref", nullable(text()), "superseded_by", array(link(), 0), "amends", array(link(), 0), "conflicts_with", array(link(), 0)), 0));
            case "report" -> report();
            default -> throw new IllegalArgumentException("UNKNOWN_SCHEMA");
        };
        s.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        s.put("$id", "urn:intra:data-permission:" + name + ":v1");
        return s;
    }
    static Map<String, Object> ref() { return obj("permission_record_id", sha(), "revision", integer(1, 1_000_000)); }
    static Map<String, Object> status() { return enumeration(List.of("SCOPE_MATCH", "OUT_OF_SCOPE", "INCOMPLETE")); }
    static Map<String, Object> result() { return obj("dimension", enumeration(DIMENSIONS), "status", status(),
            "reason_codes", array(id(), 1), "permission_records", array(ref(), 0), "explicit_broad_scope", bool()); }
    static Map<String, Object> report() {
        Map<String, Object> hashes = obj("raw_sha256", nullable(sha()), "canonical_sha256", nullable(sha()));
        Map<String, Object> cells = array(obj("instrument_id", sha(), "start", date(), "end", date(), "status", status(),
                "reason_codes", array(id(), 1), "permission_records", array(ref(), 0), "dimensions", array(result(), 1)), 0);
        cells.put("maxItems", 10000);
        return obj("schema_version", fixed("data-permission-report/v1"), "policy_version", fixed("declared-coverage/v1"),
                "status", status(), "reason_codes", array(id(), 1), "permission_approved", fixed(false), "production_eligible", fixed(false),
                "evidence_authenticated", fixed(false), "legal_approval", fixed(false), "g2_closed", fixed(false),
                "evidence_basis", fixed("CALLER_SUPPLIED_UNAUTHENTICATED_ASSERTIONS"), "hash_meaning", fixed("FILE_IDENTITY_ONLY"),
                "dataset_id", nullable(sha()), "request_id", nullable(sha()), "assessment_date", nullable(date()),
                "input_hashes", obj("dataset", hashes, "intent", hashes, "register", hashes),
                "file_hashes", array(obj("kind", enumeration(List.of("DATA", "EVIDENCE")), "reference_id", sha(),
                        "declared_sha256", nullable(sha()), "observed_sha256", nullable(sha())), 0, 2000),
                "record_findings", array(obj("permission_records", array(ref(), 0), "reason_code", id()), 0, 20000),
                "dimensions", array(result(), 1), "coverage", cells,
                "provenance", obj("checker", fixed("intra-data-permission-coverage/v1"), "implementation_sha256", sha(),
                        "schema_sha256", sha(), "verification", fixed("RECOMPUTE_FROM_LOCAL_INPUTS_NO_AUTHENTICATION")),
                "report_sha256", sha());
    }
    static void validate(String name, Object value) throws IOException {
        if (!valid(schema(name), value)) throw new IOException("SCHEMA_INVALID_" + name.toUpperCase(Locale.ROOT));
    }
    static boolean valid(Map<String, Object> s, Object v) {
        if (s.containsKey("anyOf")) return list(s.get("anyOf")).stream().anyMatch(x -> valid(map(x), v));
        if (s.containsKey("const") && !json(s.get("const")).equals(json(v))) return false;
        if (s.containsKey("enum") && !list(s.get("enum")).contains(v)) return false;
        String type = (String) s.get("type");
        if (type == null) return true;
        switch (type) {
            case "null": return v == null;
            case "boolean": return v instanceof Boolean;
            case "string":
                if (!(v instanceof String text)) return false;
                return (!s.containsKey("pattern") || text.matches((String) s.get("pattern")))
                        && text.length() >= ((Number) s.getOrDefault("minLength", 0)).intValue()
                        && text.length() <= ((Number) s.getOrDefault("maxLength", Integer.MAX_VALUE)).intValue();
            case "integer":
                if (!(v instanceof Number n)) return false;
                try {
                    long number = new BigDecimal(n.toString()).longValueExact();
                    return number >= ((Number) s.get("minimum")).longValue() && number <= ((Number) s.get("maximum")).longValue();
                } catch (ArithmeticException invalid) { return false; }
            case "array":
                if (!(v instanceof List<?> items)) return false;
                if (items.size() < ((Number) s.get("minItems")).intValue() || items.size() > ((Number) s.get("maxItems")).intValue()) return false;
                if (items.stream().map(x -> json(normalized(x))).distinct().count() != items.size()) return false;
                return items.stream().allMatch(x -> valid(map(s.get("items")), x));
            case "object":
                if (!(v instanceof Map<?, ?> fields)) return false;
                Map<String, Object> properties = map(s.get("properties"));
                if (!fields.keySet().equals(properties.keySet())) return false;
                return properties.entrySet().stream().allMatch(e -> valid(map(e.getValue()), fields.get(e.getKey())));
            default: throw new IllegalArgumentException("UNSUPPORTED_SCHEMA_KEYWORD");
        }
    }
    /** Export for maintainers; focused tests require these files to equal the runtime contracts. */
    public static void main(String[] args) throws IOException {
        Path output = Path.of(args[0]); Files.createDirectories(output);
        for (String name : List.of("dataset", "intent", "register", "report")) Files.write(output.resolve(name + ".schema.json"), bytes(schema(name)));
    }
}
