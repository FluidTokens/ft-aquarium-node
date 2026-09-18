package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
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
                // ⛔ A 404 IS AN ANSWER, NOT AN OUTAGE — and reading it as one broke the whole
                // either-order mechanism below. Measured on mainnet 2026-09-05: the ADA/FLDT pool
                // exists and is healthy (1.69M ada / 7.6M FLDT, one UTxO, inline datum), and this
                // method reported LOOKUP_FAILED for it.
                //
                // `compute_lp_asset_name` is ORDER-SENSITIVE, so exactly one of the two orderings
                // names a real asset:
                //     LP(ada, fldt)  bc53f5c2…  -> HTTP 200
                //     LP(fldt, ada)  df40ef9f…  -> HTTP 404
                // Blockfrost expresses "no UTxO with that asset at this address" as a 404, not as an
                // empty list — so the WRONG ordering landed here rather than in the `found.isEmpty()`
                // branch below, and `resolveEitherOrder` RETHROWS anything that is not
                // NO_POOL_FOR_PAIR. The correct ordering was therefore never tried.
                //
                // ⚠ Two consequences, and the second is why this is not a mislabel but a defect:
                //   1. the `found.isEmpty()` -> NO_POOL_FOR_PAIR branch is UNREACHABLE via this
                //      provider, so it has never fired in production;
                //   2. the either-order fallback fails whenever the wrong ordering happens to be
                //      tried first — a coin flip per pair — and presents as "no pool exists".
                //
                // The rule is not new to this repo: LoansConfigVerifier.fetchConfigDatumHex already
                // says "a 4xx is an answer, not an outage". This class is the sibling that never
                // applied it. 404 alone is the "no such asset here" answer; every other 4xx is about
                // US (a bad key, a quota), and 5xx / 429 / transport are genuinely transient.
                int code = result.code();
                if (code == 404) {
                    throw refuse(Refusal.NO_POOL_FOR_PAIR,
                            "the provider has no UTxO at " + poolAddress + " holding the LP asset "
                                    + unit + " (HTTP 404). If this is one of two orderings, the other "
                                    + "is the one to try; if both 404, there is no Minswap pool for "
                                    + "this pair and a convert is impossible rather than unprofitable");
                }
                throw refuse(Refusal.LOOKUP_FAILED, "the provider refused the pool lookup (HTTP "
                        + code + "): " + result.getResponse());
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
        // ⛔ MORE THAN ONE POOL PER PAIR IS NORMAL, AND THIS USED TO REFUSE IT.
        //
        // The old code threw AMBIGUOUS_POOL on found.size() > 1, reasoning that "Minswap mints one
        // per pool, so choosing between them would build against a pool nobody chose". The premise
        // is false: the LP asset name is derived from the PAIR, so every pool for the same pair
        // carries the same LP asset and two of them are simply two pools. Confirmed on mainnet
        // 2026-09-18 for ada/ASCEND -- one tiny, one deep -- and the refusal made every ada/ASCEND
        // loan read CHECK FAILED forever, which is indistinguishable from an outage.
        //
        // ⚠ The original concern was right even though the rule was wrong: picking arbitrarily WOULD
        // build against a pool nobody chose. So the choice is made on the only property that matters
        // for filling a swap -- DEPTH -- and it is stated in the log rather than left implicit.
        List<Candidate> candidates = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (Utxo utxo : found) {
            if (utxo.getInlineDatum() == null || utxo.getInlineDatum().isBlank()) {
                rejected.add(utxo.getTxHash() + "#" + utxo.getOutputIndex() + " carries no inline datum");
                continue;
            }
            MinswapPoolDatum candidateDatum;
            try {
                candidateDatum = converter.deserialize(utxo.getInlineDatum());
            } catch (RuntimeException e) {
                rejected.add(utxo.getTxHash() + "#" + utxo.getOutputIndex() + " datum did not decode: " + e);
                continue;
            }
            // ⛔ The datum states the pair authoritatively. A UTxO carrying this LP asset whose datum
            // is NOT this pair cannot fill this swap, whatever its depth, so it is never a candidate.
            if (!pairMatches(candidateDatum, assetA, assetB)) {
                rejected.add(utxo.getTxHash() + "#" + utxo.getOutputIndex() + " is "
                        + candidateDatum.assetA().toUnit() + "/" + candidateDatum.assetB().toUnit());
                continue;
            }
            candidates.add(new Candidate(utxo, candidateDatum));
        }

        if (candidates.isEmpty()) {
            throw refuse(Refusal.POOL_DATUM_UNREADABLE,
                    "every UTxO holding the LP asset " + unit + " was unusable: " + rejected);
        }

        // Constant-product depth. Direction-agnostic and derived from the reserves themselves rather
        // than the pool's own LP accounting, so it compares pools that price differently.
        candidates.sort(Comparator.comparing(Candidate::depth).reversed());
        Candidate best = candidates.getFirst();

        if (candidates.size() > 1 || !rejected.isEmpty()) {
            log.info("{} pools carry the LP asset {}; chose the deepest, {}#{} (reserves {}/{}). "
                            + "Others: {}{}",
                    candidates.size(), unit, best.utxo().getTxHash(), best.utxo().getOutputIndex(),
                    best.datum().reserveA(), best.datum().reserveB(),
                    candidates.stream().skip(1)
                            .map(c -> c.utxo().getTxHash() + "#" + c.utxo().getOutputIndex()
                                    + " (" + c.datum().reserveA() + "/" + c.datum().reserveB() + ")")
                            .toList(),
                    rejected.isEmpty() ? "" : "; not candidates: " + rejected);
        }

        Utxo pool = best.utxo();
        MinswapPoolDatum datum = best.datum();

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

    /** One UTxO that carries the pair's LP asset, with its decoded datum. */
    private record Candidate(Utxo utxo, MinswapPoolDatum datum) {
        /** Constant-product depth: what actually limits how much a swap can take out. */
        BigInteger depth() {
            return datum.reserveA().multiply(datum.reserveB());
        }
    }

    /** Whether a pool's declared pair is the pair asked for, in either order. */
    private static boolean pairMatches(MinswapPoolDatum datum, AssetType one, AssetType other) {
        String a = datum.assetA().toUnit();
        String b = datum.assetB().toUnit();
        String x = one.toUnit();
        String y = other.toUnit();
        return (a.equals(x) && b.equals(y)) || (a.equals(y) && b.equals(x));
    }

    private static RefusedException refuse(Refusal refusal, String detail) {
        return new RefusedException(refusal, detail);
    }
}
