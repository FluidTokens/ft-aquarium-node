package com.fluidtokens.aquarium.offchain.storage;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.common.domain.TxInput;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.UtxoCache;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.TxInputRepository;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.ParametersContractService;
import com.fluidtokens.aquarium.offchain.service.StakerContractService;
import com.fluidtokens.aquarium.offchain.service.TankContractService;
import com.fluidtokens.aquarium.offchain.service.loans.OracleOutRefIndex.Answer;
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
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-196-2: {@link TankUtxoStorage} as an {@code OracleOutRefIndex} — LIVE, SPENT or UNKNOWN for one oracle
 * out-ref and the NFT expected there, read from Yaci's own two tables.
 *
 * <h2>Why a real JPA context</h2>
 * The answer is two repository reads, {@code TxInputRepository.findById} then {@code UtxoRepository.findById},
 * against rows written by the storage's own {@code saveSpent}/{@code saveUnspent} and removed by Yaci's own
 * rollback and pruning paths. A mocked repository would prove only the delegation; here they are the real
 * Spring Data proxies over Hibernate on H2, with the schema from Yaci's own Flyway location
 * ({@code classpath:db/store/h2}) — the {@link TankUtxoStoragePoolRollbackTest} seam. In particular the
 * {@code amounts} column is a hypersistence {@code JsonType}, so the NFT check reads what really round-trips.
 * <p>
 * Rollback runs inside a transaction, standing in for the {@code @Transactional} proxy Spring puts around the
 * storage bean in production; pruning is Yaci's own {@code deleteBySpentAndBlockLessThan}, which opens its own.
 */
class TankUtxoStorageOracleOutRefIndexTest {

    /** The mainnet base document's value: the Lending v4 ({@code oracleVersion} 2) feed policy. */
    private static final String V2_FEED_POLICY = "26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26";

    /** A v1 feed policy — not configured, so its outputs are never stored. */
    private static final String V1_FEED_POLICY = "93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b";

    private static final AssetType FEED_NFT = new AssetType(V2_FEED_POLICY, "4f52434c");

    /** Another token of the SAME configured policy. */
    private static final AssetType OTHER_NFT = new AssetType(V2_FEED_POLICY, "4f52434d");

    private static final AssetType V1_NFT = new AssetType(V1_FEED_POLICY, "6f7261636c65464c44544333");

    private static final String FOREIGN_PKH = "f0".repeat(28);
    private static final String TANK_PKH = "a3".repeat(28);
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

    /** The seam, with the storage built over it. */
    private record Seam(TankUtxoStorage storage, UtxoRepository utxoRepository, TxInputRepository txInputRepository,
                        TransactionTemplate inTx) {
    }

    private interface SeamTest {
        void run(Seam seam) throws Exception;
    }

