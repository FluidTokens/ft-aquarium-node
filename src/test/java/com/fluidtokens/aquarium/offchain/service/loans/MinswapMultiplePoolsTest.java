package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>MORE THAN ONE UTxO CARRIES A PAIR'S LP ASSET, AND REFUSING THEM ALL WAS THE DEFECT.</b>
 *
 * <h2>Measured on mainnet, 2026-09-18</h2>
 * Every ada/ASCEND loan on the readiness page read {@code CHECK FAILED}, forever. The resolver threw
 * {@code AMBIGUOUS_POOL} — <i>"Minswap mints one per pool, so choosing between them would build
 * against a pool nobody chose"</i> — on finding two UTxOs at the pool address holding the LP asset
 * {@code f5808c2c…e6619578…}.
 *
 * <p>⚠ <b>The premise was false, and the reason is sharper than "two pools exist".</b> Reading the
 * two datums off chain:
 * <pre>
 * 02db9d9b…#1     4.53 ada   assetA = ada, assetB = f5808c2c…e6619578…  ← the LP ASSET ITSELF
 * 4c805499…#1   887,245 ada  assetA = ada, assetB = eb7a93eb…ASCEND     ← the real pool
 * </pre>
 * The small UTxO is <b>not a competing ada/ASCEND pool</b>. It is an ada / LP-token pool, which
 * therefore <em>holds</em> that LP asset and so answers the same provider query. Sorting purely by
 * depth would have picked the right one here <b>by luck</b>; what makes it right by construction is
 * that a pool's datum states its pair, and a pool for a different pair can never fill this swap.
 *
 * <p>⚑ <b>The original concern was correct even though its rule was not.</b> Choosing arbitrarily
 * really would build against a pool nobody chose — so among genuine candidates for the pair the
 * choice is made on the only property that governs a fill: depth.
 *
 * <p>⛔ This branch had <b>no test at all</b> before today, which is why it shipped. The only other
 * mention of {@code AMBIGUOUS_POOL} constructs the refusal by hand to test the executor's handling
 * of it, and never exercises the resolver's decision.
 */
class MinswapMultiplePoolsTest {

    private static final String POOL_ADDRESS = "addr1_pool";
    private static final String POOL_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";

    private static final AssetType ADA = AssetType.ada();
    private static final AssetType ASCEND = AssetType.fromUnit(
            "eb7a93ebc321647673490810f618b548d7c24aa64d30ae342dba70760014df10415343454e44");

    private static final String LP_UNIT = POOL_POLICY + ConvertTxEncoder.computeLpAssetName(ADA, ASCEND);

    /** ⚑ Read off {@code 4c805499…#1} on 2026-09-18: the real ada/ASCEND pool, 887,245 ada. */
    private static final String DEEP_POOL_DATUM = "d8799fd8799fd87a9f581c1eae96baf29e27682ea3f815aba361a0c6059d45e4bfbe95bbd2f44affffd8799f4040ffd8799f581ceb7a93ebc321647673490810f618b548d7c24aa64d30ae342dba70764a0014df10415343454e44ff1b000000ba6be20bdc1b000000ce9358c8a91b000000ed5e5802cf18641864d8799f190682ffd87980ff";

    /**
     * ⚑ Read off {@code 02db9d9b…#1} on 2026-09-18. <b>Its assetB is the LP asset itself</b>, so it
     * is an ada / LP-token pool — it answers the same query and is not this pair.
     */
    private static final String OTHER_PAIR_DATUM = "d8799fd8799fd87a9f581c1eae96baf29e27682ea3f815aba361a0c6059d45e4bfbe95bbd2f44affffd8799f4040ffd8799f581cf5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c5820e66195788208dcd363edb600eaf2331019e3599baba645d81d61ef060c82d861ff0b0e09181e181ed8799f190682ffd87980ff";

    /**
     * The deep datum with ONLY its two reserve fields reduced — same pair, same shape, same fees.
     * Derived by mutation rather than invented, so the converter's arity guard still accepts it and
     * the only thing that differs from a real pool is the depth this test is about.
     */
    private static final String SHALLOW_SAME_PAIR_DATUM = DEEP_POOL_DATUM
            .replace("1b000000ce9358c8a91b000000ed5e5802cf", "1b00000000000003e81b00000000000007d0");

    /**
     * ⛔ The other-pair datum with its reserves RAISED far above the shallow same-pair pool.
     *
     * <p>Without this the pair check cannot be tested at all: on mainnet the wrong-pair UTxO is also
     * the shallower one, so depth alone picks the right pool and a test using the real pair would
     * pass with the pair check DELETED. Measured — that exact mutant survived. Making the wrong pair
     * the DEEPER candidate is what forces the pair check to be the thing that decides.
     */
    private static final String DEEP_OTHER_PAIR_DATUM = OTHER_PAIR_DATUM
            .replace("0b0e09181e181e", "0b1b000000ff000000001b000000ff00000000181e181e");

