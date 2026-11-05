package com.mscient.intrapaper.audit.portfolio;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.provenance.*;
import com.mscient.intrapaper.reference.*;
import com.mscient.intrapaper.simulation.portfolio.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Focused signed arithmetic, strict inputs, provenance, reconstruction and publication checks. */
public final class PortfolioSettlementAuditTest {
    private static int checks;
    private static final LocalDate DATE = LocalDate.parse("2030-05-06");
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("portfolio-settlement-test-");
        try { arithmetic(root.resolve("unit")); endToEnd(root.resolve("e2e"), Path.of(args[0])); }
        finally { PortfolioSettlementAudit.cleanup(root); }
        System.out.println("PortfolioSettlementAudit: " + checks + " focused checks passed");
    }

    private static void arithmetic(Path root) throws Exception {
        Files.createDirectories(root);
        Path events = root.resolve("events.csv"), costs = root.resolve("costs.csv");
        String valid = SettlementInputs.EVENTS_HEADER + "\nA,2030-05-06,2030-05-06,CASH_FUTURES_FINAL_PRICE,106,s,fixture future\n"
                + "B,2030-05-06,2030-05-06,CASH_OPTION_PAYOFF,0,s,fixture option cash payoff\n";
        Files.writeString(events, valid);
        var frames = frames(DATE);
        var replay = replay(frames, false);
        var result = PortfolioSettlementCalculator.calculate(frames, replay, SettlementInputs.load(events, null));
        var rows = reportRows(root, result);
        var a = rows.get(0); var b = rows.get(1);
        require(a.get("replay_signed_units").equals("-2") && b.get("replay_signed_units").equals("2"), "signed futures short and option long after partial closes/reversals");
        require(a.get("settlement_adjustment_inr").equals("-4") && b.get("settlement_adjustment_inr").equals("-28"), "signed adjustments at final close, including zero payoff");
        require(rows.stream().allMatch(r -> r.get("post_settlement_signed_units").equals("0") && r.get("status").equals("SETTLED")), "settlement zeroes only report positions");
        require(result.adjustments().compareTo(new BigDecimal("-32")) == 0 && result.finalNet().compareTo(result.replayNet().subtract(new BigDecimal("32"))) == 0, "portfolio net adds adjustments without double counting execution fees");
        require(a.get("replay_execution_fees_inr").equals("4") && b.get("replay_execution_fees_inr").equals("4"), "ordinary fees remain separate");
        require(replay.executions().size() == 8 && replay.snapshots().get(frames.size() * 2 - 1).positions().get("A").signedUnits() == -2
                && replay.snapshots().getLast().positions().get("A").signedUnits() == 0, "audit preserves both pre-settlement and integrated positions");
        var opposite = PortfolioSettlementCalculator.calculate(frames, replay(frames, true), SettlementInputs.load(events, null));
        var reverseRows = reportRows(root, opposite);
        require(reverseRows.get(0).get("settlement_adjustment_inr").equals("4") && reverseRows.get(1).get("settlement_adjustment_inr").equals("28"), "futures long and option short signed cash adjustment");
        String costText = SettlementInputs.COST_HEADER + "\n2030-05-06,2030-05-06,FUTURE,FIXED,FIXED_PER_SETTLEMENT,0.005,1800,true,fixture\n"
                + "2030-05-06,2030-05-06,FUTURE,UNIT,PER_SETTLED_UNIT,0.5,0,false,fixture\n"
                + "2030-05-06,2030-05-06,FUTURE,BPS,BPS_OF_SETTLEMENT_NOTIONAL,10,1800,true,fixture\n"
                + "2030-05-06,2030-05-06,OPTION,BPS,BPS_OF_SETTLEMENT_NOTIONAL,10,1800,true,fixture\n"
                + "2030-05-06,2030-05-06,OPTION,UNIT,PER_SETTLED_UNIT,0.5,0,false,fixture\n";
        Files.writeString(costs, costText);
        var charged = PortfolioSettlementCalculator.calculate(frames, replay, SettlementInputs.load(events, costs));
        require(charged.costs().compareTo(new BigDecimal("2.26")) == 0, "all compatible cost bases and per-component paise/GST rounding");
        require(charged.finalNet().compareTo(result.finalNet().subtract(new BigDecimal("2.26"))) == 0, "settlement costs deducted once");
        Path lines = root.resolve("cost-lines.csv"); Files.writeString(lines, charged.files().get("settlement-cost-lines.csv"));
        var chargeRows = CsvTable.read(lines).rows();
        require(chargeRows.size() == 5 && chargeRows.get(0).get("calculation_base").equals("212"), "absolute short quantity drives settlement notional cost basis");
        require(chargeRows.get(0).get("charge_product_inr").equals("2120") && chargeRows.get(0).get("gst_product_inr").equals("378"), "multiplication intermediates recorded before bps division");
        require(chargeRows.stream().anyMatch(r -> r.get("component_code").equals("FIXED") && r.get("unrounded_charge_inr").equals("0.005") && r.get("charge_inr").equals("0.01")), "half paise charge rounds HALF_UP");
        require(chargeRows.stream().filter(r -> r.get("instrument_id").equals("B") && r.get("component_code").equals("BPS")).allMatch(r -> r.get("total_inr").equals("0")), "zero option payoff cost notional is zero");
        var schedule = SettlementInputs.load(events, costs).costs;
        var legacy = schedule.calculate(DATE, ContractKind.FUTURE, new BigDecimal("106"), BigDecimal.ZERO, 2);
        require(legacy.stream().map(SettlementCostSchedule.ChargeLine::totalInr).reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(new BigDecimal("1.26")) == 0, "compatible future costs agree with established schedule calculator");

        for (String bad : List.of(valid + valid.lines().skip(1).findFirst().orElseThrow() + "\n", valid + "\n",
                valid.replace("source_id", "unknown"), valid.replace("106,s", "NaN,s"), valid.replace("106,s", "1e10,s"),
                valid.replace("106,s", "-1,s"), valid.replace("106,s", "0,s"), valid.replace("PAYOFF,0,s", "PAYOFF,-1,s"),
                valid.replace("106,s", "1000000000000000000000000000000,s"), valid.replace("106,s", "1.0000000000001,s"),
                valid.replace("CASH_OPTION_PAYOFF", "PHYSICAL_OPTION"), valid.replace(",s,fixture", ",,fixture"),
                valid.replace("A,2030", " A,2030"), valid.replace("2030-05-06,CASH", "2030-02-30,CASH"))) {
            Files.writeString(events, bad); fails(() -> SettlementInputs.load(events, null), "strict malformed event rejection");
        }
        Files.write(events, new byte[]{(byte)0xc3, 0x28}); fails(() -> SettlementInputs.load(events, null), "invalid UTF-8 rejected");
        for (String bad : List.of(valid.replace("B,2030", "UNKNOWN,2030"), valid.replace("A,2030-05-06", "A,2030-05-07"),
                valid.replace("2030-05-06,CASH", "2030-05-05,CASH"), valid.replace("2030-05-06,CASH", "2030-05-07,CASH"),
                valid.replace("CASH_OPTION_PAYOFF", "CASH_FUTURES_FINAL_PRICE").replace("PRICE,0,", "PRICE,1,"),
                SettlementInputs.EVENTS_HEADER + "\n")) {
            Files.writeString(events, bad); fails(() -> PortfolioSettlementCalculator.calculate(frames, replay, SettlementInputs.load(events, null)), "wrong/unknown/date/missing event rejected");
        }
        Files.writeString(events, valid);
        fails(() -> PortfolioSettlementCalculator.calculate(frames(DATE.plusDays(1)), replay(frames(DATE.plusDays(1)), false), SettlementInputs.load(events, null)), "future-expiry event unused");
        var flatReplay = ledgerFixture(frames, false, true);
        fails(() -> PortfolioSettlementCalculator.calculate(frames, flatReplay, SettlementInputs.load(events, null)), "already-flat settlement rejected");
        Files.writeString(events, SettlementInputs.EVENTS_HEADER + "\n");
        var flatReport = PortfolioSettlementCalculator.calculate(frames, flatReplay, SettlementInputs.load(events, null));
        require(flatReport.settledCount() == 0 && flatReport.finalNet().signum() == 0, "header-only events valid for flat expiry positions");
        var unexpired = frames(DATE.plusDays(1));
        require(PortfolioSettlementCalculator.calculate(unexpired, replay(unexpired, false), SettlementInputs.load(events, null)).settledCount() == 0, "unexpired open positions remain marked and unsettled");
        fails(() -> PortfolioSettlementCalculator.calculate(frames, flatReplay, SettlementInputs.load(events, costs)), "unused costs rejected");
        Files.writeString(events, valid);
        for (String bad : List.of(costText + costText.lines().skip(1).findFirst().orElseThrow() + "\n", costText + "\n",
                costText.replace("PER_SETTLED_UNIT", "PER_EXERCISED_OPTION_UNIT"), costText.replace("BPS_OF_SETTLEMENT_NOTIONAL", "BPS_OF_OPTION_INTRINSIC"),
                costText.replace("0.5,0", "-1,0"), costText.replace("1800,true", "1800,TRUE"),
                costText.replace("2030-05-06,FUTURE,FIXED", "2030-05-05,FUTURE,FIXED"))) {
            Files.writeString(costs, bad); fails(() -> SettlementInputs.load(events, costs), "strict malformed/incompatible cost rejection");
        }
        Files.writeString(costs, costText.replace("2030-05-06,2030-05-06,FUTURE,FIXED", "2030-05-05,2030-05-05,FUTURE,FIXED"));
        fails(() -> PortfolioSettlementCalculator.calculate(frames, replay, SettlementInputs.load(events, costs)), "missing one declared component coverage rejected");
        Files.writeString(costs, costText.replace("2030-05-06,2030-05-06", "2030-05-05,2030-05-05"));
        fails(() -> PortfolioSettlementCalculator.calculate(frames, replay, SettlementInputs.load(events, costs)), "missing all dated coverage rejected");
        var snapshots = new ArrayList<>(replay.snapshots()); int finalBarIndex = frames.size() * 2 - 1;
        var last = snapshots.get(finalBarIndex); var positions = new TreeMap<>(last.positions());
        var old = positions.get("A"); positions.put("A", new PortfolioAccountingLedger.PositionSnapshot(-1, old.averageOpenPrice(), old.realizedGrossPnlInr(), old.feesInr(), old.unrealizedPnlInr()));
        snapshots.set(finalBarIndex, new PortfolioAccountingLedger.Snapshot(last.timestamp(), last.phase(), positions, last.realizedGrossPnlInr(), last.feesInr(), last.unrealizedPnlInr(), last.netPnlInr()));
        fails(() -> PortfolioSettlementCalculator.calculate(frames, with(replay, replay.executions(), snapshots), SettlementInputs.load(events, null)), "position/calculation mismatch rejected");
        fails(() -> PortfolioSettlementCalculator.calculate(frames.subList(1, frames.size()), replay, SettlementInputs.load(events, null)), "missing mark/frame mismatch rejected");
        var overflowExecutions = List.of(new PortfolioExecution("overflow1", 1, "A", frames.get(1).timestamp(), PortfolioExecution.Side.BUY, Long.MAX_VALUE, BigDecimal.ONE, BigDecimal.ZERO),
                new PortfolioExecution("overflow2", 2, "A", frames.get(2).timestamp(), PortfolioExecution.Side.BUY, 1, BigDecimal.ONE, BigDecimal.ZERO));
        fails(() -> PortfolioSettlementCalculator.calculate(frames, with(replay, overflowExecutions, replay.snapshots()), SettlementInputs.load(events, null)), "signed-unit overflow rejected");
        Files.writeString(events, valid.replace("106,s", "999999999999999999999999999999,s"));
        fails(() -> PortfolioSettlementCalculator.calculate(frames, replay, SettlementInputs.load(events, null)), "settlement amount overflow rejected");
    }

    private static void endToEnd(Path root, Path project) throws Exception {
        Files.createDirectories(root);
        try (var paths = Files.list(project.resolve("examples"))) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) Files.copy(file, root.resolve(file.getFileName()));
        }
        String first = "SYN-NIFTY-FUT-2030", second = "SYN-OPTION-2030";
        String firstBars = risingBars(root.resolve("synthetic-futures-bars.csv"));
        Files.writeString(root.resolve("synthetic-futures-bars.csv"), firstBars);
        String firstMaster = Files.readString(root.resolve("synthetic-instrument-master.csv")).replace("2030-12-26", "2030-05-06");
        Files.writeString(root.resolve("synthetic-instrument-master.csv"), firstMaster);
        Files.writeString(root.resolve("second-bars.csv"), option(firstBars, first, second));
        Files.writeString(root.resolve("second-master.csv"), option(firstMaster, first, second));
        Files.writeString(root.resolve("second-rules.csv"), Files.readString(root.resolve("synthetic-market-rule-regimes.csv")).replace(",FUTURE,", ",OPTION,"));
        String baseConfig = Files.readString(root.resolve("synthetic-run.properties"));
        Files.writeString(root.resolve("synthetic-run.properties"), baseConfig);
        Files.writeString(root.resolve("second.properties"), baseConfig.replace("synthetic-instrument-master.csv", "second-master.csv").replace("synthetic-market-rule-regimes.csv", "second-rules.csv"));
        StringBuilder replayEvents = new StringBuilder(String.join(",", PortfolioSettlementEvents.HEADERS)).append('\n');
        for (String filename : List.of("synthetic-futures-bars.csv", "second-bars.csv")) {
            var bar = CsvTable.read(root.resolve(filename)).rows().getFirst();
            var cells = new ArrayList<String>(); cells.add("event-" + bar.get("instrument_id"));
            PortfolioSettlementContract.HEADERS.forEach(key -> cells.add(bar.get(key)));
            cells.addAll(List.of("2030-05-06", "2030-05-06T09:45:00+05:30",
                    filename.equals("second-bars.csv") ? "0" : "140", "false", "", "", "synthetic explicit replay event"));
            PortfolioSettlementCalculator.row(replayEvents, cells.toArray());
        }
        Files.writeString(root.resolve("replay-events.csv"), replayEvents);
        Path inputs = root.resolve("portfolio-inputs.csv"), settings = root.resolve("portfolio.properties"), events = root.resolve("events.csv"), costs = root.resolve("costs.csv");
        Files.writeString(inputs, "instrument_id,bars_csv,run_properties,max_abs_lots\n" + first + ",synthetic-futures-bars.csv,synthetic-run.properties,2\n" + second + ",second-bars.csv,second.properties,2\n");
        String settingText = "portfolio.pending_intent_boundary_policy=CANCEL_AT_SESSION_BOUNDARY\nportfolio.position_boundary_policy=ALLOW_OVERNIGHT\nportfolio.require_contiguous_frames_within_session=true\nstrategy.fast_bars=2\nstrategy.slow_bars=3\nstrategy.long_lots=1\nfill.slippage_ticks=0\nfill.fee_per_execution_inr=1\n"
                + "portfolio.settlement_policy=EXPLICIT_FINAL_SESSION\nportfolio.settlement_instruments=" + first + "," + second
                + "\nportfolio.settlement_events_csv=replay-events.csv\n";
        Files.writeString(settings, settingText);
        String eventText = SettlementInputs.EVENTS_HEADER + "\n" + first + ",2030-05-06,2030-05-06,CASH_FUTURES_FINAL_PRICE,140,fixture,caller future\n"
                + second + ",2030-05-06,2030-05-06,CASH_OPTION_PAYOFF,0,fixture,caller payoff\n";
        Files.writeString(events, eventText);
        Files.writeString(costs, SettlementInputs.COST_HEADER + "\n2030-05-06,2030-05-06,FUTURE,FIXED,FIXED_PER_SETTLEMENT,1,0,false,fixture\n2030-05-06,2030-05-06,OPTION,FIXED,FIXED_PER_SETTLEMENT,1,0,false,fixture\n");
        Path run = PortfolioReplayCsvRunner.run(inputs, settings, root.resolve("runs")).path();
        Map<String, byte[]> originalRun = contents(run);
        fails(() -> PortfolioSettlementAudit.audit(run, inputs, settings, events, null, run.resolve("audit")), "cannot publish into original replay");
        var firstAudit = PortfolioSettlementAudit.audit(run, inputs, settings, events, null, root.resolve("audits"));
        require(PortfolioSettlementAudit.audit(run, inputs, settings, events, null, root.resolve("audits")).equals(firstAudit), "repeat publication is idempotent");
        require(PortfolioSettlementAudit.verify(firstAudit.path(), run, inputs, settings, events, null).equals(firstAudit), "semantic reconstruction reproduces all bytes");
        require(!Files.exists(firstAudit.path().resolve("settlement-cost-lines.csv")), "no optional cost file when disabled");
        var costAudit = PortfolioSettlementAudit.audit(run, inputs, settings, events, costs, root.resolve("audits"));
        require(!costAudit.auditId().equals(firstAudit.auditId()) && Files.isRegularFile(costAudit.path().resolve("settlement-cost-lines.csv")), "cost bytes bind a distinct sidecar with cost lines");
        require(PortfolioSettlementAudit.verify(costAudit.path(), run, inputs, settings, events, costs).equals(costAudit), "cost semantic verification reproduces all bytes");
        String noCostSummary = Files.readString(firstAudit.path().resolve("summary.properties"));
        String costSummary = Files.readString(costAudit.path().resolve("summary.properties"));
        require(noCostSummary.contains("audit_basis=FINAL_BAR_BEFORE_INTEGRATED_SETTLEMENT\n")
                && noCostSummary.contains("replay_integrated_settlement_count=2\n"), "sidecar explicitly identifies pre-settlement basis");
        var noCostValues = new Properties(); noCostValues.load(new java.io.StringReader(noCostSummary));
        var costValues = new Properties(); costValues.load(new java.io.StringReader(costSummary));
        require(new BigDecimal(noCostValues.getProperty("final_net_pnl_inr")).compareTo(
                new BigDecimal(noCostValues.getProperty("replay_final_net_pnl_inr"))) == 0, "matching sidecar settlement does not double count integrated proceeds");
        require(new BigDecimal(costValues.getProperty("final_net_pnl_inr")).compareTo(
                new BigDecimal(costValues.getProperty("replay_final_net_pnl_inr")).subtract(new BigDecimal("2"))) == 0,
                "sidecar charges are applied once to its independent basis");
        Path lockPath = root.resolve("audits").resolve(".settlement-lock-" + firstAudit.auditId());
        try (var channel = java.nio.channels.FileChannel.open(lockPath, StandardOpenOption.WRITE); var lock = channel.lock()) {
            fails(() -> PortfolioSettlementAudit.audit(run, inputs, settings, events, null, root.resolve("audits")), "concurrent publication refuses held lock");
        }
        require(PortfolioSettlementAuditCli.run(new String[]{"audit", run.toString(), inputs.toString(), settings.toString(), events.toString(), root.resolve("cli-audits").toString(), costs.toString()}) == 0, "dedicated audit mode");
        require(PortfolioSettlementAuditCli.run(new String[]{"verify", costAudit.path().toString(), run.toString(), inputs.toString(), settings.toString(), events.toString(), costs.toString()}) == 0, "dedicated verify mode");
        require(PortfolioSettlementAuditCli.run(new String[]{"verify-artifact", firstAudit.path().toString()}) == 0 && PortfolioSettlementAuditCli.run(new String[]{"bad"}) == 1, "dedicated artifact mode and invalid arguments");
        Files.writeString(events, eventText.replace("caller future", "different source"));
        fails(() -> PortfolioSettlementAudit.verify(firstAudit.path(), run, inputs, settings, events, null), "source reference change breaks semantic identity");
        require(PortfolioSettlementArtifacts.verify(firstAudit.path()).equals(firstAudit), "artifact integrity does not need input files or authenticate sources");
        Files.writeString(events, eventText + "\r\n"); fails(() -> PortfolioSettlementAudit.verify(firstAudit.path(), run, inputs, settings, events, null), "extra malformed rows rejected during verify");
        Files.writeString(events, eventText.replace("\n", "\r\n")); fails(() -> PortfolioSettlementAudit.verify(firstAudit.path(), run, inputs, settings, events, null), "semantically equal changed event bytes rejected");
        Files.writeString(events, eventText);
        Files.writeString(settings, settingText + "# byte change\n"); fails(() -> PortfolioSettlementAudit.verify(firstAudit.path(), run, inputs, settings, events, null), "exact original settings bytes bound"); Files.writeString(settings, settingText);
        String costText = Files.readString(costs); Files.writeString(costs, costText.replace(",1,0,false", ",2,0,false"));
        fails(() -> PortfolioSettlementAudit.verify(costAudit.path(), run, inputs, settings, events, costs), "cost input changes rejected"); Files.writeString(costs, costText);
        for (String name : List.of("settlements.csv", "inputs.csv", "summary.properties", "settlement-manifest.properties")) {
            Path file = firstAudit.path().resolve(name); byte[] bytes = Files.readAllBytes(file); Files.writeString(file, "tampered\n", StandardOpenOption.APPEND);
            fails(() -> PortfolioSettlementArtifacts.verify(firstAudit.path()), "tampering rejected: " + name); Files.write(file, bytes);
        }
        Path costLinesPath = costAudit.path().resolve("settlement-cost-lines.csv");
        byte[] costLineBytes = Files.readAllBytes(costLinesPath); Files.writeString(costLinesPath, "tampered\n", StandardOpenOption.APPEND);
        fails(() -> PortfolioSettlementArtifacts.verify(costAudit.path()), "optional cost line tampering rejected"); Files.write(costLinesPath, costLineBytes);
        Path corrupt = firstAudit.path().resolve("settlements.csv"); byte[] original = Files.readAllBytes(corrupt); Files.writeString(corrupt, "corrupt\n");
        fails(() -> PortfolioSettlementAudit.audit(run, inputs, settings, events, null, root.resolve("audits")), "repeat publication refuses corrupt existing destination"); Files.write(corrupt, original);
        Path extra = firstAudit.path().resolve("extra.csv"); Files.writeString(extra, "x"); fails(() -> PortfolioSettlementArtifacts.verify(firstAudit.path()), "unexpected sidecar file rejected"); Files.delete(extra);
        Path missing = firstAudit.path().resolve("inputs.csv"); byte[] bytes = Files.readAllBytes(missing); Files.delete(missing);
        fails(() -> PortfolioSettlementArtifacts.verify(firstAudit.path()), "missing sidecar file rejected"); Files.write(missing, bytes);
        byte[] manifestBytes = Files.readAllBytes(firstAudit.path().resolve(PortfolioSettlementArtifacts.MANIFEST));
        var forged = new TreeMap<>(contents(firstAudit.path())); forged.remove(PortfolioSettlementArtifacts.MANIFEST);
        forged.put("settlements.csv", PortfolioSettlementAudit.utf8("forged but self-consistent report\n"));
        var fake = PortfolioSettlementArtifacts.write(root.resolve("forged"), new PortfolioSettlementAudit.Prepared(forged, false));
        require(PortfolioSettlementArtifacts.verify(fake.path()).equals(fake), "self-consistent altered report passes integrity only");
        fails(() -> PortfolioSettlementAudit.verify(fake.path(), run, inputs, settings, events, null), "semantic verifier rejects rehashed false report");
        Path relocated = root.resolve("wrong-name"); Files.createDirectories(relocated);
        for (var entry : contents(firstAudit.path()).entrySet()) Files.write(relocated.resolve(entry.getKey()), entry.getValue());
        fails(() -> PortfolioSettlementArtifacts.verify(relocated), "wrong content-addressed folder name rejected");
        Path executionFile = run.resolve("executions.csv"); byte[] executions = Files.readAllBytes(executionFile); Files.writeString(executionFile, "tampered\n", StandardOpenOption.APPEND);
        fails(() -> PortfolioSettlementAudit.audit(run, inputs, settings, events, null, root.resolve("rejected")), "saved replay tampering rejected before calculation"); Files.write(executionFile, executions);
        Files.writeString(root.resolve("replay-events.csv"), replayEvents.toString().replace("synthetic explicit replay event", "changed replay event source"));
        fails(() -> PortfolioSettlementAudit.verify(firstAudit.path(), run, inputs, settings, events, null), "changed indirect validated replay input rejected");
        require(originalRun.keySet().equals(contents(run).keySet()) && originalRun.entrySet().stream().allMatch(e -> Arrays.equals(e.getValue(), uncheckedRead(run.resolve(e.getKey())))), "original replay files remain byte-for-byte unchanged");
        try (var paths = Files.list(root.resolve("audits"))) { require(paths.noneMatch(p -> p.getFileName().toString().startsWith(".settlement-staging-")), "no partial published staging folders"); }
        require(manifestBytes.length > 0 && Files.readString(firstAudit.path().resolve("summary.properties")).contains("audit_build_artifact_sha256="), "build artifact identity recorded");
    }

    private static List<Map<String, String>> reportRows(Path root, PortfolioSettlementCalculator.Report report) throws Exception { Path p = root.resolve("report.csv"); Files.writeString(p, report.files().get("settlements.csv")); return CsvTable.read(p).rows(); }
    private static Map<String, byte[]> contents(Path folder) throws Exception { var result = new TreeMap<String, byte[]>(); try (var files = Files.list(folder)) { for (var file : files.toList()) result.put(file.getFileName().toString(), Files.readAllBytes(file)); } return result; }
    private static byte[] uncheckedRead(Path path) { try { return Files.readAllBytes(path); } catch (Exception failed) { throw new RuntimeException(failed); } }
    private static String option(String text, String first, String second) { return text.replace(first, second).replace("SYNTHETIC-001", "SYNTHETIC-002").replace(",FUTURE,", ",OPTION,").replace(",2030-05-06,,,1,", ",2030-05-06,100,CALL,1,"); }
    private static String risingBars(Path path) throws Exception { var table = CsvTable.read(path); StringBuilder out = new StringBuilder(String.join(",", table.headers()) + "\n"); int index = 0; for (var source : table.rows()) { var row = new TreeMap<>(source); row.put("expiry", "2030-05-06"); row.put("open", "" + (100 + index)); row.put("close", "" + (101 + index)); row.put("high", "" + (102 + index)); row.put("low", "" + (99 + index)); PortfolioSettlementCalculator.row(out, table.headers().stream().map(row::get).toArray()); index++; } return out.toString(); }
    private static PortfolioReplayEngine.Config config() { return new PortfolioReplayEngine.Config(Map.of("A", 2, "B", 2), PortfolioReplayEngine.PendingIntentBoundaryPolicy.ALLOW_NEXT_SESSION_OPEN, CrossSessionPositionPolicy.ALLOW_OVERNIGHT, true); }
    private static PortfolioReplayEngine.Config config(List<SynchronizedBarFrameBuilder.Frame> frames) {
        return events(frames).isEmpty() ? config() : new PortfolioReplayEngine.Config(Map.of("A", 2, "B", 2),
                PortfolioReplayEngine.PendingIntentBoundaryPolicy.ALLOW_NEXT_SESSION_OPEN, CrossSessionPositionPolicy.ALLOW_OVERNIGHT,
                true, PortfolioSettlementPolicy.EXPLICIT_FINAL_SESSION, Set.of("A", "B"));
    }
    private static Optional<PortfolioSettlementEvents> events(List<SynchronizedBarFrameBuilder.Frame> frames) {
        if (!frames.getLast().barsByInstrument().get("A").expiry().equals(DATE)) return Optional.empty();
        var supplied = frames.getLast().barsByInstrument().values().stream().map(bar -> new PortfolioSettlementEvents.Event(
                "event-" + bar.instrumentId(), PortfolioSettlementContract.from(bar), DATE, Instant.parse("2030-05-06T03:50:00Z"),
                bar.contractKind() == ContractKind.FUTURE ? new BigDecimal("106") : BigDecimal.ZERO,
                false, Optional.empty(), Optional.empty(), "synthetic explicit event")).toList();
        return Optional.of(new PortfolioSettlementEvents(supplied, "b".repeat(64)));
    }
    private static PortfolioReplayEngine.Result replay(List<SynchronizedBarFrameBuilder.Frame> frames, boolean opposite) {
        return ledgerFixture(frames, opposite, false);
    }
    /** Arithmetic fixture only; real replay validation and publication are exercised by endToEnd. */
    private static PortfolioReplayEngine.Result ledgerFixture(List<SynchronizedBarFrameBuilder.Frame> frames, boolean opposite, boolean flat) {
        var ledger = new PortfolioAccountingLedger();
        var executions = new ArrayList<PortfolioExecution>();
        var snapshots = new ArrayList<PortfolioAccountingLedger.Snapshot>();
        var settlements = new ArrayList<PortfolioSettlement>();
        int previous = 0; long sequence = 0;
        for (int i = 0; i < frames.size(); i++) {
            var frame = frames.get(i);
            int target = flat ? 0 : switch (i) { case 0 -> 0; case 1 -> 2; case 2 -> 1; case 3 -> -1; default -> -2; };
            if (opposite) target = -target;
            int delta = target - previous; previous = target;
            var atFrame = new ArrayList<PortfolioExecution>();
            if (delta != 0) for (String id : List.of("A", "B")) {
                int signed = id.equals("A") ? delta : -delta;
                atFrame.add(new PortfolioExecution("fixture-" + (++sequence), sequence, id, frame.timestamp(),
                        signed > 0 ? PortfolioExecution.Side.BUY : PortfolioExecution.Side.SELL,
                        Math.abs(signed), frame.barsByInstrument().get(id).open(), BigDecimal.ONE));
            }
            executions.addAll(atFrame); snapshots.add(ledger.mark(frame, atFrame)); snapshots.add(ledger.markToClose(frame));
        }
        var supplied = events(frames);
        if (supplied.isPresent()) {
            var batch = ledger.settleFinalSession(frames.getLast(), supplied.orElseThrow(), Optional.empty());
            snapshots.addAll(batch.snapshots()); settlements.addAll(batch.settlements());
        }
        return new PortfolioReplayEngine.Result("audit-arithmetic-fixture", Map.of(), "fixture", Map.of(), "fixture",
                config(frames), "", Optional.empty(), Optional.empty(), "", "", "", "", List.of(), List.of(),
                executions, snapshots, "REPLAY_DIAGNOSTIC_ONLY", supplied.map(PortfolioSettlementEvents::sourceFileSha256),
                Optional.empty(), Optional.empty(), settlements);
    }
    private static List<SynchronizedBarFrameBuilder.Frame> frames(LocalDate expiry) { List<Bar> a = new ArrayList<>(), b = new ArrayList<>(); for (int i = 0; i < 5; i++) { Instant at = Instant.parse("2030-05-06T03:45:00Z").plusSeconds(i * 60L); a.add(bar("A", ContractKind.FUTURE, at, expiry, 100 + i)); b.add(bar("B", ContractKind.OPTION, at, expiry, 10 + i)); } return SynchronizedBarFrameBuilder.build(Map.of("A", a, "B", b)); }
    private static Bar bar(String id, ContractKind kind, Instant at, LocalDate expiry, int price) { BigDecimal value = BigDecimal.valueOf(price); return new Bar(at, DATE, id, "SYN", "SYN", id, id, kind, "SYNTHETIC", expiry, kind == ContractKind.OPTION ? Optional.of(new BigDecimal("100")) : Optional.empty(), kind == ContractKind.OPTION ? Optional.of(OptionRight.CALL) : Optional.empty(), 1, new BigDecimal("0.05"), "ONE_MINUTE", TimestampRole.BAR_START, value, value.add(BigDecimal.ONE), value.subtract(BigDecimal.ONE), value, 100L, 100L, "SYNTHETIC_FIXTURE", "a".repeat(64)); }
    private static PortfolioReplayEngine.Result with(PortfolioReplayEngine.Result r, List<PortfolioExecution> executions, List<PortfolioAccountingLedger.Snapshot> snapshots) { return new PortfolioReplayEngine.Result(r.strategyId(), r.strategyParameters(), r.fillModelId(), r.fillModelParameters(), r.buildCommit(), r.config(), r.validatedDatasetsSha256(), r.executionCostScheduleId(), r.executionCostScheduleSourceFileSha256(), r.frameInputsSha256(), r.strategyFingerprintSha256(), r.fillModelFingerprintSha256(), r.replayFingerprintSha256(), r.decisions(), r.orders(), executions, snapshots, r.classification(), r.settlementEventsSourceFileSha256(), r.settlementCostScheduleId(), r.settlementCostScheduleSourceFileSha256(), r.settlements()); }
    private static void require(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
    private static void fails(Checked work, String label) throws Exception { checks++; try { work.run(); } catch (IllegalArgumentException | java.io.IOException | ArithmeticException expected) { return; } throw new AssertionError("Expected rejection: " + label); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
