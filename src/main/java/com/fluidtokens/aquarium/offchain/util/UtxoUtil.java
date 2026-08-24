package com.fluidtokens.aquarium.offchain.util;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.AddressUtxo;
import com.bloxbean.cardano.yaci.store.common.util.ScriptReferenceUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;

public class UtxoUtil {

    /**
     * Returned in place of {@code null} when a UTxO demonstrably carries a reference script whose
     * hash cannot be derived. Deliberately not hash-shaped so it can never be mistaken for one.
     * <p>
     * Every caller in this codebase asks {@code referenceScriptHash == null}, i.e. "is this UTxO
     * free to spend", and for that question the honest answer is <b>no</b> even when we cannot name
     * the script. The one caller that reads the value rather than its nullness
     * ({@code LoansReferenceScriptVerifier}) resolves its UTxOs from Blockfrost and never through
     * this mapper.
     */
    static final String UNRESOLVED_REFERENCE_SCRIPT = "reference-script-present-hash-unresolved";

    public static Utxo toUtxo(AddressUtxoEntity addressUtxoEntity) {
        return Utxo.builder()
                .txHash(addressUtxoEntity.getTxHash())
                .outputIndex(addressUtxoEntity.getOutputIndex())
                .address(addressUtxoEntity.getOwnerAddr())
                .amount(addressUtxoEntity.getAmounts().stream().map(AmountUtil::toAmountCore).toList())
                .dataHash(addressUtxoEntity.getDataHash())
                .inlineDatum(addressUtxoEntity.getInlineDatum())
                .referenceScriptHash(referenceScript(addressUtxoEntity.getReferenceScriptHash(),
                        addressUtxoEntity.getScriptRef()))
                .build();
    }

    public static Utxo toUtxo(AddressUtxo addressUtxo) {
        return Utxo.builder()
                .txHash(addressUtxo.getTxHash())
                .outputIndex(addressUtxo.getOutputIndex())
                .address(addressUtxo.getOwnerAddr())
                .amount(addressUtxo.getAmounts().stream().map(AmountUtil::toAmountCore).toList())
                .dataHash(addressUtxo.getDataHash())
                .inlineDatum(addressUtxo.getInlineDatum())
                .referenceScriptHash(addressUtxo.getScriptRef())
                .build();
    }

    /**
     * Whether this output carries a reference script, answered from <b>both</b> columns Yaci Store
     * writes rather than only the derived one.
     *
     * <h2>Why both</h2>
     * Yaci Store derives {@code referenceScriptHash} from the raw {@code scriptRef} when it indexes
     * an output — but that derivation is <b>best-effort</b>: {@code UtxoProcessor} (yaci-store
     * v0.1.7, {@code UtxoProcessor:199-208}) wraps it in a {@code try} that logs the failure and
     * carries on with a {@code null} hash; the {@code throw} on that path is present but commented
     * out. The row is then stored with {@code scriptRef} set and {@code referenceScriptHash} NULL.
     * The same shape arises for any row written before that column was populated.
     * <p>
     * Reading only the derived column therefore reports a reference-script UTxO as an ordinary one.
     * That is not cosmetic: {@code LiquidationExecutor.adaOnlyWalletUtxo()} and
     * {@code ScheduledTransactionService} both decide what the bot may SPEND on exactly this
     * predicate, and the bot's published {@code loan_claim_action} reference script sits in a UTxO
     * at its own operational address. A false "no script here" is how the bot comes to spend the
     * very script its convert liquidations need to fit under {@code maxTxSize}.
     * <p>
     * Note the sibling overload above never had this problem — it maps {@code scriptRef} straight
     * through. The two overloads disagreeing was the tell.
     */
    private static String referenceScript(String referenceScriptHash, String scriptRef) {
        if (referenceScriptHash != null && !referenceScriptHash.isBlank()) {
            return referenceScriptHash;
        }
        if (scriptRef == null || scriptRef.isBlank()) {
            return null;
        }
        try {
            // Yaci's own derivation, re-run: when the null came from a schema gap rather than a
            // malformed scriptRef, this recovers the real hash.
            return ScriptReferenceUtil.getReferenceScriptHash(HexUtil.decodeHexString(scriptRef));
        } catch (Exception e) {
            // Undecodable — but the scriptRef is proof enough that a script is there. Fail towards
            // "do not spend this", never towards null.
            return UNRESOLVED_REFERENCE_SCRIPT;
        }
    }

}
