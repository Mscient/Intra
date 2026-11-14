package com.mscient.intrapaper.ops.evidence;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Dedicated offline command; no replay engine, gateway, feed, or broker is started. */
public final class DailyOpsEvidenceCli {
    private DailyOpsEvidenceCli() { }
    public static void main(String[] args) { System.exit(run(args)); }
    public static int run(String[] args) { return run(args, System.out, System.err); }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if ((args.length != 2 && args.length != 3) || !args[0].equals("check")) {
            err.println("Usage: DailyOpsEvidenceCli check <session-evidence.json> [<report.json outside bundle directory>]");
            return 64;
        }
        try {
            Path manifest = Path.of(args[1]);
            DailyOpsEvidenceReport report = DailyOpsEvidenceChecker.check(manifest);
            if (args.length == 2) out.write(report.bytes());
            else {
                write(manifest, Path.of(args[2]), report.bytes());
                out.println(report.status() + ": " + DailyOpsEvidenceReport.EVIDENCE_BASIS
                        + "; session=" + report.sessionId() + "; run=" + report.runId() + "; outcome=" + report.outcome());
            }
            return report.status().exitCode();
        } catch (IOException | IllegalArgumentException failure) {
            err.println("Evidence command could not write/read its report: " + failure.getMessage());
            return 74;
        }
    }

    private static void write(Path manifest, Path requested, byte[] bytes) throws IOException {
        Path bundleRoot = manifest.toAbsolutePath().normalize().getParent();
        Path output = requested.toAbsolutePath().normalize();
        if (output.startsWith(bundleRoot)) throw new IOException("Report must be outside the evidence bundle directory");
        if (Files.isSymbolicLink(output)) throw new IOException("Report path must not be a symbolic link");
        Path parent = output.getParent();
        Files.createDirectories(parent);
        if (Files.exists(bundleRoot) && parent.toRealPath().startsWith(bundleRoot.toRealPath())) {
            throw new IOException("Report parent resolves inside the evidence bundle directory");
        }
        Path temporary = Files.createTempFile(parent, ".daily-ops-report-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
