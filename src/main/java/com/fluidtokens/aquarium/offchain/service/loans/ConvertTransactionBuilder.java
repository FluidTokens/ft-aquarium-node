package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.function.TxBuilder;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Builds a <b>LiquidateAndConvert</b>: liquidates a loan and, in the same transaction, creates the
 * Minswap V2 swap order that turns its collateral into the lender's principal.
 *
 * <h2>What this builder is and is not</h2>
 * It <b>assembles</b>; it does not decide. Everything the validator dictates was computed by
 * {@link ConvertOrderPlan} against the live pool datum, and everything about profitability by
 * {@link ConvertEconomics}. What is left here is the part that can only be checked against a node:
 * inputs, outputs, indexes, fees and ex-units.
 *
 * <h2>⛔ A REAL EVALUATOR, FROM THIS CLASS'S FIRST COMMIT</h2>
 * There is no constructor without a {@link TransactionEvaluator}. The sibling builders each grew one
 * as a convenience and it is how the 2026-08-21 incident shipped placeholder ex-units — 10000 mem /
 * 1000 steps against a real 352,041,926 — which pass the mempool and fail in <b>phase 2</b>, forfeiting
 * collateral. That risk is not abstract here: <b>the operator's entire exposure on this path is "a
 * transaction fee per execution", and that sentence is true only while the budgets are real.</b>
 * Placeholders move the exposure to the collateral, which is the one way the case for running this
 * bot at a stated loss stops holding.
 *
 * <h2>⚠ Two absolute output indexes, and a dummy output that moves them</h2>
 * The convert redeemer names {@code minswapOrderSuccessInlineDatumOutputIndexes} and its refund twin
 * as indexes into {@code self.outputs} — <b>absolute</b>. This transaction carries withdrawals, and
 * CCL prepends a dummy output at the change address for every withdrawing transaction (trap 1), then
 * appends change after our outputs. So the layout is <b>observed, never predicted</b>: the body is
 * assembled once with placeholder indexes purely to read the finished layout, the carriers are located
 * <b>by their datum bytes</b> rather than by arithmetic, and the whole thing is assembled again with
 * the observed values. {@link #assertStructure} then re-derives them from the finished body.
 *
 * <h2>⚠ Trap 17 is unavoidable on this path</h2>
 * The bot's income is the liquidation fee <b>in the collateral token</b>, so every convert produces a
 * change output carrying native assets. A subsequent build that spends it must cope with a
 * multi-asset wallet UTxO; that is a property of the path, not a defect to design away.
 */
@Slf4j
public class ConvertTransactionBuilder {

    /** {@code general_spend} ignores its redeemer; authorisation is the withdrawal beside it. */
    static final PlutusData GENERAL_SPEND_REDEEMER = CompoundTransactionBuilder.GENERAL_SPEND_REDEEMER;

    public enum Refusal {
        /** {@code loans.minswap.*} is unset or belongs to another network — see {@code LoansConfigVerifier}. */
        CONVERT_ACTION_NOT_DERIVED,
        /**
         * A non-zero borrower equity. The validator subtracts it from the swappable amount but does
         * not say where it goes; {@code loan_claim_action} does, and that path is not modelled here.
         * A named refusal beats a silent wrong build.
         */
        EQUITY_NOT_MODELLED,
        /** The lender bond's datum did not survive a decode→re-encode, so it cannot be echoed. */
        BOND_DATUM_NOT_BYTE_IDENTICAL,
        /** The built order output is not what the plan said it must be. */
        ORDER_OUTPUT_MISMATCH,
        /** A carrier output's inline datum does not hash to the value the order datum embeds. */
        CARRIER_DATUM_MISMATCH,
        /** The bot's collateral-token fee is absent from the built body. */
        FEE_NOT_PAID_TO_BOT,
        /** An index the redeemer claims does not point at what it claims, on the finished body. */
        INDEX_MISMATCH,
        /**
         * ⛔ No usable oracle for the collateral leg. A convert exchanges two distinct assets, so at
         * least one leg is a token and {@code loan_claim_action} requires an oracle withdrawal for it.
         * Building without one produces a transaction that assembles, serialises and passes every
         * structural check here — and fails on chain.
         */
        COLLATERAL_ORACLE_MISSING
    }

    public static final class RefusedException extends RuntimeException {
        private final transient Refusal reason;

        RefusedException(Refusal reason, String detail) {
            super(reason + ": " + detail);
            this.reason = reason;
        }

        public Refusal reason() {
            return reason;
        }
    }

    private static RefusedException refuse(Refusal reason, String detail) {
        return new RefusedException(reason, detail);
    }

    /**
     * @param poolRefUtxo the Minswap pool UTxO, located BY ITS NFT at scan time — never a pinned
     *                    coordinate, because a pool is respent on every swap
     * @param collateralOracle the feed for the COLLATERAL leg. ⛔ Never {@code null} on this path:
     *                    a convert exchanges two distinct assets, so at least one leg is a token and
     *                    {@code retrieve_oracle_data} demands an oracle withdrawal for it. The
     *                    principal leg needs none when it is ada, which the ada short-circuit handles
     * @param orderAddress {@code Address(Script(minswapOrderSpendScriptHash), lenderStakeCredential)}
     *                     — the validator checks this exactly, including the LENDER's stake part
     * @param claim       the loan-claim redeemer's per-input data; its {@code lenderBondOutputIndex}
     *                    is filled in by the second pass, not by the caller
     */
    public record Request(Utxo loanUtxo,
                          Utxo bondUtxo,
                          Utxo poolRefUtxo,
                          com.fluidtokens.aquarium.offchain.model.loans.OracleEntry collateralOracle,
                          Utxo configUtxo,
                          Utxo lmConfigUtxo,
                          Utxo walletUtxo,
                          Map<String, TransactionInput> referenceScripts,
                          ConvertOrderPlan plan,
                          ClaimData claim,
                          AssetType collateral,
                          AssetType lenderBondAsset,
                          String orderAddress,
                          String changeAddress,
                          long validFromSlot,
                          long validToSlot) {
    }

    private final LoansContractRegistry registry;
    private final Network network;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;
    private final BackendService backendService;
    private final TransactionEvaluator scriptCostEvaluator;

    /** Offline: a rig supplies the scripts and evaluates for itself. */
    public ConvertTransactionBuilder(LoansContractRegistry registry, Network network,
                                     UtxoSupplier utxoSupplier,
                                     ProtocolParamsSupplier protocolParamsSupplier,
                                     TransactionEvaluator scriptCostEvaluator) {
        this(registry, network, utxoSupplier, protocolParamsSupplier, null, scriptCostEvaluator);
    }

    /** Production: one backend wires the utxo supplier, params and script supplier. */
    public ConvertTransactionBuilder(LoansContractRegistry registry, Network network,
                                     BackendService backendService,
                                     UtxoSupplier utxoSupplier,
                                     ProtocolParamsSupplier protocolParamsSupplier,
                                     TransactionEvaluator scriptCostEvaluator) {
        this(registry, network, utxoSupplier, protocolParamsSupplier, backendService, scriptCostEvaluator);
    }

    private ConvertTransactionBuilder(LoansContractRegistry registry, Network network,
                                      UtxoSupplier utxoSupplier,
                                      ProtocolParamsSupplier protocolParamsSupplier,
                                      BackendService backendService,
                                      TransactionEvaluator scriptCostEvaluator) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.network = Objects.requireNonNull(network, "network");
        this.utxoSupplier = Objects.requireNonNull(utxoSupplier, "utxoSupplier");
        this.protocolParamsSupplier =
                Objects.requireNonNull(protocolParamsSupplier, "protocolParamsSupplier");
        this.backendService = backendService;
        // ⛔ NOT optional, and deliberately not defaulted. See the class javadoc.
        this.scriptCostEvaluator = Objects.requireNonNull(scriptCostEvaluator,
                "a convert builder without a TransactionEvaluator would ship placeholder ex-units and "
                        + "fail in phase 2 — CCL trap 8. There is no constructor without one.");
    }

    // ---- the one entry point --------------------------------------------------------------------

    public Transaction build(Request request) {
        if (registry.getLmLiquidateAndConvertActionScriptHash() == null) {
            throw refuse(Refusal.CONVERT_ACTION_NOT_DERIVED,
                    "loans.minswap.* is not configured, so the convert action cannot be withdrawn through");
        }
        // ⛔ THE LIMB THIS BUILDER SHIPPED WITHOUT. Checked first because its absence is invisible
        // downstream: every structural assertion in this class inspects what we EMIT, and none can
        // see a withdrawal that was never added.
        if (request.collateralOracle() == null || !request.collateralOracle().usableForLiquidation()) {
            throw refuse(Refusal.COLLATERAL_ORACLE_MISSING,
                    "the collateral leg has no usable oracle entry; loan_claim_action would refuse "
                            + "this transaction on chain and nothing here would have noticed");
        }
        if (request.claim().equity().signum() != 0) {
            throw refuse(Refusal.EQUITY_NOT_MODELLED,
                    "borrower equity is " + request.claim().equity() + "; loan_claim_action constrains "
                            + "where it goes and this builder does not model that yet");
        }

        List<TransactionInput> refInputs = referenceInputs(request);
        long configRefIndex = indexOf(refInputs, inputOf(request.configUtxo()), "main config");
        // ⛔ The LM config, not the main one: lender_manager.withdraw resolves the ACTION script from
        // the LM config datum it is handed (§22.3's fourth wrinkle, found by dry-eval not by reading).
        long lmConfigRefIndex = indexOf(refInputs, inputOf(request.lmConfigUtxo()), "lm config");
        // The pool's index is into the FILTERED list of pool reference inputs, of which there is one.
        long poolRefIndex = 0L;
        long collateralOracleRefIndex = indexOf(refInputs,
                request.collateralOracle().referenceInput(), "collateral oracle");
        // ⚠ The principal leg is ada here: retrieve_oracle_data short-circuits on an empty policy id
        // and never reads this input — but the index must still be IN RANGE. Index 0 of the
        // canonically sorted reference inputs always is, which is what the sibling builders pass too.
        long principalOracleRefIndex = 0L;

        // ⛔ PASS 1. The two carrier indexes and lenderBondOutputIndex are ABSOLUTE positions in
        // self.outputs, and a withdrawing transaction gets a dummy output prepended plus change
        // appended (trap 1). Build once with placeholders purely to read the finished layout.
        Transaction probe = assembleAndComplete(request, refInputs, configRefIndex, lmConfigRefIndex,
                poolRefIndex, collateralOracleRefIndex, principalOracleRefIndex, 0L, 0L, 0L, null);

        long successIndex = outputIndexWithDatum(probe, request.plan().successDatum(), "success carrier");
        long refundIndex = outputIndexWithDatum(probe, request.plan().refundDatum(), "refund carrier");
        long bondOutputIndex = outputIndexWithDatumHex(probe, request.bondUtxo().getInlineDatum(),
                "lender bond echo");

        // PASS 2, with the observed layout, and a post-assert that re-derives it from the body.
        return assembleAndComplete(request, refInputs, configRefIndex, lmConfigRefIndex, poolRefIndex,
                collateralOracleRefIndex, principalOracleRefIndex, successIndex, refundIndex, bondOutputIndex,
                (ctx, txn) -> assertStructure(txn, request, successIndex, refundIndex, bondOutputIndex));
    }

    // ---- assembly -------------------------------------------------------------------------------

    private Transaction assembleAndComplete(Request request, List<TransactionInput> refInputs,
                                            long configRefIndex, long lmConfigRefIndex, long poolRefIndex,
                                            long collateralOracleRefIndex, long principalOracleRefIndex,
                                            long successIndex, long refundIndex, long bondOutputIndex,
                                            TxBuilder verify) {
        ConvertOrderPlan plan = request.plan();
        ScriptTx tx = new ScriptTx();

        tx.collectFrom(request.loanUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.bondUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.walletUtxo());

        // 1. The Minswap order. Its value is the validator's, not a min-ada estimate: an ADA
        //    collateral's order holds exactly the swappable amount, a token collateral's holds the
        //    tokens plus exactly 2_800_000 lovelace.
        tx.payToContract(request.orderAddress(), orderValue(request), plan.orderDatum());

        // 2. The lender bond, restored byte-for-byte. equals_data compares the WHOLE output, so the
        //    value goes back exactly as it came in and the datum is the input's own bytes.
        tx.payToContract(request.bondUtxo().getAddress(), List.copyOf(request.bondUtxo().getAmount()),
                echoedBondDatum(request));

        // 3+4. The two datum carriers. Their ADDRESSES ARE UNCONSTRAINED by the validator, so they are
        //      paid to the bot — which is what makes their min-ada working capital rather than cost,
        //      and is an invariant ConvertEconomics rests on.
        tx.payToContract(request.changeAddress(), List.of(Amount.lovelace(BigInteger.ZERO)),
                plan.successDatum());
        tx.payToContract(request.changeAddress(), List.of(Amount.lovelace(BigInteger.ZERO)),
                plan.refundDatum());

        // The four withdraw-0 invocations. ⚠ The asset-manager withdraw script must be ABSENT: the
        // validator explicitly refuses a convert that also spends an asset-manager input.
        tx.withdraw(rewardAddress(registry.getLoanPolicyId()), BigInteger.ZERO,
                LiquidationTxEncoder.loanWithdrawRedeemer(configRefIndex));
        tx.withdraw(rewardAddress(registry.getLoanClaimActionScriptHash()), BigInteger.ZERO,
                LiquidationTxEncoder.loanClaimActionWithdrawRedeemer(configRefIndex,
                        List.of(withIndexes(request.claim(), bondOutputIndex,
                                collateralOracleRefIndex, principalOracleRefIndex))));
        tx.withdraw(rewardAddress(registry.getLenderManagerWithdrawScriptHash()), BigInteger.ZERO,
                LiquidationTxEncoder.lenderManagerWithdrawRedeemer(lmConfigRefIndex));
        // ⛔ THE ORACLE WITHDRAWAL. loan_claim_action calls retrieve_oracle_data with the collateral's
        // own policy id; a non-empty one means the FluidTokens oracle validator MUST execute.
        //
        // ⚑ AND NOT THE SIBLING'S CALL. LiquidatePayInAdvanceTransactionBuilder uses
        // oracleRedeemer(feed, providerRefInputIndex, List.of()) — an EMPTY signature list, which is
        // the Charli3/Orcfax shape, where the price is proven by a provider reference input instead.
        // Mainnet FLDT is served MULTISIG (findings §40), so its branch runs verify_ed25519_signature
        // against the feed's published signatures and an empty list fails the threshold. Copying the
        // sibling verbatim is the hurried fix, and it would emit a transaction that assembles cleanly
        // and dies in phase 2.
        tx.withdraw(request.collateralOracle().rewardAddress(), BigInteger.ZERO,
                LiquidationTxEncoder.oracleRedeemer(request.collateralOracle().feed(),
                        request.collateralOracle().signatures()));

        tx.withdraw(rewardAddress(registry.getLmLiquidateAndConvertActionScriptHash()), BigInteger.ZERO,
                ConvertTxEncoder.convertRedeemer(Math.toIntExact(configRefIndex),
                        List.of(0),
                        List.of(request.lenderBondAsset().assetName()),
                        List.of(Math.toIntExact(poolRefIndex)),
                        List.of(ConvertTxEncoder.POOL_NFT_ASSET_NAME),
                        List.of(Math.toIntExact(successIndex)),
                        List.of(Math.toIntExact(refundIndex))));

        // ⛔ EXACTLY ONE ROUTE PER VALIDATOR (CCL traps 9 and 13): a script both witnessed and
        // referenced is ExtraneousScriptWitnessesUTXOW; a redeemer with no reachable script is
        // RequiredRedeemersMismatch. Both fail loudly; neither fails quietly.
        for (PlutusScript spend : List.of(registry.getLoanSpendScript(),
                registry.getLenderManagerSpendScript())) {
            if (!isReferenced(request, spend)) {
                tx.attachSpendingValidator(spend);
            }
        }
        for (PlutusScript reward : List.of(registry.getLoanScript(),
                registry.getLoanClaimActionScript(), registry.getLenderManagerScript(),
                registry.getLmLiquidateAndConvertActionScript())) {
            if (!isReferenced(request, reward)) {
                tx.attachRewardValidator(reward);
            }
        }

        return complete(request, refInputs, tx, verify);
    }

    private Transaction complete(Request request, List<TransactionInput> refInputs, ScriptTx tx,
                                 TxBuilder verify) {
        tx.readFrom(refInputs.toArray(TransactionInput[]::new));
        QuickTxBuilder quickTxBuilder = backendService != null
                ? new QuickTxBuilder(backendService)
                : new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null);

        var context = quickTxBuilder.compose(tx)
                .feePayer(request.changeAddress())
                .collateralPayer(request.changeAddress())
                .validFrom(request.validFromSlot())
                .validTo(request.validToSlot())
                // The four outputs are distinct by construction; merging them would destroy the
                // absolute indexes the redeemer names.
                .mergeOutputs(false)
                // ⛔ The evaluator is never null here, so a failed evaluation ABORTS rather than
                // handing back placeholders with a log.warn (CCL trap 8).
                .ignoreScriptCostEvaluationError(false)
                .withTxEvaluator(scriptCostEvaluator)
                // CCL trap 9b, BOTH seams: the default selector will spend a UTxO carrying a
                // reference script, and ChangeOutputAdjustments falls through to its own selector.
                .withUtxoSelectionStrategy(ReferenceScriptSafeUtxoSelection.strategy(utxoSupplier))
                .preBalanceTx((ctx, txn) ->
                        ctx.setUtxoSelector(ReferenceScriptSafeUtxoSelection.selector(utxoSupplier)));

        if (verify != null) {
            context = context.postBalanceTx(verify);
        }

        // CCL trap 9: the reference-script fee is charged only for bytes the library can OBTAIN.
        List<PlutusScript> referenced = referencedScripts(request);
        if (!referenced.isEmpty()) {
            context = context.withReferenceScripts(referenced.toArray(PlutusScript[]::new))
                    .removeDuplicateScriptWitnesses(true);
        }
        if (backendService == null) {
            // CCL trap 2: offline, ReferenceScriptResolver NPEs on a missing supplier.
            context = context.withScriptSupplier(scriptHash -> java.util.Optional.empty());
        }
        return context.build();
    }

    // ---- the post-assert, which is the real guard -------------------------------------------------

    /**
     * Re-derives from the FINISHED body everything the redeemer claims. §22.3's one failure mode is an
     * index that is wrong and still evaluates green on a fixture, so the check is structural and runs
     * inside the pipeline where no future caller can forget it.
     */
    void assertStructure(Transaction txn, Request request, long successIndex, long refundIndex,
                         long bondOutputIndex) {
        List<TransactionOutput> outs = txn.getBody().getOutputs();

        assertCarrier(outs, successIndex, request.plan().successDatum(),
                request.plan().successDatumHash(), "success");
        assertCarrier(outs, refundIndex, request.plan().refundDatum(),
                request.plan().refundDatumHash(), "refund");

        if (bondOutputIndex < 0 || bondOutputIndex >= outs.size()) {
            throw refuse(Refusal.INDEX_MISMATCH, "lenderBondOutputIndex " + bondOutputIndex
                    + " is outside the " + outs.size() + " outputs the body has");
        }
        TransactionOutput bondOut = outs.get((int) bondOutputIndex);
        if (!request.bondUtxo().getAddress().equals(bondOut.getAddress())) {
            throw refuse(Refusal.INDEX_MISMATCH,
                    "lenderBondOutputIndex points at " + bondOut.getAddress() + ", not the bond address");
        }
        if (bondOut.getInlineDatum() == null
                || !request.bondUtxo().getInlineDatum()
                        .equalsIgnoreCase(bondOut.getInlineDatum().serializeToHex())) {
            throw refuse(Refusal.BOND_DATUM_NOT_BYTE_IDENTICAL,
                    "the bond output's datum is not byte-identical to its input's, so equals_data fails");
        }

        // The order output: located by its datum, then checked for the value the plan dictates.
        TransactionOutput order = outs.stream()
                .filter(o -> request.orderAddress().equals(o.getAddress()))
                .findFirst()
                .orElseThrow(() -> refuse(Refusal.ORDER_OUTPUT_MISMATCH,
                        "no output at the Minswap order address " + request.orderAddress()));
        BigInteger expectedLovelace = request.plan().orderLovelace();
        if (order.getValue().getCoin().compareTo(expectedLovelace) != 0) {
            throw refuse(Refusal.ORDER_OUTPUT_MISMATCH,
                    "the order holds %s lovelace, the validator demands %s"
                            .formatted(order.getValue().getCoin(), expectedLovelace));
        }
        if (!request.collateral().isAda()) {
            BigInteger collateralInOrder = quantityOf(order, request.collateral());
            if (collateralInOrder.compareTo(request.plan().swappableCollateralAmount()) != 0) {
                throw refuse(Refusal.ORDER_OUTPUT_MISMATCH,
                        "the order holds %s of the collateral, the validator demands %s"
                                .formatted(collateralInOrder, request.plan().swappableCollateralAmount()));
            }

            // ⛔ The fee is the bot's INCOME and no validator asks for it — so nothing but this would
            // notice a builder that left it in the order or gave it to the lender (§25.3).
            BigInteger toBot = outs.stream()
                    .filter(o -> request.changeAddress().equals(o.getAddress()))
                    .map(o -> quantityOf(o, request.collateral()))
                    .reduce(BigInteger.ZERO, BigInteger::add);
            if (toBot.compareTo(request.plan().liquidationFee()) < 0) {
                throw refuse(Refusal.FEE_NOT_PAID_TO_BOT,
                        "the bot's outputs carry %s of the collateral but the fee it earned is %s"
                                .formatted(toBot, request.plan().liquidationFee()));
            }
        }
    }

    private void assertCarrier(List<TransactionOutput> outs, long index, PlutusData datum,
                               String expectedHash, String which) {
        if (index < 0 || index >= outs.size()) {
            throw refuse(Refusal.INDEX_MISMATCH, which + " carrier index " + index
                    + " is outside the " + outs.size() + " outputs the body has");
        }
        TransactionOutput out = outs.get((int) index);
        if (out.getInlineDatum() == null
                || !datum.serializeToHex().equalsIgnoreCase(out.getInlineDatum().serializeToHex())) {
            throw refuse(Refusal.CARRIER_DATUM_MISMATCH,
                    which + " carrier at index " + index + " does not hold the datum the order embeds");
        }
        if (!expectedHash.equalsIgnoreCase(out.getInlineDatum().getDatumHash())) {
            throw refuse(Refusal.CARRIER_DATUM_MISMATCH,
                    which + " carrier hashes to " + out.getInlineDatum().getDatumHash()
                            + " but the order datum embeds " + expectedHash);
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    private List<Amount> orderValue(Request request) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(request.plan().orderLovelace()));
        if (!request.collateral().isAda()) {
            // ⚠ CCL trap 3: the 3-arg Amount.asset(policy, name, qty) HEX-ENCODES the name, which
            // would double-encode an already-hex asset name. The unit form takes the bytes as given.
            amounts.add(Amount.asset(request.collateral().toUnit(),
                    request.plan().swappableCollateralAmount()));
        }
        return amounts;
    }

    /**
     * The bond's own datum bytes, refused rather than "fixed" if they do not survive a round trip —
     * CCL trap 4: a decode→re-encode is not byte-stable, and {@code equals_data} compares bytes.
     */
    private PlutusData echoedBondDatum(Request request) {
        String hex = request.bondUtxo().getInlineDatum();
        try {
            PlutusData decoded = PlutusData.deserialize(HexUtil.decodeHexString(hex));
            if (!hex.equalsIgnoreCase(decoded.serializeToHex())) {
                throw refuse(Refusal.BOND_DATUM_NOT_BYTE_IDENTICAL,
                        "the bond's datum does not survive a decode/re-encode round trip");
            }
            return decoded;
        } catch (RefusedException e) {
            throw e;
        } catch (Exception e) {
            throw refuse(Refusal.BOND_DATUM_NOT_BYTE_IDENTICAL, "the bond's datum is undecodable: " + e);
        }
    }

    /**
     * The claim data with the three indexes only the assembled body can know: the bond echo's absolute
     * output position, and the two oracle reference-input positions. The caller supplies the economics;
     * the builder supplies the geometry.
     */
    private static ClaimData withIndexes(ClaimData claim, long bondOutputIndex,
                                         long collateralOracleRefIndex, long principalOracleRefIndex) {
        return new ClaimData(claim.liquidationMode(), BigInteger.valueOf(bondOutputIndex),
                BigInteger.valueOf(collateralOracleRefIndex),
                BigInteger.valueOf(principalOracleRefIndex),
                claim.lenderAuth(), claim.equity(), claim.loanId(), claim.remainingDebt());
    }

    private List<TransactionInput> referenceInputs(Request request) {
        var all = new java.util.LinkedHashSet<TransactionInput>(List.of(
                inputOf(request.configUtxo()), inputOf(request.lmConfigUtxo()),
                inputOf(request.poolRefUtxo())));
        // The oracle's own three: the NFT-bearing input the validator reads the credential and value
        // from, its published script, and — for a provider-backed feed — the provider UTxO. Any of
        // them may be null (a multisig feed has no provider), and a null is skipped rather than
        // encoded as a coordinate that resolves to nothing.
        Stream.of(request.collateralOracle().referenceInput(),
                        request.collateralOracle().referenceScript(),
                        request.collateralOracle().charlieProviderReferenceInput())
                .filter(Objects::nonNull)
                .forEach(all::add);
        all.addAll(request.referenceScripts().values());
        return canonical(new ArrayList<>(all));
    }

    private static long outputIndexWithDatum(Transaction txn, PlutusData datum, String what) {
        String hex = datum.serializeToHex();
        List<TransactionOutput> outs = txn.getBody().getOutputs();
        for (int i = 0; i < outs.size(); i++) {
            var inline = outs.get(i).getInlineDatum();
            if (inline != null && hex.equalsIgnoreCase(inline.serializeToHex())) {
                return i;
            }
        }
        throw refuse(Refusal.CARRIER_DATUM_MISMATCH,
                "the first pass produced no output carrying the " + what + "'s datum");
    }

    private static long outputIndexWithDatumHex(Transaction txn, String datumHex, String what) {
        List<TransactionOutput> outs = txn.getBody().getOutputs();
        for (int i = 0; i < outs.size(); i++) {
            var inline = outs.get(i).getInlineDatum();
            if (inline != null && datumHex.equalsIgnoreCase(inline.serializeToHex())) {
                return i;
            }
        }
        throw refuse(Refusal.INDEX_MISMATCH,
                "the first pass produced no output carrying the " + what + "'s datum");
    }

    private static BigInteger quantityOf(TransactionOutput out, AssetType asset) {
        if (out.getValue() == null || out.getValue().getMultiAssets() == null) {
            return BigInteger.ZERO;
        }
        return out.getValue().getMultiAssets().stream()
                .filter(ma -> ma.getPolicyId().equalsIgnoreCase(asset.policyId()))
                .flatMap(ma -> ma.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(HexUtil.decodeHexString(
                        a.getName().startsWith("0x") ? a.getName().substring(2) : a.getName()))
                        .equalsIgnoreCase(asset.assetName()))
                .map(a -> a.getValue())
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private boolean isReferenced(Request request, PlutusScript script) {
        try {
            return request.referenceScripts().containsKey(
                    HexUtil.encodeHexString(script.getScriptHash()));
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash a registry script", e);
        }
    }

    private List<PlutusScript> referencedScripts(Request request) {
        List<PlutusScript> out = new ArrayList<>();
        for (PlutusScript s : List.of(registry.getLoanSpendScript(),
                registry.getLenderManagerSpendScript(), registry.getLoanScript(),
                registry.getLoanClaimActionScript(), registry.getLenderManagerScript(),
                registry.getLmLiquidateAndConvertActionScript())) {
            if (isReferenced(request, s)) {
                out.add(s);
            }
        }
        return out;
    }

    private String rewardAddress(String scriptHash) {
        return AddressProvider.getRewardAddress(
                Credential.fromScript(HexUtil.decodeHexString(scriptHash)), network).getAddress();
    }

    private static TransactionInput inputOf(Utxo utxo) {
        return new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex());
    }

    /** The ledger sorts reference inputs before a validator sees them; so must any index we compute. */
    private static List<TransactionInput> canonical(List<TransactionInput> inputs) {
        List<TransactionInput> sorted = new ArrayList<>(inputs);
        sorted.sort(Comparator.comparing(TransactionInput::getTransactionId)
                .thenComparingInt(TransactionInput::getIndex));
        return sorted;
    }

    private static long indexOf(List<TransactionInput> inputs, TransactionInput target, String what) {
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).equals(target)) {
                return i;
            }
        }
        throw refuse(Refusal.INDEX_MISMATCH, "the " + what + " reference input is not in the list");
    }
}
