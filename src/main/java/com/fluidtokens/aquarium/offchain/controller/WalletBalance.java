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
 * {@code AppUtxoService.listWalletUtxo()} reads the PROVIDER first and falls back to the local index,
 * deliberately: an index-backed balance cannot distinguish "the wallet is empty" from "the wallet's
 * history begins below our sync point", and a silently partial balance poisons every affordability
 * decision downstream at once.
 *
 * <p>That makes it a network call, and the readiness page re-renders every sixty seconds in every
 * open tab. Reading it per render would multiply provider traffic by the number of people looking.
 * So it is <b>cached</b> — and a cached number displayed as though it were live is the failure that
 * replaces the one being avoided. {@link #ageSeconds()} is rendered beside it for that reason.
 *
 * <h2>⚠ What an empty map means, and what it does not</h2>
 * An empty {@code byUnit} means the read RETURNED NOTHING, which on this path can be a genuinely
 * empty wallet <b>or</b> a provider that could not be reached — {@code listWalletUtxo} logs and
 * returns an empty list rather than throwing. {@link #known()} separates the two, so the page can say
 * "unknown" instead of rendering a confident zero over money that may be there.
 */
public record WalletBalance(Map<String, BigInteger> byUnit, long asOfMillis, boolean known) {

    /** Nothing has been read yet, or the read failed — NOT a zero balance. */
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
