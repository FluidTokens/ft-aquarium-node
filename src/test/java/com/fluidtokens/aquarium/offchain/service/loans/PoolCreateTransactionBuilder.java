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
 * Builds the <b>pool-creation</b> transaction of a Lending v4 pool: mint one pool NFT under the
 * deployed {@code pool.pool} policy, and lock the pool liquidity plus that NFT at the pool spend
 * address under a hand-encoded {@code PoolDatum}.
 *
 * <h2>Structurally incapable of submitting</h2>
 * The {@link QuickTxBuilder} below is constructed with a <b>null</b> {@code TransactionProcessor} —
 * the only thing in cardano-client-lib that can put a transaction on a network — exactly as
 * {@link RequestMintTransactionBuilder} is. A builder that never has a processor cannot submit,
 * whatever a caller asks of it. No evaluator is wired in either, so the transaction carries
 * placeholder ex-units and an understated fee: fine for an offline rig, not fine for submission.
 *
 * <h2>Consequence B — the datum is not validated at mint</h2>
 * {@code pool.pool}'s {@code mint} handler ({@code validators/pool.ak:65-124}, deployed pin
 * {@code bbe9c1a}) checks only that the config NFT is present on the reference input at
 * {@code configRefInputIndex}, that {@code redeemer.inputRef} is spent, and that each minted pool
 * token is named {@code index ‖ blake2b_224(serialise_data(inputRef))} and sits alone in the
 * correspondingly indexed output at the pool spend credential. It never reads the {@code PoolDatum}.
 *
 * <h2>Consequence C — no validity interval</h2>
 * {@code check_mint} reads no validity range, no signature and no withdrawal, so this transaction is
 * built with none.
 *
 * <h2>Two reference inputs, so the config index is re-derived</h2>
 * Unlike the request mint's single reference input, this transaction reads two — the config and the
 * published pool-policy reference script — so {@code configRefInputIndex} is <b>not</b> a hardcoded
 * zero. It is computed from the canonically sorted reference-input set the finished body carries and
 * asserted, in the spirit of {@link RequestMintTransactionBuilder}.
 */
public final class PoolCreateTransactionBuilder {

    private final LoansContractRegistry registry;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;

    public PoolCreateTransactionBuilder(LoansContractRegistry registry,
                                        UtxoSupplier utxoSupplier,
                                        ProtocolParamsSupplier protocolParamsSupplier) {
        this.registry = registry;
        this.utxoSupplier = utxoSupplier;
        this.protocolParamsSupplier = protocolParamsSupplier;
    }

    /**
     * Everything the pool-creation transaction needs.
     *
     * @param seedUtxo               the UTxO the mint redeemer names as {@code inputRef}; also the
     *                               source of the pool liquidity and the fee
     * @param configUtxo             the config reference input, read at {@code configRefInputIndex}
     * @param poolPolicyRefScriptUtxo the published pool-policy reference-script UTxO (a second
     *                               reference input)
     * @param funderAddress          fee payer, collateral payer and change address
     * @param poolAddress            the enterprise address of {@code poolSpendScriptHash}
     * @param poolAssetNameHex       the 29-byte pool NFT name (index prefix + hashed output ref)
     * @param poolDatum              the {@code PoolDatum} the pool output carries inline
     * @param poolLovelace           the pool liquidity leg of the pool output's value
     */
    public record Request(Utxo seedUtxo,
                          Utxo configUtxo,
                          Utxo poolPolicyRefScriptUtxo,
                          String funderAddress,
                          String poolAddress,
                          String poolAssetNameHex,
                          PlutusData poolDatum,
                          long poolLovelace) {
    }

    /**
     * Assembles and completes the pool-creation transaction.
     * <p>
     * The pool output's value is exactly {@code {poolLovelace, 1 pool NFT}}. The mint redeemer's
     * {@code configRefInputIndex} is re-derived from the finished body's canonically sorted reference
     * inputs and asserted against the value the redeemer actually claimed.
     */
    public Transaction build(Request request) {
        return buildWith(request,
                new TransactionInput(request.seedUtxo().getTxHash(), request.seedUtxo().getOutputIndex()),
                request.poolAssetNameHex(),
                request.poolAddress());
    }

