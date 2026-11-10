package com.mscient.intrapaper.ops.governance;

import static com.mscient.intrapaper.ops.governance.G7Json.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/** Executable, dependency-free subset of JSON Schema; published schemas come from these same definitions. */
public final class G7Schema {
    public static final List<String> DOMAINS = List.of("STRATEGY", "SOURCE", "BUILD", "CONFIGURATION", "PARAMETERS",
            "ROUTE", "INSTRUMENTS", "CAPACITY", "RISK_LIMITS", "COSTS", "DATA_SOURCE", "MARKET_RULES", "HOSTING", "OPERATING_PROCEDURE");
    public static final List<String> CATEGORIES = List.of("RAW_INPUT", "FEED", "ORDERS", "ACKNOWLEDGEMENTS", "EXECUTIONS",
            "CANCELLATIONS", "CORRECTIONS", "POSITIONS", "FEES", "CASH", "MARGIN", "SETTLEMENT", "RECONCILIATION",
            "RISK", "INCIDENTS", "RECOVERY", "OPERATOR", "DAILY_OPS", "REPLAY");
    public static final List<String> STREAMS = List.of("ORDERS", "ACKNOWLEDGEMENTS", "EXECUTIONS", "CANCELLATIONS",
            "CORRECTIONS", "POSITIONS", "FEES", "CASH", "MARGIN", "SETTLEMENT");
    public static final List<String> GATES = List.of("G0", "G1", "G2", "G3", "G4", "G5", "G6");
    static final List<String> EVENTS = List.of("REGISTRATION", "PERIOD", "PERIOD_EVIDENCE", "EVIDENCE", "BUNDLE", "CHANGE", "INCIDENT", "STOP",
            "RECOVERY", "CORRECTION", "SUPERSESSION", "REVIEWER_DECISION", "OWNER_DECISION", "REVOCATION");
    private static final Map<String, Object> TEXT = map("type", "string", "minLength", 1, "maxLength", 4096);
    private static final Map<String, Object> ID = map("type", "string", "pattern", "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$(?![\\s\\S])");
    private static final Map<String, Object> LABEL = map("type", "string", "pattern", "^[A-Z][A-Z0-9_]{0,63}$(?![\\s\\S])");
    private static final Map<String, Object> HASH = map("type", "string", "pattern", "^[0-9a-f]{64}$(?![\\s\\S])");
    private static final Map<String, Object> TIME = map("type", "string", "format", "date-time", "maxLength", 40,
            "pattern", "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{3}(?:[0-9]{3})?(?:[0-9]{3})?)?Z$(?![\\s\\S])");
    private static final Map<String, Object> BOOL = map("type", "boolean");
    private static final Map<String, Object> UINT = map("type", "integer", "minimum", 0, "maximum", Long.MAX_VALUE);
    private static final Map<String, Object> POSITIVE = map("type", "integer", "minimum", 1, "maximum", Long.MAX_VALUE);
    private static final Map<String, Object> DEFINITIONS = definitions();
    private G7Schema() { }
    private static Map<String, Object> enumeration(Collection<String> values) { return map("enum", values); }
    private static Map<String, Object> en(String... values) { return enumeration(List.of(values)); }
    private static Map<String, Object> nullable(Map<String, Object> schema) { return map("anyOf", List.of(schema, map("type", "null"))); }
    private static Map<String, Object> array(Map<String, Object> items) { return map("type", "array", "items", items, "maxItems", 100_000); }
    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> properties = map(pairs);
        return map("type", "object", "properties", properties, "required", new ArrayList<>(properties.keySet()), "additionalProperties", false);
    }
    private static Map<String, Object> ref(String name) { return map("$ref", "#/$defs/" + name); }
    private static Map<String, Object> dictionary(Map<String, Object> values) {
        return map("type", "object", "propertyNames", ID, "additionalProperties", values, "maxProperties", 2048);
    }
    private static Map<String, Object> definitions() {
        Map<String, Object> d = new TreeMap<>();
        var ids = array(ID); var gates = array(enumeration(GATES));
        var signature = nullable(object("algorithm", en("Ed25519"), "public_key", TEXT, "value", TEXT));
        d.put("signature", signature);
        d.put("interval", object("start", TIME, "end", TIME));
        Map<String, Object> identities = new TreeMap<>(); DOMAINS.forEach(k -> identities.put(k, nullable(ID)));
        identities.put("schema", en("g7-envelope/v1"));
        d.put("envelope", map("type", "object", "properties", identities, "required", new ArrayList<>(identities.keySet()), "additionalProperties", false));
        d.put("configuration", object("schema", en("g7-configuration/v1"), "daily_ops_configuration", nullable(ID),
                "portfolio_settings", nullable(ID), "dataset_configurations", dictionary(nullable(ID))));
        d.put("decision", object("schema", en("g7-decision/v1"), "decision_id", ID, "role", en("OWNER", "INDEPENDENT_REVIEWER"),
                "actor_id", ID, "actor_kind", en("HUMAN", "AI", "UNKNOWN"), "scope", TEXT, "at", TIME, "expires", TIME,
                "outcome", en("APPROVE", "REJECT", "RESERVATIONS"), "reservations", array(TEXT),
                "reviewed_hashes", dictionary(nullable(HASH)), "reviewed_head", HASH, "report_sha256", HASH,
                "reviewer_decision_sha256", nullable(HASH), "qualification_evidence", nullable(ID),
                "independence_evidence", nullable(ID), "authority_evidence", nullable(ID), "identity_evidence", nullable(ID), "signature", signature));
        d.put("gate-decision", object("schema", en("g7-gate-decision/v1"), "gate", enumeration(GATES),
                "decision_id", nullable(ID), "status", en("APPROVED", "OPEN", "INCOMPLETE", "REJECTED", "REVOKED", "UNKNOWN"),
                "scope", nullable(ID), "approved_at", nullable(TIME), "expires", nullable(TIME),
                "envelope_sha256", nullable(HASH), "complete", nullable(BOOL), "source_artifact_hashes", dictionary(HASH),
                "signer_id", nullable(ID), "signer_kind", en("HUMAN", "AI", "UNKNOWN"),
                "identity_evidence", nullable(ID), "authority_evidence", nullable(ID),
                "qualification_evidence", nullable(ID), "independence_evidence", nullable(ID), "signature", signature));
        d.put("rules", object("schema", en("g7-rules/v1"), "registered_at", TIME, "owner_rules_evidence", nullable(ID), "policy_review_evidence", nullable(ID),
                "cadence_seconds", nullable(POSITIVE), "review_freshness_seconds", nullable(POSITIVE),
                "escalation_path", nullable(ID), "escalation_evidence", nullable(ID),
                "finding_severities", array(object("finding_code", LABEL, "severity", nullable(LABEL))),
                "freshness", array(object("category", enumeration(CATEGORIES), "max_age_seconds", nullable(POSITIVE))),
                "triggers", array(object("trigger_id", ID, "kind", en("CHANGE", "INCIDENT", "EXCEPTION"),
                    "scope", enumeration(java.util.stream.Stream.concat(java.util.stream.Stream.concat(DOMAINS.stream(), CATEGORIES.stream()),
                            java.util.stream.Stream.of("ANY")).distinct().toList()), "severities", array(LABEL), "stop_required", nullable(BOOL),
                    "requalification", gates, "required_actions", ids))));
        d.put("manifest", object("schema", en("g7-manifest/v1"), "review_id", ID, "scope", ID,
                "period", ref("interval"), "as_of", TIME, "mode", en("SYNTHETIC_DIAGNOSTIC", "CLAIMED_OPERATIONAL"),
                "owner", object("id", nullable(ID), "kind", en("HUMAN", "AI", "UNKNOWN"), "authority_evidence", nullable(ID), "identity_evidence", nullable(ID)),
                "reviewer", object("id", nullable(ID), "kind", en("HUMAN", "AI", "UNKNOWN"), "scope", nullable(ID),
                    "qualification_evidence", nullable(ID), "independence_evidence", nullable(ID), "identity_evidence", nullable(ID)),
                "approved_envelope", ref("envelope"), "gates", array(object("gate", enumeration(GATES),
                    "decision_id", nullable(ID), "status", en("APPROVED", "OPEN", "INCOMPLETE", "REJECTED", "REVOKED", "UNKNOWN"),
                    "artifact", nullable(ID), "approved_at", nullable(TIME), "expires", nullable(TIME),
                    "scope", nullable(ID), "envelope_sha256", nullable(HASH), "complete", nullable(BOOL), "artifacts", ids)),
                "rules", nullable(ref("rules"))));
        d.put("artifact", object("schema", en("g7-input-artifact/v1"), "id", ID, "path", map("type", "string", "minLength", 1, "maxLength", 240,
                    "pattern", "^[A-Za-z0-9][A-Za-z0-9._/-]*$(?![\\s\\S])"), "sha256", HASH,
                "size_bytes", map("type", "integer", "minimum", 0, "maximum", G7Files.MAX_FILE), "format", en("JSON", "UTF8", "BINARY"),
                "category", enumeration(java.util.stream.Stream.concat(CATEGORIES.stream(), java.util.stream.Stream.of("ENVELOPE", "APPROVAL", "POLICY", "PROVENANCE", "NORMALIZATION", "REPLAY_INPUT", "RISK_INPUT", "SETTLEMENT_INPUT")).toList()),
                "classification", en("SYNTHETIC", "CALLER_SUPPLIED", "CLAIMED_OPERATIONAL", "ESTIMATE", "CAPACITY_CLAIM"),
                "source_identity", nullable(ID), "provenance", nullable(TEXT), "observed_at", nullable(TIME),
                "period_id", nullable(ID), "coverage", nullable(ref("interval")), "complete", nullable(BOOL),
                "authentication", en("UNAUTHENTICATED", "SELF_ASSERTED"), "signature", signature));
        d.put("scheduled-period", object("schema", en("g7-scheduled-period/v1"), "period_id", ID, "start", TIME, "end", TIME, "status", en("EXPECTED", "EXCLUDED"), "reason", nullable(TEXT)));
        d.put("period", object("schema", en("g7-period/v1"), "period_id", ID, "status", en("OBSERVED", "MISSING"), "reason", nullable(TEXT),
                "observed_start", nullable(TIME), "observed_end", nullable(TIME), "envelope", ref("envelope"), "evidence", ids,
                "daily_ops", nullable(ID), "feed_audit", nullable(ID), "feed_scope_ids", nullable(ids), "portfolio_run", nullable(ID),
                "portfolio_inputs", nullable(ID), "portfolio_settings", nullable(ID),
                "risk_audit", nullable(ID), "risk_inputs", nullable(object("limits", nullable(ID), "margin_proxies", nullable(ID), "sources", nullable(ID), "budgets", nullable(ID))),
                "settlement_audit", nullable(ID), "settlement_events", nullable(ID), "settlement_costs", nullable(ID),
                "settlement_costs_mode", en("UNKNOWN", "NOT_MODELED", "PROVIDED"), "reconciliation", nullable(ID),
                "exceptions", array(object("exception_id", ID, "category", enumeration(CATEGORIES), "severity", nullable(LABEL),
                    "description", TEXT, "evidence", ids, "incident_event_id", nullable(ID)))));
        d.put("change", object("schema", en("g7-change/v1"), "change_id", ID, "period_id", nullable(ID), "at", TIME, "scope", enumeration(DOMAINS),
                "before", nullable(ID), "after", nullable(ID), "approvals", ids, "history_event_id", ID));
        d.put("bundle", object("schema", en("g7-bundle/v1"), "review_id", ID, "artifacts", array(ref("artifact")),
                "schedule", array(ref("scheduled-period")), "periods", array(ref("period")), "changes", array(ref("change")),
                "statistical_estimates", ids, "capacity_claims", ids));
        d.put("event", object("schema", en("g7-event/v1"), "sequence", UINT, "event_id", ID,
                "kind", enumeration(EVENTS), "occurred_at", TIME, "recorded_at", TIME,
                "scope", ID, "period_id", nullable(ID), "severity", nullable(LABEL),
                "evidence", ids, "response", nullable(TEXT), "stop_status", en("NOT_REQUIRED", "REQUIRED", "RECORDED", "UNKNOWN"),
                "resolution_evidence", ids, "required_rereview", gates, "prior_event_id", nullable(ID), "supersedes_sha256", nullable(HASH),
                "artifact_record", nullable(ref("artifact")), "period_record", nullable(ref("period")), "change_record", nullable(ref("change")),
                "subject_sha256", HASH, "decision", nullable(ref("decision")), "previous_sha256", HASH, "signature", signature));
        d.put("history", object("schema", en("g7-history/v1"), "review_id", ID, "events", array(ref("event"))));
        d.put("publication", object("schema", en("g7-publication/v1"), "report_sha256", HASH, "report_size_bytes", UINT,
                "history_sha256", HASH, "history_size_bytes", UINT, "history_event_count", POSITIVE,
                "input_hashes", dictionary(nullable(HASH)), "input_sizes", dictionary(nullable(UINT)), "history_head", HASH,
                "dossier_history_sha256", HASH, "build_commit", TEXT, "code_sha256", HASH,
                "g7_closed", map("const", false), "operation_authorized", map("const", false), "scale_authorized", map("const", false)));
        d.put("reconciliation", object("schema", en("g7-reconciliation/v1"), "period_id", ID,
                "coverage", nullable(ref("interval")), "normalization_evidence", nullable(ID), "internal_source", nullable(ID), "external_source", nullable(ID),
                "external_identity", nullable(ID), "streams", array(object("kind", enumeration(STREAMS),
                    "internal_complete", nullable(BOOL), "external_complete", nullable(BOOL),
                    "internal_count", nullable(UINT), "external_count", nullable(UINT),
                    "internal", array(ref("record")), "external", array(ref("record"))))));
        d.put("normalized-ledger", object("schema", en("g7-normalized-ledger/v1"), "period_id", ID,
                "classification", en("SYNTHETIC", "CALLER_SUPPLIED", "CLAIMED_OPERATIONAL"), "raw_source", nullable(ID),
                "coverage", nullable(ref("interval")), "normalization_evidence", nullable(ID), "streams", array(object("kind", enumeration(STREAMS),
                    "complete", nullable(BOOL), "count", nullable(UINT), "records", array(ref("record"))))));
        d.put("record", object("schema", en("g7-normalized-record/v1"), "record_id", ID, "at", TIME, "order_id", nullable(ID), "instrument_id", nullable(ID),
                "prior_record_id", nullable(ID), "values", dictionary(nullable(TEXT))));
        return d;
    }
    public static Map<String, Object> document(String name) {
        if (!DEFINITIONS.containsKey(name)) throw new IllegalArgumentException("Unknown G7 schema");
        Map<String, Object> document = map("$schema", "https://json-schema.org/draft/2020-12/schema", "$id", "urn:mscient:g7:" + name + ":v1",
                "$ref", "#/$defs/" + name, "$defs", DEFINITIONS);
        try { return obj(parse(G7Json.bytes(document))); }
        catch (IOException impossible) { throw new IllegalStateException("Invalid internal schema", impossible); }
    }
    public static byte[] bytes(String name) { return G7Json.bytes(document(name)); }
    public static Set<String> names() { return Collections.unmodifiableSet(DEFINITIONS.keySet()); }
    static void validate(String name, Object value) throws IOException {
        if (!DEFINITIONS.containsKey(name)) throw new IOException("Unsupported G7 schema");
        validateSchema(obj(DEFINITIONS.get(name)), value, name);
    }
    private static void validateSchema(Map<String, Object> s, Object v, String path) throws IOException {
        if (s.containsKey("const")) { if (!Objects.equals(s.get("const"), v)) throw new IOException(path + ": unsupported constant"); return; }
        if (s.containsKey("$ref")) { validateSchema(obj(DEFINITIONS.get(str(s, "$ref").substring(8))), v, path); return; }
        if (s.containsKey("anyOf")) {
            for (Object candidate : list(s.get("anyOf"))) {
                try { validateSchema(obj(candidate), v, path); return; } catch (IOException ignored) { }
            }
            throw new IOException(path + ": value does not match nullable contract");
        }
        if (s.containsKey("enum")) { if (!((Collection<?>) s.get("enum")).contains(v)) throw new IOException(path + ": unsupported value"); return; }
        String type = str(s, "type");
        boolean valid = switch (type) {
            case "null" -> v == null; case "object" -> v instanceof Map; case "array" -> v instanceof List;
            case "string" -> v instanceof String; case "boolean" -> v instanceof Boolean;
            case "integer" -> v instanceof BigDecimal n && n.stripTrailingZeros().scale() <= 0 || v instanceof Integer || v instanceof Long;
            default -> false;
        };
        if (!valid) throw new IOException(path + ": expected " + type);
        if (v instanceof String text) {
            if (s.containsKey("minLength") && (text.isBlank() || text.codePointCount(0, text.length()) < ((Number) s.get("minLength")).intValue())
                    || s.containsKey("maxLength") && text.codePointCount(0, text.length()) > ((Number) s.get("maxLength")).intValue()
                    || s.containsKey("pattern") && !text.matches(str(s, "pattern"))) throw new IOException(path + ": invalid string");
            if (s.containsKey("format")) try { time(text); } catch (RuntimeException e) { throw new IOException(path + ": invalid UTC timestamp"); }
        } else if (v instanceof Number) {
            try { long n = integer(v); if (n < ((Number) s.get("minimum")).longValue()
                    || s.containsKey("maximum") && n > ((Number) s.get("maximum")).longValue()) throw new ArithmeticException(); }
            catch (ArithmeticException e) { throw new IOException(path + ": integer outside supported range"); }
        } else if (v instanceof List<?> a) {
            if (a.size() > 100_000) throw new IOException(path + ": too many entries");
            for (int i = 0; i < a.size(); i++) validateSchema(obj(s.get("items")), a.get(i), path + "[" + i + "]");
        } else if (v instanceof Map<?, ?>) {
            Map<String, Object> m = obj(v), properties = s.containsKey("properties") ? obj(s.get("properties")) : Map.of();
            if (s.containsKey("maxProperties") && m.size() > ((Number) s.get("maxProperties")).intValue()) throw new IOException(path + ": too many properties");
            if (s.containsKey("required") && !m.keySet().containsAll((Collection<?>) s.get("required"))) throw new IOException(path + ": missing fields; use explicit null for unknown values");
            for (var e : m.entrySet()) {
                if (s.containsKey("propertyNames")) validateSchema(obj(s.get("propertyNames")), e.getKey(), path + ".key");
                Object child = properties.get(e.getKey());
                if (child == null) child = s.get("additionalProperties");
                if (!(child instanceof Map)) throw new IOException(path + ": unknown field " + e.getKey());
                validateSchema(obj(child), e.getValue(), path + "." + e.getKey());
            }
        }
    }
}
