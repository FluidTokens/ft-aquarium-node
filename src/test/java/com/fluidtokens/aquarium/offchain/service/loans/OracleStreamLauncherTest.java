package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.service.StakerService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.fluidtokens.aquarium.offchain.service.loans.OracleStreamTestServer.await;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-174: when the shadow oracle stream starts, and that starting it never touches production.
 *
 * <p>Every OFF state says why in exactly one line and opens nothing; the one {@link FluidOracleClient} bean
 * stays the one bean and its prices and refresh time do not move while the shadow receives a push; {@code start}
 * returns at once; {@code stop} leaves no stream thread behind; and the shipped YAML and env-var binding are
 * pinned as shipped (officina {@code spring-boot-config-binding-traps} §4–§5).
 */
class OracleStreamLauncherTest {

    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String SERVER = "<server>";
    private static final String PREVIEW_PUSH = "/loans-v4/oracle-ws/preview-push-20261029T130459Z.json";
    private static final String PREVIEW_API = "/loans-v4/oracle-ws/preview-api-20261029T130525Z.json";
    private static final String RESOURCE = "application.yaml";

    private final Account account = new Account(Networks.preview());
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger captured;
    private Level previousLevel;
    private OracleStreamTestServer server;
    private OracleStreamTestServer.Hanging hanging;
    private OracleStreamLauncher launcher;

