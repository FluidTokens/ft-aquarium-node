package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.TransactionInputComparator;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds <b>TX A</b> of a Lending v4 loan origination: mint one request NFT under the deployed
 * {@code request.request} policy, and lock the collateral plus that NFT at the request spend
 * address under a hand-encoded {@code RequestDatum}.
 *
 * <h2>Structurally incapable of submitting</h2>
 * The {@link QuickTxBuilder} below is constructed with a <b>null</b> {@code TransactionProcessor} —
 * the third constructor argument. A processor is the only thing in cardano-client-lib that can put
 * a transaction on a network, so a builder that never has one cannot submit, whatever a caller
 * asks of it. This mirrors {@code LiquidateTransactionBuilder}'s own constraint, minus the script
 * cost evaluator: nothing here prices the redeemers either, so the transaction TX A carries
 * placeholder ex-units and an understated fee. That is fine for an offline rig and is <b>not</b>
 * fine for submission; S6 has to price it against a real evaluator first.
 *
 * <h2>Consequence C — no validity interval</h2>
 * {@code check_mint} ({@code validators/request.ak:137-195}, deployed pin {@code bbe9c1a}) reads no
 * validity range, no signature and no withdrawal: it checks only that the redeemer's
 * {@code inputRef} is spent and that each minted token is named and placed correctly. So TX A is
 * built with <b>no {@code validFrom} and no {@code validTo}</b> — adding an interval would be
 * inventing a constraint the validator does not impose, and would have to be justified against
 * something. TX B (Lend) is different: {@code check_lend} does read the range.
 *
 * <h2>Why the seed is collected explicitly</h2>
 * {@code isInputRefSpent} demands {@code find_input(self.inputs, redeemer.inputRef)} be
 * {@code Some}. Leaving the seed to coin selection would let a balanced transaction drop it and
 * fail on chain, so it is collected by name.
 */
public final class RequestMintTransactionBuilder {

    private final LoansContractRegistry registry;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;

    public RequestMintTransactionBuilder(LoansContractRegistry registry,
                                         UtxoSupplier utxoSupplier,
                                         ProtocolParamsSupplier protocolParamsSupplier) {
        this.registry = registry;
        this.utxoSupplier = utxoSupplier;
        this.protocolParamsSupplier = protocolParamsSupplier;
    }

    /**
     * Everything TX A needs.
     *
     * @param seedUtxo            the UTxO the mint redeemer names as {@code inputRef}; also the
     *                            source of the collateral tokens
     * @param configUtxo          the main config reference input, read by {@code check_mint} at
     *                            {@code redeemer.configRefInputIndex}
     * @param borrowerAddress     fee payer, collateral payer and change address
     * @param requestAddress      the enterprise address of {@code requestSpendScriptHash}
     * @param requestAssetNameHex the 29-byte request NFT name (index prefix + hashed output ref)
     * @param requestDatum        the {@code RequestDatum} the request output carries inline
     * @param collateral          the collateral leg of the request output's value
     * @param requestLovelace     the lovelace leg of the request output's value
     */
    public record Request(Utxo seedUtxo,
                          Utxo configUtxo,
                          String borrowerAddress,
                          String requestAddress,
                          String requestAssetNameHex,
                          PlutusData requestDatum,
                          Amount collateral,
                          long requestLovelace) {
    }

    /**
     * Assembles and completes TX A.
     * <p>
     * The request output's value is exactly <b>{lovelace, collateral, 1 request NFT}</b> — three
     * assets, nothing more. That is load-bearing for TX B: {@code validate_output_to_loan}'s
     * {@code collateralUnchanged} ({@code request.ak:496-511}) compares the request value minus the
     * request NFT against the loan value minus the loan NFT, and its {@code dosProtection} counts
     * the flattened value.
     */
    public Transaction build(Request request) {
        ScriptTx tx = assemble(request, 0);
        Transaction built = complete(request, tx);

        // V5, in LiquidateTransactionBuilder's spirit: the index the redeemer claims is re-derived
        // from the finished body rather than assumed. With one reference input the answer is 0 —
        // asserted, not taken on faith, because a second reference input appearing here (a
        // reference script, say) would silently move it.
        int actual = configRefIndexOf(built, request.configUtxo());
        if (actual != 0) {
            throw new IllegalStateException(
                    "the mint redeemer claims configRefInputIndex 0 but the config reference input "
                            + "landed at " + actual + " in the finished body");
        }
        return built;
    }

