# The operator UI — epic plan (for Giovanni's review)

**Status: PLAN. Nothing here is built.** Written 2026-09-03, after the mainnet shadow run
(`docs/lending-v4-findings.md` §54). It exists to be argued with before any code is written; the four
decisions in §6 are Giovanni's and the sequencing in §7 depends on them.

---

## 1. Why this epic exists, stated as a measured problem rather than a wish

An operator runs this node to make money. **The liquidation bot's most likely successful action makes
their ada balance go DOWN.** That is not a bug and not a hypothesis — it is the measured behaviour of
the only live mainnet candidate:

```
pays      : -20,887,781 lovelace     fronted from the operator's own wallet
receives  : 100,000,000 FLDT, oracle-valued at 22,267,706 lovelace
gross     : +1,379,925 lovelace, PRE tx fee — and held entirely in TOKENS
```

Item 14 is ruled: **accrued collateral is held; the operator disposes of it later, the bot never
swaps or sweeps.** So the intended steady state of a working bot is *ada draining, tokens
accumulating*.

⇒ **An operator watching only their ada balance sees a bot that loses money every time it works.**
Today the node gives them nothing else to look at: there is no view that pairs the outflow with the
inventory it bought. **That is the epic.** Everything below is downstream of it.

---

## 2. R1 — THE DUAL-BALANCE SIGNAL (the one requirement that justifies the epic)

**One view, three numbers, always together:**

| | |
|---|---|
| **ada** | wallet ada, and its change over the window — *expected to fall* |
| **tokens** | accrued collateral holdings by asset, and their change — *expected to rise* |
| **net** | both legs marked to the **same oracle feed the bot priced the trade with**, plus realised tx fees |

**⛔ The three must be rendered together and must never be separable.** An ada-only widget is worse
than no widget: it is a correct number that supports a false conclusion, and the operator's rational
response to it is to switch the bot off.

**Design constraints, each from something already measured:**

- **Price with the bot's own feed, not a market price.** The bot's economics gate values the fee
  through `LoanFinance.toLovelace` on the loan's named oracle. A UI that valued the same tokens off a
  DEX mid-price would disagree with the gate that made the decision, and the operator would have two
  numbers and no way to choose. **Same feed, or say "unpriced".**
- **Never fabricate a value.** `LoanHealth` already refuses to guess: no feed ⇒ null and a reason,
  never zero. The UI inherits that. **An unpriceable holding is shown as a token quantity with an
  explicit "no feed" marker** — not as a dash, and never as 0.
- **Show the oracle's age.** Preview has a real ~60–80 s price blackout every 5 minutes (design
  §6.7–6.8) and the registry serves windows ~323 s after their own `validFrom`. A net figure with a
  stale feed behind it must say so.
- **Realised vs unrealised is a real distinction here.** Fees are paid in ada and realised; the token
  gain is unrealised until the operator sells. **Label it**, or the first time FLDT halves the
  operator will believe the UI lied to them.

---

## 3. What already exists to build on

| surface | what it gives | gap |
|---|---|---|
| `GET /api/v1/loans` | the loan population, health, LTV | no wallet, no history |
| `GET /api/v1/loans/oracle` | the feed registry and ages | not joined to holdings |
| `GET /api/v1/loans/liquidations` | `RunSummary` + the decision ring buffer | **in-memory only**, lost on restart |
| `GET /healthcheck` | `walletOk`, staking | boolean, not a balance |
| `AppUtxoService.listWalletUtxo()` | the wallet's actual UTxOs | not exposed over HTTP |

⇒ **The data for R1 exists inside the process today.** No new external dependency is needed for the
core signal — which matters, because the constitution makes any new external service an escalation.

---

## 4. The gaps this epic closes

**G1 — wallet inventory is not exposed.** `listWalletUtxo()` is called only by the healthcheck, which
reduces it to a boolean. R1 needs the ada total, the per-asset token totals, **and the largest single
ada-only UTxO** — because that last number, not the total, is what decides whether the next anticipate
candidate is buildable (`WALLET_INPUT_TOO_SMALL`; healthcheck's own javadoc records a node holding
9,966 ADA that could build nothing).

**G2 — the decision log does not survive a restart** (item 8). Every "why did it not act last night"
question is unanswerable after a bounce. **See D1: this is the epic's one architectural decision.**

**G3 — boot-time refusals are log-only** (item 9). `CONVERT UNAVAILABLE`, a clamped market mode, a
missing reference script, `loans.enabled=false` — each is one `log.warn` at startup, in a container
whose logs an operator may never open. **A node that has decided it cannot do something must say so on
a surface that is still there an hour later.**

**G4 — accrued collateral is invisible** (item 14). The bot acquires tokens deliberately and holds
them. Nothing counts them, ages them, or tells the operator what they are worth. **⚠ Scope: display
only. No swap, no sweep, no disposal — that is the ruling, and the UI must not grow a "sell" button.**

**G5 — the config that decides everything is not shown.** Runbook §14 exists because a half-configured
arming routes the only live mainnet loan to a dead path. **The UI must show the EFFECTIVE posture** —
`min(node, market)` per market, the action, the cap, and all four arming switches — so the §14 mistake
is visible rather than deducible.

---

## 5. Requirements

