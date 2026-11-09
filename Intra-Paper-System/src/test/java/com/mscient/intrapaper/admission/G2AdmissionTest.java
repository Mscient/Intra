package com.mscient.intrapaper.admission;

import com.mscient.intrapaper.audit.feed.FeedAudit;
import com.mscient.intrapaper.audit.feed.FeedAuditArtifacts;
import com.mscient.intrapaper.marketdata.RawCaptureJournalReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static com.mscient.intrapaper.admission.AdmissionJson.*;

/** Synthetic-only acceptance for the cross-domain admission sidecar. */
public final class G2AdmissionTest {
    private static int checks;
    private static final String ASSESSMENT = "2026-11-06T10:00:31Z";
    private interface Action { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath(); Files.createDirectories(output);
        Path valid = output.resolve("valid-input");
        Map<String, Object> input = buildBundle(valid, Path.of(args[1]).toAbsolutePath());
        Path manifest = writeInput(valid, input);

        String rawBefore = hash(Files.readAllBytes(valid.resolve("feed/capture.bin")));
        check(RawCaptureJournalReader.replay(valid.resolve("feed/capture.bin"), ignored -> { }).eventCount() > 0,
                "existing raw v1 remains readable");
        G2Admission.Result pass = G2Admission.run(manifest);
        check(pass.status().equals("PASS"), "valid joined synthetic admission passes configured checks");
        check(Boolean.FALSE.equals(pass.report().get("release_authorized")), "admission never grants release authority");
        check(Boolean.FALSE.equals(pass.report().get("g2_closed")), "admission never closes G2");
        check(Boolean.FALSE.equals(pass.report().get("permission_authenticated")), "declaration match is not authenticated permission");
        check(pass.report().get("unknown_fields").equals(List.of()), "complete synthetic contract has no unknown fields");
        check(hash(Files.readAllBytes(valid.resolve("feed/capture.bin"))).equals(rawBefore), "admission does not rewrite raw v1 bytes");
        check(Arrays.equals(pass.bytes(), G2Admission.run(manifest).bytes()), "deterministic repeat report");

        Path artifact = output.resolve("valid-artifact");
        G2AdmissionArtifacts.publish(manifest, artifact, pass);
        G2AdmissionArtifacts.verify(manifest, artifact); checks++;
        reject(() -> G2AdmissionArtifacts.publish(manifest, valid.resolve("inside-input"), pass), "publication inside input bundle");
        check(!Files.exists(valid.resolve("inside-input")), "failed publication leaves no complete target");

        Path relocated = output.resolve("relocated-input"); copyTree(valid, relocated);
        G2Admission.Result relocatedResult = G2Admission.run(relocated.resolve("admission.json"));
        check(Arrays.equals(pass.bytes(), relocatedResult.bytes()), "relocation preserves deterministic bytes");

        Path unknown = variant(valid, output.resolve("unknown-source"));
        Map<String, Object> unknownInput = readInput(unknown);
        object(unknownInput.get("capture")).put("source_id", null); writeInput(unknown, unknownInput);
        G2Admission.Result unknownResult = G2Admission.run(unknown.resolve("admission.json"));
        check(unknownResult.status().equals("INCOMPLETE") && codes(unknownResult).contains("IDENTITY_UNKNOWN"),
                "unknown source identity stays unknown and incomplete");

        Path denied = variant(valid, output.resolve("permission-denied"));
        Map<String, Object> deniedInput = readInput(denied);
        Map<String, Object> rawResource = resource(deniedInput, "RAW_INPUT");
        Map<String, Object> rawPermission = object(rawResource.get("permission"));
        Path deniedEvidence = denied.resolve(string(rawPermission.get("evidence_path")));
        Map<String, Object> deniedEvidenceJson = parse(deniedEvidence);
        object(list(deniedEvidenceJson.get("records")).getFirst()).put("effect", "DENY");
        Files.write(deniedEvidence, bytes(deniedEvidenceJson)); Files.delete(denied.resolve(string(rawResource.get("path"))));
        G2Admission.Result deniedResult = G2Admission.run(denied.resolve("admission.json"));
        check(deniedResult.status().equals("FAIL") && codes(deniedResult).contains("PERMISSION_DENIED"),
                "permission denial fails without opening the missing denied dataset");

