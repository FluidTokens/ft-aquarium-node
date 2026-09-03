package com.fluidtokens.aquarium.offchain.model.loans;

/**
 * Why the bot will not build a <b>LiquidateAndConvert</b> for a candidate that the liquidation
 * scanner otherwise found sound.
 *
 * <p>Deliberately its own enum rather than more constants on {@link LiquidationExclusion}. That one
 * answers "is this loan liquidatable at all", which is a question about the borrower and the oracle;
 * this one answers "will the bot route it through Minswap", which is a question about the operator's
 * configuration and the bot's own margin. Collapsing them would put an operator's policy decision
 * into the same list as a protocol fact.
 */
public enum ConvertExclusion {

    // ⚑ The two per-market routing refusals — "this market is off" and "this market forces
    // pay-in-advance" — are deliberately NOT declared here yet. They belong to the routing stage,
    // where MarketGate learns modes and a call site exists to record them. A declared value with no
    // call site is a case the operator can never be shown while the enum implies they can, which is
    // the exact defect EveryOutcomeIsReachableTest was written for.

    /** {@code loans.liquidation.convert.enabled} is false — the operator turned the path off. */
    NOT_ARMED,

    /**
     * The lender bond's {@code shouldLiquidationConvertToPrincipal} is false, so
     * {@code lm_liquidate_and_convert_action} would fail its first conjunct. The lender picks the
     * class of liquidation their loan permits; the operator only picks the mechanism within it
     * (findings §27).
     */
    BOND_FORBIDS_CONVERSION,

    /**
     * No usable oracle price for the <b>collateral</b> asset, so the fee this convert would earn —
     * which is denominated in that asset — cannot be valued in lovelace at all. Refused rather than
     * priced at zero or at a guess.
     */
    COLLATERAL_UNPRICEABLE,

    /**
     * {@code net} — the oracle value of the collateral-denominated liquidation fee, less the ada the
     * bot actually spends — is below {@code loans.liquidation.convert.profit-margin-lovelace}.
     *
     * <p>This is also how a bond whose {@code liquidationFeePerMille} is 0 is refused: such a bond
     * pays nothing, so the net is exactly minus the outlay and the default floor rejects it. No
     * separate zero-fee constant exists, for the same reason the compound path has none.
     */
    NET_BELOW_FLOOR
}
