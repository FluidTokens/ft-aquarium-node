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

## 5. On-chain type generation — ✗ NOT VIABLE, hand-decode instead (2026-08-07)

The original plan was a second `@Blueprint` interface over the loans blueprint.
It does not work, for three independent reasons, each of which alone is fatal:

1. **The datum types are not in the deployed blueprint.** Only `ConfigDatum` and
   `LMConfigDatum` are. Every other v4 UTxO sits at a `general_spend` address whose
   handler takes `datumOpt: Option<Data>`, so Aiken never emits a schema for
   `LoanDatum`, `LenderManagerDatum`, `PoolDatum` or `AssetManagerDatumWithToken`.
2. **Tuples break the processor.** cardano-client-lib 0.7.2 types
   `BlueprintSchema.items` as a single schema; Aiken emits an *array* for tuples
   (`Tuple<<Int,Int>>` and friends). The processor dies with
   `MismatchedInputException: … from Array value (START_ARRAY)`. Worse, **one failing
   `@Blueprint` aborts the whole annotation-processing round**, so it silently takes
   the Aquarium blueprint's generated types down with it and the build collapses
   with ~20 unrelated "package does not exist" errors.
3. **Even with 1 and 2 solved, generation still dies.** `aiken build -I` (below)
   supplies the missing types, and culling the tuple/non-serialisable definitions
   gets the JSON to parse — but the processor then emits only 4 classes and stops,
   with no diagnostic. Confirmed with the loans blueprint as the *only* `@Blueprint`
   in the module, so it is not a two-blueprint conflict.

**Therefore:** `LoanDatum` and its component types are hand-written
(`model/loans/*`, `service/loans/LoanDatumConverter`). Redeemers will be too.

### 5.1 `aiken build --include-all-types` as a specification oracle

`aiken build -I` emits every serialisable type rather than only those reachable from
validator signatures — 102 → 139 definitions, and `LoanDatum`, `LenderManagerDatum`,
`PoolDatum`, `CollateralAsset` and `RepaymentMode` all appear.

It **cannot** be used for hashes: built with a different aiken version than the
deployment (`aiken.toml` declares v1.1.21), its `compiledCode` differs for **68 of 74**
validators. `loans-v4.plutus.json` remains the only valid derivation input.

But it is an excellent oracle for the hand-written decoder, and
`LoanDatumSchemaTest` uses it exactly that way — asserting field order and
constructor indices straight from the contract's own schema, so a silent
mis-decode becomes a red test. Regenerate with:

```bash
cd ../ft-cardano-loans-v4 && aiken build -I -o loans-v4-alltypes.plutus.json
cp loans-v4-alltypes.plutus.json ../ft-aquarium-node/src/test/resources/loans-v4/
```

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

**Component:** `OracleClient` calls the **FluidTokens oracle API** to fetch a
signed feed per needed token, then assembles the oracle withdrawal(s) and pins the
tx validity interval tightly inside the feed window. The bot cannot generate
prices itself.

### 6.1 The oracle API — resolved, 2026-08-06

**`GET https://api.fluidtokens.com/get-oracle-tokens`**, no auth. The same endpoint
`ada-watch` already polls for Lending v3 alerts (`FluidConstants.ORACLE_TOKENS_URL`,
`FluidOracleService`, 30s cadence) — but ada-watch only reads prices, so it parses
away the half we need.

Returns an array (19 entries on mainnet), one per supported token:

```jsonc
{
  "token": { "policyId": "...", "assetName": "", "decimals": 6, "symbol": "OADA" },
  "fluidOracle": {
    "policyId": "...", "assetName": "6f7261636c654f414441",
    "referenceScript": "25d1faae…#0",   // oracle withdraw script, deployed
    "rewardAddress":   "stake17xf2uy8…", // <- the Withdraw purpose to attach
    "referenceInput":  "61441d51…#0"     // oracle NFT utxo -> tx reference input
  },
  "governanceToken": { "governancePolicy": "...", "governanceAsset": "...", "treshold": 2 },
  "preferredOracle": "multisig",         // 18 of 19; the other is "c3"
  "active": true,
  "supportedOracle": {
    "multisig": {
      "validFrom": 1785332220000, "validTo": 1785333600000,
      "tokenPriceInLovelaces": 787376451, "tokenPriceDenominator": 1,
      "multisigOracle": {
        "requiredSignatures": 3,
        "signatures": [ { "publicKey": "636A65…", "signature": "e12e43ab…" }, … ]
      }
    }
  }
}
```

