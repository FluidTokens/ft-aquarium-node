package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers every {@link LiquidationExclusion} value and the D9 boundary, against fixtures built in
 * code (pattern: {@code LoanHealthServiceTest}, {@code LenderBondServiceTest}) plus a consistency
 * sweep over the pinned preview datums.
 * <p>
 * {@link LoanService} and {@link LenderBondService} are exercised through minimal subclasses that
 * override {@code findAll()} — the same "construct the service, never touch the injected
 * repository" trick {@code LenderBondServiceTest} uses with {@code new LenderBondService(null, null)},
 * just carried one step further so {@link LiquidationCandidateScanner#scan(long)} can be called
 * end to end without a database.
 */
class LiquidationCandidateScannerTest {

    private static final AssetType PRINCIPAL =
            new AssetType("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "5052494e");

    private static final AssetType COLLATERAL =
            new AssetType("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "434f4c4c");

    private static final long NOW = 1_800_000_000_000L;
    private static final long LEND_DATE = NOW - 30L * 24 * 3_600_000L;

    private static final String LOAN_ID = "cafe";

    private static final String REWARD_ADDRESS =
            "stake1799y3huw4j0n4weehlx3t6xvst50ge0vuv32ghk50mm7hwg5ucz8p";

    private static final long VALID_FROM = NOW - 1_000L;
    private static final long VALID_TO = NOW + 1_000L;

    // ---- oracle registry fixtures --------------------------------------------------------

    /** The oracle NFT {@link FluidOracleClient} indexes an entry by, for a given priced token. */
    private static AssetType oracleTokenFor(AssetType priced) {
        return new AssetType("c".repeat(56), priced.assetName());
    }

    private static String entry(AssetType token, long price, long validFrom, long validTo, int requiredSignatures,
                                boolean withSignature) {
        String signatures = withSignature
                ? "[{ \"publicKey\": \"%s\", \"signature\": \"ff\" }]".formatted("0".repeat(64))
                : "[]";
        return """
                {
                  "active": true,
                  "preferredOracle": "multisig",
                  "token": { "policyId": "%s", "assetName": "%s" },
                  "fluidOracle": {
                    "policyId": "%s",
                    "assetName": "%s",
                    "rewardAddress": "%s",
                    "referenceInput": "aa00000000000000000000000000000000000000000000000000000000000000#0",
                    "referenceScript": "bb00000000000000000000000000000000000000000000000000000000000000#0"
                  },
                  "multisigOracle": { "publicKeys": ["%s"], "requiredSignatures": %d },
                  "supportedOracle": {
                    "multisig": {
                      "validFrom": %d,
                      "validTo": %d,
                      "tokenPriceInLovelaces": %d,
                      "tokenPriceDenominator": 1,
                      "multisigOracle": {
                        "requiredSignatures": %d,
                        "signatures": %s
                      }
                    }
                  }
                }
                """.formatted(token.policyId(), token.assetName(),
                oracleTokenFor(token).policyId(), oracleTokenFor(token).assetName(), REWARD_ADDRESS,
                "0".repeat(64), requiredSignatures, validFrom, validTo, price, requiredSignatures, signatures);
    }

    private static String entry(AssetType token, long price) {
        return entry(token, price, VALID_FROM, VALID_TO, 1, true);
    }

    /**
     * Identical to {@link #entry(AssetType, long)} but with a malformed {@code
     * fluidOracle.referenceInput} ({@code "#0"} — an empty transaction id), so the parsed
     * {@link OracleEntry} carries a null reference input. Signatures, validity window and price are
     * all well-formed, so the entry is priceable and meets its signature threshold; only the oracle
     * NFT reference input fails to resolve.
     */
    private static String entryWithMalformedOracleReferenceInput(AssetType token, long price) {
        return """
                {
                  "active": true,
                  "preferredOracle": "multisig",
                  "token": { "policyId": "%s", "assetName": "%s" },
                  "fluidOracle": {
                    "policyId": "%s",
                    "assetName": "%s",
                    "rewardAddress": "%s",
                    "referenceInput": "#0",
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
                """.formatted(token.policyId(), token.assetName(),
                oracleTokenFor(token).policyId(), oracleTokenFor(token).assetName(), REWARD_ADDRESS,
                "0".repeat(64), VALID_FROM, VALID_TO, price, "0".repeat(64));
    }

    private static FluidOracleClient oracleClientWith(String... entries) throws Exception {
        var client = new FluidOracleClient("http://unused.invalid");
        client.load(new ObjectMapper().readTree("[" + String.join(",", entries) + "]"));
        return client;
    }

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

    // ---- Loan/LenderBond fixtures ---------------------------------------------------------

    private static CollateralAsset collateral(Optional<String> assetName) {
        return new CollateralAsset(COLLATERAL.policyId(), assetName, oracleTokenFor(COLLATERAL));
    }

    private static LiquidationMode.Liquidation liquidation(long ltv, long ltvDivider, long penaltyPerMille,
                                                            boolean equityInPrincipalCurrency) {
        return new LiquidationMode.Liquidation(BigInteger.valueOf(ltv), BigInteger.valueOf(ltvDivider),
                BigInteger.valueOf(penaltyPerMille), equityInPrincipalCurrency);
    }

    private static LoanDatum datum(LiquidationMode mode, RepaymentMode repaymentMode, BigInteger totalInstallments,
                                   BigInteger repaidInstallments, BigInteger installmentPeriod,
                                   BigInteger initialGracePeriod, BigInteger repaymentTimeWindow, long lendDate,
                                   BigInteger principalAmount, AssetType principal, AssetType principalOracle,
                                   CollateralAsset collateral) {
        return new LoanDatum(
                BigInteger.ZERO,               // doneRecasts
                principalAmount,
                BigInteger.valueOf(lendDate),
                repaidInstallments,
                BigInteger.ZERO,                // interestRate — kept at 0 so debt maths stay simple
                totalInstallments,
                principal,
                principalOracle,
                installmentPeriod,
                initialGracePeriod,
                mode,
                repaymentMode,
                repaymentTimeWindow,
                BigInteger.ZERO,                // penaltyFeeForLateRepayment
                false,
                "00",
                collateral);
    }

    /** A datum that clears every type filter (D1-D3) and never goes late by itself. */
    private static LoanDatum healthyDatum(LiquidationMode mode, BigInteger principalAmount) {
        return datum(mode, new RepaymentMode.PerpetualLoan(BigInteger.ZERO, BigInteger.ZERO),
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                LEND_DATE, principalAmount, PRINCIPAL, oracleTokenFor(PRINCIPAL),
                collateral(Optional.of(COLLATERAL.assetName())));
    }

    private static Loan loan(LoanDatum datum, BigInteger collateralAmount) {
        return new Loan("ab".repeat(32), 0, "addr_test1", LOAN_ID, collateralAmount,
                BigInteger.valueOf(3_000_000), datum);
    }

    private static LenderBond bond(boolean shouldConvert, long liquidationFeePerMille) {
        var datum = new LenderManagerDatum(
                new AuthorizationMethod.CardanoSignature("aa"),
                ConstrPlutusData.of(1),
                shouldConvert,
                BigInteger.valueOf(liquidationFeePerMille),
                "",
                PRINCIPAL);
        return new LenderBond("cd".repeat(32), 0, "addr_test1", LOAN_ID, "deadbeef", datum);
    }

    private static LenderBond permissiveBond() {
        return bond(false, 100);
    }

    // ---- service wiring, no repository involved -------------------------------------------

    private static final class FakeLoanService extends LoanService {
        private final List<Loan> loans;
        private final int unreadable;

        FakeLoanService(List<Loan> loans) {
            this(loans, 0);
        }

        /** @param unreadable loan-bearing utxos this node could see but could not read (T-060) */
        FakeLoanService(List<Loan> loans, int unreadable) {
            super(null, null);
            this.loans = loans;
            this.unreadable = unreadable;
        }

        // census(), not findAll(): the scanner asks for the census so the blindness signal travels
        // with the assessments. Overriding findAll() alone would leave this fake silently bypassed.
        @Override
        public Census census() {
            return new Census(loans, loans.size() + unreadable, unreadable, 0);
        }
    }

    private static final class FakeLenderBondService extends LenderBondService {
        private final List<LenderBond> bonds;

        FakeLenderBondService(List<LenderBond> bonds) {
            super(null, null);
            this.bonds = bonds;
        }

        @Override
        public List<LenderBond> findAll() {
            return bonds;
        }
    }

    private static LiquidationAssessment scanOne(List<Loan> loans, List<LenderBond> bonds,
                                                 FluidOracleClient client) {
        var scanner = new LiquidationCandidateScanner(
                new FakeLenderBondService(bonds), new FakeLoanService(loans), provider(client));
        List<LiquidationAssessment> results = scanner.scan(NOW).assessments();
        assertEquals(1, results.size());
        return results.getFirst();
    }

    // ---- LOAN_NOT_FOUND --------------------------------------------------------------------

    @Test
    void noLoanSharingTheBondAssetNameIsExcludedAsLoanNotFound() {
        var bond = permissiveBond();
        var assessment = scanOne(List.of(), List.of(bond), null);

        assertFalse(assessment.buildable());
        assertEquals(LiquidationExclusion.LOAN_NOT_FOUND, assessment.exclusion());
        assertNull(assessment.loan());
        assertEquals(bond, assessment.bond());
    }

    // ---- MODE_NOT_LIQUIDATION (D1) -----------------------------------------------------------

    @Test
    void nonLiquidationModeIsExcludedAsModeNotLiquidation() {
        var datum = healthyDatum(new LiquidationMode.NoLiquidationFullCollateralClaim(), BigInteger.valueOf(1_000_000));
        var loan = loan(datum, BigInteger.valueOf(1_000));
        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), null);

        assertEquals(LiquidationExclusion.MODE_NOT_LIQUIDATION, assessment.exclusion());
        assertEquals(loan, assessment.loan());
    }

    // ---- EQUITY_IN_PRINCIPAL_CURRENCY (D2) ---------------------------------------------------

    @Test
    void equityInPrincipalCurrencyIsExcluded() {
        var datum = healthyDatum(liquidation(100, 125, 100, true), BigInteger.valueOf(1_000_000));
        var loan = loan(datum, BigInteger.valueOf(1_000));
        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), null);

        assertEquals(LiquidationExclusion.EQUITY_IN_PRINCIPAL_CURRENCY, assessment.exclusion());
    }

    // ---- COLLATERAL_IS_COLLECTION (D3) -------------------------------------------------------

    @Test
    void collectionCollateralIsExcluded() {
        var datum = datum(liquidation(100, 125, 100, false),
                new RepaymentMode.PerpetualLoan(BigInteger.ZERO, BigInteger.ZERO),
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                LEND_DATE, BigInteger.valueOf(1_000_000), PRINCIPAL, oracleTokenFor(PRINCIPAL),
                collateral(Optional.empty()));
        var loan = loan(datum, BigInteger.valueOf(1_000));
        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), null);

        assertEquals(LiquidationExclusion.COLLATERAL_IS_COLLECTION, assessment.exclusion());
    }

    // ---- COLLATERAL_AMOUNT_TOO_SMALL (D3) ----------------------------------------------------

    @Test
    void singleNftCollateralIsExcluded() {
        var datum = healthyDatum(liquidation(100, 125, 100, false), BigInteger.valueOf(1_000_000));
        var loan = loan(datum, BigInteger.ONE);
        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), null);

        assertEquals(LiquidationExclusion.COLLATERAL_AMOUNT_TOO_SMALL, assessment.exclusion());
    }

    /**
     * Boundary partner to {@link #singleNftCollateralIsExcluded()}, mirroring the D9
     * boundary-pair convention: quantity 1 is excluded, quantity 2 — the smallest D3-permitted
     * quantity — must not be excluded on this filter. Zero-priced collateral (always-liquidatable
     * branch, same fixture as {@link #zeroValuedCollateralIsAlwaysLiquidatable()}) makes the
     * result deterministic regardless of the quantity, isolating the boundary itself.
     */
    @Test
    void collateralQuantityOfTwoIsTheSmallestBuildableQuantity() throws Exception {
        var datum = healthyDatum(liquidation(100, 125, -1, false), BigInteger.valueOf(1_000_000));
        var loan = loan(datum, BigInteger.valueOf(2));
        var client = oracleClientWith(entry(PRINCIPAL, 5), entry(COLLATERAL, 0));

        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), client);

        assertTrue(assessment.buildable(), assessment.exclusion() + ": " + assessment.detail());
    }

    // ---- a convert bond is no longer excluded for conversion (A3) ------------------------------

    /**
     * <b>A convert-to-principal bond is no longer excluded here.</b> This test replaces
     * {@code convertToPrincipalBondIsExcluded}, which asserted the exact opposite on a convert bond:
     * pre-change, the scanner filtered {@code shouldLiquidationConvertToPrincipal == True} out as
     * {@link LiquidationExclusion#CONVERSION_TO_PRINCIPAL_REQUIRED}. A3 lifted that exclusion, so a
     * convert loan is now scanned like any other and routed by {@code LiquidationExecutor} (A2) rather
     * than filtered here.
     * <p>
     * The fixture is the convert-bond twin of {@link #lateButLtvHealthyLoanIsBuildableViaLateness()}:
     * the identical late loan and feeds, with the bond's {@code shouldLiquidationConvertToPrincipal}
     * flipped to true. It comes back buildable with a null exclusion — the convert flag alone no longer
     * stops a candidate. (Pre-change, the same fixture would come back excluded as
     * {@code CONVERSION_TO_PRINCIPAL_REQUIRED}, which is the fail-first red for this expectation.)
     */
    @Test
    void convertToPrincipalBondIsNoLongerExcludedForConversion() throws Exception {
        var datum = lateDatum(-1); // late, so buildable via lateness; equity short-circuits to zero
        var loan = loan(datum, BigInteger.valueOf(1_000_000_000));
        var client = oracleClientWith(entry(PRINCIPAL, 5), entry(COLLATERAL, 2));

        var assessment = scanOne(List.of(loan), List.of(bond(true, 100)), client);

        assertTrue(assessment.buildable(), assessment.exclusion() + ": " + assessment.detail());
        assertNull(assessment.exclusion(),
                "the convert flag alone no longer excludes; conversion is the executor's routing job now");
    }

    /**
     * The control: a convert bond whose loan is still excluded, for a reason the convert flag has
     * nothing to do with. Exactly {@link #ltvExactlyAtTheThresholdIsNotLiquidatable()}'s fixture with
     * the bond's convert flag flipped on — it still reaches {@link LiquidationExclusion#NOT_LIQUIDATABLE},
     * proving A3 lifted only the conversion filter and left every downstream exclusion (and its order)
     * intact.
     */
    @Test
    void aConvertBondStillReachesTheDownstreamExclusionItDeserves() throws Exception {
        var datum = ltvBoundaryDatum(BigInteger.valueOf(1_000_000));
        var loan = loan(datum, BigInteger.valueOf(100));
        var client = oracleClientWith(entry(COLLATERAL, 10_000));

        var assessment = scanOne(List.of(loan), List.of(bond(true, 100)), client);

        assertEquals(LiquidationExclusion.NOT_LIQUIDATABLE, assessment.exclusion());
    }

    // ---- PRINCIPAL_ORACLE_UNUSABLE -----------------------------------------------------------

    @Test
    void principalLegWithNoOracleClientIsExcluded() throws Exception {
        var datum = healthyDatum(liquidation(100, 125, 100, false), BigInteger.valueOf(1_000_000));
        var loan = loan(datum, BigInteger.valueOf(1_000));
        // no client at all: principal is a token, so it needs one
        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), null);

        assertEquals(LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE, assessment.exclusion());
        assertTrue(assessment.detail().contains("disabled"), assessment.detail());
    }

    @Test
    void principalLegWithNoRegistryEntryIsExcluded() throws Exception {
        var datum = healthyDatum(liquidation(100, 125, 100, false), BigInteger.valueOf(1_000_000));
        var loan = loan(datum, BigInteger.valueOf(1_000));
        var client = oracleClientWith(entry(COLLATERAL, 2)); // no PRINCIPAL entry at all

        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), client);

        assertEquals(LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE, assessment.exclusion());
        assertTrue(assessment.detail().contains("no oracle entry"), assessment.detail());
    }

    @Test
    void principalLegWithoutEnoughSignaturesIsExcluded() throws Exception {
        var datum = healthyDatum(liquidation(100, 125, 100, false), BigInteger.valueOf(1_000_000));
        var loan = loan(datum, BigInteger.valueOf(1_000));
        var client = oracleClientWith(
                entry(PRINCIPAL, 5, VALID_FROM, VALID_TO, 1, false), // no signatures published
                entry(COLLATERAL, 2));

        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), client);

        assertEquals(LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE, assessment.exclusion());
        assertTrue(assessment.detail().contains("not usable for liquidation"), assessment.detail());
    }

    /**
     * T-012: a principal-leg oracle whose {@code fluidOracle.referenceInput} failed to parse (null
     * reference input) cannot have a liquidation built against it — {@code retrieve_oracle_data}
     * requires that reference input. The loan is otherwise buildable (late, with a valid collateral
     * feed), so this isolates the principal oracle leg. Against code without the fail-closed guard
     * this loan is buildable, because the malformed entry still meets its signature threshold.
     */
    @Test
    void principalLegWithAnUnparseableOracleReferenceInputIsExcluded() throws Exception {
        var datum = lateDatum(-1); // late, so buildable if the principal leg were usable
        var loan = loan(datum, BigInteger.valueOf(1_000_000_000));
        var client = oracleClientWith(
                entryWithMalformedOracleReferenceInput(PRINCIPAL, 5),
                entry(COLLATERAL, 2));

        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), client);

        assertEquals(LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE, assessment.exclusion());
        assertTrue(assessment.detail().contains("not usable for liquidation"), assessment.detail());
    }

    @Test
    void principalLegWithExpiredWindowIsExcluded() throws Exception {
        var datum = healthyDatum(liquidation(100, 125, 100, false), BigInteger.valueOf(1_000_000));
        var loan = loan(datum, BigInteger.valueOf(1_000));
        var client = oracleClientWith(
                entry(PRINCIPAL, 5, NOW - 2_000L, NOW - 1L, 1, true), // expired
                entry(COLLATERAL, 2));

        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), client);

        assertEquals(LiquidationExclusion.PRINCIPAL_ORACLE_UNUSABLE, assessment.exclusion());
        assertTrue(assessment.detail().contains("validity window"), assessment.detail());
    }

    // ---- COLLATERAL_ORACLE_UNUSABLE ----------------------------------------------------------

    @Test
    void collateralLegWithNoRegistryEntryIsExcluded() throws Exception {
        var datum = healthyDatum(liquidation(100, 125, 100, false), BigInteger.valueOf(1_000_000));
        var loan = loan(datum, BigInteger.valueOf(1_000));
        var client = oracleClientWith(entry(PRINCIPAL, 5)); // no COLLATERAL entry at all

        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), client);

        assertEquals(LiquidationExclusion.COLLATERAL_ORACLE_UNUSABLE, assessment.exclusion());
        assertTrue(assessment.detail().contains("no oracle entry"), assessment.detail());
        assertTrue(assessment.detail().contains("collateral leg"),
                "detail should name which leg failed: " + assessment.detail());
    }

    // ---- HEALTH_NOT_COMPUTABLE ---------------------------------------------------------------

    /**
     * {@code InterestOnRemainingPrincipal} with {@code totalInstallments == 0} divides by zero in
     * {@code nextInstallmentAmount} — the same failure the on-chain path would hit. Ada principal
     * needs no oracle, so the computation is reached at all.
     */
    @Test
    void divisionByZeroInFinanceMathIsHealthNotComputable() throws Exception {
        var datum = datum(liquidation(100, 125, 100, false),
                new RepaymentMode.InterestOnRemainingPrincipal(BigInteger.ZERO),
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                LEND_DATE, BigInteger.valueOf(1_000_000), AssetType.ada(), AssetType.ada(),
                collateral(Optional.of(COLLATERAL.assetName())));
        var loan = loan(datum, BigInteger.valueOf(1_000));
        var client = oracleClientWith(entry(COLLATERAL, 2));

        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), client);

        assertEquals(LiquidationExclusion.HEALTH_NOT_COMPUTABLE, assessment.exclusion());
    }

    // ---- NOT_LIQUIDATABLE / D9 boundary ------------------------------------------------------

    /**
     * {@code totalInstallments = 1}, {@code interestRate = 0} makes remaining debt exactly the
     * principal amount, so {@code currentLtv} is an exact ratio and the D9 boundary can be hit
     * precisely: 1,000,000 lovelace debt against 100 * 10,000 = 1,000,000 lovelace collateral is
     * currentLtv == 1/1, matching a 100% liquidation LTV exactly.
     * <p>
     * {@code lendDate = NOW} rather than {@link #LEND_DATE}: with {@code installmentPeriod} and
     * {@code repaymentTimeWindow} both zero, {@code isRepaymentLate}'s deadline collapses to
     * {@code lendDate} itself, so anything lent in the past would already read late and swamp the
     * LTV comparison this test is isolating.
     */
    private static LoanDatum ltvBoundaryDatum(BigInteger principalAmount) {
        return datum(liquidation(1, 1, 0, false),
                new RepaymentMode.PrincipalAndInterestOnInstallments(),
                BigInteger.ONE, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                NOW, principalAmount, AssetType.ada(), AssetType.ada(),
                collateral(Optional.of(COLLATERAL.assetName())));
    }

    @Test
    void ltvExactlyAtTheThresholdIsNotLiquidatable() throws Exception {
        var datum = ltvBoundaryDatum(BigInteger.valueOf(1_000_000));
        var loan = loan(datum, BigInteger.valueOf(100));
        var client = oracleClientWith(entry(COLLATERAL, 10_000));

        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), client);

        assertEquals(LiquidationExclusion.NOT_LIQUIDATABLE, assessment.exclusion());
    }

    @Test
    void ltvOneNotchAboveTheThresholdIsBuildable() throws Exception {
        var datum = ltvBoundaryDatum(BigInteger.valueOf(1_000_001));
        var loan = loan(datum, BigInteger.valueOf(100));
        var client = oracleClientWith(entry(COLLATERAL, 10_000));

        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), client);

        assertTrue(assessment.buildable(), assessment.exclusion() + ": " + assessment.detail());
        assertEquals(BigInteger.valueOf(1_000_001), assessment.remainingDebt());
        assertFalse(assessment.late());
    }

    // ---- zero-valued collateral: always-liquidatable branch ---------------------------------

    /**
     * D9: {@code collateralInLovelace == rational.zero} always liquidates, no LTV comparison at
     * all. A negative {@code partialLiquidationPenaltyPerMille} makes {@code redeemerEquity}
     * short-circuit to zero before it would otherwise divide by the zero-priced collateral feed.
     */
    @Test
    void zeroValuedCollateralIsAlwaysLiquidatable() throws Exception {
        var datum = healthyDatum(liquidation(100, 125, -1, false), BigInteger.valueOf(1_000_000));
        var loan = loan(datum, BigInteger.valueOf(1_000));
        var client = oracleClientWith(entry(PRINCIPAL, 5), entry(COLLATERAL, 0));

        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), client);

        assertTrue(assessment.buildable(), assessment.exclusion() + ": " + assessment.detail());
        assertEquals(BigInteger.ZERO, assessment.equity());
        assertFalse(assessment.late());
    }

    // ---- lateness is an independent trigger --------------------------------------------------

    /**
     * The lateness fixture. {@code partialLiquidationPenaltyPerMille} is a parameter because it is the
     * only knob that decides whether a wealthy-collateral loan ends up with a positive equity: a
     * negative value makes {@code redeemerEquity} short-circuit to zero (same idiom as
     * {@link #zeroValuedCollateralIsAlwaysLiquidatable()}), a positive one lets the surplus through.
     * Nothing else in this datum reacts to it — lateness and LTV are both computed without it — so the
     * two tests below differ in exactly that one number.
     */
    private static LoanDatum lateDatum(long partialLiquidationPenaltyPerMille) {
        return datum(liquidation(100, 125, partialLiquidationPenaltyPerMille, false),
                new RepaymentMode.PrincipalAndInterestOnInstallments(),
                BigInteger.valueOf(12), BigInteger.ZERO, BigInteger.valueOf(24), BigInteger.ZERO, BigInteger.ZERO,
                LEND_DATE, BigInteger.valueOf(1_000_000), PRINCIPAL, oracleTokenFor(PRINCIPAL),
                collateral(Optional.of(COLLATERAL.assetName())));
    }

    @Test
    void lateButLtvHealthyLoanIsBuildableViaLateness() throws Exception {
        var datum = lateDatum(-1); // no partial-liquidation penalty: equity short-circuits to zero
        // wealthy collateral: LTV alone would say healthy
        var loan = loan(datum, BigInteger.valueOf(1_000_000_000));
        var client = oracleClientWith(entry(PRINCIPAL, 5), entry(COLLATERAL, 2));

        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), client);

        assertTrue(assessment.buildable(), assessment.exclusion() + ": " + assessment.detail());
        assertTrue(assessment.late(), "30 days elapsed against a 24h installment period");
    }

    // ---- a positive equity is an ordinary candidate ------------------------------------------

    /**
     * <b>A liquidatable loan with a positive equity is admitted.</b> This test replaces
     * {@code aLiquidatableLoanWithPositiveEquityIsExcludedAsUnsupported}, which asserted the exact
     * opposite on this same fixture.
     *
     * <h3>Why the old assertion was removed rather than adjusted</h3>
     * It pinned {@code LiquidationExclusion.POSITIVE_EQUITY_UNSUPPORTED} as intended behaviour. That
     * exclusion rested on the claim that the deployed {@code lm_liquidate_action} and
     * {@code loan_claim_action} both force the loan-index slot of the asset-manager-filtered output
     * list and want mutually exclusive datums there. Only half of that is true: at the deployed pin,
     * {@code lm_liquidate_action.ak:87-91} reaches its slot through
     * {@code redeemer.assetOutputIndexes}, which the builder writes. Put the borrower's compensation
     * output at {@code loan_claim_action}'s forced slot and point the free index at the displaced
     * collateral output, and both validators are satisfied — rule R, evaluated against the deployed
     * scripts on real chain data in {@code RealEquityLoanDryEvalTest}. The exclusion described a
     * limitation of the builder, not of the deployment, and the builder no longer has it.
     *
     * <h3>The fixture</h3>
     * Boundary partner to {@link #lateButLtvHealthyLoanIsBuildableViaLateness()}: the identical loan,
     * identical collateral, identical feeds, with the partial-liquidation penalty flipped from
     * negative to positive so the borrower's surplus is actually computed instead of short-circuited.
     * 1,000,000,000 units of collateral at 2 lovelace is far more than the 1,000,000-unit debt at
     * 5 lovelace, so the equity is large and positive — and the loan is still liquidatable, by
     * lateness. Both facts are asserted, so this cannot pass by the equity having quietly gone to zero.
     */
    @Test
    void aLiquidatableLoanWithPositiveEquityIsBuildable() throws Exception {
        var datum = lateDatum(100);
        var loan = loan(datum, BigInteger.valueOf(1_000_000_000));
        var client = oracleClientWith(entry(PRINCIPAL, 5), entry(COLLATERAL, 2));

        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), client);

        assertTrue(assessment.equity().signum() > 0,
                "the fixture must really carry a positive equity, or this test proves nothing");
        assertTrue(assessment.late(), "30 days elapsed against a 24h installment period");
        assertTrue(assessment.buildable(),
                assessment.exclusion() + ": " + assessment.detail());
        assertNull(assessment.exclusion());
    }

    @Test
    void lateLoanWithUnusableFeedIsStillExcludedOnTheOracleLeg() throws Exception {
        var datum = lateDatum(100);
        var loan = loan(datum, BigInteger.valueOf(1_000_000_000));
        var client = oracleClientWith(entry(PRINCIPAL, 5)); // no COLLATERAL entry — D9: no oracle-free path

        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), client);

        assertFalse(assessment.buildable());
        assertEquals(LiquidationExclusion.COLLATERAL_ORACLE_UNUSABLE, assessment.exclusion());
    }

    // ---- ADA principal needs no oracle entry -------------------------------------------------

    @Test
    void adaPrincipalPricesWithOnlyACollateralFeed() throws Exception {
        var datum = healthyDatum(liquidation(1, 1, -1, false), BigInteger.valueOf(1_000_000));
        var datumWithAdaPrincipal = new LoanDatum(datum.doneRecasts(), datum.principalAmount(), datum.lendDate(),
                datum.repaidInstallments(), datum.interestRate(), datum.totalInstallments(), AssetType.ada(),
                AssetType.ada(), datum.installmentPeriod(), datum.initialGracePeriod(), datum.liquidationMode(),
                datum.repaymentMode(), datum.repaymentTimeWindow(), datum.penaltyFeeForLateRepayment(),
                datum.repaymentReceipts(), datum.originId(), datum.collateral());
        var loan = loan(datumWithAdaPrincipal, BigInteger.valueOf(1_000));
        // registry has no PRINCIPAL entry at all — ada must not need one
        var client = oracleClientWith(entry(COLLATERAL, 0));

        var assessment = scanOne(List.of(loan), List.of(permissiveBond()), client);

        assertTrue(assessment.buildable(), assessment.exclusion() + ": " + assessment.detail());
    }

    // ---- liquidation fee: §7.5 fee maths, floored not truncated ------------------------------

    /**
     * All three cases reuse the zero-priced-collateral always-liquidatable fixture (same as
     * {@link #zeroValuedCollateralIsAlwaysLiquidatable()}) so the assessment is guaranteed
     * buildable regardless of the collateral amount or the bond's fee, isolating the fee formula
     * itself. {@code liquidationFeePerMille} is a lender-authored bond field with no
     * non-negativity constraint, so it must be exercised with a negative value too.
     */
    private static LiquidationAssessment buildableWithFee(BigInteger collateralAmount,
                                                           long liquidationFeePerMille) throws Exception {
        var datum = healthyDatum(liquidation(100, 125, -1, false), BigInteger.valueOf(1_000_000));
        var loan = loan(datum, collateralAmount);
        var client = oracleClientWith(entry(PRINCIPAL, 5), entry(COLLATERAL, 0));
        return scanOne(List.of(loan), List.of(bond(false, liquidationFeePerMille)), client);
    }

    @Test
    void liquidationFeeIsExactWhenTheProductDividesEvenly() throws Exception {
        var assessment = buildableWithFee(BigInteger.valueOf(1_000), 25); // 1000*25/1000 = 25, exact

        assertTrue(assessment.buildable(), assessment.exclusion() + ": " + assessment.detail());
        assertEquals(BigInteger.valueOf(25), assessment.liquidationFee());
    }

    @Test
    void liquidationFeeFloorsWhenTheProductDoesNotDivideEvenly() throws Exception {
        var assessment = buildableWithFee(BigInteger.valueOf(1_001), 25); // 1001*25/1000 = 25.025 -> 25

        assertTrue(assessment.buildable(), assessment.exclusion() + ": " + assessment.detail());
        assertEquals(BigInteger.valueOf(25), assessment.liquidationFee());
    }

    /**
     * {@code BigInteger.divide} truncates -2500/1000 to -2; the on-chain floor is -3
     * ({@code Rational.floor()} documents exactly this asymmetry). A lender-authored negative
     * {@code liquidationFeePerMille} must not desync the bot's redeemer from what the validator
     * recomputes.
     */
    @Test
    void liquidationFeeFloorsTowardNegativeInfinityForANegativeFeePerMille() throws Exception {
        var assessment = buildableWithFee(BigInteger.valueOf(100), -25); // 100*-25/1000 = -2.5 -> -3

        assertTrue(assessment.buildable(), assessment.exclusion() + ": " + assessment.detail());
        assertEquals(BigInteger.valueOf(-3), assessment.liquidationFee());
    }

    // ---- a duplicate loanId must not blank the whole scan ------------------------------------

    /**
     * Transient duplicate unspent UTxOs sharing an asset name are reachable in principle; whatever
     * happens to the ambiguous bond, every other bond's assessment must still come back — the same
     * "one bad input must not blank the endpoint" posture {@code LoanService}/{@code LenderBondService}
     * already take on undecodable UTxOs.
     */
    @Test
    void duplicateLoanIdDoesNotBlankAssessmentsForOtherBonds() {
        var duplicatedDatum = healthyDatum(liquidation(100, 125, 100, false), BigInteger.valueOf(1_000_000));
        var duplicateLoanA = new Loan("aa".repeat(32), 0, "addr_test1", LOAN_ID, BigInteger.valueOf(1_000),
                BigInteger.valueOf(3_000_000), duplicatedDatum);
        var duplicateLoanB = new Loan("bb".repeat(32), 1, "addr_test1", LOAN_ID, BigInteger.valueOf(1_000),
                BigInteger.valueOf(3_000_000), duplicatedDatum);
        var duplicateBond = permissiveBond(); // loanId == LOAN_ID, ambiguous against A and B

        var otherLoanId = "beef";
        var otherDatum = healthyDatum(new LiquidationMode.NoLiquidationFullCollateralClaim(),
                BigInteger.valueOf(1_000_000));
        var otherLoan = new Loan("cc".repeat(32), 0, "addr_test1", otherLoanId, BigInteger.valueOf(1_000),
                BigInteger.valueOf(3_000_000), otherDatum);
        var otherBond = new LenderBond("dd".repeat(32), 0, "addr_test1", otherLoanId, "deadbeef",
                permissiveBond().datum());

        var scanner = new LiquidationCandidateScanner(
                new FakeLenderBondService(List.of(duplicateBond, otherBond)),
                new FakeLoanService(List.of(duplicateLoanA, duplicateLoanB, otherLoan)),
                provider(null));

        List<LiquidationAssessment> results = scanner.scan(NOW).assessments();

        assertEquals(2, results.size(), "a duplicate loanId must not blank assessments for other bonds");
        var otherAssessment = results.stream()
                .filter(a -> a.bond().loanId().equals(otherLoanId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the unambiguous bond's assessment went missing"));
        assertEquals(LiquidationExclusion.MODE_NOT_LIQUIDATION, otherAssessment.exclusion());
    }

    // ---- consistency sweep over the pinned preview datums ------------------------------------

    private static final Set<LiquidationExclusion> TYPE_FILTER_REASONS = Set.of(
            LiquidationExclusion.MODE_NOT_LIQUIDATION,
            LiquidationExclusion.EQUITY_IN_PRINCIPAL_CURRENCY,
            LiquidationExclusion.COLLATERAL_IS_COLLECTION);

    private static List<String> livePreviewDatumHexes() {
        try (var is = LiquidationCandidateScannerTest.class
                .getResourceAsStream("/loans-v4/preview-loan-datums.hex")) {
            return new String(Objects.requireNonNull(is).readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * {@link Loan#botLiquidatable()} and this scanner's D1-D3 filters are two independent
     * implementations of the same validator-verified type filter; they must never disagree.
     * Pinning the collateral amount above 1 isolates the D1/D2/D3-collection question from D3's
     * quantity check, which both implementations apply identically and is covered separately
     * above. Everything past the type filter (oracle client entirely absent here) is allowed to
     * land on any later exclusion or on buildable — only the D1/D2/D3-vs-not-D1/D2/D3 boundary is
     * asserted.
     * <p>
     * <b>Coverage limit.</b> Every excluded datum in this fixture (6 of the 56) is D2
     * ({@code equityInPrincipalCurrency == true}); the fixture contains zero D1
     * (non-{@code Liquidation} mode) and zero D3-collection (no asset name) cases. This sweep
     * therefore only pins D2 agreement against real chain data, plus that nothing else in the
     * fixture accidentally trips a type-filter false positive — D1 and D3-collection agreement
     * are pinned by the synthetic per-filter tests above instead.
     */
    @Test
    void scannerAgreesWithBotLiquidatableOnEveryPinnedPreviewDatum() {
        var converter = new LoanDatumConverter();
        var fixedCollateralAmount = BigInteger.valueOf(1_000);
        var hexes = livePreviewDatumHexes();
        assertFalse(hexes.isEmpty(), "fixture is empty");

        for (String hex : hexes) {
            LoanDatum datum = converter.deserialize(hex);
            var loan = loan(datum, fixedCollateralAmount);
            var assessment = scanOne(List.of(loan), List.of(permissiveBond()), null);

            boolean scannerSaysTypeExcluded = TYPE_FILTER_REASONS.contains(assessment.exclusion());
            assertEquals(!loan.botLiquidatable(), scannerSaysTypeExcluded,
                    "disagreement on " + hex.substring(0, 40) + "...: botLiquidatable=" + loan.botLiquidatable()
                            + " scanner exclusion=" + assessment.exclusion());
        }
    }
}
