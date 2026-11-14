package com.mscient.intrapaper.permission;

import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.provenance.RunConfig;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Mandatory for non-synthetic bar/portfolio dataset loading, before simulation starts. */
public final class PermissionAdmission {
    private PermissionAdmission() { }
    public record Inputs(Path manifest, String manifestSha256, Path record) {
        public Inputs {
            Objects.requireNonNull(manifest); Objects.requireNonNull(record);
            PermissionDocuments.hash(manifestSha256);
        }
    }

    public static void require(Path barsPath, RunConfig config, List<Bar> bars, Map<String, String> inputHashes)
            throws IOException {
        if (config.permissionInputs().isEmpty()) {
            if (!config.syntheticFixture()) throw new IllegalArgumentException("Data-permission preflight required");
            return; // Legacy synthetic plumbing has no permission claim.
        }
        var inputs = config.permissionInputs().orElseThrow();
        var checked = DataPermissionChecker.inspect(inputs.manifest(), inputs.manifestSha256(), inputs.record(),
                Instant.now(), config.syntheticFixture() ? DataPermissionChecker.Mode.SYNTHETIC_TEST : DataPermissionChecker.Mode.RESEARCH);
        if (checked.result().status() == DataPermissionChecker.Status.DENIED) {
            throw new IllegalArgumentException("Data-permission preflight denied: " + checked.result().reasons());
        }
        var m = checked.manifest();
        if (!m.id().equals(config.datasetId()) || !m.source().equals(config.datasetSourceName())
                || !m.acquiredAt().equals(config.datasetAcquiredAt())
                || !m.instruments().equals(Set.of(bars.getFirst().instrumentId()))
                || !m.dataFrom().equals(bars.getFirst().sessionDate())
                || !m.dataThrough().equals(bars.getLast().sessionDate())
                || !m.files().equals(inputHashes)) {
            throw new IllegalArgumentException("Data-permission manifest does not match loaded dataset metadata/hashes");
        }
        if (!config.syntheticFixture() && !config.dataRightsReference().orElse("").equals(m.permissionId())) {
            throw new IllegalArgumentException("dataset.rights_reference must be the bound permission record ID");
        }
        // Each source/product needs its own declaration; this single-bar adapter cannot infer a second grant.
        if (config.quoteJournalPath().isPresent()) {
            throw new IllegalArgumentException("Permission admission for an additional quote source is unsupported; separate reviewed scope required");
        }
        if (!m.outputUses().equals(Set.of("INTERNAL_RESEARCH"))) {
            throw new IllegalArgumentException("Bar research admission supports only INTERNAL_RESEARCH derived outputs");
        }
        try {
            URI location = URI.create(m.storage());
            if (!"file".equals(location.getScheme()) || !location.toString().endsWith("/")) {
                throw new IllegalArgumentException("Storage must be an absolute file directory URI ending in /");
            }
            Path storage = Path.of(location).toRealPath();
            if (!java.nio.file.Files.isDirectory(storage) || !barsPath.toRealPath().startsWith(storage)) {
                throw new IllegalArgumentException("Dataset file is outside declared storage location");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Data-permission storage binding invalid", invalid);
        }
        // The manifest includes the record ID and record hash. Both byte hashes enter dataset/trial identity.
        inputHashes.put("permission-manifest", checked.result().manifestSha256());
        inputHashes.put("permission-record", checked.result().permissionSha256());
    }
}
