package com.mscient.intrapaper.simulation.portfolio.risk;

import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.simulation.portfolio.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.mscient.intrapaper.simulation.portfolio.risk.PortfolioRiskInputs.*;

/** Standalone local-file replay + risk evidence bundle. No network or order submission capability. */
public final class PortfolioRiskReplayRunner {
    private static final List<String> EVIDENCE = List.of("risk-decisions.csv", "risk-events.csv", "risk-checkpoint.txt");
    private static final List<String> KEYS = List.of("schema_version", "replay_id", "risk_inputs_sha256",
            "replay_manifest_sha256", "margin_basis", "sha256.risk-decisions.csv", "sha256.risk-events.csv", "sha256.risk-checkpoint.txt");
    public record Bundle(String bundleId, String replayId, Path path) { }
    private PortfolioRiskReplayRunner() { }

    public static Bundle run(Path inputManifest, Path properties, PortfolioRiskInputs inputs, Path outputRoot) throws IOException {
        OfflinePortfolioRiskAdapter adapter = new OfflinePortfolioRiskAdapter(inputs);
        var replay = PortfolioReplayCsvRunner.replay(inputManifest, properties, model -> new PortfolioRiskFillModel(model, adapter));
        if (adapter.decisions().size() != replay.result().orders().size()) throw new IOException("Every generated intent requires exactly one risk decision");
        Path root = outputRoot.toAbsolutePath().normalize(); Files.createDirectories(root); realDirectory(root);
        Path staging = Files.createTempDirectory(root, ".risk-staging-");
        try {
            var saved = new PortfolioReplayArtifactWriter().write(staging.resolve("replay"), replay.dataset(), replay.result());
            Files.delete(staging.resolve("replay/.portfolio-publish-lock-" + saved.runId()));
            Files.writeString(staging.resolve("risk-decisions.csv"), adapter.decisionsCsv(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.writeString(staging.resolve("risk-events.csv"), adapter.traceCsv(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.write(staging.resolve("risk-checkpoint.txt"), adapter.checkpoint(), StandardOpenOption.CREATE_NEW);
            Map<String, String> values = new LinkedHashMap<>();
            values.put("schema_version", "1"); values.put("replay_id", saved.runId()); values.put("risk_inputs_sha256", inputs.fingerprint());
            values.put("replay_manifest_sha256", fileHash(saved.path().resolve("run-manifest.properties")));
            values.put("margin_basis", "CALLER_SUPPLIED_PROXY_NOT_SPAN_ELM");
            for (String name : EVIDENCE) values.put("sha256." + name, fileHash(staging.resolve(name)));
            String manifest = manifest(values); String id = sha(manifest);
            Files.writeString(staging.resolve("risk-manifest.properties"), manifest, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Path destination = root.resolve(id);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(destination.toString());
            Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
            return new Bundle(id, saved.runId(), destination);
        } finally { removeOwnedTree(staging); }
    }
    /** Integrity verification is separate from regeneration. A digest is not an authenticated source signature. */
    public static Bundle verifyArtifact(Path folder) throws IOException {
        Path root = folder.toAbsolutePath().normalize(); realDirectory(root);
        String id = root.getFileName().toString(); if (!id.matches("[0-9a-f]{64}")) throw new IOException("Invalid risk bundle folder ID");
        List<String> expected = new ArrayList<>(EVIDENCE); expected.addAll(List.of("risk-manifest.properties", "replay"));
        requireChildren(root, expected);
        Path manifestPath = root.resolve("risk-manifest.properties"); realFile(manifestPath);
        String content = Files.readString(manifestPath, StandardCharsets.UTF_8);
        if (!id.equals(sha(content))) throw new IOException("Risk bundle manifest hash mismatch");
        Map<String, String> values = properties(content);
        if (!new ArrayList<>(values.keySet()).equals(KEYS) || !manifest(values).equals(content)
                || !values.get("schema_version").equals("1") || !values.get("margin_basis").equals("CALLER_SUPPLIED_PROXY_NOT_SPAN_ELM"))
            throw new IOException("Invalid risk manifest schema");
        for (String key : KEYS) if (!List.of("schema_version", "margin_basis").contains(key) && !values.get(key).matches("[0-9a-f]{64}"))
            throw new IOException("Invalid digest: " + key);
        for (String name : EVIDENCE) if (!fileHash(root.resolve(name)).equals(values.get("sha256." + name))) throw new IOException("Risk evidence hash mismatch: " + name);
        Path replayRoot = root.resolve("replay"); realDirectory(replayRoot); requireChildren(replayRoot, List.of(values.get("replay_id")));
        Path replay = replayRoot.resolve(values.get("replay_id"));
        PortfolioReplayArtifactVerifier.verify(replay);
        Path replayManifest = replay.resolve("run-manifest.properties");
        if (!fileHash(replayManifest).equals(values.get("replay_manifest_sha256"))) throw new IOException("Replay manifest binding mismatch");
        Map<String, String> replayValues = properties(Files.readString(replayManifest));
        String modelId = new String(Base64.getDecoder().decode(replayValues.get("fill_model_id_base64")), StandardCharsets.UTF_8);
        String params = new String(Base64.getDecoder().decode(replayValues.get("fill_model_parameters_base64")), StandardCharsets.UTF_8);
        if (!modelId.equals("OFFLINE_RISK_GATEWAY_IOC_V1") || !values.get("risk_inputs_sha256").equals(canonicalMap(params).get("risk_inputs_sha256")))
            throw new IOException("Replay is not bound to this risk configuration and input set");
        return new Bundle(id, values.get("replay_id"), root);
    }
    public static Bundle verify(Path inputManifest, Path properties, PortfolioRiskInputs inputs, Path folder) throws IOException {
        Bundle saved = verifyArtifact(folder);
        byte[] checkpoint = Files.readAllBytes(saved.path().resolve("risk-checkpoint.txt"));
        var recovered = OfflinePortfolioRiskAdapter.restore(inputs, checkpoint, RunConfig.sha256(checkpoint));
        if (!recovered.decisionsCsv().equals(Files.readString(saved.path().resolve("risk-decisions.csv")))
                || !recovered.traceCsv().equals(Files.readString(saved.path().resolve("risk-events.csv"))))
            throw new IOException("Recovered risk decisions/events differ from evidence");
        Path temp = Files.createTempDirectory("portfolio-risk-verify-");
        try {
            Bundle rebuilt = run(inputManifest, properties, inputs, temp);
            if (!saved.bundleId().equals(rebuilt.bundleId())) throw new IOException("Deterministic risk replay bundle differs");
            try (var paths = Files.walk(saved.path())) {
                for (Path path : paths.filter(Files::isRegularFile).toList())
                    if (Files.mismatch(path, rebuilt.path().resolve(saved.path().relativize(path))) != -1)
                        throw new IOException("Deterministic bytes differ: " + path.getFileName());
            }
            return saved;
        } finally { removeOwnedTree(temp); }
    }
    private static String manifest(Map<String, String> values) {
        StringBuilder text = new StringBuilder(); values.forEach((key, value) -> text.append(key).append('=').append(value).append('\n')); return text.toString();
    }
    private static Map<String, String> canonicalMap(String encoded) throws IOException {
        Map<String, String> values = new TreeMap<>(); int at = 0;
        try {
            while (at < encoded.length()) {
                int colon = encoded.indexOf(':', at); int size = Integer.parseInt(encoded.substring(at, colon));
                String key = encoded.substring(colon + 1, colon + 1 + size); at = colon + 1 + size;
                colon = encoded.indexOf(':', at); size = Integer.parseInt(encoded.substring(at, colon));
                String value = encoded.substring(colon + 1, colon + 1 + size); at = colon + 1 + size;
                if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate key");
            }
        } catch (RuntimeException invalid) { throw new IOException("Invalid replay parameters", invalid); }
        return values;
    }
    private static Map<String, String> properties(String text) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int at = line.indexOf('=');
            if (at < 1 || values.putIfAbsent(line.substring(0, at), line.substring(at + 1)) != null) throw new IOException("Invalid manifest property");
        }
        return values;
    }
    private static void requireChildren(Path root, List<String> names) throws IOException {
        try (var children = Files.list(root)) {
            if (!children.map(p -> p.getFileName().toString()).sorted().toList().equals(names.stream().sorted().toList()))
                throw new IOException("Unexpected or missing risk bundle entries");
        }
    }
    private static void realDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) throw new IOException("Expected real directory: " + path);
    }
    private static void realFile(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) throw new IOException("Expected real file: " + path);
    }
    private static String fileHash(Path path) throws IOException { realFile(path); return RunConfig.sha256(Files.readAllBytes(path)); }
    private static void removeOwnedTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return; realDirectory(root);
        Path absolute = root.toAbsolutePath().normalize();
        try (var paths = Files.walk(absolute)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.toAbsolutePath().normalize().startsWith(absolute) || Files.isSymbolicLink(path)) throw new IOException("Unexpected cleanup path");
                Files.delete(path);
            }
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("verify-artifact")) {
            System.out.println("Verified risk bundle " + verifyArtifact(Path.of(args[1])).bundleId()); return;
        }
        if (args.length != 7 || !List.of("run", "verify").contains(args[0])) throw new IllegalArgumentException(
                "run|verify inputs.csv portfolio.properties limits.csv instruments.csv samples.csv output-root|bundle-folder; or verify-artifact bundle-folder");
        PortfolioRiskInputs inputs = PortfolioRiskInputs.load(Path.of(args[3]), Path.of(args[4]), Path.of(args[5]));
        Bundle bundle = args[0].equals("run") ? run(Path.of(args[1]), Path.of(args[2]), inputs, Path.of(args[6]))
                : verify(Path.of(args[1]), Path.of(args[2]), inputs, Path.of(args[6]));
        System.out.println((args[0].equals("run") ? "Wrote " : "Verified ") + bundle.path());
    }
}
