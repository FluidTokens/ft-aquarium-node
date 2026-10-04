package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Protocol parameters are fetched once per epoch window — and every value served is one the delegate
 * actually returned.
 *
 * <p>The epoch arithmetic runs on the real preview genesis ({@link ClasspathConversionsFactory}), so
 * the boundary the tests step across is the one the production bean computes, not a hand-picked
 * number. The delegate is a counting fake: what is asserted is how many provider calls were made.
 */
class EpochProtocolParamsSupplierTest {

    private static final CardanoConverters CONVERTERS =
            ClasspathConversionsFactory.createConverters(NetworkType.PREVIEW);

    /** An arbitrary preview epoch; the tests start one hour into it. */
    private static final int EPOCH = 900;

    private Instant epochStart;
    private Instant nextEpochStart;
    private MutableClock clock;
    private CountingSupplier delegate;

    @BeforeEach
    void setUp() {
        epochStart = CONVERTERS.epoch().beginningOfEpochToUTCTime(EPOCH).toInstant(ZoneOffset.UTC);
        nextEpochStart = CONVERTERS.epoch().beginningOfEpochToUTCTime(EPOCH + 1).toInstant(ZoneOffset.UTC);
        clock = new MutableClock(epochStart.plus(Duration.ofHours(1)));
        delegate = new CountingSupplier();
    }

    @Test
    void twoCallsInTheSameEpochWindowMakeOneDelegateCall() {
        var supplier = new EpochProtocolParamsSupplier(delegate, CONVERTERS, clock);
        assertEquals(1, delegate.calls, "the eager load at construction is the window's one fetch");

        ProtocolParams first = supplier.getProtocolParams();
        clock.set(nextEpochStart.minusSeconds(1));
        ProtocolParams second = supplier.getProtocolParams();

        assertEquals(1, delegate.calls, "a second call inside the same epoch window went to the provider");
        assertSame(delegate.lastReturned, first);
        assertSame(first, second);
    }

    @Test
    void aCallAfterTheBoundaryPlusTenMinutesFetchesAgain() {
        var supplier = new EpochProtocolParamsSupplier(delegate, CONVERTERS, clock);
        ProtocolParams before = supplier.getProtocolParams();

        clock.set(nextEpochStart.plus(Duration.ofMinutes(10)).plusSeconds(1));
        ProtocolParams after = supplier.getProtocolParams();

        assertEquals(2, delegate.calls, "the new epoch's parameters were never fetched");
        assertSame(delegate.lastReturned, after, "the value served must be the one just fetched");
        assertNotSame(before, after);
    }

    @Test
    void aCallAfterTheBoundaryButBeforeTenMinutesMakesNoNewCall() {
        var supplier = new EpochProtocolParamsSupplier(delegate, CONVERTERS, clock);

        clock.set(nextEpochStart.plus(Duration.ofMinutes(5)));
        supplier.getProtocolParams();
        assertEquals(1, delegate.calls, "fetched inside the settling delay after the boundary");

        clock.set(nextEpochStart.plus(Duration.ofMinutes(11)));
        supplier.getProtocolParams();
        assertEquals(2, delegate.calls, "past the settling delay the fetch is due");
    }

    @Test
    void aFailingRefreshWithAValueCachedServesTheCachedValueAndBacksOff() {
        var supplier = new EpochProtocolParamsSupplier(delegate, CONVERTERS, clock);
        ProtocolParams cached = supplier.getProtocolParams();

        delegate.failing = true;
        Instant due = nextEpochStart.plus(Duration.ofMinutes(11));
        clock.set(due);
        assertSame(cached, supplier.getProtocolParams(),
                "a failed refresh must keep serving the last value the chain returned");
        assertEquals(2, delegate.calls);

        clock.set(due.plusSeconds(30));
        assertSame(cached, supplier.getProtocolParams());
        assertEquals(2, delegate.calls, "retried within 60 s of a failure");

        clock.set(due.plusSeconds(61));
        assertSame(cached, supplier.getProtocolParams());
        assertEquals(3, delegate.calls, "never retried after the back-off elapsed");

        delegate.failing = false;
        clock.set(due.plusSeconds(122));
        ProtocolParams recovered = supplier.getProtocolParams();
        assertEquals(4, delegate.calls);
        assertSame(delegate.lastReturned, recovered, "a recovered provider's value was not taken");
    }

    @Test
    void aFailingDelegateWithNothingCachedThrows() {
        delegate.failing = true;
        var supplier = new EpochProtocolParamsSupplier(delegate, CONVERTERS, clock);

        RuntimeException thrown = assertThrows(RuntimeException.class, supplier::getProtocolParams,
                "with nothing cached there is no chain value to serve, and none may be invented");
        assertSame(CountingSupplier.FAILURE, thrown, "the delegate's own failure must be rethrown");
        // And it keeps asking: no back-off applies while there is nothing to serve.
        assertThrows(RuntimeException.class, supplier::getProtocolParams);
        assertEquals(3, delegate.calls);
    }

    @Test
    void anEagerLoadFailureAtConstructionIsSoft() {
        delegate.failing = true;
        var supplier = new EpochProtocolParamsSupplier(delegate, CONVERTERS, clock);
        assertEquals(1, delegate.calls, "construction did not attempt the eager load");

        delegate.failing = false;
        ProtocolParams params = supplier.getProtocolParams();
        assertEquals(2, delegate.calls, "the first caller after a failed boot load must fetch");
        assertSame(delegate.lastReturned, params);
    }

