package com.fluidtokens.aquarium.offchain.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
@RequiredArgsConstructor
@Slf4j
public class MetricsService {

    private final MeterRegistry meterRegistry;

    // Gauges for current state
    private final AtomicInteger syncStatus = new AtomicInteger(0);
    private final AtomicInteger walletUtxoCount = new AtomicInteger(0);
    private final AtomicInteger stakingActive = new AtomicInteger(0);
    private final AtomicLong currentSlot = new AtomicLong(0);
    private final AtomicInteger databaseConnected = new AtomicInteger(0);

    // Counters for events
    private Counter blockfrostApiCalls;
    private Counter blockfrostApiErrors;
    private Counter blocksProcessed;
    private Counter transactionsProcessed;

    // Timers for operations
    private Timer utxoFetchTimer;
    private Timer parameterLoadTimer;
    private Timer stakingCheckTimer;

    @PostConstruct
    public void initializeMetrics() {
        log.info("Initializing Aquarium Node metrics");

        // Register Gauges
        Gauge.builder("aquarium_sync_status", syncStatus, AtomicInteger::doubleValue)
                .description("Current blockchain sync status (0=not syncing, 1=syncing)")
                .register(meterRegistry);

        Gauge.builder("aquarium_wallet_utxo_count", walletUtxoCount, AtomicInteger::doubleValue)
                .description("Number of UTXOs in wallet")
                .register(meterRegistry);

        Gauge.builder("aquarium_staking_active", stakingActive, AtomicInteger::doubleValue)
                .description("Whether staking is active (0=inactive, 1=active)")
                .register(meterRegistry);

        Gauge.builder("aquarium_current_slot", currentSlot, AtomicLong::doubleValue)
                .description("Current blockchain slot")
                .register(meterRegistry);

        Gauge.builder("aquarium_database_connected", databaseConnected, AtomicInteger::doubleValue)
                .description("Database connection status (0=disconnected, 1=connected)")
                .register(meterRegistry);

        // Register Counters
        blockfrostApiCalls = Counter.builder("aquarium_blockfrost_api_calls_total")
                .description("Total number of Blockfrost API calls")
                .register(meterRegistry);

        blockfrostApiErrors = Counter.builder("aquarium_blockfrost_api_errors_total")
                .description("Total number of Blockfrost API errors")
                .register(meterRegistry);

        blocksProcessed = Counter.builder("aquarium_blocks_processed_total")
                .description("Total number of blocks processed")
                .register(meterRegistry);

        transactionsProcessed = Counter.builder("aquarium_transactions_processed_total")
                .description("Total number of transactions processed")
                .register(meterRegistry);

        // Register Timers
        utxoFetchTimer = Timer.builder("aquarium_utxo_fetch_duration_seconds")
                .description("Time spent fetching UTXOs")
                .register(meterRegistry);

        parameterLoadTimer = Timer.builder("aquarium_parameter_load_duration_seconds")
                .description("Time spent loading parameters")
                .register(meterRegistry);

        stakingCheckTimer = Timer.builder("aquarium_staking_check_duration_seconds")
                .description("Time spent checking staking status")
                .register(meterRegistry);

        log.info("Aquarium Node metrics initialized successfully");
    }

    // Gauge update methods
    public void updateSyncStatus(boolean syncing) {
        syncStatus.set(syncing ? 1 : 0);
    }

    public void updateWalletUtxoCount(int count) {
        walletUtxoCount.set(count);
    }

    public void updateStakingActive(boolean active) {
        stakingActive.set(active ? 1 : 0);
    }

    public void updateCurrentSlot(long slot) {
        currentSlot.set(slot);
    }

    public void updateDatabaseConnected(boolean connected) {
        databaseConnected.set(connected ? 1 : 0);
    }

    // Counter increment methods
    public void incrementBlockfrostApiCall() {
        blockfrostApiCalls.increment();
    }

    public void incrementBlockfrostApiError() {
        blockfrostApiErrors.increment();
    }

    public void incrementBlocksProcessed() {
        blocksProcessed.increment();
    }

    public void incrementTransactionsProcessed() {
        transactionsProcessed.increment();
    }

     // Convenience methods for timing operations
    public <T> T timeUtxoFetch(java.util.function.Supplier<T> operation) {
        try {
            return utxoFetchTimer.recordCallable(operation::get);
        } catch (Exception e) {
            throw new RuntimeException("Error during timed UTXO fetch operation", e);
        }
    }

    public <T> T timeParameterLoad(java.util.function.Supplier<T> operation) {
        try {
            return parameterLoadTimer.recordCallable(operation::get);
        } catch (Exception e) {
            throw new RuntimeException("Error during timed parameter load operation", e);
        }
    }

    public <T> T timeStakingCheck(java.util.function.Supplier<T> operation) {
        try {
            return stakingCheckTimer.recordCallable(operation::get);
        } catch (Exception e) {
            throw new RuntimeException("Error during timed staking check operation", e);
        }
    }
}