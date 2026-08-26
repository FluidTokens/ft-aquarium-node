# Preview redeploy — what it needs, what must not move, and what would make me roll it back

> **Status: A LIST, NOT AN ACTION.** Nothing here has been built, pushed to a registry, or
> deployed. Sequencing is Giovanni's and the Steward's.

## 0. ⛔ The one thing I could not establish, and it changes the rest

**I cannot determine which commit the running pod is on.** There is no chart, no deployment
manifest and no registry reference in this repository — only a `Dockerfile` and
`docker/docker-compose.yaml`, whose image is `${AQUARIUM_DOCKER_IMAGE_NAME}:${AQUARIUM_DOCKER_IMAGE_VERSION}`
with `.env.example` defaulting the tag to **`latest`**.

**A mutable tag is why this question has no answer.** "Which code is running?" should be readable
off the cluster, and with `latest` it is not — it is whatever was pushed last. **This is the first
thing the redeploy should fix**, and it costs nothing: tag the image with the **git short SHA**
(optionally *also* moving `latest`). Rollback then means naming a SHA rather than rebuilding and
hoping.

⇒ **Before deciding what this redeploy delivers, someone with cluster access must report the tag
and digest the pod is running.** Everything in §1 assumes the answer is "something older than
today".

## 1. What is actually being delivered

**14 commits dated 2026-08-26, of which 11 change code:**

| | |
|---|---|
| `fc2003b` T-048 | attach exactly the scripts not otherwise supplied |
| `772dc9b` T-049 | the wallet view is never silently partial |
| `ee188ad` T-050 | reference-script-safe collateral selection |
| `40747c3` T-053 | the tank nominates a wallet input that provably covers the fee |
| `b095022` T-056 | pay the liquidation fee by name; refuse undeclared loan assets; **delete the change split** |
| `5db49c3` T-054 | V5 runs *inside* the build pipeline |
| `8c208b1` T-058 | mainnet-wiring guard |
| `98cb917` T-051 | **the transaction is built ONCE** |
| `03de72c` T-052 | the wallet input is sized to the liquidation |
| `ccdca58` T-055 | close as moot; delete an orphan |
| `1addd58` T-060 | **the loan census — the reason this redeploy pays regardless** |

Plus three docs commits.

## 2. Building and tagging the image

```
./gradlew clean build            # NOT -x test; see §6 on why the suite is not a green light
docker build -t <registry>/ft-aquarium-node:$(git rev-parse --short HEAD) .
```

- `build.gradle` version is `0.0.1-SNAPSHOT` and **is not the image tag** — it has been
  `0.0.1-SNAPSHOT` all along and says nothing about what is inside.
- ⚠ **`Dockerfile` does `ADD ./build/libs/*.jar`, and exactly one jar must match.** `clean` first;
  a stale jar beside the new one is an ambiguous-glob build failure at best and the *wrong jar* at
  worst.
- ⚠ **`./gradlew build` runs the suite, which currently exits non-zero** (38 known failures, §6). A
  CI pipeline that gates on exit status will refuse to build. That is a decision to take
  deliberately — not a reason to reach for `-x test` without saying so.

## 3. The chart

**Not in this repository.** What it must carry, for the Steward:

- the **image tag** (a SHA, per §0)
- `SPRING_PROFILES_ACTIVE=preview`
- `BLOCKFROST_KEY`, `WALLET_MNEMONIC` — secrets, unchanged, **never printed or relayed**
- `STORE_CARDANO_HOST` / `_PORT` — the relay
- the Postgres connection
- ⚠ **whatever currently overrides `AQUARIUM_LIQUIDATION_*`** — see §4 and §5

## 4. ⛔ What must NOT change

