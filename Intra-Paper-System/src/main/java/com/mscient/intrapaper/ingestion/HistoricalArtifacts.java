package com.mscient.intrapaper.ingestion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import static java.nio.file.StandardOpenOption.*;
import static com.mscient.intrapaper.ingestion.IngestionIO.*;

/** Atomic flat bundles. Complete means published diagnostics, never permission or data readiness. */
final class HistoricalArtifacts {
    static final String VERSION="historical-ingestion/1.0.0";
    private HistoricalArtifacts() { }
    static Map<String,Object> software() throws IOException {
        Map<String,Object> classes=new TreeMap<>(); String commit="SOURCE_CLASSES";
        Path location;
        try { location=Path.of(HistoricalArtifacts.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch(Exception e) { throw new IOException("CODE_IDENTITY_UNAVAILABLE"); }
        if(Files.isDirectory(location)) {
            try(var entries=Files.walk(location.resolve("com/mscient/intrapaper"))) {
                for(Path p:entries.filter(x->x.toString().endsWith(".class")).sorted().toList())
                    classes.put(location.relativize(p).toString().replace('\\','/'),hash(Files.readAllBytes(p)));
            }
        } else try(JarFile jar=new JarFile(location.toFile())) {
            commit=jar.getManifest().getMainAttributes().getValue("Build-Commit");
            for(var entry:jar.stream().filter(e->e.getName().startsWith("com/mscient/intrapaper/") && e.getName().endsWith(".class")).toList())
                try(var in=jar.getInputStream(entry)) { classes.put(entry.getName(),hash(in.readAllBytes())); }
        }
        require(!classes.isEmpty(),"CODE_IDENTITY_UNAVAILABLE");
        return obj("version",VERSION,"build_commit",commit==null?"UNKNOWN":commit,"class_tree_sha256",hash(bytes(classes)),"java_release",21);
    }
    static Map<String,byte[]> finish(Map<String,byte[]> files, Map<String,Object> manifest) {
        Map<String,Object> hashes=new TreeMap<>(); files.forEach((k,v)->hashes.put(k,hash(v)));
        manifest.put("files",hashes);
        files.put("manifest.json",bytes(manifest));
        files.put("complete.json",bytes(obj("schema_version","historical-bundle/v1","manifest_sha256",hash(files.get("manifest.json")))));
        return files;
    }
    static void verify(Path output, Map<String,byte[]> expected) throws IOException {
        local(output); require(Files.isDirectory(output,LinkOption.NOFOLLOW_LINKS),"BUNDLE_MISSING");
        try(var entries=Files.list(output)) {
            require(entries.map(p->p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(expected.keySet()),"BUNDLE_FILE_SET_MISMATCH");
        }
        for(var e:expected.entrySet()) require(Arrays.equals(read(output.resolve(e.getKey()),128*1024*1024),e.getValue()),"BUNDLE_REPRODUCTION_MISMATCH");
    }
    @FunctionalInterface interface BeforeMove { void run(Path stage) throws IOException; }
    static void publish(Path output,Map<String,byte[]> files) throws IOException { publish(output,files,stage->{ }); }
    // Package-private fault injection exercises the real publication boundary without a CLI bypass.
    static void publish(Path output,Map<String,byte[]> files,BeforeMove beforeMove) throws IOException {
        Path target=local(output); Path parent=target.getParent();
        require(parent!=null && Files.isDirectory(parent),"OUTPUT_PARENT_MISSING");
        Path lock=parent.resolve(".historical-"+hash(target.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))+".lock");
        local(lock);
        try(FileChannel channel=FileChannel.open(lock,CREATE,WRITE); var held=channel.lock()) {
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) { verify(target,files); return; }
            Path stage=Files.createTempDirectory(parent,".historical-stage-"); boolean moved=false;
            try {
                for(var e:files.entrySet()) {
                    require(e.getKey().matches("[A-Za-z0-9._-]+"),"ARTIFACT_NAME_INVALID");
                    try(FileChannel file=FileChannel.open(stage.resolve(e.getKey()),CREATE_NEW,WRITE)) {
                        ByteBuffer b=ByteBuffer.wrap(e.getValue()); while(b.hasRemaining()) file.write(b); file.force(true);
                    }
                }
                beforeMove.run(stage);
                Files.move(stage,target,StandardCopyOption.ATOMIC_MOVE); moved=true;
            } finally { if(!moved) removeStage(stage); }
        }
    }
}
