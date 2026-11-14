package com.mscient.intrapaper.permission;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.simulation.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Invented declarations exercise software only; no evidence here authorizes real research. */
public final class PermissionAdmissionTest {
    private static int checks;
    private static final Instant AS_OF = Instant.parse("2026-11-14T00:00:00Z");
    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[0]), root = Path.of(args[1]); Files.createDirectory(root);
        for (String file : List.of("synthetic-run.properties","synthetic-futures-bars.csv",
                "synthetic-instrument-master.csv","synthetic-session-calendar.csv",
                "synthetic-market-rule-regimes.csv","synthetic-cost-schedule.csv"))
            Files.copy(project.resolve("examples").resolve(file),root.resolve(file));
        Path properties = root.resolve("synthetic-run.properties"), bars = root.resolve("synthetic-futures-bars.csv");
        Files.writeString(properties,Files.readString(properties).replace("dataset.acquired_at_utc=2030-05-06T00:00:00Z",
                "dataset.acquired_at_utc=2020-01-01T00:00:00Z"));
        var baseConfig = RunConfig.load(properties); var original = ValidatedDataset.load(bars,baseConfig);
        Map<String,Object> grant = grant(root,original.bars().getFirst().instrumentId());
        Map<String,Object> manifest = manifest(root,original);
        Path recordPath = root.resolve("permission.json"), manifestPath = root.resolve("manifest.json");
        java.util.function.Consumer<Map<String,Object>> saveGrant = value -> {
            try { Files.writeString(recordPath,json(value)); } catch (Exception e) { throw new RuntimeException(e); }
        };
        saveGrant.accept(grant); manifest.put("permission_record_sha256",RunConfig.sha256(Files.readAllBytes(recordPath)));
        Files.writeString(manifestPath,json(manifest)); String pin = RunConfig.sha256(Files.readAllBytes(manifestPath));
        ok(DataPermissionChecker.check(manifestPath,pin,recordPath,AS_OF,DataPermissionChecker.Mode.SYNTHETIC_TEST)
                .status() == DataPermissionChecker.Status.SYNTHETIC_PASS,"synthetic declaration passes only synthetic mode");
        ok(!DataPermissionChecker.check(manifestPath,pin,recordPath,AS_OF,DataPermissionChecker.Mode.RESEARCH)
                .researchAdmissionAllowed(),"synthetic declarations cannot authorize research");
        String[][] mutations = {{"decision","PENDING"},{"decision","DENIED"},{"decision","REVOKED"},
                {"simulation_right","UNKNOWN"},{"retention_right","DENIED"},{"derived_output_right","UNKNOWN"},
                {"reviewer","synthetic-owner"},{"source","OTHER"},{"product","OTHER"},
                {"effective_until_utc","2026-01-01T00:00:00Z"},{"reviewed_at_utc","2027-01-01T00:00:00Z"}};
        for (String[] mutation : mutations) {
            var changed = new TreeMap<>(grant); changed.put(mutation[0],mutation[1]); saveGrant.accept(changed);
            // Re-pin the changed record so rejection tests semantics independently of its hash.
            var changedManifest = new TreeMap<>(manifest);
            changedManifest.put("permission_record_sha256",RunConfig.sha256(Files.readAllBytes(recordPath)));
            Files.writeString(manifestPath,json(changedManifest));
            var result = DataPermissionChecker.check(manifestPath,RunConfig.sha256(Files.readAllBytes(manifestPath)),
                    recordPath,AS_OF,DataPermissionChecker.Mode.SYNTHETIC_TEST);
            ok(result.status() == DataPermissionChecker.Status.DENIED,"missing/out-of-scope right or review: " + mutation[0]);
        }
        saveGrant.accept(grant); Files.writeString(manifestPath,json(manifest));
        ok(DataPermissionChecker.check(manifestPath,"0".repeat(64),recordPath,AS_OF,DataPermissionChecker.Mode.SYNTHETIC_TEST)
                .status() == DataPermissionChecker.Status.DENIED,"manifest pin mismatch");
        Files.writeString(recordPath,json(grant)+" ");
        ok(DataPermissionChecker.check(manifestPath,pin,recordPath,AS_OF,DataPermissionChecker.Mode.SYNTHETIC_TEST)
                .status() == DataPermissionChecker.Status.DENIED,"changed record bytes despite same semantics");
        saveGrant.accept(grant);
        Files.writeString(properties,Files.readString(properties)+"\npermission.manifest_file=manifest.json\n"
                +"permission.manifest_sha256="+pin+"\npermission.record_file=permission.json\n");
        var config = RunConfig.load(properties); var admitted = ValidatedDataset.load(bars,config);
        ok(admitted.manifest().inputFileSha256().get("permission-manifest").equals(pin),"manifest enters dataset provenance");
        ok(admitted.manifest().inputFileSha256().get("permission-record").equals(manifest.get("permission_record_sha256")),"record enters dataset provenance");
        var hashes = new TreeMap<>(original.manifest().inputFileSha256());
        PermissionAdmission.require(bars,config,original.bars(),hashes);
        ok(hashes.equals(admitted.manifest().inputFileSha256()),"admission verifies exactly loaded byte hashes");
        var result = new BacktestEngine().run(admitted,config);
        String id = RunIdentity.contentId(admitted,config,new SmaCrossoverStrategy(config));
        var written = new RunArtifactWriter().write(root.resolve("runs"),id,admitted,config,result);
        ok(Files.readString(written.path().resolve("run-manifest.json")).contains(pin),"permission hash retained in run artifact");
        // Changing an otherwise matching declaration changes content identity too.
        var changedGrant = new TreeMap<>(grant); changedGrant.put("permission_reference","synthetic-v2"); saveGrant.accept(changedGrant);
        var changedManifest = new TreeMap<>(manifest);
        changedManifest.put("permission_record_sha256",RunConfig.sha256(Files.readAllBytes(recordPath)));
        Files.writeString(manifestPath,json(changedManifest)); String changedPin = RunConfig.sha256(Files.readAllBytes(manifestPath));
        Files.writeString(properties,Files.readString(properties).replace(pin,changedPin));
        var nextConfig = RunConfig.load(properties); var next = ValidatedDataset.load(bars,nextConfig);
        ok(!RunIdentity.contentId(next,nextConfig,new SmaCrossoverStrategy(nextConfig)).equals(id),"permission change creates distinct run identity");
        // Keep config unchanged while replacing a file: admitted manifest must reject it.
        Files.writeString(root.resolve("synthetic-market-rule-regimes.csv"),
                Files.readString(root.resolve("synthetic-market-rule-regimes.csv")).replace("SYNTHETIC_ONLY","UNVERIFIED"));
        rejects(() -> ValidatedDataset.load(bars,nextConfig),"changed governed sidecar");
        System.out.println("PermissionAdmissionTest: " + checks + " synthetic permission and integration checks passed; G0/G2 remain open");
    }
    private static Map<String,Object> grant(Path root,String instrumentId) {
        var g = new TreeMap<String,Object>();
        g.put("schema","DATA_PERMISSION_RECORD_V1"); g.put("record_id","synthetic-permission");
        g.put("source","SYNTHETIC_FIXTURE"); g.put("product","synthetic-bars"); g.put("instruments",List.of(instrumentId));
        g.put("data_from","2030-05-06"); g.put("data_through","2030-05-06");
        g.put("simulation_right","ALLOWED"); g.put("retention_right","ALLOWED"); g.put("retain_until_utc","2101-01-01T00:00:00Z");
        g.put("storage_locations",List.of(root.toUri().toString())); g.put("derived_output_right","ALLOWED");
        g.put("derived_output_uses",List.of("INTERNAL_RESEARCH")); g.put("permission_reference","synthetic-only");
        g.put("permission_evidence_sha256","1".repeat(64)); g.put("owner","synthetic-owner");
        g.put("owner_attestation_reference","synthetic-attestation"); g.put("reviewer","synthetic-reviewer");
        g.put("reviewer_qualification_reference","synthetic-qualification"); g.put("review_approval_reference","synthetic-review");
        g.put("reviewed_at_utc","2019-01-01T00:00:00Z"); g.put("decision","APPROVED");
        g.put("effective_from_utc","2019-01-01T00:00:00Z"); g.put("effective_until_utc","2100-01-01T00:00:00Z");
        g.put("evidence_kind","SYNTHETIC_TEST"); return g;
    }
    private static Map<String,Object> manifest(Path root,ValidatedDataset data) {
        var m = new TreeMap<String,Object>(); m.put("schema","DATA_PERMISSION_MANIFEST_V1");
        m.put("dataset_id",data.manifest().datasetId()); m.put("source","SYNTHETIC_FIXTURE"); m.put("product","synthetic-bars");
        m.put("instruments",List.of(data.bars().getFirst().instrumentId())); m.put("data_from","2030-05-06"); m.put("data_through","2030-05-06");
        m.put("acquired_at_utc","2020-01-01T00:00:00Z"); m.put("intended_use","RESEARCH_SIMULATION");
        m.put("use_from_utc","2020-01-01T00:00:00Z"); m.put("use_until_utc","2100-01-01T00:00:00Z");
        m.put("retain_until_utc","2101-01-01T00:00:00Z"); m.put("storage_location",root.toUri().toString());
        m.put("derived_output_uses",List.of("INTERNAL_RESEARCH")); m.put("input_file_sha256",data.manifest().inputFileSha256());
        m.put("permission_record_id","synthetic-permission"); m.put("evidence_kind","SYNTHETIC_TEST"); return m;
    }
    private static String json(Object value) {
        if (value instanceof Map<?,?> map) return "{"+String.join(",",map.entrySet().stream().map(e -> json(e.getKey())+":"+json(e.getValue())).toList())+"}";
        if (value instanceof Collection<?> list) return "["+String.join(",",list.stream().map(PermissionAdmissionTest::json).toList())+"]";
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"")+"\"";
    }
    private static void ok(boolean value,String reason) { checks++; if (!value) throw new AssertionError(reason); }
    private static void rejects(Action action,String reason) throws Exception {
        checks++; try { action.run(); } catch (java.io.IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected rejection: "+reason);
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
}
