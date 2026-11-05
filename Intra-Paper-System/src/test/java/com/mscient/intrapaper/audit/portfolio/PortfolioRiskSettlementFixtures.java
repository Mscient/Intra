package com.mscient.intrapaper.audit.portfolio;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.provenance.CrossSessionPositionPolicy;
import com.mscient.intrapaper.reference.*;
import com.mscient.intrapaper.simulation.portfolio.*;
import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Reproducible arithmetic fixtures plus adversarial mutations; no market data or account inputs. */
public final class PortfolioRiskSettlementFixtures {
    private static final String HASH = "a".repeat(64);
    private static final LocalDate DAY = LocalDate.parse("2030-05-06");
    private static final Instant START = Instant.parse("2030-05-06T03:45:00Z");
    private static int checks;
    record Fixture(List<SynchronizedBarFrameBuilder.Frame> frames, PortfolioReplayEngine.Result result) { }

    public static void main(String[] args) throws Exception {
        boolean keep = args.length > 1;
        Path root = keep ? Path.of(args[1]).toAbsolutePath() : Files.createTempDirectory("risk-settlement-fixtures-");
        if (keep) Files.createDirectory(root); // Never overwrite a previous fixture export.
        try {
            Path riskPath = root.resolve("risk"); Files.createDirectory(riskPath);
            Files.writeString(riskPath.resolve("limits.csv"), RiskInputs.HEADERS.get("limits.csv")
                    + "\nF,2030-05-06,2030-05-06,10,10000,s\nO,2030-05-06,2030-05-06,10,10000,s\n");
            Files.writeString(riskPath.resolve("margin-proxies.csv"), RiskInputs.HEADERS.get("margin-proxies.csv")
                    + "\nF,2030-05-06,2030-05-06,5,7,s\nO,2030-05-06,2030-05-06,5,7,s\n");
            Files.writeString(riskPath.resolve("sources.csv"), RiskInputs.HEADERS.get("sources.csv")
                    + "\ns,2030-05-06,2030-05-06,2030-05-06,synthetic unauthenticated fixture," + HASH + "\n");
            Files.writeString(riskPath.resolve("budgets.csv"), RiskInputs.HEADERS.get("budgets.csv")
                    + "\n2030-05-06,2030-05-06,10000,10000,10000,0,10,s\n");
            RiskInputs risk = RiskInputs.load(riskPath);
            StringBuilder matrix = new StringBuilder("case_id,expected_status,expected_modeled_net_pnl_inr\n");
            for (int futureSign : List.of(1, -1)) for (int optionSign : List.of(1, -1))
                for (OptionRight right : OptionRight.values()) for (String payoff : List.of("0", "5")) {
                    String name = right + "-F" + futureSign + "-O" + optionSign + "-payoff" + payoff;
                    Fixture fixture = fixture(futureSign, optionSign, right, "105", payoff, true, false);
                    var report = publish(root, name, fixture, risk);
                    BigDecimal expected = d("19.75").multiply(d(Integer.toString(futureSign)))
                            .add(d(payoff).multiply(d("2")).subtract(d("18.25")).multiply(d(Integer.toString(optionSign))))
                            .subtract(d("7.19"));
                    check(report.status().equals("PASS"), "signed fixture passes: " + name);
                    assertBridge(root.resolve(name), expected, false);
                    var bridge = CsvTable.read(root.resolve(name).resolve("settlement-reconciliation.csv")).rows();
                    equal("200.166666666666666667", bridge.getFirst().get("released_basis_inr"), "exact residual basis");
                    equal(Integer.toString(2 * futureSign), bridge.getFirst().get("pre_signed_units"), "future sign");
                    equal(Integer.toString(2 * optionSign), bridge.getLast().get("pre_signed_units"), "option sign");
                    check(bridge.stream().allMatch(r -> r.get("value_classification").equals("CALLER_SUPPLIED_UNAUTHENTICATED")), "values stay unauthenticated");
                    if (payoff.equals("0")) equal("0", bridge.getLast().get("signed_proceeds_inr"), "zero payoff is explicit");
                    matrix.append(name).append(",PASS,").append(expected.toPlainString()).append('\n');
                }
            for (int sign : List.of(1, -1)) {
                String name = "negative-future-" + sign;
                Fixture fixture = fixture(sign, sign, OptionRight.CALL, "-2", "0", true, false);
                publish(root, name, fixture, risk);
                BigDecimal expected = d("-212.5").multiply(d(Integer.toString(sign))).subtract(d("7.19"));
                assertBridge(root.resolve(name), expected, false);
                matrix.append(name).append(",PASS,").append(expected).append('\n');
            }
            Fixture omitted = fixture(1, 1, OptionRight.CALL, "105", "5", false, false);
            var missing = publish(root, "omitted-charges", omitted, risk);
            check(missing.status().equals("INCOMPLETE") && missing.coverageIssues() == 2, "omitted charges incomplete");
            assertBridge(root.resolve("omitted-charges"), d("5.5"), true);
            matrix.append("omitted-charges,INCOMPLETE,5.5\n");
            Fixture covered = fixture(1, 1, OptionRight.CALL, "105", "5", true, false);
            var option = covered.result().settlements().getLast();
            var absentCategories = new PortfolioSettlement(option.event(), option.signedUnits(), option.releasedBasisInr(),
                    option.signedProceedsInr(), option.realizedGrossPnlInr(), option.costModel(), List.of());
            var insufficient = new Fixture(covered.frames(), replace(covered.result(),
                    List.of(covered.result().settlements().getFirst(), absentCategories), covered.result().snapshots()));
            check(publish(root, "insufficient-charge-categories", insufficient, risk).status().equals("INCOMPLETE"),
                    "absent zero-valued category lines cannot establish charge coverage");
            matrix.append("insufficient-charge-categories,INCOMPLETE,4.31\n");
            Fixture flat = fixture(1, 1, OptionRight.PUT, "105", "0", false, true);
            check(publish(root, "flat", flat, risk).status().equals("PASS"), "flat event needs no unit-based charge");
            assertBridge(root.resolve("flat"), BigDecimal.ZERO, false);
            matrix.append("flat,PASS,0\n");
            String margins = Files.readString(riskPath.resolve("margin-proxies.csv"));
            Files.writeString(riskPath.resolve("margin-proxies.csv"), margins.lines().filter(l -> !l.startsWith("O,")).reduce("", (a, b) -> a + b + "\n"));
            check(publish(root, "flat-missing-proxy", flat, RiskInputs.load(riskPath)).status().equals("INCOMPLETE"), "flat still requires proxy coverage");
            var flatLast = CsvTable.read(root.resolve("flat-missing-proxy/checkpoints.csv")).rows().getLast();
            check(flatLast.get("caller_supplied_margin_proxy_inr").isEmpty(), "flat missing proxy stays blank");
            matrix.append("flat-missing-proxy,INCOMPLETE,0\n");
            Files.writeString(riskPath.resolve("margin-proxies.csv"), margins);
            String budgets = Files.readString(riskPath.resolve("budgets.csv"));
            Files.writeString(riskPath.resolve("budgets.csv"), budgets.replace(",10000,10000,10000,", ",10000,1,10000,"));
            var lowerBound = publish(root, "omitted-charges-known-loss", fixture(1, 1, OptionRight.CALL, "-2", "0", false, false), RiskInputs.load(riskPath));
            check(lowerBound.status().equals("INCOMPLETE") && lowerBound.files().get("breaches.csv").contains("MODELED_SESSION_LOSS_LOWER_BOUND_INR"), "known breach survives unknown charges");
            Files.writeString(riskPath.resolve("budgets.csv"), budgets);
            matrix.append("omitted-charges-known-loss,INCOMPLETE,-218.5\n");
            malformedAndMismatched(root, Path.of(args[0]), risk);
            Files.writeString(root.resolve("expected.csv"), matrix);
        } finally { if (!keep) PortfolioRiskAudit.cleanup(root); }
        System.out.println("PortfolioRiskSettlement: " + checks + " focused checks passed" + (keep ? "; fixtures: " + root : ""));
    }

