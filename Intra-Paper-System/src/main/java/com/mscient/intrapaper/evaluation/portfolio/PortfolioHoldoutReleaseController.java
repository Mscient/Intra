package com.mscient.intrapaper.evaluation.portfolio;

import com.mscient.intrapaper.provenance.RunConfig;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static com.mscient.intrapaper.evaluation.portfolio.SignedHoldoutJournal.NONE;

/** One durable authorization and one callback invocation per frozen family in a custody store. */
public final class PortfolioHoldoutReleaseController {
    public enum State { UNFROZEN, FROZEN, AUTHORIZED, EVALUATING, CONSUMED }
    private enum Operation { FREEZE, AUTHORIZE, EVALUATE, CONSUME_INTERRUPTED, STATUS, READ_RESULT }
    private static final String HISTORY = "INTRA_HOLDOUT_RELEASE_HISTORY_V1";
    private static final String ACCESS = "INTRA_HOLDOUT_ACCESS_HISTORY_V1";
    private static final String AUTHORIZATION = "INTRA_HOLDOUT_AUTHORIZATION_V1";
    private static final int MAX_RESULT_BYTES = 64 * 1024 * 1024;

    /** Contains no financial payload. A signed review reference is an attestation, not proof of independence. */
    public record Receipt(State state, HoldoutReleaseInputs.Binding binding, String freezeRecordSha256,
            String finalRecordSha256, String evaluationId, String outcome, String resultSha256) {
        public String toJson() {
            return PortfolioEvaluationJson.write(PortfolioEvaluationJson.object(
                    "schema", "INTRA_HOLDOUT_RELEASE_STATUS_V1", "state", state.name(),
                    "family_id", binding == null ? null : binding.familyId(),
                    "binding_sha256", binding == null ? null : binding.sha256(),
                    "catalog_sha256", binding == null ? null : binding.catalogSha256(),
                    "code_sha256", binding == null ? null : binding.codeSha256(),
                    "dataset_sha256", binding == null ? null : binding.datasetSha256(),
                    "split_sha256", binding == null ? null : binding.splitSha256(),
                    "protocol_sha256", binding == null ? null : binding.protocolSha256(),
                    "registration_ledger_sha256", binding == null ? null : binding.registrationLedgerSha256(),
                    "freeze_record_sha256", freezeRecordSha256, "final_record_sha256", finalRecordSha256,
                    "evaluation_id", evaluationId, "outcome", outcome, "result_sha256", resultSha256,
                    "performance_claim_eligible", false, "blindness_claim_eligible", false,
                    "independent_custody_review_verified", false));
        }
    }

    public record Authorization(String authorizationId, String freezeRecordSha256,
            HoldoutReleaseInputs.Binding binding, String custodyReviewSha256, Instant expiresAtUtc,
            String signatureBase64) {
        public Authorization {
            HoldoutReleaseInputs.requireId(authorizationId);
            HoldoutReleaseInputs.requireHash(freezeRecordSha256);
            HoldoutReleaseInputs.requireHash(custodyReviewSha256);
            java.util.Objects.requireNonNull(binding);
            java.util.Objects.requireNonNull(expiresAtUtc);
            java.util.Objects.requireNonNull(signatureBase64);
        }
        private String payload() {
            return String.join("\t", AUTHORIZATION, authorizationId, freezeRecordSha256,
                    binding.encode(), custodyReviewSha256, expiresAtUtc.toString());
        }
        public String encode() { return payload() + "\t" + signatureBase64; }
        public static Authorization decode(String text) throws IOException {
            String[] fields = text.split("\t", -1);
            if (fields.length != 12 || !AUTHORIZATION.equals(fields[0])) throw new IOException("Invalid authorization");
            try {
                var token = new Authorization(fields[1], fields[2], HoldoutReleaseInputs.Binding.decode(fields, 3),
                        fields[9], Instant.parse(fields[10]), fields[11]);
                if (!token.encode().equals(text)) throw new IOException("Noncanonical authorization");
                return token;
            } catch (IllegalArgumentException invalid) { throw new IOException("Invalid authorization", invalid); }
        }
        /** Run by the independent authorization custodian after reviewing the exact freeze receipt. */
        public static Authorization sign(Receipt freeze, String id, String custodyReviewSha256,
                Instant expiry, PrivateKey custodianKey) throws IOException {
            if (freeze.state() != State.FROZEN) throw new IOException("Authorization requires a frozen receipt");
            var unsigned = new Authorization(id, freeze.freezeRecordSha256(), freeze.binding(),
                    custodyReviewSha256, expiry, "");
            return new Authorization(id, freeze.freezeRecordSha256(), freeze.binding(), custodyReviewSha256,
                    expiry, SignedHoldoutJournal.sign(custodianKey, unsigned.payload()));
        }
    }

