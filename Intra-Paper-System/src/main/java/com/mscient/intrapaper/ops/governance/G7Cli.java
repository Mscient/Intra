package com.mscient.intrapaper.ops.governance;

import static com.mscient.intrapaper.ops.governance.G7Json.*;
import com.mscient.intrapaper.provenance.BuildInfo;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Standalone local-files CLI. It has no credentials, broker client, network request or operating-control dependency. */
public final class G7Cli {
    private G7Cli() { }
    public static void main(String[] args) { System.exit(run(args, System.out)); }
    public static int run(String[] args, PrintStream out) {
        try {
            if ((args.length == 1 || args.length == 2) && args[0].equals("explain")) {
                Map<String, Object> result = explanation();
                if (args.length == 2) {
                    Map<String, Object> diagnostic = evaluate(Path.of(args[1]), null);
                    result.put("status", diagnostic.get("status")); result.put("findings", diagnostic.get("findings"));
                    result.put("input_hashes", diagnostic.get("input_hashes"));
                }
                out.write(bytes(result)); return G7Review.exitCode(result);
            }
            if ((args.length == 3 || args.length == 4) && args[0].equals("evaluate")) {
                Path dossier = G7Files.local(Path.of(args[1]));
                G7Files.External extension = args.length == 4 ? new G7Files.External(Path.of(args[3])) : null;
                try (G7Files input = G7Files.load(dossier)) {
                    byte[] history = extension == null ? input.files.get("history.json") : extension.bytes;
                    if (history == null) throw new IOException("Required history missing");
                    Map<String, Object> report = new G7Review(input, history).evaluate();
                    byte[] rendered = bytes(report);
                    Map<String, byte[]> outputs = Map.of("report.json", rendered,
                            "artifact.json", bytes(artifact(report, rendered, history)));
                    G7Files.publish(Path.of(args[2]), dossier, outputs, () -> { input.unchanged(); if (extension != null) extension.unchanged(); unchangedCode(report); });
                    out.write(bytes(summary(report, "PUBLISHED_DIAGNOSTIC", hash(rendered)))); return G7Review.exitCode(report);
                }
            }
            if ((args.length == 3 || args.length == 4) && args[0].equals("verify")) {
                Map<String, Object> result = verify(Path.of(args[1]), Path.of(args[2]), args.length == 4 ? Path.of(args[3]) : null);
                out.write(bytes(result)); return (Integer) result.get("verification_exit_code");
            }
            if (args.length == 5 && args[0].equals("append")) {
                Path dossier = G7Files.local(Path.of(args[1])); G7Files.External old = new G7Files.External(Path.of(args[2]));
                G7Files.External draft = new G7Files.External(Path.of(args[3])); Object eventValue = parse(draft.bytes); G7Schema.validate("event", eventValue);
                try (G7Files input = G7Files.load(dossier)) {
                    G7Review before = new G7Review(input, old.bytes);
                    Map<String, Object> next = before.ledger.append(obj(eventValue)); byte[] extended = bytes(next);
                    // Evaluate the new generation before publication, including all bindings and signatures.
                    Map<String, Object> report = new G7Review(input, extended).evaluate();
                    if (obj(eventValue).get("decision") != null) {
                        List<Object> previousFindings = list(before.evaluate().get("findings"));
                        boolean newFailure = list(report.get("findings")).stream().map(G7Json::obj)
                                .anyMatch(f -> f.get("status").equals("FAIL") && !previousFindings.contains(f));
                        if (newFailure) throw new IOException("Decision binding rejected; prior diagnostic failures may still be reviewed/rejected");
                    }
                    G7Files.publish(Path.of(args[4]), dossier, Map.of("history.json", extended), () -> { input.unchanged(); old.unchanged(); draft.unchanged(); unchangedCode(report); });
                    out.write(bytes(summary(report, "APPENDED_NEW_GENERATION", null))); return G7Review.exitCode(report);
                }
            }
            throw new IllegalArgumentException("Unsupported command or arity");
        } catch (IOException | RuntimeException invalid) {
            // Deliberately omit exception text, absolute paths and caller values from public diagnostics.
            out.print(DailyFailure.JSON + "\n"); return 1;
        }
    }
    private static final class DailyFailure {
        static final String JSON = "{\"g7_closed\":false,\"g7_proposed\":true,\"operation_authorized\":false,\"scale_authorized\":false,\"schema\":\"g7-error/v1\",\"severity\":null,\"status\":\"FAIL\",\"required_action\":\"Input, history, output or invocation rejected. Validate strict schemas, local paths, exact bytes, chronology and no-overwrite rules. G7 remains a proposal. No authority granted.\"}";
    }
    private static Map<String, Object> explanation() {
        return map("schema", "g7-explanation/v1", "status", "INCOMPLETE", "g7_proposed", true, "g7_closed", false,
                "operation_authorized", false, "scale_authorized", false, "limits", G7Review.LIMITS,
                "commands", List.of("evaluate <dossier> <new-output> [history.json]", "verify <dossier> <output> [latest-history.json]",
                        "explain [dossier]", "append <dossier> <history.json> <event.json> <new-output>"),
                "required_contracts", List.of("manifest.json:g7-manifest/v1", "bundle.json:g7-bundle/v1", "history.json:g7-history/v1"),
                "required_gates", G7Schema.GATES, "envelope_domains", G7Schema.DOMAINS, "period_evidence_categories", G7Schema.CATEGORIES,
                "status_precedence", List.of("FAIL", "BLOCKED", "INCOMPLETE", "ELIGIBLE_FOR_HUMAN_REVIEW"),
                "status_meanings", map("FAIL", "Known inconsistency, anomaly or rejected input. Preserve evidence and escalate under reviewed policy.",
                    "BLOCKED", "Prerequisite, drift, incident, revocation or authentication block requires separate human disposition/requalification.",
                    "INCOMPLETE", "Missing/unknown/stale/partial evidence requires original records; unknown is never zero or healthy.",
                    "ELIGIBLE_FOR_HUMAN_REVIEW", "Reserved and unavailable: this revision has no provisioned G0-G6/source/owner/reviewer authentication adapters."),
                "exit_codes", map("eligible_reserved", 0, "fail", 1, "incomplete", 2, "blocked", 3),
                "verification_exit_meaning", "0 means exact byte recomputation only; 3 means a retained terminal revocation; neither grants authority.",
                "schema_names", new ArrayList<>(G7Schema.names()), "publication_files", List.of("report.json", "artifact.json"));
    }
    private static void unchangedCode(Map<String, Object> report) throws IOException {
        if (!Objects.equals(report.get("code_sha256"), codeHash())) throw new IOException("Code artifact changed during review");
    }
    public static Map<String, Object> evaluate(Path dossier, byte[] historyOverride) throws IOException {
        try (G7Files input = G7Files.load(dossier)) {
            byte[] history = historyOverride == null ? input.files.get("history.json") : historyOverride;
            if (history == null) throw new IOException("Required history missing");
            Map<String, Object> result = new G7Review(input, history).evaluate(); input.unchanged(); unchangedCode(result); return result;
        }
    }
    private static Map<String, Object> artifact(Map<String, Object> report, byte[] rendered, byte[] history) throws IOException {
        return map("schema", "g7-publication/v1", "report_sha256", hash(rendered), "report_size_bytes", (long) rendered.length,
                "history_sha256", hash(history), "history_size_bytes", (long) history.length, "history_event_count", list(obj(parse(history)).get("events")).size(),
                "input_hashes", report.get("input_hashes"), "input_sizes", report.get("input_sizes"), "history_head", report.get("history_head"),
                "dossier_history_sha256", report.get("dossier_history_sha256"),
                "build_commit", report.get("build_commit"), "code_sha256", report.get("code_sha256"),
                "g7_closed", false, "operation_authorized", false, "scale_authorized", false);
    }
    public static Map<String, Object> verify(Path dossier, Path output, Path extension) throws IOException {
        Path inputPath = G7Files.local(dossier), outputPath = G7Files.local(output);
        if (inputPath.startsWith(outputPath) || outputPath.startsWith(inputPath)) throw new IOException("Separate input/report directories required");
        G7Files.External latestFile = extension == null ? null : new G7Files.External(extension);
        try (G7Files saved = G7Files.load(output); G7Files input = G7Files.load(dossier)) {
            if (!saved.files.keySet().equals(Set.of("report.json", "artifact.json"))) throw new IOException("Unexpected or missing report files");
            Object metadata = parse(saved.files.get("artifact.json")); G7Schema.validate("publication", metadata);
            byte[] latest = latestFile == null ? input.files.get("history.json") : latestFile.bytes;
            if (latest == null) throw new IOException("Latest history required");
            G7Review current = new G7Review(input, latest);
            long count = integer(obj(metadata).get("history_event_count"));
            if (count > current.ledger.events.size()) throw new IOException("Saved review history was truncated");
            byte[] original = current.ledger.prefix((int) count);
            if (!hash(original).equals(obj(metadata).get("history_sha256")) || original.length != integer(obj(metadata).get("history_size_bytes")))
                throw new IOException("Retained history prefix differs from reviewed bytes");
            Map<String, Object> recomputed = new G7Review(input, original).evaluate(); byte[] report = bytes(recomputed);
            if (!Arrays.equals(report, saved.files.get("report.json")) || !Arrays.equals(bytes(artifact(recomputed, report, original)), saved.files.get("artifact.json")))
                throw new IOException("Report bytes, hashes, build or artifacts changed after evaluation");
            List<Object> oldEvents = list(obj(parse(original)).get("events"));
            if (current.ledger.events.size() < oldEvents.size() || !current.ledger.events.subList(0, oldEvents.size()).equals(oldEvents)) throw new IOException("History prefix changed or truncated");
            for (int i = oldEvents.size(); i < current.ledger.events.size(); i++) if (!Set.of("REVIEWER_DECISION", "OWNER_DECISION", "REVOCATION").contains(current.ledger.events.get(i).get("kind")))
                throw new IOException("Evidence addition after evaluation requires a new review and report");
            Map<String, Object> currentReport = Arrays.equals(latest, original) ? recomputed : current.evaluate();
            input.unchanged(); saved.unchanged();
            if (latestFile != null) latestFile.unchanged(); unchangedCode(currentReport);
            boolean invalidDecision = list(currentReport.get("findings")).stream().map(G7Json::obj)
                    .anyMatch(f -> f.get("status").equals("FAIL") && !list(recomputed.get("findings")).contains(f));
            boolean revoked = list(currentReport.get("findings")).stream().map(G7Json::obj).anyMatch(f -> f.get("code").equals("REVIEW_REVOKED"));
            Map<String, Object> result = summary(currentReport, revoked ? "BYTE_IDENTICAL_REVOKED_UNAUTHENTICATED" : "BYTE_IDENTICAL_UNAUTHENTICATED", hash(report));
            result.put("verification_exit_code", invalidDecision ? 1 : revoked ? 3 : 0);
            result.put("reviewed_history_head", recomputed.get("history_head")); result.put("latest_history_head", current.ledger.head);
            result.put("findings", currentReport.get("findings")); return result;
        }
    }
    private static Map<String, Object> summary(Map<String, Object> report, String verification, String reportHash) {
        return map("schema", "g7-result/v1", "review_id", report.get("review_id"), "status", report.get("status"),
                "verification", verification, "report_sha256", reportHash, "g7_proposed", true, "g7_closed", false,
                "operation_authorized", false, "scale_authorized", false, "limits", G7Review.LIMITS);
    }
    /** Hash the locally loaded JAR, including verifier dependencies. A file URI is inspected, never opened as a URL. */
    static String codeHash() throws IOException {
        try {
            var location = G7Cli.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            if (!location.getScheme().equals("file") || location.getAuthority() != null) throw new IOException("G7 must load from local code");
            Path code = G7Files.local(Path.of(location));
            if (!Files.isRegularFile(code) || !code.getFileName().toString().endsWith(".jar"))
                throw new IOException("A single local packaged JAR is required");
            for (Class<?> dependency : List.of(G7Files.class, G7Json.class, G7Schema.class, G7Ledger.class, G7Review.class, G7Adapters.class,
                    BuildInfo.class, com.mscient.intrapaper.data.StrictJsonParser.class, com.mscient.intrapaper.data.CsvTable.class,
                    com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceReport.class, com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceChecker.class,
                    com.mscient.intrapaper.audit.feed.FeedAudit.class,
                    com.mscient.intrapaper.simulation.portfolio.PortfolioReplayArtifactVerifier.class,
                    com.mscient.intrapaper.simulation.portfolio.PortfolioReplayDeterminismVerifier.class,
                    com.mscient.intrapaper.simulation.portfolio.PortfolioReplayCsvRunner.class,
                    com.mscient.intrapaper.audit.portfolio.PortfolioRiskAudit.class,
                    com.mscient.intrapaper.audit.portfolio.PortfolioRiskAuditArtifacts.class,
                    com.mscient.intrapaper.audit.portfolio.PortfolioSettlementAudit.class,
                    com.mscient.intrapaper.audit.portfolio.PortfolioSettlementArtifacts.class)) {
                var source = dependency.getProtectionDomain().getCodeSource();
                if (source == null || !location.equals(source.getLocation().toURI()))
                    throw new IOException("Mixed verifier code origins are forbidden");
            }
            return hash(G7Files.read(code));
        } catch (java.net.URISyntaxException invalid) { throw new IOException("Invalid local code identity", invalid); }
    }
    static String buildCommit() throws IOException {
        try {
            Path code = G7Files.local(Path.of(G7Cli.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
            try (var jar = new java.util.jar.JarFile(code.toFile(), false)) {
                var manifest = jar.getManifest();
                return manifest == null ? "unknown" : Objects.toString(manifest.getMainAttributes().getValue("Build-Commit"), "unknown");
            }
        } catch (java.net.URISyntaxException invalid) { throw new IOException("Invalid local build identity", invalid); }
    }
}
