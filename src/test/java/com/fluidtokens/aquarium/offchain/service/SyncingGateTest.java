package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.controller.Healthcheck;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-136: with the wallet sweep and its {@code walletReady} gate gone, the syncing flag is the ONLY thing
 * that stops a spending processor from acting on a partly-indexed chain. Liquidation and compound pin theirs in
 * their own tests; these pin the tank processor's and /healthcheck's.
 */
class SyncingGateTest {

    private static BlockEventListener listener(boolean syncing) {
        BlockEventListener listener = new BlockEventListener(null);
        listener.getIsSyncing().set(syncing);
        return listener;
    }

    private static ScheduledTransactionService tankProcessor(StakerService staker, BlockEventListener listener) {
        return new ScheduledTransactionService(
                new AppConfig.Network(), mock(AppConfig.AquariumConfiguration.class), mock(Account.class),
                mock(QuickTxBuilder.class), mock(BFBackendService.class), LoanFixtures.protocolParams(),
                mock(com.bloxbean.cardano.client.api.UtxoSupplier.class), mock(UtxoRepository.class), staker,
                LoanFixtures.converters(), mock(ParametersService.class), mock(TankContractService.class),
                mock(AppUtxoService.class), listener);
    }

    @Test
    void theTankProcessorDoesNothingWhileTheNodeIsSyncing() {
        StakerService staker = mock(StakerService.class);
        tankProcessor(staker, listener(true)).processPayments();
        verifyNoInteractions(staker);

        // Positive control: the same processor, synced, reaches its first collaborator.
        StakerService synced = mock(StakerService.class);
        tankProcessor(synced, listener(false)).processPayments();
        assertEquals(1, mockingDetails(synced).getInvocations().size(),
                "a synced tank processor must reach the staker lookup — otherwise the gate test proves nothing");
    }

    @Test
    void theHealthcheckAnswersSyncingAndReadsNothingWhileTheNodeIsSyncing() {
        AppUtxoService utxos = mock(AppUtxoService.class);
        StakerService staker = mock(StakerService.class);
        var check = new Healthcheck(mock(ParametersService.class), staker, listener(true), utxos,
                new LendingConfigGate());

        assertEquals("...syncing...", check.healthCheck().getBody());
        verifyNoInteractions(utxos, staker);

        when(utxos.listWalletUtxo()).thenReturn(List.of());
        when(staker.findStakerRefInput()).thenReturn(List.of());
        var synced = new Healthcheck(mock(ParametersService.class), staker, listener(false), utxos,
                new LendingConfigGate());
        assertEquals(Healthcheck.HealthCheck.class, synced.healthCheck().getBody().getClass(),
                "a synced node answers the full health record (positive control)");
    }
}
