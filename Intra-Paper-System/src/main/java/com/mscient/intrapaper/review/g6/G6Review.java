package com.mscient.intrapaper.review.g6;

import static com.mscient.intrapaper.review.g6.G6Json.*;
import com.mscient.intrapaper.ops.evidence.*;
import com.mscient.intrapaper.simulation.portfolio.PortfolioReplayArtifactVerifier;
import com.mscient.intrapaper.audit.portfolio.PortfolioRiskAuditArtifacts;
import com.mscient.intrapaper.audit.portfolio.PortfolioSettlementArtifacts;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Offline review only. No live authorization, source attestation, sizing or financial model. */
public final class G6Review {
    private G6Review() { }
    public static final String VERSION = "g6-review/v1";
    static final String[] IDENTITIES = {"strategy","build","configuration","instruments","rules","calendar","costs","risk","account_route","deployment","data_sources","execution"};
    static final String[] DIMENSIONS = {"maximum_order_size","exposure","participation","order_rate","risk_budget","broker_limit","exchange_limit"};
    static final List<String> METRICS = List.of("maximum_order_size","maximum_fill_size","order_count","fill_count","filled_quantity","ordered_quantity",
            "partial_orders","missed_orders","turnover","fees","realized_pnl","unrealized_pnl","drawdown","exposure","participation","fill_ratio",
            "order_rate","slippage","margin_proxy","halts","incidents","operational_exceptions","rejects","cancellations","corrections");
    static final String[] CLASSES = {"SYNTHETIC","REPLAY_ONLY","CALLER_SUPPLIED","CLAIMED_LIVE_G5","ESTIMATED","SIMULATED"};

    static final class Context {
        final Sources sources;
        final List<Map<String,Object>> findings = new ArrayList<>(), sessions = new ArrayList<>(), scenarios = new ArrayList<>();
        final Map<String,Object> report = new TreeMap<>();
        Map<String,Object> manifest, protocol;
        G6History.Checked history;
        BigDecimal previousClosingCash;
        Instant previousCloseTime;
        boolean protocolDeclaredApproved;
        boolean fail;
        Context(Path root) throws IOException { sources = new Sources(root); }
        void finding(String status, String field, String reason) {
            if (status.equals("FAIL")) fail = true;
            findings.add(Map.of("status",status,"field",field,"reason",reason));
        }
        void missing(String field, String reason) { finding("INCOMPLETE",field,reason); }
        void failure(String field, String reason) { finding("FAIL",field,reason); }
        @FunctionalInterface interface Operation { void run() throws IOException; }
        void check(String field,Operation operation) {
            try { operation.run(); }
            catch (NoSuchFileException | AccessDeniedException e) { missing(field,"Required local source is missing or unreadable"); }
            catch (IOException | RuntimeException e) { failure(field,safeReason(e)); }
        }
        private String safeReason(Exception e) {
            String reason=e.getMessage()==null ? e.getClass().getSimpleName() : e.getMessage();
            return reason.replace(sources.root.toString(),"DOSSIER");
        }
        byte[] source(Object ref,String field) throws IOException {
            if(ref==null) { missing(field,"Required evidence is missing"); return null; }
            try { return sources.ref(ref); }
            catch(NoSuchFileException | AccessDeniedException e) { missing(field,"Required local source is missing or unreadable: "+str(obj(ref),"path")); return null; }
        }
        Map<String,Object> doc(Object ref, String field) throws IOException {
            byte[] data=source(ref,field);
            return data==null ? null : parse(data);
        }
    }

    public static Map<String,Object> review(Path dossier, Path historyFile) throws IOException {
        return review(dossier,read(historyFile),true);
    }

