# CCL transaction-building review — what to do

**Read this first; `ccl-review-findings.md` is the evidence and `ccl-review-checklist.md` the standard.**
Six builders reviewed against cardano-client-lib **v0.7.2** — our exact pin — and its 95
author-written integration tests. Nothing was fixed in any liquidation path. **Nothing is deployed.**

---

# ⛔ TWO THINGS THAT CHANGE A DECISION YOU ARE ABOUT TO MAKE

## 1. The wallet needs nothing. It was never short.

**You were going to be asked to fund the wallet or run a consolidation transaction.** Neither is
required.

The liquidation builders nominate **one** UTxO as collateral, and it is **the same UTxO they spend**,
which must pass an ada-only rule **written for the spending role**. So the wallet's 9,964 ADA sat
behind a guard answering a question about a different job.

**CIP-0040 removed that restriction years ago** — collateral may carry native assets provided a
collateral output is specified, **and we already specify one**. Measured against CCL's real
`CollateralBuilders` path (no network, no submission, nothing committed):

| collateral nominated | `collateral_return` | tokens returned | min-ada |
|---|---|---|---|
| ada-only ×1 — **what we do today** | **−670,285 ⛔ unparseable** | — | ⛔ violated |
| both wallet UTxOs | **9,964,323,149 ✅** | 5,000,000 tFLDT ✅ | ✅ |
| **the token-bearing one alone** | **9,963,323,149 ✅** | 5,000,000 tFLDT ✅ | ✅ |

Row one reproduces the on-chain failure **to the lovelace**, which is what makes rows two and three
mean something. **The available capacity is ~5,965× the requirement.**

**⇒ And the fix already exists in this repo.** `ScheduledTransactionService` — the mainnet tank —
nominates **no** collateral inputs at all and lets CCL choose. **It has never had this defect.** The
proposal is not "invent a way to separate the two roles"; it is **"do what the tank already does."**

> Funding the wallet still works and is zero-risk. This is about whether it is *necessary*. It is not.

## 2. The convert path can still build the transaction no node can parse — and it is live

Last night's two fixes — the change split and the collateral refusal — **landed in one builder.**

```
                                  plain   convert
splitChangeSoAdaStaysSpendable      ✅       ⛔ ABSENT
assertCollateralIsCoverable         ✅       ⛔ ABSENT
```

The convert path **takes the collateral by design**, nominates one input exactly as the plain path
did, and has **no capacity check at all** — not a weaker one. **It can still emit the negative
`collateral_return` that every era decoder rejected.** And `LiquidationExecutor:500` routes there
whenever `shouldLiquidationConvertToPrincipal == True` — **six of the ten measured preview loans.**

**Not mainnet** (lending is disabled there). **But the next True-bond candidate takes this path.**

---

# THE STRUCTURAL FINDING — why these keep happening

**There is no canonical shape to diff against.**

The `TxContext` configuration decision — who pays the fee, who pays collateral, whether outputs merge,
whether evaluation fails closed — is written at **four sites, and no two are identical:**

| | feePayer | collateralPayer | mergeOutputs | ignoreScriptCost | preBalance | postBalance |
|---|---|---|---|---|---|---|
| plain | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| convert | ✅ | ✅ | ✅ | ✅ | ✅ | ⛔ |
| tank | ✅ | ✅ | ✅ | ✅ | ✅ | ⛔ |
| publisher | ✅ | ⛔ | ✅ | ⛔ | ✅ | ✅ |

**Some of those absences are correct.** The publisher invokes no script, so it needs neither a
collateral payer nor evaluation. **Some are the defect above.** **And nothing distinguishes the two at
a glance** — you cannot rank a single row without knowing whether that builder invokes a script.

**⇒ A deliberate omission and a forgotten one are indistinguishable to the next reader.** That is why
the convert path's missing `postBalanceTx` read as normal for a day, and it is not a documentation
habit — **it is a structural property of this codebase.** Every sibling divergence chased this week —
the witness strip, last night's one-sided fix, min-ada in three places, three disagreeing wallet
filters — is a symptom of one configuration decision with four homes and no owner.

---

# PROPOSALS, cheapest first

Each states **what it buys** before **what it costs**. **None is done; all are yours to rule on.**

### P1 — Use what is already in the jar. *No new code.*
**Buys:** removes the dust-selection failure mode on the mainnet path, and deletes one of three
disagreeing wallet filters. **Costs:** swapping a hand-rolled loop for a library class.
`ScheduledTransactionService:171` hand-rolls `findFirst()` over an ada-only filter **with no minimum
size** — it can select dust and fail to fund, the shape that starved liquidation yesterday. The
library ships **`LargestFirstUtxoSelectionStrategy`**, and `DefaultUtxoSelectionStrategyImpl` already
skips datum-hash UTxOs by default. **Two of that filter's three gaps close by adopting a dependency we
already have.** *This is the whole review in miniature: the library had it and we wrote our own.*

