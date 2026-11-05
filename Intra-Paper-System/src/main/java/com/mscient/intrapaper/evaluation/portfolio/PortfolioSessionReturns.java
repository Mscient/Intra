package com.mscient.intrapaper.evaluation.portfolio;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.evaluation.EvaluationSplitPlan;
import com.mscient.intrapaper.evaluation.EvaluationSplitPlan.Partition;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static com.mscient.intrapaper.evaluation.portfolio.PortfolioTrialIdentity.requireEqual;

/** Reads one aggregate account mark per timestamp/phase; repeated instrument totals are never summed. */
final class PortfolioSessionReturns {
    private static final List<String> DATASET_HEADERS = List.of("instrument_id", "dataset_id", "source_name",
            "acquired_at_utc", "timezone", "data_rights_status", "data_rights_reference", "bars_sha256",
            "config_sha256", "calendar_sha256", "instrument_master_snapshot_id", "instrument_master_sha256",
            "market_rule_sha256", "input_file_hashes_base64", "validated_bar_count", "complete_session_count",
            "exchange", "segment", "source_token", "trading_symbol", "contract_kind", "underlying", "expiry", "strike",
            "option_right", "lot_size", "tick_size", "final_session_date", "final_bar_close_timestamp_utc", "final_session_complete");
    private static final List<String> EQUITY_HEADERS = List.of("timestamp_utc", "phase", "instrument_id",
            "signed_units", "average_open_price", "realized_gross_pnl_inr", "fees_inr", "unrealized_pnl_inr",
            "total_realized_gross_pnl_inr", "total_fees_inr", "total_unrealized_pnl_inr", "net_pnl_inr");
    private static final List<String> TOTAL_FIELDS = List.of("total_realized_gross_pnl_inr",
            "total_fees_inr", "total_unrealized_pnl_inr", "net_pnl_inr");

    record Session(LocalDate date, Partition partition, BigDecimal realizedGrossPnlChangeInr,
            BigDecimal unrealizedPnlChangeInr, BigDecimal feesPaidInr, BigDecimal netPnlChangeInr,
            BigDecimal sessionReturn) { }

    record Series(List<Session> sessions, BigDecimal totalNetPnlInr, int instrumentCount,
            int developmentSessionCount, int validationSessionCount, int excludedHoldoutSessionCount) {
        Series { sessions = List.copyOf(sessions); }
        double[] returns() { return sessions.stream().mapToDouble(s -> s.sessionReturn().doubleValue()).toArray(); }
    }

    private record Snapshot(Instant timestamp, String phase, LocalDate date, boolean flat,
            BigDecimal realized, BigDecimal fees, BigDecimal unrealized, BigDecimal net) { }
    private record SessionBoundary(boolean startFlat, Snapshot lastClose) { }

    private PortfolioSessionReturns() { }

