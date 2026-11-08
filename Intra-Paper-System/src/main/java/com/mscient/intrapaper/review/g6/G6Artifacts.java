package com.mscient.intrapaper.review.g6;

import static com.mscient.intrapaper.review.g6.G6Json.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Atomic, create-only report generations and history extensions. No non-atomic fallback. */
public final class G6Artifacts {
    private G6Artifacts() { }
    static String codeHash() throws IOException { return com.mscient.intrapaper.audit.feed.FeedAuditArtifacts.codeHash(); }
    public static String build() throws IOException {
        try {
            Path code=Path.of(G6Artifacts.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if(Files.isDirectory(code)) return "UNPACKAGED";
            try(JarFile jar=new JarFile(code.toFile())) {
                String value=jar.getManifest().getMainAttributes().getValue("Build-Commit");
                return value==null ? "UNKNOWN" : value;
            }
        } catch(java.net.URISyntaxException e) { throw new IOException("Build identity unavailable",e); }
    }
    public static Map<String,Object> publish(Path dossier,Path inputHistory,Path output) throws IOException {
        byte[] before=read(inputHistory); Map<String,Object> report=G6Review.review(dossier,before,true);
        require(Arrays.equals(before,read(inputHistory)),"History changed during analysis");
        G6History.Checked checked=G6History.check(parse(before),str(report,"review_id"));
        byte[] data=bytes(report); String digest=hash(data);
        Map<String,Object> analysis=G6History.event(checked.events.size(),checked.head(),"ANALYSIS",str(report,"review_id"),digest,null,null,str(checked.events.getLast(),"event_time"));
        analysis.put("report",report);
        Map<String,Object> history=G6History.extend(checked,analysis,str(report,"review_id"));
        Map<String,Object> artifact=Map.of("schema","g6-artifact/v1","report_sha256",digest,"input_history_sha256",hash(before),
                "history_sha256",hash(bytes(history)),"build",build(),"code_sha256",codeHash());
        Map<String,byte[]> files=new TreeMap<>(); files.put("report.json",data); files.put("input-history.json",before);
        files.put("history.json",bytes(history)); files.put("artifact.json",bytes(artifact));
        publishFiles(dossier,output,files); return report;
    }
    static void write(Path path,byte[] data) throws IOException {
        try(FileChannel ch=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(data); while(b.hasRemaining()) ch.write(b); ch.force(true);
        }
    }
    static void publishFiles(Path dossier,Path output,Map<String,byte[]> files) throws IOException {
        localDisk(dossier); localDisk(output);
        Path target=output.toAbsolutePath().normalize(),root=dossier.toAbsolutePath().normalize();
        noLinks(root); noLinks(target); Path parent=target.getParent();
        require(parent!=null && !target.startsWith(root),"Output must be outside dossier");
        require(!Files.exists(target,LinkOption.NOFOLLOW_LINKS),"Publication is create-only");
        Files.createDirectories(parent); noLinks(parent);
        require(!parent.toRealPath().startsWith(root.toRealPath()),"Output aliases dossier");
        Path lockPath=parent.resolve(".g6-publication.lock"); noLinks(lockPath);
        try(FileChannel ch=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock;
            try { lock=ch.tryLock(); } catch(OverlappingFileLockException e) { throw new IOException("Publication busy",e); }
            require(lock!=null,"Publication busy");
            try(lock) {
                require(!Files.exists(target,LinkOption.NOFOLLOW_LINKS),"Publication already exists");
                Path stage=Files.createTempDirectory(parent,".g6-stage-");
                try {
                    for(var e:files.entrySet()) write(stage.resolve(e.getKey()),e.getValue());
                    noLinks(target); require(!Files.exists(target,LinkOption.NOFOLLOW_LINKS),"Publication destination changed");
                    Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    if(Files.exists(stage,LinkOption.NOFOLLOW_LINKS)) {
                        for(String name:files.keySet()) Files.deleteIfExists(stage.resolve(name)); Files.delete(stage);
                    }
                }
            }
        }
    }
    public static Map<String,Object> verify(Path dossier,Path output,Path extendedHistory) throws IOException {
        noLinks(output); Set<String> expected=Set.of("report.json","artifact.json","history.json","input-history.json");
        try(var paths=Files.list(output)) { require(paths.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(expected),"Unexpected report directory layout"); }
        byte[] data=read(output.resolve("report.json")),input=read(output.resolve("input-history.json")),historyBytes=read(output.resolve("history.json"));
        Map<String,Object> a=parse(read(output.resolve("artifact.json")));
        keys(a,"schema","report_sha256","input_history_sha256","history_sha256","build","code_sha256"); schema(a,"artifact");
        require(hash(data).equals(digest(a.get("report_sha256"))) && hash(input).equals(digest(a.get("input_history_sha256"))) && hash(historyBytes).equals(digest(a.get("history_sha256"))),"Report/history byte digest mismatch");
        require(build().equals(a.get("build")) && codeHash().equals(a.get("code_sha256")),"Review build/code identity mismatch");
        require(Arrays.equals(bytes(a),read(output.resolve("artifact.json"))),"Noncanonical artifact metadata");
        Map<String,Object> report=parse(data); String id=str(report,"review_id");
        require(Arrays.equals(input,bytes(parse(input))) && Arrays.equals(historyBytes,bytes(parse(historyBytes))),"Noncanonical history bytes");
        G6History.Checked prefix=G6History.check(parse(input),id),stored=G6History.check(parse(historyBytes),id);
        require(stored.events.size()==prefix.events.size()+1,"Expected exactly one appended analysis event");
        for(int i=0;i<prefix.events.size();i++) require(stored.digests.get(i).equals(prefix.digests.get(i)),"History prefix altered");
        Map<String,Object> analysis=stored.events.getLast(); require(analysis.get("type").equals("ANALYSIS") && analysis.get("subject_sha256").equals(hash(data)),"Analysis/report binding mismatch");
        Map<String,Object> expectedAnalysis=G6History.event(prefix.events.size(),prefix.head(),"ANALYSIS",id,hash(data),null,null,str(prefix.events.getLast(),"event_time"));
        expectedAnalysis.put("report",report);
        require(Arrays.equals(bytes(expectedAnalysis),bytes(analysis)),"Generated analysis event bytes changed");
        require(Arrays.equals(data,bytes(G6Review.review(dossier,output.resolve("input-history.json")))),"Report differs from deterministic source re-review");
        byte[] extended=extendedHistory==null ? historyBytes : read(extendedHistory);
        require(Arrays.equals(extended,bytes(parse(extended))),"Noncanonical extended history");
        G6History.Checked full=G6History.check(parse(extended),id);
        require(full.events.size()>=stored.events.size(),"History truncated");
        for(int i=0;i<stored.events.size();i++) require(full.digests.get(i).equals(stored.digests.get(i)),"Changed or reordered history extension");
        List<Map<String,Object>> decisions=new ArrayList<>(); boolean reviewer=false,owner=false,revoked=false;
        String reviewerDigest=null;
        Sources sources=new Sources(dossier); Map<String,Object> manifest=parse(sources.load("manifest.json",null));
        for(int i=stored.events.size();i<full.events.size();i++) {
            Map<String,Object> event=full.events.get(i); String type=str(event,"type");
            require(List.of("REVIEWER_DECISION","OWNER_DECISION","REVOCATION").contains(type),"Report is stale: new evidence/analysis requires a new report");
            Map<String,Object> subject=sources.document(Map.of("path",event.get("subject_path"),"sha256",event.get("subject_sha256")));
            if(type.equals("REVOCATION")) {
                revocation(subject,id,hash(data)); revoked=true; continue;
            }
            require(!revoked,"Decision after revocation");
            String role=type.equals("REVIEWER_DECISION") ? "reviewer" : "owner";
            if(role.equals("owner")) { require(reviewer && !owner,"Owner decision requires one prior reviewer decision"); owner=true; }
            else { require(!reviewer,"Duplicate reviewer decision"); reviewer=true; }
            decisions.add(decision(sources,subject,role,manifest,report,hash(data),reviewerDigest));
            if(role.equals("reviewer")) reviewerDigest=str(event,"subject_sha256");
        }
        Map<String,Object> expectedSources=obj(report.get("expected_source_sha256")),actualSources=obj(report.get("source_sha256"));
        boolean allSources=true;
        for(var entry:expectedSources.entrySet()) {
            if(actualSources.get(entry.getKey())==null) { allSources=false; continue; }
            require(entry.getValue().equals(actualSources.get(entry.getKey())),"Declared source digest is not verified: "+entry.getKey());
        }
        sources.unchanged();
        Map<String,Object> result=new TreeMap<>(); result.put("integrity_verified",allSources); result.put("report_reproduced",true); result.put("status",revoked ? "FAIL" : report.get("status"));
        result.put("report_sha256",hash(data)); result.put("history_head",full.head()); result.put("decisions",decisions);
        result.put("identity_independently_authenticated",false); result.put("g6_closed",false); result.put("scale_authorized",false);
        return result;
    }
    static Map<String,Object> decision(Sources sources,Map<String,Object> d,String role,Map<String,Object> manifest,Map<String,Object> report,String hash,String reviewerDigest) throws IOException {
        keys(d,"schema","review_id","role","identity","qualification_evidence","independence_evidence","reviewed_sha256","report_sha256","reviewer_decision_sha256","build","code_sha256","decision","limitations","reservations","signature"); schema(d,"decision");
        require(role.equals(d.get("role")) && obj(manifest.get("roles")).get(role).equals(d.get("identity")),"Decision role/identity mismatch");
        require(manifest.get("review_id").equals(d.get("review_id")) && hash.equals(d.get("report_sha256")),"Decision report mismatch");
        require(report.get("build").equals(d.get("build")) && report.get("code_sha256").equals(d.get("code_sha256")),"Stale decision build/code identity");
        require(Arrays.equals(bytes(report.get("source_sha256")),bytes(d.get("reviewed_sha256"))),"Reviewed source inventory mismatch");
        if(role.equals("owner")) require(reviewerDigest!=null && reviewerDigest.equals(digest(d.get("reviewer_decision_sha256"))),"Owner decision does not bind the exact reviewer recommendation");
        else require(d.get("reviewer_decision_sha256")==null,"Reviewer decision cannot refer to another recommendation");
        sources.ref(d.get("qualification_evidence")); sources.ref(d.get("independence_evidence"));
        choice(d.get("decision"),role.equals("owner") ? new String[]{"ACCEPT_RECOMMENDATION","REJECT_RECOMMENDATION"} : new String[]{"RECOMMEND_REVIEW","REJECT","RESERVATIONS"});
        for(Object s:list(d.get("limitations"))) str(s); for(Object s:list(d.get("reservations"))) str(s);
        boolean signed=G6History.signature(d);
        return Map.of("role",role,"identity",d.get("identity"),"decision",d.get("decision"),"cryptographic_signature_valid",signed,
                "independently_authenticated",false,"qualification_independently_verified",false,"decision_verified",false,
                "limitations",d.get("limitations"),"reservations",d.get("reservations"));
    }
    static void revocation(Map<String,Object> subject,String reviewId,String reportHash) throws IOException {
        keys(subject,"schema","review_id","reason","report_sha256","signature"); schema(subject,"revocation");
        require(reviewId.equals(subject.get("review_id")) && Objects.equals(reportHash,subject.get("report_sha256")),"Revocation review/report binding mismatch");
        str(subject,"reason"); G6History.signature(subject);
    }
    /** Append by publishing a NEW immutable snapshot, never rewriting the prior history. */
    public static void append(Path history,Path eventFile,Path destination) throws IOException {
        byte[] original=read(history); Map<String,Object> event=parse(read(eventFile)); String id=str(event,"review_id");
        G6History.Checked checked=G6History.check(parse(original),id);
        require(Arrays.equals(original,bytes(parse(original))),"Append requires canonical immutable input history");
        byte[] data=bytes(G6History.extend(checked,event,id));
        Path target=destination.toAbsolutePath().normalize(); noLinks(target);
        require(!Files.exists(target,LinkOption.NOFOLLOW_LINKS),"History extension must use a new path");
        Path parent=target.getParent(); require(parent!=null,"History destination needs parent"); Files.createDirectories(parent); noLinks(parent);
        // A create-new file is never a mutable shared journal. Force to disk before it is consumed.
        // Publish through a directory generation to preserve atomic visibility of the entire extension.
        publishFiles(history.toAbsolutePath().getParent(),target,Map.of("history.json",data));
    }
}
