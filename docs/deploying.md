# Deploying the Aquarium Node

For an operator standing up a node for the **first time**. If you already run one, read
[upgrading.md](upgrading.md) instead — the sequence is different and the differences matter.

This guide covers **Docker Compose only**. Systemd, Kubernetes and Nomad all work; translating is
left to you, and everything here about ordering, secrets and exposure applies unchanged.

---

## 0. The short version

If you just want a node running, this is the whole of it:

```bash
git clone https://github.com/FluidTokens/ft-aquarium-node.git
cd ft-aquarium-node/docker
cp .env.example .env && chmod 600 .env     # it will hold a seed phrase
# fill in six values, then:
docker compose up -d
docker compose logs -f aquarium
```

**Six values**: your relay host and port, a Blockfrost key, a dedicated wallet mnemonic, a database
password, and the image version to pin. Everything else has a working default.

**You never set a contract address, a script hash or a sync start point.** Those ship inside the
image, verified against the chain before release. When FluidTokens redeploy, you bump
`AQUARIUM_DOCKER_IMAGE_VERSION` — that is the entire upgrade.

Then wait for the tip (§6). The rest of this guide is for when you want more than that: arming the
bot (§8), market policy (§9), the UI (§7). **A node that never reads those sections is a correct
node** — it indexes, serves `/healthcheck`, and touches no funds.

---

## 1. Before you start

| you need | notes |
|---|---|
| Docker with the Compose plugin | `docker compose version` should print v2.x |
| A **Cardano relay** to connect to | local node, or any reachable relay. This is the chain feed |
| A **Blockfrost project key** | the free plan is sufficient. This is how transactions are submitted |
| A **dedicated wallet mnemonic** | see §2. Never an existing wallet |
| **30,000 FLDT** | delegated to the node, to be allowed to process scheduled transactions |
| RAM and disk | the shipped Compose file caps the node at **4 GB** (`AQUARIUM_MEM_LIMIT`). Postgres grows with how far back you sync; we do not publish a measured disk figure, so watch it on the first sync rather than trusting a number |

---

## 2. Keys and secrets — read this before creating anything

**The node holds a funded wallet mnemonic in its process environment.** Everything else in this
guide is configuration; this part is the one where a mistake costs money.

**⛔ Use a dedicated mnemonic. Generate a new wallet for this and use it for nothing else.** Not your
personal wallet, not a wallet you have used elsewhere, not one whose seed has ever been typed into
anything. The node signs with it automatically and unattended — that is its job.

**The node needs very little in it.** Roughly 10 ADA to operate, plus whatever principal you are
willing to front *if* you enable `ANTICIPATE` markets (§9). **The 30,000 FLDT does not live here** —
it is delegated to this wallet from a separate wallet, which can be cold. That separation is the
point: the hot wallet the node signs with never has to hold the stake.

**Where the secret lives:** `docker/.env`, as `WALLET_MNEMONIC` and `BLOCKFROST_KEY`.

```bash
cp docker/.env.example docker/.env
chmod 600 docker/.env          # not world-readable; it holds a seed phrase
```

`docker/.gitignore` already excludes `.env`, so it cannot be committed by accident from this repo.
**If you copy this configuration into your own repo, re-check that** — an ignore rule that does not
match the filename you actually used protects nothing.

**Do not pass the mnemonic on a command line** (`-e WALLET_MNEMONIC=...`): it lands in your shell
history and in the process table, visible to every other user on the box. The `env_file` mechanism
this Compose file uses avoids both.

**Rotation:** there is no in-place rotation. Generate a new wallet, move funds, re-delegate the
FLDT, change `.env`, restart. Treat a leaked mnemonic as a wallet to be emptied immediately, not one
to be secured.

---

## 3. Get the files

```bash
git clone https://github.com/FluidTokens/ft-aquarium-node.git
cd ft-aquarium-node/docker
```

You do **not** need Java, Gradle or a build toolchain. The Compose file pulls a published image that
CI built from `main` **after** running the full test suite — a red tree never becomes an image. If
you would rather build it yourself, see §11.

---

## 4. Configure

