# `mainnet-config-datum-2026-09-30.hex`

The **live mainnet ConfigDatum** after FluidTokens' in-place update of **2026-09-30 00:50:56Z**,
fetched from Koios on 2026-10-01.

- config NFT `235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200` + `706172616d6574657273`
- UTxO `3add9d6809c408fc627e93c60065e96857ba5c726d405a5808ee33a7f937c450#0`, block **14005523**
- the previous UTxO, `454814391c3a…#0` (pinned as `mainnet-config-datum-2026-09-19.hex`), is **spent**
- the LMConfigDatum is **unchanged** (byte-identical to `mainnet-lm-config-datum.hex`)

## Why it is pinned here

This is the datum that grounded mainnet on 2026-10-01 08:09Z (FAB-115). Four action fields were
re-pointed at ONE hash, `64d9b13f973be664a05c22365f90b222b0f9018b94918d3cb5d0220f`, which Koios has
never seen published or witnessed:

| field | action | before |
| --- | --- | --- |
| ConfigDatum[11] | loan claim (every seizure / liquidation) — ENFORCED | `63b26ff9…` |
| ConfigDatum[13] | loan change collateral — advisory | `7aa67954…` |
| ConfigDatum[23] | borrow (request path) — advisory | `d86664db…` |
| ConfigDatum[24] | (sell lender position slot) — advisory | `fc16ccaa…` |

Repay [12] and recast [14] were left alone, and no transaction touched the v4 loan script after
~block 14,005,000. Read as a pause by FluidTokens while audit fixes land (upstream commits of
2026-09-30, "fix FTAI-001 and FTAI-002", "fix FTAI-102") — an interpretation, not confirmed.
