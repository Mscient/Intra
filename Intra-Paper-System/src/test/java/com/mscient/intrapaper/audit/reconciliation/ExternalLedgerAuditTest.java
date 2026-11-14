package com.mscient.intrapaper.audit.reconciliation;

import com.mscient.intrapaper.data.*;
import com.mscient.intrapaper.marketdata.*;
import com.mscient.intrapaper.provenance.*;
import com.mscient.intrapaper.simulation.*;
import com.mscient.intrapaper.simulation.portfolio.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import static com.mscient.intrapaper.audit.reconciliation.LedgerSupport.*;

/** Synthetic acceptance and adversarial evidence only; no account or broker inputs. */
public final class ExternalLedgerAuditTest {
    private static final String ID = "a".repeat(64);
    private static final String START = "2030-05-06T03:45:00Z", END = "2030-05-06T04:00:00Z";
    private static int checks;
    private static Path evidence;
    public static void main(String[] args) throws Exception {
        Path project = Path.of(args[0]); evidence = Path.of(args[1]); Files.createDirectories(evidence);
        economicsAndUnknowns(); historyAndKeys(); invalidInputs(); deterministicArtifacts(); itemizedChargesAndPolicy();
        portfolioWorkflow(project); singleWorkflow(project, false); singleWorkflow(project, true);
        System.out.println("ExternalLedgerAuditTest passed " + checks + " checks in 8 synthetic groups");
    }

    private static VerifiedReplay baseline() {
        List<Expected> r = new ArrayList<>();
        r.add(e(Kind.ORDER, "O1", "03:46", "side", "BUY", "status", "FILLED", "quantity_units", 10, "filled_units", 10));
        r.add(e(Kind.ORDER, "O2", "03:49", "side", "SELL", "status", "FILLED", "quantity_units", 3, "filled_units", 3));
        r.add(e(Kind.ORDER, "O3", "03:51", "side", "BUY", "status", "CANCELLED", "quantity_units", 2, "filled_units", 0));
        r.add(e(Kind.FILL, "F1", "03:47", "side", "BUY", "order_id", "O1", "quantity_units", 4, "price_inr", 100));
        r.add(e(Kind.FILL, "F2", "03:48", "side", "BUY", "order_id", "O1", "quantity_units", 6, "price_inr", 101));
        r.add(e(Kind.FILL, "F3", "03:50", "side", "SELL", "order_id", "O2", "quantity_units", 3, "price_inr", 102));
        facts(r, "F1", "03:47", "FILL", "-400", "1");
        facts(r, "F2", "03:48", "FILL", "-606", "1");
        facts(r, "F3", "03:50", "FILL", "306", "1");
        r.add(e(Kind.SETTLEMENT, "S1", "04:00", "quantity_units", 7, "price_inr", 110, "amount_inr", 770, "component", "CASH_FUTURES_FINAL_PRICE"));
        facts(r, "S1", "04:00", "SETTLEMENT", "770", "2");
        r.add(e(Kind.POSITION, "OPENING:A", "03:45", "checkpoint", "OPENING", "quantity_units", 0));
        r.add(e(Kind.POSITION, "CLOSING:A", "04:00", "checkpoint", "CLOSING", "quantity_units", 0));
        return new VerifiedReplay(ID, Instant.parse(START), Instant.parse(END), r, true);
    }
    private static Expected e(Kind type, String id, String clock, Object... pairs) {
        var f = fields(pairs); f.put("instrument_id", "A"); f.put("event_time_utc", stamp(clock)); return new Expected(type, id, f);
    }
    private static void facts(List<Expected> r, String id, String clock, String type, String cash, String fee) {
        r.add(e(Kind.FEE, id + ":TOTAL", clock, "related_type", type, "related_id", id, "component", "TOTAL", "amount_inr", fee));
        String c = type.equals("FILL") ? "TRADE_NOTIONAL" : "SETTLEMENT_PROCEEDS";
        r.add(e(Kind.CASH, id + ":" + c, clock, "related_type", type, "related_id", id, "component", c, "amount_inr", cash));
        c = type.equals("FILL") ? "FEE" : "SETTLEMENT_FEE";
        r.add(e(Kind.CASH, id + ":" + c, clock, "related_type", type, "related_id", id, "component", c, "amount_inr", "-" + fee));
    }
    private static List<Map<String, String>> ledger() {
        List<Map<String, String>> r = new ArrayList<>();
        add(r, Kind.POSITION, "opening", "03:45", "OPENING:A", "checkpoint", "OPENING", "quantity_units", 0);
        add(r, Kind.ORDER, "order1", "03:46", "O1", "side", "BUY", "status", "FILLED", "quantity_units", 10, "filled_units", 10);
        add(r, Kind.ORDER, "order2", "03:49", "O2", "side", "SELL", "status", "FILLED", "quantity_units", 3, "filled_units", 3);
        add(r, Kind.ORDER, "order3", "03:51", "O3", "side", "BUY", "status", "CANCELLED", "quantity_units", 2, "filled_units", 0);
        add(r, Kind.FILL, "fill1", "03:47", "F1", "side", "BUY", "order_id", "order1", "quantity_units", 4, "price_inr", 100);
        add(r, Kind.FILL, "fill2", "03:48", "F2", "side", "BUY", "order_id", "order1", "quantity_units", 6, "price_inr", 101);
        add(r, Kind.FILL, "fill3", "03:50", "F3", "side", "SELL", "order_id", "order2", "quantity_units", 3, "price_inr", 102);
        lines(r, "fill1", "F1", "03:47", "FILL", "-400", "1");
        lines(r, "fill2", "F2", "03:48", "FILL", "-606", "1");
        lines(r, "fill3", "F3", "03:50", "FILL", "306", "1");
        add(r, Kind.SETTLEMENT, "settlement1", "04:00", "S1", "quantity_units", 7, "price_inr", 110, "amount_inr", 770, "component", "CASH_FUTURES_FINAL_PRICE");
        lines(r, "settlement1", "S1", "04:00", "SETTLEMENT", "770", "2");
        add(r, Kind.POSITION, "closing", "04:00", "CLOSING:A", "checkpoint", "CLOSING", "quantity_units", 0);
        return r;
    }
    private static String stamp(String clock) { return "2030-05-06T" + clock + ":00Z"; }
    private static void add(List<Map<String, String>> rows, Kind type, String entity, String clock, String replayId, Object... pairs) {
        var row = fields(pairs); row.put("record_id", "new-" + entity); row.put("record_type", type.name()); row.put("entity_id", entity);
        row.put("action", "NEW"); row.put("replaces_record_id", ""); row.put("recorded_at_utc", stamp("04:30"));
        row.put("event_sequence", Integer.toString(rows.size() + 1)); row.put("event_time_utc", stamp(clock));
        row.put("replay_record_id", replayId); row.put("instrument_id", "A"); row.put("source_reference", "synthetic caller row");
        for (String field : HEADERS) row.putIfAbsent(field, ""); rows.add(row);
    }
    private static void lines(List<Map<String, String>> r, String entity, String id, String clock, String type, String cash, String fee) {
        add(r, Kind.FEE, "fee-" + entity, clock, id + ":TOTAL", "related_type", type, "related_id", entity, "component", "TOTAL", "amount_inr", fee);
        String c = type.equals("FILL") ? "TRADE_NOTIONAL" : "SETTLEMENT_PROCEEDS";
        add(r, Kind.CASH, "cash-" + entity, clock, id + ":" + c, "related_type", type, "related_id", entity, "component", c, "amount_inr", cash);
        c = type.equals("FILL") ? "FEE" : "SETTLEMENT_FEE";
        add(r, Kind.CASH, "cash-fee-" + entity, clock, id + ":" + c, "related_type", type, "related_id", entity, "component", c, "amount_inr", "-" + fee);
    }

