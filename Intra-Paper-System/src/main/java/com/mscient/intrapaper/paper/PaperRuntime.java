package com.mscient.intrapaper.paper;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.marketdata.*;
import com.mscient.intrapaper.provenance.*;
import com.mscient.intrapaper.reference.TimestampRole;
import com.mscient.intrapaper.simulation.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Local forward-session operating mode. The only provider admitted by the type boundary is local. */
public final class PaperRuntime implements AutoCloseable {
    public enum State { READY, RUNNING, DEGRADED, HALTED, STOPPED }
    private final ValidatedDataset reference;
    private final RunConfig config;
    private final LocalDate sessionDate;
    private final List<PaperEvent> source;
    private final BacktestEngine.Session core;
    private final PaperSessionClock clock = new PaperSessionClock();
    private final QuoteQualityPolicy quality;
    private final Duration interval;
    private final Instant sessionOpen, sessionClose;
    private final Path directory;
    private final String identity;
    private final List<String> observations = new ArrayList<>();
    private final Map<Long, String> seen = new HashMap<>();
    private final Map<Long, String> seenQuotes = new HashMap<>();
    private PaperSessionStore store;
    private State state = State.READY;
    private String reason = "PREOPEN_PASSED_LOCAL_ONLY";
    private boolean connected = true;
    private boolean failed;
    private int consumed, sourceCursor, warnings, disconnects, recoveryCount, duplicates, pendingAtClose;
    private long lastSequence, lastQuoteSequence;
    private Instant lastQuoteSource, lastQualifiedSource;
    private MarketQuote latestQuote;
    private Bar activeOpening;
    private Instant lastBarTimestamp;

