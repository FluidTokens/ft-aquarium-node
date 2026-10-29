# The oracle stream (shadow)

For operators who see `[oracle-stream]` lines in their log and want to know what they are, or who want
to turn the stream off. Nothing here needs action on a healthy node.

---

## 1. What it is

FluidTokens publishes oracle prices on two channels: the HTTP registry the node has always polled
(`api.fluidtokens.com/get-oracle-tokens`), and a WebSocket that **pushes** the same registry the moment it
changes. The node connects to the WebSocket and listens — **as a shadow**:

- **No decision reads it.** Every push is parsed and written to the log as one line, and that is all. No
  liquidation, price, health figure or readiness verdict changes because of it.
- **The HTTP registry stays the source of truth.** The node keeps polling it exactly as before; the stream
  neither replaces nor corrects it.
- It exists so that every operator's log shows how the stream behaves — how promptly pushes arrive, how
  often the connection drops, what the server refuses — before anything is allowed to depend on it.

A stream that is refused, disconnected or off costs the node nothing: liquidations, scheduled transactions
and `/healthcheck` carry on unchanged.

---

## 2. On, off, and when it stays off by itself

It is **on by default**. To turn it off, add this to `docker/.env` and restart:

```
LOANS_ORACLE_STREAM_ENABLED=false
```

The node then logs one line at startup — `[oracle-stream] off … reason=disabled` — and opens no
connection. Any value other than `true` or `false` is refused with a WARN (`reason=unrecognised-enabled`)
and the stream stays off.

It also stays off, with one INFO line saying why, when:

| Condition | The line |
|---|---|
| `LOANS_ORACLE_ENABLED=false` — the HTTP oracle is off, so there is nothing to compare the stream with | `off … reason=no-api-oracle` |
| the node runs on a network other than mainnet or preview | `off … reason=network-unsupported network=<name>` |

---

## 3. Where it connects

| Network | URL |
|---|---|
| mainnet | `wss://25mxd4whnj.execute-api.eu-west-1.amazonaws.com/prod` |
| preview | `wss://2tgda3mk4l.execute-api.eu-west-1.amazonaws.com/dev` |

`LOANS_ORACLE_STREAM_URL` overrides the URL. There is no reason to set it unless FluidTokens tells you to.
The node refuses, with a WARN and the stream off:

- a value that is not a `ws://` or `wss://` URL with a host and no query (`reason=invalid-url`);
- the **other** network's host — a preview URL on a mainnet node, or the reverse
  (`reason=url-network-mismatch`).

---

## 4. Authentication — no new secret

The node signs in with **its own wallet's stake key** — the one derived from `WALLET_MNEMONIC` (and
`WALLET_ACCOUNT_INDEX`), the same key your Aquarium staking bond is named after. You configure nothing.

On every connection attempt it sends the wallet's stake address, the current time, and a CIP-30
signature over a fixed text containing both. The signature is valid for two minutes, is never written to
the log, and proves only that this node holds the stake key; it authorises nothing on chain.

**One connection per bond.** FluidTokens' server grants one open connection for each 30,000 FLDT
Aquarium staking bond. A second node on the same bond — a standby, or a test node started from the same
mnemonic — is refused with **429** while the first is connected. In the shadow this is harmless: the
standby keeps retrying on a slow ladder (§6) and simply has no stream until the first one lets go.

---

## 5. The log lines

Every line has one shape:

```
[oracle-stream] <event> boot=<id> ev=<n> <key>=<value> … [-- <explanation>]
```

- **`boot`** is 8 hex characters drawn once per process, so a restart is visible as a new `boot`.
- **`ev`** counts the INFO and WARN lines the stream has actually written in this process. A DEBUG line
  carries the current value without advancing it. **A gap in `ev` therefore means lines were lost**
  between the node and whatever you are reading the log with — never that they were filtered by level.
- Values contain no spaces. The one exception is `msg="…"`, which quotes the server's own refusal text
  (double quotes replaced, control characters dropped, at most 200 characters).

**Repeats are quiet.** A refusal or a disconnect is logged at WARN when it differs from the last one
logged at WARN, and at DEBUG while it repeats. A successful connection resets that, so the same refusal
after a connection WARNs again.

