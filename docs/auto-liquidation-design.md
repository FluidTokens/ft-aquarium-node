# Auto-Liquidation in the Aquarium Node — Design

Status: **Draft for discussion** · Owner: TBD · Last updated: 2026-07-13

This document describes how to add **automatic loan liquidation** for the
FluidTokens Lending v4 protocol (`FluidTokens/ft-cardano-loans-v4`) to the
`ft-aquarium-node` Java bot. It covers the on-chain mechanics, the off-chain
components we must build, the transaction anatomy, integration into the existing
node, and the open dependencies on the FluidTokens contract/oracle teams.

---

## 1. Goals & scope

**Goal:** run a competitive liquidation bot inside the existing Aquarium node —
scan lender positions, compute each loan's health, and, when a loan is
liquidatable, build and submit the liquidation transaction, earning the
`liquidationFeePerMille` cut of the collateral.

**Integration decision:** a new `LiquidationService` lives *inside*
`ft-aquarium-node`, reusing its indexing, scheduling and wallet infrastructure.
Scheduled-tx processing and liquidation are **independent job types**, each
toggleable via config/env. A single operator wallet is fine — operators who want
to separate concerns run two node instances. The bot keeps the liquidation fee
**directly** as tx change; this is *not* routed through the Aquarium staking
reward pool.

**Variant scope (phased, in order):**

| # | Variant | On-chain validator | Status |
|---|---------|--------------------|--------|
| 1 | `Liquidate` (simple) | `lm_liquidate_action` | in scope |
| 2 | `LiquidateAndPayInAdvance` | `lm_liquidate_and_pay_in_advance_action` | in scope |
| 3 | `LiquidateAndConvert` (Minswap) | `lm_liquidate_and_convert_action` | in scope |
| 4 | `LiquidatePayInAdvanceAndCompound` | `lm_liquidate_pay_in_advance_and_compound_action` | in scope |
| — | `LiquidateConvertAndCompound` | `lm_liquidate_convert_and_compound_action` | **out** — on-chain validator is a 170-byte stub, not implemented |

Non-liquidation actions (`Compound` standalone, `WithdrawBonds`) are out of scope
for this workstream.

---

## 2. Background: how liquidation works on-chain

### 2.1 The three NFTs

When a lender enables auto-liquidation, three NFTs share the same `assetName`
(= `loanId`), all under the **loan policy id**:

- **loan NFT** — on the loan UTxO in `loan.ak` spend script (holds collateral)
- **lender bond NFT** — locked in `lender_manager.ak` spend script; **this is
  what the bot scans for**. Its datum is `LenderManagerDatum`.
- **borrower bond NFT** — held by the borrower (receives equity refunds)

### 2.2 Config NFTs (the source of truth)

Every validator is *parameterized*, instantiated off one-shot **config NFTs**:

- **Main config** (`config.ak`, params `tx0/index0`) → the main config NFT
  policy. All core validators take `configNFTPolicyId + configNFTAssetName`.
  Actual script hashes/policies live in the **config UTxO datum**, read by index
  as a reference input, e.g.: `[0]` smart-tokens spend, `[4]` borrower-bond
  policy, `[5]` lender-bond policy, `[6]` loan policy, `[7]` repayment policy,
  `[10]` loan spend, `[16..21]` dutch-auction hashes/params.
- **LenderManager config** (`lm_config.ak`, params `tx0/index0`) → a **separate**
  config NFT. Its `LMConfigDatum` lists the 7 `lm_*_action` script hashes.

> The blueprint hashes in `plutus.json` are *unapplied* — never the deployed
> addresses. Real hashes are derived by applying the config params and are
> published in the config UTxO datum.

### 2.3 The `LenderManagerDatum` (per locked bond)

```
lenderAuth                          : how the lender reclaims the bond
lenderStakeCredential               : stake cred for bot-created outputs
shouldLiquidationConvertToPrincipal : false → collateral stays; true → must convert
liquidationFeePerMille              : the BOT's cut of collateral (its profit)
poolId                              : "" → no compounding; set → compoundable
principalAsset                      : must match for repay/compound
```

The datum decides *which variant* the bot must use (see §7).

### 2.4 Liquidation eligibility (`LiquidationMode` in the loan datum)

- `NoLiquidationFullCollateralClaim` → only when **late**; lender takes all
  collateral. No oracle.
- `NoLiquidationDutchAuctionClaim` → only when **late**; collateral goes to a
  `dutch_auction.ak` UTxO. No oracle.
