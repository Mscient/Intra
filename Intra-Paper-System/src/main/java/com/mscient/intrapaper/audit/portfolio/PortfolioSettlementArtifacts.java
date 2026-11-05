package com.mscient.intrapaper.audit.portfolio;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Atomic content-addressed settlement sidecars with an exact ordered hash manifest. */
public final class PortfolioSettlementArtifacts {
    static final String MANIFEST = "settlement-manifest.properties";
    private PortfolioSettlementArtifacts() { }
    private static List<String> names(boolean costs) {
        return costs ? List.of("settlements.csv", "settlement-cost-lines.csv", "inputs.csv", "summary.properties")
                : List.of("settlements.csv", "inputs.csv", "summary.properties");
    }
    static byte[] manifest(PortfolioSettlementAudit.Prepared prepared) {
        StringBuilder out = new StringBuilder("schema_version=1\nclassification=OFFLINE_PORTFOLIO_SETTLEMENT_AUDIT\nsettlement_costs_enabled=")
                .append(prepared.costsEnabled()).append('\n');
        for (String name : names(prepared.costsEnabled())) out.append("sha256.").append(name).append('=')
                .append(SettlementInputs.hash(prepared.files().get(name))).append('\n');
        return PortfolioSettlementAudit.utf8(out.toString());
    }
    static PortfolioSettlementAudit.WrittenAudit write(Path output, PortfolioSettlementAudit.Prepared prepared) throws IOException {
        Path root = output.toAbsolutePath().normalize(); Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) throw new IOException("Sidecar root must be a real directory");
        byte[] manifest = manifest(prepared); String id = SettlementInputs.hash(manifest);
        Path destination = root.resolve(id), lockPath = root.resolve(".settlement-lock-" + id);
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) SettlementInputs.readFile(lockPath);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock;
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException busy) { throw new IOException("Settlement publication already active", busy); }
            if (lock == null) throw new IOException("Settlement publication already active");
            try (lock) {
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return verify(destination);
                Path staging = Files.createTempDirectory(root, ".settlement-staging-");
                boolean published = false;
                try {
                    for (String name : names(prepared.costsEnabled())) Files.write(staging.resolve(name), prepared.files().get(name), StandardOpenOption.CREATE_NEW);
                    Files.write(staging.resolve(MANIFEST), manifest, StandardOpenOption.CREATE_NEW);
                    verifyFiles(staging, id);
                    Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE); published = true;
                    return new PortfolioSettlementAudit.WrittenAudit(id, destination);
                } finally { if (!published) PortfolioSettlementAudit.cleanup(staging); }
            }
        }
    }
    public static PortfolioSettlementAudit.WrittenAudit verify(Path folder) throws IOException {
        Path absolute = folder.toAbsolutePath().normalize(); String id = absolute.getFileName().toString();
        if (!id.matches("[0-9a-f]{64}")) throw new IOException("Settlement folder name must equal manifest SHA-256");
        verifyFiles(absolute, id); return new PortfolioSettlementAudit.WrittenAudit(id, absolute);
    }
    private static void verifyFiles(Path folder, String id) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(folder)) throw new IOException("Sidecar must be a real directory");
        byte[] bytes = SettlementInputs.readFile(folder.resolve(MANIFEST));
        if (!SettlementInputs.hash(bytes).equals(id)) throw new IOException("Settlement manifest/folder hash mismatch");
        List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        if (lines.size() < 3 || !lines.get(0).equals("schema_version=1") || !lines.get(1).equals("classification=OFFLINE_PORTFOLIO_SETTLEMENT_AUDIT")
                || !List.of("settlement_costs_enabled=true", "settlement_costs_enabled=false").contains(lines.get(2)))
            throw new IOException("Invalid settlement manifest contract");
        boolean costs = lines.get(2).equals("settlement_costs_enabled=true");
        List<String> names = names(costs);
        if (lines.size() != names.size() + 3) throw new IOException("Unexpected settlement manifest rows");
        Set<String> expected = new TreeSet<>(names); expected.add(MANIFEST);
        try (var paths = Files.list(folder)) {
            if (!new TreeSet<>(paths.map(p -> p.getFileName().toString()).toList()).equals(expected)) throw new IOException("Missing or unexpected settlement files");
        }
        TreeMap<String, byte[]> files = new TreeMap<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i); byte[] content = SettlementInputs.readFile(folder.resolve(name));
            if (!lines.get(i + 3).equals("sha256." + name + "=" + SettlementInputs.hash(content))) throw new IOException("Settlement output hash mismatch: " + name);
            files.put(name, content);
        }
        if (!Arrays.equals(bytes, manifest(new PortfolioSettlementAudit.Prepared(files, costs)))) throw new IOException("Noncanonical settlement manifest");
    }
}
