# Aquarium node — deployment requirements (preview)

**Audience: whoever authors the helm chart, and whoever runs it.** This states what the
*application* needs. It deliberately does not design the chart or prescribe Kubernetes objects.

Written 2026-08-24 from the code and the existing `docker/` assets, not from memory.

---

## 1. Image

- **Repository:** `fluidtokens/ft-aquarium-node` (the name `docker/.env.example` ships).
- **Tag:** `latest` by default in the compose file.
- **Digest: I cannot supply one.** This repo has **no CI** (parked deliberately) and publishes no
  image itself — the `Dockerfile` here builds one locally from `build/libs/*.jar`. **Resolve the
  digest from the registry at pin time.**
- **Base:** `eclipse-temurin:21-jdk-jammy`; entrypoint `java -jar /app/app.jar`; workdir `/app`.

> **⚠ READ THIS BEFORE PINNING.** Operators build from **`main`**. **All Lending-v4 liquidation
> work — including everything fixed on 2026-08-24 — lives on `feat/lending-v4` and is NOT on
> `main`.** A published `latest` therefore does **not** contain it. If the point of this
> deployment is the liquidation bot, the image must be built from `feat/lending-v4`.

---

## 2. Configuration inputs

`SPRING_PROFILES_ACTIVE=preview` is **required** for a preview deployment. Everything below is an
environment variable; Spring relaxed-binding maps them onto `application.yaml` keys.

### 2.1 Required (non-secret)

| Variable | Shape | Preview value |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | string | `preview` |
| `DB_DRIVER` | string | `org.postgresql.Driver` |
| `DB_DIALECT` | string | `org.hibernate.dialect.PostgreSQLDialect` |
| `POSTGRES_HOST` / `POSTGRES_PORT` | host / int | in-cluster Postgres, `5432` |
| `POSTGRES_DB` | string | `aquarium` |
| `DB_SCHEMA` | string | `public` |
| `DB_USERNAME` | string | e.g. `postgres` |
| `DB_URL` | JDBC URL | `jdbc:postgresql://$HOST:$PORT/$DB?currentSchema=$SCHEMA` |
| `STORE_CARDANO_HOST` | host | `preview-node.world.dev.cardano.org` |
| `STORE_CARDANO_PORT` | int | `3001` |

### 2.2 ⚠ SECRETS — these must NOT reach a values file or git

| Variable | What it is | Blast radius if leaked |
|---|---|---|
| **`WALLET_MNEMONIC`** | BIP-39 seed phrase for the node's wallet | **Total loss of the wallet's funds.** Multi-word; keep it quoted. |
| **`BLOCKFROST_KEY`** | Blockfrost project id | API quota theft; identifies the operator |
| **`DB_PASSWORD`** | Postgres password | Database access |

**These three take the sealed-secret path. Nothing else on this page is a secret.**

### 2.3 Optional — the liquidation bot

The **preview profile already ships safe defaults**; the chart should not need to set any of these.
Full guide: `docs/operating-the-liquidation-bot.md`.

`AQUARIUM_LIQUIDATION_DELAY_SECONDS` (60) · `..._VALIDITY_WINDOW_SECONDS` (120) ·
`..._ORACLE_MARGIN_SECONDS` (30) · `..._PROFIT_MARGIN_LOVELACE` (1500000) ·
`..._MIN_PROFIT_ABSOLUTE_LOVELACE` (0) · `..._CHECK_PROFITABILITY` (true) ·
`..._DECISION_LOG_SIZE` (200) · `..._QUARANTINE_MINUTES` (30) ·
`..._REF_*` (published reference-script coordinates; the preview profile already carries verified ones).

---

## 3. ⚠ Safety — what must be true for a preview install

Submitting a liquidation **burns a loan NFT and moves someone's collateral.** Arming takes **two
independent flags**, by design, so one flipped by accident does nothing.

| Variable | Preview default | Chart should |
|---|---|---|
| `LOANS_ENABLED` | `true` *(preview profile)* | leave alone — this only enables indexing/scanning |
| `AQUARIUM_LIQUIDATION_MODE` | `shadow` *(preview profile)* | **leave alone. Never set `live`.** |
| `AQUARIUM_LIQUIDATION_ENABLED` | `false` | **leave alone. Never set `true`.** |

