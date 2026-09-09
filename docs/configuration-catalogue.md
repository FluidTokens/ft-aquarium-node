# The Aquarium node's complete configuration surface

**Audience: the Helm chart author.** Every value this node reads, what it means, what it defaults
to, and which of them must never ship armed. Written 2026-09-04 against app `HEAD`, on Giovanni's
standing ruling — *"all the modes and params must be available to the chart user and also well
documented"* — whose operational form is **expose everything, default everything safe, ship nothing
armed**.

Companion to `docs/k8s-deployment-requirements.md` (ports, probes, persistence, resources,
singleton) and `docs/operating-the-liquidation-bot.md` (the arming runbook). This file is the
*inventory*; those two are the *procedure*.

---

## 0. How a value reaches the app — and why a `${}` scan under-counts the surface

There are **three** layers, and only the first two are visible to anyone grepping the app for
placeholders or `@Value` annotations.

| layer | how many | how to find them | example |
|---|---|---|---|
| **1. Placeholder keys** — `application.yaml` names the env var itself | 38 leaves | grep `${` | `mode: ${AQUARIUM_LIQUIDATION_MODE:disabled}` |
| **2. `@Value`-only keys** — no yaml line at all, default inline in Java | ⚑ **0 (closed 2026-09-09)** | grep `@Value` | *was* `@Value("${loans.liquidation.convert.enabled:true}")` — **every key now has a yaml line; see trap 8** |
| **3. ⚑ Hardcoded yaml leaves** — a literal value in the file, **still overridable** | 22 | walk the YAML | `store.cardano.sync-start-slot: 154984561` |

⛔ **Layer 3 is the one a derivation misses, and it contains the highest-stakes knobs in the file.**
An environment variable outranks `application.yaml` in Spring Boot's property-source order whether
or not the file mentions it, so **a hardcoded value is a default, never a constant.** This is not a
reading of the docs: `docker/docker-compose.yaml` has always driven `STORE_CARDANO_HOST`,
`STORE_CARDANO_PORT` and `BLOCKFROST_KEY` this way, against yaml lines that hardcode all three.

**64 leaves total** across the two YAML documents (default = mainnet, plus the `preview` profile),
of which 22 are hardcoded.

⚠ **Enumerating a surface and cataloguing it are different acts, and this file got them out of
step once already.** All 64 leaves were enumerated in the first pass; six of them — the Aquarium
derivation inputs, §3.5 — never got rows, and the file went on calling itself complete. It took
helm-charts' independent derivation to catch it. **A completeness claim is only worth what the
last diff against it proved.**

### The env-var naming rule

`property.name.with-dashes` → **`PROPERTY_NAME_WITH_DASHES`**: uppercase, and both `.` and `-`
become `_`. Indexed list elements use the index as a segment:
`loans.liquidation.markets[0].unit` → `LOANS_LIQUIDATION_MARKETS_0_UNIT`.

⚠ **This mapping is load-bearing for the entire chart and was unmeasured until now.** For the
eleven layer-2 keys it is *the only route in* — there is no `AQUARIUM_*` alias to fall back on, and
a name that binds nowhere produces exactly the same silence as a key left unset.
`EnvironmentVariableBindingTest` now proves it against a real
`SystemEnvironmentPropertySource`, including the market list and the enum casing. Mutation-checked:
removing the env property source turns 6 of its 7 tests red.

### Casing on the enum-valued keys — **the trap is that there is no trap**

Both are **case-insensitive**, and the catalogue says so rather than imposing a superstition:

- `loans.liquidation.mode` — compared with `equalsIgnoreCase` in `parseMode()`. `shadow`, `SHADOW`
  and `ShAdOw` are the same value. **An unrecognised value ABORTS STARTUP** naming the legal set.
- per-market `mode` / `action` — Spring's lenient enum converter, likewise case- and dash-tolerant.

⇒ **Write them however the values file reads best.** The shipped yaml uses lowercase for the node
mode (`disabled` / `shadow` / `live`) and uppercase for market fields (`SHADOW`, `ANTICIPATE`),
purely as a house style. What matters is that a *typo* is fatal, not that a case is wrong.

---

## 0.5 Provenance — who found each row

This catalogue is one of **three independent derivations** of the same surface, and a diff between
them is only meaningful if each row says where it came from. Two sources agreeing tells you nothing
when one seeded the other.

| tag | meaning | how helm-charts should count it |
|---|---|---|
| **(own)** | Swept here, independently. My sweep of layers 1 and 2 — the 38 placeholder keys and the 11 `@Value`-only keys — plus the env-binding proof, ran **before** the relaxed-binding addendum reached me. | Agreement is **real corroboration.** |
| **(fwd)** | Arrived via macchinista's addendum, originating with helm-charts' own Finding 2. | **An echo.** Do not count it as a second source. |
| **(own†)** | Row surfaced by my own enumeration, in a **category** the addendum named. The addendum listed five `store.cardano.*` keys; enumerating the YAML for them found 22 hardcoded leaves. | Corroboration of the *category* is an echo; the *specific rows* are independent. |

**Exactly five rows are (fwd):** `store.cardano.sync-start-slot`, `sync-start-blockhash`,
`keep-alive-interval`, `host`, `port` — and the layer-3 concept in §0 that frames them. Every other
row in this file is (own) or (own†); tables not otherwise marked are **entirely (own)**.

### ⇒ The rows a `${}`/`@Value` derivation cannot reach at all

This is the direction that actually tests a derivation method, so it is stated separately. These are
**not in `application.yaml` under any profile** and carry no `@Value` annotation — they are
yaci-store's own properties, absent from yaci-store's documentation too, readable only from its
source. **(own)**, from a measured preview incident:

- `store.cardano.cursor-cleanup-interval` (default **3600** s) — ⛔ **crash-loops the node after a
  long initial sync.** See §7 trap 2.
- `store.cardano.cursor-no-of-blocks-to-keep` (default **2160**).

Two further **(own)** findings no config derivation would produce, because they are about code that
reads the config rather than the config itself:

- The ninth reference-script slot **binds and validates but is never consumed** — §7 trap 5.
- `store.blocks.epoch-calculation-interval=14400` is a **malformed key** that sets nothing — §7 trap 6.

---

## 1. Classification legend

| | meaning | chart treatment |
|---|---|---|
| **A** | operator-owned value | plain chart value, safe default, documented |
| **B** | **secret** | secret reference only — never a plain value, never a default, never logged |
| **C** | **protection / must not ship armed** | ⚠ **expose it — (C) never means "omit from the chart".** Default OFF (or to the safe value), and comment *why* at the value. Several are startup-fatal if wrong. |
| **D** | derivation input — correct per network, and wrong values silently index a dead deployment | expose (redeploys happen), but default to the shipped coordinates |
| **I** | internal — expose only if the chart wants completeness | leave at default |

---

## 2. Secrets — layer 1/3 (B)

*Every row in this section: **(own)**.*


Three, and Giovanni's chart shape is **three independent (secretName, secretKey) pairs**, so the
database can come from the Zalando Postgres operator while the Blockfrost key and the mnemonic
share one secret.

| env var | property | what it is | notes |
|---|---|---|---|
| `WALLET_MNEMONIC` | `wallet.mnemonic` | **B** — the bot's wallet seed phrase. Signs and submits every liquidation; on the ANTICIPATE path it also *funds* them. | Yaml default is the empty string. ⛔ A blank seed does not fail loudly today — `AccountConfig` constructs an `Account` from whatever it is handed. Treat "set" as mandatory. Can be a mounted file (k8s doc §9.2). |
| `BLOCKFROST_KEY` | `blockfrost.key` | **B** — provider key for tx submission, protocol params and the remote evaluator. | ⚠ **Network-scoped.** A mainnet key against a preview node 403s in a way that reads as a code failure. |
| `DB_PASSWORD` | `spring.datasource.password`, `spring.flyway.password` | **B** — Postgres password. | One value, consumed by two properties; the chart must set both or Flyway migrates against the wrong credentials. |

---

## 3. Network target and infrastructure

