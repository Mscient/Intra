package com.mscient.intrapaper.riskgateway;

import com.mscient.intrapaper.riskgateway.RiskGateway.OrderProposal;
import com.mscient.intrapaper.riskgateway.RiskGateway.RiskConfiguration;
import com.mscient.intrapaper.riskgateway.RiskGateway.RiskDecision;
import com.mscient.intrapaper.riskgateway.RiskGateway.RiskEvent;
import com.mscient.intrapaper.riskgateway.RiskGateway.RiskState;
import com.mscient.intrapaper.riskgateway.RiskGatewayJournal.Verification;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/** Local single-writer facade; this is not a separate process or an order route. */
public final class DurableRiskGateway implements AutoCloseable {
    /** Retain separately from the journal, together with the caller's acknowledgement records. */
    public record RecoveryCheckpoint(RiskConfiguration configuration, Verification journal) {
        public RecoveryCheckpoint {
            Objects.requireNonNull(configuration, "configuration");
            Objects.requireNonNull(journal, "journal");
        }
    }

    private final Path journalPath;
    private final RiskConfiguration configuration;
    private final FileChannel runtimeLockChannel;
    private final FileLock runtimeLock;
    private final RiskGatewayJournal.AppendIO appendIO;
    private RiskGateway gateway;
    private Verification acknowledged;
    private boolean closed;
    private Throwable persistenceFailure;

    /** Creates a NEW journal exclusively. Existing journals require the checkpoint constructor. */
    public DurableRiskGateway(Path journalPath, RiskConfiguration configuration) throws IOException {
        this(journalPath, configuration, null, RiskGatewayJournal.FORCED_WRITE);
    }

    /** Recovers only the exact acknowledged history and configuration supplied by the caller. */
    public DurableRiskGateway(Path journalPath, RiskConfiguration configuration,
            RecoveryCheckpoint expected) throws IOException {
        this(journalPath, configuration, Objects.requireNonNull(expected, "expected"), RiskGatewayJournal.FORCED_WRITE);
    }

    // Only the focused offline suite substitutes the write/force operation.
    DurableRiskGateway(Path journalPath, RiskConfiguration configuration,
            RecoveryCheckpoint expected, RiskGatewayJournal.AppendIO appendIO) throws IOException {
        this.journalPath = Objects.requireNonNull(journalPath, "journalPath").toAbsolutePath().normalize();
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.appendIO = Objects.requireNonNull(appendIO, "appendIO");
        if (expected != null && !configuration.equals(expected.configuration())) {
            throw new IOException("Risk recovery configuration does not match the acknowledged checkpoint");
        }
        runtimeLockChannel = RiskGatewayJournal.openRuntimeLock(this.journalPath);
        try {
            runtimeLock = RiskGatewayJournal.acquire(runtimeLockChannel);
            if (expected == null) {
                try (FileChannel fresh = FileChannel.open(this.journalPath,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    fresh.force(true);
                }
            } else if (!Files.isRegularFile(this.journalPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Risk recovery requires the existing journal");
            }
            RiskGatewayJournal.ReadResult read = RiskGatewayJournal.read(this.journalPath);
            if (expected != null && !expected.journal().equals(read.verification())) {
                throw new IOException("Risk journal does not match the acknowledged checkpoint");
            }
            gateway = RiskGateway.restore(configuration, read.events());
            acknowledged = read.verification();
        } catch (IOException | RuntimeException failure) {
            try { runtimeLockChannel.close(); }
            catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /** Evaluates a proposal and persists an accepted reservation before returning the decision. */
    public synchronized RiskDecision evaluate(OrderProposal proposal) throws IOException {
        verifyAcknowledgedJournal();
        RiskGateway candidate = gateway.copyForPersistence();
        RiskDecision decision = candidate.evaluate(Objects.requireNonNull(proposal, "proposal"));
        publish(candidate);
        return decision;
    }

    /** Applies a state event and fsyncs it before the operation is reported as successful. */
    public synchronized void apply(RiskEvent event) throws IOException {
        verifyAcknowledgedJournal();
        RiskGateway candidate = gateway.copyForPersistence();
        candidate.applyEventForPersistence(Objects.requireNonNull(event, "event"));
        publish(candidate);
    }

    /** Last acknowledged state; unavailable after detected journal/persistence uncertainty. */
    public synchronized RiskState state() {
        ensureOpen();
        return gateway.state();
    }

    public synchronized RecoveryCheckpoint checkpoint() throws IOException {
        verifyAcknowledgedJournal();
        return new RecoveryCheckpoint(configuration, acknowledged);
    }

    private void verifyAcknowledgedJournal() throws IOException {
        ensureOpen();
        try {
            if (!Files.isRegularFile(journalPath, LinkOption.NOFOLLOW_LINKS)
                    || !acknowledged.equals(RiskGatewayJournal.verify(journalPath))) {
                throw new IOException("Risk journal does not match the acknowledged checkpoint");
            }
        } catch (IOException | RuntimeException failure) {
            persistenceFailure = failure;
            throw failure;
        }
    }

    private void publish(RiskGateway candidate) throws IOException {
        List<RiskEvent> events = candidate.state().eventHistory();
        try {
            if (events.size() == acknowledged.eventCount() + 1) {
                acknowledged = RiskGatewayJournal.appendOwned(journalPath, events.getLast(), acknowledged, appendIO);
            } else if (events.size() != acknowledged.eventCount()) {
                throw new IOException("One gateway call unexpectedly produced multiple journal events");
            }
            gateway = candidate;
        } catch (IOException | RuntimeException failure) {
            // A complete readable row does not prove force succeeded. Never resume automatically.
            persistenceFailure = failure;
            throw failure;
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Durable risk gateway is closed");
        if (persistenceFailure != null) {
            throw new IllegalStateException("Durable risk gateway is disabled after journal uncertainty; reconcile before recovery",
                    persistenceFailure);
        }
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
