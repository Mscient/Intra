package com.mscient.intrapaper.evaluation.hypothesis;

import com.mscient.intrapaper.data.StrictJsonParser;
import com.mscient.intrapaper.evaluation.ResearchTrialLedger;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Offline specification identity, never an approval, entitlement, or performance gate. */
public final class HypothesisCard {
    public static final String SCHEMA = "INTRA_HYPOTHESIS_CARD_V1";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final String ID = "[A-Za-z0-9][A-Za-z0-9._-]{0,63}";
    private static final String HASH = "[0-9a-f]{64}";
    private static final String TIME = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z";
    private static final Node DEFINITION = definition();
    private final Map<String, Object> values;
    private final byte[] bytes;
    private final Map<String, byte[]> attachments;

    private HypothesisCard(Map<String, Object> values, byte[] bytes, Map<String, byte[]> attachments) {
        this.values = values;
        this.bytes = bytes.clone();
        this.attachments = attachments;
    }

    public static HypothesisCard load(Path file) throws IOException {
        byte[] bytes = read(file);
        Object parsed = StrictJsonParser.parse(decode(bytes));
        DEFINITION.validate(parsed, "card");
        Map<String, Object> values = object(parsed);
        semantics(values);
        Map<String, byte[]> attachments = new TreeMap<>();
        for (String key : List.of("cost_model", "variant_ledger", "source_register")) {
            Map<String, Object> a = object(values.get(key));
            String name = (String) a.get("file");
            byte[] content = read(file.toAbsolutePath().getParent().resolve(name));
            if (!sha256(content).equals(a.get("sha256"))) throw new IOException("Attachment hash mismatch: " + key);
            if (attachments.putIfAbsent(name, content) != null) throw new IOException("Attachment filenames must be distinct");
        }
        return new HypothesisCard(values, bytes, attachments);
    }

    public String id() { return (String) values.get("card_id"); }
    public int version() { return ((BigDecimal) values.get("card_version")).intValueExact(); }
    public String status() { return (String) values.get("status"); }
    public String sha256() { return sha256(bytes); }
    public String predecessor() { return (String) values.get("previous_card_sha256"); }
    public String directoryName() { return id() + ".v" + version(); }
    byte[] bytes() { return bytes.clone(); }
    Map<String, byte[]> attachments() {
        Map<String, byte[]> copy = new TreeMap<>();
        attachments.forEach((key, value) -> copy.put(key, value.clone()));
        return copy;
    }

    /** Checks identity only against an existing trial; does not register or enable anything. */
    public void requireTrialBinding(ResearchTrialLedger.Trial trial) throws IOException {
        if (!status().equals("FROZEN_DECLARED") || !values.get("classification").equals("PLANNING_ONLY")) {
            throw new IOException("Proposed or synthetic cards cannot bind trials through this facility");
        }
        if (!trial.strategyCardSha256().equals(sha256())
                || !trial.strategyId().equals(values.get("strategy_id"))
                || !trial.strategyVersion().equals(values.get("strategy_version"))
                || !((List<?>) values.get("variant_ids")).contains(trial.variantId())) {
            throw new IOException("Trial card, strategy, version, or variant identity mismatch");
        }
    }

    private static void semantics(Map<String, Object> v) throws IOException {
        for (String key : List.of("card_id", "strategy_id", "strategy_version", "variant_family_id", "benchmark_id", "primary_metric_id")) requireResolvedId(v.get(key));
        for (String key : List.of("universe_candidate_ids", "variant_ids")) for (Object id : (List<?>) v.get(key)) requireResolvedId(id);
        Instant created = timestamp(v.get("recorded_at_utc"));
        Instant frozen = v.get("frozen_at_utc") == null ? null : timestamp(v.get("frozen_at_utc"));
        int version = ((BigDecimal) v.get("card_version")).intValueExact();
        if ((version == 1) != (v.get("previous_card_sha256") == null)) throw new IOException("Version/predecessor mismatch");
        if (v.get("previous_card_sha256") != null) requireNonzeroHash(v.get("previous_card_sha256"));
        boolean isFrozen = v.get("status").equals("FROZEN_DECLARED");
        if (isFrozen != (frozen != null)) throw new IOException("Freeze status/timestamp mismatch");
        if (frozen != null && frozen.isBefore(created)) throw new IOException("Freeze precedes card recording");
        Map<String, Object> reviews = object(v.get("reviews"));
        for (Object review : reviews.values()) {
            Map<String, Object> r = object(review);
            boolean declared = r.get("status").equals("CALLER_DECLARED");
            if (declared) {
                if (r.get("record_id") == null || r.get("evidence_sha256") == null || r.get("at_utc") == null) {
                    throw new IOException("Declared review requires identity, evidence hash and timestamp");
                }
                requireResolvedId(r.get("record_id"));
                requireNonzeroHash(r.get("evidence_sha256"));
                Instant at = timestamp(r.get("at_utc"));
                if (frozen != null && at.isAfter(frozen)) throw new IOException("Review follows freeze");
            } else if (r.get("record_id") != null || r.get("evidence_sha256") != null || r.get("at_utc") != null) {
                throw new IOException("Pending reviews must have null evidence, identity and timestamp");
            }
            if (isFrozen && !declared) throw new IOException("Frozen declaration requires all five declared reviews");
        }
        if (v.get("status").equals("PROPOSED_PENDING_OWNER_SELECTION")
                && object(reviews.get("owner_selection")).get("status").equals("CALLER_DECLARED")) {
            throw new IOException("Pending selection contradicts declared owner selection");
        }
        for (String key : List.of("cost_model", "variant_ledger", "source_register")) {
            Map<String, Object> attachment = object(v.get(key));
            requireResolvedId(attachment.get("id")); requireResolvedId(attachment.get("version"));
            if (Set.of("card.json", "identity.sha256").contains(attachment.get("file"))) throw new IOException("Reserved attachment filename");
            requireNonzeroHash(attachment.get("sha256"));
        }
    }

