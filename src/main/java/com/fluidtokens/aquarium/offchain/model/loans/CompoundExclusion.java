package com.fluidtokens.aquarium.offchain.model.loans;

/**
 * Why a compound candidate was not built.
 *
 * <p>Sibling of {@link LiquidationExclusion}, deliberately separate: compound is a different action
 * with different economics (findings §20.3). A shared enum would invite reasoning that applies to one
 * and not the other.
 */
public enum CompoundExclusion {

    /** {@code loans.compound.enabled} is false. The default, and not a fault. */
    NOT_ARMED,

    /**
     * The lender bond names no pool ({@code poolId == ""}). On chain
     * {@code lm_compound_action} fails the whole action for this, so such an escrow is
     * <b>permanently</b> uncompoundable by anyone — see findings §20.1.
     */
    BOND_NAMES_NO_POOL,

    /**
     * The pool or its pool manager is not live. Both must carry {@code poolId} as their NFT asset
     * name with quantity 1. Measured on preview 2026-09-02: twelve of thirteen pools were burned,
     * and nine of ten lender bonds named one of them (findings §20.2).
     */
    POOL_NOT_LIVE,

    /**
     * ⛔ The pool's principal is not ADA — a <b>structural, build-capability</b> refusal, distinct
     * from {@link #PRICE_UNAVAILABLE} below.
     *
     * <p>{@link com.fluidtokens.aquarium.offchain.service.loans.CompoundEconomics#assess} no longer
     * refuses on this alone: it prices the fee slice through
     * {@link com.fluidtokens.aquarium.offchain.service.loans.PricingService} regardless of the
     * principal's asset, and only refuses with {@link #PRICE_UNAVAILABLE} when that pricing itself
     * fails. This exclusion is emitted upstream instead, by
     * {@code CompoundCandidateScanner#classify}, for two reasons that both still hold today:
     *
     * <ol>
     *   <li>{@code CompoundTransactionBuilder} computes the pool's output and the bot's own net
     *   position entirely in lovelace ({@code plusLovelace}/{@code lovelaceOf} applied directly to
     *   {@code addedLiquidity} and the compounding fee) — sound only because the one principal that
     *   has ever reached it is ada, where "the principal's own unit" and "lovelace" are the same
     *   thing. It is not yet generalised to pay a token amount instead of a lovelace one, and until
     *   it is, letting a non-ada candidate reach it would build an incorrect transaction.</li>
     *   <li>Held on a product ruling from Giovanni (FAB-77, 2026-09-09): a token-principal compound
     *   pays its fee in ADA and is rewarded in the principal token — i.e. the bot acquires a token —
     *   the same class of decision as convert's "the bot buys collateral". Lifting this exclusion is
     *   not only a build question.</li>
     * </ol>
     *
     * <p>So a token-principal escrow is refused here structurally, <b>regardless of whether its
     * price would in fact be available</b> — that is the "case that is still refused" the
     * oracle-pricing slice's contract names.
     */
    PRINCIPAL_NOT_ADA,

    /**
     * ⛔ {@code PricingService} could not price the fee slice into lovelace: no oracle feed for the
     * principal asset at all, a feed present but not usable at the instant asked (expired, or not
     * yet valid), or a feed present and covering the instant but a {@code POOLED} variant —
     * {@code get_token_amount_in_lovelace} is an outright {@code fail} on that variant on chain, so
     * it cannot be priced off chain either (hazard: {@code OraclePriceFeed.price()} throws for it).
     * Fail-closed: never a 1:1 fallback, never a last-known price.
     */
    PRICE_UNAVAILABLE,

    /**
     * ⛔ The escrow UTxO carries assets the validator will not accept.
     *
     * <p>{@code lm_compound_action} constrains the SHAPE of every asset-manager input it consumes:
     * <pre>
     *   if principalAsset is ada  -> flatten(value).length == 1 + receiptNFTCount
     *   else                      -> flatten(value).length == 2 + receiptNFTCount
     * </pre>
     * so an ADA-principal escrow must hold <b>lovelace and nothing else</b>, save any number of
     * repayment receipt NFTs. This is an {@code expect}, so a violation <b>aborts</b> the validator
     * rather than returning false.
     *
     * <p>⚑ This is why "preserve the escrow's other assets in the pool output" is not a thing the
     * builder can do: such an escrow is not compoundable at all. Measured 2026-09-02: five of the
     * eight unspent preview escrows carry tFLDT beside their lovelace and are excluded here, quite
     * apart from their pools also being burned.
     */
    ESCROW_SHAPE_REJECTED,

    /**
     * The escrow is owned by a borrower bond rather than a lender bond, so
     * {@code lm_compound_action}'s lender-bond lookup does not apply to it. Two of the eight unspent
     * preview escrows are this (measured 2026-09-02) — a structural exclusion, not an economic one.
     */
    NOT_LENDER_OWNED,

    /**
     * The escrow's datum is {@code AssetManagerDatumWithHash}. A legitimate variant that no
     * token-owned action can collect: the validator does {@code expect AssetManagerDatumWithToken}
     * and aborts. Classified rather than treated as a decode failure.
     */
    ESCROW_NOT_TOKEN_OWNED,

    /** No unspent lender bond carrying the escrow's {@code ownerAsset} is in the index. */
    BOND_NOT_FOUND,

    /**
     * The net result does not clear the operator's stated floor.
     *
     * <p>This is the ordinary outcome for a pool whose {@code compoudingFeePerMille} is zero: the
     * expected fee is zero, the net is exactly minus the transaction fee, and the default floor of
     * zero refuses it. An operator who wants that work done states a negative floor and owns it.
     */
    NET_BELOW_FLOOR
}