    static Map<String,Object> review(Path dossier,byte[] historyBytes,boolean reproducePriorReports) throws IOException {
        Context c = new Context(dossier);
        c.report.put("schema",VERSION); c.report.put("g6_closed",false); c.report.put("scale_authorized",false);
        c.report.put("performance_claim_eligible",false); c.report.put("diagnostic_only",true);
        c.report.put("build",G6Artifacts.build()); c.report.put("code_sha256",G6Artifacts.codeHash());
        c.report.put("independent_authentication",false); c.report.put("eligible_live_sessions",0);
        c.report.put("verified_tested_envelope",List.of());
        c.report.put("review_id",null);
        c.report.put("eligible_six_calendar_months",false);
        c.report.put("statistical_estimates",null); c.report.put("capacity_extrapolations",null);
        c.report.put("limits",List.of("Hashes prove byte identity only. Event timestamps and supplied keys are unauthenticated.",
                "No G5 authorization/source-attestation adapter or independently provisioned trust store exists at this revision.",
                "Only recorded observations are described; no tested capacity limit follows from replay, L1 snapshots or simulated fills.",
                "No profitability, legal-compliance or trading-readiness conclusion; separate owner and independent-review decisions required.",
                "Any recommendation applies only to the exact tested identities and jointly observed size/conditions."));
        c.missing("g5_authentication","Provide independently authenticated G5 authorization, broker/source provenance and six calendar months of eligible evidence; this revision cannot authenticate them");
        c.missing("qualified_independent_review","Provide qualified independent reviewer identity, qualification/independence evidence and a separately authenticated owner decision bound to report and build");
        try {
            c.manifest = parse(c.sources.load("manifest.json",null));
            c.report.put("review_id",c.manifest.get("review_id"));
            manifest(c.manifest);
            c.report.put("input_history_sha256",hash(historyBytes));
            Map<String,Object> historyDocument=parse(historyBytes);
            require(Arrays.equals(historyBytes,bytes(historyDocument)),"History must use canonical bytes; altered formatting is a changed input");
            c.history = G6History.check(historyDocument,str(c.manifest,"review_id"));
            c.report.put("input_history_head",c.history.head());
            c.report.put("review_id",c.manifest.get("review_id")); c.report.put("pilot_id",c.manifest.get("pilot_id"));
            c.report.put("tested_identity",c.manifest.get("identity")); c.report.put("deployment_conditions",c.manifest.get("deployment_conditions"));
            c.report.put("instruments",c.manifest.get("instruments")); c.report.put("account_currency",c.manifest.get("account_currency"));
            c.report.put("evidence_start",c.manifest.get("evidence_start")); c.report.put("evidence_end_exclusive",c.manifest.get("evidence_end_exclusive"));
            Map<String,Object> registration = c.history.events.getFirst();
            require("manifest.json".equals(registration.get("subject_path")) && c.sources.hashes.get("manifest.json").equals(registration.get("subject_sha256")), "Registration/manifest digest mismatch");
            c.check("identity_evidence",()->identityEvidence(c));
            // Every prior source, including superseded evidence and decisions, stays immutable and hash checked.
            Map<String,Map<String,Object>> priorSubjects=new HashMap<>();
            Map<String,Object> priorReport=null;
            String reviewerDigest=null;
            for (Map<String,Object> e : c.history.events) {
                if (e.get("subject_path") != null) {
                    Map<String,Object> boundReport=priorReport;
                    String boundReviewer=reviewerDigest;
                    c.check("history.source."+e.get("sequence"),()->{
                    Map<String,Object> subject=c.doc(Map.of("path",e.get("subject_path"),"sha256",e.get("subject_sha256")),"history.source");
                    priorSubjects.put(hash(bytes(e)),subject);
                    if (subject!=null && List.of("EVIDENCE","CORRECTION").contains(e.get("type"))) {
                        // Even superseded source dependencies remain mandatory: correcting a declaration never erases originals.
                        sessionContract(subject); retainReferences(c,subject);
                        if(e.get("type").equals("CORRECTION")) {
                            Map<String,Object> previous=priorSubjects.get(str(e,"supersedes"));
                            if(previous==null) c.missing("correction.original","Cannot check correction against missing superseded evidence");
                            else same(previous,subject,"session_id","date","pilot_id","identity","instruments");
                        }
                        if (subject.get("daily_ops")!=null) {
                            Map<String,Object> old=c.doc(subject.get("daily_ops"),"history.daily_ops");
                            if (old!=null) retainDailyArtifacts(c,subject,old);
                        }
                        if (subject.get("broker_records")!=null) {
                            Map<String,Object> old=c.doc(subject.get("broker_records"),"history.broker_records");
                            if (old!=null) { retainReferences(c,old); G6History.signature(old); G6BrokerStreams.retain(c,old); }
                        }
                    }
                    if (subject!=null && List.of("REVIEWER_DECISION","OWNER_DECISION").contains(e.get("type"))) {
                        require(boundReport!=null,"Decision has no preceding analysis report");
                        G6Artifacts.decision(c.sources,subject,e.get("type").equals("REVIEWER_DECISION") ? "reviewer" : "owner",c.manifest,boundReport,hash(bytes(boundReport)),boundReviewer);
                    }
                    if(subject!=null && e.get("type").equals("REVOCATION")) {
                        G6Artifacts.revocation(subject,str(c.manifest,"review_id"),boundReport==null ? null : hash(bytes(boundReport)));
                    }
                    });
                }
                if(e.get("type").equals("ANALYSIS")) {
                    Map<String,Object> historical=obj(e.get("report"));
                    int sequence=number(e.get("sequence")).intValueExact();
                    c.check("history.analysis."+sequence,()->{
                        require(c.report.get("build").equals(historical.get("build")) && c.report.get("code_sha256").equals(historical.get("code_sha256")),"Historical analysis build/code mismatch");
                        if(reproducePriorReports) require(Arrays.equals(bytes(historical),bytes(review(dossier,bytes(G6History.history(c.history.events.subList(0,sequence))),false))),"Historical report does not reproduce from its exact history prefix");
                    });
                    priorReport=historical; reviewerDigest=null;
                }
                if(e.get("type").equals("REVIEWER_DECISION")) reviewerDigest=str(e,"subject_sha256");
                if (e.get("type").equals("REVOCATION")) c.failure("history.revocation","Review history contains a revocation; a new review identity is required");
            }
            c.report.put("history_signatures",c.history.events.stream().map(e -> Map.of("sequence",e.get("sequence"),
                    "cryptographic_signature_present",e.get("signature") != null,"identity_independently_authenticated",false)).toList());
            c.check("protocol",()->{ c.protocol = c.doc(c.manifest.get("protocol"),"protocol"); if (c.protocol != null) protocol(c); });
            c.check("g5_authorization",()->{
            Map<String,Object> auth = c.doc(c.manifest.get("g5_authorization"),"g5_authorization");
            if (auth != null) {
                keys(auth,"schema","pilot_id","identity","instruments","valid_from","valid_to_exclusive","source_classification","authorization_evidence","signature"); schema(auth,"authorization");
                same(c.manifest,auth,"pilot_id","identity","instruments"); choice(auth.get("source_classification"),CLASSES);
                require(!date(auth,"valid_from").isAfter(date(c.manifest,"evidence_start")) && !date(auth,"valid_to_exclusive").isBefore(date(c.manifest,"evidence_end_exclusive")),"Stale G5 authorization scope");
                c.source(auth.get("authorization_evidence"),"g5_authorization.original"); G6History.signature(auth);
                if (!auth.get("source_classification").equals("CLAIMED_LIVE_G5")) c.missing("g5_authorization","Synthetic/replay/caller authorization cannot qualify live evidence");
            }
            });
            c.check("calendar",()->{
            Map<String,Object> calendar = c.doc(c.manifest.get("calendar"),"calendar");
            if (calendar != null) operatingEvidence(c,calendar);
            });
            c.check("scenarios",()->{
            Map<String,Object> scenarios = c.doc(c.manifest.get("scenarios"),"scenarios");
            if (scenarios != null) scenarios(c,scenarios);
            });
            c.check("thresholds",()->thresholds(c));
        } catch (NoSuchFileException e) {
            c.missing("source", "Required source missing: " + e.getFile());
        } catch (IOException | RuntimeException e) {
            c.failure("contract",c.safeReason(e));
        }
        c.check("source_stability",c.sources::unchanged);
        c.report.put("status",c.fail ? "FAIL" : "INCOMPLETE");
        c.report.put("findings",c.findings); c.report.put("sessions",c.sessions); c.report.put("capacity_scenarios",c.scenarios);
        c.report.put("source_sha256",c.sources.hashes);
        c.report.put("expected_source_sha256",c.sources.expectedHashes);
        c.report.put("all_declared_sources_present",c.sources.hashes.values().stream().noneMatch(Objects::isNull));
        c.report.put("observation_units",METRICS.stream().collect(java.util.stream.Collectors.toMap(k->k,G6Review::unit,(a,b)->a,TreeMap::new)));
        return c.report;
    }

