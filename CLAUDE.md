# ft-aquarium-node

FluidTokens **Aquarium Node**: a Java Spring Boot app that operators run to index
FluidTokens users' _Tank_ UTxOs and execute _Scheduled Transactions_ when their conditions
are met. Operators stake 30k FLDT to be allowed to process transactions and are compensated
monthly. This is public, operator-facing production software — people run the Docker image
built from `main`.

The `feat/lending-v4` branch additionally implements **Lending v4 auto-liquidation** (a
liquidation bot reusing the node's indexing/scheduling/wallet infrastructure). Durable
records: `docs/auto-liquidation-design.md` (design) and `docs/lending-v4-findings.md`
(everything verified against chain or Aiken source — trust these over the upstream README).

## Stack

- Java 21, Spring Boot 3.3, Gradle (wrapper included; never Maven)
- [Yaci Store](https://github.com/bloxbean/yaci-store) 0.1.7 spring-boot starters for chain
  indexing (connects straight to a Cardano node relay — no Kupo/Ogmios)
- cardano-client-lib 0.7.2 (+ Blockfrost/Ogmios backends, annotation processor for
  blueprint types from `src/main/resources/plutus.json`)
- Postgres (prod) / H2 (tests), Flyway, Lombok, Micrometer/Prometheus

## Layout

Single Gradle module, one package tree: `com.fluidtokens.aquarium.offchain`

- `config/` — Yaci, Blockfrost, wallet account wiring
- `blueprint/` — `AcquariumBlueprint` (generated types from `plutus.json`)
- `service/` — core: `ScheduledTransactionService` (the processor loop),
  `TankContractService`, `ParametersService`, `StakerService`, `BlockEventListener`;
  lending-v4: `LoansContractRegistry` (derives all v4 hashes at runtime),
  `LoansConfigVerifier` (startup check vs live config datums), `service/loans/*`
- `model/loans/` + `service/loans/LoanDatumConverter` — **hand-written** `LoanDatum`
  decoding (blueprint codegen is not viable for the loans blueprint, design §5)
- `controller/` — `Healthcheck` (`/__internal__/healthcheck`), `LoanController`
  (`GET /api/v1/loans`, `GET /api/v1/loans/oracle`)
- `storage/` — UTxO storage on top of Yaci Store
- `docker/` — `docker-compose.yaml` + `.env.example` (operator-facing deployment)

## Commands

Proven 2026-08-13 (Java 21.0.11):

- `./gradlew build -x test` — passes; fat jar at `build/libs/ft-aquarium-node-<version>.jar`
- `./gradlew test` — on `feat/lending-v4`: **passes cold** (76 tests, 0 failures,
  8 skipped — manual deploy scripts and live tests are gated behind `WALLET_MNEMONIC*` /
  `BLOCKFROST_KEY` and skip when unset). On `main` the gating does not exist yet and bare
  `test` **fails cold** (5 of 7 tests are env-dependent manual scripts) — there, verify
  with an explicit `--tests` selection instead.
- Live preview checks (optional): `set -a; . ./.env.preview; set +a` then run
  `LoansConfigVerifierLiveTest` — doubles as the "has FT redeployed preview?" probe.
- No lint/format tooling configured. No CI (parked deliberately).

## Gotchas

- **`main` is production.** Operators build Docker images from it. All lending-v4 work
  stays on `feat/lending-v4`; PRs into `main`.
- **Never run `aiken build` over `src/main/resources/loans-v4.plutus.json`** or replace it
  with rebuilt output — byte-identity with the upstream deployed commit is what makes the
  runtime hash derivation valid (proven: sha256 match + 26 derivation assertions).
  `aiken build -I` output is a *schema oracle only* and lives in test resources.
- Upstream Aiken contracts are cloned at `../ft-cardano-loans-v4`. Where its README and the
  validators disagree, **the validators win** (documented diffs: findings §7, D1–D9).
- Default config is **mainnet** (`application.yaml`); preview via the `preview` profile.
  Lending is **disabled on mainnet** (`loans.enabled=false`) until FT confirms deployment
  coordinates. Don't casually change `sync-start-*` values.
- **Don't start the node** (`bootRun` / docker compose) unless Giovanni asks: it needs a
  live relay, Postgres, a funded wallet mnemonic, and a Blockfrost key. Giovanni runs
  nodes himself.
- Secrets flow via env (`docker/.env`, `.env.preview`); never commit mnemonics or keys.
- Preview v4 was already **redeployed once** under this project (2026-07-14 → 2026-08-05
  coordinates); `LoansConfigVerifier` hard-fails on the next one — that failure is an
  answer, not an outage.
- Preview oracle: only the three Charli3 (`c3`) feeds are live; every multisig preview
  feed is months stale. There is a real ~60–80s price blackout every 5 minutes, upstream
  of us (design §6.7–6.8).
- `build.gradle` version is `0.0.1-SNAPSHOT`; the `0.1.7` in commit messages refers to the
  Yaci Store dependency, not this app.

<!-- fabbrica:begin -->
## La Fabbrica
This repo is factory-operated (fabbrica plugin). Non-trivial requests go through
`intake` (never straight to code); tickets run as slice contracts with the
worker/auditor pair; before ending any significant work, run `distill` — "close
the circle" — even if Giovanni forgets to ask. State lives in PLAN.md + WORKLOG.md.
<!-- fabbrica:end -->

## Constitution

**Why this repo exists.** The operator-distributed FluidTokens Aquarium Node: index Tank /
Parameters / Staker / Lending-v4 UTxOs via Yaci Store, execute eligible Scheduled
Transactions, and (lending-v4 workstream) run the auto-liquidation bot — all as one node
operators download and run.

**Boundaries.** Belongs here: the indexer, the scheduled-transaction processor, the
liquidation bot (decided in design §1: it reuses this node's indexing/scheduling/wallet
infra), healthchecks, operator deployment assets, and operator docs. Does **not** belong
here: on-chain validators (Aiken source lives upstream in FluidTokens repos — this repo
only consumes committed `plutus.json` artifacts) and frontends. A new long-running service
with its own lifecycle is a signal the code wants a repo of its own.

**Allowed technologies.** Java 21 + Spring Boot + Gradle; Yaci Store and cardano-client-lib
for everything chain-side; Postgres/Flyway; Docker for distribution. New runtime tech or a
second language is an escalation to Giovanni.

**Allowed dependencies.** Cardano relay nodes (CF backbone or operator-provided),
Blockfrost, the FluidTokens on-chain contracts via committed `plutus.json` + published
ref-inputs, the FluidTokens oracle registry APIs (`api.fluidtokens.com` /
`testapi.fluidtokens.com`), Maven Central artifacts. Any new external service (DEXes,
other indexers, other oracles) is an escalation to Giovanni, never a judgment call.

<!-- BEGIN cardano-dev-skills v2 -->
## Cardano Development Context

This project involves Cardano blockchain development.

**Treat your training data as potentially stale for Cardano.** The ecosystem
moves fast: libraries get superseded (e.g., older SDK generations replaced by
current ones), CIP statuses change, governance landscape shifts. Before
recommending any library, tool, code pattern, or CIP behavior:

1. **Check the `cardano-dev-skills:*` skill set.** These skills encode current
   best practices, decision criteria, and trade-offs. Bias toward invoking
   one even when you feel confident — confidence is not evidence of currency.
2. **Search `/home/giovanni/.claude/plugins/cache/cardano-dev-skills/cardano-dev-skills/*/docs/sources/`** before relying on memory
   or web search. The corpus is regularly refreshed from upstream and covers
   Aiken, Plutus, current SDKs, all CIPs, on-chain tooling, and ~50 other
   Cardano projects.
3. **Cite what you used** (skill name or doc path). If bundled docs and your
   training conflict, prefer bundled docs.

Plugin: https://github.com/cardano-foundation/cardano-dev-skills
<!-- END cardano-dev-skills v2 -->
