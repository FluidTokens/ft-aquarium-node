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
        var shipped = new AppConfig.ConvertConfiguration();   // enabled, margin 0, dex floor 5_000_000
        var network = new AppConfig.Network();
        ReflectionTestUtils.setField(network, "network", "mainnet");

        BigInteger collateral = BigInteger.valueOf(100_000_000L);
        BigInteger txFee = BigInteger.valueOf(500_000L);      // generous; the floor governs regardless

        ConvertAssessment a = new ConvertEconomics(shipped, network)
                .assess(true, collateral, 50L, false, poolImpliedPrice(), txFee);

        assertEquals(BigInteger.valueOf(5_000_000L), a.liquidationFee(),
                "100,000,000 * 50 / 1000, in FLDT units — the bot's whole income, and it is not ada");
        assertEquals(BigInteger.valueOf(1_113_904L), a.feeValueLovelace(),
                "≈1.11 ada at the pool's mid-price");
        assertEquals(BigInteger.valueOf(3_300_000L), a.measuredOutlay(),
                "0.5 ada tx fee + the validator's 2.8 ada order rider");
        assertEquals(BigInteger.valueOf(5_000_000L), a.outlay(),
                "the DEX-cost floor governs, because the measurement alone is below Giovanni's 5 ada");
        assertTrue(a.boundByDexCostFloor());

        assertFalse(a.approved(), "1.11 ada of fee against a 5 ada floor is a loss");
        assertEquals(ConvertExclusion.NET_BELOW_FLOOR, a.exclusion());
        assertEquals(BigInteger.valueOf(-3_886_096L), a.net());

        // And the number an operator would have to state to take it anyway — legal on mainnet since
        // findings §31, and announced loudly at boot when they do.
        var atALoss = new AppConfig.ConvertConfiguration(true, BigInteger.valueOf(-3_886_096L));
        assertTrue(new ConvertEconomics(atALoss, network)
                        .assess(true, collateral, 50L, false, poolImpliedPrice(), txFee).approved(),
                "a stated floor at exactly the net must ACCEPT it: the margin is inclusive, and an "
                        + "operator cleaning up a loan nobody will profitably touch is the point");
    }
}
