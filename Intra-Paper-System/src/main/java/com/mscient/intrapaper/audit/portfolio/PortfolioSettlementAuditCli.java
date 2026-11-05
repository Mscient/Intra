package com.mscient.intrapaper.audit.portfolio;

import java.nio.file.Path;

/** Dedicated offline entrypoint, separate from IntraPaperCli and portfolio replay output. */
public final class PortfolioSettlementAuditCli {
    private PortfolioSettlementAuditCli() { }
    public static void main(String[] args) { System.exit(run(args)); }
    public static int run(String[] args) {
        try {
            PortfolioSettlementAudit.WrittenAudit result;
            if ((args.length == 6 || args.length == 7) && args[0].equals("audit")) {
                result = PortfolioSettlementAudit.audit(path(args[1]), path(args[2]), path(args[3]), path(args[4]),
                        args.length == 7 ? path(args[6]) : null, path(args[5]));
            } else if ((args.length == 6 || args.length == 7) && args[0].equals("verify")) {
                result = PortfolioSettlementAudit.verify(path(args[1]), path(args[2]), path(args[3]), path(args[4]), path(args[5]),
                        args.length == 7 ? path(args[6]) : null);
            } else if (args.length == 2 && args[0].equals("verify-artifact")) {
                result = PortfolioSettlementArtifacts.verify(path(args[1]));
                System.out.println("Artifact integrity verified; replay reconstruction and source authenticity are separate checks.");
            } else {
                System.err.println("Usage: PortfolioSettlementAuditCli audit <run> <portfolio-inputs.csv> <portfolio.properties> <settlement-events.csv> <output-root> [settlement-costs.csv]\n"
                        + "       PortfolioSettlementAuditCli verify <audit-folder> <run> <portfolio-inputs.csv> <portfolio.properties> <settlement-events.csv> [settlement-costs.csv]\n"
                        + "       PortfolioSettlementAuditCli verify-artifact <audit-folder>");
                return 1;
            }
            System.out.println("Offline settlement diagnostic verified: " + result.path()); return 0;
        } catch (Exception failure) {
            System.err.println("Portfolio settlement audit rejected: " + failure.getMessage()); return 1;
        }
    }
    private static Path path(String value) { return Path.of(value); }
}
