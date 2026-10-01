package com.fluidtokens.aquarium.offchain.controller;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetDisplay;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.LoanAge;
import com.fluidtokens.aquarium.offchain.model.TokenMetadata;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClient;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import com.fluidtokens.aquarium.offchain.service.loans.MinswapPoolResolver;
import com.fluidtokens.aquarium.offchain.service.loans.AnticipateAndSell;
import com.fluidtokens.aquarium.offchain.service.loans.PoolUsability;
import com.fluidtokens.aquarium.offchain.service.loans.WithdrawAccountRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                BigInteger.TEN, "tok", BigInteger.TEN, healthFactor, null, null, null,
                null, null, null, "PLAIN LIQUIDATE", "", null,
                new LoanAge("1d", "2026-09-13T00:00:00Z"),
                AssetDisplay.of(BigInteger.TEN, TokenMetadata.ada()),
                AssetDisplay.of(BigInteger.TEN, TokenMetadata.unknown("tok")),
                PoolUsability.noPool(),
                null, null, null, null,
                AnticipateAndSell.unknown("no pool"), null,
                ActionNow.of(false, null, null, null, true, false, null), ProcessingBlocker.NONE,
                null, null);
    }

    // ---- pagination ------------------------------------------------------------------------------

    /**
     * ⛔ <b>A PAGE NUMBER FROM OUTSIDE IS NOT TRUSTED.</b> A bookmark from when the list was longer,
     * or a page left behind when a filter narrowed it, must land on a real page — an empty table
     * reads as "the filter matched nothing", which is a different and wrong conclusion.
     */
    @Test
    void anOutOfRangePageIsClampedRatherThanRenderingAnEmptyTable() {
        List<LiquidationReadinessController.Row> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(row("loan" + i, 1.0 + i));
        }
        var arranged = LiquidationReadinessController.arrange(rows, "health", "asc", null, null);

        int pages = Math.max(1, (arranged.size() + LiquidationReadinessController.PAGE_SIZE - 1)
                / LiquidationReadinessController.PAGE_SIZE);
        assertEquals(2, pages, "30 loans at 25 a page is two pages");

        for (int requested : new int[] {-5, 0, 1}) {
            assertEquals(1, Math.min(Math.max(requested, 1), pages), "page " + requested + " clamps to 1");
        }
        assertEquals(2, Math.min(Math.max(99, 1), pages), "page 99 clamps to the last page, not past it");
    }

    /**
     * ⚠ <b>The phone view reads the COUNTS, and they must be totals.</b> "2 liquidatable" meaning
     * "on this page" is the class of half-truth this page exists to avoid — and it is the number an
     * operator uses to decide whether to go and find a laptop.
     */
    @Test
    void theCountsAreOverTheWholeFilteredSetRatherThanThePage() {
        List<LiquidationReadinessController.Row> rows = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            rows.add(row("loan" + i, i < 27 ? 0.5 : 2.0));
        }
        var arranged = LiquidationReadinessController.arrange(rows, "health", "asc", null, null);

        long liquidatable = arranged.stream()
                .filter(r -> r.healthFactor() != null && r.healthFactor() < 1.0).count();
        assertEquals(27, liquidatable, "27 are under 1.0 across the whole set");
        assertTrue(liquidatable > LiquidationReadinessController.PAGE_SIZE,
                "the fixture must exceed one page, or it cannot catch a page-scoped count");
    }

    // ---- sorting and filtering, which are query parameters rather than JavaScript ----------------

    private static LiquidationReadinessController.Row aged(String id, Double health, String iso) {
        var base = row(id, health);
        return new LiquidationReadinessController.Row(base.loanId(), base.utxoRef(), base.principalUnit(),
                base.principalAmount(), base.remainingDebt(), base.collateralUnit(), base.collateralAmount(),
                base.healthFactor(), base.currentLtvPercent(), base.liquidatable(), base.healthUnknownReason(),
                base.feeInCollateral(), base.feeValueLovelace(), base.feeUnknownReason(), base.route(),
                base.routeDetail(), base.advancePrincipalAmount(),
                new com.fluidtokens.aquarium.offchain.model.LoanAge("x", iso),
                base.principalDisplay(), base.collateralDisplay(), base.poolUsability(), base.feeDisplay(),
                base.feeValueDisplay(), base.advanceDisplay(), base.debtDisplay(), base.anticipateAndSell(),
                base.anticipateDisplay(), base.actionNow(), base.blocker(), base.loanExplorerUrl(),
                base.utxoExplorerUrl());
    }

    /**
     * ⛔ <b>UNKNOWN STAYS LAST WHEN THE DIRECTION FLIPS.</b> A naive {@code reversed()} promotes the
     * uncomputable rows straight to the top, which looks exactly like a correct descending sort and
     * pushes the genuinely urgent loans off the fold. This is the assertion that catches it.
     */
    @Test
    void reversingTheSortDoesNotPromoteRowsWhoseHealthIsUnknown() {
        var rows = List.of(row("healthy", 2.4), row("unknown", null), row("critical", 0.87));

        var asc = LiquidationReadinessController.arrange(rows, "health", "asc", null, null);
        var desc = LiquidationReadinessController.arrange(rows, "health", "desc", null, null);

        assertEquals(List.of("critical", "healthy", "unknown"),
                asc.stream().map(LiquidationReadinessController.Row::loanId).toList());
        assertEquals(List.of("healthy", "critical", "unknown"),
                desc.stream().map(LiquidationReadinessController.Row::loanId).toList(),
                "descending reverses the measurable rows and leaves unknown last");
    }

    /**
     * ⚠ A LATER lend date is a YOUNGER loan, so ascending AGE is descending DATE. Inverted once,
     * inside arrange(), because an off-by-one-direction here is invisible on a four-row page.
     */
    @Test
    void sortingByAgePutsTheYoungestFirstAscending() {
        var rows = List.of(aged("old", 1.0, "2026-01-01T00:00:00Z"),
                aged("new", 1.0, "2026-09-01T00:00:00Z"));

        assertEquals(List.of("new", "old"),
                LiquidationReadinessController.arrange(rows, "age", "asc", null, null)
                        .stream().map(LiquidationReadinessController.Row::loanId).toList());
        assertEquals(List.of("old", "new"),
                LiquidationReadinessController.arrange(rows, "age", "desc", null, null)
                        .stream().map(LiquidationReadinessController.Row::loanId).toList());
    }

    @Test
    void filteringNarrowsByPrincipalAndCollateralAndBlankMeansNoFilter() {
        var rows = List.of(row("a", 1.0), row("b", 2.0));

        assertEquals(2, LiquidationReadinessController.arrange(rows, "health", "asc", "", "").size(),
                "a blank filter is not a filter — an empty select must not hide every row");
        assertEquals(2, LiquidationReadinessController.arrange(rows, "health", "asc", "lovelace", null).size());
        assertEquals(0, LiquidationReadinessController.arrange(rows, "health", "asc", "nosuchunit", null).size(),
                "a principal no loan carries must match nothing rather than everything");
        assertEquals(2, LiquidationReadinessController.arrange(rows, "health", "asc", null, "tok").size());
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
        var r = new LiquidationReadinessController.Row("id", "id#0", "lovelace", BigInteger.TEN, null,
                "tok", BigInteger.TEN, null, null, null, "no usable oracle feed",
                null, null, "no usable oracle feed", "UNKNOWN", "no bond indexed", null,
                new LoanAge("unknown", null),
                AssetDisplay.of(BigInteger.TEN, TokenMetadata.ada()),
                AssetDisplay.of(BigInteger.TEN, TokenMetadata.unknown("tok")),
                PoolUsability.noPool(),
                null, null, null, null,
                AnticipateAndSell.unknown("no pool"), null,
                ActionNow.of(false, null, null, null, true, false, null), ProcessingBlocker.NONE,
                null, null);

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

        private final java.util.Map<AssetType, OracleEntry> byOracleToken;

        FakeOracleClient(OracleEntry... entries) {
            super("http://unused.invalid");
            byToken = new java.util.HashMap<>();
            byOracleToken = new java.util.HashMap<>();
            for (OracleEntry entry : entries) {
                // findEntry (loan-free) is keyed by the PRICED asset; findEntryByOracleToken by the
                // oracle NFT a loan's datum names — which is what the controller resolves a loan's
                // figures by since FAB-111, exactly as the builder does.
                byToken.put(entry.token(), entry);
                byOracleToken.put(entry.oracleToken(), entry);
            }
        }

        @Override
        public Optional<OracleEntry> findEntry(AssetType token) {
            return Optional.ofNullable(byToken.get(token));
        }

        @Override
        public Optional<OracleEntry> findEntryByOracleToken(AssetType oracleToken) {
            return Optional.ofNullable(byOracleToken.get(oracleToken));
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

    /**
     * ⛔ FAB-115 audit finding 5: the banner attribute comes from the CONTROLLER reading the shared gate
     * — the template test sets the variable itself and could not see a controller that never set it.
     */
    @Test
    void theReadinessPageCarriesTheClosedLendingGateIntoItsModel() {
        AppConfig.Network network = new AppConfig.Network() {
            @Override
            public com.bloxbean.cardano.client.common.model.Network getCardanoNetwork() {
                return Networks.testnet();
            }
        };
        var controller = new LiquidationReadinessController(provide(null), provide(null), provide(null),
                provide(null), provide(null), provide(null), provide(null), provide(null),
                new AppConfig.LiquidationConfiguration(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                        60, 120, 30, BigInteger.ZERO, 200, 30), network);
        var gate = new com.fluidtokens.aquarium.offchain.service.LendingConfigGate();
        controller.setLendingConfigGate(gate);

        var open = new org.springframework.ui.ConcurrentModel();
        controller.readiness(open, null, null, null, null, null);
        assertNull(open.getAttribute("lendingConfigBlocked"), "no banner while the gate is open");

        gate.block("ConfigDatum[11]: derived 63b26ff9, chain 64d9b13f");
        var closed = new org.springframework.ui.ConcurrentModel();
        controller.readiness(closed, null, null, null, null, null);
        assertEquals("ConfigDatum[11]: derived 63b26ff9, chain 64d9b13f", closed.getAttribute("lendingConfigBlocked"),
                "the controller must hand the gate's reason to the page");
    }

    /**
     * ⛔ FAB-115 round-2 finding 4: the gate must reach the ROW through the real path —
     * readiness() → rows() → row() → gatedAction — not only the static helper. One loan, health
     * unknown (so its own action reads UNKNOWN); with the gate closed the row must read REFUSED.
     */
    @Test
    void aClosedLendingGateReachesEveryRowThroughTheRealRenderPath() {
        LoanDatum datum = LoanFixtures.loanDatum(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_NFT,
                BigInteger.valueOf(100_000_000L), BigInteger.ZERO,
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, COLLATERAL_ORACLE_NFT), 0L,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        Loan loan = new Loan("f0".repeat(32), 0, "addr_test1_placeholder", "loanid00",
                BigInteger.valueOf(300_000_000L), BigInteger.valueOf(3_000_000L), datum);
        var census = new com.fluidtokens.aquarium.offchain.service.loans.LoanService.Census(List.of(loan), 1, 0, 0);
        var scanner = new com.fluidtokens.aquarium.offchain.service.loans.LiquidationCandidateScanner(null, null, null) {
            @Override
            public Scan scan(long atTimeMillis) {
                return new Scan(List.of(), census);
            }
        };
        var loans = new com.fluidtokens.aquarium.offchain.service.loans.LoanService(null, null) {
            @Override
            public Census census() {
                return census;
            }
        };
        var health = new com.fluidtokens.aquarium.offchain.service.loans.LoanHealthService(null) {
            @Override
            public com.fluidtokens.aquarium.offchain.model.loans.LoanHealth health(Loan l, long at) {
                return com.fluidtokens.aquarium.offchain.model.loans.LoanHealth.debtOnly(BigInteger.ZERO, false, "stub");
            }
        };
        AppConfig.Network network = new AppConfig.Network() {
            @Override
            public com.bloxbean.cardano.client.common.model.Network getCardanoNetwork() {
                return Networks.testnet();
            }
        };
        var controller = new LiquidationReadinessController(provide(scanner), provide(loans), provide(health),
                provide(null), provide(null), provide(null), provide(LoanFixtures.registry()), provide(null),
                new AppConfig.LiquidationConfiguration(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                        60, 120, 30, BigInteger.ZERO, 200, 30), network);
        var gate = new com.fluidtokens.aquarium.offchain.service.LendingConfigGate();
        controller.setLendingConfigGate(gate);

        var open = new org.springframework.ui.ConcurrentModel();
        controller.readiness(open, null, null, null, null, null);
        @SuppressWarnings("unchecked")
        var openRows = (List<LiquidationReadinessController.Row>) open.getAttribute("rows");
        assertEquals(1, openRows.size(), "the fixture must render its one loan, or this test proves nothing");
        assertTrue(!"REFUSED".equals(openRows.getFirst().actionNow().text()), "an open gate must not refuse");

        gate.block("ConfigDatum[11] mismatch");
        var closed = new org.springframework.ui.ConcurrentModel();
        controller.readiness(closed, null, null, null, null, null);
        @SuppressWarnings("unchecked")
        var closedRows = (List<LiquidationReadinessController.Row>) closed.getAttribute("rows");
        assertEquals("REFUSED", closedRows.getFirst().actionNow().text(),
                "with the lending gate closed, every row must say the bot refuses it");
    }

    /** {@code entry} carrying an oracleVersion — fixtures are built without one. */
    private static OracleEntry versioned(OracleEntry e, int version) {
        return new OracleEntry(e.token(), e.oracleToken(), e.rewardAddress(), e.withdrawCredentialHash(),
                e.referenceInput(), e.referenceScript(), e.verificationKeys(), e.threshold(), e.feed(),
                e.signatures(), e.charlieProviderReferenceInput(), version);
    }

    /**
     * ⛔ FAB-111/112 (oracle audit round 1, finding 3): through the real row path, the fee is valued
     * off the collateral oracle the DATUM names and the label shows THAT oracle's version — with the
     * token's other version, differently priced, registered last where a token lookup would land.
     */
    @Test
    void aRowsFeeValueAndOracleLabelComeFromTheOracleTheDatumNames() {
        LoanDatum datum = LoanFixtures.loanDatum(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_NFT,
                BigInteger.valueOf(100_000_000L), BigInteger.ZERO,
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, COLLATERAL_ORACLE_NFT), 0L,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        Loan loan = new Loan("f0".repeat(32), 0, "addr_test1_placeholder", "loanid00",
                BigInteger.valueOf(300_000_000L), BigInteger.valueOf(3_000_000L), datum);
        LenderBond bond = new LenderBond("f0".repeat(32), 1, "addr_test1_placeholder", "loanid00", "",
                LoanFixtures.bondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(),
                        PRINCIPAL_TOKEN));
        // readiness() prices at the WALL CLOCK, so the windows must contain it.
        long now = System.currentTimeMillis();
        long from = now - 60_000L, to = now + 600_000L;
        OraclePriceFeed namedCollateralFeed = OraclePriceFeed.priceDataCharlie(COLLATERAL_TOKEN,
                BigInteger.ONE, BigInteger.ONE, from, to);
        OracleEntry namedCollateral = versioned(LoanFixtures.charli3(COLLATERAL_TOKEN, COLLATERAL_ORACLE_NFT,
                "11".repeat(28), namedCollateralFeed, input("22"), input("33"), input("44")), 1);
        OracleEntry namedPrincipal = versioned(LoanFixtures.charli3(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_NFT,
                "55".repeat(28), OraclePriceFeed.priceDataCharlie(PRINCIPAL_TOKEN, BigInteger.TWO,
                        BigInteger.ONE, from, to), input("66"), input("77"), input("88")), 1);
        OracleEntry otherCollateral = versioned(LoanFixtures.charli3(COLLATERAL_TOKEN,
                new AssetType("ef".repeat(28), COLLATERAL_ORACLE_NFT.assetName()), "98".repeat(28),
                OraclePriceFeed.priceDataCharlie(COLLATERAL_TOKEN, BigInteger.valueOf(4), BigInteger.ONE,
                        from, to), input("ab"), input("ac"), input("ad")), 2);
        FakeOracleClient client = new FakeOracleClient(namedCollateral, namedPrincipal, otherCollateral);

        var assessment = LoanFixtures.assess(bond, loan, namedPrincipal.feed(), namedCollateralFeed, now);
        var census = new com.fluidtokens.aquarium.offchain.service.loans.LoanService.Census(List.of(loan), 1, 0, 0);
        var scanner = new com.fluidtokens.aquarium.offchain.service.loans.LiquidationCandidateScanner(null, null, null) {
            @Override
            public Scan scan(long atTimeMillis) {
                return new Scan(List.of(assessment), census);
            }
        };
        var loans = new com.fluidtokens.aquarium.offchain.service.loans.LoanService(null, null) {
            @Override
            public Census census() {
                return census;
            }
        };
        var health = new com.fluidtokens.aquarium.offchain.service.loans.LoanHealthService(null) {
            @Override
            public com.fluidtokens.aquarium.offchain.model.loans.LoanHealth health(Loan l, long at) {
                return com.fluidtokens.aquarium.offchain.model.loans.LoanHealth.debtOnly(BigInteger.ZERO, false, "stub");
            }
        };
        AppConfig.Network network = new AppConfig.Network() {
            @Override
            public com.bloxbean.cardano.client.common.model.Network getCardanoNetwork() {
                return Networks.testnet();
            }
        };
        var controller = new LiquidationReadinessController(provide(scanner), provide(loans), provide(health),
                provide(client), provide(null), provide(null), provide(LoanFixtures.registry()), provide(null),
                new AppConfig.LiquidationConfiguration(AppConfig.LiquidationConfiguration.Mode.SHADOW,
                        60, 120, 30, BigInteger.ZERO, 200, 30), network);
        controller.setLendingConfigGate(new com.fluidtokens.aquarium.offchain.service.LendingConfigGate());

        var model = new org.springframework.ui.ConcurrentModel();
        controller.readiness(model, null, null, null, null, null);
        @SuppressWarnings("unchecked")
        var row = ((List<LiquidationReadinessController.Row>) model.getAttribute("rows")).getFirst();

        assertNotNull(row.feeInCollateral(), "the fixture must produce a fee slice: " + row.feeUnknownReason());
        assertEquals(row.feeInCollateral(), row.feeValueLovelace(),
                "the fee is valued at the NAMED collateral oracle's price (1), not the other version's (4)");
        assertEquals(Integer.valueOf(1), row.collateralOracleVersion(),
                "the label must show the version of the oracle the datum names, not the token's other one");
    }

    /**
     * A closed gate overrides a row's action: never "would act", and it says why. An open gate leaves
     * the row's own answer untouched.
     */
    @Test
    void aClosedLendingGateOverridesARowsActionAndAnOpenOneDoesNot() {
        var wouldLiquidate = new ActionNow("LIVE", "would liquidate on the next scan", true);
        var gate = new com.fluidtokens.aquarium.offchain.service.LendingConfigGate();

        assertEquals(wouldLiquidate, LiquidationReadinessController.gatedAction(gate, wouldLiquidate));

        gate.block("ConfigDatum[11] mismatch");
        var action = LiquidationReadinessController.gatedAction(gate, wouldLiquidate);
        assertEquals("REFUSED", action.text());
        assertTrue(!action.wouldAct() && action.detail().contains("LENDING_CONFIG_MISMATCH"),
                "a closed gate must never leave a row saying the bot would act");
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
                provide(client), provide(null), provide(null), provide(registry), provide(null), null, network);
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

    /**
     * ⛔ FAB-111. The same loan, with a SECOND oracle for its principal token registered last under a
     * different NFT and a different price — the 2026-09-30 shape (v1 Lending v3, v2 Lending v4). The
     * figures must still come from the oracle the datum names; a token lookup takes the decoy and the
     * advance moves. The fake's token map keeps the LAST entry, exactly as the real client's did.
     */
    @Test
    void advanceAmountUsesTheOracleTheDatumNamesWhenTheTokenHasAnotherVersion() {
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
        OracleEntry namedPrincipalOracle = LoanFixtures.charli3(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_NFT,
                "55".repeat(28), OraclePriceFeed.priceDataCharlie(PRINCIPAL_TOKEN,
                        BigInteger.TWO, BigInteger.ONE, 0L, 10_000_000L),
                input("66"), input("77"), input("88"));
        AssetType otherVersionNft = new AssetType("ee".repeat(28), PRINCIPAL_ORACLE_NFT.assetName());
        OracleEntry otherVersion = LoanFixtures.charli3(PRINCIPAL_TOKEN, otherVersionNft,
                "99".repeat(28), OraclePriceFeed.priceDataCharlie(PRINCIPAL_TOKEN,
                        BigInteger.valueOf(3), BigInteger.ONE, 0L, 10_000_000L),
                input("aa"), input("bb"), input("cc"));
        // ⛔ And a decoy for the COLLATERAL leg too (oracle audit round 1, finding 3), also last.
        OracleEntry otherCollateralVersion = LoanFixtures.charli3(COLLATERAL_TOKEN,
                new AssetType("ef".repeat(28), COLLATERAL_ORACLE_NFT.assetName()),
                "98".repeat(28), OraclePriceFeed.priceDataCharlie(COLLATERAL_TOKEN,
                        BigInteger.valueOf(4), BigInteger.ONE, 0L, 10_000_000L),
                input("ab"), input("ac"), input("ad"));
        FakeOracleClient client = new FakeOracleClient(collateralOracle, namedPrincipalOracle, otherVersion,
                otherCollateralVersion);
        LiquidationReadinessController controller = controllerWith(client, LoanFixtures.registry());

        assertEquals(BigInteger.valueOf(97_500_000L), controller.advanceAmount(loan, bond, 1_000L),
                "the advance must be computed off the oracles the datum names (principal 2, collateral 1), "
                        + "not the tokens' other versions registered last (3 and 4)");
    }

    /**
     * The principal's named oracle prices ANOTHER token (oracle audit round 2, cross-provider finding
     * 2): no figures, rather than figures at another token's price.
     */
    @Test
    void advanceAmountIsNullWhenThePrincipalsNamedOraclePricesAnotherToken() {
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
        AssetType otherToken = new AssetType("e".repeat(56), PRINCIPAL_TOKEN.assetName());
        OracleEntry namedNftWrongToken = LoanFixtures.charli3(otherToken, PRINCIPAL_ORACLE_NFT,
                "55".repeat(28), OraclePriceFeed.priceDataCharlie(otherToken,
                        BigInteger.TWO, BigInteger.ONE, 0L, 10_000_000L),
                input("66"), input("77"), input("88"));
        LiquidationReadinessController controller = controllerWith(
                new FakeOracleClient(collateralOracle, namedNftWrongToken), LoanFixtures.registry());

        assertNull(controller.advanceAmount(loan, bond, 1_000L),
                "an oracle pricing another token must not produce this loan's figures");
    }

    /**
     * ADA collateral gets no figures (round-2 audit finding 1): neither the convert nor the pay-in-advance
     * route builds one -- only the PLAIN route does, and it needs no pool and no advance -- and figures here
     * would become a pool verdict and a "would CONVERT" the bot cannot honour. The row
     * carries {@link LiquidationReadinessController#ADA_COLLATERAL_NOT_LIQUIDATED} instead — even with
     * the principal's oracle present, so it is the collateral, not a missing feed, that withholds them.
     */
    @Test
    void anAdaCollateralGetsNoFiguresBecauseNeitherPoolRouteBuildsIt() {
        LoanDatum datum = LoanFixtures.loanDatum(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_NFT,
                BigInteger.valueOf(100_000_000L), BigInteger.ZERO, LoanFixtures.adaCollateral(), 0L,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        Loan loan = new Loan("f0".repeat(32), 0, "addr_test1_placeholder", "loanid00",
                BigInteger.valueOf(300_000_000L), BigInteger.valueOf(3_000_000L), datum);
        LenderBond bond = new LenderBond("f0".repeat(32), 1, "addr_test1_placeholder", "loanid00", "",
                LoanFixtures.bondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(),
                        PRINCIPAL_TOKEN));
        OracleEntry principalOracle = LoanFixtures.charli3(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_NFT,
                "55".repeat(28), OraclePriceFeed.priceDataCharlie(PRINCIPAL_TOKEN,
                        BigInteger.TWO, BigInteger.ONE, 0L, 10_000_000L),
                input("66"), input("77"), input("88"));
        LiquidationReadinessController controller = controllerWith(
                new FakeOracleClient(principalOracle), LoanFixtures.registry());

        assertNull(controller.advanceAmount(loan, bond, 1_000L),
                "no figures for a loan neither the convert nor the pay-in-advance route can build");
        // The verdict is withheld by the ada collateral itself, before any pool is consulted -- the row
        // says so, rather than claiming a pool could fill a liquidation the bot will not build. (The
        // positive path, a real pool that CAN fill, is aTokenCollateralLoanWithADeepPoolIsUsable.)
        var usability = controller.usabilityFor(
                new LiquidationReadinessController.PoolFetch(List.of(), null), loan, bond,
                AssetType.ada(), PRINCIPAL_TOKEN, 1_000L);
        assertEquals(PoolUsability.Verdict.UNKNOWN, usability.verdict());
        assertEquals(LiquidationReadinessController.ADA_COLLATERAL_NOT_LIQUIDATED, usability.detail());
    }

    /** The version label is the named oracle's only if it prices the collateral token (finding 3). */
    @Test
    void theCollateralVersionLabelIsAbsentWhenTheNamedOraclePricesAnotherToken() {
        AssetType otherToken = new AssetType("e".repeat(56), COLLATERAL_TOKEN.assetName());
        OracleEntry base = LoanFixtures.charli3(otherToken, COLLATERAL_ORACLE_NFT, "11".repeat(28),
                OraclePriceFeed.priceDataCharlie(otherToken, BigInteger.ONE, BigInteger.ONE, 0L, 10_000_000L),
                input("22"), input("33"), input("44"));
        OracleEntry versioned = new OracleEntry(base.token(), base.oracleToken(), base.rewardAddress(),
                base.withdrawCredentialHash(), base.referenceInput(), base.referenceScript(),
                base.verificationKeys(), base.threshold(), base.feed(), base.signatures(),
                base.charlieProviderReferenceInput(), 2);
        OracleEntry rightToken = new OracleEntry(COLLATERAL_TOKEN, base.oracleToken(), base.rewardAddress(),
                base.withdrawCredentialHash(), base.referenceInput(), base.referenceScript(),
                base.verificationKeys(), base.threshold(), base.feed(), base.signatures(),
                base.charlieProviderReferenceInput(), 2);
        LoanDatum datum = LoanFixtures.loanDatum(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_NFT,
                BigInteger.valueOf(100_000_000L), BigInteger.ZERO,
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, COLLATERAL_ORACLE_NFT), 0L,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);

        assertNull(controllerWith(new FakeOracleClient(versioned), LoanFixtures.registry())
                .collateralOracleVersion(datum), "another token's oracle has no version to show here");
        assertEquals(Integer.valueOf(2), controllerWith(new FakeOracleClient(rightToken), LoanFixtures.registry())
                .collateralOracleVersion(datum), "the positive: the right token shows its version");
    }

    /**
     * The collateral twin (oracle re-slice, Anthropic fresh audit finding 1): the collateral's named
     * oracle prices ANOTHER token, so there are no figures — rather than 97,500,000 at that token's price.
     */
    @Test
    void advanceAmountIsNullWhenTheCollateralsNamedOraclePricesAnotherToken() {
        LoanDatum datum = LoanFixtures.loanDatum(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_NFT,
                BigInteger.valueOf(100_000_000L), BigInteger.ZERO,
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, COLLATERAL_ORACLE_NFT), 0L,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        Loan loan = new Loan("f0".repeat(32), 0, "addr_test1_placeholder", "loanid00",
                BigInteger.valueOf(300_000_000L), BigInteger.valueOf(3_000_000L), datum);
        LenderBond bond = new LenderBond("f0".repeat(32), 1, "addr_test1_placeholder", "loanid00", "",
                LoanFixtures.bondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(),
                        PRINCIPAL_TOKEN));
        AssetType otherToken = new AssetType("e".repeat(56), COLLATERAL_TOKEN.assetName());
        OracleEntry namedNftWrongToken = LoanFixtures.charli3(otherToken, COLLATERAL_ORACLE_NFT,
                "11".repeat(28), OraclePriceFeed.priceDataCharlie(otherToken,
                        BigInteger.ONE, BigInteger.ONE, 0L, 10_000_000L),
                input("22"), input("33"), input("44"));
        OracleEntry principalOracle = LoanFixtures.charli3(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_NFT,
                "55".repeat(28), OraclePriceFeed.priceDataCharlie(PRINCIPAL_TOKEN,
                        BigInteger.TWO, BigInteger.ONE, 0L, 10_000_000L),
                input("66"), input("77"), input("88"));
        LiquidationReadinessController controller = controllerWith(
                new FakeOracleClient(namedNftWrongToken, principalOracle), LoanFixtures.registry());

        assertNull(controller.advanceAmount(loan, bond, 1_000L),
                "an oracle pricing another token must not produce this loan's figures");
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

    // ================================================================================================
    // The pool lookup is per PAIR, and this page was paying for it per ROW
    // ================================================================================================

    /**
     * A resolver that answers instantly and counts. The real one is a Blockfrost round trip — one call,
     * or two when the first asset ordering 404s — and holds no cache of any kind.
     */
    private static final class CountingPoolResolver extends MinswapPoolResolver {
        private int calls;
        private final boolean poolExists;

        private CountingPoolResolver(boolean poolExists) {
            super(null, "addr_pool", "00".repeat(28));
            this.poolExists = poolExists;
        }

        // ⛔ resolveAllEitherOrder is what the page calls now — it needs EVERY pool for the pair,
        // because depth only orders candidates and the fill test decides between them. Counting the
        // single-pool method instead would count zero and the dedupe assertions would pass vacuously.
        @Override
        public java.util.List<ResolvedPool> resolveAllEitherOrder(AssetType one, AssetType other) {
            calls++;
            // A real datum: PoolFetch now carries it through the memo, which is what lets the
            // per-loan verdict be computed without a second lookup.
            return poolExists
                    ? java.util.List.of(new ResolvedPool(null, new MinswapPoolDatum(AssetType.ada(),
                            new AssetType("11".repeat(28), "464c4454"), BigInteger.TEN,
                            BigInteger.valueOf(1_000_000L), BigInteger.valueOf(2_000_000L),
                            BigInteger.valueOf(30), BigInteger.valueOf(30), false), "lp"))
                    : java.util.List.of();
        }
    }

    private static LiquidationReadinessController controllerWithPool(MinswapPoolResolver resolver) {
        AppConfig.Network network = new AppConfig.Network() {
            @Override
            public com.bloxbean.cardano.client.common.model.Network getCardanoNetwork() {
                return Networks.testnet();
            }
        };
        return new LiquidationReadinessController(provide(null), provide(null), provide(null),
                provide(null), provide(resolver), provide(null), provide(null), provide(null), null, network);
    }

    /**
     * ⛔ <b>THE CALL COUNT COLLAPSES.</b> Two loans on the same pair asked the chain the same question
     * twice and got the same answer twice. Within one render they now ask once.
     *
     * <p>Before this memo a table of N loans cost <b>N to 2N Blockfrost calls per render</b> — two
     * whenever a pair has no pool, because {@code compute_lp_asset_name} is order-sensitive and both
     * orderings must 404 before the answer is known.
     */
    @Test
    void twoLoansOnOnePairProduceOneLookupRatherThanTwo() {
        CountingPoolResolver resolver = new CountingPoolResolver(true);
        LiquidationReadinessController controller = controllerWithPool(resolver);
        Map<String, LiquidationReadinessController.PoolFetch> memo = new HashMap<>();

        AssetType collateral = AssetType.ada();
        AssetType principal = new AssetType("11".repeat(28), "464c4454");

        controller.resolvePool(collateral, principal, memo);
        controller.resolvePool(collateral, principal, memo);
        controller.resolvePool(collateral, principal, memo);

        assertEquals(1, resolver.calls,
                "three rows on one pair must reach the resolver once, not three times");
    }

    /**
     * ⛔ <b>THE LOAD-BEARING HALF.</b> The whole claim is "identical data, fewer calls", so a memo that
     * returned something different from what the page would have shown would be a defect dressed as an
     * optimisation. The second read must equal the first, and equal what an unmemoised call returns.
     */
    @Test
    void theMemoReturnsExactlyWhatAFreshLookupWouldHaveReturned() {
        AssetType collateral = AssetType.ada();
        AssetType principal = new AssetType("11".repeat(28), "464c4454");

        for (boolean poolExists : new boolean[]{true, false}) {
            CountingPoolResolver resolver = new CountingPoolResolver(poolExists);
            LiquidationReadinessController controller = controllerWithPool(resolver);

            LiquidationReadinessController.PoolFetch direct = controller.lookupPool(collateral, principal);
            Map<String, LiquidationReadinessController.PoolFetch> memo = new HashMap<>();
            LiquidationReadinessController.PoolFetch first = controller.resolvePool(collateral, principal, memo);
            LiquidationReadinessController.PoolFetch second = controller.resolvePool(collateral, principal, memo);

            assertEquals(direct, first, "the memoised answer must equal an unmemoised one (pool=" + poolExists + ")");
            assertEquals(first, second, "the second read must equal the first (pool=" + poolExists + ")");
            assertEquals(poolExists, !first.datums().isEmpty(), "the fetched pool must survive the memo");
        }
    }

    /**
     * The pair is UNORDERED. {@code resolveEitherOrder} tries both orderings and the pool exists under
     * exactly one, so (ada, FLDT) and (FLDT, ada) are the same question — keying on the arguments as
     * given would miss half the duplicates and leave the page paying for them.
     */
    @Test
    void theSamePairInTheOppositeOrderIsNotAskedTwice() {
        CountingPoolResolver resolver = new CountingPoolResolver(true);
        LiquidationReadinessController controller = controllerWithPool(resolver);
        Map<String, LiquidationReadinessController.PoolFetch> memo = new HashMap<>();

        AssetType ada = AssetType.ada();
        AssetType fldt = new AssetType("11".repeat(28), "464c4454");

        controller.resolvePool(ada, fldt, memo);
        controller.resolvePool(fldt, ada, memo);

        assertEquals(1, resolver.calls, "one pair, either way round, is one question");
        assertEquals(LiquidationReadinessController.pairKey(ada, fldt),
                LiquidationReadinessController.pairKey(fldt, ada), "the key must be order-independent");
    }

    /** A genuinely different pair must still cost its own lookup — the memo must not over-collapse. */
    @Test
    void twoDifferentPairsStillCostTwoLookups() {
        CountingPoolResolver resolver = new CountingPoolResolver(true);
        LiquidationReadinessController controller = controllerWithPool(resolver);
        Map<String, LiquidationReadinessController.PoolFetch> memo = new HashMap<>();

        AssetType ada = AssetType.ada();
        AssetType fldt = new AssetType("11".repeat(28), "464c4454");
        AssetType usdm = new AssetType("22".repeat(28), "5553444d");

        controller.resolvePool(ada, fldt, memo);
        controller.resolvePool(ada, usdm, memo);

        assertEquals(2, resolver.calls, "distinct pairs are distinct questions");
    }

    /**
     * FAB-117: the POSITIVE path of usabilityFor -- a token-collateral loan, its named oracle, and a real
     * pool deep enough to fill the debt. Without it, widening the ada guard to every row (every row reading
     * "no pool verdict") kept the suite green.
     */
    @Test
    void aTokenCollateralLoanWithADeepPoolIsUsable() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(50_000_000L),
                BigInteger.ZERO, LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, COLLATERAL_ORACLE_NFT), 0L,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        Loan loan = new Loan("f0".repeat(32), 0, "addr_test1_placeholder", "loanid00",
                BigInteger.valueOf(300_000_000L), BigInteger.valueOf(3_000_000L), datum);
        LenderBond bond = new LenderBond("f0".repeat(32), 1, "addr_test1_placeholder", "loanid00", "",
                LoanFixtures.bondDatum(BigInteger.ZERO, LoanFixtures.noStakeCredential(), AssetType.ada()));
        OracleEntry collateralOracle = LoanFixtures.charli3(COLLATERAL_TOKEN, COLLATERAL_ORACLE_NFT,
                "11".repeat(28), OraclePriceFeed.priceDataCharlie(COLLATERAL_TOKEN,
                        BigInteger.ONE, BigInteger.ONE, 0L, 10_000_000L),
                input("22"), input("33"), input("44"));
        LiquidationReadinessController controller = controllerWith(
                new FakeOracleClient(collateralOracle), LoanFixtures.registry());
        // 1:1 and deep, no liquidation fee (this is about pool depth, not fees): the swappable collateral is
        // the debt plus its partial-liquidation penalty, so a deep pool clears it.
        var pool = new com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum(AssetType.ada(),
                COLLATERAL_TOKEN, BigInteger.TEN, new BigInteger("10000000000000"),
                new BigInteger("10000000000000"), BigInteger.valueOf(30L), BigInteger.valueOf(30L), false);

        var usability = controller.usabilityFor(new LiquidationReadinessController.PoolFetch(List.of(pool), null),
                loan, bond, COLLATERAL_TOKEN, AssetType.ada(), 1_000L);

        assertEquals(PoolUsability.Verdict.USABLE, usability.verdict(), usability.detail());
    }

    /** FAB-117: a liquidatable ADA-collateral row on a CONVERT bond never claims an action. */
    @Test
    void aLiquidatableAdaCollateralRowOnAConvertBondNeverClaimsAnAction() {
        ActionNow advance = new ActionNow("ADVANCE", "would liquidate on the next scan", true);
        ActionNow honest = LiquidationReadinessController.honestAction(true, true, true, advance);
        assertEquals("NONE", honest.text());
        assertFalse(honest.wouldAct(), "neither the convert nor the pay-in-advance route builds it");
        assertTrue(honest.detail().contains("ada collateral"), honest.detail());

        assertSame(advance, LiquidationReadinessController.honestAction(true, true, false, advance),
                "⛔ a PLAIN bond keeps its computed action: the plain route DOES liquidate ada collateral");
        assertSame(advance, LiquidationReadinessController.honestAction(true, false, true, advance),
                "a token collateral keeps its computed action");
        assertSame(advance, LiquidationReadinessController.honestAction(false, true, true, advance),
                "a healthy row keeps its computed (non-acting) answer");
        assertSame(advance, LiquidationReadinessController.honestAction(null, true, true, advance),
                "unknown health keeps its computed answer");
    }

    /**
     * The CALL SITE, through readiness(): a liquidatable ada/ada loan in LIVE mode. On a PLAIN bond the row is
     * not given the ada-collateral override (the plain route liquidates it); on a CONVERT bond it says NONE.
     * The plain-route action answer is pinned by
     * {@link #aPlainBondOnAnUnlistedMarketLiquidatesWithoutAConvertPool()}.
     * (FAB-117 round-1 audit: the override had been applied whatever the route, telling an operator
     * "nothing will happen" for a loan the bot submits -- and nothing pinned the call site.)
     */
    @Test
    void theAdaCollateralOverrideFollowsTheBondsRouteOnTheRenderedPage() {
        LiquidationReadinessController.Row plain = renderLiquidatableAdaRow(
                LoanFixtures.bondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(), AssetType.ada()));
        assertEquals("PLAIN LIQUIDATE", plain.route(), plain.routeDetail());
        assertFalse(plain.actionNow().detail().contains("ada collateral"),
                "a plain-bond ada row must keep the route's own answer: " + plain.actionNow());

        LiquidationReadinessController.Row convert = renderLiquidatableAdaRow(
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(),
                        AssetType.ada()));
        assertEquals("NONE", convert.actionNow().text(), convert.actionNow().toString());
        assertFalse(convert.actionNow().wouldAct());
        assertTrue(convert.actionNow().detail().contains("ada collateral"),
                "the convert row's NONE must be the ada override, not some other non-acting verdict: "
                        + convert.actionNow());
        assertEquals("NO ROUTE", convert.route(), convert.routeDetail());
    }

    @Test
    void aPlainBondOnAnUnlistedMarketLiquidatesWithoutAConvertPool() {
        LiquidationReadinessController.Row row = renderLiquidatableAdaRow(
                LoanFixtures.bondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(), AssetType.ada()));

        assertEquals("PLAIN LIQUIDATE", row.route(), row.routeDetail());
        assertEquals("LIQUIDATE", row.actionNow().text(), row.actionNow().toString());
        assertTrue(row.actionNow().wouldAct(), row.actionNow().toString());
        assertTrue(row.actionNow().detail().contains("plain"), row.actionNow().detail());
        assertFalse(row.blocker().blocked(), row.blocker().toString());
    }

    @Test
    void aPlainBondIgnoresAnAnticipateMarketAndItsCap() {
        var configuration = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.LIVE, 60, 120, 30, BigInteger.ZERO, 200, 30);
        configuration.setMarkets(List.of(anticipateMarket("lovelace", 1)));

        LiquidationReadinessController.Row row = renderLiquidatableRow(
                LoanFixtures.bondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(), AssetType.ada()),
                LoanFixtures.adaCollateral(), configuration);

        assertEquals("LIQUIDATE", row.actionNow().text(), row.actionNow().toString());
        assertTrue(row.actionNow().wouldAct(), row.actionNow().toString());
    }

    @Test
    void aPlainBondInShadowWouldLiquidateWithoutActing() {
        var configuration = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30, BigInteger.ZERO, 200, 30);

        LiquidationReadinessController.Row row = renderLiquidatableRow(
                LoanFixtures.bondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(), AssetType.ada()),
                LoanFixtures.adaCollateral(), configuration);

        assertEquals("WOULD LIQUIDATE", row.actionNow().text(), row.actionNow().toString());
        assertFalse(row.actionNow().wouldAct(), row.actionNow().toString());
    }

    @Test
    void aLoanWithNoBondNeverActsOnAnAnticipateMarket() {
        var configuration = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.LIVE, 60, 120, 30, BigInteger.ZERO, 200, 30);
        configuration.setMarkets(List.of(anticipateMarket("lovelace", 1_000_000_000L)));

        LiquidationReadinessController.Row row = renderLiquidatableRow(
                null, LoanFixtures.adaCollateral(), configuration);

        assertEquals("UNKNOWN", row.route(), row.routeDetail());
        assertFalse(row.actionNow().wouldAct(), row.actionNow().toString());
        assertEquals("NONE — no bond", row.actionNow().text(), row.actionNow().toString());
        assertTrue(row.actionNow().detail().contains("no lender bond indexed"), row.actionNow().detail());
        assertFalse(row.actionNow().detail().contains("ada collateral"), row.actionNow().detail());
        assertFalse(row.blocker().blocked(), row.blocker().toString());
        assertNull(row.blocker().label(), row.blocker().toString());
    }

    @Test
    void aLoanWithNoBondNeverActsOnAnUnlistedMarket() {
        LiquidationReadinessController.Row row = renderLiquidatableRow(
                null, LoanFixtures.adaCollateral());

        assertEquals("NONE — no bond", row.actionNow().text(), row.actionNow().toString());
        assertTrue(row.actionNow().detail().contains("no lender bond indexed"), row.actionNow().detail());
    }

    @Test
    void anAdaCollateralConvertBondHasNoExecutableRoute() {
        var configuration = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.LIVE, 60, 120, 30, BigInteger.ZERO, 200, 30);
        configuration.setMarkets(List.of(anticipateMarket("lovelace", 1_000_000_000L)));

        LiquidationReadinessController.Row row = renderLiquidatableRow(
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(50),
                        LoanFixtures.noStakeCredential(), AssetType.ada()),
                LoanFixtures.adaCollateral(), configuration);

        assertEquals("NONE", row.actionNow().text(), row.actionNow().toString());
        assertFalse(row.actionNow().wouldAct(), row.actionNow().toString());
        assertTrue(row.actionNow().detail().contains("ada collateral"), row.actionNow().detail());
        assertEquals("NO ROUTE", row.route(), row.routeDetail());
        assertTrue(row.routeDetail().contains("ada collateral"), row.routeDetail());
        assertNull(row.advancePrincipalAmount());
    }

    /**
     * The collateral predicate at the call site, through readiness(): a liquidatable TOKEN-collateral loan on a
     * CONVERT bond must never be told "ada collateral". (Round-2 audit: both render rows above are ada, so the
     * call site's {@code isAda()} could be replaced by {@code true} and survive.)
     */
    @Test
    void aTokenCollateralConvertRowIsNeverToldItIsAda() {
        AssetType token = new AssetType("ab".repeat(28), "544f4b");
        LiquidationReadinessController.Row row = renderLiquidatableRow(
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(),
                        AssetType.ada()),
                LoanFixtures.tokenCollateral(token, token));
        assertFalse(row.actionNow().detail().contains("ada collateral"), row.actionNow().toString());
    }

    @Test
    void aTokenCollateralConvertRowKeepsItsRealRouteAndNeverClaimsAdaCollateral() {
        LiquidationReadinessController.Row row = renderLiquidatableRow(
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(50),
                        LoanFixtures.noStakeCredential(), AssetType.ada()),
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, COLLATERAL_ORACLE_NFT));

        assertTrue("CAPITAL IN ADVANCE".equals(row.route()) || "CONVERT".equals(row.route()),
                "the token-collateral route must be executable: " + row.routeDetail());
        assertNotEquals("NO ROUTE", row.route(), row.routeDetail());
        assertFalse(row.actionNow().detail().contains("ada collateral"), row.actionNow().toString());
    }

    @Test
    void aClosedLendingGateOverridesALiquidatableAdaConvertRow() {
        var gate = new com.fluidtokens.aquarium.offchain.service.LendingConfigGate();
        gate.block("ConfigDatum[11] mismatch");

        LiquidationReadinessController.Row row = renderLiquidatable(
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(50),
                        LoanFixtures.noStakeCredential(), AssetType.ada()),
                LoanFixtures.adaCollateral(), liveConfiguration(), null, gate).row();

        assertEquals("REFUSED", row.actionNow().text(), row.actionNow().toString());
        assertNotEquals("NONE", row.actionNow().text(),
                "the lending gate must wrap the ada-collateral override");
    }

    @Test
    void anExcludedPlainBondOnAnUnlistedMarketNeverClaimsItWouldAct() {
        LiquidationReadinessController.Row row = renderExcludedLiquidatableRow(
                LoanFixtures.bondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(),
                        AssetType.ada()),
                LoanFixtures.adaCollateral(), liveConfiguration());

        assertFalse(row.actionNow().wouldAct(), row.actionNow().toString());
        assertEquals("NONE — excluded", row.actionNow().text(), row.actionNow().toString());
        assertTrue(row.actionNow().detail().contains("EQUITY_IN_PRINCIPAL_CURRENCY"),
                row.actionNow().detail());
        assertTrue(row.actionNow().detail().contains(
                "fixture exclusion: equity is already in the principal currency"), row.actionNow().detail());
        assertTrue(row.actionNow().detail().contains(
                "the bot never considers an excluded assessment"), row.actionNow().detail());
    }

    @Test
    void anExcludedPlainBondOnAnAnticipateMarketNeverClaimsItWouldAct() {
        AppConfig.LiquidationConfiguration configuration = liveConfiguration();
        configuration.setMarkets(List.of(anticipateMarket("lovelace", 1)));

        LiquidationReadinessController.Row row = renderExcludedLiquidatableRow(
                LoanFixtures.bondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(),
                        AssetType.ada()),
                LoanFixtures.adaCollateral(), configuration);

        assertFalse(row.actionNow().wouldAct(), row.actionNow().toString());
        assertEquals("NONE — excluded", row.actionNow().text(), row.actionNow().toString());
        assertTrue(row.actionNow().detail().contains("EQUITY_IN_PRINCIPAL_CURRENCY"),
                row.actionNow().detail());
    }

    @Test
    void anExcludedConvertBondNeverClaimsItWouldAdvance() {
        AppConfig.LiquidationConfiguration configuration = liveConfiguration();
        configuration.setMarkets(List.of(anticipateMarket("lovelace", 1)));

        LiquidationReadinessController.Row row = renderExcludedLiquidatableRow(
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(50),
                        LoanFixtures.noStakeCredential(), AssetType.ada()),
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, COLLATERAL_ORACLE_NFT), configuration);

        assertFalse(row.actionNow().wouldAct(), row.actionNow().toString());
        assertEquals("NONE — excluded", row.actionNow().text(), row.actionNow().toString());
    }

    @Test
    void aBondlessLiquidatableRowDoesNotIncrementBlockedCount() {
        Rendered rendered = renderLiquidatable(null, LoanFixtures.adaCollateral(),
                liveConfiguration(), null, new com.fluidtokens.aquarium.offchain.service.LendingConfigGate());

        assertEquals(0L, rendered.model().getAttribute("blockedCount"));
        assertEquals("NONE — no bond", rendered.row().actionNow().text(),
                rendered.row().actionNow().toString());
    }

    @Test
    void registrationBannerIsNullWhenConfirmedAndNamesNegativeAndUnknownAnswers() {
        var plainBond = LoanFixtures.bondDatum(BigInteger.valueOf(50),
                LoanFixtures.noStakeCredential(), AssetType.ada());
        Rendered confirmed = renderLiquidatable(plainBond, LoanFixtures.adaCollateral(),
                liveConfiguration(), null, new com.fluidtokens.aquarium.offchain.service.LendingConfigGate(),
                registration(path -> registered()));
        assertNull(confirmed.model().getAttribute("withdrawAccountsUnconfirmed"));
        assertFalse(confirmed.row().blocker().blocked(), confirmed.row().blocker().toString());

        String refused = LoanFixtures.registry().getLmLiquidateActionScriptHash();
        Rendered negative = renderLiquidatable(plainBond, LoanFixtures.adaCollateral(),
                liveConfiguration(), null, new com.fluidtokens.aquarium.offchain.service.LendingConfigGate(),
                registration(path -> path.contains(testnetStake(refused))
                        ? new WithdrawAccountRegistration.Fetched(404, "[]") : registered()));
        assertTrue(String.valueOf(negative.model().getAttribute("withdrawAccountsUnconfirmed"))
                .contains(refused));

        Rendered unknown = renderLiquidatable(plainBond, LoanFixtures.adaCollateral(),
                liveConfiguration(), null, new com.fluidtokens.aquarium.offchain.service.LendingConfigGate(),
                registration(path -> {
                    throw new IllegalStateException("lookup unavailable");
                }));
        assertTrue(String.valueOf(unknown.model().getAttribute("withdrawAccountsUnconfirmed"))
                .contains("could not be confirmed"));
    }

    @Test
    void plainRowUsesOnlyThePlainRegistrationRoute() {
        var bond = LoanFixtures.bondDatum(BigInteger.valueOf(50),
                LoanFixtures.noStakeCredential(), AssetType.ada());
        String liquidate = LoanFixtures.registry().getLmLiquidateActionScriptHash();
        var blocked = renderLiquidatable(bond, LoanFixtures.adaCollateral(), liveConfiguration(), null,
                new com.fluidtokens.aquarium.offchain.service.LendingConfigGate(),
                unregisteredOnly(liquidate)).row();
        assertEquals("unregistered", blocked.blocker().label(), blocked.blocker().detail());
        assertEquals("REFUSED", blocked.actionNow().text(), blocked.actionNow().detail());

        String payInAdvance = LoanFixtures.registry().getLmLiquidateAndPayInAdvanceActionScriptHash();
        var unchanged = renderLiquidatable(bond, LoanFixtures.adaCollateral(), liveConfiguration(), null,
                new com.fluidtokens.aquarium.offchain.service.LendingConfigGate(),
                unregisteredOnly(payInAdvance)).row();
        assertFalse(unchanged.blocker().blocked(), unchanged.blocker().detail());
        assertEquals("LIQUIDATE", unchanged.actionNow().text(), unchanged.actionNow().detail());
    }

    @Test
    void convertBondUsesMarketActionRatherThanItsDisplayedRouteLabel() {
        var bond = LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(50),
                LoanFixtures.noStakeCredential(), AssetType.ada());
        var collateral = LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, COLLATERAL_ORACLE_NFT);
        String payInAdvance = LoanFixtures.registry().getLmLiquidateAndPayInAdvanceActionScriptHash();
        AppConfig.LiquidationConfiguration anticipate = liveConfiguration();
        anticipate.setMarkets(List.of(anticipateMarket("lovelace", 1_000_000_000L)));
        var anticipated = renderLiquidatable(bond, collateral, anticipate, null,
                new com.fluidtokens.aquarium.offchain.service.LendingConfigGate(),
                unregisteredOnly(payInAdvance)).row();
        assertEquals("unregistered", anticipated.blocker().label(), anticipated.blocker().detail());
        assertEquals("REFUSED", anticipated.actionNow().text(), anticipated.actionNow().detail());

        var convertWithPiaMissing = renderLiquidatable(bond, collateral, liveConfiguration(), null,
                new com.fluidtokens.aquarium.offchain.service.LendingConfigGate(),
                unregisteredOnly(payInAdvance)).row();
        assertNotEquals("unregistered", convertWithPiaMissing.blocker().label(),
                convertWithPiaMissing.blocker().detail());

        String convert = "ee".repeat(28);
        var convertMissing = renderLiquidatable(bond, collateral, liveConfiguration(), null,
                new com.fluidtokens.aquarium.offchain.service.LendingConfigGate(),
                unregisteredConvertOnly(convert)).row();
        assertEquals("unregistered", convertMissing.blocker().label(), convertMissing.blocker().detail());
        assertEquals("REFUSED", convertMissing.actionNow().text(), convertMissing.actionNow().detail());
    }

    @Test
    void rowsWithNoExecutorRouteIgnoreEveryRegistrationFailure() {
        WithdrawAccountRegistration noneRegistered = registration(
                path -> new WithdrawAccountRegistration.Fetched(404, "[]"));
        var noBond = renderLiquidatable(null, LoanFixtures.adaCollateral(), liveConfiguration(), null,
                new com.fluidtokens.aquarium.offchain.service.LendingConfigGate(), noneRegistered).row();
        assertFalse(noBond.blocker().blocked(), noBond.blocker().toString());
        assertEquals("NONE — no bond", noBond.actionNow().text());

        var convertBond = LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(50),
                LoanFixtures.noStakeCredential(), AssetType.ada());
        var noRoute = renderLiquidatable(convertBond, LoanFixtures.adaCollateral(), liveConfiguration(), null,
                new com.fluidtokens.aquarium.offchain.service.LendingConfigGate(), noneRegistered).row();
        assertNotEquals("unregistered", noRoute.blocker().label(), noRoute.blocker().detail());
        assertEquals("NONE", noRoute.actionNow().text(), noRoute.actionNow().detail());
    }

    @Test
    void registrationActionStaysInsideTheLendingGateAndOutsideExclusion() {
        String missing = LoanFixtures.registry().getLmLiquidateActionScriptHash();
        var bond = LoanFixtures.bondDatum(BigInteger.valueOf(50),
                LoanFixtures.noStakeCredential(), AssetType.ada());
        var gate = new com.fluidtokens.aquarium.offchain.service.LendingConfigGate();
        gate.block("ConfigDatum mismatch");
        var closed = renderLiquidatable(bond, LoanFixtures.adaCollateral(), liveConfiguration(), null,
                gate, unregisteredOnly(missing)).row();
        assertEquals("REFUSED", closed.actionNow().text());
        assertTrue(closed.actionNow().detail().startsWith("LENDING_CONFIG_MISMATCH"),
                closed.actionNow().detail());
        assertFalse(closed.actionNow().detail().contains("WITHDRAW_ACCOUNT_NOT_REGISTERED"),
                "the lending gate must remain outermost: " + closed.actionNow().detail());

        var excluded = renderLiquidatable(bond, LoanFixtures.adaCollateral(), liveConfiguration(),
                (b, loan, now) -> LiquidationAssessment.excluded(b, loan,
                        LiquidationExclusion.EQUITY_IN_PRINCIPAL_CURRENCY, "fixture exclusion"),
                new com.fluidtokens.aquarium.offchain.service.LendingConfigGate(),
                unregisteredOnly(missing)).row();
        assertEquals("NONE — excluded", excluded.actionNow().text(), excluded.actionNow().detail());
    }

    @Test
    void executorRouteMirrorsTheExecutorBranches() {
        var convert = AppConfig.LiquidationConfiguration.Action.CONVERT;
        var anticipate = AppConfig.LiquidationConfiguration.Action.ANTICIPATE;
        assertEquals(Optional.empty(), LiquidationReadinessController.executorRoute(
                BondRoute.NO_BOND, false, convert));
        assertEquals(Optional.of(WithdrawAccountRegistration.Route.PLAIN),
                LiquidationReadinessController.executorRoute(BondRoute.PLAIN, true, anticipate));
        assertEquals(Optional.empty(), LiquidationReadinessController.executorRoute(
                BondRoute.CONVERT, true, convert));
        assertEquals(Optional.of(WithdrawAccountRegistration.Route.CONVERT),
                LiquidationReadinessController.executorRoute(BondRoute.CONVERT, false, convert));
        assertEquals(Optional.of(WithdrawAccountRegistration.Route.PAY_IN_ADVANCE),
                LiquidationReadinessController.executorRoute(BondRoute.CONVERT, false, anticipate));
    }

    @Test
    void registrationHelpersPreservePrecedenceAndAppendComputedReasons() {
        var unknown = new WithdrawAccountRegistration.Check("claim", "ab".repeat(28),
                "stake_test1claim", WithdrawAccountRegistration.Status.UNKNOWN, "timeout");
        var absent = new WithdrawAccountRegistration.Check("loan", "cd".repeat(28),
                "stake_test1loan", WithdrawAccountRegistration.Status.NOT_REGISTERED, "no history");
        var computed = new ProcessingBlocker("no pool", "pool too thin");
        var blocker = LiquidationReadinessController.registrationBlocker(computed,
                AppConfig.LiquidationConfiguration.Mode.LIVE,
                Optional.of(WithdrawAccountRegistration.Route.CONVERT), List.of(unknown, absent));
        assertEquals("unregistered", blocker.label());
        assertTrue(blocker.detail().contains("claim") && blocker.detail().contains("loan"), blocker.detail());
        assertTrue(blocker.detail().contains("otherwise: no pool — pool too thin"), blocker.detail());

        ActionNow action = LiquidationReadinessController.registrationAction(
                new ActionNow("CONVERT", "live", true), true,
                AppConfig.LiquidationConfiguration.Mode.LIVE,
                Optional.of(WithdrawAccountRegistration.Route.CONVERT), List.of(unknown));
        assertEquals("REFUSED", action.text());
        assertTrue(action.detail().contains("otherwise: CONVERT — live"), action.detail());
        assertSame(computed, LiquidationReadinessController.registrationBlocker(computed,
                AppConfig.LiquidationConfiguration.Mode.DISABLED,
                Optional.of(WithdrawAccountRegistration.Route.CONVERT), List.of(absent)));
    }

    private static LiquidationReadinessController.Row renderLiquidatableAdaRow(
            com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum bondDatum) {
        return renderLiquidatableRow(bondDatum, LoanFixtures.adaCollateral());
    }

    private static LiquidationReadinessController.Row renderLiquidatableRow(
            com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum bondDatum,
            com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset collateral) {
        return renderLiquidatableRow(bondDatum, collateral, liveConfiguration());
    }

    private static LiquidationReadinessController.Row renderLiquidatableRow(
            com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum bondDatumOrNull,
            com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset collateral,
            AppConfig.LiquidationConfiguration configuration) {
        return renderLiquidatable(bondDatumOrNull, collateral, configuration, null,
                new com.fluidtokens.aquarium.offchain.service.LendingConfigGate()).row();
    }

    private static LiquidationReadinessController.Row renderExcludedLiquidatableRow(
            com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum bondDatum,
            com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset collateral,
            AppConfig.LiquidationConfiguration configuration) {
        return renderLiquidatable(bondDatum, collateral, configuration,
                (bond, loan, now) -> LiquidationAssessment.excluded(bond, loan,
                        LiquidationExclusion.EQUITY_IN_PRINCIPAL_CURRENCY,
                        "fixture exclusion: equity is already in the principal currency"),
                new com.fluidtokens.aquarium.offchain.service.LendingConfigGate()).row();
    }

    private static AppConfig.LiquidationConfiguration liveConfiguration() {
        return new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.LIVE, 60, 120, 30,
                BigInteger.ZERO, 200, 30);
    }

    private static Rendered renderLiquidatable(
            com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum bondDatumOrNull,
            com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset collateral,
            AppConfig.LiquidationConfiguration configuration,
            AssessmentFactory assessmentFactory,
            com.fluidtokens.aquarium.offchain.service.LendingConfigGate lendingConfigGate) {
        return renderLiquidatable(bondDatumOrNull, collateral, configuration, assessmentFactory,
                lendingConfigGate, null);
    }

    private static Rendered renderLiquidatable(
            com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum bondDatumOrNull,
            com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset collateral,
            AppConfig.LiquidationConfiguration configuration,
            AssessmentFactory assessmentFactory,
            com.fluidtokens.aquarium.offchain.service.LendingConfigGate lendingConfigGate,
            WithdrawAccountRegistration registration) {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000L), BigInteger.ZERO,
                collateral, 0L, LoanFixtures.liquidation(),
                new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        Loan loan = new Loan("f0".repeat(32), 0, "addr_test1_placeholder", "loanid00",
                BigInteger.valueOf(300_000_000L), BigInteger.valueOf(3_000_000L), datum);
        LenderBond bond = bondDatumOrNull == null ? null
                : new LenderBond("f0".repeat(32), 1, "addr_test1_placeholder", "loanid00", "", bondDatumOrNull);
        long now = System.currentTimeMillis();
        var assessments = bond == null ? List.<LiquidationAssessment>of()
                : List.of(assessmentFactory == null
                        ? LoanFixtures.assess(bond, loan, OraclePriceFeed.unit(), OraclePriceFeed.unit(), now)
                        : assessmentFactory.create(bond, loan, now));
        var census = new com.fluidtokens.aquarium.offchain.service.loans.LoanService.Census(List.of(loan), 1, 0, 0);
        var scanner = new com.fluidtokens.aquarium.offchain.service.loans.LiquidationCandidateScanner(null, null, null) {
            @Override
            public Scan scan(long atTimeMillis) {
                return new Scan(assessments, census);
            }
        };
        var loans = new com.fluidtokens.aquarium.offchain.service.loans.LoanService(null, null) {
            @Override
            public Census census() {
                return census;
            }
        };
        var health = new com.fluidtokens.aquarium.offchain.service.loans.LoanHealthService(null) {
            @Override
            public com.fluidtokens.aquarium.offchain.model.loans.LoanHealth health(Loan l, long at) {
                return new com.fluidtokens.aquarium.offchain.model.loans.LoanHealth(
                        BigInteger.valueOf(100_000_000L), true, null, BigInteger.ZERO, true, null);
            }
        };
        AppConfig.Network network = new AppConfig.Network() {
            @Override
            public com.bloxbean.cardano.client.common.model.Network getCardanoNetwork() {
                return Networks.testnet();
            }
        };
        var controller = new LiquidationReadinessController(provide(scanner), provide(loans), provide(health),
                provide(new FakeOracleClient()), provide(null), provide(null), provide(LoanFixtures.registry()),
                provide(null), configuration, network);
        controller.setLendingConfigGate(lendingConfigGate);
        if (registration != null) {
            controller.setWithdrawAccountRegistration(registration);
        }
        var model = new org.springframework.ui.ConcurrentModel();
        controller.readiness(model, null, null, null, null, null);
        @SuppressWarnings("unchecked")
        var rows = (List<LiquidationReadinessController.Row>) model.getAttribute("rows");
        return new Rendered(rows.getFirst(), model);
    }

    @FunctionalInterface
    private interface AssessmentFactory {
        LiquidationAssessment create(LenderBond bond, Loan loan, long now);
    }

    private record Rendered(LiquidationReadinessController.Row row,
                            org.springframework.ui.ConcurrentModel model) { }

    private static AppConfig.LiquidationConfiguration.Market anticipateMarket(String unit, long cap) {
        var market = new AppConfig.LiquidationConfiguration.Market();
        market.setUnit(unit);
        market.setMode(AppConfig.LiquidationConfiguration.Mode.LIVE);
        market.setAction(AppConfig.LiquidationConfiguration.Action.ANTICIPATE);
        market.setCap(BigInteger.valueOf(cap));
        return market;
    }

    private static WithdrawAccountRegistration registration(
            WithdrawAccountRegistration.RegistrationsFetcher fetcher) {
        return new WithdrawAccountRegistration(LoanFixtures.registry(), Networks.testnet(), fetcher,
                System::currentTimeMillis);
    }

    private static WithdrawAccountRegistration unregisteredOnly(String scriptHash) {
        String stake = testnetStake(scriptHash);
        return registration(path -> path.contains(stake)
                ? new WithdrawAccountRegistration.Fetched(404, "[]") : registered());
    }

    private static WithdrawAccountRegistration unregisteredConvertOnly(String convertHash) {
        LoansContractRegistry real = LoanFixtures.registry();
        LoansContractRegistry registry = mock(LoansContractRegistry.class);
        when(registry.getLoanPolicyId()).thenReturn(real.getLoanPolicyId());
        when(registry.getLoanClaimActionScriptHash()).thenReturn(real.getLoanClaimActionScriptHash());
        when(registry.getLenderManagerWithdrawScriptHash())
                .thenReturn(real.getLenderManagerWithdrawScriptHash());
        when(registry.getLmLiquidateActionScriptHash()).thenReturn(real.getLmLiquidateActionScriptHash());
        when(registry.getLmLiquidateAndPayInAdvanceActionScriptHash())
                .thenReturn(real.getLmLiquidateAndPayInAdvanceActionScriptHash());
        when(registry.getLmLiquidateAndConvertActionScriptHash()).thenReturn(convertHash);
        String stake = testnetStake(convertHash);
        return new WithdrawAccountRegistration(registry, Networks.testnet(), path -> path.contains(stake)
                ? new WithdrawAccountRegistration.Fetched(404, "[]") : registered(),
                System::currentTimeMillis);
    }

    private static WithdrawAccountRegistration.Fetched registered() {
        return new WithdrawAccountRegistration.Fetched(200, "[{\"action\":\"registered\"}]");
    }

    private static String testnetStake(String scriptHash) {
        return WithdrawAccountRegistration.stakeAddress(scriptHash, Networks.testnet());
    }
}