| | |
|---|---|
| **The target network** | ✅ **Structurally safe and better than asked for.** `SUBMITTABLE_NETWORK` is **not an environment variable** — it is `static final String SUBMITTABLE_NETWORK = "preview"` in `LiquidationExecutor:126`. **No config change can move it.** Submission on any other network is refused by a veto that reads a source constant. |
| **`loans.enabled` on mainnet** | Stays `false`. `MainnetWiringTest` (T-058) guards the wiring; the config guards the behaviour. |
| **The fourth-deployment coordinates** | `config-policy-id` / `lm-config-policy-id` / asset name / smart-tokens hash. These are what `indexedPaymentCredentials()` derives from — **change one and the index silently keeps the wrong UTxOs**, which is the redeploy trap already documented in CLAUDE.md. |
| **`sync-start-slot` / `sync-start-blockhash`** | **Must not be RAISED.** It is a *lower bound*; indexing runs forward from it, so an older value is strictly safer. And per `officina:yaci-store-index-scoping` §0, **a stored cursor overrides it anyway** — changing it without deleting the cursor does nothing at all. |
| **The published reference-script coordinates** | Unset means that validator travels in the witness set, which is legal and much larger. Dropping one can push the transaction over `maxTxSize`. |
| **`liquidation.mode`** | Whatever it is now, **do not raise it to `live` in the same change as the new image.** One variable at a time. |

## 5. The `-2000000` margin — my recommendation is REMOVE it, and it is Giovanni's call

**Three reasons, in descending strength:**

1. **⚠ It does far less than it appears to, and this is measured.** The margin sits *outside* the
   absolute floor, so **a negative margin can never rescue a candidate the floor refuses.** Preview,
   2026-08-24: a convert liquidation logged `floor -27303331; - margin -100000000 = 72696669` and
   was **still refused `NOT_PROFITABLE`**, because the floor tests `-27303331 < 0`. **The big
   positive number on that line is real and is not what gated anything.** A `-2000000` margin is
   very likely doing nothing at all today.
2. **Its premise has moved.** It was set when the fee population read as all-zero. Findings §10 was
   amended in place: population B, **7 of 7 live bonds at `liquidationFeePerMille = 50`**. Fee
   slices are real now, so the reason for relaxing the margin is gone.
3. **The config explicitly deprecates the idiom.** `application.yaml:92-97`: the negative margin is
   *"a magic number that looks like it does this and does not"*, replaced by `ignore-profit-check`,
   which reaches **both** gates, announces itself at boot, logs every decision it lets through, and
   **refuses to start on mainnet**.

⇒ **If the intent is "liquidate regardless of loss while testing", the honest lever is
`AQUARIUM_LIQUIDATION_IGNORE_PROFIT_CHECK=true`, not a negative margin.** ⛔ **But not in the same
change as the new image** — it changes which candidates are eligible, and confounds §6.

## 6. ⚠ The honest risk

**Eleven commits of structural change to the transaction builders, and no green rig drives
`build()` end to end.** Every rig that does is built on `LoanFixtures` and is among the 37 failing
against a superseded deployment. The suite is 73 files / 678 tests / **38 failures**, and that
number is *expected* — but it means **the suite passing is not the signal here, and it cannot be.**

**Do I still believe V5 covers it? For what it covers, yes — and it does not cover everything.**

- ✅ **What V5 does cover:** every index a validator reads is re-derived from the **finished body**
  and compared. A wrong output index, a wrong reference-input index, a bond echo that is not an
  echo — each is a `STRUCTURAL_ASSERTION_FAILED` **at build time**: loud, free, nothing on chain,
  no collateral at risk. That is genuinely most of what T-051 changed.
- ⛔ **What V5 does not cover, and I will not pretend otherwise:**
  - **Ledger rules.** V5 asserts *structure*, not fees, min-ada, value conservation or collateral
    adequacy. A phase-1 rejection is cheap and loud, but it is the *chain* telling us, not us.
  - **The convert path has no accepted transaction at all**, so
    `OutputLayout.CCL_PREPENDED_OUTPUTS` is inherited there **by argument, not observation**.
  - **T-052's selection** decides which UTxO is nominated *before* any assertion runs. Nominating
    one that cannot cover the transaction fails at *evaluation* with an empty `ScriptFailures` map
    — which reads as "a script refused", not "you are short".