    private static PortfolioRiskCalculator.Report publish(Path root, String name, Fixture fixture, RiskInputs risk) throws Exception {
        var report = PortfolioRiskCalculator.calculate(fixture.frames(), fixture.result(), risk);
        check(report.equals(PortfolioRiskCalculator.calculate(fixture.frames(), fixture.result(), risk)), "deterministic fixture bytes");
        Path folder = root.resolve(name); Files.createDirectory(folder);
        for (var file : report.files().entrySet()) Files.writeString(folder.resolve(file.getKey()), file.getValue());
        return report;
    }

    private static void assertBridge(Path folder, BigDecimal expected, boolean missing) throws Exception {
        var checkpoints = CsvTable.read(folder.resolve("checkpoints.csv")).rows();
        var last = checkpoints.getLast(); var before = checkpoints.get(checkpoints.size() - 2);
        var events = CsvTable.read(folder.resolve("settlement-reconciliation.csv")).rows();
        check(checkpoints.size() == 7 && events.size() == 2, "same timestamp settles once");
        equal(expected.toPlainString(), last.get("modeled_net_pnl_inr"), "independent cash-flow expected total");
        BigDecimal adjustment = events.stream().map(r -> d(r.get("modeled_net_adjustment_inr"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        equal(expected.toPlainString(), d(before.get("net_pnl_inr")).add(adjustment).toPlainString(), "pre net plus adjustment reconciles without double counting");
        equal("0", last.get("gross_notional_inr"), "zero final exposure");
        equal("0", last.get("caller_supplied_margin_proxy_inr"), "zero final covered proxy");
        check(last.get("net_pnl_inr").isEmpty() == missing, "unknown net stays blank");
        check(events.stream().allMatch(r -> r.get("net_adjustment_inr").isEmpty() == missing
                && r.get("settlement_charges_inr").isEmpty() == missing), "unknown charges and net adjustment stay blank");
    }

    private static Fixture fixture(int futureSign, int optionSign, OptionRight right, String futureValue, String payoff, boolean costs, boolean flat) {
        List<SynchronizedBarFrameBuilder.Frame> frames = new ArrayList<>();
        List<PortfolioExecution> executions = new ArrayList<>();
        List<PortfolioAccountingLedger.Snapshot> snapshots = new ArrayList<>();
        var ledger = new PortfolioAccountingLedger(); long sequence = 0;
        for (int i = 0; i < 3; i++) {
            Map<String, Bar> bars = new TreeMap<>(); List<PortfolioExecution> fills = new ArrayList<>();
            for (String id : List.of("F", "O")) {
                BigDecimal price = d((id.equals("F") ? List.of("100.05", "100.10", "110") : List.of("10.05", "10.10", "12")).get(i));
                Bar bar = new Bar(START.plusSeconds(60L * i), DAY, id, "SYN", "DERIV", id + "-TOKEN", id,
                        id.equals("F") ? ContractKind.FUTURE : ContractKind.OPTION, "SYNTHETIC", DAY,
                        id.equals("F") ? Optional.empty() : Optional.of(d("100")), id.equals("F") ? Optional.empty() : Optional.of(right),
                        1, d("0.05"), "ONE_MINUTE", TimestampRole.BAR_START, price, price, price, price, 10L, 20L, "SYNTHETIC", HASH);
                bars.put(id, bar);
                int quantity = (i == 0 ? 1 : i == 1 ? 2 : -1) * (id.equals("F") ? futureSign : optionSign);
                if (!flat) fills.add(new PortfolioExecution("FILL-" + (++sequence), sequence, id, bar.timestamp(),
                        quantity > 0 ? PortfolioExecution.Side.BUY : PortfolioExecution.Side.SELL, Math.abs(quantity), price, BigDecimal.ONE));
            }
            var frame = new SynchronizedBarFrameBuilder.Frame(START.plusSeconds(60L * i), DAY, bars);
            frames.add(frame); executions.addAll(fills);
            snapshots.add(ledger.mark(frame, fills)); snapshots.add(ledger.markToClose(frame));
        }
        var events = new ArrayList<PortfolioSettlementEvents.Event>(); var entries = new ArrayList<PortfolioSettlementCostSchedule.Entry>();
        for (String id : List.of("F", "O")) {
            var contract = PortfolioSettlementContract.from(frames.getLast().barsByInstrument().get(id));
            var event = new PortfolioSettlementEvents.Event("SET-" + id, contract, DAY, START.plusSeconds(180),
                    d(id.equals("F") ? futureValue : payoff), id.equals("O") && !payoff.equals("0"),
                    Optional.of(d("100")), Optional.of("synthetic turnover"), "synthetic settlement value");
            events.add(event);
            var side = (id.equals("F") ? futureSign : optionSign) > 0 ? PortfolioSettlementCostSchedule.PositionSide.LONG : PortfolioSettlementCostSchedule.PositionSide.SHORT;
            for (var category : PortfolioSettlementCostSchedule.Category.values()) {
                if (category == PortfolioSettlementCostSchedule.Category.OPTION_EXERCISE && !event.optionExercise()) continue;
                entries.add(new PortfolioSettlementCostSchedule.Entry(id + "-" + category, DAY, Optional.of(DAY.plusDays(1)), contract,
                        side, category, "FIXTURE", PortfolioSettlementCostSchedule.Basis.FIXED_PER_SETTLEMENT,
                        d(id.equals("F") ? "1.005" : "0"), 2, RoundingMode.HALF_UP, d("1800"), true, 2, RoundingMode.HALF_UP, "synthetic tariff"));
            }
        }
        var schedule = costs ? Optional.of(new PortfolioSettlementCostSchedule("FIXTURE", entries, HASH)) : Optional.<PortfolioSettlementCostSchedule>empty();
        var batch = ledger.settleFinalSession(frames.getLast(), new PortfolioSettlementEvents(events, HASH), schedule);
        snapshots.addAll(batch.snapshots());
        var config = new PortfolioReplayEngine.Config(Map.of("F", 10, "O", 10), PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY,
                CrossSessionPositionPolicy.ALLOW_OVERNIGHT, true, PortfolioSettlementPolicy.EXPLICIT_FINAL_SESSION, Set.of("F", "O"));
        return new Fixture(frames, new PortfolioReplayEngine.Result("fixture", Map.of(), "fixture", Map.of(), "fixture", config,
                HASH, Optional.empty(), Optional.empty(), HASH, HASH, HASH, HASH, List.of(), List.of(), executions, snapshots,
                "REPLAY_DIAGNOSTIC_ONLY", Optional.of(HASH), costs ? Optional.of("FIXTURE") : Optional.empty(),
                costs ? Optional.of(HASH) : Optional.empty(), batch.settlements()));
    }

    private static void malformedAndMismatched(Path root, Path project, RiskInputs risk) throws Exception {
        Fixture fixture = fixture(1, -1, OptionRight.PUT, "105", "5", true, false);
        var result = fixture.result(); var first = result.settlements().getFirst(); var event = first.event();
        var wrongBasis = new PortfolioSettlement(event, first.signedUnits(), first.releasedBasisInr().add(BigDecimal.ONE),
                first.signedProceedsInr(), first.realizedGrossPnlInr().subtract(BigDecimal.ONE), first.costModel(), first.chargeLines());
        reject(() -> calculate(fixture, replace(result, List.of(wrongBasis, result.settlements().getLast()), result.snapshots()), risk), "wrong residual basis");
        var wrongUnits = new PortfolioSettlement(event, 1, first.releasedBasisInr(), event.valuePerUnit(),
                event.valuePerUnit().subtract(first.releasedBasisInr()), first.costModel(), first.chargeLines());
        reject(() -> calculate(fixture, replace(result, List.of(wrongUnits, result.settlements().getLast()), result.snapshots()), risk), "wrong signed units");
        var duplicateEvent = new PortfolioSettlementEvents.Event(event.eventId(), result.settlements().getLast().event().contract(), DAY,
                event.timestamp(), d("5"), true, Optional.empty(), Optional.empty(), "fixture");
        var duplicate = new PortfolioSettlement(duplicateEvent, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "DATED_SCHEDULE", List.of());
        reject(() -> calculate(fixture, replace(result, List.of(first, duplicate), result.snapshots()), risk), "duplicate event id");
        reject(() -> calculate(fixture, replace(result, List.of(result.settlements().getLast(), first), result.snapshots()), risk), "out of order events");
        reject(() -> calculate(fixture, replace(result, List.of(first), result.snapshots()), risk), "unmatched target");
        reject(() -> calculate(fixture, replace(result, result.settlements(), result.snapshots().subList(0, 6)), risk), "missing settled snapshot");
        var last = result.snapshots().getLast();
        var badSnapshot = new PortfolioAccountingLedger.Snapshot(last.timestamp(), last.phase(), last.positions(), last.realizedGrossPnlInr().add(BigDecimal.ONE),
                last.feesInr(), last.unrealizedPnlInr().subtract(BigDecimal.ONE), last.netPnlInr());
        var snapshots = new ArrayList<>(result.snapshots()); snapshots.set(6, badSnapshot);
        reject(() -> calculate(fixture, replace(result, result.settlements(), snapshots), risk), "offsetting realized/unrealized mismatch");
        var line = first.chargeLines().getFirst();
        var wrongLine = new PortfolioSettlementCostSchedule.Line(line.eventId(), line.scheduleId(), line.sourceFileSha256(), line.entry(),
                line.calculationBase(), line.chargeInr().add(BigDecimal.ONE), line.gstTaxableBaseInr(), line.gstInr());
        var wrongCharge = new PortfolioSettlement(event, first.signedUnits(), first.releasedBasisInr(), first.signedProceedsInr(),
                first.realizedGrossPnlInr(), first.costModel(), List.of(wrongLine));
        reject(() -> calculate(fixture, replace(result, List.of(wrongCharge, result.settlements().getLast()), result.snapshots()), risk), "charge math mismatch");
        var duplicateCharge = new PortfolioSettlement(event, first.signedUnits(), first.releasedBasisInr(), first.signedProceedsInr(),
                first.realizedGrossPnlInr(), first.costModel(), List.of(line, line));
        reject(() -> calculate(fixture, replace(result, List.of(duplicateCharge, result.settlements().getLast()), result.snapshots()), risk), "duplicate charges");
        String source = Files.readString(project.resolve("examples/portfolio-settlement/settlement-events.csv"));
        Path input = root.resolve("malformed-events.csv");
        for (String bad : List.of(source.replace(",5,true", ",-1,true"), source.replace(",110,false", ",NaN,false"),
                source.replace("SET-O", "SET-F"), source + source.lines().skip(1).findFirst().orElseThrow() + "\n", source.replace("event_id", "bad_header"))) {
            Files.writeString(input, bad); reject(() -> PortfolioSettlementEvents.load(input), "malformed event file");
        }
        var replay = PortfolioReplayCsvRunner.replay(project.resolve("examples/portfolio-settlement/inputs.csv"), project.resolve("examples/portfolio-settlement/portfolio.properties"));
        for (String bad : List.of(source.replace("F-TOKEN", "UNMATCHED"), source.replace("09:19:00", "09:18:00"),
                source.lines().filter(l -> !l.startsWith("SET-O,")).reduce("", (a, b) -> a + b + "\n"))) {
            Files.writeString(input, bad);
            reject(() -> PortfolioSettlementEvents.validateSelection(replay.dataset(), Set.of("F", "O"), Optional.of(PortfolioSettlementEvents.load(input))), "unmatched event selection");
        }
    }

    private static void calculate(Fixture fixture, PortfolioReplayEngine.Result result, RiskInputs risk) { PortfolioRiskCalculator.calculate(fixture.frames(), result, risk); }
    private static PortfolioReplayEngine.Result replace(PortfolioReplayEngine.Result r, List<PortfolioSettlement> events, List<PortfolioAccountingLedger.Snapshot> snapshots) {
        return new PortfolioReplayEngine.Result(r.strategyId(), r.strategyParameters(), r.fillModelId(), r.fillModelParameters(), r.buildCommit(), r.config(),
                r.validatedDatasetsSha256(), r.executionCostScheduleId(), r.executionCostScheduleSourceFileSha256(), r.frameInputsSha256(),
                r.strategyFingerprintSha256(), r.fillModelFingerprintSha256(), r.replayFingerprintSha256(), r.decisions(), r.orders(), r.executions(), snapshots,
                r.classification(), r.settlementEventsSourceFileSha256(), r.settlementCostScheduleId(), r.settlementCostScheduleSourceFileSha256(), events);
    }
    private static BigDecimal d(String value) { return new BigDecimal(value); }
    private static void equal(String expected, String actual, String label) { check(d(expected).compareTo(d(actual)) == 0, label + ": " + expected + " != " + actual); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; }
    @FunctionalInterface interface Attempt { void run() throws Exception; }
    private static void reject(Attempt attempt, String label) throws Exception {
        try { attempt.run(); } catch (IllegalArgumentException | IllegalStateException | java.io.IOException expected) { checks++; return; }
        throw new AssertionError("Accepted " + label);
    }
}
