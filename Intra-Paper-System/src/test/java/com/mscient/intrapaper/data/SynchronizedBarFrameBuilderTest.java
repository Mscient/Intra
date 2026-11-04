package com.mscient.intrapaper.data;

import com.mscient.intrapaper.reference.ContractKind;
import com.mscient.intrapaper.reference.TimestampRole;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Focused executable checks for exact-time multi-instrument bar alignment. */
public final class SynchronizedBarFrameBuilderTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 3);
    private static final String HASH = "a".repeat(64);

    private SynchronizedBarFrameBuilderTest() { }

    public static void main(String[] args) {
        alignsExactFramesAndOrdersInstrumentKeys();
        rejectsMissingTimestampWithoutForwardFill();
        rejectsDifferentSessionDatesAtSameInstant();
        rejectsDuplicateOrUnorderedBars();
        rejectsContractIdentityChangeWithinSeries();
        rejectsIncompatibleContractSeries();
        System.out.println("SynchronizedBarFrameBuilder checks passed");
    }

    private static void alignsExactFramesAndOrdersInstrumentKeys() {
        Bar future1 = future("NIFTY-FUT", "FUT", "2026-11-03T03:45:00Z", DAY, "24000.0");
        Bar option1 = option("NIFTY-CE", "CE", "2026-11-03T03:45:00Z", DAY, "100.0");
        Bar future2 = future("NIFTY-FUT", "FUT", "2026-11-03T03:46:00Z", DAY, "24001.0");
        Bar option2 = option("NIFTY-CE", "CE", "2026-11-03T03:46:00Z", DAY, "101.0");

        List<SynchronizedBarFrameBuilder.Frame> frames = SynchronizedBarFrameBuilder.build(
                Map.of("NIFTY-CE", List.of(option1, option2), "NIFTY-FUT", List.of(future1, future2)));
        require(frames.size() == 2, "all exact timestamps must produce frames");
        require(frames.getFirst().barsByInstrument().keySet().stream().toList()
                .equals(List.of("NIFTY-CE", "NIFTY-FUT")), "frame instrument order must be deterministic");
        require(frames.getLast().timestamp().equals(Instant.parse("2026-11-03T03:46:00Z")),
                "frame timestamps must remain chronological");
        require(frames.getLast().barsByInstrument().get("NIFTY-CE").close().compareTo(new BigDecimal("101.0")) == 0,
                "frame must retain original bar values");
    }

    private static void rejectsMissingTimestampWithoutForwardFill() {
        expectFailure(() -> SynchronizedBarFrameBuilder.build(Map.of(
                "NIFTY-FUT", List.of(future("NIFTY-FUT", "FUT", "2026-11-03T03:45:00Z", DAY, "24000.0"),
                        future("NIFTY-FUT", "FUT", "2026-11-03T03:46:00Z", DAY, "24001.0")),
                "NIFTY-CE", List.of(option("NIFTY-CE", "CE", "2026-11-03T03:45:00Z", DAY, "100.0"),
                        option("NIFTY-CE", "CE", "2026-11-03T03:47:00Z", DAY, "101.0")))),
                "missing observation must fail, never forward-fill");
    }

    private static void rejectsDifferentSessionDatesAtSameInstant() {
        expectFailure(() -> SynchronizedBarFrameBuilder.build(Map.of(
                "NIFTY-FUT", List.of(future("NIFTY-FUT", "FUT", "2026-11-03T03:45:00Z", DAY, "24000.0")),
                "NIFTY-CE", List.of(option("NIFTY-CE", "CE", "2026-11-03T03:45:00Z",
                        LocalDate.of(2026, 11, 4), "100.0")))), "session-date mismatch must fail");
    }

    private static void rejectsDuplicateOrUnorderedBars() {
        Bar first = future("NIFTY-FUT", "FUT", "2026-11-03T03:45:00Z", DAY, "24000.0");
        Bar duplicate = future("NIFTY-FUT", "FUT", "2026-11-03T03:45:00Z", DAY, "24001.0");
        expectFailure(() -> SynchronizedBarFrameBuilder.build(Map.of(
                "NIFTY-FUT", List.of(first, duplicate),
                "NIFTY-CE", List.of(option("NIFTY-CE", "CE", "2026-11-03T03:45:00Z", DAY, "100.0")))),
                "duplicate timestamps must fail");
        expectFailure(() -> SynchronizedBarFrameBuilder.build(Map.of(
                "NIFTY-FUT", List.of(duplicate, first),
                "NIFTY-CE", List.of(option("NIFTY-CE", "CE", "2026-11-03T03:45:00Z", DAY, "100.0")))),
                "out-of-order timestamps must fail");
    }

    private static void rejectsIncompatibleContractSeries() {
        Bar oneMinute = future("NIFTY-FUT", "FUT", "2026-11-03T03:45:00Z", DAY, "24000.0");
        Bar fiveMinute = new Bar(oneMinute.timestamp(), DAY, "NIFTY-CE", "NSE", "NFO", "CE", "NIFTY-CE",
                ContractKind.OPTION, "NIFTY", LocalDate.of(2026, 11, 26), Optional.of(new BigDecimal("24000")),
                Optional.of(com.mscient.intrapaper.reference.OptionRight.CALL), 25, new BigDecimal("0.05"),
                "FIVE_MINUTE", TimestampRole.BAR_START, new BigDecimal("100.0"), new BigDecimal("100.0"),
                new BigDecimal("100.0"), new BigDecimal("100.0"), 10L, 20L, "fixture", HASH);
        expectFailure(() -> SynchronizedBarFrameBuilder.build(Map.of(
                "NIFTY-FUT", List.of(oneMinute), "NIFTY-CE", List.of(fiveMinute))),
                "different bar intervals must fail");
    }

    private static void rejectsContractIdentityChangeWithinSeries() {
        Bar first = option("NIFTY-CE", "CE", "2026-11-03T03:45:00Z", DAY, "100.0");
        Bar changedExpiry = new Bar(Instant.parse("2026-11-03T03:46:00Z"), DAY, first.instrumentId(),
                first.exchange(), first.segment(), first.sourceToken(), first.tradingSymbol(), first.contractKind(),
                first.underlying(), LocalDate.of(2026, 12, 3), first.strike(), first.optionRight(), first.lotSize(),
                first.tickSize(), first.interval(), first.timestampRole(), first.open(), first.high(), first.low(),
                first.close(), first.volume(), first.openInterest(), first.source(), HASH);
        expectFailure(() -> SynchronizedBarFrameBuilder.build(Map.of(
                "NIFTY-FUT", List.of(future("NIFTY-FUT", "FUT", "2026-11-03T03:45:00Z", DAY, "24000.0"),
                        future("NIFTY-FUT", "FUT", "2026-11-03T03:46:00Z", DAY, "24001.0")),
                "NIFTY-CE", List.of(first, changedExpiry))),
                "one instrument ID must not change its contract expiry midstream");
    }

    private static Bar future(String instrumentId, String token, String timestamp, LocalDate day, String price) {
        BigDecimal value = new BigDecimal(price);
        return new Bar(Instant.parse(timestamp), day, instrumentId, "NSE", "NFO", token, instrumentId,
                ContractKind.FUTURE, "NIFTY", LocalDate.of(2026, 11, 26), Optional.empty(), Optional.empty(),
                25, new BigDecimal("0.05"), "ONE_MINUTE", TimestampRole.BAR_START, value, value, value, value,
                10L, 20L, "fixture", HASH);
    }

    private static Bar option(String instrumentId, String token, String timestamp, LocalDate day, String price) {
        BigDecimal value = new BigDecimal(price);
        return new Bar(Instant.parse(timestamp), day, instrumentId, "NSE", "NFO", token, instrumentId,
                ContractKind.OPTION, "NIFTY", LocalDate.of(2026, 11, 26), Optional.of(new BigDecimal("24000")),
                Optional.of(com.mscient.intrapaper.reference.OptionRight.CALL), 25, new BigDecimal("0.05"),
                "ONE_MINUTE", TimestampRole.BAR_START, value, value, value, value, 10L, 20L, "fixture", HASH);
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
