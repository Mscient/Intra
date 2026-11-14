package com.mscient.intrapaper.evaluation.portfolio;

import com.mscient.intrapaper.data.StrictJsonParser;
import com.mscient.intrapaper.evaluation.EvaluationSplitPlan;
import com.mscient.intrapaper.evaluation.ResearchTrialLedger;
import com.mscient.intrapaper.provenance.RunConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mscient.intrapaper.evaluation.portfolio.PortfolioEvaluationJson.parsedObject;
import static com.mscient.intrapaper.evaluation.portfolio.PortfolioEvaluationJson.string;

/** Public inputs only: capturing a freeze never opens replay artifacts or holdout financial files. */
public record HoldoutReleaseInputs(Path registrationLedger, Path catalog, Path codeArtifact,
        Path splitPlan, Path preregisteredProtocol) {
    public record Binding(String catalogSha256, String codeSha256, String datasetSha256,
            String splitSha256, String protocolSha256, String registrationLedgerSha256) {
        public Binding {
            for (String hash : List.of(catalogSha256, codeSha256, datasetSha256, splitSha256,
                    protocolSha256, registrationLedgerSha256)) requireHash(hash);
        }
        String encode() {
            return String.join("\t", catalogSha256, codeSha256, datasetSha256, splitSha256,
                    protocolSha256, registrationLedgerSha256);
        }
        static Binding decode(String[] fields, int offset) {
            return new Binding(fields[offset], fields[offset + 1], fields[offset + 2],
                    fields[offset + 3], fields[offset + 4], fields[offset + 5]);
        }
        public String sha256() { return digest("INTRA_HOLDOUT_BINDING_V1\t" + encode()); }
        public String familyId() { return digest("INTRA_HOLDOUT_FAMILY_V1\t" + datasetSha256 + "\t" + splitSha256); }
    }

    public Binding capture() throws IOException {
        String ledgerHash = PortfolioTrialIdentity.fileSha256(registrationLedger);
        var registrations = ResearchTrialLedger.entries(registrationLedger);
        var frozenCatalog = PortfolioTrialCatalog.load(catalog);
        String splitHash = PortfolioTrialIdentity.fileSha256(splitPlan);
        var split = EvaluationSplitPlan.loadForEvaluation(splitPlan);
        equal(splitHash, split.splitFileSha256());
        String datasetHash = frozenCatalog.trials().getFirst().trial().datasetSha256();
        Set<String> family = new HashSet<>();
        for (var registration : registrations) {
            if (datasetHash.equals(registration.trial().datasetSha256())
                    && splitHash.equals(registration.trial().splitPlanSha256())) family.add(registration.trial().trialId());
        }
        Set<String> candidates = new HashSet<>();
        for (var row : frozenCatalog.trials()) {
            equal(datasetHash, row.trial().datasetSha256());
            equal(splitHash, row.trial().splitPlanSha256());
            equal(splitHash, PortfolioTrialIdentity.fileSha256(row.splitPlanPath()));
            var registration = registrations.stream().filter(r -> r.trial().equals(row.trial())).findFirst()
                    .orElseThrow(() -> new IOException("Unregistered freeze candidate"));
            equal(registration.recordHashSha256(), row.registrationRecordSha256());
            candidates.add(row.trial().trialId());
        }
        if (!candidates.equals(family)) throw new IOException("Freeze requires the complete registered family");
        byte[] protocol = PortfolioTrialIdentity.readRegular(preregisteredProtocol);
        if (protocol.length == 0 || protocol.length > 1024 * 1024) throw new IOException("Unsupported release protocol size");
        var root = parsedObject(StrictJsonParser.parse(PortfolioTrialIdentity.utf8(protocol)), "release protocol");
        if (!root.keySet().equals(Set.of("schema", "selected_trial_ids", "evaluation_spec", "failure_policy"))
                || !"INTRA_HOLDOUT_PROTOCOL_V1".equals(string(root, "schema"))
                || !"CONSUME_ON_START_NO_RETRY".equals(string(root, "failure_policy")))
            throw new IOException("Invalid release protocol contract");
        string(root, "evaluation_spec");
        if (!(root.get("selected_trial_ids") instanceof List<?> selected) || selected.isEmpty())
            throw new IOException("Protocol requires selected trials");
        Set<String> seen = new HashSet<>();
        for (Object id : selected) {
            if (!(id instanceof String text) || !family.contains(text) || !seen.add(text))
                throw new IOException("Protocol has duplicate or unregistered selected trials");
        }
        Binding binding = new Binding(frozenCatalog.sha256(), PortfolioTrialIdentity.fileSha256(codeArtifact),
                datasetHash, splitHash, RunConfig.sha256(protocol), ledgerHash);
        // Reject input replacement during capture. No numerical holdout field is read here.
        equal(binding.catalogSha256(), PortfolioTrialIdentity.fileSha256(catalog));
        equal(binding.registrationLedgerSha256(), PortfolioTrialIdentity.fileSha256(registrationLedger));
        equal(binding.splitSha256(), PortfolioTrialIdentity.fileSha256(splitPlan));
        equal(binding.protocolSha256(), PortfolioTrialIdentity.fileSha256(preregisteredProtocol));
        equal(binding.codeSha256(), PortfolioTrialIdentity.fileSha256(codeArtifact));
        return binding;
    }

    static void requireHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid SHA-256");
    }
    static void requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || id.equals("NONE"))
            throw new IllegalArgumentException("Invalid release request ID");
    }
    static String digest(String text) { return RunConfig.sha256(text.getBytes(StandardCharsets.UTF_8)); }
    private static void equal(String expected, String actual) throws IOException {
        if (!expected.equals(actual)) throw new IOException("Freeze input identity mismatch");
    }
}