        Path incomplete = variant(valid, output.resolve("permission-incomplete"));
        Map<String, Object> incompleteInput = readInput(incomplete);
        Map<String, Object> mappingResource = resource(incompleteInput, "TOKEN_MAPPING");
        Map<String, Object> mappingPermission = object(mappingResource.get("permission"));
        Path incompleteEvidence = incomplete.resolve(string(mappingPermission.get("evidence_path")));
        Map<String, Object> incompleteEvidenceJson = parse(incompleteEvidence);
        incompleteEvidenceJson.put("completeness", "UNKNOWN"); Files.write(incompleteEvidence, bytes(incompleteEvidenceJson));
        Files.delete(incomplete.resolve(string(mappingResource.get("path"))));
        G2Admission.Result incompleteResult = G2Admission.run(incomplete.resolve("admission.json"));
        check(incompleteResult.status().equals("INCOMPLETE") && codes(incompleteResult).contains("PERMISSION_INCOMPLETE"),
                "missing permission evidence is incomplete and does not open the dataset");

        Path stale = variant(valid, output.resolve("stale-evidence"));
        Map<String, Object> staleInput = readInput(stale);
        resource(staleInput, "NORMALIZED_DATA").put("observed_at_utc", "2026-11-06T09:00:00Z"); writeInput(stale, staleInput);
        check(codes(G2Admission.run(stale.resolve("admission.json"))).contains("STALE_EVIDENCE"), "stale evidence fails closed");

        Path future = variant(valid, output.resolve("future-evidence"));
        Map<String, Object> futureInput = readInput(future);
        object(list(futureInput.get("events")).getLast()).put("observed_at_utc", "2026-11-06T10:05:00Z"); writeInput(future, futureInput);
        check(codes(G2Admission.run(future.resolve("admission.json"))).contains("FUTURE_EVIDENCE"), "future evidence fails closed");

        Path order = variant(valid, output.resolve("event-order"));
        Map<String, Object> orderInput = readInput(order);
        object(list(orderInput.get("events")).get(4)).put("observed_at_utc", "2026-11-06T10:00:10Z"); writeInput(order, orderInput);
        check(codes(G2Admission.run(order.resolve("admission.json"))).contains("EVENT_ORDER_REGRESSION"), "out-of-order event is reported");

        Path tamperedData = variant(valid, output.resolve("tampered-data"));
        Files.writeString(tamperedData.resolve("data/normalized.csv"), "tamper", StandardOpenOption.APPEND);
        check(codes(G2Admission.run(tamperedData.resolve("admission.json"))).contains("RESOURCE_HASH_MISMATCH"),
                "tampered dataset fails exact byte binding before use");

        Path tamperedFeed = variant(valid, output.resolve("tampered-feed-artifact"));
        Files.writeString(tamperedFeed.resolve("feed-artifact/report.json"), " ", StandardOpenOption.APPEND);
        check(codes(G2Admission.run(tamperedFeed.resolve("admission.json"))).contains("FEED_AUDIT_INVALID"),
                "tampered feed audit is detected");

        Path duplicate = variant(valid, output.resolve("duplicate-event"));
        Map<String, Object> duplicateInput = readInput(duplicate);
        object(list(duplicateInput.get("events")).get(1)).put("event_id", "EVENT-0"); writeInput(duplicate, duplicateInput);
        reject(() -> G2Admission.run(duplicate.resolve("admission.json")), "duplicate event ID");

        Path conflicting = variant(valid, output.resolve("conflicting-event"));
        Map<String, Object> conflictingInput = readInput(conflicting);
        Map<String, Object> second = object(list(conflictingInput.get("events")).get(1));
        Map<String, Object> first = object(list(conflictingInput.get("events")).getFirst());
        second.put("resource_id", first.get("resource_id")); second.put("record_ordinal", first.get("record_ordinal")); writeInput(conflicting, conflictingInput);
        reject(() -> G2Admission.run(conflicting.resolve("admission.json")), "duplicate/conflicting record reference");

        Path traversal = variant(valid, output.resolve("path-traversal"));
        Map<String, Object> traversalInput = readInput(traversal);
        resource(traversalInput, "NORMALIZED_DATA").put("path", "../outside.csv"); writeInput(traversal, traversalInput);
        reject(() -> G2Admission.run(traversal.resolve("admission.json")), "path traversal");

        Path malformed = variant(valid, output.resolve("unknown-field"));
        Map<String, Object> malformedInput = readInput(malformed); malformedInput.put("unexpected", true); writeInput(malformed, malformedInput);
        reject(() -> G2Admission.run(malformed.resolve("admission.json")), "unknown field");

