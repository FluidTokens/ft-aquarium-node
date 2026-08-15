package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one irreversible guard on the reference-script path: does the UTxO an operator configured
 * actually publish the validator this node derives?
 *
 * <h2>Why this is not optional</h2>
 * A stale reference-script coordinate does not produce a transaction that merely fails. It produces
 * one whose reference inputs carry <em>somebody else's</em> validator, which passes phase 1 and dies
 * in phase 2 — with the collateral already forfeit. The startup hard-fail is the only thing between
 * a FluidTokens redeploy and that outcome, so it needs a test that would notice if it ever became a
 * {@code log.warn}.
 *
 * <h2>The comparison is against DERIVED hashes</h2>
 * Every expectation below comes from a real {@link LoansContractRegistry} built on the live preview
 * config policy ids — the same registry the transaction builder attaches scripts from. Nothing here
 * asserts against a hash typed into this file, because that would pin the test to itself rather than
 * to the derivation.
 */
class LoansReferenceScriptVerifierTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    private static final String TX = "b09e23dc5639642a4cbf112d39753c96ed0528115a8468b688b0e8cb19f243fe";

    /** A well-formed 28-byte script hash that is not any validator this node derives. */
    private static final String FOREIGN_SCRIPT_HASH = "de".repeat(28);

    /** One coordinate configured — {@code loan-claim-action} — and nothing else. */
    private static AppConfig.LiquidationConfiguration oneCoordinate() {
        return new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, false, 60, 120, 30,
                BigInteger.valueOf(1_500_000), 200, 30,
                new LiquidateTransactionBuilder.ReferenceScripts(null, null, null, null,
                        new TransactionInput(TX, 0),
                        null, null));
    }

    /** All six a {@code Liquidate} actually invokes, at six outputs of one publishing transaction. */
    private static AppConfig.LiquidationConfiguration sixCoordinates() {
        return new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, false, 60, 120, 30,
                BigInteger.valueOf(1_500_000), 200, 30,
                new LiquidateTransactionBuilder.ReferenceScripts(
                        new TransactionInput(TX, 0),
                        new TransactionInput(TX, 1),
                        new TransactionInput(TX, 2),
                        new TransactionInput(TX, 3),
                        new TransactionInput(TX, 4),
                        new TransactionInput(TX, 5),
                        null));
    }

    /** Nothing published — the shipped mainnet shape. */
    private static AppConfig.LiquidationConfiguration noCoordinates() {
        return new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, false, 60, 120, 30,
                BigInteger.valueOf(1_500_000), 200, 30,
                LiquidateTransactionBuilder.ReferenceScripts.none());
    }

    /** What the six outputs above must publish, in output-index order, per the derivation. */
    private static String derivedAt(int outputIndex) {
        return switch (outputIndex) {
            case 0 -> REGISTRY.getLoanPolicyId();
            case 1 -> REGISTRY.getLoanSpendScriptHash();
            case 2 -> REGISTRY.getLenderManagerWithdrawScriptHash();
            case 3 -> REGISTRY.getLenderManagerSpendScriptHash();
            case 4 -> REGISTRY.getLoanClaimActionScriptHash();
            case 5 -> REGISTRY.getLmLiquidateActionScriptHash();
            default -> throw new IllegalArgumentException("no expectation for output " + outputIndex);
        };
    }

    // ======================================================================================
    // the stub backend
    // ======================================================================================

    /** Records what it was asked for, and answers whatever the test set up. */
    private static final class Lookup implements LoansReferenceScriptVerifier.TxOutputLookup {

        private final List<String> asked = new ArrayList<>();

        private final java.util.function.BiFunction<String, Integer, Result<Utxo>> answer;

        Lookup(java.util.function.BiFunction<String, Integer, Result<Utxo>> answer) {
            this.answer = answer;
        }

        @Override
        public Result<Utxo> lookup(String txHash, int outputIndex) {
            asked.add(txHash + "#" + outputIndex);
            return answer.apply(txHash, outputIndex);
        }
    }

    private static Utxo utxoPublishing(String txHash, int index, String referenceScriptHash) {
        Utxo utxo = new Utxo();
        utxo.setTxHash(txHash);
        utxo.setOutputIndex(index);
        utxo.setAddress("addr_test1_unused");
        utxo.setAmount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000))));
        utxo.setReferenceScriptHash(referenceScriptHash);
        return utxo;
    }

    private static Result<Utxo> found(Utxo utxo) {
        return Result.<Utxo>success("ok").withValue(utxo).code(200);
    }

    private static LoansReferenceScriptVerifier verifier(AppConfig.LiquidationConfiguration configuration,
                                                         Lookup lookup, boolean failOnUnreachable) {
        return new LoansReferenceScriptVerifier(REGISTRY, configuration, lookup, failOnUnreachable);
    }

    // ======================================================================================
    // the happy path — the control this whole class rests on
    // ======================================================================================

    /**
     * Six coordinates, each publishing the hash this node derives for that validator. Without this
     * passing, every hard-fail below could be a verifier that simply throws at everything.
     */
    @Test
    void sixCoordinatesPublishingTheDerivedHashesVerify() {
        Lookup lookup = new Lookup((txHash, index) ->
                found(utxoPublishing(txHash, index, derivedAt(index))));

        assertDoesNotThrow(() -> verifier(sixCoordinates(), lookup, false).verify());
        assertEquals(6, lookup.asked.size(), "every configured coordinate must be looked up");
        assertEquals(List.of(TX + "#0", TX + "#1", TX + "#2", TX + "#3", TX + "#4", TX + "#5"),
                lookup.asked);
    }

    /** Nothing configured is a legal configuration; it must not reach the backend at all. */
    @Test
    void noCoordinatesMeansNoLookupsAndNoFailure() {
        Lookup lookup = new Lookup((txHash, index) -> {
            throw new AssertionError("an unconfigured coordinate was looked up");
        });

        assertDoesNotThrow(() -> verifier(noCoordinates(), lookup, false).verify());
        assertTrue(lookup.asked.isEmpty());
    }

    // ======================================================================================
    // the three hard-fails
    // ======================================================================================

    /**
     * The mismatch. The UTxO exists, is readable, and publishes a perfectly valid script — just not
     * this one. That is what a redeploy looks like, and it must abort startup.
     */
    @Test
    void aUtxoPublishingSomeoneElsesValidatorAbortsStartup() {
        Lookup lookup = new Lookup((txHash, index) ->
                found(utxoPublishing(txHash, index, FOREIGN_SCRIPT_HASH)));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> verifier(oneCoordinate(), lookup, false).verify());

        assertTrue(thrown.getMessage().contains("loans.liquidation.reference-scripts.loan-claim-action"),
                "the message must name the key an operator has to fix: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(REGISTRY.getLoanClaimActionScriptHash()),
                "and the hash this node derives: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(FOREIGN_SCRIPT_HASH),
                "and the hash the chain published: " + thrown.getMessage());
    }

    /**
     * The comparison is per-validator, not "publishes something we know". Here every coordinate
     * carries a hash this node derives — but shifted by one output, so each names the wrong
     * validator. A verifier that merely checked membership would pass this.
     */
    @Test
    void coordinatesThatPublishTheDerivedHashesInTheWrongOrderAbortStartup() {
        Lookup lookup = new Lookup((txHash, index) ->
                found(utxoPublishing(txHash, index, derivedAt((index + 1) % 6))));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> verifier(sixCoordinates(), lookup, false).verify());

        assertTrue(thrown.getMessage().contains("reference-script mismatch"), thrown.getMessage());
    }

    /**
     * A 4xx is an <em>answer</em>, not an outage. A 404 means the output the coordinate names does
     * not exist — exactly the stale-coordinate case — so degrading it to "unverified" would reopen
     * the hole this class exists to close.
     */
    @Test
    void aFourHundredResponseAbortsStartupRatherThanWarning() {
        for (int code : new int[]{400, 403, 404}) {
            Lookup lookup = new Lookup((txHash, index) ->
                    Result.<Utxo>error("not found").code(code));

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> verifier(oneCoordinate(), lookup, false).verify(),
                    "HTTP " + code + " must not be treated as a transient failure");
            assertTrue(thrown.getMessage().contains("stale"), thrown.getMessage());
        }
    }

    /**
     * The coordinate names a real output that simply is not a reference-script UTxO — a typo'd index,
     * or a publishing transaction whose outputs moved. The lookup succeeded, so this is an answer
     * too, and the answer is no.
     */
    @Test
    void aUtxoCarryingNoReferenceScriptAbortsStartup() {
        for (String published : new String[]{null, ""}) {
            Lookup lookup = new Lookup((txHash, index) ->
                    found(utxoPublishing(txHash, index, published)));

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> verifier(oneCoordinate(), lookup, false).verify(),
                    "a utxo with reference_script_hash=" + published + " must not verify");
            assertTrue(thrown.getMessage().contains("carries no reference script"), thrown.getMessage());
        }
    }

    /** A successful call that carries no value at all is the same kind of answer. */
    @Test
    void aSuccessfulLookupWithNoUtxoAbortsStartup() {
        Lookup lookup = new Lookup((txHash, index) -> Result.<Utxo>success("ok").code(200));

        assertThrows(IllegalStateException.class,
                () -> verifier(oneCoordinate(), lookup, false).verify());
    }

    // ======================================================================================
    // the soft failure — and its opt-out
    // ======================================================================================

    /**
     * A 5xx or a transport error is "we could not ask", and it must not take the node down: the
     * Aquarium scheduled-transaction path does not depend on loans at all. Same policy as
     * {@link LoansConfigVerifier}.
     */
    @Test
    void aTransientBackendFailureWarnsAndContinues() {
        Lookup fiveHundred = new Lookup((txHash, index) -> Result.<Utxo>error("bad gateway").code(502));
        assertDoesNotThrow(() -> verifier(oneCoordinate(), fiveHundred, false).verify());

        Lookup throttled = new Lookup((txHash, index) -> Result.<Utxo>error("slow down").code(429));
        assertDoesNotThrow(() -> verifier(oneCoordinate(), throttled, false).verify());

        Lookup transport = new Lookup((txHash, index) -> {
            throw new RuntimeException("connect timed out");
        });
        assertDoesNotThrow(() -> verifier(oneCoordinate(), transport, false).verify());
    }

    /** And the operator who wants the check mandatory can have it. */
    @Test
    void failOnUnreachableTurnsATransientFailureIntoAStartupFailure() {
        Lookup fiveHundred = new Lookup((txHash, index) -> Result.<Utxo>error("bad gateway").code(502));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> verifier(oneCoordinate(), fiveHundred, true).verify());
        assertTrue(thrown.getMessage().contains("Cannot verify"), thrown.getMessage());
    }
}
