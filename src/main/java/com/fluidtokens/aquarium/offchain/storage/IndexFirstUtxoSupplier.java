package com.fluidtokens.aquarium.offchain.storage;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.util.UtxoUtil;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The node's {@link UtxoSupplier}: the local Yaci index first, Blockfrost only for out-refs the index
 * cannot hold (FAB-134 B3a).
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
 *   <li><b>ONE OUTPUT BY OUT-REF</b> ({@link #getTxOutput}) — from the index when it holds the row and
 *       the row's reference-script hash is faithful; otherwise from the provider. The fallback exists
 *       for reference inputs this node does not index (oracle feeds, oracle script, Charli3 provider
 *       UTxOs, FluidTokens-published reference scripts, the tank reference script). It is NOT a cache:
 *       every miss is one direct provider read, and nothing is memoised.</li>
 * </ul>
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

    public IndexFirstUtxoSupplier(UtxoRepository utxoRepository,
                                  Supplier<Set<String>> watchedPaymentCredentials,
                                  UtxoSupplier provider) {
        this.utxoRepository = Objects.requireNonNull(utxoRepository, "utxoRepository");
        this.watchedPaymentCredentials = Objects.requireNonNull(watchedPaymentCredentials, "watchedPaymentCredentials");
        this.provider = Objects.requireNonNull(provider, "provider");
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
            return indexed;
        }
        // Not indexed (a reference input this node does not watch), or indexed with a hash we could not
        // derive: one direct provider read, not memoised.
        return provider.getTxOutput(txHash, outputIndex);
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
