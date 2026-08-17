package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.OracleSignature;

import java.util.List;

/**
 * Encodes the redeemers and output datums of the T-008 {@code Liquidate} transaction as
 * {@code PlutusData}, in the {@link OracleFeedConverter} discipline: hand-written, constructor
 * indices and field order asserted against the schema oracle
 * {@code loans-v4-alltypes.plutus.json} ({@code LiquidationTxEncoderSchemaTest}), and pinned
 * against transcribed golden hex ({@code LiquidationTxEncoderTest}) so a change to the encoder
 * that happens to still satisfy the schema pins is still caught.
 * <p>
 * Encode only — this class never reads a redeemer off chain, it only builds the ones the
 * liquidation bot submits. Index resolution (which reference input a config or oracle feed
 * actually lands at in the finished transaction) is not this class's job; every {@code *Index}
 * parameter below is taken as given.
 */
public final class LiquidationTxEncoder {

    private LiquidationTxEncoder() {
    }

    /**
     * ASCII {@code "claimed_collateral"} — the asset-manager action for the collateral an
     * unaffected liquidation claims outright. Extracted from the deployed {@code compiledCode}:
     * {@code asset_manager.ak} actions are opaque {@code ByteArray}s, not an enum with a schema,
     * so there is nothing here for {@code LiquidationTxEncoderSchemaTest} to pin.
     * <p>
     * <b>Arbitrated.</b> {@code LiquidateDryEvalTest} runs a real {@code Liquidate} through the
     * PlutusV3 machine and {@code lm_liquidate_action}'s {@code validate_repayment_output} accepts
     * the datum, which it does by {@code builtin.equals_data} against a datum carrying
     * {@code constants.action_claimed_collateral} — so this is the exact byte string.
     */
    public static final String CLAIMED_COLLATERAL_ACTION_HEX = "636c61696d65645f636f6c6c61746572616c";

    /**
     * ASCII {@code "partial_liquidation"} — the asset-manager action for the compensation a
     * lender owes back on a partial liquidation. Same provenance as
     * {@link #CLAIMED_COLLATERAL_ACTION_HEX}.
     * <p>
     * <b>Arbitrated, indirectly.</b> No output layout this builder emits carries a positive equity
     * through both validators (see
     * {@code LiquidateDryEvalTest#positiveEquityIsRefusedInBothLayoutsThisBuilderCanEmit}), so it
     * cannot be arbitrated by a passing transaction. It is arbitrated by which validator
     * refuses: with the compensation output in the slot {@code loan_claim_action} reads,
     * {@code loan_claim_action} — whose {@code equity_sent_to_borrower} compares the datum against
     * one carrying {@code constants.action_partial_liquidation_compensation} — accepts it, and the
     * refusal moves to {@code lm_liquidate_action}.
     */
    public static final String PARTIAL_LIQUIDATION_ACTION_HEX = "7061727469616c5f6c69717569646174696f6e";

    // ---- LoanClaimActionWithdrawRedeemer / ClaimData ---------------------------------------

    /** {@code LoanClaimActionWithdrawRedeemer { configRefInputIndex, actionsForEachInput } } — constructor index 0. */
    public static PlutusData loanClaimActionWithdrawRedeemer(long configRefInputIndex,
            List<ClaimData> actionsForEachInput) {
        var items = actionsForEachInput.stream()
                .map(LiquidationTxEncoder::claimData)
                .toArray(PlutusData[]::new);
        return constr(0, BigIntPlutusData.of(configRefInputIndex), list(items));
    }

    /**
     * {@code ClaimData { liquidationMode, lenderBondOutputIndex, collateralOracleRefInputIndex,
     * principalOracleRefInputIndex, lenderAuth, equity, loanId, remainingDebt } } — constructor
     * index 0.
     */
    public static PlutusData claimData(ClaimData claim) {
        return constr(0,
                liquidationMode(claim.liquidationMode()),
                BigIntPlutusData.of(claim.lenderBondOutputIndex()),
                BigIntPlutusData.of(claim.collateralOracleRefInputIndex()),
                BigIntPlutusData.of(claim.principalOracleRefInputIndex()),
                authorizationMethod(claim.lenderAuth()),
                BigIntPlutusData.of(claim.equity()),
                BytesPlutusData.of(HexUtil.decodeHexString(claim.loanId())),
                BigIntPlutusData.of(claim.remainingDebt()));
    }

