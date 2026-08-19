package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;

import java.math.BigInteger;
import java.util.List;

/**
 * Encodes the datum and redeemers of the T-016 S1 <b>pool-creation</b> transaction (and the pool
 * actions the later slices will need) as {@code PlutusData}, in the exact discipline of
 * {@link RequestTxEncoder}: hand-written, every primitive delegated to
 * {@link RequestTxEncoder}'s {@code public static} helpers rather than duplicated, constructor
 * indices and field order pinned against the schema oracle {@code loans-v4-alltypes.plutus.json}
 * ({@link PoolTxEncoderSchemaTest}), and pinned again against transcribed golden hex
 * ({@link PoolTxEncoderTest}) so a change that still satisfies the schema pins is still caught.
 *
 * <h2>Why this lives in {@code src/test}</h2>
 * The node never creates a pool, borrows from one, or cancels one — it indexes pools and liquidates
 * loans. Nothing in {@code src/main} builds a {@code PoolDatum}, and this encoder deliberately stays
 * in test scope so nothing starts to. The only {@code src/main} change of this slice is the six
 * script accessors on {@link com.fluidtokens.aquarium.offchain.service.LoansContractRegistry}.
 *
 * <h2>HIGH-STAKES model binding</h2>
 * Every field below is written positionally into a datum the deployed {@code pool.ak} — and the
 * pool action validators — compare with {@code builtin.equals_data}. {@code pool.pool}'s
 * {@code mint} handler does <b>not</b> validate the pool datum ({@link PoolCreateDryEvalTest},
 * consequence B), so no green dry-evaluation in this slice arbitrates a single {@code PoolDatum}
 * field: the schema pins and the independently derived goldens are the only defences until a
 * Borrow or Cancel spends the pool.
 *
 * <h2>The pool commits to the whole bond datum by hash</h2>
 * {@code lenderBondInlineDatumHash} is {@code blake2b_256(serialise_data(bondDatum))} of the
 * {@code LenderManagerDatum} the pool will stamp onto every lender bond it originates. The fee the
 * bot later collects lives in that bond datum's {@code liquidationFeePerMille}, so the bond bytes
 * are load-bearing twice — inside this hash field and, later, as the lender-bond output's own
 * datum. Both must be produced by the one function, {@code LoanFixtures.encode(LenderManagerDatum)};
 * see {@link PoolTxEncoderTest} for the chain-anchored proof that it reproduces the deployed pool's
 * committed hash.
 *
 * <h2>Duplicated primitives</h2>
 * The private {@code constr}/{@code bytes} helpers below duplicate the ones inside
 * {@link RequestTxEncoder} and {@code LoanFixtures}. That duplication is the same one those two
 * classes already carry, sanctioned for the same reason: widening a heavily contended file's
 * visibility to share them buys nothing.
 */
public final class PoolTxEncoder {

    private PoolTxEncoder() {
    }

    // ---- PoolWithdrawRedeemer action alternatives ------------------------------------------------
    //
    // fluidtokens/types/pool/Action — the enum pool.pool's withdraw handler switches on to pick which
    // action withdraw script must be present. Pinned by PoolTxEncoderSchemaTest.

    /** {@code Action.Cancel} — constructor 0. */
    public static final int ACTION_CANCEL = 0;
    /** {@code Action.Borrow} — constructor 1. */
    public static final int ACTION_BORROW = 1;
    /** {@code Action.SellLenderPosition} — constructor 2. */
    public static final int ACTION_SELL_LENDER_POSITION = 2;
    /** {@code Action.Compound} — constructor 3. */
    public static final int ACTION_COMPOUND = 3;

    // ---- PoolManagerAction alternatives ----------------------------------------------------------
    //
    // fluidtokens/types/pool_manager/PoolManagerAction — the enum pool_manager.poolManager's withdraw
    // handler switches on to pick which pool-manager action withdraw script must be present. A separate
    // enum from pool.ak's Action above, with its own numbering; pinned by PoolTxEncoderSchemaTest.

