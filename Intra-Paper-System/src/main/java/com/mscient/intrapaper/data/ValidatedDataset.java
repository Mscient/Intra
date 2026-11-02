package com.mscient.intrapaper.data;

import com.mscient.intrapaper.provenance.DatasetManifest;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.reference.InstrumentMasterSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Inputs after schema, calendar, provenance, and as-of instrument checks pass. */
public record ValidatedDataset(
        List<Bar> bars,
        InstrumentMasterSnapshot instrumentMaster,
        Set<LocalDate> openSessions,
        DatasetManifest manifest,
        String barsSha256,
        String configSha256,
        String calendarSha256) {

    public static ValidatedDataset load(java.nio.file.Path barsPath, RunConfig config) throws IOException {
        List<Bar> bars = BarsCsvReader.read(barsPath, config);
        InstrumentMasterSnapshot master = InstrumentMasterCsvReader.read(config);
        Set<LocalDate> sessions = SessionCalendarCsvReader.read(config.sessionCalendarPath());
        Bar first = bars.getFirst();
        String onlyInstrument = first.instrumentId();
        String onlyToken = first.sourceToken();
        String onlySymbol = first.tradingSymbol();
        LocalDate onlyExpiry = first.expiry();
        for (Bar bar : bars) {
            if (!sessions.contains(bar.sessionDate())) {
                throw new IllegalArgumentException("Bars contain a date not marked OPEN in the supplied calendar: " + bar.sessionDate());
            }
            if (!bar.interval().equals(config.interval())) {
                throw new IllegalArgumentException("Bar interval " + bar.interval() + " differs from configured " + config.interval());
            }
            if (!bar.instrumentId().equals(onlyInstrument) || !bar.sourceToken().equals(onlyToken)
                    || !bar.tradingSymbol().equals(onlySymbol) || !bar.expiry().equals(onlyExpiry)) {
                throw new IllegalArgumentException("One dated futures contract is required per run; found " + bar.instrumentId());
            }
            if (!bar.source().equals(config.datasetSourceName())) {
                throw new IllegalArgumentException("Bar source does not match dataset.source_name: " + bar.source());
            }
            if (bar.contractKind() != com.mscient.intrapaper.reference.ContractKind.FUTURE) {
                throw new IllegalArgumentException("This build supports dated futures only; found " + bar.contractKind());
            }
            if (!bar.expiry().isAfter(bar.sessionDate())) {
                throw new IllegalArgumentException("Expired contract bar is not eligible: " + bar.instrumentId() + " on " + bar.sessionDate());
            }
            var reference = master.requireContract(bar.exchange(), bar.segment(), bar.sourceToken(), bar.sessionDate());
            if (!bar.instrumentId().equals(reference.instrumentId())
                    || !bar.tradingSymbol().equals(reference.tradingSymbol())
                    || bar.contractKind() != reference.contractKind()
                    || !reference.underlying().orElse("").equals(bar.underlying())
                    || !reference.expiry().orElseThrow().equals(bar.expiry())
                    || bar.lotSize() != reference.lotSize()
                    || bar.tickSize().compareTo(reference.tickSize()) != 0) {
                throw new IllegalArgumentException("Bar contract metadata does not match dated instrument master at " + bar.timestamp());
            }
        }
        String barsHash = RunConfig.sha256(Files.readAllBytes(barsPath));
        String calendarHash = RunConfig.sha256(Files.readAllBytes(config.sessionCalendarPath()));
        Map<String, String> inputs = Map.of(
                "bars", barsHash,
                "instrument-master", master.rawFileSha256(),
                "session-calendar", calendarHash);
        DatasetManifest manifest = new DatasetManifest(
                1,
                config.datasetId(),
                config.datasetSourceName(),
                config.datasetAcquiredAt(),
                config.timezone(),
                config.timestampRole(),
                config.dataRightsStatus(),
                config.dataRightsReference(),
                master.snapshotId(),
                master.rawFileSha256(),
                Optional.of(calendarHash),
                inputs);
        return new ValidatedDataset(List.copyOf(bars), master, sessions, manifest,
                barsHash, config.configSha256(), calendarHash);
    }
}
