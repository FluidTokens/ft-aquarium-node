package com.fluidtokens.aquarium.offchain.controller.health;

import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.ParametersService;
import com.fluidtokens.aquarium.offchain.service.StakerService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.SimpleStatusAggregator;
import org.springframework.boot.actuate.health.Status;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the cold-sync gate (T-030): while {@link BlockEventListener#getIsSyncing()} is true, each
 * data-dependent health indicator must report NON-DOWN even though its resource is absent/throwing,
 * so the aggregate /actuator/health stays UP (HTTP 200). Once syncing completes, a genuinely absent
 * resource must still yield DOWN.
 */
public class HealthSyncGateTest {

    private BlockEventListener syncingListener() {
        var listener = mock(BlockEventListener.class);
        when(listener.getIsSyncing()).thenReturn(new AtomicBoolean(true));
        return listener;
    }

    private BlockEventListener syncedListener() {
        var listener = mock(BlockEventListener.class);
        when(listener.getIsSyncing()).thenReturn(new AtomicBoolean(false));
        return listener;
    }

    private AppUtxoService walletWithNoAdaOnlyUtxo() {
        var svc = mock(AppUtxoService.class);
        when(svc.listWalletUtxo()).thenReturn(List.of());
        return svc;
    }

    private StakerService stakerWithNoStake() {
        var svc = mock(StakerService.class);
        when(svc.findStakerRefInput()).thenReturn(List.of());
        return svc;
    }

    private ParametersService parametersThatThrow() {
        var svc = mock(ParametersService.class);
        when(svc.loadParameters()).thenThrow(new RuntimeException("parameters not indexed yet"));
        when(svc.loadParametersRefInput()).thenThrow(new RuntimeException("ref input not indexed yet"));
        return svc;
    }

    // ---- while syncing: resource absent/throwing -> NON-DOWN ----

    @Test
    void walletUpWhileSyncing() {
        var indicator = new WalletHealthIndicator(walletWithNoAdaOnlyUtxo(), syncingListener());
        assertNotEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void stakingUpWhileSyncing() {
        var indicator = new StakingHealthIndicator(stakerWithNoStake(), syncingListener());
        assertNotEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void databaseUpWhileSyncing() {
        var indicator = new DatabaseHealthIndicator(parametersThatThrow(), syncingListener());
        assertNotEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void parametersUpWhileSyncing() {
        var indicator = new ParametersHealthIndicator(parametersThatThrow(), syncingListener());
        assertNotEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void parametersRefInputUpWhileSyncing() {
        var indicator = new ParametersRefInputHealthIndicator(parametersThatThrow(), syncingListener());
        assertNotEquals(Status.DOWN, indicator.health().getStatus());
    }

    // ---- after sync: genuine absence -> DOWN (real-health logic preserved) ----

    @Test
    void walletDownWhenSyncedAndNoUtxo() {
        var indicator = new WalletHealthIndicator(walletWithNoAdaOnlyUtxo(), syncedListener());
        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void stakingDownWhenSyncedAndNoStake() {
        var indicator = new StakingHealthIndicator(stakerWithNoStake(), syncedListener());
        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void databaseDownWhenSyncedAndThrows() {
        var indicator = new DatabaseHealthIndicator(parametersThatThrow(), syncedListener());
        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void parametersDownWhenSyncedAndThrows() {
        var indicator = new ParametersHealthIndicator(parametersThatThrow(), syncedListener());
        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    @Test
    void parametersRefInputDownWhenSyncedAndThrows() {
        var indicator = new ParametersRefInputHealthIndicator(parametersThatThrow(), syncedListener());
        assertEquals(Status.DOWN, indicator.health().getStatus());
    }

    // ---- the point of the whole exercise: aggregate stays UP while cold-syncing ----

    @Test
    void aggregateNotDownWhileSyncingWithAllResourcesAbsent() {
        var syncingListener = syncingListener();
        var utxoService = walletWithNoAdaOnlyUtxo();
        var stakerService = stakerWithNoStake();
        var parametersService = parametersThatThrow();

        var wallet = new WalletHealthIndicator(utxoService, syncingListener);
        var staking = new StakingHealthIndicator(stakerService, syncingListener);
        var database = new DatabaseHealthIndicator(parametersService, syncingListener);
        var parameters = new ParametersHealthIndicator(parametersService, syncingListener);
        var parametersRefInput = new ParametersRefInputHealthIndicator(parametersService, syncingListener);
        var sync = new SyncHealthIndicator(syncingListener);

        var aggregate = new SimpleStatusAggregator().getAggregateStatus(
                wallet.health().getStatus(),
                staking.health().getStatus(),
                database.health().getStatus(),
                parameters.health().getStatus(),
                parametersRefInput.health().getStatus(),
                sync.health().getStatus());

        assertNotEquals(Status.DOWN, aggregate);
    }
}
