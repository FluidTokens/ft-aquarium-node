package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
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
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Proof that a {@code LiquidateAndPayInAdvance} liquidation can be built and would pass the
 * deployed preview validators.</b> The real preview loan
 * {@code f855d1b4…#1} — 100 000 000 tFLDT of collateral against a 28 000 000-lovelace principal, with
 * a lender bond ({@code f855d1b4…#3}) whose {@code shouldLiquidationConvertToPrincipal == True} and
 * {@code liquidationFeePerMille == 50} — is liquidated in the pay-in-advance mode by
 * {@link LiquidatePayInAdvanceTransactionBuilder} and handed to the real PlutusV3 machine through
 * {@link EvalFixtures#evaluate}. No network, no key, no wallet, no submission: the builder has a null
 * transaction processor and this test only <em>evaluates</em> an unsigned transaction. It proves the
 * capability in principle; it arms nothing — the live bot is unchanged and the scanner still excludes
 * this loan under {@code CONVERSION_TO_PRINCIPAL_REQUIRED}.
 *
 * <h2>Why this loan is the pay-in-advance fixture</h2>
 * {@link RealLoanDryEvalTest} and {@link RealEquityLoanDryEvalTest} both liquidate loans whose bond
 * says {@code shouldLiquidationConvertToPrincipal == False} — the plain {@code Liquidate} path, where
 * the lender receives the collateral. This bond says {@code True}, which
 * {@code lm_liquidate_action.ak:147} refuses and {@code lm_liquidate_and_pay_in_advance_action.ak}
 * requires: the lender is paid the principal-currency value of the collateral (ADA) in advance, and
 * the bot keeps the collateral. The loan is comfortably positive-equity at the pinned instant, so the
 * borrower's compensation branch of {@code loan_claim_action} runs for real alongside the
 * pay-in-advance action.
 *
 * <h2>Fixture provenance</h2>
 * Every loan and bond byte is what the chain carries at {@code f855d1b4…}#1 and #3 (the transaction
 * that originated the loan). The two config reference inputs are {@link LoanFixtures}'s real
 * third-deployment preview {@code ConfigDatum}s, and the oracle wiring is
 * {@link RealLoanDryEvalTest}'s — the same fluid-oracle NFT, reference script and Charli3 provider the
 * loan datum names. Only the bot's wallet UTxO and the validity window are chosen here; the window
 * sits shortly after this loan's later {@code lendDate}.
 *
 * <h2>Reading a failure is not the mirror image of reading a success</h2>
 * As in {@link RealLoanDryEvalTest}: the evaluator reports only the <b>first</b> failing redeemer, so
 * a clean run says every redeemer passed while a failed run says only that the named one refused. The
 * negative test below names the refuser and its withdrawal index.
 */
@Slf4j
class LiquidatePayInAdvanceDryEvalTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    // ---- the real loan, transaction f855d1b4… on preview ----------------------------------------

    private static final String LOAN_TX =
            "f855d1b4cae6e1ec6db5aac9ef8038f53927e60004693729ce27d8273199aea1";
    private static final int LOAN_OUTPUT_INDEX = 1;
    private static final int BOND_OUTPUT_INDEX = 3;

    /** Asset name of both the loan NFT and the lender-bond NFT — the join key between the two. */
    private static final String LOAN_ID = "1d391e2258a62aeeae1275f2b31df80560e76732b266b2ab63c62e22";

    /** Inline datum of {@code f855d1b4…#1}, verbatim. Decoded by the production converter below. */
    private static final String LOAN_DATUM_HEX =
            "d8799f001a01ab3f001b000001a01e60ee00001901cb00d8799f4040ffd8799f4040ff0000d87b9f1864"
                    + "187d1864d87980ffd87b9f181c05ff0000d879805821504f4f4c00183f8ba4d1e645b1e26e9caf5"
                    + "6f802b129b50d833689727c920abe11d8799f581c0b77d150c275bd0a600633e4be7d09f83c4b9f"
                    + "00981e22ac9c9d3f62d8799f490014df1074464c4454ffd8799f581c9a2ec5c92daccbb269611a9"
                    + "eae7a40f9788d3f9c0229661b6234286f49000de1406f766f3633ffffff";

    /**
     * Inline datum of {@code f855d1b4…#3}, verbatim. {@code shouldLiquidationConvertToPrincipal == True}
     * ({@code d87a80}) and {@code liquidationFeePerMille == 50} ({@code 1832}). The whole output is
     * compared against its echo with {@code builtin.equals_data}, so these exact bytes are re-emitted.
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

    // ---- the oracle, from RealLoanDryEvalTest (the tFLDT entry) ----------------------------------

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

    // ---- the instant. This loan's lendDate is 1_787_216_064_000; the window sits ~1h after it. ----

    private static final long LEND_DATE = 1_787_216_064_000L;
    private static final long NOW = LEND_DATE + 3_600_000L;   // 1_787_219_664_000
    private static final long VALID_TO_MILLIS = NOW + 120_000L;
    private static final long FEED_VALID_FROM = NOW - 35_555L;
    private static final long FEED_VALID_TO = FEED_VALID_FROM + 600_000L;
    private static final long MARGIN = 300_000L;

    // ---- the synthetic remainder ----------------------------------------------------------------

    private static final String TX_CONFIG = "f1".repeat(32);
    private static final String TX_LM_CONFIG = "f2".repeat(32);
    private static final String TX_WALLET = "e0".repeat(32);

    private static final Utxo CONFIG_UTXO = LoanFixtures.configUtxo(TX_CONFIG, 0);
    private static final Utxo LM_CONFIG_UTXO = LoanFixtures.lmConfigUtxo(TX_LM_CONFIG, 0);
    private static final Utxo WALLET_UTXO = LoanFixtures.adaUtxo(TX_WALLET, 0,
            LoanFixtures.botAddress(), 60_000_000L);

    private static final int MAX_TX_SIZE = 16_384;

    /**
     * The preview {@code loan-claim-action} reference-script coordinate (the one committed in
     * {@code application.yaml}) — where the 8 665-byte {@code loan_claim_action} script is published so
     * the convert liquidation can shed it from the witness set. Any coordinate would do here; this is
     * the real one for provenance.
     */
    private static final TransactionInput LOAN_CLAIM_ACTION_REF = new TransactionInput(
            "48c102c0034b04558c640df211045fdd7511dc7046b55942ca5909372eab24cd", 0);

    // ======================================================================================
    // the fixtures
    // ======================================================================================

    @Test
    void theRealLoanAndBondDatumsDecodeToWhatThePayInAdvancePathRequires() {
        LoanDatum loan = loanDatum();
        assertEquals(BigInteger.valueOf(28_000_000), loan.principalAmount(), "28 ADA principal");
        assertEquals(BigInteger.valueOf(LEND_DATE), loan.lendDate(), "the pinned lendDate");
        assertTrue(loan.principalAsset().isAda(), "ada principal, so the principal oracle leg short-circuits");
        assertTrue(loan.principalOracleAsset().isAda());
        assertEquals(COLLATERAL, loan.collateral().assetType());
        assertEquals(ORACLE_NFT, loan.collateral().oracleTokenAsset(),
                "the datum names the fluid oracle NFT for the collateral leg");
        assertTrue(loan.liquidationMode() instanceof LiquidationMode.Liquidation liquidation
                && !liquidation.equityInPrincipalCurrency(),
                "equity must be denominated in the collateral currency");

        LenderManagerDatum bond = bondDatum();
        assertTrue(bond.shouldLiquidationConvertToPrincipal(),
                "the pay-in-advance path requires shouldLiquidationConvertToPrincipal == True");
        assertEquals(BigInteger.valueOf(50), bond.liquidationFeePerMille(), "50 per mille");
        assertTrue(bond.principalAsset().isAda());
    }

    /** The reward address the oracle withdrawal is made at derives from the deployed oracle script hash. */
    @Test
    void theOracleWiringMatchesTheDeployedOracle() throws Exception {
        assertEquals(ORACLE_REWARD_ADDRESS, LoanFixtures.rewardAddress(ORACLE_SCRIPT_HASH));
        assertEquals(ORACLE_SCRIPT_HASH, HexUtil.encodeHexString(oracleScript().getScriptHash()));
    }

    // ======================================================================================
    // the deliverable
    // ======================================================================================

    /**
     * The real loan, liquidated in pay-in-advance mode, accepted by every deployed validator the
     * transaction invokes: two spends (the loan, the bond), one mint (the loan-NFT burn) and five
     * withdrawals — {@code loan.loan}, {@code loan_claim_action}, {@code lender_manager.lenderManager},
     * {@code lm_liquidate_and_pay_in_advance_action} and the oracle.
     */
    @Test
    void theRealLoanPayInAdvanceLiquidationEvaluatesAgainstTheDeployedValidators() {
        Fixture fixture = fixture();
        LiquidatePayInAdvanceTransactionBuilder.Numbers numbers =
                builder(fixture).numbers(fixture.request());

        // The arithmetic the chain will recompute, pinned as literals.
        assertEquals(BigInteger.valueOf(EQUITY), numbers.equity(),
                "positive equity, in collateral (tFLDT) currency");
        assertEquals(BigInteger.valueOf(REMAINING_DEBT), numbers.remainingDebt());
        assertEquals(BigInteger.valueOf(5_000_000), numbers.liquidationFee(),
                "100_000_000 tFLDT at 50 per mille");
        assertEquals(BigInteger.valueOf(COLLATERAL_AMOUNT - EQUITY - 5_000_000),
                numbers.collateralLenderShouldReceive(), "collateral - equity - fee");
        assertEquals(BigInteger.valueOf(CONVERTED_TO_PRINCIPAL),
                numbers.convertedLoanCollateralToPrincipalAmount(),
                "the collateral the lender should receive, priced into ada");

        Transaction tx = build(fixture);
        List<EvaluationResult> results =
                EvalFixtures.evaluate(tx, universe(fixture), REGISTRY, List.of(oracleScript()));

        assertRedeemerCoverage(tx, results);
        assertEquals(2, count(results, RedeemerTag.Spend), "the loan and the bond");
        assertEquals(1, count(results, RedeemerTag.Mint), "one policy, one burn redeemer");
        assertEquals(5, count(results, RedeemerTag.Reward),
                "loan, loan_claim_action, lenderManager, pay-in-advance action, oracle");

        Transaction body = deserialise(tx);

        // The pay-in-advance action and the parent LenderManager withdrawals are present.
        String actionReward = LoanFixtures.rewardAddress(
                REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash());
        String parentReward = LoanFixtures.rewardAddress(REGISTRY.getLenderManagerWithdrawScriptHash());
        assertTrue(body.getBody().getWithdrawals().stream()
                        .anyMatch(w -> actionReward.equals(w.getRewardAddress())),
                "the pay-in-advance action must be invoked by withdrawal");
        assertTrue(body.getBody().getWithdrawals().stream()
                        .anyMatch(w -> parentReward.equals(w.getRewardAddress())),
                "the parent LenderManager must be invoked by withdrawal");

        // The parent LenderManager redeemer carries LiquidateAndPayInAdvance (constructor index 3).
        ConstrPlutusData parent = (ConstrPlutusData) rewardRedeemer(body, parentReward).getData();
        ConstrPlutusData action = (ConstrPlutusData) parent.getData().getPlutusDataList().get(1);
        assertEquals(3, action.getAlternative(), "LenderManagerAction.LiquidateAndPayInAdvance");

        // The lender's paid-in-advance output: ada-only, converted_to_liquidity, owned by the lender bond.
        List<TransactionOutput> assetOutputs = assetManagerOutputs(body);
        assertEquals(2, assetOutputs.size(), "a borrower compensation and a lender converted output");
        long assetOutputIndex = payInAdvanceAssetOutputIndexes(body).get(0).longValueExact();
        TransactionOutput lender = assetOutputs.get((int) assetOutputIndex);
        assertEquals(1, flattenedCount(lender), "ada-only, dosProtection flatten == 1");
        assertTrue(lender.getValue().getCoin().compareTo(BigInteger.valueOf(CONVERTED_TO_PRINCIPAL)) >= 0);
        assertEquals(lenderConvertedDatumHex(), lender.getInlineDatum().serializeToHex());

        // The borrower's compensation output: equity tFLDT, partial_liquidation, owned by the borrower bond.
        TransactionOutput borrower = assetOutputs.get(0);
        assertEquals(BigInteger.valueOf(EQUITY), quantityOf(borrower, COLLATERAL));
        assertEquals(2, flattenedCount(borrower), "token plus min-ada rider");
        assertEquals(borrowerCompensationDatumHex(), borrower.getInlineDatum().serializeToHex());

        // The bond echo: byte-identical to its input.
        TransactionOutput echo = onlyOutputAt(body, REGISTRY.getLenderManagerSpendScriptHash(), "bond echo");
        assertEquals(BOND_DATUM_HEX, echo.getInlineDatum().serializeToHex());
        assertEquals(BigInteger.valueOf(BOND_LOVELACE), echo.getValue().getCoin());

        // The loan NFT is burned exactly once.
        assertEquals(BigInteger.ONE.negate(),
                mintedQuantity(body, new AssetType(REGISTRY.getLoanPolicyId(), LOAN_ID)));

        // Measured ex-units — real budget numbers, logged and pinned as upper bounds.
        BigInteger mem = BigInteger.ZERO;
        BigInteger steps = BigInteger.ZERO;
        for (EvaluationResult result : results) {
            assertTrue(result.getExUnits().getMem().signum() > 0, "a script that ran costs memory");
            assertTrue(result.getExUnits().getSteps().signum() > 0, "a script that ran costs steps");
            log.info("  {}#{} [{}]: mem {} steps {}", result.getRedeemerTag(), result.getIndex(),
                    redeemerLabel(body, result), result.getExUnits().getMem(),
                    result.getExUnits().getSteps());
            mem = mem.add(result.getExUnits().getMem());
            steps = steps.add(result.getExUnits().getSteps());
        }
        int size = serializedSize(tx);
        log.info("pay-in-advance liquidation of {}: {} bytes, mem {}, steps {}", LOAN_ID, size, mem, steps);
        assertTrue(mem.compareTo(BigInteger.valueOf(14_000_000L)) <= 0, "total mem " + mem);
        assertTrue(steps.compareTo(BigInteger.valueOf(10_000_000_000L)) <= 0, "total steps " + steps);
    }

    /**
     * <b>The oracle-indexed money output is load-bearing.</b> Repointing {@code assetOutputIndexes} at
     * the borrower's compensation output (filtered slot 0, a {@code partial_liquidation} datum) instead
     * of the lender's paid-in-advance output is refused by
     * {@code lm_liquidate_and_pay_in_advance_action}: its {@code validate_repayment_output} compares the
     * output datum against a {@code converted_to_liquidity} datum with {@code builtin.equals_data}, and
     * the borrower slot cannot satisfy it. The refusal lands on the pay-in-advance action's own
     * withdrawal, named by index — the clean build passes it, so this isolates the one field that moved.
     */
    @Test
    void aWrongAssetOutputIndexIsRejectedByThePayInAdvanceAction() {
        Fixture fixture = fixture();
        List<Utxo> universe = universe(fixture);
        List<PlutusScript> extra = List.of(oracleScript());

        Transaction clean = build(fixture);
        EvalFixtures.evaluate(clean, universe, REGISTRY, extra);

        Transaction mutated = build(fixture);
        replaceAssetOutputIndexes(mutated, BigInteger.ZERO);

        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(mutated, universe, REGISTRY, extra);
        assertFalse(outcome.successful(),
                "the pay-in-advance action must reject an assetOutputIndex that names the borrower slot");
        String expected = redeemerError(mutated, LoanFixtures.rewardAddress(
                REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash()));
        assertTrue(outcome.detail().contains(expected),
                "expected " + expected + " to be the refuser, got: " + outcome.detail());
    }

    /**
     * <b>The convert liquidation fits under {@code maxTxSize} when {@code loan_claim_action} travels by
     * reference.</b> The same fixture, but with the 8 665-byte {@code loan_claim_action} published as a
     * reference script instead of witness-attached: the transaction evaluates to the identical
     * 8-redeemer shape with every ExUnit positive and within the same bounds — ref-vs-inline changes
     * serialization, not UPLC validation — yet the script is no longer in the witness set (it is a
     * reference input instead) and the serialized transaction is under {@link #MAX_TX_SIZE}, which the
     * all-inline shape is not.
     */
    @Test
    void referencingLoanClaimActionFitsUnderMaxTxSizeAndShedsItFromTheWitnessSet() throws Exception {
        Fixture fixture = fixture();

        // Same fixture, but loan_claim_action published as a reference script instead of witness-attached.
        LiquidatePayInAdvanceTransactionBuilder.Request base = fixture.request();
        LiquidatePayInAdvanceTransactionBuilder.Request refRequest =
                new LiquidatePayInAdvanceTransactionBuilder.Request(base.loan(), base.loanUtxo(),
                        base.bond(), base.bondUtxo(), base.walletUtxo(), base.configUtxo(),
                        base.lmConfigUtxo(), base.oracle(), base.validFromMillis(), base.validFromSlot(),
                        base.validToSlot(), base.changeAddress(),
                        new LiquidateTransactionBuilder.ReferenceScripts(
                                null, null, null, null, LOAN_CLAIM_ACTION_REF, null, null));

        // The chain must resolve the referenced script: publish it at the coord, min-ada carrier only —
        // mirrors the oracle ref-script UTxO above.
        List<Utxo> universe = new ArrayList<>(universe(fixture));
        universe.add(Utxo.builder()
                .txHash(LOAN_CLAIM_ACTION_REF.getTransactionId())
                .outputIndex(LOAN_CLAIM_ACTION_REF.getIndex())
                .address(LoanFixtures.botAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                .referenceScriptHash(REGISTRY.getLoanClaimActionScriptHash())
                .build());

        Transaction tx = new LiquidatePayInAdvanceTransactionBuilder(REGISTRY, LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams()).build(refRequest);

        // Same 8-redeemer shape, every ExUnit positive, same budget bounds as the all-inline path.
        List<EvaluationResult> results =
                EvalFixtures.evaluate(tx, universe, REGISTRY, List.of(oracleScript()));
        assertRedeemerCoverage(tx, results);
        assertEquals(2, count(results, RedeemerTag.Spend), "the loan and the bond");
        assertEquals(1, count(results, RedeemerTag.Mint), "one policy, one burn redeemer");
        assertEquals(5, count(results, RedeemerTag.Reward),
                "loan, loan_claim_action, lenderManager, pay-in-advance action, oracle");
        BigInteger mem = BigInteger.ZERO;
        BigInteger steps = BigInteger.ZERO;
        for (EvaluationResult result : results) {
            assertTrue(result.getExUnits().getMem().signum() > 0, "a script that ran costs memory");
            assertTrue(result.getExUnits().getSteps().signum() > 0, "a script that ran costs steps");
            mem = mem.add(result.getExUnits().getMem());
            steps = steps.add(result.getExUnits().getSteps());
        }
        assertTrue(mem.compareTo(BigInteger.valueOf(14_000_000L)) <= 0, "total mem " + mem);
        assertTrue(steps.compareTo(BigInteger.valueOf(10_000_000_000L)) <= 0, "total steps " + steps);

        // loan_claim_action moved: absent from the witness set, present as a reference input. Asserting
        // both proves the script was relocated, not merely that some byte count shrank.
        Transaction body = deserialise(tx);
        String claimHash = REGISTRY.getLoanClaimActionScriptHash();
        List<String> witnessHashes = new ArrayList<>();
        for (PlutusScript script : body.getWitnessSet().getPlutusV3Scripts()) {
            witnessHashes.add(HexUtil.encodeHexString(script.getScriptHash()));
        }
        assertFalse(witnessHashes.contains(claimHash),
                "loan_claim_action must not be witness-attached in the reference shape");
        assertTrue(body.getBody().getReferenceInputs().stream()
                        .anyMatch(in -> in.getTransactionId().equals(LOAN_CLAIM_ACTION_REF.getTransactionId())
                                && in.getIndex() == LOAN_CLAIM_ACTION_REF.getIndex()),
                "the loan_claim_action coordinate must be among the body's reference inputs");

        // Under budget — the whole point of A4.
        int size = serializedSize(tx);
        log.info("reference-shape pay-in-advance liquidation of {}: {} bytes", LOAN_ID, size);
        assertTrue(size < MAX_TX_SIZE, "reference shape must fit under maxTxSize, was " + size);
    }

    // ======================================================================================
    // the evaluator is load-bearing: real ex-units on the PRODUCTION wiring, not placeholders
    // ======================================================================================

    /**
     * <b>The defect this ticket exists to close, on the pay-in-advance path.</b> Every ex-units
     * assertion above reads {@link EvaluationResult#getExUnits()} — what the evaluator <em>said</em>.
     * That is not the number the chain charges: the chain reads the redeemers of the transaction, and
     * if nothing copied the evaluation into them they still hold cardano-client-lib's placeholders
     * (10000 mem, and 10000 or 1000 steps) against a measured cost orders of magnitude larger.
     * Under-declared ex-units are not rejected by the mempool; the transaction lands and fails during
     * on-chain evaluation, forfeiting the collateral.
     * <p>
     * So this test builds through the <b>evaluator-present</b> wiring — the same code path
     * {@code YaciConfig} arms, only with the offline PlutusV3 machine standing in for Blockfrost — and
     * reads each redeemer's declared ex-units off the <em>deserialised transaction body</em>, then
     * compares them with what the evaluator returned for that exact {@code (tag, index)} pair. A build
     * that wrote <em>an</em> evaluation into <em>every</em> redeemer without matching them up, or that
     * left the placeholders in place, both fail here.
     */
    @Test
    void theBuiltTransactionCarriesTheEvaluatedExUnitsAndNotThePlaceholders() throws Exception {
        Fixture fixture = fixture();
        List<Utxo> universe = universe(fixture);

        int[] calls = {0};
        @SuppressWarnings("unchecked")
        List<EvaluationResult>[] captured = new List[]{null};
        AikenTransactionEvaluator aiken = new AikenTransactionEvaluator(
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(),
                EvalFixtures.scriptSupplier(REGISTRY, List.of(oracleScript())), SlotConfigs.preview());
        TransactionEvaluator evaluator = (cbor, inputUtxos) -> {
            calls[0]++;
            Result<List<EvaluationResult>> result = aiken.evaluateTx(cbor, inputUtxos);
            captured[0] = result.getValue();
            return result;
        };

        // The offline-with-evaluator constructor: the production complete() path (evaluator set,
        // ignoreScriptCostEvaluationError(false)), with the real UPLC machine instead of Blockfrost.
        Transaction built = new LiquidatePayInAdvanceTransactionBuilder(REGISTRY, LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(), evaluator)
                .build(fixture.request());

        // Once, though the builder assembles twice. The first assembly is the layout probe, whose claim
        // redeemers carry placeholder output indexes no validator accepts; costing it would fail by
        // construction and refuse every batch. Pinned in both directions: it must happen (or the
        // redeemers keep their placeholders) and it must not happen twice (each call is a remote round
        // trip in production).
        assertEquals(1, calls[0],
                "only the final assembly may be script-costed — never the throwaway layout probe");

        // Re-read from the bytes, not from the object the builder happens to hold.
        Transaction reread = Transaction.deserialize(built.serialize());
        List<Redeemer> redeemers = reread.getWitnessSet().getRedeemers();
        assertFalse(redeemers.isEmpty(), "a pay-in-advance liquidation has redeemers");
        assertEquals(8, redeemers.size(), "two spends, one mint, five withdrawals");

        for (Redeemer redeemer : redeemers) {
            EvaluationResult costing = costingFor(captured[0], redeemer);
            assertEquals(costing.getExUnits().getMem(), redeemer.getExUnits().getMem(),
                    "declared mem for " + redeemer.getTag() + "#" + redeemer.getIndex()
                            + " is not the evaluated one");
            assertEquals(costing.getExUnits().getSteps(), redeemer.getExUnits().getSteps(),
                    "declared steps for " + redeemer.getTag() + "#" + redeemer.getIndex()
                            + " is not the evaluated one");
            // And it is unmistakably not a placeholder: the placeholder mem is 10000, three to five
            // orders of magnitude under what these scripts really cost.
            assertTrue(redeemer.getExUnits().getMem().compareTo(BigInteger.valueOf(10_000)) > 0,
                    "declared mem for " + redeemer.getTag() + "#" + redeemer.getIndex()
                            + " is the 10000 placeholder — the evaluator was not load-bearing");
            assertTrue(redeemer.getExUnits().getSteps().compareTo(BigInteger.valueOf(10_000)) > 0,
                    "declared steps for " + redeemer.getTag() + "#" + redeemer.getIndex()
                            + " is a placeholder — the evaluator was not load-bearing");
        }
    }

    /**
     * The negative pin: with no evaluator (the offline rig's default, and the state nothing may ever be
     * submitted from), the redeemers keep cardano-client-lib's placeholder ex-units and nothing is
     * thrown. It is the measurement the whole defect rests on — the placeholder is a constant the
     * library writes, orders of magnitude under the real cost — and it is what makes the load-bearing
     * test above prove something: without an evaluator, that test's redeemers would all read 10000.
     */
    @Test
    void withNoEvaluatorTheRedeemersStillCarryPlaceholdersAndNothingIsThrown() throws Exception {
        Transaction built = build(fixture());   // the 4-arg, no-evaluator constructor
        Transaction reread = Transaction.deserialize(built.serialize());
        for (Redeemer redeemer : reread.getWitnessSet().getRedeemers()) {
            assertEquals(BigInteger.valueOf(10_000), redeemer.getExUnits().getMem(),
                    "the placeholder mem changed; the defect's measurement has to be redone");
            assertTrue(redeemer.getExUnits().getSteps().compareTo(BigInteger.valueOf(10_000)) <= 0,
                    "the placeholder steps changed: " + redeemer.getExUnits().getSteps());
        }
    }

    /**
     * The loud half. {@code ignoreScriptCostEvaluationError} defaults to {@code true}, which is what
     * turned "there is no evaluator" into a {@code log.warn} and a transaction full of placeholders. With
     * an evaluator wired, the builder sets it to {@code false}, so an evaluator that fails — Blockfrost
     * down, the transaction rejected by the evaluation endpoint — fails the build (which the executor
     * quarantines with the cause) instead of producing an unsubmittable-but-submitted transaction. The
     * evaluator's own words must survive to the operator, two cardano-client-lib wrappers notwithstanding.
     */
    @Test
    void anEvaluatorThatThrowsFailsTheBuildRatherThanFallingBackToPlaceholders() {
        Fixture fixture = fixture();
        TransactionEvaluator exploding = (cbor, inputUtxos) -> {
            throw new RuntimeException("blockfrost says no");
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new LiquidatePayInAdvanceTransactionBuilder(REGISTRY, LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe(fixture)), EvalFixtures.protocolParams(), exploding)
                        .build(fixture.request()));

        assertTrue(causeChain(thrown).contains("blockfrost says no"),
                "the evaluator's own reason must survive to the operator: " + causeChain(thrown));
    }

    /**
     * The failure shape that looks like success: HTTP 200 with an <em>empty</em> costing array. Checking
     * only {@code isSuccessful()} would pass this straight through — every redeemer would keep its
     * 10000-mem placeholder and, in live mode, forfeit collateral in phase 2. The coverage check inside
     * {@code reporting()} catches it, per {@code (tag, index)} pair.
     */
    @Test
    void anEvaluatorThatSucceedsWithNoCostingsFailsTheBuildRatherThanShippingPlaceholders() {
        Fixture fixture = fixture();
        TransactionEvaluator emptySuccess =
                (cbor, inputUtxos) -> Result.success("ok").withValue(List.<EvaluationResult>of());

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> new LiquidatePayInAdvanceTransactionBuilder(REGISTRY, LoanFixtures.NETWORK,
                        LoanFixtures.utxoSupplier(universe(fixture)), EvalFixtures.protocolParams(), emptySuccess)
                        .build(fixture.request()));

        assertTrue(causeChain(thrown).contains("costed 0 of "),
                "the failure must say how many of how many were costed: " + causeChain(thrown));
    }

    private static String causeChain(Throwable thrown) {
        StringBuilder detail = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            detail.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append('\n');
            if (t.getCause() == t) {
                break;
            }
        }
        return detail.toString();
    }

    private static EvaluationResult costingFor(List<EvaluationResult> results, Redeemer redeemer) {
        for (EvaluationResult result : results) {
            if (result.getRedeemerTag() == redeemer.getTag()
                    && result.getIndex() == redeemer.getIndex().intValue()) {
                return result;
            }
        }
        throw new AssertionError("no evaluation result for " + redeemer.getTag() + "#" + redeemer.getIndex());
    }

    // ======================================================================================
    // pinned arithmetic — measured off builder.numbers() and frozen
    // ======================================================================================

    private static final long REMAINING_DEBT = 28_000_147L;
    private static final long EQUITY = 8_919_184L;
    private static final long CONVERTED_TO_PRINCIPAL = 29_109_347L;

    // ======================================================================================
    // fixture plumbing
    // ======================================================================================


    // ======================================================================================
    // balancing that ADDS an input: the probe pass, and what the balancer may spend
    //
    // Two defects meet here, both found from the pinned cardano-client-lib v0.7.2 source:
    //   (b) ScriptBalanceTxProviders.balanceTx re-runs script-cost evaluation whenever balancing
    //       added inputs, and throws "Transaction evaluator is not set" UNCONDITIONALLY on that
    //       branch — ignoreScriptCostEvaluationError does not guard it. The layout probe has no
    //       evaluator by design, so before LayoutProbeEvaluator any build whose wallet utxo did not
    //       cover the whole transaction alone died in the probe.
    //   (a) DefaultUtxoSelectionStrategyImpl.accept() is `return true`: nothing stops the balancer
    //       spending the bot's own published reference script, which on preview sits at the bot's
    //       own operational address.
    // Neither is reachable at all unless balancing actually adds an input, which is what the
    // deliberately thin wallet utxo below forces.
    // ======================================================================================

    /**
     * A real offline PlutusV3 evaluator over the given universe, for the PRICED pass.
     */
    private static TransactionEvaluator offlineEvaluator(List<Utxo> universe) {
        return (cbor, inputUtxos) -> new AikenTransactionEvaluator(
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(),
                EvalFixtures.scriptSupplier(REGISTRY, List.of(oracleScript())), SlotConfigs.preview())
                .evaluateTx(cbor, new java.util.LinkedHashSet<>(universe));
    }

    /** The published loan_claim_action reference script, at the bot's own address. Real coordinate. */
    private static Utxo publishedScriptUtxo(long lovelace) {
        return Utxo.builder()
                .txHash("48c102c0034b04558c640df211045fdd7511dc7046b55942ca5909372eab24cd")
                .outputIndex(0)
                .address(LoanFixtures.botAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(lovelace))))
                .referenceScriptHash("9ae63b26c98d90024a45f9cdb57e4154f72144d44325f0a261b8bc1d")
                .build();
    }

    /** The universe with its funded wallet utxo replaced by a deliberately inadequate one. */
    private static List<Utxo> universeWithThinWallet(Fixture fixture, long walletLovelace,
                                                     List<Utxo> extra) {
        List<Utxo> universe = new ArrayList<>(universe(fixture).stream()
                .filter(u -> !(u.getTxHash().equals(TX_WALLET) && u.getOutputIndex() == 0))
                .toList());
        universe.add(LoanFixtures.adaUtxo(TX_WALLET, 0, LoanFixtures.botAddress(), walletLovelace));
        universe.addAll(extra);
        return universe;
    }

    private static LiquidatePayInAdvanceTransactionBuilder.Request thinWalletRequest(Fixture fixture,
                                                                                     long lovelace) {
        var r = fixture.request();
        return new LiquidatePayInAdvanceTransactionBuilder.Request(r.loan(), r.loanUtxo(), r.bond(),
                r.bondUtxo(),
                LoanFixtures.adaUtxo(TX_WALLET, 0, LoanFixtures.botAddress(), lovelace),
                r.configUtxo(), r.lmConfigUtxo(), r.oracle(), r.validFromMillis(), r.validFromSlot(),
                r.validToSlot(), r.changeAddress(), r.referenceScripts());
    }

    /**
     * Defect (b), and the positive half of (a). The wallet utxo cannot cover the 28-ada principal, so
     * balancing MUST add an input — which is the only way to reach either defect. The build has to
     * succeed, take the ordinary utxo, and leave the published reference script alone.
     * <p>
     * Remove {@code LayoutProbeEvaluator} from {@code complete} and this fails with "Transaction
     * evaluator is not set", thrown from the probe. That is the failure Giovanni's first funded
     * attempt would have hit.
     */
    @Test
    void aBuildThatNeedsABalancingInputSucceedsAndSpendsAnOrdinaryUtxo() {
        Fixture fixture = fixture();
        String ordinaryTx = "aa".repeat(32);
        Utxo ordinary = LoanFixtures.adaUtxo(ordinaryTx, 0, LoanFixtures.botAddress(), 100_000_000L);
        List<Utxo> universe = universeWithThinWallet(fixture, 2_000_000L,
                List.of(publishedScriptUtxo(100_000_000L), ordinary));

        Transaction tx = new LiquidatePayInAdvanceTransactionBuilder(REGISTRY, LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(),
                offlineEvaluator(universe))
                .build(thinWalletRequest(fixture, 2_000_000L));

        List<String> inputs = tx.getBody().getInputs().stream()
                .map(in -> in.getTransactionId() + "#" + in.getIndex())
                .toList();
        assertTrue(inputs.contains(ordinaryTx + "#0"),
                "a balancing input MUST have been added, or neither defect is exercised: " + inputs);
        assertFalse(inputs.contains(
                        "48c102c0034b04558c640df211045fdd7511dc7046b55942ca5909372eab24cd#0"),
                "the published reference script must never be spent: " + inputs);

        // COLLATERAL is selected by a DIFFERENT mechanism — QuickTxBuilder.buildCollateralOutput
        // constructs its OWN DefaultUtxoSelectionStrategyImpl (:507) instead of reading the one on
        // the context — so neither guard above can reach it. Collateral is consumed on a phase-2
        // failure, which makes it the more dangerous of the two paths.
        List<String> collateral = tx.getBody().getCollateral() == null ? List.<String>of()
                : tx.getBody().getCollateral().stream()
                        .map(in -> in.getTransactionId() + "#" + in.getIndex())
                        .toList();
        assertFalse(collateral.contains(
                        "48c102c0034b04558c640df211045fdd7511dc7046b55942ca5909372eab24cd#0"),
                "the published reference script must never be pledged as COLLATERAL either — a "
                        + "phase-2 failure would consume it: " + collateral);
    }

    /**
     * The deterministic half of (a). The published reference script is the only other ada at the
     * address and would comfortably cover the shortfall. Failing the build is the correct answer:
     * refusing to liquidate is recoverable, eating the reference script is not.
     */
    @Test
    void aBuildWillNotFundItselfFromThePublishedReferenceScript() {
        Fixture fixture = fixture();
        List<Utxo> universe = universeWithThinWallet(fixture, 2_000_000L,
                List.of(publishedScriptUtxo(100_000_000L)));
        var builder = new LiquidatePayInAdvanceTransactionBuilder(REGISTRY, LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(),
                offlineEvaluator(universe));

        var e = assertThrows(IllegalStateException.class,
                () -> builder.build(thinWalletRequest(fixture, 2_000_000L)));

        String chain = LiquidationExecutor.causeChain(e);
        assertTrue(chain.contains("Not enough funds"),
                "must fail for want of SPENDABLE funds, not by consuming the reference script "
                        + "and not for want of an evaluator: " + chain);
    }

    private record Fixture(Loan loan, Utxo loanUtxo, LenderBond bond, Utxo bondUtxo, OracleEntry oracle,
                           LiquidatePayInAdvanceTransactionBuilder.Request request) {
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
                Amount.asset(REGISTRY.getLenderBondPolicyId() + LOAN_ID, BigInteger.ONE)), BOND_DATUM_HEX);
        LenderBond bond = new LenderBond(LOAN_TX, BOND_OUTPUT_INDEX, BOND_ADDRESS, LOAN_ID,
                BOND_DATUM_HEX, bondDatum());

        OracleEntry oracle = LoanFixtures.charli3(COLLATERAL, ORACLE_NFT, ORACLE_SCRIPT_HASH,
                OraclePriceFeed.priceDataCharlie(COLLATERAL, PRICE, PRICE_DENOMINATOR,
                        FEED_VALID_FROM, FEED_VALID_TO),
                ORACLE_REF_INPUT, ORACLE_REF_SCRIPT, C3_PROVIDER);

        long[] slots = validitySlots();
        long validFromMillis = millisOf(converters().slot().slotToTime(slots[0]));
        LiquidatePayInAdvanceTransactionBuilder.Request request =
                new LiquidatePayInAdvanceTransactionBuilder.Request(loan, loanUtxo, bond, bondUtxo,
                        WALLET_UTXO, CONFIG_UTXO, LM_CONFIG_UTXO, oracle, validFromMillis,
                        slots[0], slots[1], LoanFixtures.botAddress(),
                        // All-inline shape: every validator travels in the witness set.
                        LiquidateTransactionBuilder.ReferenceScripts.none());
        return new Fixture(loan, loanUtxo, bond, bondUtxo, oracle, request);
    }

    private static List<Utxo> universe(Fixture fixture) {
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO, WALLET_UTXO,
                fixture.loanUtxo(), fixture.bondUtxo()));
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

    private static PlutusScript oracleScript() {
        try {
            return PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(
                    LoanFixtures.fixture("preview-oracle-script.hex"), PlutusVersion.v3);
        } catch (Exception e) {
            throw new AssertionError("cannot load the deployed oracle script", e);
        }
    }

    private static LiquidatePayInAdvanceTransactionBuilder builder(Fixture fixture) {
        return new LiquidatePayInAdvanceTransactionBuilder(REGISTRY, LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(universe(fixture)), EvalFixtures.protocolParams());
    }

    private static Transaction build(Fixture fixture) {
        return builder(fixture).build(fixture.request());
    }

    private static CardanoConverters converters() {
        return LoanFixtures.converters();
    }

    private static long[] validitySlots() {
        CardanoConverters conv = converters();
        long slotFrom = conv.time().toSlot(utc(NOW));
        if (millisOf(conv.slot().slotToTime(slotFrom)) < NOW) {
            slotFrom += 1;
        }
        long slotTo = conv.time().toSlot(utc(VALID_TO_MILLIS));
        if (millisOf(conv.slot().slotToTime(slotTo)) > VALID_TO_MILLIS) {
            slotTo -= 1;
        }
        return new long[]{slotFrom, slotTo};
    }

    private static LocalDateTime utc(long millis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
    }

    private static long millisOf(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    // ---- reading the built transaction back ------------------------------------------------------

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

    private static void assertRedeemerCoverage(Transaction tx, List<EvaluationResult> results) {
        List<String> expected = tx.getWitnessSet().getRedeemers().stream()
                .map(redeemer -> redeemer.getTag() + "#" + redeemer.getIndex())
                .sorted().toList();
        List<String> actual = results.stream()
                .map(result -> result.getRedeemerTag() + "#" + result.getIndex())
                .sorted().toList();
        assertEquals(expected, actual, "every redeemer must have been evaluated, and nothing else");
    }

    private static long count(List<EvaluationResult> results, RedeemerTag tag) {
        return results.stream().filter(result -> result.getRedeemerTag() == tag).count();
    }

    private static String redeemerLabel(Transaction body, EvaluationResult result) {
        if (result.getRedeemerTag() != RedeemerTag.Reward) {
            return result.getRedeemerTag().toString();
        }
        String rewardAddress = body.getBody().getWithdrawals().get(result.getIndex()).getRewardAddress();
        return java.util.Map.of(
                LoanFixtures.rewardAddress(REGISTRY.getLoanPolicyId()), "loan",
                LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()), "loan_claim_action",
                LoanFixtures.rewardAddress(REGISTRY.getLenderManagerWithdrawScriptHash()), "lenderManager",
                LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash()),
                "lm_liquidate_and_pay_in_advance_action",
                ORACLE_REWARD_ADDRESS, "oracle")
                .getOrDefault(rewardAddress, rewardAddress);
    }

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

    private static TransactionOutput onlyOutputAt(Transaction tx, String paymentScriptHash, String what) {
        List<TransactionOutput> matches = tx.getBody().getOutputs().stream()
                .filter(output -> paymentCredentialOf(output.getAddress()).equals(paymentScriptHash))
                .toList();
        assertEquals(1, matches.size(), "expected exactly one " + what + " output, got " + matches.size());
        return matches.getFirst();
    }

    private static String paymentCredentialOf(String address) {
        return new com.bloxbean.cardano.client.address.Address(address)
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse("");
    }

    private static BigInteger quantityOf(TransactionOutput output, AssetType asset) {
        return output.getValue().getMultiAssets().stream()
                .filter(multiAsset -> multiAsset.getPolicyId().equals(asset.policyId()))
                .flatMap(multiAsset -> multiAsset.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(a.getNameAsBytes()).equals(asset.assetName()))
                .map(com.bloxbean.cardano.client.transaction.spec.Asset::getValue)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static BigInteger mintedQuantity(Transaction tx, AssetType asset) {
        return tx.getBody().getMint().stream()
                .filter(multiAsset -> multiAsset.getPolicyId().equals(asset.policyId()))
                .flatMap(multiAsset -> multiAsset.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(a.getNameAsBytes()).equals(asset.assetName()))
                .map(com.bloxbean.cardano.client.transaction.spec.Asset::getValue)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static int flattenedCount(TransactionOutput output) {
        int tokens = output.getValue().getMultiAssets().stream()
                .mapToInt(multiAsset -> multiAsset.getAssets().size()).sum();
        return tokens + (output.getValue().getCoin().signum() > 0 ? 1 : 0);
    }

    private static String lenderConvertedDatumHex() {
        return LiquidationTxEncoder.assetManagerDatumWithToken(
                        new com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken(
                                LOAN_TX, LOAN_OUTPUT_INDEX,
                                LiquidatePayInAdvanceTransactionBuilder.CONVERTED_TO_LIQUIDITY_ACTION_HEX,
                                new AssetType(REGISTRY.getLenderBondPolicyId(), LOAN_ID)))
                .serializeToHex();
    }

    private static String borrowerCompensationDatumHex() {
        return LiquidationTxEncoder.assetManagerDatumWithToken(
                        new com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken(
                                LOAN_TX, LOAN_OUTPUT_INDEX,
                                LiquidatePayInAdvanceTransactionBuilder.PARTIAL_LIQUIDATION_ACTION_HEX,
                                new AssetType(REGISTRY.getBorrowerBondPolicyId(), LOAN_ID)))
                .serializeToHex();
    }

    // ---- the redeemers ---------------------------------------------------------------------------

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

    private static String redeemerError(Transaction tx, String rewardAddress) {
        return "RedeemerError { tag: \"Withdraw\", index: " + withdrawalIndexOf(tx, rewardAddress);
    }

    /** {@code assetOutputIndexes} — field 4 of the {@code lm_liquidate_and_pay_in_advance_action} redeemer. */
    private static List<BigInteger> payInAdvanceAssetOutputIndexes(Transaction tx) {
        ConstrPlutusData constr = (ConstrPlutusData) rewardRedeemer(tx,
                LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash())).getData();
        ListPlutusData indexes = (ListPlutusData) constr.getData().getPlutusDataList().get(4);
        return indexes.getPlutusDataList().stream()
                .map(item -> ((BigIntPlutusData) item).getValue()).toList();
    }

    /** Rewrites {@code assetOutputIndexes} — and only that — in the pay-in-advance redeemer to {@code [value]}. */
    private static void replaceAssetOutputIndexes(Transaction tx, BigInteger value) {
        Redeemer redeemer = rewardRedeemer(tx,
                LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash()));
        ConstrPlutusData original = (ConstrPlutusData) redeemer.getData();
        List<PlutusData> fields = new ArrayList<>(original.getData().getPlutusDataList());
        fields.set(4, ListPlutusData.of(BigIntPlutusData.of(value)));
        redeemer.setData(ConstrPlutusData.builder()
                .alternative(original.getAlternative())
                .data(ListPlutusData.of(fields.toArray(PlutusData[]::new)))
                .build());
    }
}
