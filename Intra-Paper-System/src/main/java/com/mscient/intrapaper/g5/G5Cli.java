package com.mscient.intrapaper.g5;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.mscient.intrapaper.g5.G5Files.*;

/** Separate offline entrypoint. No shared CLI changes, broker actions, networking or credentials. */
public final class G5Cli {
    private G5Cli() { }
    public static int execute(String[] args) {
        try {
            if (args.length == 7 && args[0].equals("check")) {
                G5Checker.Result r = G5Checker.evaluate(Path.of(args[1]),privatePath(args[2]),G5Checker.time(args[3]),args[4]);
                if (!args[5].equals("report")) throw invalid();
                publish(r.report(),Path.of(args[6]));
                System.out.println("G5_STATUS="+r.status()+" G5_CLOSED=false ORDER_EGRESS=false REPORT_SHA256="+r.reportHash());
                return r.status() == G5Checker.Status.ELIGIBLE_FOR_HUMAN_DECISION ? 0 : r.status() == G5Checker.Status.FAIL ? 1 : 2;
            }
            if (args.length == 7 && args[0].equals("verify")) {
                G5Checker.verify(Path.of(args[1]),privatePath(args[2]),G5Checker.time(args[3]),args[4],Path.of(args[5]),args[6]);
                System.out.println("G5_VERIFIED_BYTE_IDENTICAL G5_CLOSED=false ORDER_EGRESS=false"); return 0;
            }
            if (args.length == 5 && args[0].equals("explain")) {
                System.out.write(G5Checker.evaluate(Path.of(args[1]),privatePath(args[2]),G5Checker.time(args[3]),args[4]).report()); return 0;
            }
            if (args.length == 6 && args[0].equals("record")) {
                Path root = safe(Path.of(args[1]),true); Map<String,Object> event = json(read(Path.of(args[2])));
                byte[] dossier = read(root.resolve("dossier.json"));
                if (!hash(dossier).equals(string(event,"dossier_sha256"))) throw invalid();
                Path archive = root.resolve("archive"); Files.createDirectories(archive); safe(archive,true);
                retain(archive,dossier);
                Map<String,Object> inputs=json(dossier);
                for (String group : List.of("files","evidence")) for (Object value : list(inputs,group)) {
                    Map<String,Object> binding=object(value); String path=relative(string(binding,"path"));
                    if (path.startsWith("archive/") || path.startsWith("history/") || path.startsWith("decisions/")) throw invalid();
                    Path source=root.resolve(path);
                    if (Files.exists(source,LinkOption.NOFOLLOW_LINKS)) {
                        byte[] original=read(source); if (!hash(original).equals(digest(string(binding,"sha256")))) throw invalid();
                        retain(archive,original);
                    }
                }
                String attachment = string(event,"attachment_sha256");
                if (!attachment.equals(ZERO)) {
                    byte[] supplied = read(Path.of(args[5])); if (!hash(supplied).equals(attachment)) throw invalid(); retain(archive,supplied);
                } else if (!args[5].equals("-")) throw invalid();
                Path history = root.resolve("history"); Files.createDirectories(history);
                System.out.println("G5_HISTORY_HEAD="+G5History.append(history,args[3],event,G5Checker.time(args[4]))+" G5_CLOSED=false ORDER_EGRESS=false"); return 0;
            }
            System.err.println("USAGE: check ROOT PRIVATE_OR_DASH AS_OF RETAINED_HEAD report NEW_REPORT | verify ROOT PRIVATE_OR_DASH AS_OF RETAINED_HEAD REPORT RETAINED_REPORT_HASH | explain ROOT PRIVATE_OR_DASH AS_OF RETAINED_HEAD | record ROOT EVENT RETAINED_HEAD AS_OF ATTACHMENT_OR_DASH");
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            System.err.println("G5_STATUS=FAIL G5_REJECTED_SCHEMA_IDENTITY_HISTORY_OR_IO G5_CLOSED=false ORDER_EGRESS=false");
        }
        return 3;
    }
    private static Path privatePath(String arg) { return arg.equals("-") ? null : Path.of(arg); }
    private static void retain(Path archive, byte[] content) throws IOException {
        Path p = archive.resolve(hash(content)+".bin");
        if (Files.exists(p,LinkOption.NOFOLLOW_LINKS)) { if (!Arrays.equals(read(p),content)) throw invalid(); }
        else publish(content,p);
    }
    public static void main(String[] args) { System.exit(execute(args)); }
}
