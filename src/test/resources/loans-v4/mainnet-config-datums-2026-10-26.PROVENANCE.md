# `mainnet-config-datum-2026-10-23.hex` / `mainnet-lm-config-datum-2026-10-26.hex` / `mainnet-compound-script-2026-10-26.hex` / `mainnet-pia-compound-script-2026-10-26.hex`

The **live mainnet** Lending v4 config datums after FluidTokens rewrote both in place (2026-10-23 and
2026-10-26), and the two reference scripts they published for the moved LenderManager actions. Fetched read-only
from Koios `POST /api/v1/utxo_info` (`_extended: true`) on 2026-10-26: `inline_datum.bytes` and
`reference_script.bytes`, copied verbatim with no trailing newline. All four outputs were unspent when fetched.

| file | UTxO | block / time (UTC) | what |
|---|---|---|---|
| `mainnet-config-datum-2026-10-23.hex` | `75e459a0e97d3808031d97b8360308782f197bc778471d3dbc4aa51245886dc5#0` | 14,105,271 / 2026-10-23 10:45:06 | ConfigDatum, NFT `235b3204…` + `706172616d6574657273`; 30 fields |
| `mainnet-lm-config-datum-2026-10-26.hex` | `c0f0d8219ca1ab77e55163cc9c75858815793b38b2167cbee113bea034e6ef36#0` | 14,118,734 / 2026-10-26 13:40:34 | LMConfigDatum, NFT `fb6ae202…` + `706172616d6574657273`; 8 fields |
| `mainnet-compound-script-2026-10-26.hex` | `588695471b9f865ceac88066883fad118a5a1800c9c69a0663e5ff560d656b1a#0` | 14,117,824 / 2026-10-26 08:39:52 | `lm_compound_action`, PlutusV3, hash `4713a925…` (= LMConfigDatum[3]), 5,246 bytes |
| `mainnet-pia-compound-script-2026-10-26.hex` | `047020cf4fee781a16977e104891c148212fed893dd1f5b1366eff695722114a#0` | 14,117,826 / 2026-10-26 08:40:33 | `lm_liquidate_pay_in_advance_and_compound_action`, PlutusV3, hash `00e38efa…` (= LMConfigDatum[6]), 9,417 bytes |

The script files hold the script as the ledger stores it: one CBOR byte string around the flat-encoded
program (`59147b…`, `5924c6…`). That is what `PlutusScript.serializeScriptBody()` returns for the applied
script, and `MainnetRedeploy20261026Test` compares the two for equality.

## What moved, against `mainnet-config-datum-2026-10-01.hex` / `mainnet-lm-config-datum-2026-10-01.hex`

| field | 2026-10-01 | now | |
|---|---|---|---|
| ConfigDatum[24] pool sell | `efa39cdb…` | `f43354cb9a5fd138398869ca76cd9fefadfa87e60cb0f47081376a0f` | advisory |
| LMConfigDatum[3] compound | `71515a89…` | `4713a925302692076b686eee3de80963c94e464108522afacf19039c` | **enforced**: the 2026-10-01 artefact closes the lending gate here |
| LMConfigDatum[6] liquidate + pay in advance + compound | `cd33a2aa…` | `00e38efa912fa4e6906c9e8550c34ef15aaf7074973c1445642a0be4` | advisory; `047020cf…#0` has no consumer in `src/main` |

Every other byte of both datums is identical to the 2026-10-01 captures. The config NFTs did not change. Both new
LenderManager reward accounts (`4713a925…`, `00e38efa…`) were `registered` on Koios `account_info` on 2026-10-26.
The 2026-10-01 outputs `3d800e98…#0` and `ab3e3aaf…#0` are spent, and the superseded compound reference
`29f63a1e…#0` is still unspent.
