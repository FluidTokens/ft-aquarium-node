package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.TransactionInputComparator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Builds the <b>pool-borrow</b> transaction of a Lending v4 pool: spend a pool UTxO, mint the loan
 * NFT and both bonds under one shared asset name, and emit the loan output, both bond outputs and
 * the pool continuation, in a layout the deployed {@code pool_borrow_action}, {@code pool.pool},
 * {@code general_spend}, {@code loan.loan} and both {@code bond} validators accept under the real
 * PlutusV3 machine. For <b>one</b> pool input only; multi-pool batching is out of scope.
 *
 * <h2>Structurally incapable of submitting</h2>
 * The {@link QuickTxBuilder} below is constructed with a <b>null</b> {@code TransactionProcessor} —
 * the only thing in cardano-client-lib that can put a transaction on a network — exactly as
 * {@link PoolCreateTransactionBuilder} is. A builder that never has a processor cannot submit,
 * whatever a caller asks of it. No evaluator is wired in either, so the transaction carries
 * placeholder ex-units and an understated fee: fine for an offline rig, not fine for submission.
 * The real ex-units are measured separately by {@link PoolBorrowDryEvalTest} through the UPLC
 * machine.
 *
 * <h2>The withdraw-based shape (3 mints, 1 spend, 2 rewards, no oracle)</h2>
 * {@code pool.pool} has no {@code spend} handler ({@code validators/pool.ak} at {@code ff005fb}); the
 * pool UTxO sits at a {@code general_spend} address and its spend defers to a withdrawal of
 * {@code pool.pool} itself, whose {@code Borrow} action in turn requires a withdrawal of
 * {@code pool_borrow_action} — the validator that does the real work. So the transaction carries two
 * reward (withdraw-0) invocations:
 * <ul>
 *   <li>{@code pool.pool} with {@code PoolWithdrawRedeemer{configRefInputIndex, Borrow}} — this is
 *       the one {@code loan.loan}'s {@code check_mint} reads at {@code redeemer.originWithdrawRedeemerIndex}
 *       ({@code validators/loan.ak:122-135}), and the one {@code general_spend} requires present;</li>
 *   <li>{@code pool_borrow_action} with {@code PoolBorrowActionWithdrawRedeemer{configRefInputIndex,
 *       [BorrowData]}}.</li>
 * </ul>
 * Every script travels by <b>reference input</b> (the published preview coordinates), never in the
 * witness set: the six validators are declared to {@code withReferenceScripts} and their witness
 * copies stripped by {@code removeDuplicateScriptWitnesses}, the proven idiom of
 * {@code RealLoanDryEvalTest}. {@link PoolBorrowDryEvalTest} asserts the finished witness set holds
 * no Plutus scripts.
 *
 * <h2>The CCL absolute-output-0 seam</h2>
 * {@code pool_borrow_action} reads the pool continuation as {@code safe_list_at(outputs, index)} with
 * {@code index} the pool-input loop index — 0 for one pool ({@code pool_borrow_action.ak}
 * {@code validate_output_to_pool}). But cardano-client-lib prepends a 1-ADA dummy output to
 * {@code fromAddress} at index 0 whenever a transaction has withdrawals (the {@code WITHDRAWAL}
 * {@code DepositRefundContext} composed by {@code AbstractTx.complete()} <em>before</em> the
 * {@code payToContract} outputs, cardano-client-lib 0.7.2). A naive build therefore puts the dummy at
 * 0 and the pool at 1, and {@code validate_output_to_pool}'s {@code expect InlineDatum(..)} on the
 * dummy <b>aborts</b> — a phase-2 budget message, not a readable denial. So a
 * {@link #preBalanceOutputZeroSeam} transformer, run after the outputs exist but before script-cost
 * evaluation and balancing, moves the pool continuation to absolute index 0; and {@link #assertStructure}
 * refuses the finished body (by address + inline-datum bytes + pool-NFT value, not position alone) if
 * output 0 is not the pool continuation, so a layout regression fails as a named assertion here rather
 * than as the ledger's abort.
 *
 * <h2>Every index is derived from the finished body</h2>
 * {@code outputWithLenderTokenIndex} / {@code outputWithBorrowerTokenIndex} are absolute output
 * positions, and {@code originWithdrawRedeemerIndex} is a position in the Plutus-ordered redeemer
 * list — both are things cardano-client-lib decides, not this builder. As
 * {@code LiquidateTransactionBuilder} does, the transaction is assembled once with placeholder
 * indexes purely to observe the finished layout (the <em>probe</em>), the real indexes are read off
 * that body, and it is assembled again; then {@link #assertStructure} (V5) re-derives every index
 * from the FINISHED body and refuses on any mismatch. {@code configRefInputIndex} is deterministic
 * (the config's position among the canonically sorted reference inputs) and needs no probe, but V5
 * re-checks it too.
 */
public final class PoolBorrowTransactionBuilder {

    /** The unit redeemer {@code Constr 0 []} the {@code general_spend} handler takes. */
    private static final PlutusData GENERAL_SPEND_REDEEMER =
            ConstrPlutusData.builder().alternative(0).data(ListPlutusData.of()).build();

    /**
     * {@code retrieve_oracle_data} is never called on our pool ({@code dynamicCollateralPrice = false},
     * so {@code pool_borrow_action.ak:116-161}'s {@code if dynamicCollateralPrice} branch — the only
     * reader of {@code principalOracleRefInputIndex} and {@code chosenCollateralOracleRefInputIndex} —
     * is not taken), yet {@code safe_list_at} still {@code expect index >= 0}. Zero is the value the
     * deployed template's own {@code principalOracleRefInputIndex} carries, chain evidence that 0 is
     * safe for an unread oracle index. This is the one literal index the invariant sanctions.
     */
    private static final long UNREAD_ORACLE_REF_INDEX = 0L;

    /**
     * The pool is permissionless ({@code permissionedConditionScriptHash == "NONE"}), so
     * {@code pool_borrow_action.ak}'s {@code or { == no_permissioned_condition, .. }} short-circuits on
     * the first disjunct and never evaluates {@code safe_list_at(withdrawals,
     * permissionedConditionWithdrawIndex)}. The index is therefore unread; 0 is inert.
     */
    private static final long UNREAD_PERMISSIONED_WITHDRAW_INDEX = 0L;

    private final LoansContractRegistry registry;
    private final Network network;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;

    public PoolBorrowTransactionBuilder(LoansContractRegistry registry,
                                        Network network,
                                        UtxoSupplier utxoSupplier,
                                        ProtocolParamsSupplier protocolParamsSupplier) {
        this.registry = registry;
        this.network = network;
        this.utxoSupplier = utxoSupplier;
        this.protocolParamsSupplier = protocolParamsSupplier;
    }

    /**
     * Everything the pool-borrow transaction needs, all of it the honest values — the adversarial
     * shapes {@link PoolBorrowDryEvalTest} feeds the machine are produced by byte-surgery on the
     * finished body, not by mis-configuring this request.
     *
     * @param poolUtxo             the pool input, spent through {@code general_spend}; its inline datum
     *                             is reused <b>verbatim</b> as the continuation's datum
     * @param funderUtxo           fees, the collateral the loan output posts, and the change source
     * @param configUtxo           the config reference input
     * @param referenceScriptUtxos the six published reference-script UTxOs (pool.pool, general_spend,
     *                             pool_borrow_action, loan.loan, lender bond, borrower bond)
     * @param funderAddress        fee payer and change address
     * @param borrowerAddress      {@code BorrowData.borrowerAddress}; its stake credential is what the
     *                             loan output must carry ({@code validate_output_to_loan}'s
     *                             {@code correctDestination})
     * @param wantedPrincipalAmount principal drawn from the pool ({@code > 0}); the continuation is the
     *                             pool input minus exactly this many lovelace
     * @param chosenCollateralIndex index into the pool datum's collateral options (0 for our pool)
     * @param collateralAsset      the collateral token posted into the loan output (tFLDT)
     * @param neededCollateral     the collateral quantity in the loan output
     * @param loanOutputLovelace   the loan output's lovelace leg
     * @param poolAssetNameHex     the 29-byte pool NFT asset name — also {@code BorrowData.poolId}
     * @param bondAssetName        the one asset name shared by all three mints: {@code hash_output_ref(poolRef)}
     * @param loanDatum            the {@code LoanDatum} the loan output carries inline
     * @param lenderBondAddress    the pool datum's {@code lenderBondAddress}
     * @param lenderBondDatum      the lender-bond inline datum ({@code blake2b_256} of its bytes is the
     *                             pool's committed {@code lenderBondInlineDatumHash})
     * @param lenderBondLovelace   the lender-bond output's lovelace leg
     * @param borrowerBondLovelace the borrower-bond output's lovelace leg
     * @param validFromSlot        the validity range lower bound, in slots
     * @param validToSlot          the validity range upper bound, in slots; its POSIX time is the loan
     *                             datum's {@code lendDate}
     */
    public record Request(Utxo poolUtxo,
                          Utxo funderUtxo,
                          Utxo configUtxo,
                          List<Utxo> referenceScriptUtxos,
                          String funderAddress,
                          Address borrowerAddress,
                          long wantedPrincipalAmount,
                          long chosenCollateralIndex,
                          com.fluidtokens.aquarium.offchain.model.AssetType collateralAsset,
                          long neededCollateral,
                          long loanOutputLovelace,
                          String poolAssetNameHex,
                          String bondAssetName,
                          PlutusData loanDatum,
                          String lenderBondAddress,
                          PlutusData lenderBondDatum,
                          long lenderBondLovelace,
                          long borrowerBondLovelace,
                          long validFromSlot,
                          long validToSlot) {
    }

    /**
     * Assembles and completes the pool-borrow transaction: probe for the layout, rebuild with the
     * observed indexes, then re-derive and assert every index against the finished body (V5).
     */
    public Transaction build(Request request) {
        return build(request, true);
    }

    /**
     * The same probe-then-rebuild build, but with the absolute-output-0 seam <b>disabled</b> and V5
     * skipped — so it emits exactly the layout cardano-client-lib produces on its own: the 1-ADA
     * withdrawal dummy at output 0 and the pool continuation pushed to output 1. Every redeemer index
     * is still derived correctly from that naive body, so the <em>only</em> thing wrong with the result
     * is the pool's position; feeding it to the machine isolates {@code validate_output_to_pool}'s abort
     * on the dummy. Package-private, no production caller: it exists so {@link PoolBorrowDryEvalTest} can
     * measure the broken layout and prove the machine refuses it.
     */
    Transaction buildNaive(Request request) {
        return build(request, false);
    }

    private Transaction build(Request request, boolean applySeam) {
        long configRefIndex = configRefIndexAmong(request);

        // Probe — placeholder output/withdraw indexes, purely to observe the finished layout. The
        // probe is a transaction the validators would refuse (its BorrowData names output 0 as the
        // lender-token output), so it is never evaluated: it is thrown away after the indexes are read
        // off it, and V5 re-derives them from the finished body anyway.
        Transaction probe = complete(request,
                assemble(request, configRefIndex, 0L, 0L, 0L), applySeam);

        long lenderTokenIndex = locateOutput(probe, request.lenderBondAddress(),
                registry.getLenderBondPolicyId(), request.bondAssetName(), "lender bond");
        long borrowerTokenIndex = locateOutput(probe, request.borrowerAddress().getAddress(),
                registry.getBorrowerBondPolicyId(), request.bondAssetName(), "borrower bond");
        long originWithdrawRedeemerIndex = deriveOriginWithdrawRedeemerIndex(probe);

        Transaction transaction = complete(request,
                assemble(request, configRefIndex, lenderTokenIndex, borrowerTokenIndex,
                        originWithdrawRedeemerIndex), applySeam);

        if (applySeam) {
            assertStructure(transaction, request, configRefIndex, lenderTokenIndex, borrowerTokenIndex,
                    originWithdrawRedeemerIndex);
        }
        return transaction;
    }

    // ---- assembly ---------------------------------------------------------------------------------

    private ScriptTx assemble(Request request, long configRefIndex, long lenderTokenIndex,
                              long borrowerTokenIndex, long originWithdrawRedeemerIndex) {
        ScriptTx tx = new ScriptTx();

        // Inputs: the pool (through general_spend, unit redeemer — the real authorisation is the
        // pool.pool withdraw it defers to) and the funder (fees + collateral + change).
        tx.collectFrom(request.poolUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.funderUtxo());

        // The three mints, one asset name = hash_output_ref(poolRef). The loan mint's
        // originWithdrawRedeemerIndex points at the pool.pool withdraw; both bond redeemers name the
        // pool output reference, positionally aligned with the single minted token.
        TransactionInput poolRef = inputOf(request.poolUtxo());
        Asset one = new Asset("0x" + request.bondAssetName(), BigInteger.ONE);
        tx.mintAsset(registry.getLoanScript(), List.of(one),
                LiquidationTxEncoder.loanMintRedeemer(configRefIndex, true, originWithdrawRedeemerIndex));
        tx.mintAsset(registry.getLenderBondScript(), List.of(one),
                PoolTxEncoder.bondRedeemer(List.of(poolRef)));
        tx.mintAsset(registry.getBorrowerBondScript(), List.of(one),
                PoolTxEncoder.bondRedeemer(List.of(poolRef)));

        // Outputs. The pool continuation is emitted here but forced to absolute index 0 by the
        // preBalance seam in complete(); the loan output is located by the loanSpend filter, so its
        // absolute position is free; the two bond outputs are named by the redeemer indexes above.
        tx.payToContract(request.poolUtxo().getAddress(), poolContinuationValue(request),
                poolContinuationDatum(request));
        tx.payToContract(loanOutputAddress(request), loanOutputValue(request), request.loanDatum());
        tx.payToContract(request.lenderBondAddress(), bondOutputValue(request,
                registry.getLenderBondPolicyId(), request.lenderBondLovelace()), request.lenderBondDatum());
        tx.payToContract(request.borrowerAddress().getAddress(), bondOutputValue(request,
                registry.getBorrowerBondPolicyId(), request.borrowerBondLovelace()), request.loanDatum());

        // The two withdraw-0 invocations. pool.pool(Borrow) is the one loan.loan's check_mint reads;
        // pool_borrow_action carries the BorrowData.
        tx.withdraw(rewardAddress(registry.getPoolPolicyId()), BigInteger.ZERO,
                PoolTxEncoder.poolWithdrawRedeemer(configRefIndex, PoolTxEncoder.ACTION_BORROW),
                request.funderAddress());
        tx.withdraw(rewardAddress(registry.getPoolBorrowActionScriptHash()), BigInteger.ZERO,
                PoolTxEncoder.poolBorrowActionWithdrawRedeemer(configRefIndex,
                        List.of(borrowData(request, lenderTokenIndex, borrowerTokenIndex))),
                request.funderAddress());

        // Reference inputs: config + the six published reference scripts.
        tx.readFrom(inputOf(request.configUtxo()));
        for (Utxo refScript : request.referenceScriptUtxos()) {
            tx.readFrom(inputOf(refScript));
        }

        return tx.withChangeAddress(request.funderAddress());
    }

    private PoolTxEncoder.BorrowData borrowData(Request request, long lenderTokenIndex,
                                                long borrowerTokenIndex) {
        return new PoolTxEncoder.BorrowData(
                request.borrowerAddress(),
                lenderTokenIndex,
                borrowerTokenIndex,
                UNREAD_ORACLE_REF_INDEX,
                request.chosenCollateralIndex(),
                UNREAD_ORACLE_REF_INDEX,
                BigInteger.valueOf(request.wantedPrincipalAmount()),
                request.poolAssetNameHex(),
                UNREAD_PERMISSIONED_WITHDRAW_INDEX);
    }

    private Transaction complete(Request request, ScriptTx tx, boolean applySeam) {
        try {
            // Third argument (TransactionProcessor) stays null; see the class javadoc. No evaluator is
            // wired in either, so the redeemers keep placeholder ex-units.
            return new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null)
                    .compose(tx)
                    .feePayer(request.funderAddress())
                    .collateralPayer(request.funderAddress())
                    .validFrom(request.validFromSlot())
                    .validTo(request.validToSlot())
                    .mergeOutputs(false)
                    // The six validators travel by reference input; declaring them here and stripping
                    // the witness copies keeps the witness set empty (the proven RealLoanDryEvalTest
                    // idiom). readFrom in assemble() puts their coordinates in the body.
                    .withReferenceScripts(referencedScripts())
                    .removeDuplicateScriptWitnesses(true)
                    // Offline: cardano-client-lib would otherwise walk every reference input for a
                    // script to fetch and NPE on the missing supplier.
                    .withScriptSupplier(scriptHash -> Optional.empty())
                    // The seam: force the pool continuation to absolute output 0 after the outputs
                    // exist but before script-cost evaluation and balancing. Disabled by buildNaive.
                    .preBalanceTx((context, transaction) -> {
                        if (applySeam) {
                            preBalanceOutputZeroSeam(request, transaction);
                        }
                    })
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the pool-borrow transaction", e);
        }
    }

    private PlutusScript[] referencedScripts() {
        return new PlutusScript[]{
                registry.getPoolScript(),
                registry.getPoolSpendScript(),
                registry.getPoolBorrowActionScript(),
                registry.getLoanScript(),
                registry.getLenderBondScript(),
                registry.getBorrowerBondScript()};
    }

    /**
     * The CCL absolute-output-0 seam. Finds the pool continuation in the finished output list —
     * matched on address, verbatim inline-datum bytes and the pool NFT, never on position — and moves
     * it to index 0 if it is not already there. The dummy 1-ADA output cardano-client-lib prepended is
     * left where it falls: it sits at the funder credential, so no {@code get_outputs_to_smart_credential}
     * filter and no positional read of the pool / loan / bond outputs ever touches it.
     */
    private void preBalanceOutputZeroSeam(Request request, Transaction transaction) {
        List<TransactionOutput> outputs = transaction.getBody().getOutputs();
        int poolIndex = indexOfPoolOutput(request, outputs);
        if (poolIndex < 0) {
            throw new IllegalStateException(
                    "the pool continuation is not among the finished outputs — nothing to move to index 0");
        }
        if (poolIndex != 0) {
            TransactionOutput pool = outputs.remove(poolIndex);
            outputs.add(0, pool);
        }
    }

    // ---- V5: re-derive every index from the finished body -----------------------------------------

    private void assertStructure(Transaction transaction, Request request, long configRefIndex,
                                 long lenderTokenIndex, long borrowerTokenIndex,
                                 long originWithdrawRedeemerIndex) {
        List<TransactionOutput> outputs = transaction.getBody().getOutputs();

        // The seam: output 0 is the pool continuation, by address + datum bytes + pool NFT.
        structural(indexOfPoolOutput(request, outputs) == 0,
                "the pool continuation is not at absolute output 0 in the finished body");

        // Invariant 5: the continuation's inline datum is the pool input's bytes, verbatim.
        String continuationDatumHex = outputs.get(0).getInlineDatum().serializeToHex();
        structural(continuationDatumHex.equalsIgnoreCase(request.poolUtxo().getInlineDatum()),
                "the pool continuation datum is not byte-identical to the pool input datum: input "
                        + request.poolUtxo().getInlineDatum() + ", output " + continuationDatumHex);

        // The pool continuation value is the input minus exactly wantedPrincipalAmount lovelace, and
        // still carries the pool NFT alone beside lovelace.
        BigInteger expectedLovelace = poolInputLovelace(request)
                .subtract(BigInteger.valueOf(request.wantedPrincipalAmount()));
        structural(outputs.get(0).getValue().getCoin().equals(expectedLovelace),
                "the pool continuation lovelace is not the input minus wantedPrincipalAmount");

        // configRefInputIndex points at the config among the canonically sorted reference inputs.
        List<TransactionInput> sortedRefInputs = transaction.getBody().getReferenceInputs().stream()
                .sorted(new TransactionInputComparator())
                .toList();
        structural(configRefIndex >= 0 && configRefIndex < sortedRefInputs.size()
                        && sortedRefInputs.get((int) configRefIndex).equals(inputOf(request.configUtxo())),
                "configRefInputIndex " + configRefIndex + " does not point at the config reference input");

        // The two bond-token indexes point at the bond outputs the redeemer named.
        structural(lenderTokenIndex == locateOutput(transaction, request.lenderBondAddress(),
                        registry.getLenderBondPolicyId(), request.bondAssetName(), "lender bond"),
                "outputWithLenderTokenIndex " + lenderTokenIndex + " does not point at the lender bond output");
        structural(borrowerTokenIndex == locateOutput(transaction, request.borrowerAddress().getAddress(),
                        registry.getBorrowerBondPolicyId(), request.bondAssetName(), "borrower bond"),
                "outputWithBorrowerTokenIndex " + borrowerTokenIndex
                        + " does not point at the borrower bond output");
        structural(lenderTokenIndex != borrowerTokenIndex,
                "the lender and borrower bond tokens cannot share one output");

        // originWithdrawRedeemerIndex points at the pool.pool withdraw redeemer.
        structural(originWithdrawRedeemerIndex == deriveOriginWithdrawRedeemerIndex(transaction),
                "originWithdrawRedeemerIndex " + originWithdrawRedeemerIndex
                        + " does not point at the pool.pool withdraw redeemer");
        structural(isPoolPolicyWithdrawRedeemer(
                        redeemersInScriptPurposeOrder(transaction).get((int) originWithdrawRedeemerIndex)),
                "the redeemer at originWithdrawRedeemerIndex " + originWithdrawRedeemerIndex
                        + " is not a PoolWithdrawRedeemer");

        // Nothing in the witness set: every script travels by reference input.
        structural(transaction.getWitnessSet().getPlutusV3Scripts() == null
                        || transaction.getWitnessSet().getPlutusV3Scripts().isEmpty(),
                "the witness set carries Plutus scripts; the six validators must travel by reference");
    }

    // ---- derivations ------------------------------------------------------------------------------

    /** Where the config would sort among the canonically ordered reference inputs. */
    private long configRefIndexAmong(Request request) {
        List<TransactionInput> refInputs = new ArrayList<>();
        refInputs.add(inputOf(request.configUtxo()));
        for (Utxo refScript : request.referenceScriptUtxos()) {
            refInputs.add(inputOf(refScript));
        }
        refInputs.sort(new TransactionInputComparator());
        return refInputs.indexOf(inputOf(request.configUtxo()));
    }

    /**
     * The absolute index of the single output at {@code address} that carries {@code policyId ‖
     * assetName}. Refuses if it is not unique — an ambiguous match would let a redeemer index aim at
     * the wrong output.
     */
    private static long locateOutput(Transaction tx, String address, String policyId, String assetNameHex,
                                     String what) {
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput output = outputs.get(i);
            if (address.equals(output.getAddress())
                    && quantityOf(output, policyId, assetNameHex).equals(BigInteger.ONE)) {
                matches.add(i);
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(what + " output matches " + matches.size()
                    + " outputs, expected exactly one");
        }
        return matches.get(0).longValue();
    }

    /**
     * The position of the {@code pool.pool} withdraw redeemer in the Plutus-ordered redeemer list —
     * the value {@code loan.loan}'s {@code check_mint} reads at {@code originWithdrawRedeemerIndex}.
     * <p>
     * This is a derivation, not a literal: the pool.pool withdraw redeemer is identified by its content
     * (a {@code PoolWithdrawRedeemer}, whose second field is the fieldless {@code Action} constructor —
     * as opposed to {@code pool_borrow_action}'s second field, a list of {@code BorrowData}), and its
     * position is read off the redeemers sorted into script-purpose order. For this transaction's shape
     * — 3 mints, 1 spend, 2 rewards, no oracle — the two candidate orderings a PlutusV3 machine could
     * present {@code self.redeemers} in both agree, and this method asserts they do:
     * <ul>
     *   <li>script-purpose order ({@code Mint < Spend < Reward}) and</li>
     *   <li>Conway {@code RdmrPtr} order ({@code Spend < Mint < Reward}, the {@link RedeemerTag} values)</li>
     * </ul>
     * put every mint and the spend before both rewards, so a reward's position is
     * {@code #mints + #spends + rewardOrdinal} either way; here {@code 3 + 1 + 1 = 5}. Derived rather
     * than hardcoded so it stays correct when a second spend or a third withdrawal shifts the layout.
     */
    private long deriveOriginWithdrawRedeemerIndex(Transaction transaction) {
        long viaScriptPurpose = positionUnder(transaction, new int[]{1, 0, 2, 3, 4, 5});
        long viaConway = positionUnder(transaction, new int[]{0, 1, 2, 3, 4, 5});
        if (viaScriptPurpose != viaConway) {
            throw new IllegalStateException(
                    "the two candidate redeemer orderings disagree on originWithdrawRedeemerIndex: "
                            + "script-purpose " + viaScriptPurpose + ", Conway " + viaConway
                            + " — this transaction's shape is no longer one where they coincide");
        }
        return viaScriptPurpose;
    }

    /**
     * The position of the pool.pool withdraw redeemer once every redeemer is sorted by
     * {@code (rank[tag], index)}, where {@code rank} maps a {@link RedeemerTag} value to its ordering
     * priority. Used once per candidate ordering.
     */
    private long positionUnder(Transaction transaction, int[] rankByTagValue) {
        List<Redeemer> sorted = new ArrayList<>(transaction.getWitnessSet().getRedeemers());
        sorted.sort(Comparator
                .<Redeemer>comparingInt(r -> rankByTagValue[r.getTag().value])
                .thenComparing(Redeemer::getIndex));
        for (int i = 0; i < sorted.size(); i++) {
            Redeemer r = sorted.get(i);
            if (r.getTag() == RedeemerTag.Reward && isPoolPolicyWithdrawRedeemer(r)) {
                return i;
            }
        }
        throw new IllegalStateException("no pool.pool withdraw redeemer in the body");
    }

    /** The redeemers sorted into script-purpose order, for a single positional assertion in V5. */
    private static List<Redeemer> redeemersInScriptPurposeOrder(Transaction transaction) {
        List<Redeemer> sorted = new ArrayList<>(transaction.getWitnessSet().getRedeemers());
        int[] rank = {1, 0, 2, 3, 4, 5};
        sorted.sort(Comparator
                .<Redeemer>comparingInt(r -> rank[r.getTag().value])
                .thenComparing(Redeemer::getIndex));
        return sorted;
    }

    /**
     * True if {@code redeemer} is a {@code PoolWithdrawRedeemer}: a constructor whose second field is a
     * fieldless {@code Action} constructor, as opposed to {@code pool_borrow_action}'s redeemer, whose
     * second field is a list of {@code BorrowData}. Content-based, so it survives any redeemer ordering.
     */
    private static boolean isPoolPolicyWithdrawRedeemer(Redeemer redeemer) {
        if (redeemer.getTag() != RedeemerTag.Reward
                || !(redeemer.getData() instanceof ConstrPlutusData constr)
                || constr.getData().getPlutusDataList().size() != 2) {
            return false;
        }
        return constr.getData().getPlutusDataList().get(1) instanceof ConstrPlutusData;
    }

    // ---- values, addresses, datum -----------------------------------------------------------------

    private List<Amount> poolContinuationValue(Request request) {
        BigInteger lovelace = poolInputLovelace(request)
                .subtract(BigInteger.valueOf(request.wantedPrincipalAmount()));
        return List.of(Amount.lovelace(lovelace),
                Amount.asset(registry.getPoolPolicyId() + request.poolAssetNameHex(), BigInteger.ONE));
    }

    private PlutusData poolContinuationDatum(Request request) {
        try {
            return ConstrPlutusData.deserialize(
                    com.bloxbean.cardano.client.common.cbor.CborSerializationUtil.deserialize(
                            HexUtil.decodeHexString(request.poolUtxo().getInlineDatum())));
        } catch (Exception e) {
            throw new IllegalStateException("cannot decode the pool input datum for verbatim reuse", e);
        }
    }

    private List<Amount> loanOutputValue(Request request) {
        return List.of(
                Amount.lovelace(BigInteger.valueOf(request.loanOutputLovelace())),
                Amount.asset(registry.getLoanPolicyId() + request.bondAssetName(), BigInteger.ONE),
                Amount.asset(unit(request.collateralAsset()),
                        BigInteger.valueOf(request.neededCollateral())));
    }

    private List<Amount> bondOutputValue(Request request, String bondPolicyId, long lovelace) {
        return List.of(Amount.lovelace(BigInteger.valueOf(lovelace)),
                Amount.asset(bondPolicyId + request.bondAssetName(), BigInteger.ONE));
    }

    /** The loan output address: the loan spend script, carrying the borrower's stake credential. */
    private String loanOutputAddress(Request request) {
        Credential stake = request.borrowerAddress().getDelegationCredential()
                .orElseThrow(() -> new IllegalStateException(
                        "the borrower address has no stake credential to place on the loan output"));
        return AddressProvider.getBaseAddress(
                Credential.fromScript(registry.getLoanSpendScriptHash()), stake, network).getAddress();
    }

    private String rewardAddress(String scriptHash) {
        return AddressProvider.getRewardAddress(Credential.fromScript(scriptHash), network).getAddress();
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private int indexOfPoolOutput(Request request, List<TransactionOutput> outputs) {
        String poolUnit = registry.getPoolPolicyId() + request.poolAssetNameHex();
        String poolAddress = request.poolUtxo().getAddress();
        String poolDatumHex = request.poolUtxo().getInlineDatum();
        int found = -1;
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput output = outputs.get(i);
            if (poolAddress.equals(output.getAddress())
                    && output.getInlineDatum() != null
                    && output.getInlineDatum().serializeToHex().equalsIgnoreCase(poolDatumHex)
                    && quantityOf(output, registry.getPoolPolicyId(),
                    request.poolAssetNameHex()).equals(BigInteger.ONE)) {
                if (found >= 0) {
                    throw new IllegalStateException("more than one output matches the pool continuation ("
                            + poolUnit + ")");
                }
                found = i;
            }
        }
        return found;
    }

    private BigInteger poolInputLovelace(Request request) {
        return request.poolUtxo().getAmount().stream()
                .filter(a -> "lovelace".equals(a.getUnit()))
                .map(Amount::getQuantity)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("the pool input has no lovelace"));
    }

    private static String unit(com.fluidtokens.aquarium.offchain.model.AssetType asset) {
        return asset.policyId() + asset.assetName();
    }

    private static BigInteger quantityOf(TransactionOutput output, String policyId, String assetNameHex) {
        return output.getValue().getMultiAssets().stream()
                .filter(ma -> ma.getPolicyId().equalsIgnoreCase(policyId))
                .flatMap(ma -> ma.getAssets().stream())
                .filter(a -> strip(a.getNameAsHex()).equalsIgnoreCase(assetNameHex))
                .map(Asset::getValue)
                .findFirst()
                .orElse(BigInteger.ZERO);
    }

    private static String strip(String maybePrefixed) {
        return maybePrefixed.startsWith("0x") ? maybePrefixed.substring(2) : maybePrefixed;
    }

    private static TransactionInput inputOf(Utxo utxo) {
        return new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex());
    }

    private static void structural(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("STRUCTURAL_ASSERTION_FAILED: " + message);
        }
    }
}