    public PaperRuntime(Path directory, ValidatedDataset reference, RunConfig config,
            Strategy strategy, LocalDate sessionDate, MarketDataProvider provider) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.reference = Objects.requireNonNull(reference); this.config = Objects.requireNonNull(config);
        this.sessionDate = Objects.requireNonNull(sessionDate);
        this.source = List.copyOf(provider.events());
        this.interval = IntervalSupport.duration(config.interval());
        this.quality = new QuoteQualityPolicy(config.executionModel() == ExecutionModel.BAR_NEXT_OPEN
                ? interval : config.quoteMaximumAge(), config.quoteMaximumFutureSkew());
        var window = reference.sessionCalendar().get(sessionDate);
        if (window == null || !window.open()) throw new IllegalArgumentException("PREOPEN_SESSION_CLOSED_OR_UNKNOWN");
        sessionOpen = config.timestampRole() == TimestampRole.BAR_START ? window.firstBarTimestamp().orElseThrow()
                : window.firstBarTimestamp().orElseThrow().minus(interval);
        sessionClose = config.timestampRole() == TimestampRole.BAR_START ? window.lastBarTimestamp().orElseThrow().plus(interval)
                : window.lastBarTimestamp().orElseThrow();
        preopen(strategy);
        core = new BacktestEngine().paperSession(reference, config, strategy);
        String inputs = PaperManifest.inputs(reference, config, strategy, RunIdentity.contentId(reference, config, strategy),
                sessionDate.toString(), codeFingerprint(), source);
        identity = PaperSessionStore.hash("PAPER_RUNTIME_V2\n" + inputs);
        store = new PaperSessionStore(this.directory, identity, inputs, report());
        try {
            var records = store.restored();
            for (int i = 0; i < records.size(); i += 2) {
                var input = records.get(i); var output = records.get(i + 1);
                PaperEvent event = sourceCursor < source.size() ? source.get(sourceCursor) : null;
                boolean operator = false;
                if (event == null || !Arrays.equals(input.payload(), event.toString().getBytes(StandardCharsets.UTF_8))) {
                    event = stopEvent(); operator = true;
                }
                if (!input.subscriptionId().equals("INPUT")
                        || !Arrays.equals(input.payload(), event.toString().getBytes(StandardCharsets.UTF_8)))
                    throw new IOException("Persisted paper input differs from frozen source at " + consumed);
                requireEnvelope(input, event, "INPUT");
                process(event); consumed++; if (!operator) sourceCursor++;
                requireEnvelope(output, event, "LEDGER");
                if (!Arrays.equals(output.payload(), report().getBytes(StandardCharsets.UTF_8)))
                    throw new IOException("Paper decision/ledger replay mismatch at input " + consumed);
            }
            store.requireReport(report());
        } catch (IOException | RuntimeException error) { store.close(); throw error; }
    }

    private void preopen(Strategy strategy) {
        if (!strategy.supportsDurableCheckpoints()) throw new IllegalArgumentException("PREOPEN_STRATEGY_NOT_REPLAYABLE");
        if (source.isEmpty()) throw new IllegalArgumentException("PREOPEN_CLOCK_NO_LOCAL_EVENTS");
        // Authorized replay data are already admitted by ValidatedDataset.load/PermissionAdmission.
        // Forward acquisition is never admitted. Synthetic fixtures remain UNVERIFIED plumbing.
        if (!config.syntheticFixture() && config.permissionInputs().isEmpty())
            throw new IllegalArgumentException("PREOPEN_DATA_PERMISSION_MISSING");
        if (!reference.configSha256().equals(config.configSha256())) throw new IllegalArgumentException("PREOPEN_CONFIG_MISMATCH");
        if (reference.bars().isEmpty() || config.maxPositionLots() < 1 || config.maxNotionalInr().signum() <= 0
                || config.maxDailyLossInr().signum() <= 0 || config.maxMarginInr().signum() <= 0)
            throw new IllegalArgumentException("PREOPEN_REFERENCE_OR_RISK_LIMITS_INVALID");
        if (config.crossSessionPositionPolicy() == CrossSessionPositionPolicy.AUTO_FLATTEN_SESSION_END
                || config.allowNextSessionOrders() || reference.expirySettlement().isPresent()
                || config.optionStressRiskLimits().isPresent())
            throw new IllegalArgumentException("PREOPEN_UNSUPPORTED_PAPER_POLICY: no automatic settlement/flattening, cross-session orders or option stress inputs");
        var b = reference.bars().getFirst();
        reference.requireCompleteSimulationCoverage();
        if (b.contractKind() != com.mscient.intrapaper.reference.ContractKind.FUTURE
                || (!b.underlying().equals("NIFTY") && !(config.syntheticFixture() && b.underlying().equals("SYNTHETIC"))))
            throw new IllegalArgumentException("PREOPEN_NIFTY_FUTURES_ONLY: options remain a later stage");
        if (reference.bars().stream().anyMatch(bar -> !bar.sessionDate().equals(sessionDate)))
            throw new IllegalArgumentException("PREOPEN_ONE_SESSION_REQUIRED");
        reference.instrumentMaster().requireContract(b.exchange(), b.segment(), b.sourceToken(), sessionDate);
        if (sessionDate.isAfter(b.expiry())) throw new IllegalArgumentException("PREOPEN_CONTRACT_EXPIRED");
    }

    public synchronized boolean step() throws IOException {
        if (failed) throw new IOException("Paper persistence/core failed; verified restart required");
        if (state == State.STOPPED || state == State.HALTED) return false;
        if (Files.exists(directory.resolve("STOP"))) { requestStop(); return false; }
        if (sourceCursor == source.size()) return false;
        PaperEvent event = source.get(sourceCursor);
        try {
            store.input(event);
            process(event); consumed++; sourceCursor++;
            store.commit(event, report(), consumed);
            return true;
        } catch (IOException | RuntimeException error) {
            failed = true; state = State.HALTED; reason = "PERSISTENCE_OR_CORE_FAILURE";
            throw error;
        }
    }

    /** Durable local operator stop, serialized between event transactions. */
    public synchronized void requestStop() throws IOException {
        if (failed) throw new IOException("Paper session failed; recovery required");
        if (state == State.STOPPED || state == State.HALTED) return;
        var event = stopEvent();
        try {
            store.input(event); process(event); consumed++;
            store.commit(event, report(), consumed);
            store.writeReport(report(), complete());
        } catch (IOException | RuntimeException error) { failed = true; state = State.HALTED; throw error; }
    }
    private PaperEvent stopEvent() {
        return PaperEvent.control(lastSequence + 1, PaperEvent.Kind.OPERATOR_STOP,
                clock.now() == null ? sessionOpen : clock.now(), clock.nanos());
    }

    public void run(int maximumEvents) throws IOException {
        if (maximumEvents < 0) throw new IllegalArgumentException("maximumEvents must be nonnegative");
        for (int i = 0; i < maximumEvents && step(); i++) { }
        synchronized (this) {
            if (failed) throw new IOException("Paper session failed; verified restart required");
            try { store.writeReport(report(), complete()); }
            catch (IOException | RuntimeException error) {
                failed = true; state = State.HALTED; reason = "ARTIFACT_STORAGE_FAILURE"; throw error;
            }
        }
    }
    public synchronized void verifyReport() throws IOException { store.verifyPublishedReport(report(), complete()); }
    public synchronized State state() { return state; }
    public synchronized String reason() { return reason; }
    public synchronized int consumedEvents() { return consumed; }
    public synchronized BacktestResult result() { return core.snapshot(); }

    private void process(PaperEvent event) {
        if (state == State.STOPPED || state == State.HALTED) { observe(event, "TERMINAL_STATE_IGNORED"); return; }
        String fingerprint = PaperSessionStore.hash(event.toString());
        String previous = seen.get(event.sequence());
        if (previous != null) {
            if (previous.equals(fingerprint)) { duplicates++; observe(event, "IDENTICAL_DUPLICATE_IGNORED"); }
            else halt(event, "CONFLICTING_EVENT_DUPLICATE");
            return;
        }
        if (event.sequence() != lastSequence + 1) { halt(event, "EVENT_SEQUENCE_GAP_OR_REORDER"); return; }
        String clockFailure = clock.advance(event);
        if (clockFailure != null) { halt(event, clockFailure); return; }
        seen.put(event.sequence(), fingerprint); lastSequence = event.sequence();
        switch (event.kind()) {
            case OPERATOR_STOP -> { pendingAtClose = core.pendingOrders(); stop(event, "OPERATOR_STOP"); }
            case SESSION_CLOSE -> { pendingAtClose = core.pendingOrders(); stop(event,
                    clock.now().isBefore(sessionClose) ? "EARLY_SESSION_CLOSE" : "SESSION_CLOSED"); }
            case DISCONNECT -> { connected = false; disconnects++; degrade(event, "FEED_DISCONNECTED"); }
            case RECONNECT -> { connected = true; degrade(event, "RECONNECTED_EXPLICIT_RECOVERY_REQUIRED"); }
            case RECOVER -> {
                if (!connected) { degrade(event, "RECOVERY_REQUIRES_CONNECTION"); break; }
                recoveryCount++; state = State.READY; reason = "RECOVERY_WAITING_FOR_FRESH_DATA"; latestQuote = null;
                observe(event, reason);
            }
            case MALFORMED -> degrade(event, "MALFORMED_DATA");
            case HEARTBEAT -> {
                if (!clock.now().isBefore(sessionClose)) { pendingAtClose = core.pendingOrders(); stop(event, "SESSION_CLOSED"); }
                else if (lastQualifiedSource == null || Duration.between(lastQualifiedSource, clock.now()).compareTo(quality.maximumAge()) > 0)
                    degrade(event, "STALE_FEED_HEARTBEAT");
                else observe(event, "HEARTBEAT_OK");
            }
            case QUOTE -> quote(event);
            case OPEN, BAR -> bar(event);
        }
    }

    private boolean eligibleWindow(PaperEvent event) {
        boolean finalBar = event.kind() == PaperEvent.Kind.BAR && clock.now().equals(sessionClose);
        if (clock.now().isBefore(sessionOpen) || (clock.now().compareTo(sessionClose) >= 0 && !finalBar)) {
            halt(event, "SESSION_CLOSED_EVENT"); return false;
        }
        if (!connected) { degrade(event, "FEED_DISCONNECTED"); return false; }
        if (state == State.DEGRADED) { observe(event, "EXPLICIT_RECOVERY_REQUIRED"); return false; }
        return true;
    }

    private void quote(PaperEvent event) {
        var q = event.quote().orElseThrow();
        var expected = reference.bars().getFirst();
        if (!q.instrumentId().equals(expected.instrumentId())
                || !q.source().equals(config.quoteSourceName().orElse(config.datasetSourceName()))) {
            halt(event, "UNKNOWN_QUOTE_CONTRACT_OR_SOURCE"); return;
        }
        String fingerprint = q.toString();
        String prior = seenQuotes.putIfAbsent(q.sequenceNumber(), fingerprint);
        if (prior != null) {
            if (prior.equals(fingerprint)) { duplicates++; observe(event, "IDENTICAL_QUOTE_DUPLICATE_IGNORED"); }
            else halt(event, "CONFLICTING_QUOTE_DUPLICATE");
            return;
        }
        if (q.sequenceNumber() != lastQuoteSequence + 1) { halt(event, "QUOTE_SEQUENCE_GAP_OR_REORDER"); return; }
        lastQuoteSequence = q.sequenceNumber();
        if (lastQuoteSource != null && event.sourceTime().isBefore(lastQuoteSource)) { halt(event, "QUOTE_SOURCE_TIME_REVERSED"); return; }
        lastQuoteSource = event.sourceTime();
        if (!eligibleWindow(event)) return;
        var flags = quality.assess(q, clock.now());
        if (!flags.isEmpty() || event.sourceTime().isAfter(clock.now())) {
            degrade(event, "QUOTE_QUALITY:" + new TreeSet<>(flags) + (event.sourceTime().isAfter(clock.now()) ? ":FUTURE_NOT_OBSERVABLE" : "")); return;
        }
        latestQuote = q; lastQualifiedSource = event.sourceTime(); core.quote(q);
        if (core.snapshot().riskHalts() > 0) { halt(event, "DAILY_LOSS_LIMIT"); return; }
        state = State.RUNNING; reason = "QUALIFIED_QUOTE";
        // Pending L1 latency can mature on a quote after an observed open, never at a future timestamp.
        if (activeOpening != null && config.executionModel() != ExecutionModel.BAR_NEXT_OPEN)
            core.executeAtQuote(activeOpening, clock.now());
        observe(event, reason);
    }

    private void bar(PaperEvent event) {
        Bar b = event.bar().orElseThrow();
        Bar expected = reference.bars().getFirst();
        if (!b.sessionDate().equals(sessionDate) || !b.instrumentId().equals(expected.instrumentId())
                || !b.exchange().equals(expected.exchange()) || !b.segment().equals(expected.segment())
                || !b.sourceToken().equals(expected.sourceToken()) || !b.tradingSymbol().equals(expected.tradingSymbol())
                || b.contractKind() != expected.contractKind() || !b.underlying().equals(expected.underlying())
                || !b.expiry().equals(expected.expiry()) || !b.strike().equals(expected.strike())
                || !b.optionRight().equals(expected.optionRight()) || b.lotSize() != expected.lotSize()
                || b.tickSize().compareTo(expected.tickSize()) != 0 || !b.source().equals(config.datasetSourceName())
                || !b.interval().equals(config.interval()) || b.timestampRole() != config.timestampRole()) {
            halt(event, "UNKNOWN_OR_MISMATCHED_BAR_CONTRACT"); return;
        }
        if (!eligibleWindow(event)) return;
        Instant start = b.timestampRole() == TimestampRole.BAR_START ? b.timestamp() : b.timestamp().minus(interval);
        Instant sourceTime = event.kind() == PaperEvent.Kind.OPEN ? start : start.plus(interval);
        if (!sourceTime.equals(event.sourceTime())) { halt(event, "BAR_SOURCE_TIMESTAMP_MISMATCH"); return; }
        if (sourceTime.isAfter(clock.now())) { degrade(event, "FUTURE_BAR_NOT_OBSERVABLE"); return; }
        if (Duration.between(sourceTime, clock.now()).compareTo(quality.maximumAge()) > 0) { degrade(event, "STALE_BAR"); return; }
        if (event.kind() == PaperEvent.Kind.OPEN) {
            if (b.open().compareTo(b.high()) != 0 || b.open().compareTo(b.low()) != 0 || b.open().compareTo(b.close()) != 0
                    || b.volume() != null || b.openInterest() != null) { halt(event, "OPEN_CONTAINS_UNAVAILABLE_BAR_DATA"); return; }
            if (activeOpening != null || (lastBarTimestamp == null ? !start.equals(sessionOpen)
                    : !b.timestamp().equals(lastBarTimestamp.plus(interval)))) { halt(event, "BAR_SEQUENCE_GAP_OR_REORDER"); return; }
            activeOpening = b;
            if (quoteReady(event)) core.opening(b, clock.now());
        } else {
            if (activeOpening == null || !activeOpening.timestamp().equals(b.timestamp())
                    || activeOpening.open().compareTo(b.open()) != 0) { halt(event, "BAR_WITHOUT_MATCHING_OPEN"); return; }
            if (!quoteReady(event)) return;
            core.advance(b, clock.now(), state != State.DEGRADED);
            lastBarTimestamp = b.timestamp(); activeOpening = null;
            if (core.snapshot().riskHalts() > 0) { halt(event, "DAILY_LOSS_LIMIT"); return; }
        }
        if (state != State.DEGRADED) { state = State.RUNNING; reason = "QUALIFIED_" + event.kind(); lastQualifiedSource = sourceTime; }
        observe(event, reason);
    }

    private boolean quoteReady(PaperEvent event) {
        if (config.executionModel() == ExecutionModel.BAR_NEXT_OPEN) return true;
        if (latestQuote == null || !quality.assess(latestQuote, clock.now()).isEmpty()) {
            degrade(event, "NO_FRESH_QUOTE_FOR_EXECUTION_OR_DECISION"); return false;
        }
        return true;
    }
    private void degrade(PaperEvent e, String why) { warnings++; state = State.DEGRADED; reason = why; core.suspend(clock.now(), why); observe(e, why); }
    private void halt(PaperEvent e, String why) { warnings++; state = State.HALTED; reason = why; core.suspend(clock.now(), why); observe(e, why); }
    private void stop(PaperEvent e, String why) { state = State.STOPPED; reason = why; core.suspend(clock.now(), why); observe(e, why); }
    private void observe(PaperEvent e, String disposition) {
        observations.add("sequence=" + e.sequence() + "; source=" + e.sourceTime() + "; receive=" + e.receivedAt()
                + "; decision_clock=" + clock.now() + "; kind=" + e.kind() + "; state=" + state + "; reason=" + disposition);
    }

    public synchronized String report() {
        BacktestResult r = core.snapshot();
        StringBuilder text = new StringBuilder("PAPER_SESSION_REPORT_V1\nclassification=PAPER_PLUMBING_ONLY\nperformance_claim_eligible=false\n");
        text.append("external_connection=false\ng0=OPEN\nprovider=FROZEN_LOCAL\nordering=RECEIVE_THEN_INPUT_ORDER_FAIL_CLOSED_ON_REVERSAL\n");
        text.append("run_id=").append(identity).append("\nsession_complete=").append(complete())
                .append("\noperating_evidence_eligible=false\n");
        text.append("session_date=").append(sessionDate).append("\nstate=").append(state).append("\nreason=").append(reason)
                .append("\nconsumed_events=").append(consumed).append("\nsource_events=").append(sourceCursor).append("\nclock=").append(clock.now())
                .append("\ndata_quality_warnings=").append(warnings).append("\ndisconnects=").append(disconnects)
                .append("\nrecoveries=").append(recoveryCount).append("\nduplicates_ignored=").append(duplicates)
                .append("\nrisk_halts=").append(r.riskHalts()).append("\npending_at_close=").append(pendingAtClose)
                .append("\npending_orders=").append(core.pendingOrders()).append("\nopen_position_units=").append(r.finalPositionUnits())
                .append("\norders=").append(r.orders().size()).append("\nfills=").append(r.fills().size())
                .append("\nfees_inr=").append(r.feesPaidInr()).append("\nrealized_pnl_inr=").append(r.realizedPnlInr())
                .append("\nunrealized_pnl_at_last_observed_mark_inr=").append(r.unrealizedPnlInr())
                .append("\nnet_pnl_at_last_observed_mark_inr=").append(r.netPnlInr())
                .append("\nsettlement=NOT_PERFORMED\nreconciliation=").append(r.equityCurve().isEmpty() ? "EMPTY_NO_ACTIVITY" : "PASS")
                .append("\nposition_disposition=").append(r.finalPositionUnits() == 0 ? "FLAT" : "OPEN_REQUIRES_OPERATOR_REVIEW")
                .append("\npreopen=CONFIG,LOCAL_DATA_PERMISSION,INSTRUMENT,CALENDAR,RISK,REPLAYABLE_STRATEGY,JOURNAL,CLOCK\n");
        section(text, "runtime-events", observations); section(text, "strategy-decisions", r.signals());
        section(text, "orders", r.orders()); section(text, "order-risk-events", r.orderEvents());
        section(text, "fills", r.fills()); section(text, "fees", r.feeBreakdowns()); section(text, "trades", r.trades());
        section(text, "positions-equity", r.equityCurve()); section(text, "sessions", r.sessionAudits());
        return text.toString();
    }
    private boolean complete() {
        var r = core.snapshot();
        return state == State.STOPPED && reason.equals("SESSION_CLOSED") && sourceCursor == source.size()
                && warnings == 0 && r.finalPositionUnits() == 0 && !r.sessionAudits().isEmpty()
                && r.sessionAudits().stream().allMatch(SessionAudit::complete);
    }

    /** Regenerate the committed prefix, including an out-of-band operator stop, in an owned scratch directory. */
    public synchronized int verifyReplay(Strategy replayStrategy) throws IOException {
        verifyReport();
        Path scratch = Files.createTempDirectory("intra-paper-replay-");
        try {
            try (var replay = new PaperRuntime(scratch, reference, config, replayStrategy, sessionDate,
                    new LocalMarketDataProvider(source))) {
                replay.run(sourceCursor);
                if (consumed > sourceCursor) replay.requestStop();
                replay.run(0);
                if (!identity.equals(replay.identity)) throw new IOException("Replay strategy/input identity mismatch");
            }
            var files = List.of("input-manifest.json", "checkpoint.txt", "session-report.txt", "session-manifest.json");
            for (String file : files) if (Files.mismatch(directory.resolve(file), scratch.resolve(file)) != -1)
                throw new IOException("Paper replay bytes differ: " + file);
            store.requireReplayJournal(scratch.resolve("paper.journal"));
            return files.size() + 1;
        } finally {
            try (var paths = Files.walk(scratch)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static void section(StringBuilder text, String title, List<?> rows) {
        text.append('[').append(title).append("]\n"); rows.forEach(row -> text.append(row).append('\n'));
    }
    private static void requireEnvelope(RawMarketMessage message, PaperEvent e, String kind) throws IOException {
        if (!message.source().equals("PAPER_LOCAL") || !message.connectionId().equals("SESSION")
                || !message.subscriptionId().equals(kind) || message.sourceSequence().orElse(-1) != e.sequence()
                || !message.receivedAtUtc().equals(e.receivedAt()) || message.receivedAtMonotonicNanos() != e.receiveNanos())
            throw new IOException("Paper journal envelope mismatch");
    }
    private static String codeFingerprint() throws IOException {
        try {
            Path location = Path.of(PaperRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(location)) return RunConfig.sha256(Files.readAllBytes(location));
            StringBuilder hashes = new StringBuilder();
            try (var files = Files.walk(location.resolve("com/mscient/intrapaper"))) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList())
                    hashes.append(location.relativize(file).toString().replace('\\', '/')).append(':')
                            .append(RunConfig.sha256(Files.readAllBytes(file))).append('\n');
            }
            return PaperSessionStore.hash(hashes.toString());
        } catch (java.net.URISyntaxException error) { throw new IOException("Cannot fingerprint running code", error); }
    }
    @Override public synchronized void close() throws IOException { store.close(); }
}
