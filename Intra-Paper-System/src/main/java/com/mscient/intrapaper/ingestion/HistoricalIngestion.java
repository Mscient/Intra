package com.mscient.intrapaper.ingestion;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.reference.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static com.mscient.intrapaper.ingestion.IngestionIO.*;

/** Standalone offline application service. Reads only the caller's explicit local paths. */
public final class HistoricalIngestion {
    private HistoricalIngestion() { }
    record Result(String status,Map<String,byte[]> files) {
        int exitCode() { return status.startsWith("PASS")?0:status.equals("INCOMPLETE")?3:2; }
    }
    static Result compute(String operation,Map<String,Path> paths,List<Path> evidence) throws IOException {
        Map<String,byte[]> files=new TreeMap<>(); Map<String,Object> inputHashes=new TreeMap<>();
        Map<String,Object> manifest=obj("schema_version","historical-ingestion-manifest/v1","operation",operation,
            "software",HistoricalArtifacts.software(),"parser_version",SmartApiCandleAdapterV1.VERSION,
            "permission_approved",false,"production_eligible",false,"g0_closed",false,"g2_closed",false,
            "input_sha256",inputHashes,"determinism","CALLER_FROZEN_CAPTURE_AND_ASSESSMENT_TIME",
            "raw_preservation","EXACT_BYTES_ONLY_AFTER_PERMISSION_AND_STRICT_PARSING");
        String status;
        Path stage=Files.createTempDirectory("historical-references-");
        try {
            byte[] requestBytes=snapshot("request",paths,inputHashes);
            HistoricalRequest request=HistoricalRequest.parse(requestBytes);
            manifest.put("request",request.fields());
            if(operation.equals("ingest")) {
                Map<String,Object> permission=HistoricalPermissionGate.assess(request,paths,evidence);
                files.put("permission.json",bytes(permission)); manifest.put("permission_decision_id",permission.get("decision_id"));
                if(!permission.get("status").equals("SYNTHETIC_ONLY")) {
                    status="PERMISSION_DENIED"; manifest.put("status",status);
                    files.put("validation.json",bytes(obj("status",status,"reason","PROVIDER_APPROVAL_UNVERIFIABLE","raw_opened",false,
                        "permission_approved",false,"production_eligible",false)));
                    return new Result(status,HistoricalArtifacts.finish(files,manifest));
                }
            }
            byte[] policy=snapshot("policy",paths,inputHashes);
            DatedInstrumentMappingRegistry registry=null;
            if(paths.containsKey("mapping")) {
                try { Path p=frozen("mapping",paths,inputHashes,stage); registry=DatedInstrumentMappingRegistry.load(p); }
                catch(IOException | RuntimeException invalid) { /* Missing/invalid references never become today's mapping. */ }
            }
            Map<String,Object> plan=HistoricalPlanner.plan(request,policy,registry); files.put("plan.json",bytes(plan));
            if(operation.equals("plan")) {
                status=plan.get("mapping_coverage").equals("COVERED")?"PASS_PLANNED":"INCOMPLETE";
                files.put("validation.json",bytes(obj("status",status,"mapping_coverage",plan.get("mapping_coverage"),
                    "permission_approved",false,"production_eligible",false)));
            } else {
                byte[] raw=snapshot("raw",paths,inputHashes);
                List<SmartApiCandleAdapterV1.Candle> candles=SmartApiCandleAdapterV1.parseResponse(raw,request);
                InstrumentMasterSnapshot master=null; Map<LocalDate,SessionCalendarEntry> calendar=null;
                if(paths.containsKey("master")) {
                    try { master=InstrumentMasterCsvReader.read(frozen("master",paths,inputHashes,stage),request.s("master_snapshot_id"),request.s("source"),
                        HistoricalRequest.timestamp(request.s("master_retrieved_at")),HistoricalRequest.date(request.s("master_effective_from"))); }
                    catch(IOException | RuntimeException invalid) { /* Report INCOMPLETE. */ }
                }
                if(paths.containsKey("calendar")) {
                    try { calendar=SessionCalendarCsvReader.read(frozen("calendar",paths,inputHashes,stage)); }
                    catch(IOException | RuntimeException invalid) { /* Report INCOMPLETE. */ }
                }
                CandleQuality.Result quality=CandleQuality.check(request,candles,registry,master,calendar);
                status=str(quality.report(),"status");
                if(!plan.get("mapping_coverage").equals("COVERED") && status.equals("PASS_SYNTHETIC")) status="INCOMPLETE";
                quality.report().put("status",status);
                files.put("validation.json",bytes(quality.report()));
                files.put("raw-"+hash(raw)+".json",raw);
                if(quality.normalized()!=null) files.put("normalized.csv",quality.normalized());
                manifest.put("raw_sha256",hash(raw)); manifest.put("normalized_sha256",quality.normalized()==null?null:hash(quality.normalized()));
            }
        } catch(IngestionIO.Invalid invalid) {
            status="REJECTED"; files.clear();
            files.put("validation.json",bytes(obj("status",status,"reason",invalid.getMessage(),"permission_approved",false,"production_eligible",false)));
        } catch(RuntimeException | IOException invalid) {
            // Shared parsers can include caller values in exception text. Never publish or log it.
            status="REJECTED"; files.clear();
            files.put("validation.json",bytes(obj("status",status,"reason","INVALID_OR_UNAVAILABLE_INPUT","permission_approved",false,"production_eligible",false)));
        } finally { removeStage(stage); }
        manifest.put("status",status);
        return new Result(status,HistoricalArtifacts.finish(files,manifest));
    }
    private static byte[] snapshot(String name,Map<String,Path> paths,Map<String,Object> hashes) throws IOException {
        hashes.put(name,null); require(paths.containsKey(name),"REQUIRED_INPUT_MISSING");
        byte[] b=read(paths.get(name)); hashes.put(name,hash(b)); return b;
    }
    private static Path frozen(String name,Map<String,Path> paths,Map<String,Object> hashes,Path stage) throws IOException {
        byte[] b=snapshot(name,paths,hashes); safeText(utf8(b)); Path p=stage.resolve(name+".csv"); Files.write(p,b); return p;
    }
}
