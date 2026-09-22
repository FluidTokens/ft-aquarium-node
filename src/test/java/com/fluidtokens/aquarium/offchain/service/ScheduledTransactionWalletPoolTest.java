package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.fluidtokens.aquarium.offchain.util.LedgerCeilings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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

    /**
     * ⛔ <b>THREE CANDIDATE CEILINGS, AND ONLY THE THIRD IS SAFE.</b>
     *
     * <pre>
     *   maxPossibleFee           2,549,327   what PR #22 shipped     -- UNDECODABLE
     *   maxPossibleCollateral    3,823,991   the first attempted fix -- STILL UNDECODABLE
     *   CCL's DEFAULT_COLLATERAL 5,000,000   what the library asks   -- correct
     * </pre>
     *
     * <p>Mainnet figures, 2026-09-22. The ledger wants collateral of fee x {@code collateral_percent}
     * (~3.82 ada), so the middle row looks like the answer and is what a careful reading of the
     * ledger rules produces. It is wrong, because <b>cardano-client-lib never consults the ledger</b>:
     * {@code QuickTxBuilder:65} hardcodes {@code Amount.ada(5.0)}. Any utxo under that makes CCL emit
     * a negative collateral return; a negative {@code MaryValue} is unrepresentable, so the provider
     * rejects the CBOR at offset 0 — {@code DeserialiseFailure 0 "expected tag"} — before any
     * validation runs.
     *
     * <p>⚠ <b>The middle row is why this test exists in this shape.</b> A two-band test would have
     * gone green on the fix that did not work: 3,823,991 rejects the 3 ada utxo that 2,549,327
     * accepted, so the bug looks fixed while the symptom is unchanged in production. <b>The band that
     * catches it is the one between the two ceilings that are both too low</b> — a 4 ada utxo, which
     * satisfies the ledger and not the library.
     *
     * <p>⚑ The same incident on a different path is
     * {@code LiquidateTransactionBuilder.Refusal.INSUFFICIENT_COLLATERAL} (2026-08-25).
     */
    @Test
    void aUtxoBelowCardanoClientLibsOwnCollateralFigureIsNotUsable() {
        BigInteger feeCeiling = BigInteger.valueOf(2_549_327L);
        BigInteger ledgerCollateralCeiling = BigInteger.valueOf(3_823_991L);
        BigInteger cclCeiling = BigInteger.valueOf(5_000_000L);

        // 4 ada: over the LEDGER's collateral requirement, under the LIBRARY's. The band that the
        // first fix left open, and the reason the symptom did not move when the floor was raised.
        Utxo satisfiesLedgerNotLibrary = ada("gap", 0, 4_000_000L);

        assertEquals(1, ScheduledTransactionService.walletPool(
                        List.of(satisfiesLedgerNotLibrary), feeCeiling).size(),
                "PR #22's floor accepted it");
        assertEquals(1, ScheduledTransactionService.walletPool(
                        List.of(satisfiesLedgerNotLibrary), ledgerCollateralCeiling).size(),
                "and so did the first attempted fix -- which is why the bug survived it");
        assertTrue(ScheduledTransactionService.walletPool(
                        List.of(satisfiesLedgerNotLibrary), cclCeiling).isEmpty(),
                "only the library's own figure rejects it; accepting it produces a transaction whose "
                        + "CBOR no node can decode, which is worse than one that merely fails");
    }

    /**
     * ⛔ <b>AND THE CEILING THE PROCESSOR ACTUALLY ASKS FOR IS THE THING UNDER TEST.</b>
     *
     * <p>⚠ The test above exercises {@code walletPool}, which takes the ceiling as a <b>parameter</b>
     * — so it can pin every band and still say nothing about which one production passes. That is
     * exactly the gap the first fix fell through: the filter was correct at every value it was
     * handed, and the caller handed it the wrong one. <b>A test that cannot fail when the caller
     * chooses wrongly is not a regression test for this bug.</b>
     */
    @Test
    void theProcessorAsksForAtLeastWhatCardanoClientLibWillDemand() {
        ProtocolParams params = new ProtocolParams();
        params.setMinFeeA(44);
        params.setMinFeeB(155381);
        params.setMaxTxSize(16384);
        params.setCollateralPercent(BigDecimal.valueOf(150));
        params.setPriceMem(BigDecimal.valueOf(0.0577));
        params.setPriceStep(BigDecimal.valueOf(0.0000721));
        params.setMaxTxExMem("14000000");
        params.setMaxTxExSteps("10000000000");
        params.setMinFeeRefScriptCostPerByte(BigDecimal.valueOf(15));

        BigInteger required = ScheduledTransactionService.requiredWalletLovelace(params);

        assertTrue(required.compareTo(BigInteger.valueOf(5_000_000L)) >= 0,
                "cardano-client-lib hardcodes DEFAULT_COLLATERAL_AMT = Amount.ada(5.0) at "
                        + "QuickTxBuilder:65 and builds its own selector to find it -- asking for "
                        + "less means a negative collateral return and undecodable CBOR. Got "
                        + required);
        assertTrue(required.compareTo(LedgerCeilings.maxPossibleCollateral(params)) >= 0,
                "and it must still satisfy the LEDGER's figure, so this stays correct if "
                        + "collateral_percent ever rises past the library's constant");
    }

    /** An empty pool is the signal to stop the cycle, not to fall through and build with nothing. */
    @Test
    void anEmptyWalletProducesAnEmptyPoolRatherThanThrowing() {
        assertTrue(ScheduledTransactionService.walletPool(List.of(), REQUIRED).isEmpty());
        assertFalse(ScheduledTransactionService.walletPool(
                List.of(ada("ok", 0, 5_000_000L)), REQUIRED).isEmpty());
    }
}
