package com.mscient.intrapaper.data;

import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.reference.TimestampRole;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Multiple individually validated contract datasets aligned without filling or interpolating observations. */
public record ValidatedPortfolioDataset(
        Map<String, ValidatedDataset> datasetsByInstrument,
        List<SynchronizedBarFrameBuilder.Frame> frames,
        String datasetsSha256) {

    /** Input paths for one dated contract; the map key passed to {@link #load(Map)} is its stable instrument ID. */
    public record Input(Path barsCsv, Path runProperties) {
        public Input {
            barsCsv = Objects.requireNonNull(barsCsv, "barsCsv").toAbsolutePath().normalize();
            runProperties = Objects.requireNonNull(runProperties, "runProperties").toAbsolutePath().normalize();
        }
    }

    public ValidatedPortfolioDataset {
        Objects.requireNonNull(datasetsByInstrument, "datasetsByInstrument");
        TreeMap<String, ValidatedDataset> ordered = new TreeMap<>();
        datasetsByInstrument.forEach((instrumentId, dataset) -> {
            requireInstrumentId(instrumentId);
            if (ordered.putIfAbsent(instrumentId, Objects.requireNonNull(dataset, "dataset")) != null) {
                throw new IllegalArgumentException("Duplicate portfolio instrument: " + instrumentId);
            }
        });
        if (ordered.size() < 2) throw new IllegalArgumentException("A validated portfolio requires at least two instruments");
        datasetsByInstrument = Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
        frames = List.copyOf(Objects.requireNonNull(frames, "frames"));
        if (frames.isEmpty()) throw new IllegalArgumentException("A validated portfolio requires synchronized frames");
        if (datasetsSha256 == null || !datasetsSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("datasetsSha256 must be lowercase SHA-256");
        }
        TreeMap<String, List<Bar>> barsByInstrument = new TreeMap<>();
        ordered.forEach((instrumentId, dataset) -> {
            if (dataset.bars().stream().anyMatch(bar -> !bar.instrumentId().equals(instrumentId))) {
                throw new IllegalArgumentException("Validated portfolio dataset contains a different instrument: " + instrumentId);
            }
            barsByInstrument.put(instrumentId, dataset.bars());
        });
        if (!frames.equals(SynchronizedBarFrameBuilder.build(barsByInstrument))) {
            throw new IllegalArgumentException("Portfolio frames must be the exact synchronized rows from their validated datasets");
        }
        if (!datasetsSha256.equals(hashDatasets(ordered))) {
            throw new IllegalArgumentException("Portfolio dataset fingerprint does not match validated dataset provenance");
        }
    }

    /** Loads every contract through the existing single-contract validator, then requires exact frame alignment. */
    public static ValidatedPortfolioDataset load(Map<String, Input> inputsByInstrument) throws IOException {
        return load(inputsByInstrument, java.util.Set.of(), java.util.Optional.empty());
    }

    public static ValidatedPortfolioDataset load(Map<String, Input> inputsByInstrument,
            java.util.Set<String> settlementTargets,
            java.util.Optional<com.mscient.intrapaper.simulation.portfolio.PortfolioSettlementEvents> settlementEvents) throws IOException {
        Objects.requireNonNull(inputsByInstrument, "inputsByInstrument");
        if (inputsByInstrument.size() < 2) throw new IllegalArgumentException("At least two contract inputs are required");
        TreeMap<String, Input> inputs = new TreeMap<>();
        inputsByInstrument.forEach((instrumentId, input) -> {
            requireInstrumentId(instrumentId);
            if (inputs.putIfAbsent(instrumentId, Objects.requireNonNull(input, "input")) != null) {
                throw new IllegalArgumentException("Duplicate portfolio instrument input: " + instrumentId);
            }
        });

        LinkedHashMap<String, ValidatedDataset> datasets = new LinkedHashMap<>();
        TreeMap<String, List<Bar>> barsByInstrument = new TreeMap<>();
        for (Map.Entry<String, Input> entry : inputs.entrySet()) {
            String instrumentId = entry.getKey();
            RunConfig config = RunConfig.load(entry.getValue().runProperties());
            if (config.timestampRole() != TimestampRole.BAR_START) {
                throw new IllegalArgumentException("Multi-instrument portfolio replay requires BAR_START data: " + instrumentId);
            }
            ValidatedDataset dataset = ValidatedDataset.loadForPortfolio(entry.getValue().barsCsv(), config);
            if (dataset.bars().stream().anyMatch(bar -> !bar.instrumentId().equals(instrumentId))) {
                throw new IllegalArgumentException("Portfolio input key does not match its validated contract identity: " + instrumentId);
            }
            datasets.put(instrumentId, dataset);
            barsByInstrument.put(instrumentId, dataset.bars());
        }

        List<SynchronizedBarFrameBuilder.Frame> frames = SynchronizedBarFrameBuilder.build(barsByInstrument);
        ValidatedPortfolioDataset result = new ValidatedPortfolioDataset(datasets, frames, hashDatasets(datasets));
        com.mscient.intrapaper.simulation.portfolio.PortfolioSettlementEvents.validateSelection(result, settlementTargets, settlementEvents);
        return result;
    }

    private static String hashDatasets(Map<String, ValidatedDataset> datasets) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                write(output, "INTRA_VALIDATED_PORTFOLIO_DATASETS_V1");
                output.writeInt(datasets.size());
                for (Map.Entry<String, ValidatedDataset> entry : new TreeMap<>(datasets).entrySet()) {
                    ValidatedDataset dataset = entry.getValue();
                    write(output, entry.getKey());
                    write(output, dataset.barsSha256());
                    write(output, dataset.configSha256());
                    write(output, dataset.calendarSha256());
                    write(output, dataset.manifest().instrumentMasterSha256());
                    write(output, dataset.marketRuleRegimeSchedule().sha256());
                    write(output, dataset.manifest().datasetId());
                    write(output, dataset.manifest().sourceName());
                    write(output, dataset.manifest().dataRightsStatus().name());
                    write(output, dataset.manifest().dataRightsReference().orElse(""));
                    TreeMap<String, String> additionalInputs = new TreeMap<>(dataset.manifest().inputFileSha256());
                    output.writeInt(additionalInputs.size());
                    for (Map.Entry<String, String> input : additionalInputs.entrySet()) {
                        write(output, input.getKey());
                        write(output, input.getValue());
                    }
                }
            }
            return RunConfig.sha256(bytes.toByteArray());
        } catch (IOException impossibleForMemoryStream) {
            throw new IllegalStateException("Unable to serialize validated portfolio dataset identity", impossibleForMemoryStream);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(utf8.length);
        output.write(utf8);
    }

    private static void requireInstrumentId(String value) {
        Objects.requireNonNull(value, "instrumentId");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException("Invalid portfolio instrument ID: " + value);
        }
    }
}
