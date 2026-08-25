# SLICE CONTRACT — positive-equity liquidation (loan `69aee8a0…#1`)

**Priority: TOP** (Giovanni, 2026-08-19, via FluidTokens/Matteo Coppola Mazzetti). Written while T-021
is in flight on the same file; **dispatch only when T-021 lands or is parked.** Baseline: whatever T-021
commits, else `e372ab2`.

## GOAL
Make `LiquidateTransactionBuilder` able to liquidate a loan with **positive equity**, by emitting the
borrower-compensation output the deployed validators require, and lift the scanner's
`POSITIVE_EQUITY_UNSUPPORTED` exclusion **only once the builder proves it**. Acceptance is a dry-eval
against the real deployed validators using **this loan's real on-chain data**.

## THE TARGET LOAN (all DERIVED from chain, 2026-08-19)
`69aee8a016d4d20a49404486c12d8986d7ba4b7bac520840a966f1169a2eecd6#1`, **UNSPENT**.
Principal **28,000,000 lovelace**; collateral **100,000,000 tFLDT units**; `interestRate 459`; LTV
`100/125`; penalty `100`. At the live c3 price (338163/1000000) collateral = 33,816,300 lovelace →
**LTV 0.8280 vs 0.8000 → liquidatable, and LTV < 1 so equity > 0** (≈3 ADA ≈ 9 tFLDT — the slice must
compute it properly, see below).
Bond: `shouldLiquidationConvertToPrincipal = **False**` (so plain `Liquidate` is the right action, and
this loan is *not* in the unserviceable True-bond population) and **`liquidationFeePerMille = 0`**.

## ⚠ WHAT THE OPERATOR MUST KNOW — put this in the javadoc and the operator docs
**The fee on this loan is ZERO.** Liquidating it earns nothing and costs ~1.4 ADA in transaction fees.
Even with this slice complete, **the node will only act on it under a negative
`profit-margin-lovelace`** (the preview override is −3,000,000; the shipped default is +1,500,000). That
override is **preview-only and must never reach mainnet.** This slice buys a *capability*, not revenue —
say so plainly wherever an operator will read it.

## WHAT THE VALIDATORS REQUIRE — read from source, do not re-derive from scratch
`loan_claim_action.ak:271-295` gates on `or { inputAction.equity == 0, { …equity_sent_to_borrower… } }`.
The output is located as `safe_list_at(get_outputs_to_smart_credential(self.outputs,
Script(assetManagerSpendScriptHash), Script(assetManagerWithdrawScriptHash), smartTokensSpendScriptHash),
index)` — i.e. **the `index`-th entry of the asset-manager-credential-filtered output list**, where
`index` is the loan's position among the loan inputs.

`equity_sent_to_borrower(...)` (same file) requires **all four**, and the slice must satisfy each
explicitly rather than by luck:
1. **Inline datum, byte-exact.** `equals_data(outputDatum, AssetManagerDatumWithToken{
   inputOutputReference: <the LOAN INPUT's own output_reference>, action:
   `constants.action_partial_liquidation_compensation`, data: **None**, ownerAsset: Asset{
   borrowerBondPolicyId, **loanId** } })`. Note this differs from the lender's collateral output on two
   axes — a different `action` constant and `data: None` — so it is **not** a copy of the existing datum
   builder.
2. **Amount:** `quantity_of(value, equityAssetPolicyId, equityAssetName) >= equity`. Note **`>=`**, not
   equality. `equityAssetPolicyId = collateral.policyId` because `equityInPrincipalCurrency == False`
   (if it were True, all four bot actions abort anyway — findings §13).
3. **Receipt condition:** if `repaymentReceipts`, the output must hold exactly one receipt NFT named
   `hash_output_ref(loanInputOutputReference)`. Establish this loan's `repaymentReceipts` value and
   handle both branches or refuse the unsupported one **loudly**.
4. **`dosProtection` — the tight one.** `length(flatten(value))` must be **exactly** `1 +
   receiptAssetCount` for ADA equity, or **exactly `2 + receiptAssetCount`** otherwise. Our equity asset
   is tFLDT, so with no receipts the output must hold **exactly lovelace + tFLDT and nothing else**. No
   stray token may land there, and min-ada top-up must not add one.

