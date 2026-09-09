package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ With no Minswap pool address configured, a convert candidate must be SKIPPED — and skipped
 * <b>before the provider is called</b>.
 *
 * <p>Observed running on preview, 2026-09-08: the mainnet pool address was reaching a preview
 * provider and coming back {@code 400 "Invalid address for this network"}, logged at ERROR with a
 * stack trace, once per candidate per scheduling cycle. Nothing could be retried and nothing an
 * operator read in that log told them what to change.
 *
 * <p>⚑ <b>The provider is passed as {@code null} on purpose, and that IS the assertion.</b> If the
 * guard is removed the resolver is dereferenced and this fails with a {@code NullPointerException}
 * instead — so "did not call the provider" is proven by construction rather than asserted about a
 * mock. A test that merely checked the exception type would still pass with the call restored.
 */
class ConvertSkipsWhenNoPoolAddressTest {

    private static final AssetType FLDT = new AssetType(
            "577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454");

    private static ConvertLiquidationRouter routerWithNoPoolAddress() {
        AppConfig.LoansConfiguration loans = new AppConfig.LoansConfiguration();
        // ⚠ The default is now blank — that is the property under test. If an inline default is ever
        // restored to AppConfig this stops being blank and the guard stops firing.
        assertTrue(loans.getMinswapPoolAddress() == null || loans.getMinswapPoolAddress().isBlank(),
                "LoansConfiguration must default to NO pool address; a mainnet default here is the "
                        + "defect this test exists for");
        return new ConvertLiquidationRouter(null, loans, null,
                /* poolResolver */ null, null, null, null, null);
    }

    private static LiquidationAssessment candidate() {
        LoanDatum datum = LoanFixtures.loanDatum(
                AssetType.ada(), BigInteger.valueOf(100_000_000L), BigInteger.valueOf(100L),
                new CollateralAsset(FLDT.policyId(),
                        java.util.Optional.of(FLDT.assetName()), LoanFixtures.NO_ORACLE),
                1_700_000_000_000L,
                new LiquidationMode.Liquidation(BigInteger.valueOf(500L), BigInteger.valueOf(1000L),
                        BigInteger.ZERO, false),
                new RepaymentMode.InterestOnRemainingPrincipal(BigInteger.ZERO), false);
        Loan loan = new Loan("aa".repeat(32), 0, "addr_test_loan", "cafe",
                BigInteger.valueOf(150_000_000L), BigInteger.valueOf(3_000_000L), datum);
        LenderBond bond = new LenderBond("bb".repeat(32), 0, "addr_test_bond", "cafe", "d87980",
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(50L),
                        LoanFixtures.noStakeCredential(), AssetType.ada()));
        return LiquidationAssessment.buildable(bond, loan, "no-pool-address fixture",
                BigInteger.valueOf(28_000_000L), BigInteger.ZERO, false, BigInteger.valueOf(7_500_000L));
    }

    @Test
    @DisplayName("no pool address configured -> NoPoolException naming the reason, provider untouched")
    void itSkipsAndSaysWhy() {
        ConvertLiquidationRouter router = routerWithNoPoolAddress();

        var e = assertThrows(ConvertLiquidationRouter.NoPoolException.class,
                () -> router.buildConvertLiquidation(candidate(), null, null, null, null, null,
                        java.util.Map.of(), "addr_change", 0L, 1L),
                "a blank pool address must SKIP the candidate; a NullPointerException here means the "
                        + "provider was called first, which is the defect");

        assertTrue(e.getMessage().contains("no Minswap pool address configured"),
                "the line an operator reads must name the missing configuration, not a 400 from a "
                        + "provider: " + e.getMessage());
        assertTrue(e.getMessage().contains("loans.minswap.pool-address"),
                "it must name the key to set: " + e.getMessage());
    }
}
