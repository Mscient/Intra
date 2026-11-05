package com.mscient.intrapaper.evaluation.portfolio;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static com.mscient.intrapaper.evaluation.portfolio.PortfolioEvaluationJson.object;

/** Dedicated classpath entrypoint; the shared CLI and replay output formats are unchanged. */
public final class PortfolioTrialEvaluationCli {
    private PortfolioTrialEvaluationCli() { }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 2 && args[0].equals("identity")) {
                var run = PortfolioTrialIdentity.inspect(Path.of(args[1]));
                out.print(PortfolioEvaluationJson.write(object("strategy_id", run.strategyId(),
                        "dataset_sha256", run.verification().validatedDatasetsSha256(),
                        "parameter_config_sha256", run.parameterConfigSha256(),
                        "run_id", run.verification().runId(), "run_manifest_sha256", run.manifestSha256(),
                        "performance_claim_eligible", false)));
                return 0;
            }
            if ((args.length != 6 && args.length != 7) || !args[0].equals("evaluate")) {
                err.println("Usage: PortfolioTrialEvaluationCli evaluate <trial-ledger> <trial-catalog.json> <runs-root>"
                        + " <even-block-count-4-to-12> <new-output.json> [outcome-ledger]");
                err.println("       PortfolioTrialEvaluationCli identity <verified-run-folder>");
                return 2;
            }
            var report = PortfolioTrialEvaluationAnalyzer.analyze(Path.of(args[1]), Path.of(args[2]),
                    Path.of(args[3]), Integer.parseInt(args[4]), args.length == 7 ? Path.of(args[6]) : null);
            Path output = Path.of(args[5]).toAbsolutePath().normalize();
            Files.writeString(output, report.toJson(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            out.println("Portfolio trials evaluated: analyzed=" + report.trials().size()
                    + "; registered=" + report.registeredTrialCount()
                    + "; family=" + report.comparisonFamilyTrialCount()
                    + "; holdout_included=false; performance_claim_eligible=false; output=" + output);
            return 0;
        } catch (IOException | IllegalArgumentException invalid) {
            err.println("Portfolio trial evaluation failed: " + invalid.getMessage());
            return 1;
        }
    }
}
