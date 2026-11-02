package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.model.AssetType;

import java.util.Objects;

/**
 * ⛔ <b>What this node's own index knows about ONE oracle out-ref</b> (FAB-196). Asked by
 * {@link OracleReferenceInputProbe} before Blockfrost, once per distinct out-ref, with the NFT the registry
 * expects there.
 *
 * <h2>The three answers</h2>
 * <ul>
 *   <li><b>{@link State#LIVE}</b> — the index holds an UNSPENT row for exactly that out-ref, and the row holds
 *       the expected NFT. The probe accepts the out-ref without asking Blockfrost.</li>
 *   <li><b>{@link State#SPENT}</b> — the index has recorded a spend of that out-ref, by
 *       {@link Answer#spentTxHash()}. The probe refuses without asking Blockfrost.</li>
 *   <li><b>{@link State#UNKNOWN}</b> — anything else: never seen, pruned, a policy that is not configured, a
 *       row that does not hold the expected NFT. <b>UNKNOWN means "Blockfrost decides, as before"</b>: the
 *       probe runs exactly the live-UTxO check it ran before this index existed, with the same verdicts and
 *       the same texts.</li>
 * </ul>
 * An index answers only about liveness. It never decides an out-ref's content (its address comes from the
 * probe's content source), and an answer it cannot give is UNKNOWN, never a guess.
 */
public interface OracleOutRefIndex {

    /** The three things an index can say about one out-ref. */
    enum State {
        LIVE,
        SPENT,
        UNKNOWN
    }

    /**
     * One answer. {@code spentTxHash} is the spending transaction for {@link State#SPENT} and {@code null}
     * otherwise.
     */
    record Answer(State state, String spentTxHash) {

        public Answer {
            Objects.requireNonNull(state, "state");
            if ((state == State.SPENT) != (spentTxHash != null)) {
                throw new IllegalArgumentException("a spending tx hash is carried by SPENT and only by SPENT, got "
                        + state + " with " + spentTxHash);
            }
        }

        public static Answer live() {
            return new Answer(State.LIVE, null);
        }

        public static Answer spent(String spentTxHash) {
            return new Answer(State.SPENT, Objects.requireNonNull(spentTxHash, "spentTxHash"));
        }

        public static Answer unknown() {
            return new Answer(State.UNKNOWN, null);
        }
    }

    /**
     * What the index knows about {@code outRef}, expected to hold {@code nft}. Never {@code null}.
     */
    Answer lookup(TransactionInput outRef, AssetType nft);

    /** An index that knows nothing: every out-ref is {@link State#UNKNOWN}, so Blockfrost decides them all. */
    OracleOutRefIndex NOTHING_INDEXED = (outRef, nft) -> Answer.unknown();
}
