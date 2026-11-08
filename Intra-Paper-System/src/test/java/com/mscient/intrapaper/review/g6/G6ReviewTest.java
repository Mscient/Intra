package com.mscient.intrapaper.review.g6;

import static com.mscient.intrapaper.review.g6.G6Json.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.security.*;
import java.time.LocalDate;
import java.util.*;

/** Synthetic contract/negative checks, never six-month live evidence. No test grants authority. */
public final class G6ReviewTest {
    static Path root,template; static int checks,cases;
    interface Change { void run(Fixture f) throws Exception; }
    interface Action { void run() throws Exception; }
    static void check(boolean condition,String label) { checks++; if(!condition) throw new AssertionError(label); }
    static void rejected(Action action,String label) throws Exception { try { action.run(); } catch(IOException|IllegalArgumentException e) { checks++; return; } throw new AssertionError(label); }
    static void test(String label,String status,Change change) throws Exception {
        Fixture f=new Fixture(root.resolve("case-"+(cases++)),2); change.run(f);
        Map<String,Object> report=G6Review.review(f.dir,f.history);
        check(status.equals(report.get("status")),label+": "+new String(bytes(report),java.nio.charset.StandardCharsets.UTF_8));
        check(Boolean.FALSE.equals(report.get("g6_closed")) && Boolean.FALSE.equals(report.get("scale_authorized")),label+" authority invariant");
        System.out.println("PASS "+label);
    }
    public static void main(String[] args) throws Exception {
        template=Path.of(args[0]); root=Path.of(args[1]); Files.createDirectories(root);
        if(args.length==3 && args[2].equals("generate")) { new Fixture(root.resolve("dossier"),6); return; }
        coverageTests();
        Fixture six=new Fixture(root.resolve("six"),6),five=new Fixture(root.resolve("five"),5);
        Map<String,Object> sixReport=G6Review.review(six.dir,six.history),fiveReport=G6Review.review(five.dir,five.history);
        check(sixReport.get("status").equals("INCOMPLETE"),"complete synthetic diagnostic");
        check(Boolean.TRUE.equals(sixReport.get("declared_span_at_least_six_calendar_months")),"exactly six calendar months");
        check(Boolean.FALSE.equals(fiveReport.get("declared_span_at_least_six_calendar_months")),"five calendar months");
        check(number(sixReport.get("reconciled_diagnostic_sessions")).intValueExact()==6,"all synthetic sessions reconciled");
        check(number(sixReport.get("eligible_live_sessions")).intValueExact()==0,"synthetic cannot supply eligible G5 evidence");
        check(Boolean.FALSE.equals(sixReport.get("eligible_six_calendar_months")),"calendar span alone cannot establish eligibility");
        test("missing session","INCOMPLETE",f->{ f.sessions.removeLast(); f.bind(); });
        test("missing calendar period","INCOMPLETE",f->{ listMutable(f.calendar,"days").remove(10); f.bind(); });
        test("duplicate calendar day","FAIL",f->{ listMutable(f.calendar,"days").add(1,listMutable(f.calendar,"days").getFirst()); f.bind(); });
        test("out-of-order calendar","FAIL",f->{ Collections.swap(listMutable(f.calendar,"days"),0,1); f.bind(); });
        test("excluded session","INCOMPLETE",f->{ Map<String,Object> day=obj(listMutable(f.calendar,"days").getFirst()); day.put("status","EXCLUDED"); day.put("reason","SYNTHETIC exclusion"); f.bind(); });
        test("duplicate evidence session","FAIL",f->{ f.sessions.add(f.sessions.getLast()); f.bind(); });
        test("out-of-order evidence","FAIL",f->{ Collections.swap(f.sessions,0,1); f.bind(); });
        test("synthetic falsely claimed live","FAIL",f->{ f.sessions.getFirst().put("classification","CLAIMED_LIVE_G5"); f.bind(); });
        test("replay only never eligible","INCOMPLETE",f->{ f.sessions.getFirst().put("classification","REPLAY_ONLY"); f.bind(); });
        test("missing broker","INCOMPLETE",f->{ f.sessions.getFirst().put("broker_records",null); f.bind(); });
        test("missing original broker bytes","INCOMPLETE",f->{ Files.delete(f.dir.resolve("s0/original.txt")); });
        test("incomplete broker ledger","INCOMPLETE",f->{ f.broker(0).put("complete",false); f.saveBroker(0); f.bind(); });
        test("broker cash mismatch","FAIL",f->{ f.broker(0).put("ending_cash",BigDecimal.ZERO); f.saveBroker(0); f.bind(); });
        test("broker settlement mismatch","FAIL",f->{ f.broker(0).put("settlements",List.of(Map.of("bogus",true))); f.saveBroker(0); f.bind(); });
        test("broker missing stream","INCOMPLETE",f->{ f.broker(0).put("margin_inputs",null); f.saveBroker(0); f.bind(); });
        streamTests();
        test("portfolio risk artifact breach","FAIL",f->{ riskArtifact(f,"BREACH",false); });
        test("portfolio risk artifact incomplete","INCOMPLETE",f->{ riskArtifact(f,"INCOMPLETE",false); });
        test("portfolio risk artifact tamper","FAIL",f->{ riskArtifact(f,"PASS",true); });
        test("open exception","FAIL",f->{ f.sessions.getFirst().put("exceptions",List.of(Map.of("id","X","kind","OUTAGE","state","OPEN","evidence",f.ref("s0/original.txt")))); f.bind(); });
        test("risk halt contradiction","FAIL",f->{ Map<String,Object> d=f.daily(0); obj(obj(d.get("risk")).get("final")).put("state","HALTED"); f.saveDaily(0); f.bind(); });
        test("missing acknowledgement","INCOMPLETE",f->{ Map<String,Object> d=f.daily(0); obj(d.get("operator")).put("acknowledgements",List.of()); f.saveDaily(0); f.bind(); });
        test("closing reconciliation mismatch","FAIL",f->{ obj(f.daily(0).get("closing")).put("ending_cash",BigDecimal.ZERO); f.saveDaily(0); f.bind(); });
        test("manifest byte tamper","FAIL",f->{ Files.writeString(f.dir.resolve("manifest.json")," ",StandardOpenOption.APPEND); });
        test("artifact byte tamper","FAIL",f->{ Files.writeString(f.dir.resolve("s0/artifacts/input.json")," ",StandardOpenOption.APPEND); });
        test("protocol byte tamper","FAIL",f->{ Files.writeString(f.dir.resolve("protocol.json")," ",StandardOpenOption.APPEND); });
        test("threshold change after registration","FAIL",f->{ f.protocol.put("thresholds",List.of()); f.put("protocol.json",f.protocol); });
        test("no thresholds","INCOMPLETE",f->{ f.protocol.put("thresholds",List.of()); f.bind(); });
        test("threshold violation","FAIL",f->{ obj(listMutable(f.protocol,"thresholds").getFirst()).put("value",BigDecimal.ZERO); f.approvals(false); });
        test("threshold wrong unit","FAIL",f->{ obj(listMutable(f.protocol,"thresholds").getFirst()).put("unit","LOTS"); f.bind(); });
        test("unknown metric threshold","FAIL",f->{ obj(listMutable(f.protocol,"thresholds").getFirst()).put("metric","universal_sharpe"); f.bind(); });
        for(String identity:G6Review.IDENTITIES) if(!identity.equals("calendar")) test("changed "+identity,"FAIL",f->{ obj(f.sessions.getFirst().get("identity")).put(identity,identity.equals("build") ? "b".repeat(40) : "b".repeat(64)); f.bind(); });
        test("calendar identity mismatch","FAIL",f->{ obj(f.manifest.get("identity")).put("calendar","b".repeat(64)); f.put("manifest.json",f.manifest); f.historyOnly(); });
        test("history tamper","FAIL",f->{ Map<String,Object> h=parse(read(f.history)); obj(listMutable(h,"events").getLast()).put("previous_sha256",ZERO); f.put("history.json",h); });
        test("unknown manifest field","FAIL",f->{ f.manifest.put("scale_authorized",true); f.bind(); });
        test("path traversal","FAIL",f->{ f.sessions.getFirst().put("daily_ops",Map.of("path","../outside.json","sha256",ZERO)); f.bind(); });
        test("absolute path","FAIL",f->{ f.sessions.getFirst().put("daily_ops",Map.of("path","C:/outside.json","sha256",ZERO)); f.bind(); });
        test("bad event signature","FAIL",f->{ Map<String,Object> h=parse(read(f.history)); Map<String,Object> e=obj(listMutable(h,"events").getLast()); sign(e); e.put("event_time","2026-07-02T00:00:00Z"); f.put("history.json",h); });
        test("unapproved thresholds","INCOMPLETE",f->{ check(f.protocol.get("owner_approval")==null,"approval is explicitly absent"); });
        test("signed approvals still unauthenticated","INCOMPLETE",f->{ f.approvals(false); });
        test("tampered protocol approval signature","FAIL",f->{ f.approvals(true); });
        test("cross-session cash mismatch","FAIL",f->{ Map<String,Object> d=f.daily(1); obj(d.get("closing")).put("initial_cash",new BigDecimal("1000")); f.saveDaily(1); f.bind(); });
        test("revoked review","FAIL",f->{ Map<String,Object> h=parse(read(f.history)); G6History.Checked checked=G6History.check(h,"SYNTHETIC-G6");
            f.put("revocation.json",Map.of("reason","SYNTHETIC withdrawal"));
            f.put("history.json",G6History.extend(checked,G6History.event(checked.events.size(),checked.head(),"REVOCATION","SYNTHETIC-G6",(String)f.ref("revocation.json").get("sha256"),"revocation.json",checked.digests.getFirst(),"2026-07-01T00:00:00Z"),"SYNTHETIC-G6")); });
        rejected(()->parse("{\"x\":1e999999}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"bounded decimal exponent");
        rejected(()->parse("{\"x\":1,\"x\":2}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"duplicate JSON key");
        test("missing source cannot hide later tampering","FAIL",f->{ Files.delete(f.dir.resolve("s0/original.txt")); Files.writeString(f.dir.resolve("s1/artifacts/input.json")," ",StandardOpenOption.APPEND); });
        test("identity snapshot mismatch","FAIL",f->{ Map<String,Object> refs=new TreeMap<>(); for(String key:G6Review.IDENTITIES) refs.put(key,null); refs.put("strategy",f.ref("s0/original.txt")); f.manifest.put("identity_evidence",refs); f.bind(); });
        test("registered configuration bytes changed","FAIL",f->{ f.put("s0/artifacts/config.json",Map.of("different",true)); Map<String,Object> d=f.daily(0); for(Object raw:list(obj(d.get("run")).get("artifacts"))) { Map<String,Object> a=obj(raw); if(a.get("role").equals("CONFIG")) a.put("sha256",f.ref("s0/artifacts/config.json").get("sha256")); } obj(d.get("risk")).put("config_sha256",f.ref("s0/artifacts/config.json").get("sha256")); f.saveDaily(0); f.bind(); });
        test("daily misses scheduled window","FAIL",f->{ obj(listMutable(f.calendar,"days").getFirst()).put("end_utc","2026-01-01T03:49:00Z"); f.bind(); });
        List<?> tiers=list(sixReport.get("capacity_scenarios"));
        check(obj(tiers.get(0)).get("status").equals("WITHIN_RECORDED_BOUNDS_UNTESTED"),"smaller proposal remains untested");
        check(obj(tiers.get(1)).get("status").equals("MATCHES_RECORDED_CONDITIONS_UNAUTHENTICATED"),"equal size remains unauthenticated");
        check(obj(tiers.get(2)).get("status").equals("UNTESTED"),"larger size untested");
        test("unknown capacity input","INCOMPLETE",f->{ obj(f.sessions.getFirst().get("tested_conditions")).put("participation",null); f.bind(); });
        test("false observed tier","FAIL",f->{ obj(listMutable(f.scenarios,"tiers").getLast()).put("classification","OBSERVED"); f.bind(); });
        test("false tested size","FAIL",f->{ obj(f.sessions.getFirst().get("tested_conditions")).put("maximum_order_size",new BigDecimal("5")); f.bind(); });
        test("correction preserves old sources","INCOMPLETE",f->{ f.correct(); });
        test("superseded bytes tampered","FAIL",f->{ f.correct(); Files.writeString(f.dir.resolve("session-0.json")," ",StandardOpenOption.APPEND); });
        Locale locale=Locale.getDefault(); TimeZone tz=TimeZone.getDefault();
        try { Locale.setDefault(Locale.forLanguageTag("tr-TR")); TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            check(Arrays.equals(bytes(sixReport),bytes(G6Review.review(six.dir,six.history))),"locale/timezone independent bytes");
        } finally { Locale.setDefault(locale); TimeZone.setDefault(tz); }
        Path published=root.resolve("published"); G6Artifacts.publish(six.dir,six.history,published);
        Map<String,Object> verified=G6Artifacts.verify(six.dir,published,null);
        check(Boolean.TRUE.equals(verified.get("integrity_verified")) && verified.get("status").equals("INCOMPLETE"),"packaged report reproduction");
        rejected(()->G6Artifacts.publish(six.dir,six.history,published),"no overwrite");
        rejected(()->G6Artifacts.publish(six.dir,six.history,six.dir.resolve("nested")),"no output in inputs");
        Path second=root.resolve("published-again"); G6Artifacts.publish(six.dir,six.history,second);
        check(Arrays.equals(read(published.resolve("report.json")),read(second.resolve("report.json"))),"repeat publication report bytes");
        check(Arrays.equals(read(published.resolve("history.json")),read(second.resolve("history.json"))),"deterministic analysis event");
        Map<String,Object> historical=G6Review.review(six.dir,published.resolve("history.json"));
        check(historical.get("status").equals("INCOMPLETE"),"prior analysis reproduces from exact prefix");
        Map<String,Object> alteredHistory=parse(read(published.resolve("history.json"))); Map<String,Object> alteredEvent=obj(listMutable(alteredHistory,"events").getLast());
        obj(alteredEvent.get("report")).put("diagnostic_only",false); alteredEvent.put("subject_sha256",hash(bytes(alteredEvent.get("report"))));
        Path alteredPath=root.resolve("altered-analysis.json"); Files.write(alteredPath,bytes(alteredHistory));
        check(G6Review.review(six.dir,alteredPath).get("status").equals("FAIL"),"rehashed historical report tampering rejected");
        Files.writeString(second.resolve("report.json")," ",StandardOpenOption.APPEND);
        rejected(()->G6Artifacts.verify(six.dir,second,null),"tampered report rejected");
        Path third=root.resolve("published-third"); G6Artifacts.publish(six.dir,six.history,third);
        Files.writeString(third.resolve("history.json")," ",StandardOpenOption.APPEND);
        rejected(()->G6Artifacts.verify(six.dir,third,null),"tampered history bytes rejected");
        decisions(six,published);
        Path eventPath=root.resolve("append-event.json"); Map<String,Object> storedHistory=parse(read(published.resolve("history.json"))); G6History.Checked stored=G6History.check(storedHistory,"SYNTHETIC-G6");
        Map<String,Object> appended=G6History.event(stored.events.size(),stored.head(),"EVIDENCE","SYNTHETIC-G6",(String)six.ref("session-0.json").get("sha256"),"session-0.json",null,"2026-07-02T00:00:00Z");
        Files.write(eventPath,bytes(appended)); Path appendOutput=root.resolve("appended-history");
        G6Artifacts.append(published.resolve("history.json"),eventPath,appendOutput);
        rejected(()->G6Artifacts.verify(six.dir,published,appendOutput.resolve("history.json")),"new evidence makes prior report stale");
        check(Arrays.equals(bytes(storedHistory),read(published.resolve("history.json"))),"append preserves previous history bytes");
        Path link=root.resolve("linked-dossier");
        try { Files.createSymbolicLink(link,six.dir.toAbsolutePath()); rejected(()->G6Artifacts.publish(link,six.history,root.resolve("link-output")),"symlink dossier rejected"); }
        catch(FileSystemException|UnsupportedOperationException e) { System.out.println("SKIP symlink creation unavailable: "+e.getClass().getSimpleName()); }
        pathAndPublicationTests(six);
        check(G6Cli.run(new String[]{"explain"})==0,"CLI help");
        check(G6Cli.run(new String[]{"verify",six.dir.toString(),published.toString()})==2,"CLI verified synthetic exit stays incomplete");
        System.out.println("PASS G6 checks="+checks+" cases="+cases+"; SYNTHETIC ONLY; g6_closed=false; scale_authorized=false");
    }
    static void riskArtifact(Fixture f,String status,boolean tamper) throws Exception {
        // Exercise the real artifact verifier's v2 status contract; these bytes are explicitly synthetic, not a semantic live audit.
        List<String> names=new ArrayList<>(List.of("breaches.csv","checkpoints.csv","coverage.csv","exposures.csv","inputs.csv","reconciliation.csv","risk-budgets.csv","risk-limits.csv","risk-margin-proxies.csv","risk-sources.csv","summary.properties","settlement-reconciliation.csv","settlement-charges.csv")); Collections.sort(names);
        byte[] content="SYNTHETIC ARTIFACT ADAPTER FIXTURE ONLY\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder manifest=new StringBuilder("schema_version=2\nclassification=OFFLINE_PORTFOLIO_RISK_AUDIT\nmargin_classification=CALLER_SUPPLIED_PROXIES\nstatus="+status+"\n");
        for(String name:names) manifest.append("sha256.").append(name).append('=').append(hash(content)).append('\n');
        byte[] manifestBytes=manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8); String directory="risk/"+hash(manifestBytes);
        List<Object> refs=new ArrayList<>();
        for(String name:names) { f.putBytes(directory+"/"+name,content); refs.add(f.ref(directory+"/"+name)); }
        f.putBytes(directory+"/audit-manifest.properties",manifestBytes); refs.add(f.ref(directory+"/audit-manifest.properties"));
        f.sessions.getFirst().put("artifacts",List.of(map("kind","PORTFOLIO_RISK","directory",directory,"files",refs))); f.bind();
        if(tamper) Files.writeString(f.dir.resolve(directory+"/breaches.csv"),"changed",StandardOpenOption.APPEND);
    }
    static void coverageTests() throws Exception {
        for(LocalDate start:List.of(LocalDate.of(2026,1,1),LocalDate.of(2024,8,31),LocalDate.of(2024,2,29))) {
            LocalDate end=start.plusMonths(6); List<G6Coverage.Day> days=new ArrayList<>();
            for(LocalDate d=start;d.isBefore(end);d=d.plusDays(1)) days.add(new G6Coverage.Day(d,G6Coverage.State.VERIFIED_LIVE_SESSION));
            check(G6Coverage.assess(start,end,days,true).eligibleWindow(),"six calendar months including end-of-month adjustment");
            check(!G6Coverage.assess(start,start.plusMonths(5),days.stream().filter(d->d.date().isBefore(start.plusMonths(5))).toList(),true).eligibleWindow(),"five verified months insufficient");
            check(!G6Coverage.assess(start,end,days,false).eligibleWindow(),"unauthenticated calendar insufficient");
            for(G6Coverage.State state:List.of(G6Coverage.State.MISSING,G6Coverage.State.EXCLUDED,G6Coverage.State.UNVERIFIED_SESSION)) {
                List<G6Coverage.Day> changed=new ArrayList<>(days); changed.set(5,new G6Coverage.Day(days.get(5).date(),state));
                check(!G6Coverage.assess(start,end,changed,true).eligibleWindow(),"unqualified day cannot be skipped: "+state);
            }
            List<G6Coverage.Day> missing=new ArrayList<>(days); missing.remove(5);
            check(!G6Coverage.assess(start,end,missing,true).completeSchedule(),"calendar gap detected");
            List<G6Coverage.Day> duplicate=new ArrayList<>(days); duplicate.add(1,days.getFirst());
            rejected(()->G6Coverage.assess(start,end,duplicate,true),"duplicate coverage day");
        }
        System.out.println("PASS calendar eligibility arithmetic; no real evidence authenticated by this test");
    }
    static void streamTests() throws Exception {
        test("missing broker ACK","INCOMPLETE",f->{ Map<String,Object> s=f.stream(0,"acknowledgements"); listMutable(s,"records").removeFirst(); f.stream(0,"acknowledgements",s); });
        test("duplicate broker ACK","FAIL",f->{ Map<String,Object> s=f.stream(0,"acknowledgements"); listMutable(s,"records").add(1,listMutable(s,"records").getFirst()); f.stream(0,"acknowledgements",s); });
        test("broker ACK follows fill","FAIL",f->{ Map<String,Object> s=f.stream(0,"acknowledgements"); obj(listMutable(s,"records").getFirst()).put("timestamp_utc","2026-01-01T03:45:03Z"); f.stream(0,"acknowledgements",s); });
        test("rejected order has fills","FAIL",f->{ Map<String,Object> s=f.stream(0,"rejects"); listMutable(s,"records").add(map("event_id","REJECT-1","order_id","O-BUY","timestamp_utc","2026-01-01T03:45:02Z","reason","SYNTHETIC")); f.stream(0,"rejects",s); });
        test("broker cancellation mismatch","FAIL",f->{ Map<String,Object> s=f.stream(0,"cancellations"); listMutable(s,"records").add(map("event_id","CANCEL-1","order_id","O-BUY","timestamp_utc","2026-01-01T03:45:02Z","quantity",BigDecimal.ONE,"reason","SYNTHETIC")); f.stream(0,"cancellations",s); });
        test("broker cash currency mismatch","FAIL",f->{ Map<String,Object> s=f.stream(0,"cash_flows"); listMutable(s,"records").add(map("event_id","CASH-1","timestamp_utc","2026-01-01T03:44:00Z","amount",BigDecimal.ONE,"currency","USD","kind","DEPOSIT","reference","SYNTHETIC")); f.stream(0,"cash_flows",s); });
        test("unsupported intraday cash flow","INCOMPLETE",f->{ Map<String,Object> s=f.stream(0,"cash_flows"); listMutable(s,"records").add(map("event_id","CASH-1","timestamp_utc","2026-01-01T03:46:00Z","amount",BigDecimal.ONE,"currency","INR","kind","DEPOSIT","reference","SYNTHETIC")); f.stream(0,"cash_flows",s); });
        test("cash flow breaks continuity","FAIL",f->{ Map<String,Object> s=f.stream(1,"cash_flows"); listMutable(s,"records").add(map("event_id","CASH-1","timestamp_utc","2026-02-01T03:44:00Z","amount",BigDecimal.ONE,"currency","INR","kind","DEPOSIT","reference","SYNTHETIC")); f.stream(1,"cash_flows",s); });
        test("duplicate margin sample","FAIL",f->{ Map<String,Object> s=f.stream(0,"margin_inputs"); Map<String,Object> extra=copy(obj(listMutable(s,"records").getFirst())); extra.put("event_id","M2"); listMutable(s,"records").add(extra); f.stream(0,"margin_inputs",s); });
        test("margin timestamp mismatch","FAIL",f->{ Map<String,Object> s=f.stream(0,"margin_inputs"); obj(listMutable(s,"records").getFirst()).put("timestamp_utc","2026-01-02T03:45:00Z"); f.stream(0,"margin_inputs",s); });
        test("valid correction retains originals","INCOMPLETE",f->{ correction(f,false); });
        test("correction disagrees with final ledger","FAIL",f->{ correction(f,true); });
    }
    static void correction(Fixture f,boolean mismatch) throws Exception {
        Map<String,Object> after=copy(obj(list(obj(f.daily(0).get("closing")).get("executions")).getFirst())),before=copy(after);
        before.put("price",new BigDecimal("99")); if(mismatch) after.put("price",new BigDecimal("98"));
        f.put("s0/execution-before.json",before); f.put("s0/execution-after.json",after);
        Map<String,Object> stream=f.stream(0,"corrections"); listMutable(stream,"records").add(map("event_id","FIX-1","target_kind","EXECUTION","target_id","X-BUY","timestamp_utc","2026-01-01T03:45:03Z","reason","SYNTHETIC","original",f.ref("s0/execution-before.json"),"corrected",f.ref("s0/execution-after.json")));
        f.stream(0,"corrections",stream);
    }
    static void pathAndPublicationTests(Fixture f) throws Exception {
        for(String path:List.of("//invalid.invalid/share","//?/C:/device","/??/C:/device")) rejected(()->localDisk(Path.of(path)),"UNC/device path rejected before IO");
        for(String path:List.of("../escape","a/../escape","NUL.json","a/","a//b")) rejected(()->local(f.dir,path),"unsafe source path rejected");
        Path alias=f.dir.resolve("alias.txt"); Files.createLink(alias,f.dir.resolve("s0/original.txt"));
        Sources sources=new Sources(f.dir); sources.load("s0/original.txt",null); rejected(()->sources.load("alias.txt",null),"hardlink alias rejected");
        Path parent=root.resolve("atomic"); Files.createDirectories(parent); Path target=parent.resolve("busy");
        try(var channel=java.nio.channels.FileChannel.open(parent.resolve(".g6-publication.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE); var lock=channel.lock()) {
            rejected(()->G6Artifacts.publishFiles(f.dir,target,Map.of("test.json",bytes(Map.of("synthetic",true)))),"concurrent publication refused");
            check(!Files.exists(target),"busy publisher exposes no partial report");
        }
        Path bad=parent.resolve("bad");
        rejected(()->G6Artifacts.publishFiles(f.dir,bad,Map.of("missing-parent/test.json",bytes(Map.of("synthetic",true)))),"write failure not published");
        check(!Files.exists(bad),"failed write exposes no partial report");
        try(var paths=Files.list(parent)) { check(paths.noneMatch(p->p.getFileName().toString().startsWith(".g6-stage-")),"staging cleaned after failure"); }
    }
    @SuppressWarnings("unchecked") static List<Object> listMutable(Map<String,Object> m,String key) { return (List<Object>)m.get(key); }
    static void sign(Map<String,Object> object) throws Exception {
        KeyPair kp=KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); Map<String,Object> payload=new TreeMap<>(object); payload.remove("signature");
        Signature s=Signature.getInstance("Ed25519"); s.initSign(kp.getPrivate()); s.update(bytes(payload));
        object.put("signature",Map.of("algorithm","Ed25519","public_key",Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()),"value",Base64.getEncoder().encodeToString(s.sign())));
    }
    static void decisions(Fixture f,Path output) throws Exception {
        Map<String,Object> report=parse(read(output.resolve("report.json"))); String reportHash=hash(read(output.resolve("report.json")));
        Map<String,Object> history=parse(read(output.resolve("history.json"))); G6History.Checked h=G6History.check(history,"SYNTHETIC-G6");
        for(String role:List.of("reviewer","owner")) {
            Map<String,Object> d=new TreeMap<>(); d.put("schema","g6-decision/v1"); d.put("review_id","SYNTHETIC-G6"); d.put("role",role); d.put("identity",role);
            d.put("qualification_evidence",f.ref("s0/original.txt")); d.put("independence_evidence",f.ref("s0/original.txt")); d.put("reviewed_sha256",report.get("source_sha256"));
            d.put("report_sha256",reportHash); d.put("build",report.get("build")); d.put("code_sha256",report.get("code_sha256"));
            d.put("reviewer_decision_sha256",role.equals("owner") ? h.events.getLast().get("subject_sha256") : null);
            d.put("decision",role.equals("reviewer") ? "RECOMMEND_REVIEW" : "ACCEPT_RECOMMENDATION"); d.put("limitations",List.of("SYNTHETIC")); d.put("reservations",List.of("UNAUTHENTICATED")); d.put("signature",null); sign(d);
            String name=role+"-decision.json"; f.put(name,d);
            history=G6History.extend(h,G6History.event(h.events.size(),h.head(),role.equals("reviewer") ? "REVIEWER_DECISION" : "OWNER_DECISION","SYNTHETIC-G6",hash(bytes(d)),name,null,"2026-07-02T00:00:00Z"),"SYNTHETIC-G6"); h=G6History.check(history,"SYNTHETIC-G6");
        }
        Path extension=root.resolve("decisions.json"); Files.write(extension,bytes(history));
        Map<String,Object> result=G6Artifacts.verify(f.dir,output,extension);
        for(Object raw:list(result.get("decisions"))) { Map<String,Object> d=obj(raw); check(Boolean.TRUE.equals(d.get("cryptographic_signature_valid")),"Ed25519 bytes verified"); check(Boolean.FALSE.equals(d.get("decision_verified")),"self supplied key cannot authenticate person"); }
        Map<String,Object> owner=parse(read(f.dir.resolve("owner-decision.json"))); owner.put("build","b".repeat(40)); sign(owner); f.put("owner-decision.json",owner);
        List<Object> events=listMutable(history,"events"); obj(events.getLast()).put("subject_sha256",hash(bytes(owner))); Files.write(extension,bytes(history));
        rejected(()->G6Artifacts.verify(f.dir,output,extension),"stale signed owner build rejected");
        owner.put("build",report.get("build")); sign(owner); owner.put("decision","REJECT_RECOMMENDATION"); f.put("owner-decision.json",owner);
        obj(events.getLast()).put("subject_sha256",hash(bytes(owner))); Files.write(extension,bytes(history));
        rejected(()->G6Artifacts.verify(f.dir,output,extension),"tampered review signature rejected");
    }

    static final class Fixture {
        final Path dir,history; final List<Map<String,Object>> sessions=new ArrayList<>(),daily=new ArrayList<>(),brokers=new ArrayList<>();
        Map<String,Object> manifest,calendar,protocol,scenarios;
        Fixture(Path dir,int months) throws Exception {
            this.dir=dir; this.history=dir.resolve("history.json"); Files.createDirectories(dir);
            LocalDate start=LocalDate.of(2026,1,1),end=start.plusMonths(months);
            List<Object> days=new ArrayList<>();
            for(LocalDate date=start;date.isBefore(end);date=date.plusDays(1)) { Map<String,Object> day=new TreeMap<>(); day.put("date",date.toString()); day.put("status",date.getDayOfMonth()==1 ? "SCHEDULED" : "CLOSED"); day.put("session_id",date.getDayOfMonth()==1 ? "SYNTHETIC-"+date : null); day.put("reason","Synthetic sparse calendar for contract tests; not an exchange calendar"); day.put("start_utc",date.getDayOfMonth()==1 ? date+"T03:45:00Z" : null); day.put("end_utc",date.getDayOfMonth()==1 ? date+"T03:48:00Z" : null); days.add(day); }
            calendar=map("schema","g6-calendar/v1","calendar_id","SYNTHETIC","timezone","UTC","days",days);
            protocol=map("schema","g6-protocol/v1","protocol_id","SYNTHETIC","registered_at","2025-12-01T00:00:00Z","method","DESCRIPTIVE_ONLY",
                    "thresholds",new ArrayList<>(List.of(map("metric","maximum_order_size","operator","LE","value",new BigDecimal("1"),"unit","UNITS","aggregation","EACH_SESSION","rationale","Synthetic plumbing rule only"))),"owner_approval",null,"reviewer_approval",null,"statistical_protocol",null);
            Map<String,Object> identity=new TreeMap<>(); for(String key:G6Review.IDENTITIES) identity.put(key,key.equals("build") ? "a".repeat(40) : "a".repeat(64));
            identity.put("configuration",hash(read(template.resolve("artifacts/config.json"))));
            manifest=map("schema","g6-manifest/v1","review_id","SYNTHETIC-G6","pilot_id","SYNTHETIC-NOT-A-PILOT","identity",identity,"instruments",List.of("SYNTH-FUT"),"evidence_start",start.toString(),"evidence_end_exclusive",end.toString(),"calendar",null,"protocol",null,"scenarios",null,"g5_authorization",null,"roles",Map.of("owner","owner","reviewer","reviewer"),"deployment_conditions","SYNTHETIC OFFLINE ONLY; no broker or route");
            manifest.put("account_currency","INR"); manifest.put("identity_evidence",null);
            scenarios=map("schema","g6-scenarios/v1","owner","owner","tiers",new ArrayList<>());
            for(int i=0;i<3;i++) listMutable(scenarios,"tiers").add(map("tier","T"+i,"classification","PROPOSED","identity",copy(identity),"instruments",List.of("SYNTH-FUT"),"conditions",conditions(new BigDecimal(i==0 ? "0.5" : i==1 ? "1" : "2")),"rationale","Synthetic tier; no scale authority"));
            put("normalization.txt",Map.of("protocol","Synthetic exact normalized ledger comparison only"));
            for(int i=0;i<months;i++) {
                String prefix="s"+i+"/",date=start.plusMonths(i).toString();
                for(String name:List.of("input.json","config.json","output.json","replayed-output.json")) {
                    String text=Files.readString(template.resolve("artifacts").resolve(name)).replace("2026-11-02",date).replace("2026-11-03",start.plusMonths(i).plusDays(1).toString());
                    if(name.equals("output.json") || name.equals("replayed-output.json")) {
                        Map<String,Object> out=parse(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.put("ending_cash",new BigDecimal(1008+8*i)); text=new String(bytes(out),java.nio.charset.StandardCharsets.UTF_8);
                    }
                    putBytes(prefix+"artifacts/"+name,text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                Map<String,Object> d=parse(Files.readString(template.resolve("session-evidence.json")).replace("2026-11-02",date).replace("2026-11-03",start.plusMonths(i).plusDays(1).toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                obj(d.get("closing")).put("initial_cash",new BigDecimal(1000+8*i)); obj(d.get("closing")).put("ending_cash",new BigDecimal(1008+8*i));
                for(Object raw:list(obj(d.get("run")).get("artifacts"))) { Map<String,Object> a=obj(raw); a.put("sha256",ref(prefix+str(a,"path")).get("sha256")); }
                obj(d.get("risk")).put("config_sha256",ref(prefix+"artifacts/config.json").get("sha256"));
                daily.add(d); put(prefix+"daily.json",d); putBytes(prefix+"original.txt",("SYNTHETIC NOT BROKER RECORD "+date).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Map<String,Object> b=map("schema","g6-broker/v1","session_id",d.get("session_id"),"pilot_id",manifest.get("pilot_id"),"identity",copy(identity),"classification","SYNTHETIC","originals",List.of(Map.of("source",ref(prefix+"original.txt"),"classification","SYNTHETIC","provenance","Generated contract fixture")),"normalization_protocol",ref("normalization.txt"),"risk_events",obj(d.get("risk")).get("events"),"executions",obj(d.get("closing")).get("executions"),"positions",obj(d.get("closing")).get("positions"),"initial_cash",obj(d.get("closing")).get("initial_cash"),"ending_cash",obj(d.get("closing")).get("ending_cash"),"fees",obj(d.get("closing")).get("fees"),"settlements",obj(d.get("settlement")).get("events"),"charges",obj(obj(d.get("settlement")).get("costs")).get("charges"),"acknowledgements",List.of(),"rejects",List.of(),"cancellations",List.of(),"corrections",List.of(),"cash_flows",List.of(),"margin_inputs",List.of(),"complete",true,"signature",null);
                for(String kind:G6BrokerStreams.KINDS) {
                    List<Object> records=new ArrayList<>();
                    if(kind.equals("acknowledgements")) for(Object raw:list(obj(d.get("risk")).get("events"))) {
                        Map<String,Object> e=obj(raw);
                        if("RESERVE".equals(e.get("kind"))) records.add(map("event_id","ACK-"+e.get("order_id"),"order_id",e.get("order_id"),"broker_order_id","SYNTHETIC-BROKER-"+e.get("order_id"),"timestamp_utc",e.get("timestamp_utc")));
                    }
                    if(kind.equals("margin_inputs")) records.add(map("event_id","M1","instrument_id","SYNTH-FUT","timestamp_utc",date+"T03:45:00Z","amount",BigDecimal.ONE,"currency","INR","basis","PROXY","source",ref(prefix+"original.txt")));
                    Map<String,Object> stream=map("schema","g6-stream/v1","session_id",d.get("session_id"),"pilot_id",manifest.get("pilot_id"),"identity",copy(identity),"kind",kind,"classification","SYNTHETIC","complete",true,"expected_count",records.size(),"no_records_reason",records.isEmpty() ? "SYNTHETIC explicit empty stream" : null,"records",records);
                    String name=prefix+kind+".json"; put(name,stream);
                    b.put(kind,List.of(map("source",ref(name),"classification","SYNTHETIC","record_count",records.size())));
                }
                brokers.add(copy(b)); put(prefix+"broker.json",b);
                sessions.add(map("schema","g6-session/v1","session_id",d.get("session_id"),"date",date,"pilot_id",manifest.get("pilot_id"),"identity",copy(identity),"instruments",List.of("SYNTH-FUT"),"classification","SYNTHETIC","status","OBSERVED","daily_ops",ref(prefix+"daily.json"),"broker_records",ref(prefix+"broker.json"),"artifacts",List.of(),"tested_conditions",conditions(BigDecimal.ONE),"observations",List.of(),"exceptions",List.of()));
            }
            bind();
        }
        static Map<String,Object> conditions(BigDecimal size) { Map<String,Object> c=new TreeMap<>(); for(String d:G6Review.DIMENSIONS) c.put(d,d.equals("maximum_order_size") ? size : BigDecimal.ONE); return c; }
        void putBytes(String name,byte[] data) throws IOException { Path p=dir.resolve(name); Files.createDirectories(p.getParent()); Files.write(p,data); }
        void put(String name,Object data) throws IOException { putBytes(name,bytes(data)); }
        Map<String,Object> ref(String name) throws IOException { return Map.of("path",name,"sha256",hash(Files.readAllBytes(dir.resolve(name)))); }
        Map<String,Object> stream(int i,String kind) throws IOException { return parse(Files.readAllBytes(dir.resolve("s"+i+"/"+kind+".json"))); }
        void stream(int i,String kind,Map<String,Object> stream) throws Exception {
            int count=list(stream.get("records")).size(); stream.put("expected_count",count); stream.put("no_records_reason",count==0 ? "SYNTHETIC empty stream" : null);
            String name="s"+i+"/"+kind+".json"; put(name,stream);
            brokers.get(i).put(kind,List.of(map("source",ref(name),"classification","SYNTHETIC","record_count",count))); saveBroker(i); bind();
        }
        Map<String,Object> daily(int i) { return daily.get(i); } Map<String,Object> broker(int i) { return brokers.get(i); }
        void saveDaily(int i) throws IOException { put("s"+i+"/daily.json",daily.get(i)); sessions.get(i).put("daily_ops",ref("s"+i+"/daily.json")); }
        void saveBroker(int i) throws IOException { put("s"+i+"/broker.json",brokers.get(i)); sessions.get(i).put("broker_records",ref("s"+i+"/broker.json")); }
        void bind() throws Exception {
            put("calendar.json",calendar); put("protocol.json",protocol);
            String calendarHash=(String)ref("calendar.json").get("sha256"); obj(manifest.get("identity")).put("calendar",calendarHash);
            for(int i=0;i<sessions.size();i++) { Map<String,Object> s=sessions.get(i); obj(s.get("identity")).put("calendar",calendarHash);
                if(s.get("broker_records")!=null) { String name=str(obj(s.get("broker_records")),"path"); Map<String,Object> b=parse(read(dir.resolve(name))); obj(b.get("identity")).put("calendar",calendarHash);
                    for(String kind:G6BrokerStreams.KINDS) if(b.get(kind)!=null) for(Object raw:list(b.get(kind))) {
                        Map<String,Object> descriptor=obj(raw); String streamName=str(obj(descriptor.get("source")),"path");
                        Map<String,Object> stream=parse(read(dir.resolve(streamName))); obj(stream.get("identity")).put("calendar",calendarHash); put(streamName,stream); descriptor.put("source",ref(streamName));
                    }
                    put(name,b); s.put("broker_records",ref(name)); if(i<brokers.size()) brokers.set(i,b); }
                put("session-"+i+".json",s); }
            for(Object raw:list(scenarios.get("tiers"))) obj(obj(raw).get("identity")).put("calendar",calendarHash);
            put("scenarios.json",scenarios); manifest.put("calendar",ref("calendar.json")); manifest.put("protocol",ref("protocol.json")); manifest.put("scenarios",ref("scenarios.json")); put("manifest.json",manifest); historyOnly();
        }
        void historyOnly() throws IOException {
            List<Map<String,Object>> events=new ArrayList<>(); String prev=ZERO;
            for(int i=-1;i<sessions.size();i++) { String name=i<0 ? "manifest.json" : "session-"+i+".json";
                Map<String,Object> e=G6History.event(events.size(),prev,i<0 ? "REGISTRATION" : "EVIDENCE","SYNTHETIC-G6",(String)ref(name).get("sha256"),name,null,i<0 ? "2025-12-02T00:00:00Z" : "2026-07-01T00:00:00Z"); events.add(e); prev=hash(bytes(e)); }
            put("history.json",G6History.history(events));
        }
        void correct() throws IOException {
            Map<String,Object> h=parse(read(history)); G6History.Checked checked=G6History.check(h,"SYNTHETIC-G6");
            put("correction.json",sessions.getFirst()); Map<String,Object> event=G6History.event(checked.events.size(),checked.head(),"CORRECTION","SYNTHETIC-G6",(String)ref("correction.json").get("sha256"),"correction.json",checked.digests.get(1),"2026-07-01T00:00:00Z");
            put("history.json",G6History.extend(checked,event,"SYNTHETIC-G6"));
        }
        void approvals(boolean corrupt) throws Exception {
            Map<String,Object> core=new TreeMap<>(protocol); core.remove("owner_approval"); core.remove("reviewer_approval");
            for(String role:List.of("owner","reviewer")) {
                Map<String,Object> a=map("schema","g6-approval/v1","role",role,"identity",role,"protocol_core_sha256",hash(bytes(core)),"decision","APPROVE","evidence",ref("s0/original.txt"),"signature",null);
                sign(a); if(corrupt && role.equals("reviewer")) a.put("decision","REJECT"); put(role+"-approval.json",a); protocol.put(role+"_approval",ref(role+"-approval.json"));
            }
            bind();
        }
    }
    static Map<String,Object> map(Object... values) { Map<String,Object> m=new TreeMap<>(); for(int i=0;i<values.length;i+=2) m.put((String)values[i],values[i+1]); return m; }
    static Map<String,Object> copy(Map<String,Object> m) throws IOException { return parse(bytes(m)); }
}
