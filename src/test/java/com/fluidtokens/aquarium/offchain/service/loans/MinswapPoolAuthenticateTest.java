package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.util.UtxoUtil;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The four pool-authentication rules of {@link MinswapPoolResolver}, driven directly on a plain
 * cardano-client-lib {@link Utxo} through {@link MinswapPoolResolver#authenticate} — no repository, no
 * Spring. Each refusal breaks exactly one rule and satisfies every other, so each rule is pinned on its
 * own. {@link MinswapPoolResolver.Snapshot#of} is driven the same way, and one test shows the
 * repository-backed {@link MinswapPoolResolver#snapshot()} accepts exactly what {@code authenticate}
 * accepts.
 */
class MinswapPoolAuthenticateTest {

    private static final String POOL_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String POOL_CREDENTIAL = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MSP = POOL_POLICY + ConvertTxEncoder.POOL_NFT_ASSET_NAME;

    private static final AssetType ADA = AssetType.ada();
    private static final AssetType ASCEND = AssetType.fromUnit(
            "eb7a93ebc321647673490810f618b548d7c24aa64d30ae342dba70760014df10415343454e44");
    private static final AssetType FLDT = new AssetType(
            "577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454");

    private static final String LP_ASCEND = POOL_POLICY + ConvertTxEncoder.computeLpAssetName(ADA, ASCEND);
    /** The ada / LP(ada,ASCEND)-token pool's own LP unit. */
    private static final String LP_OTHER = POOL_POLICY
            + ConvertTxEncoder.computeLpAssetName(ADA, AssetType.fromUnit(LP_ASCEND));

    /** ⚑ Read off {@code 4c805499…#1} on 2026-09-18: the real ada/ASCEND pool. */
    private static final String DEEP_POOL_DATUM = "d8799fd8799fd87a9f581c1eae96baf29e27682ea3f815aba361a0c6059d45e4bfbe95bbd2f44affffd8799f4040ffd8799f581ceb7a93ebc321647673490810f618b548d7c24aa64d30ae342dba70764a0014df10415343454e44ff1b000000ba6be20bdc1b000000ce9358c8a91b000000ed5e5802cf18641864d8799f190682ffd87980ff";

    /** ⚑ Read off {@code 02db9d9b…#1} on 2026-09-18: an ada / LP-token pool whose assetB IS the ada/ASCEND LP asset. */
    private static final String OTHER_PAIR_DATUM = "d8799fd8799fd87a9f581c1eae96baf29e27682ea3f815aba361a0c6059d45e4bfbe95bbd2f44affffd8799f4040ffd8799f581cf5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c5820e66195788208dcd363edb600eaf2331019e3599baba645d81d61ef060c82d861ff0b0e09181e181ed8799f190682ffd87980ff";

    /** The shipped mainnet pool address: payment part {@link #POOL_CREDENTIAL}. */
    private static final String POOL_ADDR = "addr1z84q0denmyep98ph3tmzwsmw0j7zau9ljmsqx6a4rvaau66j2c79gy9l76sdg0xwhd7r0c0kna0tycz4y5s6mlenh8pq777e2a";

    private static final byte[] OTHER_STAKE_KEY_HASH = HexUtil.decodeHexString("11".repeat(28));
    private static final byte[] OTHER_HASH = HexUtil.decodeHexString("22".repeat(28));

    // ---- fixture plumbing --------------------------------------------------------------------------

    private static Amount amount(String unit, long quantity) {
        return Amount.builder().unit(unit).quantity(BigInteger.valueOf(quantity)).build();
    }

    private static Utxo utxo(String txHash, int index, String address, String datum, Amount... amounts) {
        List<Amount> list = new ArrayList<>();
        list.add(amount("lovelace", 5_000_000L));
        list.addAll(Arrays.asList(amounts));
        return Utxo.builder()
                .txHash(txHash)
                .outputIndex(index)
                .address(address)
                .inlineDatum(datum)
                .amount(list)
                .build();
    }

    /** The authentic ada/ASCEND pool: lovelace + MSP×1 + its own LP unit + ASCEND, the real datum. */
    private static Utxo authenticAscend(String txHash, String address) {
        return utxo(txHash, 1, address, DEEP_POOL_DATUM,
                amount(MSP, 1), amount(LP_ASCEND, 9_000_000_000L), amount(ASCEND.toUnit(), 5));
    }

    private static Optional<MinswapPoolResolver.ResolvedPool> authenticate(Utxo utxo) {
        return MinswapPoolResolver.authenticate(utxo, POOL_CREDENTIAL, POOL_POLICY);
    }

    /** Empty, and reaching that answer threw nothing. */
    private static void assertRefused(Utxo utxo) {
        Optional<MinswapPoolResolver.ResolvedPool> result = assertDoesNotThrow(() -> authenticate(utxo));
        assertTrue(result.isEmpty(), "must not be an authentic pool: " + utxo);
    }

    private static String outRef(MinswapPoolResolver.ResolvedPool pool) {
        return pool.utxo().getTxHash() + "#" + pool.utxo().getOutputIndex();
    }

    /** POOL_CREDENTIAL as a script payment part, under a stake key that is not the shipped address's. */
    private static String poolCredentialUnderOtherStake() {
        return AddressProvider.getBaseAddress(Credential.fromScript(HexUtil.decodeHexString(POOL_CREDENTIAL)),
                Credential.fromKey(OTHER_STAKE_KEY_HASH), Networks.mainnet()).getAddress();
    }

    // ---- accepted ------------------------------------------------------------------------------------

    @Test
    void theAuthenticPoolIsAccepted() {
        var pool = authenticate(authenticAscend("aa".repeat(32), POOL_ADDR)).orElseThrow();

        assertEquals("aa".repeat(32) + "#1", outRef(pool));
        assertEquals(ConvertTxEncoder.computeLpAssetName(ADA, ASCEND), pool.lpAssetName());
        assertEquals(ADA.toUnit(), pool.datum().assetA().toUnit());
        assertEquals(ASCEND.toUnit(), pool.datum().assetB().toUnit());
    }

    @Test
    void theSamePoolUnderAnotherStakeCredentialIsAccepted() {
        String address = poolCredentialUnderOtherStake();
        Address parsed = new Address(address);
        assertEquals(POOL_CREDENTIAL, HexUtil.encodeHexString(parsed.getPaymentCredentialHash().orElseThrow()),
                "fixture: the payment credential is the pool's");
        assertNotEquals(HexUtil.encodeHexString(new Address(POOL_ADDR).getDelegationCredentialHash().orElseThrow()),
                HexUtil.encodeHexString(parsed.getDelegationCredentialHash().orElseThrow()),
                "fixture: the stake credential is not the shipped address's");

        var pool = authenticate(authenticAscend("ab".repeat(32), address));

        assertEquals("ab".repeat(32) + "#1", outRef(pool.orElseThrow()),
                "the pool is identified by its payment credential; the stake part is anyone's choice");
    }

    // ---- refused: rule 1, the payment credential -----------------------------------------------------

    @Test
    void anAddressWhosePaymentCredentialIsAKeyHashIsRefused() {
        String enterprise = AddressProvider.getEntAddress(Credential.fromKey(OTHER_HASH), Networks.mainnet())
                .getAddress();
        String base = AddressProvider.getBaseAddress(Credential.fromKey(OTHER_HASH),
                Credential.fromKey(OTHER_STAKE_KEY_HASH), Networks.mainnet()).getAddress();
        assertTrue(enterprise.startsWith("addr1v"), enterprise);
        assertTrue(base.startsWith("addr1q"), base);

        assertRefused(authenticAscend("10".repeat(32), enterprise));
        assertRefused(authenticAscend("11".repeat(32), base));
    }

    @Test
    void anAddressWhosePaymentCredentialIsAnotherScriptHashIsRefused() {
        String otherScript = AddressProvider.getBaseAddress(Credential.fromScript(OTHER_HASH),
                Credential.fromKey(OTHER_STAKE_KEY_HASH), Networks.mainnet()).getAddress();
        assertTrue(new Address(otherScript).isScriptHashInPaymentPart(), otherScript);

        assertRefused(authenticAscend("12".repeat(32), otherScript));
    }

    @Test
    void aRewardAddressHasNoPaymentCredentialAndIsRefused() {
        String reward = AddressProvider.getRewardAddress(
                Credential.fromScript(HexUtil.decodeHexString(POOL_CREDENTIAL)), Networks.mainnet()).getAddress();
        assertTrue(reward.startsWith("stake1"), reward);

        assertRefused(authenticAscend("13".repeat(32), reward));
    }

    @Test
    void anUnparseableAddressIsRefused() {
        assertRefused(authenticAscend("14".repeat(32), "not_an_address"));
    }

    @Test
    void aNullAddressIsRefused() {
        assertRefused(authenticAscend("15".repeat(32), null));
    }

    @Test
    void nullArgumentsAreRefused() {
        Utxo pool = authenticAscend("16".repeat(32), POOL_ADDR);
        assertTrue(MinswapPoolResolver.authenticate(null, POOL_CREDENTIAL, POOL_POLICY).isEmpty());
        assertTrue(MinswapPoolResolver.authenticate(pool, null, POOL_POLICY).isEmpty());
        assertTrue(MinswapPoolResolver.authenticate(pool, POOL_CREDENTIAL, null).isEmpty());
    }

    // ---- refused: rule 2, exactly one MSP of quantity 1 ----------------------------------------------

    @Test
    void noMspIsRefused() {
        assertRefused(utxo("20".repeat(32), 0, POOL_ADDR, DEEP_POOL_DATUM, amount(LP_ASCEND, 1_000L)));
    }

    @Test
    void mspQuantityTwoIsRefused() {
        assertRefused(utxo("21".repeat(32), 0, POOL_ADDR, DEEP_POOL_DATUM,
                amount(MSP, 2), amount(LP_ASCEND, 1_000L)));
    }

    @Test
    void twoMspEntriesOfOneEachAreRefused() {
        assertRefused(utxo("22".repeat(32), 0, POOL_ADDR, DEEP_POOL_DATUM,
                amount(MSP, 1), amount(MSP, 1), amount(LP_ASCEND, 1_000L)));
    }

    @Test
    void anMspUnderAnotherPolicyIsRefused() {
        assertRefused(utxo("23".repeat(32), 0, POOL_ADDR, DEEP_POOL_DATUM,
                amount("ee".repeat(28) + ConvertTxEncoder.POOL_NFT_ASSET_NAME, 1), amount(LP_ASCEND, 1_000L)));
    }

    // ---- refused: rule 3, a decodable inline datum ---------------------------------------------------

    @Test
    void noInlineDatumIsRefused() {
        assertRefused(utxo("30".repeat(32), 0, POOL_ADDR, null, amount(MSP, 1), amount(LP_ASCEND, 1_000L)));
        assertRefused(utxo("31".repeat(32), 0, POOL_ADDR, "", amount(MSP, 1), amount(LP_ASCEND, 1_000L)));
    }

    @Test
    void anUndecodableDatumIsRefused() {
        assertRefused(utxo("32".repeat(32), 0, POOL_ADDR, "d87980", amount(MSP, 1), amount(LP_ASCEND, 1_000L)));
        assertRefused(utxo("33".repeat(32), 0, POOL_ADDR, "zz-not-hex", amount(MSP, 1), amount(LP_ASCEND, 1_000L)));
    }

    // ---- refused: rule 4, its own recomputed LP unit -------------------------------------------------

    /** The ada / LP-token pool's datum and MSP, holding only the ada/ASCEND LP unit. */
    @Test
    void anotherPairsDatumHoldingOnlyThisPairsLpIsRefused() {
        assertRefused(utxo("40".repeat(32), 0, POOL_ADDR, OTHER_PAIR_DATUM, amount(MSP, 1), amount(LP_ASCEND, 14L)));
    }

    /** The ada/ASCEND datum and MSP, holding only the ada / LP-token pool's LP unit. */
    @Test
    void thisPairsDatumHoldingOnlyAnotherPairsLpIsRefused() {
        assertRefused(utxo("41".repeat(32), 0, POOL_ADDR, DEEP_POOL_DATUM, amount(MSP, 1), amount(LP_OTHER, 1_000L)));
    }

    @Test
    void theOwnLpMustHaveAPositiveQuantity() {
        var pool = authenticate(utxo("42".repeat(32), 0, POOL_ADDR, DEEP_POOL_DATUM,
                amount(MSP, 1), amount(LP_ASCEND, 1L)));
        assertEquals("42".repeat(32) + "#0", outRef(pool.orElseThrow()), "one LP token is a positive quantity");

        assertRefused(utxo("43".repeat(32), 0, POOL_ADDR, DEEP_POOL_DATUM, amount(MSP, 1), amount(LP_ASCEND, 0L)));
    }

    // ---- a hand-built amount list may carry a null element -------------------------------------------

    /** A caller-built {@link Utxo} can hold a null amount; such a UTxO is malformed and no pool, and nothing throws. */
    @Test
    void aNullAmountElementMakesItNoPoolWithoutThrowing() {
        Utxo authentic = authenticAscend("50".repeat(32), POOL_ADDR);
        List<Amount> withNulls = new ArrayList<>(authentic.getAmount());
        assertEquals(MSP, withNulls.get(1).getUnit(), "fixture: MSP follows lovelace");
        assertEquals(LP_ASCEND, withNulls.get(2).getUnit(), "fixture: the LP follows the MSP");
        withNulls.add(2, null);
        withNulls.add(0, null);
        authentic.setAmount(withNulls);

        assertRefused(authentic);

        Utxo noMsp = utxo("51".repeat(32), 0, POOL_ADDR, DEEP_POOL_DATUM, amount(LP_ASCEND, 1_000L));
        List<Amount> noMspWithNull = new ArrayList<>(noMsp.getAmount());
        noMspWithNull.add(1, null);
        noMsp.setAmount(noMspWithNull);

        assertRefused(noMsp);
    }

    /** An accepted pool may become a transaction input, so its UTxO is the very one checked, with no null amount. */
    @Test
    void everyAcceptedPoolCarriesNoNullAmount() {
        Utxo authentic = authenticAscend("52".repeat(32), POOL_ADDR);

        var pool = authenticate(authentic).orElseThrow();

        assertSame(authentic, pool.utxo(), "the accepted record carries the UTxO authenticated, not a copy");
        assertTrue(pool.utxo().getAmount().stream().noneMatch(Objects::isNull), "no null amount element");
    }

    /** Rule 2 is exactly one MSP entry: a second MSP entry with a null quantity is not overlooked. */
    @Test
    void anMspWithNullQuantityBesideAnMspOfOneIsRefused() {
        Amount nullQuantityMsp = Amount.builder().unit(MSP).quantity(null).build();

        assertRefused(utxo("53".repeat(32), 1, POOL_ADDR, DEEP_POOL_DATUM, nullQuantityMsp, amount(MSP, 1),
                amount(LP_ASCEND, 9_000_000_000L), amount(ASCEND.toUnit(), 5)));
    }

    // ---- Snapshot.of ---------------------------------------------------------------------------------

    @Test
    void aSnapshotOfOnePoolResolvesItsPairInEitherOrderAndNoOtherPair() {
        var pool = authenticate(authenticAscend("aa".repeat(32), POOL_ADDR)).orElseThrow();
        var snapshot = MinswapPoolResolver.Snapshot.of(POOL_POLICY, List.of(pool));

        assertEquals(pool, snapshot.resolveEitherOrder(ADA, ASCEND).orElseThrow());
        assertEquals(pool, snapshot.resolveEitherOrder(ASCEND, ADA).orElseThrow());
        assertTrue(snapshot.resolveEitherOrder(ADA, FLDT).isEmpty());
    }

    @Test
    void aSnapshotOfTwoPoolsForOnePairIsAmbiguousNamingBoth() {
        String first = "aa".repeat(32);
        String second = "bb".repeat(32);
        var a = authenticate(authenticAscend(first, POOL_ADDR)).orElseThrow();
        var b = authenticate(authenticAscend(second, poolCredentialUnderOtherStake())).orElseThrow();
        var snapshot = MinswapPoolResolver.Snapshot.of(POOL_POLICY, List.of(a, b));

        var refused = assertThrows(MinswapPoolResolver.RefusedException.class,
                () -> snapshot.resolveEitherOrder(ADA, ASCEND));

        assertEquals(MinswapPoolResolver.Refusal.AMBIGUOUS_POOL, refused.refusal());
        assertTrue(refused.getMessage().contains(first + "#1"), refused.getMessage());
        assertTrue(refused.getMessage().contains(second + "#1"), refused.getMessage());
    }

    @Test
    void anEmptySnapshotResolvesEveryPairToEmpty() {
        var snapshot = MinswapPoolResolver.Snapshot.of(POOL_POLICY, List.of());

        assertTrue(snapshot.resolveEitherOrder(ADA, ASCEND).isEmpty());
        assertTrue(snapshot.resolveEitherOrder(ADA, FLDT).isEmpty());
    }

    @Test
    void aNullPoolIsANullPointerException() {
        List<MinswapPoolResolver.ResolvedPool> withNull = new ArrayList<>();
        withNull.add(null);

        assertThrows(NullPointerException.class, () -> MinswapPoolResolver.Snapshot.of(POOL_POLICY, withNull));
    }

    // ---- the snapshot path applies the same rules ----------------------------------------------------

    private static Amt amt(String unit, long quantity) {
        Amt amount = new Amt();
        amount.setUnit(unit);
        amount.setQuantity(BigInteger.valueOf(quantity));
        return amount;
    }

    private static AddressUtxoEntity row(String txHash, int index, String paymentCredential, String datum,
                                         Amt... amounts) {
        AddressUtxoEntity entity = new AddressUtxoEntity();
        entity.setTxHash(txHash);
        entity.setOutputIndex(index);
        entity.setOwnerAddr(POOL_ADDR);
        entity.setOwnerPaymentCredential(paymentCredential);
        entity.setInlineDatum(datum);
        List<Amt> list = new ArrayList<>();
        list.add(amt("lovelace", 5_000_000L));
        list.addAll(List.of(amounts));
        entity.setAmounts(list);
        return entity;
    }

    /** Serves every row it is given for the pool credential, whatever each row's own column says. */
    private static UtxoRepository repo(List<AddressUtxoEntity> rows) {
        UtxoRepository repository = mock(UtxoRepository.class);
        when(repository.findUnspentByOwnerPaymentCredential(eq(POOL_CREDENTIAL), any(Pageable.class)))
                .thenReturn(Optional.of(rows));
        return repository;
    }

    private static MinswapPoolResolver.Snapshot snapshot(List<AddressUtxoEntity> rows) {
        return new MinswapPoolResolver(repo(rows), POOL_CREDENTIAL, POOL_POLICY).snapshot();
    }

    @Test
    void theSnapshotAcceptsExactlyTheRowsAuthenticateAccepts() {
        String authentic = "aa".repeat(32);
        List<AddressUtxoEntity> rows = List.of(
                row(authentic, 1, POOL_CREDENTIAL, DEEP_POOL_DATUM,
                        amt(MSP, 1), amt(LP_ASCEND, 9_000_000_000L), amt(ASCEND.toUnit(), 5)),
                row("01".repeat(32), 0, POOL_CREDENTIAL, DEEP_POOL_DATUM, amt(LP_ASCEND, 1_000L)),
                row("02".repeat(32), 0, POOL_CREDENTIAL, DEEP_POOL_DATUM, amt(MSP, 2), amt(LP_ASCEND, 1_000L)),
                row("03".repeat(32), 0, POOL_CREDENTIAL, OTHER_PAIR_DATUM, amt(MSP, 1), amt(LP_ASCEND, 14L)));

        var snapshot = snapshot(rows);
        Set<String> bySnapshot = new HashSet<>();
        for (AssetType[] pair : new AssetType[][]{{ADA, ASCEND}, {ADA, AssetType.fromUnit(LP_ASCEND)}, {ADA, FLDT}}) {
            snapshot.resolveEitherOrder(pair[0], pair[1]).ifPresent(p -> bySnapshot.add(outRef(p)));
        }
        Set<String> byAuthenticate = rows.stream()
                .map(r -> MinswapPoolResolver.authenticate(UtxoUtil.toUtxo(r), POOL_CREDENTIAL, POOL_POLICY))
                .flatMap(Optional::stream)
                .map(MinswapPoolAuthenticateTest::outRef)
                .collect(Collectors.toSet());

        assertEquals(byAuthenticate, bySnapshot);
        assertEquals(Set.of(authentic + "#1"), bySnapshot);
    }

    /** Rule 1 on the snapshot path is the row's {@code owner_payment_credential} column. */
    @Test
    void aSnapshotRowWhosePaymentCredentialColumnDiffersIsRefused() {
        var snapshot = snapshot(List.of(row("aa".repeat(32), 1, "ee".repeat(28), DEEP_POOL_DATUM,
                amt(MSP, 1), amt(LP_ASCEND, 9_000_000_000L))));

        assertTrue(snapshot.resolveEitherOrder(ADA, ASCEND).isEmpty());
    }
}
