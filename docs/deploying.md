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

**Six values**, plus one optional one: your relay host and port, a Blockfrost key, a dedicated wallet mnemonic,
a database password, and the image version to pin. Everything else has a working default.

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
willing to front *if* you enable `ANTICIPATE` or `CONVERT_BEFORE_ANTICIPATE` markets (§9). **The 30,000 FLDT does not live here** —
it is delegated to this wallet from a separate wallet, which can be cold. That separation is the
point: the hot wallet the node signs with never has to hold the stake.

**A node in `shadow` or `live` adds a funding rule of its own.** Once the bot is in `shadow` or
`live` — whatever its markets, `ANTICIPATE` or not — wallet rebalancing keeps the wallet in a fixed
shape (§9, "Wallet rebalancing"), and that shape costs ada: **10 ADA beside the token of each
`ANTICIPATE` or `CONVERT_BEFORE_ANTICIPATE` market the wallet holds, 10 ADA beside the bundle of any
other tokens, and a 5 ADA collateral UTxO**
(5 ADA under current protocol parameters), **plus the transaction fee, plus a change output that still holds its own minimum ada
once it has paid that fee** (about 1 ADA). Under current parameters a wallet holding the token of one
`ANTICIPATE` or `CONVERT_BEFORE_ANTICIPATE` market therefore needs **about 16.2 ADA**, and an ada-only
wallet about 6.2 ADA; the exact
figures move with the protocol parameters and with how many UTxOs the wallet holds (each one adds a
little to the fee). A wallet carrying a very large bundle of other tokens can need more (§9). A wallet with less than that is not reshaped, and the node logs a
`[REBALANCE] REFUSED: …` warning on every pass in which the rebalancer runs until you add ada
(an empty wallet logs `[REBALANCE] NOTHING_TO_DO: no input UTxOs…` instead).

**A preview node runs in `shadow` by default**, so it rebalances too: if its wallet is out of shape,
expect one self-send of the whole wallet shortly after it has synced. **Any deposit triggers a
reshape:** funds you send arrive as a second ada-only UTxO, which puts the wallet out of shape, and
the next pass reshapes it with a self-send. **That self-send spends EVERY wallet UTxO** — the
collateral UTxO C and every token UTxO included, not only the ada output — **so for about one block,
until it lands, nothing can be liquidated or compounded.** A liquidation or compound built in that
window is rejected by the ledger (its inputs are already being spent) and retried on the next pass. A
reshape only runs on a pass in which no liquidation or compound went out, so this window opens after a
pass that had nothing to do.

