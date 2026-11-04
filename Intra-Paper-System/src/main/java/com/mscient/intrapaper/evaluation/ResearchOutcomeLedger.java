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
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Append-only, hash-chained outcome-exposure events bound to registered research trials. */
public final class ResearchOutcomeLedger {
    private static final String HEADER = "INTRA_RESEARCH_OUTCOME_LEDGER_V1";
    private static final int MAX_LEDGER_BYTES = 128 * 1024 * 1024;
    private static final byte[] GENESIS_HASH = new byte[32];
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@%+=,-]{0,255}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public enum EventType {
        ATTEMPT_STARTED,
        OUTCOME_PRODUCED,
        OUTCOME_VIEWED,
        MECHANICAL_FAILURE_NO_OUTCOME,
        ABANDONED
    }

    public enum ExposureState {
        NO_ATTEMPT_EVENTS,
        ATTEMPTED_NO_OUTCOME_RECORDED,
        MECHANICAL_FAILURE_ONLY,
        OUTCOME_PRODUCED_UNVIEWED,
        OUTCOME_VIEWED
    }

    public record Entry(long sequence, Instant occurredAtUtc, String eventId, String trialId,
            String registrationHashSha256, String attemptId, EventType eventType,
            String reference, String reason, String previousHashSha256, String recordHashSha256) { }

    public record TrialSummary(String trialId, int attemptCount, long outcomesProduced,
            long outcomesViewed, long mechanicalFailuresWithoutOutcome, long abandonedAttempts,
            ExposureState exposureState) { }

    public record Summary(long eventCount, long attemptsStarted, long outcomesProduced,
            long outcomeViews, long mechanicalFailuresWithoutOutcome, long abandonedAttempts,
            String finalHashSha256, List<TrialSummary> trials) {
        public Summary {
            trials = List.copyOf(trials);
        }
    }

    private static final class AttemptState {
        private boolean started;
        private boolean outcomeProduced;
        private boolean outcomeViewed;
        private boolean mechanicalFailure;
        private boolean abandoned;
    }

    private static final class MutableTrialSummary {
        private int attemptCount;
        private long outcomesProduced;
        private long outcomeViews;
        private long mechanicalFailures;
        private long abandoned;
    }

    private ResearchOutcomeLedger() { }

    /** Appends one UTC-timestamped event for a previously registered trial. */
    public static Entry append(Path registrationLedgerPath, Path outcomeLedgerPath,
            String trialId, String attemptId, String eventId, EventType eventType,
            String reference, String reason) throws IOException {
        Objects.requireNonNull(registrationLedgerPath, "registrationLedgerPath");
        Objects.requireNonNull(outcomeLedgerPath, "outcomeLedgerPath");
        requireIdentifier(trialId, "trialId");
        requireIdentifier(attemptId, "attemptId");
        requireIdentifier(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        requireField(reference, "reference");
        requireField(reason, "reason");

        Map<String, ResearchTrialLedger.Entry> registrations = registrations(registrationLedgerPath);
        ResearchTrialLedger.Entry registration = registrations.get(trialId);
        if (registration == null) throw new IOException("Research trial is not preregistered: " + trialId);

        FileChannel openedChannel;
        boolean created;
        try {
            openedChannel = FileChannel.open(outcomeLedgerPath, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            created = true;
        } catch (FileAlreadyExistsException alreadyExists) {
            openedChannel = FileChannel.open(outcomeLedgerPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
            created = false;
        }
        final FileChannel channel = openedChannel;
        try (channel; FileLock ignored = channel.lock()) {
            if (created) {
                writeFully(channel, ByteBuffer.wrap((HEADER + "\n").getBytes(StandardCharsets.UTF_8)));
                channel.force(true);
            }
            List<Entry> entries = parse(readBytes(channel), registrations);
            for (Entry entry : entries) {
                if (entry.eventId().equals(eventId)) throw new IOException("Duplicate outcome event ID: " + eventId);
            }
            String attemptKey = attemptKey(trialId, attemptId);
            Map<String, AttemptState> states = attemptStates(entries);
            AttemptState state = states.get(attemptKey);
            validateTransition(state, eventType);

            Instant occurredAt = Instant.now();
            if (!entries.isEmpty() && !occurredAt.isAfter(entries.getLast().occurredAtUtc())) {
                throw new IOException("System clock did not advance beyond the previous ledger event timestamp; retry the event");
            }
            long sequence = entries.size() + 1L;
            String payload = payload(sequence, occurredAt, eventId, trialId,
                    registration.recordHashSha256(), attemptId, eventType, reference, reason);
            String previousHash = entries.isEmpty()
                    ? HexFormat.of().formatHex(GENESIS_HASH)
                    : entries.getLast().recordHashSha256();
            String recordHash = hash(previousHash, payload);
            byte[] row = (payload + "\t" + previousHash + "\t" + recordHash + "\n")
                    .getBytes(StandardCharsets.UTF_8);
            channel.position(channel.size());
            writeFully(channel, ByteBuffer.wrap(row));
            channel.force(true);
            return new Entry(sequence, occurredAt, eventId, trialId,
                    registration.recordHashSha256(), attemptId, eventType, reference,
                    reason, previousHash, recordHash);
        }
    }

    /** Verifies the complete event chain and its registration bindings. */
    public static Summary verify(Path registrationLedgerPath, Path outcomeLedgerPath) throws IOException {
        Objects.requireNonNull(outcomeLedgerPath, "outcomeLedgerPath");
        Map<String, ResearchTrialLedger.Entry> registrations = registrations(registrationLedgerPath);
        try (FileChannel channel = FileChannel.open(outcomeLedgerPath, StandardOpenOption.READ);
             FileLock ignored = channel.lock(0L, Long.MAX_VALUE, true)) {
            List<Entry> entries = parse(readBytes(channel), registrations);
            Map<String, MutableTrialSummary> summaries = new LinkedHashMap<>();
            Map<String, AttemptState> attempts = attemptStates(entries);
            long started = 0;
            long produced = 0;
            long viewed = 0;
            long mechanicalFailure = 0;
            long abandoned = 0;
            for (Entry entry : entries) {
                MutableTrialSummary summary = summaries.computeIfAbsent(entry.trialId(), ignoredTrial -> new MutableTrialSummary());
                switch (entry.eventType()) {
                    case ATTEMPT_STARTED -> { summary.attemptCount++; started++; }
                    case OUTCOME_PRODUCED -> { summary.outcomesProduced++; produced++; }
                    case OUTCOME_VIEWED -> { summary.outcomeViews++; viewed++; }
                    case MECHANICAL_FAILURE_NO_OUTCOME -> { summary.mechanicalFailures++; mechanicalFailure++; }
                    case ABANDONED -> { summary.abandoned++; abandoned++; }
                }
            }
            List<TrialSummary> trialSummaries = new ArrayList<>();
            for (ResearchTrialLedger.Entry registration : registrations.values()) {
                MutableTrialSummary summary = summaries.getOrDefault(registration.trial().trialId(), new MutableTrialSummary());
                ExposureState exposure = exposureState(registration.trial().trialId(), attempts);
                trialSummaries.add(new TrialSummary(registration.trial().trialId(), summary.attemptCount,
                        summary.outcomesProduced, summary.outcomeViews, summary.mechanicalFailures,
                        summary.abandoned, exposure));
            }
            String finalHash = entries.isEmpty()
                    ? HexFormat.of().formatHex(GENESIS_HASH)
                    : entries.getLast().recordHashSha256();
            return new Summary(entries.size(), started, produced, viewed, mechanicalFailure,
                    abandoned, finalHash, trialSummaries);
        }
    }

    private static Map<String, ResearchTrialLedger.Entry> registrations(Path ledgerPath) throws IOException {
        Map<String, ResearchTrialLedger.Entry> entries = new LinkedHashMap<>();
        for (ResearchTrialLedger.Entry entry : ResearchTrialLedger.entries(ledgerPath)) {
            entries.put(entry.trial().trialId(), entry);
        }
        return entries;
    }

    private static List<Entry> parse(byte[] bytes,
            Map<String, ResearchTrialLedger.Entry> registrations) throws IOException {
        if (bytes.length == 0) throw new IOException("Research outcome ledger is empty");
        if (bytes.length > MAX_LEDGER_BYTES) throw new IOException("Research outcome ledger exceeds the supported size");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new IOException("Research outcome ledger is not valid UTF-8", invalidUtf8);
        }
        if (text.indexOf('\r') >= 0) throw new IOException("Research outcome ledger must use LF line endings");
        if (!text.endsWith("\n")) throw new IOException("Research outcome ledger ends with a partial record");
        String[] lines = text.split("\n", -1);
        if (!HEADER.equals(lines[0])) throw new IOException("Unsupported research outcome ledger header");

        List<Entry> entries = new ArrayList<>(Math.max(0, lines.length - 2));
        Set<String> eventIds = new HashSet<>();
        Map<String, AttemptState> states = new HashMap<>();
        String expectedPreviousHash = HexFormat.of().formatHex(GENESIS_HASH);
        Instant previousTimestamp = null;
        for (int lineIndex = 1; lineIndex < lines.length - 1; lineIndex++) {
            String line = lines[lineIndex];
            if (line.isEmpty()) throw new IOException("Blank record at line " + (lineIndex + 1));
            String[] fields = line.split("\t", -1);
            if (fields.length != 11) throw new IOException("Expected 11 fields at line " + (lineIndex + 1));
            long sequence;
            try {
                sequence = Long.parseLong(fields[0]);
            } catch (NumberFormatException invalidSequence) {
                throw new IOException("Invalid sequence at line " + (lineIndex + 1), invalidSequence);
            }
            if (sequence != lineIndex) throw new IOException("Non-contiguous sequence at line " + (lineIndex + 1));
            Instant occurredAt;
            try {
                occurredAt = Instant.parse(fields[1]);
            } catch (RuntimeException invalidTimestamp) {
                throw new IOException("Invalid event timestamp at line " + (lineIndex + 1), invalidTimestamp);
            }
            if (!occurredAt.toString().equals(fields[1])) throw new IOException("Non-canonical event timestamp at line " + (lineIndex + 1));
            if (previousTimestamp != null && !occurredAt.isAfter(previousTimestamp)) {
                throw new IOException("Event timestamps are not strictly increasing at line " + (lineIndex + 1));
            }
            EventType eventType;
            try {
                eventType = EventType.valueOf(fields[6]);
            } catch (IllegalArgumentException invalidType) {
                throw new IOException("Unknown event type at line " + (lineIndex + 1), invalidType);
            }
            requireIdentifierOnRead(fields[2], "eventId", lineIndex);
            requireIdentifierOnRead(fields[3], "trialId", lineIndex);
            requireHashOnRead(fields[4], "registration hash", lineIndex);
            requireIdentifierOnRead(fields[5], "attemptId", lineIndex);
            requireFieldOnRead(fields[7], "reference", lineIndex);
            requireFieldOnRead(fields[8], "reason", lineIndex);
            if (!eventIds.add(fields[2])) throw new IOException("Duplicate event ID at line " + (lineIndex + 1));
            ResearchTrialLedger.Entry registration = registrations.get(fields[3]);
            if (registration == null) throw new IOException("Unknown registered trial at line " + (lineIndex + 1));
            if (!registration.recordHashSha256().equals(fields[4])) {
                throw new IOException("Trial registration hash mismatch at line " + (lineIndex + 1));
            }
            String key = attemptKey(fields[3], fields[5]);
            validateTransition(states.get(key), eventType, lineIndex);
            if (!fields[9].equals(expectedPreviousHash)) throw new IOException("Hash-chain predecessor mismatch at line " + (lineIndex + 1));
            String canonicalPayload = String.join("\t", fields[0], fields[1], fields[2], fields[3],
                    fields[4], fields[5], fields[6], fields[7], fields[8]);
            String actualHash = hash(fields[9], canonicalPayload);
            if (!actualHash.equals(fields[10])) throw new IOException("Record hash mismatch at line " + (lineIndex + 1));
            entries.add(new Entry(sequence, occurredAt, fields[2], fields[3], fields[4],
                    fields[5], eventType, fields[7], fields[8], fields[9], fields[10]));
            updateState(states, key, eventType);
            expectedPreviousHash = actualHash;
            previousTimestamp = occurredAt;
        }
        return List.copyOf(entries);
    }

    private static Map<String, AttemptState> attemptStates(List<Entry> entries) {
        Map<String, AttemptState> states = new HashMap<>();
        for (Entry entry : entries) updateState(states, attemptKey(entry.trialId(), entry.attemptId()), entry.eventType());
        return states;
    }

    private static void validateTransition(AttemptState state, EventType eventType) throws IOException {
        validateTransition(state, eventType, -1);
    }

    private static void validateTransition(AttemptState state, EventType eventType, int lineIndex) throws IOException {
        String where = lineIndex < 0 ? "" : " at line " + (lineIndex + 1);
        switch (eventType) {
            case ATTEMPT_STARTED -> {
                if (state != null) throw new IOException("Attempt has already started" + where);
            }
            case OUTCOME_PRODUCED -> {
                if (state == null || !state.started || state.mechanicalFailure || state.abandoned || state.outcomeProduced) {
                    throw new IOException("Outcome cannot be produced in this attempt state" + where);
                }
            }
            case OUTCOME_VIEWED -> {
                if (state == null || !state.outcomeProduced || state.mechanicalFailure) {
                    throw new IOException("Outcome cannot be viewed in this attempt state" + where);
                }
            }
            case MECHANICAL_FAILURE_NO_OUTCOME -> {
                if (state == null || !state.started || state.outcomeProduced || state.mechanicalFailure || state.abandoned) {
                    throw new IOException("Mechanical failure is not valid after an outcome or terminal event" + where);
                }
            }
            case ABANDONED -> {
                if (state == null || !state.started || state.mechanicalFailure || state.abandoned) {
                    throw new IOException("Attempt cannot be abandoned in this state" + where);
                }
            }
        }
    }

    private static void updateState(Map<String, AttemptState> states, String key, EventType eventType) {
        AttemptState state = states.computeIfAbsent(key, ignored -> new AttemptState());
        switch (eventType) {
            case ATTEMPT_STARTED -> state.started = true;
            case OUTCOME_PRODUCED -> state.outcomeProduced = true;
            case OUTCOME_VIEWED -> state.outcomeViewed = true;
            case MECHANICAL_FAILURE_NO_OUTCOME -> state.mechanicalFailure = true;
            case ABANDONED -> state.abandoned = true;
        }
    }

    private static ExposureState exposureState(String trialId, Map<String, AttemptState> attempts) {
        boolean anyAttempt = false;
        boolean anyProduced = false;
        boolean anyViewed = false;
        boolean anyMechanicalFailure = false;
        boolean everyAttemptMechanicalFailure = true;
        for (Map.Entry<String, AttemptState> entry : attempts.entrySet()) {
            if (!entry.getKey().startsWith(trialId + "\u0000")) continue;
            anyAttempt = true;
            anyProduced |= entry.getValue().outcomeProduced;
            anyViewed |= entry.getValue().outcomeViewed;
            anyMechanicalFailure |= entry.getValue().mechanicalFailure;
            everyAttemptMechanicalFailure &= entry.getValue().mechanicalFailure;
        }
        if (anyViewed) return ExposureState.OUTCOME_VIEWED;
        if (anyProduced) return ExposureState.OUTCOME_PRODUCED_UNVIEWED;
        if (anyAttempt && anyMechanicalFailure && everyAttemptMechanicalFailure) return ExposureState.MECHANICAL_FAILURE_ONLY;
        if (anyAttempt) return ExposureState.ATTEMPTED_NO_OUTCOME_RECORDED;
        return ExposureState.NO_ATTEMPT_EVENTS;
    }

    private static String attemptKey(String trialId, String attemptId) {
        return trialId + "\u0000" + attemptId;
    }

    private static String payload(long sequence, Instant occurredAt, String eventId, String trialId,
            String registrationHash, String attemptId, EventType eventType, String reference, String reason) {
        return String.join("\t", Long.toString(sequence), occurredAt.toString(), eventId, trialId,
                registrationHash, attemptId, eventType.name(), reference, reason);
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
        if (size <= 0 || size > MAX_LEDGER_BYTES) throw new IOException("Invalid research outcome ledger size: " + size);
        ByteBuffer data = ByteBuffer.allocate((int) size);
        channel.position(0);
        while (data.hasRemaining()) {
            int read = channel.read(data);
            if (read < 0) throw new IOException("Unexpected end of research outcome ledger");
            if (read == 0) throw new IOException("Research outcome ledger read made no progress");
        }
        return data.array();
    }

    private static void writeFully(FileChannel channel, ByteBuffer data) throws IOException {
        while (data.hasRemaining()) {
            if (channel.write(data) <= 0) throw new IOException("Research outcome ledger write made no progress");
        }
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + IDENTIFIER.pattern());
        }
    }

    private static void requireField(String value, String name) {
        if (value == null || !(value.equals("-") || FIELD.matcher(value).matches())) {
            throw new IllegalArgumentException(name + " must be '-' or one safe token matching " + FIELD.pattern());
        }
    }

    private static void requireIdentifierOnRead(String value, String name, int lineIndex) throws IOException {
        if (!IDENTIFIER.matcher(value).matches()) throw new IOException("Invalid " + name + " at line " + (lineIndex + 1));
    }

    private static void requireFieldOnRead(String value, String name, int lineIndex) throws IOException {
        if (!(value.equals("-") || FIELD.matcher(value).matches())) {
            throw new IOException("Invalid " + name + " at line " + (lineIndex + 1));
        }
    }

    private static void requireHashOnRead(String value, String name, int lineIndex) throws IOException {
        if (!SHA256.matcher(value).matches()) throw new IOException("Invalid " + name + " at line " + (lineIndex + 1));
    }
}
