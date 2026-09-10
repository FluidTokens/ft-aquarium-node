package com.fluidtokens.aquarium.offchain.controller;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClient;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The two properties an operator is promised about the readiness UI.</b>
 *
 * <p>The interesting one is the flag. This UI ships in the same image every operator runs, most of
 * whom will never turn it on, and it has <b>no authentication</b> — so "off by default" is not a
 * convenience, it is the security posture. A default that silently stopped working would expose loan
 * positions on every node, and nothing about the node's behaviour would look different.
 */
class LiquidationReadinessControllerTest {

    @Configuration
    @Import(LiquidationReadinessController.class)
    static class Ctx {
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(Ctx.class);
    }

    /** ⛔ Unset means ABSENT: no bean, no route, nothing served. */
    @Test
    void theUiDoesNotExistUnlessItIsTurnedOn() {
        runner().run(ctx -> assertTrue(
                ctx.getBeanNamesForType(LiquidationReadinessController.class).length == 0,
                "the readiness controller was constructed without loans.ui.enabled=true. It has no "
                        + "authentication and shows loan positions, so its absence by default is the "
                        + "posture, not a convenience"));
    }

    /** ⚠ And FALSE means absent too — a flag that only honours 'true' vs unset would be a trap. */
    @Test
    void anExplicitFalseAlsoLeavesItAbsent() {
        runner().withPropertyValues("loans.ui.enabled=false")
                .run(ctx -> assertEquals(0,
                        ctx.getBeanNamesForType(LiquidationReadinessController.class).length));
    }

    /**
     * Turning it on constructs it. ⚠ The context fails for a MISSING-DEPENDENCY reason rather than a
     * condition one, which is the proof that the condition passed: this controller takes only
     * {@code ObjectProvider}s of the lending beans plus two config objects, so the failure here is
     * about {@code AppConfig}, never about the flag.
     */
    @Test
    void turningItOnMakesTheConditionPass() {
        runner().withPropertyValues("loans.ui.enabled=true").run(ctx -> {
            if (ctx.getStartupFailure() != null) {
                assertTrue(!ctx.getStartupFailure().toString().contains("ui.enabled"),
                        "the context failed on the FLAG rather than on a missing collaborator, which "
                                + "would mean the condition never passed: " + ctx.getStartupFailure());
            } else {
                assertEquals(1, ctx.getBeanNamesForType(LiquidationReadinessController.class).length);
            }
        });
    }

    // ---- the ordering contract -------------------------------------------------------------------

    private static LiquidationReadinessController.Row row(String id, Double healthFactor) {
        return new LiquidationReadinessController.Row(id, id + "#0", "lovelace", BigInteger.TEN,
                "tok", BigInteger.TEN, healthFactor, null, null, null,
                null, null, null, "PLAIN LIQUIDATE", "", null);
    }

    /**
     * ⛔ <b>Closest to liquidation first, and UNKNOWN LAST.</b>
     *
     * <p>The ordering is the whole product: an operator reads the top of this list to decide what to
     * prepare for. ⚠ And the null placement is the load-bearing half — a loan whose health cannot be
     * computed sorting to the <b>top</b> would push the genuinely urgent ones off the fold, and it
     * would look exactly like a correct list.
     */
    @Test
    void theListPutsTheClosestToLiquidationFirstAndTheUncomputableLast() {
        List<LiquidationReadinessController.Row> rows = new ArrayList<>(List.of(
                row("healthy", 2.4), row("unknown", null), row("critical", 0.87), row("near", 1.05)));

        rows.sort(Comparator.comparingDouble(LiquidationReadinessController.Row::sortKey));

        assertEquals(List.of("critical", "near", "healthy", "unknown"),
                rows.stream().map(LiquidationReadinessController.Row::loanId).toList(),
                "closest to liquidation first; a row whose health is unknown must never outrank one "
                        + "that is measurably about to go");
    }

