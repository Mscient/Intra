package com.mscient.intrapaper.ops.evidence;

import com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceReport.Check;
import com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceReport.Result;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Offline normalized evidence reconciliation. This neither runs a trading session nor authenticates its caller. */
public final class DailyOpsEvidenceChecker {
    private static final List<String> PRE_CHECKS = List.of("CLOCK", "RUN_CONFIG", "INPUT_PLAN", "RISK_LIMITS",
            "STARTING_RECONCILIATION", "STOP_PATH");
    private static final List<String> ACKS = List.of("PRE_SESSION_READY", "SESSION_CLOSE", "RECONCILIATION_REVIEW", "INCIDENT_REVIEW");
    private DailyOpsEvidenceChecker() { }

    public static DailyOpsEvidenceReport check(Path manifest) {
        SessionEvidenceBundle bundle = SessionEvidenceBundle.load(manifest);
        Context context = new Context(bundle);
        List<Result> results = List.of(preSession(context), coverage(context), risk(context), run(context),
                settlementCosts(context), closing(context), operator(context));
        return new DailyOpsEvidenceReport(context.sessionId, context.runId, context.outcome, bundle.hashes, results);
    }

    private static final class Context {
        final SessionEvidenceBundle bundle;
        final Map<String, Object> root;
        final Check common = new Check("session_identity");
        final String sessionId, runId, outcome;
        final Instant start, end;
        final Map<String, Map<String, Object>> instruments = new TreeMap<>();
        Context(SessionEvidenceBundle bundle) {
            this.bundle = bundle;
            root = bundle.root;
            if (root == null) common.missing("bundle");
            Long version = integer(common, root, "schema_version", "schema_version");
            if (version != null) common.require(version == 1, "schema_version", "Unsupported evidence schema version");
            String classification = string(common, root, "classification", "classification");
            allowed(common, classification, "classification", "SYNTHETIC", "CALLER_SUPPLIED_DIAGNOSTIC");
            sessionId = string(common, root, "session_id", "session_id");
            runId = string(common, root, "run_id", "run_id");
            outcome = string(common, root, "outcome", "outcome");
            allowed(common, outcome, "outcome", "COMPLETED", "HALTED");
            start = time(common, root, "session_start_utc", "session_start_utc");
            end = time(common, root, "session_end_utc", "session_end_utc");
            if (start != null && end != null) common.require(end.isAfter(start), "session_end_utc", "Session end must follow its start");
            List<Object> rows = array(common, root, "instruments", "instruments");
            if (rows.isEmpty()) common.incomplete("instruments", "UNKNOWN: a nonempty instrument universe is required");
            for (int i = 0; i < rows.size(); i++) {
                String path = "instruments[" + i + "]";
                Map<String, Object> row = object(common, rows.get(i), path);
                String id = string(common, row, "instrument_id", path + ".instrument_id");
                BigDecimal multiplier = decimal(common, row, "multiplier", path + ".multiplier");
                if (multiplier != null) common.require(multiplier.signum() > 0, path + ".multiplier", "Multiplier must be positive");
                if (id != null && instruments.putIfAbsent(id, row) != null) common.fail(path, "Duplicate instrument ID");
            }
        }
        Check check(String name) { Check check = new Check(name); check.include(common); return check; }
    }

    private static Result preSession(Context x) {
        Check c = x.check("pre_session_checks_timestamps");
        Map<String, Object> pre = section(x, c, "pre_session", false);
        Instant reviewed = time(c, pre, "checked_at_utc", "pre_session.checked_at_utc");
        Rows checks = rows(c, pre, "checks", "expected_check_count", "pre_session", null);
        Map<String, Map<String, Object>> indexed = index(c, checks.values, "check_id", "pre_session.checks");
        for (String id : PRE_CHECKS) {
            Map<String, Object> row = indexed.get(id);
            String path = "pre_session.checks." + id;
            if (row == null) { c.missing(path); continue; }
            positive(c, row, "passed", path + ".passed");
            Instant checked = time(c, row, "checked_at_utc", path + ".checked_at_utc");
            before(c, checked, x.start, path + ".checked_at_utc", "Pre-session check occurred after session start");
            before(c, checked, reviewed, path + ".checked_at_utc", "Required check follows the pre-session review");
            if (id.equals("STARTING_RECONCILIATION")) {
                Map<String, Object> risk = object(c, value(x.root, "risk"), "risk");
                Map<String, Object> initial = object(c, value(risk, "initial"), "risk.initial");
                after(c, checked, time(c, initial, "timestamp_utc", "risk.initial.timestamp_utc"), path,
                        "Starting reconciliation predates the starting snapshot");
            }
            string(c, row, "operator_id", path + ".operator_id");
            String evidence = string(c, row, "evidence_path", path + ".evidence_path");
            requireFile(x, c, evidence, path + ".evidence_path");
        }
        for (String id : indexed.keySet()) if (!PRE_CHECKS.contains(id)) c.fail("pre_session.checks." + id, "Unknown required-check identifier");
        return c.result();
    }

    private static Result run(Context x) {
        Check c = x.check("replay_run_identity_artifact_integrity");
        c.include(x.bundle.integrity);
        Map<String, Object> run = object(c, value(x.root, "run"), "run");
        identity(c, run, x, "run");
        same(c, string(c, run, "replay_run_id", "run.replay_run_id"), x.runId, "run.replay_run_id", "Replay identity differs from run identity");
        after(c, time(c, run, "completed_at_utc", "run.completed_at_utc"), x.end, "run.completed_at_utc", "Run evidence predates session end");
        Rows artifacts = rows(c, run, "artifacts", "expected_artifact_count", "run", null);
        Map<String, String> roles = new TreeMap<>();
        for (int i = 0; i < artifacts.values.size(); i++) {
            String path = "run.artifacts[" + i + "]";
            Map<String, Object> artifact = object(c, artifacts.values.get(i), path);
            String role = string(c, artifact, "role", path + ".role");
            allowed(c, role, path + ".role", "INPUT", "CONFIG", "OUTPUT", "REPLAY_OUTPUT");
            String name = string(c, artifact, "path", path + ".path");
            if (name != null && role != null) roles.put(name, role);
            if (role != null && (role.equals("OUTPUT") || role.equals("REPLAY_OUTPUT"))) {
                Map<String, Object> output = artifactObject(x, c, name, path);
                if (output != null) {
                    identity(c, output, x, path + ".contents");
                    after(c, time(c, output, "completed_at_utc", path + ".contents.completed_at_utc"), x.end,
                            path + ".contents.completed_at_utc", "Output evidence predates session end");
                }
            }
        }
        for (String role : List.of("INPUT", "CONFIG", "OUTPUT", "REPLAY_OUTPUT")) {
            if (!roles.containsValue(role)) c.missing("run.artifacts.role." + role);
        }
        long configs = roles.values().stream().filter("CONFIG"::equals).count();
        if (configs > 1) c.fail("run.artifacts", "Exactly one configuration artifact is supported");
        List<Object> pairs = array(c, run, "replay_pairs", "run.replay_pairs");
        if (pairs.isEmpty()) c.incomplete("run.replay_pairs", "UNKNOWN: original/replayed byte comparisons are required");
        Set<String> originals = new HashSet<>(), replays = new HashSet<>();
        for (int i = 0; i < pairs.size(); i++) {
            String path = "run.replay_pairs[" + i + "]";
            Map<String, Object> pair = object(c, pairs.get(i), path);
            String original = string(c, pair, "original_path", path + ".original_path");
            String replay = string(c, pair, "replayed_path", path + ".replayed_path");
            if (original != null) {
                if (!originals.add(original)) c.fail(path, "Original output is compared more than once");
                if (roles.containsKey(original)) c.require("OUTPUT".equals(roles.get(original)), path, "Original must have OUTPUT role");
            }
            if (replay != null) {
                if (!replays.add(replay)) c.fail(path, "Replayed output is compared more than once");
                if (roles.containsKey(replay)) c.require("REPLAY_OUTPUT".equals(roles.get(replay)), path, "Replay must have REPLAY_OUTPUT role");
            }
            if (original != null && replay != null) {
                c.require(!original.equals(replay), path, "Original and replayed evidence must be separate files");
                byte[] left = requireFile(x, c, original, path + ".original_path");
                byte[] right = requireFile(x, c, replay, path + ".replayed_path");
                if (left != null && right != null) c.require(Arrays.equals(left, right), path, "Original and replayed artifact bytes differ");
            }
        }
        roles.forEach((name, role) -> {
            if (role.equals("OUTPUT") && !originals.contains(name)) c.missing("run.replay_pairs." + name);
            if (role.equals("REPLAY_OUTPUT") && !replays.contains(name)) c.missing("run.replay_pairs." + name);
        });
        return c.result();
    }

