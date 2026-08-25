# Handoff — lending-v4, morning of 2026-08-26

**Written at standby, 2026-08-25 night, for a cold session. Read this before touching anything.**

**Status: STANDBY BY INSTRUCTION.** Giovanni: *"aquarium goes in standby till the morning."* That is
a third state — **not blocked, and not waiting for a turn.** Nothing is degrading, nothing needs
urgent attention, and no work is half-applied. `feat/lending-v4` is clean, pushed, 0 unpushed.

---

## ⚑ THE FRAME — read this first, it reorganises everything below

Giovanni, 2026-08-25: *"we haven't really used cardano client lib docs to build the tx in this
repo."* He is proposing a review of the builder against the cardano-dev-skills docs once the latest
lands.

**Read against that sentence, today's findings stop being five unrelated bugs.** Every one was
reached empirically — a right answer with the mechanism unknown:

| # | finding | where | why it is the same shape |
|---|---|---|---|
| 1 | `removeDuplicateScriptWitnesses(true)` bundled inside a guard belonging to `withReferenceScripts` — **so it never ran in production** (`backendService` is never null there) | `LiquidateTransactionBuilder:1921-1929` | two calls treated as one because their *purpose* was never separated |
| 2 | `ignoreScriptCostEvaluationError` is honoured at **one** call site and not the other | `QuickTxBuilder:454-463` vs `ScriptBalanceTxProviders:78` | discovered by **reading v0.7.2 tonight**, not by knowing it |
| 3 | a two-pass layout probe **observing an order that is determined by construction** | `AbstractTx.complete():304-328`, `StakeTx.buildStakePayments():291-295` | the builder never constrained the order, so it measured instead |
| 4 | `.feePayer(changeAddress)` propagates into the ScriptTx's **change address** | `QuickTxBuilder:338` | a side effect nobody chose |
| 5 | the collateral return is computed **entirely inside CCL**, unguarded | `CollateralBuilders:124-149` | we did not know this until tonight |

**⇒ This is the shape of code written against a library by trial rather than by documentation: right
answers reached empirically, mechanism unknown, so the next change breaks them silently.** Not a
criticism of whoever wrote it — **it is the strongest argument for the review Giovanni is proposing,
and it arrives with five confirmed instances rather than a suspicion.** Start the review here.

> cardano-client-lib **v0.7.2** — our exact pin — is cloned at
> `~/Development/workspace/cardano-client-lib`. Every line reference above was read from it. **When
> an instrument is unavailable, go to the source; that is how three of today's five were settled.**

---

## 1. What is staged and undeployed

| commit | what |
|---|---|
| `80857b5` | **change split** (keeps the wallet spendable after a liquidation) + **`INSUFFICIENT_COLLATERAL`** refusal |
| `eda0f14` | **`Outcome.QUARANTINED`** — the quarantine skip records instead of returning silently |
| `4a5f206` | findings §17 — the two post-liquidation defects |
| `7bb150b` | the 37 now cost verification, not just noise |
| `15e3eff` | `DeploymentLivenessProbe` removed; `LayoutProbeEvaluator` marked `@Deprecated(forRemoval=true)` |

**⛔ NONE of these has run in a real `complete()`.** Nothing is deployed. The image in preview
predates all of them.

## 2. ⛔ The unproven claims, named as such

Keep these honest where the next reader finds them — they are not covered by the green tests.

- **The change split's two-pass behaviour is READ OFF SOURCE, NOT MEASURED.** The claim is that a
  `postBalanceTx` hook installed inside `complete()` runs in both probe passes, so the observed
  output indexes are computed against the post-split layout. Its 12 tests call the seam directly.
- **`EveryOutcomeIsReachableTest` proves WIRING, NOT REACHABILITY.** It reads source and asserts each
  declared `Outcome` appears at a call site. It says nothing about whether the branch is reachable.
- **Why neither is measured:** the rigs that would drive `build()` and `consider()` end to end all
  build on `LoanFixtures` and are red against a superseded deployment. See §5.

