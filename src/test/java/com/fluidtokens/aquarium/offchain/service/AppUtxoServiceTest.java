package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-049 — the wallet view must never be a <b>silently partial</b> one.
 *
 * <h2>The defect this pins</h2>
 * The previous shape read the local index first and consulted the provider <b>only when the index
 * returned EMPTY</b>:
 * <pre>{@code
 *   if (!walletUtxos.isEmpty()) return walletUtxos;   // PARTIAL SET RETURNED SILENTLY
 *   else                        … provider fallback …  // fires only on empty
 * }</pre>
 * An index-backed balance cannot tell <i>"the wallet is empty"</i> from <i>"the wallet's history
 * starts below our sync point"</i>. Empty triggers the fallback and you get the truth; <b>partial
 * returns quietly</b>, and `ScheduledTransactionService` (the <b>mainnet</b> tank),
 * `LiquidationExecutor` and `wallet_ok` are then all wrong in the same direction at once.
 *
 * <p>⚠ The wallet is genuinely indexed — {@code TankUtxoStorage:47} keeps the bot's own payment
 * credential — so this was live, not theoretical.
 *
 * <p>Pins no coordinate, no address, no deployment.
 */
class AppUtxoServiceTest {

    private static Utxo utxo(String hash, long lovelace) {
        Utxo u = new Utxo();
        u.setTxHash(hash);
        u.setOutputIndex(0);
        u.setAmount(new ArrayList<>(List.of(
                Amount.builder().unit("lovelace").quantity(BigInteger.valueOf(lovelace)).build())));
        return u;
    }

    private static final Utxo BIG = utxo("aa".repeat(32), 9_964_993_434L);
    private static final Utxo SMALL = utxo("bb".repeat(32), 1_000_000L);

    /** ⛔ The regression this ticket exists for. */
    @Test
    void aPartialIndexNeverWinsOverTheProvider() {
        List<Utxo> provider = List.of(BIG, SMALL);
        List<Utxo> partialIndex = List.of(SMALL);              // the 1 ADA output only

        var result = AppUtxoService.walletUtxos(() -> provider, () -> partialIndex);

        assertEquals(2, result.size(),
                "the provider saw two utxos and the index saw one — returning the index's view is the "
                        + "silent understatement that starves every builder");
        assertTrue(result.contains(BIG), "the large utxo must not disappear because the index missed it");
    }

    /**
     * ⚠ An EMPTY provider answer is a real answer. Second-guessing it with the index would resurrect
     * outputs the chain has since spent — the opposite failure, and a worse one.
     */
    @Test
    void anEmptyProviderAnswerIsAnANSWERAndTheIndexDoesNotOverrideIt() {
        var result = AppUtxoService.walletUtxos(List::of, () -> List.of(BIG, SMALL));

        assertTrue(result.isEmpty(),
                "the provider says the wallet is empty; the index holding rows means those rows are "
                        + "stale, not that the wallet has funds");
    }

    /** Only a provider that FAILS hands over — signalled by null, never by an empty list. */
    @Test
    void theIndexIsUsedOnlyWhenTheProviderFAILS() {
        List<Utxo> index = List.of(BIG);

        var result = AppUtxoService.walletUtxos(() -> null, () -> index);

        assertSame(index, result, "a failed provider falls back to the index");
    }

    @Test
    void bothUnavailableYieldsEmptyRatherThanThrowing() {
        assertTrue(AppUtxoService.walletUtxos(() -> null, List::of).isEmpty());
    }

    /**
     * The index is not consulted at all on the happy path. Not an optimisation — a correctness
     * property: a query that is never made cannot contribute a stale row.
     */
    @Test
    void theIndexIsNotEvenQUERIEDWhenTheProviderAnswers() {
        boolean[] indexTouched = {false};

        AppUtxoService.walletUtxos(() -> List.of(BIG), () -> {
            indexTouched[0] = true;
            return List.of(SMALL);
        });

        assertTrue(!indexTouched[0], "the index must not be read when the provider has answered");
    }
}
