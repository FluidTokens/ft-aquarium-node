# CCL transaction-building review — findings

Against `docs/ccl-review-checklist.md` (T-041), built from cardano-client-lib **v0.7.2** — our pin —
and its 95 author-written integration tests. Format per item: **what the ITs/source do · what we do ·
why it matters · ONE / SOME / ALL.** `ONE-SOME-ALL` fills in as later builders are reviewed.

---

## T-042 — `LiquidateTransactionBuilder` (the plain path)

### ⚑⚑ C1 — the headline, and it is now MEASURED

**Authors:** `ScriptTxIT.whenCustomCollateralInputs` nominates **two** collateral inputs and verifies
`getCollateral()` has size 2. `maxCollateralInputs` is a protocol parameter (3 on preview).

**We:** exactly one — `:1893 .withCollateralInputs(inputOf(request.walletUtxo()))` — **and it is the
same UTxO as the sole wallet spend input at `:1464 tx.collectFrom(request.walletUtxo())`.** That UTxO
must pass an **ada-only** guard at `:908` (`getAmount().size() == 1 && LOVELACE`) written for the
*spend* role. **The spend role's rule silently governs the collateral role.**

**⇒ CIP-0040 removes the restriction that made this necessary.** *"If no collateral output is
specified (and therefore no tokens are in the collateral input), then we keep the old definition…
However, if collateral output is specified, then… `sum(collateral_input) = sum(collateral_output) +
collateral_consumed`."* **We already specify a collateral output** — `:1860
.collateralPayer(request.changeAddress())`.

**MEASURED 2026-08-26** against CCL v0.7.2's real `CollateralBuilders` path, throwaway harness, no
network, no submission, reverted without commit. Fee 1,113,523 ⇒ required
`ceil(1,113,523 × 150/100) = 1,670,285`:

| collateral nominated | inputs | `total_collateral` | `collateral_return.coin` | tokens returned | min-ada (CIP-40 pt 1) |
|---|---|---|---|---|---|
| **ada-only ×1 — what we do today** | 1 | 1,670,285 | **−670,285 ⛔ unparseable** | — | ⛔ violated |
| **both wallet UTxOs** | 2 | 1,670,285 | **9,964,323,149 ✅** | **5,000,000 tFLDT ✅** | ✅ 1,176,630 |
| **the token-bearing one alone** | 1 | 1,670,285 | **9,963,323,149 ✅** | **5,000,000 tFLDT ✅** | ✅ 1,176,630 |

**⇒ The reading is confirmed: the tokens ARE carried back in the collateral return, min-ada IS
satisfied, and the production failure reproduces to the lovelace.**
**⇒ And a result nobody predicted: the token-bearing UTxO ALONE is sufficient. This never needed two
inputs — it needed the RIGHT one.**
**⇒ THE WALLET WAS NEVER SHORT.** It holds ~9,966 ADA of collateral capacity — ~5,965× the requirement
— that the builder cannot reach because one UTxO serves two roles under one role's rule.

⛔ **Proposal, not a fix.** Separating collateral nomination from the spend input changes how a
liquidation transaction is constructed. **Morning decision.** ONE so far; T-043 must be checked.

### The rest of the checklist

| # | verdict | evidence |
|---|---|---|
| **A1** | ⛔ **DIVERGE** | `removeDuplicateScriptWitnesses(true)` sits at `:1928` **inside** `if (backendService == null && !published.isEmpty())`. Authors call it standalone and unguarded (`ScriptTxV3IT:479`). In production `backendService != null`, **so it never runs**. KNOWN as a defect (2026-08-25); **new is that the guard itself is non-idiomatic.** |
| **A2** | idiom **(b)** | `attachValidators` `:1621-1631` skips the attach for any script that travels by reference. Internally consistent. **The cross-builder comparison is the point — T-043 decides whether this is a divergence.** |
| **A3** | ⛔ **DIVERGE — NEW** | `withReferenceScripts` at `:1927` runs **only when `backendService == null`**. Authors call it with a real `BackendService` present (`ScriptTxV3IT:226`). Consequence: `FeeCalculators` charges the Conway ref-script fee only for bytes it can obtain; with neither a declaration nor a resolving supplier it charges **zero**, at debug level. |
| **A4** | consistent | The declared list is `publishedScripts(request.referenceScripts())` — deliberately only the published ones, which is coherent with A3's guard but inherits its divergence. |
| **B1** | ✅ **MATCH** | `:1550 tx.withChangeAddress(request.changeAddress())` — **explicit, as the authors do.** ⚠ *I expected a divergence here and there is none; recorded because an expected finding that fails to appear is a result.* |
| **B2** | n/a | Change goes to the bot's base address; no datum required. |
| **B3** | ⛔ **DIVERGE, deliberately** | Authors pay recovered value out **by name** (`StakeTxIT:704`). We let it fall into change and re-shape it afterwards in `postBalanceTx` (`:1907`, the change split). **This is a deviation for a stated reason** — the 5,000,000 tFLDT the bot receives is still unexplained, and Shape A needs no amount. **Not an oversight; revisit when that number is explained.** |
| **B4** | ✅ **MATCH** | `:1863 .mergeOutputs(false)`, deliberate. |
| **C2** | ⛔ follows C1 | `:2142 BigInteger capacity = adaOnly(walletUtxo)` — capacity of **one** UTxO. Correct given C1; **understated the moment C1 is addressed**, so the two must move together. |
| **C3** | ✅ present | `assertCollateralIsCoverable` refuses before CCL's unguarded subtract (`CollateralBuilders:137`). Added 2026-08-25. |
| **C4** | note | `feePayer`, `collateralPayer` and `withChangeAddress` are all `request.changeAddress()`. Not a divergence — but **three distinct roles coincide on one value**, and C1 shows what happens when a rule written for one silently governs another. |
| **D1** | ✅ **MATCH** | `:1869 .ignoreScriptCostEvaluationError(evaluator == null)` — fail-closed whenever an evaluator exists, exactly the authors' pairing. |
| **D2** | ⛔ KNOWN | The layout-probe pass carries a no-op evaluator. `ignoreScriptCostEvaluationError` is honoured at **one** site (`QuickTxBuilder:454-463`); `ScriptBalanceTxProviders:78` calls the same helper with **no try/catch**, so on the added-input branch a missing *and* a failing evaluator both throw unconditionally. |
| **D3** | ✅ | Blockfrost `/utils/txs/evaluate` in production, narrowed to the one-method interface; offline Aiken in the rigs. Matches author intent. |
| **E1** | ⛔ **DIVERGE — NEW** | `withVerifier(...)` is **not used**. `assertStructure` is hand-rolled and runs *outside* the library's build pipeline, so a path that skips it fails silently rather than failing the build. Authors use `withVerifier` for exactly this (`ScriptTxIT:1245`). |
| **E2** | ⛔ minor — NEW | `withTxInspector(...)` unused. Diagnosing a built body means re-serialising by hand, which is how three of 2026-08-25's findings were reached the slow way. |
| **F1** | ⛔ **DIVERGE — NEW** | Output indexes carried in redeemers are **observed** via a two-pass probe. They are **determined by construction**: `AbstractTx.complete():304-328` fixes composition order, `StakeTx:291-295` emits exactly one withdrawal dummy however many withdrawals, and `ChangeOutputAdjustments` adds *inputs* without inserting or reordering outputs. **Computing and asserting fails loudly; observing accepts whatever it finds** — and it costs a second full build per liquidation. |
| **F2** | n/a | No change output requires a datum on this path. |

**Fixed under the fixable-without-asking rule:** `CollateralCoverageTest`'s pinned `1,670,285` is
**CCL's declaration** (`RoundingMode.CEILING`), not the ledger's minimum — CIP-40 specifies
`quot(fee × pct, 100)`, i.e. **floor**, = 1,670,284, with a `≥` rule. Over-declaring by one lovelace
is safe and correct; the javadoc said it was the ledger's figure. Doc-only.

---

## T-043 — `LiquidatePayInAdvanceTransactionBuilder` (the convert path)

### ✅ A2 — THE PREDICTED DIVERGENCE IS NOT THERE, AND THAT IS A RESULT

The review was designed around this question: *does the sibling use reference-script idiom (a) where
the plain path uses (b)?* **It does not. Both use (b) — a script that travels by reference is not
attached.** `LiquidatePayInAdvanceTransactionBuilder.attachValidators` guards every attach on
`scripts.X() == null`, identically to the plain path, and its javadoc records why (measured
2026-08-24: an attached-and-referenced script left the body 8,665 bytes larger at *evaluation* time —
23,459 against a 16,384 `maxTxSize` — because `removeDuplicateScriptWitnesses` runs only **after**
balancing while script-cost evaluation runs **before** it).

**Recorded as loudly as a finding: the sibling divergence we went looking for does not exist on A2.**
The plain path was brought into line on 2026-08-25; this one has been correct since `e11ccca`.

### ⛔⛔ BUT TWO NEW SIBLING DIVERGENCES APPEAR — AND BOTH ARE OUR OWN FIXES FROM LAST NIGHT

```
                                  plain   convert
