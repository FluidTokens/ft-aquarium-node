# `mainnet-loan-datum-328e44f9.hex` / `mainnet-lender-bond-datum-328e44f9.hex` / `mainnet-minswap-pool-night-usdm.hex`

One **live mainnet** Lending v4 loan with NIGHT collateral and a USDM principal, its lender bond, and the NIGHT/USDM
Minswap V2 pool a convert of it would trade against. Used by `ConvertTokenToTokenFixture` /
`ConvertTokenToTokenRigTest` (FAB-223), the first convert rig with a TOKEN principal — so the first in which
`loan_claim_action` reads a second oracle leg.

## The fetch

`POST https://api.koios.rest/api/v1/utxo_info`, body
`{"_utxo_refs":["328e44f99600ee3ba60450054685a4400716112af4a92ed0c56c00724635de59#1","328e44f99600ee3ba60450054685a4400716112af4a92ed0c56c00724635de59#3","5d6a281cebf8d2c168bd446a7120f5ff3f46286ab96106709876c80d513631af#1"],"_extended":true}`,
read-only and keyless, on **2026-11-12T23:44:25Z**. Each element's `inline_datum.bytes` was copied verbatim into its
file, with no trailing newline. **Every output was unspent when fetched.**

## The files

| file | UTxO | block / time (UTC) | address | full value | sha256 of the file |
|---|---|---|---|---|---|
| `mainnet-loan-datum-328e44f9.hex` (LoanDatum, 266 bytes) | `328e44f99600ee3ba60450054685a4400716112af4a92ed0c56c00724635de59#1` | 14,141,945 / 2026-11-01 00:59:25 | `addr1zx6hv03unjmlrgsk0a60wm9kxhfy6yu2fnzul8v8vkwxvtfpexr32us7n8gzrgqt7d7ar570gw5vmyy9xsak7xqht9tsafqtg0` (payment `b5763e3c…`, the shipped registry's loan spend) | 3,000,000 lovelace + 4,000,000,000 NIGHT (`0691b2fecca1ac4f53cb6dfb00b7013e561d1f34403b957cbb5af1fa` + `4e49474854`) + 1 loan NFT (`20256f557cec14f61cc662468f7812a1e6531e628af257a5fc5dd0c5` + `04d78e6a50e4969ca8d8b164fe5796ce7a91ee85a5e0fab09bfaede6`) | `88a6e9eed1f9da7760f5d1cccdde0f7fc31dffd2174f339a4f1859df59acc233` |
| `mainnet-lender-bond-datum-328e44f9.hex` (LenderManagerDatum, 159 bytes) | `328e44f9…#3` | 14,141,945 / 2026-11-01 00:59:25 | `addr1z9n58a9kj3rtfcrxe7ufm23aq8hsgxmdyerfdzvnhexwczwldyrcklqdeynrlhty9wn6wehlr78jcmnmrnqnnt57mqqqs07924` (payment `6743f4b6…`, the lender-manager spend) | 1,969,670 lovelace + 1 bond NFT (`bcd713bb7858d4b08738bed90ee7068d8f9b38d02e0cae0b45ac7a9b` + the loan id) | `5dd5a438afb5d54db2fa3f2a27ea09093513a6ec2d406407b5a087f5cf6f22dc` |
| `mainnet-minswap-pool-night-usdm.hex` (Minswap V2 pool datum, 166 bytes) | `5d6a281cebf8d2c168bd446a7120f5ff3f46286ab96106709876c80d513631af#1` | 14,193,027 / 2026-11-12 23:27:20 | `addr1z84q0denmyep98ph3tmzwsmw0j7zau9ljmsqx6a4rvaau66j2c79gy9l76sdg0xwhd7r0c0kna0tycz4y5s6mlenh8pq777e2a` | 4,500,000 lovelace + 287,017,579,860 NIGHT + 13,212,989,659 USDM (`c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad` + `0014df105553444d`) + 1 pool NFT (`f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c` + `4d5350`) + 9,223,371,982,099,091,585 LP (`f5808c2c…` + `126bbf31110c7232ca88cfc1463ecbc7fe3256ea37876975d0dc42b8f156f6a0`) | `7548a3f85acf3ef1770b359771d0db929aa325831ed2086d36960373d617db03` |

## What the datums say

**The loan** (decoded by production's `LoanDatumConverter`):

| field | value |
|---|---|
| principal | 108,770,000 USDM |
| interest rate | 400 |
| lend date | 1,793,495,700,000 |
| repayment mode | PerpetualLoan, APY coefficient 28, max recasts 5 |
| installment period | 0 (never late) |
| liquidation mode | Liquidation, LTV 100/125, partial-liquidation penalty 100‰, `equityInPrincipalCurrency` false |
| repayment receipts | false |
| collateral | NIGHT |
| collateral oracle NFT | `26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26` + `6f7261636c654e69676874` ("oracleNight", NIGHT v2) |
| principal oracle NFT | `26e60b20…` + `6f7261636c655553444d4333` ("oracleUSDMC3", USDM v2) |

**The lender bond**: `shouldLiquidationConvertToPrincipal` true, `liquidationFeePerMille` 40, lender stake
`Some(Inline(VerificationKey df69078b…))`. Its datum survives a decode→re-encode round trip byte for byte, which the
convert's bond echo requires.

**The pool**: asset_a NIGHT, asset_b USDM, reserves 286,904,909,617 / 13,207,781,558, fees 30/30,
`allow_dynamic_fee` false. `ConvertTxEncoder.computeLpAssetName(NIGHT, USDM)` reproduces the recorded LP asset name
`126bbf31…f6a0`. The collateral is asset_a, so a convert of this loan swaps **a → b** — the direction no earlier
convert rig exercised (d832b78e swaps b → a).

## The config datums the scenario reuses

No new config file. The scenario reuses `mainnet-config-datum-2026-10-23.hex` and `mainnet-lm-config-datum-2026-10-26.hex`
at their live coordinates:

| file | UTxO | address | value |
|---|---|---|---|
| `mainnet-config-datum-2026-10-23.hex` | `75e459a0e97d3808031d97b8360308782f197bc778471d3dbc4aa51245886dc5#0` | `addr1wy34kvsypls3wlqrk8f5l67ywpzqcm425g3g48qmpcm4yqqy7cfve` | 4,249,660 lovelace + the config NFT |
| `mainnet-lm-config-datum-2026-10-26.hex` | `c0f0d8219ca1ab77e55163cc9c75858815793b38b2167cbee113bea034e6ef36#0` | `addr1w8ak4cszwdvtfg9kyugwh9gs9krl5ylkdm84tky5x6vufysp3u5d6` | 2,150,690 lovelace + the LM config NFT |

Both were re-read on 2026-11-12, **unspent, and with datum bytes equal to the committed files.**

## ⛔ The scenario is HYPOTHETICAL

The loan, the bond and the pool are recorded; the instant and the prices are not. The rig anchors at
**T0 = 1,794,530,000,000** (2026-11-13T00:33:20Z), after every recorded UTxO above, and both oracle feeds — NIGHT v2
and USDM v2 — are **signed at runtime by the test-oracle rig** (`TestOracleRig`: FluidTokens' oracle code
re-parameterised with keys generated in memory), **not by the oracle operator**. No price in the tests is a price
anyone published.

## Census

Re-run 2026-11-12T23:37Z: 243 UTxOs at the loan credential `b5763e3c…`. Of the token→token pairs, **8 NIGHT/USDM,
2 NIGHT/USDCx and 5 STRIKE/USDM — every one of them a convert bond at 40‰.**

## STRIKE/USDM (POOL_TOO_THIN)

Used by `ConvertTokenToTokenExecutorTest` (FAB-223-2): a recorded STRIKE→USDM convert candidate whose Minswap pool is
far too thin to return its debt, so the executor refuses it `POOL_TOO_THIN` before any build. One new file, the pool
datum; the loan and bond datums are the ones FAB-225-1 committed (`mainnet-strike-usdm-pay-in-advance.PROVENANCE.md`),
reused unchanged, and so are the two v1 oracle scripts (`mainnet-oracle-script-d97da4f3.hex`,
`mainnet-oracle-script-3a561e40.hex`).

### The fetch

`POST https://api.koios.rest/api/v1/utxo_info`, body
`{"_utxo_refs":["8554c4d188ee1349d75a0cf3d4b8421e055d84de186ee994abaf43c2bc5ee939#0","165d232288e590fae56e8565c6c191b9572916bf6032d2a60ef163c40c10b496#9","8a25925a77299042c98aa91f7e061dfd395d9d1dfe01b8b4d38a7add2230ed90#1"],"_extended":true}`,
read-only and keyless, on **2026-11-12T23:44:37Z**. The pool's `inline_datum.bytes` was copied verbatim into its
file, with no trailing newline. **Every output was unspent when fetched.**

### The files

| file | UTxO | block / time (UTC) | address | full value | sha256 of the file |
|---|---|---|---|---|---|
| `mainnet-loan-datum-8554c4d1-0.hex` (LoanDatum, 268 bytes; reused) | `8554c4d188ee1349d75a0cf3d4b8421e055d84de186ee994abaf43c2bc5ee939#0` | 14,182,796 / 2026-11-10 14:20:06 | `addr1zx6hv03unjmlrgsk0a60wm9kxhfy6yu2fnzul8v8vkwxvtfce6fgzplu6he2pqf5qych0lywt7eydcz7vw7p5n2nkhhs29nd6a` (payment `b5763e3c…`, the shipped registry's loan spend) | 3,000,000 lovelace + 2,458,146,804 STRIKE (`f13ac4d66b3ee19a6aa0f2a22298737bd907cc95121662fc971b5275` + `535452494b45`) + 1 loan NFT (`20256f55…` + `086c06fdfb0b700be5070c30a2bca96a60472304cda09e2e2f37cbbe`) | `60925095b31009742a1f46144fe7c5a65d1daa9740ec7b3b01dd36765dc88a88` |
| `mainnet-lender-bond-datum-165d2322-9.hex` (LenderManagerDatum, 159 bytes; reused) | `165d232288e590fae56e8565c6c191b9572916bf6032d2a60ef163c40c10b496#9` | 13,986,878 / 2026-09-25 15:08:44 | `addr1z9n58a9kj3rtfcrxe7ufm23aq8hsgxmdyerfdzvnhexwcz22vs8kxsfh3xy66ndw8yz4xh4j2xw3s0q449um7uqtcxtqkrt52s` (payment `6743f4b6…`, the lender-manager spend) | 1,969,670 lovelace + 1 bond NFT (`bcd713bb…` + the loan id) | `ff850ecd5a16693482ffd4bd4c06f7049b686719741bd02646d8c464846ddaf9` |
| `mainnet-minswap-pool-usdm-strike.hex` (Minswap V2 pool datum, 147 bytes) | `8a25925a77299042c98aa91f7e061dfd395d9d1dfe01b8b4d38a7add2230ed90#1` | 13,989,854 / 2026-09-26 08:00:51 | `addr1z84q0denmyep98ph3tmzwsmw0j7zau9ljmsqx6a4rvaau66j2c79gy9l76sdg0xwhd7r0c0kna0tycz4y5s6mlenh8pq777e2a` (the same pool address as NIGHT/USDM) | 4,500,000 lovelace + 7,606,291 USDM (`c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad` + `0014df105553444d`) + 6,471,257 STRIKE + 1 pool NFT (`f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c` + `4d5350`) + 9,223,372,036,854,775,807 LP (`f5808c2c…` + `a0b3b8315e895f4fd22b780c24e2c87e3345e67f7fa99b408274ed8f6a4aa20d`) | `07b737fb832ebaf446536b8da0e0be43d8d512a7c141d818cb5d5ef4bb6f6f4b` |

### What the datums say

**The loan**: principal 737,444,041 USDM, interest rate 757, lend date 1,790,349,901,000, PerpetualLoan (APY
coefficient 28, max recasts 5), Liquidation (LTV 100/125, partial-liquidation penalty 100‰,
`equityInPrincipalCurrency` false). Collateral STRIKE. Both oracles are v1: STRIKE
`93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b` + `6f7261636c65535452494b45` ("oracleSTRIKE", registry
index 7) and USDM `93794f9b…` + `6f7261636c655553444d4333` ("oracleUSDMC3", registry index 4), with distinct reward
addresses.

**The lender bond**: `shouldLiquidationConvertToPrincipal` true, `liquidationFeePerMille` 40. Its datum survives a
decode→re-encode round trip (measured).

**The pool**: asset_a USDM, asset_b STRIKE, reserves **23,210 / 25,066** base units, fees 125/100,
`allow_dynamic_fee` false. The collateral is asset_b, so a convert of this loan swaps **b → a** at fee_b 100.

### The figures, and why the refusal is time-independent

Measured with `LoanFinance` / `ConvertOrderPlan` at T0 = 1,794,530,000,000 and rig prices STRIKE
100,000,000 / 10⁸ and USDM 400,000,000 / 10⁸: remaining debt 748,021,564 USDM units, `canLiquidate` true, equity
**0**, liquidation fee 98,325,872 STRIKE units, swappable 2,359,820,932 STRIKE units. The pool quotes about
**23,209** USDM units for that, against a `minimum_receive` of the whole debt, so the plan refuses `POOL_TOO_THIN`.
With equity at 0 the swappable amount does not move with time, and neither does the quote: the refusal does not
depend on the instant chosen.

### ⛔ Hypothetical, as above

The loan, the bond and the pool are recorded; the instant (T0) and both prices are not. The STRIKE v1 and USDM v1
feeds are **signed at runtime by the test-oracle rig**, not by the oracle operator.

### Census

5 STRIKE/USDM loans; this pool's datum reserves are 23,210 / 25,066 base units.
