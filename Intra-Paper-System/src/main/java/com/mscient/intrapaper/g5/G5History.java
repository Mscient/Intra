package com.mscient.intrapaper.g5;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.mscient.intrapaper.g5.G5Files.*;

/** Individually immutable, externally anchored events; corrections retain the original bytes. */
public final class G5History {
    public enum State { DRAFT, EVIDENCE_CHECKED, INDEPENDENTLY_REVIEWED, OWNER_DECISION_RECORDED, BLOCKED }
    static final Set<String> KINDS = Set.of("DOSSIER_CREATED","EVIDENCE_ADDED","SUPERSEDED","EVIDENCE_CHECKED","REVIEW","OWNER_DECISION","REVOCATION");
    public record Event(String hash, Map<String,Object> value) { }
    public record Chain(List<Event> events, String head, String dossier, boolean revoked) {
        public Chain { events = List.copyOf(events); }
    }
    private G5History() { }
    static Chain verify(Map<String,byte[]> files, String retainedHead, Instant asOf) throws IOException {
        if (!retainedHead.matches("[0-9a-f]{64}")) throw invalid();
        List<Event> out = new ArrayList<>(); Set<String> ids = new HashSet<>(), corrected = new HashSet<>();
        Map<String,Event> prior = new HashMap<>(); String head = ZERO, dossier = ZERO; boolean revoked = false;
        Instant last = Instant.MIN;
        boolean checked = false, reviewed = false;
        String build = ZERO;
        for (var entry : new TreeMap<>(files).entrySet()) {
            if (!entry.getKey().startsWith("history/")) continue;
            int seq = out.size() + 1;
            if (!entry.getKey().equals("history/" + String.format(Locale.ROOT,"%08d.json",seq))) throw new IOException("G5_HISTORY_ORDER");
            Map<String,Object> e = json(entry.getValue());
            keys(e,"schema","sequence","event_id","kind","previous","dossier_sha256","build_sha256","attachment_sha256","supersedes","at_utc","actor");
            if (!string(e,"schema").equals("INTRA_G5_EVENT_V1") || integer(e,"sequence") != seq || !Arrays.equals(bytes(e),entry.getValue())) throw invalid();
            String kind = string(e,"kind"); if (!KINDS.contains(kind)) throw invalid();
            if (!ids.add(ref(string(e,"event_id"))) || !string(e,"previous").equals(head)) throw new IOException("G5_HISTORY_DUPLICATE_OR_CHAIN");
            String d = digest(string(e,"dossier_sha256")); digest(string(e,"build_sha256")); ref(string(e,"actor"));
            String attachment = string(e,"attachment_sha256"); if (!attachment.equals(ZERO)) digest(attachment);
            Instant at = G5Checker.time(string(e,"at_utc")); if (at.isBefore(last) || at.isAfter(asOf)) throw new IOException("G5_HISTORY_TIME"); last = at;
            String supersedes = string(e,"supersedes");
            if (kind.equals("SUPERSEDED")) {
                Event old = prior.get(supersedes);
                if (old == null || !Set.of("DOSSIER_CREATED","EVIDENCE_ADDED","SUPERSEDED").contains(old.value.get("kind")) || !corrected.add(supersedes)) throw new IOException("G5_INVALID_CORRECTION");
            } else if (!supersedes.equals(ZERO)) throw new IOException("G5_INVALID_CORRECTION");
            if (seq == 1 && !kind.equals("DOSSIER_CREATED") || seq > 1 && kind.equals("DOSSIER_CREATED")) throw new IOException("G5_HISTORY_CREATION");
            if (!Set.of("DOSSIER_CREATED","EVIDENCE_ADDED","SUPERSEDED").contains(kind) && !d.equals(dossier)) throw new IOException("G5_HISTORY_OUTDATED_DIGEST");
            if (Set.of("DOSSIER_CREATED","EVIDENCE_ADDED","SUPERSEDED").contains(kind)) {
                if (!d.equals(dossier) || !e.get("build_sha256").equals(build)) { checked = false; reviewed = false; }
                dossier = d;
                build = (String)e.get("build_sha256");
                // Revocation is latched for this dossier history. A new dossier requires a new history.
            }
            if (!e.get("build_sha256").equals(build)) throw new IOException("G5_HISTORY_BUILD_MISMATCH");
            if (kind.equals("EVIDENCE_CHECKED")) checked = true;
            if (kind.equals("REVIEW")) { if (!checked || revoked) throw new IOException("G5_HISTORY_REVIEW_PREREQUISITE"); reviewed = true; }
            if (kind.equals("OWNER_DECISION") && (!reviewed || revoked)) throw new IOException("G5_HISTORY_OWNER_PREREQUISITE");
            if (kind.equals("REVOCATION")) revoked = true;
            head = hash(entry.getValue()); Event ev = new Event(head,Map.copyOf(e)); prior.put(head,ev); out.add(ev);
        }
        if (!head.equals(retainedHead)) throw new IOException("G5_HISTORY_RETAINED_HEAD_MISMATCH");
        return new Chain(out,head,dossier,revoked);
    }
    /** Caller retains the returned head separately. Never adopt an unverified on-disk head. */
    public static String append(Path history, String retainedHead, Map<String,Object> event, Instant asOf) throws IOException {
        safe(history,true); Path lock = history.resolve(".writer-lock"); boolean owned = false;
        try (var ignored = Files.newByteChannel(lock,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            owned = true;
            SortedMap<String,byte[]> files = new TreeMap<>();
            for (var e : snapshot(history).entrySet()) {
                if (e.getKey().equals(".writer-lock")) continue;
                files.put("history/"+e.getKey(),e.getValue());
            }
            Chain current = verify(files,retainedHead,asOf);
            int sequence = current.events.size()+1;
            String name = "history/"+String.format(Locale.ROOT,"%08d.json",sequence);
            byte[] b = bytes(event); files.put(name,b);
            Chain next = verify(files,hash(b),asOf);
            publish(b,history.resolve(name.substring(8)));
            return next.head;
        } finally { if (owned) Files.deleteIfExists(lock); }
    }
    /** Pure state reconstruction. No persisted state can resume or enable any behavior. */
    static State state(boolean checked, boolean review, boolean owner, boolean invalidated) {
        if (invalidated) return State.BLOCKED;
        if (!checked) return State.DRAFT;
        if (!review) return State.EVIDENCE_CHECKED;
        return owner ? State.OWNER_DECISION_RECORDED : State.INDEPENDENTLY_REVIEWED;
    }
}