splitChangeSoAdaStaysSpendable      ✅       ⛔ ABSENT
assertCollateralIsCoverable         ✅       ⛔ ABSENT
assertChangeConserved               ✅       ⛔ ABSENT
postBalanceTx                       ✅       ⛔ ABSENT
```

**Both fixes committed on 2026-08-25 (`80857b5`) landed in ONE builder.** And the convert path is not
a lesser case of either:

- **It takes the collateral by design.** Its own javadoc, `:57`: *"the bot pays the loan's principal
  in advance (in ADA) and **takes the collateral**"*. **So it receives token value at least as
  directly as the plain path**, and will produce the same single token-bearing change output that
  `adaOnlyWalletUtxo()` correctly refuses — the failure that disabled the bot on 2026-08-25.
- **It has NO collateral guard at all** — not a weaker one. `:664
  .withCollateralInputs(inputOf(request.walletUtxo()))` nominates one input exactly as the plain path
  does, but nothing checks capacity before CCL's unguarded subtract at `CollateralBuilders:137`.
  **⇒ The convert path can still emit the negative `collateral_return` that no node can parse.** The
  plain path now refuses; this one still builds it.
- **It is live.** `LiquidationExecutor:500` routes here whenever
  `shouldLiquidationConvertToPrincipal == True` — **six of the ten measured preview loans**
  (findings §10). This is not a dormant path.

**⚑ This is the defect shape the review exists to find, twenty-four hours old, created by the fix for
that very lesson.** *"Sweep for the DEFECT, not for the FIX"* — and the sweep found our own.

⛔ **Proposal, not a fix.** Porting either guard adds a refusal and a `postBalanceTx` hook to a
liquidation path. Morning decision.

### The full checklist, both builders, with scope

| # | plain | convert | scope |
|---|---|---|---|
| **A1** strip bundled in the wrong guard | ⛔ | ⛔ `:700-701` | **ALL** |
| **A2** reference-script idiom | (b) | (b) | ✅ **MATCH — predicted divergence absent** |
| **A3** `withReferenceScripts` only when no backend | ⛔ | ⛔ `:700` | **ALL** |
| **A4** declared list = published only | — | — | ALL, follows A3 |
| **B1** `withChangeAddress` explicit | ✅ `:1550` | ✅ `:526` | ✅ **ALL MATCH** |
| **B3** residual paid out by name | ⛔ | ⛔ | ALL — deliberate on the plain path, **untreated here** |
| **B4** `mergeOutputs(false)` deliberate | ✅ `:1863` | ✅ `:633` | ✅ **ALL MATCH** |
| **C1** one collateral input, welded to the spend input | ⛔ | ⛔ `:664` | **ALL** |
| **C2** capacity over all inputs | one | **none** | ⛔ **ONE — convert computes nothing** |
| **C3** guard before CCL's subtract | ✅ | ⛔ **ABSENT** | ⛔ **ONE** |
| **C4** fee/collateral/change roles coincide | — | — | ALL |
| **D1** `ignoreScriptCostEvaluationError(evaluator == null)` | ✅ | ✅ `:639` | ✅ **ALL MATCH** |
| **D2** probe pass carries a no-op evaluator | ⛔ | ⛔ | **ALL** |
| **D3** remote evaluator in prod, offline in rigs | ✅ | ✅ | ✅ **ALL MATCH** |
| **E1** `withVerifier` unused | ⛔ | ⛔ | **ALL** |
| **E2** `withTxInspector` unused | ⛔ | ⛔ | **ALL** |
| **F1** indexes observed, not computed | ⛔ | ⛔ `:456` | **ALL** |
| **change split** | ✅ | ⛔ **ABSENT** | ⛔ **ONE** |

**Denominator: 18 items checked in both builders. 5 match the authors outright, 11 diverge in both,
2 diverge between siblings.** Both sibling divergences are absences in the convert path, and both were
introduced last night.

---

## T-044 — `ScheduledTransactionService` (the Aquarium tank processor, **the only builder that runs on mainnet**)

### ✅ THE MAINNET PATH IS SAFE ON THE TRAP THAT CAUSED THE 2026-08-21 INCIDENT

The sweep showed `withTxEvaluator` **0 hits** beside `ignoreScriptCostEvaluationError` **1** — the exact
shape of trap 8, on the path that reaches operators. **It is not the trap.** Traced through v0.7.2:

```
YaciConfig:25         new QuickTxBuilder(bfBackendService)
QuickTxBuilder:…      this.transactionProcessor = new DefaultTransactionProcessor(...)
TransactionProcessor  public interface TransactionProcessor extends TransactionEvaluator   ⇐ the key line
QuickTxBuilder:371    txBuilderContext.withTxnEvaluator(transactionProcessor)   ⇐ when none is set explicitly
:240                  .ignoreScriptCostEvaluationError(false)
```

**⇒ A real Blockfrost evaluator, fail-closed. No escalation.** Worth stating plainly because the
grep signature is indistinguishable from the incident's and would read as a finding to anyone who
stopped at the sweep. *The evaluator arrives by injection, so there is no `withTxEvaluator` to find —
the same invisibility that hid the reference-script hazard at this very call site.*

### ⛔ C1 — THIS BUILDER DIVERGES FROM BOTH SIBLINGS, AND IN THE **BETTER** DIRECTION

**No `withCollateralInputs` at all.** CCL selects collateral itself, so collateral here is **not welded
to the spend input** the way it is in both liquidation builders. **⇒ The T-042 defect does not exist on
the mainnet path**, and this is the shape the liquidation builders could adopt.

### ⚠ THE WALLET FILTER IS WEAKER THAN THE LIQUIDATION GUARD — ON MAINNET

`:171-173`
```java
.filter(utxo -> utxo.getAmount().size() == 1 && utxo.getReferenceScriptHash() == null)
.findFirst();
```
Against `LiquidateTransactionBuilder:908`, which also rejects `inlineDatum` and `dataHash`, three gaps:

1. **`findFirst()`, not largest.** It takes an arbitrary ada-only UTxO. **There is no minimum size**,
   so it can select dust and fail to fund the transaction — the exact shape that starved the
   liquidation path with a 1 ADA UTxO on 2026-08-25.
2. **No datum check.** A datum-bearing ada-only UTxO passes here and is refused there. Low severity —
   a datum on a key-locked output is inert — but the two filters answer the same question differently.
3. **No floor**, where the healthcheck's `wallet_ok` uses 2,000,000 lovelace as its spendability
   threshold. **Three places encode "a UTxO this builder can use" and none of them agree.**

**Consequence:** a failed build — loud, free, nothing on chain. **Not a loss, and not urgent**, but it
is on the path that reaches operators and it is the third instance of one rule living in three places.

### The three-builder table

| # | plain | convert | **tank (mainnet)** | scope |
|---|---|---|---|---|
| **A1** strip in the wrong guard | ⛔ | ⛔ | n/a — never declares | SOME |
| **A2** reference-script idiom | (b) | (b) | **(b)** — tank contract travels by `readFrom`, not attached | ✅ **ALL MATCH** |
| **A3** `withReferenceScripts` guarded | ⛔ | ⛔ | n/a — always has a backend supplier | SOME |
| **B1** `withChangeAddress` explicit | ✅ | ✅ | ✅ `:209` | ✅ **ALL MATCH** |
| **B3** residual paid out by name | ⛔ | ⛔ | ⛔ | **ALL** |
| **B4** `mergeOutputs(false)` | ✅ | ✅ | ✅ `:239` | ✅ **ALL MATCH** |
| **C1** collateral welded to spend input | ⛔ | ⛔ | ✅ **absent — CCL selects** | ⛔ **SOME — the tank is right** |
| **C3** capacity guard | ✅ | ⛔ | ⛔ | ⛔ **ONE has it** |
| **D1** fail-closed evaluation | ✅ | ✅ | ✅ `:240` | ✅ **ALL MATCH** |
| **D2** every path gets an evaluator | ⛔ probe | ⛔ probe | ✅ no probe | SOME |
| **E1** `withVerifier` | ⛔ | ⛔ | ⛔ | **ALL** |
| **E2** `withTxInspector` | ⛔ | ⛔ | ⛔ | **ALL** |
| **F1** indexes observed | ⛔ | ⛔ | n/a — redeemer carries no output index, so no probe is needed | SOME |
| **change split** | ✅ | ⛔ | ⛔ | ⛔ **ONE has it** |

**Denominator across three builders: 5 items match author usage everywhere · 4 diverge everywhere ·
the rest split.** The tank is the only builder that gets C1 right and the only one that needs no probe.
