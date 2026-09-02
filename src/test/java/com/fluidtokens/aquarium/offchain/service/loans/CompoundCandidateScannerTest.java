package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The escrow → bond → pool join, driven with the <b>real preview objects</b> measured on 2026-09-02
 * (findings §20.2 sweep): loan {@code e833a769…}'s 29,109,268-lovelace escrow into the one live pool,
 * and the orphaned {@code 2e2048fc…} escrow whose pool is burned.
 */
class CompoundCandidateScannerTest {

    private static final String LIVE_LOAN = "e833a769ea3a480343175e253eab799ec0b058c99de30cc17160dc37";
    private static final String ORPHAN_LOAN = "2e2048fcc960c6ee63c11fb4f231eac6e197a7c15a2585c3205de35e";
    private static final String LIVE_POOL = "00d3513725536642b6fe985ce9ec87d1ebb880497d92e0a8495bc6d0bf";
    private static final String DEAD_POOL = "001183812fdf3b07179ef385658e776a99b5477e7e54b8707112061ca5";
    private static final BigInteger LIVE_ESCROW = BigInteger.valueOf(29_109_268L);

    private static final LoansContractRegistry REGISTRY = LoanFixtures.shippedPreviewRegistry();
    private static final String LENDER_BOND_POLICY = REGISTRY.getLenderBondPolicyId();

    // ---- fixtures ---------------------------------------------------------------------------

