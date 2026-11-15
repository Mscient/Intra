package com.mscient.intrapaper.paper;

import com.mscient.intrapaper.data.ValidatedDataset;
import com.mscient.intrapaper.marketdata.RawCaptureSummary;
import com.mscient.intrapaper.provenance.RunConfig;
import com.mscient.intrapaper.simulation.Strategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Canonical provenance over the already admitted inputs and paper transaction chain. */
final class PaperManifest {
    private PaperManifest() { }
    static String inputs(ValidatedDataset data, RunConfig config, Strategy strategy,
            String historicalIdentity, String date, String codeHash, List<PaperEvent> events) {
        var hashes = new TreeMap<>(data.manifest().inputFileSha256());
        hashes.put("run-config", config.configSha256());
        hashes.put("frozen-local-events", PaperSessionStore.hash(events.toString()));
        return "{\n  \"schema_version\": 1,\n  \"mode\": \"NIFTY_FUTURES_LOCAL_PAPER\",\n"
                + "  \"historical_input_identity\": " + q(historicalIdentity) + ",\n"
                + "  \"session_date\": " + q(date) + ",\n  \"code_sha256\": " + q(codeHash) + ",\n"
                + "  \"strategy_id\": " + q(strategy.id()) + ",\n  \"strategy_parameters\": " + map(strategy.parameters()) + ",\n"
                + "  \"input_file_sha256\": " + map(hashes) + "\n}\n";
    }
    static String publication(String identity, String inputs, RawCaptureSummary journal,
            String checkpointHash, String reportHash, boolean complete) {
        return "{\n  \"schema_version\": 1,\n  \"run_id\": " + q(identity) + ",\n"
                + "  \"classification\": \"PAPER_PLUMBING_ONLY\",\n  \"performance_claim_eligible\": false,\n"
                + "  \"operating_evidence_eligible\": false,\n  \"g0\": \"OPEN\",\n"
                + "  \"session_complete\": " + complete + ",\n  \"inputs\": " + inputs.stripTrailing() + ",\n"
                + "  \"journal\": {\"file\": \"paper.journal\", \"records\": " + journal.eventCount()
                + ", \"bytes\": " + journal.bytesRead() + ", \"terminal_chain_sha256\": " + q(journal.finalHashHex()) + "},\n"
                + "  \"output_file_sha256\": " + map(Map.of("input-manifest.json", PaperSessionStore.hash(inputs),
                        "checkpoint.txt", checkpointHash, "session-report.txt", reportHash)) + "\n}\n";
    }
    private static String map(Map<String, String> values) {
        List<String> items = new ArrayList<>();
        new TreeMap<>(values).forEach((key, value) -> items.add(q(key) + ": " + q(value)));
        return "{" + String.join(", ", items) + "}";
    }
    private static String q(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
