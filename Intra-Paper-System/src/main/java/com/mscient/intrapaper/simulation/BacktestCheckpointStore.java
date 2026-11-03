package com.mscient.intrapaper.simulation;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.provenance.RunConfig;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

/** Locked append-only local checkpoint chain for deterministic historical replays. */
public final class BacktestCheckpointStore implements AutoCloseable {
    private static final byte[] MAGIC = "INTRACKP".getBytes(StandardCharsets.US_ASCII);
    private static final int FORMAT_VERSION = 1;
    private static final long MAX_CHECKPOINT_BYTES = 512L * 1024 * 1024;
    private static final String GENESIS_HASH = "0".repeat(64);

    public record Restored(BacktestCheckpoint latest,
            List<StrategySignal> signals,
            List<SimOrder> orders,
            List<SimOrderEvent> orderEvents,
            List<SimFill> fills,
            List<SimFeeBreakdown> feeBreakdowns,
            List<SimTrade> trades,
            List<EquityPoint> equityCurve) {
        public Restored {
            signals = List.copyOf(signals);
            orders = List.copyOf(orders);
            orderEvents = List.copyOf(orderEvents);
            fills = List.copyOf(fills);
            feeBreakdowns = List.copyOf(feeBreakdowns);
            trades = List.copyOf(trades);
            equityCurve = List.copyOf(equityCurve);
        }
    }

    private final String runId;
    private final Path directory;
    private final Path lockPath;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private int lastNextBarIndex;
    private String lastCheckpointSha256 = GENESIS_HASH;
    private int savedSignalCount;
    private int savedOrderCount;
    private int savedOrderEventCount;
    private int savedFillCount;
    private int savedFeeBreakdownCount;
    private int savedTradeCount;
    private int savedEquityPointCount;

    public BacktestCheckpointStore(Path checkpointRoot, String runId) throws IOException {
        if (runId == null || !runId.matches("[0-9a-f]{24}")) {
            throw new IllegalArgumentException("runId must be a 24-character lowercase content ID");
        }
        this.runId = runId;
        Path root = checkpointRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        this.directory = root.resolve(runId);
        Files.createDirectories(directory);
        this.lockPath = directory.resolve(".lock");
        this.lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try {
            this.lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("Checkpoint store is already in use for run " + runId);
        } catch (OverlappingFileLockException activeInThisProcess) {
            lockChannel.close();
            throw new IOException("Checkpoint store is already in use for run " + runId, activeInThisProcess);
        } catch (IOException | RuntimeException failure) {
            lockChannel.close();
            throw failure;
        }
    }