Two files, and the split is the point.

### `docker/.env` — the six things that are yours

Copied from `.env.example`, which contains **only** what you must set:

| variable | what it is |
|---|---|
| `STORE_CARDANO_HOST`, `STORE_CARDANO_PORT` | your Cardano relay — the chain feed |
| `BLOCKFROST_KEY` | your Blockfrost project key — how transactions are submitted |
| `WALLET_MNEMONIC` | the dedicated wallet from §2 |
| `DB_USERNAME`, `DB_PASSWORD` | credentials for the bundled Postgres. Pick a real password |
| `AQUARIUM_DOCKER_IMAGE_VERSION` | ⚠ **pin it**, so a restart months from now cannot silently change what you run |

Everything else the stack needs — image name, database host, port, name, schema — has a default in
`docker-compose.yaml` and works unset.

**If `DB_PASSWORD` is missing, Compose refuses to start and says so.** It is not defaulted on
purpose: a database that comes up with an empty password because a variable was misspelled is
exactly the failure that should be loud.

### `docker/.env.advanced.example` — a menu, not a second file

Optional settings: arming the processor and the bot, the profit margin, market policy, the UI, the
memory limit, the network profile. **Compose does not read this file.** Copy the lines you want into
your `.env` and restart.

Everything in it ships in a safe position, so an operator who never opens it gets a node that
indexes the chain, serves `/healthcheck`, and touches no funds. Read §8 before changing any of the
arming values.

### ⛔ 4c. Contract coordinates are not configuration

`LOANS_CONFIG_POLICY_ID`, `LOANS_LM_CONFIG_POLICY_ID`, every `AQUARIUM_LIQUIDATION_REF_*`,
`AQUARIUM_COMPOUND_REFERENCE_SCRIPTS`, and the sync start point are **deliberately absent from both
files.** They describe where FluidTokens' contracts live on chain, they ship in the image verified
against the chain before release, and they change when FluidTokens redeploy.

**A stale override is strictly worse than no override.** It silently replaces a maintained value
with a frozen one. Set one today and it is correct; the next redeploy makes it wrong, your override
wins over the corrected default, and the failure is not a crash — it is a node that starts cleanly,
verifies cleanly, and quietly watches a dead deployment.

**⇒ When FluidTokens redeploy, you bump the image tag. Nothing else.** If you have a genuine reason
to override one, §11 explains how to verify a coordinate yourself, and **keeping it current becomes
yours**.

### What happens when only part of the deployment moves

FluidTokens sometimes change one side of the contract set. If that touches a validator this node
**never invokes**, the node starts, and logs a warning naming each field:

```
⚠ Lending v4: 3 config field(s) do not match the chain, in validators THIS NODE NEVER INVOKES.
```

That is not an error you need to act on, and it does not need a config change. It means a new image
is coming. If a field the node **does** use has moved, startup fails instead, naming the field —
because then the node genuinely cannot work against that deployment.

---

## 5. Start it

```bash
cd docker
docker compose up -d
docker compose logs -f aquarium
```

Compose starts Postgres first and waits for it to report healthy before starting the node — the
ordering is already expressed in the file, so there is no manual sequencing to do.

**Expect the first run to take a while.** It is indexing the chain from the configured start point.

---

## 6. Is it synced?

```bash
docker logs aquarium --tail 50
```

While syncing you will see the cursor advancing in large steps. You are **at the tip** when the
block number climbs one at a time, roughly every twenty seconds:

```
c.b.c.y.s.c.service.CursorServiceImpl : # of blocks written: 1
c.b.c.y.s.c.service.CursorServiceImpl : Block No: 11525533
```

Compare that block number against any chain explorer. Until it matches, the node is still catching
up and **an empty loan list means nothing yet**.

The health endpoint, from **on the box**:

```bash
curl -s http://localhost:8080/healthcheck | jq .
```

```json
{ "db_ok": true, "parameters_ok": true, "parameters_ref_input_ok": true,
  "wallet_ok": true, "staking_ok": true }
```

Also grep the logs for `HEALTH` — the node reports a wallet with no usable UTxO, or no FLDT
delegated, as non-fatal warnings rather than refusing to run.

