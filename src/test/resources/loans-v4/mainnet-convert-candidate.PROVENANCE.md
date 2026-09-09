# The mainnet convert candidate — captured 2026-09-03

⛔ **Read this before treating `d832b78e…` as anything it is not.**

Giovanni relayed it as *"a test convert tx ready to go"*. It is **not a convert transaction.**
Decoded here from mainnet Blockfrost, it is the **loan-origination (borrow) transaction that creates
the candidate** a convert would later act on. The distinction is not pedantic — a convert builder
diffed against it would be diffed against the wrong shape.

**What settles it, from the chain rather than from the name:**

| evidence | reading |
|---|---|
| **three MINTs** — loan NFT `0061ade3…`, lender bond `bcd713bb…`, borrower bond `eadc69a5…` | a convert mints nothing |
| **no Minswap order output** | the convert action's whole purpose is to create one |
| **no Minswap pool reference input** | the convert action requires one |
| pool UTxO spent and re-created 53.75 → 33.75 ada | principal leaving a pool: origination |

`valid_contract: true`, block 13,892,677, size 3,933 B, 7 redeemers, 3 withdraw-0 invocations.

## The candidate it creates — and it IS convert-eligible

| | value |
|---|---|
| loan UTxO | `d832b78e…#1`, holds the loan NFT + **100,000,000 FLDT** (`577f0b13…0014df10464c4454`) |
| principal | **20,000,000 lovelace** (ADA) |
| collateral oracle | `93794f9b…` `oracleFLDTC3` (Charli3 FLDT) |
| lender bond | `d832b78e…#3` |
| **shouldLiquidationConvertToPrincipal** | **True** — the convert action's first conjunct passes |
| **liquidationFeePerMille** | **50** (5%) |
| poolId | `0046337bd27d65a63574039b6293da11701ed2da01bcfaf626c18cccbe` |
| principalAsset | ADA |

## Files

| file | what it is |
|---|---|
| `mainnet-loan-datum-d832b78e.hex` | inline datum of `d832b78e…#1`, the loan |
| `mainnet-lender-bond-datum-d832b78e.hex` | inline datum of `d832b78e…#3`, the lender bond |
| `mainnet-minswap-pool-ada-fldt.hex` | inline datum of the **live** ADA/FLDT Minswap V2 pool |

## The Minswap side, all read from mainnet rather than assumed

- **pool UTxO**: `665195ca95aac79331ce9d83f2902849999e0f2ba98f663df37191ecda3d03c6#1`
  at `addr1z84q0denmyep98ph3tmzwsmw0j7zau9ljmsqx6a4rvaau66j2c79gy9l76sdg0xwhd7r0c0kna0tycz4y5s6mlenh8pq777e2a`
- **pool NFT**: `f5808c2c…` + **`4d5350`** — the ASCII `MSP`, the same asset name for every pool
- **LP asset name**: `bc53f5c2a8cf3ef64081d2ec8c74333d567fc7ef271c1b97d21fdd53a2c5c889`
- **reserves** at capture: 1,692,342,884,761 lovelace / 7,596,442,927,398 FLDT
- `asset_a` = ADA, `asset_b` = FLDT ⇒ for this loan **`lpABDirection = False`**, and the validator's
  else-branch (`asset_b == collateral && asset_a == principal`) is satisfied.

⚠ **The pool UTxO reference moves every time the pool is used.** These coordinates are a snapshot for
fixture purposes; a builder must resolve the pool by its **NFT** at run time, never by a pinned ref.

---

## ⛔ `mainnet-lm-config-datum.hex` RE-CAPTURED 2026-09-04

FluidTokens updated the mainnet LenderManager config **in place** at **11:19:16 UTC** to point at
their fixed convert action. The recorded fixture went stale the moment they did, and the tests
caught it: `ConvertActionDerivationTest#theMainnetLmConfigDatumReallyPublishesThatHashAtFieldFive`
went red because the recording still carried the superseded hash.

| | before | after |
|---|---|---|
| config UTxO | `7b9f20db…#1` | **`8296a2fe…#0`** |
| field 5 — convert action | `ed8d41e4…` | **`dc71541066c95303794863f0a2889fb217a6cc5498e53ad3e077339a`** |
| every other field | — | **byte-identical** |
| new reference script | — | `e4e47ab1…#0`, publishing the new hash |

Re-captured verbatim from Blockfrost, mainnet, from that transaction's output carrying the
`a56b0ac2…parameters` NFT.

⚠ **This is the second time a recorded config datum has aged out from under the suite**, and the
first cost a morning (findings §53). It is the ordinary condition of any fixture recorded from a
live chain, not an accident: **the chain is the source of truth and the recording is a snapshot with
no expiry stamped on it.** The guard is that a test compares the recording against something derived
independently, so the drift surfaces as a red rather than as a silent agreement between two stale
things.