| Event | Level | Fields | Meaning |
|---|---|---|---|
| `on` | INFO | `network=` `host=` | at startup: the stream is starting, and where it connects |
| `off` | INFO or WARN | `reason=` (and `value=`, `network=`, `host=` where relevant) | at startup: the stream is off, and why (§2, §3). WARN when a setting of yours is wrong; also `reason=internal-error(<class>)` if the stream itself failed to start — the rest of the node is unaffected |
| `connecting` | DEBUG | `attempt=` | an attempt is starting |
| `connected` | INFO | `attempt=` `handshake_ms=` | the connection is open; `attempt` counts the attempts it took |
| `push` | INFO | `push=` `n=` `chars=` `ts=` `dly_ms=` | a push arrived and parsed: its sequence number, entries, size, the server's timestamp, and how many ms after that timestamp it arrived |
| `push-ignored` | WARN or DEBUG | `reason=` `count=` | a message that is not a usable push: `malformed`, `unknown-type:<type>`, `oversize` (over 1,048,576 characters) or `binary`. The connection stays up; the message is counted, never echoed |
| `refused` | WARN or DEBUG | `status=` `msg="…"` `retry_s=` | the server refused the connection (§6) |
| `closed` | WARN, DEBUG or INFO | `reason=` `up_s=` | a connection ended, and how long it had been open. `reason` is `server-close(<code>)`, `error(<class>)`, `no-pong`, `proactive` (INFO — planned, §7) or `shutdown` (INFO — the node is stopping) |
| `stake-changed` | DEBUG | `retry_s=0` | during a 403 hold, the node's own view of its stake changed, so it retries at once |
| `listener-error` | WARN | `callback=` `error=` | an internal handler failed on a push; the stream carries on |
| `task-error` | WARN | `task=` `error=` | an internal task failed; the stream carries on |

An exception is only ever named by its class, never by its message.

---

## 6. Refusals

When the server refuses a connection, the node logs `refused status=<code> msg="<the server's text>"
retry_s=<s>` followed by its own reading:

| Status | What it means | What to do |
|---|---|---|
| **400** | the request was malformed | nothing you can fix; it backs off (60 s, doubling to 30 min). Report it if it persists |
| **401** | the signature was rejected — almost always the **system clock**, which must be within two minutes of real time | check NTP on the host. The first 401 after a connection is retried at once with a new signature; repeats back off (60 s, doubling to 30 min) |
| **403** | no Aquarium staking bond for this wallet's stake key | expected on a node that is not staked. It retries hourly, and at once if the node's own staking check (the one the scheduled-transaction processor uses) changes its answer — re-checked every 5 minutes |
| **429** | another connection on this bond is already open | expected on a standby node on the same bond. It backs off (60 s, doubling to 30 min); a successful connection resets it |
| other | anything else, server errors included | treated like a dropped connection (§7) |

---

## 7. Retry and liveness

- **Liveness is a ping, never a push.** Pushes arrive only when a price changes, so silence is normal and
  never ends a connection. The node pings every 45 s; if no pong arrives within 10 s, the connection is
  dead: it is aborted and logged as `closed reason=no-pong`.
- **Dropped connections** (`server-close`, `error`, `no-pong`, timeouts — 10 s to connect, 20 s for the
  handshake) are retried after 1, 2, 5, 10, 20, 30 and then every 60 s, each ±20%. A connection that had
  stayed open for at least 60 s starts that ladder from the beginning again.
- **Planned reconnect.** The server closes a connection after two hours, so the node replaces it first,
  after 115 minutes: it closes the old connection (waiting at most 1 s for the server to acknowledge),
  and only then opens the new one with a fresh signature — never two at once, because the bond grants
  one. Logged at INFO as `closed reason=proactive`; it is not a fault.

---

## 8. Metrics

On `/actuator/prometheus`, alongside the node's other metrics:

| Prometheus name | Type | Meaning |
|---|---|---|
| `aquarium_oracle_stream_connected` | gauge | 1 while a connection is open, 0 otherwise |
| `aquarium_oracle_stream_last_push_seconds` | gauge | Unix time of the last push that parsed; 0 before any |
| `aquarium_oracle_stream_pushes_total{result}` | counter | messages received, by `result`: `parsed`, `unknown_type`, `malformed`, `oversize`, `binary` |
| `aquarium_oracle_stream_refusals_total{status}` | counter | refused connections, by `status`: `400`, `401`, `403`, `429`, `other` |
| `aquarium_oracle_stream_disconnects_total{reason}` | counter | connections that **had opened** and then ended, by `reason`: `proactive`, `server_close`, `error`, `no_pong`. An attempt that never opened is not a disconnect |

Every series exists from startup at 0, so an absent series means the stream is off, not that nothing
happened.

---

## 9. The readiness page

With the operator UI on (`LOANS_UI_ENABLED=true`, [deploying.md](deploying.md) §7), the readiness page
shows one small grey line for the stream, ending `· no decision reads it`:

| State | The line |
|---|---|
| off | `Oracle stream (shadow): off — <why>` |
| connecting | `Oracle stream (shadow): connecting…` |
| connected | `Oracle stream (shadow): connected <since> — no push yet (pushes arrive only when a price changes)`, or `… · last push <time> · <n> pushes` |
| disconnected | `Oracle stream (shadow): disconnected (<reason>) — retrying at <time>` |
| refused | `Oracle stream (shadow): refused <status> — <server's message> — retrying at <time>` |

**`/healthcheck` never includes the stream.** A refused, disconnected or disabled stream cannot make the
node report unhealthy, and nothing that restarts containers on `/healthcheck` will ever react to it.

---

## Comparing the stream with the API

Added by FAB-175.
