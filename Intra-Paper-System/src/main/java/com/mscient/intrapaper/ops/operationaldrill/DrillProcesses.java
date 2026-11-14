package com.mscient.intrapaper.ops.operationaldrill;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fixed local Java entrypoints; scenarios cannot supply executable names or VM flags. */
final class DrillProcesses {
    private DrillProcesses() { }
    static Path build() throws IOException {
        try {
            Path path = Path.of(OperationalDrillCli.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            DrillFiles.noLinks(path);
            if (!Files.isRegularFile(path) || !path.toString().endsWith(".jar"))
                throw new IOException("Use the standalone packaged Java 21 JAR");
            return path;
        } catch (java.net.URISyntaxException e) { throw new IOException("Cannot identify build", e); }
    }
    static int run(Class<?> entrypoint, Path work, Path log, String... args) throws Exception {
        if (entrypoint != OperationalDrillWorker.class && entrypoint != RiskRestartWorker.class)
            throw new IllegalArgumentException("Unapproved worker");
        Path tmp = work.resolve("tmp"); Files.createDirectories(tmp);
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Xmx512m", "-XX:-UsePerfData", "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US",
                "-Duser.timezone=UTC", "-Djava.io.tmpdir=" + tmp, "-cp", build().toString(), entrypoint.getName()));
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        Map<String, String> env = builder.environment();
        String systemRoot = env.get("SystemRoot"); env.clear();
        if (systemRoot != null) env.put("SystemRoot", systemRoot);
        env.put("TEMP", tmp.toString()); env.put("TMP", tmp.toString());
        Process process = builder.start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) throw new IOException("Offline worker exceeded 60 seconds");
            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IOException("Worker termination failed");
            }
        }
    }
}