**⇒ The mitigation is the mode, not the tests.** In `shadow`, the bot builds and records and the
`MODE_NOT_LIVE` veto stops every submission. **Everything in §7 is observable in shadow mode with
nothing on chain.** That is what makes this redeploy safe to do before the coverage exists.

## 7. What I would watch, in order, over the first three cycles

**Cycle 1 — did it start and see the world?**

1. **`LoansConfigVerifier` passes at boot.** ⚠ And remember what it does *not* prove: it locates
   config NFTs by the policy id it is pinned to, so a superseded deployment verifies cleanly
   forever (findings §12).
2. **The scan line, which is the whole reason this redeploy pays:**
   - `… (all N loan utxos at the credential were readable)` ⇒ **`LOAN_NOT_FOUND` is provably
     settled loans**, and T-060 part 2 (Giovanni's counting fix) is safe to write.
   - `… ⚠ n LOAN UTXO(S) UNREADABLE — the LOAN_NOT_FOUND count above is CONTAMINATED` ⇒ **stop.**
     `n` loans are alive, indexed, and undecodable by us. That is a decoder gap, it is the finding,
     and the counting fix must wait.
   - Either way there will also be a `WARN` from `LoanService` when `n > 0`.
3. **`bonds` still reads 7** (or moves for a reason someone can name).

**Cycle 2 — did anything reach the builder?**

4. **`buildable > 0`?** If it is still 0 with everything readable, the market genuinely has nothing
   to liquidate and **none of the builder changes are exercised** — that is a real outcome and
   should be said plainly rather than read as success.
5. **If anything does build:** the new per-candidate nomination line,
   `… nominates wallet utxo X#n (L lovelace) for a requirement of R + O outlay`. **A fee-only
   candidate taking a small UTxO is T-052 working.**
6. **Exactly one script-cost evaluation per candidate** — T-051 removed the second build, so two
   evaluations for one candidate means the probe came back.

**Cycle 3 — is it stable?**

7. Ex-units on any built body are **real, not 10000/1000** (CCL trap 8).
8. No `WALLET_INPUT_TOO_SMALL` storm — one or two is information; every candidate means the
   requirement is being over-computed.

### ⇒ What would make me say ROLL BACK

| Signal | Why it is a rollback and not a ticket |
|---|---|
| **`0 bonds`** where there were 7 | The index stopped seeing the world. This is the redeploy trap and it is not diagnosable in place. |
| **`STRUCTURAL_ASSERTION_FAILED` on every candidate** | V5 refusing everything means the computed layout is wrong — exactly what T-051 risked. Nothing on chain, but the bot is inert and the cause is today's change. |
| **A boot failure naming a loans bean** | T-058's guard firing in production wiring. |
| **Any submission at all while in `shadow`** | The veto stack has a hole. Immediate, unconditional. |
| **`unreadable` > 0 that was 0 before** | Would mean *this build* lost the ability to read loans it used to read. (If it is non-zero from the first cycle, that is the **finding**, not a regression — the instrument is new.) |

**Not a rollback:** `0 buildable`, `COLLATERAL_ORACLE_UNUSABLE` (preview has a real ~60–80s price
blackout every 5 minutes, upstream of us), or `LOAN_NOT_FOUND` on settled loans.

## 8. What this redeploy is worth

**Two purposes, and the second is guaranteed to pay.**

1. *Conditional:* if anything is buildable, it is the first end-to-end exercise of eleven commits
   of builder change against real chain state — the coverage no green test provides.
2. **Unconditional: it is the only way to read the census.** The instrument shipped to the branch,
   not to the cluster, and those are different places. **Whatever the delta says, we learn
   something we cannot learn any other way** — including, if it is non-zero, that some of the five
   "not found" loans are alive and we are blind to them.
