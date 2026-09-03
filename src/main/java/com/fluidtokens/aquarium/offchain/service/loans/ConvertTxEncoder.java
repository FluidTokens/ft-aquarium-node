package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Every byte {@code lm_liquidate_and_convert_action} dictates: the Minswap V2 order datum, the two
 * datum hashes it embeds, the lp asset name, and the action's own withdraw redeemer.
 *
 * <h2>⛔ Where these shapes came from, because guessing any one of them fails only on chain</h2>
 * The {@code amm_dex_v2} library is an <b>Aiken dependency, not a committed source file</b>, so it is
 * absent from the upstream clone. Every Minswap constant here was therefore taken from <b>mainnet</b>
 * and cross-checked against the upstream type declarations (findings §32.3, §34):
 * <ul>
 *   <li><b>{@code computeLpAssetName} is SHA3-256, twice</b> — not blake2b. Both were computed for
 *       ADA/FLDT and put to the chain: blake2b gave a 404, SHA3 gave the live LP token. A wrong hash
 *       here makes every order datum wrong and nothing catches it before phase 2.</li>
 *   <li><b>The pool NFT's asset name is the constant {@code 4d5350} — ASCII {@code MSP}</b> — the same
 *       for every pool. It is a different value from the computed lp asset name, under the same
 *       policy, and confusing the two is easy.</li>
 *   <li><b>{@code EODInlineDatum} is constructor 2</b> ({@code EODNoDatum}, {@code EODDatumHash},
 *       {@code EODInlineDatum}). Every ordinary swap on chain carries {@code EODNoDatum}, so the live
 *       sample could confirm the record's field ORDER but not this index; it comes from the upstream
 *       declaration.</li>
 *   <li><b>{@code SwapExactIn} is constructor 0</b> of eleven order steps, and
 *       {@code SAOSpecificAmount} is constructor 0 of two.</li>
 * </ul>
 * The nine-field {@code OrderDatum} order below was confirmed against a <b>real live order</b>, not
 * only against the declaration — the two agreeing is what makes it evidence rather than a reading.
 */
public final class ConvertTxEncoder {

    private ConvertTxEncoder() {
    }

    /**
     * {@code max_batcher_fee} — the literal {@code 700000} in the validator, not a Minswap default we
     * are free to track. A different figure fails {@code equals_data} on the order datum.
     */
    public static final BigInteger MAX_BATCHER_FEE = BigInteger.valueOf(700_000L);

    /** Every Minswap V2 pool NFT: policy = the pool policy, asset name = ASCII {@code MSP}. */
    public static final String POOL_NFT_ASSET_NAME = "4d5350";

    /** {@code constants.converted_to_liquidity_action}, as its UTF-8 bytes. */
    public static final String CONVERTED_TO_LIQUIDITY_ACTION = utf8Hex("converted_to_liquidity");

    /** {@code constants.action_claimed_collateral}, as its UTF-8 bytes. */
    public static final String CLAIMED_COLLATERAL_ACTION = utf8Hex("claimed_collateral");

    // ---- Minswap ------------------------------------------------------------------------------

