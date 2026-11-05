package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.provenance.RunConfig;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Entirely synthetic four-bar fixture; no exchange or account tariff is represented. */
public final class PortfolioSettlementFixtures {
    static final String DAY = "2030-05-06";
    static final String F = "F,SYN,DERIV,F-TOKEN,SYN-FUT,FUTURE,SYNTHETIC,2030-05-06,,,1,0.05";
    static final String O = "O,SYN,DERIV,O-TOKEN,SYN-CALL,OPTION,SYNTHETIC,2030-05-06,100,CALL,1,0.05";
    static final String BASE_SETTINGS = """
            portfolio.pending_intent_boundary_policy=CANCEL_AT_SESSION_BOUNDARY
            portfolio.position_boundary_policy=REQUIRE_FLAT
            portfolio.require_contiguous_frames_within_session=true
            strategy.fast_bars=2
            strategy.slow_bars=3
            strategy.long_lots=1
            fill.slippage_ticks=0
            fill.fee_per_execution_inr=1
            """;
    private PortfolioSettlementFixtures() { }
    public static void main(String[] args) throws Exception { create(Path.of(args[0])); }
    static void create(Path root) throws Exception {
        Files.createDirectories(root);
        write(root, "inputs.csv", "instrument_id,bars_csv,run_properties,max_abs_lots\nF,future.csv,future.properties,3\nO,option.csv,option.properties,3\n");
        write(root, "portfolio.properties", BASE_SETTINGS + """
                portfolio.settlement_policy=EXPLICIT_FINAL_SESSION
                portfolio.settlement_instruments=F,O
                portfolio.settlement_events_csv=settlement-events.csv
                portfolio.settlement_cost_schedule_csv=settlement-costs.csv
                """);
        write(root, "master.csv", String.join(",", PortfolioSettlementContract.HEADERS)
                + ",effective_from,effective_through\n" + F + ",2030-01-01,\n" + O + ",2030-01-01,\n");
        write(root, "rules.csv", "market_rule_regime_id,effective_from,effective_through,exchange,segment,contract_kind,timezone,session_open_local,session_close_local,preopen_enabled,preopen_start_local,preopen_end_local,expiry_weekday,holiday_expiry_shift,lot_size,tick_size,expiry_elm_addon_percent,calendar_spread_expiry_eligible,source_reference,source_published_date,source_sha256,verification_status\n"
                + "F-RULE,2030-01-01,,SYN,DERIV,FUTURE,Asia/Kolkata,09:15,09:19,false,,,,,1,0.05,,,synthetic-rule,,,SYNTHETIC_ONLY\n"
                + "O-RULE,2030-01-01,,SYN,DERIV,OPTION,Asia/Kolkata,09:15,09:19,false,,,,,1,0.05,,,synthetic-rule,,,SYNTHETIC_ONLY\n");
        for (String instrument : List.of("F", "O")) {
            String name = instrument.equals("F") ? "future" : "option";
            write(root, name + "-calendar.csv", "session_date,session_status,first_bar_timestamp,last_bar_timestamp,market_rule_regime_id,regime_source_reference\n"
                    + DAY + ",OPEN,2030-05-06T09:15:00+05:30,2030-05-06T09:18:00+05:30," + instrument + "-RULE,synthetic-rule\n");
            write(root, name + ".properties", """
                    dataset.id=synthetic-settlement-%s
                    dataset.source_name=SYNTHETIC_FIXTURE
                    dataset.acquired_at_utc=2030-05-07T00:00:00Z
                    dataset.timezone=Asia/Kolkata
                    dataset.timestamp_role=BAR_START
                    dataset.interval=ONE_MINUTE
                    dataset.rights_status=UNVERIFIED
                    dataset.synthetic_fixture=true
                    reference.snapshot_id=synthetic-settlement-master
                    reference.source_name=SYNTHETIC_FIXTURE
                    reference.retrieved_at_utc=2030-05-07T00:00:00Z
                    reference.effective_from=2030-01-01
                    reference.file=master.csv
                    calendar.file=%s-calendar.csv
                    rules.file=rules.csv
                    strategy.fast_bars=2
                    strategy.slow_bars=3
                    strategy.lots=1
                    risk.max_position_lots=3
                    risk.max_notional_inr=10000
                    risk.margin_per_lot_inr=1000
                    risk.max_margin_inr=3000
                    risk.max_daily_loss_inr=1000
                    risk.min_seconds_between_entry_orders=60
                    risk.max_entry_orders_per_session=0
                    orders.allow_next_session=false
                    position.cross_session_policy=REQUIRE_FLAT
                    quality.allow_intrasession_gaps=false
                    quality.max_missing_intervals_per_session=0
                    cost.status=UNVERIFIED
                    cost.slippage_ticks=0
                    cost.fee_per_order_inr=0
                    cost.fee_per_unit_inr=0
                    cost.notional_fee_bps=0
                    run.commit_id=synthetic-settlement-fixture
                    """.formatted(instrument, name));
            var bars = new StringBuilder("timestamp," + String.join(",", PortfolioSettlementContract.HEADERS)
                    + ",interval,timestamp_role,open,high,low,close,volume,open_interest,source,raw_record_hash\n");
            for (int i = 0; i < 4; i++) {
                int price = (instrument.equals("F") ? 100 : 10) + i;
                bars.append("2030-05-06T09:").append(15 + i).append(":00+05:30,").append(instrument.equals("F") ? F : O)
                        .append(",ONE_MINUTE,BAR_START,").append(price).append(',').append(price + 1).append(',').append(price - 1).append(',').append(price)
                        .append(",10,20,SYNTHETIC_FIXTURE,").append(RunConfig.sha256((instrument + i).getBytes(StandardCharsets.UTF_8))).append('\n');
            }
            write(root, name + ".csv", bars.toString());
        }
        write(root, "settlement-events.csv", String.join(",", PortfolioSettlementEvents.HEADERS) + "\n"
                + "SET-F," + F + ",2030-05-06,2030-05-06T09:19:00+05:30,110,false,,,synthetic-future-value\n"
                + "SET-O," + O + ",2030-05-06,2030-05-06T09:19:00+05:30,5,true,100,synthetic-turnover,synthetic-option-value\n");
        write(root, "settlement-costs.csv", String.join(",", PortfolioSettlementCostSchedule.HEADERS) + "\n"
                + "SYNTHETIC-COST,F-FIX,2030-01-01,2031-01-01," + F + ",LONG,EXPIRY_SETTLEMENT,FIXED,FIXED_PER_SETTLEMENT,1.005,2,HALF_UP,1800,true,2,HALF_UP,synthetic-tariff\n"
                + "SYNTHETIC-COST,O-FIX,2030-01-01,2031-01-01," + O + ",LONG,EXPIRY_SETTLEMENT,FIXED,FIXED_PER_SETTLEMENT,0.50,2,HALF_UP,0,false,2,HALF_UP,synthetic-tariff\n"
                + "SYNTHETIC-COST,O-EX,2030-01-01,2031-01-01," + O + ",LONG,OPTION_EXERCISE,EXERCISE,PER_EXERCISED_OPTION_UNIT,0.125,2,HALF_EVEN,1800,true,2,HALF_UP,synthetic-tariff\n");
    }
    static PortfolioReplayCsvRunner.Replay replay(Path root) throws Exception {
        return PortfolioReplayCsvRunner.replay(root.resolve("inputs.csv"), root.resolve("portfolio.properties"));
    }
    static void write(Path root, String name, String contents) throws Exception { Files.writeString(root.resolve(name), contents, StandardCharsets.UTF_8); }
    static void cell(Path path, int index, String key, String value) throws Exception {
        var table = CsvTable.read(path); var rows = new ArrayList<String>(); rows.add(String.join(",", table.headers()));
        for (int i = 0; i < table.rows().size(); i++) {
            Map<String, String> row = new HashMap<>(table.rows().get(i)); if (i == index) row.put(key, value);
            rows.add(PortfolioReplayArtifactWriter.csv(table.headers().stream().map(row::get).toArray()));
        }
        Files.writeString(path, String.join("\n", rows) + "\n", StandardCharsets.UTF_8);
    }
    static void failure(Throwing action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | java.io.IOException | ArithmeticException expected) { return; }
        throw new AssertionError("Expected fail-closed rejection");
    }
    static void equal(String expected, BigDecimal actual) { if (new BigDecimal(expected).compareTo(actual) != 0) throw new AssertionError(expected + " != " + actual); }
    static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    static void cleanup(Path root) throws Exception {
        Path temp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        root = root.toAbsolutePath().normalize();
        if (!root.startsWith(temp) || root.equals(temp) || Files.isSymbolicLink(root)) throw new IllegalArgumentException("Unsafe test cleanup path");
        try (var paths = Files.walk(root)) { for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    @FunctionalInterface interface Throwing { void run() throws Exception; }
}
