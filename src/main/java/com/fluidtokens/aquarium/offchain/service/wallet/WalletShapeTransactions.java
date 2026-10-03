package com.fluidtokens.aquarium.offchain.service.wallet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;

import java.math.BigInteger;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.fluidtokens.aquarium.offchain.service.wallet.WalletShape.LOVELACE;
import static com.fluidtokens.aquarium.offchain.service.wallet.WalletShape.normalise;

/**
 * Builds the ONE self-send that moves a given list of wallet UTxOs into {@link WalletShape}'s target
 * shape — or, as a fallback, a plain consolidation — and verifies the BUILT body before handing it
 * back. Where the input list came from is the caller's business; this class spends exactly that list
 * and nothing else.
 *
 * <h2>Why every built body is re-verified</h2>
 * The request is not the artefact (officina CCL trap 21). cardano-client-lib is free to:
 * <ul>
 *   <li><b>merge</b> outputs to the same address — the default, which collapses the whole shape into
 *       one output; this class sets {@code mergeOutputs(false)};</li>
 *   <li><b>top up</b> an output below min-ADA (trap 6) — a junk bundle whose min-ADA exceeds 10 ADA
 *       silently stops being a 10-ADA output;</li>
 *   <li><b>take the fee from the largest change-address output</b> ({@code FeeCalculators:153-172}).
 *       Every output here is at the wallet, so when the change is not the largest output, the fee comes
 *       out of a SHAPED output and it silently shrinks.</li>
 * </ul>
 * None of those raises an error. So {@link #buildShaped} decodes the serialised body and refuses
 * unless it is exactly the plan; it never returns a transaction that fails those checks.
 *
 * <h2>Why there is no transaction evaluator</h2>
 * A plain {@link Tx} carries no script, so {@code QuickTxBuilder} never reaches script-cost evaluation
 * ({@code containsScriptTx} is set only for a {@code ScriptTx}, {@code QuickTxBuilder:321-350}). The
 * missing evaluator is therefore never read — and the body check refuses any transaction carrying a
 * redeemer or a script, so a script reaching this path is refused rather than priced with placeholders.
 *
 * <h2>Signing and submitting are separate calls</h2>
 * Neither builder signs or submits. {@link #sign} and {@link #submit} are the only paths to a
 * signature and to the network, and the network is reached only through a {@link TransactionSubmitter}
 * — bytes in, verdict out.
 */
public final class WalletShapeTransactions {

    /**
     * Everything this class can do to the network: hand over bytes. The same narrowing as
     * {@code LiquidationExecutor.TransactionSubmitter} — a submitter cannot build, balance or re-fetch,
     * so the bytes submitted are the bytes verified.
     */
    @FunctionalInterface
    public interface TransactionSubmitter {

        /** @return the backend's verdict; the value on success is the transaction hash */
        Result<String> submit(byte[] signedTransactionBytes) throws Exception;
    }

    /** How a build ended. */
    public enum Status {
        /** The transaction was built and passed every check. */
        BUILT,
        /** The build failed or the built body deviated from what was asked for; no transaction. */
        REFUSED,
        /** There was nothing to build — the input list was empty. */
        NOTHING_TO_DO
    }

    /**
     * @param status      how the build ended
     * @param transaction the unsigned, verified transaction; non-null only when {@link Status#BUILT}
     * @param plan        the plan a shaped build was checked against; null for a consolidation
     * @param detail      why, for a refusal or nothing-to-do; a short summary otherwise
     */
    public record Outcome(Status status, Transaction transaction, WalletShape.Plan plan, String detail) {

        static Outcome built(Transaction transaction, WalletShape.Plan plan, String detail) {
            return new Outcome(Status.BUILT, transaction, plan, detail);
        }

        static Outcome refused(WalletShape.Plan plan, String detail) {
            return new Outcome(Status.REFUSED, null, plan, detail);
        }

        static Outcome nothingToDo(String detail) {
            return new Outcome(Status.NOTHING_TO_DO, null, null, detail);
        }

        public boolean isBuilt() {
            return status == Status.BUILT;
        }
    }

    private final Account account;
    private final String walletAddress;
    private final ProtocolParamsSupplier protocolParamsSupplier;
    private final TransactionSubmitter submitter;

    public WalletShapeTransactions(Account account,
                                   ProtocolParamsSupplier protocolParamsSupplier,
                                   TransactionSubmitter submitter) {
        this.account = Objects.requireNonNull(account, "account");
        this.walletAddress = account.baseAddress();
        this.protocolParamsSupplier = Objects.requireNonNull(protocolParamsSupplier, "protocolParamsSupplier");
        this.submitter = Objects.requireNonNull(submitter, "submitter");
    }

    /** The wallet every input must come from and every output goes to. */
    public String walletAddress() {
        return walletAddress;
    }