**F-14 IS CONFIRMED AT THIS EXACT SITE.** The `and{}` block opens with the comment **`//No staking check
here`** verbatim. So the equity output's **stake credential is unconstrained by the validator — whoever
builds the transaction chooses it.** Consequences, both mandatory:
- Choose the **borrower's** stake credential if the loan datum carries one (mirroring how the lender's
  payout carries `lenderStakeCredential`). **If the loan datum has no borrower stake credential, STOP and
  escalate** — do not silently insert the bot's own, and do not silently emit an enterprise address. That
  choice is the F-14 finding in live form and it is not the worker's to make.
- **Assert the emitted output's full address, stake credential included, off the FINISHED body.** The
  validator will not complain; our builder must. Same shape as the fee-100 and index assertions.

## THE CENTRAL DESIGN TASK — the positional collision
The LM action's `assetOutputIndexes` and `loan_claim_action`'s equity `index` **index the same
credential-filtered output list**. Adding the equity output shifts what the LM's indexes resolve to.
This, not a missing field, is why the current javadoc says positive equity "is not satisfiable in either
output layout this builder can emit".

**Leading hypothesis, to VERIFY not assume:** for a single loan (`index = 0`) the equity output must sit
at filtered position **0**, while `assetOutputIndexes` is a free redeemer choice — so the layout
*equity first, lender collateral second, `assetOutputIndexes = [1]`* should satisfy both. If that holds,
the constraint was never impossibility but the builder's two hardcoded layouts. **Verify against the
machine; report the general form for N loans** (equity outputs interleaved with collateral outputs, and
what `assetOutputIndexes` must then be). If it does not hold, report precisely which conjunct refuses.

## TASKS
1. Read `loan_claim_action.ak` around 240-300 and `equity_sent_to_borrower` in full; confirm the four
   requirements above and the `//No staking check here` comment. Report anything I got wrong.
2. Establish this loan's `repaymentReceipts` and whether the loan datum carries a borrower stake
   credential. **Escalate if it does not** (see F-14 above).
3. Compute equity with **`LoanFinance` at the transaction's `validFrom`**, the same instant discipline
   T-021's V4 lesson established — never at scan time, never at both window ends.
4. Emit the borrower-compensation output satisfying all four requirements, and solve the positional
   collision. Derive every index from the **finished body**, never predicted.
5. **Rewrite V8** from "refuse `equity > 0`" into "**the equity output is present and correct**" — it
   must now assert the datum, the amount, the `dosProtection` shape and the full address off the finished
   body, and still refuse a build that omits the output.
6. Lift the scanner's `POSITIVE_EQUITY_UNSUPPORTED` exclusion — **last**, and only after 7 passes.
7. Retire or rewrite `positiveEquityIsRefusedInBothLayoutsThisBuilderCanEmit`. It currently pins the
   limitation as intended behaviour; it must become a test of the new capability, and **the commit must
   say why the old assertion was removed** rather than deleting it quietly.

## ACCEPTANCE
- **Dry-eval green against the real deployed validators using this loan's REAL on-chain data** (loan
  datum, bond datum, live oracle feed). This is the gate; a synthetic-only pass does not count.
- Every new/changed guard proven by a **mutant the previous code does not kill** — including one that
  omits the equity output entirely and one that corrupts its datum.
- Cold suite green, counts from `build/test-results/test/*.xml`, `:test` genuinely ran.
- No submission. `AQUARIUM_X_SUBMIT` unset. Nothing on chain from the session.
- Blueprint sha256 `5b150c3d90b3be2b83ef3bc83d0a7f5dbddd1607e0b3a11429f5e68be6477489` unchanged.
- `CLAUDE.md`, `PLAN.md`, `WORKLOG.md`, `docs/` untouched by the worker.

## ESCALATE, DO NOT DECIDE
- The loan datum carries **no borrower stake credential** (F-14 — orchestrator/Giovanni decides).
- The positional collision cannot be solved for N>1 without changing what the builder emits for the
  existing zero-equity path (that would be a behaviour change to a working production path).
- `repaymentReceipts` is true and the receipt-NFT branch needs a mint we do not currently do.