    /** The reviewed custody adapter must verify frozen data bytes and execute the frozen protocol once. */
    @FunctionalInterface public interface HoldoutEvaluator {
        byte[] evaluate(HoldoutReleaseInputs.Binding frozen) throws Exception;
    }

    private final Path historyPath;
    private final Path accessPath;
    private final Path resultPath;
    private final PublicKey authorizationKey;
    private final KeyPair historyKey;
    private final Clock clock;

    public PortfolioHoldoutReleaseController(Path historyPath, PublicKey authorizationKey,
            KeyPair historyKey) throws IOException {
        this(historyPath, authorizationKey, historyKey, Clock.systemUTC());
    }

    PortfolioHoldoutReleaseController(Path historyPath, PublicKey authorizationKey, KeyPair historyKey,
            Clock clock) throws IOException {
        this.historyPath = historyPath.toAbsolutePath().normalize();
        this.accessPath = this.historyPath.resolveSibling(this.historyPath.getFileName() + ".access");
        this.resultPath = this.historyPath.resolveSibling(this.historyPath.getFileName() + ".result");
        this.authorizationKey = java.util.Objects.requireNonNull(authorizationKey);
        this.historyKey = java.util.Objects.requireNonNull(historyKey);
        this.clock = java.util.Objects.requireNonNull(clock);
        String challenge = "INTRA_HOLDOUT_KEY_CHECK_V1";
        if (java.util.Arrays.equals(authorizationKey.getEncoded(), historyKey.getPublic().getEncoded())
                || !SignedHoldoutJournal.verify(historyKey.getPublic(), challenge,
                        SignedHoldoutJournal.sign(historyKey.getPrivate(), challenge)))
            throw new IOException("Custody requires distinct authorization and history signing keys");
    }

    public Receipt freeze(HoldoutReleaseInputs inputs, String requestId) throws IOException {
        return operate(Operation.FREEZE, requestId, inputs, (session, binding) -> {
            require(session.receipt.state() == State.UNFROZEN, "ALREADY_FROZEN");
            byte[] nonce = new byte[32]; new java.security.SecureRandom().nextBytes(nonce);
            session.transition("FREEZE", binding, requestId, keyHash(authorizationKey), NONE,
                    java.util.HexFormat.of().formatHex(nonce));
            return session.receipt;
        });
    }

    public Receipt authorize(HoldoutReleaseInputs inputs, Authorization token) throws IOException {
        return operate(Operation.AUTHORIZE, token == null ? "invalid-authorization" : token.authorizationId(), inputs, (session, binding) -> {
            require(token != null, "INVALID_AUTHORIZATION");
            require(session.receipt.state() == State.FROZEN, "AUTHORIZATION_STATE");
            require(binding.equals(session.receipt.binding()), "CHANGED_INPUTS");
            verifyAuthorization(token, session.receipt, clock.instant());
            session.transition("AUTHORIZE", binding, token.authorizationId(),
                    HoldoutReleaseInputs.digest(token.encode()), NONE, b64(token.encode()));
            return session.receipt;
        });
    }