        Path nonFinite = output.resolve("non-finite.json");
        String invalidNumber = new String(bytes(input), StandardCharsets.UTF_8).replace("\"max_resource_bytes\":1048576", "\"max_resource_bytes\":NaN");
        Files.writeString(nonFinite, invalidNumber, StandardCharsets.UTF_8); reject(() -> G2Admission.run(nonFinite), "non-finite number");

        Path oversized = output.resolve("oversized.json"); Files.write(oversized, new byte[AdmissionJson.MAX_MANIFEST_BYTES + 1]);
        reject(() -> G2Admission.run(oversized), "oversized manifest");

        Path corruptArtifact = output.resolve("corrupt-artifact"); copyTree(artifact, corruptArtifact);
        Files.writeString(corruptArtifact.resolve("report.json"), " ", StandardOpenOption.APPEND);
        reject(() -> G2AdmissionArtifacts.verify(manifest, corruptArtifact), "tampered admission report");
        reject(() -> G2AdmissionArtifacts.publish(manifest, artifact, pass), "overwrite existing artifact");
        reject(() -> G2AdmissionArtifacts.publish(manifest, output.resolve("forged-artifact"),
                new G2Admission.Result("PASS", pass.reportId(), pass.report(), bytes(Map.of("status", "PASS")))), "forged result publication");
        check(!Files.exists(output.resolve("forged-artifact")), "forged publication creates no output");

        expectCode(valid, output, "event-hash", m -> object(list(m.get("events")).getFirst()).put("record_sha256", "0".repeat(64)), "EVENT_BYTES_MISMATCH");
        expectCode(valid, output, "event-range", m -> object(list(m.get("events")).getFirst()).put("byte_length", Long.MAX_VALUE), "EVENT_BYTES_MISMATCH");
        expectCode(valid, output, "decoder-hash", m -> object(m.get("processing")).put("decoder_sha256", "0".repeat(64)), "PROCESSING_BYTES_UNVERIFIED");
        expectCode(valid, output, "schema-resource", m -> object(m.get("processing")).put("schema_resource_id", "RAW"), "PROCESSING_BYTES_UNVERIFIED");
        expectCode(valid, output, "build-unknown", m -> object(m.get("processing")).put("build_sha256", null), "PROCESSING_HASH_UNKNOWN");
        expectCode(valid, output, "replay-id", m -> object(m.get("replay_dataset")).put("dataset_id", "OTHER"), "REPLAY_IDENTITY_MISMATCH");
        expectCode(valid, output, "replay-hash", m -> object(m.get("replay_dataset")).put("normalized_sha256", "0".repeat(64)), "REPLAY_DATASET_BINDING_MISMATCH");
        expectCode(valid, output, "feed-revision", m -> object(m.get("feed_audit")).put("schedule_revision", "2"), "FEED_AUDIT_IDENTITY_MISMATCH");
        expectCode(valid, output, "feed-future", m -> object(m.get("feed_audit")).put("observed_at_utc", "2027-01-01T00:00:00Z"), "FUTURE_EVIDENCE");
        expectCode(valid, output, "source-conflict", m -> object(m.get("capture")).put("source_id", "OTHER"), "PERMISSION_BINDING_MISMATCH");
        expectCode(valid, output, "assessment-date", m -> m.put("assessment_time_utc", "2026-11-07T10:00:31Z"), "PERMISSION_BINDING_MISMATCH");
        expectCode(valid, output, "coverage-gap", m -> object(m.get("coverage")).put("observed_start_utc", "2026-11-06T10:00:01Z"), "REQUIRED_COVERAGE_MISSING");
        expectCode(valid, output, "coverage-future", m -> object(m.get("coverage")).put("observed_end_utc", "2026-11-06T10:10:00Z"), "FUTURE_COVERAGE");
        expectCode(valid, output, "schedule-range", m -> object(m.get("coverage")).put("required_start_utc", "2026-11-06T10:00:01Z"), "FEED_COVERAGE_MISMATCH");
        expectCode(valid, output, "event-time-unknown", m -> {
            Map<String, Object> e = object(list(m.get("events")).getFirst()); e.put("event_time_utc", null); e.put("event_time_status", "UNKNOWN");
        }, "EVENT_TIME_UNKNOWN");
        expectCode(valid, output, "event-domain-missing", m -> object(list(m.get("events")).getLast()).put("domain", "CAPTURE"), "EVENT_DOMAIN_MISSING");

