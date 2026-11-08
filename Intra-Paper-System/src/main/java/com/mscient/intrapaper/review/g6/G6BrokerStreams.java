package com.mscient.intrapaper.review.g6;

import static com.mscient.intrapaper.review.g6.G6Json.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Normalized external-ledger joins only. Never decodes broker formats or authenticates their provenance. */
final class G6BrokerStreams {
    static final List<String> KINDS=List.of("acknowledgements","rejects","cancellations","corrections","cash_flows","margin_inputs");
    record Stream(List<Map<String,Object>> records,boolean complete) { }
    record Result(boolean complete,BigDecimal openingCashFlow,Map<String,Object> diagnostics) { }

    private G6BrokerStreams() { }

    static void retain(G6Review.Context c,Map<String,Object> broker) throws IOException {
        for(String kind:KINDS) {
            if(broker.get(kind)==null) continue;
            for(Object raw:list(broker.get(kind))) c.check("broker.retained."+kind,()->{
                Map<String,Object> descriptor=obj(raw);
                Map<String,Object> stream=c.doc(descriptor.get("source"),"broker.retained."+kind);
                if(stream!=null) G6Review.retainReferences(c,stream);
            });
        }
    }

    private static Stream read(G6Review.Context c,Map<String,Object> session,Map<String,Object> broker,String kind) throws IOException {
        if(broker.get(kind)==null || list(broker.get(kind)).isEmpty()) {
            c.missing("broker."+kind,"Explicit normalized stream or source-bound no-records declaration is required");
            return new Stream(List.of(),false);
        }
        List<Map<String,Object>> records=new ArrayList<>();
        Set<String> ids=new HashSet<>(); Instant last=Instant.MIN; boolean complete=true;
        for(Object raw:list(broker.get(kind))) {
            Map<String,Object> descriptor=obj(raw); keys(descriptor,"source","classification","record_count");
            choice(descriptor.get("classification"),G6Review.CLASSES);
            BigDecimal count=number(descriptor.get("record_count"));
            require(count.signum()>=0 && count.stripTrailingZeros().scale()<=0,"Invalid stream record count");
            Map<String,Object> stream=c.doc(descriptor.get("source"),"broker."+kind);
            if(stream==null) { complete=false; continue; }
            keys(stream,"schema","session_id","pilot_id","identity","kind","classification","complete","expected_count","no_records_reason","records");
            schema(stream,"stream"); G6Review.same(session,stream,"session_id","pilot_id","identity");
            require(kind.equals(stream.get("kind")) && descriptor.get("classification").equals(stream.get("classification")),"Stream kind/classification mismatch");
            if(session.get("classification").equals("CLAIMED_LIVE_G5")) require(!List.of("SYNTHETIC","REPLAY_ONLY","SIMULATED").contains(stream.get("classification")),"Synthetic/replay stream falsely presented as live");
            require(stream.get("complete") instanceof Boolean,"Stream completeness must be explicit");
            List<?> rows=list(stream.get("records"));
            require(count.compareTo(BigDecimal.valueOf(rows.size()))==0,"Stream descriptor count differs from original bytes");
            BigDecimal expected=number(stream.get("expected_count"));
            require(expected.signum()>=0 && expected.stripTrailingZeros().scale()<=0,"Invalid expected record count");
            require(expected.compareTo(count)>=0,"Stream exceeds expected count");
            if(expected.compareTo(count)!=0 || !Boolean.TRUE.equals(stream.get("complete"))) {
                c.missing("broker."+kind,"Normalized stream is explicitly partial"); complete=false;
            }
            if(rows.isEmpty()) str(stream,"no_records_reason");
            else require(stream.get("no_records_reason")==null,"Nonempty stream contradicts no-records declaration");
            G6Review.retainReferences(c,stream);
            for(Object row:rows) {
                Map<String,Object> event=obj(row);
                require(ids.add(str(event,"event_id")),"Duplicate external-ledger event ID");
                Instant at=time(event);
                require(!at.isBefore(last),"External-ledger stream out of timestamp order"); last=at;
                records.add(event);
            }
        }
        return new Stream(records,complete);
    }

