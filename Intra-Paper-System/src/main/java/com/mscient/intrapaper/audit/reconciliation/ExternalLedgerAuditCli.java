package com.mscient.intrapaper.audit.reconciliation;

import java.nio.file.Path;

/** Dedicated Java 21 entrypoint. No broker, network, account or order-submission capability. */
public final class ExternalLedgerAuditCli {
    private ExternalLedgerAuditCli() { }
    public static void main(String[] args) { System.exit(run(args)); }
    public static int run(String[] args) {
        try {
            ExternalLedgerAudit.WrittenAudit result;
            if (args.length == 7 && args[0].equals("audit")) {
                result = ExternalLedgerAudit.audit(ExternalLedgerAudit.ReplayKind.valueOf(args[1]), path(args[2]), path(args[3]), path(args[4]), path(args[5]), path(args[6]));
            } else if (args.length == 7 && args[0].equals("verify")) {
                result = ExternalLedgerAudit.verify(path(args[1]), ExternalLedgerAudit.ReplayKind.valueOf(args[2]), path(args[3]), path(args[4]), path(args[5]), path(args[6]));
            } else if (args.length == 2 && args[0].equals("verify-artifact")) {
                result = LedgerAuditArtifacts.verify(path(args[1]));
                System.out.println("Audit artifact integrity verified: " + result.path() + "; recorded status=" + result.status() + "; replay reconstruction and source authenticity are separate checks.");
                return 0;
            } else {
                System.err.println("Usage: ExternalLedgerAuditCli audit <SINGLE|PORTFOLIO> <replay-folder> <bars-or-inputs.csv> <replay.properties> <external-manifest.properties> <output-root>\n"
                        + "       ExternalLedgerAuditCli verify <audit-folder> <SINGLE|PORTFOLIO> <replay-folder> <bars-or-inputs.csv> <replay.properties> <external-manifest.properties>\n"
                        + "       ExternalLedgerAuditCli verify-artifact <audit-folder>");
                return 1;
            }
            System.out.println("Offline external-ledger reconciliation " + result.status() + ": " + result.path());
            return switch (result.status()) { case PASS -> 0; case FAIL -> 2; case INCOMPLETE -> 3; };
        } catch (Exception failure) {
            System.err.println("External-ledger audit rejected: " + failure.getMessage()); return 1;
        }
    }
    private static Path path(String value) { return Path.of(value); }
}
