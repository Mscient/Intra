package com.mscient.intrapaper.evaluation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Append-only, hash-chained registry of attempted research variants. It never stores scores. */
public final class ResearchTrialLedger {
    private static final String HEADER = "INTRA_RESEARCH_TRIAL_LEDGER_V1";
    private static final int MAX_LEDGER_BYTES = 128 * 1024 * 1024;
    private static final byte[] GENESIS_HASH = new byte[32];
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public record Trial(String trialId, String strategyId, String strategyVersion, String variantId,
            String strategyCardSha256, String datasetSha256, String splitPlanSha256,
            String parameterConfigSha256) {
        public Trial {
            requireIdentifier(trialId, "trialId");
            requireIdentifier(strategyId, "strategyId");
            requireIdentifier(strategyVersion, "strategyVersion");
            requireIdentifier(variantId, "variantId");
            requireHash(strategyCardSha256, "strategyCardSha256");
            requireHash(datasetSha256, "datasetSha256");
            requireHash(splitPlanSha256, "splitPlanSha256");
            requireHash(parameterConfigSha256, "parameterConfigSha256");
        }
    }

    public record Entry(long sequence, Instant registeredAtUtc, Trial trial,
            String previousHashSha256, String recordHashSha256) { }

    public record Summary(long entryCount, long distinctStrategyVersions, String finalHashSha256) { }

    private ResearchTrialLedger() { }

    /** Registers a previously unseen trial and strategy-version/variant pair. */
    public static Entry register(Path path, Trial trial) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(trial, "trial");
        FileChannel openedChannel;
        boolean created;
        try {
            openedChannel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            created = true;
        } catch (FileAlreadyExistsException alreadyExists) {
            openedChannel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            created = false;
        }
        final FileChannel channel = openedChannel;
        try (channel;
             FileLock ignored = channel.lock()) {
            List<Entry> entries;
            if (created) {
                writeFully(channel, ByteBuffer.wrap((HEADER + "\n").getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
                entries = List.of();
            } else {
                entries = parse(readBytes(channel));
            }
            requireNewTrial(entries, trial);
            long sequence = entries.size() + 1L;
            Instant registeredAt = Instant.now();
            String payload = payload(sequence, registeredAt, trial);
            String previousHash = entries.isEmpty()
                    ? HexFormat.of().formatHex(GENESIS_HASH)
                    : entries.getLast().recordHashSha256();
            String recordHash = hash(previousHash, payload);
            byte[] row = (payload + "\t" + previousHash + "\t" + recordHash + "\n")
                    .getBytes(StandardCharsets.UTF_8);

            channel.position(channel.size());
            writeFully(channel, ByteBuffer.wrap(row));
            channel.force(true);
            return new Entry(sequence, registeredAt, trial, previousHash, recordHash);
        }
    }

    /** Verifies the complete ledger while holding a shared file lock. */
    public static Summary verify(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
             FileLock ignored = channel.lock(0L, Long.MAX_VALUE, true)) {
            List<Entry> entries = parse(readBytes(channel));
            Set<String> strategyVersions = new HashSet<>();
            entries.forEach(entry -> strategyVersions.add(strategyVersionKey(entry.trial())));
            String finalHash = entries.isEmpty()
                    ? HexFormat.of().formatHex(GENESIS_HASH)
                    : entries.getLast().recordHashSha256();
            return new Summary(entries.size(), strategyVersions.size(), finalHash);
        }
    }

