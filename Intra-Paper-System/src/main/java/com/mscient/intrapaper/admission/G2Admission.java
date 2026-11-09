package com.mscient.intrapaper.admission;

import com.mscient.intrapaper.audit.feed.FeedAuditArtifacts;
import com.mscient.intrapaper.permission.preflight.PermissionPreflight;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static com.mscient.intrapaper.admission.AdmissionJson.*;

/**
 * Offline joined G2 admission sidecar. Existing v1 journals and manifests remain byte-for-byte unchanged.
 * PASS means only that the configured local checks found no declared issue.
 */
public final class G2Admission {
    public static final String VERSION = "g2-admission/1";
    private static final Set<String> REQUIRED_ROLES = Set.of(
            "RAW_INPUT", "LIFECYCLE_INPUT", "NORMALIZED_DATA", "TOKEN_MAPPING", "SESSION_CALENDAR",
            "DECODER", "SCHEMA", "BUILD", "FEED_EVIDENCE");
    private static final Set<String> ALLOWED_ROLES;
    static {
        Set<String> roles = new HashSet<>(REQUIRED_ROLES); roles.add("FEED_REFERENCE"); ALLOWED_ROLES = Set.copyOf(roles);
    }
    private static final Set<String> REQUIRED_DOMAINS = Set.of(
            "CAPTURE", "DECODE", "NORMALIZE", "MAPPING", "SESSION", "PERMISSION", "REPLAY");
    private G2Admission() { }

    public record Result(String status, String reportId, Map<String, Object> report, byte[] bytes) {
        public Result {
            report = Collections.unmodifiableMap(new TreeMap<>(report));
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }

    private record Issue(String severity, String code, String field, String detail) {
        Map<String, Object> json() {
            return Map.of("severity", severity, "code", code, "field", field, "detail", detail);
        }
    }

    private record Resource(
            int order, String id, String role, String path, String expectedHash, long maximumBytes,
            Instant observedAt, String manifest, String use, String evidence, LocalDate asOf) { }

