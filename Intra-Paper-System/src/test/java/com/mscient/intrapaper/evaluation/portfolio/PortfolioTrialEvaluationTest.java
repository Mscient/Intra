package com.mscient.intrapaper.evaluation.portfolio;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.data.SynchronizedBarFrameBuilder;
import com.mscient.intrapaper.data.ValidatedPortfolioDataset;
import com.mscient.intrapaper.evaluation.EvaluationSplitPlan;
import com.mscient.intrapaper.evaluation.ResearchOutcomeLedger;
import com.mscient.intrapaper.evaluation.ResearchTrialLedger;
import com.mscient.intrapaper.evaluation.SessionReturnStatistics;
import com.mscient.intrapaper.provenance.CrossSessionPositionPolicy;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.simulation.portfolio.OpeningPriceIocFillModel;
import com.mscient.intrapaper.simulation.portfolio.PortfolioAccountingLedger;
import com.mscient.intrapaper.simulation.portfolio.PortfolioReplayArtifactWriter;
import com.mscient.intrapaper.simulation.portfolio.PortfolioReplayEngine;
import com.mscient.intrapaper.simulation.portfolio.PortfolioStrategy;
import com.mscient.intrapaper.simulation.portfolio.PortfolioTarget;
import com.mscient.intrapaper.simulation.portfolio.PortfolioSettlementPolicy;
import com.mscient.intrapaper.simulation.portfolio.PortfolioSettlementEvents;
import com.mscient.intrapaper.simulation.portfolio.PortfolioSettlementCostSchedule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;

import static com.mscient.intrapaper.evaluation.portfolio.PortfolioEvaluationJson.object;

/** Real synthetic replay artifacts exercise bindings, coverage, exclusion, provenance, and deterministic output. */
public final class PortfolioTrialEvaluationTest {
    private static int checks;
    private static final String A = "SYN-A";
    private static final String B = "SYN-B";
    private static final String SOURCE = "SYNTHETIC_FIXTURE";
    private static final String RULE_REFERENCE = "synthetic fixture only; not an exchange ruleset";

    private PortfolioTrialEvaluationTest() { }

    public static void main(String[] args) throws Exception {
        Path project = args.length == 0 ? Path.of(".").toAbsolutePath() : Path.of(args[0]);
        aggregateReturnsAndDeterminism(project);
        strictCatalogAndIdentityMismatch(project);
        registeredRunIdentityMismatch(project);
        partitionsAndMissingSessions(project);
        holdoutExcludedEvenWhenSealed(project);
        completeTrialCountsAndOutcomeProvenance(project);
        tamperedArtifactsAndLedgers(project);
        cliCreateNewAndFailure(project);
        statisticalEdgeCases();
        settlementReturnsAndHoldout(project);
        System.out.println("PortfolioTrialEvaluation: " + checks + " focused checks passed");
    }

