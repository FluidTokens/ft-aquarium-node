package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-050 — collateral is chosen by OUR selection, not nominated as the spend input and not left to CCL.
 *
 * <h2>Why not CCL's auto-selection, given "leverage auto features as much as possible"</h2>
 * {@code QuickTxBuilder.buildCollateralOutput} (v0.7.2 {@code :507}) builds its <b>own</b>
 * {@code DefaultUtxoSelectionStrategyImpl} and never reads the context's, so both of our guards are
 * invisible to it — and it has no reference-script exclusion. The paying address is the bot's own,
 * where a published reference script can sit; <b>one did, and an unguarded builder consumed it on
 * 2026-08-25.</b> Collateral is what a phase-2 failure consumes.
 *
 * <p>⚠ It also <b>assumes</b> the amount: {@code DEFAULT_COLLATERAL_AMT = Amount.ada(5.0)}, hardcoded.
 * Giovanni's rule 5 is precisely about <em>deriving</em> an amount from a requirement, so auto-collateral
 * satisfies his rule 2 and violates his rule 5 — and the rule that names the mechanism wins.
 */
class CollateralSelectionTest {

    private static final ProtocolParams PARAMS = LoanFixtures.protocolParams().getProtocolParams();
    private static final String CHANGE = "addr_test1qqgern5qmhfqlztqfkk7wjfc8qvadlsjc2xhwm45nrdzudn3fakarjxlcrdwsee2wtcja4l3neq6dfxernah25938dsskl9d9z";
    private static final String TFLDT = "1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c74464c4454";

