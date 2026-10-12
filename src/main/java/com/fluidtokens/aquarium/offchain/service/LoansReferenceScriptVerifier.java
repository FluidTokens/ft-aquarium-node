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
import java.util.Collections;
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
 *
 * <h2>The compound list (FAB-108-2)</h2>
 * When {@code loans.compound.enabled=true} (D4), each {@code loans.compound.reference-scripts} coordinate is
 * checked too, through the SAME lookup, out-ref memo, circuit-breaker and answer classifier. The list names
 * no validator per coordinate, so the rule is membership: a usable coordinate publishes one of the eleven
 * {@link #compoundValidators} hashes, and the first coordinate to publish a hash supplies it. Malformed,
 * repeated, absent, spent, script-less, foreign and second-supplier coordinates are DROPPED; each of the
 * eleven that no usable coordinate supplies gets one {@value #MISSING} WARN and travels inline. The live
 * list is narrowed in place, so {@code CompoundExecutor} never resolves a dropped coordinate.
 */
@Service
@Slf4j
public class LoansReferenceScriptVerifier {

    /** The prefix of the WARN for a coordinate this pass found unusable and dropped. Greppable. */
    public static final String DROPPED = "REFERENCE_SCRIPT_DROPPED";

    /** The prefix of the WARN for a coordinate this pass could not check, kept as configured. */
    public static final String UNVERIFIED = "REFERENCE_SCRIPT_UNVERIFIED";

    /** The prefix of the WARN for a compound validator no usable coordinate supplies: it travels inline. */
    public static final String MISSING = "REFERENCE_SCRIPT_MISSING";

    private static final String COMPOUND_KEY = "loans.compound.reference-scripts";

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
    /** {@code null} when the compound list is not this verifier's concern (the four-argument test seam). */
    private final AppConfig.CompoundConfiguration compound;
    private final PublishedOutputLookup lookup;
    private final boolean failOnUnreachable;

    /** The WARN texts of the last pass, for the readiness banner. Immutable, replaced whole. */
    private volatile List<String> degradations = List.of();

    @Autowired
    public LoansReferenceScriptVerifier(LoansContractRegistry registry,
                                        AppConfig.LiquidationConfiguration configuration,
                                        AppConfig.CompoundConfiguration compound,
                                        @Value("${blockfrost.url}") String blockfrostUrl,
                                        @Value("${blockfrost.key}") String blockfrostKey,
                                        @Value("${loans.verify-config.fail-on-unreachable:false}")
                                        boolean failOnUnreachable) {
        this(registry, configuration, compound, new BlockfrostPublishedOutputLookup(blockfrostUrl, blockfrostKey),
                failOnUnreachable);
    }

    /** The named keys only, with the lookup stated — the compound list is not checked. */
    public LoansReferenceScriptVerifier(LoansContractRegistry registry,
                                        AppConfig.LiquidationConfiguration configuration,
                                        PublishedOutputLookup lookup,
                                        boolean failOnUnreachable) {
        this(registry, configuration, null, lookup, failOnUnreachable);
    }

    /** The same verifier with the lookup stated, so its outcome taxonomy can be driven from a test. */
    public LoansReferenceScriptVerifier(LoansContractRegistry registry,
                                        AppConfig.LiquidationConfiguration configuration,
                                        AppConfig.CompoundConfiguration compound,
                                        PublishedOutputLookup lookup,
                                        boolean failOnUnreachable) {
        this.registry = registry;
        this.configuration = configuration;
        this.compound = compound;
        this.lookup = lookup;
        this.failOnUnreachable = failOnUnreachable;
    }

    /**
     * The eleven validators a compound runs — four {@code general_spend} spends and seven withdraw-0
     * invocations — label → the hash this node derives, in {@code CompoundTransactionBuilder}'s order. A
     * value is {@code null} when the registry cannot derive it (no {@code loans.smart-tokens-spend-script-hash}).
     * <p>
     * ⚠ VH-90: this is the set the compound list is judged against, and it must be the set the builder
     * attaches. {@code CompoundProductionWiringTest} pins it to the scripts an all-inline compound witnesses.
     */
    public static Map<String, String> compoundValidators(LoansContractRegistry registry) {
        Map<String, String> validators = new LinkedHashMap<>();
        validators.put("assetManagerSpend", registry.getAssetManagerSpendScriptHash());
        validators.put("lenderManagerSpend", registry.getLenderManagerSpendScriptHash());
        validators.put("poolSpend", registry.getPoolSpendScriptHash());
        validators.put("poolManagerSpend", registry.getPoolManagerSpendScriptHash());
        validators.put("assetManagerWithdraw", registry.getAssetManagerWithdrawScriptHash());
        validators.put("lenderManagerWithdraw", registry.getLenderManagerWithdrawScriptHash());
        validators.put("lmCompoundAction", registry.getLmCompoundActionScriptHash());
        validators.put("poolPolicy", registry.getPoolPolicyId());
        validators.put("poolCompoundAction", registry.getPoolCompoundActionScriptHash());
        validators.put("poolManagerPolicy", registry.getPoolManagerPolicyId());
        validators.put("pmCompoundLiquidity", registry.getPmCompoundLiquidityScriptHash());
        return Collections.unmodifiableMap(validators);
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
        // ⚠ One memo and one circuit-breaker for BOTH lists: a coordinate under a named key and in the
        // compound list is one question to the provider, and a provider that is down is down for both.
        Pass pass = new Pass();
        List<String> texts = new ArrayList<>();

        Map<String, Expectation> expected = expectations();
        LiquidateTransactionBuilder.ReferenceScripts liquidationUsable = null;
        Set<String> dropped = new HashSet<>();
        int unverified = 0;
        int verified = 0;
        int livenessUnknown = 0;
        if (expected.isEmpty()) {
            log.info("No liquidation reference scripts configured; every validator will travel in "
                    + "the witness set, which is over maxTxSize for a Liquidate transaction");
        } else {
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
                Answer answer = pass.ask(expectation.input());
                outcomes.put(key, answer == null ? pass.notAsked() : classify(expectation, answer));
            }

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
                        pass.firstFailure);
            }

            LiquidateTransactionBuilder.ReferenceScripts configured = configuration.getConfiguredReferenceScripts();
            liquidationUsable = new LiquidateTransactionBuilder.ReferenceScripts(
                    keep(dropped, LOAN, configured.loan()),
                    keep(dropped, LOAN_SPEND, configured.loanSpend()),
                    keep(dropped, LENDER_MANAGER, configured.lenderManager()),
                    keep(dropped, LENDER_MANAGER_SPEND, configured.lenderManagerSpend()),
                    keep(dropped, LOAN_CLAIM_ACTION, configured.loanClaimAction()),
                    keep(dropped, LM_LIQUIDATE_ACTION, configured.lmLiquidateAction()),
                    keep(dropped, ASSET_MANAGER, configured.assetManager()),
                    keep(dropped, LM_LIQUIDATE_AND_PAY_IN_ADVANCE_ACTION, configured.lmLiquidateAndPayInAdvanceAction()),
                    keep(dropped, LM_LIQUIDATE_AND_CONVERT_ACTION, configured.lmLiquidateAndConvertAction()));
        }

        CompoundPass compoundPass = checkCompound(pass, texts);

        if (liquidationUsable != null) {
            configuration.narrowReferenceScripts(liquidationUsable);
        }
        if (compoundPass != null && compoundPass.usable() != null) {
            compound.narrowReferenceScripts(compoundPass.usable());
        }
        degradations = List.copyOf(texts);

        boolean compoundChecked = compoundPass != null && compoundPass.usable() != null;
        if (expected.isEmpty() && compoundPass == null) {
            return;
        }
        int droppedCount = dropped.size() + (compoundChecked ? compoundPass.dropped() : 0);
        int unverifiedCount = unverified + (compoundChecked ? compoundPass.unverified() : 0);
        int verifiedCount = verified + (compoundChecked ? compoundPass.verified() : 0);
        int livenessUnknownCount = livenessUnknown + (compoundChecked ? compoundPass.livenessUnknown() : 0);
        int configuredCount = expected.size() + (compoundChecked ? compoundPass.configured() : 0);
        String compoundClause = compoundChecked
                ? "; %d of the eleven compound validators missing (they travel inline)".formatted(compoundPass.missing())
                : "";
        if (!texts.isEmpty()) {
            log.warn("⚠ Lending v4 reference scripts: {} dropped, {} unverified, {} verified ({} with liveness "
                            + "unknown) of {} configured{} — lending is NOT refused. A dropped validator travels "
                            + "inline; see the per-coordinate lines above.",
                    droppedCount, unverifiedCount, verifiedCount, livenessUnknownCount, configuredCount,
                    compoundClause);
        } else {
            log.info("Lending v4 reference scripts verified against chain: {} verified ({} with liveness "
                    + "unknown) — every configured coordinate publishes its derived script hash", verifiedCount,
                    livenessUnknownCount);
        }
    }

    /**
     * The compound pass. {@code null} when it does not run (D4: compound disabled); a result whose
     * {@code usable} is {@code null} when it ran but must not narrow (a required hash is not derived).
     */
    private CompoundPass checkCompound(Pass pass, List<String> texts) {
        if (compound == null || !compound.isEnabled()) {
            return null;
        }
        Map<String, String> required = compoundValidators(registry);
        List<String> underived = required.entrySet().stream().filter(e -> e.getValue() == null)
                .map(Map.Entry::getKey).toList();
        if (!underived.isEmpty()) {
            String text = ("%s %s: not checked: this node derives no script hash for %s "
                    + "(loans.smart-tokens-spend-script-hash is not set), so there is nothing to compare the list "
                    + "with. Every coordinate kept as configured.").formatted(UNVERIFIED, COMPOUND_KEY,
                    String.join(", ", underived));
            log.warn("{}", text);
            texts.add(text);
            return new CompoundPass(null, 0, 0, 0, 0, 0, 0);
        }
        Map<String, String> labelByHash = new HashMap<>();
        required.forEach((label, hash) -> labelByHash.put(hash.toLowerCase(Locale.ROOT), label));

        String raw = compound.getConfiguredReferenceScripts();
        List<String> entries = new ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                if (!part.trim().isEmpty()) {
                    entries.add(part.trim());
                }
            }
        }

        Set<String> listed = new HashSet<>();
        // lower-cased published hash → the entry that supplies it; first usable coordinate wins.
        Map<String, String> suppliedBy = new HashMap<>();
        List<TransactionInput> usable = new ArrayList<>();
        int dropped = 0;
        int unverified = 0;
        int verified = 0;
        int livenessUnknown = 0;
        for (int i = 0; i < entries.size(); i++) {
            String entry = entries.get(i);
            String where = "%s entry %d (%s)".formatted(COMPOUND_KEY, i + 1, entry);
            TransactionInput input = compoundCoordinate(entry);
            Outcome outcome;
            if (input == null) {
                outcome = Outcome.dropped(("'%s' is malformed: the expected form is txHash#index, a 64-character "
                        + "hex transaction hash and a non-negative output index").formatted(entry));
            } else if (!listed.add(entry)) {
                outcome = Outcome.dropped("listed twice; only the first occurrence is used");
            } else {
                Answer answer = pass.ask(input);
                if (answer == null) {
                    outcome = pass.notAsked();
                } else {
                    outcome = answerVerdict(answer);
                    if (outcome == null) {
                        PublishedOutput output = answer.result().getValue();
                        String published = output.referenceScriptHash();
                        String hash = published.toLowerCase(Locale.ROOT);
                        String label = labelByHash.get(hash);
                        if (label == null) {
                            outcome = Outcome.dropped(("the output publishes %s, which is none of the eleven "
                                    + "compound validators this node derives").formatted(published));
                        } else if (suppliedBy.containsKey(hash)) {
                            outcome = Outcome.dropped("the output publishes %s (%s), which %s already supplies"
                                    .formatted(published, label, suppliedBy.get(hash)));
                        } else {
                            suppliedBy.put(hash, entry);
                            outcome = usable(output);
                        }
                    }
                }
            }
            switch (outcome.kind()) {
                case DROPPED -> {
                    String text = ("%s %s: %s. The coordinate is NOT used: a compound validator no other "
                            + "coordinate supplies travels inline in the witness set. Fix the coordinate and "
                            + "restart.").formatted(DROPPED, where, outcome.reason());
                    log.warn("{}", text);
                    texts.add(text);
                    dropped++;
                }
                case UNVERIFIED -> {
                    String text = ("%s %s: could not be checked: %s. Kept as configured; it is referenced "
                            + "whatever it publishes.").formatted(UNVERIFIED, where, outcome.reason());
                    log.warn("{}", text);
                    texts.add(text);
                    usable.add(input);
                    unverified++;
                }
                case USABLE_LIVENESS_UNKNOWN -> {
                    log.info("{} publishes a compound validator; the provider did not say whether it is still "
                            + "unspent", where);
                    usable.add(input);
                    verified++;
                    livenessUnknown++;
                }
                case USABLE -> {
                    usable.add(input);
                    verified++;
                }
            }
        }

        int missing = 0;
        for (Map.Entry<String, String> validator : required.entrySet()) {
            if (suppliedBy.containsKey(validator.getValue().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String text = ("%s %s: no usable coordinate publishes %s (%s); it travels INLINE in the witness set "
                    + "(a larger transaction; one over maxTxSize is refused before submission).%s")
                    .formatted(MISSING, COMPOUND_KEY, validator.getKey(), validator.getValue(),
                            unverified > 0 ? " %d compound coordinate(s) could not be checked and may supply it."
                                    .formatted(unverified) : "");
            log.warn("{}", text);
            texts.add(text);
            missing++;
        }
        return new CompoundPass(usable, dropped, unverified, verified, livenessUnknown, entries.size(), missing);
    }

    /**
     * {@code txHash#index} with a 64-hex hash and a non-negative decimal index, or {@code null} — the
     * same well-formedness {@code CompoundConfiguration.narrowReferenceScripts} accepts.
     */
    private static TransactionInput compoundCoordinate(String entry) {
        String[] parts = entry.split("#", -1);
        if (parts.length != 2 || parts[0].length() != 64
                || !parts[0].chars().allMatch(c -> Character.digit(c, 16) >= 0 && c < 128)
                || parts[1].isEmpty() || !parts[1].chars().allMatch(c -> c >= '0' && c <= '9')) {
            return null;
        }
        try {
            return new TransactionInput(parts[0], Integer.parseInt(parts[1]));
        } catch (NumberFormatException tooLarge) {
            return null;
        }
    }

    /** What the compound pass found, for the narrowing and the summary. */
    private record CompoundPass(List<TransactionInput> usable, int dropped, int unverified, int verified,
                                int livenessUnknown, int configured, int missing) {
    }

    /**
     * The out-ref memo and the D6 circuit-breaker, shared by the named keys and the compound list for one
     * pass. ⚠ Memoised per out-ref: two coordinates at one out-ref are one question to the provider, though
     * each is judged by its own rule.
     */
    private final class Pass {
        private final Map<String, Answer> asked = new HashMap<>();
        /** D6: the first lookup that THROWS stops this pass asking. An HTTP status never trips it. */
        private String unreachable;
        private Exception firstFailure;

        /** The provider's answer for {@code input}, or {@code null} when the breaker stopped this pass asking. */
        Answer ask(TransactionInput input) {
            String outRef = outRef(input);
            Answer answer = asked.get(outRef);
            if (answer != null) {
                return answer;
            }
            if (unreachable != null) {
                return null;
            }
            try {
                answer = new Answer(lookup.lookup(input.getTransactionId(), input.getIndex()), null);
            } catch (Exception e) {
                answer = new Answer(null, e);
                unreachable = e.getClass().getSimpleName() + ": " + e.getMessage();
                firstFailure = e;
            }
            asked.put(outRef, answer);
            return answer;
        }

        Outcome notAsked() {
            return Outcome.unverified(("not asked: the provider was unreachable earlier in this pass (%s)")
                    .formatted(unreachable));
        }
    }

    private static TransactionInput keep(Set<String> dropped, String key, TransactionInput configured) {
        return dropped.contains(key) ? null : configured;
    }

    /**
     * One named coordinate's verdict, from the provider's answer: the shared {@link #answerVerdict}, then
     * the published hash against the hash this node derives for THAT key.
     */
    private static Outcome classify(Expectation expectation, Answer answer) {
        Outcome verdict = answerVerdict(answer);
        if (verdict != null) {
            return verdict;
        }
        PublishedOutput output = answer.result().getValue();
        String published = output.referenceScriptHash();
        if (!expectation.derivedScriptHash().equalsIgnoreCase(published)) {
            return Outcome.dropped("the output publishes %s, but this node derives %s for this key"
                    .formatted(published, expectation.derivedScriptHash()));
        }
        return usable(output);
    }

    /**
     * The verdict the ANSWER alone settles, shared by the named keys and the compound list — or
     * {@code null} when the output exists, is not spent and publishes a script, whose hash the caller then
     * judges. ⚠ Only 400 and 404 are ANSWERS (D2): every other status, a missing Result and a throw mean
     * "could not be asked", which keeps the coordinate.
     */
    private static Outcome answerVerdict(Answer answer) {
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
        return null;
    }

    private static Outcome usable(PublishedOutput output) {
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
