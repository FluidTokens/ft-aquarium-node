package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>A 404 IS AN ANSWER. Reading it as an outage killed the either-order fallback entirely.</b>
 *
 * <h2>The defect, measured on mainnet 2026-09-05</h2>
 * The ADA/FLDT Minswap V2 pool exists and is healthy — 1.69M ada / 7.6M FLDT, one UTxO, inline datum
 * — and {@code MinswapPoolResolver} reported {@code LOOKUP_FAILED} for it, which
 * {@code ConvertLiquidationRouter} surfaced as "no Minswap pool".
 *
 * <p>{@code compute_lp_asset_name} is <b>order-sensitive</b>, so exactly one ordering names a real
 * asset:
 * <pre>
 * LP(ada, fldt)  bc53f5c2…  -> HTTP 200   the pool
 * LP(fldt, ada)  df40ef9f…  -> HTTP 404   does not exist, correctly
 * </pre>
 * Blockfrost expresses <em>"no UTxO with that asset at this address"</em> as a <b>404</b>, not as an
 * empty list. So the wrong ordering hit the {@code !isSuccessful()} branch — {@code LOOKUP_FAILED} —
 * and {@code resolveEitherOrder} rethrows anything that is not {@code NO_POOL_FOR_PAIR}.
 * <b>The correct ordering was never tried.</b>
 *
 * <p>⚠ Two things that make this a defect rather than a mislabel, and both are asserted below:
 * <ol>
 *   <li>the {@code found.isEmpty()} → {@code NO_POOL_FOR_PAIR} branch is <b>unreachable</b> through a
 *       provider that 404s, so it had never fired in production;</li>
 *   <li>the fallback fails whenever the wrong ordering is tried first — <b>a coin flip per pair</b> —
 *       which is why this presented as "the pool does not exist".</li>
 * </ol>
 *
 * <p>The rule was already written down in this repo, in {@code LoansConfigVerifier}: <i>"a 4xx is an
 * answer, not an outage"</i>. This class was the sibling that never applied it.
 */
class MinswapPoolLookup404Test {

    private static final String POOL_ADDRESS = "addr1_pool";
    private static final String POOL_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";

    private static final AssetType ADA = AssetType.ada();
    private static final AssetType FLDT = AssetType.fromUnit(
            "577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e0014df10464c4454");

    /** The one ordering the chain actually serves, exactly as mainnet does. */
    private static final String LIVE_UNIT = POOL_POLICY + ConvertTxEncoder.computeLpAssetName(ADA, FLDT);

    /**
     * ⚑ The REAL inline datum of the live mainnet ADA/FLDT pool, read from
     * {@code f6ed88da2717cbe5b14c…#1} on 2026-09-05 — not a hand-built stand-in. A synthetic datum
     * would decode differently (or not at all) and the resolver's own arity guard would refuse it,
     * which would make every assertion here about a resolver that cannot succeed.
     */
    private static final String LIVE_POOL_DATUM = "d8799fd8799fd87a9f581c1eae96baf29e27682ea3f815aba361a0c6059d45e4bfbe95bbd2f44affffd8799f4040ffd8799f581c577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e480014df10464c4454ff1b000003238e7742b61b00000189c7ddfb791b000006e9d68f6ee818501850d8799f190682ffd87980ff";

    /**
     * A provider that answers 404 for every asset except the one that exists — Blockfrost's real
     * shape. The recorded calls are what proves the SECOND ordering was attempted at all.
     */
    private static final class FakeUtxoService implements UtxoService {
        private final List<String> asked = new ArrayList<>();
        private final int missCode;

        FakeUtxoService(int missCode) {
            this.missCode = missCode;
        }

        @Override
        public Result<List<Utxo>> getUtxos(String address, String unit, int count, int page) {
            asked.add(unit);
            if (LIVE_UNIT.equals(unit)) {
                Utxo utxo = Utxo.builder().txHash("aa".repeat(32)).outputIndex(1)
                        .inlineDatum(LIVE_POOL_DATUM).build();
                Result<List<Utxo>> hit = Result.success("ok");
                hit.withValue(List.of(utxo));
                hit.code(200);
                return hit;
            }
            Result<List<Utxo>> miss = Result.error("component not found");
            miss.code(missCode);
            return miss;
        }