    static void retainReferences(Context c,Object value) throws IOException {
        if(value instanceof Map<?,?>) {
            Map<String,Object> m=obj(value);
            if(m.keySet().equals(Set.of("path","sha256"))) { c.check("source."+m.get("path"),()->c.source(m,"source")); return; }
            for(Object v:m.values()) retainReferences(c,v);
        } else if(value instanceof List<?>) for(Object v:list(value)) retainReferences(c,v);
    }
    private static void retainDailyArtifacts(Context c,Map<String,Object> s,Map<String,Object> daily) throws IOException {
        String path=str(obj(s.get("daily_ops")),"path"); Path dir=Path.of(path).getParent();
        for(Object a:list(obj(daily.get("run")).get("artifacts"))) {
            Map<String,Object> artifact=obj(a); String relative=(dir==null ? "" : dir.toString().replace('\\','/')+"/")+str(artifact,"path");
            c.check("daily.artifact."+relative,()->c.source(Map.of("path",relative,"sha256",digest(artifact.get("sha256"))),"daily.artifact"));
        }
    }

    static void manifest(Map<String,Object> m) throws IOException {
        keys(m,"schema","review_id","pilot_id","identity","identity_evidence","account_currency","instruments","evidence_start","evidence_end_exclusive","calendar","protocol","scenarios","g5_authorization","roles","deployment_conditions"); schema(m,"manifest");
        require(str(m,"account_currency").matches("[A-Z]{3}"),"Exact account currency required");
        str(m,"review_id"); str(m,"pilot_id"); identity(obj(m.get("identity"))); instruments(m.get("instruments"));
        require(date(m,"evidence_end_exclusive").isAfter(date(m,"evidence_start")),"Empty/reversed evidence interval");
        Map<String,Object> roles = obj(m.get("roles")); keys(roles,"owner","reviewer");
        require(!str(roles,"owner").equals(str(roles,"reviewer")),"Owner/reviewer must be separate identities");
        str(m,"deployment_conditions");
        for (String field : List.of("calendar","protocol","scenarios")) if (m.get(field) != null) {
            Map<String,Object> ref = obj(m.get(field)); keys(ref,"path","sha256"); str(ref,"path"); digest(ref.get("sha256"));
        }
        if (m.get("calendar") != null) require(obj(m.get("identity")).get("calendar").equals(obj(m.get("calendar")).get("sha256")),"Calendar identity mismatch");
    }
    static void identity(Map<String,Object> m) throws IOException {
        keys(m,IDENTITIES);
        for (String key : IDENTITIES) if (key.equals("build")) require(str(m,key).matches("[0-9a-f]{40}"),"Exact tested build commit required"); else digest(m.get(key));
    }
    static void instruments(Object value) throws IOException {
        List<?> all = list(value); require(!all.isEmpty(),"Empty instrument scope"); String last = "";
        for (Object o : all) { String s = str(o); require(s.compareTo(last)>0,"Instrument scope must be unique/sorted"); last=s; }
    }
    private static void identityEvidence(Context c) throws IOException {
        if(c.manifest.get("identity_evidence")==null) {
            c.missing("identity_evidence","Provide exact strategy, build artifact, configuration, instrument/rule/calendar, cost, risk, route, deployment, data-source and execution snapshots");
            return;
        }
        Map<String,Object> evidence=obj(c.manifest.get("identity_evidence")); keys(evidence,IDENTITIES);
        Map<String,Object> identity=obj(c.manifest.get("identity"));
        for(String key:IDENTITIES) c.check("identity_evidence."+key,()->{
            byte[] data=c.source(evidence.get(key),"identity_evidence."+key);
            if(data==null) return;
            if(key.equals("build")) {
                try(var jar=new java.util.jar.JarInputStream(new java.io.ByteArrayInputStream(data))) {
                    require(jar.getManifest()!=null && identity.get("build").equals(jar.getManifest().getMainAttributes().getValue("Build-Commit")),"Tested JAR Build-Commit differs from registered build");
                }
            } else require(hash(data).equals(identity.get(key)),"Identity snapshot does not match registered "+key+" hash");
        });
        c.missing("identity_evidence.authentication","Snapshot byte identity does not authenticate deployment, source or execution conditions");
    }
    static LocalDate date(Map<String,Object> m,String k) throws IOException {
        try { String s=str(m,k); require(s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"),"ISO date required"); return LocalDate.parse(s); }
        catch (DateTimeException e) { throw new IOException("Invalid date: " + k,e); }
    }
    static void same(Map<String,Object> a,Map<String,Object> b,String... keys) throws IOException {
        for (String k : keys) require(Arrays.equals(bytes(a.get(k)),bytes(b.get(k))),"Identity/content mismatch: " + k);
    }
    private static void protocol(Context c) throws IOException {
        Map<String,Object> p = c.protocol;
        keys(p,"schema","protocol_id","registered_at","method","thresholds","owner_approval","reviewer_approval","statistical_protocol"); schema(p,"protocol");
        str(p,"protocol_id"); require("DESCRIPTIVE_ONLY".equals(p.get("method")),"Unsupported protocol method; existing statistical/HAC calculations are not changed or implicitly approved");
        Instant registered = Instant.parse(str(p,"registered_at"));
        require(!registered.isAfter(Instant.parse(str(c.history.events.getFirst(),"event_time"))),"Protocol registered after manifest registration");
        if (p.get("statistical_protocol") != null) { c.source(p.get("statistical_protocol"),"statistical_protocol"); c.missing("statistical_protocol","Bound external protocol retained only; no statistical estimates computed by G6 v1"); }
        Set<String> names = new HashSet<>();
        for (Object raw : list(p.get("thresholds"))) {
            Map<String,Object> t = obj(raw); keys(t,"metric","operator","value","unit","aggregation","rationale");
            String metric=str(t,"metric"); require(METRICS.contains(metric) && names.add(metric),"Unsupported/duplicate threshold metric");
            choice(t.get("operator"),"LE","GE","EQ"); number(t.get("value")); require(unit(metric).equals(str(t,"unit")),"Threshold unit mismatch for "+metric); str(t,"rationale");
            choice(t.get("aggregation"),"EACH_SESSION");
        }
        if (list(p.get("thresholds")).isEmpty()) c.missing("protocol.thresholds","Owner must preregister explicit metrics, units, aggregation, thresholds and decision rules with independent approval");
        int declaredApprovals=0;
        for (String role : List.of("owner","reviewer")) {
            Map<String,Object> a = c.doc(p.get(role+"_approval"),"protocol."+role+"_approval");
            if (a == null) continue;
            keys(a,"schema","role","identity","protocol_core_sha256","decision","evidence","signature"); schema(a,"approval");
            require(role.equals(a.get("role")) && obj(c.manifest.get("roles")).get(role).equals(a.get("identity")),"Protocol approver identity mismatch");
            Map<String,Object> core = new TreeMap<>(p); core.remove("owner_approval"); core.remove("reviewer_approval");
            require(hash(bytes(core)).equals(digest(a.get("protocol_core_sha256"))),"Protocol approval digest mismatch");
            boolean evidencePresent=c.source(a.get("evidence"),"protocol."+role+".evidence")!=null;
            boolean signatureValid=G6History.signature(a);
            if (!"APPROVE".equals(choice(a.get("decision"),"APPROVE","REJECT"))) c.failure("protocol.approval","Protocol approval rejected");
            else if(evidencePresent && signatureValid) declaredApprovals++;
            else c.missing("protocol."+role+"_approval","A signed exact-core approval and its evidence are required even for diagnostic threshold checks");
            c.missing("protocol."+role+"_authentication","Approval/key identity is caller supplied, not independently authenticated");
        }
        c.protocolDeclaredApproved=declaredApprovals==2;
    }

    private static void operatingEvidence(Context c,Map<String,Object> calendar) throws IOException {
        keys(calendar,"schema","calendar_id","timezone","days"); schema(calendar,"calendar"); str(calendar,"calendar_id");
        ZoneId zone = ZoneId.of(str(calendar,"timezone"));
        LocalDate start=date(c.manifest,"evidence_start"),end=date(c.manifest,"evidence_end_exclusive");
        boolean six=!end.isBefore(start.plusMonths(6)); c.report.put("declared_span_at_least_six_calendar_months",six);
        if (!six) c.missing("evidence_interval","At least six calendar months required using [start,end_exclusive), not a fixed day count");
        Map<String,Map<String,Object>> active=new TreeMap<>(); LocalDate last=null;
        for (Map<String,Object> e : c.history.active.values()) {
            Map<String,Object> s=c.doc(Map.of("path",e.get("subject_path"),"sha256",e.get("subject_sha256")),"session");
            if (s==null) continue;
            sessionContract(s); LocalDate day=date(s,"date");
            require(last==null || day.isAfter(last),"Duplicate/out-of-order evidence sessions"); last=day;
            require(!day.isBefore(start) && day.isBefore(end),"Evidence outside registered interval");
            require(active.put(str(s,"session_id"),s)==null,"Duplicate session identity");
        }
        LocalDate expected=start; Set<String> scheduleIds=new HashSet<>(); int scheduled=0,observed=0;
        List<G6Coverage.Day> coverage=new ArrayList<>();
        List<Map<String,Object>> schedule=new ArrayList<>();
        for (Object raw : list(calendar.get("days"))) {
            Map<String,Object> day=obj(raw); keys(day,"date","status","session_id","reason","start_utc","end_utc");
            LocalDate d=date(day,"date"); require(!d.isBefore(expected),"Duplicate/out-of-order calendar day");
            if (!d.equals(expected)) c.missing("calendar.coverage","Missing calendar period beginning " + expected);
            require(d.isBefore(end),"Calendar day outside interval"); expected=d.plusDays(1);
            String status=choice(day.get("status"),"SCHEDULED","CLOSED","EXCLUDED");
            Map<String,Object> scheduleRow=new TreeMap<>(day); schedule.add(scheduleRow);
            scheduleRow.put("eligible_live_evidence",false);
            if (status.equals("CLOSED")) { require(day.get("session_id")==null && day.get("start_utc")==null && day.get("end_utc")==null,"Closed day has a session/window"); str(day,"reason"); coverage.add(new G6Coverage.Day(d,G6Coverage.State.CLOSED)); continue; }
            Instant windowStart=Instant.parse(str(day,"start_utc")),windowEnd=Instant.parse(str(day,"end_utc"));
            require(windowEnd.isAfter(windowStart) && windowStart.atZone(zone).toLocalDate().equals(d) && windowEnd.atZone(zone).toLocalDate().equals(d),"Scheduled session window/date mismatch");
            String id=str(day,"session_id"); require(scheduleIds.add(id),"Duplicate scheduled session"); scheduled++;
            if (status.equals("EXCLUDED")) { str(day,"reason"); c.missing("calendar.excluded."+id,"Excluded scheduled session does not qualify; no silent removal"); }
            Map<String,Object> s=active.remove(id);
            if (s==null) { c.missing("session."+id,"Scheduled session evidence missing"); coverage.add(new G6Coverage.Day(d,G6Coverage.State.MISSING)); c.previousClosingCash=null; c.previousCloseTime=null; scheduleRow.put("observed_status","MISSING"); continue; }
            require(d.equals(date(s,"date")),"Session/calendar date mismatch");
            int before=c.sessions.size();
            c.check("session."+id,()->session(c,s,zone,windowStart,windowEnd));
            if(c.sessions.size()>before) c.sessions.getLast().put("scheduled_status",status);
            if(c.sessions.size()>before && Boolean.TRUE.equals(c.sessions.getLast().get("normalized_reconciliation_complete"))) observed++;
            else { c.previousClosingCash=null; c.previousCloseTime=null; }
            scheduleRow.put("observed_status",s.get("status"));
            coverage.add(new G6Coverage.Day(d,status.equals("EXCLUDED") ? G6Coverage.State.EXCLUDED : G6Coverage.State.UNVERIFIED_SESSION));
        }
        if (!expected.equals(end)) c.missing("calendar.coverage","Calendar does not cover through " + end);
        require(active.isEmpty(),"Unscheduled/excluded-day evidence supplied");
        if (scheduled==0) c.missing("calendar.sessions","No scheduled operating sessions");
        c.report.put("scheduled_sessions",scheduled); c.report.put("reconciled_diagnostic_sessions",observed);
        G6Coverage.Result window=G6Coverage.assess(start,end,coverage,false); // No independently authenticated calendar/source provision exists in v1.
        c.report.put("schedule_coverage",schedule); c.report.put("calendar_days_complete",window.completeSchedule());
        c.report.put("eligible_live_sessions",window.eligibleSessions()); c.report.put("eligible_six_calendar_months",window.eligibleWindow());
    }
    static void sessionContract(Map<String,Object> s) throws IOException {
        keys(s,"schema","session_id","date","pilot_id","identity","instruments","classification","status","daily_ops","broker_records","artifacts","tested_conditions","observations","exceptions"); schema(s,"session");
        str(s,"session_id"); str(s,"pilot_id"); date(s,"date"); identity(obj(s.get("identity"))); instruments(s.get("instruments"));
        choice(s.get("classification"),CLASSES); choice(s.get("status"),"OBSERVED","MISSING","EXCLUDED");
        conditions(obj(s.get("tested_conditions")));
    }
    static void conditions(Map<String,Object> m) throws IOException {
        keys(m,DIMENSIONS);
        for (String k : DIMENSIONS) if (m.get(k)!=null) require(number(m.get(k)).signum()>=0,"Negative condition: "+k);
        if (m.get("participation")!=null) require(number(m.get("participation")).compareTo(BigDecimal.ONE)<=0,"Participation exceeds one");
    }
    private static boolean session(Context c,Map<String,Object> s,ZoneId zone,Instant windowStart,Instant windowEnd) throws IOException {
        same(c.manifest,s,"pilot_id","identity","instruments"); String id=str(s,"session_id"),classification=str(s,"classification");
        Map<String,Object> row=new TreeMap<>(); row.put("session_id",id); row.put("date",s.get("date")); row.put("source_classification",classification);
        row.put("independently_authenticated",false); row.put("eligible_live_evidence",false); row.put("declared_tested_conditions",s.get("tested_conditions"));
        row.put("condition_basis","CALLER_SUPPLIED_UNAUTHENTICATED; only maximum_order_size is cross-checked against the normalized ledger");
        row.put("observation_basis","NORMALIZED_SOURCE_DIAGNOSTIC; source truth and live performance are not authenticated");
        row.put("observed_status",s.get("status"));
        Map<String,Object> metrics=new TreeMap<>(); for (String metric : METRICS) metrics.put(metric,null); row.put("observations",metrics);
        c.sessions.add(row);
        if (!"OBSERVED".equals(s.get("status"))) c.missing("session."+id,"Session is missing/excluded");
        if (!classification.equals("CLAIMED_LIVE_G5")) c.missing("session."+id+".classification","Synthetic/replay/estimated/caller evidence cannot count toward live months");
        for (Object raw : list(s.get("exceptions"))) {
            Map<String,Object> x=obj(raw); keys(x,"id","kind","state","evidence"); str(x,"id"); str(x,"kind");
            choice(x.get("state"),"OPEN","RESOLVED"); c.source(x.get("evidence"),"exception.evidence");
            if (x.get("state").equals("OPEN")) c.failure("session."+id+".exceptions","Unresolved operational exception");
        }
        Map<String,Object> daily=c.doc(s.get("daily_ops"),"session."+id+".daily_ops");
        boolean reconciled=false;
        if (daily!=null) {
            require(id.equals(daily.get("session_id")),"Daily session ID mismatch");
            require(date(s,"date").equals(Instant.parse(str(daily,"session_start_utc")).atZone(zone).toLocalDate()),"Daily session date mismatch");
            require(date(s,"date").equals(Instant.parse(str(daily,"session_end_utc")).atZone(zone).toLocalDate()),"Daily end date mismatch");
            require(windowStart.equals(Instant.parse(str(daily,"session_start_utc"))) && windowEnd.equals(Instant.parse(str(daily,"session_end_utc"))),"Observed session does not cover the exact scheduled window");
            List<String> scope=new ArrayList<>(); for (Object i : list(daily.get("instruments"))) scope.add(str(obj(i),"instrument_id")); Collections.sort(scope);
            require(scope.equals(s.get("instruments")),"Daily instrument scope mismatch");
            if (classification.equals("CLAIMED_LIVE_G5") && "SYNTHETIC".equals(daily.get("classification"))) c.failure("session."+id,"Synthetic evidence falsely presented as live");
            String path=str(obj(s.get("daily_ops")),"path"); retainDailyArtifacts(c,s,daily);
            require(obj(c.manifest.get("identity")).get("configuration").equals(obj(daily.get("risk")).get("config_sha256")),"Daily configuration differs from registered tested configuration");
            DailyOpsEvidenceReport checked;
            try(G6Snapshot snapshot=new G6Snapshot()) {
                snapshot.put("daily.json",c.sources.snapshots.get(path));
                Path parent=Path.of(path).getParent();
                String prefix=parent==null ? "" : parent.toString().replace('\\','/')+"/";
                Set<String> copied=new HashSet<>();
                for(Object raw:list(obj(daily.get("run")).get("artifacts"))) {
                    Map<String,Object> a=obj(raw); String name=str(a,"path");
                    require(copied.add(name),"Duplicate daily artifact path");
                    byte[] content=c.sources.snapshots.get(prefix+name);
                    if(content!=null) snapshot.put(name,content);
                }
                checked=DailyOpsEvidenceChecker.check(snapshot.root.resolve("daily.json"));
            }
            row.put("daily_ops_status",checked.status().name()); row.put("daily_ops_report_sha256",hash(checked.bytes()));
            for (var result:checked.results()) for (var f:result.findings()) c.finding(f.status().name(),id+"."+f.field(),f.detail());
            reconciled=checked.status()==DailyOpsEvidenceReport.Status.PASS;
            if (reconciled) observations(daily,metrics);
            row.put("observed_maximum_order_size_by_instrument",reconciled ? maximumSizes(daily,"risk","events","RESERVE") : null);
            row.put("observed_maximum_fill_size_by_instrument",reconciled ? maximumSizes(daily,"closing","executions",null) : null);
            Map<String,Object> broker=c.doc(s.get("broker_records"),"session."+id+".broker_records");
            if (broker!=null) reconciled &= broker(c,s,daily,broker,row);
            else reconciled=false;
            if (reconciled) {
                Map<String,Object> close=obj(daily.get("closing"));
                if(c.previousClosingCash!=null && row.get("opening_external_cash_flow")!=null)
                    require(c.previousClosingCash.add(number(row.get("opening_external_cash_flow"))).compareTo(number(close.get("initial_cash")))==0,"Cross-session cash mismatch after normalized external cash flows");
                c.previousClosingCash=number(close.get("ending_cash"));
                c.previousCloseTime=Instant.parse(str(daily,"session_end_utc"));
            } else { c.previousClosingCash=null; c.previousCloseTime=null; }
            if ("HALTED".equals(daily.get("outcome"))) c.missing("session."+id+".halt","Risk halt observed; owner/reviewer disposition and applicable preregistered rule required");
        } else c.doc(s.get("broker_records"),"session."+id+".broker_records");
        Object declared=obj(s.get("tested_conditions")).get("maximum_order_size");
        if (declared!=null && metrics.get("maximum_order_size")!=null) require(number(declared).compareTo(number(metrics.get("maximum_order_size")))==0,"Declared tested order size differs from observed ledger");
        for (Object raw:list(s.get("observations"))) {
            Map<String,Object> o=obj(raw); keys(o,"metric","value","unit","basis","source"); require(METRICS.contains(str(o,"metric")),"Unknown supplemental metric");
            if (o.get("value")!=null) number(o.get("value")); require(unit(str(o,"metric")).equals(str(o,"unit")),"Supplemental observation unit mismatch"); choice(o.get("basis"),"CALLER_SUPPLIED","ESTIMATED","SIMULATED","SYNTHETIC"); c.source(o.get("source"),"supplemental_observation.source");
        }
        row.put("supplemental_unverified_observations",s.get("observations"));
        for (Object raw:list(s.get("artifacts"))) c.check("session."+id+".artifact",()->artifact(c,obj(raw)));
        row.put("normalized_reconciliation_complete",reconciled);
        return reconciled;
    }

    private static Map<String,Object> maximumSizes(Map<String,Object> daily,String section,String rows,String kind) throws IOException {
        Map<String,Object> maxima=new TreeMap<>();
        for(Object raw:list(daily.get("instruments"))) maxima.put(str(obj(raw),"instrument_id"),BigDecimal.ZERO);
        for(Object raw:list(obj(daily.get(section)).get(rows))) {
            Map<String,Object> e=obj(raw);
            if(kind==null || kind.equals(e.get("kind"))) {
                String id=str(e,"instrument_id");
                maxima.put(id,number(maxima.get(id)).max(number(e.get("quantity"))));
            }
        }
        return maxima;
    }

    private static boolean broker(Context c,Map<String,Object> session,Map<String,Object> daily,Map<String,Object> b,Map<String,Object> row) throws IOException {
        keys(b,"schema","session_id","pilot_id","identity","classification","originals","normalization_protocol","risk_events","executions","positions","initial_cash","ending_cash","fees","settlements","charges","acknowledgements","rejects","cancellations","corrections","cash_flows","margin_inputs","complete","signature"); schema(b,"broker");
        same(session,b,"session_id","pilot_id","identity"); choice(b.get("classification"),CLASSES); G6History.signature(b);
        if (session.get("classification").equals("CLAIMED_LIVE_G5") && List.of("SYNTHETIC","SIMULATED","REPLAY_ONLY").contains(b.get("classification"))) c.failure("broker.classification","Non-live broker records falsely presented as live");
        boolean complete=Boolean.TRUE.equals(b.get("complete"));
        if (!complete) c.missing("broker.complete","Broker normalized ledger completeness is unknown");
        require(b.get("complete") instanceof Boolean,"Expected completeness boolean");
        if (list(b.get("originals")).isEmpty()) { c.missing("broker.originals","Exact original broker statements/exports and provenance required"); complete=false; }
        for (Object raw:list(b.get("originals"))) {
            Map<String,Object> o=obj(raw); keys(o,"source","classification","provenance"); choice(o.get("classification"),CLASSES); str(o,"provenance"); complete &= c.source(o.get("source"),"broker.original")!=null;
            if (session.get("classification").equals("CLAIMED_LIVE_G5") && List.of("SYNTHETIC","REPLAY_ONLY","SIMULATED").contains(o.get("classification"))) c.failure("broker.originals","Synthetic/replay original claimed as live");
        }
        complete &= c.source(b.get("normalization_protocol"),"broker.normalization_protocol")!=null;
        brokerCoreContract(b);
        Map<String,Object> close=obj(daily.get("closing")),settlement=obj(daily.get("settlement"));
        same(close,b,"executions","positions","initial_cash","ending_cash","fees");
        require(Arrays.equals(bytes(b.get("risk_events")),bytes(obj(daily.get("risk")).get("events"))),"Broker order/fill/risk reconciliation mismatch");
        require(Arrays.equals(bytes(b.get("settlements")),bytes(settlement.get("events"))),"Broker settlement mismatch");
        require(Arrays.equals(bytes(b.get("charges")),bytes(obj(settlement.get("costs")).get("charges"))),"Broker cost reconciliation mismatch");
        G6BrokerStreams.Result streams=G6BrokerStreams.check(c,session,daily,b);
        complete &= streams.complete();
        row.put("broker_stream_diagnostics",streams.diagnostics()); row.put("opening_external_cash_flow",streams.openingCashFlow());
        for(String metric:List.of("rejects","corrections","margin_proxy")) obj(row.get("observations")).put(metric,streams.diagnostics().get(metric));
        c.missing("broker.authentication","Normalized ledger joins verified only for supplied bytes; raw export normalization, provenance and source completeness remain unauthenticated");
        return complete;
    }

    private static void brokerCoreContract(Map<String,Object> b) throws IOException {
        for(Object raw:list(b.get("risk_events"))) {
            Map<String,Object> e=obj(raw);
            Set<String> allowed=Set.of("event_id","kind","timestamp_utc","order_id","instrument_id","side","quantity","coverage_window_id","coverage_event_id","execution_id","stop_id","reason","settlement_event_id","signed_quantity");
            require(allowed.containsAll(e.keySet()) && e.keySet().containsAll(Set.of("event_id","kind","timestamp_utc")),"Unknown/missing normalized risk event fields");
            for(var field:e.entrySet()) if(List.of("quantity","signed_quantity").contains(field.getKey())) number(field.getValue()); else str(field.getValue());
            choice(e.get("kind"),"RESERVE","FILL","CANCEL","SETTLE","HALT");
            if(e.containsKey("side")) choice(e.get("side"),"BUY","SELL");
        }
        for(Object raw:list(b.get("executions"))) keys(obj(raw),"execution_id","order_id","instrument_id","side","quantity","price","fee","timestamp_utc");
        for(Object raw:list(b.get("settlements"))) keys(obj(raw),"event_id","instrument_id","signed_quantity","cash_amount","fee","timestamp_utc","source_ref","price");
        for(Object raw:list(b.get("charges"))) keys(obj(raw),"charge_id","target_kind","target_id","component","amount","source_ref","rounding","effective_from_utc","effective_to_utc");
        for(Object quantity:obj(b.get("positions")).values()) number(quantity);
        for(String field:List.of("initial_cash","ending_cash","fees")) number(b.get(field));
    }

    private static void observations(Map<String,Object> d,Map<String,Object> m) throws IOException {
        Map<String,Object> risk=obj(d.get("risk")),close=obj(d.get("closing")),operator=obj(d.get("operator"));
        Map<String,BigDecimal> orders=new TreeMap<>(),fills=new TreeMap<>(); Map<Long,Integer> rates=new TreeMap<>();
        BigDecimal maxOrder=BigDecimal.ZERO,maxFill=BigDecimal.ZERO,ordered=BigDecimal.ZERO,filled=BigDecimal.ZERO,turnover=BigDecimal.ZERO;
        int halts=0,cancels=0;
        for(Object raw:list(risk.get("events"))) {
            Map<String,Object> e=obj(raw); String kind=str(e,"kind");
            if(kind.equals("RESERVE")) { BigDecimal q=number(e.get("quantity")); orders.put(str(e,"order_id"),q); maxOrder=maxOrder.max(q); ordered=ordered.add(q);
                rates.merge(Instant.parse(str(e,"timestamp_utc")).getEpochSecond(),1,Integer::sum); }
            if(kind.equals("HALT")) halts++; if(kind.equals("CANCEL")) cancels++;
        }
        Map<String,BigDecimal> multipliers=new TreeMap<>(); for(Object raw:list(d.get("instruments"))) { Map<String,Object> i=obj(raw); multipliers.put(str(i,"instrument_id"),number(i.get("multiplier"))); }
        for(Object raw:list(close.get("executions"))) { Map<String,Object> e=obj(raw); BigDecimal q=number(e.get("quantity"));
            fills.merge(str(e,"order_id"),q,BigDecimal::add); maxFill=maxFill.max(q); filled=filled.add(q);
            turnover=turnover.add(q.multiply(number(e.get("price"))).multiply(multipliers.get(str(e,"instrument_id")))); }
        int partial=0,missed=0; for(var e:orders.entrySet()) { BigDecimal q=fills.getOrDefault(e.getKey(),BigDecimal.ZERO); if(q.signum()==0) missed++; else if(q.compareTo(e.getValue())<0) partial++; }
        m.put("maximum_order_size",maxOrder); m.put("maximum_fill_size",maxFill); m.put("ordered_quantity",ordered); m.put("filled_quantity",filled);
        m.put("order_count",orders.size()); m.put("fill_count",list(close.get("executions")).size()); m.put("partial_orders",partial); m.put("missed_orders",missed);
        m.put("turnover",turnover); m.put("fees",close.get("fees")); m.put("realized_pnl",close.get("net_pnl"));
        // Existing daily checker only passes flat sessions; no new P&L/mark/drawdown model is introduced.
        m.put("unrealized_pnl",BigDecimal.ZERO); m.put("halts",halts); m.put("cancellations",cancels); m.put("incidents",list(operator.get("incidents")).size());
        m.put("order_rate",rates.values().stream().max(Integer::compareTo).orElse(0));
        if(ordered.signum()>0) m.put("fill_ratio",filled.divide(ordered,12,java.math.RoundingMode.HALF_EVEN));
    }
    private static void artifact(Context c,Map<String,Object> a) throws IOException {
        keys(a,"kind","directory","files"); String kind=choice(a.get("kind"),"PORTFOLIO_REPLAY","PORTFOLIO_RISK","PORTFOLIO_SETTLEMENT");
        Path folder=local(c.sources.root,str(a,"directory")); Set<String> declared=new HashSet<>();
        boolean complete=true;
        for(Object raw:list(a.get("files"))) { Map<String,Object> r=obj(raw); complete &= c.source(r,"artifact.file")!=null; Path p=local(c.sources.root,str(r,"path")); require(p.getParent().equals(folder),"Artifact file outside declared folder"); require(declared.add(p.getFileName().toString()),"Duplicate artifact file"); }
        if(!complete) return;
        try(var files=Files.list(folder)) { require(files.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(declared),"Artifact directory inventory mismatch"); }
        try(G6Snapshot snapshot=new G6Snapshot()) {
            String directory=folder.getFileName().toString();
            for(Object raw:list(a.get("files"))) { Map<String,Object> r=obj(raw); String path=str(r,"path"); snapshot.put(directory+"/"+Path.of(path).getFileName(),c.sources.snapshots.get(path)); }
            Path frozen=snapshot.root.resolve(directory);
            switch(kind) {
                case "PORTFOLIO_REPLAY" -> {
                    PortfolioReplayArtifactVerifier.verify(frozen);
                    Properties properties=new Properties();
                    try(var in=Files.newInputStream(frozen.resolve("run-manifest.properties"))) { properties.load(in); }
                    Map<String,Object> identity=obj(c.manifest.get("identity"));
                    require(identity.get("build").equals(properties.getProperty("build_commit")),"Portfolio artifact build differs from registered tested build");
                    require(identity.get("strategy").equals(properties.getProperty("strategy_fingerprint_sha256")),"Portfolio artifact strategy differs from registered tested strategy");
                    String costs=properties.getProperty("execution_cost_schedule_source_file_sha256");
                    if("NONE".equals(costs)) c.missing("artifact.costs","Execution costs were not modeled in replay artifact");
                    else require(identity.get("costs").equals(costs),"Portfolio artifact costs differ from registered cost identity");
                }
                case "PORTFOLIO_RISK" -> {
                    String status=PortfolioRiskAuditArtifacts.verify(frozen).status();
                    if(status.equals("BREACH")) c.failure("portfolio_risk","Risk artifact reports BREACH");
                    else if(!status.equals("PASS")) c.missing("portfolio_risk","Risk artifact is incomplete");
                }
                case "PORTFOLIO_SETTLEMENT" -> PortfolioSettlementArtifacts.verify(frozen);
                default -> throw new IOException("Unsupported artifact");
            }
        }
        c.missing("artifacts."+kind,"Existing artifact verifier ran; replay/audit artifacts do not authenticate G5 source truth or establish capacity");
    }

    private static void scenarios(Context c,Map<String,Object> document) throws IOException {
        keys(document,"schema","owner","tiers"); schema(document,"scenarios"); require(obj(c.manifest.get("roles")).get("owner").equals(document.get("owner")),"Scenario owner mismatch");
        Set<String> ids=new HashSet<>();
        for(Object raw:list(document.get("tiers"))) {
            Map<String,Object> s=obj(raw); keys(s,"tier","classification","identity","instruments","conditions","rationale");
            require(ids.add(str(s,"tier")),"Duplicate tier"); choice(s.get("classification"),"OBSERVED","PROPOSED"); identity(obj(s.get("identity"))); instruments(s.get("instruments"));
            conditions(obj(s.get("conditions"))); str(s,"rationale");
            boolean within=false,exact=false,unknown=false;
            if(Arrays.equals(bytes(s.get("identity")),bytes(c.manifest.get("identity"))) && s.get("instruments").equals(c.manifest.get("instruments"))) {
                for(Map<String,Object> session:c.sessions) {
                    if(!Boolean.TRUE.equals(session.get("normalized_reconciliation_complete")) || !"SCHEDULED".equals(session.get("scheduled_status")) || !"OBSERVED".equals(session.get("observed_status"))) continue;
                    Map<String,Object> observed=obj(session.get("declared_tested_conditions")),proposed=obj(s.get("conditions")); boolean covered=true,equal=true;
                    for(String d:DIMENSIONS) { if(observed.get(d)==null || proposed.get(d)==null) { covered=false; equal=false; unknown=true; }
                        else { int comp=number(proposed.get(d)).compareTo(number(observed.get(d))); if(comp>0) covered=false; if(comp!=0) equal=false; } }
                    within|=covered; exact|=equal;
                }
            }
            if(s.get("classification").equals("OBSERVED") && !exact) c.failure("scenario."+s.get("tier"),"Declared observed scenario has no exact jointly recorded condition vector");
            Map<String,Object> r=new TreeMap<>(s); r.put("status",exact ? "MATCHES_RECORDED_CONDITIONS_UNAUTHENTICATED" : within ? "WITHIN_RECORDED_BOUNDS_UNTESTED" : "UNTESTED");
            r.put("unknown_parameters",unknown); r.put("scale_authorized",false); r.put("capacity_limit_established",false); c.scenarios.add(r);
        }
        if(ids.isEmpty()) c.missing("capacity_scenarios","Owner must declare the tier ladder and all unknown parameters explicitly");
    }
    private static void thresholds(Context c) throws IOException {
        if(c.protocol==null) return;
        c.report.put("thresholds_evaluated",c.protocolDeclaredApproved);
        c.report.put("threshold_basis","Diagnostic comparison to signed caller declarations; approval identities remain unauthenticated");
        if(!c.protocolDeclaredApproved) { c.missing("thresholds","Thresholds were not evaluated: separate exact-protocol signed owner and reviewer approvals are required"); return; }
        for(Object raw:list(c.protocol.get("thresholds"))) {
            Map<String,Object> t=obj(raw); String metric=str(t,"metric");
            for(Map<String,Object> session:c.sessions) {
                Object v=obj(session.get("observations")).get(metric);
                if(v==null) { c.missing("threshold."+metric,"No supported observation for session "+session.get("session_id")); continue; }
                String expectedUnit=unit(metric);
                require(expectedUnit.equals(t.get("unit")),"Threshold unit mismatch for "+metric+"; expected "+expectedUnit);
                int compare=number(v).compareTo(number(t.get("value"))); String op=str(t,"operator");
                if(!(op.equals("LE") ? compare<=0 : op.equals("GE") ? compare>=0 : compare==0)) c.failure("threshold."+metric,"Preregistered rule violated at "+session.get("session_id"));
            }
        }
    }
    static String unit(String metric) {
        if(List.of("maximum_order_size","maximum_fill_size","ordered_quantity","filled_quantity").contains(metric)) return "UNITS";
        if(List.of("fill_ratio","participation").contains(metric)) return "RATIO";
        if(metric.equals("order_rate")) return "ORDERS_PER_UTC_SECOND";
        if(List.of("turnover","fees","realized_pnl","unrealized_pnl","drawdown","exposure","slippage","margin_proxy").contains(metric)) return "ACCOUNT_CURRENCY";
        return "COUNT";
    }
}