    public static Result run(Path manifestPath) throws IOException {
        Path manifest = manifestPath.toAbsolutePath().normalize();
        rejectLinks(manifest);
        byte[] manifestBytes = readBounded(manifest, MAX_MANIFEST_BYTES);
        Map<String, Object> input = object(parse(manifestBytes));
        keys(input, "schema", "admission_id", "classification", "assessment_time_utc", "limits", "capture",
                "processing", "coverage", "resources", "replay_dataset", "feed_audit", "events");
        if (!"g2-admission-input/v1".equals(string(input.get("schema"))))
            throw new IllegalArgumentException("Unsupported admission input schema");
        String admissionId = id(input.get("admission_id"));
        String classification = choice(input.get("classification"), "SYNTHETIC", "CALLER_DECLARED");
        Instant assessment = instant(input.get("assessment_time_utc"));
        Path root = manifest.getParent();
        if (root == null) throw new IOException("Admission manifest needs a parent directory");
        root = root.toRealPath();

        Map<String, Object> limits = object(input.get("limits"));
        keys(limits, "max_resource_bytes", "max_evidence_age_seconds", "max_future_skew_seconds");
        long globalMaximum = positive(limits.get("max_resource_bytes"), "limits.max_resource_bytes");
        if (globalMaximum > 16_777_216) throw new IllegalArgumentException("Resource limit exceeds 16 MiB");
        long maximumAge = nonnegative(limits.get("max_evidence_age_seconds"), "limits.max_evidence_age_seconds");
        long futureSkew = nonnegative(limits.get("max_future_skew_seconds"), "limits.max_future_skew_seconds");

        List<Issue> issues = new ArrayList<>();
        TreeSet<String> unknowns = new TreeSet<>();
        if (classification.equals("CALLER_DECLARED")) incomplete(issues, unknowns, "UNAUTHENTICATED_EVIDENCE",
                "classification", "Issuer, rights, completeness and independent approval cannot be authenticated by this offline checker");

        Map<String, Object> capture = object(input.get("capture"));
        keys(capture, "capture_id", "session_id", "process_id", "process_epoch", "provider_id", "source_id",
                "product_id", "channel_id", "connection_id", "subscription_id");
        TreeMap<String, Object> captureReport = new TreeMap<>();
        for (String field : List.of("capture_id", "session_id", "process_id", "process_epoch", "provider_id",
                "source_id", "product_id", "channel_id", "connection_id", "subscription_id")) {
            String value = optionalId(capture.get(field));
            captureReport.put(field, value);
            if (value == null) incomplete(issues, unknowns, "IDENTITY_UNKNOWN", "capture." + field,
                    "Explicit capture/session/process/source identity is required for PASS");
        }

        Map<String, Object> processing = object(input.get("processing"));
        keys(processing, "decoder_id", "decoder_version", "decoder_sha256", "schema_id", "schema_version",
                "schema_sha256", "build_id", "build_sha256", "decoder_resource_id", "schema_resource_id", "build_resource_id");
        TreeMap<String, Object> processingReport = new TreeMap<>();
        for (String field : List.of("decoder_id", "decoder_version", "schema_id", "schema_version", "build_id")) {
            String value = optionalId(processing.get(field));
            processingReport.put(field, value);
            if (value == null) incomplete(issues, unknowns, "PROCESSING_IDENTITY_UNKNOWN", "processing." + field,
                    "Explicit decoder/schema/build identity is required for PASS");
        }
        for (String field : List.of("decoder_sha256", "schema_sha256", "build_sha256")) {
            String value = optionalDigest(processing.get(field));
            processingReport.put(field, value);
            if (value == null) incomplete(issues, unknowns, "PROCESSING_HASH_UNKNOWN", "processing." + field,
                    "Exact decoder/schema/build bytes must be bound for PASS");
        }
        for (String kind : List.of("decoder", "schema", "build"))
            processingReport.put(kind + "_resource_id", id(processing.get(kind + "_resource_id")));

        Map<String, Object> coverage = object(input.get("coverage"));
        keys(coverage, "requested_start_utc", "requested_end_utc", "required_start_utc", "required_end_utc",
                "observed_start_utc", "observed_end_utc", "timezone", "interval_nanos");
        Instant requestedStart = instant(coverage.get("requested_start_utc"));
        Instant requestedEnd = instant(coverage.get("requested_end_utc"));
        Instant requiredStart = instant(coverage.get("required_start_utc"));
        Instant requiredEnd = instant(coverage.get("required_end_utc"));
        Instant observedStart = optionalInstant(coverage.get("observed_start_utc"));
        Instant observedEnd = optionalInstant(coverage.get("observed_end_utc"));
        String timezone = string(coverage.get("timezone"));
        try { ZoneId.of(timezone); } catch (DateTimeException invalid) { throw new IllegalArgumentException("Unknown coverage timezone", invalid); }
        long intervalNanos = positive(coverage.get("interval_nanos"), "coverage.interval_nanos");
        positiveRange(requestedStart, requestedEnd, "coverage.requested");
        positiveRange(requiredStart, requiredEnd, "coverage.required");
        if (requiredStart.isBefore(requestedStart) || requiredEnd.isAfter(requestedEnd))
            throw new IllegalArgumentException("Required coverage must fit within requested coverage");
        if ((observedStart == null) != (observedEnd == null))
            throw new IllegalArgumentException("Observed coverage endpoints must both be present or both be null");
        if (observedStart == null) {
            incomplete(issues, unknowns, "OBSERVED_COVERAGE_UNKNOWN", "coverage.observed", "Observed coverage is unknown");
        } else {
            positiveRange(observedStart, observedEnd, "coverage.observed");
            if (observedEnd.isAfter(assessment.plusSeconds(futureSkew)))
                fail(issues, "FUTURE_COVERAGE", "coverage.observed_end_utc", "Observed coverage ends after the assessment time and skew");
            if (observedStart.isAfter(requiredStart) || observedEnd.isBefore(requiredEnd))
                fail(issues, "REQUIRED_COVERAGE_MISSING", "coverage.observed", "Observed range does not cover the required range");
        }
        Map<String, Object> coverageReport = new TreeMap<>();
        coverageReport.put("requested_start_utc", requestedStart.toString()); coverageReport.put("requested_end_utc", requestedEnd.toString());
        coverageReport.put("required_start_utc", requiredStart.toString()); coverageReport.put("required_end_utc", requiredEnd.toString());
        coverageReport.put("observed_start_utc", observedStart == null ? null : observedStart.toString());
        coverageReport.put("observed_end_utc", observedEnd == null ? null : observedEnd.toString());
        coverageReport.put("timezone", timezone); coverageReport.put("interval_nanos", intervalNanos);

        List<Resource> resources = resources(input.get("resources"), globalMaximum);
        Set<String> roles = new TreeSet<>(); Set<String> resourceIds = new TreeSet<>(); Set<String> resourcePaths = new TreeSet<>();
        for (Resource resource : resources) {
            if (!roles.add(resource.role) && !resource.role.equals("FEED_REFERENCE")) throw new IllegalArgumentException("Duplicate resource role: " + resource.role);
            if (!resourceIds.add(resource.id)) throw new IllegalArgumentException("Duplicate resource logical ID: " + resource.id);
            if (!resourcePaths.add(resource.path.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("Duplicate/aliased resource path: " + resource.path);
            freshness(resource.observedAt, assessment, maximumAge, futureSkew, "resources[" + resource.order + "].observed_at_utc", issues);
        }
        if (!roles.containsAll(REQUIRED_ROLES)) throw new IllegalArgumentException("Missing required resource role");

        List<Object> permissionReports = new ArrayList<>();
        TreeMap<String, String> admittedHashes = new TreeMap<>();
        TreeMap<String, byte[]> snapshots = new TreeMap<>();
        TreeMap<String, String> controlHashes = new TreeMap<>();
        TreeMap<String, Map<String, Object>> datasetManifests = new TreeMap<>();
        boolean everyPermissionAllowed = true;
        for (Resource resource : resources) {
            Path permissionManifest = safeFile(root, resource.manifest, MAX_MANIFEST_BYTES);
            Path intendedUse = safeFile(root, resource.use, MAX_MANIFEST_BYTES);
            Path permissionEvidence = safeFile(root, resource.evidence, MAX_MANIFEST_BYTES);
            for (String path : List.of(resource.manifest, resource.use, resource.evidence))
                controlHashes.put(path, hash(readBounded(safeFile(root, path, MAX_MANIFEST_BYTES), MAX_MANIFEST_BYTES)));
            PermissionPreflight.Decision decision = PermissionPreflight.check(permissionManifest, intendedUse, permissionEvidence, resource.asOf);
            Map<String, Object> decisionJson = object(parse(decision.bytes()));
            Map<String, Object> permissionReport = new TreeMap<>(Map.ofEntries(
                    Map.entry("resource_id", resource.id), Map.entry("role", resource.role),
                    Map.entry("decision_id", decision.id()), Map.entry("decision_sha256", hash(decision.bytes())),
                    Map.entry("status", decision.status().name()), Map.entry("decision", decisionJson),
                    Map.entry("manifest_path", resource.manifest), Map.entry("intended_use_path", resource.use),
                    Map.entry("evidence_path", resource.evidence), Map.entry("observed_dataset_sha256", "UNKNOWN")));
            permissionReports.add(permissionReport);
            if (decision.status() == PermissionPreflight.Status.DENIED) {
                everyPermissionAllowed = false;
                fail(issues, "PERMISSION_DENIED", "resources." + resource.id, "Production permission preflight denied this exact resource");
                continue;
            }
            if (decision.status() == PermissionPreflight.Status.INCOMPLETE) {
                everyPermissionAllowed = false;
                incomplete(issues, unknowns, "PERMISSION_INCOMPLETE", "resources." + resource.id,
                        "Production permission preflight could not establish a complete declaration match");
                continue;
            }
            Map<String, Object> declared = parse(permissionManifest);
            Map<String, Object> useDeclaration = parse(intendedUse);
            datasetManifests.put(resource.id, declared);
            boolean scopeValid = resource.asOf.equals(assessment.atZone(ZoneOffset.UTC).toLocalDate());
            scopeValid &= resource.role.equals(declared.get("data_type"));
            scopeValid &= resource.expectedHash.equals(declared.get("dataset_sha256"));
            scopeValid &= classification.equals(declared.get("classification"));
            scopeValid &= list(useDeclaration.get("derived_outputs")).contains("AGGREGATE_METRICS");
            permissionReport.put("dataset_manifest", declared);
            if (resource.role.equals("RAW_INPUT") || resource.role.equals("LIFECYCLE_INPUT")) {
                scopeValid &= capture.get("source_id") == null || Objects.equals(capture.get("source_id"), declared.get("source_id"));
                scopeValid &= capture.get("product_id") == null || Objects.equals(capture.get("product_id"), declared.get("product_id"));
            }
            LocalDate firstDate = requiredStart.atZone(ZoneId.of(timezone)).toLocalDate();
            LocalDate lastDate = requiredEnd.minusNanos(1).atZone(ZoneId.of(timezone)).toLocalDate();
            scopeValid &= !date(declared.get("data_start")).isAfter(firstDate) && !date(declared.get("data_end")).isBefore(lastDate);
            if (!scopeValid) {
                everyPermissionAllowed = false;
                fail(issues, "PERMISSION_BINDING_MISMATCH", "resources." + resource.id,
                        "Permission date, classification, role, hash, source/product, coverage or aggregate report use does not match admission");
                continue;
            }
            // Permission declarations pass before even inspecting the dataset path. Bound reads precede use.
            Path allowedFile = safeFile(root, resource.path, resource.maximumBytes);
            byte[] snapshot = readBounded(allowedFile, resource.maximumBytes);
            String actual = hash(snapshot);
            if (!actual.equals(resource.expectedHash)) {
                everyPermissionAllowed = false;
                fail(issues, "RESOURCE_HASH_MISMATCH", "resources." + resource.id,
                        "Admission declaration does not match the permission-checked bytes");
                continue;
            }
            PermissionPreflight.withAllowedDataset(permissionManifest, intendedUse, permissionEvidence, resource.asOf,
                    snapshot, checked -> { if (!Arrays.equals(snapshot, checked)) throw new IllegalStateException("Frozen bytes differ"); return null; });
            permissionReport.put("observed_dataset_sha256", actual);
            permissionReport.put("dataset_verification", "EXACT_FROZEN_BYTES");
            admittedHashes.put(resource.id, actual);
            snapshots.put(resource.path, snapshot);
        }
        for (String kind : List.of("decoder", "schema", "build")) {
            Resource bound = resources.stream().filter(r -> r.id.equals(processingReport.get(kind + "_resource_id"))).findFirst().orElse(null);
            if (bound == null || !bound.role.equals(kind.toUpperCase(Locale.ROOT))
                    || !Objects.equals(processingReport.get(kind + "_sha256"), admittedHashes.get(bound.id)))
                incomplete(issues, unknowns, "PROCESSING_BYTES_UNVERIFIED", "processing." + kind,
                        "Processing identity must bind exact admitted resource bytes; no processing code is executed");
        }

        Map<String, Object> replay = object(input.get("replay_dataset"));
        keys(replay, "dataset_id", "dataset_version", "normalized_resource_id", "normalized_sha256");
        String replayId = id(replay.get("dataset_id")); String replayVersion = id(replay.get("dataset_version"));
        String normalizedId = id(replay.get("normalized_resource_id")); String normalizedHash = digest(replay.get("normalized_sha256"));
        Resource normalized = resources.stream().filter(item -> item.role.equals("NORMALIZED_DATA")).findFirst().orElseThrow();
        if (!normalized.id.equals(normalizedId) || !normalized.expectedHash.equals(normalizedHash))
            fail(issues, "REPLAY_DATASET_BINDING_MISMATCH", "replay_dataset", "Replay identity must bind the exact normalized resource");
        if (datasetManifests.containsKey(normalized.id) && !replayId.equals(datasetManifests.get(normalized.id).get("dataset_id")))
            fail(issues, "REPLAY_IDENTITY_MISMATCH", "replay_dataset.dataset_id", "Replay dataset ID differs from its permission manifest");

        Map<String, Object> feed = object(input.get("feed_audit"));
        keys(feed, "evidence_path", "artifact_path", "observed_at_utc", "schedule_id", "schedule_revision");
        String feedEvidencePath = relative(feed.get("evidence_path")); String feedArtifactPath = relative(feed.get("artifact_path"));
        Instant feedObservedAt = instant(feed.get("observed_at_utc"));
        String expectedSchedule = id(feed.get("schedule_id")); String expectedRevision = id(feed.get("schedule_revision"));
        freshness(feedObservedAt, assessment, maximumAge, futureSkew, "feed_audit.observed_at_utc", issues);
        Map<String, Object> feedReport = new TreeMap<>();
        feedReport.put("status", "NOT_RUN"); feedReport.put("schedule_id", expectedSchedule); feedReport.put("schedule_revision", expectedRevision);
        if (!everyPermissionAllowed) {
            incomplete(issues, unknowns, "FEED_AUDIT_WITHHELD", "feed_audit",
                    "Feed re-audit was not allowed because at least one resource permission decision did not pass");
        } else {
            try {
                Resource feedResource = resources.stream().filter(r -> r.role.equals("FEED_EVIDENCE")).findFirst().orElseThrow();
                if (!feedResource.path.equals(feedEvidencePath)) throw new IOException("Feed evidence is not the admitted manifest");
                Path artifact = safeDirectory(root, feedArtifactPath);
                try (var entries = Files.list(artifact)) {
                    if (!entries.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet())
                            .equals(Set.of("report.json", "artifact.json"))) throw new IOException("Unexpected feed artifact entries");
                }
                byte[] feedReportBytes = readBounded(safeFile(root, feedArtifactPath + "/report.json", MAX_MANIFEST_BYTES), MAX_MANIFEST_BYTES);
                byte[] feedArtifactBytes = readBounded(safeFile(root, feedArtifactPath + "/artifact.json", MAX_MANIFEST_BYTES), MAX_MANIFEST_BYTES);
                controlHashes.put(feedArtifactPath + "/report.json", hash(feedReportBytes));
                controlHashes.put(feedArtifactPath + "/artifact.json", hash(feedArtifactBytes));
                Map<String, Object> verified = verifyFrozenFeed(snapshots, feedEvidencePath, feedReportBytes, feedArtifactBytes);
                String status = choice(verified.get("status"), "PASS", "FAIL", "INCOMPLETE");
                String scheduleId = id(verified.get("schedule_id")); String revision = id(verified.get("schedule_revision"));
                if (!scheduleId.equals(expectedSchedule) || !revision.equals(expectedRevision))
                    fail(issues, "FEED_AUDIT_IDENTITY_MISMATCH", "feed_audit", "Verified feed audit has another schedule identity");
                if (status.equals("FAIL")) fail(issues, "FEED_AUDIT_FAILED", "feed_audit.status", "Verified configured feed audit reported FAIL");
                if (status.equals("INCOMPLETE")) incomplete(issues, unknowns, "FEED_AUDIT_INCOMPLETE", "feed_audit.status",
                        "Verified configured feed audit reported INCOMPLETE");
                crossCheckFeedScope(verified, captureReport, timezone, issues, unknowns);
                crossCheckFeedHashes(verified, resources, feedEvidencePath, issues);
                Map<String, Object> feedInput = object(parse(snapshots.get(feedEvidencePath)));
                for (Object value : list(feedInput.get("bindings"))) {
                    if (!Objects.equals(object(value).get("decoder"), processingReport.get("decoder_id")))
                        fail(issues, "FEED_DECODER_MISMATCH", "processing.decoder_id", "Feed decoder differs from admission decoder");
                }
                String schedulePath = relative(object(feedInput.get("schedule")).get("path"));
                Path feedParent = Path.of(feedEvidencePath).getParent();
                String joinedSchedule = (feedParent == null ? Path.of(schedulePath) : feedParent.resolve(schedulePath)).toString().replace('\\', '/');
                Map<String, Object> schedule = object(parse(snapshots.get(joinedSchedule)));
                Map<String, Object> scope = object(list(schedule.get("scopes")).getFirst());
                if (!requiredStart.equals(OffsetDateTime.parse(string(scope.get("session_start"))).toInstant())
                        || !requiredEnd.equals(OffsetDateTime.parse(string(scope.get("session_end"))).toInstant()))
                    fail(issues, "FEED_COVERAGE_MISMATCH", "feed_audit", "Feed session must equal the required admission interval");
                feedReport = Map.of("status", status, "schedule_id", scheduleId, "schedule_revision", revision,
                        "report_sha256", hash(feedReportBytes), "artifact_sha256", hash(feedArtifactBytes),
                        "evidence_sha256", hash(snapshots.get(feedEvidencePath)), "input_sha256", verified.get("input_sha256"));
            } catch (IOException | RuntimeException invalid) {
                fail(issues, "FEED_AUDIT_INVALID", "feed_audit", "Feed audit artifact did not pass deterministic verification");
                feedReport = Map.of("status", "INVALID", "schedule_id", expectedSchedule, "schedule_revision", expectedRevision);
            }
        }

        List<?> eventValues = list(input.get("events"));
        if (eventValues.isEmpty() || eventValues.size() > 100_000) throw new IllegalArgumentException("Require 1 to 100000 sidecar events");
        Set<String> eventIds = new HashSet<>(); Set<String> recordRefs = new HashSet<>(); Set<String> domains = new TreeSet<>();
        Instant previousObserved = null; List<Object> eventReports = new ArrayList<>();
        for (int index = 0; index < eventValues.size(); index++) {
            Map<String, Object> event = object(eventValues.get(index));
            keys(event, "order", "event_id", "domain", "event_type", "observed_at_utc", "event_time_utc",
                    "event_time_status", "resource_id", "record_ordinal", "record_sha256", "byte_offset", "byte_length");
            if (integer(event.get("order")) != index) throw new IllegalArgumentException("Event orders must be contiguous zero-based ordinals");
            String eventId = id(event.get("event_id"));
            if (!eventIds.add(eventId)) throw new IllegalArgumentException("Duplicate event ID: " + eventId);
            String domain = choice(event.get("domain"), REQUIRED_DOMAINS.toArray(String[]::new)); domains.add(domain);
            String eventType = id(event.get("event_type")); Instant observed = instant(event.get("observed_at_utc"));
            Instant eventTime = optionalInstant(event.get("event_time_utc"));
            String eventTimeStatus = choice(event.get("event_time_status"), "OBSERVED", "UNKNOWN");
            if ((eventTime == null) != eventTimeStatus.equals("UNKNOWN"))
                throw new IllegalArgumentException("Event time status and value disagree");
            String resourceId = id(event.get("resource_id"));
            if (!resourceIds.contains(resourceId)) throw new IllegalArgumentException("Event references unknown resource: " + resourceId);
            long ordinal = nonnegative(event.get("record_ordinal"), "events.record_ordinal");
            String recordHash = digest(event.get("record_sha256"));
            long offset = nonnegative(event.get("byte_offset"), "events.byte_offset");
            long length = positive(event.get("byte_length"), "events.byte_length");
            Resource eventResource = resources.stream().filter(r -> r.id.equals(resourceId)).findFirst().orElseThrow();
            byte[] eventBytes = snapshots.get(eventResource.path);
            if (eventBytes == null) incomplete(issues, unknowns, "EVENT_BYTES_UNVERIFIED", "events[" + index + "]", "Referenced resource was not admitted");
            else if (offset > eventBytes.length || length > eventBytes.length - offset
                    || !recordHash.equals(hash(Arrays.copyOfRange(eventBytes, (int)offset, (int)(offset + length)))))
                fail(issues, "EVENT_BYTES_MISMATCH", "events[" + index + "]", "Event byte range/hash does not match the admitted resource");
            if (!recordRefs.add(resourceId + "\u0000" + ordinal))
                throw new IllegalArgumentException("Duplicate or conflicting event record reference");
            if (previousObserved != null && observed.isBefore(previousObserved))
                fail(issues, "EVENT_ORDER_REGRESSION", "events[" + index + "].observed_at_utc", "Sidecar observation time regressed in declared order");
            previousObserved = observed;
            freshness(observed, assessment, maximumAge, futureSkew, "events[" + index + "].observed_at_utc", issues);
            if (eventTime == null) {
                incomplete(issues, unknowns, "EVENT_TIME_UNKNOWN", "events[" + index + "].event_time_utc",
                        "Unknown upstream/event time remains unknown");
            } else if (eventTime.isAfter(observed.plusSeconds(futureSkew))) {
                fail(issues, "EVENT_TIME_IN_FUTURE", "events[" + index + "].event_time_utc", "Event time exceeds declared future skew");
            }
            eventReports.add(Map.ofEntries(Map.entry("order", index), Map.entry("event_id", eventId), Map.entry("domain", domain),
                    Map.entry("event_type", eventType), Map.entry("observed_at_utc", observed.toString()),
                    Map.entry("event_time_utc", eventTime == null ? "UNKNOWN" : eventTime.toString()),
                    Map.entry("event_time_status", eventTimeStatus), Map.entry("resource_id", resourceId),
                    Map.entry("record_ordinal", ordinal), Map.entry("record_sha256", recordHash),
                    Map.entry("byte_offset", offset), Map.entry("byte_length", length)));
        }
        for (String missing : new TreeSet<>(REQUIRED_DOMAINS)) if (!domains.contains(missing))
            incomplete(issues, unknowns, "EVENT_DOMAIN_MISSING", "events", "No event binds required domain " + missing);

        for (Map.Entry<String, String> entry : controlHashes.entrySet())
            if (!entry.getValue().equals(hash(readBounded(safeFile(root, entry.getKey(), MAX_MANIFEST_BYTES), MAX_MANIFEST_BYTES))))
                throw new IOException("Control input changed during admission");
        if (!Arrays.equals(manifestBytes, readBounded(manifest, MAX_MANIFEST_BYTES))) throw new IOException("Admission input changed during evaluation");
        issues.sort(Comparator.comparing(Issue::severity).thenComparing(Issue::field).thenComparing(Issue::code).thenComparing(Issue::detail));
        String status = issues.stream().anyMatch(issue -> issue.severity.equals("FAIL")) ? "FAIL"
                : issues.isEmpty() ? "PASS" : "INCOMPLETE";
        List<Object> resourceReports = resources.stream().map(resource -> (Object)Map.ofEntries(
                Map.entry("order", resource.order), Map.entry("logical_id", resource.id), Map.entry("role", resource.role),
                Map.entry("path", resource.path), Map.entry("declared_sha256", resource.expectedHash),
                Map.entry("admitted_sha256", admittedHashes.getOrDefault(resource.id, "UNKNOWN")),
                Map.entry("maximum_bytes", resource.maximumBytes), Map.entry("observed_at_utc", resource.observedAt.toString()))).toList();

        TreeMap<String, Object> report = new TreeMap<>();
        report.put("schema", "g2-admission-report/v1"); report.put("admission_version", VERSION);
        report.put("admission_id", admissionId); report.put("classification", classification);
        report.put("input_manifest_sha256", hash(manifestBytes)); report.put("code_sha256", FeedAuditArtifacts.codeHash());
        report.put("control_input_sha256", controlHashes);
        report.put("assessment_time_utc", assessment.toString()); report.put("capture", captureReport);
        report.put("processing", processingReport); report.put("coverage", coverageReport); report.put("resources", resourceReports);
        report.put("permission_decisions", permissionReports);
        report.put("replay_dataset", Map.of("dataset_id", replayId, "dataset_version", replayVersion,
                "normalized_resource_id", normalizedId, "normalized_sha256", normalizedHash));
        report.put("feed_audit", feedReport); report.put("events", eventReports); report.put("event_domains", new ArrayList<>(domains));
        report.put("unknown_fields", new ArrayList<>(unknowns)); report.put("reasons", issues.stream().map(Issue::json).toList());
        report.put("status", status); report.put("pass_scope", "CONFIGURED_SYNTHETIC_OR_CALLER_DECLARED_CHECKS_ONLY");
        report.put("source_authenticity_verified", false); report.put("permission_authenticated", false);
        report.put("complete_feed_verified", false); report.put("market_correctness_verified", false);
        report.put("trading_readiness_approved", false); report.put("release_authorized", false);
        report.put("order_egress_enabled", false); report.put("profitability_established", false); report.put("g2_closed", false);
        report.put("normalized_projection_verified", false); report.put("event_semantics_verified", false);
        report.put("mapping_calendar_authority_verified", false);
        report.put("report_id", hash(bytes(report)));
        String reportId = (String)report.get("report_id");
        return new Result(status, reportId, report, bytes(report));
    }

    private static List<Resource> resources(Object value, long globalMaximum) {
        List<?> rows = list(value);
        if (rows.size() < REQUIRED_ROLES.size() || rows.size() > 64) throw new IllegalArgumentException("Require 9 to 64 resources");
        List<Resource> result = new ArrayList<>();
        long total = 0;
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = object(rows.get(index));
            keys(row, "order", "logical_id", "role", "path", "sha256", "max_bytes", "observed_at_utc", "permission");
            long order = integer(row.get("order"));
            if (order != index) throw new IllegalArgumentException("Resource orders must be contiguous zero-based ordinals");
            long maximum = positive(row.get("max_bytes"), "resources.max_bytes");
            total += maximum;
            if (maximum > globalMaximum || total > 67_108_864) throw new IllegalArgumentException("Resource byte limit exceeds individual or 64 MiB total limit");
            Map<String, Object> permission = object(row.get("permission"));
            keys(permission, "manifest_path", "intended_use_path", "evidence_path", "as_of");
            result.add(new Resource(index, id(row.get("logical_id")), choice(row.get("role"), ALLOWED_ROLES.toArray(String[]::new)),
                    relative(row.get("path")), digest(row.get("sha256")), maximum, instant(row.get("observed_at_utc")),
                    relative(permission.get("manifest_path")), relative(permission.get("intended_use_path")),
                    relative(permission.get("evidence_path")), date(permission.get("as_of"))));
        }
        return List.copyOf(result);
    }

