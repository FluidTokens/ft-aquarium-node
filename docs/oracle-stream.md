# The oracle stream (shadow)

For operators who see `[oracle-stream]` lines in their log and want to know what they are, or who want
to turn the stream off. Nothing here needs action on a healthy node.

---

## 1. What it is

FluidTokens publishes oracle prices on two channels: the HTTP registry the node has always polled
(`api.fluidtokens.com/get-oracle-tokens`), and a WebSocket that **pushes** the same registry after it
changes. FluidTokens checks for changes about once a minute, so a push can follow a price change by up to
about 60 s; that timing is theirs, not the node's. The node connects to the WebSocket and listens — **as a
shadow**:

- **No decision reads it.** Every push is parsed, compared with the HTTP registry and written to the log as
  one `cmp` line, and that is all. No liquidation, price, health figure or readiness verdict changes because of it.
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
connection. Unset or blank means on; `true` and `false` are case-insensitive; any other value is refused
with a WARN (`reason=unrecognised-enabled`) and the stream stays off.

⚠ **An empty `LOANS_ORACLE_STREAM_ENABLED=` line in `docker/.env` turns the stream ON**, not off — blank
means on. To turn it off, write `false`.

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

On every connection attempt it sends the wallet's stake address, the stake public key, the current time,
and a CIP-30 signature over a fixed text containing the stake address and the time. The signature is
valid for two minutes, is never written to the log, and proves only that this node holds the stake key;
it authorises nothing on chain.

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
| `push` | DEBUG | `push=` `n=` `chars=` `ts=` `rcv=` `dly_ms=` `xfer_ms=` | a push arrived and parsed: its sequence number, entries, size, the server's timestamp, when it arrived, how many ms after that timestamp it arrived, and how long its frames took ([Measuring delivery](#measuring-delivery)). DEBUG because its `cmp` line says the same push at INFO |
| `cmp` | INFO, or WARN for `MISMATCH` and `API_AHEAD` | `push=` `out=` `n=` `h=`, then the outcome's own, then — on a line about a push — `gap_s=` `dly_ms=` `ts=` `rcv=` `xfer_ms=` | the one verdict on a push compared with the API (or, with `push=-`, on an API change no push carried) — [Comparing the stream with the API](#comparing-the-stream-with-the-api) |
| `cmp-detail` | WARN or DEBUG | `push=` `out=` `only_ws=` `only_api=` `differ=` (and `api_is_push=`) | after a `MISMATCH` or `API_AHEAD`: which oracle NFTs differ. WARN when the set differs from the last one said, DEBUG while it repeats |
| `day` | INFO | `push=` `up_s=` `conn_s=` `pushes=` … `last=` | the cumulative summary at each UTC midnight, and `last=1` when the node stops — [The `day` line](#the-day-line) |
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
| `aquarium_oracle_stream_comparison_total{outcome}` | counter | `cmp` lines, by `outcome`: `matched`, `superseded`, `mismatch`, `api_ahead`, `uncovered` |
| `aquarium_oracle_stream_comparison_lag_seconds` | gauge | the `lag_s` of the last first-time `MATCHED` push (`rep=0`); NaN before any |
| `aquarium_oracle_stream_connected_seconds_total` | counter | seconds a connection has been open since startup, the open one included |

Every series exists from startup at 0 (the lag gauge at NaN), so an absent series means the stream is off,
not that nothing happened.

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

Every push is judged against what the node's own HTTP poller loads, and said as **one** `cmp` line.
The poller's result is handed to the stream after each of its loads; the stream never calls the API
itself, and nothing it concludes reaches a decision.

### The outcomes

"Equal" here means **parse-equal**: both sides run through the same parser, and the two sets of parsed
entries are compared (see the blind spots below). `h` is the first 8 hex characters of a digest of the
parsed entries, so two lines with the same `h` carry the same prices.

| `out=` | Level | The rule |
|---|---|---|
| `MATCHED` | INFO | the poller loaded the push's entries within **5 minutes** of the push. `lag_s` is how far behind the API was. **`lag_s=0` means "not behind"**: the lag is clamped at 0, because a load handed over a few milliseconds after a push stamped just after it would otherwise read as negative |
| `SUPERSEDED` | INFO | a newer, different push came first (`by=` its number) and the API never served this one within the 5 minutes |
| `MISMATCH` | WARN | the poller loaded within the 5 minutes, but never the push's entries; `api=`/`api_n=` are what it held instead |
| `API_AHEAD` | WARN | the API served entries **before** any push carried them. Either a push that arrived after the API had them (`lead_s=` by how much), or — once 5 minutes have passed — an API change no push ever carried, logged with `push=-` and the API's own `n` and `h` |
| `UNCOVERED` | INFO | nothing can be judged, and `why=` says why (below) |

- **The 5-minute window** covers what preview showed: the WebSocket led the API by 6–30 s, FluidTokens'
  change detector runs every minute, and the node polls every 30 s.
- **The 120-second allowance.** An API change less than 120 s after a (re)connect may be the API catching
  up on a push the node missed while disconnected, so it is never called `API_AHEAD`: a push carrying it
  is `UNCOVERED why=unjudgeable`, and an orphan of it is not logged at all.
- **Repeat pushes** (`rep=1`): a push of entries an earlier push already carried. If the API is already on
  them it is `MATCHED lag_s=0 rep=1` at once; otherwise it waits like any other push.
- **The same API change can be logged twice**: first as an orphan (`push=-`), then attached to a push that
  carries it late. Count `API_AHEAD` by distinct `h`, never by lines — the recipe below does.

The `why=` of an `UNCOVERED` line — **none of these is a failure**; each is a push nobody can verify:

| `why=` | Meaning |
|---|---|
| `no-api` | no poller load at all within the 5 minutes (the API, or the poller, was unreachable) |
| `unjudgeable` | the API's change fell within 120 s of a (re)connect |
| `pre-connect` | the first poller load matching the push is one that does not witness timing: the API already served those entries before the push's connection opened, or it is the very first load the node ever made, or a later load still serving that same content (its starting point, which says nothing about when the API moved to it). No lag is ever measured against either |
| `overflow` | more than 64 pushes were waiting for a verdict; the oldest is given up |
| `shutdown` | the node stopped before the 5 minutes were up — or a push arrived while it was stopping |

`MISMATCH` and `API_AHEAD` are each followed by one `cmp-detail` line naming the oracle NFTs that differ
(`only_ws`, `only_api`, `differ`, at most five of each). A standing difference is said once at WARN and
again only when it changes.

### Measuring delivery

Every `cmp` line about a push ends with how that push was delivered, so push delay can be measured from the
line itself — across nodes and across clients — without trusting the log's own timestamp:

| Field | Meaning |
|---|---|
| `ts` | FluidTokens' own `timestamp` on the push, epoch milliseconds, exactly as sent |
| `rcv` | when this node received the **complete** message — its last frame — in epoch milliseconds from the host's clock, read before the message is parsed |
| `dly_ms` | `rcv − ts`: how long after FluidTokens stamped the push this node had all of it |
| `xfer_ms` | milliseconds from the message's first frame to its last; `0` for a message in one frame. Measured where the Java WebSocket client hands the frames over, so it includes that client's own buffering — not a wire-level figure |

- **The values are taken when the push arrives** and travel with it, so a `cmp` line written minutes later —
  when the API catches up — still names the arrival, never the moment the line was written. An `API_AHEAD`
  with `push=-` carries none of them: no push arrived.
- **Comparing nodes is only as accurate as each host's clock.** `rcv` comes from the host's clock, so a node
  whose clock is a second fast reports every push a second late. Keep the clock synchronised (chrony or NTP);
  a skewed clock reads as delay — or as a negative one — and the node cannot detect its own skew.
- **The same push on several nodes** is joined by `ts` (or by `h`: the same 8-hex content hash on every node).

### The `day` line

At each **UTC midnight**, and once more when the node stops, the stream writes one INFO summary:

```
[oracle-stream] day boot=<id> ev=<n> push=<last push> up_s= conn_s= pushes= matched= superseded= mismatch= api_ahead= uncovered= lag_max_s= gap_max_s= drops= heals= proactive= last=<0|1>
```

Everything in it is **cumulative since the node started** — never reset at midnight — so the last `day`
line of a boot (its highest `ev`) carries the whole boot, and `last=1` marks the one written at shutdown.
There is exactly one `last=0` line per midnight; if the host's clock jumps forward over several midnights
(a suspended VM, say), each one gets its line when the node wakes.

- `up_s`: seconds since the stream started — counted from its first event after startup, which is at most
  10 s after the launch; `conn_s`: seconds a connection was open, including the one open now.
- `matched` … `uncovered`: `cmp` lines by outcome; `api_ahead` counts lines, orphans included.
- `lag_max_s`: the largest `lag_s` of a `MATCHED` line.
- `gap_max_s`: the longest quiet stretch inside one connection — between two pushes, from the open to the
  first push, or from the last push to the close. It is measured at each push and each close, so a quiet
  stretch still running when the line is written is not in it yet.
- `drops`: connections that ended other than by the planned reconnect or by shutdown; `heals`: connections
  that opened after a drop; `proactive`: planned reconnects (§7).

⚠ **A `kill -9` (or an out-of-memory kill) loses everything since the last `day` line.** The recipe then
reports what it can see — `ev` gaps — rather than inventing the missing numbers.

### What parse-equality cannot see

A `MATCHED` line proves the two sides carried the same **parsed** entries, not the same bytes:

- **Fields the parser ignores** never take part: `_id`, `ticker`, `decimals`, `governanceToken`, logo and
  name, and whether a key is `null` or absent.
- **Entries both sides drop** are invisible: an inactive entry, an unknown provider, a zero denominator, or
  an entry that fails to parse.
- **Signatures from keys outside `publicKeys`** are dropped on both sides, so a difference there is not
  seen.
- **A duplicated oracle NFT** collapses to the last entry naming it, on both sides.
- **Array order** is irrelevant.
- **Lag is only as fine as the poll**: the poller loads every 30 s, so `lag_s` has a resolution of 30 s.

### How much log it takes

An assumption, not a guarantee: the worst case is **one push a minute**, because FluidTokens' change
detector runs every minute (measured on preview on 2026-10-29). That is 1,440 `cmp` lines a day, each at
most 350 bytes as the node writes it, plus about 80 bytes of Docker's own wrapping per line — 430 × 1,440,
roughly **0.62 MB a day**. The connection, refusal and `day` lines are negligible beside that.

The node's container keeps at most **50 MB of log** (`max-size: 10m`, `max-file: 5` in
`docker/docker-compose.yaml`), so the stream uses about 1.2% of it a day, and a 7-day gate needs about
4.3 MB of `[oracle-stream]` lines. That budget is shared with **everything** else the node logs, which is
far more, so do not count on Docker still holding a week: **export daily**.

