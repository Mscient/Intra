package com.mscient.intrapaper.ops.governance;

import static com.mscient.intrapaper.ops.governance.G7Json.*;
import java.io.IOException;
import java.time.*;
import java.util.*;

/** Offline evidence reduction. No method grants authority or calls an operating control. */
public final class G7Review {
    public static final String LIMITS = "G7 is proposed, outside the original G0-G6 roadmap. Hashes establish byte identity only. "
            + "No source, signer, reviewer qualification, independence, custody or historical time is authenticated. "
            + "No profitability, legal-compliance, operational-readiness or trading-readiness claim. "
            + "No gate closure, operation, order, resume, limit change or scale authorization. "
            + "Owner adoption is required before G7 could become a project gate. Eligibility is unavailable in this revision. "
            + "Neither omitted evidence nor a fully forged/rechained history is authenticated by a self-contained hash chain.";
    final G7Files input;
    final Map<String, Object> manifest, bundle;
    final Map<String, Map<String, Object>> artifacts = new TreeMap<>(), schedule = new LinkedHashMap<>(), periods = new TreeMap<>();
    final Map<String, String> inventory = new TreeMap<>();
    final Map<String, Object> inventorySizes = new TreeMap<>();
    final Set<String> invalidArtifacts = new HashSet<>();
    final List<Map<String, Object>> findings = new ArrayList<>(), observations = new ArrayList<>();
    final List<Map<String, Object>> triggers = new ArrayList<>();
    final Map<String, Long> freshness = new TreeMap<>();
    final G7Ledger ledger;
    final Instant asOf, start, end;
    private final Map<String, Object> rules;
    private final String reviewId, scope;
    private final String codeIdentity, buildCommit;
    private final Map<String, String> severityRules = new TreeMap<>();
    private String contextPeriod, contextSeverity;
    private boolean contextSeverityApplicable;
    private final Set<String> contextArtifacts = new TreeSet<>(), actionArtifacts = new TreeSet<>();
    private boolean evaluated;

