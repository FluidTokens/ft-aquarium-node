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
 * <p>Ordering: the rebalancer is REGISTERED BEFORE {@link BlockEventListener} on purpose. Without the
 * {@code @Order} on {@code BlockEventListener.processBlock}, listeners of equal order run in registration
 * order, so the rebalancer would read the syncing flag one event stale — and the two ordering tests below
 * would go red in both directions.
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

    /**
     * The production-shaped context. {@code env} is laid over the shipped file as the PROCESS ENVIRONMENT
     * would be; the developer's own environment and system properties are removed, so neither decides it.
     */
    private static ApplicationContextRunner runner(RecordingBlockfrost blockfrost, AppUtxoService wallet,
                                                   Map<String, Object> env) {
        return new ApplicationContextRunner()
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
                        "loans.liquidation.mode=shadow",
                        "loans.liquidation.markets[0].unit=" + NIGHT,
                        "loans.liquidation.markets[0].action=ANTICIPATE",
                        "loans.liquidation.markets[0].cap=1000000000000")
                .withBean(BFBackendService.class, () -> blockfrost)
                .withBean(Account.class, () -> ACCOUNT)
                .withBean(AppUtxoService.class, () -> wallet)
                // ⚠ Registration order is part of the test: the rebalancer BEFORE the block listener.
                .withUserConfiguration(Binder.class, AppConfig.class, ProductionProtocolParams.class,
                        WalletRebalancer.class, BlockEventListener.class);
    }

    private static AppUtxoService wallet() {
        AppUtxoService wallet = mock(AppUtxoService.class);
        when(wallet.listWalletUtxo()).thenReturn(nightWallet());
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
     * after a fresh one blocks on that event.
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