| env var | property | plain English | type / values | default (mainnet doc) | preview profile | class | prov |
|---|---|---|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | — | Which YAML document applies. **This is the network selector.** Unset = mainnet. | `` (empty) or `preview` | *(empty → mainnet)* | `preview` | **A** | (own) |
| `NETWORK` | `network` | The Cardano network the node believes it is on — drives address prefixes, slot conversion and every "is this mainnet" guard. | `mainnet` / `preview` / `preprod` | `mainnet` | `preview` | **C** — ⚠ **expose with a locked-safe default and a loud warning; do NOT remove it.** (C) in this file means *handle specially*, never *forbid* — see §1. The safe default is **empty, derived from the profile**. ⛔ The hazard is setting it **alone**: it decouples the network from the config policy ids, the Blockfrost URL and the relay, producing a node that derives one deployment and talks to another. Worst case is `SPRING_PROFILES_ACTIVE` unset (⇒ mainnet document) with `NETWORK=preview` — preview addresses and slot conversion against mainnet coordinates, a mainnet relay and a mainnet Blockfrost URL. | (own†) |
| ~~`LOANS_SUBMITTABLE_NETWORK`~~ | ~~`loans.submittable-network`~~ | ⛔ **REMOVED 2026-09-04 — the property no longer exists.** It named the one network the node could submit on; a node correctly targeted at mainnet, armed and profitable, would refuse to submit and say so only in a decision row. Giovanni: *"arming that works everywhere except the last step, silently."* **`NETWORK` is now the single source of truth.** ⚠ Setting this env var is now a **no-op** — the harmless direction, since Spring ignores an unknown variable. | — | — | — | **gone** | (own) |
| `STORE_CARDANO_HOST` | `store.cardano.host` | The Cardano relay the indexer connects to (N2C/N2N over TCP — no Kupo/Ogmios). | hostname | `backbone.mainnet.cardanofoundation.org` ⚑ *hardcoded* | `""` ⚑ | **A** — mandatory on preview, where the default is empty. | (fwd) |
| `STORE_CARDANO_PORT` | `store.cardano.port` | Relay port. | int | `3001` ⚑ | `0` ⚑ | **A** — mandatory on preview. | (fwd) |
| `STORE_CARDANO_PROTOCOL_MAGIC` | `store.cardano.protocol-magic` | Network magic for the handshake. | int | `764824073` ⚑ | `2` ⚑ | ⛔ **C** — must match `network`. A mismatch fails the handshake, not the config. Expose for completeness; never vary independently. | (own†) |
| `STORE_CARDANO_SYNC_START_SLOT` | `store.cardano.sync-start-slot` | ⛔ **Where indexing begins.** | int | `154984561` ⚑ | `71971209` ⚑ | **C** — see §7 trap 1. | (fwd) |
| `STORE_CARDANO_SYNC_START_BLOCKHASH` | `store.cardano.sync-start-blockhash` | The block hash pinning that slot. Moves **with** the slot, always. | hex | `586ead17…` ⚑ | `0e459daa…` ⚑ | **C** — see §7 trap 1. | (fwd) |
| `STORE_CARDANO_KEEP_ALIVE_INTERVAL` | `store.cardano.keep-alive-interval` | Relay keep-alive, ms. | int | `1000` ⚑ | `1000` ⚑ | **I** | (fwd) |
| `STORE_CARDANO_CURSOR_CLEANUP_INTERVAL` | `store.cardano.cursor-cleanup-interval` | ⚑ **Not in `application.yaml` at any profile** — yaci-store's own property, default **3600** s. | int (seconds) | *(yaci-store default 3600)* | same | ⛔ **C** — see §7 trap 2. **Set it to 60 before any first mainnet sync.** | **(own)** |
| `STORE_CARDANO_CURSOR_NO_OF_BLOCKS_TO_KEEP` | `store.cardano.cursor-no-of-blocks-to-keep` | yaci-store cursor retention, default **2160**. `0` removes the cleanup bean but grows the table unbounded. | int | *(2160)* | same | **I** — expose, do not tune. Not recommended at `0`. | **(own)** |
| `BLOCKFROST_URL` | `blockfrost.url` | Blockfrost base URL. | URL | `https://cardano-mainnet.blockfrost.io/api/v0/` ⚑ | `…-preview…` ⚑ | **C** — must match the network and the key. | (own†) |
| `DB_URL` | `spring.datasource.url`, `spring.flyway.url` | JDBC URL. **Two properties, one value.** | JDBC URL | `jdbc:postgresql://localhost:5432/aquarium` | inherits | **A** | (own) |
| `DB_USERNAME` | `spring.datasource.username`, `spring.flyway.user` | DB user. ⚠ Note the two properties differ: `username` vs `user`. | string | `fluidtokens` | inherits | **A** | (own) |
| `DB_SCHEMA` | `spring.flyway.schemas` | Flyway schema. | string | `public` | inherits | **A** — see k8s doc on Postgres 15 revoking `CREATE` on `public`. | (own) |
| `SPRING_TASK_SCHEDULING_POOL_SIZE` | `spring.task.scheduling.pool.size` | Scheduler threads. **Not cosmetic**: at Spring's default of 1, the transaction processor blocks the 30 s oracle refresh for minutes and every price silently ages past its window. | int | `4` | inherits | **A** — do not lower below 4. | (own) |
| `SCHEDULING_TRANSACTION_PROCESSOR_DELAY_MINUTES` | `scheduling.transaction-processor.delay-minutes` | How often the Aquarium scheduled-transaction loop runs. | int (minutes) | `5` | inherits | **A** | (own) |
| `JAVA_TOOL_OPTIONS` | — | JVM options. ⛔ **NOT `JAVA_OPTS`** — the entrypoint is a bare `["java","-jar","app.jar"]` with no shell, so `JAVA_OPTS` is silently ignored. | string | `-XX:MaxRAMPercentage=75` | — | **A** | (own) |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `management.endpoints.web.exposure.include` | Actuator endpoints exposed. | csv | `health,prometheus` ⚑ | inherits | **I** — widening this exposes more than the chart's probes need. | (own†) |
| `APIPREFIX` | `apiPrefix` | Path prefix for `/loans*` routes. `/healthcheck` is **outside** it. | string | `/api/v1` ⚑ | inherits | **I** — changing it moves every documented URL. | (own†) |

---

## 3.5 Aquarium core — the Tank / Parameters / Staker derivation (D)

*Every row in this section: **(own)**, and **added 2026-09-04 after helm-charts' three-column diff
surfaced them as missing.** They were an **omission, not a scope decision** — see the note below.*

⛔ **These six parameterise the whole Aquarium contract tree**, which is the part of this node that
runs on `main`, in production, for every operator. `ContractRegistry` applies them in a chain:
`parameters(genesisTxHash, genesisOutputIndex)` → `staker(H_params, stakingTokenPolicy,
stakingTokenName)` → `tank(H_staker, H_params)`. Get one wrong and every derived credential moves.

| env var | property | plain English | default (mainnet) | preview | class |
|---|---|---|---|---|---|
| `AQUARIUM_GENESIS_TX_HASH` | `aquarium.genesis.tx-hash` | The genesis one-shot UTxO's tx hash. First parameter of the `parameters` validator, whose hash **is** the config-NFT policy id. | `45f379b3…` ⚑ | `d35f81f6…` ⚑ | **D** |
| `AQUARIUM_GENESIS_OUTPUT_INDEX` | `aquarium.genesis.output-index` | That UTxO's output index. | `0` ⚑ | `1` ⚑ | **D** |
| `AQUARIUM_STAKING_TOKEN_POLICY` | `aquarium.staking.token.policy` | FLDT policy id — second parameter of `staker`. | `577f0b13…` ⚑ | `0b77d150…` ⚑ | **D** |
| `AQUARIUM_STAKING_TOKEN_NAME` | `aquarium.staking.token.name` | FLDT asset name (hex) — third parameter of `staker`. | `0014df10464c4454` ⚑ | `0014df1074464c4454` ⚑ | **D** |
| `AQUARIUM_TANK_REF_INPUT_TXHASH` | `aquarium.tank.ref-input.txHash` | The published Tank reference input. ⚠ Note the **camelCase** property segment; the relaxed env name flattens it anyway. | `354ffe79…` ⚑ | `78210625…` ⚑ | **D** |
| `AQUARIUM_TANK_REF_INPUT_OUTPUTINDEX` | `aquarium.tank.ref-input.outputIndex` | Its output index. | `0` ⚑ | `0` ⚑ | **D** |

⛔ **Emit these only with the shipped values, never blank.** Their `@Value` annotations carry **no
inline default**, so the yaml is the only default there is, and an env var set to the empty string
**overrides it**. The two failure modes are asymmetric, and the dangerous one is silent:

- **`AQUARIUM_GENESIS_OUTPUT_INDEX=""`** → `Integer` conversion fails → **the context fails loudly.** Fine.
- ⛔ **`AQUARIUM_STAKING_TOKEN_POLICY=""`** (or either genesis/tank hash) → `HexUtil.decodeHexString("")`
  returns a **zero-length array, not an error**. Derivation succeeds, produces a *valid but wrong*
  script hash, and the node indexes at credentials nothing lives at. **It boots clean and finds an
  empty world** — the same pathology as trap 3, on the path that serves production.

⇒ A chart that renders `AQUARIUM_*: ""` when a value is unset would break the Aquarium node
**silently**. Render the key only when the value is non-empty, or default it to the shipped constant.