    /** Runs {@code test} on a fresh H2 database, with {@code policies} as {@code loans.oracle.indexed-nft-policies}. */
    private void withSeam(String policies, SeamTest test) {
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
            when(tank.getScriptHashHex()).thenReturn(TANK_PKH);
            @SuppressWarnings("unchecked")
            ObjectProvider<LoansContractRegistry> noRegistry = mock(ObjectProvider.class);
            var storage = new TankUtxoStorage(utxoRepository, txInputRepository, dsl, new UtxoCache(), txManager,
                    new Account(Networks.mainnet()), parameters, staker, tank, noRegistry, "", policies);
            storage.init();

            test.run(new Seam(storage, utxoRepository, txInputRepository, new TransactionTemplate(txManager)));
        });
    }

    private static String tx(int n) {
        return String.format("%064x", n);
    }

    private static Amt lovelace() {
        return Amt.builder().unit("lovelace").quantity(BigInteger.valueOf(2_000_000L)).build();
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

    private static AddressUtxo output(String txHash, int index, String ownerPkh, long slot, Amt... amounts) {
        return AddressUtxo.builder()
                .txHash(txHash)
                .outputIndex(index)
                .slot(slot)
                .blockNumber(slot)
                .blockHash("bb".repeat(32))
                .ownerAddr("addr1_fixture")
                .ownerPaymentCredential(ownerPkh)
                .lovelaceAmount(BigInteger.valueOf(2_000_000L))
                .amounts(new ArrayList<>(List.of(amounts)))
                .build();
    }

    private static AddressUtxo feedOutput(String txHash, int index, long slot) {
        return output(txHash, index, FOREIGN_PKH, slot, lovelace(), token(FEED_NFT));
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

    private static TransactionInput ref(String txHash, int index) {
        return new TransactionInput(txHash, index);
    }

    // ---- Task 2: the seam round-trips a non-empty amounts column ---------------------------------------------

    /** Measured first: a stored foreign feed output reads back through the REAL findById with its token intact. */
    @Test
    void aStoredFeedOutputsTokenRoundTripsThroughTheRealRepository() {
        withSeam(V2_FEED_POLICY, seam -> {
            String feed = tx(1);
            block(seam.storage(), List.of(), List.of(feedOutput(feed, 0, S)));

            var row = seam.utxoRepository().findById(new UtxoId(feed, 0)).orElseThrow();
            List<Amt> tokens = row.getAmounts().stream().filter(a -> !"lovelace".equals(a.getUnit())).toList();
            assertEquals(1, tokens.size(), "the stored row carries exactly the one token: " + row.getAmounts());
            assertEquals(FEED_NFT.toUnit(), tokens.get(0).getUnit());
            assertEquals(V2_FEED_POLICY, tokens.get(0).getPolicyId());
        });
    }

    // ---- (a)-(d) LIVE needs exactly that out-ref holding exactly that NFT ---------------------------------------

    /** (a) A stored foreign feed output holding the NFT is LIVE. */
    @Test
    void aStoredFeedOutputHoldingTheNftIsLive() {
        withSeam(V2_FEED_POLICY, seam -> {
            String feed = tx(1);
            block(seam.storage(), List.of(), List.of(feedOutput(feed, 0, S)));

            assertEquals(Answer.live(), seam.storage().lookup(ref(feed, 0), FEED_NFT));
        });
    }

    /** (b) The same out-ref asked with another token of the same policy is UNKNOWN: Blockfrost decides. */
    @Test
    void theSameOutRefAskedWithAnotherNftIsUnknown() {
        withSeam(V2_FEED_POLICY, seam -> {
            String feed = tx(1);
            block(seam.storage(), List.of(), List.of(feedOutput(feed, 0, S)));

            assertEquals(Answer.unknown(), seam.storage().lookup(ref(feed, 0), OTHER_NFT));
        });
    }

    /** (c) The tx hash right and the index wrong — the row is at #0, the question about #1 — is UNKNOWN. */
    @Test
    void theRightTxHashAtAnotherIndexIsUnknown() {
        withSeam(V2_FEED_POLICY, seam -> {
            String feed = tx(1);
            block(seam.storage(), List.of(), List.of(feedOutput(feed, 0, S)));

            assertEquals(Answer.unknown(), seam.storage().lookup(ref(feed, 1), FEED_NFT));
            assertEquals(Answer.live(), seam.storage().lookup(ref(feed, 0), FEED_NFT), "control: #0 is live");
        });
    }

    /** (d) A registry may write the hash in uppercase; Yaci stores it lowercase. Still LIVE. */
    @Test
    void anUppercaseTxHashIsLive() {
        withSeam(V2_FEED_POLICY, seam -> {
            String feed = "ab".repeat(32);
            block(seam.storage(), List.of(), List.of(feedOutput(feed, 0, S)));

            assertEquals(Answer.live(), seam.storage().lookup(ref(feed.toUpperCase(Locale.ROOT), 0), FEED_NFT));
        });
    }

    // ---- (e)-(f) SPENT carries the spender -----------------------------------------------------------------

    /** (e) Spent in a later block: SPENT, carrying the spending transaction's hash. */
    @Test
    void spentInALaterBlockIsSpentCarryingTheSpender() {
        withSeam(V2_FEED_POLICY, seam -> {
            String feed = tx(1), spender = tx(2);
            block(seam.storage(), List.of(), List.of(feedOutput(feed, 0, S)));
            block(seam.storage(), List.of(spend(feed, 0, spender, S + 1)), List.of());

            assertEquals(Answer.spent(spender), seam.storage().lookup(ref(feed, 0), FEED_NFT));
        });
    }

    /** (f) Created and spent in ONE block (the pendingSpends path): SPENT. */
    @Test
    void createdAndSpentInOneBlockIsSpent() {
        withSeam(V2_FEED_POLICY, seam -> {
            String feed = tx(1), spender = tx(2);
            block(seam.storage(), List.of(spend(feed, 0, spender, S)),
                    List.of(feedOutput(feed, 0, S), feedOutput(spender, 0, S)));

            assertEquals(Answer.spent(spender), seam.storage().lookup(ref(feed, 0), FEED_NFT));
            assertEquals(Answer.live(), seam.storage().lookup(ref(spender, 0), FEED_NFT), "control: its successor");
        });
    }

    // ---- (g)-(h) rollback and pruning --------------------------------------------------------------------

    /** (g) The spend rolled back: LIVE again. Then the creation rolled back: UNKNOWN. */
    @Test
    void aRolledBackSpendIsLiveAgainAndARolledBackCreationIsUnknown() {
        withSeam(V2_FEED_POLICY, seam -> {
            String feed = tx(1), spender = tx(2);
            block(seam.storage(), List.of(), List.of(feedOutput(feed, 0, S)));
            block(seam.storage(), List.of(spend(feed, 0, spender, S + 1)), List.of());
            assertEquals(Answer.spent(spender), seam.storage().lookup(ref(feed, 0), FEED_NFT), "fixture: spent");

            seam.inTx().executeWithoutResult(status -> {
                assertEquals(0, seam.storage().deleteUnspentBySlotGreaterThan(S));
                assertEquals(1, seam.storage().deleteSpentBySlotGreaterThan(S), "the S+1 spend is undone");
            });
            assertEquals(Answer.live(), seam.storage().lookup(ref(feed, 0), FEED_NFT));

            seam.inTx().executeWithoutResult(status -> {
                assertEquals(1, seam.storage().deleteUnspentBySlotGreaterThan(S - 1), "the S output is undone");
                assertEquals(0, seam.storage().deleteSpentBySlotGreaterThan(S - 1));
            });
            assertEquals(Answer.unknown(), seam.storage().lookup(ref(feed, 0), FEED_NFT));
        });
    }

    /**
     * (h) Spent, then pruned by Yaci's own {@code deleteBySpentAndBlockLessThan} above the spend's block: both
     * rows go, so the index no longer knows — UNKNOWN, never LIVE.
     */
    @Test
    void spentThenPrunedIsUnknown() {
        withSeam(V2_FEED_POLICY, seam -> {
            String feed = tx(1), spender = tx(2);
            block(seam.storage(), List.of(), List.of(feedOutput(feed, 0, S)));
            block(seam.storage(), List.of(spend(feed, 0, spender, S + 1)), List.of());
            assertEquals(Answer.spent(spender), seam.storage().lookup(ref(feed, 0), FEED_NFT), "fixture: spent");

            assertEquals(2, seam.storage().deleteBySpentAndBlockLessThan(S + 2), "the address_utxo and tx_input rows");
            assertEquals(0, seam.utxoRepository().count());
            assertEquals(0, seam.txInputRepository().count());

            assertEquals(Answer.unknown(), seam.storage().lookup(ref(feed, 0), FEED_NFT));
        });
    }

    // ---- (i)-(j) what the filter kept -----------------------------------------------------------------------

    /** (i) A foreign output holding a v1 token is never stored, so the index does not know it. */
    @Test
    void aForeignV1FeedIsNeverStoredAndUnknown() {
        withSeam(V2_FEED_POLICY, seam -> {
            String v1 = tx(1);
            block(seam.storage(), List.of(), List.of(output(v1, 0, FOREIGN_PKH, S, lovelace(), token(V1_NFT))));

            assertTrue(seam.utxoRepository().findById(new UtxoId(v1, 0)).isEmpty(), "fixture: not stored");
            assertEquals(Answer.unknown(), seam.storage().lookup(ref(v1, 0), V1_NFT));
        });
    }

    /** (j) With NO policy configured, an output at a WATCHED credential holding the NFT is held by credential: LIVE. */
    @Test
    void withABlankPolicyAWatchedCredentialHoldingTheNftIsLive() {
        withSeam("", seam -> {
            String tankOut = tx(1);
            block(seam.storage(), List.of(), List.of(output(tankOut, 0, TANK_PKH, S, lovelace(), token(FEED_NFT))));

            assertEquals(Answer.live(), seam.storage().lookup(ref(tankOut, 0), FEED_NFT));
        });
    }
}
