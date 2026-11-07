package com.mscient.intrapaper.ops.evidence;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Canonical reports contain no checker clock, absolute paths, random IDs, or file metadata. */
public record DailyOpsEvidenceReport(String sessionId, String runId, String outcome,
        Map<String, String> inputHashes, List<Result> results) {
    public static final String EVIDENCE_BASIS = "caller-supplied diagnostic evidence";

    public enum Status {
        PASS(0), INCOMPLETE(2), FAIL(1);
        private final int exitCode;
        Status(int exitCode) { this.exitCode = exitCode; }
        public int exitCode() { return exitCode; }
        static Status combine(Status left, Status right) {
            if (left == FAIL || right == FAIL) return FAIL;
            return left == INCOMPLETE || right == INCOMPLETE ? INCOMPLETE : PASS;
        }
    }

    public record Finding(Status status, String field, String detail) { }
    public record Result(String check, Status status, List<Finding> findings) {
        public Result { findings = List.copyOf(findings); }
    }

    static final class Check {
        final String name;
        final List<Finding> findings = new ArrayList<>();
        Check(String name) { this.name = name; }
        void missing(String field) { incomplete(field, "UNKNOWN: required evidence is missing"); }
        void incomplete(String field, String detail) { findings.add(new Finding(Status.INCOMPLETE, field, detail)); }
        void fail(String field, String detail) { findings.add(new Finding(Status.FAIL, field, detail)); }
        void require(boolean condition, String field, String detail) { if (!condition) fail(field, detail); }
        void include(Check other) { findings.addAll(other.findings); }
        Result result() {
            Status status = Status.PASS;
            for (Finding finding : findings) status = Status.combine(status, finding.status());
            return new Result(name, status, findings);
        }
    }

    public DailyOpsEvidenceReport {
        // Unknown hashes are deliberately null; Map.copyOf would discard this contract by rejecting them.
        inputHashes = Collections.unmodifiableMap(new TreeMap<>(inputHashes));
        results = List.copyOf(results);
    }

    public Status status() {
        Status status = Status.PASS;
        for (Result result : results) status = Status.combine(status, result.status());
        return status;
    }

    public byte[] bytes() {
        Map<String, Object> report = new TreeMap<>();
        report.put("schema_version", 1);
        report.put("classification", "OFFLINE_DAILY_OPS_EVIDENCE_DIAGNOSTIC");
        report.put("evidence_basis", EVIDENCE_BASIS);
        report.put("g4_closed", false);
        report.put("operational_readiness_eligible", false);
        report.put("session_id", sessionId);
        report.put("run_id", runId);
        report.put("outcome", outcome);
        report.put("status", status().name());
        report.put("input_hashes", inputHashes);
        List<Object> checks = new ArrayList<>();
        for (Result result : results) {
            Map<String, Object> item = new TreeMap<>();
            item.put("check", result.check());
            item.put("status", result.status().name());
            item.put("evidence_basis", EVIDENCE_BASIS);
            item.put("input_hashes", inputHashes);
            item.put("findings", result.findings().stream().map(finding -> Map.of(
                    "status", finding.status().name(), "field", finding.field(), "detail", finding.detail())).toList());
            checks.add(item);
        }
        report.put("results", checks);
        return (json(report) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Also used by synthetic fixtures. Object keys sort; array order and exact decimals are preserved. */
    public static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String string) {
            StringBuilder out = new StringBuilder("\"");
            for (int i = 0; i < string.length(); i++) {
                char ch = string.charAt(i);
                switch (ch) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (ch < 32 || Character.isSurrogate(ch)) {
                            out.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", (int) ch));
                        } else out.append(ch);
                    }
                }
            }
            return out.append('"').toString();
        }
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) return value.toString();
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> ordered = new TreeMap<>();
            map.forEach((key, entry) -> ordered.put((String) key, entry));
            List<String> entries = new ArrayList<>();
            ordered.forEach((key, entry) -> entries.add(json(key) + ":" + json(entry)));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Collection<?> list) return "[" + String.join(",", list.stream().map(DailyOpsEvidenceReport::json).toList()) + "]";
        throw new IllegalArgumentException("Unsupported report value type");
    }
}
