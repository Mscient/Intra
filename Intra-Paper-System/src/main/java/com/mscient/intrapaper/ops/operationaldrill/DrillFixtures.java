package com.mscient.intrapaper.ops.operationaldrill;

import com.mscient.intrapaper.data.CsvTable;
import com.mscient.intrapaper.data.StrictJsonParser;
import com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.mscient.intrapaper.ops.operationaldrill.OperationalScenario.*;

/** Only build-owned, hash-pinned synthetic resources can become drill inputs. */
final class DrillFixtures {
    private final SortedMap<String, byte[]> resources = new TreeMap<>();
    DrillFixtures() throws IOException {
        String catalog = DrillFiles.ascii(resource("catalog.sha256"));
        for (String line : catalog.lines().toList()) {
            String[] row = line.split("=", -1);
            if (row.length != 2 || !row[1].matches("[0-9a-f]{64}")) throw new IOException("Invalid fixture catalog");
            DrillFiles.portable(row[0]);
            byte[] bytes = resource(row[0]);
            if (!DrillFiles.sha(bytes).equals(row[1]) || resources.putIfAbsent(row[0], bytes) != null)
                throw new IOException("Fixture catalog integrity mismatch");
        }
    }
    private static byte[] resource(String name) throws IOException {
        try (var in = DrillFixtures.class.getResourceAsStream("/operational-drill-v1/" + name)) {
            if (in == null) throw new IOException("Missing packaged synthetic fixture: " + name);
            byte[] bytes = in.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException("Oversized fixture");
            return bytes;
        }
    }
    String identity() { return DrillFiles.sha(DrillFiles.utf8(DrillFiles.inventory(resources))); }
    private String text(String name) { return new String(Objects.requireNonNull(resources.get(name)), StandardCharsets.UTF_8); }
    private void group(String prefix, Path target) throws IOException {
        for (var e : resources.entrySet()) if (e.getKey().startsWith(prefix + "/"))
            DrillFiles.write(target.resolve(e.getKey().substring(prefix.length() + 1)), e.getValue());
    }
    void baseline(Path inputs) throws IOException {
        group("replay", inputs.resolve("replay")); group("external", inputs.resolve("external"));
        group("daily/clean", inputs.resolve("daily")); group("settlement", inputs.resolve("settlement"));
        DrillFiles.write(inputs.resolve("risk/scenario.drill"), text("risk/restart.drill"));
    }
    void apply(Path inputs, Fault fault, boolean recovered) throws IOException {
        // Recovery restores the original declared source bytes. Risk uses the same cumulative
        // incident prefix followed by explicit acknowledgements/reconciliation/reset events.
        baseline(inputs);
        String risk = switch (fault) {
            case MISSING_INPUT -> "feed-loss";
            case STALE_INPUT -> "stale-marks";
            case RISK_HALT -> "limit-breach";
            case PARTIAL_FILL, UNKNOWN_FILL -> "uncertain-order";
            default -> "restart";
        };
        String riskText = text("risk/" + risk + ".drill");
        if (!recovered) {
            int deliveries = switch (fault) {
                case MISSING_INPUT -> 5; case STALE_INPUT -> 5; case RISK_HALT -> 4;
                case PARTIAL_FILL -> 13; case UNKNOWN_FILL -> 8; default -> Integer.MAX_VALUE;
            };
            riskText = String.join("\n", riskText.lines().limit(10L + deliveries).toList()) + "\n";
        }
        DrillFiles.write(inputs.resolve("risk/scenario.drill"), riskText);
        if (recovered) return;
        Path manifest = inputs.resolve("daily/session-evidence.json");
        switch (fault) {
            case MISSING_INPUT -> {
                Files.delete(inputs.resolve("replay/future.csv"));
                Files.delete(inputs.resolve("daily/artifacts/input.json"));
            }
            case STALE_INPUT -> group("daily/stale", inputs.resolve("daily"));
            case RISK_HALT -> group("daily/halted", inputs.resolve("daily"));
            case UNKNOWN_FILL -> {
                cell(inputs.resolve("external/ledger.csv"), "NEW-fill-F", "quantity_units", "");
                Map<String, Object> json = json(manifest);
                map(list(map(json.get("closing")).get("executions")).getFirst()).put("quantity", null);
                saveJson(manifest, json);
            }
            case SETTLEMENT_MISMATCH -> {
                cell(inputs.resolve("settlement/events.csv"), "F", "settlement_method", "CASH_OPTION_PAYOFF");
                cell(inputs.resolve("external/ledger.csv"), "NEW-settle-F", "amount_inr", "111");
            }
            case LEDGER_MISMATCH -> {
                cell(inputs.resolve("external/ledger.csv"), "NEW-fill-F", "price_inr", "104");
                Map<String, Object> json = json(manifest);
                map(json.get("closing")).put("gross_pnl", new java.math.BigDecimal("11"));
                saveJson(manifest, json);
            }
            case INCOMPLETE_SESSION -> {
                Map<String, Object> json = json(manifest);
                map(json.get("operator")).put("acknowledgements", List.of());
                saveJson(manifest, json);
            }
            case MISSING_SETTLEMENT_COSTS -> {
                Files.delete(inputs.resolve("settlement/costs.csv"));
                cell(inputs.resolve("external/ledger.csv"), "NEW-fee-settle-F", "amount_inr", "");
                group("daily/missing-costs", inputs.resolve("daily"));
            }
            default -> { }
        }
    }
    void bindLedger(Path inputs, String runId) throws IOException {
        Path external = inputs.resolve("external");
        String manifest = text("external/manifest-template.properties").replace("@REPLAY_RUN_ID@", runId)
                .replace("@LEDGER_SHA256@", DrillFiles.sha(DrillFiles.read(external.resolve("ledger.csv"), DrillFiles.MAX_FILE)))
                .replace("@COVERAGE_SHA256@", DrillFiles.sha(DrillFiles.read(external.resolve("coverage.csv"), DrillFiles.MAX_FILE)));
        DrillFiles.write(external.resolve("manifest.properties"), manifest);
    }
    private static void cell(Path csv, String id, String column, String value) throws IOException {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        int index = CsvTable.parseLine(lines.getFirst()).indexOf(column), changed = 0;
        for (int i = 1; i < lines.size(); i++) {
            List<String> cells = new ArrayList<>(CsvTable.parseLine(lines.get(i)));
            if (cells.getFirst().equals(id)) {
                cells.set(index, value); changed++;
                lines.set(i, String.join(",", cells.stream().map(CsvTable::escape).toList()));
            }
        }
        if (index < 0 || changed != 1) throw new IOException("Synthetic injection target changed");
        DrillFiles.write(csv, String.join("\n", lines) + "\n");
    }
    @SuppressWarnings("unchecked") static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>) value; }
    private static Map<String, Object> json(Path path) throws IOException { return map(StrictJsonParser.parse(Files.readString(path))); }
    private static void saveJson(Path path, Map<String, Object> json) throws IOException {
        DrillFiles.write(path, DailyOpsEvidenceReport.json(json) + "\n");
    }
}