    /** C for the current protocol parameters — what {@link WalletShape#reasons} must be given. */
    public BigInteger collateralLovelace() {
        return WalletShape.collateralLovelace(protocolParamsSupplier.getProtocolParams());
    }

    /**
     * Build the self-send that moves {@code inputs} into the target shape, and verify the built body
     * against the plan exactly.
     */
    public Outcome buildShaped(List<Utxo> inputs, Set<String> relevantUnits) {
        if (inputs == null || inputs.isEmpty()) {
            return Outcome.nothingToDo("no input UTxOs: nothing to reshape");
        }
        String inputProblem = inputProblem(inputs);
        WalletShape.Plan plan = WalletShape.plan(inputs, relevantUnits, collateralLovelace());
        if (inputProblem != null) {
            return Outcome.refused(plan, inputProblem);
        }
        if (plan.changeAndFeeLovelace().signum() <= 0) {
            return Outcome.refused(plan, "inputs hold " + plan.inputLovelace() + " lovelace, the plan needs "
                    + plan.plannedLovelace() + " plus a fee and a change output");
        }

        Tx tx = new Tx().from(walletAddress).collectFrom(inputs);
        for (WalletShape.PlannedOutput output : plan.outputs()) {
            tx.payToAddress(walletAddress, output.amounts());
        }
        tx.withChangeAddress(walletAddress);

        Transaction built;
        try {
            built = compose(inputs, tx);
        } catch (Exception e) {
            return Outcome.refused(plan, "build failed: " + e.getMessage());
        }

        String deviation = shapeDeviation(built, inputs, plan, walletAddress);
        if (deviation != null) {
            return Outcome.refused(plan, deviation);
        }
        return Outcome.built(built, plan, "shaped: " + plan.outputs().size() + " planned outputs + change, fee "
                + built.getBody().getFee());
    }

    /**
     * The plain fallback: one output carrying every native asset plus the min-ADA cardano-client-lib
     * computes for it (omitted when there are no assets), and ADA-only change. With no assets the
     * result is a single ADA-only output.
     */
    public Outcome buildConsolidation(List<Utxo> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return Outcome.nothingToDo("no input UTxOs: nothing to consolidate");
        }
        String inputProblem = inputProblem(inputs);
        if (inputProblem != null) {
            return Outcome.refused(null, inputProblem);
        }

        Map<String, BigInteger> assets = nativeAssets(WalletShape.totalUnits(inputs));
        Tx tx = new Tx().from(walletAddress).collectFrom(inputs);
        if (!assets.isEmpty()) {
            // No lovelace amount: cardano-client-lib adds the output's min-ADA itself.
            tx.payToAddress(walletAddress, assets.entrySet().stream()
                    .map(e -> Amount.asset(e.getKey(), e.getValue()))
                    .toList());
        } else {
            // ⛔ A Tx with NO payTo never builds its collectFrom inputs: cardano-client-lib skips them
            // (AbstractTx.complete, no output builder) and coin-selects just enough to pay the fee —
            // measured, it spent one of two given UTxOs. So an ADA-only consolidation pays the whole
            // balance to the wallet; the change nets to zero and is dropped, and the fee comes out of
            // this, the only (and largest) change-address output.
            tx.payToAddress(walletAddress, Amount.lovelace(WalletShape.totalUnits(inputs)
                    .getOrDefault(LOVELACE, BigInteger.ZERO)));
        }
        tx.withChangeAddress(walletAddress);

        Transaction built;
        try {
            built = compose(inputs, tx);
        } catch (Exception e) {
            return Outcome.refused(null, "build failed: " + e.getMessage());
        }

