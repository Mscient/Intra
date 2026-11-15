package com.mscient.intrapaper.evaluation.portfolio;

import com.mscient.intrapaper.evaluation.*;
import com.mscient.intrapaper.data.StrictJsonParser;
import com.mscient.intrapaper.provenance.RunConfig;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import static com.mscient.intrapaper.evaluation.portfolio.PortfolioEvaluationJson.*;
import static com.mscient.intrapaper.evaluation.portfolio.NiftyResearchWorkflow.*;

/** Synthetic only: complete dated-contract plumbing, never evidence of a market edge. */
public final class NiftyResearchWorkflowTest {
    static int checks;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    interface Action {void run() throws Exception;}
    static void fails(Action a,String label)throws Exception {checks++;try{a.run();}catch(IOException|IllegalArgumentException expected){return;}throw new AssertionError("Accepted: "+label);}
    static int cli(String... args)throws Exception {var bytes=new ByteArrayOutputStream();try(var sink=new PrintStream(bytes,true,StandardCharsets.UTF_8)){int code=NiftyResearchWorkflow.run(args,sink,sink);if(code!=0)System.err.print(bytes.toString(StandardCharsets.UTF_8));return code;}}
    public static void main(String[] args)throws Exception {
        if(args.length==2&&args[0].equals("synthetic-keys")) {
            Path keys=Path.of(args[1]);Files.createDirectories(keys);var generator=KeyPairGenerator.getInstance("Ed25519");
            for(String name:List.of("custodian","journal")){var pair=generator.generateKeyPair();Files.write(keys.resolve(name+"-public.der"),pair.getPublic().getEncoded());Files.write(keys.resolve(name+"-private.der"),pair.getPrivate().getEncoded());}
            Files.writeString(keys.resolve("SYNTHETIC_ONLY.txt"),"Local test signing keys, no independent custody or performance evidence.");return;
        }
        if(args.length==3&&args[0].equals("synthetic-authorize")) {
            Path workspace=Path.of(args[1]),keys=Path.of(args[2]);var ctl=new PortfolioHoldoutReleaseController(workspace.resolve("gate/release.history"),publicKey(keys.resolve("custodian-public.der")),journalKey(keys.resolve("journal-public.der"),keys.resolve("journal-private.der")));
            var receipt=ctl.status("synthetic-review");var secret=KeyFactory.getInstance("Ed25519").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Files.readAllBytes(keys.resolve("custodian-private.der"))));
            var token=PortfolioHoldoutReleaseController.Authorization.sign(receipt,"synthetic-authorization",RunConfig.sha256("SYNTHETIC_NOT_INDEPENDENT".getBytes(StandardCharsets.UTF_8)),Instant.now().plusSeconds(3600),secret);
            Files.writeString(keys.resolve("authorization.token"),token.encode());return;
        }
        Path project=Path.of(args[0]),root=Path.of(args[1]);Files.createDirectories(root);
        if(args.length==3&&args[2].equals("fixture")){fixture(project,root);System.out.println("SYNTHETIC_FIXTURE="+root.toAbsolutePath());return;}
        happy(project,root.resolve("happy"));failed(project,root.resolve("failed"));invalidInputs(project,root.resolve("invalid"));consumedFailure(project,root.resolve("holdout-failure"));
        System.out.println("NiftyResearchWorkflowTest: "+checks+" synthetic research checks passed; performance_claim_eligible=false");
    }
    static void happy(Path project,Path root)throws Exception {
        fixture(project,root);var w=new NiftyResearchWorkflow(root.resolve("workspace"));
        Path holdout=root.resolve("holdout.csv");Path sealed=root.resolve("sealed.tmp");Files.move(holdout,sealed);
        for(String id:List.of("sma-a","sma-b","empty")){check(cli("register",w.workspace.toString(),root.resolve(id+".json").toString())==0,"register without opening holdout");}
        fails(()->w.register(root.resolve("sma-a.json")),"duplicate immutable trial");
        var first=ResearchTrialLedger.entries(w.ledger()).getFirst();check(first.registeredAtUtc()!=null,"registration timestamp");
        check(ResearchTrialLedger.entries(w.ledger()).size()==3,"all variants registered before results");
        for(String id:List.of("sma-a","sma-b","empty")) {
            check(cli("validate",w.workspace.toString(),id)==0,"chronological full plan valid with sealed holdout");
            check(cli("evaluate",w.workspace.toString(),id)==0,"development/validation replay");
            fails(()->w.evaluate(id),"repeat evaluation cannot overwrite attempt");
        }
        check(ResearchOutcomeLedger.verify(w.ledger(),w.outcomes()).outcomeViews()==0,"produced/unviewed state preserved");
        Path report=w.report(root.resolve("reports"));check(cli("verify-report",report.toString())==0,"report verified");
        var result=json(report.resolve("report.json"));check(result.get("selected_trial_id")!=null,"frozen validation-only selection");
        check(result.get("multiple_comparison_diagnostics")==null,"zero-variance variant prevents invented correction");
        var rows=(List<?>)result.get("trials");check(rows.size()==3,"empty trial retained");
        var empty=parsedObject(rows.stream().filter(x->{try{return string(parsedObject(x,"row"),"trial_id").equals("empty");}catch(IOException e){throw new RuntimeException(e);}}).findFirst().orElseThrow(),"empty");
        var emptyMetrics=parsedObject(empty.get("metrics"),"metrics");check(emptyMetrics.get("daily_sharpe")==null,"honest undefined empty Sharpe");
        check(new java.math.BigDecimal(emptyMetrics.get("execution_count").toString()).signum()==0,"no trades is a result");
        check(((List<?>)parsedObject(result.get("walk_forward"),"walk forward").get("windows")).size()==2,"explicit forward chronological windows");
        check(ResearchOutcomeLedger.verify(w.ledger(),w.outcomes()).outcomeViews()==3,"report access recorded for every produced trial");
        var views=hash(w.outcomes());Path same=w.report(root.resolve("reports"));check(same.equals(report)&&views.equals(hash(w.outcomes())),"unchanged inputs reproduce report without duplicate views");
        check(cli("replay-verify",w.workspace.toString(),"sma-a")==0,"deterministic replay outputs");
        var a=parsedObject(json(w.evaluationPath("sma-a")).get("artifact"),"artifact");Path run=w.workspace.resolve("runs").resolve(string(a,"run_id"));
        Path equity=run.resolve("equity.csv");String equityText=Files.readString(equity);Files.writeString(equity,equityText+"tampered");fails(()->NiftyResearchReport.verify(report),"tampered run output");Files.writeString(equity,equityText);
        Path missing=run.resolve("orders.csv"),backup=run.resolve("orders.tmp");Files.move(missing,backup);fails(()->NiftyResearchReport.verify(report),"missing artifact");Files.move(backup,missing);
        Path manifest=run.resolve("run-manifest.properties");String manifestText=Files.readString(manifest);Files.writeString(manifest,manifestText.replace("performance_claim_eligible=false","performance_claim_eligible=true"));fails(()->NiftyResearchReport.verify(report),"tampered manifest and eligibility");Files.writeString(manifest,manifestText);
        Path recipePath=report.resolve("recipe.json"),reportManifest=report.resolve("manifest.json");
        String originalRecipe=Files.readString(recipePath),originalManifest=Files.readString(reportManifest);
        var recipe=json(recipePath);var evs=parsedObject(recipe.get("evaluations"),"evaluations");
        parsedObject(evs.get("sma-a"),"link").put("artifact",parsedObject(evs.get("sma-b"),"link").get("artifact"));
        Files.writeString(recipePath,write(recipe));var refreshed=json(reportManifest);parsedObject(refreshed.get("files"),"hashes").put("recipe.json",hash(recipePath));Files.writeString(reportManifest,write(refreshed));
        fails(()->NiftyResearchReport.verify(report),"rehashed report-to-run substitution rejected semantically");
        Files.writeString(recipePath,originalRecipe);Files.writeString(reportManifest,originalManifest);
        Path body=report.resolve("report.json");String bodyText=Files.readString(body);Files.writeString(body,bodyText.replace("sma-a","other"));fails(()->NiftyResearchReport.verify(report),"report-to-trial mismatch");Files.writeString(body,bodyText);
        var generator=KeyPairGenerator.getInstance("Ed25519");var custodian=generator.generateKeyPair();var journal=generator.generateKeyPair();
        var controller=new PortfolioHoldoutReleaseController(w.workspace.resolve("release.history"),custodian.getPublic(),journal);
        final boolean[] invoked={false};
        fails(()->controller.evaluate(new HoldoutReleaseInputs(w.ledger(),root.resolve("no-catalog"),actualCode(),root.resolve("split.csv"),root.resolve("no-protocol")),"early",b->{invoked[0]=true;return new byte[]{1};}),"early holdout");check(!invoked[0],"no callback before authorization");
        var frozen=w.freeze(report,controller,"freeze-1");check(frozen.state()==PortfolioHoldoutReleaseController.State.FROZEN,"selection and complete family frozen");
        fails(()->w.register(root.resolve("sma-a.json")),"tuning/new registration after freeze");fails(()->w.evaluate("sma-a"),"development tuning after freeze");
        fails(()->w.holdout(controller,"before-authorization"),"holdout only after authorization");
        Files.move(sealed,holdout);var token=PortfolioHoldoutReleaseController.Authorization.sign(frozen,"synthetic-auth",RunConfig.sha256("SYNTHETIC_NOT_INDEPENDENT_REVIEW".getBytes(StandardCharsets.UTF_8)),Instant.now().plusSeconds(3600),custodian.getPrivate());
        controller.authorize(w.releaseInputs(),token);var consumed=w.holdout(controller,"holdout-1");check(consumed.state()==PortfolioHoldoutReleaseController.State.CONSUMED&&consumed.outcome().equals("SUCCESS"),"one concrete holdout adapter run");
        var bytes=controller.readResult("delivery-1");var h=parsedObject(StrictJsonParser.parse(PortfolioTrialIdentity.utf8(bytes)),"holdout result");check(Boolean.TRUE.equals(h.get("holdout_opened"))&&"holdout-1".equals(h.get("evaluation_id")),"opened by evaluation ID");
        check(Arrays.equals(bytes,controller.readResult("delivery-2")),"repeated delivery returns same bytes");
        w.verifyHoldout(bytes);checks++;var changedMetrics=parsedObject(h.get("metrics"),"metrics");Object originalNet=changedMetrics.get("net_pnl_inr");changedMetrics.put("net_pnl_inr",new java.math.BigDecimal("9999"));fails(()->w.verifyHoldout(write(h).getBytes(StandardCharsets.UTF_8)),"holdout report-to-run metric mismatch");changedMetrics.put("net_pnl_inr",originalNet);
        fails(()->w.holdout(controller,"holdout-2"),"repeat holdout denied");fails(()->w.register(root.resolve("sma-b.json")),"tune after holdout results available");
        NiftyResearchReport.verify(report);checks++;check(Boolean.FALSE.equals(h.get("performance_claim_eligible")),"holdout no performance gate");
        var receiptPath=w.workspace.resolve("release.history.result");Files.writeString(receiptPath,"tampered");fails(()->controller.readResult("delivery-tampered"),"tampered sealed report");
    }
    static void consumedFailure(Path project,Path root)throws Exception {
        fixture(project,root);var w=new NiftyResearchWorkflow(root.resolve("workspace"));
        for(String id:List.of("sma-a","sma-b")){w.register(root.resolve(id+".json"));w.evaluate(id);}
        Path report=w.report(root.resolve("reports"));var generator=KeyPairGenerator.getInstance("Ed25519");var c=generator.generateKeyPair();var j=generator.generateKeyPair();
        var controller=new PortfolioHoldoutReleaseController(w.workspace.resolve("release.history"),c.getPublic(),j);
        var frozen=w.freeze(report,controller,"freeze");var authorization=PortfolioHoldoutReleaseController.Authorization.sign(frozen,"synthetic-auth",hash(root.resolve("strategy.md")),Instant.now().plusSeconds(3600),c.getPrivate());
        controller.authorize(w.releaseInputs(),authorization);Files.writeString(root.resolve("holdout.csv"),"tampered holdout");
        fails(()->w.holdout(controller,"failed-holdout"),"changed committed holdout input consumes access");
        var status=controller.status("check-failed");check(status.state()==PortfolioHoldoutReleaseController.State.CONSUMED&&status.outcome().equals("FAILED"),"failed adapter cannot be retried");
        fails(()->w.holdout(controller,"retry-failed"),"failed holdout retry denied");
        check(ResearchOutcomeLedger.verify(w.ledger(),w.outcomes()).mechanicalFailuresWithoutOutcome()==1,"holdout failure in existing audit ledger");
        fails(()->controller.readResult("no-result"),"failed holdout has no invented performance result");
    }
    static void failed(Path project,Path root)throws Exception {
        fixture(project,root);var w=new NiftyResearchWorkflow(root.resolve("workspace"));
        var settings=root.resolve("a.properties");Files.writeString(settings,Files.readString(settings).replace("strategy.fast_bars=2","strategy.fast_bars=1"));
        w.register(root.resolve("sma-a.json"));fails(()->w.evaluate("sma-a"),"invalid parameters registered then failed");
        check(ResearchOutcomeLedger.verify(w.ledger(),w.outcomes()).mechanicalFailuresWithoutOutcome()==1,"mechanical failure retained");
        w.register(root.resolve("sma-b.json"));var report=w.report(root.resolve("reports"));var r=json(report.resolve("report.json"));check(r.get("selected_trial_id")==null,"no selection from incomplete family");
        check(((List<?>)r.get("trials")).size()==2,"failed and unattempted variants both retained");
        var gen=KeyPairGenerator.getInstance("Ed25519");var c=gen.generateKeyPair();var j=gen.generateKeyPair();var ctl=new PortfolioHoldoutReleaseController(w.workspace.resolve("release"),c.getPublic(),j);
        fails(()->w.freeze(report,ctl,"freeze-incomplete"),"incomplete family cannot open holdout");
    }
    static void invalidInputs(Path project,Path root)throws Exception {
        fixture(project,root);var w=new NiftyResearchWorkflow(root.resolve("workspace"));w.register(root.resolve("sma-a.json"));var c=w.card("sma-a");
        Path settings=root.resolve("a.properties");String original=Files.readString(settings);Files.writeString(settings,original.replace("strategy.long_lots=1","strategy.long_lots=2"));fails(()->w.validate("sma-a"),"frozen parameters changed");Files.writeString(settings,original);
        Path bars=root.resolve("prefix.csv");String rows=Files.readString(bars);Files.writeString(bars,rows.replaceFirst(",100,101,99,100,",",101,102,100,101,"));fails(()->w.validate("sma-a"),"data change changes admitted identity");Files.writeString(bars,rows);
        Path split=root.resolve("split.csv");String splitText=Files.readString(split);Files.writeString(split,splitText+splitText.lines().skip(1).findFirst().orElseThrow()+"\n");fails(()->EvaluationSplitPlan.loadForEvaluation(split),"duplicate split session");Files.writeString(split,splitText.replaceFirst("DEVELOPMENT","HOLDOUT"));fails(()->EvaluationSplitPlan.loadForEvaluation(split),"reassigned chronological boundary");Files.writeString(split,splitText);
        var changed=new LinkedHashMap<>(c);changed.put("settings_sha256","a".repeat(64));check(!trial(changed).parameterConfigSha256().equals(trial(c).parameterConfigSha256()),"settings identity change");
        changed=new LinkedHashMap<>(c);changed.put("code_sha256","b".repeat(64));check(!trial(changed).parameterConfigSha256().equals(trial(c).parameterConfigSha256()),"code identity change");
        changed=new LinkedHashMap<>(c);var admitted=new TreeMap<>(frozenFiles(c,"prefix_files"));admitted.put(bars.toAbsolutePath().toString(),"c".repeat(64));changed.put("prefix_files",admitted);check(!trial(changed).datasetSha256().equals(trial(c).datasetSha256()),"data identity change");
        changed=new LinkedHashMap<>(c);changed.put("holdout_files",Map.of("future.csv","d".repeat(64)));check(!trial(changed).strategyCardSha256().equals(trial(c).strategyCardSha256()),"holdout commitment change");
        // New fixture identities bind every negative input before evaluation rather than bypassing frozen hashes.
        for(String kind:List.of("missing-session","partial-session","duplicate-bar","index","cost-source","cost-boundary")) {
            Path negative=root.resolve(kind);fixture(project,negative);Path b=negative.resolve("prefix.csv");String text=Files.readString(b);
            if(kind.equals("missing-session"))text=text.lines().filter(l->!l.startsWith("2030-05-07")).collect(java.util.stream.Collectors.joining("\n"))+"\n";
            if(kind.equals("partial-session"))text=text.lines().filter(l->!l.startsWith("2030-05-06T09:17")).collect(java.util.stream.Collectors.joining("\n"))+"\n";
            if(kind.equals("duplicate-bar"))text+=text.lines().skip(1).findFirst().orElseThrow()+"\n";
            if(kind.equals("index"))text=text.replace(",FUTURE,NIFTY,",",INDEX,NIFTY,");Files.writeString(b,text);
            if(kind.startsWith("cost")){Path costs=negative.resolve("costs.csv");String cost=Files.readString(costs);cost=kind.equals("cost-source")?cost.replace(",synthetic cost fixture",","):cost.replace("2030-01-01,2031-01-01","2030-01-01,2030-05-06");Files.writeString(costs,cost);}
            refreshPlans(negative);var n=new NiftyResearchWorkflow(negative.resolve("workspace"));n.register(negative.resolve("sma-a.json"));fails(()->n.evaluate("sma-a"),kind+" rejected");
            check(ResearchOutcomeLedger.verify(n.ledger(),n.outcomes()).mechanicalFailuresWithoutOutcome()==1,kind+" failure audited");
        }
        check(nullableStatistics(new double[]{0}).get("daily_sharpe")==null,"one session undefined");
    }
    static Map<String,Object> nullableStatistics(double[] r){return NiftyResearchReport.nullableStatistics(r);}
    static void refreshPlans(Path root)throws Exception {
        var prefix=new TreeMap<String,String>();var holdout=new TreeMap<String,String>();
        for(String name:List.of("prefix-inputs.csv","prefix.csv","prefix.properties","master.csv","calendar.csv","rules.csv","costs.csv"))prefix.put(name,hash(root.resolve(name)));
        for(String name:List.of("holdout-inputs.csv","holdout.csv","holdout.properties","master.csv","calendar.csv","rules.csv","costs.csv"))holdout.put(name,hash(root.resolve(name)));
        for(String id:List.of("sma-a","sma-b","empty")){var plan=json(root.resolve(id+".json"));plan.put("prefix_files",prefix);plan.put("holdout_files",holdout);Files.writeString(root.resolve(id+".json"),write(plan));}
    }
    static void fixture(Path project,Path root)throws Exception {
        Files.createDirectories(root);String header=Files.readAllLines(project.resolve("examples/synthetic-futures-bars.csv")).getFirst();
        Files.writeString(root.resolve("master.csv"),Files.readAllLines(project.resolve("examples/synthetic-instrument-master.csv")).getFirst()+"\nSYN-NIFTY,SYN,FUT,SYN-NIFTY,SYN-NIFTY-DEC30,FUTURE,NIFTY,2030-12-26,,,1,0.05,2030-01-01,\n");
        Files.writeString(root.resolve("rules.csv"),Files.readString(project.resolve("examples/synthetic-market-rule-regimes.csv")).replace("09:45","09:21"));
        var dates=new ArrayList<LocalDate>();for(LocalDate date=LocalDate.of(2030,5,6);dates.size()<10;date=date.plusDays(1))if(date.getDayOfWeek()!=DayOfWeek.SATURDAY&&date.getDayOfWeek()!=DayOfWeek.SUNDAY)dates.add(date);
        var calendar=new StringBuilder("session_date,session_status,first_bar_timestamp,last_bar_timestamp,market_rule_regime_id,regime_source_reference\n");var split=new StringBuilder("session_date,partition\n");
        for(LocalDate d=dates.getFirst();!d.isAfter(dates.getLast());d=d.plusDays(1)) {
            if(dates.contains(d))calendar.append(d).append(",OPEN,").append(d).append("T09:15:00+05:30,").append(d).append("T09:20:00+05:30,SYNTHETIC_V1,synthetic fixture only; not an exchange ruleset\n");else calendar.append(d).append(",CLOSED,,,,\n");
        }
        var prefix=new StringBuilder(header+"\n");var holdout=new StringBuilder(header+"\n");int[] changes={2,-2,5,1,-1,3,-4,6,4,-3};
        for(int i=0;i<dates.size();i++) {
            LocalDate d=dates.get(i);split.append(d).append(',').append(i<4?"DEVELOPMENT":i<8?"VALIDATION":"HOLDOUT").append('\n');
            int delta=changes[i];int[] prices={100,101,102,103+delta,104+delta,107+2*delta};
            for(int k=0;k<6;k++){int price=prices[k];(i<8?prefix:holdout).append(d).append("T09:").append(15+k).append(":00+05:30,SYN-NIFTY,SYN,FUT,SYN-NIFTY,SYN-NIFTY-DEC30,FUTURE,NIFTY,2030-12-26,,,1,0.05,ONE_MINUTE,BAR_START,").append(price).append(',').append(price+1).append(',').append(price-1).append(',').append(price).append(",100,1000,SYNTHETIC_FIXTURE,").append(RunConfig.sha256((d.toString()+k).getBytes(StandardCharsets.UTF_8))).append('\n');}
        }
        Files.writeString(root.resolve("calendar.csv"),calendar);Files.writeString(root.resolve("split.csv"),split);Files.writeString(root.resolve("prefix.csv"),prefix);Files.writeString(root.resolve("holdout.csv"),holdout);
        String config=Files.readString(project.resolve("examples/synthetic-run.properties")).replace("synthetic-instrument-master.csv","master.csv").replace("synthetic-session-calendar.csv","calendar.csv").replace("synthetic-market-rule-regimes.csv","rules.csv").replaceAll("(?m)^cost.schedule_file=.*\r?\n?","");
        Files.writeString(root.resolve("prefix.properties"),config);Files.writeString(root.resolve("holdout.properties"),config.replace("synthetic-demo-future-v1","synthetic-holdout-future-v1"));
        for(String scope:List.of("prefix","holdout"))Files.writeString(root.resolve(scope+"-inputs.csv"),"instrument_id,bars_csv,run_properties,max_abs_lots\nSYN-NIFTY,"+scope+".csv,"+scope+".properties,2\n");
        String costsHeader="schedule_id,entry_id,effective_from,effective_until,instrument_id,contract_kind,side,brokerage_per_order_inr,brokerage_per_unit_inr,brokerage_bps,exchange_inr_per_crore,ipft_inr_per_crore,sebi_inr_per_crore,stt_bps,stamp_duty_bps,gst_bps,gst_on_brokerage,gst_on_exchange,gst_on_ipft,gst_on_sebi,gst_on_stt,gst_on_stamp_duty,source_reference\n";
        for(String side:List.of("BUY","SELL"))costsHeader+="SYN-COST,"+side+",2030-01-01,2031-01-01,SYN-NIFTY,FUTURE,"+side+",0.10,0,0,0,0,0,0,0,0,false,false,false,false,false,false,synthetic cost fixture\n";
        Files.writeString(root.resolve("costs.csv"),costsHeader);Files.writeString(root.resolve("strategy.md"),"Synthetic Nifty session SMA hypothesis. Six-bar fictional sessions, unit lot, invented prices and charges. Plumbing only. Profitability unproven.\n");
        for(String id:List.of("sma-a","sma-b","empty")) {
            String settings=id.equals("sma-a")?"a.properties":id.equals("sma-b")?"b.properties":"empty.properties";
            Files.writeString(root.resolve(settings),"portfolio.pending_intent_boundary_policy=CANCEL_AT_SESSION_BOUNDARY\nportfolio.position_boundary_policy=REQUIRE_FLAT\nportfolio.require_contiguous_frames_within_session=true\nstrategy.fast_bars=2\nstrategy.slow_bars="+(id.equals("sma-a")?3:id.equals("sma-b")?4:99)+"\nstrategy.long_lots=1\nfill.slippage_ticks=1\nfill.fee_per_execution_inr=0\nportfolio.execution_cost_schedule_csv=costs.csv\n");
            save(root.resolve(id+".json"),object("schema",PLAN,"trial_id",id,"strategy_version","v1","variant_id",id,"strategy_card_file","strategy.md",
                "prefix_inputs_csv","prefix-inputs.csv","portfolio_settings_file",settings,"split_plan_csv","split.csv","holdout_inputs_csv","holdout-inputs.csv",
                "prefix_files",Map.of(),"holdout_files",Map.of(),"starting_capital_inr","100000","selection_rule",RULE,"walk_forward_training_sessions","2","walk_forward_validation_sessions","2","walk_forward_evaluation_sessions","2"));
        }
        refreshPlans(root);
    }
}