    /** Returns every registration after verifying the complete hash chain under a shared lock. */
    public static List<Entry> entries(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
             FileLock ignored = channel.lock(0L, Long.MAX_VALUE, true)) {
            return parse(readBytes(channel));
        }
    }

    /** Requires an exact prior registration for these frozen run inputs. */
    public static Entry requireRegistered(Path path, Trial expected) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(expected, "expected");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
             FileLock ignored = channel.lock(0L, Long.MAX_VALUE, true)) {
            for (Entry entry : parse(readBytes(channel))) {
                if (entry.trial().trialId().equals(expected.trialId())) {
                    if (!entry.trial().equals(expected)) {
                        throw new IOException("Registered research trial does not match the current card, data, split, or parameter inputs");
                    }
                    return entry;
                }
            }
        }
        throw new IOException("Research trial is not preregistered: " + expected.trialId());
    }

    private static List<Entry> parse(byte[] bytes) throws IOException {
        if (bytes.length == 0) throw new IOException("Research trial ledger is empty");
        if (bytes.length > MAX_LEDGER_BYTES) throw new IOException("Research trial ledger exceeds the supported size");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new IOException("Research trial ledger is not valid UTF-8", invalidUtf8);
        }
        if (text.indexOf('\r') >= 0) throw new IOException("Research trial ledger must use LF line endings");
        if (!text.endsWith("\n")) throw new IOException("Research trial ledger ends with a partial record");
        String[] lines = text.split("\n", -1);
        if (!HEADER.equals(lines[0])) throw new IOException("Unsupported research trial ledger header");

        List<Entry> entries = new ArrayList<>(Math.max(0, lines.length - 2));
        Set<String> trialIds = new HashSet<>();
        Set<String> variants = new HashSet<>();
        String expectedPreviousHash = HexFormat.of().formatHex(GENESIS_HASH);
        for (int lineIndex = 1; lineIndex < lines.length - 1; lineIndex++) {
            String line = lines[lineIndex];
            if (line.isEmpty()) throw new IOException("Blank record at line " + (lineIndex + 1));
            String[] fields = line.split("\t", -1);
            if (fields.length != 12) throw new IOException("Expected 12 fields at line " + (lineIndex + 1));
            long sequence;
            try {
                sequence = Long.parseLong(fields[0]);
            } catch (NumberFormatException invalidSequence) {
                throw new IOException("Invalid sequence at line " + (lineIndex + 1), invalidSequence);
            }
            if (sequence != lineIndex) throw new IOException("Non-contiguous sequence at line " + (lineIndex + 1));
            Instant registeredAt;
            try {
                registeredAt = Instant.parse(fields[1]);
            } catch (RuntimeException invalidTimestamp) {
                throw new IOException("Invalid registration timestamp at line " + (lineIndex + 1), invalidTimestamp);
            }
            if (!registeredAt.toString().equals(fields[1])) {
                throw new IOException("Non-canonical registration timestamp at line " + (lineIndex + 1));
            }
            Trial trial;
            try {
                trial = new Trial(fields[2], fields[3], fields[4], fields[5], fields[6], fields[7], fields[8], fields[9]);
            } catch (IllegalArgumentException invalidTrial) {
                throw new IOException("Invalid trial at line " + (lineIndex + 1) + ": " + invalidTrial.getMessage(), invalidTrial);
            }
            if (!trialIds.add(trial.trialId())) throw new IOException("Duplicate trial ID at line " + (lineIndex + 1));
            if (!variants.add(strategyVersionKey(trial) + "\u0000" + trial.variantId())) {
                throw new IOException("Duplicate strategy-version/variant at line " + (lineIndex + 1));
            }
            if (!fields[10].equals(expectedPreviousHash)) {
                throw new IOException("Hash-chain predecessor mismatch at line " + (lineIndex + 1));
            }
            String actualHash = hash(fields[10], String.join("\t", fields[0], fields[1], fields[2], fields[3],
                    fields[4], fields[5], fields[6], fields[7], fields[8], fields[9]));
            if (!actualHash.equals(fields[11])) throw new IOException("Record hash mismatch at line " + (lineIndex + 1));
            entries.add(new Entry(sequence, registeredAt, trial, fields[10], fields[11]));
            expectedPreviousHash = actualHash;
        }
        return List.copyOf(entries);
    }

    private static void requireNewTrial(List<Entry> entries, Trial trial) {
        String variantKey = strategyVersionKey(trial) + "\u0000" + trial.variantId();
        for (Entry entry : entries) {
            if (entry.trial().trialId().equals(trial.trialId())) {
                throw new IllegalArgumentException("trialId is already registered: " + trial.trialId());
            }
            if ((strategyVersionKey(entry.trial()) + "\u0000" + entry.trial().variantId()).equals(variantKey)) {
                throw new IllegalArgumentException("strategy-version/variant is already registered: " + trial.variantId());
            }
        }
    }

    private static String strategyVersionKey(Trial trial) {
        return trial.strategyId() + "\u0000" + trial.strategyVersion();
    }

    private static String payload(long sequence, Instant registeredAt, Trial trial) {
        return String.join("\t", Long.toString(sequence), registeredAt.toString(), trial.trialId(),
                trial.strategyId(), trial.strategyVersion(), trial.variantId(), trial.strategyCardSha256(),
                trial.datasetSha256(), trial.splitPlanSha256(), trial.parameterConfigSha256());
    }

    private static String hash(String previousHashHex, String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(HexFormat.of().parseHex(previousHashHex));
            digest.update(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static byte[] readBytes(FileChannel channel) throws IOException {
        long size = channel.size();
        if (size <= 0 || size > MAX_LEDGER_BYTES) throw new IOException("Invalid research trial ledger size: " + size);
        ByteBuffer data = ByteBuffer.allocate((int) size);
        channel.position(0);
        while (data.hasRemaining()) {
            int read = channel.read(data);
            if (read < 0) throw new IOException("Unexpected end of research trial ledger");
            if (read == 0) throw new IOException("Research trial ledger read made no progress");
        }
        return data.array();
    }

    private static void writeFully(FileChannel channel, ByteBuffer data) throws IOException {
        while (data.hasRemaining()) {
            if (channel.write(data) <= 0) throw new IOException("Research trial ledger write made no progress");
        }
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + IDENTIFIER.pattern());
        }
    }

    private static void requireHash(String value, String name) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be lowercase SHA-256 hex");
        }
    }
}
