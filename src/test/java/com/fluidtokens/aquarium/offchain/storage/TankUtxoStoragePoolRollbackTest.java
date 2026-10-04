package com.fluidtokens.aquarium.offchain.storage;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.domain.TxInput;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.UtxoCache;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.TxInputRepository;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.ParametersContractService;
import com.fluidtokens.aquarium.offchain.service.StakerContractService;
import com.fluidtokens.aquarium.offchain.service.TankContractService;
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
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-137 T2a: Minswap pool rows are stored, spent and ROLLED BACK by Yaci's own tables and
 * Yaci's own rollback path, exactly like wallet rows.
 *
 * <h2>Why a real JPA context</h2>
 * Yaci 0.1.7's {@code UtxoRollbackProcessor} calls {@code deleteUnspentBySlotGreaterThan} and
 * {@code deleteSpentBySlotGreaterThan}; {@code UtxoStorageImpl} delegates those to the Spring Data
 * derived deletes {@link UtxoRepository#deleteBySlotGreaterThan} and
 * {@link TxInputRepository#deleteBySpentAtSlotGreaterThan}, and the readers use the JPQL
 * {@code findUnspentByOwnerPaymentCredential} (a LEFT JOIN on {@code tx_input}). A mocked repository
 * would prove only the delegation. Here the repositories are the real Spring Data proxies over a
 * Hibernate {@code EntityManagerFactory} on H2, with the schema from Yaci's own Flyway location
 * ({@code classpath:db/store/h2}) — so the test shows the rows carry no credential-specific handling
 * anywhere between write, spend and rollback.
 * <p>
 * The rollback calls run inside a transaction, standing in for the {@code @Transactional} proxy that
 * Spring puts around the storage bean in production (this test constructs it directly).
 */
class TankUtxoStoragePoolRollbackTest {

    private static final String POOL_PKH = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String STAKE_1 = "c1".repeat(28);
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

    private static String tx(int n) {
        return String.format("%064x", n);
    }

    private static AddressUtxo output(String txHash, String ownerPkh, String stakePkh, long slot) {
        return AddressUtxo.builder()
                .txHash(txHash)
                .outputIndex(0)
                .slot(slot)
                .blockNumber(slot)
                .blockHash("bb".repeat(32))
                .ownerAddr("addr_test1_fixture")
                .ownerPaymentCredential(ownerPkh)
                .ownerStakeCredential(stakePkh)
                .lovelaceAmount(BigInteger.valueOf(2_000_000L))
                .amounts(new ArrayList<>())
                .build();
    }

    private static TxInput spend(String txHash, String spentBy, long slot) {
        return TxInput.builder()
                .txHash(txHash)
                .outputIndex(0)
                .spentAtSlot(slot)
                .spentAtBlock(slot)
                .spentAtBlockHash("cc".repeat(32))
                .spentTxHash(spentBy)
                .build();
    }

    private static List<String> unspent(UtxoRepository repository, String pkh) {
        return repository.findUnspentByOwnerPaymentCredential(pkh, Pageable.unpaged()).orElse(List.of())
                .stream().map(e -> e.getTxHash() + "#" + e.getOutputIndex()).toList();
    }

    @Test
    void aPoolRowIsStoredSpentAndRolledBackExactlyLikeAWalletRow() {
        runner.run(context -> {
            assertEquals(null, context.getStartupFailure(), "the JPA seam must start");
            DataSource dataSource = context.getBean(DataSource.class);
            Flyway.configure().dataSource(dataSource).locations("classpath:db/store/h2").load().migrate();
            DSLContext dsl = DSL.using(dataSource, SQLDialect.H2);
            UtxoRepository utxoRepository = context.getBean(UtxoRepository.class);
            TxInputRepository txInputRepository = context.getBean(TxInputRepository.class);
            PlatformTransactionManager txManager = context.getBean(PlatformTransactionManager.class);
            var inTx = new TransactionTemplate(txManager);

            var account = new Account(Networks.testnet());
            String walletPkh = account.getBaseAddress().getPaymentCredentialHash()
                    .map(HexUtil::encodeHexString).get();
            var parameters = mock(ParametersContractService.class);
            when(parameters.getScriptHashHex()).thenReturn("a1".repeat(28));
            var staker = mock(StakerContractService.class);
            when(staker.getScriptHashHex()).thenReturn("a2".repeat(28));
            var tank = mock(TankContractService.class);
            when(tank.getScriptHashHex()).thenReturn("a3".repeat(28));
            @SuppressWarnings("unchecked")
            ObjectProvider<LoansContractRegistry> noRegistry = mock(ObjectProvider.class);
            var storage = new TankUtxoStorage(utxoRepository, txInputRepository, dsl, new UtxoCache(), txManager,
                    account, parameters, staker, tank, noRegistry, POOL_PKH);
            storage.init();

            String poolOut = tx(1), walletOut = tx(2), poolSpender = tx(3), walletSpender = tx(4);

            // (a) Block at slot S: a pool output and a wallet output.
            storage.saveSpent(List.of());
            storage.saveUnspent(List.of(output(poolOut, POOL_PKH, STAKE_1, S), output(walletOut, walletPkh, null, S)));
            assertEquals(List.of(poolOut + "#0"), unspent(utxoRepository, POOL_PKH),
                    "(a) the real findUnspentByOwnerPaymentCredential must return the stored pool row");
            assertEquals(List.of(walletOut + "#0"), unspent(utxoRepository, walletPkh));

            // (b) Block at S+1 spends both. saveSpent finds the outputs through the REAL findById.
            storage.saveSpent(List.of(spend(poolOut, poolSpender, S + 1), spend(walletOut, walletSpender, S + 1)));
            storage.saveUnspent(List.of());
            assertTrue(unspent(utxoRepository, POOL_PKH).isEmpty(), "(b) a spent pool row must not read unspent");
            assertTrue(unspent(utxoRepository, walletPkh).isEmpty(), "(b) a spent wallet row must not read unspent");
            assertEquals(2, txInputRepository.count(), "(b) both spends are recorded in tx_input");

            // (c) Roll back to S, the way UtxoRollbackProcessor does: the S+1 spends go, the S outputs stay.
            inTx.executeWithoutResult(status -> {
                assertEquals(0, storage.deleteUnspentBySlotGreaterThan(S), "(c) no output above S");
                assertEquals(2, storage.deleteSpentBySlotGreaterThan(S), "(c) both S+1 spends are undone");
            });
            assertEquals(List.of(poolOut + "#0"), unspent(utxoRepository, POOL_PKH),
                    "(c) after the rollback the pool row is unspent again");
            assertEquals(List.of(walletOut + "#0"), unspent(utxoRepository, walletPkh),
                    "(c) ...exactly like the wallet row spent and rolled back the same way");

            // (d) Roll back to S-1: the block that created both outputs is undone, so both rows go.
            inTx.executeWithoutResult(status -> {
                assertEquals(2, storage.deleteUnspentBySlotGreaterThan(S - 1), "(d) both S outputs are undone");
                assertEquals(0, storage.deleteSpentBySlotGreaterThan(S - 1));
            });
            assertEquals(0, utxoRepository.count(), "(d) no address_utxo row survives a rollback below its block");
            assertEquals(0, txInputRepository.count(), "(d) no tx_input row survives either");
            assertTrue(unspent(utxoRepository, POOL_PKH).isEmpty());
            assertTrue(unspent(utxoRepository, walletPkh).isEmpty());
        });
    }
}
