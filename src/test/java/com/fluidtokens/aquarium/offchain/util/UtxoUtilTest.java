package com.fluidtokens.aquarium.offchain.util;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The mapper decides what the bot is allowed to SPEND.
 * <p>
 * {@code LiquidationExecutor.adaOnlyWalletUtxo()} and {@code ScheduledTransactionService} both admit
 * a wallet UTxO only when {@code referenceScriptHash == null}. Yaci Store's derivation of that column
 * is best-effort — {@code UtxoProcessor} (v0.1.7, :199-208) catches a derivation failure, logs it and
 * leaves the column NULL with {@code scriptRef} still set — so a mapper reading only the derived
 * column reports a reference-script UTxO as free to spend. On preview the bot's published
 * {@code loan_claim_action} script sits at the bot's OWN operational address, which makes that
 * mis-read the difference between a working bot and one that spends its own capability.
 */
class UtxoUtilTest {

    /** The real preview coordinate, so a reader can match this against the chain. */
    private static final String PUBLISH_TX =
            "48c102c0034b04558c640df211045fdd7511dc7046b55942ca5909372eab24cd";
    private static final String LOAN_CLAIM_ACTION_HASH =
            "9ae63b26c98d90024a45f9cdb57e4154f72144d44325f0a261b8bc1d";

    private static AddressUtxoEntity entity(String referenceScriptHash, String scriptRef) {
        AddressUtxoEntity entity = new AddressUtxoEntity();
        entity.setTxHash(PUBLISH_TX);
        entity.setOutputIndex(0);
        entity.setOwnerAddr("addr_test1qztwnc4gj0yqp3z8cq8056lewx8y634hvdvcj45ky9vqd0m");
        entity.setAmounts(List.of(Amt.builder()
                .unit("lovelace")
                .quantity(BigInteger.valueOf(38_359_000L))
                .build()));
        entity.setReferenceScriptHash(referenceScriptHash);
        entity.setScriptRef(scriptRef);
        return entity;
    }

    /** The ordinary case: Yaci derived the hash, and it is passed straight through. */
    @Test
    void aDerivedHashIsCarriedThrough() {
        Utxo utxo = UtxoUtil.toUtxo(entity(LOAN_CLAIM_ACTION_HASH, "d8184c820358054e4d0100002221"));
        assertEquals(LOAN_CLAIM_ACTION_HASH, utxo.getReferenceScriptHash(),
                "the derived column wins when it is populated");
    }

    /** An ordinary ada UTxO stays spendable — the guard must not swallow the normal case. */
    @Test
    void anOutputWithNoScriptRefStaysSpendable() {
        assertNull(UtxoUtil.toUtxo(entity(null, null)).getReferenceScriptHash(),
                "a plain ada utxo must remain eligible as a wallet input");
        assertNull(UtxoUtil.toUtxo(entity(null, "")).getReferenceScriptHash(),
                "an empty scriptRef is no script at all");
    }

    /**
     * THE DEFECT. The derived column is NULL while the raw scriptRef is present — the shape
     * {@code UtxoProcessor}'s swallowed derivation failure leaves behind.
     * <p>
     * Revert {@code toUtxo} to {@code .referenceScriptHash(entity.getReferenceScriptHash())} and this
     * test fails: the published reference script is handed back as an ordinary, spendable ada UTxO.
     */
    @Test
    void aScriptRefWithNoDerivedHashIsStillReportedAsCarryingAReferenceScript() {
        Utxo utxo = UtxoUtil.toUtxo(entity(null, "d8184c820358054e4d0100002221"));

        assertNotNull(utxo.getReferenceScriptHash(),
                "a utxo whose scriptRef is set must NEVER be reported as free to spend — that is "
                        + "how the bot comes to spend its own published reference script");
    }

    /**
     * The failure-of-the-failure case: the scriptRef cannot be decoded, so no hash can be derived.
     * The answer must still be "do not spend this". Falling back to null here would reinstate the
     * whole defect for precisely the rows Yaci itself could not decode — which are the rows that
     * produced the null column in the first place.
     */
    @Test
    void anUndecodableScriptRefFailsTowardsNotSpendable() {
        Utxo utxo = UtxoUtil.toUtxo(entity(null, "not-hex-at-all-zz"));

        assertNotNull(utxo.getReferenceScriptHash(),
                "an undecodable scriptRef must fail towards 'do not spend', never towards null");
        assertEquals(UtxoUtil.UNRESOLVED_REFERENCE_SCRIPT, utxo.getReferenceScriptHash(),
                "and it must be an obviously non-hash marker, never something mistakable for a hash");
    }
}