    private static void economicsAndUnknowns() throws Exception {
        var full = evaluate("full-split-and-cancel", baseline(), ledger(), null);
        outcome(full, Status.PASS, "Full order with 4+6 units, partial sell and zero-fill cancellation");
        String totals = content(full, "totals.csv");
        require(totals.contains("FILL,A,BUY_units,10,10,0,PASS") && totals.contains("FILL,A,SELL_units,3,3,0,PASS"), "Separate BUY/SELL quantities");
        require(totals.contains("FEE,A,total_fee_inr,5,5,0,PASS"), "Independent fees sum to five");
        require(totals.contains("CASH,A,TRADE_NOTIONAL_inr,-700,-700,0,PASS") && totals.contains("CASH,A,SETTLEMENT_PROCEEDS_inr,770,770,0,PASS"), "Trade and settlement cash separate; gross cash is 70");
        require(totals.contains("POSITION,A,closing_units_from_flows,0,0,0,PASS"), "10 buys minus 3 sells minus 7 settled units is flat");
        List<Expected> partial = new ArrayList<>(baseline().records);
        var old = partial.stream().filter(e -> e.kind() == Kind.ORDER && e.id().equals("O1")).findFirst().orElseThrow();
        var fields = new TreeMap<>(old.fields()); fields.put("quantity_units", "12"); fields.put("status", "CANCELED");
        partial.set(partial.indexOf(old), new Expected(old.kind(), old.id(), fields));
        var rows = ledger(); entity(rows, "order1").put("quantity_units", "12"); entity(rows, "order1").put("status", "CANCELED");
        outcome(evaluate("partial-then-cancel", new VerifiedReplay(ID, baseline().start, baseline().end, partial, true), rows, null), Status.PASS, "Two fills totaling 10 of 12 units, then canceled remainder");

        rows = ledger(); entity(rows, "fee-fill1").put("amount_inr", "2");
        var fee = evaluate("fee-mismatch", baseline(), rows, null); outcome(fee, Status.FAIL, "Fee mismatch"); code(fee, "VALUE_MISMATCH"); code(fee, "CASH_ARITHMETIC_MISMATCH");
        require(content(fee, "summary.properties").contains("FILL.status=PASS"), "Fee exception does not erase fill reconciliation");
        rows = ledger(); entity(rows, "cash-fill1").put("amount_inr", "-401");
        var cash = evaluate("cash-mismatch", baseline(), rows, null); outcome(cash, Status.FAIL, "Cash mismatch"); code(cash, "CASH_ARITHMETIC_MISMATCH");
        rows = ledger(); entity(rows, "closing").put("quantity_units", "1");
        var position = evaluate("position-mismatch", baseline(), rows, null); outcome(position, Status.FAIL, "Position mismatch"); code(position, "POSITION_FLOW_MISMATCH");
        rows = ledger(); entity(rows, "order1").put("filled_units", "11");
        var order = evaluate("order-fill-total", baseline(), rows, null); outcome(order, Status.FAIL, "Declared order fill count differs from effective fills"); code(order, "ORDER_FILL_TOTAL_MISMATCH"); code(order, "OVERFILLED_ORDER");
        rows = ledger(); entity(rows, "settlement1").put("amount_inr", "769");
        var settlement = evaluate("settlement-mismatch", baseline(), rows, null); outcome(settlement, Status.FAIL, "Settlement proceeds mismatch"); code(settlement, "SETTLEMENT_ARITHMETIC_MISMATCH");
        rows = ledger(); entity(rows, "settlement1").put("quantity_units", "8"); entity(rows, "settlement1").put("amount_inr", "880"); entity(rows, "cash-settlement1").put("amount_inr", "880");
        var units = evaluate("settlement-quantity", baseline(), rows, null); outcome(units, Status.FAIL, "Settlement units mismatch"); code(units, "POSITION_FLOW_MISMATCH");
        for (String entity : List.of("fill1", "fee-fill1", "settlement1", "closing")) {
            rows = ledger(); String field = entity.equals("fill1") || entity.equals("closing") ? "quantity_units" : "amount_inr";
            entity(rows, entity).put(field, ""); var unknown = evaluate("unknown-" + entity, baseline(), rows, null);
            outcome(unknown, Status.INCOMPLETE, "Unknown " + field); code(unknown, "UNKNOWN_EXTERNAL_VALUE");
            require(content(unknown, "comparisons.csv").contains("," + field + ","), "Unknown has a field-level comparison");
            require(!content(unknown, "exceptions.csv").contains("VALUE_MISMATCH"), "Unknown does not become a numeric mismatch");
        }
        rows = ledger(); rows.remove(entity(rows, "fill2")); var missing = evaluate("missing-fill", baseline(), rows, null);
        outcome(missing, Status.INCOMPLETE, "Missing fill"); code(missing, "MISSING_EXTERNAL_RECORD");
        require(content(missing, "totals.csv").contains("FILL,A,BUY_units,10,,,INCOMPLETE"), "Missing quantity totals remain blank");
        rows = ledger(); rows.remove(entity(rows, "fee-fill1")); outcome(evaluate("missing-fee", baseline(), rows, null), Status.INCOMPLETE, "Missing fee record");
        rows = ledger(); entity(rows, "fee-fill1").put("source_reference", ""); outcome(evaluate("missing-source", baseline(), rows, null), Status.INCOMPLETE, "Missing row attribution");
        var gap = evaluate("coverage-gap", baseline(), ledger(), coverage().replace(START, stamp("03:46"))); outcome(gap, Status.INCOMPLETE, "Declared coverage gap"); code(gap, "COVERAGE_GAP");
        var incomplete = evaluate("coverage-unknown", baseline(), ledger(), coverage().replace("FEE," + START + "," + END + ",COMPLETE", "FEE," + START + "," + END + ",UNKNOWN")); outcome(incomplete, Status.INCOMPLETE, "Caller coverage unknown");
        require(content(incomplete, "totals.csv").contains("FEE,A,total_fee_inr,5,,,INCOMPLETE"), "Unknown coverage does not imply an observed complete fee total");
        var omitted = evaluate("coverage-missing", baseline(), ledger(), coverage().lines().filter(l -> !l.startsWith("SETTLEMENT,")).reduce("", (a,b) -> a+b+"\n")); outcome(omitted, Status.INCOMPLETE, "Missing settlement coverage even with supplied events");
        var unknownReplay = new ArrayList<Expected>();
        for (Expected record : baseline().records) {
            var values = new TreeMap<>(record.fields());
            if (record.id().equals("S1:TOTAL") || record.id().equals("S1:SETTLEMENT_FEE")) values.put("amount_inr", "");
            unknownReplay.add(new Expected(record.kind(), record.id(), values));
        }
        var unmodeled = evaluate("unmodeled-settlement-fee", new VerifiedReplay(ID, baseline().start, baseline().end, unknownReplay, true), ledger(), null);
        outcome(unmodeled, Status.INCOMPLETE, "Unmodeled replay charge remains unknown"); code(unmodeled, "UNKNOWN_EXPECTED_VALUE");
        require(content(unmodeled, "comparisons.csv").contains("amount_inr,,2,,INCOMPLETE"), "Expected fee has a blank amount and blank delta");
        List<Expected> shorts = new ArrayList<>();
        for (Expected record : baseline().records) {
            var values = new TreeMap<>(record.fields());
            if (values.containsKey("side")) values.put("side", values.get("side").equals("BUY") ? "SELL" : "BUY");
            if (record.kind() == Kind.SETTLEMENT) { values.put("quantity_units", "-7"); values.put("amount_inr", "-770"); }
            if (record.kind() == Kind.CASH && Set.of("TRADE_NOTIONAL", "SETTLEMENT_PROCEEDS").contains(record.get("component")))
                values.put("amount_inr", number(amount(record.get("amount_inr")).negate()));
            shorts.add(new Expected(record.kind(), record.id(), values));
        }
        rows = ledger();
        for (var row : rows) {
            if (!row.get("side").isEmpty()) row.put("side", row.get("side").equals("BUY") ? "SELL" : "BUY");
            if (row.get("record_type").equals("SETTLEMENT")) { row.put("quantity_units", "-7"); row.put("amount_inr", "-770"); }
            if (row.get("record_type").equals("CASH") && Set.of("TRADE_NOTIONAL", "SETTLEMENT_PROCEEDS").contains(row.get("component"))) row.put("amount_inr", number(amount(row.get("amount_inr")).negate()));
        }
        var shortSettlement = evaluate("short-settlement", new VerifiedReplay(ID, baseline().start, baseline().end, shorts, true), rows, null);
        outcome(shortSettlement, Status.PASS, "Signed short futures settlement");
        require(content(shortSettlement, "totals.csv").contains("SETTLEMENT,A,signed_units,-7,-7,0,PASS"), "Short settlement closes negative units");
        List<Expected> zeroOption = new ArrayList<>();
        for (Expected record : baseline().records) {
            var values = new TreeMap<>(record.fields());
            if (record.kind() == Kind.SETTLEMENT) { values.put("component", "CASH_OPTION_PAYOFF"); values.put("price_inr", "0"); values.put("amount_inr", "0"); }
            if (record.id().equals("S1:SETTLEMENT_PROCEEDS")) values.put("amount_inr", "0");
            zeroOption.add(new Expected(record.kind(), record.id(), values));
        }
        rows = ledger(); entity(rows, "settlement1").put("component", "CASH_OPTION_PAYOFF"); entity(rows, "settlement1").put("price_inr", "0"); entity(rows, "settlement1").put("amount_inr", "0"); entity(rows, "cash-settlement1").put("amount_inr", "0");
        outcome(evaluate("zero-option-settlement", new VerifiedReplay(ID, baseline().start, baseline().end, zeroOption, true), rows, null), Status.PASS, "Explicit zero option payoff is known, not blank");
        rows = ledger(); entity(rows, "fill1").put("order_id", "order2");
        var wrongParent = evaluate("wrong-order-link", baseline(), rows, null); outcome(wrongParent, Status.FAIL, "Explicit fills with wrong external parent order"); code(wrongParent, "ORDER_FILL_TOTAL_MISMATCH");
        System.out.println("Economics, partial fills, cancellation, settlement and unknown values passed");
    }

