# DSR/PBO method audit and implementation boundary

**Reviewed:** 2026-11-04 (Asia/Kolkata)  
**Input:** user-supplied research note pasted in this session. Its recommendations are treated as research input, not as implementation requirements by themselves.  
**Scope:** compare the note with the cited primary papers and the current offline `statistical-evaluate` command. This is a method review, not strategy evidence.

## Primary-source checks

- Bailey and López de Prado's 2014 DSR paper defines DSR as a probabilistic Sharpe calculation evaluated at a selection-adjusted hurdle. The expected-maximum hurdle uses both the number of independent trials and the dispersion of Sharpe estimates across the trial family (Eq. 1–2). The paper's Appendix A.3 discusses estimating an implied independent-trial count from average trial correlation. The current evaluator instead uses the raw number of preregistered variants and explicitly does not adjust it for correlation. [Primary paper, Eq. 1–2 and Appendix A.3](https://www.davidhbailey.com/dhbpapers/deflated-sharpe.pdf)
- The DSR paper's PSR term uses the selected return series' sample length, skewness, and kurtosis. The current implementation uses daily session returns and the observed family-wide sample standard deviation of daily Sharpe ratios for the selection hurdle. It applies an independent-normal expected-maximum approximation. These estimates do not make the session returns independent; the implementation assumes that for its Sharpe interpretation and annualization.
- Bailey et al.'s 2015 revised PBO paper, Algorithm 2.3, requires a synchronous T-by-N performance matrix, an even number of row slices, all half-slice combinations, best in-sample selection, and out-of-sample rank/logit calculation. [Primary paper, Algorithm 2.3](https://www.davidhbailey.com/dhbpapers/backtest-prob.pdf)
- The current evaluator's `chronological-block CSCV` uses contiguous session blocks over DEVELOPMENT plus VALIDATION, with all enumerated half-block combinations. It is an explicitly scoped implementation of the CSCV selection diagnostic. It does not implement purging/embargo, and CSCV is not a forward-only estimate: a complement can contain observations later than an in-sample block. The report therefore excludes HOLDOUT and keeps performance eligibility false.

## Review of the supplied note

The note correctly emphasizes that (a) raw trial count and Sharpe dispersion both matter to DSR, (b) trial dependence makes the independent-trial count consequential, (c) all trial columns need a common observation index for CSCV, (d) a holdout is vulnerable if repeatedly viewed, and (e) CSCV does not establish that a simulator, costs, or data are correct. Its proposal to record variants whose outcomes were seen, including abandoned or mechanically failed trials where applicable, is a conservative research-ledger recommendation; the papers do not fully prescribe this project's exact event/accounting rule.

The note's DSR formulas are labelled recalled/unverified in the supplied text. The DSR primary paper has now been checked directly for the structure and the role of trial dispersion and independent N. The note's detailed claims about AFML purging/embargo chapters, effective sample size under overlapping labels, residual diagnostics, and a proposed sealed-vault protocol remain recommendations or open method choices; they have not been adopted as verified requirements here.

## Existing implementation and limits

The offline command binds each supplied run to trial registration, strategy-card, dataset, split, parameter, and ledger-chain identities. It requires the selected registered family to share dataset/split identity, common analysis capital, and a complete common session sequence. It calculates DEVELOPMENT/VALIDATION session returns and descriptive Sharpe values, DSR approximation, and CSCV PBO. HOLDOUT is checked for coverage and boundary integrity but its P&L is not parsed into the statistical output. No one-shot vault exists: ordinary run artifacts contain holdout results, so access must remain controlled outside the command.

The following limitations remain open and block any performance claim:

- effective independent trial count or sensitivity over correlated trials;
- treatment of failed, abandoned, or partially observed research attempts in a complete append-only history;
- session-return serial dependence and dependence-robust uncertainty estimates;
- chronological/purged walk-forward validation and label-span diagnostics for future observation-level labels;
- physical/logical holdout sealing, single-candidate access, and burn-after-peek controls;
- sufficient authorized real data, independently checked simulator/fill/cost assumptions, preregistered decision thresholds, and independent review.

No pass/fail significance threshold is selected by this note. DSR/PBO are diagnostics, not optimization objectives, and the current result schema must remain performance-ineligible.

## Recommended follow-up

1. Decide and preregister a trial-ledger policy that records every performance outcome seen, with mechanical failures distinguished from observed results and a tamper-evident audit trail.
2. Decide whether the next inference increment should report DSR sensitivity over a declared effective-N range, or add a dependence-aware session-level estimator first. Validate the method independently before making claims.
3. Keep one-shot holdout enforcement as a separately reviewed storage/access-control design; do not label a hash check or ordinary output directory a sealed vault.
4. Add chronological walk-forward/purging only after the candidate feature/label spans and authorized session history are defined.

