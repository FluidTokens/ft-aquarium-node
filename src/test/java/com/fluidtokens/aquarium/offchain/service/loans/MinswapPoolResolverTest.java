package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * ⛔ <b>A MINSWAP POOL IS READ FROM THE LOCAL INDEX, AND THE INDEX HOLDS JUNK.</b>
 *
 * <p>The pool payment credential is a public script address: anyone can pay anything to it, with any
 * datum and any stake part. A credential-scoped scan therefore returns everything there
 * ({@code officina:yaci-store-index-scoping} §2b), and <b>authentication is the filter, not the
 * query</b>. A row is an authentic pool only when it holds exactly one MSP under the pool policy, its
 * inline datum decodes, and the LP name recomputed from the datum's pair is an LP unit the row
 * holds. An LP token or a datum alone authenticates nothing: anyone can pay either there (CCL §12 —
 * the MSP is the content that only the pool validator can move).
 *
 * <p>These tests drive the REAL snapshot over a mocked {@link UtxoRepository} returning real
 * {@link AddressUtxoEntity} rows, so the mapping through {@code UtxoUtil.toUtxo} is exercised too.
 */
class MinswapPoolResolverTest {

    private static final String POOL_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String POOL_CREDENTIAL = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MSP = POOL_POLICY + ConvertTxEncoder.POOL_NFT_ASSET_NAME;

    private static final AssetType ADA = AssetType.ada();
    private static final AssetType ASCEND = AssetType.fromUnit(
            "eb7a93ebc321647673490810f618b548d7c24aa64d30ae342dba70760014df10415343454e44");
    private static final AssetType FLDT = new AssetType(
            "577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454");

    private static final String LP_ASCEND = POOL_POLICY + ConvertTxEncoder.computeLpAssetName(ADA, ASCEND);
    private static final String LP_FLDT = POOL_POLICY + ConvertTxEncoder.computeLpAssetName(ADA, FLDT);
    /** The ada / LP(ada,ASCEND)-token pool's own LP unit. */
    private static final String LP_OTHER = POOL_POLICY
            + ConvertTxEncoder.computeLpAssetName(ADA, AssetType.fromUnit(LP_ASCEND));

    /** ⚑ Read off {@code 4c805499…#1} on 2026-09-18: the real ada/ASCEND pool (from the retired MinswapMultiplePoolsTest). */
    private static final String DEEP_POOL_DATUM = "d8799fd8799fd87a9f581c1eae96baf29e27682ea3f815aba361a0c6059d45e4bfbe95bbd2f44affffd8799f4040ffd8799f581ceb7a93ebc321647673490810f618b548d7c24aa64d30ae342dba70764a0014df10415343454e44ff1b000000ba6be20bdc1b000000ce9358c8a91b000000ed5e5802cf18641864d8799f190682ffd87980ff";

    /** ⚑ Read off {@code 02db9d9b…#1} on 2026-09-18: an ada / LP-token pool whose assetB IS the ada/ASCEND LP asset. */
    private static final String OTHER_PAIR_DATUM = "d8799fd8799fd87a9f581c1eae96baf29e27682ea3f815aba361a0c6059d45e4bfbe95bbd2f44affffd8799f4040ffd8799f581cf5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c5820e66195788208dcd363edb600eaf2331019e3599baba645d81d61ef060c82d861ff0b0e09181e181ed8799f190682ffd87980ff";

    /** The real ada/ASCEND datum with only its two reserves raised to absurd values: a forgery anyone can pay in. */
    private static final String FORGED_RESERVES_DATUM = DEEP_POOL_DATUM
            .replace("1b000000ce9358c8a91b000000ed5e5802cf", "1b0fffffffffffffff1b0fffffffffffffff");

    /** The task-1 freeze: what the OLD Blockfrost resolver returned for this fixture (see the ⊇ test). */
    private static final String OLD_ASCEND_OUT_REF = "4c805499".repeat(8) + "#1";
    private static final String OLD_FLDT_OUT_REF = "0f1d0f1d".repeat(8) + "#0";

    private static final String POOL_ADDR = "addr1z84q0denmyep98ph3tmzwsmw0j7zau9ljmsqx6a4rvaau66j2c79gy9l76sdg0xwhd7r0c0kna0tycz4y5s6mlenh8pq777e2a";
    private static final String POOL_STAKE = "staking_part_of_the_shipped_pool_address";

    // ---- fixture plumbing --------------------------------------------------------------------------

    private static Amt amt(String unit, long quantity) {
        Amt amount = new Amt();
        amount.setUnit(unit);
        amount.setQuantity(BigInteger.valueOf(quantity));
        return amount;
    }

    private static AddressUtxoEntity row(String txHash, int index, String datum, Amt... amounts) {
        return row(txHash, index, datum, POOL_STAKE, POOL_ADDR, amounts);
    }

