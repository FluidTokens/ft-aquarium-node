# Operating the Lending v4 liquidation bot

This is the operator's guide to the auto-liquidation bot that ships inside the Aquarium
Node. It covers the modes, the two arming flags, the arithmetic that decides whether a
liquidation is worth doing, every configuration key, the endpoint you watch it through, the
reference-script prerequisite — and, at the end, an honest account of what the bot can and
cannot actually liquidate today.

> **Preview only.** Lending v4 is disabled on mainnet (`loans.enabled=false`), and the bot
> refuses to submit on any network but preview regardless. Nothing in this document applies
> to a mainnet node.

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

## 3. The eight submit vetoes

Every cycle, for every candidate that built, the node evaluates eight checks in order. It
submits only if all eight pass. Each failure is recorded under its own name in the
`submit_veto` field of the decision, and the loop moves to the next candidate.

| # | Veto | Fires when |
|---|------|-----------|
| S1 | `MODE_NOT_LIVE` | `mode` is not `live` |
| S2 | `NOT_ARMED` | `enabled` is `false` |
| S3 | `NETWORK_NOT_PREVIEW` | the node is not on preview |
| S4 | `NOT_PROFITABLE` | expected profit is not **strictly** greater than zero |
| S5 | `TX_TOO_LARGE` | the serialised transaction exceeds the live `maxTxSize`, **or that could not be established** |
| S6 | `ORACLE_WINDOW_TOO_SHORT_TO_SUBMIT` | less than `oracle-window-margin-seconds` of an oracle feed's window is left *at submit time*, **or that could not be established** |
| S7 | `STALE_UTXO` | the loan or bond UTxO is no longer unspent, **or that could not be established** |
| S8 | `TRANSACTION_WINDOW_ELAPSED` | the built transaction's own validity interval has already ended at submit time, **or its end could not be read** |

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
| `profit-margin-lovelace` | `AQUARIUM_LIQUIDATION_PROFIT_MARGIN_LOVELACE` | `1500000` | See §4. |
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

## Related documents

- `docs/auto-liquidation-design.md` — the design, including the finance model and the
  liquidation transaction anatomy.
- `docs/lending-v4-findings.md` — everything verified against chain or Aiken source. Where
  the upstream README and the validators disagree, the validators win.
