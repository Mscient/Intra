package com.mscient.intrapaper.audit.portfolio;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.provenance.*;
import com.mscient.intrapaper.reference.*;
import com.mscient.intrapaper.simulation.portfolio.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Focused arithmetic, strict schedule, atomic artifact and full reconstruction checks. */
public final class PortfolioRiskAuditTest {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("portfolio-risk-audit-test-");
        try {
            arithmeticAndCoverage(root.resolve("unit"));
            endToEnd(root.resolve("e2e"), Path.of(args[0]));
        } finally { PortfolioRiskAudit.cleanup(root); }
        System.out.println("PortfolioRiskAudit: " + checks + " focused checks passed");
    }

    private static void arithmeticAndCoverage(Path folder) throws Exception {
        Files.createDirectories(folder);
        writeRisk(folder, List.of("A", "B"), "2030-05-06", "2030-05-07");
        List<SynchronizedBarFrameBuilder.Frame> frames = frames();
        PortfolioStrategy strategy = new PortfolioStrategy() {
            public String id() { return "signed-audit-fixture"; }
            public Map<String, String> parameters() { return Map.of(); }
            public PortfolioTarget evaluate(List<SynchronizedBarFrameBuilder.Frame> seen, Instant at, PortfolioAccountingLedger.Snapshot snapshot) {
                int lots = switch (seen.size()) { case 1 -> 2; case 2 -> 1; case 3 -> -1; default -> 0; };
                return new PortfolioTarget(Map.of("A", lots, "B", -lots), "signed reversal fixture");
            }
        };
        var config = new PortfolioReplayEngine.Config(Map.of("A", 2, "B", 2),
                PortfolioReplayEngine.PendingIntentBoundaryPolicy.ALLOW_NEXT_SESSION_OPEN, CrossSessionPositionPolicy.ALLOW_OVERNIGHT, false);
        var replay = new PortfolioReplayEngine(config, strategy, new OpeningPriceIocFillModel(0, new BigDecimal("1"))).run(frames);
        var report = PortfolioRiskCalculator.calculate(frames, replay, RiskInputs.load(folder));
        require(report.status().equals("PASS"), "generous limits pass signed replay");
        Path exposures = folder.resolve("read-exposures.csv");
        Files.writeString(exposures, report.files().get("exposures.csv"));
        var rows = CsvTable.read(exposures).rows();
        var shortRow = rows.stream().filter(r -> r.get("frame_index").equals("1") && r.get("phase").equals("BAR_OPEN") && r.get("instrument_id").equals("B")).findFirst().orElseThrow();
        require(shortRow.get("signed_units").equals("-2") && shortRow.get("signed_exposure_inr").equals("-20"), "short exposure stays negative");
        require(shortRow.get("gross_notional_inr").equals("20") && shortRow.get("caller_supplied_margin_proxy_inr").equals("14"), "short gross and proxy use absolute quantity");
        Path checkpoints = folder.resolve("read-checkpoints.csv");
        Files.writeString(checkpoints, report.files().get("checkpoints.csv"));
        var totals = CsvTable.read(checkpoints).rows();
        var open = totals.get(2);
        require(open.get("signed_exposure_inr").equals("180") && open.get("gross_notional_inr").equals("220"), "opposing positions do not net gross");
        require(open.get("caller_supplied_margin_proxy_inr").equals("24") && open.get("net_pnl_inr").equals("-2"), "proxy sum and execution fees");
        require(totals.get(3).get("net_pnl_inr").equals("12"), "long and short marked gains less fees");
        require(totals.get(5).get("net_pnl_inr").equals("3") && totals.get(7).get("net_pnl_inr").equals("-13"), "partial close and reversal P&L");
        var newSession = totals.stream().filter(r -> r.get("session_date").equals("2030-05-07")).findFirst().orElseThrow();
        require(new BigDecimal(newSession.get("session_pnl_inr")).compareTo(new BigDecimal(newSession.get("net_pnl_inr")).subtract(new BigDecimal(newSession.get("session_start_net_pnl_inr")))) == 0, "session uses preceding close baseline across overnight holdings");
        require(newSession.get("session_start_net_pnl_inr").equals("-13") && newSession.get("net_pnl_inr").equals("-16") && newSession.get("session_pnl_inr").equals("-3"), "overnight movement and closing fees belong to incoming session");
        require(report.files().get("reconciliation.csv").contains("true,true,true,true"), "cash-flow P&L reconciles partial closes and reversals");
        require(!PortfolioRiskCalculator.exceeds(new BigDecimal("10"), new BigDecimal("10.00")), "equality allowed");
        require(PortfolioRiskCalculator.exceeds(new BigDecimal("10.000000000001"), new BigDecimal("10")), "smallest supported cap excess breaches");
        Instant at = Instant.parse("2030-05-06T03:45:00Z");
        require(PortfolioRiskCalculator.markCoverage(null, at, 0).equals("MISSING_MARK"), "missing mark coverage");
        require(PortfolioRiskCalculator.markCoverage(new PortfolioRiskCalculator.Mark(BigDecimal.ONE, at.minusSeconds(2)), at, 1).equals("STALE_MARK"), "stale mark coverage");
        require(PortfolioRiskCalculator.markCoverage(new PortfolioRiskCalculator.Mark(BigDecimal.ONE, at.minusSeconds(1)), at, 1).equals("COMPLETE"), "mark age boundary inclusive");
        require(PortfolioRiskCalculator.markCoverage(new PortfolioRiskCalculator.Mark(BigDecimal.ONE, at.plusSeconds(1)), at, 1).equals("INVALID_MARK"), "future mark rejected");
        String budgets = Files.readString(folder.resolve("budgets.csv"));
        Files.writeString(folder.resolve("budgets.csv"), budgets.replace(",10000,10000,10000,0,10,", ",220,2,24,0,10,"));
        var boundary = PortfolioRiskCalculator.calculate(frames, replay, RiskInputs.load(folder));
        require(!boundary.files().get("breaches.csv").contains("1,2030-05-06,2030-05-06T03:46:00Z,BAR_OPEN"), "gross, loss and proxy caps allow exact equality");
        Files.writeString(folder.resolve("budgets.csv"), budgets.replace(",10000,10000,10000,0,10,", ",219,1,23,0,10,"));
        var breached = PortfolioRiskCalculator.calculate(frames, replay, RiskInputs.load(folder));
        require(breached.status().equals("BREACH") && breached.files().get("breaches.csv").contains("SESSION_LOSS_INR") && breached.files().get("breaches.csv").contains("CALLER_SUPPLIED_MARGIN_PROXY_INR"), "portfolio budget breaches surfaced");
        Files.writeString(folder.resolve("budgets.csv"), budgets);
        String limits = Files.readString(folder.resolve("limits.csv"));
        Files.writeString(folder.resolve("limits.csv"), limits.replace(",2,10000,", ",1,199,"));
        var capped = PortfolioRiskCalculator.calculate(frames, replay, RiskInputs.load(folder));
        require(capped.files().get("breaches.csv").contains("ABS_UNITS,2,1") && capped.files().get("breaches.csv").contains("GROSS_NOTIONAL_INR,200,199"), "per-instrument position and gross caps");
        Files.writeString(folder.resolve("limits.csv"), limits);
        String margins = Files.readString(folder.resolve("margin-proxies.csv"));
        Files.writeString(folder.resolve("margin-proxies.csv"), margins.lines().filter(l -> !l.startsWith("B,")).reduce("", (a, b) -> a + b + "\n"));
        var missing = PortfolioRiskCalculator.calculate(frames, replay, RiskInputs.load(folder));
        require(missing.status().equals("INCOMPLETE") && missing.files().get("coverage.csv").contains("MISSING_margin-proxies.csv"), "missing instrument proxy incomplete even while flat");
        Files.writeString(checkpoints, missing.files().get("checkpoints.csv"));
        require(CsvTable.read(checkpoints).rows().stream().allMatch(r -> r.get("caller_supplied_margin_proxy_inr").isEmpty()), "missing proxy does not become zero total");
        Files.writeString(folder.resolve("margin-proxies.csv"), margins.replace("2030-05-07", "2030-05-06"));
        require(PortfolioRiskCalculator.calculate(frames, replay, RiskInputs.load(folder)).status().equals("INCOMPLETE"), "expired margin row incomplete");
        Files.writeString(folder.resolve("margin-proxies.csv"), margins);
        String sources = Files.readString(folder.resolve("sources.csv"));
        Files.writeString(folder.resolve("sources.csv"), sources.replace(",2030-05-06,fixture", ",2030-01-01,fixture"));
        require(PortfolioRiskCalculator.calculate(frames, replay, RiskInputs.load(folder)).files().get("coverage.csv").contains("STALE_SOURCE"), "stale source incomplete");
        Files.writeString(folder.resolve("sources.csv"), sources);
        Files.writeString(folder.resolve("budgets.csv"), budgets.replace(",0,10,s", ",0,0,s"));
        var freshness = RiskInputs.load(folder);
        require(freshness.coverage("limits.csv", "A", LocalDate.parse("2030-05-06"), freshness.at("budgets.csv", "PORTFOLIO", LocalDate.parse("2030-05-06"))).equals("COMPLETE"), "source age equality allowed");
        require(freshness.coverage("limits.csv", "A", LocalDate.parse("2030-05-07"), freshness.at("budgets.csv", "PORTFOLIO", LocalDate.parse("2030-05-07"))).equals("STALE_SOURCE"), "source one day past threshold incomplete");
        Files.writeString(folder.resolve("budgets.csv"), budgets.replace("2030-05-07", "2030-05-06"));
        require(PortfolioRiskCalculator.calculate(frames, replay, RiskInputs.load(folder)).status().equals("INCOMPLETE"), "missing portfolio budget incomplete");
        Files.writeString(folder.resolve("budgets.csv"), budgets);
        Files.writeString(folder.resolve("sources.csv"), sources.replace("2030-05-07", "2030-05-06"));
        require(PortfolioRiskCalculator.calculate(frames, replay, RiskInputs.load(folder)).files().get("coverage.csv").contains("MISSING_OR_EXPIRED_SOURCE"), "expired source interval incomplete");
        Files.writeString(folder.resolve("sources.csv"), sources);
        Files.writeString(folder.resolve("sources.csv"), sources.replace(",2030-05-06,fixture", ",2030-05-07,fixture"));
        fails(() -> RiskInputs.load(folder), "future source observation rejected");
        Files.writeString(folder.resolve("sources.csv"), sources);
        for (String bad : List.of(limits + limits.lines().skip(1).findFirst().orElseThrow() + "\n",
                limits.replace(",2,10000,", ",-1,10000,"), limits.replace(",2,10000,", ",2,NaN,"),
                limits.replace("source_id", "unknown_column"), limits.replace(",s\n", ",unknown\n"), limits + "\n")) {
            Files.writeString(folder.resolve("limits.csv"), bad);
            fails(() -> RiskInputs.load(folder), "malformed risk row rejected");
        }
        Files.writeString(folder.resolve("limits.csv"), limits);
        Files.write(folder.resolve("limits.csv"), new byte[]{(byte)0xC3, (byte)0x28});
        fails(() -> RiskInputs.load(folder), "invalid UTF-8 rejected");
        Files.writeString(folder.resolve("limits.csv"), limits);
        Files.delete(folder.resolve("margin-proxies.csv"));
        fails(() -> RiskInputs.load(folder), "missing mandatory margin input file rejected");
        Files.writeString(folder.resolve("margin-proxies.csv"), margins);
        List<SynchronizedBarFrameBuilder.Frame> gaps = new ArrayList<>(frames);
        gaps.remove(1);
        var gapReplay = new PortfolioReplayEngine(config, strategy, new OpeningPriceIocFillModel(0, BigDecimal.ZERO)).run(gaps);
        require(PortfolioRiskCalculator.calculate(gaps, gapReplay, RiskInputs.load(folder)).files().get("coverage.csv").contains("MISSING_FRAME_MARKS"), "allowed replay gaps cannot claim complete audit");
        fails(() -> PortfolioRiskCalculator.calculate(frames.subList(1, frames.size()), replay, RiskInputs.load(folder)), "frame/snapshot mismatch rejected");
    }

    private static void endToEnd(Path root, Path project) throws Exception {
        Files.createDirectories(root);
        try (var paths = Files.list(project.resolve("examples"))) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) Files.copy(file, root.resolve(file.getFileName()));
        }
        String id = "SYN-NIFTY-FUT-2030", second = "SYN-SECOND-FUT-2030";
        Files.writeString(root.resolve("second-bars.csv"), Files.readString(root.resolve("synthetic-futures-bars.csv")).replace(id, second).replace("SYNTHETIC-001", "SYNTHETIC-002"));
        Files.writeString(root.resolve("second-master.csv"), Files.readString(root.resolve("synthetic-instrument-master.csv")).replace(id, second).replace("SYNTHETIC-001", "SYNTHETIC-002"));
        Files.writeString(root.resolve("second.properties"), Files.readString(root.resolve("synthetic-run.properties")).replace("synthetic-instrument-master.csv", "second-master.csv"));
        Path inputs = root.resolve("portfolio-inputs.csv"), settings = root.resolve("portfolio.properties"), risk = root.resolve("risk");
        Files.writeString(inputs, "instrument_id,bars_csv,run_properties,max_abs_lots\n" + id + ",synthetic-futures-bars.csv,synthetic-run.properties,2\n" + second + ",second-bars.csv,second.properties,2\n");
        Files.writeString(settings, "portfolio.pending_intent_boundary_policy=CANCEL_AT_SESSION_BOUNDARY\nportfolio.position_boundary_policy=ALLOW_OVERNIGHT\nportfolio.require_contiguous_frames_within_session=true\nstrategy.fast_bars=2\nstrategy.slow_bars=3\nstrategy.long_lots=1\nfill.slippage_ticks=0\nfill.fee_per_execution_inr=1\n");
        Files.createDirectory(risk);
        writeRisk(risk, List.of(id, second), "2030-05-06", "2030-05-06");
        Path run = PortfolioReplayCsvRunner.run(inputs, settings, root.resolve("runs")).path();
        fails(() -> PortfolioRiskAudit.audit(run, inputs, settings, risk, run.resolve("audit")), "audit cannot mutate the verified run directory");
        Path output = root.resolve("audits");
        var first = PortfolioRiskAudit.audit(run, inputs, settings, risk, output);
        require(first.status().equals("PASS"), "full verified workflow passes");
        var repeated = PortfolioRiskAudit.audit(run, inputs, settings, risk, output);
        require(first.equals(repeated), "same inputs reproduce identical audit and reuse verified artifact");
        require(PortfolioRiskAudit.verify(first.path(), run, inputs, settings, risk).equals(first), "semantic verifier recomputes all bytes");
        require(PortfolioRiskAuditCli.run(new String[]{"verify-artifact", first.path().toString()}) == 0, "dedicated entrypoint verifies");
        String limits = Files.readString(risk.resolve("limits.csv"));
        Files.writeString(risk.resolve("limits.csv"), limits.replace(",2,10000,", ",0,0,"));
        fails(() -> PortfolioRiskAudit.verify(first.path(), run, inputs, settings, risk), "changed risk hashes rejected by semantic verifier");
        Files.writeString(risk.resolve("limits.csv"), limits);
        Path checkpoint = first.path().resolve("checkpoints.csv");
        byte[] checkpointBytes = Files.readAllBytes(checkpoint);
        Files.writeString(checkpoint, "tampered\n", StandardOpenOption.APPEND);
        fails(() -> PortfolioRiskAuditArtifacts.verify(first.path()), "audit output tampering rejected");
        Files.write(checkpoint, checkpointBytes);
        Path manifest = first.path().resolve("audit-manifest.properties");
        byte[] manifestBytes = Files.readAllBytes(manifest);
        Files.writeString(manifest, new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8).replace("status=PASS", "status=BREACH"));
        fails(() -> PortfolioRiskAuditArtifacts.verify(first.path()), "manifest tampering rejected");
        Files.write(manifest, manifestBytes);
        String originalSettings = Files.readString(settings);
        Files.writeString(settings, originalSettings.replace("fill.slippage_ticks=0", "fill.slippage_ticks=1"));
        Path rejectedOutput = root.resolve("rejected-output");
        fails(() -> PortfolioRiskAudit.audit(run, inputs, settings, risk, rejectedOutput), "replay mismatch rejected");
        require(!Files.exists(rejectedOutput), "rejected reconstruction publishes nothing");
        Files.writeString(settings, originalSettings);
        Path equity = run.resolve("equity.csv");
        String originalEquity = Files.readString(equity);
        Files.writeString(equity, originalEquity + "\n");
        fails(() -> PortfolioRiskAudit.audit(run, inputs, settings, risk, rejectedOutput), "run hash tampering rejected");
        // Even a rehashed saved output must match regenerated replay bytes.
        Path runManifest = run.resolve("run-manifest.properties");
        String runText = Files.readString(runManifest);
        Files.writeString(runManifest, runText.replace(RiskInputs.hash(PortfolioRiskAudit.utf8(originalEquity)), RiskInputs.hash(Files.readAllBytes(equity))));
        fails(() -> PortfolioRiskAudit.audit(run, inputs, settings, risk, rejectedOutput), "rehashed replay mismatch rejected");
        Files.writeString(equity, originalEquity); Files.writeString(runManifest, runText);
        String margin = Files.readString(risk.resolve("margin-proxies.csv"));
        Files.writeString(risk.resolve("margin-proxies.csv"), margin.lines().filter(l -> !l.startsWith(second + ",")).reduce("", (a, b) -> a + b + "\n"));
        var incomplete = PortfolioRiskAudit.audit(run, inputs, settings, risk, output);
        require(incomplete.status().equals("INCOMPLETE"), "coverage gaps publish explicit incomplete report");
        require(PortfolioRiskAuditCli.run(new String[]{"verify-artifact", incomplete.path().toString()}) == 3, "incomplete audit has nonzero exit");
        Files.writeString(risk.resolve("margin-proxies.csv"), margin);
        Files.writeString(risk.resolve("limits.csv"), limits.replace(",2,10000,", ",0,0,"));
        var breach = PortfolioRiskAudit.audit(run, inputs, settings, risk, output);
        require(PortfolioRiskAuditCli.run(new String[]{"verify-artifact", breach.path().toString()}) == 2, "breached audit has nonzero exit");
        Files.writeString(risk.resolve("limits.csv"), limits);
        Files.writeString(risk.resolve("sources.csv"), "bad\n");
        require(PortfolioRiskAuditCli.run(new String[]{"audit", run.toString(), inputs.toString(), settings.toString(), risk.toString(), output.toString()}) == 1, "malformed risk data has rejection exit");
        try (var paths = Files.list(output)) {
            require(paths.noneMatch(p -> p.getFileName().toString().startsWith(".risk-audit-staging")), "publication leaves no staging artifacts");
        }
    }

    private static void writeRisk(Path root, List<String> ids, String from, String to) throws Exception {
        StringBuilder limits = new StringBuilder(RiskInputs.HEADERS.get("limits.csv") + "\n");
        StringBuilder margins = new StringBuilder(RiskInputs.HEADERS.get("margin-proxies.csv") + "\n");
        for (String id : ids) {
            limits.append(id).append(',').append(from).append(',').append(to).append(",2,10000,s\n");
            margins.append(id).append(',').append(from).append(',').append(to).append(",5,7,s\n");
        }
        Files.writeString(root.resolve("limits.csv"), limits);
        Files.writeString(root.resolve("margin-proxies.csv"), margins);
        Files.writeString(root.resolve("sources.csv"), RiskInputs.HEADERS.get("sources.csv") + "\ns," + from + "," + to + "," + from + ",fixture source only," + "a".repeat(64) + "\n");
        Files.writeString(root.resolve("budgets.csv"), RiskInputs.HEADERS.get("budgets.csv") + "\n" + from + "," + to + ",10000,10000,10000,0,10,s\n");
    }
    private static List<SynchronizedBarFrameBuilder.Frame> frames() {
        Map<String, List<Bar>> series = new TreeMap<>();
        for (String id : List.of("A", "B")) {
            List<Bar> bars = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                LocalDate day = LocalDate.parse(i < 4 ? "2030-05-06" : "2030-05-07");
                Instant at = day.atTime(3, 45).toInstant(ZoneOffset.UTC).plusSeconds((i < 4 ? i : i - 4) * 60L);
                BigDecimal price = new BigDecimal(id.equals("A") ? (i < 4 ? "100" : "110") : (i < 4 ? "10" : "12"));
                BigDecimal close = new BigDecimal(id.equals("A") ? (i < 4 ? "105" : "115") : (i < 4 ? "8" : "11"));
                bars.add(new Bar(at, day, id, "SYN", "FUT", id, id, ContractKind.FUTURE, "SYN", LocalDate.parse("2030-12-26"),
                        Optional.empty(), Optional.empty(), 1, new BigDecimal("0.05"), "ONE_MINUTE", TimestampRole.BAR_START,
                        price, price.max(close), price.min(close), close, 10L, 10L, "FIXTURE", "a".repeat(64)));
            }
            series.put(id, bars);
        }
        return SynchronizedBarFrameBuilder.build(series);
    }
    @FunctionalInterface private interface Throwing { void run() throws Exception; }
    private static void fails(Throwing call, String message) throws Exception {
        try { call.run(); } catch (IllegalArgumentException | IllegalStateException | java.io.IOException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
}
