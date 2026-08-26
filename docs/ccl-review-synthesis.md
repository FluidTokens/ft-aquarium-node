# CCL transaction-building review — what to do

**Read this first; `ccl-review-findings.md` is the evidence and `ccl-review-checklist.md` the standard.**
Six builders reviewed against cardano-client-lib **v0.7.2** — our exact pin — its 95 author-written
integration tests, **and (second pass, 2026-08-26) the 66-file CCL documentation corpus, the Yaci
corpora, and officina.** Nothing was fixed in any liquidation path. **Nothing is deployed.**

> **⚠ The first pass ran without the docs** — a `find … | head -1` returned a README-only plugin
> version (measured: 12 draws, 3 wrong versions, the right one **zero** times). Giovanni ordered it
> re-run. **Nothing from the first pass was contradicted, and the second pass found a live latent
> defect the first could not reach.** Decisions 1 and 3 below carry its results.

---

# ⛔ THREE THINGS THAT CHANGE A DECISION YOU ARE ABOUT TO MAKE

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

> Funding the wallet still works and is zero-risk. This is about whether it is *necessary*.

**⚠ SECOND-PASS QUALIFICATION — read this before acting on the row above.** The collateral arithmetic
was exercised directly against CCL's `CollateralBuilders` and **stands**. What is now in question is
whether the **input set** it ran on was complete. `AppUtxoService:28` reads the wallet from the local
index and falls back to the provider **only when the index returns EMPTY** — so a **partially indexed**
wallet returns quietly, and every builder decides on an understated balance (finding **Y-1**). The
"9,964 ADA is reachable" claim assumes the index returns `49743a1e…#4`.

**⇒ Not "the measurement was wrong" — "the measurement's input may have been partial."** A fix that
separates the collateral roles but leaves Y-1 in place **may not restore the capacity it promises.**
Settling it means querying a running node, which is your call, not mine.

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

## 3. ⛔ A latent defect that arms the moment you publish one more reference script  *(D-5)*

Found **only** because the docs surfaced `mintAsset` semantics. **Both liquidation builders burn the
loan NFT:**

```
LiquidateTransactionBuilder:1476             tx.mintAsset(registry.getLoanScript(), burns, …)
LiquidatePayInAdvanceTransactionBuilder:487  tx.mintAsset(registry.getLoanScript(), …)
```

**`mintAsset(script, …)` ALWAYS attaches a witness copy of the policy script.** But `loan.loan` is
also a reference-script candidate, and the attach-skip that implements our reference-script idiom
guards a **different call**:

```
:1626  if (scripts.loan() == null) { tx.attachRewardValidator(…); }   ← skipped when referenced
:1476  tx.mintAsset(registry.getLoanScript(), …)                      ← attaches ANYWAY
```

**⇒ Set `AQUARIUM_LIQUIDATION_REF_LOAN` — the config slot already exists at `application.yaml:140` — and the same script is a
reference input AND a witness copy in one transaction:** `ExtraneousScriptWitnessesUTXOW` at phase 1,
or the bytes paid for twice with evaluation seeing the bloated body. **And the safety net is disabled:**
`removeDuplicateScriptWitnesses(true)` sits inside a guard that never fires in production.

**Latent today** — `loan:` is unset. **But publishing `loan` is the obvious next step for
transaction-size reduction, and it is exactly what arms this.**

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

### P8 — Guard the minted-and-referenced script before publishing `loan`. *Blocks a foot-gun.*
**Buys:** publishing `loan` stops being able to produce a phase-1 rejection or a double-paid script.
**Costs:** either skip the mint-side attach when the script is referenced, or move
`removeDuplicateScriptWitnesses(true)` out of the guard that stops it running in production (**that
guard is A1 and is non-idiomatic anyway** — the library's own tests call it standalone). **⚠ Do this
BEFORE publishing another reference script, not after.**

### P6 — Compute output indexes instead of probing for them.
**Buys:** deletes the two-pass layout probe, deletes `LayoutProbeEvaluator`, and **halves the build
cost of every liquidation** — one full assembly and one evaluate call instead of two. An index that is
computed can be **asserted** and fails loudly; one that is observed accepts whatever it finds.
**Costs:** touches the core of both liquidation builders. Composition order is fixed and verified
(`AbstractTx.complete():304-328`; exactly one withdrawal dummy however many withdrawals,
`StakeTx:291-295`; `ChangeOutputAdjustments` adds inputs without reordering outputs).

**⚑ AND THE DOCS OFFER A THIRD OPTION NEITHER OF US CONSIDERED.** You proposed computing the indexes;
I proposed compute-and-assert. The library documents a third: **drop these builders to the Composable
Functions API**, which exists for *"deterministic, ordered control over how inputs/outputs are shaped
and balanced."* Larger, but it is the layer built for this problem rather than a workaround on top of
one that is not.

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

# FORWARD-LOOKING — from the docs, not urgent, not proposals

- **`ScriptTx` is DEPRECATED in 0.8.0** and will be removed; migration is documented as drop-in
  (`new ScriptTx()` → `new Tx()`). **All three production builders use it.** We are pinned at 0.7.2 and
  0.8 is preview-only on a funds path, so **this is a roadmap item, not a task** — but it is the
  clearest illustration of why the docs were worth re-running for: **a source tree pinned at 0.7.2
  cannot tell you a class is going away.**
- **`tx.mintAsset(policyId, …)`** in 0.8 mints via a *reference script*, which would dissolve P8's
  cause rather than guard it.
- **⚑ TxFlow BATCH is a CANDIDATE CLOSURE for a defect you have been carrying.** *"Transaction hashes
  are computed client-side using Blake2b-256, so subsequent transactions can reference earlier outputs
  before any are submitted."* That is the documented answer to **one wallet UTxO per cycle, the second
  liquidation building against an input the first already spent.** ⚠ **Test it, do not adopt it** —
  preview API on a funds path.

# WHAT WAS READ AND RULED OUT — so nobody spends a turn re-reading it

| corpus | verdict |
|---|---|
| `yaci-store/ledger-state-mismatches` (4 versions) | **N/A.** Entirely DRep distribution, DRep expiry, treasury/reserves and governance-action status vs DB Sync. **No UTxO semantics.** It has the most transaction-sounding directory name in the corpus, which is exactly how a scope drift starts |
| `yaci-store/tracking-address-utxos`, `plugins/write-first-plugin` | **Confirms, indexer-side** — the documented form of our write-time filter, and the upstream cause of Y-1 |
| `yaci-devkit` (21 files) | **N/A to this brief** — local devnet operation. **Worth its own ticket**: it is how the liquidation path could be exercised offline, which is the gap behind every "never run in a real `complete()`" caveat here |
| `cardano-dev-skills` CCL corpus (66 files) | **Used.** Five behavioural terms remain at **zero** — `collateralReturn`, `totalCollateral`, `"collateral return"`, `"dummy output"`, `"after balancing"` — so every behavioural finding still rests on the source and its tests, and **none is contradicted** |

**⇒ The two authorities are complementary: the docs say WHAT EXISTS, WHICH LAYER TO USE and WHAT IS
GOING AWAY; the source and its tests say WHAT ACTUALLY HAPPENS. A review with only one is incomplete
in a direction it cannot detect from inside.**

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
