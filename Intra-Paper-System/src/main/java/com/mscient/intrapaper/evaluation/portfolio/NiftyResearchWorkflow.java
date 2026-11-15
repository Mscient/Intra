package com.mscient.intrapaper.evaluation.portfolio;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.evaluation.*;
import com.mscient.intrapaper.provenance.*;
import com.mscient.intrapaper.reference.ContractKind;
import com.mscient.intrapaper.simulation.portfolio.*;
import java.io.*;
import java.math.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.time.*;
import java.util.*;
import static com.mscient.intrapaper.evaluation.portfolio.PortfolioEvaluationJson.*;

/** Local orchestration over the existing registry, validated replay, evaluator and holdout controller. */
public final class NiftyResearchWorkflow {
    static final String PLAN = "INTRA_NIFTY_RESEARCH_PLAN_V1";
    static final String CARD = "INTRA_NIFTY_FROZEN_TRIAL_V1";
    static final String RULE = "MAX_VALIDATION_NET_PNL_TIE_TRIAL_ID";
    static final Set<String> PLAN_KEYS = Set.of("schema","trial_id","strategy_version","variant_id",
        "strategy_card_file","prefix_inputs_csv","portfolio_settings_file","split_plan_csv",
        "holdout_inputs_csv","prefix_files","holdout_files","starting_capital_inr","selection_rule",
        "walk_forward_training_sessions","walk_forward_validation_sessions","walk_forward_evaluation_sessions");
    final Path workspace;
    public NiftyResearchWorkflow(Path workspace) { this.workspace = workspace.toAbsolutePath().normalize(); }
    Path ledger() { return workspace.resolve("trials.ledger"); }
    Path outcomes() { return workspace.resolve("outcomes.ledger"); }
    Path cardPath(String id) { HoldoutReleaseInputs.requireId(id); return workspace.resolve("trials").resolve(id+".json"); }
    Path evaluationPath(String id) { HoldoutReleaseInputs.requireId(id); return workspace.resolve("evaluations").resolve(id+".json"); }
    static String hash(Path path) throws IOException { return PortfolioTrialIdentity.fileSha256(path); }
    static Object canonical(Object value) {
        if (value instanceof Map<?,?> map) { var sorted = new TreeMap<String,Object>(); map.forEach((k,v)->sorted.put(k.toString(),canonical(v))); return sorted; }
        if (value instanceof List<?> list) return list.stream().map(NiftyResearchWorkflow::canonical).toList();
        return value;
    }
    static String digest(Object value) { return RunConfig.sha256(write(canonical(value)).getBytes(StandardCharsets.UTF_8)); }
    static Map<String,Object> json(Path path) throws IOException {
        return parsedObject(StrictJsonParser.parse(PortfolioTrialIdentity.utf8(PortfolioTrialIdentity.readRegular(path))),"workflow JSON");
    }
    static Path resolve(Path base,String value) { Path p = Path.of(value); return (p.isAbsolute()?p:base.resolve(p)).toAbsolutePath().normalize(); }
    static void equal(Object a,Object b,String label) throws IOException { if (!Objects.equals(a,b)) throw new IOException("Identity mismatch: "+label); }
    static void save(Path path,Object value) throws IOException {
        Files.writeString(path,write(value),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
    }
    void mutable() throws IOException { if(Files.exists(workspace.resolve("gate"))) throw new IOException("Selection frozen: no registration or development/validation tuning allowed"); }
    static Path actualCode() throws IOException {
        try {
            Path code = Path.of(NiftyResearchWorkflow.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(code) || !code.toString().endsWith(".jar")) throw new IOException("Workflow requires the packaged research JAR");
            return code.toAbsolutePath().normalize();
        } catch(java.net.URISyntaxException e) { throw new IOException(e); }
    }
    static TreeMap<String,String> fileMap(Map<String,Object> value,String key,Path base) throws IOException {
        var raw = parsedObject(value.get(key),key); var result = new TreeMap<String,String>();
        if(raw.isEmpty()) throw new IOException("Empty admitted input set: "+key);
        for(var e:raw.entrySet()) {
            if(!(e.getValue() instanceof String h)) throw new IOException("Input hash must be a string");
            HoldoutReleaseInputs.requireHash(h);
            if(result.putIfAbsent(resolve(base,e.getKey()).toString(),h)!=null) throw new IOException("Duplicate resolved input path");
        }
        return result;
    }
    static TreeMap<String,String> frozenFiles(Map<String,Object> card,String key) throws IOException {
        return fileMap(card,key,Path.of("."));
    }
    static void checkFiles(Map<String,String> files) throws IOException {
        for(var e:files.entrySet()) equal(e.getValue(),hash(Path.of(e.getKey())),"admitted input "+e.getKey());
    }
    static int count(Map<String,Object> plan,String key) throws IOException {
        String text=string(plan,key);
        if(!text.matches("0|[1-9][0-9]{0,5}")) throw new IOException("Invalid window count: "+key);
        return Integer.parseInt(text);
    }
    static ResearchTrialLedger.Trial trial(Map<String,Object> c) throws IOException {
        return new ResearchTrialLedger.Trial(string(c,"trial_id"),"NIFTY_SESSION_SMA_RESEARCH_V1",
            string(c,"strategy_version"),string(c,"variant_id"),digest(c),
            digest(object("domain","INTRA_NIFTY_ADMITTED_DATA_V1","files",c.get("prefix_files"))),
            string(c,"split_sha256"),digest(object("domain","INTRA_NIFTY_FROZEN_PARAMETERS_V1",
                "settings_sha256",c.get("settings_sha256"),"code_sha256",c.get("code_sha256"))));
    }
    public ResearchTrialLedger.Entry register(Path planPath) throws IOException {
        mutable(); var plan=json(planPath); equal(plan.keySet(),PLAN_KEYS,"plan schema fields"); equal(string(plan,"schema"),PLAN,"plan schema");
        equal(string(plan,"selection_rule"),RULE,"selection rule");
        var base=planPath.toAbsolutePath().normalize().getParent(); var c=new TreeMap<String,Object>(plan); c.put("schema",CARD);
        for(String key:List.of("strategy_card_file","prefix_inputs_csv","portfolio_settings_file","split_plan_csv","holdout_inputs_csv"))
            c.put(key,resolve(base,string(plan,key)).toString());
        var prefix=fileMap(plan,"prefix_files",base); var holdout=fileMap(plan,"holdout_files",base);
        c.put("prefix_files",prefix);c.put("holdout_files",holdout);
        if(!prefix.containsKey(string(c,"prefix_inputs_csv")) || !holdout.containsKey(string(c,"holdout_inputs_csv")))
            throw new IOException("Each admitted file set must contain its input manifest");
        if(prefix.keySet().stream().anyMatch(f -> holdout.containsKey(f) && !prefix.get(f).equals(holdout.get(f))))
            throw new IOException("Conflicting shared input commitments");
        String cap=string(plan,"starting_capital_inr");
        if(!cap.matches("[0-9]+(?:[.][0-9]+)?")||new BigDecimal(cap).signum()<=0) throw new IOException("Positive plain decimal capital required");
        int train=count(plan,"walk_forward_training_sessions"),val=count(plan,"walk_forward_validation_sessions"),ev=count(plan,"walk_forward_evaluation_sessions");
        if((train==0||val==0||ev==0)&&!(train==0&&val==0&&ev==0)) throw new IOException("Walk-forward counts must all be zero or positive");
        checkFiles(prefix); // No holdout file is opened by registration.
        c.put("code_artifact",actualCode().toString());c.put("code_sha256",hash(actualCode()));
        c.put("settings_sha256",hash(Path.of(string(c,"portfolio_settings_file"))));
        c.put("original_card_sha256",hash(Path.of(string(c,"strategy_card_file"))));
        c.put("split_sha256",hash(Path.of(string(c,"split_plan_csv"))));
        // Capture raw inputs before validating strategy parameters, so invalid/empty attempts remain registered.
        var t=trial(c); Files.createDirectories(workspace.resolve("trials")); Files.createDirectories(workspace.resolve("evaluations"));
        var entry=ResearchTrialLedger.register(ledger(),t); save(cardPath(t.trialId()),c); return entry;
    }
    Map<String,Object> card(String id) throws IOException {
        var c=json(cardPath(id)); equal(string(c,"schema"),CARD,"frozen trial schema");
        ResearchTrialLedger.requireRegistered(ledger(),trial(c));return c;
    }
    static void checkPublic(Map<String,Object> c) throws IOException {
        equal(c.get("code_sha256"),hash(actualCode()),"running code artifact");
        equal(c.get("settings_sha256"),hash(Path.of(string(c,"portfolio_settings_file"))),"frozen settings");
        equal(c.get("split_sha256"),hash(Path.of(string(c,"split_plan_csv"))),"frozen split");
        equal(c.get("original_card_sha256"),hash(Path.of(string(c,"strategy_card_file"))),"research card");
    }
    static void requireAdmitted(Path p,Set<Path> used,Map<String,String> files) throws IOException {
        p=p.toAbsolutePath().normalize();if(!files.containsKey(p.toString())) throw new IOException("Unadmitted input path: "+p);used.add(p);
    }
    static void collectPaths(Object value,Set<Path> used,Map<String,String> files) throws IOException {
        if(value instanceof Path p) {requireAdmitted(p,used,files);return;}
        if(value instanceof Optional<?> o) {if(o.isPresent())collectPaths(o.get(),used,files);return;}
        if(value!=null && value.getClass().isRecord()) for(var component:value.getClass().getRecordComponents()) {
            try { collectPaths(component.getAccessor().invoke(value),used,files); }
            catch(ReflectiveOperationException e){throw new IOException(e);}
        }
    }
    static void guardInputs(Path input,Path settings,Map<String,String> files) throws IOException {
        var used=new HashSet<Path>();requireAdmitted(input,used,files);
        var table=CsvTable.read(input); if(!table.headers().equals(List.of("instrument_id","bars_csv","run_properties","max_abs_lots"))) throw new IOException("Invalid input manifest schema");
        for(var row:table.rows()) {
            Path bars=resolve(input.getParent(),row.get("bars_csv")),config=resolve(input.getParent(),row.get("run_properties"));
            requireAdmitted(bars,used,files);requireAdmitted(config,used,files);
            var run=RunConfig.load(config);
            if(run.researchTrialLedgerPath().isPresent()||run.evaluationSplitPlanPath().isPresent()) throw new IOException("Workflow owns trial binding; remove legacy research settings");
            if(run.executionModel()!=ExecutionModel.BAR_NEXT_OPEN ||run.optionValuationInputPath().isPresent()) throw new IOException("Workflow supports futures bar replay only");
            collectPaths(run,used,files);
        }
        for(String line:Files.readAllLines(settings,StandardCharsets.UTF_8)) {
            String l=line.strip();if(l.isEmpty()||l.startsWith("#"))continue;int i=l.indexOf('=');
            if(i>0&&l.substring(0,i).strip().endsWith("_csv")&&!l.substring(i+1).strip().equals("NONE"))
                requireAdmitted(resolve(settings.getParent(),l.substring(i+1).strip()),used,files);
        }
        equal(used.stream().map(Path::toString).collect(java.util.stream.Collectors.toSet()),files.keySet(),"complete admitted file closure");
    }
    static PortfolioReplayCsvRunner.Prepared prepare(Map<String,Object> c,boolean holdout) throws IOException {
        checkPublic(c);var files=frozenFiles(c,holdout?"holdout_files":"prefix_files");checkFiles(files);
        Path input=Path.of(string(c,holdout?"holdout_inputs_csv":"prefix_inputs_csv")),settings=Path.of(string(c,"portfolio_settings_file"));
        guardInputs(input,settings,files);var p=PortfolioReplayCsvRunner.prepare(input,settings);
        if(p.config().positionBoundaryPolicy()!=CrossSessionPositionPolicy.REQUIRE_FLAT || p.config().pendingIntentBoundaryPolicy()!=PortfolioReplayEngine.PendingIntentBoundaryPolicy.CANCEL_AT_SESSION_BOUNDARY
            || !p.config().requireContiguousFramesWithinSession() || p.config().settlementPolicy()!=PortfolioSettlementPolicy.NONE)
            throw new IOException("Research requires complete intraday sessions, cancelled boundary intents and REQUIRE_FLAT; expiry settlement is a separate workflow");
        if(p.costSchedule().isEmpty()) throw new IOException("Source-attributed dated execution costs required, including synthetic plumbing");
        var split=EvaluationSplitPlan.loadForEvaluation(Path.of(string(c,"split_plan_csv")));
        List<LocalDate> expected=holdout?split.sessions().get(EvaluationSplitPlan.Partition.HOLDOUT):
            java.util.stream.Stream.concat(split.sessions().get(EvaluationSplitPlan.Partition.DEVELOPMENT).stream(),split.sessions().get(EvaluationSplitPlan.Partition.VALIDATION).stream()).toList();
        equal(p.dataset().frames().stream().map(SynchronizedBarFrameBuilder.Frame::sessionDate).distinct().toList(),expected,"complete partition sessions");
        var all=Arrays.stream(EvaluationSplitPlan.Partition.values()).flatMap(x->split.sessions().get(x).stream()).toList();
        for(var ds:p.dataset().datasetsByInstrument().values()) {
            ds.requireCompleteSimulationCoverage();var bar=ds.bars().getFirst();
            if(bar.contractKind()!=ContractKind.FUTURE || !bar.underlying().equals("NIFTY")) throw new IOException("Dated NIFTY futures required; index signal marks and options cannot be futures execution evidence");
            // Public calendar must cover the entire frozen span, including every closed date.
            for(LocalDate d=all.getFirst();!d.isAfter(all.getLast());d=d.plusDays(1))if(!ds.sessionCalendar().containsKey(d))throw new IOException("Incomplete full-plan calendar: "+d);
            var open=ds.sessionCalendar().values().stream().filter(s->s.open()&&!s.sessionDate().isBefore(all.getFirst())&&!s.sessionDate().isAfter(all.getLast())).map(SessionCalendarEntry::sessionDate).sorted().toList();
            equal(open,all,"every open calendar session assigned exactly once");
            for(var d:expected) {p.costSchedule().orElseThrow().calculate(d,bar.instrumentId(),bar.contractKind(),PortfolioExecution.Side.BUY,bar.open(),1);p.costSchedule().orElseThrow().calculate(d,bar.instrumentId(),bar.contractKind(),PortfolioExecution.Side.SELL,bar.open(),1);}
        }
        checkFiles(files);return p.withStrategy(new SessionSmaStrategy((PortfolioSmaCrossoverStrategy)p.strategy(),p.dataset(),digest(c)));
    }
    public void validate(String id) throws IOException { prepare(card(id),false); }
    String event(String id,String attempt,ResearchOutcomeLedger.EventType type,String reference,String reason) throws IOException {
        return ResearchOutcomeLedger.append(ledger(),outcomes(),id,attempt,"e-"+UUID.randomUUID(),type,reference,reason).recordHashSha256();
    }
    static Map<String,Object> artifact(PortfolioReplayCsvRunner.Prepared p,Path root) throws IOException {
        var result=p.replay().result(); Path folder=root.resolve(result.replayFingerprintSha256());
        if(Files.exists(folder)) {
            var saved=PortfolioReplayArtifactVerifier.verify(folder); Path rerun=Files.createTempDirectory(root.getParent(),"reproduce-");
            var generated=new PortfolioReplayArtifactWriter().write(rerun,p.dataset(),result);
            equal(saved.outputSha256(),PortfolioReplayArtifactVerifier.verify(generated.path()).outputSha256(),"reproduced output hashes");
            for(var name:saved.outputSha256().keySet())if(Files.mismatch(folder.resolve(name),generated.path().resolve(name))!=-1)throw new IOException("Replay bytes differ");
        } else {new PortfolioReplayArtifactWriter().write(root,p.dataset(),result);}
        var run=PortfolioTrialIdentity.inspect(folder);PortfolioReplayArtifactVerifier.verify(folder);
        return object("run_id",run.verification().runId(),"run_manifest_sha256",run.manifestSha256(),
            "validated_datasets_sha256",p.dataset().datasetsSha256(),"replay_parameter_sha256",run.parameterConfigSha256(),
            "strategy_id",run.strategyId(),"build_commit",result.buildCommit(),"code_sha256",hash(actualCode()),
            "output_sha256",new TreeMap<>(run.verification().outputSha256()));
    }
    public void evaluate(String id) throws IOException {
        mutable();var c=card(id);Path receipt=evaluationPath(id);
        String attempt="eval-"+UUID.randomUUID();var link=object("schema","INTRA_NIFTY_EVALUATION_RUN_V1","trial_id",id,
            "trial_card_sha256",digest(c),"evaluation_id",attempt,"partition","DEVELOPMENT_VALIDATION","status","ATTEMPT_STARTED","artifact",null);
        save(receipt,link);event(id,attempt,ResearchOutcomeLedger.EventType.ATTEMPT_STARTED,attempt,"OFFLINE_REPLAY");
        try {
            var p=prepare(c,false);var a=artifact(p,workspace.resolve("runs"));checkPublic(c);checkFiles(frozenFiles(c,"prefix_files"));
            link.put("artifact",a);link.put("status","OUTCOME_PRODUCED");
            Files.writeString(receipt,write(link),StandardCharsets.UTF_8,StandardOpenOption.TRUNCATE_EXISTING);
            event(id,attempt,ResearchOutcomeLedger.EventType.OUTCOME_PRODUCED,string(a,"run_id"),"SYNTHETIC_OR_OFFLINE_DIAGNOSTIC");
        }catch(IOException|RuntimeException e) {
            if(link.get("artifact")==null) {link.put("status","MECHANICAL_FAILURE_NO_OUTCOME");Files.writeString(receipt,write(link),StandardCharsets.UTF_8,StandardOpenOption.TRUNCATE_EXISTING);
                event(id,attempt,ResearchOutcomeLedger.EventType.MECHANICAL_FAILURE_NO_OUTCOME,attempt,"INVALID_OR_INCOMPLETE_INPUT");}
            throw e;
        }
    }
    void view(String id,Map<String,Object> link) throws IOException {
        String attempt=string(link,"evaluation_id");var entries=ResearchOutcomeLedger.entries(ledger(),outcomes());
        if(entries.stream().noneMatch(e->e.trialId().equals(id)&&e.attemptId().equals(attempt)&&e.eventType()==ResearchOutcomeLedger.EventType.OUTCOME_VIEWED))
            event(id,attempt,ResearchOutcomeLedger.EventType.OUTCOME_VIEWED,digest(link),"REPORT_ACCESS");
    }
    public Path report(Path outputRoot) throws IOException { return NiftyResearchReport.publish(this,outputRoot); }
    public void replayVerify(String id) throws IOException {
        var c=card(id);var link=json(evaluationPath(id));equal(string(link,"status"),"OUTCOME_PRODUCED","evaluation status");
        var rebuilt=artifact(prepare(c,false),workspace.resolve("runs"));equal(link.get("artifact"),rebuilt,"report-to-replay link");
    }
    HoldoutReleaseInputs releaseInputs() throws IOException {
        var gate=json(workspace.resolve("gate/freeze-inputs.json"));
        return new HoldoutReleaseInputs(ledger(),workspace.resolve("gate/catalog.json"),actualCode(),
            Path.of(string(gate,"split_plan_csv")),workspace.resolve("gate/protocol.json"));
    }
    public PortfolioHoldoutReleaseController.Receipt freeze(Path report,PortfolioHoldoutReleaseController controller,String requestId) throws IOException {
        mutable();NiftyResearchReport.verify(report);var recipe=json(report.resolve("recipe.json"));
        equal(recipe.get("ledger_sha256"),hash(ledger()),"current complete registration family");
        equal(recipe.get("outcomes_sha256"),hash(outcomes()),"current outcome audit");
        var result=json(report.resolve("report.json"));String selected=string(result,"selected_trial_id");
        var rows=new ArrayList<Object>();var cards=parsedObject(recipe.get("cards"),"cards");
        var links=parsedObject(recipe.get("evaluations"),"evaluations");Map<String,Object> first=null;
        for(var entry:ResearchTrialLedger.entries(ledger())) {
            String id=entry.trial().trialId();var c=parsedObject(cards.get(id),"card");equal(card(id),c,"current frozen card");checkPublic(c);
            if(first==null)first=c;var t=entry.trial(); var link=parsedObject(links.get(id),"link");
            String runId=digest(object("no_result_trial",id)),manifest=runId;
            if(link.get("artifact")!=null){var a=parsedObject(link.get("artifact"),"artifact");runId=string(a,"run_id");manifest=string(a,"run_manifest_sha256");}
            rows.add(object("trial_id",id,"strategy_id",t.strategyId(),"strategy_version",t.strategyVersion(),"variant_id",t.variantId(),
                "strategy_card_sha256",t.strategyCardSha256(),"dataset_sha256",t.datasetSha256(),"split_plan_sha256",t.splitPlanSha256(),
                "parameter_config_sha256",t.parameterConfigSha256(),"registration_record_sha256",entry.recordHashSha256(),"run_id",runId,
                "run_manifest_sha256",manifest,"split_plan_csv",string(c,"split_plan_csv"),"starting_capital_inr",string(c,"starting_capital_inr")));
        }
        // Create-new intent closes tuning even if subsequent persistence fails.
        Files.createDirectory(workspace.resolve("gate"));
        save(workspace.resolve("gate/catalog.json"),object("schema","INTRA_PORTFOLIO_TRIAL_CATALOG_V1","artifact_scope","DEVELOPMENT_VALIDATION_ONLY","trials",rows));
        save(workspace.resolve("gate/freeze-inputs.json"),object("selected_trial_id",selected,"report_folder",report.toAbsolutePath().normalize().toString(),
            "report_manifest_sha256",hash(report.resolve("manifest.json")),"split_plan_csv",string(first,"split_plan_csv"),"selected_card",card(selected)));
        save(workspace.resolve("gate/protocol.json"),object("schema","INTRA_HOLDOUT_PROTOCOL_V1","selected_trial_ids",List.of(selected),
            "evaluation_spec",write(object("selection_rule",RULE,"report_manifest_sha256",hash(report.resolve("manifest.json")),
                "selected_card",card(selected),"metrics","fixed-capital session returns; net P&L, sourced costs, exposure, turnover, drawdown, nullable Sharpe; no tuning")).strip(),
            "failure_policy","CONSUME_ON_START_NO_RETRY"));
        return controller.freeze(releaseInputs(),requestId);
    }
    public PortfolioHoldoutReleaseController.Receipt holdout(PortfolioHoldoutReleaseController controller,String evaluationId) throws IOException {
        return controller.evaluate(releaseInputs(),evaluationId,binding->{
            var gate=json(workspace.resolve("gate/freeze-inputs.json"));var c=parsedObject(gate.get("selected_card"),"selected card");
            // The signed protocol commits this exact card independently of the mutable helper file.
            var protocol=json(workspace.resolve("gate/protocol.json"));
            var spec=parsedObject(StrictJsonParser.parse(string(protocol,"evaluation_spec")),"evaluation specification");equal(spec.get("selected_card"),c,"signed selected parameters");
            var report=Path.of(string(gate,"report_folder"));equal(spec.get("report_manifest_sha256"),hash(report.resolve("manifest.json")),"selection report");NiftyResearchReport.verify(report);
            String id=string(c,"trial_id");equal(card(id),c,"registered selected trial");event(id,evaluationId,ResearchOutcomeLedger.EventType.ATTEMPT_STARTED,evaluationId,"HOLDOUT_OPENED");
            try {
                var p=prepare(c,true); var prefix=prepare(c,false);
                equal(p.dataset().datasetsByInstrument().keySet(),prefix.dataset().datasetsByInstrument().keySet(),"holdout dated instrument set");
                for(String instrument:p.dataset().datasetsByInstrument().keySet()) {
                    var a=p.dataset().datasetsByInstrument().get(instrument);var b=prefix.dataset().datasetsByInstrument().get(instrument);
                    var x=a.bars().getFirst();var y=b.bars().getFirst();
                    equal(List.of(x.instrumentId(),x.expiry(),x.tradingSymbol(),x.sourceToken(),x.lotSize(),x.tickSize()),List.of(y.instrumentId(),y.expiry(),y.tradingSymbol(),y.sourceToken(),y.lotSize(),y.tickSize()),"same dated contract");
                    equal(a.calendarSha256(),b.calendarSha256(),"full frozen calendar");
                }
                var a=artifact(p,workspace.resolve("holdout-runs"));var output=object("schema","INTRA_NIFTY_HOLDOUT_EVALUATION_V1","classification","OFFLINE_DIAGNOSTIC_ONLY",
                    "trial_id",id,"trial_card_sha256",digest(c),"evaluation_id",evaluationId,"holdout_opened",true,
                    "selection_report_manifest_sha256",spec.get("report_manifest_sha256"),"artifact",a,
                    "metrics",NiftyResearchReport.holdoutMetrics(c,p.dataset(),workspace.resolve("holdout-runs").resolve(string(a,"run_id"))),"performance_claim_eligible",false,"blindness_claim_eligible",false);
                event(id,evaluationId,ResearchOutcomeLedger.EventType.OUTCOME_PRODUCED,string(a,"run_id"),"HOLDOUT_RESULT");
                return write(output).getBytes(StandardCharsets.UTF_8);
            }catch(Exception e){event(id,evaluationId,ResearchOutcomeLedger.EventType.MECHANICAL_FAILURE_NO_OUTCOME,evaluationId,"HOLDOUT_FAILURE_CONSUMED");throw e;}
        });
    }
    public void verifyHoldout(byte[] savedResult) throws IOException {
        var result=parsedObject(StrictJsonParser.parse(PortfolioTrialIdentity.utf8(savedResult)),"holdout result");
        equal(result.get("schema"),"INTRA_NIFTY_HOLDOUT_EVALUATION_V1","holdout report schema");
        equal(result.get("performance_claim_eligible"),false,"holdout evidence flag");
        equal(result.get("holdout_opened"),true,"opened holdout report");
        String id=string(result,"trial_id");var c=card(id);equal(result.get("trial_card_sha256"),digest(c),"registered holdout trial");
        var protocol=json(workspace.resolve("gate/protocol.json"));
        var spec=parsedObject(StrictJsonParser.parse(string(protocol,"evaluation_spec")),"signed protocol");
        equal(spec.get("selected_card"),c,"selected holdout parameters");
        equal(spec.get("report_manifest_sha256"),result.get("selection_report_manifest_sha256"),"selection report link");
        var a=parsedObject(result.get("artifact"),"holdout replay");Path folder=workspace.resolve("holdout-runs").resolve(string(a,"run_id"));
        var verified=PortfolioTrialIdentity.inspect(folder);PortfolioReplayArtifactVerifier.verify(folder);
        equal(verified.manifestSha256(),a.get("run_manifest_sha256"),"holdout manifest");
        equal(new TreeMap<>(verified.verification().outputSha256()),a.get("output_sha256"),"holdout output hashes");
        // This is result verification after logged delivery, not another strategy replay.
        var p=prepare(c,true);equal(p.dataset().datasetsSha256(),verified.verification().validatedDatasetsSha256(),"holdout admitted data");
        equal(PortfolioTrialIdentity.parameterConfigSha256(p.config(),p.strategy(),p.fillModel(),p.costSchedule()),verified.parameterConfigSha256(),"frozen holdout strategy/cost parameters");
        equal(write(canonical(NiftyResearchReport.holdoutMetrics(c,p.dataset(),folder))),write(canonical(result.get("metrics"))),"holdout report-to-run metrics");
    }
    static PublicKey publicKey(Path path) throws Exception {return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Files.readAllBytes(path)));}
    static KeyPair journalKey(Path pub,Path secret) throws Exception {return new KeyPair(publicKey(pub),KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(secret))));}
    public static void main(String[] args) {int code=run(args,System.out,System.err);if(code!=0)System.exit(code);}
    public static int run(String[] args,PrintStream out,PrintStream err) {
        try {
            if(args.length==2&&args[0].equals("verify-report")){NiftyResearchReport.verify(Path.of(args[1]));out.println("REPORT_VERIFIED performance_claim_eligible=false");return 0;}
            if(args.length<3)throw new IllegalArgumentException("Usage: NiftyResearchWorkflow <register|validate|evaluate|report|replay-verify> <workspace> <plan.json|trial-id|report-output-root>; verify-report <report-folder>; <freeze|authorize|holdout|status|read-holdout|consume-interrupted> <workspace> <report-folder|token-file|request-id> <custodian-public.der> <journal-public.der> <journal-private.der>");
            var w=new NiftyResearchWorkflow(Path.of(args[1]));Files.createDirectories(w.workspace);
            try(var channel=FileChannel.open(w.workspace.resolve("workflow.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
                if(lock==null)throw new IOException("Research workflow busy");
                if(args.length==3) switch(args[0]) {
                    case "register" -> {var r=w.register(Path.of(args[2]));out.println("REGISTERED trial="+r.trial().trialId()+" sequence="+r.sequence()+" timestamp="+r.registeredAtUtc());}
                    case "validate" -> {w.validate(args[2]);out.println("PLAN_VALIDATED holdout_opened=false");}
                    case "evaluate" -> {w.evaluate(args[2]);out.println("EVALUATED partitions=DEVELOPMENT,VALIDATION performance_claim_eligible=false");}
                    case "report" -> out.println("REPORT="+w.report(Path.of(args[2])));
                    case "replay-verify" -> {w.replayVerify(args[2]);out.println("REPLAY_VERIFIED");}
                    default -> throw new IllegalArgumentException("Unknown research command");
                } else if(args.length==6) {
                    var controller=new PortfolioHoldoutReleaseController(w.workspace.resolve("gate/release.history"),publicKey(Path.of(args[3])),journalKey(Path.of(args[4]),Path.of(args[5])));
                    switch(args[0]) {
                        case "freeze" -> out.print(w.freeze(Path.of(args[2]),controller,"freeze-"+UUID.randomUUID()).toJson());
                        case "authorize" -> out.print(controller.authorize(w.releaseInputs(),PortfolioHoldoutReleaseController.Authorization.decode(Files.readString(Path.of(args[2])).strip())).toJson());
                        case "holdout" -> out.print(w.holdout(controller,args[2]).toJson());
                        case "status" -> out.print(controller.status(args[2]).toJson());
                        case "consume-interrupted" -> out.print(controller.consumeInterrupted(w.releaseInputs(),args[2]).toJson());
                        case "read-holdout", "verify-holdout" -> {byte[] bytes=controller.readResult(args[2]);var result=parsedObject(StrictJsonParser.parse(PortfolioTrialIdentity.utf8(bytes)),"holdout result");w.view(string(result,"trial_id"),result);w.verifyHoldout(bytes);if(args[0].equals("read-holdout"))out.write(bytes);else out.println("HOLDOUT_REPORT_AND_ARTIFACTS_VERIFIED no_replay=true");}
                        default -> throw new IllegalArgumentException("Unknown holdout command");
                    }
                } else throw new IllegalArgumentException("Invalid research command arity");
            }
            return 0;
        }catch(Exception e){err.println("Offline research failed: "+e.getMessage());return 1;}
    }
}
