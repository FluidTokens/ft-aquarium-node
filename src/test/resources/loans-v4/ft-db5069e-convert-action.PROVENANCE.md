# `ft-db5069e-convert-action-unapplied.hex`

The **unapplied** compiled code of
`lender_manager/lm_liquidate_and_convert_action.actionValidator.withdraw`, taken verbatim from
`plutus.json` as committed at **FluidTokens/ft-cardano-loans-v4 `db5069e`** — the merge of PR #14,
the fix for the Minswap batchability defect (findings §57.9).

**What it is for.** Applied to the eleven mainnet parameters it must derive
`c3f51e55dd156a4c29a41df0d630b0d8f1c96f396f5a317788a94b70`, which is what the LMConfigDatum publishes
at field 5 and what is published as a reference script at
`8ab0c6d168746f5827aeb0d8981f9edbb9ae00a17db95d7d44e8a8a1b86ea0bf#0`.

⚠ **Kept alongside `ft-bb4349c-convert-action-unapplied.hex` rather than replacing it.** That one is
the superseded build and its test still earns its place: it proves the fix *changes the compiled
output*, which is the thing a version bump alone can never demonstrate.

**The change it carries** (`>=` rather than `==` — FluidTokens' own improvement on the reported fix):

```aiken
const minswap_order_overhead: Int = 4_000_000
max_batcher_fee: 2_000_000
… >= swappableCollateralAmount + minswap_order_overhead     // ada collateral
… quantity_of(value, "", "") >= minswap_order_overhead      // token collateral
```
