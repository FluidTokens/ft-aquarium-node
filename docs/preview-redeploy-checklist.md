# Preview redeploy — what it needs, what must not move, and what would make me roll it back

> **Status: A LIST, NOT AN ACTION.** Nothing here has been built, pushed to a registry, or
> deployed. Sequencing is Giovanni's and the Steward's.

## 0. ⛔ CORRECTED 2026-08-26 — my finding here was WRONG, and the real gap is worse

**What I wrote:** that "which code is running?" was unanswerable, because `.env.example` defaults
the image tag to `latest`.

**What is actually true, measured from the cluster:**

```
image  : localhost:5000/ft-aquarium-node:lending-v4-2eeb52e
imageID: ...@sha256:723fa15bcc81622e691a87f55a1f9aae894ded9e0107bdd3657e779f890e4328
```

**Commit `2eeb52e`, pinned by BOTH a commit-bearing tag and a digest**, with Argo passing
`tag=lending-v4-2eeb52e` explicitly. SHA tagging is already the practice.

**⚠ How I got it wrong is the instructive part.** `.env.example` describes the **compose** path —
the one operators run — not the **deployed** path. I read the artefact that was *convenient to
read* and reported it as the state of a system that does not consume it. **A file in this repo is
not evidence about a cluster**, and the fix was to ask, which nobody had.

**⇒ The real gap, which I could not have seen and which stands:** the Argo Application lives in
`steward/clusters/ryzen/apps/`, carrying the digest and the rationale — but **Argo cannot read that
repo, so every Application is applied imperatively.** *The deployment record lives in a third
repository that neither this repo nor Argo reads.* That is the true version of what §0 was reaching
for: not "the tag is mutable", but **"the intent and the running state are recorded in different
places, and only one of them is enforced."**

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

## 6. ⛔⛔ THE RISK — AND THE MITIGATION I CLAIMED HERE DOES NOT EXIST

> **This section originally argued: "the mitigation is the mode, not the tests — in `shadow`,
> `MODE_NOT_LIVE` stops every submission, so everything in §7 is observable with nothing on chain."
> THAT IS VOID. Measured on the cluster: `AQUARIUM_LIQUIDATION_MODE=live`,
> `AQUARIUM_LIQUIDATION_ENABLED=true`, and the app confirms it at boot —
> `INIT - liquidation mode: LIVE, armed: true`. THERE IS NO SHADOW.**

**And the situation is sharper than "the flag is on".**

```
50 decisions buffered:  SUBMIT_FAILED ×38 · REFUSED ×12 · SUBMITTED ×0
scan now reads:         7 bonds, 2 BUILDABLE, exclusions {LOAN_NOT_FOUND=5}
```

**⇒ The bot has been trying, live and armed, every 30 minutes for 22 hours. Nothing reached the
chain because THE BUILDER COULD NOT PRODUCE A VALID TRANSACTION — the defect has been the safety
net.** And **eleven of today's commits fix exactly that.**

**⇒ So this is not "ship and observe". It is: take a bot that has been failing safely for 22 hours,
make it succeed, and point it at TWO LIVE CANDIDATES — with no green rig driving `build()` end to
end.**

### What the coverage actually is

- ✅ **V5 covers structure:** every index a validator reads is re-derived from the **finished body**
  and compared. A wrong output index, a wrong reference-input index, a bond echo that is not an
  echo — each is a `STRUCTURAL_ASSERTION_FAILED` **at build time**: loud, free, nothing on chain.
- ⛔ **V5 does not cover ledger rules** — fees, min-ada, value conservation, collateral adequacy.
- ⛔ **The convert path's `OutputLayout.CCL_PREPENDED_OUTPUTS` is inherited by argument, not
  observation** — no convert liquidation has ever been submitted.
- ⛔ **T-052's selection runs before any assertion**, and a nomination that cannot cover the
  transaction fails at *evaluation* with an empty `ScriptFailures` map.

### ⚠ And the failure DIRECTION changes, which is the part that matters

The 38 `SUBMIT_FAILED` are **phase-1 rejections: free, loud, nothing on chain, no collateral at
risk.** That is the cheapest failure mode there is. **A transaction that now gets *further* can
fail in phase 2 instead — where the collateral input is consumed and the fee is forfeit.** We are
deliberately moving transactions past the gate that has been protecting us.

What stands against that is real but is not a test: ex-units come from a **live Blockfrost
evaluator** whose parameters are the chain's by construction, and
`ignoreScriptCostEvaluationError(false)` makes a failed evaluation **abort the build** rather than
ship placeholders (CCL trap 8, and measured working on `8609fa37…`).

### ✅ ANSWERED — what the 38 `SUBMIT_FAILED` are, and why it changes the framing

Measured from the decision log by `steward-d0`:

```
38 / 38   DecoderErrorDeserialiseFailure + "MaryValue: expected array or int, got TypeNInt"
distinct detail strings : 1
tx sizes                : {11915: 38}      ← byte-identical, every one
loans                   : ff427de582c8 ×31 · 780bc25bff10 ×7
```

**One fault, and it is the negative collateral return.** `total_collateral 1,670,285` against a
`1,000,000` collateral input ⇒ `collateral_return = −670,285`, and **a negative `MaryValue` is
unrepresentable**, so the node cannot *read* the transaction.

**⛔ Which means the framing everywhere above was too generous, including mine.** These are **not
phase-1 rejections**. They die in the **decoder, before the ledger validates anything**:

> **The gate that has protected us is a malformed-CBOR error. That is not a safety mechanism; it is
> a bug that happens to sit upstream of every risk.**

That is the argument for `shadow` stated without any appeal to caution.

