# Upgrading a running Aquarium Node

For an operator who **already has a node running**. First-time setup is
[deploying.md](deploying.md).

Most upgrades are a version bump and a restart. The exception is a **contract redeploy**, and it is
the one worth understanding before you meet it, because the failure mode is silence rather than an
error.

---

## 1. Which kind of change is this?

| what changed | what you do | database |
|---|---|---|
| New image, same contracts | pull, restart | untouched |
| A setting in `.env` | restart | untouched |
| You added a **market**, margin, or mode | restart | untouched |
| You enabled the **UI** | restart | untouched |
| Upgrading to the release that reads **oracle liveness from the index** (FAB-196) | pull, restart — nothing else required; a re-sync with the §1.1 steps is optional ([§7](#oracle-liveness-is-read-from-the-index-first-fab-196)) | untouched, unless you choose to re-sync |
| Upgrading to the release that adds **`CONVERT_BEFORE_ANTICIPATE`** and keeps **U in the check** (FAB-194) | pull, restart — nothing else required; it changes how every `shadow` or `live` node funds liquidations from U ([§7](#u-stays-in-the-check-convert_before_anticipate-fab-194)) | untouched |
| **FluidTokens redeployed the contracts** (new config NFTs) | §4 — new coordinates **and a full re-sync** | ⛔ **wiped** |
| FluidTokens replaced **action scripts in place** (same config NFTs — e.g. 2026-10-01, 2026-10-23/26) | §4.8, §4.9 — new image, restart | untouched |
| You changed `sync-start-*` | §3 — on an existing database this **does nothing** by itself | ⛔ **wiped, or no effect** |
| Upgrading to the release that **indexes the Minswap V2 pools** (FAB-137) | §1.1 — wipe **including the cursor**, re-sync from `sync-start` | ⛔ **wiped** |
| You changed `WALLET_ACCOUNT_INDEX` | §9 — restart, then re-sync **or** one self-send | ⛔ **wiped, or a self-send** |

The first six are ordinary. The others are the subject of this guide (§4, §4.8, §4.9, §3, §1.1, §9).

### 1.1 ⛔ The Minswap pool release requires a database wipe (FAB-137)

**Upgrading to this release requires wiping the database, INCLUDING the cursor, then re-syncing from
`sync-start`.** This is not optional and there is no partial route.

This release adds the Minswap V2 pool payment credential to the set of credentials the index keeps
(the convert path needs to read a pool's state from the index). The index filters at write time: an
output under a credential the running image did not watch was discarded when its block passed, and
nothing records that it was. A node started on the new image over the old database resumes from its
stored cursor, so it watches the pools **only from the upgrade onwards** — and convert reports a pool
as "not indexed" until that pool happens to swap again. Re-indexing from `sync-start` is the only way
to have every pool in the index.

Do it with the same steps as a redeploy, without changing any coordinates:
[§4.2 stop the node](#42-stop-the-node-leave-the-database-up) →
[§4.3 back up if you want to](#43-back-up-if-you-want-to-be-able-to-go-back) →
[§4.4 wipe](#44-wipe) (deleting the database deletes the cursor with it; a row-only wipe that keeps
the cursor achieves nothing — §3) → [§4.6 start and let it catch up](#46-start-and-let-it-catch-up).
**Keep the shipped `sync-start-*` values** (skip §4.5): starting later would hide the pools, the
contracts and the wallet history the node needs.

The same release turns on Yaci's UTxO pruning (`store.utxo.pruning-enabled: true`,
`store.utxo.pruning-interval: 600`): every 10 minutes it deletes rows for outputs spent more than
2160 blocks ago. Unspent outputs are never pruned. Nothing for you to set.

### 1.2 The readiness page's USD now goes through USDCx, not USDM (FAB-182)

The default of `LOANS_UI_USD_REFERENCE_UNIT` moved from USDM to **USDCx**
(`1f3aec8bfe7ea4fe14c5f121e2a92e301afe414147860d557cac7e345553444378`). It is the page's one ADA/USD
rate — the summary's USD figure and the new prices strip — so after upgrading, the USD figures you see
are priced through USDCx and the page says so by name. Display only: nothing the bot decides reads it,
and the database is untouched. To keep USDM, set
`LOANS_UI_USD_REFERENCE_UNIT=c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad0014df105553444d`
and restart.

---

## 2. The ordinary upgrade

```bash
cd docker
# pin the new version in .env, e.g. AQUARIUM_DOCKER_IMAGE_VERSION=2026.09.22-413f16cb32fb
docker compose pull
docker compose up -d
docker compose logs -f aquarium
```

Check afterwards:

```bash
curl -s http://localhost:8080/healthcheck | jq .
```

**Confirm you are running what you think you are.** `/healthcheck` answers "is it working", not
"what is it" — the commit is on `/actuator/info`:

```bash
curl -s http://localhost:8080/actuator/info | jq .build
```

If the version you pinned and the commit reported disagree — or `dirty` is `true` on an image you
expected CI to have built — stop and find out why before arming anything.

**The comparison is direct.** A tag is `date-commit`, and the commit half is the same twelve
characters `/actuator/info` reports as `commitShort`. So `2026.09.22-413f16cb32fb` running clean
reports `413f16cb32fb`, and anything else is worth stopping for.

⚠ **Tags before 2026-09-22 are the date alone**, with no commit. Those are still valid pins and
still resolve — but two of them were published twice on their day, so an older date-only tag does
not uniquely identify an image the way a current one does.

**⚠ Pin versions.** With `latest`, a restart months from now silently changes what you run, and
there is no record of what it used to be.

**`SCHEDULING_TRANSACTION_PROCESSOR_OGMIOS_URL` has been removed (FAB-149).** It was a diagnostic
for the 2026-09-22 tank decode failure and only ever changed anything when it was set: it sent the
tank's script-cost evaluation to an Ogmios instance instead of Blockfrost. If your configuration
still sets it, the node ignores it and evaluates through Blockfrost, exactly as a node that never
set it always has. You can delete the line.

---

## 3. ⛔ The rule that surprises people

**The node only ever indexes UTxOs at credentials it knew about at the moment each block went past.**

`TankUtxoStorage` builds its credential set **once, at startup**, and drops everything else **at
write time**. The repository says it plainly:

> `saveUnspent` drops everything not in it, at write time, leaving no trace the row was ever
> offered — while the cursor advances regardless. A credential added later therefore only ever sees
> blocks from that moment on; the ones that passed meanwhile are unrecoverable short of a cursor
> delete and a full re-sync. **A filter that narrows at startup is a filter that loses history.**

Three consequences, and the third is the one that bites:

1. **Adding a credential does not backfill.** Configure a new contract address today and the node
   sees its UTxOs from today's block onward. Anything created before that is simply not in your
   database, and nothing reports it missing.
2. **The stored cursor beats your configuration.** `sync-start-slot` / `sync-start-blockhash` are
   read only when there is no cursor yet. On an existing database the cursor wins, so **editing
   those values and restarting re-indexes nothing.** No error, no warning — it just keeps going from
   where it was.
3. **Therefore, after a contract redeploy, a restart is not enough.** The new contracts' UTxOs were
   created at blocks your node has already passed, while it was still filtering for the old
   credentials. It discarded them, the cursor moved on, and they are gone. The node then starts
   cleanly, verifies cleanly, and reports **zero candidates** — which is indistinguishable from a
   quiet market.

**⇒ Treat a persistently empty world on a synced node as a suspected redeploy, not as a quiet
market.**

---

## 4. Contract redeploy — the full sequence

FluidTokens redeploys when contracts change. New config policy, new script addresses, new reference
scripts. **The image ships the new coordinates — you never type one** — but **your database still
describes the old world**, and that part no image can fix for you.

⚠ **Not every redeploy needs this.** FluidTokens sometimes move only part of the contract set. If
the part that moved is a validator this node never invokes, the node starts and logs:

```
⚠ Lending v4: N config field(s) do not match the chain, in validators THIS NODE NEVER INVOKES.
```

**That is not this section.** Nothing is wrong with your deployment, no re-sync is needed, and the
fix is a future image. Only a startup **failure** — which names the offending field — means the
deployment genuinely moved under you and the sequence below applies.

Order matters here. Do not improvise it.

### 4.1 Disarm first

```bash
# in docker/.env
AQUARIUM_LIQUIDATION_MODE=disabled
AQUARIUM_COMPOUND_ENABLED=false
SCHEDULING_TRANSACTION_PROCESSOR_ENABLED=false
```

```bash
docker compose up -d
```

**Why first:** during a re-sync the node's view of the chain is deliberately incomplete. A bot
acting on a partial view is making decisions from a half-loaded picture. Disarming costs one
restart; not disarming is a bet.

### 4.2 Stop the node, leave the database up

```bash
docker compose stop aquarium-node
```

Stop the **node**, not the stack. Wiping a database out from under a running writer is how you get a
corrupt volume instead of an empty one.

### 4.3 Back up, if you want to be able to go back

```bash
docker compose exec postgres pg_dump -U "$DB_USERNAME" "$POSTGRES_DB" | gzip > ~/aquarium-pre-upgrade.sql.gz
```

**What is actually in that database:** the indexed UTxO set and the chain cursor. Nothing else.

**What is NOT in it, and so cannot be lost by wiping:** your wallet (it is a mnemonic in `.env`),
your configuration (`.env`), your FLDT delegation (on chain), and the decision log (held in memory,
gone at every restart regardless).

So the backup is for **going back to the old coordinates**, not for protecting anything irreplaceable.
Many operators will skip it. A re-sync rebuilds everything the database holds.

### 4.4 Wipe

```bash
docker compose down
sudo rm -rf ./postgres        # the bind mount in docker-compose.yaml
```

**⛔ Check what that path is before you run it.** `./postgres` is what the shipped Compose file
uses. If you changed it, change this. If you moved it to a named volume, use
`docker volume rm <name>` instead.

### 4.5 Set the start point

A fresh database reads `sync-start-*` again — this is the only moment those values do anything.

**Start just before the redeploy**, not from genesis. Syncing hundreds of days to find contracts
deployed last week wastes days for nothing. FluidTokens publish the deployment block with a
redeploy; pick a block a little before it.

**⚠ The trade-off, stated plainly: a node started after a given block can never see anything before
it.** For a fresh contract deployment that is exactly right — there is nothing earlier to see. But
it is a **one-way door for that database**: if it turns out you needed earlier history, the only
route back is another wipe and another sync.

### 4.6 Start, and let it catch up

```bash
docker compose up -d
docker compose logs -f aquarium
```

Wait for the tip (deploying.md §6). **Do not arm anything yet.**

### 4.7 Verify before re-arming

```bash
curl -s http://localhost:8080/healthcheck | jq .
curl -s http://localhost:8080/api/v1/loans | jq 'length'
```

Startup verification is the real check, and it runs **at boot only**. It has two halves, and they
react differently:

- **Config datums (`LoansConfigVerifier`) — strict.** Since FAB-115 a mismatch no longer stops the node.
  If the config policy ids or `smart-tokens-spend-script-hash` do not match what the node derives, it
  **closes the lending gate**: every Lending v4 transaction (liquidation in every mode, convert,
  compound) is refused for the life of the process (`LENDING_CONFIG_MISMATCH`), while scheduled
  payments keep running.
- **Reference-script coordinates (`LoansReferenceScriptVerifier`) — warn and carry on.** Since FAB-108
  a bad coordinate **never closes the gate**. `REFERENCE_SCRIPT_DROPPED` (absent, spent, no reference
  script, or a hash other than the derived one): the coordinate is not used and its validator travels
  inline — a larger transaction, refused as `TX_TOO_LARGE` if it no longer fits `maxTxSize`.
  `REFERENCE_SCRIPT_UNVERIFIED` (the provider could not be asked): used as configured.
  `REFERENCE_SCRIPT_MISSING` (compound only, and only with `loans.compound.enabled=true`): a compound
  validator no usable coordinate supplies, which travels inline. Only
  `loans.verify-config.fail-on-unreachable=true` still stops the boot, and only on an UNVERIFIED named
`loans.liquidation.reference-scripts.*` key; an UNVERIFIED compound-list entry never does.

⚠ **A healthy `/healthcheck` does not mean lending may build** — the gate is reported, not part of the
verdict, because the scheduled payments it guards keep running. Read the gate before re-arming:

```bash
curl -s http://localhost:8080/healthcheck | jq '{lending_gate, lending_gate_reason}'
# Prometheus: aquarium_lending_gate_closed  (1 = closed, 0 = open) — worth an alert
# ⚠ While the node is still syncing, /healthcheck answers the plain text "...syncing..." and the jq
#   line fails — read the gauge, or wait for the sync to finish.
```

The startup log says the same, with every mismatched field:

```bash
docker logs <node> 2>&1 | grep -E "LENDING_CONFIG_MISMATCH|Lending v4 config verified|reference scripts verified|REFERENCE_SCRIPT_(DROPPED|UNVERIFIED|MISSING)"
```

- `Lending v4 config verified against chain …` and `… reference scripts verified against chain …` — good.
- Any `LENDING_CONFIG_MISMATCH` line — the gate is closed; the line names each field (derived vs chain).
  Fix the coordinates (`loans.config.policy-id`, `loans.lm-config.policy-id`,
  `loans.smart-tokens-spend-script-hash`) and restart.
- Any `REFERENCE_SCRIPT_DROPPED`, `REFERENCE_SCRIPT_UNVERIFIED` or `REFERENCE_SCRIPT_MISSING` line — the
  gate stays open and lending goes on; the line names the key or the compound entry. Fix
  `loans.liquidation.reference-scripts.*` / `loans.compound.reference-scripts` and restart.
- With the readiness UI enabled, the page also shows a `LENDING_CONFIG_MISMATCH` banner for the first, and a
  **REFERENCE SCRIPTS** banner (one line per problem, saying lending is NOT refused) for the second.

**Then re-arm in stages** — `shadow` first, read the decisions, then `live`. Same sequence as a first
deployment (deploying.md §8). A redeploy is a good moment to rehearse rather than assume.

### 4.8 When FluidTokens replace action scripts IN PLACE — the 2026-10-01 update

Not every redeploy is §4. On **2026-10-01** FluidTokens shipped security fixes (FTAI-001/002/102) by
rewriting their two config datums **in place**: same config NFTs, new hashes for the claim action and
for the LenderManager liquidate and compound actions.

The node checks the deployment **at startup only**, so nothing changes in a node that is already running —
it keeps the coordinates it booted with, and its liquidations fail at evaluation. What you see depends on
the image you restart with:

- an image with the lending gate but **from before this release** starts, keeps scheduled payments
  running, and closes the gate:
  ```
  LENDING_CONFIG_MISMATCH … ConfigDatum[11] … LMConfigDatum[2] … LMConfigDatum[3] … LMConfigDatum[4]
  ```
- an **older** image refuses to start at all — scheduled payments included.

**What to do:** pull the image built from this release and restart.

- **No wipe, no re-sync.** Only action hashes moved. Every payment credential the node indexes — the two
  config NFT policies and the loan, pool, request, asset-manager, locked-borrower-manager, LenderManager
  and pool-manager spend scripts — is unchanged, so the existing index is correct. Following §4 here
  would cost a full re-sync for nothing.
- **No `.env` change** if you use the shipped defaults (neither `.env.example` nor
  `.env.advanced.example` sets the keys below).
- ⚠ If you **override** reference-script coordinates, update them to the new defaults in
  `application.yaml` or unset them:
  - `AQUARIUM_LIQUIDATION_REF_LOAN_CLAIM_ACTION`, `AQUARIUM_LIQUIDATION_REF_LM_LIQUIDATE_ACTION`,
    `AQUARIUM_LIQUIDATION_REF_LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION`,
    `LOANS_LIQUIDATION_REFERENCE_SCRIPTS_LM_LIQUIDATE_AND_CONVERT_ACTION` — these are **verified at boot**:
    a stale one is a `REFERENCE_SCRIPT_DROPPED` WARN naming the key, and its validator travels inline
    (the gate stays open; FAB-108).
  - `AQUARIUM_COMPOUND_REFERENCE_SCRIPTS` — **verified at boot only while `loans.compound.enabled=true`**
    (FAB-108). A stale entry is then a `REFERENCE_SCRIPT_DROPPED` WARN and is not referenced, and the
    script it should have supplied is a `REFERENCE_SCRIPT_MISSING` WARN and travels inline. With compound
    off it is not checked: compare it with the shipped default by hand, or unset it.
- **Verify exactly as §4.7.** Expect one WARN for `ConfigDatum[14]` (recast): FluidTokens point it at a
  pause hash by design, and the node never recasts. It does not close the gate.

⚠ **Gate open is not the same as ready.** Every liquidation withdraws through the new action scripts, so
their **reward accounts must be registered** on chain, or each submit fails with
`ConwayWithdrawalsMissingAccounts` — which no evaluation, dry-run or `shadow` decision can show you.
FluidTokens registered them on 2026-10-01. To check, look up each stake address and confirm its most
recent registration action is `registered` (e.g. Blockfrost
`/accounts/{stake}/registrations?order=desc&count=1`):

| action script | stake address |
|---|---|
| loan claim | `stake1794x72hvctkwz0xrthmzt3ztdpjaqlyz7rs8e56ppaxzeps7acgae` |
| LenderManager liquidate | `stake17x85dymnutx0w2vdpwdnh9c6sthx4zwzem40j3rqjqexcyqe9uttz` |
| LenderManager liquidate + pay in advance | `stake17y8qqqmyl7h8uemg7uvjfzzcel2rj4q6ulht9el5nv428qq6w773h` |
| LenderManager liquidate + convert | `stake1789l86x95shx6r6s24qdt23pqjm79x5u8fadukqa7a96waqgtc0ql` |
| LenderManager compound — the **2026-10-01** script, **superseded on 2026-10-26**: the current one is in §4.9 | `stake179c4zk5fl5uezekc68n2v02fsghuy6ztweuzezal87qhnqsq0fld4` |

For a future update: the node logs every hash it derives at startup (`Derived Lending v4 contract hashes`),
and a script's stake address is its hash under the CIP-19 script-stake header (`stake17…` on mainnet).

### 4.9 When FluidTokens replace action scripts IN PLACE — the 2026-10-23/26 update

The same kind of change as §4.8, done twice in four days: FluidTokens rewrote their two config datums
**in place** again. The config NFTs are the same; three action scripts have new hashes:

| when (UTC) | config output now | field | old → new | in this node |
|---|---|---|---|---|
| 2026-10-23 10:45, block 14,105,271 | ConfigDatum `75e459a0…#0` | [24] pool: sell lender position | `efa39cdb…` → `f43354cb…` | advisory, never invoked |
| 2026-10-26 13:40, block 14,118,734 | LMConfigDatum `c0f0d821…#0` | [3] LenderManager compound | `71515a89…` → `4713a925…` | **enforced** |
| 2026-10-26 13:40, block 14,118,734 | LMConfigDatum `c0f0d821…#0` | [6] LenderManager liquidate + pay in advance + compound | `cd33a2aa…` → `00e38efa…` | advisory, never invoked |

The compound script is the one that matters. Its new version charges ADA pools a minimum fee of 1 ADA
(see "The compound fee changed" below).

As in §4.8, the node checks the deployment **at startup only**, so what you see depends on whether you
restart, and on which image:

- A node that is **already running** keeps the verdict it booted with. Liquidation and convert invoke none
  of the three scripts that moved, but every compound it builds now fails evaluation at withdrawal index 3.
- An image **from before this release** (and from the 2026-10-01 release or later), once restarted, keeps
  scheduled payments running and closes the lending gate. That stops **liquidation and convert too**, not
  only compound, whether or not you ever enabled compound:
  ```
  LENDING_CONFIG_MISMATCH … LMConfigDatum[3]
  ```
  It also logs advisory WARNs for `ConfigDatum[14]`, `ConfigDatum[24]` and `LMConfigDatum[6]`. Those
  do not close the gate.
- An image older than the 2026-10-01 release is already broken by §4.8. This release fixes both.

**What to do:** pull the image built from this release and restart.

- **No wipe, no re-sync.** Only action hashes moved. Every payment credential the node indexes is
  unchanged, so the existing index is correct (`MainnetRedeploy20261026Test`).
- **No `.env` change** if you use the shipped defaults. None of the liquidation reference-script
  coordinates changed.
- ⚠ If you **override** `AQUARIUM_COMPOUND_REFERENCE_SCRIPTS`, replace
  `29f63a1e1e7b268481df871d969b1b250b437a4d9a82aa7bfaf7b6f6252dc946#0` (the old compound script) with
  `588695471b9f865ceac88066883fad118a5a1800c9c69a0663e5ff560d656b1a#0`, or unset the key. The other ten
  entries are unchanged. The old output is still unspent, so nothing on chain tells you it is stale. With
  compound enabled the node does: the stale entry is a `REFERENCE_SCRIPT_DROPPED` WARN, and the compound
  script it should have supplied is a `REFERENCE_SCRIPT_MISSING` WARN and travels inline (FAB-108). With
  compound off, nothing checks the list. Do **not** add `047020cf…#0`. It publishes the new liquidate + pay
  in advance + compound script, which this node never invokes.

**The compound fee changed, and with it what the shipped margin approves.** This matters only if you
enabled compound (`AQUARIUM_COMPOUND_ENABLED=true`; the default is `false`).

- A compound pays the bot a fee set by the pool owner's rate `c`, per mille of the compounded principal
  `Q`. Since 2026-10-26 an **ADA** pool always pays `max(Q*c/1000, 1 ADA)`, so it pays at least 1 ADA even
  at rate 0. A **token** pool still pays `Q*c/1000` raw units, which is nothing at rate 0.
- ⚠ **So at the shipped `AQUARIUM_COMPOUND_PROFIT_MARGIN_LOVELACE=0`, a zero-rate ADA compound is now
  APPROVED** whenever its transaction fee is at most 1 ADA. Before this update it earned nothing and was
  refused. Measured on a mainnet-shaped compound: the fee was 1 ADA, the transaction fee 842,009
  lovelace, and the bot netted +157,991 lovelace.
- **To keep refusing them,** raise `AQUARIUM_COMPOUND_PROFIT_MARGIN_LOVELACE` in `.env` and restart. A
  zero-rate ADA compound nets `1 ADA − transaction fee`, so `1000000` refuses every one of them. The margin
  is shared by every compound, so it also refuses any other compound that would net less than 1 ADA.
- A negative margin (compounding at a loss you state) behaves exactly as before.
- Escrows written by this node's own liquidations are either compoundable under the new script or
  refused because of their actual shape (FAB-168, `LiquidationEscrowCompoundRoundTripTest`).
- Preview: the node derives the new compound script on every network, but preview's deployed compound
  validator still charges `Q*c/1000`. This does not matter while the preview lending gate is closed.

**The new compound script has its own reward account.** FluidTokens registered it (checked 2026-10-26).
Check it the same way as the §4.8 table if you enable compound:

| action script | stake address |
|---|---|
| LenderManager compound (since 2026-10-26, `4713a925…`) | `stake179r382f9xqnfypmtdphwu00gp93ujnjxgyy9y2h6euvs88qmpx069` |

The other rows of the §4.8 table are unchanged and still current.

**Verify exactly as §4.7.** Expect `Lending v4 config verified against chain …`, the gate open, and one
advisory WARN, for `ConfigDatum[14]` (as in §4.8). If you still see `LENDING_CONFIG_MISMATCH …
LMConfigDatum[3]`, the node is not running this release: compare `/actuator/info` with the tag you pinned
(§2). With compound enabled, also expect `… reference scripts verified against chain …` and no
`REFERENCE_SCRIPT_*` line.

---

## 5. Rolling back

**The image rolls back cleanly:** pin the previous version and restart.

**The database does not roll back with it.** If you re-synced from a later start point, the old
coordinates' history is not there any more, and going back to the old image means going back to an
empty world. That is what the §4.3 dump is for.

**⇒ The practical rule: an image rollback is cheap; a re-sync is not reversible without a backup.**

---

## 6. Routine maintenance

**Disk** grows with the chain. Watch the `./postgres` directory; it shares a disk with your logs.
Since FAB-137 the node prunes **spent** UTxO rows (§1.1), but the rows of unspent outputs and every
other table still grow.

**Re-syncing to reclaim space** is legitimate — a later start point means a smaller database — and
it is the same §4 sequence with the same one-way-door caveat.

**Upgrading Postgres major versions** is a Postgres operation, not an Aquarium one, and the
bind-mounted data directory is not portable across major versions. Simplest path: wipe and re-sync
onto the new version, treating it as §4 without the coordinate change.

**Before any maintenance that stops the node, disarm it.** A bot that stops mid-decision is fine —
it submits nothing and re-scans on restart — but a bot that is running while you are working on its
database is an avoidable variable.

---

## 7. Keys that no longer exist

If you are carrying a configuration forward from an older node, **remove these.** Most are ignored
in silence, which reads exactly like them working.

| removed key | why, and what happens if you still pass it |
|---|---|
| `LOANS_LIQUIDATION_CONVERT_PROFIT_MARGIN_LOVELACE` | merged into the single shared margin. A config still setting it gets the **shared default**, not the number it believes it set |
| `AQUARIUM_LIQUIDATION_ENABLED` | redundant with `AQUARIUM_LIQUIDATION_MODE` |
| `LOANS_ENABLED` | lending-v4 indexing is unconditional now |
| `AQUARIUM_X_SUBMIT` / `loans.submittable-network` | a barrier that silently blocked submission while everything else was armed is a bug, not a safeguard |
| `SCHEDULING_TRANSACTION_PROCESSOR_OGMIOS_URL` | a diagnostic for the 2026-09-22 tank decode failure (FAB-149). Still set, it is ignored and the tank is evaluated through Blockfrost, as on every node that never set it — see §2 |
| `AQUARIUM_LIQUIDATION_DELAY_SECONDS` / `loans.liquidation.delay-seconds` | removed by FAB-183: liquidation no longer runs on a timer (see [below](#liquidation-compound-and-rebalancing-run-on-blocks-and-prices-not-timers-fab-183)). Still set, it is ignored, and the node logs one WARN for it at boot |
| `AQUARIUM_COMPOUND_DELAY_SECONDS` / `loans.compound.delay-seconds` | removed by FAB-183: compound no longer runs on a timer (see [below](#liquidation-compound-and-rebalancing-run-on-blocks-and-prices-not-timers-fab-183)). Still set, it is ignored, and the node logs one WARN for it at boot |

**The general shape is worth keeping in mind beyond this list:** an unknown environment variable is
not an error anywhere in this stack. It is read by nothing and reported by nothing. When a setting
seems to have no effect, confirm it reached the process and that the name still exists:

```bash
docker compose exec aquarium-node env | grep AQUARIUM_
```

### The startup wallet rebalance is gone (FAB-136)

Older images listed the wallet at startup and, if any of its UTxOs predated the index, spent the
whole wallet back to itself before letting anything run. **That no longer happens**, and with it
**`/healthcheck`'s `wallet_sweep` field is gone** — if your monitoring reads that field, stop: it
will not come back, and a missing field reads exactly like a check that never ran.

What replaces it is a requirement on you, stated in [deploying.md §2](deploying.md#2-keys-and-secrets--read-this-before-creating-anything):
**the wallet must hold no UTxO created before 2025-05-06** (mainnet slot `154984561`, where the
node's index starts). The node reads its wallet only from that index, so an older UTxO is invisible
to it. **If you are not sure, send the wallet's whole balance to the node's own address once — a
single self-send — before restarting on the new image.**

New, optional: `AQUARIUM_SYNCING_THRESHOLD_MINUTES` (default `10`). The node counts as syncing — no
processing, `/healthcheck` answers `...syncing...` — while its last applied block is more than this
many minutes old.

### Wallet rebalancing is on by default

**This is a different mechanism from the startup rebalance above, and it is continuous.** The removed
one ran once at boot; this one watches the wallet on every block. `AQUARIUM_WALLET_REBALANCE_ENABLED`
defaults to `true`, and it acts when the node is in `shadow` or `live`, whatever its markets
([FAB-188, below](#every-shadow-or-live-node-now-keeps-its-wallet-shaped-fab-188)) — see
[deploying.md §9, "Wallet rebalancing"](deploying.md#wallet-rebalancing--the-shape-a-shadow-or-live-node-keeps-your-wallet-in).
A node in `disabled` mode is unaffected.

**If your node meets that condition, expect a transaction right after upgrading.** On the first block
once the node is synced, if the wallet is not already in the target shape, the node **signs and
submits one self-send of its whole wallet — in `shadow` too** — and it does so again whenever the
wallet drifts out of shape. Each one costs a transaction fee.

- **Funds.** Shaping needs 10 ADA beside the token of each `ANTICIPATE` or `CONVERT_BEFORE_ANTICIPATE`
  market the wallet holds ([FAB-194, below](#u-stays-in-the-check-convert_before_anticipate-fab-194)),
  10 ADA beside the bundle of any other tokens, and a 5 ADA collateral UTxO (under current protocol
  parameters), plus the fee, **plus a change output that still holds its own minimum ada after paying
  that fee** (about 1 ADA) — so one such token needs about 16.2 ADA and an ada-only wallet about 6.2 ADA
  (approximate: the figures move with the protocol parameters and the number of wallet UTxOs). A
  wallet with less is not touched: the node logs `[REBALANCE] REFUSED: …` at WARN on every block
  (an empty wallet: `[REBALANCE] NOTHING_TO_DO: no input UTxOs…`) and submits nothing, so a refusal
  costs no fee.
- **⛔ Move reference scripts and datum UTxOs out of the bot wallet BEFORE upgrading.** The self-send
  spends every wallet UTxO and recreates no datum, so a datum UTxO is consumed. A reference-script
  UTxO is expected to make every self-send fail at submission — `[REBALANCE] submission rejected` at
  ERROR on every block — so the wallet is never shaped. (One such ERROR right after a successful
  rebalance is only its duplicate being refused, and is harmless.)
- **Moving funds by hand?** Press **`Suspend rebalancing`** on the readiness page first
  (`LOANS_UI_ENABLED=true`, [deploying.md §7](deploying.md#wallet-rebalancing-on-the-page)), and
  **`Resume rebalancing`** when you are done, so the node does not reshape the wallet under you. The
  suspension is held in memory: a restart resumes rebalancing.
- **To opt out**, set `AQUARIUM_WALLET_REBALANCE_ENABLED=false` and restart. The readiness page
  cannot turn it back on.

### Liquidation, compound and rebalancing run on blocks and prices, not timers (FAB-183)

Older images ran liquidation and compound each on its own timer (`AQUARIUM_LIQUIDATION_DELAY_SECONDS`,
`AQUARIUM_COMPOUND_DELAY_SECONDS`, both 60 s by default). **Those timers are gone**, and so are the two
settings (the table above). The bot wallet now has one queue of work, a *pass*, and a pass runs:

1. **Liquidation.** Every candidate is tried, most profitable first, in every market. A loan whose
   submit is rejected, or whose outcome is unknown, is skipped and the next candidate is tried — see
   [Fire and forget (FAB-186)](#fire-and-forget-fab-186) below, which replaced this release's original
   rules (at most one liquidation per market per pass, and an unknown outcome ending the stage).
2. **Compound.**
3. **Housekeeping** — the wallet rebalance described above.

**After a liquidation that Blockfrost accepted, compound and housekeeping wait for the next trigger.**
The same rule holds between compound and housekeeping. A liquidation that was rejected, or whose outcome
is unknown, does not hold them back (FAB-186). In `shadow` a liquidation the node *would* have submitted
sent nothing, so it does **not** hold them back either.

**What starts a pass**, with no timer anywhere:

- a **committed block**, once the node is in sync (a node working through its initial sync starts none);
- a change in the **merged oracle prices**.

A pass already running is never joined by a second one; new triggers wait for it to finish.

**The settings that govern this:**

- `WALLET_COORDINATOR_PASS_TIMEOUT_SECONDS` (default `120`) — the pass deadline. It bounds every
  Blockfrost call made during a pass.
- `LOANS_ORACLE_SOURCE` — where the prices come from: `stream` (the default) merges the FluidTokens
  oracle stream with the registry API; `api` is the emergency lever, API polling only.
- `WALLET_PIPELINE_LOG_EVERY_PASS` (default `true`, FAB-195) — every pass ends with one INFO line:
  `wallet pipeline pass #<n> at slot <slot> (<hash>), trigger <block|price|block+price>, <k> block(s) folded in, waited <s> s, took <s> s: liquidation <outcome>, compound <outcome>, housekeeping <outcome>; Blockfrost calls discovery <n>, evaluate <n>, submit <n>`.
  *Folded in* counts the blocks a newer block replaced while they waited; *waited* runs from the first
  trigger the pass serves to its start; each `<outcome>` is described in
  [Stage outcomes on the pass line (FAB-195)](#stage-outcomes-on-the-pass-line-fab-195). `false` returns to
  INFO only when a pass did something (the rule in that entry), DEBUG otherwise.

**The Tank processor is unchanged:** it keeps its own timer, and none of the above applies to it.

**Nothing is remembered between passes.** A transaction the node submitted and that is still pending
may be built again on the next trigger; the ledger rejects the duplicate harmlessly, because the pending
transaction already spends its inputs.

### Every shadow or live node now keeps its wallet shaped (FAB-188)

Older images rebalanced the wallet only when at least one market was `action: ANTICIPATE` with an
effective mode of shadow or live. **That condition is gone.** Every node in `shadow` or `live` keeps its
wallet shaped, whatever its markets — a node with only `CONVERT` markets, or none listed, included —
because liquidation depends on a shaped wallet. See
[deploying.md §9, "Wallet rebalancing"](deploying.md#wallet-rebalancing--the-shape-a-shadow-or-live-node-keeps-your-wallet-in).

- **Expect one self-send right after upgrading** on a `shadow` or `live` node whose wallet is out of
  shape — **in `shadow` too** — for example a wallet whose ada sits in several UTxOs, or that holds no
  ada-only UTxO of exactly the collateral size. It costs a transaction fee. A preview node runs in
  `shadow` by default, so this applies to it.
- **Funds.** The shape is unchanged: 10 ADA beside each `ANTICIPATE` token the wallet holds (since
  [FAB-194](#u-stays-in-the-check-convert_before_anticipate-fab-194), the token of each `ANTICIPATE` or
  `CONVERT_BEFORE_ANTICIPATE` market), 10 ADA
  beside the bundle of any other tokens, a 5 ADA collateral UTxO (under current protocol parameters),
  plus the fee and a change output that still holds its own minimum ada after paying it — so an
  ada-only wallet needs about 6.2 ADA. A wallet with less is not touched: the node logs
  `[REBALANCE] REFUSED: …` at WARN on every pass in which the rebalancer runs and submits nothing.
- **Any deposit triggers a reshape.** Funds you send arrive as a second ada-only UTxO, which puts the
  wallet out of shape, and the next pass reshapes it with a self-send. **That self-send spends EVERY
  wallet UTxO** — the collateral UTxO C and every token UTxO included — **so for about one block, until
  it lands, nothing can be liquidated or compounded**; a candidate built in that window is rejected by
  the ledger and retried on the next pass. A reshape only runs on a pass in which no liquidation or
  compound went out.
- **To opt out**, set `AQUARIUM_WALLET_REBALANCE_ENABLED=false` and restart. On a `shadow` or `live`
  node the node then logs one WARN on every pass in which the rebalancer runs,
  `[REBALANCE] ENV_DISABLED: … liquidation depends on a shaped wallet …` — and `SUSPENDED` in its place
  while rebalancing is suspended from the readiness page.

### Liquidation is funded by wallet role (FAB-185)

Older images picked a wallet UTxO for each liquidation from a list that shrank as the pass went on — the
smallest one that covered the requirement — so a liquidation could be funded from whichever UTxO was
left. **Each liquidation check now reads the wallet once and gives its UTxOs fixed roles:**

- **C**, the ada-only UTxO of exactly the collateral size (5 ADA under current protocol parameters), is
  never handed to a liquidation as its funding UTxO;
- **U**, the largest other ada-only UTxO, pays for plain, convert and ADA-principal anticipate
  liquidations;
- a **token-principal anticipate** is paid for from the UTxO holding the most of that token.

See [deploying.md §9, "Which wallet output pays for what"](deploying.md#which-wallet-output-pays-for-what)
for what each role must hold.

- **A UTxO that went out is not used again in the same check**, and the next candidate funded from the
  same role is skipped (`… skipped: wallet output … (role U) already went out in this check`, at INFO)
  rather than funded from another UTxO. **So two plain liquidations no longer both go out in one pass**
  — they share U; the second goes on a later pass. Several liquidations go out in one pass only when they
  are funded from different roles. (Since FAB-186, below, "went out" means accepted by Blockfrost.)
  **Replaced for U by [FAB-194](#u-stays-in-the-check-convert_before_anticipate-fab-194):** U is no longer
  taken out of the check, so a later candidate funded from U is built and submitted on it; only a token
  UTxO an accepted token-principal anticipate spent is still skipped
  (`… skipped: wallet output … (role TOKEN <unit>) already went out in this check`).
- **A missing role is refused by name.** No C: nothing is built, and one WARN names role C, its size and
  the wallet rebalancer's last outcome. No U, or no token UTxO for a token anticipate: that candidate is
  recorded `WALLET_ROLE_MISSING`, with one WARN per missing role per check. A role UTxO too small for
  its candidate is recorded `WALLET_INPUT_TOO_SMALL`, naming the UTxO, what it holds and what it needed.
- **Unreadable protocol parameters now stop the check** — one line naming role C and the cause (an ERROR
  since [FAB-195](#stage-outcomes-on-the-pass-line-fab-195); it was a WARN) — instead of falling back to the
  largest UTxO: without them C cannot be sized.
- **A shaped wallet is what makes this work** — keep wallet rebalancing on
  ([FAB-188, above](#every-shadow-or-live-node-now-keeps-its-wallet-shaped-fab-188)), and keep reference
  scripts and datum UTxOs out of the bot wallet: they keep it from being shaped, and so stop liquidation.

### Fire and forget (FAB-186)

Older images served **at most one liquidation per market per pass**, treated a submit whose outcome was
unknown as if it had gone out — ending the liquidation stage for that pass — and read the text of a
rejection (`BadInputsUTxO`, an empty `ScriptFailures`) to set a wallet UTxO aside. All three are gone:

- **Every candidate is walked**, most profitable first, in every market.
- **A rejected or unknown submit frees nothing and stops nothing.** A rejection, a raw or 5xx answer, no
  answer, or a submit that threw are one outcome: that candidate failed. Nothing is set aside, and the
  next candidate is tried — on the same UTxO if it is funded from the same role. Each one is said in
  **one INFO line**, never ERROR:
  `liquidation of <loan> via <plain|convert|pay-in-advance> on <UTxO> not accepted: HTTP <code|none|threw> <response>`,
  with the response cut to 300 characters (the decision row keeps all of it). The
  `aquarium.liquidation.submit.outside.validity` counter still counts `OutsideValidityInterval` answers;
  it decides nothing.
- **An accepted submit uses up its UTxO for the pass** — the next candidate funded from it is skipped,
  as in FAB-185 — **and holds compound and housekeeping back** to the next trigger. Since
  [FAB-194](#u-stays-in-the-check-convert_before_anticipate-fab-194) that is true of a token UTxO only:
  **U stays in the check**, and a later candidate funded from it is still built and submitted. An accepted
  submit still holds compound and housekeeping back.
- **Until our accepted transaction is in a block (about 20 s), later passes rebuild candidates on U and
  get harmless rejections** — U is already spent by the pending transaction. They cost Blockfrost calls,
  and each pass now ends with a summary line that shows them:
  `wallet pipeline pass #<n> at slot <slot> (<hash>), trigger <label>, <k> block(s) folded in, waited <w> s, took <d> s: liquidation <outcome>, compound <outcome>, housekeeping <outcome>; Blockfrost calls discovery <n>, evaluate <n>, submit <n>`,
  where each `<outcome>`, and when the line is INFO or DEBUG, are described in
  [Stage outcomes on the pass line (FAB-195)](#stage-outcomes-on-the-pass-line-fab-195) below; the trigger,
  fold and wait fields are described under `WALLET_PIPELINE_LOG_EVERY_PASS`
  ([above](#liquidation-compound-and-rebalancing-run-on-blocks-and-prices-not-timers-fab-183)). By
  default **every pass says it at INFO**.

### Stage outcomes on the pass line (FAB-195)

Older images printed each stage of the pass line as the raw answer the stage returned — `DEFINITE_FAILURE`,
which read like an error and only meant "nothing went out", or `not run` / `threw`. **Each stage now prints
its outcome**, one of six:

- **`NO_OP`** — nothing was sent to the network: there was nothing to do, or every candidate was refused
  by name before submitting.
- **`ATTEMPTED`** — built and submitted, but not accepted.
- **`SUBMITTED`** — accepted into the mempool, not yet on chain (a transaction hash came back). A compound
  or housekeeping submit with no clear answer — a raw or 5xx response, or a submit that threw — also prints
  `SUBMITTED`, because nothing says it did not go out; that is a display merge only.
- **`WOULD_SUBMIT`** — a `shadow` candidate that would have been submitted.
- **`SKIPPED`** — the stage did not run this pass: an earlier stage sent something, the pass was fenced (a
  rollback, the pass deadline, shutdown), or an earlier stage failed.
- **`ERROR`** — our own error: an unexpected build failure (a builder exception, a failed index or
  Blockfrost read, a failed script evaluation — one caused by a lost race too), or a stage that threw. Each
  unexpected build failure is **one ERROR line** carrying the whole cause chain; its stack trace is logged
  at DEBUG.

**Precedence.** A stage prints the strongest that applies:
`SUBMITTED > WOULD_SUBMIT > ATTEMPTED > ERROR > NO_OP`.

**Counts.** The outcome is followed, in brackets, by the stage's non-zero counts, in this order:
`submitted <n>`, `would-submit <n>`, `attempted <n>`, `error <n>`, `no-op <n>`. A stage with no count
prints its outcome alone. For example:

```
wallet pipeline pass #12 at slot 101234567 (ab12…), trigger block, 0 block(s) folded in, waited 0.4 s, took 0.0 s: liquidation ATTEMPTED (attempted 1, error 1, no-op 3), compound NO_OP, housekeeping SKIPPED; Blockfrost calls discovery 5, evaluate 2, submit 1
```

A liquidation `ATTEMPTED` is not explained on the pass line: each of its candidates has its own
`liquidation of <loan> via <route> on <UTxO> not accepted: HTTP …` INFO line
([Fire and forget, above](#fire-and-forget-fab-186)).

**What ends a pass is unchanged:** only a liquidation or compound that went out (or whose outcome is
unknown) holds the later stages back. The counts are for reading only.

**When the line is INFO.** With `WALLET_PIPELINE_LOG_EVERY_PASS=true` (the default) every pass says it at
INFO. With `false` it is INFO when any stage is neither `NO_OP` nor `SKIPPED`, or the pass evaluated or
submitted anything, and DEBUG otherwise. **A pass with an `ERROR` is now INFO where it was DEBUG.**

### Oracle liveness is read from the index first (FAB-196)

Before every liquidation, pay-in-advance and convert build, the node checks that each oracle output the
transaction will reference — the price feed, and for a Charli3 feed its provider — is still unspent and
holds its NFT. Older images asked Blockfrost about every one of them. **The node now asks its own index
first:**

- an oracle output the index holds unspent, with its NFT, passes with **no Blockfrost call**;
- one the index recorded as spent is refused **with no Blockfrost call**, naming the transaction that spent
  it;
- anything the index does not know is checked on Blockfrost, **exactly as before**.

To have something to answer from, the index also keeps every output holding a token of the oracle NFT
policies in `loans.oracle.indexed-nft-policies`, whatever its address. That key is shipped per network in
the image's configuration (on mainnet, the Lending v4 feed policy) and **needs no setting**. Like every
write-time filter in this node, it applies only to the blocks the node indexes from then on: changing it,
or upgrading to an image that changes it, **re-indexes nothing by itself**
([§3](#3--the-rule-that-surprises-people)).

What that means depends on your node:

| your node | what happens | what you do |
|---|---|---|
| **An existing node, not re-synced** | Oracle outputs created before the upgrade are not in its index — on mainnet that is all 16 Lending v4 feeds, created 2026-09-30 — so their checks ask Blockfrost exactly as before. Oracle outputs created after the upgrade are indexed and answered from the index. | nothing — it keeps working |
| **Re-synced with the [§1.1](#11--the-minswap-pool-release-requires-a-database-wipe-fab-137) steps, keeping the shipped `sync-start-*`** | The index answers for every oracle output under the shipped policies: a live one costs no Blockfrost call, so the pass line's `discovery` count drops; a spent one is refused from the index. | optional — re-sync only if you want that now |
| **A fresh install** | As a re-synced node. | nothing |
| **A feed spent long ago** | Its rows are pruned about 2160 blocks after the spend ([§1.1](#11--the-minswap-pool-release-requires-a-database-wipe-fab-137)), so the index no longer knows it and the check goes back to Blockfrost, which reports it spent. | nothing |
| **FluidTokens publish oracle NFTs under a new policy** | A release adds it to the shipped `loans.oracle.indexed-nft-policies`. Until you re-sync after that release, those checks ask Blockfrost, as today. | nothing required; re-sync after that release to have the index answer them |

**The refusal for a spent oracle output is unchanged in effect.** Whether the index or Blockfrost says so,
the candidate is REFUSED, the node logs one ERROR line with the reason, and the wallet UTxO is kept; the
candidate is checked again on the next pass. Only the reason differs when the index answered: it ends
`spent in transaction <hash>, as recorded by this node's index`.

### U stays in the check; CONVERT_BEFORE_ANTICIPATE (FAB-194)

This release changes one thing on **every** `shadow` or `live` node, and adds one market action you opt
into. Nothing to set, and the database is untouched. What it means depends on your node:

- **Every `shadow` or `live` node: an accepted liquidation no longer takes U out of the check.** Older
  images skipped every later candidate funded from U once one liquidation funded from U had been accepted
  ([FAB-185](#liquidation-is-funded-by-wallet-role-fab-185),
  [FAB-186](#fire-and-forget-fab-186)). Now a plain liquidation, a convert or an ADA-principal
  pay-in-advance accepted on U leaves U in the check, and every later candidate funded from U is still
  built and submitted on it, in profit order. **What that costs:** usually the mempool that holds the
  first refuses it — one evaluate and one submit call, and one INFO line
  `liquidation of … via … on … not accepted: HTTP …`. A backend that has not yet seen the first
  transaction can accept it too: the liquidations endpoint then shows **two `SUBMITTED` rows for one U**,
  and at most one of the two can land — the second only if the first loses its race. The `SUBMITTED` row
  of a liquidation funded from U says so (`…; U stays in this check: …`). Nothing bounds how many
  candidates are tried on U in one check. A token UTxO spent by an accepted token-principal anticipate is
  still taken out (`… skipped: wallet output … (role TOKEN <unit>) already went out in this check`), a
  `shadow` node's would-be submission marks what an accept would, and a market in `shadow` on a `live`
  node marks nothing — all as before.
- **`CONVERT` markets, and markets you have not listed:** otherwise unchanged.
- **`ANTICIPATE` markets:** unchanged, except the U rule above on an ADA principal (which is funded from
  U) and the wording of two log lines: a pay-in-advance refused by the market gate (its mode, `cap` or
  balance), or because the wallet output funding it is too small, now reads
  `the pay-in-advance liquidation of … was refused by the market gate: …` and
  `the pay-in-advance liquidation of … was refused: …`, where older images wrote
  `the convert liquidation of …`. If you alert on that text, update the pattern.
- **Opting in: `action: CONVERT_BEFORE_ANTICIPATE`.** A market with this action converts first, and pays
  in advance in the same check only when a convert is impossible — no Minswap pool for the pair in this
  node's index (or no pool address configured), a pool too thin to repay the debt, or
  `LOANS_LIQUIDATION_CONVERT_ENABLED=false`; every other convert refusal is refused by name
  ([deploying.md §9](deploying.md#convert_before_anticipate--convert-first-pay-in-advance-only-when-convert-is-impossible)).
  Like `ANTICIPATE` it needs a `cap`, and the node refuses to start without one or with a negative one.
  **The `cap` is per loan, in the token's smallest unit:** for a 6-decimal token, `500000000` is 500 of
  it, and a `cap` of `50` is 0.00005 of it and refuses every loan. The market's token gets its own shaped
  UTxO with 10 ADA, as an `ANTICIPATE` market's does, so the rebalancer's funding figure counts it (about
  16.2 ADA for one such token under current parameters). On a node that cannot convert, each such
  market gets one WARN at boot
  (`market … is action: CONVERT_BEFORE_ANTICIPATE on a node that cannot convert: …`); with convert
  switched off, one INFO
  (`INIT - market … is action: CONVERT_BEFORE_ANTICIPATE and loans.liquidation.convert.enabled is false: …`).
- **A node whose convert is unavailable or switched off:** a `CONVERT_BEFORE_ANTICIPATE` market pays in
  advance where its boot line says — with convert switched off, every loan in it whose lender allows
  conversion; with convert unavailable, every such loan the convert route finds no pool or too thin a
  pool for, which with `loans.minswap.pool-address` blank (as preview ships it) is every such loan.

**⚠ A pool this node has never indexed counts as no pool.** A Minswap pool that has not moved since the
block your node started syncing from is not in its index, so a loan in a `CONVERT_BEFORE_ANTICIPATE`
market whose pair it serves is paid in advance, although that pool could convert it on chain. A pool
becomes visible the next time it moves (any swap recreates its output). A restart or a configuration
change does not help: the stored cursor overrides `sync-start-*` (§3). A re-sync with the
[§1.1](#11--the-minswap-pool-release-requires-a-database-wipe-fab-137) steps helps only a pool that last
moved **after** the shipped `sync-start` (mainnet: 2025-05-06) but before your own node's index began —
for example on a node whose cursor started later than the shipped value. A pool idle since **before**
2025-05-06 is in none of the blocks a re-sync replays, so it stays invisible until it moves, re-sync or
not.

---

## 8. ⛔ The image runs as a non-root user — if you mount secrets as FILES, read this first

**The first published image that runs as uid 10001 is `2026.09.19`.** Everything up to and including
`2026.09.17` runs as root.

⚠ **Check your tag, not the date.** This used to say "since 2026-09-18", which is when the change was
written — and there is **no `2026.09.18` image**, because none was published that day. Tags are dated
by the CI run that built them, so a date you read in a changelog is not necessarily a tag you can pull:

```
… 2026.09.14   2026.09.17   ← root
   2026.09.19   2026.09.20   ← uid 10001
```

So the question is not "have I upgraded since some date" but **"is the tag I run ≥ 2026.09.19"**.

**If you pass configuration by environment variable — Docker Compose, `--env-file`, Kubernetes `env:` — nothing changes.** This section does not apply to you.

**If you mount secrets as files** — Kubernetes secret volumes, systemd credentials, docker secrets — the container may no longer be able to read them, and it fails at startup:

```
Caused by: java.nio.file.AccessDeniedException: /etc/aquarium-secrets/spring.flyway.password
```

⚠ **`AccessDenied`, not `NoSuchFile`: the file is there, the process may not read it.** Root could read a `0400` root-owned mount; uid 10001 cannot.

⛔ **AND THE ERROR NAMES ONE FILE WHILE THE PROBLEM IS THE WHOLE DIRECTORY — do not fix only the credential it mentions.**

Spring dies on `spring.flyway.password` because that is the first property it resolves, not because it is the only unreadable one. **Every file in that mount is unreadable to the new uid.** On the deployment where this was found, the same projected volume also carried `wallet.mnemonic` and `blockfrost.key` at the same `0400` — so repairing only the database credentials moves the crash to the mnemonic and changes nothing else.

⇒ **This is a secret-DELIVERY change, not a database-credentials change.** Whatever you do below, do it for *everything* in the mount.

### The simplest fix: pass them as environment variables instead

**Usually the right answer, because it removes the permission surface rather than configuring around it** — and because this application already reads these values from the environment. `application.yaml` ships:

```yaml
spring:
  flyway:
    password: ${DB_PASSWORD:password}
  datasource:
    username: ${DB_USERNAME:fluidtokens}
    password: ${DB_PASSWORD:password}
```

A config tree mounted at `/etc/aquarium-secrets/` is a deployment **overriding** that with a higher-precedence property source. Drop it and set the variables directly:

```yaml
env:
  - name: DB_PASSWORD
    valueFrom:
      secretKeyRef: { name: aquarium-secrets, key: db-password }
  - name: DB_USERNAME
    valueFrom:
      secretKeyRef: { name: aquarium-secrets, key: db-username }
```

Then remove the volume mount and any `spring.config.import=configtree:…`. **With no files to read, the non-root image needs no `fsGroup` and no `defaultMode`** — the hardening stays and costs nothing.

For any other key in such a directory, the mapping is Spring's relaxed binding — uppercase, dots and dashes to underscores: `spring.flyway.password` → `SPRING_FLYWAY_PASSWORD`.

⚠ **Judge this per secret rather than as a rule.** File mounts are generally stronger than environment variables, which leak into `/proc/<pid>/environ`, child processes and crash dumps. But this node already takes `WALLET_MNEMONIC` and `BLOCKFROST_KEY` from the environment, and the mnemonic is by a wide margin the most sensitive thing it holds — so putting a **local database password** beside it is consistency, not a downgrade. A credential that is worth file-mounting is worth file-mounting *everything* for, and that is a bigger decision than this section.

### If you want to keep file mounts

**Both the group AND the mode have to move.** `fsGroup` alone does not work: Kubernetes applies it as the volume's group owner but still honours `defaultMode`, so `0400` remains owner-only — and the owner is still root.

```yaml
spec:
  securityContext:
    runAsUser:  10001
    runAsGroup: 10001
    fsGroup:    10001        # group-owns the mounted volume
  volumes:
    - name: aquarium-secrets
      secret:
        secretName: aquarium-secrets
        defaultMode: 0440    # ⛔ 0400 will still fail: the group needs read
```

`0440` keeps the secret unreadable to anything else in the container. `0444` also works and is simpler, at the cost of being world-readable there.

### Or run as root

You give up the hardening; a legitimate choice if the container is already isolated:

```yaml
spec:
  securityContext:
    runAsUser: 0
```

### Why the image changed at all

The node holds a funded wallet mnemonic in its process environment and makes outbound network calls. Root inside the container is one bug away from root on a mounted volume. The trade is a one-time deployment change against removing that class of escalation permanently.

---

## 9. Changing WALLET_ACCOUNT_INDEX on a node that has already synced

`WALLET_ACCOUNT_INDEX` picks which account of the mnemonic the node uses, and **each account is a
different wallet**: its own address and its own stake key. The node's index stores wallet UTxOs
only for the wallet configured **at boot** — it filters them as it writes. So after a switch, coins
that were **already sitting** at the new account are invisible to the node: never stored, not
counted, not reported as missing. Only what arrives after the restart is seen.

Nothing does this for you. Pick one:

- **(a) Wipe the database and re-sync** from `sync-start` (the §4.2–§4.6 sequence, without the
  coordinate change). The re-sync indexes the new account's whole history.
- **(b) Switch, restart, then self-send.** Set the index, restart, check the startup line
  `account index N, address …, stake address …` against your wallet app, then send the new
  account's **whole balance to itself** from the wallet app. It lands in fresh UTxOs, which the node
  indexes.

**The new account has its own stake key.** Re-delegate the 30,000 FLDT to it, or tank processing is
disabled — the node logs one WARN naming the stake address it is looking for.
