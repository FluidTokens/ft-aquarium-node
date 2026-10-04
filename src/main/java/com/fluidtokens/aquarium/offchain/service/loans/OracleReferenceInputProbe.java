package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ⛔ <b>Is every oracle out-ref a build is about to reference still LIVE?</b> (FAB-138.) Asked on Blockfrost
 * inside the production builder's {@code build()}, before any script-cost evaluation.
 *
 * <h2>Why before evaluation</h2>
 * A spent oracle feed does not fail as "the feed is spent". It reaches Blockfrost's
 * {@code /utils/txs/evaluate} and comes back as an empty {@code ScriptFailures}
 * ({@code ccl-transaction-building-traps} §13), which {@code LiquidationExecutor} reads as a spent WALLET
 * input and answers by dropping the nominated wallet utxo. The operator sees the wrong cause and loses a
 * good utxo for the cycle. So the probe runs first, and its refusal NEVER carries the {@code BadInputs} or
 * {@code ScriptFailures} markers the executor matches on.
 *
 * <h2>What "live" means</h2>
 * Blockfrost's {@code getTxOutput} answers a spent output forever (§12): it is not an existence check, and
 * this class never calls it. Liveness is "the out-ref is in the LIVE UTxO set at its address, holding the
 * NFT", asked as ONE {@code getUtxos(address, nftUnit)} per distinct out-ref:
 * <ul>
 *   <li>the feed ({@link OracleEntry#referenceInput()}) must hold {@link OracleEntry#oracleToken()};</li>
 *   <li>the Charli3 provider ({@link OracleEntry#charlieProviderReferenceInput()}), when there is one, must
 *       hold {@link OracleEntry#charlieProviderNft()}.</li>
 * </ul>
 * The out-ref's ADDRESS comes from {@code contentSource} — the content hold (FAB-134 7b), which may answer
 * from memory because an output's content never changes. It says nothing about liveness; that is this
 * class's whole job.
 *
 * <h2>What refuses</h2>
 * A spent out-ref, an NFT the registry does not name, two entries expecting different NFTs at one out-ref,
 * an out-ref whose content is unknown, and any provider failure. Every one throws
 * {@link OracleReferenceInputNotLiveException}; nothing is held, so the candidate is probed again next cycle
 * (FAB-134 NQ: no quarantine).
 *
 * <p>Holds a {@link UtxoService} — read-only, with no submission method — and no {@code BackendService}, so a
 * builder that holds this probe still has no path to the wire.
 */
public class OracleReferenceInputProbe {

    /** Blockfrost's largest page; a feed or provider NFT is one token, so one page always suffices. */
    private static final int PAGE_SIZE = 100;

    private final UtxoSupplier contentSource;

    private final UtxoService blockfrost;

    public OracleReferenceInputProbe(UtxoSupplier contentSource, UtxoService blockfrost) {
        this.contentSource = Objects.requireNonNull(contentSource, "contentSource");
        this.blockfrost = Objects.requireNonNull(blockfrost, "blockfrost");
    }

    /**
     * Refuses unless every oracle out-ref {@code referenced} names is live and holds its expected NFT.
     * Exactly one {@code getUtxos} per distinct out-ref per call; none at all when a refusal can be decided
     * without the network.
     *
     * @throws OracleReferenceInputNotLiveException on the first out-ref that is not provably live
     */
    public void requireLive(Collection<OracleEntry> referenced) {
        Map<TransactionInput, AssetType> expected = new LinkedHashMap<>();
        for (OracleEntry entry : referenced) {
            expect(expected, entry.referenceInput(), entry.oracleToken(), "oracle feed");
            if (entry.charlieProviderReferenceInput() != null) {
                expect(expected, entry.charlieProviderReferenceInput(), entry.charlieProviderNft(),
                        "Charli3 provider");
            }
        }
        Map<TransactionInput, String> addresses = new LinkedHashMap<>();
        expected.forEach((outRef, nft) -> addresses.put(outRef, addressOf(outRef, nft)));
        expected.forEach((outRef, nft) -> requireLive(outRef, nft, addresses.get(outRef)));
    }

    private static void expect(Map<TransactionInput, AssetType> expected, TransactionInput outRef, AssetType nft,
                               String what) {
        if (outRef == null) {
            throw new OracleReferenceInputNotLiveException(
                    "the %s names no out-ref, so its liveness cannot be checked".formatted(what));
        }
        if (nft == null) {
            throw new OracleReferenceInputNotLiveException(
                    "%s %s: the registry names no NFT for it, so its liveness cannot be checked"
                            .formatted(what, ref(outRef)));
        }
        AssetType previous = expected.putIfAbsent(outRef, nft);
        if (previous != null && !previous.equals(nft)) {
            throw new OracleReferenceInputNotLiveException(
                    "%s %s: two oracle entries expect different NFTs there (%s and %s)"
                            .formatted(what, ref(outRef), previous.toUnit(), nft.toUnit()));
        }
    }

    private String addressOf(TransactionInput outRef, AssetType nft) {
        Optional<Utxo> content = contentSource.getTxOutput(outRef.getTransactionId(), outRef.getIndex());
        if (content.isEmpty() || content.get().getAddress() == null) {
            throw new OracleReferenceInputNotLiveException(
                    "oracle out-ref %s (expected NFT %s): its address is unknown — no content for it"
                            .formatted(ref(outRef), nft.toUnit()));
        }
        return content.get().getAddress();
    }

    private void requireLive(TransactionInput outRef, AssetType nft, String address) {
        String unit = nft.toUnit();
        Result<List<Utxo>> answer;
        try {
            answer = blockfrost.getUtxos(address, unit, PAGE_SIZE, 1);
        } catch (Exception e) {
            throw new OracleReferenceInputNotLiveException(
                    "oracle out-ref %s (expected NFT %s at %s): live-UTxO check failed, provider error %s"
                            .formatted(ref(outRef), unit, address, scrubbed(e.toString())));
        }
        if (answer == null) {
            throw new OracleReferenceInputNotLiveException(
                    "oracle out-ref %s (expected NFT %s at %s): live-UTxO check failed, provider error: no answer"
                            .formatted(ref(outRef), unit, address));
        }
        if (!answer.isSuccessful()) {
            if (answer.code() == 404) {
                throw new OracleReferenceInputNotLiveException(
                        "oracle out-ref %s (expected NFT %s at %s): spent, or the NFT is not at %s (404)"
                                .formatted(ref(outRef), unit, address, address));
            }
            throw new OracleReferenceInputNotLiveException(
                    "oracle out-ref %s (expected NFT %s at %s): live-UTxO check failed, provider error %d %s"
                            .formatted(ref(outRef), unit, address, answer.code(), scrubbed(answer.getResponse())));
        }
        List<Utxo> live = answer.getValue() == null ? List.of() : answer.getValue();
        boolean present = live.stream().anyMatch(utxo -> outRef.getTransactionId().equals(utxo.getTxHash())
                && outRef.getIndex() == utxo.getOutputIndex());
        if (!present) {
            throw new OracleReferenceInputNotLiveException(
                    "oracle out-ref %s (expected NFT %s at %s): spent: the NFT now sits at %s"
                            .formatted(ref(outRef), unit, address,
                                    live.stream().map(u -> u.getTxHash() + "#" + u.getOutputIndex()).toList()));
        }
    }

    /**
     * A provider's own text, with the two spent-input markers {@code LiquidationExecutor} matches on defused,
     * so no answer Blockfrost gives can make a probe refusal read as a spent wallet utxo. Deliberately no
     * exception cause either: the executor's detail is the cause chain, and a cause's text is not scrubbed.
     */
    private static String scrubbed(String providerText) {
        return String.valueOf(providerText).replace("BadInputs", "Bad-Inputs")
                .replace("ScriptFailures", "Script-Failures");
    }

    private static String ref(TransactionInput outRef) {
        return outRef.getTransactionId() + "#" + outRef.getIndex();
    }

    /**
     * An oracle out-ref a build would reference is not provably live. Deliberately NOT a
     * {@link LiquidateTransactionBuilder.RefusedException}: it is a fact about the world the build depends
     * on, logged at ERROR every cycle by the executor's machinery-failure catch, and its message never
     * carries the spent-input markers that catch reads as a spent wallet utxo.
     */
    public static final class OracleReferenceInputNotLiveException extends RuntimeException {

        public OracleReferenceInputNotLiveException(String message) {
            super(message);
        }
    }
}