    /**
     * As {@link #build}, but with the mint redeemer's {@code inputRef}, the NFT's asset name and the
     * pool output's address overridden — the seam the adversarial mutations of
     * {@link PoolCreateDryEvalTest} use to hand the machine a transaction this builder would never
     * emit on its own.
     */
    Transaction buildWith(Request request, TransactionInput inputRefOverride,
                          String assetNameOverride, String poolAddressOverride) {
        long configRefInputIndex = configRefIndexAmong(request);
        ScriptTx tx = assemble(request, configRefInputIndex, inputRefOverride, assetNameOverride,
                poolAddressOverride);
        Transaction built = complete(request, tx);

        int actual = configRefIndexOf(built, request.configUtxo());
        if (actual != configRefInputIndex) {
            throw new IllegalStateException(
                    "the mint redeemer claims configRefInputIndex " + configRefInputIndex
                            + " but the config reference input landed at " + actual
                            + " in the finished body");
        }
        return built;
    }

    // ---- assembly ---------------------------------------------------------------------------------

    private ScriptTx assemble(Request request, long configRefInputIndex, TransactionInput inputRef,
                              String assetNameHex, String poolAddress) {
        ScriptTx tx = new ScriptTx();

        // The seed, by name: coin selection must not be free to leave it out.
        tx.collectFrom(request.seedUtxo());

        // Minted without a receiver argument, for the same reason RequestMintTransactionBuilder does:
        // the mintAsset(.., receiver, datum) overload re-derives the amount through
        // Amount.asset(policyId, asset.getName(), value), and this repo has been bitten by that name
        // round-trip (see LoanFixtures.unit()). The output is paid explicitly below instead.
        tx.mintAsset(registry.getPoolScript(),
                List.of(new Asset("0x" + assetNameHex, BigInteger.ONE)),
                PoolTxEncoder.poolMintRedeemer(configRefInputIndex, inputRef));

        List<Amount> poolValue = List.of(
                Amount.lovelace(BigInteger.valueOf(request.poolLovelace())),
                Amount.asset(registry.getPoolPolicyId() + assetNameHex, BigInteger.ONE));
        tx.payToContract(poolAddress, poolValue, request.poolDatum());

        tx.readFrom(new TransactionInput(request.configUtxo().getTxHash(),
                request.configUtxo().getOutputIndex()));
        tx.readFrom(new TransactionInput(request.poolPolicyRefScriptUtxo().getTxHash(),
                request.poolPolicyRefScriptUtxo().getOutputIndex()));

        return tx.withChangeAddress(request.funderAddress());
    }

    private Transaction complete(Request request, ScriptTx tx) {
        try {
            // The third argument is the TransactionProcessor and it stays null; see the class
            // javadoc. No evaluator is wired in either, so the redeemer keeps cardano-client-lib's
            // placeholder ex-units.
            return new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null)
                    .compose(tx)
                    .feePayer(request.funderAddress())
                    .collateralPayer(request.funderAddress())
                    // A script supplier that resolves nothing: the pool policy travels in the witness
                    // set (attached by mintAsset), and there is no remote source for anything else.
                    .withScriptSupplier(scriptHash -> Optional.empty())
                    .mergeOutputs(false)
                    // No validFrom / validTo — consequence C.
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the pool-creation transaction", e);
        }
    }

    /** Where the config would land in the canonically sorted set of the two reference inputs. */
    private static long configRefIndexAmong(Request request) {
        List<TransactionInput> refInputs = new ArrayList<>(List.of(
                new TransactionInput(request.configUtxo().getTxHash(), request.configUtxo().getOutputIndex()),
                new TransactionInput(request.poolPolicyRefScriptUtxo().getTxHash(),
                        request.poolPolicyRefScriptUtxo().getOutputIndex())));
        refInputs.sort(new TransactionInputComparator());
        return refInputs.indexOf(
                new TransactionInput(request.configUtxo().getTxHash(), request.configUtxo().getOutputIndex()));
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
