# SPEC (DRAFT — NOT LOCKED): the bot implements every lender-permitted liquidation action

Status: **DRAFT for adversarial review.** No code until locked and signed off by Giovanni.
Opened 2026-08-19 from his directive: *"we need to implement all the liquidations… this to me is the
highest priority."* Inputs: the E0 spec-recovery spike and the F3 hash-recovery experiment (both
2026-08-19); all claims below are labelled **DOCUMENTED** / **DERIVED** / **UNKNOWN**.

---

## 1. Why this exists — the framing is a population partition, not fee arithmetic

**DERIVED (E0, F2).** `lm_liquidate_action.ak:147` requires `shouldLiquidationConvertToPrincipal ==
**False**`. All three other liquidation actions require it **True**
(`lm_liquidate_and_convert_action.ak:288`, `lm_liquidate_and_pay_in_advance_action.ak:202`,
`lm_liquidate_pay_in_advance_and_compound_action.ak:238`).

**The bot today can serve only False-bonds. Every True-bond is completely unserviceable.** This is the
reason to build a second mode, and it is stronger than the profitability argument: implementing *any*
True-mode unlocks the entire population, so the cheapest one wins.

Secondary, and real: the one liquidation we have executed **lost 1.09 ADA** — fee slice 1 tFLDT
(≈338,163 lovelace) against a 1,424,185 lovelace transaction fee — and landed only because
`profit-margin-lovelace` was overridden to −3,000,000. **A bot that must earn cannot rely on plain
`Liquidate`.**

## 2. Scope

| Epic | Mode | State |
|---|---|---|
| E1 | `LiquidateAndPayInAdvance` | **first** — cheapest, unlocks the True-bond population |
| E2 | `Compound` (bare) | second — best economics, no capital, no price risk |
| E3 | `LiquidatePayInAdvanceAndCompound` | union of E1+E2 |
| E4 | `LiquidateAndConvert` | **PARKED** — blocked on FluidTokens and on having no test environment |
| E5 | Mode selection + economics hardening | minimal selection ships with E2 |

### E5 scope, made concrete by T-027's measured loss (2026-08-19)
Our first positive-equity liquidation of a real loan (`79e62601…`) was a **net loss of ≈0.19 ADA** and
cleared only under a negative preview margin; at the shipped `+1,500,000` default it is correctly refused.
Two findings become E5's core, tracked here so the economics are specced **once**:
- **E5-A (was T-A) — min-ada belongs IN the profit model.** `LiquidationExecutor.record` computes
  `expectedProfit = expectedFee − txFee − margin` with **no min-ada term**, so it overstates profit by the
  min-ada the builder funds on emitted asset-manager outputs (compensation + claim). The gate must subtract
  the min-ada actually funded, and the spec must fix the counterfactual the amount rests on (against the
  ledger only the excess over the loan UTxO's own ada leaves the wallet; against the zero-equity
  counterfactual the full rider does — pick one and justify it).
- **E5-B (was T-B) — an absolute AND percentage floor (D-6), and a negative-margin HARD-STOP (not a WARN).**
  A negative `profit-margin-lovelace` currently re-authorises loss-making liquidations silently.
  **⚠ Economics-review Finding 1 (ACCEPTED), and it is the killer: the margin sits INSIDE the number the
  floors test.** `expectedProfit = fee − txFee − minAda − margin`, so a negative margin is a subtraction of a
  negative — it *inflates* expectedProfit and sails past both floors (T-027: at margin −3,000,000 the −0.19
  ADA real result scores +2.81 ADA and clears). Two mandatory fixes:
  (i) **the floors are evaluated against a margin-EXCLUDED profit** — `floorProfit = fee − txFee − minAda`,
  and both floors test `floorProfit`, never the margin-adjusted number. The margin remains a separate
  operator lever but can no longer defeat the floors.
  (ii) **a negative `profit-margin-lovelace` is a HARD-FAIL at startup when the active network is mainnet**,
  not a WARN. A WARN on the mainnet path is a comment, not a guard, and copying a working preview config to
  mainnet is the exact foreseeable operator action. Note `profit-margin-lovelace` lives OUTSIDE the
  per-token schema, so S4's safe defaults do not reach it — this hard-stop is its safety.
  The two floors Giovanni specced (check-profitability flag · absolute ADA minimum · percentage minimum)
  ride on `floorProfit`. The USD half of the absolute floor stays deferred-to-v2 (O-1). And note
  Finding 3: `floorProfit`'s `fee` term is a mark-to-oracle TOKEN value, so the absolute ADA floor still
  compares ADA against tokens (§5, O-2) — resolving O-2 is a precondition for the absolute floor to mean
  what an operator reads.