        Path caller = variant(valid, output.resolve("caller-declared")); Map<String, Object> callerInput = readInput(caller);
        callerInput.put("classification", "CALLER_DECLARED");
        for (Object row : list(callerInput.get("resources"))) {
            Map<String, Object> r = object(row); Map<String, Object> p = object(r.get("permission"));
            Path mp = caller.resolve(string(p.get("manifest_path"))); Map<String, Object> dm = parse(mp);
            dm.put("classification", "CALLER_DECLARED"); Files.write(mp, bytes(dm)); String mh = hash(bytes(dm));
            Path up = caller.resolve(string(p.get("intended_use_path"))); Map<String, Object> u = parse(up); u.put("manifest_sha256", mh); Files.write(up, bytes(u));
            Path ep = caller.resolve(string(p.get("evidence_path"))); Map<String, Object> ev = parse(ep); ev.put("classification", "CALLER_DECLARED");
            object(list(ev.get("records")).getFirst()).put("manifest_sha256", mh); Files.write(ep, bytes(ev));
        }
        writeInput(caller, callerInput); G2Admission.Result callerResult = G2Admission.run(caller.resolve("admission.json"));
        check(callerResult.status().equals("INCOMPLETE") && codes(callerResult).contains("UNAUTHENTICATED_EVIDENCE"), "all-positive unauthenticated declarations never PASS");

        Path undeclared = variant(valid, output.resolve("undeclared-feed-reference")); Map<String, Object> undeclaredInput = readInput(undeclared);
        Map<String, Object> provenance = list(undeclaredInput.get("resources")).stream().map(AdmissionJson::object)
                .filter(r -> r.get("logical_id").equals("PROVENANCE")).findFirst().orElseThrow();
        provenance.put("path", "data/other.txt"); Files.copy(undeclared.resolve("feed/provenance.txt"), undeclared.resolve("data/other.txt"));
        writeInput(undeclared, undeclaredInput);
        check(codes(G2Admission.run(undeclared.resolve("admission.json"))).contains("FEED_AUDIT_INVALID"), "feed cannot open unadmitted original reference even when original exists");

        for (String badPath : List.of("/absolute", "C:/absolute", "data/./file", "data/../file", "data/NUL.txt", "data/file.", "data/file:stream", "data\\file"))
            reject(() -> relative(badPath), "unsafe path " + badPath);
        reject(() -> readBounded(manifest, Integer.MAX_VALUE), "read bound overflow");
        Path duplicateKey = output.resolve("duplicate-key.json"); Files.writeString(duplicateKey, "{\"schema\":1,\"schema\":2}");
        reject(() -> G2Admission.run(duplicateKey), "duplicate JSON key");
        Path invalidUtf8 = output.resolve("invalid-utf8.json"); Files.write(invalidUtf8, new byte[]{(byte)0xc3, 0x28});
        reject(() -> G2Admission.run(invalidUtf8), "invalid UTF-8");
        Path extraArtifact = output.resolve("extra-artifact"); copyTree(artifact, extraArtifact); Files.writeString(extraArtifact.resolve("extra.txt"), "extra");
        reject(() -> G2AdmissionArtifacts.verify(manifest, extraArtifact), "unexpected artifact member");
        java.io.ByteArrayOutputStream outputBytes = new java.io.ByteArrayOutputStream();
        java.io.PrintStream sink = new java.io.PrintStream(outputBytes);
        check(G2AdmissionCli.run(new String[]{"verify", manifest.toString()}, sink, sink) == 0, "CLI configured synthetic PASS exit");
        check(G2AdmissionCli.run(new String[]{"verify", caller.resolve("admission.json").toString()}, sink, sink) == 3, "CLI INCOMPLETE exit");
        check(G2AdmissionCli.run(new String[]{"verify", tamperedData.resolve("admission.json").toString()}, sink, sink) == 2, "CLI FAIL exit");
        check(G2AdmissionCli.run(new String[]{"verify", invalidUtf8.toString()}, sink, sink) == 65, "CLI malformed exit");
        check(G2AdmissionCli.run(new String[]{"unknown", manifest.toString()}, sink, sink) == 64, "CLI usage exit");
        check(G2AdmissionCli.run(new String[]{"verify-artifact", manifest.toString(), artifact.toString()}, sink, sink) == 0, "CLI artifact verification");

