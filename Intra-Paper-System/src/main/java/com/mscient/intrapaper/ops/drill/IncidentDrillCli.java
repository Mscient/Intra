package com.mscient.intrapaper.ops.drill;

import java.nio.file.Path;

/** Standalone entry point; deliberately not registered with any broker/account CLI. */
public final class IncidentDrillCli {
    private IncidentDrillCli() { }
    public static void main(String[] args) {
        int exit;
        try {
            if (args.length != 3) throw new IllegalArgumentException("Usage: run <scenario.drill> <output-root> | verify <bundle> <expected-manifest-sha256>");
            IncidentDrillOrchestrator.Status status;
            if (args[0].equals("run")) {
                var result = IncidentDrillOrchestrator.run(DrillScenario.load(Path.of(args[1])));
                Path output = DrillEvidence.publish(result, Path.of(args[2])); status = result.status();
                System.out.println("bundle=" + output.toAbsolutePath());
                System.out.println("manifest_sha256=" + output.getFileName());
            } else if (args[0].equals("verify")) status = DrillEvidence.verify(Path.of(args[1]), args[2]);
            else throw new IllegalArgumentException("Unknown command");
            System.out.println("status=" + status);
            System.out.println("SYNTHETIC_OFFLINE_DRILL_ONLY; g4_operating_session=false; proves_live_recovery=false");
            exit = switch (status) { case PASS -> 0; case FAIL -> 2; case INCOMPLETE -> 3; };
        } catch (Exception failure) {
            System.err.println("Drill rejected: " + failure.getClass().getSimpleName() + ": " + failure.getMessage()); exit = 1;
        }
        System.exit(exit);
    }
}
