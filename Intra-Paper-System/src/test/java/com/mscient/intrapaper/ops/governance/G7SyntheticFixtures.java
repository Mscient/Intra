package com.mscient.intrapaper.ops.governance;

import static com.mscient.intrapaper.ops.governance.G7Json.*;
import com.mscient.intrapaper.marketdata.*;
import com.mscient.intrapaper.simulation.portfolio.PortfolioReplayCsvRunner;
import com.mscient.intrapaper.audit.portfolio.PortfolioRiskAudit;
import com.mscient.intrapaper.audit.portfolio.PortfolioSettlementAudit;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** SYNTHETIC ONLY. These declarations are deliberately unauthenticated and confer no gate/operation authority. */
public final class G7SyntheticFixtures {
    static final String ID = "SYNTHETIC_PERIOD_01", SCOPE = "SYNTHETIC_SCOPE";
    static final Instant START = Instant.parse("2030-05-06T03:45:00Z"), END = START.plusSeconds(240);
    static final Instant AS_OF = Instant.parse("2030-05-07T00:01:00Z"), BEFORE = START.minusSeconds(600);
    private G7SyntheticFixtures() { }

    static final class Fixture {
        final Path root;
        final Map<String, Object> manifest, bundle;
        final List<Object> extraEvents = new ArrayList<>();
        final Set<String> correctionArtifacts = new HashSet<>();
        final Map<String, Map<String, Object>> byPath = new TreeMap<>();
        private int next;
        Fixture(Path root) throws IOException {
            this.root = G7Files.local(root); Files.createDirectory(this.root);
            manifest = map("schema", "g7-manifest/v1", "review_id", "SYNTHETIC_REVIEW_01", "scope", SCOPE,
                    "period", interval(), "as_of", AS_OF.toString(), "mode", "SYNTHETIC_DIAGNOSTIC",
                    "owner", map("id", "SYNTHETIC_OWNER", "kind", "HUMAN", "authority_evidence", null, "identity_evidence", null),
                    "reviewer", map("id", "SYNTHETIC_REVIEWER", "kind", "HUMAN", "scope", SCOPE,
                            "qualification_evidence", null, "independence_evidence", null, "identity_evidence", null),
                    "approved_envelope", emptyEnvelope(), "gates", new ArrayList<>(), "rules", null);
            bundle = map("schema", "g7-bundle/v1", "review_id", manifest.get("review_id"), "artifacts", new ArrayList<>(),
                    "schedule", new ArrayList<>(List.of(map("schema", "g7-scheduled-period/v1", "period_id", ID,
                            "start", START.toString(), "end", END.toString(), "status", "EXPECTED", "reason", null))),
                    "periods", new ArrayList<>(List.of(period())), "changes", new ArrayList<>(),
                    "statistical_estimates", new ArrayList<>(), "capacity_claims", new ArrayList<>());
            for (String domain : G7Schema.DOMAINS) {
                Object content = domain.equals("CONFIGURATION") ? map("schema", "g7-configuration/v1",
                        "daily_ops_configuration", null, "portfolio_settings", null, "dataset_configurations", map())
                        : "SYNTHETIC " + domain + " identity. No approved operating settings.\n";
                String artifact = add("envelope/" + domain + (content instanceof Map ? ".json" : ".txt"), "ENVELOPE", content);
                obj(manifest.get("approved_envelope")).put(domain, artifact); obj(p().get("envelope")).put(domain, artifact);
            }
            String authority = add("roles/owner.txt", "PROVENANCE", "SYNTHETIC owner authority placeholder; NOT authenticated.\n");
            String qualification = add("roles/reviewer.txt", "PROVENANCE", "SYNTHETIC qualifications placeholder; NOT reviewed.\n");
            String independence = add("roles/independence.txt", "PROVENANCE", "SYNTHETIC independence placeholder; NOT established.\n");
            String ownerIdentity = add("roles/owner-identity.txt", "PROVENANCE", "SYNTHETIC identity; NOT provisioned.\n");
            String reviewerIdentity = add("roles/reviewer-identity.txt", "PROVENANCE", "SYNTHETIC identity; NOT provisioned.\n");
            obj(manifest.get("owner")).put("authority_evidence", authority); obj(manifest.get("owner")).put("identity_evidence", ownerIdentity);
            obj(manifest.get("reviewer")).put("qualification_evidence", qualification);
            obj(manifest.get("reviewer")).put("independence_evidence", independence);
            obj(manifest.get("reviewer")).put("identity_evidence", reviewerIdentity);
            String policy = add("policy/actions.txt", "POLICY", "SYNTHETIC owner policy: preserve evidence, stop through separately controlled human procedure, escalate, independently review and requalify. No policy adoption claimed.\n");
            String policyReview = add("policy/review.txt", "POLICY", "SYNTHETIC independent policy review placeholder; NOT authenticated.\n");
            List<Object> triggers = new ArrayList<>();
            for (String domain : G7Schema.DOMAINS) triggers.add(map("trigger_id", "change-" + domain, "kind", "CHANGE", "scope", domain,
                    "severities", List.of(), "stop_required", true, "requalification", List.of("G2", "G3"), "required_actions", List.of(policy)));
            for (String kind : List.of("INCIDENT", "EXCEPTION")) triggers.add(map("trigger_id", kind, "kind", kind, "scope", "ANY",
                    "severities", List.of("SEVERE", "WARNING"), "stop_required", true, "requalification", List.of("G2", "G4"), "required_actions", List.of(policy)));
            manifest.put("rules", map("schema", "g7-rules/v1", "registered_at", BEFORE.toString(), "owner_rules_evidence", policy,
                    "policy_review_evidence", policyReview, "cadence_seconds", 240, "review_freshness_seconds", 86400,
                    "escalation_path", "SYNTHETIC_ESCALATION", "escalation_evidence", policy,
                    "finding_severities", List.of(map("finding_code", "RISK_BREACH", "severity", "SEVERE"),
                            map("finding_code", "EXTERNAL_LEDGER_MISMATCH", "severity", "SEVERE")),
                    "freshness", G7Schema.CATEGORIES.stream().map(c -> (Object) map("category", c, "max_age_seconds", 86400)).toList(),
                    "triggers", triggers));
            for (String category : G7Schema.CATEGORIES) add("observations/" + category + ".txt", category,
                    "SYNTHETIC " + category + " placeholder. Declared coverage is not original operating evidence.\n");
            for (String gate : G7Schema.GATES) {
                String original = add("gates/" + gate + "-original.txt", "APPROVAL", "SYNTHETIC " + gate + " original-decision placeholder. No gate is approved/closed by this fixture.\n");
                String decision = add("gates/" + gate + "-decision.json", "APPROVAL", gateDecision(gate, original));
                list(manifest.get("gates")).add(map("gate", gate, "decision_id", "SYNTHETIC_" + gate, "status", "APPROVED", "artifact", decision,
                        "approved_at", BEFORE.toString(), "expires", AS_OF.plusSeconds(3600).toString(), "scope", SCOPE,
                        "envelope_sha256", null, "complete", true, "artifacts", new ArrayList<>(List.of(original))));
            }
            refreshGates();
        }
        Map<String, Object> p() { return obj(list(bundle.get("periods")).getFirst()); }
        Map<String, Object> a(Object id) {
            return list(bundle.get("artifacts")).stream().map(G7Json::obj).filter(a -> a.get("id").equals(id)).findFirst().orElseThrow();
        }
        String id(String path) { return str(byPath.get(path), "id"); }
        String add(String path, String category, Object value) throws IOException {
            boolean observed = G7Schema.CATEGORIES.contains(category) || Set.of("REPLAY_INPUT", "RISK_INPUT", "SETTLEMENT_INPUT", "NORMALIZATION").contains(category);
            byte[] content = value instanceof byte[] b ? b : value instanceof String s ? s.getBytes(java.nio.charset.StandardCharsets.UTF_8) : bytes(value);
            String artifactId = "synthetic-a-" + String.format(Locale.ROOT, "%03d", ++next);
            Path target = root.resolve(G7Files.relative(path)); Files.createDirectories(target.getParent()); Files.write(target, content, StandardOpenOption.CREATE_NEW);
            String format = value instanceof byte[] ? "BINARY" : value instanceof String ? "UTF8" : "JSON";
            Map<String, Object> artifact = map("schema", "g7-input-artifact/v1", "id", artifactId, "path", path, "sha256", hash(content),
                    "size_bytes", content.length, "format", format, "category", category, "classification", "SYNTHETIC",
                    "source_identity", "SYNTHETIC_SOURCE", "provenance", "SYNTHETIC TEST FIXTURE; NOT AUTHENTICATED",
                    "observed_at", (observed ? END : BEFORE).toString(), "period_id", observed ? ID : null,
                    "coverage", observed ? interval() : null, "complete", observed ? true : null,
                    "authentication", "UNAUTHENTICATED", "signature", null);
            list(bundle.get("artifacts")).add(artifact); byPath.put(path, artifact);
            if (observed) list(p().get("evidence")).add(artifactId);
            return artifactId;
        }
        void replaceBytes(String path, Object value) throws IOException {
            byte[] content = value instanceof byte[] b ? b : value instanceof String s ? s.getBytes(java.nio.charset.StandardCharsets.UTF_8) : bytes(value);
            Files.write(root.resolve(path), content); Map<String, Object> a = byPath.get(path);
            a.put("sha256", hash(content)); a.put("size_bytes", content.length);
        }
        void refreshGates() throws IOException {
            String envelopeHash = envelopeHash(this, obj(manifest.get("approved_envelope")));
            for (Object value : list(manifest.get("gates"))) {
                Map<String, Object> gate = obj(value); gate.put("envelope_sha256", envelopeHash);
                Map<String, Object> declaration = gateDecision(str(gate, "gate"), (String) list(gate.get("artifacts")).getFirst());
                declaration.put("envelope_sha256", envelopeHash); replaceBytes(str(a(gate.get("artifact")), "path"), declaration);
            }
        }
        private Map<String, Object> gateDecision(String gate, String original) {
            return map("schema", "g7-gate-decision/v1", "gate", gate, "decision_id", "SYNTHETIC_" + gate, "status", "APPROVED",
                    "scope", SCOPE, "approved_at", BEFORE.toString(), "expires", AS_OF.plusSeconds(3600).toString(),
                    "envelope_sha256", null, "complete", true, "source_artifact_hashes", map(original, a(original).get("sha256")),
                    "signer_id", "SYNTHETIC_OWNER", "signer_kind", "HUMAN", "identity_evidence", obj(manifest.get("owner")).get("identity_evidence"),
                    "authority_evidence", obj(manifest.get("owner")).get("authority_evidence"), "qualification_evidence", obj(manifest.get("reviewer")).get("qualification_evidence"),
                    "independence_evidence", obj(manifest.get("reviewer")).get("independence_evidence"), "signature", null);
        }
        byte[] write() throws IOException {
            Files.write(root.resolve("manifest.json"), bytes(manifest)); Files.write(root.resolve("bundle.json"), bytes(bundle));
            List<Object> events = new ArrayList<>();
            events.add(event("registration", "REGISTRATION", hash(bytes(manifest)), null, BEFORE));
            for (Object scheduled : list(bundle.get("schedule"))) {
                Map<String, Object> e = event("schedule-" + obj(scheduled).get("period_id"), "PERIOD", hash(bytes(scheduled)), str(obj(scheduled), "period_id"), BEFORE);
                events.add(e);
            }
            for (Object value : list(bundle.get("artifacts"))) {
                Map<String, Object> a = obj(value);
                if (correctionArtifacts.contains(str(a, "id"))) continue;
                Map<String, Object> e = event("evidence-" + a.get("id"), "EVIDENCE", hash(bytes(a)), str(a, "period_id"), AS_OF);
                e.put("artifact_record", a); e.put("evidence", List.of(a.get("id"))); events.add(e);
            }
            for (Object value : list(bundle.get("changes"))) {
                Map<String, Object> c = obj(value), e = event(str(c, "history_event_id"), "CHANGE", hash(bytes(c)), str(c, "period_id"), AS_OF);
                e.put("scope", c.get("scope")); e.put("occurred_at", c.get("at")); e.put("change_record", c); e.put("evidence", List.of(c.get("after"))); events.add(e);
            }
            for (Object value : list(bundle.get("periods"))) {
                Map<String, Object> p = obj(value), e = event("period-evidence-" + p.get("period_id"), "PERIOD_EVIDENCE", hash(bytes(p)), str(p, "period_id"), AS_OF);
                e.put("period_record", p); e.put("evidence", p.get("evidence")); events.add(e);
            }
            events.addAll(extraEvents);
            events.add(event("bundle-final", "BUNDLE", hash(bytes(bundle)), null, AS_OF));
            byte[] history = chain(events); Files.write(root.resolve("history.json"), history); return history;
        }
    }
    static Map<String, Object> interval() { return map("start", START.toString(), "end", END.toString()); }
    static Map<String, Object> emptyEnvelope() {
        Map<String, Object> m = map("schema", "g7-envelope/v1"); G7Schema.DOMAINS.forEach(d -> m.put(d, null)); return m;
    }
    static Map<String, Object> period() {
        return map("schema", "g7-period/v1", "period_id", ID, "status", "OBSERVED", "reason", null,
                "observed_start", START.toString(), "observed_end", END.toString(), "envelope", emptyEnvelope(), "evidence", new ArrayList<>(),
                "daily_ops", null, "feed_audit", null, "feed_scope_ids", null, "portfolio_run", null, "portfolio_inputs", null, "portfolio_settings", null,
                "risk_audit", null, "risk_inputs", null, "settlement_audit", null, "settlement_events", null, "settlement_costs", null,
                "settlement_costs_mode", "UNKNOWN", "reconciliation", null, "exceptions", new ArrayList<>());
    }
    static Map<String, Object> event(String id, String kind, String subject, String period, Instant at) {
        return map("schema", "g7-event/v1", "sequence", 0, "event_id", id, "kind", kind, "occurred_at", at.toString(), "recorded_at", at.toString(),
                "scope", SCOPE, "period_id", period, "severity", null, "evidence", List.of(), "response", null, "stop_status", "UNKNOWN",
                "resolution_evidence", List.of(), "required_rereview", List.of(), "prior_event_id", null, "supersedes_sha256", null,
                "artifact_record", null, "period_record", null, "change_record", null, "subject_sha256", subject,
                "decision", null, "previous_sha256", G7Ledger.GENESIS, "signature", null);
    }
    static byte[] chain(List<Object> events) {
        String previous = G7Ledger.GENESIS; int sequence = 0;
        for (Object value : events) { Map<String, Object> e = obj(value); e.put("sequence", sequence++); e.put("previous_sha256", previous); previous = hash(bytes(e)); }
        return bytes(map("schema", "g7-history/v1", "review_id", "SYNTHETIC_REVIEW_01", "events", events));
    }
    static String envelopeHash(Fixture f, Map<String, Object> envelope) {
        Map<String, Object> identities = map();
        for (String domain : G7Schema.DOMAINS) identities.put(domain, envelope.get(domain) == null ? null : G7Review.identity(f.a(envelope.get(domain))));
        return hash(bytes(identities));
    }
    static Map<String, Object> cloneMap(Object value) throws IOException { return obj(parse(bytes(value))); }
    static void missingPeriod(Fixture f) {
        Map<String, Object> p = period(); p.put("status", "MISSING"); p.put("reason", "SYNTHETIC explicit missing scheduled evidence");
        p.put("observed_start", null); p.put("observed_end", null); list(f.bundle.get("periods")).set(0, p);
    }
    static void drift(Fixture f, String domain, boolean logged) throws IOException {
        Object before = obj(f.manifest.get("approved_envelope")).get(domain);
        String after = f.add("changed/" + domain + ".txt", "ENVELOPE", "SYNTHETIC changed " + domain + " bytes.\n");
        obj(f.p().get("envelope")).put(domain, after);
        if (logged) list(f.bundle.get("changes")).add(map("schema", "g7-change/v1", "change_id", "change-" + domain, "period_id", ID,
                "at", START.plusSeconds(1).toString(), "scope", domain, "before", before, "after", after, "approvals", List.of(),
                "history_event_id", "change-event-" + domain));
    }
    static void reconciliation(Fixture f) throws IOException {
        String policy = f.add("ledger/normalization.txt", "NORMALIZATION", "SYNTHETIC declared normalization; no external-source or mapping authentication.\n");
        String insideRaw = f.add("ledger/internal-raw.txt", "RAW_INPUT", "SYNTHETIC internal source export\n");
        String outsideRaw = f.add("ledger/external-raw.txt", "RAW_INPUT", "SYNTHETIC external source export\n");
        List<Object> streams = new ArrayList<>();
        for (String kind : G7Schema.STREAMS) {
            Map<String, Object> values = switch (kind) {
                case "ORDERS" -> map("side", "BUY", "units", "1", "status", "NEW");
                case "ACKNOWLEDGEMENTS" -> map("status", "ACKNOWLEDGED");
                case "EXECUTIONS" -> map("side", "BUY", "units", "1", "price", "100");
                case "CANCELLATIONS" -> map("cancelled_units", "0", "status", "NONE_REMAINING");
                case "CORRECTIONS" -> map("field", "status", "old_value", "NEW", "new_value", "ACKNOWLEDGED");
                case "POSITIONS" -> map("units", "1");
                case "FEES" -> map("amount", "0.00", "currency", "INR");
                case "CASH" -> map("amount", "500", "currency", "INR", "entry_type", "SYNTHETIC_DEPOSIT");
                case "MARGIN" -> map("amount", "1", "currency", "INR", "margin_type", "SYNTHETIC_PROXY");
                default -> map("units", "1", "price", "104", "amount", "104", "currency", "INR");
            };
            Map<String, Object> row = map("schema", "g7-normalized-record/v1", "record_id", "row-" + kind,
                    "at", START.plusSeconds(kind.equals("CORRECTIONS") ? 2 : 1).toString(), "order_id", "SYNTHETIC_ORDER",
                    "instrument_id", "F", "prior_record_id", kind.equals("CORRECTIONS") ? "row-ORDERS" : null, "values", values);
            streams.add(map("kind", kind, "complete", true, "count", 1, "records", List.of(row)));
        }
        String inside = f.add("ledger/internal.json", "RECONCILIATION", map("schema", "g7-normalized-ledger/v1", "period_id", ID,
                "classification", "SYNTHETIC", "raw_source", insideRaw, "coverage", interval(), "normalization_evidence", policy, "streams", streams));
        String outside = f.add("ledger/external.json", "RECONCILIATION", map("schema", "g7-normalized-ledger/v1", "period_id", ID,
                "classification", "SYNTHETIC", "raw_source", outsideRaw, "coverage", interval(), "normalization_evidence", policy, "streams", streams));
        List<Object> compared = new ArrayList<>();
        for (Object value : streams) {
            Map<String, Object> s = obj(value); compared.add(map("kind", s.get("kind"), "internal_complete", s.get("complete"), "external_complete", s.get("complete"),
                    "internal_count", s.get("count"), "external_count", s.get("count"), "internal", s.get("records"), "external", s.get("records")));
        }
        f.p().put("reconciliation", f.add("ledger/reconciliation.json", "RECONCILIATION", map("schema", "g7-reconciliation/v1", "period_id", ID,
                "coverage", interval(), "normalization_evidence", policy, "internal_source", inside, "external_source", outside,
                "external_identity", "SYNTHETIC_EXTERNAL", "streams", compared)));
    }
    static Map<String, Object> read(Fixture f, String path) throws IOException { return obj(parse(Files.readAllBytes(f.root.resolve(path)))); }
    static void reconcileEdit(Fixture f, java.util.function.Consumer<Map<String, Object>> edit) throws IOException {
        Map<String, Object> external = read(f, "ledger/external.json"); edit.accept(external); f.replaceBytes("ledger/external.json", external);
        Map<String, Object> recon = read(f, "ledger/reconciliation.json");
        Map<String, Map<String, Object>> source = new TreeMap<>();
        for (Object value : list(external.get("streams"))) source.put(str(obj(value), "kind"), obj(value));
        for (Object value : list(recon.get("streams"))) {
            Map<String, Object> stream = obj(value), original = source.get(str(stream, "kind"));
            if (original != null) { stream.put("external", original.get("records")); stream.put("external_count", original.get("count")); stream.put("external_complete", original.get("complete")); }
        }
        f.replaceBytes("ledger/reconciliation.json", recon);
    }
    static void portfolio(Fixture f, Path project) throws Exception {
        Path original = project.resolve("examples/portfolio-settlement");
        try (var originals = Files.list(original)) { for (Path file : originals.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().equals("README.md")).toList()) {
            String path = "portfolio-inputs/" + file.getFileName(); f.add(path, "REPLAY_INPUT", G7Json.utf8(Files.readAllBytes(file)));
        } }
        Path inputs = f.root.resolve("portfolio-inputs/inputs.csv"), settings = f.root.resolve("portfolio-inputs/portfolio.properties");
        Path scratch = f.root.resolveSibling(f.root.getFileName() + "-adapter-work"); Files.createDirectory(scratch);
        var run = PortfolioReplayCsvRunner.run(inputs, settings, scratch.resolve("runs"));
        copyArtifactFolder(f, run.path(), "portfolio-runs", "REPLAY");
        f.p().put("portfolio_run", f.id("portfolio-runs/" + run.runId() + "/run-manifest.properties"));
        f.p().put("portfolio_inputs", f.id("portfolio-inputs/inputs.csv")); f.p().put("portfolio_settings", f.id("portfolio-inputs/portfolio.properties"));
        String riskDir = "risk-inputs";
        f.add(riskDir + "/limits.csv", "RISK_INPUT", "instrument_id,effective_from,effective_to,max_abs_units,max_gross_notional_inr,source_id\nF,2030-05-06,2030-05-07,100,1000000,s\nO,2030-05-06,2030-05-07,100,1000000,s\n");
        f.add(riskDir + "/margin-proxies.csv", "RISK_INPUT", "instrument_id,effective_from,effective_to,long_proxy_inr_per_unit,short_proxy_inr_per_unit,source_id\nF,2030-05-06,2030-05-07,1,1,s\nO,2030-05-06,2030-05-07,1,1,s\n");
        f.add(riskDir + "/sources.csv", "RISK_INPUT", "source_id,effective_from,effective_to,observed_date,reference,reference_sha256\ns,2030-05-06,2030-05-07,2030-05-06,SYNTHETIC," + "1".repeat(64) + "\n");
        f.add(riskDir + "/budgets.csv", "RISK_INPUT", "effective_from,effective_to,max_gross_notional_inr,max_session_loss_inr,max_caller_supplied_margin_proxy_inr,max_mark_age_seconds,max_source_age_days,source_id\n2030-05-06,2030-05-07,1000000,1000000,1000000,60,2,s\n");
        var risk = PortfolioRiskAudit.audit(run.path(), inputs, settings, f.root.resolve(riskDir), scratch.resolve("risk"));
        copyArtifactFolder(f, risk.path(), "risk-audits", "RISK"); f.p().put("risk_audit", f.id("risk-audits/" + risk.auditId() + "/audit-manifest.properties"));
        f.p().put("risk_inputs", map("limits", f.id(riskDir + "/limits.csv"), "margin_proxies", f.id(riskDir + "/margin-proxies.csv"), "sources", f.id(riskDir + "/sources.csv"), "budgets", f.id(riskDir + "/budgets.csv")));
        f.add("cash-settlement/events.csv", "SETTLEMENT_INPUT", "instrument_id,expiry,settlement_date,settlement_method,settlement_value_per_unit_inr,source_id,source_reference\nF,2030-05-06,2030-05-06,CASH_FUTURES_FINAL_PRICE,104,s,SYNTHETIC\nO,2030-05-06,2030-05-06,CASH_OPTION_PAYOFF,0,s,SYNTHETIC\n");
        f.add("cash-settlement/costs.csv", "SETTLEMENT_INPUT", "effective_from,effective_through,contract_kind,component_code,basis,rate,gst_bps,gst_on_charge,source_reference\n2030-05-06,2030-05-07,FUTURE,SYNTHETIC_CHARGE,PER_SETTLED_UNIT,0.10,0,false,SYNTHETIC\n2030-05-06,2030-05-07,OPTION,SYNTHETIC_CHARGE,PER_SETTLED_UNIT,0.10,0,false,SYNTHETIC\n");
        var settlement = PortfolioSettlementAudit.audit(run.path(), inputs, settings, f.root.resolve("cash-settlement/events.csv"), f.root.resolve("cash-settlement/costs.csv"), scratch.resolve("settlement"));
        copyArtifactFolder(f, settlement.path(), "settlement-audits", "SETTLEMENT");
        f.p().put("settlement_audit", f.id("settlement-audits/" + settlement.auditId() + "/settlement-manifest.properties"));
        f.p().put("settlement_events", f.id("cash-settlement/events.csv")); f.p().put("settlement_costs", f.id("cash-settlement/costs.csv")); f.p().put("settlement_costs_mode", "PROVIDED");
        configuration(f, null); f.refreshGates();
    }
    private static void copyArtifactFolder(Fixture f, Path source, String prefix, String category) throws IOException {
        try (var files = Files.list(source)) { for (Path file : files.sorted().toList()) f.add(prefix + "/" + source.getFileName() + "/" + file.getFileName(), category, utf8(Files.readAllBytes(file))); }
    }
    static void configuration(Fixture f, Object daily) throws IOException {
        Map<String, Object> value = map("schema", "g7-configuration/v1", "daily_ops_configuration", daily, "portfolio_settings", f.p().get("portfolio_settings"),
                "dataset_configurations", f.p().get("portfolio_settings") == null ? map() : map("F", f.id("portfolio-inputs/future.properties"), "O", f.id("portfolio-inputs/option.properties")));
        String id = f.byPath.containsKey("configuration.json") ? f.id("configuration.json") : f.add("configuration.json", "ENVELOPE", value);
        f.replaceBytes("configuration.json", value);
        obj(f.manifest.get("approved_envelope")).put("CONFIGURATION", id); obj(f.p().get("envelope")).put("CONFIGURATION", id); f.refreshGates();
    }
    static Object shiftDaily(Object value, String runId) {
        if (value instanceof String s) {
            if (s.equals("SYNTHETIC-2026-11-02")) return ID;
            if (s.equals("SYNTHETIC-clean-completion")) return runId;
            if (s.startsWith("2026-11-02T") || s.startsWith("2026-11-03T")) {
                Instant instant = Instant.parse(s).plus(Duration.between(Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2030-05-06T00:00:00Z")));
                if (s.startsWith("2026-11-02T") && !instant.isBefore(START.plusSeconds(120))) instant = instant.plusSeconds(60);
                return instant.toString();
            }
            return s;
        }
        if (value instanceof Map<?, ?> m) { Map<String, Object> out = map(); m.forEach((k, v) -> out.put((String) k, shiftDaily(v, runId))); return out; }
        if (value instanceof List<?> a) return a.stream().map(v -> shiftDaily(v, runId)).toList();
        return value;
    }
    static void daily(Fixture f, Path project) throws IOException {
        Path original = project.resolve("examples/daily-ops-evidence/clean-completion");
        String runId = f.p().get("portfolio_run") == null ? "SYNTHETIC_DAILY_RUN" : Path.of(str(f.a(f.p().get("portfolio_run")), "path")).getParent().getFileName().toString();
        try (var paths = Files.walk(original)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                String path = "daily/" + original.relativize(file).toString().replace('\\', '/');
                if (path.endsWith(".json")) f.add(path, "DAILY_OPS", shiftDaily(parse(Files.readAllBytes(file)), runId));
            }
        }
        Map<String, Object> daily = read(f, "daily/session-evidence.json");
        Map<String, Object> window = obj(list(obj(daily.get("coverage")).get("windows")).getFirst());
        List<Object> observations = new ArrayList<>(list(window.get("observations")));
        for (long offset : List.of(120L, 150L)) {
            Map<String, Object> row = cloneMap(observations.getFirst());
            row.put("event_id", "SYNTHETIC_EXTENSION_" + offset);
            row.put("observed_at_utc", START.plusSeconds(offset).toString()); row.put("input_at_utc", START.plusSeconds(offset).toString());
            observations.add(row);
        }
        observations.sort(Comparator.comparing(row -> str(obj(row), "observed_at_utc")));
        window.put("observations", observations); window.put("expected_observation_count", observations.size());
        Map<String, Object> originalInput = read(f, "daily/artifacts/input.json");
        originalInput.put("observations", observations); originalInput.put("expected_observation_count", observations.size());
        f.replaceBytes("daily/artifacts/input.json", originalInput);
        for (Object value : list(obj(daily.get("run")).get("artifacts"))) {
            Map<String, Object> row = obj(value); row.put("sha256", f.byPath.get("daily/" + str(row, "path")).get("sha256"));
        }
        obj(daily.get("risk")).put("config_sha256", f.byPath.get("daily/artifacts/config.json").get("sha256"));
        f.replaceBytes("daily/session-evidence.json", daily); f.p().put("daily_ops", f.id("daily/session-evidence.json"));
        configuration(f, f.id("daily/artifacts/config.json"));
    }
    static void feed(Fixture f, boolean gap, boolean unknownPolicy) throws IOException {
        f.add("feed/provenance.txt", "RAW_INPUT", "SYNTHETIC feed/source/policy declaration; not vendor evidence.\n");
        f.add("feed/decode.txt", "RAW_INPUT", "SYNTHETIC AUDIT_EVENT_V1 decoder declaration.\n");
        Path scratch = Files.createTempDirectory(f.root.getParent(), "synthetic-feed-");
        try {
            try (var raw = RawCaptureJournalWriter.create(scratch.resolve("capture.bin"))) {
                for (int i = 0; i < 24; i++) {
                    if (gap && i == 12) continue;
                    Instant at = START.plusSeconds(i * 10L);
                    byte[] payload = bytes(map("schema", "feed-audit-event/v1", "product", "TEST", "channel", "L1", "exchange", "TEST",
                            "token", "123", "instrument_id", "SYNTHETIC_I1", "type", "DATA", "event_time", at.toString()));
                    raw.append(new RawMarketMessage(1, "SYNTHETIC", "c1", "sub1", OptionalLong.of(i + 1), at, (i * 10L + 10) * 1_000_000_000L, payload));
                }
            }
            try (var journal = FeedLifecycleJournalWriter.create(scratch.resolve("lifecycle.bin"))) {
                var types = List.of(FeedLifecycleEvent.Type.CONNECTION_REQUESTED, FeedLifecycleEvent.Type.CONNECTION_ESTABLISHED,
                        FeedLifecycleEvent.Type.AUTHENTICATED, FeedLifecycleEvent.Type.SUBSCRIPTION_REQUESTED, FeedLifecycleEvent.Type.SUBSCRIPTION_ACKNOWLEDGED);
                for (int i = 0; i < types.size(); i++) {
                    var type = types.get(i);
                    journal.append(new FeedLifecycleEvent(1, "SYNTHETIC", "c1", type, type.name().startsWith("SUBSCRIPTION") ? Optional.of("sub1") : Optional.empty(),
                            START.minusSeconds(5 - i), (i + 1) * 1_000_000_000L, Optional.empty()));
                }
            }
            f.add("feed/capture.bin", "RAW_INPUT", Files.readAllBytes(scratch.resolve("capture.bin")));
            f.add("feed/lifecycle.bin", "RAW_INPUT", Files.readAllBytes(scratch.resolve("lifecycle.bin")));
        } finally { try (var paths = Files.walk(scratch)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); } }
        Map<String, Object> scope = map("scope_id", "s1", "source", "SYNTHETIC", "product", "TEST", "channel", "L1", "connection_id", "c1", "subscription_id", "sub1",
                "exchange", "TEST", "token", "123", "instrument_id", "SYNTHETIC_I1", "session_date", "2030-05-06", "timezone", "UTC",
                "session_start", START.toString(), "session_end", END.toString(), "expected_types", List.of("DATA"), "heartbeat", "NONE",
                "windows", List.of(map("window_id", "active", "kind", "ACTIVE", "start", START.toString(), "end", END.toString(), "reason", "SYNTHETIC explicit ACTIVE")));
        f.add("feed/schedule.json", "FEED", map("schema", "feed-audit-schedule/v1", "schedule_id", "synthetic-schedule", "revision", "1", "provenance", feedRef(f, "provenance.txt"),
                "interval_rule", "[start,end)", "scopes", List.of(scope)));
        f.add("feed/policy.json", "FEED", map("schema", "feed-audit-policy/v1", "policy_id", "synthetic-policy", "revision", "1", "provenance", feedRef(f, "provenance.txt"),
                "rules", List.of(map("scope_id", "s1", "max_event_gap", unknownPolicy ? null : threshold(10000), "stale_threshold", threshold(20000), "clock_skew", threshold(1000),
                        "heartbeat_gap", null, "sequence_reset", map("value", "REJECT", "unit", "ENUM", "provenance", "SYNTHETIC choice"),
                        "sequence_domain", map("value", "RAW_STREAM", "unit", "ENUM", "provenance", "SYNTHETIC choice")))));
        Map<String, Object> binding = map("scope_id", "s1", "decoder", "AUDIT_EVENT_V1", "decode_reference", feedRef(f, "decode.txt"), "mapping", null, "quotes", null,
                "timestamp_roles", map("event", "DECLARED_EVENT_UTC", "receive", "CAPTURE_RECEIVE_UTC", "lifecycle", "LIFECYCLE_OBSERVED_UTC", "monotonic", "JOURNAL_ORDER_DIAGNOSTIC_ONLY"),
                "raw", List.of(map("order", 0, "file", feedRef(f, "capture.bin"))), "lifecycle", List.of(map("order", 0, "file", feedRef(f, "lifecycle.bin"))));
        f.p().put("feed_audit", f.add("feed/evidence.json", "FEED", map("schema", "feed-audit-evidence/v1", "bindings", List.of(binding), "schedule", feedRef(f, "schedule.json"), "policy", feedRef(f, "policy.json"))));
        f.p().put("feed_scope_ids", List.of("s1"));
    }
    private static Map<String, Object> feedRef(Fixture f, String name) { return map("path", name, "sha256", f.byPath.get("feed/" + name).get("sha256")); }
    private static Map<String, Object> threshold(long value) { return map("value", value, "unit", "MILLISECONDS", "provenance", "SYNTHETIC caller choice"); }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Use examples|schemas and a new directory");
        Path destination = G7Files.local(Path.of(args[1])); Files.createDirectory(destination);
        if (args[0].equals("schemas")) {
            for (String name : G7Schema.names()) Files.write(destination.resolve(name + ".schema.json"), G7Schema.bytes(name), StandardOpenOption.CREATE_NEW);
        } else if (args[0].equals("examples")) {
            Fixture clean = new Fixture(destination.resolve("clean-looking")); clean.write();
            Fixture missing = new Fixture(destination.resolve("missing-period")); missingPeriod(missing); missing.write();
            Fixture changed = new Fixture(destination.resolve("build-drift")); drift(changed, "BUILD", true); changed.write();
        } else throw new IllegalArgumentException("Unsupported synthetic export");
        System.out.println("SYNTHETIC exports written; g7_closed=false operation_authorized=false scale_authorized=false");
    }
}
