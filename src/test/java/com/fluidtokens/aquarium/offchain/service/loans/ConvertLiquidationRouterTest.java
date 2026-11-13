package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The convert router's WALLET NOMINATION seam, driven at the router rather than through a fake.</b>
 *
 * <p>Audit finding F1: {@code WalletInputTooSmallException} was a decorative guard. The executor's
 * catch for it was pinned by nothing (mutant M12 — neuter the catch so it falls to the (then
 * 30-minute) machinery quarantine — killed <b>0 of 1062</b> tests), and the THROW site was unreachable from the
 * suite entirely, because this class did not exist and every executor test injects a fake router.
 *
 * <p>⚠ <b>A fake router cannot pin the router's own behaviour.</b> That is the whole reason this class
 * is here and not another case in {@code LiquidationExecutorTest}: the executor tests prove what the
 * executor does WITH the exception, and only a real {@link ConvertLiquidationRouter} proves that the
 * exception is ever raised. Both halves are needed, and neither substitutes for the other.
 */
class ConvertLiquidationRouterTest {

    private static final AssetType FLDT = new AssetType(
            "577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454");
    private static final String MINSWAP_POOL_POLICY =
            "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";

    /** The live ADA/FLDT pool's shape: deep enough that {@code POOL_TOO_THIN} cannot be the refusal. */
    private static MinswapPoolDatum deepPool() {
        return new MinswapPoolDatum(AssetType.ada(), FLDT, BigInteger.TEN,
                new BigInteger("1692342884761"), new BigInteger("7596442927398"),
                BigInteger.valueOf(80L), BigInteger.valueOf(80L), false);
    }

    /**
     * A resolver that answers with a pool without touching the index — the {@code UtxoRepository} is
     * {@code null}, so any real query would NPE and the test would say so.
     */
    private static final class FixedPoolResolver extends MinswapPoolResolver {
        FixedPoolResolver() {
            super(null, "addr_pool", MINSWAP_POOL_POLICY);
        }

        /** Answers the same pool for any pair asked, as this fake always has. */
        @Override
        public Snapshot snapshot() {
            Utxo poolUtxo = Utxo.builder().txHash("dd".repeat(32)).outputIndex(0)
                    .address("addr_pool").build();
            return new Snapshot(Map.of(), MINSWAP_POOL_POLICY) {
                @Override
                public Optional<ResolvedPool> resolveEitherOrder(AssetType one, AssetType other) {
                    return Optional.of(new ResolvedPool(poolUtxo, deepPool(), "lp"));
                }
            };
        }
    }

    /**
     * ⚠ {@code AppConfig.LoansConfiguration} is {@code @Getter}-only — its fields are written by
     * {@code @Value} in production and by nothing else — so the configured shape is expressed by
     * overriding the getters rather than by adding setters to a production class this slice must not
     * touch.
     */
    private static AppConfig.LoansConfiguration configuredForMinswap() {
        return new AppConfig.LoansConfiguration() {
            @Override
            public String getMinswapPoolAddress() {
                return "addr_pool";
            }

            @Override
            public String getMinswapPoolPolicyId() {
                return MINSWAP_POOL_POLICY;
            }

            @Override
            public String getMinswapOrderSpendScriptHash() {
                return "cc".repeat(28);
            }
        };
    }

    /**
     * FAB-185 — wallet role C, which the router hands to the builder untouched. These tests refuse before any
     * build, so it is never read; it is a well-formed C so that no refusal here can be about it.
     */
    private static final Utxo ROLE_C = LoanFixtures.adaUtxo("c5".repeat(32), 0, "addr_change", 5_000_000L);

    private static ConvertLiquidationRouter router() {
        AppConfig.LoansConfiguration loans = configuredForMinswap();
        return new ConvertLiquidationRouter(LoanFixtures.registry(), loans, null,
                new FixedPoolResolver(), null, null, LoanFixtures.converters(), LoanFixtures.NETWORK);
    }

