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
| **FluidTokens redeployed the contracts** | §4 — new coordinates **and a full re-sync** | ⛔ **wiped** |
| You changed `sync-start-*` | §3 — on an existing database this **does nothing** by itself | ⛔ **wiped, or no effect** |

The first four are ordinary. The last two are the subject of this guide.

---

## 2. The ordinary upgrade

```bash
cd docker
# pin the new version in .env: AQUARIUM_DOCKER_IMAGE_VERSION=...
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

**⚠ Pin versions.** With `latest`, a restart months from now silently changes what you run, and
there is no record of what it used to be.

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

Startup verification is the real check, and it is strict: the node **refuses to start** if the
reference-script coordinates it is configured with do not publish the scripts it derives. Reaching a
clean health check means that comparison passed.

**Then re-arm in stages** — `shadow` first, read the decisions, then `live`. Same sequence as a first
deployment (deploying.md §8). A redeploy is a good moment to rehearse rather than assume.

---

## 5. Rolling back

**The image rolls back cleanly:** pin the previous version and restart.

**The database does not roll back with it.** If you re-synced from a later start point, the old
coordinates' history is not there any more, and going back to the old image means going back to an
empty world. That is what the §4.3 dump is for.

**⇒ The practical rule: an image rollback is cheap; a re-sync is not reversible without a backup.**

---

## 6. Routine maintenance

**Disk** grows with the chain. Watch the `./postgres` directory; the node does not prune, and it
shares a disk with your logs.

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

If you are carrying a configuration forward from an older node, **remove these.** They are ignored
in silence, which reads exactly like them working.

| removed key | why, and what happens if you still pass it |
|---|---|
| `LOANS_LIQUIDATION_CONVERT_PROFIT_MARGIN_LOVELACE` | merged into the single shared margin. A config still setting it gets the **shared default**, not the number it believes it set |
| `AQUARIUM_LIQUIDATION_ENABLED` | redundant with `AQUARIUM_LIQUIDATION_MODE` |
| `LOANS_ENABLED` | lending-v4 indexing is unconditional now |
| `AQUARIUM_X_SUBMIT` / `loans.submittable-network` | a barrier that silently blocked submission while everything else was armed is a bug, not a safeguard |

**The general shape is worth keeping in mind beyond this list:** an unknown environment variable is
not an error anywhere in this stack. It is read by nothing and reported by nothing. When a setting
seems to have no effect, confirm it reached the process and that the name still exists:

```bash
docker compose exec aquarium-node env | grep AQUARIUM_
```

---

## 8. ⛔ The image runs as a non-root user — if you mount secrets as FILES, read this first

Since 2026-09-18 the image runs as **uid 10001** (`aquarium`) instead of root.

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
