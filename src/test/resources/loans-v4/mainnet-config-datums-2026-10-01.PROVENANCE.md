# `mainnet-config-datum-2026-10-01.hex` / `mainnet-lm-config-datum-2026-10-01.hex`

The **live mainnet** Lending v4 config datums after FluidTokens' 2026-10-01 redeploy (security fixes
FTAI-001 / FTAI-002 / FTAI-102, upstream `ft-cardano-loans-v4` `fec809e`). Fetched from Koios
`POST /api/v1/asset_utxos` (`_extended: true`), inline datum bytes copied verbatim, on 2026-10-01.

| | ConfigDatum | LMConfigDatum |
|---|---|---|
| NFT | `235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200` + `706172616d6574657273` | `fb6ae2027358b4a0b62710eb95102d87fa13f66ecf55d8943699c492` + `706172616d6574657273` |
| UTxO | `3d800e98a4da21dc9abcce30c145729406fef7db4d5cd3b4ecd6813aa228a75c#0` | `ab3e3aafe7ea0e6fec24d9ac9249e01edb242dee55aeaa2399da097a21177620#0` |
| block / time | 14,012,337 / 2026-10-01 14:27:15Z | 14,012,458 / 2026-10-01 15:12:39Z |

FluidTokens' own frontend constants name the same two UTxOs (`CONFIG_REF_UTXO`, `LM_CONFIG_REF_UTXO`,
relayed by Giovanni 2026-10-01).

## What moved, against `mainnet-config-datum-2026-09-19.hex` / `mainnet-lm-config-datum.hex`

| field | 2026-09-19 | 2026-10-01 | |
|---|---|---|---|
| ConfigDatum[11] loan claim | `63b26ff9…` | `6a6f2aec…` | new code + two new parameters |
| ConfigDatum[14] loan recast | `c0af09a3…` | `64d9b13f…` | FluidTokens' unpublished pause hash — advisory |
| ConfigDatum[23] pool borrow | `d86664db…` | `b8f18700…` | new code |
| ConfigDatum[24] pool sell | `fc16ccaa…` | `efa39cdb…` | new code + one new parameter; action disabled by FluidTokens |
| LMConfigDatum[2] liquidate | `df30096d…` | `8f469373…` | parameterised by the claim |
| LMConfigDatum[3] compound | `2a8faf65…` | `71515a89…` | new code |
| LMConfigDatum[4] liquidate + pay in advance | `bec0ed6f…` | `0e000364…` | parameterised by the claim |
| LMConfigDatum[5] liquidate + convert | `2432ab45…` | `cbf3e8c5…` | parameterised by the claim |
| LMConfigDatum[6] liquidate + pay in advance + compound | `7159f08e…` | `cd33a2aa…` | parameterised by the claim |

ConfigDatum[13] (change collateral) is back to its pre-2026-09-30 value; every other field is unchanged.
The undated `mainnet-config-datum.hex` / `mainnet-lm-config-datum.hex` are earlier captures and stay
untouched as historical evidence.

## `mainnet-compound-script-2026-10-01.hex`

The reference script published at `29f63a1e1e7b268481df871d969b1b250b437a4d9a82aa7bfaf7b6f6252dc946#0`
(block 14,012,485): `lm_compound_action`, hash `71515a89…` (= LMConfigDatum[3]), PlutusV3, 5,197 bytes.
Copied from Koios `utxo_info` (`reference_script.bytes`) on 2026-10-01; byte-identical to Blockfrost
`/scripts/71515a89…/cbor` (checked independently by the slice-1 audit).