    private static Result coverage(Context x) {
        Check c = x.check("input_feed_coverage_stale_handling");
        Map<String, Object> coverage = section(x, c, "coverage", true);
        Rows windows = rows(c, coverage, "windows", "expected_window_count", "coverage", null);
        InputEvidence inputs = inputEvidence(x, c);
        Set<String> usedInputs = new HashSet<>();
        boolean allCoverageComplete = windows.complete && !windows.values.isEmpty();
        if (windows.values.isEmpty()) c.incomplete("coverage.windows", "UNKNOWN: nonempty declared feed windows are required");
        Set<String> windowIds = new HashSet<>(), covered = new HashSet<>();
        Map<String, List<Interval>> intervals = new TreeMap<>();
        boolean intervalPlanKnown = windows.complete;
        for (int i = 0; i < windows.values.size(); i++) {
            String path = "coverage.windows[" + i + "]";
            Map<String, Object> window = object(c, windows.values.get(i), path);
            String id = string(c, window, "window_id", path + ".window_id");
            if (id != null && !windowIds.add(id)) c.fail(path, "Duplicate coverage window ID");
            String instrument = instrument(x, c, window, path);
            String source = string(c, window, "source_id", path + ".source_id");
            Instant start = time(c, window, "start_utc", path + ".start_utc");
            Instant end = time(c, window, "end_utc", path + ".end_utc");
            if (start == null || end == null || instrument == null || source == null) intervalPlanKnown = false;
            within(c, start, x.start, x.end, path + ".start_utc");
            within(c, end, x.start, x.end, path + ".end_utc");
            if (start != null && end != null) {
                c.require(end.isAfter(start), path, "Feed window end must follow its start");
                if (instrument != null && source != null) intervals.computeIfAbsent(DailyOpsEvidenceReport.json(List.of(instrument, source)),
                        ignored -> new ArrayList<>()).add(new Interval(start, end));
            }
            before(c, time(c, window, "acknowledged_at_utc", path + ".acknowledged_at_utc"), start,
                    path + ".acknowledged_at_utc", "Subscription/input acknowledgement follows window start");
            Long maxAge = nonnegative(c, window, "max_age_ms", path + ".max_age_ms");
            Long maxSilence = nonnegative(c, window, "max_silence_ms", path + ".max_silence_ms");
            if (maxSilence != null) c.require(maxSilence > 0, path + ".max_silence_ms", "Silence threshold must be positive");
            Rows observations = rows(c, window, "observations", "expected_observation_count", path, null);
            allCoverageComplete &= observations.complete;
            if (observations.values.isEmpty()) {
                c.incomplete(path + ".observations", "UNKNOWN: an empty event list does not establish feed coverage");
                allCoverageComplete = false;
            }
            Set<String> observationIds = new HashSet<>();
            List<Instant> times = new ArrayList<>();
            for (int j = 0; j < observations.values.size(); j++) {
                String samplePath = path + ".observations[" + j + "]";
                Map<String, Object> sample = object(c, observations.values.get(j), samplePath);
                String sampleId = string(c, sample, "event_id", samplePath + ".event_id");
                if (sampleId != null && !observationIds.add(sampleId)) c.fail(samplePath, "Duplicate input event ID in window");
                same(c, string(c, sample, "instrument_id", samplePath + ".instrument_id"), instrument, samplePath, "Input instrument differs from its window");
                same(c, string(c, sample, "source_id", samplePath + ".source_id"), source, samplePath, "Input source differs from its window");
                Instant observed = time(c, sample, "observed_at_utc", samplePath + ".observed_at_utc");
                Instant input = time(c, sample, "input_at_utc", samplePath + ".input_at_utc");
                String inputKey = inputKey(c, sample, samplePath);
                if (inputKey == null) allCoverageComplete = false;
                else {
                    usedInputs.add(inputKey);
                    Map<String, Object> supplied = inputs.samples.get(inputKey);
                    if (supplied == null) {
                        if (inputs.complete) c.fail(samplePath, "Coverage observation has no matching input artifact record");
                        else c.incomplete(samplePath, "UNKNOWN: input artifact records are incomplete");
                    } else {
                        same(c, observed, time(c, supplied, "observed_at_utc", samplePath + ".artifact.observed_at_utc"), samplePath, "Coverage/input receive timestamps differ");
                        same(c, input, time(c, supplied, "input_at_utc", samplePath + ".artifact.input_at_utc"), samplePath, "Coverage/input event timestamps differ");
                    }
                }
                within(c, observed, start, end, samplePath + ".observed_at_utc");
                if (observed != null) times.add(observed);
                Boolean permit = bool(c, sample, "new_risk_allowed", samplePath + ".new_risk_allowed");
                String action = string(c, sample, "action", samplePath + ".action");
                allowed(c, action, samplePath + ".action", "ALLOW", "PAUSE", "HALT");
                if (permit != null && action != null) c.require(permit == action.equals("ALLOW"), samplePath, "Risk permission contradicts input action");
                if (observed != null && input != null) {
                    Duration age = Duration.between(input, observed);
                    c.require(!age.isNegative(), samplePath + ".input_at_utc", "Input timestamp is in the future at observation");
                    if (maxAge != null && age.compareTo(Duration.ofMillis(maxAge)) > 0) {
                        if (permit != null) c.require(!permit, samplePath, "Stale input allowed new risk");
                        if (action != null) c.require(!action.equals("ALLOW"), samplePath, "Stale input has no pause/halt handling");
                        if (action == null || action.equals("ALLOW")) referencedIncident(x, c,
                                string(c, sample, "incident_id", samplePath + ".incident_id"), samplePath + ".incident_id");
                    }
                }
                if (action != null && !action.equals("ALLOW")) {
                    String incident = string(c, sample, "incident_id", samplePath + ".incident_id");
                    referencedIncident(x, c, incident, samplePath + ".incident_id");
                    String stopId = string(c, sample, "stop_id", samplePath + ".stop_id");
                    Map<String, Object> stop = referencedRecord(x, c, "stops", "stop_id", stopId, samplePath + ".stop_id");
                    if (stop != null) {
                        same(c, action, string(c, stop, "kind", samplePath + ".stop.kind"), samplePath, "Input action differs from its stop record");
                        same(c, incident, string(c, stop, "incident_id", samplePath + ".stop.incident_id"), samplePath, "Input and stop incident references differ");
                        positive(c, stop, "new_risk_blocked", samplePath + ".stop.new_risk_blocked");
                        after(c, time(c, stop, "requested_at_utc", samplePath + ".stop.requested_at_utc"), observed,
                                samplePath, "Stop request predates its input trigger");
                    }
                }
            }
            if (maxSilence != null && start != null && end != null && !times.isEmpty()
                    && times.size() == observations.values.size() && observations.complete) {
                Instant previous = start;
                for (Instant time : times) {
                    c.require(!time.isBefore(previous), path + ".observations", "Input observations regress in time");
                    silence(c, previous, time, maxSilence, path);
                    previous = time;
                }
                silence(c, previous, end, maxSilence, path);
            }
            if (instrument != null) covered.add(instrument);
        }
        for (String id : x.instruments.keySet()) if (!covered.contains(id)) c.missing("coverage.instrument." + id);
        if (intervalPlanKnown) intervals.forEach((id, spans) -> {
            spans.sort(java.util.Comparator.comparing(Interval::start));
            Instant boundary = x.start;
            for (Interval span : spans) {
                if (boundary != null) c.require(!span.start.isAfter(boundary), "coverage." + id, "Declared input windows leave a session coverage gap");
                if (boundary == null || span.end.isAfter(boundary)) boundary = span.end;
            }
            after(c, boundary, x.end, "coverage." + id, "Declared input windows end before session evidence ends");
        });
        if (inputs.complete && allCoverageComplete) for (String key : inputs.samples.keySet()) {
            if (!usedInputs.contains(key)) c.fail("coverage.input_records", "Input artifact contains an observation outside the declared coverage records");
        }
        return c.result();
    }

    private record InputEvidence(Map<String, Map<String, Object>> samples, boolean complete) { }

