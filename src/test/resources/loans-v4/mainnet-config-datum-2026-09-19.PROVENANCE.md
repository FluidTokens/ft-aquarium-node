# `mainnet-config-datum-2026-09-19.hex`

The **live mainnet ConfigDatum** as FluidTokens left it after an in-place update, fetched from
Koios on 2026-09-19.

- config NFT `235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200` + `706172616d6574657273`
- UTxO `454814391c3a399c3dbdffb208fee60442f12890dc2dd002b1b53561cecb5eba#0`, block **13961069**
- the previous UTxO, `bad663cca0de…#0` (block 13947723), is **spent**

## Why it is pinned here

This is the datum that grounded every operator's node. Against the artefact we ship it differs at
exactly three fields, all pool-side:

| field | ours (derived) | chain |
| --- | --- | --- |
| ConfigDatum[23] `pool_borrow_action` | `6ee66a5e…` | `d86664db…` |
| ConfigDatum[24] `pool_sell_lender_position_action` | `6e71cf5e…` | `fc16ccaa…` |
| ConfigDatum[26] `pool_edit_action` | `67ad051d…` | `1ec18533…` |

Same length, same field count — nothing inserted or removed. The **LMConfigDatum is byte-identical**
and still at `bbb8c37e…#1`, so the liquidation half is untouched.

## ⚠ Why we did NOT simply vendor the newer upstream artefact

Upstream `a8bb3f4` ("built done", on two commits titled *fix on pool nft safety*) changes **five**
validators, not three — also `pm_cancel_pool_manager` and `pm_edit_pool`. Those two are
**parameters to `pool_manager.poolManager`**, so adopting that artefact fixes 23/24/26 and breaks
ConfigDatum[27], [28] and LMConfigDatum[3], [6], which match today. Measured, not assumed: same
compiler (`v1.1.21` in both `aiken.toml`s), every arity unchanged, 68 of 78 validators identical.

**Mainnet is currently a mix of two upstream builds, and no single artefact derives it.** Their
deployment was still in flight when this was captured: the config names
`pool_sell_lender_position` `fc16ccaa…`, which **did not exist on chain in any form**.

⇒ So this fixture is not a migration target. It is the evidence for the severity split, and the
regression test that a pool-side redeploy we cannot follow does not stop the node running.
