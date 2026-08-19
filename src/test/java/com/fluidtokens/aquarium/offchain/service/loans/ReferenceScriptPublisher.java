package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the transactions that <b>publish</b> the six Lending v4 liquidation validators as
 * reference scripts: each output pays the destination address the minimum ada the ledger
 * requires and carries exactly one validator in its {@code script_ref} field, with no datum.
 *
 * <h2>Why this exists at all</h2>
 * A {@code Liquidate} invokes six validators totalling more bytes than {@code maxTxSize}
 * (measured by {@link ReferenceScriptPublisherTest}, ~18.7 kB against 16_384), so they cannot
 * travel in its witness set — the liquidation is unbuildable until they sit on chain as
 * reference scripts. FluidTokens has not republished them for the third preview deployment.
 * A reference script is only a script attached to some UTxO; we hold the applied scripts, so
 * we can publish them ourselves. The coordinates that come out of a real publication are what
 * {@code application.yaml}'s currently-blank {@code loans.liquidation.reference-scripts} block
 * eventually gets filled with — <b>that is a later, separate step</b>.
 *
 * <h2>Structurally incapable of submitting</h2>
 * The {@link QuickTxBuilder} below is constructed with a <b>null</b> {@code TransactionProcessor}
 * — the third constructor argument. A processor is the only thing in cardano-client-lib that can
 * put a transaction on a network, so a builder that never has one cannot submit, whatever a
 * caller asks of it. This is the same constraint {@code LiquidateTransactionBuilder} and
 * {@link RequestMintTransactionBuilder} carry. Nothing in this class or its callers holds a
 * {@code BackendService}, and the transactions it returns are unsigned.
 *
 * <h2>Why it lives in {@code src/test}</h2>
 * Same rule as the T-016 origination fixtures: the node is a liquidation bot and must not learn
 * to publish scripts. Publishing is a one-off human act, not a node capability.
 *
 * <h2>What it checks about its own output</h2>
 * Every assertion in {@link #build} reads the transaction back from its own CBOR
 * ({@link Transaction#deserialize}) rather than the builder's record of what it did — the
 * discipline the rest of this package settled on. The load-bearing one is the script-hash
 * check: a wrong script published is ~86 ada locked behind a coordinate no verifier will ever
 * accept, and {@code LoansReferenceScriptVerifier} would hard-fail the node on it at startup.
 */
public final class ReferenceScriptPublisher {

    /** Nothing above this may be serialised: preview's {@code maxTxSize}, read live 2026-08-17. */
    static final int MAX_TX_SIZE = 16_384;

    /**
     * The six validators a plain {@code Liquidate} invokes, named as
     * {@code loans.liquidation.reference-scripts} names them. {@code assetManager} is deliberately
     * absent: it is not invoked by a Liquidate (it only guards the outputs), so publishing it
     * would buy nothing — see {@code LiquidateTransactionBuilder.ReferenceScripts}.
     */
    public enum Validator {
        LOAN("loan"),
        LOAN_SPEND("loan-spend"),
        LENDER_MANAGER("lender-manager"),
        LENDER_MANAGER_SPEND("lender-manager-spend"),
        LOAN_CLAIM_ACTION("loan-claim-action"),
        LM_LIQUIDATE_ACTION("lm-liquidate-action");

        private final String configKey;

        Validator(String configKey) {
            this.configKey = configKey;
        }

        /** The {@code application.yaml} key whose coordinate this validator's UTxO would fill. */
        public String configKey() {
            return configKey;
        }
    }

    /**
     * The Ticket Owner's per-script min-ada estimate, in lovelace, and the band the computed
     * value must land in. These are <b>not</b> used to size any output — every output is sized by
     * {@link MinAdaCalculator} against the live protocol params — they exist so that a computed
     * value wandering away from what was measured when this slice was written is an error rather
     * than a silently different transaction. The band is deliberately loose (±20%): it is a
     * "same order of magnitude, same script" check, not a pin. The pin is in the test.
     */
    private static final Map<Validator, Long> ESTIMATED_MIN_ADA_LOVELACE = new EnumMap<>(Map.of(
            Validator.LOAN, 11_960_000L,
            Validator.LOAN_SPEND, 5_970_000L,
            Validator.LENDER_MANAGER, 5_150_000L,
            Validator.LENDER_MANAGER_SPEND, 5_970_000L,
            Validator.LOAN_CLAIM_ACTION, 38_310_000L,
            Validator.LM_LIQUIDATE_ACTION, 19_200_000L));

    private static final double MIN_ADA_BAND = 0.20d;

    /**
     * Which scripts go in which transaction. The split is data, not control flow, so that
     * "two transactions, and here is why one will not do" is something a test can state by
     * handing this class a different {@link Plan} rather than by reading the code.
     *
     * @param groups one element per transaction, in build order
     */
    public record Plan(List<List<Validator>> groups) {

        /**
         * The minimum split with headroom: the two large action validators in one transaction,
         * the four smaller ones in the other. {@link #single()} is what proves the split is
         * necessary rather than cautious.
         */
        public static Plan minimumSplit() {
            return new Plan(List.of(
                    List.of(Validator.LOAN_CLAIM_ACTION, Validator.LM_LIQUIDATE_ACTION),
                    List.of(Validator.LOAN, Validator.LOAN_SPEND,
                            Validator.LENDER_MANAGER, Validator.LENDER_MANAGER_SPEND)));
        }

        /** All six in one transaction. Exists to be refused; see {@link #assertFitsMaxTxSize}. */
        public static Plan single() {
            return new Plan(List.of(List.of(Validator.values())));
        }

        /**
         * Publish exactly the named validators, in one transaction.
         * <p>
         * This exists because publishing all six is not what the size constraint actually requires.
         * Only enough scripts have to travel by reference to bring the liquidation under
         * {@code maxTxSize}; the rest stay in the witness set. Measured on the real loan:
         * five inline plus {@code LOAN_CLAIM_ACTION} referenced is 11,713 bytes against 16,384.
         * So the shipped plan is {@code of(LOAN_CLAIM_ACTION)} — one script, ~38.36 ADA locked,
         * rather than six and ~86.84.
         * <p>
         * The duplicate guard still applies. The <em>completeness</em> guard deliberately does not:
         * a partial plan is the point here, and which subset is correct is a property of the
         * transaction being built, not of this class. Whoever calls this owns that choice, and
         * {@link BuiltTransaction#published()} reports exactly what went out so the choice is
         * checkable rather than assumed.
         */
        public static Plan of(Validator... validators) {
            return new Plan(List.of(List.of(validators)));
        }

        /** Every validator the plan publishes, in build order, duplicates included. */
        List<Validator> flattened() {
            List<Validator> all = new ArrayList<>();
            groups.forEach(all::addAll);
            return all;
        }
    }

    /**
     * One published output, <b>read back out of the deserialised transaction body</b> — never the
     * builder's own record of what it asked for.
     *
     * @param validator      which validator the attached script's hash identifies
     * @param scriptHash     the hash of the script actually attached, hex
     * @param scriptBodyBytes size of the bare script, i.e. what counts against {@code maxTxSize}
     * @param lovelace       the ada the output holds
     */
    public record PublishedScript(Validator validator, String scriptHash, int scriptBodyBytes,
                                  long lovelace) {
    }

    /**
     * One finished transaction and everything measured about it. Every field is derived from
     * {@link #cbor}, so a test asserting on this is asserting on the serialised transaction.
     */
    public record BuiltTransaction(Transaction transaction, byte[] cbor, int sizeBytes,
                                   long feeLovelace, List<PublishedScript> published) {

        /** The ada this transaction locks into reference-script outputs, fee excluded. */
        public long lockedLovelace() {
            return published.stream().mapToLong(PublishedScript::lovelace).sum();
        }
    }

    private final LoansContractRegistry registry;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;

    public ReferenceScriptPublisher(LoansContractRegistry registry,
                                    UtxoSupplier utxoSupplier,
                                    ProtocolParamsSupplier protocolParamsSupplier) {
        this.registry = registry;
        this.utxoSupplier = utxoSupplier;
        this.protocolParamsSupplier = protocolParamsSupplier;
    }

    /**
     * Builds every transaction the plan calls for, asserting the structure of each one and of the
     * plan as a whole. Throws — rather than returning something a human might submit — on any
     * mismatch.
     * <p>
     * Change goes back to {@code funderAddress}. That is fine only when it differs from
     * {@code destinationAddress}; when they are the same wallet, use
     * {@link #build(Plan, String, String, String)} and give the change somewhere of its own —
     * see the change-address warning there.
     *
     * @param plan               which scripts go in which transaction
     * @param destinationAddress where the reference-script outputs are paid; the address whose
     *                           UTxOs the coordinates will point at
     * @param funderAddress      whose UTxOs pay for all of it
     */
    public List<BuiltTransaction> build(Plan plan, String destinationAddress, String funderAddress) {
        return build(plan, destinationAddress, funderAddress, funderAddress, Mutation.none());
    }

    /**
     * As {@link #build(Plan, String, String)}, with the change sent somewhere of its own.
     *
     * <h2>Why the change address matters here, and matters a lot</h2>
     * cardano-client-lib deducts the fee from <b>the largest-coin output at the change address</b>
     * ({@code FeeCalculators.execute}, 0.7.2). If the change address is also the destination, the
     * candidates include the reference-script outputs — and a 38-ada {@code loanClaimAction}
     * output is the largest one whenever the wallet's change is smaller than that. The fee then
     * comes out of an output whose min-ada was exact, {@code ChangeOutputAdjustments} notices it
     * is now short, and pulls a whole extra UTxO into it: <b>measured on the runner's own shape,
     * one 60-ada UTxO was swallowed into a reference-script output that needed 38.359 ada</b>.
     * Nothing about that is visible in the output count or the script hashes.
     * <p>
     * {@link #assertPaysExactlyMinAda} catches it either way — that check exists because of this —
     * but a change address distinct from the destination stops it happening at all, whatever shape
     * the funding UTxOs have. Any address the same wallet controls will do; the runner uses the
     * next address on the same derivation path.
     */
    public List<BuiltTransaction> build(Plan plan, String destinationAddress, String funderAddress,
                                        String changeAddress) {
        return build(plan, destinationAddress, funderAddress, changeAddress, Mutation.none());
    }

    /**
     * Substitutes one output's attached script for a different validator's while the plan goes on
     * claiming the original — the seam {@link ReferenceScriptPublisherTest}'s falsifiability case
     * uses to hand the self-checks a transaction this class would never emit on its own. A
     * hash check that cannot be made to fail proves nothing about the ~86 ada it guards.
     *
     * @param groupIndex  which transaction of the plan to corrupt, 0-based
     * @param outputIndex which of that transaction's reference-script outputs, 0-based
     * @param substitute  the validator whose script is attached instead
     */
    record Mutation(int groupIndex, int outputIndex, Validator substitute) {

        static Mutation none() {
            return new Mutation(-1, -1, null);
        }

        boolean appliesTo(int group, int output) {
            return substitute != null && group == groupIndex && output == outputIndex;
        }
    }

    List<BuiltTransaction> build(Plan plan, String destinationAddress, String funderAddress,
                                 String changeAddress, Mutation mutation) {
        assertPlanHasNoDuplicates(plan);

        List<BuiltTransaction> built = new ArrayList<>();
        for (int i = 0; i < plan.groups().size(); i++) {
            built.add(buildOne(plan.groups().get(i), i, destinationAddress, funderAddress,
                    changeAddress, mutation));
        }
        return built;
    }

    /**
     * The minimum ada the ledger requires for an output at {@code address} carrying
     * {@code validator}'s script and nothing else, computed by cardano-client-lib from the live
     * protocol params.
     * <p>
     * Iterated to a fixpoint on purpose: the coin is itself part of the serialised output whose
     * size drives the answer, so sizing an output with the min-ada computed for a zero coin can
     * undershoot. In practice this converges on the first or second pass.
     */
    public long minAdaFor(Validator validator, String address) {
        MinAdaCalculator calculator = new MinAdaCalculator(protocolParamsSupplier.getProtocolParams());
        TransactionOutput probe = TransactionOutput.builder()
                .address(address)
                .value(Value.builder().coin(BigInteger.ZERO).build())
                .build();
        probe.setScriptRef(scriptOf(validator));

        BigInteger minAda = calculator.calculateMinAda(probe);
        for (int pass = 0; pass < 8; pass++) {
            probe.setValue(Value.builder().coin(minAda).build());
            BigInteger next = calculator.calculateMinAda(probe);
            if (next.equals(minAda)) {
                return minAda.longValueExact();
            }
            minAda = next;
        }
        throw new IllegalStateException(
                "min-ada for " + validator + " did not converge after 8 passes, last " + minAda);
    }

    /** The estimate this slice was briefed with, for the "computed vs estimated" report. */
    public static long estimatedMinAdaFor(Validator validator) {
        return ESTIMATED_MIN_ADA_LOVELACE.get(validator);
    }

    /** The applied script the registry derives for this validator. */
    public PlutusScript scriptOf(Validator validator) {
        return switch (validator) {
            case LOAN -> registry.getLoanScript();
            case LOAN_SPEND -> registry.getLoanSpendScript();
            case LENDER_MANAGER -> registry.getLenderManagerScript();
            case LENDER_MANAGER_SPEND -> registry.getLenderManagerSpendScript();
            case LOAN_CLAIM_ACTION -> registry.getLoanClaimActionScript();
            case LM_LIQUIDATE_ACTION -> registry.getLmLiquidateActionScript();
        };
    }

    /** The hash the registry derives for this validator — the coordinate's acceptance criterion. */
    public String scriptHashOf(Validator validator) {
        return switch (validator) {
            case LOAN -> registry.getLoanPolicyId();
            case LOAN_SPEND -> registry.getLoanSpendScriptHash();
            case LENDER_MANAGER -> registry.getLenderManagerWithdrawScriptHash();
            case LENDER_MANAGER_SPEND -> registry.getLenderManagerSpendScriptHash();
            case LOAN_CLAIM_ACTION -> registry.getLoanClaimActionScriptHash();
            case LM_LIQUIDATE_ACTION -> registry.getLmLiquidateActionScriptHash();
        };
    }

    // ---- assembly ---------------------------------------------------------------------------------

    private BuiltTransaction buildOne(List<Validator> group, int groupIndex,
                                      String destinationAddress, String funderAddress,
                                      String changeAddress, Mutation mutation) {
        Map<Validator, Long> expectedLovelace = new EnumMap<>(Validator.class);
        Tx tx = new Tx();
        for (int i = 0; i < group.size(); i++) {
            Validator validator = group.get(i);
            Validator attached = mutation.appliesTo(groupIndex, i) ? mutation.substitute() : validator;
            long lovelace = assertMinAdaInBand(validator, minAdaFor(validator, destinationAddress));
            expectedLovelace.put(validator, lovelace);
            // payToAddress(address, amounts, Script) is the reference-script overload: it lands in
            // TransactionOutput.setScriptRef(Script), which stores Script.scriptRefBytes() — the
            // CBOR [scriptType, scriptBody] array that becomes field 3 (tag 24) of the output.
            // No datum overload is used, and none is wanted: a datum on these outputs would only
            // enlarge them and raise their min-ada.
            tx.payToAddress(destinationAddress, List.of(Amount.lovelace(BigInteger.valueOf(lovelace))),
                    scriptOf(attached));
        }
        tx.from(funderAddress);
        tx.withChangeAddress(changeAddress);

        Transaction completed = complete(tx, changeAddress, groupIndex);
        return assertStructure(completed, group, groupIndex, destinationAddress, changeAddress,
                expectedLovelace);
    }

    /**
     * @param changeAddress also the fee payer: cardano-client-lib's balancer takes the fee out of
     *                      an output at the <em>fee payer</em>'s address, so pointing it anywhere
     *                      but the change output is what causes the swallowed-UTxO failure
     *                      documented on {@link #build(Plan, String, String, String)}
     */
    private Transaction complete(Tx tx, String changeAddress, int groupIndex) {
        try {
            // The third argument is the TransactionProcessor and it stays null; see the class
            // javadoc. There is no script being executed here, so no evaluator and no collateral
            // are involved either — this is an ordinary payment that happens to carry scripts.
            return new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null)
                    .compose(tx)
                    .feePayer(changeAddress)
                    // A script supplier that resolves nothing: cardano-client-lib otherwise walks
                    // the reference inputs looking for scripts to fetch, and this builder has no
                    // remote source. There are no reference inputs here anyway.
                    .withScriptSupplier(scriptHash -> Optional.empty())
                    // Four outputs to the same address must stay four outputs: merging them would
                    // collapse four reference scripts into one output that can hold only one.
                    .mergeOutputs(false)
                    // No signer is attached — nothing here signs anything — but the fee must still
                    // budget for the one vkey witness the eventual signer adds, or the reported
                    // funding requirement is understated.
                    .additionalSignersCount(1)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "cannot build reference-script publishing transaction " + (groupIndex + 1), e);
        }
    }

    // ---- self-checks, all against the deserialised body -------------------------------------------

    /**
     * The plan must publish each of the six exactly once: a duplicate wastes its min-ada twice
     * over and a missing one leaves the liquidation unbuildable for a reason nothing else here
     * would report.
     */
    /**
     * Refuses a plan that publishes the same script twice — which would lock its min-ada a second
     * time for nothing, and leave two coordinates where the config expects one.
     * <p>
     * It deliberately does <em>not</em> require the plan to cover all six. It used to, from when
     * publishing all six was assumed necessary; measurement showed it is not
     * ({@link Plan#of(Validator...)}), and a guard that enforces an assumption after the assumption
     * is known to be false is worse than no guard — it would have forced a 86.84 ADA spend where
     * 38.36 does the job.
     */
    private static void assertPlanHasNoDuplicates(Plan plan) {
        List<Validator> flattened = plan.flattened();
        Set<Validator> distinct = new LinkedHashSet<>(flattened);
        if (distinct.size() != flattened.size()) {
            throw new IllegalStateException("the plan publishes a script more than once: " + flattened);
        }
    }

    private static long assertMinAdaInBand(Validator validator, long computed) {
        long estimated = ESTIMATED_MIN_ADA_LOVELACE.get(validator);
        double drift = Math.abs(computed - estimated) / (double) estimated;
        if (drift > MIN_ADA_BAND) {
            throw new IllegalStateException("min-ada for " + validator + " computed as " + computed
                    + " lovelace, outside the ±" + (int) (MIN_ADA_BAND * 100)
                    + "% band around the briefed estimate of " + estimated + " lovelace");
        }
        return computed;
    }

    private BuiltTransaction assertStructure(Transaction completed, List<Validator> group,
                                             int groupIndex, String destinationAddress,
                                             String changeAddress,
                                             Map<Validator, Long> expectedLovelace) {
        byte[] cbor;
        Transaction decoded;
        try {
            cbor = completed.serialize();
            decoded = Transaction.deserialize(cbor);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "transaction " + (groupIndex + 1) + " does not round-trip through CBOR", e);
        }

        assertFitsMaxTxSize(cbor.length, groupIndex, group);

        List<TransactionOutput> outputs = decoded.getBody().getOutputs();
        List<PublishedScript> published = new ArrayList<>();
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput output = outputs.get(i);
            String where = "transaction " + (groupIndex + 1) + " output " + i;

            if (output.getInlineDatum() != null || output.getDatumHash() != null) {
                throw new IllegalStateException(where + " carries a datum; reference-script outputs "
                        + "carry none — it would only enlarge them and raise their min-ada");
            }
            if (output.getScriptRef() == null) {
                // The only legitimate script-less output is the change.
                if (!changeAddress.equals(output.getAddress())) {
                    throw new IllegalStateException(where + " carries no reference script and is not "
                            + "the change to " + changeAddress + " but a payment to "
                            + output.getAddress());
                }
                continue;
            }
            if (!destinationAddress.equals(output.getAddress())) {
                throw new IllegalStateException(where + " carries a reference script but is paid to "
                        + output.getAddress() + ", not the destination " + destinationAddress);
            }
            published.add(identify(output, where));
        }

        assertPublishedMatchesTheGroup(published, group, groupIndex);
        assertPaysExactlyMinAda(published, groupIndex, expectedLovelace);

        return new BuiltTransaction(decoded, cbor, cbor.length,
                decoded.getBody().getFee().longValueExact(), List.copyOf(published));
    }

    /**
     * Each published output must hold <b>exactly</b> the min-ada computed for it — no less, or the
     * ledger rejects the transaction, and no more, because the surplus is ada parked in a UTxO
     * nobody will think to sweep.
     * <p>
     * This is not a formality. cardano-client-lib's balancer deducts the fee from, and tops up,
     * the largest output at the fee payer's address; point that at the destination and a
     * reference-script output silently absorbs a whole funding UTxO — measured at 60 ada into an
     * output needing 38.359, with the output count and every script hash still correct. See
     * {@link #build(Plan, String, String, String)}.
     */
    private static void assertPaysExactlyMinAda(List<PublishedScript> published, int groupIndex,
                                                Map<Validator, Long> expectedLovelace) {
        for (PublishedScript script : published) {
            long expected = expectedLovelace.get(script.validator());
            if (script.lovelace() != expected) {
                throw new IllegalStateException("transaction " + (groupIndex + 1) + "'s "
                        + script.validator() + " output holds " + script.lovelace()
                        + " lovelace, not the " + expected + " lovelace min-ada it was built with"
                        + " (difference " + (script.lovelace() - expected) + ")");
            }
        }
    }

    /**
     * The single check that makes the two-transaction split a measurement rather than a claim: a
     * plan whose scripts do not fit is refused here, with the measured size in the message.
     */
    private static void assertFitsMaxTxSize(int sizeBytes, int groupIndex, List<Validator> group) {
        if (sizeBytes >= MAX_TX_SIZE) {
            throw new IllegalStateException("transaction " + (groupIndex + 1) + " publishing " + group
                    + " serialises to " + sizeBytes + " bytes, at or over maxTxSize " + MAX_TX_SIZE
                    + " — the scripts have to be split across more transactions");
        }
    }

    /** Reads the attached script back out of the output and names it by its hash. */
    private PublishedScript identify(TransactionOutput output, String where) {
        PlutusScript attached = PlutusScript.deserializeScriptRef(output.getScriptRef());
        String hash;
        int bodyBytes;
        try {
            hash = HexUtil.encodeHexString(attached.getScriptHash());
            bodyBytes = attached.serializeScriptBody().length;
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash the script attached to " + where, e);
        }

        Validator identified = hashesToValidators().get(hash);
        if (identified == null) {
            throw new IllegalStateException(where + " carries script hash " + hash
                    + ", which is none of the six liquidation validators this repo derives");
        }
        return new PublishedScript(identified, hash, bodyBytes,
                output.getValue().getCoin().longValueExact());
    }

    /**
     * Positional, not set-based: the outputs are emitted in plan order, so naming which position
     * went wrong is what turns a failure into a diagnosis.
     */
    private void assertPublishedMatchesTheGroup(List<PublishedScript> published,
                                                List<Validator> group, int groupIndex) {
        if (published.size() != group.size()) {
            throw new IllegalStateException("transaction " + (groupIndex + 1) + " was planned to publish "
                    + group.size() + " reference script(s) but the finished body carries "
                    + published.size());
        }
        for (int i = 0; i < group.size(); i++) {
            Validator expected = group.get(i);
            PublishedScript actual = published.get(i);
            if (actual.validator() != expected) {
                throw new IllegalStateException("transaction " + (groupIndex + 1)
                        + " reference-script output " + i + " was planned to publish " + expected
                        + " (" + scriptHashOf(expected) + ") but carries " + actual.validator()
                        + " (" + actual.scriptHash() + ")");
            }
            if (!scriptHashOf(expected).equals(actual.scriptHash())) {
                throw new IllegalStateException("transaction " + (groupIndex + 1)
                        + " reference-script output " + i + " carries script hash "
                        + actual.scriptHash() + ", not the derived hash for " + expected + " ("
                        + scriptHashOf(expected) + ")");
            }
        }
    }

    private Map<String, Validator> hashesToValidators() {
        Map<String, Validator> byHash = new LinkedHashMap<>();
        for (Validator validator : Validator.values()) {
            byHash.put(scriptHashOf(validator), validator);
        }
        return byHash;
    }

    /**
     * A publishing destination we can <b>prove</b> we do not control: the enterprise address of an
     * always-fails PlutusV3 script, derived per network. This is what a real publication should pay
     * its reference-script outputs to — the coordinates outlive this project, and paying them to a
     * wallet we happen to hold today makes their permanence a matter of key custody rather than of
     * the ledger.
     *
     * <h2>Why an always-fails script, and not merely a datum-less output</h2>
     * Under Plutus V3 a datum is <em>optional</em>, so a datum-less output at a V3 script address can
     * still be spent — "no datum" is not "unspendable". Permanence here rests <b>entirely</b> on the
     * script genuinely always failing. The script is {@code (program 1.1.0 (error))}: a bare
     * {@code error} term that aborts the UPLC machine before it inspects any argument, so it refuses
     * every spend regardless of datum, redeemer or context. That behaviour is not assumed — it is
     * proven by {@code ReferenceScriptPublisherTest}'s dry-eval permanence test, which spends a
     * synthetic UTxO at this address through the real UPLC machine and asserts the spend redeemer is
     * refused.
     *
     * <h2>Derivation (all three steps reproducible)</h2>
     * <ol>
     *   <li>{@code compiledCode = 4401010061} is the CBOR-wrapped flat encoding of
     *       {@code (program 1.1.0 (error))} — {@code aiken uplc encode --cbor --hex} over that
     *       one-line program (aiken v1.1.22). The bare {@code 01010061} flat body is wrapped in the
     *       {@code 0x44} CBOR bytestring header the blueprint's own {@code compiledCode} fields use.</li>
     *   <li>{@link PlutusBlueprintUtil#getPlutusScriptFromCompiledCode} builds the PlutusV3 script;
     *       {@link PlutusScript#getScriptHash()} is {@code blake2b_224(0x03 || compiledCode) =
     *       994b345acef955ada938b01e9f0405ab57743d41f0b398de604d0969} — the same {@code 0x03}-prefixed
     *       rule that reproduces every loans-v4 blueprint hash.</li>
     *   <li>{@link AddressProvider#getEntAddress} over that script credential and the requested
     *       network is the enterprise (no-stake) address the reference-script outputs are paid to.</li>
     * </ol>
     *
     * <h2>Fail-closed on mainnet</h2>
     * {@link #forNetwork} refuses mainnet outright — see there. This destination is for preview /
     * preprod publishing only; sending reference scripts to an address nobody controls on mainnet is
     * a separate, later, deliberate decision, and no mainnet destination is derived here.
     */
    public static final class UnspendableDestination {

        /**
         * The CBOR-wrapped flat encoding of the always-fails program {@code (program 1.1.0 (error))};
         * see the class javadoc for the full derivation. A wrong constant here is the exact
         * catastrophe this type exists to prevent, which is why the dry-eval permanence test proves
         * the resulting script always fails rather than trusting these bytes.
         */
        private static final String ALWAYS_FAILS_COMPILED_CODE = "4401010061";

        private static final PlutusScript ALWAYS_FAILS_SCRIPT = buildAlwaysFailsScript();

        private final String address;

        private UnspendableDestination(String address) {
            this.address = address;
        }

        /**
         * The unspendable destination for {@code network} — <b>except mainnet, which is refused</b>.
         * <p>
         * Mainnet is identified by its protocol magic (preprod and preview both carry testnet
         * network-id 0, so the network-id alone cannot tell mainnet apart). Constructing a destination
         * we do not control on mainnet is deliberately out of reach here: it is a separate, later
         * decision, so this throws rather than derive any mainnet address at all.
         */
        public static UnspendableDestination forNetwork(Network network) {
            if (network.getProtocolMagic() == Networks.mainnet().getProtocolMagic()) {
                throw new IllegalStateException(
                        "refusing to derive an unspendable reference-script destination on mainnet: "
                        + "sending reference scripts to an address nobody controls is a separate, later "
                        + "decision — no mainnet destination is implemented here");
            }
            String address = AddressProvider.getEntAddress(
                    Credential.fromScript(scriptHashHex()), network).getAddress();
            return new UnspendableDestination(address);
        }

        /** The enterprise script address the reference-script outputs are paid to. */
        public String address() {
            return address;
        }

        /** The always-fails script's hash, hex — the payment credential of {@link #address()}. */
        public String scriptHash() {
            return scriptHashHex();
        }

        /** The always-fails PlutusV3 script itself, for the dry-eval permanence proof. */
        public PlutusScript script() {
            return ALWAYS_FAILS_SCRIPT;
        }

        private static String scriptHashHex() {
            try {
                return HexUtil.encodeHexString(ALWAYS_FAILS_SCRIPT.getScriptHash());
            } catch (Exception e) {
                throw new IllegalStateException("cannot hash the always-fails script", e);
            }
        }

        private static PlutusScript buildAlwaysFailsScript() {
            try {
                return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                        ALWAYS_FAILS_COMPILED_CODE, PlutusVersion.v3);
            } catch (Exception e) {
                throw new IllegalStateException("cannot build the always-fails PlutusV3 script from "
                        + ALWAYS_FAILS_COMPILED_CODE, e);
            }
        }
    }
}
