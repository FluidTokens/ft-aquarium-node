# `mainnet-loan-datum-8554c4d1-<n>.hex` / `mainnet-lender-bond-datum-165d2322-<n>.hex` / `mainnet-oracle-script-d97da4f3.hex` / `mainnet-oracle-script-3a561e40.hex`

Three **live mainnet** Lending v4 loans with STRIKE collateral and a USDM principal, their three lender bonds, and
the two deployed oracle scripts their datums name (STRIKE v1, USDM v1). Fetched read-only and keyless from Koios on
2026-11-12, copied verbatim with no trailing newline; every loan and bond output was unspent when fetched. Used by
`StrikeUsdmFixture` / `PayInAdvanceStrikeUsdmDryEvalTest` (FAB-225).

| file | UTxO / coordinate | block / time (UTC) | recorded value |
|---|---|---|---|
| `mainnet-loan-datum-8554c4d1-0.hex` | `8554c4d188ee1349d75a0cf3d4b8421e055d84de186ee994abaf43c2bc5ee939#0` | 14,182,796 / 2026-11-10 14:20:06 | LoanDatum, 268 bytes; UTxO 3,000,000 lovelace + loan NFT `20256f55…` + `086c06fd…` + 2,458,146,804 STRIKE |
| `mainnet-loan-datum-8554c4d1-1.hex` | `8554c4d1…#1` | 14,182,796 / 2026-11-10 14:20:06 | LoanDatum, 268 bytes; UTxO 3,000,000 lovelace + loan NFT `20256f55…` + `eff57e8c…` + 6,388,132,400 STRIKE |
| `mainnet-loan-datum-8554c4d1-2.hex` | `8554c4d1…#2` | 14,182,796 / 2026-11-10 14:20:06 | LoanDatum, 268 bytes; UTxO 3,000,000 lovelace + loan NFT `20256f55…` + `c979fc8a…` + 1,153,720,796 STRIKE |
| `mainnet-lender-bond-datum-165d2322-9.hex` | `165d232288e590fae56e8565c6c191b9572916bf6032d2a60ef163c40c10b496#9` | 13,986,878 / 2026-09-25 15:08:44 | LenderManagerDatum, 159 bytes; UTxO 1,969,670 lovelace + bond NFT `bcd713bb…` + `086c06fd…` (loan #0) |
| `mainnet-lender-bond-datum-165d2322-10.hex` | `165d2322…#10` | 13,986,878 / 2026-09-25 15:08:44 | LenderManagerDatum, 159 bytes; UTxO 1,969,670 lovelace + bond NFT `bcd713bb…` + `eff57e8c…` (loan #1) |
| `mainnet-lender-bond-datum-165d2322-11.hex` | `165d2322…#11` | 13,986,878 / 2026-09-25 15:08:44 | LenderManagerDatum, 159 bytes; UTxO 1,969,670 lovelace + bond NFT `bcd713bb…` + `c979fc8a…` (loan #2) |
| `mainnet-oracle-script-d97da4f3.hex` | reference script at `05958abc849fae4ae9f4c85be494061c04ca55597f058526ddbec0bc2aed2593#0` | not recorded (fetched by hash) | STRIKE v1 oracle, PlutusV3, hash `d97da4f3f2c3757971332b9bfb3c46df18715b059e6ab0fe66ddb036`; 4,107 bytes as stored (CBOR byte-string header `591008`, a 4,104-byte program) |
| `mainnet-oracle-script-3a561e40.hex` | reference script at `2d557048a3750f00549641048cd82081a8f033479c5d0454886b224675d2c975#1` | not recorded (fetched by hash) | USDM v1 oracle, PlutusV3, hash `3a561e401144af4f5dfeeb28ad7fe16b33e656ad5099a93e434928a4`; 4,113 bytes as stored (header `59100e`, a 4,110-byte program) |

Loan address (all three): `addr1zx6hv03unjmlrgsk0a60wm9kxhfy6yu2fnzul8v8vkwxvtfce6fgzplu6he2pqf5qych0lywt7eydcz7vw7p5n2nkhhs29nd6a`,
payment credential `b5763e3c…`, the shipped registry's loan spend. Bond addresses, payment credential `6743f4b6…` (the
lender-manager spend): #9 `addr1z9n58a9kj3rtfcrxe7ufm23aq8hsgxmdyerfdzvnhexwcz22vs8kxsfh3xy66ndw8yz4xh4j2xw3s0q449um7uqtcxtqkrt52s`,
#10 `addr1z9n58a9kj3rtfcrxe7ufm23aq8hsgxmdyerfdzvnhexwczff488uagw68fv50kjxv3wrx38829tay6zszthnccsradgqvya8cr`,
#11 `addr1z9n58a9kj3rtfcrxe7ufm23aq8hsgxmdyerfdzvnhexwcz2jeyv3rqufwy3qpnhlcz44nzfrf2dagzv54lfsqx3du86q9qvfuc`.
Full loan ids: `086c06fdfb0b700be5070c30a2bca96a60472304cda09e2e2f37cbbe`, `eff57e8c18494ecc8b287b8dd4ed019284d6cbbfda44d425a6328137`,
`c979fc8a04e68835a70c07f6bee0f6d959e55be04c728c41491c44fd`.

**The loan UTxOs are recent, the loans are not.** The three loan outputs were re-created on 2026-11-10 (block
14,182,796), but every datum carries the lend date 1,790,349,901,000 (2026-09-25 15:25:01Z), and the lender bonds are
the outputs of 2026-09-25 (block 13,986,878). Each bond is resolved by asset — lender-bond policy `bcd713bb…` with the
loan id as asset name — not by transaction.

## The three Koios calls

- `POST https://api.koios.rest/api/v1/utxo_info` `{"_utxo_refs":["8554c4d1…939#0","…#1","…#2"],"_extended":true}` →
  each element's `inline_datum.bytes` → `mainnet-loan-datum-8554c4d1-<tx_index>.hex`;
- `POST https://api.koios.rest/api/v1/asset_utxos`
  `{"_asset_list":[["bcd713bb7858d4b08738bed90ee7068d8f9b38d02e0cae0b45ac7a9b","<loan id>"],…×3],"_extended":true}` →
  `inline_datum.bytes` → `mainnet-lender-bond-datum-165d2322-<tx_index>.hex`;
- `POST https://api.koios.rest/api/v1/script_info` `{"_script_hashes":["d97da4f3…b036","3a561e40…28a4"]}` → `bytes` →
  `mainnet-oracle-script-<hash8>.hex`.

## What the datums say (decoded by production's converters)

| field | all three loans |
|---|---|
| principal | USDM `c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad` + `0014df105553444d` |
| principal oracle NFT | `93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b` + `6f7261636c655553444d4333` ("oracleUSDMC3") |
| collateral | STRIKE `f13ac4d66b3ee19a6aa0f2a22298737bd907cc95121662fc971b5275` + `535452494b45` |
| collateral oracle NFT | `93794f9b…` + `6f7261636c65535452494b45` ("oracleSTRIKE") |
| lend date | 1,790,349,901,000 |
| installment period | 0 (never late) |
| repayment mode | PerpetualLoan, APY coefficient 28, max recasts 5 |
| liquidation mode | Liquidation, LTV 100/125, partial-liquidation penalty 100‰, `equityInPrincipalCurrency` false |
| repayment receipts | false |

| | loan #0 | loan #1 | loan #2 |
|---|---|---|---|
| principal amount | 737,444,041 | 1,916,439,720 | 346,116,239 |
| interest rate | 757 | 400 | 400 |

All three bonds: `shouldLiquidationConvertToPrincipal` true, `liquidationFeePerMille` 40, principal USDM — so the
liquidation is the pay-in-advance action, with the lender paid in USDM.

## The oracles

Both NFTs resolve, in `mainnet-oracle-registry-2026-10-01.json`, to oracle-version-1 multisig entries:

| entry | token | reward credential | referenceInput | referenceScript | keys / threshold |
|---|---|---|---|---|---|
| 7 | STRIKE | `d97da4f3…` | `5f6bbacd3da81e917812049192fffba44e0c6b2bbd397f8c2a296d7afbee9d6b#2` | `05958abc…#0` | `[CB1506C8…E914]` / 1 |
| 4 | USDM | `3a561e40…` | `5f6bbacd…#0` | `2d557048…#1` | `[CB1506C8…E914]` / 1 |

All four oracle UTxOs were unspent at these coordinates on 2026-11-12.

**Each script is FluidTokens' oracle code applied to its own eight parameters, from `aquarium-sc.plutus.json`.**
Peeling the eight outer applications off each recorded script and re-applying those parameters to
`aquarium-sc.plutus.json`'s `oracle.oracle.withdraw` reproduces the deployed hash exactly; re-applying them to
`loans-v4.plutus.json`'s gives `b1cad684…` (STRIKE) and `262dbcc7…` (USDM), both wrong. The parameters: keys
[`cb1506…e914`], threshold 1, the token's policy and name, governance `93794f9b…` / "OracleGovernance".
`TestOracleRig.controlHashed` re-checks this on every build.

## The config reference inputs (reused, not re-recorded)

The existing fixtures, at their live coordinates; both unspent and byte-identical to Koios on 2026-11-12
(`mainnet-config-datums-2026-10-26.PROVENANCE.md`):

- `mainnet-config-datum-2026-10-23.hex` at `75e459a0e97d3808031d97b8360308782f197bc778471d3dbc4aa51245886dc5#0`,
  `addr1wy34kvsypls3wlqrk8f5l67ywpzqcm425g3g48qmpcm4yqqy7cfve`, 4,249,660 lovelace + NFT `235b3204…` + `706172616d6574657273`;
- `mainnet-lm-config-datum-2026-10-26.hex` at `c0f0d8219ca1ab77e55163cc9c75858815793b38b2167cbee113bea034e6ef36#0`,
  `addr1w8ak4cszwdvtfg9kyugwh9gs9krl5ylkdm84tky5x6vufysp3u5d6`, 2,150,690 lovelace + NFT `fb6ae202…` + `706172616d6574657273`.

## Why the prices are not recorded

No recorded signed feed makes these loans liquidatable (at the 2026-10-01 STRIKE price of 233,067,799 / 10⁸ loan #0's
LTV is under 100/125), and a signed feed cannot be edited, so both prices come from the test oracle rig
(`TestOracleRig`): the code above, re-parameterised with runtime keys.
