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
