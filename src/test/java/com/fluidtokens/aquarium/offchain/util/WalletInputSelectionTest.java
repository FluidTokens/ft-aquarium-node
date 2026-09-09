package com.fluidtokens.aquarium.offchain.util;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-052 — the wallet input is chosen to cover the transaction, smallest that suffices.
 *
 * <p>Giovanni's rule 5: <i>"a 5 ada utxo would be perfect, but if this is a loan whose principal
 * needs to be repaid by the liquidator, then you will need a larger ada amount."</i> Both halves of
 * that are asserted below, against the same wallet.
 */
class WalletInputSelectionTest {

    private static Utxo ada(String hash, int ix, long lovelace) {
        Utxo utxo = new Utxo();
        utxo.setTxHash(hash);
        utxo.setOutputIndex(ix);
        utxo.setAmount(List.of(Amount.lovelace(BigInteger.valueOf(lovelace))));
        return utxo;
    }

    private static BigInteger ada(long whole) {
        return BigInteger.valueOf(whole).multiply(BigInteger.valueOf(1_000_000L));
    }

    /** The 2026-08-24 wallet shape: a small utxo first in the list, larger ones behind it. */
    private static List<Utxo> wallet() {
        return List.of(ada("aa", 0, 5_000_000L), ada("bb", 1, 58_000_000L), ada("cc", 2, 38_000_000L));
    }

    /**
     * ⇒ THE FEE-ONLY HALF. A plain liquidation brings only the fee, so the 5-ada utxo is exactly
     * right — and taking it leaves the 38 and 58 for a candidate that needs them.
     */
    @Test
    void aFeeOnlyRequirementTakesTheSmallSufficientUtxo() {
        Optional<Utxo> chosen = WalletInputSelection.smallestSufficient(wallet(), ada(2));
        assertTrue(chosen.isPresent());
        assertEquals("aa", chosen.get().getTxHash(), "a fee-only liquidation must not consume a large input");
    }

    /**
     * ⇒ THE PRINCIPAL-REPAYING HALF. The same wallet, a requirement the small utxo cannot meet:
     * the smallest that DOES is taken, not the largest and not the first.
     */
    @Test
    void aPrincipalRepayingRequirementSkipsTheOnesThatCannotFundIt() {
        Optional<Utxo> chosen = WalletInputSelection.smallestSufficient(wallet(), ada(30));
        assertTrue(chosen.isPresent());
        assertEquals("cc", chosen.get().getTxHash(),
                "38 ada covers 30; picking 58 wastes the larger input and picking 5 cannot fund it");
    }

    /**
     * ⚠ The failure that motivated the whole ticket: nominating a utxo that cannot cover the
     * transaction. It must be refused HERE, where the reason is sayable, rather than at evaluation
     * where Blockfrost reports an empty ScriptFailures map that reads as "a script said no".
     */
    @Test
    void nothingSufficientIsEmptyRatherThanTheClosestThing() {
        assertTrue(WalletInputSelection.smallestSufficient(wallet(), ada(100)).isEmpty());
    }

    @Test
    void anExactMatchCounts() {
        assertTrue(WalletInputSelection.smallestSufficient(wallet(), ada(5)).isPresent(),
                "the requirement is a floor the utxo must reach, not exceed");
        assertEquals("aa", WalletInputSelection.smallestSufficient(wallet(), ada(5)).get().getTxHash());
    }

    /** The four clauses, each rejected on its own so no single one can silently stop mattering. */
    @Test
    void ineligibleUtxosAreNotNominable() {
        Utxo twoAssets = ada("dd", 0, 90_000_000L);
        twoAssets.setAmount(List.of(Amount.lovelace(BigInteger.valueOf(90_000_000L)),
                Amount.asset("policy" + "name", BigInteger.ONE)));
        Utxo refScript = ada("ee", 0, 90_000_000L);
        refScript.setReferenceScriptHash("deadbeef");
        Utxo inlineDatum = ada("ff", 0, 90_000_000L);
        inlineDatum.setInlineDatum("d87980");
        Utxo datumHash = ada("00", 0, 90_000_000L);
        datumHash.setDataHash("cafebabe");

        for (Utxo bad : List.of(twoAssets, refScript, inlineDatum, datumHash)) {
            assertFalse(WalletInputSelection.nominable(bad), bad.getTxHash() + " must not be nominable");
            assertTrue(WalletInputSelection.smallestSufficient(List.of(bad), ada(1)).isEmpty(),
                    bad.getTxHash() + " must never be selected, however large it is");
        }
        assertTrue(WalletInputSelection.nominable(ada("aa", 0, 5_000_000L)));
    }

    /**
     * The diagnostic half: a refusal that says only "nothing qualified" cannot tell an empty wallet
     * from a wallet whose contents are all ineligible, and those need opposite operator responses.
     */
    @Test
    void largestNominableReportsWhatIsActuallyThere() {
        assertEquals(Optional.of(BigInteger.valueOf(58_000_000L)),
                WalletInputSelection.largestNominable(wallet()));
        Utxo refScript = ada("ee", 0, 90_000_000L);
        refScript.setReferenceScriptHash("deadbeef");
        assertTrue(WalletInputSelection.largestNominable(List.of(refScript)).isEmpty(),
                "an ineligible utxo must not be reported as available headroom");
        assertTrue(WalletInputSelection.largestNominable(List.of()).isEmpty());
    }

    /** Positive control: the requirement actually discriminates. */
    @Test
    void theRequirementChangesTheAnswer() {
        String small = WalletInputSelection.smallestSufficient(wallet(), ada(2)).orElseThrow().getTxHash();
        String large = WalletInputSelection.smallestSufficient(wallet(), ada(45)).orElseThrow().getTxHash();
        assertFalse(small.equals(large),
                "if the requirement does not change which utxo is chosen, it is not being applied");
    }
}
