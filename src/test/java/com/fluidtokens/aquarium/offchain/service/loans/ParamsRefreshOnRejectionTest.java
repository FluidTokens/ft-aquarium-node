package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.backend.api.TransactionService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.EpochProtocolParamsSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FAB-134 B5a, through the constructors Spring actually calls. A submission the ledger rejects with
 * {@code PPViewHashesDontMatch} must make the next {@code getProtocolParams()} go to the provider — on
 * the wired submitter, not on a test seam that production never uses. The rejected result itself is
 * handed back untouched: nothing is retried and no outcome changes.
 */
class ParamsRefreshOnRejectionTest {

    private static final String REJECTION = "{\"message\":\"ConwayUtxowFailure (PPViewHashesDontMatch "
            + "(SJust (SafeHash \\\"ab\\\")) (SJust (SafeHash \\\"cd\\\")))\"}";

    private MovableClock clock;
    private CountingSupplier delegate;
    private EpochProtocolParamsSupplier supplier;
    private BFBackendService backend;
    private Result<String> rejected;

    @BeforeEach
    void setUp() throws Exception {
        clock = new MovableClock(LoanFixtures.converters().epoch()
                .beginningOfEpochToUTCTime(900).toInstant(ZoneOffset.UTC).plusSeconds(3600));
        delegate = new CountingSupplier();
        supplier = new EpochProtocolParamsSupplier(delegate, LoanFixtures.converters(), clock);
        assertEquals(1, delegate.calls);
        clock.now = clock.now.plusSeconds(120);

        rejected = Result.error(REJECTION);
        TransactionService transactions = mock(TransactionService.class);
        when(transactions.submitTransaction(any(byte[].class))).thenReturn(rejected);
        backend = mock(BFBackendService.class);
        when(backend.getTransactionService()).thenReturn(transactions);
    }

    @Test
    void theLiquidationExecutorsWiredSubmitterRefreshesOnAParamsRejection() throws Exception {
        var configuration = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30, BigInteger.valueOf(1_500_000), 200);
        @SuppressWarnings("unchecked")
        ObjectProvider<ConvertLiquidationRouter> convert = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<FluidOracleClient> oracles = mock(ObjectProvider.class);
        var executor = new LiquidationExecutor(configuration, null, null, null, null, null, null, null,
                convert, null, null, null, oracles, previewNetwork(), supplier, LoanFixtures.converters(),
                backend);

        var submitter = (LiquidationExecutor.TransactionSubmitter)
                ReflectionTestUtils.getField(executor, "submitter");
        assertSame(rejected, submitter.submit(new byte[]{1}), "the rejection must reach the caller untouched");

        supplier.getProtocolParams();
        assertEquals(2, delegate.calls,
                "a PPViewHashesDontMatch rejection on the Spring-wired submitter did not refresh the parameters");
    }

    @Test
    void theCompoundExecutorsWiredSubmitterRefreshesOnAParamsRejection() throws Exception {
        var executor = new CompoundExecutor(new AppConfig.CompoundConfiguration(false, 60L, BigInteger.ZERO),
                previewNetwork(), null, null, null, null, null, null, null,
                LoanFixtures.utxoSupplier(List.of()), LoanFixtures.converters(), supplier, backend);

        var submitter = (CompoundExecutor.TransactionSubmitter)
                ReflectionTestUtils.getField(executor, "submitter");
        assertSame(rejected, submitter.submit(new byte[]{1}), "the rejection must reach the caller untouched");

        supplier.getProtocolParams();
        assertEquals(2, delegate.calls,
                "a PPViewHashesDontMatch rejection on the Spring-wired submitter did not refresh the parameters");
    }

    private static AppConfig.Network previewNetwork() {
        return new AppConfig.Network() {
            @Override
            public String getNetwork() {
                return "preview";
            }
        };
    }

    private static final class CountingSupplier implements ProtocolParamsSupplier {
        int calls;

        @Override
        public ProtocolParams getProtocolParams() {
            calls++;
            return new ProtocolParams();
        }
    }

    private static final class MovableClock extends Clock {
        Instant now;

        MovableClock(Instant now) {
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
