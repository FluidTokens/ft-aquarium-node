# The first mainnet convert wave, settled — captured 2026-10-26

`mainnet-convert-settlements-2026-10-26.json` holds, byte for byte, every UTxO of the **ten** mainnet
`LiquidateAndConvert` liquidations made under `lm_liquidate_and_convert_action` `cbf3e8c5…` (live since
2026-10-01) — none of them by this node; all by third-party operators — together with what each Minswap
order became and what happened to that escrow afterwards. Read by `LiquidationEscrowCompoundRoundTripTest`
(FAB-168).

**Source:** Koios, public and keyless — `POST https://api.koios.rest/api/v1/utxo_info` with
`_extended: true` for every UTxO (value, asset list, `inline_datum.bytes`), `tx_info` for the input and
output lists, `asset_txs?_history=true` per lender bond for what happened next. Captured 2026-10-26
15:32–15:40 UTC. The ten liquidations were found through
`script_redeemers?_script_hash=cbf3e8c5…` (10 reward-purpose redeemers). Every UTxO in the file is
**spent** at capture time; the bytes are the chain's at the moment each existed.

## What each record holds

| field | what it is |
|---|---|
| `loan` | the loan input the convert spent (its datum gives collateral, principal, liquidation mode) |
| `bond` | the lender-bond input; `bondEcho` is its byte-identical re-creation, output `#2` |
| `order` | output `#1`: the Minswap V2 order at `c3e28c36…` + the lender's stake, 4 ADA + the swap amount |
| `compensation` | output `#3`: the borrower's equity at the asset manager, `partial_liquidation`, owned by the BORROWER bond (`eadc69a5…`) |
| `settledEscrow` | what the batcher wrote at the asset manager (`b256da41…`, enterprise) when it spent the order |
| `settlement` | `success` (the `converted_to_liquidity` datum, lovelace only) or `refund` (the `claimed_collateral` datum, 2 ADA + the collateral back) |
| `escrowSpentBy` | the transaction that later spent that escrow |

`onChainCompound` is the complete input set of `a793085f…` (block 14,071,696): the escrow `e4a98c45…#1`,
the bond echo `bb885b16…#2`, the pool `1b921dd6…#0` (pool id `004c3793…`) and the pool manager
`4462a6e4…#1` (`compoudingFeePerMille = 0`), plus the pool output's lovelace (1,016,112,980).

## What the chain says (the classification the test pins)

| convert tx | block | collateral | order spent by | settlement | escrow then spent by |
|---|---|---|---|---|---|
| `0f280a54…` | 14,013,857 | ASCEND | `4de7b900…` | **success**, 106,095,906 lovelace | `77f47983…` — compound, `lm_compound_action` `71515a89…` |
| `bb885b16…` | 14,071,694 | ASCEND | `e4a98c45…` | **success**, 208,102,980 lovelace | `a793085f…` — compound, `71515a89…` |
| `a6b7a924…` | 14,071,695 | ASCEND | `7dc5f347…` | **success**, 905,269,145 lovelace | `3f79fcd6…` — compound, `71515a89…` |
| `2e53e734…` | 14,080,877 | ASCEND | `4ef26d32…` | **success**, 272,632,538 lovelace | `8cddf255…` — compound, `71515a89…` |
| `0209ad84…` | 14,092,817 | ASCEND | `929deee1…` | **success**, 436,398,912 lovelace | `36059f5e…` — compound, `71515a89…` |
| `4982abb6…` | 14,106,353 | IAG | `aa27c367…` (#5) | **refund**, 2 ADA + 144,229,936,924 IAG | `8275716a…` — not a compound (reward scripts `1bcd2ac8…`, `247065fa…`, `addd060d…`) |
| `5c1e8579…` | 14,106,353 | IAG | `aa27c367…` (#6) | **refund**, 2 ADA + 143,506,725,373 IAG | `8223db63…` — same shape |
| `b1350594…` | 14,106,353 | IAG | `aa27c367…` (#7) | **refund**, 2 ADA + 43,159,145,818 IAG | `ea917881…` — same shape |
| `f68a9620…` | 14,106,353 | IAG | `e7becb89…` (#0) | **refund**, 2 ADA + 15,866,186,134 IAG | `3ebf36e8…` — same shape |
| `f0a6e7df…` | 14,106,353 | IAG | `e7becb89…` (#1) | **refund**, 2 ADA + 9,526,829,476 IAG | `7d75d368…` — same shape |

- **The 2026-10-23 IAG wave was five REFUNDS**, batched one block after the converts (14,106,354) in two
  Minswap batches. Each returned the whole swap amount of IAG plus 2 ADA (the order's 4 ADA less the
  2 ADA batcher fee) to the asset manager under the order's refund datum. That is the `killable` order
  failing `minimum_receive = remainingDebt`, by design. The candidate spenders the orchestrator's brief
  listed (`6748d415…`, `9616dd5e…`, `438b15ad…`, `4aedc66f…`, `d33d9f1b…`, `8c4986a0…`, `65d67991…`,
  `e9b9c499…`) spend **none** of the ten orders; the real spenders were found by `tx_info` over every
  transaction at the ten order addresses after block 14,013,000.
- **All five successes were compounded on chain within one to five blocks**, all by the 2026-10-01
  `lm_compound_action` `71515a89…`. That script charged `Q*c/1000`, so at `c = 0` the pool received all of
  Q (`a793085f…`: 808,010,000 → 1,016,112,980, exactly +208,102,980). The 2026-10-26 script keeps 1 ADA
  of it back; `row3cTheSettledConvertEscrowCompoundsThroughTheNewLmCompound` pins both.
- **Every convert writes exactly one asset-manager output of its own** — `#3`, the borrower's
  compensation. A pending convert therefore has no lender escrow at all until the batcher acts.
- **No native-principal convert exists on mainnet**: all ten bonds name an ADA principal. A settled
  native-principal convert escrow is a recorded gap, not a fixture.

⚠ The order's `minimum_receive` (the loan's remaining debt at the convert's validity start) is the one
number the test reads off the chain instead of re-deriving; everything else in each order datum is
rebuilt by `ConvertOrderPlan.plan` from the loan, bond and compensation in this file and compared byte
for byte.
