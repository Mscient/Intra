package com.mscient.intrapaper.riskgateway;

import com.mscient.intrapaper.riskgateway.RiskGateway.CancellationEvent;
import com.mscient.intrapaper.riskgateway.RiskGateway.ExecutionEvent;
import com.mscient.intrapaper.riskgateway.RiskGateway.InstrumentMark;
import com.mscient.intrapaper.riskgateway.RiskGateway.MarginInput;
import com.mscient.intrapaper.riskgateway.RiskGateway.MarkToMarketEvent;
import com.mscient.intrapaper.riskgateway.RiskGateway.OrderAcceptedEvent;
import com.mscient.intrapaper.riskgateway.RiskGateway.OrderProposal;
import com.mscient.intrapaper.riskgateway.RiskGateway.OrderSide;
import com.mscient.intrapaper.riskgateway.RiskGateway.RiskEvent;
import com.mscient.intrapaper.riskgateway.RiskGateway.SessionStartEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Durable, locked, hash-chained event storage for caller-managed gateway recovery. */
public final class RiskGatewayJournal {
    private static final String GENESIS = "0".repeat(64);
    private static final int MAX_FILE_BYTES = 256 * 1024 * 1024;
    private static final int MAX_EVENT_BYTES = 1_048_576;
    private static final int MAX_COLLECTION_SIZE = 100_000;

    public record Verification(long eventCount, String finalHash, Optional<Instant> lastEventAt) { }
    public record ReadResult(List<RiskEvent> events, Verification verification) {
        public ReadResult { events = List.copyOf(events); }
    }

    private record Parsed(List<RiskEvent> events, Verification verification,
            Map<String, byte[]> payloadsByEventId) { }

    private RiskGatewayJournal() { }

    /** Package-private deterministic fault seam; production always writes fully and forces. */
    @FunctionalInterface
    interface AppendIO {
        void writeAndForce(FileChannel channel, ByteBuffer row) throws IOException;
    }

    static final AppendIO FORCED_WRITE = (channel, row) -> {
        while (row.hasRemaining()) channel.write(row);
        channel.force(true);
    };

    /** Appends and fsyncs one event. An identical existing event ID is an idempotent no-op. */
    public static Verification append(Path journalPath, RiskEvent event) throws IOException {
        if (journalPath == null || event == null) throw new NullPointerException("journalPath/event");
        Path path = journalPath.toAbsolutePath().normalize();
        try (FileChannel runtime = openRuntimeLock(path); FileLock lease = acquire(runtime)) {
            return appendOwned(path, event, null, FORCED_WRITE);
        }
    }