---

## 7. Reaching the UI — an exposure decision, not a port mapping

The node serves an operator UI at `/api/v1/loans/readiness` when `LOANS_UI_ENABLED=true`. It shows
live loan positions, health factors, what capital would be required, and what the bot decided.

**⛔ The shipped Compose file publishes no port for the node, and you should not add one.** There is
**no authentication in front of the UI.** Publishing `8080` puts your position data — and a read of
your operating posture — on the public internet for anyone who scans the host.

**On a public or cloud host — use an SSH tunnel:**

```bash
ssh -L 8080:localhost:8080 you@your-host
# then open http://localhost:8080/api/v1/loans/readiness in your own browser
```

The port stays bound to loopback on the server. Nothing is exposed, access follows your existing SSH
credentials, and there is no extra surface to secure.

**On a private, isolated network** — a home LAN, a VPN, a segment with no ingress — publishing the
port is reasonable, because the network boundary is doing the work an authentication layer would
otherwise have to. Add this under `aquarium-node`, and bind it to the interface you mean:

```yaml
    ports:
      - "10.0.0.5:8080:8080"   # a specific private address, never 0.0.0.0
```

**Do not put it behind a plain reverse proxy and call that done.** A proxy without authentication
just moves the open door. If you want browser access from outside, terminate TLS *and* require auth
(mTLS, an identity proxy, basic auth over TLS at minimum).

### ⛔ `LOANS_UI_ENABLED=false` does NOT close port 8080

It is easy to read the flag as an on/off switch for the whole surface. It is not. Only
`LiquidationReadinessController` and the Thymeleaf engine are conditional on it; **every JSON
endpoint is unconditional and stays up.** What an unauthenticated caller reaches either way:

| path | up when UI is off? | what it discloses |
|---|---|---|
| `GET /api/v1/loans` | **yes** | every indexed loan: amounts, assets, remaining debt, equity, current LTV, liquidatability |
| `GET /api/v1/loans/liquidations` | **yes** | every decision the bot made and why |
| `GET /api/v1/loans/oracle` | **yes** | the oracle feeds it is using |
| `GET /healthcheck` | **yes** | five booleans |
| `GET /actuator/health`, `/actuator/info`, `/actuator/prometheus` | **yes** | build commit, dirty flag, and runtime metrics |
| `GET /api/v1/loans/readiness` | no — this is the only thing the flag removes | the HTML operator page |

**⇒ Turning the UI off narrows the disclosure; it does not end it.** The exposure decision above is
about the *port*, not about the flag. Tunnel it or keep it on a private network either way.

---

## 8. Arming the bot

**Everything ships off.** Turning it on is a sequence, and the middle step is the one worth not
skipping.

**Step 1 — run it disabled.** Let it sync fully. Confirm the health endpoint is clean and that the
loan list looks like the chain.

**Step 2 — `shadow`.** Set `AQUARIUM_LIQUIDATION_MODE=shadow` and restart. The bot now scans,
prices, builds and size-checks real transactions, and **refuses to submit every one of them.** This
is a rehearsal against real loans at no risk. Read the result:

```bash
curl -s http://localhost:8080/api/v1/loans/liquidations | jq .
```

Every decision and its reason is there. **It is held in memory — read it before you restart.**

**Step 3 — `live`.** Only once shadow shows the decisions you expect.

**⚠ Three ways to be armed and idle, all silent, none an error:**

1. `LOANS_LIQUIDATION_CONVERT_ENABLED=false` — the node converts nothing, whatever markets say
2. every market `DISABLED`, or `ANTICIPATE` with a `cap` below what the loan needs
3. the shared profit margin set above what the work actually earns

All three look exactly like a quiet market. The liquidations endpoint is what tells them apart.

**⛔ One margin governs every mode.** `AQUARIUM_LIQUIDATION_PROFIT_MARGIN_LOVELACE` (default
`5000000`) applies to plain, anticipate and convert alike. There is no separate convert margin —
lowering it to take on convert work lowers it for anticipate work too. That is deliberate, and it is
the setting that most often makes a node look armed while doing nothing.

