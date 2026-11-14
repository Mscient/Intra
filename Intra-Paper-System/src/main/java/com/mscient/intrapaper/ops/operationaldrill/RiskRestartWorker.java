package com.mscient.intrapaper.ops.operationaldrill;

import com.mscient.intrapaper.riskgateway.DurableRiskGateway;
import com.mscient.intrapaper.riskgateway.DurableRiskGateway.RecoveryCheckpoint;
import com.mscient.intrapaper.riskgateway.RiskGateway.*;
import com.mscient.intrapaper.riskgateway.RiskGatewayJournal.Verification;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Fixed synthetic process-loss boundary, after acknowledgement and before graceful close. */
public final class RiskRestartWorker {
    private static final Instant T = Instant.parse("2026-11-07T09:00:00Z");
    private static final RiskConfiguration CONFIG = new RiskConfiguration("INR",
            Map.of("SYNTH", new InstrumentRiskSpec("SYNTH", "INR", BigDecimal.ONE, 10)),
            new BigDecimal("10000"), new BigDecimal("10000"), new BigDecimal("1000"), Duration.ofSeconds(5));
    private RiskRestartWorker() { }
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 21 || args.length != 2) throw new IllegalArgumentException("Java 21 worker arguments required");
        Path dir = Path.of(args[1]);
        Path journal = dir.resolve("risk.journal"), checkpoint = dir.resolve("checkpoint.txt");
        if (args[0].equals("seed")) {
            var gateway = new DurableRiskGateway(journal, CONFIG);
            gateway.apply(new SessionStartEvent("Start", "SYNTHETIC", T,
                    Map.of("SYNTH", InstrumentMark.of(new BigDecimal("100"), margin()))));
            if (!gateway.evaluate(proposal()).accepted()) throw new IllegalStateException("Synthetic reservation rejected");
            gateway.apply(new ExecutionEvent("Fill", "SYNTHETIC", T, "Order", "SYNTH", OrderSide.BUY, 1, new BigDecimal("100")));
            var cp = gateway.checkpoint().journal();
            DrillFiles.write(checkpoint, cp.eventCount() + "\n" + cp.finalHash() + "\n" + cp.lastEventAt().orElseThrow() + "\n");
            DrillFiles.write(dir.resolve("before.txt"), state(gateway.state()));
            System.out.println("ACKNOWLEDGED position=1 residual=2; HALT_WITHOUT_CLOSE"); System.out.flush();
            Runtime.getRuntime().halt(0);
        } else if (args[0].equals("recover")) {
            List<String> cp = DrillFiles.ascii(DrillFiles.read(checkpoint, 4096)).lines().toList();
            var expected = new RecoveryCheckpoint(CONFIG, new Verification(Long.parseLong(cp.get(0)), cp.get(1), Optional.of(Instant.parse(cp.get(2)))));
            byte[] before = DrillFiles.read(journal, DrillFiles.MAX_FILE);
            try (var gateway = new DurableRiskGateway(journal, CONFIG, expected)) {
                String state = state(gateway.state());
                if (!state.equals(Files.readString(dir.resolve("before.txt"))) || !gateway.evaluate(proposal()).accepted()
                        || !Arrays.equals(before, DrillFiles.read(journal, DrillFiles.MAX_FILE))
                        || !state.equals(state(gateway.state())) || !gateway.checkpoint().equals(expected))
                    throw new IllegalStateException("Recovery/idempotency mismatch");
                DrillFiles.write(dir.resolve("after.txt"), state);
            }
            System.out.println("RECOVERED position=1 residual=2; RETRY_NO_APPEND; PASS");
        } else throw new IllegalArgumentException("Unknown fixed worker mode");
    }
    private static MarginInput margin() { return new MarginInput("SYNTH", BigDecimal.TEN, "SYNTHETIC", "DRILL_ONLY", "SYNTHETIC", T); }
    private static OrderProposal proposal() { return new OrderProposal("Order", "SYNTHETIC", T, "SYNTH", OrderSide.BUY, 3,
            Optional.of(new BigDecimal("100")), Optional.of(margin())); }
    private static String state(RiskState state) {
        long position = state.executedPositions().getOrDefault("SYNTH", 0L);
        long residual = state.workingOrderReservations().get("Order").remainingQuantity();
        if (position != 1 || residual != 2) throw new IllegalStateException("Partial exposure lost");
        return "position=" + position + "\nresidual=" + residual + "\nstate=" + state + "\n";
    }
}
