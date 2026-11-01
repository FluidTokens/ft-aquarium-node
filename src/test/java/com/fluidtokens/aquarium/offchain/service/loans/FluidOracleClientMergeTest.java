package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-183: {@link FluidOracleClient}'s one merged, NFT-keyed snapshot, written by the API's {@code load} and by the
 * stream's {@code mergeStreamPush} / {@code streamConnected}.
 *
 * <p>Per NFT a strictly newer {@code validFrom} wins from either source and an equal one keeps the held entry
 * (Giovanni A1, 2026-10-31); an NFT stays held only while the API's last load or a CONNECTED stream's last push lists
 * it; the change listener is told once per changing write; the load listener still hears the API's own entries; and
 * two writers racing never lose an update or publish a torn snapshot.
 */
class FluidOracleClientMergeTest {

    private static final String TOKEN_POLICY = "aa".repeat(28);
    private static final String NFT_POLICY = "bb".repeat(28);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long T0 = 1_793_000_000_000L;
    /** A 50-minute window, inside the protocol's 60-minute maximum, so every feed here is usable at {@code T0 + 1}. */
    private static final long WINDOW_MS = 3_000_000L;

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;
    private Level previous;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(FluidOracleClient.class);
        previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void release() {
        logger.detachAppender(appender);
        logger.setLevel(previous);
    }

    // ======================================================================================
    // The merge rule
    // ======================================================================================

    @Test
    void aNewerValidFromWinsFromEitherSourceAnEqualOneKeepsTheHeldEntryAndAnOlderOneIsIgnored() {
        var client = client();
        client.streamConnected(true);
        AssetType a = nft("A");

        client.load(payload(entry("TOK", "A", 1, T0, 10)));
        assertHeld(client, a, T0, 10, "the API's first entry");

        client.mergeStreamPush(parsed(entry("TOK", "A", 1, T0 + 200, 20)), Instant.now());
        assertHeld(client, a, T0 + 200, 20, "a newer push wins");

        client.load(payload(entry("TOK", "A", 1, T0 + 150, 30)));
        assertHeld(client, a, T0 + 200, 20, "an older API entry is ignored");

        client.load(payload(entry("TOK", "A", 1, T0 + 200, 40)));
        assertHeld(client, a, T0 + 200, 20, "an EQUAL API entry keeps the held one");

        client.load(payload(entry("TOK", "A", 1, T0 + 300, 50)));
        assertHeld(client, a, T0 + 300, 50, "a newer API entry wins");

        client.mergeStreamPush(parsed(entry("TOK", "A", 1, T0 + 300, 60)), Instant.now());
        assertHeld(client, a, T0 + 300, 50, "an EQUAL push keeps the held one");

        client.mergeStreamPush(parsed(entry("TOK", "A", 1, T0 + 250, 70)), Instant.now());
        assertHeld(client, a, T0 + 300, 50, "an older push is ignored");
        assertEquals(1, client.entries().size());
    }

    @Test
    void v1AndV2OfOneTokenAreHeldApartAndEachLegIsPricedByItsOwnNft() {
        var client = client();
        client.streamConnected(true);
        AssetType token = token("TOK");
        AssetType v1 = nft("TOKv1");
        AssetType v2 = nft("TOKv2");
        client.load(payload(entry("TOK", "TOKv1", 1, T0, 10), entry("TOK", "TOKv2", 2, T0, 10)));

        client.mergeStreamPush(parsed(entry("TOK", "TOKv2", 2, T0 + 100, 20)), Instant.now());

        assertEquals(2, client.entries().size(), "one entry per NFT, never one per token");
        assertEquals(BigInteger.valueOf(10), price(client.findFeedForLeg(token, v1, T0 + 101)), "v1 by its own NFT");
        assertEquals(BigInteger.valueOf(20), price(client.findFeedForLeg(token, v2, T0 + 101)), "v2 by its own NFT");
        assertEquals(v2, client.findEntry(token).orElseThrow().oracleToken(), "LOAN-FREE: the highest version");
        assertEquals(List.of(v1, v2), client.snapshot().versionsByToken().get(token).stream()
                .map(OracleEntry::oracleToken).toList(), "both versions, in API order");
        assertEquals(1, client.trackedAssets());
        assertEquals(2, client.trackedOracles());
    }

