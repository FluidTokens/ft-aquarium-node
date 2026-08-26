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
