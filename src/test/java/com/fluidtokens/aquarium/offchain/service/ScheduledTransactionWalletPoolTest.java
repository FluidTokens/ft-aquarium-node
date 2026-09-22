package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>ONE WALLET UTxO PER TANK — the property that removes the need to chain transactions.</b>
 *
 * <h2>What went wrong, and why nothing caught it</h2>
 * Selection used to run INSIDE the per-tank loop and take the SMALLEST qualifying utxo, so every
 * tank in a cycle chose the SAME one. The loop only worked because {@code completeAndWait()} waited
 * for confirmation and the next iteration re-read a wallet that now held the change: <b>the per-tank
 * provider read WAS the chaining mechanism</b>, undocumented and load-bearing. Hoisting it for
 * efficiency — the obvious optimisation, and one that was proposed — would have made every
 * transaction after the first spend an already-spent input.
 *
 * <p>⚠ <b>{@code ScheduledTransactionService} had no test class at all.</b> That is why the coupling
 * was invisible, and it is the same absence that let {@code TankStructureVerifier} sit unexecuted for
 * months. This class exists so the fan-out property is asserted by something other than reading.
 */
class ScheduledTransactionWalletPoolTest {

    private static final BigInteger REQUIRED = BigInteger.valueOf(2_000_000L);

    private static Utxo ada(String txHash, int index, long lovelace) {
        return Utxo.builder().txHash(txHash).outputIndex(index)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(lovelace)))).build();
    }

    /** ⛔ THE ONE THAT MATTERS: every tank in a cycle must get a DIFFERENT input. */
    @Test
    void everyDrawFromThePoolIsADistinctUtxo() {
        var pool = ScheduledTransactionService.walletPool(List.of(
                ada("aa", 0, 5_000_000L), ada("bb", 1, 6_000_000L), ada("cc", 2, 7_000_000L)), REQUIRED);

        Set<String> seen = new HashSet<>();
        while (!pool.isEmpty()) {
            Utxo u = pool.poll();
            assertTrue(seen.add(u.getTxHash() + "#" + u.getOutputIndex()),
                    "a utxo was handed out twice — two transactions would spend the same input and "
                            + "the second would be rejected: " + u.getTxHash());
        }
        assertEquals(3, seen.size(), "all three should have been usable");
    }

    /**
     * ⚠ The ceiling is the wallet's SHAPE, not its balance. One enormous utxo processes ONE tank per
     * cycle — the operator's lever is to split the wallet, and stating that is the point of the log
     * line this backs.
     */
    @Test
    void oneLargeUtxoYieldsOneTankPerCycleHoweverMuchAdaItHolds() {
        var pool = ScheduledTransactionService.walletPool(
                List.of(ada("aa", 0, 10_000_000_000L)), REQUIRED);

        assertEquals(1, pool.size(),
                "a thousand ada in ONE utxo is still one input, so still one tank this cycle");
    }

    /** Smallest first: a fee should not consume the utxo being saved for something that needs it. */
    @Test
    void theSmallestSufficientUtxoIsHandedOutFirst() {
        var pool = ScheduledTransactionService.walletPool(List.of(
                ada("big", 0, 90_000_000L), ada("small", 1, 2_500_000L), ada("mid", 2, 9_000_000L)),
                REQUIRED);

        assertEquals("small", pool.poll().getTxHash());
        assertEquals("mid", pool.poll().getTxHash());
        assertEquals("big", pool.poll().getTxHash());
    }

    /** Below the ledger's worst case is not a usable input, however many of them there are. */
    @Test
    void utxosThatCannotCoverTheWorstCaseFeeAreExcluded() {
        var pool = ScheduledTransactionService.walletPool(List.of(
                ada("dust1", 0, 1_000_000L), ada("dust2", 1, 1_999_999L), ada("ok", 2, 2_000_000L)),
                REQUIRED);

        assertEquals(1, pool.size(), "only the one that covers `required` may be used");
        assertEquals("ok", pool.poll().getTxHash());
    }

    /**
     * ⛔ NEVER SPEND A UTxO CARRYING A REFERENCE SCRIPT. Spending one destroys it permanently and
     * breaks every transaction that references it — and this service spends from the same wallet the
     * liquidation bot publishes reference scripts into. That happened once already, on 2026-08-25.
     */
    @Test
    void aUtxoCarryingAReferenceScriptIsNeverHandedOut() {
        Utxo withScript = Utxo.builder().txHash("refscript").outputIndex(0)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(50_000_000L))))
                .referenceScriptHash("deadbeef").build();

        var pool = ScheduledTransactionService.walletPool(
                List.of(withScript, ada("plain", 1, 5_000_000L)), REQUIRED);

        assertEquals(1, pool.size());
        assertEquals("plain", pool.poll().getTxHash(), "the reference-script utxo must be untouched");
    }

    /** ⚠ A multi-asset utxo drags its tokens into a transaction that never asked for them. */
    @Test
    void aUtxoHoldingTokensIsNotUsedToPayAFee() {
        Utxo withToken = Utxo.builder().txHash("hastoken").outputIndex(0)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(50_000_000L)),
                        Amount.asset("deadbeef", "TOK", BigInteger.TEN))).build();

        var pool = ScheduledTransactionService.walletPool(
                List.of(withToken, ada("plain", 1, 5_000_000L)), REQUIRED);

        assertEquals(1, pool.size());
        assertEquals("plain", pool.poll().getTxHash());
    }

    /** An empty pool is the signal to stop the cycle, not to fall through and build with nothing. */
    @Test
    void anEmptyWalletProducesAnEmptyPoolRatherThanThrowing() {
        assertTrue(ScheduledTransactionService.walletPool(List.of(), REQUIRED).isEmpty());
        assertFalse(ScheduledTransactionService.walletPool(
                List.of(ada("ok", 0, 5_000_000L)), REQUIRED).isEmpty());
    }
}
