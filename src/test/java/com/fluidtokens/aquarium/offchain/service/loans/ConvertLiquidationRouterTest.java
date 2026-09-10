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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The convert router's WALLET NOMINATION seam, driven at the router rather than through a fake.</b>
 *
 * <p>Audit finding F1: {@code WalletInputTooSmallException} was a decorative guard. The executor's
 * catch for it was pinned by nothing (mutant M12 — neuter the catch so it falls to the 30-minute
 * machinery quarantine — killed <b>0 of 1062</b> tests), and the THROW site was unreachable from the
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
     * A resolver that answers with a pool without touching a provider — the {@code UtxoService} is
     * {@code null}, so any real lookup would NPE and the test would say so.
     */
    private static final class FixedPoolResolver extends MinswapPoolResolver {
        FixedPoolResolver() {
            super(null, "addr_pool", MINSWAP_POOL_POLICY);
        }

        @Override
        public Optional<ResolvedPool> resolveEitherOrder(AssetType one, AssetType other) {
            Utxo poolUtxo = Utxo.builder().txHash("dd".repeat(32)).outputIndex(0)
                    .address("addr_pool").build();
            return Optional.of(new ResolvedPool(poolUtxo, deepPool(), "lp"));
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
                1_759_000_000_000L, 1_799_000_000_000L);
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
     * and be quarantined for thirty minutes as a machinery fault).
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
                        collateralOracle(), "addr_change",
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
                        collateralOracle(), "addr_change",
                        1_760_000_000_000L, 1_760_000_120_000L));

        assertEquals(ConvertEconomics.MINSWAP_ORDER_OVERHEAD, asked[0],
                "a token-collateral order carries the Minswap overhead and nothing else; the wallet "
                        + "nomination must be sized to that, not to list position");
    }
}