**It ships the signatures.** `supportedOracle.multisig.multisigOracle.signatures[]`
is exactly the input to `OracleRedeemer { data, signatures }`. The mapping is
direct:

| API | on-chain |
|---|---|
| `validFrom` / `validTo` / `token` | `CommonFeedData { valid_from, valid_to, token }` |
| `tokenPriceInLovelaces` / `tokenPriceDenominator` | `Aggregated { token_price_in_lovelaces, token_price_denominator }` |
| `multisigOracle.signatures[].signature` | `Signature.signature` |
| `fluidOracle.rewardAddress` | the `Withdraw` script purpose carrying the redeemer |
| `fluidOracle.referenceInput` | the reference input holding the oracle NFT |
| `fluidOracle.referenceScript` | where to `readFrom` the oracle script itself |

**Two providers, two mechanisms.** `preferredOracle: "multisig"` (18/19) is the
signed path above, verified in `validators/oracle.ak:167-183` by
`verify_ed25519_signature` over `verification_keys` with a threshold.
`preferredOracle: "c3"` instead points at a **Charli3 reference input** and uses the
`PriceDataCharlie { provider_ref_input_index, .. }` feed variant — no signatures
involved. Phase 2 only needs the multisig path.

Note `lib/fluidtokens/oracle.ak` `retrieve_oracle_data` does **not** check
signatures — it only validates the window, the token match and the oracle NFT's
presence. Signature verification happens because the oracle script's own withdrawal
is in the tx. Both must be present or the feed is trusted-but-unverified.

**Two things still unknown:**

1. **Is there a preview instance of this API?** `api.fluidtokens.com` serves mainnet
   data; our loans are on preview. Either FT hosts a preview equivalent or preview
   liquidations need a stub feed.
2. **`Signature.key_position`** is an index into the validator's `verification_keys`
   list, but the API gives us `publicKey`. The mapping has to be recovered from the
   on-chain oracle datum, then `publicKey` → position resolved per feed.

### 6.2 The signed feed encoding — proven, 2026-08-09

`validators/oracle.ak` verifies a feed with

```aiken
let serialise_data = builtin.serialise_data(redeemer.data)
verify_ed25519_signature(verification_key, serialise_data, redem.signature)
```

so the redeemer's bytes must match the oracle's to the byte. Rather than assert an
encoding from reading the type, we took the signatures FluidTokens publishes and
searched for the encoding they verify under. Of 24 candidates (definite vs
indefinite CBOR lists × constructor index 0/1/2 × `Asset` field order × with/without
the token), **exactly one verified, and it verified all 20 signatures across 18
feeds**:

```
Aggregated = Constr 0 [ CommonFeedData, price_in_lovelaces, price_denominator ]
CommonFeedData = Constr 0 [ valid_from, valid_to, Asset ]
Asset = Constr 0 [ policyId, assetName ]      -- policy id first
```

with **indefinite-length lists** (`d8799f…ff`), which is what Plutus `serialiseData`
emits and what cardano-client-lib produces. An ed25519 signature is a total function
of its message, so this is a proof of the encoding, not evidence for it. Pinned by
`OracleFeedSignatureTest` against a captured payload — signatures stay valid over
their message forever, so the fixture does not rot.

### 6.3 One oracle script per asset

`retrieve_oracle_data` does `pairs.get_first(redeemers, Withdraw(oraclePaymentCredential))`
— a single redeemer per credential. A token/token loan needs two prices, which only
works because **each asset has its own oracle script**: the registry returns 19
entries with 19 distinct `fluidOracle.rewardAddress` values. A liquidation therefore
carries one withdrawal per priced leg, each with its own reference input.

