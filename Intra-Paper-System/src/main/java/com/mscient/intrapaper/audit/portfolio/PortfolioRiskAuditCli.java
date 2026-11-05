package com.mscient.intrapaper.audit.portfolio;

import java.nio.file.Path;

/** Dedicated entrypoint: intentionally separate from IntraPaperCli and replay behavior. */
public final class PortfolioRiskAuditCli {
    private PortfolioRiskAuditCli() { }
    public static void main(String[] args) { System.exit(run(args)); }
    public static int run(String[] args) {
        try {
            PortfolioRiskAudit.WrittenAudit result;
            if (args.length == 6 && args[0].equals("audit")) {
                result = PortfolioRiskAudit.audit(path(args[1]), path(args[2]), path(args[3]), path(args[4]), path(args[5]));
            } else if (args.length == 6 && args[0].equals("verify")) {
                result = PortfolioRiskAudit.verify(path(args[1]), path(args[2]), path(args[3]), path(args[4]), path(args[5]));
            } else if (args.length == 2 && args[0].equals("verify-artifact")) {
                result = PortfolioRiskAuditArtifacts.verify(path(args[1]));
                System.out.println("Artifact integrity verified; semantic verification requires original replay/risk inputs.");
            } else {
                System.err.println("Usage: PortfolioRiskAuditCli audit <run> <portfolio-inputs.csv> <portfolio.properties> <risk-folder> <output-root>\n"
                        + "       PortfolioRiskAuditCli verify <audit-folder> <run> <portfolio-inputs.csv> <portfolio.properties> <risk-folder>\n"
                        + "       PortfolioRiskAuditCli verify-artifact <audit-folder>");
                return 1;
            }
            System.out.println("Offline audit: " + result.status() + " | caller-supplied margin proxies | " + result.path());
            return switch (result.status()) { case "PASS" -> 0; case "BREACH" -> 2; default -> 3; };
        } catch (Exception failure) {
            System.err.println("Portfolio risk audit rejected: " + failure.getMessage());
            return 1;
        }
    }
    private static Path path(String value) { return Path.of(value); }
}
