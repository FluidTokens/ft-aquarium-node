package com.fluidtokens.aquarium.offchain.service.wallet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.EpochService;
import com.bloxbean.cardano.client.backend.api.TransactionService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.yaci.store.events.EventMetadata;
import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.EpochProtocolParamsSupplier;
import com.fluidtokens.aquarium.offchain.config.YaciConfig;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import org.cardanofoundation.conversions.CardanoConverters;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-131: the rebalancer THROUGH THE CONTAINER — the engine had no production caller until this slice, and
 * every engine test supplies its own protocol parameters and submitter. "Promoting test code to src/main
 * requires a production-wiring test" (CLAUDE.md): a bean built from what the tests supply proves nothing
 * about what production earns.
 *
 * <p>The context is the real {@link AppConfig} — its {@code walletShapeTransactions} bean and every nested
 * configuration component — over the SHIPPED {@code application.yaml} base document, plus the real
 * {@link BlockEventListener} and {@link WalletRebalancer}. The {@link ProtocolParamsSupplier} bean is
 * {@code YaciConfig}'s own bean method (the per-epoch cache). The only stand-ins are the outside world: the
 * Blockfrost backend (its epoch service answers {@code LoanFixtures} parameters and its transaction service
 * records what is submitted), the bot account, and the wallet read.
 *
 * <p>⛔ FAB-131 r2: <b>the rebalancer runs AFTER the block's transaction commits.</b> At the chain tip Yaci
 * publishes {@code CommitEvent} inside {@code @Transactional publishBlockEvents}; a wallet read, a build and a
 * Blockfrost submit must not hold that transaction open, nor mark it rollback-only. The context therefore
 * carries a real Spring transaction manager ({@link RecordingTransactionManager}: no DataSource, it only counts
 * commits and rollbacks) and Boot's {@link TransactionAutoConfiguration} — the same route by which production
 * gets the factory that makes {@code @TransactionalEventListener} transactional at all. Without that factory
 * the annotation degrades to a plain {@code @EventListener}, which the in-transaction test below catches.
 *
 * <p>Ordering: the rebalancer is REGISTERED BEFORE {@link BlockEventListener} on purpose. Inside a transaction
 * the phase orders them (the block listener runs in the transaction, the rebalancer after its commit). With no
 * transaction — Yaci's parallel initial-sync path, where {@code fallbackExecution} runs the rebalancer inline —
 * only the {@code @Order} on {@code BlockEventListener.processBlock} does: listeners of equal order run in
 * registration order, so the rebalancer would read the syncing flag one event stale, and the ordering test
 * below would go red in both directions.
 */
class WalletRebalancerWiringTest {

    private static final String NIGHT = "ee".repeat(28) + "4e49474854";

    private static final Account ACCOUNT = new Account(Networks.mainnet());

    /** Blockfrost as the engine sees it: parameters in, submitted bytes recorded, nothing on the network. */
    static final class RecordingBlockfrost extends BFBackendService {

        final List<byte[]> submitted = new ArrayList<>();

        RecordingBlockfrost() {
            super("https://example.invalid/api/v0/", "test");
        }