**⚑ And margin `0` on a convert is not break-even.** The Minswap order carries a fixed outlay
(`LOANS_LIQUIDATION_CONVERT_MINSWAP_ORDER_COST_LOVELACE`, default `4000000`), but the gate is
`max(txFee + orderCost, dex-cost-floor-lovelace)` — a **floor over the whole sum**, not an addend —
and the shipped floor (`5000000`) binds whenever `txFee < 1000000`, which is true of most
liquidations. So at shipped defaults a convert must clear **5,000,000 lovelace of outlay** before
margin `0` counts as break-even, and **≥ 10,000,000 lovelace of oracle-valued fee slice** once the
shipped 5 ADA margin is added.

---

## 9. The market specification

Only relevant if you run the lending-v4 liquidation bot.

When a loan is liquidated the bot builds one of two transactions:

| action | what it does | what it costs you |
|---|---|---|
| `CONVERT` | sells the collateral through a Minswap V2 order to repay the lender | **no capital** — a transaction fee and the ~4 ADA the validator makes the order carry |
| `ANTICIPATE` | repays the lender **from your own wallet** and keeps the collateral | **your capital**, up to a `cap` you must state |

**⛔ `unit` is the loan's PRINCIPAL asset — what was lent — not the collateral.**

This is the mistake to avoid and it is completely silent. An entry keyed by the collateral is
well-formed, boots cleanly, logs at startup — and **matches no loan.** That market is then treated
as *unlisted*, **and an unlisted market CONVERTS by default.** A `mode: DISABLED` written against
the wrong asset therefore does the exact opposite of what it says, with no error anywhere.

**`CONVERT` is the default everywhere.** You do not list a market to get it: no markets listed means
every market converts, provided `LOANS_LIQUIDATION_CONVERT_ENABLED` is true (it ships `true`).
**Listing a market is how you deviate.**

Two controls, and a convert needs both to permit it:

1. `LOANS_LIQUIDATION_CONVERT_ENABLED` — global. `false` means this node touches no DEX, ever
2. `LOANS_LIQUIDATION_MARKETS_*` — per market: override the action, cap what you will front, or
   disable one market while the rest keep working

### Worked example — three markets

Convert ADA-principal loans; anticipate a token-principal market you do not trust a pool for, capped
at 500 **of that token**; stay out of a third entirely.

```bash
LOANS_LIQUIDATION_MARKETS_0_UNIT=lovelace
LOANS_LIQUIDATION_MARKETS_0_ACTION=CONVERT

LOANS_LIQUIDATION_MARKETS_1_UNIT=577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e0014df10464c4454
LOANS_LIQUIDATION_MARKETS_1_ACTION=ANTICIPATE
LOANS_LIQUIDATION_MARKETS_1_CAP=500000000

LOANS_LIQUIDATION_MARKETS_2_UNIT=1111111111111111111111111111111111111111111111111111111144554d4d59
LOANS_LIQUIDATION_MARKETS_2_MODE=DISABLED
```

The index groups the fields of one market. The same thing in YAML, if you configure that way:

```yaml
loans:
  liquidation:
    markets:
      - unit: lovelace
        action: CONVERT
      - unit: 577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e0014df10464c4454
        action: ANTICIPATE
        cap: 500000000
      - unit: 1111111111111111111111111111111111111111111111111111111144554d4d59
        mode: DISABLED
```

### Field reference

- **`unit`** — the loan's **principal** asset. `lovelace`, or the principal's policy id (56 hex
  chars) followed by its asset name in hex, no separator. **Required**; anything malformed aborts
  startup by name.
- **`action`** — `CONVERT` or `ANTICIPATE`. Defaults to `CONVERT`; never inferred from `cap`.
- **`cap`** — the most principal the bot may front in that market, in that asset's own unit.
  **Mandatory for `ANTICIPATE` and the node refuses to start without it** — anticipating uncapped is
  unbounded exposure. Ignored with a warning on a `CONVERT` market.
- **`mode`** — `DISABLED`, `SHADOW` or `LIVE` for that market alone. Omit to inherit the node mode.