    private static void settlementReturnsAndHoldout(Path project) throws Exception {
        try (Fixture fixture = new Fixture(project, true, true)) {
            var report = fixture.analyze(); var first = report.trials().getFirst(); var last = first.sessions().getLast();
            decimal(last.realizedGrossPnlChangeInr(), "5", "settlement realized change includes both signed positions");
            decimal(last.feesPaidInr(), "4.38", "execution and rounded settlement charges counted once");
            decimal(last.netPnlChangeInr(), "0.62", "session ends after both timed settlements");
            decimal(last.sessionReturn(), "0.0000062", "settlement net divided by fixed capital");
            decimal(first.totalNetPnlInr(), "27.62", "settlement is included in validation totals");
            decimal(report.trials().getLast().sessions().getLast().netPnlChangeInr(), "10.62", "short settlement gains enter returns");
            require(first.includedSettlementCount() == 2 && first.excludedHoldoutSettlementCount() == 0
                    && first.excludedHoldoutSessionCount() == 2, "prefix evaluates settlements and retains the frozen holdout plan");
            require(report.toJson().contains("DEVELOPMENT_VALIDATION_ONLY") && report.toJson().equals(fixture.analyze().toJson()), "scope is explicit and output deterministic");
            Path output = fixture.root.resolve("settlement-evaluation.json");
            try (PrintStream sink = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
                require(PortfolioTrialEvaluationCli.run(new String[]{"evaluate", fixture.ledger.toString(), fixture.catalog.toString(),
                        fixture.runs.toString(), "4", output.toString()}, sink, sink) == 0, "settlement prefix evaluates through the CLI");
            }
            require(Files.readString(output).equals(report.toJson()), "CLI emits the verified settlement-aware report");
            Files.writeString(fixture.catalog, Files.readString(fixture.catalog).replace("DEVELOPMENT_VALIDATION_ONLY", "ALL_SPLIT_SESSIONS"));
            fails(fixture::analyze, "missing holdout artifacts require an explicit prefix scope");
            fixture.writeCatalog();
            Path run = fixture.firstRun();
            rewriteCsv(run.resolve("settlements.csv"), row -> { row.put("released_basis_inr", "101"); return row; });
            refreshManifest(run, Map.of()); fixture.repinFirstRun();
            fails(fixture::analyze, "included settlement arithmetic is fully reconciled even after rehashing");
        }
        try (Fixture fixture = new Fixture(project, true, true, false)) {
            var first = fixture.analyze().trials().getFirst();
            decimal(first.totalNetPnlInr(), "33", "flat settlement events do not change returns");
            decimal(first.sessions().getLast().feesPaidInr(), "4", "flat settlement events do not charge fixed fees");
            require(first.includedSettlementCount() == 2, "flat events retain audit coverage");
        }
        try (Fixture fixture = new Fixture(project, true, false)) {
            var original = fixture.analyze();
            require(original.trials().getFirst().includedSettlementCount() == 0
                    && original.trials().getFirst().excludedHoldoutSettlementCount() == 2, "full-split terminal settlements belong to excluded holdout");
            decimal(original.trials().getFirst().totalNetPnlInr(), "33", "holdout settlement cannot change evaluated returns");
            for (var catalogRow : fixture.rows) {
                Path run = fixture.runs.resolve((String) catalogRow.get("run_id"));
                rewriteCsv(run.resolve("equity.csv"), row -> {
                    if (row.get("timestamp_utc").startsWith(fixture.dates.get(8).toString())
                            || row.get("timestamp_utc").startsWith(fixture.dates.get(9).toString())) {
                        for (String key : List.of("average_open_price", "realized_gross_pnl_inr", "fees_inr", "unrealized_pnl_inr",
                                "total_realized_gross_pnl_inr", "total_fees_inr", "total_unrealized_pnl_inr", "net_pnl_inr")) row.put(key, "SEALED_HOLDOUT");
                    }
                    return row;
                });
                rewriteCsv(run.resolve("settlements.csv"), row -> {
                    for (String key : List.of("settlement_value_per_unit_inr", "charge_turnover_per_unit_inr", "released_basis_inr", "signed_proceeds_inr",
                            "realized_gross_pnl_inr", "expiry_settlement_fees_inr", "option_exercise_fees_inr", "total_fee_inr")) row.put(key, "SEALED_HOLDOUT");
                    return row;
                });
                rewriteCsv(run.resolve("settlement-cost-lines.csv"), row -> {
                    for (String key : List.of("rate", "gst_bps", "calculation_base", "charge_inr", "gst_taxable_base_inr", "gst_inr", "total_fee_inr")) row.put(key, "SEALED_HOLDOUT");
                    return row;
                });
                refreshManifest(run, Map.of());
                catalogRow.put("run_manifest_sha256", PortfolioTrialIdentity.fileSha256(run.resolve("run-manifest.properties")));
            }
            fixture.writeCatalog();
            var sealed = fixture.analyze();
            require(sealed.statistics().equals(original.statistics()) && sealed.trials().getFirst().sessions().equals(original.trials().getFirst().sessions())
                    && sealed.trials().getFirst().sharpeHac().equals(original.trials().getFirst().sharpeHac()),
                    "holdout settlement values, fees, and equity are never numerically interpreted by legacy or HAC statistics");
            Files.writeString(fixture.catalog, Files.readString(fixture.catalog).replace("ALL_SPLIT_SESSIONS", "DEVELOPMENT_VALIDATION_ONLY"));
            fails(fixture::analyze, "prefix scope cannot contain holdout artifacts");
            fixture.writeCatalog();
            Path run = fixture.firstRun();
            String originalSettlements = Files.readString(run.resolve("settlements.csv"));
            String originalDatasets = Files.readString(run.resolve("datasets.csv"));
            rewriteCsv(run.resolve("datasets.csv"), row -> {
                if (row.get("instrument_id").equals(A)) row.put("final_bar_close_timestamp_utc",
                        Instant.parse(row.get("final_bar_close_timestamp_utc")).minusSeconds(1).toString());
                return row;
            });
            fails(fixture::analyze, "changed final bar metadata is rejected before refreshing artifact hashes");
            refreshManifest(run, Map.of()); fixture.repinFirstRun();
            try {
                fixture.analyze();
                throw new AssertionError("Rehashed final bar metadata must match actual checkpoints");
            } catch (IOException expected) {
                require(expected.getMessage().startsWith("INCOMPLETE: VERIFIED_SESSION_SERIES_UNAVAILABLE;")
                        && expected.getMessage().contains("Identity mismatch: portfolio final bar close timestamp for " + A),
                        "rehashed sealed holdout is rejected specifically for the final BAR_CLOSE mismatch");
            }
            Files.writeString(run.resolve("datasets.csv"), originalDatasets);
            Files.writeString(run.resolve("settlements.csv"), originalSettlements + originalSettlements.lines().skip(1).findFirst().orElseThrow() + "\n");
            refreshManifest(run, Map.of()); fixture.repinFirstRun();
            fails(fixture::analyze, "duplicate held-out settlement rows still fail structural validation");
            Files.writeString(run.resolve("settlements.csv"), originalSettlements);
            rewriteCsv(run.resolve("settlements.csv"), row -> { row.put("settlement_timestamp", fixture.dates.get(8) + "T03:49:00Z"); return row; });
            refreshManifest(run, Map.of()); fixture.repinFirstRun();
            fails(fixture::analyze, "holdout exclusion still enforces event dates and checkpoint linkage");
        }
    }

