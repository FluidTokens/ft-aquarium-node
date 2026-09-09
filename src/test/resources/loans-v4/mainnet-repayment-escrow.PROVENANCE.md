# The first mainnet repayment escrow — `42a0ca18…#0`, 2026-09-04

`mainnet-repayment-escrow-42a0ca18.hex` is the inline datum of **output 0 of the transaction that
repaid the only mainnet Lending v4 loan**, copied verbatim from Blockfrost.

```
tx      42a0ca18aac5de654f17df3bdeac14ed9463bf4139a47ba3bd40c7c763dc9e09
time    2026-09-04 08:52:48 UTC
output  0 — 25,000,000 lovelace, ada-only, at 3644da1d… (assetManagerSpendScriptHash)
datum   AssetManagerDatumWithToken {
          inputOutputReference: d832b78e…#1        ← the loan UTxO that was repaid
          action:               "installment_repayment"
          data:                 … loan 1b6fda50…, 20,000,000 principal …
          ownerAsset:           bcd713bb…(lenderBondPolicyId) + 1b6fda50…(loanId)
        }
```

**It was a REPAYMENT, not a liquidation.** The transaction's reward redeemer is
`e4b067a674e2d695d4f7ae732103abb8d2b52cc10bd4922d7412ba19` — `loanRepayActionScriptHash`. The bot
was never armed and never acted; the borrower simply paid.

## Why this fixture matters

It is **the first real mainnet artefact the compound path has ever had.** Findings §54.5 recorded
that the path was *structurally* empty — one loan ever minted, never burned, therefore no escrow —
and that stopped being true at 08:52:48 UTC. See §56.

⚠ **The economics did not change with it.** The only live mainnet pool manager still publishes
`compoudingFeePerMille = 0`, so a compound of this escrow pays nothing and is refused at the shipped
margin. `MainnetCompoundEscrowTest` asserts both halves separately on purpose: *there is finally
something to compound* and *compounding it is worth doing* are different questions.

⚠ **This datum is checked against the live chain by that test rather than trusted.** Two recorded
config datums have already aged out from under this suite (§53, and the LM config on 2026-09-04),
and both surfaced as reds only because something derived independently was compared against them.