> ### ⚠ Why these were missing, stated plainly
> They were **not** a scope decision. The 64-leaf enumeration in §0 included all six; the sweep
> then wrote rows for the lending side and did not come back for them, and the file went on
> claiming to be the complete surface. **The claim was wrong and the diff is what caught it.**
>
> ⛔ **And the omission fell in the worst possible place.** Everything else in this catalogue governs
> lending v4, which is `loans.enabled=false` on mainnet and therefore inert in production. **These
> six govern the Tank indexing that every operator actually runs today, off `main`.** The rows most
> likely to matter to a real operator were the rows a lending-shaped sweep skipped — which is
> exactly what an independent derivation is for.

---

## 4. Lending v4 — indexing and derivation (D)

*Every row in this section: **(own)**.*


These derive the entire v4 contract tree at runtime. **Wrong values do not fail; they index a
deployment that does not exist** — see §7 trap 3.

| env var | property | plain English | default (mainnet) | preview | class |
|---|---|---|---|---|---|
| ~~`LOANS_ENABLED`~~ | ~~`loans.enabled`~~ | ⛔ **REMOVED 2026-09-04 — v4 indexing is UNCONDITIONAL.** The flag *was* the defect: `TankUtxoStorage` fixes its credential set once at startup, so with it off v4 UTxOs were dropped at write time with no trace while the cursor advanced, and switching it back on never re-read those blocks. Worse, `saveSpent` kept working, so a loan indexed *before* an off-window that *moved* during it vanished from the index while still live on chain. **What decides whether anything is indexed is now whether the config policy ids are set** — §7 trap 7. ⚠ Setting this env var is now a no-op. | — | — | **gone** |
| `LOANS_CONFIG_POLICY_ID` | `loans.config.policy-id` | Main config-NFT policy id — the parameter the whole tree is derived from. | `db2c498e…` | `d46f626f…` | **D** |
| `LOANS_LM_CONFIG_POLICY_ID` | `loans.lm-config.policy-id` | LenderManager config-NFT policy id. | `a56b0ac2…` | `a7d4b762…` | **D** |
| `LOANS_CONFIG_REF_UTXO_TX_HASH` | `loans.config.ref-utxo-tx-hash` | The tx that minted both config NFTs. | `7b9f20db…` | `8dd38e97…` | **D** |
| `LOANS_CONFIG_ASSET_NAME` | `loans.config.asset-name` | Hex of `"parameters"`. Effectively a constant. | `706172616d6574657273` *(layer 2)* | same | **I** |
| `LOANS_SMART_TOKENS_SPEND_SCRIPT_HASH` | `loans.smart-tokens-spend-script-hash` | Published in the on-chain ConfigDatum, not derivable. Same on both networks. | `fca77bcc…` | same | **D** |
| `LOANS_VERIFY_CONFIG_FAIL_ON_UNREACHABLE` | `loans.verify-config.fail-on-unreachable` | Whether a **Blockfrost outage** at startup is fatal. Default false: a blip must not take down the Aquarium path, which does not depend on loans. A config **mismatch** is always fatal, regardless. | `false` *(layer 2)* | same | **A** |
| `LOANS_ORACLE_ENABLED` | `loans.oracle.enabled` | The FluidTokens price-feed client. Off ⇒ no health factors ⇒ no candidates. | `true` | inherits | **A** |
| `LOANS_ORACLE_URL` | `loans.oracle.url` | Price-feed registry endpoint. | `https://api.fluidtokens.com/get-oracle-tokens` | `https://testapi.fluidtokens.com/…` | **A** |
| `LOANS_MINSWAP_POOL_POLICY_ID` | `loans.minswap.pool-policy-id` | Minswap V2 coordinate parameterising `lm_liquidate_and_convert_action`. | `f5808c2c…` *(layer 2)* | ⚠ mainnet's, and **preview's are not these** | **D** |
| `LOANS_MINSWAP_POOL_SPEND_SCRIPT_HASH` | `loans.minswap.pool-spend-script-hash` | as above | `ea07b733…` *(layer 2)* | as above | **D** |
| `LOANS_MINSWAP_ORDER_SPEND_SCRIPT_HASH` | `loans.minswap.order-spend-script-hash` | as above | `c3e28c36…` *(layer 2)* | as above | **D** |
| `LOANS_MINSWAP_POOL_ADDRESS` | `loans.minswap.pool-address` | Where V2 pools sit; queried per candidate rather than indexed. | `addr1z84q0de…` *(layer 2)* | as above | **D** |
| `LOANS_UI_ENABLED` | `loans.ui.enabled` + `spring.thymeleaf.enabled` | The read-only readiness page at `${apiPrefix}/loans/readiness`. | **`false`** | inherits | ⛔ **C** — **there is NO AUTHENTICATION.** It publishes loan positions and the operator's own routing. Only ever behind loopback, a proxy, or a `ClusterIP` with no ingress. |

---

## 5. The liquidation bot

*Every row in §5.1–§5.6: **(own)**, including the env-binding proof behind §5.4 and §5.5.*


### 5.1 Arming (C)

| env var | property | plain English | type | default (mainnet) | preview | class |
|---|---|---|---|---|---|---|
| `AQUARIUM_LIQUIDATION_MODE` | `loans.liquidation.mode` | What the loop may do. `disabled` = never scans. `shadow` = scan, build, price, record, **never sign**. `live` = may submit, if also enabled. | `disabled`\|`shadow`\|`live`, case-insensitive | **`disabled`** | `shadow` | ⛔ **C** — a typo **aborts startup** by design. |
| ~~`AQUARIUM_LIQUIDATION_ENABLED`~~ | ~~`loans.liquidation.enabled`~~ | ⛔ **REMOVED 2026-09-04.** A second arming boolean beside `mode`, justified as *"one switch is too easy to flip by accident"*. Giovanni: *"redundant with mode — mode == disabled already IS off … the gates are too much; it's a bot, if you use it you know it's risky, so gates don't really help."* ⚠ The redundancy cost real things: two spellings of "off" that logged differently, and one more silent way for an operator who **had** decided to arm not to have. **`AQUARIUM_LIQUIDATION_MODE` is the whole node-level dial.** Setting this is now a no-op. | — | — | — | **gone** |
| `AQUARIUM_LIQUIDATION_DELAY_SECONDS` | `loans.liquidation.delay-seconds` | Seconds between cycles. | int | `60` | inherits | **A** |
| `AQUARIUM_LIQUIDATION_DECISION_LOG_SIZE` | `loans.liquidation.decision-log-size` | In-memory ring buffer behind `GET …/loans/liquidations`. | int | `200` | inherits | **A** |
| `AQUARIUM_LIQUIDATION_QUARANTINE_MINUTES` | `loans.liquidation.quarantine-minutes` | How long a failed candidate is skipped. | int | `30` | inherits | **A** |

### 5.2 Validity windows (A, but coupled)

| env var | property | plain English | default | class |
|---|---|---|---|---|
| `AQUARIUM_LIQUIDATION_VALIDITY_WINDOW_SECONDS` | `loans.liquidation.validity-window-seconds` | How far past *now* the built tx stays valid. | `120` | **A** |
| `AQUARIUM_LIQUIDATION_ORACLE_MARGIN_SECONDS` | `loans.liquidation.oracle-window-margin-seconds` | How much of each oracle feed's window must remain unused after the tx's `validTo`. | `30` | **A** |

⚠ **One piece of arithmetic, read together.** A candidate is buildable only while
`validity-window + margin` (=150 s) of the feed's ~600 s window is still ahead of it. Raising the
margin shortens the usable stretch second for second; a margin near 600 silently disables the bot
while every candidate reports `ORACLE_WINDOW_MARGIN_TOO_SMALL`.
⛔ **Do not narrow the validity window to buy oracle headroom** — measured preview rejections needed
182 and 219 slots. **Wider, not tighter**; "wide enough" is unmeasured.

### 5.3 Profit controls — ⛔ read §7 trap 4 before exposing