**A default preview install scans, builds, prices and records liquidations but never submits** —
the `MODE_NOT_LIVE` veto stops it. That is the intended posture. **Arming is Giovanni's decision,
made deliberately, never a chart default.**

`SUBMITTABLE_NETWORK` is hard-coded to `preview` in the code, so mainnet is fail-closed regardless
of flags. **`AQUARIUM_X_SUBMIT` is NOT an application setting** — it gates a manual test runner
only and has no effect on the running node. Do not add it to the chart.

---

## 4. Ports and reachability

- **One port: `8080`** (Spring Boot default; no `server.port` is set).
- Everything is served there: `/healthcheck`, `/actuator/health`, `/actuator/prometheus`,
  and the read-only API under `/api/v1` (`/loans`, `/loans/oracle`).

**Inbound requirement: NONE.** The node is a client of everything it talks to — it dials out to a
Cardano relay (TCP 3001), Blockfrost (HTTPS) and the FluidTokens oracle API (HTTPS). **Nothing
outside the cluster needs to reach it.** Port 8080 needs to be reachable **in-cluster only**, for
probes and for Prometheus scraping.

Given the host exposes no 80/443/NodePorts, **that is fine and needs no change.** If someone wants
the loans API or metrics from outside later, that is a deliberate later decision — and note the
endpoints are unauthenticated, so they should not be exposed casually.

**Egress the node requires:** TCP `3001` to the relay host; HTTPS to
`cardano-preview.blockfrost.io` and `testapi.fluidtokens.com`.

---

## 5. Health and readiness

**⚠ There is no endpoint that currently means "ready", and the chart author will get this wrong
without being told.**

| Endpoint | Returns | Use as |
|---|---|---|
| `/actuator/health` | Spring's own health (DB etc.) | **liveness** |
| `/healthcheck` | see below | **not a correct readiness probe as-is** |

`/healthcheck` returns **HTTP 200 with the plain body `...syncing...` while the node is still
syncing the chain**, and only afterwards returns a JSON body
(`db_ok`, `parameters_ok`, `parameters_ref_input_ok`, `wallet_ok`, `staking_ok`). **So a 200 from
it does not mean ready** — during a long initial sync it means "alive and catching up".

**Recommendation:** liveness on `/actuator/health`; **no readiness gate initially**, or a readiness
probe that treats the literal body `...syncing...` as not-ready. Do not fail the pod for it —
initial sync is long (§6) and a readiness flap would restart-loop the node.

> **Documentation defect found while writing this:** `CLAUDE.md` records the healthcheck path as
> `/__internal__/healthcheck`. **The controller maps `/healthcheck`** and there is no servlet
> context path. **`/healthcheck` is correct**; the CLAUDE.md line is stale and would send a chart
> author to a 404.

---

## 6. Persistence — the important one

- **Postgres is required.** The compose stack runs `postgres:15`, database `aquarium`, schema
  `public`, data at `/var/lib/postgresql/data`. Flyway migrates on startup.
- **The node itself is stateless** — no volume needed on the application pod.

**⚠ What losing the Postgres volume costs.** The node indexes the chain with `yaci-store` from a
pinned start point. On preview that is **slot 71,971,209**, and the tip today is **~120,955,928** —
**≈ 49 million slots, about 567 days of chain.** Losing the volume means re-indexing all of it from
scratch, during which the node reports `...syncing...` and does no useful work.

**So: a durable PVC, and back it up or accept a multi-hour-to-multi-day rebuild.** Sizing: start at
**20Gi** and watch it; I have not measured the on-disk size of a full preview index and will not
invent a number.

---

## 7. Resources

The shipped compose file sets **`JAVA_OPTS=-Xms256m -Xmx512m`** for the application, which is the
authors' own figure and the best evidence available.

- **Application pod:** request ~`512Mi` / limit ~`1Gi` memory (heap 512m plus JVM overhead);
  CPU is bursty during sync, modest at tip — start `250m` request, `1` limit.
