package com.mscient.intrapaper.permission.coverage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import static com.mscient.intrapaper.permission.coverage.CoverageJson.*;

/** Atomic content addressing and recomputation verification. No raw inputs are published. */
public final class PermissionCoverageArtifacts {
    private PermissionCoverageArtifacts() { }
    public static Path publish(Path root, Map<String, Object> report) throws IOException {
        CoverageSchemas.validate("report", report);
        validateHash(report);
        root = root.toAbsolutePath().normalize(); requireLocal(root); Files.createDirectories(root);
        String id = str(report, "report_sha256"); Path target = root.resolve(id + ".json"), lockPath = root.resolve(".coverage-publish.lock");
        requireLocal(lockPath);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new IOException("PUBLICATION_BUSY"); }
            if (lock == null) throw new IOException("PUBLICATION_BUSY");
            try (lock) {
                byte[] bytes = bytes(report);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    if (!Arrays.equals(readReport(target), bytes)) throw new IOException("EXISTING_ARTIFACT_MISMATCH");
                    return target;
                }
                Path staging = Files.createTempFile(root, ".coverage-staging-", ".tmp");
                try {
                    try (FileChannel out = FileChannel.open(staging, StandardOpenOption.WRITE)) {
                        ByteBuffer buffer = ByteBuffer.wrap(bytes);
                        while (buffer.hasRemaining()) out.write(buffer);
                        out.force(true);
                    }
                    // Deliberately no fallback: an unsupported atomic move fails publication.
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                } finally { Files.deleteIfExists(staging); }
                return target;
            }
        }
    }
    private static void validateHash(Map<String, Object> report) throws IOException {
        Map<String, Object> payload = new TreeMap<>(report); Object declared = payload.remove("report_sha256");
        if (!hash(bytes(payload)).equals(declared)) throw new IOException("REPORT_HASH_MISMATCH");
    }
    /** Verifies even INCOMPLETE reports; verified means reproducible evidence comparison, never approval. */
    public static Map<String, Object> verify(Path reportPath, Path dataset, Path intent, Path register) throws IOException {
        byte[] actual = readReport(reportPath); Object parsed = parse(actual); CoverageSchemas.validate("report", parsed);
        Map<String, Object> report = map(parsed); validateHash(report);
        if (!reportPath.getFileName().toString().equals(str(report, "report_sha256") + ".json")) throw new IOException("REPORT_NAME_MISMATCH");
        if (!Arrays.equals(actual, bytes(report))) throw new IOException("NONCANONICAL_REPORT");
        Map<String, Object> recomputed = PermissionCoverage.check(dataset, intent, register);
        if (!Arrays.equals(actual, bytes(recomputed))) throw new IOException("INPUT_OR_PROVENANCE_MISMATCH");
        return report;
    }
}
