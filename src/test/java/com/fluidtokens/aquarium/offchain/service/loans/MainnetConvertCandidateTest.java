package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.ConvertAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.ConvertExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The real mainnet convert candidate, and what this bot's gate says about it.</b>
 *
 * <h2>What `d832b78e…` is, and what it is not</h2>
 * It was relayed as <i>"a test convert tx ready to go"</i>. Decoded from mainnet it is the
 * <b>loan-origination transaction that CREATES the candidate</b> — three mints (loan NFT, lender bond,
 * borrower bond), no Minswap order output, no pool reference input. A convert mints nothing and exists
 * to create an order, so <b>it is not a convert and cannot serve as a diff target for one.</b> See
 * {@code mainnet-convert-candidate.PROVENANCE.md}.
 *
 * <p>What it IS is far from useless: a <b>real, convert-eligible mainnet loan</b>, with a live Minswap
 * pool for its pair — the first candidate this path has ever had that is not fabricated.
 *
 * <h2>⚠ And the answer the operator needs BEFORE deploying anything</h2>
 * At the shipped defaults this candidate is <b>REFUSED</b>: its 5% fee on 100,000,000 FLDT is worth
 * roughly 1.1 ada, against a 5 ada DEX-cost floor. Converting it requires an explicitly negative
 * margin — which is exactly the protocol-health operating mode Giovanni ruled in on the same day
 * (findings §31). <b>That is a number to know before the box is armed, not after a cycle logs nothing.</b>
 */
class MainnetConvertCandidateTest {

    private static final String LOAN = "/loans-v4/mainnet-loan-datum-d832b78e.hex";
    private static final String BOND = "/loans-v4/mainnet-lender-bond-datum-d832b78e.hex";

    /** FLDT, CIP-68 (222). The loan's collateral. */
    private static final AssetType FLDT =
            new AssetType("577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454");

    /**
     * The live pool's mid-price at capture: 1,692,342,884,761 lovelace against 7,596,442,927,398 FLDT.
     * ⚠ <b>A cross-check, not the gate's input.</b> Production prices the fee off the Charli3
     * {@code oracleFLDTC3} feed the loan names; this is here so the magnitude is checkable offline and
     * is close enough to make the verdict below robust to the difference.
     */
    private static OraclePriceFeed poolImpliedPrice() {
        return OraclePriceFeed.aggregated(FLDT, BigInteger.valueOf(1_692_342_884_761L),
                BigInteger.valueOf(7_596_442_927_398L), 0L, Long.MAX_VALUE);
    }