**⚠ HARD SEQUENCING CONSTRAINT (review S3, ACCEPTED). E5-A and E5-B must land before E1 ARMS on any
network.** E1 (PayInAdvance) is the only mode that fronts the bot's own capital, and E5-A/E5-B are the
correctness fix (min-ada in the model) and the safety floor (absolute + % + loud negative-margin) that
govern that capital. Shipping E1 first would arm it with `LiquidationExecutor.record`'s
`fee − txFee − margin` — the exact arithmetic T-027 measured a real 0.19 ADA loss against — with no
min-ada term and no floor. E5-A/E5-B touch only `LiquidationExecutor`, so they may be **implemented early**,
out of E5's nominal order; but E1 must not be armed (submit path enabled, even on preview behind a flag)
until they are in place. This restores the PLAN's original "prove the loop can earn before we let it lend"
and is the milestone's single most important ordering rule.

**Out of scope, permanently: `LiquidateConvertAndCompound`.** **DERIVED + DOCUMENTED (E0/F1).** The
validator is 13 lines — `withdraw(...) { False }`, comment `//We need to be DEX batchers to do this`,
85 bytes compiled in our own blueprint. Not difficult; closed.

## 3. Configuration model — one schema per **principal** token

Giovanni's Q2/Q3/Q4/Q5 rulings unify into a single per-principal-token record. He said *"feel free to
tailor round them"*; this is that tailoring, and every departure is flagged in the tracker.

```
loans.liquidation.tokens:
  "<policyId.assetName>" | "ada":
    enabled: bool                     # Q2a — disabled even if balance exists.  DEFAULT false
    payInAdvanceCapLovelace: long     # Q2b/Q2c hard cap.  DEFAULT 0 (= refuse to front). NOT nullable
    checkProfitability: bool          # Q3.1 — false = liquidate regardless.  DEFAULT true
    minProfitAbsoluteLovelace: long   # Q3.2 (ADA half — implementable today). DEFAULT 0
    minProfitPercent: decimal         # Q3.3 — applied ALONGSIDE the absolute, both must pass. DEFAULT 0
    compoundPolicy: NEVER | WHEN_PERMITTED   # Q4.  DEFAULT NEVER
    enabledModes: [LIQUIDATE, PAY_IN_ADVANCE, COMPOUND, PAY_IN_ADVANCE_AND_COMPOUND]  # Q5.  DEFAULT [] (empty)
```

**⚠ SAFE-BY-DEFAULT (review S4, ACCEPTED).** Every field has an explicit default and every default is the
*safe* one: a token absent from config, or present with unset fields, does **nothing** — no mode enabled,
no capital fronted, profitability checked, floors at zero-but-active. The earlier draft made
`payInAdvanceCapLovelace: null = "no cap"`, which put **unbounded treasury fronting** at the unset default —
inverted safety at exactly the field that governs mainnet capital risk. The cap is now **non-nullable and
defaults to 0** (front nothing); an operator must set a positive cap deliberately to enable fronting. This
is the artifact that would otherwise cross to mainnet wrong when someone flips `loans.enabled=true`.

Keyed on the **principal** token per his words. The cap governs capital deployment, which is
principal-denominated, so the key is right for that purpose — but note §6 R4: the *inventory* risk is in
the **collateral**, which this key does not constrain.

**USD floor (review S5, ACCEPTED):** `minProfitAbsoluteUsdCents` is **removed from the v1 schema**, not
carried-and-ignored. A config that sets it must **hard-fail at load** with a message pointing at O-1 (no
USD feed in the Fluid registry) — a floor that silently does not floor on mainnet is worse than an absent
one.