    private static InputEvidence inputEvidence(Context x, Check c) {
        Map<String, Object> run = object(c, value(x.root, "run"), "run");
        List<Object> artifacts = array(c, run, "artifacts", "run.artifacts");
        Map<String, Map<String, Object>> samples = new TreeMap<>();
        boolean complete = true, found = false;
        for (Object entry : artifacts) {
            Map<String, Object> artifact = SessionEvidenceBundle.object(entry);
            if (!"INPUT".equals(value(artifact, "role"))) continue;
            found = true;
            String name = string(c, artifact, "path", "run.input.path");
            Map<String, Object> input = artifactObject(x, c, name, "run.input");
            if (input == null) { complete = false; continue; }
            Rows rows = rows(c, input, "observations", "expected_observation_count", "run.input", null);
            complete &= rows.complete;
            if (rows.values.isEmpty()) {
                c.incomplete("run.input.observations", "UNKNOWN: an empty input artifact cannot establish feed coverage");
                complete = false;
            }
            for (int i = 0; i < rows.values.size(); i++) {
                String path = "run.input.observations[" + i + "]";
                Map<String, Object> sample = object(c, rows.values.get(i), path);
                String key = inputKey(c, sample, path);
                time(c, sample, "observed_at_utc", path + ".observed_at_utc");
                time(c, sample, "input_at_utc", path + ".input_at_utc");
                if (key == null) complete = false;
                else if (samples.putIfAbsent(key, sample) != null) c.fail(path, "Duplicate input observation across artifacts");
            }
        }
        if (!found) c.missing("run.artifacts.role.INPUT");
        return new InputEvidence(samples, complete && found);
    }

    private static String inputKey(Check c, Map<String, Object> sample, String path) {
        String id = string(c, sample, "event_id", path + ".event_id");
        String source = string(c, sample, "source_id", path + ".source_id");
        String instrument = string(c, sample, "instrument_id", path + ".instrument_id");
        return id == null || source == null || instrument == null ? null : DailyOpsEvidenceReport.json(List.of(source, instrument, id));
    }

    private record Interval(Instant start, Instant end) { }

    private static void silence(Check c, Instant start, Instant end, long allowed, String path) {
        c.require(Duration.between(start, end).compareTo(Duration.ofMillis(allowed)) <= 0,
                path + ".observations", "Observed receive-time silence exceeds the caller threshold");
    }

    private record Rows(List<Object> values, boolean complete) { }
    private static Rows rows(Check c, Map<String, Object> map, String key, String countKey, String path, String emptyReason) {
        List<Object> values = array(c, map, key, path + "." + key);
        Long expected = nonnegative(c, map, countKey, path + "." + countKey);
        Boolean complete = bool(c, map, "complete", path + ".complete");
        if (Boolean.FALSE.equals(complete)) c.incomplete(path + ".complete", "UNKNOWN: caller declares this evidence partial");
        if (expected != null) {
            if (values.size() < expected) c.incomplete(path + "." + key, "UNKNOWN: fewer records supplied than the explicit expected count");
            if (values.size() > expected) c.fail(path + "." + key, "Record count exceeds the explicit expected count");
        }
        String emptyEvidence = null;
        if (values.isEmpty() && emptyReason != null) emptyEvidence = string(c, map, emptyReason, path + "." + emptyReason);
        return new Rows(values, Boolean.TRUE.equals(complete) && expected != null && expected == values.size()
                && value(map, key) instanceof List<?> && (!values.isEmpty() || emptyReason == null || emptyEvidence != null));
    }

