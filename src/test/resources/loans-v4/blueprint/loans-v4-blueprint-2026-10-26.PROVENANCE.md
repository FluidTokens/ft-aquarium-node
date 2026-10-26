# `src/main/resources/loans-v4.plutus.json` — FluidTokens' 2026-10-23 / 2026-10-26 mainnet datum rewrites

**sha256 `d2f12d046ee130f93c23ccb490012ace375a21d14fd5c13b824266c504b8ae2d`**, assembled by
`assemble-2026-10-26.py` (this directory). It is **not** byte-for-byte any single upstream commit, and
that is deliberate.

```
python3 assemble-2026-10-26.py <(git -C ft-cardano-loans-v4 show a02d4e2:plutus.json) \
    src/test/resources/loans-v4-2026-10-01.plutus.json src/main/resources/loans-v4.plutus.json \
    src/test/resources/loans-v4/blueprint/a02d4e2-not-deployed-entries.json
```

Re-running it reproduces the shipped file byte for byte.

| input | source | sha256 |
|---|---|---|
| base (everything else) | upstream `ft-cardano-loans-v4` `a02d4e2cb656f337a053ac5adb5029b02f246b00` `plutus.json` (merge of PR #21 "compound-min-fee") | `cf7236cf3a3e6b050bd8feb8a1ddcf6b7bda98794a636a7c0d803848279cf9cc` |
| kept: the eight entries below | the previously vendored artefact (the 2026-10-01 hybrid: upstream `fec809e` + pool manager from `aad6c59`), preserved byte for byte as `src/test/resources/loans-v4-2026-10-01.plutus.json` | `a638e71ca047b668f74eeeff1b80e76695626ddfe506a9ca1d2721e9a1e72704` |
| output (side): the eight a02d4e2 entries substituted out, verbatim | `a02d4e2-not-deployed-entries.json` | `5fc7214377265f1c9c1492eaa50b8c613c1374c72580658e0508af99921e11fb` |

Every input was compiled by Aiken `v1.1.21+42babe5` (each file's own `preamble.compiler`; upstream's
`aiken.toml` declares `v1.1.21`). Nothing was rebuilt locally. The `definitions` of the output are
identical to the 2026-10-01 artefact's, and every `$ref` of a kept entry resolves in them.

## Substitutions, each with its chain reason

| kept entry (`.withdraw` and `.else`) | a02d4e2 unapplied hash | kept unapplied hash | why the chain says keep it |
|---|---|---|---|
| `pool_manager/pm_cancel_pool_manager.poolManager` | `924baacf…` | (as 2026-10-01) | a02d4e2 still carries the 2026-09-18 "unique NFT names" change FluidTokens confirmed "should not have been merged". ConfigDatum[27]/[28] and LMConfigDatum[3] derive only from the kept code. |
| `pool_manager/pm_edit_pool.poolManager` | `60c381c3…` | (as 2026-10-01) | same |
| `loan/loan_repay_action.loan_repay_action` | `446f2947…` | `6b28dda7…` | a02d4e2 changed it (`13775b1` "fix finding 5"), but the live ConfigDatum[12] is still `41ac9c9f…`, which only the kept code derives. a02d4e2's derives `779a92a8032c77167b2d172c0c35aea18f6b47bf57667c696c769adb`. Upstream branch `qFT3-101` (another repay change) is not deployed either. |
| `loan/loan_recast_action.loan_recast_action` | `a6d58d76…` | `b487a7d8…` | a02d4e2 changed it (`a815065`, `30ca6bb`). The live [14] is FluidTokens' unpublished pause hash `64d9b13f…`, so the chain cannot arbitrate. The vendored one is kept to keep the change minimal; [14] is advisory and the node never invokes recast. |

## What moved against the 2026-10-01 artefact — exactly three validators

| validator | live field | 2026-10-01 → now |
|---|---|---|
| `lender_manager/lm_compound_action` (1 ADA minimum compounding fee for ADA principals, upstream `9a77c2c`) | LMConfigDatum[3] (**enforced**) | `71515a89…` → `4713a925302692076b686eee3de80963c94e464108522afacf19039c` |
| `lender_manager/lm_liquidate_pay_in_advance_and_compound_action` (its compounding fee, upstream `lib/fluidtokens/finance.ak`) | LMConfigDatum[6] (advisory) | `cd33a2aa…` → `00e38efa912fa4e6906c9e8550c34ef15aaf7074973c1445642a0be4` |
| `pool/pool_sell_lender_position.pool_sell_lender_position_action` (back to **two** parameters; upstream `13775b1`, `e22e43f`, `96240ad`) | ConfigDatum[24] (advisory) | `efa39cdb…` → `f43354cb9a5fd138398869ca76cd9fefadfa87e60cb0f47081376a0f` |

Every other derived hash is identical (the control group), and the payment credentials the node indexes
did not move, so no re-sync is needed.

## Evidence the assembly is the deployment

`MainnetRedeploy20261026Test`, against the live datums captured 2026-10-26
(`../mainnet-config-datum-2026-10-23.hex`, `../mainnet-lm-config-datum-2026-10-26.hex`, see
`../mainnet-config-datums-2026-10-26.PROVENANCE.md`):

- every derivable field equals the live one. The exceptions are ConfigDatum[0] (configured, not derived),
  [7] (not derived) and [14] (FluidTokens' recast pause), the same three as on 2026-10-01;
- the real `LoansConfigVerifier`, served those datums through a stubbed Blockfrost, leaves the lending
  gate open: enforced = ∅, advisory = ConfigDatum[14] only. The 2026-10-01 artefact closes it on
  LMConfigDatum[3];
- the applied `lm_compound_action` and `lm_liquidate_pay_in_advance_and_compound_action` serialise to
  exactly the on-chain reference-script bytes at `588695…#0` and `047020cf…#0`;
- the entries substituted out do not derive the chain (a02d4e2's repay misses [12], and its pool manager
  misses [27], [28] and LM[3]).

⛔ **A plain re-vendor of a02d4e2 (or any later upstream `plutus.json`) re-introduces the 2026-09-18 pool
manager and the undeployed repay, and closes the lending gate.** `MainnetRedeploy20261026Test` (whole-file
sha256 and the exact set of validators that moved) and `MainnetBlueprintSelectionTest` (`BLUEPRINT_SHA256`)
pin the file, so that cannot happen unnoticed.
