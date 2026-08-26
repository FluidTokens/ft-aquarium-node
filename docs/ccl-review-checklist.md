# CCL transaction-building review — the checklist (T-041)

**Built 2026-08-26 from cardano-client-lib v0.7.2 — our exact pin — at
`~/Development/workspace/cardano-client-lib`. Written WITHOUT OPENING OUR BUILDERS**, so it cannot
be shaped by what we happen to do. Every item is a **question**, not an assertion about us.

## ⚠ Where the authority actually is, measured before this was written

| source | verdict |
|---|---|
| `cardano-dev-skills:build-transaction`, CCL section | a Maven coordinate + "suits JVM projects", ~10 lines |
| `cardano-dev-skills` corpus `docs/sources/cardano-client-lib/` | **one file, a README.** Zero hits for `ScriptTx`, `withdraw`, `collateral`, evaluator, `changeAddress`, `referenceScript`, `removeDuplicate` |
| the library's own `docs/` | Docusaurus scaffold — `intro.md`, `usedby.md`, `support-this-project.mdx` |
| **the library's 95 integration tests** | **the standard.** Author-written, executable, at our pin |

**⇒ `ScriptTxIT` (1,276 lines), `ScriptTxV3IT` (542, our era), `StakeTxIT` (889, withdrawals) and
`TxBuilderContextIT` (963) are the CCL documentation for what we build.** Prose describes intent; a
test the authors run describes **behaviour**, and behaviour is what a builder can disagree with.

**KNOWN** marks an item `officina:ccl-transaction-building-traps` already covers. officina was
distilled *from this repo*, so it is a register of what we already know — **never the standard.** An
item marked KNOWN finding something is confirmation; an unmarked item finding something is new.

---

## A. Script witnesses vs reference scripts

**A1.** Is `removeDuplicateScriptWitnesses(true)` called **unconditionally**, or bundled inside a
condition belonging to another call? *Authors: `ScriptTxV3IT.alwaysTrueScript_when…removeDuplicateWitness`
calls it standalone on the `TxContext`, with no guard.* **Consequence:** a script present both in the
witness set and as a reference input is `ExtraneousScriptWitnessesUTXOW` at phase 1, and until then
its bytes are paid for twice. **KNOWN** (trap 9) — but the *guard placement* is not.

**A2.** For each script, which of the **two documented idioms** is used, and is the choice consistent?
*(a) attach + `readFrom` + let `removeDuplicateScriptWitnesses` strip the copy —
`ScriptTxV3IT:438`. (b) do NOT attach; `readFrom` + `withReferenceScripts(script)` to declare the
bytes — `ScriptTxV3IT:226`.* **Consequence:** mixing them per-script inside one builder is how a
script ends up attached on one path and referenced on another, which is exactly the sibling
divergence this review exists to find.

**A3.** Is `withReferenceScripts(...)` called **regardless of whether a `BackendService` is present**?
*Authors call it with a real backend (`ScriptTxV3IT:226` uses `new QuickTxBuilder(backendService)`
and still declares the script).* **Consequence:** `FeeCalculators` can only charge the Conway
reference-script fee for bytes it can obtain; given neither a declaration nor a supplier that
resolves, it charges **zero** and says so at debug level. **NEW — the authors' unconditional call is
not an idiom we can assume we match.**

**A4.** If reference scripts are declared, is the list **complete**? **Consequence:** a partial list
makes `FeeCalculators` price only what it was handed and skip the supplier that would have priced the
rest — under-charging in the direction that fails at phase 1.

## B. The change address — set, or inherited?

**B1.** Is the change address set **explicitly** on the `Tx`/`ScriptTx` via `withChangeAddress(...)`,
or does it arrive as a side effect of `feePayer`? *Authors set it explicitly in every script test:
`ScriptTxV3IT:438`, `ScriptTxIT:1238`, `ScriptTxIT:522`. `QuickTxBuilder:338` propagates `feePayer`
into the `ScriptTx`'s change address when it was not set.* **Consequence:** two different decisions —
"who pays the fee" and "where does the residue go" — collapse into one call, and the second is made
by default rather than chosen. **NEW.**

**B2.** Where change goes to a contract, is the **datum** supplied? *`withChangeAddress(address,
plutusData)` is a documented 2-arg form (`ScriptTxV3IT:471`).* **Consequence:** a change output at a
script address with no datum is unspendable under V1/V2 and merely undecodable under V3.

**B3.** Are **non-ada residuals paid out explicitly** rather than left to fall into change?
*Authors pay recovered value out by name — `StakeTxIT.withdrawal_scriptAddress_zeroBalance` does
`.payToAddress(sender1Addr, Amount.ada(10))` for the value it just unlocked.* **Consequence:** change
absorbs whatever was not named, so anything the transaction *receives* lands in one output whose shape
nobody chose. **NEW as a documented idiom** (the symptom is KNOWN, trap 17).

**B4.** Is `mergeOutputs(...)` set deliberately, and is the choice consistent across builders?
*Authors set `mergeOutputs(false)` when they want outputs kept distinct (`ScriptTxIT:1226`); default
is true.* **Consequence:** merging silently combines two outputs the validator may count separately.