### Exporting the lines

Once a day (from cron, for example), on the host — `<node>` is the container's name, `aquarium` in the
shipped compose file:

```sh
docker logs <node> --since 24h 2>&1 | grep '\[oracle-stream\]' >> oracle-stream.log
```

Overlapping exports are harmless: the recipe ignores a line it has already counted. A missed day is not —
it shows up as `ev` gaps.

### The W3 gate recipe

The gate the stream must pass before anything may depend on it (Giovanni, 2026-10-29): at least **7 days**
of mainnet shadow; **every** push parse-equal to the API content it precedes; the API **never** ahead of
the stream; at least **99% uptime**, with every drop healing by itself; and the longest quiet gap measured.

Run it over the exported file, with any POSIX `sh` and `awk`:

```sh
sh w3-gate.sh < oracle-stream.log
```

where `w3-gate.sh` is exactly this block:

<!-- w3-gate-recipe:start -->
```sh
# W3 gate over exported [oracle-stream] lines on stdin. POSIX sh + awk only.
awk '
function fixed2(a, b,   x) {
  if (b == 0) return "-"
  x = int(a * 100 / b)
  return sprintf("%d.%02d", int(x / 100), x % 100)
}
{
  sub(/\r$/, "")
  s = $0; found = 0
  # The first "[oracle-stream] <event> boot=.. ev=.." on the line: any log prefix is skipped.
  while ((i = index(s, "[oracle-stream] ")) > 0) {
    s = substr(s, i + 16)
    n = split(s, t, " ")
    if (n >= 3 && t[2] ~ /^boot=/ && t[3] ~ /^ev=/) { found = 1; break }
  }
  if (!found) next
  # boot and ev by position: a server msg="..." later on the line cannot rewrite them.
  b = substr(t[2], 6); e = substr(t[3], 4) + 0
  split("", f)
  for (j = 4; j <= n; j++) {
    if (t[j] == "--") break
    p = index(t[j], "=")
    if (p > 1) f[substr(t[j], 1, p - 1)] = substr(t[j], p + 1)
  }
  if (!(b in known)) { known[b] = 1; boot[++nb] = b; evmax[b] = 0; evn[b] = 0; dayev[b] = 0 }
  if (e > 0 && !((b, e) in seenev)) { seenev[b, e] = 1; evn[b]++; if (e > evmax[b]) evmax[b] = e }
  if (t[1] == "cmp") {
    # API_AHEAD by distinct h: one API change can be logged as an orphan, then on a late push.
    if (f["out"] == "API_AHEAD" && !((b, f["h"]) in ahead)) { ahead[b, f["h"]] = 1; api_ahead++ }
    if (f["push"] != "-" && !((b, f["push"]) in seenpush)) {
      seenpush[b, f["push"]] = 1; pushes++; out[f["out"]]++
      if (f["out"] == "MATCHED" && f["lag_s"] + 0 > lag_max) lag_max = f["lag_s"] + 0
    }
  } else if (t[1] == "day" && e > dayev[b]) {
    # The day line with the highest ev is the latest: its fields cover the whole boot.
    dayev[b] = e; up[b] = f["up_s"] + 0; conn[b] = f["conn_s"] + 0
    drops[b] = f["drops"] + 0; heals[b] = f["heals"] + 0; gap[b] = f["gap_max_s"] + 0
  }
}
END {
  U = 0; C = 0; D = 0; H = 0; G = 0; L = 0; gaps = ""
  for (k = 1; k <= nb; k++) {
    b = boot[k]
    U += up[b]; C += conn[b]; D += drops[b]; H += heals[b]; L += evmax[b] - evn[b]
    if (gap[b] + 0 > G) G = gap[b] + 0
    gaps = gaps (k > 1 ? "," : "") b ":" (evmax[b] - evn[b])
  }
  P = pushes + 0
  print "boots=" nb + 0
  print "pushes=" P
  print "matched=" out["MATCHED"] + 0
  print "superseded=" out["SUPERSEDED"] + 0
  print "mismatch=" out["MISMATCH"] + 0
  print "api_ahead=" api_ahead + 0
  print "uncovered=" out["UNCOVERED"] + 0
  print "verified_pct=" fixed2(out["MATCHED"] * 100, pushes + 0)
  print "up_s=" U
  print "conn_s=" C
  print "uptime_pct=" fixed2(C * 100, U + 0)
  print "drops=" D
  print "heals=" H
  print "unhealed=" D - H
  print "gap_max_s=" G
  print "lag_max_s=" lag_max + 0
  print "days=" fixed2(U, 86400)
  print "ev_gaps=" gaps
  r = ""
  if (out["MISMATCH"] + 0 > 0) r = r ",mismatch"
  if (api_ahead + 0 > 0) r = r ",api_ahead"
  if (C * 100 < 99 * U) r = r ",uptime"
  if (D - H > 0) r = r ",unhealed"
  if (U < 7 * 86400) r = r ",days"
  if (P == 0) r = r ",no-pushes"
  if (L > 0) r = r ",lost-lines"
  print "verdict=" (r == "" ? "PASS" : "FAIL") " reasons=" substr(r, 2)
  exit (r == "" ? 0 : 1)
}'
```
<!-- w3-gate-recipe:end -->