**The first real build is the measurement.** Both guards fail loudly and for free if the seam was
misread — phase 1 or a build-time refusal, nothing on chain.

## 3. Open questions

- **Why did the bot receive 5,000,000 tFLDT** on `49743a1e…`? Preview loans carry
  `liquidationFeePerMille = 0`, so **it is not the fee.** ⚑ **This gates the Shape A → Shape B
  decision below.**
- **`LayoutProbeEvaluator` — Giovanni ordered deletion; it is on hold pending his ruling.** Evidence:
  a no-op returning success with zero costings is the **only** value surviving both call sites
  (finding 2 above), so it is load-bearing. **(1)** keep it deprecated — recommended; **(2)** delete
  the class, inline the lambda twice — his literal ask, identical behaviour, but the stub stays in
  `src/main` and loses the trap-8 naming guard; **(3)** delete the no-op — **provable regression.**
  ⇒ **Finding 3 changes the endgame: replace the probe with compute-and-assert and the class goes on
  its own.** `assertStructure` already exists as the assertion half. That also deletes one of the two
  builds per liquidation.
- **Shape A → Shape B.** There is no CCL primitive that splits change; `payToAddress` is the primitive
  and the builder already uses its sibling for every other output. Shape B would run before balancing
  and get fee pricing for free — **but it means naming an amount, and we cannot yet explain the
  5,000,000.** Shape A's property is not needing to know it. **Once the number is explained, B wins.**
- **Per-candidate wallet selection** — deferred. The executor resolves one wallet UTxO per cycle and
  reuses it for every candidate, so a second liquidation in the same cycle builds against an input the
  first already spent. Needs a rule for how a cycle divides its inputs.
- **The 37.** They no longer only withhold a signal — **they block the rigs that would verify
  production changes** (§2). First instance arrived the same evening it was written up:
  `LiquidatePayInAdvanceDryEvalTest::aBuildThatNeedsABalancingInputSucceedsAndSpendsAnOrdinaryUtxo`,
  written for exactly the hazard in finding 2, is one of the red 38. Deciding them is Giovanni's.

## 4. ⛔ The wallet — Giovanni's, and still unmade

```
pure-ada collateral capacity   1,000,000
required (fee × 150%, CEILING) 1,670,285
shortfall                        670,285
```

**The binding constraint is the pure-ada COLLATERAL INPUT, not the balance** — the body balances fine
with four inputs. CCL subtracts without checking (`CollateralBuilders:137`), so the return went to
**−670,285**, and a negative `MaryValue` has no encoding: every era decoder rejected it **before any
validation ran.** `assertCollateralIsCoverable` now refuses this at build time.

⚠ `1_000_000` is also a hardcoded CCL placeholder in **two** places (`CollateralBuilders:115`,
`ScriptBalanceTxProviders:75`). **Three identical numbers, unrelated provenance, one field.** Do not
read one for another.

## 5. Suite, and what the deployed bot is doing

**Cold, env unset: 65 files · 631 tests · 38 failures · 0 errors · 19 skipped.** No orphans.

**38 = the 37 fixture-pinned + the named `LiquidationSubmitVetoTest::aTokenLiquidationRefusedSolelyByTheMinAdaRider`.**
Same 11 classes all evening. **If it moves, that is its own item — report the number before anything else.**

```bash
rm -rf build/test-results/test && ./gradlew cleanTest test      # never a bare `test`: UP-TO-DATE proves nothing
```

**The deployed preview bot is looping `SUBMIT_FAILED` every 30 minutes, deterministically and for
free** — it builds, the submission is rejected at the decoder, the loan is quarantined, the quarantine
lapses, it tries again. Nothing is degrading, nothing is on chain, no fee is paid. **It is safe to
leave exactly as it is.**

## 6. Standing constraints

Nothing near mainnet · nothing deployed · `adaOnlyWalletUtxo()` unrelaxed · the profit flag unset ·
the 37 held · `officina` and `fabbrica` **commit-not-push at 12 commits each — both pushes are
Giovanni's** · branch work and pushes on `feat/lending-v4` are fine, merges/tags/releases are not.
