package com.mscient.intrapaper.ops.operationaldrill;

import com.mscient.intrapaper.audit.portfolio.PortfolioSettlementAudit;
import com.mscient.intrapaper.audit.reconciliation.ExternalLedgerAudit;
import com.mscient.intrapaper.ops.drill.*;
import com.mscient.intrapaper.ops.evidence.*;
import com.mscient.intrapaper.simulation.portfolio.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.mscient.intrapaper.ops.operationaldrill.OperationalScenario.*;

/** Runs only in an owned child JVM, with its temporary directory redirected into the workspace. */
public final class OperationalDrillWorker {
    private final Path work, evidence, inputs;
    private final DrillFixtures fixtures;
    private final Set<String> inputNames = new TreeSet<>();
    private Path replay;
    private Fault active;
    private record Outcome(Status status, String detail) { }
    private OperationalDrillWorker(Path work) throws IOException {
        this.work = work; evidence = work.resolve("evidence"); inputs = work.resolve("inputs");
        fixtures = new DrillFixtures();
    }
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 21 || args.length != 1) throw new IllegalArgumentException("Java 21 worker required");
        new OperationalDrillWorker(Path.of(args[0])).execute();
    }
    private void execute() throws Exception {
        OperationalScenario scenario = OperationalScenario.load(work.resolve("scenario.drill"));
        Files.createDirectories(evidence); Files.createDirectories(inputs);
        DrillFiles.write(evidence.resolve("scenario.drill"), scenario.bytes());
        DrillFiles.write(evidence.resolve("identity.properties"), "tool=OFFLINE_OPERATIONAL_DRILL_V1\nbuild_sha256="
                + DrillFiles.sha(DrillFiles.read(DrillProcesses.build(), DrillFiles.MAX_BUNDLE))
                + "\nfixture_catalog_sha256=" + fixtures.identity()
                + "\njava_runtime=" + System.getProperty("java.runtime.version") + "\njava_vendor=" + System.getProperty("java.vendor")
                + "\nreplay=PortfolioReplayCsvRunner+PortfolioReplayDeterminismVerifier\nrisk=IncidentDrillOrchestrator+OfflinePortfolioRiskAdapter"
                + "\nsettlement=PortfolioSettlementAudit\nreconciliation=ExternalLedgerAudit\ndaily_ops=DailyOpsEvidenceChecker"
                + "\nprocess_restart=DurableRiskGateway\n");
        StringBuilder rows = new StringBuilder("sequence,action,target,expected,actual,assertion,missing_evidence,execution_error\n");
        int mismatches = 0, missingEvidence = 0, errors = 0;
        for (Step step : scenario.steps()) {
            Path dir = evidence.resolve(String.format(Locale.ROOT, "steps/%03d", step.sequence()));
            Files.createDirectories(dir);
            if (step.action() == Action.RUN && step.target().equals("RECONCILIATION") && replay != null)
                fixtures.bindLedger(inputs, replay.getFileName().toString());
            // Freeze the exact pre-invocation input set, including explicit missing hashes.
            if (step.action() == Action.RUN) freezeInputs(dir);
            Outcome outcome;
            boolean error = false;
            try { outcome = apply(step, dir); }
            catch (Exception unexpected) {
                error = true; errors++;
                String detail = unexpected.getClass().getSimpleName() + ":" + String.valueOf(unexpected.getMessage())
                        .replace(work.toString(), "<workspace>").replace(DrillProcesses.build().toString(), "<build>");
                DrillFiles.write(dir.resolve("error.txt"), detail + "\n");
                outcome = new Outcome(Status.FAIL, "UNEXPECTED_COMPONENT_ERROR");
            }
            if (step.action() != Action.RUN) freezeInputs(dir);
            boolean match = !error && outcome.status() == step.expected();
            if (!match) mismatches++;
            DrillFiles.write(dir.resolve("result.properties"), "sequence=" + step.sequence() + "\naction=" + step.action()
                    + "\ntarget=" + step.target() + "\nexpected=" + step.expected() + "\nactual=" + outcome.status()
                    + "\nassertion=" + (match ? "PASS" : "FAIL") + "\ndetail=" + outcome.detail() + "\nexecution_error=" + error + "\n");
            DrillFiles.write(dir.resolve("log.txt"), "INVOKE " + step.action() + " " + step.target()
                    + "\nRESULT " + outcome.status() + " " + outcome.detail() + "\n");
            List<String> absent = step.evidence().stream().filter(name -> !Files.isRegularFile(dir.resolve(name), LinkOption.NOFOLLOW_LINKS)).toList();
            missingEvidence += absent.size();
            rows.append(step.sequence()).append(',').append(step.action()).append(',').append(step.target()).append(',')
                    .append(step.expected()).append(',').append(outcome.status()).append(',').append(match ? "PASS" : "FAIL")
                    .append(',').append(String.join(";", absent)).append(',').append(error).append('\n');
        }
        Status status = mismatches > 0 ? Status.FAIL : missingEvidence > 0 ? Status.INCOMPLETE : Status.PASS;
        DrillFiles.write(evidence.resolve("results.csv"), rows.toString());
        DrillFiles.write(evidence.resolve("report.properties"), "format=OFFLINE_OPERATIONAL_REPORT_V1\nscenario_id=" + scenario.id()
                + "\nclassification=SYNTHETIC_ONLY\nstatus=" + status + "\nstep_count=" + scenario.steps().size()
                + "\nexpectation_mismatches=" + mismatches + "\nmissing_evidence=" + missingEvidence + "\nexecution_errors=" + errors
                + "\nactive_fault=" + (active == null ? "NONE" : active) + "\nrecovery_complete=" + (active == null)
                + "\nscenario_sha256=" + DrillFiles.sha(scenario.bytes())
                + "\noperational_readiness_eligible=false\ng4_operating_session=false\n");
        System.out.println("WORKER_COMPLETE " + status);
    }
    private void freezeInputs(Path dir) throws IOException {
        SortedMap<String, byte[]> current = DrillFiles.snapshot(inputs);
        inputNames.addAll(current.keySet());
        StringBuilder hashes = new StringBuilder("path,sha256,bytes\n");
        for (String name : inputNames) {
            byte[] bytes = current.get(name);
            hashes.append(name).append(',').append(bytes == null ? "" : DrillFiles.sha(bytes))
                    .append(',').append(bytes == null ? "" : bytes.length).append('\n');
        }
        DrillFiles.materialize(dir.resolve("inputs"), current);
        DrillFiles.write(dir.resolve("inputs.csv"), hashes.toString());
        if (replay != null) DrillFiles.write(dir.resolve("replay-reference.properties"),
                "run_id=" + replay.getFileName() + "\nmanifest_sha256="
                        + DrillFiles.sha(DrillFiles.read(replay.resolve("run-manifest.properties"), DrillFiles.MAX_FILE)) + "\n");
    }
    private Outcome apply(Step step, Path dir) throws Exception {
        switch (step.action()) {
            case SETUP -> { fixtures.baseline(inputs); return new Outcome(Status.PASS, "PACKAGED_SYNTHETIC_FIXTURES"); }
            case INJECT -> { active = Fault.valueOf(step.target()); fixtures.apply(inputs, active, false); return new Outcome(Status.PASS, "FAULT_INJECTED"); }
            case RECOVER -> { fixtures.apply(inputs, active, true); active = null; return new Outcome(Status.PASS, "RECOVERY_INPUTS_SUPPLIED_RECHECK_REQUIRED"); }
            case RUN -> { return component(Component.valueOf(step.target()), dir); }
        }
        throw new IllegalStateException("Unknown step");
    }
    private Outcome component(Component component, Path dir) throws Exception {
        Path input = inputs.resolve("replay/inputs.csv"), settings = inputs.resolve("replay/portfolio.properties");
        Path runtime = work.resolve("runs/" + dir.getFileName()); Files.createDirectories(runtime);
        switch (component) {
            case REPLAY -> {
                Path saved;
                try { saved = PortfolioReplayCsvRunner.run(input, settings, runtime.resolve("replays")).path(); }
                catch (NoSuchFileException expected) {
                    if (active != Fault.MISSING_INPUT || !Path.of(expected.getFile()).equals(inputs.resolve("replay/future.csv"))) throw expected;
                    DrillFiles.write(dir.resolve("rejection.txt"), "NoSuchFileException:replay/future.csv\n");
                    return new Outcome(Status.FAIL, "MISSING_SYNTHETIC_BARS_REJECTED");
                }
                PortfolioReplayDeterminismVerifier.verify(input, settings, saved);
                replay = saved;
                copy(saved, dir.resolve("component"));
                return new Outcome(Status.PASS, "REPLAY_REGENERATED_AND_VERIFIED");
            }
            case RISK -> {
                var result = IncidentDrillOrchestrator.run(DrillScenario.load(inputs.resolve("risk/scenario.drill")));
                DrillFiles.materialize(dir.resolve("component"), DrillEvidence.bundle(result));
                // This is the native incident runner's status: unresolved acknowledgements and
                // unreset halts remain INCOMPLETE even when every safety assertion passes.
                return new Outcome(Status.valueOf(result.status().name()), "NATIVE_INCIDENT_RESULT");
            }
            case SETTLEMENT -> {
                if (replay == null) return new Outcome(Status.INCOMPLETE, "VERIFIED_REPLAY_UNAVAILABLE");
                Path costs = inputs.resolve("settlement/costs.csv"), events = inputs.resolve("settlement/events.csv");
                if (!Files.exists(costs)) costs = null;
                Path saved;
                try { saved = PortfolioSettlementAudit.audit(replay, input, settings, events, costs, runtime.resolve("audit")).path(); }
                catch (IllegalArgumentException expected) {
                    if (active != Fault.SETTLEMENT_MISMATCH || !"Settlement contract/method/date mismatch: F".equals(expected.getMessage())) throw expected;
                    DrillFiles.write(dir.resolve("rejection.txt"), "IllegalArgumentException:" + expected.getMessage() + "\n");
                    return new Outcome(Status.FAIL, "SETTLEMENT_METHOD_MISMATCH_REJECTED");
                }
                PortfolioSettlementAudit.verify(saved, replay, input, settings, events, costs);
                copy(saved, dir.resolve("component"));
                DrillFiles.write(dir.resolve("observations.json"), costs == null
                        ? "{\"settlement_cost_inr\":null,\"settlement_cost_status\":\"NOT_MODELED\"}\n"
                        : "{\"settlement_cost_status\":\"CALLER_SUPPLIED\"}\n");
                return new Outcome(costs == null ? Status.INCOMPLETE : Status.PASS,
                        costs == null ? "NOT_MODELED_COST_IS_UNKNOWN" : "SETTLEMENT_VERIFIED");
            }
            case RECONCILIATION -> {
                if (replay == null) return new Outcome(Status.INCOMPLETE, "VERIFIED_REPLAY_UNAVAILABLE");
                Path manifest = inputs.resolve("external/manifest.properties");
                var result = ExternalLedgerAudit.audit(ExternalLedgerAudit.ReplayKind.PORTFOLIO, replay, input, settings, manifest, runtime.resolve("audit"));
                ExternalLedgerAudit.verify(result.path(), ExternalLedgerAudit.ReplayKind.PORTFOLIO, replay, input, settings, manifest);
                copy(result.path(), dir.resolve("component"));
                return new Outcome(Status.valueOf(result.status().name()), "NATIVE_LEDGER_RESULT");
            }
            case DAILY_OPS -> {
                var result = DailyOpsEvidenceChecker.check(inputs.resolve("daily/session-evidence.json"));
                DrillFiles.write(dir.resolve("component/report.json"), result.bytes());
                return new Outcome(Status.valueOf(result.status().name()), "NATIVE_DAILY_EVIDENCE_RESULT");
            }
            case PROCESS_RESTART -> {
                Path state = runtime.resolve("state"); Files.createDirectories(state);
                for (String mode : List.of("seed", "recover")) {
                    Path log = runtime.resolve(mode + ".log");
                    int exit = DrillProcesses.run(RiskRestartWorker.class, runtime, log, mode, state.toString());
                    DrillFiles.write(dir.resolve("component/" + mode + ".log"), Files.readString(log).replace("\r\n", "\n"));
                    if (exit != 0) throw new IOException("Fixed restart worker failed: " + mode);
                }
                copy(state, dir.resolve("component/state"));
                return new Outcome(Status.PASS, "ABRUPT_JVM_EXIT_RECOVERY_AND_IDEMPOTENCY_VERIFIED");
            }
        }
        throw new IllegalStateException("Unknown component");
    }
    private static void copy(Path source, Path target) throws IOException { DrillFiles.materialize(target, DrillFiles.snapshot(source)); }
}