| env var | property | plain English | type | default (mainnet) | class |
|---|---|---|---|---|---|
| `AQUARIUM_LIQUIDATION_PROFIT_MARGIN_LOVELACE` | `loans.liquidation.profit-margin-lovelace` | What the operator wants to **earn** per liquidation, above cost. Raised 1.5 → 5 ADA on Giovanni's instruction. | lovelace, **may be negative** | **`5000000`** | **A** |
| `AQUARIUM_LIQUIDATION_MIN_PROFIT_ABSOLUTE_LOVELACE` | `loans.liquidation.min-profit-absolute-lovelace` | **The absolute floor**, gate (a): `floorProfit = feeSlice − txFee − minAdaFunded` must reach this. The margin **cannot reach it**. | lovelace, may be negative | `0` | **A** |
| `AQUARIUM_LIQUIDATION_MIN_EXPECTED_PROFIT_LOVELACE` | `loans.liquidation.min-expected-profit-lovelace` | **The margin-adjusted floor**, gate (c): `floorProfit − margin` must exceed this. | lovelace, may be negative | `0` | **A** |
| `AQUARIUM_LIQUIDATION_CHECK_PROFITABILITY` | `loans.liquidation.check-profitability` | Whether gate (a) runs at all. `false` removes the absolute floor, leaving only the margin lever. | bool | `true` | ⛔ **C** — expose, default true. |
| `AQUARIUM_LIQUIDATION_IGNORE_PROFIT_CHECK` | `loans.liquidation.ignore-profit-check` | ⛔ **TEST-ONLY.** Bypasses **both** gates and liquidates at unbounded loss. | bool | `false` | ⛔⛔ **C** — **`LiquidationExecutor` REFUSES TO START when this is true and `network` is not preview/preprod.** A chart that renders it on mainnet produces a crash loop. Expose it *only* with that stated in the values comment, or omit it. |

### 5.4 Markets — per-market policy (A)

⛔ **`unit` is the loan's PRINCIPAL asset — what was lent — not the collateral.** `MarketGate` matches
on `principalAsset()` at all three call sites. **A collateral-keyed entry matches nothing, and an
unmatched market is UNLISTED, which converts by default** — so a `mode: DISABLED` on the wrong asset
silently converts. *Found by audit 2026-09-10, before it reached an operator.*

An **object list**, so a chart must render indexed names or a YAML fragment.

```yaml
loans:
  liquidation:
    markets:
      - unit: lovelace        # "lovelace", or policyIdHex+assetNameHex
        mode: SHADOW          # DISABLED|SHADOW|LIVE; omit = inherit the node mode
        action: CONVERT       # CONVERT|ANTICIPATE; default CONVERT
        cap: 1000000000       # MANDATORY iff ANTICIPATE, ignored otherwise
```

| env var | plain English | type | default | class |
|---|---|---|---|---|
| `LOANS_LIQUIDATION_MARKETS_<i>_UNIT` | Which asset this entry governs. | `lovelace`, or 56 hex chars of policy id + hex asset name | — | **A** |
| `LOANS_LIQUIDATION_MARKETS_<i>_MODE` | This market's execution state. Omitted = inherit. | enum, case-insensitive | inherit | **A** |
| `LOANS_LIQUIDATION_MARKETS_<i>_ACTION` | The strategy. `CONVERT` routes collateral through Minswap and **fronts nothing**. `ANTICIPATE` fronts the whole principal from the bot's wallet. | `CONVERT`\|`ANTICIPATE` | `CONVERT` | ⛔ **C** for `ANTICIPATE` — it spends the operator's own capital. |
| `LOANS_LIQUIDATION_MARKETS_<i>_CAP` | The most principal the bot may front in this market, **in that asset's own unit**. | integer | — | ⛔ **C** — mandatory on ANTICIPATE; **startup fails without it**, because anticipating uncapped is unbounded exposure. |

**Defaults and rules worth stating in the values file:**
- ⛔ **An UNLISTED market is `action: CONVERT` at the node mode.** An empty list means *convert
  everywhere at whatever posture the node is in* — **it does not mean "do nothing"**. Listing a
  market is how an operator *deviates*.
- ⛔ **AND THAT GOT SHARPER ON 2026-09-04.** `loans.liquidation.enabled` used to sit behind the mode
  as a second arming confirmation: `mode: live` with the flag false was **not armed**. With the flag
  gone, **`AQUARIUM_LIQUIDATION_MODE=live` plus an empty `markets` list is armed on every market
  immediately, with nothing else to set.** ⇒ On a node that is going live, decide the market list
  *before* the mode, not after.
- ⛔ **The node mode is a CEILING**: effective = `min(nodeMode, marketMode)` over
  `DISABLED < SHADOW < LIVE`. A market may be more restrictive, never less. A market asking for
  `LIVE` under a `shadow` node runs as `SHADOW` and says so at boot.
- **Startup aborts** on: a missing/blank unit, a malformed unit, a duplicate unit, an empty action,
  an `ANTICIPATE` entry with no cap, a negative cap. A `CONVERT` entry with a cap **warns** and
  ignores it.

### 5.5 The convert path — ⚑ **now yaml, per profile** (was layer 2, closed 2026-09-09)

⛔ **The convert margin was DELETED on 2026-09-09.** Giovanni: *"for me convert is a liquidation and
profitMarginLovelace is literally the same as liquidation. can we get rid of convert and merge into
liquidation? … it's just one knob, the additional protection is the market specification where we can
enable/override convert w/ anticipate."* Convert now answers to the **shared**
`AQUARIUM_LIQUIDATION_PROFIT_MARGIN_LOVELACE` in §5.3, like every other mode. **Three keys are left in
this block, and none of them is a margin** — one arming flag and two cost inputs.

| env var | property | plain English | type | default | class |
|---|---|---|---|---|---|
| `LOANS_LIQUIDATION_CONVERT_ENABLED` | `loans.liquidation.convert.enabled` | The Minswap conversion mechanism, **globally**. `false` = no converts anywhere, whatever any market says. | bool | ⚑ **`true`** | **C** — ⚠ **the one arming flag in this app that defaults ON.** Flipped to `false` on 2026-09-09 and **restored to `true` on 2026-09-10** on Giovanni's ruling: *"convert should remain but under liquidation and be enabled by default. there is a case we want to disable conversions."* Defensible here and nowhere else: the bot fronts no capital on this path and an unfilled order returns the collateral to the lender, so the failure mode is a no-op, not a loss. Still subject to `AQUARIUM_LIQUIDATION_MODE`. |
| `LOANS_LIQUIDATION_CONVERT_MINSWAP_ORDER_COST_LOVELACE` | `loans.liquidation.convert.minswap-order-cost-lovelace` | ⚑ **What a conversion SPENDS on the Minswap order — a fixed expense, charged before the conversion counts as break-even.** ⚠ **This does NOT mean a margin of `0` clears at 4 ADA of income** — the gate is `max(txFee + this, dex-cost-floor-lovelace)`, and the floor below is a FLOOR over that whole sum, not an addend to it. | lovelace, **≥ 4000000** | `4000000` | ⛔ **C** — **null, negative, or below `ConvertEconomics.MINSWAP_ORDER_OVERHEAD` throws at startup, on every network, naming the key.** Stating MORE is a legitimate belief about cost; stating LESS understates a spend the chain will certainly make. |
| `LOANS_LIQUIDATION_CONVERT_DEX_COST_FLOOR_LOVELACE` | `loans.liquidation.convert.dex-cost-floor-lovelace` | A **floor on the TOTAL cost** of a convert: the gate charges `max(txFee + orderCost, this)`. ⚑ **This is what actually binds at the shipped defaults**, not the order cost above — it wins whenever `txFee < 1,000,000`, true of most liquidation transactions (the pinned candidate elsewhere in this repo measures `txFee = 989,747`). What it still guards is an **under-measured tx fee**: the order cost is stated, the fee is measured. **To be folded away after the first correctly-priced live convert.** | lovelace, **≥ 0** | `5000000` | ⛔ **C** — **negative or unset throws at startup, on every network.** A negative cost of doing work is a typo, not a bound. |

⛔ **THE NUMBER AN OPERATOR ACTUALLY FACES, missing until this round: at the shipped defaults a
convert needs ≥ 10,000,000 lovelace of oracle-valued fee slice to be approved** —
`5,000,000` outlay (the dex-cost floor binding, not the 4,000,000 order cost) **plus** the shipped
`5,000,000` `AQUARIUM_LIQUIDATION_PROFIT_MARGIN_LOVELACE` margin (§5.3). "Margin `0` means clear
4 ADA" was never the whole story even before the floor was accounted for correctly — it omitted the
margin on top of the outlay entirely.

#### ⇒ Two cost keys, and why they are two

`minswap-order-cost-lovelace` is a **named term inside** the measurement;
`dex-cost-floor-lovelace` is a **floor over the whole of it**. Neither is a margin: both say what the
work costs, and `AQUARIUM_LIQUIDATION_PROFIT_MARGIN_LOVELACE` (§5.3) says how far above break-even the
operator wants to be. **⛔ Read §6.6 before touching the order cost** — a second, *non*-configurable
number of the same magnitude decides what the order must **carry**, and confusing the two fails
conversions on chain.

#### ⇒ The two convert controls, and how they compose

There are exactly two, and **neither substitutes for the other. Both must permit a convert.**

1. **`LOANS_LIQUIDATION_CONVERT_ENABLED`** — global, and it wins: `false` ⇒ no converts anywhere,
   whatever any market says. A candidate that reaches the gate is refused `NOT_ARMED` before any
   arithmetic runs.
