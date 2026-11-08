package com.mscient.intrapaper.g0;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Offline adversarial suite, using wholly invented values and source bytes. */
public final class G0CheckerTest {
    private static int checks;
    private static final String DAY = "2026-11-08", UNTIL = "2026-12-08";
    private static Path work;
    private static void check(boolean ok, String name) { checks++; if (!ok) throw new AssertionError(name); }
    private static void write(Path p, String content) throws Exception { Files.writeString(p, content, StandardCharsets.UTF_8); }
    private static String read(Path p) throws Exception { return Files.readString(p, StandardCharsets.UTF_8); }
    private static String kv(String name, Map<String,String> values) {
        StringBuilder s = new StringBuilder("g0-" + name + "-v1\nkey\tvalue\n");
        values.forEach((k,v) -> s.append(k).append('\t').append(v).append('\n')); return s.toString();
    }
    public static Path fixture(Path path, boolean pending) throws Exception {
        Files.createDirectories(path.resolve("evidence"));
        var c = new LinkedHashMap<String,String>();
        for (String key : G0Schema.CHARTER) c.put(key, "");
        c.put("charter_id", "synthetic-g0"); c.put("classification", "SYNTHETIC"); c.put("assessment_date", DAY);
        c.put("boundary", "OFFLINE_RESEARCH_PAPER"); c.put("order_route", "ABSENT"); c.put("account_access", "DISABLED");
        c.put("network_access", "DISABLED"); c.put("spending_authorized", "NO"); c.put("live_authorized", "NO");
        c.put("data_uses", "use1"); c.put("ruleset_id", "synthetic-rules"); c.put("limitations_acknowledged", "YES");
        write(path.resolve("charter.tsv"), kv("charter", c));
        var vals = new HashMap<String,String>();
        vals.put("operator", "SyntheticOperator"); vals.put("human_reviewer", "SyntheticReviewer");
        vals.put("stop_authority", "SyntheticOperator"); vals.put("resume_authority", "SyntheticOperator");
        vals.put("incident_contact", "SyntheticContact"); vals.put("escalation_authority", "SyntheticOperator");
        for (String id : List.of("own_capital_only", "limits_are_actual", "independent_review_completed", "no_order_acknowledgement")) vals.put(id,"YES");
        vals.put("risk_capital_is_loss_ceiling", "NO"); vals.put("risk_capital_value", "1000");
        vals.put("annual_spend_ceiling", "10"); vals.put("live_loss_ceiling", "1"); vals.put("currency", "INR");
        vals.put("budget_period", "2026-11-08/2027-11-08"); vals.put("order_activity_scope", "NONE");
        vals.put("instruments_in", "SYNTH"); vals.put("data_sources", "SYNTH_SOURCE"); vals.put("data_purposes", "RESEARCH");
        vals.put("data_storage", "PRIVATE_LOCAL"); vals.put("data_retention", "use1:30");
        vals.put("data_sharing", "NONE"); vals.put("data_publication", "NONE"); vals.put("data_deletion", "DELETE_AT_EXPIRY");
        StringBuilder o = new StringBuilder("g0-owners-v1\n" + G0Schema.OWNER_HEADER + "\n");
        for (String id : G0Schema.DECISIONS) o.append(id).append(pending ? "\tPENDING_OWNER_INPUT\t\tOwner has supplied no decision\tNONE\t\t\n" : "\tOWNER_ATTESTED\t" + vals.getOrDefault(id,"SyntheticDecision") + "\tSynthetic caller attestation only\tCALLER_ATTESTATION\t" + DAY + "\t" + UNTIL + "\n");
        write(path.resolve("owners.tsv"), o.toString());
        StringBuilder s = new StringBuilder("g0-sources-v1\n" + G0Schema.SOURCE_HEADER + "\n");
        int n=0;
        for(String rule : G0Schema.RULES) {
            String id="s"+(++n), bytes="Synthetic source for " + rule + "\n";
            write(path.resolve("evidence/" + id + ".txt"), bytes);
            s.append(id).append('\t').append(rule).append("\tsynthetic-rules\t").append(pending ? "UNRESOLVED\tUNRESOLVED\tUNRESOLVED" : "CURRENT_EVIDENCE\tPRIMARY_ACCEPTED_BY_REVIEWER\tAPPLICABLE")
                .append("\tSynthetic document\tSynthetic publisher\thttps://example.invalid/").append(id)
                .append("\t2026-10-01\t2026-10-01\t").append(DAY).append('\t').append(UNTIL).append("\tSynthetic section\tSynthetic requirement\t")
                .append(rule.equals("data_rights") ? "SYNTH_SOURCE" : "").append('\t').append(id).append(".txt\t").append(G0Checker.sha256(bytes.getBytes(StandardCharsets.UTF_8))).append("\tSynthetic evidence only\n");
        }
        write(path.resolve("sources.tsv"), s.toString());
        write(path.resolve("data.tsv"), "g0-data-v1\n" + G0Schema.DATA_HEADER + "\nuse1\tSYNTH_SOURCE\t" + (pending ? "UNRESOLVED" : "RECORDED") + "\tRESEARCH\tSYNTH\tPRIVATE_LOCAL\t30\tNONE\tNONE\tDELETE_AT_EXPIRY\tRESEARCH\tSYNTH\tPRIVATE_LOCAL\t30\tNONE\tNONE\tDELETE_AT_EXPIRY\ts14\tSynthetic rights scope only\n");
        pendingReview(path);
        if(!pending) approve(path);
        return path;
    }
    private static void pendingReview(Path p) throws Exception {
        var r = new LinkedHashMap<String,String>(); for(String key:G0Schema.REVIEW) r.put(key, "");
        r.put("decision", "PENDING"); r.put("evidence_kind", "NONE"); r.put("authentication", "UNVERIFIED"); r.put("reason", "No independent human review supplied");
        write(p.resolve("review.tsv"), kv("review",r));
    }
    private static void approve(Path p) throws Exception {
        pendingReview(p);
        String pack = G0Checker.evaluate(p).packSha256();
        var r = new LinkedHashMap<String,String>();
        r.put("decision", "APPROVE_EVIDENCE_PACK"); r.put("reviewer", "SyntheticReviewer"); r.put("qualified", "YES");
        r.put("qualification", "Synthetic qualification assertion for software tests"); r.put("independent", "YES");
        r.put("evidence_kind", "CALLER_ATTESTATION"); r.put("authentication", "UNVERIFIED"); r.put("reviewed", DAY);
        r.put("valid_until", UNTIL); r.put("pack_sha256", pack);
        String record = kv("review-record",r);
        write(p.resolve("evidence/review.txt"),record);
        r.put("evidence","review.txt"); r.put("sha256",G0Checker.sha256(record.getBytes(StandardCharsets.UTF_8))); r.put("reason","Synthetic review only; no signature authenticated");
        write(p.resolve("review.tsv"),kv("review",r));
    }
    private static Path fresh(String name) throws Exception { return fixture(work.resolve(name), false); }
    private static void syncReviewRecord(Path p) throws Exception {
        Map<String,String> r=new LinkedHashMap<>();
        for(String line:read(p.resolve("review.tsv")).lines().skip(2).toList()) {String[] cells=line.split("\t",-1);r.put(cells[0],cells[1]);}
        var record=new LinkedHashMap<String,String>();
        for(String field:List.of("decision","reviewer","qualified","qualification","independent","evidence_kind","authentication","reviewed","valid_until","pack_sha256")) record.put(field,r.get(field));
        String text=kv("review-record",record);write(p.resolve("evidence/review.txt"),text);
        cell(p,"review","sha256","value",G0Checker.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }
    private static void cell(Path p, String file, String id, String field, String replacement) throws Exception {
        String[] lines=read(p.resolve(file+".tsv")).split("\n",-1), header=lines[1].split("\t",-1);
        int col=Arrays.asList(header).indexOf(field); if(col<0) throw new AssertionError(field);
        for(int i=2;i<lines.length-1;i++) {
            String[] fields=lines[i].split("\t",-1);
            if(fields[0].equals(id)) { fields[col]=replacement; lines[i]=String.join("\t",fields); }
        }
        write(p.resolve(file+".tsv"),String.join("\n",lines));
    }
    private static void remove(Path p, String file, String id) throws Exception {
        write(p.resolve(file+".tsv"),read(p.resolve(file+".tsv")).lines().filter(line->!line.startsWith(id+"\t")).collect(java.util.stream.Collectors.joining("\n"))+"\n");
    }
    private static void status(Path p,G0Checker.Status expected,String name) throws Exception {
        check(G0Checker.evaluate(p).status()==expected,name);
    }
    private static void blockedRow(Path p, String row, String name) throws Exception {
        var result=G0Checker.evaluate(p);
        check(result.status()==G0Checker.Status.INCOMPLETE,name);
        check(new String(result.report(),StandardCharsets.UTF_8).contains("finding\t"+row+"\tINCOMPLETE\t"),"specific blocker "+row);
    }
    private static void rejected(Throwing run,String name) throws Exception {
        boolean caught=false; try{run.run();}catch(java.io.IOException|IllegalArgumentException e){caught=true;} check(caught,name);
    }
    @FunctionalInterface private interface Throwing {void run() throws Exception;}
    public static void main(String[] args) throws Exception {
        if(args.length==2 && args[0].equals("--example")) { fixture(Path.of(args[1]),true); System.out.println("SYNTHETIC_PENDING_EXAMPLE_WRITTEN"); return; }
        work=Path.of(args[0]); Files.createDirectories(work);
        Path good=fresh("good"); status(good,G0Checker.Status.PASS,"complete synthetic attested pack");
        var initial=G0Checker.evaluate(good);
        check(new String(initial.report(),StandardCharsets.UTF_8).contains("authorization\tNONE"),"never authorizes");
        check(!new String(initial.report(),StandardCharsets.UTF_8).contains("1000"),"redacted financial value");
        check(!new String(initial.report(),StandardCharsets.UTF_8).contains(good.toString()),"redacted canonical location");
        Path pending=fixture(work.resolve("pending"),true); status(pending,G0Checker.Status.INCOMPLETE,"all unknowns preserved");
        for (String field : G0Schema.CHARTER) if (!field.equals("assessment_date")) {
            Path unknownCharter=fresh("unknown-charter-"+field);cell(unknownCharter,"charter",field,"value","UNKNOWN");pendingReview(unknownCharter);
            blockedRow(unknownCharter,"charter."+field,"unknown charter "+field);
        }
        for(String id:G0Schema.DECISIONS) {
            Path p=fresh("missing-"+id); remove(p,"owners",id); pendingReview(p); blockedRow(p,"owner."+id,"missing owner "+id);
            p=fresh("pending-"+id); cell(p,"owners",id,"status","PENDING_OWNER_INPUT"); cell(p,"owners",id,"value",""); pendingReview(p); blockedRow(p,"owner."+id,"pending owner "+id);
            p=fresh("unresolved-"+id); cell(p,"owners",id,"status","UNRESOLVED"); cell(p,"owners",id,"value",""); pendingReview(p); blockedRow(p,"owner."+id,"unresolved owner "+id);
            p=fresh("unknown-"+id); cell(p,"owners",id,"value","UNKNOWN"); pendingReview(p); blockedRow(p,"owner."+id,"unknown owner "+id);
        }
        for(String field:List.of("purpose","instruments","storage","sharing","publication","deletion")) {
            Path p=fresh("rights-"+field); cell(p,"data","use1","allowed_"+field,"OTHER"); approve(p); status(p,G0Checker.Status.FAIL,"rights mismatch "+field);
        }
        Path p=fresh("retention"); cell(p,"data","use1","max_retention_days","29"); approve(p); status(p,G0Checker.Status.FAIL,"rights retention mismatch");
        p=fresh("rights-source"); cell(p,"data","use1","source","OTHER"); approve(p); status(p,G0Checker.Status.FAIL,"source mismatch");
        p=fresh("instruments-overlap");cell(p,"owners","instruments_out","value","SYNTH");approve(p);status(p,G0Checker.Status.FAIL,"instrument both included and excluded");
        for(String field:List.of("published","effective","reviewed")) {
            p=fresh("future-"+field); cell(p,"sources","s1",field,"2027-01-01"); cell(p,"sources","s1","valid_until","2027-02-01"); pendingReview(p);
            status(p,field.equals("published") ? G0Checker.Status.FAIL:G0Checker.Status.INCOMPLETE,"future source "+field);
        }
        p=fresh("expired"); cell(p,"sources","s1","reviewed","2026-10-02");cell(p,"sources","s1","valid_until","2026-11-07"); pendingReview(p); status(p,G0Checker.Status.INCOMPLETE,"expired source");
        p=fresh("unknown-date");cell(p,"sources","s1","effective","");pendingReview(p);status(p,G0Checker.Status.INCOMPLETE,"effective unknown");
        for (int i=1;i<=G0Schema.RULES.size();i++) { p=fresh("missing-source-"+i);remove(p,"sources","s"+i);pendingReview(p);status(p,G0Checker.Status.INCOMPLETE,"missing rule evidence "+i); }
        for (String field:G0Schema.DATA_HEADER.split("\t")) if (!List.of("id","status","reason").contains(field)) { p=fresh("missing-data-"+field);cell(p,"data","use1",field,"");pendingReview(p);status(p,G0Checker.Status.INCOMPLETE,"missing data dimension "+field); }
        p=fresh("missing-data-use");remove(p,"data","use1");pendingReview(p);status(p,G0Checker.Status.INCOMPLETE,"missing use register row");
        p=fresh("contradictory");cell(p,"sources","s1","status","CONTRADICTORY");approve(p);status(p,G0Checker.Status.FAIL,"source contradiction");
        p=fresh("ruleset");cell(p,"sources","s1","ruleset","other");approve(p);status(p,G0Checker.Status.FAIL,"frozen ruleset conflict");
        for(String value:List.of("1001","ILLUSTRATIVE","-1","NaN","1e3")) {
            p=fresh("limit-"+value.replace('-','X'));cell(p,"owners","live_loss_ceiling","value",value);approve(p);status(p,G0Checker.Status.FAIL,"invalid or conflicting limit "+value);
        }
        for(String id:List.of("risk_capital_is_loss_ceiling","limits_are_actual","order_activity_scope","own_capital_only","no_order_acknowledgement")) {
            p=fresh("constraint-"+id);cell(p,"owners",id,"value","WRONG");approve(p);status(p,G0Checker.Status.FAIL,"declared constraint "+id);
        }
        p=fresh("unapproved");cell(p,"owners","annual_spend_ceiling","status","PENDING_OWNER_INPUT");pendingReview(p);status(p,G0Checker.Status.FAIL,"unapproved placeholder");
        p=fresh("source-hash");cell(p,"sources","s1","sha256","0".repeat(64));pendingReview(p);status(p,G0Checker.Status.FAIL,"source hash changed");
        p=fresh("false-authentication");cell(p,"owners","A3","status","CONFIRMED_WITH_EVIDENCE");pendingReview(p);status(p,G0Checker.Status.INCOMPLETE,"assertion cannot authenticate itself");
        for(String field:List.of("order_route","account_access","network_access","live_authorized","spending_authorized")) {
            p=fresh("active-"+field);cell(p,"charter",field,"value","ACTIVE");approve(p);status(p,G0Checker.Status.FAIL,"active boundary "+field);
        }
        for(String field:G0Schema.REVIEW) {
            p=fresh("review-missing-"+field);cell(p,"review",field,"value","");
            status(p,G0Checker.Status.INCOMPLETE,"missing review "+field);
        }
        p=fresh("review-wrong");cell(p,"review","reviewer","value","SyntheticOperator");status(p,G0Checker.Status.FAIL,"nonindependent reviewer");
        p=fresh("review-unknown-qualification");cell(p,"review","qualification","value","UNKNOWN");syncReviewRecord(p);status(p,G0Checker.Status.INCOMPLETE,"unknown reviewer qualification");
        for(String field:List.of("qualified","independent")) {
            p=fresh("review-no-"+field);cell(p,"review",field,"value","NO");syncReviewRecord(p);status(p,G0Checker.Status.FAIL,"review constraint "+field);
            p=fresh("review-unknown-"+field);cell(p,"review",field,"value","UNKNOWN");syncReviewRecord(p);status(p,G0Checker.Status.INCOMPLETE,"review unknown "+field);
        }
        p=fresh("expired-review");cell(p,"review","reviewed","value","2026-11-01");cell(p,"review","valid_until","value","2026-11-07");syncReviewRecord(p);status(p,G0Checker.Status.INCOMPLETE,"expired review");
        p=fresh("future-review");cell(p,"review","reviewed","value","2026-11-09");syncReviewRecord(p);status(p,G0Checker.Status.FAIL,"future review");
        p=fresh("rejected-review");cell(p,"review","decision","value","REJECT");syncReviewRecord(p);status(p,G0Checker.Status.FAIL,"reviewer rejection");
        p=fresh("review-record-wrong");write(p.resolve("evidence/review.txt"),"Wrong review\n");cell(p,"review","sha256","value",G0Checker.sha256("Wrong review\n".getBytes(StandardCharsets.UTF_8)));status(p,G0Checker.Status.FAIL,"incorrect review contents");
        p=fresh("review-verified-claim");cell(p,"review","authentication","value","SIGNATURE_VERIFIED");Path ref=p;Path signatureRef=p;rejected(()->G0Checker.evaluate(signatureRef),"unconfigured verified signature rejected");
        for(String file:List.of("charter","owners","sources","data","review")) {
            p=fresh("duplicate-"+file);String text=read(p.resolve(file+".tsv"));String duplicate=text.lines().skip(2).findFirst().orElseThrow();write(p.resolve(file+".tsv"),text+duplicate+"\n");ref=p;Path duplicateRef=ref;rejected(()->G0Checker.evaluate(duplicateRef),"duplicate "+file);
        }
        p=fresh("unknown-key");write(p.resolve("charter.tsv"),read(p.resolve("charter.tsv"))+"unknown\tvalue\n");ref=p;Path unknown=ref;rejected(()->G0Checker.evaluate(unknown),"unknown key");
        p=fresh("bad-date");cell(p,"sources","s1","published","2026-02-30");ref=p;Path badDate=ref;rejected(()->G0Checker.evaluate(badDate),"invalid calendar date");
        p=fresh("ambiguous-set");cell(p,"data","use1","instruments","SYNTH;SYNTH");ref=p;Path duplicateSet=ref;rejected(()->G0Checker.evaluate(duplicateSet),"duplicate set");
        p=fresh("bad-url");cell(p,"sources","s1","url","https://user:secret@example.invalid/");ref=p;Path url=ref;rejected(()->G0Checker.evaluate(url),"credentials in URL");
        p=fresh("traversal");cell(p,"sources","s1","evidence","../secret");ref=p;Path traversal=ref;rejected(()->G0Checker.evaluate(traversal),"attachment traversal");
        for(byte[] invalid:List.of(new byte[]{(byte)0xc3,0x28},"\ufeffg0-charter-v1\nkey\tvalue\n".getBytes(StandardCharsets.UTF_8),"g0-charter-v1\r\nkey\tvalue\r\n".getBytes(StandardCharsets.UTF_8))) {
            p=fresh("encoding-"+checks);Files.write(p.resolve("charter.tsv"),invalid);ref=p;Path encoding=ref;rejected(()->G0Checker.evaluate(encoding),"malformed encoding");
        }
        Path output=work.resolve("published");G0Checker.publish(initial,output);G0Checker.verify(good,output.resolve("report.tsv"),initial.reportSha256());check(true,"exact verify");
        rejected(()->G0Checker.publish(initial,output),"refuse overwrite");
        check(Arrays.equals(initial.report(),G0Checker.evaluate(good).report()),"repeat deterministic");
        Locale oldLocale=Locale.getDefault();TimeZone oldZone=TimeZone.getDefault();
        try{Locale.setDefault(Locale.forLanguageTag("tr-TR"));TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Apia"));check(Arrays.equals(initial.report(),G0Checker.evaluate(good).report()),"locale timezone repeat");}
        finally{Locale.setDefault(oldLocale);TimeZone.setDefault(oldZone);}
        Path tampered=work.resolve("tampered.tsv");byte[] forged=new String(initial.report(),StandardCharsets.UTF_8).replace("implementation_sha256\t","implementation_sha256\t0").getBytes(StandardCharsets.UTF_8);Files.write(tampered,forged);
        rejected(()->G0Checker.verify(good,tampered,initial.reportSha256()),"retained hash tamper");
        rejected(()->G0Checker.verify(good,tampered,G0Checker.sha256(forged)),"implementation identity tamper with new hash");
        byte[] wrongBuild=new String(initial.report(),StandardCharsets.UTF_8).replace("build_identity\t","build_identity\tWRONG").getBytes(StandardCharsets.UTF_8);Files.write(tampered,wrongBuild);rejected(()->G0Checker.verify(good,tampered,G0Checker.sha256(wrongBuild)),"wrong build identity");
        write(good.resolve("evidence/s1.txt"),"Changed source\n");rejected(()->G0Checker.verify(good,output.resolve("report.tsv"),initial.reportSha256()),"tampered input verification");
        Path concurrent=work.resolve("concurrent");ExecutorService pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->{try{G0Checker.publish(initial,concurrent);return true;}catch(Exception e){return false;}});var b=pool.submit(()->{try{G0Checker.publish(initial,concurrent);return true;}catch(Exception e){return false;}});check(a.get()^b.get(),"single atomic publisher");}finally{pool.shutdown();}
        System.out.println("G0_CHECKS="+checks+" PASS (synthetic; caller attestations unverified)");
        System.out.println("G0_JVM_FIXTURE="+fixture(work.resolve("jvm-repeat"),false));
    }
}