    private static void aggregateReturnsAndDeterminism(Path project) throws Exception {
        try (Fixture fixture = new Fixture(project)) {
            var report = fixture.analyze();
            require(report.registeredTrialCount() == 2 && report.comparisonFamilyTrialCount() == 2
                    && report.catalogTrialCount() == 2 && report.trials().size() == 2, "complete family counts");
            var first = report.trials().getFirst();
            require(first.instrumentCount() == 2 && first.sessions().size() == 8
                    && first.developmentSessionCount() == 4 && first.validationSessionCount() == 4
                    && first.excludedHoldoutSessionCount() == 2, "one synchronous aggregate series per trial");
            decimal(first.sessions().getFirst().netPnlChangeInr(), "11", "aggregate P&L counted once");
            decimal(first.sessions().getFirst().feesPaidInr(), "4", "all instrument fees included");
            decimal(first.sessions().getFirst().sessionReturn(), "0.00011", "explicit fixed-capital return");
            decimal(first.totalNetPnlInr(), "33", "only development and validation P&L summed");
            close(first.statistics().meanSessionReturn(), 0.00004125, 1e-18, "session mean");
            close(first.statistics().sampleSessionReturnStandardDeviation(), Math.sqrt(2855.0 / 56.0) / 100000.0,
                    1e-18, "sample session standard deviation");
            require(first.sharpeHac().status().equals("INCOMPLETE")
                    && first.sharpeHac().reason().equals("INSUFFICIENT_SESSIONS")
                    && first.sharpeHac().bandwidths().stream().allMatch(value -> value.standardError() == null),
                    "short synthetic portfolio history receives incomplete HAC without affecting legacy diagnostics");
            require(first.sessionAxisSha256().matches("[0-9a-f]{64}")
                    && first.includedReturnSeriesSha256().matches("[0-9a-f]{64}"),
                    "hashed validated session axis and included returns are emitted");
            require(report.statistics().pbo().combinations() == 6, "four-block CSCV enumerates choose(4,2)");
            require(first.statistics().deflatedSharpeProbability()
                    == first.statistics().dsrProbabilityAtRawFamilyTrialCount(), "raw-count DSR definition retained");
            require(first.statistics().dsrProbabilityAtRawFamilyTrialCount()
                    <= first.statistics().dsrProbabilityAtOneEffectiveTrial(), "DSR sensitivity endpoints");
            require(!report.performanceClaimEligible() && report.trials().stream()
                    .noneMatch(PortfolioTrialEvaluationAnalyzer.TrialReport::performanceClaimEligible), "eligibility remains false");
            String json = report.toJson();
            Files.writeString(fixture.catalog, PortfolioEvaluationJson.write(object("schema", PortfolioTrialCatalog.SCHEMA, "trials", fixture.rows)));
            var defaultScope = fixture.analyze();
            require(report.statistics().equals(defaultScope.statistics())
                    && defaultScope.artifactScope() == PortfolioTrialCatalog.ArtifactScope.ALL_SPLIT_SESSIONS,
                    "omitted scope preserves full-split catalog compatibility while catalog byte hashes differ");
            fixture.writeCatalog();
            var parsed = PortfolioEvaluationJson.parsedObject(
                    com.mscient.intrapaper.data.StrictJsonParser.parse(json), "report");
            require(PortfolioTrialEvaluationAnalyzer.REPORT_SCHEMA.equals(parsed.get("schema")), "dedicated report schema");
            require("UNVERIFIED".equals(PortfolioEvaluationJson.parsedObject(parsed.get("sharpe_hac_protocol"), "HAC protocol").get("preregistration_status")),
                    "independent preregistration remains unverified");
            require(json.equals(fixture.analyze().toJson()), "same inputs produce identical output bytes");
            Locale savedLocale = Locale.getDefault();
            TimeZone savedZone = TimeZone.getDefault();
            try {
                Locale.setDefault(Locale.FRANCE);
                TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
                require(json.equals(fixture.analyze().toJson()), "output independent of host locale and timezone");
            } finally { Locale.setDefault(savedLocale); TimeZone.setDefault(savedZone); }
            java.util.Collections.reverse(fixture.rows);
            fixture.writeCatalog();
            require(report.statistics().equals(fixture.analyze().statistics()), "catalog order cannot select winners");
        }
    }

    private static void strictCatalogAndIdentityMismatch(Path project) throws Exception {
        try (Fixture fixture = new Fixture(project)) {
            Map<String, Object> row = fixture.rows.getFirst();
            for (String field : List.of("trial_id", "strategy_id", "strategy_version", "variant_id",
                    "strategy_card_sha256", "dataset_sha256", "split_plan_sha256", "parameter_config_sha256",
                    "registration_record_sha256", "run_id", "run_manifest_sha256")) {
                Object prior = row.get(field);
                row.put(field, field.endsWith("sha256") || field.equals("run_id") ? "f".repeat(64) : prior + "-wrong");
                fixture.writeCatalog();
                fails(fixture::analyze, "mismatched " + field);
                row.put(field, prior);
            }
            for (Object capital : List.of("0", "-1", "+100", "1e5", "0100", "100,000", new BigDecimal("100000"))) {
                row.put("starting_capital_inr", capital);
                fixture.writeCatalog();
                fails(fixture::analyze, "invalid starting capital");
            }
            row.put("starting_capital_inr", "100001");
            fixture.writeCatalog();
            fails(fixture::analyze, "capital must agree across family");
            row.put("starting_capital_inr", "100000");
            row.put("unexpected", true);
            fixture.writeCatalog();
            fails(fixture::analyze, "unknown trial keys");
            row.remove("unexpected");
            Object prior = row.remove("split_plan_csv");
            fixture.writeCatalog();
            fails(fixture::analyze, "missing catalog keys");
            row.put("split_plan_csv", prior);
            fixture.rows.add(new LinkedHashMap<>(row));
            fixture.writeCatalog();
            fails(fixture::analyze, "duplicate trials and runs");
            fixture.rows.removeLast();
            fixture.rows.get(1).put("run_id", row.get("run_id"));
            fixture.writeCatalog();
            fails(fixture::analyze, "one run cannot serve two trial identities");
            Files.writeString(fixture.catalog, "{\"schema\":\"x\",\"schema\":\"x\",\"trials\":[]}");
            fails(fixture::analyze, "duplicate JSON keys");
            Files.write(fixture.catalog, new byte[]{(byte) 0xc3, (byte) 0x28});
            fails(fixture::analyze, "malformed UTF-8 catalog");
            try (var file = java.nio.channels.FileChannel.open(fixture.catalog, java.nio.file.StandardOpenOption.WRITE)) {
                file.position(16L * 1024 * 1024);
                file.write(java.nio.ByteBuffer.wrap(new byte[]{0}));
            }
            fails(fixture::analyze, "oversized catalog rejected before parsing");
        }
    }

