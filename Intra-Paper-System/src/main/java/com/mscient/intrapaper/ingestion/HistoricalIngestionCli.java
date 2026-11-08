package com.mscient.intrapaper.ingestion;

import java.io.PrintStream;
import java.nio.file.*;
import java.util.*;
import static com.mscient.intrapaper.ingestion.IngestionIO.*;

/** No network, authentication, broker, retention deletion, or live-trading command exists. */
public final class HistoricalIngestionCli {
    private HistoricalIngestionCli() { }
    public static void main(String[] args) { System.exit(run(args,System.out,System.err)); }
    public static int run(String[] args,PrintStream out,PrintStream err) {
        if(args.length<1 || !Set.of("plan","ingest","verify").contains(args[0]) || args.length%2!=1) return usage(err);
        Map<String,String> options=new TreeMap<>(); List<Path> evidence=new ArrayList<>();
        Set<String> allowed=new HashSet<>(List.of("request","policy","mapping","master","calendar","raw","out","operation"));
        allowed.addAll(HistoricalPermissionGate.DOCUMENTS);
        try {
            for(int i=1;i<args.length;i+=2) {
                if(!args[i].startsWith("--") || args[i+1].isBlank()) return usage(err);
                String key=args[i].substring(2);
                if(key.equals("evidence-file")) { evidence.add(local(Path.of(args[i+1]))); continue; }
                if(!allowed.contains(key) || options.putIfAbsent(key,args[i+1])!=null) return usage(err);
            }
            boolean verify=args[0].equals("verify");
            if(verify!=options.containsKey("operation")) return usage(err);
            String operation=verify?options.get("operation"):args[0];
            if(!Set.of("plan","ingest").contains(operation) || !options.keySet().containsAll(List.of("request","policy","out"))) return usage(err);
            if(operation.equals("ingest") && !options.containsKey("raw")) return usage(err);
            if(operation.equals("plan") && (options.keySet().stream().anyMatch(k->!Set.of("request","policy","mapping","out","operation").contains(k)) || !evidence.isEmpty())) return usage(err);
            Map<String,Path> paths=new TreeMap<>();
            for(var e:options.entrySet()) if(!Set.of("operation","out").contains(e.getKey())) paths.put(e.getKey(),local(Path.of(e.getValue())));
            Path output=local(Path.of(options.get("out")));
            if(paths.values().stream().anyMatch(p->p.startsWith(output)) || evidence.stream().anyMatch(p->p.startsWith(output))) return usage(err);
            HistoricalIngestion.Result result=HistoricalIngestion.compute(operation,paths,evidence);
            if(verify) {
                try { HistoricalArtifacts.verify(output,result.files()); }
                catch(Exception failure) { err.println("VERIFICATION_FAILED"); return 4; }
                out.println("VERIFIED status="+result.status()); return result.exitCode();
            }
            HistoricalArtifacts.publish(output,result.files());
            out.println("status="+result.status()+" manifest_sha256="+hash(result.files().get("manifest.json")));
            return result.exitCode();
        } catch(Exception failure) { err.println("COMMAND_FAILED"); return 74; }
    }
    private static int usage(PrintStream err) {
        err.println("Usage: plan|ingest|verify --request FILE --policy FILE --out NEW_DIRECTORY [--operation plan|ingest] [--raw FILE] [--mapping FILE --master FILE --calendar FILE] [permission flags; see guide]");
        return 64;
    }
}