    private static Utxo utxo(String tag, long lovelace, String scriptHash, boolean tokens) {
        Utxo u = new Utxo();
        u.setTxHash(tag.repeat(64 / tag.length()));
        u.setOutputIndex(0);
        u.setAddress(CHANGE);
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(lovelace)).build());
        if (tokens) {
            amounts.add(Amount.builder().unit(TFLDT).quantity(BigInteger.valueOf(5_000_000L)).build());
        }
        u.setAmount(amounts);
        u.setReferenceScriptHash(scriptHash);
        return u;
    }

    private static com.bloxbean.cardano.client.api.UtxoSupplier supplying(Utxo... utxos) {
        return LoanFixtures.utxoSupplier(Arrays.asList(utxos));
    }

    private static List<String> hashesOf(TransactionInput[] inputs) {
        return Arrays.stream(inputs).map(TransactionInput::getTransactionId).toList();
    }

    // ---- the amount is DERIVED ------------------------------------------------------------------

    @Test
    void theRequirementIsDerivedFromProtocolParamsAndNotAssumed() {
        BigInteger required = LiquidateTransactionBuilder.maxPossibleCollateral(PARAMS);

        // minFeeA*maxTxSize + minFeeB + priceMem*maxTxExMem + priceStep*maxTxExSteps, x collateralPercent
        BigInteger expectedMaxFee = BigInteger.valueOf(44L * 16384)
                .add(BigInteger.valueOf(155_381))
                .add(BigInteger.valueOf(807_800))      // 0.0577 x 14,000,000
                .add(BigInteger.valueOf(721_000));     // 0.0000721 x 10,000,000,000
        assertEquals(expectedMaxFee.multiply(BigInteger.valueOf(150))
                .add(BigInteger.valueOf(99)).divide(BigInteger.valueOf(100)), required);

        assertTrue(required.compareTo(BigInteger.valueOf(5_000_000L)) < 0,
                "the DERIVED ceiling is below cardano-client-lib's hardcoded 5 ADA — and it moves when "
                        + "the chain's parameters move, which is the whole difference: " + required);
    }

    // ---- the guard CCL cannot apply -------------------------------------------------------------

    @Test
    void aReferenceScriptUtxoIsNEVERPledgedAsCollateral() {
        Utxo refScript = utxo("aa", 100_000_000L, "deadbeef", false);
        Utxo ordinary = utxo("bb", 10_000_000L, null, false);

        var chosen = LiquidateTransactionBuilder.collateralInputsFor(
                supplying(refScript, ordinary), PARAMS, CHANGE, ordinary);

        assertFalse(hashesOf(chosen).contains(refScript.getTxHash()),
                "collateral is what a PHASE-2 failure consumes — a published reference script must "
                        + "never be pledged, and this is the guard CCL's own selector cannot apply");
        assertTrue(hashesOf(chosen).contains(ordinary.getTxHash()));
    }

    // ---- CIP-40: tokens are allowed --------------------------------------------------------------

    @Test
    void aTokenBearingUtxoIsELIGIBLE() {
        Utxo tokenBearing = utxo("cc", 9_964_993_434L, null, true);

        var chosen = LiquidateTransactionBuilder.collateralInputsFor(
                supplying(tokenBearing), PARAMS, CHANGE, tokenBearing);

        assertEquals(List.of(tokenBearing.getTxHash()), hashesOf(chosen),
                "CIP-0040 permits tokens in collateral when a collateral output is specified, and we "
                        + "specify one — refusing them is what made 9,964 ADA unreachable");
    }

    /** The measured 2026-08-25 wallet: a 1 ADA ada-only output and a large token-bearing one. */
    @Test
    void theMeasuredWalletNowReachesItsLargeUtxoInsteadOfStarving() {
        Utxo dust = utxo("dd", 1_000_000L, null, false);
        Utxo big = utxo("ee", 9_964_993_434L, null, true);

        var chosen = LiquidateTransactionBuilder.collateralInputsFor(
                supplying(dust, big), PARAMS, CHANGE, dust);

        assertEquals(List.of(big.getTxHash()), hashesOf(chosen),
                "largest-ada first: one input already covers the derived ceiling, so the 1 ADA output "
                        + "that starved the bot is not chosen and not needed");
    }

    // ---- accumulation and bounds ----------------------------------------------------------------

    @Test
    void severalSmallUtxosAreAccumulatedUntilTheRequirementIsMet() {
        Utxo a = utxo("11", 2_000_000L, null, false);
        Utxo b = utxo("22", 2_000_000L, null, false);
        Utxo c = utxo("33", 2_000_000L, null, false);

        var chosen = LiquidateTransactionBuilder.collateralInputsFor(
                supplying(a, b, c), PARAMS, CHANGE, a);

        assertEquals(2, chosen.length,
                "2 x 2 ADA covers the ~3.6 ADA derived ceiling; a third would be waste");
    }

    @Test
    void neverMoreThanMaxCollateralInputs() {
        Utxo[] dust = new Utxo[8];
        for (int i = 0; i < dust.length; i++) {
            dust[i] = utxo(String.format("%02d", 40 + i), 200_000L, null, false);
        }

        var chosen = LiquidateTransactionBuilder.collateralInputsFor(
                supplying(dust), PARAMS, CHANGE, dust[0]);

        assertTrue(chosen.length <= PARAMS.getMaxCollateralInputs(),
                "maxCollateralInputs is a protocol parameter and exceeding it is a phase-1 rejection");
    }

    /**
     * ⚠ An empty array would hand CCL back its own unguarded selector — the exact defect this exists
     * to prevent. Falling back to the nominated utxo keeps the guard even when nothing qualifies.
     */
    @Test
    void withNoEligibleUtxoItFallsBackRatherThanHandingControlToCCL() {
        Utxo onlyRefScript = utxo("ff", 100_000_000L, "deadbeef", false);
        Utxo nominated = utxo("99", 3_000_000L, null, false);

        var chosen = LiquidateTransactionBuilder.collateralInputsFor(
                supplying(onlyRefScript), PARAMS, CHANGE, nominated);

        assertEquals(1, chosen.length, "never an empty array");
        assertEquals(nominated.getTxHash(), chosen[0].getTransactionId());
    }
}