**My half of the join, verified rather than asserted** (the fault is `steward-d0`'s measurement;
that today's commits fix it is this repo's claim, and the two should not be signed by the same
party):

- `collateralInputsFor` accumulates inputs until `capacity ≥ maxPossibleCollateral(params)` —
  **3,910,541 lovelace from LIVE preview parameters** (corrected 2026-08-26 — the 3,607,616 first
  written here was fixture-derived, see `LedgerCeilings`), comfortably above the 1,670,285 that
  produced the negative return. Input ≥ requirement ⇒ the return is non-negative.
- And if the wallet *cannot* reach it, `assertCollateralIsCoverable` reads
  **`collateralReturn` off the built artefact** and refuses `INSUFFICIENT_COLLATERAL` at build
  time. **So the worst case is a refusal, not another unparseable transaction.**

**⚠ The limit on "no second fault", which is the half that matters to us:** these died at the
decoder, so *nothing downstream of it has ever run*. **Script execution, collateral consumption and
phase 2 are UNTESTED, not proven clean** — and that is precisely the territory the redeploy opens.

### ⇒ Recommendation: DEPLOY INTO `shadow`, ARM SEPARATELY

**The redeploy's guaranteed payoff — reading the census — does not need `live` at all.** Set
`AQUARIUM_LIQUIDATION_MODE=shadow` **with or before** the new image; read the delta and watch the
builder against two real candidates with nothing on chain; then arm as its own change once the
builds look right.

**One variable at a time, and the safe direction first.** ⛔ **Whether to accept the live risk
instead is Giovanni's decision, not a default to fall into because the flag happens to be set.**

### ⚠ The question that should be answered before either path

**What are the 38 `SUBMIT_FAILED` details?** If they name a fault today's commits fix, we know what
will now succeed. **If they name something else, the redeploy may change nothing** — and the whole
risk would have been taken for no gain. That is one query against the decision log and it should
precede the build.

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

### ⚑ PRE-LABELLED EXPECTED OUTCOMES — none of these is a regression

**Write these down before the first cycle, because an accepted defect that is not pre-labelled is
indistinguishable from a new one at 3am.**

| Expected | Why it is not a regression |
|---|---|
| **The SECOND liquidation fails `BadInputsUTxO` on the same cycle** | **Two loans are queued** (`ff427de582c8`, `780bc25bff10`). The executor resolves wallet UTxOs once per cycle, so the first transaction to succeed consumes the input the second was built against. **Giovanni already ruled on this and accepted it with rationale** — *"1 liquidation per block might be enough to begin with."* Expect it on the very first successful cycle. |
| **`LOAN_NOT_FOUND = 5`** | Settled loans, unless the census says otherwise — that is what the census is for. |
| **`COLLATERAL_ORACLE_UNUSABLE` appearing and vanishing** | The real ~60–80s preview blackout every five minutes, upstream of us. |

**⚠ One interaction nothing tests, worth watching rather than predicting.**
`assertCollateralIsCoverable`'s *secondary* capacity term reads the **nominated wallet UTxO**
(`adaOnly(walletUtxo)`), while T-050 now chooses collateral inputs **separately and possibly
several of them**, and T-052 now nominates the **smallest sufficient** UTxO rather than the largest.
If that nominated UTxO is small enough, the secondary check could refuse a candidate whose actual
collateral inputs are ample. **It fails in the safe direction — a refusal, nothing on chain — but
it would look like the bot rejecting good candidates.** The authoritative check (negative return on
the artefact) is unaffected. A run of `INSUFFICIENT_COLLATERAL` refusals is the signature.

### ⇒ What would make me say ROLL BACK

| Signal | Why it is a rollback and not a ticket |
|---|---|
| **`0 bonds`** where there were 7 | The index stopped seeing the world. This is the redeploy trap and it is not diagnosable in place. |
| **`STRUCTURAL_ASSERTION_FAILED` on every candidate** | V5 refusing everything means the computed layout is wrong — exactly what T-051 risked. Nothing on chain, but the bot is inert and the cause is today's change. |
| **A boot failure naming a loans bean** | T-058's guard firing in production wiring. |
| **Any submission while in `shadow`** | The veto stack has a hole. Immediate, unconditional. ⚠ **Only meaningful if the mode is actually `shadow` — it is currently `live`, see §6.** |
| **A submitted transaction that fails in PHASE 2** | Collateral forfeit. This is the failure the 38 phase-1 rejections have been preventing, and the one the redeploy makes reachable. |
| **`unreadable` > 0 that was 0 before** | Would mean *this build* lost the ability to read loans it used to read. (If it is non-zero from the first cycle, that is the **finding**, not a regression — the instrument is new.) |

**Not a rollback:** `COLLATERAL_ORACLE_UNUSABLE` (preview has a real ~60–80s price blackout every
five minutes, upstream of us), or `LOAN_NOT_FOUND` on settled loans.

⚠ **`0 buildable` has been struck from this list.** When it was written the scan read
`0 buildable`; it now reads **`2 buildable`**, and a seventh bond appeared overnight. **A drop back
to `0 buildable` after the redeploy would therefore be a REGRESSION, not the quiet market it would
have been yesterday** — the population moved under the analysis, which is exactly why a measurement
in a document needs a date on it.

## 8. What this redeploy is worth

**Two purposes, and the second is guaranteed to pay.**

1. *Conditional:* if anything is buildable, it is the first end-to-end exercise of eleven commits
   of builder change against real chain state — the coverage no green test provides.
2. **Unconditional: it is the only way to read the census.** The instrument shipped to the branch,
   not to the cluster, and those are different places. **Whatever the delta says, we learn
   something we cannot learn any other way** — including, if it is non-zero, that some of the five
   "not found" loans are alive and we are blind to them.
