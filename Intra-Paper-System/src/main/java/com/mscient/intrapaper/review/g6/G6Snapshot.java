package com.mscient.intrapaper.review.g6;

import static com.mscient.intrapaper.review.g6.G6Json.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Feeds existing path-based verifiers exactly the bytes hashed by G6, not a second read of mutable inputs. */
final class G6Snapshot implements AutoCloseable {
    final Path root;
    private final Set<Path> files=new HashSet<>();

    G6Snapshot() throws IOException {
        Path temporary=Path.of(System.getProperty("java.io.tmpdir"));
        noLinks(temporary);
        root=Files.createTempDirectory(temporary,"intra-g6-snapshot-").toAbsolutePath().normalize();
        noLinks(root);
    }

    Path put(String relative,byte[] data) throws IOException {
        Path target=local(root,relative);
        Files.createDirectories(target.getParent());
        G6Artifacts.write(target,data);
        files.add(target);
        return target;
    }

    @Override public void close() throws IOException {
        noLinks(root);
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Snapshot root changed");
        try(var walk=Files.walk(root)) {
            for(Path p:walk.sorted(Comparator.reverseOrder()).toList()) {
                require(p.startsWith(root),"Snapshot cleanup escaped its root");
                noLinks(p);
                require(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS) || files.contains(p),"Unexpected snapshot file; refusing cleanup");
                Files.delete(p);
            }
        }
    }
}