2. **`markets[]` (§5.4)** — per market. `action: ANTICIPATE` fronts capital in that market instead of
   converting; `mode: DISABLED` sits that market out entirely; **an unlisted market converts.**

⚠ *In the code the market list is consulted first* — `MarketGate` routes in `LiquidationExecutor`,
before `ConvertEconomics` sees the candidate — *so a market routed to `ANTICIPATE` never reaches the
global switch at all.* That is an implementation detail and not a precedence: the outcome is the AND
of the two, in either order.

⚠ **So "no markets listed" means convert everywhere — provided the global switch is true.** Both
failure modes look identical from the outside (zero candidates, no error), which is why both are
listed in §6.5.

### 5.6 Reference scripts — **nine named slots** (A, but not a tuning knob)

Each is one published `txHash#index`. **Empty means "not published"** and that validator travels
inline in the witness set — legal, and much bigger. Six inline validators total 18,584 bytes against
a 16,384 `maxTxSize`, so on any network where the bot must actually submit these are **the
difference between buildable and unbuildable**.

| env var | property suffix | notes |
|---|---|---|
| `AQUARIUM_LIQUIDATION_REF_LOAN` | `loan` | |
| `AQUARIUM_LIQUIDATION_REF_LOAN_SPEND` | `loan-spend` | |
| `AQUARIUM_LIQUIDATION_REF_LENDER_MANAGER` | `lender-manager` | |
| `AQUARIUM_LIQUIDATION_REF_LENDER_MANAGER_SPEND` | `lender-manager-spend` | |
| `AQUARIUM_LIQUIDATION_REF_LOAN_CLAIM_ACTION` | `loan-claim-action` | |
| `AQUARIUM_LIQUIDATION_REF_LM_LIQUIDATE_ACTION` | `lm-liquidate-action` | the PLAIN path's action validator; 4,227 B |
| `AQUARIUM_LIQUIDATION_REF_LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION` | `lm-liquidate-and-pay-in-advance-action` | 7,051 B — the largest left inline on an anticipate liquidation |
| `AQUARIUM_LIQUIDATION_REF_ASSET_MANAGER` | `asset-manager` | ⚠ present in the mainnet document only; the preview document omits it and inherits |
| ⛔ `LOANS_LIQUIDATION_REFERENCE_SCRIPTS_LM_LIQUIDATE_AND_CONVERT_ACTION` | `lm-liquidate-and-convert-action` | ⛔ **The ninth slot has NO `AQUARIUM_LIQUIDATION_REF_*` alias** — it is layer 2, so the relaxed name above is the only way to set it. See §7 trap 5: **it is also not consumed yet.** |

**Behaviour:** a malformed coordinate **fails the context** rather than silently becoming "not
published" — a typo that quietly moved a validator back into the witness set would present only as
every candidate refusing on a size the operator believed they had fixed. Every rejection names the
key. Coordinates are **verified resolvable on chain at startup**, and a hash mismatch is fatal, so
a stale coordinate is worse than an absent one.

### 5.7 Reading the refusal log — every reason an operator can meet, and what it means

⚑ **Why this section exists.** On 2026-09-09 a mainnet cycle logged "1 buildable", logged its wallet
UTxOs and its validity window, and then went silent — no outcome, no reason, nothing. Diagnosing it
cost an afternoon and the answer was only recoverable from the in-memory endpoint in §5.8. Three exits
used to leave the candidate's fate untraceable at INFO, and one of them recorded a `REFUSED` decision
while **logging nothing at all** — closed 2026-09-09. A money path that exits silently makes "refused
for a good reason" and "quietly did nothing" indistinguishable to the operator reading the log.

Read the log line together with the recorded decision (`outcome`/`reason`/`detail` at §5.8): the
record is what survives past the log's own retention, the log line is what a `journalctl`/
`docker logs` tail shows as it happens.

⚠ **They are not always the same string, and the difference is deliberate — do not read a mismatch
as a stale surface or an overwritten decision.** Where both exist, the log line is the record's text
or a **superset** of it; nothing re-derives one from memory of the other. Two exits below add
operator context that the stored `detail` does not carry:

| exit | recorded `detail` | log line adds |
|---|---|---|
| `NO_UTXO` | `loan utxo present=…, bond utxo present=… — spent since the scan` | the actual loan and bond **refs**, and which of the two was missing |
| pay-in-advance not modelled | the router's own message | the **principal asset unit**, and — only when that principal is not ada — the remedy |

Everywhere else the two are byte-identical, computed once and shared.

| exit (`LiquidationExecutor`, approx. line) | outcome / reason | level | meaning |
|---|---|---|---|
| ~821 config/lm-config utxo missing | *(no decision — the whole cycle returns)* | WARN | the node's own config or lm-config UTxO could not be resolved; nothing this cycle could have built |
| ~871 quarantine hold | `QUARANTINED` | INFO | this loan utxo failed to build on an earlier cycle and is held for the rest of its quarantine window; **the hold is IN-MEMORY ONLY and does not survive a restart** — restarting the node is itself the remedy if the hold should lift now |
| ~889 utxo spent since the scan | `NO_UTXO` | INFO | the loan or bond utxo the scanner saw is no longer unspent — repaid, or liquidated by somebody else, between the scan and the build; not an error, never quarantined; names both refs and which of the two was missing |
| ~929 no convert capability | `REFUSED` / `CONVERT_UNAVAILABLE` | WARN | the market is `action: CONVERT` but this node has no `ConvertLiquidationRouter` bean (`loans.minswap.*` unset, or belongs to another network); **never falls back to pay-in-advance** — that would front capital the operator never authorised |
| ~945 no Minswap pool | `REFUSED` / `NO_MINSWAP_POOL` | INFO | no Minswap pool exists for this pair — impossible, not unprofitable; the operator's remedy is `action: ANTICIPATE` on that market if it should still liquidate |
| ~950 convert unprofitable | `UNPROFITABLE` | INFO | the Minswap convert was built and priced, but its fee does not clear the operator's floor |
| ~957 convert build failed | `REFUSED` / the exception's root class name | ERROR | a genuine machinery fault assembling the convert transaction (a Blockfrost timeout, say); quarantined |
| ~992 wallet input too small (pay-in-advance) | `REFUSED` / `WALLET_INPUT_TOO_SMALL` | WARN | no wallet utxo covers the lender payout this pay-in-advance liquidation must fund; not quarantined — a wallet top-up cures it |
| ~1000 market gate refusal (pay-in-advance) | `REFUSED` / the gate's own reason | INFO | the market is disabled, or the principal this candidate requires the bot to front exceeds the operator's stated cap |
| ~1015 pay-in-advance shape not modelled | `REFUSED` / the router's own message | INFO | the convert shape (non-ada principal, or non-positive equity) this seam cannot yet build for; the log line names the principal asset unit and, for a non-ada principal, the remedy — set that market's `action` to `CONVERT` so Minswap fronts it instead |
| ~1033 pay-in-advance build failed | `REFUSED` / the exception's root class name | ERROR | a genuine machinery fault; quarantined |
| ~1057 wallet input too small (plain path) | `REFUSED` / `WALLET_INPUT_TOO_SMALL` | WARN | no wallet utxo covers the fee for a fee-only liquidation; not quarantined |
| ~1094 plain-path builder refusal | `REFUSED` / the builder's own `Refusal` name (one of fifty) | **INFO** for 48 of the 50 reasons — a clean verdict on the candidate, reproducible next cycle; **ERROR** for the two that wrap a real fault underneath (`SCRIPT_COST_EVALUATION_FAILED`, `TRANSACTION_NOT_BUILDABLE`) | the level is the discriminator: INFO means "this candidate is not liquidatable", ERROR means "the machinery broke while checking" — logging every refusal at ERROR would bury the two that matter under the forty-eight that do not |
| ~1115 plain-path build failed | `REFUSED` / the exception's root class name | ERROR | a genuine machinery fault; quarantined |
| ~1299 priced verdict | `WOULD_SUBMIT` \| `UNPROFITABLE` \| `SUBMIT_VETOED` \| `SUBMITTED` \| `SUBMIT_FAILED` | INFO | the candidate was built and priced; every armed-vs-shadow and profitable-vs-not question is answered from this line — read `submit_veto` alongside it |
| — | `PRICE_UNAVAILABLE` (`LiquidationDecision.Outcome`) | **still RESERVED here, not yet emitted** | the oracle-pricing slice (2026-09-09) landed the fail-closed pricing gate and its named refusal on the **compound** path only (`CompoundExclusion.PRICE_UNAVAILABLE`, §6 below, via the new `PricingService`) — `LiquidationExecutor`'s plain/anticipate/convert paths were out of that slice's file allowlist and do not emit this `Outcome` value yet; grepping this class for it today and finding nothing is still a deliberate gap, tracked as FAB-77 item (2) ("decidable in every mode … with no silent refusal") |

