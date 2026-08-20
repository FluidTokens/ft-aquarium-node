package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken;
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.TransactionInputComparator;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A <b>test-only</b> builder for a {@code LiquidateAndPayInAdvance} liquidation — the money-path
 * mode in which the bot pays the loan's principal in advance (in ADA) and takes the collateral,
 * for a lender bond whose {@code shouldLiquidationConvertToPrincipal == True}. It reproduces the
 * shape the deployed preview {@code lm_liquidate_and_pay_in_advance_action},
 * {@code lender_manager.lenderManager}, {@code loan_claim_action}, {@code loan.loan},
 * {@code general_spend} and the FluidTokens oracle accept under the real PlutusV3 machine, for
 * <b>one</b> loan.
 *
 * <h2>Structurally incapable of submitting</h2>
 * The {@link QuickTxBuilder} below is constructed with a <b>null</b> {@code TransactionProcessor} —
 * the only thing in cardano-client-lib that can put a transaction on a network — exactly as
 * {@link PoolBorrowTransactionBuilder} is. {@link #build} returns an <b>unsigned</b>
 * {@link Transaction}; there is no evaluator, no signer, no key and no network. The ex-units the
 * redeemers carry are cardano-client-lib's placeholders; the real ones are measured separately by
 * {@link LiquidatePayInAdvanceDryEvalTest} through the UPLC machine.
 *
 * <h2>How it differs from the plain {@code Liquidate} builder</h2>
 * The transaction is the plain-liquidation shape with three changes the pay-in-advance validators
 * force:
 * <ul>
 *   <li>the parent {@code lender_manager.lenderManager} withdraw carries
 *       {@code LenderManagerAction.LiquidateAndPayInAdvance} (constructor index 3, verified against
 *       {@code lib/fluidtokens/types/lender_manager.ak} at {@code ff005fb}), not {@code Liquidate};</li>
 *   <li>the action withdraw is {@code lm_liquidate_and_pay_in_advance_action}, whose redeemer adds a
 *       {@code principalAndCollateralOracleRefInputIndexes} list to the four fields the plain one has;
 *       its script is <b>witness-attached</b> because {@link EvalFixtures#scriptSupplier} does not
 *       carry it;</li>
 *   <li>the lender is paid in ADA — {@code convertedLoanCollateralToPrincipalAmount}, an
 *       ADA-only output ({@code dosProtection} demands {@code flatten == 1}) carrying the
 *       {@code converted_to_liquidity} action — instead of the collateral itself.</li>
 * </ul>
 * The borrower's equity compensation (in the collateral currency, tFLDT) and the loan-NFT burn are
 * unchanged: {@code loan_claim_action} governs them exactly as in a plain liquidation, so on a
 * positive-equity loan the compensation output sits at the bare loan index in the asset-manager
 * filtered output list and the lender's converted output is reached through
 * {@code assetOutputIndexes}.
 *
 * <h2>Every index is derived from the finished body</h2>
 * {@code lenderBondOutputIndex} (an absolute output position) and {@code assetOutputIndexes} (a
 * position in the asset-manager-credential-filtered output list) are things cardano-client-lib
 * decides, not this builder. As {@code LiquidateTransactionBuilder} does, the transaction is
 * assembled once with placeholder indexes purely to observe the finished layout (the <em>probe</em>),
 * the real indexes are read off that body, and it is assembled again; then {@link #assertStructure}
 * re-derives them from the finished body and refuses on any mismatch.
 */
public final class LiquidatePayInAdvanceTransactionBuilder {

    /** The unit redeemer {@code Constr 0 []} the {@code general_spend} handler takes. */
    private static final PlutusData GENERAL_SPEND_REDEEMER =
            ConstrPlutusData.builder().alternative(0).data(ListPlutusData.of()).build();

    /**
     * ASCII {@code "converted_to_liquidity"} — {@code constants.converted_to_liquidity_action}
     * ({@code lib/fluidtokens/constants.ak} at {@code ff005fb}), the asset-manager action on the
     * lender's paid-in-advance output. Built from the ASCII literal rather than pinned as hex so it
     * cannot silently drift from the constant it stands for.
     */
    static final String CONVERTED_TO_LIQUIDITY_ACTION_HEX =
            HexUtil.encodeHexString("converted_to_liquidity".getBytes(StandardCharsets.US_ASCII));

    /** ASCII {@code "partial_liquidation"} — {@code constants.action_partial_liquidation_compensation}. */
    static final String PARTIAL_LIQUIDATION_ACTION_HEX =
            HexUtil.encodeHexString("partial_liquidation".getBytes(StandardCharsets.US_ASCII));

    private final LoansContractRegistry registry;
    private final Network network;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;

    public LiquidatePayInAdvanceTransactionBuilder(LoansContractRegistry registry,
                                                   Network network,
                                                   UtxoSupplier utxoSupplier,
                                                   ProtocolParamsSupplier protocolParamsSupplier) {
        this.registry = registry;
        this.network = network;
        this.utxoSupplier = utxoSupplier;
        this.protocolParamsSupplier = protocolParamsSupplier;
    }

    /**
     * Everything the pay-in-advance transaction needs, all of it honest values — the adversarial
     * shape {@link LiquidatePayInAdvanceDryEvalTest} feeds the machine is produced by byte-surgery on
     * the finished body, not by mis-configuring this request.
     *
     * @param loan             the decoded loan, spent through {@code general_spend}
     * @param loanUtxo         the loan UTxO
     * @param bond             the decoded lender bond
     * @param bondUtxo         the lender-bond UTxO; its output is echoed byte-for-byte
     * @param walletUtxo       funds the ADA paid in advance to the lender, plus fees and min-ada
     * @param configUtxo       the main config reference input
     * @param lmConfigUtxo     the LenderManager config reference input
     * @param oracle           the Charli3-backed collateral oracle entry
     * @param validFromMillis  the instant the debt and equity are computed at — must be the POSIX time
     *                         {@code validFromSlot} converts back to, so the on-chain recomputation matches
     * @param validFromSlot    the validity range lower bound, in slots
     * @param validToSlot      the validity range upper bound, in slots
     * @param changeAddress    fee payer and change address (the bot); it keeps the collateral it took
     */
    public record Request(Loan loan,
                          Utxo loanUtxo,
                          LenderBond bond,
                          Utxo bondUtxo,
                          Utxo walletUtxo,
                          Utxo configUtxo,
                          Utxo lmConfigUtxo,
                          OracleEntry oracle,
                          long validFromMillis,
                          long validFromSlot,
                          long validToSlot,
                          String changeAddress) {
    }

    /** The five numbers the redeemer and the outputs rest on, all computed through {@link LoanFinance}. */
    public record Numbers(BigInteger remainingDebt,
                          BigInteger equity,
                          BigInteger liquidationFee,
                          BigInteger collateralLenderShouldReceive,
                          BigInteger convertedLoanCollateralToPrincipalAmount) {
    }

    /**
     * The five numbers, computed exactly as {@code LiquidateTransactionBuilder} and the on-chain
     * validators do: the debt and equity at {@code validFromMillis}, the fee from the bond's per-mille
     * rate, and — the pay-in-advance-specific one — the collateral the lender should receive converted
     * to the principal currency (ADA) through the two oracles. Since the principal is ADA its feed is
     * the 1:1 unit feed, so {@code convertFromAToBWithOracles} reduces to
     * {@code ceil(collateralReceive priced in lovelace)}.
     */
    public Numbers numbers(Request request) {
        LoanDatum datum = request.loan().datum();
        LiquidationMode.Liquidation mode = (LiquidationMode.Liquidation) datum.liquidationMode();
        BigInteger collateralAmount = request.loan().collateralAmount();

        BigInteger remainingDebt = LoanFinance.remainingDebt(datum, request.validFromMillis());
        BigInteger equity = LoanFinance.redeemerEquity(mode, Rational.fromInt(collateralAmount),
                Rational.fromInt(remainingDebt), OraclePriceFeed.unit(), request.oracle().feed());
        BigInteger liquidationFee = Rational.required(
                        collateralAmount.multiply(request.bond().datum().liquidationFeePerMille()),
                        BigInteger.valueOf(1000))
                .floor();
        BigInteger collateralLenderShouldReceive = collateralAmount.subtract(equity).subtract(liquidationFee);
        BigInteger converted = LoanFinance.toLovelace(
                Rational.fromInt(collateralLenderShouldReceive), request.oracle().feed()).ceil();
        return new Numbers(remainingDebt, equity, liquidationFee, collateralLenderShouldReceive, converted);
    }

    /**
     * Assembles and completes the transaction: probe for the layout, rebuild with the observed
     * indexes, then re-derive and assert them against the finished body.
     */
    public Transaction build(Request request) {
        Numbers numbers = numbers(request);
        if (numbers.equity().signum() <= 0) {
            throw new IllegalStateException(
                    "this builder models the positive-equity pay-in-advance layout; equity was "
                            + numbers.equity());
        }
        if (!request.bond().datum().shouldLiquidationConvertToPrincipal()) {
            throw new IllegalStateException(
                    "lm_liquidate_and_pay_in_advance_action requires shouldLiquidationConvertToPrincipal == True");
        }

        List<TransactionInput> refInputs = referenceInputs(request);
        int configRefIndex = refIndex(refInputs, inputOf(request.configUtxo()), "main config");
        int lmConfigRefIndex = refIndex(refInputs, inputOf(request.lmConfigUtxo()), "lm config");
        int collateralOracleRefIndex = refIndex(refInputs, request.oracle().referenceInput(), "collateral oracle");
        // Principal is ADA: retrieve_oracle_data short-circuits on policyId == "" and never reads the
        // reference input, but the index must still be in range. Index 0 of the canonically sorted
        // reference inputs always is — the same value LiquidateTransactionBuilder gives an ada leg.
        int principalOracleRefIndex = 0;
        int providerRefIndex = refIndex(refInputs, request.oracle().charlieProviderReferenceInput(),
                "charli3 provider");

        // Probe with placeholder output indexes purely to observe the finished layout.
        Transaction probe = complete(request, assemble(request, numbers, configRefIndex, lmConfigRefIndex,
                collateralOracleRefIndex, principalOracleRefIndex, providerRefIndex, refInputs, 0L, 0L));

        long lenderBondOutputIndex = locateBondOutput(probe, request);
        long assetOutputIndex = locateLenderConvertedOutput(probe, request, numbers);

        Transaction transaction = complete(request, assemble(request, numbers, configRefIndex,
                lmConfigRefIndex, collateralOracleRefIndex, principalOracleRefIndex, providerRefIndex,
                refInputs, lenderBondOutputIndex, assetOutputIndex));

        assertStructure(transaction, request, numbers, lenderBondOutputIndex, assetOutputIndex);
        return transaction;
    }

    // ---- assembly ---------------------------------------------------------------------------------

    private ScriptTx assemble(Request request, Numbers numbers, int configRefIndex, int lmConfigRefIndex,
                              int collateralOracleRefIndex, int principalOracleRefIndex,
                              int providerRefIndex, List<TransactionInput> refInputs,
                              long lenderBondOutputIndex, long assetOutputIndex) {
        ScriptTx tx = new ScriptTx();
        String loanId = request.loan().loanId();

        // Inputs. The general_spend handlers take a unit redeemer; the real authorisation is the
        // withdraw-0 invocation of the validator they wrap. The wallet funds the paid-in-advance ada.
        tx.collectFrom(request.loanUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.bondUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.walletUtxo());

        // Burn the loan NFT (loan_claim_action.ak requires quantity_of(self.mint, loanPolicyId, loanId) == -1).
        tx.mintAsset(registry.getLoanScript(),
                List.of(new Asset("0x" + loanId, BigInteger.ONE.negate())),
                LiquidationTxEncoder.loanMintRedeemer(configRefIndex, false, 0));

        // Outputs. The bond echo first, then — in the filtered asset-manager list — the borrower's
        // equity compensation at slot 0 (loan_claim_action reads it at the bare loan index) and the
        // lender's paid-in-advance ada at slot 1 (lm_liquidate_and_pay_in_advance_action reaches it
        // through assetOutputIndexes).
        tx.payToContract(request.bondUtxo().getAddress(), List.copyOf(request.bondUtxo().getAmount()),
                bondEchoDatum(request));
        tx.payToContract(assetManagerAddress(), collateralEquityAmounts(request, numbers.equity()),
                borrowerCompensationDatum(request));
        tx.payToContract(assetManagerAddress(),
                List.of(Amount.lovelace(numbers.convertedLoanCollateralToPrincipalAmount())),
                lenderConvertedDatum(request));

        // Withdraw-0 invocations. The main config authorises loan / claim / pay-in-advance action; the
        // parent LenderManager reads the LM config and carries the LiquidateAndPayInAdvance action.
        tx.withdraw(rewardAddress(registry.getLoanPolicyId()), BigInteger.ZERO,
                LiquidationTxEncoder.loanWithdrawRedeemer(configRefIndex), request.changeAddress());
        tx.withdraw(rewardAddress(registry.getLoanClaimActionScriptHash()), BigInteger.ZERO,
                LiquidationTxEncoder.loanClaimActionWithdrawRedeemer(configRefIndex,
                        List.of(claimData(request, numbers, lenderBondOutputIndex,
                                collateralOracleRefIndex, principalOracleRefIndex))),
                request.changeAddress());
        tx.withdraw(rewardAddress(registry.getLenderManagerWithdrawScriptHash()), BigInteger.ZERO,
                lenderManagerPayInAdvanceRedeemer(lmConfigRefIndex), request.changeAddress());
        tx.withdraw(rewardAddress(registry.getLmLiquidateAndPayInAdvanceActionScriptHash()), BigInteger.ZERO,
                payInAdvanceActionRedeemer(configRefIndex, List.of(0L), List.of(request.loan().loanId()),
                        List.of(principalOracleRefIndex), List.of(collateralOracleRefIndex),
                        List.of(assetOutputIndex)),
                request.changeAddress());
        tx.withdraw(request.oracle().rewardAddress(), BigInteger.ZERO,
                LiquidationTxEncoder.oracleRedeemer(request.oracle().feed(), providerRefIndex, List.of()),
                request.changeAddress());

        tx.readFrom(refInputs.toArray(TransactionInput[]::new));

        attachValidators(tx);
        return tx.withChangeAddress(request.changeAddress());
    }

    /**
     * The six validators this transaction invokes, attached to the witness set. The
     * pay-in-advance action script is the one {@link EvalFixtures#scriptSupplier} does not carry, so
     * attaching every one of them keeps the resolution self-contained (the oracle travels by reference
     * input, resolved from the {@code List.of(oracleScript())} extra at evaluation time).
     */
    private void attachValidators(ScriptTx tx) {
        tx.attachSpendingValidator(registry.getLoanSpendScript());
        tx.attachSpendingValidator(registry.getLenderManagerSpendScript());
        tx.attachRewardValidator(registry.getLoanScript());
        tx.attachRewardValidator(registry.getLoanClaimActionScript());
        tx.attachRewardValidator(registry.getLenderManagerScript());
        tx.attachRewardValidator(registry.getLmLiquidateAndPayInAdvanceActionScript());
    }

    private Transaction complete(Request request, ScriptTx tx) {
        try {
            // Third argument (TransactionProcessor) stays null; see the class javadoc.
            return new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null)
                    .compose(tx)
                    .feePayer(request.changeAddress())
                    .collateralPayer(request.changeAddress())
                    .validFrom(request.validFromSlot())
                    .validTo(request.validToSlot())
                    .mergeOutputs(false)
                    // No evaluator here: the redeemers keep placeholder ex-units and the offline rig
                    // prices them itself. Nothing must be fetched — the rig hands every script in.
                    .ignoreScriptCostEvaluationError(true)
                    .withScriptSupplier(scriptHash -> Optional.empty())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the pay-in-advance transaction", e);
        }
    }

    // ---- redeemers --------------------------------------------------------------------------------

    /**
     * {@code LenderManagerWithdrawRedeemer { configRefInputIndex, LiquidateAndPayInAdvance } } —
     * {@code LenderManagerAction.LiquidateAndPayInAdvance} is constructor index 3 (WithdrawBonds 0,
     * Liquidate 1, Compound 2), verified against {@code lib/fluidtokens/types/lender_manager.ak} at
     * {@code ff005fb}. Built inline: {@link LiquidationTxEncoder#lenderManagerWithdrawRedeemer} hard-codes
     * the {@code Liquidate} action instead.
     */
    private static PlutusData lenderManagerPayInAdvanceRedeemer(long lmConfigRefIndex) {
        return constr(0, BigIntPlutusData.of(lmConfigRefIndex), constr(3));
    }

    /**
     * {@code LMLiquidateAndPayInAdvanceWithdrawRedeemer { configRefInputIndex, lenderBondInputIndexes,
     * lenderBondAssetNames, principalAndCollateralOracleRefInputIndexes, assetOutputIndexes } } —
     * constructor index 0. The oracle field is a list of {@code (Int, Int)} pairs, principal index
     * first, collateral second (the destructuring order in
     * {@code lm_liquidate_and_pay_in_advance_action.ak}). Built inline, per the schema at {@code ff005fb}.
     */
    private static PlutusData payInAdvanceActionRedeemer(long configRefInputIndex,
                                                         List<Long> lenderBondInputIndexes,
                                                         List<String> lenderBondAssetNamesHex,
                                                         List<Integer> principalOracleRefIndexes,
                                                         List<Integer> collateralOracleRefIndexes,
                                                         List<Long> assetOutputIndexes) {
        PlutusData bondIndexes = list(lenderBondInputIndexes.stream()
                .map(BigIntPlutusData::of).toArray(PlutusData[]::new));
        PlutusData bondNames = list(lenderBondAssetNamesHex.stream()
                .map(name -> (PlutusData) com.bloxbean.cardano.client.plutus.spec.BytesPlutusData
                        .of(HexUtil.decodeHexString(name)))
                .toArray(PlutusData[]::new));
        // An Aiken 2-tuple (Int, Int) serialises as a bare PlutusData list [a, b] — NOT a Constr.
        PlutusData[] pairs = new PlutusData[principalOracleRefIndexes.size()];
        for (int i = 0; i < pairs.length; i++) {
            pairs[i] = list(BigIntPlutusData.of(principalOracleRefIndexes.get(i)),
                    BigIntPlutusData.of(collateralOracleRefIndexes.get(i)));
        }
        PlutusData assetOutputs = list(assetOutputIndexes.stream()
                .map(BigIntPlutusData::of).toArray(PlutusData[]::new));
        return constr(0, BigIntPlutusData.of(configRefInputIndex), bondIndexes, bondNames,
                list(pairs), assetOutputs);
    }

    private ClaimData claimData(Request request, Numbers numbers, long lenderBondOutputIndex,
                               int collateralOracleRefIndex, int principalOracleRefIndex) {
        return new ClaimData(
                request.loan().datum().liquidationMode(),
                BigInteger.valueOf(lenderBondOutputIndex),
                BigInteger.valueOf(collateralOracleRefIndex),
                BigInteger.valueOf(principalOracleRefIndex),
                request.bond().datum().lenderAuth(),
                numbers.equity(),
                request.loan().loanId(),
                numbers.remainingDebt());
    }

    // ---- output datums ----------------------------------------------------------------------------

    /** The lender bond echo datum: the input's bytes, verbatim, so {@code equals_data} accepts it. */
    private static PlutusData bondEchoDatum(Request request) {
        try {
            return PlutusData.deserialize(
                    CborSerializationUtil.deserialize(HexUtil.decodeHexString(request.bondUtxo().getInlineDatum())));
        } catch (Exception e) {
            throw new IllegalStateException("cannot decode the lender bond input datum for its echo", e);
        }
    }

    /**
     * The borrower's equity compensation datum — {@code partial_liquidation}, owned by the borrower
     * bond, descending from the loan input. {@code loan_claim_action}'s {@code equity_sent_to_borrower}
     * compares against exactly this.
     */
    private PlutusData borrowerCompensationDatum(Request request) {
        return LiquidationTxEncoder.assetManagerDatumWithToken(new AssetManagerDatumWithToken(
                request.loanUtxo().getTxHash(), request.loanUtxo().getOutputIndex(),
                PARTIAL_LIQUIDATION_ACTION_HEX,
                new AssetType(registry.getBorrowerBondPolicyId(), request.loan().loanId())));
    }

    /**
     * The lender's paid-in-advance datum — {@code converted_to_liquidity}, owned by the lender bond.
     * {@code lm_liquidate_and_pay_in_advance_action}'s {@code validate_repayment_output} compares
     * against exactly this.
     */
    private PlutusData lenderConvertedDatum(Request request) {
        return LiquidationTxEncoder.assetManagerDatumWithToken(new AssetManagerDatumWithToken(
                request.loanUtxo().getTxHash(), request.loanUtxo().getOutputIndex(),
                CONVERTED_TO_LIQUIDITY_ACTION_HEX,
                new AssetType(registry.getLenderBondPolicyId(), request.loan().loanId())));
    }

    /** The borrower compensation value: {@code equity} collateral tokens, min-ada left to cardano-client-lib. */
    private List<Amount> collateralEquityAmounts(Request request, BigInteger equity) {
        AssetType collateral = request.loan().datum().collateral().assetType();
        return List.of(Amount.asset(collateral.policyId() + collateral.assetName(), equity));
    }

    // ---- addresses --------------------------------------------------------------------------------

    /**
     * The asset-manager spend credential as an enterprise address. {@code get_outputs_to_smart_credential}
     * filters on the payment credential alone in its native-token branch, so no stake part is needed and
     * both asset-manager outputs may share it.
     */
    private String assetManagerAddress() {
        return AddressProvider.getEntAddress(
                Credential.fromScript(registry.getAssetManagerSpendScriptHash()), network).getAddress();
    }

    private String rewardAddress(String scriptHash) {
        return AddressProvider.getRewardAddress(Credential.fromScript(scriptHash), network).getAddress();
    }

    // ---- reference inputs & derivations -----------------------------------------------------------

    /** Config, LM config and the three oracle reference inputs, canonically sorted. */
    private List<TransactionInput> referenceInputs(Request request) {
        List<TransactionInput> refInputs = new ArrayList<>(List.of(
                inputOf(request.configUtxo()),
                inputOf(request.lmConfigUtxo()),
                request.oracle().referenceInput(),
                request.oracle().referenceScript(),
                request.oracle().charlieProviderReferenceInput()));
        refInputs.sort(new TransactionInputComparator());
        return refInputs;
    }

    private static int refIndex(List<TransactionInput> refInputs, TransactionInput input, String what) {
        int index = refInputs.indexOf(input);
        if (index < 0) {
            throw new IllegalStateException(what + " reference input " + input + " is not among the body's");
        }
        return index;
    }

    /** The absolute output position of the lender-bond echo — matched on address, datum and NFT. */
    private long locateBondOutput(Transaction probe, Request request) {
        List<TransactionOutput> outputs = probe.getBody().getOutputs();
        List<Integer> matches = new ArrayList<>();
        AssetType bondNft = new AssetType(registry.getLenderBondPolicyId(), request.loan().loanId());
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput output = outputs.get(i);
            if (request.bondUtxo().getAddress().equals(output.getAddress())
                    && output.getInlineDatum() != null
                    && output.getInlineDatum().serializeToHex()
                    .equalsIgnoreCase(request.bondUtxo().getInlineDatum())
                    && quantityOf(output, bondNft).equals(BigInteger.ONE)) {
                matches.add(i);
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException("bond echo matches " + matches.size() + " outputs, expected one");
        }
        return matches.getFirst().longValue();
    }

    /**
     * The position of the lender's converted output <em>within the asset-manager-credential-filtered
     * output list</em> — the index {@code assetOutputIndexes} carries. Matched by its
     * {@code converted_to_liquidity} datum so a reorder relative to the borrower compensation output is
     * caught rather than assumed.
     */
    private long locateLenderConvertedOutput(Transaction probe, Request request, Numbers numbers) {
        String convertedDatumHex = lenderConvertedDatum(request).serializeToHex();
        List<TransactionOutput> filtered = assetManagerOutputs(probe);
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < filtered.size(); i++) {
            TransactionOutput output = filtered.get(i);
            if (output.getInlineDatum() != null
                    && output.getInlineDatum().serializeToHex().equalsIgnoreCase(convertedDatumHex)) {
                matches.add(i);
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "the lender converted output matches " + matches.size() + " asset-manager outputs, expected one");
        }
        return matches.getFirst().longValue();
    }

    /** The outputs at the asset-manager spend credential, in body order — the {@code get_outputs_to_smart_credential} list. */
    private List<TransactionOutput> assetManagerOutputs(Transaction tx) {
        String assetManagerSpend = registry.getAssetManagerSpendScriptHash();
        List<TransactionOutput> filtered = new ArrayList<>();
        for (TransactionOutput output : tx.getBody().getOutputs()) {
            if (assetManagerSpend.equals(paymentCredentialOf(output.getAddress()))) {
                filtered.add(output);
            }
        }
        return filtered;
    }

    // ---- structural re-derivation from the finished body ------------------------------------------

    private void assertStructure(Transaction transaction, Request request, Numbers numbers,
                                 long lenderBondOutputIndex, long assetOutputIndex) {
        // The bond echo is where the claim redeemer says, and it is byte-identical to the input.
        structural(lenderBondOutputIndex == locateBondOutput(transaction, request),
                "lenderBondOutputIndex " + lenderBondOutputIndex + " no longer points at the bond echo");
        TransactionOutput echo = transaction.getBody().getOutputs().get((int) lenderBondOutputIndex);
        structural(request.bondUtxo().getInlineDatum().equalsIgnoreCase(echo.getInlineDatum().serializeToHex()),
                "the bond echo datum is not byte-identical to the bond input datum");

        // The asset-manager filtered list is [borrower compensation, lender converted], and
        // assetOutputIndexes points at the lender one.
        List<TransactionOutput> assetOutputs = assetManagerOutputs(transaction);
        structural(assetOutputs.size() == 2, "expected exactly two asset-manager outputs, got " + assetOutputs.size());
        structural(assetOutputIndex == locateLenderConvertedOutput(transaction, request, numbers),
                "assetOutputIndex " + assetOutputIndex + " no longer points at the lender converted output");
        structural(assetOutputIndex != 0,
                "the borrower compensation output must occupy filtered slot 0 (the bare loan index)");

        // The lender's paid-in-advance output is ada-only (dosProtection flatten == 1) and covers the amount.
        TransactionOutput lenderOutput = assetOutputs.get((int) assetOutputIndex);
        structural(flattenedCount(lenderOutput) == 1, "the lender converted output must be ada-only (flatten == 1)");
        structural(lenderOutput.getValue().getCoin()
                        .compareTo(numbers.convertedLoanCollateralToPrincipalAmount()) >= 0,
                "the lender converted output holds less ada than convertedLoanCollateralToPrincipalAmount");

        // The borrower compensation output carries at least the equity in collateral tokens, flatten == 2.
        TransactionOutput borrowerOutput = assetOutputs.get(0);
        AssetType collateral = request.loan().datum().collateral().assetType();
        structural(quantityOf(borrowerOutput, collateral).compareTo(numbers.equity()) >= 0,
                "the borrower compensation output holds less collateral than the equity");
        structural(flattenedCount(borrowerOutput) == 2,
                "the borrower compensation output must be a token plus its min-ada rider (flatten == 2)");

        // The loan NFT is burned exactly once.
        structural(mintedQuantity(transaction, new AssetType(registry.getLoanPolicyId(), request.loan().loanId()))
                        .equals(BigInteger.ONE.negate()),
                "the loan NFT must be burned exactly once");
    }

    // ---- primitives -------------------------------------------------------------------------------

    private static ConstrPlutusData constr(int alternative, PlutusData... fields) {
        return ConstrPlutusData.builder().alternative(alternative).data(ListPlutusData.of(fields)).build();
    }

    private static PlutusData list(PlutusData... items) {
        return ListPlutusData.of(items);
    }

    private static TransactionInput inputOf(Utxo utxo) {
        return new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex());
    }

    private static String paymentCredentialOf(String address) {
        return new com.bloxbean.cardano.client.address.Address(address)
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse("");
    }

    private static BigInteger quantityOf(TransactionOutput output, AssetType asset) {
        return output.getValue().getMultiAssets().stream()
                .filter(multiAsset -> multiAsset.getPolicyId().equalsIgnoreCase(asset.policyId()))
                .flatMap(multiAsset -> multiAsset.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(a.getNameAsBytes()).equalsIgnoreCase(asset.assetName()))
                .map(Asset::getValue)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static BigInteger mintedQuantity(Transaction tx, AssetType asset) {
        return tx.getBody().getMint().stream()
                .filter(multiAsset -> multiAsset.getPolicyId().equalsIgnoreCase(asset.policyId()))
                .flatMap(multiAsset -> multiAsset.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(a.getNameAsBytes()).equalsIgnoreCase(asset.assetName()))
                .map(Asset::getValue)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static int flattenedCount(TransactionOutput output) {
        int tokens = output.getValue().getMultiAssets().stream()
                .mapToInt(multiAsset -> multiAsset.getAssets().size())
                .sum();
        return tokens + (output.getValue().getCoin().signum() > 0 ? 1 : 0);
    }

    private static void structural(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("STRUCTURAL_ASSERTION_FAILED: " + message);
        }
    }
}
