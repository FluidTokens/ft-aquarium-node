package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The shadow oracle stream judged against the production poller (FAB-175): every push gets exactly ONE outcome,
 * said as one {@code [oracle-stream] cmp} line.
 *
 * <ul>
 *   <li>{@code MATCHED}: the API served the push's entries within {@link #WINDOW} of the push — with the lag;</li>
 *   <li>{@code SUPERSEDED}: a newer, different push came first and the API never served this one in the window;</li>
 *   <li>{@code MISMATCH}: the API was observed in the window but never served this push's entries;</li>
 *   <li>{@code API_AHEAD}: the API served entries before any push carried them — either a push that arrived after
 *       the API had it ({@code lead_s}), or, after {@link #WINDOW}, an API change no push ever carried
 *       ({@code push=-});</li>
 *   <li>{@code UNCOVERED}: nothing can be judged — no API load in the window ({@code why=no-api}), the API's change
 *       fell inside {@link #API_LAG_ALLOWANCE} of a (re)connect ({@code why=unjudgeable}), the API already served
 *       the push's entries before the push's connection opened ({@code why=pre-connect}), the queue overflowed
 *       ({@code why=overflow}) or the node stopped ({@code why=shutdown}).</li>
 * </ul>
 *
 * <p><b>{@code why=pre-connect}</b> (ruling R-a): when the first API observation that matches a push is content the
 * API was already serving before that push's connection opened — the poller's baseline, typically the load the
 * node held at start — the push is not MATCHED and the lag gauge does not move. The API's state from before this
 * node was listening says nothing about who was first, so any lag measured against it would be invented.
 *
 * <p><b>{@code lag_s=0}</b> (ruling R-b) means "not behind, within the poll and hand-off measurement error": the lag
 * and its gauge are clamped at 0. A push stamped a few milliseconds after a poller load whose hand-off has not yet
 * reached the {@code oracle-stream} thread would otherwise read as a negative lag.
 *
 * <p><b>After {@link #shutdown}</b> (ruling R-c) any further push resolves at once as {@code UNCOVERED why=shutdown},
 * so a push that lands between the comparator's shutdown and the client's stop still gets its one line.
 *
 * <p>⛔ <b>The API side is the production poller's own result</b>, handed over by a
 * {@link FluidOracleClient.LoadListener} after each of its loads: no fetch of its own and no refresh. And it writes
 * nothing anywhere but its log lines and meters.
 *
 * <p><b>Equality is parse-equality</b>: equal sets of {@link OracleEntry} out of the same
 * {@link OracleRegistryParser}. {@code h} is the first 8 hex characters of the SHA-256 of the entries'
 * {@code toString()}s, sorted and joined by {@code \n} — the digest {@code FluidOracleClientLoadPinTest} pins.
 *
 * <p><b>Why these constants</b> (probe log items 18, 27, 32–35): on preview the WS led the API by at least 6 s and
 * at most 30 s, the API serves only what the WS already pushed unless FluidTokens' job stalls for more than three
 * minutes, and their change detector runs every minute; the API is polled every 30 s. {@link #WINDOW} covers all
 * of it with room. {@link #API_LAG_ALLOWANCE} covers a push missed while disconnected: an API change within it of
 * a (re)connect may be the API catching up on a push this node never saw, so it cannot be called "ahead".
 *
 * <p>Seconds are whole seconds rounded DOWN: {@code floor(milliseconds / 1000)}.
 *
 * <p>⛔ <b>Single-threaded</b>: every method runs on the {@code oracle-stream} thread (tests call it directly with
 * explicit instants). Only the meters are read from elsewhere, through {@code volatile} fields.
 *
 * <p>MISMATCH and API_AHEAD also say one {@code cmp-detail} line, latched on the NFT units that differ, so a
 * standing difference is said once at WARN and again only when it changes. The sides it compares:
 * MISMATCH — the push against the API's last load; API_AHEAD — the API's change against what the WS held before
 * it (the push before, for a push-attached one; the last push, for {@code push=-}).
 */
public final class OracleStreamComparator implements OracleStreamClient.Listener {

    static final Duration WINDOW = Duration.ofMinutes(5);
    static final Duration API_LAG_ALLOWANCE = Duration.ofSeconds(120);
    static final int MAX_PENDING = 64;

    /** How many push hashes are remembered to recognise a repeat. */
    static final int PUSHED_HASHES = 1024;
    /** NFT units echoed per list in a {@code cmp-detail} line. */
    static final int DETAIL_UNITS = 5;
    /** The longest {@code cmp-detail} fields, in chars. */
    static final int DETAIL_LIMIT = 1000;

    static final String METER_COMPARISON = "aquarium.oracle.stream.comparison";
    static final String METER_LAG = "aquarium.oracle.stream.comparison.lag.seconds";
    static final String METER_CONNECTED_SECONDS = "aquarium.oracle.stream.connected.seconds";

    enum Outcome {
        MATCHED, SUPERSEDED, MISMATCH, API_AHEAD, UNCOVERED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }

        boolean warn() {
            return this == MISMATCH || this == API_AHEAD;
        }
    }

    /**
     * One {@code cmp} line's fields. Null fields are left out; the order is fixed:
     * {@code push out n h}, then the outcome's own ({@code lag_s rep by api api_n lead_s why}), then
     * {@code gap_s dly_ms} for an outcome attached to a push.
     *
     * <p>{@code lag_s} is never below 0: {@code lag_s=0} means "not behind, within the poll and hand-off
     * measurement error" (R-b). {@code why} is one of {@code no-api}, {@code unjudgeable}, {@code pre-connect},
     * {@code overflow}, {@code shutdown}.
     */
    record Cmp(String push, Outcome outcome, int n, String hash, Long lagS, Integer rep, Long by, String api,
               Integer apiN, Long leadS, String why, String gapS, Long dlyMs) {

        String fields() {
            StringBuilder out = new StringBuilder("push=").append(push)
                    .append(" out=").append(outcome.name())
                    .append(" n=").append(n)
                    .append(" h=").append(hash);
            append(out, "lag_s", lagS);
            append(out, "rep", rep);
            append(out, "by", by);
            append(out, "api", api);
            append(out, "api_n", apiN);
            append(out, "lead_s", leadS);
            append(out, "why", why);
            append(out, "gap_s", gapS);
            append(out, "dly_ms", dlyMs);
            return out.toString();
        }

        private static void append(StringBuilder out, String key, Object value) {
            if (value != null) {
                out.append(' ').append(key).append('=').append(value);
            }
        }
    }

    static Cmp matched(long seq, int n, String hash, long lagS, int rep, String gapS, long dlyMs) {
        return new Cmp(String.valueOf(seq), Outcome.MATCHED, n, hash, lagS, rep, null, null, null, null, null,
                gapS, dlyMs);
    }

    static Cmp superseded(long seq, int n, String hash, long by, String gapS, long dlyMs) {
        return new Cmp(String.valueOf(seq), Outcome.SUPERSEDED, n, hash, null, null, by, null, null, null, null,
                gapS, dlyMs);
    }

    static Cmp mismatch(long seq, int n, String hash, String api, int apiN, String gapS, long dlyMs) {
        return new Cmp(String.valueOf(seq), Outcome.MISMATCH, n, hash, null, null, null, api, apiN, null, null,
                gapS, dlyMs);
    }

    static Cmp apiAhead(long seq, int n, String hash, long leadS, String gapS, long dlyMs) {
        return new Cmp(String.valueOf(seq), Outcome.API_AHEAD, n, hash, null, null, null, null, null, leadS, null,
                gapS, dlyMs);
    }

    /** An API change no push carried: the API's own {@code n} and {@code h}, and nothing attached to a push. */
    static Cmp apiAheadOrphan(int n, String hash) {
        return new Cmp("-", Outcome.API_AHEAD, n, hash, null, null, null, null, null, null, null, null, null);
    }

    static Cmp uncovered(long seq, int n, String hash, String why, String gapS, long dlyMs) {
        return new Cmp(String.valueOf(seq), Outcome.UNCOVERED, n, hash, null, null, null, null, null, null, why,
                gapS, dlyMs);
    }

    /**
     * The API's last load. {@code since} is the first load of the run of loads that served {@code hash} — the
     * earliest instant the API is known to have served it.
     */
    private record ApiState(String hash, Set<OracleEntry> entries, Instant at, Instant since) {
    }

    /** An API load whose hash moved from the previous load's. */
    private static final class ApiChange {
        final String hash;
        final Set<OracleEntry> entries;
        final Instant at;
        final boolean judgeable;
        boolean reported;

        ApiChange(String hash, Set<OracleEntry> entries, Instant at, boolean judgeable) {
            this.hash = hash;
            this.entries = entries;
            this.at = at;
            this.judgeable = judgeable;
        }
    }

    /** A push still waiting for its outcome. */
    private static final class Pending {
        final long seq;
        final Instant at;
        final String hash;
        final Set<OracleEntry> entries;
        final int rep;
        final String gapS;
        final long dlyMs;
        /** When the connection that delivered this push opened. */
        final Instant connOpenedAt;
        Long supersededBy;
        int apiObservations;

        Pending(long seq, Instant at, String hash, Set<OracleEntry> entries, int rep, String gapS, long dlyMs,
                Instant connOpenedAt) {
            this.seq = seq;
            this.at = at;
            this.hash = hash;
            this.entries = entries;
            this.rep = rep;
            this.gapS = gapS;
            this.dlyMs = dlyMs;
            this.connOpenedAt = connOpenedAt;
        }
    }

    private final OracleStreamLog events;
    private final Map<Outcome, Counter> outcomes = new EnumMap<>(Outcome.class);

    private Instant connOpenSince;
    private ApiState apiLast;
    private final ArrayDeque<ApiChange> apiChanges = new ArrayDeque<>();
    /** Push hash → the last push that carried it; least recently used dropped first. */
    private final LinkedHashMap<String, Long> pushedHashes = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > PUSHED_HASHES;
        }
    };
    private final List<Pending> pending = new ArrayList<>();
    /** Written on the stream thread only; volatile so a test may read it. */
    private volatile long pushSeq;
    private Instant lastPushAt;
    private Set<OracleEntry> lastPushEntries = Set.of();
    /** Set by {@link #shutdown}: every later push is UNCOVERED {@code why=shutdown} at once (R-c). */
    private boolean stopped;

    /** Connected time: closed spans, plus the open one up to the latest instant seen. */
    private long closedMillis;
    private Instant lastSeen;
    private volatile double connectedSeconds;
    private volatile double lastLagSeconds = Double.NaN;

    public OracleStreamComparator(OracleStreamLog log, MeterRegistry registry) {
        this.events = log;
        if (registry != null) {
            for (Outcome outcome : Outcome.values()) {
                outcomes.put(outcome, Counter.builder(METER_COMPARISON).tag("outcome", outcome.tag())
                        .description("Oracle stream pushes compared against the API poller, by outcome")
                        .register(registry));
            }
            Gauge.builder(METER_LAG, this, comparator -> comparator.lastLagSeconds)
                    .description("Seconds from the last matched push to the API load that served it; NaN before any")
                    .register(registry);
            FunctionCounter.builder(METER_CONNECTED_SECONDS, this, comparator -> comparator.connectedSeconds)
                    .description("Seconds the shadow oracle stream has held an open connection")
                    .register(registry);
        }
    }

    /** Pushes seen so far. */
    long pushes() {
        return pushSeq;
    }

    // ======================================================================================
    // The stream's side
    // ======================================================================================

    @Override
    public void onOpen(Instant at) {
        advance(at);
        closeSpan();
        connOpenSince = at;
        publishConnected();
    }

    @Override
    public void onClose(Instant at, String reason) {
        advance(at);
        closeSpan();
    }

    @Override
    public void onPush(Instant at, long serverTimestamp, OracleRegistryParser.Parsed parsed, int chars) {
        advance(at);
        long seq = pushSeq + 1;
        pushSeq = seq;
        Set<OracleEntry> entries = Set.copyOf(parsed.byOracleToken().values());
        String hash = hash(entries);
        Instant since = later(lastPushAt, connOpenSince);
        String gapS = since == null ? "-" : String.valueOf(seconds(since, at));
        long dlyMs = at.toEpochMilli() - serverTimestamp;
        Set<OracleEntry> before = lastPushEntries;
        lastPushAt = at;
        lastPushEntries = entries;
        events.debug("push", "push=" + seq + " n=" + parsed.entries().size() + " chars=" + chars
                + " ts=" + serverTimestamp + " dly_ms=" + dlyMs);

        if (stopped) {
            // R-c: the comparator has shut down, but the client has not stopped yet — still one line.
            emit(uncovered(seq, entries.size(), hash, "shutdown", gapS, dlyMs));
            return;
        }
        if (pushedHashes.containsKey(hash)) {
            // A repeat: the API may already be on it.
            if (apiLast != null && apiLast.hash().equals(hash)) {
                if (preConnect(apiLast, connOpenSince)) {
                    emit(uncovered(seq, entries.size(), hash, "pre-connect", gapS, dlyMs));
                } else {
                    emit(matched(seq, entries.size(), hash, 0, 1, gapS, dlyMs));
                }
            } else {
                pending.add(new Pending(seq, at, hash, entries, 1, gapS, dlyMs, connOpenSince));
            }
        } else {
            ApiChange ahead = firstChangeTo(hash, at);
            if (ahead == null) {
                pending.add(new Pending(seq, at, hash, entries, 0, gapS, dlyMs, connOpenSince));
            } else if (ahead.judgeable) {
                ahead.reported = true;
                emit(apiAhead(seq, entries.size(), hash, seconds(ahead.at, at), gapS, dlyMs));
                detail(String.valueOf(seq), Outcome.API_AHEAD, before, ahead.entries, "");
            } else {
                emit(uncovered(seq, entries.size(), hash, "unjudgeable", gapS, dlyMs));
            }
        }
        pushedHashes.put(hash, seq);
        for (Pending p : pending) {
            if (p.seq < seq && !p.hash.equals(hash) && p.supersededBy == null) {
                p.supersededBy = seq;
            }
        }
        while (pending.size() > MAX_PENDING) {
            Pending oldest = pending.removeFirst();
            emit(uncovered(oldest.seq, oldest.entries.size(), oldest.hash, "overflow", oldest.gapS, oldest.dlyMs));
        }
    }

    // ======================================================================================
    // The API's side, the clock and the end
    // ======================================================================================

    /** One load of the production poller: what it now holds, and the instant it stored as its refresh time. */
    void onApiLoad(Instant at, Collection<OracleEntry> entries) {
        advance(at);
        Set<OracleEntry> held = Set.copyOf(entries);
        String hash = hash(held);
        // The first load is a baseline, not a change: nothing says when the API moved to it.
        if (apiLast != null && !apiLast.hash().equals(hash)) {
            boolean judgeable = connOpenSince != null && !connOpenSince.isAfter(at.minus(API_LAG_ALLOWANCE));
            apiChanges.addLast(new ApiChange(hash, held, at, judgeable));
            while (apiChanges.size() > MAX_PENDING) {
                apiChanges.removeFirst();
            }
        }
        Instant since = apiLast != null && apiLast.hash().equals(hash) ? apiLast.since() : at;
        apiLast = new ApiState(hash, held, at, since);
        Iterator<Pending> waiting = pending.iterator();
        while (waiting.hasNext()) {
            Pending p = waiting.next();
            if (Duration.between(p.at, at).compareTo(WINDOW) > 0) {
                continue;
            }
            p.apiObservations++;
            if (p.hash.equals(hash)) {
                waiting.remove();
                if (preConnect(apiLast, p.connOpenedAt)) {
                    emit(uncovered(p.seq, p.entries.size(), p.hash, "pre-connect", p.gapS, p.dlyMs));
                    continue;
                }
                // R-b: a load handed off after a push stamped just after it is "not behind", never negative.
                long lag = Math.max(0, seconds(p.at, at));
                if (p.rep == 0) {
                    lastLagSeconds = lag;
                }
                emit(matched(p.seq, p.entries.size(), p.hash, lag, p.rep, p.gapS, p.dlyMs));
            }
        }
    }

    /** Resolves every push older than {@link #WINDOW}, then every API change no push carried. */
    void tick(Instant now) {
        advance(now);
        Iterator<Pending> waiting = pending.iterator();
        while (waiting.hasNext()) {
            Pending p = waiting.next();
            if (Duration.between(p.at, now).compareTo(WINDOW) <= 0) {
                continue;
            }
            waiting.remove();
            if (p.supersededBy != null) {
                emit(superseded(p.seq, p.entries.size(), p.hash, p.supersededBy, p.gapS, p.dlyMs));
            } else if (p.apiObservations == 0) {
                emit(uncovered(p.seq, p.entries.size(), p.hash, "no-api", p.gapS, p.dlyMs));
            } else {
                emit(mismatch(p.seq, p.entries.size(), p.hash, apiLast.hash(), apiLast.entries().size(), p.gapS,
                        p.dlyMs));
                Long apiIsPush = pushedHashes.get(apiLast.hash());
                detail(String.valueOf(p.seq), Outcome.MISMATCH, p.entries, apiLast.entries(),
                        apiIsPush == null ? "" : " api_is_push=" + apiIsPush);
            }
        }
        for (ApiChange change : apiChanges) {
            if (change.judgeable && !change.reported && Duration.between(change.at, now).compareTo(WINDOW) > 0
                    && !pushedHashes.containsKey(change.hash)) {
                change.reported = true;
                emit(apiAheadOrphan(change.entries.size(), change.hash));
                detail("-", Outcome.API_AHEAD, lastPushEntries, change.entries, "");
            }
        }
        Instant horizon = now.minus(WINDOW.plus(API_LAG_ALLOWANCE));
        apiChanges.removeIf(change -> change.at.isBefore(horizon));
    }

    /** The node is stopping: every push still waiting is UNCOVERED, and so is every push after this (R-c). */
    void shutdown(Instant now) {
        advance(now);
        stopped = true;
        for (Pending p : pending) {
            emit(uncovered(p.seq, p.entries.size(), p.hash, "shutdown", p.gapS, p.dlyMs));
        }
        pending.clear();
    }

    // ======================================================================================
    // Helpers
    // ======================================================================================

    private void emit(Cmp cmp) {
        Counter counter = outcomes.get(cmp.outcome());
        if (counter != null) {
            counter.increment();
        }
        if (cmp.outcome().warn()) {
            events.warn("cmp", cmp.fields());
        } else {
            events.info("cmp", cmp.fields());
        }
    }

    /** The {@code cmp-detail} line: which NFTs differ, latched on exactly that. */
    private void detail(String push, Outcome outcome, Set<OracleEntry> ws, Set<OracleEntry> api, String extra) {
        Map<String, OracleEntry> wsByUnit = byUnit(ws);
        Map<String, OracleEntry> apiByUnit = byUnit(api);
        List<String> onlyWs = wsByUnit.keySet().stream().filter(u -> !apiByUnit.containsKey(u)).toList();
        List<String> onlyApi = apiByUnit.keySet().stream().filter(u -> !wsByUnit.containsKey(u)).toList();
        List<String> differ = wsByUnit.keySet().stream()
                .filter(u -> apiByUnit.containsKey(u) && !wsByUnit.get(u).equals(apiByUnit.get(u)))
                .toList();
        String signature = "only_ws=" + String.join(",", onlyWs) + " only_api=" + String.join(",", onlyApi)
                + " differ=" + String.join(",", differ);
        String fields = "push=" + push + " out=" + outcome.name() + " only_ws=" + onlyWs.size()
                + " only_api=" + onlyApi.size() + " differ=" + differ.size() + extra
                + " -- only_ws:" + units(onlyWs) + " only_api:" + units(onlyApi) + " differ:" + units(differ);
        if (fields.length() > DETAIL_LIMIT) {
            fields = fields.substring(0, DETAIL_LIMIT);
        }
        events.warnOnChange("cmp-detail", signature, "cmp-detail", fields);
    }

    /** Sorted by NFT unit. */
    private static Map<String, OracleEntry> byUnit(Set<OracleEntry> entries) {
        return entries.stream().collect(Collectors.toMap(e -> e.oracleToken().toUnit(), Function.identity(),
                (a, b) -> b, TreeMap::new));
    }

    private static String units(List<String> units) {
        List<String> shown = units.size() <= DETAIL_UNITS ? units : units.subList(0, DETAIL_UNITS);
        String more = units.size() > DETAIL_UNITS ? ",+" + (units.size() - DETAIL_UNITS) : "";
        return "[" + String.join(",", shown) + more + "]";
    }

    /**
     * R-a: the API was already serving {@code api}'s content before the push's connection opened — its baseline,
     * not a change this node watched — so a match against it would invent a lag.
     */
    private static boolean preConnect(ApiState api, Instant connOpenedAt) {
        return connOpenedAt != null && api.since().isBefore(connOpenedAt);
    }

    /** The earliest remembered API change to {@code hash} strictly before {@code at}, or null. */
    private ApiChange firstChangeTo(String hash, Instant at) {
        for (ApiChange change : apiChanges) {
            if (change.hash.equals(hash) && change.at.isBefore(at)) {
                return change;
            }
        }
        return null;
    }

    private void advance(Instant at) {
        if (lastSeen == null || at.isAfter(lastSeen)) {
            lastSeen = at;
        }
        publishConnected();
    }

    private void closeSpan() {
        if (connOpenSince != null) {
            closedMillis += Math.max(0, Duration.between(connOpenSince, lastSeen).toMillis());
            connOpenSince = null;
        }
        publishConnected();
    }

    private void publishConnected() {
        long open = connOpenSince == null ? 0 : Math.max(0, Duration.between(connOpenSince, lastSeen).toMillis());
        connectedSeconds = (closedMillis + open) / 1000.0;
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    /** {@code floor(milliseconds / 1000)}. */
    static long seconds(Instant from, Instant to) {
        return Math.floorDiv(Duration.between(from, to).toMillis(), 1000L);
    }

    /** The first 8 hex characters of the SHA-256 of the entries' {@code toString()}s, sorted, joined by {@code \n}. */
    static String hash(Collection<OracleEntry> entries) {
        String joined = String.join("\n", entries.stream().map(OracleEntry::toString).sorted().toList());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
