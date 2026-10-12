package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Checks, once at startup, every configured {@code loans.liquidation.reference-scripts.*} coordinate
 * against chain — and DROPS, with one loud WARN, each one that cannot be used, so its validator travels
 * inline from the vendored blueprint instead (FAB-108).
 * <p>
 * A reference-script coordinate is a deployment coordinate, and it goes stale the way the config NFT
 * policy ids {@link LoansConfigVerifier} checks do: FluidTokens redeploys v4, or spends a publication,
 * and the coordinate in {@code application.yaml} now names <em>somebody else's</em> validator, or
 * nothing. A transaction built against it would fail evaluation. Dropping the coordinate costs only
 * size: every liquidation route already reads a {@code null} slot as "attach inline", and a transaction
 * that then exceeds {@code maxTxSize} is refused by the existing {@code TX_TOO_LARGE} veto.
 *
 * <h2>⛔ It no longer closes the lending gate</h2>
 * Under FAB-115 a mismatch here closed {@link LendingConfigGate}, refusing every Lending v4 transaction
 * over one stale coordinate. Giovanni, 2026-10-12: <i>"we shouldn't stop anything (if possible), just
 * write warns in the logs quite loudly"</i>. This class holds no gate. {@link LoansConfigVerifier} still
 * closes it for config-datum mismatches, which is a different failure: those mean the derivation itself
 * is wrong, and no inline validator can fix that.
 *
 * <h2>Three outcomes per coordinate</h2>
 * <ul>
 *   <li><b>Usable</b> — the output exists, publishes the hash this node derives for that key, and is
 *       unspent (or its liveness is unknown). Kept.</li>
 *   <li><b>{@value #DROPPED}</b> — a positive finding: the output is absent or rejected (HTTP 404/400),
 *       spent, carries no reference script, publishes another hash, or the node derives no hash for that
 *       key. One WARN; the slot is nulled in the live configuration.</li>
 *   <li><b>{@value #UNVERIFIED}</b> — the provider could not be asked. One WARN; the slot is KEPT.</li>
 * </ul>
 * ⚠ <b>Ambiguity falls on the recoverable side.</b> Only a positive finding drops. A wrong coordinate
 * kept as UNVERIFIED is still not a funds risk: submission requires a passing evaluation inside
 * {@code build()}, which needs both the published hash and the bytes (CCL trap 13), so it is refused
 * before anything is sent.
 */
@Service
@Slf4j
public class LoansReferenceScriptVerifier {

    /** The prefix of the WARN for a coordinate this pass found unusable and dropped. Greppable. */
    public static final String DROPPED = "REFERENCE_SCRIPT_DROPPED";

    /** The prefix of the WARN for a coordinate this pass could not check, kept as configured. */
    public static final String UNVERIFIED = "REFERENCE_SCRIPT_UNVERIFIED";

    /** Whether the provider says the output is still unspent. */
    public enum Liveness {
        UNSPENT, SPENT,
        /** The provider did not say — kept, because not knowing is not a finding. */
        UNKNOWN
    }

    /**
     * What the chain reports for one coordinate.
     *
     * @param referenceScriptHash the published script hash, or {@code null} when the output carries none
     * @param consumedBy          the consuming transaction when {@link Liveness#SPENT}, else {@code null}
     */
    public record PublishedOutput(String referenceScriptHash, Liveness liveness, String consumedBy) {
    }

    /**
     * Everything this class needs from a provider: one output of a creating transaction, with its
     * liveness. Narrowed to a single method so the outcome taxonomy can be driven from a test — a
     * concrete backend cannot be stubbed, and a drop nobody can test quietly becomes a log line.
     * <p>
     * Contract: a success with a {@code null} value means the transaction has no such output; an error
     * Result carries the HTTP status; a throw means the provider could not be asked.
     */
    @FunctionalInterface
    public interface PublishedOutputLookup {
        Result<PublishedOutput> lookup(String txHash, int outputIndex) throws Exception;
    }

    private final LoansContractRegistry registry;
    private final AppConfig.LiquidationConfiguration configuration;
    private final PublishedOutputLookup lookup;
    private final boolean failOnUnreachable;

    /** The WARN texts of the last pass, for the readiness banner. Immutable, replaced whole. */
    private volatile List<String> degradations = List.of();

    @Autowired
    public LoansReferenceScriptVerifier(LoansContractRegistry registry,
                                        AppConfig.LiquidationConfiguration configuration,
                                        @Value("${blockfrost.url}") String blockfrostUrl,
                                        @Value("${blockfrost.key}") String blockfrostKey,
                                        @Value("${loans.verify-config.fail-on-unreachable:false}")
                                        boolean failOnUnreachable) {
        this(registry, configuration, new BlockfrostPublishedOutputLookup(blockfrostUrl, blockfrostKey),
                failOnUnreachable);
    }

    /** The same verifier with the lookup stated, so its outcome taxonomy can be driven from a test. */
    public LoansReferenceScriptVerifier(LoansContractRegistry registry,
                                        AppConfig.LiquidationConfiguration configuration,
                                        PublishedOutputLookup lookup,
                                        boolean failOnUnreachable) {
        this.registry = registry;
        this.configuration = configuration;
        this.lookup = lookup;
        this.failOnUnreachable = failOnUnreachable;
    }

    /** The one throw left, opt-in: an operator who set fail-on-unreachable asked for it. */
    static final class OptInUnreachableFailure extends IllegalStateException {
        OptInUnreachableFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * One WARN text per coordinate the last pass DROPPED or left UNVERIFIED, in key order — empty when
     * every configured coordinate is usable, or nothing was checked.
     */
    public List<String> degradations() {
        return degradations;
    }

    /**
     * ⛔ <b>Never throws, except {@link OptInUnreachableFailure}.</b> A throw here fails the whole
     * context, scheduled payments included, over a coordinate that costs only transaction size.
     */
    @PostConstruct
    public void verify() {
        try {
            check();
        } catch (OptInUnreachableFailure e) {
            throw e;
        } catch (RuntimeException e) {
            // Any fault inside the pass — a derivation that throws, a narrowing the configuration
            // refuses. Nothing has been narrowed, so every coordinate is still what the operator set.
            String text = ("%s verification did not complete: %s: %s. Every coordinate kept as configured, "
                    + "UNVERIFIED.").formatted(UNVERIFIED, e.getClass().getSimpleName(), e.getMessage());
            degradations = List.of(text);
            log.error("⛔ Lending v4 reference scripts: {} Lending is NOT refused; a wrong coordinate fails "
                    + "evaluation before anything is submitted.", text, e);
        }
    }

    private void check() {
        if (!registry.isConfigured()) {
            degradations = List.of();
            log.info("Lending v4 is not configured: the liquidation reference scripts are not checked against "
                    + "chain (a blank deployment derives nothing to compare them with)");
            return;
        }
        Map<String, Expectation> expected = expectations();
        if (expected.isEmpty()) {
            degradations = List.of();
            log.info("No liquidation reference scripts configured; every validator will travel in "
                    + "the witness set, which is over maxTxSize for a Liquidate transaction");
            return;
        }

        // ⚠ Memoised per out-ref: two keys at one coordinate are one question to the provider, though
        // each is judged against its OWN derived hash.
        Map<String, Answer> asked = new HashMap<>();
        // D6: the first lookup that THROWS stops this pass asking. An HTTP status never trips it.
        String unreachable = null;
        Exception firstFailure = null;
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        for (Map.Entry<String, Expectation> entry : expected.entrySet()) {
            String key = entry.getKey();
            Expectation expectation = entry.getValue();
            if (expectation.derivedScriptHash() == null) {
                outcomes.put(key, Outcome.dropped("this node derives no script hash for this key (for the "
                        + "convert action, loans.minswap.* is not configured), so there is nothing to check it "
                        + "against"));
                continue;
            }
            String outRef = outRef(expectation.input());
            Answer answer = asked.get(outRef);
            if (answer == null) {
                if (unreachable != null) {
                    outcomes.put(key, Outcome.unverified(("not asked: the provider was unreachable earlier in "
                            + "this pass (%s)").formatted(unreachable)));
                    continue;
                }
                try {
                    answer = new Answer(lookup.lookup(expectation.input().getTransactionId(),
                            expectation.input().getIndex()), null);
                } catch (Exception e) {
                    answer = new Answer(null, e);
                    unreachable = e.getClass().getSimpleName() + ": " + e.getMessage();
                    firstFailure = e;
                }
                asked.put(outRef, answer);
            }
            outcomes.put(key, classify(expectation, answer));
        }

        List<String> texts = new ArrayList<>();
        Set<String> dropped = new HashSet<>();
        int unverified = 0;
        int verified = 0;
        int livenessUnknown = 0;
        for (Map.Entry<String, Outcome> entry : outcomes.entrySet()) {
            String key = entry.getKey();
            Outcome outcome = entry.getValue();
            String where = "%s (%s)".formatted(key, describe(expected.get(key).input()));
            switch (outcome.kind()) {
                case DROPPED -> {
                    String text = ("%s %s: %s. The coordinate is NOT used: that validator travels inline in the "
                            + "witness set (a larger transaction; one over maxTxSize is refused before "
                            + "submission). Fix the coordinate and restart.")
                            .formatted(DROPPED, where, outcome.reason());
                    log.warn("{}", text);
                    texts.add(text);
                    dropped.add(key);
                }
                case UNVERIFIED -> {
                    String text = ("%s %s: could not be checked: %s. Kept as configured; if it is wrong, "
                            + "evaluation refuses the transaction before submission.")
                            .formatted(UNVERIFIED, where, outcome.reason());
                    log.warn("{}", text);
                    texts.add(text);
                    unverified++;
                }
                case USABLE_LIVENESS_UNKNOWN -> {
                    log.info("{} publishes the derived script hash; the provider did not say whether it is "
                            + "still unspent", where);
                    verified++;
                    livenessUnknown++;
                }
                case USABLE -> verified++;
            }
        }

        if (unverified > 0 && failOnUnreachable) {
            throw new OptInUnreachableFailure("Cannot verify liquidation reference scripts "
                    + "(loans.verify-config.fail-on-unreachable is set): " + String.join("; ", texts),
                    firstFailure);
        }

        LiquidateTransactionBuilder.ReferenceScripts configured = configuration.getConfiguredReferenceScripts();
        configuration.narrowReferenceScripts(new LiquidateTransactionBuilder.ReferenceScripts(
                keep(dropped, LOAN, configured.loan()),
                keep(dropped, LOAN_SPEND, configured.loanSpend()),
                keep(dropped, LENDER_MANAGER, configured.lenderManager()),
                keep(dropped, LENDER_MANAGER_SPEND, configured.lenderManagerSpend()),
                keep(dropped, LOAN_CLAIM_ACTION, configured.loanClaimAction()),
                keep(dropped, LM_LIQUIDATE_ACTION, configured.lmLiquidateAction()),
                keep(dropped, ASSET_MANAGER, configured.assetManager()),
                keep(dropped, LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION, configured.lmLiquidateAndPayInAdvanceAction()),
                keep(dropped, LM_LIQUIDATE_AND_CONVERT_ACTION, configured.lmLiquidateAndConvertAction())));
        degradations = List.copyOf(texts);

        if (!texts.isEmpty()) {
            log.warn("⚠ Lending v4 reference scripts: {} dropped, {} unverified, {} verified ({} with liveness "
                            + "unknown) of {} configured — lending is NOT refused. A dropped validator travels "
                            + "inline; see the per-coordinate lines above.",
                    dropped.size(), unverified, verified, livenessUnknown, expected.size());
        } else {
            log.info("Lending v4 reference scripts verified against chain: {} verified ({} with liveness "
                    + "unknown) — every configured coordinate publishes its derived script hash", verified,
                    livenessUnknown);
        }
    }

    private static TransactionInput keep(Set<String> dropped, String key, TransactionInput configured) {
        return dropped.contains(key) ? null : configured;
    }

    /**
     * One coordinate's verdict, from the provider's answer. ⚠ Only 400 and 404 are ANSWERS (D2): every
     * other status, a missing Result and a throw mean "could not be asked", which keeps the slot.
     */
    private static Outcome classify(Expectation expectation, Answer answer) {
        if (answer.thrown() != null) {
            return Outcome.unverified(answer.thrown().getClass().getSimpleName() + ": "
                    + answer.thrown().getMessage());
        }
        Result<PublishedOutput> result = answer.result();
        if (result == null) {
            return Outcome.unverified("no response from the provider");
        }
        if (!result.isSuccessful()) {
            int code = result.code();
            if (code == 404) {
                // lookup-negatives §1: a 404 may be the WRONG NETWORK rather than a stale coordinate.
                return Outcome.dropped(("HTTP 404: the creating transaction does not exist on this network — "
                        + "the coordinate is stale, or from another network (%s)").formatted(result.getResponse()));
            }
            if (code == 400) {
                return Outcome.dropped("HTTP 400: the provider rejected the coordinate (%s)"
                        .formatted(result.getResponse()));
            }
            return Outcome.unverified("HTTP %d: %s".formatted(code, result.getResponse()));
        }

        PublishedOutput output = result.getValue();
        if (output == null) {
            return Outcome.dropped("the creating transaction has no such output — the coordinate is stale, "
                    + "or from another network");
        }
        String published = output.referenceScriptHash();
        if (published == null || published.isBlank()) {
            return Outcome.dropped("the output carries no reference script");
        }
        if (output.liveness() == Liveness.SPENT) {
            return Outcome.dropped("the output was spent by %s (it published %s)"
                    .formatted(output.consumedBy(), published));
        }
        if (!expectation.derivedScriptHash().equalsIgnoreCase(published)) {
            return Outcome.dropped("the output publishes %s, but this node derives %s for this key"
                    .formatted(published, expectation.derivedScriptHash()));
        }
        return output.liveness() == Liveness.UNKNOWN ? Outcome.usableLivenessUnknown() : Outcome.usable();
    }

    private static String outRef(TransactionInput input) {
        return input.getTransactionId().toLowerCase(Locale.ROOT) + "#" + input.getIndex();
    }

    private static String describe(TransactionInput input) {
        return input.getTransactionId() + "#" + input.getIndex();
    }

    /** One configured coordinate and the script hash this repo says must be at it. */
    record Expectation(TransactionInput input, String derivedScriptHash) {
    }

    /** What the provider said for one out-ref, or what it threw. */
    private record Answer(Result<PublishedOutput> result, Exception thrown) {
    }

    private enum Kind { USABLE, USABLE_LIVENESS_UNKNOWN, DROPPED, UNVERIFIED }

    private record Outcome(Kind kind, String reason) {
        static Outcome usable() {
            return new Outcome(Kind.USABLE, null);
        }

        static Outcome usableLivenessUnknown() {
            return new Outcome(Kind.USABLE_LIVENESS_UNKNOWN, null);
        }

        static Outcome dropped(String reason) {
            return new Outcome(Kind.DROPPED, reason);
        }

        static Outcome unverified(String reason) {
            return new Outcome(Kind.UNVERIFIED, reason);
        }
    }

    private static final String PREFIX = "loans.liquidation.reference-scripts.";
    private static final String LOAN = PREFIX + "loan";
    private static final String LOAN_SPEND = PREFIX + "loan-spend";
    private static final String LENDER_MANAGER = PREFIX + "lender-manager";
    private static final String LENDER_MANAGER_SPEND = PREFIX + "lender-manager-spend";
    private static final String LOAN_CLAIM_ACTION = PREFIX + "loan-claim-action";
    private static final String LM_LIQUIDATE_ACTION = PREFIX + "lm-liquidate-action";
    private static final String ASSET_MANAGER = PREFIX + "asset-manager";
    private static final String LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION =
            PREFIX + "lm-liquidate-and-pay-in-advance-action";
    private static final String LM_LIQUIDATE_AND_CONVERT_ACTION = PREFIX + "lm-liquidate-and-convert-action";

    /**
     * The configured subset, keyed by the property an operator would have to fix. Unset coordinates
     * are absent rather than checked: "not published" is a legal configuration, it just makes the
     * transaction larger. Read from the CONFIGURED scripts, never the narrowed ones, so a second pass
     * checks what the operator set rather than what the first pass left.
     * <p>
     * Package-private so a test can assert WHICH SLOTS ARE COVERED. That set is the thing that was
     * wrong — the convert action was absent, so a stale coordinate there was the one kind that boots
     * clean — and a set is only assertable if it can be read.
     */
    Map<String, Expectation> expectations() {
        LiquidateTransactionBuilder.ReferenceScripts scripts = configuration.getConfiguredReferenceScripts();
        Map<String, Expectation> expected = new LinkedHashMap<>();
        put(expected, LOAN, scripts.loan(), registry.getLoanPolicyId());
        put(expected, LOAN_SPEND, scripts.loanSpend(), registry.getLoanSpendScriptHash());
        put(expected, LENDER_MANAGER, scripts.lenderManager(), registry.getLenderManagerWithdrawScriptHash());
        put(expected, LENDER_MANAGER_SPEND, scripts.lenderManagerSpend(),
                registry.getLenderManagerSpendScriptHash());
        put(expected, LOAN_CLAIM_ACTION, scripts.loanClaimAction(), registry.getLoanClaimActionScriptHash());
        put(expected, LM_LIQUIDATE_ACTION, scripts.lmLiquidateAction(), registry.getLmLiquidateActionScriptHash());
        put(expected, ASSET_MANAGER, scripts.assetManager(), registry.getAssetManagerWithdrawScriptHash());
        // ⛔ THE NINTH SLOT, ADDED 2026-09-05 — and its absence is why a stale coordinate here was the
        // ONE kind that boots clean. Every other slot is checked against the derived hash at startup;
        // this one was checked by nothing, so `56840ffb…#0` — which publishes the SUPERSEDED convert
        // action ed8d41e4… — passed startup and failed at build with `withdraw:3 missingRequiredScripts`,
        // four walls deep into a live mainnet debug.
        //
        // ⚠ The derived hash is null when loans.minswap.* is not configured. put() skips only a null
        // COORDINATE, not a null hash: a convert coordinate configured WITHOUT loans.minswap.* is
        // DROPPED without a lookup (there is nothing to compare it with). Leave it blank on a node that
        // cannot convert.
        put(expected, LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION, scripts.lmLiquidateAndPayInAdvanceAction(),
                registry.getLmLiquidateAndPayInAdvanceActionScriptHash());
        put(expected, LM_LIQUIDATE_AND_CONVERT_ACTION, scripts.lmLiquidateAndConvertAction(),
                registry.getLmLiquidateAndConvertActionScriptHash());

        return expected;
    }

    private static void put(Map<String, Expectation> expected, String key, TransactionInput input,
                            String derivedScriptHash) {
        if (input != null) {
            expected.put(key, new Expectation(input, derivedScriptHash));
        }
    }
}