`verification_keys` and `threshold` are **validator parameters**, not datum fields —
`validator oracle(verification_keys: List<ByteArray>, threshold: Int, …)` — so they
are baked into each per-asset script. `key_position` indexes that parameter list.
Every published signature's `publicKey` appears in the entry's `multisigOracle.publicKeys`
at an ascending index, so position = index in that list. **Caveat:** 17 of 18 entries
have a single key, so only fGLD (3 keys → `[0,1,2]`) carries any information. This is
consistent with the published data but does not prove `publicKeys` shares the order of
the on-chain parameter; confirming that needs the applied oracle script.

The registry also reports **two different thresholds** for the same asset — fGLD has
entry-level `requiredSignatures: 2` but feed-level `requiredSignatures: 3`. Sending
every available signature satisfies either, and is what we should do: the validator
`expect`s each supplied signature to verify, so a wrong `key_position` on any one of
them fails the whole transaction.

### 6.4 Not every oracle is signed — Charli3

The registry serves two provider kinds, and they are not interchangeable:

| `preferredOracle` | assets | on-chain variant | how it is validated |
|---|---|---|---|
| `multisig` | 18 | `Aggregated` | ed25519 signatures over the serialised feed |
| `c3` | OADA | `PriceDataCharlie` | structurally, against a Charli3 reference input |

A `c3` entry carries **no `multisigOracle` and no signatures at all**, and its redeemer
needs a `provider_ref_input_index` that only means anything relative to a specific
transaction. Encoding one as `Aggregated` — which is what we did before this was
noticed — produces a redeemer that fails for want of signatures.

So a c3 feed is priceable **and, since T-006, buildable for liquidation**: `PriceDataCharlie`
is modelled and the Charli3 provider UTxO is located from `supportedOracle.c3.referenceInput`.
`OracleEntry.usableForLiquidation()` returns true for a c3 entry exactly when that Charli3
provider reference input is known **and** the entry's own `fluidOracle.referenceInput` parses.
(T-012 closed a fail-open in that predicate: a null `fluidOracle.referenceInput` now yields
not-usable for **every** variant — multisig and c3 alike — because `retrieve_oracle_data`
requires that reference input regardless of provider kind.) The three live c3 feeds are the
working preview liquidation path; encoding a c3 entry as `Aggregated` — which is what we did
before this was noticed — produced a redeemer that failed for want of signatures.

Note this is a *reporting* distinction, not a health one: `liquidatable` still answers
"would the chain permit this", which is true regardless of whether we can currently
build the transaction. Phase 3 filters on `usableForLiquidation()`.

### 6.5 `key_position` — resolved from the deployed script, 2026-08-10

**`key_position` is the index of the signing key in the registry's published
`publicKeys` order.** Read directly out of the deployed oracle script rather than
inferred.

The chain of evidence:

1. The registry's `fluidOracle.rewardAddress` decodes to a script stake credential,
   and that credential equals the `reference_script_hash` on the UTxO named by
   `fluidOracle.referenceScript`. So the withdraw credential *is* the applied oracle
   script.
2. Fetching that script's CBOR (preview fGold,
   `ca8bbb0abbc25278a27c6d5dba950cb33ce700c10a9a753b7a586e91`) shows the applied
   parameters embedded verbatim. The `verification_keys` parameter appears as
   `9f 5820 <k0> 5820 <k1> 5820 <k2> ff` — an indefinite-length list of three 32-byte
   keys at uniform 34-byte spacing — **in exactly the order the API publishes them**.

fGold is the useful case precisely because it has three keys; every other entry has one,
where position 0 is true by construction.

**Deriving the script hash does not work, and the reason is instructive.** 132 parameter
combinations across both bundled blueprints all missed. Reading the deployed script
showed why: `_oracle_asset_policy_id` / `_oracle_asset_asset_name` are the **priced
token**, not the oracle NFT — the oracle NFT's policy id does not appear in the script
at all. Even with that corrected, derivation still misses, because `charlie_specs` and
`orcfax_specs` are not empty and their contents are not published. So the script hash
cannot currently be reproduced offline; reading the deployed script is the only route.

**Consequence for the parser.** Preview omits `multisigOracle.publicKeys` on some
entries while still publishing signatures (fGold). The signature array order is *not* a
safe substitute for the key list — a partial signer set would shift every index — so
those entries stay priceable and report `usableForLiquidation() = false` rather than
guessing a position. Guessing wrong fails the entire transaction, since the validator
`expect`s every supplied signature to verify at its stated position.

