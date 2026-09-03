# Operating the Lending v4 liquidation bot

This is the operator's guide to the auto-liquidation bot that ships inside the Aquarium
Node. It covers the modes, the two arming flags, the arithmetic that decides whether a
liquidation is worth doing, every configuration key, the endpoint you watch it through, the
reference-script prerequisite — and, at the end, an honest account of what the bot can and
cannot actually liquidate today.

> **⚠ THIS BANNER CHANGED ON 2026-09-03. Mainnet is now a supported posture — in SHADOW.**
> Lending v4 still ships `loans.enabled=false` on mainnet and the submit gate still defaults to
> preview (`loans.submittable-network`), so **a stock mainnet node still does nothing**. But those
> are now *defaults an operator may change* rather than a wall, and §9–§12 below are written for
> the operator who does. **Read §10 before arming anything on mainnet.**

---

## 1. The three modes

`loans.liquidation.mode` decides what the loop is allowed to do.

| Mode | What happens |
|------|--------------|
| `disabled` | The loop returns before it scans anything. No indexing work, no endpoint content. |
| `shadow` | Scan every lender bond, build a real `Liquidate` transaction per buildable candidate, price it, record the verdict. Nothing is signed and nothing is transmitted. |
| `live` | The same, and then — only if all eight submit vetoes pass — sign and submit. |

The value is case-insensitive and trimmed. **An unrecognised value aborts startup** rather
than falling back to a default: silently disabling a bot you meant to run, and silently
running one you meant to disable, are both worse than refusing to start.

**Shipped defaults:**

| | `mode` | `enabled` |
|---|---|---|
| base document (mainnet) | `disabled` | `false` |
| `preview` profile | `shadow` | `false` |

So a stock preview node scans, builds, prices and records, and cannot submit. A stock
mainnet node does not run the bot at all.

## 2. The two arming flags, and why there are two

Submitting requires **both**:

```
loans.liquidation.mode      = live     (AQUARIUM_LIQUIDATION_MODE)
loans.liquidation.enabled   = true     (AQUARIUM_LIQUIDATION_ENABLED)
```

They are deliberately separate and they are checked separately (vetoes S1 and S2 below).
One flag is too easy to flip by accident — a copied `.env` from a colleague, an
experiment left in place, a mode set to `live` "just to see the logs". Arming this bot is a
two-key turn, and neither key is ever turned by a commit: both defaults live in
`application.yaml` and are pinned by a test that fails if either is flipped
(`ShippedDefaultsTest`).

Submitting additionally requires the node to be on **preview** (veto S3). Mainnet is already
protected by `loans.enabled=false`, which stops the bot's beans existing at all; the network
veto is a second, independent line enforced in the code that would do the submitting.

## 3. The nine submit vetoes

Every cycle, for every candidate that built, the node evaluates nine checks in order. It
submits only if all nine pass. Each failure is recorded under its own name in the
`submit_veto` field of the decision, and the loop moves to the next candidate.

**The first four are POLICY** — statements about what the operator has authorised. The last five
are statements about *this candidate at this instant*. Policy comes first deliberately: on a node
held back by configuration, reporting a downstream symptom would send you looking at the wrong thing.

| # | Veto | Fires when |
|---|------|-----------|
| S1 | `MODE_NOT_LIVE` | `mode` is not `live` |
| S2 | `NOT_ARMED` | `enabled` is `false` |
| S3 | `NETWORK_NOT_PREVIEW` | the node's network is not `loans.submittable-network` |
| **S4** | **`MARKET_NOT_LIVE`** | **this loan's market is `SHADOW` or `DISABLED` on a node that is otherwise armed — see §9** |
| S5 | `NOT_PROFITABLE` | expected profit is not **strictly** greater than zero |
| S6 | `TX_TOO_LARGE` | the serialised transaction exceeds the live `maxTxSize`, **or that could not be established** |
| S7 | `ORACLE_WINDOW_TOO_SHORT_TO_SUBMIT` | less than `oracle-window-margin-seconds` of an oracle feed's window is left *at submit time*, **or that could not be established** |
| S8 | `STALE_UTXO` | the loan or bond UTxO is no longer unspent, **or that could not be established** |
| S9 | `TRANSACTION_WINDOW_ELAPSED` | the built transaction's own validity interval has already ended at submit time, **or its end could not be read** |

