package com.mscient.intrapaper.ingestion;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.mscient.intrapaper.ingestion.IngestionIO.*;

/** Synthetic-only acceptance. Tests the application and the actual production classpath CLI process. */
public final class HistoricalIngestionTest {
    private static int checks, sequence;
    private static Path example, root; private static String production;
    private static final Map<String,String> NAMES=Map.of("request","request.json","policy","policy.json","raw","candles.json","mapping","mapping.csv","master","master.csv","calendar","calendar.csv");
    public static void main(String[] args) throws Exception {
        example=Path.of(args[0]); root=Path.of(args[1]); production=args[2]; Files.createDirectories(root);
        validAndReproduce(); parserCases(); requestCases(); qualityCases(); permissions(); publication(); cli();
        System.out.println("Historical ingestion acceptance checks passed: "+checks);
    }
    private static Map<String,Path> inputs() { Map<String,Path> m=new TreeMap<>(); NAMES.forEach((k,v)->m.put(k,example.resolve(v))); return m; }
    private static Path file(String s) throws IOException { Path p=root.resolve("input-"+(sequence++)+".txt"); Files.writeString(p,s,StandardCharsets.UTF_8); return p; }
    private static String original(String name) throws IOException { return Files.readString(example.resolve(NAMES.get(name))); }
    private static Map<String,Path> changed(String name,String text) throws IOException { var p=inputs(); p.put(name,file(text)); return p; }
    private static String modify(String name,String field,Object value) throws IOException { var m=map(parse(original(name).getBytes(StandardCharsets.UTF_8))); m.put(field,value); return json(m); }
    private static HistoricalIngestion.Result compute(Map<String,Path> p) throws IOException { return HistoricalIngestion.compute("ingest",p,List.of()); }
    private static void check(boolean b,String name) { if(!b) throw new AssertionError(name); checks++; }
    private static void rejected(String name,String text) throws Exception {
        var result=compute(changed(name,text)); check(result.status().equals("REJECTED"),"reject "+name+" case "+sequence);
        check(!result.files().containsKey("normalized.csv"),"no normalized invalid input");
    }
    private static void validAndReproduce() throws Exception {
        var a=compute(inputs()); check(a.status().equals("PASS_SYNTHETIC"),"valid status: "+new String(a.files().get("validation.json"),StandardCharsets.UTF_8));
        check(a.files().containsKey("raw-"+hash(Files.readAllBytes(inputs().get("raw")))+".json"),"raw hash address");
        String csv=new String(a.files().get("normalized.csv"),StandardCharsets.UTF_8);
        check(csv.contains("2026-01-02T03:45:00Z"),"explicit offset to UTC");
        check(csv.contains("\"102\",\"\",\"\",\"SYNTHETIC_CANDLES\""),"unknown volume and OI retained blank");
        check(csv.contains("\"103\",\"0\",\"\",\"SYNTHETIC_CANDLES\""),"zero volume distinct from null");
        Path bars=root.resolve("canonical.csv"); Files.write(bars,a.files().get("normalized.csv"));
        var config=com.mscient.intrapaper.provenance.RunConfig.load(example.getParent().resolve("synthetic-run.properties"));
        var loaded=com.mscient.intrapaper.data.BarsCsvReader.read(bars,config);
        check(loaded.size()==3 && loaded.get(1).volume()==null && loaded.get(2).volume()==0L,"existing canonical reader compatibility");
        var b=compute(inputs()); for(String name:a.files().keySet()) check(Arrays.equals(a.files().get(name),b.files().get(name)),"repeat "+name);
        Locale old=Locale.getDefault(); TimeZone oldZone=TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY); TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            b=compute(inputs()); for(String name:a.files().keySet()) check(Arrays.equals(a.files().get(name),b.files().get(name)),"locale "+name);
        } finally { Locale.setDefault(old); TimeZone.setDefault(oldZone); }
        var p=HistoricalIngestion.compute("plan",inputs(),List.of()); check(p.status().equals("PASS_PLANNED"),"planner");
        var plan=map(parse(p.files().get("plan.json"))); var windows=list(plan.get("windows"));
        check(windows.size()==2,"split windows");
        check(map(windows.get(0)).get("end_utc").equals("2026-01-02T03:47:00Z"),"exact window end");
        check(map(windows.get(1)).get("start_utc").equals(map(windows.get(0)).get("end_utc")),"no gap overlap");
        check(map(windows.get(1)).get("end_utc").equals("2026-01-02T03:48:00Z"),"final partial window");
        check(plan.get("policy_sha256").equals(hash(Files.readAllBytes(inputs().get("policy")))),"policy bound");
        Files.write(root.resolve("reference-manifest.json"),a.files().get("manifest.json"));
        System.out.println("REFERENCE_MANIFEST_SHA256="+hash(a.files().get("manifest.json")));
        System.out.println("NORMALIZED_SHA256="+hash(a.files().get("normalized.csv")));
    }
    private static String payload(String rows) { return "{\"status\":true,\"message\":\"SUCCESS\",\"errorcode\":\"\",\"data\":"+rows+"}"; }
    private static String row(String time,String o,String h,String l,String c,String volume) { return "[\""+time+"\","+o+","+h+","+l+","+c+","+volume+"]"; }
    private static void parserCases() throws Exception {
        String time="2026-01-02T09:15:00+05:30";
        for(String input:List.of("{}","[]","null","{","{\"data\":[],\"data\":[]}",payload("null"),payload("[[]]"),
                payload("[[\"2026-01-02T09:15:00\",100,102,99,101,1]]"),payload("[[\"2026-02-30T09:15:00+05:30\",100,102,99,101,1]]"),
                payload("[[100,100,102,99,101,1]]"),payload("[[\""+time+"\",100,102,99,101,1,2]]"),payload("[[\""+time+"\",100,102,99,101]]"),
                payload("["+row(time,"NaN","102","99","101","1")+"]"),payload("["+row(time,"Infinity","102","99","101","1")+"]"),
                payload("["+row(time,"\"100\"","102","99","101","1")+"]"),payload("["+row(time,"1e9999999","102","99","101","1")+"]"),
                payload("["+row(time,"0","102","99","101","1")+"]"),payload("["+row(time,"-1","102","99","101","1")+"]"),
                payload("["+row(time,"100","99","98","101","1")+"]"),payload("["+row(time,"100","102","101","101","1")+"]"),
                payload("["+row(time,"100","102","99","101","-1")+"]"),payload("["+row(time,"100","102","99","101","1.5")+"]"),
                payload("["+row(time,"100","102","99","101","9223372036854775808")+"]"),
                payload("["+row("2026-01-02T03:48:00Z","100","102","99","101","1")+"]"),
                payload("["+row("2026-01-02T03:44:00Z","100","102","99","101","1")+"]"),
                original("raw").replace("true","false"),original("raw")+" trailing","\ufeff"+original("raw"))) rejected("raw",input);
        for(String marker:List.of("Authorization","jwtToken","feed_token","apiKey","pin","totpSecret","Cookie")) {
            String raw=original("raw").replace("\"status\": true","\""+marker+"\":\"DO_NOT_LEAK_123\",\"status\":true");
            var r=compute(changed("raw",raw)); check(r.status().equals("REJECTED"),"secret rejection");
            for(byte[] value:r.files().values()) check(!utf8(value).contains("DO_NOT_LEAK_123"),"secret absent");
            check(r.files().keySet().stream().noneMatch(n->n.startsWith("raw-")),"no secret raw retained");
        }
        var invalid=inputs(); Path bad=root.resolve("bad-utf8.json"); Files.write(bad,new byte[]{(byte)0xc3,0x28}); invalid.put("raw",bad);
        check(compute(invalid).status().equals("REJECTED"),"malformed UTF8");
        var utc=compute(changed("raw",original("raw").replace("2026-01-02T09:15:00+05:30","2026-01-02T03:45:00Z")));
        check(utc.status().equals("PASS_SYNTHETIC"),"Z accepted");
        rejected("raw",original("raw").replace("100,","100.01,"));
    }
    private static void requestCases() throws Exception {
        var schema=map(parse(Files.readAllBytes(example.getParent().getParent().resolve("schemas/historical-ingestion/request.schema.json"))));
        var originalRequest=map(parse(original("request").getBytes(StandardCharsets.UTF_8)));
        check(new HashSet<>(list(schema.get("required"))).equals(originalRequest.keySet()),"schema fields match fixture");
        for(String field:originalRequest.keySet()) {
            var omitted=new TreeMap<>(originalRequest); omitted.remove(field); rejected("request",json(omitted));
        }
        for(var pair:Map.of("interval","THREE_MINUTE","exchange","NSE","source_token","1001,1002","from","2026-01-03T03:45:00Z",
                "to","2026-01-02T03:48:30Z","timestamp_role","UNKNOWN","storage","CLOUD","source","ANGEL_ONE_APPROVED").entrySet())
            rejected("request",modify("request",pair.getKey(),pair.getValue()));
        rejected("request",modify("request","retention_status","PROVIDER_APPROVED"));
        rejected("request",modify("request","retention_days",BigDecimal.ZERO));
        rejected("request",modify("request","unexpected","value"));
        rejected("request",modify("request","from","2026-01-02T09:15:00"));
        rejected("policy",modify("policy","min_delay_ms",BigDecimal.ZERO));
        rejected("policy",modify("policy","review_by","2025-12-31"));
        rejected("policy",modify("policy","max_concurrency",new BigDecimal(2)));
        rejected("policy",modify("policy","limits",List.of()));
        rejected("policy",modify("policy","boundary","inclusive"));
        var missing=inputs(); missing.remove("mapping"); check(HistoricalIngestion.compute("plan",missing,List.of()).status().equals("INCOMPLETE"),"plan mapping missing");
        var identity=changed("mapping",original("mapping").replace("SYNTH_FUT_20260129","DIFFERENT"));
        check(HistoricalIngestion.compute("plan",identity,List.of()).status().equals("REJECTED"),"planner token identity conflict");
    }
    private static void qualityCases() throws Exception {
        for(String name:List.of("mapping","master","calendar")) {
            var p=inputs(); p.remove(name); check(compute(p).status().equals("INCOMPLETE"),name+" missing");
            p.put(name,root.resolve("not-present")); check(compute(p).status().equals("INCOMPLETE"),name+" unavailable");
        }
        check(compute(changed("calendar",original("calendar").replace("2026-01-02","2026-01-01"))).status().equals("INCOMPLETE"),"calendar stale");
        check(compute(changed("master",original("master").replace("2026-01-01","2026-01-03"))).status().equals("INCOMPLETE"),"master future");
        check(compute(changed("request",modify("request","master_effective_from","2026-01-03"))).status().equals("INCOMPLETE"),"snapshot future");
        check(compute(changed("mapping",original("mapping").replace("2026-01-01T00:00:00Z","2026-01-03T00:00:00Z"))).status().equals("INCOMPLETE"),"mapping future");
        String raw=original("raw"); var envelope=map(parse(raw.getBytes(StandardCharsets.UTF_8))); var rows=new ArrayList<Object>(list(envelope.get("data")));
        var dup=new ArrayList<Object>(rows); dup.add(rows.getFirst()); envelope.put("data",dup);
        var a=compute(changed("raw",json(envelope))); check(a.status().equals("REJECTED"),"exact duplicate"); check(utf8(a.files().get("validation.json")).contains("EXACT_DUPLICATE"),"exact diagnosis");
        var conflict=new ArrayList<Object>(list(rows.getFirst())); conflict.set(5,new BigDecimal(999)); dup.set(3,conflict); envelope.put("data",dup);
        a=compute(changed("raw",json(envelope))); check(a.status().equals("REJECTED"),"conflicting duplicate"); check(utf8(a.files().get("validation.json")).contains("CONFLICTING_DUPLICATE"),"conflict diagnosis");
        Collections.reverse(rows); envelope.put("data",rows); a=compute(changed("raw",json(envelope))); check(utf8(a.files().get("validation.json")).contains("ROWS_OUT_OF_ORDER"),"ordering");
        envelope.put("data",List.of(rows.getLast())); a=compute(changed("raw",json(envelope))); check(a.status().equals("INCOMPLETE"),"missing coverage");
        check(utf8(a.files().get("validation.json")).contains("2026-01-02T03:46:00Z"),"exact missing timestamp");
        envelope.put("data",List.of()); check(compute(changed("raw",json(envelope))).status().equals("INCOMPLETE"),"empty response");
        a=compute(changed("calendar",original("calendar").replace("03:47:00Z","03:46:00Z"))); check(utf8(a.files().get("validation.json")).contains("SESSION_BOUNDARY_VIOLATION"),"session boundary");
        a=compute(changed("raw",raw.replace("09:16:00+05:30","09:16:30+05:30"))); check(utf8(a.files().get("validation.json")).contains("UNEXPECTED_TIMESTAMP"),"off-grid timestamp");
        String mapping=original("mapping"); check(compute(changed("mapping",mapping+mapping.lines().skip(1).findFirst().orElseThrow()+"\n")).status().equals("INCOMPLETE"),"ambiguous mapping fail closed");
    }
    private static void permissions() throws Exception {
        var r=map(parse(original("request").getBytes(StandardCharsets.UTF_8))); r.put("source","ANGEL_ONE"); r.put("retention_status","OWNER_PROPOSED_UNVERIFIED"); r.put("calendar_basis","CALLER_SUPPLIED_UNVERIFIED");
        var p=changed("request",json(r)); p.put("raw",root.resolve("must-not-be-opened"));
        var result=compute(p); check(result.status().equals("PERMISSION_DENIED"),"provider denied");
        check(!utf8(result.files().get("manifest.json")).contains("raw_sha256"),"raw not read");
        check(utf8(result.files().get("permission.json")).contains("\"permission_approved\":false"),"no approval");
        for(String doc:HistoricalPermissionGate.DOCUMENTS) p.put(doc,file("{}"));
        check(compute(p).status().equals("PERMISSION_DENIED"),"malformed evidence denied");
        var synthetic=inputs(); synthetic.put("permission-evidence",file("{}")); check(compute(synthetic).status().equals("REJECTED"),"synthetic identity separated");
        Path preflight=example.getParent().resolve("dataset-permission-preflight");
        for(var e:Map.of("preflight-manifest","manifest.json","intended-use","intended-use.json","permission-evidence","permission-evidence.json").entrySet()) p.put(e.getKey(),preflight.resolve(e.getValue()));
        check(compute(p).status().equals("PERMISSION_DENIED"),"expired wrong-source synthetic grants cannot admit provider");
    }
    private static void publication() throws Exception {
        var result=compute(inputs()); Path output=root.resolve("published-v1"); HistoricalArtifacts.publish(output,result.files());
        HistoricalArtifacts.verify(output,result.files()); check(true,"verify original"); HistoricalArtifacts.publish(output,result.files()); check(true,"idempotent publish");
        Path partial=root.resolve("must-not-publish");
        try { HistoricalArtifacts.publish(partial,result.files(),s->{throw new IOException("injected");}); throw new AssertionError("atomic failure"); }
        catch(IOException expected) { check(!Files.exists(partial),"failed run not complete"); }
        try(var entries=Files.list(root)) { check(entries.noneMatch(p->p.getFileName().toString().startsWith(".historical-stage-")),"stage cleaned"); }
        Files.writeString(output.resolve("normalized.csv"),"tampered");
        try { HistoricalArtifacts.verify(output,result.files()); throw new AssertionError("tamper"); } catch(Invalid expected) { checks++; }
        try { HistoricalArtifacts.publish(output,result.files()); throw new AssertionError("overwrite"); } catch(Invalid expected) { checks++; }
        check(Files.readString(output.resolve("normalized.csv")).equals("tampered"),"existing preserved");
        Map<String,byte[]> forged=new TreeMap<>(result.files()); var manifest=map(parse(forged.get("manifest.json")));
        forged.put("normalized.csv","forged\n".getBytes(StandardCharsets.UTF_8)); map(manifest.get("files")).put("normalized.csv",hash(forged.get("normalized.csv")));
        forged.put("manifest.json",bytes(manifest)); forged.put("complete.json",bytes(obj("schema_version","historical-bundle/v1","manifest_sha256",hash(forged.get("manifest.json")))));
        Path forgery=root.resolve("forged"); HistoricalArtifacts.publish(forgery,forged);
        try { HistoricalArtifacts.verify(forgery,result.files()); throw new AssertionError("forged coherent hashes"); } catch(Invalid expected) { checks++; }
        Path extra=root.resolve("extra"); HistoricalArtifacts.publish(extra,result.files()); Files.writeString(extra.resolve("extra.txt"),"unexpected");
        try { HistoricalArtifacts.verify(extra,result.files()); throw new AssertionError("extra"); } catch(Invalid expected) { checks++; }
    }
    private static List<String> arguments(String op,Path output) {
        List<String> a=new ArrayList<>(List.of(op)); inputs().forEach((k,v)->{a.add("--"+k);a.add(v.toString());}); a.add("--out");a.add(output.toString());return a;
    }
    private static int process(List<String> arguments) throws Exception {
        List<String> cmd=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",production,HistoricalIngestionCli.class.getName())); cmd.addAll(arguments);
        Process p=new ProcessBuilder(cmd).redirectErrorStream(true).start(); String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        int exit=p.waitFor(); Files.writeString(root.resolve("cli-"+(sequence++)+".log"),output); return exit;
    }
    private static void cli() throws Exception {
        Path output=root.resolve("cli-bundle"); var args=arguments("ingest",output); check(process(args)==0,"production CLI ingest");
        args.set(0,"verify"); args.addAll(List.of("--operation","ingest")); check(process(args)==0,"production CLI verify");
        Files.writeString(output.resolve("normalized.csv"),"tamper"); check(process(args)==4,"production CLI tamper exit");
        check(process(List.of("ingest","--skip","true"))==64,"no skip flag");
        check(process(List.of("plan","--request","a","--request","b"))==64,"duplicate args");
        check(process(List.of("plan","--request","https://example.invalid/data","--policy","x","--out","x"))==74,"network path rejected");
        check(process(List.of("ingest"))==64,"usage exit");
        var p=inputs(); p.remove("calendar"); Path incomplete=root.resolve("cli-incomplete"); List<String> inc=new ArrayList<>(List.of("ingest"));
        p.forEach((k,v)->{inc.add("--"+k);inc.add(v.toString());}); inc.addAll(List.of("--out",incomplete.toString()));
        check(process(inc)==3,"incomplete CLI exit");
        inc.set(0,"verify"); inc.addAll(List.of("--operation","ingest")); check(process(inc)==3,"verified incomplete stays blocked");
        Path repeat=root.resolve("cli-repeat"); check(process(arguments("ingest",repeat))==0,"independent JVM reproduction");
        var expected=compute(inputs()); HistoricalArtifacts.verify(repeat,expected.files()); check(true,"same bytes process and in-process");
        var changed=changed("mapping",original("mapping").replace("synthetic-only","synthetic-revised"));
        try { HistoricalArtifacts.verify(repeat,compute(changed).files()); throw new AssertionError("changed reference"); } catch(Invalid mismatch) { checks++; }
        Path denied=root.resolve("cli-denied"); var provider=map(parse(original("request").getBytes(StandardCharsets.UTF_8)));
        provider.put("source","ANGEL_ONE"); provider.put("retention_status","OWNER_PROPOSED_UNVERIFIED"); provider.put("calendar_basis","CALLER_SUPPLIED_UNVERIFIED");
        var denial=List.of("ingest","--request",file(json(provider)).toString(),"--policy",inputs().get("policy").toString(),"--raw",root.resolve("never-open").toString(),"--out",denied.toString());
        check(process(denial)==2,"provider CLI denial before raw access");
    }
}