Line numbers are a reading aid against `HEAD`, not ground truth — re-derive them from the file when
they drift.

**The compound path's exclusions** (`CompoundExclusion`, carried per-candidate on the
`CompoundCandidate` rather than logged — see `CompoundCandidateScanner.classify`):

| exclusion | meaning |
|---|---|
| `NOT_ARMED` | `loans.compound.enabled` is false — the default, not a fault |
| `BOND_NAMES_NO_POOL` | the lender bond names no pool (`poolId == ""`); this escrow is **permanently** uncompoundable by anyone |
| `POOL_NOT_LIVE` | the pool or its pool manager is not live — burned, or missing the right NFT quantity |
| `PRINCIPAL_NOT_ADA` | the pool's principal is not ada — a **structural** refusal, emitted by the scanner before `CompoundEconomics` ever runs. As of 2026-09-09 this is **not** a pricing gap: `CompoundEconomics.assess` can price a non-ada principal's fee via `PricingService` (see `PRICE_UNAVAILABLE` below). It is refused here anyway because (1) `CompoundTransactionBuilder` still computes the pool output and the bot's own net position entirely in lovelace and is not yet generalised to pay out a token amount, and (2) lifting it is held on a product ruling from Giovanni (FAB-77): a token-principal compound pays its fee in ada and is rewarded in the principal token — the bot acquiring a token, the same class of decision as convert's "the bot buys collateral" |
| `PRICE_UNAVAILABLE` | `PricingService` could not price the compounding fee slice into lovelace: no oracle feed for the principal asset, a feed present but not usable at the instant asked (expired, or not yet valid), or a feed present and covering the instant but a `POOLED` variant (an outright `fail` in `finance.ak`, unpriceable off chain too). Reachable from `CompoundEconomics.assess` for any principal, including ada in principle — in practice, as of 2026-09-09, every candidate reaching `assess` is still ada-principal (see `PRINCIPAL_NOT_ADA` above), so this fires only when live-tested directly against `assess`, not yet via the automated compound cycle |
| `ESCROW_SHAPE_REJECTED` | the escrow carries assets the validator will not accept for its principal type (an ada-principal escrow must hold lovelace alone, plus receipt NFTs) |
| `NOT_LENDER_OWNED` | the escrow is owned by a borrower bond, not a lender bond — `lm_compound_action`'s bond lookup does not apply |
| `ESCROW_NOT_TOKEN_OWNED` | no inline datum, or not `AssetManagerDatumWithToken` — **except** when the utxo carries a reference script: that is FluidTokens' own asset-manager script published at its own address (verified on chain 2026-09-09: `83d1c5393a53e365eb15a7bdfd1feff560f43f9560bc60c23c4e41de709bae33#0`), a protocol object that must **never** be spent, not datum-less junk. The detail line names the reference script hash by name when that is the shape; the genuinely no-datum case keeps its original wording. |
| `BOND_NOT_FOUND` | no unspent lender bond for this escrow's loan is in the index |
| `NET_BELOW_FLOOR` | built and priced, but does not clear the operator's stated floor — the ordinary outcome for a pool whose fee is zero |

### 5.8 The decision log endpoint — the ONLY place a held-but-unsubmitted decision can be read

