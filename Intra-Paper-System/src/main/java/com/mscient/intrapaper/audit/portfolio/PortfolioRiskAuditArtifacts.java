package com.mscient.intrapaper.audit.portfolio;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Content-addressed, fixed-layout reports. Integrity verification alone is not an audit rerun. */
public final class PortfolioRiskAuditArtifacts {
    private static final String MANIFEST = "audit-manifest.properties";
    private static final List<String> OUTPUTS = List.of("breaches.csv", "checkpoints.csv", "coverage.csv", "exposures.csv",
            "inputs.csv", "reconciliation.csv", "risk-budgets.csv", "risk-limits.csv", "risk-margin-proxies.csv",
            "risk-sources.csv", "summary.properties");
    private PortfolioRiskAuditArtifacts() { }

    static byte[] manifest(PortfolioRiskAudit.Prepared prepared) {
        StringBuilder out = new StringBuilder("schema_version=1\nclassification=OFFLINE_PORTFOLIO_RISK_AUDIT\nmargin_classification=CALLER_SUPPLIED_PROXIES\nstatus=")
                .append(prepared.status()).append('\n');
        for (String name : OUTPUTS) out.append("sha256.").append(name).append('=').append(RiskInputs.hash(prepared.files().get(name))).append('\n');
        return PortfolioRiskAudit.utf8(out.toString());
    }

    static PortfolioRiskAudit.WrittenAudit write(Path output, PortfolioRiskAudit.Prepared prepared) throws IOException {
        Path root = output.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root))
            throw new IOException("Audit output must be a real directory");
        byte[] manifest = manifest(prepared);
        String id = RiskInputs.hash(manifest);
        Path destination = root.resolve(id), lockPath = root.resolve(".risk-audit-lock-" + id);
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) RiskInputs.requireFile(lockPath);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { throw new IOException("Audit publication already active", busy); }
            if (lock == null) throw new IOException("Audit publication already active");
            try (lock) {
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return verify(destination);
                Path staging = Files.createTempDirectory(root, ".risk-audit-staging-");
                boolean published = false;
                try {
                    for (String name : OUTPUTS) Files.write(staging.resolve(name), prepared.files().get(name), StandardOpenOption.CREATE_NEW);
                    Files.write(staging.resolve(MANIFEST), manifest, StandardOpenOption.CREATE_NEW);
                    verifyFiles(staging, id);
                    Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
                    published = true;
                    return new PortfolioRiskAudit.WrittenAudit(id, destination, prepared.status());
                } finally { if (!published) PortfolioRiskAudit.cleanup(staging); }
            }
        }
    }

    public static PortfolioRiskAudit.WrittenAudit verify(Path folder) throws IOException {
        Path absolute = folder.toAbsolutePath().normalize();
        String id = absolute.getFileName().toString();
        if (!id.matches("[0-9a-f]{64}")) throw new IOException("Audit folder must be its manifest SHA-256");
        return new PortfolioRiskAudit.WrittenAudit(id, absolute, verifyFiles(absolute, id));
    }

    private static String verifyFiles(Path folder, String id) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(folder))
            throw new IOException("Audit folder is not a real directory");
        Set<String> expected = new TreeSet<>(OUTPUTS); expected.add(MANIFEST);
        try (var children = Files.list(folder)) {
            if (!new TreeSet<>(children.map(p -> p.getFileName().toString()).toList()).equals(expected))
                throw new IOException("Missing or unexpected audit files");
        }
        RiskInputs.requireFile(folder.resolve(MANIFEST));
        byte[] bytes = Files.readAllBytes(folder.resolve(MANIFEST));
        if (!RiskInputs.hash(bytes).equals(id)) throw new IOException("Audit manifest/folder hash mismatch");
        List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
        if (lines.size() != OUTPUTS.size() + 4 || !lines.get(0).equals("schema_version=1")
                || !lines.get(1).equals("classification=OFFLINE_PORTFOLIO_RISK_AUDIT")
                || !lines.get(2).equals("margin_classification=CALLER_SUPPLIED_PROXIES")
                || !List.of("status=PASS", "status=BREACH", "status=INCOMPLETE").contains(lines.get(3)))
            throw new IOException("Invalid audit manifest contract");
        TreeMap<String, byte[]> contents = new TreeMap<>();
        for (int i = 0; i < OUTPUTS.size(); i++) {
            String name = OUTPUTS.get(i);
            RiskInputs.requireFile(folder.resolve(name));
            byte[] content = Files.readAllBytes(folder.resolve(name));
            if (!lines.get(i + 4).equals("sha256." + name + "=" + RiskInputs.hash(content)))
                throw new IOException("Audit output hash mismatch: " + name);
            contents.put(name, content);
        }
        String status = lines.get(3).substring("status=".length());
        if (!Arrays.equals(bytes, manifest(new PortfolioRiskAudit.Prepared(contents, status))))
            throw new IOException("Noncanonical audit manifest");
        return status;
    }
}
