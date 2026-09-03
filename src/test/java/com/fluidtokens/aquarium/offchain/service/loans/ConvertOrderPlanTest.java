package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The convert plan, driven end to end by REAL mainnet data.</b>
 *
 * <p>Nothing here is fabricated: the pool datum is the live ADA/FLDT Minswap V2 pool, the loan and
 * lender-bond datums are the real convert candidate {@code d832b78e…} (findings §32), and the lp asset
 * name it computes is the one mainnet actually serves. That matters more than usual for this path —
 * <b>convert has never run on any network</b> (§28.1), so a fabricated fixture would be checking the
 * builder against the same assumptions that built it.
 */
class ConvertOrderPlanTest {

    private static final String MINSWAP_POOL_POLICY =
            "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final AssetType FLDT =
            new AssetType("577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454");
    private static final AssetType LENDER_BOND = new AssetType(
            "bcd713bb7858d4b08738bed90ee7068d8f9b38d02e0cae0b45ac7a9b",
            "1b6fda505ea9b739e42b5871d274344af37c196ddb70619541a7d06d");
    private static final String LOAN_TX = "d832b78e3d4a9ff99dfa8f238ae378b37dbd36b30efd24d68e5786f99786cf99";

    private static String fixture(String path) throws IOException {
        try (InputStream is = ConvertOrderPlanTest.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("fixture not on the classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static MinswapPoolDatum livePool() throws IOException {
        return new MinswapPoolDatumConverter()
                .deserialize(fixture("/loans-v4/mainnet-minswap-pool-ada-fldt.hex"));
    }

    private static PlutusData receiver() {
        return ConvertTxEncoder.plainScriptAddress(
                "1551bd4efdef76f3184798331e1c74f6a1cef51955b0c96b8db18d1f");
    }

    private static ConvertOrderPlan planTheRealCandidate(MinswapPoolDatum pool) {
        return ConvertOrderPlan.plan(FLDT, AssetType.ada(),
                BigInteger.valueOf(100_000_000L),      // the loan's FLDT collateral
                BigInteger.ZERO,                        // equity
                BigInteger.valueOf(20_000_000L),        // remainingDebt -> minimum_receive
                50L,                                    // liquidationFeePerMille from the real bond
                true, false, pool, MINSWAP_POOL_POLICY, LENDER_BOND,
                new AuthorizationMethod.CardanoSignature(
                        "6fae7995cd41876a6110904d46a29596f834fc7d4a9f38e63e310330"),
                receiver(), LOAN_TX, 1);
    }

    // ---- the live pool decodes, and its shape is what the validator reads ------------------------

    /**
     * ⚠ The arity check is the load-bearing one: every field is positional, so a Minswap type change
     * that inserted a field would otherwise be read as a pool with <b>different assets</b> — a
     * well-formed order for the wrong pair.
     */
    @Test
    void theLiveAdaFldtPoolDecodesToTheAssetsMainnetHolds() throws IOException {
        MinswapPoolDatum pool = livePool();

        assertEquals(AssetType.ada(), pool.assetA(), "the pool declares ADA first");
        assertEquals(FLDT, pool.assetB());
        assertEquals(BigInteger.valueOf(1_692_342_884_761L), pool.reserveA());
        assertEquals(BigInteger.valueOf(7_596_442_927_398L), pool.reserveB());
        assertEquals(10, MinswapPoolDatum.FIELD_COUNT);
    }

    /**
     * ⛔ The dangerous arity change is a field ADDED, not one missing — a short datum throws on its own
     * and needs no guard, while a long one <b>decodes perfectly into the wrong positions</b> and
     * returns a pool for a different pair with nothing amiss.
     *
     * <p>⚠ This test was rewritten after a mutant proved the first version vacuous: it fed a
     * two-field datum and asserted {@code RuntimeException}, which an index-out-of-bounds satisfies —
     * so removing the arity check entirely left it green. <b>A test that passes for a reason other
     * than the one it names is not a test of that thing.</b>
     */
    @Test
    void aPoolDatumWithAnEXTRAFieldIsRefusedRatherThanReadPositionally() {
        var wrongPair = ConstrPlutusData.builder().alternative(0)
                .data(com.bloxbean.cardano.client.plutus.spec.ListPlutusData.of(
                        BigIntPlutusData.of(0),
                        asset("cc".repeat(28), "beef"),      // would be read as asset_a
                        asset("dd".repeat(28), "cafe"),      // would be read as asset_b
                        BigIntPlutusData.of(1), BigIntPlutusData.of(2), BigIntPlutusData.of(3),
                        BigIntPlutusData.of(4), BigIntPlutusData.of(5), BigIntPlutusData.of(6),
                        BigIntPlutusData.of(7), BigIntPlutusData.of(8)))   // ELEVEN fields
                .build();

        var e = assertThrows(RuntimeException.class,
                () -> new MinswapPoolDatumConverter().fromPlutusData(wrongPair));
        assertTrue(e.getMessage().contains("11") && e.getMessage().contains("10"),
                "the refusal must name the arity it saw and the one it expected: " + e.getMessage());
    }

    private static PlutusData asset(String policyHex, String nameHex) {
        return ConstrPlutusData.builder().alternative(0)
                .data(com.bloxbean.cardano.client.plutus.spec.ListPlutusData.of(
                        com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(
                                com.bloxbean.cardano.client.util.HexUtil.decodeHexString(policyHex)),
                        com.bloxbean.cardano.client.plutus.spec.BytesPlutusData.of(
                                com.bloxbean.cardano.client.util.HexUtil.decodeHexString(nameHex))))
                .build();
    }

    // ---- the plan, against the real candidate ----------------------------------------------------

    /**
     * ⛔ <b>Direction is the POOL's answer, not ours.</b> The real pool declares ADA as {@code asset_a}
     * and the loan's collateral is FLDT, so {@code lpABDirection} is <b>False</b> and the validator
     * takes its else-branch — which then demands {@code asset_b == collateral && asset_a == principal}.
     * Computing the direction from our own idea of ordering would invert the swap.
     */
    @Test
    void theRealCandidateSwapsBToAAgainstTheRealPool() throws IOException {
        ConvertOrderPlan plan = planTheRealCandidate(livePool());

        assertFalse(plan.aToBDirection(),
                "asset_a is ADA and the collateral is FLDT, so this sells B for A");
        assertEquals("bc53f5c2a8cf3ef64081d2ec8c74333d567fc7ef271c1b97d21fdd53a2c5c889",
                plan.lpAssetName(), "the lp asset name mainnet actually serves for this pool");
    }

    /** {@code collateral − equity − fee}, with the fee truncated exactly as the validator does. */
    @Test
    void theSwappableAmountIsTheValidatorsSubtraction() throws IOException {
        ConvertOrderPlan plan = planTheRealCandidate(livePool());

        assertEquals(BigInteger.valueOf(5_000_000L), plan.liquidationFee(),
                "100,000,000 * 50 / 1000 — the bot's income, in FLDT");
        assertEquals(BigInteger.valueOf(95_000_000L), plan.swappableCollateralAmount());
        assertEquals(BigInteger.valueOf(20_000_000L), plan.minimumReceive(),
                "remainingDebt, fixed by the validator — never ours to widen");
    }

    /**
     * ⛔ THE ORDER'S ADA, which differs by collateral kind and is the term a model would omit. A token
     * collateral's order must carry exactly 2.8 ada <b>alongside</b> the tokens; an ADA collateral's
     * order holds exactly the swappable amount and nothing extra.
     */
    @Test
    void aTokenCollateralOrderCarriesTheValidatorsTwoPointEightAdaAndAnAdaOneDoesNot() throws IOException {
        assertEquals(BigInteger.valueOf(2_800_000L), planTheRealCandidate(livePool()).orderLovelace());

        // The same loan with ADA collateral, against an ADA/FLDT-shaped pool with the roles swapped.
        var adaCollateralPool = new MinswapPoolDatum(AssetType.ada(), FLDT,
                BigInteger.TEN, BigInteger.TEN, BigInteger.TEN);
        ConvertOrderPlan adaPlan = ConvertOrderPlan.plan(AssetType.ada(), FLDT,
                BigInteger.valueOf(100_000_000L), BigInteger.ZERO, BigInteger.valueOf(20_000_000L),
                50L, true, false, adaCollateralPool, MINSWAP_POOL_POLICY, LENDER_BOND,
                new AuthorizationMethod.CardanoSignature("aa".repeat(28)), receiver(), LOAN_TX, 1);

        assertTrue(adaPlan.aToBDirection(), "now the collateral IS asset_a");
        assertEquals(adaPlan.swappableCollateralAmount(), adaPlan.orderLovelace(),
                "an ADA collateral's order lovelace IS the swappable amount — no rider at all");
    }

    /** The two datum hashes are asymmetric by construction and must never coincide. */
    @Test
    void theSuccessAndRefundHashesAreBothPresentAndDifferent() throws IOException {
        ConvertOrderPlan plan = planTheRealCandidate(livePool());

        assertEquals(64, plan.successDatumHash().length());
        assertEquals(64, plan.refundDatumHash().length());
        assertNotEquals(plan.successDatumHash(), plan.refundDatumHash());
        assertEquals(plan.successDatumHash(), ConvertTxEncoder.datumHash(plan.successDatum()),
                "the carrier output must be able to reproduce the hash from the datum it holds");
        assertEquals(plan.refundDatumHash(), ConvertTxEncoder.datumHash(plan.refundDatum()));
    }

    // ---- the refusals ------------------------------------------------------------------------------

    @Test
    void aPoolForADifferentPairIsRefusedRatherThanPricedBadly() {
        var wrongPool = new MinswapPoolDatum(AssetType.ada(),
                new AssetType("cc".repeat(28), "beef"), BigInteger.TEN, BigInteger.TEN, BigInteger.TEN);

        var e = assertThrows(ConvertOrderPlan.RefusedException.class,
                () -> planTheRealCandidate(wrongPool));
        assertEquals(ConvertOrderPlan.Refusal.POOL_IS_FOR_A_DIFFERENT_PAIR, e.refusal());
    }

    @Test
    void aBondThatForbidsConversionAndEquityInPrincipalCurrencyAreBothRefused() throws IOException {
        MinswapPoolDatum pool = livePool();
        var auth = new AuthorizationMethod.CardanoSignature("aa".repeat(28));

        var forbids = assertThrows(ConvertOrderPlan.RefusedException.class,
                () -> ConvertOrderPlan.plan(FLDT, AssetType.ada(), BigInteger.valueOf(100L),
                        BigInteger.ZERO, BigInteger.ONE, 50L, false, false, pool,
                        MINSWAP_POOL_POLICY, LENDER_BOND, auth, receiver(), LOAN_TX, 1));
        assertEquals(ConvertOrderPlan.Refusal.BOND_FORBIDS_CONVERSION, forbids.refusal());

        var equity = assertThrows(ConvertOrderPlan.RefusedException.class,
                () -> ConvertOrderPlan.plan(FLDT, AssetType.ada(), BigInteger.valueOf(100L),
                        BigInteger.ZERO, BigInteger.ONE, 50L, true, true, pool,
                        MINSWAP_POOL_POLICY, LENDER_BOND, auth, receiver(), LOAN_TX, 1));
        assertEquals(ConvertOrderPlan.Refusal.EQUITY_IN_PRINCIPAL_CURRENCY, equity.refusal());
    }

    /** Equity plus fee can consume the whole collateral; a zero-amount order is not buildable. */
    @Test
    void nothingLeftToSwapIsRefusedRatherThanOrderedForZero() throws IOException {
        MinswapPoolDatum pool = livePool();
        var e = assertThrows(ConvertOrderPlan.RefusedException.class,
                () -> ConvertOrderPlan.plan(FLDT, AssetType.ada(), BigInteger.valueOf(1_000L),
                        BigInteger.valueOf(950L), BigInteger.ONE, 50L, true, false, pool,
                        MINSWAP_POOL_POLICY, LENDER_BOND,
                        new AuthorizationMethod.CardanoSignature("aa".repeat(28)),
                        receiver(), LOAN_TX, 1));
        assertEquals(ConvertOrderPlan.Refusal.NOTHING_LEFT_TO_SWAP, e.refusal());
    }

    /**
     * ⚑ And the sanity check that ties the plan back to the loan we actually decoded: the fixture's
     * own datums, not constants copied into this file.
     */
    @Test
    void theFixturesThisPlanIsDrivenByAreTheRealCandidatesOwnDatums() throws IOException {
        LenderManagerDatum bond = new LenderManagerDatumConverter()
                .deserialize(fixture("/loans-v4/mainnet-lender-bond-datum-d832b78e.hex"));
        LoanDatum loan = new LoanDatumConverter()
                .deserialize(fixture("/loans-v4/mainnet-loan-datum-d832b78e.hex"));

        assertTrue(bond.shouldLiquidationConvertToPrincipal());
        assertEquals(BigInteger.valueOf(50L), bond.liquidationFeePerMille());
        assertEquals(AssetType.ada(), loan.principalAsset());
        assertEquals(FLDT, loan.collateral().assetType());
    }
}