    private static void registeredRunIdentityMismatch(Path project) throws Exception {
        for (String field : List.of("strategy_id", "dataset_sha256", "parameter_config_sha256")) {
            try (Fixture fixture = new Fixture(project)) {
                Path ledger = fixture.root.resolve("wrong-identity.ledger");
                for (var row : fixture.rows) {
                    row.put(field, field.equals("strategy_id") ? "WRONG_STRATEGY" : "e".repeat(64));
                    var entry = ResearchTrialLedger.register(ledger, trial(row));
                    row.put("registration_record_sha256", entry.recordHashSha256());
                }
                fixture.ledger = ledger;
                fixture.writeCatalog();
                fails(fixture::analyze, "run identity must match the registration itself: " + field);
            }
        }
    }

    private static void partitionsAndMissingSessions(Path project) throws Exception {
        try (Fixture fixture = new Fixture(project)) {
            String valid = Files.readString(fixture.split);
            Files.writeString(fixture.split, valid.replace(fixture.dates.getFirst() + ",DEVELOPMENT",
                    fixture.dates.getFirst() + ",VALIDATION"));
            fails(fixture::analyze, "chronological partition progression");
            Files.writeString(fixture.split, valid.replaceAll("(?m)^.*HOLDOUT\\n", ""));
            fails(fixture::analyze, "holdout partition required");
            Files.writeString(fixture.split, valid + fixture.dates.getFirst() + ",DEVELOPMENT\n");
            fails(fixture::analyze, "duplicate split sessions");
            Files.writeString(fixture.split, valid);
            fails(() -> PortfolioTrialEvaluationAnalyzer.analyze(fixture.ledger, fixture.catalog, fixture.runs, 5),
                    "odd CSCV block count");
            fails(() -> PortfolioTrialEvaluationAnalyzer.analyze(fixture.ledger, fixture.catalog, fixture.runs, 6),
                    "at least two sessions per chronological block");
            Path run = fixture.firstRun();
            rewriteCsv(run.resolve("equity.csv"), row -> row.get("timestamp_utc")
                    .startsWith(fixture.dates.get(2).toString()) ? null : row);
            rewriteCsv(run.resolve("datasets.csv"), row -> { row.put("validated_bar_count", "36"); return row; });
            refreshManifest(run, Map.of("snapshot_count", "72"));
            fixture.repinFirstRun();
            fails(fixture::analyze, "missing complete session cannot be hidden by rehashing counters");
        }
        try (Fixture fixture = new Fixture(project)) {
            Path run = fixture.firstRun();
            rewriteCsv(run.resolve("equity.csv"), row -> {
                if (row.get("timestamp_utc").startsWith(fixture.dates.get(3).toString())
                        && row.get("phase").equals("BAR_CLOSE") && row.get("timestamp_utc").contains("03:49:00")) {
                    row.put("signed_units", "1");
                }
                return row;
            });
            refreshManifest(run, Map.of());
            fixture.repinFirstRun();
            fails(fixture::analyze, "flat development partition boundary");
        }
    }

    private static void holdoutExcludedEvenWhenSealed(Path project) throws Exception {
        try (Fixture fixture = new Fixture(project)) {
            var original = fixture.analyze();
            String marker = "SEALED_HOLDOUT_VALUE";
            for (var catalogRow : fixture.rows) {
                Path run = fixture.runs.resolve((String) catalogRow.get("run_id"));
                rewriteCsv(run.resolve("equity.csv"), row -> {
                    if (row.get("timestamp_utc").startsWith(fixture.dates.get(8).toString())
                            || row.get("timestamp_utc").startsWith(fixture.dates.get(9).toString())) {
                        for (String field : List.of("realized_gross_pnl_inr", "fees_inr", "unrealized_pnl_inr",
                                "total_realized_gross_pnl_inr", "total_fees_inr", "total_unrealized_pnl_inr", "net_pnl_inr")) {
                            row.put(field, marker);
                        }
                    }
                    return row;
                });
                refreshManifest(run, Map.of());
                catalogRow.put("run_manifest_sha256", PortfolioTrialIdentity.fileSha256(run.resolve("run-manifest.properties")));
            }
            fixture.writeCatalog();
            var sealed = fixture.analyze();
            require(original.statistics().equals(sealed.statistics())
                    && original.trials().getFirst().sharpeHac().equals(sealed.trials().getFirst().sharpeHac()),
                    "holdout P&L cannot affect Sharpe, HAC, DSR, or CSCV");
            for (int index = 0; index < original.trials().size(); index++) {
                require(original.trials().get(index).sessions().equals(sealed.trials().get(index).sessions()),
                        "holdout P&L cannot enter session returns");
                decimal(sealed.trials().get(index).totalNetPnlInr(),
                        original.trials().get(index).totalNetPnlInr().toPlainString(), "holdout excluded from total P&L");
            }
            require(!sealed.toJson().contains(marker), "holdout values are not emitted");
            require(sealed.trials().getFirst().excludedHoldoutSessionCount() == 2, "holdout coverage still verified");
        }
    }