- **Postgres:** ordinary small-instance sizing; the volume matters more than the CPU.

**These are starting points from the compose file and normal JVM overhead, not measurements of a
long-running node.** Watch and adjust; I would not treat them as tuned.

---

## 8. Things the chart author would otherwise get wrong

1. **The image from `main` does not contain the liquidation work.** See §1.
2. **`WALLET_MNEMONIC` is multi-word.** Keep it quoted end to end; unquoted it word-splits if the
   file is ever sourced in a shell, leaking seed words into stderr and shell history.
3. **Two arming flags, not one**, and neither belongs in a chart default (§3).
4. **`/healthcheck` returns 200 while syncing** — do not use it as readiness (§5).
5. **The node needs no inbound access at all** (§4).
6. **Postgres must be reachable before the app starts.** Compose gates on a `pg_isready`
   healthcheck; the k8s equivalent matters because Flyway runs at startup.
7. **`AQUARIUM_X_SUBMIT` is not an app setting** — ignore it (§3).
8. **A Secret key the pod's volume names is a hard *startup* dependency, and the `optional:` in
   `optional:configtree:` does not soften it.** That `optional:` is a **Spring** fallback: it only
   ever runs once the process does. If the Secret is missing a key the volume projects, the
   **kubelet** fails the *whole volume* and the container never starts —
   `MountVolume.SetUp failed for volume "secrets": references non-existent secret key: db-password`,
   and the pod sits in `ContainerCreating` indefinitely. `db-password` is the sharp case precisely
   because it must be projected **twice** (§9.2): one absent key takes out both properties and the
   pod with them. Project each key with `optional: true` only if the application can genuinely boot
   without it — this one cannot, so the correct answer is to make the key's *source* durable (item 9),
   not to soften the mount.
9. **Whoever creates the Secret must create it whole — never hand-add a key to a Secret a controller
   owns.** With sealed-secrets (the mechanism in use), the `Secret` carries `ownerReferences` back to
   its `SealedSecret` and is **reconciled to match it**. A key added out of band with `kubectl` works
   immediately, survives restarts, and is then silently removed at the controller's next full
   reconcile. **The failure surfaces days later, disconnected in time from the act that caused it**,
   and it surfaces as an *unstartable* pod rather than a failing one — a running pod keeps running on
   the tmpfs copy it mounted before the key vanished, so the damage is invisible until the next
   recreation. Every key the volume names belongs in the sealed source.

> **Measured, `cardano-pv`, 2026-08-31 → 09-01:** `db-password` was hand-added to a controller-owned
> Secret on 08-25 and reconciled away on 08-31. 765 `FailedMount` events over 25 hours, against a pod
> that stayed `Running 1/1` and fully functional the entire time on a 5-day-old tmpfs mount. Nothing
> was degraded; the pod was simply no longer *recreatable*. Items 8 and 9 are the two halves of that.
>
> **✅ RESOLVED 2026-09-02.** The structural fix landed rather than the stopgap: `DB_PASSWORD` is now
> consumed **by reference** from the Postgres operator's own Secret, so **no copy of the password
> exists anywhere** and items 8 and 9 can no longer recur through this key. The pod rolled cleanly
> (restarts 0, armed state preserved, config verifiers 0 mismatched) and image rolls are ordinary
> deploys again. *The paragraph above is kept because the mechanism is general and the next key
> someone projects will not be `db-password`.*

10. **⛔ NEVER `kubectl rollout undo` this Deployment — roll FORWARD.** Twelve old ReplicaSets still
    carry the previous **four-item** secret projection, including the `db-password` key that no longer
    exists. A rollback recreates the exact `ContainerCreating` condition items 8 and 9 describe — on a
    Deployment whose *current* state is perfectly healthy. **This is the reflex reach during an
    incident, which is precisely when it will be reached for**, and the pod it strands is an armed,
    transaction-signing bot. A ReplicaSet is a frozen copy of a pod spec: it remembers a Secret shape
    the cluster has since abandoned, and nothing reconciles that memory. **Old ReplicaSets are not
    "previous good states" — they are previous states.**