    private static Map<String, Map<String, Object>> index(Check c, List<Object> rows, String idKey, String path) {
        Map<String, Map<String, Object>> indexed = new TreeMap<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = object(c, rows.get(i), path + "[" + i + "]");
            String id = string(c, row, idKey, path + "[" + i + "]." + idKey);
            if (id != null && indexed.putIfAbsent(id, row) != null) c.fail(path + "[" + i + "]", "Duplicate " + idKey);
        }
        return indexed;
    }

    private static Map<String, Object> section(Context x, Check c, String name, boolean postSession) {
        Map<String, Object> map = object(c, value(x.root, name), name);
        Boolean complete = bool(c, map, "complete", name + ".complete");
        if (Boolean.FALSE.equals(complete)) c.incomplete(name + ".complete", "UNKNOWN: caller declares this evidence partial");
        Instant checked = time(c, map, "checked_at_utc", name + ".checked_at_utc");
        if (postSession) after(c, checked, x.end, name + ".checked_at_utc", "Evidence review predates session end");
        else before(c, checked, x.start, name + ".checked_at_utc", "Pre-session review follows session start");
        return map;
    }

    private static Object value(Map<String, Object> map, String key) { return map == null ? null : map.get(key); }
    private static boolean absent(Object value) { return value == null || value instanceof String s && s.isBlank(); }
    private static Map<String, Object> object(Check c, Object value, String path) {
        if (absent(value)) { c.missing(path); return null; }
        Map<String, Object> map = SessionEvidenceBundle.object(value);
        if (map == null) c.fail(path, "Expected a JSON object");
        return map;
    }
    @SuppressWarnings("unchecked")
    private static List<Object> array(Check c, Map<String, Object> map, String key, String path) {
        Object value = value(map, key);
        if (absent(value)) { c.missing(path); return List.of(); }
        if (!(value instanceof List<?>)) { c.fail(path, "Expected a JSON array"); return List.of(); }
        return (List<Object>) value;
    }
    private static String string(Check c, Map<String, Object> map, String key, String path) {
        Object value = value(map, key);
        if (absent(value)) { c.missing(path); return null; }
        if (!(value instanceof String text)) { c.fail(path, "Expected a nonblank JSON string"); return null; }
        return text;
    }
    private static Boolean bool(Check c, Map<String, Object> map, String key, String path) {
        Object value = value(map, key);
        if (absent(value)) { c.missing(path); return null; }
        if (!(value instanceof Boolean flag)) { c.fail(path, "Expected an explicit JSON boolean"); return null; }
        return flag;
    }
    private static void positive(Check c, Map<String, Object> map, String key, String path) {
        Boolean flag = bool(c, map, key, path);
        if (flag != null) c.require(flag, path, "Caller explicitly reports an unsuccessful check/action");
    }
    private static BigDecimal decimal(Check c, Map<String, Object> map, String key, String path) {
        Object value = value(map, key);
        if (absent(value)) { c.missing(path); return null; }
        if (!(value instanceof BigDecimal number) || number.precision() > 30 || Math.abs((long) number.scale()) > 9) {
            c.fail(path, "Expected an exact JSON decimal with at most 30 significant digits and scale between -9 and 9");
            return null;
        }
        return number;
    }
    private static Long integer(Check c, Map<String, Object> map, String key, String path) {
        BigDecimal decimal = decimal(c, map, key, path);
        if (decimal == null) return null;
        try { return decimal.longValueExact(); }
        catch (ArithmeticException invalid) { c.fail(path, "Expected a 64-bit integer"); return null; }
    }
    private static Long nonnegative(Check c, Map<String, Object> map, String key, String path) {
        Long value = integer(c, map, key, path);
        if (value != null && value < 0) { c.fail(path, "Expected a nonnegative integer"); return null; }
        return value;
    }
    private static Instant time(Check c, Map<String, Object> map, String key, String path) {
        String value = string(c, map, key, path);
        if (value == null) return null;
        try {
            if (!value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z")) throw new DateTimeParseException("UTC required", value, 0);
            return Instant.parse(value);
        } catch (DateTimeParseException invalid) { c.fail(path, "Expected an explicit valid UTC timestamp ending in Z"); return null; }
    }
    private static void allowed(Check c, String value, String path, String... allowed) {
        if (value != null) c.require(List.of(allowed).contains(value), path, "Unsupported value for the versioned evidence contract");
    }
    private static void same(Check c, Object left, Object right, String path, String message) {
        if (left != null && right != null) {
            boolean equal = left instanceof BigDecimal a && right instanceof BigDecimal b ? a.compareTo(b) == 0 : left.equals(right);
            c.require(equal, path, message);
        }
    }
    private static void before(Check c, Instant observed, Instant boundary, String path, String message) {
        if (observed != null && boundary != null) c.require(!observed.isAfter(boundary), path, message);
    }
    private static void after(Check c, Instant observed, Instant boundary, String path, String message) {
        if (observed != null && boundary != null) c.require(!observed.isBefore(boundary), path, message);
    }
    private static void within(Check c, Instant observed, Instant start, Instant end, String path) {
        after(c, observed, start, path, "Timestamp precedes the declared session/window");
        before(c, observed, end, path, "Timestamp follows the declared session/window");
    }
    private static void identity(Check c, Map<String, Object> map, Context x, String path) {
        same(c, string(c, map, "session_id", path + ".session_id"), x.sessionId, path + ".session_id", "Session identity mismatch");
        same(c, string(c, map, "run_id", path + ".run_id"), x.runId, path + ".run_id", "Run identity mismatch");
    }
    private static String instrument(Context x, Check c, Map<String, Object> map, String path) {
        String id = string(c, map, "instrument_id", path + ".instrument_id");
        if (id != null && !x.instruments.isEmpty()) c.require(x.instruments.containsKey(id), path + ".instrument_id", "Unplanned instrument");
        return id;
    }
    private static byte[] requireFile(Context x, Check c, String name, String path) {
        if (name == null) return null;
        byte[] bytes = x.bundle.files.get(name);
        if (bytes == null) c.incomplete(path, "UNKNOWN: declared evidence artifact is missing, unreadable, or undeclared");
        return bytes;
    }
    private static Map<String, Object> artifactObject(Context x, Check c, String name, String path) {
        byte[] bytes = requireFile(x, c, name, path);
        if (bytes == null) return null;
        try { return object(c, SessionEvidenceBundle.parse(bytes), path + ".contents"); }
        catch (IOException invalid) { c.fail(path, "Artifact is malformed UTF-8/JSON"); return null; }
    }

    private record Order(String instrument, String side, BigDecimal remaining) { }

    private static Result risk(Context x) {
        Check c = x.check("risk_gateway_reservation_reconciliation");
        Map<String, Object> risk = section(x, c, "risk", true);
        identity(c, risk, x, "risk");
        String configHash = string(c, risk, "config_sha256", "risk.config_sha256");
        boolean configurationFound = false;
        Map<String, Object> run = SessionEvidenceBundle.object(value(x.root, "run"));
        if (value(run, "artifacts") instanceof List<?> artifacts) for (Object entry : artifacts) {
            Map<String, Object> artifact = SessionEvidenceBundle.object(entry);
            if ("CONFIG".equals(value(artifact, "role"))) {
                configurationFound = true;
                Object path = value(artifact, "path");
                if (path instanceof String name) {
                    String hash = x.bundle.hashes.get(name);
                    if (hash == null) c.missing("risk.configuration_artifact");
                    else same(c, configHash, hash, "risk.config_sha256", "Gateway configuration is not bound to the supplied configuration artifact");
                }
            }
        }
        if (!configurationFound) c.missing("risk.configuration_artifact");
        Map<String, Object> initial = object(c, value(risk, "initial"), "risk.initial");
        Map<String, Object> ending = object(c, value(risk, "final"), "risk.final");
        before(c, time(c, initial, "timestamp_utc", "risk.initial.timestamp_utc"), x.start, "risk.initial.timestamp_utc", "Starting risk snapshot follows session start");
        after(c, time(c, ending, "timestamp_utc", "risk.final.timestamp_utc"), x.end, "risk.final.timestamp_utc", "Ending risk snapshot predates session end");
        before(c, time(c, ending, "timestamp_utc", "risk.final.timestamp_utc"), time(c, risk, "checked_at_utc", "risk.checked_at_utc"),
                "risk.final.timestamp_utc", "Ending risk snapshot follows its reconciliation review");
        String state = string(c, initial, "state", "risk.initial.state");
        allowed(c, state, "risk.initial.state", "ACTIVE", "HALTED");
        if (state != null) c.require(state.equals("ACTIVE"), "risk.initial.state", "Gateway was not active at the start of the declared session");
        String finalState = string(c, ending, "state", "risk.final.state");
        allowed(c, finalState, "risk.final.state", "ACTIVE", "HALTED");
        if (finalState != null && x.outcome != null) same(c, finalState, x.outcome.equals("HALTED") ? "HALTED" : "ACTIVE", "risk.final.state", "Gateway state contradicts session outcome");
        Long initialReserved = nonnegative(c, initial, "reserved_units", "risk.initial.reserved_units");
        Long initialOrders = nonnegative(c, initial, "working_order_count", "risk.initial.working_order_count");
        if (initialReserved != null) c.require(initialReserved == 0, "risk.initial.reserved_units", "Pre-session reservations must be explicitly reconciled to zero");
        if (initialOrders != null) c.require(initialOrders == 0, "risk.initial.working_order_count", "Pre-session working orders must be explicitly reconciled to zero");
        Map<String, BigDecimal> positions = positions(x, c, initial, "risk.initial");
        positions.forEach((id, quantity) -> c.require(quantity.signum() == 0, "risk.initial.positions." + id, "Pre-session position is open"));
        Map<String, BigDecimal> reportedPositions = positions(x, c, ending, "risk.final");
        Long finalReserved = nonnegative(c, ending, "reserved_units", "risk.final.reserved_units");
        Long finalOrders = nonnegative(c, ending, "working_order_count", "risk.final.working_order_count");
        if (finalReserved != null) c.require(finalReserved == 0, "risk.final.reserved_units", "Unreleased end-of-session reservations");
        if (finalOrders != null) c.require(finalOrders == 0, "risk.final.working_order_count", "Working orders remain at session end");

        Rows events = rows(c, risk, "events", "expected_event_count", "risk", "no_activity_reason");
        Map<String, Map<String, Object>> eventIds = index(c, events.values, "event_id", "risk.events");
        Map<String, Object> close = object(c, value(x.root, "closing"), "closing");
        Rows executions = rows(c, close, "executions", "expected_execution_count", "closing", "no_execution_reason");
        Map<String, Map<String, Object>> executionIds = index(c, executions.values, "execution_id", "closing.executions");
        Map<String, Object> settlement = object(c, value(x.root, "settlement"), "settlement");
        Rows settlements = rows(c, settlement, "events", "expected_event_count", "settlement", null);
        Map<String, Map<String, Object>> settlementIds = index(c, settlements.values, "event_id", "settlement.events");
        Map<String, Order> orders = new TreeMap<>();
        Set<String> usedOrders = new HashSet<>(), matchedExecutions = new HashSet<>(), matchedSettlements = new HashSet<>();
        Instant previous = x.start;
        boolean known = events.complete && eventIds.size() == events.values.size() && initialReserved != null
                && initialReserved == 0 && initialOrders != null && initialOrders == 0 && state != null
                && positions.size() == x.instruments.size() && !x.instruments.isEmpty();
        boolean sawHalt = false;
        for (int i = 0; i < events.values.size(); i++) {
            String path = "risk.events[" + i + "]";
            Map<String, Object> event = object(c, events.values.get(i), path);
            String kind = string(c, event, "kind", path + ".kind");
            allowed(c, kind, path + ".kind", "RESERVE", "FILL", "CANCEL", "SETTLE", "HALT");
            Instant timestamp = time(c, event, "timestamp_utc", path + ".timestamp_utc");
            within(c, timestamp, x.start, x.end, path + ".timestamp_utc");
            after(c, timestamp, previous, path + ".timestamp_utc", "Risk events regress in time");
            if (timestamp != null) previous = timestamp;
            if (kind == null || timestamp == null || event == null) { known = false; continue; }
            if (kind.equals("HALT")) {
                c.require(!sawHalt, path, "Duplicate halt transition");
                sawHalt = true;
                state = "HALTED";
                String stop = string(c, event, "stop_id", path + ".stop_id");
                Map<String, Object> record = referencedRecord(x, c, "stops", "stop_id", stop, path + ".stop_id");
                if (record != null) {
                    same(c, "HALT", string(c, record, "kind", path + ".stop.kind"), path, "Gateway halt references a stop of another kind");
                    same(c, timestamp, time(c, record, "effective_at_utc", path + ".stop.effective_at_utc"), path, "Gateway halt does not match stop effective timestamp");
                }
                continue;
            }
            String instrument = instrument(x, c, event, path);
            if (kind.equals("SETTLE")) {
                String id = string(c, event, "settlement_event_id", path + ".settlement_event_id");
                BigDecimal quantity = signedUnits(c, event, "signed_quantity", path + ".signed_quantity");
                if (id != null && !matchedSettlements.add(id)) c.fail(path, "Settlement event consumed more than once");
                Map<String, Object> counterpart = counterpart(c, settlementIds, settlements, id, path, "settlement event");
                if (counterpart != null) {
                    same(c, instrument, string(c, counterpart, "instrument_id", path + ".settlement.instrument_id"), path, "Settlement instrument mismatch");
                    same(c, quantity, signedUnits(c, counterpart, "signed_quantity", path + ".settlement.signed_quantity"), path, "Settlement quantity mismatch");
                    same(c, timestamp, time(c, counterpart, "timestamp_utc", path + ".settlement.timestamp_utc"), path, "Settlement timestamp mismatch");
                }
                if (instrument == null || quantity == null) { known = false; continue; }
                BigDecimal position = positions.get(instrument);
                if (position != null) {
                    c.require(position.signum() == quantity.signum() && position.abs().compareTo(quantity.abs()) >= 0,
                            path, "Settlement does not close an existing signed position");
                    positions.put(instrument, position.subtract(quantity));
                }
                continue;
            }
            String orderId = string(c, event, "order_id", path + ".order_id");
            BigDecimal quantity = units(c, event, path);
            String side = string(c, event, "side", path + ".side");
            allowed(c, side, path + ".side", "BUY", "SELL");
            if (instrument == null || orderId == null || quantity == null || side == null) { known = false; continue; }
            if (kind.equals("RESERVE")) {
                if (state != null) c.require(state.equals("ACTIVE"), path, "New reservation after gateway halt");
                c.require(usedOrders.add(orderId), path, "Order ID reused for another reservation");
                orders.putIfAbsent(orderId, new Order(instrument, side, quantity));
                eligibleInput(x, c, event, instrument, timestamp, path);
                continue;
            }
            Order order = orders.get(orderId);
            if (order == null) {
                if (known) c.fail(path, "Unmatched fill/cancel: no working reservation");
                else c.incomplete(path, "UNKNOWN: reservation cannot be matched from partial risk evidence");
            } else {
                same(c, instrument, order.instrument, path, "Event instrument differs from its reservation");
                same(c, side, order.side, path, "Event side differs from its reservation");
                c.require(quantity.compareTo(order.remaining) <= 0, path, "Event quantity exceeds the remaining reservation");
                BigDecimal remaining = order.remaining.subtract(quantity);
                if (remaining.signum() == 0) orders.remove(orderId);
                else orders.put(orderId, new Order(order.instrument, order.side, remaining));
            }
            if (kind.equals("FILL")) {
                String id = string(c, event, "execution_id", path + ".execution_id");
                if (id != null && !matchedExecutions.add(id)) c.fail(path, "Execution consumed more than once");
                Map<String, Object> execution = counterpart(c, executionIds, executions, id, path, "execution");
                if (execution != null) {
                    for (String field : List.of("order_id", "instrument_id", "side")) same(c,
                            string(c, event, field, path + "." + field), string(c, execution, field, path + ".execution." + field), path, "Fill/execution " + field + " mismatch");
                    same(c, quantity, units(c, execution, path + ".execution"), path, "Fill/execution quantity mismatch");
                    same(c, timestamp, time(c, execution, "timestamp_utc", path + ".execution.timestamp_utc"), path, "Fill/execution timestamp mismatch");
                }
                BigDecimal position = positions.get(instrument);
                if (position != null) {
                    BigDecimal next = position.add(side.equals("BUY") ? quantity : quantity.negate());
                    if (sawHalt) c.require(next.abs().compareTo(position.abs()) <= 0, path, "Fill increased risk after the recorded halt");
                    positions.put(instrument, next);
                }
            }
        }
        if (x.outcome != null && x.outcome.equals("HALTED") && !sawHalt) c.missing("risk.events.HALT");
        if (known) same(c, state, finalState, "risk.final.state", "Risk events do not reconstruct the final gateway state");
        if (known) {
            BigDecimal reserved = orders.values().stream().map(Order::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (finalReserved != null) same(c, reserved, BigDecimal.valueOf(finalReserved), "risk.final.reserved_units", "Reservation events do not reconcile to ending snapshot");
            if (finalOrders != null) same(c, (long) orders.size(), finalOrders, "risk.final.working_order_count", "Working-order events do not reconcile to ending snapshot");
            reportedPositions.forEach((id, quantity) -> same(c, quantity, positions.get(id), "risk.final.positions." + id, "Risk position events do not reconcile to ending snapshot"));
            unmatched(c, executionIds, executions, matchedExecutions, "closing.executions", "Execution has no risk fill event");
            unmatched(c, settlementIds, settlements, matchedSettlements, "settlement.events", "Settlement has no risk reconciliation event");
        } else c.incomplete("risk.reconciliation", "UNKNOWN: risk ledger reconstruction is incomplete");
        return c.result();
    }

    private static void unmatched(Check c, Map<String, Map<String, Object>> indexed, Rows rows,
            Set<String> matched, String path, String message) {
        if (rows.complete && indexed.size() == rows.values.size()) for (String id : indexed.keySet()) {
            if (!matched.contains(id)) c.fail(path + "." + id, message);
        }
    }

    private static Map<String, Object> counterpart(Check c, Map<String, Map<String, Object>> indexed,
            Rows rows, String id, String path, String kind) {
        if (id == null) return null;
        Map<String, Object> result = indexed.get(id);
        if (result == null) {
            if (rows.complete && indexed.size() == rows.values.size()) c.fail(path, "Unmatched " + kind + " reference: " + id);
            else c.incomplete(path, "UNKNOWN: " + kind + " cannot be matched from incomplete evidence");
        }
        return result;
    }

    private static Map<String, BigDecimal> positions(Context x, Check c, Map<String, Object> owner, String path) {
        Map<String, Object> map = object(c, value(owner, "positions"), path + ".positions");
        Map<String, BigDecimal> result = new TreeMap<>();
        for (String id : x.instruments.keySet()) {
            Long units = integer(c, map, id, path + ".positions." + id);
            if (units != null) result.put(id, BigDecimal.valueOf(units));
        }
        if (map != null) for (String id : map.keySet()) if (!x.instruments.containsKey(id) && !x.instruments.isEmpty()) c.fail(path + ".positions." + id, "Unplanned position instrument");
        return result;
    }

    private static BigDecimal units(Check c, Map<String, Object> row, String path) {
        Long value = integer(c, row, "quantity", path + ".quantity");
        if (value == null) return null;
        if (value <= 0) { c.fail(path + ".quantity", "Quantity must be a positive integer"); return null; }
        return BigDecimal.valueOf(value);
    }

    private static BigDecimal signedUnits(Check c, Map<String, Object> row, String key, String path) {
        Long value = integer(c, row, key, path);
        if (value == null) return null;
        if (value == 0) { c.fail(path, "Signed settlement quantity must be nonzero"); return null; }
        return BigDecimal.valueOf(value);
    }

    private static void eligibleInput(Context x, Check c, Map<String, Object> event, String instrument,
            Instant timestamp, String path) {
        String windowId = string(c, event, "coverage_window_id", path + ".coverage_window_id");
        String eventId = string(c, event, "coverage_event_id", path + ".coverage_event_id");
        if (windowId == null || eventId == null) return;
        Map<String, Object> coverage = object(c, value(x.root, "coverage"), "coverage");
        Boolean coverageComplete = bool(c, coverage, "complete", "coverage.complete");
        if (Boolean.FALSE.equals(coverageComplete)) c.incomplete(path + ".input", "UNKNOWN: the input gate timeline is partial");
        List<Object> windows = array(c, coverage, "windows", "coverage.windows");
        for (Object entry : windows) {
            Map<String, Object> window = SessionEvidenceBundle.object(entry);
            if (!windowId.equals(value(window, "window_id"))) continue;
            same(c, instrument, string(c, window, "instrument_id", path + ".input.instrument_id"), path, "Reservation uses another instrument's input window");
            within(c, timestamp, time(c, window, "start_utc", path + ".input.start_utc"),
                    time(c, window, "end_utc", path + ".input.end_utc"), path + ".input");
            Rows observations = rows(c, window, "observations", "expected_observation_count", path + ".input", null);
            Map<String, Object> latest = null;
            Instant latestAt = null;
            boolean timelineKnown = Boolean.TRUE.equals(coverageComplete) && observations.complete;
            for (Object observation : observations.values) {
                Map<String, Object> candidate = object(c, observation, path + ".input.observations");
                Instant at = time(c, candidate, "observed_at_utc", path + ".input.observations.observed_at_utc");
                if (at == null) timelineKnown = false;
                if (timestamp != null && at != null && !at.isAfter(timestamp) && (latestAt == null || !at.isBefore(latestAt))) {
                    latest = candidate; latestAt = at;
                }
            }
            if (latest != null && timelineKnown) {
                positive(c, latest, "new_risk_allowed", path + ".latest_input.new_risk_allowed");
                same(c, "ALLOW", string(c, latest, "action", path + ".latest_input.action"), path,
                        "Reservation bypassed the latest pause/halt observation");
            } else c.incomplete(path + ".latest_input", "UNKNOWN: the latest input gate cannot be established from partial observations");
            for (Object observation : observations.values) {
                Map<String, Object> sample = SessionEvidenceBundle.object(observation);
                if (!eventId.equals(value(sample, "event_id"))) continue;
                positive(c, sample, "new_risk_allowed", path + ".input.new_risk_allowed");
                Instant observed = time(c, sample, "observed_at_utc", path + ".input.observed_at_utc");
                Instant input = time(c, sample, "input_at_utc", path + ".input.input_at_utc");
                before(c, observed, timestamp, path, "Reservation uses an observation received later than the decision");
                Long maxAge = nonnegative(c, window, "max_age_ms", path + ".input.max_age_ms");
                if (timestamp != null && input != null && maxAge != null) {
                    Duration age = Duration.between(input, timestamp);
                    c.require(!age.isNegative() && age.compareTo(Duration.ofMillis(maxAge)) <= 0, path, "Reservation used stale/future input at decision time");
                }
                return;
            }
            c.missing(path + ".coverage_event_id"); return;
        }
        c.missing(path + ".coverage_window_id");
    }

    private static Result settlementCosts(Context x) {
        Check c = x.check("settlement_cost_coverage");
        Map<String, Object> settlement = section(x, c, "settlement", true);
        Boolean applicable = bool(c, settlement, "applicable", "settlement.applicable");
        Set<String> due = new HashSet<>();
        boolean applicabilityKnown = !x.instruments.isEmpty();
        for (var entry : x.instruments.entrySet()) {
            Boolean flag = bool(c, entry.getValue(), "settlement_due", "instruments." + entry.getKey() + ".settlement_due");
            if (flag == null) applicabilityKnown = false;
            if (Boolean.TRUE.equals(flag)) due.add(entry.getKey());
        }
        if (applicable != null && applicabilityKnown) same(c, applicable, !due.isEmpty(), "settlement.applicable", "Settlement applicability contradicts the explicit instrument declarations");
        if (Boolean.FALSE.equals(applicable)) string(c, settlement, "not_applicable_reason", "settlement.not_applicable_reason");
        Rows settlementRows = rows(c, settlement, "events", "expected_event_count", "settlement", null);
        Map<String, Map<String, Object>> settlementIds = index(c, settlementRows.values, "event_id", "settlement.events");
        if (Boolean.FALSE.equals(applicable) && !settlementRows.values.isEmpty()) c.fail("settlement.events", "Settlement events supplied while explicitly declared not applicable");
        Set<String> settled = new HashSet<>();
        Map<String, Object> close = object(c, value(x.root, "closing"), "closing");
        Rows executionRows = rows(c, close, "executions", "expected_execution_count", "closing", "no_execution_reason");
        Map<String, Map<String, Object>> executionIds = index(c, executionRows.values, "execution_id", "closing.executions");
        Map<String, Target> targets = new TreeMap<>();
        executionIds.forEach((id, row) -> targets.put("EXECUTION/" + id, target(c, row, "closing.executions." + id)));
        settlementIds.forEach((id, row) -> {
            String path = "settlement.events." + id;
            String instrument = instrument(x, c, row, path);
            if (instrument != null) {
                settled.add(instrument);
                if (applicabilityKnownFor(x, instrument)) c.require(Boolean.TRUE.equals(value(x.instruments.get(instrument), "settlement_due")), path, "Settlement event for an instrument not due for settlement");
            }
            BigDecimal quantity = signedUnits(c, row, "signed_quantity", path + ".signed_quantity");
            BigDecimal price = nonnegativeDecimal(c, row, "price", path + ".price");
            BigDecimal cash = decimal(c, row, "cash_amount", path + ".cash_amount");
            BigDecimal multiplier = multiplier(x, c, instrument, path);
            if (quantity != null && price != null && multiplier != null) same(c, cash, quantity.multiply(price).multiply(multiplier), path + ".cash_amount", "Settlement cash does not reconcile to signed quantity, price and multiplier");
            string(c, row, "source_ref", path + ".source_ref");
            Instant time = time(c, row, "timestamp_utc", path + ".timestamp_utc");
            within(c, time, x.start, x.end, path + ".timestamp_utc");
            targets.put("SETTLEMENT/" + id, target(c, row, path));
        });
        for (String id : due) if (!settled.contains(id)) c.missing("settlement.events.instrument." + id);
        if (Boolean.TRUE.equals(applicable) && settlementRows.values.isEmpty()) c.incomplete("settlement.events", "UNKNOWN: applicable settlement needs nonempty event evidence");
        Map<String, Object> costs = object(c, value(settlement, "costs"), "settlement.costs");
        after(c, time(c, costs, "checked_at_utc", "settlement.costs.checked_at_utc"), x.end, "settlement.costs.checked_at_utc", "Cost review predates session end");
        Rows charges = rows(c, costs, "charges", "expected_charge_count", "settlement.costs", targets.isEmpty() ? "no_charges_reason" : null);
        List<Object> components = array(c, costs, "required_components", "settlement.costs.required_components");
        Set<String> required = new HashSet<>();
        for (int i = 0; i < components.size(); i++) {
            Object component = components.get(i);
            if (absent(component)) c.missing("settlement.costs.required_components[" + i + "]");
            else if (!(component instanceof String text)) c.fail("settlement.costs.required_components", "Component names must be strings");
            else if (!required.add(text)) c.fail("settlement.costs.required_components", "Duplicate required component");
        }
        if (required.isEmpty()) c.incomplete("settlement.costs.required_components", "UNKNOWN: nonempty explicit cost-component coverage is required");
        Set<String> chargeIds = new HashSet<>(), covered = new HashSet<>(), unknownAmounts = new HashSet<>();
        Map<String, BigDecimal> sums = new TreeMap<>();
        for (int i = 0; i < charges.values.size(); i++) {
            String path = "settlement.costs.charges[" + i + "]";
            Map<String, Object> row = object(c, charges.values.get(i), path);
            String id = string(c, row, "charge_id", path + ".charge_id");
            if (id != null && !chargeIds.add(id)) c.fail(path, "Duplicate charge ID");
            String kind = string(c, row, "target_kind", path + ".target_kind");
            allowed(c, kind, path + ".target_kind", "EXECUTION", "SETTLEMENT");
            String targetId = string(c, row, "target_id", path + ".target_id");
            String component = string(c, row, "component", path + ".component");
            if (component != null && !required.isEmpty()) c.require(required.contains(component), path + ".component", "Undeclared cost component");
            BigDecimal amount = nonnegativeDecimal(c, row, "amount", path + ".amount");
            string(c, row, "source_ref", path + ".source_ref");
            string(c, row, "rounding", path + ".rounding");
            Instant from = time(c, row, "effective_from_utc", path + ".effective_from_utc");
            Instant to = time(c, row, "effective_to_utc", path + ".effective_to_utc");
            if (from != null && to != null) c.require(to.isAfter(from), path, "Cost source coverage interval is empty or reversed");
            if (kind == null || targetId == null) continue;
            String targetKey = kind + "/" + targetId;
            Target target = targets.get(targetKey);
            if (target == null) {
                Rows targetRows = kind.equals("SETTLEMENT") ? settlementRows : executionRows;
                Map<String, Map<String, Object>> ids = kind.equals("SETTLEMENT") ? settlementIds : executionIds;
                counterpart(c, ids, targetRows, targetId, path, "cost target");
            } else {
                after(c, target.time, from, path, "Charge source starts after its event");
                if (target.time != null && to != null) c.require(target.time.isBefore(to), path, "Charge source does not cover its event (end is exclusive)");
            }
            if (component != null && !covered.add(targetKey + "/" + component)) c.fail(path, "Duplicate target/component charge");
            if (amount == null) unknownAmounts.add(targetKey);
            else sums.merge(targetKey, amount, BigDecimal::add);
        }
        targets.forEach((targetKey, target) -> {
            boolean allComponents = !required.isEmpty();
            for (String component : required) if (!covered.contains(targetKey + "/" + component)) {
                c.missing("settlement.costs.coverage." + targetKey + "/" + component); allComponents = false;
            }
            if (allComponents && charges.complete && !unknownAmounts.contains(targetKey)) same(c, target.fee, sums.get(targetKey), "settlement.costs." + targetKey, "Itemized charges do not reconcile to the event fee");
        });
        return c.result();
    }

    private record Target(Instant time, BigDecimal fee) { }
    private static Target target(Check c, Map<String, Object> row, String path) {
        return new Target(time(c, row, "timestamp_utc", path + ".timestamp_utc"), nonnegativeDecimal(c, row, "fee", path + ".fee"));
    }
    private static boolean applicabilityKnownFor(Context x, String instrument) {
        return value(x.instruments.get(instrument), "settlement_due") instanceof Boolean;
    }
    private static BigDecimal nonnegativeDecimal(Check c, Map<String, Object> row, String key, String path) {
        BigDecimal value = decimal(c, row, key, path);
        if (value != null && value.signum() < 0) { c.fail(path, "Amount must be nonnegative"); return null; }
        return value;
    }
    private static BigDecimal multiplier(Context x, Check c, String id, String path) {
        return id == null ? null : decimal(c, x.instruments.get(id), "multiplier", path + ".multiplier");
    }

    private static Result closing(Context x) {
        Check c = x.check("end_positions_pnl_fee_reconciliation");
        Map<String, Object> close = section(x, c, "closing", true);
        Map<String, Object> completedRun = object(c, value(x.root, "run"), "run");
        after(c, time(c, close, "checked_at_utc", "closing.checked_at_utc"),
                time(c, completedRun, "completed_at_utc", "run.completed_at_utc"), "closing.checked_at_utc",
                "Closing reconciliation predates run completion evidence");
        Rows executions = rows(c, close, "executions", "expected_execution_count", "closing", "no_execution_reason");
        Map<String, Map<String, Object>> executionIds = index(c, executions.values, "execution_id", "closing.executions");
        Map<String, Object> settlement = object(c, value(x.root, "settlement"), "settlement");
        Boolean applicable = bool(c, settlement, "applicable", "settlement.applicable");
        Rows settlements = rows(c, settlement, "events", "expected_event_count", "settlement", null);
        Map<String, Map<String, Object>> settlementIds = index(c, settlements.values, "event_id", "settlement.events");
        Map<String, Object> risk = object(c, value(x.root, "risk"), "risk");
        Map<String, BigDecimal> initial = positions(x, c, object(c, value(risk, "initial"), "risk.initial"), "risk.initial");
        Map<String, BigDecimal> reconstructed = new TreeMap<>(initial);
        Map<String, BigDecimal> reported = positions(x, c, close, "closing");
        Map<String, BigDecimal> riskEnding = positions(x, c, object(c, value(risk, "final"), "risk.final"), "risk.final");
        reported.forEach((id, quantity) -> {
            c.require(quantity.signum() == 0, "closing.positions." + id, "Position remains open at session end");
            same(c, quantity, riskEnding.get(id), "closing.positions." + id, "Closing and gateway position snapshots differ");
        });
        BigDecimal grossCashflow = BigDecimal.ZERO, fees = BigDecimal.ZERO;
        boolean known = executions.complete && settlements.complete && executionIds.size() == executions.values.size()
                && settlementIds.size() == settlements.values.size() && applicable != null
                && initial.size() == x.instruments.size() && !x.instruments.isEmpty();
        boolean feesKnown = executions.complete && settlements.complete;
        for (int i = 0; i < executions.values.size(); i++) {
            String path = "closing.executions[" + i + "]";
            Map<String, Object> row = object(c, executions.values.get(i), path);
            string(c, row, "order_id", path + ".order_id");
            String id = instrument(x, c, row, path);
            BigDecimal quantity = units(c, row, path);
            String side = string(c, row, "side", path + ".side");
            allowed(c, side, path + ".side", "BUY", "SELL");
            BigDecimal price = nonnegativeDecimal(c, row, "price", path + ".price");
            BigDecimal fee = nonnegativeDecimal(c, row, "fee", path + ".fee");
            Instant timestamp = time(c, row, "timestamp_utc", path + ".timestamp_utc");
            within(c, timestamp, x.start, x.end, path + ".timestamp_utc");
            BigDecimal multiplier = multiplier(x, c, id, path);
            if (fee == null) feesKnown = false; else fees = fees.add(fee);
            if (quantity == null || side == null || price == null || multiplier == null || timestamp == null || id == null) { known = false; continue; }
            BigDecimal signed = side.equals("BUY") ? quantity : quantity.negate();
            if (reconstructed.containsKey(id)) reconstructed.put(id, reconstructed.get(id).add(signed));
            grossCashflow = grossCashflow.subtract(signed.multiply(price).multiply(multiplier));
        }
        for (int i = 0; i < settlements.values.size(); i++) {
            String path = "settlement.events[" + i + "]";
            Map<String, Object> row = object(c, settlements.values.get(i), path);
            String id = instrument(x, c, row, path);
            BigDecimal quantity = signedUnits(c, row, "signed_quantity", path + ".signed_quantity");
            BigDecimal cash = decimal(c, row, "cash_amount", path + ".cash_amount");
            BigDecimal fee = nonnegativeDecimal(c, row, "fee", path + ".fee");
            if (fee == null) feesKnown = false; else fees = fees.add(fee);
            if (id == null || quantity == null || cash == null) { known = false; continue; }
            if (reconstructed.containsKey(id)) reconstructed.put(id, reconstructed.get(id).subtract(quantity));
            grossCashflow = grossCashflow.add(cash);
        }
        BigDecimal initialCash = nonnegativeDecimal(c, close, "initial_cash", "closing.initial_cash");
        BigDecimal gross = decimal(c, close, "gross_pnl", "closing.gross_pnl");
        BigDecimal declaredFees = nonnegativeDecimal(c, close, "fees", "closing.fees");
        BigDecimal net = decimal(c, close, "net_pnl", "closing.net_pnl");
        BigDecimal endingCash = decimal(c, close, "ending_cash", "closing.ending_cash");
        if (gross != null && declaredFees != null) same(c, net, gross.subtract(declaredFees), "closing.net_pnl", "Net P&L does not equal gross P&L minus fees");
        boolean flatSnapshots = !x.instruments.isEmpty() && initial.size() == x.instruments.size()
                && reported.size() == x.instruments.size() && initial.values().stream().allMatch(quantity -> quantity.signum() == 0)
                && reported.values().stream().allMatch(quantity -> quantity.signum() == 0);
        if (initialCash != null && net != null && flatSnapshots) same(c, endingCash, initialCash.add(net), "closing.ending_cash", "Ending cash does not reconcile to starting cash and net P&L");
        if (feesKnown) same(c, declaredFees, fees, "closing.fees", "Execution and settlement fees do not reconcile to the closing total");
        else c.incomplete("closing.fee_reconciliation", "UNKNOWN: event fee evidence is incomplete");
        if (known) {
            reconstructed.forEach((id, quantity) -> {
                c.require(quantity.signum() == 0, "closing.reconstructed_positions." + id, "Reconstructed position remains open at session end");
                same(c, quantity, reported.get(id), "closing.positions." + id, "Execution/settlement position ledger does not reconcile");
            });
            boolean flatStart = initial.values().stream().allMatch(quantity -> quantity.signum() == 0);
            boolean flatEnd = reconstructed.values().stream().allMatch(quantity -> quantity.signum() == 0);
            if (flatStart && flatEnd) same(c, gross, grossCashflow, "closing.gross_pnl", "Gross P&L does not reconcile to signed execution and settlement cashflows");
        } else c.incomplete("closing.position_pnl_reconciliation", "UNKNOWN: execution/settlement reconstruction is incomplete");
        Map<String, Object> run = SessionEvidenceBundle.object(value(x.root, "run"));
        if (value(run, "artifacts") instanceof List<?> artifacts) for (Object entry : artifacts) {
            Map<String, Object> artifact = SessionEvidenceBundle.object(entry);
            if (!"OUTPUT".equals(value(artifact, "role"))) continue;
            String name = string(c, artifact, "path", "run.output.path");
            Map<String, Object> output = artifactObject(x, c, name, "run.output");
            if (output == null) continue;
            for (String field : List.of("gross_pnl", "fees", "net_pnl", "ending_cash")) same(c,
                    decimal(c, close, field, "closing." + field), decimal(c, output, field, "run.output." + field), "run.output." + field, "Run output differs from closing evidence");
            Map<String, BigDecimal> outputPositions = positions(x, c, output, "run.output");
            outputPositions.forEach((id, quantity) -> same(c, quantity, reported.get(id), "run.output.positions." + id, "Run output positions differ from closing evidence"));
        }
        return c.result();
    }

    private static Result operator(Context x) {
        Check c = x.check("operator_acknowledgements_incident_stop_records");
        Map<String, Object> operator = section(x, c, "operator", true);
        Rows ackRows = rows(c, operator, "acknowledgements", "expected_acknowledgement_count", "operator", null);
        Map<String, Map<String, Object>> acks = index(c, ackRows.values, "ack_id", "operator.acknowledgements");
        List<String> required = new ArrayList<>(ACKS);
        if ("HALTED".equals(x.outcome)) required.add("HALT_AUTHORITY");
        for (String id : required) if (!acks.containsKey(id)) c.missing("operator.acknowledgements." + id);
        for (var entry : acks.entrySet()) {
            String id = entry.getKey(), path = "operator.acknowledgements." + id;
            Map<String, Object> ack = entry.getValue();
            allowed(c, id, path + ".ack_id", "PRE_SESSION_READY", "SESSION_CLOSE", "RECONCILIATION_REVIEW", "INCIDENT_REVIEW", "HALT_AUTHORITY");
            string(c, ack, "operator_id", path + ".operator_id");
            string(c, ack, "evidence_ref", path + ".evidence_ref");
            Instant timestamp = time(c, ack, "timestamp_utc", path + ".timestamp_utc");
            if (id.equals("PRE_SESSION_READY")) {
                before(c, timestamp, x.start, path + ".timestamp_utc", "Pre-session readiness was acknowledged after session start");
                Map<String, Object> pre = object(c, value(x.root, "pre_session"), "pre_session");
                after(c, timestamp, time(c, pre, "checked_at_utc", "pre_session.checked_at_utc"), path, "Readiness acknowledgement predates pre-session review");
                for (Object row : array(c, pre, "checks", "pre_session.checks")) {
                    Map<String, Object> check = object(c, row, "pre_session.checks");
                    after(c, timestamp, time(c, check, "checked_at_utc", "pre_session.checks.checked_at_utc"), path, "Readiness acknowledgement predates a required check");
                }
            } else {
                after(c, timestamp, x.end, path + ".timestamp_utc", "Closing/review acknowledgement predates session end");
                if (id.equals("SESSION_CLOSE") || id.equals("RECONCILIATION_REVIEW")) {
                    for (String name : List.of("closing", "risk", "settlement", "coverage")) {
                        Map<String, Object> section = object(c, value(x.root, name), name);
                        after(c, timestamp, time(c, section, "checked_at_utc", name + ".checked_at_utc"), path, "Acknowledgement predates " + name + " review");
                    }
                    Map<String, Object> run = object(c, value(x.root, "run"), "run");
                    after(c, timestamp, time(c, run, "completed_at_utc", "run.completed_at_utc"), path, "Acknowledgement predates run completion evidence");
                    Map<String, Object> settlement = object(c, value(x.root, "settlement"), "settlement");
                    Map<String, Object> costs = object(c, value(settlement, "costs"), "settlement.costs");
                    after(c, timestamp, time(c, costs, "checked_at_utc", "settlement.costs.checked_at_utc"), path, "Acknowledgement predates cost review");
                }
            }
        }
        string(c, operator, "reviewed_by", "operator.reviewed_by");
        Instant incidentReview = time(c, operator, "incident_reviewed_at_utc", "operator.incident_reviewed_at_utc");
        Instant stopReview = time(c, operator, "stop_reviewed_at_utc", "operator.stop_reviewed_at_utc");
        after(c, incidentReview, x.end, "operator.incident_reviewed_at_utc", "Incident register review predates session end");
        after(c, stopReview, x.end, "operator.stop_reviewed_at_utc", "Stop register review predates session end");
        if (acks.containsKey("SESSION_CLOSE")) {
            Instant closedAt = time(c, acks.get("SESSION_CLOSE"), "timestamp_utc", "operator.acknowledgements.SESSION_CLOSE.timestamp_utc");
            after(c, closedAt, incidentReview, "operator.acknowledgements.SESSION_CLOSE", "Session close predates incident register review");
            after(c, closedAt, stopReview, "operator.acknowledgements.SESSION_CLOSE", "Session close predates stop register review");
        }
        if (acks.containsKey("INCIDENT_REVIEW")) after(c,
                time(c, acks.get("INCIDENT_REVIEW"), "timestamp_utc", "operator.acknowledgements.INCIDENT_REVIEW.timestamp_utc"),
                incidentReview, "operator.acknowledgements.INCIDENT_REVIEW", "Incident acknowledgement predates register review");
        Rows incidents = rows(c, operator, "incidents", "incident_count", "operator", null);
        Rows stops = rows(c, operator, "stops", "stop_count", "operator", null);
        Boolean noIncidents = bool(c, operator, "no_incidents_confirmed", "operator.no_incidents_confirmed");
        Boolean noStops = bool(c, operator, "no_stops_confirmed", "operator.no_stops_confirmed");
        if (incidents.complete) same(c, noIncidents, incidents.values.isEmpty(), "operator.no_incidents_confirmed", "Explicit no-incident confirmation contradicts the incident register");
        if (stops.complete) same(c, noStops, stops.values.isEmpty(), "operator.no_stops_confirmed", "Explicit no-stop confirmation contradicts the stop register");
        Map<String, Map<String, Object>> incidentIds = index(c, incidents.values, "incident_id", "operator.incidents");
        index(c, stops.values, "stop_id", "operator.stops");
        Long openSevere = nonnegative(c, operator, "open_severe_incident_count", "operator.open_severe_incident_count");
        if (openSevere != null) c.require(openSevere == 0, "operator.open_severe_incident_count", "Severe incident remains open");
        long observedSevere = 0;
        boolean severitiesKnown = incidents.complete && incidentIds.size() == incidents.values.size();
        for (var entry : incidentIds.entrySet()) {
            String path = "operator.incidents." + entry.getKey();
            Map<String, Object> row = entry.getValue();
            String severity = string(c, row, "severity", path + ".severity");
            allowed(c, severity, path + ".severity", "MINOR", "SEVERE");
            String state = string(c, row, "state", path + ".state");
            allowed(c, state, path + ".state", "OPEN", "CLOSED");
            if (severity == null || state == null) severitiesKnown = false;
            if ("SEVERE".equals(severity) && "OPEN".equals(state)) {
                observedSevere++; c.fail(path, "Severe incident remains open");
            }
            Instant opened = time(c, row, "opened_at_utc", path + ".opened_at_utc");
            within(c, opened, x.start, x.end, path + ".opened_at_utc");
            Instant acknowledged = time(c, row, "acknowledged_at_utc", path + ".acknowledged_at_utc");
            after(c, acknowledged, opened, path, "Incident acknowledgement predates the incident");
            before(c, acknowledged, incidentReview, path, "Incident acknowledgement follows register review");
            string(c, row, "acknowledged_by", path + ".acknowledged_by");
            string(c, row, "reason", path + ".reason");
            if ("CLOSED".equals(state)) {
                Instant resolved = time(c, row, "resolved_at_utc", path + ".resolved_at_utc");
                after(c, resolved, opened, path, "Incident resolution predates the incident");
                before(c, resolved, incidentReview, path, "Incident resolution follows register review");
                string(c, row, "resolution_ref", path + ".resolution_ref");
            }
        }
        if (severitiesKnown && openSevere != null) same(c, observedSevere, openSevere, "operator.open_severe_incident_count", "Severe-incident count differs from the register");
        boolean haltStop = false;
        for (int i = 0; i < stops.values.size(); i++) {
            String path = "operator.stops[" + i + "]";
            Map<String, Object> row = object(c, stops.values.get(i), path);
            String kind = string(c, row, "kind", path + ".kind");
            allowed(c, kind, path + ".kind", "PAUSE", "HALT");
            if ("HALT".equals(kind)) {
                haltStop = true;
                if ("COMPLETED".equals(x.outcome)) c.fail(path, "Halt stop contradicts a completed session in this no-resume evidence contract");
            }
            Instant requested = time(c, row, "requested_at_utc", path + ".requested_at_utc");
            Instant effective = time(c, row, "effective_at_utc", path + ".effective_at_utc");
            Instant acknowledged = time(c, row, "acknowledged_at_utc", path + ".acknowledged_at_utc");
            within(c, requested, x.start, x.end, path + ".requested_at_utc");
            within(c, effective, x.start, x.end, path + ".effective_at_utc");
            after(c, effective, requested, path, "Stop effective timestamp predates its request");
            after(c, acknowledged, effective, path, "Stop acknowledgement predates effective stop");
            before(c, acknowledged, stopReview, path, "Stop acknowledgement follows register review");
            string(c, row, "requested_by", path + ".requested_by");
            string(c, row, "acknowledged_by", path + ".acknowledged_by");
            string(c, row, "reason", path + ".reason");
            for (String flag : List.of("new_risk_blocked", "orders_reconciled", "positions_reconciled")) positive(c, row, flag, path + "." + flag);
            String incident = string(c, row, "incident_id", path + ".incident_id");
            counterpart(c, incidentIds, incidents, incident, path, "stop incident");
            if ("HALT".equals(kind)) {
                String stopId = string(c, row, "stop_id", path + ".stop_id");
                Map<String, Object> risk = object(c, value(x.root, "risk"), "risk");
                boolean matched = false;
                for (Object event : array(c, risk, "events", "risk.events")) {
                    Map<String, Object> record = SessionEvidenceBundle.object(event);
                    if (stopId != null && stopId.equals(value(record, "stop_id")) && "HALT".equals(value(record, "kind"))) {
                        matched = true;
                        same(c, effective, time(c, record, "timestamp_utc", path + ".risk.timestamp_utc"), path, "Halt stop timestamp differs from gateway halt");
                    }
                }
                if (!matched) c.missing(path + ".risk_halt_event");
            }
            if ("HALT".equals(kind) && acks.containsKey("HALT_AUTHORITY")) after(c,
                    time(c, acks.get("HALT_AUTHORITY"), "timestamp_utc", "operator.acknowledgements.HALT_AUTHORITY.timestamp_utc"),
                    acknowledged, "operator.acknowledgements.HALT_AUTHORITY", "Halt authority acknowledgement predates the stop acknowledgement");
        }
        if ("HALTED".equals(x.outcome) && !haltStop) c.missing("operator.stops.HALT");
        return c.result();
    }

    private static void referencedIncident(Context x, Check c, String id, String path) {
        referencedRecord(x, c, "incidents", "incident_id", id, path);
    }

    private static Map<String, Object> referencedRecord(Context x, Check c, String key, String idKey, String id, String path) {
        if (id == null) return null;
        Map<String, Object> operator = object(c, value(x.root, "operator"), "operator");
        Rows records = rows(c, operator, key, key.equals("incidents") ? "incident_count" : "stop_count", "operator", null);
        Map<String, Map<String, Object>> indexed = index(c, records.values, idKey, "operator." + key);
        return counterpart(c, indexed, records, id, path, key + " record");
    }
}
