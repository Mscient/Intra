package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.provenance.RunConfig;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Stable content identity for one input dataset, build, configuration, and strategy. */
public final class RunIdentity {
    private RunIdentity() { }

    public static String contentId(ValidatedDataset dataset, RunConfig config, Strategy strategy) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                write(output, "INTRA_RUN_ID_V10");
                write(output, dataset.barsSha256());
                write(output, config.configSha256());
                write(output, dataset.instrumentMaster().rawFileSha256());
                write(output, dataset.calendarSha256());
                write(output, dataset.costSchedule().map(com.mscient.intrapaper.provenance.CostSchedule::sha256).orElse("NO_COST_SCHEDULE"));
                write(output, dataset.componentCostSchedule().map(com.mscient.intrapaper.provenance.ComponentCostSchedule::sha256)
                        .orElse("NO_COMPONENT_COST_SCHEDULE"));
                write(output, dataset.marginRequirementSchedule().map(com.mscient.intrapaper.provenance.MarginRequirementSchedule::sha256)
                        .orElse("NO_MARGIN_REQUIREMENT_SCHEDULE"));
                write(output, dataset.expirySettlement().map(com.mscient.intrapaper.provenance.ExpirySettlement::sha256).orElse("NO_EXPIRY_SETTLEMENT"));
                write(output, dataset.settlementCostSchedule().map(com.mscient.intrapaper.provenance.SettlementCostSchedule::sha256)
                        .orElse("NO_SETTLEMENT_COST_SCHEDULE"));
                write(output, dataset.quoteHistory().map(com.mscient.intrapaper.marketdata.VerifiedQuoteHistory::fileSha256)
                        .orElse("NO_QUOTE_JOURNAL"));
                write(output, config.commitId());
                write(output, strategy.id());
                Map<String, String> parameters = new TreeMap<>(strategy.parameters());
                output.writeInt(parameters.size());
                for (Map.Entry<String, String> parameter : parameters.entrySet()) {
                    write(output, parameter.getKey());
                    write(output, parameter.getValue());
                }
            }
            return RunConfig.sha256(bytes.toByteArray()).substring(0, 24);
        } catch (IOException impossibleForMemoryStream) {
            throw new IllegalStateException("Unable to compute run identity", impossibleForMemoryStream);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
