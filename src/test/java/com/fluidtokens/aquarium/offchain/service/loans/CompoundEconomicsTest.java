package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundExclusion;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compound profitability gate, including the case Giovanni ruled on 2026-09-02:
 * <i>"there should be a check compoudingFeePerMille in the bot to accept to process if zero … as long
 * as operator checks this himself and owns it."</i>
 *
 * <p>The shape that encodes "owns it" is the same one the liquidation margin uses: a floor the
 * operator <b>states</b>, never a check they switch off. So there is no boolean here to disable the
 * gate — arming zero-fee work means stating a negative number, which still bounds the loss.
 */
class CompoundEconomicsTest {

    private static final BigInteger ESCROW_45_ADA = BigInteger.valueOf(45_000_000L);
    private static final BigInteger TX_FEE = BigInteger.valueOf(300_000L);

    private static CompoundEconomics economics(boolean enabled, long floorLovelace, String network) {
        var cfg = new AppConfig.CompoundConfiguration(enabled, 60L, BigInteger.valueOf(floorLovelace));
        var net = new AppConfig.Network();
        ReflectionTestUtils.setField(net, "network", network);
        return new CompoundEconomics(cfg, net);
    }

    private static CompoundEconomics armed(long floorLovelace) {
        return economics(true, floorLovelace, "preview");
    }

    /** A paying pool: 45 ADA escrow at 5‰ earns 225_000, which does not clear a 300_000 fee. */
    @Test
    void aFeeThatDoesNotCoverTheTransactionIsRefusedAtTheSafeDefault() {
        CompoundAssessment a = armed(0).assess(true, true, true, ESCROW_45_ADA, 5L, TX_FEE);

        assertEquals(BigInteger.valueOf(225_000L), a.expectedFee());
        assertEquals(BigInteger.valueOf(-75_000L), a.net());
        assertFalse(a.approved());
        assertEquals(CompoundExclusion.NET_BELOW_FLOOR, a.exclusion());
    }

    /** The same pool with enough escrow clears it: 200 ADA at 5‰ earns 1_000_000. */
    @Test
    void aFeeThatCoversTheTransactionIsApproved() {
        CompoundAssessment a = armed(0)
                .assess(true, true, true, BigInteger.valueOf(200_000_000L), 5L, TX_FEE);

        assertEquals(BigInteger.valueOf(1_000_000L), a.expectedFee());
        assertEquals(BigInteger.valueOf(700_000L), a.net());
        assertTrue(a.approved());
    }

    /** Exact break-even is allowed by the default floor of 0 — it is not a loss. */
    @Test
    void exactBreakEvenIsApprovedAtTheDefaultFloor() {
        CompoundAssessment a = armed(0)
                .assess(true, true, true, BigInteger.valueOf(60_000_000L), 5L, BigInteger.valueOf(300_000L));

        assertEquals(BigInteger.ZERO, a.net());
        assertTrue(a.approved(), "net == floor must pass: the floor is a minimum, not a strict bound");
    }

    /**
     * ⛔ THE SAFE DEFAULT. The only live preview pool published {@code compoudingFeePerMille = 0}
     * (findings §20.2). Out of the box that work is refused, so the node can never quietly do it.
     */
    @Test
    void aZeroFeePoolIsRefusedOutOfTheBox() {
        CompoundAssessment a = armed(0).assess(true, true, true, ESCROW_45_ADA, 0L, TX_FEE);

        assertTrue(a.zeroFeePool());
        assertEquals(BigInteger.ZERO, a.expectedFee());
        assertEquals(TX_FEE.negate(), a.net(), "the whole outlay is the transaction fee");
        assertFalse(a.approved());
        assertEquals(CompoundExclusion.NET_BELOW_FLOOR, a.exclusion());
    }

