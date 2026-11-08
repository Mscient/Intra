package com.mscient.intrapaper.evaluation.hypothesis;

import com.mscient.intrapaper.evaluation.ResearchTrialLedger;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;

/** Separate CLI; existing replay, registration, holdout and scoring behavior is unchanged. */
public final class HypothesisCardCli {
    private HypothesisCardCli() { }
    public static void main(String[] args) {
        try {
            HypothesisCard card;
            if (args.length == 2 && args[0].equals("schema")) {
                Files.writeString(Path.of(args[1]), HypothesisCard.schemaJson(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                System.out.println("SCHEMA_WRITTEN"); return;
            } else if (args.length == 2 && args[0].equals("inspect")) {
                card = HypothesisCard.load(Path.of(args[1]));
            } else if (args.length == 3 && args[0].equals("archive")) {
                Path directory = HypothesisRegistry.archive(Path.of(args[1]), Path.of(args[2]));
                card = HypothesisCard.load(directory.resolve("card.json"));
            } else if (args.length == 4 && args[0].equals("verify")) {
                card = HypothesisRegistry.verify(Path.of(args[1]), args[2], Integer.parseInt(args[3]));
            } else if (args.length == 6 && args[0].equals("check-trial")) {
                card = HypothesisRegistry.verify(Path.of(args[1]), args[2], Integer.parseInt(args[3]));
                var matches = ResearchTrialLedger.entries(Path.of(args[4])).stream().filter(e -> e.trial().trialId().equals(args[5])).toList();
                if (matches.size() != 1) throw new IllegalArgumentException();
                card.requireTrialBinding(matches.getFirst().trial());
            } else throw new IllegalArgumentException();
            System.out.println("CARD_ID=" + card.id() + " VERSION=" + card.version() + " SHA256=" + card.sha256() + " STATUS=" + card.status());
            System.out.println("approval_authenticated=false permission_approved=false trial_eligible=false g1_closed=false market_evidence=false independent_preregistration=UNVERIFIED");
        } catch (Exception invalid) {
            // Avoid disclosing file paths, private evidence or supplied prose in CLI errors.
            System.err.println("HYPOTHESIS_CARD_INVALID_OR_IO_FAILURE"); System.exit(3);
        }
    }
}
