package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.provenance.RunConfig;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/** Writes and atomically publishes a content-addressed run folder with input and output hashes. */
public final class RunArtifactWriter {
    public record WrittenRun(String runId, Path path) { }

    public WrittenRun write(Path outputRoot, String runId, ValidatedDataset data,
            RunConfig config, BacktestResult result) throws IOException {
        Path outputRootAbsolute = outputRoot.toAbsolutePath().normalize();
        if (!runId.matches("[0-9a-f]{24}")) throw new IllegalArgumentException("runId must be a 24-character lowercase hex content ID");
        Files.createDirectories(outputRootAbsolute);
        cleanupAbandonedStaging(outputRootAbsolute);
        Path runFolder = outputRootAbsolute.resolve(runId);
        if (Files.exists(runFolder)) throw new FileAlreadyExistsException(runFolder.toString());
        Path stagingFolder = outputRootAbsolute.resolve("tmp-" + runId + "-" + UUID.randomUUID());
        Path stagingLockPath = outputRootAbsolute.resolve(".staging-lock-" + stagingFolder.getFileName());
        try (FileChannel lockChannel = FileChannel.open(stagingLockPath,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             FileLock stagingLock = requireStagingLock(lockChannel, stagingFolder)) {
            Files.createDirectory(stagingFolder);
            try {

                writeNew(stagingFolder.resolve("signals.csv"), csv(
                        "bar_number,decision_timestamp,session_date,instrument_id,target_lots,status,metrics_json",
                        result.signals().stream().map(signal -> row(signal.barNumber(), signal.decisionTimestamp(),
                                signal.sessionDate(), signal.instrumentId(), signal.targetLots(), signal.status(),
                                inlineMapJson(signal.metrics()))).toList()));
                writeNew(stagingFolder.resolve("orders.csv"), csv(
                        "order_id,decision_timestamp,submit_timestamp,fill_timestamp,side,target_lots,requested_units,order_type,status,reason",
                        result.orders().stream().map(order -> row(order.orderId(), order.decisionTimestamp(), order.submitTimestamp(),
                            order.fillTimestamp(), order.side(), order.targetLots(), order.requestedUnits(), order.orderType(),
                            order.status(), order.reason())).toList()));
                writeNew(stagingFolder.resolve("order-events.csv"), csv(
                        "timestamp,order_id,event,detail",
                        result.orderEvents().stream().map(event -> row(event.timestamp(), event.orderId(), event.event(), event.detail())).toList()));
                writeNew(stagingFolder.resolve("fills.csv"), csv(
                        "fill_id,order_id,timestamp,side,quantity_units,reference_open_price,fill_price,modeled_slippage_inr,fee_inr,margin_per_lot_inr,margin_source_reference,position_units_after",
                        result.fills().stream().map(fill -> row(fill.fillId(), fill.orderId(), fill.timestamp(), fill.side(),
                            fill.quantityUnits(), fill.referenceOpenPrice(), fill.fillPrice(), fill.slippageCostInr(),
                            fill.feeInr(), fill.marginPerLotInr(), fill.marginSourceReference(),
                            fill.positionUnitsAfter())).toList()));
                writeNew(stagingFolder.resolve("fee-breakdowns.csv"), csv(
                        "fill_id,cost_model,contract_kind,traded_value_inr,legacy_fee_inr,brokerage_inr,exchange_transaction_inr,ipft_contribution_inr,sebi_turnover_inr,stt_inr,stamp_duty_inr,gst_taxable_base_inr,gst_inr,total_fee_inr",
                        result.feeBreakdowns().stream().map(fee -> row(fee.fillId(), fee.costModel(), fee.contractKind(),
                                fee.tradedValueInr(), fee.legacyFeeInr(), fee.brokerageInr(), fee.exchangeTransactionInr(),
                                fee.ipftContributionInr(), fee.sebiTurnoverInr(), fee.sttInr(), fee.stampDutyInr(),
                                fee.gstTaxableBaseInr(), fee.gstInr(), fee.totalInr())).toList()));
                writeNew(stagingFolder.resolve("trades.csv"), csv(
                        "trade_id,instrument_id,entry_timestamp,exit_timestamp,quantity_units,entry_price,exit_price,gross_pnl_inr,fees_inr,net_pnl_inr",
                        result.trades().stream().map(trade -> row(trade.tradeId(), trade.instrumentId(), trade.entryTimestamp(),
                            trade.exitTimestamp(), trade.quantityUnits(), trade.entryPrice(), trade.exitPrice(),
                            trade.grossPnlInr(), trade.feesInr(), trade.netPnlInr())).toList()));
                writeNew(stagingFolder.resolve("equity.csv"), csv(
                        "timestamp,session_date,close,position_units,realized_pnl_inr,unrealized_pnl_inr,fees_paid_inr,net_pnl_inr,risk_state",
                        result.equityCurve().stream().map(point -> row(point.timestamp(), point.sessionDate(), point.close(),
                            point.positionUnits(), point.realizedPnlInr(), point.unrealizedPnlInr(), point.feesPaidInr(),
                            point.netPnlInr(), point.riskState())).toList()));
                writeNew(stagingFolder.resolve("sessions.csv"), csv(
                        "session_date,expected_bars,observed_bars,complete,position_units_at_start,position_units_at_end,realized_pnl_change_inr,unrealized_pnl_change_inr,fees_paid_inr,modeled_slippage_inr,net_pnl_change_inr,orders_created,fills,completed_trades,risk_halts,ending_risk_state",
                        result.sessionAudits().stream().map(session -> row(session.sessionDate(), session.expectedBars(),
                            session.observedBars(), session.complete(), session.positionUnitsAtStart(), session.positionUnitsAtEnd(),
                            session.realizedPnlChangeInr(), session.unrealizedPnlChangeInr(), session.feesPaidInr(),
                            session.modeledSlippageInr(), session.netPnlChangeInr(),
                            session.ordersCreated(), session.fills(), session.completedTrades(), session.riskHalts(),
                            session.endingRiskState())).toList()));
                writeNew(stagingFolder.resolve("settlements.csv"), csv(
                        "instrument_id,settlement_date,settlement_timestamp,settlement_kind,settlement_value,exit_value_per_unit,position_units_settled,gross_pnl_inr,settlement_fees_inr,source_reference",
                        result.settlements().stream().map(settlement -> row(settlement.instrumentId(),
                                settlement.settlementDate(), settlement.settlementTimestamp(), settlement.kind(),
                                settlement.settlementValue(), settlement.exitValuePerUnit(), settlement.positionUnitsSettled(),
                                settlement.grossPnlInr(), settlement.settlementFeesInr(), settlement.sourceReference())).toList()));
                writeNew(stagingFolder.resolve("settlement-charges.csv"), csv(
                        "settlement_timestamp,model,contract_kind,position_units,option_intrinsic_per_unit_inr,exercise_stt_base_inr,sebi_exercise_turnover_base_inr,sebi_turnover_inr,exercise_stt_inr,gst_taxable_base_inr,gst_inr,total_inr",
                        result.settlementChargeBreakdowns().stream().map(charge -> row(charge.settlementTimestamp(),
                                charge.model(), charge.contractKind(), charge.positionUnits(),
                                charge.optionIntrinsicPerUnitInr(), charge.exerciseSttBaseInr(),
                                charge.sebiExerciseTurnoverBaseInr(), charge.sebiTurnoverInr(),
                                charge.exerciseSttInr(), charge.gstTaxableBaseInr(), charge.gstInr(),
                                charge.totalInr())).toList()));

                writeNew(stagingFolder.resolve("summary.json"), summaryJson(runId, data, config, result));
                Map<String, String> outputHashes = new TreeMap<>();
                for (String name : List.of("signals.csv", "orders.csv", "order-events.csv", "fills.csv", "fee-breakdowns.csv",
                        "trades.csv", "equity.csv", "sessions.csv", "settlements.csv", "settlement-charges.csv", "summary.json")) {
                    outputHashes.put(name, RunConfig.sha256(Files.readAllBytes(stagingFolder.resolve(name))));
                }
                writeNew(stagingFolder.resolve("run-manifest.json"), manifestJson(runId, data, config, result, outputHashes));
                Files.move(stagingFolder, runFolder, StandardCopyOption.ATOMIC_MOVE);
                return new WrittenRun(runId, runFolder);
            } catch (IOException | RuntimeException failure) {
                try {
                    removeStaging(stagingFolder);
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        } finally {
            if (!Files.exists(stagingFolder, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.deleteIfExists(stagingLockPath);
                } catch (IOException ignored) {
                    // A leftover sidecar is harmless; any extant staging directory remains lock-checked.
                }
            }
        }
    }

    private static FileLock requireStagingLock(FileChannel channel, Path stagingFolder) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Run staging folder is already locked: " + stagingFolder);
            return lock;
        } catch (OverlappingFileLockException lockedInThisProcess) {
            throw new IOException("Run staging folder is already locked: " + stagingFolder, lockedInThisProcess);
        }
    }

    /** Removes only recognized staging directories whose sidecar lock is available. */
    private static void cleanupAbandonedStaging(Path outputRoot) throws IOException {
        List<Path> candidates;
        try (Stream<Path> entries = Files.list(outputRoot)) {
            candidates = entries.filter(RunArtifactWriter::isRecognizedStagingFolder).toList();
        }
        for (Path stagingFolder : candidates) {
            if (!stagingFolder.getParent().equals(outputRoot)
                    || !Files.isDirectory(stagingFolder, LinkOption.NOFOLLOW_LINKS)) continue;
            Path lockPath = outputRoot.resolve(".staging-lock-" + stagingFolder.getFileName());
            if (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) continue;
            boolean removed = false;
            try (FileChannel lockChannel = FileChannel.open(lockPath,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                FileLock stagingLock;
                try {
                    stagingLock = lockChannel.tryLock();
                } catch (OverlappingFileLockException activeInThisProcess) {
                    continue;
                }
                if (stagingLock == null) continue;
                try (stagingLock) {
                    if (Files.isDirectory(stagingFolder, LinkOption.NOFOLLOW_LINKS)) {
                        removeStaging(stagingFolder);
                        removed = true;
                    }
                }
            }
            if (removed) {
                try {
                    Files.deleteIfExists(lockPath);
                } catch (IOException ignored) {
                    // Keep the harmless lock marker if the filesystem cannot remove it now.
                }
            }
        }
    }

    private static boolean isRecognizedStagingFolder(Path path) {
        String name = path.getFileName().toString();
        return name.matches("tmp-[0-9a-f]{24}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static void removeStaging(Path stagingFolder) throws IOException {
        if (!Files.exists(stagingFolder, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> entries = Files.walk(stagingFolder)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
        }
    }

    private static String summaryJson(String runId, ValidatedDataset data, RunConfig config, BacktestResult result) {
        DiagnosticMetrics metrics = DiagnosticMetrics.from(result);
        return "{\n"
                + "  \"run_id\": " + q(runId) + ",\n"
                + "  \"result_classification\": " + q(result.resultClassification()) + ",\n"
                + "  \"performance_claim_eligible\": false,\n"
                + "  \"bars\": " + data.bars().size() + ",\n"
                + "  \"signals\": " + result.signals().size() + ",\n"
                + "  \"timestamp_spacing_anomalies\": " + data.timestampSpacingAnomalies() + ",\n"
                + "  \"estimated_missing_intervals\": " + data.estimatedMissingIntervals() + ",\n"
                + "  \"expected_bars_from_calendar\": " + data.expectedBarCount() + ",\n"
                + "  \"open_sessions\": " + data.openSessionCount() + ",\n"
                + "  \"complete_open_sessions\": " + data.completeSessionCount() + ",\n"
                + "  \"incomplete_open_sessions\": " + (data.openSessionCount() - data.completeSessionCount()) + ",\n"
                + "  \"orders\": " + result.orders().size() + ",\n"
                + "  \"fills\": " + result.fills().size() + ",\n"
                + "  \"fee_breakdowns\": " + result.feeBreakdowns().size() + ",\n"
                + "  \"completed_trades\": " + result.trades().size() + ",\n"
                + "  \"expiry_settlements\": " + result.settlements().size() + ",\n"
                + "  \"settlement_charge_breakdowns\": " + result.settlementChargeBreakdowns().size() + ",\n"
                + "  \"settlement_charges_paid_inr\": " + q(num(result.settlementChargeBreakdowns().stream()
                        .map(SimSettlementChargeBreakdown::totalInr).reduce(BigDecimal.ZERO, BigDecimal::add))) + ",\n"
                + "  \"filled_units\": " + metrics.filledUnits() + ",\n"
                + "  \"filled_turnover_inr\": " + q(num(metrics.filledTurnoverInr())) + ",\n"
                + "  \"completed_trade_diagnostics\": {\"wins\": " + metrics.winningTrades()
                + ", \"losses\": " + metrics.losingTrades() + ", \"breakeven\": " + metrics.breakevenTrades()
                + ", \"net_pnl_inr\": " + q(num(metrics.completedTradeNetPnlInr()))
                + ", \"win_rate\": " + optionalNum(metrics.winRate())
                + ", \"average_net_pnl_inr\": " + optionalNum(metrics.averageNetPnlPerTradeInr())
                + ", \"net_profit_factor\": " + optionalNum(metrics.netProfitFactor())
                + ", \"average_holding_seconds\": " + optionalNum(metrics.averageHoldingSeconds()) + "},\n"
                + "  \"risk_halts\": " + result.riskHalts() + ",\n"
                + "  \"final_position_units\": " + result.finalPositionUnits() + ",\n"
                + "  \"realized_pnl_inr\": " + q(num(result.realizedPnlInr())) + ",\n"
                + "  \"unrealized_pnl_inr\": " + q(num(result.unrealizedPnlInr())) + ",\n"
                + "  \"fees_paid_inr\": " + q(num(result.feesPaidInr())) + ",\n"
                + "  \"modeled_slippage_inr\": " + q(num(result.modeledSlippageInr())) + ",\n"
                + "  \"net_pnl_inr\": " + q(num(result.netPnlInr())) + ",\n"
                + "  \"max_drawdown_inr\": " + q(num(result.maxDrawdownInr())) + ",\n"
                + "  \"dataset_id\": " + q(config.datasetId()) + ",\n"
                + "  \"data_rights_status\": " + q(config.dataRightsStatus().name()) + ",\n"
                + "  \"strategy_id\": " + q(result.strategyId()) + ",\n"
                + "  \"cost_status\": " + q(config.costStatus()) + "\n"
                + "}\n";
    }

    private static String manifestJson(String runId, ValidatedDataset data, RunConfig config,
            BacktestResult result, Map<String, String> outputHashes) {
        var bars = data.bars();
        var first = bars.getFirst();
        var last = bars.getLast();
        Map<String, String> inputHashes = new TreeMap<>();
        inputHashes.put("bars", data.barsSha256());
        inputHashes.put("configuration", data.configSha256());
        inputHashes.put("instrument_master", data.instrumentMaster().rawFileSha256());
        inputHashes.put("session_calendar", data.calendarSha256());
        data.costSchedule().ifPresent(schedule -> inputHashes.put("cost_schedule", schedule.sha256()));
        data.componentCostSchedule().ifPresent(schedule -> inputHashes.put("component_cost_schedule", schedule.sha256()));
        data.marginRequirementSchedule().ifPresent(schedule -> inputHashes.put("margin_requirement_schedule", schedule.sha256()));
        data.expirySettlement().ifPresent(settlement -> inputHashes.put("expiry_settlement", settlement.sha256()));
        return "{\n"
                + "  \"schema_version\": 6,\n"
                + "  \"run_id\": " + q(runId) + ",\n"
                + "  \"run_commit_id\": " + q(config.commitId()) + ",\n"
                + "  \"dataset_id\": " + q(config.datasetId()) + ",\n"
                + "  \"dataset_source\": " + q(config.datasetSourceName()) + ",\n"
                + "  \"dataset_acquired_at_utc\": " + q(config.datasetAcquiredAt().toString()) + ",\n"
                + "  \"data_rights_status\": " + q(config.dataRightsStatus().name()) + ",\n"
                + "  \"data_rights_reference\": " + optionalJson(config.dataRightsReference()) + ",\n"
                + "  \"instrument_id\": " + q(first.instrumentId()) + ",\n"
                + "  \"trading_symbol\": " + q(first.tradingSymbol()) + ",\n"
                + "  \"contract_kind\": " + q(first.contractKind().name()) + ",\n"
                + "  \"expiry\": " + q(first.expiry().toString()) + ",\n"
                + "  \"strike\": " + first.strike().map(value -> q(num(value))).orElse("null") + ",\n"
                + "  \"option_right\": " + first.optionRight().map(Enum::name).map(RunArtifactWriter::q).orElse("null") + ",\n"
                + "  \"instrument_master_snapshot_id\": " + q(data.instrumentMaster().snapshotId()) + ",\n"
                + "  \"instrument_master_sha256\": " + q(data.instrumentMaster().rawFileSha256()) + ",\n"
                + "  \"session_calendar_sha256\": " + q(data.calendarSha256()) + ",\n"
                + "  \"timezone\": " + q(config.timezone()) + ",\n"
                + "  \"timestamp_role\": " + q(config.timestampRole().name()) + ",\n"
                + "  \"interval\": " + q(config.interval()) + ",\n"
                + "  \"intra_session_data_quality\": {\"timestamp_spacing_anomalies\": "
                + data.timestampSpacingAnomalies() + ", \"estimated_missing_intervals\": "
                + data.estimatedMissingIntervals() + ", \"expected_bars_from_calendar\": "
                + data.expectedBarCount() + ", \"open_sessions\": " + data.openSessionCount()
                + ", \"complete_open_sessions\": " + data.completeSessionCount()
                + ", \"max_missing_intervals_per_session\": " + config.maxMissingIntervalsPerSession()
                + ", \"gaps_allowed_by_config\": " + config.allowIntraSessionGaps()
                + ", \"session_completeness_status\": "
                + q(data.completeSessionCount() == data.openSessionCount() ? "COMPLETE_FOR_SUPPLIED_WINDOWS" : "INCOMPLETE_ALLOWED_DIAGNOSTIC")
                + "},\n"
                + "  \"date_range\": {\"start\": " + q(bars.getFirst().sessionDate().toString())
                + ", \"end\": " + q(bars.getLast().sessionDate().toString()) + "},\n"
                + "  \"bar_timestamp_range\": {\"start\": " + q(bars.getFirst().timestamp().toString())
                + ", \"end\": " + q(bars.getLast().timestamp().toString()) + "},\n"
                + "  \"strategy\": {\"id\": " + q(result.strategyId()) + ", \"parameters\": "
                + mapJson(result.strategyParameters()) + "},\n"
                + "  \"fill_model\": \"NEXT_BAR_OPEN_MARKET_WITH_FIXED_ADVERSE_TICK_SLIPPAGE_V1\",\n"
                + "  \"passive_orders_supported\": false,\n"
                + "  \"partial_fills_supported\": false,\n"
                + "  \"cost_model\": {\"status\": " + q(config.costStatus()) + ", \"evidence_reference\": "
                + optionalJson(config.costEvidenceReference()) + ", \"slippage_ticks\": " + config.slippageTicks()
                + ", \"slippage_ticks_source\": " + q(data.componentCostSchedule().isPresent()
                        ? "component_cost_schedule" : "legacy_inputs_or_schedule")
                + ", \"fee_per_order_inr\": " + q(num(config.feePerOrderInr()))
                + ", \"fee_per_unit_inr\": " + q(num(config.feePerUnitInr()))
                + ", \"notional_fee_bps\": " + q(num(config.notionalFeeBps()))
                + ", \"notional_fee_rounding\": \"HALF_UP_TO_PAISE_PER_FILL\""
                + (data.costSchedule().isPresent() ? ", \"dated_schedule_sha256\": "
                        + q(data.costSchedule().orElseThrow().sha256()) : "")
                + (data.componentCostSchedule().isPresent() ? ", \"component_schedule_sha256\": "
                        + q(data.componentCostSchedule().orElseThrow().sha256())
                        + ", \"fee_breakdown_file\": \"fee-breakdowns.csv\", \"component_rounding\": \"HALF_UP_TO_PAISE_PER_COMPONENT_PER_FILL\"" : "")
                + "},\n"
                + "  \"risk_limits\": {\"max_position_lots\": " + config.maxPositionLots()
                + ", \"max_notional_inr\": " + q(num(config.maxNotionalInr()))
                + ", \"margin_per_lot_inr\": " + q(num(config.marginPerLotInr()))
                + ", \"margin_requirement_source\": " + q(data.marginRequirementSchedule().isPresent()
                        ? "CALLER_SUPPLIED_DATED_SCHEDULE" : "FIXED_USER_SUPPLIED_PROXY")
                + (data.marginRequirementSchedule().isPresent() ? ", \"margin_requirement_schedule_sha256\": "
                        + q(data.marginRequirementSchedule().orElseThrow().sha256()) : "")
                + ", \"max_margin_inr\": " + q(num(config.maxMarginInr()))
                + ", \"max_daily_loss_inr\": " + q(num(config.maxDailyLossInr()))
                + ", \"min_seconds_between_entry_orders\": " + config.minSecondsBetweenEntryOrders()
                + ", \"allow_next_session_orders\": " + config.allowNextSessionOrders()
                + ", \"cross_session_position_policy\": " + q(config.crossSessionPositionPolicy().name()) + "},\n"
                + "  \"expiry_settlement\": " + data.expirySettlement().map(settlement ->
                        "{\"date\": " + q(settlement.settlementDate().toString())
                        + ", \"timestamp\": " + q(settlement.settlementTimestamp().toString())
                        + ", \"kind\": " + q(settlement.kind().name())
                        + ", \"value\": " + q(num(settlement.settlementValue()))
                        + ", \"source_reference\": " + q(settlement.sourceReference())
                        + (settlement.sebiExerciseTurnoverValuePerUnitInr().isPresent()
                                ? ", \"sebi_exercise_turnover_value_per_unit_inr\": "
                                        + q(num(settlement.sebiExerciseTurnoverValuePerUnitInr().orElseThrow())) : "")
                        + ", \"sha256\": " + q(settlement.sha256()) + "}").orElse("null") + ",\n"
                + "  \"settlement_charges_modeled\": false,\n"
                + "  \"option_exercise_stt_sebi_modeled\": "
                + (data.componentCostSchedule().isPresent() && first.contractKind() == com.mscient.intrapaper.reference.ContractKind.OPTION
                        && data.expirySettlement().isPresent()) + ",\n"
                + "  \"margin_model\": " + q(data.marginRequirementSchedule().isPresent()
                        ? "CALLER_SUPPLIED_DATED_REQUIREMENT_NOT_SPAN_RECOMPUTATION"
                        : "FIXED_USER_SUPPLIED_PROXY_NOT_EXCHANGE_MARGIN") + ",\n"
                + "  \"synthetic_fixture\": " + config.syntheticFixture() + ",\n"
                + "  \"result_classification\": " + q(result.resultClassification()) + ",\n"
                + "  \"performance_claim_eligible\": false,\n"
                + "  \"input_file_sha256\": " + mapJson(inputHashes) + ",\n"
                + "  \"output_file_sha256\": " + mapJson(outputHashes) + "\n"
                + "}\n";
    }

    private static String mapJson(Map<String, String> values) {
        List<String> items = new ArrayList<>();
        new TreeMap<>(values).forEach((key, value) -> items.add("    " + q(key) + ": " + q(value)));
        if (items.isEmpty()) return "{}";
        return "{\n" + String.join(",\n", items) + "\n  }";
    }

    private static String inlineMapJson(Map<String, String> values) {
        List<String> items = new ArrayList<>();
        new TreeMap<>(values).forEach((key, value) -> items.add(q(key) + ": " + q(value)));
        return "{" + String.join(", ", items) + "}";
    }

    private static String csv(String header, List<String> rows) {
        return header + "\n" + (rows.isEmpty() ? "" : String.join("\n", rows) + "\n");
    }

    private static String row(Object... fields) {
        List<String> values = new ArrayList<>();
        for (Object field : fields) values.add(CsvTable.escape(field == null ? "" : field.toString()));
        return String.join(",", values);
    }

    private static String optionalJson(java.util.Optional<String> value) {
        return value.map(RunArtifactWriter::q).orElse("null");
    }

    private static String optionalNum(java.util.Optional<BigDecimal> value) {
        return value.map(RunArtifactWriter::num).map(RunArtifactWriter::q).orElse("null");
    }

    private static String num(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String q(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static void writeNew(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