    // ======================================================================================
    // The membership rule
    // ======================================================================================

    @Test
    void anNftAbsentFromTheApisLastLoadIsDroppedWhileTheStreamIsDisconnectedAndKeptWhileAConnectedPushListsIt() {
        var client = client();
        AssetType a = nft("A");
        AssetType b = nft("B");
        AssetType c = nft("C");

        client.load(payload(entry("TA", "A", 1, T0, 1), entry("TB", "B", 1, T0, 1)));
        client.mergeStreamPush(parsed(entry("TA", "A", 1, T0, 1), entry("TB", "B", 1, T0, 1),
                entry("TC", "C", 1, T0, 1)), Instant.now());
        assertEquals(Set.of(a, b), nfts(client), "disconnected: a push holds nothing the API does not list");

        client.load(payload(entry("TA", "A", 1, T0, 1)));
        assertEquals(Set.of(a), nfts(client), "disconnected: an NFT the API stops listing is dropped");

        client.streamConnected(true);
        client.load(payload(entry("TA", "A", 1, T0, 1), entry("TB", "B", 1, T0, 1)));
        client.mergeStreamPush(parsed(entry("TA", "A", 1, T0, 1), entry("TB", "B", 1, T0, 1),
                entry("TC", "C", 1, T0, 1)), Instant.now());
        assertEquals(Set.of(a, b, c), nfts(client), "connected: the push's own NFT is held");

        client.load(payload(entry("TA", "A", 1, T0, 1)));
        assertEquals(Set.of(a, b, c), nfts(client), "connected: the last push still lists B and C");

        client.mergeStreamPush(parsed(entry("TA", "A", 1, T0, 1), entry("TB", "B", 1, T0, 1)), Instant.now());
        assertEquals(Set.of(a, b), nfts(client), "connected: C is listed by neither the API nor the last push");
        assertConsistent(client.snapshot());
    }

    @Test
    void aDisconnectDropsStreamOnlyNftsAndReportsStream() {
        var client = client();
        List<String> told = new CopyOnWriteArrayList<>();
        client.streamConnected(true);
        client.load(payload(entry("TA", "A", 1, T0, 1)));
        client.mergeStreamPush(parsed(entry("TA", "A", 1, T0, 1), entry("TC", "C", 1, T0, 1)), Instant.now());
        assertEquals(Set.of(nft("A"), nft("C")), nfts(client), "fixture: C is held by the stream alone");
        client.addChangeListener((source, changed, connected) -> told.add(source + " " + units(changed) + " " + connected));

        client.streamConnected(false);

        assertEquals(Set.of(nft("A")), nfts(client), "the stream-only NFT is dropped at once");
        assertEquals(List.of("STREAM " + nft("C").toUnit() + " false"), told);
        assertFalse(client.snapshot().streamConnected());
        assertEquals(Set.of(), client.snapshot().streamNfts(), "a push from an ended connection holds nothing");

        client.streamConnected(true);
        assertEquals(Set.of(nft("A")), nfts(client), "reconnecting brings back nothing by itself");
        assertEquals(1, told.size(), "a reconnect that changes nothing tells no one");
    }

