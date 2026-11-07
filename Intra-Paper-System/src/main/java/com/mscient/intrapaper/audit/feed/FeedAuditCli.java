package com.mscient.intrapaper.audit.feed;

import java.io.*;
import java.nio.file.Path;
import java.util.Map;

/** Separate entrypoint: never invokes the trading CLI, broker, or a feed adapter. */
public final class FeedAuditCli {
    private FeedAuditCli() { }
    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2 || args.length > 3 || !switch (args[0]) {
            case "run", "verify-artifact" -> args.length == 3;
            case "verify" -> args.length == 2;
            default -> false;
        }) {
            err.println("Usage: FeedAuditCli run <evidence.json> <new-artifact-directory> | verify <evidence.json> | verify-artifact <evidence.json> <artifact-directory>");
            return 64;
        }
        try {
            Path evidence = Path.of(args[1]);
            if (args[0].equals("verify-artifact")) {
                FeedAuditArtifacts.verify(evidence, Path.of(args[2])); out.println("VERIFIED artifact hashes, schema, and deterministic re-audit"); return 0;
            }
            Map<String, Object> report = FeedAudit.run(evidence);
            if (args[0].equals("run")) FeedAuditArtifacts.publish(evidence, Path.of(args[2]), report);
            String status = (String) report.get("status"); out.println(status + ": " + FeedAudit.LIMITS);
            return switch (status) { case "PASS" -> 0; case "FAIL" -> 2; default -> 3; };
        } catch (IOException | RuntimeException rejected) {
            err.println("REJECTED_INPUT_OR_ARTIFACT: " + rejected.getMessage()); return 65;
        }
    }
}