Read the bolded halves carefully. Submitting is the irreversible act — it burns the loan NFT
and moves someone's collateral — so **every ambiguous case resolves to not submitting**. A
Blockfrost timeout fetching protocol parameters is not evidence that the transaction fits.
An oracle registry that cannot be reached is not evidence that its feeds are fresh. An index
that throws has not said the output is still there.

Failing to submit costs a cycle. Submitting wrongly cannot be undone.

S6, S7 and S8 are re-checked at *submit* time, not at build time. A cycle scans, resolves
UTxOs, fetches protocol parameters and evaluates scripts before it gets anywhere near
submitting, and every one of those is a network round trip; the feed that was fresh when the
build started may not be by the time the transaction would land in a block.

S8 is not a duplicate of S6, and the gap it closes is the one that matters most in practice.
S6 can only speak about a loan that *has* an oracle feed — and an ADA/ADA loan has none, so
without S8 there would be no submit-time staleness check at all on exactly the shape that
builds today (see §8). Submitting an expired transaction was never dangerous — it is refused
in phase 1 and costs nothing — but it is not something this loop should do knowingly. Where a
feed does exist, S6 fires first and reports the more specific reason.

After any submit attempt the loan UTxO is quarantined for `quarantine-minutes`. **Any**
attempt: accepted, rejected, or thrown. The thrown case is the one the rule exists for — a
connection reset can arrive after the bytes have already gone out, so transmission status is
unknown, and that is exactly when you must not try again. The quarantine is taken *before*
the attempt, not on success. It is what stops the next cycle re-deriving the same candidate
from a local index that has not yet seen the spend and submitting a second transaction
against the same output.

**The quarantine is in-memory and per-process.** It protects one node from re-submitting; it
knows nothing about any other node. Do **not** run two Aquarium nodes armed on the same
wallet mnemonic against the same deployment: both would derive the same candidate and both
would submit. One of the two would lose harmlessly — its transaction names an already-spent
input, so it is refused in phase 1 at the mempool and no fee is paid — but you would be
relying on that rather than on the design. A restart also empties the quarantine, so give a
restarted node a cycle's grace before assuming its decision log is complete.

## 4. The profitability arithmetic

