package com.mscient.intrapaper.simulation.portfolio;

import com.mscient.intrapaper.data.Bar;
import com.mscient.intrapaper.data.SynchronizedBarFrameBuilder;
import com.mscient.intrapaper.reference.ContractKind;
import com.mscient.intrapaper.reference.OptionRight;
import com.mscient.intrapaper.reference.TimestampRole;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Focused accounting checks for multi-instrument positions and contemporaneous marks. */
public final class PortfolioAccountingLedgerTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);
    private static final String HASH = "b".repeat(64);

    private PortfolioAccountingLedgerTest() { }

    public static void main(String[] args) {
        accountsLongAndShortPositionsAcrossInstruments();
        usesNoFutureBarCloseForBarStartFrames();
        usesCloseForBarEndFrames();
        duplicateExecutionIsIdempotent();
        accountsThroughFlatAndReversal();
        rejectedFrameDoesNotPartiallyMutateLedger();
        rejectsInvalidTimeAndLotSize();
        System.out.println("PortfolioAccountingLedger checks passed");
    }

    private static void accountsLongAndShortPositionsAcrossInstruments() {
        SynchronizedBarFrameBuilder.Frame first = frame("2026-11-03T03:45:00Z",
                future("2026-11-03T03:45:00Z", "100.0", "110.0", "105.0"),
                option("2026-11-03T03:45:00Z", "10.0", "12.0", "12.0"));
        SynchronizedBarFrameBuilder.Frame second = frame("2026-11-03T03:46:00Z",
                future("2026-11-03T03:46:00Z", "102.0", "112.0", "110.0"),
                option("2026-11-03T03:46:00Z", "9.0", "11.0", "10.0"));
        PortfolioAccountingLedger ledger = new PortfolioAccountingLedger();

        PortfolioExecution longFuture = execution("F-1", 1, "NIFTY-FUT", first.timestamp(),
                PortfolioExecution.Side.BUY, 50, "100.0", "1.0");
        PortfolioAccountingLedger.Snapshot snapshot1 = ledger.mark(first, List.of(longFuture, longFuture));
        require(snapshot1.positions().get("NIFTY-FUT").signedUnits() == 50,
                "identical duplicate execution must be applied once");
        require(snapshot1.feesInr().compareTo(new BigDecimal("1.0")) == 0,
                "duplicate execution fee must be applied once");

        PortfolioExecution reduceFuture = execution("F-2", 2, "NIFTY-FUT", second.timestamp(),
                PortfolioExecution.Side.SELL, 25, "110.0", "2.0");
        PortfolioExecution shortOption = execution("O-1", 3, "NIFTY-CE", second.timestamp(),
                PortfolioExecution.Side.SELL, 25, "11.0", "1.0");
        PortfolioAccountingLedger.Snapshot snapshot2 = ledger.mark(second, List.of(reduceFuture, shortOption));
        require(snapshot2.positions().get("NIFTY-FUT").signedUnits() == 25,
                "partial reduction must leave the residual long position");
        require(snapshot2.positions().get("NIFTY-FUT").realizedGrossPnlInr()
                .compareTo(new BigDecimal("250.0")) == 0, "realized futures P&L must use closed units");
        require(snapshot2.positions().get("NIFTY-FUT").unrealizedPnlInr()
                .compareTo(new BigDecimal("50.0")) == 0, "residual future must be marked at the bar open");
        require(snapshot2.positions().get("NIFTY-CE").signedUnits() == -25,
                "sell execution must create a signed short position");
        require(snapshot2.positions().get("NIFTY-CE").unrealizedPnlInr()
                .compareTo(new BigDecimal("50.0")) == 0, "short option mark-to-market sign must be correct");
        require(snapshot2.netPnlInr().compareTo(new BigDecimal("346.0")) == 0,
                "portfolio net P&L must aggregate realized, unrealized, and fees across instruments");
    }

    private static void usesNoFutureBarCloseForBarStartFrames() {
        SynchronizedBarFrameBuilder.Frame frame = frame("2026-11-03T03:45:00Z",
                future("2026-11-03T03:45:00Z", "100.0", "130.0", "125.0"),
                option("2026-11-03T03:45:00Z", "10.0", "20.0", "19.0"));
        PortfolioAccountingLedger ledger = new PortfolioAccountingLedger();
        var snapshot = ledger.mark(frame, List.of(execution("F-1", 1, "NIFTY-FUT", frame.timestamp(),
                PortfolioExecution.Side.BUY, 25, "100.0", "0")));
        require(snapshot.positions().get("NIFTY-FUT").unrealizedPnlInr().signum() == 0,
                "BAR_START frame must use the open and not the future intrabar close");
    }

    private static void usesCloseForBarEndFrames() {
        Instant timestamp = Instant.parse("2026-11-03T03:46:00Z");
        SynchronizedBarFrameBuilder.Frame frame = SynchronizedBarFrameBuilder.build(Map.of(
                "NIFTY-FUT", List.of(future(timestamp.toString(), "100.0", "106.0", "105.0", TimestampRole.BAR_END)),
                "NIFTY-CE", List.of(option(timestamp.toString(), "10.0", "12.0", "11.0", TimestampRole.BAR_END))))
                .getFirst();
        PortfolioAccountingLedger ledger = new PortfolioAccountingLedger();
        var snapshot = ledger.mark(frame, List.of(execution("F-1", 1, "NIFTY-FUT", timestamp,
                PortfolioExecution.Side.BUY, 25, "100.0", "0")));
        require(snapshot.positions().get("NIFTY-FUT").unrealizedPnlInr()
                .compareTo(new BigDecimal("125.0")) == 0,
                "BAR_END frame must use the close available at its timestamp");
    }

    private static void duplicateExecutionIsIdempotent() {
        SynchronizedBarFrameBuilder.Frame frame = frame("2026-11-03T03:45:00Z",
                future("2026-11-03T03:45:00Z", "100.0", "101.0", "100.5"),
                option("2026-11-03T03:45:00Z", "10.0", "11.0", "10.5"));
        PortfolioAccountingLedger ledger = new PortfolioAccountingLedger();
        PortfolioExecution event = execution("F-1", 1, "NIFTY-FUT", frame.timestamp(),
                PortfolioExecution.Side.BUY, 25, "100.0", "0");
        ledger.mark(frame, List.of(event));
        expectFailure(() -> ledger.mark(nextFrame(frame.timestamp()), List.of(
                execution("F-1", 2, "NIFTY-FUT", nextFrame(frame.timestamp()).timestamp(),
                        PortfolioExecution.Side.BUY, 25, "101.0", "0"))),
                "conflicting reuse of an execution ID must fail");
    }

    private static void accountsThroughFlatAndReversal() {
        SynchronizedBarFrameBuilder.Frame first = frame("2026-11-03T03:45:00Z",
                future("2026-11-03T03:45:00Z", "100.0", "101.0", "100.5"),
                option("2026-11-03T03:45:00Z", "10.0", "11.0", "10.5"));
        SynchronizedBarFrameBuilder.Frame second = frame("2026-11-03T03:46:00Z",
                future("2026-11-03T03:46:00Z", "90.0", "100.0", "95.0"),
                option("2026-11-03T03:46:00Z", "10.0", "11.0", "10.5"));
        PortfolioAccountingLedger ledger = new PortfolioAccountingLedger();
        ledger.mark(first, List.of(execution("F-1", 1, "NIFTY-FUT", first.timestamp(),
                PortfolioExecution.Side.SELL, 25, "100.0", "0")));
        var reversal = ledger.mark(second, List.of(execution("F-2", 2, "NIFTY-FUT", second.timestamp(),
                PortfolioExecution.Side.BUY, 50, "90.0", "0")));
        var future = reversal.positions().get("NIFTY-FUT");
        require(future.signedUnits() == 25, "buying beyond a short must leave the residual long position");
        require(future.realizedGrossPnlInr().compareTo(new BigDecimal("250.0")) == 0,
                "short cover must realize inverse-side P&L");
        require(future.averageOpenPrice().compareTo(new BigDecimal("90.0")) == 0,
                "reversed residual must use its own opening fill basis");
        require(future.unrealizedPnlInr().signum() == 0, "reversed residual must mark from its new basis");
    }

    private static void rejectedFrameDoesNotPartiallyMutateLedger() {
        SynchronizedBarFrameBuilder.Frame frame = frame("2026-11-03T03:45:00Z",
                future("2026-11-03T03:45:00Z", "100.0", "101.0", "100.5"),
                option("2026-11-03T03:45:00Z", "10.0", "11.0", "10.5"));
        PortfolioAccountingLedger ledger = new PortfolioAccountingLedger();
        PortfolioExecution valid = execution("F-1", 1, "NIFTY-FUT", frame.timestamp(),
                PortfolioExecution.Side.BUY, 25, "100.0", "1.0");
        PortfolioExecution conflicting = execution("F-1", 2, "NIFTY-FUT", frame.timestamp(),
                PortfolioExecution.Side.BUY, 25, "101.0", "1.0");
        expectFailure(() -> ledger.mark(frame, List.of(valid, conflicting)),
                "conflicting IDs in one frame must fail");
        var accepted = ledger.mark(frame, List.of(valid));
        require(accepted.positions().get("NIFTY-FUT").signedUnits() == 25,
                "failed frame must not leave the first execution applied");
        require(accepted.feesInr().compareTo(new BigDecimal("1.0")) == 0,
                "failed frame must not retain any partially applied fee");
    }

    private static void rejectsInvalidTimeAndLotSize() {
        SynchronizedBarFrameBuilder.Frame frame = frame("2026-11-03T03:45:00Z",
                future("2026-11-03T03:45:00Z", "100.0", "101.0", "100.5"),
                option("2026-11-03T03:45:00Z", "10.0", "11.0", "10.5"));
        PortfolioAccountingLedger ledger = new PortfolioAccountingLedger();
        expectFailure(() -> ledger.mark(frame, List.of(execution("F-1", 1, "NIFTY-FUT",
                Instant.parse("2026-11-03T03:45:01Z"), PortfolioExecution.Side.BUY, 25, "100.0", "0"))),
                "execution timestamp must match the frame exactly");
        expectFailure(() -> ledger.mark(frame, List.of(execution("F-1", 1, "NIFTY-FUT",
                frame.timestamp(), PortfolioExecution.Side.BUY, 24, "100.0", "0"))),
                "partial non-lot quantities must fail");
        expectFailure(() -> ledger.mark(frame, List.of(execution("F-1", 1, "NIFTY-FUT",
                frame.timestamp(), PortfolioExecution.Side.BUY, 25, "100.01", "0"))),
                "non-tick execution prices must fail");
        var cleanRetry = ledger.mark(frame, List.of());
        require(cleanRetry.positions().get("NIFTY-FUT").signedUnits() == 0,
                "rejected frame must remain retryable without partial state");
    }

    private static SynchronizedBarFrameBuilder.Frame nextFrame(Instant previous) {
        Instant timestamp = previous.plusSeconds(60);
        return frame(timestamp.toString(), future(timestamp.toString(), "100.0", "101.0", "100.5"),
                option(timestamp.toString(), "10.0", "11.0", "10.5"));
    }

    private static SynchronizedBarFrameBuilder.Frame frame(String timestamp,
            Bar future, Bar option) {
        return SynchronizedBarFrameBuilder.build(Map.of("NIFTY-FUT", List.of(future),
                "NIFTY-CE", List.of(option))).getFirst();
    }

    private static PortfolioExecution execution(String id, long sequence, String instrumentId,
            Instant timestamp, PortfolioExecution.Side side, long quantity, String price, String fee) {
        return new PortfolioExecution(id, sequence, instrumentId, timestamp, side, quantity,
                new BigDecimal(price), new BigDecimal(fee));
    }

    private static Bar future(String timestamp, String open, String high, String close) {
        return bar(timestamp, "NIFTY-FUT", "FUT", ContractKind.FUTURE, Optional.empty(), Optional.empty(),
                open, high, close, TimestampRole.BAR_START);
    }

    private static Bar future(String timestamp, String open, String high, String close, TimestampRole role) {
        return bar(timestamp, "NIFTY-FUT", "FUT", ContractKind.FUTURE, Optional.empty(), Optional.empty(),
                open, high, close, role);
    }

    private static Bar option(String timestamp, String open, String high, String close) {
        return bar(timestamp, "NIFTY-CE", "CE", ContractKind.OPTION, Optional.of(new BigDecimal("24000")),
                Optional.of(OptionRight.CALL), open, high, close, TimestampRole.BAR_START);
    }

    private static Bar option(String timestamp, String open, String high, String close, TimestampRole role) {
        return bar(timestamp, "NIFTY-CE", "CE", ContractKind.OPTION, Optional.of(new BigDecimal("24000")),
                Optional.of(OptionRight.CALL), open, high, close, role);
    }

    private static Bar bar(String timestamp, String instrumentId, String token, ContractKind kind,
            Optional<BigDecimal> strike, Optional<OptionRight> right,
            String open, String high, String close, TimestampRole role) {
        BigDecimal openValue = new BigDecimal(open);
        BigDecimal highValue = new BigDecimal(high);
        BigDecimal closeValue = new BigDecimal(close);
        BigDecimal lowValue = openValue.min(closeValue);
        return new Bar(Instant.parse(timestamp), DAY, instrumentId, "NSE", "NFO", token, instrumentId, kind,
                "NIFTY", LocalDate.of(2026, 11, 26), strike, right, 25, new BigDecimal("0.05"),
                "ONE_MINUTE", role, openValue, highValue, lowValue, closeValue,
                10L, 20L, "fixture", HASH);
    }

    private static void expectFailure(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