        String deviation = consolidationDeviation(built, inputs, assets, walletAddress);
        if (deviation != null) {
            return Outcome.refused(null, deviation);
        }
        return Outcome.built(built, null, "consolidated: " + built.getBody().getOutputs().size()
                + " outputs, fee " + built.getBody().getFee());
    }

    /** Sign with the bot account. Never called by either builder. */
    public Transaction sign(Transaction unsigned) {
        return account.sign(unsigned);
    }

    /** Hand the signed bytes to the submitter. Never called by either builder. */
    public Result<String> submit(Transaction signed) throws Exception {
        return submitter.submit(signed.serialize());
    }

    private Transaction compose(List<Utxo> inputs, Tx tx) {
        return new QuickTxBuilder(inMemorySupplierOf(inputs), protocolParamsSupplier, (TransactionProcessor) null)
                .compose(tx)
                // ⛔ The default merges every output to the same address into one — and here they all
                // share the wallet address, so the shape would collapse (officina CCL trap 21).
                .mergeOutputs(false)
                .feePayer(walletAddress)
                .build();
    }

    /**
     * A supplier that knows only the given inputs, so cardano-client-lib cannot select anything else:
     * if the given list cannot balance the transaction, the build fails rather than reaching further.
     */
    static UtxoSupplier inMemorySupplierOf(List<Utxo> inputs) {
        List<Utxo> copy = List.copyOf(inputs);
        return new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
                if (page != null && page > 0) {
                    return List.of();
                }
                return copy.stream().filter(utxo -> Objects.equals(utxo.getAddress(), address)).toList();
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return copy.stream()
                        .filter(utxo -> utxo.getTxHash().equals(txHash) && utxo.getOutputIndex() == outputIndex)
                        .findFirst();
            }
        };
    }

    /** A reason the given inputs cannot be spent by this wallet alone, or null. */
    private String inputProblem(List<Utxo> inputs) {
        Set<String> seen = new HashSet<>();
        for (Utxo utxo : inputs) {
            if (!walletAddress.equals(utxo.getAddress())) {
                return "input " + ref(utxo) + " is at " + utxo.getAddress() + ", not the wallet " + walletAddress;
            }
            if (!seen.add(ref(utxo))) {
                return "input " + ref(utxo) + " is listed twice";
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // Body checks — always on the DESERIALISED body, never on the object the builder returned.
    // ---------------------------------------------------------------------------------------------

    /** Why the built body is not exactly the plan plus one ADA-only change output, or null. */
    static String shapeDeviation(Transaction built, List<Utxo> inputs, WalletShape.Plan plan, String wallet) {
        Transaction tx;
        try {
            tx = Transaction.deserialize(built.serialize());
        } catch (Exception e) {
            return "built transaction does not round-trip: " + e.getMessage();
        }
        String common = commonDeviation(tx, inputs, wallet);
        if (common != null) {
            return common;
        }

        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        List<WalletShape.PlannedOutput> planned = plan.outputs();
        if (outputs.size() != planned.size() + 1) {
            return "built body has " + outputs.size() + " outputs, the plan needs exactly "
                    + (planned.size() + 1) + " (" + planned.size() + " planned + one change)";
        }
        for (int i = 0; i < planned.size(); i++) {
            Map<String, BigInteger> actual = unitsOf(outputs.get(i).getValue());
            Map<String, BigInteger> expected = planned.get(i).units();
            if (!actual.equals(expected)) {
                return "output " + i + " (" + planned.get(i).role() + ") is " + actual + ", the plan says "
                        + expected;
            }
        }
        TransactionOutput change = outputs.get(planned.size());
        if (!unitsOf(change.getValue()).keySet().equals(Set.of(LOVELACE))) {
            return "change output " + planned.size() + " is not ADA-only: " + unitsOf(change.getValue());
        }
        return null;
    }

    /**
     * Why the built consolidation is not one output holding every native asset (when there are any)
     * plus ADA-only change, or null.
     */
    static String consolidationDeviation(Transaction built, List<Utxo> inputs, Map<String, BigInteger> assets,
                                         String wallet) {
        Transaction tx;
        try {
            tx = Transaction.deserialize(built.serialize());
        } catch (Exception e) {
            return "built transaction does not round-trip: " + e.getMessage();
        }
        String common = commonDeviation(tx, inputs, wallet);
        if (common != null) {
            return common;
        }

        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        int expectedOutputs = assets.isEmpty() ? 1 : 2;
        if (outputs.size() != expectedOutputs) {
            return "consolidation has " + outputs.size() + " outputs, expected " + expectedOutputs;
        }
        if (!assets.isEmpty()) {
            Map<String, BigInteger> held = nativeAssets(unitsOf(outputs.get(0).getValue()));
            if (!held.equals(assets)) {
                return "consolidation output 0 holds " + held + ", expected every native asset " + assets;
            }
        }
        TransactionOutput change = outputs.get(outputs.size() - 1);
        if (!unitsOf(change.getValue()).keySet().equals(Set.of(LOVELACE))) {
            return "consolidation change is not ADA-only: " + unitsOf(change.getValue());
        }
        if (change.getValue().getCoin().signum() <= 0) {
            return "consolidation change holds no lovelace: " + change.getValue().getCoin();
        }
        return null;
    }

    /**
     * Checks both builders share: inputs exactly the given list, every output at the wallet with no
     * datum or script, nothing script-bearing or ledger-effectful in the body, and per-unit
     * conservation.
     */
    private static String commonDeviation(Transaction tx, List<Utxo> inputs, String wallet) {
        TransactionBody body = tx.getBody();

        Set<String> given = inputs.stream().map(WalletShapeTransactions::ref).collect(Collectors.toCollection(TreeSet::new));
        List<String> spent = body.getInputs().stream().map(WalletShapeTransactions::ref).toList();
        if (spent.size() != given.size() || !new TreeSet<>(spent).equals(given)) {
            return "built inputs " + new TreeSet<>(spent) + " are not exactly the given inputs " + given;
        }

        for (int i = 0; i < body.getOutputs().size(); i++) {
            TransactionOutput output = body.getOutputs().get(i);
            if (!wallet.equals(output.getAddress())) {
                return "output " + i + " is at " + output.getAddress() + ", not the wallet " + wallet;
            }
            if (output.getDatumHash() != null || output.getInlineDatum() != null || output.getScriptRef() != null) {
                return "output " + i + " carries a datum or a reference script";
            }
        }

        if (notEmpty(body.getMint())) {
            return "built body mints or burns";
        }
        if (notEmpty(body.getWithdrawals())) {
            return "built body withdraws";
        }
        if (notEmpty(body.getCerts())) {
            return "built body carries certificates";
        }
        if (notEmpty(body.getCollateral()) || body.getCollateralReturn() != null || body.getTotalCollateral() != null) {
            return "built body carries collateral";
        }
        if (notEmpty(body.getReferenceInputs())) {
            return "built body carries reference inputs";
        }
        if (body.getVotingProcedures() != null || notEmpty(body.getProposalProcedures())
                || body.getDonation() != null || body.getCurrentTreasuryValue() != null) {
            return "built body carries governance or treasury fields";
        }
        TransactionWitnessSet witnesses = tx.getWitnessSet();
        if (witnesses != null && (notEmpty(witnesses.getRedeemers()) || notEmpty(witnesses.getNativeScripts())
                || notEmpty(witnesses.getPlutusV1Scripts()) || notEmpty(witnesses.getPlutusV2Scripts())
                || notEmpty(witnesses.getPlutusV3Scripts()) || notEmpty(witnesses.getPlutusDataList()))) {
            return "built transaction carries redeemers, scripts or datums";
        }

        return conservationDeviation(body, inputs);
    }

    /** Per unit, inputs == outputs + fee (fee for lovelace only); no negative quantity anywhere. */
    static String conservationDeviation(TransactionBody body, List<Utxo> inputs) {
        Map<String, BigInteger> in = new TreeMap<>(WalletShape.totalUnits(inputs));
        Map<String, BigInteger> out = new TreeMap<>();
        for (TransactionOutput output : body.getOutputs()) {
            for (Map.Entry<String, BigInteger> e : unitsOf(output.getValue()).entrySet()) {
                if (e.getValue().signum() < 0) {
                    return "an output holds a negative quantity of " + e.getKey() + ": " + e.getValue();
                }
                out.merge(e.getKey(), e.getValue(), BigInteger::add);
            }
        }
        BigInteger fee = body.getFee() == null ? BigInteger.ZERO : body.getFee();
        if (fee.signum() <= 0) {
            return "built body has no positive fee: " + fee;
        }
        out.merge(LOVELACE, fee, BigInteger::add);

        Set<String> units = new TreeSet<>(in.keySet());
        units.addAll(out.keySet());
        for (String unit : units) {
            BigInteger a = in.getOrDefault(unit, BigInteger.ZERO);
            BigInteger b = out.getOrDefault(unit, BigInteger.ZERO);
            if (a.compareTo(b) != 0) {
                return "not conserved: " + unit + " inputs " + a + " != outputs" + (LOVELACE.equals(unit) ? " + fee " : " ")
                        + b;
            }
        }
        return null;
    }

    /** A value as lower-case unit → quantity, lovelace included, zero quantities dropped. */
    static Map<String, BigInteger> unitsOf(Value value) {
        Map<String, BigInteger> units = new TreeMap<>();
        units.put(LOVELACE, value.getCoin() == null ? BigInteger.ZERO : value.getCoin());
        if (value.getMultiAssets() != null) {
            value.getMultiAssets().forEach(multiAsset -> multiAsset.getAssets().forEach(asset -> {
                if (asset.getValue() != null && asset.getValue().signum() != 0) {
                    units.merge(normalise(multiAsset.getPolicyId() + asset.getNameAsHex().replaceFirst("^0x", "")),
                            asset.getValue(), BigInteger::add);
                }
            }));
        }
        return units;
    }

    private static Map<String, BigInteger> nativeAssets(Map<String, BigInteger> units) {
        Map<String, BigInteger> assets = new TreeMap<>();
        units.forEach((unit, quantity) -> {
            if (!LOVELACE.equals(unit) && quantity.signum() != 0) {
                assets.put(unit, quantity);
            }
        });
        return assets;
    }

    private static boolean notEmpty(Collection<?> collection) {
        return collection != null && !collection.isEmpty();
    }

    private static String ref(Utxo utxo) {
        return utxo.getTxHash() + "#" + utxo.getOutputIndex();
    }

    private static String ref(TransactionInput input) {
        return input.getTransactionId() + "#" + input.getIndex();
    }
}
