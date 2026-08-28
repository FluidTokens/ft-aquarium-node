package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LiquidationUtxoResolver} against a stubbed index.
 * <p>
 * This class answers the two questions that decide whether a liquidation spends the right outputs:
 * "is this UTxO <em>still</em> unspent" and "which UTxO at the config credential <em>is</em> the
 * config". Both are filters, and an unexercised filter is one that can be deleted without anyone
 * noticing — which is what these tests exist to prevent. Each one is written so that removing the
 * rule it covers makes it fail, rather than merely making it less precise.
 * <p>
 * {@link UtxoRepository} is a Spring Data interface with a large inherited surface, so it is stubbed
 * with a {@link Proxy} that serves the single method this class calls and throws on everything else
 * — a change of lookup strategy inside the resolver then shows up as a failure rather than as a
 * silently empty result. The rows are real {@link AddressUtxoEntity} instances, so
 * {@code UtxoUtil.toUtxo} performs exactly the mapping it performs in production.
 */
class LiquidationUtxoResolverTest {

    private static final String CONFIG_POLICY_ID = LoanFixtures.CONFIG_POLICY_ID;
    private static final String LM_CONFIG_POLICY_ID = LoanFixtures.LM_CONFIG_POLICY_ID;
    private static final String CONFIG_ASSET_NAME = LoanFixtures.CONFIG_ASSET_NAME;

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    private static final String TX_A = "aa".repeat(32);
    private static final String TX_B = "bb".repeat(32);
    private static final String TX_C = "cc".repeat(32);

    private static final String LOAN_ID = "a1b2c3d4e5f6a1b2";

    /** A token that is not a config NFT, for the decoy rows. */
    private static final String DECOY_UNIT = "ee".repeat(28) + "4445434f59";

    // ---- the index stub ------------------------------------------------------------------------

