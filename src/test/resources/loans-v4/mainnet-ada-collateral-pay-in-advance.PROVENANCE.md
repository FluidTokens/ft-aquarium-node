# `mainnet-loan-datum-2d067dae.hex` / `mainnet-lender-bond-datum-2d067dae.hex` / `mainnet-oracle-script-df6f292e.hex`

One **live mainnet** Lending v4 loan with ADA collateral and a USDM principal, its lender bond, and the deployed
oracle script its datum names for the principal (USDM v2). Fetched read-only and keyless from Koios on 2026-11-13
(~02:40Z), copied verbatim with no trailing newline; the loan and bond outputs were unspent when fetched. Used by
`AdaCollateralUsdmFixture` / `PayInAdvanceAdaCollateralDryEvalTest` (FAB-226-1, which recorded all three files).

| file | UTxO / coordinate | block / time (UTC) | recorded value |
|---|---|---|---|
| `mainnet-loan-datum-2d067dae.hex` | `2d067daec3f38b9d87609e8db38afd003c41a25b1db46dcb9a1e611c85c1b75c#1` | 14,029,073 / 2026-10-05 12:41:47 | LoanDatum, 192 bytes; UTxO exactly 8,100,000,000 lovelace + loan NFT `20256f557cec14f61cc662468f7812a1e6531e628af257a5fc5dd0c5` + `441301d34a34125e10f0ea9bd01c5f08dfe5b91d3c2c3101508430fd` |
| `mainnet-lender-bond-datum-2d067dae.hex` | `2d067dae…#3` (same transaction) | 14,029,073 / 2026-10-05 12:41:47 | LenderManagerDatum, 159 bytes; UTxO 1,969,670 lovelace + bond NFT `bcd713bb7858d4b08738bed90ee7068d8f9b38d02e0cae0b45ac7a9b` + `441301d3…` |
| `mainnet-oracle-script-df6f292e.hex` | reference script at `ac42d186c3d43b02552afe950c0fd924b5220067b85fe46800e63695c3932de4#1` (its creation transaction) | not recorded (fetched by hash) | USDM v2 oracle, PlutusV3, hash `df6f292ece90837f738ef156f8940e5f98db2027b3afa8d2041c492d`; 4,131 bytes as stored (CBOR byte-string header `591020`, a 4,128-byte program) |

Loan address: `addr1zx6hv03unjmlrgsk0a60wm9kxhfy6yu2fnzul8v8vkwxvtfd3m9rcvrnrasmk5qw7wzn2u995ensxshafrmy666kdkqsmrd6hj`,
payment credential `b5763e3c…`, the shipped registry's loan spend. Bond address:
`addr1z9n58a9kj3rtfcrxe7ufm23aq8hsgxmdyerfdzvnhexwczfg9cfhunxjkr3vjanr2tmgvkq0l0hlau7cggjmsm0knwws7g0e4k`, payment
credential `6743f4b6…`, the lender-manager spend. The loan and the bond share the deployment of the config reference
inputs below (same loan-spend and lender-manager credentials). The bond is resolved by asset — lender-bond policy
`bcd713bb…` with the loan id as asset name.

**The collateral is the whole lovelace of the loan UTxO.** There is no separate min-ada: an ADA collateral is
`quantity_of(value, "", "")`, i.e. every lovelace the output holds, so production's `LoanService` reads a collateral
of 8,100,000,000.

## The three Koios calls

- `POST https://api.koios.rest/api/v1/utxo_info`
  `{"_utxo_refs":["2d067daec3f38b9d87609e8db38afd003c41a25b1db46dcb9a1e611c85c1b75c#1"],"_extended":true}` →
  `inline_datum.bytes` → `mainnet-loan-datum-2d067dae.hex`;
- `POST https://api.koios.rest/api/v1/asset_utxos`
  `{"_asset_list":[["bcd713bb7858d4b08738bed90ee7068d8f9b38d02e0cae0b45ac7a9b","441301d34a34125e10f0ea9bd01c5f08dfe5b91d3c2c3101508430fd"]],"_extended":true}` →
  `inline_datum.bytes` → `mainnet-lender-bond-datum-2d067dae.hex`;
