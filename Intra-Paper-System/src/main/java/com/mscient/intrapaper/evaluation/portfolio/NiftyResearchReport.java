package com.mscient.intrapaper.evaluation.portfolio;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.evaluation.*;
import com.mscient.intrapaper.simulation.portfolio.*;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.mscient.intrapaper.evaluation.portfolio.PortfolioEvaluationJson.*;
import static com.mscient.intrapaper.evaluation.portfolio.NiftyResearchWorkflow.*;

/** Manifest-bound complete-family report. Missing results remain rows; unsupported inference remains null. */
public final class NiftyResearchReport {
    private NiftyResearchReport() {}
    static Path publish(NiftyResearchWorkflow w,Path outputRoot) throws IOException {
        var cards=object();var evaluations=object();
        for(var registration:ResearchTrialLedger.entries(w.ledger())) {
            String id=registration.trial().trialId();var c=w.card(id);cards.put(id,c);
            var link=Files.exists(w.evaluationPath(id))?json(w.evaluationPath(id)):object("status","NO_ATTEMPT_EVENTS","artifact",null);
            if(link.get("artifact")!=null)w.view(id,link);evaluations.put(id,link);
        }
        // Snapshot audit history so later holdout events do not invalidate earlier reports.
        var recipe=object("schema","INTRA_NIFTY_REPORT_RECIPE_V1","workspace",w.workspace.toString(),
            "ledger_sha256",hash(w.ledger()),"outcomes_sha256",Files.exists(w.outcomes())?hash(w.outcomes()):null,
            "cards",cards,"evaluations",evaluations);
        Path stage=Files.createTempDirectory(w.workspace,"report-stage-");
        Files.copy(w.ledger(),stage.resolve("trials.ledger"));
        if(Files.exists(w.outcomes()))Files.copy(w.outcomes(),stage.resolve("outcomes.ledger"));
        save(stage.resolve("recipe.json"),recipe);var report=build(stage);save(stage.resolve("report.json"),report);
        var hashes=new TreeMap<String,String>();for(String name:List.of("recipe.json","trials.ledger","outcomes.ledger","report.json"))
            if(Files.exists(stage.resolve(name)))hashes.put(name,hash(stage.resolve(name)));
        save(stage.resolve("manifest.json"),object("schema","INTRA_NIFTY_REPORT_MANIFEST_V1","classification","OFFLINE_DIAGNOSTIC_ONLY",
            "files",hashes,"report_id",hashes.get("report.json"),"performance_claim_eligible",false));
        Path destination=outputRoot.toAbsolutePath().normalize().resolve(hashes.get("report.json"));Files.createDirectories(destination.getParent());
        if(Files.exists(destination)){verify(destination);return destination;}
        Files.move(stage,destination,StandardCopyOption.ATOMIC_MOVE);verify(destination);return destination;
    }
    public static void verify(Path report) throws IOException {
        var manifest=json(report.resolve("manifest.json"));equal(manifest.keySet(),Set.of("schema","classification","files","report_id","performance_claim_eligible"),"manifest fields");
        equal(manifest.get("schema"),"INTRA_NIFTY_REPORT_MANIFEST_V1","manifest schema");equal(manifest.get("classification"),"OFFLINE_DIAGNOSTIC_ONLY","classification");equal(manifest.get("performance_claim_eligible"),false,"evidence flag");
        var files=parsedObject(manifest.get("files"),"manifest files");
        var required=new HashSet<>(Set.of("recipe.json","trials.ledger","report.json"));if(Files.exists(report.resolve("outcomes.ledger")))required.add("outcomes.ledger");
        equal(files.keySet(),required,"manifest file set");
        try(var stream=Files.list(report)){equal(stream.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()),
            java.util.stream.Stream.concat(required.stream(),java.util.stream.Stream.of("manifest.json")).collect(java.util.stream.Collectors.toSet()),"report layout");}
        for(var e:files.entrySet())equal(e.getValue(),hash(report.resolve(e.getKey())),"report artifact hash");
        equal(manifest.get("report_id"),files.get("report.json"),"report identity");
        equal(report.getFileName().toString(),files.get("report.json"),"report folder identity");
        equal(Files.readString(report.resolve("report.json"),StandardCharsets.UTF_8),write(build(report)),"recomputed report bytes");
    }
    static Map<String,Object> build(Path reportFolder) throws IOException {
        var recipe=json(reportFolder.resolve("recipe.json"));equal(recipe.get("schema"),"INTRA_NIFTY_REPORT_RECIPE_V1","recipe schema");
        equal(recipe.get("ledger_sha256"),hash(reportFolder.resolve("trials.ledger")),"snapshot ledger");
        if(recipe.get("outcomes_sha256")!=null)equal(recipe.get("outcomes_sha256"),hash(reportFolder.resolve("outcomes.ledger")),"snapshot outcomes");
        var cards=parsedObject(recipe.get("cards"),"cards");var links=parsedObject(recipe.get("evaluations"),"evaluations");
        var entries=ResearchTrialLedger.entries(reportFolder.resolve("trials.ledger"));
        var ids=entries.stream().map(e->e.trial().trialId()).collect(java.util.stream.Collectors.toSet());equal(cards.keySet(),ids,"complete registered cards");equal(links.keySet(),ids,"complete trial statuses");
        ResearchOutcomeLedger.Summary audit=recipe.get("outcomes_sha256")==null?null:ResearchOutcomeLedger.verify(reportFolder.resolve("trials.ledger"),reportFolder.resolve("outcomes.ledger"));
        var events=audit==null?List.<ResearchOutcomeLedger.Entry>of():ResearchOutcomeLedger.entries(reportFolder.resolve("trials.ledger"),reportFolder.resolve("outcomes.ledger"));
        var trials=new ArrayList<Object>();var series=new ArrayList<SessionReturnStatistics.Series>();var successful=new TreeMap<String,List<PortfolioTrialEvaluationAnalyzer.SessionReturn>>();
        Map<String,Object> first=null;String familyData=null,familySplit=null;boolean complete=true;
        for(var registration:entries) {
            String id=registration.trial().trialId();var c=parsedObject(cards.get(id),"card");equal(trial(c),registration.trial(),"registered frozen identity");
            if(first==null){first=c;familyData=registration.trial().datasetSha256();familySplit=registration.trial().splitPlanSha256();}
            equal(familyData,registration.trial().datasetSha256(),"single complete data family");equal(familySplit,registration.trial().splitPlanSha256(),"single split family");
            for(String key:List.of("starting_capital_inr","selection_rule","holdout_files","holdout_inputs_csv","walk_forward_training_sessions","walk_forward_validation_sessions","walk_forward_evaluation_sessions"))equal(first.get(key),c.get(key),"consistent family "+key);
            var link=parsedObject(links.get(id),"evaluation link");String status=string(link,"status");
            var summary=audit==null?null:audit.trials().stream().filter(s->s.trialId().equals(id)).findFirst().orElseThrow();
            var row=object("trial_id",id,"registration_sequence",registration.sequence(),"registered_at_utc",registration.registeredAtUtc().toString(),
                "registration_record_sha256",registration.recordHashSha256(),"strategy_id",registration.trial().strategyId(),"strategy_version",registration.trial().strategyVersion(),
                "variant_id",registration.trial().variantId(),"frozen_trial_sha256",registration.trial().strategyCardSha256(),"dataset_commitment_sha256",familyData,
                "split_plan_sha256",familySplit,"parameter_config_sha256",registration.trial().parameterConfigSha256(),"code_sha256",c.get("code_sha256"),
                "status",status,"results_produced",summary==null?0:summary.outcomesProduced(),"results_viewed",summary==null?0:summary.outcomesViewed(),
                "exposure_state",summary==null?"NO_ATTEMPT_EVENTS":summary.exposureState().name(),"evaluation_run",link,"metrics",null,"partition_metrics",null,"session_results",List.of(),"performance_claim_eligible",false);
            if(link.get("artifact")!=null) {
                equal(status,"OUTCOME_PRODUCED","produced result status");equal(link.get("trial_id"),id,"result trial");equal(link.get("trial_card_sha256"),digest(c),"result frozen trial");
                String eval=string(link,"evaluation_id");
                var produced=events.stream().filter(e->e.trialId().equals(id)&&e.attemptId().equals(eval)&&e.eventType()==ResearchOutcomeLedger.EventType.OUTCOME_PRODUCED).findFirst().orElseThrow(()->new IOException("Unrecorded result"));
                var p=prepare(c,false);var a=parsedObject(link.get("artifact"),"artifact");Path run=Path.of(string(recipe,"workspace")).resolve("runs").resolve(string(a,"run_id"));
                equal(produced.reference(),a.get("run_id"),"outcome-to-run link");
                var frozen=PortfolioTrialIdentity.inspect(run);PortfolioReplayArtifactVerifier.verify(run);
                equal(a.get("run_manifest_sha256"),frozen.manifestSha256(),"pinned replay manifest");equal(a.get("output_sha256"),new TreeMap<>(frozen.verification().outputSha256()),"pinned output hashes");
                equal(a.get("validated_datasets_sha256"),p.dataset().datasetsSha256(),"frozen source inputs to run");
                equal(frozen.verification().validatedDatasetsSha256(),p.dataset().datasetsSha256(),"run dataset identity");equal(a.get("code_sha256"),c.get("code_sha256"),"run code identity");
                equal(a.get("build_commit"),frozen.manifest().get("build_commit"),"run build metadata");
                String expected=PortfolioTrialIdentity.parameterConfigSha256(p.config(),p.strategy(),p.fillModel(),p.costSchedule());
                equal(expected,frozen.parameterConfigSha256(),"registered executable parameters and costs");equal(a.get("replay_parameter_sha256"),expected,"result parameter link");equal(frozen.strategyId(),registration.trial().strategyId(),"registered strategy");
                var split=EvaluationSplitPlan.loadForEvaluation(Path.of(string(c,"split_plan_csv")));BigDecimal capital=new BigDecimal(string(c,"starting_capital_inr"));
                var s=PortfolioSessionReturns.read(run,frozen,split,capital,PortfolioTrialCatalog.ArtifactScope.DEVELOPMENT_VALIDATION_ONLY);
                var sessions=s.sessions().stream().map(x->new PortfolioTrialEvaluationAnalyzer.SessionReturn(x.date(),x.partition(),x.realizedGrossPnlChangeInr(),x.unrealizedPnlChangeInr(),x.feesPaidInr(),x.netPnlChangeInr(),x.sessionReturn())).toList();
                successful.put(id,sessions);series.add(new SessionReturnStatistics.Series(id,s.returns()));
                var details=checkpointMetrics(p.dataset(),CsvTable.read(run.resolve("equity.csv")).rows(),CsvTable.read(run.resolve("executions.csv")).rows(),capital);
                row.put("metrics",details);row.put("session_results",sessionJson(sessions,details));
                var parts=object();for(var partition:List.of(EvaluationSplitPlan.Partition.DEVELOPMENT,EvaluationSplitPlan.Partition.VALIDATION)) {
                    var subset=sessions.stream().filter(x->x.partition()==partition).toList();parts.put(partition.name(),returnMetrics(subset));
                }row.put("partition_metrics",parts);
                row.put("evidence_classification",p.dataset().datasetsByInstrument().values().stream().allMatch(ds->ds.manifest().sourceName().equals("SYNTHETIC_FIXTURE"))?"SYNTHETIC_PLUMBING_ONLY":"DATED_FUTURES_EXECUTION_DIAGNOSTIC");
                row.put("source_inputs",c.get("prefix_files"));row.put("cost_schedule",object("schedule_id",p.costSchedule().orElseThrow().scheduleId(),"source_file_sha256",p.costSchedule().orElseThrow().sourceFileSha256(),"source_authenticity_verified",false,"source_references",p.costSchedule().orElseThrow().entries().stream().map(PortfolioExecutionCostSchedule.Entry::sourceReference).distinct().sorted().toList(),"fill_model",p.fillModel().id(),"fill_assumptions",new TreeMap<>(p.fillModel().parameters())));
            } else {
                complete=false;
                if(!Set.of("NO_ATTEMPT_EVENTS","ATTEMPT_STARTED","MECHANICAL_FAILURE_NO_OUTCOME").contains(status))throw new IOException("Missing artifact for result-producing trial");
                if(status.equals("MECHANICAL_FAILURE_NO_OUTCOME")&&(summary==null||summary.mechanicalFailuresWithoutOutcome()<1))throw new IOException("Failure not in outcome ledger");
                if(summary!=null&&summary.outcomesProduced()>0)throw new IOException("Produced result silently omitted");
            }
            trials.add(row);
        }
        if(first==null)throw new IOException("No registered trials");
        Object shared=null;String correctionLimit=null;
        if(!complete)correctionLimit="Complete family has failed, empty, or unattempted variants; multiple-comparison diagnostics unavailable";
        else try {
            var stats=SessionReturnStatistics.analyze(series,4);shared=object("method","existing scoped DSR sensitivity and chronological-block CSCV", "sharpe_serial_dependence_adjusted",false,
                "trial_dependence_estimated",false,"probability_backtest_overfitting",stats.pbo().probabilityBacktestOverfitting(),"cscv_combinations",stats.pbo().combinations(),
                "trials",stats.trials().stream().map(s->object("trial_id",s.trialId(),"daily_sharpe",s.dailySharpe(),"annualized_sharpe_252",s.annualizedSharpe(),"return_skewness",s.skewness(),"return_kurtosis",s.kurtosis(),
                    "deflated_sharpe_probability",s.deflatedSharpeProbability(),"dsr_at_one_effective_trial",s.dsrProbabilityAtOneEffectiveTrial(),"dsr_at_raw_family_count",s.dsrProbabilityAtRawFamilyTrialCount())).toList());
        }catch(IOException insufficient){correctionLimit=insufficient.getMessage();}
        String selected=null;
        if(complete)selected=winner(successful,EvaluationSplitPlan.Partition.VALIDATION,null);
        return object("schema","INTRA_NIFTY_RESEARCH_REPORT_V1","classification","OFFLINE_DIAGNOSTIC_ONLY","performance_claim_eligible",false,
            "code_sha256",first.get("code_sha256"),"registered_trial_count",entries.size(),"result_trial_count",successful.size(),"complete_family_results",complete,
            "trial_ledger_sha256",recipe.get("ledger_sha256"),"outcome_ledger_sha256",recipe.get("outcomes_sha256"),"dataset_commitment_sha256",familyData,"split_plan_sha256",familySplit,
            "starting_capital_inr",new BigDecimal(string(first,"starting_capital_inr")),"return_formula","session_net_pnl_change / fixed_starting_capital; simple, noncompounded; risk_free_return=0","selection_rule",RULE,"selected_trial_id",selected,
            "included_partitions",List.of("DEVELOPMENT","VALIDATION"),"holdout_opened",false,"holdout_used_for_selection",false,
            "trials",trials,"multiple_comparison_diagnostics",shared,"multiple_comparison_limitation",correctionLimit,
            "walk_forward",walkForward(first,successful,complete),"uncertainty_interval",null,"uncertainty_limitation","No dependence-aware interval or effective trial count is supported; synthetic samples establish plumbing only",
            "data_rights_verified",false,"market_rules_verified",false,"cost_sources_verified",false,"operating_evidence_eligible",false,"strategy_performance_proven",false);
    }
    static BigDecimal sum(List<PortfolioTrialEvaluationAnalyzer.SessionReturn> s) {return s.stream().map(PortfolioTrialEvaluationAnalyzer.SessionReturn::netPnlChangeInr).reduce(BigDecimal.ZERO,BigDecimal::add);}
    static Map<String,Object> returnMetrics(List<PortfolioTrialEvaluationAnalyzer.SessionReturn> sessions) {
        double[] r=sessions.stream().mapToDouble(s->s.sessionReturn().doubleValue()).toArray();var result=nullableStatistics(r);
        result.put("net_pnl_inr",sum(sessions));result.put("gross_pnl_inr",sessions.stream().map(s->s.realizedGrossPnlChangeInr().add(s.unrealizedPnlChangeInr())).reduce(BigDecimal.ZERO,BigDecimal::add));
        result.put("costs_inr",sessions.stream().map(PortfolioTrialEvaluationAnalyzer.SessionReturn::feesPaidInr).reduce(BigDecimal.ZERO,BigDecimal::add));return result;
    }
    static Map<String,Object> nullableStatistics(double[] r) {
        if(Arrays.stream(r).anyMatch(x->!Double.isFinite(x)))return object("session_count",r.length,"mean_session_return",null,"sample_session_return_stddev",null,"daily_sharpe",null,"annualized_sharpe_252",null,"limitation","Observations exceed finite statistical range");
        double mean=Arrays.stream(r).average().orElse(0),ss=0;for(double x:r)ss+=(x-mean)*(x-mean);
        Double std=r.length<2?null:Math.sqrt(ss/(r.length-1));Double sharpe=std==null||std==0?null:mean/std;
        if(std!=null&&!Double.isFinite(std))std=null;if(sharpe!=null&&!Double.isFinite(sharpe*Math.sqrt(252)))sharpe=null;
        return object("session_count",r.length,"mean_session_return",r.length==0?null:mean,"sample_session_return_stddev",std,
            "daily_sharpe",sharpe,"annualized_sharpe_252",sharpe==null?null:sharpe*Math.sqrt(252),"limitation",sharpe==null?"Insufficient sessions or undefined/zero variance":null);
    }
    static String winner(Map<String,List<PortfolioTrialEvaluationAnalyzer.SessionReturn>> trials,EvaluationSplitPlan.Partition part,List<LocalDate> dates) {
        String winner=null;BigDecimal best=null;for(var e:new TreeMap<>(trials).entrySet()) {
            var values=e.getValue().stream().filter(s->(part==null||s.partition()==part)&&(dates==null||dates.contains(s.sessionDate()))).toList();
            BigDecimal score=sum(values);if(best==null||score.compareTo(best)>0){winner=e.getKey();best=score;}
        }return winner;
    }
    static Map<String,Object> walkForward(Map<String,Object> c,Map<String,List<PortfolioTrialEvaluationAnalyzer.SessionReturn>> trials,boolean complete) throws IOException {
        int train=count(c,"walk_forward_training_sessions"),validation=count(c,"walk_forward_validation_sessions"),evaluation=count(c,"walk_forward_evaluation_sessions");
        var windows=new ArrayList<Object>();String limit=null;
        if(train==0)limit="Walk-forward disabled in frozen plan";
        else if(!complete)limit="Incomplete comparison family; no variants silently excluded";
        else {
            var sessions=trials.values().iterator().next();var dates=sessions.stream().map(PortfolioTrialEvaluationAnalyzer.SessionReturn::sessionDate).toList();
            for(int start=0;start+train+validation+evaluation<=dates.size();start+=evaluation) {
                var training=dates.subList(start,start+train);var valid=dates.subList(start+train,start+train+validation);var evaluated=dates.subList(start+train+validation,start+train+validation+evaluation);
                String selected=winner(trials,null,valid);var values=trials.get(selected).stream().filter(s->evaluated.contains(s.sessionDate())).toList();
                windows.add(object("training_sessions",training.stream().map(LocalDate::toString).toList(),"validation_sessions",valid.stream().map(LocalDate::toString).toList(),
                    "evaluation_sessions",evaluated.stream().map(LocalDate::toString).toList(),"selected_trial_id",selected,"evaluation_metrics",returnMetrics(values)));
            }if(windows.isEmpty())limit="Insufficient complete sessions for one frozen chronological window";
        }
        return object("training_behavior","Fixed registered hypotheses; no parameter fitting; per-session warmup", "selection_rule",RULE,
            "uses_holdout",false,"windows",windows,"limitation",limit,"confidence_claim",null);
    }
    static List<Object> sessionJson(List<PortfolioTrialEvaluationAnalyzer.SessionReturn> sessions,Map<String,Object> details) throws IOException {
        var byDate=parsedObject(details.get("per_session_execution"),"session metrics");var out=new ArrayList<Object>();
        for(var s:sessions)out.add(object("session_date",s.sessionDate().toString(),"partition",s.partition().name(),"gross_pnl_change_inr",s.realizedGrossPnlChangeInr().add(s.unrealizedPnlChangeInr()),
            "costs_inr",s.feesPaidInr(),"net_pnl_change_inr",s.netPnlChangeInr(),"session_return",s.sessionReturn(),"execution_and_exposure",byDate.get(s.sessionDate().toString())));return out;
    }
    static Map<String,Object> checkpointMetrics(ValidatedPortfolioDataset data,List<Map<String,String>> equity,List<Map<String,String>> executions,BigDecimal capital) {
        var metrics=new TreeMap<String,Object>();var perSession=object();var positions=new HashMap<String,Long>();
        BigDecimal turnover=BigDecimal.ZERO,peak=BigDecimal.ZERO,drawdown=BigDecimal.ZERO,maxGross=BigDecimal.ZERO,cost=BigDecimal.ZERO;
        int entryCount=0,closedCount=0,exposed=0,checkpoints=0;String previousTime="";var observations=new ArrayList<Double>();BigDecimal priorSession=BigDecimal.ZERO;
        var dates=data.frames().stream().map(SynchronizedBarFrameBuilder.Frame::sessionDate).distinct().toList();
        for(var d:dates)perSession.put(d.toString(),object("execution_count",0,"entry_count",0,"completed_position_count",0,"turnover_inr",BigDecimal.ZERO,
            "exposed_instrument_checkpoints",0,"max_gross_notional_inr",BigDecimal.ZERO,"checkpoint_drawdown_inr",BigDecimal.ZERO));
        ZoneId zone=ZoneId.of(data.datasetsByInstrument().values().iterator().next().manifest().timezone());
        for(var x:executions) {
            long qty=Long.parseLong(x.get("quantity_units")),signed=x.get("side").equals("BUY")?qty:-qty;String id=x.get("instrument_id");long old=positions.getOrDefault(id,0L),next=old+signed;
            int entry=old==0&&next!=0?1:0,closed=old!=0&&next==0?1:0;entryCount+=entry;closedCount+=closed;positions.put(id,next);
            BigDecimal value=new BigDecimal(x.get("price")).multiply(BigDecimal.valueOf(qty));turnover=turnover.add(value);
            String date=Instant.parse(x.get("timestamp_utc")).atZone(zone).toLocalDate().toString();
            @SuppressWarnings("unchecked")var day=(Map<String,Object>)perSession.get(date);
            day.put("execution_count",(Integer)day.get("execution_count")+1);day.put("entry_count",(Integer)day.get("entry_count")+entry);day.put("completed_position_count",(Integer)day.get("completed_position_count")+closed);
            day.put("turnover_inr",((BigDecimal)day.get("turnover_inr")).add(value));
        }
        int n=data.datasetsByInstrument().size();
        var frameByOpen=new HashMap<Instant,SynchronizedBarFrameBuilder.Frame>();var frameByClose=new HashMap<Instant,SynchronizedBarFrameBuilder.Frame>();
        for(var frame:data.frames()){frameByOpen.put(frame.timestamp(),frame);frameByClose.put(frame.timestamp().plus(IntervalSupport.duration(frame.barsByInstrument().values().iterator().next().interval())),frame);}
        var dayPeak=new HashMap<String,BigDecimal>();var dayStart=new HashMap<String,BigDecimal>();BigDecimal previousNet=BigDecimal.ZERO;
        for(int i=0;i<equity.size();i+=n) {
            var group=equity.subList(i,i+n);var row=group.getFirst();Instant time=Instant.parse(row.get("timestamp_utc"));boolean open=row.get("phase").equals("BAR_OPEN");var frame=(open?frameByOpen:frameByClose).get(time);
            String date=frame.sessionDate().toString();@SuppressWarnings("unchecked")var day=(Map<String,Object>)perSession.get(date);
            BigDecimal gross=BigDecimal.ZERO;
            for(var q:group){long units=Long.parseLong(q.get("signed_units"));var b=frame.barsByInstrument().get(q.get("instrument_id"));gross=gross.add((open?b.open():b.close()).multiply(BigDecimal.valueOf(units).abs()));if(units!=0){exposed++;day.put("exposed_instrument_checkpoints",(Integer)day.get("exposed_instrument_checkpoints")+1);}}
            BigDecimal net=new BigDecimal(row.get("net_pnl_inr"));peak=peak.max(net);drawdown=drawdown.max(peak.subtract(net));maxGross=maxGross.max(gross);cost=new BigDecimal(row.get("total_fees_inr"));checkpoints++;
            dayStart.putIfAbsent(date,previousNet);BigDecimal relative=net.subtract(dayStart.get(date));dayPeak.put(date,dayPeak.getOrDefault(date,BigDecimal.ZERO).max(relative));
            day.put("checkpoint_drawdown_inr",((BigDecimal)day.get("checkpoint_drawdown_inr")).max(dayPeak.get(date).subtract(relative)));
            day.put("max_gross_notional_inr",((BigDecimal)day.get("max_gross_notional_inr")).max(gross));previousNet=net;
            boolean finalCheckpoint=i+n==equity.size()||!Instant.parse(equity.get(i+n).get("timestamp_utc")).atZone(zone).toLocalDate().toString().equals(date);
            if(finalCheckpoint){observations.add(net.subtract(priorSession).divide(capital,MathContext.DECIMAL128).doubleValue());priorSession=net;}
        }
        var result=nullableStatistics(observations.stream().mapToDouble(Double::doubleValue).toArray());
        result.putAll(object("net_pnl_inr",previousNet,"gross_pnl_inr",previousNet.add(cost),"costs_inr",cost,"execution_count",executions.size(),"entry_count",entryCount,"completed_position_count",closedCount,
            "turnover_inr",turnover,"checkpoint_drawdown_inr",drawdown,"checkpoint_drawdown_fraction_of_starting_capital",drawdown.divide(capital,MathContext.DECIMAL128),
            "max_gross_notional_inr",maxGross,"exposed_instrument_checkpoints",exposed,"account_checkpoint_count",checkpoints,"exposure_sampling","bar-open and bar-close checkpoints; intrabar extremes unavailable",
            "per_session_execution",perSession,"slippage_cost_separately_estimated_inr",null,"slippage_limitation","Configured adverse ticks are included in execution prices; no separately observed spread/impact estimate"));return result;
    }
    static Map<String,Object> holdoutMetrics(Map<String,Object> c,ValidatedPortfolioDataset data,Path run) throws IOException {
        PortfolioReplayArtifactVerifier.verify(run);
        return checkpointMetrics(data,CsvTable.read(run.resolve("equity.csv")).rows(),CsvTable.read(run.resolve("executions.csv")).rows(),new BigDecimal(string(c,"starting_capital_inr")));
    }
}
