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

> Preview sync start is slot `71971209` (the Aquarium preview ref input), which is
> **older** than the v4 config mint at slot `118496146` / block `4514052`, so a
> preview sync already covers the whole v4 history. No sync-start change needed.

## 7. Open items

- [x] ~~Derivation test~~ — `LoansContractDerivationTest`, green
- [x] ~~Confirm our `plutus.json` is the deployed commit~~ — **yes**, proven by §4
- [x] ~~Promote the derivation from test into a runtime `LoansContractRegistry`~~ — §6
- [x] ~~Wire loan UTxO indexing by payment credential~~ — §6
- [ ] Cross-check the derived hashes against the live `ConfigDatum` at startup
      (currently the config UTxO is indexed but not read back and compared)
- [ ] Decode `LoanDatum` (`lib/fluidtokens/types/loan.ak`) → Java
- [ ] Port the health-factor math from `lib/fluidtokens/finance.ak`
- [ ] Read upstream README §"Bots logic", diff it against the validators, and
      record every discrepancy here
- [ ] Mainnet coordinates — v4 mainnet deployment status still unconfirmed
- [ ] Dutch auction absent on preview (§3.1) — decide if it's in scope

## 8. Reproducing this

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