- `POST https://api.koios.rest/api/v1/script_info` `{"_script_hashes":["df6f292ece90837f738ef156f8940e5f98db2027b3afa8d2041c492d"]}` →
  `bytes` → `mainnet-oracle-script-df6f292e.hex`.

## What the datums say (decoded by production's converters)

| field | the loan |
|---|---|
| principal | 551,000,000 USDM (`c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad` + `0014df105553444d`) |
| principal oracle NFT | `26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26` + `6f7261636c655553444d4333` ("oracleUSDMC3", USDM **v2**) |
| collateral | ADA: policy id `""`, asset name `Some("")`, oracle token decoded as ada — no oracle leg |
| interest rate | 400 |
| lend date | 1,791,205,500,000 |
| repayment mode | PerpetualLoan, APY coefficient 28, max recasts 5 |
| liquidation mode | Liquidation, LTV 100/125, partial-liquidation penalty 100‰, `equityInPrincipalCurrency` false |
| repayment receipts | false |

The collateral decodes as ada (`isAda()`), with an asset name present, so the loan is bot-liquidatable. The bond:
`shouldLiquidationConvertToPrincipal` true, `liquidationFeePerMille` 40, principal USDM — so the liquidation is the
pay-in-advance action, with the lender paid in USDM and the bot taking the ADA.

## The oracle

The principal NFT resolves, in `mainnet-oracle-registry-2026-10-01.json`, to entry 21: USDM, oracle version 2,
multisig, reward address `stake1780k72fwe6ggxlmn3mc4d7y5pe0e3keqy7e6l2xjqswyjtgqtk3fg` (credential `df6f292e…`),
keys [`CB1506C8…E914`], 1 required signature, recorded price 400,610,982 / 10⁸ over
[1,790,839,501,224, 1,790,842,501,224]. Its two live coordinates, both unspent on 2026-11-13:

- referenceInput `4e4b5381376bae774459510b16b3cf4b52cf7437addf88d5dd24d167080d9dcd#2` (1,500,000 lovelace + the NFT);
- referenceScript `ac42d186c3d43b02552afe950c0fd924b5220067b85fe46800e63695c3932de4#1`.

The collateral needs no oracle: `lib/fluidtokens/oracle.ak`'s `retrieve_oracle_data` returns the synthesised 1:1
feed for the empty policy id without reading any reference input or redeemer.

**The script is FluidTokens' oracle code applied to its own eight parameters, from `loans-v4.plutus.json`.** Peeling
the eight outer applications off the recorded script and re-applying those parameters to `loans-v4.plutus.json`'s
`oracle.oracle.withdraw` reproduces `df6f292e…` exactly; re-applying them to `aquarium-sc.plutus.json`'s does not.
The parameters: keys [`cb1506…e914`], threshold 1, the token's policy and name, governance `26e60b20…` /
"OracleGovernance". `TestOracleRig.controlHashed` re-checks this on every build.

## The config reference inputs (reused, not re-recorded)

The same two fixtures `mainnet-strike-usdm-pay-in-advance.PROVENANCE.md` uses, at their live coordinates
(`mainnet-config-datums-2026-10-26.PROVENANCE.md`):

- `mainnet-config-datum-2026-10-23.hex` at `75e459a0e97d3808031d97b8360308782f197bc778471d3dbc4aa51245886dc5#0`,
  4,249,660 lovelace + NFT `235b3204…` + `706172616d6574657273`;
- `mainnet-lm-config-datum-2026-10-26.hex` at `c0f0d8219ca1ab77e55163cc9c75858815793b38b2167cbee113bea034e6ef36#0`,
  2,150,690 lovelace + NFT `fb6ae202…` + `706172616d6574657273`.

## Why the price is not recorded

No recorded signed USDM feed makes this loan liquidatable (its LTV is about 0.27 at the recorded 400,610,982 / 10⁸),
and a signed feed cannot be edited, so the USDM price comes from the test oracle rig (`TestOracleRig`): the code
above, re-parameterised with runtime keys.
