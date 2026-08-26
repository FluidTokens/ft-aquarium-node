package com.fluidtokens.aquarium.offchain.util;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The derived ceilings (T-050/T-053).
 *
 * <h2>Why these exist rather than a constant</h2>
 * Fee-dependent decisions — how much collateral to pledge, how large a wallet input must be — are
 * taken <b>before</b> balancing, and the fee is known only <b>after</b> it. cardano-client-lib answers
 * that with {@code DEFAULT_COLLATERAL_AMT = Amount.ada(5.0)}, hardcoded at {@code QuickTxBuilder:65}.
 * <b>A hardcoded value that happens to be generous is still not an answer, and it does not move when
 * the chain does.</b> These are bounds no accepted transaction can exceed.
 */
class LedgerCeilingsTest {

    private static ProtocolParams params(int minFeeA, int minFeeB, int maxTxSize,
                                         String priceMem, String priceStep,
                                         String maxMem, String maxSteps, String collateralPct) {
        return ProtocolParams.builder()
                .minFeeA(minFeeA).minFeeB(minFeeB).maxTxSize(maxTxSize)
                .priceMem(new BigDecimal(priceMem)).priceStep(new BigDecimal(priceStep))
                .maxTxExMem(maxMem).maxTxExSteps(maxSteps)
                .collateralPercent(new BigDecimal(collateralPct))
                .build();
    }

    /** Preview, 2026-08. */
    private static final ProtocolParams PREVIEW =
            params(44, 155_381, 16_384, "0.0577", "0.0000721", "14000000", "10000000000", "150");

    @Test
    void theFeeCeilingIsTheLedgersOwnMaximumAndNotAnEstimate() {
        // 44 x 16384  +  155381  +  0.0577 x 14e6  +  0.0000721 x 1e10
        BigInteger expected = BigInteger.valueOf(720_896 + 155_381 + 807_800 + 721_000);

        assertEquals(expected, LedgerCeilings.maxPossibleFee(PREVIEW));
    }

    @Test
    void theCollateralCeilingIsTheFeeCeilingTimesThePercentROUNDEDUP() {
        BigInteger fee = LedgerCeilings.maxPossibleFee(PREVIEW);
        BigInteger expected = fee.multiply(BigInteger.valueOf(150))
                .add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100));

        assertEquals(expected, LedgerCeilings.maxPossibleCollateral(PREVIEW));
    }

    /**
     * ⚠ CEILING, not floor. CIP-0040 states the requirement as {@code quot(fee × pct, 100)} under a
     * {@code ≥} rule and cardano-client-lib declares {@code RoundingMode.CEILING}; rounding down
     * understates it by one lovelace, which is exactly enough to produce a collateral return no node
     * can parse.
     */
    @Test
    void roundingIsUpEvenWhenItChangesTheAnswerByOneLovelace() {
        // fee x 150 / 100 lands on .5 whenever the fee is odd
        ProtocolParams odd = params(1, 1, 1, "0", "0", "0", "0", "150");   // maxFee = 1*1 + 1 = 2
        assertEquals(BigInteger.valueOf(3), LedgerCeilings.maxPossibleCollateral(odd),
                "2 x 150 / 100 = 3 exactly");

        ProtocolParams odder = params(0, 3, 1, "0", "0", "0", "0", "150"); // maxFee = 3
        assertEquals(BigInteger.valueOf(5), LedgerCeilings.maxPossibleCollateral(odder),
                "3 x 150 / 100 = 4.5 ⇒ CEILING is 5; floor would understate by one lovelace");
    }

    @Test
    void theDerivedCollateralCeilingIsBELOWCardanoClientLibsHardcodedFiveAda() {
        assertTrue(LedgerCeilings.maxPossibleCollateral(PREVIEW)
                        .compareTo(BigInteger.valueOf(5_000_000L)) < 0,
                "the constant was not merely un-derived, it was over-provisioning against a number "
                        + "nobody had computed: " + LedgerCeilings.maxPossibleCollateral(PREVIEW));
    }

    /** It MOVES when the chain moves — the whole difference from a constant. */
    @Test
    void theCeilingTracksTheProtocolParameters() {
        ProtocolParams doubled =
                params(88, 310_762, 16_384, "0.1154", "0.0001442", "14000000", "10000000000", "150");

        assertEquals(LedgerCeilings.maxPossibleFee(PREVIEW).multiply(BigInteger.valueOf(2)),
                LedgerCeilings.maxPossibleFee(doubled),
                "double every fee parameter and the ceiling doubles; a constant would not have moved");
    }

    @Test
    void lovelaceOfSumsOnlyLovelaceAndToleratesAnAbsentAmount() {
        Utxo u = new Utxo();
        u.setAmount(new ArrayList<>(List.of(
                Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(7)).build(),
                Amount.builder().unit("aa.bb").quantity(BigInteger.valueOf(99)).build())));
        assertEquals(BigInteger.valueOf(7), LedgerCeilings.lovelaceOf(u));

        assertEquals(BigInteger.ZERO, LedgerCeilings.lovelaceOf(new Utxo()));
    }
}
