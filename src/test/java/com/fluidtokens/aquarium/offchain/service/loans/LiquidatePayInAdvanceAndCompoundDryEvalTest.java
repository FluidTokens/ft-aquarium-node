package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Proof that a {@code LiquidateAndPayInAdvanceAndCompound} liquidation can be built and would pass
 * the deployed preview validators.</b> The real preview loan {@code 287dd41e…#1} — 100 000 000 tFLDT of
 * collateral against a 28 000 000-lovelace principal, with a lender bond ({@code 287dd41e…#3}) whose
 * {@code shouldLiquidationConvertToPrincipal == True} and {@code liquidationFeePerMille == 50} — is
 * liquidated in the pay-in-advance-and-compound mode: the converted collateral is compounded into the
 * real pool {@code 287dd41e…#0}, whose real pool-manager UTxO
 * ({@code b4da8866…#1}, {@code compoudingFeePerMille == 0}) is spent and echoed. The whole thing is
 * handed to the real PlutusV3 machine through {@link EvalFixtures#evaluate}. No network, no key, no
 * wallet, no submission: the builder has a null transaction processor and this test only
 * <em>evaluates</em> an unsigned transaction. It proves the capability in principle; it arms nothing —
 * the live bot is unchanged and the scanner still excludes this loan under
 * {@code CONVERSION_TO_PRINCIPAL_REQUIRED}.
 *
 * <h2>Fixture provenance</h2>
 * Every loan, bond, pool and pool-manager byte is what the chain carries: the loan, bond and pool at
 * {@code 287dd41e…}#1/#3/#0 (the transaction that created the pool and loan), the pool-manager at
 * {@code b4da8866…#1} (read read-only off preview). The two config reference inputs are
 * {@link LoanFixtures}'s real third-deployment preview {@code ConfigDatum}s (the same policy id
 * {@code c45d5306…} that {@code 287dd41e} itself references), and the oracle wiring is
 * {@link LiquidatePayInAdvanceDryEvalTest}'s — the same fluid-oracle NFT, reference script and Charli3
 * provider the loan datum names. Only the bot's wallet UTxO and the validity window are chosen here.
 *
 * <h2>Reading a failure is not the mirror image of reading a success</h2>
 * As {@link EvalFixtures} documents: the evaluator reports only the <b>first</b> failing redeemer, so a
 * clean run says every redeemer passed while a failed run says only that the named one refused. The
 * negative test below names the refuser and its withdrawal index.
 */
@Slf4j
class LiquidatePayInAdvanceAndCompoundDryEvalTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    // ---- the real loan / bond / pool, transaction 287dd41e… on preview --------------------------

    private static final String LOAN_TX =
            "287dd41e681a183d6450a614a98d50cf248fa7ed188f140f911b954e4ce9499f";
    private static final int LOAN_OUTPUT_INDEX = 1;
    private static final int BOND_OUTPUT_INDEX = 3;
    private static final int POOL_OUTPUT_INDEX = 0;

    /** Asset name of the loan NFT and the lender-bond NFT — the join key between them. */
    private static final String LOAN_ID = "c58e25f459554b923b0e37665883d533689bf5d5945b21b434890323";
    /** The pool NFT / pool-manager NFT asset name — {@code poolId}. */
    private static final String POOL_ID = "00183f8ba4d1e645b1e26e9caf56f802b129b50d833689727c920abe11";

    /** Inline datum of {@code 287dd41e…#1}, verbatim. Decoded by the production converter below. */
    private static final String LOAN_DATUM_HEX =
            "d8799f001a01ab3f001b000001a01e6ea9a0001901cb00d8799f4040ffd8799f4040ff0000d87b9f1864"
                    + "187d1864d87980ffd87b9f181c05ff0000d879805821504f4f4c00183f8ba4d1e645b1e26e9caf5"
                    + "6f802b129b50d833689727c920abe11d8799f581c0b77d150c275bd0a600633e4be7d09f83c4b9f"
                    + "00981e22ac9c9d3f62d8799f490014df1074464c4454ffd8799f581c9a2ec5c92daccbb269611a9"
                    + "eae7a40f9788d3f9c0229661b6234286f49000de1406f766f3633ffffff";

    /**
     * Inline datum of {@code 287dd41e…#3}, verbatim. {@code shouldLiquidationConvertToPrincipal == True}
     * ({@code d87a80}) and {@code liquidationFeePerMille == 50} ({@code 1832}). The whole output is
     * compared against its echo with {@code builtin.equals_data}, so these exact bytes are re-emitted.
     */
    private static final String BOND_DATUM_HEX =
            "d8799fd8799f581cea1bb1ccd33aeb9e02516c2eb50adbaa63d7b7538b03c96908bfc934ffd8799fd8799f"
                    + "d8799f581c1c5621a0d3f7ee5041ece1c8f41a9f611ab4bca268923c21b6ca8dc3ffffffd87a8018"
                    + "32581d00183f8ba4d1e645b1e26e9caf56f802b129b50d833689727c920abe11d8799f4040ffff";

    /**
     * Inline datum of the pool {@code 287dd41e…#0}, verbatim. Echoed byte-for-byte into the compounded
     * pool output; its {@code lenderAuth} is {@code CardanoWithdrawScript(poolManagerPolicyId)}
     * ({@code d87b9f581cb2324fbd…ff}), which the pool-manager withdrawal in this transaction satisfies.
     */
    private static final String POOL_DATUM_HEX =
            "d8799f444e4f4e45d87980d8799fd8799f4040ffd8799f4040ff1901cb000000d87b9f1864187d1864d879"
                    + "80ffd87b9f181c05ff0000d8798040ffd87b9f581cb2324fbdcace499f6f1a9599daaebd707eb0ca"
                    + "70edbd6676fa20520bffd8799fd87a9f581ce302d1bee8142bee85f7e76f68399a170b2a1454ac19"
                    + "ffd9927332d3ffd8799fd8799fd8799f581c1c5621a0d3f7ee5041ece1c8f41a9f611ab4bca26892"
                    + "3c21b6ca8dc3ffffffff58202f95afc6b341ccd223e592cfc9df85715b323a6c65d99c2a75737459"
                    + "5626ce9c9fd8799f581c0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62d8799"
                    + "f490014df1074464c4454ffd8799f581c9a2ec5c92daccbb269611a9eae7a40f9788d3f9c0229661"
                    + "b6234286f49000de1406f766f3633ffffff9f1864ff9f1832ffd87a80ff";

    /**
     * Inline datum of the pool-manager {@code b4da8866…#1}, verbatim. {@code PoolManagerDatum{
     * poolOwnerAuth: CardanoSignature(ea1bb1cc…), compoudingFeePerMille = 0}} ({@code …ff00ff}). The whole
     * output is compared against its echo with {@code builtin.equals_data}, so these exact bytes and the
     * exact value are re-emitted.
     */
    private static final String PM_DATUM_HEX =
            "d8799fd8799f581cea1bb1ccd33aeb9e02516c2eb50adbaa63d7b7538b03c96908bfc934ff00ff";

    private static final String LOAN_ADDRESS =
            "addr_test1zzrr2mm7vnwzsnn8eqsqf62dgf84sr3z2rq2xnne5a7mr0y788t0nqjduhey4swhxfp7h42thj"
                    + "hhvnjkmcgaps3ahx5qxanp9j";
    private static final String BOND_ADDRESS =
            "addr_test1zr3s95d7aq2zhm597lnk76pengtsk2s52jkpnl7ejfen95cu2cs6p5lhaegyrm8per6p48mpr2"
                    + "6tegngjg7zrdk23hps7h96kk";
    private static final String POOL_ADDRESS =
            "addr_test1zrqtup89qqtvzf9fj49svmx2temtrx4e0vyxve4dnax8c3gu2cs6p5lhaegyrm8per6p48mpr2"
                    + "6tegngjg7zrdk23hpsgrpkjm";
    private static final String PM_ADDRESS =
            "addr_test1zpeqm6heflk06jhzl723petzekluf7rvz77yr9g8rx6auvsu2cs6p5lhaegyrm8per6p48mpr2"
                    + "6tegngjg7zrdk23hpscmydxy";

    private static final AssetType COLLATERAL =
            new AssetType("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454");
    private static final AssetType POOL_NFT =
            new AssetType("65a0bc5e6e5152fbe2bf3e1053f4020f6c7ee0a563beb0fe070a7b93", POOL_ID);
    private static final AssetType PM_NFT =
            new AssetType("b2324fbdcace499f6f1a9599daaebd707eb0ca70edbd6676fa20520b", POOL_ID);

    private static final long COLLATERAL_AMOUNT = 100_000_000L;
    private static final long LOAN_LOVELACE = 3_000_000L;
    private static final long BOND_LOVELACE = 1_810_200L;
    private static final long POOL_LOVELACE = 447_750_000L;
    private static final long PM_LOVELACE = 1_456_780L;

    private static final String PM_TX =
            "b4da88664ed6257fa43a4a26c23e0ed4a89f1e9b21f915d9804312e806d6d71a";
    private static final int PM_OUTPUT_INDEX = 1;

    // ---- the oracle, from LiquidatePayInAdvanceDryEvalTest (the tFLDT entry) ---------------------

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

    // ---- the instant. This loan's lendDate is 1_787_216_964_000; the window sits ~1h after it. ----

    private static final long LEND_DATE = 1_787_216_964_000L;
    private static final long NOW = LEND_DATE + 3_600_000L;   // 1_787_220_564_000
    private static final long VALID_TO_MILLIS = NOW + 120_000L;
    private static final long FEED_VALID_FROM = NOW - 35_555L;
    private static final long FEED_VALID_TO = FEED_VALID_FROM + 600_000L;

    // ---- the synthetic remainder ----------------------------------------------------------------

    private static final String TX_CONFIG = "f1".repeat(32);
    private static final String TX_LM_CONFIG = "f2".repeat(32);
    private static final String TX_WALLET = "e0".repeat(32);

    private static final Utxo CONFIG_UTXO = LoanFixtures.configUtxo(TX_CONFIG, 0);
    private static final Utxo LM_CONFIG_UTXO = LoanFixtures.lmConfigUtxo(TX_LM_CONFIG, 0);
    private static final Utxo WALLET_UTXO = LoanFixtures.adaUtxo(TX_WALLET, 0,
            LoanFixtures.botAddress(), 60_000_000L);

    // ======================================================================================
    // the decode fixtures — real chain bytes decode to what the compound path requires
    // ======================================================================================

    @Test
    void theRealLoanAndBondDatumsDecodeToWhatTheCompoundPathRequires() {
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
                "the compound path requires shouldLiquidationConvertToPrincipal == True");
        assertEquals(BigInteger.valueOf(50), bond.liquidationFeePerMille(), "50 per mille");
        assertEquals(POOL_ID, bond.poolId(), "the bond names the pool being compounded into");
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
     * The real loan, liquidated in pay-in-advance-and-compound mode, accepted by every deployed validator
     * the transaction invokes: four spends (loan, bond, pool, pool-manager), one mint (the loan-NFT burn)
     * and nine withdrawals — {@code loan.loan}, {@code loan_claim_action},
     * {@code lender_manager.lenderManager}, {@code lm_liquidate_pay_in_advance_and_compound_action},
     * {@code pool.pool}, {@code pool_compound_action}, {@code pool_manager.poolManager},
     * {@code pm_compound_liquidity} and the oracle.
     */
    @Test
    void theRealLoanCompoundLiquidationEvaluatesAgainstTheDeployedValidators() {
        Fixture fixture = fixture();
        LiquidatePayInAdvanceAndCompoundTransactionBuilder.Numbers numbers =
                builder(fixture).numbers(fixture.request());

        log.info("MEASURED remainingDebt={} equity={} liquidationFee={} collateralLenderShouldReceive={} "
                        + "converted={}", numbers.remainingDebt(), numbers.equity(), numbers.liquidationFee(),
                numbers.collateralLenderShouldReceive(),
                numbers.convertedLoanCollateralToPrincipalAmount());

        // The arithmetic the chain will recompute, pinned as literals (they differ from
        // LiquidatePayInAdvanceDryEvalTest's f855d1b4 values — the lendDate differs).
        assertEquals(BigInteger.valueOf(EQUITY), numbers.equity(),
                "positive equity, in collateral (tFLDT) currency");
        assertEquals(BigInteger.valueOf(REMAINING_DEBT), numbers.remainingDebt());
        assertEquals(BigInteger.valueOf(5_000_000), numbers.liquidationFee(),
                "100_000_000 tFLDT at 50 per mille");
        assertEquals(BigInteger.valueOf(COLLATERAL_AMOUNT - EQUITY - 5_000_000),
                numbers.collateralLenderShouldReceive(), "collateral - equity - fee");
        assertEquals(BigInteger.valueOf(CONVERTED_TO_PRINCIPAL),
                numbers.convertedLoanCollateralToPrincipalAmount(),
                "the collateral the lender should receive, priced into ada — added to the pool");

        Transaction tx = build(fixture);
        List<EvaluationResult> results =
                EvalFixtures.evaluate(tx, universe(fixture), REGISTRY, List.of(oracleScript()));

        assertRedeemerCoverage(tx, results);
        assertEquals(4, count(results, RedeemerTag.Spend), "the loan, the bond, the pool, the pool-manager");
        assertEquals(1, count(results, RedeemerTag.Mint), "one policy, one burn redeemer");
        assertEquals(9, count(results, RedeemerTag.Reward),
                "loan, loan_claim_action, lenderManager, compound action, pool.pool, pool_compound_action, "
                        + "pool_manager, pm_compound_liquidity, oracle");

        Transaction body = deserialise(tx);

        // The parent LenderManager redeemer carries LiquidatePayInAdvanceAndCompound (constructor index 5).
        String parentReward = LoanFixtures.rewardAddress(REGISTRY.getLenderManagerWithdrawScriptHash());
        ConstrPlutusData parent = (ConstrPlutusData) rewardRedeemer(body, parentReward).getData();
        ConstrPlutusData action = (ConstrPlutusData) parent.getData().getPlutusDataList().get(1);
        assertEquals(5, action.getAlternative(), "LenderManagerAction.LiquidatePayInAdvanceAndCompound");

        // The compounded pool output: input value + converted lovelace, datum and address byte-identical.
        TransactionOutput poolOutput = onlyOutputAt(body, paymentCredentialOf(POOL_ADDRESS), "pool");
        assertEquals(BigInteger.valueOf(POOL_LOVELACE + CONVERTED_TO_PRINCIPAL),
                poolOutput.getValue().getCoin(), "pool input lovelace plus the converted amount");
        assertEquals(POOL_DATUM_HEX, poolOutput.getInlineDatum().serializeToHex(),
                "pool output datum byte-identical to the input");
        assertEquals(POOL_ADDRESS, poolOutput.getAddress(), "pool output address identical to the input");
        assertEquals(BigInteger.ONE, quantityOf(poolOutput, POOL_NFT), "pool NFT preserved");

        // The pool-manager output: byte-identical to its input (value, datum, address).
        TransactionOutput pmOutput = onlyOutputAt(body, paymentCredentialOf(PM_ADDRESS), "pool-manager");
        assertEquals(BigInteger.valueOf(PM_LOVELACE), pmOutput.getValue().getCoin());
        assertEquals(PM_DATUM_HEX, pmOutput.getInlineDatum().serializeToHex());
        assertEquals(PM_ADDRESS, pmOutput.getAddress());
        assertEquals(BigInteger.ONE, quantityOf(pmOutput, PM_NFT), "pool-manager NFT preserved");

        // The borrower's compensation output: equity tFLDT, partial_liquidation. No lender payout output.
        List<TransactionOutput> assetOutputs = assetManagerOutputs(body);
        assertEquals(1, assetOutputs.size(),
                "exactly one asset-manager output — the borrower compensation (no lender converted output)");
        TransactionOutput borrower = assetOutputs.get(0);
        assertEquals(BigInteger.valueOf(EQUITY), quantityOf(borrower, COLLATERAL));
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
        log.info("compound liquidation of {}: {} bytes, mem {}, steps {}", LOAN_ID, size, mem, steps);
        assertTrue(mem.compareTo(BigInteger.valueOf(14_000_000L)) <= 0, "total mem " + mem);
        assertTrue(steps.compareTo(BigInteger.valueOf(10_000_000_000L)) <= 0, "total steps " + steps);
    }

    /**
     * <b>The compounded pool output value is load-bearing.</b> Perturbing the pool output's lovelace by
     * −1 (so it no longer equals the pool input value plus the oracle-derived converted amount) is refused
     * by {@code lm_liquidate_pay_in_advance_and_compound_action}: its {@code correctOutputValue} conjunct
     * compares the pool output value against {@code poolInput.value |> add(ada, "", addedLiquidity)} with
     * {@code builtin.equals_data}, where {@code addedLiquidity} is computed from the oracles and is
     * independent of the output. Because {@code compoudingFeePerMille == 0}, no fee term can mask the
     * delta. The refusal is named by withdrawal index — the clean build passes it, so this isolates the
     * one field that moved.
     * <p>
     * ({@code pool_compound_action} would <em>not</em> catch this alone: it derives {@code addedPrincipal}
     * from the output itself, so an output that is smaller is internally consistent for it. The compound
     * action's oracle-anchored check is the one that binds the value, which is exactly why the compound
     * arithmetic is the risk this test defends.)
     */
    @Test
    void aWrongCompoundedPoolValueIsRejectedByTheCompoundAction() {
        Fixture fixture = fixture();
        List<Utxo> universe = universe(fixture);
        List<PlutusScript> extra = List.of(oracleScript());

        Transaction clean = build(fixture);
        EvalFixtures.evaluate(clean, universe, REGISTRY, extra);

        Transaction mutated = build(fixture);
        perturbPoolOutputCoin(mutated, BigInteger.ONE.negate());

        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(mutated, universe, REGISTRY, extra);
        assertFalse(outcome.successful(),
                "the compound action must reject a pool output value that is not input + addedLiquidity");
        String expected = redeemerError(mutated, LoanFixtures.rewardAddress(
                REGISTRY.getLmLiquidatePayInAdvanceAndCompoundActionScriptHash()));
        assertTrue(outcome.detail().contains(expected),
                "expected " + expected + " to be the refuser, got: " + outcome.detail());
    }

    /**
     * <b>The compounded pool output datum echo is load-bearing.</b> Replacing the pool output's inline
     * datum with a datum that is not the pool input datum is refused by {@code pool_compound_action}: its
     * {@code builtin.equals_data(output.datum, input.output.datum)} conjunct (verified against
     * {@code validators/pool/pool_compound_action.ak} at {@code ff005fb}) binds the echo. The compound
     * action {@code lm_liquidate_pay_in_advance_and_compound_action} does <em>not</em> catch this — it
     * checks the pool output <em>value</em> but explicitly leaves the datum to the pool compound action
     * ("Pool compound action already ensures datum and address are correct") — so the value-echo negative
     * above cannot stand in for this one. The refusal is named by withdrawal index; the clean build passes
     * it, so this isolates the one field that moved.
     */
    @Test
    void aWrongCompoundedPoolDatumIsRejected() {
        Fixture fixture = fixture();
        List<Utxo> universe = universe(fixture);
        List<PlutusScript> extra = List.of(oracleScript());

        Transaction clean = build(fixture);
        EvalFixtures.evaluate(clean, universe, REGISTRY, extra);

        Transaction mutated = build(fixture);
        perturbPoolOutputDatum(mutated);

        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(mutated, universe, REGISTRY, extra);
        assertFalse(outcome.successful(),
                "pool_compound_action must reject a pool output datum that is not equal_data to the input datum");
        String expected = redeemerError(mutated, LoanFixtures.rewardAddress(
                REGISTRY.getPoolCompoundActionScriptHash()));
        assertTrue(outcome.detail().contains(expected),
                "expected " + expected + " to be the refuser, got: " + outcome.detail());
    }

    /**
     * <b>The {@code pm_compound_liquidity} {@code self.redeemers} pointers are load-bearing.</b> The
     * pool-manager compound-liquidity redeemer carries two indexes into the finished body's
     * {@code self.redeemers} — {@code poolWithdrawRedeemerIndex} and
     * {@code lenderManagerWithdrawRedeemerIndex} — and {@code pm_compound_liquidity} reads the redeemers at
     * those positions and demands the first be {@code Withdraw(Script(poolWithdrawScriptHash))} carrying a
     * {@code PoolWithdrawRedeemer} with a {@code Compound} action (verified against
     * {@code validators/pool-manager/pm_compound_liquidity.ak} at {@code ff005fb}). Repointing
     * {@code poolWithdrawRedeemerIndex} at the (in-range) lender-manager withdraw redeemer instead — a
     * {@code Withdraw(Script(lenderManagerWithdrawScriptHash))} — makes that
     * {@code expect ... == Withdraw(Script(poolWithdrawScriptHash))} fail. The refusal is named by
     * withdrawal index; the clean build passes it, so this isolates the one pointer that moved.
     */
    @Test
    void aWrongPmCompoundLiquidityRedeemerIndexIsRejected() {
        Fixture fixture = fixture();
        List<Utxo> universe = universe(fixture);
        List<PlutusScript> extra = List.of(oracleScript());

        Transaction clean = build(fixture);
        EvalFixtures.evaluate(clean, universe, REGISTRY, extra);

        Transaction mutated = build(fixture);
        perturbPmCompoundLiquidityRedeemerIndex(mutated);

        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(mutated, universe, REGISTRY, extra);
        assertFalse(outcome.successful(),
                "pm_compound_liquidity must reject a poolWithdrawRedeemerIndex that does not point at the "
                        + "pool.pool withdraw redeemer");
        String expected = redeemerError(mutated, LoanFixtures.rewardAddress(
                REGISTRY.getPmCompoundLiquidityScriptHash()));
        assertTrue(outcome.detail().contains(expected),
                "expected " + expected + " to be the refuser, got: " + outcome.detail());
    }

    // ======================================================================================
    // pinned arithmetic — measured off builder.numbers() and frozen
    // ======================================================================================

    // Measured off builder.numbers() (logged as MEASURED above), then frozen. These coincide with
    // LiquidatePayInAdvanceDryEvalTest's f855d1b4 values NOT by being copied — they are the honest
    // measurement — but because this loan's perpetual remaining debt is a pure function of
    // (validFrom - lendDate) (loan_claim_action.ak at ff005fb) and the collateral price is time-
    // independent, and this test's window sits the same 1h after this loan's (different) real lendDate
    // as that one's does after its own. Same elapsed => same debt, same equity, same converted amount.
    private static final long REMAINING_DEBT = 28_000_147L;
    private static final long EQUITY = 8_919_184L;
    private static final long CONVERTED_TO_PRINCIPAL = 29_109_347L;

    // ======================================================================================
    // fixture plumbing
    // ======================================================================================

    private record Fixture(Loan loan, Utxo loanUtxo, LenderBond bond, Utxo bondUtxo, Utxo poolUtxo,
                           Utxo poolManagerUtxo, OracleEntry oracle,
                           LiquidatePayInAdvanceAndCompoundTransactionBuilder.Request request) {
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

        Utxo poolUtxo = LoanFixtures.utxo(LOAN_TX, POOL_OUTPUT_INDEX, POOL_ADDRESS, List.of(
                Amount.lovelace(BigInteger.valueOf(POOL_LOVELACE)),
                Amount.asset(LoanFixtures.unit(POOL_NFT), BigInteger.ONE)), POOL_DATUM_HEX);

        Utxo poolManagerUtxo = LoanFixtures.utxo(PM_TX, PM_OUTPUT_INDEX, PM_ADDRESS, List.of(
                Amount.lovelace(BigInteger.valueOf(PM_LOVELACE)),
                Amount.asset(LoanFixtures.unit(PM_NFT), BigInteger.ONE)), PM_DATUM_HEX);

        OracleEntry oracle = LoanFixtures.charli3(COLLATERAL, ORACLE_NFT, ORACLE_SCRIPT_HASH,
                OraclePriceFeed.priceDataCharlie(COLLATERAL, PRICE, PRICE_DENOMINATOR,
                        FEED_VALID_FROM, FEED_VALID_TO),
                ORACLE_REF_INPUT, ORACLE_REF_SCRIPT, C3_PROVIDER);

        long[] slots = validitySlots();
        long validFromMillis = millisOf(converters().slot().slotToTime(slots[0]));
        LiquidatePayInAdvanceAndCompoundTransactionBuilder.Request request =
                new LiquidatePayInAdvanceAndCompoundTransactionBuilder.Request(loan, loanUtxo, bond, bondUtxo,
                        poolUtxo, poolManagerUtxo, WALLET_UTXO, CONFIG_UTXO, LM_CONFIG_UTXO, oracle, POOL_ID,
                        validFromMillis, slots[0], slots[1], LoanFixtures.botAddress());
        return new Fixture(loan, loanUtxo, bond, bondUtxo, poolUtxo, poolManagerUtxo, oracle, request);
    }

    private static List<Utxo> universe(Fixture fixture) {
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO, WALLET_UTXO,
                fixture.loanUtxo(), fixture.bondUtxo(), fixture.poolUtxo(), fixture.poolManagerUtxo()));
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

    private static LiquidatePayInAdvanceAndCompoundTransactionBuilder builder(Fixture fixture) {
        return new LiquidatePayInAdvanceAndCompoundTransactionBuilder(REGISTRY, LoanFixtures.NETWORK,
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
        return java.util.Map.ofEntries(
                        java.util.Map.entry(LoanFixtures.rewardAddress(REGISTRY.getLoanPolicyId()), "loan"),
                        java.util.Map.entry(LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()),
                                "loan_claim_action"),
                        java.util.Map.entry(LoanFixtures.rewardAddress(
                                REGISTRY.getLenderManagerWithdrawScriptHash()), "lenderManager"),
                        java.util.Map.entry(LoanFixtures.rewardAddress(
                                REGISTRY.getLmLiquidatePayInAdvanceAndCompoundActionScriptHash()),
                                "lm_compound_action"),
                        java.util.Map.entry(LoanFixtures.rewardAddress(REGISTRY.getPoolPolicyId()), "pool.pool"),
                        java.util.Map.entry(LoanFixtures.rewardAddress(
                                REGISTRY.getPoolCompoundActionScriptHash()), "pool_compound_action"),
                        java.util.Map.entry(LoanFixtures.rewardAddress(REGISTRY.getPoolManagerPolicyId()),
                                "pool_manager"),
                        java.util.Map.entry(LoanFixtures.rewardAddress(
                                REGISTRY.getPmCompoundLiquidityScriptHash()), "pm_compound_liquidity"),
                        java.util.Map.entry(ORACLE_REWARD_ADDRESS, "oracle"))
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

    private static String borrowerCompensationDatumHex() {
        return LiquidationTxEncoder.assetManagerDatumWithToken(
                        new com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken(
                                LOAN_TX, LOAN_OUTPUT_INDEX,
                                LiquidatePayInAdvanceAndCompoundTransactionBuilder.PARTIAL_LIQUIDATION_ACTION_HEX,
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

    /** Rewrites the compounded pool output's lovelace — and only that — by {@code delta}. */
    private static void perturbPoolOutputCoin(Transaction tx, BigInteger delta) {
        String poolCredential = paymentCredentialOf(POOL_ADDRESS);
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        TransactionOutput poolOutput = null;
        for (TransactionOutput output : outputs) {
            if (paymentCredentialOf(output.getAddress()).equals(poolCredential)) {
                if (poolOutput != null) {
                    throw new IllegalStateException("more than one pool output to perturb");
                }
                poolOutput = output;
            }
        }
        if (poolOutput == null) {
            throw new IllegalStateException("no pool output to perturb");
        }
        Value value = poolOutput.getValue();
        poolOutput.setValue(Value.builder()
                .coin(value.getCoin().add(delta))
                .multiAssets(value.getMultiAssets())
                .build());
    }

    /**
     * Replaces the compounded pool output's inline datum — and only that — with {@code Constr 0 []}, a
     * valid inline datum that is not the pool input datum, so {@code pool_compound_action}'s
     * {@code equals_data(output.datum, input.output.datum)} no longer holds.
     */
    private static void perturbPoolOutputDatum(Transaction tx) {
        String poolCredential = paymentCredentialOf(POOL_ADDRESS);
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        TransactionOutput poolOutput = null;
        for (TransactionOutput output : outputs) {
            if (paymentCredentialOf(output.getAddress()).equals(poolCredential)) {
                if (poolOutput != null) {
                    throw new IllegalStateException("more than one pool output to perturb");
                }
                poolOutput = output;
            }
        }
        if (poolOutput == null) {
            throw new IllegalStateException("no pool output to perturb");
        }
        PlutusData wrong = ConstrPlutusData.of(0);
        if (poolOutput.getInlineDatum() != null
                && poolOutput.getInlineDatum().serializeToHex().equals(wrong.serializeToHex())) {
            throw new IllegalStateException("the chosen wrong datum coincides with the pool input datum");
        }
        poolOutput.setInlineDatum(wrong);
    }

    /**
     * Repoints the {@code pm_compound_liquidity} redeemer's {@code poolWithdrawRedeemerIndex} at the
     * {@code lenderManagerWithdrawRedeemerIndex} — a different, in-range {@code self.redeemers} slot whose
     * ScriptPurpose is {@code Withdraw(Script(lenderManagerWithdrawScriptHash))} — and changes nothing
     * else, so the validator's {@code expect ... == Withdraw(Script(poolWithdrawScriptHash))} fails.
     */
    private static void perturbPmCompoundLiquidityRedeemerIndex(Transaction tx) {
        String pmReward = LoanFixtures.rewardAddress(REGISTRY.getPmCompoundLiquidityScriptHash());
        Redeemer pm = rewardRedeemer(tx, pmReward);
        ConstrPlutusData data = (ConstrPlutusData) pm.getData();
        List<PlutusData> fields = data.getData().getPlutusDataList();
        BigInteger poolWithdrawRedeemerIndex = ((BigIntPlutusData) fields.get(0)).getValue();
        BigInteger lenderManagerWithdrawRedeemerIndex = ((BigIntPlutusData) fields.get(1)).getValue();
        if (poolWithdrawRedeemerIndex.equals(lenderManagerWithdrawRedeemerIndex)) {
            throw new IllegalStateException(
                    "the two self.redeemers indexes coincide; cannot repoint to a distinct redeemer");
        }
        pm.setData(ConstrPlutusData.of(0,
                BigIntPlutusData.of(lenderManagerWithdrawRedeemerIndex),
                BigIntPlutusData.of(lenderManagerWithdrawRedeemerIndex)));
    }

    // ======================================================================================
    // T-069 — the evaluator reaches the built transaction (CCL trap 8)
    // ======================================================================================

    /**
     * A stub that costs every redeemer with a value derived from its own position, so the numbers on
     * the finished body can only have come from here. Deliberately NOT the real Aiken evaluator: this
     * test is about whether an evaluator's output reaches the artefact, not about what the validators
     * cost — and the real one cannot run against these fixtures while they pin a superseded deployment.
     */
    private static final class PositionalStubEvaluator implements TransactionEvaluator {

        private int calls;

        @Override
        public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, java.util.Set<Utxo> inputUtxos) {
            calls++;
            List<EvaluationResult> results = new ArrayList<>();
            try {
                for (Redeemer redeemer : Transaction.deserialize(cbor).getWitnessSet().getRedeemers()) {
                    int index = redeemer.getIndex().intValue();
                    results.add(EvaluationResult.builder()
                            .redeemerTag(redeemer.getTag())
                            .index(index)
                            .exUnits(ExUnits.builder()
                                    .mem(BigInteger.valueOf(7_000_000L + index))
                                    .steps(BigInteger.valueOf(3_000_000_000L + index))
                                    .build())
                            .build());
                }
            } catch (Exception e) {
                throw new AssertionError("the builder handed the evaluator undeserialisable bytes", e);
            }
            return Result.success("ok").withValue(results);
        }
    }

    /** cardano-client-lib's placeholders: 10000 mem, and 10000 or 1000 steps by redeemer tag. */
    private static boolean isPlaceholder(Redeemer redeemer) {
        BigInteger mem = redeemer.getExUnits().getMem();
        BigInteger steps = redeemer.getExUnits().getSteps();
        return mem.equals(BigInteger.valueOf(10_000L))
                && (steps.equals(BigInteger.valueOf(10_000L)) || steps.equals(BigInteger.valueOf(1_000L)));
    }

    /**
     * T-069. The measured ex-units reach the FINISHED, RE-DESERIALISED transaction.
     *
     * <p>Read off the bytes, never off {@code EvaluationResult}: a suite of 307 tests once missed this
     * exact defect because every ex-units assertion in it read the evaluator's report, which is correct
     * whether or not the builder ever applied it. The report is the rig's; the transaction is what gets
     * submitted.
     */
    @Test
    void anEvaluatorsMeasuredExUnitsReachTheBuiltTransaction() throws Exception {
        Fixture fixture = fixture();
        PositionalStubEvaluator evaluator = new PositionalStubEvaluator();

        Transaction built = new LiquidatePayInAdvanceAndCompoundTransactionBuilder(
                REGISTRY, LoanFixtures.NETWORK, LoanFixtures.utxoSupplier(universe(fixture)),
                EvalFixtures.protocolParams(), evaluator)
                .build(fixture.request());

        assertTrue(evaluator.calls > 0, "the evaluator was never consulted — trap 8 is back");

        Transaction reread = Transaction.deserialize(built.serialize());
        List<Redeemer> redeemers = reread.getWitnessSet().getRedeemers();
        assertFalse(redeemers.isEmpty(), "a compound liquidation has redeemers");
        for (Redeemer redeemer : redeemers) {
            int index = redeemer.getIndex().intValue();
            assertEquals(BigInteger.valueOf(7_000_000L + index), redeemer.getExUnits().getMem(),
                    "redeemer " + redeemer.getTag() + "/" + index + " did not take the measured mem");
            assertEquals(BigInteger.valueOf(3_000_000_000L + index), redeemer.getExUnits().getSteps(),
                    "redeemer " + redeemer.getTag() + "/" + index + " did not take the measured steps");
        }
    }

    /**
     * T-075 SPIKE — is a compound liquidation approvable under the current gate?
     *
     * <p><b>Falsifying result, fixed before this was written:</b> if the bot's outlay is zero, it
     * funds nothing, and the suspicion dies on the spot.
     *
     * <p><b>Rewritten after review (A-1).</b> The first design measured {@code poolOutput − poolInput}
     * and called it the bot's cost. That is the POOL'S GAIN, and the two are equal only if the bot is
     * the sole source of the increase — which is the load-bearing claim and was the one thing the
     * method did not measure. This traces the BOT'S OWN value instead: what its wallet input carried,
     * minus what came back to its address.
     *
     * <p><b>Reports a THRESHOLD, not a floor (A-3).</b> {@code expectedFee} cannot be read off the
     * artefact, and computing it from the executor's fee model would contaminate a third of the
     * answer. Since {@code floor ≥ 0 ⟺ expectedFee ≥ txFee + outlay}, both right-hand terms ARE
     * measurable, so the spike reports the break-even threshold and leaves the fee question beside
     * the measurement rather than inside it.
     *
     * <p>⚠ <b>Known contamination, stated rather than hidden (A-2):</b> {@code txFee} follows ex-units,
     * ex-units follow the evaluator, and this body is built by the TEST path. A fee measured under the
     * rig is not the fee production pays, and if understated the threshold looks BETTER than reality.
     * Re-measure after the promotion wires a real evaluator.
     */
    @Test
    void t075IsACompoundLiquidationApprovable() throws Exception {
        Fixture fixture = fixture();
        Transaction built = Transaction.deserialize(build(fixture).serialize());
        String bot = LoanFixtures.botAddress();

        BigInteger botIn = WALLET_UTXO.getAmount().stream()
                .filter(a -> "lovelace".equals(a.getUnit()))
                .map(com.bloxbean.cardano.client.api.model.Amount::getQuantity)
                .findFirst().orElse(BigInteger.ZERO);
        BigInteger botBack = built.getBody().getOutputs().stream()
                .filter(o -> bot.equals(o.getAddress()))
                .map(o -> o.getValue().getCoin())
                .reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger outlay = botIn.subtract(botBack);

        BigInteger txFee = built.getBody().getFee();
        BigInteger threshold = txFee.add(outlay);

        // What the bot could earn at its ceiling, for scale only — NOT an input to the threshold.
        BigInteger feePerMille = fixture.bond().datum().liquidationFeePerMille();

        log.info("T-075 bot input={} returned={} OUTLAY={}", botIn, botBack, outlay);
        log.info("T-075 txFee={} (rig evaluator — see A-2) feePerMille={}", txFee, feePerMille);
        log.info("T-075 THRESHOLD: approvable iff expectedFee >= {} lovelace", threshold);
        log.info("T-075 outlay is {} => {}", outlay.signum() > 0 ? "POSITIVE" : "zero or negative",
                outlay.signum() > 0
                        ? "the bot funds this liquidation; the threshold is what it must earn back"
                        : "REFUTED — the bot funds nothing, so the suspicion does not hold");

        assertTrue(botIn.signum() > 0, "the spike must see the bot's own input to measure its outlay");
    }

    /**
     * The negative control, and the assertion above is worthless without it. With no evaluator the
     * builder must still produce a transaction, and its redeemers must still carry the placeholders —
     * which is what makes "the measured values arrived" a discriminating claim rather than a
     * restatement of whatever the builder happened to emit.
     */
    @Test
    void withoutAnEvaluatorTheRedeemersKeepTheirPlaceholders() throws Exception {
        Fixture fixture = fixture();

        Transaction built = build(fixture);

        Transaction reread = Transaction.deserialize(built.serialize());
        List<Redeemer> redeemers = reread.getWitnessSet().getRedeemers();
        assertFalse(redeemers.isEmpty(), "a compound liquidation has redeemers");
        for (Redeemer redeemer : redeemers) {
            assertTrue(isPlaceholder(redeemer),
                    "unpriced build: redeemer " + redeemer.getTag() + "/" + redeemer.getIndex()
                            + " carries " + redeemer.getExUnits().getMem() + "/"
                            + redeemer.getExUnits().getSteps() + " rather than a placeholder");
        }
    }
}