    /** {@code PoolManagerAction.CancelPoolManager} — constructor 0. */
    public static final int PM_ACTION_CANCEL_POOL_MANAGER = 0;
    /** {@code PoolManagerAction.UpdatePoolManager} — constructor 1. */
    public static final int PM_ACTION_UPDATE_POOL_MANAGER = 1;
    /** {@code PoolManagerAction.CompoundLiquidity} — constructor 2. */
    public static final int PM_ACTION_COMPOUND_LIQUIDITY = 2;

    // ---- test-scope models -----------------------------------------------------------------------

    /**
     * {@code PoolDatum} from {@code lib/fluidtokens/types/pool.ak} — constructor 0, 10 fields, in the
     * order pinned by {@link PoolTxEncoderSchemaTest}.
     *
     * @param permissionedConditionScriptHash hex; {@code "NONE"} (as hex, {@code "4e4f4e45"}) for a
     *                                        permissionless pool
     * @param extraData                       opaque {@code Data}; nothing on chain reads it at mint
     * @param commonData                      the SAME {@code CommonData} type {@link RequestTxEncoder}
     *                                        encodes ({@code request.ak} imports it from {@code pool.ak})
     * @param lenderBondInlineDatumHash       hex of {@code blake2b_256(serialise_data(bondDatum))}
     */
    public record PoolDatum(String permissionedConditionScriptHash,
                            PlutusData extraData,
                            RequestTxEncoder.CommonData commonData,
                            AuthorizationMethod lenderAuth,
                            Address lenderBondAddress,
                            String lenderBondInlineDatumHash,
                            List<CollateralAsset> collateralOptions,
                            List<BigInteger> minCollateral,
                            List<BigInteger> minCollateralDivider,
                            boolean dynamicCollateralPrice) {
    }

    /**
     * {@code BorrowData} from {@code lib/fluidtokens/types/pool.ak} — constructor 0, 9 fields.
     * <p>
     * The five index fields are {@code long} because several of them ({@code principalOracleRefInputIndex},
     * {@code chosenCollateralOracleRefInputIndex}) carry {@code -1} on chain when no oracle is used, and
     * the CBOR must be able to encode a negative integer for them.
     *
     * @param poolIdHex the 29-byte pool NFT asset name ({@code 0x00} index prefix + 28-byte hash)
     */
    public record BorrowData(Address borrowerAddress,
                             long outputWithLenderTokenIndex,
                             long outputWithBorrowerTokenIndex,
                             long principalOracleRefInputIndex,
                             long chosenCollateralIndex,
                             long chosenCollateralOracleRefInputIndex,
                             BigInteger wantedPrincipalAmount,
                             String poolIdHex,
                             long permissionedConditionWithdrawIndex) {
    }

    // ---- PoolDatum -------------------------------------------------------------------------------

    /**
     * {@code PoolDatum { permissionedConditionScriptHash, extraData, commonData, lenderAuth,
     * lenderBondAddress, lenderBondInlineDatumHash, collateralOptions, minCollateral,
     * minCollateralDivider, dynamicCollateralPrice } } — constructor 0.
     */
    public static PlutusData poolDatum(PoolDatum d) {
        return constr(0,
                bytes(d.permissionedConditionScriptHash()),
                d.extraData(),
                RequestTxEncoder.commonData(d.commonData()),
                RequestTxEncoder.authorizationMethod(d.lenderAuth()),
                RequestTxEncoder.address(d.lenderBondAddress()),
                bytes(d.lenderBondInlineDatumHash()),
                list(d.collateralOptions().stream().map(RequestTxEncoder::collateralAsset).toList()),
                intList(d.minCollateral()),
                intList(d.minCollateralDivider()),
                bool(d.dynamicCollateralPrice()));
    }

    // ---- PoolMintRedeemer ------------------------------------------------------------------------

