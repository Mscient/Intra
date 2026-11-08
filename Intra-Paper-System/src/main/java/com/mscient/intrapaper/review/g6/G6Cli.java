package com.mscient.intrapaper.review.g6;

import static com.mscient.intrapaper.review.g6.G6Json.*;
import java.nio.file.Path;
import java.util.Map;

/** Separate CLI; exit 0 is reserved for eligibility (currently unreachable), 1 FAIL, 2 INCOMPLETE. */
public final class G6Cli {
    private G6Cli() { }
    public static void main(String[] args) { System.exit(run(args)); }
    public static int run(String[] args) {
        try {
            if(args.length==1 && args[0].equals("explain")) {
                System.out.print("G6 offline review v1\nreview DOSSIER HISTORY OUTPUT_DIRECTORY\nverify DOSSIER OUTPUT_DIRECTORY [EXTENDED_HISTORY]\nexplain DOSSIER HISTORY\nappend HISTORY EVENT NEW_GENERATION_DIRECTORY\n"
                        +"[start,end_exclusive) must span six calendar months; every calendar day and scheduled session is explicit.\n"
                        +"FAIL: contradictions, tampering, protocol/reconciliation errors. INCOMPLETE: missing or unauthenticated evidence.\n"
                        +"ELIGIBLE_FOR_HUMAN_DECISION is the ceiling; v1 cannot reach it because G5/broker/identity authentication is not provisioned.\n"
                        +"Ed25519 validates signed bytes only; supplied keys, identities, qualifications and timestamps remain unauthenticated.\n"
                        +"g6_closed=false; scale_authorized=false. No performance, capacity, compliance or readiness claim.\n"); return 0;
            }
            Map<String,Object> report;
            if(args.length==4 && args[0].equals("review")) report=G6Artifacts.publish(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]));
            else if((args.length==3 || args.length==4) && args[0].equals("verify")) report=G6Artifacts.verify(Path.of(args[1]),Path.of(args[2]),args.length==4 ? Path.of(args[3]) : null);
            else if(args.length==3 && args[0].equals("explain")) report=G6Review.review(Path.of(args[1]),Path.of(args[2]));
            else if(args.length==4 && args[0].equals("append")) { G6Artifacts.append(Path.of(args[1]),Path.of(args[2]),Path.of(args[3])); System.out.println("History extension published; identities remain unauthenticated."); return 0; }
            else throw new IllegalArgumentException("Use explain for exact offline command syntax");
            System.out.write(bytes(report)); return "FAIL".equals(report.get("status")) ? 1 : 2;
        } catch(java.nio.file.NoSuchFileException | java.nio.file.AccessDeniedException e) {
            System.out.println(com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceReport.json(Map.of("status","INCOMPLETE","g6_closed",false,"scale_authorized",false,"error","Required local input is missing or unreadable"))); return 2;
        } catch(Exception e) {
            System.out.println(DailyMessage.error(e)); return 1;
        }
    }
    private static final class DailyMessage {
        static String error(Exception e) { return com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceReport.json(Map.of("status","FAIL","g6_closed",false,"scale_authorized",false,"error",e.getMessage()==null ? e.getClass().getSimpleName() : e.getMessage())); }
    }
}
