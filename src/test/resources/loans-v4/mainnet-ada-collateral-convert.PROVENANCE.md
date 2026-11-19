# `mainnet-loan-datum-2d067dae.hex` / `mainnet-lender-bond-datum-2d067dae.hex` / `mainnet-minswap-pool-ada-usdm.hex`

One **live mainnet** Lending v4 loan with ADA collateral and a USDM principal, its lender bond, and the ADA/USDM
Minswap V2 pool a convert of it would trade against. Used by `ConvertAdaCollateralFixture` /
`ConvertAdaCollateralRigTest` and `ConvertProductionWiringTest` (m) (FAB-224), the first convert rig with an ADA
collateral — so the first in which the collateral is an ada leg, the equity output holds ada only, and the build is
paid from the loan's own lovelace with no wallet input.

The loan and bond datum files were committed by FAB-226-1 (`mainnet-ada-collateral-pay-in-advance.PROVENANCE.md`)
and are **reused byte-identical**; FAB-224 adds only the pool datum and this file.

## The fetches

All read-only and keyless, against Koios; each element's `inline_datum.bytes` copied verbatim into its file, with no
trailing newline.

1. `POST https://api.koios.rest/api/v1/utxo_info`, body
   `{"_utxo_refs":["2d067daec3f38b9d87609e8db38afd003c41a25b1db46dcb9a1e611c85c1b75c#1","2d067daec3f38b9d87609e8db38afd003c41a25b1db46dcb9a1e611c85c1b75c#3"],"_extended":true}`,
   on **2026-11-13T02:14:54Z**: the loan and the bond.
2. `POST https://api.koios.rest/api/v1/asset_utxos`, body
   `{"_asset_list":[["f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c","7dd6988c5a86693c76aeec1ea94afa41770be0de21a775ca7a2a1eabdb6a0171"]],"_extended":true}`,
   on **2026-11-13T02:15:30Z**: the ADA/USDM pool, the one result carrying the Minswap pool NFT.
3. `POST https://api.koios.rest/api/v1/utxo_info` again, with the loan, the bond, the pool, the config and the LM
   config refs, on **2026-11-13T02:39:10Z**.

**The loan and the bond were unspent at both fetches (1 and 3).** The pool was unspent at its fetch (2) and had been
respent by a later swap by fetch 3, as every pool output is; the recorded pool UTxO is the one fetch 2 returned.

## The files

