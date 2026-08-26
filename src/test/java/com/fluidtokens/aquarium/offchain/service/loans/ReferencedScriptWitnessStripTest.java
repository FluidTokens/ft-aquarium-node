package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-048 — <b>attach exactly the scripts that are not otherwise supplied</b>.
 *
 * <h2>Both directions, because we have seen both errors in one week</h2>
 * Giovanni's rule 3: <i>"liquidation should be possible with a combination of reference script or
 * attached scripts (ensure only required scripts are attached)"</i> — <b>both modes must work</b>.
 * <ul>
 *   <li><b>Referenced AND attached</b> ⇒ {@code ExtraneousScriptWitnessesUTXOW} at phase 1.</li>
 *   <li><b>Neither referenced NOR attached</b> ⇒ the script cannot be resolved and the redeemer has
 *       nothing to run.</li>
 * </ul>
 * <b>An assertion covering only the first leaves the second live.</b> Every test here therefore checks
 * what SURVIVES as well as what is removed.
 *
 * <h2>Why this exists at all</h2>
 * {@code attachValidators} already declines to attach a referenced script. But at cardano-client-lib
 * <b>0.7.2</b> every {@code ScriptTx.mintAsset(…)} overload takes a {@code PlutusScript} and calls a
 * <b>private</b> {@code attachMintValidator(script)} unconditionally ({@code ScriptTx:309}) — there is
 * no policy-id form until 0.8.0. So the one script a liquidation both mints with and may reference,
 * {@code loan.loan}, re-enters the witness set behind the attach-skip's back.
 *
 * <p>⚠ These tests pin no deployment coordinate, so they keep meaning the same thing after a redeploy.
 */
class ReferencedScriptWitnessStripTest {

    /** Distinct, valid, tiny V3 scripts — different bytes, therefore different hashes. */
    private static final PlutusV3Script A = script("46450101002499");
    private static final PlutusV3Script B = script("4746010100229800");
    private static final PlutusV3Script C = script("484701010022980099");

    private static PlutusV3Script script(String cborHex) {
        return PlutusV3Script.builder().type("PlutusScriptV3").cborHex(cborHex).build();
    }

    private static String hash(PlutusV3Script s) {
        try {
            return HexUtil.encodeHexString(s.getScriptHash());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Transaction txWitnessing(PlutusV3Script... scripts) {
        return Transaction.builder()
                .body(TransactionBody.builder().inputs(new ArrayList<>()).outputs(new ArrayList<>()).build())
                .witnessSet(TransactionWitnessSet.builder()
                        .plutusV3Scripts(new ArrayList<>(List.of(scripts)))
                        .build())
                .build();
    }

    private static List<String> hashesIn(Transaction txn) {
        return txn.getWitnessSet().getPlutusV3Scripts().stream()
                .map(s -> hash((PlutusV3Script) s)).toList();
    }

    private static void strip(Transaction txn, PlutusV3Script... referenced) {
        LiquidateTransactionBuilder
                .stripReferencedScriptsFromWitnessSet(List.of((Object[]) referenced).stream()
                        .map(o -> (PlutusScript) o).toList())
                .apply(null, txn);
    }

    // ---- direction 1: referenced must NOT be witnessed ------------------------------------------

    @Test
    void aReferencedScriptIsRemovedFromTheWitnessSet() {
        Transaction txn = txWitnessing(A, B, C);

        strip(txn, A);

        assertFalse(hashesIn(txn).contains(hash(A)),
                "a script supplied as a reference input must not also be witnessed — that is "
                        + "ExtraneousScriptWitnessesUTXOW at phase 1");
    }

    // ---- direction 2: everything else must SURVIVE ----------------------------------------------

    @Test
    void everyScriptThatIsNotReferencedSURVIVES() {
        Transaction txn = txWitnessing(A, B, C);

        strip(txn, A);

        assertEquals(2, txn.getWitnessSet().getPlutusV3Scripts().size());
        assertTrue(hashesIn(txn).contains(hash(B)), "B is not referenced and must stay attached");
        assertTrue(hashesIn(txn).contains(hash(C)), "C is not referenced and must stay attached");
    }

    /**
     * The all-attached mode of rule 3. Nothing is referenced, so nothing may be removed — a strip that
     * over-reaches here produces a transaction whose redeemers have no script to run.
     */
    @Test
    void withNothingReferencedTheWitnessSetIsUntouched() {
        Transaction txn = txWitnessing(A, B, C);

        strip(txn);

        assertEquals(List.of(hash(A), hash(B), hash(C)), hashesIn(txn),
                "the all-attached mode must survive intact, in order");
    }

    @Test
    void referencingEveryScriptEmptiesTheWitnessSetAndNoMore() {
        Transaction txn = txWitnessing(A, B, C);

        strip(txn, A, B, C);

        assertTrue(txn.getWitnessSet().getPlutusV3Scripts().isEmpty(),
                "the fully-referenced mode witnesses nothing");
    }

    // ---- shapes it must tolerate ---------------------------------------------------------------

    @Test
    void aReferencedScriptThatWasNeverAttachedIsANoOp() {
        Transaction txn = txWitnessing(B, C);

        strip(txn, A);

        assertEquals(2, txn.getWitnessSet().getPlutusV3Scripts().size(),
                "removing something that is not there must not disturb what is");
    }

    @Test
    void anAbsentWitnessSetDoesNotThrow() {
        Transaction txn = Transaction.builder()
                .body(TransactionBody.builder().inputs(new ArrayList<>()).outputs(new ArrayList<>()).build())
                .build();

        assertDoesNotThrow(() -> strip(txn, A));
    }

    /**
     * Identity is the script HASH, never object equality — the registry hands out fresh instances and
     * a reference-script coordinate resolves to a different object carrying the same bytes.
     */
    @Test
    void identityIsTheHashNotTheObject() {
        Transaction txn = txWitnessing(A, B);

        strip(txn, script(A.getCborHex()));   // equal bytes, different instance

        assertFalse(hashesIn(txn).contains(hash(A)), "a distinct instance of the same script must match");
        assertTrue(hashesIn(txn).contains(hash(B)));
    }
}
