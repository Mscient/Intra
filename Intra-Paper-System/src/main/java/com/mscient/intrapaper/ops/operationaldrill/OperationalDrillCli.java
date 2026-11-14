package com.mscient.intrapaper.ops.operationaldrill;

import java.nio.file.Path;
import static com.mscient.intrapaper.ops.operationaldrill.OperationalScenario.Status;

/** Deliberately separate from the simulator's general command dispatcher. */
public final class OperationalDrillCli {
    private OperationalDrillCli() { }
    public static void main(String[] args) {
        int exit;
        try {
            if (args.length != 3) throw new IllegalArgumentException("Usage: run <scenario.drill> <output-root> | verify <bundle> <trusted-manifest-sha256>");
            Status status;
            if (args[0].equals("run")) {
                var result = OperationalDrill.run(Path.of(args[1]), Path.of(args[2]));
                System.out.println("bundle=" + result.path());
                System.out.println("manifest_sha256=" + result.manifestSha256()); status = result.status();
            } else if (args[0].equals("verify")) status = OperationalDrill.verify(Path.of(args[1]), args[2]);
            else throw new IllegalArgumentException("Unknown mode");
            System.out.println("status=" + status + "\nclassification=SYNTHETIC_ONLY");
            exit = switch (status) { case PASS -> 0; case FAIL -> 2; case INCOMPLETE -> 3; };
        } catch (Exception failure) {
            System.err.println("Operational drill rejected: " + failure.getClass().getSimpleName() + ": " + failure.getMessage()); exit = 1;
        }
        System.exit(exit);
    }
}
