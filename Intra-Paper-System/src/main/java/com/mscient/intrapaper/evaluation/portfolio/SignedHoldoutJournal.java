package com.mscient.intrapaper.evaluation.portfolio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Signed chains detect rewritten records even if an attacker recomputes ordinary hashes. */
final class SignedHoldoutJournal {
    static final String NONE = "NONE";
    static final String GENESIS = "0".repeat(64);
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    record Row(long sequence, Instant time, String payload, String hash) { }
    private SignedHoldoutJournal() { }

    static FileChannel open(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) throw new IOException("Invalid custody file");
        return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    static List<Row> read(FileChannel channel, String domain, PublicKey key) throws IOException {
        long size = channel.size();
        if (size == 0) return List.of();
        if (size > MAX_BYTES) throw new IOException("Custody journal exceeds size limit");
        ByteBuffer bytes = ByteBuffer.allocate((int) size);
        channel.position(0);
        while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new IOException("Partial custody journal");
        String text = PortfolioTrialIdentity.utf8(bytes.array());
        if (!text.startsWith(domain + "\n") || !text.endsWith("\n") || text.indexOf('\r') >= 0)
            throw new IOException("Malformed custody journal");
        List<Row> rows = new ArrayList<>();
        String previous = GENESIS;
        Instant lastTime = Instant.MIN;
        String[] lines = text.split("\n", -1);
        for (int i = 1; i < lines.length - 1; i++) {
            String[] fields = lines[i].split("\t", -1);
            if (fields.length < 6) throw new IOException("Malformed custody record");
            try {
                long sequence = Long.parseLong(fields[0]);
                Instant time = Instant.parse(fields[1]);
                if (sequence != i || !Long.toString(sequence).equals(fields[0])
                        || !time.toString().equals(fields[1]) || time.isBefore(lastTime))
                    throw new IOException("Invalid custody ordering");
                int tail = fields.length - 3;
                String payload = String.join("\t", java.util.Arrays.copyOfRange(fields, 2, tail));
                String signed = String.join("\t", fields[0], fields[1], payload, fields[tail]);
                if (!previous.equals(fields[tail]) || !verify(key, domain + "\t" + signed, fields[tail + 1])
                        || !HoldoutReleaseInputs.digest(signed + "\t" + fields[tail + 1]).equals(fields[tail + 2]))
                    throw new IOException("Custody signature or hash mismatch");
                rows.add(new Row(sequence, time, payload, fields[tail + 2]));
                previous = fields[tail + 2];
                lastTime = time;
            } catch (IllegalArgumentException invalid) { throw new IOException("Invalid custody record", invalid); }
        }
        return List.copyOf(rows);
    }

    static Row append(FileChannel channel, String domain, List<Row> rows, String payload,
            PrivateKey key, Instant time) throws IOException {
        if (payload.indexOf('\n') >= 0 || payload.indexOf('\r') >= 0) throw new IOException("Invalid journal payload");
        if (!rows.isEmpty() && time.isBefore(rows.getLast().time())) throw new IOException("Custody clock regressed");
        long sequence = rows.size() + 1L;
        String previous = rows.isEmpty() ? GENESIS : rows.getLast().hash();
        String signed = sequence + "\t" + time + "\t" + payload + "\t" + previous;
        String signature = sign(key, domain + "\t" + signed);
        String hash = HoldoutReleaseInputs.digest(signed + "\t" + signature);
        byte[] bytes = ((channel.size() == 0 ? domain + "\n" : "") + signed + "\t" + signature + "\t" + hash + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (channel.size() + bytes.length > MAX_BYTES) throw new IOException("Custody journal exceeds size limit");
        channel.position(channel.size());
        write(channel, bytes);
        channel.force(true);
        return new Row(sequence, time, payload, hash);
    }

    static void write(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }
    static String sign(PrivateKey key, String text) throws IOException {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(key);
            signature.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (GeneralSecurityException invalid) { throw new IOException("Custody signing failed", invalid); }
    }
    static boolean verify(PublicKey key, String text, String encoded) throws IOException {
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (!Base64.getEncoder().encodeToString(bytes).equals(encoded)) return false;
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(key);
            signature.update(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return signature.verify(bytes);
        } catch (GeneralSecurityException | IllegalArgumentException invalid) { return false; }
    }
}