        @Override
        public Result<List<Utxo>> getUtxos(String address, int count, int page) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Result<List<Utxo>> getUtxos(String address, int count, int page,
                                           com.bloxbean.cardano.client.api.common.OrderEnum order) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Result<List<Utxo>> getUtxos(String address, String unit, int count, int page,
                                           com.bloxbean.cardano.client.api.common.OrderEnum order) {
            return getUtxos(address, unit, count, page);
        }

        @Override
        public Result<Utxo> getTxOutput(String txHash, int outputIndex) {
            throw new UnsupportedOperationException();
        }
    }

    private static MinswapPoolResolver resolver(FakeUtxoService service) {
        return new MinswapPoolResolver(service, POOL_ADDRESS, POOL_POLICY);
    }

    // ==========================================================================================

    /**
     * ⛔ THE REGRESSION GUARD. The wrong ordering is asked FIRST and 404s; the resolver must go on to
     * ask the other one and return the pool. Before the fix this threw {@code LOOKUP_FAILED} on the
     * first call and never made the second.
     */
    @Test
    void a404OnTheFirstOrderingMustNotStopTheSecondFromBeingTried() {
        FakeUtxoService service = new FakeUtxoService(404);

        Optional<MinswapPoolResolver.ResolvedPool> pool =
                resolver(service).resolveEitherOrder(FLDT, ADA);

        assertTrue(pool.isPresent(),
                "the pool exists under the OTHER ordering and must be found; a 404 on the first "
                        + "ordering is the chain saying 'not this one', not the provider failing");
        assertEquals(2, service.asked.size(),
                "both orderings must be attempted — the whole point of resolveEitherOrder. Asked: "
                        + service.asked);
        assertEquals(LIVE_UNIT, service.asked.get(1),
                "the second attempt must be the other ordering");
    }

    /**
     * ⚠ And the direct claim underneath it, isolated so the test above cannot pass for some other
     * reason: a 404 is classified as {@code NO_POOL_FOR_PAIR}, which is the ONLY refusal
     * {@code resolveEitherOrder} treats as retryable.
     */
    @Test
    void a404IsNoPoolForPairRatherThanLookupFailed() {
        MinswapPoolResolver.RefusedException refused =
                assertThrows(MinswapPoolResolver.RefusedException.class,
                        () -> resolver(new FakeUtxoService(404)).resolve(FLDT, ADA));

        assertEquals(MinswapPoolResolver.Refusal.NO_POOL_FOR_PAIR, refused.refusal(),
                "a 404 means the provider has no UTxO with that asset at that address — an answer "
                        + "about the chain, not a statement about the provider");
    }

    /**
     * ⛔ AND THE OTHER HALF, or the fix would be "call every failure a missing pool". A provider that
     * is genuinely broken must still be {@code LOOKUP_FAILED} — and must NOT be retried under the
     * other ordering, because a second query to a broken provider proves nothing and a silent
     * {@code Optional.empty()} would read as "no pool exists".
     */
    @Test
    void a500AndA403AreStillLookupFailedAndStopTheSearch() {
        for (int code : new int[]{500, 503, 429, 403, 402}) {
            FakeUtxoService service = new FakeUtxoService(code);
            MinswapPoolResolver.RefusedException refused =
                    assertThrows(MinswapPoolResolver.RefusedException.class,
                            () -> resolver(service).resolveEitherOrder(FLDT, ADA),
                            "HTTP " + code + " must propagate, not resolve to an absent pool");
            assertEquals(MinswapPoolResolver.Refusal.LOOKUP_FAILED, refused.refusal(),
                    "HTTP " + code + " is about the provider or our credentials, never about whether "
                            + "a pool exists");
            assertEquals(1, service.asked.size(),
                    "a broken provider must not be asked twice: HTTP " + code);
        }
    }

    /**
     * ⚠ Proof of harness for the fixture itself. If the fake never served the live unit, every
     * assertion above would be about a resolver that can never succeed — and the first test would
     * pass for the wrong reason on a build where BOTH orderings 404.
     */
    @Test
    void theFakeReallyServesTheLiveOrdering() {
        FakeUtxoService service = new FakeUtxoService(404);
        MinswapPoolResolver.ResolvedPool pool = resolver(service).resolve(ADA, FLDT);
        assertEquals("aa".repeat(32), pool.utxo().getTxHash());
        assertEquals(1, service.asked.size());
        assertNotNull(pool.datum(), "the recorded mainnet datum must decode, or the fixture is a "
                + "resolver that can never succeed and every assertion above is vacuous");
    }
}
