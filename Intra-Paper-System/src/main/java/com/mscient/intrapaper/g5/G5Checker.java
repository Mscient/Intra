package com.mscient.intrapaper.g5;

import com.mscient.intrapaper.g0.G0Checker;
import com.mscient.intrapaper.evaluation.hypothesis.HypothesisCard;
import com.mscient.intrapaper.evaluation.ResearchTrialLedger;
import com.mscient.intrapaper.evaluation.portfolio.PortfolioTrialEvaluationAnalyzer;
import com.mscient.intrapaper.permission.preflight.PermissionPreflight;
import com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceChecker;
import com.mscient.intrapaper.audit.feed.FeedAudit;
import com.mscient.intrapaper.audit.portfolio.PortfolioRiskAudit;
import com.mscient.intrapaper.audit.portfolio.PortfolioSettlementAudit;
import com.mscient.intrapaper.simulation.portfolio.PortfolioReplayDeterminismVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.jar.JarFile;
import static com.mscient.intrapaper.g5.G5Files.*;

/** Offline readiness diagnostics only. There is deliberately no authorization or egress API. */
public final class G5Checker {
    public static final String VERSION = "INTRA_G5_DOSSIER_V1";
    public enum Status { BLOCKED, INCOMPLETE, FAIL, ELIGIBLE_FOR_HUMAN_DECISION }
    public record Requirement(String gate, String claim, String verifier, String action) { }
    public static final SortedMap<String,Requirement> REQUIREMENTS = requirements();
    static final List<String> IDENTITIES = List.of("build","config","card","strategy","implementation","dataset","protocol","family");
    static final Set<String> CLASSIFICATIONS = Set.of("SYNTHETIC","CALLER_SUPPLIED_UNAUTHENTICATED","SIGNED_KEY_POSSESSION_ONLY","AI_GENERATED");
    static final List<String> SCOPE = List.of("scope","instruments","broker_route","data_source","hosting","network","stop","recovery","operating_policy");
    public record Result(Status status, byte[] report, String dossierDigest, G5History.State state) {
        public Result { report = report.clone(); }
        @Override public byte[] report() { return report.clone(); }
        public String reportHash() { return hash(report); }
        public boolean g5Closed() { return false; }
        public boolean orderEgressEnabled() { return false; }
    }
    private final List<Map<String,Object>> findings = new ArrayList<>(), evidence = new ArrayList<>();
    private final SortedMap<String,byte[]> files;
    private final Map<String,Object> dossier, identities;
    private final Map<String,Map<String,Object>> actors = new TreeMap<>();
    private final Instant asOf;
    private final Path sourceRoot;
    private final String digest;
    private long requiredSessions, requiredStreak, maxAge;
    private boolean expired;
    private boolean inputChanged;
    private boolean readLocationBoundG0;
    private final Set<String> boundPaths = new HashSet<>();
    private G5Checker(SortedMap<String,byte[]> files, Instant asOf, Path sourceRoot) throws IOException {
        this.files = files; this.asOf = asOf; this.sourceRoot=sourceRoot;
        byte[] input = files.get("dossier.json"); if (input == null) throw new IOException("G5_DOSSIER_MISSING");
        dossier = json(input);
        keys(dossier,"schema","dossier_id","identities","build_commit","private_ref","private_sha256","roles","scope_refs","files","evidence");
        if (!string(dossier,"schema").equals(VERSION)) throw invalid();
        ref(string(dossier,"dossier_id")); ref(string(dossier,"private_ref")); digest(string(dossier,"private_sha256"));
        if (!string(dossier,"build_commit").matches("[0-9a-f]{40}")) throw invalid();
        identities = object(dossier.get("identities")); keys(identities,IDENTITIES.toArray(String[]::new));
        for (String name : IDENTITIES) digest(string(identities,name));
        Map<String,Object> scopes = object(dossier.get("scope_refs")); keys(scopes,SCOPE.toArray(String[]::new));
        for (String name : SCOPE) ref(string(scopes,name));
        digest = hash(input); // Exact bytes, including whitespace, are the reviewed dossier.
    }
    static Instant time(String s) throws IOException {
        if (!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z")) throw invalid();
        try { Instant i = Instant.parse(s); if (!i.toString().equals(s)) throw invalid(); return i; }
        catch (DateTimeException e) { throw invalid(); }
    }
    static SortedMap<String,Requirement> requirements() {
        SortedMap<String,Requirement> r = new TreeMap<>();
        add(r,"G0","CLOSED","G0","charter","Owner: close the charter with current evidence");
        add(r,"G0","APPROVED","DOCUMENT","authority budgets account_scope data_rights","Owner: supply private inputs and stop/resume authority; rights reviewer: verify executed rights");
        add(r,"G1","CLOSED","DOCUMENT","gate","Owner and independent reviewer: close G1");
        add(r,"G1","APPROVED","HYPOTHESIS_CARD","card","Owner: approve and freeze the exact strategy card");
        add(r,"G1","APPROVED","DOCUMENT","implementation trial_protocol","Reviewer: verify exact implementation and trial protocol");
        add(r,"G2","CLOSED","DOCUMENT","gate","Independent reviewer: close G2 from authentic evidence");
        add(r,"G2","APPROVED","PERMISSION","permissions","Rights reviewer: verify dataset and exact permitted uses");
        add(r,"G2","PASS","PORTFOLIO_REPLAY","replay","Reviewer: reproduce authorized dataset/build replay");
        add(r,"G2","PASS","RISK_AUDIT","risk_audit","Reviewer: verify risk replay and risk audit");
        add(r,"G2","PASS","SETTLEMENT_AUDIT","settlement_audit","Reviewer: verify settlement audit");
        add(r,"G2","APPROVED","DOCUMENT","dataset_build quality cost_margin","Reviewer: verify data quality and effective cost/margin provenance");
        add(r,"G2","RESOLVED","DOCUMENT","reconciliation limitations","Independent reconciler: resolve every exception and limitation");
        add(r,"G3","CLOSED","DOCUMENT","gate","Independent reviewer: close G3");
        add(r,"G3","ELIGIBLE","DOCUMENT","decision","Independent reviewer: supply eligible strategy-evidence decision");
        add(r,"G3","PASS","REGISTRATION","registration","Custodian: verify exact registered trial family");
        add(r,"G3","PASS","TRIAL_EVALUATION","statistics","Independent reviewer: reproduce the registered statistical report with explicit frozen settings and outcome history");
        add(r,"G3","SEALED","DOCUMENT","holdout","Independent custodian: prove holdout status and prior exposure");
        add(r,"G3","APPROVED","DOCUMENT","independent_review","Independent human reviewer: review statistical and economic evidence");
        add(r,"G4","CLOSED","DOCUMENT","gate","Owner and independent operations reviewer: close G4 using actual evidence");
        add(r,"G4","COMPLETE","DAILY_OPS","session","Operator and operations reviewer: supply actual complete sessions, schedule and pre-open streak");
        add(r,"G4","PASS","FEED_AUDIT","feed_audit","Operations reviewer: verify authorized actual feed audit");
        add(r,"G4","COMPLETE","DOCUMENT","drill","Operations reviewer: verify actual drills in the intended environment");
        add(r,"G4","RESOLVED","DOCUMENT","reconciliation","Independent reconciler: resolve daily reconciliation exceptions");
        add(r,"G4","CLEAR","DOCUMENT","severe_incidents","Operations reviewer: prove no unresolved severe incident");
        add(r,"G5","APPROVED","DOCUMENT","scope exact_configuration instruments broker_route data_source hosting static_ip current_rule_review operator reviewer stop recovery private_limits operating_policy","Owner, broker, rights and independent reviewers: supply current exact-scope approvals and operating evidence");
        return Collections.unmodifiableSortedMap(r);
    }
    private static void add(Map<String,Requirement> r, String gate, String claim, String verifier, String names, String action) {
        for (String name : names.split(" ")) r.put(gate+"_"+name,new Requirement(gate,claim,verifier,action));
    }
    private void finding(String item, String level, String code, String action) {
        findings.add(Map.of("item",item,"level",level,"code",code,"action",action));
    }
    private void privateInputs(Path privateFile) throws IOException {
        if (privateFile == null || !Files.exists(privateFile,LinkOption.NOFOLLOW_LINKS)) {
            finding("private_inputs","INCOMPLETE","MISSING_PRIVATE_OWNER_INPUTS","Owner: supply salted private inputs through the private directory"); return;
        }
        byte[] b = read(privateFile);
        if (!hash(b).equals(dossier.get("private_sha256"))) { finding("private_inputs","FAIL","PRIVATE_INPUT_HASH_MISMATCH","Owner: create a new dossier revision"); return; }
        Map<String,Object> p = json(b);
        keys(p,"schema","nonce","reference","required_sessions","required_preopen_streak","max_evidence_age_seconds","pilot_start_utc","pilot_end_utc","risk_limits","policy_ref");
        if (!string(p,"schema").equals("INTRA_G5_PRIVATE_V1") || !ref(string(p,"reference")).equals(dossier.get("private_ref"))) throw invalid();
        digest(string(p,"nonce")); ref(string(p,"policy_ref"));
        if (!p.get("policy_ref").equals(object(dossier.get("scope_refs")).get("operating_policy"))) finding("private_inputs","FAIL","PRIVATE_POLICY_REFERENCE_MISMATCH","Owner: bind the private thresholds to the exact operating policy");
        requiredSessions = integer(p,"required_sessions"); requiredStreak = integer(p,"required_preopen_streak"); maxAge = integer(p,"max_evidence_age_seconds");
        if (requiredSessions == 0 || requiredStreak == 0 || maxAge == 0 || requiredStreak > requiredSessions) throw invalid();
        Instant start = time(string(p,"pilot_start_utc")), end = time(string(p,"pilot_end_utc")); if (!end.isAfter(start)) throw invalid();
        if (!asOf.isBefore(end)) { expired = true; finding("private_inputs","BLOCKED","PILOT_SCOPE_EXPIRED","Owner: supersede scope; obtain fresh reviews"); }
        Map<String,Object> limits = object(p.get("risk_limits"));
        keys(limits,"capital","annual_spend","pilot_loss","position","notional","margin","order_rate","session_loss","currency_ref","measurement_policy_ref");
        for (String k : List.of("capital","annual_spend","pilot_loss","position","notional","margin","order_rate","session_loss")) {
            if (!string(limits,k).matches("(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{1,8})?")) throw invalid();
        }
        ref(string(limits,"currency_ref")); ref(string(limits,"measurement_policy_ref"));
        finding("private_inputs","BLOCKED","OWNER_INPUT_AUTHENTICITY_UNESTABLISHED","Owner and independent reviewer: authenticate deliberate private inputs; this checker cannot authenticate them");
    }
    private void roles() throws IOException {
        Map<String,Object> roles = object(dossier.get("roles")); keys(roles,"owner","operator","reviewer","custodian");
        for (var e : roles.entrySet()) {
            if (e.getValue() == null) { finding("role_"+e.getKey(),"BLOCKED","ROLE_MISSING","Owner: appoint the accountable human role"); continue; }
            Map<String,Object> a = object(e.getValue()); keys(a,"id","public_key","key_sha256","qualification_ref","independence_ref");
            ref(string(a,"id")); digest(string(a,"key_sha256")); ref(string(a,"qualification_ref")); ref(string(a,"independence_ref"));
            String publicKey = string(a,"public_key");
            try { if (Base64.getDecoder().decode(publicKey).length != 44 || !hash(Base64.getDecoder().decode(publicKey)).equals(a.get("key_sha256"))) throw invalid(); }
            catch (IllegalArgumentException ex) { throw invalid(); }
            actors.put(e.getKey(),a);
        }
        Map<String,Object> reviewer = actors.get("reviewer"), operator = actors.get("operator");
        if (reviewer != null && operator != null && (reviewer.get("id").equals(operator.get("id")) || reviewer.get("key_sha256").equals(operator.get("key_sha256"))))
            finding("role_reviewer","FAIL","REVIEWER_NOT_INDEPENDENT","Owner: appoint a different qualified independent human");
        finding("roles","BLOCKED","IDENTITY_QUALIFICATION_INDEPENDENCE_UNESTABLISHED","Independent authority: authenticate human identities, qualifications and organizational independence outside the local key trust domain");
    }
    private void fileBindings() throws IOException {
        Set<String> ids = new HashSet<>(), paths = new HashSet<>();
        for (Object value : list(dossier,"files")) {
            Map<String,Object> f = object(value); keys(f,"id","path","sha256");
            String name = id(string(f,"id")), path = relative(string(f,"path")), expected = digest(string(f,"sha256"));
            if (!ids.add(name) || !paths.add(path) || path.equals("dossier.json") || path.startsWith("history/") || path.startsWith("decisions/") || path.startsWith("archive/")) throw invalid();
            boundPaths.add(path);
            byte[] b = files.get(path);
            if (b == null) { inputChanged=true; finding(name,"INCOMPLETE","BOUND_FILE_MISSING","Owner: supply exact referenced bytes"); }
            else if (!hash(b).equals(expected)) { inputChanged=true; finding(name,"FAIL","BOUND_FILE_TAMPERED","Owner: recover original bytes or explicitly supersede dossier"); }
            if (IDENTITIES.contains(name) && !expected.equals(identities.get(name))) finding(name,"FAIL","EXACT_IDENTITY_MISMATCH","Owner: reconcile build/card/strategy/config identities");
            evidence.add(Map.of("item",name,"source_identity",hash(path.getBytes(StandardCharsets.UTF_8)),"expected_sha256",expected,
                    "observed_sha256",b == null ? ZERO : hash(b),"provenance","CALLER_SUPPLIED_UNAUTHENTICATED"));
            if (name.equals("build") && b != null && hash(b).equals(expected)) {
                // The build file must be a locally saved JAR; it is inspected, never executed.
                Path tmp = Files.createTempFile("intra-g5-build-",".jar");
                try {
                    Files.write(tmp,b);
                    try (JarFile jar = new JarFile(tmp.toFile())) {
                        var entry=jar.getJarEntry("META-INF/MANIFEST.MF"); String commit=null;
                        if (entry != null) {
                            if (entry.getSize()>65536) throw invalid();
                            try(var in=jar.getInputStream(entry)) {
                                byte[] manifest=in.readNBytes(65537); if(manifest.length>65536) throw invalid();
                                commit=new java.util.jar.Manifest(new java.io.ByteArrayInputStream(manifest)).getMainAttributes().getValue("Build-Commit");
                            }
                        }
                        if (!dossier.get("build_commit").equals(commit)) finding("build","FAIL","BUILD_COMMIT_MISMATCH","Owner: nominate a clean built and tested revision");
                    }
                } finally { Files.deleteIfExists(tmp); }
            }
        }
        for (String name : IDENTITIES) if (!ids.contains(name)) finding(name,"INCOMPLETE","EXACT_IDENTITY_FILE_MISSING","Owner: bind the exact local identity file");
    }
    private void evidence(Path frozen) throws IOException {
        for (Object value : list(dossier,"evidence")) boundPaths.add(relative(string(object(value),"path")));
        for (String path : files.keySet()) if (!path.equals("dossier.json") && !path.startsWith("history/") && !path.startsWith("archive/") && !path.startsWith("decisions/") && !boundPaths.contains(path)) {
            inputChanged=true; finding("inputs","FAIL","UNBOUND_INPUT_FILE","Owner: bind every verifier dependency; publish reports outside the dossier tree");
        }
        Set<String> present = new HashSet<>(), ids = new HashSet<>(), contentHashes = new HashSet<>();
        SortedMap<LocalDate,Map<String,Object>> sessions = new TreeMap<>(); long drills = 0;
        for (Object value : list(dossier,"evidence")) {
            Map<String,Object> ref = object(value); keys(ref,"id","path","sha256");
            String itemId = ref(string(ref,"id")), path = relative(string(ref,"path")), expected = digest(string(ref,"sha256"));
            if (path.startsWith("archive/") || path.startsWith("decisions/") || path.startsWith("history/") || path.equals("dossier.json")) throw invalid();
            if (!ids.add(itemId)) throw invalid();
            boundPaths.add(path);
            byte[] b = files.get(path);
            if (b == null) { inputChanged=true; finding(itemId,"INCOMPLETE","EVIDENCE_MISSING","Owner/reviewer: supply referenced evidence bytes"); continue; }
            if (!hash(b).equals(expected)) { inputChanged=true; finding(itemId,"FAIL","EVIDENCE_TAMPERED","Owner: recover exact bytes or append a correction"); continue; }
            Map<String,Object> e = json(b);
            keys(e,"schema","id","requirement","source_ref","classification","claim","at_utc","valid_until_utc","identities","actor_role","payload_path","payload_sha256","verifier","arguments","session_date","preopen","signature");
            if (!string(e,"schema").equals("INTRA_G5_EVIDENCE_V1") || !itemId.equals(ref(string(e,"id")))) throw invalid();
            String req = string(e,"requirement"); Requirement rule = REQUIREMENTS.get(req); if (rule == null) throw invalid();
            present.add(req); ref(string(e,"source_ref")); String classification = string(e,"classification"); if (!CLASSIFICATIONS.contains(classification)) throw invalid();
            String claim = string(e,"claim");
            if (!Set.of("CLOSED","APPROVED","PASS","COMPLETE","CLEAR","RESOLVED","SEALED","ELIGIBLE","OPEN","PENDING","FAIL","UNKNOWN","REJECTED").contains(claim)) throw invalid();
            if (!claim.equals(rule.claim)) finding(req,claim.equals("FAIL") || claim.equals("REJECTED") ? "FAIL" : "BLOCKED","REQUIREMENT_NOT_SATISFIED",rule.action);
            Instant at = time(string(e,"at_utc")), until = time(string(e,"valid_until_utc")); if (until.isBefore(at)) throw invalid();
            if (at.isAfter(asOf) || !asOf.isBefore(until) || maxAge == 0 || Duration.between(at,asOf).getSeconds() > maxAge) {
                expired=true;
                finding(req,"BLOCKED","STALE_EXPIRED_OR_FUTURE_EVIDENCE",rule.action);
            }
            Map<String,Object> bound = object(e.get("identities")); keys(bound,IDENTITIES.toArray(String[]::new));
            for (String name : IDENTITIES) if (!digest(string(bound,name)).equals(identities.get(name))) finding(req,"FAIL","EVIDENCE_IDENTITY_MISMATCH",rule.action);
            String actor = string(e,"actor_role"); if (!Set.of("owner","operator","reviewer","custodian").contains(actor)) throw invalid();
            String signature = actors.containsKey(actor) ? G5Signatures.verify(e,actors.get(actor)) : "UNVERIFIABLE_ROLE";
            if (signature.equals("INVALID_SIGNATURE")) finding(req,"FAIL","INVALID_SIGNATURE",rule.action);
            else if (!signature.equals("VALID_KEY_POSSESSION_ONLY")) finding(req,"BLOCKED","UNSIGNED_OR_UNVERIFIABLE",rule.action);
            finding(req,"BLOCKED",classification.equals("SYNTHETIC") || classification.equals("AI_GENERATED") ? "SYNTHETIC_OR_AI_NOT_ACTUAL_EVIDENCE" : "CALLER_EVIDENCE_AUTHENTICITY_UNESTABLISHED",rule.action);
            String payloadPath = relative(string(e,"payload_path")), payloadHash = digest(string(e,"payload_sha256")); byte[] payload = files.get(payloadPath);
            if (!boundPaths.contains(payloadPath)) finding(req,"FAIL","SOURCE_NOT_IN_DOSSIER_FILE_BINDINGS",rule.action);
            if (payload == null) { inputChanged=true; finding(req,"INCOMPLETE","SOURCE_BYTES_MISSING",rule.action); }
            else if (!hash(payload).equals(payloadHash)) { inputChanged=true; finding(req,"FAIL","SOURCE_BYTES_TAMPERED",rule.action); }
            if (!string(e,"verifier").equals(rule.verifier)) finding(req,"FAIL","REQUIRED_VERIFIER_MISMATCH",rule.action);
            else if (payload != null && hash(payload).equals(payloadHash)) runVerifier(req,rule,e,frozen);
            evidence.add(Map.of("item",req,"evidence_id",itemId,"source_identity",e.get("source_ref"),"evidence_sha256",expected,
                    "payload_sha256",payloadHash,"provenance",classification,"signature",signature));
            String date = string(e,"session_date"), preopen = string(e,"preopen");
            if (req.equals("G4_session")) {
                LocalDate d;
                try { d = LocalDate.parse(date); if (!d.toString().equals(date)) throw invalid(); } catch (DateTimeException ex) { throw invalid(); }
                if (!Set.of("PASS","FAIL","UNKNOWN").contains(preopen)) throw invalid();
                if (sessions.put(d,e) != null || !contentHashes.add(payloadHash)) finding(req,"FAIL","DUPLICATE_SESSION_OR_SOURCE",rule.action);
            } else if (!date.isEmpty() || !preopen.isEmpty()) throw invalid();
            if (req.equals("G4_drill")) drills++;
        }
        for (var e : REQUIREMENTS.entrySet()) if (!present.contains(e.getKey())) finding(e.getKey(),"BLOCKED","REQUIRED_EVIDENCE_MISSING",e.getValue().action);
        // Diagnostic counts only. They never count as actual sessions or drills.
        long streak = 0;
        for (Map<String,Object> session : sessions.values()) streak = "PASS".equals(session.get("preopen")) && "COMPLETE".equals(session.get("claim")) ? streak + 1 : 0;
        long completeSessions=sessions.values().stream().filter(s -> "COMPLETE".equals(s.get("claim"))).count();
        if (requiredSessions == 0 || completeSessions < requiredSessions) finding("G4_session","BLOCKED","INCOMPLETE_SESSION_COUNT","Owner: supply explicit required count and actual reviewed complete sessions");
        if (requiredStreak == 0 || streak < requiredStreak) finding("G4_session","BLOCKED","INCOMPLETE_PREOPEN_STREAK","Operations reviewer: verify scheduled-session continuity and required passing streak");
        if (drills == 0) finding("G4_drill","BLOCKED","ACTUAL_OPERATIONAL_DRILL_MISSING","Operations reviewer: supply actual environment drill evidence");
        finding("G4_session","BLOCKED","ACTUAL_SESSION_SCHEDULE_CUSTODY_UNESTABLISHED","Independent operations custodian: authenticate full schedule, actual sessions, pre-open chronology and exclusions");
    }
    private void runVerifier(String req, Requirement rule, Map<String,Object> e, Path frozen) throws IOException {
        List<Path> args = new ArrayList<>();
        for (Object a : list(e,"arguments")) {
            if (!(a instanceof String s) || s.startsWith("archive/") || s.startsWith("decisions/") || s.startsWith("history/")) throw invalid(); args.add(frozen.resolve(relative(s)));
        }
        try {
            if (inputChanged) { finding(req,"FAIL","VERIFIER_INPUT_TREE_INVALID",rule.action); return; }
            if (args.isEmpty() && !rule.verifier.equals("DOCUMENT")) {
                finding(req,"INCOMPLETE","VERIFIER_INPUTS_MISSING",rule.action); return;
            }
            G5VerifierPaths.beforeInvocation(rule.verifier,args,frozen);
            switch (rule.verifier) {
                case "DOCUMENT" -> { if (!args.isEmpty()) throw invalid(); finding(req,"INCOMPLETE","NO_INDEPENDENT_SEMANTIC_VERIFIER","Independent reviewer: verify document content against current source records"); }
                case "G0" -> {
                    arity(args,1);
                    // G0 deliberately binds canonical source locations. Moving it would invalidate its review.
                    Path original=safe(sourceRoot.resolve(frozen.relativize(args.getFirst())),true);
                    readLocationBoundG0=true;
                    if (G0Checker.evaluate(original).status() != G0Checker.Status.PASS) throw new IOException("NOT_PASS");
                    evidence.add(Map.of("item",req,"source_identity",hash(original.toUri().toASCIIString().getBytes(StandardCharsets.UTF_8)),"provenance","CALLER_SUPPLIED_UNAUTHENTICATED"));
                }
                case "HYPOTHESIS_CARD" -> {
                    arity(args,1); HypothesisCard card = HypothesisCard.load(args.getFirst());
                    if (!card.status().equals("FROZEN_DECLARED") || !card.sha256().equals(identities.get("card"))) throw new IOException("NOT_FROZEN");
                    Map<String,Object> c = json(read(args.getFirst()));
                    String strategy = hash((c.get("strategy_id")+"\n"+c.get("strategy_version")+"\n").getBytes(StandardCharsets.UTF_8));
                    if (!strategy.equals(identities.get("strategy"))) throw new IOException("STRATEGY_MISMATCH");
                }
                case "PERMISSION" -> { arity(args,3); if (PermissionPreflight.check(args.get(0),args.get(1),args.get(2),asOf.atOffset(ZoneOffset.UTC).toLocalDate()).status() != PermissionPreflight.Status.ALLOWED) throw new IOException("NOT_ALLOWED"); }
                case "PORTFOLIO_REPLAY" -> { arity(args,3); argumentIdentity(args.get(0),"dataset"); argumentIdentity(args.get(1),"config"); PortfolioReplayDeterminismVerifier.verify(args.get(0),args.get(1),args.get(2)); }
                case "RISK_AUDIT" -> { arity(args,5); argumentIdentity(args.get(2),"dataset"); argumentIdentity(args.get(3),"config"); PortfolioRiskAudit.verify(args.get(0),args.get(1),args.get(2),args.get(3),args.get(4)); }
                case "SETTLEMENT_AUDIT" -> { arity(args,6); argumentIdentity(args.get(2),"dataset"); argumentIdentity(args.get(3),"config"); PortfolioSettlementAudit.verify(args.get(0),args.get(1),args.get(2),args.get(3),args.get(4),args.get(5)); }
                case "REGISTRATION" -> { arity(args,1); argumentIdentity(args.getFirst(),"family"); ResearchTrialLedger.verify(args.getFirst()); }
                case "TRIAL_EVALUATION" -> {
                    arity(args,6); argumentIdentity(args.getFirst(),"family");
                    Map<String,Object> settings=json(read(args.get(3))); keys(settings,"schema","chronological_block_count");
                    if (!string(settings,"schema").equals("INTRA_G5_EVALUATION_SETTINGS_V1")) throw invalid();
                    long blocks=integer(settings,"chronological_block_count"); if (blocks>Integer.MAX_VALUE) throw invalid();
                    byte[] reproduced=PortfolioTrialEvaluationAnalyzer.analyze(args.get(0),args.get(1),args.get(2),(int)blocks,args.get(5)).toJson().getBytes(StandardCharsets.UTF_8);
                    if (!Arrays.equals(reproduced,read(args.get(4)))) throw new IOException("G5_STATISTICAL_REPORT_REPRODUCTION_MISMATCH");
                }
                case "DAILY_OPS" -> { arity(args,1); if (DailyOpsEvidenceChecker.check(args.getFirst()).status() != com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceReport.Status.PASS) throw new IOException("NOT_PASS"); }
                case "FEED_AUDIT" -> { arity(args,1); if (!"PASS".equals(FeedAudit.run(args.getFirst()).get("status"))) throw new IOException("NOT_PASS"); }
                default -> throw invalid();
            }
            if (!rule.verifier.equals("DOCUMENT")) finding(req,"INFO","EXISTING_VERIFIER_PASSED_DIAGNOSTIC_ONLY",rule.action);
        } catch (IOException | IllegalArgumentException | IllegalStateException ex) {
            // Never copy downstream error strings, paths, financial values or source bytes into a report.
            finding(req,"FAIL","EXISTING_VERIFIER_REJECTED",rule.action);
        }
    }
    private static void arity(List<Path> args, int count) throws IOException { if (args.size()!=count) throw invalid(); }
    private void argumentIdentity(Path argument, String identity) throws IOException {
        if (!hash(read(argument)).equals(identities.get(identity))) throw new IOException("G5_VERIFIER_INPUT_IDENTITY_MISMATCH");
    }
    private boolean decision(String role, G5History.Chain history) throws IOException {
        String name = "decisions/"+role+".json"; byte[] b = files.get(name);
        if (b == null) { finding(role+"_decision","INCOMPLETE","HUMAN_DECISION_MISSING","Human "+role+": review exact dossier and build and supply a separate signed decision"); return false; }
        Map<String,Object> d = json(b);
        keys(d,"schema","decision_id","role","actor","decision","dossier_sha256","build_sha256","at_utc","valid_until_utc","qualification_ref","independence_ref","classification","signature");
        if (!string(d,"schema").equals("INTRA_G5_DECISION_V1") || !string(d,"role").equals(role)) throw invalid();
        ref(string(d,"decision_id")); ref(string(d,"actor")); ref(string(d,"qualification_ref")); ref(string(d,"independence_ref"));
        String kind = string(d,"decision"), classification = string(d,"classification");
        if (!Set.of("APPROVE_FUTURE_HUMAN_CONSIDERATION","REJECT","REVOKE").contains(kind) || !CLASSIFICATIONS.contains(classification)) throw invalid();
        boolean valid = true;
        if (!digest(string(d,"dossier_sha256")).equals(digest) || !digest(string(d,"build_sha256")).equals(identities.get("build"))) {
            valid = false; finding(role+"_decision","FAIL","OUTDATED_DOSSIER_OR_BUILD_DECISION","Human "+role+": review the revised dossier and build");
        }
        Instant at = time(string(d,"at_utc")), until = time(string(d,"valid_until_utc")); if (!until.isAfter(at)) throw invalid();
        if (at.isAfter(asOf) || !asOf.isBefore(until)) { valid = false; expired = true; finding(role+"_decision","BLOCKED","EXPIRED_OR_FUTURE_DECISION","Human "+role+": supply a current exact-digest decision"); }
        Map<String,Object> actor = actors.get(role);
        if (actor == null || !actor.get("id").equals(d.get("actor")) || !actor.get("qualification_ref").equals(d.get("qualification_ref")) || !actor.get("independence_ref").equals(d.get("independence_ref"))) {
            valid = false; finding(role+"_decision","FAIL","DECISION_ACTOR_OR_ROLE_MISMATCH","Owner: reconcile assigned qualified human roles");
        }
        String sig = actor == null ? "UNVERIFIABLE_ROLE" : G5Signatures.verify(d,actor);
        if (!sig.equals("VALID_KEY_POSSESSION_ONLY")) { valid = false; finding(role+"_decision",sig.equals("INVALID_SIGNATURE")?"FAIL":"BLOCKED","INVALID_OR_MISSING_DECISION_SIGNATURE","Human "+role+": supply a valid exact-digest signature"); }
        String eventKind = role.equals("owner") ? "OWNER_DECISION" : "REVIEW";
        boolean recorded = history.events().stream().anyMatch(ev -> ev.value().get("kind").equals(eventKind) && ev.value().get("attachment_sha256").equals(hash(b)) && ev.value().get("dossier_sha256").equals(digest) && ev.value().get("build_sha256").equals(identities.get("build")) && ev.value().get("actor").equals(d.get("actor")) && ev.value().get("at_utc").equals(d.get("at_utc")));
        if (!recorded) { valid=false; finding(role+"_decision","BLOCKED","DECISION_NOT_IN_ANCHORED_HISTORY","Custodian: append decision event and retain new head independently"); }
        if (!kind.equals("APPROVE_FUTURE_HUMAN_CONSIDERATION")) { valid=false; expired=true; finding(role+"_decision","BLOCKED","HUMAN_REJECTED_OR_REVOKED","Owner: preserve rejection or revocation; do not deploy"); }
        evidence.add(Map.of("item",role+"_decision","evidence_sha256",hash(b),"provenance",classification,"signature",sig));
        finding(role+"_decision","BLOCKED","HUMAN_AUTHENTICITY_NOT_ESTABLISHED","Independent authority: authenticate the human decision outside caller-supplied keys");
        return valid;
    }
    public static Result evaluate(Path inputRoot, Path privateFile, Instant asOf, String retainedHead) throws IOException {
        SortedMap<String,byte[]> files = snapshot(inputRoot);
        for (var entry : files.entrySet()) {
            if (entry.getKey().startsWith("decisions/") && !Set.of("decisions/owner.json","decisions/reviewer.json").contains(entry.getKey())) throw invalid();
            if (entry.getKey().startsWith("archive/") && (!entry.getKey().matches("archive/[0-9a-f]{64}\\.bin") || !entry.getKey().equals("archive/"+hash(entry.getValue())+".bin"))) throw new IOException("G5_ARCHIVE_TAMPERED");
        }
        G5Checker c = new G5Checker(files,asOf,safe(inputRoot,true));
        c.privateInputs(privateFile); c.roles(); c.fileBindings();
        G5History.Chain history = G5History.verify(files,retainedHead,asOf);
        Set<String> archivedEvidence = new HashSet<>();
        Set<String> seenDossiers = new HashSet<>();
        for (G5History.Event event : history.events()) {
            String archivedDossier = (String)event.value().get("dossier_sha256");
            byte[] original = files.get("archive/"+archivedDossier+".bin");
            if (original == null || !hash(original).equals(archivedDossier)) c.finding("history","FAIL","ORIGINAL_DOSSIER_HISTORY_BYTES_MISSING","Custodian: retain each immutable dossier revision");
            else if (seenDossiers.add(archivedDossier)) {
                Map<String,Object> historic=json(original);
                for (String group:List.of("files","evidence")) for (Object value:list(historic,group)) {
                    String h=digest(string(object(value),"sha256")); byte[] retained=files.get("archive/"+h+".bin");
                    if (retained==null || !hash(retained).equals(h)) c.finding("history","BLOCKED","ORIGINAL_BOUND_EVIDENCE_NOT_RETAINED","Custodian: retain original evidence and dependency bytes for every dossier revision");
                }
            }
            String attachment = (String)event.value().get("attachment_sha256");
            if (!attachment.equals(ZERO)) {
                byte[] content = files.get("archive/"+attachment+".bin");
                if (content == null || !hash(content).equals(attachment)) c.finding("history","FAIL","HISTORY_ATTACHMENT_MISSING_OR_TAMPERED","Custodian: recover retained event attachment bytes");
                archivedEvidence.add(attachment);
            }
            if (event.value().get("kind").equals("EVIDENCE_CHECKED")) c.finding("history","BLOCKED","RECORDED_CHECK_IS_NOT_AUTHENTICATED_GATE_CLOSURE","Reviewer: local history events cannot promote gate state");
        }
        for (Object ev : list(c.dossier,"evidence")) if (!archivedEvidence.contains(string(object(ev),"sha256")))
            c.finding(ref(string(object(ev),"id")),"BLOCKED","EVIDENCE_ADDITION_NOT_IN_RETAINED_HISTORY","Custodian: record evidence addition with exact retained bytes");
        if (history.events().isEmpty()) c.finding("history","BLOCKED","HISTORY_MISSING","Custodian: append creation and evidence events; retain head independently");
        boolean changed = !history.dossier().equals(c.digest);
        if (changed) c.finding("history","BLOCKED","DOSSIER_CHANGED_INVALIDATES_BOTH_DECISIONS","Owner and reviewer: supersede input history, then repeat all downstream checks and reviews");
        if (history.revoked()) c.finding("history","BLOCKED","REVOCATION_LATCHED","Owner: preserve revocation; create a separate dossier for any later proposal");
        // Freeze verifier inputs in a private temporary tree, so all calculations consume the same bytes.
        Path frozen = Files.createTempDirectory("intra-g5-snapshot-");
        try {
            for (var e : files.entrySet()) {
                if (e.getKey().startsWith("history/") || e.getKey().startsWith("decisions/")) continue;
                Path p = frozen.resolve(e.getKey()); Files.createDirectories(p.getParent()); Files.write(p,e.getValue(),StandardOpenOption.CREATE_NEW);
            }
            // Reject escapes in referenced verifier inputs before any existing parser opens attachments.
            try { G5VerifierPaths.validate(files); }
            catch (IOException ex) { c.inputChanged=true; c.finding("inputs","FAIL","VERIFIER_INPUT_TREE_INVALID","Owner: repair malformed or escaping verifier inputs; preserve original evidence history"); }
            c.evidence(frozen);
            if (c.readLocationBoundG0 && !treeHash(files).equals(treeHash(snapshot(c.sourceRoot)))) throw new IOException("G5_SOURCE_CHANGED_DURING_LOCATION_BOUND_VERIFICATION");
        } finally {
            try (var walk = Files.walk(frozen)) { for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
        boolean reviewer = c.decision("reviewer",history), owner = c.decision("owner",history);
        // Current verifier contracts cannot authenticate G0-G4 closure or independent humans.
        // There is no configuration, key enrollment, environment switch or boolean to promote this basis.
        c.finding("G5","BLOCKED","INDEPENDENT_AUTHENTICITY_ADAPTER_NOT_IMPLEMENTED","Owner: obtain independent source/identity/custody review; a separately reviewed implementation is required to consume authenticated evidence");
        Status status = c.findings.stream().anyMatch(f -> f.get("level").equals("FAIL")) ? Status.FAIL :
                c.findings.stream().anyMatch(f -> f.get("level").equals("BLOCKED")) ? Status.BLOCKED :
                c.findings.stream().anyMatch(f -> f.get("level").equals("INCOMPLETE")) ? Status.INCOMPLETE : Status.ELIGIBLE_FOR_HUMAN_DECISION;
        boolean invalidated = changed || history.revoked() || c.expired || c.inputChanged || status == Status.FAIL;
        G5History.State state = G5History.state(status == Status.ELIGIBLE_FOR_HUMAN_DECISION,reviewer,owner,invalidated);
        c.findings.sort(Comparator.comparing(G5Files::sortKey)); c.evidence.sort(Comparator.comparing(G5Files::sortKey));
        Map<String,Object> report = new TreeMap<>();
        report.put("schema","INTRA_G5_REPORT_V1"); report.put("status",status.name()); report.put("as_of_utc",asOf.toString());
        report.put("dossier_sha256",c.digest); report.put("input_tree_sha256",treeHash(files)); report.put("history_head",history.head());
        report.put("identities",c.identities); report.put("build_commit",c.dossier.get("build_commit")); report.put("state",state.name());
        report.put("owner_signature_binding_valid",owner && !invalidated);
        report.put("reviewer_signature_binding_valid",reviewer && !invalidated);
        report.put("human_identity_verified",false); report.put("g5_closed",false); report.put("order_egress_enabled",false); report.put("live_authorized",false);
        Map<String,Object> gates=new TreeMap<>();
        for (String gate:List.of("G0","G1","G2","G3","G4")) {
            boolean failed=c.findings.stream().anyMatch(f->f.get("item").toString().startsWith(gate+"_") && f.get("level").equals("FAIL"));
            gates.put(gate,Map.of("status",failed?"FAIL":"BLOCKED","closure_proven",false));
        }
        report.put("gates",gates);
        report.put("provenance","CALLER_SUPPLIED_UNAUTHENTICATED"); report.put("evidence",c.evidence); report.put("findings",c.findings);
        report.put("checker_identity",implementation());
        report.put("limits","Offline diagnostic only. Key possession and hashes do not establish human identity, independence, actual sessions, source truth, permission, legal compliance, profitability or trading readiness. Frozen as-of is caller-supplied; no clock attestation. Retain report hash and history head separately; local publication is not power-loss or external-custody proof.");
        return new Result(status,bytes(report),c.digest,state);
    }
    private static String implementation() throws IOException {
        try {
            Path p = Path.of(G5Checker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isDirectory(p)) return hash(read(p));
            return treeHash(snapshot(p));
        } catch (java.net.URISyntaxException e) { throw invalid(); }
    }
    public static void verify(Path root, Path privateFile, Instant asOf, String head, Path report, String retainedReportHash) throws IOException {
        digest(retainedReportHash); byte[] actual = read(report);
        if (!hash(actual).equals(retainedReportHash)) throw new IOException("G5_REPORT_HASH_MISMATCH");
        if (!Arrays.equals(actual,evaluate(root,privateFile,asOf,head).report())) throw new IOException("G5_REPORT_RECONSTRUCTION_MISMATCH");
    }
}