    private static void completeTrialCountsAndOutcomeProvenance(Path project) throws Exception {
        try (Fixture fixture = new Fixture(project)) {
            fixture.registerExtra("unrelated", "OTHER", "c".repeat(64));
            var before = fixture.analyze();
            require(before.registeredTrialCount() == 3 && before.comparisonFamilyTrialCount() == 2,
                    "unrelated registrations counted separately");
            fixture.recordOutcomes();
            var after = PortfolioTrialEvaluationAnalyzer.analyze(fixture.ledger, fixture.catalog, fixture.runs, 4, fixture.outcomes);
            require(before.statistics().equals(after.statistics()), "outcome exposure does not select or score trials");
            require(after.outcomeHistory().summary().eventCount() == 5 && after.outcomeHistory().summary().trials().size() == 3,
                    "outcome event and registered-trial counts");
            require(after.outcomeHistory().summary().trials().stream().anyMatch(trial ->
                    trial.trialId().equals("portfolio-B") && trial.exposureState()
                            == ResearchOutcomeLedger.ExposureState.MECHANICAL_FAILURE_ONLY),
                    "recorded failure state does not reduce raw family size");
            require(after.toJson().contains("\"comparison_family_trial_count\": 2")
                    && after.toJson().contains("\"file_sha256\": \"" + PortfolioTrialIdentity.fileSha256(fixture.outcomes)),
                    "outcome file hashes and family counts reported");
            fixture.registerExtra("missing", "SYN_DAY_TURN", before.datasetSha256());
            fails(fixture::analyze, "omitted registered family member");
        }
        try (Fixture fixture = new Fixture(project)) {
            fixture.rows.removeLast();
            fixture.writeCatalog();
            fails(fixture::analyze, "minimum two registered trial runs");
        }
    }

    private static void tamperedArtifactsAndLedgers(Path project) throws Exception {
        try (Fixture fixture = new Fixture(project)) {
            Path run = fixture.firstRun();
            Path equity = run.resolve("equity.csv");
            String valid = Files.readString(equity);
            Files.writeString(equity, valid + "tampered\n");
            fails(fixture::analyze, "artifact byte tampering");
            Files.writeString(equity, valid);
            rewriteCsv(equity, row -> { row.put("net_pnl_inr", "999999"); return row; });
            refreshManifest(run, Map.of());
            fails(fixture::analyze, "rehashed artifacts still fail the catalog manifest pin");
            fixture.repinFirstRun();
            fails(fixture::analyze, "rehashed aggregate equity must reconcile");
        }
        try (Fixture fixture = new Fixture(project)) {
            String ledger = Files.readString(fixture.ledger);
            Files.writeString(fixture.ledger, ledger.replace("LONG_BOTH", "LONG_WRONG"));
            fails(fixture::analyze, "registration hash-chain tampering");
        }
        try (Fixture fixture = new Fixture(project)) {
            fixture.recordOutcomes();
            Files.writeString(fixture.outcomes, Files.readString(fixture.outcomes).replace("fixture-ref", "tampered-ref"));
            fails(() -> PortfolioTrialEvaluationAnalyzer.analyze(fixture.ledger, fixture.catalog,
                    fixture.runs, 4, fixture.outcomes), "outcome history hash-chain tampering");
        }
        try (Fixture fixture = new Fixture(project)) {
            Path run = fixture.firstRun();
            rewriteCsv(run.resolve("equity.csv"), row -> row.get("instrument_id").equals(B) ? null : row);
            refreshManifest(run, Map.of());
            fixture.repinFirstRun();
            fails(fixture::analyze, "missing instrument snapshots");
        }
        try (Fixture fixture = new Fixture(project)) {
            Path run = fixture.firstRun();
            rewriteCsv(run.resolve("datasets.csv"), row -> { row.put("source_name", "CHANGED_SOURCE"); return row; });
            refreshManifest(run, Map.of());
            fixture.repinFirstRun();
            fails(fixture::analyze, "registered dataset fingerprint binds artifact source provenance");
        }
    }

    private static void cliCreateNewAndFailure(Path project) throws Exception {
        try (Fixture fixture = new Fixture(project)) {
            Path output = fixture.root.resolve("evaluation.json");
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            try (PrintStream out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                 PrintStream err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
                String[] args = {"evaluate", fixture.ledger.toString(), fixture.catalog.toString(),
                        fixture.runs.toString(), "4", output.toString()};
                require(PortfolioTrialEvaluationCli.run(args, out, err) == 0, "dedicated CLI succeeds");
                String saved = Files.readString(output);
                require(PortfolioTrialEvaluationCli.run(args, out, err) == 1
                        && saved.equals(Files.readString(output)), "CLI never overwrites an existing output");
                require(PortfolioTrialEvaluationCli.run(new String[]{"identity", fixture.firstRun().toString()}, out, err) == 0,
                        "identity command emits catalog input hashes");
                fixture.rows.getFirst().put("parameter_config_sha256", "f".repeat(64));
                fixture.writeCatalog();
                Path failedOutput = fixture.root.resolve("failed.json");
                args[5] = failedOutput.toString();
                require(PortfolioTrialEvaluationCli.run(args, out, err) == 1 && !Files.exists(failedOutput),
                        "validation failure emits no report");
            }
        }
    }

