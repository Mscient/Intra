package com.mscient.intrapaper.ops;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/** Focused checks for K0-K3 escalation, latching, dwell, ordering, and human clearance. */
public final class OperationalKillControllerTest {
    private OperationalKillControllerTest() { }

    public static void main(String[] args) {
        var policy = new KillPolicy(Duration.ofSeconds(10), Duration.ofSeconds(6), 2);
        var controller = new OperationalKillController(policy);
        controller.startSession(LocalDate.parse("2026-11-05"));
        Instant start = Instant.parse("2026-11-05T04:00:00Z");
        var k0 = controller.observe(observation("stale", start, seconds(1), KillTrigger.STALE_FEED));
        require(k0.level() == KillLevel.K0_PAUSE && !k0.riskIncreasingActionsAllowed()
                && k0.requiredResponses().contains(KillDecision.Response.PAUSE_NEW_RISK), "K0 pauses new risk");
        requireThrows(IllegalStateException.class, () -> controller.clear(clearance(true)), "K0 is not human-cleared");
        controller.observe(observation("healthy-1", start.plusSeconds(1), seconds(2)));
        controller.observe(observation("healthy-gap", start.plusSeconds(11), seconds(12)));
        controller.observe(observation("healthy-2", start.plusSeconds(16), seconds(17)));
        var autoCleared = controller.observe(observation("healthy-3", start.plusSeconds(21), seconds(22)));
        require(autoCleared.automaticallyCleared() && autoCleared.level() == KillLevel.NONE
                && autoCleared.riskIncreasingActionsAllowed(), "K0 clears after continuous configured healthy dwell");

        var k1 = controller.observe(observation("reject-storm-1", start.plusSeconds(22), seconds(23),
                KillTrigger.REJECT_OR_OPS_STORM));
        require(k1.level() == KillLevel.K1_CANCEL_AND_BLOCK && !k1.riskIncreasingActionsAllowed()
                && k1.requiredResponses().contains(KillDecision.Response.CANCEL_WORKING_ORDERS_AND_BLOCK_NEW_RISK),
                "K1 blocks and recommends cancellation");
        controller.observe(observation("k1-trigger-cleared", start.plusSeconds(23), seconds(24)));
        requireThrows(IllegalArgumentException.class, () -> controller.clear(
                        new HumanKillClearance("operator-1", "review-1", false, true, true, true, true)),
                "incomplete K1 clearance rejected");
        controller.clear(clearance(true));
        var repeated = controller.observe(observation("reject-storm-2", start.plusSeconds(24), seconds(25),
                KillTrigger.REJECT_OR_OPS_STORM));
        require(repeated.level() == KillLevel.K2_FLATTEN_AND_HALT && repeated.newlyEscalated()
                && repeated.activeTriggers().contains(KillTrigger.REPEATED_K1_ESCALATION),
                "repeated K1 escalates to K2");
        controller.observe(observation("k2-trigger-cleared", start.plusSeconds(25), seconds(26)));
        controller.clear(clearance(true));

        var k3Controller = new OperationalKillController(policy);
        k3Controller.startSession(LocalDate.parse("2026-11-05"));
        var k3 = k3Controller.observe(observation("compromise", start, seconds(1), KillTrigger.SUSPECTED_COMPROMISE));
        require(k3.level() == KillLevel.K3_DISCONNECT
                && k3.requiredResponses().contains(KillDecision.Response.DISCONNECT_PROCESS_AND_USE_HUMAN_MANUAL_PATH),
                "K3 recommends disconnect/manual path");
        k3Controller.observe(observation("k3-trigger-cleared", start.plusSeconds(1), seconds(2)));
        requireThrows(IllegalArgumentException.class, () -> k3Controller.clear(clearance(false)),
                "K3 requires restart verification");
        require(k3Controller.clear(clearance(true)).level() == KillLevel.NONE, "fully attested K3 clear");

        var orderController = new OperationalKillController(policy);
        orderController.startSession(LocalDate.parse("2026-11-05"));
        orderController.observe(observation("first", start, seconds(1)));
        requireThrows(IllegalArgumentException.class,
                () -> orderController.observe(observation("second", start.plusSeconds(1), seconds(1))),
                "non-increasing monotonic observations rejected");
        var recoveredOrdering = orderController.observe(observation("second", start.plusSeconds(2), seconds(2)));
        require(recoveredOrdering.level() == KillLevel.NONE, "rejected ordering input does not consume its ID");
        requireThrows(IllegalArgumentException.class,
                () -> orderController.observe(observation("first", start.plusSeconds(3), seconds(3))),
                "duplicate observation ID rejected");
        requireThrows(IllegalArgumentException.class,
                () -> orderController.startSession(LocalDate.parse("2026-11-05")),
                "session dates must strictly increase");
        System.out.println("OperationalKillController focused checks passed.");
    }

    private static SafetyObservation observation(String id, Instant at, long monotonic, KillTrigger... triggers) {
        return new SafetyObservation(id, at, monotonic, Set.of(triggers));
    }

    private static long seconds(long value) { return Duration.ofSeconds(value).toNanos(); }

    private static HumanKillClearance clearance(boolean restartVerified) {
        return new HumanKillClearance("operator-1", "review-1", true, true, true, true, restartVerified);
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static void requireThrows(Class<? extends Throwable> type, Runnable action, String description) {
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError(description + ": unexpected exception " + failure, failure);
        }
        throw new AssertionError(description + ": expected " + type.getSimpleName());
    }
}