11. **A single `Startup probe failed: connection refused` event at boot is the `startupProbe` working,
    not a fault.** It fires before the JVM has bound its port. Investigate only if it *repeats* past
    the startup budget, or if the pod does not reach `Running 1/1` (§5 explains why a tight liveness
    probe is only safe because a startupProbe covers the slow first boot).

---

## 9. Answers to the chart author's seven gaps (2026-08-24)

### 9.1 Env vars are enough — no mounted config file needed

The coordinates that have no `${ENV:...}` placeholder are still bound from the **Spring
Environment**, not hardcoded: `@Value("${aquarium.genesis.tx-hash}")`,
`@Value("${loans.config.policy-id:}")`, `@Value("${loans.smart-tokens-spend-script-hash:}")`.
Environment variables are a **higher-precedence property source** than the profile YAML, and
`SystemEnvironmentPropertySource` does relaxed name matching. So these all work:

`AQUARIUM_GENESIS_TX_HASH` · `AQUARIUM_GENESIS_OUTPUT_INDEX` · `LOANS_CONFIG_POLICY_ID` ·
`LOANS_CONFIG_REF_UTXO_TX_HASH` · `LOANS_LM_CONFIG_POLICY_ID` ·
`LOANS_SMART_TOKENS_SPEND_SCRIPT_HASH`

**Proof from this repo's own working setup:** `SPRING_PROFILES_ACTIVE` is set in
`docker-compose.yaml` and works, and `spring.profiles.active` has no `${}` placeholder anywhere.
Same mechanism.

> **Caveat, stated rather than hidden:** this is Spring's documented behaviour plus the working
> example above; **I have not started the app to confirm each key empirically.** Verify with one
> boot — it is cheap and loud: `LoansContractRegistry` logs every derived hash at startup, so a
> coordinate that did not take is immediately visible.

**Why it matters that this works:** these coordinates change. Preview has been redeployed three
times, and a **fourth** deployment exists as of tonight with new policy ids. Rebuilding the image
to chase a redeploy would be the wrong shape.

### 9.2 Secrets — names, and yes, the mnemonic can come from a file

| Property | Env var | Suggested Secret key |
|---|---|---|
| `wallet.mnemonic` | `WALLET_MNEMONIC` | `wallet-mnemonic` |
| `blockfrost.key` | `BLOCKFROST_KEY` | `blockfrost-key` |
| `spring.datasource.password` **and** `spring.flyway.password` | `DB_PASSWORD` | `db-password` |

*(Note Flyway has its own url/user/password properties, separate from the datasource. `DB_PASSWORD`
feeds both.)*

> **Consequence for a file-based mount, and it has already bitten once:** feeding both properties
> from one key means the volume projects `db-password` **twice**, to `spring.datasource.password`
> and `spring.flyway.password`. That doubles the blast radius of the key going missing without
> doubling anything that would make it noticed — see §8 items 8 and 9.

**File-based secrets work today with no application change**, via Spring Boot **config trees**.
Mount the Secret at a directory and set:

```
SPRING_CONFIG_IMPORT=optional:configtree:/etc/aquarium-secrets/
```

with files named for the properties (`wallet.mnemonic`, `blockfrost.key`). Spring maps file name →
property, contents → value.

**I agree with the reasoning and recommend it for all three.** An env var is visible in
`kubectl describe pod`, in the pod spec and in Argo's UI; this is a mnemonic the application signs
and submits with. A 0400 file is strictly better. **Not empirically verified here — worth
confirming on the same first boot as 9.1.**

### 9.3 Postgres — external, consumed and never bundled

Repo precedent confirmed: consume an external Postgres. `DB_URL` carries host, port, database and
`?currentSchema=`; defaults are database `aquarium`, schema `public`, and the compose stack uses
`postgres:15`.

**Flyway needs DDL privileges on an empty database** — it creates the schema objects at startup and
must be able to `CREATE`. It runs before the application is usable, so Postgres must be reachable
first (compose gates on `pg_isready`).

### 9.4 Chain source — Giovanni runs his own preview relay

The preview profile deliberately ships `store.cardano.host: ""` and `port: 0`, so it **must** be
supplied. Two options:

