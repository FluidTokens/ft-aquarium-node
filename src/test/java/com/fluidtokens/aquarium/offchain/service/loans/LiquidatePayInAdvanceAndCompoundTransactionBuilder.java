package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
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
 * A <b>test-only</b> builder for a {@code LiquidateAndPayInAdvanceAndCompound} liquidation — the
 * money-path mode in which the bot pays the loan's principal in advance, takes the collateral,
 * <em>converts</em> it to the principal currency (ADA) through the oracles, and — instead of paying
 * that ADA to the lender as the plain pay-in-advance mode does — <b>compounds it into the lender's
 * pool</b>. It reproduces the shape the deployed preview
 * {@code lm_liquidate_pay_in_advance_and_compound_action}, {@code lender_manager.lenderManager},
 * {@code loan_claim_action}, {@code loan.loan}, {@code pool.pool}, {@code pool_compound_action},
 * {@code pool_manager.poolManager}, {@code pm_compound_liquidity}, four {@code general_spend} wrappers
 * and the FluidTokens oracle accept under the real PlutusV3 machine, for <b>one</b> loan compounded
 * into <b>one</b> pool.
 *
 * <h2>⚠ DO NOT PROMOTE THIS TO {@code src/main} AS IT STANDS — CCL TRAP 8 IS STILL IN IT</h2>
 * {@link #complete} builds with {@code new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null)}
 * and {@code ignoreScriptCostEvaluationError(true)}. That third {@code null} is <b>not only</b> the
 * {@code TransactionProcessor} — <b>the same argument becomes the {@code TransactionEvaluator}</b>, so
 * the script-cost step is never run and every transaction this builds declares cardano-client-lib's
 * <b>placeholder ex-units</b> (10000 mem / 10000-or-1000 steps) against real costs 2–5 orders of
 * magnitude larger. Under-declared ex-units are not rejected at the mempool: the transaction lands and
 * exhausts its budget on chain, <b>in phase two, collateral forfeit</b>.
 * <p>
 * This is exactly the defect that took down the production convert path on <b>2026-08-21</b> and was
 * fixed for the sibling builder by {@code 5b439da} ({@code withTxEvaluator(...)} +
 * {@code ignoreScriptCostEvaluationError(evaluator == null)}). It survives here only because this
 * builder has <b>no production caller</b> — the dry-eval test supplies its own evaluator through the
 * offline rig, which is why the tests are green and prove nothing about this.
 * <p>
 * <b>Corrective #2 (in force since 2026-08-21) applies directly: a test→{@code src/main} promotion
 * REQUIRES a production-wiring test; byte-identity is NEVER the sole gate.</b> Promoting this file
 * byte-identically — the way {@link LiquidatePayInAdvanceTransactionBuilder} was promoted — would
 * reintroduce the same incident verbatim. Fix the evaluator first, then prove it through the
 * Spring-wired shape. See {@code CLAUDE.md} "Gotchas" and {@code LiquidateTransactionBuilder}'s own
 * javadoc, which documented this trap before either incident.
 *
 * <h2>Structurally incapable of submitting</h2>
 * The {@link QuickTxBuilder} below is constructed with a <b>null</b> {@code TransactionProcessor} —
 * the only thing in cardano-client-lib that can put a transaction on a network — exactly as
 * {@link LiquidatePayInAdvanceTransactionBuilder} is. {@link #build} returns an <b>unsigned</b>
 * {@link Transaction}; there is no evaluator, no signer, no key and no network. The ex-units the
 * redeemers carry are cardano-client-lib's placeholders; the real ones are measured separately by
 * {@link LiquidatePayInAdvanceAndCompoundDryEvalTest} through the UPLC machine. <b>That last sentence
 * is the trap above, stated as if it were a safety property: read it together with the warning.</b>
 *
 * <h2>How it differs from the plain {@code LiquidateAndPayInAdvance} builder</h2>
 * The claim leg — the loan spend, the {@code loan_claim_action} redeemer, the borrower's equity
 * compensation output and the loan-NFT burn — is <b>unchanged</b> from
 * {@link LiquidatePayInAdvanceTransactionBuilder}: {@code loan_claim_action} governs them identically
 * on either path. What changes is where the converted collateral goes:
 * <ul>
 *   <li>there is <b>no</b> lender {@code converted_to_liquidity} ADA output — instead the pool UTxO is
 *       spent (through its {@code general_spend} wrapper) and re-emitted with its lovelace increased by
 *       exactly {@code convertedLoanCollateralToPrincipalAmount}. The pool datum and address are
 *       byte-identical to the input ({@code pool_compound_action} demands {@code equals_data} on both,
 *       and {@code lm_liquidate_pay_in_advance_and_compound_action} demands the value delta be exactly
 *       the oracle-derived converted amount — the pool manager's {@code compoudingFeePerMille} is 0, so
 *       no fee is subtracted);</li>
 *   <li>the pool manager UTxO is spent (through its own {@code general_spend} wrapper) and re-emitted
 *       <b>byte-identical</b> to its input ({@code equals_data(poolManagerOutput,
 *       poolManagerInput.output)});</li>
 *   <li>the parent {@code lender_manager.lenderManager} withdraw carries
 *       {@code LenderManagerAction.LiquidatePayInAdvanceAndCompound} (constructor index 5, verified
 *       against {@code lib/fluidtokens/types/lender_manager.ak} at {@code ff005fb}), and three further
 *       withdrawals appear: {@code pool.pool(Compound)} (Action index 3), {@code pool_compound_action}
 *       and the pool-manager pair {@code pool_manager.poolManager(CompoundLiquidity)}
 *       (PoolManagerAction index 2) plus {@code pm_compound_liquidity}.</li>
 * </ul>
 *
 * <h2>Self-authorising: no signature</h2>
 * The pool datum's {@code lenderAuth} is {@code CardanoWithdrawScript(poolManagerPolicyId)}, so
 * {@code pool_compound_action}'s {@code authorize_action} conjunct is satisfied by the
 * {@code pool_manager.poolManager} withdrawal that is already in the transaction — no required signer,
 * no key, no signature.
 *
 * <h2>Every index is derived from the finished body</h2>
 * Five things cardano-client-lib decides, not this builder, appear in redeemers: the absolute output
 * position of the bond echo ({@code loan_claim_action}'s {@code lenderBondOutputIndex}); the bond's and
 * the pool's positions within their smart-credential-filtered input lists (the compound action's
 * {@code lenderBondInputIndexes}/{@code poolInputIndexes}); and the two indexes into the finished body's
 * {@code self.redeemers} that {@code pm_compound_liquidity} carries (the pool.pool and the parent
 * lender-manager withdraw redeemers' positions). As {@link LiquidatePayInAdvanceTransactionBuilder} and
 * {@link PoolCancelTransactionBuilder} do, the transaction is assembled once with placeholder indexes to
 * observe the finished layout (the <em>probe</em>), the real indexes are read off that body, and it is
 * assembled again; then {@link #assertStructure} re-derives them from the finished body and refuses on
 * any mismatch.
 */
public final class LiquidatePayInAdvanceAndCompoundTransactionBuilder {

    /** The unit redeemer {@code Constr 0 []} the {@code general_spend} handler takes. */
    private static final PlutusData GENERAL_SPEND_REDEEMER =
            ConstrPlutusData.builder().alternative(0).data(ListPlutusData.of()).build();

    /** ASCII {@code "partial_liquidation"} — {@code constants.action_partial_liquidation_compensation}. */
    static final String PARTIAL_LIQUIDATION_ACTION_HEX =
            HexUtil.encodeHexString("partial_liquidation".getBytes(StandardCharsets.US_ASCII));

    private final LoansContractRegistry registry;
    private final Network network;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;

    public LiquidatePayInAdvanceAndCompoundTransactionBuilder(LoansContractRegistry registry,
                                                              Network network,
                                                              UtxoSupplier utxoSupplier,
                                                              ProtocolParamsSupplier protocolParamsSupplier) {
        this.registry = registry;
        this.network = network;
        this.utxoSupplier = utxoSupplier;
        this.protocolParamsSupplier = protocolParamsSupplier;
    }

    /**
     * Everything the pay-in-advance-and-compound transaction needs, all of it honest values — the
     * adversarial shape {@link LiquidatePayInAdvanceAndCompoundDryEvalTest} feeds the machine is produced
     * by byte-surgery on the finished body, not by mis-configuring this request.
     *
     * @param loan             the decoded loan, spent through {@code general_spend}
     * @param loanUtxo         the loan UTxO
     * @param bond             the decoded lender bond
     * @param bondUtxo         the lender-bond UTxO; its output is echoed byte-for-byte
     * @param poolUtxo         the pool UTxO, spent through {@code general_spend}; its lovelace is raised by
     *                         the converted amount and its datum + address are echoed byte-for-byte
     * @param poolManagerUtxo  the pool-manager UTxO, spent through {@code general_spend}; its output is
     *                         echoed byte-for-byte
     * @param walletUtxo       funds fees and min-ada
     * @param configUtxo       the main config reference input
     * @param lmConfigUtxo     the LenderManager config reference input
     * @param oracle           the Charli3-backed collateral oracle entry
     * @param poolIdHex        the pool NFT asset name — the compound action's {@code poolIds} entry and the
     *                         {@code CompoundData.poolId}; equals {@code bond.datum().poolId()} on chain
     * @param validFromMillis  the instant the debt and equity are computed at — must be the POSIX time
     *                         {@code validFromSlot} converts back to, so the on-chain recomputation matches
     * @param validFromSlot    the validity range lower bound, in slots
     * @param validToSlot      the validity range upper bound, in slots
     * @param changeAddress    fee payer and change address (the bot)
     */
    public record Request(Loan loan,
                          Utxo loanUtxo,
                          LenderBond bond,
                          Utxo bondUtxo,
                          Utxo poolUtxo,
                          Utxo poolManagerUtxo,
                          Utxo walletUtxo,
                          Utxo configUtxo,
                          Utxo lmConfigUtxo,
                          OracleEntry oracle,
                          String poolIdHex,
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
     * The five numbers, computed exactly as {@link LiquidatePayInAdvanceTransactionBuilder} and the
     * on-chain validators do. {@code convertedLoanCollateralToPrincipalAmount} is the amount added to the
     * pool's lovelace during the compound.
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
     * Assembles and completes the transaction: probe for the layout, rebuild with the observed indexes,
     * then re-derive and assert them against the finished body.
     */
    public Transaction build(Request request) {
        Numbers numbers = numbers(request);
        if (numbers.equity().signum() <= 0) {
            throw new IllegalStateException(
                    "this builder models the positive-equity compound layout; equity was "
                            + numbers.equity());
        }
        if (!request.bond().datum().shouldLiquidationConvertToPrincipal()) {
            throw new IllegalStateException(
                    "lm_liquidate_pay_in_advance_and_compound_action requires "
                            + "shouldLiquidationConvertToPrincipal == True");
        }

        List<TransactionInput> refInputs = referenceInputs(request);
        int configRefIndex = refIndex(refInputs, inputOf(request.configUtxo()), "main config");
        int lmConfigRefIndex = refIndex(refInputs, inputOf(request.lmConfigUtxo()), "lm config");
        int collateralOracleRefIndex = refIndex(refInputs, request.oracle().referenceInput(), "collateral oracle");
        // Principal is ADA: retrieve_oracle_data short-circuits on policyId == "" and never reads the
        // reference input, but the index must still be in range. Index 0 always is.
        int principalOracleRefIndex = 0;
        int providerRefIndex = refIndex(refInputs, request.oracle().charlieProviderReferenceInput(),
                "charli3 provider");

        // Probe with placeholder indexes purely to observe the finished layout.
        Layout probe = layout(complete(request, assemble(request, numbers, configRefIndex, lmConfigRefIndex,
                collateralOracleRefIndex, principalOracleRefIndex, providerRefIndex, refInputs,
                new Indexes(0L, 0L, 0L, 0L, 0L))), request);

        Transaction transaction = complete(request, assemble(request, numbers, configRefIndex,
                lmConfigRefIndex, collateralOracleRefIndex, principalOracleRefIndex, providerRefIndex,
                refInputs, probe.indexes()));

        assertStructure(transaction, request, numbers, probe.indexes());
        return transaction;
    }

    /** The five body-derived indexes the redeemers rest on. */
    private record Indexes(long lenderBondOutputIndex,
                           long lenderBondFilteredInputIndex,
                           long poolFilteredInputIndex,
                           long poolWithdrawRedeemerIndex,
                           long lenderManagerWithdrawRedeemerIndex) {
    }

    private record Layout(Indexes indexes) {
    }

    private Layout layout(Transaction probe, Request request) {
        long lenderBondOutputIndex = locateBondOutput(probe, request);
        long lenderBondFilteredInputIndex = filteredInputIndex(probe,
                registry.getLenderManagerSpendScriptHash(), inputOf(request.bondUtxo()), "lender bond");
        long poolFilteredInputIndex = filteredInputIndex(probe,
                registry.getPoolSpendScriptHash(), inputOf(request.poolUtxo()), "pool");
        long poolWithdrawRedeemerIndex = redeemerIndexIn(probe, registry.getPoolPolicyId(), "pool.pool");
        long lmWithdrawRedeemerIndex = redeemerIndexIn(probe,
                registry.getLenderManagerWithdrawScriptHash(), "lender_manager.lenderManager");
        return new Layout(new Indexes(lenderBondOutputIndex, lenderBondFilteredInputIndex,
                poolFilteredInputIndex, poolWithdrawRedeemerIndex, lmWithdrawRedeemerIndex));
    }

    // ---- assembly ---------------------------------------------------------------------------------

    private ScriptTx assemble(Request request, Numbers numbers, int configRefIndex, int lmConfigRefIndex,
                              int collateralOracleRefIndex, int principalOracleRefIndex,
                              int providerRefIndex, List<TransactionInput> refInputs, Indexes indexes) {
        ScriptTx tx = new ScriptTx();
        String loanId = request.loan().loanId();

        // Inputs. The general_spend handlers take a unit redeemer; the real authorisation is the
        // withdraw-0 invocation of the validator each wraps. The wallet funds fees and min-ada.
        tx.collectFrom(request.loanUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.bondUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.poolUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.poolManagerUtxo(), GENERAL_SPEND_REDEEMER);
        tx.collectFrom(request.walletUtxo());

        // Burn the loan NFT (loan_claim_action.ak requires quantity_of(self.mint, loanPolicyId, loanId) == -1).
        tx.mintAsset(registry.getLoanScript(),
                List.of(new Asset("0x" + loanId, BigInteger.ONE.negate())),
                LiquidationTxEncoder.loanMintRedeemer(configRefIndex, false, 0));

        // Outputs (exactly four). The bond echo first (byte-identical to input), then — in the filtered
        // asset-manager list — the borrower's equity compensation at slot 0 (loan_claim_action reads it at
        // the bare loan index); then the compounded pool output (input value + converted lovelace, datum
        // and address byte-identical); then the pool-manager echo (byte-identical to input).
        tx.payToContract(request.bondUtxo().getAddress(), List.copyOf(request.bondUtxo().getAmount()),
                echoDatum(request.bondUtxo()));
        tx.payToContract(assetManagerAddress(), collateralEquityAmounts(request, numbers.equity()),
                borrowerCompensationDatum(request));
        tx.payToContract(request.poolUtxo().getAddress(),
                compoundedPoolAmounts(request, numbers.convertedLoanCollateralToPrincipalAmount()),
                echoDatum(request.poolUtxo()));
        tx.payToContract(request.poolManagerUtxo().getAddress(),
                List.copyOf(request.poolManagerUtxo().getAmount()),
                echoDatum(request.poolManagerUtxo()));

        // Withdraw-0 invocations. The main config authorises loan / claim / compound action / pool /
        // pool_compound / pool_manager; the parent LenderManager reads the LM config and carries the
        // LiquidatePayInAdvanceAndCompound action.
        tx.withdraw(rewardAddress(registry.getLoanPolicyId()), BigInteger.ZERO,
                LiquidationTxEncoder.loanWithdrawRedeemer(configRefIndex), request.changeAddress());
        tx.withdraw(rewardAddress(registry.getLoanClaimActionScriptHash()), BigInteger.ZERO,
                LiquidationTxEncoder.loanClaimActionWithdrawRedeemer(configRefIndex,
                        List.of(claimData(request, numbers, indexes.lenderBondOutputIndex(),
                                collateralOracleRefIndex, principalOracleRefIndex))),
                request.changeAddress());
        tx.withdraw(rewardAddress(registry.getLenderManagerWithdrawScriptHash()), BigInteger.ZERO,
                lenderManagerCompoundActionRedeemer(lmConfigRefIndex), request.changeAddress());
        tx.withdraw(rewardAddress(registry.getLmLiquidatePayInAdvanceAndCompoundActionScriptHash()),
                BigInteger.ZERO,
                payInAdvanceAndCompoundActionRedeemer(configRefIndex,
                        List.of(indexes.lenderBondFilteredInputIndex()), List.of(request.loan().loanId()),
                        List.of(request.poolIdHex()), List.of(indexes.poolFilteredInputIndex()),
                        List.of(principalOracleRefIndex), List.of(collateralOracleRefIndex)),
                request.changeAddress());
        tx.withdraw(rewardAddress(registry.getPoolPolicyId()), BigInteger.ZERO,
                poolCompoundWithdrawRedeemer(configRefIndex), request.changeAddress());
        tx.withdraw(rewardAddress(registry.getPoolCompoundActionScriptHash()), BigInteger.ZERO,
                poolCompoundActionRedeemer(configRefIndex, List.of(request.poolIdHex())),
                request.changeAddress());
        tx.withdraw(rewardAddress(registry.getPoolManagerPolicyId()), BigInteger.ZERO,
                poolManagerCompoundLiquidityWithdrawRedeemer(configRefIndex), request.changeAddress());
        tx.withdraw(rewardAddress(registry.getPmCompoundLiquidityScriptHash()), BigInteger.ZERO,
                pmCompoundLiquidityRedeemer(indexes.poolWithdrawRedeemerIndex(),
                        indexes.lenderManagerWithdrawRedeemerIndex()),
                request.changeAddress());
        tx.withdraw(request.oracle().rewardAddress(), BigInteger.ZERO,
                LiquidationTxEncoder.oracleRedeemer(request.oracle().feed(), providerRefIndex, List.of()),
                request.changeAddress());

        tx.readFrom(refInputs.toArray(TransactionInput[]::new));

        attachValidators(tx);
        return tx.withChangeAddress(request.changeAddress());
    }

    /**
     * Every validator this transaction invokes, attached to the witness set. The four action scripts the
     * {@link EvalFixtures#scriptSupplier} does not carry (the compound action, {@code pool_compound_action}
     * and {@code pm_compound_liquidity}) plus the pool/pool-manager policies and wrappers are attached, so
     * a missing one surfaces as a {@code RequiredRedeemersMismatch} rather than silently passing. The
     * oracle travels by reference input, resolved from the {@code List.of(oracleScript())} extra.
     */
    private void attachValidators(ScriptTx tx) {
        tx.attachSpendingValidator(registry.getLoanSpendScript());
        tx.attachSpendingValidator(registry.getLenderManagerSpendScript());
        tx.attachSpendingValidator(registry.getPoolSpendScript());
        tx.attachSpendingValidator(registry.getPoolManagerSpendScript());
        tx.attachRewardValidator(registry.getLoanScript());
        tx.attachRewardValidator(registry.getLoanClaimActionScript());
        tx.attachRewardValidator(registry.getLenderManagerScript());
        tx.attachRewardValidator(registry.getLmLiquidatePayInAdvanceAndCompoundActionScript());
        tx.attachRewardValidator(registry.getPoolScript());
        tx.attachRewardValidator(registry.getPoolCompoundActionScript());
        tx.attachRewardValidator(registry.getPoolManagerScript());
        tx.attachRewardValidator(registry.getPmCompoundLiquidityScript());
    }

    private Transaction complete(Request request, ScriptTx tx) {
        try {
            // ⚠ CCL TRAP 8 — the third argument is NOT only the TransactionProcessor. The same null
            // becomes the TransactionEvaluator, so the two lines below ship PLACEHOLDER ex-units.
            // Harmless while this builder is test-only; a phase-2 failure with collateral forfeit the
            // moment it is promoted. DO NOT PROMOTE WITHOUT FIXING THIS — see the class javadoc's
            // "DO NOT PROMOTE" section, corrective #2, and the 5b439da fix for the sibling builder.
            return new QuickTxBuilder(utxoSupplier, protocolParamsSupplier, null)
                    .compose(tx)
                    .feePayer(request.changeAddress())
                    .collateralPayer(request.changeAddress())
                    .validFrom(request.validFromSlot())
                    .validTo(request.validToSlot())
                    .mergeOutputs(false)
                    // No evaluator here: the redeemers keep placeholder ex-units and the offline rig
                    // prices them itself. Nothing must be fetched — the rig hands every script in.
                    // This is the fixture supplying what production would have to earn: see above.
                    .ignoreScriptCostEvaluationError(true)
                    .withScriptSupplier(scriptHash -> Optional.empty())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("cannot build the pay-in-advance-and-compound transaction", e);
        }
    }

    // ---- redeemers --------------------------------------------------------------------------------

    /**
     * {@code LenderManagerWithdrawRedeemer { configRefInputIndex, LiquidatePayInAdvanceAndCompound } } —
     * {@code LenderManagerAction.LiquidatePayInAdvanceAndCompound} is constructor index 5 (WithdrawBonds 0,
     * Liquidate 1, Compound 2, LiquidateAndPayInAdvance 3, LiquidateAndConvert 4), verified against
     * {@code lib/fluidtokens/types/lender_manager.ak} at {@code ff005fb}.
     */
    private static PlutusData lenderManagerCompoundActionRedeemer(long lmConfigRefIndex) {
        return constr(0, BigIntPlutusData.of(lmConfigRefIndex), constr(5));
    }

    /**
     * {@code LMLiquidatePayInAdvanceAndCompoundWithdrawRedeemer { configRefInputIndex,
     * lenderBondInputIndexes, lenderBondAssetNames, poolIds, poolInputIndexes,
     * principalAndCollateralOracleRefInputIndexes } } — constructor index 0, field order per
     * {@code lib/fluidtokens/types/lender_manager.ak} at {@code ff005fb}. The oracle field is a list of
     * {@code (Int, Int)} pairs, principal index first, collateral second (the destructuring order in
     * {@code lm_liquidate_pay_in_advance_and_compound_action.ak}).
     */
    private static PlutusData payInAdvanceAndCompoundActionRedeemer(long configRefInputIndex,
                                                                    List<Long> lenderBondInputIndexes,
                                                                    List<String> lenderBondAssetNamesHex,
                                                                    List<String> poolIdsHex,
                                                                    List<Long> poolInputIndexes,
                                                                    List<Integer> principalOracleRefIndexes,
                                                                    List<Integer> collateralOracleRefIndexes) {
        PlutusData bondIndexes = list(lenderBondInputIndexes.stream()
                .map(BigIntPlutusData::of).toArray(PlutusData[]::new));
        PlutusData bondNames = list(lenderBondAssetNamesHex.stream()
                .map(name -> (PlutusData) BytesPlutusData.of(HexUtil.decodeHexString(name)))
                .toArray(PlutusData[]::new));
        PlutusData poolIds = list(poolIdsHex.stream()
                .map(name -> (PlutusData) BytesPlutusData.of(HexUtil.decodeHexString(name)))
                .toArray(PlutusData[]::new));
        PlutusData poolIndexes = list(poolInputIndexes.stream()
                .map(BigIntPlutusData::of).toArray(PlutusData[]::new));
        // An Aiken 2-tuple (Int, Int) serialises as a bare PlutusData list [a, b] — NOT a Constr.
        PlutusData[] pairs = new PlutusData[principalOracleRefIndexes.size()];
        for (int i = 0; i < pairs.length; i++) {
            pairs[i] = list(BigIntPlutusData.of(principalOracleRefIndexes.get(i)),
                    BigIntPlutusData.of(collateralOracleRefIndexes.get(i)));
        }
        return constr(0, BigIntPlutusData.of(configRefInputIndex), bondIndexes, bondNames, poolIds,
                poolIndexes, list(pairs));
    }

    /**
     * {@code PoolWithdrawRedeemer { configRefInputIndex, Compound } } — {@code pool.Action.Compound} is
     * constructor index 3 (Cancel 0, Borrow 1, SellLenderPosition 2), verified against
     * {@code lib/fluidtokens/types/pool.ak} at {@code ff005fb}.
     */
    private static PlutusData poolCompoundWithdrawRedeemer(long configRefInputIndex) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex), constr(3));
    }

    /**
     * {@code PoolCompoundActionWithdrawRedeemer { configRefInputIndex, [CompoundData{poolId}] } } —
     * constructor index 0, {@code CompoundData} being constructor 0 with a single {@code poolId} field.
     */
    private static PlutusData poolCompoundActionRedeemer(long configRefInputIndex, List<String> poolIdsHex) {
        PlutusData actions = list(poolIdsHex.stream()
                .map(poolId -> (PlutusData) constr(0, BytesPlutusData.of(HexUtil.decodeHexString(poolId))))
                .toArray(PlutusData[]::new));
        return constr(0, BigIntPlutusData.of(configRefInputIndex), actions);
    }

    /**
     * {@code PoolManagerWithdrawRedeemer { configRefInputIndex, CompoundLiquidity } } —
     * {@code PoolManagerAction.CompoundLiquidity} is constructor index 2 (CancelPoolManager 0,
     * UpdatePoolManager 1), verified against {@code lib/fluidtokens/types/pool_manager.ak} at {@code ff005fb}.
     */
    private static PlutusData poolManagerCompoundLiquidityWithdrawRedeemer(long configRefInputIndex) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex), constr(2));
    }

    /**
     * {@code CompoundLiquidityActionWithdrawRedeemer { poolWithdrawRedeemerIndex,
     * lenderManagerWithdrawRedeemerIndex } } — constructor index 0, <b>no</b> configRefInputIndex. Both
     * fields are indexes into the finished body's {@code self.redeemers}, where {@code pm_compound_liquidity}
     * expects the {@code pool.pool} and the {@code lender_manager.lenderManager} withdraw redeemers.
     */
    private static PlutusData pmCompoundLiquidityRedeemer(long poolWithdrawRedeemerIndex,
                                                          long lenderManagerWithdrawRedeemerIndex) {
        return constr(0, BigIntPlutusData.of(poolWithdrawRedeemerIndex),
                BigIntPlutusData.of(lenderManagerWithdrawRedeemerIndex));
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

    // ---- output datums & values -------------------------------------------------------------------

    /** A UTxO's inline datum, deserialised verbatim, so {@code equals_data} accepts the echo. */
    private static PlutusData echoDatum(Utxo utxo) {
        try {
            return PlutusData.deserialize(
                    CborSerializationUtil.deserialize(HexUtil.decodeHexString(utxo.getInlineDatum())));
        } catch (Exception e) {
            throw new IllegalStateException("cannot decode the input datum for its echo: " + utxo, e);
        }
    }

    /**
     * The borrower's equity compensation datum — {@code partial_liquidation}, owned by the borrower bond,
     * descending from the loan input. {@code loan_claim_action}'s {@code equity_sent_to_borrower} compares
     * against exactly this.
     */
    private PlutusData borrowerCompensationDatum(Request request) {
        return LiquidationTxEncoder.assetManagerDatumWithToken(new AssetManagerDatumWithToken(
                request.loanUtxo().getTxHash(), request.loanUtxo().getOutputIndex(),
                PARTIAL_LIQUIDATION_ACTION_HEX,
                new AssetType(registry.getBorrowerBondPolicyId(), request.loan().loanId())));
    }

    /** The borrower compensation value: {@code equity} collateral tokens, min-ada left to cardano-client-lib. */
    private List<Amount> collateralEquityAmounts(Request request, BigInteger equity) {
        AssetType collateral = request.loan().datum().collateral().assetType();
        return List.of(Amount.asset(collateral.policyId() + collateral.assetName(), equity));
    }

    /**
     * The compounded pool value: the pool input value with its lovelace raised by exactly
     * {@code converted} (the pool manager's {@code compoudingFeePerMille} is 0, so no fee is subtracted).
     * Every non-lovelace asset — the pool NFT — is carried across unchanged, so
     * {@code equals_data(poolOutput.value, poolInput.value |> add(ada, "", converted))} holds.
     */
    private static List<Amount> compoundedPoolAmounts(Request request, BigInteger converted) {
        List<Amount> amounts = new ArrayList<>();
        for (Amount amount : request.poolUtxo().getAmount()) {
            if ("lovelace".equals(amount.getUnit())) {
                amounts.add(Amount.lovelace(amount.getQuantity().add(converted)));
            } else {
                amounts.add(Amount.asset(amount.getUnit(), amount.getQuantity()));
            }
        }
        return amounts;
    }

    // ---- addresses --------------------------------------------------------------------------------

    /**
     * The asset-manager spend credential as an enterprise address. {@code get_outputs_to_smart_credential}
     * filters on the payment credential alone in its native-token branch, so no stake part is needed.
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
     * The position of {@code target} within the finished body's inputs filtered by {@code credentialHash} —
     * exactly what {@code get_inputs_from_smart_credential} / {@code list.filter} produce, because the
     * body's inputs are in canonical order and the filter preserves it.
     */
    private long filteredInputIndex(Transaction tx, String credentialHash, TransactionInput target,
                                    String what) {
        List<TransactionInput> filtered = new ArrayList<>();
        for (TransactionInput input : tx.getBody().getInputs()) {
            if (credentialHash.equals(paymentCredentialOf(resolve(input).getAddress()))) {
                filtered.add(input);
            }
        }
        int index = filtered.indexOf(target);
        if (index < 0) {
            throw new IllegalStateException("the " + what + " input " + target
                    + " is not among the body's inputs at credential " + credentialHash);
        }
        return index;
    }

    /**
     * Where the {@code Withdraw(Script(scriptHash))} redeemer sits in {@code self.redeemers} — the whole
     * grouped {@code Pairs<ScriptPurpose, Redeemer>} list a Plutus V3 script sees, computed as
     * {@code (#Mint + #Spend) + rewardIndexOfThisWithdrawal}. The same derivation
     * {@link PoolCancelTransactionBuilder#poolWithdrawRedeemerIndexIn} uses and the machine arbitrates.
     */
    private long redeemerIndexIn(Transaction tx, String scriptHash, String what) {
        List<Withdrawal> withdrawals = tx.getBody().getWithdrawals();
        int rewardIndex = -1;
        for (int i = 0; withdrawals != null && i < withdrawals.size(); i++) {
            if (rewardAddressHolds(withdrawals.get(i).getRewardAddress(), scriptHash)) {
                if (rewardIndex >= 0) {
                    throw new IllegalStateException("two withdrawals at the same script " + scriptHash);
                }
                rewardIndex = i;
            }
        }
        if (rewardIndex < 0) {
            throw new IllegalStateException("no withdrawal at " + what + " script " + scriptHash);
        }
        int precedingGroups = (int) tx.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == RedeemerTag.Mint || r.getTag() == RedeemerTag.Spend)
                .count();
        return precedingGroups + rewardIndex;
    }

    private Utxo resolve(TransactionInput input) {
        return utxoSupplier.getTxOutput(input.getTransactionId(), input.getIndex())
                .orElseThrow(() -> new IllegalStateException("cannot resolve input " + input));
    }

    /** The outputs at the asset-manager spend credential, in body order. */
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

    private List<TransactionOutput> outputsAtCredential(Transaction tx, String credentialHash) {
        List<TransactionOutput> filtered = new ArrayList<>();
        for (TransactionOutput output : tx.getBody().getOutputs()) {
            if (credentialHash.equals(paymentCredentialOf(output.getAddress()))) {
                filtered.add(output);
            }
        }
        return filtered;
    }

    // ---- structural re-derivation from the finished body ------------------------------------------

    private void assertStructure(Transaction transaction, Request request, Numbers numbers, Indexes indexes) {
        // The bond echo is where the claim redeemer says, and it is byte-identical to the input.
        structural(indexes.lenderBondOutputIndex() == locateBondOutput(transaction, request),
                "lenderBondOutputIndex " + indexes.lenderBondOutputIndex()
                        + " no longer points at the bond echo");
        TransactionOutput echo = transaction.getBody().getOutputs()
                .get((int) indexes.lenderBondOutputIndex());
        structural(request.bondUtxo().getInlineDatum().equalsIgnoreCase(echo.getInlineDatum().serializeToHex()),
                "the bond echo datum is not byte-identical to the bond input datum");
        structural(echo.getValue().getCoin().equals(lovelaceOf(request.bondUtxo())),
                "the bond echo lovelace is not byte-identical to the bond input");

        // Exactly one asset-manager output — the borrower compensation, at filtered slot 0.
        List<TransactionOutput> assetOutputs = assetManagerOutputs(transaction);
        structural(assetOutputs.size() == 1,
                "expected exactly one asset-manager output, got " + assetOutputs.size());
        TransactionOutput borrowerOutput = assetOutputs.get(0);
        AssetType collateral = request.loan().datum().collateral().assetType();
        structural(quantityOf(borrowerOutput, collateral).compareTo(numbers.equity()) >= 0,
                "the borrower compensation output holds less collateral than the equity");
        structural(flattenedCount(borrowerOutput) == 2,
                "the borrower compensation output must be a token plus its min-ada rider (flatten == 2)");

        // The bond and pool sit at index 0 of their smart-credential-filtered input lists.
        structural(indexes.lenderBondFilteredInputIndex() == filteredInputIndex(transaction,
                        registry.getLenderManagerSpendScriptHash(), inputOf(request.bondUtxo()), "lender bond"),
                "lenderBondFilteredInputIndex " + indexes.lenderBondFilteredInputIndex()
                        + " no longer points at the bond input");
        structural(indexes.poolFilteredInputIndex() == filteredInputIndex(transaction,
                        registry.getPoolSpendScriptHash(), inputOf(request.poolUtxo()), "pool"),
                "poolFilteredInputIndex " + indexes.poolFilteredInputIndex()
                        + " no longer points at the pool input");

        // The pm_compound_liquidity redeemer's two self.redeemers indexes.
        structural(indexes.poolWithdrawRedeemerIndex() == redeemerIndexIn(transaction,
                        registry.getPoolPolicyId(), "pool.pool"),
                "poolWithdrawRedeemerIndex " + indexes.poolWithdrawRedeemerIndex()
                        + " no longer points at the pool.pool withdraw redeemer");
        structural(indexes.lenderManagerWithdrawRedeemerIndex() == redeemerIndexIn(transaction,
                        registry.getLenderManagerWithdrawScriptHash(), "lender_manager.lenderManager"),
                "lenderManagerWithdrawRedeemerIndex " + indexes.lenderManagerWithdrawRedeemerIndex()
                        + " no longer points at the lender_manager.lenderManager withdraw redeemer");

        // The compounded pool output: exactly one, at the pool credential, input value + converted lovelace,
        // datum and address byte-identical.
        List<TransactionOutput> poolOutputs = outputsAtCredential(transaction,
                registry.getPoolSpendScriptHash());
        structural(poolOutputs.size() == 1,
                "expected exactly one pool output, got " + poolOutputs.size());
        TransactionOutput poolOutput = poolOutputs.get(0);
        structural(poolOutput.getValue().getCoin().equals(
                        lovelaceOf(request.poolUtxo()).add(numbers.convertedLoanCollateralToPrincipalAmount())),
                "the pool output lovelace is not the input lovelace plus the converted amount");
        structural(request.poolUtxo().getInlineDatum()
                        .equalsIgnoreCase(poolOutput.getInlineDatum().serializeToHex()),
                "the pool output datum is not byte-identical to the pool input datum");
        structural(request.poolUtxo().getAddress().equals(poolOutput.getAddress()),
                "the pool output address is not identical to the pool input address");
        AssetType poolNft = new AssetType(registry.getPoolPolicyId(), request.poolIdHex());
        structural(quantityOf(poolOutput, poolNft).equals(BigInteger.ONE),
                "the pool output does not carry exactly one pool NFT");

        // The pool-manager output: exactly one, byte-identical to the input (value, datum, address).
        List<TransactionOutput> pmOutputs = outputsAtCredential(transaction,
                registry.getPoolManagerSpendScriptHash());
        structural(pmOutputs.size() == 1,
                "expected exactly one pool-manager output, got " + pmOutputs.size());
        TransactionOutput pmOutput = pmOutputs.get(0);
        structural(pmOutput.getValue().getCoin().equals(lovelaceOf(request.poolManagerUtxo())),
                "the pool-manager output lovelace is not byte-identical to the input");
        structural(request.poolManagerUtxo().getInlineDatum()
                        .equalsIgnoreCase(pmOutput.getInlineDatum().serializeToHex()),
                "the pool-manager output datum is not byte-identical to the input datum");
        structural(request.poolManagerUtxo().getAddress().equals(pmOutput.getAddress()),
                "the pool-manager output address is not identical to the input address");

        // The loan NFT is burned exactly once.
        structural(mintedQuantity(transaction, new AssetType(registry.getLoanPolicyId(),
                        request.loan().loanId())).equals(BigInteger.ONE.negate()),
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

    private static BigInteger lovelaceOf(Utxo utxo) {
        return utxo.getAmount().stream()
                .filter(amount -> "lovelace".equals(amount.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static String paymentCredentialOf(String address) {
        return new Address(address).getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse("");
    }

    /** Whether a bech32 reward address carries {@code scriptHash} as its credential (tail of the bytes). */
    private static boolean rewardAddressHolds(String rewardAddressBech32, String scriptHash) {
        String bytes = HexUtil.encodeHexString(new Address(rewardAddressBech32).getBytes());
        return bytes.toLowerCase().endsWith(scriptHash.toLowerCase());
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
