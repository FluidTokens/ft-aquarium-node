#!/usr/bin/env python3
"""Assemble the vendored Lending v4 blueprint for FluidTokens' 2026-10-23/26 mainnet datum rewrites.

Reproducible recipe (see loans-v4-blueprint-2026-10-26.PROVENANCE.md):
  base   = upstream ft-cardano-loans-v4 @ a02d4e2cb656f337a053ac5adb5029b02f246b00  plutus.json
  keep   = the eight entries below from the previously vendored artefact (the 2026-10-01 hybrid,
           src/test/resources/loans-v4-2026-10-01.plutus.json), because the chain runs those:
           - the four pool-manager entries: a02d4e2 still carries the 2026-09-18 "unique NFT names"
             change FluidTokens confirmed "should not have been merged" (ConfigDatum[27]/[28] and
             LMConfigDatum[3] derive only from the kept code);
           - loan_repay_action / loan_recast_action: a02d4e2 changed both, but the live ConfigDatum[12]
             is still the repay a02d4e2 does NOT derive, and [14] is FluidTokens' recast pause hash.
Usage: assemble-2026-10-26.py <a02d4e2 plutus.json> <loans-v4-2026-10-01.plutus.json> <out> [<rejected>]
  <rejected>, if given, receives the eight a02d4e2 entries that were substituted OUT, verbatim, as a JSON
  object keyed by title -- the evidence MainnetRedeploy20261026Test uses to show they do not derive the chain.
"""
import json, sys

KEEP_FROM_BASE = [
    "pool_manager/pm_cancel_pool_manager.poolManager.withdraw",
    "pool_manager/pm_cancel_pool_manager.poolManager.else",
    "pool_manager/pm_edit_pool.poolManager.withdraw",
    "pool_manager/pm_edit_pool.poolManager.else",
    "loan/loan_repay_action.loan_repay_action.withdraw",
    "loan/loan_repay_action.loan_repay_action.else",
    "loan/loan_recast_action.loan_recast_action.withdraw",
    "loan/loan_recast_action.loan_recast_action.else",
]

new = json.load(open(sys.argv[1]))
old = {v["title"]: v for v in json.load(open(sys.argv[2]))["validators"]}
swapped = 0
rejected = {}
for i, v in enumerate(new["validators"]):
    if v["title"] in KEEP_FROM_BASE:
        assert v["hash"] != old[v["title"]]["hash"], ("nothing to keep", v["title"])
        rejected[v["title"]] = v
        new["validators"][i] = old[v["title"]]
        swapped += 1
assert swapped == len(KEEP_FROM_BASE), swapped

def refs(x):
    if isinstance(x, dict):
        for k, val in x.items():
            if k == "$ref":
                yield val
            else:
                yield from refs(val)
    elif isinstance(x, list):
        for val in x:
            yield from refs(val)

for title in KEEP_FROM_BASE:
    for r in refs(old[title]):
        key = r.replace("#/definitions/", "").replace("~1", "/")
        assert key in new["definitions"], (title, r)

with open(sys.argv[3], "w") as f:
    f.write(json.dumps(new, indent=2, ensure_ascii=False))  # aiken's own layout, no trailing newline
if len(sys.argv) > 4:
    with open(sys.argv[4], "w") as f:
        f.write(json.dumps(rejected, indent=2, ensure_ascii=False))