**Cap + profitability-off cross-check (economics Finding 6 / Codex C4, ACCEPTED):** `checkProfitability:
false` ("liquidate regardless") together with `payInAdvanceCapLovelace > 0` fronts capital with **no floor
of any kind** — the only bound is the cap, which R4 notes bounds *principal deployed*, not *loss* and not
*inventory*. Config load must **refuse** the combination `checkProfitability == false && payInAdvanceCap >
0` (or, on mainnet, hard-fail it) — fronting the treasury with profitability checks disabled is not a
state an operator reaches by accident and must not reach silently.

## 4. Mode selection — belongs here, not deferred to E5

Per loan, the bot computes three sets and intersects them:

1. **PERMITTED** — from the lender's bond datum. `shouldLiquidationConvertToPrincipal` partitions
   `{Liquidate}` from `{PayInAdvance, Convert, PayInAdvanceAndCompound}` (§1). `poolId == ""` disables
   every Compound variant (**DOCUMENTED**, datum comment). The borrower's
   `equityInPrincipalCurrency == True` vetoes **all four** (**DERIVED**, one `expect` per action) —
   there is no mode that routes around it.
2. **ENABLED** — operator config (§3), Q5 = YES.
3. **FEASIBLE** — capital available within cap; for Compound, `PoolDatum.lenderAuth` must be satisfiable
   by the bot (see §6 R2).

Then choose. **⚠ NOT "by expected profit" as first drafted — economics-review Finding 2 / Codex C2
(ACCEPTED, both reviewers converged): the modes pay in different, non-commensurable assets.**
Liquidate / PayInAdvance take their fee as **collateral tokens** (mark-to-oracle, unrealisable in-repo);
Compound takes its fee in **principal** (realisable). Comparing raw oracle-valued expected profit would
rank a token gain above an equal-looking realisable principal gain and pick the mode that fronts capital
and leaves an illiquid position — directly inverting §1/§5's own "Compound has the best economics" claim.
**The tiebreak rule: prefer realisable-principal modes over token-fee modes; only when comparing two
token-fee modes may raw oracle-valued profit decide, and even then the token leg is discounted to a
realisable value (oracle price minus an assumed disposal haircut) before comparison.** The exact haircut
is O-3 (below), a Giovanni ruling; until it is set, the selector must NOT auto-rank token modes above
principal modes. Empty intersection ⇒ the loan is not ours; record the reason so an operator can tell
"no permission" from "no capital" from "not profitable". **A silent skip is a support ticket.**

## 5. Profitability — a gate, and an honest one

**Q3.1** flag, **Q3.2** absolute floor, **Q3.3** percentage floor, both floors applied together.

**The percentage is of capital at risk — the principal advanced — not of collateral value.** Giovanni's
stated reasoning is slippage scaling with size *"particularly when we repay upfront, i.e. node operator
uses their own funds"*, and the quantity slippage acts on is the position the operator funds. Flagged as
D-7; a reviewer may argue for collateral value.

