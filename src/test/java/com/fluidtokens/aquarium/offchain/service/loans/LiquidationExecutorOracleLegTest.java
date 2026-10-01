package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ The executor's own NFT-keyed oracle reads — the pre-submit window re-check and the recorded
 * collateral economics — hold to {@link OracleEntry#namedForLeg}: the oracle a loan's datum names is
 * that leg's only if it prices that leg's token (oracle re-slice, cross-provider round 2). Both are
 * unreachable with a wrong-token entry through today's builders, which refuse first; these tests make
 * the executor's guard stand on its own rather than on that ordering.
 */
class LiquidationExecutorOracleLegTest {

    private static final AssetType TOKEN = new AssetType("a".repeat(56), "544f4b");
    private static final AssetType OTHER = new AssetType("e".repeat(56), "544f4b");
    private static final AssetType NFT = new AssetType("c".repeat(56), "6f7261636c65");
    private static final long NOW = 1_760_000_000_000L;
    private static final long MARGIN = 60_000L;

    /** Priced at 3, valid for an hour past NOW — a window that clears the margin by a mile. */
    private static OracleEntry pricing(AssetType token) {
        return new OracleEntry(token, NFT, null, null, null, null, List.of(), 0,
                new OraclePriceFeed(OraclePriceFeed.Variant.AGGREGATED, token, BigInteger.valueOf(3),
                        BigInteger.ONE, NOW - 60_000L, NOW + 3_600_000L),
                List.of(), null);
    }

    @Test
    void theWindowOfAnOracleForAnotherTokenIsNotThisLegsWindow() {
        String why = LiquidationExecutor.shortfall(TOKEN, NFT, "collateral", NOW, MARGIN,
                Map.of(NFT.toUnit(), pricing(OTHER)));
        assertTrue(why != null && why.contains("collateral leg has no oracle entry"),
                "another token's ample window must not clear this leg: " + why);
    }

    @Test
    void theNamedOracleForThisTokenClearsTheWindowCheck() {
        assertNull(LiquidationExecutor.shortfall(TOKEN, NFT, "principal", NOW, MARGIN,
                Map.of(NFT.toUnit(), pricing(TOKEN))));
        assertNull(LiquidationExecutor.shortfall(AssetType.ada(), NFT, "principal", NOW, MARGIN, Map.of()),
                "an ada leg has no window to check");
    }

    private static LiquidationAssessment tokenCollateralAssessment() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(20_000_000L),
                BigInteger.valueOf(100L), LoanFixtures.tokenCollateral(TOKEN, NFT), 1_700_000_000_000L,
                new LiquidationMode.Liquidation(BigInteger.valueOf(500L), BigInteger.valueOf(1000L),
                        BigInteger.ZERO, false),
                new RepaymentMode.InterestOnRemainingPrincipal(BigInteger.ZERO), false);
        Loan loan = new Loan("aa".repeat(32), 0, "addr_loan", "cafe",
                BigInteger.valueOf(100_000_000L), BigInteger.valueOf(3_000_000L), datum);
        LenderBond bond = new LenderBond("bb".repeat(32), 0, "addr_bond", "cafe", "d87980",
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(50L),
                        LoanFixtures.noStakeCredential(), AssetType.ada()));
        return LiquidationAssessment.buildable(bond, loan, "oracle leg fixture",
                BigInteger.valueOf(20_000_000L), BigInteger.ZERO, false, BigInteger.valueOf(5_000_000L));
    }

    @Test
    void theRecordedCollateralFeedIsNeverAnotherTokensPrice() {
        var e = assertThrows(IllegalStateException.class, () -> LiquidationExecutor.collateralFeed(
                tokenCollateralAssessment(), Map.of(NFT.toUnit(), pricing(OTHER))));
        assertTrue(e.getMessage().contains("no oracle entry for the collateral leg"), e.getMessage());
    }

    @Test
    void theRecordedCollateralFeedIsTheNamedOraclesForThisToken() {
        OracleEntry named = pricing(TOKEN);
        assertSame(named.feed(), LiquidationExecutor.collateralFeed(tokenCollateralAssessment(),
                Map.of(NFT.toUnit(), named)));
        assertEquals(BigInteger.valueOf(3), named.feed().priceInLovelaces());
    }
}
