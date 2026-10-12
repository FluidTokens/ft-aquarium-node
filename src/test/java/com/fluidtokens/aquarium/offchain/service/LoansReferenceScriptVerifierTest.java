package com.fluidtokens.aquarium.offchain.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.LoansReferenceScriptVerifier.Liveness;
import com.fluidtokens.aquarium.offchain.service.LoansReferenceScriptVerifier.PublishedOutput;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>A bad named reference-script coordinate is WARNED and DROPPED at boot — its validator then
 * travels inline — and nothing is refused for it</b> (FAB-108; Giovanni, 2026-10-12: <i>"we shouldn't
 * stop anything (if possible), just write warns in the logs quite loudly"</i>).
 *
 * <h2>Three outcomes, and which side ambiguity falls on</h2>
 * <ul>
 *   <li><b>Usable</b> — the output exists, is unspent (or its liveness is unknown), and publishes the
 *       hash this node derives for that key. Kept.</li>
 *   <li><b>DROPPED</b> — a positive finding that the coordinate is wrong: absent (404), rejected (400),
 *       spent, no reference script, somebody else's validator, or a key this node derives no hash for.
 *       One {@code REFERENCE_SCRIPT_DROPPED} WARN, and the slot is nulled in the live configuration.</li>
 *   <li><b>UNVERIFIED</b> — the provider could not be asked. One {@code REFERENCE_SCRIPT_UNVERIFIED}
 *       WARN, and the slot is KEPT: a wrong-but-kept coordinate fails evaluation inside
 *       {@code build()}, before submission, so keeping it is the recoverable side (VH-73).</li>
 * </ul>
 *
 * <h2>The comparison is against DERIVED hashes</h2>
 * Every expectation below comes from a real {@link LoansContractRegistry} — the same registry the
 * transaction builder attaches scripts from — and every "must not" is paired in its own method with
 * the "must still" one variable away (VH-66), so a verifier that dropped or kept everything fails here.
 */
class LoansReferenceScriptVerifierTest {

    /** Preview coordinates, no {@code loans.minswap.*}: the convert action derives nothing. */
    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    /** The mainnet deployment WITH the Minswap parameters, so all nine slots derive a hash. */
    private static final LoansContractRegistry FULL = new LoansContractRegistry(
            "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416",
            "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4",
            "706172616d6574657273",
            "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa",
            "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c",
            "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b",
            "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c");

    private static final String PREFIX = "loans.liquidation.reference-scripts.";

    /** The nine named keys, in {@link LiquidateTransactionBuilder.ReferenceScripts} slot order. */
    private static final List<String> KEYS = List.of(
            PREFIX + "loan", PREFIX + "loan-spend", PREFIX + "lender-manager",
            PREFIX + "lender-manager-spend", PREFIX + "loan-claim-action", PREFIX + "lm-liquidate-action",
            PREFIX + "asset-manager", PREFIX + "lm-liquidate-and-pay-in-advance-action",
            PREFIX + "lm-liquidate-and-convert-action");

    /** A well-formed 28-byte script hash that is not any validator this node derives. */
    private static final String FOREIGN_SCRIPT_HASH = "de".repeat(28);

    /** The ruling's mutation control: a real coordinate, publishing a hash that is not the claim action. */
    private static final TransactionInput RULING_COORDINATE = new TransactionInput(
            "954f8be5773c3ebce3377ecb7a420f407ef18500638bb6d7db0022ed9e9b7c50", 0);
    private static final String RULING_PUBLISHED = "1551bd4efdef76f3184798331e1c74f6a1cef51955b0c96b8db18d1f";

    private static final String SPENDER = "5b".repeat(32);

    // ======================================================================================
    // fixtures
    // ======================================================================================

    /** Coordinate {@code n}: {@code nnnn…n#0}, distinct per slot. */
    private static TransactionInput in(int n) {
        return new TransactionInput(String.valueOf(n).repeat(64).substring(0, 64), 0);
    }

    private static AppConfig.LiquidationConfiguration configured(LiquidateTransactionBuilder.ReferenceScripts scripts) {
        return new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30,
                BigInteger.valueOf(1_500_000), 200, scripts);
    }

    private static LiquidateTransactionBuilder.ReferenceScripts scripts(TransactionInput... nine) {
        assertEquals(9, nine.length);
        return new LiquidateTransactionBuilder.ReferenceScripts(nine[0], nine[1], nine[2], nine[3], nine[4],
                nine[5], nine[6], nine[7], nine[8]);
    }

    /** All nine slots, at {@code in(1)} … {@code in(9)}. */
    private static LiquidateTransactionBuilder.ReferenceScripts allNine() {
        return scripts(in(1), in(2), in(3), in(4), in(5), in(6), in(7), in(8), in(9));
    }

    /** One coordinate configured — {@code loan-claim-action} — and nothing else. */
    private static LiquidateTransactionBuilder.ReferenceScripts claimOnly(TransactionInput coordinate) {
        return scripts(null, null, null, null, coordinate, null, null, null, null);
    }

    private static List<TransactionInput> slots(LiquidateTransactionBuilder.ReferenceScripts s) {
        return Arrays.asList(s.loan(), s.loanSpend(), s.lenderManager(), s.lenderManagerSpend(),
                s.loanClaimAction(), s.lmLiquidateAction(), s.assetManager(),
                s.lmLiquidateAndPayInAdvanceAction(), s.lmLiquidateAndConvertAction());
    }

    /** What slot {@code i} must publish, per the derivation. */
    private static String derived(LoansContractRegistry registry, int slot) {
        return switch (slot) {
            case 0 -> registry.getLoanPolicyId();
            case 1 -> registry.getLoanSpendScriptHash();
            case 2 -> registry.getLenderManagerWithdrawScriptHash();
            case 3 -> registry.getLenderManagerSpendScriptHash();
            case 4 -> registry.getLoanClaimActionScriptHash();
            case 5 -> registry.getLmLiquidateActionScriptHash();
            case 6 -> registry.getAssetManagerWithdrawScriptHash();
            case 7 -> registry.getLmLiquidateAndPayInAdvanceActionScriptHash();
            case 8 -> registry.getLmLiquidateAndConvertActionScriptHash();
            default -> throw new IllegalArgumentException("no slot " + slot);
        };
    }

    private static String coordinate(TransactionInput input) {
        return input.getTransactionId() + "#" + input.getIndex();
    }

    // ======================================================================================
    // the stub provider
    // ======================================================================================

    @FunctionalInterface
    private interface Answer {
        Result<PublishedOutput> apply(String txHash, int index) throws Exception;
    }

    /** Records what it was asked for, in order, and answers whatever the test set up. */
    private static final class Lookup implements LoansReferenceScriptVerifier.PublishedOutputLookup {

        private final List<String> asked = new ArrayList<>();

        private final Answer answer;

        Lookup(Answer answer) {
            this.answer = answer;
        }

        @Override
        public Result<PublishedOutput> lookup(String txHash, int outputIndex) throws Exception {
            asked.add(txHash + "#" + outputIndex);
            return answer.apply(txHash, outputIndex);
        }
    }

    private static Result<PublishedOutput> published(String hash, Liveness liveness) {
        return Result.<PublishedOutput>success("ok")
                .withValue(new PublishedOutput(hash, liveness, liveness == Liveness.SPENT ? SPENDER : null))
                .code(200);
    }

    private static Result<PublishedOutput> status(int code) {
        return Result.<PublishedOutput>error("HTTP " + code + " for txs/…/utxos: provider said no").code(code);
    }

    /** A provider that answers each coordinate from {@code chain}, and 404 for anything else. */
    private static Lookup chain(Map<String, Result<PublishedOutput>> chain) {
        return new Lookup((tx, ix) -> chain.getOrDefault(tx + "#" + ix, status(404)));
    }

    /** Every slot of {@code scripts} publishing the hash {@code registry} derives for it, UNSPENT. */
    private static Map<String, Result<PublishedOutput>> honest(LoansContractRegistry registry,
                                                               LiquidateTransactionBuilder.ReferenceScripts scripts) {
        Map<String, Result<PublishedOutput>> chain = new HashMap<>();
        List<TransactionInput> slots = slots(scripts);
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i) != null) {
                chain.put(coordinate(slots.get(i)), published(derived(registry, i), Liveness.UNSPENT));
            }
        }
        return chain;
    }

    private static LoansReferenceScriptVerifier verifier(LoansContractRegistry registry,
                                                         AppConfig.LiquidationConfiguration configuration,
                                                         Lookup lookup, boolean failOnUnreachable) {
        return new LoansReferenceScriptVerifier(registry, configuration, lookup, failOnUnreachable);
    }

    /** Runs {@code verifier.verify()} and returns what it logged. */
    private static List<ILoggingEvent> verifyCapturing(LoansReferenceScriptVerifier verifier) {
        Logger logger = (Logger) LoggerFactory.getLogger(LoansReferenceScriptVerifier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(verifier::verify, "verify() must never throw for a reference-script problem");
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list;
    }

    private static List<ILoggingEvent> at(List<ILoggingEvent> events, Level level) {
        return events.stream().filter(e -> e.getLevel() == level).toList();
    }

    /** The per-coordinate WARNs that name {@code key}. */
    private static List<String> warningsNaming(List<ILoggingEvent> events, String key) {
        return at(events, Level.WARN).stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(key + " ")).toList();
    }

    // ======================================================================================
    // (a) the happy path — the control this whole class rests on
    // ======================================================================================

    /**
     * Nine coordinates, each publishing the hash this node derives, all unspent. Without this passing,
     * every drop below could be a verifier that simply drops everything.
     */
    @Test
    void allNinePublishingTheirDerivedHashesAreKeptWithOneLookupEach() {
        var configuration = configured(allNine());
        Lookup lookup = chain(honest(FULL, allNine()));
        var verifier = verifier(FULL, configuration, lookup, false);

        List<ILoggingEvent> logs = verifyCapturing(verifier);

        assertEquals(allNine(), configuration.getReferenceScripts(), "nothing may be dropped");
        assertTrue(verifier.degradations().isEmpty(), "and nothing degraded: " + verifier.degradations());
        assertEquals(slots(allNine()).stream().map(LoansReferenceScriptVerifierTest::coordinate).toList(),
                lookup.asked, "one lookup per distinct coordinate, in slot order");
        assertTrue(at(logs, Level.WARN).isEmpty(), "a clean pass warns about nothing: " + at(logs, Level.WARN));
    }

    // ======================================================================================
    // (b), (c) — when nothing may be asked
    // ======================================================================================

    /** (b) Nothing configured is a legal configuration; it must not reach the provider at all. */
    @Test
    void noCoordinatesMeansNoLookupsAndNothingDegraded() {
        var configuration = configured(LiquidateTransactionBuilder.ReferenceScripts.none());
        Lookup lookup = new Lookup((tx, ix) -> {
            throw new AssertionError("an unconfigured coordinate was looked up");
        });
        var verifier = verifier(REGISTRY, configuration, lookup, false);

        verifyCapturing(verifier);

        assertTrue(lookup.asked.isEmpty());
        assertTrue(verifier.degradations().isEmpty());
        assertEquals(LiquidateTransactionBuilder.ReferenceScripts.none(), configuration.getReferenceScripts());
    }

    /**
     * (c) D5: an UNCONFIGURED registry (blank policy ids) derives well-formed, meaningless hashes, so
     * asking the chain would drop every coordinate against nonsense. Zero lookups, no narrowing — and
     * the same coordinates against a configured registry ARE looked up (the "must still").
     */
    @Test
    void anUnconfiguredRegistryMakesNoLookupsAndNarrowsNothing() {
        var unconfigured = new LoansContractRegistry("", "", "706172616d6574657273",
                "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa");
        assertFalse(unconfigured.isConfigured(), "precondition: blank policy ids are 'not configured'");
        var configuration = configured(allNine());
        Lookup lookup = new Lookup((tx, ix) -> status(404));
        var verifier = verifier(unconfigured, configuration, lookup, false);

        verifyCapturing(verifier);

        assertTrue(lookup.asked.isEmpty(), "an unconfigured registry must not reach the chain: " + lookup.asked);
        assertEquals(allNine(), configuration.getReferenceScripts(), "and must narrow nothing");
        assertTrue(verifier.degradations().isEmpty());

        // must still: the identical configuration against a configured registry is looked up (and,
        // with every coordinate 404, dropped).
        var control = configured(allNine());
        Lookup asked = new Lookup((tx, ix) -> status(404));
        verifyCapturing(verifier(FULL, control, asked, false));
        assertEquals(9, asked.asked.size());
        assertEquals(LiquidateTransactionBuilder.ReferenceScripts.none(), control.getReferenceScripts());
    }

    // ======================================================================================
    // (d) the ruling's mutation control
    // ======================================================================================

    /**
     * ⛔ THE CASE THE TICKET EXISTS FOR. {@code loan-claim-action} names a real, unspent output that
     * publishes somebody else's validator. Dropped — that slot only — with one loud WARN naming the
     * key, the coordinate and both hashes; the node does not throw and nothing else changes.
     */
    @Test
    void aCoordinatePublishingAnotherValidatorIsDroppedWithOneWarnAndNothingElseChanges() {
        var configuredScripts = scripts(in(1), in(2), in(3), in(4), RULING_COORDINATE, in(6), in(7), in(8), in(9));
        var configuration = configured(configuredScripts);
        Map<String, Result<PublishedOutput>> chain = honest(FULL, configuredScripts);
        chain.put(coordinate(RULING_COORDINATE), published(RULING_PUBLISHED, Liveness.UNSPENT));
        assertNotEquals(RULING_PUBLISHED, FULL.getLoanClaimActionScriptHash(), "precondition: a foreign hash");
        var verifier = verifier(FULL, configuration, chain(chain), false);

        List<ILoggingEvent> logs = verifyCapturing(verifier);

        String key = PREFIX + "loan-claim-action";
        assertNull(configuration.getReferenceScripts().loanClaimAction(), "the bad slot must be dropped");
        List<TransactionInput> before = slots(configuredScripts);
        List<TransactionInput> after = slots(configuration.getReferenceScripts());
        for (int i = 0; i < 9; i++) {
            if (i != 4) {
                assertEquals(before.get(i), after.get(i), "every other slot must be unchanged: " + KEYS.get(i));
            }
        }

        List<String> named = warningsNaming(logs, key);
        assertEquals(1, named.size(), "exactly one WARN for the dropped coordinate: " + named);
        String warn = named.get(0);
        assertTrue(warn.contains(LoansReferenceScriptVerifier.DROPPED), warn);
        assertTrue(warn.contains(coordinate(RULING_COORDINATE)), "names the coordinate: " + warn);
        assertTrue(warn.contains(RULING_PUBLISHED), "names the published hash: " + warn);
        assertTrue(warn.contains(FULL.getLoanClaimActionScriptHash()), "names the derived hash: " + warn);
        assertFalse(warn.contains(LoansReferenceScriptVerifier.UNVERIFIED), warn);
        assertEquals(1, at(logs, Level.WARN).stream()
                .filter(e -> e.getFormattedMessage().contains(LoansReferenceScriptVerifier.DROPPED)).count(),
                "one DROPPED WARN in the whole pass");
        assertEquals(List.of(warn), verifier.degradations(), "the degradation carries the WARN text");
    }

    // ======================================================================================
    // (e) the comparison is per key, not "publishes something we know"
    // ======================================================================================

    @Test
    void swappedOrShiftedCoordinatesAreDroppedBecauseTheComparisonIsPerKey() {
        assertNotEquals(FULL.getLoanSpendScriptHash(), FULL.getLenderManagerSpendScriptHash(),
                "precondition: the two spend validators differ");
        // loan-spend and lender-manager-spend exchanged: each publishes the OTHER's derived hash.
        var swapped = allNine();
        var configuration = configured(swapped);
        Map<String, Result<PublishedOutput>> chain = honest(FULL, swapped);
        chain.put(coordinate(in(2)), published(FULL.getLenderManagerSpendScriptHash(), Liveness.UNSPENT));
        chain.put(coordinate(in(4)), published(FULL.getLoanSpendScriptHash(), Liveness.UNSPENT));
        var verifier = verifier(FULL, configuration, chain(chain), false);

        verifyCapturing(verifier);

        assertNull(configuration.getReferenceScripts().loanSpend());
        assertNull(configuration.getReferenceScripts().lenderManagerSpend());
        assertEquals(in(1), configuration.getReferenceScripts().loan(), "must still keep the honest slots");
        assertEquals(in(5), configuration.getReferenceScripts().loanClaimAction());
        assertEquals(2, verifier.degradations().size(), verifier.degradations().toString());

        // Six derived hashes shifted by one output: every one a hash this node derives, none at its key.
        var six = scripts(in(1), in(2), in(3), in(4), in(5), in(6), null, null, null);
        var shiftedConfiguration = configured(six);
        Map<String, Result<PublishedOutput>> shifted = new HashMap<>();
        for (int i = 0; i < 6; i++) {
            shifted.put(coordinate(in(i + 1)), published(derived(FULL, (i + 1) % 6), Liveness.UNSPENT));
        }
        var shiftedVerifier = verifier(FULL, shiftedConfiguration, chain(shifted), false);

        verifyCapturing(shiftedVerifier);

        assertEquals(LiquidateTransactionBuilder.ReferenceScripts.none(), shiftedConfiguration.getReferenceScripts(),
                "all six must be dropped");
        assertEquals(6, shiftedVerifier.degradations().size());
    }

    // ======================================================================================
    // (f), (g), (h), (i) — the answers
    // ======================================================================================

    /**
     * (f) D2: 400 and 404 are ANSWERS. A 404 means the creating transaction is not on this network —
     * stale, or from another network (lookup-negatives §1), and the text must say both.
     */
    @Test
    void aNotFoundARejectionOrAMissingOutputIsDropped() {
        String key = PREFIX + "loan-claim-action";
        for (int code : new int[]{404, 400}) {
            var configuration = configured(claimOnly(in(5)));
            var verifier = verifier(REGISTRY, configuration, new Lookup((tx, ix) -> status(code)), false);

            List<ILoggingEvent> logs = verifyCapturing(verifier);

            assertNull(configuration.getReferenceScripts().loanClaimAction(), "HTTP " + code + " must drop");
            String warn = warningsNaming(logs, key).get(0);
            assertTrue(warn.contains(LoansReferenceScriptVerifier.DROPPED), warn);
            if (code == 404) {
                assertTrue(warn.contains("stale") && warn.contains("another network"),
                        "a 404 can mean the wrong network; the text must say so: " + warn);
            }
        }

        var missing = configured(claimOnly(in(5)));
        verifyCapturing(verifier(REGISTRY, missing,
                new Lookup((tx, ix) -> Result.<PublishedOutput>success("ok").withValue(null).code(200)), false));
        assertNull(missing.getReferenceScripts().loanClaimAction(), "a success with no output must drop");

        // must still: the same coordinate, answered with the derived hash, is kept.
        var control = configured(claimOnly(in(5)));
        verifyCapturing(verifier(REGISTRY, control,
                new Lookup((tx, ix) -> published(REGISTRY.getLoanClaimActionScriptHash(), Liveness.UNSPENT)), false));
        assertEquals(in(5), control.getReferenceScripts().loanClaimAction());
    }

    /** (g) A real output that is not a reference-script output is an answer too, and the answer is no. */
    @Test
    void anOutputCarryingNoReferenceScriptIsDropped() {
        for (String hash : new String[]{null, ""}) {
            var configuration = configured(claimOnly(in(5)));
            var verifier = verifier(REGISTRY, configuration,
                    new Lookup((tx, ix) -> published(hash, Liveness.UNSPENT)), false);

            verifyCapturing(verifier);

            assertNull(configuration.getReferenceScripts().loanClaimAction(),
                    "reference_script_hash=" + hash + " must drop");
            assertTrue(verifier.degradations().get(0).contains("no reference script"),
                    verifier.degradations().toString());
        }
        var control = configured(claimOnly(in(5)));
        verifyCapturing(verifier(REGISTRY, control,
                new Lookup((tx, ix) -> published(REGISTRY.getLoanClaimActionScriptHash(), Liveness.UNSPENT)), false));
        assertEquals(in(5), control.getReferenceScripts().loanClaimAction(), "must still keep the published one");
    }

    /**
     * (h) CCL trap 12: the creating transaction still reports a spent output, hash and all, forever.
     * Liveness is the only content that moves — so a SPENT output with the RIGHT hash is dropped, and
     * the text names the transaction that consumed it.
     */
    @Test
    void aSpentOutputWithTheRightHashIsDroppedAndTheSameHashUnspentIsKept() {
        var spent = configured(claimOnly(in(5)));
        var spentVerifier = verifier(REGISTRY, spent,
                new Lookup((tx, ix) -> published(REGISTRY.getLoanClaimActionScriptHash(), Liveness.SPENT)), false);

        verifyCapturing(spentVerifier);

        assertNull(spent.getReferenceScripts().loanClaimAction(), "a spent coordinate must drop");
        assertTrue(spentVerifier.degradations().get(0).contains(SPENDER),
                "naming the consuming transaction: " + spentVerifier.degradations());

        var unspent = configured(claimOnly(in(5)));
        var unspentVerifier = verifier(REGISTRY, unspent,
                new Lookup((tx, ix) -> published(REGISTRY.getLoanClaimActionScriptHash(), Liveness.UNSPENT)), false);
        verifyCapturing(unspentVerifier);
        assertEquals(in(5), unspent.getReferenceScripts().loanClaimAction(), "the same hash unspent is kept");
        assertTrue(unspentVerifier.degradations().isEmpty());
    }

    /** (i) A provider that does not say whether the output is spent is not a finding: kept, no WARN. */
    @Test
    void anUnknownLivenessWithTheRightHashIsKeptWithoutAWarn() {
        String key = PREFIX + "loan-claim-action";
        var configuration = configured(claimOnly(in(5)));
        var verifier = verifier(REGISTRY, configuration,
                new Lookup((tx, ix) -> published(REGISTRY.getLoanClaimActionScriptHash(), Liveness.UNKNOWN)), false);

        List<ILoggingEvent> logs = verifyCapturing(verifier);

        assertEquals(in(5), configuration.getReferenceScripts().loanClaimAction());
        assertTrue(warningsNaming(logs, key).isEmpty(), "no per-coordinate WARN: " + warningsNaming(logs, key));
        assertTrue(verifier.degradations().isEmpty());

        // must still: the same answer with SPENT is read, and drops.
        var spent = configured(claimOnly(in(5)));
        verifyCapturing(verifier(REGISTRY, spent,
                new Lookup((tx, ix) -> published(REGISTRY.getLoanClaimActionScriptHash(), Liveness.SPENT)), false));
        assertNull(spent.getReferenceScripts().loanClaimAction());
    }

    // ======================================================================================
    // (j), (k) — "we could not ask", and its opt-in
    // ======================================================================================

    /**
     * (j) D1/D2: every status other than 400/404, a null Result and a throw mean "the provider could not
     * be asked". The slot is KEPT as configured, with one UNVERIFIED WARN that names the reason.
     */
    @Test
    void aTransientFailureKeepsTheSlotWithOneUnverifiedWarn() {
        String key = PREFIX + "loan-claim-action";
        Map<String, Answer> transients = new java.util.LinkedHashMap<>();
        for (int code : new int[]{401, 402, 403, 418, 429, 500, 502}) {
            transients.put("HTTP " + code, (tx, ix) -> status(code));
        }
        transients.put("no response", (tx, ix) -> null);
        transients.put("connect timed out", (tx, ix) -> {
            throw new RuntimeException("connect timed out");
        });

        for (Map.Entry<String, Answer> transientFailure : transients.entrySet()) {
            var configuration = configured(claimOnly(in(5)));
            var verifier = verifier(REGISTRY, configuration, new Lookup(transientFailure.getValue()), false);

            List<ILoggingEvent> logs = verifyCapturing(verifier);

            String reason = transientFailure.getKey();
            assertEquals(in(5), configuration.getReferenceScripts().loanClaimAction(),
                    reason + " must keep the slot as configured");
            List<String> named = warningsNaming(logs, key);
            assertEquals(1, named.size(), reason + ": " + named);
            String warn = named.get(0);
            assertTrue(warn.startsWith(LoansReferenceScriptVerifier.UNVERIFIED), reason + ": " + warn);
            assertTrue(warn.contains(coordinate(in(5))), reason + ": " + warn);
            assertTrue(warn.contains(reason), "the reason must be named: " + warn);
            assertFalse(warn.contains(LoansReferenceScriptVerifier.DROPPED), reason + ": " + warn);
            assertEquals(List.of(warn), verifier.degradations());
        }

        // must still: one variable apart, a 404 is an answer and drops.
        var control = configured(claimOnly(in(5)));
        verifyCapturing(verifier(REGISTRY, control, new Lookup((tx, ix) -> status(404)), false));
        assertNull(control.getReferenceScripts().loanClaimAction());
    }

    /** (k) D3: an operator who set fail-on-unreachable asked for the throw — only for "could not ask". */
    @Test
    void failOnUnreachableThrowsForATransientFailureButNotForADrop() {
        var transientFailure = verifier(REGISTRY, configured(claimOnly(in(5))),
                new Lookup((tx, ix) -> status(502)), true);
        LoansReferenceScriptVerifier.OptInUnreachableFailure thrown = assertThrows(
                LoansReferenceScriptVerifier.OptInUnreachableFailure.class, transientFailure::verify);
        assertTrue(thrown.getMessage().contains("Cannot verify"), thrown.getMessage());

        var dropped = configured(claimOnly(in(5)));
        var dropOnly = verifier(REGISTRY, dropped,
                new Lookup((tx, ix) -> published(FOREIGN_SCRIPT_HASH, Liveness.UNSPENT)), true);
        assertDoesNotThrow(dropOnly::verify, "a DROPPED coordinate is an answer, not 'unreachable'");
        assertNull(dropped.getReferenceScripts().loanClaimAction());
    }

    // ======================================================================================
    // (l), (m), (n), (o) — the pass's own mechanics
    // ======================================================================================

    /**
     * (l) A convert coordinate configured WITHOUT {@code loans.minswap.*}: the node derives no hash for
     * it, so there is nothing to compare against. No lookup for that slot, dropped, no throw — and the
     * other configured slot is still looked up and kept.
     */
    @Test
    void aSlotThisNodeDerivesNoHashForIsDroppedWithoutALookup() {
        assertNull(REGISTRY.getLmLiquidateAndConvertActionScriptHash(),
                "precondition: without loans.minswap.* the convert action derives nothing");
        var configuration = configured(scripts(null, null, null, null, in(5), null, null, null, in(9)));
        Lookup lookup = new Lookup((tx, ix) -> {
            if ((tx + "#" + ix).equals(coordinate(in(9)))) {
                throw new AssertionError("the slot with no derived hash was looked up");
            }
            return published(REGISTRY.getLoanClaimActionScriptHash(), Liveness.UNSPENT);
        });
        var verifier = verifier(REGISTRY, configuration, lookup, false);

        verifyCapturing(verifier);

        assertEquals(List.of(coordinate(in(5))), lookup.asked, "only the slot with a derived hash is asked");
        assertNull(configuration.getReferenceScripts().lmLiquidateAndConvertAction());
        assertEquals(in(5), configuration.getReferenceScripts().loanClaimAction(), "must still keep the other");
        assertTrue(verifier.degradations().get(0).contains(LoansReferenceScriptVerifier.DROPPED)
                && verifier.degradations().get(0).contains("lm-liquidate-and-convert-action"),
                verifier.degradations().toString());
    }

    /**
     * (m) Two keys at one coordinate: one lookup, each key judged against its OWN derived hash. The
     * transaction hash is compared case-insensitively, so an upper-case spelling is the same out-ref.
     */
    @Test
    void aSharedCoordinateIsLookedUpOnce() {
        TransactionInput lower = new TransactionInput("ab".repeat(32), 3);
        TransactionInput upper = new TransactionInput("AB".repeat(32), 3);
        var configuration = configured(scripts(lower, null, null, null, upper, null, null, null, null));
        Lookup lookup = new Lookup((tx, ix) -> published(REGISTRY.getLoanPolicyId(), Liveness.UNSPENT));
        var verifier = verifier(REGISTRY, configuration, lookup, false);

        verifyCapturing(verifier);

        assertEquals(1, lookup.asked.size(), "one out-ref, one lookup: " + lookup.asked);
        assertEquals(lower, configuration.getReferenceScripts().loan(), "the loan key matches and is kept");
        assertNull(configuration.getReferenceScripts().loanClaimAction(),
                "the claim key, at the same output, publishes the wrong validator and is dropped");
    }

    /**
     * (n) D6: after the first lookup that THROWS, the provider is not asked again in this pass. Every
     * coordinate is then UNVERIFIED and kept — the unasked ones saying so.
     */
    @Test
    void theFirstTransportFailureStopsFurtherLookups() {
        var six = scripts(in(1), in(2), in(3), in(4), in(5), in(6), null, null, null);
        var configuration = configured(six);
        Lookup lookup = new Lookup((tx, ix) -> {
            throw new java.net.ConnectException("Connection refused");
        });
        var verifier = verifier(REGISTRY, configuration, lookup, false);

        verifyCapturing(verifier);

        assertEquals(1, lookup.asked.size(), "the provider is asked once: " + lookup.asked);
        assertEquals(six, configuration.getReferenceScripts(), "every coordinate kept");
        assertEquals(6, verifier.degradations().size(), verifier.degradations().toString());
        assertTrue(verifier.degradations().stream().allMatch(d -> d.startsWith(LoansReferenceScriptVerifier.UNVERIFIED)));
        assertEquals(5, verifier.degradations().stream().filter(d -> d.contains("not asked")).count(),
                verifier.degradations().toString());
    }

    /**
     * (o) Any other fault inside the pass: logged at ERROR, NOTHING narrowed, one degradation, no throw.
     * The "must still": the same configuration, with a registry that derives, is narrowed.
     */
    @Test
    void aFaultInsideThePassNarrowsNothingAndIsRecorded() {
        LoansContractRegistry exploding = Mockito.mock(LoansContractRegistry.class);
        Mockito.when(exploding.isConfigured()).thenReturn(true);
        Mockito.when(exploding.getLoanPolicyId()).thenThrow(new IllegalStateException("derivation exploded"));
        var configuration = configured(allNine());
        Lookup lookup = new Lookup((tx, ix) -> published(FOREIGN_SCRIPT_HASH, Liveness.UNSPENT));
        var verifier = verifier(exploding, configuration, lookup, false);

        List<ILoggingEvent> logs = verifyCapturing(verifier);

        assertEquals(allNine(), configuration.getReferenceScripts(), "nothing may be narrowed");
        assertEquals(1, verifier.degradations().size(), verifier.degradations().toString());
        assertTrue(verifier.degradations().get(0).contains("verification did not complete"),
                verifier.degradations().toString());
        assertTrue(at(logs, Level.ERROR).stream().anyMatch(e -> e.getFormattedMessage().contains("derivation exploded")),
                "the fault is logged at ERROR");

        var control = configured(allNine());
        verifyCapturing(verifier(FULL, control, new Lookup((tx, ix) -> published(FOREIGN_SCRIPT_HASH,
                Liveness.UNSPENT)), false));
        assertEquals(LiquidateTransactionBuilder.ReferenceScripts.none(), control.getReferenceScripts());
    }

    // ======================================================================================
    // (p) narrowing only drops
    // ======================================================================================

    @Test
    void narrowingOnlyDropsAndVerifyingTwiceGivesTheSameResult() {
        var configuration = configured(claimOnly(in(5)));

        IllegalArgumentException changed = assertThrows(IllegalArgumentException.class,
                () -> configuration.narrowReferenceScripts(claimOnly(in(6))));
        assertTrue(changed.getMessage().startsWith("narrowing may only drop a coordinate"), changed.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> configuration.narrowReferenceScripts(scripts(in(1), null, null, null, in(5), null, null, null, null)),
                "adding a coordinate to an unconfigured slot is not a drop");
        assertEquals(claimOnly(in(5)), configuration.getReferenceScripts(), "a refused narrowing changes nothing");

        assertDoesNotThrow(() -> configuration.narrowReferenceScripts(claimOnly(new TransactionInput(
                in(5).getTransactionId(), in(5).getIndex()))), "an equal coordinate is kept");
        assertDoesNotThrow(() -> configuration.narrowReferenceScripts(LiquidateTransactionBuilder.ReferenceScripts.none()));
        assertEquals(LiquidateTransactionBuilder.ReferenceScripts.none(), configuration.getReferenceScripts());
        assertEquals(claimOnly(in(5)), configuration.getConfiguredReferenceScripts(),
                "the configured scripts never change");
        assertDoesNotThrow(() -> configuration.narrowReferenceScripts(claimOnly(in(5))),
                "narrowing is judged against the CONFIGURED scripts, so a later pass may restore a slot");

        var twice = configured(scripts(in(1), null, null, null, in(5), null, null, null, null));
        Lookup lookup = new Lookup((tx, ix) -> published(REGISTRY.getLoanPolicyId(), Liveness.UNSPENT));
        var verifier = verifier(REGISTRY, twice, lookup, false);
        verifyCapturing(verifier);
        var first = twice.getReferenceScripts();
        List<String> firstDegradations = verifier.degradations();
        verifyCapturing(verifier);
        assertEquals(first, twice.getReferenceScripts());
        assertEquals(firstDegradations, verifier.degradations());
        assertEquals(in(1), first.loan());
        assertNull(first.loanClaimAction());
    }

    // ======================================================================================
    // (q) the summary line
    // ======================================================================================

    @Test
    void theSummaryIsAWarnWhenAnythingDegradedAndAnInfoOtherwise() {
        var degraded = verifier(REGISTRY, configured(claimOnly(in(5))),
                new Lookup((tx, ix) -> published(FOREIGN_SCRIPT_HASH, Liveness.UNSPENT)), false);
        List<ILoggingEvent> degradedLogs = verifyCapturing(degraded);
        List<String> summaries = at(degradedLogs, Level.WARN).stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("lending is NOT refused")).toList();
        assertEquals(1, summaries.size(), "one summary WARN: " + at(degradedLogs, Level.WARN));
        assertTrue(summaries.get(0).contains("1 dropped") && summaries.get(0).contains("0 unverified"),
                summaries.get(0));

        var clean = verifier(REGISTRY, configured(claimOnly(in(5))),
                new Lookup((tx, ix) -> published(REGISTRY.getLoanClaimActionScriptHash(), Liveness.UNSPENT)), false);
        List<ILoggingEvent> cleanLogs = verifyCapturing(clean);
        assertTrue(at(cleanLogs, Level.WARN).isEmpty(), "a clean pass has no WARN: " + at(cleanLogs, Level.WARN));
        assertTrue(at(cleanLogs, Level.INFO).stream().anyMatch(e -> e.getFormattedMessage().contains("1 verified")),
                "and an INFO naming the verified count: " + at(cleanLogs, Level.INFO));
    }

    // ======================================================================================
    // key coverage
    // ======================================================================================

    /**
     * ⛔ <b>THE NINTH SLOT — and its absence is the whole reason a stale coordinate here booted clean
     * and failed four walls later.</b>
     *
     * <p>Eight slots were checked against the derived hash at startup; {@code
     * lm-liquidate-and-convert-action} was checked by nothing. So {@code 56840ffb…#0}, which publishes
     * the <b>superseded</b> convert action {@code ed8d41e4…}, passed startup — and the live build then
     * withdrew from the reward address of the hash the node <em>derives</em> ({@code dc715410…}) with
     * only {@code ed8d41e4…} on offer: {@code withdraw:3 missingRequiredScripts}.
     *
     * <p>⚑ <b>The check that would have caught it is the one this class already performs for every
     * other slot</b> — compare the PUBLISHED hash against the DERIVED one. The coordinate had been
     * confirmed only to resolve and to carry <em>a</em> script, which is a different claim.
     *
     * <p>⚠ Asserted on the expectation MAP rather than by reaching chain, exactly as the sibling
     * assertions here do: the value under test is which keys are covered, not what the chain says.
     */
    @Test
    void theConvertActionSlotIsVerifiedLikeTheOtherEight() {
        assertTrue(expectationKeys().contains(
                        "loans.liquidation.reference-scripts.lm-liquidate-and-convert-action"),
                "the convert action is not verified at startup, so a stale coordinate there boots "
                        + "clean and fails at build with missingRequiredScripts. Covered keys: "
                        + expectationKeys());
    }

    /** Every named slot configured, so the covered-key set is the only variable. */
    private static Set<String> expectationKeys() {
        return new LoansReferenceScriptVerifier(REGISTRY, configured(allNine()),
                (tx, ix) -> {
                    throw new UnsupportedOperationException("expectations() must not reach the chain");
                }, false).expectations().keySet();
    }

    /** ⚠ Proof of harness: the reader must see the slots that ARE covered, or the test above is vacuous. */
    @Test
    void theExpectationReaderSeesEveryConfiguredSlot() {
        assertEquals(9, expectationKeys().size(),
                "nine named reference-script slots exist and all nine must be verified: "
                        + expectationKeys());
        assertEquals(Set.copyOf(KEYS), expectationKeys());
        assertNotNull(REGISTRY);
    }
}