    G7Review(G7Files input, byte[] history) throws IOException {
        this.input = input;
        manifest = input.json("manifest.json", "manifest"); bundle = input.json("bundle.json", "bundle");
        reviewId = str(manifest, "review_id"); scope = str(manifest, "scope");
        if (!reviewId.equals(bundle.get("review_id"))) throw new IOException("Bundle review identity mismatch");
        asOf = time(manifest.get("as_of")); Map<String, Object> period = obj(manifest.get("period"));
        start = time(period.get("start")); end = time(period.get("end"));
        if (!start.isBefore(end) || end.isAfter(asOf)) throw new IOException("Review interval must be nonempty and end by explicit as_of");
        rules = manifest.get("rules") == null ? null : obj(manifest.get("rules"));
        input.hashes.forEach((name, digest) -> { inventory.put(reference("file", name), digest); inventorySizes.put(reference("file", name), (long) input.files.get(name).length); });
        inventory.remove(reference("file", "history.json")); inventorySizes.remove(reference("file", "history.json"));
        codeIdentity = G7Cli.codeHash();
        String declaredBuild = G7Cli.buildCommit();
        buildCommit = declaredBuild.matches("[0-9a-f]{40}|unknown|unknown-dirty") ? declaredBuild : "unknown";
        ledger = new G7Ledger(history, reviewId, scope, input.hashes.get("manifest.json"), asOf);
        byte[] anchor = input.files.get("history.json");
        if (anchor == null) throw new IOException("Immutable dossier history anchor missing");
        if (!Arrays.equals(anchor, history)) {
            G7Ledger anchored = new G7Ledger(anchor, reviewId, scope, input.hashes.get("manifest.json"), asOf);
            if (ledger.events.size() < anchored.events.size() || !ledger.events.subList(0, anchored.events.size()).equals(anchored.events))
                throw new IOException("Selected history must extend the exact immutable dossier history");
        }
        if (rules != null) for (Object value : list(rules.get("finding_severities"))) {
            Map<String, Object> declaration = obj(value); String code = str(declaration, "finding_code");
            if (severityRules.containsKey(code)) throw new IOException("Duplicate severity policy");
            severityRules.put(code, str(declaration, "severity"));
        }
    }
    private void context(String period, Collection<?> evidence, String severity) {
        contextPeriod = period; contextSeverity = severity; contextSeverityApplicable = false; contextArtifacts.clear(); actionArtifacts.clear();
        if (evidence != null) for (Object id : evidence) if (id != null) contextArtifacts.add((String) id);
    }
    void periodContext(Map<String, Object> period, Object artifact) {
        context(str(period, "period_id"), list(period.get("evidence")), null);
        if (artifact != null) contextArtifacts.add((String) artifact);
    }
    private List<Map<String, Object>> evidence(Collection<String> ids) {
        List<Map<String, Object>> refs = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> a = artifacts.get(id);
            refs.add(a == null ? map("artifact_ref", reference("artifact", id), "path_sha256", null, "sha256", null, "size_bytes", null)
                    : map("artifact_ref", reference("artifact", id), "path_sha256", hash(bytes(a.get("path"))), "sha256", a.get("sha256"), "size_bytes", a.get("size_bytes")));
        }
        return refs;
    }
    private Map<String, Object> finding(String status, String code, String field, String action) {
        if (!Set.of("FAIL", "BLOCKED", "INCOMPLETE").contains(status)) throw new IllegalArgumentException("Unsupported finding status");
        if (!code.matches("[A-Z][A-Z0-9_]{0,127}")) throw new IllegalArgumentException("Nonconstant finding code");
        String severity = severityRules.containsKey(code) ? severityRules.get(code) : severityRules.get("ANY");
        if (contextSeverity != null && contextSeverityApplicable) severity = contextSeverity;
        return map("status", status, "code", code, "subject_ref", reference("subject", field), "period_ref", reference("period", contextPeriod),
                "severity", null, "declared_severity_ref", reference("severity", contextSeverity),
                "declared_policy_severity_ref", reference("severity", severity), "severity_authenticated", false,
                "severity_policy_ref", rules == null ? null : reference("artifact", rules.get("policy_review_evidence")),
                "evidence_refs", evidence(contextArtifacts), "review_manifest_sha256", input.hashes.get("manifest.json"),
                "bundle_sha256", input.hashes.get("bundle.json"), "required_action", action, "required_action_refs", evidence(actionArtifacts));
    }
    void add(String status, String code, String field, String action) { findings.add(finding(status, code, field, action)); }
    void missing(String code, String field, String action) { add("INCOMPLETE", code, field, action); }
    void fail(String code, String field, String action) { add("FAIL", code, field, action); }
    void block(String code, String field, String action) { add("BLOCKED", code, field, action); }
    Map<String, Object> artifact(Object value, String field) throws IOException {
        if (value == null) { missing("MISSING_EVIDENCE", field, "Supply exact original evidence and separately authenticate its source."); return null; }
        Map<String, Object> a = artifacts.get((String) value);
        if (a == null) throw new IOException("Unknown artifact reference at " + field);
        return a;
    }
    String artifactHash(Object value, String field) throws IOException {
        Map<String, Object> a = artifact(value, field); return a == null ? null : str(a, "sha256");
    }
    Map<String, Object> artifactJson(Object value, String field, String schema) throws IOException {
        Map<String, Object> a = artifact(value, field);
        if (a == null || invalidArtifacts.contains(str(a, "id")) || !input.files.containsKey(str(a, "path"))) return null;
        if (!"JSON".equals(a.get("format"))) throw new IOException("Adapter input must declare JSON");
        Object parsed = parse(input.files.get(str(a, "path")));
        if (schema != null) G7Schema.validate(schema, parsed);
        if (!(parsed instanceof Map)) throw new IOException("Expected evidence object");
        return obj(parsed);
    }
    Map<String, Object> configuration(Map<String, Object> period) throws IOException {
        return artifactJson(obj(period.get("envelope")).get("CONFIGURATION"), str(period, "period_id") + ".configuration", "configuration");
    }
    void refs(Object values, String field) throws IOException {
        List<Object> a = list(values);
        if (new HashSet<>(a).size() != a.size()) throw new IOException("Duplicate references at " + field);
        for (Object id : a) artifact(id, field);
    }
    private void loadArtifacts() throws IOException {
        Set<String> paths = new HashSet<>(); Set<String> expected = new TreeSet<>(List.of("manifest.json", "bundle.json", "history.json"));
        for (Object value : list(bundle.get("artifacts"))) {
            Map<String, Object> a = obj(value); String id = str(a, "id"), path = G7Files.relative(str(a, "path"));
            if (artifacts.putIfAbsent(id, a) != null || !paths.add(path.toLowerCase(Locale.ROOT)) || !expected.add(path)) throw new IOException("Duplicate artifact or reserved path");
            byte[] b = input.files.get(path);
            context(str(a, "period_id"), List.of(id), null);
            if (b == null) {
                inventory.put(reference("file", path), null); inventorySizes.put(reference("file", path), null);
                missing("ARTIFACT_MISSING", id, "Restore the exact registered bytes; do not substitute or fill values.");
            }
            else {
                if (!hash(b).equals(a.get("sha256")) || integer(a.get("size_bytes")) != b.length) {
                    invalidArtifacts.add(id);
                    fail("ARTIFACT_BYTE_IDENTITY_MISMATCH", id, "Reject mismatched bytes/size; restore the original artifact or append a correction with a new artifact identity.");
                }
                Object content = null;
                if (a.get("format").equals("JSON")) content = parse(b);
                else if (a.get("format").equals("UTF8")) content = utf8(b);
                if (a.get("classification").equals("CLAIMED_OPERATIONAL") && synthetic(content)) fail("SYNTHETIC_MISREPRESENTED", id, "Withdraw the operational claim and retain synthetic classification.");
            }
            if (a.get("observed_at") == null) missing("OBSERVATION_TIME_UNKNOWN", id, "Supply the original evidence timestamp.");
            else if (time(a.get("observed_at")).isAfter(asOf)) fail("FUTURE_EVIDENCE", id, "Correct the declared review time or evidence chronology through a new review.");
            if (a.get("source_identity") == null || a.get("provenance") == null) missing("PROVENANCE_UNKNOWN", id, "Supply source identity and provenance without inventing authentication.");
            missing("SOURCE_UNAUTHENTICATED", id, "Independently establish provenance and source identity; declared authentication is insufficient.");
            observations.add(map("kind", "ARTIFACT", "artifact_ref", reference("artifact", id), "sha256", a.get("sha256"), "size_bytes", a.get("size_bytes"), "classification", a.get("classification"),
                    "declared_authentication", a.get("authentication"), "signature_status", signature(a), "source_authenticated", false));
            if (a.get("classification").equals("SYNTHETIC")) {
                if (manifest.get("mode").equals("CLAIMED_OPERATIONAL")) fail("SYNTHETIC_IN_OPERATIONAL_BUNDLE", id, "Separate synthetic diagnostics from genuine evidence; re-review the claim.");
                else missing("SYNTHETIC_ONLY", id, "Obtain genuine separately authorized operating evidence before any adoption decision.");
            }
        }
        if (!expected.containsAll(input.files.keySet())) throw new IOException("Unregistered files in dossier");
        if (!input.files.containsKey("history.json")) throw new IOException("Dossier history missing");
        context(null, null, null);
    }
    private static boolean synthetic(Object x) {
        if (x instanceof String s) return s.startsWith("# SYNTHETIC") || s.startsWith("SYNTHETIC") || s.contains("classification=SYNTHETIC") || s.contains("classification=REPLAY_DIAGNOSTIC_ONLY");
        if (x instanceof Map<?, ?> m) {
            for (String key : List.of("classification", "mode", "evidence_basis")) if (m.get(key) instanceof String s
                    && (s.startsWith("SYNTHETIC") || s.equals("REPLAY_DIAGNOSTIC_ONLY"))) return true;
            return m.values().stream().anyMatch(G7Review::synthetic);
        }
        if (x instanceof List<?> a) return a.stream().anyMatch(G7Review::synthetic);
        return false;
    }
    private Map<String, Object> envelope(Map<String, Object> source, String field) throws IOException {
        Map<String, Object> hashes = new TreeMap<>();
        for (String domain : G7Schema.DOMAINS) {
            Map<String, Object> a = artifact(source.get(domain), field + "." + domain);
            if (a == null) hashes.put(domain, null);
            else hashes.put(domain, identity(a));
        }
        return hashes;
    }
    static Map<String, Object> identity(Map<String, Object> artifact) {
        return map("artifact_ref", reference("artifact", artifact.get("id")), "path_sha256", hash(bytes(artifact.get("path"))),
                "size_bytes", artifact.get("size_bytes"), "sha256", artifact.get("sha256"));
    }
    private void reviewRules() throws IOException {
        context(null, rules == null ? null : Arrays.asList(rules.get("owner_rules_evidence"), rules.get("policy_review_evidence"), rules.get("escalation_evidence")), null);
        if (rules == null) { missing("OWNER_RULES_ABSENT", "rules", "Owner must preregister cadence, freshness, severity handling, escalation and requalification rules."); return; }
        if (time(rules.get("registered_at")).isAfter(start)) fail("RULES_NOT_PREDECLARED", "rules.registered_at", "Register a new prospective review; retrospective rules cannot qualify this interval.");
        artifact(rules.get("owner_rules_evidence"), "rules.owner_rules_evidence");
        artifact(rules.get("escalation_evidence"), "rules.escalation_evidence");
        artifact(rules.get("policy_review_evidence"), "rules.policy_review_evidence");
        missing("POLICY_REVIEW_UNAUTHENTICATED", "rules", "Authenticate the owner's policy and independent policy review separately; severity labels and procedures remain declarations.");
        if (severityRules.isEmpty()) missing("SEVERITY_POLICY_ABSENT", "rules", "Supply the reviewed finding-to-severity policy; unmapped severity remains null.");
        for (String field : List.of("cadence_seconds", "review_freshness_seconds", "escalation_path")) if (rules.get(field) == null)
            missing("OWNER_RULE_ABSENT", "rules." + field, "Obtain the explicit owner-selected rule; no default is applied.");
        if (rules.get("review_freshness_seconds") != null && Duration.between(end, asOf).compareTo(Duration.ofSeconds(integer(rules.get("review_freshness_seconds")))) > 0)
            block("REVIEW_STALE", "period", "Obtain fresh evidence and a new independent/owner review using the predeclared cadence.");
        Set<String> categories = new HashSet<>(), triggerIds = new HashSet<>();
        for (Object value : list(rules.get("freshness"))) {
            Map<String, Object> f = obj(value); String category = str(f, "category");
            if (!categories.add(category)) throw new IOException("Duplicate freshness rule");
            if (f.get("max_age_seconds") == null) missing("FRESHNESS_RULE_ABSENT", category, "Owner must select evidence freshness; no duration is inferred.");
            else freshness.put(category, integer(f.get("max_age_seconds")));
        }
        for (String c : G7Schema.CATEGORIES) if (!categories.contains(c)) missing("FRESHNESS_RULE_ABSENT", c, "Owner must select evidence freshness for this category.");
        for (Object value : list(rules.get("triggers"))) {
            Map<String, Object> t = obj(value);
            if (!triggerIds.add(str(t, "trigger_id"))) throw new IOException("Duplicate trigger identity");
            if (new HashSet<>(list(t.get("severities"))).size() != list(t.get("severities")).size()
                    || new HashSet<>(list(t.get("requalification"))).size() != list(t.get("requalification")).size()) throw new IOException("Duplicate trigger member");
            if (list(t.get("required_actions")).isEmpty()) missing("TRIGGER_ACTIONS_ABSENT", str(t, "trigger_id"), "Specify exact human actions and escalation.");
            refs(t.get("required_actions"), "rules.trigger_actions");
            if (t.get("stop_required") == null) missing("STOP_RULE_UNKNOWN", str(t, "trigger_id"), "Owner must explicitly select stop handling; unknown is never false.");
            if (t.get("kind").equals("CHANGE") && (!G7Schema.DOMAINS.contains(t.get("scope")) || list(t.get("requalification")).isEmpty()))
                missing("REQUALIFICATION_PATH_ABSENT", str(t, "trigger_id"), "Predeclare the earlier-gate review path for this identity change.");
            triggers.add(t);
        }
        for (String domain : G7Schema.DOMAINS) if (triggers.stream().noneMatch(t -> t.get("kind").equals("CHANGE") && t.get("scope").equals(domain)))
            missing("CHANGE_RULE_ABSENT", domain, "Owner must predeclare the material-change requalification path.");
        for (String kind : List.of("INCIDENT", "EXCEPTION")) if (triggers.stream().noneMatch(t -> t.get("kind").equals(kind)))
            missing("REVIEW_TRIGGER_ABSENT", kind, "Owner must define severity labels, stop requirements and independent review triggers.");
        context(null, null, null);
    }
    List<Map<String, Object>> trigger(String kind, String scope, String severity, String field) {
        List<Map<String, Object>> matched = triggers.stream().filter(t -> t.get("kind").equals(kind)
                && (t.get("scope").equals(scope) || t.get("scope").equals("ANY"))
                && (kind.equals("CHANGE") || list(t.get("severities")).contains(severity))).toList();
        contextSeverityApplicable = !kind.equals("CHANGE") && severity != null && !matched.isEmpty();
        if (matched.isEmpty()) missing("TRIGGER_UNDEFINED", field, "Owner must define handling; severity and action are unknown.");
        for (Map<String, Object> t : matched) {
            for (Object action : list(t.get("required_actions"))) actionArtifacts.add((String) action);
            if (!list(t.get("required_actions")).isEmpty()) block("HUMAN_ACTION_REQUIRED", field, "Follow the exact action evidence and escalation path selected by the declared policy; obtain independent human review.");
            if (Boolean.TRUE.equals(t.get("stop_required"))) block("HUMAN_STOP_REQUIRED", field, "Human must use the separately controlled stop procedure; this tool takes no control action.");
            if (!list(t.get("requalification")).isEmpty()) block("REQUALIFICATION_REQUIRED", field, "Return to " + String.join(",", list(t.get("requalification")).stream().map(Object::toString).toList()) + " for the predeclared re-review; G7 cannot repair earlier gates.");
        }
        return matched;
    }
    private void gates(Map<String, Object> approved) throws IOException {
        Set<String> seen = new HashSet<>(); String envelopeHash = hash(bytes(approved));
        for (Object value : list(manifest.get("gates"))) {
            Map<String, Object> g = obj(value); String gate = str(g, "gate");
            if (!seen.add(gate)) throw new IOException("Duplicate gate decision");
            context(null, list(g.get("artifacts")), null);
            if (g.get("artifact") != null) contextArtifacts.add(str(g, "artifact"));
            refs(g.get("artifacts"), gate);
            Map<String, Object> a = artifact(g.get("artifact"), gate);
            if (!g.get("status").equals("APPROVED") || g.get("decision_id") == null || a == null || invalidArtifacts.contains(str(a, "id"))
                    || !input.files.containsKey(str(a, "path"))) block("GATE_APPROVAL_ABSENT", gate, "Obtain the genuine complete earlier-gate approval; do not substitute G7 evidence.");
            if (!Boolean.TRUE.equals(g.get("complete")) || list(g.get("artifacts")).isEmpty()) block("GATE_EVIDENCE_INCOMPLETE", gate, "Supply exact original decision/artifact identities and establish full earlier-gate coverage; omitted evidence remains unknown.");
            if (g.get("approved_at") == null || g.get("expires") == null) block("GATE_VALIDITY_UNKNOWN", gate, "Obtain explicit approval and expiry times.");
            else if (time(g.get("approved_at")).isAfter(start) || !time(g.get("expires")).isAfter(asOf)
                    || !time(g.get("expires")).isAfter(time(g.get("approved_at")))) block("GATE_EXPIRED_OR_LATE", gate, "Requalify the earlier gate before the proposed scope can be considered.");
            if (!scope.equals(g.get("scope")) || !envelopeHash.equals(g.get("envelope_sha256"))) block("GATE_ENVELOPE_MISMATCH", gate, "Obtain an approval bound to these exact identities and scope.");
            if (a != null && !invalidArtifacts.contains(str(a, "id")) && input.files.containsKey(str(a, "path"))) {
                Map<String, Object> decision = artifactJson(g.get("artifact"), gate, "gate-decision");
                for (String key : List.of("gate", "decision_id", "status", "scope", "approved_at", "expires", "envelope_sha256", "complete"))
                    if (!Objects.equals(g.get(key), decision.get(key))) block("GATE_DECISION_MISMATCH", gate, "Reconcile the manifest against the exact original gate-decision declaration; caller metadata cannot replace it.");
                Map<String, Object> expectedHashes = new TreeMap<>();
                for (Object id : list(g.get("artifacts"))) expectedHashes.put((String) id, artifactHash(id, gate));
                if (!expectedHashes.equals(decision.get("source_artifact_hashes"))) block("GATE_ARTIFACT_IDENTITIES_MISMATCH", gate, "Bind every exact original gate artifact, including its path, size and bytes in the inventory.");
                for (String key : List.of("identity_evidence", "authority_evidence", "qualification_evidence", "independence_evidence")) roleEvidence(decision.get(key), gate + "." + key);
                if (decision.get("signer_id") == null) block("GATE_SIGNER_IDENTITY_ABSENT", gate, "Establish the genuine original human decision maker's identity and authority.");
                if (!decision.get("signer_kind").equals("HUMAN")) block("GATE_NONHUMAN_OR_UNKNOWN_DECISION", gate, "AI reviews and unknown signers cannot qualify an earlier project gate.");
                signature(decision);
            }
            block("GATE_AUTHENTICATION_UNESTABLISHED", gate, "Independently authenticate original gate decision, signers and exact artifact hash; this baseline has no trusted approval adapter.");
        }
        context(null, null, null);
        for (String gate : G7Schema.GATES) if (!seen.contains(gate)) block("GATE_APPROVAL_ABSENT", gate, "Supply the exact earlier-gate decision and artifact bytes; no gate is optional in this proposal's operational-envelope review.");
    }
    private void coverage(Map<String, Object> approved) throws IOException {
        Instant previousEnd = start;
        for (Object value : list(bundle.get("schedule"))) {
            Map<String, Object> s = obj(value); String id = str(s, "period_id"); Instant a = time(s.get("start")), b = time(s.get("end"));
            context(id, null, null);
            if (schedule.putIfAbsent(id, s) != null) throw new IOException("Duplicate scheduled period");
            if (!a.isBefore(b) || a.isBefore(start) || b.isAfter(end) || a.isBefore(previousEnd)) throw new IOException("Invalid/reordered/overlapping schedule");
            if (!a.equals(previousEnd)) missing("SCHEDULE_GAP", id, "Register every review interval explicitly, including non-operating intervals with reasons.");
            if (rules != null && rules.get("cadence_seconds") != null && Duration.between(a, b).compareTo(Duration.ofSeconds(integer(rules.get("cadence_seconds")))) > 0)
                missing("CADENCE_COVERAGE_EXCEEDED", id, "Split evidence into the owner-selected monitoring periods; coarse coverage is incomplete.");
            previousEnd = b;
            if (s.get("status").equals("EXCLUDED")) {
                if (s.get("reason") == null) fail("EXCLUSION_REASON_MISSING", id, "Record why the period is missing or excluded.");
                missing("PERIOD_EXCLUDED", id, "Retain exclusion; excluded operating evidence cannot count as healthy coverage.");
            }
        }
        if (schedule.isEmpty() || !previousEnd.equals(end)) missing("SCHEDULE_INCOMPLETE", "schedule", "Register a complete explicit partition of the review interval.");
        Set<String> exceptionIds = new HashSet<>();
        for (Object value : list(bundle.get("periods"))) {
            Map<String, Object> p = obj(value); String id = str(p, "period_id");
            periodContext(p, null);
            if (periods.putIfAbsent(id, p) != null) throw new IOException("Duplicate observed period");
            Map<String, Object> s = schedule.get(id); if (s == null) throw new IOException("Unscheduled observed period");
            refs(p.get("evidence"), id);
            if (p.get("status").equals("MISSING")) {
                if (p.get("reason") == null || p.get("observed_start") != null || p.get("observed_end") != null) fail("INVALID_MISSING_PERIOD", id, "Record an explicit reason and null observations for missing periods.");
                if (!list(p.get("evidence")).isEmpty() || G7Schema.DOMAINS.stream().anyMatch(d -> obj(p.get("envelope")).get(d) != null)
                        || List.of("daily_ops", "feed_audit", "feed_scope_ids", "portfolio_run", "portfolio_inputs", "portfolio_settings", "risk_audit", "risk_inputs", "settlement_audit", "settlement_events", "settlement_costs", "reconciliation")
                        .stream().anyMatch(k -> p.get(k) != null) || !p.get("settlement_costs_mode").equals("UNKNOWN"))
                    fail("MISSING_PERIOD_CONTAINS_OBSERVATIONS", id, "Retain partial observations in an OBSERVED record with incomplete coverage; a MISSING record must keep observations null.");
                missing("PERIOD_MISSING", id, "Recover original evidence or retain incomplete coverage; never infer a healthy period.");
            } else {
                if (p.get("observed_start") == null || p.get("observed_end") == null) missing("OBSERVED_WINDOW_UNKNOWN", id, "Supply exact observed coverage boundaries.");
                else if (!time(p.get("observed_start")).isBefore(time(p.get("observed_end"))) || time(p.get("observed_start")).isBefore(time(s.get("start")))
                        || time(p.get("observed_end")).isAfter(time(s.get("end")))) fail("OBSERVED_WINDOW_INVALID", id, "Correct invalid or out-of-period boundaries through a new immutable observation record.");
                else if (!p.get("observed_start").equals(s.get("start")) || !p.get("observed_end").equals(s.get("end"))) missing("PARTIAL_PERIOD_COVERAGE", id, "Obtain full scheduled coverage; partial observations remain incomplete.");
                Map<String, Object> observed = envelope(obj(p.get("envelope")), id);
                for (String domain : G7Schema.DOMAINS) if (approved.get(domain) == null || observed.get(domain) == null) {
                    missing("ENVELOPE_IDENTITY_UNKNOWN", id + "." + domain, "Obtain exact approved and observed identities for this domain; unknown cannot establish equality.");
                } else if (!Objects.equals(approved.get(domain), observed.get(domain))) {
                    block("ENVELOPE_DRIFT", id + "." + domain, "Stop and escalate through the declared change procedure; a new approval is required.");
                    trigger("CHANGE", domain, null, id + "." + domain);
                    boolean logged = list(bundle.get("changes")).stream().map(G7Json::obj).anyMatch(c -> c.get("scope").equals(domain)
                            && Objects.equals(c.get("period_id"), id) && Objects.equals(c.get("before"), obj(manifest.get("approved_envelope")).get(domain))
                            && Objects.equals(c.get("after"), obj(p.get("envelope")).get(domain)));
                    if (!logged) fail("UNLOGGED_DRIFT", id + "." + domain, "Append a change event retaining previous identities and approvals.");
                }
                for (String category : G7Schema.CATEGORIES) {
                    List<Map<String, Object>> evidence = list(p.get("evidence")).stream().map(x -> artifacts.get((String) x)).filter(a -> a.get("category").equals(category)).toList();
                    if (evidence.isEmpty()) missing("CATEGORY_EVIDENCE_MISSING", id + "." + category, "Supply original category evidence; explicit zero requires a complete source record.");
                    for (Map<String, Object> a : evidence) {
                        contextArtifacts.add(str(a, "id"));
                        if (Set.of("ESTIMATE", "CAPACITY_CLAIM").contains(a.get("classification"))) fail("ESTIMATE_USED_AS_OBSERVATION", str(a, "id"), "Keep estimates and capacity claims separate from operating observations.");
                        if (a.get("period_id") == null) missing("ARTIFACT_PERIOD_UNKNOWN", id + "." + str(a, "id"), "Bind original evidence to the exact scheduled period; unrelated or omitted coverage cannot count.");
                        else if (!id.equals(a.get("period_id"))) fail("ARTIFACT_PERIOD_MISMATCH", id + "." + str(a, "id"), "Supply the artifact for this exact period and preserve the unrelated original.");
                        if (!Boolean.TRUE.equals(a.get("complete"))) missing("ARTIFACT_COVERAGE_UNKNOWN", id + "." + str(a, "id"), "Establish complete original coverage; null/false completeness is not zero activity.");
                        if (a.get("coverage") == null) missing("ARTIFACT_WINDOW_UNKNOWN", id + "." + str(a, "id"), "Supply original coverage boundaries; no window is inferred.");
                        else if (!obj(a.get("coverage")).get("start").equals(s.get("start")) || !obj(a.get("coverage")).get("end").equals(s.get("end")))
                            missing("ARTIFACT_PARTIAL_COVERAGE", id + "." + str(a, "id"), "Retain the declared partial window and obtain full scheduled-period evidence.");
                        if (a.get("observed_at") != null && freshness.containsKey(category)
                                && Duration.between(time(a.get("observed_at")), asOf).compareTo(Duration.ofSeconds(freshness.get(category))) > 0)
                            missing("STALE_EVIDENCE", id + "." + str(a, "id"), "Replace stale evidence through a new append-only review using the owner's freshness rule.");
                    }
                }
                G7Adapters.check(this, p, s);
            }
            for (Object item : list(p.get("exceptions"))) {
                Map<String, Object> e = obj(item); String exception = str(e, "exception_id");
                context(id, list(e.get("evidence")), str(e, "severity"));
                if (!exceptionIds.add(exception)) throw new IOException("Duplicate exception identity");
                refs(e.get("evidence"), exception);
                if (list(e.get("evidence")).isEmpty()) missing("EXCEPTION_EVIDENCE_ABSENT", exception, "Attach the original exception evidence.");
                trigger("EXCEPTION", str(e, "category"), str(e, "severity"), exception);
                fail("OPERATING_EXCEPTION", exception, "Escalate the recorded operating exception and obtain independent disposition under the exact declared policy.");
                Map<String, Object> incident = ledger.byId.get(str(e, "incident_event_id"));
                if (incident == null || !incident.get("kind").equals("INCIDENT") || !Objects.equals(incident.get("period_id"), id))
                    fail("EXCEPTION_INCIDENT_MISSING", exception, "Append the linked incident for this period.");
            }
        }
        for (String id : schedule.keySet()) if (!periods.containsKey(id)) { context(id, null, null); missing("PERIOD_UNACCOUNTED", id, "Add an observed or explicit MISSING period record."); }
        context(null, null, null);
    }
    private void changesAndIncidents() throws IOException {
        Map<String, Object> approved = obj(manifest.get("approved_envelope")); Set<String> changes = new HashSet<>(), changeEvents = new HashSet<>();
        for (Object value : list(bundle.get("changes"))) {
            Map<String, Object> c = obj(value); String id = str(c, "change_id"), domain = str(c, "scope");
            List<Object> referenced = new ArrayList<>(list(c.get("approvals"))); referenced.add(c.get("before")); referenced.add(c.get("after"));
            context(str(c, "period_id"), referenced, null);
            if (!changes.add(id) || !changeEvents.add(str(c, "history_event_id"))) throw new IOException("Duplicate change identity/event");
            refs(c.get("approvals"), id); artifact(c.get("before"), id); artifact(c.get("after"), id);
            if (!Objects.equals(c.get("before"), approved.get(domain))) fail("CHANGE_BASELINE_MISMATCH", id, "Bind the change to the authorized baseline.");
            if (time(c.get("at")).isAfter(asOf)) fail("FUTURE_CHANGE", id, "Correct change chronology in a new review.");
            Map<String, Object> e = ledger.byId.get(str(c, "history_event_id"));
            if (c.get("period_id") == null || !schedule.containsKey(str(c, "period_id"))) missing("CHANGE_PERIOD_UNKNOWN", id, "Bind the material change to its exact scheduled period.");
            if (Objects.equals(c.get("before"), c.get("after"))) fail("CHANGE_HAS_NO_NEW_IDENTITY", id, "Preserve a distinct immutable after identity for a material change.");
            if (e == null || !"CHANGE".equals(ledger.logicalKinds.get(str(c, "history_event_id"))) || !e.get("scope").equals(domain)
                    || !e.get("occurred_at").equals(c.get("at")) || !e.get("subject_sha256").equals(hash(bytes(c)))) fail("CHANGE_HISTORY_MISMATCH", id, "Append an event bound to the exact change bytes.");
            block("MATERIAL_CHANGE", id, "Keep this review blocked until the predeclared earlier-gate requalification is separately completed.");
            trigger("CHANGE", domain, null, id);
            if (list(c.get("approvals")).isEmpty()) missing("CHANGE_APPROVAL_MISSING", id, "Obtain a separately authenticated change approval; it cannot amend this frozen envelope.");
        }
        Set<String> registeredArtifacts = new HashSet<>(), registeredPeriods = new HashSet<>(), periodRecords = new HashSet<>();
        List<Map<String, Object>> evidenceEvents = ledger.events.subList(0, ledger.decisionStart);
        for (Map<String, Object> e : evidenceEvents) {
            String id = str(e, "event_id"), kind = str(e, "kind"), logical = ledger.logicalKinds.get(id);
            List<Object> referenced = new ArrayList<>(list(e.get("evidence"))); referenced.addAll(list(e.get("resolution_evidence")));
            context(str(e, "period_id"), referenced, str(e, "severity")); refs(e.get("evidence"), id); refs(e.get("resolution_evidence"), id);
            if (e.get("period_id") != null && !schedule.containsKey(str(e, "period_id"))) throw new IOException("Event references an unscheduled period");
            if (kind.equals("REGISTRATION") && time(e.get("recorded_at")).isAfter(start)) fail("REGISTRATION_LATE", id, "Register a new prospective review; declared times are not trusted timestamps.");
            if (kind.equals("PERIOD")) {
                String p = str(e, "period_id");
                if (p == null || !registeredPeriods.add(p) || !hash(bytes(schedule.get(p))).equals(e.get("subject_sha256"))) throw new IOException("Period registration mismatch/duplicate");
                if (time(e.get("recorded_at")).isAfter(time(schedule.get(p).get("start")))) fail("PERIOD_REGISTERED_LATE", p, "Register monitoring periods prospectively; do not backfill the calendar.");
            }
            if (logical.equals("EVIDENCE")) {
                List<Object> a = list(e.get("evidence"));
                if (a.size() != 1 || !registeredArtifacts.add((String) a.getFirst()) || !Objects.equals(artifacts.get((String) a.getFirst()), e.get("artifact_record"))) throw new IOException("Evidence registration mismatch/duplicate");
                Map<String, Object> artifact = artifacts.get((String) a.getFirst());
                if (artifact.get("observed_at") != null && time(artifact.get("observed_at")).isAfter(time(e.get("recorded_at")))) fail("EVIDENCE_REGISTERED_BEFORE_OBSERVED", id, "Correct declared custody chronology without overwriting evidence.");
            }
            if (logical.equals("PERIOD_EVIDENCE") && !ledger.superseded.contains(id)) {
                String p = str(e, "period_id");
                if (!periodRecords.add(p)) throw new IOException("Duplicate active period record; append an explicit correction");
                if (!Objects.equals(periods.get(p), e.get("period_record"))) fail("PERIOD_RECORD_HISTORY_MISMATCH", p, "Append a correction retaining the previous immutable period record; observations cannot be edited in place.");
            }
            if (logical.equals("INCIDENT") || Set.of("STOP", "RECOVERY").contains(kind)) {
                if (list(e.get("evidence")).isEmpty() || e.get("response") == null) missing("EVENT_EVIDENCE_OR_RESPONSE_MISSING", id, "Attach original event evidence and the operator response.");
                else if (!Objects.equals(artifactHash(list(e.get("evidence")).getFirst(), id), e.get("subject_sha256"))) throw new IOException("Event subject is not its first evidence artifact");
            }
            if (logical.equals("CHANGE") && !changeEvents.contains(id) && !ledger.superseded.contains(id)) fail("CHANGE_RECORD_MISSING", id, "Retain the matching active change record in the period bundle.");
            if (logical.equals("INCIDENT")) {
                var matched = trigger("INCIDENT", str(e, "scope"), str(e, "severity"), id);
                if (e.get("severity") == null) missing("INCIDENT_SEVERITY_UNKNOWN", id, "Owner must classify this incident under predeclared rules.");
                if (e.get("stop_status").equals("UNKNOWN")) missing("INCIDENT_STOP_STATUS_UNKNOWN", id, "Obtain the original stop requirement and action evidence; unknown cannot mean no stop was needed.");
                boolean stop = matched.stream().anyMatch(t -> Boolean.TRUE.equals(t.get("stop_required"))) || e.get("stop_status").equals("REQUIRED");
                List<Map<String, Object>> stops = evidenceEvents.stream().filter(x -> x.get("kind").equals("STOP") && id.equals(x.get("prior_event_id"))).toList();
                if (stop && stops.stream().noneMatch(x -> x.get("stop_status").equals("RECORDED") && !list(x.get("evidence")).isEmpty()))
                    fail("REQUIRED_STOP_ABSENT", id, "Human must record the separately controlled stop action and preserve its evidence.");
                for (Map<String, Object> t : matched) if (!list(e.get("required_rereview")).containsAll(list(t.get("requalification"))))
                    fail("INCIDENT_REREVIEW_PATH_MISMATCH", id, "Retain every earlier gate required by the owner's trigger.");
                boolean recovery = evidenceEvents.stream().anyMatch(x -> x.get("kind").equals("RECOVERY") && id.equals(x.get("prior_event_id")));
                block(recovery ? "RECOVERY_REVIEW_UNAUTHENTICATED" : "INCIDENT_UNRESOLVED", id,
                        "Require qualified independent incident/recovery review and human disposition. No incident is auto-closed and no halt is released.");
            }
            if (kind.equals("RECOVERY")) {
                if (list(e.get("resolution_evidence")).isEmpty()) fail("INVALID_INCIDENT_CLOSURE", id, "Supply resolution evidence and independent recovery review; an assertion is insufficient.");
                else missing("RECOVERY_REVIEW_REQUIRED", id, "Authenticate independent recovery review and all follow-ups; recovery evidence is not permission to resume.");
                if (!e.get("stop_status").equals("RECORDED")) fail("RECOVERY_STOP_UNCERTAIN", id, "Retain the halt pending separately controlled human review.");
            }
            if (kind.equals("CORRECTION") || kind.equals("SUPERSESSION")) observations.add(map("kind", "APPENDED_CORRECTION", "event_ref", reference("event", id),
                    "prior_event_sha256", e.get("supersedes_sha256"), "subject_sha256", e.get("subject_sha256"), "clears_prior_findings", false));
        }
        context(null, null, null);
        if (evidenceEvents.isEmpty() || !evidenceEvents.getLast().get("kind").equals("BUNDLE")
                || !evidenceEvents.getLast().get("subject_sha256").equals(input.hashes.get("bundle.json")))
            missing("BUNDLE_NOT_FINALIZED", "bundle", "Append a final BUNDLE event bound to the exact inventory/period/change bytes before any human decision.");
        for (String id : artifacts.keySet()) if (!registeredArtifacts.contains(id)) { context(str(artifacts.get(id), "period_id"), List.of(id), null); missing("ARTIFACT_NOT_REGISTERED", id, "Append evidence registration bound to its exact path, size, hash and metadata."); }
        for (String id : schedule.keySet()) {
            context(id, null, null);
            if (!registeredPeriods.contains(id)) missing("PERIOD_NOT_REGISTERED", id, "Append the original prospective period registration; missing custody remains unknown.");
            if (!periodRecords.contains(id)) missing("PERIOD_EVIDENCE_NOT_REGISTERED", id, "Append the exact observed or explicit MISSING period record; retain superseded records in history.");
        }
        context(null, null, null);
    }
    private void roleEvidence(Object id, String field) throws IOException {
        Map<String, Object> a = artifact(id, field);
        if (a == null) return;
        if (rules == null || rules.get("review_freshness_seconds") == null || a.get("observed_at") == null)
            missing("ROLE_EVIDENCE_FRESHNESS_UNKNOWN", field, "Supply original role evidence and an explicit owner review lifetime; no freshness duration is inferred.");
        else if (Duration.between(time(a.get("observed_at")), asOf).compareTo(Duration.ofSeconds(integer(rules.get("review_freshness_seconds")))) > 0)
            missing("ROLE_EVIDENCE_STALE", field, "Obtain current identity, authority, qualification or independence evidence under the declared review lifetime; freshness does not authenticate a person.");
    }
    private void roles() throws IOException {
        Map<String, Object> owner = obj(manifest.get("owner")), reviewer = obj(manifest.get("reviewer"));
        context(null, Arrays.asList(owner.get("authority_evidence"), owner.get("identity_evidence"), reviewer.get("identity_evidence"), reviewer.get("qualification_evidence"), reviewer.get("independence_evidence")), null);
        roleEvidence(owner.get("authority_evidence"), "owner.authority"); roleEvidence(reviewer.get("qualification_evidence"), "reviewer.qualifications"); roleEvidence(reviewer.get("independence_evidence"), "reviewer.independence");
        roleEvidence(owner.get("identity_evidence"), "owner.identity"); roleEvidence(reviewer.get("identity_evidence"), "reviewer.identity");
        if (owner.get("id") == null || reviewer.get("id") == null || owner.get("kind").equals("UNKNOWN") || reviewer.get("kind").equals("UNKNOWN"))
            missing("HUMAN_IDENTITY_UNKNOWN", "reviewer", "Obtain genuine owner and reviewer identity/authority evidence; absent identity remains unknown.");
        if (owner.get("id") != null && owner.get("id").equals(reviewer.get("id")) || reviewer.get("kind").equals("AI") || owner.get("kind").equals("AI")) fail("SELF_OR_AI_REVIEW", "reviewer", "Obtain a distinct qualified independent human reviewer and human owner.");
        if (!scope.equals(reviewer.get("scope"))) block("REVIEWER_SCOPE_MISMATCH", "reviewer", "Obtain independent review for the exact scope.");
        missing("REVIEWER_QUALIFICATIONS_UNAUTHENTICATED", "reviewer", "Independently establish qualifications, scope and independence; self-supplied documents/signatures do not authenticate them.");
        missing("OWNER_AUTHORITY_UNAUTHENTICATED", "owner", "Authenticate owner identity and authority outside this diagnostic tool.");
        context(null, null, null);
    }
    private List<Map<String, Object>> pendingDecisions() {
        return List.of(finding("INCOMPLETE", "HUMAN_DECISIONS_PENDING", "review",
                "Obtain independent-review and owner decisions on the exact reviewed hashes, report and unexpired scope."));
    }
    private void decisions(byte[] reviewable, Map<String, String> reviewedHashes) throws IOException {
        Map<String, Object> reviewer = obj(manifest.get("reviewer")), owner = obj(manifest.get("owner"));
        Map<String, Object> reviewEvent = null; boolean ownerSeen = false; int reviewerCount = 0, ownerCount = 0;
        for (int i = ledger.decisionStart; i < ledger.events.size(); i++) {
            Map<String, Object> e = ledger.events.get(i);
            context(null, list(e.get("evidence")), null);
            if (e.get("kind").equals("REVOCATION")) {
                refs(e.get("evidence"), "revocation");
                block("REVIEW_REVOKED", str(e, "event_id"), "Stop relying on the exact revoked review/decision; retain the terminal revocation and obtain a new review identity.");
                continue;
            }
            Map<String, Object> d = obj(e.get("decision")); String id = str(d, "decision_id");
            boolean independent = e.get("kind").equals("REVIEWER_DECISION");
            contextArtifacts.addAll(java.util.stream.Stream.of(d.get("qualification_evidence"), d.get("independence_evidence"), d.get("authority_evidence"), d.get("identity_evidence"))
                    .filter(Objects::nonNull).map(Object::toString).toList());
            if (independent) { reviewerCount++; if (ownerSeen) fail("REVIEW_DECISION_ORDER", id, "A new independent review cannot overwrite a prior owner decision."); }
            else { ownerCount++; ownerSeen = true; }
            if (!d.get("role").equals(independent ? "INDEPENDENT_REVIEWER" : "OWNER")
                    || !d.get("actor_id").equals((independent ? reviewer : owner).get("id")) || !d.get("actor_kind").equals("HUMAN")) fail("DECISION_IDENTITY_MISMATCH", id, "Obtain the designated human's genuine decision.");
            if (!scope.equals(d.get("scope")) || !ledger.head(ledger.decisionStart).equals(d.get("reviewed_head"))
                    || !reviewedHashes.equals(d.get("reviewed_hashes")) || !hash(reviewable).equals(d.get("report_sha256"))) fail("DECISION_REVIEWED_BYTES_MISMATCH", id, "Re-review the exact current evidence, pre-decision history and report; old decisions cannot authorize changed bytes.");
            if (!time(d.get("expires")).isAfter(asOf) || !time(d.get("expires")).isAfter(time(d.get("at")))) block("DECISION_EXPIRED", id, "Obtain a fresh independent and owner review; do not extend expiry automatically.");
            if (rules != null && rules.get("review_freshness_seconds") != null
                    && Duration.between(time(d.get("at")), time(d.get("expires"))).compareTo(Duration.ofSeconds(integer(rules.get("review_freshness_seconds")))) > 0)
                block("DECISION_EXPIRY_EXCEEDS_POLICY", id, "Use the exact owner-defined review freshness; a caller cannot extend decision life beyond the reviewed rule.");
            if (time(d.get("at")).isBefore(end)) fail("DECISION_BEFORE_PERIOD_END", id, "Complete the operating evidence before retrospective review.");
            if (independent) {
                if (!Objects.equals(d.get("qualification_evidence"), reviewer.get("qualification_evidence"))
                        || !Objects.equals(d.get("independence_evidence"), reviewer.get("independence_evidence")) || d.get("reviewer_decision_sha256") != null)
                    fail("REVIEWER_EVIDENCE_MISMATCH", id, "Bind the exact reviewer qualification and independence records.");
                reviewEvent = e;
            } else if (reviewEvent == null || !hash(bytes(reviewEvent)).equals(d.get("reviewer_decision_sha256"))) fail("OWNER_REVIEW_BINDING_MISMATCH", id, "Owner must review the exact preceding independent decision.");
            artifact(d.get("qualification_evidence"), id); artifact(d.get("independence_evidence"), id);
            artifact(d.get("identity_evidence"), id);
            if (!Objects.equals(d.get("identity_evidence"), (independent ? reviewer : owner).get("identity_evidence"))
                    || !Objects.equals(d.get("authority_evidence"), independent ? null : owner.get("authority_evidence")))
                fail("DECISION_IDENTITY_EVIDENCE_MISMATCH", id, "Bind the exact designated human's identity and owner authority records.");
            if (!independent) artifact(d.get("authority_evidence"), id);
            if (!d.get("outcome").equals("APPROVE") || !list(d.get("reservations")).isEmpty()) block("DECISION_RESERVATION_OR_REJECTION", id, "Retain all reservations/rejection and obtain separately reviewed disposition.");
            missing("DECISION_UNAUTHENTICATED", id, "Authenticate identity, authority, independence and signing time outside this offline tool.");
            observations.add(map("kind", "HUMAN_DECISION", "decision_ref", reference("decision", id), "outcome", d.get("outcome"),
                    "reservation_sha256", list(d.get("reservations")).stream().map(x -> hash(bytes(x))).toList(),
                    "signature_status", signature(d), "identity_authenticated", false, "reviewed_report_sha256", d.get("report_sha256")));
        }
        if (reviewerCount > 1 || ownerCount > 1) fail("DUPLICATE_ROLE_DECISION", "history", "Use a new review identity instead of replacing a role decision.");
        if (reviewerCount == 0 || ownerCount == 0) findings.addAll(pendingDecisions());
        context(null, null, null);
    }
    Map<String, Object> evaluate() throws IOException {
        if (evaluated) throw new IOException("Review instances are single-use immutable reductions");
        evaluated = true;
        loadArtifacts(); roles(); reviewRules();
        block("AUTHENTICATION_ADAPTERS_UNAVAILABLE", "review", "Provision and independently qualify genuine G0-G6/source/owner/reviewer identity authentication before any future eligibility implementation; this proposal cannot establish it.");
        if (!buildCommit.matches("[0-9a-f]{40}")) missing("BUILD_COMMIT_UNKNOWN", "build", "Use a clean commit-identified build for review; this diagnostic build cannot claim a tested production revision.");
        Map<String, Object> approved = envelope(obj(manifest.get("approved_envelope")), "approved_envelope");
        gates(approved); coverage(approved); changesAndIncidents();
        refs(bundle.get("statistical_estimates"), "statistical_estimates"); refs(bundle.get("capacity_claims"), "capacity_claims");
        for (String field : List.of("statistical_estimates", "capacity_claims")) {
            String classification = field.equals("statistical_estimates") ? "ESTIMATE" : "CAPACITY_CLAIM";
            for (Object id : list(bundle.get(field))) if (!artifacts.get((String) id).get("classification").equals(classification))
                fail("CLAIM_CLASSIFICATION_MISMATCH", field, "Keep statistical estimates and capacity claims explicitly classified and separate from operating observations.");
            observations.add(map("kind", field, "artifact_refs", list(bundle.get(field)).stream().map(x -> reference("artifact", x)).toList(), "used_for_authorization", false, "methods_changed", false));
        }
        // The decision target is the deterministic pre-decision report; no circular digest is needed.
        List<Map<String, Object>> base = new ArrayList<>(findings); base.addAll(pendingDecisions());
        byte[] prefix = ledger.prefix(ledger.decisionStart);
        Map<String, String> reviewedHashes = new TreeMap<>(inventory); reviewedHashes.put(reference("file", "history.json"), hash(prefix));
        Map<String, Object> reviewedSizes = new TreeMap<>(inventorySizes); reviewedSizes.put(reference("file", "history.json"), (long) prefix.length);
        Map<String, Object> reviewable = report(base, new ArrayList<>(observations), reviewedHashes, reviewedSizes, ledger.head(ledger.decisionStart), null);
        if (ledger.decisionStart == ledger.events.size()) return reviewable;
        decisions(bytes(reviewable), reviewedHashes);
        Map<String, String> currentHashes = new TreeMap<>(inventory); currentHashes.put(reference("file", "history.json"), hash(bytes(ledger.document)));
        Map<String, Object> currentSizes = new TreeMap<>(inventorySizes); currentSizes.put(reference("file", "history.json"), (long) bytes(ledger.document).length);
        return report(findings, observations, currentHashes, currentSizes, ledger.head, hash(bytes(reviewable)));
    }
    private Map<String, Object> report(List<Map<String, Object>> findings, List<Map<String, Object>> observations, Map<String, String> hashes, Map<String, Object> sizes, String head, String decisionTarget) throws IOException {
        findings = findings.stream().distinct().sorted(Comparator.comparing(f -> new String(bytes(f), java.nio.charset.StandardCharsets.UTF_8))).toList();
        observations = observations.stream().sorted(Comparator.comparing(o -> new String(bytes(o), java.nio.charset.StandardCharsets.UTF_8))).toList();
        String status = findings.stream().anyMatch(f -> f.get("status").equals("FAIL")) ? "FAIL"
                : findings.stream().anyMatch(f -> f.get("status").equals("BLOCKED")) ? "BLOCKED"
                : "INCOMPLETE";
        return map("schema", "g7-report/v1", "review_id", reference("review", reviewId), "scope_sha256", hash(bytes(scope)), "as_of", manifest.get("as_of"),
                "period", manifest.get("period"), "status", status, "classification", "OFFLINE_PROPOSED_G7_DIAGNOSTIC",
                "g7_proposed", true, "g7_closed", false, "operation_authorized", false, "scale_authorized", false,
                "performance_claim_eligible", false, "source_authenticated", false, "limits", LIMITS,
                "build_commit", buildCommit, "code_sha256", codeIdentity, "input_hashes", hashes, "input_sizes", sizes,
                "dossier_history_sha256", input.hashes.get("history.json"), "history_head", head, "decision_target_sha256", decisionTarget,
                "findings", findings, "observations", observations);
    }
    public static int exitCode(Map<String, Object> report) {
        return switch (str(report, "status")) { case "ELIGIBLE_FOR_HUMAN_REVIEW" -> 0; case "FAIL" -> 1; case "INCOMPLETE" -> 2; default -> 3; };
    }
}
