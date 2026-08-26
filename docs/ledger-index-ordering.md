# How the ledger orders inputs, reference inputs and redeemers — the measurement

> **T-051, first task.** Giovanni's guideline said *"calculate indexes by sorting inputs
> alphabetically (all indexes have criteria about they are sorted like inputs, ref inptus, and I
> guess redeemers too? anyway)"* — and flagged its own uncertainty. **This is the test of that
> hypothesis, not an implementation of it.** Result: **confirmed for four entity kinds, refuted
> for the fifth — and the fifth is the only one the two-pass probe exists for.**

## Instruments

Three, deliberately of different provenance, because a single one cannot discriminate:

1. **A transaction the chain accepted** — `49743a1e9ef4b0e7756f2143f89fbb2e1a4e274d8a19068ae5d6f0e5244755f7`
   (preview, `is_valid = true`), decoded from CBOR: 3 inputs, 6 reference inputs, 5 withdrawals,
   1 mint policy, 5 outputs, 8 redeemers.
2. **cardano-client-lib 0.7.2 source**, checked out at tag `v0.7.2`.
3. **The docs corpus** — `cardano-dev-skills/docs/sources/developer-portal/developers/curriculum/`
   `smart-contracts/advanced/design-patterns/utxo-indexers.md:23`, and Ogmios'
   `local-tx-submission.md:85` for the redeemer-pointer entity kinds.

The corpus states the rule the other two must agree with:

> *"the ledger does not preserve input order. A transaction's inputs are a set, **re-sorted
> lexicographically by (transaction hash, output index)** before the validator ever sees them, so
> on-chain code can never rely on the order in which the builder added inputs… **Outputs are the
> opposite: they are a list that keeps exactly the order the builder created.**"*

## The result

| entity | ledger ordering | who sorts, at 0.7.2 | our status |
|---|---|---|---|
| **inputs** (Spend redeemer) | **set** — re-sorted lexicographically by `(txhash, ix)` | CCL: `RedeemerUtil.getSortedInputs` sorts a copy before `indexOf` | ✅ CCL owns it |
| **reference inputs** | **set** — same rule | **nobody in CCL.** Zero sort hits under `function/`, `quicktx/`, `transaction-spec/` main sources | ✅ **we** sort, `TransactionInputComparator`, and take every index off the sorted list |
| **withdrawals** (Reward redeemer) | **map** — key order, reward-account bytes | CCL sorts explicitly *then* assigns `size()-1` (`StakeTx:457-472`), with a comment saying why | ✅ correct by construction |
| **mint** (Mint redeemer) | **map** — key order, policy id | ⚠ **two paths disagree** — see below | ✅ safe today, latent |
| **outputs** | **list — builder order preserved exactly** | nothing to sort; the order *is* the construction order | ⚠ the probe's only remaining reason |

Every ordering claim above was checked against the accepted transaction and held: inputs,
collateral and reference inputs all lexicographically sorted; withdrawals sorted by reward-account
bytes; each redeemer index resolving to the entity it names.

### Verified redeemer → entity mapping on the accepted transaction

```
tag=0 (Spend)       index=0  -> input  3d839207c0f38283…#1
tag=0 (Spend)       index=1  -> input  3d839207c0f38283…#3
tag=1 (Mint)        index=0  -> policy 2f1aa941f437e351…
tag=3 (Withdrawal)  index=0..4 -> the 5 reward accounts, in sorted order
```

## ⇒ What this means for T-051

**Giovanni's hypothesis is confirmed where he applied it and refuted where the problem actually
is.** Sorting is the right rule for inputs, reference inputs, withdrawals and mint policies — and
for lowercase hex, lexicographic *is* alphabetical, so his wording is exact. But:

- **The reference-input half is already done.** `referenceInputs()` already returns a canonically
  sorted list and `refIndex()` already resolves against it. That half is already single-pass.
- **The two-pass probe exists only for OUTPUT indexes** — `lenderBondOutputIndex` and the
  asset-manager output indexes. **Outputs are a list, so there is nothing to sort.** Sorting
  cannot delete the probe.

**But it does not need to.** Because outputs preserve builder order *exactly*, their positions are
**constructed, not discovered**. Only two things displace them, and both are now measured:

1. **One dummy output, prepended, when withdrawals are present.** `StakeTx:292-295` adds a single
   `DepositRefundContext` under `if (withdrawalContexts.size() > 0)` — **one per transaction, not
   one per withdrawal.** Measured: 5 withdrawals → exactly 1 dummy, at index 0, 1,000,000 lovelace
   at the change address.
2. **Change, appended last.** Measured at index 4.

Measured layout of the accepted transaction, which is the whole rule:

```
[0] dummy   1,000,000       change addr, ada-only, no datum   ⇐ CCL, because withdrawals exist
[1] ours    1,810,200       + 1 asset, inline datum
[2] ours    1,655,040       + 1 asset, inline datum
[3] ours    1,771,410       + 1 asset, inline datum
[4] change  9,964,993,434   + 1 asset                          ⇐ CCL, appended
```

⇒ **`ourOutputBase = withdrawalsPresent ? 1 : 0`, our outputs at `base + insertion order`, change
last.** That is computable before building, which is Giovanni's outcome — *"we shouldn't build tx
twice"* — reached by construction rather than by sorting.

## ⚠ A latent CCL defect found on the way, dormant only because we mint under one policy

**CCL has two mint-redeemer-index paths and they do not agree.**

- `ScriptCallContextProviders:122-124` sorts first: `MintUtil.getSortedMultiAssets(...)` then
  `getIndexByPolicyId`. ✅
- `ScriptTx:843-846` — **the path `mintAsset` actually takes** — indexes into
  `transaction.getBody().getMint()` **in unsorted list order**.

The body's mint field serialises in list order too (`TransactionBody.serialize`, no sort), while
the ledger reads it as a map and indexes the **sorted** policy ids. `MintUtil`'s own javadoc states
the invariant `ScriptTx` breaks: *"The multiAssets list in mint field of transaction is sorted by
the policyId."*

**⇒ A transaction minting under two or more policies, added in non-sorted order, gets a Mint
redeemer index pointing at the wrong policy.** Both our builders make exactly one `mintAsset` call
under one policy (`registry.getLoanScript()`), so the index is 0 under either rule and we are safe.
**This is recorded, not fixed** — it is CCL's, it is dormant, and inventing a second policy to
exercise it is not this ticket.

**⚠ And it could not have been found from the transaction.** With one mint policy, sorted and
unsorted are the same answer; `n=1` cannot discriminate between two rules that agree at `n=1`. It
came from the source, and the transaction was consistent with both hypotheses.

## What this measurement does NOT establish

- **That the computed output layout survives every path.** It is measured on *one* accepted
  transaction of *one* shape. The withdrawal merge branch (`StakeTx:479-...`) folds a withdrawal
  receiver into an *existing* output at the same address when one is present — our receivers are
  all the change address and all amounts are zero, so they folded into the dummy. **A builder that
  pays its own change address before withdrawing would see a different layout**, and neither
  builder does that today.
- **That the ledger sorts the mint map rather than trusting serialised order.** Taken from the
  corpus and the CDDL map semantics, not measured — our `n=1` cannot see it.
- **Anything about the convert path's layout.** Only the plain path has an accepted transaction.