    public Optional<Restored> restore(ValidatedDataset dataset, RunConfig config, Strategy strategy)
            throws IOException {
        requireOpen();
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(strategy, "strategy");
        if (!RunIdentity.contentId(dataset, config, strategy).equals(runId)) {
            throw new IOException("Checkpoint store path does not match the frozen run content ID");
        }
        List<Path> files = checkpointFiles();
        if (files.isEmpty()) return Optional.empty();
        List<StrategySignal> signals = new ArrayList<>();
        List<SimOrder> orders = new ArrayList<>();
        List<SimOrderEvent> events = new ArrayList<>();
        List<SimFill> fills = new ArrayList<>();
        List<SimFeeBreakdown> feeBreakdowns = new ArrayList<>();
        List<SimTrade> trades = new ArrayList<>();
        List<EquityPoint> equity = new ArrayList<>();
        int expectedPreviousIndex = 0;
        String expectedPreviousHash = GENESIS_HASH;
        BacktestCheckpoint latest = null;
        for (Path file : files) {
            BacktestCheckpoint checkpoint = read(file);
            requireMetadata(checkpoint, dataset, config, strategy);
            int filenameIndex = parseCheckpointIndex(file);
            if (checkpoint.nextBarIndex() != filenameIndex
                    || checkpoint.previousNextBarIndex() != expectedPreviousIndex
                    || !checkpoint.previousCheckpointSha256().equals(expectedPreviousHash)) {
                throw new IOException("Checkpoint chain has a gap, reordered snapshot, or mismatched hash link: " + file);
            }
            if (checkpoint.nextBarIndex() > dataset.bars().size()) {
                throw new IOException("Checkpoint index exceeds the frozen dataset length: " + file);
            }
            signals.addAll(checkpoint.signalDelta());
            orders.addAll(checkpoint.orderDelta());
            events.addAll(checkpoint.orderEventDelta());
            fills.addAll(checkpoint.fillDelta());
            feeBreakdowns.addAll(checkpoint.feeBreakdownDelta());
            trades.addAll(checkpoint.tradeDelta());
            equity.addAll(checkpoint.equityDelta());
            if (signals.size() != checkpoint.nextBarIndex() || equity.size() != checkpoint.nextBarIndex()) {
                throw new IOException("Checkpoint signal/equity prefix does not match the completed bar count: " + file);
            }
            if (checkpoint.pending() != null && checkpoint.pending().orderIndex() >= orders.size()) {
                throw new IOException("Checkpoint pending order refers to a missing ledger order: " + file);
            }
            if (!checkpoint.activeSession().equals(checkpoint.riskGate().sessionDate())) {
                throw new IOException("Checkpoint risk session does not match engine session: " + file);
            }
            latest = checkpoint;
            expectedPreviousIndex = checkpoint.nextBarIndex();
            expectedPreviousHash = sha256(Files.readAllBytes(file));
        }
        if (latest == null) return Optional.empty();
        this.lastNextBarIndex = latest.nextBarIndex();
        this.lastCheckpointSha256 = expectedPreviousHash;
        this.savedSignalCount = signals.size();
        this.savedOrderCount = orders.size();
        this.savedOrderEventCount = events.size();
        this.savedFillCount = fills.size();
        this.savedFeeBreakdownCount = feeBreakdowns.size();
        this.savedTradeCount = trades.size();
        this.savedEquityPointCount = equity.size();
        return Optional.of(new Restored(latest, signals, orders, events, fills, feeBreakdowns, trades, equity));
    }

    public BacktestCheckpoint newCheckpoint(int nextBarIndex, ValidatedDataset dataset, RunConfig config,
            Strategy strategy, List<StrategySignal> signals, List<SimOrder> orders,
            List<SimOrderEvent> orderEvents, List<SimFill> fills, List<SimFeeBreakdown> feeBreakdowns,
            List<SimTrade> trades, List<EquityPoint> equityCurve, java.math.BigDecimal realizedPnlInr,
            java.math.BigDecimal feesPaidInr, java.math.BigDecimal slippageInr,
            java.math.BigDecimal averageEntryPrice, int positionLots, BacktestCheckpoint.PendingState pending,
            BacktestCheckpoint.OpenTradeState openTrade, java.time.LocalDate activeSession,
            java.math.BigDecimal netAtPriorClose, int riskHalts, RiskGate.Snapshot riskGate,
            java.math.BigDecimal highWaterInr, java.math.BigDecimal maxDrawdownInr) {
        requireOpen();
        return new BacktestCheckpoint(1, runId, dataset.barsSha256(), config.configSha256(), config.commitId(),
                strategy.id(), strategy.parameters(), lastNextBarIndex, nextBarIndex, lastCheckpointSha256,
                delta(signals, savedSignalCount), delta(orders, savedOrderCount),
                delta(orderEvents, savedOrderEventCount), delta(fills, savedFillCount),
                delta(feeBreakdowns, savedFeeBreakdownCount), delta(trades, savedTradeCount),
                delta(equityCurve, savedEquityPointCount), realizedPnlInr, feesPaidInr, slippageInr,
                averageEntryPrice, positionLots, pending, openTrade, activeSession, netAtPriorClose,
                riskHalts, riskGate, highWaterInr, maxDrawdownInr);
    }