    private static AddressUtxoEntity row(String txHash, int index, String datum, String stake, String addr,
                                         Amt... amounts) {
        AddressUtxoEntity entity = new AddressUtxoEntity();
        entity.setTxHash(txHash);
        entity.setOutputIndex(index);
        entity.setOwnerAddr(addr);
        entity.setOwnerPaymentCredential(POOL_CREDENTIAL);
        entity.setOwnerStakeCredential(stake);
        entity.setInlineDatum(datum);
        List<Amt> list = new ArrayList<>();
        list.add(amt("lovelace", 5_000_000L));
        list.addAll(List.of(amounts));
        entity.setAmounts(list);
        return entity;
    }

    /** The authentic ada/ASCEND pool: MSP + its own LP unit + the real datum. */
    private static AddressUtxoEntity authenticAscend(String txHash) {
        return row(txHash, 1, DEEP_POOL_DATUM, amt(MSP, 1), amt(LP_ASCEND, 9_000_000_000L), amt(ASCEND.toUnit(), 5));
    }

    /**
     * A repository that honours the page it is asked for — so a resolver asking for a page of 10 gets
     * 10 and silently misses the rest, exactly as Postgres would.
     */
    private static UtxoRepository repo(List<AddressUtxoEntity> rows) {
        UtxoRepository repository = mock(UtxoRepository.class);
        when(repository.findUnspentByOwnerPaymentCredential(eq(POOL_CREDENTIAL), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Pageable page = inv.getArgument(1);
                    List<AddressUtxoEntity> served = page.isUnpaged() ? rows
                            : rows.stream().skip(page.getOffset()).limit(page.getPageSize()).toList();
                    return Optional.of(served);
                });
        return repository;
    }

    private static MinswapPoolResolver resolver(UtxoRepository repository) {
        return new MinswapPoolResolver(repository, POOL_CREDENTIAL, POOL_POLICY);
    }

    private static Optional<MinswapPoolResolver.ResolvedPool> ascend(List<AddressUtxoEntity> rows) {
        return resolver(repo(rows)).snapshot().resolveEitherOrder(ADA, ASCEND);
    }

    private static String outRef(MinswapPoolResolver.ResolvedPool pool) {
        return pool.utxo().getTxHash() + "#" + pool.utxo().getOutputIndex();
    }

    /**
     * A never-selected row is checked two ways: alone it yields no pool, and beside the authentic pool
     * it neither displaces it nor makes the pair ambiguous — in both orderings.
     */
    private static void assertNeverSelected(AddressUtxoEntity junk) {
        assertTrue(ascend(List.of(junk)).isEmpty(), "alone, this row must not be a pool");
        String good = "aa".repeat(32);
        for (List<AddressUtxoEntity> rows : List.of(List.of(junk, authenticAscend(good)),
                List.of(authenticAscend(good), junk))) {
            var found = ascend(rows);
            assertTrue(found.isPresent(), "the authentic pool must still be found");
            assertEquals(good + "#1", outRef(found.get()), "and only the authentic pool");
        }
    }

    // ---- found ---------------------------------------------------------------------------------------

    @Test
    void anAuthenticPoolIsFoundAskingInEitherOrder() {
        var snapshot = resolver(repo(List.of(authenticAscend("aa".repeat(32))))).snapshot();

        var forward = snapshot.resolveEitherOrder(ADA, ASCEND);
        var backward = snapshot.resolveEitherOrder(ASCEND, ADA);

        assertEquals("aa".repeat(32) + "#1", outRef(forward.orElseThrow()));
        assertEquals("aa".repeat(32) + "#1", outRef(backward.orElseThrow()));
        assertEquals(ASCEND.toUnit(), forward.get().datum().assetB().toUnit(), "the datum states the ordering");
        assertEquals(ConvertTxEncoder.computeLpAssetName(ADA, ASCEND), forward.get().lpAssetName());
    }

    @Test
    void anAuthenticPoolUnderAnotherStakeCredentialIsFound() {
        var pool = row("ab".repeat(32), 0, DEEP_POOL_DATUM, "a_different_stake_credential",
                "addr1_same_payment_other_stake", amt(MSP, 1), amt(LP_ASCEND, 1_000L));

        assertEquals("ab".repeat(32) + "#0", outRef(ascend(List.of(pool)).orElseThrow()),
                "the pool is identified by its payment credential; the stake part is anyone's choice");
    }

    @Test
    void aPairWithNoIndexedPoolIsEmptyNotAnError() {
        assertTrue(resolver(repo(List.of(authenticAscend("aa".repeat(32))))).snapshot()
                .resolveEitherOrder(ADA, FLDT).isEmpty());
    }

    // ---- never selected ------------------------------------------------------------------------------

