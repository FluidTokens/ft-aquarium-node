package com.fluidtokens.aquarium.offchain.storage;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.util.UtxoUtil;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The node's {@link UtxoSupplier}: the local Yaci index first, Blockfrost only for out-refs the index
 * cannot hold (FAB-134 B3a) — and, for the STATIC ones among those, only once per process (B5b).
 *
 * <h2>What is served from where</h2>
 * <ul>
 *   <li><b>UTxOs AT AN ADDRESS</b> ({@link #getPage}, and CCL's default {@code getAll} on top of it) —
 *       <b>from the index only, never from the provider.</b> The index is the system of record for the
 *       payment credentials it watches ({@link TankUtxoStorage#indexedPaymentCredentials()}, the single
 *       source of that set). An empty answer is the answer: a provider fallback that fires only on
 *       EMPTY would make a PARTIAL index silently authoritative ({@code officina:yaci-store-index-scoping}
 *       §5). An address whose credential the index does NOT watch is REFUSED with an
 *       {@link IllegalStateException}: its rows were discarded at write time (§2), so "empty" would be
 *       a lie indistinguishable from an empty wallet.</li>
 *   <li><b>ONE OUTPUT BY OUT-REF</b> ({@link #getTxOutput}) — from one of THREE sources, in this order
 *       (FAB-134 B5b):
 *       <ol>
 *         <li><b>INDEX-backed</b> — the index holds the row and its reference-script hash is faithful. The
 *             index always wins, even over a held answer, and a hit never fills the hold. Every UTxO at a
 *             watched credential lands here: the bot's wallet and collateral, loans, bonds, pools, the
 *             config and parameters/staker reference inputs.</li>
 *         <li><b>HOLD-backed</b> — a miss whose out-ref is in the CURRENT static set
 *             ({@code StaticReferenceInputs#current()}, recomputed on each miss): the FluidTokens-published
 *             reference scripts ({@code loans.liquidation.reference-scripts.*},
 *             {@code loans.compound.reference-scripts}), the tank reference input, and each oracle registry
 *             entry's feed, script and Charli3 provider UTxOs. Read from the provider on the first request
 *             and held by out-ref after that; an ABSENT answer is never held; an out-ref that leaves the
 *             static set is dropped on the next miss, so the hold is bounded by the set. Call sites:
 *             cardano-client-lib's {@code ReferenceScriptResolver} and {@code FeeCalculators} (every
 *             balancing pass) in the liquidation, pay-in-advance, convert and tank builds;
 *             {@code CompoundExecutor.referenceScripts()} per compound candidate.</li>
 *         <li><b>PROVIDER-backed</b> — any other miss: one direct provider read, not held.</li>
 *       </ol></li>
 * </ul>
 *
 * <h2>Why the hold is content-safe, and what it does NOT say</h2>
 * An output's content at a given out-ref never changes: Blockfrost answers the same bytes for it forever,
 * even after the output is spent ({@code officina:ccl-transaction-building-traps} §12). So a held answer
 * cannot be WRONG CONTENT; the only thing that can go stale is LIVENESS — and liveness is the ledger's to
 * judge: a spent reference input fails at phase 1 ({@code BadInputsUTxO}) and costs no collateral. The
 * hold is therefore <b>never evidence that a coordinate is still live</b> (§12 again: "{@code getTxOutput}
 * is not an existence check"). Re-resolving a static coordinate means re-reading its SOURCE — the oracle
 * registry's refresh, the operator's config and the boot-time verifier — never re-reading the same out-ref.
 * Coin selection never sees a held UTxO: {@link #getPage} (and {@code getAll} on top of it) reads the
 * index only (§9b: a reference-script UTxO in coin selection gets spent).
 *
 * <h2>Why the reference-script hash gates a hit</h2>
 * CCL learns a reference input's script from {@code getTxOutput}'s {@code referenceScriptHash} and
 * prices the reference-script fee from it ({@code officina:ccl-transaction-building-traps} §9, §13).
 * Rows are mapped with {@link UtxoUtil#toUtxo(com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity)},
 * which recovers the hash from {@code scriptRef} — but for an undecodable {@code scriptRef} it returns a
 * non-hash sentinel meaning "a script is here, hash unknown". That value must never reach CCL, so any
 * row whose hash is present but not hash-shaped is answered by the provider instead.
 *
 * <h2>Spent outputs and failures</h2>
 * {@code getTxOutput} may return a row the index has since marked spent: the interface documents that
 * it "doesn't check if the output is spent", and Blockfrost's answer has the same property. A database
 * failure PROPAGATES from both methods — it never becomes an empty answer or a provider call. Note that
 * moving these reads from Blockfrost to the database changes the exception type crossing each caller's
 * error boundary ({@code ccl-transaction-building-traps} §19).
 */
public class IndexFirstUtxoSupplier implements UtxoSupplier {

    /** A reference-script hash is a 28-byte blake2b-224 digest; anything else is not one. */
    private static final Pattern SCRIPT_HASH = Pattern.compile("[0-9a-fA-F]{56}");

    private static final Comparator<Utxo> BY_OUT_REF =
            Comparator.comparing(Utxo::getTxHash).thenComparingInt(Utxo::getOutputIndex);

    private final UtxoRepository utxoRepository;

    private final Supplier<Set<String>> watchedPaymentCredentials;

    private final UtxoSupplier provider;

    /** The CURRENT static reference inputs, recomputed from their sources on each miss (never copied here). */
    private final Supplier<Set<TransactionInput>> staticReferenceInputs;

    /**
     * Provider answers for static reference inputs, by out-ref ({@code txHash#index}). Holds only present
     * answers, only for out-refs in the current static set, and is pruned to that set on every miss — so
     * it is bounded by the set. Read by {@link #getTxOutput} only; {@link #getPage} never consults it.
     */
    private final Map<String, Utxo> held = new ConcurrentHashMap<>();

    public IndexFirstUtxoSupplier(UtxoRepository utxoRepository,
                                  Supplier<Set<String>> watchedPaymentCredentials,
                                  UtxoSupplier provider,
                                  Supplier<Set<TransactionInput>> staticReferenceInputs) {
        this.utxoRepository = Objects.requireNonNull(utxoRepository, "utxoRepository");
        this.watchedPaymentCredentials = Objects.requireNonNull(watchedPaymentCredentials, "watchedPaymentCredentials");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.staticReferenceInputs = Objects.requireNonNull(staticReferenceInputs, "staticReferenceInputs");
    }

    @Override
    public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
        requireWatched(address);
        int size = nrOfItems == null ? DEFAULT_NR_OF_ITEMS_TO_FETCH : nrOfItems;
        int pageNo = page == null ? 0 : page;

        Comparator<Utxo> comparator = order == OrderEnum.desc ? BY_OUT_REF.reversed() : BY_OUT_REF;
        List<Utxo> all = utxoRepository.findUnspentByOwnerAddr(address, Pageable.unpaged())
                .stream()
                .flatMap(Collection::stream)
                .map(UtxoUtil::toUtxo)
                .sorted(comparator)
                .toList();

        long from = (long) pageNo * size;
        if (size <= 0 || pageNo < 0 || from >= all.size()) {
            // Past the end: EMPTY, which is what terminates CCL's default getAll.
            return List.of();
        }
        return all.subList((int) from, (int) Math.min(from + size, all.size()));
    }

    @Override
    public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
        Optional<Utxo> indexed = utxoRepository.findById(new UtxoId(txHash, outputIndex)).map(UtxoUtil::toUtxo);
        if (indexed.isPresent() && hasFaithfulReferenceScriptHash(indexed.get())) {
            // The index always wins, and a hit never fills the hold.
            return indexed;
        }
        // A miss (not indexed, or indexed with a hash we could not derive). Prune the hold to the CURRENT
        // static set first: an out-ref that left it — a feed the registry moved — is dropped here.
        Set<String> current = outRefs(staticReferenceInputs.get());
        held.keySet().retainAll(current);

        String outRef = outRef(txHash, outputIndex);
        if (!current.contains(outRef)) {
            // Not a static reference input: one direct provider read, not held.
            return provider.getTxOutput(txHash, outputIndex);
        }
        Utxo heldAnswer = held.get(outRef);
        if (heldAnswer != null) {
            return Optional.of(heldAnswer);
        }
        Optional<Utxo> answer = provider.getTxOutput(txHash, outputIndex);
        // ⛔ An absent answer is never held: the next call asks again.
        answer.ifPresent(utxo -> held.put(outRef, utxo));
        return answer;
    }

    private static Set<String> outRefs(Set<TransactionInput> inputs) {
        Set<String> outRefs = new HashSet<>();
        if (inputs != null) {
            for (TransactionInput input : inputs) {
                if (input != null && input.getTransactionId() != null) {
                    outRefs.add(outRef(input.getTransactionId(), input.getIndex()));
                }
            }
        }
        return outRefs;
    }

    private static String outRef(String txHash, int outputIndex) {
        return txHash.toLowerCase(Locale.ROOT) + "#" + outputIndex;
    }

    private static boolean hasFaithfulReferenceScriptHash(Utxo utxo) {
        String hash = utxo.getReferenceScriptHash();
        return hash == null || SCRIPT_HASH.matcher(hash).matches();
    }

    private void requireWatched(String address) {
        String credential;
        try {
            credential = new Address(address).getPaymentCredentialHash()
                    .map(HexUtil::encodeHexString)
                    .orElse(null);
        } catch (RuntimeException e) {
            throw new IllegalStateException("The local index cannot answer for address " + address
                    + ": its payment credential cannot be derived", e);
        }
        if (credential == null || !watchedPaymentCredentials.get().contains(credential)) {
            throw new IllegalStateException("The local index does not watch address " + address
                    + " (payment credential " + credential + "): its outputs were never stored, so an "
                    + "answer would read as an empty wallet. Refusing rather than answering empty.");
        }
    }
}