The bot's entire take is the **liquidation fee slice**: `collateralAmount ×
liquidationFeePerMille / 1000`, floored. That slice is denominated in the *collateral*
asset, so it is priced through the collateral leg's oracle feed before it can be compared
with a lovelace transaction fee. (For ADA collateral the feed is the synthesised 1:1 identity,
so the slice is already lovelace.)

```
expected_profit = fee_slice_in_lovelace − tx_fee − profit_margin_lovelace
```

S4 requires `expected_profit > 0`, strictly. Break-even does not submit.

**What the margin has to cover.** `profit-margin-lovelace` is not the bot's target income —
it is the buffer between "the ledger fee this transaction declares" and "what liquidating
actually costs you". It has to absorb:

- **the reference-script surcharge**, which is the dominant term. At
  `minFeeRefScriptCostPerByte = 15`, referencing all six validators (18,584 bytes) costs
  **~0.28 ADA per liquidation**. Referencing only the two large ones (12,749 bytes) costs
  ~0.19 ADA.
- collateral risk: a transaction that fails in phase-2 script evaluation forfeits its
  collateral, not just its fee.
- the value of a cycle spent on a candidate that later turns out to be unbuildable.
- ordinary fee variance between the build and the block.

The shipped 1.5 ADA leaves headroom for either reference-script shape and still refuses dust
liquidations, whose fee slice would not repay the transaction that claims it.

## 5. Every `loans.liquidation.*` key

| Key | Environment variable | Default | What it does |
|-----|---------------------|---------|--------------|
| `mode` | `AQUARIUM_LIQUIDATION_MODE` | `disabled` (preview: `shadow`) | See §1. An unrecognised value aborts startup. |
| `enabled` | `AQUARIUM_LIQUIDATION_ENABLED` | `false` | The arming flag. See §2. |
| `delay-seconds` | `AQUARIUM_LIQUIDATION_DELAY_SECONDS` | `60` | Fixed delay between cycles. |
| `validity-window-seconds` | `AQUARIUM_LIQUIDATION_VALIDITY_WINDOW_SECONDS` | `120` | How far past "now" the built transaction's validity interval extends. |
| `oracle-window-margin-seconds` | `AQUARIUM_LIQUIDATION_ORACLE_MARGIN_SECONDS` | `30` | How much of each oracle feed's window must still be unused after the transaction's `validTo` — and, at submit time, after *now*. |
| `profit-margin-lovelace` | `AQUARIUM_LIQUIDATION_PROFIT_MARGIN_LOVELACE` | `1500000` | See §4, and §11 for a **negative** value. |
| *(node-level)* `loans.submittable-network` | `LOANS_SUBMITTABLE_NETWORK` | `preview` | The only network this node will submit on. S3 enforces it. **The default is the protection**; changing it to `mainnet` is a deliberate act. |
| `decision-log-size` | `AQUARIUM_LIQUIDATION_DECISION_LOG_SIZE` | `200` | Capacity of the in-memory decision ring buffer. Nothing is persisted. |
| `quarantine-minutes` | `AQUARIUM_LIQUIDATION_QUARANTINE_MINUTES` | `30` | How long a loan UTxO is skipped after a failed build or any submit attempt. |
| `reference-scripts.loan` | `AQUARIUM_LIQUIDATION_REF_LOAN` | empty (preview: set) | See §6. |
| `reference-scripts.loan-spend` | `AQUARIUM_LIQUIDATION_REF_LOAN_SPEND` | empty (preview: set) | |
| `reference-scripts.lender-manager` | `AQUARIUM_LIQUIDATION_REF_LENDER_MANAGER` | empty (preview: set) | |
| `reference-scripts.lender-manager-spend` | `AQUARIUM_LIQUIDATION_REF_LENDER_MANAGER_SPEND` | empty (preview: set) | |
| `reference-scripts.loan-claim-action` | `AQUARIUM_LIQUIDATION_REF_LOAN_CLAIM_ACTION` | empty (preview: set) | |
| `reference-scripts.lm-liquidate-action` | `AQUARIUM_LIQUIDATION_REF_LM_LIQUIDATE_ACTION` | empty (preview: set) | |
| `reference-scripts.asset-manager` | `AQUARIUM_LIQUIDATION_REF_ASSET_MANAGER` | empty | Accepted but not needed; see §6. |

**Window arithmetic.** `validity-window-seconds` and `oracle-window-margin-seconds` are one
piece of arithmetic, so read them together. A preview Charli3 feed publishes a 600 s window,
and the builder demands `feed.validTo − tx.validTo ≥ margin`. A candidate is therefore only
buildable while at least `validity-window + margin` of the feed's window is still ahead of
it: 120 + 30 = 150 s of the 600, leaving 450 s usable. Raising the margin shortens that
usable stretch second for second; a margin near 600 silently disables the bot for most of
every window, with every candidate reporting `ORACLE_WINDOW_MARGIN_TOO_SMALL`.

## 6. The reference-script prerequisite

**Without published reference scripts the bot cannot submit anything, ever.** This is not a
tuning knob; it is the difference between a buildable and an unbuildable transaction.

A `Liquidate` invokes six validators. If none of them is published, all six travel in the
transaction's witness set: **18,584 bytes of validators against a `maxTxSize` of 16,384**. A
real single-loan liquidation measured in shadow mode came to **19,838 bytes** with no
reference scripts. Veto S5 refuses it, correctly, every time.

Publishing them moves that weight from *size* into *fee*: at
`minFeeRefScriptCostPerByte = 15`, referencing all six costs ~0.28 ADA per liquidation, which
`profit-margin-lovelace` has to cover (§4).

Each key takes a `txHash#index` coordinate. **A malformed value aborts startup and names the
key** — a typo that quietly became "not published" would put a 3 kB validator back in the
witness set with no symptom but a repeated size veto.