**⚠ THE USD MINIMUM IS NOT IMPLEMENTABLE FROM THE ORACLE HE REFERENCED.** **DERIVED, measured
2026-08-19.** All five FluidTokens registry entries (tFLDT, NIGHT, OADA, fGold, FLDTmultisig) publish
`tokenPriceInLovelaces` / `tokenPriceDenominator` — **every price is denominated in lovelace, and the
registry contains no USD, stablecoin or fiat feed at all** (searched: usd, usdm, iusd, djed, dai, fiat,
dollar — none present). A dollar floor therefore needs an **ADA/USD source we do not have**, and adding
one is a **constitutional escalation** ("any new external service … is an escalation to Giovanni, never a
judgment call"). Ship the ADA floor; carry the USD field as unimplemented-and-rejected until he rules.
**Do not silently convert at a hardcoded rate.**

**⚠ AND THE PROFIT IS NOT CASH.** **DERIVED (E0 §1).** In PayInAdvance the bot pays principal and keeps
the collateral; the fee is realised as a **price discount on collateral**, converted at the oracle
cross-rate with `rational.ceil` — rounded **up, against the bot** (`finance.ak:441`). So a "profitable"
liquidation leaves the bot **long an inventory position it must later sell**, at a price nobody has
guaranteed. The profitability gate is therefore **mark-to-oracle, not realised**, and the spec must say
so in the operator documentation. In Compound the fee arrives in **principal** and this problem does not
arise — which is the real reason E2's economics are the best of any mode.

## 6. Named risks, carried into the epic contracts

- **R1 — a new predicted-index class.** `asset_manager.ak:160-168` reads `ownerAssetInputIndexes[i]` as
  an **absolute index into `self.inputs`**. Every index our builders use today is a position in a
  *credential-filtered* list, which is immune to coin selection; an absolute input index is not. Bare
  Compound cannot avoid it. **E3 can, by including zero asset-manager inputs** — which also sidesteps R5.
- **R2 — some pools can never be compounded by any bot.** `pool_compound_action.ak:97-105` requires
  `authorize_action(create_auth(poolDatum.lenderAuth, …))`. README:60 states (**DOCUMENTED**) that
  `lenderAuth` should be the PoolManager withdraw hash, in which case spending the pool manager satisfies
  it — but a pool whose `lenderAuth` is a `CardanoSignature` is **permanently incompatible**. This is a
  **scanner-level eligibility check**, not a builder concern.
- **R3 — ex-units, not bytes, are the binding constraint.** Reference scripts do not count toward
  `maxTxSize`, and no mode approaches 16,384 bytes. Plain Liquidate already burns **2.82M mem /
  965M steps** of a 17.5M / 10G budget. **Ex-units are unmeasured for every mode in this spec** and every
  epic must measure them before batching is designed.
- **R4 — the cap key does not constrain inventory.** Per-principal caps bound capital deployed; they do
  not bound how much of an illiquid *collateral* the bot accumulates. A collateral allow/deny list may be
  needed. Open, D-8.
- **R5 — E3's shared index list.** `lenderBondInputIndexes` is indexed twice from zero — once per loan
  input, once per asset-manager input — while its length is pinned to the loan count. Mitigation: zero
  asset-manager inputs.
- **R6 — reference scripts must be published.** Compound needs **6 additional applied validators** on
  chain as reference scripts; an operator deployment step, with deposits.

## 7. E4 Convert — why it is parked, in one place

**DERIVED (E0/F3).** Four independent blockers, three needing a human or a live observation:
`ADA-collateral Convert appears impossible by construction` (the validator demands the order's total ADA
*equal* the swap amount while Minswap's apply path subtracts a mandatory non-zero batcher fee → negative
output — an upstream bug); the validator **hardcodes `max_batcher_fee = 700_000`** while Minswap's
reference batcher charges `2_000_000` and the chain enforces `used <= max`, so **orders may never fill**;
the order's stake credential is dictated by the **lender**, and read literally Minswap's order validator
would make an arbitrary lender stake key **unspendable** (contradicted by a real mainnet order — UNKNOWN
which revision is deployed); and **the five Minswap applied parameters are UNKNOWN** — F3 searched
~859,400 derivations against the on-chain `LMConfigDatum[5]` with no match.

**Convert cannot be exercised on preview at all.** The convert script has **never appeared in a preview
transaction** (404), and although every preprod-parameterised Minswap V2 script *is* published on preview
as a bare reference script, the authen policy holds **zero assets** and the pool/factory/order addresses
have **zero transaction history**. Nothing to reference; no batcher to fill.

**Dependency on Q10** (pending Giovanni's word): ask FluidTokens for the five parameter values or the
convert reference script / source revision, plus whether a convert order has ever been filled and where.

**A stated limit on our own provenance proof.** Our "19 of 19 comparable fields reproduce, therefore the
deployed commit is `ff005fb`" argument **never touched `LMConfigDatum[5]`** — the one field we cannot
derive. So one live explanation for F3's NO MATCH is that FT's deployed convert validator was compiled
from a **different source revision**, and it is the only validator where that drift is invisible to the
proof.

## 8. Decision tracker

### Giovanni's rulings, verbatim (2026-08-19)
| # | Ruling |
|---|---|
| Q2 | *"yes from its own balance, there should be a configurable hard cap PER TOKEN: a. token disabled even if there is balance; b. token enabled, no cap; c. token enabled and max cap. These are my overall requirements, feel free to tailor round them."* |
| Q3 | *"1. check-profitability flag…; 2. min profitability, an ABSOLUTE minimum — in either ADA or dollar; this depends on the Fluid oracle — if we can convert to dollar it'd be better, ideally BOTH…; 3. min profitability % ALONG WITH the absolute minimum → the larger the loan the bigger the slippage, particularly when we repay upfront."* |
| Q4 | *"it should be the best user experience. best effort in liquidating plus operator flexibility; if there is something that can be configured per (principal) token, make it configurable."* |
| Q5 | Operators select modes — **YES**. |
| Q8 | Bare `Compound` in scope — **YES** (*"sure"*). |
| Q1 / Q6 / Q9 | Not answered (*"not sure how to answer"*) — deferred with Convert; **must not block E1/E2**. |
| Q10 | Not yet understood by him; being explained. FT request **PENDING HIS WORD — do not send.** |
| T-022 | *"I will create .env.mainnet with BF key"* → then run the read-only registry oracle resolution and pin the golden. **Hold until it exists.** |

### Decisions derived here (each a reviewer target)
| # | Decision | Rationale |
|---|---|---|
| D-1 | One milestone, sequenced epics — not per-mode milestones | selection + economics are cross-cutting; splitting duplicates the shared half |
| D-2 | **E1 = PayInAdvance**, not Convert | withdrew my own Convert-first argument once F2 showed the partition; cheapest mode unlocks the same population |
| D-3 | `LiquidateConvertAndCompound` dropped | validator is `False` |
| D-4 | Config keyed per **principal** token | his words; cap governs capital, which is principal-denominated |
| D-5 | `enabled` + nullable cap encodes Q2's three states | avoids a three-valued enum |
| D-6 | Both profit floors apply together (AND, not OR) | *"% ALONG WITH the absolute minimum"* |
| D-7 | The **%** is of **capital at risk** (principal advanced) | his slippage reasoning points at the funded position |
| D-8 | Minimal mode selection ships with E2, not E5 | otherwise each epic invents its own and E5 becomes a refactor |
| D-9 | Skips must be **recorded with a reason** | "no permission" vs "no capital" vs "not profitable" are different operator actions |

### Open items — need a ruling before lock
| # | Open |
|---|---|
| ◯ O-1 | **The USD profit floor is not implementable** (§5). Ship ADA-only, or authorise an external ADA/USD source (constitutional escalation)? |
| ◯ O-2 | Profit is **mark-to-oracle, not realised** (§5). Accept, with the operator doc saying so? Or add an inventory/exposure cap (relates to R4/D-8)? |
| ◯ O-3 | Collateral allow/deny list in addition to the principal cap? (R4) |
| ◯ O-4 | `compoundPolicy` values — is `WHEN_PERMITTED` enough, or does he want a threshold (compound only above N)? |
| ◯ O-5 | Q10 — send the FT request? Gates E4 entirely and has a long round-trip. |

---

## Adversarial review — findings, cross-provider convergence, dispositions (2026-08-19)

Two-pass review run at milestone altitude. **Pass A — seams (opus, disjoint remit):** epic junctions,
SPEC-vs-deployed-validators, factory dependency. **Pass B — economics (opus):** the profit arithmetic and
mode logic head-on. **Cross-provider — Codex (JSON contract):** re-run of the economics remit; it survived
on the tight contract after dying twice on prose briefs. **Provider-diversity caveat (verbatim for the
lock decision):** the cross-provider pass was ATTEMPTED and initially failed twice on the forwarder; the
economics findings rest primarily on two independent in-house opus passes with disjoint remits and fresh
contexts, corroborated by a surviving Codex pass. Provider diversity is a means to independence, not the
goal — two genuinely-independent reviews with this stated gap beat one dressed up as two.

**CONVERGENCE (independent reviewers hitting the same defect — the strongest signal):** the mode-selection
commensurability defect (economics F2 = Codex C2), the mark-to-oracle unrealisable-profit defect
(economics F3 = Codex C1), the submit-time re-pricing gap (economics F5 = Codex C5), and the
cap+profitability-off hole (economics F6 = Codex C4) were each raised **independently by two providers**.

| # | source | sev | disposition | SPEC change |
|---|---|---|---|---|
| S3 | seams | HIGH | **ACCEPT** | §2: hard rule E5-A/E5-B before E1 arms |
| S4 | seams | HIGH | **ACCEPT** | §3: safe-by-default; cap non-nullable default 0 |
| S5 | seams | MED | **ACCEPT** | §3: USD field removed from v1, hard-fail at load |
| F1 | econ | HIGH | **ACCEPT** | §2 E5-B: floors test margin-EXCLUDED profit; negative margin hard-fails on mainnet |
| F2 / C2 | econ+codex | HIGH | **ACCEPT** | §4: prefer realisable-principal modes; token legs haircut-discounted; no auto-rank of token modes (haircut = O-3) |
| F3 / C1 | econ+codex | HIGH | **ACCEPT (precondition)** | §5/E5-B: gate's `fee` is mark-to-oracle tokens; O-2 must be resolved before the absolute floor means what an operator reads |
| F4 | econ | MED | **ACCEPT** | §2 E5-A: min-ada is per-loan `outputMinAda − adaFromInputUtxo`, not a constant; count ALL funded outputs; note realisation cost |
| F6 / C4 | econ+codex | HIGH | **ACCEPT** | §3: refuse `checkProfitability=false && cap>0` |
| S1 | seams | HIGH | **ACCEPT (E2 scope)** | §2: E2 needs its own candidate-enumeration + fixture story — bare Compound is not loan-anchored; the T-016 factory cannot originate an E2 candidate (deposits collateral, not principal). E2 spec must add a "how is a compound candidate found and test-originated" section before E2 starts. |
| S2 | seams | HIGH | **ACCEPT (acceptance bar)** | §6: add an explicit acceptance bar per mode — indexes computed off the finished, coin-selected body, and an on-chain proof (as T-016 was for plain Liquidate). "Dry-eval green" is not sufficient: the rig has no coin selection so it is blind to R1's absolute `self.inputs` index. |
| C3 | codex | MED | **ACCEPT (E3)** | §2: E3's "union of E1+E2" is not yet shown validator-legal as one tx; E3 must prove the composed transaction on chain, not assert the union. |
| F5 / C5 | econ+codex | MED | **DEFER-with-note (E5)** | §5: no submit-time re-price / volatility-scaled buffer. Recorded as E5 scope; lean on the ADA/ADA oracle-free population (findings §7.4) which carries no scan→submit price risk. |
| S7 | seams | MED | **DEFER-with-note** | §5: E1 loses plain-Liquidate's oracle-free path and re-acquires the ~60–80s blackout exposure for its target population. Note, not a blocker. |
| S8 | seams | MED | **NOTE** | §5: `minProfitPercent of capital at risk` is a no-op for non-fronting modes (Liquidate, Compound advance no principal). E5-B must state the % floor applies only to capital-fronting modes. |
| S6 | seams | MED | **OPEN (O-2)** | E1's token inventory is realisable only via Convert (E4, parked) or by restricting to ADA-collateral loans — which is disjoint from E1's True-bond population. Load-bearing on a parked epic; stays an open item blocking lock until ruled. |

**New OPEN items requiring Giovanni before lock (in addition to O-1):**
- **O-2 — realisation policy.** The bot's fee is illiquid collateral tokens. Does the gate report/compare
  mark-to-oracle value (and accept token inventory as the P&L unit), or restrict liquidations to
  realisable-ADA outcomes (ADA collateral / `shouldLiquidationConvertToPrincipal` loans), or wait for E4
  Convert? The absolute floor's meaning and E1's whole value proposition (S6) both hinge on this.
- **O-3 — the disposal haircut** used to discount token-fee legs for mode comparison (§4). A number, or a
  rule (e.g. Minswap depth-based), or "no token modes auto-ranked until E4."

**Clean bills the review verified (real results):** Compound/PoolManager eligibility correctly captured
(§4/§6 R2 vs findings §15); R5's zero-asset-input mitigation valid; §14 output-freedom not relied upon;
`LiquidateConvertAndCompound` correctly dropped (validator is `False`); safe-default arming confirmed
(Codex C6 — `loans.enabled=true` alone arms nothing); §5's USD/O-1 honesty correct; committed mainnet
config defaults safe today.

**NOT LOCKED.** This review informs the lock; the lock is Giovanni's approve. E1 dispatches only after
lock, and only with E5-A/E5-B in place (S3).
