package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Utxo;
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
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The structural answer to "a green suite proves nothing": the {@code Liquidate} transaction
 * {@link LiquidateTransactionBuilder} assembles is handed to the real PlutusV3 machine and run
 * against the <em>real deployed validators</em>, not against this repo's model of them.
 *
 * <h2>What is actually being run</h2>
 * Every script is the applied compiled code {@link LoansContractRegistry} derives from the
 * committed {@code loans-v4.plutus.json} (byte-identical to the upstream deployed commit), and the
 * two config reference inputs carry the datums recorded off preview
 * ({@code src/test/resources/loans-v4/preview-*-config-datum.hex}). So the config field indices the
 * validators read, the hashes they compare against, and the arithmetic they redo are all the
 * deployed ones. Everything else — loan, bond, wallet UTxOs — is synthetic, so the test runs cold:
 * no network, no key, no wallet, deterministic. See {@link EvalFixtures} for the rig, including why
 * its {@code maxTxSize} is deliberately unreal.
 *
 * <h2>Why there is no oracle leg here</h2>
 * Both loans below are ada-principal / ada-collateral. {@code retrieve_oracle_data}
 * ({@code lib/fluidtokens/oracle.ak}) returns the synthesised 1:1 feed from its
 * {@code expectedTokenPolicyId == ""} branch <em>before</em> it looks at any reference input or
 * redeemer, so an ada leg consults no oracle at all and the transaction carries no oracle
 * withdrawal. That is the only leg shape this rig can evaluate: the deployed oracle script is
 * applied to eight parameter values FluidTokens does not publish, so it cannot be reconstructed
 * here, and synthesising a fake one would prove something about the fake rather than about the
 * chain. The oracle/c3 leg is therefore explicitly out of this slice's scope (carried to T-010).
 *
 * <h2>The rig can fail</h2>
 * {@link #aMutatedRemainingDebtIsRejectedByLoanClaimAction()} takes a transaction that evaluates
 * clean, moves {@code remainingDebt} in the claim redeemer by one, and requires the evaluation to
 * fail. Without it a passing suite here would be indistinguishable from a rig that never reaches
 * the validators' arithmetic at all.
 *
 * <h2>Reading a failure is not the mirror image of reading a success</h2>
 * The evaluator reports only the <b>first</b> failing redeemer (see {@link EvalFixtures} —
 * "Harness limitations"). A clean run therefore says every redeemer passed; a failed run says only
 * that the named one refused, and says <em>nothing</em> about any redeemer after it. Every
 * assertion below that reasons from a failure states which redeemer index it depends on and why
 * the inference is sound.
 */
@Slf4j
class LiquidateDryEvalTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    /** The same fixed instant {@link LiquidateTransactionBuilderTest} uses; well inside preview. */
    private static final long NOW = 1_700_000_000_000L;
    private static final long VALID_FROM = NOW;
    private static final long VALID_TO = NOW + 120_000L;
    private static final long MARGIN = 300_000L;
    /** 30 days before {@link #NOW}: past every deadline the fixture datums set. */
    private static final long LATE_LEND_DATE = NOW - 30L * 24 * 3_600_000L;

    private static final String LOAN_ID_A = "a1b2c3d4e5f6a1b2";
    private static final String LOAN_ID_B = "b2c3d4e5f6a1b2c3";
    private static final String STAKE_KEY = "33333333333333333333333333333333333333333333333333333333";

    private static final String TX_LOAN_A = repeat("aa");
    private static final String TX_BOND_B = repeat("bb");
    private static final String TX_LOAN_B = repeat("cc");
    private static final String TX_BOND_A = repeat("dd");
    private static final String TX_WALLET = repeat("e0");
    private static final String TX_BOT_SPARE = repeat("e1");
    private static final String TX_CONFIG = repeat("f1");
    private static final String TX_LM_CONFIG = repeat("f2");

    private static final Utxo CONFIG_UTXO = LoanFixtures.configUtxo(TX_CONFIG, 0);
    private static final Utxo LM_CONFIG_UTXO = LoanFixtures.lmConfigUtxo(TX_LM_CONFIG, 0);
    private static final Utxo WALLET_UTXO = LoanFixtures.adaUtxo(TX_WALLET, 0,
            LoanFixtures.botAddress(), 50_000_000L);
    private static final Utxo BOT_SPARE_UTXO = LoanFixtures.adaUtxo(TX_BOT_SPARE, 0,
            LoanFixtures.botAddress(), 10_000_000L);

    /** The real ledger limit. Not the rig's {@link EvalFixtures#protocolParams()} ceiling. */
    private static final int MAX_TX_SIZE = 16_384;

    /**
     * The six reference-script UTxOs FluidTokens published on preview, copied verbatim from the
     * {@code preview} profile of {@code src/main/resources/application.yaml}
     * ({@code loans.liquidation.reference-scripts.*}) — pinned here the way
     * {@code LoansContractDerivationTest} pins the derived hashes, so a redeploy that moves them
     * shows up as a diff between this file and that yaml rather than as a silent re-measurement.
     * <p>
     * {@code assetManager} stays null because there is nothing to publish for: a plain
     * {@code Liquidate} only <em>creates</em> asset-manager outputs and never spends one, so that
     * validator is never invoked and the preview profile carries no coordinate for it either.
     */
    private static final LiquidateTransactionBuilder.ReferenceScripts PUBLISHED =
            new LiquidateTransactionBuilder.ReferenceScripts(
                    new TransactionInput(
                            "00a4e9f69c6ce80b8cb4fe7008a40a2f007aa53b25ec52ae30f11e701f7aa693", 0),
                    new TransactionInput(
                            "5c10900c23d16538bc518fa982f0d59a15908f0bb821860ddbef086346b669da", 0),
                    new TransactionInput(
                            "fe791b232b8ffcd31c72001a0a6345bc36101eac4d87133b0cf1a101024ffc07", 0),
                    new TransactionInput(
                            "13dd33290f62fe42dbbe7afc1d28505c025955bc55bd9b0a0ddff438663c2571", 0),
                    new TransactionInput(
                            "b09e23dc5639642a4cbf112d39753c96ed0528115a8468b688b0e8cb19f243fe", 0),
                    new TransactionInput(
                            "549b438c3a579a31cc4b7595f43c3af75bd02b237026583b834fc64349a47fe0", 0),
                    null);

    private static String repeat(String pair) {
        return pair.repeat(32);
    }

    // ======================================================================================
    // N = 1
    // ======================================================================================

    /**
     * The plain case: 100 ADA of principal at 10% against 100 ADA of collateral, 30 days late.
     * <pre>
     *   remainingDebt                     110_000_000
     *   collateral - debt - 5% penalty     -15_500_000  -> equity clamps to 0
     *   liquidationFeePerMille 10          1_000_000
     *   collateral - equity - fee         99_000_000
     * </pre>
     * Seven redeemers have to return ex-units: two spends (loan, bond), one mint (the loan NFT
     * burn) and four withdrawals ({@code loan}, {@code loan_claim_action}, {@code lenderManager},
     * {@code lm_liquidate_action}).
     */
    @Test
    void oneAdaLoanWithZeroEquityEvaluatesAgainstTheDeployedValidators() {
        Scenario scenario = scenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 100_000_000L);

        assertEquals(BigInteger.valueOf(110_000_000), scenario.assessment().remainingDebt());
        assertEquals(BigInteger.ZERO, scenario.assessment().equity(),
                "the fixture must really have no borrower compensation");
        assertEquals(BigInteger.valueOf(1_000_000), scenario.assessment().liquidationFee());

        Transaction tx = build(List.of(scenario));
        List<EvaluationResult> results = EvalFixtures.evaluate(tx, universe(List.of(scenario)), REGISTRY);

        assertRedeemerCoverage(tx, results);
        assertEquals(2, count(results, RedeemerTag.Spend));
        assertEquals(1, count(results, RedeemerTag.Mint));
        assertEquals(4, count(results, RedeemerTag.Reward));
        assertWithinBudget(results);
    }

    /**
     * Positive equity is refused in <b>both output layouts this builder can emit</b>. Exactly three
     * things are claimed here, and nothing beyond them.
     * <p>
     * <b>(a) The two validators no longer structurally collide.</b> At the deployed commit
     * {@code ff005fb}, {@code lm_liquidate_action} reads its asset output as
     * {@code safe_list_at(assetOutputs, safe_list_at(redeemer.assetOutputIndexes, index))} — through
     * the redeemer — while {@code loan_claim_action} <em>still</em> reads
     * {@code safe_list_at(get_outputs_to_smart_credential(..), index)}, the bare loan index,
     * unchanged at {@code ff005fb}. Both filter the same list (the outputs whose payment credential
     * is the asset-manager spend script) and want mutually exclusive datums
     * ({@code action_claimed_collateral} against
     * {@code action_partial_liquidation_compensation}), but the position each names is no longer
     * forced to be the same one.
     * <p>
     * <b>(b) This builder emits identity {@code assetOutputIndexes}, so both layouts it can produce
     * still fail.</b> At the identity the redeemer names slot {@code index}, which is where
     * {@code loan_claim_action} looks too — so the collision is back, and the two evaluation runs
     * below show it: as built, the claimed collateral is in the slot and {@code loan_claim_action}
     * refuses; mirrored, the compensation output is in the slot, {@code loan_claim_action}
     * <em>accepts</em> our compensation output and {@code lm_liquidate_action} refuses.
     * <p>
     * <b>(c) Whether some other layout satisfies both is an open question, deliberately not answered
     * here.</b> A non-identity {@code assetOutputIndexes} over a reordered output list is not tested
     * by this file and not claimed either way. What is enforced is only (b):
     * {@link LiquidateTransactionBuilder}'s V8 refuses a positive-equity batch with
     * {@code POSITIVE_EQUITY_UNSUPPORTED}, and {@code LiquidationCandidateScanner} excludes one before
     * it can reach the builder.
     *
     * <h3>Exactly what the two evaluation runs below do and do not prove</h3>
     * The evaluator stops at the first failing redeemer ({@link EvalFixtures} — "Harness
     * limitations"), and this transaction's {@code loan_claim_action} withdrawal sorts <em>before</em>
     * its {@code lm_liquidate_action} one in the body (indexes 0 and 2; the test asserts that
     * ordering rather than assuming it, because both inferences below depend on it).
     * <ul>
     *   <li><b>Mirrored run — the load-bearing one.</b> With the compensation output moved into the
     *       slot, the failure moves to {@code lm_liquidate_action} at the <em>higher</em> index. The
     *       evaluator would have reported the lower index had it also failed, so this proves
     *       {@code loan_claim_action} <b>accepted</b> the compensation output — its datum, its
     *       amount, its {@code dosProtection} flatten count, all of it — while
     *       {@code lm_liquidate_action} refused the same layout.</li>
     *   <li><b>As-built run — weak on its own.</b> A failure at the <em>lower</em> index is equally
     *       consistent with both validators refusing, so it proves only that
     *       {@code loan_claim_action} cannot accept the as-built layout. It is deliberately
     *       <em>not</em> read as evidence that {@code lm_liquidate_action} accepted anything.</li>
     * </ul>
     * What rules out "our claimed-collateral output is simply malformed" is not either failure but
     * the datum assertions: both asset-manager output datums are pinned by their serialized bytes,
     * with the two action strings spelled out as ASCII literals here rather than taken from
     * {@link LiquidationTxEncoder}'s constants — so a mutated constant moves the transaction without
     * moving the expectation, and this test fails.
     * <p>
     * <b>Which is why this test builds through the package-private
     * {@link LiquidateTransactionBuilder#buildIgnoringPositiveEquityVeto} seam.</b> V8 exists
     * <em>because</em> of what is proven below; running the evidence through the veto it justifies
     * would leave the veto resting on nothing. The seam disables V8 and only V8 — every other guard,
     * including the two permanent V7 {@code expect}s, still applies to the transaction evaluated here.
     */
    @Test
    void positiveEquityIsRefusedInBothLayoutsThisBuilderCanEmit() {
        Scenario scenario = scenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 200_000_000L);
        List<Utxo> universe = universe(List.of(scenario));

        assertEquals(BigInteger.valueOf(84_500_000), scenario.assessment().equity(),
                "the fixture must really produce a compensation output");

        // Our side first: the builder emits both asset-manager outputs, and each one is pinned by
        // its serialized datum bytes — not just by its lovelace — so "the collision is upstream"
        // cannot be confused with "our output is malformed". The two action strings are spelled as
        // ASCII literals here: deriving them from LiquidationTxEncoder's constants would move the
        // expectation in lockstep with any mutation of them and pin nothing.
        Transaction tx = buildIgnoringPositiveEquityVeto(List.of(scenario));
        List<Integer> assetManagerOutputs = assetManagerOutputIndexes(tx);
        assertEquals(2, assetManagerOutputs.size(), "a claimed-collateral and a compensation output");

        TransactionOutput claimedCollateral =
                tx.getBody().getOutputs().get(assetManagerOutputs.get(0));
        assertEquals(BigInteger.valueOf(113_500_000), claimedCollateral.getValue().getCoin(),
                "collateral - equity - fee");
        assertEquals(assetManagerDatum(scenario, "claimed_collateral", REGISTRY.getLenderBondPolicyId()),
                claimedCollateral.getInlineDatum().serializeToHex(),
                "the claimed-collateral output must carry constants.action_claimed_collateral and be "
                        + "owned by the lender bond");

        TransactionOutput compensation = tx.getBody().getOutputs().get(assetManagerOutputs.get(1));
        assertEquals(BigInteger.valueOf(84_500_000), compensation.getValue().getCoin(),
                "the borrower's equity");
        assertEquals(assetManagerDatum(scenario, "partial_liquidation", REGISTRY.getBorrowerBondPolicyId()),
                compensation.getInlineDatum().serializeToHex(),
                "the compensation output must carry "
                        + "constants.action_partial_liquidation_compensation and be owned by the "
                        + "borrower bond");

        // Claim (b): the layouts below are the ones the *identity* redeemer produces. Read off the
        // deserialised lm redeemer rather than assumed, because the whole inference rests on
        // lm_liquidate_action being pointed at slot 0 — the same slot loan_claim_action reads.
        assertEquals(List.of(BigInteger.ZERO), emittedAssetOutputIndexes(tx),
                "this builder emits identity assetOutputIndexes; that is what re-creates the collision");

        // Both inferences below rest on loan_claim_action being evaluated before lm_liquidate_action,
        // because the evaluator reports only the first failing redeemer. Asserted, not assumed.
        int claimWithdrawal = withdrawalIndexOf(tx,
                LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()));
        int liquidateWithdrawal = withdrawalIndexOf(tx,
                LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateActionScriptHash()));
        assertTrue(claimWithdrawal < liquidateWithdrawal,
                "the mirrored-run inference needs loan_claim_action at the lower redeemer index, got "
                        + claimWithdrawal + " and " + liquidateWithdrawal);

        // As built — claimed collateral in the slot. loan_claim_action refuses. This alone does not
        // say what lm_liquidate_action would have done: it is at the higher index, and the evaluator
        // never got there.
        EvalFixtures.Outcome asBuilt = EvalFixtures.evaluateRaw(tx, universe, REGISTRY);
        assertFalse(asBuilt.successful(), "loan_claim_action cannot accept this layout");
        assertTrue(asBuilt.detail().contains(redeemerError(tx,
                        LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()))),
                "expected loan_claim_action to be the rejecting script, got: " + asBuilt.detail());

        // Mirrored — compensation in the slot. The refusal moves to the *higher* index, so the
        // lower one passed: loan_claim_action accepted the compensation output, and
        // lm_liquidate_action refused the very same layout.
        swapOutputs(tx, assetManagerOutputs.get(0), assetManagerOutputs.get(1));
        EvalFixtures.Outcome mirrored = EvalFixtures.evaluateRaw(tx, universe, REGISTRY);
        assertFalse(mirrored.successful(), "lm_liquidate_action cannot accept the mirrored layout");
        assertTrue(mirrored.detail().contains(redeemerError(tx,
                        LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateActionScriptHash()))),
                "expected lm_liquidate_action to be the rejecting script, got: " + mirrored.detail());
    }

    // ======================================================================================
    // Size, with the published reference scripts resolved
    // ======================================================================================

    /**
     * <b>The gate: does a {@code Liquidate} fit in a block once the six validators travel by
     * reference instead of in the witness set?</b>
     * <p>
     * Every other test in this file builds with {@code ReferenceScripts.none()}, which puts all six
     * applied scripts — 18,584 bytes of them — in the witness set and lands at 19,862 bytes against
     * a real {@code maxTxSize} of {@value #MAX_TX_SIZE}. That number says nothing about
     * submittability, because on chain the scripts would be read from the UTxOs FluidTokens
     * published on preview. This test builds the very same N=1 ada/ada scenario with those
     * coordinates and measures the result.
     * <p>
     * The size is taken from {@link Transaction#serialize()} on the built transaction — the actual
     * CBOR the ledger would count — never estimated and never compared against a figure copied from
     * an earlier run. The witness-set assertion is what makes the measurement honest: a transaction
     * that still carried the scripts would also be a transaction whose size means nothing here.
     * <p>
     * <b>What this number is not.</b> The 19,862 figure above is <i>priced</i>; this test builds with
     * no {@link com.bloxbean.cardano.client.api.TransactionEvaluator}, so every redeemer still carries
     * cardano-client-lib's placeholder ex-units, and nothing is signed. Quoting the two side by side
     * as if they were the same measurement is apples-to-oranges. The gap was measured rather than
     * waved at: substituting the real cost (2,255,013 mem / 777,825,970 steps) into every redeemer
     * and adding one vkey witness takes the oracle-bearing shape from 1,986 to <b>2,124 bytes</b> —
     * 138 bytes, against 14,260 of remaining headroom. The verdict does not move, which is precisely
     * why the honest framing costs nothing.
     */
    @Test
    void oneAdaLiquidationFitsUnderMaxTxSizeOnceTheReferenceScriptsAreResolved() {
        Scenario scenario = scenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 100_000_000L);

        Transaction tx = build(List.of(scenario), PUBLISHED);

        assertTrue(tx.getWitnessSet().getPlutusV3Scripts().isEmpty(),
                "a published script must be read from its reference input, not carried");
        List<TransactionInput> refInputs = tx.getBody().getReferenceInputs();
        for (TransactionInput coordinate : publishedCoordinates()) {
            assertTrue(refInputs.contains(coordinate),
                    "reference script " + coordinate.getTransactionId() + "#" + coordinate.getIndex()
                            + " is missing from the reference inputs");
        }

        int size = serializedSize(tx);
        log.info("Liquidate N=1 ada/ada with published reference scripts: {} bytes (maxTxSize {})",
                size, MAX_TX_SIZE);
        assertTrue(size < MAX_TX_SIZE,
                "the no-oracle liquidation is " + size + " bytes, maxTxSize is " + MAX_TX_SIZE);
    }

    /**
     * The same transaction handed to the UPLC machine with the six published reference-script UTxOs
     * in the universe, so the scripts have to be resolved through {@link EvalFixtures#scriptSupplier}
     * rather than read out of the witness set — the path the class javadoc of {@link EvalFixtures}
     * describes as "currently never consulted".
     * <p>
     * The ex-units are not re-litigated here; what is being proven is only that the reference-script
     * path evaluates at all, so that the size measured above belongs to a transaction the validators
     * still accept.
     * <p>
     * That the six UTxOs are load-bearing rather than decorative was measured, not assumed: dropping
     * them from the universe and leaving everything else alone turns this test red with
     * {@code ApiException: Error evaluating transaction / NoSuchElementException: No value present} —
     * the evaluator finding a reference input it cannot resolve a script from.
     */
    @Test
    void theReferenceScriptShapeStillEvaluatesAgainstTheDeployedValidators() {
        Scenario scenario = scenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 100_000_000L);

        Transaction tx = build(List.of(scenario), PUBLISHED);
        List<Utxo> universe = new ArrayList<>(universe(List.of(scenario)));
        universe.addAll(referenceScriptUtxos());

        List<EvaluationResult> results = EvalFixtures.evaluate(tx, universe, REGISTRY);

        assertRedeemerCoverage(tx, results);
        assertEquals(2, count(results, RedeemerTag.Spend));
        assertEquals(1, count(results, RedeemerTag.Mint));
        assertEquals(4, count(results, RedeemerTag.Reward));
        assertWithinBudget(results);
    }

    // ======================================================================================
    // N = 2
    // ======================================================================================

    /**
     * Two loans whose transaction hashes interleave loan and bond in canonical order
     * ({@code aa} loanA, {@code bb} bondB, {@code cc} loanB, {@code dd} bondA), so
     * {@code lenderBondInputIndexes} is {@code [1, 0]} rather than the identity. D5 — that the
     * indexes really pair each loan with its own bond — is asserted structurally by
     * {@link LiquidateTransactionBuilderTest}; here {@code lm_liquidate_action} checks it itself,
     * including its own {@code list.unique(..) == length(lenderBondInputs)} rule.
     */
    @Test
    void twoInterleavedAdaLoansEvaluateWithNonIdentityBondIndexes() {
        Scenario a = scenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 100_000_000L);
        Scenario b = scenario(LOAN_ID_B, TX_LOAN_B, TX_BOND_B, 100_000_000L, 100_000_000L);

        Transaction tx = build(List.of(a, b));
        List<EvaluationResult> results = EvalFixtures.evaluate(tx, universe(List.of(a, b)), REGISTRY);

        assertRedeemerCoverage(tx, results);
        assertEquals(4, count(results, RedeemerTag.Spend), "two loans and two bonds are spent");
        assertEquals(1, count(results, RedeemerTag.Mint), "one policy, one mint redeemer");
        assertEquals(4, count(results, RedeemerTag.Reward));
        assertWithinBudget(results);
    }

    // ======================================================================================
    // The adversarial case — the rig must be able to fail
    // ======================================================================================

    /**
     * {@code loan_claim_action} recomputes the debt itself and demands
     * {@code remainingDebt == inputAction.remainingDebt}. The builder's V4 guard refuses a batch
     * whose assessment does not reproduce, so the mutation is made <em>after</em> the transaction is
     * built, straight on the redeemer in the witness set — the only way to hand the machine a
     * transaction the builder would never emit.
     * <p>
     * If this ever passes, the rig is not reaching the validator's arithmetic and every other
     * assertion in this file is worthless.
     */
    @Test
    void aMutatedRemainingDebtIsRejectedByLoanClaimAction() {
        Scenario scenario = scenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 100_000_000L);
        List<Utxo> universe = universe(List.of(scenario));

        Transaction clean = build(List.of(scenario));
        EvalFixtures.evaluate(clean, universe, REGISTRY);

        Transaction mutated = build(List.of(scenario));
        BigInteger debt = scenario.assessment().remainingDebt();
        PlutusData tampered = LiquidationTxEncoder.loanClaimActionWithdrawRedeemer(
                configRefInputIndexOf(mutated),
                List.of(claimWithRemainingDebt(scenario, mutated, debt.add(BigInteger.ONE))));
        replaceClaimRedeemer(mutated, tampered);

        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(mutated, universe, REGISTRY);
        assertFalse(outcome.successful(),
                "loan_claim_action must reject a remainingDebt it does not recompute");
        // Named, not just "something failed": the rejection has to come from the withdrawal whose
        // redeemer was tampered with, or the mutation proved nothing about that validator.
        assertTrue(outcome.detail().contains(redeemerError(mutated,
                        LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()))),
                "expected loan_claim_action to be the rejecting script, got: " + outcome.detail());
    }

    /**
     * {@code list.unique(redeemer.assetOutputIndexes) == redeemer.assetOutputIndexes} is a top-level
     * conjunct of {@code lm_liquidate_action}, and it is there for a reason: a repeated index lets two
     * loans be settled against <em>one</em> asset-manager output, which is a double-satisfaction
     * attack. This hands the machine {@code [0, 0]} on the N=2 zero-equity batch and requires the
     * refusal, named at {@code lm_liquidate_action}'s own withdrawal index.
     * <p>
     * The honest build is evaluated first, clean, so that the failure below is attributable to the one
     * field that was changed and not to the fixture.
     */
    @Test
    void aDuplicateAssetOutputIndexIsRejectedByLmLiquidateAction() {
        Scenario a = scenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 100_000_000L);
        Scenario b = scenario(LOAN_ID_B, TX_LOAN_B, TX_BOND_B, 100_000_000L, 100_000_000L);
        List<Utxo> universe = universe(List.of(a, b));

        EvalFixtures.evaluate(build(List.of(a, b)), universe, REGISTRY);

        Transaction mutated = build(List.of(a, b));
        replaceLmLiquidateRedeemer(mutated, List.of(0L, 0L));

        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(mutated, universe, REGISTRY);
        assertFalse(outcome.successful(),
                "list.unique must reject a repeated assetOutputIndex — it is the double-satisfaction guard");
        assertTrue(outcome.detail().contains(redeemerError(mutated,
                        LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateActionScriptHash()))),
                "expected lm_liquidate_action to be the rejecting script, got: " + outcome.detail());
    }

    /**
     * The positional half of the field's meaning, which {@code list.unique} does not cover: {@code [1,
     * 0]} is duplicate-free and in range, and points each loan at the <em>other</em> loan's collateral
     * output. It has to be rejected anyway, because {@code validate_repayment_output} rebuilds the
     * expected datum from {@code loanInput.output_reference} and compares it with
     * {@code builtin.equals_data} — each collateral output carries its own loan's
     * {@code inputOutputReference}, so a swap is a datum mismatch.
     * <p>
     * This is the test that distinguishes a correct derivation from one that emits some other
     * permutation of the right numbers, which is precisely what a golden built on an identity fixture
     * cannot do.
     */
    @Test
    void anAssetOutputIndexPointingAtTheOtherLoansOutputIsRejectedByLmLiquidateAction() {
        Scenario a = scenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 100_000_000L);
        Scenario b = scenario(LOAN_ID_B, TX_LOAN_B, TX_BOND_B, 100_000_000L, 100_000_000L);
        List<Utxo> universe = universe(List.of(a, b));

        EvalFixtures.evaluate(build(List.of(a, b)), universe, REGISTRY);

        Transaction mutated = build(List.of(a, b));
        // Duplicate-free and in range, so list.unique and safe_list_at are both satisfied; only the
        // datum comparison can catch it.
        replaceLmLiquidateRedeemer(mutated, List.of(1L, 0L));

        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(mutated, universe, REGISTRY);
        assertFalse(outcome.successful(),
                "each collateral output carries its own loan's inputOutputReference, so a swap must fail");
        assertTrue(outcome.detail().contains(redeemerError(mutated,
                        LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateActionScriptHash()))),
                "expected lm_liquidate_action to be the rejecting script, got: " + outcome.detail());
    }

    /**
     * The out-of-range case, for the record: {@code safe_list_at} bottoms out in
     * {@code builtin.head_list}, so an index past the end is not a graceful {@code None} — the machine
     * dies with {@code EmptyList}. Same failure mode as a redeemer that is one field short, which is
     * what makes it worth pinning: it is the shape of error an operator would see if this field were
     * ever mis-derived upwards.
     */
    @Test
    void anOutOfRangeAssetOutputIndexKillsTheMachineRatherThanFailingGracefully() {
        Scenario a = scenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 100_000_000L);
        Scenario b = scenario(LOAN_ID_B, TX_LOAN_B, TX_BOND_B, 100_000_000L, 100_000_000L);
        List<Utxo> universe = universe(List.of(a, b));

        Transaction mutated = build(List.of(a, b));
        replaceLmLiquidateRedeemer(mutated, List.of(0L, 2L));   // only two asset-manager outputs exist

        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(mutated, universe, REGISTRY);
        assertFalse(outcome.successful(), "an index past the end of assetOutputs cannot be satisfied");
        assertTrue(outcome.detail().contains(redeemerError(mutated,
                        LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateActionScriptHash()))),
                "expected lm_liquidate_action to be the rejecting script, got: " + outcome.detail());
        assertTrue(outcome.detail().contains("EmptyList"),
                "safe_list_at bottoms out in head_list, so this is an EmptyList death: " + outcome.detail());
    }

    // ======================================================================================
    // scenario plumbing
    // ======================================================================================

    private record Scenario(LoanFixtures.LoanUtxo loan,
                            LoanFixtures.BondUtxo bond,
                            LiquidationAssessment assessment) {

        LiquidateTransactionBuilder.LoanLiquidation toLiquidation() {
            return new LiquidateTransactionBuilder.LoanLiquidation(assessment, loan.utxo(), bond.utxo());
        }
    }

    /**
     * An ada-principal / ada-collateral loan at 10% over one installment, 30 days late, with a
     * lender bond charging 10 per mille. {@code collateral} is the loan UTxO's whole lovelace
     * balance, which is what {@code get_collateral_amount} reads for an ada collateral.
     */
    private static Scenario scenario(String loanId, String loanTx, String bondTx,
                                     long principal, long collateral) {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(principal),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(loanTx, 0, loanId, datum, collateral, List.of());
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(bondTx, 0, loanId,
                LoanFixtures.bondDatum(BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY),
                        AssetType.ada()), 2_000_000L);
        LiquidationAssessment assessment = LoanFixtures.assess(bond.bond(), loan.loan(),
                OraclePriceFeed.unit(), OraclePriceFeed.unit(), VALID_FROM);
        return new Scenario(loan, bond, assessment);
    }

    private static List<Utxo> universe(List<Scenario> scenarios) {
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO, WALLET_UTXO,
                BOT_SPARE_UTXO));
        scenarios.forEach(scenario -> {
            universe.add(scenario.loan().utxo());
            universe.add(scenario.bond().utxo());
        });
        return universe;
    }

    private static Transaction build(List<Scenario> scenarios) {
        return build(scenarios, LiquidateTransactionBuilder.ReferenceScripts.none());
    }

    private static Transaction build(List<Scenario> scenarios,
                                     LiquidateTransactionBuilder.ReferenceScripts refs) {
        return builder(scenarios).build(request(scenarios, refs));
    }

    /**
     * The same build with {@link LiquidateTransactionBuilder}'s V8 positive-equity veto disabled, and
     * nothing else. Used by exactly one test — the one whose evaluation runs are <em>the evidence for
     * V8</em>. Building that transaction through the veto it justifies would make the veto
     * self-certifying; see the seam's own javadoc.
     */
    private static Transaction buildIgnoringPositiveEquityVeto(List<Scenario> scenarios) {
        return builder(scenarios).buildIgnoringPositiveEquityVeto(
                request(scenarios, LiquidateTransactionBuilder.ReferenceScripts.none()));
    }

    private static LiquidateTransactionBuilder builder(List<Scenario> scenarios) {
        return new LiquidateTransactionBuilder(REGISTRY, LoanFixtures.NETWORK, LoanFixtures.converters(),
                LoanFixtures.utxoSupplier(universe(scenarios)), EvalFixtures.protocolParams());
    }

    private static LiquidateTransactionBuilder.Request request(
            List<Scenario> scenarios, LiquidateTransactionBuilder.ReferenceScripts refs) {
        Map<String, OracleEntry> noOracles = Map.of();
        return new LiquidateTransactionBuilder.Request(
                scenarios.stream().map(Scenario::toLiquidation).toList(),
                CONFIG_UTXO, LM_CONFIG_UTXO, noOracles, WALLET_UTXO, LoanFixtures.botAddress(),
                VALID_FROM, VALID_TO, MARGIN, refs);
    }

    // ---- the published reference scripts ---------------------------------------------------

    /** The six coordinates of {@link #PUBLISHED}, in the record's own order. */
    private static List<TransactionInput> publishedCoordinates() {
        return List.of(PUBLISHED.loan(), PUBLISHED.loanSpend(), PUBLISHED.lenderManager(),
                PUBLISHED.lenderManagerSpend(), PUBLISHED.loanClaimAction(),
                PUBLISHED.lmLiquidateAction());
    }

    /**
     * A UTxO per published coordinate, each carrying the hash of the script that really sits there,
     * which is what {@link EvalFixtures#scriptSupplier} is keyed by. Nothing spends these — they are
     * only ever reference inputs — so they are parked at their own script's enterprise address,
     * where no coin selection can reach them.
     */
    private static List<Utxo> referenceScriptUtxos() {
        List<TransactionInput> coordinates = publishedCoordinates();
        List<PlutusScript> scripts = List.of(REGISTRY.getLoanScript(), REGISTRY.getLoanSpendScript(),
                REGISTRY.getLenderManagerScript(), REGISTRY.getLenderManagerSpendScript(),
                REGISTRY.getLoanClaimActionScript(), REGISTRY.getLmLiquidateActionScript());
        List<Utxo> utxos = new ArrayList<>();
        for (int i = 0; i < coordinates.size(); i++) {
            String scriptHash = scriptHash(scripts.get(i));
            utxos.add(Utxo.builder()
                    .txHash(coordinates.get(i).getTransactionId())
                    .outputIndex(coordinates.get(i).getIndex())
                    .address(LoanFixtures.entAddress(scriptHash))
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                    .referenceScriptHash(scriptHash)
                    .build());
        }
        return utxos;
    }

    private static String scriptHash(PlutusScript script) {
        try {
            return HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new AssertionError("cannot hash an applied loans-v4 script", e);
        }
    }

    /** The CBOR the ledger would count, measured rather than estimated. */
    private static int serializedSize(Transaction tx) {
        try {
            return tx.serialize().length;
        } catch (Exception e) {
            throw new AssertionError("cannot serialize the built transaction", e);
        }
    }

    // ---- reading the results back ---------------------------------------------------------

    /** Exactly one {@link EvaluationResult} per redeemer in the witness set, matched on tag+index. */
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

    /** Ex-units sanity: positive, and inside the protocol maximums the rig was given. */
    private static void assertWithinBudget(List<EvaluationResult> results) {
        BigInteger totalMem = BigInteger.ZERO;
        BigInteger totalSteps = BigInteger.ZERO;
        for (EvaluationResult result : results) {
            assertTrue(result.getExUnits().getMem().signum() > 0,
                    "a script that really ran costs memory: " + result);
            assertTrue(result.getExUnits().getSteps().signum() > 0,
                    "a script that really ran costs steps: " + result);
            totalMem = totalMem.add(result.getExUnits().getMem());
            totalSteps = totalSteps.add(result.getExUnits().getSteps());
        }
        assertTrue(totalMem.compareTo(BigInteger.valueOf(14_000_000L)) <= 0,
                "total memory " + totalMem + " exceeds maxTxExMem");
        assertTrue(totalSteps.compareTo(BigInteger.valueOf(10_000_000_000L)) <= 0,
                "total steps " + totalSteps + " exceeds maxTxExSteps");
    }

    /**
     * The asset-manager output datum a validator will compare against, built from an ASCII action
     * literal rather than from {@link LiquidationTxEncoder}'s constants. That independence is the
     * whole point: an expectation derived from the constant under test moves with it and pins
     * nothing.
     */
    private static String assetManagerDatum(Scenario scenario, String action, String ownerPolicyId) {
        Utxo loanUtxo = scenario.loan().utxo();
        return LiquidationTxEncoder.assetManagerDatumWithToken(new AssetManagerDatumWithToken(
                        loanUtxo.getTxHash(), loanUtxo.getOutputIndex(),
                        HexUtil.encodeHexString(action.getBytes(StandardCharsets.US_ASCII)),
                        new AssetType(ownerPolicyId, scenario.loan().loan().loanId())))
                .serializeToHex();
    }

    /** The body-order positions of the outputs sitting at the asset-manager spend credential. */
    private static List<Integer> assetManagerOutputIndexes(Transaction tx) {
        String credential = REGISTRY.getAssetManagerSpendScriptHash();
        List<Integer> indexes = new ArrayList<>();
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            String payment = new Address(outputs.get(i).getAddress()).getPaymentCredentialHash()
                    .map(HexUtil::encodeHexString).orElse(null);
            if (credential.equals(payment)) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static void swapOutputs(Transaction tx, int first, int second) {
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        TransactionOutput held = outputs.get(first);
        outputs.set(first, outputs.get(second));
        outputs.set(second, held);
    }

    /** The prefix the Aiken evaluator prints for the withdrawal made at {@code rewardAddress}. */
    private static String redeemerError(Transaction tx, String rewardAddress) {
        return "RedeemerError { tag: \"Withdraw\", index: " + withdrawalIndexOf(tx, rewardAddress);
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

    // ---- the adversarial mutation ------------------------------------------------------------

    /**
     * Rebuilds the claim the builder emitted for this scenario with a different
     * {@code remainingDebt}. Everything else — the bond output index, the two oracle indexes, the
     * lender auth — is read back off the transaction so the mutation really is one field.
     */
    private static ClaimData claimWithRemainingDebt(Scenario scenario, Transaction tx,
                                                    BigInteger remainingDebt) {
        return new ClaimData(
                scenario.loan().loan().datum().liquidationMode(),
                BigInteger.valueOf(bondOutputIndexOf(tx, scenario)),
                BigInteger.ZERO,
                BigInteger.ZERO,
                scenario.bond().bond().datum().lenderAuth(),
                scenario.assessment().equity(),
                scenario.loan().loan().loanId(),
                remainingDebt);
    }

    private static long bondOutputIndexOf(Transaction tx, Scenario scenario) {
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            TransactionOutput output = outputs.get(i);
            if (output.getAddress().equals(scenario.bond().utxo().getAddress())
                    && output.getInlineDatum() != null
                    && output.getInlineDatum().serializeToHex()
                    .equalsIgnoreCase(scenario.bond().utxo().getInlineDatum())) {
                return i;
            }
        }
        throw new AssertionError("no bond echo in the built transaction");
    }

    /** Where the main config landed in the canonically sorted reference-input set. */
    private static long configRefInputIndexOf(Transaction tx) {
        List<TransactionInput> refInputs = tx.getBody().getReferenceInputs().stream()
                .sorted(new TransactionInputComparator())
                .toList();
        int index = refInputs.indexOf(new TransactionInput(TX_CONFIG, 0));
        if (index < 0) {
            throw new AssertionError("no main config reference input");
        }
        return index;
    }

    /**
     * The {@code assetOutputIndexes} the builder actually emitted, read off the <b>deserialised</b>
     * transaction — the bytes the machine will see — rather than off the in-memory redeemer object or
     * off {@link LiquidationTxEncoder}.
     */
    private static List<BigInteger> emittedAssetOutputIndexes(Transaction tx) {
        return lmLiquidateFields(deserialise(tx)).get(3) instanceof ListPlutusData list
                ? list.getPlutusDataList().stream()
                .map(item -> ((BigIntPlutusData) item).getValue())
                .toList()
                : List.of();
    }

    /**
     * Replaces <b>only</b> {@code assetOutputIndexes} — field 3 — of the {@code lm_liquidate_action}
     * withdrawal's redeemer, in the shape of {@link #replaceClaimRedeemer}.
     * <p>
     * Fields 0..2 are lifted verbatim off the deserialised transaction rather than rebuilt from the
     * fixture, so the mutation really is one field: a rebuilt redeemer that happened to differ
     * elsewhere would make the resulting refusal prove nothing about this field.
     */
    private static void replaceLmLiquidateRedeemer(Transaction tx, List<Long> assetOutputIndexes) {
        List<PlutusData> fields = lmLiquidateFields(deserialise(tx));
        List<PlutusData> mutated = new ArrayList<>(fields.subList(0, 3));
        mutated.add(ListPlutusData.of(assetOutputIndexes.stream()
                .map(BigIntPlutusData::of)
                .toArray(PlutusData[]::new)));

        int withdrawalIndex = withdrawalIndexOf(tx,
                LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateActionScriptHash()));
        for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
            if (redeemer.getTag() == RedeemerTag.Reward
                    && redeemer.getIndex().intValue() == withdrawalIndex) {
                redeemer.setData(ConstrPlutusData.builder()
                        .alternative(0)
                        .data(ListPlutusData.of(mutated.toArray(PlutusData[]::new)))
                        .build());
                return;
            }
        }
        throw new AssertionError("no reward redeemer for the lm_liquidate_action withdrawal");
    }

    /** The four fields of the {@code lm_liquidate_action} withdrawal's redeemer constructor. */
    private static List<PlutusData> lmLiquidateFields(Transaction tx) {
        int withdrawalIndex = withdrawalIndexOf(tx,
                LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateActionScriptHash()));
        for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
            if (redeemer.getTag() == RedeemerTag.Reward
                    && redeemer.getIndex().intValue() == withdrawalIndex) {
                ConstrPlutusData constr = (ConstrPlutusData) redeemer.getData();
                List<PlutusData> fields = constr.getData().getPlutusDataList();
                if (constr.getAlternative() != 0 || fields.size() != 4) {
                    throw new AssertionError("the lm_liquidate redeemer is constr "
                            + constr.getAlternative() + " with " + fields.size()
                            + " fields, expected constr 0 with 4");
                }
                return fields;
            }
        }
        throw new AssertionError("no reward redeemer for the lm_liquidate_action withdrawal");
    }

    private static Transaction deserialise(Transaction tx) {
        try {
            return Transaction.deserialize(tx.serialize());
        } catch (Exception e) {
            throw new AssertionError("the built transaction does not round-trip through CBOR", e);
        }
    }

    /** Swaps the {@code loan_claim_action} withdrawal's redeemer data in place. */
    private static void replaceClaimRedeemer(Transaction tx, PlutusData replacement) {
        int withdrawalIndex = withdrawalIndexOf(tx,
                LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()));
        for (Redeemer redeemer : tx.getWitnessSet().getRedeemers()) {
            if (redeemer.getTag() == RedeemerTag.Reward
                    && redeemer.getIndex().intValue() == withdrawalIndex) {
                redeemer.setData(replacement);
                return;
            }
        }
        throw new AssertionError("no reward redeemer for the loan_claim_action withdrawal");
    }
}