The preview profile ships six coordinates that were verified on chain: each UTxO's
`reference_script_hash` equals the hash this node derives for that validator.
`LoansReferenceScriptVerifier` re-checks that at **every startup** and hard-fails on a
mismatch — because a stale coordinate means building against somebody else's validator, and
that transaction does not merely fail, it fails in phase-2 evaluation with the collateral
already forfeit. When FluidTokens redeploys v4, that startup failure is the answer, not an
outage: update the coordinates.

`asset-manager` is deliberately left empty. A plain `Liquidate` only *creates*
asset-manager outputs and never spends one, so its script is neither attached nor needed;
publishing a coordinate for it would add a reference input and its fee for nothing.

## 7. Watching it: `GET /api/v1/loans/liquidations`

```
GET /api/v1/loans/liquidations?limit=50&include_cbor=false
```

Top level:

| Field | Meaning |
|-------|---------|
| `mode` | `DISABLED` / `SHADOW` / `LIVE` |
| `armed` | `mode == LIVE && enabled` — the two-key turn of §2 |
| `last_run_at` | when the last cycle ran |
| `bonds_scanned` | every lender bond the last cycle looked at |
| `buildable` | how many of them were liquidation candidates |
| `exclusions` | histogram of why the rest were not, keyed by exclusion name |
| `decisions` | newest first, one per candidate that was acted on |

`bonds_scanned` always reconciles: every scanned bond is either counted in `exclusions` or
eligible to appear in `decisions`. Excluded bonds never produce a decision row, or a node
watching a few hundred healthy loans would evict every interesting row with
`NOT_LIQUIDATABLE` noise on every cycle.

### Reading one decision

| `outcome` | Meaning |
|-----------|---------|
| `NO_UTXO` | one of the two UTxOs was already spent; nothing was built |
| `REFUSED` | the builder refused, or building threw. `reason` carries the exact refusal name |
| `UNPROFITABLE` | built and priced; the fee slice does not clear the tx fee plus the margin |
| `WOULD_SUBMIT` | built, priced, worth doing — and not submitted because the bot is not armed for it. `submit_veto` says which of S1–S3 |
| `SUBMIT_VETOED` | armed, and still not submitted: `submit_veto` names S5, S6, S7 or S8 |
| `SUBMITTED` | signed and accepted. `tx_hash` is what went out |
| `SUBMIT_FAILED` | signed, transmitted, rejected. `detail` carries the backend's response |

The pair to read together is `outcome` and `submit_veto`. `WOULD_SUBMIT` next to
`armed: false` and `submit_veto: MODE_NOT_LIVE` is shadow mode working exactly as intended:
the transaction was built and priced, and then deliberately dropped. `SUBMIT_VETOED` with
`TX_TOO_LARGE` is an armed bot declining — check §6.

The numeric fields (`expected_fee_lovelace`, `tx_fee_lovelace`, `margin_lovelace`,
`expected_profit_lovelace`, `tx_size_bytes`, `inputs`, `outputs`, `reference_inputs`,
`redeemers`) are populated only when a transaction was actually built.
`include_cbor=true` adds `tx_cbor_hex`, the whole unsigned transaction — kilobytes of hex,
so it is opt-in and omitted outright rather than sent as null.

The log is in memory and bounded by `decision-log-size`. A restart loses it.

## 8. What this bot can and cannot liquidate today

This section is the honest one. Read it before you conclude the bot is broken.

A v4 loan becomes liquidatable when `currentLtv > liquidationLtv`, or when a repayment is
late. On the loans currently indexed on preview, `liquidationLtv` is **80%**. So the on-chain
window opens at 80% LTV.

The bot cannot build a transaction for most of that window.

The reason is the **borrower's equity refund**. On a partial liquidation the borrower is
owed back:

```
equity = collateral − debt − debt × partialLiquidationPenaltyPerMille / 1000
```

Whenever that is positive, the transaction has to carry an equity output — and the
*currently deployed* validators cannot accept one. `lm_liquidate_action` and
`loan_claim_action` both claim the loan-index slot of the same asset-manager-filtered output
list, and they want mutually exclusive datums in it. No output ordering satisfies both. This
was verified by running both output layouts through the real PlutusV3 machine and watching
each validator refuse the other's. The builder therefore refuses any candidate with positive
equity, under the name `POSITIVE_EQUITY_UNSUPPORTED`.

That leaves exactly the loans where equity is **zero**:

```
collateral − debt − debt × 0.100 ≤ 0
⇔ debt / collateral ≥ 1 / 1.1
⇔ LTV ≥ 90.91%
```

using the observed `partialLiquidationPenaltyPerMille` of **100**, which is uniform across
all **56** indexed preview loans. **Zero** of those 56 carry the negative penalty that would
disable equity outright and widen this band.

So, concretely, on preview today:

- **Liquidatable and buildable: LTV ≥ 90.91%.** These are the only candidates the bot can
  produce a transaction for.
- **Liquidatable but unbuildable: 80% ≤ LTV < 90.91%.** On-chain these loans are fair game;
  this bot refuses them with `POSITIVE_EQUITY_UNSUPPORTED`.
- **Unbuildable regardless of LTV: every late-payment liquidation of a healthy loan.** A loan
  can be late while its collateral comfortably covers its debt — which means positive equity,
  which means the same refusal. Lateness does not help.

None of this is a bug in the bot and none of it is fixable off chain. It resolves the day
FluidTokens redeploys validators that can carry an equity output; the transaction the builder
already constructs for those cases is the structurally correct one, and it becomes
submittable at that point without a code change here.

Two phrasings to avoid, because both are wrong in ways that matter: the bot's reach is not
"underwater loans" (too narrow — 90.91% LTV is not yet underwater, and the band above it
includes loans with real collateral cushion), and it is not "late or over-LTV loans" (wrong
— that is the on-chain rule, and it is precisely the set the bot mostly *cannot* address).

### One more preview reality

Only the three Charli3 (`c3`) oracle feeds are live on preview; every multisig preview feed
is months stale. There is a real ~60–80 s price blackout every five minutes, upstream of
this node, during which candidates report oracle-window refusals. That is the registry, not
the bot.

---

## 9. Per-market policy — `loans.liquidation.markets`

A **market** is a loan's **principal asset**. Each entry decides two independent things: whether the
bot may act there (`mode`), and which transaction it would build (`action`).

```yaml
loans:
  liquidation:
    markets:
      - unit: lovelace          # "lovelace", or policyIdHex + assetNameHex
        mode: SHADOW            # DISABLED | SHADOW | LIVE — omit to inherit the node mode
        action: CONVERT         # CONVERT | ANTICIPATE — default CONVERT
        cap: 1000000000         # MANDATORY iff action: ANTICIPATE, meaningless otherwise
```

**An unlisted market is `action: CONVERT` at the node's own mode.** An absent or empty list therefore
means *"convert everywhere, at whatever posture the node is in"*, and **listing a market is how you
deviate.** In particular `loans.liquidation.mode: shadow` shadows **every** market with no list at all
— you never have to enumerate markets to rehearse.

**⛔ The node mode is a CEILING, not a default:**

```
effective(market) = min(loans.liquidation.mode, market.mode)     DISABLED < SHADOW < LIVE
```

A market may be **more** restrictive than the node and **never less**. A market asking for `LIVE` on a
`shadow` node runs as `SHADOW`, and says so loudly at boot — so an operator who wrote `LIVE` and got
`SHADOW` is told, rather than believing they armed something they did not.

**`action` partitions only the loans whose lender bond permits conversion.** A bond with
`shouldLiquidationConvertToPrincipal = false` gets a plain `Liquidate` whatever the market says — the
lender picks the class, you pick the mechanism within it.

