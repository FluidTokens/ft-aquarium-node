package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.function.TxBuilder;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Builds the unsigned repayment-escrow <b>compound</b> transaction: collect a repaid loan's principal
 * from the asset manager and deliver it into the lender's pool, keeping the pool owner's compounding
 * fee (findings §20, §22).
 *
 * <h2>Shape</h2>
 * Four script spends, each authorised by a withdrawal because {@code general_spend} only checks that
 * one exists for its credential, plus seven withdraw-0 invocations — <b>eleven redeemers</b>
 * (§22.1–22.2). One candidate per transaction: the list-shaped redeemers carry exactly one element.
 * Batching several escrows is legal on chain and deliberately not attempted here — every index in
 * §22.3 becomes a genuine list the moment it is, and the failure mode of the worst of them is silent.
 *
 * <h2>⛔ The three index spaces</h2>
 * "Index" means three different things in this transaction (§22.3), and only one of the three can be
 * wrong <em>and still evaluate green</em>: the {@code self.redeemers} positions inside
 * {@code CompoundLiquidityActionWithdrawRedeemer}, because {@code pm_compound_liquidity} gates purely
 * on the action tags it finds there. An offline dry-eval cannot be relied on to catch it — the wrong
 * sibling's tag still type-checks. <b>So the guard is here, in the builder, as a hard refusal derived
 * from the FINISHED body</b>, matching each cited position by its script purpose and never by the
 * redeemer's data shape (CCL trap 14: two of these redeemers are byte-indistinguishable).
 *
 * <h2>⛔ Three byte-identical echoes</h2>
 * {@code lm_compound_action} requires {@code equals_data} on the lender bond and the pool manager,
 * and {@code pool_compound_action} requires it on the pool's datum. CCL trap 4: a decode→re-encode
 * round trip is not byte-stable, so each datum is round-tripped and the build is <b>refused</b> if the
 * bytes move, rather than shipping a transaction that fails on chain after the fee is spent.
 *
 * <h2>Where the fee goes</h2>
 * No validator pays the bot. The pool is required to receive {@code addedLiquidity − compoundingFee};
 * the remainder simply is not constrained, so it flows to the change address — which must be the
 * bot's. §20.1 warns that a builder balancing the whole escrow into the pool donates its own fee, so
 * the pool output is computed as {@code poolInput + (addedLiquidity − fee)} and the bot's net position
 * is re-derived from the finished body and asserted. <b>An explicit fee output is deliberately not
 * emitted</b>: the fee is frequently zero (the only live preview pool publishes
 * {@code compoudingFeePerMille = 0}) and often below min-ada, and an output that cannot hold it would
 * turn an economic question into a build failure. The assertion, not the output shape, is what
 * protects the value.
 *
 * <h2>Ex-units</h2>
 * Measured, never guessed — CCL trap 8. An evaluator is wired through {@code withTxEvaluator} and
 * {@code ignoreScriptCostEvaluationError(evaluator == null)}, and the operator's whole risk case
 * ("exposure is the transaction fee per execution") is true only while that holds: placeholder
 * ex-units move the exposure to the collateral.
 */
@Slf4j
public class CompoundTransactionBuilder {

    /** The unit redeemer {@code Constr 0 []} every {@code general_spend} handler takes. */
    static final PlutusData GENERAL_SPEND_REDEEMER =
            ConstrPlutusData.builder().alternative(0).data(ListPlutusData.of()).build();

    /** Four script inputs, therefore four spend redeemers before the first reward redeemer. */
    static final int SCRIPT_SPEND_COUNT = 4;

    public enum Refusal {
        CANDIDATE_NOT_READY,
        POOL_DATUM_NOT_BYTE_IDENTICAL,
        BOND_DATUM_NOT_BYTE_IDENTICAL,
        POOL_MANAGER_DATUM_NOT_BYTE_IDENTICAL,
        REDEEMER_INDEX_MISMATCH,
        POOL_OUTPUT_MISMATCH,
        ECHO_NOT_BYTE_IDENTICAL,
        BOT_NET_MISMATCH,
        COLLATERAL_RETURN_NEGATIVE
    }

    @Getter
    public static final class RefusedException extends RuntimeException {
        private final Refusal reason;

        RefusedException(Refusal reason, String detail) {
            super(reason + ": " + detail);
            this.reason = reason;
        }
    }

    static RefusedException refuse(Refusal reason, String detail) {
        return new RefusedException(reason, detail);
    }