    private static void crossCheckFeedScope(Map<String, Object> feed, Map<String, Object> capture, String timezone,
                                            List<Issue> issues, Set<String> unknowns) {
        List<?> scopes = list(feed.get("scopes"));
        if (scopes.size() != 1) {
            fail(issues, "AMBIGUOUS_FEED_SCOPE", "feed_audit.scopes", "Admission v1 requires exactly one verified feed scope");
            return;
        }
        Map<String, Object> scope = object(scopes.getFirst());
        for (Map.Entry<String, String> join : Map.of(
                "source", "source_id", "product", "product_id", "channel", "channel_id",
                "connection_id", "connection_id", "subscription_id", "subscription_id").entrySet()) {
            String declared = (String)capture.get(join.getValue());
            if (declared == null) continue;
            if (!declared.equals(string(scope.get(join.getKey()))))
                fail(issues, "FEED_SCOPE_IDENTITY_MISMATCH", "feed_audit.scopes[0]." + join.getKey(), "Feed scope disagrees with capture identity");
        }
        if (!timezone.equals(string(scope.get("timezone"))))
            fail(issues, "FEED_TIMEZONE_MISMATCH", "feed_audit.scopes[0].timezone", "Feed scope disagrees with coverage timezone");
    }

    private static void crossCheckFeedHashes(Map<String, Object> feed, List<Resource> resources, String evidencePath, List<Issue> issues) {
        Map<String, Object> hashes = object(feed.get("input_sha256"));
        Path parent = Path.of(evidencePath).getParent();
        for (Resource resource : resources) if (resource.role.equals("RAW_INPUT") || resource.role.equals("LIFECYCLE_INPUT")) {
            String name = (parent == null ? Path.of(resource.path) : parent.relativize(Path.of(resource.path))).toString().replace('\\', '/');
            Object found = hashes.get(name);
            if (!(found instanceof String) || !resource.expectedHash.equals(found))
                fail(issues, "FEED_INPUT_HASH_MISMATCH", "feed_audit.input_sha256." + name,
                        "Feed audit does not bind the admitted raw/lifecycle bytes");
        }
    }