    static FileChannel openRuntimeLock(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent == null) throw new IOException("Risk journal requires a parent directory");
        Files.createDirectories(parent);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw new IOException("Risk journal parent must be a real directory");
        }
        Path lockPath = parent.resolve("." + path.getFileName() + ".runtime.lock");
        rejectSymlink(lockPath, "risk gateway runtime lock");
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    /** Caller must hold the lifetime lease; comparison and append share the journal lock. */
    static Verification appendOwned(Path path, RiskEvent event, Verification expected, AppendIO io) throws IOException {
        Path parent = path.getParent();
        if (parent == null) throw new IOException("Risk journal requires a parent directory");
        Files.createDirectories(parent);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw new IOException("Risk journal parent must be a real directory");
        }
        Path lockPath = parent.resolve("." + path.getFileName() + ".lock");
        rejectSymlink(lockPath, "risk journal lock");
        try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = acquire(lockChannel);
            try (lock) {
                rejectJournalPath(path);
                byte[] currentBytes = readBytes(path);
                Parsed current = parse(currentBytes);
                if (expected != null && !expected.equals(current.verification())) {
                    throw new IOException("Risk journal does not match the acknowledged checkpoint");
                }
                byte[] payload = encode(event);
                byte[] priorPayload = current.payloadsByEventId().get(event.eventId());
                if (priorPayload != null) {
                    if (!java.util.Arrays.equals(priorPayload, payload)) {
                        throw new IOException("Risk event ID was reused with conflicting payload: " + event.eventId());
                    }
                    return current.verification();
                }
                if (current.verification().lastEventAt().isPresent()
                        && event.occurredAt().isBefore(current.verification().lastEventAt().orElseThrow())) {
                    throw new IOException("Risk journal event timestamps must be nondecreasing");
                }
                long sequence = Math.addExact(current.verification().eventCount(), 1);
                String encoded = Base64.getEncoder().encodeToString(payload);
                String hash = eventHash(sequence, current.verification().finalHash(), encoded);
                String row = sequence + "\t" + current.verification().finalHash() + "\t" + encoded + "\t" + hash + "\n";
                byte[] rowBytes = row.getBytes(StandardCharsets.US_ASCII);
                if ((long) currentBytes.length + rowBytes.length > MAX_FILE_BYTES) {
                    throw new IOException("Risk journal exceeds the configured 256 MiB limit");
                }
                try (FileChannel journal = FileChannel.open(path, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    io.writeAndForce(journal, ByteBuffer.wrap(rowBytes));
                }
                return new Verification(sequence, hash, Optional.of(event.occurredAt()));
            }
        }
    }

    public static Verification verify(Path journalPath) throws IOException {
        return read(journalPath).verification();
    }

    public static ReadResult read(Path journalPath) throws IOException {
        if (journalPath == null) throw new NullPointerException("journalPath");
        Path path = journalPath.toAbsolutePath().normalize();
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw new IOException("Risk journal parent must be a real directory");
        }
        Path lockPath = parent.resolve("." + path.getFileName() + ".lock");
        rejectSymlink(lockPath, "risk journal lock");
        try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock = acquire(lockChannel);
            try (lock) {
                rejectJournalPath(path);
                Parsed parsed = parse(readBytes(path));
                return new ReadResult(parsed.events(), parsed.verification());
            }
        }
    }

    public static RiskGateway restore(Path journalPath, RiskGateway.RiskConfiguration configuration) throws IOException {
        ReadResult read = read(journalPath);
        return RiskGateway.restore(configuration, read.events());
    }

    static FileLock acquire(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Risk journal is already locked by another process");
            return lock;
        } catch (OverlappingFileLockException lockedHere) {
            throw new IOException("Risk journal is already locked in this process", lockedHere);
        }
    }

    private static void rejectJournalPath(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Risk journal must be a regular non-symlink file");
        }
    }

    private static void rejectSymlink(Path path, String label) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException(label + " path must be a regular non-symlink file");
        }
    }

    private static byte[] readBytes(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new byte[0];
        long size = Files.size(path);
        if (size > MAX_FILE_BYTES) throw new IOException("Risk journal exceeds the configured 256 MiB limit");
        return Files.readAllBytes(path);
    }

    private static Parsed parse(byte[] bytes) throws IOException {
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Risk journal exceeds the configured 256 MiB limit");
        if (bytes.length == 0) return new Parsed(List.of(),
                new Verification(0, GENESIS, Optional.empty()), Map.of());
        if (bytes[bytes.length - 1] != '\n') throw new IOException("Risk journal has a truncated final row");
        String text = new String(bytes, StandardCharsets.US_ASCII);
        if (text.indexOf('\r') >= 0) throw new IOException("Risk journal rows must use canonical LF line endings");
        List<RiskEvent> events = new ArrayList<>();
        Map<String, byte[]> payloadsByEventId = new HashMap<>();
        String head = GENESIS;
        Optional<Instant> lastTimestamp = Optional.empty();
        String[] rows = text.substring(0, text.length() - 1).split("\n", -1);
        for (int index = 0; index < rows.length; index++) {
            String[] fields = rows[index].split("\t", -1);
            if (fields.length != 4) throw new IOException("Malformed risk journal row " + (index + 1));
            long sequence;
            try { sequence = Long.parseLong(fields[0]); }
            catch (NumberFormatException invalid) { throw new IOException("Invalid risk journal sequence", invalid); }
            if (sequence != index + 1L || !Long.toString(sequence).equals(fields[0]) || !fields[1].equals(head)) {
                throw new IOException("Risk journal sequence or previous hash mismatch at row " + (index + 1));
            }
            byte[] payload;
            try {
                if (fields[2].length() > 4 * ((MAX_EVENT_BYTES + 2) / 3)) {
                    throw new IOException("Risk event exceeds the 1 MiB size limit");
                }
                payload = Base64.getDecoder().decode(fields[2]);
                if (!Base64.getEncoder().encodeToString(payload).equals(fields[2])) {
                    throw new IllegalArgumentException("noncanonical base64");
                }
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid risk journal payload encoding at row " + (index + 1), invalid);
            }
            RiskEvent event = decode(payload);
            if (lastTimestamp.isPresent() && event.occurredAt().isBefore(lastTimestamp.orElseThrow())) {
                throw new IOException("Risk journal event timestamps decrease at row " + (index + 1));
            }
            byte[] prior = payloadsByEventId.putIfAbsent(event.eventId(), payload);
            if (prior != null) {
                if (!java.util.Arrays.equals(prior, payload)) {
                    throw new IOException("Risk journal contains conflicting event ID reuse: " + event.eventId());
                }
                throw new IOException("Risk journal contains a duplicate event ID: " + event.eventId());
            }
            String calculated = eventHash(sequence, head, fields[2]);
            if (!calculated.equals(fields[3])) throw new IOException("Risk journal hash mismatch at row " + (index + 1));
            events.add(event);
            head = calculated;
            lastTimestamp = Optional.of(event.occurredAt());
        }
        return new Parsed(List.copyOf(events), new Verification(events.size(), head, lastTimestamp), Map.copyOf(payloadsByEventId));
    }

    private static String eventHash(long sequence, String previousHash, String payloadBase64) {
        String canonical = "INTRA_RISK_GATEWAY_JOURNAL_V1\n" + sequence + "\n" + previousHash + "\n" + payloadBase64 + "\n";
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static byte[] encode(RiskEvent event) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(1);
            if (event instanceof SessionStartEvent start) {
                out.writeByte(1); write(out, start.eventId()); write(out, start.sessionId()); write(out, start.occurredAt().toString());
                writeMarks(out, start.openingMarks());
            } else if (event instanceof OrderAcceptedEvent accepted) {
                OrderProposal proposal = accepted.proposal();
                out.writeByte(2); write(out, proposal.proposalId()); write(out, proposal.sessionId());
                write(out, proposal.occurredAt().toString()); write(out, proposal.instrumentId());
                write(out, proposal.side().name()); out.writeLong(proposal.quantity());
                writeOptionalDecimal(out, proposal.limitPrice()); writeOptionalMargin(out, proposal.marginInput());
            } else if (event instanceof ExecutionEvent execution) {
                out.writeByte(3); write(out, execution.eventId()); write(out, execution.sessionId());
                write(out, execution.occurredAt().toString()); write(out, execution.orderId());
                write(out, execution.instrumentId()); write(out, execution.side().name());
                out.writeLong(execution.quantity()); write(out, execution.fillPrice().toString());
            } else if (event instanceof CancellationEvent cancellation) {
                out.writeByte(4); write(out, cancellation.eventId()); write(out, cancellation.sessionId());
                write(out, cancellation.occurredAt().toString()); write(out, cancellation.orderId());
            } else if (event instanceof MarkToMarketEvent mark) {
                out.writeByte(5); write(out, mark.eventId()); write(out, mark.sessionId());
                write(out, mark.occurredAt().toString()); writeMarks(out, mark.marks());
            } else {
                throw new IOException("Unsupported risk event type: " + event.getClass().getName());
            }
        }
        if (bytes.size() > MAX_EVENT_BYTES) throw new IOException("Risk event exceeds the 1 MiB size limit");
        return bytes.toByteArray();
    }

    private static RiskEvent decode(byte[] payload) throws IOException {
        if (payload.length > MAX_EVENT_BYTES) throw new IOException("Risk event exceeds the 1 MiB size limit");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != 1) throw new IOException("Unsupported risk event schema version");
            int type = in.readUnsignedByte();
            RiskEvent event = switch (type) {
                case 1 -> new SessionStartEvent(read(in), read(in), Instant.parse(read(in)), readMarks(in));
                case 2 -> {
                    String proposal = read(in), session = read(in), time = read(in), instrument = read(in);
                    OrderSide side = OrderSide.valueOf(read(in)); long quantity = in.readLong();
                    yield new OrderAcceptedEvent(new OrderProposal(proposal, session, Instant.parse(time), instrument,
                            side, quantity, readOptionalDecimal(in), readOptionalMargin(in)));
                }
                case 3 -> new ExecutionEvent(read(in), read(in), Instant.parse(read(in)), read(in), read(in),
                        OrderSide.valueOf(read(in)), in.readLong(), new BigDecimal(read(in)));
                case 4 -> new CancellationEvent(read(in), read(in), Instant.parse(read(in)), read(in));
                case 5 -> new MarkToMarketEvent(read(in), read(in), Instant.parse(read(in)), readMarks(in));
                default -> throw new IOException("Unknown risk event type code: " + type);
            };
            if (in.available() != 0) throw new IOException("Trailing bytes in risk event payload");
            if (!java.util.Arrays.equals(payload, encode(event))) {
                throw new IOException("Risk event payload is not canonical");
            }
            return event;
        } catch (EOFException invalid) {
            throw new IOException("Malformed risk event payload", invalid);
        } catch (RuntimeException invalid) {
            throw new IOException("Malformed risk event payload", invalid);
        }
    }

    private static void writeMarks(DataOutputStream out, Map<String, InstrumentMark> marks) throws IOException {
        TreeMap<String, InstrumentMark> ordered = new TreeMap<>(marks);
        writeCount(out, ordered.size());
        for (Map.Entry<String, InstrumentMark> entry : ordered.entrySet()) {
            write(out, entry.getKey());
            writeOptionalDecimal(out, entry.getValue().markPrice());
            writeOptionalMargin(out, entry.getValue().marginInput());
        }
    }

    private static Map<String, InstrumentMark> readMarks(DataInputStream in) throws IOException {
        int count = readCount(in);
        TreeMap<String, InstrumentMark> marks = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            String instrument = read(in);
            InstrumentMark mark = new InstrumentMark(readOptionalDecimal(in), readOptionalMargin(in));
            if (marks.putIfAbsent(instrument, mark) != null) throw new IOException("Duplicate risk mark instrument");
        }
        return Map.copyOf(marks);
    }

    private static void writeOptionalMargin(DataOutputStream out, Optional<MarginInput> value) throws IOException {
        out.writeBoolean(value.isPresent());
        if (value.isEmpty()) return;
        MarginInput margin = value.orElseThrow();
        write(out, margin.instrumentId()); write(out, margin.amountPerContract().toString());
        write(out, margin.source()); write(out, margin.reference()); write(out, margin.sessionId()); write(out, margin.asOf().toString());
    }

    private static Optional<MarginInput> readOptionalMargin(DataInputStream in) throws IOException {
        if (!in.readBoolean()) return Optional.empty();
        return Optional.of(new MarginInput(read(in), new BigDecimal(read(in)), read(in), read(in), read(in), Instant.parse(read(in))));
    }

    private static void writeOptionalDecimal(DataOutputStream out, Optional<BigDecimal> value) throws IOException {
        out.writeBoolean(value.isPresent());
        if (value.isPresent()) write(out, value.orElseThrow().toString());
    }

    private static Optional<BigDecimal> readOptionalDecimal(DataInputStream in) throws IOException {
        return in.readBoolean() ? Optional.of(new BigDecimal(read(in))) : Optional.empty();
    }

    private static void writeCount(DataOutputStream out, int count) throws IOException {
        if (count < 0 || count > MAX_COLLECTION_SIZE) throw new IOException("Risk event collection exceeds size limit");
        out.writeInt(count);
    }

    private static int readCount(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_COLLECTION_SIZE) throw new IOException("Risk event collection exceeds size limit");
        return count;
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        if (value.length() > 65_536) throw new IOException("Risk event text field exceeds 64 KiB");
        ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
        } catch (java.nio.charset.CharacterCodingException invalidText) {
            throw new IOException("Risk event text is not valid Unicode", invalidText);
        }
        if (encoded.remaining() > 65_536) throw new IOException("Risk event text field exceeds 64 KiB");
        if ((long) out.size() + Integer.BYTES + encoded.remaining() > MAX_EVENT_BYTES) {
            throw new IOException("Risk event exceeds the 1 MiB size limit");
        }
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String read(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > 65_536) throw new IOException("Risk event text field exceeds 64 KiB");
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new EOFException();
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException invalidUtf8) {
            throw new IOException("Risk event field is not valid UTF-8", invalidUtf8);
        }
    }
}