        @Override
        public EpochService getEpochService() {
            return (EpochService) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{EpochService.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "getProtocolParameters" ->
                                Result.success("ok").withValue(LoanFixtures.protocolParams().getProtocolParams());
                        case "toString" -> "recording EpochService";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        @Override
        public TransactionService getTransactionService() {
            return (TransactionService) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{TransactionService.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "submitTransaction" -> {
                            submitted.add((byte[]) args[0]);
                            yield Result.success("ok").withValue("a".repeat(64));
                        }
                        case "toString" -> "recording TransactionService";
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    /**
     * A real {@link PlatformTransactionManager} with no resource behind it: Spring's own synchronization
     * machinery runs (so {@code @TransactionalEventListener} sees a live transaction), and the test can tell
     * whether the commit had already happened when the wallet was read.
     */
    static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        final AtomicInteger commits = new AtomicInteger();

        final AtomicInteger rollbacks = new AtomicInteger();

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            commits.incrementAndGet();
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rollbacks.incrementAndGet();
        }
    }

    @Configuration
    @EnableConfigurationProperties
    static class Binder {
    }

    /** The PRODUCTION protocol-params bean, built by YaciConfig's own bean method over the context's backend. */
    @Configuration
    static class ProductionProtocolParams {
        @Bean
        ProtocolParamsSupplier protocolParamsSupplier(BFBackendService backend, CardanoConverters converters) {
            return new YaciConfig().protocolParamsSupplier(backend, converters);
        }
    }

    private static PropertySource<?> shippedBaseDocument() {
        try {
            return new YamlPropertySourceLoader()
                    .load("application.yaml", new ClassPathResource("application.yaml")).get(0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Utxo utxo(int index, long lovelace, Object... unitQuantityPairs) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(BigInteger.valueOf(lovelace)));
        for (int i = 0; i < unitQuantityPairs.length; i += 2) {
            amounts.add(Amount.asset((String) unitQuantityPairs[i], BigInteger.valueOf((Long) unitQuantityPairs[i + 1])));
        }
        Utxo u = new Utxo();
        u.setTxHash(String.format("%064x", 0x3000 + index));
        u.setOutputIndex(0);
        u.setAddress(ACCOUNT.baseAddress());
        u.setAmount(amounts);
        return u;
    }

    private static List<Utxo> nightWallet() {
        return List.of(utxo(1, 1_180_000, NIGHT, 15_000_000_000L), utxo(2, 200_000_000));
    }

    /** The production-shaped context in {@code shadow}, with a transaction manager no test inspects. */
    private static ApplicationContextRunner runner(RecordingBlockfrost blockfrost, AppUtxoService wallet,
                                                   Map<String, Object> env) {
        return runner(blockfrost, wallet, env, "shadow", new RecordingTransactionManager());
    }

    /**
     * The production-shaped context. {@code env} is laid over the shipped file as the PROCESS ENVIRONMENT
     * would be; the developer's own environment and system properties are removed, so neither decides it.
     * {@code mode} {@code null} leaves {@code loans.liquidation.mode} to the shipped file (and {@code env}).
     */
    private static ApplicationContextRunner runner(RecordingBlockfrost blockfrost, AppUtxoService wallet,
                                                   Map<String, Object> env, String mode,
                                                   RecordingTransactionManager transactionManager) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    sources.addLast(shippedBaseDocument());
                    if (!env.isEmpty()) {
                        sources.addFirst(new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
                    }
                })
                .withPropertyValues(
                        "loans.liquidation.markets[0].unit=" + NIGHT,
                        "loans.liquidation.markets[0].action=ANTICIPATE",
                        "loans.liquidation.markets[0].cap=1000000000000")
                .withConfiguration(AutoConfigurations.of(TransactionAutoConfiguration.class))
                .withBean(PlatformTransactionManager.class, () -> transactionManager)
                .withBean(BFBackendService.class, () -> blockfrost)
                .withBean(Account.class, () -> ACCOUNT)
                .withBean(AppUtxoService.class, () -> wallet)
                // ⚠ Registration order is part of the test: the rebalancer BEFORE the block listener.
                .withUserConfiguration(Binder.class, AppConfig.class, ProductionProtocolParams.class,
                        WalletRebalancer.class, BlockEventListener.class);
        return mode == null ? runner : runner.withPropertyValues("loans.liquidation.mode=" + mode);
    }

    private static AppUtxoService wallet() {
        AppUtxoService wallet = mock(AppUtxoService.class);
        when(wallet.listWalletUtxo()).thenReturn(nightWallet());
        return wallet;
    }

    /** A wallet read that records how many commits the transaction manager had seen when it was made. */
    private static AppUtxoService walletRecordingCommits(RecordingTransactionManager transactionManager,
                                                         List<Integer> commitsAtRead) {
        AppUtxoService wallet = mock(AppUtxoService.class);
        when(wallet.listWalletUtxo()).thenAnswer(invocation -> {
            commitsAtRead.add(transactionManager.commits.get());
            return nightWallet();
        });
        return wallet;
    }

    private static int reads(AppUtxoService wallet) {
        return mockingDetails(wallet).getInvocations().size();
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static CommitEvent<?> blockAt(CardanoConverters converters, Duration age) {
        long slot = converters.time().toSlot(LocalDateTime.ofInstant(Instant.now().minus(age), ZoneOffset.UTC));
        return new CommitEvent<>(EventMetadata.builder().slot(slot).build(), List.of());
    }

    @Test
    void theRebalancerAndItsEngineAreWiredFromTheProductionBeans() {
        RecordingBlockfrost blockfrost = new RecordingBlockfrost();
        runner(blockfrost, wallet(), Map.of()).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            assertEquals(1, context.getBeanNamesForType(WalletRebalancer.class).length, "one WalletRebalancer bean");
            assertEquals(1, context.getBeanNamesForType(WalletShapeTransactions.class).length, "one engine bean");

            WalletShapeTransactions engine = context.getBean(WalletShapeTransactions.class);
            WalletRebalancer rebalancer = context.getBean(WalletRebalancer.class);
            assertSame(engine, field(rebalancer, "engine"), "the rebalancer holds the engine bean");

            Object params = field(engine, "protocolParamsSupplier");
            assertSame(context.getBean(ProtocolParamsSupplier.class), params,
                    "the engine reads the context's ProtocolParamsSupplier bean");
            assertInstanceOf(EpochProtocolParamsSupplier.class, params,
                    "the engine's parameters are the per-epoch cache, not a fixture and not a fetch per call");
            assertEquals(ACCOUNT.baseAddress(), engine.walletAddress(), "the engine spends the bot account");

            // The submitter is Blockfrost's transaction service: bytes handed to the engine arrive there.
            Transaction signed = engine.sign(engine.buildShaped(nightWallet(), java.util.Set.of(NIGHT)).transaction());
            assertTrue(engine.submit(signed).isSuccessful());
            assertEquals(1, blockfrost.submitted.size());
            assertTrue(java.util.Arrays.equals(signed.serialize(), blockfrost.submitted.get(0)),
                    "the bytes submitted are the bytes signed");
        });
    }

