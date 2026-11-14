package com.mscient.intrapaper.permission.preflight;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** Separate offline runner. Exit zero exclusively denotes ALLOWED under the supplied declarations. */
public final class PermissionPreflightCli {
    private PermissionPreflightCli() { }
    public static void main(String[] args) { System.exit(run(args, System.out, System.err)); }
    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 2 && args[0].equals("schema")) {
                out.write(PermissionSchemas.schema(args[1])); return 0;
            }
            boolean check = args.length > 0 && args[0].equals("check");
            boolean verify = args.length > 0 && (args[0].equals("verify") || args[0].equals("verify-dataset"));
            boolean dataset = args.length > 0 && (args[0].equals("check-dataset") || args[0].equals("verify-dataset"));
            if ((!check && !verify && !dataset) || args.length != (dataset ? 7 : 6)) {
                err.println("Usage: PermissionPreflightCli check|verify <manifest.json> <use.json> <permissions.json> <as-of YYYY-MM-DD> <report.json>");
                err.println("       PermissionPreflightCli check-dataset|verify-dataset <manifest.json> <use.json> <permissions.json> <as-of YYYY-MM-DD> <report.json> <dataset-file>");
                err.println("       PermissionPreflightCli schema dataset-manifest|intended-use|permission-evidence");
                return 64;
            }
            if (!args[4].matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("Require explicit ISO assessment date");
            LocalDate asOf = LocalDate.parse(args[4]);
            Path m = Path.of(args[1]), u = Path.of(args[2]), p = Path.of(args[3]), report = Path.of(args[5]);
            // Reject output/input aliases before touching an output, including existing hard links.
            for (Path input : dataset ? List.of(m, u, p, Path.of(args[6])) : List.of(m, u, p)) {
                if (report.toAbsolutePath().normalize().equals(input.toAbsolutePath().normalize())
                        || Files.exists(report) && Files.exists(input) && Files.isSameFile(report, input))
                    throw new IOException("Report must be separate from all inputs");
            }
            PermissionPreflight.Decision decision = dataset
                    ? PermissionPreflight.checkDataset(m, u, p, asOf, Path.of(args[6]))
                    : PermissionPreflight.check(m, u, p, asOf);
            if (verify) {
                if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS) || Files.size(report) > 4 * 1024 * 1024
                        || !Arrays.equals(Files.readAllBytes(report), decision.bytes())) {
                    err.println("Report does not reproduce for these inputs, checker bytes, and assessment date"); return 4;
                }
            } else write(report, decision.bytes());
            out.println(decision.status() + " " + decision.id() + " (declared evidence only; G2 open)");
            return decision.status().exitCode();
        } catch (IOException | IllegalArgumentException | java.time.DateTimeException failure) {
            err.println("Preflight could not complete: " + failure.getMessage()); return 74;
        }
    }
    private static void write(Path target, byte[] bytes) throws IOException {
        Path output = target.toAbsolutePath().normalize();
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) || Files.size(output) != bytes.length
                    || !Arrays.equals(Files.readAllBytes(output), bytes)) throw new IOException("Existing report differs; choose a new output path");
            return;
        }
        Files.createDirectories(output.getParent());
        // CREATE_NEW never replaces an earlier decision or an input aliased through a directory link.
        // Interrupted publication may leave a partial file; it cannot pass verify or a repeated check.
        Files.write(output, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
