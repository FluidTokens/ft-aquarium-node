package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * Finds the live Minswap V2 pool for one (collateral, principal) pair.
 *
 * <h2>⛔ Why this queries the provider instead of the node's own index</h2>
 * {@code TankUtxoStorage} keeps only UTxOs at the derived lending credentials, so <b>a Minswap pool
 * UTxO is discarded at write time with no trace it was ever offered</b> —
 * {@code officina:yaci-store-index-scoping}'s exact failure mode, and it would read as "no pool
 * exists" rather than "we never kept it". Indexing them instead would pull every V2 pool on the
 * network into this node's storage and need a {@code sync-start} far enough back to catch old ones.
 * <b>One query per candidate is proportionate</b>, and it is the shape the oracle registry client
 * already uses (findings §39.2).
 *
 * <h2>⚠ By NFT at run time, never a pinned coordinate</h2>
 * A pool UTxO is spent and re-created on <b>every swap</b> — measured: the coordinate this project
 * recorded in the morning was stale by the afternoon. The lookup is therefore
 * {@code /addresses/{poolAddress}/utxos/{lpAssetUnit}}, where the LP asset name is <b>computed</b>
 * (SHA3-256, twice — §34) rather than looked up, and which returns exactly one row.
 */
@Slf4j
public class MinswapPoolResolver {

    /** Why no pool could be used for this pair. Each is a fact about the chain, not a policy. */
    public enum Refusal {
        /** No UTxO at the pool address holds this pair's LP asset — no such pool exists. */
        NO_POOL_FOR_PAIR,
        /**
         * ⚠ More than one. Minswap mints one LP asset per pool, so this cannot happen for a healthy
         * deployment — and picking one arbitrarily would build against a pool nobody chose. Refused.
         */
        AMBIGUOUS_POOL,
        /** The pool UTxO carries no inline datum, or one this node cannot decode. */
        POOL_DATUM_UNREADABLE,
        /** The provider could not be reached, which is not evidence that no pool exists. */
        LOOKUP_FAILED
    }

    public static final class RefusedException extends RuntimeException {
        private final transient Refusal refusal;

        RefusedException(Refusal refusal, String detail) {
            super(refusal + ": " + detail);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /** The pool UTxO and its decoded datum, together — a caller needs both and they must agree. */
    public record ResolvedPool(Utxo utxo, MinswapPoolDatum datum, String lpAssetName) {
    }

    private final UtxoService utxoService;
    private final String poolAddress;
    private final String poolPolicyId;
    private final MinswapPoolDatumConverter converter = new MinswapPoolDatumConverter();

    public MinswapPoolResolver(UtxoService utxoService, String poolAddress, String poolPolicyId) {
        this.utxoService = utxoService;
        this.poolAddress = poolAddress;
        this.poolPolicyId = poolPolicyId;
    }

    /**
     * @param assetA the pool's own {@code asset_a}, and {@code assetB} its {@code asset_b} — the LP
     *               name is order-sensitive, so a caller that has not established the pool's ordering
     *               must try both and take the one the chain serves
     */
    public ResolvedPool resolve(AssetType assetA, AssetType assetB) {
        String lpAssetName = ConvertTxEncoder.computeLpAssetName(assetA, assetB);
        String unit = poolPolicyId + lpAssetName;

        List<Utxo> found;
        try {
            Result<List<Utxo>> result = utxoService.getUtxos(poolAddress, unit, 10, 1);
            if (!result.isSuccessful()) {
                throw refuse(Refusal.LOOKUP_FAILED, "the provider refused the pool lookup: "
                        + result.getResponse());
            }
            found = result.getValue();
        } catch (RefusedException e) {
            throw e;
        } catch (Exception e) {
            // ⚠ NOT "no pool": an unreachable provider is a statement about us, not about the chain.
            throw refuse(Refusal.LOOKUP_FAILED, "the pool lookup failed: " + e);
        }

        if (found == null || found.isEmpty()) {
            throw refuse(Refusal.NO_POOL_FOR_PAIR,
                    "no UTxO at " + poolAddress + " holds the LP asset " + unit
                            + "; there is no Minswap pool for this pair, so a convert is impossible "
                            + "rather than merely unprofitable");
        }
        if (found.size() > 1) {
            throw refuse(Refusal.AMBIGUOUS_POOL,
                    found.size() + " UTxOs hold the LP asset " + unit + "; Minswap mints one per pool, "
                            + "so choosing between them would build against a pool nobody chose");
        }

        Utxo pool = found.get(0);
        if (pool.getInlineDatum() == null || pool.getInlineDatum().isBlank()) {
            throw refuse(Refusal.POOL_DATUM_UNREADABLE,
                    "the pool UTxO " + pool.getTxHash() + "#" + pool.getOutputIndex()
                            + " carries no inline datum");
        }
        MinswapPoolDatum datum;
        try {
            datum = converter.deserialize(pool.getInlineDatum());
        } catch (RuntimeException e) {
            // The arity guard lives in the converter; a change in Minswap's type surfaces here.
            throw refuse(Refusal.POOL_DATUM_UNREADABLE, "the pool datum did not decode: " + e);
        }

        log.debug("resolved the Minswap pool for {}/{}: {}#{} (lp {})", assetA.toUnit(), assetB.toUnit(),
                pool.getTxHash(), pool.getOutputIndex(), lpAssetName);
        return new ResolvedPool(pool, datum, lpAssetName);
    }

    /**
     * The pair in whichever order the chain actually serves. ⚠ {@code compute_lp_asset_name} is
     * order-sensitive and the caller usually knows only <em>which two assets</em>, not which Minswap
     * calls {@code asset_a} — so both orders are tried and the one that exists wins. The returned
     * datum then states the ordering authoritatively.
     */
    public Optional<ResolvedPool> resolveEitherOrder(AssetType one, AssetType other) {
        try {
            return Optional.of(resolve(one, other));
        } catch (RefusedException first) {
            if (first.refusal() != Refusal.NO_POOL_FOR_PAIR) {
                throw first;
            }
        }
        try {
            return Optional.of(resolve(other, one));
        } catch (RefusedException second) {
            if (second.refusal() != Refusal.NO_POOL_FOR_PAIR) {
                throw second;
            }
            return Optional.empty();
        }
    }

    private static RefusedException refuse(Refusal refusal, String detail) {
        return new RefusedException(refusal, detail);
    }
}