    /**
     * ⚠ An uncomputable figure is {@code null} plus a reason, and the row carries no zero anywhere to
     * be mistaken for one. <b>A fabricated number on this page would be acted on.</b>
     */
    @Test
    void anUncomputableRowCarriesNullsAndAReasonRatherThanZeros() {
        var r = new LiquidationReadinessController.Row("id", "id#0", "lovelace", BigInteger.TEN,
                "tok", BigInteger.TEN, null, null, null, "no usable oracle feed",
                null, null, "no usable oracle feed", "UNKNOWN", "no bond indexed", null);

        assertNull(r.healthFactor());
        assertNull(r.feeValueLovelace());
        assertNull(r.advancePrincipalAmount());
        assertEquals("no usable oracle feed", r.healthUnknownReason());
        assertTrue(r.sortKey() == Double.MAX_VALUE, "and it sorts last");
    }

    // ======================================================================================
    // F5 (round 2) — advanceAmount() must route through the REAL principal oracle, never the
    // deprecated 4-arg numbers() overload that silently priced every principal as ada.
    // ======================================================================================

    private static final AssetType COLLATERAL_TOKEN =
            new AssetType("aa".repeat(28), "434f4c4c");
    private static final AssetType COLLATERAL_ORACLE_NFT =
            new AssetType("bb".repeat(28), "434f4c4c4f5241434c45");
    private static final AssetType PRINCIPAL_TOKEN =
            new AssetType("cc".repeat(28), "5553444d");
    private static final AssetType PRINCIPAL_ORACLE_NFT =
            new AssetType("dd".repeat(28), "5553444d4f5241434c45");

    /** A stand-in whose {@code findEntry} answers from a fixed map — {@code findEntry} is public, so
     * this is overridable across packages without touching {@code FluidOracleClient} at all. */
    private static final class FakeOracleClient extends FluidOracleClient {

        private final java.util.Map<AssetType, OracleEntry> byToken;

        FakeOracleClient(OracleEntry... entries) {
            super("http://unused.invalid");
            byToken = new java.util.HashMap<>();
            for (OracleEntry entry : entries) {
                // findEntry (production) is keyed by the PRICED asset — entry.token() — never the
                // oracle NFT (entry.oracleToken(), which is findEntryByOracleToken's key instead).
                byToken.put(entry.token(), entry);
            }
        }

        @Override
        public Optional<OracleEntry> findEntry(AssetType token) {
            return Optional.ofNullable(byToken.get(token));
        }
    }

