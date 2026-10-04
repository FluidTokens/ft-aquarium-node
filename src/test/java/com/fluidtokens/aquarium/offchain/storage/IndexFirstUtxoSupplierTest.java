package com.fluidtokens.aquarium.offchain.storage;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.common.util.ScriptReferenceUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.util.UtxoUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DataAccessResourceFailureException;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-134 B3a — the {@code UtxoSupplier} bean answers from the local index first.
 *
 * <h2>What each test kills</h2>
 * <ul>
 *   <li>(a) a {@code getPage} that ignores {@code page} — CCL's default {@code getAll} then never sees
 *       an empty page and loops forever;</li>
 *   <li>(b) an unwatched address answered EMPTY instead of refused — the write-time filter never kept
 *       its rows, so "empty" would be a lie indistinguishable from a real empty wallet;</li>
 *   <li>(c) a provider fallback on an empty index answer — the silent-understatement trap
 *       ({@code officina:yaci-store-index-scoping} §5);</li>
 *   <li>(d) the {@code AddressUtxo} mapper overload, which writes raw {@code scriptRef} hex as the hash;</li>
 *   <li>(e) a memoised provider answer;</li>
 *   <li>(f) the {@code UtxoUtil} "hash unresolved" sentinel handed to CCL as if it were a hash;</li>
 *   <li>(g) a database failure turned into an empty answer or a provider call.</li>
 * </ul>
 */
class IndexFirstUtxoSupplierTest {

    private static final Account WALLET = new Account(Networks.testnet());
    private static final Account STRANGER = new Account(Networks.testnet());

    private static final String WALLET_PKH = WALLET.getBaseAddress().getPaymentCredentialHash()
            .map(HexUtil::encodeHexString).get();

    private static final String SENTINEL = "reference-script-present-hash-unresolved";

    private static final String TX_A = "aa".repeat(32);
    private static final String TX_B = "bb".repeat(32);

    /** A provider that counts every call and answers something recognisable. */
    static final class CountingProvider implements UtxoSupplier {
        final AtomicInteger pageCalls = new AtomicInteger();
        final AtomicInteger txOutputCalls = new AtomicInteger();

        @Override
        public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
            pageCalls.incrementAndGet();
            // Non-empty on page 0 so a fallback is visible in the CONTENT as well as the count.
            return page != null && page == 0 ? List.of(providerUtxo("ee".repeat(32), 9)) : List.of();
        }

