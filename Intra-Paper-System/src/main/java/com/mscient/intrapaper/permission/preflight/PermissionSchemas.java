package com.mscient.intrapaper.permission.preflight;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Runtime validation and published JSON Schemas have one field-definition source. */
public final class PermissionSchemas {
    private PermissionSchemas() { }
    private static final String RESERVED = reservedPattern();
    private static final String ID = "(?!(?:" + RESERVED + ")$)[A-Za-z0-9][A-Za-z0-9._:-]{0,127}";
    private static final String HASH = "[0-9a-f]{64}";
    private static final String DATE = "[0-9]{4}-[0-9]{2}-[0-9]{2}";
    private static final String REF = "(?!(?:" + RESERVED + ")$)[!-~](?:[ -~]{0,510}[!-~])?";
    private static final List<String> OUTPUTS = List.of("NONE", "AGGREGATE_METRICS", "CHARTS", "SIGNALS", "MODEL_PARAMETERS", "TRANSFORMED_DATA");
    private static final List<String> MATERIAL = List.of("NONE", "RAW_DATA", "AGGREGATE_METRICS", "CHARTS", "SIGNALS", "MODEL_PARAMETERS", "TRANSFORMED_DATA");
    private static final List<String> SHARING = List.of("NONE", "INTERNAL", "EXTERNAL", "PUBLIC");
    private record Field(String type, String pattern, List<String> values) {
        Map<String, Object> json() {
            Map<String, Object> result = new TreeMap<>();
            result.put("type", type);
            if (type.equals("integer")) { result.put("minimum", 1); result.put("maximum", 365000); }
            else if (type.equals("array")) {
                Map<String, Object> item = new TreeMap<>(); item.put("type", "string");
                if (pattern != null) item.put("pattern", "^" + pattern + "$");
                if (!values.isEmpty()) item.put("enum", values);
                result.put("items", item); result.put("minItems", 1); result.put("maxItems", 256); result.put("uniqueItems", true);
            } else {
                if (pattern != null) result.put("pattern", "^" + pattern + "$");
                if (!values.isEmpty()) result.put("enum", values);
                if (DATE.equals(pattern)) result.put("format", "date");
            }
            return result;
        }
        boolean valid(Object value) {
            if (type.equals("integer")) return value instanceof BigDecimal n
                    && n.compareTo(BigDecimal.ONE) >= 0 && n.compareTo(BigDecimal.valueOf(365000)) <= 0
                    && n.stripTrailingZeros().scale() <= 0;
            if (type.equals("array")) {
                if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 256
                        || list.stream().distinct().count() != list.size()) return false;
                return list.stream().allMatch(this::stringValid);
            }
            return stringValid(value);
        }
        private boolean stringValid(Object value) {
            if (!(value instanceof String s) || s.isEmpty() || !s.equals(s.strip())) return false;
            if (pattern != null && !s.matches(pattern)) return false;
            if (!values.isEmpty() && !values.contains(s)) return false;
            if (DATE.equals(pattern)) {
                try { return LocalDate.parse(s).getYear() >= 1; }
                catch (DateTimeParseException invalid) { return false; }
            }
            return true;
        }
    }
    private static Field string(String regex) { return new Field("string", regex, List.of()); }
    private static Field choice(String... choices) { return new Field("string", null, List.of(choices)); }
    private static Field set(List<String> choices) { return new Field("array", choices.isEmpty() ? ID : null, choices); }
    private static Field recipients() { return new Field("array", "(?:NONE|" + ID + ")", List.of()); }
    private static String reservedPattern() {
        // JSON-Schema-compatible case-insensitive spellings, without Java-only regex flags.
        return String.join("|", List.of("ALL", "ANY", "UNKNOWN", "UNSPECIFIED", "AMBIGUOUS", "TBD", "NONE").stream()
                .map(word -> word.chars().mapToObj(c -> "[" + (char)c + Character.toLowerCase((char)c) + "]").collect(java.util.stream.Collectors.joining())).toList());
    }
    private static Map<String, Field> scope() {
        Map<String, Field> f = new TreeMap<>();
        for (String key : List.of("source_id", "product_id", "data_type")) f.put(key, string(ID));
        f.put("instruments", set(List.of()));
        f.put("data_start", string(DATE)); f.put("data_end", string(DATE));
        return f;
    }
    private static Map<String, Field> fields(String name) {
        Map<String, Field> f = name.equals("permission-evidence") ? new TreeMap<>() : scope();
        if (!name.equals("permission-record")) f.put("schema_version", choice("1"));
        switch (name) {
            case "dataset-manifest" -> {
                f.put("dataset_id", string(ID)); f.put("dataset_sha256", string(HASH));
                f.put("classification", choice("SYNTHETIC", "CALLER_DECLARED"));
                f.put("candidate_status", choice("CANDIDATE"));
            }
            case "intended-use" -> {
                f.put("use_id", string(ID)); f.put("manifest_sha256", string(HASH));
                for (String key : List.of("use_start", "use_end", "retention_start", "delete_by")) f.put(key, string(DATE));
                f.put("purposes", set(List.of("SIMULATION", "BACKTEST")));
                f.put("storage_locations", set(List.of())); f.put("derived_outputs", set(OUTPUTS));
                f.put("sharing_mode", new Field("string", null, SHARING));
                f.put("sharing_recipients", recipients()); f.put("shared_material", set(MATERIAL));
            }
            case "permission-evidence" -> {
                f.put("evidence_set_id", string(ID)); f.put("classification", choice("SYNTHETIC", "CALLER_DECLARED"));
                f.put("completeness", choice("COMPLETE", "INCOMPLETE", "UNKNOWN"));
            }
            case "permission-record" -> {
                f.put("record_id", string(ID)); f.put("manifest_sha256", string(HASH));
                f.put("effect", choice("ALLOW", "DENY")); f.put("record_state", choice("ACTIVE", "REVOKED", "AMBIGUOUS"));
                for (String key : List.of("effective_from", "effective_through", "retain_until", "reviewed_on")) f.put(key, string(DATE));
                f.put("max_retention_days", new Field("integer", null, List.of()));
                f.put("purposes", set(List.of("SIMULATION", "BACKTEST")));
                f.put("storage_locations", set(List.of())); f.put("derived_outputs", set(OUTPUTS));
                f.put("sharing_modes", set(SHARING)); f.put("sharing_recipients", recipients()); f.put("shared_material", set(MATERIAL));
                for (String key : List.of("owner", "reviewer")) f.put(key, string(ID));
                for (String key : List.of("owner_approval", "review_status")) f.put(key, choice("APPROVED", "PENDING", "REJECTED"));
                for (String key : List.of("owner_approval_reference", "review_reference", "evidence_reference")) f.put(key, string(REF));
                f.put("evidence_sha256", string(HASH));
            }
            default -> throw new IllegalArgumentException("Unknown schema: " + name);
        }
        return f;
    }
    public static byte[] schema(String name) {
        Map<String, Object> schema = definition(name);
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("title", name + " v1 (additional semantic rules in the preflight guide)");
        return PermissionJson.bytes(schema);
    }
    private static Map<String, Object> definition(String name) {
        Map<String, Object> properties = new TreeMap<>();
        fields(name).forEach((key, field) -> properties.put(key, field.json()));
        if (name.equals("permission-evidence")) properties.put("records", Map.of("type", "array", "minItems", 1,
                "maxItems", 64, "items", definition("permission-record")));
        Map<String, Object> result = new TreeMap<>();
        result.put("type", "object"); result.put("additionalProperties", false);
        result.put("properties", properties); result.put("required", new ArrayList<>(properties.keySet()));
        return result;
    }
    static boolean validate(String name, Object input, String path, List<PermissionPreflight.Reason> reasons) {
        int before = reasons.size();
        Map<String, Object> obj = PermissionJson.object(input);
        if (obj == null) { missing(reasons, path, "Expected a complete JSON object"); return false; }
        Map<String, Field> f = fields(name);
        for (String key : obj.keySet()) if (!f.containsKey(key) && !(name.equals("permission-evidence") && key.equals("records")))
            missing(reasons, path + "." + key, "Unknown field; scope is not inferred");
        f.forEach((key, field) -> {
            if (!obj.containsKey(key) || obj.get(key) == null || obj.get(key).equals("")) missing(reasons, path + "." + key, "Required evidence or scope is missing");
            else if (!field.valid(obj.get(key))) missing(reasons, path + "." + key, "Invalid type, value, date, enum, or nonempty unique set");
        });
        if (name.equals("permission-evidence")) {
            if (!(obj.get("records") instanceof List<?> records) || records.isEmpty() || records.size() > 64)
                missing(reasons, path + ".records", "Require 1 to 64 complete applicable records");
            else for (int i = 0; i < records.size(); i++) validate("permission-record", records.get(i), path + ".records[" + i + "]", reasons);
        }
        return reasons.size() == before;
    }
    private static void missing(List<PermissionPreflight.Reason> reasons, String field, String message) {
        reasons.add(new PermissionPreflight.Reason(PermissionPreflight.Status.INCOMPLETE, "SCHEMA_INCOMPLETE", field, message));
    }
}
