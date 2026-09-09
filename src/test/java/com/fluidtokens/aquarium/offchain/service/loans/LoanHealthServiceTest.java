package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That {@link LoanHealthService} composes {@link LoanFinance} correctly.
 *
 * <p>{@code LoanFinance} itself is verified against values the Aiken compiler produced from
 * {@code finance.ak}, so it is trustworthy as the source of expected numbers here. What was
 * <em>not</em> covered anywhere is the wiring around it: which oracle feed is passed as the
 * principal leg and which as the collateral leg. Transposing those two arguments produces
 * plausible numbers and would have passed every other test in this repository, so the central
 * test below fixes the orientation and then proves the fixture could detect a swap at all.
 *
 * <p>The loan is deliberately token/token. With an ada leg the principal feed is the synthesised
 * 1:1 unit and a transposition is far less visible.
 */
class LoanHealthServiceTest {

    private static final AssetType PRINCIPAL =
            new AssetType("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "5052494e");

    private static final AssetType COLLATERAL =
            new AssetType("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "434f4c4c");

    /** 5 lovelace per principal unit, 2 per collateral unit — distinct, so a swap changes the answer. */
    private static final long PRINCIPAL_PRICE = 5;
    private static final long COLLATERAL_PRICE = 2;

    private static final long NOW = 1_786_000_000_000L;
    private static final long LEND_DATE = NOW - 30L * 24 * 3_600_000L;

    private static final BigInteger COLLATERAL_AMOUNT = BigInteger.valueOf(4_000_000);

    /** A real mainnet reward address: a malformed one would be dropped during parsing, silently. */
    private static final String REWARD_ADDRESS =
            "stake1799y3huw4j0n4weehlx3t6xvst50ge0vuv32ghk50mm7hwg5ucz8p";

    // ---- fixtures -----------------------------------------------------------------------

    /**
     * Windows must stay inside {@code max_oracle_validity_range} (60 minutes) or the feed is
     * unusable — an easy way to write a fixture that silently prices nothing.
     */
    private static final long VALID_FROM = NOW - 1_000L;
    private static final long VALID_TO = NOW + 1_000L;

    private static String registry(long principalPrice, long collateralPrice, long validFrom, long validTo) {
        return "[%s,%s]".formatted(
                entry(PRINCIPAL, principalPrice, validFrom, validTo),
                entry(COLLATERAL, collateralPrice, validFrom, validTo));
    }

    private static String registry(long principalPrice, long collateralPrice) {
        return registry(principalPrice, collateralPrice, VALID_FROM, VALID_TO);
    }

    private static String entry(AssetType token, long price, long validFrom, long validTo) {
        return """
                {
                  "active": true,
                  "preferredOracle": "multisig",
                  "token": { "policyId": "%s", "assetName": "%s" },
                  "fluidOracle": {
                    "policyId": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                    "assetName": "%s",
                    "rewardAddress": "%s",
                    "referenceInput": "aa00000000000000000000000000000000000000000000000000000000000000#0",
                    "referenceScript": "bb00000000000000000000000000000000000000000000000000000000000000#0"
                  },
                  "multisigOracle": { "publicKeys": ["%s"], "requiredSignatures": 1 },
                  "supportedOracle": {
                    "multisig": {
                      "validFrom": %d,
                      "validTo": %d,
                      "tokenPriceInLovelaces": %d,
                      "tokenPriceDenominator": 1,
                      "multisigOracle": {
                        "requiredSignatures": 1,
                        "signatures": [{ "publicKey": "%s", "signature": "ff" }]
                      }
                    }
                  }
                }
                """.formatted(token.policyId(), token.assetName(), token.assetName(), REWARD_ADDRESS,
                "0".repeat(64), validFrom, validTo, price, "0".repeat(64));
    }

    private static LoanHealthService serviceWith(String registryJson) throws Exception {
        var client = new FluidOracleClient("http://unused.invalid");
        client.load(new ObjectMapper().readTree(registryJson));
        return new LoanHealthService(provider(client));
    }

    /** Enough of {@link ObjectProvider} for the one call the service makes. */
    private static ObjectProvider<FluidOracleClient> provider(FluidOracleClient client) {
        return new ObjectProvider<>() {
            @Override
            public FluidOracleClient getObject() {
                return client;
            }

            @Override
            public FluidOracleClient getObject(Object... args) {
                return client;
            }

            @Override
            public FluidOracleClient getIfAvailable() {
                return client;
            }

            @Override
            public FluidOracleClient getIfUnique() {
                return client;
            }
        };
    }

    private static LoanDatum datum(LiquidationMode liquidationMode) {
        return new LoanDatum(
                BigInteger.ZERO,                      // doneRecasts
                BigInteger.valueOf(1_000_000),        // principalAmount
                BigInteger.valueOf(LEND_DATE),
                BigInteger.ZERO,                      // repaidInstallments
                BigInteger.valueOf(1_000),            // interestRate — 10%
                BigInteger.ZERO,                      // totalInstallments
                PRINCIPAL,
                PRINCIPAL,                            // principalOracleAsset, unused by health
                BigInteger.ZERO,                      // installmentPeriod
                BigInteger.ZERO,                      // initialGracePeriod
                liquidationMode,
                new RepaymentMode.PerpetualLoan(BigInteger.valueOf(28), BigInteger.valueOf(5)),
                BigInteger.ZERO,                      // repaymentTimeWindow
                BigInteger.ZERO,                      // penaltyFeeForLateRepayment
                false,
                "00",
                new CollateralAsset(COLLATERAL.policyId(), Optional.of(COLLATERAL.assetName()), COLLATERAL));
    }

    private static LiquidationMode.Liquidation liquidation() {
        return new LiquidationMode.Liquidation(BigInteger.valueOf(100), BigInteger.valueOf(125),
                BigInteger.valueOf(100), false);
    }

    private static Loan loan(LiquidationMode mode) {
        return new Loan("ab".repeat(32), 0, "addr_test1", "cafe",
                COLLATERAL_AMOUNT, BigInteger.valueOf(3_000_000), datum(mode));
    }

    // ---- the wiring ---------------------------------------------------------------------

    /**
     * The test this class exists for. Every priced field must equal what {@link LoanFinance}
     * returns for the principal-then-collateral argument order, and the fixture must be able to
     * tell that order apart from its reverse.
     */
    @Test
    void pricedFieldsUseThePrincipalFeedForDebtAndTheCollateralFeedForCollateral() throws Exception {
        var health = serviceWith(registry(PRINCIPAL_PRICE, COLLATERAL_PRICE))
                .health(loan(liquidation()), NOW);

        var principalFeed = FluidOracleClient.feedOf(PRINCIPAL, BigInteger.valueOf(PRINCIPAL_PRICE),
                BigInteger.ONE, VALID_FROM, VALID_TO);
        var collateralFeed = FluidOracleClient.feedOf(COLLATERAL, BigInteger.valueOf(COLLATERAL_PRICE),
                BigInteger.ONE, VALID_FROM, VALID_TO);

        var debt = Rational.fromInt(LoanFinance.remainingDebt(datum(liquidation()), NOW));
        var collateral = Rational.fromInt(COLLATERAL_AMOUNT);

        var expectedLtv = LoanFinance.currentLtv(debt, collateral, principalFeed, collateralFeed);
        var transposedLtv = LoanFinance.currentLtv(debt, collateral, collateralFeed, principalFeed);
        assertNotEquals(expectedLtv, transposedLtv,
                "the fixture must be able to detect a transposition, or this test proves nothing");

        assertEquals(expectedLtv, health.currentLtv(), "principal feed prices the debt");
        assertEquals(LoanFinance.redeemerEquity(liquidation(), collateral, debt, principalFeed, collateralFeed),
                health.equity());
        assertEquals(LoanFinance.canLiquidate(debt, collateral, LoanFinance.liquidationLtv(liquidation()),
                principalFeed, collateralFeed), health.liquidatable());
        assertNull(health.unavailableReason());
    }

    @Test
    void debtAndLatenessMatchTheFinanceEngine() throws Exception {
        var health = serviceWith(registry(PRINCIPAL_PRICE, COLLATERAL_PRICE))
                .health(loan(liquidation()), NOW);

        assertEquals(LoanFinance.remainingDebt(datum(liquidation()), NOW), health.remainingDebt());
        assertEquals(LoanFinance.isRepaymentLate(datum(liquidation()), NOW), health.repaymentLate());
    }

    /**
     * {@code loan_claim_action.ak:230} is {@code or { isRepaymentLate, can_liquidate }}, so a late
     * loan is liquidatable no matter how healthy its LTV is. Installments make it late; the
     * collateral is left generous so {@code can_liquidate} alone would say false.
     */
    @Test
    void latenessAloneMakesALoanLiquidatable() throws Exception {
        var lateDatum = new LoanDatum(
                BigInteger.ZERO, BigInteger.valueOf(1_000_000), BigInteger.valueOf(LEND_DATE),
                BigInteger.ZERO, BigInteger.valueOf(1_000), BigInteger.valueOf(12),
                PRINCIPAL, PRINCIPAL, BigInteger.valueOf(24), BigInteger.ZERO,
                liquidation(),
                new RepaymentMode.PrincipalAndInterestOnInstallments(),
                BigInteger.ZERO, BigInteger.ZERO, false, "00",
                new CollateralAsset(COLLATERAL.policyId(), Optional.of(COLLATERAL.assetName()), COLLATERAL));
        var wealthy = new Loan("ab".repeat(32), 0, "addr_test1", "cafe",
                BigInteger.valueOf(1_000_000_000), BigInteger.valueOf(3_000_000), lateDatum);

        var health = serviceWith(registry(PRINCIPAL_PRICE, COLLATERAL_PRICE))
                .health(wealthy, NOW);

        assertTrue(health.repaymentLate(), "30 days elapsed against a 24h installment period");
        assertFalse(LoanFinance.canLiquidate(Rational.fromInt(health.remainingDebt()),
                        Rational.fromInt(BigInteger.valueOf(1_000_000_000)),
                        LoanFinance.liquidationLtv(liquidation()),
                        FluidOracleClient.feedOf(PRINCIPAL, BigInteger.valueOf(PRINCIPAL_PRICE), BigInteger.ONE, 0, 0),
                        FluidOracleClient.feedOf(COLLATERAL, BigInteger.valueOf(COLLATERAL_PRICE), BigInteger.ONE, 0, 0)),
                "LTV alone must not be the reason");
        assertTrue(health.liquidatable(), "late is an independent trigger");
    }

    // ---- the unavailable paths ----------------------------------------------------------

    @Test
    void anExpiredFeedReportsExpiredRatherThanUnknown() throws Exception {
        var health = serviceWith(registry(PRINCIPAL_PRICE, COLLATERAL_PRICE, NOW - 2_000L, NOW - 1L))
                .health(loan(liquidation()), NOW);

        assertNull(health.currentLtv());
        assertTrue(health.unavailableReason().contains("expired"), health.unavailableReason());
        assertEquals(LoanFinance.remainingDebt(datum(liquidation()), NOW), health.remainingDebt(),
                "debt needs no oracle and must survive an oracle outage");
    }

    @Test
    void anAssetWithNoEntryReportsNoPrice() throws Exception {
        var health = serviceWith("[]").health(loan(liquidation()), NOW);

        assertNull(health.liquidatable());
        assertTrue(health.unavailableReason().startsWith("no oracle price"), health.unavailableReason());
    }

    /** Modes with no LTV threshold cannot produce one, and must say so instead of guessing. */
    @Test
    void modesWithoutAnLtvThresholdReportDebtOnly() throws Exception {
        var health = serviceWith(registry(PRINCIPAL_PRICE, COLLATERAL_PRICE))
                .health(loan(new LiquidationMode.NoLiquidationFullCollateralClaim()), NOW);

        assertNull(health.currentLtv());
        assertNull(health.liquidatable());
        assertTrue(health.unavailableReason().contains("NoLiquidationFullCollateralClaim"),
                health.unavailableReason());
        assertEquals(LoanFinance.remainingDebt(datum(liquidation()), NOW), health.remainingDebt());
    }
}