    /**
     * {@code LiquidationMode} sub-encoder — constructor indices 0/1/2, mirroring the decode side
     * in {@code LoanDatumConverter}.
     */
    public static PlutusData liquidationMode(LiquidationMode mode) {
        return switch (mode) {
            case LiquidationMode.NoLiquidationFullCollateralClaim ignored -> constr(0);
            case LiquidationMode.NoLiquidationDutchAuctionClaim ignored -> constr(1);
            case LiquidationMode.Liquidation l -> constr(2,
                    BigIntPlutusData.of(l.ltv()),
                    BigIntPlutusData.of(l.ltvDivider()),
                    BigIntPlutusData.of(l.partialLiquidationPenaltyPerMille()),
                    bool(l.equityInPrincipalCurrency()));
        };
    }

    /**
     * {@code AuthorizationMethod} sub-encoder — constructor indices 0..3 in order
     * CardanoSignature / CardanoSpendScript / CardanoWithdrawScript / CardanoMintScript, all
     * {@code { hash: ByteArray } }.
     */
    public static PlutusData authorizationMethod(AuthorizationMethod auth) {
        return switch (auth) {
            case AuthorizationMethod.CardanoSignature a -> constr(0, hashBytes(a.hash()));
            case AuthorizationMethod.CardanoSpendScript a -> constr(1, hashBytes(a.hash()));
            case AuthorizationMethod.CardanoWithdrawScript a -> constr(2, hashBytes(a.hash()));
            case AuthorizationMethod.CardanoMintScript a -> constr(3, hashBytes(a.hash()));
        };
    }

    private static PlutusData hashBytes(String hashHex) {
        return BytesPlutusData.of(HexUtil.decodeHexString(hashHex));
    }

    // ---- LMLiquidateWithdrawRedeemer ---------------------------------------------------------

    /**
     * {@code LMLiquidateWithdrawRedeemer { configRefInputIndex, lenderBondInputIndexes,
     * lenderBondAssetNames, assetOutputIndexes } } — constructor index 0.
     * <p>
     * {@code assetOutputIndexes} is the fourth and last field, one entry per loan input in
     * loan-input order, and each entry indexes the outputs <em>filtered</em> by the asset-manager
     * spend credential rather than the transaction body. Resolving it is
     * {@code LiquidateTransactionBuilder}'s job, not this class's — see this class's javadoc on
     * every {@code *Index} parameter being taken as given.
     */
    public static PlutusData lmLiquidateWithdrawRedeemer(long configRefInputIndex,
            List<Long> lenderBondInputIndexes, List<String> lenderBondAssetNamesHex,
            List<Long> assetOutputIndexes) {
        var indexes = lenderBondInputIndexes.stream()
                .map(BigIntPlutusData::of)
                .toArray(PlutusData[]::new);
        var names = lenderBondAssetNamesHex.stream()
                .map(LiquidationTxEncoder::hashBytes)
                .toArray(PlutusData[]::new);
        var assetOutputs = assetOutputIndexes.stream()
                .map(BigIntPlutusData::of)
                .toArray(PlutusData[]::new);
        return constr(0, BigIntPlutusData.of(configRefInputIndex), list(indexes), list(names),
                list(assetOutputs));
    }

    // ---- LoanWithdrawRedeemer (action fixed to Claim) ----------------------------------------