        System.out.println("G2 admission: " + checks + " checks PASS; SYNTHETIC_OFFLINE_ONLY; G2_INCOMPLETE");
    }

    private static Map<String, Object> buildBundle(Path root, Path feedExample) throws Exception {
        Files.createDirectories(root); Path feed = root.resolve("feed"); copyTree(feedExample, feed);
        FeedAuditArtifacts.publish(feed.resolve("evidence.json"), root.resolve("feed-artifact"), FeedAudit.run(feed.resolve("evidence.json")));
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("data/normalized.csv"), "timestamp,instrument_id,value\n2026-11-06T10:00:00Z,I1,1\n");
        Files.writeString(root.resolve("data/mapping.csv"), "token,instrument_id\n123,I1\n");
        Files.writeString(root.resolve("data/calendar.csv"), "date,timezone,start,end\n2026-11-06,UTC,10:00:00,10:00:30\n");
        Files.writeString(root.resolve("data/schema.txt"), "synthetic-schema-v1\n");
        Files.writeString(root.resolve("data/build.txt"), "synthetic-processing-build-v1\n");

        List<Map<String, Object>> definitions = List.of(
                resourceDefinition(0, "RAW", "RAW_INPUT", "feed/capture.bin", 4096),
                resourceDefinition(1, "LIFECYCLE", "LIFECYCLE_INPUT", "feed/lifecycle.bin", 4096),
                resourceDefinition(2, "NORMALIZED", "NORMALIZED_DATA", "data/normalized.csv", 4096),
                resourceDefinition(3, "MAPPING", "TOKEN_MAPPING", "data/mapping.csv", 4096),
                resourceDefinition(4, "CALENDAR", "SESSION_CALENDAR", "data/calendar.csv", 4096),
                resourceDefinition(5, "DECODER", "DECODER", "feed/decode.txt", 4096),
                resourceDefinition(6, "SCHEMA", "SCHEMA", "data/schema.txt", 4096),
                resourceDefinition(7, "BUILD", "BUILD", "data/build.txt", 4096),
                resourceDefinition(8, "FEED", "FEED_EVIDENCE", "feed/evidence.json", 4096),
                resourceDefinition(9, "SCHEDULE", "FEED_REFERENCE", "feed/schedule.json", 4096),
                resourceDefinition(10, "POLICY", "FEED_REFERENCE", "feed/policy.json", 4096),
                resourceDefinition(11, "PROVENANCE", "FEED_REFERENCE", "feed/provenance.txt", 4096));
        for (Map<String, Object> definition : definitions) createPermission(root, definition);