    /**
     * @param candidate      a {@link CompoundCandidate#structurallyReady()} candidate
     * @param bondUtxo       the lender bond's UTxO. Passed in rather than taken from the candidate:
     *                       {@link com.fluidtokens.aquarium.offchain.model.loans.LenderBond} carries
     *                       the decoded datum and the coordinates, not the value, and
     *                       {@code equals_data} compares the whole output — so the builder needs the
     *                       real amounts, from the same index read that produced the candidate
     * @param configUtxo     the main config UTxO, a reference input
     * @param lmConfigUtxo   the lender-manager config UTxO, a reference input
     * @param walletUtxo     the bot's ada-only input, paying the fee and serving as collateral
     * @param changeAddress  the bot's address — <b>where the compounding fee lands</b>
     * @param compoundingFee {@code addedLiquidity * feePerMille / 1000}, computed by
     *                       {@link CompoundEconomics} with the validator's truncation
     * @param validFromSlot  ⚠ anchor this to the chain tip, not a wall clock (CCL trap 20). The
     *                       builder takes slots because only the caller knows the node's position.
     */
    public record Request(CompoundCandidate candidate,
                          Utxo bondUtxo,
                          Utxo configUtxo,
                          Utxo lmConfigUtxo,
                          Utxo walletUtxo,
                          String changeAddress,
                          BigInteger compoundingFee,
                          long validFromSlot,
                          long validToSlot) {
    }

    private final LoansContractRegistry registry;
    private final Network network;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;
    private final BackendService backendService;
    private final TransactionEvaluator scriptCostEvaluator;

    /** Offline: rigs supply every script and evaluate for themselves. */
    public CompoundTransactionBuilder(LoansContractRegistry registry, Network network,
                                      UtxoSupplier utxoSupplier,
                                      ProtocolParamsSupplier protocolParamsSupplier,
                                      TransactionEvaluator scriptCostEvaluator) {
        this(registry, network, utxoSupplier, protocolParamsSupplier, null, scriptCostEvaluator);
    }

    /** Production: one backend wires utxo supplier, params, script supplier and processor. */
    public CompoundTransactionBuilder(LoansContractRegistry registry, Network network,
                                      BackendService backendService,
                                      UtxoSupplier utxoSupplier,
                                      ProtocolParamsSupplier protocolParamsSupplier,
                                      TransactionEvaluator scriptCostEvaluator) {
        this(registry, network, utxoSupplier, protocolParamsSupplier, backendService, scriptCostEvaluator);
    }

