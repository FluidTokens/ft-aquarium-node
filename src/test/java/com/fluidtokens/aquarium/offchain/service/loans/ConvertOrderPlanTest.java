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

    /** USDM on mainnet — the other live pool's asset_b, for the thin-pool case below. */
    private static final AssetType USDM =
            new AssetType("c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad", "0014df105553444d");

    /** The ADA/FLDT pool's declared fee, measured live 2026-09-10: 80/10000 on BOTH directions. */
    private static final BigInteger FEE_80 = BigInteger.valueOf(80L);
    /** The FLDT/USDM pool's, measured the same day — 70/10000. Two pools, two fees. */
    private static final BigInteger FEE_70 = BigInteger.valueOf(70L);

    /** The committed fixture pool's reserves, so a fixture that must SUCCEED is deep enough to. */
    private static final BigInteger ADA_FLDT_RESERVE_ADA = new BigInteger("1692342884761");
    private static final BigInteger ADA_FLDT_RESERVE_FLDT = new BigInteger("7596442927398");

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
    void everyOrderCarriesTheMinswapOverhead_andTheAdaCaseAddsItToTheSwapAmount() throws IOException {
        assertEquals(BigInteger.valueOf(4_000_000L), planTheRealCandidate(livePool()).orderLovelace());

        // The same loan with ADA collateral, against an ADA/FLDT-shaped pool with the roles swapped.
        // ⚠ Reserves are the LIVE pool's, not BigInteger.TEN as they were before the POOL_TOO_THIN
        // pre-check existed: a ten-unit pool cannot deliver a twenty-million debt, so the old fixture
        // now refuses for a reason that has nothing to do with what this test is about.
        var adaCollateralPool = new MinswapPoolDatum(AssetType.ada(), FLDT,
                BigInteger.TEN, ADA_FLDT_RESERVE_ADA, ADA_FLDT_RESERVE_FLDT,
                FEE_80, FEE_80, false);
        ConvertOrderPlan adaPlan = ConvertOrderPlan.plan(AssetType.ada(), FLDT,
                BigInteger.valueOf(100_000_000L), BigInteger.ZERO, BigInteger.valueOf(20_000_000L),
                50L, true, false, adaCollateralPool, MINSWAP_POOL_POLICY, LENDER_BOND,
                new AuthorizationMethod.CardanoSignature("aa".repeat(28)), receiver(), LOAN_TX, 1);

        assertTrue(adaPlan.aToBDirection(), "now the collateral IS asset_a");
        // ⛔ INVERTED at db5069e, and the old assertion was the defect. An ada collateral's order
        // used to hold exactly the swappable amount, which left no room for a batcher fee and made
        // the order unbatchable (findings §57.9). It now carries the swap amount PLUS the overhead.
        assertEquals(adaPlan.swappableCollateralAmount().add(BigInteger.valueOf(4_000_000L)),
                adaPlan.orderLovelace(),
                "an ada collateral's order is swap amount + minswap_order_overhead; equality with "
                        + "the bare swap amount is the shape that could never be batched");
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
        // Reserves stay nominal deliberately: the pair check refuses BEFORE the quote runs, and this
        // test would still pass if it did not — which is why the ordering is pinned separately below.
        var wrongPool = new MinswapPoolDatum(AssetType.ada(),
                new AssetType("cc".repeat(28), "beef"), BigInteger.TEN, BigInteger.TEN,
                BigInteger.TEN, FEE_80, FEE_80, false);

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

    // ---- the pre-trade quote: POOL_TOO_THIN, and the fee it is computed with ----------------------

    /**
     * ⛔ <b>THE CALIBRATION, against a batch that really settled.</b> Minswap batch
     * {@code 5f0572b2…} (2026-09-08) took our order of 86,279,718 FLDT against reserves
     * 1,667,895,724,071 ADA / 7,689,154,296,039 FLDT and paid <b>18,565,738 lovelace</b>.
     *
     * <p>The quote must be an <b>upper bound that does not exceed what the pool really paid</b>:
     * quoting LESS than the batch is harmless (the pre-check refuses an order that would marginally
     * have filled), quoting MORE is the failure this whole pre-check exists to prevent — it lets a
     * futile order through to be built, submitted and refunded at the operator's expense.
     *
     * <p>⚠ <b>And this is the test that forbids a constant.</b> The 24/10000 and 30/10000 figures
     * quoted for AMMs generally BOTH over-quote this batch by about half a percent — on the wrong side
     * — while the pool's own declared 80 lands 272 lovelace under it. Two live mainnet pools declare
     * two different fees (ADA/FLDT 80, FLDT/USDM 70), so no single constant is even available.
     */
    @Test
    void theQuoteIsCalibratedAgainstTheRealMainnetBatchAndNeverOverQuotesIt() {
        BigInteger in = new BigInteger("86279718");
        BigInteger reserveFldt = new BigInteger("7689154296039");
        BigInteger reserveAda = new BigInteger("1667895724071");
        BigInteger realBatchPaid = new BigInteger("18565738");

        BigInteger quoted = ConvertOrderPlan.constantProductOut(in, reserveFldt, reserveAda, FEE_80);

        assertEquals(new BigInteger("18565466"), quoted,
                "the pool's own fee of 80/10000 reproduces the settled batch to within 272 lovelace");
        assertTrue(quoted.compareTo(realBatchPaid) <= 0,
                "the quote must never promise more than the pool really paid: quoted " + quoted
                        + " against a settled " + realBatchPaid);

        // The two constants a future reader might reach for, pinned as the mistake they would be.
        for (BigInteger wrong : new BigInteger[]{BigInteger.valueOf(24L), BigInteger.valueOf(30L)}) {
            assertTrue(ConvertOrderPlan.constantProductOut(in, reserveFldt, reserveAda, wrong)
                            .compareTo(realBatchPaid) > 0,
                    "a hardcoded " + wrong + "/10000 OVER-quotes the real batch, which is the "
                            + "direction that lets a futile order through — this is why the fee is "
                            + "read from the pool datum");
        }
    }

    /**
     * ⛔ <b>The live FLDT/USDM candidate of 2026-09-09, which would have been built and refunded.</b>
     * Pool 83,148,023,044 FLDT / 3,961,443,135 USDM, swapping 22,003,200,000 FLDT against a debt of
     * 980,001,429 USDM: the pool returns about 824 M, a 155 M shortfall. {@code minimum_receive} is the
     * debt and the validator fixes it, so this order does not fill badly — it is refunded, and the
     * operator pays for the round trip.
     */
    @Test
    void aPoolThatCannotDeliverTheDebtIsRefusedByNameBeforeAnythingIsBuilt() {
        var thinPool = new MinswapPoolDatum(FLDT, USDM, BigInteger.TEN,
                new BigInteger("83148023044"), new BigInteger("3961443135"),
                FEE_70, FEE_70, false);

        var e = assertThrows(ConvertOrderPlan.RefusedException.class,
                () -> ConvertOrderPlan.plan(FLDT, USDM,
                        new BigInteger("22003200000"), BigInteger.ZERO, new BigInteger("980001429"),
                        0L, true, false, thinPool, MINSWAP_POOL_POLICY, LENDER_BOND,
                        new AuthorizationMethod.CardanoSignature("aa".repeat(28)),
                        receiver(), LOAN_TX, 1));

        assertEquals(ConvertOrderPlan.Refusal.POOL_TOO_THIN, e.refusal());
        // The operator reads these numbers on the endpoint; the refusal is only as good as its detail.
        assertTrue(e.getMessage().contains("824348402"), "the quoted output: " + e.getMessage());
        assertTrue(e.getMessage().contains("980001429"), "the required amount: " + e.getMessage());
        assertTrue(e.getMessage().contains("155653027"), "the shortfall: " + e.getMessage());
        assertTrue(e.getMessage().contains("83148023044") && e.getMessage().contains("3961443135"),
                "the pool reserves it was quoted against: " + e.getMessage());
        assertTrue(e.getMessage().contains("70/10000"), "the fee actually used: " + e.getMessage());
    }

    /**
     * The off-by-one in the fee arithmetic is the likeliest defect here, so the boundary is pinned on
     * both sides: a pool one unit too shallow refuses, and one unit deeper proceeds. Computed against
     * the real candidate's own shape (95,000,000 FLDT swappable against a 20,000,000 lovelace debt).
     */
    @Test
    void aPoolOneUnitTooThinRefusesAndOneUnitDeeperProceeds() {
        BigInteger reserveFldt = ADA_FLDT_RESERVE_FLDT;
        BigInteger exactlyEnough = new BigInteger("1612168329245");

        assertEquals(BigInteger.valueOf(20_000_000L), ConvertOrderPlan.constantProductOut(
                        BigInteger.valueOf(95_000_000L), reserveFldt, exactlyEnough, FEE_80),
                "the boundary reserve returns the debt exactly");

        assertEquals(BigInteger.valueOf(19_999_999L), ConvertOrderPlan.constantProductOut(
                        BigInteger.valueOf(95_000_000L), reserveFldt,
                        exactlyEnough.subtract(BigInteger.ONE), FEE_80),
                "one unit shallower is one unit short");

        // …and through plan(), because the arithmetic being right says nothing about it being wired.
        // planTheRealCandidate sells FLDT (asset_b) for ada (asset_a), which is the bToA shape the two
        // assertions above are computed for: reserve_b is the FLDT side we sell INTO the pool.
        assertEquals(ConvertOrderPlan.Refusal.POOL_TOO_THIN,
                assertThrows(ConvertOrderPlan.RefusedException.class,
                        () -> planTheRealCandidate(new MinswapPoolDatum(AssetType.ada(), FLDT,
                                BigInteger.TEN, exactlyEnough.subtract(BigInteger.ONE), reserveFldt,
                                FEE_80, FEE_80, false))).refusal());

        assertEquals(BigInteger.valueOf(20_000_000L),
                planTheRealCandidate(new MinswapPoolDatum(AssetType.ada(), FLDT, BigInteger.TEN,
                        exactlyEnough, reserveFldt, FEE_80, FEE_80, false)).minimumReceive(),
                "one unit deeper and the plan is built");
    }

    /**
     * ⛔ <b>EACH DIRECTION PAYS ITS OWN NUMERATOR — tested on UNEQUAL fees, because the live pools
     * cannot test it.</b> ADA/FLDT declares 80/80 and FLDT/USDM 70/70, so on every pool this node
     * actually trades against, reading the wrong numerator produces the identical answer and a
     * direction bug is invisible. This fixture makes them 0 and 9000 so the two are impossible to
     * confuse, and asserts the refusal FLIPS with the direction: selling asset_a is free and fills,
     * selling asset_b costs 90 % and cannot.
     *
     * <p>Swapping the two numerators in {@code feeNumeratorFor} inverts both assertions.
     */
    @Test
    void eachSwapDirectionIsQuotedWithItsOwnFeeNumerator() {
        BigInteger reserve = new BigInteger("1000000000000");
        BigInteger free = BigInteger.ZERO;
        BigInteger punitive = BigInteger.valueOf(9_000L);
        var lopsided = new MinswapPoolDatum(AssetType.ada(), FLDT, BigInteger.TEN,
                reserve, reserve, free, punitive, false);

        assertEquals(free, ConvertOrderPlan.feeNumeratorFor(lopsided, true),
                "aToB sells asset_a and pays base_fee_a");
        assertEquals(punitive, ConvertOrderPlan.feeNumeratorFor(lopsided, false),
                "bToA sells asset_b and pays base_fee_b");

        // aToB — collateral IS asset_a, so the free side applies and the debt is covered.
        ConvertOrderPlan sellingAssetA = ConvertOrderPlan.plan(AssetType.ada(), FLDT,
                BigInteger.valueOf(100_000_000L), BigInteger.ZERO, BigInteger.valueOf(20_000_000L),
                50L, true, false, lopsided, MINSWAP_POOL_POLICY, LENDER_BOND,
                new AuthorizationMethod.CardanoSignature("aa".repeat(28)), receiver(), LOAN_TX, 1);
        assertTrue(sellingAssetA.aToBDirection());

        // bToA — collateral IS asset_b, the 90 % side, and 95,000,000 in returns only ~9,499,909.
        var e = assertThrows(ConvertOrderPlan.RefusedException.class,
                () -> ConvertOrderPlan.plan(FLDT, AssetType.ada(),
                        BigInteger.valueOf(100_000_000L), BigInteger.ZERO,
                        BigInteger.valueOf(20_000_000L), 50L, true, false, lopsided,
                        MINSWAP_POOL_POLICY, LENDER_BOND,
                        new AuthorizationMethod.CardanoSignature("aa".repeat(28)),
                        receiver(), LOAN_TX, 1));
        assertEquals(ConvertOrderPlan.Refusal.POOL_TOO_THIN, e.refusal());
        assertTrue(e.getMessage().contains("9499909") && e.getMessage().contains("9000/10000"),
                "the refusal names the output and the numerator it used: " + e.getMessage());
        assertTrue(e.getMessage().contains("base_fee_b"),
                "and which side of the pool that numerator came from: " + e.getMessage());
    }

    /**
     * {@code allow_dynamic_fee} means the two base numerators are not the whole fee, so a quote built
     * from them would UNDERSTATE the cost — the direction that lets a futile order through. Refused by
     * name rather than quoted at a price the pool does not charge. Both live pools have it false.
     */
    @Test
    void aPoolWithDynamicFeesIsRefusedRatherThanQuotedFromItsBaseNumerators() {
        var dynamicPool = new MinswapPoolDatum(AssetType.ada(), FLDT, BigInteger.TEN,
                ADA_FLDT_RESERVE_ADA, ADA_FLDT_RESERVE_FLDT, FEE_80, FEE_80, true);

        var e = assertThrows(ConvertOrderPlan.RefusedException.class,
                () -> planAgainst(dynamicPool));
        assertEquals(ConvertOrderPlan.Refusal.POOL_HAS_DYNAMIC_FEE, e.refusal());

        // The identical pool with the flag clear is deep enough to plan — so the refusal is caused by
        // the flag and by nothing else about this fixture.
        assertEquals(BigInteger.valueOf(20_000_000L),
                planAgainst(new MinswapPoolDatum(AssetType.ada(), FLDT, BigInteger.TEN,
                        ADA_FLDT_RESERVE_ADA, ADA_FLDT_RESERVE_FLDT, FEE_80, FEE_80, false))
                        .minimumReceive());
    }

    /** The real candidate's own numbers against an arbitrary pool — ada collateral, FLDT principal. */
    private static ConvertOrderPlan planAgainst(MinswapPoolDatum pool) {
        return ConvertOrderPlan.plan(AssetType.ada(), FLDT,
                BigInteger.valueOf(100_000_000L), BigInteger.ZERO, BigInteger.valueOf(20_000_000L),
                50L, true, false, pool, MINSWAP_POOL_POLICY, LENDER_BOND,
                new AuthorizationMethod.CardanoSignature("aa".repeat(28)), receiver(), LOAN_TX, 1);
    }

    /**
     * ⚑ The committed fixture is the LIVE pool, so its fee is evidence rather than a fixture choice —
     * and the quote's whole calibration rests on this being 80, not on a number typed into a test.
     */
    @Test
    void theCommittedPoolFixtureCarriesTheFeeTheChainDeclares() throws IOException {
        MinswapPoolDatum pool = livePool();
        assertEquals(FEE_80, pool.baseFeeANumerator());
        assertEquals(FEE_80, pool.baseFeeBNumerator());
        assertFalse(pool.allowDynamicFee(), "the live ADA/FLDT pool does not use dynamic fees");
    }
}
