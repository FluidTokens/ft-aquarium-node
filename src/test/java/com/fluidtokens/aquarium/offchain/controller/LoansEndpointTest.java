package com.fluidtokens.aquarium.offchain.controller;

import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.fasterxml.jackson.databind.JsonNode;
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
import com.fluidtokens.aquarium.offchain.model.loans.LoanHealth;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidationCandidateScanner;
import com.fluidtokens.aquarium.offchain.service.loans.LoanHealthService;
import com.fluidtokens.aquarium.offchain.service.loans.LoanService;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /loans} — the loan-anchored view's {@code bot_liquidatable} verdict is the executor's,
 * not a parallel loan-side one.
 * <p>
 * The controller is constructed directly, exactly as {@code OracleStatusEndpointTest} and
 * {@code LiquidationDecisionsEndpointTest} do: no Spring context. {@link LiquidationCandidateScanner}
 * is a subclass whose {@code scan} returns a fixed batch of assessments, so the join the controller
 * performs — key each assessment by its bond's loanId, look each loan up by its own loanId — is
 * exercised end to end without a database or an oracle. The claims that matter are about serialised
 * JSON (snake_case names, and {@code bot_liquidatable} being a boolean the executor produced), so
 * they go through a plain {@link ObjectMapper}.
 * <p>
 * Every loan fixture here is loan-side {@link Loan#botLiquidatable()} — {@code Liquidation} mode,
 * named collateral, quantity &gt; 1 — so the divergence being tested is real: the loan-side filter
 * says "liquidatable by type", and only the scanner assessment decides the actual verdict.
 */
class LoansEndpointTest {

    private static final AssetType PRINCIPAL =
            new AssetType("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "5052494e");
    private static final AssetType COLLATERAL =
            new AssetType("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "434f4c4c");

    private static AssetType oracleTokenFor(AssetType priced) {
        return new AssetType("c".repeat(56), priced.assetName());
    }

    // ---- fixtures -------------------------------------------------------------------------

    private static CollateralAsset collateral() {
        return new CollateralAsset(COLLATERAL.policyId(), Optional.of(COLLATERAL.assetName()),
                oracleTokenFor(COLLATERAL));
    }

    /** A datum that clears every loan-side type filter (D1-D3), so {@code loan.botLiquidatable()} is true. */
    private static LoanDatum liquidatableDatum() {
        return new LoanDatum(BigInteger.ZERO, BigInteger.valueOf(1_000_000), BigInteger.ZERO,
                BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, PRINCIPAL, oracleTokenFor(PRINCIPAL),
                BigInteger.ZERO, BigInteger.ZERO,
                new LiquidationMode.Liquidation(BigInteger.valueOf(100), BigInteger.valueOf(125),
                        BigInteger.valueOf(100), false),
                new RepaymentMode.PerpetualLoan(BigInteger.ZERO, BigInteger.ZERO),
                BigInteger.ZERO, BigInteger.ZERO, false, "00", collateral());
    }

    private static Loan loan(String loanId) {
        return new Loan("ab".repeat(32), 0, "addr_test1", loanId, BigInteger.valueOf(1_000),
                BigInteger.valueOf(3_000_000), liquidatableDatum());
    }

    private static LenderBond bond(String loanId) {
        var datum = new LenderManagerDatum(
                new AuthorizationMethod.CardanoSignature("aa"),
                ConstrPlutusData.of(1),
                false,
                BigInteger.valueOf(100),
                "",
                PRINCIPAL);
        return new LenderBond("cd".repeat(32), 0, "addr_test1", loanId, "deadbeef", datum);
    }

    private static LiquidationAssessment buildable(String loanId) {
        return LiquidationAssessment.buildable(bond(loanId), loan(loanId), "buildable liquidation",
                BigInteger.valueOf(1_000_000), BigInteger.ZERO, false, BigInteger.valueOf(100));
    }

    private static LiquidationAssessment convertExcluded(String loanId) {
        return LiquidationAssessment.excluded(bond(loanId), loan(loanId),
                LiquidationExclusion.CONVERSION_TO_PRINCIPAL_REQUIRED,
                "bond requires converting liquidation proceeds to principal");
    }

    // ---- service wiring, no repository/oracle involved ------------------------------------

    private static final class FakeLoanService extends LoanService {
        private final List<Loan> loans;

        FakeLoanService(List<Loan> loans) {
            super(null, null);
            this.loans = loans;
        }

        @Override
        public List<Loan> findAll() {
            return loans;
        }
    }

    private static final class StubHealthService extends LoanHealthService {
        StubHealthService() {
            super(null);
        }

        @Override
        public LoanHealth health(Loan loan, long atTimeMillis) {
            return LoanHealth.debtOnly(BigInteger.ZERO, false, "stub");
        }
    }

    private static LiquidationCandidateScanner scannerReturning(List<LiquidationAssessment> assessments) {
        return new LiquidationCandidateScanner(null, null, null) {
            @Override
            public List<LiquidationAssessment> scan(long atTimeMillis) {
                return assessments;
            }
        };
    }

    private static LoanController controller(List<Loan> loans, List<LiquidationAssessment> assessments) {
        return new LoanController(new FakeLoanService(loans), new StubHealthService(), null,
                scannerReturning(assessments));
    }

    private static JsonNode json(List<LoanController.LoanView> views) {
        return new ObjectMapper().valueToTree(views);
    }

    // ---- (d) REGRESSION: convert-excluded loan is not bot_liquidatable ----------------------

    /**
     * The headline defect: a loan that passes every loan-side filter ({@code loan.botLiquidatable()}
     * is true) but whose scanner assessment is {@code CONVERSION_TO_PRINCIPAL_REQUIRED} must report
     * {@code bot_liquidatable == false}, with the exact exclusion name as the reason. Asserted on
     * serialised JSON so the snake_case field names are proven, not just the record accessors.
     */
    @Test
    void convertExcludedLoanIsNotBotLiquidatable() {
        var loan = loan("cafe");
        assertTrue(loan.botLiquidatable(), "fixture must be loan-side liquidatable, or the test proves nothing");

        var node = json(controller(List.of(loan), List.of(convertExcluded("cafe"))).loans(false)).get(0);

        assertFalse(node.get("bot_liquidatable").asBoolean(),
                "a loan the scanner excludes as CONVERSION_TO_PRINCIPAL_REQUIRED must not report true");
        assertEquals("CONVERSION_TO_PRINCIPAL_REQUIRED", node.get("bot_liquidatable_reason").asText());
    }

    // ---- buildable loan -------------------------------------------------------------------

    @Test
    void buildableLoanIsBotLiquidatableWithNoReason() {
        var node = json(controller(List.of(loan("beef")), List.of(buildable("beef"))).loans(false)).get(0);

        assertTrue(node.get("bot_liquidatable").asBoolean());
        var reason = node.get("bot_liquidatable_reason");
        assertTrue(reason == null || reason.isNull(),
                "reason must be null exactly when bot_liquidatable is true");
    }

    // ---- no matching bond assessment ------------------------------------------------------

    /**
     * A loan whose bond is not locked at the LenderManager credential produces no assessment, so the
     * join misses. The verdict must be false with {@code BOND_NOT_DELEGATED} — never a fabricated
     * true. The scanner returns an assessment for a <em>different</em> loanId, proving the miss is by
     * loanId and not merely an empty batch.
     */
    @Test
    void loanWithNoBondAssessmentIsBondNotDelegated() {
        var node = json(controller(List.of(loan("f00d")), List.of(buildable("beef"))).loans(false)).get(0);

        assertFalse(node.get("bot_liquidatable").asBoolean());
        assertEquals("BOND_NOT_DELEGATED", node.get("bot_liquidatable_reason").asText());
    }

    // ---- bot_liquidatable_only filter -----------------------------------------------------

    /**
     * {@code bot_liquidatable_only=true} keeps exactly the buildable loans and drops the
     * convert-excluded one — the filter is the same scanner verdict as the per-row field, not the
     * loan-side type filter (both loans are loan-side liquidatable).
     */
    @Test
    void botLiquidatableOnlyKeepsOnlyTheBuildableLoan() {
        var controller = controller(
                List.of(loan("cafe"), loan("beef")),
                List.of(convertExcluded("cafe"), buildable("beef")));

        var filtered = controller.loans(true);

        assertEquals(1, filtered.size(), "only the buildable loan survives bot_liquidatable_only");
        assertEquals("beef", filtered.getFirst().loanId());
        assertTrue(filtered.getFirst().botLiquidatable());

        assertEquals(2, controller.loans(false).size(), "without the filter both loans are returned");
    }
}
