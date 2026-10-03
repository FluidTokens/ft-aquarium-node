package com.fluidtokens.aquarium.offchain.service.wallet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.controller.Healthcheck;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.ParametersService;
import com.fluidtokens.aquarium.offchain.service.ScheduledTransactionService;
import com.fluidtokens.aquarium.offchain.service.StakerService;
import com.fluidtokens.aquarium.offchain.service.TankContractService;
import com.fluidtokens.aquarium.offchain.service.loans.CompoundCandidateScanner;
import com.fluidtokens.aquarium.offchain.service.loans.CompoundExecutor;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidationCandidateScanner;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidationExecutor;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

/**
 * ⛔ FAB-134: every processor that spends from the wallet skips its cycle until {@link WalletReadiness}
 * is open — counted on the first collaborator each cycle reaches past the gate — and the container must
 * hand each of them the shared readiness bean (setter injection, REQUIRED).
 */
class WalletReadinessGateTest {

    /** A synced node, so the syncing check is never the thing that skips. */
    private static BlockEventListener synced() {
        BlockEventListener listener = new BlockEventListener(null);
        listener.getIsSyncing().set(false);
        return listener;
    }

    private static int calls(Object mock) {
        return mockingDetails(mock).getInvocations().size();
    }

    private static WalletReadiness closed() {
        return new WalletReadiness();
    }

    private static WalletReadiness open() {
        WalletReadiness readiness = new WalletReadiness();
        readiness.markDone("nothing to rebalance");
        return readiness;
    }

    /**
     * Runs one cycle with the gate closed (zero collaborator calls), then with it open and with no
     * readiness at all (a direct test construction means "ready"), each a positive control.
     */
    private static void assertGated(String name, Object counted, Consumer<WalletReadiness> setter, Runnable cycle) {
        setter.accept(closed());
        cycle.run();
        assertEquals(0, calls(counted), name + " ran its cycle while the wallet sweep was incomplete");

        setter.accept(open());
        cycle.run();
        assertEquals(1, calls(counted), name + " must run once the wallet is ready (positive control)");

        setter.accept(null);
        cycle.run();
        assertEquals(2, calls(counted), name + " with no readiness bean (direct construction) runs ungated");
    }

    @Test
    void theTankProcessorSkipsItsCycleUntilTheWalletIsReady() {
        StakerService staker = mock(StakerService.class);
        var processor = new ScheduledTransactionService(
                new AppConfig.Network(), mock(AppConfig.AquariumConfiguration.class), mock(Account.class),
                mock(QuickTxBuilder.class), mock(BFBackendService.class), LoanFixtures.protocolParams(),
                mock(UtxoRepository.class), staker, LoanFixtures.converters(), mock(ParametersService.class),
                mock(TankContractService.class), mock(AppUtxoService.class), synced());

        assertGated("ScheduledTransactionService", staker, processor::setWalletReadiness, processor::processPayments);
    }

    @Test
    void liquidationSkipsItsCycleUntilTheWalletIsReady() {
        var configuration = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30, BigInteger.ZERO, 200, 30);
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        LiquidationCandidateScanner scanner = mock(LiquidationCandidateScanner.class);
        var executor = new LiquidationExecutor(configuration, synced(), mock(AppUtxoService.class),
                mock(Account.class), scanner, null, null, null, null, null, null, null, network,
                LoanFixtures.protocolParams(), LoanFixtures.converters(),
                bytes -> {
                    throw new AssertionError("nothing may be submitted here");
                });

        assertGated("LiquidationExecutor", scanner, executor::setWalletReadiness, executor::runCycle);
    }

    @Test
    void compoundSkipsItsCycleUntilTheWalletIsReady() {
        CompoundCandidateScanner scanner = mock(CompoundCandidateScanner.class);
        var executor = new CompoundExecutor(new AppConfig.CompoundConfiguration(false, 60L, BigInteger.ZERO),
                new AppConfig.Network(), synced(), mock(AppUtxoService.class), mock(Account.class), scanner,
                null, null, null, null, LoanFixtures.converters(),
                bytes -> {
                    throw new AssertionError("nothing may be submitted here");
                });

        assertGated("CompoundExecutor", scanner, executor::setWalletReadiness, executor::runCycle);
    }

    /**
     * The container must hand every reader the shared bean: an optional or missing injection would leave
     * the field null — which a direct construction reads as "ready" — and the processor would spend from
     * a partly-indexed wallet. Exercising those containers is too expensive, so the annotations are pinned.
     */
    @Test
    void everyReaderHasTheReadinessInjectedAndRequired() throws Exception {
        assertTrue(WalletReadiness.class.isAnnotationPresent(Component.class), "WalletReadiness must be a @Component");
        for (Class<?> type : List.of(ScheduledTransactionService.class, LiquidationExecutor.class,
                CompoundExecutor.class, Healthcheck.class)) {
            var setter = type.getMethod("setWalletReadiness", WalletReadiness.class);
            Autowired autowired = setter.getAnnotation(Autowired.class);
            assertNotNull(autowired, type.getSimpleName() + ".setWalletReadiness is not @Autowired");
            assertTrue(autowired.required(), type.getSimpleName() + ".setWalletReadiness must be REQUIRED");
        }
    }

    /** The sweep itself: a component, one constructor Spring can pick, and a commit-event listener. */
    @Test
    void theSweepIsACommitEventListenerComponentWithOneAutowiredConstructor() throws Exception {
        assertTrue(WalletSweepService.class.isAnnotationPresent(Component.class));
        Constructor<?>[] constructors = WalletSweepService.class.getDeclaredConstructors();
        long annotated = Arrays.stream(constructors).filter(c -> c.isAnnotationPresent(Autowired.class)).count();
        assertEquals(1L, annotated, "exactly one constructor must be @Autowired, or the context cannot start");
        var listener = WalletSweepService.class.getMethod("onCommitEvent", CommitEvent.class);
        assertNotNull(listener.getAnnotation(EventListener.class), "onCommitEvent must be an @EventListener");
        assertTrue(Arrays.stream(WalletSweepService.class.getMethods())
                        .noneMatch(m -> m.isAnnotationPresent(Scheduled.class)),
                "the one-shot is driven by commit events only: no @Scheduled poll");
    }
}