    @Test
    void aRowWithNoMspIsNeverSelected() {
        assertNeverSelected(row("01".repeat(32), 0, DEEP_POOL_DATUM, amt(LP_ASCEND, 1_000L)));
    }

    @Test
    void aRowWithTwoMspUnitsIsNeverSelected() {
        assertNeverSelected(row("02".repeat(32), 0, DEEP_POOL_DATUM, amt(MSP, 1), amt(MSP, 1), amt(LP_ASCEND, 1_000L)));
    }

    @Test
    void aRowWithMspQuantityTwoIsNeverSelected() {
        assertNeverSelected(row("03".repeat(32), 0, DEEP_POOL_DATUM, amt(MSP, 2), amt(LP_ASCEND, 1_000L)));
    }

    @Test
    void anMspUnderAnotherPolicyIsNeverSelected() {
        assertNeverSelected(row("04".repeat(32), 0, DEEP_POOL_DATUM,
                amt("ee".repeat(28) + ConvertTxEncoder.POOL_NFT_ASSET_NAME, 1), amt(LP_ASCEND, 1_000L)));
    }

    @Test
    void aRowWithNoInlineDatumIsNeverSelected() {
        assertNeverSelected(row("05".repeat(32), 0, null, amt(MSP, 1), amt(LP_ASCEND, 1_000L)));
    }

    @Test
    void aRowWithAnUndecodableDatumIsNeverSelected() {
        assertNeverSelected(row("06".repeat(32), 0, "d87980", amt(MSP, 1), amt(LP_ASCEND, 1_000L)));
    }

    /** The real mainnet shape: an ada / LP-token pool that HOLDS the ada/ASCEND LP asset as a reserve. */
    @Test
    void aPoolForAnotherPairIsNeverSelectedEvenHoldingThisPairsLpAsset() {
        assertNeverSelected(row("02db9d9b".repeat(8), 1, OTHER_PAIR_DATUM,
                amt(MSP, 1), amt(LP_OTHER, 1_000L), amt(LP_ASCEND, 14L)));
    }

    /** MSP and an ada/ASCEND datum, but the LP unit it holds is not the one that datum's pair names. */
    @Test
    void aRowWhoseLpUnitIsNotItsOwnPairsIsNeverSelected() {
        assertNeverSelected(row("07".repeat(32), 0, DEEP_POOL_DATUM, amt(MSP, 1), amt(LP_FLDT, 1_000L)));
    }

    /** Enormous reserves for the right pair, the right LP unit — and no MSP. A forgery, not a pool. */
    @Test
    void forgedReservesWithoutAnMspAreNeverSelected() {
        assertNeverSelected(row("08".repeat(32), 0, FORGED_RESERVES_DATUM, amt(LP_ASCEND, 1_000_000L)));
    }

