package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-136: the syncing flag is the AGE of the last block this node applied — {@code now − blockTime(slot)}
 * of the last {@link CommitEvent} — and the node is syncing exactly when that age exceeds
 * {@code aquarium.syncing-threshold-minutes}. It gates all three spending processors, the
 * healthcheck verdict and the readiness wallet, so its boundary, its default and its binding are
 * each pinned here.
 */
class BlockEventListenerTest {

    private static final CardanoConverters MAINNET =
            ClasspathConversionsFactory.createConverters(NetworkType.MAINNET);

    /** A Shelley-era mainnet slot (the node's own sync start). */
    private static final long SLOT = 154_984_561L;

    private static Instant blockTimeOf(long slot) {
        return MAINNET.slot().slotToTime(slot).toInstant(ZoneOffset.UTC);
    }

    /** A clock that reads exactly {@code drift} after the block time of {@link #SLOT}. */
    private static Clock clockAt(Duration drift) {
        return Clock.fixed(blockTimeOf(SLOT).plus(drift), ZoneOffset.UTC);
    }

    private static BlockEventListener listener(long thresholdMinutes, Duration driftOfSlot) {
        return new BlockEventListener(MAINNET, thresholdMinutes, clockAt(driftOfSlot));
    }

    private static CommitEvent<?> eventAt(long slot) {
        CommitEvent<?> event = Mockito.mock(CommitEvent.class, Answers.RETURNS_DEEP_STUBS);
        Mockito.when(event.getMetadata().getSlot()).thenReturn(slot);
        return event;
    }

    private static boolean syncingAfter(long thresholdMinutes, Duration drift) {
        BlockEventListener listener = listener(thresholdMinutes, drift);
        listener.processBlock(eventAt(SLOT));
        return listener.getIsSyncing().get();
    }

    @Test
    void aFreshBeanIsSyncing() {
        assertTrue(listener(10, Duration.ZERO).getIsSyncing().get(),
                "before the first block the node must not process anything");
        assertTrue(new BlockEventListener(null).getIsSyncing().get());
        assertEquals(0L, listener(10, Duration.ZERO).getLastAppliedSlot().get());
    }

    @Test
    void syncingIsADriftStrictlyGreaterThanTheThreshold() {
        assertFalse(syncingAfter(10, Duration.ofMinutes(9).plusSeconds(59)), "9m59s is caught up");
        assertFalse(syncingAfter(10, Duration.ofMinutes(10)), "exactly N minutes is NOT syncing (drift > N)");
        assertTrue(syncingAfter(10, Duration.ofMinutes(10).plusSeconds(1)), "10m01s is syncing");
    }

    @Test
    void theThresholdIsHonouredNotHardCoded() {
        assertTrue(syncingAfter(3, Duration.ofMinutes(4)), "with N = 3 a 4-minute-old block is syncing");
        assertFalse(syncingAfter(3, Duration.ofMinutes(2)), "with N = 3 a 2-minute-old block is caught up");
    }

    @Test
    void aBlockFromTheFutureIsNotSyncing() {
        assertFalse(syncingAfter(10, Duration.ofMinutes(-5)), "a negative drift (clock behind the block) is caught up");
    }

    @Test
    void theFlagFollowsTheDriftBothWaysAndIsNeverLatched() {
        // The clock is fixed at SLOT's block time + 30 min; events of different ages arrive.
        BlockEventListener listener = listener(10, Duration.ofMinutes(30));
        long thirtyMinutesOld = SLOT;
        long oneMinuteOld = SLOT + 29 * 60;

        listener.processBlock(eventAt(thirtyMinutesOld));
        assertTrue(listener.getIsSyncing().get());
        assertEquals(thirtyMinutesOld, listener.getLastAppliedSlot().get());

        listener.processBlock(eventAt(oneMinuteOld));
        assertFalse(listener.getIsSyncing().get());
        assertEquals(oneMinuteOld, listener.getLastAppliedSlot().get());

        listener.processBlock(eventAt(thirtyMinutesOld));
        assertTrue(listener.getIsSyncing().get(), "an old block after a fresh one is syncing again — no latch");
        assertEquals(thirtyMinutesOld, listener.getLastAppliedSlot().get());
    }

