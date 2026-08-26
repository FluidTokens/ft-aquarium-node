# Tests pinned to live chain state — the inventory

**A test that pins live chain state is not a test. It is a measurement with a shelf life, and
nothing in the build knows when it expires.**

It does not fail when the world moves on. It **aborts**, or **passes vacuously**, or **asserts
against a deployment nobody is running** — and all three read as "fine" from a summary line. This
file exists so the next person meets the list rather than discovering it one class at a time, which
is how 2026-08-25 went.

> The sharpest instance: `LiquidatePayInAdvanceProductionWiringLiveTest` is pinned to loan
> `f855d1b4…`, **which we ourselves consumed on 2026-08-24**. A live test pinned to a loan we spent
> is a fixture with an expiry date nobody wrote down. It was reached for on 2026-08-25 as the
> instrument to settle a transaction-size question, and it silently aborted.

## The three expiry modes, and why they need telling apart

| mode | what you see | why it is dangerous |
|---|---|---|
| **abort** | JUnit "skipped"; **Gradle's XML carries no message** and the console says only `SKIPPED` | indistinguishable from a test that quietly did nothing. A summary script counting only `<failure>` reports it as a **pass** — this happened on 2026-08-25 while measuring a transaction |
| **vacuous pass** | green | the assertions ran against data that no longer means what it meant |
| **false assertion** | red, loudly | **the safe one.** These are the 37 currently-failing fixtures: they fail because they are honest |

## The inventory

Deployment column: which preview deployment's coordinates the file pins. **THIRD** = `c45d5306…` /
`de1b8b40…`, superseded 2026-08-25. **FOURTH** = `d46f626f…` / `a7d4b762…`, live.

### The root — one file supplies most of the rest

| file | pins | deployment | on expiry |
|---|---|---|---|
| `service/loans/LoanFixtures.java` | config + lm-config policy ids, config UTxO `7374a985…` | **THIRD** | false assertion |

**This is not a test — it is the shared fixture `registry()` that most dry-eval tests build on, so
they inherit the third deployment through it.** Fixing it is most of fixing the 37, and that is
exactly why it is not a small change.

### Live-gated: abort or skip when their world moves

| file | pins | deployment | on expiry |
|---|---|---|---|
| `LiquidatePayInAdvanceProductionWiringLiveTest` | 7 constants: config, lm-config, asset name, smart-tokens, **loan `f855d1b4…` (CONSUMED 08-24)**, loan/bond addresses, ref-script tx | **THIRD** | **abort** — observed 2026-08-25 |
| `service/loans/LiquidateForRealTest` | third coordinates, **both loans consumed 08-24**, ref-script tx | **THIRD** | abort/skip (triple-gated) |
| `service/loans/LoanFactoryOnChainRunnerTest` | third config UTxO; **submits for real** | **THIRD** | abort/skip |
| `service/loans/ReferenceScriptPublishRunnerTest` | via `LoanFixtures`; synthetic UTxOs, never submits | **THIRD** | false assertion |
| `service/loans/ReferenceScriptPublishSubmitTest` | fourth coordinates, derived hashes asserted pre-build | **FOURTH** | false assertion |
| `WalletSplitSubmitTest` | **nothing — reads the wallet live**; triple-gated, **submits for real** | n/a | abort/skip |
| `PreviewTankTest`, `PreviewParametersTest`, `MainnetTankTest` | Aquarium deploy scripts, mnemonic-gated | Aquarium | abort/skip |
| `LoansConfigVerifierLiveTest` | **nothing — reads the shipped coordinates** | n/a | ✅ **fixed 2026-08-25** |

### Dry-eval and unit: pin real chain data, fail loudly

These are the **37**. They pin real third-deployment loan datums, addresses, oracle payloads and
derived hashes, and they fail rather than lie — which is why they are held rather than urgent.