**R1** — the dual-balance signal (§2). *Everything else is optional; this is not.*
**R2** — decision history that survives a restart, filterable by outcome, with the veto that held each
one (D1 decides how).
**R3** — the effective posture panel: four arming switches, per-market effective mode/action/cap,
and every boot-time refusal still legible (G3, G5).
**R4** — the loan population with health, LTV and the scanner's verdict per bond, including
`unreadable` — **the blindness counter is a first-class number, not a footnote.** A zero market and a
blind node look identical without it (T-060, §54.1).
**R5** — the shadow dump reachable from the UI: variant, held-by veto, size, **ex-units read off the
built transaction**, and the CBOR available but not rendered inline.

**⚠ R6 — display the figure the bot acts on.** The amount an anticipate fronts is
`convertedLoanCollateralToPrincipalAmount`, **not `remainingDebt`**. They differ by 886,721 lovelace on
the live mainnet candidate. **Take the production builder's `numbers()` output; do not re-derive
arithmetic in a view layer** — a second implementation of the same formula is a second answer.

**Non-goals, explicitly:** no arming controls, no submit button, no key handling, no disposal of
accrued tokens, no config editing. **The UI is an instrument, not a cockpit.** Anything that changes
node behaviour stays in config and in Giovanni's hands.

---

## 6. ⛔ The four decisions that are Giovanni's

**D1 — how does the decision log persist? (blocks R2)**
`LiquidationDecisionLog`'s javadoc already refuses this on stated grounds: persisting means a JPA
entity and a Flyway migration, and `spring.flyway.locations` is left at the Yaci Store starter's
default — **this app contributes not one migration to any operator's schema.** Adding a location
changes schema management for **every** node, mainnet included, where lending is disabled and the
class does not even exist.

| option | cost | blast radius |
|---|---|---|
| (a) Flyway migration | smallest code | **every operator's schema**, mainnet included |
| **(b) append-only JSONL on the mounted volume** ⭐ | a writer + a reader | **none** — no schema, no new dependency |
| (c) stay in-memory, export to Prometheus | least work | **does not answer "why not last night"** — counters, not decisions |

**Recommendation: (b).** It survives restarts, it is greppable by an operator without any tooling, it
costs no schema change on nodes that will never run lending, and it can be deleted or rotated without a
migration. ⚠ It needs a retention bound and a disk-full posture — an unbounded log on an operator's
volume is a new failure mode, and the answer must be "degrade to in-memory and say so", never "stop
liquidating".

**D2 — who may see this?** The page shows wallet balances and transaction CBOR. `/healthcheck` and
`/api/v1/loans` are unauthenticated today. **Recommendation: do not invent auth in this repo.** Serve
the UI on the existing internal surface, document a reverse-proxy requirement in the runbook, and bind
to loopback by default. ⚠ If the answer is instead "it must be safe on a public port", that is a
different and larger epic and should be sized separately.

**D3 — does the UI ship in the operator image?** `main` is production and lending is disabled there.
**Recommendation: gate the whole UI on `loans.enabled`**, exactly as every lending bean already is, so
the mainnet operator image is byte-identical in behaviour for anyone not running the bot.

**D4 — what renders it?** The constitution makes new runtime tech an escalation.
**Recommendation: server-rendered HTML from the existing Spring app** (Thymeleaf, already on the Boot
BOM) reading the JSON endpoints that exist. **No SPA, no Node toolchain, no second build.** ⚠ If
Giovanni wants a richer client later, the JSON endpoints are the seam and nothing here forecloses it.

---

## 7. Sequencing (each slice independently useful, no slice depends on an unmade decision)

| # | slice | needs | delivers |
|---|---|---|---|
| **S1** | wallet inventory: ada, per-asset tokens, **largest ada-only UTxO** — service + JSON | — | G1, and the healthcheck's boolean gets a number behind it |
| **S2** | **the dual-balance view** — S1 joined to the oracle registry, marked to the bot's own feed, with staleness and realised/unrealised split | S1, **D4** | **R1 — the epic's reason to exist** |
| **S3** | effective-posture panel: four switches, per-market `min()`, boot refusals surfaced | D4 | R3, G3, G5 — and makes the runbook §14 mistake *visible* |
| **S4** | loan population view incl. `unreadable` | D4 | R4 |
| **S5** | durable decision log + history view | **D1** | R2, G2 |
| **S6** | shadow dump view | D4 | R5 |

**S1 and S2 are the epic.** S3–S6 are worth doing and none of them is why this exists.

⚠ **S5 is the only slice blocked on a decision**, which is why it is last rather than first despite
item 8's age. **Do not let it gate S1/S2.**

---

## 8. What this plan deliberately does not answer

- **Whether the bot should ever dispose of accrued collateral.** Ruled: it should not. If that ever
  changes it is a new epic with its own economics, not a button added here.
- **Multi-node or fleet views.** One node, one operator.
- **Alerting to anywhere off the box** (email, Slack, webhooks). G3 is about a *surface that is still
  there an hour later*, not about pushing notifications — pushing is a new external dependency and
  therefore an escalation.
- **Anything on the convert path's UI beyond the shadow dump**, until FluidTokens repin their Minswap
  dependency (findings §51) and the path can actually execute.

---

## Related documents

- `docs/lending-v4-findings.md` §54 — the mainnet shadow run these requirements are measured from
- `docs/operating-the-liquidation-bot.md` §14 — the pre-arm configuration the posture panel must make visible
- `docs/auto-liquidation-design.md` — the bot's design
