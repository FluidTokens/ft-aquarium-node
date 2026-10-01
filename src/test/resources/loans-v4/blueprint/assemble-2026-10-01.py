#!/usr/bin/env python3
"""Assemble the vendored Lending v4 blueprint for FluidTokens' 2026-10-01 mainnet deployment.

Reproducible recipe (see loans-v4-blueprint-2026-10-01.PROVENANCE.md):
  base   = upstream ft-cardano-loans-v4 @ fec809eb795f90c7aac48610aa9b208057c85339  plutus.json
  keep   = the four pool-manager entries below from upstream @ aad6c59 (the previously vendored artefact),
           because the chain runs those: FluidTokens (Raul, 2026-10-01) confirmed the 2026-09-18
           pool-manager commit "should not have been merged".
Usage: assemble-2026-10-01.py <fec809e plutus.json> <aad6c59 plutus.json> <out>
"""
import json, sys

KEEP_FROM_BASE = [
    "pool_manager/pm_cancel_pool_manager.poolManager.withdraw",
    "pool_manager/pm_cancel_pool_manager.poolManager.else",
    "pool_manager/pm_edit_pool.poolManager.withdraw",
    "pool_manager/pm_edit_pool.poolManager.else",
]

new = json.load(open(sys.argv[1]))
old = {v["title"]: v for v in json.load(open(sys.argv[2]))["validators"]}
swapped = 0
for i, v in enumerate(new["validators"]):
    if v["title"] in KEEP_FROM_BASE:
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