    private static void requireResolvedId(Object value) throws IOException {
        String id = ((String) value).toUpperCase(java.util.Locale.ROOT);
        if (Set.of("UNKNOWN", "UNRESOLVED", "NONE", "TBD", "UNVERIFIED", "UNSPECIFIED").contains(id) || id.startsWith("PENDING_")) {
            throw new IOException("Identity cannot be an unresolved placeholder");
        }
    }

    private static Instant timestamp(Object value) throws IOException {
        try {
            Instant result = Instant.parse((String) value);
            if (!result.toString().equals(value)) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException e) { throw new IOException("Noncanonical UTC timestamp", e); }
    }

    private static void requireNonzeroHash(Object value) throws IOException {
        if ("0".repeat(64).equals(value)) throw new IOException("Placeholder evidence hash");
    }

    static byte[] read(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) throw new IOException("Expected ordinary local file");
        long size = Files.size(path);
        if (size < 1 || size > MAX_BYTES) throw new IOException("Input size outside 1..1048576 bytes");
        // Bounded read even if the file grows after the size check.
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length < 1 || bytes.length > MAX_BYTES) throw new IOException("Input size changed outside bounds");
            return bytes;
        }
    }

    static String decode(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }

    private record Node(String type, boolean nullable, String pattern, List<String> choices,
                        Map<String, Node> fields, Node items) {
        void validate(Object v, String label) throws IOException {
            if (v == null && nullable) return;
            boolean valid = switch (type) {
                case "object" -> v instanceof Map<?, ?> m && m.keySet().equals(fields.keySet());
                case "array" -> v instanceof List<?> a && !a.isEmpty() && a.size() <= 256 && a.stream().distinct().count() == a.size();
                case "integer" -> v instanceof BigDecimal n && n.compareTo(BigDecimal.ONE) >= 0
                        && n.compareTo(BigDecimal.valueOf(1000000)) <= 0 && n.stripTrailingZeros().scale() <= 0;
                case "string" -> v instanceof String s && validString(s) && (pattern == null || s.matches(pattern))
                        && (choices.isEmpty() || choices.contains(s));
                default -> false;
            };
            if (!valid) throw new IOException("Schema mismatch: " + label);
            if (type.equals("object")) for (var f : fields.entrySet()) f.getValue().validate(object(v).get(f.getKey()), label + "." + f.getKey());
            if (type.equals("array")) for (Object item : (List<?>) v) items.validate(item, label + "[]");
        }
        Map<String, Object> schema() {
            Map<String, Object> m = new TreeMap<>();
            m.put("type", nullable ? List.of(type, "null") : type);
            if (pattern != null) m.put("pattern", "^" + pattern + "$");
            if (!choices.isEmpty()) m.put("enum", choices);
            if (type.equals("object")) {
                Map<String, Object> p = new TreeMap<>(); fields.forEach((k, v) -> p.put(k, v.schema()));
                m.put("properties", p); m.put("required", fields.keySet().stream().sorted().toList()); m.put("additionalProperties", false);
            }
            if (type.equals("string")) { m.put("minLength", 1); m.put("maxLength", 16000); }
            if (type.equals("integer")) { m.put("minimum", 1); m.put("maximum", 1000000); }
            if (type.equals("array")) { m.put("items", items.schema()); m.put("minItems", 1); m.put("maxItems", 256); m.put("uniqueItems", true); }
            return m;
        }
    }
    private static boolean validString(String s) {
        if (s.isEmpty() || s.length() > 16000 || !s.equals(s.strip())) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 127) return false;
            if (Character.isHighSurrogate(c)) {
                if (++i >= s.length() || !Character.isLowSurrogate(s.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    private static Node text() { return string(null, false); }
    private static Node string(String pattern, boolean nullable) { return new Node("string", nullable, pattern, List.of(), Map.of(), null); }
    private static Node choice(String... choices) { return new Node("string", false, null, List.of(choices), Map.of(), null); }
    private static Node obj(Map<String, Node> fields) { return new Node("object", false, null, List.of(), Map.copyOf(fields), null); }
    private static Node section(String... names) {
        Map<String, Node> f = new TreeMap<>(); for (String name : names) f.put(name, text()); return obj(f);
    }
    private static Node definition() {
        Map<String, Node> f = new TreeMap<>();
        f.put("schema", choice(SCHEMA));
        for (String key : List.of("card_id", "strategy_id", "strategy_version", "variant_family_id", "benchmark_id", "primary_metric_id")) f.put(key, string(ID, false));
        f.put("card_version", new Node("integer", false, null, List.of(), Map.of(), null));
        f.put("previous_card_sha256", string(HASH, true)); f.put("changelog", text());
        f.put("recorded_at_utc", string(TIME, false)); f.put("frozen_at_utc", string(TIME, true));
        f.put("classification", choice("PLANNING_ONLY", "SYNTHETIC_PLUMBING_ONLY"));
        f.put("status", choice("PROPOSED_PENDING_OWNER_SELECTION", "PROPOSED_PENDING_REVIEW", "DEFERRED", "FROZEN_DECLARED"));
        for (String key : List.of("universe_candidate_ids", "variant_ids")) f.put(key, new Node("array", false, null, List.of(), Map.of(), string(ID, false)));
        Node attachment = obj(Map.of("id", string(ID, false), "version", string(ID, false),
                "file", string("[A-Za-z0-9][A-Za-z0-9._-]{0,127}", false), "sha256", string(HASH, false)));
        for (String key : List.of("cost_model", "variant_ledger", "source_register")) f.put(key, attachment);
        f.put("hypothesis", section("economic_mechanism", "participant_constraint", "event_signal", "decision_timestamp", "holding_horizon", "expected_sign", "expected_size_range", "rationale_sources"));
        f.put("instruments", section("research_universe", "identity_requirements", "contract_selection", "expiry_roll_policy", "candidate_disposition"));
        f.put("measurement", section("primary_metric_definition", "units", "observation_unit", "benchmark_definition", "secondary_diagnostics"));
        Map<String, Node> data = new TreeMap<>(section("source_fields_frequency", "timestamp_roles", "session_calendar", "known_gaps", "minimum_usable_sample", "collection_storage_replay_derived_sharing").fields());
        data.put("rights_state", choice("UNRESOLVED", "PENDING_SOURCE_REVIEW", "CALLER_DECLARED_REVIEWED"));
        f.put("data", obj(data));
        f.put("execution", section("signal_vs_pnl", "order_type", "latency_spread", "fills_queue_partial_missed", "impact_adverse_selection"));
        f.put("economics", section("cost_formula", "component_status", "capital_margin_funding", "fixed_time_cost", "sensitivity_scenarios"));
        f.put("design", section("all_candidates_variants", "prior_exposure", "development", "validation", "holdout", "multiple_testing", "no_retuning"));
        Map<String, Node> decisions = new TreeMap<>(section("falsification_rejection", "missing_data_behavior", "stop_conditions", "required_approvals").fields());
        decisions.put("gate_status", choice("INCOMPLETE"));
        f.put("decisions", obj(decisions));
        Node review = obj(Map.of("status", choice("PENDING_OWNER_INPUT", "UNRESOLVED", "CALLER_DECLARED"),
                "record_id", string(ID, true), "evidence_sha256", string(HASH, true), "at_utc", string(TIME, true)));
        Map<String, Node> reviews = new TreeMap<>();
        for (String key : List.of("owner_selection", "independent_review", "learning_acknowledgment", "private_a15_attestation", "g0_review")) reviews.put(key, review);
        f.put("reviews", obj(reviews));
        return obj(f);
    }

    /** Minimal deterministic JSON writer for the published structural schema, not card rewriting. */
    public static String schemaJson() {
        Map<String, Object> schema = new LinkedHashMap<>(DEFINITION.schema());
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("$id", "urn:intra:hypothesis-card:v1");
        return json(schema) + "\n";
    }
    private static String json(Object value) {
        if (value instanceof Map<?, ?> map) return "{" + String.join(",", map.entrySet().stream().map(e -> json(e.getKey()) + ":" + json(e.getValue())).toList()) + "}";
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(HypothesisCard::json).toList()) + "]";
        if (value instanceof String s) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return String.valueOf(value);
    }
}