### P2 — Port the collateral guard and the change split to the convert path.
**Buys:** the live path stops being able to build an unparseable transaction, and stops disabling the
wallet after a successful liquidation. **Costs:** adds a refusal and a `postBalanceTx` hook to a
liquidation path — **your call, which is why it is not done.** ⚠ *Or supersede it with P3, which makes
the collateral half unnecessary on both paths.*

### P3 — Nominate collateral independently of the spend input.
**Buys:** the wallet's full balance becomes usable as collateral; the 670,285 shortfall disappears;
neither liquidation path can starve on a small ada-only UTxO again. **Costs:** one call removed from
each of two builders — **the tank already demonstrates the target shape.** ⚠ `adaOnlyWalletUtxo()`
stays exactly as it is; it was never the problem, it was being asked about a role it was not written
for.

### P4 — One canonical `TxContext` configuration, applied by all builders.
**Buys:** the structural finding above stops recurring — a future fix lands once, and an absence
becomes visible as an absence. **Costs:** touches all four sites; the largest change here and the one
with the most regression surface. *The prize is that "which sibling is missing the fix?" stops being a
question anyone has to remember to ask.*

### P5 — Replace the hand-rolled structural assertion with `withVerifier(...)`.
**Buys:** the assertion runs **inside** the library's build pipeline and fails the build, instead of
running outside it where a path can skip it silently. **Costs:** **three separate fixes, not one** —
see the costing note below.

### P6 — Compute output indexes instead of probing for them.
**Buys:** deletes the two-pass layout probe, deletes `LayoutProbeEvaluator`, and **halves the build
cost of every liquidation** — one full assembly and one evaluate call instead of two. An index that is
computed can be **asserted** and fails loudly; one that is observed accepts whatever it finds.
**Costs:** touches the core of both liquidation builders. Composition order is fixed and verified
(`AbstractTx.complete():304-328`; exactly one withdrawal dummy however many withdrawals,
`StakeTx:291-295`; `ChangeOutputAdjustments` adds inputs without reordering outputs).

### P7 — `withTxInspector(...)` on the diagnostic paths. *Trivial.*
**Buys:** reading a built body stops requiring hand re-serialisation — which is how three of
yesterday's findings were reached the slow way.

---

# ⚠ THE COSTING, HONESTLY

**The four items that diverge in all three builders are three fixes, not one.** `withVerifier`,
`withTxInspector`, paying residuals out by name, and the reference-script/witness handling are all
`Tx`/`TxContext` calls made **inside each builder's own assembly**. The wiring seam decides **which
supplier, which backend, which evaluator** — **it does not decide how a transaction is assembled.**

*Stated because the natural reading is that one wiring change fixes four divergences. It does not, and
the discovery would otherwise arrive during implementation.*

---

# THE DENOMINATOR

**18 items · 3 production builders.** **5 match author usage everywhere · 4 diverge everywhere · the
rest split.** Matches were recorded deliberately: *five-match/eleven-diverge is a fact about the
builders; eleven findings alone would be a fact about the reviewer.*

**And the shape of the four is the review's answer to your diagnosis.** They are not sibling drift —
they are **consistent house style that differs from author usage**, on exactly the points the
integration tests demonstrate. **Three independently written builders agree with each other and
disagree with the library.** Internal consistency is what made it invisible: **nothing looks wrong when
everything agrees.**

Two results worth as much as findings: **the divergence the review was designed to find does not
exist** (both liquidation builders use the same reference-script idiom; the convert path has been
correct since `e11ccca`), and **`ReferenceScriptSafeUtxoSelection` is correctly hand-rolled** — CCL's
coin selection is reference-script blind, so there was no idiom to adopt, and it already handles the
fallback trap that would have made it decorative.

---

# STANDING HAZARDS — named, not proposed

**The 2026-08-21 promotion shape still exists.** `LoanFactory` is a build-only builder with a null
`TransactionProcessor` in the test tree — structurally what `LiquidatePayInAdvanceTransactionBuilder`
was before it was promoted and shipped the null-evaluator defect. **The shape was documented, not
removed**, and CLAUDE.md's production-wiring rule is the only thing between it and a repeat.

**⚑ A correction to our own officina trap 8.** Its detection signature — *builder with no
`withTxEvaluator`* — fired **three times tonight, benign every time**: the tank (evaluator arrives by
**injection**, so there is no call to grep), the publisher (**no redeemers**, so evaluation is skipped
by design), and `LoanFactory` (**build-only**, null processor deliberate). **Three false positives for
the one true one it was written from, and every false positive is *defensible*** — the expensive kind,
because it trains the next reader to discount the alarm. **The signature must be qualified by how the
builder was constructed and whether the transaction carries redeemers.** *An unqualified detection rule
that fires wrongly three times in four will be ignored by the fourth reader — and the fourth is the one
it was written for.*
