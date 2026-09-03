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

> **SUPERSEDED 2026-08-28. The sha below is no longer the deployed one, and this paragraph
> misled a later reader into enumerating validator types from a seven-week-old version.**
>
> **The deployed commit is `e0b818e` (2026-08-19, "fixed R-03 and rewritten assetManager to
> avoid using input indexes"), sha256 `a55a1c2e723a6d1222e464bb32adc1abe1d669ca6d00205d50459122adf7b98f`.**
> Verified by hashing our vendored artifact and every upstream `plutus.json`: ours matches
> `e0b818e` and no other commit.
>
> The artifact has moved twice since this paragraph was written — `8a57b61` (2026-08-17, third
> deployment) and `349a054` (2026-08-25, fourth) — and **the local clone's HEAD is `bbe9c1a`,
> which is OLDER than the deployed commit.** So `git show HEAD:<path>` in that clone reads a
> version the chain is not running, silently. Always address it as
> `git show e0b818e:<path>`, and re-derive the sha rather than trusting this line: the way to
> find the deployed commit is to hash the vendored artifact and search upstream for the match,
> which takes one command and cannot go stale.

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

**Measured population A — preview, 2026-08-16 (SUPERSEDED, see B).** All ten live loans carried
`liquidationFeePerMille = 0`; six also carried `shouldLiquidationConvertToPrincipal = true`. So no
third-party liquidator could profit from any of them at any price. The open question this raised:
**is zero also the default on mainnet?**

> ## ⛔ AMENDED 2026-08-26 — **THE POPULATION TURNED OVER AND THE CONCLUSION INVERTED**
>
> **Measured population B — preview, 2026-08-26.** Every one of the **seven live lender bonds** at
> `addr_test1zrwj6lelm5x206nga9y39s7nxtcc9xdkajy9g4m5jtgqd6cu2cs6p5lhaegyrm8per6p48mpr26tegngjg7zrdk23hpsju03se`
> carries **`liquidationFeePerMille = 50`**. **Zero of seven are zero-fee.** Read as field 3 of
> `LenderManagerDatum` from each bond's inline datum — the field itself, not inferred from a split.
>
> **⇒ The paragraph above is not wrong; its POPULATION is gone.** Every loan measured on 08-16 has
> been settled or superseded, and every bond alive today pays **5%**. *A measured claim whose
> population has moved underneath it is the most dangerous kind of stale fact: **every word of it was
> true when written.***
>
> **⇒ So "the liquidator's profit is exactly zero in every variant" holds of population A and is FALSE
> of population B**, and the open question — *is zero the default?* — is answered **no** for every
> preview loan now in existence.
>
> **Corroborated on chain by the first real liquidation**, `49743a1e9ef4b0e7756f2143f89fbb2e1a4e274d8a19068ae5d6f0e5244755f7`:
> its 100,000,000 collateral tokens split **2,410,366 equity / 92,589,634 lender / 5,000,000 to the
> bot** — conserved to the unit, matching `lender = collateral − equity − liquidationFee`, and
> `5,000,000 / 100,000,000` is exactly **50 per mille**. **The datum read and the on-chain split agree.**
>
> ⚠ **Two consequences that are NOT this document's to decide.** (1) The `-2000000` profit margin was
> set because liquidations looked structurally unprofitable — **that premise has moved.** (2) The bot's
> take is now a *computed fee* rather than an unexplained residual, which is what makes paying it out
> by name viable and may supersede the change split entirely. Both are Giovanni's.

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

## 16. The FOURTH deployment IS `e0b818e` — and the measurement that said otherwise never varied its variable (2026-08-25)

**Confirming PLAN.md's original identification, and retracting the "correction" that briefly stood here.**

Measured against live preview, **both arms against the FOURTH coordinates**, with the sha256 of the
blueprint actually loaded printed from the classpath on each run:

| blueprint | fourth coordinates (`d46f626f…` / config UTxO `8dd38e97…`) | result |
|---|---|---|
| **`e0b818e`** (`a55a1c2e…`) | yes | **0 mismatches — GREEN** |
| `ff005fb` (`5b150c3d…`) | yes | **10 mismatches** — `ConfigDatum[3,9,11,12,14,15]` + `LMConfigDatum[2,3,4,6]` |

The 10-mismatch arm is exactly the `@PostConstruct` failure the deployed image produced, reproduced
locally. **Blueprint and coordinates are a matched pair; either half alone cannot boot.**

### 16.1 How the wrong answer was produced — the part worth keeping

An earlier revision of this section claimed the fourth deployment was `ff005fb` re-minted with no
code change. That was wrong, and the mechanism is more useful than the conclusion:

**`LoansConfigVerifierLiveTest` hardcoded the THIRD deployment's policy ids and never read
`application.yaml`** — while its own javadoc cited it as the check for whether the coordinates in
`application.yaml` had gone stale. `application.yaml` was repinned, that test was run, it came back
green, and the green was read as evidence about the new coordinates. **Both arms of the 2×2 were
therefore scored against the third deployment**, where `ff005fb`-green and `e0b818e`-red are simply
correct and say nothing whatever about the fourth.

**The run did assert proof-of-variant — on the blueprint, by sha256, on both arms, deliberately. It
did not assert it on the coordinates, and the unasserted input was the one that never propagated.**
An experiment that does not manipulate the variable it claims to manipulate produces a real,
reproducible, entirely meaningless result — and it is *more* convincing than a correct one, because
both arms are clean and the numbers are stable.

Fixed: the live test now reads the shipped coordinates out of the preview profile and prints them.
There is no constant left in it to drift. Generalised in `fabbrica` `verification-harness` §9a-ii.

**The accusation this section previously levelled at the original 2×2 — that it was scored against a
table derived from one of the candidates — is withdrawn.** It was a mechanism invented to explain a
discrepancy that the broken measurement had itself manufactured. The original identification was
right.

### 16.2 Superseded config NFTs are never burnt — measured, not assumed

Unaffected by the retraction above, and the reason §12 is structural. Queried 2026-08-25: for
**both** the third (superseded) and fourth (live) deployments, config and lm-config NFTs are all
`quantity=1`, `mint_or_burn_count=1`, still sitting at their addresses.

**A liveness or existence check on the pinned config UTxO therefore cannot ever fire.** That is the
constraint `DeploymentLivenessProbe` (R2) was designed around: it asked whether anything *new* had
appeared at the credentials the pin derives, which shares no input with the pin's own contents.

> **⛔ The probe was REMOVED 2026-08-25 (Giovanni's call).** The constraint above is unchanged and
> still structural — **the node now has no detector for a dead pin at all.** `LoansConfigVerifier`
> cannot acquire one: both halves of its comparison come from the pin. Until something replaces it,
> **a persistently empty world is a suspected redeploy and the config policy id must be checked by
> hand.** The cheap replacement, if one is ever wanted, is a single field on the healthcheck — the
> newest indexed slot across `indexedPaymentCredentials()` and its age — not a scheduled service.

### 16.3 The two loans that prompted the repin

`caa42146…` (slot 120990298) and `3d839207…` (slot 120991899), both from FluidTokens. Each
references the fourth deployment's config as a reference input; neither contains the third
deployment's coordinates anywhere. Output #1 of each is the loan UTxO and decodes as a 17-field
`LoanDatum` matching `LoanDatumConverter` exactly:

- `LiquidationMode.Liquidation(100, 125, 100, false)` — liquidatable, not either `NoLiquidation*`
- `RepaymentMode.PerpetualLoan(28, 5)`
- field 15 = `"POOL"` + pool id — pool-funded
- collateral tFLDT, principal 40 ADA, principal and interest assets both ada

Compared against all **56** real preview datums in `src/test/resources/loans-v4/preview-loan-datums.hex`,
these are the **majority variant** — not a new shape. Note the naming trap: these are *pool-funded*
loans, which is not the same as needing the *compound* liquidation action. `src/main` has no compound
builder at all (`LiquidatePayInAdvanceAndCompoundTransactionBuilder` lives only in `src/test`), and
the two real liquidations of 2026-08-24 were built by `LiquidatePayInAdvanceTransactionBuilder` — the
convert path — on loans of exactly this shape.

**Corroboration worth recording:** deriving from `ff005fb` + fourth coordinates yields
`loanSpendScriptHash=31e0dc1d…`, `poolSpendScriptHash=bf8c4378…`, `loanPolicyId=2f1aa941…` — all of
which *do* appear in these two transactions. That is a reminder that partial agreement is not
agreement: those three hashes are among the few that do not move on a re-mint, and reading them as
confirmation is exactly how a wrong blueprint survives a spot check.

## 17. A successful liquidation disables the bot, and a starved collateral input produces a transaction no node can parse (measured 2026-08-25)

Two defects found on the same day, both **after** the first real liquidation
(`49743a1e…`, block 4,603,278, `valid_contract=true`, ledger size 12,216, fee 1,133,033), and
neither reachable by any test in the suite. Both were found by **decoding the artefact** —
the same instrument that settled the reference-script question hours earlier.

**Both are invisible to the builder's `assertStructure` (V5), and correctly so.** V5 checks
reference-input ordering, bond echoes against bond inputs, and asset-manager outputs at their
observed indexes — *everything the validators read*. It says nothing about the bot's own change
output or its collateral, because **no validator looks there.** A defect in the parts of a
transaction that only the ledger reads has no on-chain oracle to check it against.

### 17.1 The change output — a successful action creates the condition that blocks the next

The change came back as **one** output:

```
✅ 49743a1e…#0  1,000,000 lovelace  ada-only   ⇐ cardano-client-lib's withdrawal DUMMY, not ours
❌ 49743a1e…#4  9,964,993,434 lovelace + 5,000,000 tFLDT
```

`adaOnlyWalletUtxo()` refuses `#4` — correctly, it holds two assets. The wallet held **9,966 ADA
and could build nothing**; its only eligible input was a 1 ADA dummy CCL had emitted for its own
reasons. The tank processor is starved by the same filter (`ScheduledTransactionService:169`,
which takes `findFirst()` rather than largest, so it is more fragile still).

**Fixed by splitting the change in `postBalanceTx`** — re-shaping what the balancer produced needs
no knowledge of the payout economics, so it cannot drift from rule R the way a duplicated residual
calculation would. Installed inside `complete()`, so it runs in **both** passes of the layout probe
and the observed output indexes are computed against the post-split layout.

> **⛔ The tempting fix is the wrong one.** Carving a fixed small ada output before balancing takes
> the carve out of the *current* input, not out of the trapped pool: each liquidation leaves a fresh
> small ada UTxO and a large token-bearing one, and **the large one stays unspendable forever.** The
> working capital ratchets down to the carve size while the balance grows. It fixes the symptom for
> exactly one cycle and permanently strands the rest.

### 17.2 The collateral — a negative `MaryValue` is unrepresentable, so the transaction never exists

```
SUBMIT_FAILED   size 11,915
backend: TxCmdTxReadError — SIX decoder failures across eras
final:  DeserialiseFailure 1227  "MaryValue: expected array or int, got TypeNInt"

decoded from the CBOR at BYTE OFFSET 1227, the exact position the ledger named:
  body key 13  collateral_inputs = 49743a1e…#0   (the 1,000,000 ada-only utxo)
  body key 17  total_collateral  =  1,670,285
  body key 16  COLLATERAL_RETURN =   −670,285    ⇐ 1,000,000 − 1,670,285, exact
```

**The builder computed a collateral return from a collateral input that cannot cover the required
collateral, and emitted the negative result instead of refusing.** A negative `MaryValue` has no
encoding, so every era decoder rejected the CBOR **before any validation ran — not phase 2, not
phase 1. It never became a transaction at all.**

Three things are worth keeping:

- **The binding constraint is the collateral input, not the balance.** The transaction has four
  inputs and spends the collateral-carrying UTxO too, so the *body balances fine*. Collateral must
  be pure ada, so capacity was capped at 1,000,000 no matter how much the wallet held. Any fix must
  reason about **pure-ada capacity specifically.**
- **The other negative in the body is legitimate.** Key 9, `mint`, value −1 — an NFT burn. The first
  negative found is not automatically the culprit.
- **Only the sixth of six decoder errors names the real field.** Which is the day's signature for the
  third time: *`InsufficientCollateral` earlier was a symptom of a stale input; `DeserialiseFailure`
  now is a symptom of insufficient collateral.* **Twice the loudest error was not the cause.**

**Refused, not clamped.** Clamping the return to zero yields a transaction that *parses* and is still
wrong — it over-collateralises and moves the failure somewhere quieter. The shortfall is a fact about
the wallet, not a number to round.

**⚠ The required collateral is a CEILING.** `1,113,523 × 150 / 100` is `1,670,284.5` and the ledger
asked for `1,670,285`. Integer division understates it by one lovelace and rebuilds the same
unparseable transaction.

**Checked on the built body, not before building.** The requirement is a function of the fee and the
fee is not known until balancing has run; the only sound pre-build bound, `minFeeB × collateral_percent`
= 233,072, would have passed against a 1,000,000 capacity and **caught nothing**. Building is free —
no signature, no submission, nothing on chain.

### 17.3 The gap this exposes in the healthcheck

`adaOnlyWalletUtxo()` accepted that UTxO **correctly**: it was ada-only, datum-free and script-free.
It asks about **shape**. Nothing asked whether it was **large enough**, and `wallet_ok`'s 2 ADA floor
— the only place in the node encoding the ledger's collateral relationship — **reports rather than
gates**. This is that gap producing an unparseable artefact.

### 17.4 The first real liquidation — the full artefact, and the witness set measured on chain

> **⚠ RULE, learned the hard way 2026-08-26: an identifier that cannot be pasted into a query is a
> citation, not a reference.** `49743a1e…` is quoted correctly in three documents and a dozen
> messages, and **every one of those citations is useless for the only thing a hash is for.** It was
> never lost — it was *truncated*, consistently, at every site, which is why nobody noticed. A session
> spent a turn trying to query it and could not. **Write identifiers out in full the first time.**

```
tx        49743a1e9ef4b0e7756f2143f89fbb2e1a4e274d8a19068ae5d6f0e5244755f7
block     4,603,278     ledger size 12,216 bytes     fee 1,133,033     valid_contract / is_valid TRUE
```

**Witness set, decoded from the accepted transaction (Blockfrost `/txs/{hash}/cbor`, 2026-08-26).**
Body keys present: `0,1,2,3,5,8,9,11,13,16,17,18`. Witness keys: `0` (vkey), `5` (redeemers), `7`
(PlutusV3). **Five PlutusV3 scripts, and NO duplicates:**

| bytes | script hash | what it is |
|---:|---|---|
| 2,547 | `2f1aa941f437e351e3870f7247d735b2bc2952f1c7977426e8960d17` | **`loan.loan`** — the loan policy id (§16 corroboration) |
| 1,158 | `31e0dc1d75076e4f7795b24c4cc4b5515791bb4eff4af7961e404f3e` | `loanSpend` (§16 corroboration) |
| 1,158 | `dd2d7f3fdd0ca7ea68e94912c3d332f18299b6ec8854577492d006eb` | |
| 968 | `777aa0f117733d2c504c8ae56618b4196aa322fb75f9e2d67a6b85e6` | |
| 4,227 | `e0a13838d176cea9de466afe2075f38f682603013604021a3959700f` | |

**⇒ THE MEASUREMENT THIS SETTLES.** `loan.loan` is added to the witness set by **two independent
paths** in this builder — `attachRewardValidator` (`LiquidateTransactionBuilder:1626`, taken because
`loan` is not referenced) **and** `mintAsset` (`:1476`, which always attaches). **It appears exactly
once.** cardano-client-lib deduplicates at both add sites — `MintCreators:81-83` and
`ScriptCallContextProviders:173-174`, the latter commented *"To avoid duplicate script in list"* — and
the comparison is value-based, since `PlutusScript` is Lombok `@Data` over `(type, cborHex)`.

**⇒ Source and chain agree. Not double-attached.** Recorded because the source read alone would have
been an inference about what the ledger accepted, and **only the ledger witnesses that.**

**⚠ And it does NOT dissolve the latent defect it was checked against.** That defect is about the
*referenced* case: when `loan` travels as a reference input the attach is skipped but `mintAsset`
still puts it in the witness set, and **`ExtraneousScriptWitnessesUTXOW` needs one copy, not two.**
Dedupe cannot help. **`loan_claim_action` (8,662 bytes) is correctly absent from the table above** —
it travelled as a reference input, which is what the 2026-08-25 publication bought.

## 18. ⛔ OPEN QUESTION FOR THE PROTOCOL: a loan can be made permanently unliquidatable by a stranger

**Not a builder finding, and not fixable off-chain.** Recorded separately from the mitigation for that
reason.

**Anyone can send a token to a loan script address.** `loan_claim_action` governs how the collateral is
split, and the off-chain builder emits outputs for exactly three things: the borrower's equity, the
lender's share, and (now) the liquidator's fee — **all denominated in the loan's DECLARED collateral
asset.** Nothing pays out an asset the loan datum does not mention.

**⇒ So a loan UTxO carrying an unexpected token cannot be liquidated by this bot at all.** Cost to
whoever does it: **one min-UTxO and a transaction fee.**

**What we did about it (2026-08-26, T-056).** The builder now refuses such a loan by name and names the
offending unit. **That is a blast-radius reduction, not a fix:** before the refusal, the stray asset
reached the bot's change output, `adaOnlyWalletUtxo()` refused the resulting wallet UTxO, and **the
whole bot stopped** — the 2026-08-25 outage, reproduced by a stranger. After it, **that one loan is
skipped and the bot keeps running.**

**⇒ The loan remains unliquidatable either way.** Whether it is recoverable — whether the validators
permit a liquidation that ignores or sweeps an undeclared asset, or whether the borrower can withdraw
it — **is a question about the on-chain contracts, and this repo only consumes them.** It has not been
asked upstream.

**⚠ Provenance: DERIVED, not observed.** No loan has ever carried a stray asset; the measured
liquidation's UTxO was clean (`lovelace 3,000,000 · collateral 100,000,000 · loan NFT 1`). The
reachability argument is that a script address accepts any payment, which is a property of Cardano
rather than of these validators. **It has not been demonstrated on chain, and doing so deliberately
against FluidTokens' preview deployment would be a hostile act, not a test.**

---

## 19. The 2026-09-01 transaction decode (§19.1/19.2/19.7 stand — ⛔ §19.3–19.5 are RETRACTED, see §21)

> # ⛔ RETRACTION NOTICE — READ BEFORE §19.3
> **§19.3, §19.4 and §19.5 are WRONG and are retracted in full by [§21](#21).** They claimed a fifth
> deployment, a stale vendored blueprint and a bot blind to every loan. **None of that is true.** The
> comparison behind them put the **third** deployment's fixture registry against the **fourth**
> deployment's live config — two different deployments, which differ by design. The shipped registry
> derives the live config's hashes **exactly**. The original heading ("A FIFTH DEPLOYMENT IS LIVE …
> the bot is blind to every loan") is preserved here only so the error is legible rather than tidied
> away. **§19.1, §19.2 and §19.7 are chain facts and are unaffected.**

**Trigger.** Giovanni pointed at preview tx
`9088270f2d941354cb52594a33a6aba0dc29eb34a2d85fa6a852256d6ceefa59` (block 4,622,348,
2026-09-01 11:22:05Z) as "a loan w/ compound I think". Decoding it produced three findings, of
which the third outranks the request that prompted it.

### 19.1 The transaction CLOSED a loan; it did not create one

`/assets/{loanNft}/history` for
`2f1aa941…172e2048fcc960c6ee63c11fb4f231eac6e197a7c15a2585c3205de35e`:

```
minted  1281429c1ab09ef78894b3f67f47dcb7c80cb2f9a60128e84e143639c395f461
burned  9088270f2d941354cb52594a33a6aba0dc29eb34a2d85fa6a852256d6ceefa59   ← the named tx
quantity 0 · held by nobody
```

The spent input at `31e0dc1d…` carried the loan NFT, 3,000,000 lovelace and 200,000,000 tFLDT of
collateral; the collateral returned to the borrower and the loan NFT was burned. **There is no loan
at these coordinates to liquidate, and there never will be again.**

**Under the live loan policy `2f1aa941…`, nine loan NFTs have ever been minted and all nine are
burned. ZERO live loans exist on preview.** So no test can be pinned to a live loan today — not for
want of a builder, but for want of a loan.

### 19.2 It was a PERPETUAL loan, and "compound" is a different axis entirely

The spent loan's datum decodes to `repaidInstallments = 0`, `totalInstallments = 0`,
`principalAmount = 40,000,000`, `interestRate = 400`, `principalAsset = ADA`, and
`RepaymentMode = PerpetualLoan(28, 5)` (constructor alternative **2**). It is **not** a
compound-interest loan and **not** an installment loan.

**The word that caused the confusion is `installment_repayment`, and it is not on the loan.** It is
an ASCII tag inside the datum of output #0, which sits at `de8f8186…` — **config field 15**, a script
this repo had never recorded. That output is a *scheduled action*, and the tag names the action, not
the loan's repayment mode.

> **⚑ Terminology, because two different things are called "compound" and only one of them is a loan
> property.** In lending v4, *compound* names a **liquidation action that recycles proceeds into a
> lending pool** (`lm_compound_action`, `lm_liquidate_pay_in_advance_and_compound_action`,
> `lm_liquidate_convert_and_compound_action`). It is **not** compound interest. No `RepaymentMode`
> constructor expresses compound interest at all — the three are `InterestOnRemainingPrincipal`,
> `PrincipalAndInterestOnInstallments` and `PerpetualLoan` (§5). **A request to "liquidate the
> compound loan" is therefore two separate questions**, and the compound one is already answered:
> T-075 established algebraically that **no compound candidate is approvable at any loan size for any
> valid fee rate**, and `LiquidateConvertAndCompound` is a hard-`False` stub (§18 note, T-077).
> `src/main` contains **no compound builder at all**.

### 19.3 ⛔ THE FINDING THAT OUTRANKS THE REQUEST: the runtime derivation no longer matches the chain

The config UTxO at the **pinned** coordinates (`loans.config.policy-id = d46f626f…`,
`ref-utxo-tx-hash = 8dd38e97…`) is **current** — its datum lists the hashes the 09-01 transaction
actually used. What no longer matches is **our derivation**. Runtime `derivedHashes()` versus the
live config datum, same config policy id, same ref UTxO:

| field | derived by `LoansContractRegistry` | live on chain | |
|---|---|---|---|
| `loanPolicyId` | `4f84c6e3f4a7812d…` | `2f1aa941f437e351…` | ✗ |
| `loanSpendScriptHash` | `86356f7e64dc284e…` | `31e0dc1d75076e4f…` | ✗ |
| `poolPolicyId` | `65a0bc5e6e5152fb…` | `a33aee4034165f17…` | ✗ |
| `poolSpendScriptHash` | `c0be04e50016c124…` | `bf8c4378bab7de15…` | ✗ |
| `requestPolicyId` | `b5a224f1c7bdec3e…` | `39bef32eb5f696f6…` | ✗ |
| `requestSpendScriptHash` | `f02a3931f5b6a5f3…` | `978934c46206696e…` | ✗ |
| `borrowerBondPolicyId` | `eadc69a5d2d1357a…` | `eadc69a5d2d1357a…` | ✓ |
| `lenderBondPolicyId` | `bcd713bb7858d4b0…` | `bcd713bb7858d4b0…` | ✓ |

**19 of the config datum's 23 credential fields differ from `src/test/resources/loans-v4/preview-config-datum.hex`.**
Only the two bond policies survive, and the reason is diagnostic: they are derived with an integer
index (`bond.bond` applied to `i(1)`), not from the vendored compiled code, so **they are the two
that a validator rebuild cannot move.** Everything derived from `loans-v4.plutus.json` moved;
everything not derived from it did not.

**⇒ THE VENDORED `loans-v4.plutus.json` IS STALE. The validators were rebuilt upstream and the config
datum was updated IN PLACE, at the same config NFT, at the same ref UTxO.**

### 19.4 Why nothing detected it, and how this is WORSE than the §12 redeploy

§12 established that `LoansConfigVerifier` cannot detect a redeploy because a redeploy **mints new
config NFTs under a new policy id** while the old ones linger. **This deployment did not do that.**
The policy id and the ref UTxO are unchanged, so the verifier is not merely blind here — it is
looking at *the correct, current* config UTxO and **passing honestly**. The mismatch is not between
pinned and live coordinates; it is between the live config's **contents** and what our vendored
artefact **derives**. No amount of coordinate-checking finds it.

The consequence runs through the write-time filter, exactly as `officina:yaci-store-index-scoping`
describes: `indexedPaymentCredentials()` (`LoansContractRegistry:309`) includes
`loanSpendScriptHash`, which is the **stale** `86356f7e…`. Live loans sit at `31e0dc1d…`.
**`TankUtxoStorage` therefore discards every real loan UTxO at write time, leaving no trace one was
ever offered.** A node on this branch boots clean, verifies clean, derives 26 self-consistent hashes,
and reports zero candidates — **and today zero candidates also happens to be the truth (19.1), which
is precisely what would keep this hidden.**

> **⚑ THE GENERAL LESSON, and it is the same shape as the 08-31 `db-password` finding one layer up:
> the check that passes is not the check you need.** `LoansConfigVerifier` asks "is the deployment I
> am pinned to still intact?" and the answer is yes. **Nobody was asking "do the hashes I DERIVE still
> equal the hashes the config PUBLISHES?"** — and the config datum has been publishing the answer, in
> a field we read past, the whole time. **The two numbers were never compared, so agreement was never
> evidence.**

### 19.5 It has been observable in this repo since 2026-08-26

§16's witness-set table already records `2f1aa941…` as `loan.loan` and `31e0dc1d…` as `loanSpend`,
read off an accepted transaction on 2026-08-26 — **while `LoansContractDerivationTest` asserted
`4f84c6e3…` and `86356f7e…` for the same two fields.** The contradiction has been sitting in the
repo, in two documents, for six days. It was invisible because the derivation suite was already red
and the redness was attributed to fixture debt.

**⇒ A RED SUITE IS NOT A BACKLOG, IT IS A DISABLED ALARM.** The 38 failures were being read as
"fixtures need regenerating" — a chore. At least four of them were the chain telling us the
deployment had moved. *A failing test that is expected to fail conveys nothing when it starts
failing for a new reason.*

### 19.6 What this does NOT establish

- **Not** that the upstream source changed semantically. Only that the compiled artefact differs.
  Which upstream commit the live validators correspond to is **unresolved** — `e0b818e` is the sha
  §16 proved for the *fourth* deployment and it must be re-derived, not assumed, for this one.
- **Not** that re-vendoring is safe or sufficient. `loans-v4.plutus.json` must never be rebuilt
  locally (CLAUDE.md); the replacement must be fetched byte-identically from the upstream commit that
  the live hashes actually derive from, and that commit must be *identified by derivation*, not by
  tag or date.
- **Not** a mainnet statement. Lending is `enabled: false` on mainnet.

### 19.7 The redeemers settle it: a borrower's FINAL REPAYMENT, no pool, no liquidation (2026-09-02)

§19.1 inferred closure from the burn. The redeemers prove it, and name the action.

**Four redeemers (`/txs/{hash}/redeemers`, data via `/scripts/datum/{hash}/cbor`):**

| # | purpose | script | CBOR | decoded |
|---|---|---|---|---|
| 0 | `mint` | `2f1aa941…` loan policy | `d8799f02d87a8002ff` | `LoanMintRedeemer{configRefInputIndex:2, isPoolOrigin:True, originWithdrawRedeemerIndex:2}` |
| 1 | `reward` | `2f1aa941…` loan policy | `d8799f02d87a80ff` | `LoanWithdrawRedeemer{configRefInputIndex:2, action:` **`Repay`** `}` |
| 2 | `reward` | `c0f7e513…` | `d8799f029fd8799f01581c2e2048fc…d87a80ffffff` | `LoanRepayActionWithdrawRedeemer{configRefInputIndex:2, actionsForEachInput:[RepayData{borrowerBondOutputIndex:1, loanId:2e2048fc…,` **`isFinalRepayment:True`** `}]}` |
| 3 | `spend` | `31e0dc1d…` loanSpend | `d87980` | `Constr0[]` — the `general_spend` unit redeemer, delegating to the withdraw-0 validators |

**Three independent confirmations that this is `Repay`, and the first needs no source at all:**
1. **`c0f7e513…` IS `loanRepayActionScriptHash`** — live config field 12 (§19.3). The script identity
   alone names the action; it cannot be misread from a stale type definition.
2. `Action` alternative **1** = `Repay` (`Claim`=0, `Repay`=1, `ChangeCollateral`=2, `Recast`=3).
3. `isFinalRepayment` is documented upstream as *"Used only in Perpetual Loans to signal the last
   repayment that will close the loan"* — **independently corroborating §19.2's `PerpetualLoan(28,5)`
   decode**, from a different field of a different object.

`mint` field key 9 carries **`qty = -1`** — a pure burn, nothing minted. Fee 555,121.

**⇒ (a) The action is `Repay`, final repayment of a perpetual loan. NOT a liquidation.** No
lender-manager action script appears anywhere in the transaction, and `loanClaimActionScriptHash`
(`c6e0c439…`) — the claim path a liquidation would use — is absent.

**⇒ (b) NOTHING touches a pool, and no compounding occurs.** Live pool credentials
(`poolPolicyId a33aee40…`, `poolSpendScriptHash bf8c4378…`, `poolCompoundActionScriptHash`,
`poolManagerSpendScriptHash`) appear in **no input, no output and no redeemer**. The repaid funds went
to the **asset manager** (config field 15, `de8f8186…`), whose datum is
`AssetManagerDatumWithToken{ inputOutputReference: (1281429c…, 1), action: "installment_repayment",
data: …, ownerAsset: (bcd713bb… = lenderBondPolicyId, 2e2048fc… = loanId) }` — **45,000,000 lovelace
held for whoever presents the LENDER BOND.** Repayment escrowed for the lender is the opposite of
compounding into a pool.

> ⚠ The burn redeemer's `isPoolOrigin: True` says the loan *originated* from a pool. It does **not**
> mean a pool participates here: no pool script is invoked, so nothing pool-side was validated in this
> transaction. Origin is a property of the loan; compounding would be a property of the action.

**⇒ (c) The borrower initiated it.** `required_signers` (body key 14) is exactly one:
`65997c7f8d4dcd677096be2a6b3ba882be0f79b7280aa2193ba20c2b` — the borrower, whose stake credential
(`9e39d6f9…`) is the one on the loan address. Every non-collateral wallet input is theirs, and the
change returns to them. There are **two** vkey witnesses: the borrower, and
`55211e84a5f0bdbb0b60c46b25f887a03f05ac9146fd507acf3d102c`, which supplied **only the 5,000,000
lovelace collateral input** — it is not a required signer, takes no output, and earns no fee. A
collateral provider, not a batcher.

**⇒ (d) The `installment_repayment` tag is NOT a pending schedule, and this tx is not executing one.**
`action` is a free-form `ByteArray` label on the `AssetManagerDatum` — not a constructor, not a
trigger. This transaction **created** that asset-manager UTxO (the address holds exactly one, from
this tx). **It has nothing to do with the Aquarium node's Scheduled Transactions:** the preview tank
script is `421e1852…` (from the pinned tank ref-input `782106…#0`) and it appears nowhere in this
transaction.

> **The naming is the whole trap.** A `ByteArray` action label chosen by an off-chain builder reads
> exactly like a protocol concept, and this one collided with two of them at once — the node's
> *Scheduled Transactions* and v4's *installment* repayment mode. **The loan had `totalInstallments =
> 0`.** Only the redeemer and the script hash are load-bearing; a string in a datum is a comment.

---

## 20. The repayment-escrow COMPOUND action: mapped, and unprofitable-by-configuration on preview (2026-09-02)

Matteo Coppola Mazzetti (FluidTokens), relayed by Giovanni: *"Questo è l'azione di compound … hai la
liquidità ferma nello smart contract … bisogna prenderla e raggiungerla alla pool del proprietario …
Il bot prende una fee anche su questo."* This is a **different action** from §19.7's `Repay` and from
the compound-liquidation variants of T-075/T-077. It is the action that collects the escrow §19.7
found. Mapped here from the validators plus chain.

### 20.1 What the transaction must do

`lm_compound_action` (lender-manager withdraw) is the orchestrator. Per pool manager it:

- reads **asset-manager** inputs, expecting `AssetManagerDatumWithToken{ownerAsset, …}` — the escrow;
- reads the matching **lender bond** input, expecting `LenderManagerDatum{principalAsset, poolId, …}`;
- requires `poolId != ""` (*"If `""`, no compounding allowed"* — a pool-less bond is permanently
  uncompoundable, the §15 orphan shape again);
- requires the **PoolManager NFT** and the **Pool NFT** to both carry `poolId` as their asset name —
  `quantity_of(poolManagerInput.output.value, poolManagerPolicyId, poolId) == 1` and the same for the
  pool. **This is exactly the pair T-070 already resolves.**
- computes `compoundingFee = addedLiquidity * compoudingFeePerMille / 1000`, where
  `compoudingFeePerMille` comes from the **`PoolManagerDatum`**;
- constrains the pool output to `poolInput + (addedLiquidity − compoundingFee)` in the principal
  asset, everything else equal.

`pool_compound_action` (pool withdraw) independently enforces `addedPrincipal > 0`, an unchanged pool
address and datum, and `authorize_action(datum.lenderAuth …)`.

**⇒ THE BOT HOLDS NOTHING.** Both `lenderBondInputs` and the pool manager must be returned
**byte-identical** to their inputs (`builtin.equals_data(lenderBondInput.output, lenderBondOutput)`,
`builtin.equals_data(poolManagerOutput, poolManagerInput.output)`). The bot spends and restores them;
ownership derives from `poolId` in the bond datum, never from anything the bot must own. *That is what
makes this protocol-designed bot work rather than a privileged operation.*

**Where the fee comes from — and it is a residue, not a payment.** No validator sends the bot
anything. The pool is required to receive `addedLiquidity − compoundingFee`; the remaining
`compoundingFee` is simply **not constrained**, so it stays in the transaction and lands wherever the
builder puts it — the bot's own change. ⚠ **A builder that naively balances to the pool donates its
own fee and no validator objects.** The fee must be an explicit output, not an accident of balancing.

### 20.2 ⛔ It cannot be executed on preview today, for two independent reasons

**(1) The escrow from §19.7 targets a BURNED pool.** Its lender bond (`bcd713bb…2e2048fc`, live, held
at the LenderManager `dd2d7f3f…`) carries
`poolId = 001183812fdf3b07179ef385658e776a99b5477e7e54b8707112061ca5`. Under `poolPolicyId a33aee40…`
that asset's **quantity is 0**. Thirteen pools have existed; **exactly one is live**
(`00d3513725536642b6fe985ce9ec87d1ebb880497d92e0a8495bc6d0bf`). The two `quantity_of(… ) == 1` checks
are unsatisfiable for this escrow. **The 45,000,000 lovelace is not merely uncollected — its
destination no longer exists.**

Of the ten lender bonds at the LenderManager, **exactly one** points at the live pool (loan
`e833a769ea3a4803…`). Every other one names a burned pool.

**(2) 💀 THE FEE ON THE ONLY LIVE POOL IS ZERO.** Its `PoolManagerDatum` (utxo `1ad93a03…#1`) reads:

```
poolOwnerAuth         = Constr0[ea1bb1cc…]   (the config adminCredential — FluidTokens)
compoudingFeePerMille = 0
```

⇒ `compoundingFee = addedLiquidity * 0 / 1000 = **0**`. **The bot would pay the transaction fee and
earn nothing.** Matteo's *"il bot prende una fee"* is true of the protocol and false of this pool.

> **⚑ The fee is neither protocol-fixed nor ours to choose — it is set by the POOL OWNER**, whose
> `poolOwnerAuth` here is FluidTokens' own admin credential. Not a config value we can set, not a
> number Giovanni can rule on, and not a constant. **On another pool, or on mainnet, it may be
> nonzero — the mechanism is sound and only this instance is uneconomic.**

### 20.3 What T-075 and T-077 do and do NOT cover

**They do not cover this action.** T-075 measured `LiquidatePayInAdvanceAndCompound` — a *liquidation*
variant whose economics are the collateral's mark-to-oracle value against a per-mille liquidation fee.
T-077 pinned `LiquidateConvertAndCompound`, a hard-`False` stub. **This is `lm_compound_action`, a
third action**: no collateral, no oracle, no liquidation — only escrowed principal moving into a pool.
T-075's algebra (`C*(f/1000 − 1) >= txFee`, negative for any fee below 1000/1000) **does not carry
over**, because the bot advances no principal here; its outlay is the transaction fee alone.

**⇒ Economically this action is the FAVOURABLE one** — break-even needs only
`addedLiquidity * feePerMille / 1000 >= txFee`, which at a realistic ~0.3 ADA fee and a 5‰ rate needs
only ~60 ADA of escrow. **It fails today solely because that rate is 0**, which is a datum on someone
else's UTxO rather than anything about the design. *T-075's conclusion and this one coincide in
outcome and share no reasoning — worth keeping separate, because the next pool could change this one
and nothing could change T-075's.*

### 20.4 ~~The stale derivation (§19.3) is squarely on the critical path~~ — ⛔ RETRACTED (see §21)

> **This subsection is void.** It rested entirely on §19.3, which is retracted. There is **no**
> derivation drift: the shipped registry derives `poolCompoundActionScriptHash`,
> `poolManagerSpendScriptHash`, `poolManagerPolicyId`, `poolSpendScriptHash`, `poolPolicyId` and
> `assetManagerSpendScriptHash` correctly today. **No re-vendor is needed and the candidate-commit
> hunt below is moot** — it was hunting for a replacement the code does not need. §20.1–20.3 are
> independent of this and stand. The text is kept for legibility.

**Original text, void:**

Every credential this action needs is among the 19 that moved: `poolCompoundActionScriptHash` (25),
`poolManagerSpendScriptHash` (26), `poolManagerPolicyId` (27), `poolSpendScriptHash` (8),
`poolPolicyId` (2), `assetManagerSpendScriptHash` (15). **A builder written against today's derivation
would target scripts that do not exist.** §19.3 must be fixed before any compound builder can be
written, not after.

**Candidate-commit hunt, first pass (2026-09-02).** `ft-cardano-loans-v5` **is the same repository as
v4** — `git ls-remote` returns byte-identical ref lists for both, so the v5 name is a mirror and offers
no newer source. Across 22 branches, the committed `plutus.json` was fingerprinted: **only `main`/HEAD
(`e0b818e`) matches our vendored artefact** (`sha256 a55a1c2e…`), and that is the one already proven
NOT to derive the live hashes. **21 distinct candidate blueprints remain**, none yet derived —
derivation needs the Java registry harness, and comparing fingerprints cannot substitute for it. Two
hypotheses stay open and must both be tested rather than assumed: the deployment was built from **one
of those branches**, or from **uncommitted state** (in which case byte-identical re-vendoring is
impossible and this is blocked on FluidTokens).


---

## 21. ⛔ §19.3 WAS WRONG: there is no drift, no fifth deployment, and no re-vendor is needed (2026-09-02)

**Giovanni: *"afaik there was no re-deployment."* He is right, and §19.3 was wrong.** This section
retracts it, states the mechanism of the error, and carries the receipt he asked for.

### 21.1 The measurement that settles it

`LoanFixtures.shippedPreviewRegistry()` — the registry built from `application.yaml`'s preview
profile, i.e. **what the image actually runs** — derives, against the live config datum:

| field | shipped registry derives | live config publishes | |
|---|---|---|---|
| `loanPolicyId` | `2f1aa941f437e351…` | `2f1aa941f437e351…` | ✓ |
| `loanSpendScriptHash` | `31e0dc1d75076e4f…` | `31e0dc1d75076e4f…` | ✓ |
| `poolPolicyId` | `a33aee4034165f17…` | `a33aee4034165f17…` | ✓ |
| `poolSpendScriptHash` | `bf8c4378bab7de15…` | `bf8c4378bab7de15…` | ✓ |
| `requestPolicyId` | `39bef32eb5f696f6…` | `39bef32eb5f696f6…` | ✓ |
| `requestSpendScriptHash` | `978934c46206696e…` | `978934c46206696e…` | ✓ |

**6 of 6.** ⇒ **`loans-v4.plutus.json` is CORRECT. The bot is NOT blind. No re-vendor is needed and
none should be attempted.**

### 21.2 How the error was made, and why it looked so convincing

§19.3 read `derivedHashes()` out of **`LoansContractDerivationTest`**, which is pinned to
`CONFIG_POLICY_ID = c45d5306…` — **the THIRD deployment, deliberately.** `ShippedPreviewRegistryTest`
says so in its own javadoc: *"the fixture registry is deliberately still the THIRD deployment — 25
test files replay recorded third-deployment data through it."* Every derived credential hangs off the
config policy id, so a third-deployment registry **must** produce different hashes from a
fourth-deployment config. **That divergence is the design working, and it was read as the design
broken.**

> **⚑ THE LESSON, and it is the exact inverse of the one §19.4 drew.** §19.4 congratulated itself for
> asking "do the hashes we DERIVE equal the hashes the config PUBLISHES?" — a good question. **The
> error was not the question; it was never checking WHICH registry was answering it.** A comparison
> is only as meaningful as the identity of its two sides, and *"derived"* was not one thing: this repo
> deliberately maintains **two** registries at different deployments. **I compared a value to a
> constant without establishing that they were about the same object** — and then found the mismatch
> so alarming that its size became evidence for it rather than a prompt to re-check the setup.
> *19 of 23 fields differing should have read as "wrong baseline", not "catastrophe".*

And the corroboration that felt strongest was the weakest: §19.5 cited §16 recording `2f1aa941` off a
real transaction as proof of a contradiction. **§16 was simply right, and agreed with the shipped
registry all along.** The only thing it contradicted was a fixture nobody claimed was current.

**The 38 red tests are what they were always diagnosed as** — third-deployment fixture debt, an open
decision that belongs to Giovanni. §19.5's *"a red suite is a disabled alarm"* was a real principle
attached to a false instance; the redness was correctly attributed the whole time.

### 21.3 The receipt: the config UTxO was created ONCE and never touched

The config NFT is `d46f626f…706172616d6574657273` (asset name = ASCII **`parameters`**), quantity 1.

```
/assets/{asset}/history       →  1 entry:  minted in 8dd38e97…
/assets/{asset}/transactions  →  1 entry:  8dd38e97…        ← its entire lifetime
```

| | |
|---|---|
| **tx** | `8dd38e97b79cc7c8a3c59400944b7cd9f724876a1d49ea17ffb5e49b3785091c` |
| **block** | 4,590,589 |
| **time** | **2026-08-21 10:13:44 UTC** |
| **signed by** | `ea1bb1ccd33aeb9e02516c2eb50adbaa63d7b7538b03c96908bfc934` — sole `required_signer` **and** sole vkey witness |

That signer **is** the `adminCredential` in the config datum's own field 1 — FluidTokens' admin, as
expected. **There is no spend chain: the UTxO has never been spent, so there was no in-place update
and no second config.** The datum has carried `loanPolicyId 2f1aa941…` and `loanSpendScriptHash
31e0dc1d…` since the moment it was minted, and today's transactions use exactly those.

**⇒ Plain gloss for the FluidTokens conversation: the preview lending config was created on
2026-08-21 by the admin key and has not been modified since. Nothing was redeployed or re-pointed
afterwards. The Aquarium node has been correctly pinned to it the whole time.**

### 21.4 What still stands

- **§19.1** — the 09-01 transaction closed its loan; nine loan NFTs minted, all nine burned; **zero
  live loans.** Chain fact.
- **§19.2 / §19.7** — `PerpetualLoan(28,5)`, action `Repay`, `isFinalRepayment: True`, no pool
  touched. Chain fact.
- **§20.1–20.3** — the compound action's mechanics, the **burned destination pool**, and
  **`compoudingFeePerMille = 0`** on the only live pool. Independent of the retraction and unchanged:
  those remain the real blockers.
- **The one genuinely useful idea in the retracted work**: nothing in the suite compares the
  **shipped** registry's derivations against the **live** config datum. Worth adding as a real test
  (pin the live datum as a fixture and assert all 29 fields), which is the check §21.1 ran by hand.

---

## 22. The compound transaction's redeemer map — read at the DEPLOYED sha, and three index spaces (2026-09-02)

Slice-3 groundwork. **Every line below is `git show e0b818e:…`, never the working tree** — CCL trap 15,
and the trap fired here in a benign way worth recording: §20.1 was written from the working tree at
`bbe9c1a`. Re-read at `e0b818e`, `lm_compound_action.ak` and `pool_compound_action.ak` are
**byte-identical** between the two commits, so §20.1 was right — *by luck, not by method*. The anchor
is proven independently: `git show e0b818e:plutus.json` hashes to
`a55a1c2e723a6d1222e464bb32adc1abe1d669ca6d00205d50459122adf7b98f`, exactly our vendored artefact.

### 22.1 Why `general_spend` multiplies the redeemer count

`general_spend` (deployed source) does almost nothing itself: with a datum present it only checks that
**some withdrawal in the transaction is keyed to its `withdrawScriptHash`**. So every script UTxO the
compound consumes drags in a withdraw-0 invocation to authorise it, and the four spends become four
*pairs*. This is why "six scripts" understates the shape.

| spent UTxO | at credential | authorised by a withdrawal at |
|---|---|---|
| the escrow | `assetManagerSpendScriptHash` | `assetManagerWithdrawScriptHash` |
| the lender bond | `lenderManagerSpendScriptHash` | `lenderManagerWithdrawScriptHash` |
| the pool | `poolSpendScriptHash` | `poolPolicyId` |
| the pool manager | `poolManagerSpendScriptHash` | `poolManagerPolicyId` |

Each spend's own redeemer is the unit `Constr0[]` (`d87980`) — `general_spend` ignores it.

### 22.2 The seven withdrawals

All at amount 0. Types from `lib/fluidtokens/types/*` at `e0b818e`.

| id | script | redeemer |
|---|---|---|
| **a** | `assetManagerWithdrawScriptHash` | `AssetManagerWithdrawRedeemer{configRefInputIndex}` |
| **b** | `lenderManagerWithdrawScriptHash` | `LenderManagerWithdrawRedeemer{configRefInputIndex, action: Compound}` |
| **c** | `lmCompoundActionScriptHash` | `LMCompoundWithdrawRedeemer{configRefInputIndex, poolIds, poolInputIndexes, lenderBondInputIndexes}` |
| **d** | `poolPolicyId` | `PoolWithdrawRedeemer{configRefInputIndex, action: Compound}` |
| **e** | `poolCompoundActionScriptHash` | `PoolCompoundActionWithdrawRedeemer{configRefInputIndex, actionsForEachInput: [CompoundData{poolId}]}` |
| **f** | `poolManagerPolicyId` | `PoolManagerWithdrawRedeemer{configRefInputIndex, action: CompoundLiquidity}` |
| **g** | `pmCompoundLiquidityScriptHash` | `CompoundLiquidityActionWithdrawRedeemer{poolWithdrawRedeemerIndex, lenderManagerWithdrawRedeemerIndex}` |

`assetManager.withdraw` is the mild one: for a token-owned escrow it only requires that **some input
carries the `ownerAsset`** — satisfied because the lender bond is spent. `pm_compound_liquidity`
authorises nothing of its own and gates purely on the action tags of **(d)** and **(b)** (D-17), which
is why **(g)** must be able to point at them.

### 22.3 ⛔ THREE INDEX SPACES, AND THEY ARE NOT INTERCHANGEABLE

This is the likeliest defect site in the whole build, and the reason is that the word "index" means
three different things in one transaction:

| index field | indexes into | ordering that decides it |
|---|---|---|
| `configRefInputIndex` (a,b,c,d,e,f) | `self.reference_inputs` | **canonical** — sorted by `(txId, outputIndex)` |
| `poolInputIndexes`, `lenderBondInputIndexes` (c) | the **FILTERED** lists from `get_inputs_from_smart_credential` | ledger input order, canonical, **then filtered** |
| `poolWithdrawRedeemerIndex`, `lenderManagerWithdrawRedeemerIndex` (g) | `self.redeemers` | **canonical redeemer order** — by `(tag, index)` |

**⚑ The three fail differently and none of them fails loudly.** A wrong reference index reads the wrong
config and aborts; a wrong *filtered* input index pairs an escrow with the wrong bond, which is
`lm_compound_action`'s own anti-double-satisfaction concern and returns false; a wrong *redeemer* index
makes **(g)** validate the wrong sibling's action tag, which can still pass while authorising something
nobody intended. **Only the third can be wrong and green.**

**⇒ A filtered-list position is NOT a `tx.inputs` position.** Computing `lenderBondInputIndexes` by
locating the bond in the transaction's input list is the natural implementation and it is wrong
whenever any other input sorts between the filtered ones. The index must be computed **within the
filtered projection**, reproducing `get_inputs_from_smart_credential`'s predicate exactly.

**⇒ And (g)'s fields cannot be known before the body exists.** Redeemer positions depend on the final
canonical ordering of every redeemer in the transaction. This is CCL trap 1's two-pass discipline
applied to *redeemers* rather than outputs: build once to observe the ordering, then rebuild with the
observed indexes, and **post-assert on the finished body** that each cited position really holds the
redeemer it claims — matched by its script purpose, never by its data's shape (trap 14, which exists
because `PoolWithdrawRedeemer` and `PoolManagerWithdrawRedeemer` are byte-indistinguishable).

### 22.4 The two byte-identical restorations are a trap-4 problem

`lm_compound_action` requires `builtin.equals_data(lenderBondInput.output, lenderBondOutput)` and
`builtin.equals_data(poolManagerOutput, poolManagerInput.output)`, and `pool_compound_action` requires
`equals_data(output.datum, input.output.datum)` for the pool.

**⇒ The datums must be echoed as their ORIGINAL BYTES.** CCL trap 4: a decode→re-encode round trip is
not byte-stable, because bytestrings over 64 bytes chunk and the chain accepts a chunking CCL will not
reproduce. The bond, pool-manager and pool datums must be carried from the indexed UTxO's
`inlineDatum` hex to the output untouched. Anything that parses them into a model and rebuilds is
wrong even when every field is right.

### 22.5 What the builder must therefore do

1. Two passes: observe redeemer ordering, then rebuild with real indexes for **(g)**.
2. Compute **(c)**'s indexes inside the filtered projection, not the raw input list.
3. Echo three datums byte-for-byte; never re-encode.
4. Place the compounding fee in an **explicit** output — no validator requires it, so a naive balance
   donates it to the pool (§20.1).
5. Wire a real evaluator and `ignoreScriptCostEvaluationError(false)` (trap 8/13). **Giovanni's risk
   case — "exposure is the tx fee per execution" — is true only if the ex-units are measured.**
   Placeholder ex-units move the exposure to the collateral, which is the one way that reasoning fails.
6. Assert ex-units off the **built, deserialised** transaction, never off the evaluator's report.
7. Guard coin selection so it cannot spend a reference-script UTxO (trap 9b), at **both** seams.
8. Post-assert on the finished body: every cited index points at what it claims;
   `collateral_return >= 0` (trap 16); no script both witnessed and referenced (trap 9).

### 22.6 Two corrections the dry-eval found that reading had missed (2026-09-02)

**⛔ `configRefInputIndex` does not name the same config on every validator.** §22.2 lists six
redeemers carrying that field and implies one meaning. It has two. `lender_manager.withdraw` resolves
the **LM config** NFT from the index it is handed — it reads `lmConfig` fields 1..7 to pick which
action script must also be withdrawing — while every other redeemer on this path resolves the **main**
config. Handing redeemer **(b)** the main config's reference index fails as a bare
`RedeemerError { tag: "Withdraw", index: 4, Machine(EvaluationFailure) }` with nothing naming the
cause.

> **⚑ A fourth index space, and the one that hides best**, because it is not a different *kind* of
> index — it is the same kind pointing into the same list, meaning a different object. Reading the
> field names could not distinguish it; only running the validators did. *The three-space table in
> §22.3 was necessary and not sufficient.*

**✅ And `repaymentPolicyId` IS derivable after all** — §20/§21's "conservatism" rests on a false
premise. The live config's field 7 is `19d8576594820b4c94d3cb2c4010ca563c6ef7b2c31e5175d7f8ec6e`, which
is exactly what the registry already derives as **`assetManagerWithdrawScriptHash`**: `asset_manager`
is one validator serving as both a minting policy and a withdraw script, the Tier-1 "doubles as"
shape. So `CompoundCandidateScanner`'s escrow-shape check can count repayment receipt NFTs properly and
stop refusing an escrow that legitimately carries one. The refusal is a false negative and harms
nothing today — the live candidate is ada-only — but the stated reason for it was wrong.

### 22.7 Measured: the compound transaction evaluates

Offline PlutusV3 evaluation of the real candidate `e833a769…` against the deployed validators, over
the recorded escrow, bond, pool, pool manager and both configs (`CompoundDryEvalTest`):

```
Spend  0    39,533 mem     13,599,872 steps      Reward 0   285,607   100,716,946
Spend  1    50,981         17,548,148            Reward 1   110,128    36,535,810
Spend  3    45,257         15,574,010            Reward 2   620,492   237,620,266
Spend  4    22,361          7,677,458            Reward 3    44,833    13,723,072
                                                 Reward 4   120,939    38,911,971
                                                 Reward 5   152,103    46,336,496
                                                 Reward 6 1,092,251   413,454,926
TOTAL  mem 2,584,485   steps 941,698,975   across 11 redeemers
```

Comfortably inside preview's `maxTxExMem` 17,500,000, and the same order as the liquidation that
landed on chain (2,819,867 / 964,770,147).

⚠ **What this does not prove** (CCL trap 11): fees, min-ada, value conservation, witness-set validity
and collateral adequacy are the ledger's and this rig is silent about every one of them. The builder's
body assertions cover what they can; the rest is a phase-1 answer, which is free and has not been
asked for yet.

### 22.8 The first armed cycle: the guard was right, the expectation was wrong (2026-09-02)

`95d87fa` went live and refused, deterministically, six cycles running:

```
BOT_NET_MISMATCH: bot nets 2325431, expected -1479060 (fee earned 0 - tx fee 1479060)
```

**The body was correct. The expectation was not.** Conservation bounds the answer, so it can be
decomposed rather than guessed. With the pool receiving the whole `addedLiquidity` and both echoes
byte-identical, `T = W − txFee`, hence `net = W − nominated − txFee`. Therefore

```
W − nominated = net + txFee = 2,325,431 + 1,479,060 = 3,804,491
```

⇒ **cardano-client-lib's coin selection had added 3,804,491 lovelace of FURTHER WALLET INPUTS** beyond
the one nominated, to cover the fee, the outputs' min-ada and the withdrawal dummy. That is **the
bot's own money returning as change**. Nothing belonging to the pool or the lender was ever at risk;
the assertion's baseline — "the nominated UTxO is the only wallet input" — was simply false.

**Reproduced offline to the lovelace.** Running the same build with a *thin* wallet selects two inputs
and the pre-fix assertion reports `bot nets 2320261, expected -1484230` — the production failure, off
by only the fee difference between two fixture wallets.

> **⚑ Why the rig missed it, and it is this repo's own recorded failure shape.** The offline wallet
> held **one fat 20 ADA UTxO**, so nothing extra was ever selected and the false baseline held by
> accident. **The fixture supplied what production has to earn.** `CompoundAccountingTest` now runs the
> wallet thin — which is what a real bot wallet looks like after it has been paying fees — and asserts
> multi-input selection actually occurred, so the fixture cannot quietly stop exercising the thing.

**The determinism is itself explained by the defect.** Six byte-identical refusals with the fee not
moving by one lovelace: the wallet UTxO set cannot change while nothing ever submits, and CCL's
selection is deterministic over a stable set. *A bot that never succeeds has a perfectly stable
wallet.*

**Fix:** the bot's contribution is now summed from the **built body's own inputs**, resolved through
the `UtxoSupplier` and filtered to the change address — not from the nominated UTxO. An unresolvable
input is a refusal, never an assumed zero.

### 22.9 ⛔ AND THE TRANSACTION IS TOO BIG TO SUBMIT — seam 3, measured

The refusal above was hiding a second, independent blocker that no cycle had reached:

```
built transaction   24,878 bytes
max_tx_size         16,384        OVER BY 8,494
inline validators   22,689        = 91% of the transaction
```

Eleven validators travel **inline** in the witness set. Even with the accounting fixed, this
transaction is rejected at **phase 1** with `MaxTxSizeUTxO` — free and loud, nothing on chain, but it
can never succeed as built.

**Publishing the eleven as reference scripts** (its own slice) both fixes the size and pays for itself:

| | |
|---|---|
| size fee saved | 998,316 (22,689 × 44) |
| Conway ref-script fee added | 340,335 (22,689 × 15) |
| **net fee saving** | **657,981 (~0.66 ADA per compound)** |
| resulting size | ~2,189 bytes + reference-input overhead |

⚠ And per CCL trap 9a, the *publishing* transactions are themselves under-fee'd by cardano-client-lib
and need the `postBalanceTx` top-up — a known trap on the way in, not a surprise.

### 22.10 PUBLICATION RECORD — four compound validators, preview, 2026-09-02

Authorised first-hand by Giovanni ("publish the 4 reference scripts, go"). Chain writes from this
session, funded by the bot's own preview wallet.

| validator | script hash | coordinate | locked |
|---|---|---|---:|
| `lm_compound_action` | `dd4709091734af2d…` | `9e915e60cf643362c3d2b52f7117360385388611e6ec20c502f95bf0936e24fc#0` | 23,015,400 |
| `pool_compound_action` | `33128ca352b5472f…` | `2ef25730a349944e001d5929b4e0c7967358e920ee089c0088ec3bcf14358b3b#0` | 17,110,700 |
| `asset_manager` | `19d8576594820b4c…` | `d779005e19f0526bf17b23bc9a36d4a144cc796da455e60f2f2b5f723e49cd98#0` | 15,072,070 |
| `pool_manager` | `45ce890c9bcf70f6…` | `8eaffac0ae8204d9d7828443f9b1b676e44e495f8fb11ef303d150fbc18bbd7b#0` | 11,330,990 |

**Total locked 66,529,160 lovelace (66.53 ADA); fees ~1.58 ADA.** All four verified present at the
destination after publication, hashes matched against what the live config datums publish
(main config fields 7/25/27, LM config field 3) **before** anything was built — a wrong publication
is ada locked behind a coordinate no verifier will ever accept.

**Destination `addr_test1wzv5kdz…` — an enterprise SCRIPT address** (`UnspendableDestination`), the
same one the liquidation publications used. **Coin selection cannot spend it because there is no key
to sign with**, so CCL trap 9b is closed structurally rather than by a guard someone must remember.

**Four and not eleven**, because margin costs money: four buys 5,970 bytes for 66.53 ADA; eleven
would buy 13,799 for ~105. Two would have fit at 324 bytes — about nine extra transaction inputs,
which a thin wallet produces routinely.

### 22.11 ⚠ "Confirmed" is not "the UTxO index has caught up"

Two of the four publications first failed with
`ConwayMempoolFailure "All inputs are spent. Transaction has probably already been included"`.

The cause is a lag between two Blockfrost endpoints. The first attempt slept a flat 45 s between
publications; the second replaced that with **polling `getTransaction` until the submission
confirmed** — and *still* failed, because `getTransaction` reports a transaction before
`getUtxos(address)` stops returning the input it consumed. **Confirmation and index freshness are
different facts, and the stricter-looking check was not the sufficient one.**

**⇒ The fix that actually worked is idempotence, not waiting.** The runner now reads the destination
first and skips any script already published, so a re-run cannot double-publish — which matters
because a duplicate publication is a second min-ada locked behind a coordinate nothing would flag.
*A retry-safe operation beats a better-timed one: the first tries to avoid the race, the second
survives it.*

### 22.12 Measured after publication — the size problem is closed

| | bytes | fee |
|---|---:|---:|
| all eleven inline | 24,912 | 1,473,195 |
| four referenced | **10,414** | **843,852** |
| `max_tx_size` | 16,384 | |
| **margin** | **5,970** | saving 629,343 |

Measured on a **built body**, not arithmetic — the gap §22.9 flagged is closed. The build is done
without an evaluator (size needs none, and the rig still cannot *evaluate* a referenced build per CCL
trap 13); placeholder ex-units encode slightly smaller than real ones, so this **under**-estimates and
the margin is conservative rather than optimistic. Arithmetic had predicted 10,426 — within 12 bytes.

### 22.13 ⛔ THE PUBLICATION DISABLED THE BOT — CCL trap 17, self-inflicted (2026-09-02)

`67f07c3` went live and the compound loop refused with a new reason:

```
compound SKIPPED e833a769…: no ada-only wallet utxo is nominable as input and collateral
```

**The predicate is right and the wallet is the wrong shape — and the publication I had just run is
what made it that shape.** Measured directly at the bot's address after publishing:

```
1 utxo:  2ef25730…#1   9,898,050,234 lovelace  +  20,000,000 tFLDT
```

Nine thousand ada, and **not one lovelace of it nominable**, because `nominable()` requires
`amount.size() == 1` — collateral must be pure ada (trap 16). Before publishing, the wallet held four
ada-only UTxOs (three of 1,000,000 and one of 3,804,491). **The four publication transactions
consumed every one of them and swept the remainder — token included — into a single change output.**

> **⚑ This is CCL trap 17 exactly, and the trap was distilled from this very wallet.** Its recorded
> example is *"the wallet held 9,966 ADA and could build nothing"*. This is the same wallet, the same
> number to three significant figures, and the same failure — **re-created by a runner I wrote after
> reading the trap.** Knowing a trap and instrumenting against it are different acts: the compound
> BUILDER is safe by construction (`docs/change-output-enumeration.md`, route 8 refused at the door),
> and I checked that; **the publication RUNNER was never held to the same standard, because it was
> "just a one-off".** A one-off that spends the wallet's shape is not a smaller act than a builder.

**Two code defects fixed here** (neither is the blocker; both would have bitten later):
- The executor took `findFirst()` of the nominable UTxOs. It now takes
  `WalletInputSelection.largest()`, as the liquidation path does. A 1,000,000 UTxO cannot cover the
  collateral the ledger requires — 150% of a ~844,000 fee is 1,265,778 — so first-in-list would have
  produced a **negative collateral return**, trap 16, a transaction the node cannot parse.
- The refusal now reports the wallet's **shape**: total lovelace, UTxO count, and how many carry
  native assets. *A message that cannot distinguish an empty wallet from a full but ineligible one
  sends an operator looking for funds they already have.*

### 22.14 The 3,804,491 is not a coincidence — same transaction, by construction

The steward handed over, without a theory: the wallet held `7e1efbaf…#4`, ada-only, **exactly
3,804,491** — the previous image's unexplained delta — and `7e1efbaf` is the same transaction as
rejected escrow `7e1efbaf…#2`. The outputs of that transaction explain both:

```
#0  1,000,000      -> the bot      (cardano-client-lib's withdrawal dummy, trap 1)
#2  1,771,410 +tok -> ASSET MANAGER (the escrow that appears in the refusal table)
#4  3,804,491      -> the bot      (ada-only change)
#5  9,954,141,273  -> the bot      (the rest)
```

**`7e1efbaf…` is a liquidation the bot itself executed.** A liquidation escrows value at the asset
manager *and* returns the leftover ada to the bot — so the escrow and the change are siblings from one
transaction, and sharing a tx hash is the expected relationship rather than a surprising one. Later,
CCL's coin selection picked that ada-only change as the compound's extra input, which is why the
`BOT_NET_MISMATCH` delta equalled it to the lovelace (§22.8).

*The bot's own past work is the source of both the escrows it now wants to compound and the small
UTxOs it needs to compound them with.*

### 22.15 ✅ THE BOT COMPOUNDED — `9ab95194…`, 2026-09-02 13:09:22Z

**The wallet reshape was the last blocker. The bot did the rest by itself, one block later.**

```
tx              9ab95194e98a129eb417c9ad4ec77f54f28c9f3ca32a8475ec8f24affcd4af05
block           4,625,217          2026-09-02 13:09:22Z
valid_contract  TRUE
redeemers       11                 four general_spend spends + seven withdraw-0
size            10,637 bytes       against max_tx_size 16,384
fee             1,060,617
ex-units        mem 2,595,965      steps 945,031,023
pool            395,750,000  ->  424,859,268     (+29,109,268 = the ENTIRE escrow)
```

**Every offline prediction held against the ledger:**

| | predicted offline | on chain | |
|---|---:|---:|---|
| size | 10,414 | 10,637 | +223, and **the prediction was a deliberate under-estimate** (§22.12: placeholder ex-units encode smaller than real ones) — the error is in the direction the method promised |
| ex-units mem | 2,584,485 | 2,595,965 | +0.44% |
| ex-units steps | 941,698,975 | 945,031,023 | +0.35% |
| pool delta | +29,109,268 | +29,109,268 | exact |
| fee | 843,852 | 1,060,617 | higher: real ex-units cost more than placeholders, the same asymmetry |

**The pool received the entire `addedLiquidity` and the bot kept nothing**, because
`compoudingFeePerMille` is 0 on that pool. The bot paid 1,060,617 lovelace to do unpaid work — which
is precisely what Giovanni's stated `-2000000` floor authorised, and what the safe default of 0 would
have refused. **The armed loss is not a side effect of this run; it is the whole reason it happened.**

### 22.16 The last blocker was a wallet with 9,898 ada in it

Worth stating plainly, because the sequence is the lesson. The bot was correct, armed, and pointed at
a real candidate for three consecutive images, and was stopped by, in order:

1. `BOT_NET_MISMATCH` — a guard whose baseline was wrong (§22.8);
2. `MaxTxSizeUTxO` waiting behind it, unreached (§22.9);
3. `no ada-only wallet utxo is nominable` — **caused by the publication that fixed (2)** (§22.13).

**Each fix uncovered the next blocker, and the third was self-inflicted.** None of the three was a
defect in the compound logic, which evaluated correctly the first time it was built (§22.7) and
worked unchanged on chain. *The transaction was never the hard part.*

The reshape itself: one self-send `355e504c714213b358b8cda3e39c21c4100e71b63a6d566b0f2a8b6c02c22765`,
producing one token-bearing output at its exact min-ada (1,176,630 + 20,000,000 tFLDT), five ada-only
outputs of 20 ada, and ada-only change of 9,796,688,875. Fee 184,729.

> **⚠ And the first dry run of that self-send built ONE output.** `mergeOutputs` defaults to **true**,
> so six payments to one address collapse into one — a transaction that would have been valid, cheap,
> and **exactly the state it was meant to repair**. Caught because the runner asserts the SHAPE of the
> built body rather than trusting what it asked for. *A no-op is the hardest failure to notice,
> because everything about it succeeds.*

---

## 23. ✅ LENDING v4 IS ON MAINNET, AND OUR VENDORED BLUEPRINT DERIVES IT (2026-09-02)

FluidTokens shipped v4 to mainnet. Giovanni relayed four values first-hand from their own
(TypeScript) tooling; this repo has no TypeScript surface, so they map onto `application.yaml`'s
**base document** — which is the mainnet profile — and the preview profile overrides all four, so the
block is **structurally inert on every preview deployment**.

```
config ref utxo   7b9f20dbadaebe1400915e4a63444a9eb7515c21c1114d4bc9c77f1455148cb0
config policy     db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416
lm config policy  a56b0ac2654663f395601601a7825649e5488905648747e912d870e4
asset name        706172616d6574657273   ("parameters", the same word as preview)
```

### 23.1 Verified read-only before committing, in the §21 discipline

| check | result |
|---|---|
| ref UTxO exists | ✅ tx `7b9f20db…`, 4 outputs |
| config NFT present | ✅ output #0, policy `db2c498e…`, name `parameters` |
| LM config NFT present | ✅ output #1, policy `a56b0ac2…`, **same transaction** — the preview pattern |
| ConfigDatum shape | ✅ constructor 0, **29 fields**, same as preview |
| LMConfigDatum shape | ✅ 8 fields |
| **derivation** | ✅ **the vendored `loans-v4.plutus.json` derives EVERY credential the mainnet datums publish** |

**⇒ Their mainnet build IS the artefact we vendor.** Everything downstream is knowable, and the bot
*could* operate there. `MainnetRegistryMatchesConfigTest` pins it, driving the production
`LoansConfigVerifier` against recorded mainnet datums — the same shape as
`ShippedRegistryMatchesPinnedConfigTest`, pointed at the other network.

**Three credentials are byte-identical across networks, and that is diagnostic rather than
suspicious**: `borrowerBondPolicyId`, `lenderBondPolicyId` (derived from an integer index, not from
the config policy) and `smartTokensSpendScriptHash` (not derivable at all — published in the datum).
**They are exactly the values a new deployment cannot move.** A fourth joins them:
`lmLiquidateConvertAndCompoundActionScriptHash` = `435b42cc…` on both networks — **T-077 predicted
this**, because that stub is the one lender-manager action derived with no config parameters.

### 23.2 ⛔ Nothing has ever run there — and no reference scripts exist

Confirmed by two independent providers:

```
Blockfrost /scripts/{hash}   loanSpend, poolCompoundAction  ->  HTTP 404
Koios      /script_info      same hashes                    ->  []
```

**The validators have never appeared on chain.** The deploy transaction publishes **zero** reference
scripts (0 of its 4 outputs carry one). So the mainnet deployment today is *the config registered and
nothing else*: no loan has been made, no validator witnessed, nothing to point a reference input at.

**Reference-script discovery, the recipe — verified, not remembered.** Neither provider offers a
**global reverse lookup from script hash to the UTxO publishing it**. What exists:
- **Blockfrost** — `GET /addresses/{addr}/utxos` returns `reference_script_hash` per UTxO. *Knowing a
  publication address is sufficient; not knowing one is fatal.* `GET /scripts/{hash}` answers only
  "has this script ever appeared on chain", which is **404 until first use** and is not a publication
  check.
- **Koios** — `POST /script_info` with `_script_hashes` returns creation information; `[]` for a
  script never seen. Its UTxO endpoints expose a `reference_script` object the same way, and are
  likewise address-scoped.

**⇒ Enumerate a known publication address and match on `reference_script_hash`. There is no
hash→UTxO index; if the publisher's address is unknown, the coordinate is not discoverable at all.**

### 23.3 What publishing would cost on mainnet

`coins_per_utxo_size` is 4310 and `max_tx_size` 16,384 — identical to preview, so the preview
arithmetic transfers exactly. The applied scripts are the same sizes (a parameter change does not move
them):

| validator | bytes | locked |
|---|---:|---:|
| `lm_compound_action` | 5,130 | 22.80 ADA |
| `pool_compound_action` | 3,760 | 16.90 ADA |
| `asset_manager` | 3,287 | 14.86 ADA |
| `pool_manager` | 2,419 | 11.12 ADA |
| **total for the compound path** | | **65.67 ADA** |

⚠ **This is real money on mainnet**, unlike the preview equivalent. And it buys nothing until a loan
exists there to compound.

---

## 24. FluidTokens' mainnet reference scripts — verified, and two are on the wrong network (2026-09-03)

Giovanni relayed 27 published coordinates (TS constants, **tx hash only — no output index**). Every
one resolved read-only, matched **by hash against what the mainnet config datum publishes**, never by
the constant's name.

### 24.1 ✅ 25 of 27 are correct, and the shape is uniformly good

Every verified publication sits at **output #0**, is **UNSPENT**, and lives at an **enterprise SCRIPT
address** — payment credential is a script, so **coin selection cannot spend it and CCL trap 9b is
closed structurally**. Not one is at a key-spendable address. **And not one constant is mislabelled**:
every name matched the role its on-chain hash maps to.

**⇒ Coordinates are `<txhash>#0` throughout.**

### 24.2 ⛔ TWO ENTRIES ARE PREVIEW TRANSACTIONS IN A MAINNET LIST

```
BORROW_BOND_MINT  d2d5b9b6…   mainnet HTTP 404   preview HTTP 200   publishes eadc69a5… (borrowerBondPolicyId)
LENDER_BOND_MINT  479f7460…   mainnet HTTP 404   preview HTTP 200   publishes bcd713bb… (lenderBondPolicyId)
```
Confirmed by a second provider: Koios `tx_info` returned **1 of 3** requested hashes — only the
known-good control.

**✅ CORRECTED 2026-09-03 — FluidTokens fixed the list, and both new coordinates verify on mainnet:**

```
BORROW_BOND_MINT_SCRIPT_REF  90efd6a6d59bf72137186fdb6fb9cbe4b816b95cc3ecd9383b46997947dc9063#0
                             → publishes eadc69a5d2d1357acc9b9d49ec5390fcdf6e080c7a40139917223dcb
LENDER_BOND_MINT_SCRIPT_REF  cf66c3c5e625b77757b2dcd366f9cb54ac61815e66e63ac9ee9ef74b8ab85f47#0
                             → publishes bcd713bb7858d4b08738bed90ee7068d8f9b38d02e0cae0b45ac7a9b
```

Read on **mainnet** Blockfrost: both resolve, both publish at **output #0**, and the two script hashes
are exactly the `borrowerBondPolicyId` / `lenderBondPolicyId` this project derives. **So the list is
now 27 of 27**, in the same uniformly good shape as §24.1.

**⚠ But note what that verification does and does not settle** — the same trap as before, pointed the
other way. The hashes matching proves nothing *by itself*, because these two policies are byte-identical
across networks. **What settles it is that these hashes resolved on MAINNET**, which the superseded pair
did not. The network the query went to is the evidence; the hash is only the corroboration.

### 24.2a "Do we need them?" — NO, and it is checked against the validators rather than recalled

The bot **never mints or burns a bond on any path**, so neither bond-mint script can enter its
reference-script set:

| path | what it does with the bond |
|---|---|
| plain `Liquidate` | spends the lender-bond UTxO at the lender-manager credential and re-creates it |
| `LiquidateAndPayInAdvance` | same |
| **`LiquidateAndConvert`** | `builtin.equals_data(lenderBondInput.output, lenderBondOutput)` — the bond output must be **byte-identical to its input** |
| `Compound` | reads the bond to resolve the pool; does not move it |

Read at `e0b818e`, `lm_liquidate_and_convert_action` touches `lenderBondPolicyId` only as a **datum
value** — `quantity_of(lenderBondInput.output.value, lenderBondPolicyId, …) == 1` — pulled out of the
ConfigDatum as data. **A policy id used to count an asset is not a script the transaction witnesses**,
so it needs no reference input, no witness and no redeemer. The convert transaction's reference set is
the config UTxOs, the Minswap pool, and the validators it withdraws or spends through — the bond-mint
scripts appear in none of them.

**⇒ The two coordinates matter to origination, which this bot does not do.** Worth having correct in
FluidTokens' list; irrelevant to the operator's `reference-scripts` configuration.

> **⚑ AND THIS IS THE CASE A HASH CHECK CANNOT CATCH.** The two bond policies are precisely the
> credentials that are **byte-identical on preview and mainnet** (§23.1 — derived from an integer
> index, so a deployment cannot move them). So the published script hash at those preview UTxOs
> **does match what the mainnet config publishes.** A verifier comparing hashes, or trusting the
> constant's name, passes them. **Only resolving the transaction on the intended network separates
> them** — and the mistake is the natural one: copying two lines from a preview deploy script whose
> values were genuinely correct.
>
> **⇒ For any cross-network coordinate, the network is part of the identity, and it is the part no
> hash carries.**

**Harmless for this bot**: neither bond policy is invoked on the liquidation or compound paths — the
bot spends bonds through the lender-manager credential and never mints or burns one. It would bite
whoever builds an origination path.

### 24.3 What is NOT published — 8 of 33 roles, none of them ours

`requestPolicyId`, `requestSpendScriptHash` (borrower origination), `smartTokensSpendScriptHash` (no
such validator ships in the blueprint — it is a config input, not a publishable script),
`lockedBorrowerManagerSpendScriptHash`, `poolSellLenderPositionActionScriptHash` (pool-owner action),
`lmLiquidateConvertAndCompoundActionScriptHash` (**the hard-`False` stub — unusable by anyone**,
T-077), plus the two bond policies above.

**None is on either path this bot walks.** Both sets are complete.

### 24.4 Paste-ready, and both paths are COMPLETE

**Compound** — all eleven present (§22.1–22.2). One key, comma-separated:
```
AQUARIUM_COMPOUND_REFERENCE_SCRIPTS=\
83d1c5393a53e365eb15a7bdfd1feff560f43f9560bc60c23c4e41de709bae33#0,\
55a67ecdf41df12275588f01a33cb4d0c88345e05bec7a52be4099dff9597d3d#0,\
d0549a87da42d048eb1c3b5b8f7811fd2ccd882ad36c85ee209d3a8d1ca0265f#0,\
d52f3f88e44ca798d9f45313b83267a7ffa01a6105603ed2b2aebcd8383c45ea#0,\
e5e5bab0c7b39a929af8516f940811ca483dbc23ba647a664c1463c2a70b3fe0#0,\
ebc11a0346719772709390b11156f6e3b46c5b39d305f80c1f842ceadc9a242b#0,\
954f8be5773c3ebce3377ecb7a420f407ef18500638bb6d7db0022ed9e9b7c50#0,\
5215ca557800881b044ce92c77018b92b9d5b6c56f835d6217bc7e1435000f8a#0,\
8340312072cd352519e01d7e294d3a4cb84a7f0b63f44adef027abf84d2e0bee#0,\
8bfb510d6d90573280d9a47b94411477f0992228e0b43cb7cb864f2af66b6812#0,\
15d88c19c9841e7b5cdd125613ff2013993aeb89f871f340c7a2e43fce1373f5#0
```

**Liquidation** — all eight present. Named keys, *not* a list:
```
AQUARIUM_LIQUIDATION_REF_LOAN=f87ed9cc0fd53fd5d8d9c88bfac066fa741aa927e98e5c001496bfb4c82db84f#0
AQUARIUM_LIQUIDATION_REF_LOAN_SPEND=46d7195856788885fd4a488dff7bde8bbaf46d5dc4a2fa3dbd12e9cb42129c96#0
AQUARIUM_LIQUIDATION_REF_LENDER_MANAGER=ebc11a0346719772709390b11156f6e3b46c5b39d305f80c1f842ceadc9a242b#0
AQUARIUM_LIQUIDATION_REF_LENDER_MANAGER_SPEND=55a67ecdf41df12275588f01a33cb4d0c88345e05bec7a52be4099dff9597d3d#0
AQUARIUM_LIQUIDATION_REF_LOAN_CLAIM_ACTION=51eaf4994ee313bf4c95be65656e092d7366b0f397f7ecc1e0113c063fab5f98#0
AQUARIUM_LIQUIDATION_REF_LM_LIQUIDATE_ACTION=8ba0dfb30d40361b9bc775f032e2427c799a6cfefce0cbf13e8f1242c990249a#0
AQUARIUM_LIQUIDATION_REF_LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION=2ed58f66779acd64f9add3755dd0686d6841001c90683b369cae0f5f07287476#0
AQUARIUM_LIQUIDATION_REF_ASSET_MANAGER=e5e5bab0c7b39a929af8516f940811ca483dbc23ba647a664c1463c2a70b3fe0#0
```

**⇒ Giovanni locks NOTHING.** The 65.67 ADA estimate in §23.3 is withdrawn — FluidTokens published
everything this bot needs.

⚠ These are unspent **today**. `LoansReferenceScriptVerifier` re-checks each at startup, so a spent or
replaced coordinate is a hard boot failure — an answer, not an outage.

---

## 25. LiquidateAndConvert: the bot never holds the collateral — the strategy question is MOOT (2026-09-03)

FluidTokens supplied the mainnet parameterisation for `lm_liquidate_and_convert_action`. Read at the
**deployed sha** `e0b818e`, never the working tree.

### 25.1 ✅ The parameters are VERIFIED — they derive the on-chain hash exactly

Applying the eleven quoted parameters to the vendored blueprint's
`lender_manager/lm_liquidate_and_convert_action.actionValidator.withdraw` yields
`ed8d41e48d2b48c23c1673493e133b2e5a3300555026cab6c729683b` — **exactly what the mainnet LMConfigDatum
publishes at field 5.**

**⇒ This retires the last underivable field in the deployment.** `LoansConfigVerifier` carries the
comment *"Field 5 … is deliberately unchecked: it takes five Minswap parameters we do not have."* We
have them now. Verified live on mainnet, all three Minswap hashes are real PlutusV2 scripts:
pool policy `f5808c2c…` (4,699 B), pool spend `ea07b733…` (3,965 B), order spend `c3e28c36…` (2,659 B).

### 25.2 ⛔ THE READING THAT CHANGES THE PICTURE — proven from the validator, not hoped

The convert action **constructs a Minswap V2 swap order inside the liquidation transaction**:

```
orderType = SwapExactIn {
    swap_amount:     swappableCollateralAmount,
    minimum_receive: loanInputAction.remainingDebt,   ← the VALIDATOR sets min-received
    killable:        True }
receiver  = get_smart_destination_address(isCIP113Pair, assetManagerSpendScriptHash, …)
OrderDatum { canceller: lenderAuth,
             success_receiver: receiver, success_receiver_datum: converted_to_liquidity_action,
             refund_receiver:  receiver, refund_receiver_datum:  action_claimed_collateral,
             max_batcher_fee: 700000 }
```

**Both the success and refund receivers are the ASSET MANAGER, owned by the LENDER BOND.**

> **⇒ THE BOT NEVER HOLDS THE COLLATERAL AND NEVER HOLDS THE PROCEEDS.** It creates an order;
> Minswap's own batcher fills it; the principal lands in the asset manager escrowed for the lender.
> **Giovanni's standing carve-out — "it buys collateral and nobody has said what it should hold" — does
> not apply to this action.** It was the right question about a different mechanism.

**And the two paths compose.** The escrow this produces, owned by a lender bond, is *exactly* what
`lm_compound_action` collects (§20). **convert → asset-manager escrow → compound → pool**, with the
bot paid at each step and holding nothing in between.

**Three consequences that shrink the work rather than growing it:**
- **Slippage is not ours to model.** `minimum_receive` is fixed by the validator at `remainingDebt`,
  and `killable: True` means a pool that cannot deliver it refunds rather than hanging. The
  `officina:dex-swap-integration` request→batch model applies — *we create the order, we never
  scoop* — but the price bound is enforced on chain, not by our economics gate.
- **The bot fronts NOTHING.** The debt is settled from the swap proceeds.
- `expect equityInPrincipalCurrency == False` — the convert path **requires** equity not in the
  principal currency, the mirror of §13.

### 25.3 ⚠ The one thing that is NOT zero: the fee is paid in the COLLATERAL asset

```
liquidationFee           = loanCollateralAmount * liquidationFeePerMille / 1000
swappableCollateralAmount = loanCollateralAmount - equity - liquidationFee
```
The fee and the equity are **subtracted** from what gets swapped, and the validator **does not
constrain where either goes** — the same unconstrained-residue shape as the compound fee (§20.1). On
the natural construction the fee lands with the bot, **denominated in the collateral token, not ada.**

**⇒ The strategy question does not vanish, it shrinks**: from *"what should the bot do with a whole
liquidated collateral position"* to *"the bot accrues fee income in assorted collateral tokens"*. That
is an inventory/treasury question, not a liquidation-strategy one — and it is still Giovanni's, just
much smaller. ⚠ It also means the change output carries native assets on every convert, which is CCL
**trap 17** by construction, every time.

### 25.4 The empty parameters, and `mConStr1`

`minswapPoolWithdrawScriptHash` and `minswapOrderWithdrawScriptHash` are `""`. They feed
`get_inputs_from_smart_credential(…, Script(spend), Script(withdraw), smartTokensSpendScriptHash)`,
whose withdraw argument is the **CIP-113 smart-token counterpart** of a credential. Minswap V2's pool
and order validators are plain **PlutusV2** contracts with no withdraw-script half, so the empty hash
selects the non-CIP-113 branch. *Empty here means "this credential family has no withdraw script",
not "unset".*

`mConStr1([loanClaimScript.policyId])` is a **`PaymentCredential`**: Aiken encodes
`VerificationKey` as constructor 0 and **`Script` as constructor 1**, so this is
`Script(loanClaimPolicyId)` — the loan-claim validator's credential, passed as a typed credential
rather than a bare hash.

### 25.5 Are we armed? No — and here is the exact gap

**Code that exists:** none for convert. There is **no convert builder in `src/main` or `src/test`**.
`PayInAdvanceLiquidationRouter` routes a `shouldLiquidationConvertToPrincipal == True` candidate to
the **pay-in-advance** builder, and `LiquidationCandidateScanner` still carries
`CONVERSION_TO_PRINCIPAL_REQUIRED` as an exclusion. The registry does not derive the convert hash
(it could not, until now).

**⚠ And an August finding must NOT be carried over unexamined.** The measured **−27,303,331** floor
was on the **pay-in-advance** path, where the bot *fronts the principal* and the accounting counted
that outlay as pure cost while valuing the received collateral at nothing. **Convert is a different
mechanism: the bot fronts nothing.** So that objection does not transfer — it has to be **re-derived**
for a flow whose only outlay is the transaction fee. *Reusing the number would be the same
category error as reusing trap 14's documented redeemer pair (§14c).*

---

## 26. The submission guard becomes configuration — and one gate Giovanni counted on does not exist (2026-09-03)

**Giovanni's ruling**, first-hand: *"if someone is operating this bot they KNOW they're doing
financial stuff on a network … putting too many gates is only annoying and won't really protect
anyone. No different image pls … configuration configuration configuration. The defaults must be
defensive; if an operator copy-pastes from preview it's their problem."*

`SUBMITTABLE_NETWORK`, previously a hard-coded `"preview"` constant, is now
**`loans.submittable-network`, defaulting to `preview`**. The gate is unchanged; **the default now
carries the protection.** That matters more than it sounds: since `1d17e9a` **mainnet is the default
profile**, so the submission default has to lean the other way.

**Both executors read the one value.** `CompoundExecutor` had **no network check at all** — its only
gate between a candidate and a mainnet submission was `loans.compound.enabled`, one boolean. Closing
that asymmetry cost **zero new gates**: the same config value, applied twice.

### 26.1 ⛔ CORRECTION: the market gates do not exist

Giovanni's enumeration was *"loans enabled, liquidation mode, plus the gates for the markets in case
of anticipated payments — market enabled/disable and the per-market cap"*, and he asked "right?".
Verified exhaustively against `src/main`:

| gate he named | reality |
|---|---|
| loans enabled | ✅ `loans.enabled` — gates bean existence; no executor exists without it |
| liquidation mode | ✅ S1 `MODE_NOT_LIVE` + S2 `NOT_ARMED` (two flags, both required) |
| **market enable/disable** | ❌ **DOES NOT EXIST** |
| **per-market cap** | ❌ **DOES NOT EXIST** |

There is no key matching `market`, `cap`, `exposure`, `limit` or `allow-list` anywhere in
`AppConfig` or `application.yaml`; the complete `loans.*` inventory is 31 keys and contains nothing
of the sort. **The one he called "the risky ones" is the one that is not built.**

What pay-in-advance *does* have: the eight submit vetoes, the two profitability floors, and the
`PayInAdvanceLiquidationRouter`'s clean-refusal seam. **No per-market exposure limit of any kind.**

⇒ This does not contradict the ruling — the ruling is about not *adding* gates. It corrects the
inventory the ruling rests on, so the decision is made against what exists.

### 26.2 Two construction traps the change surfaced immediately

- **A `@Value` default is not a default of the class.** Adding the field with only
  `@Value("${…:preview}")` made **23 liquidation veto tests** go red: `@Value` fires only when Spring
  binds, so every other construction path held `null` and read as non-submittable. The default
  belongs **on the field** as well.
- **A class's own logic must go through its accessor.** Those tests build `Network` as an anonymous
  subclass overriding `getNetwork()` while the private field stays `null`. Comparing against the
  field says "not submittable" for a node that plainly is. **An overridden accessor that the class
  itself bypasses is a half-truth** — it behaves one way from outside and another from within.

---

## 27. MEMO — pay-in-advance vs convert: who chooses, who profits, which is better (2026-09-03)

Giovanni: *"does the bot operator decide if repay in advance or use minswap? How is the bot operator
profit calculated? What is better, anticipate principal? how about the operator profit? is it cut
before? Let's think about that properly."* Answered from the validators at `e0b818e` and the two
builders.

### 27.1 WHO CHOOSES — the lender picks the CLASS, the operator picks the MECHANISM

One flag on the **lender's bond**, `shouldLiquidationConvertToPrincipal`, partitions every loan:

| the lender's flag | actions the validators permit |
|---|---|
| `False` | **plain `Liquidate` only** — `lm_liquidate_action` requires `== False` |
| `True` | **`LiquidateAndPayInAdvance` OR `LiquidateAndConvert`** — both require `== True` |

**⇒ So the honest answer is BOTH, at different levels.** The operator never chooses which loans are
convert loans — the lender did that when the bond was minted. But **for the convert half, two
mechanisms are legal and the operator picks.** `LiquidationExecutor` currently makes that choice
implicitly: a convert-flagged bond always goes to the pay-in-advance seam, because it is the only one
built. **Today the operator has no choice because only one door exists — not because the protocol
denies one.**

### 27.2 THE TWO FLOWS, SIDE BY SIDE

| | **pay-in-advance** | **convert (Minswap)** |
|---|---|---|
| who supplies the principal | **the bot, from its own wallet** | **Minswap's batcher**, from the pool |
| bot's outlay | `oracle_convert(collateral − equity − fee)` in the principal asset | the transaction fee only |
| what the bot receives | **the collateral tokens** | **nothing but its fee slice** |
| where the proceeds go | bot pays the lender directly | asset-manager escrow, owned by the lender bond |
| settlement | atomic in one tx | **two-step**: our tx creates the order, the batcher fills it later |
| price risk | the bot holds the collateral afterwards | the validator fixes `minimum_receive = remainingDebt` |
| capital tied up | **one loan's principal, per loan** | none |

### 27.3 THE PROFIT — same formula, same units, very different position

**Both paths pay the operator the same slice, and it is CUT BEFORE — subtracted inside the
transaction, never captured as a wallet delta:**
```
liquidationFee = loanCollateralAmount × liquidationFeePerMille / 1000      (bond datum; 50‰ observed)
```
In both actions it is **subtracted from what the lender receives** — `collateral − equity −
liquidationFee` — and the validator does **not** constrain where it goes, so it lands wherever the
builder puts it. **⇒ It is denominated in the COLLATERAL asset, not in ada, on both paths.**

**But the resulting position is not the same, and this is the crux:**

- **Pay-in-advance is a PURCHASE.** The bot pays the converted value of `collateral − equity − fee`
  and receives `collateral − equity`. **It has bought the collateral at a discount equal to the fee.**
  The profit is real but **unrealised**: it is a token position, and it becomes money only when the
  bot sells.
- **Convert is a COMMISSION.** The bot supplies nothing and keeps only the fee slice. There is no
  position to unwind beyond the fee itself.

> **⇒ This is the answer to "is it cut before?": yes, on both — but on pay-in-advance the cut arrives
> wrapped in a purchase you also have to fund.**

### 27.4 ⚠ THE −27,303,331 FLOOR MUST BE RE-DERIVED, AND ITS BUG IS NOW NAMEABLE

The measured floor treated the fronted principal as **pure cost** and the acquired collateral as **no
benefit at all**. §27.3 says why that is wrong in one line: **pay-in-advance is a purchase, and the
accounting expensed it.** A model that books the outflow and ignores the asset will refuse every
pay-in-advance forever, at any margin.

**It does not transfer to convert at all** — there is no purchase to mis-book, because the bot buys
nothing. **Convert needs a fresh model, not a corrected one**, and its shape is simpler: outlay is
the transaction fee; income is the fee slice in collateral tokens.

⚠ **Both models then need the same missing ingredient: a price for the collateral token**, to state
profit in one currency. The oracle that already prices the collateral for the health factor is the
obvious source, and it is already in the executor's snapshot.

### 27.5 WHICH IS BETTER — and the case for each

**Convert wins on almost every axis for a liquidation bot:**
- **no capital.** Pay-in-advance ties up one loan's principal per loan and caps throughput at wallet
  size; convert's throughput is bounded only by fees. *This is the difference between a bot that
  scales and one that does not.*
- **no price risk.** The bot never holds the collateral, so it cannot be caught by a move between
  liquidating and selling.
- **no inventory.** The fee slice is small; a whole collateral position is not.
- **the strategy question shrinks** from "what do we do with acquired collateral" to "what do we do
  with fee income" (§25.3).

**Pay-in-advance wins in exactly two cases, and they are real:**
1. **No Minswap pool, or a pool too thin to deliver `remainingDebt`.** Convert is then not merely
   worse, it is impossible — the order would be killed. Pay-in-advance needs no venue.
2. **When the operator WANTS the collateral** — believes it under-priced, or is accumulating it
   deliberately. Then the discount is the point, and convert throws away the thing being sought.

**And one honest cost of convert that pay-in-advance does not have: it is not atomic.** Our
transaction creates an order; the batcher fills it afterwards. Between the two the position sits in
Minswap's contract — `killable: True` and the refund path make it safe, but it is a second event to
observe, and *"submitted" stops meaning "done"*.

### 27.6 WHAT BUILDING CONVERT COSTS, AND WHAT REMAINS HIS

**Engineering** (§25.5): a convert builder (never written); Minswap `OrderDatum`/`PoolDatum`
encoding; `compute_lp_asset_name` and the a→b direction; pool reference-input resolution; the two
blake2b datum hashes the order carries; trap-17 change handling for a token-bearing fee; registry
derivation (now unblocked). **Plus an economics model per §27.4.**

**His decisions, not ours:**
1. **The fee-inventory policy** — both paths accrue income in collateral tokens; what happens to it.
2. **Go/no-go on convert**, and whether it becomes the *default* for convert-flagged loans or an
   operator-selected alternative. §27.1 says the protocol permits either.
3. **Whether pay-in-advance stays at all** once convert exists — it is the capital-hungry path, and
   the market gates now bound exactly that exposure.

---

## 28. ⛔ CONVERT CANNOT BE REHEARSED ON PREVIEW — and the validator's full shape, read at `e0b818e` (2026-09-03)

Giovanni ruled the convert path in: *"liquidation by minswap/conversion should always be enabled by
default, additional configuration can be provided to disable a market OR to force anticipate instead
of convert."* This section is the reachability answer that had to come **before** the builder, the
routing design that ruling implies, and everything `lm_liquidate_and_convert_action.ak` demands.

### 28.1 The reachability verdict: offline YES, preview NO, live is MAINNET-ONLY

Three measurements, in the order that settles the question.

**① The mainnet parameterisation derives exactly — so the offline rig is unblocked.** Applying
FluidTokens' eleven quoted parameters to our vendored blueprint yields
`ed8d41e48d2b48c23c1673493e133b2e5a3300555026cab6c729683b`, **which is what the mainnet LMConfigDatum
publishes at field 5.** Re-measured this session, not carried over from §25.1.

**② The same Minswap coordinates do NOT derive preview.** Substituting preview's own loans
coordinates (fourth deployment, `d46f626f…` / `a7d4b762…`) while keeping the three mainnet Minswap
hashes yields `52b778c8…`. Preview's LMConfigDatum field 5 publishes **`aa3628d86e3f16b7d797d0633087859c11e3d200a5defc8ff0fc920e`**.

> **⇒ Preview's convert action is parameterised with Minswap coordinates we do not have, and
> cannot derive.** Placeholders were tested and excluded: all-empty gives `795441a3…`, all-zero-28
> gives `77d61704…`. Neither matches. FluidTokens pointed preview at *some* other Minswap deployment.

**③ Nothing on preview supports it anyway.** Blockfrost preview, this session:

| query | result |
|---|---|
| `/assets/policy/f5808c2c…` (mainnet Minswap V2 pool policy) | **404** — not on preview |
| `/scripts/aa3628d8…` (preview's OWN convert action) | **404 — never submitted on preview, ever** |
| `/scripts/00b8a30b…` (preview's pay-in-advance action) | present, PlutusV3, 7,051 bytes |

**The convert script has never appeared in a preview transaction.** The sibling path that *has* been
exercised is right beside it in the same datum, which is what makes the absence meaningful rather
than merely unobserved.

**⇒ THE THREE CONSEQUENCES, and they are not the same shape:**
- **The offline dry-eval rig is fully unblocked** and must run on **mainnet** parameterisation. It
  fabricates its own UTxOs, so it needs no live pool — only a fixture pool ref-input carrying a real
  `PoolDatum`. This is where the builder gets proven.
- **A live preview rehearsal is impossible**, on two independent grounds — we cannot even *derive*
  the preview script hash, and no Minswap V2 pool exists there to reference. Either alone is fatal.
- **⚠ So convert's first on-chain execution would be its first on-chain execution ever, on mainnet,
  with real funds.** Every prior path in this project earned a preview rehearsal first. This one
  structurally cannot. That is a fact for Giovanni's go/no-go, not an argument against building it.

**One question to FluidTokens unblocks a preview rehearsal** — the preview parameterisation of
`lm_liquidate_and_convert_action` (the three Minswap hashes), plus whether a Minswap V2 pool exists on
preview for any pair we can originate a loan in. Until then, offline is the whole rehearsal.

### 28.2 What the validator demands — the engineering surface, complete

Read from `validators/lender-manager/lm_liquidate_and_convert_action.ak` at `e0b818e`.

**The Minswap pool is a REFERENCE INPUT**, resolved through `get_inputs_from_smart_credential` and
required to hold `minswapPoolAssetName` under `minswapPoolPolicyId` (`quantity_of(...) == 1`). Its
inline datum is decoded as a `PoolDatum`, and `asset_a`/`asset_b` must be exactly the
(collateral, principal) pair in one order or the other — `lpABDirection` is `asset_a == collateral`.
**⇒ No live pool for the exact pair ⇒ convert is impossible for that loan**, not merely unprofitable.

**⛔ The 2.8 ada nobody would have predicted.** For a **non-ada** collateral the order output must
carry the swappable collateral **and exactly `2_800_000` lovelace**:

```
correctOrderValue = if collateralPolicyId == ada_policy_id {
    quantity_of(order, collateral) == swappableCollateralAmount
} else {
    quantity_of(order, collateral) == swappableCollateralAmount && quantity_of(order, "", "") == 2800000 }
```

That ada leaves with the order — `max_batcher_fee` is 700,000 of it and the receiver is the lender's
asset manager. **A token collateral therefore costs the bot 2.8 ada it does not get back**, four to
five times a typical transaction fee. An economics model that omitted it would approve losses while
looking careful. ⚠ *Provenance:* we count the whole 2.8 ada as the bot's, which is the conservative
reading; how much of the loan input's own min-ada `loan_claim_action` lets flow into the order is
**not yet measured**. A later measurement can only make the gate *less* strict.

**Two datum-carrier outputs.** The order's `EODInlineDatum{hash}` fields need the datums to exist on
chain, so the transaction must carry two outputs whose **inline datums hash to** `successDatumHash`
and `refundDatumHash`. Their **addresses are unconstrained** — so the builder self-addresses them and
their min-ada returns as bot-owned UTxOs in the same transaction. **That is an invariant the
economics gate rests on**: send them elsewhere and the gate silently under-counts cost.

The two hashes are `blake2b_256(serialise_data(AssetManagerDatumWithToken{…}))`, and they are **not
symmetric** — a detail worth having in writing before the builder:

| | `inputOutputReference` | `action` | `data` |
|---|---|---|---|
| success | `OutputReference{ transaction_id: "", output_index: 0 }` | `converted_to_liquidity_action` | the **collateral** `Asset` |
| refund | the loan input's **own** `output_reference` | `action_claimed_collateral` | `None` |

**Other hard requirements:** `expect equityInPrincipalCurrency == False` (mirror of §13);
`shouldLiquidationConvertToPrincipal` must be true; the lender bond output must equal its input under
`builtin.equals_data` **on the whole output**; `liquidationFeePerMille >= 0`; the loan NFT's asset
name must equal the lender bond's; `lenderBondInputIndexes` must be **unique** and as long as
`loanInputs`; and **the asset-manager withdraw script must be absent from the transaction** — so
convert and compound cannot share one transaction (that is the separate
`lm_liquidate_convert_and_compound_action`). The order address is
`Script(minswapOrderSpendScriptHash)` with the **lender's** stake credential in the non-CIP-113 case.

### 28.3 ⚑ The non-atomicity problem is RETIRED — on the protocol author's word

FluidTokens (Matteo), relayed first-hand 2026-09-03: if the order does not fill, **Minswap returns the
original collateral into `asset_manager` and the lender reclaims it as-is**; the bot is finished the
moment the order is created, whether or not it fills; the order's owner is the **lender**.

**⇒ No pending-order machinery, no placed-not-yet-filled state, no re-place idempotence.** The
liquidation **consumes the loan UTxO**, so the thing that would trigger a duplicate no longer exists.
Order-created **is** done, terminally.

**And a stronger consequence for the economics than the safety argument alone gives:** the bot's fee is
subtracted **before** the swap, so the bot holds the same position whether the order fills or not.
**Convert's profitability does not depend on the fill.** The worst case is a no-op for every party.

⚠ **One thing the scanner must not get wrong:** a *failed* order's returned collateral sits in the
asset manager **owned by the lender, as collateral** — it is not compoundable principal. Only a
*successful* swap's principal is what `lm_compound_action` later collects. The compound scanner's
existing `PRINCIPAL_NOT_ADA` / escrow-shape refusals are not obviously sufficient to tell those apart;
**that is a named check for the compound-side stage**, not an assumption.

### 28.4 The routing design: three states, one knob — `loans.liquidation.markets`

Giovanni's ruling makes convert the default and leaves two overrides. The existing key already carries
per-market state (§Thread A, `5a23cc4`), so it gains a **mode** rather than a second key:

```
loans.liquidation.markets: "<unit>:<mode>[:<cap>]"

  (unlisted)              → CONVERT      the default for every market
  <unit>:off              → SKIP         the bot does nothing in this market, on any path
  <unit>:anticipate:<cap> → PAY-IN-ADVANCE, capped — <cap> is MANDATORY
  <unit>:convert          → CONVERT, stated explicitly
```

**Why the mode comes before the cap.** The cap is only ever meaningful for `anticipate`: convert fronts
no principal, so there is nothing to cap. Putting the mode first makes an entry unreadable *unless* it
says which path it selects, and makes "a cap with no mode" — the ambiguous case — not expressible.

**Why unlisted means convert, and why that is still defensive.** Convert fronts no capital and holds
nothing, and §28.3 shows its failure mode is a no-op. **Pay-in-advance keeps the opposite default**:
it is reachable *only* via an explicit entry naming a cap, exactly as `5a23cc4` shipped it. The two
defaults differ because the exposures differ — the same reasoning that gives the compound path a floor
of 0 and the liquidation path 1,500,000.

**Three states that cannot contradict**, because they are one value: a market cannot be simultaneously
off and capped, or forced to anticipate without a cap. **A malformed entry resolves to `off`**, loudly
— never to a wider state.

**⚠ The behaviour change this carries, stated rather than discovered:** under `5a23cc4` an entry
`lovelace:500000000` meant "anticipate is permitted up to 500 ada". Under this grammar that form is
**malformed** and the market resolves to `off`. It is rejected rather than reinterpreted precisely
because reinterpreting it would silently change which path an operator's existing config selects.

**Note on scope:** `anticipate` is the *only* way pay-in-advance can now be reached at all. §27
established that the lender's bond flag partitions loans — `False` permits plain Liquidate only,
`True` permits pay-in-advance **or** convert. So with convert as the default for the `True` class,
**pay-in-advance is unreachable unless an operator forces it.** That is the intended reading of
Giovanni's ruling, and it is also the escape hatch for a market with **no Minswap pool**, where
convert is impossible rather than merely worse.

### 28.5 Stage 1, shipped: `ConvertEconomics`

A fresh model. §25.5's warning holds — the −27,303,331 pay-in-advance floor does not transfer, and
nothing was carried over.

```
income  = liquidationFee × collateral oracle price      (collateral units → lovelace, floored)
outlay  = txFee + (collateral is ada ? 0 : 2_800_000)
approved ⟺ income − outlay ≥ loans.liquidation.convert.profit-margin-lovelace   (default 0)
```

**⚠ The honest statement of what this compares.** The income is an **oracle valuation of an unrealised
token position**; the outlay is ada genuinely spent. The pay-in-advance floor went wrong by valuing an
acquired asset at nothing, and **the opposite mistake — crediting tokens as though they were cash — is
just as available.** It is named in the class rather than buried, and **the margin is the operator's
lever on it**: raise it until the ada-equivalent is worth the exposure.

`loans.liquidation.convert.enabled` **defaults `true`** — the only arming flag in this codebase that
does — and that is Giovanni's ruling applied, not weakened: the flag only matters for a node whose
operator has already passed `loans.enabled`, `loans.liquidation.mode`, `loans.liquidation.enabled` and
`loans.submittable-network`. A negative margin is **refused on mainnet at startup**, as on every other
path; an unknown network counts as mainnet.

**Verification:** 11 tests, and four mutants each killed by exactly one test — dropping the 2.8 ada
term, rounding the fee up, making the floor strict, and moving the `enabled` default off the field.
Suite after: **96 files, 818 tests, 38 failures, 22 skipped, 0 orphans** — failures and skips
unchanged from the pre-stage baseline.

**Unstarted at this boundary**, named so nothing reads as done: the market **mode** parsing (§28.4 is
a design, `MarketGate` still parses `<unit>:<cap>`); the registry's convert-hash derivation; the
convert builder; Minswap `PoolDatum`/`OrderDatum` encoding; `compute_lp_asset_name`; the dry-eval rig;
executor wiring; the image.

---

## 29. Per-market configuration as objects, and SHADOW — the rehearsal preview could not give (2026-09-03)

Two rulings from Giovanni, same turn. The second one answers §28.1: *"make a per-market mode: shadow,
meaning that if a liquidation can happen, we build the tx, validate and dump the tx bytes in the logs
so it can be analysed. Unsigned obviously."*

**⇒ That is the missing rehearsal.** Convert cannot be exercised on preview at all (§28.1), but it
**can** be exercised on **mainnet, against real loans, with zero fund risk** — build the real
transaction, evaluate it, dump the unsigned CBOR, sign nothing. And mainnet shadow is *stronger* than
the offline rig, because the protocol parameters, the UTxOs and the ex-units are the chain's rather
than a fixture's. **This should be the first mainnet posture for convert**, not a debugging aid.

### 29.1 The shape — a YAML object list, superseding the delimited string

*Refined within the hour by Giovanni, and the refinement is the important part: **`mode` is the
EXECUTION STATE, not the action.** An earlier draft folded shadow into the action as a flag; he
separated arming from strategy, which is right — they are independent questions and a shape that
conflates them cannot express "shadow the anticipate path".*

```yaml
loans:
  liquidation:
    markets:
      - unit: lovelace          # "lovelace", or policyIdHex + assetNameHex
        mode: SHADOW            # DISABLED | SHADOW | LIVE — execution state
        action: CONVERT         # CONVERT | ANTICIPATE — strategy; default CONVERT
        cap: 1000000000         # MANDATORY iff action: ANTICIPATE, meaningless otherwise
```

Every attribute is **named**, so nothing is positional and nothing is inferred from token order. "A cap
with no mode" and "500000000 read as a mode" stop being parse hazards and become unrepresentable.
**In particular `action` is never inferred from the presence of `cap`** — that would reintroduce
positional guessing in a shape whose whole purpose is to kill it.

**Five design questions, resolved:**

**① `mode` and `action` are orthogonal, and both are needed.** `mode` answers *may this market act*;
`action` answers *which transaction it would build*. Neither is derivable from the other: shadowing an
anticipate market and arming a convert one are both meaningful, and so is their inverse.

**② `DISABLED` is the off state, and there is no separate `active` boolean.** An `active` flag *and* a
`DISABLED` mode would be two knobs that can disagree — the answer to `active: false, mode: LIVE` would
be a coin flip.

**③ ⛔ The mode vocabulary is the GLOBAL one reused, not a new spelling — `DISABLED | SHADOW | LIVE`.**
Giovanni wrote `SHADOW | ARMED` and delegated the naming (*"or whatever"*), and two things argue
against `ARMED` specifically:
- **`armed` already means something else here.** `loans.liquidation.enabled` is the node's arming flag
  and `LiquidationConfiguration.isArmed()` reads it. A per-market `ARMED` would put two unrelated
  "armed"s in one subsystem.
- **Reusing `LiquidationConfiguration.Mode` makes the composition rule below a literal `min`**, because
  that enum is *already declared* in the order `DISABLED, SHADOW, LIVE`. One vocabulary at both scopes,
  and the safety rule falls out of the type instead of being enforced by hand.

His intent is preserved exactly; only the spelling differs, and it is a one-line change if he prefers
his own.

**④ ⛔ THE GLOBAL MODE IS A CEILING, NOT A DEFAULT — a market may be more restrictive, never less.**

```
effective(market) = min(loans.liquidation.mode, market.mode)      DISABLED < SHADOW < LIVE
```

This is the load-bearing safety rule of the whole design. Without it, `mode: LIVE` on one market would
let a node whose global posture is `shadow` **submit** — making the node-level dial a lie, and that dial
is what 23 existing veto tests and every operator rely on. With it, **no per-market value can ever
loosen a node-level safety setting**, which is the only property that makes per-market arming safe to
ship at all. ⚠ The clamp must be **logged loudly at boot** — an operator who wrote `LIVE` and got
`SHADOW` must be told, or they will believe they armed a market they did not.

**⑤ An UNLISTED market inherits the global posture — so you never have to list a market to shadow it.**
Its defaults are `mode:` *(the global mode)*, `action: CONVERT`, no cap. **`loans.liquidation.mode:
shadow` therefore shadows every market with no list at all**, which matters because shadow is convert's
only mainnet rehearsal (§29.5): needing to enumerate markets before rehearsing would make the rehearsal
cost proportional to the market count. A per-market `mode` exists to **deviate** — arm one market while
the rest shadow, or hold one market back while the rest are live.

⚠ **And one scope limit worth stating before an operator assumes otherwise: `action` partitions only the
`shouldLiquidationConvertToPrincipal == True` class.** A loan whose bond forbids conversion gets plain
`Liquidate` whatever the market says — the lender picks the class, the operator picks the mechanism
within it (§27).

**⚑ The object shape buys a real safety improvement the string could not.** Under the string grammar a
malformed entry had to resolve to *off*, because a half-parsed token could not be distinguished from a
typo. With named fields a broken entry is unambiguous — `action: ANTICIPATE` with no `cap`, an unknown
`mode`, a `unit` that is neither `lovelace` nor a well-formed hex unit — so it **aborts startup**,
matching the precedent `LiquidationConfiguration.parseMode()` already sets. This is gated behind
`loans.enabled`, so a typo can never refuse to start a production mainnet node that does not run the bot.

**⇒ The 5a23cc4 string form simply ceases to exist.** There is no old value to reinterpret, so the
behaviour-change hazard §28.4 flagged is gone — a clean structure replaces it rather than shadowing it.

### 29.2 ⚠ Two consequences of the object shape that will bite at deploy time, not at review time

**A YAML list is not expressible as one environment variable.** Every operator-facing knob in this
repo is an `AQUARIUM_*` / `LOANS_*` env var flowing through `docker/.env`, and the string form was one
of them. A `List<Market>` requires either **indexed env vars** —
`LOANS_LIQUIDATION_MARKETS_0_UNIT`, `LOANS_LIQUIDATION_MARKETS_0_MODE`, … — or a **mounted YAML
fragment**. Both work; neither is what the current `docker-compose.yaml` does. **This belongs in the
operator docs in the same change that ships the binding**, not after the first operator finds it.

**And it forces `@ConfigurationProperties`.** `@Value` cannot bind a list of objects from relaxed env
names at all. The binding therefore lands as its own `@Component @ConfigurationProperties` class
rather than as more fields on `LiquidationConfiguration` — which also avoids reworking a class 23
existing veto tests construct directly.

### 29.3 ⚠ The risk posture this widens, stated so it is owned rather than discovered

Under `5a23cc4` an unlisted market did **nothing at all**. Under this design an unlisted market
**converts**. Combined with `loans.liquidation.convert.enabled` defaulting true (§28.5), **an armed
liquidation node will, on its first boot after this change, convert in every market it sees.**

What still stands between that and a submission: `loans.enabled`, `loans.liquidation.mode`,
`loans.liquidation.enabled`, `loans.submittable-network`, and the convert economics gate. That is a
deliberate posture and it follows Giovanni's ruling — but it is a widening, and the honest mitigation
is the one he supplied in the same breath: **`shadow: true` first.**

### 29.4 The cap semantics — CONFIRMED, with the wording gap named

Giovanni: *"pay-in-advance is reachable only when the market is explicitly set to anticipate AND the
cap is higher than the principal repayment due."* The first half is the design above. The second half
is already `5a23cc4`'s behaviour and it **inherits unchanged** — with two corrections to the wording,
both in favour of the code that already exists:

- **The comparison is `>=`, not `>`.** Read at `e0b818e`,
  `lm_liquidate_and_pay_in_advance_action.ak`'s `validate_repayment_output` requires
  `quantity_of(repaymentOutput.value, …) >= repaymentAmount`. **A deposit exactly equal to the
  requirement is valid on chain**, so a strict `>` would refuse a candidate sitting exactly on the
  operator's own stated bound — silently raising every cap by one lovelace, the same defect a strict
  profit floor has. `MarketGate` allows `cap == required` and that is the validator's answer, not a
  preference. `MarketGateTest.theBoundaryIsInclusiveOnTheCap` already tested it; it now **cites the
  validator line** so the two cannot drift.
- **"The principal repayment due" is the intuitive figure and the wrong one.** The cap is compared
  against `convertedLoanCollateralToPrincipalAmount`, **not `remainingDebt`**.
  `PayInAdvanceLiquidationRouter` already feeds the validator's figure and carries a comment saying
  so; both are now named in `MarketGate`'s own javadoc, where the comparison lives.

### 29.5 SHADOW: reuse, decisively — it is one veto and one log line

**This repo already has the machinery, and a second shadow mechanism that could disagree with the
first would be the two-knobs problem at a larger scale.** What exists today:

- `loans.liquidation.mode: shadow` → the loop **scans, builds, prices, size-checks and records**, then
  the ordered veto ladder fires `S1 MODE_NOT_LIVE` and nothing is signed.
- The outcome and the veto are **separate fields**: a vetoed candidate still records
  `WOULD_SUBMIT` or `UNPROFITABLE` on its own merits. So a veto's position in the ladder cannot
  swallow the profitability verdict.
- `LiquidationDecision` **already carries `txCborHex`**, populated for every built candidate regardless
  of veto, alongside `txHash`, `txSizeBytes`, input/output/reference/redeemer counts and the full
  pricing arithmetic. `LiquidationController` already serves it.

**⇒ Per-market shadow extends this; it does not duplicate it.** Three additions, and no new mechanism:

1. **One new veto, `MARKET_NOT_LIVE`**, inserted after `S3 NETWORK_NOT_PREVIEW` and before
   `S4 NOT_PROFITABLE` — policy vetoes before candidate vetoes, so an operator on a live node reads
   the real reason rather than a downstream symptom. S4–S8 renumber to S5–S9. Its detail names
   **which scope decided**, since the effective mode is a clamp: the market asked for `SHADOW`, or the
   global ceiling held it there.
   ⚠ **`DISABLED` is NOT this veto.** A disabled market refuses *before* the build, the way
   `MARKET_DISABLED` does today — there is nothing to rehearse. `SHADOW` is precisely the state that
   builds and then stops, which is the whole distinction: **shadow shows you the transactions that
   would have gone.**
2. **The dump**, at INFO on a stable greppable prefix: candidate id, variant (which action was
   shadowed), the decision arithmetic (income / outlay / margin), **the per-redeemer ex-units**, size,
   and the **unsigned CBOR hex** on its own line so a grep can separate metadata from payload.
   ⚠ A 10 kB transaction is ~20,000 hex characters; that is the cost of "analysable" and it is why the
   payload gets its own line.
3. **Ex-units, which are recorded nowhere today.** The decision counts redeemers but never says what
   they cost — and per CCL trap 8 that is exactly the number that must be read off the **built,
   deserialised** transaction rather than off an evaluator's report. Shadow is the feature that finally
   needs it, so it is added there.

### 29.6 ⚠ The honesty boundary — shadow proves PHASE 2, not phase 1

A dumped transaction is a strong artefact and **not a submission guarantee**, and the log line must say
so rather than leaving an operator to infer it:

- **What it proves:** every script ran under a real evaluator and returned ex-units — the phase-2
  failure mode that forfeits collateral is exactly what shadow rules out.
- **What it does not prove:** the ledger's phase-1 rules — fees, min-ada, the witness set, collateral.
  CCL traps 9 and 9a are both phase-1 fee defects that a clean evaluation says nothing about.
- **And the size is an under-estimate**: an unsigned transaction carries no vkey witnesses, so the
  signed body is larger than what `txSizeBytes` reports. Against a 16,384 `max_tx_size` that matters
  (§22.9).
- **Mainnet shadow is nonetheless the strongest rehearsal this project can build**, because the
  protocol parameters, the UTxOs and the ex-units are all the chain's. The offline rig's parameters are
  a fixture's, and trap 7 measured that library cost models drift *low* — the dangerous direction.

### 29.7 ⇒ WHEN to force anticipate — answered by the protocol author, and it validates the shape

Giovanni asked FluidTokens directly *"when should the bot pay in advance instead of always using
Minswap?"* Matteo: **for all tokens that do not have a reliable pool on Minswap, because the order must
always deliver at least the minimum the lender expects.**

That is §27's reasoning confirmed from the source. `minimum_receive` is fixed on chain at
`remainingDebt` (§25.2), so a pool too thin to deliver the debt means the order **will not fill** —
convert is not merely worse there, it does not complete.

**⇒ Pool reliability is a per-token property and it is the OPERATOR'S judgement.** So
`action: ANTICIPATE` on a market is exactly the sentence *"this token has no pool I trust — take the
capital route here"*, and every unlisted or `CONVERT` market takes the swap. **This validates two
choices already made rather than changing them:** keying markets per unit, and making anticipate a
per-market override rather than a global toggle.

**⛔ No automatic pool-depth detection, and no runtime fallback from convert to anticipate.** It would
need a pool-depth oracle this project deliberately does not have, and it is unnecessary: a mis-set
convert market **self-corrects into a refund**, not a loss. If Giovanni later wants auto-routing it is a
question to raise, not scope to assume.

⚠ **One correction to the gloss that came with this, because the sign matters.** It is tempting to say a
non-filling order means "the bot spent 2.8 ada and a fee for a no-op". **It does not.** The liquidation
fee is subtracted *before* the swap and stays with the bot either way, so **the bot's position is
identical whether the order fills or not** (§28.3) — the 2.8 ada is a cost it already paid for a fee it
already holds. What differs on a non-fill is the **lender's** outcome: they get their collateral back
rather than their principal. Calling that a no-op *for the bot* would understate what the bot keeps and
misprice the gate; calling it a loss would overstate it.

**Unstarted at this boundary:** the `@ConfigurationProperties` binding and its startup validation; the
`MARKET_SHADOW` veto and ladder renumber; the dump and its ex-units; the operator-docs change for
indexed env vars; and everything §28.5 already listed for convert itself.

---

## 30. The convert outlay becomes a FLOOR, not a sum — and the ada-collateral gap, measured (2026-09-03)

Giovanni's refinement to the §28.5 gate, first-hand: *"the margin must take into account for convert the
ADA spent to interact with the DEX. So between batcher and tx fee you can round at 4 ada or 5 ada. No
need to convert collateral token etc. As long as, at least at tx time, given the DOLLAR VALUE of the bot
profit and on-chain costs to do the swap, the operator is net positive even PRE-CONVERSION."*

### 30.1 ⛔ The suspected ada-collateral under-count: MEASURED, and it is not where it was thought to be

The question raised was whether an **ada**-collateral convert also pays the batcher fee and a mandatory
order ada, making the shipped `(ada ? 0 : 2.8M)` branch too generous. Read at `e0b818e`:

```
if collateralPolicyId == ada_policy_id {
    quantity_of(minswapOrderOutput.value, collateralPolicyId, collateralAssetName)
        == swappableCollateralAmount            ← for ada this IS the order's total lovelace
}
```

**⇒ For an ada collateral the validator mandates NO extra ada whatsoever** — the order's entire lovelace
is the swappable collateral. So `max_batcher_fee` of 700,000 comes out of the **swap input**, which is
**the lender's proceeds, not the bot's wallet.** The `ada ? 0` branch is correct as a *measurement*.

**But the concern behind the question was right even though the mechanism was not.** An ada-collateral
convert's measured cost is a transaction fee and nothing else — under a lovelace, where a token
collateral pays 2.8 ada. **A gate resting on measurement alone would wave through ada converts that pay
barely more than a fee**, which is exactly what Giovanni's rounding is guarding against.

### 30.2 The instrument: `max(measured, floor)`, never a sum

```
measuredOutlay = txFee + (collateral is ada ? 0 : 2_800_000)
outlay         = max(measuredOutlay, loans.liquidation.convert.dex-cost-floor-lovelace)   # default 5_000_000
```

**A floor, not an addend, and the distinction is load-bearing.** Adding a batcher-fee term to the
measurement would charge the batcher twice on a token collateral and would *falsely attribute* it on an
ada one. **A floor captures the conservatism without asserting who pays what** — the honest instrument
for a cost whose incidence is genuinely split between the bot and the lender's proceeds. The assessment
records `measuredOutlay`, `dexCostFloor` and `outlay` separately, and `boundByDexCostFloor()` tells an
operator that lowering the **floor**, not the transaction, is the lever on a refusal.

Default `5_000_000` — the conservative end of Giovanni's "4 ada or 5 ada". Refused at startup when
negative or unset, **on every network**: unlike the profit margin, a negative cost of doing work has no
reading that expresses a deliberate operator choice. It is separable from
`profit-margin-lovelace` and cannot contradict it: one says what the interaction costs, the other says
how far above break-even the operator wants to be.

### 30.3 "Dollar value" — and why no USD feed is needed

His bar is *"given the dollar value of the bot profit and on-chain costs to do the swap, the operator is
net positive"*. The gate compares the fee's **oracle value in lovelace** against **costs in lovelace**,
and `OraclePriceFeed` is denominated in *lovelace per smallest token unit* — so **both sides carry the
same ADA/USD factor and it cancels.** A lovelace comparison *is* his dollar comparison, exactly, with no
USD oracle and no new external dependency (which would have been an escalation under the constitution).

### 30.4 ⚑ And he has consciously ruled on the memo's objection

§27 and §28.5 both flagged that crediting an unrealised token position at oracle price is the mirror of
the pay-in-advance error. **Giovanni has now ruled that risk acceptable at tx time** — *"no need to
convert collateral token etc … net positive even pre-conversion"* — with net-positive as the bar and the
margin as his cushion above it. **The model is unchanged; what changed is that it is now a decision
rather than an assumption**, which is the whole reason it was named in-class rather than buried.

**Verification:** 15 tests. Four mutants on the new arithmetic, each killed: floor-as-sum (3 tests),
floor-ignored (2), floor-as-cap (6), and the original drop-2.8-ada (2). Suite **96 files, 822 tests, 38
failures, 22 skipped, 0 orphans** — failures and skips unchanged.

---

## 31. ⛔ OPERATING AT A LOSS IS A FEATURE, ON EVERY NETWORK — the mainnet hard-fail is gone (2026-09-03)

Giovanni, first-hand, closing the last standing economics question: *"it's fundamental to allow
operators to operate at a loss. Protocol must be kept bad-loss-free at all costs. So you can expect our
bot to be used by FluidTeam to clean up loans non-profitable for other operators but still need cleanup.
So up to you what you want to do. BUT operating at a loss MUST be implemented even on mainnet."*

**⇒ The framing changes, not just the code.** A negative margin was modelled as an *operator mistake* —
"copy the working preview config to mainnet" — and guarded accordingly. It is now a **documented
operating mode**: the bot is a protocol-health tool, and a stated-loss liquidation, compound or convert
that clears an unhealthy loan nobody else will profitably touch is the intended public-good function.
FluidTokens' own team is a foreseen operator of exactly that mode.

### 31.1 What was removed, and what replaced it

| path | before | after |
|---|---|---|
| `LiquidationExecutor.guardMainnetNegativeMargin()` | `IllegalStateException` at construction on mainnet | loud WARN, node comes up and runs |
| `CompoundEconomics.announceAndGuard()` | same | same |
| `ConvertEconomics.announceAndGuard()` | same | built honour-anywhere from the start |

The replacement is one line an audit can grep, on **every network**, louder on mainnet, naming the
**path** and the **stated floor**:

```
⛔ OPERATING AT A LOSS ON MAINNET, BY OPERATOR CONFIGURATION — path: convert;
   loans.liquidation.convert.profit-margin-lovelace = -2000000 lovelace (network mainnet). …
```

⚠ The "unrecognised network counts as mainnet" rule survives, but its **purpose inverted**: it used to
decide who gets *refused*, and now decides who gets the *louder line*. Fail-closed in reporting rather
than in arming.

### 31.2 ⛔ THE DEFAULT IS NOW THE ONLY PROTECTION — which promotes it to an invariant

Defaults are unchanged and still refuse every loss on every network: **liquidation 1,500,000 in code /
5,000,000 in the shipped yaml · compound 0 · convert 0 (net-positive)**. So an operator who configures
nothing cannot operate at a loss anywhere, and **only an explicitly negative value** does — which no
copy-paste of a zero or positive configuration can produce. That is Giovanni's defensive-defaults ruling
intact, with the loss-making mode as a deliberate opt-in rather than an accessible accident.

**⇒ Because the guard is gone, "every shipped default is non-negative" stops being a nicety and becomes
load-bearing.** If one ever ships negative, a node that stated nothing would work at a loss on mainnet
and **nothing in the build would notice** — the new boot line would fire and read like configuration
working as designed. `LossMakingIsOptInTest` pins it in both places the promise lives: the `@Value`
defaults in the source, and the env-var defaults in `application.yaml` (which override them, and are
what an operator actually receives).

### 31.3 The one guard that stays fatal, and why it is not the same thing

`loans.liquidation.convert.dex-cost-floor-lovelace` still **aborts startup** when negative or unset.
It is not a margin: it states what the work *costs*, not what the operator is *willing to lose*. A
negative cost of doing work has no reading that expresses an intention — it is a typo — so the ruling
does not reach it.

**Verification:** the mutant is the old hard-fail reappearing, and it is killed on all three paths by
one test each. The boot line is asserted through a real log appender, including that it names the path
and the floor. Suite **98 files, 833 tests, 38 failures, 22 skipped, 0 orphans** — distribution
unchanged.

---

## 32. ⛔ `d832b78e…` IS NOT A CONVERT — it is the loan a convert would act on (2026-09-03)

Relayed as *"a test convert tx ready to go"* and passed on as a **golden reference: a real, on-chain,
validated instance of the exact transaction shape the builder must produce**, to be used as a diff
target. **Decoded from mainnet, it is a loan-origination (borrow) transaction.** Building a convert
builder to diff against it would have been building against the wrong shape — so this is recorded
first, before anything downstream rests on it.

### 32.1 What settles it, from the chain rather than from the name

| evidence | reading |
|---|---|
| **three MINTs** — loan NFT `0061ade3…`, lender bond `bcd713bb…`, borrower bond `eadc69a5…` | **a convert mints nothing** |
| **no Minswap order output** | creating one is the convert action's entire purpose |
| **no Minswap pool reference input** | the convert action requires one and fails without it |
| a pool UTxO spent and re-created, 53.75 → 33.75 ada | principal leaving a pool: origination |
| ref inputs include **both bond-MINT scripts** (`90efd6a6…`, `cf66c3c5…`) | the two §24.2a says convert never touches |

`valid_contract: true`, block 13,892,677, 3,933 bytes, 7 redeemers, 3 withdraw-0 invocations.

**⇒ There is still no convert transaction on any network**, so §28.1 stands unchanged: the offline rig
plus shadow-on-mainnet remain the whole rehearsal. What this does retire is the idea that a diff target
exists.

### 32.2 ✅ What it IS — the first real convert candidate this path has ever had

| | value |
|---|---|
| loan | `d832b78e…#1` — loan NFT + **100,000,000 FLDT** (`577f0b13…0014df10464c4454`) |
| principal | **20,000,000 lovelace** |
| collateral oracle | `93794f9b…` `oracleFLDTC3` (Charli3 FLDT) |
| lender bond | `d832b78e…#3` |
| **shouldLiquidationConvertToPrincipal** | **True** ⇒ the convert action's first conjunct passes |
| **liquidationFeePerMille** | **50** (5%) |

Every fixture is committed under `src/test/resources/loans-v4/` with its provenance.

### 32.3 ⚑ Three Minswap facts the builder needs, measured on chain rather than recalled

The vendored `amm_dex_v2` library is **not** in the upstream clone (it is a Aiken dependency, not a
committed source file), so these were taken from mainnet instead — which is the stronger source anyway.

- **`compute_lp_asset_name` is SHA3-256, not blake2b.** Computed both ways for ADA/FLDT and asked the
  chain: blake2b gives `b3675eb2…` → **404**; `sha3_256(sha3_256(polA‖nameA) ‖ sha3_256(polB‖nameB))`
  gives `bc53f5c2…` → **200, the live LP token**. A single hash-function assumption would have made
  every order datum wrong, and it would have failed only on chain.
- **The pool NFT's asset name is the constant `4d5350` — ASCII `MSP`** — the same for every pool, not a
  per-pool hash. So the redeemer's `minswapPoolAssetName` is a constant, while `lpAssetName` is the
  computed one; they are different values under the same policy, and confusing them is easy.
- **The live ADA/FLDT `PoolDatum`**, field order confirmed against the validator's `expect`:
  `asset_a` = **ADA**, `asset_b` = **FLDT**, reserves 1,692,342,884,761 / 7,596,442,927,398,
  fees 80/80, `fee_sharing = Some(1666)`. ⇒ For this loan **`lpABDirection = False`**, and the
  validator's else-branch (`asset_b == collateral && asset_a == principal`) holds. **The pool exists and
  the pair is convertible.**

⚠ The pool UTxO reference moves on every swap. A builder must resolve it by **NFT at run time**; a
pinned coordinate is a fixture only.

### 32.4 ⛔ AND THE ANSWER THE OPERATOR NEEDS BEFORE ARMING THE BOX: this candidate is REFUSED

Run through the shipped gate (`9f5d101`), priced at the pool's own mid-price:

```
liquidationFee   = 100,000,000 × 50 / 1000 = 5,000,000 FLDT units   (income, in TOKENS)
feeValueLovelace = 1,113,904                                        (≈1.11 ada)
measuredOutlay   = 500,000 tx fee + 2,800,000 order rider = 3,300,000
outlay           = max(3,300,000, 5,000,000 floor) = 5,000,000
net              = −3,886,096   ⇒ NET_BELOW_FLOOR, REFUSED
```

**A 5% fee on a 20-ada loan is about 1.1 ada, and one DEX interaction costs 5.** So the shipped
defaults refuse it, correctly and by design — a bot that took it would be paying ~3.9 ada to do the
work.

**⇒ To convert this loan Giovanni must state a negative margin**, which is legal on mainnet since
§31 and announced loudly at boot. That is not a workaround; it is precisely the protocol-health mode he
described the same day — *"our bot to be used by FluidTeam to clean up loans non-profitable for other
operators but still need cleanup"*. **This candidate is that case, arriving within hours of the ruling
that permits it.**

The arithmetic is pinned in `MainnetConvertCandidateTest`, including the exact floor that accepts it,
so a change to the gate that quietly flips this verdict fails a test rather than surprising an operator.

⚠ The figure uses the **pool mid-price**; production prices the fee off the Charli3 `oracleFLDTC3` feed
the loan names. The two will differ slightly — but not by the ~4× that would change the verdict.

---

## 33. SHADOW is a rehearsal, not a refusal — and the dump that would have lied (2026-09-03)

Stage: `MARKET_NOT_LIVE` plus the shadow dump. The per-market execution state from §29 now has the
mechanism it was designed against.

### 33.1 The ladder is nine, and the first four are POLICY

`MARKET_NOT_LIVE` is inserted as **S4**, after the network and before profitability; S4–S8 renumber to
S5–S9. Policy vetoes before candidate vetoes, so an operator who held a market back reads *that* rather
than a downstream symptom.

**⇒ And this veto is what turns per-market SHADOW into a rehearsal.** By the time it fires the candidate
has already been scanned, built, priced, size-checked and recorded — only the submission is withheld.
`MarketGate`'s temporary `MARKET_SHADOW_NOT_YET_IMPLEMENTED` refusal existed exactly until this veto did,
and is now deleted.

⚠ **`DISABLED` is not this state.** A disabled market is held here too, but there is nothing to analyse;
`SHADOW` is precisely the mode that builds and then stops. **Shadow shows you the transactions that
would have gone.**

An unlisted market still inherits the node mode, so an armed node with an empty list submits exactly as
before — asserted, because shipping this stage otherwise would have silently disarmed every existing
operator.

### 33.2 ⛔ THE DUMP ALMOST SHIPPED AS A LIE — and the rig is what caught it

The dump prints, at INFO on greppable prefixes: `SHADOW TX` with the variant, the veto that held it, the
unsigned size, **per-redeemer ex-units**, and the economics; then `SHADOW CBOR` with the payload on its
own line (a ~10 kB transaction is ~20,000 hex characters).

**The first version reported ex-units as though they were measurements. They were `10000/10000` and
`10000/1000` — cardano-client-lib's placeholder budget, CCL trap 8's exact signature.** The veto rig
builds through the *no-evaluator* constructor on purpose (its subject is the ladder, not costing), so
its transactions have never carried a real budget — and the dump would have presented them as a
validated rehearsal.

> **⇒ A rehearsal whose entire claim is "every script evaluated" is WORSE THAN USELESS when the budgets
> are placeholders, because it looks like proof.** This project has already paid for that once: the
> 2026-08-21 incident shipped precisely these numbers, under-declaring by two to five orders of
> magnitude, and 307 tests missed it because every ex-units assertion read the evaluator's *report*
> rather than the transaction.

So `exUnitsSummary` **detects the signature** — every redeemer at 10000 mem with 10000 or 1000 steps —
and prefixes `⛔NOT-VALIDATED:PLACEHOLDER-EX-UNITS(CCL-trap-8)`, with a matching **ERROR** line saying
the dump proves nothing. ⚠ **All, not any:** one measured redeemer among placeholders is a *different*
fault (trap 8's "validate the payload, not the envelope"), and calling it placeholders would send an
operator to the wrong cause.

**⚑ The general lesson, which is the one worth keeping.** The rig supplied a weaker input than
production earns — the same shape as the 2026-08-21 "fixture supplies what production must earn" —
but this time the *feature under test was the reporting of that very property*. **A diagnostic must be
able to report its own absence of evidence.** The fix was not to make the rig stronger; it was to make
the dump refuse to overclaim, which holds for every future caller including the ones with no evaluator
at all.

### 33.3 Verification

Four mutants, each killed: the market veto removed (2 tests), the placeholder marker dropped (2), the
dump skipped for `MARKET_NOT_LIVE` (1), and `all`→`any` on the placeholder test (2). Both branches of
the summary are driven **directly** rather than through a rig, so neither depends on a harness happening
to be wired one way. Suite **100 files, 842 tests, 38 failures, 22 skipped, 0 orphans** — distribution
unchanged.

**Unstarted:** the convert builder and its §28.5 engineering, now with §32.3's measured Minswap facts;
and the operator docs (shadow-first, the stated-loss mode, indexed env vars).

---

## 34. The convert encoder — every Minswap byte taken from the chain, and one from upstream (2026-09-03)

Builder sub-stage A: `ConvertTxEncoder`. Pure functions, no transaction yet — the order datum, the two
datum hashes the order embeds, the lp asset name, and the action's own withdraw redeemer.

**Pinned against three independent oracles rather than against a reading**: the **live mainnet chain**
(the LP token, a real filled order), the **deployed blueprint** (the redeemer's field order), and the
**upstream Minswap declarations** (one constructor index no ordinary swap exercises). Every constant
here fails **only on chain** if wrong.

### 34.1 What the chain settled, and what it could not

| fact | source | why the other source could not settle it |
|---|---|---|
| `compute_lp_asset_name` = **SHA3-256, twice** | chain: blake2b → 404, SHA3 → the live LP token | — |
| pool NFT asset name = **`4d5350`** (`MSP`), constant | chain: the pool creation output | — |
| `OrderDatum` = **9 fields**, in the validator's record order | chain: a real filled order | — |
| `SwapExactIn` = constructor **0**; `SAOSpecificAmount` = **0**; `OAMSignature` = **0** | chain, corroborated upstream | — |
| **`EODInlineDatum` = constructor 2** | **upstream declaration only** | ⛔ **every ordinary swap on chain carries `EODNoDatum` (0)** — the live sample confirms the record's field ORDER and can say nothing about this index |

**⇒ The last row is the interesting one.** A sample proves what it contains, and a variant nobody uses
is exactly what a sample cannot reach. `EODNoDatum`, `EODDatumHash`, `EODInlineDatum` — off by one and
the datum decodes to a *different variant*, failing `equals_data` with nothing in the error pointing at
an enum.

### 34.2 Three shapes that are not what the sibling encoders assume

- **⛔ The success datum's `transaction_id` is the EMPTY byte string.** The validator writes
  `OutputReference { transaction_id: "", output_index: 0 }` literally. `LiquidationTxEncoder`'s
  `outputReference` helper *rejects* anything that is not 64 hex chars — correct for its own use and
  wrong for this one, which is why this path does not reuse it. Reuse would have thrown at build time,
  which is the good failure; a laxer helper would have shipped a wrong hash.
- **The success datum's `data` field carries the collateral `Asset`**, where every other asset-manager
  datum this codebase writes carries `None`. The field is typed `Data`, so **nothing but the hash
  comparison would ever notice**.
- **⛔ `lenderBondInputIndexes` comes BEFORE `lenderBondAssetNames` in the redeemer** — the reverse of
  the order the validator's *body* reads them in. Both are per-loan lists, so a swap produces a
  redeemer that decodes cleanly into the wrong fields. **The declaration is what the encoding follows;
  the body's mention order is not evidence of anything.**

Also enforced at encode time: every per-loan list must have the same length. The validator walks them
all by one index, so a short list does not fail — it silently reads *another loan's* value.

### 34.3 ⚠ A defect in the mutation harness, not in the code

The first `blake2b-not-sha3` mutant reported **`killed = []`** — apparently surviving. It had not
survived; it had **failed to compile**, and the harness counted failures without counting whether
anything ran. **"No failures" and "nothing ran" printed identically.**

That is this repo's own `cleanTest` lesson one level up (CLAUDE.md: *assert the number of XML files,
not only the totals*), and it is worth stating as a rule because it recurs in every ad-hoc mutation
run: **a mutation harness must report the test COUNT alongside the kill list, or a mutant that breaks
the build reads as a mutant the suite could not catch — and the conclusion drawn is the exact opposite
of the truth.** Re-run with a compiling substitute (SHA-256 for SHA3-256, and single- for double-hash):
both killed, by the live-LP-token test.

### 34.4 Verification

Six mutants, each killed by exactly one test: SHA-256 for SHA3 · single- for double-hash ·
`EODInlineDatum` moved to constructor 1 · the two redeemer list fields swapped · `killable` set false ·
a non-empty `transaction_id` on the success datum. Suite **101 files, 852 tests, 38 failures, 22
skipped, 0 orphans**.

**Unstarted:** the builder itself (`ConvertTransactionBuilder`) — output layout, the pool resolved by
NFT at run time, the byte-echoed lender bond, trap-17 token change, and a real evaluator from the first
commit; then its dry-eval against the §32 fixtures; then the operator docs.

---

## 35. The convert plan, driven by the real pool — and a test that passed for the wrong reason (2026-09-03)

Builder sub-stage B1: `MinswapPoolDatumConverter` and `ConvertOrderPlan`. Everything
`lm_liquidate_and_convert_action` **dictates** about the order, computed once and refusing rather than
guessing, before any transaction is assembled. **Sub-stage B2 — the CCL assembly, with a real
evaluator from its first commit — is deliberately not in this commit.**

### 35.1 Why the plan is its own object

Every value in it is fixed by the validator, not chosen by us: the direction comes from the pool's own
datum, `minimum_receive` is `remainingDebt`, the order's ada is a literal, and the two datum hashes are
constructions reproduced byte for byte. Computing them inside a `QuickTxBuilder` chain would mix
decisions checkable **against the contract** with decisions checkable only **against a node**.

**⛔ And the pool is resolved by its NFT at run time, never by a coordinate.** A Minswap pool UTxO is
spent and re-created on *every swap*, so a pinned reference is stale within minutes. The plan takes the
**datum the caller found**, not a reference it remembered.

### 35.2 Three values the plan gets from the pool rather than from us

- **Direction.** The live pool declares `asset_a = ADA` and the real candidate's collateral is FLDT, so
  `lpABDirection` is **False** and the validator takes its else-branch — which then demands
  `asset_b == collateral && asset_a == principal`. Deciding direction from our own idea of ordering
  would invert the swap.
- **The lp asset name** is computed from the **pool's declared order**. The pair is the same set either
  way; the hash is not.
- **A pool for a different pair is refused, not priced badly.** It is an impossible transaction, and
  saying so with its own refusal keeps it out of the economics.

The order's ada differs by collateral kind — 2,800,000 alongside a token, and for an ADA collateral the
order's lovelace **is** the swappable amount with no rider at all (§30.1).

### 35.3 ⛔ A TEST THAT PASSED FOR A REASON OTHER THAN THE ONE IT NAMED

The arity guard on `PoolDatum` had a test: feed a two-field datum, assert `RuntimeException`. A mutant
that **deleted the arity check entirely** left it **green** — because `f.get(1)` then throws
`IndexOutOfBounds`, which is also a `RuntimeException`. **The test passed for a different reason than
the one in its name, and would have kept passing with the guard gone.**

**⇒ And the case it was testing was the harmless one.** A datum with a field *missing* throws on its
own and needs no guard. **The dangerous change is a field ADDED**: eleven fields decode perfectly into
the wrong positions and return a pool for a **different pair**, with nothing amiss anywhere. The test
now feeds exactly that, with recognisable wrong assets at positions 1 and 2, and asserts the refusal
**names both arities**. It kills the mutant.

> **⚑ The rule this is an instance of: an exception-type assertion is only as specific as the narrowest
> thing that can throw it.** `assertThrows(RuntimeException.class)` is satisfied by every bug on the
> path to the behaviour under test, including the absence of the very check being tested. Assert on the
> message, or on a distinguishing property, whenever the guard and its absence produce the same
> exception class.

*This is the third measurement-that-cannot-detect-its-own-absence in two days*, after the shadow dump's
placeholder ex-units (§33.2) and the mutation harness that printed `killed=[]` for a mutant that never
compiled (§34.3). **Three unrelated instances is a pattern, not a coincidence — and all three were found
by mutating, never by reading.**

### 35.4 Verification

Six mutants, each killed: direction inverted (5 tests) · the order-ada branch flipped (1) ·
the lp-name argument order swapped (1) · `<= 0` weakened to `< 0` on the swappable amount (1) ·
the pool's asset_a/asset_b swapped in the decoder (2) · **and the arity check removed (1, only after
the test was rewritten)**. Suite **102 files, 862 tests, 38 failures, 22 skipped, 0 orphans**.

**Unstarted:** B2, the CCL assembly — output layout with the two-pass index discovery (trap 1), the
byte-echoed lender bond under `equals_data`, trap-17 token change, the two self-addressed carriers, and
**a real evaluator from the first commit** with ex-units asserted off the built, deserialised
transaction. Then C, the dry-eval against these fixtures. Then the operator docs.

---

## 36. The last underivable field, derived — and convert availability becomes a reported fact (2026-09-03)

Builder sub-stage B1.5, a prerequisite the assembly could not proceed without: **the registry could not
derive `lm_liquidate_and_convert_action`**, so no builder could withdraw through it.

`LoansConfigVerifier` carried, since the beginning: *"Field 5 … is deliberately unchecked: it takes
five Minswap parameters we do not have."* We have them. The three Minswap coordinates are now
configuration (`loans.minswap.*`), defaulted to FluidTokens' **verified mainnet** parameterisation, and
applied to the vendored blueprint they derive `ed8d41e4…` — **exactly what the mainnet LMConfigDatum
publishes at field 5**. Every credential in the deployment is now knowable from the artefact we ship.

The two `*WithdrawScriptHash` parameters stay **constants, not keys**: Minswap V2's pool and order
validators are plain PlutusV2 with no withdraw half, so the empty string selects the non-CIP-113 branch
(§25.4). *"Unset" and "this credential family has no withdraw script" are different statements*, and
only the second is true — a key would let an operator express the first.

### 36.1 ⛔ The one derived hash that may legitimately disagree with the chain

Every other hash this registry derives is a function of the deployment alone, so a mismatch is a bug.
**This one is not.** The Minswap coordinates are **network-specific**: a node configured with mainnet's
while running elsewhere derives a real, well-formed hash **for the wrong deployment**. Measured, and
pinned in the test: preview's loans coordinates with mainnet's Minswap yield `52b778c8…` against a
published `aa3628d8…`.

**⇒ So field 5 is REPORTED, never a mismatch that stops the node.** Refusing to boot would be
disproportionate — it disables exactly one path and breaks none — and it is the state **every preview
node is in today**. The three outcomes are deliberately distinguishable in the log, because they send an
operator to completely different places:

```
CONVERT AVAILABLE:   derives X and the LMConfigDatum publishes X
CONVERT UNAVAILABLE: loans.minswap.* is not set
CONVERT UNAVAILABLE: derives X but this deployment publishes Y — the configured coordinates
                     belong to a DIFFERENT network's Minswap deployment
```

⚑ **"We have no coordinates" and "the coordinates are for another network" are different faults**, and a
single "unavailable" line would have merged them — the same defect shape as the quiet market that was
indistinguishable from a dead deployment (§12).

### 36.2 Verification

Six tests, and two are worth naming:
- **Every Minswap parameter must move the hash.** Three assertions, one per coordinate — a parameter
  that silently failed to reach the derivation would otherwise produce a hash that is right for the
  wrong reason, and only on a network where the others happened to match.
- **The fixture must actually contain the hash the test asserts.** Without that, the mainnet assertion
  compares a constant in the test file with a constant in the test file.

Partial coordinates yield **null**, not a hash: *a hash derived from two of three is a real-looking hash
for nothing.*

Suite **103 files, 868 tests, 38 failures, 22 skipped, 0 orphans**.

**Unstarted:** B2 proper — the CCL assembly, with the real evaluator from its first commit.

---

## 37. The convert builder — and three tests that passed for the wrong reason (2026-09-03)

Builder sub-stage B2: `ConvertTransactionBuilder`. The assembly — inputs, outputs, indexes, fees,
ex-units. Everything the validator dictates was already settled by `ConvertOrderPlan` (§35); what is
here is only what can be checked against a node.

### 37.1 ⛔ There is no constructor without a `TransactionEvaluator`

Not a convention — **there is no overload**, and the private constructor `requireNonNull`s it with a
message that names CCL trap 8. The sibling builders each grew a no-evaluator convenience, and that is
how 2026-08-21 shipped placeholder ex-units (10000 mem / 1000 steps against a real 352,041,926) which
pass the mempool and fail in **phase 2**.

**The stakes are specific to this path**: the operator's entire exposure is *"a transaction fee per
execution"*, and **that sentence is true only while the budgets are real.** Placeholders move the
exposure to the collateral, which is the one way the case for running this bot at a stated loss stops
holding. A reflective test asserts **every public constructor** takes an evaluator, so a future
convenience overload fails a test rather than a submission.

### 37.2 The two-pass, and why the indexes are observed rather than predicted

The convert redeemer names the two carrier outputs by **absolute** index into `self.outputs`, and so
does the loan-claim redeemer's `lenderBondOutputIndex`. This transaction carries withdrawals, so CCL
prepends a dummy output at the change address (trap 1) and appends change after ours. **Predicting
"my first `payToContract` is output 0" is off by exactly that dummy.**

So the body is assembled once with placeholders purely to read the finished layout, the carriers are
located **by their datum bytes** rather than by arithmetic, and the whole thing is assembled again with
the observed values — then `assertStructure` re-derives them from the finished body.

**Also enforced there, and nothing else would:**
- **The bot's fee.** `collateral − equity − swappable` is an unconstrained residue (§25.3): a builder
  that left it in the order or handed it to the lender produces a **perfectly valid transaction that
  pays the operator nothing.** No validator objects, and the economics gate cannot see it.
- The order's lovelace and collateral quantity, against the plan's figures.
- The lender bond's echo, byte-identical (CCL trap 4 — a decode→re-encode is not byte-stable, and
  `equals_data` compares bytes).

Scoped as accepted: **one loan per transaction**, and a non-zero borrower equity is a named
`EQUITY_NOT_MODELLED` refusal rather than a silent wrong build.

### 37.3 ⛔ THREE TESTS IN THIS FILE PASSED FOR A REASON OTHER THAN THE ONE THEY NAMED

All three were found by mutating; none by reading. **This is now six in three days.**

| test | why it passed | what it was supposed to prove |
|---|---|---|
| off-by-the-dummy-output | **the bond fixture reused the success datum**, so the wrong index accidentally held the right bytes | that a mispredicted carrier index is caught |
| null evaluator refused | **every other argument was null too**, so the registry's own `requireNonNull` threw first | that the *evaluator* guard fires |
| carrier hash check | the byte-equality check upstream always fired first | that an order embedding a hash its carrier does not produce is refused |

**⇒ Three distinct disarming mechanisms, one shape.**
- **A fixture that collides with the thing under test disarms the test silently.**
- **A test whose subject is the LAST check on a path must reach that check** — every other argument has
  to be valid, or an earlier guard answers for it.
- **A redundant-looking check needs the one case that separates it**, or a mutant deletes it for free.
  Here that case cannot arise from `ConvertOrderPlan` (it computes datum and hash from one object) but
  would arise the moment a future path took the hash from anywhere else — and the resulting transaction
  would have carriers Minswap ignores, with nothing on chain objecting.

### 37.4 Verification

Four mutants, each now killed by exactly one test: the fee check removed · the carrier-hash check
removed · the order's lovelace unchecked · a null evaluator permitted. **Two of those four survived
their first run** and are the two rows above; the third row was a plain red.

Suite **104 files, 879 tests, 38 failures, 22 skipped, 0 orphans**.

**Unstarted:** sub-stage C — the dry-eval against the §32 fixtures, which is where the ex-units stop
being an invariant and become a measurement. Then the operator docs.

---

## 38. ⛔ RETRACTED IN PART — "a convert can never be dry-evaluated offline" was an OVERCLAIM (2026-09-03)

> **⛔ RETRACTION, same day, on Giovanni's challenge. §40 carries the correction and the evidence;
> this section is left standing because the reasoning that produced the error is worth reading.**
>
> **What stands:** every convert requires the oracle withdrawal (§38.1's three steps are correct), and
> shadow-on-mainnet remains the in-situ final check.
>
> **What is WRONG:** §38.2's *"the offline rig structurally cannot fabricate an oracle leg"* and §38.4's
> *"not offline-provable"*. **The oracle feed, its validity window and its signature are all PUBLISHED
> by FluidTokens' own API — the one this node already consumes in production.** Nothing needs
> fabricating; it needs fetching.
>
> **The error, named:** I reasoned from *"no rig here does this"* to *"no rig can"*. The sibling oracle
> rigs are red because they **pinned a payload whose window expired** — stale data, which is a fixture
> problem, not a capability one. Giovanni said exactly that and he was right.

Sub-stage C was to be the offline dry-eval of the convert transaction against the §32 fixtures. **It is
blocked, structurally, and not by anything in the builder.** Reported rather than papered over.

### 38.1 The proof, in three steps from source

**① `retrieve_oracle_data` short-circuits only for ADA.** Read at `e0b818e`,
`lib/fluidtokens/oracle.ak`:

```
if expectedTokenPolicyId == "" {
    Some(Aggregated { …, token_price_in_lovelaces: 1, token_price_denominator: 1 })   // the 1:1 unit feed
} else {
    expect Some(oracleRedeemer) = pairs.get_first(redeemers, Withdraw(oraclePaymentCredential))
    …
}
```

**② `expectedTokenPolicyId` is the leg's own policy id.** `loan_claim_action.ak` passes
`datum.collateral.policyId` (and the principal's, for the other leg). ⚠ So the `NONE/NONE`
`oracleTokenAsset` does **not** bypass this — it only names which NFT must sit in the reference input.
**Any non-ADA leg requires an oracle withdrawal, full stop.**

**③ A convert always has a token leg.** `lm_liquidate_and_convert_action` requires a Minswap pool whose
`asset_a`/`asset_b` are the (collateral, principal) pair — **two distinct assets**. So at least one leg
is not ADA.

> **⇒ EVERY convert transaction requires the FluidTokens oracle validator to execute. There is no
> ada/ada convert, and therefore no oracle-free convert.**

### 38.2 Why that blocks the rig, and why it is pre-existing

Every **green** dry-eval in this project is ada/ada precisely to avoid the oracle leg — `LiquidateDryEvalTest`
says so in its own javadoc: *"an ada leg consults no oracle at all … That is the only leg shape this rig
can evaluate."*

The two rigs that **do** carry an oracle leg — `RealLoanDryEvalTest` and
`LiquidatePayInAdvanceDryEvalTest` — are **currently red**, and measured this session they fail in the
same place:

```
RedeemerError { tag: "Withdraw", index: 1, err: Machine(EvaluationFailure, …) }
```

That is the documented pinned-to-superseded-chain-state condition (`docs/tests-pinned-to-chain-state.md`,
8 of the 38 known failures). **Building convert's dry-eval on that foundation would inherit a broken
oracle leg**, and a rig that cannot evaluate its own sibling cannot prove anything about a new path.

⚑ Note what is *not* the blocker: the Minswap pool. A rig fabricates its own UTxOs, so a pool reference
input carrying the real mainnet `PoolDatum` and an `MSP` NFT is trivially supplied — the evaluator
resolves what the supplier gives it and neither knows nor cares that preview has no Minswap.

### 38.3 ⛔ RETRACTED — see §40. This subsection's central claim is FALSE.

Shadow-on-mainnet was already the recommended first posture (§29, §33). This upgrades it:

> ~~**Shadow-on-mainnet is not the best available rehearsal for convert. It is the ONLY thing that can
> ever evaluate one.**~~ ⛔ **FALSE — do not quote this.** An offline rig can fetch the same reference
> inputs; nothing about them is unfabricatable, because nothing needs fabricating. §40 carries the
> mechanism. Shadow remains the **in-situ final check** — the chain's own UTxOs and parameters at that
> instant — which is a different and still necessary thing.

And it makes the placeholder-ex-units detector of §33.2 load-bearing rather than defensive: **the shadow
dump is now the first and only place a convert's ex-units are ever measured**, so a dump that silently
reported placeholders would have been the whole proof, wrong.

### 38.4 What convert's proof actually consists of

| layer | proved by | state |
|---|---|---|
| Minswap byte shapes | live chain + upstream declarations (§34) | ✅ 10 tests |
| the validator-dictated plan | the live ADA/FLDT pool datum (§35) | ✅ 10 tests |
| the action hash | derives the published mainnet field 5 (§36) | ✅ 6 tests |
| assembly, indexes, echoes, the bot's fee | structural post-assert on a built body (§37) | ✅ 11 tests |
| **script execution + ex-units** | **shadow-on-mainnet only** | ⛔ not offline-provable |

**Two options, and the recommendation.**
- **(i) Un-red the oracle rigs first** — re-pin `RealLoanDryEvalTest` / `LiquidatePayInAdvanceDryEvalTest`
  to the fourth deployment with fresh oracle payloads, then build convert's dry-eval on top. It would
  fix 8 pre-existing failures as a side effect, and it is **unrelated maintenance that convert should
  not be gated on**.
- **(ii) Ship convert with its structural proof and make shadow-on-mainnet the evaluation step**, with
  the runbook requiring the operator to read the dump before arming. ⇐ **recommended**, because it is
  what a mainnet deploy actually needs, and (i) does not become false by waiting.

⚠ **A third option does not exist**, and it is worth writing down so nobody spends a day on it: there is
no fabricated fixture that makes a convert oracle-free, because the requirement comes from the *pair
being two assets*, which is what a convert IS.

---

## 39. Stage D, piece 3 — and the last mile nobody had scoped, again (2026-09-03)

Stage D is the executor wiring that makes convert reachable at all: without it
`ConvertTransactionBuilder` is correct, tested, and **dead code** (§38 report). Five pieces; this
records the first one landed and a discovery that changes the second.

### 39.1 The convert action's reference-script key

`loans.liquidation.reference-scripts.lm-liquidate-and-convert-action` — a ninth named slot beside the
eight that existed. **It is not optional in practice.** `lm_liquidate_and_convert_action` is a
different script from both `lm-liquidate-action` (the plain path's) and
`lm-liquidate-and-pay-in-advance-action`; left unpublished it travels inline, and a convert already
carries four validators plus a Minswap order. The same arithmetic put a pay-in-advance transaction at
**20,548 bytes against a 16,384 limit** with everything else already published.

**FluidTokens has already published it on mainnet** — `56840ffb…#0`, part of the verified 27-of-27 set
(§24). The key is what lets an operator point at it.

⚑ FAB-75 tracks replacing all nine named keys with **one coordinate list the chain resolves by
`referenceScriptHash`** — the shape the compound path already uses, where *a mislabelled coordinate is
not expressible*. This adds the one key convert needs now rather than blocking on that.

⚠ Also corrected in passing: the `lm-liquidate-and-pay-in-advance-action` key's javadoc called it
*"the convert path's own action validator"*, from when those two words meant the same thing in this
repo. They do not, and a reader configuring convert would have pointed at the wrong key.

### 39.2 ⛔ THE NODE DOES NOT INDEX MINSWAP POOLS — so piece 2 cannot read the local index

The convert plan needs the **live pool UTxO and its datum**, resolved by NFT at scan time. The obvious
implementation is a lookup in the node's own index. **It cannot be**: `TankUtxoStorage` keeps only
UTxOs at `LoansContractRegistry.indexedPaymentCredentials()`, which are derived from the pinned config
policy ids. **Minswap's pool credential is not among them, and a Minswap pool UTxO is therefore
discarded at write time with no trace it was ever offered** — `officina:yaci-store-index-scoping`'s
exact failure mode.

Two ways out, and the choice matters:
- **Index them.** Add the Minswap pool credential to the kept set. ⛔ That indexes *every Minswap V2
  pool on the network* into this node's storage, and would need a `sync-start` far enough back to
  capture pools created long ago. Disproportionate for a lookup of one UTxO per candidate.
- **Query the provider.** The pool address is stable per network, and
  `/addresses/{poolAddress}/utxos/{lpAssetUnit}` returns **exactly one** UTxO — measured this session
  against the live ADA/FLDT pool. Two facts make this cheap: the lp asset name is computable
  (§34, SHA3-256 twice), and the answer is one row.

**⇒ Query the provider**, with the pool address as configuration. It follows the precedent already set
by the oracle registry client, which reaches out rather than indexing.

⚠ **And this is the third instance of the same shape in this stage sequence** — B1.5 (the registry
could not derive the convert hash), §38 (no offline rig can evaluate a convert), and now the pool
lookup. Each was found by asking *"what will actually happen when this runs"* rather than by reading
the code, and each sat in the last mile between correct code and a working feature. **A feature is not
done when its code is correct; it is done when something calls it and an operator can observe the
result.**

**Remaining in Stage D:** routing on `MarketGate.actionFor()` · the pool resolver · `ConvertEconomics`
called with the built fee · a decision `variant` for convert.

---

## 40. ⇒ THE CORRECTION: convert IS offline dry-evaluable, and nothing needs forging (2026-09-03)

Giovanni challenged §38's *"never"* — *"it is a smart-contract tx, any TX evaluator can run it; the
question is what the oracle leg needs, not whether evaluation is possible."* He is right. This is the
mechanism, verified rather than reasoned.

### 40.1 The oracle validator has THREE branches, and only one needs a signature

Read at `e0b818e`, `validators/oracle.ak`'s `withdraw`:

| redeemer variant | what it requires | can we supply it? |
|---|---|---|
| `PriceDataCharlie` | a Charli3 provider **reference input** with an inline `OracleDatum`, its identifier NFT, window containment, and a price identity | ✅ **reads only — no signature at all** |
| `PriceDataOrcfax` | Orcfax pointer + price reference inputs, same shape | ✅ reads only |
| `_` (aggregated / multisig / dedicated) | `verify_ed25519_signature` over `serialise_data(redeemer.data)`, n-of-m against the validator's baked-in keys | ⚠ needs a signature — **which FluidTokens publishes** |

⚑ Note what the uniqueness line at the top does **not** do: `expect list.unique(sigs) == sigs` is
satisfied by an **empty** list. It is a duplicate guard, not a presence check — so the two
reference-input branches genuinely carry no signature requirement.

### 40.2 And for the REAL candidate the signature is handed to us

`https://api.fluidtokens.com/get-oracle-tokens` — the endpoint `FluidOracleClient` already consumes in
production — was fetched read-only this session. Its FLDT entry:

```
preferredOracle: multisig          supportedOracle keys: ["multisig"]      (no Charli3 entry on mainnet)
validFrom  1788463500334   validTo  1788466500334      (2026-09-03 19:25:00 .. 20:15:00 UTC — 50 minutes)
price      22265406 / 100000000  = 0.22265406 lovelace per FLDT unit
signatures 1 present, threshold 1
```

**⇒ The feed, its window and its signature are all published.** Nothing is forged. And the price is a
useful cross-check: **0.22265406** against the live pool's mid-price of **≈0.22280** — four significant
figures apart, which is what an oracle and a pool should look like.

⚠ *The loan datum's oracle asset is named `oracleFLDTC3`, and the "C3" reads as Charli3 — but the
registry says mainnet FLDT is served **multisig-only**. The asset NAME is not the provider.* That is
the same shape as §32: **a name is not evidence of what a thing is.**

### 40.3 Why the sibling rigs are red, and what the convert rig must do differently

**The feed window is fifty minutes.** `RealLoanDryEvalTest` and `LiquidatePayInAdvanceDryEvalTest`
**pinned a captured payload**, so they were correct for fifty minutes and have been wrong ever since.
That is the whole of their redness — a fixture problem, not a capability one.

**⇒ So the convert dry-eval must FETCH THE FEED AT RUN TIME** and set the transaction's validity
interval inside `validFrom..validTo`, gated and skipping without credentials like
`LoansConfigVerifierLiveTest`. **A pinned oracle payload is a test with a fifty-minute shelf life**,
and this repo has two of them already.

### 40.4 What this changes

- **Convert gets a PRE-DEPLOY offline proof.** Better than shadow-only: the transaction can be
  evaluated, with real ex-units, before anything is deployed anywhere.
- **Shadow-on-mainnet is still the in-situ final check** — real UTxOs, real protocol parameters, the
  chain's own state at that instant — but it is **no longer the only proof**, and §38.3's claim that it
  is must not be quoted.
- **§33.2's placeholder-ex-units detector stays load-bearing** for the shadow path; it simply is not
  the sole line of defence any more.

### 40.5 ⚑ The error worth keeping

**I reasoned from "no rig here does this" to "no rig can."** The absence in front of me was a *fixture*
limitation and I read it as a *structural* one — then wrote "never", which is the strongest possible
claim, off the weakest possible evidence.

**⇒ "No existing X does this" is not "X cannot".** It is the mirror of §34's lesson — *a sample proves
what it contains and cannot reach a variant nobody uses* — and both reduce to the same discipline:
**an absence is evidence about the observation, not about the world.** §34 got it right by going to a
second source; §38 got it wrong by not.

And the tell was in my own text: option (i) in §38.4 said the fix was *"fresh oracle payloads"* — which
concedes the thing "never" denies. **A conclusion contradicted by its own escape hatch is not a
conclusion.**
