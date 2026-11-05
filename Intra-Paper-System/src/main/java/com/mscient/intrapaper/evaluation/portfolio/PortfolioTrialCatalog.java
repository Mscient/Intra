package com.mscient.intrapaper.evaluation.portfolio;

import com.mscient.intrapaper.data.StrictJsonParser;
import com.mscient.intrapaper.evaluation.ResearchTrialLedger;
import com.mscient.intrapaper.provenance.RunConfig;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.mscient.intrapaper.evaluation.portfolio.PortfolioEvaluationJson.parsedObject;
import static com.mscient.intrapaper.evaluation.portfolio.PortfolioEvaluationJson.string;

/** Strict, externally supplied binding between a registration, one portfolio run, and frozen split dates. */
public record PortfolioTrialCatalog(List<Entry> trials, String sha256, Path path) {
    public static final String SCHEMA = "INTRA_PORTFOLIO_TRIAL_CATALOG_V1";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final Set<String> TRIAL_KEYS = Set.of("trial_id", "strategy_id", "strategy_version",
            "variant_id", "strategy_card_sha256", "dataset_sha256", "split_plan_sha256",
            "parameter_config_sha256", "registration_record_sha256", "run_id",
            "run_manifest_sha256", "split_plan_csv", "starting_capital_inr");

    public record Entry(ResearchTrialLedger.Trial trial, String registrationRecordSha256,
            String runId, String runManifestSha256, Path splitPlanPath, BigDecimal startingCapitalInr) { }

    public PortfolioTrialCatalog { trials = List.copyOf(trials); }

    public static PortfolioTrialCatalog load(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.size(absolute) > MAX_BYTES) throw new IOException("Unsupported trial catalog size");
        byte[] bytes = PortfolioTrialIdentity.readRegular(absolute);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IOException("Unsupported trial catalog size");
        var root = parsedObject(StrictJsonParser.parse(PortfolioTrialIdentity.utf8(bytes)), "trial catalog");
        if (!root.keySet().equals(Set.of("schema", "trials"))) {
            throw new IOException("Trial catalog keys must be exactly schema and trials");
        }
        if (!SCHEMA.equals(string(root, "schema"))) throw new IOException("Unsupported portfolio trial catalog schema");
        if (!(root.get("trials") instanceof List<?> rows) || rows.isEmpty()) {
            throw new IOException("Trial catalog must contain trial rows");
        }
        List<Entry> entries = new ArrayList<>(rows.size());
        HashSet<String> trialIds = new HashSet<>();
        HashSet<String> runIds = new HashSet<>();
        for (Object item : rows) {
            var row = parsedObject(item, "catalog trial");
            if (!row.keySet().equals(TRIAL_KEYS)) throw new IOException("Unexpected or missing catalog trial keys");
            ResearchTrialLedger.Trial trial;
            try {
                trial = new ResearchTrialLedger.Trial(string(row, "trial_id"), string(row, "strategy_id"),
                        string(row, "strategy_version"), string(row, "variant_id"),
                        string(row, "strategy_card_sha256"), string(row, "dataset_sha256"),
                        string(row, "split_plan_sha256"), string(row, "parameter_config_sha256"));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid catalog trial identity", invalid);
            }
            String registrationHash = hash(string(row, "registration_record_sha256"), "registration_record_sha256");
            String runId = hash(string(row, "run_id"), "run_id");
            String manifestHash = hash(string(row, "run_manifest_sha256"), "run_manifest_sha256");
            if (!trialIds.add(trial.trialId())) throw new IOException("Duplicate catalog trial_id: " + trial.trialId());
            if (!runIds.add(runId)) throw new IOException("Duplicate catalog run_id: " + runId);
            String capitalText = string(row, "starting_capital_inr");
            if (capitalText.length() > 256 || !capitalText.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) {
                throw new IOException("starting_capital_inr must be a positive plain decimal string");
            }
            BigDecimal capital = new BigDecimal(capitalText).stripTrailingZeros();
            if (capital.signum() <= 0) throw new IOException("starting_capital_inr must be positive");
            Path split;
            try {
                split = Path.of(string(row, "split_plan_csv"));
                if (!split.isAbsolute()) split = absolute.getParent().resolve(split);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid split_plan_csv path", invalid);
            }
            entries.add(new Entry(trial, registrationHash, runId, manifestHash,
                    split.toAbsolutePath().normalize(), capital));
        }
        return new PortfolioTrialCatalog(entries, RunConfig.sha256(bytes), absolute);
    }

    private static String hash(String text, String label) throws IOException {
        if (!text.matches("[0-9a-f]{64}")) throw new IOException(label + " must be lowercase SHA-256 hex");
        return text;
    }
}