    /**
     * ⛔ A CommitEvent reaches the rebalancer AFTER {@link BlockEventListener} has updated the syncing flag for
     * the SAME event, in both directions: a fresh block releases a fresh bean on that event, and an old block
     * after a fresh one blocks on that event. Published with NO transaction, so the transaction phase does not
     * order the two listeners here and only {@code BlockEventListener}'s {@code @Order} does.
     */
    @Test
    void aCommitEventReachesTheRebalancerAfterTheSyncingFlagIsUpdated() {
        RecordingBlockfrost blockfrost = new RecordingBlockfrost();
        AppUtxoService wallet = wallet();
        runner(blockfrost, wallet, Map.of()).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            CardanoConverters converters = context.getBean(CardanoConverters.class);
            BlockEventListener listener = context.getBean(BlockEventListener.class);
            assertTrue(listener.getIsSyncing().get(), "a fresh node starts syncing");
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive(), "the no-transaction path");

            // A one-minute-old block: the listener clears the flag, and the rebalancer must see it cleared.
            context.publishEvent(blockAt(converters, Duration.ofMinutes(1)));
            assertFalse(listener.getIsSyncing().get());
            assertEquals(1, reads(wallet), "the rebalancer read a stale 'syncing' flag: it ran before the listener");
            assertEquals(1, blockfrost.submitted.size(), "SHADOW submits the rebalance");

            Transaction tx = Transaction.deserialize(blockfrost.submitted.get(0));
            List<Map<String, BigInteger>> outputs = new ArrayList<>();
            for (TransactionOutput output : tx.getBody().getOutputs()) {
                outputs.add(WalletShapeTransactions.unitsOf(output.getValue()));
            }
            assertTrue(outputs.contains(Map.of("lovelace", WalletShape.SHAPED_TOKEN_LOVELACE,
                    NIGHT, BigInteger.valueOf(15_000_000_000L))), "15,000 NIGHT + 10 ADA: " + outputs);

