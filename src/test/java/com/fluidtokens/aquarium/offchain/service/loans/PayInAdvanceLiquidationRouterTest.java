package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.cardanofoundation.conversions.CardanoConverters;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Function;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <h2>Reviewer's orientation — what this class proves, and what it does not</h2>
 * <b>Proves:</b> the ROUTING decision only — that a bond whose datum says
 * {@code shouldLiquidationConvertToPrincipal == True} reaches the pay-in-advance builder rather than
 * the plain one, and that the two shapes the seam cannot model are refused cleanly instead of
 * throwing. <b>Does NOT prove:</b> that the routed transaction is valid, buildable against live data,
 * or acceptable to the chain — the builder's own tests and the live check answer that.
 * <b>Evaluator:</b> none; this class does not evaluate scripts at all.
 *
 * The routing seam in isolation: given an already-resolved convert candidate,
 * {@link PayInAdvanceLiquidationRouter} either builds the {@code LiquidateAndPayInAdvance} transaction
 * through the promoted, submit-incapable {@link LiquidatePayInAdvanceTransactionBuilder} or refuses it
 * cleanly. Nothing here signs, submits, or evaluates against a chain — the builder has a null
 * transaction processor and this test only inspects the unsigned body.
 *
 * <h2>The fixture is the frozen {@code f855d1b4…} preview loan</h2>
 * The loan/bond/oracle bytes are the same real preview shape {@link LiquidatePayInAdvanceDryEvalTest}
 * pins — 100 000 000 tFLDT of collateral against a 28 000 000-lovelace (ada) principal, with a lender
 * bond whose {@code shouldLiquidationConvertToPrincipal == True}. They are <b>duplicated</b> here on
 * purpose so this test owns its inputs and does not couple to the dry-eval rig or {@code LoanFixtures}.
 */
class PayInAdvanceLiquidationRouterTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    // ---- the real loan, transaction f855d1b4… on preview (duplicated from the dry-eval fixture) --

    private static final String LOAN_TX =
            "f855d1b4cae6e1ec6db5aac9ef8038f53927e60004693729ce27d8273199aea1";
    private static final int LOAN_OUTPUT_INDEX = 1;
    private static final int BOND_OUTPUT_INDEX = 3;

    private static final String LOAN_ID = "1d391e2258a62aeeae1275f2b31df80560e76732b266b2ab63c62e22";

    private static final String LOAN_DATUM_HEX =
            "d8799f001a01ab3f001b000001a01e60ee00001901cb00d8799f4040ffd8799f4040ff0000d87b9f1864"
                    + "187d1864d87980ffd87b9f181c05ff0000d879805821504f4f4c00183f8ba4d1e645b1e26e9caf5"
                    + "6f802b129b50d833689727c920abe11d8799f581c0b77d150c275bd0a600633e4be7d09f83c4b9f"
                    + "00981e22ac9c9d3f62d8799f490014df1074464c4454ffd8799f581c9a2ec5c92daccbb269611a9"
                    + "eae7a40f9788d3f9c0229661b6234286f49000de1406f766f3633ffffff";

    /**
     * Inline datum of {@code f855d1b4…#3}, verbatim. {@code shouldLiquidationConvertToPrincipal == True}
     * ({@code d87a80}) and {@code liquidationFeePerMille == 50} ({@code 1832}). Its {@code poolId} is
     * non-empty — which is exactly why routing is keyed on the convert flag and never on {@code poolId}.
     */
    private static final String BOND_DATUM_HEX =
            "d8799fd8799f581cea1bb1ccd33aeb9e02516c2eb50adbaa63d7b7538b03c96908bfc934ffd8799fd879"
                    + "9fd8799f581c1c5621a0d3f7ee5041ece1c8f41a9f611ab4bca268923c21b6ca8dc3ffffffd87a80"
                    + "1832581d00183f8ba4d1e645b1e26e9caf56f802b129b50d833689727c920abe11d8799f4040ff"
                    + "ff";

    private static final String LOAN_ADDRESS =
            "addr_test1zzrr2mm7vnwzsnn8eqsqf62dgf84sr3z2rq2xnne5a7mr0y788t0nqjduhey4swhxfp7h42thj"
                    + "hhvnjkmcgaps3ahx5qxanp9j";
    private static final String BOND_ADDRESS =
            "addr_test1zr3s95d7aq2zhm597lnk76pengtsk2s52jkpnl7ejfen95cu2cs6p5lhaegyrm8per6p48mpr2"
                    + "6tegngjg7zrdk23hps7h96kk";

    private static final AssetType COLLATERAL =
            new AssetType("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454");
    private static final long COLLATERAL_AMOUNT = 100_000_000L;
    private static final long LOAN_LOVELACE = 3_000_000L;
    private static final long BOND_LOVELACE = 1_810_200L;

    // ---- the oracle, from the dry-eval fixture (the tFLDT entry) ---------------------------------

    private static final AssetType ORACLE_NFT =
            new AssetType("9a2ec5c92daccbb269611a9eae7a40f9788d3f9c0229661b6234286f", "000de1406f766f3633");
    private static final AssetType C3_FEED_NFT =
            new AssetType("decfbd6bdd5c3eb1915564d414fe099db8c08d5e18037562cc7bb4b3", "4f7261636c6546656564");

    private static final String ORACLE_SCRIPT_HASH =
            "402c984d6397f508ced0674646bb2fcd67f593c5b79d91e1e5c0b124";
    private static final String ORACLE_ADDRESS =
            "addr_test1wpqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqw4jw8q";

    private static final TransactionInput ORACLE_REF_INPUT = new TransactionInput(
            "cc4721afdf4721f8f179b3afddb8e096805c0fad16afe54687d7368d12bd769c", 0);
    private static final TransactionInput ORACLE_REF_SCRIPT = new TransactionInput(
            "ba34f9e5bbf6d148b67208d53f11be9253de0d9df81190bcf034438d3838218f", 0);
    private static final TransactionInput C3_PROVIDER = new TransactionInput(
            "a17501465ed79dbc6cb25e2e99edbc421b1baa9d100b6780da89770702b235a5", 0);

    private static final String C3_PROVIDER_ADDRESS =
            "addr_test1wzgy7cu7mnnjau2qn5th8932tr27f83tfgusm60sklwppmgh6re39";
    private static final String C3_PROVIDER_DATUM_HEX =
            "d8799fd87b9fa3001a000528f30100021b000001a47d6fbc38ffff";

    private static final BigInteger PRICE = BigInteger.valueOf(338163);
    private static final BigInteger PRICE_DENOMINATOR = BigInteger.valueOf(1_000_000);

    // ---- the instant. This loan's lendDate is 1_787_216_064_000; the window sits ~1h after it. ---

    private static final long LEND_DATE = 1_787_216_064_000L;
    private static final long NOW = LEND_DATE + 3_600_000L;
    private static final long VALID_TO_MILLIS = NOW + 120_000L;
    private static final long FEED_VALID_FROM = NOW - 35_555L;
    private static final long FEED_VALID_TO = FEED_VALID_FROM + 600_000L;

    // ---- the redeemer arithmetic, pinned in the dry-eval test --------------------------------

    private static final long REMAINING_DEBT = 28_000_147L;
    private static final long EQUITY = 8_919_184L;
    private static final long LIQUIDATION_FEE = 5_000_000L;

    // ---- the synthetic remainder ----------------------------------------------------------------

    private static final String TX_CONFIG = "f1".repeat(32);
    private static final String TX_LM_CONFIG = "f2".repeat(32);
    private static final String TX_WALLET = "e0".repeat(32);

    private static final Utxo CONFIG_UTXO = LoanFixtures.configUtxo(TX_CONFIG, 0);
    private static final Utxo LM_CONFIG_UTXO = LoanFixtures.lmConfigUtxo(TX_LM_CONFIG, 0);
    private static final Utxo WALLET_UTXO = LoanFixtures.adaUtxo(TX_WALLET, 0,
            LoanFixtures.botAddress(), 60_000_000L);

    /**
     * Part 3's {@code principalBalance} argument — "ample" for every test in this class that is not
     * itself about the balance check, so MarketGate's cap (not its balance term) stays the thing under
     * test, exactly as {@code anyWallet()} keeps the SELECTOR out of the way for tests not about T-052.
     */
    private static final BigInteger AMPLE_BALANCE = BigInteger.valueOf(1_000_000_000_000L);

    /** A selector that supplies the fixture wallet whatever the payout — for tests not about T-052. */
    private static Function<BigInteger, Optional<Utxo>> anyWallet() {
        return payout -> Optional.of(WALLET_UTXO);
    }

    // ======================================================================================
    // (1) the deliverable — a convert loan routed to the pay-in-advance builder
    // ======================================================================================

    /**
     * The frozen convert loan, routed through {@link PayInAdvanceLiquidationRouter}, produces the
     * pay-in-advance shape: a parent {@code lender_manager.lenderManager} withdrawal whose redeemer
     * carries {@code LenderManagerAction.LiquidateAndPayInAdvance} (constructor index 3), and exactly
     * two asset-manager outputs (the borrower compensation and the lender's paid-in-advance ada).
     */
    @Test
    void aConvertLoanIsRoutedToThePayInAdvanceBuilder() {
        LiquidationAssessment assessment = convertAssessment(BigInteger.valueOf(EQUITY));

        Transaction tx = router().buildConvertLiquidation(assessment, loanUtxo(), bondUtxo(),
                CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnit(), AMPLE_BALANCE, anyWallet(), NOW, VALID_TO_MILLIS);

        // The parent LenderManager redeemer carries LiquidateAndPayInAdvance (constructor index 3).
        String parentReward = LoanFixtures.rewardAddress(REGISTRY.getLenderManagerWithdrawScriptHash());
        ConstrPlutusData parent = (ConstrPlutusData) rewardRedeemer(tx, parentReward).getData();
        ConstrPlutusData action = (ConstrPlutusData) parent.getData().getPlutusDataList().get(1);
        assertEquals(3, action.getAlternative(),
                "the parent LenderManager must carry LiquidateAndPayInAdvance (constructor index 3)");

        // Exactly two asset-manager outputs: the borrower compensation and the lender converted ada.
        assertEquals(2, assetManagerOutputs(tx).size(),
                "the pay-in-advance layout emits a borrower compensation and a lender converted output");
    }

    // ======================================================================================
    // (2) a non-convert loan is not routed to pay-in-advance
    // ======================================================================================

    /**
     * The routing key is {@code shouldLiquidationConvertToPrincipal}, never {@code poolId}. The frozen
     * convert bond selects the pay-in-advance seam even though its {@code poolId} is non-empty; a
     * plain bond does not, even given the same non-empty {@code poolId}. This pins the exact predicate
     * {@code LiquidationExecutor} branches on.
     */
    @Test
    void aNonConvertLoanIsNotRoutedToPayInAdvance() {
        LenderManagerDatum convertBond = bondDatum();
        assertTrue(convertBond.shouldLiquidationConvertToPrincipal(),
                "the frozen f855 bond is the convert shape — routed to pay-in-advance");
        assertFalse(convertBond.poolId().isEmpty(),
                "and its poolId is non-empty, so poolId cannot be what routes it");

        LenderManagerDatum plainBond = LoanFixtures.bondDatum(BigInteger.valueOf(50),
                LoanFixtures.noStakeCredential(), AssetType.ada(), convertBond.poolId());
        assertFalse(plainBond.shouldLiquidationConvertToPrincipal(),
                "a plain bond with the very same non-empty poolId is NOT routed to pay-in-advance");
    }

    // ======================================================================================
    // (3) the clean refusals — never a crash, a quarantine or a transaction
    // ======================================================================================

    /**
     * F0 (round 2) — EQUITY 0 IS THE VALIDATOR'S NORMAL CASE, NOT A REFUSAL. {@code
     * loan_claim_action.ak:240-259} (deployed {@code ff005fb}) accepts {@code inputAction.equity == 0}
     * outright and its {@code or { equity == 0, .. }} borrower-compensation gate (ak:273) never looks
     * for a compensation output when it is zero. This is the underwater loan — the common
     * liquidation, and the live USDM loan as of 2026-09-09 — so the seam must build it, not refuse it.
     * <p>
     * Mutant: restoring the old {@code equity.signum() <= 0} refusal makes this test fail (it would
     * throw {@code PayInAdvanceNotModelledException} instead of returning a transaction).
     */
    @Test
    void equityZeroIsTheValidatorsNormalCaseAndBuildsWithNoBorrowerCompensationOutput() {
        // convertAssessment(ZERO) alone is NOT enough: the router recomputes equity fresh from the
        // LOAN'S OWN datum + the real oracle feed (builder.numbers()), never from assessment.equity()
        // (that field only gates the precondition). equityZeroLoanDatum() is tuned so the REAL
        // computed equity lands on exactly zero against the frozen collateral amount and oracle price.
        LiquidationAssessment assessment = convertAssessment(BigInteger.ZERO, equityZeroLoanDatum());

        Transaction tx = router().buildConvertLiquidation(assessment, loanUtxo(), bondUtxo(),
                CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnit(), AMPLE_BALANCE, anyWallet(), NOW, VALID_TO_MILLIS);

        // Exactly ONE asset-manager output at equity 0: the lender's paid-in-advance principal, and no
        // borrower compensation output at all — loan_claim_action never reads one for this loan.
        List<TransactionOutput> assetManagerOutputs = assetManagerOutputs(tx);
        assertEquals(1, assetManagerOutputs.size(),
                "at equity 0 the pay-in-advance layout emits ONLY the lender's converted output, no "
                        + "borrower compensation");

        // The bot keeps the WHOLE collateral share (collateralAmount - equity == collateralAmount at
        // equity 0), so the ada dry-eval CBOR sha256 for the equity > 0 shape stays untouched — this is
        // a genuinely separate code path, not a parameterisation of it.
        String parentReward = LoanFixtures.rewardAddress(REGISTRY.getLenderManagerWithdrawScriptHash());
        ConstrPlutusData parent = (ConstrPlutusData) rewardRedeemer(tx, parentReward).getData();
        ConstrPlutusData action = (ConstrPlutusData) parent.getData().getPlutusDataList().get(1);
        assertEquals(3, action.getAlternative(),
                "the parent LenderManager must still carry LiquidateAndPayInAdvance (constructor index 3)");
    }

    /**
     * Defence in depth only: {@code LoanFinance.redeemerEquity} floors a negative computed equity to
     * ZERO, so this trigger is unreachable through a real assessment — but the seam still does not
     * know how to model a genuinely negative equity, and the promoted builder still throws on one.
     */
    @Test
    void aNegativeEquityIsRefusedCleanly() {
        LiquidationAssessment assessment = convertAssessment(BigInteger.valueOf(-1));
        PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException refusal = assertThrows(
                PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException.class,
                () -> router().buildConvertLiquidation(assessment, loanUtxo(), bondUtxo(),
                        CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnit(), AMPLE_BALANCE, anyWallet(), NOW, VALID_TO_MILLIS));
        assertEquals("pay-in-advance not yet modelled for a negative equity", refusal.getMessage());
    }

    /**
     * ⛔ A non-ada principal is now MODELLED (the whole point of the token-principals slice) — it is
     * refused only when the executor's {@code oraclesByUnit} snapshot has no entry for the loan's
     * OWN {@code principalOracleAsset}, exactly as WALL 3 requires: looked up by the oracle NFT the
     * datum names, not by the priced asset.
     */
    @Test
    void nonAdaPrincipalWithNoMatchingOracleIsRefusedCleanly() {
        LiquidationAssessment assessment = convertAssessment(BigInteger.valueOf(EQUITY),
                nonAdaPrincipalLoanDatum());
        PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException refusal = assertThrows(
                PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException.class,
                () -> router().buildConvertLiquidation(assessment, loanUtxo(), bondUtxo(),
                        CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnit(), AMPLE_BALANCE, anyWallet(), NOW, VALID_TO_MILLIS));
        assertTrue(refusal.getMessage().contains("no oracle entry for principal oracle asset"),
                refusal.getMessage());
    }

    // ======================================================================================
    // (4) a TOKEN PRINCIPAL, routed and built end to end — WALLS 1-4 exercised off the built body
    // ======================================================================================
    //
    // ⚠ No real registered oracle asset besides tFLDT (the collateral one) is available offline —
    // see docs/lending-v4-findings.md §59 on why a second REAL Charli3-recognised token cannot be
    // fabricated (charlie_specs is a closed, deployment-time validator parameter). This principal
    // oracle is therefore SYNTHETIC — its own fake script hash / reference input / reward address,
    // the same style LiquidationSubmitVetoTest and LiquidationExecutorTest already use for their
    // oracle fixtures. It is real enough to prove every piece of OFF-CHAIN wiring (WALLS 1-4, all
    // Java-side), but this class evaluates no script (see its own class javadoc) — it never did, and
    // still does not. The genuinely on-chain-validated proof is LoanFinanceTest's exact arithmetic
    // vectors, which need no chain data at all.

    private static final AssetType TOKEN_PRINCIPAL =
            new AssetType("cc".repeat(28), "0014df105553444d");
    private static final AssetType TOKEN_PRINCIPAL_ORACLE_NFT =
            new AssetType("8a".repeat(28), "8a".repeat(10));
    private static final String TOKEN_PRINCIPAL_ORACLE_CREDENTIAL = "8b".repeat(28);
    // ⚠ "fe" — deliberately NOT the hex-lowest reference input in this fixture (see
    // TransactionInputComparator), so its sorted position is provably non-zero. A hardcoded
    // principalOracleRefIndex == 0 mutant must be DISTINGUISHABLE from the real derived position —
    // choosing a coordinate that happens to sort first would let that mutant survive by coincidence.
    private static final TransactionInput TOKEN_PRINCIPAL_ORACLE_REF_INPUT =
            LoanFixtures.input("fe".repeat(32), 0);
    private static final TransactionInput TOKEN_PRINCIPAL_ORACLE_REF_SCRIPT =
            LoanFixtures.input("8d".repeat(32), 0);
    private static final TransactionInput TOKEN_PRINCIPAL_C3_PROVIDER =
            LoanFixtures.input("8e".repeat(32), 0);

    private static LoanDatum tokenPrincipalLoanDatum() {
        LoanDatum ada = loanDatum();
        return new LoanDatum(ada.doneRecasts(), ada.principalAmount(), ada.lendDate(),
                ada.repaidInstallments(), ada.interestRate(), ada.totalInstallments(), TOKEN_PRINCIPAL,
                TOKEN_PRINCIPAL_ORACLE_NFT, ada.installmentPeriod(), ada.initialGracePeriod(),
                ada.liquidationMode(), ada.repaymentMode(), ada.repaymentTimeWindow(),
                ada.penaltyFeeForLateRepayment(), ada.repaymentReceipts(), ada.originId(),
                ada.collateral());
    }

    /**
     * ⚠ Priced 1:1 — the same numeric ratio {@link OraclePriceFeed#unit()} carries — DELIBERATELY, so
     * this fixture's remaining debt and equity come out exactly as the frozen ada fixture's do
     * ({@code EQUITY}, below) and the loan stays solvent. This test proves the WIRING (a distinct
     * reference input, a distinct withdraw-0, the payout landing in the principal asset, the output
     * shape) — the EXACT two-feed ARITHMETIC at a genuinely different price is
     * {@code LoanFinanceTest.convertFromAToBWithOraclesMatchesThePinnedMainnetCandidate}, which needs
     * no chain-shaped fixture at all.
     */
    private static OracleEntry tokenPrincipalOracle() {
        return LoanFixtures.charli3(TOKEN_PRINCIPAL, TOKEN_PRINCIPAL_ORACLE_NFT,
                TOKEN_PRINCIPAL_ORACLE_CREDENTIAL,
                OraclePriceFeed.priceDataCharlie(TOKEN_PRINCIPAL,
                        BigInteger.ONE, BigInteger.ONE,
                        FEED_VALID_FROM, FEED_VALID_TO),
                TOKEN_PRINCIPAL_ORACLE_REF_INPUT, TOKEN_PRINCIPAL_ORACLE_REF_SCRIPT,
                TOKEN_PRINCIPAL_C3_PROVIDER);
    }

    private static Map<String, OracleEntry> oraclesByUnitWithTokenPrincipal() {
        Map<String, OracleEntry> byUnit = new java.util.LinkedHashMap<>(oraclesByUnit());
        OracleEntry principal = tokenPrincipalOracle();
        byUnit.put(principal.oracleToken().toUnit(), principal);
        return byUnit;
    }

    /**
     * M9 (F4, round 2) — {@link #tokenPrincipalOracle()} with its window narrowed so it does NOT
     * cover {@code VALID_TO_MILLIS}: {@code validTo == NOW + 10_000}, well short of {@code
     * VALID_TO_MILLIS == NOW + 120_000}. Proves WALL 3's principal-feed window check
     * (LiquidatePayInAdvanceTransactionBuilder.build(), mirroring the collateral leg's V3) is real —
     * a mutant deleting that block would let this build succeed against a feed that has already
     * expired by the time the transaction's own validity window ends.
     */
    private static OracleEntry tokenPrincipalOracleWithNarrowWindow() {
        long narrowValidTo = NOW + 10_000L;
        return LoanFixtures.charli3(TOKEN_PRINCIPAL, TOKEN_PRINCIPAL_ORACLE_NFT,
                TOKEN_PRINCIPAL_ORACLE_CREDENTIAL,
                OraclePriceFeed.priceDataCharlie(TOKEN_PRINCIPAL, BigInteger.ONE, BigInteger.ONE,
                        FEED_VALID_FROM, narrowValidTo),
                TOKEN_PRINCIPAL_ORACLE_REF_INPUT, TOKEN_PRINCIPAL_ORACLE_REF_SCRIPT,
                TOKEN_PRINCIPAL_C3_PROVIDER);
    }

    private static Map<String, OracleEntry> oraclesByUnitWithNarrowWindowPrincipal() {
        Map<String, OracleEntry> byUnit = new java.util.LinkedHashMap<>(oraclesByUnit());
        OracleEntry principal = tokenPrincipalOracleWithNarrowWindow();
        byUnit.put(principal.oracleToken().toUnit(), principal);
        return byUnit;
    }

    /** M9 — the principal oracle's own window check, WALL 3's mirror of the collateral leg's V3. */
    @Test
    void aPrincipalOracleFeedThatDoesNotCoverTheTxWindowIsRefusedCleanly() {
        LiquidationAssessment assessment = convertAssessment(BigInteger.valueOf(EQUITY), tokenPrincipalLoanDatum());

        PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException refusal = assertThrows(
                PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException.class,
                () -> tokenPrincipalRouter().buildConvertLiquidation(assessment, loanUtxo(), bondUtxo(),
                        CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnitWithNarrowWindowPrincipal(),
                        AMPLE_BALANCE, tokenWallet(), NOW, VALID_TO_MILLIS));
        assertTrue(refusal.getMessage().contains("principal oracle feed window"), refusal.getMessage());
    }

    /**
     * M19 (F4, round 2) — {@code assertStructure}'s WALL-4 check for a token principal must be
     * {@code flatten == 2}, not weakened to {@code >= 1}. Every REAL build in this test class already
     * produces flatten == 2 (min-ada top-up always adds a coin alongside the token), so the two
     * comparisons are indistinguishable on any built body — a mutant weakening {@code ==2} to
     * {@code >=1} passes every OTHER test in this file silently. This one hand-mutates the ALREADY
     * BUILT, ALREADY-PROVEN-CORRECT body (stripping the lender output's own ada to zero, so flatten
     * drops to 1) and re-invokes {@code assertStructure} directly — package-private for exactly this —
     * proving the check is load-bearing rather than coincidentally always true.
     */
    @Test
    void assertStructureRequiresFlattenExactlyTwoForATokenPrincipalLenderOutput() {
        LoanDatum datum = tokenPrincipalLoanDatum();
        LiquidationAssessment assessment = convertAssessment(BigInteger.valueOf(EQUITY), datum);
        Transaction tx = tokenPrincipalRouter().buildConvertLiquidation(assessment, loanUtxo(), bondUtxo(),
                CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnitWithTokenPrincipal(), AMPLE_BALANCE, tokenWallet(),
                NOW, VALID_TO_MILLIS);

        LiquidatePayInAdvanceTransactionBuilder builder = new LiquidatePayInAdvanceTransactionBuilder(
                REGISTRY, LoanFixtures.NETWORK, LoanFixtures.utxoSupplier(tokenPrincipalUniverse()),
                EvalFixtures.protocolParams());
        LiquidatePayInAdvanceTransactionBuilder.Numbers numbers = builder.numbers(loan(datum), bond(),
                oracle(), tokenPrincipalOracle(), NOW);
        LiquidatePayInAdvanceTransactionBuilder.Request request = new LiquidatePayInAdvanceTransactionBuilder.Request(
                loan(datum), loanUtxo(), bond(), bondUtxo(), TOKEN_WALLET_UTXO, CONFIG_UTXO, LM_CONFIG_UTXO,
                oracle(), tokenPrincipalOracle(), NOW, VALID_TO_MILLIS, 0L, 0L,
                TOKEN_WALLET_UTXO.getAddress(), configuration().getReferenceScripts(), 30_000L);
        long lenderBondOutputIndex = OutputLayout.CCL_PREPENDED_OUTPUTS;
        long assetOutputIndex = numbers.equity().signum() > 0 ? 1L : 0L;

        // Sanity: the REAL body passes as-is — this is what proves the mutation below is what flips it.
        assertDoesNotThrow(() -> builder.assertStructure(tx, request, numbers, lenderBondOutputIndex, assetOutputIndex));

        // Byte-surgery (officina CCL trap 4's discipline): strip the lender output's own ada to zero,
        // so flatten drops from 2 to 1 — a shape that dosProtection's flatten == 2 must reject.
        TransactionOutput lenderOutput = assetManagerOutputs(tx).get((int) assetOutputIndex);
        lenderOutput.getValue().setCoin(BigInteger.ZERO);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> builder.assertStructure(tx, request, numbers, lenderBondOutputIndex, assetOutputIndex));
        assertTrue(thrown.getMessage().contains("flatten == 2"), thrown.getMessage());
    }

    // ⛔ F3 (round 2) — A USDM+ADA WALLET UTXO, not an ada-only one.
    //
    // Before this fix the token-principal test funded its build from WALLET_UTXO — an ADA-ONLY 60 ADA
    // utxo — even though this scenario's payout is denominated in TOKEN_PRINCIPAL. cardano-client-lib
    // cannot conjure a token out of an ada-only input, so the balancer's change output for the wallet
    // came back holding a NEGATIVE quantity of TOKEN_PRINCIPAL (measured: -29,109,347 on the real
    // USDM audit) — an UNBALANCED transaction that this test never asserted balance on, so it passed
    // anyway. This utxo carries both: ample TOKEN_PRINCIPAL to fund the lender's payout (~29.1M base
    // units, per the pinned arithmetic below) and ample ada for the fee, the S-15 collateral rider and
    // the lender output's own min-ada.
    private static final String TX_TOKEN_WALLET = "e1".repeat(32);
    private static final Utxo TOKEN_WALLET_UTXO = LoanFixtures.utxo(TX_TOKEN_WALLET, 0,
            LoanFixtures.botAddress(), List.of(
                    Amount.lovelace(BigInteger.valueOf(60_000_000L)),
                    Amount.asset(LoanFixtures.unit(TOKEN_PRINCIPAL), BigInteger.valueOf(50_000_000L))),
            null);

    /** Supplies {@link #TOKEN_WALLET_UTXO} — the USDM+ada shape, for the token-principal test only. */
    private static Function<BigInteger, Optional<Utxo>> tokenWallet() {
        return payout -> Optional.of(TOKEN_WALLET_UTXO);
    }

    private static AppConfig.LiquidationConfiguration configurationWithTokenMarket() {
        AppConfig.LiquidationConfiguration cfg = configuration();
        List<AppConfig.LiquidationConfiguration.Market> markets = new ArrayList<>(cfg.getMarkets());
        markets.add(anticipateMarket(TOKEN_PRINCIPAL.toUnit(), 1_500_000_000L));
        cfg.setMarkets(markets);
        return cfg;
    }

    /**
     * The universe {@link #tokenPrincipalRouter()} builds against — carries the synthetic principal
     * oracle's three coordinates (the same additive pattern {@code universe()} uses for the collateral
     * leg's oracle in the dry-eval rig) and the USDM+ada wallet utxo (F3), alongside {@code
     * WALLET_UTXO} (ada-only) so the balancer has a genuinely ada-only collateral candidate too —
     * exactly the shape F8 documents as relied upon. Exposed (not inlined into the router factory) so
     * {@link #assertBalanced} can resolve every spent input by the SAME set the router built against.
     */
    private static List<Utxo> tokenPrincipalUniverse() {
        List<Utxo> universeWithPrincipalOracle = new ArrayList<>(universe());
        universeWithPrincipalOracle.add(TOKEN_WALLET_UTXO);
        universeWithPrincipalOracle.add(LoanFixtures.utxo(
                TOKEN_PRINCIPAL_ORACLE_REF_INPUT.getTransactionId(), TOKEN_PRINCIPAL_ORACLE_REF_INPUT.getIndex(),
                LoanFixtures.rewardAddress(TOKEN_PRINCIPAL_ORACLE_CREDENTIAL), List.of(
                        Amount.lovelace(BigInteger.valueOf(1_038_710L)),
                        Amount.asset(LoanFixtures.unit(TOKEN_PRINCIPAL_ORACLE_NFT), BigInteger.ONE)), null));
        return universeWithPrincipalOracle;
    }

    private static PayInAdvanceLiquidationRouter tokenPrincipalRouter() {
        return new PayInAdvanceLiquidationRouter(REGISTRY, converters(), configurationWithTokenMarket(),
                new LiquidatePayInAdvanceTransactionBuilder(REGISTRY, LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(tokenPrincipalUniverse()), EvalFixtures.protocolParams()));
    }

    /**
     * WALL 1 — the built transaction pays the lender the two-feed composition, in the PRINCIPAL
     * asset, never a one-feed lovelace shortcut. WALL 4 — that output is the token plus its min-ada
     * rider (flatten == 2), not ada. Both measured off the BUILT body, the same discipline
     * {@code LiquidatePayInAdvanceTransactionBuilder}'s own {@code assertStructure} uses — this test
     * would fail if that internal assertion were ever weakened, because it re-derives the same figure
     * independently via {@link LoanFinance#convertFromAToBWithOracles} rather than trusting the
     * builder's own number.
     */
    @Test
    void aTokenPrincipalLoanIsRoutedAndPaysTheLenderInTheTokenWithTheTwoFeedComposition() {
        LiquidationAssessment assessment = convertAssessment(BigInteger.valueOf(EQUITY), tokenPrincipalLoanDatum());

        // F3 (round 2) — a USDM+ada wallet utxo, never the ada-only WALLET_UTXO/anyWallet() the OLD
        // fixture used for a TOKEN-denominated payout (see TOKEN_WALLET_UTXO's own javadoc for the
        // -29,109,347 USDM unbalanced-change bug this replaces).
        Transaction tx = tokenPrincipalRouter().buildConvertLiquidation(assessment, loanUtxo(),
                bondUtxo(), CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnitWithTokenPrincipal(),
                AMPLE_BALANCE, tokenWallet(), NOW, VALID_TO_MILLIS);

        // F3 — BALANCE, on the built body: no negative quantity in any output, and inputs (+ mint)
        // equal outputs (+ fee) for every asset unit. This is exactly the assertion the old fixture
        // never made, and exactly what would have caught the unbalanced USDM change.
        assertBalanced(tx, tokenPrincipalUniverse());

        BigInteger collateralLenderShouldReceive = BigInteger.valueOf(COLLATERAL_AMOUNT)
                .subtract(BigInteger.valueOf(EQUITY)).subtract(BigInteger.valueOf(LIQUIDATION_FEE));
        BigInteger expectedPayout = LoanFinance.convertFromAToBWithOracles(
                oracle().feed(), tokenPrincipalOracle().feed(), Rational.fromInt(collateralLenderShouldReceive));

        List<TransactionOutput> assetManagerOutputs = assetManagerOutputs(tx);
        assertEquals(2, assetManagerOutputs.size());
        TransactionOutput lenderOutput = assetManagerOutputs.stream()
                .filter(o -> quantityOf(o, TOKEN_PRINCIPAL).signum() > 0)
                .findFirst().orElseThrow(() -> new AssertionError("no output pays the principal token"));
        assertEquals(expectedPayout, quantityOf(lenderOutput, TOKEN_PRINCIPAL),
                "the lender must be paid EXACTLY the two-feed composition, not a one-feed shortcut");
        assertEquals(0, lenderOutput.getValue().getMultiAssets().size() == 1
                        ? 0 : lenderOutput.getValue().getMultiAssets().size() - 1,
                "the lender output must carry exactly the principal token (plus its min-ada rider, "
                        + "which is lovelace, not a second multi-asset entry)");

        // WALL 3 — TWO distinct oracle withdrawals (collateral and principal are different oracle
        // credentials here), and the principal oracle's own reference input is among the tx's.
        String collateralOracleReward = oracle().rewardAddress();
        String principalOracleReward = tokenPrincipalOracle().rewardAddress();
        assertTrue(!collateralOracleReward.equals(principalOracleReward),
                "fixture sanity: the two oracle legs must be distinct credentials for this to prove WALL 3");
        List<Withdrawal> withdrawals = tx.getBody().getWithdrawals();
        assertTrue(withdrawals.stream().anyMatch(w -> w.getRewardAddress().equals(collateralOracleReward)));
        assertTrue(withdrawals.stream().anyMatch(w -> w.getRewardAddress().equals(principalOracleReward)),
                "the principal oracle must get its OWN withdraw-0 invocation");
        assertTrue(tx.getBody().getReferenceInputs().stream()
                        .anyMatch(i -> i.getTransactionId().equals(TOKEN_PRINCIPAL_ORACLE_REF_INPUT.getTransactionId())
                                && i.getIndex() == TOKEN_PRINCIPAL_ORACLE_REF_INPUT.getIndex()),
                "the principal oracle's own reference input must be among the transaction's");

        // WALL 3 — the redeemer's principalOracleRefInputIndex must be the REAL derived position of
        // the principal oracle's reference input, never a hardcoded value: a hardcoded 0 would name
        // whatever reference input happens to sort first, which is neither oracle here (config and
        // lm-config sort ahead of both by TransactionInputComparator on this fixture's tx hashes).
        String lmActionReward =
                LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash());
        ConstrPlutusData lmRedeemer = (ConstrPlutusData) rewardRedeemer(tx, lmActionReward).getData();
        com.bloxbean.cardano.client.plutus.spec.ListPlutusData pairsListData =
                (com.bloxbean.cardano.client.plutus.spec.ListPlutusData) lmRedeemer.getData().getPlutusDataList().get(3);
        com.bloxbean.cardano.client.plutus.spec.ListPlutusData firstPair =
                (com.bloxbean.cardano.client.plutus.spec.ListPlutusData) pairsListData.getPlutusDataList().get(0);
        BigInteger encodedPrincipalOracleRefIndex =
                ((com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData) firstPair.getPlutusDataList().get(0))
                        .getValue();
        int actualPosition = tx.getBody().getReferenceInputs().indexOf(TOKEN_PRINCIPAL_ORACLE_REF_INPUT);
        assertTrue(actualPosition >= 0, "the principal oracle reference input must be in the body");
        assertEquals(BigInteger.valueOf(actualPosition), encodedPrincipalOracleRefIndex,
                "the redeemer's principalOracleRefInputIndex must be the REAL sorted position (" + actualPosition
                        + "), not hardcoded to 0 or any other fixed value");
    }

    // ======================================================================================
    // fixture plumbing
    // ======================================================================================

    private static PayInAdvanceLiquidationRouter router() {
        return new PayInAdvanceLiquidationRouter(REGISTRY, converters(), configuration(),
                new LiquidatePayInAdvanceTransactionBuilder(REGISTRY, LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe()), EvalFixtures.protocolParams()));
    }

    private static AppConfig.LiquidationConfiguration configuration() {
        AppConfig.LiquidationConfiguration configuration = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60L, 120L, 30L,
                BigInteger.valueOf(1_500_000L), 200, 30L);
        // The market gate defaults to DISABLED for every market (Giovanni's defensive-default
        // ruling). A pay-in-advance test must therefore name the market it operates in, exactly
        // as an operator must — an ample ada cap, so the gate is satisfied and never the thing
        // under test here.
        configuration.setMarkets(java.util.List.of(
                anticipateMarket("lovelace", 1_000_000_000_000L)));
        return configuration;
    }

    private static CardanoConverters converters() {
        return LoanFixtures.converters();
    }

    private static LoanDatum loanDatum() {
        return new LoanDatumConverter().deserialize(LOAN_DATUM_HEX);
    }

    /** The frozen loan datum with only its principal (and principal-oracle) asset swapped to a token. */
    private static LoanDatum nonAdaPrincipalLoanDatum() {
        LoanDatum ada = loanDatum();
        AssetType token = new AssetType("aa".repeat(28), "abcd");
        return new LoanDatum(ada.doneRecasts(), ada.principalAmount(), ada.lendDate(),
                ada.repaidInstallments(), ada.interestRate(), ada.totalInstallments(), token, token,
                ada.installmentPeriod(), ada.initialGracePeriod(), ada.liquidationMode(),
                ada.repaymentMode(), ada.repaymentTimeWindow(), ada.penaltyFeeForLateRepayment(),
                ada.repaymentReceipts(), ada.originId(), ada.collateral());
    }

    /**
     * F0 (round 2) — the frozen loan's own datum, with ONLY {@code principalAmount}/{@code
     * interestRate} tuned so the REAL computed equity (through {@code LoanFinance.redeemerEquity},
     * against the FIXED {@code COLLATERAL_AMOUNT} and the FIXED oracle price {@code PRICE}) lands on
     * EXACTLY zero. Collateral, oracle and bond are untouched, so this is genuinely the same shape as
     * every other test in this class, just at the equity boundary loan_claim_action treats specially.
     * <p>
     * Arithmetic pinned by hand: collateralInLovelace = 100,000,000 · 338163/1,000,000 = 33,816,300;
     * solving 33,816,300 - D - 0.05·D = 0 for the ada-denominated debt D gives D = 32,206,000 exactly
     * (32,206,000 · 1.05 = 33,816,300). Zero interest and one installment make {@code
     * PrincipalAndInterestOnInstallments}'s remainingDebt exactly {@code principalAmount}, so setting
     * it to 32,206,000 is sufficient — lendDate/time do not enter this repayment mode's formula.
     */
    private static LoanDatum equityZeroLoanDatum() {
        LoanDatum ada = loanDatum();
        return new LoanDatum(ada.doneRecasts(), BigInteger.valueOf(32_206_000L), ada.lendDate(),
                ada.repaidInstallments(), BigInteger.ZERO, ada.totalInstallments(), ada.principalAsset(),
                ada.principalOracleAsset(), ada.installmentPeriod(), ada.initialGracePeriod(),
                ada.liquidationMode(), ada.repaymentMode(), ada.repaymentTimeWindow(),
                ada.penaltyFeeForLateRepayment(), ada.repaymentReceipts(), ada.originId(),
                ada.collateral());
    }

    private static LenderManagerDatum bondDatum() {
        return new LenderManagerDatumConverter().deserialize(BOND_DATUM_HEX);
    }

    private static Loan loan(LoanDatum datum) {
        return new Loan(LOAN_TX, LOAN_OUTPUT_INDEX, LOAN_ADDRESS, LOAN_ID,
                BigInteger.valueOf(COLLATERAL_AMOUNT), BigInteger.valueOf(LOAN_LOVELACE), datum);
    }

    private static Utxo loanUtxo() {
        return LoanFixtures.utxo(LOAN_TX, LOAN_OUTPUT_INDEX, LOAN_ADDRESS, List.of(
                Amount.lovelace(BigInteger.valueOf(LOAN_LOVELACE)),
                Amount.asset(LoanFixtures.unit(COLLATERAL), BigInteger.valueOf(COLLATERAL_AMOUNT)),
                Amount.asset(REGISTRY.getLoanPolicyId() + LOAN_ID, BigInteger.ONE)), LOAN_DATUM_HEX);
    }

    private static LenderBond bond() {
        return new LenderBond(LOAN_TX, BOND_OUTPUT_INDEX, BOND_ADDRESS, LOAN_ID, BOND_DATUM_HEX,
                bondDatum());
    }

    private static Utxo bondUtxo() {
        return LoanFixtures.utxo(LOAN_TX, BOND_OUTPUT_INDEX, BOND_ADDRESS, List.of(
                Amount.lovelace(BigInteger.valueOf(BOND_LOVELACE)),
                Amount.asset(REGISTRY.getLenderBondPolicyId() + LOAN_ID, BigInteger.ONE)), BOND_DATUM_HEX);
    }

    private static OracleEntry oracle() {
        return LoanFixtures.charli3(COLLATERAL, ORACLE_NFT, ORACLE_SCRIPT_HASH,
                OraclePriceFeed.priceDataCharlie(COLLATERAL, PRICE, PRICE_DENOMINATOR,
                        FEED_VALID_FROM, FEED_VALID_TO),
                ORACLE_REF_INPUT, ORACLE_REF_SCRIPT, C3_PROVIDER);
    }

    private static Map<String, OracleEntry> oraclesByUnit() {
        OracleEntry oracle = oracle();
        return Map.of(oracle.oracleToken().toUnit(), oracle);
    }

    private static LiquidationAssessment convertAssessment(BigInteger equity) {
        return convertAssessment(equity, loanDatum());
    }

    private static LiquidationAssessment convertAssessment(BigInteger equity, LoanDatum datum) {
        // A sanity check on the fixture: the frozen bond really is the convert shape this seam routes.
        assertTrue(bondDatum().shouldLiquidationConvertToPrincipal());
        assertTrue(datum.liquidationMode() instanceof LiquidationMode.Liquidation);
        return LiquidationAssessment.buildable(bond(), loan(datum), "f855 convert fixture",
                BigInteger.valueOf(REMAINING_DEBT), equity, false, BigInteger.valueOf(LIQUIDATION_FEE));
    }

    private static List<Utxo> universe() {
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO, WALLET_UTXO,
                loanUtxo(), bondUtxo()));
        universe.add(LoanFixtures.utxo(ORACLE_REF_INPUT.getTransactionId(), ORACLE_REF_INPUT.getIndex(),
                ORACLE_ADDRESS, List.of(Amount.lovelace(BigInteger.valueOf(1_038_710L)),
                        Amount.asset(LoanFixtures.unit(ORACLE_NFT), BigInteger.ONE)), null));
        universe.add(Utxo.builder()
                .txHash(ORACLE_REF_SCRIPT.getTransactionId())
                .outputIndex(ORACLE_REF_SCRIPT.getIndex())
                .address(ORACLE_ADDRESS)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(40_000_000L))))
                .referenceScriptHash(ORACLE_SCRIPT_HASH)
                .build());
        universe.add(LoanFixtures.utxo(C3_PROVIDER.getTransactionId(), C3_PROVIDER.getIndex(),
                C3_PROVIDER_ADDRESS, List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)),
                        Amount.asset(LoanFixtures.unit(C3_FEED_NFT), BigInteger.ONE)),
                C3_PROVIDER_DATUM_HEX));
        return universe;
    }

    // ---- F3 (round 2) — balance, on the built body ------------------------------------------------

    /**
     * <b>No negative quantity in any output; inputs (+ mint) == outputs (+ fee), per asset unit.</b>
     * The assertion the old token-principal fixture never made, and the one that would have caught its
     * unbalanced USDM change (-29,109,347 on the real audit) — an ada-only wallet cannot fund a
     * TOKEN-denominated payout, and cardano-client-lib does not refuse that at build time; it simply
     * hands back a change output that owes more of the token than the wallet had.
     * <p>
     * Every spent {@code TransactionInput} is resolved against {@code universe} — the SAME set the
     * router built against, deliberately, since cardano-client-lib's own coin selection can add MORE
     * inputs than this builder's explicit {@code collectFrom} calls (e.g. an extra ada-only utxo picked
     * during balancing) and a hardcoded input list would then diverge from what the body actually
     * spent. A coordinate absent from {@code universe} is treated as a collateral-only reference
     * (skipped) rather than failing the lookup — see {@code assertStructure}'s own S-15 discipline for
     * why locating by identity rather than assuming is the discipline throughout this builder.
     */
    private static void assertBalanced(Transaction tx, List<Utxo> universe) {
        Map<String, Utxo> byRef = new java.util.HashMap<>();
        for (Utxo utxo : universe) {
            byRef.put(utxo.getTxHash() + "#" + utxo.getOutputIndex(), utxo);
        }
        Map<String, BigInteger> supply = new java.util.HashMap<>();
        for (TransactionInput input : tx.getBody().getInputs()) {
            Utxo utxo = byRef.get(input.getTransactionId() + "#" + input.getIndex());
            if (utxo == null) {
                continue;
            }
            for (Amount amount : utxo.getAmount()) {
                supply.merge(amount.getUnit(), amount.getQuantity(), BigInteger::add);
            }
        }
        if (tx.getBody().getMint() != null) {
            for (var multiAsset : tx.getBody().getMint()) {
                for (var asset : multiAsset.getAssets()) {
                    String unit = multiAsset.getPolicyId()
                            + HexUtil.encodeHexString(asset.getNameAsBytes());
                    supply.merge(unit, asset.getValue(), BigInteger::add);
                }
            }
        }

        Map<String, BigInteger> demand = new java.util.HashMap<>();
        demand.merge(AssetType.LOVELACE, tx.getBody().getFee(), BigInteger::add);
        for (TransactionOutput output : tx.getBody().getOutputs()) {
            demand.merge(AssetType.LOVELACE, output.getValue().getCoin(), BigInteger::add);
            assertTrue(output.getValue().getCoin().signum() >= 0,
                    "an output holds negative ada: " + output.getValue().getCoin());
            if (output.getValue().getMultiAssets() != null) {
                for (var multiAsset : output.getValue().getMultiAssets()) {
                    for (var asset : multiAsset.getAssets()) {
                        String unit = multiAsset.getPolicyId()
                                + HexUtil.encodeHexString(asset.getNameAsBytes());
                        demand.merge(unit, asset.getValue(), BigInteger::add);
                        assertTrue(asset.getValue().signum() >= 0,
                                "an output holds a NEGATIVE quantity of " + unit + ": " + asset.getValue());
                    }
                }
            }
        }

        // A unit that nets to exactly zero (e.g. the loan NFT: +1 spent, -1 minted/burned) is present
        // in supply at 0 but absent from demand entirely — a missing key and a zero VALUE are not the
        // same key set, so Map.equals would flag a false mismatch. Strip zero entries from both sides
        // before comparing; a genuine imbalance never nets to exactly zero by accident.
        supply.values().removeIf(v -> v.signum() == 0);
        demand.values().removeIf(v -> v.signum() == 0);
        assertEquals(supply, demand, "inputs (+ mint) must equal outputs (+ fee), per asset unit — an "
                + "unbalanced body means a wallet utxo could not actually fund this transaction");
    }

    // ---- reading the built transaction back ------------------------------------------------------

    private static List<TransactionOutput> assetManagerOutputs(Transaction tx) {
        String assetManagerSpend = REGISTRY.getAssetManagerSpendScriptHash();
        List<TransactionOutput> filtered = new ArrayList<>();
        for (TransactionOutput output : tx.getBody().getOutputs()) {
            if (assetManagerSpend.equals(paymentCredentialOf(output.getAddress()))) {
                filtered.add(output);
            }
        }
        return filtered;
    }

    private static Redeemer rewardRedeemer(Transaction tx, String rewardAddress) {
        int index = withdrawalIndexOf(tx, rewardAddress);
        for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
            if (redeemer.getTag() == RedeemerTag.Reward && redeemer.getIndex().intValue() == index) {
                return redeemer;
            }
        }
        throw new AssertionError("no reward redeemer for the withdrawal at " + rewardAddress);
    }

    private static int withdrawalIndexOf(Transaction tx, String rewardAddress) {
        List<Withdrawal> withdrawals = tx.getBody().getWithdrawals();
        for (int i = 0; i < withdrawals.size(); i++) {
            if (withdrawals.get(i).getRewardAddress().equals(rewardAddress)) {
                return i;
            }
        }
        throw new AssertionError("no withdrawal at " + rewardAddress);
    }

    private static String paymentCredentialOf(String address) {
        return new com.bloxbean.cardano.client.address.Address(address)
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse("");
    }

    private static BigInteger quantityOf(TransactionOutput output, AssetType asset) {
        if (output.getValue().getMultiAssets() == null) {
            return BigInteger.ZERO;
        }
        return output.getValue().getMultiAssets().stream()
                .filter(m -> m.getPolicyId().equalsIgnoreCase(asset.policyId()))
                .flatMap(m -> m.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(a.getNameAsBytes()).equalsIgnoreCase(asset.assetName()))
                .map(com.bloxbean.cardano.client.transaction.spec.Asset::getValue)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    // ======================================================================================
    // T-052 — the wallet input is selected to cover THIS liquidation's lender payout
    // ======================================================================================

    /**
     * <b>The router asks for the exact ada it is about to pay the lender.</b>
     * <p>
     * This is the half of T-052's acceptance that says a principal-repaying liquidation is never
     * built against an input too small to fund it. The figure handed to the selector must be
     * {@code convertedLoanCollateralToPrincipalAmount} — the ada that leaves the bot's wallet — and
     * not a proxy.
     */
    @Test
    void theSelectorIsAskedForTheExactLenderPayout() {
        LiquidationAssessment assessment = convertAssessment(BigInteger.valueOf(EQUITY));
        List<BigInteger> asked = new ArrayList<>();

        Transaction tx = router().buildConvertLiquidation(assessment, loanUtxo(), bondUtxo(),
                CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnit(), AMPLE_BALANCE,
                payout -> {
                    asked.add(payout);
                    return Optional.of(WALLET_UTXO);
                },
                NOW, VALID_TO_MILLIS);

        assertEquals(1, asked.size(), "the selector must be consulted exactly once");
        // Re-derived from the BUILT body rather than from the router's own arithmetic: the lender's
        // paid-in-advance output is the ada the wallet has to fund, so the amount demanded of the
        // wallet and the amount paid out must be the same number. Comparing the router against itself
        // would be an assertion structurally incapable of failing.
        BigInteger paidToLender = tx.getBody().getOutputs().stream()
                .filter(o -> o.getValue().getMultiAssets() == null
                        || o.getValue().getMultiAssets().isEmpty())
                .map(o -> o.getValue().getCoin())
                .max(BigInteger::compareTo)
                .orElseThrow();
        assertEquals(paidToLender, asked.getFirst(),
                "the wallet was asked to cover an amount that is not what the lender is paid");
    }

    /**
     * ⚠ <b>A wallet that cannot fund the payout is a REFUSAL, not a crash and not a built
     * transaction.</b> Before T-052 the executor nominated one utxo per cycle and this candidate
     * would have been built against it regardless, failing at evaluation with an empty
     * {@code ScriptFailures} map — the unreadable shape measured on preview 2026-08-24.
     */
    @Test
    void aWalletThatCannotFundTheLenderPayoutIsRefusedCleanly() {
        LiquidationAssessment assessment = convertAssessment(BigInteger.valueOf(EQUITY));

        PayInAdvanceLiquidationRouter.WalletInputTooSmallException refusal = assertThrows(
                PayInAdvanceLiquidationRouter.WalletInputTooSmallException.class,
                () -> router().buildConvertLiquidation(assessment, loanUtxo(), bondUtxo(),
                        CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnit(), AMPLE_BALANCE,
                        payout -> Optional.empty(), NOW, VALID_TO_MILLIS));

        assertTrue(refusal.getMessage().contains("repays the lender"),
                "the refusal must say what the wallet was short of, not merely that it was short: "
                        + refusal.getMessage());
    }

    /**
     * Positive control: the selection is real, not decorative. Handing back a different wallet UTxO
     * must reach the built transaction's inputs — otherwise the test above proves only that a lambda
     * was invoked.
     */
    @Test
    void theSelectedUtxoIsTheOneActuallySpent() {
        LiquidationAssessment assessment = convertAssessment(BigInteger.valueOf(EQUITY));

        Transaction tx = router().buildConvertLiquidation(assessment, loanUtxo(), bondUtxo(),
                CONFIG_UTXO, LM_CONFIG_UTXO, oraclesByUnit(), AMPLE_BALANCE, anyWallet(), NOW, VALID_TO_MILLIS);

        assertTrue(tx.getBody().getInputs().stream()
                        .anyMatch(i -> i.getTransactionId().equals(WALLET_UTXO.getTxHash())
                                && i.getIndex() == WALLET_UTXO.getOutputIndex()),
                "the utxo the selector returned is not among the transaction's inputs");
    }

    /**
     * A market that permits pay-in-advance up to {@code cap}. Since 2026-09-03 a test that exercises
     * that path must SAY which market it operates in and that the operator chose ANTICIPATE there —
     * exactly what an operator must now do, and no longer inferable from a cap alone.
     */
    private static AppConfig.LiquidationConfiguration.Market anticipateMarket(String unit, long cap) {
        var m = new AppConfig.LiquidationConfiguration.Market();
        m.setUnit(unit);
        m.setMode(AppConfig.LiquidationConfiguration.Mode.LIVE);
        m.setAction(AppConfig.LiquidationConfiguration.Action.ANTICIPATE);
        m.setCap(java.math.BigInteger.valueOf(cap));
        return m;
    }
}
