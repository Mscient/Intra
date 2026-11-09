package com.mscient.intrapaper.admission;

import java.io.*;
import java.nio.file.Path;

/** Standalone offline entrypoint; it does not invoke replay, broker, feed, or order APIs. */
public final class G2AdmissionCli {
    private G2AdmissionCli() { }
    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2 || args.length > 3 || !switch (args[0]) {
            case "run", "verify-artifact" -> args.length == 3;
            case "verify" -> args.length == 2;
            default -> false;
        }) {
            err.println("Usage: G2AdmissionCli run <input.json> <new-artifact-directory> | verify <input.json> | verify-artifact <input.json> <artifact-directory>");
            return 64;
        }
        try {
            Path input = Path.of(args[1]);
            if (args[0].equals("verify-artifact")) {
                G2AdmissionArtifacts.verify(input, Path.of(args[2]));
                out.println("VERIFIED artifact bytes and deterministic recomputation; decision status is in report.json; permission_authenticated=false g2_closed=false");
                return 0;
            }
            G2Admission.Result result = G2Admission.run(input);
            if (args[0].equals("run")) G2AdmissionArtifacts.publish(input, Path.of(args[2]), result);
            out.println(result.status() + " report_id=" + result.reportId()
                    + " scope=CONFIGURED_CHECKS_ONLY release_authorized=false g2_closed=false");
            return switch (result.status()) { case "PASS" -> 0; case "FAIL" -> 2; default -> 3; };
        } catch (IOException | RuntimeException rejected) {
            err.println("REJECTED_INPUT_OR_ARTIFACT: " + rejected.getMessage());
            return 65;
        }
    }
}