    /** {@code AssetManagerDatumWithToken(OutputReference, action, data=None, ownerAsset)}. */
    private static String escrowDatum(String ownerPolicy, String ownerName) {
        var outRef = ConstrPlutusData.of(0,
                com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(new byte[32]),
                BigIntPlutusData.of(0));
        var owner = ConstrPlutusData.of(0,
                com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(
                        com.bloxbean.cardano.client.util.HexUtil.decodeHexString(ownerPolicy)),
                com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(
                        com.bloxbean.cardano.client.util.HexUtil.decodeHexString(ownerName)));
        var datum = ConstrPlutusData.of(0, outRef,
                com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of("compound".getBytes()),
                ConstrPlutusData.of(1), // data: None
                owner);
        try {
            return datum.serializeToHex();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Utxo escrow(String loanId, BigInteger lovelace, Amount... extras) {
        var amounts = new java.util.ArrayList<Amount>();
        amounts.add(Amount.lovelace(lovelace));
        amounts.addAll(List.of(extras));
        return Utxo.builder()
                .txHash("ab".repeat(32)).outputIndex(2)
                .address("addr_test1_escrow")
                .amount(amounts)
                .inlineDatum(escrowDatum(LENDER_BOND_POLICY, loanId))
                .build();
    }

    private static LenderBond bond(String loanId, String poolId, AssetType principal) {
        var datum = new LenderManagerDatum(
                new AuthorizationMethod.CardanoSignature("aa".repeat(28)),
                ListPlutusData.of(),
                false,
                BigInteger.valueOf(50L),
                poolId,
                principal);
        return new LenderBond("cd".repeat(32), 1, "addr_test1_bond", loanId, "00", datum);
    }

    /** A pool manager UTxO whose datum publishes the given fee. */
    private static Utxo poolManager(long feePerMille) {
        var datum = ConstrPlutusData.of(0,
                ConstrPlutusData.of(0, com.bloxbean.cardano.client.plutus.spec.BytesPlutusData
                        .of(new byte[28])),
                BigIntPlutusData.of(feePerMille));
        try {
            return Utxo.builder().txHash("ef".repeat(32)).outputIndex(1)
                    .address("addr_test1_pm")
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L))))
                    .inlineDatum(datum.serializeToHex())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Utxo pool() {
        return Utxo.builder().txHash("ff".repeat(32)).outputIndex(0)
                .address("addr_test1_pool")
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(500_000_000L))))
                .build();
    }

    private static CompoundCandidateScanner scanner(List<Utxo> escrows, List<LenderBond> bonds,
                                                    Map<String, LiquidationUtxoResolver.PoolPair> pools) {
        return new CompoundCandidateScanner(null, REGISTRY,
                new FakeBonds(bonds), new FakeResolver(pools)) {
            @Override
            public List<Utxo> escrows() {
                return escrows;
            }
        };
    }

    private static LiquidationUtxoResolver.PoolPair resolved(long fee) {
        return new LiquidationUtxoResolver.PoolPair(LiquidationUtxoResolver.PoolLookup.RESOLVED,
                pool(), poolManager(fee), "both NFTs present");
    }

    private static final LiquidationUtxoResolver.PoolPair NOT_VISIBLE =
            new LiquidationUtxoResolver.PoolPair(LiquidationUtxoResolver.PoolLookup.NOT_VISIBLE,
                    null, null, "pool burned");

    // ---- the join ---------------------------------------------------------------------------

    /** ⛔ The one candidate that was actually compoundable on preview on 2026-09-02. */
    @Test
    void theLiveCandidateResolvesEndToEnd() {
        var scan = scanner(List.of(escrow(LIVE_LOAN, LIVE_ESCROW)),
                List.of(bond(LIVE_LOAN, LIVE_POOL, AssetType.ada())),
                Map.of(LIVE_POOL, resolved(0L))).scan();

        assertEquals(1, scan.ready().size());
        CompoundCandidate c = scan.ready().getFirst();
        assertEquals(LIVE_LOAN, c.loanId());
        assertEquals(LIVE_POOL, c.poolId());
        assertTrue(c.principalIsAda());
        assertEquals(LIVE_ESCROW, c.addedLiquidity(),
                "addedLiquidity is the escrow's ENTIRE lovelace — the UTxO is consumed whole");
        assertEquals(0L, c.feePerMille(), "the live pool manager publishes a zero fee");
        assertNull(c.exclusion());
    }

    /** The orphan: pool burned, so the two quantity_of checks can never hold. */
    @Test
    void aBurnedPoolIsCarriedAsPoolNotLiveRatherThanDropped() {
        var scan = scanner(List.of(escrow(ORPHAN_LOAN, BigInteger.valueOf(45_000_000L))),
                List.of(bond(ORPHAN_LOAN, DEAD_POOL, AssetType.ada())),
                Map.of(DEAD_POOL, NOT_VISIBLE)).scan();

        assertEquals(1, scan.candidates().size(), "a refused candidate must still be reported");
        assertTrue(scan.ready().isEmpty());
        assertEquals(CompoundExclusion.POOL_NOT_LIVE, scan.candidates().getFirst().exclusion());
    }

    /**
     * ⛔ The validator's shape constraint: an ada-principal escrow must hold lovelace alone.
     * {@code expect} means a violation ABORTS, so this must never reach a builder.
     */
    @Test
    void anEscrowCarryingForeignAssetsIsRefusedOnShape() {
        var tFLDT = Amount.builder()
                .unit("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f620014df1074464c4454")
                .quantity(BigInteger.valueOf(200_000_000L)).build();

        var scan = scanner(List.of(escrow(LIVE_LOAN, BigInteger.valueOf(1_771_410L), tFLDT)),
                List.of(bond(LIVE_LOAN, LIVE_POOL, AssetType.ada())),
                Map.of(LIVE_POOL, resolved(50L))).scan();

        assertEquals(CompoundExclusion.ESCROW_SHAPE_REJECTED, scan.candidates().getFirst().exclusion());
        assertTrue(scan.ready().isEmpty());
    }

    @Test
    void aBorrowerOwnedEscrowIsExcludedStructurally() {
        var borrowerOwned = Utxo.builder()
                .txHash("11".repeat(32)).outputIndex(0).address("addr_test1_escrow")
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(1_775_720L))))
                .inlineDatum(escrowDatum(REGISTRY.getBorrowerBondPolicyId(), LIVE_LOAN))
                .build();

        var scan = scanner(List.of(borrowerOwned), List.of(), Map.of()).scan();

        assertEquals(CompoundExclusion.NOT_LENDER_OWNED, scan.candidates().getFirst().exclusion());
    }

    @Test
    void aBondNamingNoPoolIsPermanentlyUncompoundable() {
        var scan = scanner(List.of(escrow(LIVE_LOAN, LIVE_ESCROW)),
                List.of(bond(LIVE_LOAN, "", AssetType.ada())), Map.of()).scan();

        assertEquals(CompoundExclusion.BOND_NAMES_NO_POOL, scan.candidates().getFirst().exclusion());
    }

    @Test
    void anEscrowWithNoBondInTheIndexIsReported() {
        var scan = scanner(List.of(escrow(LIVE_LOAN, LIVE_ESCROW)), List.of(), Map.of()).scan();

        assertEquals(CompoundExclusion.BOND_NOT_FOUND, scan.candidates().getFirst().exclusion());
    }

    /** A token-principal pool: refused rather than compared against a lovelace fee. */
    @Test
    void aTokenPrincipalPoolIsRefused() {
        var principal = new AssetType("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62",
                "0014df1074464c4454");
        var tFLDT = Amount.builder().unit(principal.toUnit())
                .quantity(BigInteger.valueOf(200_000_000L)).build();

        var scan = scanner(List.of(escrow(LIVE_LOAN, BigInteger.valueOf(2_000_000L), tFLDT)),
                List.of(bond(LIVE_LOAN, LIVE_POOL, principal)),
                Map.of(LIVE_POOL, resolved(50L))).scan();

        var c = scan.candidates().getFirst();
        assertEquals(CompoundExclusion.PRINCIPAL_NOT_ADA, c.exclusion());
        assertEquals(BigInteger.valueOf(200_000_000L), c.addedLiquidity(),
                "the principal quantity is still reported, in the principal's own unit");
    }

    /** An unreadable pool-manager datum must read as a zero fee, never as a high one. */
    @Test
    void anUnreadablePoolManagerDatumFailsClosedToZero() {
        var broken = Utxo.builder().txHash("aa".repeat(32)).outputIndex(0)
                .address("addr_test1_pm").amount(List.of(Amount.lovelace(BigInteger.TWO)))
                .inlineDatum("deadbeef").build();

        assertEquals(0L, CompoundCandidateScanner.compoundingFeePerMille(broken));
    }

    @Test
    void everyEscrowIsAccountedForEvenWhenAllAreRefused() {
        var scan = scanner(
                List.of(escrow(ORPHAN_LOAN, BigInteger.valueOf(45_000_000L)),
                        escrow(LIVE_LOAN, LIVE_ESCROW)),
                List.of(bond(ORPHAN_LOAN, DEAD_POOL, AssetType.ada()),
                        bond(LIVE_LOAN, DEAD_POOL, AssetType.ada())),
                Map.of(DEAD_POOL, NOT_VISIBLE)).scan();

        assertEquals(2, scan.candidates().size(),
                "\"no candidates\" and \"two candidates with burned pools\" must not look alike");
        assertTrue(scan.ready().isEmpty());
        assertFalse(scan.candidates().stream().anyMatch(CompoundCandidate::structurallyReady));
    }

    // ---- fakes ------------------------------------------------------------------------------

    private static final class FakeBonds extends LenderBondService {
        private final List<LenderBond> bonds;

        FakeBonds(List<LenderBond> bonds) {
            super(null, null);
            this.bonds = bonds;
        }

        @Override
        public List<LenderBond> findAll() {
            return bonds;
        }
    }

    private static final class FakeResolver extends LiquidationUtxoResolver {
        private final Map<String, PoolPair> pools;

        FakeResolver(Map<String, PoolPair> pools) {
            super(null, null, null);
            this.pools = pools;
        }

        @Override
        public PoolPair resolvePool(String poolId) {
            return pools.getOrDefault(poolId,
                    new PoolPair(PoolLookup.NOT_VISIBLE, null, null, "not in the fake index"));
        }
    }
}