    /**
     * ⛔ AND THE OPERATOR'S RULED CHOICE. A stated negative floor arms exactly that work — and still
     * bounds it: the loss may not exceed the number the operator wrote down.
     */
    @Test
    void aStatedNegativeFloorArmsZeroFeeWorkAndStillBoundsTheLoss() {
        CompoundEconomics armedAtALoss = armed(-2_000_000L);

        CompoundAssessment allowed = armedAtALoss.assess(true, true, true, ESCROW_45_ADA, 0L, TX_FEE);
        assertTrue(allowed.approved(), "a -2 ADA stated bound must accept a 0.3 ADA loss");
        assertTrue(allowed.zeroFeePool());

        // Still a bound, not an off switch: a loss beyond the stated figure is refused.
        CompoundAssessment tooExpensive = armedAtALoss
                .assess(true, true, true, ESCROW_45_ADA, 0L, BigInteger.valueOf(2_500_000L));
        assertFalse(tooExpensive.approved(), "a stated bound must still refuse a worse loss");
        assertEquals(CompoundExclusion.NET_BELOW_FLOOR, tooExpensive.exclusion());
    }

    /**
     * ⛔ THE UNIT TRAP. A token-principal pool's fee is a token quantity; subtracting a lovelace tx
     * fee from it yields a number that looks like profit and is not. Refused, never guessed.
     */
    @Test
    void aNonAdaPrincipalIsRefusedRatherThanCompared() {
        CompoundAssessment a = armed(0)
                .assess(true, true, false, BigInteger.valueOf(999_000_000L), 50L, TX_FEE);

        assertFalse(a.approved());
        assertEquals(CompoundExclusion.PRINCIPAL_NOT_ADA, a.exclusion());
        assertNullNet(a);
    }

    @Test
    void theStructuralRefusalsFireBeforeAnyArithmetic() {
        assertEquals(CompoundExclusion.NOT_ARMED,
                economics(false, 0, "preview").assess(true, true, true, ESCROW_45_ADA, 50L, TX_FEE).exclusion());
        assertEquals(CompoundExclusion.BOND_NAMES_NO_POOL,
                armed(0).assess(false, true, true, ESCROW_45_ADA, 50L, TX_FEE).exclusion());
        assertEquals(CompoundExclusion.POOL_NOT_LIVE,
                armed(0).assess(true, false, true, ESCROW_45_ADA, 50L, TX_FEE).exclusion());
    }

    /**
     * ⛔ The on-chain expression truncates, and so must this one. Rounding UP would claim a fee the
     * validator does not require the pool to give up, and the transaction would fail phase 2 — after
     * collateral is committed.
     */
    @Test
    void theFeeTruncatesExactlyAsTheValidatorDoes() {
        // 1999 * 1 / 1000 = 1 on chain, not 2.
        assertEquals(BigInteger.ONE, CompoundEconomics.expectedFee(BigInteger.valueOf(1999L), 1L));
        assertEquals(BigInteger.ZERO, CompoundEconomics.expectedFee(BigInteger.valueOf(999L), 1L));
        assertEquals(BigInteger.valueOf(225_000L), CompoundEconomics.expectedFee(ESCROW_45_ADA, 5L));
    }

    /** A negative floor is a preview-only override; on mainnet it must refuse to construct. */
    @Test
    void aNegativeFloorIsAHardStartupFailureOnMainnet() {
        var mainnet = economics(true, -1L, "mainnet");
        IllegalStateException e = assertThrows(IllegalStateException.class, mainnet::announceAndGuard);
        assertTrue(e.getMessage().contains("profit-margin-lovelace"), e.getMessage());

        // Fail-closed: an unrecognised network is treated as mainnet, not waved through.
        assertThrows(IllegalStateException.class, () -> economics(true, -1L, "wonderland").announceAndGuard());
    }

    @Test
    void aNegativeFloorOnPreviewWarnsAndProceeds() {
        armed(-2_000_000L).announceAndGuard();
        economics(true, 0L, "mainnet").announceAndGuard();
    }

    private static void assertNullNet(CompoundAssessment a) {
        assertTrue(a.net() == null && a.expectedFee() == null,
                "a structural refusal must not publish arithmetic that was never valid");
    }
}