    private record Fixture(String txHash, String datum) { }

    private static UtxoService serving(List<Fixture> fixtures) {
        return new UtxoService() {
            @Override
            public Result<List<Utxo>> getUtxos(String address, String unit, int count, int page) {
                if (!LP_UNIT.equals(unit)) {
                    Result<List<Utxo>> miss = Result.error("component not found");
                    miss.code(404);
                    return miss;
                }
                Result<List<Utxo>> hit = Result.success("ok");
                hit.withValue(fixtures.stream().map(f -> Utxo.builder()
                        .txHash(f.txHash()).outputIndex(1).inlineDatum(f.datum()).build()).toList());
                hit.code(200);
                return hit;
            }

            @Override
            public Result<List<Utxo>> getUtxos(String a, int c, int p) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Result<List<Utxo>> getUtxos(String a, int c, int p,
                                               com.bloxbean.cardano.client.api.common.OrderEnum o) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Result<List<Utxo>> getUtxos(String a, String u, int c, int p,
                                               com.bloxbean.cardano.client.api.common.OrderEnum o) {
                return getUtxos(a, u, c, p);
            }

            @Override
            public Result<Utxo> getTxOutput(String t, int i) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static MinswapPoolResolver resolver(List<Fixture> fixtures) {
        return new MinswapPoolResolver(serving(fixtures), POOL_ADDRESS, POOL_POLICY);
    }

    /** The exact mainnet shape: the real pool plus an ada/LP-token pool holding the same asset. */
    @Test
    void theMainnetPairResolvesDespiteASecondUtxoCarryingTheSameLpAsset() {
        var resolved = resolver(List.of(new Fixture("02db9d9b".repeat(8), OTHER_PAIR_DATUM),
                new Fixture("4c805499".repeat(8), DEEP_POOL_DATUM)))
                .resolveEitherOrder(ADA, ASCEND);

        assertTrue(resolved.isPresent(),
                "two UTxOs carrying the LP asset is the NORMAL mainnet shape, not an ambiguity");
        assertEquals("4c805499".repeat(8), resolved.get().utxo().getTxHash(),
                "the ada/ASCEND pool must win over the ada/LP-token pool");
        assertEquals(ASCEND.toUnit(), resolved.get().datum().assetB().toUnit(),
                "and the chosen datum must actually be this pair");
    }

    /**
     * ⛔ The pair check, isolated from depth. The other-pair UTxO is listed FIRST and the real pool
     * is the only alternative — so if the pair were not checked, order alone would decide.
     */
    @Test
    void aUtxoForADifferentPairIsNeverAPoolForThisOneEvenWhenItIsDeeper() {
        var resolved = resolver(List.of(new Fixture("02db9d9b".repeat(8), DEEP_OTHER_PAIR_DATUM),
                new Fixture("4c805499".repeat(8), SHALLOW_SAME_PAIR_DATUM)))
                .resolveEitherOrder(ADA, ASCEND);

        assertTrue(resolved.isPresent(), "a shallow pool is still a pool");
        assertEquals("4c805499".repeat(8), resolved.get().utxo().getTxHash(),
                "the wrong-pair UTxO must be excluded BY PAIR, not by depth — here it is listed "
                        + "first AND is far deeper, so depth alone would choose it");
        assertEquals(ASCEND.toUnit(), resolved.get().datum().assetB().toUnit(),
                "the chosen pool must be this pair");
    }

    /** And among genuine candidates for the pair, depth decides — listed shallow-first. */
    @Test
    void theDeepestPoolWinsAmongGenuineCandidates() {
        var resolved = resolver(List.of(new Fixture("aa".repeat(32), SHALLOW_SAME_PAIR_DATUM),
                new Fixture("bb".repeat(32), DEEP_POOL_DATUM)))
                .resolveEitherOrder(ADA, ASCEND);

        assertTrue(resolved.isPresent());
        assertEquals("bb".repeat(32), resolved.get().utxo().getTxHash(),
                "the deeper pool must be chosen — it is what governs whether a swap can fill");
    }

    /** Reversed input order: the decision must be depth, never the provider's ordering. */
    @Test
    void theChoiceDoesNotDependOnTheOrderTheProviderReturnsThemIn() {
        var resolved = resolver(List.of(new Fixture("bb".repeat(32), DEEP_POOL_DATUM),
                new Fixture("aa".repeat(32), SHALLOW_SAME_PAIR_DATUM)))
                .resolveEitherOrder(ADA, ASCEND);

        assertEquals("bb".repeat(32), resolved.orElseThrow().utxo().getTxHash(),
                "same winner whichever order they arrive in");
    }
}
