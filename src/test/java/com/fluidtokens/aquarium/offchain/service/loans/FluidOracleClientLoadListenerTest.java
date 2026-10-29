package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-175: {@link FluidOracleClient.LoadListener} — the read-only hook the oracle stream's comparison hears the
 * production poller through.
 *
 * <p>A listener is told exactly what a load left held ({@code entries()}) and the very instant stored as
 * {@code lastRefresh()}, on the loading thread, after all of it is set. One that throws is named by its class in one
 * WARN, cannot stop the listeners after it and cannot break the load. A refresh that fails before any load tells no
 * one. With no listener, {@code FluidOracleClientLoadPinTest} pins that the load is what it was.
 */
class FluidOracleClientLoadListenerTest {

    private static final String PUSH_TIME_API = "/loans-v4/oracle-ws/preview-api-20261029T130525Z.json";
    private static final String EARLIER_API = "/loans-v4/oracle-ws/preview-api-20261029T130505Z.json";
    private static final String MAINNET = "/loans-v4/mainnet-oracle-registry-2026-10-01.json";

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

    private record Heard(Set<OracleEntry> entries, int size, Instant at, String thread) {
    }

    @Test
    void aListenerHearsExactlyWhatEachLoadLeftHeldOnTheLoadingThread() throws Exception {
        var client = new FluidOracleClient("http://unused.invalid");
        List<Heard> heard = new CopyOnWriteArrayList<>();
        client.addLoadListener((entries, at) -> heard.add(
                new Heard(Set.copyOf(entries), entries.size(), at, Thread.currentThread().getName())));

        List<String> loaded = new ArrayList<>();
        List<Instant> refreshes = new ArrayList<>();
        List<Set<OracleEntry>> held = new ArrayList<>();
        for (String fixture : List.of(EARLIER_API, PUSH_TIME_API, MAINNET)) {
            JsonNode payload = FluidOracleClientLoadPinTest.resource(fixture);
            client.load(payload);
            loaded.add(fixture);
            refreshes.add(client.lastRefresh());
            held.add(Set.copyOf(client.entries()));
            assertEquals(loaded.size(), heard.size(), "told once per load: " + fixture);
            Heard last = heard.getLast();
            assertEquals(client.entries().size(), last.size(), fixture);
            assertEquals(Set.copyOf(client.entries()), last.entries(), "exactly entries(): " + fixture);
            assertSame(client.lastRefresh(), last.at(), "the very instant stored as lastRefresh(): " + fixture);
            assertEquals(Thread.currentThread().getName(), last.thread(), "on the loading thread");
        }
        assertEquals(35, heard.getLast().size(), "fixture: every oracle of the two-version registry, not one per token");
        assertNotEquals(held.get(0), held.get(1), "fixture: the two preview snapshots differ");
        assertNotEquals(Instant.EPOCH, refreshes.getFirst());
    }

    @Test
    void theEntriesAListenerReceivesCannotBeChanged() throws Exception {
        var client = new FluidOracleClient("http://unused.invalid");
        List<Collection<OracleEntry>> received = new ArrayList<>();
        client.addLoadListener((entries, at) -> received.add(entries));
        client.load(FluidOracleClientLoadPinTest.resource(PUSH_TIME_API));
        Set<OracleEntry> before = Set.copyOf(client.entries());

        Collection<OracleEntry> view = received.getFirst();
        assertThrows(UnsupportedOperationException.class, view::clear);
        assertThrows(UnsupportedOperationException.class, () -> view.remove(view.iterator().next()));
        assertEquals(before, Set.copyOf(client.entries()));
    }

    @Test
    void aThrowingListenerIsNamedOnceAndNeitherStopsTheNextNorBreaksTheLoad() throws Exception {
        var client = new FluidOracleClient("http://unused.invalid");
        List<String> order = new CopyOnWriteArrayList<>();
        List<Set<OracleEntry>> seenByThrower = new ArrayList<>();
        client.addLoadListener((entries, at) -> {
            order.add("first");
            seenByThrower.add(Set.copyOf(client.entries()));
            throw new IllegalStateException("secret-ish detail that must never reach the log");
        });
        client.addLoadListener((entries, at) -> order.add("second"));
        appender.list.clear();

        int priced = assertDoesNotThrow(() -> client.load(FluidOracleClientLoadPinTest.resource(PUSH_TIME_API)));

        assertEquals(5, priced, "load() returned normally with its count");
        assertEquals(List.of("first", "second"), order, "registration order, and the one after a throw still ran");
        assertEquals(5, client.entries().size(), "production state is set");
        assertNotEquals(Instant.EPOCH, client.lastRefresh(), "the refresh time is set");
        assertEquals(Set.copyOf(client.entries()), seenByThrower.getFirst(),
                "all state was already set when the listener ran");

        List<String> listenerWarns = appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("listener"))
                .toList();
        assertEquals(List.of("oracle load listener failed (IllegalStateException)"), listenerWarns);
        assertFalse(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("secret-ish")),
                "the exception's message never reaches the log");
        assertTrue(appender.list.stream().allMatch(e -> e.getThrowableProxy() == null), "no stack trace either");
    }

    @Test
    void noListenerIsToldWhenTheRefreshFailsBeforeAnyLoad() {
        var client = new FluidOracleClient("http://unused.invalid");
        List<Instant> told = new CopyOnWriteArrayList<>();
        client.addLoadListener((entries, at) -> told.add(at));

        assertDoesNotThrow(client::refresh);

        assertEquals(List.of(), told);
        assertEquals(Instant.EPOCH, client.lastRefresh(), "fixture: the refresh did fail");
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().startsWith("could not refresh the FluidTokens oracle")),
                "fixture: the failure is the refresh's own");
    }
}