    private static void historyAndKeys() throws Exception {
        var rows = ledger(); var original = entity(rows, "fill1"); var correction = update(rows, original, "CORRECT", "correction1"); original.put("price_inr", "99"); rows.add(correction);
        var corrected = evaluate("correction", baseline(), rows, null); outcome(corrected, Status.PASS, "Correction restores execution price"); code(corrected, "CORRECTION_APPLIED");
        require(content(corrected, "events.csv").contains("SUPERSEDED,new-fill1") && content(corrected, "events.csv").contains("ACTIVE,correction1"), "History retains original and replacement rows");
        var second = update(rows, correction, "CORRECT", "correction2"); rows.add(second); outcome(evaluate("correction-chain", baseline(), rows, null), Status.PASS, "Multiple explicit corrections");
        for (String entity : List.of("order1", "fee-fill1", "cash-fill1", "settlement1", "closing")) {
            rows = ledger(); original = entity(rows, entity); correction = update(rows, original, "CORRECT", "correct-" + entity);
            if (entity.equals("order1")) original.put("quantity_units", "11");
            else if (entity.equals("closing")) original.put("quantity_units", "1");
            else original.put("amount_inr", entity.equals("fee-fill1") ? "2" : entity.equals("cash-fill1") ? "-401" : "769");
            rows.add(correction); var result = evaluate("correction-" + entity, baseline(), rows, null);
            outcome(result, Status.PASS, "Full replacement correction for " + entity); code(result, "CORRECTION_APPLIED");
        }
        rows = ledger(); add(rows, Kind.FILL, "erroneous", "03:52", "NO-SUCH-FILL", "order_id", "order1", "side", "BUY", "quantity_units", 1, "price_inr", 999);
        rows.add(update(rows, entity(rows, "erroneous"), "REVERSE", "reverse1"));
        var reversed = evaluate("reversed-error", baseline(), rows, null); outcome(reversed, Status.PASS, "Reversed extra fill has no effective accounting impact"); code(reversed, "REVERSAL_APPLIED");
        require(content(reversed, "events.csv").contains("REVERSED,reverse1"), "Reversal is retained in history");
        rows = ledger(); rows.add(update(rows, entity(rows, "fill1"), "REVERSE", "reverse-required"));
        var absent = evaluate("reversed-required-fill", baseline(), rows, null); outcome(absent, Status.INCOMPLETE, "Reversed expected fill becomes missing"); code(absent, "MISSING_RELATED_RECORD");
        rows = ledger(); rows.add(new TreeMap<>(entity(rows, "fill1"))); rows.getLast().put("event_sequence", Integer.toString(rows.size()));
        var duplicate = evaluate("duplicate-id", baseline(), rows, null); outcome(duplicate, Status.FAIL, "Duplicate event ID"); code(duplicate, "DUPLICATE_RECORD_ID");
        rows = ledger(); var conflict = new TreeMap<>(entity(rows, "fill1"));
        conflict.put("price_inr", "999"); conflict.put("event_sequence", Integer.toString(rows.size() + 1)); rows.add(conflict);
        var conflicting = evaluate("conflicting-id-payload", baseline(), rows, null);
        outcome(conflicting, Status.FAIL, "Same event ID with conflicting economics"); code(conflicting, "DUPLICATE_RECORD_ID");
        require(content(conflicting, "totals.csv").contains("FILL,A,BUY_units,10,,,INCOMPLETE"), "Conflicting ID has no winning payload or invented total");
        rows = ledger(); var extra = new TreeMap<>(entity(rows, "fill1")); extra.put("record_id", "duplicate-entity"); extra.put("event_sequence", Integer.toString(rows.size()+1)); rows.add(extra);
        var duplicateEntity = evaluate("duplicate-entity", baseline(), rows, null); outcome(duplicateEntity, Status.FAIL, "Duplicate entity ID"); code(duplicateEntity, "DUPLICATE_ENTITY_ID");
        rows = ledger(); extra = new TreeMap<>(entity(rows, "fill1")); extra.put("record_id", "duplicate-match"); extra.put("entity_id", "other-fill"); extra.put("event_sequence", Integer.toString(rows.size()+1)); rows.add(extra);
        var duplicateMatch = evaluate("duplicate-replay-id", baseline(), rows, null); outcome(duplicateMatch, Status.FAIL, "Two entities claim same replay execution"); code(duplicateMatch, "DUPLICATE_REPLAY_MATCH");
        rows = ledger(); entity(rows, "fill2").put("event_sequence", entity(rows, "fill1").get("event_sequence"));
        var sequence = evaluate("duplicate-sequence", baseline(), rows, null); outcome(sequence, Status.FAIL, "Duplicate source sequence"); code(sequence, "DUPLICATE_SEQUENCE");
        rows = ledger(); original = entity(rows, "fill1"); rows.add(update(rows, original, "CORRECT", "branch1")); rows.add(update(rows, original, "CORRECT", "branch2"));
        var branch = evaluate("branch", baseline(), rows, null); outcome(branch, Status.FAIL, "Branched correction"); code(branch, "BRANCHED_CORRECTION");
        rows = ledger(); original = entity(rows, "fill1"); correction = update(rows, original, "CORRECT", "orphan"); correction.put("replaces_record_id", "lost-history"); rows.set(rows.indexOf(original), correction);
        var orphan = evaluate("orphan", baseline(), rows, null); outcome(orphan, Status.INCOMPLETE, "Missing correction target"); code(orphan, "MISSING_CORRECTION_TARGET");
        rows = ledger(); original = entity(rows, "fill1"); correction = update(rows, original, "CORRECT", "cycle-a"); correction.put("replaces_record_id", "cycle-b"); rows.remove(original); rows.add(correction);
        second = update(rows, correction, "CORRECT", "cycle-b"); rows.add(second);
        var cycle = evaluate("cycle", baseline(), rows, null); outcome(cycle, Status.FAIL, "Cyclic history"); code(cycle, "CORRECTION_CYCLE");
        rows = ledger(); correction = update(rows, entity(rows, "fill1"), "CORRECT", "early-correction"); correction.put("event_sequence", "1"); correction.put("recorded_at_utc", stamp("03:47")); rows.add(correction);
        var early = evaluate("correction-order", baseline(), rows, null); outcome(early, Status.FAIL, "Correction before its target"); code(early, "INVALID_CORRECTION_ORDER");
        rows = ledger(); rows.add(update(rows, entity(rows, "fill1"), "REVERSE", "terminal")); rows.add(update(rows, rows.getLast(), "CORRECT", "after-terminal"));
        var terminal = evaluate("after-reversal", baseline(), rows, null); outcome(terminal, Status.FAIL, "Cannot update a terminal reversal"); code(terminal, "INVALID_CORRECTION_TARGET");
        rows = ledger(); Collections.reverse(rows); var outOfOrder = evaluate("physical-order", baseline(), rows, null); outcome(outOfOrder, Status.PASS, "Physical input order is normalized by explicit references"); code(outOfOrder, "OUT_OF_ORDER_INPUT");
        rows = ledger(); String seq = entity(rows, "fill1").get("event_sequence"); entity(rows, "fill1").put("event_sequence", entity(rows, "fill2").get("event_sequence")); entity(rows, "fill2").put("event_sequence", seq);
        var economicOrder = evaluate("economic-order", baseline(), rows, null); outcome(economicOrder, Status.PASS, "Economic events out of arrival order"); code(economicOrder, "OUT_OF_ORDER_EVENT");
        rows = ledger(); rows.forEach(r -> r.put("replay_record_id", "")); var fallback = evaluate("fallback", baseline(), rows, null); outcome(fallback, Status.PASS, "All documented unique fallback keys");
        require(content(fallback, "comparisons.csv").contains("FALLBACK_KEY"), "Fallback use is recorded");
        rows = ledger(); entity(rows, "fill1").put("replay_record_id", "UNKNOWN-ID"); var wrongId = evaluate("explicit-wrong", baseline(), rows, null); outcome(wrongId, Status.FAIL, "Unknown explicit ID cannot fall back"); code(wrongId, "UNKNOWN_REPLAY_ID");
        List<Expected> ambiguous = new ArrayList<>(baseline().records); ambiguous.add(e(Kind.ORDER, "O4", "03:46", "side", "BUY", "status", "FILLED", "quantity_units", 10, "filled_units", 10));
        rows = ledger(); entity(rows, "order1").put("replay_record_id", "");
        var ambiguity = evaluate("ambiguous-fallback", new VerifiedReplay(ID, baseline().start, baseline().end, ambiguous, true), rows, null); outcome(ambiguity, Status.FAIL, "Ambiguous order fallback"); code(ambiguity, "AMBIGUOUS_FALLBACK");
        rows = ledger(); entity(rows, "fill1").put("replay_record_id", ""); entity(rows, "fill1").put("order_id", ""); outcome(evaluate("missing-key", baseline(), rows, null), Status.INCOMPLETE, "Incomplete fallback key");
        System.out.println("Corrections, reversals, duplicate IDs, ordering and explicit/fallback keys passed");
    }