            // A thirty-minute-old block: the listener sets the flag, and the rebalancer must see it set.
            context.publishEvent(blockAt(converters, Duration.ofMinutes(30)));
            assertTrue(listener.getIsSyncing().get());
            assertEquals(1, reads(wallet), "the rebalancer read a stale 'not syncing' flag: it ran before the listener");
            assertEquals(1, blockfrost.submitted.size());
        });
    }

    /**
     * ⛔ Audit finding 1: at the chain tip Yaci publishes {@code CommitEvent} INSIDE its block transaction
     * ({@code ShelleyBlockEventPublisher.publishBlockEvents}, {@code @Transactional}). The rebalancer must not run
     * there: nothing at the point {@code publishEvent} returns, exactly one read and one submit once the
     * transaction commits — and the read made AFTER the commit, not during it (which is what a
     * {@code BEFORE_COMMIT} listener would do).
     */
    @Test
    void insideTheBlocksTransactionTheRebalancerWaitsForTheCommit() {
        RecordingBlockfrost blockfrost = new RecordingBlockfrost();
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        List<Integer> commitsAtRead = new ArrayList<>();
        AppUtxoService wallet = walletRecordingCommits(transactionManager, commitsAtRead);
        runner(blockfrost, wallet, Map.of(), "shadow", transactionManager).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            assertSame(transactionManager, context.getBean(PlatformTransactionManager.class));
            CardanoConverters converters = context.getBean(CardanoConverters.class);
            BlockEventListener listener = context.getBean(BlockEventListener.class);

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertTrue(TransactionSynchronizationManager.isSynchronizationActive(),
                        "the test must publish inside a live Spring transaction");
                context.publishEvent(blockAt(converters, Duration.ofMinutes(1)));
                assertFalse(listener.getIsSyncing().get(), "the block listener runs inside the transaction");
                assertEquals(0, reads(wallet), "the rebalancer read the wallet INSIDE the block's transaction");
                assertEquals(0, blockfrost.submitted.size(), "the rebalancer submitted INSIDE the block's transaction");
            });

            assertEquals(1, transactionManager.commits.get());
            assertEquals(1, reads(wallet), "once the block's transaction commits, the rebalancer runs once");
            assertEquals(List.of(1), commitsAtRead, "the wallet must be read AFTER the commit, not during it");
            assertEquals(1, blockfrost.submitted.size(), "and submits once");
        });
    }

    /** A block whose transaction rolls back never reaches the rebalancer: nothing read, nothing submitted. */
    @Test
    void aRolledBackBlockTransactionNeverReachesTheRebalancer() {
        RecordingBlockfrost blockfrost = new RecordingBlockfrost();
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        AppUtxoService wallet = wallet();
        runner(blockfrost, wallet, Map.of(), "shadow", transactionManager).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            CardanoConverters converters = context.getBean(CardanoConverters.class);
            BlockEventListener listener = context.getBean(BlockEventListener.class);

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                context.publishEvent(blockAt(converters, Duration.ofMinutes(1)));
                status.setRollbackOnly();
            });

            assertEquals(1, transactionManager.rollbacks.get());
            assertEquals(0, transactionManager.commits.get());
            assertFalse(listener.getIsSyncing().get(), "the event did reach the in-transaction block listener");
            assertEquals(0, reads(wallet), "a rolled-back block must not reach the rebalancer");
            assertEquals(0, blockfrost.submitted.size());
        });
    }

    /**
     * With NO transaction — Yaci's parallel initial-sync path publishes {@code CommitEvent} on an executor
     * thread outside any transaction — the rebalancer still runs ({@code fallbackExecution = true}), inline.
     */
    @Test
    void withNoTransactionTheRebalancerStillRunsInline() {
        RecordingBlockfrost blockfrost = new RecordingBlockfrost();
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        AppUtxoService wallet = wallet();
        runner(blockfrost, wallet, Map.of(), "shadow", transactionManager).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            assertFalse(TransactionSynchronizationManager.isSynchronizationActive(), "no transaction here");

            context.publishEvent(blockAt(context.getBean(CardanoConverters.class), Duration.ofMinutes(1)));

            assertEquals(1, reads(wallet), "with no transaction the rebalancer must still run");
            assertEquals(1, blockfrost.submitted.size());
            assertEquals(0, transactionManager.commits.get() + transactionManager.rollbacks.get(),
                    "and nothing opened a transaction for it");
        });
    }

    /**
     * ⛔ Audit finding 3: the SHIPPED default, through the container. No {@code loans.liquidation.mode} override,
     * so the shipped file's {@code disabled} decides; an ANTICIPATE market is configured and a fresh block
     * arrives, inside a committed transaction and outside one. Nothing is read and nothing is submitted. The
     * positive control is the same container with only {@code AQUARIUM_LIQUIDATION_MODE=shadow} added: it does
     * rebalance, so the shipped mode is the one thing that held the first one back.
     */
    @Test
    void theShippedDisabledModeKeepsAnAnticipateNodeFromRebalancing() {
        RecordingBlockfrost blockfrost = new RecordingBlockfrost();
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        AppUtxoService wallet = wallet();
        runner(blockfrost, wallet, Map.of(), null, transactionManager).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            assertEquals(AppConfig.LiquidationConfiguration.Mode.DISABLED,
                    context.getBean(AppConfig.LiquidationConfiguration.class).getMode(), "the shipped default");
            CardanoConverters converters = context.getBean(CardanoConverters.class);
            BlockEventListener listener = context.getBean(BlockEventListener.class);

            new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                    context.publishEvent(blockAt(converters, Duration.ofMinutes(1))));
            context.publishEvent(blockAt(converters, Duration.ofMinutes(1)));

            assertEquals(1, transactionManager.commits.get());
            assertFalse(listener.getIsSyncing().get(), "the blocks arrived and the node is not syncing");
            assertEquals(0, reads(wallet), "the shipped mode is DISABLED: no wallet read");
            assertEquals(0, blockfrost.submitted.size(), "the shipped mode is DISABLED: no submission");
        });

        RecordingBlockfrost armedBlockfrost = new RecordingBlockfrost();
        AppUtxoService armedWallet = wallet();
        runner(armedBlockfrost, armedWallet, Map.of("AQUARIUM_LIQUIDATION_MODE", "shadow"), null,
                new RecordingTransactionManager()).run(context -> {
            assertNull(context.getStartupFailure(), "the container did not start: " + context.getStartupFailure());
            context.publishEvent(blockAt(context.getBean(CardanoConverters.class), Duration.ofMinutes(1)));
            assertEquals(1, reads(armedWallet), "positive control: the same container in shadow reads the wallet");
            assertEquals(1, armedBlockfrost.submitted.size(), "positive control: and submits");
        });
    }

    @Test
    void theShippedFileEnablesTheRebalancerAndTheEnvVarDisablesIt() {
        runner(new RecordingBlockfrost(), wallet(), Map.of()).run(context ->
                assertEquals(new WalletRebalancer.State(true, false, true),
                        context.getBean(WalletRebalancer.class).state()));
        runner(new RecordingBlockfrost(), wallet(), Map.of("AQUARIUM_WALLET_REBALANCE_ENABLED", "false")).run(context ->
                assertEquals(new WalletRebalancer.State(false, false, false),
                        context.getBean(WalletRebalancer.class).state()));
    }

    /** The key's binding with NO shipped file: absent → true (the Java default), {@code false} → false. */
    @Test
    void thePropertyBindsAbsentAsTrueAndFalseAsFalse() {
        ApplicationContextRunner bare = new ApplicationContextRunner()
                .withInitializer(context -> {
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                    // The one key the LiquidationConfiguration bean cannot start without (no inline default).
                    sources.addLast(new MapPropertySource("margin",
                            Map.of("loans.liquidation.profit-margin-lovelace", "5000000")));
                })
                .withBean(WalletShapeTransactions.class, () -> new WalletShapeTransactions(ACCOUNT,
                        LoanFixtures.protocolParams(), bytes -> Result.error("not in this test")))
                .withBean(AppUtxoService.class, WalletRebalancerWiringTest::wallet)
                .withBean(BlockEventListener.class, () -> new BlockEventListener(LoanFixtures.converters()))
                .withBean(AppConfig.LiquidationConfiguration.class, () -> new AppConfig.LiquidationConfiguration(
                        AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30, BigInteger.ZERO, 200))
                .withUserConfiguration(WalletRebalancer.class);

        bare.run(context -> {
            assertNull(context.getStartupFailure(), String.valueOf(context.getStartupFailure()));
            assertTrue(context.getBean(WalletRebalancer.class).state().envEnabled(), "absent → true");
        });
        bare.withPropertyValues("loans.wallet.rebalance.enabled=false").run(context ->
                assertFalse(context.getBean(WalletRebalancer.class).state().envEnabled(), "false → false"));
    }
}
