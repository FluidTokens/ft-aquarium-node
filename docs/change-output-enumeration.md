# Why the change split was deleted — every route a token can reach the bot's change output

**The artefact that justifies removing `splitChangeSoAdaStaysSpendable` and `assertChangeConserved`
(T-056, 2026-08-26).** In six months this is the only thing that will explain why they are gone.

## The background in one paragraph

A liquidation returns the bot's change as one output. On 2026-08-25 that output carried
**9,964,993,434 lovelace + 5,000,000 tFLDT**, and `adaOnlyWalletUtxo()` refused it — correctly, it
holds two assets. The wallet held 9,966 ADA and the bot could build nothing. **A successful action
created the condition that blocked the next one.** The fix was a `postBalanceTx` hook that re-shaped
the change afterwards.

**It was a repair, not a cause fix, and it was written while the tokens were UNEXPLAINED.** They are
now explained: measured 2026-08-26, the bot's share is the **liquidation fee** —
`liquidationFeePerMille = 50`, read from the bond datum, corroborated by the on-chain split
`2,410,366 equity / 92,589,634 lender / 5,000,000 bot` summing to the collateral exactly. **A computed
fee can be paid out by name; an unexplained residual cannot.**

## The enumeration

Every route by which a native asset can land in the bot's change output, and whether paying the fee
out by name closes it.

| # | route | closed? | why |
|---|---|---|---|
| 1 | **the liquidation fee slice** | ✅ | The fee is computed at build time (`:1054`) and the payout is `collateral − equity − fee` (`:1077`) — **the fee is the deliberate remainder.** Name it as an output and it stops being change |
| 2 | the loan NFT | ✅ | burned; `mint = −1` |
| 3 | the bond NFT, **and everything else on the bond UTxO** | ✅ | `:1491` echoes `List.copyOf(bond.bondUtxo().getAmount())` — **the exact full value**, so nothing can leak from that input |
| 4 | equity and the lender share | ✅ | paid to asset-manager outputs, `:1509-1521` |
| 5 | the wallet spend input | ✅ | `adaOnlyWalletUtxo()` — ada only, and it stays unrelaxed |
| 6 | collateral inputs (T-050 permits tokens there) | ✅ | **not consumed on success**, and T-050 deliberately does not nominate the spend input |
| 7 | reference inputs | ✅ | never consumed |
| 8 | **⛔ a stray asset on the LOAN UTxO that is neither its declared collateral nor its loan NFT** | **NO — SURVIVES** | `assetManagerAmounts` pays out **only** `loan.datum().collateral().assetType()`. Anything else on that UTxO is paid out by no output and flows to change |

**⇒ Seven of eight close. Route 8 does not, and pay-by-name does not touch it.**

**⚑ And a route that closes for its own reason: when the collateral asset IS ada** (`collateral.isAda()`),
every payout and the fee are lovelace, so change is ada-only and **the split was never needed for those
loans at all.** *It was firing for a population it did not serve, which looks exactly like a guard that
is working.*

## What was done about route 8, and what was not

**Refused at the door rather than repaired afterwards.** A loan UTxO carrying an undeclared asset is
now refused by name, counted, and surfaced in the scan summary.

**⇒ That is a blast-radius reduction, not a fix.** Today, route 8 firing means the build succeeds, the
change carries the stray asset, the wallet's only fresh output is token-bearing, `adaOnlyWalletUtxo()`
finds nothing, and **the whole bot stops** — the 2026-08-25 outage, reproduced by a stranger. After the
refusal, **that one loan is skipped and the bot keeps running.** The griefed loan is unliquidatable
either way; only today does it take everything else with it.

**⚠ The split never HANDLED route 8 — it accidentally SURVIVED it**, which is not the same thing and
reads identically from outside.

## ⛔ The open question this leaves, which is NOT a builder question

**Anyone can send one token to a loan script address and make that loan permanently unliquidatable by
this bot. Cost to the attacker: one min-UTxO and a fee.** The refusal makes it survivable, not
preventable — **we still cannot liquidate that loan.**

**Whether a griefed loan is recoverable at all is a CONTRACT question, not an off-chain one**, and it
is recorded in `lending-v4-findings.md` as an open question for the protocol rather than as work.

## Honesty about the evidence

Route 8 **has never fired.** The measured transaction's loan UTxO was clean —
`[lovelace 3,000,000 · collateral 100,000,000 · loanNFT 1]`. **Confirming a universal against the one
case we happened to observe is exactly the reasoning that made this enumeration a precondition of the
deletion rather than a note beside it.**
