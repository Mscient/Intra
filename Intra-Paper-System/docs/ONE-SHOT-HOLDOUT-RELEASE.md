# One-shot holdout release controller

`PortfolioHoldoutReleaseController` is a separate custody state machine. It freezes a registered portfolio trial family, verifies a custodian's signed authorization, and permits one invocation of a reviewed holdout evaluation adapter. The development/validation evaluator continues to exclude holdout financial values. This increment supplies software controls and synthetic implementation evidence; **independent custody review remains open**. It grants no profitability, blindness, research-gate, paper-trading, or live-trading claim.

## States and transition bindings

| Current state | Operation | Required evidence | Result |
| --- | --- | --- | --- |
| `UNFROZEN` | `freeze` | Complete registered family, valid split and protocol, exact public input hashes | `FROZEN`, with a unique random freeze nonce |
| `FROZEN` | `authorize` | Unchanged inputs; unexpired Ed25519 authorization from the pinned custodian, covering this freeze record and custody-review hash | `AUTHORIZED` |
| `AUTHORIZED` | `evaluate` | Unchanged inputs; valid authorization at start; no existing result file | Force `EVALUATING` and its signed head **before invoking the adapter** |
| `EVALUATING` | Adapter finishes | Inputs unchanged at completion; nonempty result durably written to a new file and hashed | `CONSUMED / SUCCESS` |
| `EVALUATING` | Adapter throws or result publication fails | The original binding and evaluation ID | `CONSUMED / FAILED`; no second invocation |
| `EVALUATING` after interruption | `consumeInterrupted` | Original binding and evaluation ID | `CONSUMED / INTERRUPTED`; no invocation or resumption |
| `CONSUMED` | `readResult` | Successful consumption and matching saved result hash | Deliver the existing bytes; state stays consumed |

Every accepted transition contains the same six SHA-256 hashes: exact catalog bytes, exact code-artifact bytes, registered dataset fingerprint, exact split CSV bytes, exact preregistered protocol bytes, and exact registration-ledger bytes. The stable `familyId` covers the dataset and split; the catalog must include every registration in that family, across strategy versions and variants. Catalog hashes also pin candidate parameters, run IDs, manifest hashes, scope, and normalization capital. Unrelated new registrations change the frozen ledger hash too.

The authorization signature covers its ID, the unique freeze record hash, all six input hashes, a custody-review document hash, and expiry. History verification checks that signature again and enforces transition order, bindings, evidence links, and evaluation ID. Authorization and journal signing keys must differ. Both trusted public keys must come from the custodian's deployment configuration, outside the submitted journal.

No second freeze, second authorization, or second evaluation is permitted in that store after its corresponding transition. A denied request before `EVALUATING` may be corrected and retried with the original inputs and still-valid authorization. Once `EVALUATING` is durable, even a retry using the same ID is denied. Delivery retries use `readResult`, never `evaluate`. An expired accepted authorization cannot be replaced with a fresh release token.

## Public freeze inputs and protocol

`HoldoutReleaseInputs.capture()` validates the existing strict catalog, registration chain, complete family, chronological split, and protocol. It does **not** open replay artifacts or holdout financial files. It rechecks public file hashes during capture. The dataset fingerprint is the existing registered portfolio dataset identity, not a fresh read of secret holdout data.

The UTF-8 protocol JSON has exactly these keys:

```json
{
  "schema": "INTRA_HOLDOUT_PROTOCOL_V1",
  "selected_trial_ids": ["previously-registered-trial-id"],
  "evaluation_spec": "Frozen metrics, transformations, costs, selection rule, data identity, reporting rule, and rejection criteria; or exact references to them.",
  "failure_policy": "CONSUME_ON_START_NO_RETRY"
}
```

Selected IDs must be nonempty, unique, and members of the complete frozen family. The controller checks this schema and hashes the specification; it does not interpret the natural-language evaluation rule. The independent reviewer must establish that the selection and entire protocol were fixed before release. Local file hashes and timestamps cannot establish preregistration timing by themselves.

## Authorization control and independent review

The **independent authorization custodian** controls the Ed25519 authorization private key, the canonical family registry, and the decision to release. The evaluation service holds a different journal signing key, pinned custodian public key, and the custody store. The research operator supplies public freeze inputs and requests access. The role names do not establish separation: a real independent reviewer must inspect who can read data, sign tokens, change deployment, replace stores, or restore backups.

Before signing, the custodian must review the complete signed freeze history and head with the trusted journal public key, the exact catalog/code/split/protocol/registration bytes, actual data-byte commitments, selected candidates, prior-access record, and custody deployment. A receipt JSON alone is insufficient to authenticate the freeze. The signed review hash records a custodian attestation; the software cannot judge the review's independence or sufficiency. Status therefore always emits `independent_custody_review_verified=false`, `performance_claim_eligible=false`, and `blindness_claim_eligible=false`, even after a valid signature and successful evaluation.

The custodian must maintain **one canonical store per dataset/split family** and independently retain its current signed head. Changing candidates does not create permission for another look at the same holdout. A unique freeze nonce prevents token replay to another freeze; it cannot prevent a custodian from issuing another token for a new store. No such registry or external custody deployment is provided by this increment.

## Integration API

Build with JDK 21. The API is deliberately separate from `PortfolioTrialEvaluationCli`; the existing CLI never becomes a holdout release command.

Inside the reviewed evaluation service:

```java
var inputs = new HoldoutReleaseInputs(trialLedger, catalogJson, reviewedJar,
        splitCsv, preregisteredProtocolJson);
var controller = new PortfolioHoldoutReleaseController(canonicalHistoryPath,
        independentlyPinnedCustodianPublicKey, serviceJournalKeyPair);
var frozen = controller.freeze(inputs, "freeze-request-1");
// Preserve frozen.toJson(), the signed history/head, and all public inputs for custodian review.
```

