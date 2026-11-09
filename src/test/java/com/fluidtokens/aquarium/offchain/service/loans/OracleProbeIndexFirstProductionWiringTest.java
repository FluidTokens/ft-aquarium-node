package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.common.domain.TxInput;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.UtxoCache;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.TxInputRepository;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.config.YaciConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.ParametersContractService;
import com.fluidtokens.aquarium.offchain.service.StakerContractService;
import com.fluidtokens.aquarium.offchain.service.StaticReferenceInputs;
import com.fluidtokens.aquarium.offchain.service.TankContractService;
import com.fluidtokens.aquarium.offchain.service.loans.OracleReferenceInputProbe.OracleReferenceInputNotLiveException;
import com.fluidtokens.aquarium.offchain.storage.TankUtxoStorage;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-196-3: <b>the oracle probe production builds hold reads the node's index first</b> — proven through the
 * bean methods Spring calls, over Yaci's real tables and the real {@link TankUtxoStorage}.
 *
 * <h2>Why this shape</h2>
 * The probe and the index are each proven alone ({@code OracleReferenceInputProbeIndexFirstTest} over a stub
 * index, {@code TankUtxoStorageOracleOutRefIndexTest} over the real tables). Neither proves that what production
 * builds joins them: that {@code YaciConfig.oracleReferenceInputProbe} hands the probe the storage, that the
 * storage's write-time filter keeps a feed at an address no watched credential owns, and that the
 * {@code YaciConfig.utxoSupplier} bean resolves the feed's address from the same rows. Here every one of those is
 * the production object: the {@code UtxoSupplier} is {@code new YaciConfig().utxoSupplier(...)} (static set
 * empty), the probe is {@code new YaciConfig().oracleReferenceInputProbe(utxoSupplier, backend, storage)}, and the
 * storage is configured with the mainnet value of {@code loans.oracle.indexed-nft-policies}. Blockfrost is a
 * mocked {@link UtxoService} behind a mocked {@link BFBackendService}, so every call it receives is counted.
 * <p>
 * The seam is {@code TankUtxoStoragePoolRollbackTest}'s: the real Spring Data repositories over Hibernate on H2,
 * with the schema from Yaci's own Flyway location ({@code classpath:db/store/h2}), and blocks fed through the
 * storage's own {@code saveSpent} / {@code saveUnspent}, inputs first, as Yaci 0.1.7's {@code UtxoProcessor}
 * writes them. Pruning is Yaci's own {@code deleteBySpentAndBlockLessThan}, which runs on H2 as it is.
 *
 * <h2>The cases</h2>
 * <ul>
 *   <li>(a) a feed the index holds unspent: live, with NO Blockfrost call of any kind;</li>
 *   <li>(b) a later block spends it: the SPENT refusal naming the spender, not a wait, carrying no executor
 *       marker, again with no Blockfrost call;</li>
 *   <li>(c) then pruned: the index no longer knows, so Blockfrost decides exactly as before FAB-196 — one
 *       {@code getTxOutput} for the content, one {@code getUtxos} for the liveness, and the 404 text unchanged;</li>
 *   <li>(d) the registry names {@code tx#1} while the index holds the NFT at {@code tx#0}: the same two calls,
 *       never LIVE;</li>
 *   <li>(e) a feed whose NFT is under a policy that is not configured (v1): never stored, the same two calls;</li>
 *   <li>(f) a row kept only for its oracle NFT is never a coin-selection UTxO: the supplier refuses its
 *       address.</li>
 * </ul>
 */
class OracleProbeIndexFirstProductionWiringTest {

    /** The mainnet value of {@code loans.oracle.indexed-nft-policies}: the Lending v4 feed policy. */
    private static final String V2_FEED_POLICY = "26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26";

    /** A v1 feed policy — not configured, so its outputs are never stored. */
    private static final String V1_FEED_POLICY = "93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b";

    private static final AssetType FEED_NFT = new AssetType(V2_FEED_POLICY, "4f52434c");

    private static final AssetType V1_NFT = new AssetType(V1_FEED_POLICY, "6f7261636c65464c44544333");

    private static final AssetType PRICED = new AssetType("c0".repeat(28), "544f4b");

    /** The feed's script credential: owned by no watched credential, so only the policy clause can keep it. */
    private static final String FEED_SCRIPT_HASH = "f0".repeat(28);

    private static final String FEED_ADDRESS = AddressProvider.getEntAddress(
            Credential.fromScript(HexUtil.decodeHexString(FEED_SCRIPT_HASH)), Networks.mainnet()).toBech32();

    private static final long S = 5_000L;

    @Configuration
    @EntityScan(basePackageClasses = AddressUtxoEntity.class)
    @EnableJpaRepositories(basePackageClasses = UtxoRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {UtxoRepository.class, TxInputRepository.class}))
    static class Jpa {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class, TransactionAutoConfiguration.class))
            .withUserConfiguration(Jpa.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:" + UUID.randomUUID()
                            + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=",
                    "spring.jpa.hibernate.ddl-auto=none",
                    "spring.jpa.open-in-view=false");

    /** The production objects over one fresh database, and the Blockfrost mock behind them. */
    private record Wiring(TankUtxoStorage storage, UtxoRepository utxoRepository, TxInputRepository txInputRepository,
                          UtxoSupplier utxoSupplier, OracleReferenceInputProbe probe, UtxoService blockfrost) {
    }

    private interface WiringTest {
        void run(Wiring wiring) throws Exception;
    }

    private void withWiring(WiringTest test) {
        runner.run(context -> {
            assertEquals(null, context.getStartupFailure(), "the JPA seam must start");
            DataSource dataSource = context.getBean(DataSource.class);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/store/h2").load().migrate();
            DSLContext dsl = DSL.using(dataSource, SQLDialect.H2);
            UtxoRepository utxoRepository = context.getBean(UtxoRepository.class);
            TxInputRepository txInputRepository = context.getBean(TxInputRepository.class);
            PlatformTransactionManager txManager = context.getBean(PlatformTransactionManager.class);

            var parameters = mock(ParametersContractService.class);
            when(parameters.getScriptHashHex()).thenReturn("a1".repeat(28));
            var staker = mock(StakerContractService.class);
            when(staker.getScriptHashHex()).thenReturn("a2".repeat(28));
            var tank = mock(TankContractService.class);
            when(tank.getScriptHashHex()).thenReturn("a3".repeat(28));
            @SuppressWarnings("unchecked")
            ObjectProvider<LoansContractRegistry> noRegistry = mock(ObjectProvider.class);
            var storage = new TankUtxoStorage(utxoRepository, txInputRepository, dsl, new UtxoCache(), txManager,
                    new Account(Networks.mainnet()), parameters, staker, tank, noRegistry, "", V2_FEED_POLICY);
            storage.init();
            assertFalse(storage.indexedPaymentCredentials().contains(FEED_SCRIPT_HASH),
                    "fixture: the feed's credential must not be watched, or the credential clause would keep it");

            UtxoService blockfrost = mock(UtxoService.class);
            BFBackendService backend = mock(BFBackendService.class);
            when(backend.getUtxoService()).thenReturn(blockfrost);
            StaticReferenceInputs staticReferenceInputs = mock(StaticReferenceInputs.class);
            when(staticReferenceInputs.current()).thenReturn(Set.of());

            UtxoSupplier utxoSupplier = new YaciConfig().utxoSupplier(utxoRepository, storage, backend,
                    staticReferenceInputs);
            OracleReferenceInputProbe probe = new YaciConfig().oracleReferenceInputProbe(utxoSupplier, backend, storage);

            test.run(new Wiring(storage, utxoRepository, txInputRepository, utxoSupplier, probe, blockfrost));
        });
    }

    private static String tx(int n) {
        return String.format("%064x", n);
    }

    /** As Yaci 0.1.7 builds it: {@code unit} = policy id + hex asset name, {@code policyId} set from the chain. */
    private static Amt token(AssetType asset) {
        return Amt.builder()
                .unit(asset.toUnit())
                .policyId(asset.policyId())
                .assetName(asset.assetName())
                .quantity(BigInteger.ONE)
                .build();
    }

    /** A feed output at {@link #FEED_ADDRESS} holding {@code nft}, as Yaci hands it to {@code saveUnspent}. */
    private static AddressUtxo feedOutput(String txHash, int index, AssetType nft, long slot) {
        return AddressUtxo.builder()
                .txHash(txHash)
                .outputIndex(index)
                .slot(slot)
                .blockNumber(slot)
                .blockHash("bb".repeat(32))
                .ownerAddr(FEED_ADDRESS)
                .ownerPaymentCredential(FEED_SCRIPT_HASH)
                .lovelaceAmount(BigInteger.valueOf(2_000_000L))
                .amounts(new ArrayList<>(List.of(
                        Amt.builder().unit("lovelace").quantity(BigInteger.valueOf(2_000_000L)).build(),
                        token(nft))))
                .build();
    }

    private static TxInput spend(String txHash, int index, String spentBy, long slot) {
        return TxInput.builder()
                .txHash(txHash)
                .outputIndex(index)
                .spentAtSlot(slot)
                .spentAtBlock(slot)
                .spentAtBlockHash("cc".repeat(32))
                .spentTxHash(spentBy)
                .build();
    }

    /** One block, the way Yaci 0.1.7's {@code UtxoProcessor} writes it: every input, then every output. */
    private static void block(TankUtxoStorage storage, List<TxInput> inputs, List<AddressUtxo> outputs) {
        storage.saveSpent(inputs);
        storage.saveUnspent(outputs);
    }

    /** A multisig, {@code oracleVersion} 2 entry — the mainnet Lending v4 shape: a feed out-ref and no provider. */
    private static OracleEntry multisigV2(AssetType oracleNft, TransactionInput feedRef) {
        return new OracleEntry(PRICED, oracleNft, "", "a0".repeat(28), feedRef, LoanFixtures.input("9b".repeat(32), 0),
                List.of("00".repeat(32)), 1, OraclePriceFeed.aggregated(PRICED, BigInteger.ONE, BigInteger.ONE,
                0L, 1L), List.of(), null, 2, null);
    }

    /** What Blockfrost's {@code getTxOutput} answers for an out-ref: its content, holding {@code nft}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Result<Utxo> content(String txHash, int index, AssetType nft) {
        Utxo utxo = LoanFixtures.utxo(txHash, index, FEED_ADDRESS,
                List.of(Amount.lovelace(BigInteger.valueOf(2_000_000)), LoanFixtures.token(nft, 1)), null);
        return (Result<Utxo>) Result.success("ok").code(200).withValue(utxo);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Result<List<Utxo>> live(Utxo... utxos) {
        return (Result<List<Utxo>>) Result.success("ok").code(200).withValue(new ArrayList<>(List.of(utxos)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Result<List<Utxo>> failed(int code, String body) {
        return (Result<List<Utxo>>) Result.error(body).code(code);
    }

    /** The refusal, checked for the executor's spent-input markers exactly as the executor would read it. */
    private static OracleReferenceInputNotLiveException refused(Runnable call) {
        var e = assertThrows(OracleReferenceInputNotLiveException.class, call::run);
        String compact = e.getMessage().replaceAll("\\s", "");
        assertFalse(compact.contains("BadInputs"), "a probe refusal carries BadInputs: " + e.getMessage());
        assertFalse(compact.contains("ScriptFailures"), "a probe refusal carries ScriptFailures: " + e.getMessage());
        assertFalse(compact.contains(LiquidationExecutor.BAD_INPUTS_MARKER), e.getMessage());
        assertFalse(compact.contains(LiquidationExecutor.EMPTY_SCRIPT_FAILURES_MARKER), e.getMessage());
        return e;
    }

    /** Blockfrost was asked exactly once for the content and once for the liveness of {@code outRef}, and nothing else. */
    private static void askedBlockfrostOnce(UtxoService blockfrost, String txHash, int index, AssetType nft)
            throws Exception {
        verify(blockfrost, times(1)).getTxOutput(txHash, index);
        verify(blockfrost, times(1)).getUtxos(FEED_ADDRESS, nft.toUnit(), 100, 1);
        verifyNoMoreInteractions(blockfrost);
    }

    // ---- (a)-(b) the index answers: no Blockfrost call --------------------------------------------------------

    /** (a) The feed stored unspent: live, and Blockfrost is asked nothing — neither content nor liveness. */
    @Test
    void aFeedTheIndexHoldsUnspentIsLiveWithNoBlockfrostCall() {
        withWiring(w -> {
            String feed = tx(1);
            block(w.storage(), List.of(), List.of(feedOutput(feed, 0, FEED_NFT, S)));

            assertDoesNotThrow(() -> w.probe().requireLive(List.of(multisigV2(FEED_NFT, LoanFixtures.input(feed, 0)))));

            verifyNoInteractions(w.blockfrost());
        });
    }

    /** (b) A later block spends it: the index's SPENT refusal, not a wait, no executor marker, no Blockfrost call. */
    @Test
    void aFeedTheIndexRecordedSpentIsRefusedWithNoBlockfrostCall() {
        withWiring(w -> {
            String feed = tx(1), spender = tx(2);
            block(w.storage(), List.of(), List.of(feedOutput(feed, 0, FEED_NFT, S)));
            block(w.storage(), List.of(spend(feed, 0, spender, S + 1)), List.of());

            var e = refused(() -> w.probe().requireLive(List.of(multisigV2(FEED_NFT, LoanFixtures.input(feed, 0)))));

            assertEquals("oracle out-ref " + feed + "#0 (expected NFT " + FEED_NFT.toUnit() + " at " + FEED_ADDRESS
                    + "): spent in transaction " + spender + ", as recorded by this node's index", e.getMessage());
            assertFalse(e.notYetVisible(), "a recorded spend is a refusal, never a wait");
            verifyNoInteractions(w.blockfrost());
        });
    }

    // ---- (c)-(e) the index does not know: Blockfrost decides, as before ----------------------------------------

    /**
     * (c) Spent, then pruned by Yaci's own {@code deleteBySpentAndBlockLessThan} — both rows go, so the index no
     * longer knows. The content comes from Blockfrost ({@code getTxOutput}, once) and so does the liveness
     * ({@code getUtxos}, once), and a 404 gives the text pinned before FAB-196.
     */
    @Test
    void aSpentFeedOncePrunedGoesBackToBlockfrost() {
        withWiring(w -> {
            String feed = tx(1), spender = tx(2);
            block(w.storage(), List.of(), List.of(feedOutput(feed, 0, FEED_NFT, S)));
            block(w.storage(), List.of(spend(feed, 0, spender, S + 1)), List.of());
            assertEquals(2, w.storage().deleteBySpentAndBlockLessThan(S + 2), "the address_utxo and tx_input rows");
            assertEquals(0, w.utxoRepository().count());
            assertEquals(0, w.txInputRepository().count());

            when(w.blockfrost().getTxOutput(feed, 0)).thenReturn(content(feed, 0, FEED_NFT));
            when(w.blockfrost().getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1))
                    .thenReturn(failed(404, "{\"status_code\":404,\"error\":\"Not Found\"}"));

            var e = refused(() -> w.probe().requireLive(List.of(multisigV2(FEED_NFT, LoanFixtures.input(feed, 0)))));

            assertEquals("oracle out-ref " + feed + "#0 (expected NFT " + FEED_NFT.toUnit() + " at " + FEED_ADDRESS
                    + "): spent, or the NFT is not at " + FEED_ADDRESS + " (404)", e.getMessage());
            assertFalse(e.notYetVisible());
            askedBlockfrostOnce(w.blockfrost(), feed, 0, FEED_NFT);
        });
    }

    /** (d) The registry names {@code tx#1}; the index holds the NFT at {@code tx#0}. Never LIVE: Blockfrost decides. */
    @Test
    void anOutRefAtAnotherIndexThanTheIndexedFeedGoesToBlockfrostAndIsNeverLive() {
        withWiring(w -> {
            String feed = tx(1);
            block(w.storage(), List.of(), List.of(feedOutput(feed, 0, FEED_NFT, S)));

            when(w.blockfrost().getTxOutput(feed, 1)).thenReturn(content(feed, 1, FEED_NFT));
            when(w.blockfrost().getUtxos(FEED_ADDRESS, FEED_NFT.toUnit(), 100, 1))
                    .thenReturn(live(content(feed, 0, FEED_NFT).getValue()));

            var e = refused(() -> w.probe().requireLive(List.of(multisigV2(FEED_NFT, LoanFixtures.input(feed, 1)))));

            assertEquals("oracle out-ref " + feed + "#1 (expected NFT " + FEED_NFT.toUnit() + " at " + FEED_ADDRESS
                    + "): spent: the NFT now sits at [" + feed + "#0]", e.getMessage());
            assertFalse(e.notYetVisible());
            askedBlockfrostOnce(w.blockfrost(), feed, 1, FEED_NFT);
        });
    }

    /** (e) A feed whose NFT is under a policy that is not configured (v1) is never stored: Blockfrost decides. */
    @Test
    void aFeedUnderAPolicyThatIsNotConfiguredIsNeverStoredAndGoesToBlockfrost() {
        withWiring(w -> {
            String v1Feed = tx(1);
            block(w.storage(), List.of(), List.of(feedOutput(v1Feed, 0, V1_NFT, S)));
            assertTrue(w.utxoRepository().findById(new UtxoId(v1Feed, 0)).isEmpty(), "fixture: not stored");

            when(w.blockfrost().getTxOutput(v1Feed, 0)).thenReturn(content(v1Feed, 0, V1_NFT));
            when(w.blockfrost().getUtxos(FEED_ADDRESS, V1_NFT.toUnit(), 100, 1))
                    .thenReturn(live(content(v1Feed, 0, V1_NFT).getValue()));

            assertDoesNotThrow(() -> w.probe().requireLive(List.of(multisigV2(V1_NFT, LoanFixtures.input(v1Feed, 0)))));

            askedBlockfrostOnce(w.blockfrost(), v1Feed, 0, V1_NFT);
        });
    }

    // ---- (f) a policy-kept row is not a coin-selection UTxO ----------------------------------------------------

    /**
     * (f) The feed row is in the index only because it holds an oracle NFT; its address is owned by no watched
     * credential. The production supplier refuses that address for coin selection rather than serve it — so a
     * feed can never be selected as a wallet input.
     */
    @Test
    void aRowKeptForItsOracleNftIsNeverACoinSelectionUtxo() {
        withWiring(w -> {
            String feed = tx(1);
            block(w.storage(), List.of(), List.of(feedOutput(feed, 0, FEED_NFT, S)));
            assertTrue(w.utxoRepository().findById(new UtxoId(feed, 0)).isPresent(), "fixture: the feed is stored");

            assertThrows(IllegalStateException.class, () -> w.utxoSupplier().getAll(FEED_ADDRESS));
            assertThrows(IllegalStateException.class,
                    () -> w.utxoSupplier().getPage(FEED_ADDRESS, 10, 0, OrderEnum.asc));
            verifyNoInteractions(w.blockfrost());
        });
    }
}