    /**
     * An in-memory stand-in for the Yaci Store repository: payment credential to the rows currently
     * unspent at it.
     */
    private static UtxoRepository index(Map<String, List<AddressUtxoEntity>> unspentByCredential) {
        return (UtxoRepository) Proxy.newProxyInstance(
                LiquidationUtxoResolverTest.class.getClassLoader(),
                new Class<?>[]{UtxoRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findUnspentByOwnerPaymentCredential" ->
                            Optional.of(unspentByCredential.getOrDefault((String) args[0], List.of()));
                    case "toString" -> "stub UtxoRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "the resolver called " + method.getName() + "; this stub only serves "
                                    + "findUnspentByOwnerPaymentCredential, so the resolver is no "
                                    + "longer reading the local index the way this test assumes");
                });
    }

    private static AddressUtxoEntity row(String txHash, int outputIndex, String ownerAddr,
                                         List<Amt> amounts) {
        AddressUtxoEntity entity = new AddressUtxoEntity();
        entity.setTxHash(txHash);
        entity.setOutputIndex(outputIndex);
        entity.setOwnerAddr(ownerAddr);
        entity.setAmounts(new ArrayList<>(amounts));
        return entity;
    }

    private static Amt amt(String unit, long quantity) {
        Amt amount = new Amt();
        amount.setUnit(unit);
        amount.setQuantity(BigInteger.valueOf(quantity));
        return amount;
    }

    private static Amt lovelace(long quantity) {
        return amt(AssetType.LOVELACE, quantity);
    }

    /** The config NFT: the whole point of the filter under test. */
    private static Amt configNft(String policyId) {
        return amt(policyId + CONFIG_ASSET_NAME, 1L);
    }

    /**
     * {@code AppConfig.Network} binds its field from {@code ${network}}, so without a context it
     * would switch on null. Overriding the one method the resolver uses keeps this test free of both
     * a Spring context and reflection.
     */
    private static AppConfig.Network previewNetwork() {
        return new AppConfig.Network() {
            @Override
            public com.bloxbean.cardano.client.common.model.Network getCardanoNetwork() {
                return LoanFixtures.NETWORK;
            }
        };
    }

    private static LiquidationUtxoResolver resolver(Map<String, List<AddressUtxoEntity>> unspent) {
        return new LiquidationUtxoResolver(index(unspent), REGISTRY, previewNetwork());
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private static Loan loan(String txHash, int outputIndex) {
        return new Loan(txHash, outputIndex, LoanFixtures.loanAddress(), LOAN_ID,
                BigInteger.valueOf(100_000_000), BigInteger.valueOf(100_000_000),
                LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                        BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), 1_600_000_000_000L,
                        LoanFixtures.liquidation(),
                        new RepaymentMode.PrincipalAndInterestOnInstallments(), false));
    }

    private static LenderBond bond(String txHash, int outputIndex) {
        LenderManagerDatum datum = LoanFixtures.bondDatum(BigInteger.TEN,
                LoanFixtures.noStakeCredential(), AssetType.ada());
        return new LenderBond(txHash, outputIndex, LoanFixtures.bondAddress(), LOAN_ID,
                LoanFixtures.hex(datum), datum);
    }

    // ======================================================================================
    // R1 — "still unspent", at exactly these coordinates
    // ======================================================================================

    @Test
    void aLoanUtxoStillInTheUnspentSetIsResolved() {
        var unspent = Map.of(REGISTRY.getLoanSpendScriptHash(),
                List.of(row(TX_A, 0, LoanFixtures.loanAddress(), List.of(lovelace(100_000_000)))));

        Optional<Utxo> resolved = resolver(unspent).resolveLoanUtxo(loan(TX_A, 0));

        assertTrue(resolved.isPresent());
        assertEquals(TX_A, resolved.get().getTxHash());
        assertEquals(0, resolved.get().getOutputIndex());
        assertEquals(LoanFixtures.loanAddress(), resolved.get().getAddress());
        assertEquals(BigInteger.valueOf(100_000_000), resolved.get().getAmount().getFirst().getQuantity());
    }

    /**
     * The output was spent between the scan and the build. Reporting it as present would hand the
     * builder a UTxO that no longer exists, and the transaction would die at submission with the fee
     * already committed.
     */
    @Test
    void aLoanUtxoAbsentFromTheUnspentSetResolvesToEmpty() {
        var unspent = Map.of(REGISTRY.getLoanSpendScriptHash(),
                List.of(row(TX_B, 0, LoanFixtures.loanAddress(), List.of(lovelace(100_000_000)))));

        assertTrue(resolver(unspent).resolveLoanUtxo(loan(TX_A, 0)).isEmpty(),
                "a different transaction's output must not stand in for this one");
    }

    /**
     * The sharper half of R1: same transaction, different output index. A resolver matching on the
     * tx hash alone would return output 0 for a loan that lives at output 1 — the same hash, a
     * different value, a different datum.
     */
    @Test
    void aDifferentOutputIndexOfTheSameTransactionResolvesToEmpty() {
        var unspent = Map.of(REGISTRY.getLoanSpendScriptHash(),
                List.of(row(TX_A, 0, LoanFixtures.loanAddress(), List.of(lovelace(100_000_000)))));

        assertTrue(resolver(unspent).resolveLoanUtxo(loan(TX_A, 1)).isEmpty(),
                "output 1 is not output 0, however alike the two look");
    }

    /** The mirror of the above for the bond leg, since the two are resolved independently. */
    @Test
    void aBondUtxoIsResolvedFromTheLenderManagerCredentialOnly() {
        var unspent = Map.of(
                REGISTRY.getLenderManagerSpendScriptHash(),
                List.of(row(TX_C, 2, LoanFixtures.bondAddress(), List.of(lovelace(2_000_000)))),
                // The same coordinates sitting at the loan credential must not satisfy a bond lookup.
                REGISTRY.getLoanSpendScriptHash(),
                List.of(row(TX_C, 2, LoanFixtures.loanAddress(), List.of(lovelace(2_000_000)))));

        Optional<Utxo> resolved = resolver(unspent).resolveBondUtxo(bond(TX_C, 2));

        assertTrue(resolved.isPresent());
        assertEquals(LoanFixtures.bondAddress(), resolved.get().getAddress(),
                "the bond must come from the LenderManager credential, not the loan one");
    }

    @Test
    void aBondUtxoAbsentFromTheUnspentSetResolvesToEmpty() {
        var unspent = Map.of(REGISTRY.getLenderManagerSpendScriptHash(), List.<AddressUtxoEntity>of());

        assertTrue(resolver(unspent).resolveBondUtxo(bond(TX_C, 2)).isEmpty());
    }

    // ======================================================================================
    // R2/R3 — the config NFT filter
    // ======================================================================================

    /**
     * The filter under test, stated as the mutant it has to kill: anyone may pay to the config
     * validator's address, so "the first unspent output at the config credential" is emphatically not
     * the config. Only the holder of {@code policyId + configAssetName} is — the identification rule
     * {@code LoansConfigVerifier.fetchConfigDatumHex} applies, read from the local index instead of
     * Blockfrost. The NFT holder is placed <b>last</b> here precisely so that dropping the filter
     * selects a decoy rather than the right answer by luck.
     */
    @Test
    void theConfigUtxoIsTheNftHolderEvenWhenItIsNotFirst() {
        String address = LoanFixtures.entAddress(CONFIG_POLICY_ID);
        var unspent = Map.of(CONFIG_POLICY_ID, List.of(
                row(TX_A, 0, address, List.of(lovelace(2_000_000))),
                row(TX_B, 7, address, List.of(lovelace(5_000_000), amt(DECOY_UNIT, 1L))),
                row(TX_C, 3, address, List.of(lovelace(5_000_000), configNft(CONFIG_POLICY_ID)))));

        Optional<Utxo> configUtxo = resolver(unspent).resolveConfigUtxo();

        assertTrue(configUtxo.isPresent());
        assertEquals(TX_C, configUtxo.get().getTxHash(),
                "the config is the NFT holder, not whatever happens to be listed first");
        assertEquals(3, configUtxo.get().getOutputIndex());
    }

    @Test
    void theLmConfigUtxoIsTheLmNftHolderEvenWhenItIsNotFirst() {
        String address = LoanFixtures.entAddress(LM_CONFIG_POLICY_ID);
        var unspent = Map.of(LM_CONFIG_POLICY_ID, List.of(
                row(TX_A, 0, address, List.of(lovelace(2_000_000))),
                // The MAIN config NFT is not the LM config NFT, however alike the two are shaped.
                row(TX_B, 1, address, List.of(lovelace(5_000_000), configNft(CONFIG_POLICY_ID))),
                row(TX_C, 5, address, List.of(lovelace(5_000_000), configNft(LM_CONFIG_POLICY_ID)))));

        Optional<Utxo> lmConfigUtxo = resolver(unspent).resolveLmConfigUtxo();

        assertTrue(lmConfigUtxo.isPresent());
        assertEquals(TX_C, lmConfigUtxo.get().getTxHash());
        assertEquals(5, lmConfigUtxo.get().getOutputIndex());
    }

    /**
     * Nothing at the credential holds the NFT. Empty is the only honest answer: the executor turns it
     * into a warning and skips the cycle, whereas a decoy would silently become the reference input
     * every script in the transaction reads its configuration from.
     */
    @Test
    void noNftHolderAtTheConfigCredentialResolvesToEmpty() {
        String address = LoanFixtures.entAddress(CONFIG_POLICY_ID);
        var unspent = Map.of(CONFIG_POLICY_ID, List.of(
                row(TX_A, 0, address, List.of(lovelace(2_000_000))),
                row(TX_B, 1, address, List.of(lovelace(9_000_000), amt(DECOY_UNIT, 1L)))));

        assertTrue(resolver(unspent).resolveConfigUtxo().isEmpty(),
                "ada and junk tokens paid to the config address are not the config");
    }

    @Test
    void anEmptyCredentialResolvesToEmptyForBothConfigs() {
        var resolver = resolver(Map.of());

        assertTrue(resolver.resolveConfigUtxo().isEmpty());
        assertTrue(resolver.resolveLmConfigUtxo().isEmpty());
    }

    /**
     * The two configs live at different credentials and must not be interchangeable: a resolver that
     * accepted any config-shaped NFT would feed the LM config datum into the main config's
     * reference-input slot, and every index derived from it would point at the wrong thing.
     */
    @Test
    void theTwoConfigLookupsUseTheirOwnCredentials() {
        var unspent = Map.of(
                CONFIG_POLICY_ID, List.of(row(TX_A, 0, LoanFixtures.entAddress(CONFIG_POLICY_ID),
                        List.of(lovelace(5_000_000), configNft(CONFIG_POLICY_ID)))),
                LM_CONFIG_POLICY_ID, List.of(row(TX_B, 0, LoanFixtures.entAddress(LM_CONFIG_POLICY_ID),
                        List.of(lovelace(5_000_000), configNft(LM_CONFIG_POLICY_ID)))));

        var resolver = resolver(unspent);

        assertEquals(TX_A, resolver.resolveConfigUtxo().orElseThrow().getTxHash());
        assertEquals(TX_B, resolver.resolveLmConfigUtxo().orElseThrow().getTxHash());
    }

    // ======================================================================================
    // T-070 — resolving a bond's pool by poolId
    // ======================================================================================

    private static final String POOL_ID = "00183f8ba4d1e645b1e26e9caf56f802b129b50d833689727c920abe11";
    private static final String OTHER_POOL_ID = "00993f8ba4d1e645b1e26e9caf56f802b129b50d833689727c920abe99";

    private static Map<String, List<AddressUtxoEntity>> poolIndex(String poolIdOnPool,
                                                                  String poolIdOnManager) {
        Map<String, List<AddressUtxoEntity>> unspent = new java.util.HashMap<>();
        if (poolIdOnPool != null) {
            unspent.put(REGISTRY.getPoolSpendScriptHash(), List.of(row(TX_A, 0,
                    LoanFixtures.entAddress(REGISTRY.getPoolSpendScriptHash()),
                    List.of(lovelace(5_000_000), amt(REGISTRY.getPoolPolicyId() + poolIdOnPool, 1L)))));
        }
        if (poolIdOnManager != null) {
            unspent.put(REGISTRY.getPoolManagerSpendScriptHash(), List.of(row(TX_B, 1,
                    LoanFixtures.entAddress(REGISTRY.getPoolManagerSpendScriptHash()),
                    List.of(lovelace(3_000_000),
                            amt(REGISTRY.getPoolManagerPolicyId() + poolIdOnManager, 1L)))));
        }
        return unspent;
    }

    @Test
    void aPoolAndItsManagerCarryingTheSamePoolIdResolveAsThePair() {
        LiquidationUtxoResolver.PoolPair pair =
                resolver(poolIndex(POOL_ID, POOL_ID)).resolvePool(POOL_ID);

        assertEquals(LiquidationUtxoResolver.PoolLookup.RESOLVED, pair.outcome(), pair.detail());
        assertEquals(TX_A, pair.pool().getTxHash());
        assertEquals(TX_B, pair.poolManager().getTxHash());
    }

    /**
     * ⛔ The finding this ticket was revised for (M-1). A node with more than one pool indexed holds a
     * UTxO at the pool credential and a UTxO at the pool-manager credential that belong to
     * <b>different pools</b>. "Both were located" is satisfied; the pair is wrong. Matching on the
     * NFT whose asset name IS the {@code poolId} is what makes it a pair rather than a coincidence.
     */
    @Test
    void aPoolAndAManagerFromDIFFERENTPoolsAreNotThePair() {
        LiquidationUtxoResolver.PoolPair pair =
                resolver(poolIndex(POOL_ID, OTHER_POOL_ID)).resolvePool(POOL_ID);

        assertEquals(LiquidationUtxoResolver.PoolLookup.HALF_VISIBLE, pair.outcome(),
                "a manager belonging to another pool must not be accepted as this pool's: "
                        + pair.detail());
        assertNull(pair.poolManager(), "the other pool's manager must not be returned");
    }

    /**
     * The one case the index CAN settle: something minted this pool's NFT, so the pool exists and the
     * missing half is a gap rather than a non-existent pool. Distinguished from
     * {@link LiquidationUtxoResolver.PoolLookup#NOT_VISIBLE} on purpose (M-2) — one refusal for both
     * would be the repeating-quarantine shape this epic refuses elsewhere.
     */
    @Test
    void aVisiblePoolWithNoManagerIsHalfVisibleRatherThanAbsent() {
        LiquidationUtxoResolver.PoolPair pair =
                resolver(poolIndex(POOL_ID, null)).resolvePool(POOL_ID);

        assertEquals(LiquidationUtxoResolver.PoolLookup.HALF_VISIBLE, pair.outcome(), pair.detail());
        assertTrue(pair.detail().contains("index gap"),
                "the detail must say the pool exists, not merely that something is missing: "
                        + pair.detail());
    }

    /**
     * ⚠ And the honest negative: with neither half visible the index cannot tell "no such pool" from
     * "not indexed yet", and the outcome says so rather than guessing. A resolver that claimed
     * "transient, retry" here would be inventing a distinction its data cannot support.
     */
    @Test
    void neitherHalfVisibleIsNotClaimedToBeTransient() {
        LiquidationUtxoResolver.PoolPair pair =
                resolver(poolIndex(null, null)).resolvePool(POOL_ID);

        assertEquals(LiquidationUtxoResolver.PoolLookup.NOT_VISIBLE, pair.outcome(), pair.detail());
        assertTrue(pair.detail().contains("indistinguishable"),
                "the detail must not claim to know which case this is: " + pair.detail());
    }

    @Test
    void aBondNamingNoPoolResolvesToNothingWithoutTouchingTheIndex() {
        LiquidationUtxoResolver.PoolPair pair = resolver(Map.of()).resolvePool("");

        assertEquals(LiquidationUtxoResolver.PoolLookup.NOT_VISIBLE, pair.outcome());
        assertTrue(pair.detail().contains("names no pool"), pair.detail());
    }
}
