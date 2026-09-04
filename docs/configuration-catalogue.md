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
| **2. `@Value`-only keys** — no yaml line at all, default inline in Java | 11 | grep `@Value` | `@Value("${loans.liquidation.convert.enabled:true}")` |
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
| `LOANS_SUBMITTABLE_NETWORK` | `loans.submittable-network` | ⛔ **The submit gate.** The one network this node may sign and submit on. Compared to `network`; unequal ⇒ every build stops at veto **S3**. | network name | **`preview`** *(layer 2 — no yaml line)* | `preview` | ⛔ **C** — **the default IS the protection.** Mainnet is the default *profile*, so this default deliberately leans the other way. Setting it to `mainnet` is a deliberate written act. | (own) |
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
| `LOANS_ENABLED` | `loans.enabled` | ⛔ **The master switch for everything lending.** False ⇒ the beans are not constructed at all: no scanner, no bot, no UI, no market validation. | **`false`** | `true` | ⛔ **C** — false on mainnet by design. Turning it on is step 1 of arming. |
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
| `AQUARIUM_LIQUIDATION_ENABLED` | `loans.liquidation.enabled` | The separate arming flag. **Submitting needs BOTH** `mode==live` **and** this — one switch is too easy to flip by accident. | bool | **`false`** | inherits | ⛔ **C** |
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
- ⚑ **An UNLISTED market is `action: CONVERT` at the node mode.** An empty list means *convert
  everywhere at whatever posture the node is in* — **it does not mean "do nothing"**. Listing a
  market is how an operator *deviates*.
- ⛔ **The node mode is a CEILING**: effective = `min(nodeMode, marketMode)` over
  `DISABLED < SHADOW < LIVE`. A market may be more restrictive, never less. A market asking for
  `LIVE` under a `shadow` node runs as `SHADOW` and says so at boot.
- **Startup aborts** on: a missing/blank unit, a malformed unit, a duplicate unit, an empty action,
  an `ANTICIPATE` entry with no cap, a negative cap. A `CONVERT` entry with a cap **warns** and
  ignores it.

### 5.5 The convert path (layer 2 — no yaml lines)

| env var | property | plain English | type | default | class |
|---|---|---|---|---|---|
| `LOANS_LIQUIDATION_CONVERT_ENABLED` | `loans.liquidation.convert.enabled` | The Minswap conversion mechanism, globally. | bool | ⚑ **`true`** | **A** — *the one arming flag in this codebase that defaults ON*, on Giovanni's ruling: it fronts no capital and its failure mode is a no-op, not a loss. It is still behind `loans.enabled`, the mode, the arming flag and the submit gate. |
| `LOANS_LIQUIDATION_CONVERT_PROFIT_MARGIN_LOVELACE` | `loans.liquidation.convert.profit-margin-lovelace` | What `feeValue − (txFee + orderAda)` must reach. `0` refuses every net loss while allowing break-even — which is what refuses a `liquidationFeePerMille = 0` bond out of the box. | lovelace, **may be negative** | `0` | **A** — honoured on **every** network, mainnet included. A negative value WARNs loudly at boot; it never hard-fails. |
| `LOANS_LIQUIDATION_CONVERT_DEX_COST_FLOOR_LOVELACE` | `loans.liquidation.convert.dex-cost-floor-lovelace` | The assumed cost of one DEX interaction (batcher + fee), as a **floor** on the charged outlay, not an addend: the gate charges `max(txFee + orderAda, this)`. | lovelace, **≥ 0** | `5000000` | ⛔ **C** — **negative or unset throws at startup, on every network.** A negative cost of doing work is a typo, not a bound. |

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
   `guardMainnetNegativeMargin()` **warns**; it does not throw. The same is true of the compound and
   convert margins. ⚠ Two stale comments in the tree still claim otherwise; this catalogue is the
   correct account. **Only `ignore-profit-check` is startup-fatal on mainnet**, and only
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
LOANS_ENABLED: "false"                        # mainnet default; "true" on preview
AQUARIUM_LIQUIDATION_MODE: "disabled"
AQUARIUM_LIQUIDATION_ENABLED: "false"
LOANS_SUBMITTABLE_NETWORK: "preview"          # the submit gate — NOT the network selector
AQUARIUM_COMPOUND_ENABLED: "false"
AQUARIUM_LIQUIDATION_IGNORE_PROFIT_CHECK: "false"   # fatal on mainnet if true
LOANS_UI_ENABLED: "false"                     # no authentication
AQUARIUM_LIQUIDATION_PROFIT_MARGIN_LOVELACE: "5000000"
AQUARIUM_LIQUIDATION_MIN_PROFIT_ABSOLUTE_LOVELACE: "0"
AQUARIUM_LIQUIDATION_MIN_EXPECTED_PROFIT_LOVELACE: "0"
AQUARIUM_LIQUIDATION_CHECK_PROFITABILITY: "true"
LOANS_LIQUIDATION_CONVERT_ENABLED: "true"     # fronts no capital; failure mode is a no-op
LOANS_LIQUIDATION_CONVERT_PROFIT_MARGIN_LOVELACE: "0"
LOANS_LIQUIDATION_CONVERT_DEX_COST_FLOOR_LOVELACE: "5000000"
```

**Arming is four deliberate acts, in this order**, and the runbook
(`docs/operating-the-liquidation-bot.md` §14) is what walks them:
`LOANS_ENABLED=true` → reference scripts published and configured → `AQUARIUM_LIQUIDATION_MODE=live`
→ `AQUARIUM_LIQUIDATION_ENABLED=true` → `LOANS_SUBMITTABLE_NETWORK=mainnet`.