    @BeforeEach
    void captureTheLog() {
        captured = (Logger) LoggerFactory.getLogger("com.fluidtokens.aquarium.offchain");
        previousLevel = captured.getLevel();
        captured.setLevel(Level.TRACE);
        appender.start();
        captured.addAppender(appender);
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            if (launcher != null) {
                launcher.stop();
            }
            if (server != null) {
                server.close();
            }
            if (hanging != null) {
                hanging.close();
            }
        } finally {
            captured.detachAppender(appender);
            captured.setLevel(previousLevel);
        }
    }

    // ======================================================================================
    // OFF states
    // ======================================================================================

    static Stream<Arguments> offStates() {
        return Stream.of(
                Arguments.of("1 disabled", "FALSE ", true, "preview", SERVER, Level.INFO,
                        "reason=disabled -- loans.oracle.stream.enabled=false"),
                Arguments.of("1 disabled wins over a bad network", "false", true, "preprod", SERVER, Level.INFO,
                        "reason=disabled -- loans.oracle.stream.enabled=false"),
                Arguments.of("2 unrecognised", "maybe", true, "preview", SERVER, Level.WARN,
                        "reason=unrecognised-enabled value=maybe"),
                Arguments.of("3 no API oracle", "true", false, "preview", SERVER, Level.INFO,
                        "reason=no-api-oracle -- loans.oracle.enabled=false: nothing to compare against"),
                Arguments.of("3 no API oracle, blank flag", "", false, "preview", SERVER, Level.INFO,
                        "reason=no-api-oracle -- loans.oracle.enabled=false: nothing to compare against"),
                Arguments.of("4 network unsupported", "true", true, "preprod", SERVER, Level.INFO,
                        "reason=network-unsupported network=preprod"),
                Arguments.of("5 blank url", "true", true, "preview", "  ", Level.WARN, "reason=invalid-url"),
                Arguments.of("5 https url", "true", true, "preview", "https://127.0.0.1/dev", Level.WARN,
                        "reason=invalid-url"),
                Arguments.of("5 no host", "true", true, "preview", "wss:///dev", Level.WARN, "reason=invalid-url"),
                Arguments.of("5 a query", "true", true, "preview", SERVER + "?ts=1", Level.WARN,
                        "reason=invalid-url"),
                Arguments.of("5 a fragment", "true", true, "preview", SERVER + "#x", Level.WARN,
                        "reason=invalid-url"),
                Arguments.of("5 not a url", "true", true, "preview", "not a url at all", Level.WARN,
                        "reason=invalid-url"),
                Arguments.of("6 preview on the mainnet host", "true", true, "preview",
                        "wss://" + OracleStreamLauncher.MAINNET_HOST + "/prod", Level.WARN,
                        "reason=url-network-mismatch network=preview host=" + OracleStreamLauncher.MAINNET_HOST),
                Arguments.of("6 mainnet on the preview host", "true", true, "mainnet",
                        "wss://" + OracleStreamLauncher.PREVIEW_HOST + "/dev", Level.WARN,
                        "reason=url-network-mismatch network=mainnet host=" + OracleStreamLauncher.PREVIEW_HOST));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("offStates")
    void eachOffStateLogsExactlyOneLineAndConnectsNowhere(String name, String enabled, boolean apiOracle,
                                                         String network, String url, Level level, String expected) {
        server = new OracleStreamTestServer().start();
        String resolvedUrl = url.replace(SERVER, server.uri().toString());
        ObjectProvider<FluidOracleClient> oracle = apiOracle ? present() : absent();
        launcher = assertDoesNotThrow(() -> new OracleStreamLauncher(account, network(network), oracle, staker(), meters(), enabled,
                resolvedUrl));
        appender.list.clear();

        assertDoesNotThrow(launcher::start);
        launcher.start(); // idempotent: still one line

        List<ILoggingEvent> lines = streamLines();
        assertEquals(1, lines.size(), name + ": " + messages(lines));
        assertEquals(level, lines.getFirst().getLevel(), name);
        assertTrue(lines.getFirst().getFormattedMessage().startsWith("[oracle-stream] off boot="), name);
        assertTrue(lines.getFirst().getFormattedMessage().endsWith(" " + expected)
                        || lines.getFirst().getFormattedMessage().contains(" " + expected + " "),
                name + ": " + lines.getFirst().getFormattedMessage());
        sleepMillis(300);
        assertEquals(0, server.requests(), name + ": the server saw a connection");
        assertEquals(List.of(), streamThreads(), name + ": an off launcher started a thread");
    }

    @Test
    void constructionNeverThrowsEvenWithGarbageAndStartSaysWhyItIsOff() {
        launcher = assertDoesNotThrow(() -> new OracleStreamLauncher(null, null, null, null, null, null, null));
        assertDoesNotThrow(launcher::start);
        List<ILoggingEvent> lines = streamLines();
        assertEquals(1, lines.size(), messages(lines).toString());
        assertEquals(Level.WARN, lines.getFirst().getLevel());
        assertTrue(lines.getFirst().getFormattedMessage().endsWith(" off reason=internal-error(NullPointerException)")
                        || lines.getFirst().getFormattedMessage().contains("reason=internal-error(NullPointerException)"),
                lines.getFirst().getFormattedMessage());

        appender.list.clear();
        OracleStreamLauncher garbage = assertDoesNotThrow(() -> new OracleStreamLauncher(null, null, null, null, null,
                " \u0000 yes\nplease, a long one", "::::"));
        assertDoesNotThrow(garbage::start);
        assertDoesNotThrow(garbage::stop);
        List<ILoggingEvent> garbageLines = streamLines();
        assertEquals(1, garbageLines.size(), messages(garbageLines).toString());
        String line = garbageLines.getFirst().getFormattedMessage();
        assertTrue(line.contains(" off ") && line.contains(" reason=unrecognised-enabled value="), line);
        assertFalse(line.chars().anyMatch(Character::isISOControl), "no control character reaches the line: " + line);
        String value = line.substring(line.indexOf("value=") + "value=".length());
        assertTrue(value.length() <= 16 && !value.contains(" "), "first 16 chars, no spaces: '" + value + "'");
    }

    // ======================================================================================
    // status(): what the readiness page reads
    // ======================================================================================

    /** Each OFF state is reported by {@code status()} as OFF, its detail beginning with the line's reason token. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("offStates")
    void eachOffStateIsReportedByStatusWithItsReason(String name, String enabled, boolean apiOracle, String network,
                                                     String url, Level level, String expected) {
        server = new OracleStreamTestServer().start();
        String resolvedUrl = url.replace(SERVER, server.uri().toString());
        launcher = new OracleStreamLauncher(account, network(network), apiOracle ? present() : absent(), staker(),
                meters(), enabled, resolvedUrl);
        assertEquals(OracleStreamClient.Status.State.OFF, launcher.status().state(), "before start()");
        assertEquals("not started", launcher.status().detail());

        launcher.start();

        String reason = expected.substring("reason=".length()).split(" ")[0];
        OracleStreamClient.Status status = launcher.status();
        assertEquals(OracleStreamClient.Status.State.OFF, status.state(), name);
        assertNotNull(status.detail(), name);
        assertTrue(status.detail().startsWith(reason + " ("), name + ": " + status.detail());
        assertNotNull(status.since(), name);
    }

    @Test
    void anInternalErrorIsReportedByStatusAsOffWithItsClass() {
        launcher = new OracleStreamLauncher(null, null, null, null, null, null, null);
        launcher.start();
        assertEquals(OracleStreamClient.Status.State.OFF, launcher.status().state());
        assertEquals("internal-error(NullPointerException)", launcher.status().detail());
    }

    @Test
    void statusIsConnectedAfterTheOpenAndOffAfterStop() {
        server = new OracleStreamTestServer().authorize(account.stakeAddress()).start();
        launcher = new OracleStreamLauncher(account, network("preview"), present(), staker(), meters(), "true",
                server.uri().toString());
        launcher.start();
        server.awaitSession(0, WAIT);
        AtomicReference<OracleStreamClient.Status> seen = new AtomicReference<>();
        await(() -> {
            OracleStreamClient.Status now = launcher.status();
            seen.set(now);
            return now.state() == OracleStreamClient.Status.State.CONNECTED;
        }, WAIT, "CONNECTED");

        OracleStreamClient.Status connected = seen.get();
        assertNotNull(connected.since());
        assertNull(connected.lastPushAt(), "no push yet");
        assertEquals(0, connected.pushes());

        launcher.stop();
        assertEquals(OracleStreamClient.Status.State.OFF, launcher.status().state());
        assertEquals("stopped", launcher.status().detail());
    }

    /**
     * r3 F3: the PRODUCTION stake supplier ends a 403 hold. Nothing hands the client a supplier here: the launcher
     * builds its own from {@link StakerService#findStakerRefInput()}, and when that flips from empty to a bond the
     * held stream retries at the next re-check, long before the hold would have ended — read only on the
     * {@code oracle-stream} thread.
     */
    @Test
    void theLaunchersOwnStakeSupplierEndsA403HoldWhenTheBondAppears() {
        server = new OracleStreamTestServer().refuse(403,
                "{\"message\":\"no Aquarium staking bond found for this stake key\"}").start();
        AtomicBoolean bonded = new AtomicBoolean(false);
        AtomicInteger reads = new AtomicInteger();
        List<String> readers = new CopyOnWriteArrayList<>();
        StakerService staker = org.mockito.Mockito.mock(StakerService.class);
        org.mockito.Mockito.when(staker.findStakerRefInput()).thenAnswer(invocation -> {
            readers.add(Thread.currentThread().getName());
            reads.incrementAndGet();
            return bonded.get() ? List.of(new TransactionInput("ab".repeat(32), 0)) : List.of();
        });
        launcher = new OracleStreamLauncher(account, network("preview"), present(), staker, meters(), "true",
                server.uri().toString());
        OracleStreamClient.Timings production = OracleStreamClient.Timings.PRODUCTION;
        double scale = 0.003; // the 1 h hold → 10.8 s ±5%, the 5 min re-check → 900 ms
        launcher.useForTest(new OracleStreamClient.Timings(production.tcpConnectTimeout(),
                production.handshakeTimeout(), production.pingInterval(), production.pongTimeout(),
                production.proactiveReconnectAfter(), scale), Clock.systemUTC());
        launcher.start();

        AtomicReference<OracleStreamClient.Status> seen = new AtomicReference<>();
        await(() -> {
            OracleStreamClient.Status now = launcher.status();
            seen.set(now);
            return now.state() == OracleStreamClient.Status.State.REFUSED
                    && Integer.valueOf(403).equals(now.refusalStatus());
        }, WAIT, "the 403 hold");
        Instant holdEnds = seen.get().nextAttemptAt();
        assertNotNull(holdEnds);
        await(() -> reads.get() >= 2, WAIT, "the stake read at the refusal and at the first re-check");
        assertEquals(1, server.requests(), "no retry while the stake is unchanged");

        bonded.set(true);
        await(() -> server.requests() >= 2, WAIT, "the retry after the bond appeared");
        Instant retried = Instant.now();
        assertTrue(retried.isBefore(holdEnds), "the hold ended early: retried at " + retried
                + ", the hold ran to " + holdEnds);
        assertEquals(List.of(OracleStreamLauncher.THREAD_NAME), readers.stream().distinct().toList(),
                "the stake is read only on the oracle-stream thread");
    }

    // ======================================================================================
    // FAB-174-2 residue R1: the default-ON path for the SHIPPED hosts
    // ======================================================================================

    /**
     * Every ON case above connects to {@code ws://127.0.0.1}, which no network check can ever reject — so widening
     * the host-mismatch check to refuse the node's OWN shipped host turned the stream off on both networks with
     * the suite green. This decides with the URLs application.yaml actually ships, through the same pure
     * function {@code start()} uses, and opens no socket.
     */
    @Test
    void eachNetworkWithItsShippedUrlIsOnAtItsShippedHost() throws IOException {
        Map<String, String> shipped = shippedUrls();
        for (String network : List.of("mainnet", "preview")) {
            String url = shipped.get(network);
            OracleStreamLauncher.Decision decision = decide(network, url);
            String host = network.equals("mainnet") ? OracleStreamLauncher.MAINNET_HOST : OracleStreamLauncher.PREVIEW_HOST;
            assertTrue(decision.on(), network + " with its shipped URL must be ON: " + decision);
            assertEquals("network=" + network + " host=" + host, decision.fields(),
                    "the 'on' line's fields for " + network);
            assertEquals(URI.create(url), decision.uri());
            assertNull(decision.detail());
        }
        assertEquals("25mxd4whnj.execute-api.eu-west-1.amazonaws.com", OracleStreamLauncher.MAINNET_HOST);
        assertEquals("2tgda3mk4l.execute-api.eu-west-1.amazonaws.com", OracleStreamLauncher.PREVIEW_HOST);
    }

    /** {@code wss://} is the shipped scheme, and accepting it is the point, not an accident of a ws-only check. */
    @Test
    void wssIsAcceptedOnPurpose() throws IOException {
        for (String url : shippedUrls().values()) {
            assertEquals("wss", URI.create(url).getScheme(), "fixture: the shipped URL is wss: " + url);
            assertNotNull(OracleStreamLauncher.validUri(url), url);
        }
        assertNotNull(OracleStreamLauncher.validUri("WSS://" + OracleStreamLauncher.PREVIEW_HOST + "/dev"),
                "the scheme is case-insensitive");
        assertNotNull(OracleStreamLauncher.validUri("ws://127.0.0.1:1/dev"), "plain ws stays accepted (the tests' server)");
        assertNull(OracleStreamLauncher.validUri("https://" + OracleStreamLauncher.PREVIEW_HOST + "/dev"));
    }

    /** And the check that exists for a reason still holds: each network on the OTHER's shipped host stays off. */
    @Test
    void eachNetworkOnTheOtherNetworksShippedHostStaysOffWithTheWarn() throws IOException {
        Map<String, String> shipped = shippedUrls();
        OracleStreamLauncher.Decision mainnet = decide("mainnet", shipped.get("preview"));
        assertFalse(mainnet.on());
        assertTrue(mainnet.warn(), "a mismatch is a WARN");
        assertEquals("reason=url-network-mismatch network=mainnet host=" + OracleStreamLauncher.PREVIEW_HOST,
                mainnet.fields());

        OracleStreamLauncher.Decision preview = decide("preview", shipped.get("mainnet"));
        assertFalse(preview.on());
        assertTrue(preview.warn());
        assertEquals("reason=url-network-mismatch network=preview host=" + OracleStreamLauncher.MAINNET_HOST,
                preview.fields());
    }

    private static OracleStreamLauncher.Decision decide(String network, String url) {
        return OracleStreamLauncher.decide("true", () -> true, network(network), url);
    }

    /** The default of each network's {@code loans.oracle.stream.url} placeholder, as application.yaml ships it. */
    private static Map<String, String> shippedUrls() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader().load(RESOURCE, new ClassPathResource(RESOURCE));
        PropertySource<?> preview = documents.stream()
                .filter(d -> "preview".equals(String.valueOf(d.getProperty("spring.config.activate.on-profile"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no document activates the preview profile"));
        return Map.of(
                "mainnet", placeholderDefault(String.valueOf(documents.getFirst().getProperty("loans.oracle.stream.url"))),
                "preview", placeholderDefault(String.valueOf(preview.getProperty("loans.oracle.stream.url"))));
    }

    // ======================================================================================
    // ON: one bean, untouched
    // ======================================================================================

    /**
     * The shadow receives and logs a real push while the context's one {@link FluidOracleClient} — the bean
     * every decision reads — keeps exactly the entries and refresh time it had before {@code start()}.
     */
    @Test
    void exactlyOneOracleClientBeanAndTheShadowNeverTouchesIt() throws Exception {
        server = new OracleStreamTestServer().authorize(account.stakeAddress()).start();
        String push = resource(PREVIEW_PUSH);
        new ApplicationContextRunner()
                .withPropertyValues("network=preview", "loans.oracle.stream.url=" + server.uri())
                .withBean(FluidOracleClient.class, () -> {
                    try {
                        return OracleClients.mainnetTwoVersions();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withBean(Account.class, () -> account)
                .withBean(StakerService.class, OracleStreamLauncherTest::staker)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(AppConfig.Network.class, () -> network("preview"))
                .withBean(OracleStreamLauncher.class)
                .run(context -> {
                    assertNull(context.getStartupFailure(), "the context started");
                    FluidOracleClient oracle = context.getBean(FluidOracleClient.class);
                    Set<OracleEntry> entriesBefore = Set.copyOf(oracle.entries());
                    Instant refreshedBefore = oracle.lastRefresh();
                    assertEquals(35, entriesBefore.size(), "fixture: the mainnet registry the bean was loaded with");

                    OracleStreamLauncher bean = context.getBean(OracleStreamLauncher.class);
                    bean.start();
                    OracleStreamTestServer.Session session = server.awaitSession(0, WAIT);
                    session.sendText(push);
                    await(() -> bean.pushes() == 1, WAIT, "the shadow's onPush");
                    // The listener counts the push BEFORE it logs it: the line is awaited itself.
                    await(() -> messages(streamLines()).stream().anyMatch(l -> l.contains("[oracle-stream] push ")
                            && l.contains(" push=1 n=") && l.contains(" chars=" + push.length())
                            && l.contains(" ts=1793279099046 dly_ms=")), WAIT, "the push line");

                    assertEquals(1, context.getBeanNamesForType(FluidOracleClient.class).length);
                    assertSame(oracle, context.getBeanProvider(FluidOracleClient.class).getIfAvailable());
                    assertEquals(entriesBefore, Set.copyOf(oracle.entries()), "the shadow changed the prices");
                    assertEquals(refreshedBefore, oracle.lastRefresh(), "the shadow refreshed the oracle client");
                    assertTrue(messages(streamLines()).stream().anyMatch(l -> l.contains("[oracle-stream] on ")
                            && l.endsWith(" network=preview host=127.0.0.1")), messages(streamLines()).toString());
                    assertEquals(List.of(), server.authFailures());
                });
        assertEquals(List.of(), streamThreadsAfterAWhile(), "closing the context stops the stream");
    }

    /**
     * FAB-175, production-shaped: the comparison hears the API side only through the context's one
     * {@link FluidOracleClient} — its own load, here called as the poller would call it. A real push of the preview
     * fixture, then that load of the API snapshot parse-equal to it: one MATCHED {@code cmp} line, still one bean,
     * and the bean holds exactly what that load produced — the shadow never wrote.
     */
    @Test
    void aPushThenThePollersLoadOfTheSameEntriesIsOneMatchedLineAndTheShadowNeverWrites() throws Exception {
        server = new OracleStreamTestServer().authorize(account.stakeAddress()).start();
        String push = resource(PREVIEW_PUSH);
        JsonNode api = new ObjectMapper().readTree(resource(PREVIEW_API));
        Set<OracleEntry> apiParsed = Set.copyOf(OracleClients.fromFixture(PREVIEW_API).entries());
        new ApplicationContextRunner()
                .withPropertyValues("network=preview", "loans.oracle.stream.url=" + server.uri())
                .withBean(FluidOracleClient.class, () -> {
                    try {
                        return OracleClients.mainnetTwoVersions();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withBean(Account.class, () -> account)
                .withBean(StakerService.class, OracleStreamLauncherTest::staker)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(AppConfig.Network.class, () -> network("preview"))
                .withBean(OracleStreamLauncher.class)
                .run(context -> {
                    assertNull(context.getStartupFailure(), "the context started");
                    FluidOracleClient oracle = context.getBean(FluidOracleClient.class);
                    OracleStreamLauncher bean = context.getBean(OracleStreamLauncher.class);
                    bean.start();
                    server.awaitSession(0, WAIT).sendText(push);
                    await(() -> bean.pushes() == 1, WAIT, "the comparator's onPush");
                    assertEquals(List.of(), cmpLines(), "the push waits for the API");

                    oracle.load(api); // the poller's load, on this thread
                    Set<OracleEntry> loaded = Set.copyOf(oracle.entries());
                    Instant loadedAt = oracle.lastRefresh();
                    await(() -> !cmpLines().isEmpty(), WAIT, "the cmp line");

                    List<String> cmp = cmpLines();
                    assertEquals(1, cmp.size(), cmp.toString());
                    assertTrue(cmp.getFirst().contains(" cmp boot=") && cmp.getFirst().contains(
                            " push=1 out=MATCHED n=5 h=" + OracleStreamComparator.hash(apiParsed) + " lag_s="),
                            cmp.getFirst());
                    assertTrue(cmp.getFirst().contains(" rep=0 gap_s="), cmp.getFirst());
                    // FAB-175-1 audit residue (b): the poller's load was handed to the stream thread, never
                    // judged on the poller's own (here, this test's) thread.
                    assertEquals(OracleStreamLauncher.THREAD_NAME, cmpEvents().getFirst().getThreadName());
                    assertEquals(1, context.getBeanNamesForType(FluidOracleClient.class).length);
                    assertEquals(apiParsed, loaded, "fixture: the bean holds exactly what that load produced");
                    assertEquals(loaded, Set.copyOf(oracle.entries()), "the shadow changed the prices");
                    assertEquals(loadedAt, oracle.lastRefresh(), "the shadow refreshed the oracle client");
                    assertEquals(List.of(), server.authFailures());
                });
        assertEquals(1, cmpLines().size(), "nothing was waiting at shutdown");
        assertEquals(List.of(), streamThreadsAfterAWhile(), "closing the context stops the stream");
    }

    /**
     * r2 (a): the launcher's SCHEDULED tick is the only production path that resolves a push once
     * {@link OracleStreamComparator#WINDOW} has passed — SUPERSEDED, MISMATCH, UNCOVERED {@code why=no-api} and the
     * orphan API_AHEAD all come from it. A real push, no poller load, the launcher's clock moved past WINDOW, and
     * nothing here calls tick(): the {@code why=no-api} line can only come from the {@code cmp-tick} the launcher
     * scheduled.
     */
    @Test
    void theScheduledTickResolvesAPushNoLoadServedAsUncoveredNoApi() throws Exception {
        server = new OracleStreamTestServer().authorize(account.stakeAddress()).start();
        String push = resource(PREVIEW_PUSH);
        OracleStreamClientLivenessTest.MutableClock clock = new OracleStreamClientLivenessTest.MutableClock(Instant.now());
        launcher = new OracleStreamLauncher(account, network("preview"), present(), staker(), meters(), "true",
                server.uri().toString());
        launcher.useForTest(OracleStreamClient.Timings.PRODUCTION, clock);
        launcher.start();
        server.awaitSession(0, WAIT).sendText(push);
        await(() -> launcher.pushes() == 1, WAIT, "the comparator's onPush");
        assertEquals(List.of(), cmpLines(), "fixture: nothing has resolved the push yet");

        clock.advance(OracleStreamComparator.WINDOW.plusSeconds(1));
        await(() -> !cmpLines().isEmpty(), WAIT, "the scheduled tick's cmp line");

        List<String> cmp = cmpLines();
        assertEquals(1, cmp.size(), cmp.toString());
        assertTrue(cmp.getFirst().contains(" push=1 out=UNCOVERED n=5 h=")
                && cmp.getFirst().contains(" why=no-api gap_s="), cmp.getFirst());
    }

    /**
     * r2 (b): closing the context runs {@code stop()}, which resolves every push still waiting as UNCOVERED
     * {@code why=shutdown} — on the stream thread, before the client stops. A real push nothing matched, then the
     * context closes: exactly one such line.
     */
    @Test
    void aPushStillWaitingWhenTheContextClosesIsOneUncoveredShutdownLine() throws Exception {
        server = new OracleStreamTestServer().authorize(account.stakeAddress()).start();
        String push = resource(PREVIEW_PUSH);
        new ApplicationContextRunner()
                .withPropertyValues("network=preview", "loans.oracle.stream.url=" + server.uri())
                .withBean(FluidOracleClient.class, () -> {
                    try {
                        return OracleClients.mainnetTwoVersions();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .withBean(Account.class, () -> account)
                .withBean(StakerService.class, OracleStreamLauncherTest::staker)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(AppConfig.Network.class, () -> network("preview"))
                .withBean(OracleStreamLauncher.class)
                .run(context -> {
                    assertNull(context.getStartupFailure(), "the context started");
                    OracleStreamLauncher bean = context.getBean(OracleStreamLauncher.class);
                    bean.start();
                    server.awaitSession(0, WAIT).sendText(push);
                    await(() -> bean.pushes() == 1, WAIT, "the comparator's onPush");
                    assertEquals(List.of(), cmpLines(), "fixture: the push is still waiting");
                });

        List<String> cmp = cmpLines();
        assertEquals(1, cmp.size(), cmp.toString());
        assertTrue(cmp.getFirst().contains(" push=1 out=UNCOVERED n=5 h=")
                && cmp.getFirst().contains(" why=shutdown gap_s="), cmp.getFirst());
        assertEquals(List.of(), streamThreadsAfterAWhile(), "closing the context stops the stream");
    }

    /**
     * FAB-175-1 audit residue (a), the {@code cmp-seed} task: the oracle bean already holds the 13:05:25Z preview
     * snapshot before {@code start()}, and the push parse-equal to it arrives twice. Only the seed tells the
     * comparator what the API served before the connection opened, so the repeat resolves AT ONCE as UNCOVERED
     * {@code why=pre-connect} — no poller load needed. Then the poller's next load of the same snapshot resolves
     * push 1 the same way: no lag is ever measured against what the node found at start.
     */
    @Test
    void theSeedIsWhatTheOracleBeanHeldBeforeStartSoItsContentIsPreConnect() throws Exception {
        server = new OracleStreamTestServer().authorize(account.stakeAddress()).start();
        String push = resource(PREVIEW_PUSH);
        FluidOracleClient oracle = OracleClients.fromFixture(PREVIEW_API);
        assertNotEquals(Instant.EPOCH, oracle.lastRefresh(), "fixture: loaded before start");
        launcher = new OracleStreamLauncher(account, network("preview"), present(oracle), staker(), meters(), "true",
                server.uri().toString());
        launcher.start();
        OracleStreamTestServer.Session session = server.awaitSession(0, WAIT);
        session.sendText(push);
        session.sendText(push);
        await(() -> launcher.pushes() == 2, WAIT, "both pushes");
        await(() -> !cmpLines().isEmpty(), WAIT, "the repeat's cmp line, resolved by the seed alone");
        String h = OracleStreamComparator.hash(oracle.entries());
        assertEquals(1, cmpLines().size(), cmpLines().toString());
        assertTrue(cmpLines().getFirst().contains(" push=2 out=UNCOVERED n=5 h=" + h + " why=pre-connect "),
                cmpLines().getFirst());

        oracle.load(new ObjectMapper().readTree(resource(PREVIEW_API))); // the poller's next load
        await(() -> cmpLines().size() == 2, WAIT, "push 1's cmp line");
        assertTrue(cmpLines().get(1).contains(" push=1 out=UNCOVERED n=5 h=" + h + " why=pre-connect "),
                cmpLines().get(1));
    }

    /**
     * FAB-175-2 r2 (F1), one {@code day} line per midnight: the injected clock MOVES, 1:1 with
     * {@link System#nanoTime()}, and its first reading — the launcher's, when it schedules the first line — is
     * 999 µs before midnight. The task is scheduled to the nanosecond, so it wakes at or after midnight by that
     * clock: exactly ONE {@code last=0} line for that midnight, on the stream thread, and not one early wake. The next
     * target is a day later, so nothing more follows. r1 truncated the delay to whole milliseconds (0 here), woke
     * early, and re-fired at 0 ms until the clock passed midnight: a burst of lines.
     */
    @Test
    void aMidnightASubMillisecondFractionAwayGivesExactlyOneDayLine() {
        server = new OracleStreamTestServer().authorize(account.stakeAddress()).start();
        NanoClock clock = new NanoClock(Instant.parse("2026-11-02T00:00:00Z").minusNanos(999_000));
        launcher = new OracleStreamLauncher(account, network("preview"), present(), staker(), meters(), "true",
                server.uri().toString());
        launcher.useForTest(OracleStreamClient.Timings.PRODUCTION, clock);
        launcher.start();

        await(() -> !dayEvents().isEmpty(), WAIT, "the midnight day line");
        sleepMillis(500);
        List<ILoggingEvent> midnight = dayEvents();
        assertEquals(1, midnight.size(), messages(midnight).toString());
        assertTrue(midnight.getFirst().getFormattedMessage().endsWith(" last=0"), messages(midnight).toString());
        assertEquals(OracleStreamLauncher.THREAD_NAME, midnight.getFirst().getThreadName(), "on the stream thread");
        assertEquals(0, launcher.dayEarlyWakes(), "a task that woke before midnight");

        launcher.stop();
        List<String> lines = messages(dayEvents());
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(1).endsWith(" last=1"), lines.toString());
        assertEquals(List.of(), streamThreadsAfterAWhile());
        assertTrue(messages(streamLines()).stream().noneMatch(l -> l.contains(" task-error ")),
                messages(streamLines()).toString());
    }

    /**
     * FAB-175-2 r2 (F1): a task that wakes before its midnight by the injected clock waits again and writes
     * nothing. The clock stands still 500 µs before midnight, so the task keeps waking early: no line. Each wait is
     * the full 500 µs to the nanosecond, so it cannot wake more than once per 500 µs of real time — a delay
     * truncated to whole milliseconds (0 here) would re-fire at 0 ms, far more often than that, even on a busy host.
     * Once the clock reaches midnight, exactly one {@code last=0} line, from the stream thread, and the next target
     * is a day away. The delay comes from the injected clock: a fixed 24 h from the boot would never have woken.
     */
    @Test
    void aDayTaskThatWakesBeforeItsMidnightWaitsAgainAndWritesNothing() {
        server = new OracleStreamTestServer().authorize(account.stakeAddress()).start();
        OracleStreamClientLivenessTest.MutableClock clock = new OracleStreamClientLivenessTest.MutableClock(
                Instant.parse("2026-11-02T00:00:00Z").minusNanos(500_000));
        launcher = new OracleStreamLauncher(account, network("preview"), present(), staker(), meters(), "true",
                server.uri().toString());
        launcher.useForTest(OracleStreamClient.Timings.PRODUCTION, clock);
        long started = System.nanoTime();
        launcher.start();

        await(() -> launcher.dayEarlyWakes() >= 2, WAIT, "two early wakes");
        sleepMillis(300);
        long wakes = launcher.dayEarlyWakes();
        long bound = (System.nanoTime() - started) / 500_000 + 1;
        assertTrue(wakes <= bound, wakes + " early wakes in at most " + bound + " waits of 500 µs");
        assertEquals(List.of(), messages(dayEvents()), "no line before midnight");

        clock.advance(Duration.ofNanos(500_000));
        await(() -> !dayEvents().isEmpty(), WAIT, "the midnight day line");
        sleepMillis(1200);
        List<ILoggingEvent> midnight = dayEvents();
        assertEquals(1, midnight.size(), messages(midnight).toString());
        assertTrue(midnight.getFirst().getFormattedMessage().endsWith(" last=0"), messages(midnight).toString());
        assertEquals(OracleStreamLauncher.THREAD_NAME, midnight.getFirst().getThreadName(), "on the stream thread");

        launcher.stop();
        List<String> last = messages(dayEvents()).stream().filter(l -> l.endsWith(" last=1")).toList();
        assertEquals(1, last.size(), messages(dayEvents()).toString());
        assertEquals(List.of(), streamThreadsAfterAWhile());
        assertTrue(messages(streamLines()).stream().noneMatch(l -> l.contains(" task-error ")),
                messages(streamLines()).toString());
    }

    @Test
    void startReturnsAtOnceWhenTheHandshakeHangs() throws IOException {
        hanging = new OracleStreamTestServer.Hanging();
        launcher = new OracleStreamLauncher(account, network("preview"), present(), staker(), meters(), "true", hanging.uri().toString());
        long started = System.nanoTime();
        launcher.start();
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(elapsedMs < 500, "start() took " + elapsedMs + " ms");
        await(() -> hanging.accepted() >= 1, WAIT, "the connect attempt");
    }

    @Test
    void startRunsOnApplicationReadyAndStopOnShutdown() throws Exception {
        EventListener listener = OracleStreamLauncher.class.getMethod("start").getAnnotation(EventListener.class);
        assertNotNull(listener, "start() carries @EventListener");
        assertEquals(List.of(ApplicationReadyEvent.class), Arrays.asList(listener.value()));
        assertNotNull(OracleStreamLauncher.class.getMethod("stop").getAnnotation(PreDestroy.class),
                "stop() carries @PreDestroy");
    }

    @Test
    void stopLeavesNoStreamThreadAlive() {
        server = new OracleStreamTestServer().authorize(account.stakeAddress()).start();
        launcher = new OracleStreamLauncher(account, network("preview"), present(), staker(), meters(), "true", server.uri().toString());
        launcher.start();
        launcher.start();
        server.awaitSession(0, WAIT);
        await(() -> messages(streamLines()).stream().anyMatch(l -> l.contains("[oracle-stream] connected ")),
                WAIT, "the connected line");
        assertFalse(streamThreads().isEmpty(), "fixture: the stream's threads are running");

        launcher.stop();

        assertEquals(List.of(), streamThreadsAfterAWhile());
        assertEquals(1, server.sessions().size(), "a second start() opened nothing");
        assertTrue(messages(streamLines()).stream().anyMatch(l -> l.contains("[oracle-stream] closed ")
                && l.contains(" reason=shutdown up_s=")), messages(streamLines()).toString());
        assertDoesNotThrow(launcher::stop);
    }

    // ======================================================================================
    // Shipped configuration
    // ======================================================================================

    @Test
    void theShippedYamlPinsTheStreamKeys() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader().load(RESOURCE, new ClassPathResource(RESOURCE));
        PropertySource<?> base = documents.getFirst();
        PropertySource<?> preview = documents.stream()
                .filter(d -> "preview".equals(String.valueOf(d.getProperty("spring.config.activate.on-profile"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no document activates the preview profile"));

        assertEquals("${LOANS_ORACLE_STREAM_ENABLED:true}", String.valueOf(base.getProperty("loans.oracle.stream.enabled")));
        String baseUrl = String.valueOf(base.getProperty("loans.oracle.stream.url"));
        assertEquals("${LOANS_ORACLE_STREAM_URL:wss://25mxd4whnj.execute-api.eu-west-1.amazonaws.com/prod}", baseUrl);
        String previewUrl = String.valueOf(preview.getProperty("loans.oracle.stream.url"));
        assertEquals("${LOANS_ORACLE_STREAM_URL:wss://2tgda3mk4l.execute-api.eu-west-1.amazonaws.com/dev}", previewUrl);
        assertNull(preview.getProperty("loans.oracle.stream.enabled"), "preview must not override enabled");
        assertTrue(preview instanceof EnumerablePropertySource<?>, "fixture: preview must be enumerable");
        assertEquals(List.of("loans.oracle.stream.url"), Arrays.stream(
                        ((EnumerablePropertySource<?>) preview).getPropertyNames())
                .filter(n -> n.startsWith("loans.oracle.stream.")).toList());

        assertEquals(OracleStreamLauncher.MAINNET_HOST, URI.create(placeholderDefault(baseUrl)).getHost());
        assertEquals(OracleStreamLauncher.PREVIEW_HOST, URI.create(placeholderDefault(previewUrl)).getHost());

        // The constructor's own defaults: the url has none a profile could not reach (§4), and the flag's is ON.
        Constructor<?> constructor = OracleStreamLauncher.class.getConstructors()[0];
        assertEquals(1, OracleStreamLauncher.class.getConstructors().length);
        List<String> values = Arrays.stream(constructor.getParameters())
                .map(p -> p.getAnnotation(Value.class))
                .filter(java.util.Objects::nonNull)
                .map(Value::value)
                .toList();
        assertEquals(List.of("${loans.oracle.stream.enabled:true}", "${loans.oracle.stream.url:}"), values);
        assertTrue(Arrays.stream(constructor.getParameters()).map(Parameter::getType)
                .anyMatch(ObjectProvider.class::equals), "FluidOracleClient only through an ObjectProvider");
        assertFalse(Arrays.stream(constructor.getParameters()).map(Parameter::getType)
                .anyMatch(FluidOracleClient.class::equals), "never a hard FluidOracleClient dependency");
    }

    /**
     * {@code LOANS_ORACLE_STREAM_ENABLED} blank means ON: Docker Compose's {@code env_file} forwards
     * {@code LOANS_ORACLE_STREAM_ENABLED=} as an empty string, which beats the {@code :true} default. Resolved
     * through a real {@link SystemEnvironmentPropertySource} over the shipped file, then handed to the launcher.
     */
    @Test
    void aBlankLoansOracleStreamEnabledMeansOn() throws IOException {
        server = new OracleStreamTestServer().authorize(account.stakeAddress()).start();
        PropertySource<?> base = new YamlPropertySourceLoader().load(RESOURCE, new ClassPathResource(RESOURCE)).getFirst();

        StandardEnvironment blank = environment(base, Map.of("LOANS_ORACLE_STREAM_ENABLED", "",
                "LOANS_ORACLE_STREAM_URL", server.uri().toString()));
        String enabled = blank.resolvePlaceholders("${loans.oracle.stream.enabled:true}");
        String url = blank.resolvePlaceholders("${loans.oracle.stream.url:}");
        assertEquals("", enabled, "the blank env var reaches the property");
        assertEquals(server.uri().toString(), url, "LOANS_ORACLE_STREAM_URL binds loans.oracle.stream.url");

        launcher = new OracleStreamLauncher(account, network("preview"), present(), staker(), meters(), enabled, url);
        launcher.start();
        server.awaitSession(0, WAIT);
        await(() -> messages(streamLines()).stream().anyMatch(l -> l.contains("[oracle-stream] on ")), WAIT,
                "the on line");

        StandardEnvironment off = environment(base, Map.of("LOANS_ORACLE_STREAM_ENABLED", "false"));
        assertEquals("false", off.resolvePlaceholders("${loans.oracle.stream.enabled:true}"));
        StandardEnvironment unset = environment(base, Map.of());
        assertEquals("true", unset.resolvePlaceholders("${loans.oracle.stream.enabled:true}"));
        assertEquals("wss://25mxd4whnj.execute-api.eu-west-1.amazonaws.com/prod",
                unset.resolvePlaceholders("${loans.oracle.stream.url:}"));
    }

    // ======================================================================================
    // Helpers
    // ======================================================================================

    private static StandardEnvironment environment(PropertySource<?> base, Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
        environment.getPropertySources().addLast(base);
        return environment;
    }

    /** {@code ${NAME:default}} → {@code default}. */
    private static String placeholderDefault(String placeholder) {
        return placeholder.substring(placeholder.indexOf(':') + 1, placeholder.length() - 1);
    }

    /** A stake check that reads "no stake"; the launcher only ever reads it during a 403 hold. */
    private static StakerService staker() {
        return org.mockito.Mockito.mock(StakerService.class);
    }

    private static MeterRegistry meters() {
        return new SimpleMeterRegistry();
    }

    private static ObjectProvider<FluidOracleClient> present() {
        return present(new FluidOracleClient("http://unused.invalid"));
    }

    private static ObjectProvider<FluidOracleClient> present(FluidOracleClient client) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("fluidOracleClient", client);
        return factory.getBeanProvider(FluidOracleClient.class);
    }

    private static ObjectProvider<FluidOracleClient> absent() {
        return new StaticListableBeanFactory().getBeanProvider(FluidOracleClient.class);
    }

    private static AppConfig.Network network(String name) {
        AppConfig.Network network = new AppConfig.Network();
        network.setNetworkForTest(name);
        return network;
    }

    private List<ILoggingEvent> streamLines() {
        return List.copyOf(appender.list).stream()
                .filter(e -> e.getFormattedMessage().startsWith("[oracle-stream] "))
                .toList();
    }

    private List<String> cmpLines() {
        return messages(cmpEvents());
    }

    private List<ILoggingEvent> cmpEvents() {
        return streamLines().stream().filter(e -> e.getFormattedMessage().startsWith("[oracle-stream] cmp ")).toList();
    }

    private List<ILoggingEvent> dayEvents() {
        return streamLines().stream().filter(e -> e.getFormattedMessage().startsWith("[oracle-stream] day ")).toList();
    }

    private static List<String> messages(List<ILoggingEvent> events) {
        return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static List<String> streamThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(n -> n.startsWith("oracle-stream"))
                .sorted()
                .toList();
    }

    private static List<String> streamThreadsAfterAWhile() {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        List<String> alive = streamThreads();
        while (!alive.isEmpty() && System.nanoTime() < deadline) {
            sleepMillis(20);
            alive = streamThreads();
        }
        return alive;
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * A clock that moves 1:1 with {@link System#nanoTime()} from {@code start}, counted from its FIRST reading — so
     * the first reader sees exactly {@code start}, however long the set-up before it took.
     */
    private static final class NanoClock extends Clock {

        private final Instant start;
        private final AtomicReference<Long> origin = new AtomicReference<>();

        NanoClock(Instant start) {
            this.start = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            long now = System.nanoTime();
            origin.compareAndSet(null, now);
            return start.plusNanos(now - origin.get());
        }
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = OracleStreamLauncherTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("missing fixture " + path);
            }
            return new String(in.readAllBytes(), UTF_8);
        }
    }
}
