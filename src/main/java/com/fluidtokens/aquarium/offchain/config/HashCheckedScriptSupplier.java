package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link ScriptSupplier} the transaction builders price reference scripts with: a provider is
 * asked for a script's bytes ONCE per hash, the bytes are checked to hash to what was asked for, and
 * from then on the script is served from memory (FAB-134 B3b).
 *
 * <h2>Why a by-hash memo is not a UTxO cache</h2>
 * A script hash is the hash OF the script's bytes, so the bytes are a pure function of the key: there
 * is no later state of the chain in which the same hash names different bytes, and nothing here can go
 * stale. That is the opposite of a UTxO, which can be spent between two reads — this class holds no
 * UTxO, no address and no out-ref, and answers nothing about WHERE a script is published or whether
 * that output still exists. Those questions still go to the {@code UtxoSupplier} on every build.
 * The hash check is what makes the memo sound: the only way it could serve the wrong bytes is if the
 * provider handed us bytes that are not the script we asked for, and those are refused here, before
 * they are remembered.
 *
 * <h2>Fails closed — never empty for a real hash</h2>
 * cardano-client-lib prices the Conway reference-script fee only from bytes it can obtain; a supplier
 * that answers {@code Optional.empty()} makes it charge that fee as ZERO without an error, and the
 * transaction is rejected at phase 1 with {@code FeeTooSmallUTxO} (CCL trap 9). So an empty provider
 * answer for a non-blank hash is an {@link IllegalStateException} here, which the builder's
 * {@code complete()} turns into its named refusal. Only a null or blank hash — which is how
 * cardano-client-lib asks about a reference input that carries no script at all — answers empty, and
 * it never reaches the provider.
 */
public class HashCheckedScriptSupplier implements ScriptSupplier {

    private final ScriptSupplier provider;
    private final Map<String, PlutusScript> verified = new ConcurrentHashMap<>();

    public HashCheckedScriptSupplier(ScriptSupplier provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public Optional<PlutusScript> getScript(String scriptHash) {
        if (scriptHash == null || scriptHash.isBlank()) {
            return Optional.empty();
        }
        String key = scriptHash.toLowerCase();
        PlutusScript memo = verified.get(key);
        if (memo != null) {
            return Optional.of(memo);
        }
        // A provider exception propagates untouched and nothing is remembered: the next build asks again.
        Optional<PlutusScript> answer = provider.getScript(scriptHash);
        if (answer == null || answer.isEmpty()) {
            throw new IllegalStateException("the script provider has no script for hash " + scriptHash
                    + "; refusing to answer empty, because cardano-client-lib would then charge that "
                    + "reference script's fee as zero and the transaction would be rejected at phase 1 "
                    + "(FeeTooSmallUTxO, CCL trap 9)");
        }
        PlutusScript script = answer.get();
        String actual = hashOf(script);
        if (!key.equals(actual)) {
            throw new IllegalStateException("the script provider was asked for hash " + scriptHash
                    + " and returned a script whose hash is " + actual + "; not served, not remembered");
        }
        verified.put(key, script);
        return Optional.of(script);
    }

    private static String hashOf(PlutusScript script) {
        try {
            return HexUtil.encodeHexString(script.getScriptHash()).toLowerCase();
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash the script the provider returned", e);
        }
    }
}