- `Liquidation { lTV, lTVDivider, partialLiquidationPenaltyPerMille,
  equityInPrincipalCurrency }` → when **late OR LTV-unhealthy**. Oracle-driven.

---

## 3. Health Factor engine (highest-risk component)

A faithful Java/`BigInteger` port of `lib/fluidtokens/finance.ak`. It must match
the on-chain **rational** arithmetic exactly (ceil/floor rounding included) or
the liquidation tx fails script evaluation. **Build and unit-test this first,
standalone, before any tx-building work.**

### 3.1 The liquidation predicate

```
currentLtv   = outstandingDebt_in_lovelace / collateral_in_lovelace
liquidatable ⇔ liquidationLtv < currentLtv        (or collateral == 0)
```

`liquidationLtv = lTV / lTVDivider`. "Health Factor" for display is simply
`liquidationLtv / currentLtv` (≤ 1 ⇒ liquidatable), but the on-chain check is the
LTV comparison above (`finance.ak::can_liquidate`). A loan is also liquidatable
purely by **lateness** regardless of LTV.

### 3.2 Remaining debt (`get_remaining_debt`) — three formulas

- **`PrincipalAndInterestOnInstallments`**: `(P + P·interestRate) / totalInstallments`
  per installment × remaining installments.
- **`InterestOnRemainingPrincipal`** (amortization): the annuity formula with
  `rational_pow((1 + r/n), n)` — needs an exact rational power port.
- **`PerpetualLoan`**: the `APY = m·x + c` curve —
  `remainingInterest = P·(c·hoursSinceLastInstallment + m·hoursSoFar²)/8760`,
  with `m = apyIncreaseLinearCoefficient/1_000_000`, `c = interestRate` (as
  `/10000`), and `pow(timeSinceLendDate, 2)`. **Must** replicate the integer
  constants (`3_600_000`, `12_960_000_000_000`, `8760`) and `ceil` exactly.

`interestRate` is stored as `n/10000`. Time deltas are POSIX millis;
`installmentPeriod`/`initialGracePeriod`/`repaymentTimeWindow` are in **hours**.

### 3.3 Oracle price conversion