    /** {@code LoanWithdrawRedeemer { configRefInputIndex, action: Claim } } — {@code Action.Claim} is constructor index 0. */
    public static PlutusData loanWithdrawRedeemer(long configRefInputIndex) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex), constr(0));
    }

    // ---- LenderManagerWithdrawRedeemer (action fixed to Liquidate) ----------------------------

    /**
     * {@code LenderManagerWithdrawRedeemer { configRefInputIndex, action: Liquidate } } —
     * {@code LenderManagerAction.Liquidate} is constructor index 1 ({@code WithdrawBonds} is 0).
     */
    public static PlutusData lenderManagerWithdrawRedeemer(long configRefInputIndex) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex), constr(1));
    }

    // ---- LoanMintRedeemer ----------------------------------------------------------------------

    /** {@code LoanMintRedeemer { configRefInputIndex, isPoolOrigin, originWithdrawRedeemerIndex } } — constructor index 0. */
    public static PlutusData loanMintRedeemer(long configRefInputIndex, boolean isPoolOrigin,
            long originWithdrawRedeemerIndex) {
        return constr(0,
                BigIntPlutusData.of(configRefInputIndex),
                bool(isPoolOrigin),
                BigIntPlutusData.of(originWithdrawRedeemerIndex));
    }

    // ---- OracleRedeemer --------------------------------------------------------------------------

    /**
     * {@code OracleRedeemer { data: OraclePriceFeed, signatures: List<Signature> } } — constructor
     * index 0, wrapping {@link OracleFeedConverter#toPlutusData(OraclePriceFeed)} for the signed
     * variants (Aggregated / Dedicated).
     */
    public static PlutusData oracleRedeemer(OraclePriceFeed feed, List<OracleSignature> signatures) {
        return oracleRedeemer(OracleFeedConverter.toPlutusData(feed), signatures);
    }

    /**
     * As above, wrapping {@link OracleFeedConverter#toPlutusData(OraclePriceFeed, long)} for a
     * {@code PriceDataCharlie} feed, which needs a {@code providerRefInputIndex} and is unsigned
     * (an empty {@code signatures} list is the normal case — see that converter's javadoc).
     */
    public static PlutusData oracleRedeemer(OraclePriceFeed feed, long providerRefInputIndex,
            List<OracleSignature> signatures) {
        return oracleRedeemer(OracleFeedConverter.toPlutusData(feed, providerRefInputIndex), signatures);
    }

    private static PlutusData oracleRedeemer(PlutusData feedData, List<OracleSignature> signatures) {
        var sigs = signatures.stream()
                .map(LiquidationTxEncoder::signature)
                .toArray(PlutusData[]::new);
        return constr(0, feedData, list(sigs));
    }

    /**
     * {@code Signature { signature: ByteArray, key_position: Int } } — signature BEFORE
     * key_position, the opposite of {@link OracleSignature}'s own field order.
     */
    private static PlutusData signature(OracleSignature sig) {
        return constr(0, hashBytes(sig.signatureHex()), BigIntPlutusData.of(sig.keyPosition()));
    }

    // ---- AssetManagerDatumWithToken --------------------------------------------------------------

    /**
     * {@code AssetManagerDatumWithToken { inputOutputReference, action, data, ownerAsset } } —
     * constructor index 0 of {@code AssetManagerDatum}. {@code data} is always the Aiken
     * {@code None} this ticket needs, encoded directly as {@code Constr 1 []} rather than through
     * a modelled {@code Option} type.
     */
    public static PlutusData assetManagerDatumWithToken(AssetManagerDatumWithToken datum) {
        if (datum.ownerAsset().isAda()) {
            throw new IllegalArgumentException(
                    "ownerAsset must not be ada — an ada owner asset can never gate a UTxO and would "
                            + "encode an unspendable datum");
        }
        return constr(0,
                outputReference(datum.transactionId(), datum.outputIndex()),
                hashBytes(datum.action()),
                constr(1),
                asset(datum.ownerAsset()));
    }

    /**
     * {@code OutputReference { transaction_id: ByteArray, output_index: Int } } — constructor
     * index 0. {@code transaction_id} is a flat {@code ByteArray} (PlutusV3), not nested in
     * another constructor.
     */
    private static PlutusData outputReference(String transactionIdHex, int outputIndex) {
        if (transactionIdHex == null || transactionIdHex.length() != 64) {
            throw new IllegalArgumentException(
                    "transactionId must be exactly 64 hex chars (32 bytes), was: " + transactionIdHex);
        }
        if (outputIndex < 0) {
            throw new IllegalArgumentException("outputIndex must be >= 0, was: " + outputIndex);
        }
        return constr(0, hashBytes(transactionIdHex), BigIntPlutusData.of(outputIndex));
    }

    /** {@code Asset { policyId, assetName } } — constructor index 0, policy id first. */
    private static PlutusData asset(AssetType token) {
        return constr(0,
                BytesPlutusData.of(token.getPlutusDataPolicyId()),
                BytesPlutusData.of(token.getPlutusDataAssetName()));
    }

    // ---- primitives --------------------------------------------------------------------------------

    /** Aiken {@code Bool}: Constr 0 = False, Constr 1 = True. */
    private static PlutusData bool(boolean b) {
        return constr(b ? 1 : 0);
    }

    private static ConstrPlutusData constr(int alternative, PlutusData... fields) {
        return ConstrPlutusData.builder()
                .alternative(alternative)
                .data(ListPlutusData.of(fields))
                .build();
    }

    private static PlutusData list(PlutusData... items) {
        return ListPlutusData.of(items);
    }
}
