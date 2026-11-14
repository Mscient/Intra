package com.mscient.intrapaper.permission;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;

/** Standalone, read-only checker. Output is deterministic JSON; no approval is created. */
public final class DataPermissionCli {
    private DataPermissionCli() { }
    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }
    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 6 || !args[0].equals("check")) {
            err.println("Usage: DataPermissionCli check <manifest.json> <pinned-manifest-sha256> <permission.json> <as-of-UTC> <RESEARCH|SYNTHETIC_TEST>");
            return 64;
        }
        try {
            var result = DataPermissionChecker.check(Path.of(args[1]), args[2], Path.of(args[3]),
                    Instant.parse(args[4]), DataPermissionChecker.Mode.valueOf(args[5]));
            out.writeBytes(result.bytes());
            if (out.checkError()) return 74;
            return switch (result.status()) {
                case PASS -> 0;
                case SYNTHETIC_PASS -> 10; // Never a successful production admission exit.
                case DENIED -> 3;
            };
        } catch (RuntimeException invalid) {
            err.println("Invalid permission-check arguments; admission denied");
            return 64;
        }
    }
}