    /** Re-audit can see only the frozen, permission-checked resources, never an original undeclared file. */
    private static Map<String, Object> verifyFrozenFeed(Map<String, byte[]> snapshots, String evidence,
                                                       byte[] report, byte[] artifact) throws IOException {
        Path temporary = Files.createTempDirectory("g2-admission-frozen-");
        try {
            Path inputs = Files.createDirectory(temporary.resolve("inputs"));
            Path artifacts = Files.createDirectory(temporary.resolve("artifact"));
            for (Map.Entry<String, byte[]> entry : snapshots.entrySet()) {
                Path target = unresolved(inputs, entry.getKey());
                Files.createDirectories(target.getParent()); Files.write(target, entry.getValue(), StandardOpenOption.CREATE_NEW);
            }
            Files.write(artifacts.resolve("report.json"), report, StandardOpenOption.CREATE_NEW);
            Files.write(artifacts.resolve("artifact.json"), artifact, StandardOpenOption.CREATE_NEW);
            FeedAuditArtifacts.verify(unresolved(inputs, evidence), artifacts);
            return object(parse(report));
        } finally {
            try (var files = Files.walk(temporary)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void freshness(Instant evidence, Instant assessment, long maximumAge, long futureSkew,
                                  String field, List<Issue> issues) {
        if (evidence.isAfter(assessment.plusSeconds(futureSkew)))
            fail(issues, "FUTURE_EVIDENCE", field, "Evidence observation is later than the explicit assessment time and skew");
        else if (evidence.isBefore(assessment.minusSeconds(maximumAge)))
            fail(issues, "STALE_EVIDENCE", field, "Evidence observation is older than the declared maximum age");
    }

    private static void positiveRange(Instant start, Instant end, String field) {
        if (!start.isBefore(end)) throw new IllegalArgumentException(field + " must use a positive [start,end) interval");
    }

    private static long positive(Object value, String field) {
        long result = integer(value);
        if (result <= 0) throw new IllegalArgumentException(field + " must be positive");
        return result;
    }

    private static long nonnegative(Object value, String field) {
        long result = integer(value);
        if (result < 0) throw new IllegalArgumentException(field + " must be non-negative");
        return result;
    }

    private static void fail(List<Issue> issues, String code, String field, String detail) {
        issues.add(new Issue("FAIL", code, field, detail));
    }

    private static void incomplete(List<Issue> issues, Set<String> unknowns, String code, String field, String detail) {
        issues.add(new Issue("INCOMPLETE", code, field, detail)); unknowns.add(field);
    }
}