`LiquidateDryEvalTest` (11) · `LiquidatePayInAdvanceDryEvalTest` (5) ·
`LoansContractDerivationTest` (4, 29 pinned hashes) · `LiquidatePayInAdvanceAndCompoundDryEvalTest`
(4) · `RealEquityLoanDryEvalTest` (3) · `RealLoanDryEvalTest` (3) · `RequestCancelDryEvalTest` (2) ·
`LiquidationExecutorTest` (2) · `LoansConfigVerifierTest` (2, recorded datums) ·
`RequestMintDryEvalTest` (1)

### Oracle payloads — captured, and their validity windows are long past

`PreviewOracleRegistryTest`, `OracleFeedSchemaTest`, `OracleFeedValidityTest`, `OracleEntryTest`,
`service/loans/OracleClients.java`. *Inferred, not verified:* these assert on captured registry
payloads whose feed windows have expired. That is usually deliberate — a captured payload is the
point — but **any one of them that asserts "usable" rather than "parsed correctly" would be the
vacuous-pass mode**, and none has been checked for it.

### Also pinning the reference script we spent

`48c102c0…#0` — our 2026-08-17 publication, **consumed 2026-08-25** — appears in
`ShippedDefaultsTest` (correctly, as a **negative** pin), `util/UtxoUtilTest`,
`ReferenceScriptSafeUtxoSelectionTest`, `RealLoanDryEvalTest`, `LiquidatePayInAdvanceDryEvalTest`,
`LiquidateForRealTest`, `LiquidatePayInAdvanceProductionWiringLiveTest`. Most are harmless string
fixtures; `ShippedDefaultsTest`'s is deliberate and should stay.

## ⚑ NEW WEIGHT, 2026-08-25: the stale fixtures now cost VERIFICATION, not just noise

**Until today the 37 were tolerable because they only failed loudly.** That is the whole argument for
holding them: a false assertion is the *safe* expiry mode (see the table above), and regenerating them
pre-empts a decision that is Giovanni's.

**That argument has changed, and the change should reach him rather than sit here.** Three production
guards landed today — the change split, the collateral-coverage refusal, and `Outcome.QUARANTINED` —
and **not one of them could be proven end to end**, because the only rigs that drive
`LiquidateTransactionBuilder.build()` or `LiquidationExecutor.consider()` build on `LoanFixtures` and
are red against a superseded deployment.

What that cost, concretely:

| guard | proven | NOT proven |
|---|---|---|
| change split | the split itself, per-unit conservation, 3 mutants — all by calling the seam directly | that it runs inside a real `complete()`; **the two-pass claim is read off source, not measured** |
| collateral refusal | the arithmetic, the ceiling, the artefact check, 4 mutants | that it fires on a real build |
| `QUARANTINED` | that the outcome is wired to a call site, by discrimination | **reachability** — `EveryOutcomeIsReachableTest` reads source and says so in its own javadoc |

**⇒ The 37 are no longer only withholding a signal. They are blocking the rigs that would verify
production changes**, so every guard added while they are red ships with a gap that has to be written
down instead of closed. **That is new weight on an open decision, not a new recommendation** — the
choice between regenerating fixtures from fourth-deployment loans and disabling them pending new ones
is still Giovanni's, and this file still fixes nothing.

## Deliberately not fixed

**This list is an inventory, not a work item.** Fixing anything here would pre-empt the unresolved
decision about the 37 — whether to regenerate fixtures from fourth-deployment loans or disable them
pending new ones — which is Giovanni's and has not been made.

One exception, already taken: `LoansConfigVerifierLiveTest` was fixed on 2026-08-25 because it was
not merely stale but **actively producing a false result** — it hardcoded third-deployment ids while
being cited as the check for whether the shipped coordinates had gone stale, and a 2×2 built on it
named the wrong deployed commit for half a day.

## What to do when adding a test that touches chain state

- **Read the coordinate from the shipped config**, not from a constant. `LoansConfigVerifierLiveTest`
  now does this, and prints what it loaded.
- If you must pin a live object, **say in the javadoc what makes it expire** — a loan is consumed, a
  reference script is spent, a deployment is superseded.
- **Prefer failing loudly to aborting.** An abort is invisible; if a precondition is absent, consider
  whether the honest outcome is a failure.
- **Never read a skipped test as a pass.** Check `skipped=` in the XML, not just `failures=`.