    /** More than one page's worth (10) of junk holding the pair's LP unit, the authentic pool first, middle, last. */
    @Test
    void moreThanTenJunkRowsNeverHideOrDisplaceTheAuthenticPool() {
        String good = "aa".repeat(32);
        for (int position : new int[]{0, 6, 12}) {
            List<AddressUtxoEntity> rows = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                String hash = String.format("%064x", i + 1);
                rows.add(i % 2 == 0
                        ? row(hash, 0, FORGED_RESERVES_DATUM, amt(LP_ASCEND, 1_000L))
                        : row(hash, 0, null, amt(LP_ASCEND, 1_000L)));
            }
            rows.add(position, authenticAscend(good));

            assertEquals(good + "#1", outRef(ascend(rows).orElseThrow(
                    () -> new AssertionError("authentic pool at position " + position + " was not found"))),
                    "authentic pool at position " + position);
        }
    }

    // ---- ambiguity is an index-integrity error -------------------------------------------------------

    @Test
    void twoAuthenticPoolsForOnePairAreRefusedNamingBoth() {
        String first = "aa".repeat(32);
        String second = "bb".repeat(32);
        var snapshot = resolver(repo(List.of(authenticAscend(first), authenticAscend(second)))).snapshot();

        var refused = assertThrows(MinswapPoolResolver.RefusedException.class,
                () -> snapshot.resolveEitherOrder(ADA, ASCEND));

        assertEquals(MinswapPoolResolver.Refusal.AMBIGUOUS_POOL, refused.refusal());
        String detail = refused.getMessage().substring((MinswapPoolResolver.Refusal.AMBIGUOUS_POOL + ": ").length());
        assertTrue(detail.startsWith("index integrity:"), refused.getMessage());
        assertTrue(refused.getMessage().contains(first + "#1"), refused.getMessage());
        assertTrue(refused.getMessage().contains(second + "#1"), refused.getMessage());
    }

    // ---- one query, and a failed query is a failure ----------------------------------------------------

    @Test
    void oneSnapshotIsExactlyOneUnpagedCredentialQueryHoweverManyLookupsFollow() {
        UtxoRepository repository = repo(List.of(authenticAscend("aa".repeat(32))));
        var snapshot = resolver(repository).snapshot();

        snapshot.resolveEitherOrder(ADA, ASCEND);
        snapshot.resolveEitherOrder(ASCEND, ADA);
        snapshot.resolveEitherOrder(ADA, FLDT);
        snapshot.resolveEitherOrder(FLDT, ADA);

        verify(repository, times(1)).findUnspentByOwnerPaymentCredential(eq(POOL_CREDENTIAL),
                argThat(Pageable::isUnpaged));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void aDatabaseFailureIsLookupFailedNeverAnEmptyAnswer() {
        UtxoRepository repository = mock(UtxoRepository.class);
        when(repository.findUnspentByOwnerPaymentCredential(any(), any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("connection refused"));

        var refused = assertThrows(MinswapPoolResolver.RefusedException.class,
                () -> resolver(repository).snapshot().resolveEitherOrder(ADA, ASCEND));

        assertEquals(MinswapPoolResolver.Refusal.LOOKUP_FAILED, refused.refusal());
        assertTrue(refused.getMessage().contains("connection refused"), refused.getMessage());
    }

    // ---- superset of the old Blockfrost resolver -------------------------------------------------------

    /**
     * ⊇: on the task-1 fixture the old Blockfrost resolver returned {@link #OLD_ASCEND_OUT_REF} for
     * (ada, ASCEND) and {@link #OLD_FLDT_OUT_REF} for (ada, FLDT), in either asking order. The datums are
     * real (2026-09-18 and the shipped ada/FLDT fixture); <b>the amounts are SYNTHESISED</b> — each real
     * pool holds its MSP and its own LP unit, and the ada / LP-token pool also holds the ada/ASCEND LP
     * asset as its reserve, which is why it answered the old per-asset query.
     */
    @Test
    void theIndexAnswerContainsEveryPoolTheOldBlockfrostLookupReturned() throws IOException {
        String fldtDatum = Files.readString(Path.of("src/test/resources/loans-v4/mainnet-minswap-pool-ada-fldt.hex")).trim();
        List<AddressUtxoEntity> rows = List.of(
                row("4c805499".repeat(8), 1, DEEP_POOL_DATUM, amt(MSP, 1), amt(LP_ASCEND, 1_000L), amt(ASCEND.toUnit(), 5)),
                row("02db9d9b".repeat(8), 1, OTHER_PAIR_DATUM, amt(MSP, 1), amt(LP_OTHER, 1_000L), amt(LP_ASCEND, 14)),
                row("0f1d0f1d".repeat(8), 0, fldtDatum, amt(MSP, 1), amt(LP_FLDT, 1_000L), amt(FLDT.toUnit(), 7)));
        var snapshot = resolver(repo(rows)).snapshot();

        assertEquals(OLD_ASCEND_OUT_REF, outRef(snapshot.resolveEitherOrder(ADA, ASCEND).orElseThrow()));
        assertEquals(OLD_ASCEND_OUT_REF, outRef(snapshot.resolveEitherOrder(ASCEND, ADA).orElseThrow()));
        assertEquals(OLD_FLDT_OUT_REF, outRef(snapshot.resolveEitherOrder(ADA, FLDT).orElseThrow()));
        assertEquals(OLD_FLDT_OUT_REF, outRef(snapshot.resolveEitherOrder(FLDT, ADA).orElseThrow()));
    }

    // ---- the shipped coordinates agree -----------------------------------------------------------------

    /** The base document's pool address carries the shipped pool-spend-script-hash, under the shipped policy. */
    @Test
    void theShippedPoolAddressCarriesTheShippedSpendScriptHash() throws IOException {
        PropertySource<?> base = new YamlPropertySourceLoader()
                .load("application.yaml", new ClassPathResource("application.yaml")).getFirst();

        String address = shippedDefault(base, "loans.minswap.pool-address");
        String spendHash = shippedDefault(base, "loans.minswap.pool-spend-script-hash");
        String policy = shippedDefault(base, "loans.minswap.pool-policy-id");

        String paymentCredential = HexUtil.encodeHexString(new Address(address).getPaymentCredentialHash().orElseThrow());
        assertEquals(spendHash, paymentCredential);
        assertEquals(POOL_CREDENTIAL, spendHash);
        assertEquals(POOL_POLICY, policy);
    }

    /** {@code ${ENV:default}} → the default, which is what ships; a bare value is returned as is. */
    private static String shippedDefault(PropertySource<?> source, String key) {
        Object raw = source.getProperty(key);
        assertTrue(raw != null, key + " is missing from the base document");
        Matcher placeholder = Pattern.compile("^\\$\\{[A-Z0-9_]+:(.*)}$").matcher(raw.toString());
        return placeholder.matches() ? placeholder.group(1) : raw.toString();
    }
}