**When to force `ANTICIPATE`.** FluidTokens' guidance, first-hand: *for tokens that do not have a
reliable pool on Minswap*, because the swap order must always deliver at least the minimum the lender
expects. **Pool reliability is your judgement — the bot does not detect it.** A mis-set `CONVERT`
market is not a loss: an order that cannot fill returns the original collateral to the lender.

**A malformed entry ABORTS STARTUP** — a missing `cap` on an `ANTICIPATE` market, an unknown `mode`, a
`unit` that is neither `lovelace` nor a well-formed hex unit, or a duplicated unit. That is only
defensible because the fields are named: every failure is unambiguous, so quietly disabling the market
would hide your error rather than protect you from it.

### 9.1 ⚠ A YAML list is not one environment variable

Every other knob here is a single env var. **This one is not.** In Docker or Kubernetes you need
either **indexed environment variables**:

```
LOANS_LIQUIDATION_MARKETS_0_UNIT=lovelace
LOANS_LIQUIDATION_MARKETS_0_MODE=SHADOW
LOANS_LIQUIDATION_MARKETS_0_ACTION=CONVERT
LOANS_LIQUIDATION_MARKETS_1_UNIT=577f0b13…0014df10464c4454
LOANS_LIQUIDATION_MARKETS_1_ACTION=ANTICIPATE
LOANS_LIQUIDATION_MARKETS_1_CAP=500000000
```

…or a **mounted YAML fragment**. Both work; only the first fits the existing `docker/.env` shape.
The relaxed `SCREAMING_SNAKE` mapping is a property of the environment property source specifically,
and it is asserted by a test that binds through a real one — not by a test that merely sets properties
with the same names.

---

## 10. ⛔ SHADOW IS NOT A DRY RUN — it is the in-situ final check

In `SHADOW` — at node level or for one market — a candidate is **scanned, built, priced, size-checked,
evaluated and recorded** exactly as a live one. Only the submission is withheld. Two lines land in the
log on a stable prefix:

```
SHADOW TX <loan> variant=… held-by=MARKET_NOT_LIVE size=NNNNB(unsigned, grows once signed)
  exunits=[total=…/… each=Spend:0=… Reward:1=…] economics=… — PHASE-2 ONLY: the scripts
  evaluated, but the ledger's phase-1 rules (fee, min-ada, witnesses, collateral) are NOT
  proven by this. Nothing was signed and nothing was submitted.
SHADOW CBOR <loan> <hex>
```

`SHADOW CBOR` is the complete unsigned transaction. Decode it, check the outputs, check the order
datum. It is byte-identical to what the decision log records at `GET /api/v1/loans/liquidations`.

**⛔ AND FOR THE CONVERT PATH THIS IS THE LAST CHECK BEFORE REAL FUNDS MOVE.**

A convert is by definition the exchange of **two different assets**, so at least one leg is not ADA, so
the FluidTokens oracle validator must execute as part of the transaction.

> ⚠ **An earlier version of this section said that made convert "not offline-provable" and that shadow
> was "the ONLY proof there will ever be". That was wrong and is retracted** (findings §40). The oracle
> feed, its validity window and its signature are all **published** by the FluidTokens oracle API — the
> one this node already consumes — so a convert **can** be evaluated offline against live-fetched
> reference inputs, with real ex-units, *before* anything is deployed.

**⇒ So there are two proofs, and they answer different questions.** The offline dry-eval says *"this
transaction's scripts pass"*; shadow says *"this transaction, against the chain's UTxOs and protocol
parameters at this instant, passes"*. **Shadow is the in-situ final check, not a substitute for the
first and not made redundant by it.** The rule for a mainnet convert deploy is still:

> **Deploy in SHADOW. Read the `SHADOW TX` line. Verify `exunits` are real. Decode the `SHADOW CBOR`.
> Only then arm.**

**⚠ How to tell a real dump from a worthless one.** If the transaction was built without an evaluator
the ex-units are cardano-client-lib's placeholders and prove nothing, so the line carries a marker and
an ERROR beside it:

