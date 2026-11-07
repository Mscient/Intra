package com.mscient.intrapaper.permission.coverage;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;

/** Dedicated CLI. check exit codes: match 0, out of scope 1, incomplete 2; usage/I/O/verify failure 3. */
public final class PermissionCoverageCli {
    private PermissionCoverageCli() { }
    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 5 || !(args[0].equals("check") || args[0].equals("verify"))) {
            err.println("Usage: check <dataset.json> <intent.json> <register.json> <output-dir>");
            err.println("   or: verify <dataset.json> <intent.json> <register.json> <report-hash.json>");
            return 3;
        }
        try {
            Path d = Path.of(args[1]), i = Path.of(args[2]), r = Path.of(args[3]), output = Path.of(args[4]);
            Map<String, Object> report;
            if (args[0].equals("verify")) {
                report = PermissionCoverageArtifacts.verify(output, d, i, r);
                out.println("VERIFIED status=" + report.get("status") + " report_sha256=" + report.get("report_sha256")
                        + " permission_approved=false production_eligible=false evidence_authenticated=false");
                return 0;
            }
            report = PermissionCoverage.check(d, i, r);
            PermissionCoverageArtifacts.publish(output, report);
            out.println("status=" + report.get("status") + " report=" + report.get("report_sha256") + ".json permission_approved=false production_eligible=false");
            return switch ((String) report.get("status")) { case "SCOPE_MATCH" -> 0; case "OUT_OF_SCOPE" -> 1; default -> 2; };
        } catch (Exception invalid) {
            // Never echo caller paths, evidence contents, parser values, or exception messages.
            err.println("CHECK_OR_VERIFICATION_FAILED permission_approved=false production_eligible=false");
            return 3;
        }
    }
}
