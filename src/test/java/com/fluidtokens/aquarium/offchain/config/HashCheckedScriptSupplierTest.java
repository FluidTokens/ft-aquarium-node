package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-134 B3b: the script supplier production prices reference scripts with. Every property here is a
 * way it could hand cardano-client-lib something other than the verified bytes of the script asked for:
 * an empty answer (the reference-script fee charged as zero, CCL trap 9), the wrong script, or a
 * remembered failure.
 */
class HashCheckedScriptSupplierTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();
    private static final PlutusScript LOAN = REGISTRY.getLoanScript();
    private static final PlutusScript CLAIM = REGISTRY.getLoanClaimActionScript();
    private static final String LOAN_HASH = hash(LOAN);

    /** A provider that answers from a queue and counts how often it was asked. */
    private static final class ScriptedProvider implements ScriptSupplier {
        final AtomicInteger calls = new AtomicInteger();
        final Deque<Supplier<Optional<PlutusScript>>> answers = new ArrayDeque<>();

        ScriptedProvider then(Supplier<Optional<PlutusScript>> answer) {
            answers.add(answer);
            return this;
        }

        @Override
        public Optional<PlutusScript> getScript(String scriptHash) {
            calls.incrementAndGet();
            return answers.isEmpty() ? Optional.empty() : answers.poll().get();
        }
    }

    @Test
    void aNullOrBlankHashAnswersEmptyWithoutAskingTheProvider() {
        ScriptedProvider provider = new ScriptedProvider().then(() -> Optional.of(LOAN));
        HashCheckedScriptSupplier supplier = new HashCheckedScriptSupplier(provider);

        assertTrue(supplier.getScript(null).isEmpty(),
                "null is how cardano-client-lib asks about a reference input that carries no script");
        assertTrue(supplier.getScript("").isEmpty());
        assertTrue(supplier.getScript("   ").isEmpty());
        assertEquals(0, provider.calls.get(), "a blank hash must never reach the provider");
    }

    @Test
    void aVerifiedScriptIsFetchedOnceAndThenServedFromMemory() {
        ScriptedProvider provider = new ScriptedProvider().then(() -> Optional.of(LOAN));
        HashCheckedScriptSupplier supplier = new HashCheckedScriptSupplier(provider);

        assertSame(LOAN, supplier.getScript(LOAN_HASH).orElseThrow());
        assertSame(LOAN, supplier.getScript(LOAN_HASH).orElseThrow(),
                "the second ask must be served from memory, not from an empty provider queue");
        assertSame(LOAN, supplier.getScript(LOAN_HASH.toUpperCase()).orElseThrow(),
                "a hash is hex: its case does not name a different script");
        assertEquals(1, provider.calls.get(), "one provider call per hash, ever");
    }

    @Test
    void aScriptWhoseHashIsNotTheOneAskedForIsRefusedAndNotRemembered() {
        ScriptedProvider provider = new ScriptedProvider()
                .then(() -> Optional.of(CLAIM))
                .then(() -> Optional.of(LOAN));
        HashCheckedScriptSupplier supplier = new HashCheckedScriptSupplier(provider);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> supplier.getScript(LOAN_HASH));
        assertTrue(refused.getMessage().contains(LOAN_HASH) && refused.getMessage().contains(hash(CLAIM)),
                "the refusal must name both the requested and the returned hash: " + refused.getMessage());

        assertSame(LOAN, supplier.getScript(LOAN_HASH).orElseThrow(),
                "the wrong script must not have been memoised: the next ask goes back to the provider");
        assertEquals(2, provider.calls.get());
    }

    @Test
    void anEmptyProviderAnswerThrowsNamingTrapNineInsteadOfAnsweringEmpty() {
        ScriptedProvider provider = new ScriptedProvider()
                .then(Optional::empty)
                .then(() -> Optional.of(LOAN));
        HashCheckedScriptSupplier supplier = new HashCheckedScriptSupplier(provider);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> supplier.getScript(LOAN_HASH),
                "an empty answer would make cardano-client-lib charge the reference-script fee as zero");
        assertTrue(refused.getMessage().contains("trap 9") && refused.getMessage().contains("zero"),
                "the refusal must say why empty is unsafe: " + refused.getMessage());

        assertSame(LOAN, supplier.getScript(LOAN_HASH).orElseThrow(), "nothing was memoised by the miss");
        assertEquals(2, provider.calls.get());
    }

    @Test
    void aProviderExceptionPropagatesAndALaterSuccessIsRemembered() {
        RuntimeException outage = new RuntimeException("blockfrost 503");
        ScriptedProvider provider = new ScriptedProvider()
                .then(() -> {
                    throw outage;
                })
                .then(() -> Optional.of(LOAN));
        HashCheckedScriptSupplier supplier = new HashCheckedScriptSupplier(provider);

        assertSame(outage, assertThrows(RuntimeException.class, () -> supplier.getScript(LOAN_HASH)));
        assertSame(LOAN, supplier.getScript(LOAN_HASH).orElseThrow());
        assertSame(LOAN, supplier.getScript(LOAN_HASH).orElseThrow());
        assertEquals(2, provider.calls.get(), "the failure was not remembered; the success was");
    }

    private static String hash(PlutusScript script) {
        try {
            return HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