    /** An FLDT-collateral, ada-principal convert candidate — the shape the live mainnet loan has. */
    private static LiquidationAssessment candidate() {
        LoanDatum datum = LoanFixtures.loanDatum(
                AssetType.ada(), BigInteger.valueOf(20_000_000L), BigInteger.valueOf(100L),
                new CollateralAsset(FLDT.policyId(),
                        Optional.of(FLDT.assetName()), LoanFixtures.NO_ORACLE),
                1_700_000_000_000L,
                new LiquidationMode.Liquidation(BigInteger.valueOf(500L), BigInteger.valueOf(1000L),
                        BigInteger.ZERO, false),
                new RepaymentMode.InterestOnRemainingPrincipal(BigInteger.ZERO), false);
        Loan loan = new Loan("aa".repeat(32), 0, "addr_loan", "cafe",
                BigInteger.valueOf(100_000_000L), BigInteger.valueOf(3_000_000L), datum);
        LenderBond bond = new LenderBond("bb".repeat(32), 0, "addr_bond", "cafe", "d87980",
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(50L),
                        LoanFixtures.noStakeCredential(), AssetType.ada()));
        return LiquidationAssessment.buildable(bond, loan, "convert fixture",
                BigInteger.valueOf(20_000_000L), BigInteger.ZERO, false, BigInteger.valueOf(5_000_000L));
    }

    /**
     * The collateral leg's feed. ⚠ It is REQUIRED, not optional: {@code LoanFinance.redeemerEquity}
     * values the collateral through it, so a null here is an NPE rather than a degraded path — which
     * is what a first draft of this test discovered.
     */
    private static Map<String, OracleEntry> collateralOracle() {
        // ⚠ Priced BELOW the pool (0.15 vs the pool's ~0.2228 lovelace per FLDT unit) on purpose: the
        // loan is then underwater, equity floors to zero, and the whole collateral less the
        // liquidation fee is swappable — which is the shape a real liquidatable candidate has by the
        // time it is liquidatable (CCL trap 20e). Priced AT the pool the fixture is marginal and the
        // POOL_TOO_THIN pre-check fires first, masking the seam this class exists to test.
        OraclePriceFeed feed = new OraclePriceFeed(OraclePriceFeed.Variant.AGGREGATED, FLDT,
                BigInteger.valueOf(15_000_000L), BigInteger.valueOf(100_000_000L),
                1_759_998_000_000L, 1_760_001_000_000L);
        return Map.of(LoanFixtures.NO_ORACLE.toUnit(),
                LoanFixtures.multisig(FLDT, LoanFixtures.NO_ORACLE, "dd".repeat(28), feed,
                        null, null, java.util.List.of()));
    }

    private static Utxo loanUtxo() {
        return Utxo.builder().txHash("aa".repeat(32)).outputIndex(0).address("addr_loan").build();
    }

    /**
     * ⛔ <b>THE THROW SITE, and the requirement it must state.</b> When no single nominable wallet UTxO
     * covers what the order takes, the router refuses BY NAME rather than handing the builder a null
     * (which would surface as a bare {@code NullPointerException} through the executor's generic catch
     * and be logged at ERROR as a machinery fault).
     *
     * <p>⚠ The selector returning {@code Optional.empty()} is the ONLY thing this fixture makes go
     * wrong — the pool is deep, the bond permits conversion and the plan is computable — so the
     * exception cannot be arriving for another reason.
     *
     * <p>Mutant M12 (neuter the executor's catch) is killed by the executor-side pair of this test;
     * removing this throw and passing {@code null} through is killed HERE.
     */
    @Test
    void aWalletWithNoSufficientUtxoIsRefusedByNameAndSaysWhatTheOrderNeeded() {
        var e = assertThrows(ConvertLiquidationRouter.WalletInputTooSmallException.class,
                () -> router().buildConvertLiquidation(candidate(), loanUtxo(), null, null, null,
                        requirement -> Optional.empty(),
                        ROLE_C, collateralOracle(), "addr_change",
                        1_760_000_000_000L, 1_760_000_120_000L));

        assertTrue(e.getMessage().contains("4000000"),
                "the operator must be told WHAT the order needed — the Minswap order overhead for a "
                        + "token collateral: " + e.getMessage());
        assertTrue(e.getMessage().contains("no nominable wallet utxo"),
                "and that the shortfall is the wallet's, not the candidate's: " + e.getMessage());
        assertTrue(e.getMessage().contains("reference script"),
                "the remedy must name what makes a utxo nominable, which is the trap operators hit: "
                        + e.getMessage());
    }

    /**
     * ⚑ And the requirement handed to the selector is the ORDER's own figure, not a guess: for a TOKEN
     * collateral it is exactly {@code MINSWAP_ORDER_OVERHEAD}. Captured off the real call so that a
     * change to what the router asks for cannot pass unnoticed.
     */
    @Test
    void theSelectorIsAskedForExactlyWhatTheOrderCarries() {
        BigInteger[] asked = new BigInteger[1];

        assertThrows(ConvertLiquidationRouter.WalletInputTooSmallException.class,
                () -> router().buildConvertLiquidation(candidate(), loanUtxo(), null, null, null,
                        requirement -> {
                            asked[0] = requirement;
                            return Optional.empty();
                        },
                        ROLE_C, collateralOracle(), "addr_change",
                        1_760_000_000_000L, 1_760_000_120_000L));

        assertEquals(ConvertEconomics.MINSWAP_ORDER_OVERHEAD, asked[0],
                "a token-collateral order carries the Minswap overhead and nothing else; the wallet "
                        + "nomination must be sized to that, not to list position");
    }

    /**
     * ⛔ The collateral oracle the datum names must price the COLLATERAL token (oracle re-slice,
     * cross-provider finding 2): present under that NFT but pricing another token, it is refused by
     * name — never built with, and never an NPE inside the equity arithmetic.
     */
    @Test
    void aCollateralOracleThatPricesAnotherTokenIsRefusedByName() {
        OracleEntry named = collateralOracle().get(LoanFixtures.NO_ORACLE.toUnit());
        AssetType otherToken = new AssetType("e".repeat(56), FLDT.assetName());
        OracleEntry wrongToken = new OracleEntry(otherToken, named.oracleToken(), named.rewardAddress(),
                named.withdrawCredentialHash(), named.referenceInput(), named.referenceScript(),
                named.verificationKeys(), named.threshold(), named.feed(), named.signatures(),
                named.charlieProviderReferenceInput());

        var e = assertThrows(IllegalStateException.class,
                () -> router().buildConvertLiquidation(candidate(), loanUtxo(), null, null, null,
                        requirement -> Optional.empty(),
                        ROLE_C, Map.of(LoanFixtures.NO_ORACLE.toUnit(), wrongToken), "addr_change",
                        1_760_000_000_000L, 1_760_000_120_000L));

        assertTrue(e.getMessage().contains("pricing " + FLDT.toUnit()), e.getMessage());
    }

    /**
     * ⛔ The PRINCIPAL leg's named oracle too: a token principal whose datum-named oracle prices another
     * token is refused by name, not priced with (feedOf goes through OracleEntry.namedForLeg).
     */
    @Test
    void aPrincipalOracleThatPricesAnotherTokenIsRefusedByName() {
        AssetType principal = new AssetType("c".repeat(56), "4e49474854");
        AssetType principalNft = new AssetType("d".repeat(56), "6f7261636c65");
        LiquidationAssessment ada = candidate();
        LoanDatum datum = LoanFixtures.loanDatum(principal, principalNft, BigInteger.valueOf(20_000_000L),
                BigInteger.valueOf(100L), ada.loan().datum().collateral(), 1_700_000_000_000L,
                ada.loan().datum().liquidationMode(), ada.loan().datum().repaymentMode(), false);
        Loan loan = new Loan("aa".repeat(32), 0, "addr_loan", "cafe",
                BigInteger.valueOf(100_000_000L), BigInteger.valueOf(3_000_000L), datum);
        LenderBond bond = new LenderBond("bb".repeat(32), 0, "addr_bond", "cafe", "d87980",
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(50L),
                        LoanFixtures.noStakeCredential(), principal));
        LiquidationAssessment tokenPrincipal = LiquidationAssessment.buildable(bond, loan, "token principal",
                BigInteger.valueOf(20_000_000L), BigInteger.ZERO, false, BigInteger.valueOf(5_000_000L));
        AssetType otherToken = new AssetType("e".repeat(56), principal.assetName());
        OracleEntry wrongToken = LoanFixtures.multisig(otherToken, principalNft, "ee".repeat(28),
                new OraclePriceFeed(OraclePriceFeed.Variant.AGGREGATED, otherToken, BigInteger.ONE,
                        BigInteger.ONE, 1_759_000_000_000L, 1_799_000_000_000L),
                null, null, java.util.List.of());
        Map<String, OracleEntry> oracles = new java.util.HashMap<>(collateralOracle());
        oracles.put(principalNft.toUnit(), wrongToken);

        var e = assertThrows(IllegalStateException.class,
                () -> router().buildConvertLiquidation(tokenPrincipal, loanUtxo(), null, null, null,
                        requirement -> Optional.empty(), ROLE_C, oracles, "addr_change",
                        1_760_000_000_000L, 1_760_000_120_000L));

        assertTrue(e.getMessage().contains("pricing " + principal.toUnit()), e.getMessage());
    }

    /**
     * FAB-224: an ada collateral is no longer refused by name. FAB-117 refused it first because the convert route then
     * needed a collateral oracle ada does not have (it died as a NullPointerException inside redeemerEquity) and the
     * builder could not build it. An ada collateral is now an ada leg priced with the 1:1 unit feed and paid from the
     * collateral, so it reaches the same steps as any collateral: this candidate (100 ADA, 50‰) is planned and refused
     * by the order plan as NOTHING_LEFT_TO_SWAP, and with no pool address configured it is the ordinary no-pool
     * refusal — neither is the old "ada collateral:" message.
     */
    @Test
    void anAdaCollateralIsPlannedLikeAnyCollateralRatherThanRefusedByName() {
        LiquidationAssessment base = candidate();
        LoanDatum datum = LoanFixtures.loanDatum(FLDT, LoanFixtures.NO_ORACLE, BigInteger.valueOf(20_000_000L),
                BigInteger.valueOf(100L), LoanFixtures.adaCollateral(), 1_700_000_000_000L,
                base.loan().datum().liquidationMode(), base.loan().datum().repaymentMode(), false);
        Loan loan = new Loan("aa".repeat(32), 0, "addr_loan", "cafe",
                BigInteger.valueOf(100_000_000L), BigInteger.valueOf(3_000_000L), datum);
        LenderBond bond = new LenderBond("bb".repeat(32), 0, "addr_bond", "cafe", "d87980",
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(50L),
                        LoanFixtures.noStakeCredential(), FLDT));
        LiquidationAssessment adaCollateral = LiquidationAssessment.buildable(bond, loan, "ada collateral",
                BigInteger.valueOf(20_000_000L), BigInteger.ZERO, false, BigInteger.valueOf(5_000_000L));

        var planned = assertThrows(ConvertOrderPlan.RefusedException.class,
                () -> router().buildConvertLiquidation(adaCollateral, loanUtxo(), null, null, null,
                        requirement -> Optional.empty(), ROLE_C, collateralOracle(), "addr_change",
                        1_760_000_000_000L, 1_760_000_120_000L));
        assertEquals(ConvertOrderPlan.Refusal.NOTHING_LEFT_TO_SWAP, planned.refusal(), planned.getMessage());
        assertEquals("NOTHING_LEFT_TO_SWAP: collateral 100000000 − equity 96970000 − fee 5000000 = -1970000",
                planned.getMessage());
        assertFalse(planned.getMessage().startsWith("ada collateral:"), planned.getMessage());

        AppConfig.LoansConfiguration noPool = new AppConfig.LoansConfiguration() {
            @Override
            public String getMinswapPoolAddress() {
                return "";
            }
        };
        ConvertLiquidationRouter unconfigured = new ConvertLiquidationRouter(LoanFixtures.registry(), noPool, null,
                new FixedPoolResolver(), null, null, LoanFixtures.converters(), LoanFixtures.NETWORK);
        var noPoolRefusal = assertThrows(ConvertLiquidationRouter.NoPoolException.class,
                () -> unconfigured.buildConvertLiquidation(adaCollateral, loanUtxo(), null, null, null,
                        requirement -> Optional.empty(), ROLE_C, collateralOracle(), "addr_change",
                        1_760_000_000_000L, 1_760_000_120_000L));
        assertFalse(noPoolRefusal.getMessage().startsWith("ada collateral:"), noPoolRefusal.getMessage());
    }

    /**
     * FAB-224: the ada-collateral funding rule. Every term is a pre-build ceiling: the order overhead (4,000,000), the
     * four bot-owned outputs' min-ada (4 × 2,000,000), a small equity's top-up (2,000,000 − equity), and the fee
     * ceiling — the boundary is exactly {@code slack ≥ feeCeiling}, and below it the ask is the shortfall alone.
     */
    @Test
    void theAdaCollateralWalletShortfallRule() {
        BigInteger ceiling = BigInteger.valueOf(2_405_077L);
        assertEquals(Optional.empty(), shortfall(14_405_077L, 0L, ceiling), "slack == feeCeiling: no wallet input");
        assertEquals(Optional.of(BigInteger.ZERO), shortfall(14_405_076L, 0L, ceiling),
                "one lovelace under: a wallet input, asked for nothing beyond the fee ceiling");
        assertEquals(Optional.of(BigInteger.valueOf(3_900_000L)), shortfall(8_100_000L, 0L, ceiling), "1‰ of 8,100 ADA");
        assertEquals(Optional.of(BigInteger.valueOf(12_000_000L)), shortfall(0L, 0L, ceiling), "a 0‰ bond");
        assertEquals(Optional.of(BigInteger.valueOf(5_899_999L)), shortfall(8_100_000L, 1L, ceiling),
                "an equity of 1 lovelace adds a top-up ceiling of 1,999,999");
        assertEquals(Optional.of(BigInteger.valueOf(3_900_000L)), shortfall(8_100_000L, 2_000_000L, ceiling),
                "an equity at the ceiling adds no top-up");
        assertEquals(Optional.empty(), shortfall(324_000_000L, 979_792L, BigInteger.valueOf(45_684_181L)),
                "40‰ of 8,100 ADA covers everything, a small equity's top-up included");
    }

    private static Optional<BigInteger> shortfall(long liquidationFee, long equity, BigInteger feeCeiling) {
        return ConvertLiquidationRouter.adaCollateralWalletShortfall(BigInteger.valueOf(liquidationFee),
                BigInteger.valueOf(equity), feeCeiling);
    }

    // ===== FAB-183: the feed window guard the plain and pay-in-advance routes already had =====

    /** The collateral feed of {@link #collateralOracle()}, re-windowed. */
    private static Map<String, OracleEntry> collateralOracleWindowed(long validFrom, long validTo) {
        OracleEntry named = collateralOracle().get(LoanFixtures.NO_ORACLE.toUnit());
        OraclePriceFeed feed = new OraclePriceFeed(OraclePriceFeed.Variant.AGGREGATED, FLDT,
                named.feed().priceInLovelaces(), named.feed().priceDenominator(), validFrom, validTo);
        return Map.of(LoanFixtures.NO_ORACLE.toUnit(),
                LoanFixtures.multisig(FLDT, LoanFixtures.NO_ORACLE, "dd".repeat(28), feed,
                        null, null, java.util.List.of()));
    }

    /**
     * ⛔ <b>A collateral feed that opens after the body's start is refused BEFORE the wallet is asked.</b>
     * {@code retrieve_oracle_data} requires {@code valid_from <= transactionValidFrom}; this route used to
     * check only {@code usableForLiquidation()}, so such a feed reached the chain. The body here starts at
     * {@code 1_760_000_000_000} and the feed opens ten seconds later.
     *
     * <p>The selector would answer {@code Optional.empty()} — a {@code WalletInputTooSmallException} — so
     * a guard that ran after it, or not at all, fails this test by throwing the wrong type. The counter
     * pins that the selector was never invoked.
     */
    @Test
    void aCollateralFeedOpeningAfterTheBodysStartIsRefusedBeforeTheWalletIsAsked() {
        int[] selectorCalls = new int[1];

        var e = assertThrows(ConvertLiquidationRouter.FeedWindowException.class,
                () -> router().buildConvertLiquidation(candidate(), loanUtxo(), null, null, null,
                        requirement -> {
                            selectorCalls[0]++;
                            return Optional.empty();
                        },
                        ROLE_C, collateralOracleWindowed(1_760_000_010_000L, 1_760_001_000_000L), "addr_change",
                        1_760_000_000_000L, 1_760_000_120_000L));

        assertEquals(0, selectorCalls[0], "the wallet selector must not run for a body the validator refuses");
        assertTrue(e.getMessage().contains("collateral"), "names the leg: " + e.getMessage());
        assertTrue(e.getMessage().contains("[1760000010000,1760001000000]"),
                "names the feed window: " + e.getMessage());
        assertTrue(e.getMessage().contains("[1760000000000,1760000120000]"),
                "names the transaction window, in the body's own slot times: " + e.getMessage());
    }

    /** The other end: a feed that closes before the body's end is refused the same way. */
    @Test
    void aCollateralFeedClosingBeforeTheBodysEndIsRefused() {
        int[] selectorCalls = new int[1];

        assertThrows(ConvertLiquidationRouter.FeedWindowException.class,
                () -> router().buildConvertLiquidation(candidate(), loanUtxo(), null, null, null,
                        requirement -> {
                            selectorCalls[0]++;
                            return Optional.empty();
                        },
                        ROLE_C, collateralOracleWindowed(1_759_998_000_000L, 1_760_000_060_000L), "addr_change",
                        1_760_000_000_000L, 1_760_000_120_000L));

        assertEquals(0, selectorCalls[0]);
    }

    /**
     * The PRINCIPAL leg is guarded too when the principal is a token: a correctly named principal feed whose
     * window does not cover the body is refused by name, while the collateral feed covers it.
     */
    @Test
    void aTokenPrincipalFeedThatDoesNotCoverTheBodyIsRefusedNamingThePrincipalLeg() {
        AssetType principal = new AssetType("c".repeat(56), "4e49474854");
        AssetType principalNft = new AssetType("d".repeat(56), "6f7261636c65");
        LiquidationAssessment ada = candidate();
        LoanDatum datum = LoanFixtures.loanDatum(principal, principalNft, BigInteger.valueOf(20_000_000L),
                BigInteger.valueOf(100L), ada.loan().datum().collateral(), 1_700_000_000_000L,
                ada.loan().datum().liquidationMode(), ada.loan().datum().repaymentMode(), false);
        Loan loan = new Loan("aa".repeat(32), 0, "addr_loan", "cafe",
                BigInteger.valueOf(100_000_000L), BigInteger.valueOf(3_000_000L), datum);
        LenderBond bond = new LenderBond("bb".repeat(32), 0, "addr_bond", "cafe", "d87980",
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(50L),
                        LoanFixtures.noStakeCredential(), principal));
        LiquidationAssessment tokenPrincipal = LiquidationAssessment.buildable(bond, loan, "token principal",
                BigInteger.valueOf(20_000_000L), BigInteger.ZERO, false, BigInteger.valueOf(5_000_000L));
        OracleEntry principalOracle = LoanFixtures.multisig(principal, principalNft, "ee".repeat(28),
                new OraclePriceFeed(OraclePriceFeed.Variant.AGGREGATED, principal, BigInteger.ONE,
                        BigInteger.ONE, 1_760_000_030_000L, 1_760_001_000_000L),
                null, null, java.util.List.of());
        Map<String, OracleEntry> oracles = new java.util.HashMap<>(collateralOracle());
        oracles.put(principalNft.toUnit(), principalOracle);
        int[] selectorCalls = new int[1];

        var e = assertThrows(ConvertLiquidationRouter.FeedWindowException.class,
                () -> router().buildConvertLiquidation(tokenPrincipal, loanUtxo(), null, null, null,
                        requirement -> {
                            selectorCalls[0]++;
                            return Optional.empty();
                        },
                        ROLE_C, oracles, "addr_change", 1_760_000_000_000L, 1_760_000_120_000L));

        assertEquals(0, selectorCalls[0]);
        assertTrue(e.getMessage().contains("principal"), "names the leg: " + e.getMessage());
        assertTrue(e.getMessage().contains("[1760000030000,1760001000000]"), e.getMessage());
    }

    // ===== the pool snapshot: ONE index query per liquidation cycle (FAB-137 T2b) =====

    private static final String POOL_CREDENTIAL = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";

    /** A real-resolver router over a mocked index holding the given rows at the pool credential. */
    private static ConvertLiquidationRouter indexedRouter(
            com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository repository) {
        return new ConvertLiquidationRouter(LoanFixtures.registry(), configuredForMinswap(), null,
                new MinswapPoolResolver(repository, POOL_CREDENTIAL, MINSWAP_POOL_POLICY), null, null,
                LoanFixtures.converters(), LoanFixtures.NETWORK);
    }

    private static com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository indexHolding(
            java.util.List<com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity> rows) {
        var repository = org.mockito.Mockito.mock(
                com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository.class);
        org.mockito.Mockito.when(repository.findUnspentByOwnerPaymentCredential(
                org.mockito.ArgumentMatchers.eq(POOL_CREDENTIAL), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(rows));
        return repository;
    }

    /** The live ada/FLDT pool's real datum, with SYNTHESISED amounts: its MSP and its own LP unit. */
    private static com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity adaFldtPool()
            throws java.io.IOException {
        var row = new com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity();
        row.setTxHash("0f1d".repeat(16));
        row.setOutputIndex(0);
        row.setOwnerAddr("addr_pool");
        row.setOwnerPaymentCredential(POOL_CREDENTIAL);
        row.setInlineDatum(java.nio.file.Files.readString(
                java.nio.file.Path.of("src/test/resources/loans-v4/mainnet-minswap-pool-ada-fldt.hex")).trim());
        java.util.List<com.bloxbean.cardano.yaci.store.common.domain.Amt> amounts = new java.util.ArrayList<>();
        for (Object[] unitAndQty : new Object[][]{{"lovelace", 1_692_342_884_761L},
                {MINSWAP_POOL_POLICY + ConvertTxEncoder.POOL_NFT_ASSET_NAME, 1L},
                {MINSWAP_POOL_POLICY + ConvertTxEncoder.computeLpAssetName(AssetType.ada(), FLDT), 1_000L},
                {FLDT.toUnit(), 7_596_442_927_398L}}) {
            var amt = new com.bloxbean.cardano.yaci.store.common.domain.Amt();
            amt.setUnit((String) unitAndQty[0]);
            amt.setQuantity(BigInteger.valueOf((Long) unitAndQty[1]));
            amounts.add(amt);
        }
        row.setAmounts(amounts);
        return row;
    }

    private static void buildOnce(ConvertLiquidationRouter router) {
        try {
            router.buildConvertLiquidation(candidate(), loanUtxo(), null, null, null,
                    requirement -> Optional.empty(), ROLE_C, collateralOracle(), "addr_change",
                    1_760_000_000_000L, 1_760_000_120_000L);
        } catch (RuntimeException expected) {
            // Whatever the outcome past the pool step: this test counts index queries, not verdicts.
        }
    }

    @Test
    void threeCandidatesInOneCycleMakeOneIndexQueryAndANewCycleMakesTheNext() throws Exception {
        var repository = indexHolding(java.util.List.of(adaFldtPool()));
        ConvertLiquidationRouter router = indexedRouter(repository);

        router.beginCycle();
        buildOnce(router);
        buildOnce(router);
        buildOnce(router);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(1))
                .findUnspentByOwnerPaymentCredential(org.mockito.ArgumentMatchers.eq(POOL_CREDENTIAL),
                        org.mockito.ArgumentMatchers.argThat(org.springframework.data.domain.Pageable::isUnpaged));

        router.beginCycle();
        buildOnce(router);
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(2))
                .findUnspentByOwnerPaymentCredential(org.mockito.ArgumentMatchers.eq(POOL_CREDENTIAL),
                        org.mockito.ArgumentMatchers.argThat(org.springframework.data.domain.Pageable::isUnpaged));
        org.mockito.Mockito.verifyNoMoreInteractions(repository);
    }

    @Test
    void aPairWithNoIndexedPoolSaysItIsNotIndexed() {
        ConvertLiquidationRouter router = indexedRouter(indexHolding(java.util.List.of()));
        router.beginCycle();

        var e = assertThrows(ConvertLiquidationRouter.NoPoolException.class,
                () -> router.buildConvertLiquidation(candidate(), loanUtxo(), null, null, null,
                        requirement -> Optional.empty(), ROLE_C, collateralOracle(), "addr_change",
                        1_760_000_000_000L, 1_760_000_120_000L));

        assertEquals("no Minswap pool for " + FLDT.toUnit() + "/lovelace is indexed — not indexed (pools "
                + "idle since 2025-05-06 are invisible); convert is impossible for this loan, not merely "
                + "unprofitable — set this market to action: CONVERT_BEFORE_ANTICIPATE with a cap if it should be "
                + "liquidated", e.getMessage());
    }

    /** CCL §19: the lookup is now a database read, and its failure must still arrive as LOOKUP_FAILED. */
    @Test
    void aDatabaseFailureReachesTheExecutorAsLookupFailedNotAsNoPool() {
        var repository = org.mockito.Mockito.mock(
                com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository.class);
        org.mockito.Mockito.when(repository.findUnspentByOwnerPaymentCredential(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        ConvertLiquidationRouter router = indexedRouter(repository);
        router.beginCycle();

        var e = assertThrows(MinswapPoolResolver.RefusedException.class,
                () -> router.buildConvertLiquidation(candidate(), loanUtxo(), null, null, null,
                        requirement -> Optional.empty(), ROLE_C, collateralOracle(), "addr_change",
                        1_760_000_000_000L, 1_760_000_120_000L));
        assertEquals(MinswapPoolResolver.Refusal.LOOKUP_FAILED, e.refusal());
    }

    // ===== FAB-194-3: the two IMPOSSIBLE verdicts come before the wallet is asked =====

    /**
     * A selector that records that it was asked. FAB-194-3 designates U only when the router asks, so a
     * {@code CONVERT_BEFORE_ANTICIPATE} fallback relies on the router reaching both impossible verdicts — no
     * pool, a pool too thin — WITHOUT asking: a verdict reached after the selector would have designated U
     * (and recorded a missing one) for a convert that was never possible.
     */
    private static java.util.function.Function<BigInteger, Optional<Utxo>> recording(boolean[] asked) {
        return requirement -> {
            asked[0] = true;
            return Optional.empty();
        };
    }

    /** A resolver answering one fixed pool datum for any pair, like {@link FixedPoolResolver}. */
    private static MinswapPoolResolver resolverOf(MinswapPoolDatum datum) {
        return new MinswapPoolResolver(null, "addr_pool", MINSWAP_POOL_POLICY) {
            @Override
            public Snapshot snapshot() {
                Utxo poolUtxo = Utxo.builder().txHash("dd".repeat(32)).outputIndex(0)
                        .address("addr_pool").build();
                return new Snapshot(Map.of(), MINSWAP_POOL_POLICY) {
                    @Override
                    public Optional<ResolvedPool> resolveEitherOrder(AssetType one, AssetType other) {
                        return Optional.of(new ResolvedPool(poolUtxo, datum, "lp"));
                    }
                };
            }
        };
    }

    /** FAB-194-3 — a pool far too shallow to return the debt: {@code POOL_TOO_THIN}, the selector never asked. */
    @Test
    void aThinPoolIsRefusedPoolTooThinBeforeTheWalletIsAsked() {
        // One ada against the live pool's FLDT: swapping the collateral returns far less than the 20 ADA debt.
        MinswapPoolDatum thin = new MinswapPoolDatum(AssetType.ada(), FLDT, BigInteger.TEN,
                BigInteger.valueOf(1_000_000L), new BigInteger("7596442927398"),
                BigInteger.valueOf(80L), BigInteger.valueOf(80L), false);
        ConvertLiquidationRouter router = new ConvertLiquidationRouter(LoanFixtures.registry(),
                configuredForMinswap(), null, resolverOf(thin), null, null, LoanFixtures.converters(),
                LoanFixtures.NETWORK);
        boolean[] asked = new boolean[1];

        var e = assertThrows(ConvertOrderPlan.RefusedException.class,
                () -> router.buildConvertLiquidation(candidate(), loanUtxo(), null, null, null,
                        recording(asked), ROLE_C, collateralOracle(), "addr_change",
                        1_760_000_000_000L, 1_760_000_120_000L));

        assertEquals(ConvertOrderPlan.Refusal.POOL_TOO_THIN, e.refusal(), e.getMessage());
        assertFalse(asked[0], "the wallet selector must not be asked before the pool verdict");
    }

    /** FAB-194-3 — no authentic pool for the pair in the index: {@code NoPoolException}, the selector never asked. */
    @Test
    void aPairWithNoIndexedPoolIsRefusedBeforeTheWalletIsAsked() {
        ConvertLiquidationRouter router = indexedRouter(indexHolding(java.util.List.of()));
        router.beginCycle();
        boolean[] asked = new boolean[1];

        assertThrows(ConvertLiquidationRouter.NoPoolException.class,
                () -> router.buildConvertLiquidation(candidate(), loanUtxo(), null, null, null,
                        recording(asked), ROLE_C, collateralOracle(), "addr_change",
                        1_760_000_000_000L, 1_760_000_120_000L));

        assertFalse(asked[0], "the wallet selector must not be asked when no pool exists");
    }

    /** FAB-194-3 — no pool address configured: {@code NoPoolException} with the new advice, the selector never asked. */
    @Test
    void aBlankPoolAddressIsRefusedBeforeTheWalletIsAsked() {
        AppConfig.LoansConfiguration blank = new AppConfig.LoansConfiguration() {
            @Override
            public String getMinswapPoolAddress() {
                return "  ";
            }
        };
        ConvertLiquidationRouter router = new ConvertLiquidationRouter(LoanFixtures.registry(), blank, null,
                new FixedPoolResolver(), null, null, LoanFixtures.converters(), LoanFixtures.NETWORK);
        boolean[] asked = new boolean[1];

        var e = assertThrows(ConvertLiquidationRouter.NoPoolException.class,
                () -> router.buildConvertLiquidation(candidate(), loanUtxo(), null, null, null,
                        recording(asked), ROLE_C, collateralOracle(), "addr_change",
                        1_760_000_000_000L, 1_760_000_120_000L));

        assertEquals("convert unavailable: no Minswap pool address configured for this network, so there is no "
                + "pool to swap " + FLDT.toUnit() + " against; set loans.minswap.pool-address, or set this market "
                + "to action: CONVERT_BEFORE_ANTICIPATE with a cap if the loan should still be liquidated",
                e.getMessage());
        assertFalse(asked[0], "the wallet selector must not be asked when no pool address is configured");
    }

    /** FAB-194-3 — {@code convertEnabled()} answers {@code loans.liquidation.convert.enabled}, read off a real gate. */
    @Test
    void convertEnabledAnswersTheEconomicsGatesOwnSwitch() {
        for (boolean enabled : new boolean[]{true, false}) {
            ConvertEconomics economics = new ConvertEconomics(new AppConfig.ConvertConfiguration(enabled), null, null);
            ConvertLiquidationRouter router = new ConvertLiquidationRouter(LoanFixtures.registry(),
                    configuredForMinswap(), null, new FixedPoolResolver(), economics, null,
                    LoanFixtures.converters(), LoanFixtures.NETWORK);

            assertEquals(enabled, economics.enabled());
            assertEquals(enabled, router.convertEnabled());
        }
    }
}
