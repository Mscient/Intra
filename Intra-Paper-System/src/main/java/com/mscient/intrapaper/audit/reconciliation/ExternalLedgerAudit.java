package com.mscient.intrapaper.audit.reconciliation;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static com.mscient.intrapaper.audit.reconciliation.LedgerSupport.*;

/** Standalone, offline comparison of verified replay bytes with caller-normalized external evidence. */
public final class ExternalLedgerAudit {
    public enum ReplayKind { SINGLE, PORTFOLIO }
    public enum Outcome { PASS, FAIL, INCOMPLETE }
    public record WrittenAudit(String auditId, Path path, Outcome status) { }
    record Prepared(Map<String, byte[]> files, Outcome status) { }
    private ExternalLedgerAudit() { }

    public static WrittenAudit audit(ReplayKind kind, Path run, Path input, Path settings,
                                     Path externalManifest, Path outputRoot) throws IOException {
        outsideReplay(run, outputRoot);
        return LedgerAuditArtifacts.write(outputRoot, prepare(kind, run, input, settings, externalManifest));
    }

    /** Verifies report integrity, then reproduces every report byte using the original inputs/build. */
    public static WrittenAudit verify(Path audit, ReplayKind kind, Path run, Path input, Path settings,
                                      Path externalManifest) throws IOException {
        WrittenAudit saved = LedgerAuditArtifacts.verify(audit);
        Prepared prepared = prepare(kind, run, input, settings, externalManifest);
        if (!Arrays.equals(LedgerAuditArtifacts.manifest(prepared.files()), readFile(audit.resolve(LedgerAuditArtifacts.MANIFEST))))
            throw new IOException("Audit manifest differs from deterministic reconstruction");
        for (var file : prepared.files().entrySet())
            if (!Arrays.equals(file.getValue(), readFile(audit.resolve(file.getKey()))))
                throw new IOException("Audit report differs from deterministic reconstruction: " + file.getKey());
        return saved;
    }

    static Prepared prepare(ReplayKind kind, Path run, Path input, Path settings, Path externalManifest) throws IOException {
        Objects.requireNonNull(kind, "replay kind");
        Snapshots snapshots = new Snapshots();
        snapshots.hashOnly("audit/build-artifact", VerifiedReplay.buildHash());
        List<Finding> findings = new ArrayList<>();
        LedgerInputs external = LedgerInputs.load(externalManifest, snapshots, findings);
        VerifiedReplay replay = null;
        try { replay = VerifiedReplay.load(kind, run, input, settings, snapshots, external.itemized()); }
        catch (IOException | RuntimeException failure) {
            finding(findings, Kind.INPUT, Severity.FAIL, "REPLAY_VERIFICATION_FAILED", null, "", "replay", "", "",
                    "Saved replay integrity or deterministic reconstruction failed; no unverified financial baseline was consumed");
        }
        if (replay != null) {
            if (!replay.runId.equals(external.metadata.get("replay_run_id")))
                finding(findings, Kind.INPUT, Severity.FAIL, "REPLAY_ID_MISMATCH", null, "", "replay_run_id", replay.runId,
                        external.metadata.getOrDefault("replay_run_id", ""), "Caller bundle must name this exact verified replay");
            if (!kind.name().equals(external.metadata.get("replay_kind")))
                finding(findings, Kind.INPUT, Severity.FAIL, "REPLAY_KIND_MISMATCH", null, "", "replay_kind", kind.name(),
                        external.metadata.getOrDefault("replay_kind", ""), "Caller replay type differs from the selected adapter");
            // Revalidate indirect market-rule, cost, quote, settlement and dataset inputs too.
            VerifiedReplay.reverify(kind, run, input, settings, replay.runId);
        }
        snapshots.unchanged();
        LedgerReconciler.Result result = LedgerReconciler.reconcile(replay, external, snapshots, findings);
        return new Prepared(result.files(), Outcome.valueOf(result.status().name()));
    }

    private static void outsideReplay(Path run, Path output) throws IOException {
        Path target = output.toAbsolutePath().normalize(), ancestor = target;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
        if (ancestor == null) throw new IOException("Cannot resolve audit output root");
        Path resolved = ancestor.toRealPath().resolve(ancestor.relativize(target)).normalize();
        Path replay = run.toAbsolutePath().normalize();
        if (Files.exists(replay, LinkOption.NOFOLLOW_LINKS)) replay = replay.toRealPath();
        if (resolved.startsWith(replay)) throw new IOException("Audit output must be outside the saved replay directory");
    }
}
