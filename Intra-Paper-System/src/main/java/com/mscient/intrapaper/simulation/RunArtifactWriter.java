package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.provenance.RunConfig;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Writes a content-addressed, create-new run folder with replay inputs and output hashes. */
public final class RunArtifactWriter {
    public record WrittenRun(String runId, Path path) { }

    public WrittenRun write(Path outputRoot, String runId, ValidatedDataset data,
            RunConfig config, BacktestResult result) throws IOException {
        Files.createDirectories(outputRoot);
        Path runFolder = outputRoot.toAbsolutePath().normalize().resolve(runId);
        Files.createDirectory(runFolder);

        writeNew(runFolder.resolve("orders.csv"), csv(
                "order_id,decision_timestamp,submit_timestamp,fill_timestamp,side,target_lots,requested_units,order_type,status,reason",
                result.orders().stream().map(order -> row(order.orderId(), order.decisionTimestamp(), order.submitTimestamp(),
                        order.fillTimestamp(), order.side(), order.targetLots(), order.requestedUnits(), order.orderType(),
                        order.status(), order.reason())).toList()));
        writeNew(runFolder.resolve("order-events.csv"), csv(
                "timestamp,order_id,event,detail",
                result.orderEvents().stream().map(event -> row(event.timestamp(), event.orderId(), event.event(), event.detail())).toList()));
        writeNew(runFolder.resolve("fills.csv"), csv(
                "fill_id,order_id,timestamp,side,quantity_units,reference_open_price,fill_price,modeled_slippage_inr,fee_inr,position_units_after",
                result.fills().stream().map(fill -> row(fill.fillId(), fill.orderId(), fill.timestamp(), fill.side(),
                        fill.quantityUnits(), fill.referenceOpenPrice(), fill.fillPrice(), fill.slippageCostInr(),
                        fill.feeInr(), fill.positionUnitsAfter())).toList()));
        writeNew(runFolder.resolve("trades.csv"), csv(
                "trade_id,instrument_id,entry_timestamp,exit_timestamp,quantity_units,entry_price,exit_price,gross_pnl_inr,fees_inr,net_pnl_inr",
                result.trades().stream().map(trade -> row(trade.tradeId(), trade.instrumentId(), trade.entryTimestamp(),
                        trade.exitTimestamp(), trade.quantityUnits(), trade.entryPrice(), trade.exitPrice(),
                        trade.grossPnlInr(), trade.feesInr(), trade.netPnlInr())).toList()));
        writeNew(runFolder.resolve("equity.csv"), csv(
                "timestamp,session_date,close,position_units,realized_pnl_inr,unrealized_pnl_inr,fees_paid_inr,net_pnl_inr,risk_state",
                result.equityCurve().stream().map(point -> row(point.timestamp(), point.sessionDate(), point.close(),
                        point.positionUnits(), point.realizedPnlInr(), point.unrealizedPnlInr(), point.feesPaidInr(),
                        point.netPnlInr(), point.riskState())).toList()));

        writeNew(runFolder.resolve("summary.json"), summaryJson(runId, data, config, result));
        Map<String, String> outputHashes = new TreeMap<>();
        for (String name : List.of("orders.csv", "order-events.csv", "fills.csv", "trades.csv", "equity.csv", "summary.json")) {
            outputHashes.put(name, RunConfig.sha256(Files.readAllBytes(runFolder.resolve(name))));
        }
        writeNew(runFolder.resolve("run-manifest.json"), manifestJson(runId, data, config, result, outputHashes));
        return new WrittenRun(runId, runFolder);
    }

    private static String summaryJson(String runId, ValidatedDataset data, RunConfig config, BacktestResult result) {
        return "{\n"
                + "  \"run_id\": " + q(runId) + ",\n"
                + "  \"result_classification\": " + q(result.resultClassification()) + ",\n"
                + "  \"performance_claim_eligible\": false,\n"
                + "  \"bars\": " + data.bars().size() + ",\n"
                + "  \"orders\": " + result.orders().size() + ",\n"
                + "  \"fills\": " + result.fills().size() + ",\n"
                + "  \"completed_trades\": " + result.trades().size() + ",\n"
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
        return "{\n"
                + "  \"schema_version\": 1,\n"
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
                + "  \"instrument_master_snapshot_id\": " + q(data.instrumentMaster().snapshotId()) + ",\n"
                + "  \"instrument_master_sha256\": " + q(data.instrumentMaster().rawFileSha256()) + ",\n"
                + "  \"session_calendar_sha256\": " + q(data.calendarSha256()) + ",\n"
                + "  \"timezone\": " + q(config.timezone()) + ",\n"
                + "  \"timestamp_role\": " + q(config.timestampRole().name()) + ",\n"
                + "  \"interval\": " + q(config.interval()) + ",\n"
                + "  \"date_range\": {\"start\": " + q(bars.getFirst().sessionDate().toString())
                + ", \"end\": " + q(bars.getLast().sessionDate().toString()) + "},\n"
                + "  \"bar_timestamp_range\": {\"start\": " + q(bars.getFirst().timestamp().toString())
                + ", \"end\": " + q(bars.getLast().timestamp().toString()) + "},\n"
                + "  \"strategy\": {\"id\": \"SMA_CROSSOVER_LONG_ONLY_V1\", \"fast_bars\": " + config.fastBars()
                + ", \"slow_bars\": " + config.slowBars() + ", \"lots\": " + config.requestedLots() + "},\n"
                + "  \"fill_model\": \"NEXT_BAR_OPEN_MARKET_WITH_FIXED_ADVERSE_TICK_SLIPPAGE_V1\",\n"
                + "  \"passive_orders_supported\": false,\n"
                + "  \"partial_fills_supported\": false,\n"
                + "  \"cost_model\": {\"status\": " + q(config.costStatus()) + ", \"evidence_reference\": "
                + optionalJson(config.costEvidenceReference()) + ", \"slippage_ticks\": " + config.slippageTicks()
                + ", \"fee_per_order_inr\": " + q(num(config.feePerOrderInr()))
                + ", \"fee_per_unit_inr\": " + q(num(config.feePerUnitInr())) + "},\n"
                + "  \"risk_limits\": {\"max_position_lots\": " + config.maxPositionLots()
                + ", \"max_notional_inr\": " + q(num(config.maxNotionalInr()))
                + ", \"max_daily_loss_inr\": " + q(num(config.maxDailyLossInr())) + "},\n"
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
