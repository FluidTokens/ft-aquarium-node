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
     * ⛔ The pool's principal is not ADA.
     *
     * <p>The compounding fee is denominated in the <b>principal asset</b>, while the transaction fee
     * that must be cleared is in <b>lovelace</b>. For a token-principal pool those are different
     * units and the gate's subtraction would be meaningless — it would compare a token quantity to a
     * lovelace cost and produce a number that looks like profit. Pricing the token needs an oracle
     * this path does not have, so the honest answer is to refuse rather than to compare.
     */
    PRINCIPAL_NOT_ADA,

    /**
     * The net result does not clear the operator's stated floor.
     *
     * <p>This is the ordinary outcome for a pool whose {@code compoudingFeePerMille} is zero: the
     * expected fee is zero, the net is exactly minus the transaction fee, and the default floor of
     * zero refuses it. An operator who wants that work done states a negative floor and owns it.
     */
    NET_BELOW_FLOOR
}
