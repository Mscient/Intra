package com.mscient.intrapaper.audit.reconciliation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import static com.mscient.intrapaper.audit.reconciliation.LedgerSupport.*;

/** Canonical manifests, exact layouts and atomic, content-addressed publication of separate sidecars. */
public final class LedgerAuditArtifacts {
    static final String MANIFEST = "audit-manifest.properties";
    static final List<String> FILES = List.of("comparisons.csv", "coverage.csv", "events.csv", "exceptions.csv", "inputs.csv", "summary.properties", "totals.csv");
    private LedgerAuditArtifacts() { }

    static byte[] manifest(Map<String, byte[]> files) {
        if (!files.keySet().equals(new TreeSet<>(FILES))) throw new IllegalArgumentException("Unexpected audit output layout");
        StringBuilder out = new StringBuilder("schema_version=1\nclassification=OFFLINE_EXTERNAL_LEDGER_RECONCILIATION\noutput_files=")
                .append(String.join(",", FILES)).append('\n');
        for (String name : FILES) out.append("sha256.").append(name).append('=').append(hash(files.get(name))).append('\n');
        return utf8(out.toString());
    }

    static ExternalLedgerAudit.WrittenAudit write(Path output, ExternalLedgerAudit.Prepared prepared) throws IOException {
        Path root = output.toAbsolutePath().normalize(); Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) throw new IOException("Audit root must be a real directory");
        byte[] manifest = manifest(prepared.files()); String id = hash(manifest);
        Path destination = root.resolve(id), lockPath = root.resolve(".ledger-audit-lock-" + id);
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) readFile(lockPath);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock;
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException busy) { throw new IOException("Audit publication is already active", busy); }
            if (lock == null) throw new IOException("Audit publication is already active");
            try (lock) {
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return verify(destination);
                Path staging = Files.createTempDirectory(root, ".ledger-audit-staging-"); boolean published = false;
                try {
                    for (String name : FILES) writeNew(staging.resolve(name), prepared.files().get(name));
                    writeNew(staging.resolve(MANIFEST), manifest);
                    verifyFiles(staging, id);
                    Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE); published = true;
                    return new ExternalLedgerAudit.WrittenAudit(id, destination, prepared.status());
                } finally { if (!published) cleanup(staging); }
            }
        }
    }

    /** Integrity only: does not authenticate caller records or reconstruct a replay. */
    public static ExternalLedgerAudit.WrittenAudit verify(Path folder) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        String id = root.getFileName().toString();
        if (!id.matches("[0-9a-f]{64}")) throw new IOException("Audit folder name must equal its manifest SHA-256");
        ExternalLedgerAudit.Outcome status = verifyFiles(root, id);
        return new ExternalLedgerAudit.WrittenAudit(id, root, status);
    }
    private static ExternalLedgerAudit.Outcome verifyFiles(Path root, String id) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) throw new IOException("Audit must be a real directory");
        byte[] saved = readFile(root.resolve(MANIFEST));
        if (!hash(saved).equals(id)) throw new IOException("Audit manifest/folder hash mismatch");
        var files = new TreeMap<String, byte[]>();
        Set<String> names = new TreeSet<>(FILES); names.add(MANIFEST);
        try (var paths = Files.list(root)) {
            if (!new TreeSet<>(paths.map(p -> p.getFileName().toString()).toList()).equals(names)) throw new IOException("Missing or unexpected audit files");
        }
        for (String name : FILES) files.put(name, readFile(root.resolve(name)));
        if (!Arrays.equals(saved, manifest(files))) throw new IOException("Audit output hashes or canonical manifest differ");
        Map<String, String> summary = properties(files.get("summary.properties"));
        if (!"1".equals(summary.get("schema_version")) || !"OFFLINE_EXTERNAL_LEDGER_RECONCILIATION".equals(summary.get("classification")))
            throw new IOException("Invalid audit summary contract");
        try { return ExternalLedgerAudit.Outcome.valueOf(required(summary, "status")); }
        catch (RuntimeException invalid) { throw new IOException("Invalid audit status", invalid); }
    }
    private static void writeNew(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
    }
    private static void cleanup(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(absolute)) throw new IOException("Unexpected audit staging path");
        try (var paths = Files.walk(absolute)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(absolute)) throw new IOException("Cleanup escaped audit staging root");
                Files.delete(path);
            }
        }
    }
}