- **Giovanni's own relay, which is what `.env.preview` uses: `192.168.1.37:3001`.** For a k3s
  cluster this is a **LAN address**, so the pod needs egress to it — worth stating explicitly
  because it is not an internet route.
- Public fallback from `.env.example`: `preview-node.world.dev.cardano.org:3001`.

### 9.5 Memory — I cannot give you a measured number, and the JAVA_OPTS finding is worse than you thought

**`JAVA_OPTS` is not referenced by the Dockerfile at all.** The entrypoint is
`ENTRYPOINT ["java", "-jar", "app.jar"]`. So it is not a quoting bug — **the variable is ignored
entirely**, and the `-Xms256m -Xmx512m` in the compose file has never had any effect. Any figure
derived from it, including the one in §7 above, carries no weight. **§7's numbers should be treated
as unverified defaults.**

**I have never started this node.** `CLAUDE.md` says not to without Giovanni asking — he runs nodes
himself — so I have **no steady-state RSS and no initial-sync peak**, and I will not invent them.

**Heap policy: use `JAVA_TOOL_OPTIONS`, which the JVM reads automatically**, rather than
`JAVA_OPTS`, which nothing reads:

```
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75
```

**Percentage, not a fixed `-Xmx`**, so the heap tracks whatever limit the chart sets instead of
silently mismatching it. Ship a labelled unverified default and measure on the first real run; the
**initial sync is the peak**, and it is ~49 million slots.

### 9.6 Singleton — confirmed, and treat it as a hard requirement

**Yes: `replicas: 1`, `strategy: Recreate`.** One wallet, one signer.

**On idempotence, precisely: there is none on chain.** The application has mechanisms that *bound*
concurrency — per-loan quarantine, and submit-veto S7 which re-reads both UTxOs immediately before
signing — but **no idempotence key and nothing that makes a duplicate deterministically fail
before signing.** Two replicas would race the same UTxO set; the loser is rejected by the ledger,
but only after both have signed and submitted. **So `replicas: 1` is a correctness requirement,
not a preference.**

### 9.7 Fatal-by-design boot failures, and shutdown

Both verifiers are `@ConditionalOnProperty(loans.enabled=true)` — and the preview profile defaults
`loans.enabled` to **true**, so they do run.

| What | When it kills startup |
|---|---|
| `LoansConfigVerifier` | derived script hashes do not match the live config datums — **the redeploy detector.** Must stay fatal. |
| `LoansReferenceScriptVerifier` | a configured reference-script coordinate's on-chain hash ≠ the derived one. Backend *unreachable* is tolerated by default (`loans.verify-config.fail-on-unreachable:false`). |
| `AccountConfig` | `wallet.mnemonic` has **no default** — absent mnemonic fails context startup. |
| `AppConfig` genesis | `aquarium.genesis.tx-hash` has **no default** — same. |

**Do not paper over these with a lenient probe, a longer `initialDelaySeconds`, or a restart
policy that hides them.** A hard fail here means the node would otherwise run against the **wrong
contracts**. A CrashLoopBackOff with one of these in the log is the system working correctly, and
the log line names the fix.

**Graceful shutdown is NOT configured** — there is no `server.shutdown: graceful`, so Spring's
default is immediate. **The risk is real but narrow:** the executor signs and submits inside a
scheduled cycle, so a SIGTERM mid-submit can kill the process after the transaction reached the
network but before the decision log records it — the transaction still lands, and the node's own
record of it is lost. Recommend **`terminationGracePeriodSeconds: 60`**. Enabling Spring graceful
shutdown is a one-line application config change, not something the chart can fix; noted as a
candidate.

### 9.8 Agreed, explicitly

**No values file in the public helm-charts repo will ever ship `mode: live` or `enabled: true`.**
`values-preview.yaml` ships **at most `shadow`**. Arming happens in the Steward's Argo values,
outside that repo, as a deliberate act.

**Agreed without reservation.** A published chart preset that arms a transaction-signing bot is a
footgun anyone can `helm install`, and this bot moves other people's collateral. If a future reader
finds no `live` preset in that repo, **that is the design and not an omission.**