    static Result check(G6Review.Context c,Map<String,Object> session,Map<String,Object> daily,Map<String,Object> broker) throws IOException {
        Map<String,Stream> streams=new LinkedHashMap<>();
        for(String kind:KINDS) {
            // A missing stream does not hide a malformed, contradictory or tampered later stream.
            c.check("broker."+kind,()->streams.put(kind,read(c,session,broker,kind)));
            streams.putIfAbsent(kind,new Stream(List.of(),false));
        }
        Map<String,Map<String,Object>> orders=new TreeMap<>(),executions=new TreeMap<>();
        Map<String,Instant> firstFill=new HashMap<>(); Map<String,Map<String,Object>> cancellations=new TreeMap<>();
        Map<String,Object> risk=obj(daily.get("risk")),closing=obj(daily.get("closing"));
        for(Object raw:list(risk.get("events"))) {
            Map<String,Object> e=obj(raw);
            if("RESERVE".equals(e.get("kind"))) orders.put(str(e,"order_id"),e);
            if("CANCEL".equals(e.get("kind"))) cancellations.put(cancelKey(e),e);
        }
        for(Object raw:list(closing.get("executions"))) {
            Map<String,Object> e=obj(raw); executions.put(str(e,"execution_id"),e);
            firstFill.merge(str(e,"order_id"),time(e),(a,b)->a.isBefore(b) ? a : b);
        }
        Instant start=Instant.parse(str(daily,"session_start_utc")),end=Instant.parse(str(closing,"checked_at_utc"));
        Set<String> acknowledged=new HashSet<>(),rejected=new HashSet<>(),cancelled=new HashSet<>();
        for(Map<String,Object> e:streams.get("acknowledgements").records()) {
            keys(e,"event_id","order_id","broker_order_id","timestamp_utc");
            str(e,"broker_order_id"); Map<String,Object> order=order(orders,e,start,end);
            String id=str(e,"order_id"); require(acknowledged.add(id),"Duplicate terminal broker acknowledgement");
            require(!time(e).isBefore(time(order)),"Broker acknowledgement predates the order");
            require(!firstFill.containsKey(id) || !time(e).isAfter(firstFill.get(id)),"Fill precedes broker acknowledgement");
        }
        for(Map<String,Object> e:streams.get("rejects").records()) {
            keys(e,"event_id","order_id","timestamp_utc","reason"); str(e,"reason"); order(orders,e,start,end);
            String id=str(e,"order_id"); require(rejected.add(id) && !firstFill.containsKey(id),"Duplicate reject or rejected order has fills");
            require(cancellations.values().stream().anyMatch(cancel->id.equals(cancel.get("order_id"))),"Rejected order has no reservation release in the risk ledger");
        }
        if(streams.get("acknowledgements").complete() && streams.get("rejects").complete()) {
            for(String id:orders.keySet()) if(!acknowledged.contains(id) && !rejected.contains(id)) c.missing("broker.acknowledgements."+id,"Order has neither a broker acknowledgement nor rejection");
        }
        for(Map<String,Object> e:streams.get("cancellations").records()) {
            keys(e,"event_id","order_id","timestamp_utc","quantity","reason"); str(e,"reason"); order(orders,e,start,end);
            require(number(e.get("quantity")).signum()>0,"Cancellation quantity must be positive");
            String key=cancelKey(e); require(cancelled.add(key),"Duplicate cancellation");
            require(cancellations.containsKey(key),"Broker cancellation does not match the risk ledger");
        }
        if(streams.get("cancellations").complete()) require(cancelled.equals(cancellations.keySet()),"Risk/broker cancellation ledgers disagree");
        corrections(c,streams.get("corrections"),daily,start,end);
        BigDecimal cash=BigDecimal.ZERO;
        for(Map<String,Object> e:streams.get("cash_flows").records()) {
            keys(e,"event_id","timestamp_utc","amount","currency","kind","reference"); str(e,"reference");
            require(c.manifest.get("account_currency").equals(str(e,"currency")),"Cash-flow currency mismatch");
            String kind=choice(e.get("kind"),"DEPOSIT","WITHDRAWAL","ADJUSTMENT");
            BigDecimal amount=number(e.get("amount"));
            require(c.previousCloseTime==null || time(e).isAfter(c.previousCloseTime),"Opening cash flow predates the prior close or is counted again");
            require(!kind.equals("DEPOSIT") || amount.signum()>0,"Deposit must be positive");
            require(!kind.equals("WITHDRAWAL") || amount.signum()<0,"Withdrawal must be negative");
            if(time(e).isAfter(start)) c.missing("broker.cash_flows","Intraday external cash flow is unsupported by the existing flat-session daily accounting contract");
            else cash=cash.add(amount);
        }
        Map<String,Object> diagnostics=new TreeMap<>();
        diagnostics.put("rejects",streams.get("rejects").complete() ? streams.get("rejects").records().size() : null);
        diagnostics.put("corrections",streams.get("corrections").complete() ? streams.get("corrections").records().size() : null);
        Map<Instant,Map<String,BigDecimal>> marginSamples=new TreeMap<>();
        for(Map<String,Object> e:streams.get("margin_inputs").records()) {
            keys(e,"event_id","instrument_id","timestamp_utc","amount","currency","basis","source");
            String id=str(e,"instrument_id"); require(list(session.get("instruments")).contains(id),"Margin instrument outside scope");
            require(c.manifest.get("account_currency").equals(str(e,"currency")),"Margin currency mismatch");
            choice(e.get("basis"),"CALLER_SUPPLIED_BROKER_MARGIN","PROXY");
            BigDecimal amount=number(e.get("amount")); require(amount.signum()>=0,"Negative margin input");
            require(!time(e).isBefore(start) && !time(e).isAfter(end),"Margin timestamp outside session");
            c.source(e.get("source"),"margin.source");
            require(marginSamples.computeIfAbsent(time(e),ignored->new TreeMap<>()).putIfAbsent(id,amount)==null,"Duplicate instrument margin sample");
        }
        BigDecimal margin=null;
        if(streams.get("margin_inputs").complete()) for(Map<String,BigDecimal> sample:marginSamples.values()) {
            if(sample.keySet().equals(new TreeSet<>(list(session.get("instruments")).stream().map(Object::toString).toList()))) {
                BigDecimal total=sample.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);
                margin=margin==null ? total : margin.max(total);
            } else c.missing("broker.margin_inputs","Margin sample does not cover every instrument at the same timestamp");
        }
        diagnostics.put("margin_proxy",margin);
        diagnostics.put("margin_basis","MAXIMUM_OF_SUPPLIED_SIMULTANEOUS_SAMPLES_ONLY; not exchange margin or a capacity bound");
        Map<String,Object> counts=new TreeMap<>(); streams.forEach((kind,stream)->counts.put(kind,Map.of("record_count",stream.records().size(),"declared_complete",stream.complete())));
        diagnostics.put("streams",counts);
        boolean complete=streams.values().stream().allMatch(Stream::complete)
                && orders.keySet().stream().allMatch(id->acknowledged.contains(id)||rejected.contains(id));
        boolean cashSupported=streams.get("cash_flows").complete() && streams.get("cash_flows").records().stream().noneMatch(e->{ try { return time(e).isAfter(start); } catch(IOException invalid) { return true; } });
        return new Result(complete && cashSupported,cashSupported ? cash : null,diagnostics);
    }

    private static void corrections(G6Review.Context c,Stream stream,Map<String,Object> daily,Instant start,Instant end) throws IOException {
        Map<String,Map<String,Object>> finalRecords=new HashMap<>();
        for(Object raw:list(obj(daily.get("closing")).get("executions"))) { Map<String,Object> e=obj(raw); finalRecords.put("EXECUTION/"+str(e,"execution_id"),e); }
        for(Object raw:list(obj(obj(daily.get("settlement")).get("costs")).get("charges"))) { Map<String,Object> e=obj(raw); finalRecords.put("CHARGE/"+str(e,"charge_id"),e); }
        Map<String,Map<String,Object>> corrected=new HashMap<>();
        for(Map<String,Object> e:stream.records()) {
            keys(e,"event_id","target_kind","target_id","timestamp_utc","reason","original","corrected");
            String kind=choice(e.get("target_kind"),"EXECUTION","CHARGE"),id=str(e,"target_id"),key=kind+"/"+id; str(e,"reason");
            require(!time(e).isBefore(start) && !time(e).isAfter(end),"Correction timestamp outside declared reconciliation interval");
            Map<String,Object> before=c.doc(e.get("original"),"correction.original"),after=c.doc(e.get("corrected"),"correction.corrected");
            if(before==null || after==null) continue;
            String idField=kind.equals("EXECUTION") ? "execution_id" : "charge_id";
            require(id.equals(before.get(idField)) && id.equals(after.get(idField)),"Correction changes target identity");
            require(finalRecords.containsKey(key),"Correction target absent from final normalized ledger");
            if(corrected.containsKey(key)) require(Arrays.equals(bytes(corrected.get(key)),bytes(before)),"Correction does not link to its prior corrected record");
            if(kind.equals("EXECUTION")) require(!time(e).isBefore(time(before)),"Correction predates original execution");
            corrected.put(key,after);
        }
        for(var entry:corrected.entrySet()) require(Arrays.equals(bytes(entry.getValue()),bytes(finalRecords.get(entry.getKey()))),"Corrected record differs from daily final ledger");
    }

    private static String cancelKey(Map<String,Object> e) throws IOException {
        return str(e,"order_id")+"/"+time(e)+"/"+number(e.get("quantity")).stripTrailingZeros().toPlainString();
    }
    private static Map<String,Object> order(Map<String,Map<String,Object>> orders,Map<String,Object> e,Instant start,Instant end) throws IOException {
        Map<String,Object> order=orders.get(str(e,"order_id")); require(order!=null,"Broker event references an unknown order");
        Instant at=time(e); require(!at.isBefore(start) && !at.isAfter(end) && !at.isBefore(time(order)),"Broker event timestamp is inconsistent with its order/session");
        return order;
    }
    private static Instant time(Map<String,Object> event) throws IOException {
        try { String value=str(event,"timestamp_utc"); require(value.endsWith("Z") && !value.contains(":60"),"UTC timestamp required"); return Instant.parse(value); }
        catch(java.time.DateTimeException e) { throw new IOException("Invalid external-ledger timestamp",e); }
    }
}
