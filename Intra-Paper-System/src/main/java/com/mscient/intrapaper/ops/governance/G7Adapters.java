package com.mscient.intrapaper.ops.governance;

import static com.mscient.intrapaper.ops.governance.G7Json.*;
import com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceChecker;
import com.mscient.intrapaper.audit.feed.FeedAudit;
import com.mscient.intrapaper.audit.portfolio.PortfolioRiskAuditArtifacts;
import com.mscient.intrapaper.audit.portfolio.PortfolioRiskAudit;
import com.mscient.intrapaper.audit.portfolio.PortfolioSettlementArtifacts;
import com.mscient.intrapaper.audit.portfolio.PortfolioSettlementAudit;
import com.mscient.intrapaper.simulation.portfolio.PortfolioReplayArtifactVerifier;
import com.mscient.intrapaper.simulation.portfolio.PortfolioReplayDeterminismVerifier;
import com.mscient.intrapaper.simulation.portfolio.PortfolioReplayCsvRunner;
import com.mscient.intrapaper.data.CsvTable;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/** Fixed read-only adapter allowlist. Existing calculators, controllers and statistical methods are unchanged. */
final class G7Adapters {
    private G7Adapters() { }
    static void check(G7Review r, Map<String, Object> p, Map<String, Object> scheduled) throws IOException {
        String id = str(p, "period_id");
        for (String adapter : List.of("daily_ops", "feed_audit", "portfolio_run", "risk_audit", "settlement_audit", "reconciliation")) {
            r.periodContext(p, p.get(adapter));
            Map<String, Object> a = r.artifact(p.get(adapter), id + "." + adapter);
            if (a == null || r.invalidArtifacts.contains(str(a, "id")) || !r.input.files.containsKey(str(a, "path"))) continue;
            if (!list(p.get("evidence")).contains(a.get("id"))) throw new IOException("Adapter input not in period evidence");
            try {
                Path frozen = r.input.frozen(str(a, "path"));
                switch (adapter) {
                    case "daily_ops" -> daily(r, p, scheduled, a, frozen);
                    case "feed_audit" -> feed(r, p, scheduled, a, frozen);
                    case "portfolio_run" -> portfolio(r, p, scheduled, a, frozen);
                    case "risk_audit" -> risk(r, p, a, frozen);
                    case "settlement_audit" -> settlement(r, p, a, frozen);
                    case "reconciliation" -> reconcile(r, p, scheduled);
                    default -> throw new IOException("Unsupported verifier");
                }
            } catch (MissingInput missing) {
                r.missing("ADAPTER_INPUTS_MISSING", id + "." + adapter, "Supply every exact original dependency required to recompute this diagnostic; artifact status/identity alone cannot substitute for it.");
            } catch (IOException | RuntimeException invalid) {
                r.fail("ADAPTER_REJECTED", id + "." + adapter, "Repair or replace the malformed/mismatched evidence through append-only history; existing verifier rejected it.");
            }
        }
    }
    private static void daily(G7Review r, Map<String, Object> p, Map<String, Object> s, Map<String, Object> a, Path path) throws IOException {
        String id = str(p, "period_id"); Map<String, Object> daily = r.artifactJson(a.get("id"), id, null);
        jsonPaths(r, p, daily, str(a, "path"));
        var result = DailyOpsEvidenceChecker.check(path);
        if (!id.equals(result.sessionId()) || !Objects.equals(s.get("start"), daily.get("session_start_utc"))
                || !Objects.equals(s.get("end"), daily.get("session_end_utc"))) r.fail("DAILY_SCOPE_MISMATCH", id, "Supply the daily evidence for the exact scheduled period and boundaries.");
        for (var check : result.results()) for (var finding : check.findings()) {
            if (!finding.status().name().equals("PASS")) r.add(finding.status().name(), "DAILY_" + check.check().toUpperCase(Locale.ROOT), id + "." + finding.field(),
                    "Resolve the original daily-operations finding under the reviewed policy and obtain independent disposition; inspect its exact local evidence.");
        }
        Map<String, Object> run = obj(daily.get("run"));
        Map<String, Object> configuration = r.configuration(p);
        if (run != null && run.get("artifacts") instanceof List<?>) for (Object value : list(run.get("artifacts"))) {
            Map<String, Object> file = obj(value);
            if (file.get("role").equals("CONFIG")) {
                if (configuration == null || configuration.get("daily_ops_configuration") == null) r.missing("DAILY_CONFIGURATION_UNKNOWN", id, "Supply the exact daily configuration member of the approved/observed configuration identity.");
                else if (!Objects.equals(file.get("sha256"), r.artifactHash(configuration.get("daily_ops_configuration"), id)))
                    r.fail("DAILY_CONFIGURATION_MISMATCH", id, "Bind the daily checker to the exact observed configuration member bytes.");
            }
        }
        // Preserve evidence of daily incidents/halts even if the daily checker considers local declarations consistent.
        if (daily.get("operator") instanceof Map<?, ?> operator) {
            Object incidents = operator.get("incidents");
            if (incidents instanceof List<?> entries && !entries.isEmpty()
                    && r.ledger.events.stream().noneMatch(e -> e.get("kind").equals("INCIDENT") && id.equals(e.get("period_id"))))
                r.fail("DAILY_INCIDENT_NOT_IN_HISTORY", id, "Register every daily incident in G7 history; no silent closure is allowed.");
        }
        r.observations.add(map("kind", "DAILY_EXISTING_VERIFIER", "period_ref", reference("period", id), "result", result.status().name().equals("PASS") ? "RECOMPUTED_NO_CONFIGURED_FINDING" : "RECOMPUTED_WITH_FINDINGS",
                "report_sha256", hash(result.bytes()), "source_authenticated", false));
    }
    private static void feed(G7Review r, Map<String, Object> p, Map<String, Object> s, Map<String, Object> a, Path path) throws IOException {
        String id = str(p, "period_id"); Map<String, Object> manifest = r.artifactJson(a.get("id"), id, null);
        jsonPaths(r, p, manifest, str(a, "path"));
        for (Object reference : List.of(manifest.get("schedule"), manifest.get("policy"))) {
            String linked = linked(r, p, str(a, "path"), str(obj(reference), "path"));
            jsonPaths(r, p, obj(parse(r.input.files.get(linked))), linked);
        }
        Map<String, Object> result = FeedAudit.run(path);
        for (Object row : list(result.get("rows"))) {
            Map<String, Object> finding = obj(row); String severity = str(finding, "severity");
            if (severity.equals("FAIL") || severity.equals("INCOMPLETE"))
                r.add(severity, "FEED_" + str(finding, "reason_code"), id + "." + str(finding, "scope_id"), "Resolve the original feed audit finding and independently review the declared source and policy; inspect exact local rows without inferring missing market activity.");
        }
        // FeedAudit verifies its own linked files; G7 additionally binds its schedule to this period.
        Map<String, Object> scheduleRef = obj(manifest.get("schedule"));
        String base = str(a, "path"); int slash = base.lastIndexOf('/'); base = slash < 0 ? "" : base.substring(0, slash + 1);
        byte[] scheduleBytes = r.input.files.get(base + G7Files.relative(str(scheduleRef, "path")));
        if (scheduleBytes == null) throw new IOException("Feed schedule absent from frozen inventory");
        Map<String, Object> schedule = obj(parse(scheduleBytes));
        List<Object> declaredScopes = p.get("feed_scope_ids") == null ? null : list(p.get("feed_scope_ids"));
        Set<Object> actualScopes = new HashSet<>();
        for (Object scopeValue : list(schedule.get("scopes"))) {
            Map<String, Object> scope = obj(scopeValue);
            actualScopes.add(scope.get("scope_id"));
            Instant aTime = OffsetDateTime.parse(str(scope, "session_start")).toInstant(), bTime = OffsetDateTime.parse(str(scope, "session_end")).toInstant();
            if (!aTime.equals(time(s.get("start"))) || !bTime.equals(time(s.get("end")))) r.fail("FEED_SCOPE_MISMATCH", id, "Bind raw feed audit to this exact scheduled interval.");
            for (Object v : list(scope.get("windows"))) {
                Map<String, Object> window = obj(v);
                if (!window.get("kind").equals("ACTIVE")) r.missing("FEED_WINDOW_EXCLUDED", id, "Retain closed, paused or excluded windows explicitly; they are not healthy operating coverage.");
            }
        }
        if (declaredScopes == null) r.missing("FEED_UNIVERSE_UNKNOWN", id, "Supply the exact intended feed scope identities; observing some instruments does not establish full universe coverage.");
        else if (new HashSet<>(declaredScopes).size() != declaredScopes.size() || !actualScopes.equals(new HashSet<>(declaredScopes)))
            r.fail("FEED_UNIVERSE_MISMATCH", id, "Bind all expected feed scope identities to the exact schedule; do not omit or substitute instruments/sources.");
        r.missing("OBSERVED_ENVIRONMENT_UNAUTHENTICATED", id, "Independently establish actual source, route, hosting, instruments and procedures; artifact declarations cannot observe a running environment.");
        r.observations.add(map("kind", "FEED_EXISTING_VERIFIER", "period_ref", reference("period", id), "result", result.get("status").equals("PASS") ? "RECOMPUTED_NO_CONFIGURED_FINDING" : "RECOMPUTED_WITH_FINDINGS",
                "report_sha256", hash(bytes(result)), "source_authenticated", false));
    }
    private static final class MissingInput extends IOException { private static final long serialVersionUID = 1L; }
    private static String registered(G7Review r, Map<String, Object> p, Object id) throws IOException {
        if (id == null) throw new MissingInput();
        Map<String, Object> a = r.artifact(id, str(p, "period_id"));
        if (!list(p.get("evidence")).contains(id)) throw new IOException("Dependency omitted from period evidence");
        String path = G7Files.relative(str(a, "path"));
        if (r.invalidArtifacts.contains(str(a, "id"))) throw new IOException("Mismatched dependency bytes");
        if (!r.input.files.containsKey(path)) throw new MissingInput();
        return path;
    }
    /** Preflight every file reference before calling legacy readers that permit absolute paths. */
    private static String linked(G7Review r, Map<String, Object> p, String from, String relative) throws IOException {
        G7Files.relative(relative);
        int slash = from.lastIndexOf('/'); String path = (slash < 0 ? "" : from.substring(0, slash + 1)) + relative;
        G7Files.relative(path);
        Map<String, Object> a = r.artifacts.values().stream().filter(v -> v.get("path").equals(path)).findFirst().orElseThrow(() -> new IOException("Unregistered dependency"));
        return registered(r, p, a.get("id"));
    }
    private static void jsonPaths(G7Review r, Map<String, Object> p, Object value, String from) throws IOException {
        if (value instanceof Map<?, ?> m) for (var entry : m.entrySet()) {
            String key = (String) entry.getKey(); Object child = entry.getValue();
            if ((key.equals("path") || key.endsWith("_path")) && child != null) {
                if (!(child instanceof String)) throw new IOException("Malformed path reference");
                linked(r, p, from, (String) child);
            }
            jsonPaths(r, p, child, from);
        } else if (value instanceof List<?> a) for (Object child : a) jsonPaths(r, p, child, from);
    }
    static Map<String, String> properties(byte[] bytes, boolean strict) throws IOException {
        Map<String, String> values = new TreeMap<>();
        for (String line : utf8(bytes).lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int separator = line.indexOf('=');
            if (separator < 1 || strict && (separator != line.lastIndexOf('=') || line.contains("\\") || !line.equals(line.strip())))
                throw new IOException("Portable unescaped key=value properties required");
            String key = line.substring(0, separator), value = line.substring(separator + 1);
            if (!key.matches("[A-Za-z0-9._]+") || !value.equals(value.strip()) || value.isEmpty() && strict || values.putIfAbsent(key, value) != null)
                throw new IOException("Duplicate or malformed properties");
        }
        return values;
    }
    private static void settingPaths(G7Review r, Map<String, Object> p, String path) throws IOException {
        for (var entry : properties(r.input.files.get(path), true).entrySet()) {
            if (entry.getKey().endsWith(".file") || entry.getKey().endsWith("_file") || entry.getKey().endsWith("_csv")) {
                if (!entry.getValue().equals("NONE")) linked(r, p, path, entry.getValue());
            }
        }
    }
    private record ReplayFiles(Path inputs, Path settings, Path run) { }
    private static ReplayFiles replayFiles(G7Review r, Map<String, Object> p) throws IOException {
        String inputs = registered(r, p, p.get("portfolio_inputs")), settings = registered(r, p, p.get("portfolio_settings"));
        String run = registered(r, p, p.get("portfolio_run"));
        if (!run.endsWith("/run-manifest.properties")) throw new IOException("Exact run manifest required");
        CsvTable table = CsvTable.read(r.input.frozen(inputs));
        Map<String, Object> configuration = r.configuration(p);
        if (!table.headers().equals(List.of("instrument_id", "bars_csv", "run_properties", "max_abs_lots")) || table.rows().size() > 128)
            throw new IOException("Unsupported portfolio input contract");
        for (Map<String, String> row : table.rows()) {
            linked(r, p, inputs, row.get("bars_csv"));
            String config = linked(r, p, inputs, row.get("run_properties")); settingPaths(r, p, config);
            Object expected = configuration == null ? null : obj(configuration.get("dataset_configurations")).get(row.get("instrument_id"));
            if (expected == null) r.missing("REPLAY_DATASET_CONFIGURATION_UNKNOWN", str(p, "period_id"), "Supply every exact instrument configuration member of the observed envelope; no member is inferred.");
            else if (!Objects.equals(hash(r.input.files.get(config)), r.artifactHash(expected, "dataset_configuration")))
                r.fail("REPLAY_DATASET_CONFIGURATION_MISMATCH", str(p, "period_id"), "Bind each replay instrument to its exact observed and previously approved configuration bytes.");
        }
        settingPaths(r, p, settings);
        if (configuration == null || configuration.get("portfolio_settings") == null)
            r.missing("REPLAY_CONFIGURATION_UNKNOWN", str(p, "period_id"), "Supply the exact original portfolio-settings member of the approved/observed envelope.");
        else if (!Objects.equals(r.artifactHash(p.get("portfolio_settings"), "portfolio_settings"), r.artifactHash(configuration.get("portfolio_settings"), "configuration")))
            r.fail("REPLAY_CONFIGURATION_MISMATCH", str(p, "period_id"), "Bind the replay's actual original settings to the exact observed configuration envelope.");
        return new ReplayFiles(r.input.frozen(inputs), r.input.frozen(settings), r.input.frozen(run).getParent());
    }
    private static void portfolio(G7Review r, Map<String, Object> p, Map<String, Object> scheduled, Map<String, Object> a, Path path) throws IOException {
        String id = str(p, "period_id");
        if (!path.getFileName().toString().equals("run-manifest.properties")) throw new IOException("Portfolio manifest path mismatch");
        var result = PortfolioReplayArtifactVerifier.verify(path.getParent());
        ReplayFiles original = replayFiles(r, p);
        PortfolioReplayDeterminismVerifier.verify(original.inputs, original.settings, original.run);
        var replay = PortfolioReplayCsvRunner.replay(original.inputs, original.settings);
        if (!replay.dataset().frames().getFirst().timestamp().equals(time(scheduled.get("start")))
                || replay.dataset().frames().getLast().timestamp().isAfter(time(scheduled.get("end"))))
            r.fail("REPLAY_SCOPE_MISMATCH", id, "Supply the original replay for the exact scheduled period; unrelated or partial windows cannot count as full operating evidence.");
        r.missing("REPLAY_OPERATIONAL_COVERAGE_UNESTABLISHED", id, "Deterministic local replay is software evidence; it does not establish complete real order/position/account activity or authenticate the observed operating envelope.");
        r.observations.add(map("kind", "PORTFOLIO_ACCOUNTING_SETTLEMENT", "period_ref", reference("period", id),
                "run_sha256", result.runId(), "result", "REPERFORMED_EXISTING_REPLAY_VERIFIER", "source_authenticated", false));
    }
    private static void risk(G7Review r, Map<String, Object> p, Map<String, Object> a, Path path) throws IOException {
        if (!path.getFileName().toString().equals("audit-manifest.properties")) throw new IOException("Risk manifest path mismatch");
        var identity = PortfolioRiskAuditArtifacts.verify(path.getParent());
        // Preserve the original diagnostic status even when original recomputation inputs are missing.
        if (identity.status().equals("BREACH")) r.fail("RISK_BREACH", str(p, "period_id"), "Record the separately controlled stop and independent risk/incident review.");
        if (identity.status().equals("INCOMPLETE")) r.missing("RISK_INCOMPLETE", str(p, "period_id"), "Obtain complete original risk, margin and source coverage; unknown limits/proxies remain unknown.");
        ReplayFiles original = replayFiles(r, p);
        if (p.get("risk_inputs") == null) throw new MissingInput();
        Map<String, Object> inputs = obj(p.get("risk_inputs")); Path folder = null;
        for (String key : List.of("limits", "margin_proxies", "sources", "budgets")) {
            Path file = r.input.frozen(registered(r, p, inputs.get(key)));
            String name = switch (key) { case "margin_proxies" -> "margin-proxies.csv"; default -> key + ".csv"; };
            if (!file.getFileName().toString().equals(name) || folder != null && !folder.equals(file.getParent())) throw new IOException("Risk input layout mismatch");
            folder = file.getParent();
        }
        var result = PortfolioRiskAudit.verify(path.getParent(), original.run, original.inputs, original.settings, folder);
        r.observations.add(map("kind", "RISK_EXISTING_VERIFIER", "period_ref", reference("period", p.get("period_id")), "artifact_sha256", result.auditId(),
                "result", "REPERFORMED_EXISTING_RISK_VERIFIER", "source_authenticated", false));
        r.missing("ACCOUNT_RISK_AUTHENTICATION_UNAVAILABLE", str(p, "period_id"), "Authenticate original account limits, positions, cash and margin independently; this replay audit uses caller-provided proxies and executes no risk controls.");
    }
    private static void settlement(G7Review r, Map<String, Object> p, Map<String, Object> a, Path path) throws IOException {
        if (!path.getFileName().toString().equals("settlement-manifest.properties")) throw new IOException("Settlement manifest path mismatch");
        PortfolioSettlementArtifacts.verify(path.getParent());
        ReplayFiles original = replayFiles(r, p);
        Path events = r.input.frozen(registered(r, p, p.get("settlement_events"))), costs = null;
        String mode = str(p, "settlement_costs_mode");
        if (mode.equals("UNKNOWN")) throw new MissingInput();
        if (mode.equals("PROVIDED")) costs = r.input.frozen(registered(r, p, p.get("settlement_costs")));
        else {
            if (p.get("settlement_costs") != null) throw new IOException("Contradictory settlement cost mode");
            r.missing("SETTLEMENT_COSTS_NOT_MODELED", str(p, "period_id"), "Obtain complete original dated fee/tax/charge coverage; not modeled never means zero charges.");
        }
        var result = PortfolioSettlementAudit.verify(path.getParent(), original.run, original.inputs, original.settings, events, costs);
        r.observations.add(map("kind", "SETTLEMENT_EXISTING_VERIFIER", "period_ref", reference("period", p.get("period_id")), "artifact_sha256", result.auditId(),
                "result", "REPERFORMED_EXISTING_SETTLEMENT_VERIFIER", "source_authenticated", false));
        r.missing("SETTLEMENT_SOURCE_AUTHENTICATION_UNAVAILABLE", str(p, "period_id"), "Independently establish settlement source truth, charge completeness and actual account reconciliation; local re-performance grants no operational authority.");
    }
    private static final Map<String, List<String>> FIELDS = Map.of(
            "ORDERS", List.of("side", "units", "status"), "ACKNOWLEDGEMENTS", List.of("status"),
            "EXECUTIONS", List.of("side", "units", "price"), "CANCELLATIONS", List.of("cancelled_units", "status"),
            "CORRECTIONS", List.of("field", "old_value", "new_value"), "POSITIONS", List.of("units"),
            "FEES", List.of("amount", "currency"), "CASH", List.of("amount", "currency", "entry_type"),
            "MARGIN", List.of("amount", "currency", "margin_type"), "SETTLEMENT", List.of("units", "price", "amount", "currency"));
    private static void reconcile(G7Review r, Map<String, Object> p, Map<String, Object> scheduled) throws IOException {
        String id = str(p, "period_id"); Map<String, Object> recon = r.artifactJson(p.get("reconciliation"), id, "reconciliation");
        r.missing("EXTERNAL_LEDGER_ADAPTER_UNAVAILABLE", id, "The baseline has no external-account ledger verifier. Exact declared normalized comparison does not authenticate raw exports, prove normalization, or establish account completeness; obtain independent source review.");
        if (!id.equals(recon.get("period_id"))) throw new IOException("Reconciliation period mismatch");
        coverage(r, recon, scheduled, id);
        r.artifact(recon.get("normalization_evidence"), id);
        Map<String, Object> internal = r.artifactJson(recon.get("internal_source"), id, "normalized-ledger");
        Map<String, Object> external = r.artifactJson(recon.get("external_source"), id, "normalized-ledger");
        if (internal == null || external == null) return;
        for (Map<String, Object> source : List.of(internal, external)) {
            if (!id.equals(source.get("period_id")) || !Objects.equals(source.get("normalization_evidence"), recon.get("normalization_evidence"))) throw new IOException("Normalization identity/scope mismatch");
            r.artifact(source.get("raw_source"), id);
            coverage(r, source, scheduled, id);
        }
        String insideHash = r.artifactHash(internal.get("raw_source"), id), outsideHash = r.artifactHash(external.get("raw_source"), id);
        if (internal.get("raw_source") == null || external.get("raw_source") == null) r.missing("RECONCILIATION_RAW_SOURCE_UNKNOWN", id, "Retain both exact independent original raw source records; absent source is unknown.");
        if (insideHash != null && outsideHash != null && insideHash.equals(outsideHash) || Objects.equals(recon.get("internal_source"), recon.get("external_source")))
            r.fail("SELF_RECONCILIATION", id, "Use an independently sourced original ledger and retain both originals.");
        if (recon.get("external_identity") == null) r.missing("EXTERNAL_IDENTITY_UNKNOWN", id, "Identify the independent source without claiming it authenticated.");
        Map<String, Map<String, Object>> inside = index(list(internal.get("streams")), "kind"), outside = index(list(external.get("streams")), "kind");
        Map<String, Map<String, Object>> internalRecords = allRecords(inside), externalRecords = allRecords(outside);
        Set<String> seen = new HashSet<>();
        for (Object item : list(recon.get("streams"))) {
            Map<String, Object> stream = obj(item); String kind = str(stream, "kind");
            if (!seen.add(kind)) throw new IOException("Duplicate reconciliation stream");
            for (String side : List.of("internal", "external")) {
                Map<String, Object> source = (side.equals("internal") ? inside : outside).get(kind);
                if (source == null) { r.missing("NORMALIZED_STREAM_MISSING", id + "." + kind, "Supply the complete independently normalized stream."); continue; }
                if (!Objects.equals(source.get("records"), stream.get(side)) || !Objects.equals(source.get("count"), stream.get(side + "_count"))
                        || !Objects.equals(source.get("complete"), stream.get(side + "_complete"))) throw new IOException("Reconciliation differs from normalized source bytes");
                List<Object> rows = list(stream.get(side));
                if (!Boolean.TRUE.equals(stream.get(side + "_complete")) || stream.get(side + "_count") == null)
                    r.missing("RECONCILIATION_COVERAGE_UNKNOWN", id + "." + kind + "." + side, "Obtain explicit complete coverage and count; missing is not zero.");
                else if (integer(stream.get(side + "_count")) != rows.size()) r.fail("RECONCILIATION_COUNT_MISMATCH", id + "." + kind + "." + side, "Restore all source records and explain the count mismatch.");
                records(r, rows, kind, id, scheduled, side.equals("internal") ? internalRecords : externalRecords);
            }
            Map<String, Map<String, Object>> left = index(list(stream.get("internal")), "record_id"), right = index(list(stream.get("external")), "record_id");
            boolean equal = left.equals(right) && stream.get("internal").equals(stream.get("external"));
            if (!equal) r.fail("EXTERNAL_LEDGER_MISMATCH", id + "." + kind, "Independently reconcile the exact ordered orders, events and amounts; no tolerance, imputation, reordering or financial recalculation is used.");
            r.observations.add(map("kind", "NORMALIZED_EXTERNAL_COMPARISON", "period_ref", reference("period", id), "stream", kind,
                    "equal", equal, "internal_count", stream.get("internal_count"), "external_count", stream.get("external_count"), "source_authenticated", false));
        }
        for (String kind : G7Schema.STREAMS) if (!seen.contains(kind)) r.missing("RECONCILIATION_STREAM_MISSING", id + "." + kind, "Supply the explicit stream including any source-confirmed zero count.");
    }
    private static void coverage(G7Review r, Map<String, Object> record, Map<String, Object> scheduled, String id) {
        if (record.get("coverage") == null) r.missing("RECONCILIATION_WINDOW_UNKNOWN", id, "Obtain the original exact normalized-source coverage window; null is never full coverage.");
        else if (!obj(record.get("coverage")).get("start").equals(scheduled.get("start")) || !obj(record.get("coverage")).get("end").equals(scheduled.get("end")))
            r.missing("RECONCILIATION_PARTIAL_COVERAGE", id, "Retain partial normalized coverage and obtain every scheduled original source period.");
    }
    private static Map<String, Map<String, Object>> allRecords(Map<String, Map<String, Object>> streams) throws IOException {
        Map<String, Map<String, Object>> rows = new TreeMap<>();
        for (Map<String, Object> stream : streams.values()) for (Object value : list(stream.get("records"))) {
            Map<String, Object> record = obj(value);
            if (rows.putIfAbsent(str(record, "record_id"), record) != null) throw new IOException("Ambiguous cross-stream record identity");
        }
        return rows;
    }
    private static Map<String, Map<String, Object>> index(List<Object> values, String key) throws IOException {
        Map<String, Map<String, Object>> indexed = new TreeMap<>();
        for (Object value : values) { Map<String, Object> row = obj(value); if (indexed.putIfAbsent(str(row, key), row) != null) throw new IOException("Duplicate normalized record identity"); }
        return indexed;
    }
    private static void records(G7Review r, List<Object> rows, String kind, String id, Map<String, Object> scheduled, Map<String, Map<String, Object>> originals) throws IOException {
        Set<String> seen = new HashSet<>(); Set<List<Object>> correctedFields = new HashSet<>(); Instant last = null;
        for (Object value : rows) {
            Map<String, Object> row = obj(value); String record = str(row, "record_id"); Instant at = time(row.get("at"));
            if (!seen.add(record)) throw new IOException("Duplicate normalized record");
            if (last != null && at.isBefore(last) || at.isBefore(time(scheduled.get("start"))) || at.isAfter(time(scheduled.get("end")))) throw new IOException("Record outside period or reordered");
            last = at; Map<String, Object> values = obj(row.get("values"));
            if (Set.of("ORDERS", "ACKNOWLEDGEMENTS", "EXECUTIONS", "CANCELLATIONS", "CORRECTIONS").contains(kind) && row.get("order_id") == null
                    || Set.of("ORDERS", "EXECUTIONS", "POSITIONS", "MARGIN", "SETTLEMENT").contains(kind) && row.get("instrument_id") == null)
                r.missing("NORMALIZED_IDENTITY_UNKNOWN", id + "." + kind + "." + record, "Obtain the original order/instrument identity required for this stream; absent identifiers are not inferred.");
            if (!values.keySet().equals(new HashSet<>(FIELDS.get(kind)))) throw new IOException("Unexpected normalized value fields");
            for (var entry : values.entrySet()) {
                if (entry.getValue() == null) r.missing("NORMALIZED_VALUE_UNKNOWN", id + "." + kind + "." + record, "Obtain the missing original value; do not infer zero.");
                else if (Set.of("units", "cancelled_units", "price", "amount").contains(entry.getKey())) {
                    String text = (String) entry.getValue();
                    if (!text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?") || text.length() > 128) throw new IOException("Invalid exact decimal string");
                    new BigDecimal(text); // Validation only; no accounting arithmetic.
                }
            }
            if (kind.equals("CORRECTIONS")) {
                Map<String, Object> prior = originals.get(str(row, "prior_record_id"));
                if (prior == null || record.equals(row.get("prior_record_id")) || time(prior.get("at")).isAfter(at)
                        || !Objects.equals(prior.get("order_id"), row.get("order_id")) || !Objects.equals(prior.get("instrument_id"), row.get("instrument_id"))
                        || !correctedFields.add(Arrays.asList(row.get("prior_record_id"), values.get("field")))
                        || !obj(prior.get("values")).containsKey(values.get("field"))
                        || !Objects.equals(obj(prior.get("values")).get(values.get("field")), values.get("old_value"))
                        || Objects.equals(values.get("old_value"), values.get("new_value")))
                    r.fail("INVALID_EXTERNAL_CORRECTION", id + "." + record, "Preserve the exact prior original record, field and old/new values; corrections cannot overwrite, invent or silently reorder originals.");
            }
        }
    }
}
