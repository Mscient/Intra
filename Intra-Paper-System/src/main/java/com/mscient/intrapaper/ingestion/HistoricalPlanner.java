package com.mscient.intrapaper.ingestion;

import com.mscient.intrapaper.data.IntervalSupport;
import com.mscient.intrapaper.reference.*;
import java.io.IOException;
import java.time.*;
import java.util.*;
import static com.mscient.intrapaper.ingestion.IngestionIO.*;

final class HistoricalPlanner {
    private HistoricalPlanner() { }
    static Map<String,Object> plan(HistoricalRequest r, byte[] policy, DatedInstrumentMappingRegistry registry) throws IOException {
        safeText(utf8(policy)); Map<String,Object> p=map(parse(policy));
        keys(p,"schema_version","policy_id","effective_date","review_by","source_url","evidence_status","boundary",
            "min_delay_ms","max_concurrency","limits");
        require(str(p,"schema_version").equals("historical-policy/v1"),"POLICY_VERSION"); id(str(p,"policy_id"));
        require(str(p,"source_url").equals("https://smartapi.angelone.in/docs"),"POLICY_SOURCE_INVALID");
        require(str(p,"evidence_status").equals("OWNER_CONSERVATIVE_UNVERIFIED"),"POLICY_EVIDENCE_INVALID");
        require(str(p,"boundary").equals("[start,end)"),"POLICY_BOUNDARY_INVALID");
        LocalDate effective=HistoricalRequest.date(str(p,"effective_date")), review=HistoricalRequest.date(str(p,"review_by"));
        require(!effective.isAfter(review) && !r.assessment().isBefore(effective) && !r.assessment().isAfter(review),"POLICY_STALE_OR_NOT_EFFECTIVE");
        integer(p.get("min_delay_ms"),1000,3600000); integer(p.get("max_concurrency"),1,1);
        List<?> limits=list(p.get("limits")); require(!limits.isEmpty() && limits.size()<=32,"POLICY_LIMITS_INVALID");
        Set<String> combinations=new HashSet<>(); long span=0;
        for (Object v:limits) {
            Map<String,Object> l=map(v); keys(l,"exchange","interval","max_window_seconds");
            require(str(l,"exchange").equals("NFO"),"POLICY_EXCHANGE_UNSUPPORTED");
            Duration d=IntervalSupport.duration(str(l,"interval"));
            long n=integer(l.get("max_window_seconds"),d.getSeconds(),366L*86400);
            require(n%d.getSeconds()==0,"POLICY_GRID_INVALID");
            require(combinations.add(str(l,"exchange")+":"+str(l,"interval")),"POLICY_DUPLICATE_LIMIT");
            if (str(l,"exchange").equals(r.s("exchange")) && str(l,"interval").equals(r.s("interval"))) span=n;
        }
        require(span>0,"EXCHANGE_INTERVAL_UNSUPPORTED");
        SortedSet<String> mappingIds=new TreeSet<>();
        boolean covered=registry!=null;
        if (registry!=null) {
            Instant cursor=r.from();
            while (cursor.isBefore(r.to())) {
                InstrumentMappingResolution resolution;
                try { resolution=registry.resolve(r.s("exchange"),r.s("source_token"),cursor); }
                catch (IllegalArgumentException missing) { covered=false; break; }
                require(resolution.instrumentId().equals(r.s("instrument_id")),"TOKEN_IDENTITY_CONFLICT");
                require(resolution.mapping().instrumentType()==ContractKind.FUTURE || resolution.mapping().instrumentType()==ContractKind.OPTION,"CONTRACT_UNSUPPORTED");
                mappingIds.add(resolution.mappingId());
                cursor=resolution.mapping().effectiveUntilUtc().filter(t->t.isBefore(r.to())).orElse(r.to());
            }
        }
        List<Object> windows=new ArrayList<>();
        for (Instant start=r.from();start.isBefore(r.to());) {
            Instant end=start.plusSeconds(span); if(end.isAfter(r.to())) end=r.to();
            require(windows.size()<10000,"PLAN_TOO_LARGE");
            windows.add(obj("index",windows.size(),"start_utc",start.toString(),"end_utc",end.toString())); start=end;
        }
        return obj("schema_version","historical-plan/v1","request_id",r.s("request_id"),"source",r.s("source"),
            "exchange",r.s("exchange"),"source_token",r.s("source_token"),"instrument_id",r.s("instrument_id"),
            "interval",r.s("interval"),"boundary","[start,end)","policy",p,"policy_sha256",hash(policy),
            "mapping_coverage",covered?"COVERED":"INCOMPLETE","mapping_ids",mappingIds,
            "registry_sha256",registry==null?null:registry.registrySha256(),"windows",windows,
            "permission_approved",false,"production_eligible",false,"requests_sent",0);
    }
}
