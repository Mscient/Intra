package com.mscient.intrapaper.evaluation;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.provenance.DatasetManifest;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.simulation.Strategy;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Resolves preregistered trial identity to the exact inputs used by one backtest run. */
public final class ResearchTrialBinding {
    public record Evidence(ResearchTrialLedger.Trial trial, ResearchTrialLedger.Entry registration) { }

    private ResearchTrialBinding() { }

    public static ResearchTrialLedger.Trial expectedTrial(RunConfig config, ValidatedDataset dataset,
            Strategy strategy) throws IOException {
        return expectedTrial(config, dataset, strategy.id());
    }

    public static ResearchTrialLedger.Trial expectedTrial(RunConfig config, ValidatedDataset dataset,
            String strategyId) throws IOException {
        if (config.researchTrialLedgerPath().isEmpty()) {
            throw new IllegalArgumentException("Research trial registration is not configured");
        }
        if (config.researchTrialId().isEmpty() || config.researchStrategyVersion().isEmpty()
                || config.researchVariantId().isEmpty() || config.researchStrategyCardPath().isEmpty()
                || config.evaluationSplitPlanPath().isEmpty()) {
            throw new IllegalArgumentException("Research trial configuration is incomplete");
        }
        String cardHash = RunConfig.sha256(Files.readAllBytes(config.researchStrategyCardPath().orElseThrow()));
        String splitHash = EvaluationSplitPlan.load(config.evaluationSplitPlanPath().orElseThrow(), dataset).splitFileSha256();
        return new ResearchTrialLedger.Trial(config.researchTrialId().orElseThrow(), strategyId,
                config.researchStrategyVersion().orElseThrow(), config.researchVariantId().orElseThrow(),
                cardHash, datasetSha256(dataset), splitHash, config.trialParameterConfigSha256());
    }

    public static Optional<Evidence> resolve(RunConfig config, ValidatedDataset dataset, Strategy strategy)
            throws IOException {
        return resolve(config, dataset, strategy.id());
    }

    public static Optional<Evidence> resolve(RunConfig config, ValidatedDataset dataset, String strategyId)
            throws IOException {
        if (config.researchTrialLedgerPath().isEmpty()) {
            if (!config.syntheticFixture()) {
                throw new IllegalArgumentException("Non-synthetic strategy runs require a preregistered research trial");
            }
            return Optional.empty();
        }
        ResearchTrialLedger.Trial expected = expectedTrial(config, dataset, strategyId);
        ResearchTrialLedger.Entry registration = ResearchTrialLedger.requireRegistered(
                config.researchTrialLedgerPath().orElseThrow(), expected);
        return Optional.of(new Evidence(expected, registration));
    }

    private static String datasetSha256(ValidatedDataset dataset) {
        DatasetManifest manifest = dataset.manifest();
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                write(output, "INTRA_TRIAL_DATASET_V1");
                write(output, manifest.datasetId());
                write(output, manifest.sourceName());
                write(output, manifest.acquiredAtUtc().toString());
                write(output, manifest.timezone());
                write(output, manifest.timestampRole().name());
                write(output, manifest.dataRightsStatus().name());
                write(output, manifest.dataRightsReference().orElse(""));
                write(output, manifest.instrumentMasterSnapshotId());
                write(output, manifest.instrumentMasterSha256());
                write(output, manifest.sessionCalendarSha256().orElse(""));
                Map<String, String> inputHashes = new TreeMap<>(manifest.inputFileSha256());
                output.writeInt(inputHashes.size());
                for (Map.Entry<String, String> input : inputHashes.entrySet()) {
                    write(output, input.getKey());
                    write(output, input.getValue());
                }
                write(output, dataset.barsSha256());
                write(output, dataset.calendarSha256());
                write(output, dataset.marketRuleRegimeSchedule().sha256());
                write(output, dataset.costSchedule().map(value -> value.sha256()).orElse("NO_COST_SCHEDULE"));
                write(output, dataset.componentCostSchedule().map(value -> value.sha256()).orElse("NO_COMPONENT_COST_SCHEDULE"));
                write(output, dataset.marginRequirementSchedule().map(value -> value.sha256()).orElse("NO_MARGIN_SCHEDULE"));
                write(output, dataset.expirySettlement().map(value -> value.sha256()).orElse("NO_EXPIRY_SETTLEMENT"));
                write(output, dataset.settlementCostSchedule().map(value -> value.sha256()).orElse("NO_SETTLEMENT_COST_SCHEDULE"));
                write(output, dataset.quoteHistory().map(value -> value.fileSha256()).orElse("NO_QUOTE_JOURNAL"));
            }
            return RunConfig.sha256(bytes.toByteArray());
        } catch (IOException impossibleForMemoryStream) {
            throw new IllegalStateException("Unable to hash trial dataset inputs", impossibleForMemoryStream);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