    @Test
    void lastAppliedSlotIsTheEventsSlotAfterEveryEvent() {
        BlockEventListener listener = listener(10, Duration.ZERO);
        for (long slot : List.of(SLOT, SLOT + 20, SLOT + 41, SLOT - 600)) {
            listener.processBlock(eventAt(slot));
            assertEquals(slot, listener.getLastAppliedSlot().get());
        }
    }

    @Test
    void anEventWithoutMetadataNeverReachesYaciAsAnExceptionAndChangesNothing() {
        BlockEventListener listener = listener(10, Duration.ZERO);
        listener.processBlock(eventAt(SLOT));
        assertFalse(listener.getIsSyncing().get());

        CommitEvent<?> noMetadata = Mockito.mock(CommitEvent.class);
        Mockito.when(noMetadata.getMetadata()).thenReturn(null);
        assertDoesNotThrow(() -> listener.processBlock(noMetadata));
        assertFalse(listener.getIsSyncing().get());
        assertEquals(SLOT, listener.getLastAppliedSlot().get());

        CommitEvent<?> throwing = Mockito.mock(CommitEvent.class);
        Mockito.when(throwing.getMetadata()).thenThrow(new IllegalStateException("boom"));
        assertDoesNotThrow(() -> listener.processBlock(throwing));
        assertFalse(listener.getIsSyncing().get());
        assertEquals(SLOT, listener.getLastAppliedSlot().get());

        BlockEventListener fresh = listener(10, Duration.ZERO);
        assertDoesNotThrow(() -> fresh.processBlock(noMetadata));
        assertTrue(fresh.getIsSyncing().get(), "a broken event must not release a fresh bean");
        assertEquals(0L, fresh.getLastAppliedSlot().get());
    }

    @Test
    void aNonPositiveThresholdFailsConstructionNamingTheProperty() {
        for (long bad : new long[]{0, -1}) {
            var e = assertThrows(IllegalArgumentException.class,
                    () -> new BlockEventListener(MAINNET, bad, Clock.systemUTC()));
            assertTrue(e.getMessage().contains("aquarium.syncing-threshold-minutes"), e.getMessage());
        }
    }

    // ---- binding: the SHIPPED base document, not a fixture -------------------------------------

    private static StandardEnvironment baseDocument(Map<String, Object> envVars) throws Exception {
        List<PropertySource<?>> docs = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml"));
        var env = new StandardEnvironment();
        // Hermetic: the developer's own environment must not decide this test.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addLast(docs.get(0));
        if (!envVars.isEmpty()) {
            env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, envVars));
        }
        return env;
    }

    @Test
    void theBaseDocumentShipsTenMinutes() throws Exception {
        assertEquals(10L, baseDocument(Map.of()).getProperty("aquarium.syncing-threshold-minutes", Long.class));
    }

    @Test
    void theEnvVarOverridesTheShippedDefault() throws Exception {
        assertEquals(7L, baseDocument(Map.of("AQUARIUM_SYNCING_THRESHOLD_MINUTES", "7"))
                .getProperty("aquarium.syncing-threshold-minutes", Long.class));
    }

    @Test
    void theClassConstantIsTheShippedDefault() throws Exception {
        assertEquals(baseDocument(Map.of()).getProperty("aquarium.syncing-threshold-minutes", Long.class),
                BlockEventListener.DEFAULT_SYNCING_THRESHOLD_MINUTES);
    }

    @Test
    void theProductionConstructorCarriesNoInlineDefault() throws Exception {
        var ctor = BlockEventListener.class.getDeclaredConstructor(CardanoConverters.class, long.class);
        Value value = ctor.getParameters()[1].getAnnotation(Value.class);
        assertEquals("${aquarium.syncing-threshold-minutes}", value.value(),
                "the YAML key is the only default; an inline one would survive its deletion");
    }
}