    private static void statisticalEdgeCases() throws Exception {
        var same = List.of(new SessionReturnStatistics.Series("z", new double[]{1, 2, 3, 4, 5, 6, 7, 8}),
                new SessionReturnStatistics.Series("a", new double[]{1, 2, 3, 4, 5, 6, 7, 8}));
        var ties = SessionReturnStatistics.analyze(same, 4);
        require(ties.pbo().combinations() == 6 && ties.pbo().belowMedianCount() == 0
                && ties.pbo().probabilityBacktestOverfitting() == 0.0, "OOS ties use median midranks");
        require(ties.trials().getFirst().trialId().equals("a"), "ascending trial IDs fix selection ties");
        fails(() -> SessionReturnStatistics.analyze(List.of(new SessionReturnStatistics.Series("a", new double[8]),
                same.getFirst()), 4), "zero-variance series fails closed");
        fails(() -> SessionReturnStatistics.analyze(List.of(new SessionReturnStatistics.Series("a",
                new double[]{1, 1, 1, 1, 1, 1, 2, 3}), same.getFirst()), 4), "zero-variance CSCV fold fails closed");
        fails(() -> SessionReturnStatistics.analyze(List.of(new SessionReturnStatistics.Series("a",
                new double[]{1, 2, 3, 4, 5, 6, 7, Double.NaN}), same.getFirst()), 4), "nonfinite returns fail closed");
    }

    private static ResearchTrialLedger.Trial trial(Map<String, Object> row) {
        return new ResearchTrialLedger.Trial((String) row.get("trial_id"), (String) row.get("strategy_id"),
                (String) row.get("strategy_version"), (String) row.get("variant_id"),
                (String) row.get("strategy_card_sha256"), (String) row.get("dataset_sha256"),
                (String) row.get("split_plan_sha256"), (String) row.get("parameter_config_sha256"));
    }

    @FunctionalInterface private interface RowEdit { Map<String, String> edit(Map<String, String> row); }

    private static void rewriteCsv(Path path, RowEdit edit) throws Exception {
        CsvTable table = CsvTable.read(path);
        StringBuilder output = new StringBuilder(String.join(",", table.headers())).append('\n');
        for (var original : table.rows()) {
            var row = edit.edit(new LinkedHashMap<>(original));
            if (row == null) continue;
            output.append(table.headers().stream().map(header -> CsvTable.escape(row.get(header)))
                    .collect(java.util.stream.Collectors.joining(","))).append('\n');
        }
        Files.writeString(path, output);
    }

    private static void refreshManifest(Path run, Map<String, String> overrides) throws Exception {
        List<String> lines = Files.readAllLines(run.resolve("run-manifest.properties"));
        StringBuilder manifest = new StringBuilder();
        for (String line : lines) {
            int separator = line.indexOf('=');
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (key.startsWith("sha256.")) value = PortfolioTrialIdentity.fileSha256(run.resolve(key.substring(7)));
            value = overrides.getOrDefault(key, value);
            manifest.append(key).append('=').append(value).append('\n');
        }
        Files.writeString(run.resolve("run-manifest.properties"), manifest);
    }

    private static final class Fixture implements AutoCloseable {
        private final Path root;
        private Path ledger;
        private final Path catalog;
        private final Path split;
        private final Path runs;
        private final Path outcomes;
        private final List<LocalDate> dates = new ArrayList<>();
        private final List<Map<String, Object>> rows = new ArrayList<>();
        private final boolean prefixOnly;