    private static void invalidInputs() throws Exception {
        for (String bad : List.of("NaN", "1e2", "-1", "0", "1.0000000000001", "1000000000000000000000000000000")) {
            var rows = ledger(); entity(rows, "fill1").put("price_inr", bad); var result = evaluate("bad-price-" + checks, baseline(), rows, null);
            outcome(result, Status.FAIL, "Malformed/invalid price"); code(result, "INVALID_RECORD");
        }
        for (String bad : List.of("1.5", "9223372036854775808", "0", "-1")) {
            var rows = ledger(); entity(rows, "fill1").put("quantity_units", bad); outcome(evaluate("bad-units-" + checks, baseline(), rows, null), Status.FAIL, "Invalid units");
        }
        var rows = ledger(); entity(rows, "fee-fill1").put("amount_inr", "-1"); outcome(evaluate("negative-fee", baseline(), rows, null), Status.FAIL, "Negative fee is not an implicit reversal");
        rows = ledger(); entity(rows, "fill1").put("event_time_utc", "2030-05-06T09:17:00+05:30"); outcome(evaluate("offset-timestamp", baseline(), rows, null), Status.FAIL, "Non-normalized timestamp");
        rows = ledger(); entity(rows, "fill1").put("event_time_utc", stamp("05:00")); outcome(evaluate("future-event", baseline(), rows, null), Status.FAIL, "Event time after recorded time");
        rows = ledger(); entity(rows, "fill1").put("event_time_utc", stamp("03:44")); var outside = evaluate("outside-scope", baseline(), rows, null); outcome(outside, Status.FAIL, "Outside replay scope"); code(outside, "EVENT_OUTSIDE_REPLAY_SCOPE");
        rows = ledger(); entity(rows, "fill1").put("amount_inr", "1"); outcome(evaluate("inapplicable-cell", baseline(), rows, null), Status.FAIL, "Inapplicable payload cannot hide cash");
        rows = ledger(); var reversal = update(rows, entity(rows, "fill1"), "REVERSE", "bad-reverse"); reversal.put("amount_inr", "-100"); rows.add(reversal); outcome(evaluate("reversal-payload", baseline(), rows, null), Status.FAIL, "Reversal requires a blank payload");
        Path folder = evidence.resolve("input-integrity"); Path manifest = bundle(folder, ExternalLedgerAudit.ReplayKind.PORTFOLIO, ID, ledger(), coverage());
        byte[] original = Files.readAllBytes(folder.resolve("ledger.csv")); Files.writeString(folder.resolve("ledger.csv"), "tampered\n", StandardOpenOption.APPEND);
        var tamper = loadAndReconcile(manifest, baseline()); outcome(tamper, Status.FAIL, "External hash tampering"); code(tamper, "INPUT_HASH_MISMATCH");
        require(content(tamper, "events.csv").lines().count() == 1, "Hash-mismatched ledger was not consumed");
        require(content(tamper, "inputs.csv").contains(hash(original)) && content(tamper, "inputs.csv").contains(hash(Files.readAllBytes(folder.resolve("ledger.csv")))), "Declared and actual hashes retained");
        Files.write(folder.resolve("ledger.csv"), original);
        String saved = Files.readString(manifest);
        for (String bad : List.of(saved.replace("comparison=EXACT", "comparison=APPROXIMATE"), saved.replace("fee_basis=TOTAL_PER_EVENT\n", ""),
                saved + "schema_version=1\n", saved.replace("ledger_file=ledger.csv", "ledger_file=../ledger.csv"), saved.replace("cash_basis=SIGNED_NOTIONAL_EXCLUDING_FEES", "cash_basis=ACCOUNT_BALANCE"))) {
            Files.writeString(manifest, bad); outcome(loadAndReconcile(manifest, baseline()), Status.FAIL, "Unsupported/incomplete manifest policy");
        }
        Files.writeString(manifest, saved);
        for (byte[] invalid : List.of(new byte[]{(byte)0xc3, 0x28}, utf8("\uFEFF" + new String(original, java.nio.charset.StandardCharsets.UTF_8)),
                utf8(new String(original, java.nio.charset.StandardCharsets.UTF_8) + "\n"), utf8("unknown-header\n"))) {
            Files.write(folder.resolve("ledger.csv"), invalid); Files.writeString(manifest, saved.replace(hash(original), hash(invalid)));
            outcome(loadAndReconcile(manifest, baseline()), Status.FAIL, "Strict UTF-8/CSV structural rejection");
        }
        System.out.println("Malformed records, explicit policies, strict bytes and input tampering passed");
    }

