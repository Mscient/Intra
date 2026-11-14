package com.mscient.intrapaper.ops.drill;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.riskgateway.RiskGateway;
import com.mscient.intrapaper.simulation.portfolio.PortfolioExecution;
import com.mscient.intrapaper.simulation.portfolio.PortfolioReplayEngine.OrderIntent;
import com.mscient.intrapaper.simulation.portfolio.risk.OfflinePortfolioRiskAdapter;
import com.mscient.intrapaper.simulation.portfolio.risk.PortfolioRiskInputs;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Content-addressed local publication and verification by exact deterministic regeneration. */
public final class DrillEvidence {
    private DrillEvidence() { }
    public static Map<String, byte[]> bundle(IncidentDrillOrchestrator.Result result) throws IOException {
        Map<String, byte[]> files = new TreeMap<>(result.files());
        StringBuilder manifest = new StringBuilder("format=OFFLINE_INCIDENT_MANIFEST_V1\nimplementation_sha256=" + implementationHash() + "\n");
        files.forEach((name, bytes) -> manifest.append("sha256.").append(name).append('=').append(sha(bytes)).append('\n'));
        files.put("manifest.properties", manifest.toString().getBytes(StandardCharsets.UTF_8));
        if (files.values().stream().mapToLong(a -> a.length).sum() > 128L * 1024 * 1024)
            throw new IllegalArgumentException("Evidence exceeds 128 MiB");
        return files;
    }
    public static Path publish(IncidentDrillOrchestrator.Result result, Path outputRoot) throws IOException {
        Map<String, byte[]> files = bundle(result);
        String id = sha(files.get("manifest.properties"));
        Files.createDirectories(outputRoot);
        Path target = outputRoot.resolve(id);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(target.toString());
        Path stage = Files.createTempDirectory(outputRoot, ".drill-staging-");
        // Failed staging is retained for diagnosis; an incomplete directory is never published as a bundle.
        for (var entry : files.entrySet()) {
            Path file = stage.resolve(entry.getKey()); Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue(), StandardOpenOption.CREATE_NEW);
        }
        // Directory move without REPLACE_EXISTING preserves concurrent/no-overwrite safety on supported local filesystems.
        Files.move(stage, target);
        return target;
    }
    public static IncidentDrillOrchestrator.Status verify(Path bundle, String expectedManifestSha256) throws IOException {
        if (!expectedManifestSha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected lowercase SHA-256 required");
        Path manifest = bundle.resolve("manifest.properties");
        if (Files.isSymbolicLink(bundle) || Files.isSymbolicLink(manifest) || Files.size(manifest) > 65536
                || !sha(Files.readAllBytes(manifest)).equals(expectedManifestSha256))
            throw new IllegalArgumentException("Manifest integrity mismatch");
        if (Files.isSymbolicLink(bundle.resolve("scenario.drill"))) throw new IllegalArgumentException("Linked scenario rejected");
        var result = IncidentDrillOrchestrator.run(DrillScenario.load(bundle.resolve("scenario.drill")));
        Map<String, byte[]> expected = bundle(result);
        Set<String> actual = new TreeSet<>();
        Set<String> directories = new TreeSet<>();
        expected.keySet().forEach(name -> { if (name.contains("/")) directories.add(name.substring(0, name.lastIndexOf('/'))); });
        try (var paths = Files.walk(bundle)) {
            for (Path file : paths.toList()) {
                if (file.equals(bundle)) continue;
                String relative = bundle.relativize(file).toString().replace('\\', '/');
                if (Files.isSymbolicLink(file)) throw new IllegalArgumentException("Linked artifact rejected");
                if (Files.isDirectory(file)) {
                    if (!directories.contains(relative)) throw new IllegalArgumentException("Unexpected artifact directory");
                } else actual.add(relative);
            }
        }
        if (!actual.equals(expected.keySet())) throw new IllegalArgumentException("Artifact file set mismatch");
        for (var e : expected.entrySet()) {
            Path file = bundle.resolve(e.getKey());
            if (Files.size(file) != e.getValue().length || !Arrays.equals(Files.readAllBytes(file), e.getValue()))
                throw new IllegalArgumentException("Regenerated evidence differs: " + e.getKey());
        }
        return result.status();
    }
    public static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String csv(String... values) {
        return Arrays.stream(values).map(CsvTable::escape).collect(java.util.stream.Collectors.joining(","));
    }
    private static String implementationHash() throws IOException {
        Map<String, Class<?>> classes = new TreeMap<>();
        for (Class<?> c : List.of(DrillScenario.class, IncidentDrillOrchestrator.class, DrillEvidence.class,
                IncidentDrillCli.class, OfflinePortfolioRiskAdapter.class, PortfolioRiskInputs.class,
                RiskGateway.class, OrderIntent.class, PortfolioExecution.class, CsvTable.class, RunConfig.class)) collect(c, classes);
        StringBuilder identity = new StringBuilder();
        for (var e : classes.entrySet()) {
            try (var stream = e.getValue().getResourceAsStream("/" + e.getKey().replace('.', '/') + ".class")) {
                if (stream == null) throw new IOException("Implementation class unavailable: " + e.getKey());
                identity.append(e.getKey()).append('=').append(sha(stream.readAllBytes())).append('\n');
            }
        }
        return sha(identity.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static void collect(Class<?> c, Map<String, Class<?>> classes) {
        if (classes.putIfAbsent(c.getName(), c) == null) for (Class<?> child : c.getDeclaredClasses()) collect(child, classes);
    }
}