`get_token_amount_in_lovelace` uses the feed's `price_in_lovelaces /
price_denominator`. Supported feed shapes: `Aggregated`, `Dedicated`,
`PriceDataCharlie`, `PriceDataOrcfax` (all price-in-lovelace). **`Pooled`
`fail`s** in this path — not usable for the debt/collateral valuation. If
`expectedTokenPolicyId == ""` (i.e. ADA principal/collateral), the feed is the
identity `1/1` and no oracle is needed for that leg.

### 3.4 Equity (partial-liquidation refund to borrower)

```
equityInLovelace = collateral_lovelace − debt_lovelace − (debt_lovelace · penaltyPerMille/1000)
equity           = convert(equityInLovelace → principal or collateral currency), floored, max(·,0)
```

`equityInPrincipalCurrency` selects the output currency. If
`partialLiquidationPenaltyPerMille < 0`, equity is disabled and must be `0`.

### 3.5 Deliverable

`HealthFactorService` with pure functions mirroring `finance.ak`, plus a
`Rational` helper over `BigInteger`. Cross-checked with a table of test vectors
generated from the Aiken tests / a devnet loan set.

---

## 4. Indexing model

Extend the Yaci Store indexing (currently Tank-only) to track, by script
credential / policy:

| Data | Source | Purpose |
|------|--------|---------|
| lender bond UTxOs | `lender_manager.ak` spend script | the scan anchor (one per active loan) |
| loan UTxOs | `loan.ak` spend script | collateral + `LoanDatum` for HF |
| main config UTxO | config NFT | reference input; script-hash lookups |
| LM config UTxO | LM config NFT | `lm_*_action` hashes |
| oracle UTxOs | oracle NFTs | reference inputs for price feeds |
| pool / pool_manager UTxOs | `pool.ak` / `pool_manager.ak` | only for compound variants (phase 4) |

Reuse `UtxoRepository.findUnspentByOwnerPaymentCredential` as in
`ScheduledTransactionService`. Add storages analogous to `TankUtxoStorage`.

**Scan loop** (mirrors the README "Bots logic"): for each lender bond →
find its loan (same `assetName`) → check `LiquidationMode` allows liquidation →
compute HF / lateness → if liquidatable, select variant from
`LenderManagerDatum` (§7) → build & submit.

---

## 5. On-chain type generation

Add the loans `plutus.json` to the project and a second `@Blueprint` interface
(e.g. `LoansBlueprint`, distinct package) so the annotation processor generates:
`LoanDatum`, `LenderManagerDatum`, `LMConfigDatum`, the config datum, the 5
`OraclePriceFeed` variants + `OracleRedeemer`, `AssetManagerDatumWithToken`, and
every redeemer (`LoanClaimActionWithdrawRedeemer`/`ClaimData`,
`LMLiquidateWithdrawRedeemer`, `LMLiquidateAndPayInAdvance…`,
`LMLiquidateAndConvert…`, `LMLiquidatePayInAdvanceAndCompound…`).

Note some list fields use tuples (`List<(Int, Int)>` for oracle ref-input index
pairs) — verify the generator handles these or hand-write those converters.

---

## 6. Oracle integration

Oracle-mode liquidations require a **fresh signed feed in the tx** — the
validator (`oracle.ak` via `retrieve_oracle_data`) reads an `OracleRedeemer {
data, signatures }` from a **`Withdraw` redeemer**, not from any datum. Contract
constraints the node must satisfy:

- the oracle **NFT** is present in a **reference input**;
- feed `valid_from ≤ tx.validFrom` **and** `valid_to ≥ tx.validTo`;
- `valid_to − valid_from ≤ max_oracle_validity_range`;
- feed token matches the loan's collateral / principal oracle asset.

**Component:** `OracleClient` calls the **FluidTokens oracle API** (confirmed
source; endpoint/spec TBD) to fetch a signed feed per needed token, then
assembles the oracle withdrawal(s) and pins the tx validity interval tightly
inside the feed window. The bot cannot generate prices itself.

---

## 7. Variant selection (decision tree)

Per the README "Bots logic", for a liquidatable loan:

1. `shouldLiquidationConvertToPrincipal == false` → **`Liquidate`** (§8).
2. else if `poolId == ""` → convert collateral to principal:
   **`LiquidateAndPayInAdvance`** (bot fronts principal, keeps collateral) or
   **`LiquidateAndConvert`** (place Minswap order) — bot picks based on liquidity
   & slippage.
3. else (`poolId` set and pool exists) → **`LiquidatePayInAdvanceAndCompound`**
   (convert + compound into the pool, earn both fees). *(Convert+compound combo
   is out of scope — validator stub.)*

Phase the implementation strictly in this order; each step roughly doubles the
tx-building surface.

---

## 8. Transaction anatomy — Phase 1 `Liquidate` (detailed)

For `shouldLiquidationConvertToPrincipal == false`, N loans in one tx (1:1
loan ↔ lender-bond, both keyed by `loanId`):

**Inputs**
- each loan UTxO from `loan.ak` spend (loan NFT + collateral);
- each lender-bond UTxO from `lender_manager.ak` spend (lender bond NFT);
- a bot wallet UTxO (fees, collateral, change).

**Mint:** burn each loan NFT (`loanPolicyId, loanId : −1`).

**Withdrawals** (0-ADA "withdraw-0" script invocations):
- `loan` spend + `loan_claim_action` — redeemer
  `LoanClaimActionWithdrawRedeemer { configRefInputIndex, actionsForEachInput:
  [ClaimData] }`. Each `ClaimData` carries `liquidationMode`,
  `lenderBondOutputIndex`, `collateralOracleRefInputIndex`,
  `principalOracleRefInputIndex`, `lenderAuth`, `equity`, `loanId`,
  `remainingDebt` (must equal the HF engine's computed value).
- `lender_manager` spend + `lm_liquidate_action` — redeemer
  `LMLiquidateWithdrawRedeemer { configRefInputIndex, lenderBondInputIndexes,
  lenderBondAssetNames }`.
- **oracle withdrawal(s)** — one per feed, `OracleRedeemer { data, signatures }`
  (§6). Skipped for the ADA leg (identity feed).

**Reference inputs:** main config UTxO (`configRefInputIndex`); collateral &
principal oracle NFT UTxOs; **reference scripts** for `loan` spend,
`loan_claim_action`, `lender_manager` spend, `lm_liquidate_action`,
`asset_manager`, `oracle`.

**Outputs**
- each lender bond returned to `lender_manager.ak`, **byte-identical** to its
  input (`lm_liquidate` requires `equals_data(input, output)`); also referenced
  by `ClaimData.lenderBondOutputIndex` and must still contain the bond NFT.
- each collateral output to `asset_manager` spend (smart credential), datum
  `AssetManagerDatumWithToken { inputOutputReference = loan input ref, action =
  action_claimed_collateral, data = None, ownerAsset = lender bond }`, value
  `≥ collateral − equity − liquidationFee`. DoS rule: exactly 1 flattened asset
  (ADA collateral) or 2 (token collateral).
- if `equity > 0`: borrower-compensation output to `asset_manager`, action
  `action_partial_liquidation_compensation`, `ownerAsset = borrower bond`, value
  `≥ equity` (checked by `loan_claim_action`; +receipt NFT if
  `repaymentReceipts`).
- the `liquidationFee` slice of collateral is **left as change to the bot** (its
  profit) plus ADA change.

**Validity interval:** tight, inside every oracle feed window.

**Index resolution (the hard part):** Cardano orders inputs canonically by
`(txHash, outputIndex)`. The builder must, *after* input selection:
- derive `loanInputs` and `lenderBondInputs` in final input order (filtered by
  script credential) and set `lenderBondInputIndexes` so
  `lenderBondInputs[idx[i]]` pairs with `loanInputs[i]` — with **all indexes
  unique** and `len == len(loanInputs)` (validator enforces both);
- set absolute `lenderBondOutputIndex` and the per-index `asset_manager` output
  ordering;
- set `configRefInputIndex` and the oracle ref-input indexes.

Reuse `service/TransactionInputComparator` and the `resolveRefIndexes` pattern
already used by `ScheduledTransactionService`.

**Later phases** add: (2) principal pre-payment outputs to the lender + the
pay-in-advance redeemer with oracle index pairs; (3) Minswap V2 order
construction (order datum, slippage, pool ref inputs) — the largest single
addition; (4) pool + pool_manager indexing and compound outputs.

---

## 9. Node integration & configuration

- New `liquidation.*` config block, structured like the existing
  `staking`/`config` blocks: each contract as `{ policy, ref-input.txHash,
  sync-start-blockhash }`. Add entries for main config, LM config, loan,
  lender_manager, loan_claim_action, lm_liquidate_action, asset_manager, and the
  oracle set.
- Feature flags: `AQUARIUM_SCHEDULED_TX_ENABLED`, `AQUARIUM_LIQUIDATION_ENABLED`
  (both default sensibly) so an operator can run either or both.
- `LiquidationService` mirrors `ScheduledTransactionService`: `@Scheduled` loop,
  syncing guard, per-utxo failure quarantine list, `QuickTxBuilder`/`ScriptTx`.
- Oracle API credentials/endpoint via env.
- Profitability gate: only submit when
  `value(liquidationFee collateral) − tx fee − risk margin > 0`.

---

## 10. Open dependencies (blocking live wiring, not this design)

From the FluidTokens contract/oracle team:

1. **Deployment status of v4** — mainnet already, or do we bring up preprod?
   (Currently *unsure*.)
2. **Config coordinates:** main config NFT policy+assetname + config UTxO
   location & datum field list; LM config NFT + `LMConfigDatum` UTxO.
3. **Loan/bond policy id** (applied `loan.ak`).
4. **Reference-script tx hashes** for the ~6 validators the tx reads.
5. **Oracle API spec** (endpoint, auth, response shape) + oracle NFT
   policy+assetnames + which tokens have feeds.
6. Confirmation on the **`lm_liquidate_convert_and_compound_action` stub** —
   will it be implemented, or is that combo permanently out?
7. A **preprod test fixture** (a lender + liquidatable loan) for end-to-end runs.

---

## 11. Phased roadmap

1. **Foundation** — loans `plutus.json` + `@Blueprint`; generated types; indexing
   for lender bond / loan / config / oracle UTxOs; **HF engine + unit tests**
   (parity vs on-chain). *No tx yet.*
2. **Oracle client** — FT API integration; signed feed → withdraw redeemer;
   validity-window handling.
3. **Simple `Liquidate`** — full tx builder (§8); first end-to-end liquidation on
   preprod.
4. **`LiquidateAndPayInAdvance`**.
5. **`LiquidateAndConvert`** (Minswap).
6. **`LiquidatePayInAdvanceAndCompound`** — adds pool/pool_manager indexing.
7. Config toggles, profitability gating, operator docs.

---

## 12. Key risks

- **HF parity:** off-chain rational math must match `finance.ak` bit-for-bit
  (rounding, integer constants, perpetual curve). Mitigate with test vectors.
- **Index resolution:** wrong input/output/ref indexes ⇒ silent script failure.
  Mitigate by reusing the node's comparator and adding builder-level assertions.
- **Oracle freshness/latency:** feed must be signed and fit the validity window
  at submit time; stale feeds fail. Tight validity intervals + retry.
- **Competition:** bots race; losing the race wastes fees. Fast scan + submit;
  quarantine already-spent utxos.
- **Minswap coupling (phase 5):** external protocol surface; version drift.
