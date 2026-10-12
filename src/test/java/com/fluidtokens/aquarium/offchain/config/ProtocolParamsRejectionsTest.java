package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.Result;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Only the two ledger rejections that name the protocol parameters refresh them — and only on the
 * per-epoch supplier. Every other rejection, and every success, leaves the cache alone.
 */
class ProtocolParamsRejectionsTest {

    private static final CardanoConverters CONVERTERS =
            ClasspathConversionsFactory.createConverters(NetworkType.PREVIEW);

    /** Roughly what Blockfrost returns for a phase-1 rejection, with the marker in the middle. */
    private static final String PP_VIEW = "{\"error\":\"Bad Request\",\"message\":\"{\\\"contents\\\":"
            + "{\\\"era\\\":\\\"ShelleyBasedEraConway\\\",\\\"error\\\":[\\\"ConwayUtxowFailure "
            + "(PPViewHashesDontMatch (SJust (SafeHash \\\\\\\"ab\\\\\\\")) (SJust (SafeHash \\\\\\\"cd\\\\\\\")))\\\"]}}\"}";
    private static final String FEE = "{\"message\":\"ConwayUtxowFailure (UtxoFailure (FeeTooSmallUTxO "
            + "(Mismatch {mismatchSupplied = Coin 290451, mismatchExpected = Coin 314662})))\"}";
    private static final String BAD_INPUTS = "{\"message\":\"ConwayUtxowFailure (UtxoFailure "
            + "(BadInputsUTxO (fromList [TxIn (TxId {unTxId = SafeHash \\\"00\\\"}) (TxIx 0)])))\"}";

    private EpochProtocolParamsSupplierTest.MutableClock clock;
    private EpochProtocolParamsSupplierTest.CountingSupplier delegate;
    private EpochProtocolParamsSupplier supplier;

    @BeforeEach
    void setUp() {
        clock = new EpochProtocolParamsSupplierTest.MutableClock(CONVERTERS.epoch()
                .beginningOfEpochToUTCTime(900).toInstant(ZoneOffset.UTC).plus(Duration.ofHours(1)));
        delegate = new EpochProtocolParamsSupplierTest.CountingSupplier();
        supplier = new EpochProtocolParamsSupplier(delegate, CONVERTERS, clock);
        // Past the 60 s guard, so an invalidation that is called takes effect.
        clock.set(clock.instant().plusSeconds(120));
    }

    /** One call after the result went through; 2 means the cache was invalidated, 1 means it was not. */
    private int delegateCallsAfter(Result<?> result) {
        ProtocolParamsRejections.refreshIfParamsRejected(supplier, result);
        supplier.getProtocolParams();
        return delegate.calls;
    }

    @Test
    void ppViewHashesDontMatchRefreshes() {
        assertTrue(ProtocolParamsRejections.paramsRejected(PP_VIEW));
        assertEquals(2, delegateCallsAfter(Result.error(PP_VIEW)));
    }

    @Test
    void feeTooSmallUtxoRefreshes() {
        assertTrue(ProtocolParamsRejections.paramsRejected(FEE));
        assertEquals(2, delegateCallsAfter(Result.error(FEE)));
    }

    @Test
    void anUnrelatedRejectionDoesNotRefresh() {
        assertFalse(ProtocolParamsRejections.paramsRejected(BAD_INPUTS));
        assertEquals(1, delegateCallsAfter(Result.error(BAD_INPUTS)),
                "BadInputsUTxO says nothing about the parameters and must not refresh them");
    }

    @Test
    void aSuccessfulResultDoesNotRefreshEvenIfItsTextHasAMarker() {
        assertEquals(1, delegateCallsAfter(Result.success(PP_VIEW).withValue("txhash")));
    }

    @Test
    void aNullResultDoesNotRefreshOrThrow() {
        assertDoesNotThrow(() -> ProtocolParamsRejections.refreshIfParamsRejected(supplier, null));
        assertEquals(1, delegateCallsAfter(null));
    }

    @Test
    void aNullResponseIsNoMatch() {
        assertFalse(ProtocolParamsRejections.paramsRejected(null));
        assertEquals(1, delegateCallsAfter(Result.error(null)));
    }

    @Test
    void aSupplierThatIsNotThePerEpochOneIsNeverTouched() {
        ProtocolParamsSupplier other = mock(ProtocolParamsSupplier.class);
        assertDoesNotThrow(() -> ProtocolParamsRejections.refreshIfParamsRejected(other, Result.error(PP_VIEW)));
        verifyNoInteractions(other);
        assertDoesNotThrow(() -> ProtocolParamsRejections.refreshIfParamsRejected(null, Result.error(PP_VIEW)));
    }
}
