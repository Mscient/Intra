package com.mscient.intrapaper.ingestion;

import com.mscient.intrapaper.permission.preflight.PermissionPreflight;
import com.mscient.intrapaper.permission.coverage.PermissionCoverage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static com.mscient.intrapaper.ingestion.IngestionIO.*;

/** Declaration tools are reused but cannot authenticate provider rights. There is no override. */
final class HistoricalPermissionGate {
    static final List<String> DOCUMENTS=List.of("preflight-manifest","intended-use","permission-evidence",
        "coverage-dataset","coverage-intent","coverage-register");
    private HistoricalPermissionGate() { }
    static Map<String,Object> assess(HistoricalRequest request, Map<String,Path> paths, List<Path> explicitEvidence) throws IOException {
        if(request.synthetic()) {
            require(DOCUMENTS.stream().noneMatch(paths::containsKey) && explicitEvidence.isEmpty(),"SYNTHETIC_PERMISSION_CONFUSION");
            return decision("SYNTHETIC_ONLY","SYNTHETIC_FIXTURE_IDENTITY",null,null,new TreeMap<>());
        }
        Map<String,Object> hashes=new TreeMap<>(); Map<String,byte[]> snapshots=new TreeMap<>();
        for(String name:DOCUMENTS) {
            byte[] b=null;
            if(paths.containsKey(name)) {
                try { b=read(paths.get(name)); safeText(utf8(b)); snapshots.put(name,b); }
                catch(IOException | RuntimeException missing) { /* Missing evidence fails closed. */ }
            }
            hashes.put(name,b==null?null:hash(b));
        }
        String preflight=null, coverage=null;
        Path stage=Files.createTempDirectory("historical-permission-");
        try {
            // Preflight reads only these three frozen documents; it never dereferences evidence pointers.
            for(String name:DOCUMENTS) if(snapshots.containsKey(name)) Files.write(stage.resolve(name+".json"),snapshots.get(name));
            var result=PermissionPreflight.check(stage.resolve("preflight-manifest.json"),stage.resolve("intended-use.json"),
                stage.resolve("permission-evidence.json"),request.assessment());
            preflight=result.id();
            Map<String,Object> coverageResult;
            // Coverage may hash local files named inside documents. Permit only explicitly supplied paths.
            // Never supply raw market bytes to it while provider permission remains unverified.
            Set<Path> allowed=new HashSet<>();
            for(Path p:explicitEvidence) allowed.add(local(p));
            boolean safe=snapshots.keySet().containsAll(List.of("coverage-dataset","coverage-intent","coverage-register"));
            if(safe) {
                try {
                    Map<String,Object> dataset=map(parse(snapshots.get("coverage-dataset")));
                    require(list(dataset.get("files")).isEmpty(),"PROVIDER_DATA_NOT_ADMITTED");
                    Map<String,Object> register=map(parse(snapshots.get("coverage-register")));
                    for(Object v:list(register.get("records"))) {
                        Map<String,Object> record=map(v), evidence=map(record.get("evidence"));
                        if(evidence.get("path")!=null) {
                            String rel=str(evidence,"path"); Path p=Path.of(rel);
                            require(!p.isAbsolute() && rel.matches("[A-Za-z0-9_./-]+"),"EVIDENCE_PATH_INVALID");
                            Path source=local(paths.get("coverage-register").toAbsolutePath().getParent().resolve(p));
                            require(allowed.contains(source),"EVIDENCE_PATH_NOT_EXPLICIT");
                            // Only one flat copied name; no user-controlled output path or metadata is published.
                            byte[] b=read(source); safeText(utf8(b)); String name="evidence-"+hash(b)+".bin";
                            Files.write(stage.resolve(name),b); evidence.put("path",name);
                            hashes.put(name,hash(b));
                        }
                    }
                    Files.write(stage.resolve("coverage-register.json"),bytes(register));
                } catch(IOException | RuntimeException invalid) { safe=false; }
            }
            if(safe) coverageResult=PermissionCoverage.check(stage.resolve("coverage-dataset.json"),stage.resolve("coverage-intent.json"),stage.resolve("coverage-register.json"));
            else {
                // Existing checker records INCOMPLETE with missing inputs; no indirect file is opened.
                coverageResult=PermissionCoverage.check(stage.resolve("missing-dataset"),stage.resolve("missing-intent"),stage.resolve("missing-register"));
            }
            coverage=hash(bytes(coverageResult));
            return decision("PERMISSION_DENIED","PROVIDER_APPROVAL_UNVERIFIABLE",preflight,coverage,hashes);
        } finally { removeStage(stage); }
    }
    private static Map<String,Object> decision(String status,String reason,String preflight,String coverage,Map<String,Object> hashes) {
        Map<String,Object> d=obj("schema_version","historical-permission/v1","status",status,"reason",reason,
            "preflight_decision_id",preflight,"coverage_report_sha256",coverage,"input_sha256",hashes,
            "permission_approved",false,"production_eligible",false,"g0_closed",false,"g2_closed",false);
        d.put("decision_id",hash(bytes(d))); return d;
    }
}
