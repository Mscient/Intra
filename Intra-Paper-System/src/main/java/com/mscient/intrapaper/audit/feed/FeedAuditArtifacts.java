package com.mscient.intrapaper.audit.feed;

import static com.mscient.intrapaper.audit.feed.AuditJson.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Atomic directory publication; verification checks schema, hashes, inputs, code, and exact recomputation. */
public final class FeedAuditArtifacts {
    private FeedAuditArtifacts() { }
    public static String codeHash() throws IOException {
        try {
            Path location = Path.of(FeedAudit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            TreeMap<String, String> classes = new TreeMap<>();
            if (Files.isDirectory(location)) {
                try (var paths = Files.walk(location.resolve("com/mscient/intrapaper"))) {
                    for (Path p : paths.filter(p -> p.toString().endsWith(".class")).toList())
                        classes.put(location.relativize(p).toString().replace('\\', '/'), hash(Files.readAllBytes(p)));
                }
            } else try (JarFile jar = new JarFile(location.toFile())) {
                for (var entry : Collections.list(jar.entries())) if (entry.getName().startsWith("com/mscient/intrapaper/") && entry.getName().endsWith(".class")) {
                    try (InputStream in = jar.getInputStream(entry)) { classes.put(entry.getName(), hash(in.readAllBytes())); }
                }
            }
            if (classes.isEmpty()) throw new IOException("Cannot fingerprint audit code");
            return hash(bytes(classes));
        } catch (java.net.URISyntaxException e) { throw new IOException("Cannot identify code location", e); }
    }
    public static void publish(Path manifest, Path destination, Map<String, Object> report) throws IOException {
        Path output = destination.toAbsolutePath().normalize(); Path bundle = manifest.toAbsolutePath().normalize().getParent().toRealPath();
        Path parent = output.getParent();
        if (parent == null || output.startsWith(bundle)) throw new IOException("Output must be outside the input bundle");
        rejectLinks(parent); Files.createDirectories(parent);
        if (parent.toRealPath().startsWith(bundle)) throw new IOException("Output resolves inside input bundle");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Output already exists; use a new artifact directory");
        Path stage = Files.createTempDirectory(parent, ".feed-audit-stage-");
        try {
            byte[] data = bytes(report);
            writeForced(stage.resolve("report.json"), data);
            writeForced(stage.resolve("artifact.json"), bytes(Map.of("schema", "feed-audit-artifact/v1", "report_sha256", hash(data),
                    "code_sha256", report.get("code_sha256"), "audit_version", FeedAudit.VERSION)));
            // No non-atomic fallback and no replacement of an existing result.
            Files.move(stage, output, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(stage)) {
                Files.deleteIfExists(stage.resolve("report.json")); Files.deleteIfExists(stage.resolve("artifact.json")); Files.delete(stage);
            }
        }
    }
    private static void writeForced(Path file, byte[] bytes) throws IOException {
        Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) { channel.force(true); }
    }
    private static void rejectLinks(Path path) throws IOException {
        for (Path p = path; p != null; p = p.getParent()) if (Files.isSymbolicLink(p)
                || Files.exists(p, LinkOption.NOFOLLOW_LINKS) && Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).isOther()) throw new IOException("Linked/reparse artifact paths are unsupported");
    }
    public static void verify(Path manifest, Path directory) throws IOException {
        Path dir = directory.toAbsolutePath().normalize(); rejectLinks(dir);
        try (var files = Files.list(dir)) {
            if (!files.map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(Set.of("report.json", "artifact.json")))
                throw new IOException("Artifact requires exactly report.json and artifact.json");
        }
        rejectLinks(dir.resolve("report.json")); rejectLinks(dir.resolve("artifact.json"));
        Map<String, Object> artifact = parse(dir.resolve("artifact.json"));
        keys(artifact, "schema", "report_sha256", "code_sha256", "audit_version"); version(artifact, "feed-audit-artifact/v1");
        byte[] data = Files.readAllBytes(dir.resolve("report.json"));
        if (!digest(artifact.get("report_sha256")).equals(hash(data))) throw new IOException("Report hash mismatch");
        if (!FeedAudit.VERSION.equals(str(artifact, "audit_version")) || !codeHash().equals(digest(artifact.get("code_sha256"))))
            throw new IOException("Audit code/version mismatch");
        Map<String, Object> report = parse(dir.resolve("report.json"));
        keys(report, "schema", "audit_version", "code_sha256", "schedule_id", "schedule_revision", "input_sha256", "scopes", "status", "limits", "rows");
        version(report, "feed-audit-report/v1");
        // Equality to a newly validated report also rechecks every nested row field and reason code.
        if (!Arrays.equals(data, bytes(FeedAudit.run(manifest)))) throw new IOException("Report schema/content does not match deterministic re-audit");
        if (!Arrays.equals(bytes(artifact), Files.readAllBytes(dir.resolve("artifact.json")))) throw new IOException("Noncanonical artifact manifest");
    }
}