```
exunits=[⛔NOT-VALIDATED:PLACEHOLDER-EX-UNITS(CCL-trap-8) total=…]
⛔ THE SHADOW DUMP ABOVE PROVES NOTHING: every redeemer carries cardano-client-lib's placeholder
   budget, so no evaluator ran. …
```

**If you see that marker, the rehearsal did not happen.** Do not arm. Real budgets are hundreds of
thousands of mem and hundreds of millions of steps; the placeholder is `10000` mem with `10000` or
`1000` steps.

---

## 11. Operating at a stated loss — the protocol-health mode

Giovanni's ruling, 2026-09-03: *"it's fundamental to allow operators to operate at a loss. Protocol
must be kept bad-loss-free at all costs … operating at a loss MUST be implemented even on mainnet."*

A **negative** profit margin is honoured on **every network**, mainnet included. It is not a
protection you switch off — it is a **bound you state**: a candidate worse than the figure you name is
still refused.

| path | key | default | at the default |
|---|---|---|---|
| liquidation | `loans.liquidation.profit-margin-lovelace` | `1500000` (yaml) / `5000000` shipped | refuses every loss |
| compound | `loans.compound.profit-margin-lovelace` | `0` | refuses every loss |
| convert | `loans.liquidation.convert.profit-margin-lovelace` | `0` | refuses every loss |

**The defaults are the protection, not a guard.** Only an *explicitly negative* value operates at a
loss, and no copy-paste of a zero or positive configuration can produce one. A node that does state one
announces it at boot, on every network, and more loudly on mainnet:

```
⛔ OPERATING AT A LOSS ON MAINNET, BY OPERATOR CONFIGURATION — path: convert;
   loans.liquidation.convert.profit-margin-lovelace = -4000000 lovelace (network mainnet). …
```

**One knob is NOT a margin and stays fatal**: `loans.liquidation.convert.dex-cost-floor-lovelace`
(default `5000000`) states what one Minswap interaction *costs*, not what you are willing to lose. A
negative value there is a typo with no meaningful reading, and it aborts startup.

---

## 12. The convert path, end to end

> **⛔ NOT YET REACHABLE FROM A RUNNING NODE — read this before configuring anything below.**
> As of 2026-09-03 the convert path is **built and proven structurally, and has no production
> caller.** `ConvertTransactionBuilder`, `ConvertOrderPlan` and `ConvertEconomics` exist, are tested
> and are correct as far as offline proof can reach — but **no executor routes a candidate to them**,
> so a deployed node will never build a convert and will never emit a convert `SHADOW TX` line.
> `PayInAdvanceLiquidationRouter` still routes every convert-eligible candidate to pay-in-advance.
>
> **⇒ Everything in this section is the contract the wiring will honour, not behaviour you can
> observe today.** The executor wiring — routing on the market's `action`, locating the Minswap pool
> by its NFT at scan time, supplying the convert action's reference script, and consulting
> `ConvertEconomics` with the built transaction's fee — is the remaining stage. Until it lands,
> configuring `loans.liquidation.convert.*` changes nothing.


**What it does.** For a loan whose lender bond permits conversion, it liquidates and — in the same
transaction — creates a Minswap V2 swap order that turns the collateral into the lender's principal.
Both the success and refund receivers are the **lender's** asset manager.

**⇒ The bot fronts nothing and holds nothing.** Its income is the liquidation fee, taken **before** the
swap, **in the collateral token**. FluidTokens confirmed the failure mode first-hand: an order that
does not fill returns the **original collateral** to the lender, who reclaims it. **The bot is finished
the moment the order is created**, and because its fee is taken before the swap, **its position is the
same whether the order fills or not.** There is no pending-order state to track.

**What it costs the bot**, and the second term surprises people:

```
outlay = max( txFee + (collateral is ADA ? 0 : 2_800_000) , dex-cost-floor )
```

For a **non-ADA** collateral the validator requires exactly **2.8 ada** in the order output alongside
the tokens, and that ada leaves with the order. The floor (default 5 ada) then rounds the whole DEX
interaction up, covering the batcher fee whose incidence the measurement cannot attribute.