In the custodian's separate signing environment, **after independent review**:

```java
var authorization = PortfolioHoldoutReleaseController.Authorization.sign(
        reviewedFrozenReceipt, "authorization-1", custodyReviewSha256,
        releaseExpiryUtc, custodianPrivateKey);
// Deliver authorization.encode() through the approved custody channel.
```

Back in the reviewed evaluation service:

```java
controller.authorize(inputs,
        PortfolioHoldoutReleaseController.Authorization.decode(receivedToken));
var consumed = controller.evaluate(inputs, "evaluation-1", frozenBinding -> {
    // Only here may the custody adapter open holdout values.
    // Verify actual data bytes and candidate artifacts against the frozen commitments.
    // Execute the frozen selection and evaluation specification exactly once.
    return reviewedCustodyAdapter.evaluateOnce(frozenBinding);
});
// Metadata only: consumed.toJson().
// Delivery retries retrieve the same verified bytes without evaluating again:
byte[] savedResult = controller.readResult("delivery-1");
```

`HoldoutEvaluator` is the service integration boundary; this increment does not ship a new statistical holdout scorer or data vault. Independent review must confirm that the deployed callback and dependencies match the reviewed code hash, verify the registered dataset against actual secret data bytes, enforce selected candidates and metrics, and restrict raw/result access. A callback can otherwise load arbitrary data or evaluate multiple times internally. The controller checks public inputs before and after invocation, but cannot prove the behavior of arbitrary code inside it. No production custodian keys or authorization tokens are created by tests or examples.

## Storage, access logging, and failure handling

For a history path `release.history`, the store contains:

- `release.history`: Ed25519-signed, hash-chained accepted transitions, containing hashes and IDs only.
- `release.history.head`: signed current sequence/hash anchor, atomically replaced after each forced history append.
- `release.history.access` and `.access.head`: a separately signed attempt/decision chain and head. Every normal API attempt is logged before reading the state or invoking the adapter; denial records contain fixed reason codes. Invalid request IDs are replaced by a hash-derived ID. Accepted decisions reference the state head; an additional forced `STARTED` access record anchors `EVALUATING` before the callback. These references detect deletion or rollback of both state history and its matching head while the audit remains intact.
- `release.history.result`: create-new, forced result bytes, available through the API only after successful consumption. Results never enter the history, access log, or status JSON.

The service takes the audit lock before the history lock and retains both during evaluation. Competing writers fail closed or observe consumption after the lock is released. A corrupt main history is not extended; its rejected access attempt is recorded in the separate intact audit chain. If the audit is corrupt, full, unwritable, or already locked by another service instance in the same JVM, the controller denies access and cannot promise a durable additional attempt record. Preserve that service error through independently controlled operational logging. Callback error messages, stack traces, paths, and values are never copied into API denial messages or journals.

The signed heads detect local truncation, missing heads, stale sequence/hash, and partial records. Signed chains detect edits even if ordinary hashes are recomputed. Verification also rejects a correctly signed but impossible state transition. Missing/stale heads are never reconstructed from history. Uncertainty between append and head replacement strands the store closed; a callback cannot start unless the state/history head and the separate audit start/head writes finish. An intact `EVALUATING` state after process interruption may only be closed as interrupted. A partially written/uncommitted result remains sealed through the API. NTFS power-loss, disk-full, hardware durability, and external log retention require deployment-specific review; the synthetic termination test is not a power-loss experiment.

## What this system cannot enforce

An operator who can read raw holdout files, read result files directly, obtain the custodian key, obtain the journal key together with writable histories, replace reviewed code, call an evaluator outside this API, choose another store, or restore a matching history/head/audit backup can bypass these controls. The two journals cross-check their state commitments, but cannot detect rollback of **both journals and both matching signed heads together**. Independently retained fresh heads, an independently maintained family registry, secret data custody, restricted service identity, and key separation must address those gaps. The controller cannot prove every outside access attempt was logged, independently authenticate data sources, establish prior blindness, or undo information already seen.

Full-split replay artifacts already contain operator-readable holdout outcomes. Using this controller after such artifacts have been generated or inspected does not make them blind. Development/validation prefix artifacts avoid carrying holdout rows, but the reviewer still needs to reconcile their distinct dataset identity with the preregistered future holdout data and protocol. No provenance label silently changes a consumed partition into a new holdout.

## Synthetic acceptance

Run `test-holdout-release.ps1` for the controller's production dependency closure and synthetic tests, or `test-portfolio-trial-evaluation.ps1` for all production sources plus the existing evaluator/replay/verifier regressions and these release tests. The latter is already part of the Windows offline CI suite; no hosted CI pass is claimed here.

Local JDK 21 validation on 2026-11-07 passed **158 synthetic holdout checks**, **161 existing evaluator checks**, and the existing portfolio replay, execution-cost, and artifact-verifier regressions. This verifies implementation behavior, not independent custody.

Coverage includes early evaluation/result access; malformed IDs and safe logging; complete-family freeze; missing protocol decisions; all input hashes and changed candidates; signed review/token tampering and expiry; authorization retries; preflight retries; repeated release/evaluation; cached-result delivery retries; result tampering; controller restart; callback failure; in-flight input changes; simulated abrupt termination and terminal closure; uncertain staged-head writes; concurrent access; edited, rehashed, truncated, partial, and semantically invalid signed histories; head/audit tampering; state-and-head rollback/deletion against an intact audit (including interrupted evaluation); and verification-key substitution. These checks establish implementation behavior under synthetic inputs only. **No profitability or blindness claim is supported without independent custody review.**
