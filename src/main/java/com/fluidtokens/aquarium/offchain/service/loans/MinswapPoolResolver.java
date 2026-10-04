package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import com.fluidtokens.aquarium.offchain.util.UtxoUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Finds the live Minswap V2 pool for one (collateral, principal) pair — from the node's OWN INDEX.
 *
 * <h2>One query per liquidation cycle</h2>
 * {@link #snapshot()} issues exactly one {@code findUnspentByOwnerPaymentCredential(poolSpendScriptHash,
 * unpaged)} — the LEFT JOIN on {@code tx_input}, because {@code address_utxo} alone still holds every
 * pool UTxO a swap has since spent ({@code officina:yaci-store-index-scoping} §4b). The caller takes
 * one snapshot per cycle and asks it about every candidate; no candidate ever triggers a query of its
 * own. The pool credential is indexed like the wallet's (FAB-137 T2a), so pools that have been idle
 * since before the index's start point are not in it.
 *
 * <h2>⛔ Authentication is the filter, not the query</h2>
 * The pool payment credential is a <b>public script address</b>: anyone can pay anything there, with
 * any datum, any LP token and any stake part, and a credential-scoped scan returns all of it
 * (§2b). A row is an authentic pool only when ALL of these hold:
 * <ol>
 *   <li>it sits at the pool payment credential — under ANY stake credential;</li>
 *   <li>it holds exactly one MSP ({@code <pool-policy-id>4d5350}, quantity 1) — the one asset only the
 *       pool validator can move, so it is the authentication (CCL §12: assert on content that moves).
 *       An LP token or a datum authenticates nothing: anyone can pay either to the address;</li>
 *   <li>its inline datum decodes and states a pair;</li>
 *   <li>the LP name recomputed from that pair ({@link ConvertTxEncoder#computeLpAssetName}, SHA3-256
 *       twice, order-sensitive) is an LP unit the UTxO actually holds.</li>
 * </ol>
 * An authentic pool is filed under that recomputed LP unit and no other, so the pair check IS the key:
 * a pool for another pair that merely <em>holds</em> this pair's LP asset (the real mainnet ada /
 * LP-token pool, 2026-09-18) is filed under its own pair and is never an answer for this one. Every
 * other row is ignored.
 *
 * <h2>⛔ Two authentic pools for one pair is an index-integrity error</h2>
 * Minswap V2 mints one MSP per pool and one LP asset per pair, so two authentic pools for a pair
 * cannot both be live on chain. The index saying otherwise means the index is wrong, and no pool is
 * chosen between them: {@link Refusal#AMBIGUOUS_POOL}, naming both out-refs.
 */
@Slf4j
public class MinswapPoolResolver {

    /** Why no pool could be used for this pair. */
    public enum Refusal {
        /**
         * ⛔ Index integrity: more than one authentic pool for one pair. Minswap cannot produce that on
         * chain, so the index is wrong — and picking one would build against a pool nobody chose.
         */
        AMBIGUOUS_POOL,
        /** The index could not be read, which is not evidence that no pool exists. */
        LOOKUP_FAILED
    }

    public static final class RefusedException extends RuntimeException {
        private final transient Refusal refusal;

        RefusedException(Refusal refusal, String detail) {
            super(refusal + ": " + detail);
            this.refusal = refusal;
        }

        RefusedException(Refusal refusal, String detail, Throwable cause) {
            super(refusal + ": " + detail, cause);
            this.refusal = refusal;
        }

        public Refusal refusal() {
            return refusal;
        }
    }

    /** The pool UTxO and its decoded datum, together — a caller needs both and they must agree. */
    public record ResolvedPool(Utxo utxo, MinswapPoolDatum datum, String lpAssetName) {
    }

    private final UtxoRepository utxoRepository;
    private final String poolSpendScriptHash;
    private final String poolPolicyId;
    private final MinswapPoolDatumConverter converter = new MinswapPoolDatumConverter();

    public MinswapPoolResolver(UtxoRepository utxoRepository, String poolSpendScriptHash, String poolPolicyId) {
        this.utxoRepository = utxoRepository;
        this.poolSpendScriptHash = poolSpendScriptHash;
        this.poolPolicyId = poolPolicyId;
    }

    /**
     * Every authentic pool in the index, read with ONE unpaged credential query.
     *
     * @throws RefusedException {@link Refusal#LOOKUP_FAILED} when the index cannot be read — never an
     *                          empty snapshot, which would read as "no pool exists"
     */
    public Snapshot snapshot() {
        List<AddressUtxoEntity> rows;
        try {
            rows = utxoRepository
                    .findUnspentByOwnerPaymentCredential(poolSpendScriptHash, Pageable.unpaged())
                    .stream()
                    .flatMap(Collection::stream)
                    .toList();
        } catch (RuntimeException e) {
            // ⚠ NOT "no pool": a database that cannot answer is a statement about us, not about the chain.
            throw new RefusedException(Refusal.LOOKUP_FAILED, "the pool index query failed: " + e, e);
        }

        String msp = poolPolicyId + ConvertTxEncoder.POOL_NFT_ASSET_NAME;
        Map<String, List<ResolvedPool>> byLpUnit = new HashMap<>();
        int rejected = 0;
        for (AddressUtxoEntity row : rows) {
            Optional<ResolvedPool> pool = authenticate(row, msp);
            if (pool.isEmpty()) {
                rejected++;
                continue;
            }
            byLpUnit.computeIfAbsent(poolPolicyId + pool.get().lpAssetName(), k -> new ArrayList<>())
                    .add(pool.get());
        }
        if (rejected > 0) {
            log.debug("Minswap pool snapshot: {} row(s) at {} are not authentic pools and were ignored; "
                    + "{} authentic", rejected, poolSpendScriptHash, rows.size() - rejected);
        }
        return new Snapshot(byLpUnit, poolPolicyId);
    }

    /**
     * ⛔ Scaffolding for the readiness page only; removed by FAB-135-T2c. One snapshot per call.
     */
    public List<ResolvedPool> resolveAllEitherOrder(AssetType one, AssetType other) {
        return snapshot().resolveEitherOrder(one, other).map(List::of).orElse(List.of());
    }

    /** The authentic pool this row is, if it is one; empty for anything else at the address. */
    private Optional<ResolvedPool> authenticate(AddressUtxoEntity row, String msp) {
        Utxo utxo;
        try {
            utxo = UtxoUtil.toUtxo(row);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        List<Amount> amounts = utxo.getAmount() == null ? List.of() : utxo.getAmount();

        List<Amount> msps = amounts.stream().filter(a -> msp.equals(a.getUnit())).toList();
        if (msps.size() != 1 || !BigInteger.ONE.equals(msps.getFirst().getQuantity())) {
            return Optional.empty();
        }

        String datumHex = utxo.getInlineDatum();
        if (datumHex == null || datumHex.isBlank()) {
            return Optional.empty();
        }
        MinswapPoolDatum datum;
        try {
            datum = converter.deserialize(datumHex);
        } catch (RuntimeException e) {
            return Optional.empty();
        }

        String lpAssetName = ConvertTxEncoder.computeLpAssetName(datum.assetA(), datum.assetB());
        String lpUnit = poolPolicyId + lpAssetName;
        boolean holdsOwnLp = amounts.stream().anyMatch(a -> lpUnit.equals(a.getUnit())
                && a.getQuantity() != null && a.getQuantity().signum() > 0);
        if (!holdsOwnLp) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedPool(utxo, datum, lpAssetName));
    }

    /** The authentic pools of one index read, keyed by LP unit. Asking it issues no query. */
    public static class Snapshot {
        private final Map<String, List<ResolvedPool>> byLpUnit;
        private final String poolPolicyId;

        Snapshot(Map<String, List<ResolvedPool>> byLpUnit, String poolPolicyId) {
            this.byLpUnit = byLpUnit;
            this.poolPolicyId = poolPolicyId;
        }

        /**
         * The pool for the pair in whichever order Minswap calls {@code asset_a}: both LP names are
         * tried, and the returned datum states the ordering authoritatively.
         *
         * @return empty when no authentic pool for the pair is indexed
         * @throws RefusedException {@link Refusal#AMBIGUOUS_POOL} when more than one is
         */
        public Optional<ResolvedPool> resolveEitherOrder(AssetType one, AssetType other) {
            Set<String> units = new LinkedHashSet<>(List.of(
                    poolPolicyId + ConvertTxEncoder.computeLpAssetName(one, other),
                    poolPolicyId + ConvertTxEncoder.computeLpAssetName(other, one)));
            List<ResolvedPool> found = new ArrayList<>();
            for (String unit : units) {
                found.addAll(byLpUnit.getOrDefault(unit, List.of()));
            }
            if (found.size() > 1) {
                throw new RefusedException(Refusal.AMBIGUOUS_POOL, "index integrity: " + found.size()
                        + " authentic Minswap pools for " + one.toUnit() + "/" + other.toUnit() + " ("
                        + String.join(", ", found.stream()
                        .map(p -> p.utxo().getTxHash() + "#" + p.utxo().getOutputIndex()).toList())
                        + "); Minswap cannot produce two live pools for one pair, so the index is wrong "
                        + "and no pool is chosen between them");
            }
            return found.stream().findFirst();
        }
    }
}
