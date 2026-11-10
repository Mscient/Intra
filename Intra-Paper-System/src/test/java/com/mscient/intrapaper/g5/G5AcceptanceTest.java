package com.mscient.intrapaper.g5;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.jar.*;
import static com.mscient.intrapaper.g5.G5Files.*;

/** Synthetic-only acceptance; no retained secret keys, private people, accounts or real market inputs. */
public final class G5AcceptanceTest {
    static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
    static final String AT = "2026-11-08T10:00:00Z", UNTIL = "2026-11-09T12:00:00Z";
    static final String COMMIT = "a".repeat(40);
    static int checks, serial;
    static Path root;
    static String refId() { return "ref-"+String.format(Locale.ROOT,"%032x",++serial); }
    static void expect(boolean v, String name) { checks++; if (!v) throw new AssertionError(name); }
    interface Throwing { void run() throws Exception; }
    static void rejects(Throwing code, String name) throws Exception {
        boolean rejected=false; try { code.run(); } catch (IOException | IllegalArgumentException e) { rejected=true; }
        expect(rejected,name);
    }
    static Map<String,Object> m(Object... fields) {
        Map<String,Object> out = new TreeMap<>(); for (int i=0;i<fields.length;i+=2) out.put((String)fields[i],fields[i+1]); return out;
    }
    static final class Fixture {
        Path dir, privateFile; Map<String,Object> dossier, identities, privateValues;
        Map<String,Map<String,Object>> items = new TreeMap<>(), roles = new TreeMap<>();
        Map<String,KeyPair> keys = new TreeMap<>(); String head=ZERO; int sequence;
        Fixture() throws Exception {
            dir=Files.createDirectory(root.resolve("case-"+(++serial))); Files.createDirectories(dir.resolve("archive")); Files.createDirectories(dir.resolve("history"));
            privateFile=dir.resolveSibling(dir.getFileName()+"-private.json");
            Map<String,Object> limits=m("capital","0","annual_spend","0","pilot_loss","0","position","0","notional","0","margin","0","order_rate","0","session_loss","0","currency_ref",refId(),"measurement_policy_ref",refId());
            privateValues=m("schema","INTRA_G5_PRIVATE_V1","nonce",hash(refId().getBytes(StandardCharsets.UTF_8)),"reference",refId(),"required_sessions",2,"required_preopen_streak",2,"max_evidence_age_seconds",86400,
                    "pilot_start_utc","2026-11-08T13:00:00Z","pilot_end_utc",UNTIL,"risk_limits",limits,"policy_ref",refId());
            Files.write(privateFile,bytes(privateValues));
            for (String role : List.of("owner","operator","reviewer","custodian")) {
                KeyPair pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); keys.put(role,pair);
                roles.put(role,m("id",refId(),"public_key",Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),"key_sha256",hash(pair.getPublic().getEncoded()),"qualification_ref",refId(),"independence_ref",refId()));
            }
            identities=new TreeMap<>(); List<Object> bound=new ArrayList<>();
            for (String name : G5Checker.IDENTITIES) {
                String path=name+".bin"; byte[] b=("SYNTHETIC_ONLY_"+name+"\n").getBytes(StandardCharsets.UTF_8);
                if (name.equals("build")) {
                    path="build.jar"; Manifest manifest=new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version","1.0"); manifest.getMainAttributes().putValue("Build-Commit",COMMIT);
                    try (JarOutputStream jar=new JarOutputStream(Files.newOutputStream(dir.resolve(path)),manifest)) { jar.putNextEntry(new JarEntry("SYNTHETIC_ONLY.txt")); jar.write(b); jar.closeEntry(); }
                    b=Files.readAllBytes(dir.resolve(path));
                } else Files.write(dir.resolve(path),b);
                identities.put(name,hash(b)); bound.add(m("id",name,"path",path,"sha256",hash(b)));
            }
            Map<String,Object> scope=new TreeMap<>(); for (String name:G5Checker.SCOPE) scope.put(name,refId());
            scope.put("operating_policy",privateValues.get("policy_ref"));
            dossier=m("schema",G5Checker.VERSION,"dossier_id",refId(),"identities",identities,"build_commit",COMMIT,"private_ref",privateValues.get("reference"),"private_sha256",hash(Files.readAllBytes(privateFile)),"roles",roles,"scope_refs",scope,"files",bound,"evidence",new ArrayList<>());
            for (var requirement:G5Checker.REQUIREMENTS.entrySet()) {
                addItem(requirement.getKey(),requirement.getValue(),"","");
                if (requirement.getKey().equals("G4_session")) addItem(requirement.getKey(),requirement.getValue(),"2026-11-07","PASS");
            }
            save();
            for(Object value:(List<?>)dossier.get("files")) retain(Files.readAllBytes(dir.resolve(object(value).get("path").toString())));
            event("DOSSIER_CREATED",ZERO,ZERO);
            for (var item:items.values()) event("EVIDENCE_ADDED",hash(bytes(item)),ZERO);
        }
        void addItem(String req, G5Checker.Requirement rule, String date, String preopen) throws Exception {
            String id=refId(),payload=id+".source.json";
            byte[] content=bytes(m("schema","SYNTHETIC_ONLY_NO_ACTUAL_EVIDENCE","id",id)); Files.write(dir.resolve(payload),content);
            @SuppressWarnings("unchecked") List<Object> bound=(List<Object>)dossier.get("files");
            bound.add(m("id","source"+serial,"path",payload,"sha256",hash(content)));
            if (req.equals("G4_session") && date.isEmpty()) { date="2026-11-06"; preopen="PASS"; }
            Map<String,Object> e=m("schema","INTRA_G5_EVIDENCE_V1","id",id,"requirement",req,"source_ref",refId(),"classification","SYNTHETIC","claim",rule.claim(),"at_utc",AT,"valid_until_utc",UNTIL,
                    "identities",new TreeMap<>(identities),"actor_role","reviewer","payload_path",payload,"payload_sha256",hash(content),"verifier",rule.verifier(),"arguments",new ArrayList<>(),"session_date",date,"preopen",preopen,"signature","");
            sign(e,"reviewer"); items.put(id,e);
        }
        void sign(Map<String,Object> e,String role) throws Exception {
            Map<String,Object> p=new TreeMap<>(e); p.remove("signature"); Signature sig=Signature.getInstance("Ed25519"); sig.initSign(keys.get(role).getPrivate()); sig.update(bytes(p)); e.put("signature",Base64.getEncoder().encodeToString(sig.sign()));
        }
        Map<String,Object> item(String req) { return items.values().stream().filter(e->e.get("requirement").equals(req)).findFirst().orElseThrow(); }
        void save() throws Exception {
            List<Object> refs=new ArrayList<>();
            for (var item:items.values()) {
                String path=item.get("id")+".json"; byte[] b=bytes(item); Files.write(dir.resolve(path),b);
                refs.add(m("id",item.get("id"),"path",path,"sha256",hash(b)));
            }
            dossier.put("evidence",refs); Files.write(dir.resolve("dossier.json"),bytes(dossier));
        }
        void event(String kind,String attachment,String correction) throws Exception {
            byte[] db=Files.readAllBytes(dir.resolve("dossier.json")); retain(db);
            if (!attachment.equals(ZERO)) {
                byte[] found=null;
                for (var item:items.values()) { byte[] b=bytes(item); if (hash(b).equals(attachment)) { found=b; break; } }
                if (found==null) for (String candidate:List.of("checked-report.bin","decisions/reviewer.json","decisions/owner.json")) {
                    Path p=dir.resolve(candidate); if (Files.exists(p)) { byte[] b=Files.readAllBytes(p); if (hash(b).equals(attachment)) { found=b; break; } }
                }
                if (found==null && Files.exists(dir.resolve("archive/"+attachment+".bin"))) found=Files.readAllBytes(dir.resolve("archive/"+attachment+".bin"));
                if (found==null) throw new AssertionError("missing attachment"); retain(found);
            }
            Map<String,Object> e=m("schema","INTRA_G5_EVENT_V1","sequence",++sequence,"event_id",refId(),"kind",kind,"previous",head,"dossier_sha256",hash(db),"build_sha256",identities.get("build"),"attachment_sha256",attachment,"supersedes",correction,"at_utc",AT,"actor",roles.get(kind.equals("REVIEW")?"reviewer":"owner").get("id"));
            // Fixture preparation is batch-local; append locking/publication is tested separately.
            byte[] eventBytes=bytes(e); Files.write(dir.resolve("history/"+String.format(Locale.ROOT,"%08d.json",sequence)),eventBytes,StandardOpenOption.CREATE_NEW); head=hash(eventBytes);
        }
        void retain(byte[] b) throws Exception { Path p=dir.resolve("archive/"+hash(b)+".bin"); if (!Files.exists(p)) Files.write(p,b); }
        G5Checker.Result check() throws Exception { return G5Checker.evaluate(dir,privateFile,NOW,head); }
        void decisions() throws Exception {
            Path folder=dir.resolve("decisions"); Files.createDirectories(folder);
            byte[] report=check().report(); retain(report); event("EVIDENCE_CHECKED",hash(report),ZERO);
            for (String role:List.of("reviewer","owner")) {
                Map<String,Object> actor=roles.get(role);
                Map<String,Object> d=m("schema","INTRA_G5_DECISION_V1","decision_id",refId(),"role",role,"actor",actor.get("id"),"decision","APPROVE_FUTURE_HUMAN_CONSIDERATION","dossier_sha256",hash(Files.readAllBytes(dir.resolve("dossier.json"))),"build_sha256",identities.get("build"),"at_utc",AT,"valid_until_utc",UNTIL,
                        "qualification_ref",actor.get("qualification_ref"),"independence_ref",actor.get("independence_ref"),"classification","SYNTHETIC","signature","");
                sign(d,role); Files.write(folder.resolve(role+".json"),bytes(d)); event(role.equals("owner")?"OWNER_DECISION":"REVIEW",hash(bytes(d)),ZERO);
            }
        }
    }
    static boolean code(G5Checker.Result r,String code) { return new String(r.report(),StandardCharsets.UTF_8).contains("\"code\":\""+code+"\""); }
    static void blocked(G5Checker.Result r,String name) { expect(r.status()!=G5Checker.Status.ELIGIBLE_FOR_HUMAN_DECISION,name); expect(!r.g5Closed()&&!r.orderEgressEnabled(),name+" egress"); }
    static void gates() throws Exception {
        for (String req:List.of("G0_charter","G1_gate","G2_gate","G3_gate","G4_gate")) {
            for (String mode:List.of("OPEN","MISSING","STALE","TAMPERED")) {
                Fixture f=new Fixture(); Map<String,Object> e=f.item(req);
                if (mode.equals("MISSING")) f.items.remove(e.get("id"));
                else if (mode.equals("OPEN")) e.put("claim","OPEN");
                else if (mode.equals("STALE")) e.put("valid_until_utc","2026-11-08T11:00:00Z");
                else Files.writeString(f.dir.resolve(e.get("payload_path").toString()),"tampered");
                if (!mode.equals("TAMPERED")) { f.sign(e,"reviewer"); f.save(); }
                G5Checker.Result result=f.check(); blocked(result,req+mode);
                if(mode.equals("STALE")) expect(result.state()==G5History.State.BLOCKED,"expired gate blocks state");
                expect(code(result,switch(mode) { case "OPEN"->"REQUIREMENT_NOT_SATISFIED"; case "MISSING"->"REQUIRED_EVIDENCE_MISSING"; case "STALE"->"STALE_EXPIRED_OR_FUTURE_EVIDENCE"; default->"SOURCE_BYTES_TAMPERED"; }),req+mode+" reason");
            }
        }
        for(String req:List.of("G0_data_rights","G0_authority","G5_broker_route","G5_hosting","G5_static_ip","G5_current_rule_review","G5_reviewer","G2_reconciliation","G4_drill")) {
            Fixture f=new Fixture(); f.items.remove(f.item(req).get("id")); f.save(); blocked(f.check(),req+" missing"); expect(code(f.check(),"REQUIRED_EVIDENCE_MISSING"),req+" reason");
        }
    }
    static void bindingsAndHumans() throws Exception {
        for(String identity:G5Checker.IDENTITIES) {
            Fixture f=new Fixture(); Map<String,Object> e=f.item("G2_gate"); object(e.get("identities")).put(identity,hash((identity+"different").getBytes(StandardCharsets.UTF_8))); f.sign(e,"reviewer"); f.save(); expect(code(f.check(),"EVIDENCE_IDENTITY_MISMATCH"),identity);
        }
        Fixture f=new Fixture(); expect(code(f.check(),"HUMAN_DECISION_MISSING"),"missing decisions");
        f.decisions(); G5Checker.Result before=f.check(); blocked(before,"synthetic approved"); expect(before.status()==G5Checker.Status.BLOCKED,"synthetic approved is BLOCKED");
        expect(before.state()==G5History.State.DRAFT,"local signatures do not advance effective state");
        expect(code(before,"SYNTHETIC_OR_AI_NOT_ACTUAL_EVIDENCE"),"synthetic never actual");
        byte[] original=Files.readAllBytes(f.dir.resolve("dossier.json")); Files.write(f.dir.resolve("dossier.json"),(new String(original,StandardCharsets.UTF_8)+" ").getBytes(StandardCharsets.UTF_8));
        G5Checker.Result changed=f.check(); expect(code(changed,"OUTDATED_DOSSIER_OR_BUILD_DECISION"),"edit invalidates decisions"); expect(changed.state()==G5History.State.BLOCKED,"edit blocks state");
        expect(new String(changed.report(),StandardCharsets.UTF_8).contains("\"owner_signature_binding_valid\":false"),"owner invalidated");
        expect(new String(changed.report(),StandardCharsets.UTF_8).contains("\"reviewer_signature_binding_valid\":false"),"reviewer invalidated");
        f=new Fixture(); f.roles.put("reviewer",f.roles.get("operator")); f.save(); expect(code(f.check(),"REVIEWER_NOT_INDEPENDENT"),"independence");
        f=new Fixture(); f.roles.put("reviewer",null); f.save(); expect(code(f.check(),"ROLE_MISSING"),"reviewer missing");
        f=new Fixture(); f.item("G0_charter").put("signature",Base64.getEncoder().encodeToString(new byte[64])); f.save(); expect(code(f.check(),"INVALID_SIGNATURE"),"invalid signature");
        for (String classification:List.of("CALLER_SUPPLIED_UNAUTHENTICATED","SIGNED_KEY_POSSESSION_ONLY","AI_GENERATED")) {
            f=new Fixture(); Map<String,Object> e=f.item("G4_drill"); e.put("classification",classification); f.sign(e,"reviewer"); f.save(); blocked(f.check(),classification+" not actual");
        }
        f=new Fixture(); f.item("G4_severe_incidents").put("claim","OPEN"); f.save(); expect(code(f.check(),"REQUIREMENT_NOT_SATISFIED"),"severe incident");
        f=new Fixture(); f.items.remove(f.item("G4_session").get("id")); f.save(); expect(code(f.check(),"INCOMPLETE_SESSION_COUNT"),"sessions");
        f=new Fixture(); f.item("G4_session").put("preopen","FAIL"); f.save(); expect(code(f.check(),"INCOMPLETE_PREOPEN_STREAK"),"streak");
        f=new Fixture(); f.decisions(); f.event("REVOCATION",ZERO,ZERO); expect(code(f.check(),"REVOCATION_LATCHED"),"revocation"); expect(f.check().state()==G5History.State.BLOCKED,"restart revoked state");
        f=new Fixture(); f.privateValues.put("pilot_end_utc","2026-11-08T11:00:00Z"); f.privateValues.put("pilot_start_utc","2026-11-08T10:00:00Z"); Files.write(f.privateFile,bytes(f.privateValues)); f.dossier.put("private_sha256",hash(Files.readAllBytes(f.privateFile))); f.save(); expect(code(f.check(),"PILOT_SCOPE_EXPIRED"),"scope expiry");
        f=new Fixture(); f.decisions(); Map<String,Object> decision=json(Files.readAllBytes(f.dir.resolve("decisions/reviewer.json"))); decision.put("qualification_ref",refId()); f.sign(decision,"reviewer"); Files.write(f.dir.resolve("decisions/reviewer.json"),bytes(decision)); expect(code(f.check(),"DECISION_ACTOR_OR_ROLE_MISMATCH"),"qualification mismatch");
    }
    static void historyAndIo() throws Exception {
        Fixture f=new Fixture(); byte[] report=f.check().report(); Path output=f.dir.resolveSibling("report-"+(++serial)+".json"); publish(report,output);
        G5Checker.verify(f.dir,f.privateFile,NOW,f.head,output,hash(report)); checks++;
        Map<String,Object> append=m("schema","INTRA_G5_EVENT_V1","sequence",f.sequence+1,"event_id",refId(),"kind","REVOCATION","previous",f.head,"dossier_sha256",hash(Files.readAllBytes(f.dir.resolve("dossier.json"))),"build_sha256",f.identities.get("build"),"attachment_sha256",ZERO,"supersedes",ZERO,"at_utc",AT,"actor",f.roles.get("owner").get("id"));
        f.head=G5History.append(f.dir.resolve("history"),f.head,append,NOW); expect(code(f.check(),"REVOCATION_LATCHED"),"production append");
        final Fixture exact=f; Files.writeString(output,"{}"); rejects(()->G5Checker.verify(exact.dir,exact.privateFile,NOW,exact.head,output,hash(report)),"report tamper");
        f=new Fixture(); final Fixture truncated=f; Files.delete(f.dir.resolve("history/"+String.format(Locale.ROOT,"%08d.json",f.sequence))); rejects(truncated::check,"truncation");
        f=new Fixture(); final Fixture reorder=f; byte[] first=Files.readAllBytes(f.dir.resolve("history/00000001.json")),second=Files.readAllBytes(f.dir.resolve("history/00000002.json")); Files.write(f.dir.resolve("history/00000001.json"),second); Files.write(f.dir.resolve("history/00000002.json"),first); rejects(reorder::check,"reorder");
        f=new Fixture(); final Fixture duplicate=f; Files.write(f.dir.resolve("history/"+String.format(Locale.ROOT,"%08d.json",f.sequence+1)),Files.readAllBytes(f.dir.resolve("history/00000001.json"))); rejects(duplicate::check,"duplicate history");
        f=new Fixture(); final Fixture correction=f; correction.event("SUPERSEDED",ZERO,"f".repeat(64)); rejects(correction::check,"invalid correction");
        f=new Fixture(); String firstHash=hash(Files.readAllBytes(f.dir.resolve("history/00000001.json"))); f.event("SUPERSEDED",ZERO,firstHash); blocked(f.check(),"valid correction retained");
        expect(Files.exists(f.dir.resolve("history/00000001.json")),"old event retained");
        f=new Fixture(); final Fixture traversal=f; Map<String,Object> e=f.item("G0_charter"); e.put("payload_path","../private.json"); f.save(); rejects(traversal::check,"path traversal");
        for(byte[] bad:List.of(new byte[]{(byte)0xc3,(byte)0x28},"{\"schema\":1,\"schema\":2}".getBytes(StandardCharsets.UTF_8),"{} trailing".getBytes(StandardCharsets.UTF_8),"{\"x\":\"\\ud800\"}".getBytes(StandardCharsets.UTF_8))) {
            f=new Fixture(); final Fixture malformed=f; Files.write(f.dir.resolve("dossier.json"),bad); rejects(malformed::check,"malformed UTF8/JSON");
        }
        Path target=root.resolve("atomic-fail.json"); rejects(()->publish(report,target,(a,b)->{throw new IOException("injected publication failure");}),"atomic publication failure"); expect(!Files.exists(target),"no partial report");
        publish(report,target); rejects(()->publish(new byte[0],target),"refuse overwrite"); expect(Arrays.equals(Files.readAllBytes(target),report),"original retained");
        Path race=root.resolve("race.json"); rejects(()->publish(report,race,(a,b)->{Files.writeString(b,"racing writer");Files.createLink(b,a);}),"racing publication"); expect(Files.readString(race).equals("racing writer"),"racing destination retained");
        // Windows symlink privileges are host-dependent. The runner additionally tests junctions.
        Path link=root.resolve("escape-link");
        try { Files.createSymbolicLink(link,root.getParent()); rejects(()->snapshot(link),"symlink ancestry escape"); }
        catch (FileSystemException | UnsupportedOperationException denied) { System.out.println("SYMLINK_CREATION_UNAVAILABLE_USE_RUNNER_JUNCTION_CHECK"); }
        for(boolean changed:List.of(false,true)) for(boolean checked:List.of(false,true)) for(boolean reviewed:List.of(false,true)) for(boolean owner:List.of(false,true)) {
            G5History.State state=G5History.state(checked,reviewed,owner,changed);
            expect(changed?state==G5History.State.BLOCKED:!checked?state==G5History.State.DRAFT:!reviewed?state==G5History.State.EVIDENCE_CHECKED:owner?state==G5History.State.OWNER_DECISION_RECORDED:state==G5History.State.INDEPENDENTLY_REVIEWED,"state machine");
        }
    }
    static void determinism() throws Exception {
        Fixture f=new Fixture(); G5Checker.Result initial=f.check(); expect(initial.status()==G5Checker.Status.BLOCKED,"determinism baseline must be blocked, not malformed"); byte[] expected=initial.report(); Locale previous=Locale.getDefault(); TimeZone zone=TimeZone.getDefault();
        try {
            for(Locale locale:List.of(Locale.US,Locale.forLanguageTag("tr-TR"),Locale.JAPAN)) for(String timezone:List.of("UTC","Pacific/Apia","Asia/Kolkata")) {
                Locale.setDefault(locale); TimeZone.setDefault(TimeZone.getTimeZone(timezone)); expect(Arrays.equals(expected,f.check().report()),"locale timezone deterministic");
            }
        } finally { Locale.setDefault(previous); TimeZone.setDefault(zone); }
        Path example=root.resolve("jvm-repeat"); Files.move(f.dir,example); Files.move(f.privateFile,root.resolve("jvm-private.json")); Files.writeString(root.resolve("jvm-head.txt"),f.head);
        expect(!new String(expected,StandardCharsets.UTF_8).contains("pilot_loss"),"private values omitted");
        expect(G5Cli.execute(new String[]{"--enable-live"})==3,"no live flag");
    }
    static void additionalAcceptance() throws Exception {
        G5VerifierPaths.validate(Map.of("signed.json",bytes(m("signature","/valid/base64==","public_key","/also/base64==")))); checks++;
        for(String role:List.of("reviewer","owner")) for(String mode:List.of("MISSING","EXPIRED","SIGNATURE","BUILD")) {
            Fixture f=new Fixture(); f.decisions(); Path p=f.dir.resolve("decisions/"+role+".json");
            if(mode.equals("MISSING")) Files.delete(p);
            else {
                Map<String,Object> d=json(Files.readAllBytes(p));
                if(mode.equals("EXPIRED")) d.put("valid_until_utc","2026-11-08T11:00:00Z");
                if(mode.equals("BUILD")) d.put("build_sha256","f".repeat(64));
                f.sign(d,role);
                if(mode.equals("SIGNATURE")) d.put("signature",Base64.getEncoder().encodeToString(new byte[64]));
                Files.write(p,bytes(d));
            }
            String expected=switch(mode) {case "MISSING"->"HUMAN_DECISION_MISSING";case "EXPIRED"->"EXPIRED_OR_FUTURE_DECISION";case "BUILD"->"OUTDATED_DOSSIER_OR_BUILD_DECISION";default->"INVALID_OR_MISSING_DECISION_SIGNATURE";};
            expect(code(f.check(),expected),role+mode);
        }
        Fixture f=new Fixture(); f.decisions(); Files.writeString(f.dir.resolve("implementation.bin"),"changed after review");
        String report=new String(f.check().report(),StandardCharsets.UTF_8);
        expect(report.contains("\"owner_signature_binding_valid\":false")&&report.contains("\"reviewer_signature_binding_valid\":false"),"attachment edit invalidates both decisions");
        f=new Fixture();
        Map<String,Object> event=m("schema","INTRA_G5_EVENT_V1","sequence",f.sequence+1,"event_id",refId(),"kind","REVOCATION","previous",f.head,"dossier_sha256",hash(Files.readAllBytes(f.dir.resolve("dossier.json"))),"build_sha256",f.identities.get("build"),"attachment_sha256",ZERO,"supersedes",ZERO,"at_utc",AT,"actor",f.roles.get("owner").get("id"));
        Path request=f.dir.resolveSibling("event-request.json"); Files.write(request,bytes(event));
        expect(G5Cli.execute(new String[]{"record",f.dir.toString(),request.toString(),f.head,NOW.toString(),"-"})==0,"record CLI archives and appends");
        f.head=hash(bytes(event)); expect(code(f.check(),"REVOCATION_LATCHED"),"recorded revocation effective after restart");
        f=new Fixture(); final Fixture unexpected=f; f.dossier.put("live_authorized",true); f.save(); rejects(unexpected::check,"caller boolean cannot bypass");
        f=new Fixture(); final Fixture actualClaim=f; f.item("G4_drill").put("classification","AUTHENTIC_ACTUAL"); f.save(); rejects(actualClaim::check,"invented authentic classification rejected");
        Path paths=Files.createDirectory(root.resolve("path-guard"));
        Files.writeString(paths.resolve("manifest.data"),"instrument_id,bars_csv,run_properties,max_abs_lots\nSYNTH,bars.data,config.data,1\n");
        Files.writeString(paths.resolve("bars.data"),"synthetic"); Files.writeString(paths.resolve("settings.data"),"setting=1\n");
        List<Path> args=List.of(paths.resolve("manifest.data"),paths.resolve("settings.data"),paths);
        for(String bad:List.of("reference.file=\\u002e\\u002e/outside\n","reference.file = /outside\n","reference.file=C\\:/outside\n","reference.file=../outside\n")) {
            Files.writeString(paths.resolve("config.data"),bad); rejects(()->G5VerifierPaths.beforeInvocation("PORTFOLIO_REPLAY",args,paths),"decoded nested path escape");
        }
        // A real existing verifier PASS still cannot close G0 or authenticate its synthetic reviewer.
        f=new Fixture(); Path g0=com.mscient.intrapaper.g0.G0CheckerTest.fixture(f.dir.resolve("g0"),false);
        @SuppressWarnings("unchecked") List<Object> bindings=(List<Object>)f.dossier.get("files");
        for(var e:snapshot(g0).entrySet()) {
            bindings.add(m("id","g0source"+(++serial),"path","g0/"+e.getKey(),"sha256",hash(e.getValue()))); f.retain(e.getValue());
        }
        Map<String,Object> charter=f.item("G0_charter"); charter.put("arguments",List.of("g0")); f.sign(charter,"reviewer"); f.save();
        G5Checker.Result g0Result=f.check(); expect(code(g0Result,"EXISTING_VERIFIER_PASSED_DIAGNOSTIC_ONLY"),"G0 existing verifier invoked successfully"); blocked(g0Result,"G0 verifier never closes gate");
    }
    public static void main(String[] args) throws Exception {
        root=Path.of(args[0]).toAbsolutePath(); Files.createDirectories(root);
        gates(); bindingsAndHumans(); historyAndIo(); additionalAcceptance(); determinism();
        System.out.println("G5_ACCEPTANCE_CHECKS="+checks+" ALL_SYNTHETIC G5_CLOSED=false ORDER_EGRESS=false");
    }
}
