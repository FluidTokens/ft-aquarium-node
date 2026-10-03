package com.fluidtokens.aquarium.offchain.controller;

import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidationCandidateScanner;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidationExecutor;

/**
 * The route selected by the indexed lender bond. This mirrors the
 * {@link LiquidationExecutor#consider} branch on
 * {@code shouldLiquidationConvertToPrincipal}; a bond that forbids conversion is plain liquidation,
 * while a bond that permits it is routed by the market. {@link LiquidationCandidateScanner} builds
 * executor assessments from lender bonds, so an unindexed bond means there is no executor candidate.
 */
public enum BondRoute {
    NO_BOND,
    PLAIN,
    CONVERT;

    public static final String NO_BOND_DETAIL = "no lender bond indexed for this loan — the bot works "
            + "from lender bonds, so it has no liquidation candidate here and would do nothing";

    public static BondRoute of(LenderBond bond) {
        if (bond == null) {
            return NO_BOND;
        }
        return bond.datum().shouldLiquidationConvertToPrincipal() ? CONVERT : PLAIN;
    }
}
