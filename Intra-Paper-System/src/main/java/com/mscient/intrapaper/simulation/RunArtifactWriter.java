package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.evaluation.EvaluationPartitionReport;
import com.mscient.intrapaper.evaluation.ResearchTrialBinding;
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
        var researchTrial = ResearchTrialBinding.resolve(config, data, result.strategyId());
        var evaluationPartitions = EvaluationPartitionReport.create(config, data, result.sessionAudits());
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
                        "fill_id,order_id,timestamp,side,quantity_units,reference_open_price,fill_price,modeled_slippage_inr,fee_inr,margin_per_lot_inr,margin_source_reference,position_units_after,execution_model,quote_source,quote_sequence,quote_received_at_utc,reference_execution_price",
                        result.fills().stream().map(fill -> row(fill.fillId(), fill.orderId(), fill.timestamp(), fill.side(),
                            fill.quantityUnits(), fill.referenceOpenPrice(), fill.fillPrice(), fill.slippageCostInr(),
                            fill.feeInr(), fill.marginPerLotInr(), fill.marginSourceReference(),
                            fill.positionUnitsAfter(), fill.executionModel(), fill.quoteSource() == null ? "" : fill.quoteSource(),
                            fill.quoteSequence() == 0 ? "" : fill.quoteSequence(),
                            fill.quoteReceivedAt() == null ? "" : fill.quoteReceivedAt().toString(),
                            fill.referenceExecutionPrice())).toList()));
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
                        "session_date,market_rule_regime_id,regime_source_reference,expected_bars,observed_bars,complete,position_units_at_start,position_units_at_end,realized_pnl_change_inr,unrealized_pnl_change_inr,fees_paid_inr,modeled_slippage_inr,net_pnl_change_inr,orders_created,fills,completed_trades,risk_halts,ending_risk_state",
                        result.sessionAudits().stream().map(session -> {
                            var calendarEntry = data.sessionCalendar().get(session.sessionDate());
                            return row(session.sessionDate(), calendarEntry.marketRuleRegimeId(),
                            calendarEntry.regimeSourceReference(), session.expectedBars(),
                            session.observedBars(), session.complete(), session.positionUnitsAtStart(), session.positionUnitsAtEnd(),
                            session.realizedPnlChangeInr(), session.unrealizedPnlChangeInr(), session.feesPaidInr(),
                            session.modeledSlippageInr(), session.netPnlChangeInr(),
                            session.ordersCreated(), session.fills(), session.completedTrades(), session.riskHalts(),
                            session.endingRiskState());
                        }).toList()));
                writeNew(stagingFolder.resolve("settlements.csv"), csv(
                        "instrument_id,settlement_date,settlement_timestamp,settlement_kind,settlement_value,exit_value_per_unit,position_units_settled,gross_pnl_inr,settlement_fees_inr,source_reference",
                        result.settlements().stream().map(settlement -> row(settlement.instrumentId(),
                                settlement.settlementDate(), settlement.settlementTimestamp(), settlement.kind(),
                                settlement.settlementValue(), settlement.exitValuePerUnit(), settlement.positionUnitsSettled(),
                                settlement.grossPnlInr(), settlement.settlementFeesInr(), settlement.sourceReference())).toList()));
                writeNew(stagingFolder.resolve("settlement-charges.csv"), csv(
                        "settlement_timestamp,model,contract_kind,position_units,option_intrinsic_per_unit_inr,exercise_stt_base_inr,sebi_exercise_turnover_base_inr,sebi_turnover_inr,exercise_stt_inr,gst_taxable_base_inr,gst_inr,additional_settlement_fees_inr,total_inr",
                        result.settlementChargeBreakdowns().stream().map(charge -> row(charge.settlementTimestamp(),
                                charge.model(), charge.contractKind(), charge.positionUnits(),
                                charge.optionIntrinsicPerUnitInr(), charge.exerciseSttBaseInr(),
                                charge.sebiExerciseTurnoverBaseInr(), charge.sebiTurnoverInr(),
                                charge.exerciseSttInr(), charge.gstTaxableBaseInr(), charge.gstInr(),
                                charge.additionalSettlementFeesInr(), charge.totalInr())).toList()));
                writeNew(stagingFolder.resolve("settlement-cost-lines.csv"), csv(
                        "settlement_timestamp,component_code,basis,calculation_base,rate,charge_inr,gst_bps,gst_taxable_base_inr,gst_inr,total_inr,source_reference",
                        result.settlementCostLines().stream().map(line -> row(line.settlementTimestamp(),
                                line.componentCode(), line.basis(), line.calculationBase(), line.rate(),
                                line.chargeInr(), line.gstBps(), line.gstTaxableBaseInr(), line.gstInr(),
                                line.totalInr(), line.sourceReference())).toList()));
                if (!result.optionValuations().isEmpty()) {
                    writeNew(stagingFolder.resolve("option-valuation.csv"), csv(
                            "timestamp,instrument_id,model,source_reference,underlying_price,time_to_expiry_years,risk_free_rate,dividend_yield,annualized_volatility,market_premium_close,theoretical_value,market_minus_model_premium,delta,gamma,vega_per_volatility_point,theta_per_calendar_day,rho_per_rate_point,position_units_at_close,position_market_value,position_theoretical_value,position_market_minus_model_value,position_delta_equivalent_units,position_gamma,position_vega_per_volatility_point,position_theta_per_calendar_day,position_rho_per_rate_point",
                            result.optionValuations().stream().map(value -> row(value.timestamp(), value.instrumentId(),
                                    value.model(), value.sourceReference(), value.underlyingPrice(),
                                    value.timeToExpiryYears(), value.riskFreeRate(), value.dividendYield(),
                                    value.annualizedVolatility(), value.marketPremiumClose(), value.theoreticalValue(),
                                    value.marketMinusModelPremium(), value.delta(), value.gamma(),
                                    value.vegaPerVolatilityPoint(), value.thetaPerCalendarDay(),
                                    value.rhoPerRatePoint(), value.positionUnitsAtClose(), value.positionMarketValue(),
                                    value.positionTheoreticalValue(), value.positionMarketMinusModelValue(),
                                    value.positionDeltaEquivalentUnits(), value.positionGamma(),
                                    value.positionVegaPerVolatilityPoint(), value.positionThetaPerCalendarDay(),
                                    value.positionRhoPerRatePoint())).toList()));
                }
                writeNew(stagingFolder.resolve("evaluation-partitions.csv"), evaluationPartitionsCsv(evaluationPartitions));

                writeNew(stagingFolder.resolve("summary.json"), summaryJson(runId, data, config, result, evaluationPartitions));
                Map<String, String> outputHashes = new TreeMap<>();
                List<String> outputNames = new ArrayList<>(List.of("signals.csv", "orders.csv", "order-events.csv", "fills.csv", "fee-breakdowns.csv",
                        "trades.csv", "equity.csv", "sessions.csv", "settlements.csv", "settlement-charges.csv",
                        "settlement-cost-lines.csv", "evaluation-partitions.csv", "summary.json"));
                if (!result.optionValuations().isEmpty()) outputNames.add("option-valuation.csv");
                for (String name : outputNames) {
                    outputHashes.put(name, RunConfig.sha256(Files.readAllBytes(stagingFolder.resolve(name))));
                }
                writeNew(stagingFolder.resolve("run-manifest.json"),
                        manifestJson(runId, data, config, result, outputHashes, researchTrial, evaluationPartitions));
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

    private static String summaryJson(String runId, ValidatedDataset data, RunConfig config, BacktestResult result,
            java.util.Optional<EvaluationPartitionReport> evaluationPartitions) {
        DiagnosticMetrics metrics = DiagnosticMetrics.from(result);
        return "{\n"
                + "  \"run_id\": " + q(runId) + ",\n"
                + "  \"result_classification\": " + q(result.resultClassification()) + ",\n"
                + "  \"option_valuation\": " + data.optionValuationInputs().map(schedule ->
                        "{\"input_sha256\": " + q(schedule.sha256())
                                + ", \"output_file\": \"option-valuation.csv\", \"used_in_execution_or_pnl\": false}")
                        .orElse("null") + ",\n"
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
                + "  \"additional_settlement_cost_lines\": " + result.settlementCostLines().size() + ",\n"
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
                + "  \"evaluation_partition_report\": " + evaluationPartitionReportJson(evaluationPartitions) + ",\n"
                + "  \"cost_status\": " + q(config.costStatus()) + "\n"
                + "}\n";
    }

    private static String manifestJson(String runId, ValidatedDataset data, RunConfig config,
            BacktestResult result, Map<String, String> outputHashes,
            java.util.Optional<ResearchTrialBinding.Evidence> researchTrial,
            java.util.Optional<EvaluationPartitionReport> evaluationPartitions) {
        var bars = data.bars();
        var first = bars.getFirst();
        var last = bars.getLast();
        Map<String, String> inputHashes = new TreeMap<>();
        inputHashes.put("bars", data.barsSha256());
        inputHashes.put("configuration", data.configSha256());
        inputHashes.put("instrument_master", data.instrumentMaster().rawFileSha256());
        inputHashes.put("session_calendar", data.calendarSha256());
        inputHashes.put("market_rule_regimes", data.marketRuleRegimeSchedule().sha256());
        data.costSchedule().ifPresent(schedule -> inputHashes.put("cost_schedule", schedule.sha256()));
        data.componentCostSchedule().ifPresent(schedule -> inputHashes.put("component_cost_schedule", schedule.sha256()));
        data.marginRequirementSchedule().ifPresent(schedule -> inputHashes.put("margin_requirement_schedule", schedule.sha256()));
        data.expirySettlement().ifPresent(settlement -> inputHashes.put("expiry_settlement", settlement.sha256()));
        data.settlementCostSchedule().ifPresent(schedule -> inputHashes.put("settlement_cost_schedule", schedule.sha256()));
        data.quoteHistory().ifPresent(history -> inputHashes.put("quote_journal", history.fileSha256()));
        data.optionValuationInputs().ifPresent(schedule -> inputHashes.put("option_valuation_inputs", schedule.sha256()));
        evaluationPartitions.ifPresent(report -> inputHashes.put("evaluation_split_plan", report.splitPlanSha256()));
        return "{\n"
                + "  \"schema_version\": 14,\n"
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
                + "  \"market_rule_regime_schedule_sha256\": " + q(data.marketRuleRegimeSchedule().sha256()) + ",\n"
                + "  \"market_rule_regimes\": " + marketRuleRegimesJson(data, first.sessionDate(), last.sessionDate()) + ",\n"
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
                + "  \"research_trial\": " + researchTrialJson(researchTrial) + ",\n"
                + "  \"evaluation_partition_report\": " + evaluationPartitionReportJson(evaluationPartitions) + ",\n"
                + "  \"fill_model\": " + q(switch (config.executionModel()) {
                    case BAR_NEXT_OPEN -> "NEXT_BAR_OPEN_MARKET_WITH_FIXED_ADVERSE_TICK_SLIPPAGE_V1";
                    case L1_FULL_SIZE_IOC -> "L1_TOP_OF_BOOK_FULL_SIZE_IOC_V1";
                    case L1_PARTIAL_IOC -> "L1_TOP_OF_BOOK_WHOLE_LOT_PARTIAL_IOC_V1";
                }) + ",\n"
                + "  \"execution_model\": {\"id\": " + q(config.executionModel().name())
                + ", \"quote_journal_sha256\": " + data.quoteHistory()
                        .map(history -> q(history.fileSha256())).orElse("null")
                + ", \"quote_source_name\": " + optionalJson(config.quoteSourceName())
                + ", \"decision_to_arrival_latency_ms\": " + config.decisionToArrivalLatency().toMillis()
                + ", \"quote_maximum_age_ms\": " + config.quoteMaximumAge().toMillis()
                + ", \"quote_maximum_future_skew_ms\": " + config.quoteMaximumFutureSkew().toMillis() + "},\n"
                + "  \"quote_journal\": " + data.quoteHistory().map(history ->
                        "{\"sha256\": " + q(history.fileSha256())
                                + ", \"event_count\": " + history.summary().eventCount()
                                + ", \"last_sequence_number\": " + history.summary().lastSequenceNumber()
                                + ", \"sequence_gap_count\": " + history.summary().sequenceGapCount()
                                + ", \"missing_sequence_numbers\": " + history.summary().missingSequenceNumbers()
                                + ", \"final_hash\": " + q(history.summary().finalHashHex())
                                + ", \"bytes_read\": " + history.summary().bytesRead() + "}")
                        .orElse("null") + ",\n"
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
                + ", \"max_entry_orders_per_session\": " + config.maxEntryOrdersPerSession()
                + ", \"allow_next_session_orders\": " + config.allowNextSessionOrders()
                + ", \"cross_session_position_policy\": " + q(config.crossSessionPositionPolicy().name())
                + config.optionStressRiskLimits().map(limits -> ", \"option_stress_loss_cap\": {\"max_loss_inr\": "
                        + q(num(limits.maxLossInr())) + ", \"underlying_shock_percent\": "
                        + q(num(limits.underlyingShockPercent())) + ", \"volatility_shock_points\": "
                        + q(num(limits.volatilityShockPoints())) + ", \"exit_haircut_per_unit_inr\": "
                        + q(num(limits.exitHaircutPerUnitInr())) + ", \"gate\": \"PRE_ENTRY_DECISION_TIME_FULL_REVALUATION\"}")
                        .orElse("") + "},\n"
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
                + "  \"settlement_charge_components_modeled\": " + (data.settlementCostSchedule().isPresent()
                        || (data.componentCostSchedule().isPresent()
                                && first.contractKind() == com.mscient.intrapaper.reference.ContractKind.OPTION
                                && data.expirySettlement().isPresent())) + ",\n"
                + "  \"settlement_cost_schedule\": " + data.settlementCostSchedule().map(schedule ->
                        "{\"sha256\": " + q(schedule.sha256())
                                + ", \"lines_file\": \"settlement-cost-lines.csv\", \"rate_source\": \"CALLER_SUPPLIED_EFFECTIVE_DATED\"}")
                        .orElse("null") + ",\n"
                + "  \"option_exercise_stt_sebi_modeled\": "
                + (data.componentCostSchedule().isPresent() && first.contractKind() == com.mscient.intrapaper.reference.ContractKind.OPTION
                        && data.expirySettlement().isPresent()) + ",\n"
                + "  \"margin_model\": " + q(data.marginRequirementSchedule().isPresent()
                        ? "CALLER_SUPPLIED_DATED_REQUIREMENT_NOT_SPAN_RECOMPUTATION"
                        : "FIXED_USER_SUPPLIED_PROXY_NOT_EXCHANGE_MARGIN") + ",\n"
                + "  \"recovery\": {\"checkpoint_interval_bars\": " + config.checkpointIntervalBars() + "},\n"
                + "  \"synthetic_fixture\": " + config.syntheticFixture() + ",\n"
                + "  \"result_classification\": " + q(result.resultClassification()) + ",\n"
                + "  \"performance_claim_eligible\": false,\n"
                + "  \"input_file_sha256\": " + mapJson(inputHashes) + ",\n"
                + "  \"output_file_sha256\": " + mapJson(outputHashes) + "\n"
                + "}\n";
    }

    private static String evaluationPartitionsCsv(java.util.Optional<EvaluationPartitionReport> report) {
        String header = "partition,first_session,last_session,session_count,complete_session_count,position_units_at_start,"
                + "position_units_at_end,flat_at_partition_boundaries,realized_pnl_change_inr,unrealized_pnl_change_inr,"
                + "net_pnl_change_inr,max_session_close_drawdown_inr,fees_paid_inr,modeled_slippage_inr,orders_created,"
                + "fills,completed_trades,risk_halts,status,performance_claim_eligible";
        List<String> rows = report.map(value -> value.partitions().stream().map(partition -> row(
                partition.partition(), partition.firstSession(), partition.lastSession(), partition.sessionCount(),
                partition.completeSessionCount(), partition.positionUnitsAtStart(), partition.positionUnitsAtEnd(),
                partition.flatAtPartitionBoundaries(), partition.realizedPnlChangeInr(),
                partition.unrealizedPnlChangeInr(), partition.netPnlChangeInr(),
                partition.maxSessionCloseDrawdownInr(), partition.feesPaidInr(), partition.modeledSlippageInr(),
                partition.ordersCreated(), partition.fills(), partition.completedTrades(), partition.riskHalts(),
                partition.status(), false)).toList()).orElse(List.of());
        return csv(header, rows);
    }

    private static String evaluationPartitionReportJson(java.util.Optional<EvaluationPartitionReport> report) {
        if (report.isEmpty()) return "null";
        return "{\"split_plan_sha256\": " + q(report.orElseThrow().splitPlanSha256())
                + ", \"file\": \"evaluation-partitions.csv\""
                + ", \"aggregation\": \"SESSION_NET_PNL_CHANGE_SUM\""
                + ", \"drawdown_method\": \"SESSION_CLOSE_EQUITY_ONLY\""
                + ", \"holdout_control\": \"NOT_SEALED_OR_ONE_SHOT_ENFORCED\""
                + ", \"performance_claim_eligible\": false}";
    }

    private static String mapJson(Map<String, String> values) {
        List<String> items = new ArrayList<>();
        new TreeMap<>(values).forEach((key, value) -> items.add("    " + q(key) + ": " + q(value)));
        if (items.isEmpty()) return "{}";
        return "{\n" + String.join(",\n", items) + "\n  }";
    }

    private static String researchTrialJson(java.util.Optional<ResearchTrialBinding.Evidence> evidence) {
        if (evidence.isEmpty()) return "null";
        var binding = evidence.orElseThrow();
        var trial = binding.trial();
        var entry = binding.registration();
        return "{\"trial_id\": " + q(trial.trialId())
                + ", \"strategy_id\": " + q(trial.strategyId())
                + ", \"strategy_version\": " + q(trial.strategyVersion())
                + ", \"variant_id\": " + q(trial.variantId())
                + ", \"registration_sequence\": " + entry.sequence()
                + ", \"registration_record_sha256\": " + q(entry.recordHashSha256())
                + ", \"strategy_card_sha256\": " + q(trial.strategyCardSha256())
                + ", \"dataset_sha256\": " + q(trial.datasetSha256())
                + ", \"split_plan_sha256\": " + q(trial.splitPlanSha256())
                + ", \"parameter_config_sha256\": " + q(trial.parameterConfigSha256()) + "}";
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

    private static String marketRuleRegimesJson(ValidatedDataset data,
            java.time.LocalDate firstDate, java.time.LocalDate lastDate) {
        Map<String, List<java.time.LocalDate>> sessionsByRegime = new TreeMap<>();
        data.sessionCalendar().entrySet().stream()
                .filter(entry -> entry.getValue().open()
                        && !entry.getKey().isBefore(firstDate) && !entry.getKey().isAfter(lastDate))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> sessionsByRegime.computeIfAbsent(
                        entry.getValue().marketRuleRegimeId(), ignored -> new ArrayList<>()).add(entry.getKey()));

        List<String> entries = sessionsByRegime.entrySet().stream().map(entry -> {
            var regime = data.marketRuleRegimeSchedule().regimes().get(entry.getKey());
            String sessionDates = entry.getValue().stream().map(date -> q(date.toString()))
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            return "{\"regime_id\":" + q(regime.id())
                    + ",\"session_dates\":" + sessionDates
                    + ",\"effective_from\":" + q(regime.effectiveFrom().toString())
                    + ",\"effective_through\":" + regime.effectiveThrough().map(value -> q(value.toString())).orElse("null")
                    + ",\"exchange\":" + q(regime.exchange())
                    + ",\"segment\":" + q(regime.segment())
                    + ",\"contract_kind\":" + q(regime.contractKind().name())
                    + ",\"timezone\":" + q(regime.timezone())
                    + ",\"session_open_local\":" + q(regime.sessionOpenLocal().toString())
                    + ",\"session_close_local\":" + q(regime.sessionCloseLocal().toString())
                    + ",\"preopen_enabled\":" + regime.preopenEnabled()
                    + ",\"preopen_start_local\":" + regime.preopenStartLocal().map(value -> q(value.toString())).orElse("null")
                    + ",\"preopen_end_local\":" + regime.preopenEndLocal().map(value -> q(value.toString())).orElse("null")
                    + ",\"expiry_weekday\":" + regime.expiryWeekday().map(Enum::name).map(RunArtifactWriter::q).orElse("null")
                    + ",\"holiday_expiry_shift\":" + optionalJson(regime.holidayExpiryShift())
                    + ",\"lot_size\":" + regime.lotSize()
                    + ",\"tick_size\":" + q(num(regime.tickSize()))
                    + ",\"expiry_elm_addon_percent\":" + regime.expiryElmAddonPercent().map(value -> q(num(value))).orElse("null")
                    + ",\"calendar_spread_expiry_eligible\":" + regime.calendarSpreadExpiryEligible()
                            .map(String::valueOf).orElse("null")
                    + ",\"source_reference\":" + q(regime.sourceReference())
                    + ",\"source_published_date\":" + regime.sourcePublishedDate().map(value -> q(value.toString())).orElse("null")
                    + ",\"source_sha256\":" + optionalJson(regime.sourceSha256())
                    + ",\"verification_status\":" + q(regime.verificationStatus().name()) + "}";
        }).toList();
        return "[" + String.join(",", entries) + "]";
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
