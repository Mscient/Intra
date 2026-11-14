package com.mscient.intrapaper.ops.evidence;

import com.mscient.intrapaper.data.StrictJsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reads each declared local file once; parsing and hashing consume the same immutable byte snapshot. */
final class SessionEvidenceBundle {
    private static final long MAX_FILE_BYTES = 16 * 1024 * 1024;
    final Path manifest;
    final Map<String, String> hashes = new TreeMap<>();
    final Map<String, byte[]> files = new TreeMap<>();
    private final Map<String, Path> resolvedArtifacts = new TreeMap<>();
    final DailyOpsEvidenceReport.Check integrity = new DailyOpsEvidenceReport.Check("replay_run_identity_artifact_integrity");
    Map<String, Object> root;

    private SessionEvidenceBundle(Path manifest) { this.manifest = manifest.toAbsolutePath().normalize(); }

    static SessionEvidenceBundle load(Path path) {
        SessionEvidenceBundle bundle = new SessionEvidenceBundle(path);
        byte[] bytes = bundle.read(bundle.manifest, "bundle");
        if (bytes == null) return bundle;
        try { bundle.root = object(parse(bytes)); }
        catch (IOException invalid) {
            bundle.integrity.fail("bundle", "Malformed UTF-8/JSON evidence: " + invalid.getMessage());
            return bundle;
        }
        if (bundle.root == null) {
            bundle.integrity.fail("bundle", "Evidence bundle must be a JSON object");
            return bundle;
        }
        Map<String, Object> run = object(bundle.root.get("run"));
        if (run != null && run.get("artifacts") instanceof List<?> artifacts) {
            if (artifacts.size() > 256) {
                bundle.integrity.fail("run.artifacts", "More than 256 artifact declarations");
                return bundle;
            }
            for (int i = 0; i < artifacts.size(); i++) {
                Map<String, Object> artifact = object(artifacts.get(i));
                String field = "run.artifacts[" + i + "]";
                if (artifact == null) continue; // The typed checker records missing/invalid declarations.
                Object name = artifact.get("path");
                if (!(name instanceof String relative) || relative.isBlank()) continue;
                if (bundle.hashes.containsKey(relative)) {
                    bundle.integrity.fail(field + ".path", "Duplicate artifact path");
                    continue;
                }
                Path artifactPath = bundle.localPath(relative, field + ".path");
                byte[] content = artifactPath == null ? null : bundle.read(artifactPath, relative);
                if (content != null) bundle.files.put(relative, content);
                Object expected = artifact.get("sha256");
                if (expected == null || expected.equals("")) bundle.integrity.missing(field + ".sha256");
                else if (!(expected instanceof String hash) || !hash.matches("[0-9a-f]{64}")) {
                    bundle.integrity.fail(field + ".sha256", "Expected SHA-256 must contain 64 lowercase hexadecimal digits");
                } else if (content != null && !expected.equals(bundle.hashes.get(relative))) {
                    bundle.integrity.fail(field + ".sha256", "Artifact SHA-256 mismatch: " + relative);
                }
            }
        }
        return bundle;
    }

    private Path localPath(String relative, String field) {
        hashes.put(relative, null);
        if (!relative.matches("[A-Za-z0-9][A-Za-z0-9._/-]*") || relative.equals("bundle")
                || List.of(relative.split("/", -1)).stream().anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
            integrity.fail(field, "Artifact path must be a distinct portable relative local path without traversal");
            return null;
        }
        Path rootPath = manifest.getParent();
        Path resolved = rootPath.resolve(relative).normalize();
        if (resolved.equals(manifest) || !resolved.startsWith(rootPath)) {
            integrity.fail(field, "Artifact path aliases or escapes the bundle");
            return null;
        }
        try {
            Path step = rootPath;
            for (Path component : rootPath.relativize(resolved)) {
                step = step.resolve(component);
                if (Files.isSymbolicLink(step)) {
                    integrity.fail(field, "Symbolic-link artifacts are not supported");
                    return null;
                }
            }
            if (Files.exists(resolved) && !resolved.toRealPath().startsWith(rootPath.toRealPath())) {
                integrity.fail(field, "Artifact resolves outside the bundle directory");
                return null;
            }
            for (var prior : resolvedArtifacts.entrySet()) {
                if (relative.equalsIgnoreCase(prior.getKey()) || resolved.equals(prior.getValue())
                        || Files.exists(resolved) && Files.exists(prior.getValue()) && Files.isSameFile(resolved, prior.getValue())) {
                    integrity.fail(field, "Artifact aliases another declared file: " + prior.getKey());
                    return null;
                }
            }
            resolvedArtifacts.put(relative, resolved);
        } catch (IOException unavailable) {
            integrity.incomplete(field, "UNKNOWN: artifact path cannot be resolved");
            return null;
        }
        return resolved;
    }

    private byte[] read(Path path, String name) {
        hashes.put(name, null);
        try {
            if (Files.isSymbolicLink(path)) {
                integrity.fail(name, "Symbolic-link evidence is not supported");
                return null;
            }
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                integrity.incomplete(name, "UNKNOWN: required evidence file is missing or is not a regular file");
                return null;
            }
            if (Files.size(path) > MAX_FILE_BYTES) {
                integrity.fail(name, "Evidence file exceeds the 16 MiB diagnostic limit");
                return null;
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > MAX_FILE_BYTES) {
                integrity.fail(name, "Evidence file exceeds the 16 MiB diagnostic limit");
                return null;
            }
            hashes.put(name, hash(bytes));
            return bytes;
        } catch (NoSuchFileException absent) {
            integrity.missing(name);
        } catch (IOException unavailable) {
            integrity.incomplete(name, "UNKNOWN: required evidence file cannot be read");
        }
        return null;
    }

    static Object parse(byte[] bytes) throws IOException {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        return StrictJsonParser.parse(text);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) { return value instanceof Map<?, ?> ? (Map<String, Object>) value : null; }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
