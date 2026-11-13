package com.mscient.intrapaper.ops.governance;

import static com.mscient.intrapaper.ops.governance.G7Json.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/** Immutable generations. Corrections supersede a logical event; prior events and artifacts remain mandatory. */
final class G7Ledger {
    static final String GENESIS = "0".repeat(64);
    final Map<String, Object> document;
    final List<Map<String, Object>> events = new ArrayList<>();
    final Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
    final Set<String> superseded = new HashSet<>();
    final Map<String, String> logicalKinds = new HashMap<>();
    int decisionStart;
    String head = GENESIS;
    G7Ledger(byte[] content, String reviewId, String scope, String manifestHash, Instant asOf) throws IOException {
        Object parsed = parse(content); G7Schema.validate("history", parsed); document = obj(parsed);
        if (!Arrays.equals(content, bytes(document))) throw new IOException("History must use canonical JSON with one LF");
        if (!reviewId.equals(document.get("review_id"))) throw new IOException("History review identity mismatch");
        Instant previousTime = null; boolean sealed = false, revoked = false;
        Set<String> decisionIds = new HashSet<>(), revokedTargets = new HashSet<>();
        for (Object value : list(document.get("events"))) {
            Map<String, Object> e = obj(value); String id = str(e, "event_id"), kind = str(e, "kind"), prior = str(e, "prior_event_id");
            if (integer(e.get("sequence")) != events.size() || !head.equals(e.get("previous_sha256"))) throw new IOException("Event chain link/order mismatch");
            if (byId.containsKey(id)) throw new IOException("Duplicate event identity");
            Instant recorded = time(e.get("recorded_at")), occurred = time(e.get("occurred_at"));
            if (recorded.isAfter(asOf) || occurred.isAfter(recorded) || previousTime != null && recorded.isBefore(previousTime)) throw new IOException("Invalid event chronology");
            previousTime = recorded;
            if (events.isEmpty()) {
                if (!kind.equals("REGISTRATION") || !manifestHash.equals(e.get("subject_sha256")) || !scope.equals(e.get("scope"))
                        || e.get("period_id") != null) throw new IOException("First event must register exact manifest bytes and scope");
            } else if (kind.equals("REGISTRATION")) throw new IOException("Repeated registration");
            boolean decision = kind.equals("OWNER_DECISION") || kind.equals("REVIEWER_DECISION");
            if ((decision || kind.equals("REVOCATION")) && !sealed) { decisionStart = events.size(); sealed = true; }
            if (sealed && !decision && !kind.equals("REVOCATION")) throw new IOException("Evidence changed after review; new review identity required");
            if (revoked && decision) throw new IOException("Revoked review cannot receive another decision");
            if (decision != (e.get("decision") != null)) throw new IOException("Decision payload/kind mismatch");
            if (decision) {
                Map<String, Object> d = obj(e.get("decision"));
                if (!decisionIds.add(str(d, "decision_id")) || !hash(bytes(d)).equals(e.get("subject_sha256"))) throw new IOException("Duplicate or mismatched decision");
                if (!e.get("occurred_at").equals(d.get("at"))) throw new IOException("Decision timestamp mismatch");
                if (!scope.equals(e.get("scope")) || e.get("period_id") != null) throw new IOException("Decision event scope mismatch");
                signature(d);
            }
            boolean needsPrior = Set.of("CORRECTION", "SUPERSESSION", "STOP", "RECOVERY", "REVOCATION").contains(kind);
            if (needsPrior && (prior == null || !byId.containsKey(prior))) throw new IOException("Event needs an existing prior event");
            if (!needsPrior && prior != null) throw new IOException("Unexpected prior-event reference");
            String logical = kind;
            if (kind.equals("CORRECTION") || kind.equals("SUPERSESSION")) {
                logical = logicalKinds.get(prior);
                if (!Set.of("EVIDENCE", "PERIOD_EVIDENCE", "CHANGE", "INCIDENT").contains(logical)
                        || !superseded.add(prior) || !e.get("scope").equals(byId.get(prior).get("scope"))
                        || !Objects.equals(e.get("period_id"), byId.get(prior).get("period_id"))
                        || list(e.get("evidence")).isEmpty() || e.get("response") == null
                        || !hash(bytes(byId.get(prior))).equals(e.get("supersedes_sha256"))
                        || occurred.isBefore(time(byId.get(prior).get("occurred_at")))) throw new IOException("Invalid correction/supersession");
                if (e.get("subject_sha256").equals(byId.get(prior).get("subject_sha256"))) throw new IOException("Correction must reference different immutable bytes");
            } else if (e.get("supersedes_sha256") != null) throw new IOException("Unexpected supersession hash");
            if (kind.equals("STOP") || kind.equals("RECOVERY")) {
                if (!logicalKinds.get(prior).equals("INCIDENT") || !e.get("scope").equals(byId.get(prior).get("scope"))
                        || !Objects.equals(e.get("period_id"), byId.get(prior).get("period_id"))
                        || occurred.isBefore(time(byId.get(prior).get("occurred_at")))) throw new IOException("Stop/recovery must reference its incident, period and scope");
                if (kind.equals("RECOVERY") && events.stream().noneMatch(x -> x.get("kind").equals("STOP") && prior.equals(x.get("prior_event_id"))
                        && !time(x.get("occurred_at")).isAfter(occurred))) throw new IOException("Recovery needs preceding stop evidence");
                if (kind.equals("STOP") && events.stream().anyMatch(x -> x.get("kind").equals("STOP") && prior.equals(x.get("prior_event_id"))))
                    throw new IOException("Repeated stop record; preserve follow-up in a new review");
            }
            if (kind.equals("REVOCATION")) {
                if (!Set.of("REGISTRATION", "OWNER_DECISION", "REVIEWER_DECISION").contains(str(byId.get(prior), "kind"))
                        || !revokedTargets.add(prior) || !hash(bytes(byId.get(prior))).equals(e.get("subject_sha256"))
                        || !scope.equals(e.get("scope")) || e.get("period_id") != null) throw new IOException("Invalid revocation binding/replay");
                revoked = true;
            }
            Object record = switch (logical) {
                case "EVIDENCE" -> e.get("artifact_record"); case "PERIOD_EVIDENCE" -> e.get("period_record");
                case "CHANGE" -> e.get("change_record"); default -> null;
            };
            for (String field : List.of("artifact_record", "period_record", "change_record")) {
                if (e.get(field) != null && e.get(field) != record) throw new IOException("Unexpected event record payload");
            }
            if (Set.of("EVIDENCE", "PERIOD_EVIDENCE", "CHANGE").contains(logical)) {
                if (record == null || !hash(bytes(record)).equals(e.get("subject_sha256"))) throw new IOException("Event record/hash mismatch");
                Map<String, Object> bound = obj(record);
                if (logical.equals("EVIDENCE") && (list(e.get("evidence")).size() != 1 || !bound.get("id").equals(list(e.get("evidence")).getFirst())))
                    throw new IOException("Evidence event must bind its sole artifact record");
                if (logical.equals("EVIDENCE") && (!scope.equals(e.get("scope")) || !Objects.equals(bound.get("period_id"), e.get("period_id"))))
                    throw new IOException("Evidence event scope/period must match its artifact record");
                if (logical.equals("PERIOD_EVIDENCE") && (!bound.get("period_id").equals(e.get("period_id")) || !scope.equals(e.get("scope"))))
                    throw new IOException("Period record scope mismatch");
                if (logical.equals("CHANGE") && (!bound.get("history_event_id").equals(id) || !bound.get("scope").equals(e.get("scope"))
                        || !Objects.equals(bound.get("period_id"), e.get("period_id")) || !bound.get("at").equals(e.get("occurred_at"))))
                    throw new IOException("Change event record mismatch");
            }
            if (kind.equals("PERIOD") && (e.get("period_id") == null || !scope.equals(e.get("scope")))) throw new IOException("Period registration needs exact scope");
            if (kind.equals("BUNDLE") && (!scope.equals(e.get("scope")) || e.get("period_id") != null))
                throw new IOException("Bundle binding/replay mismatch");
            for (String key : List.of("evidence", "resolution_evidence", "required_rereview")) {
                List<Object> a = list(e.get(key)); if (new HashSet<>(a).size() != a.size()) throw new IOException("Duplicate event reference");
            }
            signature(e); logicalKinds.put(id, logical); byId.put(id, e); events.add(e); head = hash(bytes(e));
        }
        if (events.isEmpty()) throw new IOException("Missing registration event");
        if (!sealed) decisionStart = events.size();
    }
    byte[] prefix(int count) { return bytes(map("schema", "g7-history/v1", "review_id", document.get("review_id"), "events", events.subList(0, count))); }
    String head(int count) { return count == 0 ? GENESIS : hash(bytes(events.get(count - 1))); }
    Map<String, Object> append(Map<String, Object> event) throws IOException {
        // Callers provide an unsigned draft with the next sequence/link. Never rewrite signed data.
        G7Schema.validate("event", event);
        List<Object> extended = new ArrayList<>(events); extended.add(event);
        return map("schema", "g7-history/v1", "review_id", document.get("review_id"), "events", extended);
    }
}
