package com.mscient.intrapaper.provenance;

import java.io.IOException;
import java.io.InputStream;
import java.util.jar.Manifest;

/** Build metadata embedded in the runnable JAR. */
public final class BuildInfo {
    private BuildInfo() { }

    public static String commitId() {
        try (InputStream manifestStream = BuildInfo.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            if (manifestStream == null) return "unknown";
            Manifest manifest = new Manifest(manifestStream);
            String commitId = manifest.getMainAttributes().getValue("Build-Commit");
            return commitId == null || commitId.isBlank() ? "unknown" : commitId;
        } catch (IOException invalidManifest) {
            return "unknown";
        }
    }
}
