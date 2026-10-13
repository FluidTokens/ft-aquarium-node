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
import com.fluidtokens.aquarium.offchain.service.loans.CompoundTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * FAB-108 F3: the memo is keyed by the whole OUT-REF, not the transaction. Two named slots at two
     * outputs of ONE transaction, each publishing its own derived hash, are two questions — and both are
     * kept. A memo keyed by tx hash alone would answer the second slot with the first one's output, and
     * drop it for publishing the wrong validator.
     */
    @Test
    void twoSlotsAtTwoOutputsOfOneTransactionAreTwoLookupsAndBothKept() {
        TransactionInput first = new TransactionInput("ab".repeat(32), 0);
        TransactionInput second = new TransactionInput("ab".repeat(32), 1);
        var configured = scripts(first, null, null, null, second, null, null, null, null);
        var configuration = configured(configured);
        assertNotEquals(REGISTRY.getLoanPolicyId(), REGISTRY.getLoanClaimActionScriptHash(),
                "precondition: the two slots publish different hashes");
        Lookup lookup = new Lookup((tx, ix) -> published(
                ix == 0 ? REGISTRY.getLoanPolicyId() : REGISTRY.getLoanClaimActionScriptHash(), Liveness.UNSPENT));
        var verifier = verifier(REGISTRY, configuration, lookup, false);

        verifyCapturing(verifier);

        assertEquals(List.of(coordinate(first), coordinate(second)), lookup.asked,
                "one transaction, two outputs: two lookups");
        assertEquals(configured, configuration.getReferenceScripts(), "both slots publish their own hash and are kept");
        assertTrue(verifier.degradations().isEmpty(), verifier.degradations().toString());
    }

    // ======================================================================================
    // FAB-108 F1 — the Blockfrost key reaches no text, through the REAL lookup
    // ======================================================================================

    /** A key JDK 21 refuses as a header value; every part of it is searched for. */
    private static final String KEY_HEAD = "previewSENTINELverifierkey0000000";
    private static final String KEY_TAIL = "tailSENTINELverifierkey0000";
    private static final String BROKEN_KEY = KEY_HEAD + "a\nb" + KEY_TAIL;
    private static final List<String> KEY_FRAGMENTS = List.of(KEY_HEAD, KEY_TAIL, "a\nb");

    /** A loopback port nothing listens on: nothing here may reach a real network. */
    private static String unboundLoopbackUrl() throws java.io.IOException {
        int port;
        try (var socket = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        return "http://127.0.0.1:" + port + "/api/v0";
    }

    private static void assertNoKeyIn(String where, String text) {
        for (String fragment : KEY_FRAGMENTS) {
            assertFalse(text != null && text.contains(fragment), "the Blockfrost key leaked into " + where + ": " + text);
        }
    }

    private static void assertNoKeyIn(String where, Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            assertNoKeyIn(where, t.getMessage());
            assertNoKeyIn(where, String.valueOf(t));
        }
    }

    private static void assertNoKeyIn(List<ILoggingEvent> events) {
        for (ILoggingEvent event : events) {
            assertNoKeyIn("a " + event.getLevel() + " log line", event.getFormattedMessage());
            for (var proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                assertNoKeyIn("a logged exception", proxy.getMessage());
            }
        }
    }

    /**
     * ⛔ FAB-108 F1. On JDK 21 a key with a line break inside it made {@code HttpRequest.Builder.header}
     * throw {@code IllegalArgumentException("invalid header value: \"<key>\"")}, and the verifier put that
     * message into the UNVERIFIED WARN, {@code degradations()} and the opt-in failure. Driven through the
     * REAL {@link BlockfrostPublishedOutputLookup}: no log line at any level, no degradation and no thrown
     * message may contain any part of the key — and the slot must still be KEPT, UNVERIFIED, with the
     * reason saying the value was withheld.
     */
    @Test
    void aBlockfrostKeyThatCannotBeAHeaderReachesNoLogNoDegradationAndNoThrownMessage() throws Exception {
        String url = unboundLoopbackUrl();
        String key = PREFIX + "loan-claim-action";

        var configuration = configured(claimOnly(in(5)));
        var verifier = new LoansReferenceScriptVerifier(REGISTRY, configuration,
                new BlockfrostPublishedOutputLookup(url, BROKEN_KEY), false);
        Logger logger = (Logger) LoggerFactory.getLogger(LoansReferenceScriptVerifier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(verifier::verify);
        } finally {
            logger.detachAppender(appender);
        }

        assertNoKeyIn(appender.list);
        verifier.degradations().forEach(d -> assertNoKeyIn("degradations()", d));
        // must still: the coordinate could not be checked, so it is KEPT, with one UNVERIFIED line.
        assertEquals(in(5), configuration.getReferenceScripts().loanClaimAction(), "an unverified slot is kept");
        assertEquals(1, verifier.degradations().size(), verifier.degradations().toString());
        String degradation = verifier.degradations().get(0);
        assertTrue(degradation.startsWith(LoansReferenceScriptVerifier.UNVERIFIED) && degradation.contains(key)
                && degradation.contains("value withheld"), degradation);

        // with fail-on-unreachable, the thrown message (and its cause chain) carries no part of it either.
        var failing = new LoansReferenceScriptVerifier(REGISTRY, configured(claimOnly(in(5))),
                new BlockfrostPublishedOutputLookup(url, BROKEN_KEY), true);
        ListAppender<ILoggingEvent> failingLogs = new ListAppender<>();
        failingLogs.start();
        logger.addAppender(failingLogs);
        LoansReferenceScriptVerifier.OptInUnreachableFailure thrown;
        try {
            thrown = assertThrows(LoansReferenceScriptVerifier.OptInUnreachableFailure.class, failing::verify);
        } finally {
            logger.detachAppender(failingLogs);
        }
        assertNoKeyIn("the opt-in failure", thrown);
        assertNoKeyIn(failingLogs.list);
        assertTrue(thrown.getMessage().contains(key) && thrown.getMessage().contains("value withheld"),
                "must still name the coordinate and why: " + thrown.getMessage());
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
    // FAB-108-2 — the compound list, checked against the ELEVEN compound validators
    // ======================================================================================

    private static final String COMPOUND_KEY = "loans.compound.reference-scripts";

    /** Index of {@code lmCompoundAction} among the builder's eleven (four spends, then the seven withdrawals). */
    private static final int LM_COMPOUND = 6;

    /**
     * The eleven scripts a compound runs, as the BUILDER names them — its four {@code general_spend} spends
     * plus {@link CompoundTransactionBuilder#withdrawCredentials} — never the verifier's own list, so a
     * validator missing from the verifier's set shows up here as a drop (VH-90).
     */
    private static List<String> builderEleven(LoansContractRegistry registry) {
        List<String> eleven = new ArrayList<>(List.of(registry.getAssetManagerSpendScriptHash(),
                registry.getLenderManagerSpendScriptHash(), registry.getPoolSpendScriptHash(),
                registry.getPoolManagerSpendScriptHash()));
        eleven.addAll(CompoundTransactionBuilder.withdrawCredentials(registry));
        return eleven;
    }

    /** Compound coordinate {@code i}: {@code c0c0…#0}, {@code c1c1…#0}, … — distinct from the named {@link #in}. */
    private static TransactionInput compoundIn(int i) {
        return new TransactionInput(String.format("%02x", 0xc0 + i).repeat(32), 0);
    }

    private static List<TransactionInput> elevenCoordinates() {
        List<TransactionInput> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(compoundIn(i));
        }
        return eleven;
    }

    private static String joined(List<TransactionInput> coordinates) {
        return coordinates.stream().map(LoansReferenceScriptVerifierTest::coordinate).collect(Collectors.joining(","));
    }

    /** Coordinate {@code i} of {@code coordinates} publishing the builder's {@code i}-th hash, UNSPENT. */
    private static Map<String, Result<PublishedOutput>> honestCompound(List<TransactionInput> coordinates) {
        List<String> eleven = builderEleven(REGISTRY);
        Map<String, Result<PublishedOutput>> chain = new HashMap<>();
        for (int i = 0; i < coordinates.size(); i++) {
            chain.put(coordinate(coordinates.get(i)), published(eleven.get(i), Liveness.UNSPENT));
        }
        return chain;
    }

    /** An enabled-or-not compound configuration whose raw {@code @Value} list is {@code raw}. */
    private static AppConfig.CompoundConfiguration compound(boolean enabled, String raw) {
        var configuration = new AppConfig.CompoundConfiguration(enabled, 60L, BigInteger.ZERO);
        ReflectionTestUtils.setField(configuration, "referenceScripts", raw);
        return configuration;
    }

    private static LoansReferenceScriptVerifier compoundVerifier(LoansContractRegistry registry,
                                                                 AppConfig.CompoundConfiguration compound,
                                                                 Lookup lookup) {
        return new LoansReferenceScriptVerifier(registry,
                configured(LiquidateTransactionBuilder.ReferenceScripts.none()), compound, lookup, false);
    }

    private static List<String> warnsContaining(List<ILoggingEvent> events, String text) {
        return at(events, Level.WARN).stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(text)).toList();
    }

    /** The {@code REFERENCE_SCRIPT_MISSING} WARNs. */
    private static List<String> missing(List<ILoggingEvent> events) {
        return warnsContaining(events, LoansReferenceScriptVerifier.MISSING);
    }

    private static List<String> compoundDegradations(LoansReferenceScriptVerifier verifier) {
        return verifier.degradations().stream().filter(d -> d.contains(COMPOUND_KEY)).toList();
    }

    /**
     * C1 — the control the compound tests rest on: eleven coordinates, each publishing one of the
     * builder's eleven, all unspent. Nothing degraded, eleven lookups, and the narrowed list is the
     * configured list.
     */
    @Test
    void elevenCompoundCoordinatesPublishingTheElevenAreAllKept() {
        List<TransactionInput> eleven = elevenCoordinates();
        var compound = compound(true, joined(eleven));
        Lookup lookup = chain(honestCompound(eleven));
        var verifier = compoundVerifier(REGISTRY, compound, lookup);

        List<ILoggingEvent> logs = verifyCapturing(verifier);

        assertTrue(compoundDegradations(verifier).isEmpty(), "nothing degraded: " + verifier.degradations());
        assertTrue(verifier.degradations().isEmpty(), verifier.degradations().toString());
        assertEquals(joined(eleven), compound.getReferenceScripts(), "the narrowed list is the configured list");
        assertEquals(joined(eleven), compound.getConfiguredReferenceScripts());
        assertEquals(eleven.stream().map(LoansReferenceScriptVerifierTest::coordinate).toList(), lookup.asked,
                "one lookup per coordinate, in list order");
        assertTrue(at(logs, Level.WARN).isEmpty(), "a clean pass warns about nothing: " + at(logs, Level.WARN));
    }

    /**
     * C1 with UNKNOWN liveness — the provider names each coordinate's published hash but not whether the
     * output is still unspent. That is not a finding: every coordinate is KEPT, nothing is dropped and no
     * validator is warned missing. The shared classifier keeps it on the named keys too (case (i)).
     */
    @Test
    void elevenCompoundCoordinatesWithUnknownLivenessAreAllKept() {
        List<TransactionInput> eleven = elevenCoordinates();
        List<String> hashes = builderEleven(REGISTRY);
        Map<String, Result<PublishedOutput>> chain = new HashMap<>();
        for (int i = 0; i < eleven.size(); i++) {
            chain.put(coordinate(eleven.get(i)), published(hashes.get(i), Liveness.UNKNOWN));
        }
        var compound = compound(true, joined(eleven));
        Lookup lookup = chain(chain);
        var verifier = compoundVerifier(REGISTRY, compound, lookup);

        List<ILoggingEvent> logs = verifyCapturing(verifier);

        assertEquals(joined(eleven), compound.getReferenceScripts(),
                "liveness unknown is kept: the narrowed list is the configured list");
        assertTrue(missing(logs).isEmpty(), "every validator is supplied: " + missing(logs));
        assertTrue(warnsContaining(logs, LoansReferenceScriptVerifier.DROPPED).isEmpty(),
                "nothing is dropped: " + warnsContaining(logs, LoansReferenceScriptVerifier.DROPPED));
        assertTrue(verifier.degradations().isEmpty(), verifier.degradations().toString());
        assertEquals(11, lookup.asked.size(), "each coordinate was asked: " + lookup.asked);
    }

    /**
     * C2 — ⛔ the ruling's mutation control, on the compound list: lmCompoundAction's coordinate is
     * replaced by a real, unspent output that publishes the SUPERSEDED lm_compound_action. It is DROPPED
     * (not UNVERIFIED), its validator is warned MISSING and travels inline — and the other ten are KEPT.
     */
    @Test
    void aCompoundCoordinatePublishingASupersededValidatorIsDroppedAndItsValidatorIsMissing() {
        List<TransactionInput> coordinates = new ArrayList<>(elevenCoordinates());
        coordinates.set(LM_COMPOUND, RULING_COORDINATE);
        Map<String, Result<PublishedOutput>> chain = honestCompound(elevenCoordinates());
        chain.put(coordinate(RULING_COORDINATE), published(RULING_PUBLISHED, Liveness.UNSPENT));
        assertFalse(builderEleven(REGISTRY).contains(RULING_PUBLISHED), "precondition: none of the eleven");
        var compound = compound(true, joined(coordinates));
        var verifier = compoundVerifier(REGISTRY, compound, chain(chain));

        List<ILoggingEvent> logs = verifyCapturing(verifier);

        List<String> named = warnsContaining(logs, coordinate(RULING_COORDINATE));
        assertEquals(1, named.size(), "exactly one WARN names the coordinate: " + named);
        String dropped = named.get(0);
        assertTrue(dropped.startsWith(LoansReferenceScriptVerifier.DROPPED), dropped);
        assertTrue(dropped.contains(COMPOUND_KEY), dropped);
        assertTrue(dropped.contains(RULING_PUBLISHED), "names the published hash: " + dropped);
        assertFalse(dropped.contains(LoansReferenceScriptVerifier.UNVERIFIED), dropped);

        String lmCompoundHash = REGISTRY.getLmCompoundActionScriptHash();
        assertEquals(builderEleven(REGISTRY).get(LM_COMPOUND), lmCompoundHash, "precondition: index 6 is it");
        List<String> missing = missing(logs);
        assertEquals(1, missing.size(), "one validator is missing: " + missing);
        assertTrue(missing.get(0).contains("lmCompoundAction") && missing.get(0).contains(lmCompoundHash)
                && missing.get(0).contains("travels INLINE"), missing.get(0));

        // must still: the other ten are kept, in the configured order.
        List<TransactionInput> ten = new ArrayList<>(coordinates);
        ten.remove(LM_COMPOUND);
        assertEquals(joined(ten), compound.getReferenceScripts(), "the other ten are kept");
        assertEquals(joined(coordinates), compound.getConfiguredReferenceScripts(), "the configured list never changes");
        assertEquals(List.of(dropped, missing.get(0)), verifier.degradations());
    }

    /**
     * C3 — the shared classifier, on the compound list: 404, SPENT and no-script are ANSWERS and drop;
     * 402, 500 and a transport failure mean "could not ask", and the coordinate is kept. A validator a
     * kept-but-unverified coordinate might supply is still warned MISSING, and the text says how many
     * coordinates could not be checked.
     */
    @Test
    void theCompoundListUsesTheSameStatusMappingAsTheNamedKeys() {
        String lmCompoundHash = REGISTRY.getLmCompoundActionScriptHash();
        TransactionInput target = compoundIn(LM_COMPOUND);
        Map<String, Answer> answers = new LinkedHashMap<>();
        answers.put("HTTP 404", (tx, ix) -> status(404));
        answers.put("SPENT", (tx, ix) -> published(lmCompoundHash, Liveness.SPENT));
        answers.put("no reference script", (tx, ix) -> published(null, Liveness.UNSPENT));
        answers.put("HTTP 402", (tx, ix) -> status(402));
        answers.put("HTTP 500", (tx, ix) -> status(500));
        answers.put("transport", (tx, ix) -> {
            throw new java.net.ConnectException("Connection refused");
        });
        Set<String> droppedCases = Set.of("HTTP 404", "SPENT", "no reference script");

        for (Map.Entry<String, Answer> answer : answers.entrySet()) {
            String label = answer.getKey();
            Map<String, Result<PublishedOutput>> honest = honestCompound(elevenCoordinates());
            Lookup lookup = new Lookup((tx, ix) -> (tx + "#" + ix).equals(coordinate(target))
                    ? answer.getValue().apply(tx, ix) : honest.get(tx + "#" + ix));
            var compound = compound(true, joined(elevenCoordinates()));
            var verifier = compoundVerifier(REGISTRY, compound, lookup);

            List<ILoggingEvent> logs = verifyCapturing(verifier);

            List<String> kept = Arrays.asList(compound.getReferenceScripts().split(","));
            List<String> named = warnsContaining(logs, coordinate(target));
            assertEquals(1, named.size(), label + ": " + named);
            List<String> missing = missing(logs).stream().filter(m -> m.contains("lmCompoundAction")).toList();
            assertEquals(1, missing.size(), label + ": lmCompoundAction is supplied by no checked coordinate: "
                    + missing(logs));
            if (droppedCases.contains(label)) {
                assertFalse(kept.contains(coordinate(target)), label + " must drop: " + kept);
                assertTrue(named.get(0).startsWith(LoansReferenceScriptVerifier.DROPPED), label + ": " + named);
                assertFalse(missing.get(0).contains("could not be checked"), label + ": " + missing.get(0));
            } else {
                assertTrue(kept.contains(coordinate(target)), label + " must keep the coordinate: " + kept);
                assertTrue(named.get(0).startsWith(LoansReferenceScriptVerifier.UNVERIFIED), label + ": " + named);
                assertFalse(named.get(0).contains(LoansReferenceScriptVerifier.DROPPED), label + ": " + named);
                // the breaker: after a transport failure the four coordinates behind it are not asked.
                int unverified = label.equals("transport") ? 5 : 1;
                assertTrue(missing.get(0).contains(unverified + " compound coordinate(s) could not be checked"),
                        label + ": " + missing.get(0));
                assertEquals(11, kept.size(), label + ": every unverified coordinate is kept: " + kept);
            }
        }
    }

    /**
     * C4 — two coordinates publishing ONE validator: the first is kept, the second DROPPED naming the
     * first. (CompoundExecutor keys by hash, so a kept duplicate would silently replace the first.)
     */
    @Test
    void aSecondCoordinatePublishingTheSameValidatorIsDropped() {
        List<TransactionInput> eleven = elevenCoordinates();
        TransactionInput second = new TransactionInput("e1".repeat(32), 0);
        List<TransactionInput> twelve = new ArrayList<>(eleven);
        twelve.add(second);
        Map<String, Result<PublishedOutput>> chain = honestCompound(eleven);
        chain.put(coordinate(second), published(REGISTRY.getPoolSpendScriptHash(), Liveness.UNSPENT));
        var compound = compound(true, joined(twelve));
        var verifier = compoundVerifier(REGISTRY, compound, chain(chain));

        List<ILoggingEvent> logs = verifyCapturing(verifier);

        assertEquals(joined(eleven), compound.getReferenceScripts(), "the first is kept, the second dropped");
        List<String> named = warnsContaining(logs, coordinate(second));
        assertEquals(1, named.size(), named.toString());
        assertTrue(named.get(0).startsWith(LoansReferenceScriptVerifier.DROPPED), named.get(0));
        assertTrue(named.get(0).contains(coordinate(eleven.get(2))), "names the coordinate that supplies it: "
                + named.get(0));
        assertTrue(missing(logs).isEmpty(), "nothing is missing: " + missing(logs));
    }

    /** C5 — the same coordinate listed twice: one lookup, and the second occurrence is dropped. */
    @Test
    void aCoordinateListedTwiceIsLookedUpOnceAndTheSecondOccurrenceDropped() {
        List<TransactionInput> eleven = elevenCoordinates();
        String raw = joined(eleven) + "," + coordinate(eleven.get(0));
        Lookup lookup = chain(honestCompound(eleven));
        var compound = compound(true, raw);
        var verifier = compoundVerifier(REGISTRY, compound, lookup);

        List<ILoggingEvent> logs = verifyCapturing(verifier);

        assertEquals(1, Collections.frequency(lookup.asked, coordinate(eleven.get(0))), lookup.asked.toString());
        assertEquals(11, lookup.asked.size(), lookup.asked.toString());
        List<String> twice = warnsContaining(logs, "listed twice");
        assertEquals(1, twice.size(), at(logs, Level.WARN).toString());
        assertTrue(twice.get(0).startsWith(LoansReferenceScriptVerifier.DROPPED)
                && twice.get(0).contains(coordinate(eleven.get(0))), twice.get(0));
        assertEquals(joined(eleven), compound.getReferenceScripts(), "must still keep the first occurrence");
    }

    /** C6 — a malformed entry is DROPPED without a lookup and without a throw; the well-formed ones are kept. */
    @Test
    void aMalformedCompoundEntryIsDroppedWithoutALookup() {
        List<TransactionInput> eleven = elevenCoordinates();
        List<String> malformed = List.of("nothex#0", "abc", "ab".repeat(32) + "#-1", "ab".repeat(32) + "#x",
                "ab".repeat(31) + "#0", "ab".repeat(32) + "#0#1");
        String raw = joined(eleven) + ", " + String.join(" , ", malformed) + ",,";
        Lookup lookup = chain(honestCompound(eleven));
        var compound = compound(true, raw);
        var verifier = compoundVerifier(REGISTRY, compound, lookup);

        List<ILoggingEvent> logs = verifyCapturing(verifier);

        assertEquals(eleven.stream().map(LoansReferenceScriptVerifierTest::coordinate).toList(), lookup.asked,
                "a malformed entry is never looked up");
        for (String entry : malformed) {
            List<String> named = warnsContaining(logs, "'" + entry + "'");
            assertEquals(1, named.size(), entry + ": " + at(logs, Level.WARN));
            assertTrue(named.get(0).startsWith(LoansReferenceScriptVerifier.DROPPED)
                    && named.get(0).contains("malformed"), named.get(0));
        }
        assertEquals(joined(eleven), compound.getReferenceScripts(), "must still keep the eleven");
        assertTrue(missing(logs).isEmpty(), missing(logs).toString());
    }

    /**
     * C7 — D4: compound disabled means the list is not checked at all: no lookup, no narrowing, no
     * degradation. The "must still": the same list, enabled, IS looked up and narrowed.
     */
    @Test
    void aDisabledCompoundPathIsNotChecked() {
        List<TransactionInput> eleven = elevenCoordinates();
        String raw = " " + String.join(" , ", eleven.stream().map(LoansReferenceScriptVerifierTest::coordinate).toList()) + " ";
        Lookup lookup = chain(honestCompound(eleven));
        var disabled = compound(false, raw);
        var verifier = compoundVerifier(REGISTRY, disabled, lookup);

        List<ILoggingEvent> logs = verifyCapturing(verifier);

        assertTrue(lookup.asked.isEmpty(), "a disabled compound path must not reach the chain: " + lookup.asked);
        assertEquals(raw, disabled.getReferenceScripts(), "and must narrow nothing");
        assertTrue(verifier.degradations().isEmpty(), verifier.degradations().toString());
        assertTrue(missing(logs).isEmpty(), missing(logs).toString());

        Lookup asked = chain(honestCompound(eleven));
        var enabled = compound(true, raw);
        verifyCapturing(compoundVerifier(REGISTRY, enabled, asked));
        assertEquals(11, asked.asked.size(), "must still check the same list when enabled");
        assertEquals(joined(eleven), enabled.getReferenceScripts(), "and narrow it to the canonical form");
    }

    /** C8 — enabled with nothing listed: every one of the eleven travels inline, and each says so. */
    @Test
    void anEnabledCompoundPathWithNoCoordinatesWarnsElevenMissing() {
        Lookup lookup = new Lookup((tx, ix) -> {
            throw new AssertionError("nothing is configured, so nothing may be looked up");
        });
        var compound = compound(true, "");
        var verifier = compoundVerifier(REGISTRY, compound, lookup);

        List<ILoggingEvent> logs = verifyCapturing(verifier);

        assertTrue(lookup.asked.isEmpty(), lookup.asked.toString());
        List<String> missing = missing(logs);
        assertEquals(11, missing.size(), missing.toString());
        for (String hash : builderEleven(REGISTRY)) {
            assertEquals(1, missing.stream().filter(m -> m.contains(hash)).count(), hash + " in " + missing);
        }
        assertEquals("", compound.getReferenceScripts());
    }

    /**
     * C9 — a coordinate under a named key AND in the compound list is one question to the provider: the
     * two passes share the out-ref memo. Each pass still judges it by its own rule, and both keep it.
     */
    @Test
    void aCoordinateSharedByANamedKeyAndTheCompoundListIsLookedUpOnce() {
        List<TransactionInput> eleven = elevenCoordinates();
        TransactionInput shared = eleven.get(1);
        assertEquals(REGISTRY.getLenderManagerSpendScriptHash(), builderEleven(REGISTRY).get(1),
                "precondition: compound index 1 is lender-manager-spend");
        var liquidation = configured(scripts(null, null, null, shared, null, null, null, null, null));
        var compound = compound(true, joined(eleven));
        Lookup lookup = chain(honestCompound(eleven));
        var verifier = new LoansReferenceScriptVerifier(REGISTRY, liquidation, compound, lookup, false);

        verifyCapturing(verifier);

        assertEquals(1, Collections.frequency(lookup.asked, coordinate(shared)), lookup.asked.toString());
        assertEquals(11, lookup.asked.size(), "eleven distinct out-refs, eleven lookups: " + lookup.asked);
        assertEquals(shared, liquidation.getReferenceScripts().lenderManagerSpend(), "the named key is kept");
        assertEquals(joined(eleven), compound.getReferenceScripts(), "and so is the compound entry");
        assertTrue(verifier.degradations().isEmpty(), verifier.degradations().toString());
    }

    /**
     * C10 — narrowing only drops, and its output is the format every other reader parses: comma-joined
     * {@code txHash#index} (StaticReferenceInputs and CompoundExecutor split the same string; CCL trap 26a).
     */
    @Test
    void compoundNarrowingOnlyDropsAndKeepsTheConfiguredFormat() {
        TransactionInput a = compoundIn(0);
        TransactionInput b = compoundIn(1);
        String raw = " " + coordinate(a) + " , " + coordinate(b) + " ";
        var compound = compound(true, raw);

        IllegalArgumentException foreign = assertThrows(IllegalArgumentException.class,
                () -> compound.narrowReferenceScripts(List.of(RULING_COORDINATE)));
        assertTrue(foreign.getMessage().startsWith("narrowing may only drop a coordinate"), foreign.getMessage());
        assertThrows(IllegalArgumentException.class, () -> compound.narrowReferenceScripts(null));
        assertEquals(raw, compound.getReferenceScripts(), "a refused narrowing changes nothing");
        assertEquals(raw, compound.getConfiguredReferenceScripts());

        compound.narrowReferenceScripts(List.of(b));
        assertEquals(coordinate(b), compound.getReferenceScripts());
        compound.narrowReferenceScripts(List.of(b, new TransactionInput(a.getTransactionId(), a.getIndex())));
        String narrowed = compound.getReferenceScripts();
        assertEquals(coordinate(a) + "," + coordinate(b), narrowed, "in the configured order");
        for (String part : narrowed.split(",")) {
            assertTrue(part.matches("[0-9a-fA-F]{64}#[0-9]+"), "every part is txHash#index: '" + part + "'");
        }
        compound.narrowReferenceScripts(List.of());
        assertEquals("", compound.getReferenceScripts(), "dropping everything leaves an empty list");
        assertEquals(raw, compound.getConfiguredReferenceScripts(), "the configured list never changes");
    }

    /**
     * C11 — a registry without {@code loans.smart-tokens-spend-script-hash} derives no pool-manager family,
     * so four of the eleven are null and there is nothing to compare the list with: no lookup, no
     * narrowing, ONE UNVERIFIED WARN naming them and the property, and no throw.
     */
    @Test
    void aNullRequiredHashSkipsTheCompoundPassWithOneUnverifiedWarn() {
        var noSmartTokens = new LoansContractRegistry(LoanFixtures.CONFIG_POLICY_ID, LoanFixtures.LM_CONFIG_POLICY_ID,
                LoanFixtures.CONFIG_ASSET_NAME, null);
        assertTrue(noSmartTokens.isConfigured(), "precondition: configured");
        assertNull(noSmartTokens.getLmCompoundActionScriptHash(), "precondition: no lmCompoundAction");
        List<TransactionInput> eleven = elevenCoordinates();
        String raw = " " + joined(eleven) + " ";
        var compound = compound(true, raw);
        Lookup lookup = chain(honestCompound(eleven));
        var verifier = compoundVerifier(noSmartTokens, compound, lookup);

        List<ILoggingEvent> logs = verifyCapturing(verifier);

        assertTrue(lookup.asked.isEmpty(), lookup.asked.toString());
        List<String> unverified = warnsContaining(logs, LoansReferenceScriptVerifier.UNVERIFIED);
        assertEquals(1, unverified.size(), unverified.toString());
        assertTrue(unverified.get(0).contains("lmCompoundAction") && unverified.get(0).contains("poolManagerPolicy")
                && unverified.get(0).contains("loans.smart-tokens-spend-script-hash"), unverified.get(0));
        assertEquals(raw, compound.getReferenceScripts(), "nothing is narrowed");
        assertTrue(missing(logs).isEmpty(), missing(logs).toString());

        Lookup asked = chain(honestCompound(eleven));
        verifyCapturing(compoundVerifier(REGISTRY, compound(true, joined(eleven)), asked));
        assertEquals(11, asked.asked.size(), "must still check the list against a registry that derives all eleven");
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