    public boolean save(BacktestCheckpoint checkpoint) throws IOException {
        requireOpen();
        if (!checkpoint.runId().equals(runId)) throw new IllegalArgumentException("checkpoint run ID mismatch");
        if (checkpoint.nextBarIndex() == lastNextBarIndex) return false;
        if (checkpoint.previousNextBarIndex() != lastNextBarIndex
                || !checkpoint.previousCheckpointSha256().equals(lastCheckpointSha256)) {
            throw new IllegalStateException("checkpoint does not extend the currently restored chain");
        }
        byte[] payload = serialize(checkpoint);
        if (payload.length > MAX_CHECKPOINT_BYTES) throw new IOException("Serialized checkpoint exceeds the configured size limit");
        byte[] payloadHash = digest(payload);
        ByteBuffer envelope = ByteBuffer.allocate(MAGIC.length + Integer.BYTES * 2 + payload.length + payloadHash.length);
        envelope.put(MAGIC).putInt(FORMAT_VERSION).putInt(payload.length).put(payload).put(payloadHash).flip();
        Path target = checkpointPath(checkpoint.nextBarIndex());
        Path temporary = directory.resolve(".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                while (envelope.hasRemaining()) channel.write(envelope);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException alreadyExists) {
                throw new IOException("Checkpoint already exists at completed bar " + checkpoint.nextBarIndex(), alreadyExists);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        lastNextBarIndex = checkpoint.nextBarIndex();
        lastCheckpointSha256 = sha256(Files.readAllBytes(target));
        savedSignalCount += checkpoint.signalDelta().size();
        savedOrderCount += checkpoint.orderDelta().size();
        savedOrderEventCount += checkpoint.orderEventDelta().size();
        savedFillCount += checkpoint.fillDelta().size();
        savedFeeBreakdownCount += checkpoint.feeBreakdownDelta().size();
        savedTradeCount += checkpoint.tradeDelta().size();
        savedEquityPointCount += checkpoint.equityDelta().size();
        return true;
    }

    /** Called only after the complete run folder has been atomically published. */
    public void clearAfterPublish() throws IOException {
        requireOpen();
        for (Path checkpoint : checkpointFiles()) Files.deleteIfExists(checkpoint);
        try (Stream<Path> entries = Files.list(directory)) {
            for (Path temporary : entries.filter(path -> path.getFileName().toString().startsWith(".tmp-")).toList()) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static <T> List<T> delta(List<T> all, int from) {
        if (from < 0 || from > all.size()) throw new IllegalStateException("checkpoint ledger count exceeds current state");
        return List.copyOf(all.subList(from, all.size()));
    }

    private List<Path> checkpointFiles() throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(path -> path.getFileName().toString().matches("checkpoint-[0-9]{10}\\.bin"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }

    private BacktestCheckpoint read(Path path) throws IOException {
        long size = Files.size(path);
        if (size < MAGIC.length + Integer.BYTES * 2 + 32 || size > MAX_CHECKPOINT_BYTES + 64) {
            throw new IOException("Invalid checkpoint file size: " + path);
        }
        byte[] bytes = Files.readAllBytes(path);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        byte[] magic = new byte[MAGIC.length];
        buffer.get(magic);
        if (!MessageDigest.isEqual(magic, MAGIC)) throw new IOException("Invalid checkpoint file magic: " + path);
        if (buffer.getInt() != FORMAT_VERSION) throw new IOException("Unsupported checkpoint file version: " + path);
        int length = buffer.getInt();
        if (length < 1 || length != buffer.remaining() - 32) throw new IOException("Invalid checkpoint payload length: " + path);
        byte[] payload = new byte[length];
        buffer.get(payload);
        byte[] storedHash = new byte[32];
        buffer.get(storedHash);
        if (!MessageDigest.isEqual(storedHash, digest(payload))) throw new IOException("Checkpoint payload hash mismatch: " + path);
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(payload))) {
            input.setObjectInputFilter(BacktestCheckpointStore::filter);
            Object value = input.readObject();
            if (!(value instanceof BacktestCheckpoint checkpoint) || input.read() != -1) {
                throw new IOException("Unexpected checkpoint payload object or trailing data: " + path);
            }
            return checkpoint;
        } catch (ClassNotFoundException | RuntimeException invalid) {
            throw new IOException("Unable to decode checkpoint: " + path, invalid);
        }
    }

    private static ObjectInputFilter.Status filter(ObjectInputFilter.FilterInfo info) {
        if (info.depth() > 80 || info.references() > 2_000_000 || info.streamBytes() > MAX_CHECKPOINT_BYTES
                || info.arrayLength() > 1_000_000) return ObjectInputFilter.Status.REJECTED;
        Class<?> type = info.serialClass();
        if (type == null) return ObjectInputFilter.Status.UNDECIDED;
        if (type.isArray()) {
            Class<?> component = type.getComponentType();
            return component.isPrimitive() || component == Object.class || allowedClass(component)
                    ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
        }
        return allowedClass(type) ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
    }

    private static boolean allowedClass(Class<?> type) {
        String name = type.getName();
        return type.isPrimitive()
                || name.equals("com.mscient.intrapaper.simulation.BacktestCheckpoint")
                || name.equals("com.mscient.intrapaper.simulation.BacktestCheckpoint$PendingState")
                || name.equals("com.mscient.intrapaper.simulation.BacktestCheckpoint$OpenTradeState")
                || name.equals("com.mscient.intrapaper.simulation.RiskGate$Snapshot")
                || name.equals("com.mscient.intrapaper.simulation.StrategySignal")
                || name.equals("com.mscient.intrapaper.simulation.SimOrder")
                || name.equals("com.mscient.intrapaper.simulation.SimOrderEvent")
                || name.equals("com.mscient.intrapaper.simulation.SimFill")
                || name.equals("com.mscient.intrapaper.simulation.SimFeeBreakdown")
                || name.equals("com.mscient.intrapaper.simulation.SimTrade")
                || name.equals("com.mscient.intrapaper.simulation.EquityPoint")
                || name.equals("com.mscient.intrapaper.reference.ContractKind")
                || name.equals("java.lang.String") || name.equals("java.lang.Integer")
                || name.equals("java.lang.Long") || name.equals("java.lang.Boolean")
                || name.equals("java.lang.Number") || name.equals("java.lang.Enum")
                || name.equals("java.math.BigDecimal") || name.equals("java.math.BigInteger")
                || name.equals("java.time.Ser")
                || name.equals("java.util.ArrayList") || name.equals("java.util.HashMap")
                || name.equals("java.util.TreeMap") || name.equals("java.util.CollSer")
                || name.startsWith("java.util.Collections$Unmodifiable")
                || name.startsWith("java.util.ImmutableCollections$");
    }

    private void requireMetadata(BacktestCheckpoint checkpoint, ValidatedDataset dataset,
            RunConfig config, Strategy strategy) throws IOException {
        if (!checkpoint.runId().equals(runId)
                || !checkpoint.barsSha256().equals(dataset.barsSha256())
                || !checkpoint.configSha256().equals(config.configSha256())
                || !checkpoint.buildCommit().equals(config.commitId())
                || !checkpoint.strategyId().equals(strategy.id())
                || !checkpoint.strategyParameters().equals(new TreeMap<>(strategy.parameters()))) {
            throw new IOException("Checkpoint frozen inputs or strategy identity do not match this replay");
        }
    }

    private Path checkpointPath(int nextBarIndex) {
        return directory.resolve(String.format("checkpoint-%010d.bin", nextBarIndex));
    }

    private static int parseCheckpointIndex(Path path) throws IOException {
        String filename = path.getFileName().toString();
        try {
            return Integer.parseInt(filename.substring("checkpoint-".length(), filename.length() - ".bin".length()));
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid checkpoint filename: " + filename, invalid);
        }
    }

    private static byte[] serialize(BacktestCheckpoint checkpoint) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(checkpoint);
        }
        return bytes.toByteArray();
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest(bytes));
    }

    private void requireOpen() {
        if (!lock.isValid()) throw new IllegalStateException("checkpoint store is closed");
    }

    @Override
    public void close() throws IOException {
        if (lock.isValid()) lock.release();
        if (lockChannel.isOpen()) lockChannel.close();
    }
}