    static Series read(Path runFolder, PortfolioTrialIdentity.FrozenRun run, EvaluationSplitPlan split,
            BigDecimal capital) throws IOException {
        List<LocalDate> plannedDates = java.util.Arrays.stream(Partition.values())
                .flatMap(partition -> split.sessions().get(partition).stream()).toList();
        TreeMap<LocalDate, Partition> assignments = new TreeMap<>();
        split.sessions().forEach((partition, dates) -> dates.forEach(date -> assignments.put(date, partition)));

        CsvTable datasets = verifiedTable(runFolder, run, "datasets.csv", DATASET_HEADERS);
        TreeMap<String, Map<String, String>> instruments = new TreeMap<>();
        ZoneId zone = null;
        Integer expectedFrames = null;
        for (var row : datasets.rows()) {
            String id = row.get("instrument_id");
            if (id.isBlank() || !id.equals(id.strip()) || instruments.putIfAbsent(id, row) != null) {
                throw new IOException("Invalid or duplicate portfolio dataset instrument");
            }
            ZoneId instrumentZone;
            try { instrumentZone = ZoneId.of(row.get("timezone")); }
            catch (RuntimeException invalid) { throw new IOException("Invalid portfolio session timezone", invalid); }
            if (zone != null && !zone.equals(instrumentZone)) {
                throw new IOException("Portfolio instruments must share one session timezone");
            }
            zone = instrumentZone;
            int frames = positiveInteger(row.get("validated_bar_count"), "validated_bar_count");
            if (expectedFrames != null && expectedFrames != frames) {
                throw new IOException("Portfolio instruments have different validated bar counts");
            }
            expectedFrames = frames;
            if (positiveInteger(row.get("complete_session_count"), "complete_session_count") != plannedDates.size()) {
                throw new IOException("Missing or incomplete portfolio sessions in dataset provenance");
            }
        }
        if (instruments.size() < 2) throw new IOException("Evaluation requires multiple portfolio instruments");
        requireEqual(run.verification().validatedDatasetsSha256(), datasetFingerprint(instruments),
                "portfolio dataset artifact fingerprint");
        if (!instruments.keySet().equals(PortfolioTrialIdentity.lotLimits(run).keySet())) {
            throw new IOException("Portfolio dataset instruments do not match registered lot limits");
        }

        CsvTable equity = verifiedTable(runFolder, run, "equity.csv", EQUITY_HEADERS);
        int instrumentCount = instruments.size();
        if (equity.rows().size() % instrumentCount != 0) {
            throw new IOException("Incomplete portfolio equity snapshot instrument coverage");
        }
        int snapshotCount = equity.rows().size() / instrumentCount;
        if (snapshotCount != positiveInteger(run.manifest().get("snapshot_count"), "snapshot_count")
                || snapshotCount != 2L * expectedFrames) {
            throw new IOException("Portfolio snapshot count does not match validated frame provenance");
        }
        List<String> ids = List.copyOf(instruments.keySet());
        List<Snapshot> snapshots = new ArrayList<>(snapshotCount);
        for (int index = 0; index < equity.rows().size(); index += instrumentCount) {
            snapshots.add(snapshot(equity.rows().subList(index, index + instrumentCount),
                    ids, zone, assignments));
        }

        TreeMap<LocalDate, SessionBoundary> boundaries = new TreeMap<>();
        Snapshot previousClose = null;
        Duration interval = null;
        BigDecimal previousFees = BigDecimal.ZERO;
        for (int index = 0; index < snapshots.size(); index += 2) {
            Snapshot open = snapshots.get(index);
            Snapshot close = snapshots.get(index + 1);
            if (!open.phase().equals("BAR_OPEN") || !close.phase().equals("BAR_CLOSE")
                    || !open.date().equals(close.date()) || !close.timestamp().isAfter(open.timestamp())) {
                throw new IOException("Invalid portfolio BAR_OPEN/BAR_CLOSE session pairing");
            }
            Duration currentInterval = Duration.between(open.timestamp(), close.timestamp());
            if (interval != null && !interval.equals(currentInterval)) {
                throw new IOException("Portfolio bar intervals differ");
            }
            interval = currentInterval;
            if (previousClose != null) {
                if (open.timestamp().isBefore(previousClose.timestamp())
                        || open.date().isBefore(previousClose.date())
                        || (open.date().equals(previousClose.date())
                        && !open.timestamp().equals(previousClose.timestamp()))) {
                    throw new IOException("Portfolio sessions/frames are not chronological and contiguous");
                }
            }
            if (open.fees() != null) {
                if (open.fees().compareTo(previousFees) < 0 || close.fees().compareTo(open.fees()) < 0) {
                    throw new IOException("Portfolio cumulative fees decrease");
                }
                previousFees = close.fees();
            }
            SessionBoundary prior = boundaries.get(open.date());
            boolean startFlat = prior == null ? previousClose == null || previousClose.flat() : prior.startFlat();
            boundaries.put(open.date(), new SessionBoundary(startFlat, close));
            previousClose = close;
        }
        if (!List.copyOf(boundaries.keySet()).equals(plannedDates)) {
            throw new IOException("Portfolio artifacts must contain every and only registered split session");
        }
        for (Partition partition : Partition.values()) {
            List<LocalDate> dates = split.sessions().get(partition);
            if (!boundaries.get(dates.getFirst()).startFlat() || !boundaries.get(dates.getLast()).lastClose().flat()) {
                throw new IOException("Position-contaminated " + partition + " partition boundary");
            }
        }
        if ("REQUIRE_FLAT".equals(run.manifest().get("position_boundary_policy"))
                && boundaries.values().stream().anyMatch(boundary -> !boundary.lastClose().flat())) {
            throw new IOException("Portfolio session boundary contradicts REQUIRE_FLAT");
        }

        List<Session> sessions = new ArrayList<>();
        BigDecimal priorNet = BigDecimal.ZERO;
        BigDecimal priorRealized = BigDecimal.ZERO;
        BigDecimal priorUnrealized = BigDecimal.ZERO;
        BigDecimal priorFees = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;
        for (LocalDate date : plannedDates) {
            Partition partition = assignments.get(date);
            if (partition == Partition.HOLDOUT) continue;
            Snapshot end = boundaries.get(date).lastClose();
            BigDecimal netChange = end.net().subtract(priorNet);
            BigDecimal sessionReturn = netChange.divide(capital, MathContext.DECIMAL128);
            double value = sessionReturn.doubleValue();
            if (!Double.isFinite(value) || (sessionReturn.signum() != 0 && value == 0.0)) {
                throw new IOException("Session return is outside the supported numeric range");
            }
            sessions.add(new Session(date, partition, end.realized().subtract(priorRealized),
                    end.unrealized().subtract(priorUnrealized), end.fees().subtract(priorFees),
                    netChange, sessionReturn));
            totalPnl = totalPnl.add(netChange);
            priorNet = end.net();
            priorRealized = end.realized();
            priorUnrealized = end.unrealized();
            priorFees = end.fees();
        }
        return new Series(sessions, totalPnl, instrumentCount, split.sessions().get(Partition.DEVELOPMENT).size(),
                split.sessions().get(Partition.VALIDATION).size(), split.sessions().get(Partition.HOLDOUT).size());
    }