`GET ${apiPrefix}/loans/liquidations?limit=N` (`LiquidationController`; add `&include_cbor=true` to
also get the built transaction's hex) returns, newest first, the ring buffer of the last
`decision-log-size` decisions (§5.1) plus the last cycle's exclusion histogram. Every field from
`expected_fee_lovelace` down is null unless a transaction was actually built for that decision.

⛔ **It is IN-MEMORY.** `LiquidationDecisionLog` backs it with no persistence, so **it must be read
BEFORE a restart** — a restart clears it exactly as it clears the quarantine map (§5.7). This endpoint
is what made the 2026-09-09 diagnosis possible at all, and until this section it was documented
nowhere an operator would find it.

---

## 6. The compound path

*Every row in this section: **(own)**.*


`lm_compound_action`: collect a repaid loan's principal from the asset manager and deliver it into
the lender's pool, keeping the pool owner's compounding fee. A **different action** from every
liquidation key above.

| env var | property | plain English | type | default | class |
|---|---|---|---|---|---|
| `AQUARIUM_COMPOUND_ENABLED` | `loans.compound.enabled` | The arming flag. The node assesses candidates either way; it builds nothing until this is on. | bool | **`false`** | ⛔ **C** |
| `AQUARIUM_COMPOUND_DELAY_SECONDS` | `loans.compound.delay-seconds` | Seconds between cycles. | int | `60` | **A** |
| `AQUARIUM_COMPOUND_PROFIT_MARGIN_LOVELACE` | `loans.compound.profit-margin-lovelace` | What `expectedFee − txFee` must reach. `0` refuses every net loss while allowing break-even — which is what refuses a **zero-fee pool** out of the box. The rate is `compoudingFeePerMille`, set by the **pool owner**; measured 2026-09-02, the only live preview pool published `0`. | lovelace, **may be negative** | `0` | **A** — ⚠ **the `application.yaml` comment claiming "negative on mainnet is a hard startup failure" is STALE.** `CompoundEconomics` only WARNs. See §7 trap 4. |
| `AQUARIUM_COMPOUND_REFERENCE_SCRIPTS` | `loans.compound.reference-scripts` | ⚑ **A comma-separated coordinate LIST, not named keys.** The node reads `referenceScriptHash` off the chain, so a mislabelled coordinate is not expressible — the shape the liquidation path should eventually adopt (FAB-75). | csv of `txHash#index` | *(empty)* | **A** — empty means all 11 validators inline = **24,878 bytes against a 16,384 limit**, i.e. unbuildable. Referencing the four largest measures ~10,400. |

---

## 6.4 ⚑ The Aquarium scheduled-transaction processor (NEW since e6a9752)

| env var | property | plain English | type | default | class |
|---|---|---|---|---|---|
| `SCHEDULING_TRANSACTION_PROCESSOR_ENABLED` | `scheduling.transaction-processor.enabled` | The Tank scheduled-transaction processor — the original Aquarium job, which **builds and SUBMITS** from the operator's wallet. | bool | ⛔ **`false`** | **C** — gates the whole bean via `@ConditionalOnProperty` with **no `matchIfMissing`**. ⚠ **A node upgrading across this change stops processing until it is set**, and nothing else complains: a disabled processor and a quiet one look identical. |
| `SCHEDULING_TRANSACTION_PROCESSOR_DELAY_MINUTES` | `scheduling.transaction-processor.delay-minutes` | Poll interval. | int | `5` | **A** |

## 6.5 ⇒ THE MINIMUM MAINNET ARMING SET — what actually turns the bot on

**Everything else is tuning. These are the dials that decide whether the node does anything.**

| env var | ships | to arm |
|---|---|---|
| `SCHEDULING_TRANSACTION_PROCESSOR_ENABLED` | `false` | `"true"` — the Aquarium scheduled-transaction processor |
| `AQUARIUM_LIQUIDATION_MODE` | `disabled` | `live` (or `shadow` to rehearse) |
| `AQUARIUM_COMPOUND_ENABLED` | `false` | `"true"` |
| `AQUARIUM_LIQUIDATION_PROFIT_MARGIN_LOVELACE` | `5000000` | lower it, or **the one margin** refuses work the operator wants |
| `LOANS_LIQUIDATION_CONVERT_ENABLED` | ⚑ `true` | nothing — it is already on. Set `"false"` to stop **all** conversions |
| `loans.liquidation.markets[]` | empty | nothing — an empty list means **convert every market at the node mode**. Listing a market is how an operator *deviates* |

⇒ **ONE MARGIN, then the market list.** Since 2026-09-09 there is a single
`AQUARIUM_LIQUIDATION_PROFIT_MARGIN_LOVELACE` governing every mode — convert included — and the only
per-mode, per-market control is `markets[]`. The convert path's own margin key is gone (§6.7).

⚠ **The margin is the one that surprises people.** It is **shared across every mode** and it is what
refused the first live convert. Arming a path and leaving this at 5 ada is a node that looks armed
and does nothing. ⛔ **And now it cuts both ways: lowering it for convert lowers it for anticipate
too.** That is the trade the one-knob merge makes, deliberately.

⚠ **Three ways to be armed and idle, all silent:** the global convert switch off, every market
`DISABLED` or `ANTICIPATE` without a cap that fits, or the shared margin above what the work earns.
None of them is an error, and all three read as a quiet market.

## 6.6 ⛔ NEVER OPERATOR-SETTABLE — validator literals, not configuration

| constant | value | why |
|---|---|---|
| `ConvertTxEncoder.MAX_BATCHER_FEE` | `2000000` | the order datum is compared with `equals_data`; a different value **fails the validator** |
| `ConvertEconomics.MINSWAP_ORDER_OVERHEAD` | `4000000` | the order's ada is checked `>=` against this; below it the order is refused, above it the bot overpays |

⇒ **These mirror literals in `lm_liquidate_and_convert_action.ak` at the deployed sha.** They move
only when FluidTokens redeploy, **in the same commit as the reference-script coordinate** (§57.9c).
**A chart that exposes them is a chart that can break every convert.**

### ⛔ `MINSWAP_ORDER_OVERHEAD` STAYS NON-CONFIGURABLE — and there is now a settable key beside it

⚠ **`LOANS_LIQUIDATION_CONVERT_MINSWAP_ORDER_COST_LOVELACE` (§5.5) is `4000000` too, and it is a
different thing.** Until 2026-09-09 one Java constant served two masters, and the split exists because
those two masters answer to different authorities:

| | the BUILDER's number | the ECONOMICS' number |
|---|---|---|
| **what it means** | what the order output must **CARRY** | what the bot **SPENDS** |
| **who decides** | `lm_liquidate_and_convert_action` — it checks `>=` | the operator's belief about cost |
| **where it lives** | `ConvertEconomics.MINSWAP_ORDER_OVERHEAD`, read by `ConvertOrderPlan` | `loans.liquidation.convert.minswap-order-cost-lovelace` |
| **settable?** | ⛔ **never** — a wrong value fails on chain | ✅ yes, **downward-bounded by the constant** |

⇒ **A configurable value must never reach order construction.** `ConvertOrderPlan`,
`ConvertTransactionBuilder` and `ConvertTxEncoder` read the constant and nothing else; the configured
key is read only by `ConvertEconomics.assess`. **`announceAndGuard` refuses at startup, naming the key,
if the configured cost is null, negative, or below the constant** — an operator may believe a convert
costs *more* than the order carries, but stating *less* understates a spend the chain will certainly
make.

## 6.7 ⛔ REMOVED — a chart must NOT pass these

| gone | why |
|---|---|
| `AQUARIUM_X_SUBMIT` / `loans.submittable-network` | removed on Giovanni's ruling: *"a barrier that silently blocks submission even when everything else is armed is a bug, not a safeguard"* |
| `LOANS_ENABLED` (`loans.enabled`) | removed: v4 indexing is unconditional — *"if the flag is flipped later we won't see old loans"* |
| `AQUARIUM_LIQUIDATION_ENABLED` | removed: redundant with the mode |
| `LOANS_LIQUIDATION_CONVERT_PROFIT_MARGIN_LOVELACE` (`loans.liquidation.convert.profit-margin-lovelace`) | ⚑ removed 2026-09-09, merged into the shared `AQUARIUM_LIQUIDATION_PROFIT_MARGIN_LOVELACE`: *"convert is a liquidation and profitMarginLovelace is literally the same as liquidation … it's just one knob"*. A chart still passing it silently gets the shared default instead of the number it thinks it set. |

⚠ **Passing a removed key is not an error and produces no warning** — it is simply ignored, which
reads exactly like it working.

## 6.8 ⚠ THE PREFIX TRAP — one reference-script key is `LOANS_`, not `AQUARIUM_`

Eight liquidation reference-script slots are `AQUARIUM_LIQUIDATION_REF_*`. **The ninth is not:**

```
LOANS_LIQUIDATION_REFERENCE_SCRIPTS_LM_LIQUIDATE_AND_CONVERT_ACTION
```
⇒ It is the **relaxed-binding form of `loans.liquidation.reference-scripts.…`, and it has no
`AQUARIUM_LIQUIDATION_REF_*` alias.** *A chart that names it by the pattern of the other eight passes
nothing, the slot stays empty, the convert script travels inline, and the transaction exceeds
`maxTxSize` — with no configuration error anywhere.*

## 7. Traps

### Trap 1 ⛔ — `sync-start-slot` is silently overridden by the stored cursor

**Changing `STORE_CARDANO_SYNC_START_SLOT` on a pod that has already synced does nothing at all.**
Verified in yaci-store `StartService` (0.1.7), from source, not documentation:

```java
Optional<Cursor> optional = cursorService.getStartCursor();
if (optional.isPresent()) from = new Point(optional.get().getSlot(), …);  // cursor WINS
else … storeProperties.getSyncStartSlot() …                               // only reached here
```

The pod restarts cleanly, resumes where it was, indexes nothing new, and **reports success**.

⇒ **A start-point change requires deleting the stored cursor, and the two must move together.**
The failure modes are asymmetric and only one is loud:
- *wipe rows, keep cursor* → total and immediate.
- *change config, keep cursor* → **a silent no-op** that looks like a wrong start point and sends
  the operator off to re-measure a number that was never read.

⚠ **`sync-start-*` is a LOWER BOUND, and indexing runs forward from it — an older value is strictly
safer than a newer one.** When the bot sees nothing, `sync-start` is almost never the cause; the
payment-credential filter is (trap 3).

**Chart implication:** expose both, keep the slot and blockhash **as one unit** in the values file so
they cannot drift apart, and put this warning *next to the value*, not in a linked doc.

### Trap 2 ⛔ — cursor cleanup OOMs the node after a long initial sync

yaci-store **0.1.7** (what this app pins): `CursorRepository.deleteByIdAndBlockLessThan` is a bare
Spring Data **derived** delete, so `CursorCleanupScheduler` loads every matching row into one
persistence context. Observed 2026-08-25 on preview: ~1.65M cursor rows from a 45-minute sync, a
2.1 GiB heap spike, `EntityEntryContext.addEntityEntry` OOM. **Restarts do not drain the backlog**,
so it presents as a hard crash loop — and the pod dies by *failed liveness probe*, i.e. **SIGTERM,
never `OOMKilled`**, so grepping events for `OOMKilled` finds nothing.

Fixed upstream in `2764ca6` but **in no release**; `0.1.7 → 2.0.x` is a migration, not a bump.
**Configuration is the only lever, and both knobs are absent from yaci-store's documentation** —
they exist only in source, and **this app sets neither in any profile**.

⇒ **Set `STORE_CARDANO_CURSOR_CLEANUP_INTERVAL=60` before any first mainnet sync.** It bounds a sync
that is running; it does not drain a backlog already accumulated.

### Trap 3 ⛔ — a dead deployment is indistinguishable from a quiet market

Preview v4 has been redeployed **three times**. `LoansConfigVerifier` **does not detect this** — it
locates the config NFTs by the policy id it is pinned to, and a redeploy mints *new* NFTs under a
*new* policy id while leaving the old ones unburnt. **The pinned coordinates therefore keep verifying
cleanly forever.** The cause is the payment-credential filter: `TankUtxoStorage` keeps only UTxOs at
credentials derived from the pinned policy ids, so the new deployment's UTxOs are indexed off the
chain and then **discarded at write time, leaving no trace they were ever offered**.

⇒ A node pinned to a dead deployment **boots clean, verifies clean, and reports zero candidates.**
**Treat a persistently empty world as a suspected redeploy**, and read `GET …/loans/liquidations`:
`bonds_scanned`, `settled` and `unreadable` **together**. Both counts zero means nothing was found at
all — an indexing problem, not a quiet market. And `unreadable` must be 0: while it is not, `settled`
cannot be trusted either, because a loan that is present but illegible is counted as one that is gone.

### Trap 4 ⛔ — the profit floors, and what a negative margin does *not* do

Three things a values file must say, because two of them are actively counter-intuitive:

1. **A negative `profit-margin-lovelace` does NOT hard-fail on mainnet.** Findings §31 removed that
   guard on Giovanni's ruling — *"operating at a loss MUST be implemented even on mainnet"*.
   `guardMainnetNegativeMargin()` **warns**; it does not throw. The same is true of the compound
   margin, and of the convert path — which since 2026-09-09 **reads this very key**, so a negative
   value here now warns twice, once per path, and lowers the bar for both. ⚠ Two stale comments in
   the tree still claim otherwise; this catalogue is the correct account. **Only `ignore-profit-check` is startup-fatal on mainnet**, and only
   `convert.dex-cost-floor-lovelace` is fatal when negative on *every* network.
2. **A negative margin ALONE changes nothing.** The absolute floor refuses a negative `floorProfit`
   *independently*, and the margin is deliberately outside that number. Measured on preview
   2026-08-24: a convert logged `floor -27303331; - margin -100000000 = 72696669` and was **still
   refused** `NOT_PROFITABLE`. ⇒ **To operate at a loss, `MIN_PROFIT_ABSOLUTE_LOVELACE` and
   `MIN_EXPECTED_PROFIT_LOVELACE` must move together.** Setting one leaves the bot refusing exactly
   as before.
3. ⚑ **The gate scores ANTICIPATE on the fee slice, not on what it returns** (the open **O-2** seam).
   `floorProfit`'s income term is a mark-to-oracle *token* slice while `minAdaFunded` is the ADA the
   bot fronts — so the floor compares ADA out against tokens in. Measured on the live mainnet loan
   `cae82d7d…`: the gate sees 1,667,591 lovelace against a real return of **+4,219,239**. **With the
   shipped default margin of 5,000,000 that genuinely profitable loan is REFUSED even fully armed.**
   ⇒ Do not present the floors as pure risk appetite. Until O-2 lands, an operator who wants the
   ANTICIPATE path to act will have to state floors that look like losses and are not.

### Trap 5 ⛔ — the convert reference script is configurable but **inert**

`LOANS_LIQUIDATION_REFERENCE_SCRIPTS_LM_LIQUIDATE_AND_CONVERT_ACTION` binds correctly and is
validated at startup, **but `ConvertLiquidationRouter` passes `Map.of()` to the builder** — the
coordinate never reaches the transaction. FluidTokens has published the script on mainnet
(`56840ffb…#0`), and until the router is wired, a convert transaction carries its validators inline
at **20,548 bytes against a 16,384 limit** and cannot be submitted.

⇒ **Expose the key** (it is real, and it will work the moment the router is fixed) **and say in the
values comment that the convert path is not yet buildable on mainnet for this reason.** The fix is
app code and is Giovanni's call.

### Trap 6 ⚠ — a malformed key that sets nothing

`application.yaml` carries `store.blocks.epoch-calculation-interval=14400:` — the `=14400` is **part
of the key name** and the value is null. The real property is
`store.epoch-aggr.epoch-calculation-interval`. It sets nothing, and is harmless only because
`EpochProcessor` is `@ConditionalOnProperty(matchIfMissing = false)` and the `epoch-aggr` starter is
not on the classpath. **Do not model this key in the chart**; it is an app-side defect, not a knob.

---

### Trap 7 ⛔ — "not configured" and "configured wrong" are different, and only one may fail

*(own)* With `loans.enabled` gone there is no switch left that stops `LoansContractRegistry` and
`LoansConfigVerifier` being built, so **a bare install reaches both on every node.** A public chart
ships every contract coordinate empty, which makes this the default path, not an edge case.

**Measured 2026-09-04, and the surprising half first:** blank coordinates **do not fail**. `b("")` is
a legal empty bytestring, every `applyParamToScript` succeeds, and the registry comes up holding a
full set of plausible 56-hex hashes derived from nothing — plus the blank policy id itself, which
`AddressProvider` turns into the perfectly valid address `addr1wy22xa6y`. Feeding those seven
credentials to the write-time filter would give a fresh install the exact pathology of trap 3:
**boots clean, indexes at credentials no UTxO will ever carry, reports an empty world.**

⇒ The app now distinguishes **three** states, and a chart should mirror them in its values comments:

| coordinates | node does | why |
|---|---|---|
| **blank** | starts clean, derives nothing, **indexes nothing for v4**, WARNs, contacts no provider | "not configured yet" is legitimate for a fresh install and must never crash |
| **well-formed** | derives, indexes, verifies against chain | the normal path |
| ⛔ **present but malformed** | **fails at startup, naming the key** | a typo is not an absence; reading it as "not configured" would leave an operator who fat-fingered one character with a node reporting a quiet market |

⛔ **The mismatch hard-fail is UNCHANGED and must stay.** `LoansConfigVerifier` still refuses to start
when the derived hashes disagree with the live config datums. Unlike the network gate removed the
same day, **that one is a real safeguard**: a stale policy id verifies cleanly forever against a dead
deployment (trap 3), so it is the only thing that catches a redeploy. **Absent is a Tuesday; stale is
a fault.**

⇒ **Chart implication:** `loans.config.policyId` / `lmConfig.policyId` may ship empty and a bare
`helm install` starts. Do **not** add a chart-side gate that skips them — the app owns that
distinction now, and a second one would drift.

---

## 8. What is NOT in this app, and belongs to the chart

- **Maintenance mode / `sleep infinity`.** No such flag exists in the image; the entrypoint is a bare
  `["java","-jar","app.jar"]`. If the chart wants a maintenance pod it must override the container
  `command`, not set an env var.
- **Replicas.** ⛔ **Singleton — a hard requirement**, not a preference. Two replicas mean two
  indexers on one database and two bots racing to spend the same wallet UTxOs.
- **Probes, ports, persistence, resources.** `docs/k8s-deployment-requirements.md` §4–§7.
  `/healthcheck` is **outside** `apiPrefix`.
- **`POSTGRES_HOST` / `POSTGRES_PORT` / `POSTGRES_DB` / `DB_DRIVER` / `DB_DIALECT`.** These appear in
  `docker/.env.example` but the app reads **only** the assembled `DB_URL`; compose uses them to build
  it. A chart that builds `DB_URL` itself does not need them.

---

## 9. A safe default posture, in full

Everything below is what the chart should ship, and all of it is already the app's own default. The
node indexes, serves the API, and **cannot move value**:

```yaml
# LOANS_ENABLED, LOANS_SUBMITTABLE_NETWORK and AQUARIUM_LIQUIDATION_ENABLED are GONE
# (2026-09-04) — do not emit them.
AQUARIUM_LIQUIDATION_MODE: "disabled"
AQUARIUM_COMPOUND_ENABLED: "false"
AQUARIUM_LIQUIDATION_IGNORE_PROFIT_CHECK: "false"   # fatal on mainnet if true
LOANS_UI_ENABLED: "false"                     # no authentication
AQUARIUM_LIQUIDATION_PROFIT_MARGIN_LOVELACE: "5000000"
AQUARIUM_LIQUIDATION_MIN_PROFIT_ABSOLUTE_LOVELACE: "0"
AQUARIUM_LIQUIDATION_MIN_EXPECTED_PROFIT_LOVELACE: "0"
AQUARIUM_LIQUIDATION_CHECK_PROFITABILITY: "true"
LOANS_LIQUIDATION_CONVERT_ENABLED: "true"     # fronts no capital; failure mode is a no-op
LOANS_LIQUIDATION_CONVERT_MINSWAP_ORDER_COST_LOVELACE: "4000000"  # what a convert SPENDS; ≥ the constant
LOANS_LIQUIDATION_CONVERT_DEX_COST_FLOOR_LOVELACE: "5000000"
# markets[] deliberately EMPTY: with the mode disabled above it changes nothing, and an empty list
# means "convert every market at the node mode" the moment the mode is raised (§5.5).
```

⚑ **`LOANS_LIQUIDATION_CONVERT_PROFIT_MARGIN_LOVELACE` is absent from this list because it no longer
exists** (2026-09-09, §6.7). The margin above is the only one, and it governs convert too.

**Arming is now targeting plus two deliberate acts**, and the runbook
(`docs/operating-the-liquidation-bot.md` §14) is what walks them:
point `NETWORK` / the profile at the chain → supply the config policy ids → publish **and** configure
the reference scripts → `AQUARIUM_LIQUIDATION_MODE=live`.
⛔ **There is no longer a switch after that.** A node targeted at a chain and armed **acts on it** —
that coherence is the whole point of removing the submit gate.

### Trap 8 ✅ — the `@Value`-only key a profile could not reach (**CLOSED 2026-09-09**)

**Historically the largest silent gap in this surface, and it is now shut.** Eleven keys existed only
as inline `@Value` defaults with **no yaml line at all**. ⛔ **A profile document can only override a
key that EXISTS** — so the `preview` document had nothing to blank, and a preview node resolved
**mainnet** values.

**It was not theoretical.** Observed running on preview: `loans.minswap.pool-address` resolved the
mainnet DEX pool address, sent it to a preview provider, and took back
`400 "Invalid address for this network"` — at ERROR, with a stack trace, **for every candidate on
every scheduling cycle.** ⚠ **The three sibling Minswap keys had the identical defect and produced no
symptom at all**, because they only fed a derivation nobody had published. *The loud one was the
lucky one.*

**Every key now has a yaml line, per profile**, and the annotations carry **no inline default** —
either `${key:}` for strings, or **no default at all** where the type cannot bind an empty string
(a boolean or a `BigInteger`: `${key:}` would fail at startup with a *conversion* error rather than a
missing-key one). ⇒ **A node whose configuration omits one of those does not boot**, which is the
intended reading of "the yaml is where this lives".

**Guarded by `MinswapPoolAddressIsPerProfileTest`, which asserts the ABSENCE of an inline default on
the annotation itself.** *Asserting the resolved value cannot catch a re-added default: the mainnet
document supplies the same value, so nothing on the mainnet path would change and every other test
would still pass.*