## C. Collateral

**C1.** **How many collateral inputs are nominated?** *Authors pass **two**:
`ScriptTxIT.whenCustomCollateralInputs` → `.withCollateralInputs(new TransactionInput(payTxHash, 1),
new TransactionInput(payTxHash, 2))`, and verify `getCollateral()` has size 2. `maxCollateralInputs`
is a protocol parameter (3 on preview).* **Consequence: collateral capacity is the SUM of the
nominated inputs.** A builder that nominates exactly one is capped at that one UTxO's ada however
much the wallet holds — **and this is the live blocker as of 2026-08-25.** **NEW, and the highest-value
question on this checklist.**

**C2.** Is collateral **capacity** computed over all nominated inputs, or over one assumed input?
**Consequence:** a guard that models a single input will refuse builds a correctly-multi-input
transaction would have made.

**C3.** Is there a builder-side check before CCL subtracts? *`CollateralBuilders.balanceCollateralOutputs()`
:137 does `collateralReturn.getCoin().subtract(totalCollateral)` **with no sufficiency check**, and
:129 rounds `fee × collateral_percent / 100` with `RoundingMode.CEILING`.* **Consequence:** insufficient
capacity yields a **negative** collateral return, which has no CBOR encoding — rejected by the decoder
**before any validation runs.** **KNOWN** (trap 16, found 2026-08-25).

**C4.** Is a separate **collateral payer** used where fee payer and collateral source differ?
*`ScriptTxIT.alwaysTrueScript_withFeeFromChange_differentCollateral`.*

## D. Script-cost evaluation

**D1.** Is `ignoreScriptCostEvaluationError(false)` paired with `withTxEvaluator(...)`? *Authors do
both together — `StakeTxIT.withdrawal_scriptAddress_zeroBalance`.* **Consequence:** the flag defaults
**true**, so a failed evaluation is a `log.warn` and the build ships placeholder ex-units → phase 2,
collateral forfeit. **KNOWN** (traps 8, 13).

**D2.** Does **every** build path get an evaluator, including intermediate or discarded assemblies?
*The flag is honoured at exactly one site — `QuickTxBuilder:454-463`. `ScriptBalanceTxProviders:78`
calls the same helper with **no try/catch**, so on the added-input branch both a missing evaluator and
a **failing** one throw unconditionally.* **Consequence:** a build that works while one UTxO covers the
whole transaction dies the moment balancing adds an input. **KNOWN as of 2026-08-25, not in officina.**

**D3.** Which evaluator, and is the choice justified per environment? *Authors use
`AikenTransactionEvaluator(utxoSupplier, protocolParamsSupplier)` even against a real backend, and
pass a `ScriptSupplier` when a script travels by reference (`ScriptTxV3IT:226`).* **Consequence:** a
reference-script transaction cannot be priced unless the evaluator can obtain the script **bytes** —
declaring the hash is not enough. **KNOWN** (trap 13).

## E. Verifying the built artefact — the seam the library already provides

**E1.** Is `withVerifier(...)` used to assert on the finished body? *Authors do:
`ScriptTxIT.whenCustomCollateralInputs` → `.withVerifier(txn -> assertThat(txn.getBody().getCollateral())…)`.*
**Consequence:** a hand-rolled post-build assertion runs *outside* the library's own build pipeline, so
it cannot fail the build the way `withVerifier` does, and it is easy to leave a path that skips it.
**NEW.**

**E2.** Is `withTxInspector(...)` available on the diagnostic paths? *Used throughout the ITs.*
**Consequence:** without it, diagnosing a built body means re-serialising it by hand — which is how
three of 2026-08-25's findings were reached the slow way.

## F. Output layout

**F1.** Are output indexes carried in redeemers **computed from construction**, or observed from a
probe build? *Composition order is fixed: `AbstractTx.complete():304-328` emits deposit/refund dummy
outputs first, then the donation dummy, then the caller's outputs in insertion order; change is
appended by the balancer; `ChangeOutputAdjustments` adds **inputs** and adjusts the existing change
output's value but does **not** insert or reorder outputs. `StakeTx.buildStakePayments():291-295` adds
**exactly one** withdrawal dummy however many withdrawals exist.* **Consequence:** an index that is
determined by construction can be **asserted** and fails loudly; one that is observed silently accepts
whatever it finds. **NEW as a documented fact** (the dummy itself is KNOWN, trap 1).

**F2.** Does a change output ever need a **datum**? *`ScriptTxV3IT.alwaysTrueScript_datumHashInChangeOutput`.*

## G. How to use this

Per builder, answer every item with: **`file:line` · what the ITs or v0.7.2 source do · what we do ·
why the difference matters · and whether it appears in ONE builder, SOME, or ALL.**

**A finding with no consequence named is noise.** An item where we match the authors is still an
answer — record it, because Phase 2's value is the *divergence between siblings*, and that is only
visible when both sides of the comparison were checked against the same list.
