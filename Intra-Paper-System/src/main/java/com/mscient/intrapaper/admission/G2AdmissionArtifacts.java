package com.mscient.intrapaper.admission;

import com.mscient.intrapaper.audit.feed.FeedAuditArtifacts;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static com.mscient.intrapaper.admission.AdmissionJson.*;

/** Atomic publication and full deterministic reverification for G2 admission reports. */
public final class G2AdmissionArtifacts {
    private G2AdmissionArtifacts() { }

    public static void publish(Path manifestPath, Path destination, G2Admission.Result result) throws IOException {
        G2Admission.Result reproduced = G2Admission.run(manifestPath);
        if (!Arrays.equals(result.bytes(), reproduced.bytes()) || !result.reportId().equals(reproduced.reportId()))
            throw new IOException("Publication requires the current deterministically reproduced report");
        if (result.bytes().length > MAX_REPORT_BYTES) throw new IOException("Admission report exceeds 16 MiB");
        Path manifest = manifestPath.toAbsolutePath().normalize();
        Path bundle = manifest.getParent().toRealPath();
        Path output = destination.toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent == null || output.startsWith(bundle)) throw new IOException("Output must be outside the admission input bundle");
        rejectLinks(parent); Files.createDirectories(parent);
        if (parent.toRealPath().startsWith(bundle)) throw new IOException("Output resolves inside the admission input bundle");
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Output already exists; use a new artifact directory");
        Path stage = Files.createTempDirectory(parent, ".g2-admission-stage-");
        try {
            byte[] report = result.bytes();
            writeForced(stage.resolve("report.json"), report);
            Map<String, Object> artifact = Map.of(
                    "schema", "g2-admission-artifact/v1",
                    "admission_version", G2Admission.VERSION,
                    "report_id", result.reportId(),
                    "report_sha256", hash(report),
                    "code_sha256", FeedAuditArtifacts.codeHash());
            writeForced(stage.resolve("artifact.json"), bytes(artifact));
            Files.move(stage, output, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(stage)) {
                Files.deleteIfExists(stage.resolve("report.json"));
                Files.deleteIfExists(stage.resolve("artifact.json"));
                Files.delete(stage);
            }
        }
    }

    public static void verify(Path manifestPath, Path directory) throws IOException {
        Path dir = directory.toAbsolutePath().normalize(); rejectLinks(dir);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Admission artifact is not a directory");
        try (var files = Files.list(dir)) {
            Set<String> names = files.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
            if (!names.equals(Set.of("report.json", "artifact.json")))
                throw new IOException("Admission artifact requires exactly report.json and artifact.json");
        }
        Path reportPath = dir.resolve("report.json"), artifactPath = dir.resolve("artifact.json");
        rejectLinks(reportPath); rejectLinks(artifactPath);
        byte[] reportBytes = readBounded(reportPath, MAX_REPORT_BYTES);
        Map<String, Object> artifact = parse(artifactPath);
        keys(artifact, "schema", "admission_version", "report_id", "report_sha256", "code_sha256");
        if (!"g2-admission-artifact/v1".equals(string(artifact.get("schema")))) throw new IOException("Unsupported admission artifact schema");
        if (!G2Admission.VERSION.equals(string(artifact.get("admission_version")))) throw new IOException("Admission version mismatch");
        if (!digest(artifact.get("report_sha256")).equals(hash(reportBytes))) throw new IOException("Admission report hash mismatch");
        if (!digest(artifact.get("code_sha256")).equals(FeedAuditArtifacts.codeHash())) throw new IOException("Admission code identity mismatch");
        G2Admission.Result reproduced = G2Admission.run(manifestPath);
        if (!reproduced.reportId().equals(id(artifact.get("report_id"))) || !Arrays.equals(reproduced.bytes(), reportBytes))
            throw new IOException("Admission report does not match deterministic recomputation");
        if (!Arrays.equals(bytes(artifact), readBounded(artifactPath, MAX_MANIFEST_BYTES)))
            throw new IOException("Admission artifact manifest is not canonical");
    }

    private static void writeForced(Path path, byte[] content) throws IOException {
        Files.write(path, content, StandardOpenOption.CREATE_NEW);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) { channel.force(true); }
    }
}
