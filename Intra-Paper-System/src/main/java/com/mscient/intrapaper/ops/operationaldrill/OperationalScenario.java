package com.mscient.intrapaper.ops.operationaldrill;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Closed, versioned instruction set. No paths, commands, endpoints or caller fixtures. */
public record OperationalScenario(String id, List<Step> steps, byte[] bytes) {
    public enum Status { PASS, FAIL, INCOMPLETE }
    public enum Action { SETUP, INJECT, RUN, RECOVER }
    public enum Component { REPLAY, RISK, SETTLEMENT, RECONCILIATION, DAILY_OPS, PROCESS_RESTART }
    public enum Fault { MISSING_INPUT, STALE_INPUT, RISK_HALT, PARTIAL_FILL, UNKNOWN_FILL,
        SETTLEMENT_MISMATCH, LEDGER_MISMATCH, INCOMPLETE_SESSION, MISSING_SETTLEMENT_COSTS, PROCESS_RESTART }
    public record Step(int sequence, Action action, String target, Status expected, List<String> evidence) {
        public Step { evidence = List.copyOf(evidence); }
    }
    public OperationalScenario { steps = List.copyOf(steps); bytes = bytes.clone(); }
    @Override public byte[] bytes() { return bytes.clone(); }
    public static OperationalScenario load(Path path) throws IOException { return parse(DrillFiles.read(path, 65536)); }
    public static OperationalScenario parse(byte[] bytes) {
        String text = DrillFiles.ascii(bytes);
        String[] lines = text.split("\n", -1);
        if (bytes.length > 65536 || lines.length < 7 || lines.length > 70
                || !lines[0].equals("OFFLINE_OPERATIONAL_DRILL_V1")
                || !lines[1].matches("scenario_id=[a-z][a-z0-9-]{0,63}")
                || !lines[2].equals("classification=SYNTHETIC_ONLY")
                || !lines[3].equals("sequence,action,target,expected,required_evidence"))
            throw new IllegalArgumentException("Invalid V1 scenario header/size");
        List<Step> steps = new ArrayList<>();
        Fault active = null;
        boolean replay = false, ran = false, observedFault = false;
        for (int i = 4; i < lines.length - 1; i++) {
            String[] row = lines[i].split(",", -1);
            if (row.length != 5 || !row[0].equals(Integer.toString(i - 3)))
                throw new IllegalArgumentException("Exact columns and contiguous sequence required");
            Action action = Action.valueOf(row[1]);
            Status expected = Status.valueOf(row[3]);
            List<String> evidence = List.of(row[4].split(";", -1));
            if (evidence.size() > 16 || new HashSet<>(evidence).size() != evidence.size()
                    || !evidence.containsAll(List.of("result.properties", "log.txt", "inputs.csv")))
                throw new IllegalArgumentException("Unique evidence including result.properties;log.txt;inputs.csv required");
            for (String name : evidence) DrillFiles.portable(name);
            switch (action) {
                case SETUP -> {
                    if (i != 4 || !row[2].equals("SYNTHETIC_V1")) throw new IllegalArgumentException("First step must set up SYNTHETIC_V1");
                }
                case INJECT -> {
                    if (active != null) throw new IllegalArgumentException("Recover the active fault before injecting another");
                    active = Fault.valueOf(row[2]); observedFault = false;
                }
                case RECOVER -> {
                    if (active != Fault.valueOf(row[2]) || !observedFault)
                        throw new IllegalArgumentException("Recovery requires an observed matching fault");
                    active = null;
                }
                case RUN -> {
                    Component c = Component.valueOf(row[2]);
                    if ((c == Component.SETTLEMENT || c == Component.RECONCILIATION) && !replay)
                        throw new IllegalArgumentException("Run REPLAY before a dependent audit");
                    if (c == Component.REPLAY && active != Fault.MISSING_INPUT) replay = true;
                    if (c == Component.PROCESS_RESTART && active != Fault.PROCESS_RESTART)
                        throw new IllegalArgumentException("PROCESS_RESTART requires its injected fault");
                    if (active != null && affected(active).contains(c)) observedFault = true;
                    ran = true;
                }
            }
            if (i == 4 && action != Action.SETUP) throw new IllegalArgumentException("Setup must be first");
            steps.add(new Step(i - 3, action, row[2], expected, evidence));
        }
        if (!ran || steps.size() > 64 || (active != null && !observedFault))
            throw new IllegalArgumentException("Scenario must run a component and observe every injected fault");
        return new OperationalScenario(lines[1].substring(12), steps, bytes);
    }
    static Set<Component> affected(Fault f) {
        return switch (f) {
            case MISSING_INPUT -> Set.of(Component.REPLAY, Component.RISK, Component.DAILY_OPS);
            case STALE_INPUT, RISK_HALT -> Set.of(Component.RISK, Component.DAILY_OPS);
            case PARTIAL_FILL -> Set.of(Component.RISK);
            case UNKNOWN_FILL -> Set.of(Component.RISK, Component.RECONCILIATION, Component.DAILY_OPS);
            case SETTLEMENT_MISMATCH -> Set.of(Component.SETTLEMENT, Component.RECONCILIATION);
            case LEDGER_MISMATCH -> Set.of(Component.RECONCILIATION, Component.DAILY_OPS);
            case INCOMPLETE_SESSION -> Set.of(Component.DAILY_OPS);
            case MISSING_SETTLEMENT_COSTS -> Set.of(Component.SETTLEMENT, Component.RECONCILIATION, Component.DAILY_OPS);
            case PROCESS_RESTART -> Set.of(Component.PROCESS_RESTART);
        };
    }
}
