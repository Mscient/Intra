package com.mscient.intrapaper.data;

import com.mscient.intrapaper.provenance.DatasetManifest;
import com.mscient.intrapaper.provenance.CostSchedule;
import com.mscient.intrapaper.provenance.ComponentCostSchedule;
import com.mscient.intrapaper.provenance.ExpirySettlement;
import com.mscient.intrapaper.provenance.MarginRequirementSchedule;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.reference.ContractKind;
import com.mscient.intrapaper.reference.InstrumentMasterSnapshot;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Inputs after schema, full-session boundary, provenance, and as-of instrument checks pass. */
public record ValidatedDataset(
        List<Bar> bars,
        InstrumentMasterSnapshot instrumentMaster,
        Map<LocalDate, SessionCalendarEntry> sessionCalendar,
        DatasetManifest manifest,
        String barsSha256,
        String configSha256,
        String calendarSha256,
        Optional<CostSchedule> costSchedule,
        Optional<ComponentCostSchedule> componentCostSchedule,
        Optional<MarginRequirementSchedule> marginRequirementSchedule,
        Optional<ExpirySettlement> expirySettlement,
        long timestampSpacingAnomalies,
        long estimatedMissingIntervals,
        long expectedBarCount,
        long openSessionCount,
        long completeSessionCount) {

    public static ValidatedDataset load(java.nio.file.Path barsPath, RunConfig config) throws IOException {
        BarsCsvReader.ReadResult barsRead = BarsCsvReader.readWithHash(barsPath, config);
        List<Bar> bars = barsRead.bars();
        Duration expectedSpacing = IntervalSupport.duration(config.interval());
        InstrumentMasterSnapshot master = InstrumentMasterCsvReader.read(config);
        SessionCalendarCsvReader.ReadResult calendarRead = SessionCalendarCsvReader.readWithHash(config.sessionCalendarPath());
        Map<LocalDate, SessionCalendarEntry> calendar = calendarRead.sessions();
        Optional<CostSchedule> costSchedule = config.costSchedulePath().isPresent()
                ? Optional.of(CostSchedule.load(config.costSchedulePath().orElseThrow())) : Optional.empty();
        Optional<ComponentCostSchedule> componentCostSchedule = config.componentCostSchedulePath().isPresent()
                ? Optional.of(ComponentCostSchedule.load(config.componentCostSchedulePath().orElseThrow())) : Optional.empty();
        Optional<MarginRequirementSchedule> marginRequirementSchedule = config.marginRequirementSchedulePath().isPresent()
                ? Optional.of(MarginRequirementSchedule.load(config.marginRequirementSchedulePath().orElseThrow())) : Optional.empty();
        Optional<ExpirySettlement> expirySettlement = config.expirySettlementPath().isPresent()
                ? Optional.of(ExpirySettlement.load(config.expirySettlementPath().orElseThrow())) : Optional.empty();
        Map<LocalDate, List<Bar>> barsByDate = new HashMap<>();
        Bar first = bars.getFirst();
        Bar last = bars.getLast();
        String onlyInstrument = first.instrumentId();
        String onlyToken = first.sourceToken();
        String onlySymbol = first.tradingSymbol();
        LocalDate onlyExpiry = first.expiry();
        var onlyKind = first.contractKind();
        var onlyStrike = first.strike();
        var onlyRight = first.optionRight();
        ZoneId timezone = ZoneId.of(config.timezone());

        for (Bar bar : bars) {
            if (!bar.interval().equals(config.interval())) {
                throw new IllegalArgumentException("Bar interval " + bar.interval() + " differs from configured " + config.interval());
            }
            if (!bar.instrumentId().equals(onlyInstrument) || !bar.sourceToken().equals(onlyToken)
                    || !bar.tradingSymbol().equals(onlySymbol) || !bar.expiry().equals(onlyExpiry)
                    || bar.contractKind() != onlyKind || !sameStrike(bar.strike(), onlyStrike)
                    || !bar.optionRight().equals(onlyRight)) {
                throw new IllegalArgumentException("One dated derivative contract is required per run; found " + bar.instrumentId());
            }
            if (!bar.source().equals(config.datasetSourceName())) {
                throw new IllegalArgumentException("Bar source does not match dataset.source_name: " + bar.source());
            }
            if (bar.contractKind() != ContractKind.FUTURE && bar.contractKind() != ContractKind.OPTION) {
                throw new IllegalArgumentException("This build supports dated futures and single-option premium bars only; found " + bar.contractKind());
            }
            if (bar.sessionDate().isAfter(bar.expiry())) {
                throw new IllegalArgumentException("Expired contract bar is not eligible: " + bar.instrumentId() + " on " + bar.sessionDate());
            }
            var reference = master.requireContract(bar.exchange(), bar.segment(), bar.sourceToken(), bar.sessionDate());
            if (!bar.instrumentId().equals(reference.instrumentId())
                    || !bar.tradingSymbol().equals(reference.tradingSymbol())
                    || bar.contractKind() != reference.contractKind()
                    || !reference.underlying().orElse("").equals(bar.underlying())
                    || !reference.expiry().orElseThrow().equals(bar.expiry())
                    || !sameStrike(bar.strike(), reference.strike())
                    || !bar.optionRight().equals(reference.optionRight())
                    || bar.lotSize() != reference.lotSize()
                    || bar.tickSize().compareTo(reference.tickSize()) != 0) {
                throw new IllegalArgumentException("Bar contract metadata does not match dated instrument master at " + bar.timestamp());
            }
            SessionCalendarEntry entry = calendar.get(bar.sessionDate());
            if (entry == null || !entry.open()) {
                throw new IllegalArgumentException("Bar date is not marked OPEN in the supplied calendar: " + bar.sessionDate());
            }
            if (bar.timestamp().isBefore(entry.firstBarTimestamp().orElseThrow())
                    || bar.timestamp().isAfter(entry.lastBarTimestamp().orElseThrow())) {
                throw new IllegalArgumentException("Bar timestamp lies outside the supplied session window: " + bar.timestamp());
            }
            if (!bar.timestamp().atZone(timezone).toLocalDate().equals(entry.sessionDate())) {
                throw new IllegalArgumentException("Session calendar timestamp does not resolve to its declared local date: " + bar.sessionDate());
            }
            barsByDate.computeIfAbsent(bar.sessionDate(), ignored -> new ArrayList<>()).add(bar);
        }

        long anomalies = 0;
        long estimatedMissingIntervals = 0;
        long expectedBarCount = 0;
        long openSessionCount = 0;
        long completeSessions = 0;
        boolean finalSessionComplete = false;
        for (LocalDate date = first.sessionDate(); !date.isAfter(last.sessionDate()); date = date.plusDays(1)) {
            SessionCalendarEntry entry = calendar.get(date);
            if (entry == null) {
                throw new IllegalArgumentException("Session calendar must include every local calendar date in the data range: " + date);
            }
            List<Bar> sessionBars = barsByDate.get(date);
            if (!entry.open()) {
                if (sessionBars != null) throw new IllegalArgumentException("Bars exist on a CLOSED calendar date: " + date);
                continue;
            }
            openSessionCount++;
            if (costSchedule.isPresent()) {
                costSchedule.orElseThrow().require(date, CostSchedule.Side.BUY);
                costSchedule.orElseThrow().require(date, CostSchedule.Side.SELL);
            }
            if (componentCostSchedule.isPresent()) {
                componentCostSchedule.orElseThrow().require(date, onlyKind, CostSchedule.Side.BUY);
                componentCostSchedule.orElseThrow().require(date, onlyKind, CostSchedule.Side.SELL);
            }
            if (marginRequirementSchedule.isPresent()) {
                marginRequirementSchedule.orElseThrow().require(date, onlyInstrument, onlyKind);
            }
            if (sessionBars == null || sessionBars.isEmpty()) {
                throw new IllegalArgumentException("Entire OPEN session is missing from bars: " + date);
            }

            var windowStart = entry.firstBarTimestamp().orElseThrow();
            var windowEnd = entry.lastBarTimestamp().orElseThrow();
            if (!sessionBars.getFirst().timestamp().equals(windowStart)
                    || !sessionBars.getLast().timestamp().equals(windowEnd)) {
                throw new IllegalArgumentException("First/last bars do not match the declared session boundaries on " + date);
            }
            Duration window = Duration.between(windowStart, windowEnd);
            long intervalNanos = expectedSpacing.toNanos();
            long windowNanos = window.toNanos();
            if (windowNanos % intervalNanos != 0) {
                throw new IllegalArgumentException("Session boundary span is not aligned to configured interval on " + date);
            }
            long expectedForSession = windowNanos / intervalNanos + 1;
            expectedBarCount += expectedForSession;
            long missingIntervals = expectedForSession - sessionBars.size();
            if (missingIntervals < 0) {
                throw new IllegalArgumentException("More bars than the declared session window permits on " + date);
            }
            if (missingIntervals > config.maxMissingIntervalsPerSession()) {
                throw new IllegalArgumentException("Estimated missing intervals " + missingIntervals
                        + " exceed quality.max_missing_intervals_per_session="
                        + config.maxMissingIntervalsPerSession() + " on " + date);
            }
            estimatedMissingIntervals += missingIntervals;
            boolean sessionHasSpacingAnomaly = false;
            for (int i = 1; i < sessionBars.size(); i++) {
                Duration actual = Duration.between(sessionBars.get(i - 1).timestamp(), sessionBars.get(i).timestamp());
                if (!actual.equals(expectedSpacing)) {
                    anomalies++;
                    sessionHasSpacingAnomaly = true;
                    if (!config.allowIntraSessionGaps()) {
                        throw new IllegalArgumentException("Unexpected same-session timestamp spacing between "
                                + sessionBars.get(i - 1).timestamp() + " and " + sessionBars.get(i).timestamp()
                                + "; set quality.allow_intrasession_gaps=true only after reviewing the source/session schedule");
                    }
                }
            }
            boolean sessionComplete = sessionBars.size() == expectedForSession && !sessionHasSpacingAnomaly;
            if (sessionComplete) completeSessions++;
            if (date.equals(last.sessionDate())) finalSessionComplete = sessionComplete;
        }

        if (expirySettlement.isPresent()) {
            ExpirySettlement settlement = expirySettlement.orElseThrow();
            if (!settlement.instrumentId().equals(onlyInstrument) || !settlement.expiry().equals(onlyExpiry)) {
                throw new IllegalArgumentException("Expiry settlement instrument/expiry does not match the bars");
            }
            var expectedSettlementKind = onlyKind == ContractKind.FUTURE
                    ? ExpirySettlement.Kind.FUTURES_FINAL_PRICE : ExpirySettlement.Kind.UNDERLYING_FINAL_CLOSE;
            if (settlement.kind() != expectedSettlementKind) {
                throw new IllegalArgumentException("Settlement kind " + settlement.kind()
                        + " does not match contract kind " + onlyKind + "; expected " + expectedSettlementKind);
            }
            if (!settlement.settlementDate().equals(last.sessionDate())) {
                throw new IllegalArgumentException("Settlement date must equal the final dataset session date");
            }
            if (!finalSessionComplete) {
                throw new IllegalArgumentException("Expiry settlement requires a complete final session");
            }
            if (!settlement.settlementTimestamp().atZone(timezone).toLocalDate().equals(settlement.settlementDate())) {
                throw new IllegalArgumentException("Settlement timestamp does not resolve to settlement_date in dataset.timezone");
            }
            Instant lastBarEnd = config.timestampRole() == com.mscient.intrapaper.reference.TimestampRole.BAR_END
                    ? last.timestamp() : last.timestamp().plus(expectedSpacing);
            if (settlement.settlementTimestamp().isBefore(lastBarEnd)) {
                throw new IllegalArgumentException("Settlement timestamp precedes the final bar end");
            }
        } else if (last.sessionDate().equals(onlyExpiry)) {
            throw new IllegalArgumentException("A settlement input is required when the dataset includes a contract expiry-date session");
        }

        String barsHash = barsRead.sha256();
        String calendarHash = calendarRead.sha256();
        Map<String, String> inputs = new java.util.TreeMap<>(Map.of(
                "bars", barsHash,
                "instrument-master", master.rawFileSha256(),
                "session-calendar", calendarHash));
        costSchedule.ifPresent(schedule -> inputs.put("cost-schedule", schedule.sha256()));
        componentCostSchedule.ifPresent(schedule -> inputs.put("component-cost-schedule", schedule.sha256()));
        marginRequirementSchedule.ifPresent(schedule -> inputs.put("margin-requirement-schedule", schedule.sha256()));
        expirySettlement.ifPresent(settlement -> inputs.put("expiry-settlement", settlement.sha256()));
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
        return new ValidatedDataset(List.copyOf(bars), master, calendar, manifest,
                barsHash, config.configSha256(), calendarHash, costSchedule, componentCostSchedule,
                marginRequirementSchedule, expirySettlement, anomalies,
                estimatedMissingIntervals, expectedBarCount, openSessionCount, completeSessions);
    }

    private static boolean sameStrike(Optional<java.math.BigDecimal> left, Optional<java.math.BigDecimal> right) {
        if (left.isEmpty() || right.isEmpty()) return left.isEmpty() == right.isEmpty();
        return left.orElseThrow().compareTo(right.orElseThrow()) == 0;
    }
}