    private CompoundTransactionBuilder(LoansContractRegistry registry, Network network,
                                       UtxoSupplier utxoSupplier,
                                       ProtocolParamsSupplier protocolParamsSupplier,
                                       BackendService backendService,
                                       TransactionEvaluator scriptCostEvaluator) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.network = Objects.requireNonNull(network, "network");
        this.utxoSupplier = Objects.requireNonNull(utxoSupplier, "utxoSupplier");
        this.protocolParamsSupplier = Objects.requireNonNull(protocolParamsSupplier, "protocolParamsSupplier");
        this.backendService = backendService;
        this.scriptCostEvaluator = scriptCostEvaluator;
    }

    // ---- the one entry point ----------------------------------------------------------------

    public Transaction build(Request request) {
        CompoundCandidate c = request.candidate();
        if (!c.structurallyReady()) {
            throw refuse(Refusal.CANDIDATE_NOT_READY,
                    "candidate " + c.loanId() + " is excluded as " + c.exclusion() + ": " + c.detail());
        }

        // Reference inputs, canonically ordered before any index is read off them: the ledger sorts
        // them by (txHash, outputIndex) before a validator ever sees the list.
        List<TransactionInput> refInputs = canonical(List.of(
                inputOf(request.configUtxo()), inputOf(request.lmConfigUtxo())));
        long configRefIndex = indexOf(refInputs, inputOf(request.configUtxo()), "main config");
        // ⛔ A FOURTH WRINKLE ON §22.3: `configRefInputIndex` does not name the same config on every
        // validator. lender_manager.withdraw resolves the LM CONFIG NFT from the index it is handed
        // (it reads lmConfig fields 1..7 to pick the action script), while every other redeemer here
        // resolves the MAIN config. Same field name, different reference input — and handing it the
        // main config's index fails as a plain EvaluationFailure with nothing naming the cause.
        // Found by the dry-eval, not by reading: withdraw index 4.
        long lmConfigRefIndex = indexOf(refInputs, inputOf(request.lmConfigUtxo()), "lm config");

        // ⛔ self.redeemers positions. Computed, then re-derived from the finished body and compared
        // (assertStructure) — §22.3's one failure mode that can be wrong and still evaluate green.
        List<String> withdrawalOrder = withdrawalOrder();
        long poolWithdrawRedeemerIndex = rewardRedeemerIndex(withdrawalOrder, registry.getPoolPolicyId());
        long lmWithdrawRedeemerIndex =
                rewardRedeemerIndex(withdrawalOrder, registry.getLenderManagerWithdrawScriptHash());

        ScriptTx tx = assemble(request, configRefIndex, lmConfigRefIndex,
                poolWithdrawRedeemerIndex, lmWithdrawRedeemerIndex);

        return complete(request, refInputs, tx,
                (ctx, txn) -> assertStructure(txn, request, withdrawalOrder,
                        poolWithdrawRedeemerIndex, lmWithdrawRedeemerIndex));
    }

    // ---- assembly ---------------------------------------------------------------------------

    private ScriptTx assemble(Request request, long configRefIndex, long lmConfigRefIndex,
                              long poolWithdrawRedeemerIndex, long lmWithdrawRedeemerIndex) {
        CompoundCandidate c = request.candidate();
        ScriptTx tx = new ScriptTx();

        // Four script spends. general_spend ignores the redeemer; the authorisation is the presence
        // of a withdrawal at its withdrawScriptHash, added below.
        tx.collectFrom(c.escrow(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.bondUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(c.pool(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(c.poolManager(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.walletUtxo());

        // The pool gains addedLiquidity MINUS the fee. Everything else about it is unchanged, and
        // pool_compound_action checks the whole value with equals_data — so the input's other assets
        // are carried across verbatim rather than reconstructed.
        BigInteger delta = c.addedLiquidity().subtract(request.compoundingFee());
        tx.payToContract(c.pool().getAddress(), plusLovelace(c.pool().getAmount(), delta),
                echo(c.pool().getInlineDatum(), Refusal.POOL_DATUM_NOT_BYTE_IDENTICAL, "pool"));

        // Two byte-identical restorations: equals_data compares the WHOLE output, so value and datum
        // both go back exactly as they came in.
        tx.payToContract(request.bondUtxo().getAddress(), List.copyOf(request.bondUtxo().getAmount()),
                echo(c.bond().inlineDatum(), Refusal.BOND_DATUM_NOT_BYTE_IDENTICAL, "lender bond"));
        tx.payToContract(c.poolManager().getAddress(), List.copyOf(c.poolManager().getAmount()),
                echo(c.poolManager().getInlineDatum(), Refusal.POOL_MANAGER_DATUM_NOT_BYTE_IDENTICAL,
                        "pool manager"));

        // The seven withdrawals (§22.2). Order of addition is irrelevant — the ledger sorts them.
        tx.withdraw(rewardAddress(registry.getAssetManagerWithdrawScriptHash()), BigInteger.ZERO,
                CompoundTxEncoder.assetManagerWithdraw(configRefIndex));
        // ⛔ THE LM CONFIG, not the main one — see build().
        tx.withdraw(rewardAddress(registry.getLenderManagerWithdrawScriptHash()), BigInteger.ZERO,
                CompoundTxEncoder.lenderManagerWithdraw(lmConfigRefIndex));
        tx.withdraw(rewardAddress(registry.getLmCompoundActionScriptHash()), BigInteger.ZERO,
                CompoundTxEncoder.lmCompound(configRefIndex, List.of(c.poolId()),
                        List.of(0L), List.of(0L)));
        tx.withdraw(rewardAddress(registry.getPoolPolicyId()), BigInteger.ZERO,
                CompoundTxEncoder.poolWithdraw(configRefIndex));
        tx.withdraw(rewardAddress(registry.getPoolCompoundActionScriptHash()), BigInteger.ZERO,
                CompoundTxEncoder.poolCompoundAction(configRefIndex, List.of(c.poolId())));
        tx.withdraw(rewardAddress(registry.getPoolManagerPolicyId()), BigInteger.ZERO,
                CompoundTxEncoder.poolManagerWithdraw(configRefIndex));
        tx.withdraw(rewardAddress(registry.getPmCompoundLiquidityScriptHash()), BigInteger.ZERO,
                CompoundTxEncoder.compoundLiquidity(poolWithdrawRedeemerIndex, lmWithdrawRedeemerIndex));


        // Every validator this transaction invokes travels INLINE in the witness set. CCL trap 13:
        // a redeemer without its script is RequiredRedeemersMismatch, and an offline evaluator cannot
        // fetch what is not there. Publishing these as reference scripts would cut the size and is a
        // later, separate decision — inline is the library default and needs no coordinate to verify.
        tx.attachSpendingValidator(registry.getAssetManagerSpendScript());
        tx.attachSpendingValidator(registry.getLenderManagerSpendScript());
        tx.attachSpendingValidator(registry.getPoolSpendScript());
        tx.attachSpendingValidator(registry.getPoolManagerSpendScript());

        tx.attachRewardValidator(registry.getAssetManagerScript());
        tx.attachRewardValidator(registry.getLenderManagerScript());
        tx.attachRewardValidator(registry.getLmCompoundActionScript());
        tx.attachRewardValidator(registry.getPoolScript());
        tx.attachRewardValidator(registry.getPoolCompoundActionScript());
        tx.attachRewardValidator(registry.getPoolManagerScript());
        tx.attachRewardValidator(registry.getPmCompoundLiquidityScript());

        return tx;
    }

    private Transaction complete(Request request, List<TransactionInput> refInputs,
                                 ScriptTx tx, TxBuilder verify) {
        tx.readFrom(refInputs.toArray(TransactionInput[]::new));
        QuickTxBuilder quickTxBuilder = backendService != null
                ? new QuickTxBuilder(backendService)
                : new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null);

        var context = quickTxBuilder.compose(tx)
                .feePayer(request.changeAddress())
                .collateralPayer(request.changeAddress())
                .validFrom(request.validFromSlot())
                .validTo(request.validToSlot())
                .mergeOutputs(false)
                // CCL trap 8/13: with an evaluator present a failed evaluation must ABORT the build.
                // The default (true) turns it into a log.warn and hands back placeholder ex-units,
                // which is a phase-2 failure waiting to be submitted.
                .ignoreScriptCostEvaluationError(scriptCostEvaluator == null)
                // CCL trap 9b, BOTH seams: the default selector will happily spend a UTxO carrying a
                // reference script, and ChangeOutputAdjustments falls through to its own selector.
                .withUtxoSelectionStrategy(ReferenceScriptSafeUtxoSelection.strategy(utxoSupplier))
                .preBalanceTx((ctx, txn) ->
                        ctx.setUtxoSelector(ReferenceScriptSafeUtxoSelection.selector(utxoSupplier)))
                .postBalanceTx(verify);

        if (backendService == null) {
            // CCL trap 2: offline, ReferenceScriptResolver walks every reference input looking for a
            // script to fetch and NPEs on the missing supplier. The rigs hand scripts in explicitly.
            context = context.withScriptSupplier(scriptHash -> java.util.Optional.empty());
        }
        if (scriptCostEvaluator != null) {
            context = context.withTxEvaluator(scriptCostEvaluator);
        }
        return context.build();
    }

    // ---- the post-assert, which is the real guard --------------------------------------------

    /**
     * Re-derives from the FINISHED body everything the redeemers claim, and refuses on any mismatch.
     * Runs inside the build pipeline so there is no separate call for a future path to forget.
     */
    void assertStructure(Transaction txn, Request request, List<String> withdrawalOrder,
                         long poolWithdrawRedeemerIndex, long lmWithdrawRedeemerIndex) {
        CompoundCandidate c = request.candidate();

        // ⛔ 1. The redeemer positions (g) cites, matched BY SCRIPT PURPOSE. Trap 14: PoolWithdraw
        // and PoolManagerWithdraw are byte-indistinguishable, so a shape test here would be unsafe.
        assertRewardRedeemerAt(txn, poolWithdrawRedeemerIndex, registry.getPoolPolicyId(),
                "poolWithdrawRedeemerIndex");
        assertRewardRedeemerAt(txn, lmWithdrawRedeemerIndex,
                registry.getLenderManagerWithdrawScriptHash(), "lenderManagerWithdrawRedeemerIndex");

        // 2. The pool output really is the input plus (addedLiquidity - fee), and nothing else moved.
        BigInteger expected = lovelaceOf(c.pool().getAmount())
                .add(c.addedLiquidity()).subtract(request.compoundingFee());
        TransactionOutput poolOut = outputWithDatum(txn, c.pool().getAddress(), c.pool().getInlineDatum());
        if (poolOut == null) {
            throw refuse(Refusal.POOL_OUTPUT_MISMATCH,
                    "no output at the pool address carries the pool's original datum bytes");
        }
        if (poolOut.getValue().getCoin().compareTo(expected) != 0) {
            throw refuse(Refusal.POOL_OUTPUT_MISMATCH,
                    "pool output holds %s lovelace, expected %s (input %s + added %s - fee %s)"
                            .formatted(poolOut.getValue().getCoin(), expected,
                                    lovelaceOf(c.pool().getAmount()), c.addedLiquidity(),
                                    request.compoundingFee()));
        }

        // 3. The two equals_data echoes are byte-identical, checked on the built body rather than on
        //    what we intended to emit.
        assertEcho(txn, request.bondUtxo().getAddress(), c.bond().inlineDatum(),
                request.bondUtxo().getAmount(), "lender bond");
        assertEcho(txn, c.poolManager().getAddress(), c.poolManager().getInlineDatum(),
                c.poolManager().getAmount(), "pool manager");

        // 4. The bot's net position IS the fee minus the transaction fee. This is what stops the
        //    builder donating its own fee to the pool (§20.1) — no validator objects to that, so
        //    nothing else would catch it.
        BigInteger toBot = txn.getBody().getOutputs().stream()
                .filter(o -> request.changeAddress().equals(o.getAddress()))
                .map(o -> o.getValue().getCoin())
                .reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger net = toBot.subtract(lovelaceOf(request.walletUtxo().getAmount()));
        BigInteger want = request.compoundingFee().subtract(txn.getBody().getFee());
        if (net.compareTo(want) != 0) {
            throw refuse(Refusal.BOT_NET_MISMATCH,
                    "bot nets %s lovelace, expected %s (fee earned %s - tx fee %s)"
                            .formatted(net, want, request.compoundingFee(), txn.getBody().getFee()));
        }

        // 5. CCL trap 16: a negative collateral return is not a phase-1 rejection, it is an
        //    unparseable transaction — refused before any validation runs, and hard to diagnose.
        var collateralReturn = txn.getBody().getCollateralReturn();
        if (collateralReturn != null && collateralReturn.getValue().getCoin().signum() < 0) {
            throw refuse(Refusal.COLLATERAL_RETURN_NEGATIVE,
                    "collateral return is " + collateralReturn.getValue().getCoin()
                            + "; the pure-ada collateral capacity cannot cover the required amount");
        }
    }

    private void assertRewardRedeemerAt(Transaction txn, long position, String scriptHash, String field) {
        List<String> rewards = rewardScriptHashesInBodyOrder(txn);
        long rewardOffset = position - SCRIPT_SPEND_COUNT;
        if (rewardOffset < 0 || rewardOffset >= rewards.size()) {
            throw refuse(Refusal.REDEEMER_INDEX_MISMATCH,
                    "%s = %d is outside the body's redeemer list (%d spends + %d rewards)"
                            .formatted(field, position, SCRIPT_SPEND_COUNT, rewards.size()));
        }
        String actual = rewards.get((int) rewardOffset);
        if (!scriptHash.equalsIgnoreCase(actual)) {
            throw refuse(Refusal.REDEEMER_INDEX_MISMATCH,
                    "%s = %d points at the withdrawal for %s, not %s".formatted(field, position, actual, scriptHash));
        }
    }

    /** Withdrawal script hashes in the body's own canonical order — the order the ledger indexes. */
    static List<String> rewardScriptHashesInBodyOrder(Transaction txn) {
        var withdrawals = txn.getBody().getWithdrawals();
        if (withdrawals == null) {
            return List.of();
        }
        return withdrawals.stream()
                .sorted(Comparator.comparing(w -> HexUtil.encodeHexString(
                        new Address(w.getRewardAddress()).getBytes())))
                .map(w -> HexUtil.encodeHexString(
                        new Address(w.getRewardAddress()).getDelegationCredentialHash().orElse(new byte[0])))
                .toList();
    }

    private void assertEcho(Transaction txn, String address, String datumHex,
                            List<Amount> amounts, String what) {
        TransactionOutput out = outputWithDatum(txn, address, datumHex);
        if (out == null) {
            throw refuse(Refusal.ECHO_NOT_BYTE_IDENTICAL,
                    what + " echo is absent, or its datum bytes moved");
        }
        if (out.getValue().getCoin().compareTo(lovelaceOf(amounts)) != 0) {
            throw refuse(Refusal.ECHO_NOT_BYTE_IDENTICAL,
                    "%s echo holds %s lovelace, input held %s — equals_data compares the whole output"
                            .formatted(what, out.getValue().getCoin(), lovelaceOf(amounts)));
        }
    }

    // ---- primitives -------------------------------------------------------------------------

    /**
     * Decode and re-encode, refusing unless the bytes are identical. CCL trap 4: cardano-client-lib
     * re-serialises whatever {@code PlutusData} it is handed, and a datum posted with a different
     * (equally legal) bytestring chunking comes back different — which {@code equals_data} rejects
     * on chain after the fee is spent. Caught here for free instead.
     */
    static PlutusData echo(String originalHex, Refusal reason, String what) {
        if (originalHex == null) {
            throw refuse(reason, what + " has no inline datum to echo");
        }
        try {
            PlutusData decoded = ConstrPlutusData.deserialize(
                    CborSerializationUtil.deserialize(HexUtil.decodeHexString(originalHex)));
            if (!originalHex.equalsIgnoreCase(decoded.serializeToHex())) {
                throw refuse(reason, what + " datum re-encodes to different bytes");
            }
            return decoded;
        } catch (RefusedException e) {
            throw e;
        } catch (Exception e) {
            throw refuse(reason, what + " datum could not be decoded: " + e);
        }
    }

    /** The canonical withdrawal order: reward addresses sorted by their raw bytes. */
    List<String> withdrawalOrder() {
        List<String> hashes = new ArrayList<>(List.of(
                registry.getAssetManagerWithdrawScriptHash(),
                registry.getLenderManagerWithdrawScriptHash(),
                registry.getLmCompoundActionScriptHash(),
                registry.getPoolPolicyId(),
                registry.getPoolCompoundActionScriptHash(),
                registry.getPoolManagerPolicyId(),
                registry.getPmCompoundLiquidityScriptHash()));
        hashes.sort(Comparator.comparing(h -> HexUtil.encodeHexString(
                new Address(rewardAddress(h)).getBytes())));
        return List.copyOf(hashes);
    }

    /**
     * A reward redeemer's position in {@code self.redeemers}: every spend redeemer sorts before every
     * reward redeemer, and rewards are ordered by their withdrawal's position.
     */
    static long rewardRedeemerIndex(List<String> withdrawalOrder, String scriptHash) {
        int at = withdrawalOrder.indexOf(scriptHash);
        if (at < 0) {
            throw refuse(Refusal.REDEEMER_INDEX_MISMATCH,
                    scriptHash + " is not among the transaction's withdrawals");
        }
        return SCRIPT_SPEND_COUNT + at;
    }

    private String rewardAddress(String scriptHash) {
        return AddressProvider.getRewardAddress(Credential.fromScript(scriptHash), network).getAddress();
    }

    private static TransactionOutput outputWithDatum(Transaction txn, String address, String datumHex) {
        return txn.getBody().getOutputs().stream()
                .filter(o -> address.equals(o.getAddress()))
                .filter(o -> o.getInlineDatum() != null && datumHexOf(o).equalsIgnoreCase(datumHex))
                .findFirst()
                .orElse(null);
    }

    private static String datumHexOf(TransactionOutput out) {
        try {
            return out.getInlineDatum().serializeToHex();
        } catch (Exception e) {
            return "";
        }
    }

    private static List<Amount> plusLovelace(List<Amount> amounts, BigInteger delta) {
        List<Amount> out = new ArrayList<>();
        boolean seen = false;
        for (Amount a : amounts) {
            if ("lovelace".equals(a.getUnit())) {
                out.add(Amount.lovelace(a.getQuantity().add(delta)));
                seen = true;
            } else {
                out.add(a);
            }
        }
        if (!seen) {
            out.add(Amount.lovelace(delta));
        }
        return out;
    }

    private static BigInteger lovelaceOf(List<Amount> amounts) {
        return amounts.stream()
                .filter(a -> "lovelace".equals(a.getUnit()))
                .map(Amount::getQuantity)
                .findFirst()
                .orElse(BigInteger.ZERO);
    }

    private static TransactionInput inputOf(Utxo utxo) {
        return new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex());
    }

    private static List<TransactionInput> canonical(List<TransactionInput> inputs) {
        return inputs.stream()
                .sorted(Comparator.comparing(TransactionInput::getTransactionId)
                        .thenComparingInt(TransactionInput::getIndex))
                .toList();
    }

    private static long indexOf(List<TransactionInput> inputs, TransactionInput target, String what) {
        int at = inputs.indexOf(target);
        if (at < 0) {
            throw refuse(Refusal.REDEEMER_INDEX_MISMATCH, what + " is not among the reference inputs");
        }
        return at;
    }
}
