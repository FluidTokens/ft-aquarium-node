package com.fluidtokens.aquarium.offchain.storage;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.domain.TxInput;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.UtxoCache;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.TxInputRepository;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.ParametersContractService;
import com.fluidtokens.aquarium.offchain.service.StakerContractService;
import com.fluidtokens.aquarium.offchain.service.TankContractService;
import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-134 B2b: an output created AND spent in the same block must be recorded spent.
 *
 * <h2>The defect</h2>
 * Yaci 0.1.7 {@code UtxoProcessor.handleTransactionEvent} calls, once per block, on one thread:
 * {@code saveSpent(every input of the block)} THEN {@code saveUnspent(every output of the block)}.
 * {@link TankUtxoStorage#saveSpent} keeps an input only if its output is already indexed — and an output
 * created earlier in the same block is not indexed yet when {@code saveSpent} runs. Its spend was
 * dropped, {@code saveUnspent} then stored the output, and the index held a GHOST: an output reported
 * unspent forever, which coin and collateral selection would pick and every build would fail on
 * (BadInputs), every cycle.
 *
 * <h2>Seam: real H2, real Yaci schema, real {@code UtxoStorageImpl} SQL</h2>
 * The bug lives in the interaction between our filter and the superclass's writes, so the superclass
 * must really write. The Yaci migrations run from {@code classpath:db/store/h2} (the same location
 * production names, H2 copy) and {@code UtxoStorageImpl} writes through a real jOOQ {@link DSLContext}.
 * Only the Spring Data {@link UtxoRepository} is stood in for — the real JPA repositories are exercised by
 * {@link TankUtxoStoragePoolRollbackTest} — and its {@code findById} answers from the same {@code address_utxo} table, so the filter reads
 * exactly what the superclass wrote. The unspent check mirrors {@code findUnspentByOwnerPaymentCredential}
 * (the wallet read since slice 4): {@code address_utxo LEFT JOIN tx_input ... WHERE tx_input IS NULL}.
 */
class TankUtxoStorageTest {

    private static final String FOREIGN_PKH = "ff".repeat(28);
    /** The Minswap V2 pool payment credential the base document ships (loans.minswap.pool-spend-script-hash). */
    private static final String POOL_PKH = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String STAKE_1 = "c1".repeat(28);
    private static final String STAKE_2 = "c2".repeat(28);
    private static final long SLOT = 1_000L;

    private DSLContext dsl;
    private String walletPkh;

    @BeforeEach
    void schema() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        ds.setUser("sa");
        ds.setPassword("");
        Flyway.configure().dataSource(ds).locations("classpath:db/store/h2").load().migrate();
        dsl = DSL.using(ds, SQLDialect.H2);
    }

    private TankUtxoStorage storage(UtxoCache cache) {
        return storage(cache, POOL_PKH, emptyLoans());
    }

    private TankUtxoStorage storage(UtxoCache cache, String minswapPoolSpendScriptHash,
                                    ObjectProvider<LoansContractRegistry> loans) {
        var utxoRepository = mock(UtxoRepository.class);
        when(utxoRepository.findById(any())).thenAnswer(inv -> {
            UtxoId id = inv.getArgument(0);
            return indexed(id.getTxHash(), id.getOutputIndex())
                    ? Optional.of(new AddressUtxoEntity()) : Optional.empty();
        });
        var account = new Account(Networks.testnet());
        walletPkh = account.getBaseAddress().getPaymentCredentialHash().map(HexUtil::encodeHexString).get();
        var parameters = mock(ParametersContractService.class);
        when(parameters.getScriptHashHex()).thenReturn("a1".repeat(28));
        var staker = mock(StakerContractService.class);
        when(staker.getScriptHashHex()).thenReturn("a2".repeat(28));
        var tank = mock(TankContractService.class);
        when(tank.getScriptHashHex()).thenReturn("a3".repeat(28));
        return new TankUtxoStorage(utxoRepository, mock(TxInputRepository.class), dsl, cache, null,
                account, parameters, staker, tank, loans, minswapPoolSpendScriptHash);
    }

    private TankUtxoStorage storage() {
        return storage(new UtxoCache());
    }

    private TankUtxoStorage storage(String minswapPoolSpendScriptHash, ObjectProvider<LoansContractRegistry> loans) {
        return storage(new UtxoCache(), minswapPoolSpendScriptHash, loans);
    }

    /** No registry bean at all: {@code ifAvailable} does nothing. */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<LoansContractRegistry> emptyLoans() {
        return mock(ObjectProvider.class);
    }

    /** A registry that exists but has blank coordinates — the bare-install state. */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<LoansContractRegistry> unconfiguredLoans() {
        var registry = new LoansContractRegistry("", "", "706172616d6574657273", "");
        assertFalse(registry.isConfigured(), "fixture: this registry must be the UNCONFIGURED one");
        ObjectProvider<LoansContractRegistry> provider = mock(ObjectProvider.class);
        doAnswer(inv -> {
            ((Consumer<LoansContractRegistry>) inv.getArgument(0)).accept(registry);
            return null;
        }).when(provider).ifAvailable(any());
        return provider;
    }

    // ---- reads straight off the tables -------------------------------------------------------------

    private boolean indexed(String txHash, int index) {
        return dsl.fetchExists(dsl.selectOne().from(DSL.table("address_utxo"))
                .where(DSL.field("tx_hash").eq(txHash)).and(DSL.field("output_index").eq(index)));
    }

    private boolean spentRow(String txHash, int index) {
        return dsl.fetchExists(dsl.selectOne().from(DSL.table("tx_input"))
                .where(DSL.field("tx_hash").eq(txHash)).and(DSL.field("output_index").eq(index)));
    }

    /** Same shape as Yaci's {@code findUnspentByOwnerPaymentCredential}: outputs with no spend row. */
    private List<String> unspentFor(String pkh) {
        return dsl.fetch("SELECT a.tx_hash, a.output_index FROM address_utxo a "
                        + "LEFT JOIN tx_input s ON a.tx_hash = s.tx_hash AND a.output_index = s.output_index "
                        + "WHERE a.owner_payment_credential = ? AND s.tx_hash IS NULL", pkh)
                .map(r -> r.get(0, String.class) + "#" + r.get(1, Integer.class));
    }

    private int spentRowCount() {
        return dsl.fetchCount(DSL.table("tx_input"));
    }

    // ---- fixtures ----------------------------------------------------------------------------------

    private static String tx(int n) {
        return String.format("%064x", n);
    }

    private static AddressUtxo output(String txHash, int index, String ownerPkh, long slot) {
        return output(txHash, index, ownerPkh, null, slot);
    }

    private static AddressUtxo output(String txHash, int index, String ownerPkh, String stakePkh, long slot) {
        return AddressUtxo.builder()
                .txHash(txHash)
                .outputIndex(index)
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

    private static TxInput spend(String txHash, int index, String spentBy, long slot) {
        return TxInput.builder()
                .txHash(txHash)
                .outputIndex(index)
                .spentAtSlot(slot)
                .spentAtBlock(slot)
                .spentAtBlockHash("bb".repeat(32))
                .spentTxHash(spentBy)
                .build();
    }

    /** One block, in Yaci 0.1.7's order: every input of the block, then every output of the block. */
    private static void block(TankUtxoStorage storage, List<TxInput> inputs, List<AddressUtxo> outputs) {
        storage.saveSpent(inputs);
        storage.saveUnspent(outputs);
    }

    // ---- the defect --------------------------------------------------------------------------------

    /**
     * ⛔ tx1 pays the wallet X; tx2, in the SAME block, spends X and pays the wallet Y. X must carry its
     * spend; only Y is unspent.
     */
    @Test
    void aWalletOutputCreatedAndSpentInTheSameBlockIsRecordedSpent() {
        var storage = storage();
        String tx1 = tx(1), tx2 = tx(2);

        block(storage,
                List.of(spend(tx1, 0, tx2, SLOT)),
                List.of(output(tx1, 0, walletPkh, SLOT), output(tx2, 0, walletPkh, SLOT)));

        assertTrue(indexed(tx1, 0), "X is ours, so its output row is kept");
        assertTrue(spentRow(tx1, 0),
                "X was spent in the block that created it; without a spend row it is a ghost forever");
        assertEquals(List.of(tx2 + "#0"), unspentFor(walletPkh),
                "the wallet's unspent read must return only Y — never the already-spent X");
        // The spend row must carry the SPENDING block and tx: Yaci's rollback deletes tx_input rows by
        // spent_at_slot, so a row without it would survive a rollback and hide a live output forever.
        var row = dsl.fetchOne("SELECT spent_at_slot, spent_at_block, spent_tx_hash FROM tx_input "
                + "WHERE tx_hash = ? AND output_index = 0", tx1);
        assertEquals(SLOT, row.get(0, Long.class));
        assertEquals(SLOT, row.get(1, Long.class));
        assertEquals(tx2, row.get(2, String.class));
    }

    /** A block that died between its two calls leaves nothing behind for the next block's saveSpent. */
    @Test
    void aBlockThatDiedBeforeSaveUnspentLeavesNothingForTheNextBlock() {
        var storage = storage();
        String x = tx(41), spender = tx(42), next = tx(43);

        storage.saveSpent(List.of(spend(x, 0, spender, SLOT)));      // block N: saveUnspent never runs
        // Block N' (a fork that never spends X) creates X.
        block(storage, List.of(spend(next, 0, tx(44), SLOT + 1)), List.of(output(x, 0, walletPkh, SLOT + 1)));

        assertTrue(!spentRow(x, 0), "a dead block's remembered spend must not attach to a later block's output");
        assertEquals(List.of(x + "#0"), unspentFor(walletPkh));
    }

    /** A chain inside one block: tx1 → X, tx2 spends X → Y, tx3 spends Y → Z. Only Z is unspent. */
    @Test
    void aChainOfSameBlockSpendsLeavesOnlyTheLastOutputUnspent() {
        var storage = storage();
        String tx1 = tx(1), tx2 = tx(2), tx3 = tx(3);

        block(storage,
                List.of(spend(tx1, 0, tx2, SLOT), spend(tx2, 0, tx3, SLOT)),
                List.of(output(tx1, 0, walletPkh, SLOT), output(tx2, 0, walletPkh, SLOT),
                        output(tx3, 0, walletPkh, SLOT)));

        assertEquals(List.of(tx3 + "#0"), unspentFor(walletPkh));
    }

    // ---- unchanged behaviour -----------------------------------------------------------------------

    @Test
    void anInputSpendingAnAlreadyIndexedOutputIsSaved() {
        var storage = storage();
        String tx1 = tx(1), tx2 = tx(2);
        block(storage, List.of(), List.of(output(tx1, 0, walletPkh, SLOT)));

        block(storage, List.of(spend(tx1, 0, tx2, SLOT + 1)), List.of());

        assertTrue(spentRow(tx1, 0));
        assertTrue(unspentFor(walletPkh).isEmpty());
    }

    /**
     * An input spending a foreign output that was never ours is dropped — and is NOT carried into the
     * next block, where an output with that ref (impossible on chain, but the point is the set's
     * lifetime) must not pick up the stale spend.
     */
    @Test
    void anInputSpendingAForeignOutputIsNotSavedAndNotCarriedIntoTheNextBlock() {
        var storage = storage();
        String foreign = tx(9), spender = tx(2);

        block(storage, List.of(spend(foreign, 0, spender, SLOT)), List.of());
        assertEquals(0, spentRowCount(), "a spend of an output we never indexed is not ours to record");

        block(storage, List.of(), List.of(output(foreign, 0, walletPkh, SLOT + 1)));

        assertFalse(spentRow(foreign, 0), "the remembered spend must end with the block that offered it");
        assertEquals(List.of(foreign + "#0"), unspentFor(walletPkh));
    }

    /** A foreign output created and spent in one block leaves NO row of either kind. */
    @Test
    void aForeignOutputCreatedAndSpentInTheSameBlockLeavesNoRowAtAll() {
        var storage = storage();
        String tx1 = tx(1), tx2 = tx(2);

        block(storage,
                List.of(spend(tx1, 0, tx2, SLOT)),
                List.of(output(tx1, 0, FOREIGN_PKH, SLOT), output(tx2, 0, FOREIGN_PKH, SLOT)));

        assertFalse(indexed(tx1, 0));
        assertFalse(indexed(tx2, 0));
        assertEquals(0, spentRowCount(), "no spend row for an output that was filtered out");
    }

    // ---- lifetime of the remembered set ------------------------------------------------------------

    /**
     * The set is cleared by {@code saveUnspent} even when nothing matched. Yaci's Byron and genesis
     * processors call {@code saveUnspent} WITHOUT a preceding {@code saveSpent}, so a set that outlived
     * its block would be read by the next {@code saveUnspent} call.
     */
    @Test
    void theRememberedSetIsClearedAfterSaveUnspent() {
        var storage = storage();
        String x = tx(1), spender = tx(2);

        block(storage, List.of(spend(x, 0, spender, SLOT)), List.of());
        storage.saveUnspent(List.of(output(x, 0, walletPkh, SLOT + 1)));

        assertFalse(spentRow(x, 0), "a spend remembered in an earlier block must not attach to a later output");
    }

    @Test
    void theRememberedSetIsClearedEvenWhenSaveUnspentThrows() {
        var failing = new UtxoCache() {
            boolean fail = true;

            @Override
            public void add(AddressUtxo addressUtxo) {
                if (fail) {
                    throw new IllegalStateException("fixture: the write failed");
                }
                super.add(addressUtxo);
            }
        };
        var storage = storage(failing);
        String x = tx(1), spender = tx(2), other = tx(3);

        storage.saveSpent(List.of(spend(x, 0, spender, SLOT)));
        assertThrows(IllegalStateException.class,
                () -> storage.saveUnspent(List.of(output(other, 0, walletPkh, SLOT))));

        failing.fail = false;
        storage.saveUnspent(List.of(output(x, 0, walletPkh, SLOT + 1)));

        assertFalse(spentRow(x, 0), "a failed saveUnspent must still end the block's remembered set");
    }

    // ---- FAB-137 T2a: the Minswap V2 pool payment credential --------------------------------------

    /** The base set: wallet + parameters + staker + tank, and nothing else. */
    private Set<String> baseSet() {
        return Set.of(walletPkh, "a1".repeat(28), "a2".repeat(28), "a3".repeat(28));
    }

    /**
     * (i) The pool credential is watched whatever stake credential the pool output carries — S1, S2 or
     * none (enterprise) — and the addition is UNCONDITIONAL: no registry bean, or a registry with blank
     * coordinates, must not take it away (a flag on a write-time filter is a data-retention switch).
     */
    @Test
    void thePoolCredentialIsIndexedUnderAnyStakeCredentialWithNoRegistry() {
        assertPoolOutputsAreSavedUnderEveryStakeShape(storage(POOL_PKH, emptyLoans()));
    }

    @Test
    void thePoolCredentialIsIndexedUnderAnyStakeCredentialWithAnUnconfiguredRegistry() {
        assertPoolOutputsAreSavedUnderEveryStakeShape(storage(POOL_PKH, unconfiguredLoans()));
    }

    private void assertPoolOutputsAreSavedUnderEveryStakeShape(TankUtxoStorage storage) {
        assertTrue(storage.indexedPaymentCredentials().contains(POOL_PKH),
                "the Minswap pool payment credential must be in the indexed set: "
                        + storage.indexedPaymentCredentials());
        assertEquals(baseSet().size() + 1, storage.indexedPaymentCredentials().size(),
                "the set grows by exactly the pool credential");

        String withS1 = tx(101), withS2 = tx(102), enterprise = tx(103);
        block(storage, List.of(), List.of(
                output(withS1, 0, POOL_PKH, STAKE_1, SLOT),
                output(withS2, 0, POOL_PKH, STAKE_2, SLOT),
                output(enterprise, 0, POOL_PKH, null, SLOT)));

        assertTrue(indexed(withS1, 0), "pool output under stake credential S1 must be stored");
        assertTrue(indexed(withS2, 0), "pool output under a DIFFERENT stake credential S2 must be stored");
        assertTrue(indexed(enterprise, 0), "pool output with no stake part (enterprise) must be stored");
        assertEquals(Set.of(withS1 + "#0", withS2 + "#0", enterprise + "#0"), Set.copyOf(unspentFor(POOL_PKH)));
    }

    /** The configured hash is normalised to lowercase, which is how Yaci writes owner_payment_credential. */
    @Test
    void anUppercasePoolCredentialIsIndexedLowercase() {
        var storage = storage(POOL_PKH.toUpperCase(), emptyLoans());
        assertTrue(storage.indexedPaymentCredentials().contains(POOL_PKH));
        assertFalse(storage.indexedPaymentCredentials().contains(POOL_PKH.toUpperCase()));
    }

    /** (ii) Blank means "this network has no Minswap deployment": the set is exactly the base set. */
    @Test
    void aBlankPoolCredentialAddsNothing() {
        for (String blank : java.util.Arrays.asList("", "  ", null)) {
            var storage = storage(blank, emptyLoans());
            assertEquals(baseSet(), storage.indexedPaymentCredentials(), "blank value [" + blank + "]");
        }
    }

    /** (iii) Present but malformed is a typo, not an absence: construction fails, naming the key. */
    @Test
    void aMalformedPoolCredentialFailsConstructionNamingTheProperty() {
        for (String malformed : List.of(
                POOL_PKH.substring(1),                    // 55 characters
                POOL_PKH + "0",                           // 57 characters
                "zz" + POOL_PKH.substring(2),             // 56 characters, not hex
                "addr1z84q0denmyep98ph3tmzwsmw0j7zau9ljmsqx6a4rvaau66j2c79gy9l76sdg0xwhd7r0c0kna0tycz4y5s6mlenh8pq777e2a")) {
            var e = assertThrows(IllegalStateException.class, () -> storage(malformed, emptyLoans()),
                    "a malformed value must not start: " + malformed);
            assertTrue(e.getMessage().contains("loans.minswap.pool-spend-script-hash"),
                    "the failure must name the property: " + e.getMessage());
            assertTrue(e.getMessage().contains(malformed), "the failure must name the value: " + e.getMessage());
        }
    }

    /**
     * (iv) + (v) Minswap batchers routinely create and spend a pool UTxO in one block. The created
     * output must end up spent — a tx_input row exists and the unspent join returns nothing for it —
     * with the pool output first in the block's output list, and last after a wallet output.
     */
    @Test
    void aPoolOutputCreatedAndSpentInTheSameBlockIsRecordedSpentPoolFirst() {
        var storage = storage();
        String created = tx(201), batch = tx(202);

        block(storage,
                List.of(spend(created, 0, batch, SLOT)),
                List.of(output(created, 0, POOL_PKH, STAKE_1, SLOT), output(batch, 0, POOL_PKH, STAKE_1, SLOT)));

        assertTrue(indexed(created, 0), "the pool output is ours, so its row is kept");
        assertTrue(spentRow(created, 0), "spent in the block that created it: it needs a tx_input row");
        assertEquals(List.of(batch + "#0"), unspentFor(POOL_PKH));
    }

    @Test
    void aPoolOutputCreatedAndSpentInTheSameBlockIsRecordedSpentPoolLastAfterAWalletOutput() {
        var storage = storage();
        String walletTx = tx(301), created = tx(302), batch = tx(303);

        block(storage,
                List.of(spend(created, 0, batch, SLOT)),
                List.of(output(walletTx, 0, walletPkh, SLOT), output(created, 0, POOL_PKH, STAKE_2, SLOT)));

        assertTrue(indexed(created, 0), "the pool output is ours, so its row is kept");
        assertTrue(spentRow(created, 0), "spent in the block that created it: it needs a tx_input row");
        assertTrue(unspentFor(POOL_PKH).isEmpty(), "the created-and-spent pool output must not read unspent");
        assertEquals(List.of(walletTx + "#0"), unspentFor(walletPkh));
    }
}