    private static void deterministicArtifacts() throws Exception {
        Path folder = evidence.resolve("determinism/input"); Path manifest = bundle(folder, ExternalLedgerAudit.ReplayKind.PORTFOLIO, ID, ledger(), coverage());
        var first = loadAndReconcile(manifest, baseline()); var second = loadAndReconcile(manifest, baseline()); equal(first.files(), second.files(), "Repeated report bytes");
        Path moved = evidence.resolve("determinism/relocated"); copy(folder, moved);
        equal(first.files(), loadAndReconcile(moved.resolve("external.properties"), baseline()).files(), "Relocated bundle report bytes");
        Locale locale = Locale.getDefault(); TimeZone zone = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR")); TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            Files.setLastModifiedTime(folder.resolve("ledger.csv"), FileTime.from(Instant.parse("2000-01-01T00:00:00Z")));
            equal(first.files(), loadAndReconcile(manifest, baseline()).files(), "Locale, timezone and file metadata do not enter report bytes");
        } finally { Locale.setDefault(locale); TimeZone.setDefault(zone); }
        var prepared = new ExternalLedgerAudit.Prepared(first.files(), ExternalLedgerAudit.Outcome.PASS);
        var a = LedgerAuditArtifacts.write(evidence.resolve("determinism/reports-a"), prepared);
        var b = LedgerAuditArtifacts.write(evidence.resolve("determinism/reports-b"), prepared);
        require(a.auditId().equals(b.auditId()), "Repeated report manifest IDs"); equal(contents(a.path()), contents(b.path()), "Repeated published bytes including manifest");
        require(LedgerAuditArtifacts.verify(a.path()).status() == ExternalLedgerAudit.Outcome.PASS, "Artifact verification");
        require(LedgerAuditArtifacts.write(evidence.resolve("determinism/reports-a"), prepared).auditId().equals(a.auditId()), "Idempotent publication");
        Path comparison = a.path().resolve("comparisons.csv"); byte[] clean = Files.readAllBytes(comparison); Files.writeString(comparison, "tampered\n", StandardOpenOption.APPEND);
        fails(() -> LedgerAuditArtifacts.verify(a.path()), "Report byte tampering detected"); Files.write(comparison, clean);
        Path extra = a.path().resolve("unexpected.txt"); Files.writeString(extra, "extra"); fails(() -> LedgerAuditArtifacts.verify(a.path()), "Unexpected report file detected"); Files.delete(extra);
        Path outputManifest = a.path().resolve(LedgerAuditArtifacts.MANIFEST); byte[] canonical = Files.readAllBytes(outputManifest); Files.writeString(outputManifest, "schema_version=1\n");
        fails(() -> LedgerAuditArtifacts.verify(a.path()), "Manifest tampering detected"); Files.write(outputManifest, canonical);
        require(ExternalLedgerAuditCli.run(new String[]{"verify-artifact", a.path().toString()}) == 0, "Standalone artifact CLI");
        require(ExternalLedgerAuditCli.run(new String[]{"unsupported"}) == 1, "Invalid CLI exits 1");
        System.out.println("Deterministic bytes, manifest hashing, publication and report tampering passed");
    }

    private static void itemizedChargesAndPolicy() throws Exception {
        var records = new ArrayList<>(baseline().records);
        records.add(e(Kind.FEE, "F1:BROKERAGE", "03:47", "related_type", "FILL", "related_id", "F1", "component", "BROKERAGE", "amount_inr", "0.6"));
        records.add(e(Kind.FEE, "F1:GST", "03:47", "related_type", "FILL", "related_id", "F1", "component", "GST", "amount_inr", "0.4"));
        records.add(e(Kind.FEE, "F2:FILL_MODEL", "03:48", "related_type", "FILL", "related_id", "F2", "component", "FILL_MODEL", "amount_inr", "1"));
        records.add(e(Kind.FEE, "F3:FILL_MODEL", "03:50", "related_type", "FILL", "related_id", "F3", "component", "FILL_MODEL", "amount_inr", "1"));
        records.add(e(Kind.FEE, "S1:CHARGE:ENTRY", "04:00", "related_type", "SETTLEMENT", "related_id", "S1", "component", "CHARGE:ENTRY", "amount_inr", "1.5"));
        records.add(e(Kind.FEE, "S1:GST:ENTRY", "04:00", "related_type", "SETTLEMENT", "related_id", "S1", "component", "GST:ENTRY", "amount_inr", "0.5"));
        var replay = new VerifiedReplay(ID, baseline().start, baseline().end, records, true);
        var rows = itemizedLedger();
        var exact = evaluateItemized("itemized-exact", replay, rows);
        outcome(exact, Status.PASS, "Itemized charges with independent declared totals");
        require(content(exact, "totals.csv").contains("FEE,A,total_fee_inr,5,5,0,PASS"), "Components do not double count total fees");
        require(content(exact, "comparisons.csv").contains("fee_total_from_items,2,2,0,PASS"), "Settlement charge plus GST equals total");
        rows = itemizedLedger(); entity(rows, "brokerage").put("amount_inr", "0.7"); entity(rows, "gst").put("amount_inr", "0.3");
        var offset = evaluateItemized("offsetting-charge-errors", replay, rows);
        outcome(offset, Status.FAIL, "Equal totals cannot hide offsetting item mismatches"); code(offset, "VALUE_MISMATCH");
        require(!content(offset, "exceptions.csv").contains("CHARGE_ITEM_TOTAL_MISMATCH"), "Declared total remains arithmetically consistent");
        rows = itemizedLedger(); entity(rows, "brokerage").put("amount_inr", "0.7");
        var badTotal = evaluateItemized("item-sum-mismatch", replay, rows); outcome(badTotal, Status.FAIL, "Bad item sum"); code(badTotal, "CHARGE_ITEM_TOTAL_MISMATCH");
        rows = itemizedLedger(); rows.remove(entity(rows, "gst"));
        var missing = evaluateItemized("missing-item", replay, rows); outcome(missing, Status.INCOMPLETE, "Missing item stays unknown");
        require(content(missing, "comparisons.csv").contains("fee_total_from_items,,1,,INCOMPLETE"), "Missing component not inferred from total");
        rows = itemizedLedger(); entity(rows, "gst").put("amount_inr", "");
        outcome(evaluateItemized("unknown-item", replay, rows), Status.INCOMPLETE, "Blank component stays unknown");
        rows = itemizedLedger(); var corrected = update(rows, entity(rows, "brokerage"), "CORRECT", "correct-item");
        entity(rows, "brokerage").put("amount_inr", "99"); rows.add(corrected); Collections.reverse(rows);
        outcome(evaluateItemized("item-correction-out-of-order", replay, rows), Status.PASS, "Item correction through declared history IDs");
        rows = itemizedLedger(); rows.add(update(rows, entity(rows, "gst"), "REVERSE", "reverse-item"));
        outcome(evaluateItemized("item-reversal", replay, rows), Status.INCOMPLETE, "Reversed required item stays missing");
        rows = itemizedLedger(); var duplicate = new TreeMap<>(entity(rows, "gst")); duplicate.put("record_id", "duplicate-item");
        duplicate.put("entity_id", "other-gst"); duplicate.put("event_sequence", "99"); rows.add(duplicate);
        var ambiguous = evaluateItemized("duplicate-item-match", replay, rows); outcome(ambiguous, Status.FAIL, "Two declared items claim one charge"); code(ambiguous, "DUPLICATE_REPLAY_MATCH");
        rows = itemizedLedger(); entity(rows, "gst").put("component", "BROKERAGE");
        outcome(evaluateItemized("wrong-item-code", replay, rows), Status.FAIL, "Correct replay ID cannot mask wrong component");

        Path manifest = bundle(evidence.resolve("explicit-policy"), ExternalLedgerAudit.ReplayKind.PORTFOLIO, ID, ledger(), coverage());
        String saved = Files.readString(manifest).replace("matching=UNIQUE_COMPOSITE_KEYS", "matching=DECLARED_IDS");
        Files.writeString(manifest, saved);
        var exactIds = loadAndReconcile(manifest, baseline()); outcome(exactIds, Status.PASS, "Declared IDs policy");
        String summary = content(exactIds, "summary.properties");
        require(summary.contains("external_records=CALLER_SUPPLIED") && summary.contains("policy_source_reference=synthetic exact policy v1") && summary.contains("tolerances=NONE"), "Policy provenance and caller assertion labeled");
        for (String bad : List.of(saved.replace("tolerances=NONE", "tolerances=0.01"), saved.replace("tolerances=NONE\n", ""),
                saved.replace("policy_source_reference=synthetic exact policy v1", "policy_source_reference="), saved.replace("matching=DECLARED_IDS\n", ""))) {
            Files.writeString(manifest, bad); outcome(loadAndReconcile(manifest, baseline()), Status.FAIL, "No implicit tolerance or policy provenance");
        }
        rows = ledger(); entity(rows, "fill1").put("replay_record_id", "");
        manifest = bundle(evidence.resolve("explicit-policy-missing-id"), ExternalLedgerAudit.ReplayKind.PORTFOLIO, ID, rows, coverage());
        Files.writeString(manifest, Files.readString(manifest).replace("matching=UNIQUE_COMPOSITE_KEYS", "matching=DECLARED_IDS"));
        var noId = loadAndReconcile(manifest, baseline()); outcome(noId, Status.INCOMPLETE, "Missing declared ID cannot fall back"); code(noId, "MISSING_DECLARED_ID");
        require(!content(noId, "comparisons.csv").contains("FALLBACK_KEY"), "No silent composite matching");

        var exported = new ArrayList<Expected>();
        var breakdown = new PortfolioExecutionFeeBreakdown(PortfolioExecutionFeeBreakdown.CostModel.DATED_SCHEDULE,
                "S", "E", "c".repeat(64), "synthetic tariff", LocalDate.parse("2030-05-06"), com.mscient.intrapaper.reference.ContractKind.FUTURE,
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("1"), new BigDecimal("2"), new BigDecimal("3"),
                new BigDecimal("4"), new BigDecimal("5"), new BigDecimal("6"), new BigDecimal("21"), new BigDecimal("7"), new BigDecimal("28"));
        LedgerChargeItems.execution(exported, new PortfolioExecution("F", 1, "A", Instant.parse(START), PortfolioExecution.Side.BUY, 1,
                new BigDecimal("100"), new BigDecimal("28"), Optional.of(breakdown)));
        require(exported.size() == 7 && exported.stream().filter(e -> e.id().equals("F:GST")).findFirst().orElseThrow().get("amount_inr").equals("7"), "Dated schedule exports actual GST, not its taxable base");
        require(exported.stream().map(e -> amount(e.get("amount_inr"))).reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(new BigDecimal("28")) == 0, "Dated item exporter preserves exact applied amounts");
        exported.clear();
        LedgerChargeItems.execution(exported, new PortfolioExecution("F", 1, "A", Instant.parse(START), PortfolioExecution.Side.BUY, 1, new BigDecimal("100"), new BigDecimal("28")));
        require(exported.size() == 1 && exported.getFirst().get("amount_inr").isEmpty(), "Missing itemization is unknown, not allocated from total");
        System.out.println("Itemized charges, history, policy provenance and declared-ID matching passed");
    }

    private static List<Map<String, String>> itemizedLedger() {
        var rows = ledger();
        add(rows, Kind.FEE, "brokerage", "03:47", "F1:BROKERAGE", "related_type", "FILL", "related_id", "fill1", "component", "BROKERAGE", "amount_inr", "0.6");
        add(rows, Kind.FEE, "gst", "03:47", "F1:GST", "related_type", "FILL", "related_id", "fill1", "component", "GST", "amount_inr", "0.4");
        add(rows, Kind.FEE, "item2", "03:48", "F2:FILL_MODEL", "related_type", "FILL", "related_id", "fill2", "component", "FILL_MODEL", "amount_inr", "1");
        add(rows, Kind.FEE, "item3", "03:50", "F3:FILL_MODEL", "related_type", "FILL", "related_id", "fill3", "component", "FILL_MODEL", "amount_inr", "1");
        add(rows, Kind.FEE, "settle-charge", "04:00", "S1:CHARGE:ENTRY", "related_type", "SETTLEMENT", "related_id", "settlement1", "component", "CHARGE:ENTRY", "amount_inr", "1.5");
        add(rows, Kind.FEE, "settle-gst", "04:00", "S1:GST:ENTRY", "related_type", "SETTLEMENT", "related_id", "settlement1", "component", "GST:ENTRY", "amount_inr", "0.5");
        return rows;
    }

    private static LedgerReconciler.Result evaluateItemized(String name, VerifiedReplay replay, List<Map<String, String>> rows) throws Exception {
        Path folder = evidence.resolve("cases").resolve(name);
        Path manifest = bundle(folder, ExternalLedgerAudit.ReplayKind.PORTFOLIO, ID, rows, coverage());
        Files.writeString(manifest, Files.readString(manifest).replace("fee_basis=TOTAL_PER_EVENT", "fee_basis=ITEMIZED_AND_TOTAL"));
        var result = loadAndReconcile(manifest, replay);
        Path report = folder.resolve("report"); Files.createDirectories(report);
        for (var file : result.files().entrySet()) Files.write(report.resolve(file.getKey()), file.getValue());
        return result;
    }

    private static void portfolioWorkflow(Path project) throws Exception {
        Path root = evidence.resolve("verified-portfolio"), replayInputs = root.resolve("replay-inputs"); copy(project.resolve("examples/portfolio-settlement"), replayInputs);
        Path inputs = replayInputs.resolve("inputs.csv"), settings = replayInputs.resolve("portfolio.properties");
        var replay = PortfolioReplayCsvRunner.replay(inputs, settings);
        require(replay.result().executions().size() == 2 && replay.result().settlements().size() == 2, "Independent fixed portfolio fixture has two fills/two settlements");
        Path run = new PortfolioReplayArtifactWriter().write(root.resolve("replays"), replay.dataset(), replay.result()).path();
        Map<String, byte[]> originalRun = contents(run);
        Path external = root.resolve("external"); copy(project.resolve("examples/external-ledger-reconciliation"), external);
        Path manifest = external.resolve("external.properties"); fromTemplate(external, manifest, run.getFileName().toString());
        var first = ExternalLedgerAudit.audit(ExternalLedgerAudit.ReplayKind.PORTFOLIO, run, inputs, settings, manifest, root.resolve("audits-a"));
        require(first.status() == ExternalLedgerAudit.Outcome.PASS, "Verified actual portfolio versus independently authored external fixture");
        String totals = Files.readString(first.path().resolve("totals.csv"));
        require(totals.contains("FEE,F,total_fee_inr,2.19,2.19,0,PASS") && totals.contains("FEE,O,total_fee_inr,1.64,1.64,0,PASS"), "Dated modeled fees independently reconcile");
        require(totals.contains("CASH,F,TRADE_NOTIONAL_inr,-103,-103,0,PASS") && totals.contains("CASH,O,SETTLEMENT_PROCEEDS_inr,5,5,0,PASS"), "Futures and option cash have separate rows");
        String summary = Files.readString(first.path().resolve("summary.properties"));
        require(summary.contains("replay_verified=true") && summary.contains("source_authenticity=NOT_ESTABLISHED") && summary.contains("g4_gate_closed=false"), "Verification is scoped; authenticity and G4 are not claimed");
        require(Files.readString(first.path().resolve("inputs.csv")).contains("audit/build-artifact"), "Exact audit build provenance");
        var second = ExternalLedgerAudit.audit(ExternalLedgerAudit.ReplayKind.PORTFOLIO, run, inputs, settings, manifest, root.resolve("audits-b"));
        require(first.auditId().equals(second.auditId()), "Actual verified replay repeated report hash"); equal(contents(first.path()), contents(second.path()), "Actual verified replay repeated output bytes");
        require(ExternalLedgerAudit.verify(first.path(), ExternalLedgerAudit.ReplayKind.PORTFOLIO, run, inputs, settings, manifest).status() == ExternalLedgerAudit.Outcome.PASS, "Semantic report reconstruction");
        Path itemManifest = external.resolve("itemized/external.properties");
        fromTemplate(itemManifest.getParent(), itemManifest, run.getFileName().toString());
        var itemAudit = ExternalLedgerAudit.audit(ExternalLedgerAudit.ReplayKind.PORTFOLIO, run, inputs, settings, itemManifest, root.resolve("itemized-audits"));
        require(itemAudit.status() == ExternalLedgerAudit.Outcome.PASS, "Independent itemized portfolio fixture");
        require(Files.readString(itemAudit.path().resolve("comparisons.csv")).contains("fee_total_from_items,1.19,1.19,0,PASS"), "Settlement components sum independently to total");
        require(Files.readString(itemAudit.path().resolve("totals.csv")).contains("FEE,F,total_fee_inr,2.19,2.19,0,PASS"), "Itemization never double counts totals");
        require(ExternalLedgerAudit.verify(itemAudit.path(), ExternalLedgerAudit.ReplayKind.PORTFOLIO, run, inputs, settings, itemManifest).auditId().equals(itemAudit.auditId()), "Itemized semantic reconstruction is deterministic");
        require(ExternalLedgerAuditCli.run(auditArgs("verify", first.path(), run, inputs, settings, manifest, null)) == 0, "Semantic CLI PASS");
        fails(() -> ExternalLedgerAudit.audit(ExternalLedgerAudit.ReplayKind.PORTFOLIO, run, inputs, settings, manifest, run.resolve("sidecar")), "Output inside replay rejected");
        byte[] ledger = Files.readAllBytes(external.resolve("ledger.csv")); Files.writeString(external.resolve("ledger.csv"), "tampered\n", StandardOpenOption.APPEND);
        var failedInput = ExternalLedgerAudit.audit(ExternalLedgerAudit.ReplayKind.PORTFOLIO, run, inputs, settings, manifest, root.resolve("tampered-input"));
        require(failedInput.status() == ExternalLedgerAudit.Outcome.FAIL && Files.readString(failedInput.path().resolve("exceptions.csv")).contains("INPUT_HASH_MISMATCH"), "Tampered external bytes produce hashed FAIL report");
        require(ExternalLedgerAuditCli.run(auditArgs("audit", null, run, inputs, settings, manifest, root.resolve("tampered-input"))) == 2, "Audit CLI FAIL exits 2");
        Files.write(external.resolve("ledger.csv"), ledger);
        Path executions = run.resolve("executions.csv"); byte[] savedExecutions = Files.readAllBytes(executions); Files.writeString(executions, "tampered\n", StandardOpenOption.APPEND);
        var failedReplay = ExternalLedgerAudit.audit(ExternalLedgerAudit.ReplayKind.PORTFOLIO, run, inputs, settings, manifest, root.resolve("tampered-replay"));
        require(failedReplay.status() == ExternalLedgerAudit.Outcome.FAIL && Files.readString(failedReplay.path().resolve("summary.properties")).contains("replay_verified=false"), "Tampered replay never supplies financial baseline");
        require(Files.readString(failedReplay.path().resolve("comparisons.csv")).lines().count() == 1, "No unverified comparisons were invented"); Files.write(executions, savedExecutions);
        Path costs = replayInputs.resolve("settlement-costs.csv"); byte[] savedCosts = Files.readAllBytes(costs); Files.writeString(costs, "\n", StandardOpenOption.APPEND);
        var changedIndirect = ExternalLedgerAudit.audit(ExternalLedgerAudit.ReplayKind.PORTFOLIO, run, inputs, settings, manifest, root.resolve("tampered-cost-input"));
        require(changedIndirect.status() == ExternalLedgerAudit.Outcome.FAIL, "Changed indirect replay input is rejected");
        require(Files.readString(changedIndirect.path().resolve("inputs.csv")).contains(hash(Files.readAllBytes(costs))), "Changed indirect input hash is retained without a verified financial baseline"); Files.write(costs, savedCosts);
        Path rehashed = root.resolve("forged-report"); copy(first.path(), rehashed);
        Files.writeString(rehashed.resolve("summary.properties"), summary.replace("status=PASS\n", "status=FAIL\n"));
        Map<String, byte[]> forgedFiles = contents(rehashed); forgedFiles.remove(LedgerAuditArtifacts.MANIFEST);
        byte[] forgedManifest = LedgerAuditArtifacts.manifest(forgedFiles); Files.write(rehashed.resolve(LedgerAuditArtifacts.MANIFEST), forgedManifest);
        Path forged = root.resolve("forged").resolve(hash(forgedManifest)); Files.createDirectories(forged.getParent()); Files.move(rehashed, forged);
        require(LedgerAuditArtifacts.verify(forged).status() == ExternalLedgerAudit.Outcome.FAIL, "Rehashed report has integrity only");
        fails(() -> ExternalLedgerAudit.verify(forged, ExternalLedgerAudit.ReplayKind.PORTFOLIO, run, inputs, settings, manifest), "Rehashed financial/summary change fails semantic reconstruction");
        String savedSettings = Files.readString(settings); Files.writeString(settings, savedSettings.lines().filter(l -> !l.startsWith("portfolio.settlement_cost_schedule_csv=")).reduce("", (a,b) -> a+b+"\n"));
        var noCosts = PortfolioReplayCsvRunner.replay(inputs, settings); Path unknownRun = new PortfolioReplayArtifactWriter().write(root.resolve("unknown-cost-replays"), noCosts.dataset(), noCosts.result()).path();
        fromTemplate(external, manifest, unknownRun.getFileName().toString());
        var unknown = ExternalLedgerAudit.audit(ExternalLedgerAudit.ReplayKind.PORTFOLIO, unknownRun, inputs, settings, manifest, root.resolve("unknown-cost-audits"));
        require(unknown.status() == ExternalLedgerAudit.Outcome.INCOMPLETE, "Actual NOT_MODELED settlement fees are unknown despite stored zero");
        require(Files.readString(unknown.path().resolve("comparisons.csv")).contains("amount_inr,,1.19,,INCOMPLETE"), "Actual unknown replay fee stays blank");
        require(ExternalLedgerAuditCli.run(auditArgs("audit", null, unknownRun, inputs, settings, manifest, root.resolve("unknown-cost-audits"))) == 3, "Audit CLI INCOMPLETE exits 3");
        Files.writeString(settings, savedSettings); fromTemplate(external, manifest, run.getFileName().toString());
        equal(originalRun, contents(run), "Replay remains byte-for-byte unchanged");
        System.out.println("Verified portfolio, actual settlement, all CLI modes, tampering and unknown modeled costs passed");
    }

    private static void singleWorkflow(Path project, boolean partial) throws Exception {
        Path root = evidence.resolve(partial ? "verified-single-partial" : "verified-single-full"); Files.createDirectories(root);
        try (var paths = Files.list(project.resolve("examples"))) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) Files.copy(file, root.resolve(file.getFileName()));
        }
        Path bars = root.resolve("synthetic-futures-bars.csv"), settings = root.resolve("synthetic-run.properties");
        if (partial) {
            String config = Files.readString(settings).replace("strategy.lots=1", "strategy.lots=2").replace("risk.max_position_lots=1", "risk.max_position_lots=3")
                    .replace("risk.max_margin_inr=1000", "risk.max_margin_inr=3000").replace("position.cross_session_policy=REQUIRE_FLAT", "position.cross_session_policy=ALLOW_OVERNIGHT");
            Files.writeString(settings, config + "execution.model=L1_PARTIAL_IOC\nexecution.quote_journal_file=synthetic-quotes.qj\nexecution.quote_source_name=SYNTHETIC-QUOTES\nexecution.decision_to_arrival_latency_ms=0\nexecution.quote_maximum_age_ms=60000\nexecution.quote_maximum_future_skew_ms=0\n");
            try (var writer = QuoteJournalWriter.create(root.resolve("synthetic-quotes.qj"))) {
                long sequence = 0;
                for (Map<String, String> row : CsvTable.read(bars).rows()) {
                    Instant timestamp = OffsetDateTime.parse(row.get("timestamp")).toInstant();
                    long ticks = new BigDecimal(row.get("open")).divide(new BigDecimal(row.get("tick_size"))).longValueExact();
                    writer.append(new MarketQuote(1, ++sequence, "SYNTHETIC-QUOTES", row.get("instrument_id"), Optional.of(timestamp), timestamp, sequence * 1_000_000L, ticks - 1, 1, ticks + 1, 1));
                }
            }
        }
        RunConfig config = RunConfig.load(settings); ValidatedDataset data = ValidatedDataset.load(bars, config); Strategy strategy = new SmaCrossoverStrategy(config);
        var result = new BacktestEngine().run(data, config, strategy);
        if (partial) require(result.fills().stream().anyMatch(f -> result.orders().stream().anyMatch(o -> o.orderId().equals(f.orderId()) && o.requestedUnits() > f.quantityUnits() && o.status().equals("CANCELED"))), "Actual verified L1 partial IOC has a canceled remainder");
        else require(!result.fills().isEmpty(), "Actual full single replay executes fills");
        String id = RunIdentity.contentId(data, config, strategy); Path run = new RunArtifactWriter().write(root.resolve("replays"), id, data, config, result).path();
        var rows = singleExternal(run, data, result);
        String coverage = coverage().replace(START, data.bars().getFirst().timestamp().toString()).replace(END, data.bars().getLast().timestamp().plusSeconds(60).toString());
        Path manifest = bundle(root.resolve("external"), ExternalLedgerAudit.ReplayKind.SINGLE, id, rows, coverage);
        var audit = ExternalLedgerAudit.audit(ExternalLedgerAudit.ReplayKind.SINGLE, run, bars, settings, manifest, root.resolve("audits"));
        require(audit.status() == ExternalLedgerAudit.Outcome.PASS, "Actual verified single " + (partial ? "partial" : "full") + " replay reconciliation");
        require(ExternalLedgerAudit.verify(audit.path(), ExternalLedgerAudit.ReplayKind.SINGLE, run, bars, settings, manifest).auditId().equals(audit.auditId()), "Single adapter semantic reconstruction");
        if (partial) require(Files.readString(audit.path().resolve("inputs.csv")).contains("quote_journal"), "Partial quote-journal input provenance");
        System.out.println("Actual verified single " + (partial ? "partial IOC" : "full-fill") + " workflow passed");
    }
    private static List<Map<String, String>> singleExternal(Path run, ValidatedDataset data, BacktestResult result) throws Exception {
        String instrument = data.bars().getFirst().instrumentId(); List<Map<String, String>> rows = new ArrayList<>();
        Map<String, String> filled = new HashMap<>();
        for (var fill : CsvTable.read(run.resolve("fills.csv")).rows()) filled.merge(fill.get("order_id"), fill.get("quantity_units"), (a,b) -> new BigDecimal(a).add(new BigDecimal(b)).toPlainString());
        for (var order : CsvTable.read(run.resolve("orders.csv")).rows()) {
            add(rows, Kind.ORDER, order.get("order_id"), "03:45", order.get("order_id"), "side", order.get("side"), "status", order.get("status"), "quantity_units", order.get("requested_units"), "filled_units", filled.getOrDefault(order.get("order_id"), "0"));
            rows.getLast().put("event_time_utc", order.get("decision_timestamp"));
        }
        for (var fill : CsvTable.read(run.resolve("fills.csv")).rows()) {
            String id = fill.get("fill_id"), timestamp = fill.get("timestamp");
            add(rows, Kind.FILL, id, "03:45", id, "side", fill.get("side"), "order_id", fill.get("order_id"), "quantity_units", fill.get("quantity_units"), "price_inr", fill.get("fill_price")); rows.getLast().put("event_time_utc", timestamp);
            BigDecimal cash = new BigDecimal(fill.get("quantity_units")).multiply(new BigDecimal(fill.get("fill_price"))); if (fill.get("side").equals("BUY")) cash = cash.negate();
            int first = rows.size(); lines(rows, id, id, "03:45", "FILL", number(cash), fill.get("fee_inr"));
            for (int i = first; i < rows.size(); i++) rows.get(i).put("event_time_utc", timestamp);
        }
        add(rows, Kind.POSITION, "open", "03:45", "OPENING:" + instrument, "checkpoint", "OPENING", "quantity_units", 0);
        rows.getLast().put("event_time_utc", data.bars().getFirst().timestamp().toString());
        add(rows, Kind.POSITION, "close", "03:45", "CLOSING:" + instrument, "checkpoint", "CLOSING", "quantity_units", result.finalPositionUnits());
        rows.getLast().put("event_time_utc", data.bars().getLast().timestamp().plusSeconds(60).toString());
        rows.forEach(r -> r.put("instrument_id", instrument));
        return rows;
    }

    private static String[] auditArgs(String mode, Path audit, Path run, Path input, Path settings, Path manifest, Path output) {
        return mode.equals("audit") ? new String[]{mode, "PORTFOLIO", run.toString(), input.toString(), settings.toString(), manifest.toString(), output.toString()}
                : new String[]{mode, audit.toString(), "PORTFOLIO", run.toString(), input.toString(), settings.toString(), manifest.toString()};
    }
    private static Map<String, String> update(List<Map<String, String>> rows, Map<String, String> original, String action, String id) {
        var result = new TreeMap<>(original); result.put("record_id", id); result.put("action", action); result.put("replaces_record_id", original.get("record_id"));
        result.put("event_sequence", Integer.toString(rows.size()+1)); result.put("recorded_at_utc", stamp("05:00"));
        if (action.equals("REVERSE")) for (String field : HEADERS.subList(7, HEADERS.size()-1)) result.put(field, "");
        return result;
    }
    private static Map<String, String> entity(List<Map<String, String>> rows, String id) { return rows.stream().filter(r -> id.equals(r.get("entity_id"))).findFirst().orElseThrow(); }
    private static String coverage() {
        StringBuilder out = new StringBuilder(String.join(",", COVERAGE_HEADERS) + "\n");
        for (Kind kind : DOMAINS) row(out, kind, START, END, "COMPLETE", "synthetic caller coverage"); return out.toString();
    }
    private static Path bundle(Path folder, ExternalLedgerAudit.ReplayKind kind, String id, List<Map<String, String>> rows, String coverage) throws Exception {
        Files.createDirectories(folder); StringBuilder out = new StringBuilder(String.join(",", HEADERS) + "\n");
        for (Map<String, String> fields : rows) row(out, HEADERS.stream().map(h -> fields.getOrDefault(h, "")).toArray());
        byte[] ledger = utf8(out.toString()), windows = utf8(coverage); Files.write(folder.resolve("ledger.csv"), ledger); Files.write(folder.resolve("coverage.csv"), windows);
        Path manifest = folder.resolve("external.properties");
        Files.writeString(manifest, "schema_version=1\nclassification=SYNTHETIC\nreplay_kind=" + kind + "\nreplay_run_id=" + id
                + "\ncomparison=EXACT\nmatching=UNIQUE_COMPOSITE_KEYS\ntolerances=NONE\npolicy_source_reference=synthetic exact policy v1\ncash_basis=SIGNED_NOTIONAL_EXCLUDING_FEES\nfee_basis=TOTAL_PER_EVENT\ncurrency=INR\nledger_file=ledger.csv\nledger_sha256=" + hash(ledger)
                + "\ncoverage_file=coverage.csv\ncoverage_sha256=" + hash(windows) + "\nsource_id=SYNTHETIC\nsource_reference=synthetic fixture only\n");
        return manifest;
    }
    private static void fromTemplate(Path folder, Path manifest, String id) throws Exception {
        String template = Files.readString(folder.resolve("manifest-template.properties"));
        Files.writeString(manifest, template.replace("@REPLAY_RUN_ID@", id).replace("@LEDGER_SHA256@", hash(Files.readAllBytes(folder.resolve("ledger.csv")))).replace("@COVERAGE_SHA256@", hash(Files.readAllBytes(folder.resolve("coverage.csv")))));
    }
    private static LedgerReconciler.Result evaluate(String name, VerifiedReplay replay, List<Map<String, String>> rows, String coverage) throws Exception {
        Path folder = evidence.resolve("cases").resolve(name); Path manifest = bundle(folder, ExternalLedgerAudit.ReplayKind.PORTFOLIO, ID, rows, coverage == null ? coverage() : coverage);
        var result = loadAndReconcile(manifest, replay); Path report = folder.resolve("report"); Files.createDirectories(report);
        for (var file : result.files().entrySet()) Files.write(report.resolve(file.getKey()), file.getValue()); return result;
    }
    private static LedgerReconciler.Result loadAndReconcile(Path manifest, VerifiedReplay replay) {
        var snapshots = new Snapshots(); var findings = new ArrayList<Finding>(); var input = LedgerInputs.load(manifest, snapshots, findings);
        return LedgerReconciler.reconcile(replay, input, snapshots, findings);
    }
    private static String content(LedgerReconciler.Result result, String file) { return new String(result.files().get(file), java.nio.charset.StandardCharsets.UTF_8); }
    private static void outcome(LedgerReconciler.Result result, Status expected, String label) { require(result.status() == expected, label + ": expected " + expected + " got " + result.status() + "\n" + content(result, "exceptions.csv")); }
    private static void code(LedgerReconciler.Result result, String code) { require(content(result, "exceptions.csv").contains("," + code + ","), "Exception code " + code); }
    private static void copy(Path from, Path to) throws Exception {
        Files.createDirectories(to); try (var paths = Files.walk(from)) { for (Path path : paths.toList()) {
            Path target = to.resolve(from.relativize(path)); if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
        } }
    }
    private static Map<String, byte[]> contents(Path folder) throws Exception { var result = new TreeMap<String, byte[]>(); try (var paths = Files.list(folder)) { for (Path path : paths.toList()) result.put(path.getFileName().toString(), Files.readAllBytes(path)); } return result; }
    private static void equal(Map<String, byte[]> a, Map<String, byte[]> b, String label) {
        require(a.keySet().equals(b.keySet()), label + " file set"); for (String name : a.keySet()) require(Arrays.equals(a.get(name), b.get(name)), label + " " + name);
    }
    private interface Checked { void run() throws Exception; }
    private static void fails(Checked action, String label) throws Exception { boolean rejected = false; try { action.run(); } catch (Exception expected) { rejected = true; } require(rejected, label); }
    private static void require(boolean condition, String label) { checks++; if (!condition) throw new AssertionError(label); }
}