It prints one `key=value` per line and ends with `verdict=PASS reasons=` or `verdict=FAIL reasons=<list>`,
exiting 0 on PASS and 1 on FAIL (anything else means the recipe itself failed).

- **How it counts.** Outcomes come from `cmp` lines, each push counted once per boot (`boot`, `push`), so
  an overlapping export counts nothing twice; `api_ahead` counts distinct `h` per boot. `boot` and `ev` are
  read by their position right after the event name, so a server's quoted `msg="…"` later on a line can
  never stand in for them. Uptime comes from each boot's latest `day` line (`up_s`, `conn_s`, `drops`,
  `heals`, `gap_max_s`), summed across boots — or, for `gap_max_s`, the largest. `lag_max_s` is the largest
  `MATCHED` lag. `days` is the summed `up_s` over 86,400, rounded down to two decimals, as are both
  percentages (`-` when there is nothing to divide by).
- **FAIL** when any of: `mismatch` above 0, `api_ahead` above 0, `uptime_pct` below 99 (exactly 99.00 passes),
  `unhealed` (drops minus heals) above 0, `days` below 7, `no-pushes`, `lost-lines`. `reasons` names each.
- **The gate fails without evidence** (Giovanni, 2026-10-29). Two of the reasons are about the log, not the
  stream:
  - `no-pushes`: not one push in the whole log. Nothing was compared, so nothing was proven — a quiet week
    is not a passing week.
  - `lost-lines`: any `ev` gap in any boot (`ev_gaps` below). A missing line may have been the `MISMATCH`
    or `API_AHEAD` that fails the gate, so the gate cannot pass over it. Re-export the missing days if Docker
    still holds them; otherwise the week has to be run again.
- ⚠ **`SUPERSEDED` and `UNCOVERED` pushes are UNVERIFIED** — neither proven equal nor proven different.
  They do not fail the gate; `verified_pct` (matched over pushes) is reported for Giovanni's judgement, not
  turned into a verdict.
- **`ev_gaps`** lists, per boot, how many `ev` numbers are missing between 1 and the highest seen —
  lines lost, a boot whose start fell outside every export, or a missed export. Any of them is a
  `lost-lines` FAIL. A boot with no `day` line at all (killed before its first midnight) adds nothing to
  `up_s`, and the lines a `kill -9` lost after the last `day` line leave no gap to see: **a PASS is a
  verdict on the lines that survived.**
- `lag_s=0` lines are "not behind", not missing data; `why=pre-connect` and every other `UNCOVERED` reason
  are unverified pushes, not failures.