**What it earns**, valued at the collateral oracle price:

```
approved  ⟺  feeValueLovelace − outlay  ≥  profit-margin-lovelace
```

⚠ **The income is a token position valued by an oracle, and the outlay is ada you actually spent.**
That asymmetry is deliberate and was ruled acceptable at transaction time. **The margin is your lever
on it**: if you do not trust the price, or the liquidity behind it, raise the margin until the
ada-equivalent is worth the exposure.

### 12.1 Convert keys

| Key | Environment variable | Default |
|---|---|---|
| `loans.liquidation.convert.enabled` | `LOANS_LIQUIDATION_CONVERT_ENABLED` | **`true`** |
| `loans.liquidation.convert.profit-margin-lovelace` | `LOANS_LIQUIDATION_CONVERT_PROFIT_MARGIN_LOVELACE` | `0` |
| `loans.liquidation.convert.dex-cost-floor-lovelace` | `LOANS_LIQUIDATION_CONVERT_DEX_COST_FLOOR_LOVELACE` | `5000000` |
| `loans.minswap.pool-policy-id` | `LOANS_MINSWAP_POOL_POLICY_ID` | FluidTokens' verified **mainnet** value |
| `loans.minswap.pool-spend-script-hash` | `LOANS_MINSWAP_POOL_SPEND_SCRIPT_HASH` | ″ |
| `loans.minswap.order-spend-script-hash` | `LOANS_MINSWAP_ORDER_SPEND_SCRIPT_HASH` | ″ |

**`convert.enabled` is the one arming flag in this codebase that defaults ON.** That is not a
weakening: it only matters on a node that has already passed `loans.enabled`,
`loans.liquidation.mode`, `loans.liquidation.enabled` and `loans.submittable-network` — and this path
fronts no capital and holds nothing, so its failure mode is a no-op rather than a loss.

### 12.2 Is convert available on this node? The boot log answers it

The Minswap coordinates are **network-specific**, so a node carrying mainnet's while running elsewhere
derives a real, well-formed hash **for the wrong deployment**. That is reported, never fatal — it
disables one path and breaks none:

```
CONVERT AVAILABLE:   lm_liquidate_and_convert_action derives X and the LMConfigDatum publishes the same
CONVERT UNAVAILABLE: loans.minswap.* is not set
CONVERT UNAVAILABLE: derives X but this deployment's LMConfigDatum publishes Y — the configured
                     loans.minswap.* coordinates belong to a DIFFERENT network's Minswap deployment
```

**Every preview node today prints the third line**, and correctly: preview has no Minswap deployment at
all, so convert is unavailable there by nature and cannot be rehearsed on preview. That is precisely
why §10's mainnet-shadow step exists.

---

## 13. Coherence across the three paths

Three independent actions share this node, and they are armed independently. Before a mainnet deploy,
know which is which:

| | plain **Liquidate** | **Convert** | **Pay-in-advance** |
|---|---|---|---|
| bot fronts capital | no | no | **yes — the whole principal** |
| bot ends up holding | the collateral | nothing | **the collateral** |
| income | fee, in collateral | fee, in collateral | the discount, in collateral |
| reachable when | bond flag false, or true | bond flag **true** | bond flag **true** AND market `action: ANTICIPATE` **with a cap** |
| default margin | refuses loss | refuses loss | refuses loss |
| per-market cap | — | — | **required** |

**Compound** is separate again: it collects an already-repaid principal into the lender's pool, fronts
nothing, and is gated by `loans.compound.enabled` (default `false`) plus its own margin.

**⇒ Pay-in-advance is unreachable unless you explicitly force it per market.** With convert as the
default for the convert-eligible class, `ANTICIPATE` is an escape hatch for markets with no viable
Minswap pool — not a path you fall into.

---

## Related documents

- `docs/auto-liquidation-design.md` — the design, including the finance model and the
  liquidation transaction anatomy.
- `docs/lending-v4-findings.md` — everything verified against chain or Aiken source. Where
  the upstream README and the validators disagree, the validators win.