        Map<String, Object> capture = new TreeMap<>();
        capture.put("capture_id", "CAPTURE-1"); capture.put("session_id", "SESSION-20261106");
        capture.put("process_id", "PROCESS-CAPTURE"); capture.put("process_epoch", "EPOCH-1");
        capture.put("provider_id", "SYNTHETIC_FIXTURE"); capture.put("source_id", "SYNTHETIC");
        capture.put("product_id", "TEST"); capture.put("channel_id", "L1");
        capture.put("connection_id", "c1"); capture.put("subscription_id", "sub1");
        Map<String, Object> processing = new TreeMap<>();
        processing.put("decoder_id", "AUDIT_EVENT_V1"); processing.put("decoder_version", "V1");
        processing.put("decoder_sha256", definitions.get(5).get("sha256")); processing.put("decoder_resource_id", "DECODER");
        processing.put("schema_id", "G2_EVENT_SIDECAR"); processing.put("schema_version", "V1");
        processing.put("schema_sha256", definitions.get(6).get("sha256")); processing.put("schema_resource_id", "SCHEMA");
        processing.put("build_id", "SYNTHETIC_BUILD"); processing.put("build_sha256", definitions.get(7).get("sha256"));
        processing.put("build_resource_id", "BUILD");
        Map<String, Object> coverage = new TreeMap<>();
        coverage.put("requested_start_utc", "2026-11-06T10:00:00Z"); coverage.put("requested_end_utc", "2026-11-06T10:00:30Z");
        coverage.put("required_start_utc", "2026-11-06T10:00:00Z"); coverage.put("required_end_utc", "2026-11-06T10:00:30Z");
        coverage.put("observed_start_utc", "2026-11-06T10:00:00Z"); coverage.put("observed_end_utc", "2026-11-06T10:00:30Z");
        coverage.put("timezone", "UTC"); coverage.put("interval_nanos", 1_000_000_000L);
        List<Object> events = new ArrayList<>();
        String[] domains = {"CAPTURE", "DECODE", "NORMALIZE", "MAPPING", "SESSION", "PERMISSION", "REPLAY"};
        String[] resourceIds = {"RAW", "RAW", "NORMALIZED", "MAPPING", "CALENDAR", "LIFECYCLE", "NORMALIZED"};
        for (int i = 0; i < domains.length; i++) {
            String observed = String.format(Locale.ROOT, "2026-11-06T10:00:%02dZ", 20 + i);
            String eventResourceId = resourceIds[i];
            Map<String, Object> definition = definitions.stream().filter(d -> d.get("logical_id").equals(eventResourceId)).findFirst().orElseThrow();
            byte[] eventBytes = Files.readAllBytes(root.resolve(string(definition.get("path"))));
            events.add(new TreeMap<>(Map.ofEntries(Map.entry("order", i), Map.entry("event_id", "EVENT-" + i),
                    Map.entry("domain", domains[i]), Map.entry("event_type", domains[i] + "_BOUND"),
                    Map.entry("observed_at_utc", observed), Map.entry("event_time_utc", "2026-11-06T10:00:00Z"),
                    Map.entry("event_time_status", "OBSERVED"), Map.entry("resource_id", resourceIds[i]),
                    Map.entry("record_ordinal", i), Map.entry("record_sha256", hash(Arrays.copyOfRange(eventBytes, i, i + 1))),
                    Map.entry("byte_offset", i), Map.entry("byte_length", 1))));
        }
        return new TreeMap<>(Map.ofEntries(
                Map.entry("schema", "g2-admission-input/v1"), Map.entry("admission_id", "SYNTHETIC-ADMISSION-1"),
                Map.entry("classification", "SYNTHETIC"), Map.entry("assessment_time_utc", ASSESSMENT),
                Map.entry("limits", Map.of("max_resource_bytes", 1_048_576, "max_evidence_age_seconds", 120, "max_future_skew_seconds", 1)),
                Map.entry("capture", capture), Map.entry("processing", processing), Map.entry("coverage", coverage),
                Map.entry("resources", new ArrayList<>(definitions)),
                Map.entry("replay_dataset", Map.of("dataset_id", "DATASET-NORMALIZED", "dataset_version", "V1",
                        "normalized_resource_id", "NORMALIZED", "normalized_sha256", string(definitions.get(2).get("sha256")))),
                Map.entry("feed_audit", Map.of("evidence_path", "feed/evidence.json", "artifact_path", "feed-artifact",
                        "observed_at_utc", "2026-11-06T10:00:30Z", "schedule_id", "synthetic-schedule", "schedule_revision", "1")),
                Map.entry("events", events)));
    }

    private static void expectCode(Path valid, Path output, String name, java.util.function.Consumer<Map<String, Object>> mutation, String code) throws Exception {
        Path root = variant(valid, output.resolve(name)); Map<String, Object> input = readInput(root); mutation.accept(input); writeInput(root, input);
        G2Admission.Result result = G2Admission.run(root.resolve("admission.json"));
        check(code == null ? result.status().equals("PASS") : !result.status().equals("PASS") && codes(result).contains(code), name);
    }

    private static Map<String, Object> resourceDefinition(int order, String id, String role, String path, long maxBytes) {
        return new TreeMap<>(Map.ofEntries(Map.entry("order", order), Map.entry("logical_id", id), Map.entry("role", role),
                Map.entry("path", path), Map.entry("sha256", "PENDING"), Map.entry("max_bytes", maxBytes),
                Map.entry("observed_at_utc", "2026-11-06T10:00:30Z"),
                Map.entry("permission", new TreeMap<>(Map.of("manifest_path", "permissions/" + id + "/manifest.json",
                        "intended_use_path", "permissions/" + id + "/use.json", "evidence_path", "permissions/" + id + "/evidence.json",
                        "as_of", "2026-11-06")))));
    }

    private static void createPermission(Path root, Map<String, Object> resource) throws Exception {
        String id = string(resource.get("logical_id")); String role = string(resource.get("role"));
        Path data = root.resolve(string(resource.get("path"))); String dataHash = hash(Files.readAllBytes(data)); resource.put("sha256", dataHash);
        Path directory = root.resolve("permissions").resolve(id); Files.createDirectories(directory);
        Map<String, Object> scope = new TreeMap<>(Map.of("source_id", "SYNTHETIC", "product_id", "TEST",
                "data_type", role, "instruments", List.of("I1"), "data_start", "2026-11-06", "data_end", "2026-11-06"));
        Map<String, Object> manifest = new TreeMap<>(scope);
        manifest.put("schema_version", "1"); manifest.put("dataset_id", "DATASET-" + id); manifest.put("dataset_sha256", dataHash);
        manifest.put("classification", "SYNTHETIC"); manifest.put("candidate_status", "CANDIDATE");
        byte[] manifestBytes = bytes(manifest); Files.write(directory.resolve("manifest.json"), manifestBytes);
        String manifestHash = hash(manifestBytes);
        Map<String, Object> use = new TreeMap<>(scope);
        use.put("schema_version", "1"); use.put("use_id", "USE-" + id); use.put("manifest_sha256", manifestHash);
        use.put("purposes", List.of("SIMULATION")); use.put("use_start", "2026-11-06"); use.put("use_end", "2026-11-06");
        use.put("storage_locations", List.of("SYNTHETIC_LOCAL_TEST")); use.put("retention_start", "2026-11-06");
        use.put("delete_by", "2026-11-08"); use.put("derived_outputs", List.of("AGGREGATE_METRICS")); use.put("sharing_mode", "NONE");
        use.put("sharing_recipients", List.of("NONE")); use.put("shared_material", List.of("NONE"));
        Files.write(directory.resolve("use.json"), bytes(use));
        Map<String, Object> record = new TreeMap<>(scope);
        record.put("record_id", "GRANT-" + id); record.put("manifest_sha256", manifestHash); record.put("effect", "ALLOW");
        record.put("record_state", "ACTIVE"); record.put("purposes", List.of("SIMULATION"));
        record.put("effective_from", "2026-11-06"); record.put("effective_through", "2026-11-08");
        record.put("storage_locations", List.of("SYNTHETIC_LOCAL_TEST")); record.put("max_retention_days", 3);
        record.put("retain_until", "2026-11-08"); record.put("derived_outputs", List.of("AGGREGATE_METRICS"));
        record.put("sharing_modes", List.of("NONE")); record.put("sharing_recipients", List.of("NONE")); record.put("shared_material", List.of("NONE"));
        record.put("owner", "SYNTHETIC_OWNER"); record.put("owner_approval", "APPROVED");
        record.put("owner_approval_reference", "synthetic:task-authorized-fixture"); record.put("reviewer", "SYNTHETIC_REVIEWER");
        record.put("review_status", "APPROVED"); record.put("review_reference", "synthetic:not-independent-review");
        record.put("reviewed_on", "2026-11-06"); record.put("evidence_reference", "synthetic:not-a-contract");
        record.put("evidence_sha256", hash(("evidence-" + id).getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> evidence = new TreeMap<>(); evidence.put("schema_version", "1"); evidence.put("evidence_set_id", "SET-" + id);
        evidence.put("classification", "SYNTHETIC"); evidence.put("completeness", "COMPLETE"); evidence.put("records", List.of(record));
        Files.write(directory.resolve("evidence.json"), bytes(evidence));
    }

    private static Path writeInput(Path root, Map<String, Object> input) throws IOException {
        Path path = root.resolve("admission.json"); Files.write(path, bytes(input)); return path;
    }

    private static Map<String, Object> readInput(Path root) throws IOException { return parse(root.resolve("admission.json")); }

    private static Map<String, Object> resource(Map<String, Object> input, String role) {
        return list(input.get("resources")).stream().map(AdmissionJson::object)
                .filter(value -> role.equals(value.get("role"))).findFirst().orElseThrow();
    }

    private static Set<String> codes(G2Admission.Result result) {
        Set<String> resultCodes = new TreeSet<>();
        for (Object value : list(result.report().get("reasons"))) resultCodes.add(string(object(value).get("code")));
        return resultCodes;
    }

    private static Path variant(Path source, Path destination) throws IOException { copyTree(source, destination); return destination; }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
            }
        }
    }

    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }

    private static void reject(Action action, String message) throws Exception {
        try { action.run(); }
        catch (IOException | IllegalArgumentException | IllegalStateException expected) { checks++; return; }
        throw new AssertionError("Accepted invalid fixture: " + message);
    }
}
