package com.mscient.intrapaper.permission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Offline permission preflight; a hash establishes identity, never legal authority. */
public final class DataPermissionChecker {
    private DataPermissionChecker() { }
    public enum Mode { RESEARCH, SYNTHETIC_TEST }
    public enum Status { PASS, SYNTHETIC_PASS, DENIED }
    public record Result(Status status, Mode mode, Instant asOf, String manifestSha256,
                         String permissionSha256, String datasetId, String permissionId, List<String> reasons) {
        public Result { reasons = List.copyOf(reasons); }
        public boolean researchAdmissionAllowed() { return status == Status.PASS && mode == Mode.RESEARCH; }
        public byte[] bytes() {
            String text = "{\n  \"schema\": \"DATA_PERMISSION_PREFLIGHT_V1\",\n"
                    + "  \"status\": " + q(status.name()) + ",\n  \"mode\": " + q(mode.name())
                    + ",\n  \"as_of_utc\": " + q(asOf.toString())
                    + ",\n  \"research_admission_allowed\": " + researchAdmissionAllowed()
                    + ",\n  \"approval_basis\": \"OWNER_AND_QUALIFIED_REVIEWER_RECORD; AUTHENTICITY_NOT_VERIFIED\""
                    + ",\n  \"dataset_id\": " + q(datasetId) + ",\n  \"permission_record_id\": " + q(permissionId)
                    + ",\n  \"manifest_sha256\": " + q(manifestSha256)
                    + ",\n  \"permission_record_sha256\": " + q(permissionSha256)
                    + ",\n  \"reasons\": [" + String.join(",", reasons.stream().map(DataPermissionChecker::q).toList())
                    + "]\n}\n";
            return text.getBytes(StandardCharsets.UTF_8);
        }
    }
    record Checked(Result result, PermissionDocuments.Manifest manifest, PermissionDocuments.Grant grant) { }

    /** asOf must be explicit for reproducible historical checks; admission uses the current UTC clock. */
    public static Result check(Path manifest, String expectedManifestSha256, Path permission, Instant asOf, Mode mode) {
        return inspect(manifest, expectedManifestSha256, permission, asOf, mode).result();
    }

    static Checked inspect(Path manifestPath, String expectedHash, Path permissionPath, Instant asOf, Mode mode) {
        Objects.requireNonNull(asOf, "asOf"); Objects.requireNonNull(mode, "mode");
        var reasons = new TreeSet<String>();
        PermissionDocuments.Manifest m = null;
        PermissionDocuments.Grant g = null;
        String mh = null, gh = null;
        try {
            var doc = PermissionDocuments.read(manifestPath); mh = doc.sha256();
            m = PermissionDocuments.manifest(doc);
        } catch (IOException | RuntimeException invalid) { reasons.add("MANIFEST_MISSING_OR_INVALID"); }
        try {
            PermissionDocuments.hash(expectedHash);
            if (!expectedHash.equals(mh)) reasons.add("MANIFEST_HASH_MISMATCH");
        } catch (RuntimeException invalid) { reasons.add("MANIFEST_PIN_MISSING_OR_INVALID"); }
        try {
            var doc = PermissionDocuments.read(permissionPath); gh = doc.sha256();
            g = PermissionDocuments.grant(doc);
        } catch (IOException | RuntimeException invalid) { reasons.add("PERMISSION_MISSING_OR_INVALID"); }
        if (m != null && g != null) {
            if (!m.permissionId().equals(g.id())) reasons.add("PERMISSION_ID_MISMATCH");
            if (!m.permissionHash().equals(gh)) reasons.add("PERMISSION_HASH_MISMATCH");
            if (!m.source().equals(g.source())) reasons.add("SOURCE_OUT_OF_SCOPE");
            if (!m.product().equals(g.product())) reasons.add("PRODUCT_OUT_OF_SCOPE");
            if (!g.instruments().containsAll(m.instruments())) reasons.add("INSTRUMENT_OUT_OF_SCOPE");
            if (m.dataFrom().isBefore(g.dataFrom()) || m.dataThrough().isAfter(g.dataThrough())) reasons.add("DATA_DATES_OUT_OF_SCOPE");
            if (g.decision() != PermissionDocuments.Decision.APPROVED) reasons.add("REVIEW_NOT_APPROVED");
            if (g.owner().equalsIgnoreCase(g.reviewer())) reasons.add("REVIEWER_NOT_INDEPENDENT");
            if (g.reviewedAt().isAfter(asOf) || g.reviewedAt().isAfter(m.useFrom())) reasons.add("REVIEW_NOT_IN_DATE");
            if (asOf.isBefore(g.effectiveFrom())) reasons.add("GRANT_NOT_YET_EFFECTIVE");
            if (!asOf.isBefore(g.effectiveUntil())) reasons.add("GRANT_EXPIRED");
            if (m.useFrom().isBefore(g.effectiveFrom()) || m.useUntil().isAfter(g.effectiveUntil())) reasons.add("USE_PERIOD_OUT_OF_SCOPE");
            if (asOf.isBefore(m.useFrom()) || !asOf.isBefore(m.useUntil())) reasons.add("OUTSIDE_INTENDED_USE_PERIOD");
            if (g.simulation() != PermissionDocuments.Right.ALLOWED) reasons.add("SIMULATION_NOT_EXPLICITLY_ALLOWED");
            if (g.retention() != PermissionDocuments.Right.ALLOWED) reasons.add("RETENTION_NOT_EXPLICITLY_ALLOWED");
            if (m.retainUntil().isAfter(g.retainUntil()) || !asOf.isBefore(m.retainUntil())) reasons.add("RETENTION_OUT_OF_SCOPE");
            if (!g.storage().contains(m.storage())) reasons.add("STORAGE_OUT_OF_SCOPE");
            if (g.derived() != PermissionDocuments.Right.ALLOWED || !g.outputUses().containsAll(m.outputUses())) reasons.add("DERIVED_OUTPUT_OUT_OF_SCOPE");
            if (m.kind() != g.kind()) reasons.add("EVIDENCE_KIND_MISMATCH");
            if (mode == Mode.RESEARCH && (g.kind() != PermissionDocuments.Kind.OWNER_REVIEWED
                    || m.kind() != PermissionDocuments.Kind.OWNER_REVIEWED)) reasons.add("SYNTHETIC_EVIDENCE_CANNOT_AUTHORIZE_RESEARCH");
            if (mode == Mode.SYNTHETIC_TEST && (g.kind() != PermissionDocuments.Kind.SYNTHETIC_TEST
                    || m.kind() != PermissionDocuments.Kind.SYNTHETIC_TEST)) reasons.add("TEST_MODE_REQUIRES_SYNTHETIC_EVIDENCE");
        }
        Status status = !reasons.isEmpty() ? Status.DENIED : mode == Mode.RESEARCH ? Status.PASS : Status.SYNTHETIC_PASS;
        var result = new Result(status, mode, asOf, mh, gh, m == null ? null : m.id(),
                m == null ? null : m.permissionId(), List.copyOf(reasons));
        return new Checked(result, m, g);
    }
    private static String q(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