    @Test
    void aFreshSupplierBuiltInsideTheSettlingWindowFetchesAgainWhenTheWindowEnds() {
        clock.set(epochStart.plus(Duration.ofMinutes(5)));
        var supplier = new EpochProtocolParamsSupplier(delegate, CONVERTERS, clock);
        assertEquals(1, delegate.calls, "the eager load at construction is the first fetch");

        clock.set(epochStart.plus(Duration.ofMinutes(9)));
        ProtocolParams inside = supplier.getProtocolParams();
        assertEquals(1, delegate.calls, "fetched again while the settling window is still open");

        clock.set(epochStart.plus(Duration.ofMinutes(11)));
        ProtocolParams settled = supplier.getProtocolParams();
        assertEquals(2, delegate.calls,
                "a value read inside the settling window was kept past it, for the whole epoch");
        assertSame(delegate.lastReturned, settled, "the value served must be the settled one just fetched");
        assertNotSame(inside, settled);
    }

    @Test
    void aNullDelegateWithNothingCachedThrows() {
        delegate.returningNull = true;
        var supplier = new EpochProtocolParamsSupplier(delegate, CONVERTERS, clock);
        assertEquals(1, delegate.calls, "construction did not attempt the eager load");

        assertThrows(IllegalStateException.class, supplier::getProtocolParams,
                "a null from the provider is not a chain value and must never be served");
        assertEquals(2, delegate.calls);
    }

    @Test
    void aNullDelegateWithAValueCachedServesTheCachedValueAndBacksOff() {
        var supplier = new EpochProtocolParamsSupplier(delegate, CONVERTERS, clock);
        ProtocolParams cached = supplier.getProtocolParams();

        delegate.returningNull = true;
        Instant due = nextEpochStart.plus(Duration.ofMinutes(11));
        clock.set(due);
        assertSame(cached, supplier.getProtocolParams(),
                "a null refresh must keep serving the last value the chain returned");
        assertEquals(2, delegate.calls);

        clock.set(due.plusSeconds(59));
        assertSame(cached, supplier.getProtocolParams());
        assertEquals(2, delegate.calls, "retried within 60 s of a null answer");

        clock.set(due.plusSeconds(61));
        assertSame(cached, supplier.getProtocolParams());
        assertEquals(3, delegate.calls, "never retried after the back-off elapsed");
    }

    // ---- invalidate(reason): a ledger rejection naming the parameters (FAB-134 B5a) ----

    @Test
    void invalidateAfterTheGuardWindowMakesTheNextCallFetchOnce() {
        var supplier = new EpochProtocolParamsSupplier(delegate, CONVERTERS, clock);
        ProtocolParams before = supplier.getProtocolParams();

        clock.set(clock.instant().plus(EpochProtocolParamsSupplier.RETRY_AFTER_FAILURE));
        supplier.invalidate("PPViewHashesDontMatch (test)");
        ProtocolParams after = supplier.getProtocolParams();
        assertEquals(2, delegate.calls, "an invalidation past the 60 s guard must make the next call fetch");
        assertSame(delegate.lastReturned, after, "the value served must be the one just fetched");
        assertNotSame(before, after);

        supplier.getProtocolParams();
        assertEquals(2, delegate.calls, "one invalidation is one fetch, not a disabled cache");
    }

    @Test
    void invalidateInsideTheGuardWindowFetchesNothing() {
        var supplier = new EpochProtocolParamsSupplier(delegate, CONVERTERS, clock);
        ProtocolParams cached = supplier.getProtocolParams();

        clock.set(clock.instant().plus(EpochProtocolParamsSupplier.RETRY_AFTER_FAILURE).minusSeconds(1));
        supplier.invalidate("FeeTooSmallUTxO (test)");
        assertSame(cached, supplier.getProtocolParams());
        assertEquals(1, delegate.calls, "a refresh within 60 s of the last fetch: the guard did not hold");
    }

    @Test
    void invalidateWithNothingCachedIsANoOp() {
        delegate.failing = true;
        var supplier = new EpochProtocolParamsSupplier(delegate, CONVERTERS, clock);
        assertEquals(1, delegate.calls);

        clock.set(clock.instant().plusSeconds(120));
        supplier.invalidate("PPViewHashesDontMatch (test)");
        assertEquals(1, delegate.calls, "invalidate must never fetch by itself");

        delegate.failing = false;
        ProtocolParams params = supplier.getProtocolParams();
        assertEquals(2, delegate.calls, "with nothing cached the next caller fetches, as it always did");
        assertSame(delegate.lastReturned, params);
    }

    @Test
    void aFailedRefetchAfterInvalidateStillServesTheLastChainValue() {
        var supplier = new EpochProtocolParamsSupplier(delegate, CONVERTERS, clock);
        ProtocolParams cached = supplier.getProtocolParams();

        clock.set(clock.instant().plusSeconds(61));
        supplier.invalidate("PPViewHashesDontMatch (test)");
        delegate.failing = true;
        assertSame(cached, supplier.getProtocolParams(),
                "a failed refetch after an invalidation must keep serving the last value the chain returned");
        assertEquals(2, delegate.calls, "the invalidation did not trigger a refetch");

        // A second rejection right after the failed attempt must not hammer the provider.
        supplier.invalidate("PPViewHashesDontMatch (test, again)");
        assertSame(cached, supplier.getProtocolParams());
        assertEquals(2, delegate.calls, "a second refresh attempt within 60 s of the first");
    }

    /** Counts calls; each success returns a fresh instance so "which value was served" is checkable. */
    static final class CountingSupplier implements ProtocolParamsSupplier {
        static final RuntimeException FAILURE = new RuntimeException("provider down (test)");
        int calls;
        boolean failing;
        boolean returningNull;
        ProtocolParams lastReturned;

        @Override
        public ProtocolParams getProtocolParams() {
            calls++;
            if (failing) {
                throw FAILURE;
            }
            if (returningNull) {
                return null;
            }
            lastReturned = new ProtocolParams();
            return lastReturned;
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
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
            return now;
        }
    }
}