    /**
     * {@code compute_lp_asset_name(aPolicy, aName, bPolicy, bName)} —
     * {@code sha3_256(sha3_256(aPolicy ‖ aName) ‖ sha3_256(bPolicy ‖ bName))}.
     *
     * <p>⚠ Order matters and is <b>not</b> sorted here: the caller passes the pool's own
     * {@code asset_a} then {@code asset_b}, read from its live datum. Sorting them ourselves would
     * silently disagree with a pool whose ordering we guessed wrong.
     */
    public static String computeLpAssetName(AssetType assetA, AssetType assetB) {
        byte[] a = sha3(concat(assetA));
        byte[] b = sha3(concat(assetB));
        byte[] both = new byte[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        return HexUtil.encodeHexString(sha3(both));
    }

    /**
     * {@code SwapExactIn { a_to_b_direction, swap_amount_option, minimum_receive, killable }} —
     * constructor 0 of {@code OrderStep}.
     *
     * <p>⚑ <b>{@code killable} is always {@code True} and {@code minimum_receive} is never ours to
     * choose:</b> the validator fixes it at {@code remainingDebt}. Together those mean a pool that
     * cannot deliver the debt <b>refunds</b> rather than filling badly — which is why this path needs
     * no slippage model (findings §25.2).
     */
    public static PlutusData swapExactIn(boolean aToBDirection, BigInteger swapAmount,
                                         BigInteger minimumReceive) {
        require(swapAmount != null && swapAmount.signum() > 0, "swapAmount must be positive");
        require(minimumReceive != null && minimumReceive.signum() > 0, "minimumReceive must be positive");
        return constr(0,
                bool(aToBDirection),
                constr(0, BigIntPlutusData.of(swapAmount)),   // SAOSpecificAmount
                BigIntPlutusData.of(minimumReceive),
                bool(true));                                  // killable
    }

    /**
     * The nine-field {@code OrderDatum}, in the declaration order a live mainnet order confirms.
     *
     * @param canceller       the LENDER's authorisation — the order is theirs to cancel, not the bot's
     * @param receiver        {@code get_smart_destination_address(...)}: both receivers are the same
     *                        asset-manager address, so the proceeds AND a refund land with the lender
     * @param successDatumHash blake2b-256 of the success {@code AssetManagerDatumWithToken}
     * @param refundDatumHash  blake2b-256 of the refund {@code AssetManagerDatumWithToken}
     * @param lpAsset         {@code Asset { minswapPoolPolicyId, computeLpAssetName(...) }}
     * @param step            {@link #swapExactIn}
     */
    public static PlutusData orderDatum(AuthorizationMethod canceller,
                                        PlutusData receiver,
                                        String successDatumHash,
                                        String refundDatumHash,
                                        AssetType lpAsset,
                                        PlutusData step) {
        return constr(0,
                orderAuthorizationMethod(canceller),
                receiver,
                extraOrderDatumInline(refundDatumHash),
                receiver,
                extraOrderDatumInline(successDatumHash),
                asset(lpAsset),
                step,
                BigIntPlutusData.of(MAX_BATCHER_FEE),
                constr(1));   // expiry_setting_opt: None
    }

    /**
     * {@code EODInlineDatum { hash }} — <b>constructor 2</b>, after {@code EODNoDatum} and
     * {@code EODDatumHash}. Getting this index wrong produces a datum that decodes to a different
     * variant and fails the validator's {@code equals_data} with nothing to point at.
     */
    public static PlutusData extraOrderDatumInline(String datumHashHex) {
        require(datumHashHex != null && datumHashHex.length() == 64,
                "a datum hash is 32 bytes / 64 hex chars, was: " + datumHashHex);
        return constr(2, BytesPlutusData.of(HexUtil.decodeHexString(datumHashHex)));
    }

    /** {@code OrderAuthorizationMethod}: signature 0, spend 1, withdraw 2, mint 3. */
    public static PlutusData orderAuthorizationMethod(AuthorizationMethod method) {
        return switch (method) {
            case AuthorizationMethod.CardanoSignature s -> constr(0, hash(s.hash()));
            case AuthorizationMethod.CardanoSpendScript s -> constr(1, hash(s.hash()));
            case AuthorizationMethod.CardanoWithdrawScript s -> constr(2, hash(s.hash()));
            case AuthorizationMethod.CardanoMintScript s -> constr(3, hash(s.hash()));
        };
    }

    /**
     * {@code get_smart_destination_address(isCIP113: False, paymentHash, …)} —
     * {@code Address(Script(paymentHash), None)}.
     *
     * <p>Only the non-CIP-113 branch is modelled, because it is the only one reachable for a pool
     * whose payment credential is Minswap's own script rather than the smart-tokens one. A CIP-113
     * pair would need {@code Address(Script(smartTokens), Some(Inline(Script(withdrawHash))))}, and
     * refusing is better than encoding a shape nothing has exercised.
     */
    public static PlutusData plainScriptAddress(String paymentScriptHash) {
        return constr(0,
                constr(1, hash(paymentScriptHash)),   // Credential::Script
                constr(1));                            // stake_credential: None
    }

    // ---- the two datums the order embeds, and their hashes -------------------------------------

    /**
     * The SUCCESS datum: {@code AssetManagerDatumWithToken { OutputReference{"", 0},
     * converted_to_liquidity, data: collateralAsset, ownerAsset }}.
     *
     * <p>⚠ <b>Its {@code transaction_id} is the EMPTY byte string</b>, not a 32-byte hash — the
     * validator writes {@code OutputReference { transaction_id: "", output_index: 0 }} literally. The
     * liquidation encoder's {@code outputReference} helper rejects anything that is not 64 hex chars,
     * correctly for its own use and wrongly for this one, which is why this path does not reuse it.
     *
     * <p>⚠ And its {@code data} field carries the <b>collateral Asset</b>, where every other
     * asset-manager datum this codebase writes carries {@code None}. The field is typed {@code Data},
     * so nothing but the hash comparison would ever notice a mistake.
     */
    public static PlutusData successDatum(AssetType collateralAsset, AssetType ownerAsset) {
        return constr(0,
                constr(0, BytesPlutusData.of(new byte[0]), BigIntPlutusData.of(0)),
                BytesPlutusData.of(HexUtil.decodeHexString(CONVERTED_TO_LIQUIDITY_ACTION)),
                asset(collateralAsset),
                asset(ownerAsset));
    }

    /**
     * The REFUND datum: {@code AssetManagerDatumWithToken { loanInput.output_reference,
     * claimed_collateral, data: None, ownerAsset }} — the loan's OWN reference, and {@code None}.
     * Deliberately not symmetric with {@link #successDatum}; the validator builds them differently.
     */
    public static PlutusData refundDatum(String loanTxHash, int loanOutputIndex, AssetType ownerAsset) {
        require(loanTxHash != null && loanTxHash.length() == 64,
                "the loan's transaction id is 32 bytes / 64 hex chars, was: " + loanTxHash);
        require(loanOutputIndex >= 0, "outputIndex must be >= 0, was: " + loanOutputIndex);
        return constr(0,
                constr(0, hash(loanTxHash), BigIntPlutusData.of(loanOutputIndex)),
                BytesPlutusData.of(HexUtil.decodeHexString(CLAIMED_COLLATERAL_ACTION)),
                constr(1),   // data: None
                asset(ownerAsset));
    }

    /**
     * {@code blake2b_256(serialise_data(d))} — the hash the order datum embeds and that a carrier
     * output's inline datum must reproduce.
     *
     * <p>⚑ Which is why the builder must place outputs whose inline datums are <b>these exact
     * objects</b> rather than re-encodings of them: CCL trap 4 says a decode→re-encode is not
     * byte-stable, and here a single differing byte moves the hash and fails the validator.
     */
    public static String datumHash(PlutusData datum) {
        return datum.getDatumHash();
    }

    // ---- the action's own redeemer -------------------------------------------------------------

    /**
     * {@code LMLiquidateAndConvertActionWithdrawRedeemer}, in the blueprint's field order.
     *
     * <p>⛔ <b>{@code lenderBondInputIndexes} comes BEFORE {@code lenderBondAssetNames}</b> — the
     * opposite of the order the validator's body reads them in. The declaration is what the encoding
     * follows; reading the body and encoding in the order it happens to mention things produces a
     * redeemer that decodes into the wrong fields.
     */
    public static PlutusData convertRedeemer(int configRefInputIndex,
                                             List<Integer> lenderBondInputIndexes,
                                             List<String> lenderBondAssetNames,
                                             List<Integer> minswapRefInputIndexes,
                                             List<String> minswapPoolAssetNames,
                                             List<Integer> successDatumOutputIndexes,
                                             List<Integer> refundDatumOutputIndexes) {
        int n = lenderBondInputIndexes.size();
        require(lenderBondAssetNames.size() == n
                        && minswapRefInputIndexes.size() == n
                        && minswapPoolAssetNames.size() == n
                        && successDatumOutputIndexes.size() == n
                        && refundDatumOutputIndexes.size() == n,
                "every per-loan list must have one entry per loan input; the validator walks them by "
                        + "the SAME index and a short list silently reads the wrong loan's values");
        require(configRefInputIndex >= 0, "configRefInputIndex must be >= 0");
        return constr(0,
                BigIntPlutusData.of(configRefInputIndex),
                ints(lenderBondInputIndexes),
                bytesList(lenderBondAssetNames),
                ints(minswapRefInputIndexes),
                bytesList(minswapPoolAssetNames),
                ints(successDatumOutputIndexes),
                ints(refundDatumOutputIndexes));
    }

    // ---- primitives ----------------------------------------------------------------------------

    /** {@code Asset { policyId, assetName }} — constructor 0, policy id first. */
    private static PlutusData asset(AssetType token) {
        return constr(0,
                BytesPlutusData.of(token.getPlutusDataPolicyId()),
                BytesPlutusData.of(token.getPlutusDataAssetName()));
    }

    /** Aiken {@code Bool}: constructor 0 is False, constructor 1 is True. */
    private static PlutusData bool(boolean b) {
        return constr(b ? 1 : 0);
    }

    private static ConstrPlutusData constr(int alternative, PlutusData... fields) {
        return ConstrPlutusData.builder()
                .alternative(alternative)
                .data(ListPlutusData.of(fields))
                .build();
    }

    private static BytesPlutusData hash(String hex) {
        return BytesPlutusData.of(HexUtil.decodeHexString(hex));
    }

    private static PlutusData ints(List<Integer> values) {
        return ListPlutusData.of(values.stream()
                .map(v -> (PlutusData) BigIntPlutusData.of(v)).toArray(PlutusData[]::new));
    }

    private static PlutusData bytesList(List<String> hexes) {
        return ListPlutusData.of(hexes.stream()
                .map(h -> (PlutusData) BytesPlutusData.of(HexUtil.decodeHexString(h)))
                .toArray(PlutusData[]::new));
    }

    private static byte[] concat(AssetType asset) {
        byte[] policy = asset.getPlutusDataPolicyId();
        byte[] name = asset.getPlutusDataAssetName();
        byte[] out = new byte[policy.length + name.length];
        System.arraycopy(policy, 0, out, 0, policy.length);
        System.arraycopy(name, 0, out, policy.length, name.length);
        return out;
    }

    private static byte[] sha3(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA3-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA3-256 is required for Minswap lp asset names", e);
        }
    }

    private static String utf8Hex(String s) {
        return HexUtil.encodeHexString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
