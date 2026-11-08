package com.mscient.intrapaper.evaluation.hypothesis;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;

/** Create-new local snapshots. External anchoring is still required against wholesale replacement. */
public final class HypothesisRegistry {
    private HypothesisRegistry() { }

    public static Path archive(Path registry, Path cardFile) throws IOException {
        HypothesisCard card = HypothesisCard.load(cardFile);
        requireRegistry(registry);
        Path lock = registry.resolve(".registry.lock");
        if (Files.isSymbolicLink(lock)) throw new IOException("Registry lock must be ordinary");
        try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            Path target = registry.resolve(card.directoryName());
            if (Files.exists(target)) throw new IOException("Card identity already archived; create a changed version");
            if (card.version() > 1) {
                HypothesisCard previous = verify(registry, card.id(), card.version() - 1);
                if (!previous.sha256().equals(card.predecessor())) throw new IOException("Previous version hash mismatch");
            }
            Path staging = Files.createTempDirectory(registry, ".staging-");
            write(staging.resolve("card.json"), card.bytes());
            for (var a : card.attachments().entrySet()) write(staging.resolve(a.getKey()), a.getValue());
            write(staging.resolve("identity.sha256"), (card.sha256() + "\n").getBytes(StandardCharsets.US_ASCII));
            // Publication is all-or-nothing on filesystems supporting atomic directory moves.
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
            return target;
        }
    }

    public static HypothesisCard verify(Path registry, String id, int version) throws IOException {
        requireRegistry(registry);
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || version < 1 || version > 1000000) {
            throw new IOException("Invalid card identity");
        }
        HypothesisCard current = null;
        String previousHash = null;
        for (int i = 1; i <= version; i++) {
            Path directory = registry.resolve(id + ".v" + i);
            if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory)) throw new IOException("Missing ordinary version directory");
            current = HypothesisCard.load(directory.resolve("card.json"));
            String receipt = HypothesisCard.decode(HypothesisCard.read(directory.resolve("identity.sha256")));
            if (!receipt.equals(current.sha256() + "\n") || !id.equals(current.id()) || current.version() != i
                    || !java.util.Objects.equals(previousHash, current.predecessor())) throw new IOException("Archived identity or predecessor mismatch");
            previousHash = current.sha256();
        }
        return current;
    }

    private static void requireRegistry(Path registry) throws IOException {
        if (Files.isSymbolicLink(registry) || !Files.isDirectory(registry)) throw new IOException("Registry must be an existing ordinary local directory");
    }
    private static void write(Path file, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) if (channel.write(buffer) <= 0) throw new IOException("Write made no progress");
            channel.force(true);
        }
    }
}
