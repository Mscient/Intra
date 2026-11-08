package com.mscient.intrapaper.review.g6;

import static com.mscient.intrapaper.review.g6.G6Json.*;
import java.io.IOException;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.*;

/** Immutable history snapshots. An extension must retain every byte-canonical prior event. */
public final class G6History {
    private G6History() { }
    static final String[] TYPES = {"REGISTRATION", "EVIDENCE", "CORRECTION", "ANALYSIS", "REVIEWER_DECISION", "OWNER_DECISION", "REVOCATION"};
    static final class Checked {
        final List<Map<String,Object>> events = new ArrayList<>();
        final List<String> digests = new ArrayList<>();
        final Map<String,Map<String,Object>> active = new LinkedHashMap<>();
        String head() { return digests.isEmpty() ? ZERO : digests.getLast(); }
    }
    static Checked check(Map<String,Object> history, String reviewId) throws IOException {
        keys(history, "schema", "events"); schema(history,"history"); Checked c = new Checked();
        Instant last = Instant.MIN;
        boolean analysisCurrent=false,reviewer=false,owner=false,revoked=false;
        for (Object raw : list(history.get("events"))) {
            Map<String,Object> e = obj(raw);
            keys(e,"schema","sequence","previous_sha256","type","review_id","subject_sha256","subject_path","supersedes","event_time","report","signature");
            schema(e,"event"); require(number(e.get("sequence")).compareTo(java.math.BigDecimal.valueOf(c.events.size())) == 0, "History sequence/reordering violation");
            require(c.head().equals(digest(e.get("previous_sha256"))), "History link mismatch");
            require(reviewId.equals(str(e,"review_id")), "History review identity mismatch");
            String type = choice(e.get("type"), TYPES); digest(e.get("subject_sha256"));
            require(!revoked,"Revocation is terminal for this review identity");
            require((c.events.isEmpty()) == type.equals("REGISTRATION"), "Registration must occur exactly once, first");
            Instant at;
            try { require(str(e,"event_time").endsWith("Z") && !str(e,"event_time").contains(":60"),"Event timestamp must be a UTC instant"); at = Instant.parse(str(e,"event_time")); } catch (RuntimeException x) { throw new IOException("Invalid event UTC instant", x); }
            require(!at.isBefore(last), "Out-of-order event timestamps"); last = at;
            signature(e);
            String target = e.get("supersedes") == null ? null : digest(e.get("supersedes"));
            if (type.equals("CORRECTION")) {
                require(target != null && c.active.containsKey(target), "Correction must supersede an active prior evidence event");
            } else if (type.equals("REVOCATION")) {
                require(target != null && c.digests.contains(target), "Revocation must point to a prior event");
                revoked=true;
            } else require(target == null, "Unexpected supersession reference");
            if (type.equals("ANALYSIS")) {
                require(e.get("subject_path") == null, "Analysis embeds its canonical report, not a mutable path");
                Map<String,Object> report=obj(e.get("report"));
                require(VERSION(report) && reviewId.equals(report.get("review_id")),"Analysis report identity/contract mismatch");
                require(hash(bytes(report)).equals(e.get("subject_sha256")),"Analysis report bytes/digest mismatch");
                require(c.head().equals(report.get("input_history_head")) && hash(bytes(history(c.events))).equals(report.get("input_history_sha256")),"Analysis does not bind the exact preceding history");
                analysisCurrent=true; reviewer=false; owner=false;
            } else {
                str(e,"subject_path");
                require(e.get("report")==null,"Only analysis events may embed a report");
            }
            if(type.equals("EVIDENCE") || type.equals("CORRECTION")) analysisCurrent=false;
            if(type.equals("REVIEWER_DECISION")) {
                require(analysisCurrent && !reviewer && !owner,"Reviewer decision requires a current analysis and may occur only once"); reviewer=true;
            }
            if(type.equals("OWNER_DECISION")) {
                require(analysisCurrent && reviewer && !owner,"Owner decision requires a current independent-review decision and may occur only once"); owner=true;
            }
            String h = hash(bytes(e));
            if (type.equals("EVIDENCE")) c.active.put(h,e);
            if (type.equals("CORRECTION")) {
                Map<String,Map<String,Object>> replaced = new LinkedHashMap<>();
                c.active.forEach((k,v) -> replaced.put(k.equals(target) ? h : k,k.equals(target) ? e : v));
                c.active.clear(); c.active.putAll(replaced);
            }
            c.events.add(e); c.digests.add(h);
        }
        require(!c.events.isEmpty(), "Missing manifest registration"); return c;
    }
    private static boolean VERSION(Map<String,Object> report) {
        return G6Review.VERSION.equals(report.get("schema")) && Boolean.FALSE.equals(report.get("g6_closed"))
                && Boolean.FALSE.equals(report.get("scale_authorized")) && Boolean.FALSE.equals(report.get("performance_claim_eligible"));
    }
    /** Returns only mathematical signature validity. The key/identity is NEVER independently authenticated. */
    static boolean signature(Map<String,Object> signed) throws IOException {
        if (signed.get("signature") == null) return false;
        Map<String,Object> s = obj(signed.get("signature")); keys(s,"algorithm","public_key","value");
        require("Ed25519".equals(s.get("algorithm")), "Only Ed25519 is implemented");
        Map<String,Object> payload = new TreeMap<>(signed); payload.remove("signature");
        try {
            byte[] keyBytes = Base64.getDecoder().decode(str(s,"public_key"));
            byte[] signatureBytes=Base64.getDecoder().decode(str(s,"value"));
            require(keyBytes.length==44 && signatureBytes.length==64,"Noncanonical Ed25519 key/signature length");
            require(Base64.getEncoder().encodeToString(keyBytes).equals(s.get("public_key")) && Base64.getEncoder().encodeToString(signatureBytes).equals(s.get("value")),"Noncanonical base64 signature/key");
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(keyBytes));
            Signature v = Signature.getInstance("Ed25519"); v.initVerify(key); v.update(bytes(payload));
            require(v.verify(signatureBytes), "Invalid Ed25519 signature"); return true;
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw new IOException("Invalid signature/key encoding", e); }
    }
    static Map<String,Object> event(int sequence, String previous, String type, String id, String subject,
                                    String path, String supersedes, String time) {
        Map<String,Object> e = new TreeMap<>(); e.put("schema","g6-event/v1"); e.put("sequence",sequence);
        e.put("previous_sha256",previous); e.put("type",type); e.put("review_id",id); e.put("subject_sha256",subject);
        e.put("subject_path",path); e.put("supersedes",supersedes); e.put("event_time",time); e.put("report",null); e.put("signature",null); return e;
    }
    static Map<String,Object> history(List<Map<String,Object>> events) { return Map.of("schema","g6-history/v1","events",events); }
    static Map<String,Object> extend(Checked c, Map<String,Object> event, String id) throws IOException {
        List<Map<String,Object>> all = new ArrayList<>(c.events); all.add(event);
        Map<String,Object> h = history(all); check(h,id); return h;
    }
}