    private static String fixture(String path) throws IOException {
        try (InputStream is = MainnetConvertCandidateTest.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("fixture not on the classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    /** ⛔ The three fields that decide whether a convert is even legal for this loan. */
    @Test
    void theLenderBondPermitsConversionAndNamesAFivePercentFee() throws IOException {
        LenderManagerDatum bond = new LenderManagerDatumConverter().deserialize(fixture(BOND));

        assertTrue(bond.shouldLiquidationConvertToPrincipal(),
                "the convert action's FIRST conjunct — a bond that forbids conversion makes the whole "
                        + "path illegal for this loan no matter what the operator configures");
        assertEquals(BigInteger.valueOf(50L), bond.liquidationFeePerMille(),
                "5% of the collateral, and the only thing that pays the bot");
        assertEquals(AssetType.ada(), bond.principalAsset());
        assertEquals("0046337bd27d65a63574039b6293da11701ed2da01bcfaf626c18cccbe", bond.poolId());
    }

    /** The loan itself: 20 ada of principal against 100,000,000 FLDT of collateral. */
    @Test
    void theLoanIsAdaPrincipalAgainstFldtCollateral() throws IOException {
        LoanDatum loan = new LoanDatumConverter().deserialize(fixture(LOAN));

        assertEquals(AssetType.ada(), loan.principalAsset());
        assertEquals(FLDT, loan.collateral().assetType(),
                "a TOKEN collateral, which is what puts the validator's 2,800,000 lovelace into the "
                        + "Minswap order output and makes this convert cost real ada");
    }

    /**
     * ⛔ <b>THE VERDICT ON THE REAL CANDIDATE.</b> Refused at the shipped defaults — and the numbers are
     * asserted rather than described, so a change to the gate that quietly flips this shows up here.
     */
    @Test
    void atTheShippedDefaultsThisCandidateIsRefusedAndTheOperatorMustStateALossToTakeIt() {
        // ⚠ ARMED EXPLICITLY, though the shipped default is now true again (2026-09-10). This test is
        // about the MARGIN gate — a candidate refused because the mechanism is off would assert
        // nothing about the economics it exists to pin, while still reading green, so the flag is
        // stated rather than inherited.
        // ⚑ The margin no longer comes from this block: it is the SHARED
        // loans.liquidation.profit-margin-lovelace, stated at 0 below so this test measures the same
        // gate it always did — the shipped 5 ada would refuse this candidate on the margin before the
        // outlay arithmetic could say anything.
        var shipped = new AppConfig.ConvertConfiguration(true, BigInteger.valueOf(5_000_000L));
        var network = new AppConfig.Network();
        ReflectionTestUtils.setField(network, "network", "mainnet");

        BigInteger collateral = BigInteger.valueOf(100_000_000L);
        BigInteger txFee = BigInteger.valueOf(500_000L);      // generous; the floor governs regardless

        ConvertAssessment a = new ConvertEconomics(shipped, liquidation(BigInteger.ZERO), network)
                .assess(true, collateral, 50L, false, poolImpliedPrice(), txFee);

        assertEquals(BigInteger.valueOf(5_000_000L), a.liquidationFee(),
                "100,000,000 * 50 / 1000, in FLDT units — the bot's whole income, and it is not ada");
        assertEquals(BigInteger.valueOf(1_113_904L), a.feeValueLovelace(),
                "≈1.11 ada at the pool's mid-price");
        assertEquals(BigInteger.valueOf(4_500_000L), a.measuredOutlay(),
                "0.5 ada tx fee + the validator's 4 ada Minswap order overhead (db5069e)");
        assertEquals(BigInteger.valueOf(5_000_000L), a.outlay(),
                "the DEX-cost floor governs, because the measurement alone is below Giovanni's 5 ada");
        assertTrue(a.boundByDexCostFloor());

        assertFalse(a.approved(), "1.11 ada of fee against a 5 ada floor is a loss");
        assertEquals(ConvertExclusion.NET_BELOW_FLOOR, a.exclusion());
        assertEquals(BigInteger.valueOf(-3_886_096L), a.net());

        // And the number an operator would have to state to take it anyway — legal on mainnet since
        // findings §31, and announced loudly at boot when they do. ⚠ It is now the SHARED margin, so
        // stating it here states it for every other liquidation mode as well: that is the trade the
        // one-knob merge makes, and it belongs in the test that shows the number being stated.
        var atALoss = liquidation(BigInteger.valueOf(-3_886_096L));
        assertTrue(new ConvertEconomics(shipped, atALoss, network)
                        .assess(true, collateral, 50L, false, poolImpliedPrice(), txFee).approved(),
                "a stated floor at exactly the net must ACCEPT it: the margin is inclusive, and an "
                        + "operator cleaning up a loan nobody will profitably touch is the point");
    }

    /**
     * ⛔ <b>THE LIVE CANDIDATE, PINNED — the numbers Giovanni is choosing a margin between.</b>
     *
     * <p>⚠ <b>Provenance:</b> these five figures were relayed with the ticket as the measurement of a
     * live convert on this path — a fee slice worth 1,626,257 lovelace against a 989,747 lovelace
     * transaction fee. They are <b>not</b> derived from the {@code d832b78e} fixtures above and do not
     * claim to be; what is asserted here is what THIS GATE says about them, so the verdict cannot
     * drift silently while the arithmetic changes underneath it.
     *
     * <pre>
     *   feeValue   1_626_257            income, at the collateral oracle price
     *   txFee        989_747            measured
     *   orderCost  4_000_000            loans.liquidation.convert.minswap-order-cost-lovelace
     *   ---------------------
     *   measured   4_989_747            txFee + orderCost
     *   dexFloor   5_000_000            binds, by 10,253 lovelace
     *   outlay     5_000_000
     *   net       -3_373_743
     * </pre>
     *
     * <p>⇒ <b>The choice this pins:</b> a shared margin of {@code -5_000_000} BUILDS this convert;
     * {@code -2_500_000} REFUSES it. Both are stated-loss settings, legal on mainnet since findings
     * §31, and the net sits between them — which is exactly why the number matters and why it is
     * asserted rather than described.
     */
    @Test
    void theLiveCandidateIsBuiltAtAFiveAdaStatedLossAndRefusedAtTwoAndAHalf() {
        var network = new AppConfig.Network();
        ReflectionTestUtils.setField(network, "network", "mainnet");

        // Shipped costs: the 4 ada order cost and the 5 ada DEX floor, both stated rather than
        // inherited — this test is ABOUT those figures, so a change to either must show up here.
        var shipped = new AppConfig.ConvertConfiguration(true, BigInteger.valueOf(5_000_000L),
                BigInteger.valueOf(4_000_000L));

        // 32,525,140 collateral units at 50/1000 = 1,626,257, priced 1:1 -> the observed fee slice.
        BigInteger collateral = BigInteger.valueOf(32_525_140L);
        BigInteger txFee = BigInteger.valueOf(989_747L);
        OraclePriceFeed oneForOne =
                OraclePriceFeed.aggregated(FLDT, BigInteger.ONE, BigInteger.ONE, 0L, Long.MAX_VALUE);

        ConvertAssessment a = new ConvertEconomics(shipped, liquidation(BigInteger.ZERO), network)
                .assess(true, collateral, 50L, false, oneForOne, txFee);

        assertEquals(BigInteger.valueOf(1_626_257L), a.feeValueLovelace(), "the fee slice");
        assertEquals(BigInteger.valueOf(989_747L), a.txFee());
        assertEquals(BigInteger.valueOf(4_000_000L), a.orderAdaFunded(),
                "the CONFIGURED order cost — the fixed expense of every conversion");
        assertEquals(BigInteger.valueOf(4_989_747L), a.measuredOutlay());
        assertEquals(BigInteger.valueOf(5_000_000L), a.outlay(),
                "the DEX floor still binds — by 10,253 lovelace, which is the whole of what it is "
                        + "still worth once the order cost is stated explicitly");
        assertEquals(BigInteger.valueOf(-3_373_743L), a.net());

        // ⛔ THE TWO CANDIDATE MARGINS, and the verdict each produces.
        assertTrue(new ConvertEconomics(shipped, liquidation(BigInteger.valueOf(-5_000_000L)), network)
                        .assess(true, collateral, 50L, false, oneForOne, txFee).approved(),
                "a stated loss of 5 ada BUILDS this convert: -3,373,743 clears -5,000,000");

        ConvertAssessment refused =
                new ConvertEconomics(shipped, liquidation(BigInteger.valueOf(-2_500_000L)), network)
                        .assess(true, collateral, 50L, false, oneForOne, txFee);
        assertFalse(refused.approved(),
                "a stated loss of 2.5 ada REFUSES it: -3,373,743 is below -2,500,000");
        assertEquals(ConvertExclusion.NET_BELOW_FLOOR, refused.exclusion());
    }

    /** The shared margin — {@code loans.liquidation.profit-margin-lovelace} — and nothing else. */
    private static AppConfig.LiquidationConfiguration liquidation(BigInteger margin) {
        return new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30, margin, 200, 30);
    }
}