    /** A retry of this method is always rejected once EVALUATING has been durably recorded. */
    public Receipt evaluate(HoldoutReleaseInputs inputs, String evaluationId, HoldoutEvaluator evaluator) throws IOException {
        return operate(Operation.EVALUATE, evaluationId, inputs, (session, binding) -> {
            require(evaluator != null, "INVALID_EVALUATOR");
            require(session.receipt.state() == State.AUTHORIZED, "EVALUATION_STATE");
            require(binding.equals(session.receipt.binding()), "CHANGED_INPUTS");
            verifyAuthorization(session.authorization, session.receipt, clock.instant());
            require(!Files.exists(resultPath, java.nio.file.LinkOption.NOFOLLOW_LINKS), "RESULT_ALREADY_EXISTS");
            // force(history), then force(signed head), before the adapter can read any holdout value.
            session.transition("EVALUATE", binding, evaluationId,
                    HoldoutReleaseInputs.digest(session.authorization.encode()), NONE, NONE);
            session.recordEvaluationStart(evaluationId);
            String resultHash;
            try {
                byte[] result = evaluator.evaluate(binding);
                if (result == null || result.length == 0 || result.length > MAX_RESULT_BYTES)
                    throw new IOException("Invalid evaluation result");
                result = result.clone();
                require(binding.equals(inputs.capture()), "CHANGED_INPUTS");
                resultHash = RunConfig.sha256(result);
                try (FileChannel output = FileChannel.open(resultPath, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
                    SignedHoldoutJournal.write(output, result);
                    output.force(true);
                }
            } catch (Exception failure) {
                // Never journal callback messages, paths, stack traces, or partially observed values.
                session.transition("CONSUME", binding, evaluationId, NONE, "FAILED", NONE);
                throw new Denied("EVALUATION_FAILED_CONSUMED");
            }
            session.transition("CONSUME", binding, evaluationId, resultHash, "SUCCESS", NONE);
            return session.receipt;
        });
    }

    /** A crashed/terminated evaluation can only be closed as consumed; it cannot be resumed or rerun. */
    public Receipt consumeInterrupted(HoldoutReleaseInputs inputs, String evaluationId) throws IOException {
        return operate(Operation.CONSUME_INTERRUPTED, evaluationId, inputs, (session, binding) -> {
            require(session.receipt.state() == State.EVALUATING, "INTERRUPTED_STATE");
            require(binding.equals(session.receipt.binding()), "CHANGED_INPUTS");
            require(evaluationId.equals(session.receipt.evaluationId()), "EVALUATION_ID");
            session.transition("CONSUME", binding, evaluationId, NONE, "INTERRUPTED", NONE);
            return session.receipt;
        });
    }

    public Receipt status(String requestId) throws IOException {
        return operate(Operation.STATUS, requestId, null, (session, ignored) -> session.receipt);
    }

    /** Delivery retries read the same hash-verified result; they never invoke the evaluator. */
    public byte[] readResult(String requestId) throws IOException {
        return operate(Operation.READ_RESULT, requestId, null, (session, ignored) -> {
            require(session.receipt.state() == State.CONSUMED && session.receipt.outcome().equals("SUCCESS"), "RESULT_SEALED");
            require(Files.size(resultPath) <= MAX_RESULT_BYTES, "RESULT_SIZE");
            byte[] result = PortfolioTrialIdentity.readRegular(resultPath);
            require(RunConfig.sha256(result).equals(session.receipt.resultSha256()), "RESULT_TAMPERED");
            return result;
        });
    }

    @FunctionalInterface private interface Work<T> { T run(Session session, HoldoutReleaseInputs.Binding binding) throws IOException; }

    private synchronized <T> T operate(Operation operation, String id, HoldoutReleaseInputs inputs, Work<T> work) throws IOException {
        boolean validId;
        try { HoldoutReleaseInputs.requireId(id); validId = true; }
        catch (IllegalArgumentException invalid) { validId = false; }
        String auditId = validId ? id : "invalid-" + HoldoutReleaseInputs.digest(String.valueOf(id)).substring(0, 16);
        // Fixed lock order across processes. If the audit chain cannot be trusted/written, deny access.
        try (FileChannel access = SignedHoldoutJournal.open(accessPath); FileLock auditLock = access.lock()) {
            List<SignedHoldoutJournal.Row> audit = new ArrayList<>(SignedHoldoutJournal.read(access, ACCESS, historyKey.getPublic()));
            checkHead(accessPath, ACCESS, audit);
            for (var row : audit) validateAccess(row.payload());
            append(access, accessPath, ACCESS, audit, operation + "\t" + auditId + "\tATTEMPT\t" + NONE + "\t" + NONE + "\t" + NONE);
            String attemptedBinding = NONE;
            try (FileChannel history = SignedHoldoutJournal.open(historyPath); FileLock stateLock = history.lock()) {
                require(validId, "INVALID_REQUEST_ID");
                var rows = new ArrayList<>(SignedHoldoutJournal.read(history, HISTORY, historyKey.getPublic()));
                checkHead(historyPath, HISTORY, rows);
                checkAuditAnchors(audit, rows);
                Session session = new Session(history, rows, access, audit);
                HoldoutReleaseInputs.Binding binding = inputs == null ? null : inputs.capture();
                attemptedBinding = binding == null ? NONE : binding.sha256();
                T result = work.run(session, binding);
                append(access, accessPath, ACCESS, audit, operation + "\t" + auditId + "\tALLOWED\t" + attemptedBinding + "\t" + NONE
                        + "\t" + session.receipt.finalRecordSha256());
                return result;
            } catch (IOException | RuntimeException invalid) {
                String reason = invalid instanceof Denied denied ? denied.reason : "INVALID_OR_UNTRUSTED_INPUT";
                append(access, accessPath, ACCESS, audit, operation + "\t" + auditId + "\tDENIED\t" + attemptedBinding + "\t" + reason + "\t" + NONE);
                throw new IOException("Holdout access denied: " + reason);
            }
        } catch (java.nio.channels.OverlappingFileLockException busy) {
            throw new IOException("Holdout custody store is busy");
        }
    }

    private final class Session {
        final FileChannel channel;
        final List<SignedHoldoutJournal.Row> rows;
        final FileChannel access;
        final List<SignedHoldoutJournal.Row> audit;
        Receipt receipt = new Receipt(State.UNFROZEN, null, NONE, SignedHoldoutJournal.GENESIS, NONE, NONE, NONE);
        Authorization authorization;
        Session(FileChannel channel, List<SignedHoldoutJournal.Row> rows, FileChannel access,
                List<SignedHoldoutJournal.Row> audit) throws IOException {
            this.channel = channel;
            this.rows = rows;
            this.access = access;
            this.audit = audit;
            for (var row : rows) accept(row);
        }
        void recordEvaluationStart(String evaluationId) throws IOException {
            append(access, accessPath, ACCESS, audit, Operation.EVALUATE + "\t" + evaluationId + "\tSTARTED\t"
                    + receipt.binding().sha256() + "\t" + NONE + "\t" + receipt.finalRecordSha256());
        }
        void transition(String action, HoldoutReleaseInputs.Binding binding, String id, String evidence,
                String outcome, String token) throws IOException {
            String payload = String.join("\t", action, binding.encode(), id, evidence, outcome, token);
            var row = append(channel, historyPath, HISTORY, rows, payload);
            accept(row);
        }
        void accept(SignedHoldoutJournal.Row row) throws IOException {
            String[] fields = row.payload().split("\t", -1);
            if (fields.length != 11) throw new IOException("Invalid holdout transition");
            try {
                var binding = HoldoutReleaseInputs.Binding.decode(fields, 1);
                String id = fields[7], evidence = fields[8], outcome = fields[9], token = fields[10];
                HoldoutReleaseInputs.requireId(id);
                if (receipt.binding() != null) require(binding.equals(receipt.binding()), "HISTORY_BINDING_CHANGED");
                String freezeHash = receipt.freezeRecordSha256(), evaluationId = receipt.evaluationId(), result = NONE;
                State next;
                switch (fields[0]) {
                    case "FREEZE" -> {
                        require(receipt.state() == State.UNFROZEN && evidence.equals(keyHash(authorizationKey))
                                && outcome.equals(NONE), "HISTORY_FREEZE");
                        HoldoutReleaseInputs.requireHash(token);
                        freezeHash = row.hash(); next = State.FROZEN;
                    }
                    case "AUTHORIZE" -> {
                        require(receipt.state() == State.FROZEN && outcome.equals(NONE), "HISTORY_AUTHORIZE");
                        authorization = Authorization.decode(unb64(token));
                        verifyAuthorization(authorization, receipt, row.time());
                        require(id.equals(authorization.authorizationId()) && evidence.equals(
                                HoldoutReleaseInputs.digest(authorization.encode())), "HISTORY_AUTHORIZATION_TOKEN");
                        next = State.AUTHORIZED;
                    }
                    case "EVALUATE" -> {
                        require(receipt.state() == State.AUTHORIZED && outcome.equals(NONE) && token.equals(NONE)
                                && evidence.equals(HoldoutReleaseInputs.digest(authorization.encode())), "HISTORY_EVALUATE");
                        verifyAuthorization(authorization, receipt, row.time());
                        evaluationId = id; next = State.EVALUATING;
                    }
                    case "CONSUME" -> {
                        require(receipt.state() == State.EVALUATING && id.equals(evaluationId)
                                && token.equals(NONE), "HISTORY_CONSUME");
                        if (outcome.equals("SUCCESS")) { HoldoutReleaseInputs.requireHash(evidence); result = evidence; }
                        else require((outcome.equals("FAILED") || outcome.equals("INTERRUPTED")) && evidence.equals(NONE), "HISTORY_OUTCOME");
                        next = State.CONSUMED;
                    }
                    default -> throw new IOException("Unknown holdout transition");
                }
                receipt = new Receipt(next, binding, freezeHash, row.hash(), evaluationId, outcome, result);
            } catch (IllegalArgumentException invalid) { throw new IOException("Invalid holdout transition", invalid); }
        }
    }

    private void verifyAuthorization(Authorization token, Receipt receipt, Instant time) throws IOException {
        require(token != null && token.binding().equals(receipt.binding())
                && token.freezeRecordSha256().equals(receipt.freezeRecordSha256()), "AUTHORIZATION_BINDING");
        require(SignedHoldoutJournal.verify(authorizationKey, token.payload(), token.signatureBase64()), "AUTHORIZATION_SIGNATURE");
        require(time.isBefore(token.expiresAtUtc()), "AUTHORIZATION_EXPIRED");
    }

    private SignedHoldoutJournal.Row append(FileChannel channel, Path path, String domain,
            List<SignedHoldoutJournal.Row> rows, String payload) throws IOException {
        var row = SignedHoldoutJournal.append(channel, domain, rows, payload, historyKey.getPrivate(), clock.instant());
        writeHead(path, domain, row);
        rows.add(row);
        return row;
    }

    private Path headPath(Path path) { return path.resolveSibling(path.getFileName() + ".head"); }
    private void checkHead(Path path, String domain, List<SignedHoldoutJournal.Row> rows) throws IOException {
        Path head = headPath(path);
        if (rows.isEmpty()) {
            require(!Files.exists(head, java.nio.file.LinkOption.NOFOLLOW_LINKS), "HISTORY_TRUNCATED");
            return;
        }
        require(Files.size(head) <= 2048, "HISTORY_HEAD_SIZE");
        String text = PortfolioTrialIdentity.utf8(PortfolioTrialIdentity.readRegular(head));
        var last = rows.getLast();
        String payload = domain + "\t" + last.sequence() + "\t" + last.hash();
        String[] fields = text.split("\t", -1);
        require(fields.length == 4 && text.equals(payload + "\t" + fields[3])
                && SignedHoldoutJournal.verify(historyKey.getPublic(), payload, fields[3]), "HISTORY_HEAD_MISMATCH");
    }
    private void writeHead(Path path, String domain, SignedHoldoutJournal.Row row) throws IOException {
        Path target = headPath(path);
        Path staged = target.resolveSibling(target.getFileName() + ".pending");
        String payload = domain + "\t" + row.sequence() + "\t" + row.hash();
        byte[] bytes = (payload + "\t" + SignedHoldoutJournal.sign(historyKey.getPrivate(), payload)).getBytes(StandardCharsets.UTF_8);
        // An uncertain/partial write fails closed. Never reconstruct a missing/stale head from history.
        try (FileChannel output = FileChannel.open(staged, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            SignedHoldoutJournal.write(output, bytes); output.force(true);
        }
        Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void validateAccess(String payload) throws IOException {
        String[] fields = payload.split("\t", -1);
        try {
            require(fields.length == 6, "INVALID_ACCESS_LOG");
            Operation.valueOf(fields[0]); HoldoutReleaseInputs.requireId(fields[1]);
            require(SetHolder.PHASES.contains(fields[2]), "INVALID_ACCESS_LOG");
            if (!fields[3].equals(NONE)) HoldoutReleaseInputs.requireHash(fields[3]);
            require(fields[4].matches("[A-Z_]+"), "INVALID_ACCESS_LOG");
            if (!fields[5].equals(NONE)) HoldoutReleaseInputs.requireHash(fields[5]);
            require((fields[2].equals("ALLOWED") || fields[2].equals("STARTED")) != fields[5].equals(NONE), "INVALID_ACCESS_LOG");
            if (fields[2].equals("STARTED")) require(fields[0].equals("EVALUATE"), "INVALID_ACCESS_LOG");
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid access log", invalid); }
    }
    private static final class SetHolder { static final java.util.Set<String> PHASES = java.util.Set.of("ATTEMPT", "STARTED", "ALLOWED", "DENIED"); }
    private static void checkAuditAnchors(List<SignedHoldoutJournal.Row> audit,
            List<SignedHoldoutJournal.Row> history) throws IOException {
        var hashes = new java.util.HashSet<String>();
        hashes.add(SignedHoldoutJournal.GENESIS);
        history.forEach(row -> hashes.add(row.hash()));
        for (var row : audit) {
            String[] fields = row.payload().split("\t", -1);
            if (fields[2].equals("ALLOWED") || fields[2].equals("STARTED"))
                require(hashes.contains(fields[5]), "HISTORY_ROLLBACK");
        }
    }
    private static String keyHash(PublicKey key) { return RunConfig.sha256(key.getEncoded()); }
    private static String b64(String text) { return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    private static String unb64(String text) throws IOException {
        try {
            byte[] bytes = Base64.getDecoder().decode(text);
            if (!Base64.getEncoder().encodeToString(bytes).equals(text)) throw new IOException("Invalid token encoding");
            return PortfolioTrialIdentity.utf8(bytes);
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid token encoding", invalid); }
    }
    private static void require(boolean condition, String reason) throws IOException { if (!condition) throw new Denied(reason); }
    private static final class Denied extends IOException {
        final String reason;
        Denied(String reason) { super(reason); this.reason = reason; }
    }
}
