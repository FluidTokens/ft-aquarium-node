package com.fluidtokens.aquarium.offchain.model.loans;

import java.math.BigInteger;

/**
 * {@code LiquidationMode} from {@code lib/fluidtokens/types/general.ak}.
 * <p>
 * Constructor indices are load-bearing and are asserted against the blueprint
 * definition {@code fluidtokens/types/general/LiquidationMode}.
 */
public sealed interface LiquidationMode {

    /** Constr 0 — no liquidation; the lender takes the whole collateral on default. */
    record NoLiquidationFullCollateralClaim() implements LiquidationMode {
    }

    /** Constr 1 — no liquidation; the collateral goes to a dutch auction. */
    record NoLiquidationDutchAuctionClaim() implements LiquidationMode {
    }

    /**
     * Constr 2 — the <em>only</em> mode the liquidation bot can act on, and then only when
     * {@code equityInPrincipalCurrency} is false: all four LenderManager actions
     * {@code expect Liquidation {..}} followed by {@code expect equityInPrincipalCurrency == False}.
     * See {@code docs/lending-v4-findings.md} §7 D1/D2.
     *
     * @param partialLiquidationPenaltyPerMille negative ⇒ borrower loses all collateral;
     *                                          {@code >= 0} ⇒ lender must refund the difference.
     */
    record Liquidation(BigInteger ltv,
                       BigInteger ltvDivider,
                       BigInteger partialLiquidationPenaltyPerMille,
                       boolean equityInPrincipalCurrency) implements LiquidationMode {
    }

    /**
     * True when the <em>mode</em> alone does not rule out bot liquidation (§7 D1 + D2).
     * <p>
     * Necessary but <b>not</b> sufficient — the collateral must also be a single named asset
     * with quantity &gt; 1 (§7 D3), which needs the UTxO and so lives on
     * {@link Loan#botLiquidatable()}. Use that for a real answer.
     */
    default boolean allowsBotLiquidation() {
        return this instanceof Liquidation l && !l.equityInPrincipalCurrency();
    }
}
