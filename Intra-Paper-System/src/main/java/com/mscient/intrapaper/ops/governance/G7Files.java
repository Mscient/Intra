package com.mscient.intrapaper.ops.governance;

import static com.mscient.intrapaper.ops.governance.G7Json.*;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Byte snapshots of ordinary local files. No URI resolution, executable inputs or link following. */
final class G7Files implements AutoCloseable {
    static final long MAX_FILE = 16L * 1024 * 1024, MAX_TREE = 128L * 1024 * 1024;
    final Path root;
    final Map<String, byte[]> files = new TreeMap<>();
    final Map<String, String> hashes = new TreeMap<>();
    private final Map<String, Stamp> stamps = new TreeMap<>();
    private final Map<Path, Stamp> ancestors = new LinkedHashMap<>();
    private final List<FileChannel> channels = new ArrayList<>();
    private Path frozen;
    private record Stamp(Object key, long size, java.nio.file.attribute.FileTime modified,
                         java.nio.file.attribute.FileTime created, boolean directory) {
        static Stamp of(BasicFileAttributes a) { return new Stamp(a.fileKey(), a.size(), a.lastModifiedTime(), a.creationTime(), a.isDirectory()); }
        boolean sameAncestor(Stamp other) { return Objects.equals(key, other.key) && created.equals(other.created) && directory == other.directory; }
    }
    @FunctionalInterface interface BeforePublication { void check() throws IOException; }
    private G7Files(Path root) { this.root = root; }
    static Path local(Path path) throws IOException {
        String spelling = path.toString();
        if (spelling.startsWith("\\\\") || spelling.startsWith("//") || spelling.contains("://"))
            throw new IOException("Network/device paths are forbidden");
        for (Path part : path) if (part.toString().equals("..") || part.toString().contains(":"))
            throw new IOException("Traversal and alternate data streams are forbidden");
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.getRoot() == null || absolute.getRoot().toString().startsWith("\\\\")) throw new IOException("Local absolute path required");
        // On Windows, mapped network drives must also be rejected before opening any file.
        if (System.getProperty("os.name").startsWith("Windows")) {
            String drive = absolute.getRoot().toString();
            if (!drive.matches("[A-Za-z]:\\\\")) throw new IOException("Ordinary local drive required");
            if (!absolute.getRoot().equals(Path.of(System.getProperty("java.home")).toAbsolutePath().getRoot()))
                throw new IOException("Use the local JDK volume; alternate/mapped drives are unsupported");
            // Operators must provision a local JDK/temp/dossier volume; Java cannot authenticate privileged mounts.
        }
        List<Path> steps = new ArrayList<>();
        for (Path p = absolute; p != null; p = p.getParent()) steps.add(p);
        Collections.reverse(steps);
        for (Path p : steps) if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (a.isSymbolicLink() || a.isOther()) throw new IOException("Symlinks, junctions and special files are forbidden");
            if (!p.toRealPath().equals(p)) throw new IOException("Path alias or redirected ancestor is forbidden");
        }
        return absolute;
    }
    static String relative(String path) throws IOException {
        if (path.length() > 240 || !path.matches("[A-Za-z0-9][A-Za-z0-9._/-]*")) throw new IOException("Portable relative path required");
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.length() > 128 || part.equals(".") || part.equals("..") || part.endsWith(".")
                    || part.matches("(?i)(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])(?:\\..*)?")) throw new IOException("Unsafe path component");
        }
        return path;
    }
    static byte[] read(Path path) throws IOException {
        return readOrdinary(local(path));
    }
    private static byte[] readOrdinary(Path p) throws IOException {
        Stamp before = stamp(p);
        if (before.directory) throw new IOException("Required ordinary file missing");
        if (before.size > MAX_FILE) throw new IOException("File resource bound exceeded");
        try (var in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
            byte[] b = in.readNBytes((int) MAX_FILE + 1);
            if (b.length > MAX_FILE) throw new IOException("File resource bound exceeded");
            if (!before.equals(stamp(p)) || b.length != before.size) throw new IOException("File changed while reading");
            return b;
        }
    }
    private static Stamp stamp(Path path) throws IOException {
        BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!(a.isRegularFile() || a.isDirectory()) || a.isSymbolicLink() || a.isOther())
            throw new IOException("Ordinary local file/directory required");
        // The Microsoft Windows provider returns null fileKey. Keep that unknown;
        // creation/modified times, open read locks and byte checks remain separate evidence.
        return Stamp.of(a);
    }
    /** Explicit external history/draft inputs are also checked for replacement and restored-byte mutation. */
    static final class External {
        final Path path;
        final byte[] bytes;
        private final Stamp stamp;
        External(Path requested) throws IOException {
            path = local(requested); stamp = stamp(path); bytes = read(path);
            if (!stamp.equals(stamp(path))) throw new IOException("External input changed while reading");
        }
        void unchanged() throws IOException {
            if (!stamp.equals(stamp(path)) || !Arrays.equals(bytes, read(path))) throw new IOException("External input changed during review");
        }
    }
    static G7Files load(Path path) throws IOException {
        return load(path, true);
    }
    private static G7Files load(Path path, boolean holdLocks) throws IOException {
        G7Files snapshot = new G7Files(local(path));
        if (!Files.isDirectory(snapshot.root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Dossier directory missing");
        Set<String> names = new HashSet<>(); Set<Object> keys = new HashSet<>(); Map<String, List<Path>> digestPaths = new HashMap<>(); long[] size = {0};
        for (Path p = snapshot.root.getParent(); p != null; p = p.getParent()) snapshot.ancestors.put(p, stamp(p));
        try {
        Files.walkFileTree(snapshot.root, new SimpleFileVisitor<>() {
            int entries;
            private void check(Path p, BasicFileAttributes a) throws IOException {
                if (++entries > 2048) throw new IOException("Directory resource bound exceeded");
                if (a.isSymbolicLink() || a.isOther()) throw new IOException("Linked input forbidden");
                if (!p.startsWith(snapshot.root) || !p.toRealPath().equals(p)) throw new IOException("Redirected/aliased input path");
            }
            @Override public FileVisitResult preVisitDirectory(Path p, BasicFileAttributes a) throws IOException {
                check(p, a);
                String name = snapshot.root.relativize(p).toString().replace('\\', '/');
                if (!name.isEmpty()) relative(name);
                if (!names.add(name.toLowerCase(Locale.ROOT)) || a.fileKey() != null && !keys.add(a.fileKey())) throw new IOException("Directory alias");
                snapshot.stamps.put(name, stamp(p)); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path p, BasicFileAttributes a) throws IOException {
                check(p, a);
                String name = relative(snapshot.root.relativize(p).toString().replace('\\', '/'));
                if (!names.add(name.toLowerCase(Locale.ROOT)) || a.fileKey() != null && !keys.add(a.fileKey())) throw new IOException("Duplicate/case/hard-link alias");
                if (snapshot.files.size() >= 1024) throw new IOException("File-count resource bound exceeded");
                if (holdLocks) {
                    FileChannel channel = FileChannel.open(p, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                    snapshot.channels.add(channel);
                    FileLock lock;
                    try { lock = channel.tryLock(0, Long.MAX_VALUE, true); }
                    catch (OverlappingFileLockException busy) { throw new IOException("Input already in use", busy); }
                    if (lock == null) throw new IOException("Input mutation/review lock unavailable");
                }
                Stamp before = stamp(p);
                byte[] bytes = readOrdinary(p); size[0] += bytes.length;
                if (!before.equals(stamp(p))) throw new IOException("Input changed during snapshot");
                if (size[0] > MAX_TREE) throw new IOException("Dossier resource bound exceeded");
                String digest = hash(bytes);
                // With stable/locked bytes, hard links must share their digest. Limit native
                // same-file checks to that group when the provider omits fileKey.
                if (a.fileKey() == null) for (Path other : digestPaths.getOrDefault(digest, List.of()))
                    if (Files.isSameFile(p, other)) throw new IOException("Hard-link alias");
                digestPaths.computeIfAbsent(digest, unused -> new ArrayList<>()).add(p);
                snapshot.files.put(name, bytes); snapshot.hashes.put(name, digest);
                snapshot.stamps.put(name, before);
                return FileVisitResult.CONTINUE;
            }
        });
        snapshot.checkStamps();
        return snapshot;
        } catch (IOException | RuntimeException failure) { snapshot.close(); throw failure; }
    }
    Map<String, Object> json(String path, String schema) throws IOException {
        byte[] content = files.get(relative(path));
        if (content == null) throw new IOException("Missing required contract: " + path);
        Object parsed = parse(content); G7Schema.validate(schema, parsed); return obj(parsed);
    }
    Path frozen(String relative) throws IOException {
        relative(relative);
        if (!files.containsKey(relative)) throw new IOException("Unlisted frozen input");
        if (frozen == null) {
            Path temp = local(Path.of(System.getProperty("java.io.tmpdir")));
            frozen = Files.createTempDirectory(temp, "g7-frozen-");
            for (var e : files.entrySet()) {
                Path target = frozen.resolve(e.getKey()); Files.createDirectories(target.getParent());
                Files.write(target, e.getValue(), StandardOpenOption.CREATE_NEW);
            }
        }
        return frozen.resolve(relative);
    }
    void unchanged() throws IOException {
        checkStamps();
        try (G7Files again = load(root, false)) {
            if (!hashes.equals(again.hashes) || !stamps.equals(again.stamps)) throw new IOException("Dossier changed during review");
        }
        if (frozen != null) try (G7Files copy = load(frozen, false)) {
            if (!hashes.equals(copy.hashes)) throw new IOException("Frozen input changed during adapter review");
        }
    }
    private void checkStamps() throws IOException {
        local(root);
        for (var e : ancestors.entrySet()) if (!e.getValue().sameAncestor(stamp(e.getKey()))) throw new IOException("Redirected input ancestor");
        for (var e : stamps.entrySet()) if (!e.getValue().equals(stamp(root.resolve(e.getKey())))) throw new IOException("Input metadata changed during review");
    }
    @Override public void close() throws IOException {
        IOException failure = null;
        for (FileChannel channel : channels) try { channel.close(); } catch (IOException e) { failure = e; }
        if (frozen != null) try { cleanup(frozen); frozen = null; } catch (IOException e) { failure = e; }
        if (failure != null) throw failure;
    }
    private static void cleanup(Path owned) throws IOException {
        local(owned);
        try (var paths = Files.walk(owned)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) { local(p); Files.delete(p); }
        }
    }
    static void publish(Path requested, Path input, Map<String, byte[]> outputs) throws IOException {
        publish(requested, input, outputs, () -> { });
    }
    static void publish(Path requested, Path input, Map<String, byte[]> outputs, BeforePublication beforeMove) throws IOException {
        Path destination = local(requested), parent = destination.getParent();
        relative(destination.getFileName().toString());
        input = local(input);
        if (parent == null || destination.equals(destination.getRoot()) || destination.startsWith(input) || input.startsWith(destination))
            throw new IOException("Output must be a new directory outside the dossier");
        Files.createDirectories(parent); local(parent);
        Stamp parentStamp = stamp(parent);
        Path lockPath = parent.resolve(".g7-publication.lock"); local(lockPath);
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            FileLock lock;
            try { lock = channel.tryLock(); } catch (OverlappingFileLockException busy) { throw new IOException("Publication busy", busy); }
            if (lock == null) throw new IOException("Publication busy");
            try (lock) {
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Output already exists; overwriting is forbidden");
                Path staging = Files.createTempDirectory(parent, ".g7-stage-"); boolean moved = false;
                try {
                    if (outputs.isEmpty()) throw new IOException("Empty publication");
                    Set<String> names = new HashSet<>();
                    for (var e : outputs.entrySet()) {
                        String name = relative(e.getKey());
                        if (name.contains("/") || !names.add(name.toLowerCase(Locale.ROOT))) throw new IOException("Publication files must be distinct and flat");
                        try (FileChannel file = FileChannel.open(staging.resolve(name), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                            var buffer = java.nio.ByteBuffer.wrap(e.getValue()); while (buffer.hasRemaining()) file.write(buffer); file.force(true);
                        }
                    }
                    beforeMove.check();
                    if (!parentStamp.sameAncestor(stamp(parent)) || Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Publication parent redirected or destination appeared");
                    local(staging); local(destination);
                    Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE); moved = true;
                } finally { if (!moved) cleanup(staging); }
            }
        }
    }
}