    /**
     * Today's replace-on-load, reproduced: with no push ever merged and per-NFT {@code validFrom} never
     * decreasing, every lookup after each load answers exactly what a client that loaded ONLY that payload
     * answers — an NFT the payload leaves out included. Real registry snapshots, then synthetic ones.
     */
    @Test
    void apiOnlyLoadsWithNonDecreasingValidFromReproduceTodaysReplaceSemantics() throws Exception {
        List<JsonNode> payloads = new ArrayList<>(List.of(
                FluidOracleClientLoadPinTest.resource("/loans-v4/oracle-ws/preview-api-20261029T130505Z.json"),
                FluidOracleClientLoadPinTest.resource("/loans-v4/oracle-ws/preview-api-20261029T130525Z.json"),
                FluidOracleClientLoadPinTest.resource("/loans-v4/oracle-ws/preview-api-20261029T131027Z.json"),
                FluidOracleClientLoadPinTest.resource("/loans-v4/mainnet-oracle-registry-2026-10-01.json")));
        payloads.add(payload(entry("TA", "A", 1, T0, 1), entry("TB", "B", 1, T0, 1), entry("TOK", "TOKv1", 1, T0, 5),
                entry("TOK", "TOKv2", 2, T0, 5)));
        payloads.add(payload(entry("TA", "A", 1, T0, 1), entry("TB", "B", 1, T0 + 10, 2),
                entry("TOK", "TOKv2", 2, T0 + 10, 6), entry("TOK", "TOKv1", 1, T0 + 10, 6)));
        payloads.add(payload(entry("TB", "B", 1, T0 + 10, 2)));
        payloads.add(payload(entry("TA", "A", 1, T0 + 20, 3), entry("TB", "B", 1, T0 + 20, 3)));

        var merged = client();
        for (int i = 0; i < payloads.size(); i++) {
            var replaced = client();
            assertEquals(replaced.load(payloads.get(i)), merged.load(payloads.get(i)), "load's count, payload " + i);
            assertSameAnswers(replaced, merged, "after payload " + i);
        }
        assertEquals(Set.of(nft("A"), nft("B")), nfts(merged), "fixture: NFTs did disappear on the way");
    }

    // ======================================================================================
    // The change listener
    // ======================================================================================

    @Test
    void theChangeListenerIsToldOncePerChangingWriteWithItsSourceAndNftsAndNeverOnANoOpWrite() {
        var client = client();
        List<String> told = new CopyOnWriteArrayList<>();
        List<String> threads = new CopyOnWriteArrayList<>();
        client.addChangeListener((source, changed, connected) -> {
            told.add(source + " " + units(changed) + " " + connected);
            threads.add(Thread.currentThread().getName());
        });
        String a = nft("A").toUnit();
        String b = nft("B").toUnit();
        String c = nft("C").toUnit();

        client.load(payload(entry("TA", "A", 1, T0, 1), entry("TB", "B", 1, T0, 1)));
        assertEquals(List.of("API " + a + "," + b + " false"), told);

        client.load(payload(entry("TA", "A", 1, T0, 1), entry("TB", "B", 1, T0, 1)));
        client.streamConnected(true);
        client.mergeStreamPush(parsed(entry("TA", "A", 1, T0, 1), entry("TB", "B", 1, T0, 1)), Instant.now());
        assertEquals(1, told.size(), "a repeated load, a connect and an equal push changed nothing: " + told);

        client.mergeStreamPush(parsed(entry("TA", "A", 1, T0 + 200, 2), entry("TB", "B", 1, T0, 1)), Instant.now());
        assertEquals("STREAM " + a + " true", told.getLast());

        client.load(payload(entry("TA", "A", 1, T0 + 200, 2), entry("TB", "B", 1, T0 + 300, 3)));
        assertEquals("API " + b + " true", told.getLast());

        client.load(payload(entry("TA", "A", 1, T0 + 200, 2), entry("TB", "B", 1, T0 + 300, 3),
                entry("TC", "C", 1, T0, 1)));
        assertEquals("API " + c + " true", told.getLast());

        client.streamConnected(false);
        assertEquals(4, told.size(), "a disconnect that drops nothing tells no one: " + told);
        assertEquals(List.of(Thread.currentThread().getName()), threads.stream().distinct().toList(),
                "on the writer's own thread");
    }