**⚠ The node mode is a ceiling.** A market asking for `LIVE` on a node running `shadow` runs as
`SHADOW`, and says so at boot. A market may be more restrictive than the node, never less.

**⛔ The node refuses to start** on an entry that cannot mean anything: missing or malformed `unit`,
duplicate `unit`, `ANTICIPATE` with no `cap`, or a negative `cap` on an `ANTICIPATE` entry. That is
deliberate — a typo that quietly disabled one market would look exactly like a market with no
liquidations in it.

---

## 10. Security checklist

- [ ] `WALLET_MNEMONIC` is a **dedicated** wallet, used for nothing else
- [ ] `docker/.env` is `chmod 600` and not in any repository
- [ ] The 30,000 FLDT is delegated **from a separate wallet**, ideally cold
- [ ] No `ports:` mapping on `aquarium-node` — UI reached by tunnel or private network (§7)
- [ ] You have not published 8080 on the strength of `LOANS_UI_ENABLED=false` — that flag
      removes the HTML page only, and `/api/v1/loans` still serves your positions (§7)
- [ ] Postgres is on `127.0.0.1` (the shipped file does this; check if you edited it)
- [ ] A real `DB_PASSWORD`
- [ ] The image version is **pinned**, not `latest`
- [ ] Host firewall allows only what you intend — the node makes **outbound** connections to your
      relay and Blockfrost; it needs no inbound at all
- [ ] Log rotation is on (the shipped file sets it) — logs are verbose and the disk is shared with
      your database

**What the node never does:** it does not accept inbound commands, has no admin endpoint, and
nothing it serves can move funds. The exposure risk from the UI is **disclosure** — your positions
and posture — not remote control.

---

## 11. Advanced

### Building the image yourself

Reasonable if you want to verify what you run. You need JDK 21:

```bash
./gradlew bootJar          # the jar CI would have built
docker build -t ft-aquarium-node:local .
```

Then point `AQUARIUM_DOCKER_IMAGE_NAME`/`_VERSION` at it and set `pull_policy: never`.

**The image records the commit it was built from**, and reports it on **`/actuator/info`** — not on
`/healthcheck`, which carries five booleans and nothing else:

```bash
curl -s http://localhost:8080/actuator/info | jq .build
# { "commit": "2776163…", "commitShort": "2776163…", "dirty": "false", "time": "…" }
```

It is also the first line of the startup banner, so `docker compose logs` has it without curl. Build
from a dirty tree and it says `dirty: true`. Build with no `.git` and it reports `unknown` rather
than guessing — which is why the Dockerfile is deliberately not multi-stage.

### Overriding a contract coordinate

If you must (§4c), verify it first rather than trusting a value someone pasted you. For any
`txHash#index`, ask the chain what script it actually publishes:

```bash
curl -s -H "project_id: $BLOCKFROST_KEY" \
  "https://cardano-mainnet.blockfrost.io/api/v0/txs/<txHash>/utxos" \
  | jq '.outputs[] | select(.output_index==<index>) | .reference_script_hash'
```

**Compare that against the hash the node derives** — the boot log prints the derived set
(`Derived Lending v4 contract hashes: ...`). A coordinate that resolves, is unspent, and publishes
*a* script can still be the wrong script; only comparing the hashes catches it, and that failure
mode has cost real debugging time.

---

## 12. When something looks wrong

**No loans, ever.** First check §6 — an unsynced node is the usual answer. If it is at the tip,
`GET /api/v1/loans/liquidations` reports what the last scan saw, including an `unreadable` count.
**A persistently empty world on a synced node should be treated as a suspected contract redeploy**,
not as a quiet market — see [upgrading.md](upgrading.md).

**Armed but never acts.** The three silent-idle causes in §8. The liquidations endpoint names the
veto for every candidate.

**A setting seems to do nothing.** Confirm it actually reached the process:

```bash
docker compose exec aquarium-node env | grep AQUARIUM_
```

**Node will not start.** Startup verification is strict on purpose: it refuses to run against
contract coordinates that do not match what it derives, rather than running against the wrong ones.
The message names the key at fault.
