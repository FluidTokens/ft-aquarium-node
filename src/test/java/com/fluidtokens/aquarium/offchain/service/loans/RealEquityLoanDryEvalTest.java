package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.TransactionInputComparator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>The acceptance gate for positive-equity liquidation:</b> preview loan
 * {@code 69aee8a0…#1} — a real, third-party FluidTokens loan whose collateral is worth <em>more</em>
 * than its debt — liquidated through {@link LiquidateTransactionBuilder} exactly as production would,
 * and handed to the real PlutusV3 machine against the deployed validators.
 *
 * <h2>Why this loan needed a file of its own</h2>
 * {@link RealLoanDryEvalTest}'s loan is deeply underwater: its equity clamps to zero, so
 * {@code loan_claim_action.ak:273}'s {@code or { inputAction.equity == 0, .. }} short-circuits and the
 * whole borrower-compensation branch of the validator is never reached. Every real-data evaluation in
 * this repo until now took that short circuit. This loan does not: its LTV is 0.828, above the 0.8
 * liquidation threshold but below 1, so it is liquidatable <em>and</em> the borrower is owed the
 * residue. {@code equity_sent_to_borrower} executes for real here — its datum equality, its
 * {@code >=} amount check, its receipt condition and its {@code dosProtection} flatten count — and so
 * does the positional constraint that forced rule R into existence.
 *
 * <h2>What rule R is, and what this file proves about it</h2>
 * {@code loan_claim_action.ak:275-284} reads the compensation output as
 * {@code safe_list_at(get_outputs_to_smart_credential(..), index)} — the bare loan index, with no
 * redeemer indirection. {@code lm_liquidate_action.ak:87-91} reads the claimed-collateral output as
 * {@code safe_list_at(assetOutputs, safe_list_at(redeemer.assetOutputIndexes, index))} — through a
 * field the builder chooses. So the compensation output goes at the forced slot and
 * {@code assetOutputIndexes} yields. That is rule R, and
 * {@link #theRealPositiveEquityLiquidationEvaluatesAgainstTheDeployedValidators()} is the evidence
 * that the deployed scripts accept it on real data rather than only on synthetic fixtures.
 * <p>
 * {@link #thePreRuleRLayoutIsRejectedByLoanClaimActionOnThisLoanToo()} is the other half: the layout
 * this builder emitted before rule R, on this same real loan, is refused on chain. Without it, "rule
 * R" would be an unfalsifiable preference.
 *
 * <h2>⚠ WHAT AN OPERATOR MUST KNOW ABOUT THIS LOAN</h2>
 * <b>Its {@code liquidationFeePerMille} is ZERO.</b> Liquidating it earns the operator nothing, and
 * it costs the operator <b>two</b> things, both measured off the body this file builds and both
 * pinned as assertions below:
 * <ul>
 *   <li>a transaction fee of <b>1_364_238 lovelace</b> (~1.36 ADA), and</li>
 *   <li>a min-ada rider of <b>1_655_040 lovelace</b> (~1.66 ADA) on the borrower's compensation
 *       output — see {@link #COMPENSATION_MIN_ADA}. This loan's equity is denominated in tFLDT, so
 *       the builder emits that output carrying no ada at all and cardano-client-lib tops it to the
 *       floor <em>out of the bot's own inputs</em>. It is the borrower's money on arrival and the
 *       bot's on departure.</li>
 * </ul>
 * <b>That is ~2.19 ADA out of pocket, not ~1.4</b> — {@code COMPENSATION_MIN_ADA} 1,655,040 plus
 * {@code TX_FEE} 539,546.
 *
 * <p>⛔ <b>AND THE VERDICT FLIPPED ON 2026-09-04, which is worth more than the number.</b> The outlay
 * used to be ~3.02 ADA and the note here said so: against the preview
 * {@code profit-margin-lovelace} override of −3,000,000 (shipped default +1,500,000, and <b>that
 * override is preview-only and must never reach mainnet</b>), a fee slice of zero minus 3,019,278 of
 * outlay minus a −3,000,000 margin came to <b>−19,278</b> — a loan the node should decline, by
 * nineteen thousand lovelace.
 *
 * <p><b>Re-measured after the fixtures moved to the FOURTH deployment, the fee is 824,692 lovelace
 * lower</b> (evaluation cost against a different ConfigDatum; the body layout and the synthetic
 * reference-script coordinates did not change). The same arithmetic is now
 * {@code 0 − 2,194,586 − (−3,000,000) = +805,414} — <b>a loan the node would ACCEPT.</b>
 *
 * <p>⚠ <b>Read that as what it is.</b> Nothing about the loan changed and nothing about the operator's
 * policy changed; a fee moved and a decision inverted, because the scenario was sitting nineteen
 * thousand lovelace from the line. <b>A margin calibrated that finely is not a policy, it is a
 * coincidence</b> — and the same override will straddle differently again at the next redeploy.
 * <p>
 * <b>It does not decline it today.</b> {@code LiquidationExecutor}'s expected-profit arithmetic is
 * {@code fee slice − tx fee − margin} and carries no min-ada term, so it scores this loan at
 * {@code 0 − 1_364_238 − (−3_000_000) = +1_635_762} and calls it profitable. The gap is recorded in
 * that method's javadoc and is a profitability-model question, not this file's — nothing here changes
 * it. {@code LiquidateTransactionBuilder.assertCompensationSubsidyBounded} caps how large the
 * unaccounted rider can get; it does not make the arithmetic count it.
 * <p>
 * What this file proves is a <em>capability</em>, not revenue: the bot can now build a liquidation
 * that pays the borrower back. Whether any given loan is worth liquidating is still the profitability
 * check's question, and on this one the honest answer is no.
 *
 * <h2>Fixture provenance</h2>
 * Read off preview with Blockfrost on <b>2026-08-19</b>, and pinned as literals so the test runs cold
 * and deterministically thereafter — <b>no network, no key, no wallet, no env var</b>, and nothing is
 * ever signed or submitted.
 * <ul>
 *   <li>Loan UTxO: {@code 69aee8a016d4d20a49404486c12d8986d7ba4b7bac520840a966f1169a2eecd6#1} —
 *       3 ADA, 100_000_000 tFLDT, the loan NFT, inline datum {@link #LOAN_DATUM_HEX}. Unspent when
 *       read.</li>
 *   <li>Lender bond UTxO: the same transaction, {@code #3} — 1_805_890 lovelace, the lender-bond NFT,
 *       inline datum {@link #BOND_DATUM_HEX}. Unspent when read.</li>
 *   <li>Oracle: the tFLDT entry of {@code https://testapi.fluidtokens.com/get-oracle-tokens}, price
 *       {@code 338163/1000000}, with its three on-chain UTxOs.</li>
 * </ul>
 * The two config reference inputs and the applied loans-v4 scripts come from {@link LoanFixtures} and
 * {@link LoansContractRegistry} respectively, so the validators that run are the deployed ones. Only
 * the bot's wallet UTxO and the six reference-script <em>coordinates</em> are synthetic; the UTxOs
 * built for those coordinates carry the freshly derived script hashes, which is what
 * {@link EvalFixtures#scriptSupplier} resolves on.
 */
@Slf4j
class RealEquityLoanDryEvalTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    // ---- the loan and the bond, verbatim from preview -------------------------------------------

    private static final String LOAN_TX =
            "69aee8a016d4d20a49404486c12d8986d7ba4b7bac520840a966f1169a2eecd6";
    private static final int LOAN_OUTPUT_INDEX = 1;
    private static final int BOND_OUTPUT_INDEX = 3;
    private static final String LOAN_ID = "724088bab4719b698cf5c74bb6f4c8ec5a28a5a2ccaf2c2975f50e1e";

    private static final String LOAN_DATUM_HEX =
            "d8799f001a01ab3f001b000001a0193f25e0001901cb00d8799f4040ffd8799f4040ff0000d87b9f1864"
                    + "187d1864d87980ffd87b9f181c05ff0000d879805821504f4f4c0001357d1b94be1b55ea87f85fe"
                    + "9c5236f647a09506578dc493f791259d8799f581c0b77d150c275bd0a600633e4be7d09f83c4b9f"
                    + "00981e22ac9c9d3f62d8799f490014df1074464c4454ffd8799f581c9a2ec5c92daccbb269611a9"
                    + "eae7a40f9788d3f9c0229661b6234286f49000de1406f766f3633ffffff";

    private static final String BOND_DATUM_HEX =
            "d8799fd8799f581cea1bb1ccd33aeb9e02516c2eb50adbaa63d7b7538b03c96908bfc934ffd8799fd879"
                    + "9fd8799f581c1c5621a0d3f7ee5041ece1c8f41a9f611ab4bca268923c21b6ca8dc3ffffffd87980"
                    + "00581d0001357d1b94be1b55ea87f85fe9c5236f647a09506578dc493f791259d8799f4040ffff";

    // ⛔ DERIVED FROM THE REGISTRY, not pinned as bech32. Literal addresses here survived the
    // 2026-09-04 re-point looking correct and then failed as RequiredRedeemersMismatch /
    // STRUCTURAL_ASSERTION_FAILED — the inputs stayed at the old script while every redeemer named
    // the new one. Those errors name script hashes and never mention addresses, so a stale fixture
    // reads as a builder bug. The stake halves are the real ones from the recorded UTxOs.
    private static final String LOAN_STAKE_KEY = "9e39d6f9824de5f24ac1d73243ebd54bbcaf764e56de11d0c23db9a8";
    private static final String LENDER_STAKE_KEY = "1c5621a0d3f7ee5041ece1c8f41a9f611ab4bca268923c21b6ca8dc3";

    private static final String LOAN_ADDRESS =
            LoanFixtures.baseScriptAddress(REGISTRY.getLoanSpendScriptHash(), LOAN_STAKE_KEY);
    private static final String BOND_ADDRESS =
            LoanFixtures.baseScriptAddress(REGISTRY.getLenderManagerSpendScriptHash(), LENDER_STAKE_KEY);

    /** tFLDT — the collateral, and therefore also the currency the equity is denominated in. */
    private static final AssetType COLLATERAL =
            new AssetType("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454");
    private static final long COLLATERAL_AMOUNT = 100_000_000L;
    private static final long LOAN_LOVELACE = 3_000_000L;
    private static final long BOND_LOVELACE = 1_805_890L;
    private static final long PRINCIPAL = 28_000_000L;

    // ---- the oracle ------------------------------------------------------------------------------

    private static final AssetType ORACLE_NFT =
            new AssetType("9a2ec5c92daccbb269611a9eae7a40f9788d3f9c0229661b6234286f", "000de1406f766f3633");
    private static final AssetType C3_FEED_NFT =
            new AssetType("decfbd6bdd5c3eb1915564d414fe099db8c08d5e18037562cc7bb4b3", "4f7261636c6546656564");

    private static final String ORACLE_SCRIPT_HASH =
            "402c984d6397f508ced0674646bb2fcd67f593c5b79d91e1e5c0b124";
    private static final String ORACLE_ADDRESS =
            "addr_test1wpqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqw4jw8q";
    private static final String ORACLE_REWARD_ADDRESS =
            "stake_test17pqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqwavks2";

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
    private static final long FEED_VALID_FROM = 1_787_135_064_288L;
    private static final long FEED_VALID_TO = 1_787_135_664_288L;

    // ---- the instant -----------------------------------------------------------------------------

    /**
     * A <b>pinned</b> instant, never wall clock: 2026-08-19T10:25:00Z. Inside the pinned feed window
     * with 444_288 ms of feed left after {@link #VALID_TO}, against the 300_000 ms margin the request
     * asks for.
     * <p>
     * At the pinned price, 100_000_000 tFLDT is worth 33_816_300 lovelace against
     * {@link #DEBT_AT_VALID_FROM} of debt — an ltv of 0.828 against the datum's 100/125 = 0.8
     * threshold. Liquidatable, and <b>below 1</b>, which is what makes the equity positive.
     */
    private static final long NOW = 1_787_135_100_000L;
    private static final long VALID_FROM = NOW;
    private static final long VALID_TO = NOW + 120_000L;
    private static final long MARGIN = 300_000L;

    /**
     * The two figures the redeemer carries, at the transaction's {@code validFrom}. Pinned as
     * literals rather than recomputed, so a change in {@link LoanFinance} shows up here rather than
     * moving the expectation with the code.
     */
    private static final BigInteger DEBT_AT_VALID_FROM = BigInteger.valueOf(28_000_210L);
    private static final BigInteger EQUITY = BigInteger.valueOf(8_918_979L);
    /** {@code liquidationFeePerMille} is 0 on this bond — see the operator warning in the javadoc. */
    private static final BigInteger LIQUIDATION_FEE = BigInteger.ZERO;
    private static final BigInteger COLLATERAL_PAYOUT =
            BigInteger.valueOf(COLLATERAL_AMOUNT).subtract(EQUITY).subtract(LIQUIDATION_FEE);
    /**
     * The lovelace on the borrower's compensation output — <b>entirely a subsidy the bot funds</b>,
     * because this loan's equity is denominated in tFLDT and the output carries no ada of its own.
     * Measured off the built body, not computed, and pinned so it cannot drift unnoticed: it is the
     * figure the operator warning in this class's javadoc adds to the transaction fee.
     */
    private static final long COMPENSATION_MIN_ADA = 1_655_040L;
    /**
     * The balanced transaction fee, pinned for the same reason: together with
     * {@link #COMPENSATION_MIN_ADA} it is the operator's whole outlay on this loan, and the operator
     * warning in this class's javadoc quotes both.
     */
    private static final long TX_FEE = 539_546L;
    // ⚠ RE-MEASURED 2026-09-04, from 1,364,238 — a 824,692 lovelace DROP, and it is not a relaxation.
    // The fixtures moved from the THIRD preview deployment to the FOURTH (Giovanni's ruling), which
    // changes the ConfigDatum this transaction reads as a reference input. The fee is size + ex-units,
    // the reference-script coordinates here are synthetic and unchanged, and the body layout is
    // unchanged — so the difference is EVALUATION COST against a different config datum. The figure is
    // an OUTPUT of the build, pinned so the operator note below quotes a measured number rather than a
    // remembered one; re-pinning it is what keeps that promise. The operator's outlay on this loan is
    // now COMPENSATION_MIN_ADA + TX_FEE ≈ 2.19 ADA, not the ~3.02 the note used to quote.

    // ---- the synthetic remainder -----------------------------------------------------------------

    private static final String TX_CONFIG = "f1".repeat(32);
    private static final String TX_LM_CONFIG = "f2".repeat(32);
    private static final String TX_WALLET = "e0".repeat(32);

    private static final Utxo CONFIG_UTXO = LoanFixtures.configUtxo(TX_CONFIG, 0);
    private static final Utxo LM_CONFIG_UTXO = LoanFixtures.lmConfigUtxo(TX_LM_CONFIG, 0);
    private static final Utxo WALLET_UTXO = LoanFixtures.adaUtxo(TX_WALLET, 0,
            LoanFixtures.botAddress(), 50_000_000L);

    /**
     * Six synthetic reference-script coordinates. FluidTokens has published none for the current
     * preview deployment, and the UTxOs built for these in {@link #universe()} carry the freshly
     * derived script hashes — so the evaluation is against the real applied code whatever the
     * coordinates say.
     */
    private static final LiquidateTransactionBuilder.ReferenceScripts REFERENCE_SCRIPTS =
            new LiquidateTransactionBuilder.ReferenceScripts(
                    new TransactionInput("a1".repeat(32), 0),
                    new TransactionInput("a2".repeat(32), 0),
                    new TransactionInput("a3".repeat(32), 0),
                    new TransactionInput("a4".repeat(32), 0),
                    new TransactionInput("a5".repeat(32), 0),
                    new TransactionInput("a6".repeat(32), 0),
                    null);

    // ======================================================================================
    // The datums, before anything is built
    // ======================================================================================

    /**
     * The three datum fields the whole slice turns on, decoded from the chain's own bytes.
     * <ul>
     *   <li>{@code repaymentReceipts == False} — so {@code equity_sent_to_borrower}'s
     *       {@code receiptCondition} short-circuits and its {@code dosProtection} wants exactly
     *       {@code 2} flattened assets (lovelace + tFLDT). The {@code True} branch would need a
     *       receipt NFT mint this repo does not model, and V6's
     *       {@code REPAYMENT_RECEIPTS_WITH_EQUITY} refuses it loudly.</li>
     *   <li>{@code equityInPrincipalCurrency == False} — so the equity is denominated in the
     *       collateral, tFLDT, and {@code lm_liquidate_action.ak:122}'s hard
     *       {@code expect equityInPrincipalCurrency == False} is satisfied.</li>
     *   <li>{@code shouldLiquidationConvertToPrincipal == False} — so the plain {@code Liquidate}
     *       action is the right one.</li>
     * </ul>
     */
    @Test
    void theRealDatumsCarryTheThreeFlagsThisPathRequires() {
        LoanDatum datum = loanDatum();
        LenderManagerDatum bond = bondDatum();

        assertEquals(BigInteger.valueOf(PRINCIPAL), datum.principalAmount());
        assertEquals(BigInteger.valueOf(459), datum.interestRate(),
                "a non-zero perpetual rate, so the debt really is time-dependent");
        assertFalse(datum.repaymentReceipts(),
                "the receipt-NFT branch of equity_sent_to_borrower is not modelled in this repo");

        LiquidationMode.Liquidation liquidation =
                (LiquidationMode.Liquidation) datum.liquidationMode();
        assertFalse(liquidation.equityInPrincipalCurrency(),
                "lm_liquidate_action.ak:122 is a hard expect on this being False");

        assertEquals(BigInteger.ZERO, bond.liquidationFeePerMille(),
                "THIS LOAN PAYS THE OPERATOR NOTHING — see the operator warning in the class javadoc");
        assertFalse(bond.shouldLiquidationConvertToPrincipal(),
                "the plain Liquidate path requires this to be False");
    }

    /**
     * The equity is positive, and it is positive for the reason the slice claims: the collateral is
     * worth more than the debt at the pinned price. Both figures are derived through
     * {@link LoanFinance} at {@link #VALID_FROM} — the same instant and the same calls the builder
     * uses — and compared against literals.
     */
    @Test
    void theCollateralIsWorthMoreThanTheDebtSoTheEquityIsPositive() {
        LiquidationAssessment assessment = fixture().assessment();

        assertEquals(DEBT_AT_VALID_FROM, assessment.remainingDebt());
        assertEquals(EQUITY, assessment.equity(),
                "a positive equity is the whole point of this fixture");
        assertEquals(LIQUIDATION_FEE, assessment.liquidationFee());
        assertTrue(assessment.buildable(),
                "the scanner must admit this loan now that the positive-equity exclusion is gone: "
                        + assessment.detail());

        // The collateral at the pinned price, in lovelace, against the debt — the inequality that
        // makes the equity positive rather than clamped.
        BigInteger collateralInLovelace = BigInteger.valueOf(COLLATERAL_AMOUNT)
                .multiply(PRICE).divide(PRICE_DENOMINATOR);
        assertEquals(BigInteger.valueOf(33_816_300L), collateralInLovelace);
        assertTrue(collateralInLovelace.compareTo(DEBT_AT_VALID_FROM) > 0,
                "ltv below 1 is what leaves the borrower a residue");
    }

    // ======================================================================================
    // THE GATE
    // ======================================================================================

    /**
     * <b>The acceptance gate.</b> The transaction the production builder produces for this real loan,
     * evaluated by the real PlutusV3 machine against the deployed validators. Eight redeemers — two
     * spends, one mint, five withdrawals (four loans-v4 plus the oracle) — and every one of them must
     * return ex-units.
     * <p>
     * This is the first evaluation in this repo in which {@code equity_sent_to_borrower} runs at all.
     * Its four conjuncts are all live here: the compensation output's datum must equal
     * {@code AssetManagerDatumWithToken { inputOutputReference: <the loan input's own ref>, action:
     * action_partial_liquidation_compensation, data: None, ownerAsset: <borrower bond>/<loanId> }}
     * byte for byte, its tFLDT quantity must be {@code >= equity}, its receipt condition is satisfied
     * by {@code repaymentReceipts == False}, and its {@code dosProtection} demands exactly two
     * flattened assets.
     * <p>
     * Everything asserted about the body is read off the <b>deserialised</b> transaction — the bytes
     * the machine sees — and the two asset-manager datums are rebuilt from ASCII action literals
     * rather than from {@link LiquidationTxEncoder}'s constants, so a mutated constant moves the
     * transaction without moving the expectation.
     */
    @Test
    void theRealPositiveEquityLiquidationEvaluatesAgainstTheDeployedValidators() {
        Fixture fixture = fixture();
        Transaction tx = build(fixture);

        List<EvaluationResult> results =
                EvalFixtures.evaluate(tx, universe(fixture), REGISTRY, List.of(oracleScript()));
        assertRedeemerCoverage(tx, results);
        assertEquals(2, count(results, RedeemerTag.Spend), "the loan and the bond");
        assertEquals(1, count(results, RedeemerTag.Mint), "one policy, one burn redeemer");
        assertEquals(5, count(results, RedeemerTag.Reward),
                "four loans-v4 withdrawals plus the oracle's");

        Transaction body = deserialise(tx);

        // ---- rule R, off the finished body -------------------------------------------------------
        List<TransactionOutput> assetManager = assetManagerOutputs(body);
        assertEquals(2, assetManager.size(),
                "a compensation output and a claimed-collateral output");

        TransactionOutput compensation = assetManager.get(0);
        assertEquals(compensationDatumHex(), compensation.getInlineDatum().serializeToHex(),
                "filtered slot 0 — the position loan_claim_action reads with the bare loan index");
        assertEquals(EQUITY, quantityOf(compensation, COLLATERAL),
                "the borrower's residue, in tFLDT");
        assertEquals(2, flattenedCount(compensation),
                "dosProtection: exactly lovelace + tFLDT, with repaymentReceipts False");
        // The min-ada rider, pinned rather than merely asserted positive. This loan's equity is
        // denominated in tFLDT, so every lovelace of it is a SUBSIDY THE BOT FUNDS out of its own
        // inputs: the builder emits the output carrying only the tFLDT, and cardano-client-lib tops
        // it to min-ada during balancing. It is bounded to one min-ada by
        // LiquidateTransactionBuilder.assertCompensationSubsidyBounded; it is NOT subtracted by
        // LiquidationExecutor's expected-profit arithmetic, which is a known gap recorded in that
        // method's javadoc. Pinned here so the figure the operator note below quotes is measured.
        assertEquals(BigInteger.valueOf(COMPENSATION_MIN_ADA), compensation.getValue().getCoin(),
                "the min-ada rider the bot funds on the borrower's compensation output");
        assertEquals(BigInteger.valueOf(TX_FEE), body.getBody().getFee(),
                "the other half of the operator's outlay — the two together are the ~2.19 ADA the "
                        + "class javadoc quotes, against a fee slice of zero");

        TransactionOutput claimedCollateral = assetManager.get(1);
        assertEquals(claimedCollateralDatumHex(), claimedCollateral.getInlineDatum().serializeToHex(),
                "filtered slot 1 — the displaced output assetOutputIndexes points at");
        assertEquals(COLLATERAL_PAYOUT, quantityOf(claimedCollateral, COLLATERAL),
                "collateral - equity - fee");
        assertEquals(2, flattenedCount(claimedCollateral));

        assertEquals(List.of(BigInteger.ONE), emittedAssetOutputIndexes(body),
                "assetOutputIndexes must yield to loan_claim_action's forced slot");

        // ---- the enterprise address ---------------------------------------------------------------
        // The validator does not check this: equity_sent_to_borrower's and{} block opens with
        // "//No staking check here". The builder does, and so does this.
        Address compensationAddress = new Address(compensation.getAddress());
        Address collateralAddress = new Address(claimedCollateral.getAddress());
        assertEquals(REGISTRY.getAssetManagerSpendScriptHash(),
                HexUtil.encodeHexString(compensationAddress.getPaymentCredentialHash().orElseThrow()));
        assertTrue(compensationAddress.getDelegationCredential().isEmpty(),
                "the borrower's compensation goes to an enterprise address — no third party earns "
                        + "staking rewards on someone else's refund");
        assertEquals(HexUtil.encodeHexString(
                        new Address(BOND_ADDRESS).getDelegationCredentialHash().orElseThrow()),
                HexUtil.encodeHexString(collateralAddress.getDelegationCredentialHash().orElseThrow()),
                "the lender's claimed collateral keeps the bond's stake credential, unchanged");
        assertNotEquals(collateralAddress.getAddress(), compensationAddress.getAddress(),
                "this bond really does carry a stake credential, so the enterprise choice is a "
                        + "choice — the old builder sent both outputs to the lender's address");

        // ---- the redeemer's own numbers ------------------------------------------------------------
        List<PlutusData> claim = claimFields(body);
        assertEquals(DEBT_AT_VALID_FROM, ((BigIntPlutusData) claim.get(7)).getValue(),
                "remainingDebt in the redeemer is the validFrom figure");
        assertEquals(EQUITY, ((BigIntPlutusData) claim.get(5)).getValue(),
                "equity in the redeemer, which equity_sent_to_borrower compares the output against");

        // ---- the rest of the shape ------------------------------------------------------------------
        List<TransactionInput> refInputs = sortedRefInputs(body);
        assertTrue(refInputs.contains(ORACLE_REF_INPUT), "the fluid oracle utxo, holding the datum's NFT");
        assertTrue(refInputs.contains(ORACLE_REF_SCRIPT), "the oracle's own reference script");
        assertTrue(refInputs.contains(C3_PROVIDER), "the Charli3 provider utxo");
        assertTrue(body.getBody().getWithdrawals().stream()
                        .anyMatch(w -> ORACLE_REWARD_ADDRESS.equals(w.getRewardAddress())),
                "the oracle must be invoked by withdrawal, or its validator never ran");

        // The bond echo, byte-identical to its input.
        TransactionOutput echo = onlyBondOutput(body);
        assertEquals(BOND_ADDRESS, echo.getAddress());
        assertEquals(BOND_DATUM_HEX, echo.getInlineDatum().serializeToHex());
        assertEquals(BigInteger.valueOf(BOND_LOVELACE), echo.getValue().getCoin());

        // The loan NFT is burned, exactly once.
        assertEquals(BigInteger.ONE.negate(),
                mintedQuantity(body, new AssetType(REGISTRY.getLoanPolicyId(), LOAN_ID)));

        // The operator's whole outlay on this loan, in one line: the transaction fee plus the min-ada
        // it funds on the borrower's compensation output, against a liquidation fee slice of zero.
        log.info("REAL positive-equity liquidation: equity {} tFLDT, payout {} tFLDT, fee slice {}, "
                        + "tx fee {} lovelace + compensation min-ada {} lovelace out of pocket, {} bytes",
                EQUITY, COLLATERAL_PAYOUT, LIQUIDATION_FEE, body.getBody().getFee(),
                compensation.getValue().getCoin(), serializedSize(tx));
    }

    /**
     * <b>The layout this builder emitted before rule R is refused on chain, on this same real loan.</b>
     * <p>
     * The transaction from the gate above is mutated back into the pre-rule-R shape — claimed
     * collateral at filtered slot 0, compensation at slot 1 — with {@code assetOutputIndexes} moved to
     * {@code [0]} so that {@code lm_liquidate_action} still finds its own output and is not the thing
     * that breaks. Only {@code loan_claim_action}'s forced slot is now wrong, and it is
     * {@code loan_claim_action} that refuses.
     * <p>
     * This is what makes rule R falsifiable rather than a preference. Without it, an edit reverting
     * the emission order would show up only as a lost fee on preview.
     */
    @Test
    void thePreRuleRLayoutIsRejectedByLoanClaimActionOnThisLoanToo() {
        Fixture fixture = fixture();
        Transaction tx = build(fixture);

        List<Integer> positions = assetManagerOutputPositions(tx);
        assertEquals(2, positions.size());
        swapOutputs(tx, positions.get(0), positions.get(1));
        replaceAssetOutputIndexes(tx, List.of(0L));

        EvalFixtures.Outcome outcome =
                EvalFixtures.evaluateRaw(tx, universe(fixture), REGISTRY, List.of(oracleScript()));
        assertFalse(outcome.successful(),
                "loan_claim_action reads filtered slot 0 with the bare loan index; the "
                        + "claimed-collateral datum sitting there cannot satisfy equity_sent_to_borrower");
        assertTrue(outcome.detail().contains(redeemerError(tx,
                        LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()))),
                "expected loan_claim_action to be the rejecting script, got: " + outcome.detail());
    }

    /**
     * <b>Corrupting the compensation datum is refused on chain.</b> The output stays in the right
     * slot, keeps the right amount and the right flatten count; only its datum's {@code ownerAsset} is
     * moved from the borrower bond to the lender bond — the single axis, together with the action
     * constant, on which the compensation datum differs from the collateral one.
     * <p>
     * This is what distinguishes "our output is in the right place" from "our output is correct".
     * {@code equity_sent_to_borrower}'s {@code isDatumCorrect} is a {@code builtin.equals_data} over
     * the whole datum, so a wrong owner is as fatal as a wrong action — and a builder that emitted the
     * lender's bond here would produce a transaction whose shape every structural check accepts.
     */
    @Test
    void aCompensationDatumOwnedByTheWrongBondIsRejectedOnChain() {
        Fixture fixture = fixture();
        Transaction tx = build(fixture);

        List<Integer> positions = assetManagerOutputPositions(tx);
        TransactionOutput compensation = tx.getBody().getOutputs().get(positions.get(0));
        assertEquals(compensationDatumHex(), compensation.getInlineDatum().serializeToHex(),
                "the mutation below only means anything if this is the compensation output");

        compensation.setInlineDatum(LiquidationTxEncoder.assetManagerDatumWithToken(
                new AssetManagerDatumWithToken(LOAN_TX, LOAN_OUTPUT_INDEX,
                        HexUtil.encodeHexString("partial_liquidation"
                                .getBytes(StandardCharsets.US_ASCII)),
                        new AssetType(REGISTRY.getLenderBondPolicyId(), LOAN_ID))));

        EvalFixtures.Outcome outcome =
                EvalFixtures.evaluateRaw(tx, universe(fixture), REGISTRY, List.of(oracleScript()));
        assertFalse(outcome.successful(),
                "equity_sent_to_borrower's isDatumCorrect is equals_data over the whole datum");
        assertTrue(outcome.detail().contains(redeemerError(tx,
                        LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()))),
                "expected loan_claim_action to be the rejecting script, got: " + outcome.detail());
    }

    /**
     * <b>Omitting the compensation output entirely is refused before anything is built.</b>
     * <p>
     * There is no seam that makes the builder skip it, so the omission is produced the only honest way
     * available: the finished body is stripped of the output and V5's positional guard is run over the
     * resulting filtered list directly. What is left at slot 0 is the claimed-collateral output, which
     * is exactly what {@code loan_claim_action} would read and reject on chain — so the refusal here
     * anticipates a phase-2 failure rather than inventing one.
     */
    @Test
    void omittingTheCompensationOutputIsCaughtByThePositionalGuard() {
        Fixture fixture = fixture();
        Transaction tx = build(fixture);

        List<TransactionOutput> assetManager = new ArrayList<>(assetManagerOutputs(deserialise(tx)));
        assertEquals(2, assetManager.size());
        assetManager.removeFirst();

        LiquidateTransactionBuilder.RefusedException refused = org.junit.jupiter.api.Assertions
                .assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                        () -> LiquidateTransactionBuilder.compensationOutputsAtTheirLoanIndex(
                                assetManager, List.of(compensationDatumHex()), List.of(LOAN_ID)));
        assertEquals(LiquidateTransactionBuilder.Refusal.STRUCTURAL_ASSERTION_FAILED,
                refused.getReason());
    }

    // ======================================================================================
    // The fixture
    // ======================================================================================

    private record Fixture(Loan loan, Utxo loanUtxo, LenderBond bond, Utxo bondUtxo,
                           OracleEntry oracle, LiquidationAssessment assessment) {

        LiquidateTransactionBuilder.LoanLiquidation toLiquidation() {
            return new LiquidateTransactionBuilder.LoanLiquidation(assessment, loanUtxo, bondUtxo);
        }
    }

    private static LoanDatum loanDatum() {
        return new LoanDatumConverter().deserialize(LOAN_DATUM_HEX);
    }

    private static LenderManagerDatum bondDatum() {
        return new LenderManagerDatumConverter().deserialize(BOND_DATUM_HEX);
    }

    private static Fixture fixture() {
        LoanDatum datum = loanDatum();

        Utxo loanUtxo = LoanFixtures.utxo(LOAN_TX, LOAN_OUTPUT_INDEX, LOAN_ADDRESS, List.of(
                Amount.lovelace(BigInteger.valueOf(LOAN_LOVELACE)),
                Amount.asset(LoanFixtures.unit(COLLATERAL), BigInteger.valueOf(COLLATERAL_AMOUNT)),
                Amount.asset(REGISTRY.getLoanPolicyId() + LOAN_ID, BigInteger.ONE)), LOAN_DATUM_HEX);
        Loan loan = new Loan(LOAN_TX, LOAN_OUTPUT_INDEX, LOAN_ADDRESS, LOAN_ID,
                BigInteger.valueOf(COLLATERAL_AMOUNT), BigInteger.valueOf(LOAN_LOVELACE), datum);

        Utxo bondUtxo = LoanFixtures.utxo(LOAN_TX, BOND_OUTPUT_INDEX, BOND_ADDRESS, List.of(
                        Amount.lovelace(BigInteger.valueOf(BOND_LOVELACE)),
                        Amount.asset(REGISTRY.getLenderBondPolicyId() + LOAN_ID, BigInteger.ONE)),
                BOND_DATUM_HEX);
        LenderBond bond = new LenderBond(LOAN_TX, BOND_OUTPUT_INDEX, BOND_ADDRESS, LOAN_ID,
                BOND_DATUM_HEX, bondDatum());

        OracleEntry oracle = LoanFixtures.charli3(COLLATERAL, ORACLE_NFT, ORACLE_SCRIPT_HASH,
                OraclePriceFeed.priceDataCharlie(COLLATERAL, PRICE, PRICE_DENOMINATOR,
                        FEED_VALID_FROM, FEED_VALID_TO),
                ORACLE_REF_INPUT, ORACLE_REF_SCRIPT, C3_PROVIDER);

        LiquidationAssessment assessment = LoanFixtures.assess(bond, loan, OraclePriceFeed.unit(),
                oracle.feed(), VALID_FROM);
        return new Fixture(loan, loanUtxo, bond, bondUtxo, oracle, assessment);
    }

    /**
     * Everything the evaluator may resolve an input or reference input from: the loan, the bond, the
     * two configs, the bot's wallet, the three oracle UTxOs exactly as preview holds them, and the six
     * reference-script UTxOs carrying the freshly derived loans-v4 script hashes.
     */
    private static List<Utxo> universe(Fixture fixture) {
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO, WALLET_UTXO,
                fixture.loanUtxo(), fixture.bondUtxo()));

        universe.add(LoanFixtures.utxo(ORACLE_REF_INPUT.getTransactionId(), ORACLE_REF_INPUT.getIndex(),
                ORACLE_ADDRESS, List.of(Amount.lovelace(BigInteger.valueOf(1_038_710L)),
                        Amount.asset(LoanFixtures.unit(ORACLE_NFT), BigInteger.ONE)), null));
        universe.add(Utxo.builder().txHash(ORACLE_REF_SCRIPT.getTransactionId())
                .outputIndex(ORACLE_REF_SCRIPT.getIndex()).address(ORACLE_ADDRESS)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(40_000_000L))))
                .referenceScriptHash(ORACLE_SCRIPT_HASH).build());
        universe.add(LoanFixtures.utxo(C3_PROVIDER.getTransactionId(), C3_PROVIDER.getIndex(),
                C3_PROVIDER_ADDRESS, List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)),
                        Amount.asset(LoanFixtures.unit(C3_FEED_NFT), BigInteger.ONE)),
                C3_PROVIDER_DATUM_HEX));

        List<PlutusScript> scripts = List.of(REGISTRY.getLoanScript(), REGISTRY.getLoanSpendScript(),
                REGISTRY.getLenderManagerScript(), REGISTRY.getLenderManagerSpendScript(),
                REGISTRY.getLoanClaimActionScript(), REGISTRY.getLmLiquidateActionScript());
        List<TransactionInput> coordinates = List.of(REFERENCE_SCRIPTS.loan(),
                REFERENCE_SCRIPTS.loanSpend(), REFERENCE_SCRIPTS.lenderManager(),
                REFERENCE_SCRIPTS.lenderManagerSpend(), REFERENCE_SCRIPTS.loanClaimAction(),
                REFERENCE_SCRIPTS.lmLiquidateAction());
        for (int i = 0; i < coordinates.size(); i++) {
            String hash = scriptHash(scripts.get(i));
            universe.add(Utxo.builder().txHash(coordinates.get(i).getTransactionId())
                    .outputIndex(coordinates.get(i).getIndex())
                    .address(LoanFixtures.entAddress(hash))
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                    .referenceScriptHash(hash).build());
        }
        return universe;
    }

    /** The production builder, with no evaluator: nothing built here may be — or is — submitted. */
    private static Transaction build(Fixture fixture) {
        Transaction tx = new LiquidateTransactionBuilder(REGISTRY, LoanFixtures.NETWORK,
                LoanFixtures.converters(), LoanFixtures.utxoSupplier(universe(fixture)),
                EvalFixtures.protocolParams())
                .build(new LiquidateTransactionBuilder.Request(
                        List.of(fixture.toLiquidation()), CONFIG_UTXO, LM_CONFIG_UTXO,
                        Map.of(ORACLE_NFT.toUnit(), fixture.oracle()), WALLET_UTXO,
                        LoanFixtures.botAddress(), VALID_FROM, VALID_TO, MARGIN, REFERENCE_SCRIPTS));
        assertNotNull(tx);
        return tx;
    }

    // ======================================================================================
    // Expectations, built from ASCII literals rather than from the encoder's constants
    // ======================================================================================

    private static String compensationDatumHex() {
        return assetManagerDatum("partial_liquidation", REGISTRY.getBorrowerBondPolicyId());
    }

    private static String claimedCollateralDatumHex() {
        return assetManagerDatum("claimed_collateral", REGISTRY.getLenderBondPolicyId());
    }

    private static String assetManagerDatum(String action, String ownerPolicyId) {
        return LiquidationTxEncoder.assetManagerDatumWithToken(new AssetManagerDatumWithToken(
                        LOAN_TX, LOAN_OUTPUT_INDEX,
                        HexUtil.encodeHexString(action.getBytes(StandardCharsets.US_ASCII)),
                        new AssetType(ownerPolicyId, LOAN_ID)))
                .serializeToHex();
    }

    // ======================================================================================
    // Reading the body
    // ======================================================================================

    private static Transaction deserialise(Transaction tx) {
        try {
            return Transaction.deserialize(tx.serialize());
        } catch (Exception e) {
            throw new AssertionError("the built transaction does not round-trip through CBOR", e);
        }
    }

    private static int serializedSize(Transaction tx) {
        try {
            return tx.serialize().length;
        } catch (Exception e) {
            throw new AssertionError("cannot serialize the built transaction", e);
        }
    }

    private static String scriptHash(PlutusScript script) {
        try {
            return HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new AssertionError("cannot hash an applied loans-v4 script", e);
        }
    }

    private static PlutusScript oracleScript() {
        try {
            return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                    LoanFixtures.fixture("preview-oracle-script.hex"), PlutusVersion.v3);
        } catch (Exception e) {
            throw new AssertionError("cannot load the deployed oracle script", e);
        }
    }

    /** The list {@code get_outputs_to_smart_credential} builds, in body order. */
    private static List<TransactionOutput> assetManagerOutputs(Transaction tx) {
        return tx.getBody().getOutputs().stream()
                .filter(output -> REGISTRY.getAssetManagerSpendScriptHash().equals(
                        new Address(output.getAddress()).getPaymentCredentialHash()
                                .map(HexUtil::encodeHexString).orElse(null)))
                .toList();
    }

    /** The same outputs' positions in the body, for mutations that have to address them there. */
    private static List<Integer> assetManagerOutputPositions(Transaction tx) {
        List<Integer> positions = new ArrayList<>();
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            if (REGISTRY.getAssetManagerSpendScriptHash().equals(
                    new Address(outputs.get(i).getAddress()).getPaymentCredentialHash()
                            .map(HexUtil::encodeHexString).orElse(null))) {
                positions.add(i);
            }
        }
        return positions;
    }

    private static void swapOutputs(Transaction tx, int first, int second) {
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        TransactionOutput held = outputs.get(first);
        outputs.set(first, outputs.get(second));
        outputs.set(second, held);
    }

    private static TransactionOutput onlyBondOutput(Transaction tx) {
        List<TransactionOutput> matches = tx.getBody().getOutputs().stream()
                .filter(output -> REGISTRY.getLenderManagerSpendScriptHash().equals(
                        new Address(output.getAddress()).getPaymentCredentialHash()
                                .map(HexUtil::encodeHexString).orElse(null)))
                .toList();
        assertEquals(1, matches.size(), "exactly one bond echo");
        return matches.getFirst();
    }

    private static BigInteger quantityOf(TransactionOutput output, AssetType asset) {
        return com.bloxbean.cardano.client.api.util.ValueUtil.toAmountList(output.getValue()).stream()
                .filter(amount -> LoanFixtures.unit(asset).equals(amount.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static int flattenedCount(TransactionOutput output) {
        return com.bloxbean.cardano.client.api.util.ValueUtil.toAmountList(output.getValue()).size();
    }

    private static BigInteger mintedQuantity(Transaction tx, AssetType asset) {
        return tx.getBody().getMint().stream()
                .filter(multiAsset -> multiAsset.getPolicyId().equals(asset.policyId()))
                .flatMap(multiAsset -> multiAsset.getAssets().stream())
                .filter(a -> a.getNameAsHex().replaceFirst("^0x", "")
                        .equalsIgnoreCase(asset.assetName()))
                .map(com.bloxbean.cardano.client.transaction.spec.Asset::getValue)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static List<TransactionInput> sortedRefInputs(Transaction tx) {
        return tx.getBody().getReferenceInputs().stream()
                .sorted(new TransactionInputComparator())
                .toList();
    }

    private static void assertRedeemerCoverage(Transaction tx, List<EvaluationResult> results) {
        List<String> expected = tx.getWitnessSet().getRedeemers().stream()
                .map(redeemer -> redeemer.getTag() + "#" + redeemer.getIndex())
                .sorted()
                .toList();
        List<String> actual = results.stream()
                .map(result -> result.getRedeemerTag() + "#" + result.getIndex())
                .sorted()
                .toList();
        assertEquals(expected, actual, "every redeemer must have been evaluated, and nothing else");
    }

    private static long count(List<EvaluationResult> results, RedeemerTag tag) {
        return results.stream().filter(result -> result.getRedeemerTag() == tag).count();
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

    private static String redeemerError(Transaction tx, String rewardAddress) {
        return "RedeemerError { tag: \"Withdraw\", index: " + withdrawalIndexOf(tx, rewardAddress);
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

    /** The eight fields of the single {@code ClaimData} in the loan-claim withdrawal's redeemer. */
    private static List<PlutusData> claimFields(Transaction tx) {
        ConstrPlutusData withdrawRedeemer = (ConstrPlutusData) rewardRedeemer(tx,
                LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash())).getData();
        ListPlutusData actions =
                (ListPlutusData) withdrawRedeemer.getData().getPlutusDataList().get(1);
        assertEquals(1, actions.getPlutusDataList().size(), "one loan, one action");
        List<PlutusData> fields =
                ((ConstrPlutusData) actions.getPlutusDataList().getFirst()).getData().getPlutusDataList();
        assertEquals(8, fields.size(), "ClaimData has eight fields");
        return fields;
    }

    private static List<BigInteger> emittedAssetOutputIndexes(Transaction tx) {
        ConstrPlutusData redeemer = (ConstrPlutusData) rewardRedeemer(tx,
                LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateActionScriptHash())).getData();
        ListPlutusData indexes = (ListPlutusData) redeemer.getData().getPlutusDataList().get(3);
        return indexes.getPlutusDataList().stream()
                .map(data -> ((BigIntPlutusData) data).getValue())
                .toList();
    }

    /**
     * Replaces <b>only</b> {@code assetOutputIndexes} — field 3 of the {@code lm_liquidate_action}
     * withdrawal's redeemer. Fields 0..2 are lifted verbatim off the existing redeemer rather than
     * rebuilt, so the mutation really is one field.
     */
    private static void replaceAssetOutputIndexes(Transaction tx, List<Long> assetOutputIndexes) {
        Redeemer redeemer = rewardRedeemer(tx,
                LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateActionScriptHash()));
        List<PlutusData> fields = ((ConstrPlutusData) redeemer.getData()).getData().getPlutusDataList();
        List<PlutusData> mutated = new ArrayList<>(fields.subList(0, 3));
        mutated.add(ListPlutusData.of(assetOutputIndexes.stream()
                .map(BigIntPlutusData::of).toArray(PlutusData[]::new)));
        redeemer.setData(ConstrPlutusData.builder().alternative(0)
                .data(ListPlutusData.of(mutated.toArray(PlutusData[]::new))).build());
    }
}