        private Fixture(Path project) throws Exception {
            this(project, false, false);
        }
        private Fixture(Path project, boolean settle, boolean prefixOnly) throws Exception {
            this(project, settle, prefixOnly, true);
        }
        private Fixture(Path project, boolean settle, boolean prefixOnly, boolean holdAtSettlement) throws Exception {
            this.prefixOnly = prefixOnly;
            root = Files.createTempDirectory("portfolio-trial-evaluation-check-").toAbsolutePath().normalize();
            ledger = root.resolve("trials.ledger");
            catalog = root.resolve("catalog.json");
            split = root.resolve("split.csv");
            runs = root.resolve("runs");
            outcomes = root.resolve("outcomes.ledger");
            LocalDate firstDate = LocalDate.of(2030, 5, 6);
            for (LocalDate date = firstDate; dates.size() < 10; date = date.plusDays(1)) {
                if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) dates.add(date);
            }
            int runSessions = prefixOnly ? 8 : 10;
            LocalDate finalSession = dates.get(runSessions - 1);
            String masterHeader = Files.readAllLines(project.resolve("examples/synthetic-instrument-master.csv")).getFirst();
            StringBuilder master = new StringBuilder(masterHeader).append('\n');
            for (String id : List.of(A, B)) {
                master.append(id).append(",SYN,FUT,").append(id).append(',').append(id)
                        .append(",FUTURE,SYNTHETIC,2030-12-26,,,1,0.05,2030-01-01,\n");
            }
            Files.writeString(root.resolve("master.csv"), master);
            Files.writeString(root.resolve("rules.csv"), Files.readString(
                    project.resolve("examples/synthetic-market-rule-regimes.csv")).replace("09:45", "09:19"));
            StringBuilder calendar = new StringBuilder("session_date,session_status,first_bar_timestamp,last_bar_timestamp,market_rule_regime_id,regime_source_reference\n");
            for (LocalDate date = firstDate; !date.isAfter(finalSession); date = date.plusDays(1)) {
                if (dates.contains(date)) calendar.append(date).append(",OPEN,").append(date)
                        .append("T09:15:00+05:30,").append(date).append("T09:18:00+05:30,SYNTHETIC_V1,")
                        .append(RULE_REFERENCE).append('\n');
                else calendar.append(date).append(",CLOSED,,,,\n");
            }
            Files.writeString(root.resolve("calendar.csv"), calendar);
            String template = Files.readString(project.resolve("examples/synthetic-run.properties"))
                    .replace("synthetic-instrument-master.csv", "master.csv")
                    .replace("synthetic-session-calendar.csv", "calendar.csv")
                    .replace("synthetic-market-rule-regimes.csv", "rules.csv")
                    .replaceAll("(?m)^cost.schedule_file=.*\\r?\\n", "");
            String barsHeader = Files.readAllLines(project.resolve("examples/synthetic-futures-bars.csv")).getFirst();
            Map<String, ValidatedPortfolioDataset.Input> inputs = new LinkedHashMap<>();
            int[] aChanges = {12, -3, 9, 2, -5, 7, 15, 1, 50, 60};
            int[] bChanges = {3, 6, -2, 8, 4, -6, 5, 9, 20, 30};
            for (String id : List.of(A, B)) {
                Path config = root.resolve(id + ".properties");
                Files.writeString(config, template.replace("synthetic-demo-future-v1", "synthetic-" + id));
                Path bars = root.resolve(id + ".csv");
                StringBuilder content = new StringBuilder(barsHeader).append('\n');
                for (int session = 0; session < runSessions; session++) {
                    int change = (id.equals(A) ? aChanges : bChanges)[session];
                    LocalDate date = dates.get(session);
                    for (int bar = 0; bar < 4; bar++) {
                        int price = 100 + (bar >= 2 ? change : 0);
                        content.append(date).append("T09:").append(15 + bar).append(":00+05:30,")
                                .append(id).append(",SYN,FUT,").append(id).append(',').append(id)
                                .append(",FUTURE,SYNTHETIC,2030-12-26,,,1,0.05,ONE_MINUTE,BAR_START,")
                                .append(price).append(',').append(price + 1).append(',').append(price - 1).append(',')
                                .append(price).append(",100,1000,").append(SOURCE).append(',')
                                .append(RunConfig.sha256((id + date + bar).getBytes(StandardCharsets.UTF_8))).append('\n');
                    }
                }
                Files.writeString(bars, content);
                inputs.put(id, new ValidatedPortfolioDataset.Input(bars, config));
            }
            var dataset = ValidatedPortfolioDataset.load(inputs);
            Optional<PortfolioSettlementEvents> settlementEvents = Optional.empty();
            Optional<PortfolioSettlementCostSchedule> settlementCosts = Optional.empty();
            if (settle) {
                var events = new StringBuilder(String.join(",", PortfolioSettlementEvents.HEADERS)).append('\n');
                var costs = new StringBuilder(String.join(",", PortfolioSettlementCostSchedule.HEADERS)).append('\n');
                for (String id : List.of(A, B)) {
                    String identity = id + ",SYN,FUT," + id + "," + id + ",FUTURE,SYNTHETIC,2030-12-26,,,1,0.05";
                    events.append("SET-").append(id).append(',').append(identity).append(',').append(finalSession).append(',')
                            .append(finalSession).append(id.equals(A) ? "T03:49:00Z,110" : "T03:50:00Z,95").append(",false,,,synthetic settlement\n");
                    for (String side : List.of("LONG", "SHORT")) costs.append("SYNTHETIC,C-").append(id).append('-').append(side)
                            .append(',').append(finalSession).append(",,").append(identity).append(',').append(side)
                            .append(",EXPIRY_SETTLEMENT,FIXED,FIXED_PER_SETTLEMENT,1.005,2,HALF_UP,1800,true,2,HALF_UP,synthetic cost\n");
                }
                Path eventFile = root.resolve("replay-events.csv"), costFile = root.resolve("settlement-costs.csv");
                Files.writeString(eventFile, events); Files.writeString(costFile, costs);
                settlementEvents = Optional.of(PortfolioSettlementEvents.load(eventFile));
                settlementCosts = Optional.of(PortfolioSettlementCostSchedule.load(costFile));
            }
            StringBuilder splitText = new StringBuilder("session_date,partition\n");
            for (int index = 0; index < dates.size(); index++) splitText.append(dates.get(index)).append(',')
                    .append(index < 4 ? "DEVELOPMENT" : index < 8 ? "VALIDATION" : "HOLDOUT").append('\n');
            Files.writeString(split, splitText);
            String splitHash = EvaluationSplitPlan.loadForEvaluation(split).splitFileSha256();
            String cardHash = RunConfig.sha256("Synthetic fixture card; no performance evidence.\n".getBytes(StandardCharsets.UTF_8));
            var config = new PortfolioReplayEngine.Config(Map.of(A, 2, B, 2),
                    PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY,
                    CrossSessionPositionPolicy.REQUIRE_FLAT, true,
                    settle ? PortfolioSettlementPolicy.EXPLICIT_FINAL_SESSION : PortfolioSettlementPolicy.NONE,
                    settle ? java.util.Set.of(A, B) : java.util.Set.of());
            var fill = new OpeningPriceIocFillModel(0, BigDecimal.ONE);
            for (int index = 0; index < 2; index++) {
                int sideB = index == 0 ? 1 : -1;
                PortfolioStrategy strategy = new PortfolioStrategy() {
                    @Override public String id() { return "SYN_DAY_TURN"; }
                    @Override public Map<String, String> parameters() { return Map.of("side_b", Integer.toString(sideB)); }
                    @Override public PortfolioTarget evaluate(List<SynchronizedBarFrameBuilder.Frame> frames,
                            Instant time, PortfolioAccountingLedger.Snapshot completed) {
                        boolean enter = (frames.size() - 1) % 4 == 0 || (settle && holdAtSettlement && frames.getLast().sessionDate().equals(finalSession));
                        return new PortfolioTarget(Map.of(A, enter ? 1 : 0, B, enter ? sideB : 0), "synthetic turn");
                    }
                };
                String parameters = PortfolioTrialIdentity.parameterConfigSha256(config, strategy, fill, Optional.empty(), settlementEvents, settlementCosts);
                if (settle) {
                    var supplied = settlementEvents.orElseThrow();
                    require(!parameters.equals(PortfolioTrialIdentity.parameterConfigSha256(config, strategy, fill, Optional.empty(),
                            Optional.of(new PortfolioSettlementEvents(supplied.events(), "f".repeat(64))), settlementCosts)), "registration binds settlement event input hash");
                    var costs = settlementCosts.orElseThrow();
                    require(!parameters.equals(PortfolioTrialIdentity.parameterConfigSha256(config, strategy, fill, Optional.empty(), settlementEvents,
                            Optional.of(new PortfolioSettlementCostSchedule(costs.scheduleId(), costs.entries(), "e".repeat(64))))), "registration binds settlement cost input hash");
                    fails(() -> PortfolioTrialIdentity.parameterConfigSha256(config, strategy, fill, Optional.empty()), "settlement registration cannot omit its source inputs");
                }
                var trial = new ResearchTrialLedger.Trial(index == 0 ? "portfolio-A" : "portfolio-B", strategy.id(), "v1",
                        index == 0 ? "LONG_BOTH" : "LONG_SHORT", cardHash, dataset.datasetsSha256(), splitHash, parameters);
                var registration = ResearchTrialLedger.register(ledger, trial);
                var result = new PortfolioReplayEngine(config, strategy, fill, Optional.empty(), settlementEvents, settlementCosts).run(dataset);
                var written = new PortfolioReplayArtifactWriter().write(runs, dataset, result);
                var frozen = PortfolioTrialIdentity.inspect(written.path());
                require(parameters.equals(frozen.parameterConfigSha256()), "prereplay and artifact parameter identity agree");
                rows.add(object("trial_id", trial.trialId(), "strategy_id", trial.strategyId(),
                        "strategy_version", trial.strategyVersion(), "variant_id", trial.variantId(),
                        "strategy_card_sha256", trial.strategyCardSha256(), "dataset_sha256", trial.datasetSha256(),
                        "split_plan_sha256", trial.splitPlanSha256(), "parameter_config_sha256", trial.parameterConfigSha256(),
                        "registration_record_sha256", registration.recordHashSha256(), "run_id", written.runId(),
                        "run_manifest_sha256", frozen.manifestSha256(), "split_plan_csv", "split.csv", "starting_capital_inr", "100000"));
            }
            writeCatalog();
        }

