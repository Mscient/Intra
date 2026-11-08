package com.mscient.intrapaper.evaluation.hypothesis;

import com.mscient.intrapaper.book.ToyLimitOrderBook;
import com.mscient.intrapaper.data.StrictJsonParser;
import com.mscient.intrapaper.evaluation.ResearchTrialLedger;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TimeZone;

/** All review/freeze/ledger examples generated here are fictional software fixtures. */
public final class HypothesisCardTest {
    private static int checks;
    private static Path root;
    private static Path fixture;
    private static String original;
    private static int fileSequence;
    private interface Action { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        fixture = Path.of(args[0]); root = Files.createDirectories(Path.of(args[2]));
        original = Files.readString(fixture.resolve("g1-s01-v1.card.json"));
        for (String name : List.of("cost-model-v1.csv", "variant-ledger-v1.md", "source-register-v1.md")) {
            Files.copy(fixture.resolve(name), root.resolve(name));
        }
        check(HypothesisCard.schemaJson().equals(Files.readString(Path.of(args[1]))), "schema definitions match publication");
        for (String id : List.of("s01", "s02", "s03")) {
            HypothesisCard c = HypothesisCard.load(fixture.resolve("g1-" + id + "-v1.card.json"));
            check(c.version() == 1 && !c.status().equals("FROZEN_DECLARED"), "actual cards unapproved");
        }
        HypothesisCard proposed = HypothesisCard.load(file(original));
        String hash = proposed.sha256();
        check(hash.equals(HypothesisCard.sha256(original.getBytes(StandardCharsets.UTF_8))), "byte identity matches ledger convention");
        check(hash.equals(HypothesisCard.load(file(original)).sha256()), "repeat byte determinism");
        check(!hash.equals(HypothesisCard.load(file(original + " ")).sha256()), "whitespace edit changes exact-byte identity");
        Locale oldLocale = Locale.getDefault(); TimeZone oldZone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR")); TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            check(hash.equals(HypothesisCard.load(file(original)).sha256()), "locale/timezone independent hash");
        } finally { Locale.setDefault(oldLocale); TimeZone.setDefault(oldZone); }
        Map<String, Object> base = parse(original);
        for (String key : base.keySet()) {
            Map<String, Object> changed = parse(original); changed.remove(key);
            fails(() -> HypothesisCard.load(file(json(changed))), "missing root field " + key);
        }
        for (String key : List.of("hypothesis", "instruments", "measurement", "data", "execution", "economics", "design", "decisions", "reviews")) {
            for (String field : object(base.get(key)).keySet()) {
                Map<String, Object> changed = parse(original); object(changed.get(key)).remove(field);
                fails(() -> HypothesisCard.load(file(json(changed))), "missing section field " + key + "." + field);
            }
        }
        fails(() -> HypothesisCard.load(file(original.replaceFirst("\\{", "{\"card_id\":\"G1-S01\","))), "duplicate root field");
        fails(() -> HypothesisCard.load(file(original.replace("\"rights_state\":", "\"extra\":\"UNRESOLVED\",\"rights_state\":"))), "unknown nested field");
        fails(() -> HypothesisCard.load(file(original.replace("\"owner_selection\": {", "\"owner_selection\": {\"status\":\"PENDING_OWNER_INPUT\","))), "duplicate review field");
        mutateReject("schema", "INTRA_HYPOTHESIS_CARD_V2"); mutateReject("card_version", 0); mutateReject("card_version", 1.5);
        mutateReject("card_id", "../elsewhere"); mutateReject("card_id", " ");
        mutateReject("card_id", "unknown"); mutateReject("variant_ids", List.of("PENDING_OWNER_INPUT"));
        mutateReject("status", "APPROVED"); mutateReject("classification", "MARKET_VERIFIED");
        mutateReject("permission_approved", true); mutateReject("g1_closed", true);
        mutateReject("frozen_at_utc", "2026-11-09T00:00:00Z");
        mutateReject("recorded_at_utc", "2026-11-08T02:50:54+00:00");
        mutateReject("recorded_at_utc", "2026-13-08T02:50:54Z");
        mutateReject("recorded_at_utc", "2026-11-08T02:50:54.000Z");
        mutateReject("variant_ids", List.of("same", "same")); mutateReject("universe_candidate_ids", List.of());
        mutateReject("previous_card_sha256", "a".repeat(64));
        Map<String, Object> falseRights = parse(original); object(falseRights.get("data")).put("rights_state", "APPROVED");
        fails(() -> HypothesisCard.load(file(json(falseRights))), "false permission approval");
        Map<String, Object> falseGate = parse(original); object(falseGate.get("decisions")).put("gate_status", "COMPLETE");
        fails(() -> HypothesisCard.load(file(json(falseGate))), "false gate closure");
        for (String key : List.of("benchmark_id", "primary_metric_id", "variant_family_id", "universe_candidate_ids")) {
            Map<String, Object> m = parse(original); m.put(key, key.equals("universe_candidate_ids") ? List.of("C_FUT") : "CHANGED-V1");
            check(!hash.equals(HypothesisCard.load(file(json(m))).sha256()), "bound semantic identity " + key);
        }
        for (String key : List.of("cost_model", "source_register", "variant_ledger")) {
            Map<String, Object> m = parse(original); object(m.get(key)).put("sha256", "0".repeat(64));
            final Map<String, Object> zero = m;
            fails(() -> HypothesisCard.load(file(json(zero))), "zero attachment anchor");
            m = parse(original); object(m.get(key)).put("file", "../external");
            final Map<String, Object> escaped = m;
            fails(() -> HypothesisCard.load(file(json(escaped))), "attachment traversal");
        }
        Path malformed = root.resolve("bad-utf8.json"); Files.write(malformed, new byte[]{(byte)0xc3, (byte)0x28});
        fails(() -> HypothesisCard.load(malformed), "malformed UTF8");
        fails(() -> HypothesisCard.load(file(original + "{}")), "trailing JSON");
        fails(() -> HypothesisCard.load(file(original.replace("Initial proposed", "\\ud800 Initial proposed"))), "unpaired Unicode surrogate");
        fails(() -> HypothesisCard.load(file(" ".repeat(1048577))), "bounded input");
        // Explicit unresolved values survive; they do not synthesize amounts or evidence.
        check(original.contains("UNKNOWN") && original.contains("PENDING_G2_DATA") && original.contains("UNRESOLVED"), "unresolved retained");
        Path costs = root.resolve("cost-model-v1.csv"); byte[] costBytes = Files.readAllBytes(costs);
        Files.writeString(costs, "changed"); fails(() -> HypothesisCard.load(file(original)), "changed cost bytes"); Files.write(costs, costBytes);
        Path registry = Files.createDirectory(root.resolve("registry"));
        HypothesisRegistry.archive(registry, file(original));
        check(hash.equals(HypothesisRegistry.verify(registry, proposed.id(), 1).sha256()), "proposed snapshot identity");
        fails(() -> HypothesisRegistry.archive(registry, file(original)), "duplicate snapshot");
        Map<String, Object> frozen = freeze();
        String frozenText = json(frozen);
        HypothesisCard c = HypothesisCard.load(file(frozenText));
        HypothesisRegistry.archive(registry, file(frozenText));
        check(c.sha256().equals(HypothesisRegistry.verify(registry, c.id(), 1).sha256()), "fictional frozen snapshot");
        Path archived = registry.resolve(c.directoryName()).resolve("card.json"); byte[] archiveBytes = Files.readAllBytes(archived);
        Files.writeString(archived, frozenText.replace("FLAT-SESSION-SIGNAL-V1", "CHANGED"));
        fails(() -> HypothesisRegistry.verify(registry, c.id(), 1), "silent frozen edit"); Files.write(archived, archiveBytes);
        Map<String, Object> v2 = parse(frozenText); v2.put("card_version", 2); v2.put("previous_card_sha256", c.sha256()); v2.put("changelog", "SYNTHETIC correction: changed benchmark; old version retained"); v2.put("benchmark_id", "CHANGED");
        HypothesisCard next = HypothesisCard.load(file(json(v2))); HypothesisRegistry.archive(registry, file(json(v2)));
        check(!next.sha256().equals(c.sha256()) && c.sha256().equals(HypothesisRegistry.verify(registry,c.id(),1).sha256()), "new version preserves old identity");
        check(next.sha256().equals(HypothesisRegistry.verify(registry,c.id(),2).sha256()), "verify version chain");
        Map<String, Object> v3 = parse(json(v2)); v3.put("card_version", 3); v3.put("previous_card_sha256", c.sha256());
        fails(() -> HypothesisRegistry.archive(registry,file(json(v3))), "wrong predecessor");
        v3.put("previous_card_sha256", next.sha256()); v3.put("card_version", 4);
        fails(() -> HypothesisRegistry.archive(registry,file(json(v3))), "skipped version");
        for (String review : object(frozen.get("reviews")).keySet()) {
            Map<String, Object> m = parse(frozenText); object(object(m.get("reviews")).get(review)).put("status", "PENDING_OWNER_INPUT");
            fails(() -> HypothesisCard.load(file(json(m))), "frozen missing review " + review);
        }
        for (String value : List.of("APPROVED", "VERIFIED", "UNKNOWN")) {
            Map<String, Object> m = parse(frozenText); object(object(m.get("reviews")).get("independent_review")).put("status", value);
            fails(() -> HypothesisCard.load(file(json(m))), "false review status");
        }
        for (String field : List.of("record_id", "evidence_sha256", "at_utc")) {
            Map<String, Object> m = parse(frozenText); object(object(m.get("reviews")).get("independent_review")).put(field, null);
            fails(() -> HypothesisCard.load(file(json(m))), "declared missing review field");
        }
        Map<String, Object> placeholder = parse(frozenText); object(object(placeholder.get("reviews")).get("independent_review")).put("record_id", "unknown");
        fails(() -> HypothesisCard.load(file(json(placeholder))), "case-insensitive unresolved review identity");
        Map<String, Object> pending = parse(original); object(object(pending.get("reviews")).get("g0_review")).put("at_utc", "2026-11-08T03:00:00Z");
        fails(() -> HypothesisCard.load(file(json(pending))), "pending cannot invent timestamp");
        Map<String, Object> late = parse(frozenText); object(object(late.get("reviews")).get("g0_review")).put("at_utc", "2026-11-10T00:00:00Z");
        fails(() -> HypothesisCard.load(file(json(late))), "late approval");
        Map<String, Object> early = parse(frozenText); early.put("frozen_at_utc", "2026-11-01T00:00:00Z");
        fails(() -> HypothesisCard.load(file(json(early))), "freeze before recording");
        var proposedTrial = trial(proposed.sha256(), "S01", "wave-a-v1", "S01-OR15-H15");
        fails(() -> proposed.requireTrialBinding(proposedTrial), "proposed separate from trials");
        var frozenTrial = trial(c.sha256(), "S01", "wave-a-v1", "S01-OR15-H15");
        fails(() -> c.requireTrialBinding(frozenTrial), "synthetic frozen separate from trials");
        Map<String, Object> declared = parse(frozenText); declared.put("classification", "PLANNING_ONLY");
        HypothesisCard binding = HypothesisCard.load(file(json(declared))); // fictional declarations; no authentication claimed
        binding.requireTrialBinding(trial(binding.sha256(), "S01", "wave-a-v1", "S01-OR15-H15")); checks++;
        fails(() -> binding.requireTrialBinding(trial(c.sha256(),"S01","wave-a-v1","S01-OR15-H15")), "mismatched card");
        fails(() -> binding.requireTrialBinding(trial(binding.sha256(),"S02","wave-a-v1","S01-OR15-H15")), "mismatched strategy");
        fails(() -> binding.requireTrialBinding(trial(binding.sha256(),"S01","other","S01-OR15-H15")), "mismatched version");
        fails(() -> binding.requireTrialBinding(trial(binding.sha256(),"S01","wave-a-v1","undeclared")), "undeclared variant");
        Path ledger = root.resolve("fictional.ledger"); ResearchTrialLedger.register(ledger, frozenTrial);
        check(ResearchTrialLedger.entries(ledger).size() == 1, "existing ledger reused unchanged");
        cli(0, true, "inspect", file(original).toString()); cli(3, false, "inspect", malformed.toString());
        cli(0, true, "verify", registry.toString(), c.id(), "2");
        cli(3, false, "check-trial", registry.toString(), c.id(), "1", ledger.toString(), "synthetic-trial");
        Path schema = root.resolve("generated-schema.json"); cli(0,false,"schema",schema.toString()); cli(3,false,"schema",schema.toString());
        toyBook();
        System.out.println("Hypothesis card: " + checks + " checks PASS; SYNTHETIC SOFTWARE ONLY; market_evidence=false approval_authenticated=false g1_closed=false");
    }
    private static Map<String,Object> freeze() throws Exception {
        Map<String,Object> m = parse(original); m.put("card_id", "SYNTHETIC-FROZEN"); m.put("status", "FROZEN_DECLARED"); m.put("classification", "SYNTHETIC_PLUMBING_ONLY"); m.put("frozen_at_utc", "2026-11-09T00:00:00Z");
        object(m.get("reviews")).values().forEach(r -> { var v=object(r); v.put("status","CALLER_DECLARED"); v.put("record_id","SYNTHETIC-REVIEW"); v.put("evidence_sha256",HypothesisCard.sha256("FICTIONAL ONLY".getBytes(StandardCharsets.UTF_8))); v.put("at_utc","2026-11-08T03:00:00Z"); });
        return m;
    }
    private static ResearchTrialLedger.Trial trial(String hash,String strategy,String version,String variant) {
        return new ResearchTrialLedger.Trial("synthetic-trial",strategy,version,variant,hash,"a".repeat(64),"b".repeat(64),"c".repeat(64));
    }
    private static void toyBook() {
        var book = new ToyLimitOrderBook();
        book.submit(new ToyLimitOrderBook.OrderRequest("A",ToyLimitOrderBook.Side.SELL,ToyLimitOrderBook.OrderType.LIMIT,2,OptionalLong.of(101)));
        book.submit(new ToyLimitOrderBook.OrderRequest("B",ToyLimitOrderBook.Side.SELL,ToyLimitOrderBook.OrderType.LIMIT,3,OptionalLong.of(101)));
        var r=book.submit(new ToyLimitOrderBook.OrderRequest("I",ToyLimitOrderBook.Side.BUY,ToyLimitOrderBook.OrderType.MARKET_IOC,4,OptionalLong.empty()));
        check(r.trades().size()==2 && r.trades().get(0).sellOrderId().equals("A") && r.trades().get(0).quantity()==2 && r.trades().get(1).quantity()==2,"toy FIFO partial exercise");
        var missed=book.submit(new ToyLimitOrderBook.OrderRequest("M",ToyLimitOrderBook.Side.SELL,ToyLimitOrderBook.OrderType.MARKET_IOC,1,OptionalLong.empty()));
        check(missed.trades().isEmpty() && missed.order().status()==ToyLimitOrderBook.OrderStatus.CANCELED,"toy missed IOC");
    }
    private static void cli(int exit,boolean flags,String... args) throws Exception {
        var command=new java.util.ArrayList<String>(); command.add(Path.of(System.getProperty("java.home"),"bin","java.exe").toString()); command.add("-cp"); command.add(System.getProperty("java.class.path")); command.add(HypothesisCardCli.class.getName()); command.addAll(List.of(args));
        var p=new ProcessBuilder(command).redirectErrorStream(true).start(); String output=new String(p.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        check(p.waitFor()==exit,"CLI exit"); if(flags) check(output.contains("trial_eligible=false") && output.contains("permission_approved=false") && output.contains("g1_closed=false") && output.contains("independent_preregistration=UNVERIFIED"),"CLI never grants gates/approval");
    }
    private static Path file(String text) throws IOException { Path p=root.resolve("case-"+(++fileSequence)+".json"); Files.writeString(p,text); return p; }
    private static void mutateReject(String key,Object value) throws Exception { Map<String,Object> m=parse(original); m.put(key,value); fails(()->HypothesisCard.load(file(json(m))),"invalid field "+key); }
    private static void fails(Action action,String label) throws Exception { try {action.run();} catch(IOException|IllegalArgumentException expected) {checks++;return;} throw new AssertionError("Expected failure: "+label); }
    private static void check(boolean condition,String label) { if(!condition) throw new AssertionError(label); checks++; }
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object o) {return (Map<String,Object>)o;}
    private static Map<String,Object> parse(String s) throws IOException {return object(StrictJsonParser.parse(s));}
    private static String json(Object o) {
        if(o==null)return "null";
        if(o instanceof Map<?,?> m)return "{"+String.join(",",m.entrySet().stream().map(e->json(e.getKey())+":"+json(e.getValue())).toList())+"}";
        if(o instanceof List<?> l)return "["+String.join(",",l.stream().map(HypothesisCardTest::json).toList())+"]";
        if(o instanceof String s)return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r")+"\"";
        return o.toString();
    }
}
