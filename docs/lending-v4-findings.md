# Lending v4 — On-Chain Findings & Integration Trace

Living document. Everything here was **verified against chain or against the
Aiken source**, not taken from docs. Where the upstream README disagrees with
the `.ak` source, the source wins (per instruction: *"don't trust it, always
cross check against the code"*).

- Branch: `feat/lending-v4` (forked off `main` @ `f71e051`)
- Network verified: **preview**
- Aiken source: `github.com/FluidTokens/ft-cardano-loans-v4` (validators + `plutus.json`)
- Verified on: 2026-08-05

---

## 1. Deployment coordinates (preview)

Given by Giovanni, confirmed on-chain:

```
CONFIG_REF_UTXO     = 6de7b7ecc2c822526d08dc998731d9e133e13c80dea099fb87827336dca12094
CONFIG_POLICY_ID    = f1a475ea8cccc1e0b7a59b10e79ad171452dc057ffb1cda0df92835c
LM_CONFIG_POLICY_ID = d0998754ddc3e9cfe80356d7e12db163d03cecc5b6b438dad4f4a3e3
CONFIG_ASSET_NAME   = 706172616d6574657273   ("parameters")
```

`CONFIG_ASSET_NAME` is not arbitrary — it is hardcoded in
`lib/fluidtokens/constants.ak`: `pub const config_asset_name = "parameters"`.
Both config NFTs use it.

### ⚠️ These superseded the 14 Jul 2026 set

v4 preview was **redeployed** between 14 Jul and now. The old values are dead:

| | 14 Jul (dead) | current |
|---|---|---|
| `CONFIG_REF_UTXO` | `4cc33e2d…d96` | `6de7b7ec…094` |
| `CONFIG_POLICY_ID` | `0e60ea5f…845` | `f1a475ea…83c` |
| `LM_CONFIG_POLICY_ID` | `ec121978…4bf` | `d0998754…3e3` |

Assume any other July-era v4 coordinate is also stale.

## 2. Indexing start point

Blockfrost `/assets/{policy+name}` for the config NFT:

```
initial_mint_tx_hash : 6de7b7ecc2c822526d08dc998731d9e133e13c80dea099fb87827336dca12094
mint_or_burn_count   : 1
```

`/assets/{asset}/transactions?order=asc` returns **exactly one** tx. So the NFT
was minted and **has never been moved or updated** — the mint tx *is* the live
config UTxO. `CONFIG_REF_UTXO` == the mint tx; they are the same thing.

Oldest tx → sync start:

```
block hash   : e24c9fc7b4f6bc5e2c313e55501c2d94817ecdb862fab69b5115c66f97d29018
block height : 4514052
slot         : 118496146
epoch        : 1371
time         : 1785152146
```

→ `store.cardano.sync-start-blockhash` / `sync-start-slot` for the preview profile.

**Caveat:** this is the *config deployment* block. Loans can only exist at or
after it, so it is a safe lower bound and nothing is missed. It is not
necessarily the block of the first loan.

## 3. The config UTxO (tx `6de7b7ec…`)

One tx carries **both** config NFTs:

| idx | address | holds | datum hash |
|---|---|---|---|
| 0 | `addr_test1wrc6ga023nxvrc9h5kd3peu669c52twq2llmrndqm7fgxhqvf6zh6` | main config NFT | `0750bb70…e5b1` |
| 1 | `addr_test1wrgfnp65mhp7nnlgqdtd0cfdk93aq08vckmtgwx66n628ccmkhzs4` | LM config NFT | `17c9be65…bb30` |
| 2,3 | wallet | change | — |

Both datums are **inline**. Both are `constructor 0` records whose field counts
match the Aiken types exactly (29 and 8) — decoding is unambiguous.

### 3.1 `ConfigDatum` — decoded (`lib/fluidtokens/types/config.ak`)

| # | field | value |
|---|---|---|
| 0 | `smartTokensSpendScriptHash` | `fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa` |
| 1 | `adminCredential` | `VerificationKey(ea1bb1ccd33aeb9e02516c2eb50adbaa63d7b7538b03c96908bfc934)` |
| 2 | `poolPolicyId` | `c02ec05d2806339fd752c1370a28ab613c8603f071fa077a03ea55a0` |
| 3 | `requestPolicyId` | `4519ff8d1d17660b60ee98c80cd48dd1c81d720d1ea3280cecfc5b46` |
| 4 | `borrowerBondPolicyId` | `eadc69a5d2d1357acc9b9d49ec5390fcdf6e080c7a40139917223dcb` |
| 5 | `lenderBondPolicyId` | `bcd713bb7858d4b08738bed90ee7068d8f9b38d02e0cae0b45ac7a9b` |
| 6 | **`loanPolicyId`** | `79d911c0beb40fae0a26ff802b1bc017d62b3bd07eca4766b6660dad` |
| 7 | `repaymentPolicyId` | `e50a4db3a3b65fc9c317fc600c70aee76f3286e6af1dd2f7f289aaa3` |
| 8 | `poolSpendScriptHash` | `9434006c1e8365c3afedc69332a3eee5d5779eb1a1d44ed37c7cb6f3` |
| 9 | `requestSpendScriptHash` | `e8b4765ebe61377d3f32ea56f37974335ebfe0aa2417e19611cfeed1` |
| 10 | **`loanSpendScriptHash`** | `b8569d71e5a918f79ba2b6899f53c534631f73db92207582a15c414a` |
| 11 | `loanClaimActionScriptHash` | `2ec873803f7b44688edbbe98671bf1f79c921143bb39b10f274b9b79` |
| 12 | `loanRepayActionScriptHash` | `7e36682e0366ecbc38e3875fdb4f44f33c2ce0bdcdc555a4f3015245` |
| 13 | `loanChangeCollateralActionScriptHash` | `28dbdb0620901f7b43feb4d8cbbc8b9e4c6499ffbb5537e152546a47` |
| 14 | `loanRecastActionScriptHash` | `66b3b28fea964bf0b15b42015cee2c09cca2347a640f30a4d7d43289` |
| 15 | `assetManagerSpendScriptHash` | `3f9068ec82efa5c87537fe626a65037f868a86c0191e99a4decf56dd` |
| 16 | `dutchAuctionSpendScriptHash` | **`""` (empty)** |
| 17 | `dutchAuctionWithdrawScriptHash` | **`""` (empty)** |
| 18 | `dutchAuctionStartingIncreasePerMille` | `0` |
| 19 | `dutchAuctionLoweringAmount` | `0` |
| 20 | `dutchAuctionLoweringFrequency` | `0` |
| 21 | `dutchAuctionMinPriceToCancel` | `0` |
| 22 | `poolCancelActionScriptHash` | `85ba63cdb595df5eb3ecbcb10620fd106b3af2e745308f18b01aaf66` |
| 23 | `poolBorrowActionScriptHash` | `991a88e0bd9f0fd925e3e7f4b52c2da48e47474b055c1a5e1610980c` |
| 24 | `poolSellLenderPositionActionScriptHash` | `dc8332cb9d29423f21ca0fa578860cb412d276747f0c486595e41d65` |
| 25 | `poolCompoundActionScriptHash` | `0626cf5a62522eede017006c1144f29fa9446e11cb352f67c2910803` |
| 26 | `poolManagerSpendScriptHash` | `e67abf27731ce422ab8918a1ef88dab0b25a2fe86cb3c8123b09fe49` |
| 27 | `poolManagerPolicyId` | `e4aee9c6a86cfa3ed2bcdc9b2089a7f259167e0a10f866650d6ca296` |
| 28 | `lockedBorrowerManagerSpendScriptHash` | `3b10fe08db2516218b2a1c6efe3ff6dd5008dd8898da408e1116274c` |

> **Dutch auction is NOT deployed on this preview instance** — both hashes empty,
> all four tuning params zero. Any liquidation path that routes through the
> Dutch auction is untestable here until FT deploys it.

### 3.2 `LMConfigDatum` — decoded (`lib/fluidtokens/types/lender_manager.ak`)

| # | field | value |
|---|---|---|
| 0 | `adminCredential` | `VerificationKey(ea1bb1cc…fc934)` — same admin as main config |
| 1 | `lmWithdrawBondsActionScriptHash` | `4aa7f99f9bd697071162cce2c8edb92e40a31edc48141db94ab74584` |
| 2 | **`lmLiquidateActionScriptHash`** | `c67e53e2b8d9c9a305e8dc281ddb1b79a2741976fd5657e8eadf3bf9` |
| 3 | `lmCompoundActionScriptHash` | `36835ebd9a76dc22595782be75da7fcec8560562462ea6e35e01efaa` |
| 4 | `lmLiquidateAndPayInAdvanceActionScriptHash` | `25c2475d888702af5872dafa032fff7b5853686f48290cd3eee5f1fa` |
| 5 | `lmLiquidateAndConvertActionScriptHash` | `b9d55fda0aed6ef9b7b75a1936125d77c0df227446dc48192767e400` |
| 6 | `lmLiquidatePayInAdvanceAndCompoundActionScriptHash` | `732e7ef5745919b9b5e0ff5cb20aee09c199706fc54e45392bcac54a` |
| 7 | `lmLiquidateConvertAndCompoundActionScriptHash` | `435b42cc200719c3868dfe01689ee07e2eeff5f5809f25408cbe4e7d` |

Note field 7 has a **real hash despite being the stub validator**
(`lm_liquidate_convert_and_compound_action.ak` is a ~170-byte no-op). A
non-empty hash in the config is **not** evidence the action works. Already
scoped out of our plan — this confirms the reason.

The `withdraw` handler of `lenderManager` reads these **by index** off the LM
config as a raw data list (`safe_list_at(lmConfig, 1..7)`) — so **field order is
consensus-critical**. Never reorder.

## 4. Parameterisation — how v4 hashes are derived ✅ PROVEN

> **Status: settled.** `LoansContractDerivationTest` re-derives **every** derivable
> field of both config datums from just the two config NFT policy ids and asserts
> them against the live on-chain values. **26 assertions, all green.**
>
> This proves the bundled `loans-v4.plutus.json` **is the deployed commit**. It also
> means we never hardcode a v4 address — we derive and self-check.
>
> Corroborating evidence: the loans repo has had **no commits since 2026-07-07**
> (`plutus.json` untouched since 2026-07-02), so the preview redeploy that changed
> the config policies was a fresh one-shot mint of *identical* contracts.

### 4.1 The rules (all three were wrong on first guess — do not re-derive from intuition)

**Rule 1 — UTxOs never sit at the logic validator.** There is no `loan.loan.spend`;
`loan` only has `mint`/`withdraw`. Every `…SpendScriptHash` in the config datum is a
**`general_spend`** wrapper that delegates spending to a withdraw validator:

```
generalSpend(withdrawScriptHash, configPolicy) = general_spend(withdrawScriptHash, configPolicy, "parameters")
```

A validator's own hash serves as **both** its minting policy id and its withdraw
script hash — that is why `loanPolicyId` is also `loanWithdrawScriptHash`.

**Rule 2 — the LenderManager subtree wraps with the LM config policy, everything else
with the main one.** This asymmetry is real and silent: guessing wrong still yields a
plausible 28-byte hash.

```
loan/pool/request/assetManager/lockedBorrowerManager/poolManager → general_spend(…, CONFIG_POLICY_ID)
lenderManager                                                    → general_spend(…, LM_CONFIG_POLICY_ID)
```

**Rule 3 — `loanClaimCredential` / `loanClaimPaymentCredential` are `Credential`, not
`ByteArray`.** They must be encoded as constructor **alternative 1** (`Script`), not
raw bytes. Passing bytes produces a wrong-but-valid-looking hash.

### 4.2 The derivation chain

```
config(tx0, index0)                         → CONFIG_POLICY_ID    f1a475ea…  (given)
lm_config(tx0', index0')                    → LM_CONFIG_POLICY_ID d0998754…  (given)

loan(CFG, "parameters")                     → loanPolicyId    = loanWithdrawScriptHash
  general_spend(loanPolicyId, CFG)          → loanSpendScriptHash      ← the address to index
pool(CFG, "parameters")                     → poolPolicyId
  general_spend(poolPolicyId, CFG)          → poolSpendScriptHash
request / assetManager / lockedBorrowerManager                  … same shape

lenderManager(LM_CFG, "parameters")         → lenderManagerWithdrawScriptHash
  general_spend(…, LM_CFG)                  → lenderManagerSpendScriptHash
    lm_withdraw_bonds_action(lmSpend)       → lmWithdrawBondsActionScriptHash   ← confirms the two above
    lm_liquidate_action(CFG,"parameters",lmSpend,amSpend,amWithdraw,Script(loanClaimAction))
                                            → lmLiquidateActionScriptHash
```

**The LenderManager hashes are published nowhere on chain** — neither datum carries
them. They must be derived. `lm_withdraw_bonds_action` is parameterised by
`lenderManagerSpendScriptHash`, so the `LMConfigDatum` confirms the derivation
indirectly:

```
lenderManagerWithdrawScriptHash = d628e1eb4f4c7ff6af341ae8d6af81c7477b1f12eb49978529e45cbb
lenderManagerSpendScriptHash    = b2b99ad8c1e5c9f2c341d86a9b7268adf394dff27d20e2824e88ec64
```

We do **not** need `tx0/index0` for either one-shot — the resulting policy ids were
handed to us directly.

**Only underivable field:** `lmLiquidateAndConvertActionScriptHash`, which takes five
Minswap parameters (pool policy, pool spend/withdraw, order spend/withdraw) we don't
have. Everything else in both datums is reproduced.

### 4.3 Previous version of this section (superseded)

`grep '^validator ' validators/` gives the parameter lists. The ones we need:

```
loan(configNFTPolicyId, configNFTAssetName)
pool(configNFTPolicyId, configNFTAssetName)
request(configNFTPolicyId, configNFTAssetName)
lenderManager(lmConfigNFTPolicyId, lmConfigNFTAssetName)
config(tx0, index0)          -- the one-shot
lm_config(tx0, index0)       -- the LM one-shot
bond(_bondType: Int)
lm_withdraw_bonds_action(lenderManagerSpendScriptHash)
```

So the whole tree hangs off the two config NFT policies:

```
config(tx0, index0)                        → CONFIG_POLICY_ID    (f1a475ea…)
lm_config(tx0', index0')                   → LM_CONFIG_POLICY_ID (d0998754…)
loan(CONFIG_POLICY_ID, "parameters")       → loanSpendScriptHash
pool(CONFIG_POLICY_ID, "parameters")       → poolSpendScriptHash
request(CONFIG_POLICY_ID, "parameters")    → requestSpendScriptHash
lenderManager(LM_CONFIG_POLICY_ID, "parameters") → LenderManager spend hash
```

**This is exactly the `ContractRegistry` pattern already built for Aquarium** —
`AikenScriptUtil.applyParamToScript` over committed `compiledCode`, never
`aiken build`.

This first sketch was **wrong in three ways** — it missed the `general_spend`
layer, the LM/main config asymmetry, and the `Credential` encoding. Kept only as a
record of what the validator parameter lists look like; §4.1–4.2 is authoritative.

## 5. What to index ✅ VERIFIED AGAINST LIVE LOANS

Index by **payment credential**, never by full address.

Loan UTxOs carry a **stake credential** (`LenderManagerDatum.lenderStakeCredential`
— *"stake credential of the lender, used for any utxo created by the bots"*), so the
bech32 address varies per lender while the payment credential stays fixed:

```
derived script-only address : addr_test1w zu9d8t3uk533aum52mgn86nc56xx8mnmwfzqavz59wyzj s2hn0py
a real live loan UTxO       : addr_test1z zu9d8t3uk533aum52mgn86nc56xx8mnmwfzqavz59wyzj 5788t0n…
                                          ^^^^ same payment credential, stake part appended
```

`UtxoRepository.findUnspentByOwnerPaymentCredential` — the method the Tank indexer
already uses — is exactly right, since it keys on the payment credential.

**Live preview state** (confirmed via Blockfrost):

| policy | assets minted |
|---|---|
| `loanPolicyId` | 24 |
| `poolPolicyId` | 40 |
| `borrowerBondPolicyId` | ≥100 (page cap) |
| `lenderBondPolicyId` | ≥100 (page cap) |
| `requestPolicyId` | none — never minted |

So there is **real loan activity to index**, and `requestPolicyId` has no assets at
all yet.

**Reference scripts are self-hosted.** Each `general_spend` script is deployed as a
reference script at its *own* address, e.g. the loan spend script sits at
`5c10900c…69da#0` (block 4521414) and the LenderManager spend script at
`13dd3329…2571#0`. Both UTxOs' `reference_script_hash` equals the hash we derived —
an independent on-chain confirmation of §4, and it means we get the ref inputs for
free without asking FT for deployment tx hashes.

### 5.1 Superseded first take

For loan discovery the target is the **loan spend script address**, built from
`loanSpendScriptHash` = `b8569d71e5a918f79ba2b6899f53c534631f73db92207582a15c414a`
(preview / network id 0, no stake part).

Same `UtxoRepository.findUnspentByOwnerPaymentCredential` path the Tank indexer
already uses — the payment credential is the script hash. `loanPolicyId`
(`79d911c0…`) identifies genuine loan UTxOs and screens out junk sent to the
address.

### 5.2 Where the config NFTs live ✅ VERIFIED

Each config NFT sits at **its own validator's script address** — the payment
credential *is* the config policy id (both addresses decode to header `0x70`,
script-only, no stake part):

```
f1a475ea…835c "parameters" → addr_test1wrc6ga023nxvrc9h5kd3peu669c52twq2llmrndqm7fgxhqvf6zh6
d0998754…a3e3 "parameters" → addr_test1wrgfnp65mhp7nnlgqdtd0cfdk93aq08vckmtgwx66n628ccmkhzs4
```

So the two config UTxOs — needed as **reference inputs** on every v4 tx — are picked
up by indexing the two policy ids as payment credentials. No extra lookup path.

## 6. Runtime wiring ✅ IMPLEMENTED

`LoansContractRegistry` (`service` package) is the runtime home of §4. It mirrors
`ContractRegistry`: applies parameters to the committed `compiledCode` in
`loans-v4.plutus.json` at startup, never recompiles.

- **Inputs** (`application.yaml`, `loans.*`): `config.policy-id`,
  `lm-config.policy-id`, `config.asset-name` (defaults to `"parameters"`),
  `smart-tokens-spend-script-hash`, `config.ref-utxo-tx-hash`. Preview values are
  committed; **mainnet has `loans.enabled: false`** until FT confirms a deployment.
- **Gating:** `@ConditionalOnProperty(loans.enabled=true)`, so the bean simply does
  not exist on mainnet and nothing else changes behaviour.
- **`smartTokensSpendScriptHash` is not derivable** — the blueprint ships no
  `smart_tokens` validator, the value only exists in the `ConfigDatum`. Without it
  the pool-manager branch (`poolManager*`, `lmCompound`,
  `lmLiquidatePayInAdvanceAndCompound`) is skipped and those getters return `null`;
  the plain liquidation path does not need any of them. A second test asserts the
  registry still comes up correctly in that degraded mode.
- **Indexing:** `LoansContractRegistry.indexedPaymentCredentials()` returns the 9
  credentials (both config policies + 7 `general_spend` wrappers) and
  `TankUtxoStorage` adds them to its whitelist via an `ObjectProvider`, so
  `saveUnspent` starts keeping v4 UTxOs. The set is logged at startup.

`LoansContractDerivationTest` was rewritten to assert against the **registry's**
getters rather than re-deriving in the test, so the proof now covers the code the
node actually runs. Both tests green.

### 6.1 Startup verification — a redeploy now fails loudly

`LoansConfigVerifier` runs in `@PostConstruct` and compares every derived hash
against the live config datums, read from chain via Blockfrost. Preview was already
redeployed once under us (2026-07-14 → 2026-08-05); without this check the next one
is a **silent** failure — stale policy ids in yaml, derivation still succeeds, node
indexes a deployment that no longer exists while looking healthy.

Failure modes are deliberately distinct:

| situation | behaviour |
|---|---|
| any derived hash ≠ chain | **hard fail**, listing every mismatching field |
| config NFT not at its script address | **hard fail** — the policy id is stale |
| datum field count ≠ 29 / 8 | **hard fail** — contract types changed, indices invalid |
| Blockfrost 4xx (bad key, unknown address) | **hard fail** — that is an answer, not an outage |
| Blockfrost 5xx / 429 / transport error | warn and continue (`loans.verify-config.fail-on-unreachable=true` to make it mandatory) |

The transient case is soft on purpose: a Blockfrost blip must not take down the
Aquarium scheduled-transaction path, which has nothing to do with loans. But 4xx is
deliberately **not** in that bucket — a wrong or missing API key would otherwise skip
verification on every single boot while logging a warning nobody reads, which is the
same silent failure in a different costume.

`smartTokensSpendScriptHash` is checked too even though it is configured rather than
derived — a stale value there silently corrupts the whole pool-manager branch. If it
is unset, the verifier logs the on-chain value so it can just be pasted in.

Three layers of test, because each covers what the others cannot:

- `LoansContractDerivationTest` — derivation vs. the hashes recorded in the docs
- `LoansConfigVerifierTest` — comparison logic vs. **recorded** datum fixtures
  (`src/test/resources/loans-v4/*.hex`), no network. Pins the datum **field
  indices**, which are the fragile part; includes a negative test that feeds the
  pre-redeploy policy id `0e60ea5f…` and asserts the mismatch is caught
- `LoansConfigVerifierLiveTest` — the full fetch path against live preview, plus a
  bad-key case asserting a 4xx aborts rather than degrading. Skipped unless
  `BLOCKFROST_KEY` is set (`set -a; . ./.env.preview; set +a`). Doubles as the
  "has FT redeployed preview again?" check

All green, including the live one.

> Preview sync start is slot `71971209` (the Aquarium preview ref input), which is
> **older** than the v4 config mint at slot `118496146` / block `4514052`, so a
> preview sync already covers the whole v4 history. No sync-start change needed.

## 7. README "Bots logic" vs the validators ✅ DIFFED

Upstream repo cloned at `../ft-cardano-loans-v4`, HEAD `5cc99a4` (2026-07-07,
"Update: readme"). Its `plutus.json` is **byte-identical** to our bundled
`loans-v4.plutus.json` (`sha256 29712aa5169f6e09c13199f2e0d6b9eb0959a0ebdd2e56a463c99a1562c5f478`),
and the last commit touching it is `bbe9c1a` (2026-07-02). So the contracts have
not moved since we vendored them — an independent re-confirmation of §4.

The README's 9-step "Bots logic" is a **sketch**. Everything below is enforced by
the validators and absent from, or contradicted by, the prose. Ranked by how badly
it bites the bot.

### 7.1 Scope filters the README does not mention

**D1 — bots can only liquidate `LiquidationMode::Liquidation`.** All four working
LM actions open with `expect Liquidation { equityInPrincipalCurrency, .. } =
loanInputAction.liquidationMode`:

| validator | line |
|---|---|
| `lm_liquidate_action.ak` | 120 |
| `lm_liquidate_and_pay_in_advance_action.ak` | 169 |
| `lm_liquidate_and_convert_action.ak` | 184 |
| `lm_liquidate_pay_in_advance_and_compound_action.ak` | 207 |

`NoLiquidationFullCollateralClaim` and `NoLiquidationDutchAuctionClaim` are
therefore **unreachable from the LenderManager path** — the lender claims those
manually via `loan_claim_action`. README step 3's "check that it allows
liquidation" hides a hard type filter. **This settles the dutch-auction open
item: out of scope, the bot cannot build such a tx at all.**

**D2 — `equityInPrincipalCurrency == True` loans are untouchable.** Same four
sites, next line: `expect equityInPrincipalCurrency == False`. Nothing in the
README hints at it. The bot's addressable set is exactly
`Liquidation { equityInPrincipalCurrency: False, .. }`.

**D3 — collateral shape restrictions** (`lm_liquidate_action.ak:108,116`):

- `expect Some(collateralAssetName) = collateral.maybeAssetName` — NFT-*collection*
  collateral cannot be liquidated
- `expect loanCollateralAmount > 1` — single-NFT collateral cannot be liquidated

Both must be scan filters. Without them the bot builds txs that die in script eval.

### 7.2 The README names the wrong contract

**D4 — liquidated collateral goes to `asset_manager`, not `lender_manager`.**
README step 5 says *"send the collateral (minus fees) to the lender_manager.ak"*
and step 9 says *"scan all the remaining lender_manager.ak utxos … with datum
AssetManagerDatumWithToken"*. The validator writes to **`assetManagerSpendScriptHash`**
(`lm_liquidate_action.ak:66-72`) with an `AssetManagerDatumWithToken`
(`:147-157`). The whole "Automatic liquidations and compounding" prose section
repeats the same mistake.

No code change needed — `assetManagerSpendScriptHash` is already in
`indexedPaymentCredentials()` — but the **compound-candidate scan must target the
asset-manager credential**, and anyone following the README would point it at the
wrong script.

### 7.3 Transaction-shape constraints

**D5 — every LenderManager input must be a bond being liquidated**
(`lm_liquidate_action.ak:175-178`):

```aiken
list.length(list.unique(redeemer.lenderBondInputIndexes)) == list.length(lenderBondInputs)
list.length(redeemer.lenderBondInputIndexes) == list.length(loanInputs)
```

`#LM inputs == #loan inputs`, indices unique. No unrelated LM UTxO may ride along
in a liquidation tx.

**D6 — the bond UTxO must be echoed byte-identically.**
`builtin.equals_data(lenderBondInput.output, lenderBondOutput)` (`:145`) — same
address *including stake part*, same value, same datum. Note the consequence:
the bond output's stake credential comes from the **input**, not from
`LenderManagerDatum.lenderStakeCredential`. That field's comment ("used for any
utxo created by the bots") applies to the asset outputs only.

**D7 — one output per loan, nothing merged.** `dosProtection` (`:213-218`) requires
the asset output to flatten to exactly **1** entry for ADA collateral and exactly
**2** otherwise.

### 7.4 Where the health-factor math is actually enforced

**D8 — `equity` and `remainingDebt` are bot-supplied inputs, checked elsewhere.**
`lm_liquidate_action` pulls them from the *loan* claim redeemer
(`parsedLoanRedeemer.actionsForEachInput[index]`, `:95-98`) and only does
arithmetic with them. The authoritative validation lives in
`loan_claim_action.ak` — `can_liquidate` at `:232`, `get_equity` /
`get_equity_in_collateral_currency`. **Aim the HF parity tests at
`loan_claim_action.ak`, not the LM actions.**

**D9 — `can_liquidate` is strict-greater, and zero collateral always liquidates.**
`finance.ak:174-195`:

```aiken
or {
  collateralInLovelace == rational.zero,          // <- always liquidatable
  rational.compare(liquidationLtv, currentLtv) == Less,   // <- strictly greater
}
```

Liquidatable iff `currentLtv > liquidationLtv`; **equality is not**. Both sides run through
`get_token_amount_in_lovelace`.

**Correction, 2026-08-16.** This paragraph used to end "so an LTV check **always** needs both
oracle feeds — there is no oracle-free liquidation path." **That is wrong for an ada leg.**
`lib/fluidtokens/oracle.ak:39-50` short-circuits on an empty policy id and returns a synthetic 1:1
feed *before* touching the redeemers, the reference input or the oracle NFT:

```aiken
if expectedTokenPolicyId == "" {
  Some(Aggregated { common: CommonFeedData { valid_from: 0, valid_to: 0,
        token: Asset { policyId: "", assetName: "" } },
       token_price_in_lovelaces: 1, token_price_denominator: 1 })
}
```

So an ada-principal / ada-collateral loan needs **no oracle withdrawal, no oracle reference input,
no signatures, no Charli3 provider UTxO**, and has no exposure to the 60–80s five-minutely price
blackout (design §6.8). This is not inference: `LiquidateDryEvalTest` evaluates exactly that shape
through the real PlutusV3 machine at the `bbe9c1a` pin with no oracle in the transaction, and a
live preview loan already uses ada collateral (`bad3e0871c24…`, 40 ada, `AdaCollateralTest`). The
authors wrote explicit ada branches throughout — `lm_liquidate_action.ak:213-218`,
`request.ak:496-511`, `request.ak:552-562`, `loan_claim_action.ak:447-452` — so it is a
first-class supported shape rather than an accident.

Related, and the reason the wrong sentence survived: the "collateral needs an asset name" rule is
`expect Some(collateralAssetName) = collateral.maybeAssetName` (`lm_liquidate_action.ak:108`),
which demands `Some`, **not** a non-empty bytestring. Ada collateral is `Some("")` and passes.
Our own filters read it correctly — `Loan.botLiquidatable()` and `LiquidationCandidateScanner`
both test `Optional.isPresent`/`isEmpty` on an `Optional<String>`, which means *None*, not *empty*.
Neither excludes ada collateral.

### 7.5 Agreements worth pinning down

- **The loan↔bond join key is the asset name.** `quantity_of(loanInput.output.value,
  loanPolicyId, lenderBondAssetName) == 1` (`:131-135`). Confirms design §4.
- **`shouldLiquidationConvertToPrincipal == False` is enforced** for the plain
  `Liquidate` action (`:143`), matching README step 5.
- **Fee maths:** `liquidationFee = loanCollateralAmount * liquidationFeePerMille / 1000`
  (floor), deducted from the lender's payout (`:118-124`). The validator never
  checks *where* the fee goes, and the lender payout is `>=` (`:204-209`) — so the
  bot keeps the fee as change. Confirms the "bot keeps the fee directly" decision.
- **`LiquidateConvertAndCompound` is a hard-`False` stub**
  (`lm_liquidate_convert_and_compound_action.ak`, 261 bytes — earlier noted as 170):
  `withdraw(...) { False }`, comment *"We need to be DEX batchers to do this"*.
  README step 8 correctly offers only the two viable options, so prose and contract
  agree. The variant still exists in `LenderManagerAction` and its hash is still
  published in `LMConfigDatum`.
- **`ConfigDatum` field indices confirmed** against the reads in
  `lm_liquidate_action.ak`: 0 `smartTokensSpendScriptHash`, 4 `borrowerBondPolicyId`,
  5 `lenderBondPolicyId`, 6 `loanPolicyId`, 10 `loanSpendScriptHash`.

### 7.6 Gap this exposed in our own code

**Bond policy ids are not derived.** README step 1 — "scan LM utxos that contain a
Lender bond nft" — needs `lenderBondPolicyId`, and `LoansContractRegistry` derives
no bond policy at all. It is trivial: `validators/bond.ak` is
`validator bond(_bondType: Int)`, the parameter is otherwise **unused** and exists
only to fork the two policies (`//borrower == 0`, `//lender == 1`). So:

- `lenderBondPolicyId   = hash(apply(bond.bond, [1]))` → `ConfigDatum[5]`
- `borrowerBondPolicyId = hash(apply(bond.bond, [0]))` → `ConfigDatum[4]`

Both are independently verifiable against the live config datum, so they belong in
`LoansContractRegistry` + `LoansConfigVerifier` alongside everything else.

## 8. Open items

- [x] ~~Derivation test~~ — `LoansContractDerivationTest`, green
- [x] ~~Confirm our `plutus.json` is the deployed commit~~ — **yes**, proven by §4
- [x] ~~Promote the derivation from test into a runtime `LoansContractRegistry`~~ — §6
- [x] ~~Wire loan UTxO indexing by payment credential~~ — §6
- [x] ~~Cross-check the derived hashes against the live `ConfigDatum` at startup~~ — §6.1
- [x] ~~Read upstream README §"Bots logic", diff it against the validators~~ — §7
- [x] ~~Dutch auction — decide if it's in scope~~ — **out of scope**, D1: the LM
      actions only accept `LiquidationMode::Liquidation`
- [ ] Derive `lenderBondPolicyId` / `borrowerBondPolicyId` in `LoansContractRegistry`
      and verify them against `ConfigDatum[5]` / `ConfigDatum[4]` (§7.6)
- [x] ~~Decode `LoanDatum` (`lib/fluidtokens/types/loan.ak`) → Java~~ — hand-written
      (`model/loans/*` + `LoanDatumConverter`); blueprint codegen is not viable, see
      design §5. Pinned by `LoanDatumConverterTest` (56 live preview datums) and
      `LoanDatumSchemaTest` (field order/constructor indices vs `aiken build -I`)
- [x] ~~Expose the indexed loans over HTTP~~ — `GET ${apiPrefix}/loans`, `LoanController`
- [ ] Port the health-factor math from `lib/fluidtokens/finance.ak`, with parity
      tests aimed at `loan_claim_action.ak` (§7.4)
- [ ] Mainnet coordinates — v4 mainnet deployment status still unconfirmed

## 9. Reproducing this

```bash
KEY=$(grep -E '^BLOCKFROST_KEY=' .env.preview | cut -d= -f2 | tr -d ' "')
BASE=https://cardano-preview.blockfrost.io/api/v0
ASSET=f1a475ea8cccc1e0b7a59b10e79ad171452dc057ffb1cda0df92835c706172616d6574657273

curl -s -H "project_id: $KEY" "$BASE/assets/$ASSET"
curl -s -H "project_id: $KEY" "$BASE/assets/$ASSET/transactions?order=asc"
curl -s -H "project_id: $KEY" "$BASE/txs/6de7b7ec…094/utxos"
curl -s -H "project_id: $KEY" "$BASE/scripts/datum/0750bb70…e5b1"
curl -s -H "project_id: $KEY" "$BASE/scripts/datum/17c9be65…bb30"
```

## 10. Liquidation economics — who receives what (verified at `bbe9c1a`, 2026-08-16)

Settled because an intuition that the liquidator repays the debt and seizes the collateral
(Aave/Compound shape) does **not** describe v4, and the difference decides whether the bot can
ever be profitable.

**Plain `Liquidate` (`lm_liquidate_action.ak`).** The liquidator pays nothing and receives
nothing except the fee slice. `liquidationFee = loanCollateralAmount * liquidationFeePerMille
/ 1000` (:118-119) is used **only** as a subtraction: `loanCollateralThatLenderShouldReceive =
loanCollateralAmount - equity - liquidationFee` (:123-124). The collateral goes to an
`asset_manager` output whose `ownerAsset` is the **lender's bond** (:147-157), checked with
`>=` (:204-209). The validator never checks where the remainder goes, so the fee is simply the
slice not required to reach the lender — the executor keeps it as change. **There is no
FluidTokens treasury address in the action: the fee is the liquidator's reward, not a platform
cut.**

**`LiquidateAndPayInAdvance`.** The bot does front principal and keep the collateral, but the
lender is made whole at the collateral's **oracle value**, not at the debt:
`convertedLoanCollateralToPrincipalAmount = convert(collateralAmount - equity -
liquidationFee)` (:172-179). The bot pays out approximately the collateral's full value and
receives the collateral, so its gain is `equity + fee` in value terms — **not** the
over-collateralisation spread.

**Where the spread actually goes.** `equity` returns to the **borrower** (the excess above debt
plus penalty); the penalty accrues to the **lender**. The liquidator's compensation in every
variant is `liquidationFee` and nothing else.

**Consequence.** With `equity == 0` — the only shape the deployed contracts can satisfy — and
`liquidationFeePerMille == 0`, the liquidator's profit is exactly zero in **every** variant,
including the parked ones. Building `LiquidateAndPayInAdvance` would not make such a loan
profitable. Our profitability gate (fee value − tx fee − margin) therefore models the right
quantity.

**Measured population, preview, 2026-08-16.** All ten live loans carry
`liquidationFeePerMille = 0`; six also carry `shouldLiquidationConvertToPrincipal = true`.
So no third-party liquidator can profit from any of them at any price. The open question this
raises for the epic's value: **is zero also the default on mainnet?**

## 11. Origination: three traps in the request path (verified at `bbe9c1a`, 2026-08-16)

Found while scoping T-016. Nobody has ever exercised this path on preview — `requestPolicyId`
never minted and both request scripts 404 from Blockfrost, so all ten live loans came through the
pool path. That means there is no on-chain example to copy and no published reference script for
the request validator (the largest, at 9,628 bytes). Everything below comes from source.

### 11.1 `minPrincipal` is not a principal floor

Despite the name, `minPrincipal / minPrincipalDivider` is **collateral units per single unit of
principal**. The floor on the lent amount is `ceil(collateralAmount / (minPrincipal /
minPrincipalDivider))` — `lib/fluidtokens/finance.ak:144-151`. So `minPrincipal = 3` against 300
tFLDT (`300_000_000` units at 6 decimals) yields a floor of `100_000_000` lovelace, not a floor of
3 anything.

Read the field name and set `minPrincipal = 100_000_000` "because we want a 100 ADA floor" and you
author a request that **can never be filled** — the floor becomes `ceil(300_000_000 / 100_000_000)`
= 3 lovelace, and the check that was supposed to protect the borrower protects nothing. The failure
is silent at origination and only shows up as a loan on terms nobody intended.

### 11.2 The request NFT is 29 bytes; the other three are 28

`check_mint` (`request.ak:137-195`) requires `bytearray.at(assetName, 0) == index` and
`bytearray.drop(assetName, 1) == inputRefHash`, so the request NFT's name is a **1-byte index
prefix** (`0x00` for a single token) followed by `blake2b_224(serialise_data(outputRef))`.

The loan NFT, borrower bond and lender bond are **28 bytes with no prefix**, and they hash a
*different* output reference — `hash_output_ref(input.output_reference)` of the request UTxO,
computed in `check_lend` (`request.ak:271`), not the mint seed. Two hashes of two different
`OutputReference`s, one prefixed and one bare. Getting this wrong strands the collateral: the mint
succeeds only for the exact name the validator recomputes, and every later transaction that names
the token must agree.

### 11.3 A burn still needs a genuinely spent seed

Both `Cancel` and `Lend` require `quantity_of(self.mint, requestPolicyId, requestId) == -1`, which
**invokes the mint handler**. `check_mint` filters minted tokens to `quantity > 0`, so the
token-accounting half degenerates to `True` on a pure burn — but **`isInputRefSpent` sits outside
that filter and still applies.** Every burn must therefore supply a `RequestMintRedeemer` whose
`inputRef` names a UTxO the transaction actually spends.

The natural assumption — "a burn needs no seed" — is wrong, and produces a transaction that fails
on chain for a reason nothing in the burn path suggests.

### 11.4 The mint handler never reads the datum

`check_mint` checks the seed and the token accounting. It does not look at the request output's
datum — not its shape, not its type, not its presence. So a green evaluation of the mint proves
nothing whatsoever about the `RequestDatum`. The first evaluation-level arbitration of that datum
is `Cancel`, which does `expect datum: RequestDatum = inputDatum` before authorising. That is a
second and stronger reason to build the escape hatch before anything is submitted: it is not only
the way out, it is the first thing that tells us the datum was ever right.

### 11.5 `check_cancel` constrains no output at all

`request.ak:197-217` is three conjuncts and **not one of them is an output**:

```aiken
quantity_of(inputValue, requestPolicyId, requestId) == 1,
quantity_of(self.mint, requestPolicyId, requestId) == -1,
authorize_action(create_auth(borrowerAuth, .., self.extra_signatories, self.mint)),
```

The validator checks that the request NFT was held, that it is burnt, and that the borrower
authorised. **It does not check where the collateral goes** — not by address, not by datum, not by
value. "Cancelling returns the collateral to the borrower" is a property of the *off-chain*
construction alone. A Cancel that sends 300,000,000 tFLDT base units to a stranger, or to an
address nobody holds a key for, passes every on-chain check.

This makes the escape hatch's safety entirely ours. Any Cancel builder must assert the collateral
destination on the finished transaction body, and any operator-facing path that submits one needs a
named veto on the change address. Note the asymmetry with the *expiration* path:
`check_cancel_after_expiration` DOES constrain the output (`validate_collateral_less_penalty_output`,
`:394-419`, requires `outputAda >= inputAda - penalty` and `borrowerOutput.address ==
borrowerAddress`) — because there the canceller is a stranger. When it is the borrower, the contract
reasonably assumes they will not rob themselves. An automated borrower is exactly the case that
assumption does not cover.

### 11.6 A Cancel is a three-script transaction

`request` has only `mint` and `withdraw` handlers — `else(_) { fail }` at `request.ak:132-134`. The
Cancel logic lives in the **withdraw** purpose, while the request UTxO itself sits at a
`general_spend` wrapper with its own spend purpose. So one Cancel runs:

| purpose | script | why |
|---|---|---|
| Spend | `general_spend` applied → `requestSpendScriptHash` | the UTxO's own validator; with an inline datum its only check is that a withdrawal from the request policy exists (`general_spend.ak:31-41`) |
| Withdraw (amount 0) | `request.request` | where `check_cancel` actually lives |
| Mint (−1) | `request.request` | the burn, which re-enters `check_mint` (see §11.3) |

Two consequences. Anyone sizing or pricing a Cancel from the mint transaction's numbers will be
wrong — both scripts travel in the witness set, neither has a published preview reference script,
and `general_spend` adds ~1.1 KB on top of `request.request`'s 9,628. And the withdraw-0 coupling is
load-bearing in a way that is easy to miss: **remove the withdrawal and the *spend* leg fails**,
not the withdraw leg, because the spend wrapper's entire job is to check that the withdrawal is
there.

## 12. `LoansConfigVerifier` does NOT detect a redeploy (measured 2026-08-17, the first real test)

**The epic believed, from day one, that this verifier was the redeploy detector.** CLAUDE.md said it
"hard-fails on the next one — that failure is an answer, not an outage". `application.yaml` says a
redeploy "turns these into a hard startup failure". The PR draft said it "hard-fails on a mismatch,
which is how we detect a redeploy". **All of that is false, and a real redeploy has now proved it.**

FluidTokens redeployed preview (third deployment: 14 Jul → 5 Aug → 17 Aug). Coordinates verified on
chain:

| | old (what we ship) | new |
|---|---|---|
| config ref UTxO | `6de7b7ec…dca12094` | `7374a985…e54be781` |
| config policy id | `f1a475ea…df92835c` | `c45d5306…4032aaa9` |
| LM config policy id | `d0998754…d4f4a3e3` | `de1b8b40…fc731484` |
| config asset name | `706172616d6574657273` | **same** — hardcoded in `constants.ak` |

Both new NFTs were minted in that one transaction, `mint_or_burn_count: 1`, sitting at outputs 0 and 1
— structurally identical to the old deployment.

**And `LoansConfigVerifierLiveTest` PASSES RIGHT NOW, against live preview, after the redeploy.** Two
tests, both ran, both green.

**Why, and it is obvious in hindsight.** The verifier locates the config NFTs **by the policy id it is
pinned to**, reads their inline datums, and compares every derived hash. A redeploy **mints a fresh
config NFT under a fresh policy id and leaves the old one alone** — measured: the old config NFT still
has `quantity: 1`, unburnt. So the pinned coordinates still describe something real and self-consistent
on chain. Every check passes. **The verifier detects MUTATION OF THE DEPLOYMENT IT IS PINNED TO. It has
no way to notice that a different, newer deployment exists.** Those are different questions and only
the weaker one was ever being asked.

**The operational consequence is worse than a missing alarm.** A node booted today against the new
deployment will start cleanly, verify cleanly, and report **zero liquidation candidates** —
indistinguishable from a quiet market. **Nothing anywhere in the system says "you are pinned to a
deployment nobody uses."**

**Correction, same day, and the mechanism matters more than the symptom.** This section first blamed
stale `sync-start-*` values, claiming the node "will not index the new deployment's UTxOs at all."
**That is false.** `sync-start-*` is a *start* point and indexing runs **forward** from it, so pinning
an *earlier* block is inclusive of everything after — §2 of this document always said it was a safe
lower bound where "nothing is missed", which is exactly the reasoning I failed to apply to my own
claim. The dangerous direction would be a sync-start *after* the new deployment, which is the opposite
of what we had.

The real cause is the **payment-credential filter**: `TankUtxoStorage` keeps only UTxOs whose payment
credential appears in `LoansContractRegistry.indexedPaymentCredentials()`, and those are derived from
the pinned config policy ids. So the new deployment's transactions **are** indexed off the chain and
then **discarded at the application layer**. Right symptom, wrong mechanism — and the remedy differs
completely: nothing to fix in `sync-start-*`, everything to fix in the policy ids.

Worth keeping the error visible rather than editing it away, because of its shape: **a correct
conclusion reached through a wrong mechanism is more dangerous than a wrong conclusion**, since the
conclusion survives scrutiny and carries the bad mechanism along with it.

**What would actually detect it** (none of which exists): watching for a mint of asset name
`706172616d6574657273` under *any* policy id; asking FluidTokens; or an aliveness check — the config
UTxO being unspent is necessary but not sufficient, since a superseded one stays unspent forever. The
cheapest honest version is probably the last: assert that the *loans* we index are non-empty and
recent, and treat a persistently empty world as a suspected redeploy rather than as calm.

**This is the same shape as every other defect in this epic**, and the most consequential instance of
it: a guard that cannot fire, believed for weeks to be the thing that would fire. It was written to
answer a question, it answers a narrower one, and **nobody asked which** — because the artefact and its
test shared the assumption that a redeploy would move the thing being watched.

## 13. `equityInPrincipalCurrency = True` makes a loan un-liquidatable by any bot (CITED, verified here)

**Provenance: found by the `ft-cardano-loans-v4-testing` suite (their F-12, execution-verified,
graded medium). Verified independently in our clone before recording.** Confirmed at `ff005fb`: **all
four** lender-manager liquidation actions carry `expect equityInPrincipalCurrency == False` —
`lm_liquidate_action`, `lm_liquidate_and_convert_action`,
`lm_liquidate_and_pay_in_advance_action`, `lm_liquidate_pay_in_advance_and_compound_action`. One
occurrence each, grepped at that commit.

Meanwhile `loan_claim_action` implements **both** branches, choosing the equity asset from the flag:
```aiken
equityAssetPolicyId: if equityInPrincipalCurrency {
  datum.principalAsset.policyId
} else {
  datum.collateral.policyId
},
```

**So a loan whose borrower chose equity in principal currency can only ever be liquidated MANUALLY, by
the lender invoking `loan_claim_action` directly. The entire bot infrastructure is unavailable to it** —
and the upstream README sells exactly that infrastructure as the point ("bots compete to execute these
transactions and earn liquidation and compounding fees").

**Why this is a finding rather than another entry on the constraint list**, and the distinction is worth
keeping: the other abort sites in these validators are reached by **mistakes** — a wrong index, a
missing name, a one-token collateral. **This one is reached by using the protocol as offered.** A
borrower picks a documented option, `loan_claim_action` honours it, and every automated path refuses it
by **raising rather than denying** — so a liquidator has no way to learn that this class of loan is
simply not theirs to take. It looks identical to a bad index or a stale reference input.

**Not a problem for our current target:** loan `124e7c5d…` carries `equityInPrincipalCurrency = false`
(re-fetched from chain and re-decoded to be sure), so it is liquidatable by the bot path. **Recorded
because it is the abort that would have been hardest to diagnose, and because it belongs in what we
tell FluidTokens** — a documented borrower option that silently excludes the liquidation network they
advertise is worth their attention regardless of our epic.

**Complementarity worth stating once, since two suites now feed each other:** their tests are stronger
on **individual constraints**, because they can vary one field at a time against a validator in
isolation. Our dry-eval is stronger on the **two-validator interaction**, because it runs both against
the real machine in one transaction. Neither supersedes the other, and this finding came from the half
we do not cover.

## 14. `pool_cancel_action` constrains no output — the escape hatch has no on-chain destination (verified at `ff005fb`, 2026-08-18)

**Provenance: found while building T-016-K (the pool-cancel builder, the recovery path for a pool we
fund). Read from `git show ff005fb:validators/pool/pool_cancel_action.ak` and dry-eval-verified against
the real PlutusV3 machine; audited independently from source.** Note the validator lives at
`validators/pool/pool_cancel_action.ak` (the `pool/` subdirectory), not at the repo root.

The entire withdraw handler is one `indexed_all` over the pool inputs with a **three-conjunct**
predicate, and it reads `self.reference_inputs / inputs / mint / withdrawals / extra_signatories` —
**never `self.outputs`**:

```aiken
utils.indexed_all(poolInputs, fn(index, input) {
  expect InlineDatum(inputDatum) = input.output.datum
  expect datum: PoolDatum = inputDatum
  let redeemerAction = safe_list_at(redeemer.actionsForEachInput, index)
  and {
    quantity_of(input.output.value, poolPolicyId, redeemerAction.poolId) == 1,   // A: NFT held on input
    quantity_of(self.mint, poolPolicyId, redeemerAction.poolId) == -1,           // B: NFT burnt
    authorize_action(create_auth(datum.lenderAuth, self.inputs, self.withdrawals,
                                 self.extra_signatories, self.mint)),            // C: lender authorised
  }
})
```

**Two consequences, both funding-relevant:**

1. **No on-chain destination for the recovered ADA.** Nothing constrains any output by address, datum,
   value, or position. The chain would accept a cancel that ships the pool's ADA to a stranger.
   Recovery-to-the-lender is a **purely off-chain change-address property** (`withChangeAddress`), which
   the builder sets and the dry-eval asserts on the finished body (no output at the pool address, every
   output at the funder, `returned == pool + funder − fee`). **Any production recovery/liquidation path
   that reuses this must enforce the destination off-chain — there is no on-chain backstop.** This mirrors
   `request.ak`'s `check_cancel` (§11.5–11.6): the cancel family is output-free by design.

2. **No emptiness / unlent gate.** The predicate decodes `PoolDatum` but reads only `datum.lenderAuth`;
   no conjunct anywhere in the call chain gates cancel on pool state (active loans, lent amount). Cancel
   recovers only the ADA physically in the pool UTxO — lent ADA lives in loan UTxOs — but that is a fact
   of *where the value sits*, not an on-chain guard. There is no "pool must be unlent" check to rely on
   or to violate.

**Adjacent, same slice — `pool.pool`'s `check_mint` does not police burns.** It builds
`mintedTokens = list.filter(tokens(self.mint, policy), quantity > 0)`, so a burn (`−1`) is filtered out
and `check_mint` reduces to `isInputRefSpent`. The **sole** burn-correctness check for a pool cancel is
`pool_cancel_action` conjunct B (`== -1`); a wrong-name or wrong-count burn passes the pool policy itself.
A defense-in-depth boundary worth knowing for whoever builds the compound / sell-position actions.

**Why recorded:** the funding decision for T-016-X rests on "we can get our ADA back out." We can — but
the guarantee is *we build the tx correctly*, not *the chain protects us*. That distinction is exactly
the kind that reads as safe until the day an off-chain bug sends recovery to the wrong address with no
validator to catch it.

## 15. The Pool Manager NFT convention, the orphan lock, and a self-inflicted footgun (verified at `ff005fb`, 2026-08-19)

**Provenance: found when FluidTokens flagged that our factory's pool "didn't mint a pool manager nft".
Read at `ff005fb`; some points reproduced against the real deployed validators via dry-eval (T-024).**

**The PM NFT is optional at the validator, expected by convention.** `validators/pool.ak` never mentions
the pool manager; the pairing is enforced only from the PM side (`pool_manager.ak`'s mint checks the pool
mint, not the reverse). README: mandatory *"for automatic compounding"*, *"otherwise leave it empty"*.
So a PM-less pool is a legitimate configuration that **can never be compounded by anyone** — nothing is
lost, but `lm_compound_action` / `lm_liquidate_pay_in_advance_and_compound_action` cannot be built
against it (`poolId != ""` gate plus a required PM UTxO that does not exist). Liquidations are unaffected.

**The orphan lock (D-15).** `pool_cancel_action` constrains no mint but the pool NFT's own, so a pool can
be cancelled **without** burning its PoolManager — and `pm_cancel_pool_manager.ak:55,87-91` then requires
a **live pool holding the matching NFT**, so the orphaned PM UTxO becomes permanently unspendable (its
min-ADA only). A front-end that always co-burns avoids it; a hand-built cancel would not. Our T-024 cancel
path burns the PM in the same transaction and, with the pool's `lenderAuth` delegating to the PM, refuses
outright to cancel without it.

**The `//No staking check here` comment at `pool_manager.ak:166` is stale.** It claims *"We don't check
the destination of the minted NFTs"*, but `:121-127` enforces `Script(poolManagerSpendScriptHash)`, a
single token at quantity 1, and a well-typed `PoolManagerDatum`. The code is stronger than its comment.

**The self-inflicted footgun (D-16) — unreachable as an attack, real as a footgun.** A bond's
`lenderAuth` is read by `lm_withdraw_bonds_action` to release the bond, and by
`lm_liquidate_and_convert_action` (written into a Minswap order's `canceller`). If it were
`CardanoWithdrawScript(poolManagerPolicyId)`, satisfying it needs only a withdrawal from
`pool_manager.poolManager` → `pm_update_pool_manager`, whose body is
`and { length(poolInputs) == 0, indexed_all(poolManagerInputs, …poolOwnerAuth…) }` — and
`utils.indexed_all` **returns `True` on an empty list** (`utils.ak:203-209`), so a tx with zero pool
inputs and zero PM inputs satisfies it unconditionally (only the config NFT reference input survives).
The bond-spend path has **no second gate** (`lm_withdraw_bonds_action.ak:19-25` asserts only
`authorize_action(lenderAuth)` — no output, no value preservation, no signature). **So a bond delegated
that way is withdrawable by anyone, along with the asset-manager vault its NFT gates.**

BUT it is **UNREACHABLE by a third party**: `pool_borrow_action.ak:356-357` fixes the bond datum to
`blake2b_256(serialise(datum)) == poolDatum.lenderBondInlineDatumHash`, pre-committed by the **pool
creator** at pool creation — not copied from the pool's own `lenderAuth`, not free-form per-borrow, not
influenceable by any borrower or attacker. The only party who can commit the vulnerable shape is the
bond's own owner, against their own interest; FluidTokens' tooling commits a `CardanoSignature`
(observed: preview loan `714e923d…`, bond auth `CardanoSignature(ea1bb1cc…)`). **Verdict: footgun with a
live trigger nobody sane pulls, not an exploitable vulnerability. Advisory to FT optional; no PoC, no
security disclosure.** Not exhaustively verified: no chain-wide scan of live bond datums for a
delegation-shaped preimage.

**Why our pool delegation (T-024) is safe from the same hole.** Every path that consumes the *pool's*
`lenderAuth` (`pool_cancel_action.ak:49`, `pool_compound_action.ak:99`) **spends a pool**, so
`poolInputs > 0` fails `pm_update`'s `length(poolInputs) == 0` and forces the tx through
`pm_cancel_pool_manager` (re-checks `poolOwnerAuth`) or `pm_compound_liquidity`. The empty-fold hatch
opens only where there is no pool input — the bond withdrawal — and that is exactly where we use a
signature, not the delegation.

**D-17 — `pm_compound_liquidity` authorises nothing of its own.** Its body gates purely on two sibling
redeemers' action tags (the pool withdraw redeemer is `Compound`, the LM withdraw redeemer is one of the
compound actions). The "pool owner authorises compound" reading is *not* what the validators do — the
authorisation chain is circular (`pool_compound_action` → PM delegation → `pool_manager.withdraw` →
`pm_compound_liquidity` → back to `pool_compound_action`'s redeemer tag) and is benign only because
`pool_compound_action` independently constrains the output to `addedPrincipal > 0` with the pool's datum,
address and remaining value `equals_data`-identical, so that branch can only *donate* to the pool.

**D-18 — `indexed_all([]) == True` is a general vacuity shape, not a one-off.** Any validator whose only
real check lives inside an `indexed_all` / `list.all` predicate is unconditionally satisfiable when the
iterated list is empty. `pm_update_pool_manager` is where it bites because it also demands the list be
empty (`length(poolInputs) == 0`). Other call sites are protected by an outer non-emptiness constraint or
by being irrelevant when empty — a per-site accident, not a property of the helper. Worth an upstream
audit note.