        @Override
        public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
            txOutputCalls.incrementAndGet();
            return Optional.of(providerUtxo(txHash, outputIndex));
        }

        int calls() {
            return pageCalls.get() + txOutputCalls.get();
        }
    }

    private static Utxo providerUtxo(String txHash, int outputIndex) {
        return Utxo.builder().txHash(txHash).outputIndex(outputIndex).address("provider")
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(1_234_567L)))).build();
    }

    /**
     * An in-memory Yaci repository serving only {@code findUnspentByOwnerAddr} and {@code findById}.
     * Anything else throws, so the supplier cannot quietly switch query.
     */
    private static UtxoRepository index(Function<String, Optional<List<AddressUtxoEntity>>> byAddress,
                                        Function<UtxoId, Optional<AddressUtxoEntity>> byId,
                                        AtomicInteger repoCalls) {
        return (UtxoRepository) Proxy.newProxyInstance(
                IndexFirstUtxoSupplierTest.class.getClassLoader(),
                new Class<?>[]{UtxoRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findUnspentByOwnerAddr" -> {
                        repoCalls.incrementAndGet();
                        // The supplier pages ITSELF over the whole answer: a paged query sliced again would
                        // silently truncate a wallet of more than one page.
                        if (!((org.springframework.data.domain.Pageable) args[1]).isUnpaged()) {
                            throw new AssertionError("the address query must be unpaged: " + args[1]);
                        }
                        yield byAddress.apply((String) args[0]);
                    }
                    case "findById" -> {
                        repoCalls.incrementAndGet();
                        yield byId.apply((UtxoId) args[0]);
                    }
                    case "toString" -> "stub UtxoRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("unexpected " + method.getName());
                });
    }

    private static UtxoRepository addressIndex(List<AddressUtxoEntity> walletRows) {
        return index(addr -> Optional.of(WALLET.baseAddress().equals(addr) ? walletRows : List.of()),
                id -> Optional.empty(), new AtomicInteger());
    }

    private static UtxoRepository idIndex(AddressUtxoEntity row) {
        return index(addr -> Optional.of(List.of()),
                id -> row.getTxHash().equals(id.getTxHash()) && row.getOutputIndex().equals(id.getOutputIndex())
                        ? Optional.of(row) : Optional.empty(),
                new AtomicInteger());
    }

    private static IndexFirstUtxoSupplier supplier(UtxoRepository repo, UtxoSupplier provider) {
        return new IndexFirstUtxoSupplier(repo, () -> Set.of(WALLET_PKH), provider);
    }

    private static AddressUtxoEntity row(String txHash, int outputIndex, String ownerAddr, long lovelace) {
        AddressUtxoEntity entity = new AddressUtxoEntity();
        entity.setTxHash(txHash);
        entity.setOutputIndex(outputIndex);
        entity.setOwnerAddr(ownerAddr);
        Amt amount = new Amt();
        amount.setUnit("lovelace");
        amount.setQuantity(BigInteger.valueOf(lovelace));
        entity.setAmounts(new ArrayList<>(List.of(amount)));
        return entity;
    }

    private static List<AddressUtxoEntity> shuffledRows(int n) {
        List<AddressUtxoEntity> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            rows.add(row(String.format("%064x", i / 3), i % 3, WALLET.baseAddress(), 1_000_000L + i));
        }
        Collections.shuffle(rows, new Random(42));
        return rows;
    }

    private static final Comparator<Utxo> ORDER =
            Comparator.comparing(Utxo::getTxHash).thenComparingInt(Utxo::getOutputIndex);

    // ------------------------------------------------------------------ (a)

    /**
     * ⛔ 205 rows paginate 100 / 100 / 5 / EMPTY, and {@code getAll} terminates with all 205 sorted.
     * The explicit page-3 assertion runs BEFORE {@code getAll} so a {@code getPage} that ignores
     * {@code page} fails cleanly here rather than only through the timeout.
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void getAllReturnsTheWholeIndexSortedPaginatesAndTerminatesWithoutTheProvider() {
        var provider = new CountingProvider();
        var supplier = supplier(addressIndex(shuffledRows(205)), provider);
        String addr = WALLET.baseAddress();

        assertEquals(100, supplier.getPage(addr, 100, 0, OrderEnum.asc).size());
        assertEquals(100, supplier.getPage(addr, 100, 1, OrderEnum.asc).size());
        assertEquals(5, supplier.getPage(addr, 100, 2, OrderEnum.asc).size());
        assertTrue(supplier.getPage(addr, 100, 3, OrderEnum.asc).isEmpty(),
                "past the last row getPage must return EMPTY, or CCL's default getAll never terminates");

        List<Utxo> all = supplier.getAll(addr);

        assertEquals(205, all.size());
        assertEquals(all.stream().sorted(ORDER).toList(), all, "getAll must be sorted by (txHash, outputIndex)");
        assertEquals(205, all.stream().map(u -> u.getTxHash() + "#" + u.getOutputIndex()).distinct().count(),
                "pages must not overlap");
        assertEquals(0, provider.calls(), "an address read must NEVER reach the provider");
    }

    @Test
    void descendingOrderAndNullDefaults() {
        var provider = new CountingProvider();
        var supplier = supplier(addressIndex(shuffledRows(7)), provider);
        String addr = WALLET.baseAddress();

        List<Utxo> desc = supplier.getPage(addr, 100, 0, OrderEnum.desc);
        assertEquals(desc.stream().sorted(ORDER.reversed()).toList(), desc);

        List<Utxo> defaults = supplier.getPage(addr, null, null, OrderEnum.asc);
        assertEquals(7, defaults.size(), "null nrOfItems / page mean the default page size and page 0");
        assertEquals(0, provider.calls());
    }

    // ------------------------------------------------------------------ (b)

    /** ⛔ The write-time filter never kept this address's rows: "empty" would be a lie. Refuse. */
    @Test
    void anUnwatchedAddressIsREFUSEDNamingItAndNeverAnsweredEmpty() {
        var provider = new CountingProvider();
        AtomicInteger repoCalls = new AtomicInteger();
        var supplier = supplier(index(addr -> Optional.of(List.of()), id -> Optional.empty(), repoCalls), provider);
        String stranger = STRANGER.baseAddress();

        var thrown = assertThrows(IllegalStateException.class,
                () -> supplier.getPage(stranger, 100, 0, OrderEnum.asc));
        assertTrue(thrown.getMessage().contains(stranger), "the refusal must name the address: " + thrown.getMessage());
        assertThrows(IllegalStateException.class, () -> supplier.getAll(stranger));
        assertEquals(0, provider.calls(), "a refused address must not be answered by the provider either");
        assertEquals(0, repoCalls.get(), "the index must not even be asked about an address it does not watch");
    }

    // ------------------------------------------------------------------ (c)

    /** ⛔ An empty index answer is the answer. A fallback-on-empty makes a PARTIAL index authoritative. */
    @Test
    void anEmptyIndexAnswerIsEmptyAndNeverConsultsTheProvider() {
        var provider = new CountingProvider();
        var emptyList = supplier(addressIndex(List.of()), provider);
        var emptyOptional = supplier(index(addr -> Optional.empty(), id -> Optional.empty(), new AtomicInteger()),
                provider);
        String addr = WALLET.baseAddress();

        assertTrue(emptyList.getPage(addr, 100, 0, OrderEnum.asc).isEmpty());
        assertTrue(emptyList.getAll(addr).isEmpty());
        assertTrue(emptyOptional.getPage(addr, 100, 0, OrderEnum.asc).isEmpty());
        assertTrue(emptyOptional.getAll(addr).isEmpty());
        assertEquals(0, provider.calls(), "an empty index answer must not fall back to the provider");
    }

    // ------------------------------------------------------------------ (d)

    /**
     * A hit is answered from the index, with the reference-script HASH recovered from {@code scriptRef}
     * (the entity mapper) — never the raw {@code scriptRef} hex the {@code AddressUtxo} overload writes.
     */
    @Test
    void aTxOutputHitIsTheIndexRowWithTheHashRecoveredFromScriptRef() throws Exception {
        String scriptRef = "8203" + "4e4d01000033222220051200120011";
        String expectedHash = ScriptReferenceUtil.getReferenceScriptHash(HexUtil.decodeHexString(scriptRef));
        AddressUtxoEntity withScript = row(TX_A, 1, WALLET.baseAddress(), 20_000_000L);
        withScript.setScriptRef(scriptRef);   // referenceScriptHash left NULL: the schema-gap shape
        var provider = new CountingProvider();

        Utxo utxo = supplier(idIndex(withScript), provider).getTxOutput(TX_A, 1).orElseThrow();

        assertEquals(TX_A, utxo.getTxHash());
        assertEquals(WALLET.baseAddress(), utxo.getAddress(), "the row must come from the index");
        assertEquals(expectedHash, utxo.getReferenceScriptHash(),
                "the reference-script HASH must be recovered from scriptRef");
        assertNotEquals(scriptRef, utxo.getReferenceScriptHash(), "raw scriptRef hex is not a hash");
        assertEquals(0, provider.calls(), "an indexed output must not reach the provider");
    }

    // ------------------------------------------------------------------ (e)

    /** ⛔ A miss goes to the provider — every time. Nothing is cached in this slice. */
    @Test
    void aTxOutputMissIsTheProvidersAnswerAndIsNeverMemoised() {
        var provider = new CountingProvider();
        var supplier = supplier(idIndex(row(TX_A, 0, WALLET.baseAddress(), 1L)), provider);

        Utxo first = supplier.getTxOutput(TX_B, 3).orElseThrow();
        assertEquals("provider", first.getAddress());
        assertEquals(1, provider.txOutputCalls.get());

        supplier.getTxOutput(TX_B, 3).orElseThrow();
        assertEquals(2, provider.txOutputCalls.get(),
                "two identical misses must make two provider calls: nothing is memoised");
    }

    // ------------------------------------------------------------------ (f)

    // ------------------------------------------------------------------ (i)

    /**
     * ⛔ The OTHER half of the sentinel rule: coin selection must still SEE that a wallet row carries a
     * reference script. {@code ReferenceScriptSafeUtxoSelection} refuses a UTxO only while its
     * {@code referenceScriptHash} is non-null, and the bot's published reference script lives at the very
     * address {@code getPage} serves. A {@code getPage} that nulled the hash (or the sentinel) would let
     * the selector spend it — an unrecoverable loss. So the sentinel is blocked in {@code getTxOutput}
     * only, and both shapes come out of {@code getPage} with the hash set.
     */
    @Test
    void getPageKeepsEveryReferenceScriptMarkSoTheSelectorNeverSpendsOne() {
        String scriptRef = "8203" + "4e4d01000033222220051200120011";
        AddressUtxoEntity withHash = row(TX_A, 0, WALLET.baseAddress(), 30_000_000L);
        withHash.setScriptRef(scriptRef);
        AddressUtxoEntity undecodable = row(TX_A, 1, WALLET.baseAddress(), 40_000_000L);
        undecodable.setScriptRef("zz-not-hex");
        AddressUtxoEntity plain = row(TX_B, 0, WALLET.baseAddress(), 5_000_000L);
        var supplier = supplier(addressIndex(List.of(withHash, undecodable, plain)), new CountingProvider());

        List<Utxo> page = supplier.getPage(WALLET.baseAddress(), 100, 0, OrderEnum.asc);

        for (Utxo utxo : page) {
            boolean carriesScript = !utxo.getTxHash().equals(TX_B);
            assertEquals(carriesScript, utxo.getReferenceScriptHash() != null,
                    utxo.getTxHash() + "#" + utxo.getOutputIndex() + " lost its reference-script mark");
            assertEquals(!carriesScript,
                    com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptSafeUtxoSelection.spendable(utxo),
                    "the selector's verdict on " + utxo.getTxHash() + "#" + utxo.getOutputIndex());
        }
        assertEquals(3, page.size());
    }

    /** ⛔ The "hash unresolved" sentinel must never reach CCL, which would price a script that is not one. */
    @Test
    void aRowWhoseScriptRefIsUndecodableIsAnsweredByTheProviderNeverAsTheSentinel() {
        AddressUtxoEntity undecodable = row(TX_A, 2, WALLET.baseAddress(), 20_000_000L);
        undecodable.setScriptRef("zz-not-hex");
        assertEquals(SENTINEL, UtxoUtil.toUtxo(undecodable).getReferenceScriptHash(),
                "precondition: this row maps to the sentinel");
        var provider = new CountingProvider();

        Utxo utxo = supplier(idIndex(undecodable), provider).getTxOutput(TX_A, 2).orElseThrow();

        assertNotEquals(SENTINEL, utxo.getReferenceScriptHash(), "the sentinel escaped to the caller");
        assertEquals("provider", utxo.getAddress(), "an unfaithful row must be answered by the provider");
        assertEquals(1, provider.txOutputCalls.get());
    }

    // ------------------------------------------------------------------ (g)

    /** ⛔ A database failure is not an empty answer and not a reason to ask the provider. */
    @Test
    void aRepositoryFailurePROPAGATESFromBothMethods() {
        var failure = new DataAccessResourceFailureException("connection refused");
        var provider = new CountingProvider();
        var supplier = supplier(index(addr -> {
            throw failure;
        }, id -> {
            throw failure;
        }, new AtomicInteger()), provider);

        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> supplier.getPage(WALLET.baseAddress(), 100, 0, OrderEnum.asc)));
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> supplier.getAll(WALLET.baseAddress())));
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> supplier.getTxOutput(TX_A, 0)));
        assertEquals(0, provider.calls(), "a database failure must not become a provider call");
    }
}
