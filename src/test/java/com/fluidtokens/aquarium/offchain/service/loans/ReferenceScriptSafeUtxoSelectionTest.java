package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.coinselection.UtxoSelectionStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bot must never fund itself by spending its own published reference script.
 * <p>
 * Verified against the pinned cardano-client-lib v0.7.2 source: {@code
 * DefaultUtxoSelectionStrategyImpl.select} screens candidates on {@code accept(utxo)},
 * {@code utxosToExclude}, datum hash and inline datum — and {@code accept} is {@code return true}.
 * There is <b>no</b> reference-script exclusion in coin selection. On preview the published
 * {@code loan_claim_action} script sits in a UTxO at the bot's own operational address
 * ({@code 48c102c0…#0}, confirmed live 2026-08-24), so the balancer can see it as ordinary ada.
 * Spending it makes every later convert liquidation exceed {@code maxTxSize} — a silent,
 * irreversible, self-disabling failure.
 */
class ReferenceScriptSafeUtxoSelectionTest {

    /** The bot's real preview operational address — a truncated one throws "Invalid checksum" and
     * would make every assertThrows below pass for the wrong reason. */
    private static final String ADDRESS = "addr_test1qztwnc4gj0yqp3z8cq8056lewx8y634hvdvcj45ky9vqd0"
            + "m85mlrjgjry6pnqf32xtu8rvlp344wzs0eajkc5mlsc3zq47crnj";
    private static final String PUBLISHED_SCRIPT_TX =
            "48c102c0034b04558c640df211045fdd7511dc7046b55942ca5909372eab24cd";
    private static final String LOAN_CLAIM_ACTION_HASH =
            "9ae63b26c98d90024a45f9cdb57e4154f72144d44325f0a261b8bc1d";

    private static Utxo publishedScript(long lovelace) {
        return Utxo.builder()
                .txHash(PUBLISHED_SCRIPT_TX)
                .outputIndex(0)
                .address(ADDRESS)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(lovelace))))
                .referenceScriptHash(LOAN_CLAIM_ACTION_HASH)
                .build();
    }

    private static Utxo ordinary(String txHash, long lovelace) {
        return Utxo.builder()
                .txHash(txHash)
                .outputIndex(0)
                .address(ADDRESS)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(lovelace))))
                .build();
    }

    private static UtxoSupplier supplier(List<Utxo> utxos) {
        return new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
                return page != null && page > 0 ? List.of()
                        : utxos.stream().filter(u -> u.getAddress().equals(address)).toList();
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return utxos.stream()
                        .filter(u -> u.getTxHash().equals(txHash) && u.getOutputIndex() == outputIndex)
                        .findFirst();
            }
        };
    }

    /**
     * THE DISCRIMINATING CASE. The published reference script is the ONLY ada at the address and it
     * would comfortably cover the request. The correct answer is to fail for want of funds: refusing
     * to build is recoverable, eating the reference script is not.
     * <p>
     * Swap {@code ReferenceScriptSafeUtxoSelection.strategy(..)} for a plain
     * {@code DefaultUtxoSelectionStrategyImpl} and this test fails — selection succeeds and hands
     * back {@code 48c102c0…#0}.
     */
    @Test
    void thePublishedReferenceScriptIsNeverSelectedEvenWhenItIsTheOnlyAdaAvailable() {
        UtxoSelectionStrategy strategy = ReferenceScriptSafeUtxoSelection.strategy(
                supplier(List.of(publishedScript(100_000_000L))));

        // The exception must be an INSUFFICIENT-FUNDS one specifically. A bare
        // assertThrows(RuntimeException) would also swallow a malformed-address failure and pass
        // while proving nothing — which is exactly what it did on the first run of this test.
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> strategy.select(ADDRESS, Amount.lovelace(BigInteger.valueOf(30_000_000L)),
                        Set.of()));
        assertTrue(e.getMessage() != null && e.getMessage().contains("Not enough funds"),
                "selection must fail for want of SPENDABLE funds rather than return the published "
                        + "reference script, and for that reason specifically: " + e);
    }

    /** An ordinary UTxO alongside it is selected, and the reference script is left alone. */
    @Test
    void anOrdinaryUtxoIsSelectedAndTheReferenceScriptIsLeftAlone() {
        String ordinaryTx = "aa".repeat(32);
        UtxoSelectionStrategy strategy = ReferenceScriptSafeUtxoSelection.strategy(
                supplier(List.of(publishedScript(100_000_000L), ordinary(ordinaryTx, 50_000_000L))));

        Set<Utxo> selected = strategy.select(ADDRESS,
                Amount.lovelace(BigInteger.valueOf(30_000_000L)), Set.of());

        assertEquals(1, selected.size(), "one ordinary utxo covers it: " + selected);
        assertEquals(ordinaryTx, selected.iterator().next().getTxHash(),
                "the ordinary utxo must be the one taken");
        assertTrue(selected.stream().noneMatch(u -> u.getTxHash().equals(PUBLISHED_SCRIPT_TX)),
                "the published reference script must never appear: " + selected);
    }

    /**
     * THE FALLBACK HALF, which is the part an {@code accept} override alone would miss.
     * {@code DefaultUtxoSelectionStrategyImpl.fallback()} returns a <b>plain</b>
     * {@code LargestFirstUtxoSelectionStrategy} whose own {@code accept} is {@code return true}, and
     * that fallback is taken whenever selection exceeds the input limit. A guard that holds in the
     * common case and lapses under pressure is not a guard.
     * <p>
     * Restore {@code fallback()} to {@code super.fallback()} and this test fails.
     */
    @Test
    void theFallbackStrategyRefusesTheReferenceScriptToo() {
        UtxoSelectionStrategy fallback = ReferenceScriptSafeUtxoSelection
                .strategy(supplier(List.of(publishedScript(100_000_000L))))
                .fallback();

        assertNotNull(fallback, "the chain must have a fallback at all");
        RuntimeException e = assertThrows(RuntimeException.class,
                () -> fallback.select(ADDRESS, Amount.lovelace(BigInteger.valueOf(30_000_000L)),
                        Set.of()));
        assertTrue(e.getMessage() != null && e.getMessage().contains("Not enough funds"),
                "the FALLBACK must refuse the reference script as well, and for want of funds "
                        + "specifically — this is the half a plain accept() override silently "
                        + "loses: " + e);
    }

    /** And the fallback still does its job on ordinary funds. */
    @Test
    void theFallbackStillSelectsOrdinaryFunds() {
        String ordinaryTx = "bb".repeat(32);
        Set<Utxo> selected = ReferenceScriptSafeUtxoSelection
                .strategy(supplier(List.of(publishedScript(100_000_000L), ordinary(ordinaryTx, 50_000_000L))))
                .fallback()
                .select(ADDRESS, Amount.lovelace(BigInteger.valueOf(30_000_000L)), Set.of());

        assertEquals(ordinaryTx, selected.iterator().next().getTxHash());
    }
}