**What liquidation needs from the wallet, by role.** Each check reads the wallet once and gives its
UTxOs roles, and each kind of liquidation is funded from one role only (§9, "Which wallet output pays
for what"):

- **C** — the ada-only UTxO of exactly the collateral size: **5 ADA** under current protocol
  parameters. It is the collateral the rebalancer keeps, and it is never handed to a liquidation as
  its funding UTxO.
- **U** — the largest other ada-only UTxO (the change, in a shaped wallet). A **plain** liquidation
  needs U to hold the fee ceiling (the most this ledger could charge in fees, about 2.6 ADA). A
  **convert** needs the Minswap order overhead (4 ADA) plus the fee ceiling — **about 6.6 ADA**, an
  approximate figure derived from those two constants. An **ADA-principal anticipate** needs the
  lender payout plus the fee ceiling plus **about 3.5 ADA of riders** (the bot's own collateral
  output and the change's minimum ada).
- **A token anticipate** is funded from **its own token UTxO**, never from U: the rebalancer gives
  the token of each `ANTICIPATE` or `CONVERT_BEFORE_ANTICIPATE` market one, with **10 ADA** beside the
  token.
- **+10 ADA** when the wallet holds any other tokens (the UTxO that bundles them).

**A missing or too-small role is refused, by name.** A liquidation whose role is missing is recorded as
`WALLET_ROLE_MISSING`, one whose role UTxO is too small as `WALLET_INPUT_TOO_SMALL` naming the UTxO,
what it holds and what it needed; a check with no C builds nothing. The WARN names the role and **the
wallet rebalancer's last outcome**. So anything that keeps the wallet from being shaped — a
reference-script or datum UTxO (below) — stops liquidation too, and the WARN says why.

**⛔ Keep no reference-script or datum UTxO in this wallet.** A rebalance spends every UTxO the wallet
holds, and its outputs carry no reference script and no datum — so a datum UTxO parked here is
consumed and its datum is not recreated. A reference-script UTxO is expected to make **every**
rebalance fail at submission instead (logged at ERROR on every pass in which the rebalancer runs), so the
wallet is never shaped.

**⛔ The wallet must hold no UTxO created before 2025-05-06.** The node reads its wallet **only from
its own index**, and that index starts at mainnet slot `154984561` (2025-05-06). A UTxO created
before then was never seen by the index, so the node cannot see it: it is not counted, not spent,
and not reported as missing — the wallet simply looks smaller than it is, or empty. A freshly
generated wallet meets this automatically. **If you reuse a wallet, or are not sure, send its whole
balance to the node's own address once — a single self-send — before starting the node.** Every
UTxO then dates from after the sync start and the index sees all of them.

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

### `docker/.env` — the six things that are yours, plus one optional

Copied from `.env.example`, which contains **only** what you must set, plus the optional
`WALLET_ACCOUNT_INDEX`:

| variable | what it is |
|---|---|
| `STORE_CARDANO_HOST`, `STORE_CARDANO_PORT` | your Cardano relay — the chain feed |
| `BLOCKFROST_KEY` | your Blockfrost project key — how transactions are submitted |
| `WALLET_MNEMONIC` | the dedicated wallet from §2 |
| `WALLET_ACCOUNT_INDEX` | *optional* — which account of that mnemonic (the "Account #N" a wallet app like Eternl shows). Blank = the first account. Each account has its **own stake key**: delegate the 30,000 FLDT to the one you pick. At startup the node logs `account index N, address …, stake address …` — check both against the wallet app |
| `DB_USERNAME`, `DB_PASSWORD` | credentials for the bundled Postgres. Pick a real password |
| `AQUARIUM_DOCKER_IMAGE_VERSION` | ⚠ **pin it**, so a restart months from now cannot silently change what you run. Tags are `date-commit`, e.g. `2026.09.22-413f16cb32fb` — permanent, unlike `latest` |

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

While the node's last applied block is more than `AQUARIUM_SYNCING_THRESHOLD_MINUTES` (default 10)
minutes old, `/healthcheck` answers the plain text `...syncing...` and nothing is processed.

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

### The summary at the top of the page

On a wide screen the prices sit in the right half of the summary below (the Book), unfiltered; on a narrow phone view,
and whenever that summary is absent, they sit in a **prices strip** directly under the node-wallet strip.
Either way they list every token the oracle registry prices, from the same oracle feeds the bot prices with: its price in ADA and in USD, and the
feed's age, which counts up live between reloads (a muted entry has no price usable right now and shows the
last one held).

Above the filters, a desktop-width screen shows a summary of the loans it lists:

- **Counts** — how many loans, how many are liquidatable now, and how many the bot could not process.
- **Collateral locked (TVL)** — the collateral sitting in live loans, per token. It is *not* the debt.
  Each loan's collateral is valued in ADA at that loan's own collateral oracle.
- **A loan whose collateral has no usable oracle price** stays in its token's amount, is left out of the
  ADA and USD totals, and is counted on an "excluded" line — so a gap is said, never hidden as a zero.
- **The USD figure is an ESTIMATE, not a quote.** It is the ADA total converted at the reference
  stablecoin's oracle price (USDCx by default), counting 1 of it as $1. A stablecoin can trade off its peg
  and the oracle can lag; the page labels the figure "estimated" and names the method, and the token, on
  the line beside it.
- **The summary follows the filters, not the page.** Narrow the list with the filters and it re-counts
  what is left, across every page; paging through the list does not change it.

`LOANS_UI_USD_REFERENCE_UNIT` is the page's **one ADA/USD rate** — the summary's USD and every USD price
in the prices strip go through it. It defaults to mainnet's **USDCx**
(`1f3aec8bfe7ea4fe14c5f121e2a92e301afe414147860d557cac7e345553444378`), the largest stablecoin on
Cardano. Any USD-pegged stablecoin's unit may be set instead: the page names the figure after that
token's own label, so it is never mislabelled. Leave it unset to keep the default; set it empty and USD
is turned off — the summary and the strip show "USD unavailable", with a reason naming
`LOANS_UI_USD_REFERENCE_UNIT`. On preview it is blank, so USD is unavailable there too. It is listed,
commented out, in `docker/.env.advanced.example`.

### Wallet rebalancing on the page

Under the status banner, a **`Wallet rebalancing:`** line shows what the rebalancer (§9, "Wallet
rebalancing") will do on the next pass, read from the same check the rebalancer itself runs — it
never says `ARMED` while the rebalancer is idle, and it always says why it is idle:

| state | what it means |
|---|---|
| `ARMED` | on each pass — after a committed block or an oracle price change — in which no liquidation or compound went out, if the wallet has drifted out of shape, the node signs and submits one self-send of its whole wallet, in shadow as well as live |
| `IDLE` | rebalancing is allowed but nothing arms it: the liquidation mode is not shadow or live, or the node is syncing. The line names which |
| `SUSPENDED` | suspended from this page; nothing is rebalanced until it is resumed here or the node restarts |
| `OFF` | `AQUARIUM_WALLET_REBALANCE_ENABLED` is `false`; this page cannot turn it on |

Two chips beside it show `env default: on`/`off` (the environment variable) and `suspended from this
page: yes`/`no`. When rebalancing is `ARMED` the banner also carries a note saying so.

**The page's only controls that change the node** are the **`Suspend rebalancing`** and **`Resume
rebalancing`** buttons. Suspend before you move funds in or out of the wallet by hand, so the node
does not reshape it under you, and resume when you are done. Neither does anything else:

- **The suspension lives in memory.** A restart forgets it and rebalancing resumes.
- **Resume cannot override the environment.** With `AQUARIUM_WALLET_REBALANCE_ENABLED=false` there is
  no Resume button, and a posted resume leaves rebalancing off.
- **There is no authentication and no CSRF protection on these buttons.** Anyone who can reach the
  page — or get a browser that can reach it to post a form — can suspend or resume rebalancing. A
  resume can lead to a self-send of the node's own wallet back to itself (a transaction fee), never
  to a payment to anyone else. One more reason the port stays unpublished.

**⚠ Behind a reverse proxy that strips a path prefix, the page you land on after pressing a button
can be wrong.** Each button answers `303 See Other` with a `Location` that is an absolute path built
from the request path as the node received it, and the node does not apply forwarded headers such
as `X-Forwarded-Prefix` — so a prefix the proxy removed is missing from the redirect. The action itself has already happened;
navigate back to the page and reload it.

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
| `GET /api/v1/addresses/{address}/utxos`, `GET /api/v1/addresses/{address}/utxos/{asset}` | **yes** | the indexed UTxOs at any address, payment credential or stake address the caller names, optionally for one asset — including the node's own wallet |
| `GET /api/v1/addresses/{address}/transactions` | **yes** | the transactions that created or spent the indexed outputs at that address |
| `GET /api/v1/assets/utxos/unit/{unit}` | **yes** | the indexed UTxOs holding an asset |
| `GET /api/v1/assets/{unit}/utxos` — new in Yaci Store 2.0.7 | **yes** | the same, under its new path |
| `GET /api/v1/assets/{unit}/transactions` — new in Yaci Store 2.0.7 | **yes** | the transactions behind the indexed outputs holding that asset |
| `GET /api/v1/utxos/{txHash}/{index}`, `POST /api/v1/utxos` | **yes** | one indexed UTxO, or a list of them, by transaction hash and output index |
| `GET /v3/api-docs`, `/v3/api-docs.yaml`, `/v3/api-docs/swagger-config` | **yes** | a machine-readable description of the HTTP API, these routes included |
| `GET /swagger-ui.html` and its assets under `/swagger-ui/` | **yes** | an interactive page for calling those routes |
| `GET /api/v1/loans/readiness` | no — the flag removes this page and the two routes below | the HTML operator page |
| `POST /api/v1/loans/readiness/wallet-rebalance/suspend` | no | nothing — it **suspends wallet rebalancing** (in memory, until resumed or restarted) |
| `POST /api/v1/loans/readiness/wallet-rebalance/resume` | no | nothing — it **resumes wallet rebalancing**, unless `AQUARIUM_WALLET_REBALANCE_ENABLED=false` |

These routes come from Yaci Store, the library the node indexes with: the `/api/v1/addresses`,
`/api/v1/assets` and `/api/v1/utxos` ones from its UTxO API, the `/v3/api-docs` and `/swagger-ui` ones
from the API description served with it. The UTxO routes read `address_utxo`, which holds only what
the node's write-time filter kept: its wallet, the watched contracts, oracle-feed outputs and the
Minswap pools. FAB-203 switched off Yaci's plugin-admin, sync-status and genesis routes; whether to
close the rest is tracked as FAB-146.

**⇒ Turning the UI off narrows the disclosure; it does not end it.** The exposure decision above is
about the *port*, not about the flag. Tunnel it or keep it on a private network either way.

---

## 8. Arming the bot

**Everything ships off.** Turning it on is a sequence, and the middle step is the one worth not
skipping.

**Step 1 — run it disabled.** Let it sync fully. Confirm the health endpoint is clean and that the
loan list looks like the chain.

**Step 2 — `shadow`.** Set `AQUARIUM_LIQUIDATION_MODE=shadow` and restart. The bot now scans,
prices, builds and size-checks real liquidations, and **refuses to submit every one of them.** This
is a rehearsal against real loans at no risk to the loans. **⚠ It is not a node that submits
nothing:** whatever its markets, wallet rebalancing signs and submits self-sends of the node's own
wallet in shadow too, each costing a transaction fee — see §9, "Wallet rebalancing". Read the result:

```bash
curl -s http://localhost:8080/api/v1/loans/liquidations | jq .
```

Every decision and its reason is there. **It is held in memory — read it before you restart.**

**Step 3 — `live`.** Only once shadow shows the decisions you expect.

**⚠ Three ways to be armed and idle, all silent, none an error:**

1. `LOANS_LIQUIDATION_CONVERT_ENABLED=false` — the node converts nothing, whatever markets say (a
   `CONVERT_BEFORE_ANTICIPATE` market pays in advance instead, under its `cap`)
2. every market `DISABLED`, or `ANTICIPATE` with a `cap` below what each loan needs — and the same for
   the loans a `CONVERT_BEFORE_ANTICIPATE` market pays in advance. The `cap` is **per loan, in the
   token's smallest unit** (§9, "Field reference"): a `cap` of `50` on a 6-decimal token is 0.00005 of
   it, and refuses every loan
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

When a loan is liquidated the bot builds one of three transactions: a **plain** liquidation, a
**convert**, or a **pay-in-advance**. A loan whose lender does not allow conversion always gets the
plain one (below). For a loan whose lender allows conversion, the market's `action` decides how — and
for `CONVERT_BEFORE_ANTICIPATE`, whether a convert is possible for that loan (its pool verdict, or
convert switched off) decides between the convert and the pay-in-advance:

| action | what it does | what it costs you |
|---|---|---|
| `CONVERT` | sells the collateral through a Minswap V2 order to repay the lender | **no capital** — a transaction fee and the ~4 ADA the validator makes the order carry |
| `ANTICIPATE` | repays the lender **from your own wallet** and keeps the collateral | **your capital**, up to a `cap` you must state |
| `CONVERT_BEFORE_ANTICIPATE` | converts, as `CONVERT` does; only when a convert is **impossible** for the loan, repays the lender from your own wallet instead, as `ANTICIPATE` does, in the same check (below) | **no capital** when it converts; **your capital**, up to a `cap` you must state, when it pays in advance |

**`action` applies only to loans whose lender allows conversion.** Each loan's lender bond says
whether its collateral may be converted to the principal. A loan whose lender does not allow it gets a
**plain** liquidation whatever the market's `action` and `cap` say: it takes its fee in collateral and
fronts nothing. The market's `mode` still applies to it.

**⛔ `unit` is the loan's PRINCIPAL asset — what was lent — not the collateral.**

This is the mistake to avoid and it is completely silent. An entry keyed by the collateral is
well-formed, boots cleanly, logs at startup — and **matches no loan.** That market is then treated
as *unlisted*, **and an unlisted market CONVERTS by default.** A `mode: DISABLED` written against
the wrong asset therefore does the exact opposite of what it says, with no error anywhere.

**`CONVERT` is the default everywhere.** You do not list a market to get it: no markets listed means
every market converts, provided `LOANS_LIQUIDATION_CONVERT_ENABLED` is true (it ships `true`).
**Listing a market is how you deviate.**

Two controls, and a convert needs both to permit it:

1. `LOANS_LIQUIDATION_CONVERT_ENABLED` — global. `false` means this node touches no DEX, ever (a
   `CONVERT_BEFORE_ANTICIPATE` market then pays in advance, below)
2. `LOANS_LIQUIDATION_MARKETS_*` — per market: override the action, cap what you will front, or
   disable one market while the rest keep working

### `CONVERT_BEFORE_ANTICIPATE` — convert first, pay in advance only when convert is impossible

A `CONVERT_BEFORE_ANTICIPATE` market tries the convert first, exactly as a `CONVERT` market does. It
pays the lender in advance instead — **in the same check**, under the `ANTICIPATE` rules: the `cap`,
the token balance, the token's own UTxO (or U for an ADA principal), and profitability — **only when a
convert is impossible**, for one of three causes:

1. **`NO_MINSWAP_POOL`** — this node's index holds no authentic Minswap V2 pool for the loan's pair, or
   `loans.minswap.pool-address` is blank (preview ships it blank, so on preview every such loan pays in
   advance);
2. **`POOL_TOO_THIN`** — the pool cannot deliver enough to repay the debt;
3. **`CONVERT_DISABLED`** — `LOANS_LIQUIDATION_CONVERT_ENABLED=false`, decided before anything is built.

**Every other convert refusal is refused by name and is never paid in advance** — the refusals a
`CONVERT` market records: a convert that is not worth doing, a pool lookup that failed (`LOOKUP_FAILED`)
or found more than one pool for the pair (`AMBIGUOUS_POOL`), a pool with a dynamic fee
(`POOL_HAS_DYNAMIC_FEE`), nothing left to swap, a missing or too-small U, a failed build. A
`mode: DISABLED` market is refused before any build, with no fallback.

**One row per loan.** A loan that falls back ends in one pay-in-advance row on the liquidations
endpoint, whose detail starts `fallback to pay-in-advance (no convert: <cause>): `, and the node says it
at INFO:

```
<loan> falls back to pay-in-advance in this check — no convert: <cause> (<why>)
```

A pay-in-advance refused by the market gate (its mode, `cap` or balance), or because the wallet output
funding it is too small, is then logged in the pay-in-advance wording —
`the pay-in-advance liquidation of … was refused by the market gate: …` or
`the pay-in-advance liquidation of … was refused: …`.

**The boot says where such a market pays in advance.** On a node that cannot convert (a
`CONVERT UNAVAILABLE` line at boot), each `CONVERT_BEFORE_ANTICIPATE` market gets one WARN:
`market … is action: CONVERT_BEFORE_ANTICIPATE on a node that cannot convert: …`. With
`LOANS_LIQUIDATION_CONVERT_ENABLED=false`, each gets one INFO instead:
`INIT - market … is action: CONVERT_BEFORE_ANTICIPATE and loans.liquidation.convert.enabled is false: …`.
Both state the market's `cap` and its effective mode.

**The readiness page's "Action now" is an estimate for such a market.** It follows the pool verdict
from this node's index: a usable pool → `CONVERT`; no pool, a pool too thin, no Minswap configuration,
or convert switched off → the answer an `ANTICIPATE` market gets (its `cap` against this loan, then its
mode); a pool with a dynamic fee → nothing; a pool check that failed, or a loan whose figures could not
be priced → unknown. The page tests every indexed pool when it renders, while the bot resolves one pool
when it builds, so the two can disagree for a block, and the row says so. **One disagreement lasts
longer than a block:** a loan whose collateral leaves nothing to swap after the borrower's equity and
the liquidation fee shows on the page as a pool too thin, and so gets the pay-in-advance answer, while
the bot refuses that loan by name (`NOTHING_LEFT_TO_SWAP`) and never pays it in advance. The two
disagree for as long as that holds.

**⚠ A pool this node's index has never seen counts as no pool.** The node knows a Minswap pool only from
its own index, and the index holds a pool only once the pool's **current** output has passed through a
block the node indexed. A pool that has sat idle since the block the node started syncing from is
therefore not in the index, and a loan in this market whose pair it serves is **paid in advance**
(`NO_MINSWAP_POOL`), although that pool could convert it on chain. It becomes visible the next time it
moves (any swap recreates its output). **A restart or a configuration change does not make it
visible**: the node resumes from its stored cursor, whatever `sync-start-*` says
([upgrading.md §3](upgrading.md#3--the-rule-that-surprises-people)).

**A re-sync helps only some idle pools.** A re-sync with the steps of
[upgrading.md §1.1](upgrading.md#11--the-minswap-pool-release-requires-a-database-wipe-fab-137) — wipe
the database **including the cursor**, keep the shipped `sync-start-*` — replays the chain from the
shipped start point (mainnet: slot `154984561`, 2025-05-06). It makes visible a pool that last moved
**after** that point but before your own node's index began — for example on a node whose cursor
started later than the shipped value. It does **not** help a pool idle since **before** 2025-05-06: the
re-sync replays the same blocks, none of which carries that pool's current output, so it stays
invisible until it moves, re-sync or not.

### Worked example — four markets

Convert ADA-principal loans; anticipate a token-principal market you do not trust a pool for, capped
at 500 **of that token** per loan; convert a second token market where a pool can repay the debt and
pay in advance where none can, capped at 1,000 of that 6-decimal token per loan; stay out of a fourth
entirely.

```bash
LOANS_LIQUIDATION_MARKETS_0_UNIT=lovelace
LOANS_LIQUIDATION_MARKETS_0_ACTION=CONVERT

LOANS_LIQUIDATION_MARKETS_1_UNIT=577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e0014df10464c4454
LOANS_LIQUIDATION_MARKETS_1_ACTION=ANTICIPATE
LOANS_LIQUIDATION_MARKETS_1_CAP=500000000

LOANS_LIQUIDATION_MARKETS_2_UNIT=2222222222222222222222222222222222222222222222222222222254455354
LOANS_LIQUIDATION_MARKETS_2_ACTION=CONVERT_BEFORE_ANTICIPATE
LOANS_LIQUIDATION_MARKETS_2_CAP=1000000000

LOANS_LIQUIDATION_MARKETS_3_UNIT=1111111111111111111111111111111111111111111111111111111144554d4d59
LOANS_LIQUIDATION_MARKETS_3_MODE=DISABLED
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
      - unit: 2222222222222222222222222222222222222222222222222222222254455354
        action: CONVERT_BEFORE_ANTICIPATE
        cap: 1000000000
      - unit: 1111111111111111111111111111111111111111111111111111111144554d4d59
        mode: DISABLED
```

### Field reference

- **`unit`** — the loan's **principal** asset. `lovelace`, or the principal's policy id (56 hex
  chars) followed by its asset name in hex, no separator. **Required**; anything malformed aborts
  startup by name.
- **`action`** — `CONVERT`, `ANTICIPATE` or `CONVERT_BEFORE_ANTICIPATE`. Defaults to `CONVERT`; never
  inferred from `cap`. It applies only to loans whose lender allows conversion (above).
- **`cap`** — the most principal the bot may front for **one loan**, in **the token's smallest unit**
  (lovelace for `lovelace`). It is compared with what that one loan requires fronted, not with a total
  across loans. For a 6-decimal token, `500000000` is 500 of it; a `cap` of `50` is 0.00005 of it and
  refuses every loan. **Mandatory for `ANTICIPATE` and `CONVERT_BEFORE_ANTICIPATE`, and the node refuses
  to start without it** — anticipating uncapped is unbounded exposure. Ignored with a warning on a
  `CONVERT` market.
- **`mode`** — `DISABLED`, `SHADOW` or `LIVE` for that market alone. Omit to inherit the node mode.

**⚠ The node mode is a ceiling.** A market asking for `LIVE` on a node running `shadow` runs as
`SHADOW`, and says so at boot. A market may be more restrictive than the node, never less.

**⛔ The node refuses to start** on an entry that cannot mean anything: missing or malformed `unit`,
duplicate `unit`, `ANTICIPATE` or `CONVERT_BEFORE_ANTICIPATE` with no `cap`, or a negative `cap` on an
`ANTICIPATE` or `CONVERT_BEFORE_ANTICIPATE` entry. That is deliberate — a typo that quietly disabled one
market would look exactly like a market with no liquidations in it.

### Wallet rebalancing — the shape a shadow or live node keeps your wallet in

**Wallet rebalancing is on by default** (`AQUARIUM_WALLET_REBALANCE_ENABLED=true`), and on a node as
shipped it does nothing: it is armed only by the settings below. Once armed, **it signs and submits
transactions from your wallet, in `shadow` as well as `live`** — they are self-sends, wallet
housekeeping, not liquidations.

**When it acts.** All of these must hold, checked in this order:

1. `AQUARIUM_WALLET_REBALANCE_ENABLED` is `true` (the default);
2. it is not suspended from the readiness page (§7);
3. `AQUARIUM_LIQUIDATION_MODE` is `shadow` or `live`;
4. the node is not syncing (§6).

**Your markets do not arm it.** A node with only `CONVERT` markets, or none listed, keeps its wallet
shaped too, because liquidation depends on a shaped wallet. The markets decide only which tokens get a
UTxO of their own (below).
That is also why, on a `shadow` or `live` node, turning rebalancing off
(`AQUARIUM_WALLET_REBALANCE_ENABLED=false`) or suspending it from the readiness page is logged as a
warning on every pass in which the rebalancer runs —
`[REBALANCE] ENV_DISABLED: … liquidation depends on a shaped wallet …` (or `SUSPENDED`) — rather than
in silence.

**When it runs.** Rebalancing is the last stage of the node's wallet pipeline. A pass is triggered by a
block the node has committed or by a change in the oracle prices, and runs **liquidation first, then
compound, then rebalancing**, one pass at a time. A liquidation Blockfrost accepted, or a compound that
went out (or may have), ends the pass, so rebalancing runs only on a pass in which neither happened;
otherwise it waits for the next trigger. On such a pass it reads the wallet from its index and, **if the wallet is out of shape,
builds, signs and submits one self-send** that puts it back in shape. A node in `disabled` mode never
rebalances.

**The shape.**

- **One UTxO per principal token of an `ANTICIPATE` or `CONVERT_BEFORE_ANTICIPATE` market whose
  effective mode is shadow or live** —
  its own `mode`, capped by the node's (§9 above) — holding all of that token and exactly 10 ADA (none
  at all when no market qualifies);
- **one UTxO holding every other token**, with exactly 10 ADA — only if the wallet holds any;
- **one ada-only collateral UTxO of exactly C**, where C is the larger of the ledger's maximum
  collateral for a transaction and 5 ADA — 5 ADA under current protocol parameters;
- **every remaining lovelace in one ada-only UTxO.**

Why: the bot fronts the principal of an `ANTICIPATE` market — and of a `CONVERT_BEFORE_ANTICIPATE`
market whose convert is impossible — from **one** UTxO, which must carry the token *and*
the ada the transaction needs beside it (the fee ceiling plus about 5.5 ADA of riders — under 10 ADA
at current parameters), so each such token gets its own
funded UTxO instead of being scattered or bundled with others. C is never below the 5 ADA the
transaction builder selects as collateral, nor below the most collateral the ledger can require, so
a collateral UTxO of the right size is kept ready for the bot's script transactions. And one change
UTxO keeps your ada in one piece rather than fragmented. An ADA-principal market is fronted from that change,
so it adds no UTxO of its own. **A token of an `ANTICIPATE` or `CONVERT_BEFORE_ANTICIPATE` market whose
mode is `DISABLED` is not kept ready**: it goes in the "every other token" UTxO.

**The funds it needs.** The wallet's ada must cover:

1. the planned outputs — 10 ADA for the token of each `ANTICIPATE` or `CONVERT_BEFORE_ANTICIPATE`
   market it holds, 10 ADA if it holds any other token, and C;
2. **plus** the transaction fee, which is always paid out of the change output — never out of a
   planned output, which keeps exactly its planned value;
3. **plus** the change output's own minimum ada, which it must still hold after paying that fee
   (about 1 ADA under current parameters).

Under current parameters, a wallet holding **the token of one `ANTICIPATE` or
`CONVERT_BEFORE_ANTICIPATE` market needs about 16.2 ADA** (10 + 5
planned, roughly 0.2 of fee and about 1 of change), and an ada-only wallet about 6.2 ADA. These are
approximate: they move with the protocol parameters, and each extra UTxO in the wallet adds a little
to the fee. ⚠ One exception: if the bundle of *other* tokens (those of no such market) is so large that
its own minimum ada is close to 10 ADA (around 30 spam tokens with long names), cardano-client-lib
refuses the build itself (`build failed: Not enough funds …`) until the change is larger than 10 ADA,
so such a wallet still needs the old figure: the planned outputs plus a change above 10 ADA. When the wallet is short only of the change's minimum, the refusal names the exact
shortfall (`… the wallet is short by N lovelace (N.NNNNNN ADA) for this shape; add at least that
much`). Otherwise the self-send is not built, and the node logs a warning **on every pass in which the
rebalancer runs**:

```
[REBALANCE] REFUSED: … — the wallet is out of shape …; nothing signed or submitted
```

and **nothing is signed or submitted, so a refusal costs no fee.** The same warning appears when the
transaction cannot be built to the exact shape — for example a bundle of other tokens so large that
its minimum ada exceeds 10 ADA — or when it would exceed the ledger's size limits. Add ada, or remove
the tokens that cannot be shaped, and the warnings stop. A wallet that is **empty** logs
`[REBALANCE] NOTHING_TO_DO: no input UTxOs…` at WARN on every pass in which the rebalancer runs instead,
also submitting nothing.

**⛔ It spends the whole wallet.** Every UTxO under the wallet's payment key is an input — whatever
its stake part — with no exception for reference scripts or datums, and every output goes to the
wallet's base address carrying neither. **A datum UTxO in the bot wallet is consumed, and its datum
is not recreated.** **A reference-script UTxO is expected to make every rebalance fail:** it is
taken as an input, but the self-send is priced without the script's size, so the ledger is expected
to reject it as underpriced — on every pass in which the rebalancer runs, at ERROR — and the wallet never
gets shaped. Keep both kinds elsewhere (§2).

**A rejected duplicate is expected; a rejection on every pass in which the rebalancer runs is not.**
There is no pending-transaction guard: if a later pass still reads the old wallet — the self-send has not
yet landed in a block the node has indexed — the same self-send is built and submitted again, and that
duplicate is rejected and logged at ERROR as `[REBALANCE] submission rejected`. **One such ERROR (or a
few, one per pass that runs before the self-send lands) right after a successful rebalance is expected
and harmless** — the same transaction can only be applied once. **The same ERROR on every pass in which
the rebalancer runs, long after that, means something in the wallet is making the self-send fail** — a
reference-script UTxO, for example — and needs your attention: suspend rebalancing, fix the wallet,
resume.

**Turning it off, or pausing it.**

- **Off:** `AQUARIUM_WALLET_REBALANCE_ENABLED=false` and restart. Nothing on the readiness page can
  turn it back on.
- **Paused:** **`Suspend rebalancing`** on the readiness page (§7) — do this before you move funds
  in or out of the wallet by hand, then **`Resume rebalancing`**. The suspension is held in memory, so
  a restart resumes rebalancing.

### Which wallet output pays for what

Each liquidation check reads the wallet **once** and gives its UTxOs roles, which do not change for
the rest of that check:

| liquidation | funded from | it must hold, before anything is built |
|---|---|---|
| plain | **U**, the largest ada-only UTxO that is not C | the fee ceiling |
| convert | **U** | the Minswap order plus the fee ceiling (about 6.6 ADA when the collateral is a token) |
| anticipate, ADA principal | **U** | the lender payout, the fee ceiling and about 3.5 ADA of riders |
| anticipate, token principal | **the UTxO holding the most of that token** (no datum, no reference script) | the payout in the token, and the fee ceiling plus about 5.5 ADA of riders beside it |

A `CONVERT_BEFORE_ANTICIPATE` market is funded as a convert — from **U** — when it converts, and as an
anticipate when it pays in advance: from the UTxO holding its token for a token principal, from **U** for
an ADA principal. A missing U does not stop a token-principal fallback, which needs none.

**C — the ada-only UTxO of exactly the collateral size — is never handed to a liquidation as its
funding UTxO.** The figures are ceilings checked before the build; the build is the judge of whether
they suffice. Every liquidation builder pins **C** as its only collateral, never spends it, and sees only
its designated UTxO as a wallet input — it never draws on any other wallet UTxO while balancing.

A **compound** is funded from **U** alone with **C** as its collateral, and its change comes back as one
ada-only UTxO — except that a token pool's compounding fee arrives in the pool's token inside that UTxO,
which may trigger a wallet reshape.

**Fire and forget: U stays in the check; an accepted liquidation takes out only a token UTxO.** Every
candidate is tried, most profitable first, in every market. After a liquidation funded from **U** — a
plain liquidation, a convert, or an ADA-principal pay-in-advance — Blockfrost **accepted**, every later
candidate funded from U is **still built and submitted on U**, in profit order. Usually the mempool
holding the first refuses it, which costs one evaluate and one submit call and one INFO line
(`liquidation of … via … on … not accepted: HTTP …`). But a backend that has not yet seen the first
transaction can accept it too: the liquidations endpoint then shows **two `SUBMITTED` rows for one U**,
and at most one of the two can land — the second only if the first loses its race. The `SUBMITTED` row
of a liquidation funded from U says so (`…; U stays in this check: later candidates funded from U are
still built and submitted, …`). Nothing bounds how many candidates are tried on U in one check.
**A token UTxO is still taken out:** after an accepted token-principal pay-in-advance, the UTxO it spent
is marked used for the rest of the check, and the next candidate designated to it is **skipped** —
logged at INFO as `… skipped: wallet output … (role TOKEN <unit>) already went out in this check` —
rather than funded from another UTxO. A `shadow` node's would-be submission marks exactly what an accept
would; a liquidation held back by a market in `shadow` on a `live` node marks nothing. **A rejected or
unknown submit frees nothing and stops nothing:** it is logged once at INFO, and the next candidate is
tried, on the same UTxO. Until an accepted liquidation is in a block (about 20 s), later passes rebuild on
U and get harmless rejections; the Blockfrost calls they cost appear in each pass's summary line
(`wallet pipeline pass #<n> at slot <slot> (<hash>), trigger <label>, <k> block(s) folded in, waited <w> s, took <d> s: liquidation …; Blockfrost calls discovery …, evaluate …, submit …`).
Each stage on that line prints its outcome — `NO_OP`, `ATTEMPTED`, `SUBMITTED`, `WOULD_SUBMIT`, `SKIPPED`
or `ERROR`, with its counts — as described in
[upgrading.md §7, "Stage outcomes on the pass line"](upgrading.md#stage-outcomes-on-the-pass-line-fab-195).
Every pass says it at INFO
by default; `WALLET_PIPELINE_LOG_EVERY_PASS=false` returns to INFO only when something happened. See
[upgrading.md §7, "Fire and forget"](upgrading.md#fire-and-forget-fab-186).

---

## 10. Security checklist

- [ ] `WALLET_MNEMONIC` is a **dedicated** wallet, used for nothing else
- [ ] `docker/.env` is `chmod 600` and not in any repository
- [ ] The 30,000 FLDT is delegated **from a separate wallet**, ideally cold
- [ ] No `ports:` mapping on `aquarium-node` — UI reached by tunnel or private network (§7)
- [ ] You have not published 8080 on the strength of `LOANS_UI_ENABLED=false` — that flag
      removes the HTML page and its two buttons only, and `/api/v1/loans` still serves your
      positions (§7)
- [ ] With `LOANS_UI_ENABLED=true`, only people you trust can reach the page — anyone who can
      reach it can suspend or resume wallet rebalancing (§7)
- [ ] Postgres is on `127.0.0.1` (the shipped file does this; check if you edited it)
- [ ] A real `DB_PASSWORD`
- [ ] The image version is **pinned**, not `latest`
- [ ] Host firewall allows only what you intend — the node needs no inbound at all, and makes
      **outbound** connections to:
      - your relay, and Blockfrost;
      - `api.github.com` — the daily version check, which you can turn off (§11);
      - FluidTokens' oracle registry, `api.fluidtokens.com` (mainnet) or `testapi.fluidtokens.com`
        (preview), polled every 30 s — a plain request for the price list: no key, no account, nothing
        of yours beyond the host's IP address;
      - FluidTokens' oracle stream, a WebSocket on AWS API Gateway in eu-west-1
        (`25mxd4whnj.execute-api.eu-west-1.amazonaws.com` on mainnet,
        `2tgda3mk4l.execute-api.eu-west-1.amazonaws.com` on preview), on by default (§11) — it sends the
        wallet's stake address, its stake public key, the time and a signature over the address and the
        time, never the mnemonic; `LOANS_ORACLE_STREAM_ENABLED=false` turns it off
- [ ] Log rotation is on (the shipped file sets it) — logs are verbose and the disk is shared with
      your database

**What the node never does:** nothing it serves can pay funds to anyone. It accepts exactly two
inbound commands, and only with `LOANS_UI_ENABLED=true`: the readiness page's routes that suspend or
resume wallet rebalancing (§7). The worst a resume can lead to is a self-send of the node's own
wallet back to itself — a transaction fee — never a payment to anyone else. The exposure risk from
the UI is therefore mostly **disclosure** — your positions and posture — plus that one switch.

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

### The version check

At startup, and then every 24 hours, the node asks GitHub whether the commit it was built from is
behind `main`:

```
GET https://api.github.com/repos/FluidTokens/ft-aquarium-node/compare/<the build's commit>...main?per_page=1
```

No account and no key are involved. **What GitHub learns** is your host's IP address and the
commit your image was built from — nothing else is sent. It is informational only: the node never
changes, downloads, pulls or restarts anything because of the answer.

Each check writes exactly one line to the log:

| You see | Meaning |
|---|---|
| `INFO version check: up to date — …` | the build is the head of `main` |
| `WARN version check: this node is N commits behind main … Changes since this build: <link>` | `main` has moved on; the link lists exactly the commits your build lacks. It does not mean an image for `main`'s head exists yet — CI builds it after the commit lands |
| `INFO version check: this build (…) is N commits ahead of main — not a build of main` | a build from a branch, not from `main` |
| `INFO version check unavailable: …` | no answer could be decided: a fork or a local build GitHub does not know, a build that does not record its commit, GitHub's limit of 60 unauthenticated requests per hour per IP, a timeout (10 s), or GitHub unreachable |

A build from a dirty tree is still checked against its recorded commit, and the line adds that the
build carries uncommitted changes on top of it.

If you run the readiness page (§7), its header shows the same answer next to the build commit, in the
same small grey line as the network and the time — for example `build 64d43150c047 · up to date`. When
the build is behind `main` it reads `⚠ N commits behind main` in amber, as a link to the same compare
view the log line names. When no answer could be decided it reads `version unknown`; hover it for the
reason. The page only shows the result of the last daily check and never contacts GitHub itself, so a
new answer appears on the page after the next check, not when you reload it.

To turn it off, set `AQUARIUM_VERSION_CHECK_ENABLED=false` in `docker/.env`. The node then logs one
line at startup saying the check is off and never contacts GitHub.

### The oracle stream

On by default, the node also holds one WebSocket open to FluidTokens' oracle stream, which pushes prices
after they change — FluidTokens checks for changes about once a minute, so a push can follow a change by up
to about 60 s. It signs in with the wallet's own stake key — it sends the stake address, the stake public
key, the time and a signature, and no new secret is involved — and FluidTokens allows one connection per
staking bond. By default prices now come from the stream and the HTTP registry merged: for each oracle the
newer price wins, whichever channel brought it, and the node keeps polling the registry as before. To return
to prices from the HTTP registry alone, set `LOANS_ORACLE_SOURCE=api` in `docker/.env`; the stream then
stays a shadow that is only logged. To turn the stream off entirely, set `LOANS_ORACLE_STREAM_ENABLED=false`.
Every log line, metric and refusal is explained in [oracle-stream.md](oracle-stream.md).

---

## 12. When something looks wrong

**No loans, ever.** First check §6 — an unsynced node is the usual answer. If it is at the tip,
`GET /api/v1/loans/liquidations` reports what the last scan saw, including an `unreadable` count.
**A persistently empty world on a synced node should be treated as a suspected contract redeploy**,
not as a quiet market — see [upgrading.md](upgrading.md).

**Armed but never acts.** The three silent-idle causes in §8. The liquidations endpoint names the
veto for every candidate. And check the **lending gate**: a config-datum mismatch (`LoansConfigVerifier`)
no longer stops the node, it refuses every Lending v4 transaction — see the next entry. A reference-script
coordinate mismatch does **not** close the gate (FAB-108): it is a `REFERENCE_SCRIPT_DROPPED` WARN and that
validator travels inline, so the symptom to look for is a `TX_TOO_LARGE` veto, not a refusal.

**A setting seems to do nothing.** Confirm it actually reached the process:

```bash
docker compose exec aquarium-node env | grep AQUARIUM_
```

**Lending refused, everything else running (`LENDING_CONFIG_MISMATCH`).** Startup verification is
strict on purpose: it will not build against contract coordinates that do not match what it derives.
Since FAB-115 a config-datum mismatch (config policy ids or `smart-tokens-spend-script-hash`) no longer
stops the node — scheduled payments keep running — but every Lending v4 transaction (liquidation in every
mode, convert, compound) is refused for the life of the process. A clean `/healthcheck` does not rule it
out; check the log:

```bash
docker logs <node> 2>&1 | grep -E "LENDING_CONFIG_MISMATCH|Lending v4 config verified|reference scripts verified|REFERENCE_SCRIPT_(DROPPED|UNVERIFIED|MISSING)"
```

The line names each field at fault (derived vs chain). Fix the coordinates and restart; see
[upgrading.md §4.7](upgrading.md). `loans.liquidation.reference-scripts.*` and
`loans.compound.reference-scripts` are checked at boot too, but never close the gate (FAB-108): a bad one is
`REFERENCE_SCRIPT_DROPPED` (not used; its validator travels inline), one the provider could not be asked
about is `REFERENCE_SCRIPT_UNVERIFIED` (used as configured), and a compound validator nothing supplies is
`REFERENCE_SCRIPT_MISSING` (inline; the compound list is checked only with `loans.compound.enabled=true`).
The readiness page shows them in a **REFERENCE SCRIPTS** banner. Only
`loans.verify-config.fail-on-unreachable=true` stops the boot, and only over an UNVERIFIED named
`loans.liquidation.reference-scripts.*` key; an UNVERIFIED compound-list entry never does.