    /**
     * As {@link #build}, but with the mint redeemer's {@code inputRef} and the NFT's asset name
     * overridden — the seam the adversarial mutations of {@link RequestMintDryEvalTest} use to hand
     * the machine a transaction this builder would never emit on its own.
     *
     * @param request              the otherwise-unmodified TX A request
     * @param inputRef             what the redeemer claims was spent
     * @param assetNameHexOverride the name the token is actually minted under
     */
    Transaction buildWithMutation(Request request, TransactionInput inputRef,
                                  String assetNameHexOverride) {
        ScriptTx tx = assembleWith(request, 0, inputRef, assetNameHexOverride);
        return complete(request, tx);
    }

    // ---- assembly ---------------------------------------------------------------------------------

    private ScriptTx assemble(Request request, long configRefInputIndex) {
        return assembleWith(request, configRefInputIndex,
                new TransactionInput(request.seedUtxo().getTxHash(), request.seedUtxo().getOutputIndex()),
                request.requestAssetNameHex());
    }

    private ScriptTx assembleWith(Request request, long configRefInputIndex,
                                  TransactionInput inputRef, String assetNameHex) {
        ScriptTx tx = new ScriptTx();

        // The seed, by name: coin selection must not be free to leave it out.
        tx.collectFrom(request.seedUtxo());

        // Minted without a receiver argument on purpose. The mintAsset(.., receiver, datum)
        // overload re-derives the amount through Amount.asset(policyId, asset.getName(), value),
        // and this repo has already been bitten by that name round-trip — see LoanFixtures.unit()'s
        // javadoc. The output is paid explicitly below instead.
        tx.mintAsset(registry.getRequestScript(),
                List.of(new Asset("0x" + assetNameHex, BigInteger.ONE)),
                RequestTxEncoder.requestMintRedeemer(configRefInputIndex, inputRef));

        List<Amount> requestValue = List.of(
                Amount.lovelace(BigInteger.valueOf(request.requestLovelace())),
                request.collateral(),
                Amount.asset(registry.getRequestPolicyId() + assetNameHex, BigInteger.ONE));
        tx.payToContract(request.requestAddress(), requestValue, request.requestDatum());

        tx.readFrom(new TransactionInput(request.configUtxo().getTxHash(),
                request.configUtxo().getOutputIndex()));

        return tx.withChangeAddress(request.borrowerAddress());
    }

    private Transaction complete(Request request, ScriptTx tx) {
        try {
            // The third argument is the TransactionProcessor and it stays null; see the class
            // javadoc. No evaluator is wired in either, so the redeemers keep cardano-client-lib's
            // placeholder ex-units.
            return new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null)
                    .compose(tx)
                    .feePayer(request.borrowerAddress())
                    .collateralPayer(request.borrowerAddress())
                    // A script supplier that resolves nothing: cardano-client-lib otherwise walks
                    // every reference input looking for a script to fetch, and this builder has no
                    // remote source. The request validator travels in the witness set.
                    .withScriptSupplier(scriptHash -> Optional.empty())
                    .mergeOutputs(false)
                    // No validFrom / validTo — consequence C.
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the request-mint transaction", e);
        }
    }

    /** Where the config landed in the canonically sorted reference-input set of the finished body. */
    private static int configRefIndexOf(Transaction tx, Utxo configUtxo) {
        List<TransactionInput> refInputs = new ArrayList<>(tx.getBody().getReferenceInputs());
        refInputs.sort(new TransactionInputComparator());
        int index = refInputs.indexOf(
                new TransactionInput(configUtxo.getTxHash(), configUtxo.getOutputIndex()));
        if (index < 0) {
            throw new IllegalStateException("the config reference input is missing from the body");
        }
        return index;
    }
}
