package com.mscient.intrapaper.ops.operationaldrill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.mscient.intrapaper.ops.operationaldrill.OperationalScenario.Status;

/** Public offline API. Verification trusts an independently retained root, then regenerates. */
public final class OperationalDrill {
    public record WrittenReport(Path path, String manifestSha256, Status status) { }
    private OperationalDrill() { }
    public static WrittenReport run(Path scenario, Path output) throws Exception {
        OperationalScenario loaded = OperationalScenario.load(scenario);
        Path root = output.toAbsolutePath().normalize(); DrillFiles.noLinks(root); Files.createDirectories(root);
        SortedMap<String, byte[]> files = generate(loaded, root);
        String id = DrillFiles.sha(files.get("manifest.properties"));
        Path target = root.resolve(id);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        Path staging = Files.createTempDirectory(root, ".operational-publish-");
        DrillFiles.materialize(staging, files);
        // No replacement of an existing bundle. Incomplete staging never has the final hash name.
        Files.move(staging, target);
        return new WrittenReport(target, id, status(files));
    }
    public static Status verify(Path bundle, String expectedHash) throws Exception {
        if (!expectedHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Independently retained lowercase SHA-256 required");
        SortedMap<String, byte[]> saved = DrillFiles.snapshot(bundle.toAbsolutePath().normalize());
        byte[] root = saved.get("manifest.properties");
        if (root == null || !DrillFiles.sha(root).equals(expectedHash)) throw new IOException("Manifest trust-anchor mismatch");
        TreeMap<String, byte[]> payload = new TreeMap<>(saved); payload.remove("manifest.properties");
        if (!Arrays.equals(root, manifest(payload))) throw new IOException("Artifact file set/hash mismatch");
        byte[] scenario = saved.get("scenario.drill");
        if (scenario == null) throw new IOException("Missing frozen scenario");
        // Verify uses a new independent workspace, never writes into the retained bundle.
        Path temporary = Files.createTempDirectory("operational-verification-");
        try {
            SortedMap<String, byte[]> rebuilt = generate(OperationalScenario.parse(scenario), temporary);
            if (!saved.keySet().equals(rebuilt.keySet())) throw new IOException("Regenerated artifact set differs");
            for (String name : saved.keySet()) if (!Arrays.equals(saved.get(name), rebuilt.get(name)))
                throw new IOException("Regenerated report differs: " + name);
            return status(rebuilt);
        } finally { DrillFiles.removeOwned(temporary); }
    }
    private static SortedMap<String, byte[]> generate(OperationalScenario scenario, Path root) throws Exception {
        if (Runtime.version().feature() != 21) throw new IllegalArgumentException("Java 21 required");
        Path work = Files.createTempDirectory(root, ".operational-work-").toAbsolutePath();
        boolean complete = false;
        try {
            DrillFiles.write(work.resolve("scenario.drill"), scenario.bytes());
            Path log = work.resolve("worker.log");
            int exit = DrillProcesses.run(OperationalDrillWorker.class, work, log, work.toString());
            if (exit != 0) throw new IOException("Worker failed (exit " + exit + "); diagnostic workspace retained at " + work);
            SortedMap<String, byte[]> files = DrillFiles.snapshot(work.resolve("evidence"));
            files.put("worker.log", DrillFiles.utf8(new String(DrillFiles.read(log, DrillFiles.MAX_FILE), StandardCharsets.UTF_8).replace("\r\n", "\n")));
            files.put("manifest.properties", manifest(files));
            if (files.values().stream().mapToLong(b -> b.length).sum() > DrillFiles.MAX_BUNDLE) throw new IOException("Oversized evidence");
            complete = true;
            return files;
        } finally { if (complete) DrillFiles.removeOwned(work); }
    }
    static byte[] manifest(Map<String, byte[]> files) {
        StringBuilder text = new StringBuilder("format=OFFLINE_OPERATIONAL_BUNDLE_V1\n");
        new TreeMap<>(files).forEach((name, bytes) -> text.append(name).append('=').append(DrillFiles.sha(bytes)).append('\n'));
        return DrillFiles.utf8(text.toString());
    }
    private static Status status(Map<String, byte[]> files) throws IOException {
        byte[] bytes = files.get("report.properties");
        if (bytes == null) throw new IOException("Missing report");
        List<String> statuses = new String(bytes, StandardCharsets.UTF_8).lines().filter(s -> s.startsWith("status=")).toList();
        if (statuses.size() != 1) throw new IOException("Invalid report status");
        return Status.valueOf(statuses.getFirst().substring(7));
    }
}