    /**
     * {@code PoolMintRedeemer { configRefInputIndex, inputRef } } — constructor 0. {@code inputRef} is
     * the seed: the UTxO the transaction must spend, which {@code check_mint}'s {@code isInputRefSpent}
     * looks for with {@code find_input}.
     */
    public static PlutusData poolMintRedeemer(long configRefInputIndex, TransactionInput inputRef) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex),
                RequestTxEncoder.outputReference(inputRef));
    }

    // ---- PoolWithdrawRedeemer --------------------------------------------------------------------

    /**
     * {@code PoolWithdrawRedeemer { configRefInputIndex, action } } — constructor 0, where
     * {@code action} is the fieldless {@code Action} enum at {@code actionAlternative} (one of the
     * {@code ACTION_*} constants). This is the redeemer the pool's {@code withdraw} handler reads to
     * pick which action withdraw script the transaction must carry.
     */
    public static PlutusData poolWithdrawRedeemer(long configRefInputIndex, int actionAlternative) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex), constr(actionAlternative));
    }

    // ---- BorrowData / pool_borrow_action ---------------------------------------------------------

    /**
     * {@code BorrowData { borrowerAddress, outputWithLenderTokenIndex, outputWithBorrowerTokenIndex,
     * principalOracleRefInputIndex, chosenCollateralIndex, chosenCollateralOracleRefInputIndex,
     * wantedPrincipalAmount, poolId, permissionedConditionWithdrawIndex } } — constructor 0.
     */
    public static PlutusData borrowData(BorrowData d) {
        return constr(0,
                RequestTxEncoder.address(d.borrowerAddress()),
                BigIntPlutusData.of(d.outputWithLenderTokenIndex()),
                BigIntPlutusData.of(d.outputWithBorrowerTokenIndex()),
                BigIntPlutusData.of(d.principalOracleRefInputIndex()),
                BigIntPlutusData.of(d.chosenCollateralIndex()),
                BigIntPlutusData.of(d.chosenCollateralOracleRefInputIndex()),
                BigIntPlutusData.of(d.wantedPrincipalAmount()),
                bytes(d.poolIdHex()),
                BigIntPlutusData.of(d.permissionedConditionWithdrawIndex()));
    }

    /**
     * {@code PoolBorrowActionWithdrawRedeemer { configRefInputIndex, actionsForEachInput } } —
     * constructor 0. {@code actionsForEachInput} is a {@code List<BorrowData>}, one per pool input.
     */
    public static PlutusData poolBorrowActionWithdrawRedeemer(long configRefInputIndex,
                                                              List<BorrowData> actionsForEachInput) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex),
                list(actionsForEachInput.stream().map(PoolTxEncoder::borrowData).toList()));
    }

    // ---- CancelData / pool_cancel_action ---------------------------------------------------------

    /** {@code CancelData { poolId } } — constructor 0. {@code poolId} is the 29-byte pool NFT name. */
    public static PlutusData cancelData(String poolIdHex) {
        return constr(0, bytes(poolIdHex));
    }

    /**
     * {@code PoolCancelActionWithdrawRedeemer { configRefInputIndex, actionsForEachInput } } —
     * constructor 0. {@code actionsForEachInput} is a {@code List<CancelData>}, one per pool input.
     */
    public static PlutusData poolCancelActionWithdrawRedeemer(long configRefInputIndex,
                                                             List<String> poolIdHexes) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex),
                list(poolIdHexes.stream().map(PoolTxEncoder::cancelData).toList()));
    }

    // ---- PoolManagerDatum / pool_manager.poolManager ---------------------------------------------

    /**
     * {@code PoolManagerDatum} from {@code lib/fluidtokens/types/pool_manager.ak} — constructor 0, two
     * fields, in the order pinned by {@link PoolTxEncoderSchemaTest}.
     *
     * <h2>{@code compoudingFeePerMille} is spelled that way upstream — do not "fix" it</h2>
     * The typo is FluidTokens', in {@code lib/fluidtokens/types/pool_manager.ak} at pin {@code ff005fb},
     * and the component name here mirrors it deliberately so the two can be matched by eye against the
     * schema oracle. Nothing in the CBOR carries a field name — a {@code Constr} is positional — so the
     * spelling is a readability contract with the upstream source, never a wire contract.
     *
     * <h2>This datum IS validated at mint, unlike {@code PoolDatum}</h2>
     * {@code pool_manager.ak}'s {@code check_mint} does {@code expect PoolManagerDatum { .. } =
     * outputDatum} on the PoolManager output, so an ill-typed datum aborts the mint. That is the
     * opposite of {@code pool.pool}, whose mint never reads the {@code PoolDatum} at all
     * ({@link PoolCreateDryEvalTest}, consequence B) — so here dry-evaluation really does arbitrate the
     * datum's shape, and {@link PoolCreateDryEvalTest} proves it with an ill-typed mutant.
     *
     * @param poolOwnerAuth          who may cancel or edit the pool through this PoolManager. Read by
     *                               {@code pm_cancel_pool_manager}'s {@code authorize_action}
     * @param compoudingFeePerMille  the compounding bot's cut of compounded principal, per mille. It is
     *                               <b>not</b> the liquidation fee — that one lives in the lender bond's
     *                               {@code LenderManagerDatum.liquidationFeePerMille} — and nothing on
     *                               the create or cancel path reads this field
     */
    public record PoolManagerDatum(AuthorizationMethod poolOwnerAuth,
                                   BigInteger compoudingFeePerMille) {
    }

    /** {@code PoolManagerDatum { poolOwnerAuth, compoudingFeePerMille } } — constructor 0. */
    public static PlutusData poolManagerDatum(PoolManagerDatum d) {
        return constr(0,
                RequestTxEncoder.authorizationMethod(d.poolOwnerAuth()),
                BigIntPlutusData.of(d.compoudingFeePerMille()));
    }

    /**
     * {@code PoolManagerMintRedeemer { configRefInputIndex, poolWithdrawRedeemerIndex } } —
     * constructor 0.
     * <p>
     * {@code poolWithdrawRedeemerIndex} indexes {@code self.redeemers} — the whole
     * {@code Pairs<ScriptPurpose, Redeemer>} list, not the withdrawals — and must point at the
     * {@code pool.pool} withdraw entry. {@code pool_manager.ak}'s {@code check_mint} reads it
     * <b>only</b> inside {@code if length(poolManagerBurntNFTs) > 0}, so on a pure mint (a pool
     * creation) it is never evaluated; on a burn (a pool cancel) it is load-bearing. Either way it must
     * be derived from the finished body, never assumed — see
     * {@link PoolCancelTransactionBuilder#poolWithdrawRedeemerIndexIn}, the single derivation both
     * builders use.
     */
    public static PlutusData poolManagerMintRedeemer(long configRefInputIndex,
                                                     long poolWithdrawRedeemerIndex) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex),
                BigIntPlutusData.of(poolWithdrawRedeemerIndex));
    }

    /**
     * {@code PoolManagerWithdrawRedeemer { configRefInputIndex, action } } — constructor 0, where
     * {@code action} is the fieldless {@code PoolManagerAction} at {@code actionAlternative} (one of the
     * {@code PM_ACTION_*} constants). This is the redeemer {@code pool_manager.poolManager}'s
     * {@code withdraw} handler reads to pick which pool-manager action withdraw script must be present.
     */
    public static PlutusData poolManagerWithdrawRedeemer(long configRefInputIndex,
                                                         int actionAlternative) {
        return constr(0, BigIntPlutusData.of(configRefInputIndex), constr(actionAlternative));
    }

    /**
     * {@code CancelPoolManagerActionWithdrawRedeemer { configRefInputIndex, poolWithdrawRedeemerIndex,
     * poolManagerNFTAssetNames } } — constructor 0.
     * <p>
     * {@code poolManagerNFTAssetNames} is positionally aligned with the PoolManager <em>inputs</em>:
     * {@code pm_cancel_pool_manager} walks them with {@code indexed_all} and reads
     * {@code safe_list_at(redeemer.poolManagerNFTAssetNames, index)}. Its second field is a second,
     * independent copy of the same {@code self.redeemers} index the mint redeemer above carries; both
     * are derived from the finished body separately, never copied from one another.
     */
    public static PlutusData cancelPoolManagerActionWithdrawRedeemer(long configRefInputIndex,
                                                                     long poolWithdrawRedeemerIndex,
                                                                     List<String> poolManagerNftNameHexes) {
        return constr(0,
                BigIntPlutusData.of(configRefInputIndex),
                BigIntPlutusData.of(poolWithdrawRedeemerIndex),
                list(poolManagerNftNameHexes.stream().map(PoolTxEncoder::bytes).toList()));
    }

    // ---- BondRedeemer ----------------------------------------------------------------------------

    /**
     * {@code BondRedeemer { inputRefs } } — constructor 0. {@code inputRefs} is a
     * {@code List<OutputReference>}, positionally aligned with the minted bond tokens:
     * {@code bond.ak}'s {@code mint} names each token after {@code hash_output_ref(inputRefs[index])}.
     */
    public static PlutusData bondRedeemer(List<TransactionInput> inputRefs) {
        return constr(0, list(inputRefs.stream().map(RequestTxEncoder::outputReference).toList()));
    }

    // ---- asset names -----------------------------------------------------------------------------

    /**
     * The pool NFT asset name: <b>{@code index-byte ‖ blake2b_224(serialise_data(seed))}</b>, 29 bytes
     * for the single-token case, exactly as {@code pool.ak}'s {@code check_mint} demands
     * ({@code bytearray.at(assetName, 0) == index}, {@code bytearray.drop(assetName, 1) == inputRefHash}).
     * <p>
     * The 28-byte tail is {@link RequestFixtures#hashOutputRef(TransactionInput)} — the very same hash
     * of the very same {@code OutputReference} shape the request NFT uses, only here prefixed by the
     * token index.
     */
    public static String poolAssetName(int index, TransactionInput seed) {
        return String.format("%02x", index) + RequestFixtures.hashOutputRef(seed);
    }

    /**
     * The lender-/borrower-bond NFT asset name: <b>{@code blake2b_224(serialise_data(poolRef))}</b>,
     * 28 bytes and <b>no</b> index prefix — {@code bond.ak}'s {@code mint} checks
     * {@code assetName == hash_output_ref(inputRef)} with no {@code at}/{@code drop}. {@code poolRef}
     * is the output reference spent to originate the loan (the pool UTxO's own reference).
     */
    public static String bondAssetName(TransactionInput poolRef) {
        return RequestFixtures.hashOutputRef(poolRef);
    }

    // ---- primitives ------------------------------------------------------------------------------
    //
    // Deliberately duplicated from RequestTxEncoder / LoanFixtures rather than shared — see the class
    // javadoc.

    private static PlutusData bool(boolean b) {
        return constr(b ? 1 : 0);
    }

    private static PlutusData bytes(String hex) {
        return BytesPlutusData.of(HexUtil.decodeHexString(hex));
    }

    private static ListPlutusData list(List<PlutusData> items) {
        return ListPlutusData.of(items.toArray(PlutusData[]::new));
    }

    private static ListPlutusData intList(List<BigInteger> items) {
        return list(items.stream().map(v -> (PlutusData) BigIntPlutusData.of(v)).toList());
    }

    private static ConstrPlutusData constr(int alternative, PlutusData... fields) {
        return ConstrPlutusData.builder()
                .alternative(alternative)
                .data(ListPlutusData.of(fields))
                .build();
    }
}
