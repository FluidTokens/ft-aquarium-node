# `src/main/resources/loans-v4.plutus.json` — FluidTokens' 2026-10-01 mainnet deployment

**sha256 `a638e71ca047b668f74eeeff1b80e76695626ddfe506a9ca1d2721e9a1e72704`**, assembled by
`assemble-2026-10-01.py` (this directory). It is **not** byte-for-byte any single upstream commit, and
that is deliberate.

| input | upstream `ft-cardano-loans-v4` commit | `plutus.json` sha256 |
|---|---|---|
| base (everything else) | `fec809eb795f90c7aac48610aa9b208057c85339` ("core fix for FTAI-001") | `51637332e424201d156cc812e5b7db7a2f1812d0e677c0da87b6cc22caecc743` |
| kept: `pool_manager/pm_cancel_pool_manager.poolManager.{withdraw,else}`, `pool_manager/pm_edit_pool.poolManager.{withdraw,else}` | `aad6c59` (= the artefact vendored before this change) | `ef1064fd0e7e1b8045a50d1d889388750ce8e23dc8402970be973b73a3a55d62` |

Both compiled by Aiken `v1.1.21+42babe5`, the compiler upstream's `aiken.toml` declares. Nothing was
rebuilt locally.

## Why the four pool-manager entries are kept

The 2026-09-18 upstream commits `a39a391` / `04b6e70` / `50025f5` ("unique NFT names") changed
`pm_cancel_pool_manager` and `pm_edit_pool`. **The chain does not run them**: the live ConfigDatum's
`poolManagerSpendScriptHash` [27] and `poolManagerPolicyId` [28] (`1e0bf58a…`) and the LMConfigDatum's
`lm_compound_action` [3] (`71515a89…`, parameterised by both) derive only from the pre-09-18
pool-manager code. FluidTokens (Raul Rosa, 2026-10-01 16:40, relayed by Giovanni): that commit
**"should not have been merged"** — the deployed pool-manager is the intended one.

⛔ **A plain re-vendor of a later upstream `plutus.json` will silently re-introduce those two
validators** and close the lending gate on [27]/[28]/LM[3]. `MainnetRedeploy20261001Test` (the exact set of validators
that moved, and the whole-file sha256) and `MainnetBlueprintSelectionTest` (`BLUEPRINT_SHA256`) pin it so
that cannot happen unnoticed.

## Evidence the assembly is the deployment

Every hash the registry derives from this file equals the live mainnet datums captured on
2026-10-01 (`../mainnet-config-datum-2026-10-01.hex`, `../mainnet-lm-config-datum-2026-10-01.hex`),
except ConfigDatum[14] (recast), which FluidTokens points at the unpublished pause hash
`64d9b13f…` — advisory, never used by the node.