    private static <T> ObjectProvider<T> provide(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    private static LiquidationReadinessController controllerWith(FluidOracleClient client,
                                                                  LoansContractRegistry registry) {
        AppConfig.Network network = new AppConfig.Network() {
            @Override
            public com.bloxbean.cardano.client.common.model.Network getCardanoNetwork() {
                return Networks.testnet();
            }
        };
        return new LiquidationReadinessController(provide(null), provide(null), provide(null),
                provide(client), provide(null), provide(registry), null, network);
    }

    /**
     * ⛔ THE BUG THIS TEST EXISTS TO CATCH. A collateral priced 1:1 in lovelace and a principal priced
     * 2 lovelace per base unit: the CORRECT figure divides the collateral's lovelace value by the
     * principal's own price (WALL 1's two-feed composition) and the OLD (deprecated 4-arg) figure did
     * not divide by anything — it silently treated the principal as ada. The two numbers differ by
     * exactly the principal's price factor here (195,000,000 vs 97,500,000), so a test that could pass
     * under either formula would prove nothing; this one cannot.
     * <p>
     * Arithmetic pinned by hand: remainingDebt = 100,000,000 (0% interest, 1 installment) ⇒ equity =
     * floor(300,000,000·1 - 100,000,000·1 - 0.05·100,000,000·1) = 90,000,000 ⇒ liquidationFee =
     * floor(300,000,000·50/1000) = 15,000,000 ⇒ collateralLenderShouldReceive = 300,000,000 - 90,000,000
     * - 15,000,000 = 195,000,000 ⇒ converted = ceil(195,000,000·1 / 2) = 97,500,000.
     */
    @Test
    void advanceAmountRoutesThroughTheRealPrincipalOracleNotTheAdaShortcut() {
        LoanDatum datum = LoanFixtures.loanDatum(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_NFT,
                BigInteger.valueOf(100_000_000L), BigInteger.ZERO,
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, COLLATERAL_ORACLE_NFT), 0L,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        Loan loan = new Loan("f0".repeat(32), 0, "addr_test1_placeholder", "loanid00",
                BigInteger.valueOf(300_000_000L), BigInteger.valueOf(3_000_000L), datum);
        LenderBond bond = new LenderBond("f0".repeat(32), 1, "addr_test1_placeholder", "loanid00", "",
                LoanFixtures.bondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(),
                        PRINCIPAL_TOKEN));

        OracleEntry collateralOracle = LoanFixtures.charli3(COLLATERAL_TOKEN, COLLATERAL_ORACLE_NFT,
                "11".repeat(28), OraclePriceFeed.priceDataCharlie(COLLATERAL_TOKEN,
                        BigInteger.ONE, BigInteger.ONE, 0L, 10_000_000L),
                input("22"), input("33"), input("44"));
        OracleEntry principalOracle = LoanFixtures.charli3(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_NFT,
                "55".repeat(28), OraclePriceFeed.priceDataCharlie(PRINCIPAL_TOKEN,
                        BigInteger.TWO, BigInteger.ONE, 0L, 10_000_000L),
                input("66"), input("77"), input("88"));
        FakeOracleClient client = new FakeOracleClient(collateralOracle, principalOracle);
        LiquidationReadinessController controller = controllerWith(client, LoanFixtures.registry());

        BigInteger advance = controller.advanceAmount(loan, bond, 1_000L);

        assertEquals(BigInteger.valueOf(97_500_000L), advance,
                "must be the two-feed WALL-1 composition, not the ada-shortcut figure");
        assertNotEquals(BigInteger.valueOf(195_000_000L), advance,
                "195,000,000 is what the deprecated null-principal-oracle overload would have "
                        + "produced — seeing it here means the fix regressed");
    }

    /** advanceAmount refuses (null) rather than guess when the loan's OWN principal oracle is missing. */
    @Test
    void advanceAmountIsNullWhenNoPrincipalOracleEntryExists() {
        LoanDatum datum = LoanFixtures.loanDatum(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_NFT,
                BigInteger.valueOf(100_000_000L), BigInteger.ZERO,
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, COLLATERAL_ORACLE_NFT), 0L,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        Loan loan = new Loan("f0".repeat(32), 0, "addr_test1_placeholder", "loanid00",
                BigInteger.valueOf(300_000_000L), BigInteger.valueOf(3_000_000L), datum);
        LenderBond bond = new LenderBond("f0".repeat(32), 1, "addr_test1_placeholder", "loanid00", "",
                LoanFixtures.bondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(),
                        PRINCIPAL_TOKEN));

        OracleEntry collateralOracle = LoanFixtures.charli3(COLLATERAL_TOKEN, COLLATERAL_ORACLE_NFT,
                "11".repeat(28), OraclePriceFeed.priceDataCharlie(COLLATERAL_TOKEN,
                        BigInteger.ONE, BigInteger.ONE, 0L, 10_000_000L),
                input("22"), input("33"), input("44"));
        // ONLY the collateral oracle is registered — no entry for the principal.
        FakeOracleClient client = new FakeOracleClient(collateralOracle);
        LiquidationReadinessController controller = controllerWith(client, LoanFixtures.registry());

        assertNull(controller.advanceAmount(loan, bond, 1_000L),
                "a missing principal oracle must read UNKNOWN (null), never fall back to ada pricing");
    }

    private static TransactionInput input(String prefix) {
        return LoanFixtures.input(prefix.repeat(32), 0);
    }
}
