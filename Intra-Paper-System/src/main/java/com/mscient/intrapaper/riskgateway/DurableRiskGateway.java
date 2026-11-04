package com.mscient.intrapaper.riskgateway;

import com.mscient.intrapaper.riskgateway.RiskGateway.OrderProposal;
import com.mscient.intrapaper.riskgateway.RiskGateway.RiskConfiguration;
import com.mscient.intrapaper.riskgateway.RiskGateway.RiskDecision;
import com.mscient.intrapaper.riskgateway.RiskGateway.RiskEvent;
import com.mscient.intrapaper.riskgateway.RiskGateway.RiskState;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/** Single-process durable facade that publishes state changes only after journal persistence succeeds. */
public final class DurableRiskGateway implements AutoCloseable {
    private final Path journalPath;
    private final RiskConfiguration configuration;
    private final FileChannel runtimeLockChannel;
    private final FileLock runtimeLock;
    private RiskGateway gateway;
    private boolean closed;
    private Throwable restoreFailure;

    public DurableRiskGateway(Path journalPath, RiskConfiguration configuration) throws IOException {
        this.journalPath = Objects.requireNonNull(journalPath, "journalPath").toAbsolutePath().normalize();
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        Path parent = this.journalPath.getParent();
        if (parent == null) throw new IOException("Durable risk gateway requires a journal parent directory");
        Files.createDirectories(parent);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(parent)) {
            throw new IOException("Durable risk gateway parent must be a real directory");
        }
        Path lockPath = parent.resolve("." + this.journalPath.getFileName() + ".runtime.lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(lockPath) || !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Durable risk gateway lock must be a regular non-symlink file");
        }
        runtimeLockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            try {
                runtimeLock = runtimeLockChannel.tryLock();
            } catch (OverlappingFileLockException alreadyOpen) {
                throw new IOException("Another durable risk gateway already owns this journal in this process", alreadyOpen);
            }
            if (runtimeLock == null) throw new IOException("Another durable risk gateway already owns this journal");
            RiskGatewayJournal.ReadResult read = RiskGatewayJournal.read(this.journalPath);
            gateway = RiskGateway.restore(configuration, read.events());
        } catch (IOException | RuntimeException failure) {
            runtimeLockChannel.close();
            throw failure;
        }
    }

    /** Evaluates a proposal and persists an accepted reservation before returning the decision. */
    public synchronized RiskDecision evaluate(OrderProposal proposal) throws IOException {
        ensureOpen();
        int priorEventCount = gateway.state().eventHistory().size();
        RiskDecision decision = gateway.evaluate(Objects.requireNonNull(proposal, "proposal"));
        persistNewEventsOrRestore(priorEventCount);
        return decision;
    }

    /** Applies a state event and fsyncs it before the operation is reported as successful. */
    public synchronized void apply(RiskEvent event) throws IOException {
        ensureOpen();
        int priorEventCount = gateway.state().eventHistory().size();
        try {
            gateway.applyEventForPersistence(Objects.requireNonNull(event, "event"));
        } catch (RuntimeException invalidEvent) {
            restoreAfterFailure(invalidEvent);
        }
        persistNewEventsOrRestore(priorEventCount);
    }

    public synchronized RiskState state() {
        ensureOpen();
        return gateway.state();
    }

    private void persistNewEventsOrRestore(int priorEventCount) throws IOException {
        List<RiskEvent> events = gateway.state().eventHistory();
        if (events.size() == priorEventCount) return;
        if (events.size() != priorEventCount + 1) {
            restoreAfterFailure(new IOException("One gateway call unexpectedly produced multiple journal events"));
            return;
        }
        try {
            RiskGatewayJournal.append(journalPath, events.getLast());
        } catch (IOException | RuntimeException persistenceFailure) {
            restoreAfterFailure(persistenceFailure);
        }
    }

    private void restoreAfterFailure(Throwable original) throws IOException {
        try {
            gateway = RiskGatewayJournal.restore(journalPath, configuration);
        } catch (IOException | RuntimeException restoreFailure) {
            gateway = null;
            this.restoreFailure = restoreFailure;
            original.addSuppressed(restoreFailure);
        }
        if (original instanceof IOException io) throw io;
        if (original instanceof RuntimeException runtime) throw runtime;
        throw new IOException("Risk gateway journal persistence failed", original);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Durable risk gateway is closed");
        if (gateway == null) throw new IllegalStateException("Durable risk gateway state could not be restored", restoreFailure);
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try {
            runtimeLock.release();
        } finally {
            runtimeLockChannel.close();
        }
    }
}
