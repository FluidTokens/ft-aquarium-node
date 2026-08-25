# Change split — keeping the bot's wallet spendable after a liquidation

**The problem, measured 2026-08-25.** A successful liquidation returns the bot's change as a
*single* output carrying both the ada and the collateral tokens it just received. That output fails
`adaOnlyWalletUtxo()`'s `getAmount().size() == 1` test — correctly — so the bot's entire balance
becomes unspendable in one transaction. After `49743a1e…` the wallet held 9,966 ADA and could build
nothing:

```
✅ 49743a1e…#0  1,000,000 lovelace  ada-only   ⇐ cardano-client-lib's withdrawal DUMMY output, not ours
❌ 49743a1e…#4  9,964,993,434 + 5,000,000 tFLDT
```

**A successful action created the condition that blocks the next one.** The tank processor is
starved by the same filter (`ScheduledTransactionService:169`, which takes `findFirst()` rather than
largest, so it is more fragile still).

## What the builder does today, and where the checks sit

`LiquidateTransactionBuilder.build()` is a **two-pass layout probe** (officina trap 1 — withdrawals
prepend a dummy output, so absolute output indexes cannot be predicted):

```
1. probe       = complete(assemble(… placeholder indexes …))
2. bondOutputIndexes  = locateBondOutputs(probe)          ← layout OBSERVED off the probe body
   assetOutputIndexes = assetOutputIndexes(probe …)
3. transaction = complete(assemble(… real indexes …))
4. assertStructure(transaction, …)                        ← V5, on the FINISHED body
```

**Both passes go through `complete()`**, which is where the `QuickTxBuilder.TxContext` is configured
and `.build()` is called. **So anything installed as a `preBalanceTx` or `postBalanceTx` hook inside
`complete()` runs in BOTH passes** — the probe observes the post-hook layout, and the indexes are
computed against it. That is the opposite of what a first reading suggests, and it matters.

**⚠ But `assertStructure` does not bound the bot's own outputs.** It checks reference-input ordering,
bond echoes against bond inputs, and asset-manager outputs at their observed indexes with exact
datums and bounded subsidies — i.e. **everything the validators read**. It asserts nothing about the
change output, because no validator looks there. **A mis-shaped change output is invisible to V5.**

## The three shapes

### Shape A — re-shape the change output in `postBalanceTx`

Take the change output CCL produced, move its native assets into a new min-ada output at the same
address, leave the remainder ada-only, and top the fee up for the extra output's bytes.

- **Runs:** inside `complete()`, after balancing, in both passes.
- **Needs no knowledge of the liquidation's economics** — it re-shapes whatever CCL decided, so it
  cannot disagree with the payout arithmetic because it never computes any.
- **Fee:** must be bumped manually, exactly as the reference-script publish had to be
  (`ReferenceScriptPublisher`, trap 9a) — balancing has already run.
- **Failure if wrong:** min-ada too low → phase 1 `OutputTooSmallUTxO`; value not conserved →
  phase 1; fee short → phase 1 `FeeTooSmallUTxO`. **All loud, all free, nothing on chain.**

### Shape B — pay the collateral residual explicitly, before balancing

Compute what the bot will retain (collateral collected minus everything paid to asset-manager and
bond outputs) and emit it as its own output, so CCL's change comes out ada-only.

- **Runs:** in `assemble()`, before balancing, so **CCL prices the fee correctly with no manual bump.**
- **Needs the residual**, which means duplicating payout arithmetic that already exists elsewhere in
  the builder — a second place to keep in step with rule R.
- **Failure if wrong:** residual too high → cannot balance; too low → the excess falls into change,
  which is still the bot's own output. **Also loud, also phase 1.**

### Shape C — carve a fixed ada-only output before balancing

Simplest to write: always emit a small ada-only output to the wallet, and let the change keep the
tokens.

**⛔ Rejected, and the reason is worth recording.** The carve comes out of the *current* input, not
out of the trapped pool. Each liquidation would leave a fresh small ada-only UTxO and a large
token-bearing one, and **the large one stays unspendable forever** — the bot's working capital
would ratchet down to the carve size while its balance grew. It fixes the symptom for exactly one
cycle and permanently strands the rest.

## ⚠ A correction to something I said earlier

I told the peer Shape B risks **mis-sending collateral**. **That was wrong.** Both A and B write
only to the bot's own change address, so neither can send value to a third party; the worst either
can do is misallocate between two outputs the bot already owns, and every such error is caught by
the ledger at **phase 1** — free, loud, nothing on chain. The asymmetry I claimed does not exist.

## Recommendation: **Shape A, plus the assertion it needs**

**Shape A**, because it needs no economics. Its whole job is redistribution between two outputs at
one address, so it cannot drift from rule R the way a duplicated residual calculation would, and the
fee bump it requires is a pattern this repo has already executed correctly once today.

**And it needs an assertion that does not exist yet**, because V5 is blind to the change output:

> **After the split, the sum over all outputs at the change address must equal the sum before it —
> lovelace and every asset unit independently.**

That is a conservation check on the re-shaping itself, local to the hook, needing nothing from the
rest of the builder. It converts every arithmetic slip in the split from *"whatever the ledger
happens to notice"* into a build-time failure naming the unit that did not balance. Without it the
split is protected only by phase-1 rejection, which is free but arrives after a submission and reads
as a chain problem rather than a builder one.

**Sizing:** the token output takes `MinAdaCalculator.calculateMinAda(output)` against live protocol
params — never a constant. Measured comparables from `49743a1e…`: **1,655,040** and **1,771,410**
lovelace for token-bearing outputs of this shape.

**Cost:** ~1.7 ADA per liquidation parked in a token output at the bot's own address — not lost, but
not spendable by a path that only nominates ada-only inputs — plus ~60–80 bytes against 4,480 of
headroom.

## Not in scope, and named so it is a decision rather than an omission

**A treasury address.** Sending collateral to a distinct address would keep the operational wallet
clean and make consolidation explicit, and it is the better long-run shape. It is also a new address
to fund, hold keys for and reconcile. **Same-address split now; treasury as its own decision.**

**Token accumulation.** Every liquidation adds one token UTxO. They pile up, and consolidating them
is a separate job that no part of this design does.

**Per-candidate wallet selection.** Independent of this: the executor resolves one wallet UTxO per
cycle and reuses it for every candidate, so a second liquidation in the same cycle is built against
an input the first has already spent (`BadInputsUTxO`). This design does not address it, and fixing
it needs a rule for how a cycle divides its inputs.