### 6.6 The preview registry — it exists

`https://testapi.fluidtokens.com/get-oracle-tokens`, wired in under the `preview`
profile. Five entries, and crucially it **prices tFLDT**
(`0b77d150…0014df1074464c4454`) — the collateral on every loan we index — with the
oracle NFT `9a2ec5c9…000de1406f766f3633` that those loan datums name. Preview health is
therefore fully computable, where the mainnet registry left LTV permanently unavailable.

**tFLDT is Charli3-backed**, so those loans are priceable and — since `PriceDataCharlie` is
modelled (T-006) — liquidatable by us via the c3 path.

### 6.7 The signable preview feeds are dead (2026-08-10)

The obvious workaround — get a loan collateralised in the `FLDTmultisig` test token,
which is signed and resolves its key position cleanly — **does not work**. Only the three
c3 feeds are actually being published:

| feed | provider | last valid | state |
|---|---|---|---|
| tFLDT, NIGHT, OADA | c3 | rolling, 10 min window reissued every 5 min | live |
| FLDTmultisig | multisig | 2026-06-08 | 63 days stale |
| fGold | multisig | 2026-02-24 | 167 days stale |

The validator requires the feed's window to contain the transaction's validity range, so
a months-old feed cannot be used no matter how well-formed its signatures are. When this
was written, every oracle we could sign a redeemer with was stale and every live oracle was
one we could not sign — the Phase 3 blocker on preview. It had two exits:

1. **Ask FluidTokens to resume publishing fresh multisig feeds on preview.** No new
   contract modelling, and it makes multisig-signed liquidation testable immediately.
2. **Implement `PriceDataCharlie`.** Larger: the redeemer carries a
   `provider_ref_input_index` into the transaction's own `reference_inputs`, so it can
   only be set while building the transaction, and the validator checks the price against
   the Charli3 datum rather than against signatures.

**Exit (2) is done (T-006).** The three live c3 feeds (tFLDT, NIGHT, OADA) are now the
working preview liquidation path, and the bot has liquidated real loans against them. Exit
(1) is no longer a blocker — it matters only if a *multisig*-signed liquidation is ever
needed; the stale multisig feeds remain unusable until FluidTokens republishes them.

### 6.8 There is a price blackout every 5 minutes, and it is upstream

Measured over two full cycles with `GET /loans/oracle`:

```
21:19:26  refresh age 28s   usable 0/5   <- window expired 21:19:24
21:20:26  refresh age 58s   usable 0/5
21:20:46  refresh age  8s   usable 3/5   <- recovered, ~80s dark
21:24:28  refresh age 13s   usable 0/5   <- window expired 21:24:24
21:24:48  refresh age  3s   usable 0/5
```

**The `age 3s` line is the one that matters.** We had refreshed three seconds earlier
and still held no valid feed, so the registry itself was serving an already-expired
window 21 seconds after its own `validTo`. The next window is published roughly a minute
*after* the previous one lapses, leaving a real hole in which no valid price exists
anywhere — not one we can close by polling harder.

This **falsifies the scheduler-starvation theory** that motivated the thread-pool change.
Refresh age sat between 1s and 29s for the whole run, exactly as a 30s `fixedDelay`
predicts. The pool change remains reasonable hygiene — one scheduler thread shared with
the transaction processor is still a latent hazard — but it did not fix this and was not
the cause. One 70s stretch showed no successful refresh (age climbing 28→38→58), which is
most likely a failed call at the boundary, since `refresh()` only advances `lastRefresh`
on success. That single stretch is unexplained.

**Consequence for Phase 3.** Roughly 20% of the time there is no usable price, so the bot
must expect to find nothing and retry rather than treat it as an outage. More sharply: a
transaction built close to a boundary can have its validity range fall outside the feed's
window and be rejected, so the builder should check the remaining window before
submitting rather than only at decision time.

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
5. ~~**Oracle API spec**~~ — **resolved without them**, see §6.1:
   `GET https://api.fluidtokens.com/get-oracle-tokens`, no auth, and it returns the
   signatures, oracle NFT policy/assetname, reward address and reference
   input/script per token. Still to ask: **is there a preview instance?**
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
