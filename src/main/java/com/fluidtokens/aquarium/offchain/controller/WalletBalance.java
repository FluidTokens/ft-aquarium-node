package com.fluidtokens.aquarium.offchain.controller;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.bloxbean.cardano.client.api.model.Utxo;

/**
 * The operator's own wallet, totalled per asset, as at a moment that is <b>stated rather than
 * implied</b>.
 *
 * <h2>⛔ Why this carries its own age</h2>
 * {@code AppUtxoService.listWalletUtxo()} reads the LOCAL Yaci index by the wallet's payment
 * credential — no provider is involved (FAB-134 B2). The readiness page re-renders every sixty seconds
 * in every open tab, so it reads at most once per TTL, a render-frequency throttle on a local read —
 * and a throttled number displayed as though it were live is still stale. {@link #ageSeconds()} is
 * rendered beside it for that reason.
 *
 * <h2>⚠ What an empty map means, and what it does not</h2>
 * A KNOWN empty {@code byUnit} is an empty INDEXED wallet: the read is made from the index once the
 * node is not syncing (FAB-136), and its completeness rests on the operator requirement in
 * {@code docs/deploying.md} §2 — the wallet holds no UTxO created before the index's sync start. Nothing
 * here proves that requirement was met. {@link #known()} false means NO answer — the node is still
 * syncing or the read failed — so the page can say so instead of rendering a confident zero over money
 * that may be there.
 */
public record WalletBalance(Map<String, BigInteger> byUnit, long asOfMillis, boolean known) {

    /** Nothing has been read yet, the read is gated closed, or it failed — NOT a zero balance. */
    public static WalletBalance unknown() {
        return new WalletBalance(Map.of(), 0L, false);
    }

    public static WalletBalance of(List<Utxo> utxos, long nowMillis) {
        if (utxos == null) {
            return unknown();
        }
        Map<String, BigInteger> totals = new LinkedHashMap<>();
        for (Utxo utxo : utxos) {
            if (utxo.getAmount() == null) {
                continue;
            }
            utxo.getAmount().forEach(amount -> totals.merge(amount.getUnit(),
                    amount.getQuantity() == null ? BigInteger.ZERO : amount.getQuantity(),
                    BigInteger::add));
        }
        return new WalletBalance(totals, nowMillis, true);
    }

    /**
     * ⛔ ZERO, never null, and that is safe HERE precisely because {@link #known()} guards it. A
     * caller asking "can I afford this" on an unknown balance gets zero and refuses, which is the
     * direction that cannot spend money it does not have.
     */
    public BigInteger of(String unit) {
        return byUnit.getOrDefault(unit, BigInteger.ZERO);
    }

    public long ageSeconds(long nowMillis) {
        return known ? Math.max(0L, (nowMillis - asOfMillis) / 1000L) : 0L;
    }

    /** ada first, then the rest by unit, so the figure an operator looks for first is first. */
    public List<Map.Entry<String, BigInteger>> ordered() {
        return byUnit.entrySet().stream()
                .sorted((a, b) -> {
                    if (a.getKey().equals("lovelace")) return -1;
                    if (b.getKey().equals("lovelace")) return 1;
                    return a.getKey().compareTo(b.getKey());
                })
                .toList();
    }
}