    private static String datasetFingerprint(TreeMap<String, Map<String, String>> instruments) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            write(output, "INTRA_VALIDATED_PORTFOLIO_DATASETS_V1");
            output.writeInt(instruments.size());
            for (var entry : instruments.entrySet()) {
                write(output, entry.getKey());
                var row = entry.getValue();
                for (String field : List.of("bars_sha256", "config_sha256", "calendar_sha256",
                        "instrument_master_sha256", "market_rule_sha256", "dataset_id", "source_name",
                        "data_rights_status", "data_rights_reference")) write(output, row.get(field));
                var inputs = new TreeMap<>(PortfolioTrialIdentity.parseMap(
                        PortfolioTrialIdentity.decode(row.get("input_file_hashes_base64"))));
                output.writeInt(inputs.size());
                for (var input : inputs.entrySet()) {
                    write(output, input.getKey());
                    write(output, input.getValue());
                }
            }
        }
        return com.mscient.intrapaper.provenance.RunConfig.sha256(bytes.toByteArray());
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static CsvTable verifiedTable(Path folder, PortfolioTrialIdentity.FrozenRun run,
            String name, List<String> headers) throws IOException {
        CsvTable table = CsvTable.read(folder.resolve(name));
        requireEqual(run.verification().outputSha256().get(name), table.sha256(), name + " read hash");
        if (!table.headers().equals(headers)) throw new IOException("Unexpected portfolio " + name + " schema");
        return table;
    }

    private static Snapshot snapshot(List<Map<String, String>> rows, List<String> ids, ZoneId zone,
            Map<LocalDate, Partition> assignments) throws IOException {
        Map<String, String> first = rows.getFirst();
        Instant timestamp;
        try {
            timestamp = Instant.parse(first.get("timestamp_utc"));
            if (!timestamp.toString().equals(first.get("timestamp_utc"))) throw new IllegalArgumentException("noncanonical");
        } catch (RuntimeException invalid) { throw new IOException("Invalid portfolio snapshot timestamp", invalid); }
        LocalDate date = timestamp.atZone(zone).toLocalDate();
        Partition partition = assignments.get(date);
        if (partition == null) throw new IOException("Portfolio snapshot is outside registered split sessions: " + date);
        boolean included = partition != Partition.HOLDOUT;
        boolean flat = true;
        BigDecimal realized = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        BigDecimal unrealized = BigDecimal.ZERO;
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            if (!row.get("instrument_id").equals(ids.get(index))
                    || !row.get("timestamp_utc").equals(first.get("timestamp_utc"))
                    || !row.get("phase").equals(first.get("phase"))) {
                throw new IOException("Incomplete, duplicated, or unordered portfolio snapshot instruments");
            }
            for (String field : TOTAL_FIELDS) {
                if (!row.get(field).equals(first.get(field))) throw new IOException("Repeated portfolio totals disagree");
            }
            long units;
            try {
                units = Long.parseLong(row.get("signed_units"));
                if (units == Long.MIN_VALUE || !Long.toString(units).equals(row.get("signed_units"))) {
                    throw new NumberFormatException("noncanonical");
                }
            } catch (NumberFormatException invalid) { throw new IOException("Invalid portfolio signed_units", invalid); }
            flat &= units == 0;
            if (included) {
                BigDecimal instrumentFees = decimal(row, "fees_inr");
                BigDecimal instrumentUnrealized = decimal(row, "unrealized_pnl_inr");
                BigDecimal averagePrice = decimal(row, "average_open_price");
                if (instrumentFees.signum() < 0 || averagePrice.signum() < 0
                        || (units == 0 && (instrumentUnrealized.signum() != 0 || averagePrice.signum() != 0))) {
                    throw new IOException("Invalid portfolio position/fee snapshot");
                }
                realized = realized.add(decimal(row, "realized_gross_pnl_inr"));
                fees = fees.add(instrumentFees);
                unrealized = unrealized.add(instrumentUnrealized);
            }
        }
        if (!included) return new Snapshot(timestamp, first.get("phase"), date, flat, null, null, null, null);
        BigDecimal net = decimal(first, "net_pnl_inr");
        if (realized.compareTo(decimal(first, "total_realized_gross_pnl_inr")) != 0
                || fees.compareTo(decimal(first, "total_fees_inr")) != 0
                || unrealized.compareTo(decimal(first, "total_unrealized_pnl_inr")) != 0
                || realized.add(unrealized).subtract(fees).compareTo(net) != 0) {
            throw new IOException("Portfolio aggregate equity totals do not reconcile");
        }
        return new Snapshot(timestamp, first.get("phase"), date, flat, realized, fees, unrealized, net);
    }

    private static BigDecimal decimal(Map<String, String> row, String field) throws IOException {
        String value = row.get(field);
        if (value == null || value.length() > 256
                || !value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")) {
            throw new IOException("Invalid portfolio decimal: " + field);
        }
        return new BigDecimal(value);
    }

    private static int positiveInteger(String value, String label) throws IOException {
        try {
            int number = Integer.parseInt(value);
            if (number <= 0 || !Integer.toString(number).equals(value)) throw new NumberFormatException("noncanonical");
            return number;
        } catch (NumberFormatException invalid) { throw new IOException("Invalid portfolio " + label, invalid); }
    }
}