| file | UTxO | block / time (UTC) | address | full value | sha256 of the file |
|---|---|---|---|---|---|
| `mainnet-loan-datum-2d067dae.hex` (LoanDatum, 192 bytes; reused from FAB-226-1) | `2d067daec3f38b9d87609e8db38afd003c41a25b1db46dcb9a1e611c85c1b75c#1` | 14,029,073 / 2026-10-05 12:41:47 | `addr1zx6hv03unjmlrgsk0a60wm9kxhfy6yu2fnzul8v8vkwxvtfd3m9rcvrnrasmk5qw7wzn2u995ensxshafrmy666kdkqsmrd6hj` (payment `b5763e3c…`, the shipped registry's loan spend) | 8,100,000,000 lovelace + 1 loan NFT (`20256f557cec14f61cc662468f7812a1e6531e628af257a5fc5dd0c5` + `441301d34a34125e10f0ea9bd01c5f08dfe5b91d3c2c3101508430fd`, the loan id) | `6f47c03ac408b542588dc929a87a545f5e715ab360ba05d1a761680f3ace61f4` |
| `mainnet-lender-bond-datum-2d067dae.hex` (LenderManagerDatum, 159 bytes; reused from FAB-226-1) | `2d067dae…#3` | 14,029,073 / 2026-10-05 12:41:47 | `addr1z9n58a9kj3rtfcrxe7ufm23aq8hsgxmdyerfdzvnhexwczfg9cfhunxjkr3vjanr2tmgvkq0l0hlau7cggjmsm0knwws7g0e4k` (payment `6743f4b6…`, the lender-manager spend) | 1,969,670 lovelace + 1 bond NFT (`bcd713bb7858d4b08738bed90ee7068d8f9b38d02e0cae0b45ac7a9b` + the loan id) | `3e9097c65d794f72536346af5ef37d174c3b3d71aaca239ed0ffb68919489001` |
| `mainnet-minswap-pool-ada-usdm.hex` (Minswap V2 pool datum, 132 bytes) | `e2e08f2e28b118ad4cfdf06f94581652e51ba67749e59b94361e0ab04fb15459#1` | 14,193,483 / 2026-11-13 01:55:54 | `addr1z84q0denmyep98ph3tmzwsmw0j7zau9ljmsqx6a4rvaau66j2c79gy9l76sdg0xwhd7r0c0kna0tycz4y5s6mlenh8pq777e2a` | 2,020,169,734,229 lovelace + 351,296,019,357 USDM (`c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad` + `0014df105553444d`) + 1 pool NFT (`f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c` + `4d5350`) + 9,223,371,398,395,780,719 LP (`f5808c2c…` + `7dd6988c5a86693c76aeec1ea94afa41770be0de21a775ca7a2a1eabdb6a0171`) | `506782ccde9d6eb79225c7b5d24570d98103981bf9bbcb0f4767044791135fbb` |

## What the datums say

**The loan** (decoded by production's `LoanDatumConverter`):

| field | value |
|---|---|
| principal | 551,000,000 USDM |
| principal oracle NFT | `26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26` + `6f7261636c655553444d4333` ("oracleUSDMC3", USDM **v2**, registry index 21, deployed script `df6f292e…`) |
| interest rate | 400 |
| lend date | 1,791,205,500,000 |
| repayment mode | PerpetualLoan, APY coefficient 28, max recasts 5 |
| installment period | 0 (never late) |
| liquidation mode | Liquidation, LTV 100/125, partial-liquidation penalty 100‰, `equityInPrincipalCurrency` false |
| repayment receipts | false |
| origin id | `504f4f4c00d5e24dffa730339bcd86e82df630592353673a16283fad3d1e168459` |
| collateral | ADA: `CollateralAsset(policyId "", maybeAssetName Some(""), oracleTokenAsset ("", ""))` — no oracle leg |

**The collateral is the whole lovelace of the loan UTxO**: an ADA collateral is `quantity_of(value, "", "")`, so the
collateral is 8,100,000,000.

**The lender bond**: lenderAuth `CardanoSignature(e504fd97b5da0baa5dbf2eafd2c0c689f24d795e443858090d27fd6f)`, lender
stake `Some(Inline(VerificationKey 282e137e4cd2b0e2c9766352f686580ffbeffef3d84225b86df69b9d))`,
`shouldLiquidationConvertToPrincipal` true, `liquidationFeePerMille` 40, principal USDM. The CBOR integer `1828` (40)
occurs exactly once in the file, inside `d87a801828581d`.

**The pool**: asset_a ada, asset_b USDM, total liquidity 638,458,995,098, reserves 2,020,163,590,991 /
351,294,965,988, fees 75/75, fee sharing `Some(3666)`, `allow_dynamic_fee` false.
`ConvertTxEncoder.computeLpAssetName(ada, USDM)` reproduces the recorded LP asset name `7dd6988c…0171`. The collateral
is asset_a, so a convert of this loan swaps **a → b**.

## The config datums the scenario reuses

No new config file. The scenario reuses `mainnet-config-datum-2026-10-23.hex` and `mainnet-lm-config-datum-2026-10-26.hex`
at the coordinates, addresses and values `ConvertTokenToTokenFixture` uses
(`mainnet-token-to-token-convert.PROVENANCE.md`):

| file | UTxO | address | value |
|---|---|---|---|
| `mainnet-config-datum-2026-10-23.hex` | `75e459a0e97d3808031d97b8360308782f197bc778471d3dbc4aa51245886dc5#0` | `addr1wy34kvsypls3wlqrk8f5l67ywpzqcm425g3g48qmpcm4yqqy7cfve` | 4,249,660 lovelace + the config NFT |
| `mainnet-lm-config-datum-2026-10-26.hex` | `c0f0d8219ca1ab77e55163cc9c75858815793b38b2167cbee113bea034e6ef36#0` | `addr1w8ak4cszwdvtfg9kyugwh9gs9krl5ylkdm84tky5x6vufysp3u5d6` | 2,150,690 lovelace + the LM config NFT |

Both were re-read at fetch 3 (**2026-11-13T02:39:10Z**), **unspent, and with datum bytes equal to the committed
files.**

## ⛔ The scenario is HYPOTHETICAL

The loan, the bond and the pool are recorded; the instant and the price are not. The rig anchors at
**T0 = 1,794,540,000,000** (2026-11-13T03:20:00Z), after every recorded UTxO above, and the USDM v2 feed is **signed
at runtime by the test-oracle rig** (`TestOracleRig`: FluidTokens' oracle code re-parameterised with keys generated in
memory, control-hashed to the deployed v2 oracle `756897fd`, the same v2 code as `df6f292e`), **not by the oracle
operator**. No price in the tests is a price anyone published. An ADA collateral has no oracle leg, so there is no
second feed.

## ⛔ The SYNTHETIC bond variants

`ConvertAdaCollateralFixture` derives two bond datums that were **never recorded**, in code, by replacing the single
CBOR integer `1828` (`liquidationFeePerMille` 40) in the recorded bond hex with the CBOR encoding of another value:
**1‰** (`01`) and **0‰** (`00`). The fixture asserts the anchor matched exactly once and that the decoded variant equals
the recorded datum in every field but `liquidationFeePerMille`. No such bond exists on chain.
