package com.fluidtokens.aquarium.offchain.storage;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.core.model.Amount;
import com.bloxbean.cardano.yaci.core.model.Era;
import com.bloxbean.cardano.yaci.core.model.TransactionBody;
import com.bloxbean.cardano.yaci.core.model.TransactionInput;
import com.bloxbean.cardano.yaci.core.model.TransactionOutput;
import com.bloxbean.cardano.yaci.core.protocol.chainsync.messages.Point;
import com.bloxbean.cardano.yaci.helper.model.Transaction;
import com.bloxbean.cardano.yaci.helper.model.Utxo;
import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.domain.TxInput;
import com.bloxbean.cardano.yaci.store.events.EventMetadata;
import com.bloxbean.cardano.yaci.store.events.RollbackEvent;
import com.bloxbean.cardano.yaci.store.events.TransactionEvent;
import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import com.bloxbean.cardano.yaci.store.utxo.UtxoStoreProperties;
import com.bloxbean.cardano.yaci.store.utxo.processor.UtxoProcessor;
import com.bloxbean.cardano.yaci.store.utxo.processor.UtxoRollbackProcessor;
import com.bloxbean.cardano.yaci.store.utxo.storage.AddressStorage;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.UtxoCache;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.TxInputEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.TxInputRepository;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.ParametersContractService;
import com.fluidtokens.aquarium.offchain.service.StakerContractService;
import com.fluidtokens.aquarium.offchain.service.TankContractService;
import com.fluidtokens.aquarium.offchain.service.loans.OracleOutRefIndex.Answer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-203-1: what this node's index does when <b>Yaci Store's own {@link UtxoProcessor} and
 * {@link UtxoRollbackProcessor}</b> drive {@link TankUtxoStorage} — pinned on 0.1.7 so the bump to 2.0.7 can be
 * measured against it ("same data in, same data out"), not argued.
 *
 * <h2>The rule this file lives by: it must compile and pass UNCHANGED on Yaci Store 0.1.7 and 2.0.7</h2>
 * It therefore names no class or member that exists in only one of them. The processors are registered by
 * class, so the container picks and fills whichever constructor the version on the classpath has (0.1.7:
 * {@code UtxoStorage, ApplicationEventPublisher, MeterRegistry}; 2.0.7 adds {@code AddressStorage} and
 * {@code StakingClient}). {@code AddressUtxo} objects are never compared by {@code equals} (2.0.7 adds
 * {@code txIndex}); every pin compares out-refs and named fields. The single version-dependent element is the
 * reflective {@code StakingClient} mock in {@link #runner()}.
 *
 * <h2>The seam</h2>
 * A real Spring context over H2 with Yaci's Flyway schema ({@code classpath:db/store/h2}) and the real Spring
 * Data repositories (prior art: {@link TankUtxoStoragePoolRollbackTest}). The storage is the context's only
 * {@code UtxoStorage}; its jOOQ writes go through a {@link TransactionAwareDataSourceProxy}, as Spring Boot's
 * jOOQ wiring does in production, so they belong to the block's transaction rather than to an autocommit
 * connection of their own. The driver mirrors {@code ShelleyBlockEventPublisher.publishBlockEvents} (parallel
 * processing off): one transaction around {@code TransactionEvent} then {@code CommitEvent}.
 *
 * <h2>The fixture</h2>
 * {@code W} the wallet's base address; {@code F} an enterprise key address no credential watches; {@code O} an
 * enterprise script address no credential watches; {@code P} an address under the shipped Minswap V2 pool
 * credential. Block 1 at {@link #S1}, in this order:
 * <ul>
 *   <li>{@code A} (valid): spends {@code X#0} (never indexed); creates {@code A#0→W}, {@code A#1→F},
 *       {@code A#2→O} holding one oracle feed NFT, {@code A#3→P} holding a token;</li>
 *   <li>{@code B} (valid): spends {@code A#0} — created in this same block; creates {@code B#0→W};</li>
 *   <li>{@code C} (INVALID): one body output, collateral input {@code A#1}, collateral return to {@code W}
 *       (indexed {@code C#1}).</li>
 * </ul>
 * Block 2 at {@link #S2}: {@code D} (valid) spends {@code B#0} and {@code A#2}; creates {@code D#0→F}.
 *
 * <h2>The pins</h2>
 * P1 what the storage is handed · P2 rows after block 1 · P3 the {@code UtxoCache} around the commit · P4 an
 * after-commit consumer · P5 the node's reads · P6 block 2 · P7 a rollback to block 1. Spent rows stay in
 * {@code address_utxo}; spentness lives in {@code tx_input} ({@code officina:yaci-store-index-scoping} §4b), and
 * {@code saveSpent} keeps only inputs whose output is indexed (§2a) — hence what P2 expects.
 */
class UtxoProcessorPinTest {

    /** The shipped {@code loans.minswap.pool-spend-script-hash}: the Minswap V2 pool payment credential. */
    private static final String POOL_PKH = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";

    /** The mainnet {@code loans.oracle.indexed-nft-policies}: the Lending v4 feed policy. */
    private static final String FEED_POLICY = "26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26";

    private static final AssetType FEED_NFT = new AssetType(FEED_POLICY, "4f52434c");

    /** A token held by the pool output; its policy is not an oracle policy. */
    private static final AssetType POOL_TOKEN = new AssetType("c0".repeat(28), "504f4f4c");

    /** Present only on Yaci Store 2.0.7's classpath; named as a string so this file compiles on 0.1.7 too. */
    private static final String STAKING_CLIENT = "com.bloxbean.cardano.yaci.store.client.staking.StakingClient";

    private static final CardanoConverters MAINNET = ClasspathConversionsFactory.createConverters(NetworkType.MAINNET);

    private static final long S1 = 140_000_000L;
    private static final long S2 = 140_000_020L;
    private static final String BLOCK_HASH_1 = "b1".repeat(32);
    private static final String BLOCK_HASH_2 = "b2".repeat(32);

    private static final String TX_X = "09".repeat(32);
    private static final String TX_A = "0a".repeat(32);
    private static final String TX_B = "0b".repeat(32);
    private static final String TX_C = "0c".repeat(32);
    private static final String TX_D = "0d".repeat(32);

    private static final Map<String, String> NAMES = Map.of(TX_X, "X", TX_A, "A", TX_B, "B", TX_C, "C", TX_D, "D");

    private static final String F = AddressProvider.getEntAddress(
            Credential.fromKey(HexUtil.decodeHexString("ff".repeat(28))), Networks.testnet()).toBech32();
    private static final String O_SCRIPT = "f0".repeat(28);
    private static final String O = AddressProvider.getEntAddress(
            Credential.fromScript(HexUtil.decodeHexString(O_SCRIPT)), Networks.testnet()).toBech32();
    private static final String P = AddressProvider.getEntAddress(
            Credential.fromScript(HexUtil.decodeHexString(POOL_PKH)), Networks.testnet()).toBech32();

    // ---- the context -------------------------------------------------------------------------------------------

    @Configuration
    @EntityScan(basePackageClasses = AddressUtxoEntity.class)
    @EnableJpaRepositories(basePackageClasses = UtxoRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UtxoRepository.class, TxInputRepository.class}))
    static class Jpa {
    }

    /**
     * Everything but the JPA seam. Yaci's two processors, the real {@link BlockEventListener} and the probe are
     * registered BY CLASS: the container chooses and fills their constructors, never this test.
     */
    @Configuration(proxyBeanMethods = false)
    @Import({UtxoProcessor.class, UtxoRollbackProcessor.class, BlockEventListener.class, CommitProbe.class})
    static class Index {

        @Bean
        Account account() {
            return new Account(Networks.testnet());
        }

        @Bean
        UtxoCache utxoCache() {
            return new UtxoCache();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        AddressStorage addressStorage() {
            return mock(AddressStorage.class);
        }

        @Bean
        UtxoStoreProperties utxoStoreProperties() {
            return new UtxoStoreProperties();
        }

        @Bean
        CardanoConverters cardanoConverters() {
            return MAINNET;
        }

        /** The context's only {@code UtxoStorage}. */
        @Bean
        RecordingTankUtxoStorage utxoStorage(UtxoRepository utxoRepository, TxInputRepository txInputRepository,
                                             DataSource dataSource, UtxoCache utxoCache,
                                             PlatformTransactionManager transactionManager, Account account) {
            var parameters = mock(ParametersContractService.class);
            when(parameters.getScriptHashHex()).thenReturn("a1".repeat(28));
            var staker = mock(StakerContractService.class);
            when(staker.getScriptHashHex()).thenReturn("a2".repeat(28));
            var tank = mock(TankContractService.class);
            when(tank.getScriptHashHex()).thenReturn("a3".repeat(28));
            @SuppressWarnings("unchecked")
            ObjectProvider<LoansContractRegistry> noRegistry = mock(ObjectProvider.class);
            return new RecordingTankUtxoStorage(utxoRepository, txInputRepository,
                    DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.H2), utxoCache,
                    transactionManager, account, parameters, staker, tank, noRegistry, POOL_PKH, FEED_POLICY);
        }
    }

    /** {@link TankUtxoStorage} unchanged, plus a record of every list Yaci's processor hands it. */
    static class RecordingTankUtxoStorage extends TankUtxoStorage {

        private final List<List<String>> spentCalls = new CopyOnWriteArrayList<>();
        private final List<List<String>> unspentCalls = new CopyOnWriteArrayList<>();

        RecordingTankUtxoStorage(UtxoRepository utxoRepository, TxInputRepository txInputRepository,
                                 org.jooq.DSLContext dsl, UtxoCache utxoCache,
                                 PlatformTransactionManager transactionManager, Account account,
                                 ParametersContractService parameters, StakerContractService staker,
                                 TankContractService tank, ObjectProvider<LoansContractRegistry> loans,
                                 String minswapPool, String oracleNftPolicies) {
            super(utxoRepository, txInputRepository, dsl, utxoCache, transactionManager, account, parameters, staker,
                    tank, loans, minswapPool, oracleNftPolicies);
        }

        @Override
        public void saveSpent(List<TxInput> txInputs) {
            spentCalls.add(txInputs.stream().map(i -> name(i.getTxHash(), i.getOutputIndex())).toList());
            super.saveSpent(txInputs);
        }

        @Override
        public void saveUnspent(List<AddressUtxo> addressUtxoList) {
            unspentCalls.add(addressUtxoList.stream()
                    .map(u -> name(u.getTxHash(), u.getOutputIndex())
                            + (Boolean.TRUE.equals(u.getIsCollateralReturn()) ? " collateral-return" : ""))
                    .toList());
            super.saveUnspent(addressUtxoList);
        }

        public List<List<String>> spentCalls() {
            return List.copyOf(spentCalls);
        }

        public List<List<String>> unspentCalls() {
            return List.copyOf(unspentCalls);
        }
    }

    /**
     * Stands in for {@code WalletPipelineService.onCommit}, with exactly its annotation. That method only
     * enqueues; the pipeline's stages read the index later, on another thread. This probe reads the wallet's
     * unspent outputs at the moment it runs, which is the earliest any such consumer could.
     */
    static class CommitProbe {

        private final UtxoRepository utxoRepository;
        private final String walletPkh;
        private final List<String> runs = new CopyOnWriteArrayList<>();

        CommitProbe(UtxoRepository utxoRepository, Account account) {
            this.utxoRepository = utxoRepository;
            this.walletPkh = account.getBaseAddress().getPaymentCredentialHash().map(HexUtil::encodeHexString).get();
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
        public void onCommit(CommitEvent<?> commitEvent) {
            runs.add(commitEvent.getMetadata().getSlot() + " " + unspentByCredential(utxoRepository, walletPkh));
        }

        List<String> runs() {
            return List.copyOf(runs);
        }
    }

    private ApplicationContextRunner runner() {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class))
                .withUserConfiguration(Jpa.class, Index.class)
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + UUID.randomUUID()
                                + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                        "spring.datasource.username=sa",
                        "spring.datasource.password=",
                        "spring.jpa.hibernate.ddl-auto=none",
                        "spring.jpa.open-in-view=false",
                        "aquarium.syncing-threshold-minutes=10");
        if (ClassUtils.isPresent(STAKING_CLIENT, UtxoProcessorPinTest.class.getClassLoader())) {
            // Exists for Yaci Store 2.0.7's UtxoProcessor constructor only. It is consulted for pointer addresses
            // on a CommitEvent before the Conway era, so it is never called for the Conway-era blocks here.
            Class<Object> stakingClient = loadClass(STAKING_CLIENT);
            runner = runner.withBean(stakingClient, () -> mock(stakingClient));
        }
        return runner;
    }

    @SuppressWarnings("unchecked")
    private static Class<Object> loadClass(String name) {
        try {
            return (Class<Object>) Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The beans every pin reads, after the context has started and Yaci's schema is migrated. */
    private record World(AssertableApplicationContext context, RecordingTankUtxoStorage storage,
                         UtxoRepository utxoRepository, TxInputRepository txInputRepository, UtxoCache cache,
                         CommitProbe probe, BlockEventListener blockEventListener, TransactionTemplate inTx,
                         String w, String walletPkh) {
    }

    private static World world(AssertableApplicationContext context) {
        assertNull(context.getStartupFailure(), "the context must start: " + context.getStartupFailure());
        Flyway.configure().dataSource(context.getBean(DataSource.class)).locations("classpath:db/store/h2")
                .load().migrate();
        Account account = context.getBean(Account.class);
        return new World(context, context.getBean(RecordingTankUtxoStorage.class),
                context.getBean(UtxoRepository.class), context.getBean(TxInputRepository.class),
                context.getBean(UtxoCache.class), context.getBean(CommitProbe.class),
                context.getBean(BlockEventListener.class),
                new TransactionTemplate(context.getBean(PlatformTransactionManager.class)),
                account.baseAddress(),
                account.getBaseAddress().getPaymentCredentialHash().map(HexUtil::encodeHexString).get());
    }

    // ---- the driver --------------------------------------------------------------------------------------------

    /** One block as Yaci publishes it at the tip: one transaction around TransactionEvent then CommitEvent. */
    private static void block(World world, EventMetadata metadata, List<Transaction> transactions) {
        block(world, metadata, transactions, () -> { }, () -> { });
    }

    /** {@link #block}, running {@code beforeCommitEvent} and {@code afterCommitEvent} inside the transaction. */
    private static void block(World world, EventMetadata metadata, List<Transaction> transactions,
                              Runnable beforeCommitEvent, Runnable afterCommitEvent) {
        world.inTx().executeWithoutResult(status -> {
            world.context().publishEvent(new TransactionEvent(metadata, transactions));
            beforeCommitEvent.run();
            world.context().publishEvent(new CommitEvent<>(metadata, List.of()));
            afterCommitEvent.run();
        });
    }

    private static EventMetadata metadata(long slot, long block, String blockHash) {
        return EventMetadata.builder()
                .era(Era.Conway)
                .slot(slot)
                .block(block)
                .blockHash(blockHash)
                .blockTime(1_700_000_000L + slot)
                .epochNumber(560)
                .build();
    }

    // ---- the fixture -------------------------------------------------------------------------------------------

    private static Amount lovelace(long quantity) {
        return Amount.builder().unit("lovelace").policyId(null).assetName("lovelace")
                .quantity(BigInteger.valueOf(quantity)).build();
    }

    /** Yaci's shape for a native asset: the unit is {@code policy.hexName}, the asset name its UTF-8 text. */
    private static Amount token(AssetType asset, String utf8Name) {
        return Amount.builder().unit(asset.policyId() + "." + asset.assetName()).policyId(asset.policyId())
                .assetName(utf8Name).assetNameBytes(HexUtil.decodeHexString(asset.assetName()))
                .quantity(BigInteger.ONE).build();
    }

    private static Utxo utxo(String txHash, int index, String address, Amount... amounts) {
        return Utxo.builder().txHash(txHash).index(index).address(address).amounts(List.of(amounts)).build();
    }

    private static Set<TransactionInput> inputs(TransactionInput... inputs) {
        return new LinkedHashSet<>(List.of(inputs));
    }

    private static TransactionInput in(String txHash, int index) {
        return new TransactionInput(txHash, index);
    }

    private static Transaction valid(long slot, long block, String txHash, Set<TransactionInput> inputs, Utxo... outputs) {
        return Transaction.builder()
                .blockNumber(block)
                .slot(slot)
                .txHash(txHash)
                .body(TransactionBody.builder().txHash(txHash).inputs(inputs).outputs(List.of())
                        .collateralInputs(Set.of()).build())
                .utxos(List.of(outputs))
                .invalid(false)
                .build();
    }

    private static List<Transaction> blockOne(String w) {
        Transaction a = valid(S1, 1, TX_A, inputs(in(TX_X, 0)),
                utxo(TX_A, 0, w, lovelace(2_000_000)),
                utxo(TX_A, 1, F, lovelace(3_000_000)),
                utxo(TX_A, 2, O, lovelace(4_000_000), token(FEED_NFT, "ORCL")),
                utxo(TX_A, 3, P, lovelace(5_000_000), token(POOL_TOKEN, "POOL")));
        Transaction b = valid(S1, 1, TX_B, inputs(in(TX_A, 0)),
                utxo(TX_B, 0, w, lovelace(1_800_000)));
        // Invalid: its body inputs are not spent; its collateral input is, and its collateral return is an output.
        // The return's own index is left at 0 on purpose: the processor re-indexes it to body.outputs.size() = 1.
        Transaction c = Transaction.builder()
                .blockNumber(1)
                .slot(S1)
                .txHash(TX_C)
                .body(TransactionBody.builder().txHash(TX_C).inputs(inputs())
                        .outputs(List.of(TransactionOutput.builder().address(F).amounts(List.of(lovelace(1_000_000))).build()))
                        .collateralInputs(inputs(in(TX_A, 1))).build())
                .utxos(List.of())
                .collateralReturnUtxo(utxo(TX_C, 0, w, lovelace(2_500_000)))
                .invalid(true)
                .build();
        return List.of(a, b, c);
    }

    private static List<Transaction> blockTwo() {
        return List.of(valid(S2, 2, TX_D, inputs(in(TX_B, 0), in(TX_A, 2)),
                utxo(TX_D, 0, F, lovelace(6_000_000))));
    }

    // ---- readers -----------------------------------------------------------------------------------------------

    private static String name(String txHash, int index) {
        return NAMES.getOrDefault(txHash, txHash) + "#" + index;
    }

    private static Set<String> names(Collection<AddressUtxoEntity> rows) {
        var names = new TreeSet<String>();
        rows.forEach(row -> names.add(name(row.getTxHash(), row.getOutputIndex())));
        return names;
    }

    private static Set<String> unspentByCredential(UtxoRepository repository, String credential) {
        return names(repository.findUnspentByOwnerPaymentCredential(credential, Pageable.unpaged()).orElse(List.of()));
    }

    /** {@code address_utxo}, spent rows included, one line per row. */
    private static Set<String> addressUtxoRows(UtxoRepository repository) {
        var rows = new TreeSet<String>();
        for (AddressUtxoEntity row : repository.findAll()) {
            rows.add(name(row.getTxHash(), row.getOutputIndex())
                    + " credential=" + row.getOwnerPaymentCredential()
                    + " lovelace=" + row.getLovelaceAmount()
                    + " slot=" + row.getSlot()
                    + " block=" + row.getBlockNumber()
                    + " collateralReturn=" + Boolean.TRUE.equals(row.getIsCollateralReturn()));
        }
        return rows;
    }

    /** {@code tx_input}, one line per recorded spend. */
    private static Set<String> txInputRows(TxInputRepository repository) {
        var rows = new TreeSet<String>();
        for (TxInputEntity row : repository.findAll()) {
            rows.add(name(row.getTxHash(), row.getOutputIndex())
                    + " spentBy=" + NAMES.getOrDefault(row.getSpentTxHash(), row.getSpentTxHash())
                    + " slot=" + row.getSpentAtSlot());
        }
        return rows;
    }

    private static Set<String> cacheContents(UtxoCache cache) {
        var names = new TreeSet<String>();
        cache.getAll().forEach(u -> names.add(name(u.getTxHash(), u.getOutputIndex())));
        return names;
    }

    private static Set<String> expectedRowsAfterBlockOne(String walletPkh) {
        return new TreeSet<>(List.of(
                "A#0 credential=" + walletPkh + " lovelace=2000000 slot=" + S1 + " block=1 collateralReturn=false",
                "A#2 credential=" + O_SCRIPT + " lovelace=4000000 slot=" + S1 + " block=1 collateralReturn=false",
                "A#3 credential=" + POOL_PKH + " lovelace=5000000 slot=" + S1 + " block=1 collateralReturn=false",
                "B#0 credential=" + walletPkh + " lovelace=1800000 slot=" + S1 + " block=1 collateralReturn=false",
                "C#1 credential=" + walletPkh + " lovelace=2500000 slot=" + S1 + " block=1 collateralReturn=true"));
    }

    private static final Set<String> SPENDS_AFTER_BLOCK_ONE = Set.of("A#0 spentBy=B slot=" + S1);

    // ---- the pins ----------------------------------------------------------------------------------------------

    @Test
    void blockOneThroughYacisOwnProcessorPinsWhatIsHandedKeptSpentCachedAndRead() {
        runner().run(context -> {
            World world = world(context);
            var cacheBeforeCommitEvent = new ArrayList<Set<String>>();
            var cacheSizeAfterCommitEvent = new ArrayList<Integer>();
            var probeRunsInsideTransaction = new ArrayList<List<String>>();

            block(world, metadata(S1, 1, BLOCK_HASH_1), blockOne(world.w()),
                    () -> cacheBeforeCommitEvent.add(cacheContents(world.cache())),
                    () -> {
                        cacheSizeAfterCommitEvent.add(world.cache().size());
                        probeRunsInsideTransaction.add(world.probe().runs());
                    });

            // P1 — what the storage is handed: one saveSpent with every input (collateral included), in transaction
            // order, then one saveUnspent with every output, the collateral return re-indexed to 1.
            assertEquals(List.of(List.of("X#0", "A#0", "A#1")), world.storage().spentCalls(),
                    "P1: saveSpent is handed every input of block 1, in transaction order, once");
            assertEquals(List.of(List.of("A#0", "A#1", "A#2", "A#3", "B#0", "C#1 collateral-return")),
                    world.storage().unspentCalls(),
                    "P1: saveUnspent is handed every output of block 1, in transaction order, once");

            // P2 — the rows: A#1 (unwatched F) is never stored, so neither is its collateral spend; X#0 was never
            // indexed; A#0, created and spent in this block, is stored AND spent; A#2 is kept for its feed NFT alone.
            assertEquals(expectedRowsAfterBlockOne(world.walletPkh()), addressUtxoRows(world.utxoRepository()),
                    "P2: address_utxo after block 1");
            assertEquals(SPENDS_AFTER_BLOCK_ONE, txInputRows(world.txInputRepository()),
                    "P2: tx_input after block 1 holds only the same-block spend of A#0");

            // P3 — the cache: the five kept outputs between the two publishes, empty once CommitEvent is handled.
            assertEquals(List.of(Set.of("A#0", "A#2", "A#3", "B#0", "C#1")), cacheBeforeCommitEvent,
                    "P3: inside the block's transaction, before CommitEvent, the UtxoCache holds the five kept outputs");
            assertEquals(List.of(0), cacheSizeAfterCommitEvent, "P3: CommitEvent clears the UtxoCache");
            assertEquals(0, world.cache().size(), "P3: the UtxoCache is still empty after the commit");

            // P4 — an after-commit consumer: never inside the transaction, once after it, seeing the committed rows.
            assertEquals(List.of(List.of()), probeRunsInsideTransaction,
                    "P4: the AFTER_COMMIT probe has not run while the block's transaction is open");
            assertEquals(List.of(S1 + " [B#0, C#1]"), world.probe().runs(),
                    "P4: the AFTER_COMMIT probe ran once for block 1 and read the wallet's committed unspent outputs");
            assertEquals(S1, world.blockEventListener().getLastCommittedSlot().get(),
                    "P4: BlockEventListener's committed slot is block 1's");

            // P5 — the node's reads.
            assertEquals(Set.of("B#0", "C#1"), unspentByCredential(world.utxoRepository(), world.walletPkh()),
                    "P5: the wallet's unspent outputs by payment credential");
            assertEquals(Set.of("A#3"), unspentByCredential(world.utxoRepository(), POOL_PKH),
                    "P5: the pool's unspent outputs by payment credential");
            assertEquals(Set.of("B#0", "C#1"), names(world.utxoRepository()
                            .findUnspentByOwnerAddr(world.w(), Pageable.unpaged()).orElse(List.of())),
                    "P5: the wallet's unspent outputs by address");
            assertEquals(Answer.live(), world.storage().lookup(
                            new com.bloxbean.cardano.client.transaction.spec.TransactionInput(TX_A, 2), FEED_NFT),
                    "P5: the feed NFT output A#2 is LIVE in the index");

            UtxoSupplier provider = mock(UtxoSupplier.class);
            var supplier = new IndexFirstUtxoSupplier(world.utxoRepository(),
                    world.storage()::indexedPaymentCredentials, provider, Set::of);
            assertEquals(List.of("B#0 lovelace=1800000", "C#1 lovelace=2500000"),
                    supplier.getPage(world.w(), 100, 0, OrderEnum.asc).stream()
                            .map(u -> name(u.getTxHash(), u.getOutputIndex()) + " " + amounts(u))
                            .toList(),
                    "P5: IndexFirstUtxoSupplier.getPage(W) answers the wallet's two unspent outputs from the index");
            var pool = supplier.getTxOutput(TX_A, 3).orElseThrow();
            assertEquals("A#3 " + P + " lovelace=5000000 " + POOL_TOKEN.toUnit() + "=1",
                    name(pool.getTxHash(), pool.getOutputIndex()) + " " + pool.getAddress() + " " + amounts(pool),
                    "P5: IndexFirstUtxoSupplier.getTxOutput(A#3) answers the pool output, token included");
            verifyNoInteractions(provider);
        });
    }

    @Test
    void blockTwoSpendsAnOracleOutputAndAWalletOutputAndStoresNothingUnwatched() {
        runner().run(context -> {
            World world = world(context);
            block(world, metadata(S1, 1, BLOCK_HASH_1), blockOne(world.w()));
            block(world, metadata(S2, 2, BLOCK_HASH_2), blockTwo());

            assertEquals(new TreeSet<>(List.of("A#0 spentBy=B slot=" + S1, "A#2 spentBy=D slot=" + S2,
                            "B#0 spentBy=D slot=" + S2)), txInputRows(world.txInputRepository()),
                    "P6: tx_input gains B#0 and A#2, both spent by D at block 2's slot");
            assertEquals(expectedRowsAfterBlockOne(world.walletPkh()), addressUtxoRows(world.utxoRepository()),
                    "P6: D#0 (to the unwatched F) is not stored; address_utxo is unchanged");
            assertEquals(Set.of("C#1"), unspentByCredential(world.utxoRepository(), world.walletPkh()),
                    "P6: the wallet's only unspent output is the collateral return");
            assertEquals(Answer.spent(TX_D), world.storage().lookup(
                            new com.bloxbean.cardano.client.transaction.spec.TransactionInput(TX_A, 2), FEED_NFT),
                    "P6: the feed NFT output A#2 is SPENT, naming D");
            assertEquals(List.of(S1 + " [B#0, C#1]", S2 + " [C#1]"), world.probe().runs(),
                    "P6: the AFTER_COMMIT probe ran once per block");
            assertEquals(S2, world.blockEventListener().getLastCommittedSlot().get(),
                    "P6: BlockEventListener's committed slot is block 2's");
        });
    }

    @Test
    void aRollbackToBlockOneRestoresBlockTwosSpends() {
        runner().run(context -> {
            World world = world(context);
            block(world, metadata(S1, 1, BLOCK_HASH_1), blockOne(world.w()));
            block(world, metadata(S2, 2, BLOCK_HASH_2), blockTwo());

            world.inTx().executeWithoutResult(status -> world.context().publishEvent(RollbackEvent.builder()
                    .rollbackTo(new Point(S1, BLOCK_HASH_1))
                    .currentPoint(new Point(S2, BLOCK_HASH_2))
                    .currentBlock(2)
                    .build()));

            assertEquals(SPENDS_AFTER_BLOCK_ONE, txInputRows(world.txInputRepository()),
                    "P7: the rollback removes block 2's spends and keeps block 1's");
            assertEquals(expectedRowsAfterBlockOne(world.walletPkh()), addressUtxoRows(world.utxoRepository()),
                    "P7: address_utxo is unchanged from P2");
            assertEquals(Set.of("B#0", "C#1"), unspentByCredential(world.utxoRepository(), world.walletPkh()),
                    "P7: the wallet's unspent outputs are block 1's again");
            assertEquals(Answer.live(), world.storage().lookup(
                            new com.bloxbean.cardano.client.transaction.spec.TransactionInput(TX_A, 2), FEED_NFT),
                    "P7: the feed NFT output A#2 is LIVE again");
        });
    }

    private static String amounts(com.bloxbean.cardano.client.api.model.Utxo utxo) {
        return String.join(" ", utxo.getAmount().stream().map(a -> a.getUnit() + "=" + a.getQuantity()).toList());
    }
}