    @Test
    void aThrowingChangeListenerIsNamedByItsClassAndNeitherStopsTheNextNorBreaksTheWrite() {
        var client = client();
        List<Set<AssetType>> second = new CopyOnWriteArrayList<>();
        client.addChangeListener((source, changed, connected) -> {
            throw new IllegalStateException("secret-ish detail that must never reach the log");
        });
        client.addChangeListener((source, changed, connected) -> second.add(changed));
        appender.list.clear();

        int priced = assertDoesNotThrow(() -> client.load(payload(entry("TA", "A", 1, T0, 1))));

        assertEquals(1, priced);
        assertEquals(List.of(Set.of(nft("A"))), second, "the listener after a throw still ran");
        assertEquals(Set.of(nft("A")), nfts(client), "the write is held");
        List<String> warns = appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertEquals(List.of("oracle change listener failed (IllegalStateException)"), warns);
        assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("secret-ish")));
        assertTrue(appender.list.stream().allMatch(e -> e.getThrowableProxy() == null), "no stack trace");
    }

    // ======================================================================================
    // The load listener hears the API's own content
    // ======================================================================================

    @Test
    void aLoadListenerStillHearsTheApisOwnEntriesAfterAStreamMergeMadeTheHeldValueNewer() {
        var client = client();
        List<Set<OracleEntry>> heard = new CopyOnWriteArrayList<>();
        client.addLoadListener((entries, at) -> heard.add(Set.copyOf(entries)));
        client.streamConnected(true);
        client.mergeStreamPush(parsed(entry("TA", "A", 1, T0 + 500, 9)), Instant.now());

        JsonNode api = payload(entry("TA", "A", 1, T0, 1), entry("TB", "B", 1, T0, 1));
        client.load(api);

        Set<OracleEntry> apiOwn = Set.copyOf(OracleRegistryParser.parse(api).entries());
        assertEquals(List.of(apiOwn), heard, "the API's own entries, never the merged ones");
        assertEquals(apiOwn, Set.copyOf(client.lastApiEntries()));
        assertHeld(client, nft("A"), T0 + 500, 9, "fixture: the held value is the stream's newer one");
        assertNotEquals(apiOwn, Set.copyOf(client.entries()), "fixture: merged and API's own differ");
    }

    // ======================================================================================
    // Two writers
    // ======================================================================================

    /**
     * The API thread loads and the stream thread pushes, {@value #WRITES} writes each, on two shared NFTs with
     * increasing {@code validFrom} (even for the API, odd for the stream), plus one NFT each side alone lists. A third
     * thread samples the snapshot throughout: every sample is internally consistent, and a held NFT's
     * {@code validFrom} never goes back — a lost update would make it. At the end each NFT holds the largest
     * {@code validFrom} written to it.
     */
    @Test
    void interleavedWritersLoseNoUpdateAndNoReaderSeesATornSnapshot() throws Exception {
        var client = client();
        client.streamConnected(true);
        List<JsonNode> loads = new ArrayList<>(WRITES);
        List<List<OracleEntry>> pushes = new ArrayList<>(WRITES);
        for (int i = 0; i < WRITES; i++) {
            long api = T0 + 2L * i;
            long stream = api + 1;
            loads.add(payload(entry("TA", "A", 1, api, i), entry("TB", "B", 1, api, i), entry("TC", "C", 1, api, i)));
            pushes.add(parsed(entry("TA", "A", 1, stream, i), entry("TB", "B", 1, stream, i),
                    entry("TA", "S", 2, stream, i)));
        }
        Errors errors = new Errors();
        AtomicBoolean writing = new AtomicBoolean(true);
        AtomicLong samples = new AtomicLong();
        CountDownLatch go = new CountDownLatch(1);

        Thread apiWriter = new Thread(() -> errors.run("api", go, () -> loads.forEach(client::load)));
        Thread streamWriter = new Thread(() -> errors.run("stream", go,
                () -> pushes.forEach(push -> client.mergeStreamPush(push, Instant.now()))));
        Thread sampler = new Thread(() -> errors.run("sampler", go, () -> {
            Map<AssetType, Long> seen = new HashMap<>();
            while (writing.get()) {
                FluidOracleClient.Snapshot s = client.snapshot();
                assertConsistent(s);
                s.byOracleToken().forEach((nft, entry) -> {
                    long now = entry.feed().validFrom();
                    Long before = seen.put(nft, now);
                    if (before != null && now < before) {
                        throw new AssertionError(nft.assetName() + " went back from " + before + " to " + now);
                    }
                });
                samples.incrementAndGet();
            }
        }));
        sampler.start();
        apiWriter.start();
        streamWriter.start();
        go.countDown();
        apiWriter.join(Duration.ofSeconds(60));
        streamWriter.join(Duration.ofSeconds(60));
        writing.set(false);
        sampler.join(Duration.ofSeconds(10));

        assertEquals(List.of(), errors.list(), "no write throws and every sample holds");
        assertFalse(apiWriter.isAlive() || streamWriter.isAlive() || sampler.isAlive(), "every thread finished");
        assertTrue(samples.get() > 0, "fixture: the sampler sampled");
        long lastApi = T0 + 2L * (WRITES - 1);
        Map<String, Long> last = new LinkedHashMap<>();
        client.entries().forEach(e -> last.put(new String(HexFormat.of().parseHex(e.oracleToken().assetName()),
                StandardCharsets.UTF_8), e.feed().validFrom()));
        assertEquals(Map.of("A", lastApi + 1, "B", lastApi + 1, "C", lastApi, "S", lastApi + 1), last,
                "each NFT holds the largest validFrom written to it");
        assertConsistent(client.snapshot());
    }

    private static final int WRITES = 2500;

    /** Every throw on any thread, named by where it happened. */
    private static final class Errors {
        private final ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();

        void run(String name, CountDownLatch go, Runnable body) {
            try {
                if (!go.await(10, TimeUnit.SECONDS)) {
                    errors.add(name + ": never started");
                    return;
                }
                body.run();
            } catch (Throwable t) {
                errors.add(name + ": " + t);
            }
        }

        List<String> list() {
            return List.copyOf(errors);
        }
    }

    // ======================================================================================
    // Helpers
    // ======================================================================================

    private static FluidOracleClient client() {
        return new FluidOracleClient("http://unused.invalid");
    }

    /**
     * The derived maps rebuilt here, independently, from the snapshot's own merged map: version lists in the API's
     * order for the NFTs it listed then stream-only NFTs by unit, and per token the highest {@code oracleVersion}
     * (the first of a tie). And the membership rule holds.
     */
    static void assertConsistent(FluidOracleClient.Snapshot s) {
        Map<AssetType, OracleEntry> merged = s.byOracleToken();
        List<AssetType> order = new ArrayList<>();
        s.apiEntries().keySet().forEach(nft -> {
            if (merged.containsKey(nft)) {
                order.add(nft);
            }
        });
        merged.keySet().stream().filter(nft -> !s.apiEntries().containsKey(nft))
                .sorted(Comparator.comparing(AssetType::toUnit)).forEach(order::add);
        Map<AssetType, List<OracleEntry>> versions = new LinkedHashMap<>();
        order.forEach(nft -> versions.computeIfAbsent(merged.get(nft).token(), k -> new ArrayList<>())
                .add(merged.get(nft)));
        Map<AssetType, OracleEntry> byToken = new HashMap<>();
        versions.forEach((token, list) -> {
            OracleEntry best = list.getFirst();
            for (OracleEntry e : list) {
                if (rank(e) > rank(best)) {
                    best = e;
                }
            }
            byToken.put(token, best);
        });
        if (!versions.equals(s.versionsByToken()) || !byToken.equals(s.byToken())) {
            throw new AssertionError("derived maps are not the ones rebuilt from the merged map: " + s);
        }
        if (!s.apiNfts().equals(s.apiEntries().keySet())) {
            throw new AssertionError("the API's NFT set is not its entries' keys");
        }
        for (AssetType nft : merged.keySet()) {
            if (!s.apiNfts().contains(nft) && !(s.streamConnected() && s.streamNfts().contains(nft))) {
                throw new AssertionError("held without the API or a connected push listing it: " + nft);
            }
        }
    }

    private static int rank(OracleEntry e) {
        return e.oracleVersion() == null ? Integer.MIN_VALUE : e.oracleVersion();
    }

    /** What every public lookup answers, for every token and NFT either client holds. */
    private static void assertSameAnswers(FluidOracleClient expected, FluidOracleClient actual, String when) {
        assertEquals(Set.copyOf(expected.entries()), Set.copyOf(actual.entries()), when + ": entries()");
        assertEquals(expected.trackedAssets(), actual.trackedAssets(), when + ": trackedAssets()");
        assertEquals(expected.trackedOracles(), actual.trackedOracles(), when + ": trackedOracles()");
        assertEquals(expected.snapshot().versionsByToken(), actual.snapshot().versionsByToken(), when + ": versions");
        Set<OracleEntry> all = new java.util.HashSet<>(expected.entries());
        all.addAll(actual.entries());
        for (OracleEntry e : all) {
            long at = e.feed().validFrom() + 1;
            assertEquals(expected.findEntry(e.token()), actual.findEntry(e.token()), when + ": findEntry");
            assertEquals(expected.findFeed(e.token(), at), actual.findFeed(e.token(), at), when + ": findFeed");
            assertEquals(expected.findFeedIgnoringValidity(e.token()), actual.findFeedIgnoringValidity(e.token()),
                    when + ": findFeedIgnoringValidity");
            assertEquals(expected.findEntryByOracleToken(e.oracleToken()), actual.findEntryByOracleToken(e.oracleToken()),
                    when + ": findEntryByOracleToken");
            assertEquals(expected.findFeedForLeg(e.token(), e.oracleToken(), at),
                    actual.findFeedForLeg(e.token(), e.oracleToken(), at), when + ": findFeedForLeg");
        }
    }

    private static void assertHeld(FluidOracleClient client, AssetType nft, long validFrom, long price, String why) {
        OracleEntry held = client.findEntryByOracleToken(nft).orElseThrow(() -> new AssertionError(why + ": none"));
        assertEquals(validFrom, held.feed().validFrom(), why);
        assertEquals(BigInteger.valueOf(price), held.feed().priceInLovelaces(), why);
        assertConsistent(client.snapshot());
    }

    private static Set<AssetType> nfts(FluidOracleClient client) {
        return client.entries().stream().map(OracleEntry::oracleToken).collect(Collectors.toSet());
    }

    private static String units(Set<AssetType> nfts) {
        return nfts.stream().map(AssetType::toUnit).sorted().collect(Collectors.joining(","));
    }

    private static BigInteger price(Optional<OraclePriceFeed> feed) {
        return feed.orElseThrow(() -> new AssertionError("no feed")).priceInLovelaces();
    }

    private static String hex(String name) {
        return HexFormat.of().formatHex(name.getBytes(StandardCharsets.UTF_8));
    }

    private static AssetType token(String name) {
        return new AssetType(TOKEN_POLICY, hex(name));
    }

    private static AssetType nft(String name) {
        return new AssetType(NFT_POLICY, hex(name));
    }

    /** One active multisig registry entry, priced {@code price}/1000000, valid for 50 minutes from {@code validFrom}. */
    private static ObjectNode entry(String token, String nft, Integer oracleVersion, long validFrom, long price) {
        ObjectNode node = JSON.createObjectNode();
        node.put("active", true);
        node.put("preferredOracle", "multisig");
        if (oracleVersion != null) {
            node.put("oracleVersion", oracleVersion);
        }
        node.putObject("token").put("policyId", TOKEN_POLICY).put("assetName", hex(token));
        node.putObject("fluidOracle").put("policyId", NFT_POLICY).put("assetName", hex(nft))
                .put("rewardAddress", "")
                .put("referenceInput", "cc".repeat(32) + "#0")
                .put("referenceScript", "dd".repeat(32) + "#0");
        node.putObject("supportedOracle").putObject("multisig")
                .put("tokenPriceInLovelaces", price)
                .put("tokenPriceDenominator", 1_000_000L)
                .put("validFrom", validFrom)
                .put("validTo", validFrom + WINDOW_MS);
        return node;
    }

    private static ArrayNode payload(ObjectNode... entries) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode e : entries) {
            array.add(e.deepCopy());
        }
        return array;
    }

    /** What the stream client hands over for a push of these entries: the parser's own entries. */
    private static List<OracleEntry> parsed(ObjectNode... entries) {
        List<OracleEntry> out = OracleRegistryParser.parse(payload(entries)).entries();
        assertEquals(entries.length, out.size(), "fixture: every entry parses");
        return out;
    }
}