        private void writeCatalog() throws IOException {
            Files.writeString(catalog, PortfolioEvaluationJson.write(object("schema", PortfolioTrialCatalog.SCHEMA,
                    "artifact_scope", prefixOnly ? "DEVELOPMENT_VALIDATION_ONLY" : "ALL_SPLIT_SESSIONS", "trials", rows)));
        }
        private PortfolioTrialEvaluationAnalyzer.Report analyze() throws IOException {
            return PortfolioTrialEvaluationAnalyzer.analyze(ledger, catalog, runs, 4);
        }
        private Path firstRun() { return runs.resolve((String) rows.getFirst().get("run_id")); }
        private void repinFirstRun() throws IOException {
            rows.getFirst().put("run_manifest_sha256", PortfolioTrialIdentity.fileSha256(firstRun().resolve("run-manifest.properties")));
            writeCatalog();
        }
        private void registerExtra(String id, String strategy, String datasetHash) throws IOException {
            var first = trial(rows.getFirst());
            ResearchTrialLedger.register(ledger, new ResearchTrialLedger.Trial(id, strategy, "v1", id,
                    first.strategyCardSha256(), datasetHash, first.splitPlanSha256(), "d".repeat(64)));
        }
        private void recordOutcomes() throws Exception {
            event("portfolio-A", "a1", "e1", ResearchOutcomeLedger.EventType.ATTEMPT_STARTED);
            event("portfolio-A", "a1", "e2", ResearchOutcomeLedger.EventType.OUTCOME_PRODUCED);
            event("portfolio-A", "a1", "e3", ResearchOutcomeLedger.EventType.OUTCOME_VIEWED);
            event("portfolio-B", "b1", "e4", ResearchOutcomeLedger.EventType.ATTEMPT_STARTED);
            event("portfolio-B", "b1", "e5", ResearchOutcomeLedger.EventType.MECHANICAL_FAILURE_NO_OUTCOME);
        }
        private void event(String trialId, String attempt, String id, ResearchOutcomeLedger.EventType type) throws Exception {
            ResearchOutcomeLedger.append(ledger, outcomes, trialId, attempt, id, type, "fixture-ref", "-");
            Thread.sleep(2);
        }
        @Override public void close() throws IOException {
            if (Files.isSymbolicLink(root)) throw new IOException("Unexpected fixture cleanup path");
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    if (!path.toAbsolutePath().normalize().startsWith(root)) throw new IOException("Fixture escaped root");
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }

    private static void fails(Throwing action, String message) throws Exception {
        try { action.run(); }
        catch (IOException | IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("Expected rejection: " + message);
    }
    private static void decimal(BigDecimal value, String expected, String message) {
        require(value.compareTo(new BigDecimal(expected)) == 0, message + ": " + value);
    }
    private static void close(double value, double expected, double tolerance, String message) {
        require(Math.abs(value - expected) <= tolerance, message + ": " + value);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
}
