package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken;
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.TransactionInputComparator;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural proof for {@link LiquidateTransactionBuilder}: a real {@code Liquidate} transaction is
 * assembled from synthetic fixtures with no chain access at all, and then taken apart again and
 * checked field by field — indexes against the canonically sorted body, outputs against design §8,
 * redeemer numbers against the assessment they came from.
 * <p>
 * These are <b>structural</b> claims. Whether the deployed validators accept the transaction is
 * slice C's question; nothing here asserts on-chain acceptance.
 */
@Slf4j
class LiquidateTransactionBuilderTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    /** A fixed instant well inside preview's history, so slot conversion is deterministic. */
    private static final long NOW = 1_700_000_000_000L;
    private static final long VALID_FROM = NOW;
    private static final long VALID_TO = NOW + 120_000L;
    private static final long MARGIN = 300_000L;
    /** 30 days before {@link #NOW}: past every deadline the fixture datums set, so the loans are late. */
    private static final long LATE_LEND_DATE = NOW - 30L * 24 * 3_600_000L;

    private static final String LOAN_ID_A = "a1b2c3d4e5f6a1b2";
    private static final String LOAN_ID_B = "b2c3d4e5f6a1b2c3";
    private static final String STAKE_KEY = "33333333333333333333333333333333333333333333333333333333";

    private static final String TX_LOAN_A = repeat("aa");
    private static final String TX_BOND_B = repeat("bb");
    private static final String TX_LOAN_B = repeat("cc");
    private static final String TX_BOND_A = repeat("dd");
    private static final String TX_ORACLE_NFT = repeat("9a");
    private static final String TX_ORACLE_SCRIPT = repeat("9b");
    private static final String TX_CHARLI3_PROVIDER = repeat("9c");
    private static final String TX_WALLET = repeat("e0");
    private static final String TX_BOT_SPARE = repeat("e1");
    private static final String TX_CONFIG = repeat("f1");
    private static final String TX_LM_CONFIG = repeat("f2");
    private static final String TX_REF_SCRIPTS = repeat("70");

    /** The real ledger limit — the one the transactions measured below have to fit under. */
    private static final int MAX_TX_SIZE = 16_384;

    /**
     * The six reference-script UTxOs FluidTokens published on preview, copied verbatim from the
     * {@code preview} profile of {@code src/main/resources/application.yaml}
     * ({@code loans.liquidation.reference-scripts.*}). {@code assetManager} is null there and null
     * here: a plain {@code Liquidate} never spends an asset-manager output, so that validator is
     * never invoked. {@link #TX_REF_SCRIPTS} stays the synthetic placeholder for the structural
     * reference-script test, which cares only that a coordinate is present, not which one.
     */
    private static final LiquidateTransactionBuilder.ReferenceScripts PUBLISHED_PREVIEW =
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

    private static final Utxo CONFIG_UTXO = LoanFixtures.adaUtxo(TX_CONFIG, 0,
            LoanFixtures.entAddress(LoanFixtures.CONFIG_POLICY_ID), 5_000_000L);
    private static final Utxo LM_CONFIG_UTXO = LoanFixtures.adaUtxo(TX_LM_CONFIG, 0,
            LoanFixtures.entAddress(LoanFixtures.LM_CONFIG_POLICY_ID), 5_000_000L);
    private static final Utxo WALLET_UTXO = LoanFixtures.adaUtxo(TX_WALLET, 0,
            LoanFixtures.botAddress(), 50_000_000L);
    private static final Utxo BOT_SPARE_UTXO = LoanFixtures.adaUtxo(TX_BOT_SPARE, 0,
            LoanFixtures.botAddress(), 10_000_000L);

    // Token collateral leg, priced by a Charli3 feed.
    private static final AssetType COLLATERAL_TOKEN = new AssetType(repeatHash("c0"), "544f4b");
    private static final AssetType ORACLE_TOKEN = new AssetType(repeatHash("b0"), "4f52434c");
    private static final String ORACLE_CREDENTIAL = repeatHash("a0");

    private static String repeat(String pair) {
        return pair.repeat(32);
    }

    /** 28 bytes — a policy id / script hash. */
    private static String repeatHash(String pair) {
        return pair.repeat(28);
    }

    // ======================================================================================
    // The test-scope datum encoder, pinned before anything is built on top of it
    // ======================================================================================

    /**
     * Every fixture datum below is produced by {@link LoanFixtures}'s test-scope encoder. If that
     * encoder disagreed with the production decoder, every scenario in this file would be built on
     * a datum the node could not read — so the two are pinned against each other first.
     */
    @Test
    void theFixtureEncoderRoundTripsThroughTheProductionLoanDatumConverter() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);

        assertEquals(datum, new LoanDatumConverter().deserialize(LoanFixtures.hex(datum)));
    }

    @Test
    void theFixtureEncoderRoundTripsThroughTheProductionLenderManagerDatumConverter() {
        LenderManagerDatum datum = LoanFixtures.bondDatum(BigInteger.TEN,
                LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), AssetType.ada());

        LenderManagerDatum decoded = new LenderManagerDatumConverter().deserialize(LoanFixtures.hex(datum));
        assertEquals(datum.lenderAuth(), decoded.lenderAuth());
        assertEquals(datum.liquidationFeePerMille(), decoded.liquidationFeePerMille());
        assertEquals(datum.poolId(), decoded.poolId());
        assertEquals(datum.principalAsset(), decoded.principalAsset());
        assertEquals(datum.shouldLiquidationConvertToPrincipal(),
                decoded.shouldLiquidationConvertToPrincipal());
        assertEquals(datum.lenderStakeCredential().serializeToHex(),
                decoded.lenderStakeCredential().serializeToHex());
    }

    /**
     * The stronger pin: a datum recorded off a real preview loan is decoded by the production
     * converter and re-encoded by the fixture encoder, and must come back as the exact bytes the
     * chain produced. A round trip through our own models only proves self-consistency; this proves
     * the encoder writes what the validators actually wrote.
     */
    @Test
    void theFixtureEncoderReproducesARecordedPreviewLoanDatumByteForByte() throws IOException {
        List<String> recorded = Files.readAllLines(
                        Path.of("src/test/resources/loans-v4/preview-loan-datums.hex"), StandardCharsets.UTF_8)
                .stream().map(String::trim).filter(line -> !line.isEmpty()).toList();
        assertFalse(recorded.isEmpty(), "no recorded preview loan datums");

        LoanDatumConverter converter = new LoanDatumConverter();
        int checked = 0;
        for (String hex : recorded) {
            LoanDatum decoded = converter.deserialize(hex);
            assertEquals(hex.toLowerCase(), LoanFixtures.hex(decoded).toLowerCase(),
                    "fixture encoder does not reproduce recorded datum " + checked);
            checked++;
        }
        assertEquals(recorded.size(), checked);
    }

    // ======================================================================================
    // N = 1, ada principal and ada collateral
    // ======================================================================================

    /**
     * The whole §8 anatomy for a single ada/ada liquidation, checked against numbers worked out by
     * hand rather than read back off the builder:
     * <pre>
     *   principal 100 ADA at 10%, one installment  -> remainingDebt      110_000_000
     *   collateral 200 ADA, penalty 50 per mille   -> equity              84_500_000
     *   liquidationFeePerMille 10 on 200 ADA       -> liquidationFee       2_000_000
     *   collateral - equity - fee                  -> collateral output  113_500_000
     * </pre>
     */
    @Test
    void buildsTheFullAnatomyForOneAdaLoan() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        assertEquals(BigInteger.valueOf(110_000_000), scenario.assessment().remainingDebt());
        assertEquals(BigInteger.valueOf(84_500_000), scenario.assessment().equity());
        assertEquals(BigInteger.valueOf(2_000_000), scenario.assessment().liquidationFee());
        assertTrue(scenario.assessment().late());

        Transaction tx = buildIgnoringPositiveEquityVeto(List.of(scenario), Map.of(),
                LiquidateTransactionBuilder.ReferenceScripts.none());

        // Inputs: the loan, its bond, and the bot's ada.
        List<TransactionInput> inputs = sortedInputs(tx);
        assertEquals(3, inputs.size());
        assertTrue(inputs.contains(new TransactionInput(TX_LOAN_A, 0)));
        assertTrue(inputs.contains(new TransactionInput(TX_BOND_A, 0)));
        assertTrue(inputs.contains(new TransactionInput(TX_WALLET, 0)));

        // Reference inputs: the two config UTxOs, nothing else — an ada leg consults no oracle.
        List<TransactionInput> refInputs = sortedRefInputs(tx);
        assertEquals(List.of(new TransactionInput(TX_CONFIG, 0), new TransactionInput(TX_LM_CONFIG, 0)),
                refInputs);

        // Outputs. cardano-client-lib prepends a dummy output at the change address whenever a
        // transaction carries withdrawals (it is what the withdrawn rewards are paid into and what
        // triggers input selection) and appends the change output, so the builder's own three sit
        // between them. Pinned literally: if that ever changes, this test says so loudly rather
        // than the indexes silently drifting.
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        assertEquals(5, outputs.size(), "dummy, bond, collateral, equity, change");
        assertEquals(LoanFixtures.botAddress(), outputs.get(0).getAddress());

        TransactionOutput bondOutput = outputs.get(1);
        assertEquals(scenario.bond().utxo().getAddress(), bondOutput.getAddress());
        assertEquals(amountsByUnit(scenario.bond().utxo().getAmount()),
                amountsByUnit(ValueUtil.toAmountList(bondOutput.getValue())));
        assertEquals(scenario.bond().utxo().getInlineDatum().toLowerCase(),
                bondOutput.getInlineDatum().serializeToHex().toLowerCase(),
                "D6: the bond output must echo the input's datum bytes");

        TransactionOutput collateralOutput = outputs.get(2);
        assertEquals(BigInteger.valueOf(113_500_000), collateralOutput.getValue().getCoin());
        assertEquals(1, ValueUtil.toAmountList(collateralOutput.getValue()).size(),
                "D7: an ada-collateral asset-manager output flattens to exactly one asset");
        assertEquals(expectedAssetManagerDatum(scenario, LiquidationTxEncoder.CLAIMED_COLLATERAL_ACTION_HEX,
                        REGISTRY.getLenderBondPolicyId()),
                collateralOutput.getInlineDatum().serializeToHex());

        TransactionOutput equityOutput = outputs.get(3);
        assertEquals(BigInteger.valueOf(84_500_000), equityOutput.getValue().getCoin());
        assertEquals(1, ValueUtil.toAmountList(equityOutput.getValue()).size());
        assertEquals(expectedAssetManagerDatum(scenario, LiquidationTxEncoder.PARTIAL_LIQUIDATION_ACTION_HEX,
                        REGISTRY.getBorrowerBondPolicyId()),
                equityOutput.getInlineDatum().serializeToHex());

        // The loan NFT is burned.
        assertEquals(1, tx.getBody().getMint().size());
        assertEquals(REGISTRY.getLoanPolicyId(), tx.getBody().getMint().getFirst().getPolicyId());
        assertEquals(BigInteger.ONE.negate(),
                tx.getBody().getMint().getFirst().getAssets().getFirst().getValue());

        // Four withdraw-0 invocations, all at zero.
        List<Withdrawal> withdrawals = tx.getBody().getWithdrawals();
        assertEquals(4, withdrawals.size());
        withdrawals.forEach(withdrawal -> assertEquals(BigInteger.ZERO, withdrawal.getCoin()));

        // Redeemers: one per script input, one mint, four rewards.
        assertEquals(2, redeemers(tx, RedeemerTag.Spend).size());
        assertEquals(1, redeemers(tx, RedeemerTag.Mint).size());
        assertEquals(4, redeemers(tx, RedeemerTag.Reward).size());

        // configRefInputIndex is 0 for the main config; the LenderManager reads the LM config at 1.
        assertEquals(hex(LiquidationTxEncoder.loanWithdrawRedeemer(0)),
                hex(withdrawRedeemer(tx, LoanFixtures.rewardAddress(REGISTRY.getLoanPolicyId()))));
        assertEquals(hex(LiquidationTxEncoder.lenderManagerWithdrawRedeemer(1)),
                hex(withdrawRedeemer(tx,
                        LoanFixtures.rewardAddress(REGISTRY.getLenderManagerWithdrawScriptHash()))),
                "the LenderManager validator is authorised by the LM config, not the main one");
        assertEquals(hex(LiquidationTxEncoder.lmLiquidateWithdrawRedeemer(0, List.of(0L),
                        List.of(LOAN_ID_A), List.of(0L))),
                hex(withdrawRedeemer(tx,
                        LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateActionScriptHash()))));

        // D8 — the claim redeemer carries the assessment's numbers verbatim, and points at the bond
        // echo's real place in the body rather than at the builder's own emission order.
        ClaimData expectedClaim = new ClaimData(LoanFixtures.liquidation(), BigInteger.ONE,
                BigInteger.ZERO, BigInteger.ZERO, scenario.bond().bond().datum().lenderAuth(),
                scenario.assessment().equity(), LOAN_ID_A, scenario.assessment().remainingDebt());
        assertEquals(hex(LiquidationTxEncoder.loanClaimActionWithdrawRedeemer(0, List.of(expectedClaim))),
                hex(withdrawRedeemer(tx,
                        LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()))));

        // With no reference scripts published, every invoked validator travels in the witness set:
        // loan general_spend, LM general_spend, loan, loan_claim_action, lenderManager, lm_liquidate.
        assertEquals(6, tx.getWitnessSet().getPlutusV3Scripts().size());
        assertTrue(tx.getBody().getFee().signum() > 0, "a balanced transaction pays a fee");
    }

    /** The mint redeemer's two unproven fields are pinned so slice C's verdict has something to move. */
    @Test
    void burnsWithTheNonPoolMintRedeemer() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));
        Transaction tx = buildIgnoringPositiveEquityVeto(List.of(scenario), Map.of(),
                LiquidateTransactionBuilder.ReferenceScripts.none());

        assertEquals(hex(LiquidationTxEncoder.loanMintRedeemer(0, false, 0)),
                hex(redeemers(tx, RedeemerTag.Mint).getFirst().getData()));
    }

    /** {@code None} in the datum means the collateral lands at an enterprise asset-manager address. */
    @Test
    void anAbsentStakeCredentialProducesAnEnterpriseAssetManagerAddress() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.noStakeCredential());
        Transaction tx = buildIgnoringPositiveEquityVeto(List.of(scenario), Map.of(),
                LiquidateTransactionBuilder.ReferenceScripts.none());

        assertEquals(LoanFixtures.entAddress(REGISTRY.getAssetManagerSpendScriptHash()),
                tx.getBody().getOutputs().get(2).getAddress());
    }

    // ======================================================================================
    // N = 2, with canonical ordering that is not insertion order
    // ======================================================================================

    /**
     * Two loans whose transaction hashes make the canonical input order interleave loan and bond
     * ({@code aa} loanA, {@code bb} bondB, {@code cc} loanB, {@code dd} bondA), so the pairing
     * {@code lenderBondInputIndexes} has to express is {@code [1, 0]} — not the identity it would be
     * if the builder trusted insertion order.
     */
    @Test
    void resolvesLenderBondInputIndexesFromCanonicalOrderNotInsertionOrder() {
        AdaScenario a = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));
        AdaScenario b = adaScenario(LOAN_ID_B, TX_LOAN_B, TX_BOND_B, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        Transaction tx = buildIgnoringPositiveEquityVeto(List.of(a, b), Map.of(),
                LiquidateTransactionBuilder.ReferenceScripts.none());

        List<TransactionInput> inputs = sortedInputs(tx);
        assertEquals(List.of(new TransactionInput(TX_LOAN_A, 0), new TransactionInput(TX_BOND_B, 0),
                        new TransactionInput(TX_LOAN_B, 0), new TransactionInput(TX_BOND_A, 0),
                        new TransactionInput(TX_WALLET, 0)),
                inputs, "the fixture must actually interleave loans and bonds");

        // loan order is [A, B]; bond order is [B(bb), A(dd)] — so A pairs with bond index 1.
        //
        // assetOutputIndexes is [0, 1] while lenderBondInputIndexes is [1, 0], and the difference is
        // the point of this golden: the bond echoes go out in *bond*-input order, so pairing a loan
        // with its echo needs the permutation, while the collateral outputs go out in *loan*-input
        // order, so loan i's collateral is at filtered position i. An implementation that reused the
        // bond ordering — or simply passed lenderBondInputIndexes twice — would emit [1, 0] here and
        // this assertion is what catches it.
        assertEquals(hex(LiquidationTxEncoder.lmLiquidateWithdrawRedeemer(0, List.of(1L, 0L),
                        List.of(LOAN_ID_A, LOAN_ID_B), List.of(0L, 1L))),
                hex(withdrawRedeemer(tx,
                        LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateActionScriptHash()))));

        // ClaimData is in final loan-input order, each pointing at its own bond output. Loan A's
        // echo is at 2 and loan B's at 1, because the echoes go out in *bond*-input order — see the
        // output assertions below.
        ClaimData claimA = new ClaimData(LoanFixtures.liquidation(), BigInteger.TWO, BigInteger.ZERO,
                BigInteger.ZERO, a.bond().bond().datum().lenderAuth(), a.assessment().equity(), LOAN_ID_A,
                a.assessment().remainingDebt());
        ClaimData claimB = new ClaimData(LoanFixtures.liquidation(), BigInteger.ONE, BigInteger.ZERO,
                BigInteger.ZERO, b.bond().bond().datum().lenderAuth(), b.assessment().equity(), LOAN_ID_B,
                b.assessment().remainingDebt());
        assertEquals(hex(LiquidationTxEncoder.loanClaimActionWithdrawRedeemer(0, List.of(claimA, claimB))),
                hex(withdrawRedeemer(tx,
                        LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()))));

        // Outputs 1 and 2 are the bond echoes in *bond-input* order — B's (bb) then A's (dd), not
        // the loan order the claims are in. lm_liquidate_action reads the echo as
        // `lenderBondOutputs[lenderBondIndex]` with the index it used on the identically filtered
        // input list, so the k-th bond output must echo the k-th bond input. Emitting them in loan
        // order instead is rejected on chain (proved by LiquidateDryEvalTest).
        assertEquals(b.bond().utxo().getInlineDatum().toLowerCase(),
                tx.getBody().getOutputs().get(1).getInlineDatum().serializeToHex().toLowerCase());
        assertEquals(a.bond().utxo().getInlineDatum().toLowerCase(),
                tx.getBody().getOutputs().get(2).getInlineDatum().serializeToHex().toLowerCase());

        assertEquals(2, tx.getBody().getMint().getFirst().getAssets().size(), "both loan NFTs burn");
    }

    // ======================================================================================
    // assetOutputIndexes — the fourth LMLiquidateWithdrawRedeemer field at ff005fb
    // ======================================================================================

    /**
     * {@link LiquidateTransactionBuilder#assetOutputIndexes} unit-tested on a <b>synthetic</b> output
     * list it did not build, arranged so that the three plausible wrong implementations each give a
     * different, wrong answer.
     * <p>
     * Body order is {@code [botAddr, collateralB, botAddr, collateralA]} and loan order is
     * {@code [A, B]}. The asset-manager-filtered list is therefore
     * {@code [collateralB, collateralA]}, so loan A's collateral is at filtered position 1 and loan
     * B's at 0 — the correct answer is <b>{@code [1, 0]}</b>:
     * <ul>
     *   <li>an implementation returning the identity would give {@code [0, 1]};</li>
     *   <li>one returning <em>absolute body</em> indexes, the way
     *       {@code ClaimData.lenderBondOutputIndex} legitimately does, would give {@code [3, 1]} —
     *       this is the confusion the field is most exposed to, because both fields are "an output
     *       index" and only one of them is into a filtered list;</li>
     *   <li>one reusing the bond ordering, or filtering without preserving body order, would give
     *       something else again.</li>
     * </ul>
     * The literal {@code [1, 0]} is asserted rather than a property, so all three fail.
     */
    @Test
    void assetOutputIndexesAreFilteredListPositionsInLoanOrderNotBodyIndexes() {
        String assetManager = LoanFixtures.entAddress(REGISTRY.getAssetManagerSpendScriptHash());
        PlutusData collateralA = syntheticCollateralDatum(LOAN_ID_A);
        PlutusData collateralB = syntheticCollateralDatum(LOAN_ID_B);

        List<TransactionOutput> body = List.of(
                syntheticOutput(LoanFixtures.botAddress(), null),
                syntheticOutput(assetManager, collateralB),
                syntheticOutput(LoanFixtures.botAddress(), null),
                syntheticOutput(assetManager, collateralA));

        assertEquals(List.of(1L, 0L),
                LiquidateTransactionBuilder.assetOutputIndexes(body,
                        REGISTRY.getAssetManagerSpendScriptHash(),
                        List.of(hex(collateralA), hex(collateralB))),
                "the index is a position in the asset-manager-filtered list, in loan-input order");
    }

    /**
     * The two ambiguous shapes, and the duplicate. All three refuse rather than emit a number: an
     * ambiguous or absent match means the builder cannot say which slot a loan's collateral is in, and
     * a duplicate is the double-satisfaction shape {@code lm_liquidate_action}'s
     * {@code list.unique(assetOutputIndexes) == assetOutputIndexes} conjunct exists to reject —
     * refusing is recoverable, emitting costs a fee.
     */
    @Test
    void assetOutputIndexesRefusesAnAmbiguousAbsentOrDuplicatedMatch() {
        String assetManager = LoanFixtures.entAddress(REGISTRY.getAssetManagerSpendScriptHash());
        PlutusData collateralA = syntheticCollateralDatum(LOAN_ID_A);
        PlutusData collateralB = syntheticCollateralDatum(LOAN_ID_B);
        String credential = REGISTRY.getAssetManagerSpendScriptHash();

        // The same datum on two asset-manager outputs: which one is loan A's slot is unanswerable.
        List<TransactionOutput> twice = List.of(
                syntheticOutput(assetManager, collateralA),
                syntheticOutput(assetManager, collateralA));
        assertEquals(LiquidateTransactionBuilder.Refusal.STRUCTURAL_ASSERTION_FAILED,
                assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                        () -> LiquidateTransactionBuilder.assetOutputIndexes(twice, credential,
                                List.of(hex(collateralA)))).getReason());

        // Loan B has no collateral output at all — B's datum sits at the bot address, where the
        // validator's filter would never look.
        List<TransactionOutput> absent = List.of(
                syntheticOutput(assetManager, collateralA),
                syntheticOutput(LoanFixtures.botAddress(), collateralB));
        assertEquals(LiquidateTransactionBuilder.Refusal.STRUCTURAL_ASSERTION_FAILED,
                assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                        () -> LiquidateTransactionBuilder.assetOutputIndexes(absent, credential,
                                List.of(hex(collateralA), hex(collateralB)))).getReason());

        // Two loans whose collateral datums coincide would resolve to one slot twice — refused, never
        // emitted, because list.unique(..) == .. is what the validator would reject it with.
        List<TransactionOutput> oneSlot = List.of(syntheticOutput(assetManager, collateralA));
        assertEquals(LiquidateTransactionBuilder.Refusal.STRUCTURAL_ASSERTION_FAILED,
                assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                        () -> LiquidateTransactionBuilder.assetOutputIndexes(oneSlot, credential,
                                List.of(hex(collateralA), hex(collateralA)))).getReason());
    }

    /**
     * The arity pin, read off the <b>deserialised built transaction</b> rather than off the encoder:
     * an encoder-side assertion would still pass if the builder called the three-argument overload, and
     * a three-field {@code Constr} is what the deployed {@code lm_liquidate_action} destructures as
     * four and dies on with {@code Machine(EmptyList(..))} before doing any work.
     * <p>
     * Deliberately blunt — constructor 0, exactly four fields, field 3 a list of integers — because it
     * is meant to survive every change to what the indexes <em>are</em> and only fail if the redeemer
     * stops having the shape the validator destructures.
     */
    @Test
    void theLmLiquidateRedeemerCarriesFourFieldsEndingInAListOfIntegers() throws Exception {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                100_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));
        assertEquals(BigInteger.ZERO, scenario.assessment().equity(), "a plain build, no V8 seam");

        Transaction built = build(List.of(scenario), Map.of(),
                LiquidateTransactionBuilder.ReferenceScripts.none());
        Transaction tx = Transaction.deserialize(built.serialize());

        PlutusData data = withdrawRedeemer(tx,
                LoanFixtures.rewardAddress(REGISTRY.getLmLiquidateActionScriptHash()));
        ConstrPlutusData constr = assertInstanceOf(ConstrPlutusData.class, data);
        assertEquals(0, constr.getAlternative());

        List<PlutusData> fields = constr.getData().getPlutusDataList();
        assertEquals(4, fields.size(),
                "LMLiquidateWithdrawRedeemer has four fields at ff005fb, assetOutputIndexes last");

        ListPlutusData assetOutputIndexes = assertInstanceOf(ListPlutusData.class, fields.get(3));
        List<PlutusData> items = assetOutputIndexes.getPlutusDataList();
        assertEquals(1, items.size(), "one entry per loan input");
        items.forEach(item -> assertInstanceOf(BigIntPlutusData.class, item));
    }

    /** An output the helper can read: only the address and the inline datum are load-bearing. */
    private static TransactionOutput syntheticOutput(String address, PlutusData inlineDatum) {
        return TransactionOutput.builder()
                .address(address)
                .value(Value.builder().coin(BigInteger.valueOf(2_000_000L)).build())
                .inlineDatum(inlineDatum)
                .build();
    }

    /**
     * A claimed-collateral datum for {@code loanId}, distinct per loan because it carries the loan's
     * own id. Built through {@link LiquidationTxEncoder} because the helper under test matches on
     * exactly these bytes; nothing about the encoder is being pinned here.
     */
    private static PlutusData syntheticCollateralDatum(String loanId) {
        return LiquidationTxEncoder.assetManagerDatumWithToken(new AssetManagerDatumWithToken(
                TX_LOAN_A, 0, LiquidationTxEncoder.CLAIMED_COLLATERAL_ACTION_HEX,
                new AssetType(REGISTRY.getLenderBondPolicyId(), loanId)));
    }

    // ======================================================================================
    // Token collateral priced by a Charli3 feed
    // ======================================================================================

    /**
     * A token-collateral loan: one oracle withdrawal, the oracle NFT and the Charli3 provider both
     * as reference inputs, {@code provider_ref_input_index} resolved against the final reference
     * order, and D7's two-asset output shape.
     */
    @Test
    void buildsATokenCollateralLiquidationWithACharli3Oracle() {
        TokenScenario scenario = tokenScenario(LoanFixtures.inlineKeyStakeCredential(STAKE_KEY),
                VALID_FROM - 60_000L, VALID_FROM + 600_000L);

        assertEquals(BigInteger.valueOf(55_000_000), scenario.assessment().remainingDebt());
        assertEquals(BigInteger.valueOf(422_500), scenario.assessment().equity());
        assertEquals(BigInteger.valueOf(10_000), scenario.assessment().liquidationFee());

        Transaction tx = buildIgnoringPositiveEquityVeto(List.of(scenario.scenario()),
                Map.of(ORACLE_TOKEN.toUnit(), scenario.oracle()),
                LiquidateTransactionBuilder.ReferenceScripts.none());

        // Reference inputs, canonically ordered: oracle NFT, oracle script, charli3 provider, then
        // the two configs. The oracle's index is 0 and the provider's is 2 — neither is guessable
        // from the order they were added in.
        List<TransactionInput> refInputs = sortedRefInputs(tx);
        assertEquals(List.of(new TransactionInput(TX_ORACLE_NFT, 0),
                        new TransactionInput(TX_ORACLE_SCRIPT, 0),
                        new TransactionInput(TX_CHARLI3_PROVIDER, 0),
                        new TransactionInput(TX_CONFIG, 0),
                        new TransactionInput(TX_LM_CONFIG, 0)),
                refInputs);

        // Five withdrawals now: the four v4 scripts plus the oracle.
        assertEquals(5, tx.getBody().getWithdrawals().size());
        assertEquals(hex(LiquidationTxEncoder.oracleRedeemer(scenario.oracle().feed(), 2, List.of())),
                hex(withdrawRedeemer(tx, scenario.oracle().rewardAddress())),
                "provider_ref_input_index must be the charli3 provider's place in the sorted set");

        // The claim redeemer points the collateral leg at the oracle reference input and leaves the
        // ada principal leg at zero.
        ClaimData claim = new ClaimData(LoanFixtures.liquidation(), BigInteger.ONE, BigInteger.ZERO,
                BigInteger.ZERO, scenario.scenario().bond().bond().datum().lenderAuth(),
                scenario.assessment().equity(), LOAN_ID_A, scenario.assessment().remainingDebt());
        assertEquals(hex(LiquidationTxEncoder.loanClaimActionWithdrawRedeemer(3, List.of(claim))),
                hex(withdrawRedeemer(tx,
                        LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()))));

        // D7 — token collateral flattens to exactly two assets, and the quantity is exact.
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        TransactionOutput collateralOutput = outputs.get(2);
        assertEquals(2, ValueUtil.toAmountList(collateralOutput.getValue()).size());
        assertEquals(BigInteger.valueOf(567_500), quantityOf(collateralOutput, COLLATERAL_TOKEN));
        assertTrue(collateralOutput.getValue().getCoin().signum() > 0, "min-ada rider");

        TransactionOutput equityOutput = outputs.get(3);
        assertEquals(2, ValueUtil.toAmountList(equityOutput.getValue()).size());
        assertEquals(BigInteger.valueOf(422_500), quantityOf(equityOutput, COLLATERAL_TOKEN));

        // The liquidation fee is left to the bot as change — the whole point of the batch.
        BigInteger feeSlice = outputs.stream()
                .filter(output -> output.getAddress().equals(LoanFixtures.botAddress()))
                .map(output -> quantityOf(output, COLLATERAL_TOKEN))
                .reduce(BigInteger.ZERO, BigInteger::add);
        assertEquals(BigInteger.valueOf(10_000), feeSlice);
    }

    // ======================================================================================
    // Reference scripts
    // ======================================================================================

    /**
     * When the caller knows where the scripts are published, they are read from instead of being
     * carried: the witness set must come back empty, or the ledger rejects the transaction with
     * {@code ExtraneousScriptWitnessesUTXOW}.
     */
    @Test
    void readsPublishedScriptsFromReferenceInputsInsteadOfAttachingThem() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        LiquidateTransactionBuilder.ReferenceScripts published =
                new LiquidateTransactionBuilder.ReferenceScripts(
                        new TransactionInput(TX_REF_SCRIPTS, 0),
                        new TransactionInput(TX_REF_SCRIPTS, 1),
                        new TransactionInput(TX_REF_SCRIPTS, 2),
                        new TransactionInput(TX_REF_SCRIPTS, 3),
                        new TransactionInput(TX_REF_SCRIPTS, 4),
                        new TransactionInput(TX_REF_SCRIPTS, 5),
                        null);

        Transaction tx = buildIgnoringPositiveEquityVeto(List.of(scenario), Map.of(), published);

        assertTrue(tx.getWitnessSet().getPlutusV3Scripts().isEmpty(),
                "a published script must not also travel in the witness set");
        List<TransactionInput> refInputs = sortedRefInputs(tx);
        IntStream.range(0, 6).forEach(index ->
                assertTrue(refInputs.contains(new TransactionInput(TX_REF_SCRIPTS, index)),
                        "reference script " + index + " is missing"));
    }

    /**
     * <b>The honest size bound for a liquidation, measured on the heaviest shape the bot builds.</b>
     * <p>
     * {@code LiquidateDryEvalTest} measures the ada/ada shape, which is the cheapest one: no oracle.
     * A token-collateral liquidation is what the bound has to be read off, because it carries
     * strictly more than the ada one — the oracle NFT, the oracle reference script and the Charli3
     * provider as three extra reference inputs, a fifth withdrawal for the oracle, its extra
     * redeemer, and two-asset outputs instead of ada-only ones.
     * <p>
     * The scripts travel by reference, using the six coordinates FluidTokens really published on
     * preview ({@link #PUBLISHED_PREVIEW}); the size is
     * {@link Transaction#serialize()}{@code .length} on the built transaction, so it is the CBOR the
     * ledger would count rather than an estimate or a figure carried over from an earlier run. The
     * empty-witness-set assertion is what makes it a measurement of the reference-script shape at
     * all.
     * <p>
     * The scenario has a positive equity, so it is built through the V8 seam for the reason
     * {@link #buildIgnoringPositiveEquityVeto} gives: the transaction is the structurally correct
     * one, and its size is the size a submittable liquidation of this shape would have.
     */
    @Test
    void aTokenCollateralLiquidationFitsUnderMaxTxSizeWithThePublishedReferenceScripts() {
        TokenScenario scenario = tokenScenario(LoanFixtures.inlineKeyStakeCredential(STAKE_KEY),
                VALID_FROM - 60_000L, VALID_FROM + 600_000L);

        Transaction tx = buildIgnoringPositiveEquityVeto(List.of(scenario.scenario()),
                Map.of(ORACLE_TOKEN.toUnit(), scenario.oracle()), PUBLISHED_PREVIEW);

        assertTrue(tx.getWitnessSet().getPlutusV3Scripts().isEmpty(),
                "a published script must be read from its reference input, not carried");
        List<TransactionInput> refInputs = sortedRefInputs(tx);
        for (TransactionInput coordinate : publishedCoordinates()) {
            assertTrue(refInputs.contains(coordinate),
                    "reference script " + coordinate.getTransactionId() + "#" + coordinate.getIndex()
                            + " is missing from the reference inputs");
        }
        // The extra weight this shape carries over the ada/ada one, asserted rather than assumed.
        // What this pin actually defends is OVER-inclusion: an extra reference input is caught here
        // and nowhere else. It does NOT catch a fixture that quietly loses its oracle — dropping the
        // charli3 provider trips the CHARLIE_PROVIDER_REFERENCE_INPUT_MISSING veto inside the builder
        // long before this line is reached. Both directions are covered; they are just covered by
        // different layers, and the comment used to claim the wrong one.
        assertEquals(11, refInputs.size(),
                "the six v4 reference scripts, the oracle NFT, the oracle's own reference script, the "
                        + "charli3 provider, and the two configs");
        assertEquals(5, tx.getBody().getWithdrawals().size(), "the four v4 scripts plus the oracle");

        int size = serializedSize(tx);
        log.info("Liquidate N=1 token collateral + charli3 oracle with published reference scripts: "
                + "{} bytes (maxTxSize {})", size, MAX_TX_SIZE);
        assertTrue(size < MAX_TX_SIZE,
                "the oracle-bearing liquidation is " + size + " bytes, maxTxSize is " + MAX_TX_SIZE);
    }

    /** The six coordinates of {@link #PUBLISHED_PREVIEW}, in the record's own order. */
    private static List<TransactionInput> publishedCoordinates() {
        return List.of(PUBLISHED_PREVIEW.loan(), PUBLISHED_PREVIEW.loanSpend(),
                PUBLISHED_PREVIEW.lenderManager(), PUBLISHED_PREVIEW.lenderManagerSpend(),
                PUBLISHED_PREVIEW.loanClaimAction(), PUBLISHED_PREVIEW.lmLiquidateAction());
    }

    /** The CBOR the ledger would count, measured rather than estimated. */
    private static int serializedSize(Transaction tx) {
        try {
            return tx.serialize().length;
        } catch (Exception e) {
            throw new AssertionError("cannot serialize the built transaction", e);
        }
    }

    // ======================================================================================
    // V1 — the assessment must be buildable
    // ======================================================================================

    /**
     * The excluded assessment carries every number a buildable one would, so V1 is the only thing
     * standing between it and a finished transaction. An assessment with the null numbers
     * {@link LiquidationAssessment#excluded} really produces would make this test pass on a
     * {@code NullPointerException} from somewhere downstream, which would keep passing if V1 were
     * deleted and stop testing V1 the moment those fields were ever populated.
     */
    @Test
    void v1RefusesAnExcludedAssessment() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));
        AdaScenario excluded = scenario.withAssessment(LoanFixtures.excludedButFullyNumbered(
                scenario.assessment(), LiquidationExclusion.NOT_LIQUIDATABLE));

        assertNotNull(excluded.assessment().remainingDebt(), "the fixture must survive past V1");
        assertNotNull(excluded.assessment().equity());
        assertNotNull(excluded.assessment().liquidationFee());
        assertEquals(LiquidateTransactionBuilder.Refusal.NOT_BUILDABLE, refusal(excluded));
    }

    // ======================================================================================
    // V2 — the arithmetic the chain has to satisfy
    // ======================================================================================

    @Test
    void v2RefusesWhenCollateralCannotCoverEquityAndFee() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));
        AdaScenario broken = scenario.withAssessment(LoanFixtures.withNumbers(scenario.assessment(),
                scenario.assessment().remainingDebt(), BigInteger.valueOf(199_000_000),
                BigInteger.valueOf(2_000_000)));

        assertEquals(LiquidateTransactionBuilder.Refusal.COLLATERAL_CANNOT_COVER_EQUITY_AND_FEE,
                refusal(broken));
    }

    @Test
    void v2RefusesNegativeEquity() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));
        AdaScenario broken = scenario.withAssessment(LoanFixtures.withNumbers(scenario.assessment(),
                scenario.assessment().remainingDebt(), BigInteger.valueOf(-1),
                scenario.assessment().liquidationFee()));

        assertEquals(LiquidateTransactionBuilder.Refusal.NEGATIVE_EQUITY, refusal(broken));
    }

    @Test
    void v2RefusesANonPositiveRemainingDebt() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));
        AdaScenario broken = scenario.withAssessment(LoanFixtures.withNumbers(scenario.assessment(),
                BigInteger.ZERO, scenario.assessment().equity(), scenario.assessment().liquidationFee()));

        assertEquals(LiquidateTransactionBuilder.Refusal.NON_POSITIVE_REMAINING_DEBT, refusal(broken));
    }

    /**
     * {@code liquidationFeePerMille} is a lender-authored bond field with no on-chain
     * non-negativity constraint, so a negative fee is reachable from real chain data — and it does
     * not shrink the bot's take, it inflates the asset-manager payout past the collateral that is
     * actually in the loan UTxO.
     */
    @Test
    void v2RefusesANegativeLiquidationFee() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN.negate(), LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        assertEquals(BigInteger.valueOf(-2_000_000), scenario.assessment().liquidationFee(),
                "the fixture's fee must really be negative, and reproduce");
        assertEquals(LiquidateTransactionBuilder.Refusal.NEGATIVE_LIQUIDATION_FEE, refusal(scenario));
    }

    // ======================================================================================
    // V3 — feed windows, transaction-grade
    // ======================================================================================

    @Test
    void v3RefusesAFeedThatDoesNotCoverTheWholeTransactionWindow() {
        // The feed is live at validFrom — usableAt would pass — but expires inside the window.
        TokenScenario scenario = tokenScenario(LoanFixtures.inlineKeyStakeCredential(STAKE_KEY),
                VALID_FROM - 60_000L, VALID_FROM + 60_000L);

        assertEquals(LiquidateTransactionBuilder.Refusal.ORACLE_FEED_NOT_USABLE_OVER_WINDOW,
                refusal(List.of(scenario.scenario()), Map.of(ORACLE_TOKEN.toUnit(), scenario.oracle()),
                        MARGIN));
    }

    @Test
    void v3RefusesTooLittleRemainingFeedWindow() {
        // Covers the window, but only 60s of feed left afterwards against a 300s margin.
        TokenScenario scenario = tokenScenario(LoanFixtures.inlineKeyStakeCredential(STAKE_KEY),
                VALID_FROM - 60_000L, VALID_TO + 60_000L);

        assertEquals(LiquidateTransactionBuilder.Refusal.ORACLE_WINDOW_MARGIN_TOO_SMALL,
                refusal(List.of(scenario.scenario()), Map.of(ORACLE_TOKEN.toUnit(), scenario.oracle()),
                        MARGIN));
    }

    // ======================================================================================
    // V4 — the health guards: debt and equity at validFrom, liquidatability over the window
    // ======================================================================================

    /**
     * A drifting debt is <b>buildable</b>, and this test used to assert the opposite.
     *
     * <h3>What changed and why the old assertion was wrong</h3>
     * V4 used to recompute the debt at both ends of the validity interval and refuse when the two
     * differed, because "the chain's evaluation point is unknown". It is not unknown:
     * {@code loan_claim_action.ak:212-222} ({@code ff005fb}) computes
     * {@code get_remaining_debt(.., validFrom - datum.lendDate)}, and {@code validFrom} is the lower
     * bound of the validity interval <em>this transaction sets</em> ({@code loan_claim_action.ak:86}).
     * One figure, computed at an instant we choose, matches exactly at any window length. The old
     * rule therefore refused transactions the chain would have accepted — every loan with a non-zero
     * perpetual {@code interestRate}.
     *
     * <h3>The fixture drifts for real</h3>
     * A perpetual loan at 10% over a 50-minute window. The drift is asserted rather than assumed: a
     * fixture whose debt happened not to move would make this test vacuous, and would have passed
     * under the old rule too.
     */
    @Test
    void v4AcceptsADebtThatDriftsInsideTheValidityWindowBecauseTheChainReadsItAtValidFrom() {
        long validTo = VALID_FROM + 3_000_000L;
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(),
                new RepaymentMode.PerpetualLoan(BigInteger.ZERO, BigInteger.ZERO), false);
        AdaScenario scenario = adaScenarioFrom(datum, LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L,
                BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        assertNotEquals(LoanFinance.remainingDebt(datum, VALID_FROM),
                LoanFinance.remainingDebt(datum, validTo),
                "the fixture must really drift, or this test proves nothing about the narrowing");
        assertEquals(LoanFinance.remainingDebt(datum, VALID_FROM), scenario.assessment().remainingDebt(),
                "the assessment is the one the scanner would take, at validFrom");
        assertEquals(BigInteger.ZERO, scenario.assessment().equity(),
                "zero equity keeps V8 out of the way, so the outcome is V4's alone");

        Transaction tx = builder(List.of(scenario), Map.of()).build(
                request(List.of(scenario), Map.of(), WALLET_UTXO, MARGIN, VALID_FROM, validTo,
                        LiquidateTransactionBuilder.ReferenceScripts.none()));
        assertNotNull(tx, "a drifting debt assessed at validFrom is buildable");
    }

    /**
     * The original fixture of the test above, kept verbatim — 200 ADA of collateral against 100 ADA
     * of drifting perpetual principal — and re-pinned.
     * <p>
     * It used to refuse {@code REMAINING_DEBT_NOT_INVARIANT}. Now the debt reproduces at
     * {@code validFrom}, and so does the equity: {@code get_equity_in_collateral_currency}
     * ({@code lib/fluidtokens/finance.ak:381}) takes no time argument, so its only time-varying input
     * is the {@code validFrom} debt it is handed. Both V4 legs pass, and what refuses instead is V8 —
     * the positive-equity layout gap, which is a statement about the deployed validators and has
     * nothing to do with the window. That the reason moved from a V4 refusal to a V8 one is the
     * clearest single demonstration that <em>both</em> narrowings took effect on the same fixture.
     */
    @Test
    void v4NoLongerRefusesTheDriftingDebtFixtureAndV8IsWhatStopsIt() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(),
                new RepaymentMode.PerpetualLoan(BigInteger.ZERO, BigInteger.ZERO), false);
        AdaScenario scenario = adaScenarioFrom(datum, LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 200_000_000L,
                BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        assertTrue(scenario.assessment().equity().signum() > 0,
                "the fixture must carry a positive — and therefore drifting — equity");
        assertEquals(LiquidateTransactionBuilder.Refusal.POSITIVE_EQUITY_UNSUPPORTED,
                refusal(List.of(scenario), Map.of(), MARGIN, VALID_FROM, VALID_FROM + 3_000_000L));
    }

    /**
     * The narrowing is anchored at {@code validFrom}, not merely loosened: an assessment carrying the
     * debt at {@code validTo} — the shape a scan taken one window later would produce — still
     * refuses. Deleting the comparison altogether, or moving it to "any point in the window", would
     * make this test pass silently.
     */
    @Test
    void v4RefusesADebtTakenAtValidToRatherThanAtValidFrom() {
        long validTo = VALID_FROM + 3_000_000L;
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(),
                new RepaymentMode.PerpetualLoan(BigInteger.ZERO, BigInteger.ZERO), false);
        AdaScenario scenario = adaScenarioFrom(datum, LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L,
                BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        AdaScenario stale = scenario.withAssessment(LoanFixtures.withNumbers(scenario.assessment(),
                LoanFinance.remainingDebt(datum, validTo), scenario.assessment().equity(),
                scenario.assessment().liquidationFee()));

        assertEquals(LiquidateTransactionBuilder.Refusal.REMAINING_DEBT_NOT_INVARIANT,
                refusal(List.of(stale), Map.of(), MARGIN, VALID_FROM, validTo));
    }

    /**
     * Same anchoring for {@code equity}: the equity the loan produces from the {@code validTo} debt is
     * refused, even though it is a perfectly self-consistent number at the other end of the window.
     * <p>
     * The fixture has a positive equity so that the figure can drift at all, which means V8 would
     * refuse it too — but V4 runs first, so the reason asserted here is V4's, and it is the reason
     * that would disappear if the equity comparison were dropped rather than narrowed.
     */
    @Test
    void v4RefusesAnEquityTakenAtValidToRatherThanAtValidFrom() {
        long validTo = VALID_FROM + 3_000_000L;
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(),
                new RepaymentMode.PerpetualLoan(BigInteger.ZERO, BigInteger.ZERO), false);
        AdaScenario scenario = adaScenarioFrom(datum, LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 200_000_000L,
                BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        BigInteger equityAtValidTo = LoanFinance.redeemerEquity(LoanFixtures.liquidation(),
                Rational.fromInt(BigInteger.valueOf(200_000_000)),
                Rational.fromInt(LoanFinance.remainingDebt(datum, validTo)),
                OraclePriceFeed.unit(), OraclePriceFeed.unit());
        assertNotEquals(scenario.assessment().equity(), equityAtValidTo,
                "the fixture's equity must really drift across the window");

        AdaScenario stale = scenario.withAssessment(LoanFixtures.withNumbers(scenario.assessment(),
                scenario.assessment().remainingDebt(), equityAtValidTo,
                scenario.assessment().liquidationFee()));

        assertEquals(LiquidateTransactionBuilder.Refusal.EQUITY_NOT_REPRODUCIBLE,
                refusal(List.of(stale), Map.of(), MARGIN, VALID_FROM, validTo));
    }

    /**
     * {@code NOT_LIQUIDATABLE_OVER_WINDOW} is <b>still checked at both ends</b>, and this is the only
     * fixture shape under which that is observable.
     *
     * <h3>Why the fixture is strange, and why it has to be</h3>
     * {@code late || can_liquidate} is monotone non-decreasing in time for every ordinary loan: a
     * perpetual debt only grows ({@code lib/fluidtokens/finance.ak:209-247}), an installment debt does
     * not move at all, and {@code is_repayment_late} only ever flips false-to-true. Under monotonicity
     * "liquidatable at {@code validFrom}" implies "liquidatable at {@code validTo}", so the second
     * iteration of the loop can never be the one that refuses, and a window check is
     * indistinguishable from a {@code validFrom}-only check.
     * <p>
     * A <em>negative</em> {@code interestRate} breaks the monotonicity — the datum field is a plain
     * {@code Int} and nothing on chain constrains its sign — so the debt shrinks with time and the
     * loan heals during the window. That is the mutant: liquidatable at {@code validFrom}
     * (ltv 1.30 against a 1.20 threshold), healthy at {@code validTo} (ltv 1.10). A narrowing of this
     * leg to {@code validFrom} would build it; the unchanged window check refuses it.
     * <pre>
     *   principal 200 ADA, interestRate -10_512_000 (c = -1051.2), perpetual, m = 0
     *   lendDate  NOW - 10_500_000 ms (2.9166 h)   collateral 100 ADA   ltv 1200/1000
     *   debt at validFrom  200e6 * (1 - 0.35) = 130_000_000   -> ltv 1.30 > 1.20  liquidatable
     *   debt at validTo    200e6 * (1 - 0.45) = 110_000_000   -> ltv 1.10 &lt; 1.20  healthy
     * </pre>
     */
    @Test
    void v4StillRefusesALoanThatHealsBeforeValidTo() {
        long validTo = VALID_FROM + 3_000_000L;
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(200_000_000),
                BigInteger.valueOf(-10_512_000), LoanFixtures.adaCollateral(), NOW - 10_500_000L,
                new LiquidationMode.Liquidation(
                        BigInteger.valueOf(1200), BigInteger.valueOf(1000), BigInteger.valueOf(50), false),
                new RepaymentMode.PerpetualLoan(BigInteger.ZERO, BigInteger.ZERO), false);
        AdaScenario scenario = adaScenarioFrom(datum, LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L,
                BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        // The fixture is only a proof if the two ends really disagree — pinned here off LoanFinance,
        // which is the same port the builder itself calls.
        assertEquals(BigInteger.valueOf(130_000_000), LoanFinance.remainingDebt(datum, VALID_FROM));
        assertEquals(BigInteger.valueOf(110_000_000), LoanFinance.remainingDebt(datum, validTo));
        assertFalse(LoanFinance.isRepaymentLate(datum, validTo), "lateness would mask the ltv leg");
        assertTrue(liquidatableAt(datum, VALID_FROM), "liquidatable at validFrom");
        assertFalse(liquidatableAt(datum, validTo), "and healthy again by validTo");
        assertEquals(BigInteger.ZERO, scenario.assessment().equity(),
                "zero equity keeps V8 from masking the reason under test");

        assertEquals(LiquidateTransactionBuilder.Refusal.NOT_LIQUIDATABLE_OVER_WINDOW,
                refusal(List.of(scenario), Map.of(), MARGIN, VALID_FROM, validTo));
    }

    /** D9's {@code late || can_liquidate} for the ada/ada fixtures, at 100 ADA of collateral. */
    private static boolean liquidatableAt(LoanDatum datum, long at) {
        var liquidation = (LiquidationMode.Liquidation) datum.liquidationMode();
        return LoanFinance.isRepaymentLate(datum, at)
                || LoanFinance.canLiquidate(Rational.fromInt(LoanFinance.remainingDebt(datum, at)),
                Rational.fromInt(BigInteger.valueOf(100_000_000)),
                LoanFinance.liquidationLtv(liquidation), OraclePriceFeed.unit(), OraclePriceFeed.unit());
    }

    /**
     * The assessment is an <em>input</em> to this builder, not something it can trust. This is the
     * exact shape of the attack: a buildable assessment whose {@code liquidationFee} claims
     * 50 ADA where the bond's 10-per-mille rate on 200 ADA of collateral is 2 ADA. The inflated fee
     * is subtracted from the asset-manager payout, so the transaction would pay 65.5 ADA where
     * {@code lm_liquidate_action} recomputes the fee itself and demands at least 113.5 — a
     * transaction that is guaranteed to die in script evaluation with the fee already spent, and to
     * be rebuilt on the next scan, and the next.
     * <p>
     * Note the divergence is between the assessment and a <em>recomputation</em>, not between two
     * fixtures: this is what a stale scan, a tampered record, or a scanner bug all look like from
     * here, and the answer to all three is to refuse rather than to quietly substitute the
     * recomputed number (D8 — the redeemer carries the assessment's values or nothing does).
     */
    @Test
    void v4RefusesALiquidationFeeThatTheBondDoesNotProduce() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));
        assertEquals(BigInteger.valueOf(2_000_000), scenario.assessment().liquidationFee(),
                "the true fee, for contrast");

        AdaScenario inflated = scenario.withAssessment(LoanFixtures.withNumbers(scenario.assessment(),
                scenario.assessment().remainingDebt(), scenario.assessment().equity(),
                BigInteger.valueOf(50_000_000)));

        // The inflated fee still leaves a non-negative payout, so V2 lets it through: only a
        // recomputation catches it.
        assertTrue(BigInteger.valueOf(200_000_000)
                        .subtract(inflated.assessment().equity())
                        .subtract(inflated.assessment().liquidationFee()).signum() >= 0,
                "the fixture must get past V2, or it proves nothing about the recomputation");
        assertEquals(LiquidateTransactionBuilder.Refusal.LIQUIDATION_FEE_NOT_REPRODUCIBLE,
                refusal(inflated));
    }

    /**
     * Same discipline for {@code equity}, which unlike the fee does reach a redeemer field: an
     * equity that the loan datum and the two feeds do not reproduce is refused rather than
     * corrected.
     */
    @Test
    void v4RefusesAnEquityThatTheLoanDoesNotProduce() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));
        AdaScenario wrong = scenario.withAssessment(LoanFixtures.withNumbers(scenario.assessment(),
                scenario.assessment().remainingDebt(),
                scenario.assessment().equity().add(BigInteger.ONE),
                scenario.assessment().liquidationFee()));

        assertEquals(LiquidateTransactionBuilder.Refusal.EQUITY_NOT_REPRODUCIBLE, refusal(wrong));
    }

    /** A healthy, on-time loan: D9's {@code late || can_liquidate} is false, so nothing may be built. */
    @Test
    void v4RefusesALoanThatIsNeitherLateNorOverItsLiquidationLtv() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), NOW);

        assertFalse(scenario.assessment().late(), "the fixture must not be late");
        assertEquals(LiquidateTransactionBuilder.Refusal.NOT_LIQUIDATABLE_OVER_WINDOW, refusal(scenario));
    }

    // ======================================================================================
    // V5 — structural assertions on the finished body
    // ======================================================================================

    /**
     * An ada payout below the min-ada floor gets silently topped up by cardano-client-lib, which
     * would quietly overpay the asset manager out of the bot's fee slice. The post-assembly check
     * catches it on the finished body.
     */
    @Test
    void v5RefusesWhenTheCollateralOutputIsToppedUpToMinAda() {
        // debt 100 lovelace, no fee -> the collateral output would be 105 lovelace, below min-ada.
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100L, 0L,
                200_000_000L, BigInteger.ZERO, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        assertEquals(BigInteger.valueOf(100), scenario.assessment().remainingDebt());
        assertEquals(BigInteger.valueOf(199_999_895), scenario.assessment().equity());
        assertEquals(BigInteger.ZERO, scenario.assessment().liquidationFee());

        assertEquals(LiquidateTransactionBuilder.Refusal.STRUCTURAL_ASSERTION_FAILED,
                refusalIgnoringPositiveEquityVeto(scenario));
    }

    // ======================================================================================
    // V6 — the modelling gaps
    // ======================================================================================

    /**
     * Everything about this entry is complete except the provider reference input, so V6 is the only
     * thing stopping it: with the veto gone the builder reaches index resolution and refuses for a
     * <em>different</em> reason, which fails the assertion below on the reason rather than on an
     * incidental {@code NullPointerException}.
     */
    @Test
    void v6RefusesACharli3FeedWithNoProviderReferenceInput() {
        TokenScenario scenario = tokenScenario(LoanFixtures.inlineKeyStakeCredential(STAKE_KEY),
                VALID_FROM - 60_000L, VALID_FROM + 600_000L);
        OracleEntry withoutProvider = new OracleEntry(scenario.oracle().token(),
                scenario.oracle().oracleToken(), scenario.oracle().rewardAddress(),
                scenario.oracle().withdrawCredentialHash(), scenario.oracle().referenceInput(),
                scenario.oracle().referenceScript(), List.of(), 0, scenario.oracle().feed(),
                List.of(), null);

        assertEquals(LiquidateTransactionBuilder.Refusal.CHARLIE_PROVIDER_REFERENCE_INPUT_MISSING,
                refusal(List.of(scenario.scenario()), Map.of(ORACLE_TOKEN.toUnit(), withoutProvider),
                        MARGIN));
    }

    @Test
    void v6RefusesAPooledFeed() {
        assertEquals(LiquidateTransactionBuilder.Refusal.UNSUPPORTED_ORACLE_VARIANT,
                refusalForVariant(OraclePriceFeed.Variant.POOLED));
    }

    @Test
    void v6RefusesAnOrcfaxFeed() {
        assertEquals(LiquidateTransactionBuilder.Refusal.UNSUPPORTED_ORACLE_VARIANT,
                refusalForVariant(OraclePriceFeed.Variant.PRICE_DATA_ORCFAX));
    }

    private static LiquidateTransactionBuilder.Refusal refusalForVariant(OraclePriceFeed.Variant variant) {
        TokenScenario scenario = tokenScenario(LoanFixtures.inlineKeyStakeCredential(STAKE_KEY),
                VALID_FROM - 60_000L, VALID_FROM + 600_000L);
        OraclePriceFeed feed = new OraclePriceFeed(variant, COLLATERAL_TOKEN,
                BigInteger.valueOf(100), BigInteger.ONE, VALID_FROM - 60_000L, VALID_FROM + 600_000L);
        OracleEntry entry = new OracleEntry(scenario.oracle().token(), scenario.oracle().oracleToken(),
                scenario.oracle().rewardAddress(), scenario.oracle().withdrawCredentialHash(),
                scenario.oracle().referenceInput(), scenario.oracle().referenceScript(), List.of(), 0,
                feed, List.of(), scenario.oracle().charlieProviderReferenceInput());

        return refusal(List.of(scenario.scenario()), Map.of(ORACLE_TOKEN.toUnit(), entry), MARGIN);
    }

    @Test
    void v6RefusesAPointerStakeCredential() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.pointerStakeCredential());

        assertEquals(LiquidateTransactionBuilder.Refusal.POINTER_STAKE_CREDENTIAL, refusal(scenario));
    }

    @Test
    void v6RefusesRepaymentReceiptsTogetherWithEquity() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), true);
        AdaScenario scenario = adaScenarioFrom(datum, LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 200_000_000L,
                BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        assertTrue(scenario.assessment().equity().signum() > 0);
        assertEquals(LiquidateTransactionBuilder.Refusal.REPAYMENT_RECEIPTS_WITH_EQUITY, refusal(scenario));
    }

    // ======================================================================================
    // V7 — the two datum fields the plain Liquidate path requires to be false
    // ======================================================================================

    /**
     * Why all three fixtures below are 100 ADA against 100 ADA rather than the 200 ADA the rest of
     * this file uses: at 100 ADA the collateral is worth less than the 110 ADA of debt plus its 5%
     * partial-liquidation penalty, so {@code equity} clamps to zero and V8 is not in the way. With V7
     * disabled such a batch builds all the way through, and the test fails because nothing was thrown
     * — on the missing refusal itself, not on V8 firing in V7's place.
     */
    private static final String ZERO_EQUITY_FIXTURE =
            "the fixture must have zero equity, or V8 masks the veto under test";

    /**
     * Findings §7.1 D2. {@code lm_liquidate_action.ak:122} is a hard
     * {@code expect equityInPrincipalCurrency == False}: no output layout satisfies a loan whose
     * {@code Liquidation} sets it. The scanner excludes these already
     * ({@code LiquidationExclusion.EQUITY_IN_PRINCIPAL_CURRENCY}); this is the builder refusing to
     * take the scanner's word for it.
     */
    @Test
    void v7RefusesEquityDenominatedInThePrincipalCurrency() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidationInPrincipalCurrency(),
                new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        AdaScenario scenario = adaScenarioFrom(datum, LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L,
                BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        assertEquals(BigInteger.ZERO, scenario.assessment().equity(), ZERO_EQUITY_FIXTURE);
        assertEquals(LiquidateTransactionBuilder.Refusal.EQUITY_IN_PRINCIPAL_CURRENCY, refusal(scenario));
    }

    /**
     * Findings §7.5. {@code lm_liquidate_action.ak:143} makes
     * {@code shouldLiquidationConvertToPrincipal == False} a conjunct of the check the plain
     * {@code Liquidate} path runs, so a bond that demands conversion belongs to a different action
     * entirely. The bond datum is identical to every other fixture's except for that one flag.
     */
    @Test
    void v7RefusesABondThatRequiresConvertingProceedsToPrincipal() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        AdaScenario scenario = adaScenarioFrom(datum, LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L,
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.TEN,
                        LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), AssetType.ada()));

        assertTrue(scenario.bond().bond().datum().shouldLiquidationConvertToPrincipal(),
                "the fixture bond must really demand conversion");
        assertEquals(BigInteger.ZERO, scenario.assessment().equity(), ZERO_EQUITY_FIXTURE);
        assertEquals(LiquidateTransactionBuilder.Refusal.CONVERSION_TO_PRINCIPAL_REQUIRED,
                refusal(scenario));
    }

    // ======================================================================================
    // V8 — positive equity is unsatisfiable on the deployed validators
    // ======================================================================================

    /**
     * The 200-ADA-collateral scenario the anatomy tests above build — 84.5 ADA of borrower equity —
     * is refused outright on the public entry point.
     * <p>
     * {@code lm_liquidate_action.ak:87-91} reaches the {@code get_outputs_to_smart_credential(..)}
     * list through {@code assetOutputIndexes[index]} while {@code loan_claim_action.ak:275-284} still
     * indexes it with the bare loan index, and the two demand mutually exclusive datums
     * ({@code action_claimed_collateral} against {@code action_partial_liquidation_compensation}). The
     * builder emits identity {@code assetOutputIndexes}, which puts both on the same slot;
     * {@code LiquidateDryEvalTest} runs both layouts <em>the builder can emit</em> against the deployed
     * scripts and watches each validator refuse the other's. Nothing here re-derives that — this test
     * only pins that the builder refuses instead of building.
     * <p>
     * The refusal is <em>deployment-specific</em> and scoped to what this builder emits — whether some
     * other layout satisfies both validators is untested. The same batch built through the seam is
     * still a well-formed transaction, which is why the anatomy tests keep asserting on it.
     */
    @Test
    void v8RefusesAPositiveEquityTheDeployedValidatorsCannotSatisfy() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        assertEquals(BigInteger.valueOf(84_500_000), scenario.assessment().equity(),
                "the fixture must really carry a borrower compensation");
        assertEquals(LiquidateTransactionBuilder.Refusal.POSITIVE_EQUITY_UNSUPPORTED, refusal(scenario));
    }

    /**
     * The boundary partner: the identical loan against 100 ADA of collateral instead of 200 owes more
     * than it holds, so the equity clamps to zero and the batch builds. Without this, V8 would be
     * indistinguishable from a veto that refuses every ada liquidation.
     */
    @Test
    void v8LetsAZeroEquityLiquidationThrough() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                100_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        assertEquals(BigInteger.ZERO, scenario.assessment().equity(),
                "100 ADA of collateral against 110 ADA of debt plus a 5% penalty clamps to zero");

        Transaction tx = build(List.of(scenario), Map.of(),
                LiquidateTransactionBuilder.ReferenceScripts.none());

        // Four outputs, not five: dummy, bond echo, collateral, change — and no equity output, which
        // is exactly the shape that stays submittable on the deployed validators.
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        assertEquals(4, outputs.size(), "dummy, bond, collateral, change");
        assertEquals(BigInteger.valueOf(99_000_000), outputs.get(2).getValue().getCoin(),
                "collateral - equity - fee = 100_000_000 - 0 - 1_000_000");
        assertEquals(expectedAssetManagerDatum(scenario, LiquidationTxEncoder.CLAIMED_COLLATERAL_ACTION_HEX,
                        REGISTRY.getLenderBondPolicyId()),
                outputs.get(2).getInlineDatum().serializeToHex());
    }

    // ======================================================================================
    // oracle wiring guards
    // ======================================================================================

    /**
     * There is no witness fallback for an oracle. The bundled blueprint does contain
     * {@code oracle.oracle}, but unapplied: the deployed script is that code with eight parameters
     * applied ({@code verification_keys}, {@code threshold}, {@code charlie_specs},
     * {@code orcfax_specs}, the asset identifiers) whose values FluidTokens does not publish, so
     * attaching what we have would witness a different credential than the withdrawal is made from.
     * The registry's published reference script is the only way in, and its absence is a refusal.
     */
    @Test
    void refusesAnOracleWithNoPublishedReferenceScript() {
        TokenScenario scenario = tokenScenario(LoanFixtures.inlineKeyStakeCredential(STAKE_KEY),
                VALID_FROM - 60_000L, VALID_FROM + 600_000L);
        OracleEntry withoutScript = withReferenceScript(scenario.oracle(), null);

        assertEquals(LiquidateTransactionBuilder.Refusal.ORACLE_REFERENCE_SCRIPT_MISSING,
                refusal(List.of(scenario.scenario()), Map.of(ORACLE_TOKEN.toUnit(), withoutScript),
                        MARGIN));
    }

    /**
     * The reward address decides which redeemer {@code pairs.get_first(redeemers, Withdraw(cred))}
     * picks up, so a registry entry whose published address does not derive from its own withdrawal
     * credential on this network is unusable — the withdrawal would be made from one script and the
     * feed read for another.
     */
    @Test
    void refusesAnOracleWhoseRewardAddressDoesNotMatchItsCredential() {
        TokenScenario scenario = tokenScenario(LoanFixtures.inlineKeyStakeCredential(STAKE_KEY),
                VALID_FROM - 60_000L, VALID_FROM + 600_000L);
        OracleEntry mismatched = new OracleEntry(scenario.oracle().token(),
                scenario.oracle().oracleToken(),
                LoanFixtures.rewardAddress(repeatHash("a1")),   // a different credential's address
                ORACLE_CREDENTIAL,
                scenario.oracle().referenceInput(), scenario.oracle().referenceScript(),
                List.of(), 0, scenario.oracle().feed(), List.of(),
                scenario.oracle().charlieProviderReferenceInput());

        assertEquals(LiquidateTransactionBuilder.Refusal.ORACLE_REWARD_ADDRESS_MISMATCH,
                refusal(List.of(scenario.scenario()), Map.of(ORACLE_TOKEN.toUnit(), mismatched),
                        MARGIN));
    }

    @Test
    void refusesWhenNoOracleEntryIsSuppliedForATokenLeg() {
        TokenScenario scenario = tokenScenario(LoanFixtures.inlineKeyStakeCredential(STAKE_KEY),
                VALID_FROM - 60_000L, VALID_FROM + 600_000L);

        assertEquals(LiquidateTransactionBuilder.Refusal.ORACLE_ENTRY_MISSING,
                refusal(List.of(scenario.scenario()), Map.of(), MARGIN));
    }

    /**
     * One withdrawal per oracle <em>credential</em>, not per leg:
     * {@code retrieve_oracle_data} resolves its feed with
     * {@code pairs.get_first(redeemers, Withdraw(oraclePaymentCredential))}, which finds exactly one
     * redeemer per credential, so a second withdrawal from the same script would be dead weight at
     * best. This loan's principal and collateral are the same token behind the same oracle.
     */
    @Test
    void collapsesTwoLegsBehindOneOracleIntoASingleWithdrawal() {
        TokenScenario scenario = sameOracleBothLegsScenario();

        Transaction tx = buildIgnoringPositiveEquityVeto(List.of(scenario.scenario()),
                Map.of(ORACLE_TOKEN.toUnit(), scenario.oracle()),
                LiquidateTransactionBuilder.ReferenceScripts.none());

        // Four v4 script withdrawals plus exactly one oracle withdrawal, not two.
        assertEquals(5, tx.getBody().getWithdrawals().size());
        assertEquals(5, redeemers(tx, RedeemerTag.Reward).size());
        assertEquals(1, tx.getBody().getWithdrawals().stream()
                        .filter(w -> w.getRewardAddress().equals(scenario.oracle().rewardAddress()))
                        .count(),
                "the shared oracle must be withdrawn from once");

        // And both legs of the claim point at that one oracle's reference input.
        ClaimData claim = new ClaimData(LoanFixtures.liquidation(), BigInteger.ONE, BigInteger.ZERO,
                BigInteger.ZERO, scenario.scenario().bond().bond().datum().lenderAuth(),
                scenario.assessment().equity(), LOAN_ID_A, scenario.assessment().remainingDebt());
        assertEquals(hex(LiquidationTxEncoder.loanClaimActionWithdrawRedeemer(3, List.of(claim))),
                hex(withdrawRedeemer(tx,
                        LoanFixtures.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()))));
    }

    // ======================================================================================
    // request-shape guards
    // ======================================================================================

    /**
     * D6 needs the bond output to be a byte-identical echo, and cardano-client-lib re-serialises
     * whatever {@code PlutusData} it is handed. A bytestring over 64 bytes forces CBOR chunking and
     * the library always splits at a maximal 64-byte boundary, so a datum whose 65-byte
     * {@code poolId} was chunked 60 + 5 on chain can be decoded but never re-emitted as it arrived.
     * Building on it would produce an output the validator rejects, so the batch is refused. Same
     * recipe as {@code LenderBondServiceTest#inlineDatumSurvivesAChunkedBytestringByteIdentical}.
     */
    @Test
    void refusesABondDatumThatDoesNotSurviveAByteIdenticalRoundTrip() {
        String poolId = "ab".repeat(65);
        LenderManagerDatum datum = LoanFixtures.bondDatum(BigInteger.TEN,
                LoanFixtures.inlineKeyStakeCredential(STAKE_KEY), AssetType.ada(), poolId);

        String libraryHex = LoanFixtures.hex(datum);
        String libraryChunk = "5f5840" + "ab".repeat(64) + "41ab" + "ff";
        assertTrue(libraryHex.contains(libraryChunk),
                "test assumption: cardano-client-lib chunks a 65-byte bytestring as 64+1");
        String handRolled = libraryHex.replace(libraryChunk,
                "5f583c" + "ab".repeat(60) + "45" + "ab".repeat(5) + "ff");
        assertNotEquals(libraryHex, handRolled, "the hand-rolled chunking must actually differ");

        LoanDatum loanDatum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN_A, 0, LOAN_ID_A, loanDatum,
                200_000_000L, List.of());
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND_A, 0, LOAN_ID_A, datum, 2_000_000L,
                handRolled);
        LiquidationAssessment assessment = LoanFixtures.assess(bond.bond(), loan.loan(),
                OraclePriceFeed.unit(), OraclePriceFeed.unit(), VALID_FROM);

        assertEquals(LiquidateTransactionBuilder.Refusal.BOND_DATUM_NOT_BYTE_IDENTICAL,
                refusal(new AdaScenario(loan, bond, assessment, null)));
    }

    @Test
    void refusesAnEmptyBatch() {
        assertEquals(LiquidateTransactionBuilder.Refusal.EMPTY_BATCH,
                refusal(List.of(), Map.of(), MARGIN));
    }

    @Test
    void refusesAValidityWindowThatDoesNotMoveForward() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        assertEquals(LiquidateTransactionBuilder.Refusal.VALIDITY_WINDOW_INVALID,
                refusal(List.of(scenario), Map.of(), MARGIN, VALID_FROM, VALID_FROM));
    }

    @Test
    void refusesAStakeCredentialThatIsNeitherSomeNorNone() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.undecodableStakeCredential());

        assertEquals(LiquidateTransactionBuilder.Refusal.UNDECODABLE_STAKE_CREDENTIAL, refusal(scenario));
    }

    @Test
    void refusesAWalletUtxoThatIsNotAdaOnly() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));
        Utxo dirty = LoanFixtures.utxo(TX_WALLET, 0, LoanFixtures.botAddress(),
                List.of(Amount.lovelace(BigInteger.valueOf(50_000_000)),
                        LoanFixtures.token(COLLATERAL_TOKEN, 5)), null);

        LiquidateTransactionBuilder.RefusedException refused =
                assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                        () -> builder(List.of(scenario), Map.of()).build(
                                request(List.of(scenario), Map.of(), dirty, MARGIN, VALID_FROM, VALID_TO,
                                        LiquidateTransactionBuilder.ReferenceScripts.none())));
        assertEquals(LiquidateTransactionBuilder.Refusal.WALLET_UTXO_NOT_ADA_ONLY, refused.getReason());
    }

    @Test
    void refusesTwoEntriesForTheSameLoan() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        assertEquals(LiquidateTransactionBuilder.Refusal.DUPLICATE_LOAN,
                refusal(List.of(scenario, scenario), Map.of(), MARGIN));
    }

    @Test
    void refusesAUtxoThatIsNotTheAssessedOne() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));
        Utxo elsewhere = LoanFixtures.utxo(TX_LOAN_B, 7, scenario.loan().utxo().getAddress(),
                scenario.loan().utxo().getAmount(), scenario.loan().utxo().getInlineDatum());
        AdaScenario swapped = new AdaScenario(scenario.loan(), scenario.bond(), scenario.assessment(),
                elsewhere);

        assertEquals(LiquidateTransactionBuilder.Refusal.UTXO_DOES_NOT_MATCH_ASSESSMENT, refusal(swapped));
    }

    // ======================================================================================
    // the redeemers' ex-units: read off the built transaction, not off the evaluator's report
    // ======================================================================================

    /**
     * Every other ex-units assertion in this repo reads {@code EvaluationResult.getExUnits()} — what
     * the evaluator <em>said</em>. That is not the number the chain charges: the chain reads the
     * redeemers of the transaction, and if nothing copied the evaluation into them they still hold
     * cardano-client-lib's placeholders (10000 mem, 10000 or 1000 steps) against a measured 2.26M mem
     * / 778M steps. Under-declared ex-units are not rejected by the mempool; the transaction lands and
     * fails during on-chain evaluation, forfeiting the collateral.
     * <p>
     * So this test deserialises the finished transaction and compares each redeemer's declared
     * ex-units with what the evaluator was made to return for that exact {@code (tag, index)} pair.
     * The stub's numbers are a function of the pair rather than one constant, so a build that wrote
     * <em>an</em> evaluation into <em>every</em> redeemer without matching them up would fail here too.
     */
    @Test
    void theBuiltTransactionCarriesTheEvaluatedExUnitsAndNotThePlaceholders() throws Exception {
        AdaScenario scenario = zeroEquityAdaScenario();
        StubEvaluator evaluator = new StubEvaluator();

        Transaction built = builder(List.of(scenario), Map.of(), evaluator)
                .build(request(List.of(scenario), Map.of(), WALLET_UTXO, MARGIN, VALID_FROM, VALID_TO,
                        LiquidateTransactionBuilder.ReferenceScripts.none()));

        // Once, though the builder assembles twice. The first assembly is the layout probe, whose
        // claim redeemers carry placeholder output indexes no validator accepts; costing it would
        // fail by construction and refuse every batch. Pinned in both directions: it must happen (or
        // the redeemers keep their placeholders) and it must not happen twice (in production each
        // call is a round trip to Blockfrost's evaluate endpoint).
        assertEquals(1, evaluator.calls,
                "only the final assembly may be script-costed — never the throwaway layout probe");

        // Re-read from the bytes, not from the object the builder happens to hold.
        Transaction reread = Transaction.deserialize(built.serialize());
        List<Redeemer> redeemers = reread.getWitnessSet().getRedeemers();
        assertFalse(redeemers.isEmpty(), "a Liquidate has redeemers");

        for (Redeemer redeemer : redeemers) {
            int index = redeemer.getIndex().intValue();
            assertEquals(stubMem(redeemer.getTag(), index), redeemer.getExUnits().getMem(),
                    "declared mem for " + redeemer.getTag() + "#" + index
                            + " is not the evaluated one — a placeholder would be 10000");
            assertEquals(stubSteps(redeemer.getTag(), index), redeemer.getExUnits().getSteps(),
                    "declared steps for " + redeemer.getTag() + "#" + index
                            + " is not the evaluated one — a placeholder would be 10000 or 1000");
        }
    }

    /**
     * The same build with no evaluator: exactly today's behaviour, placeholders and all. Pinned rather
     * than merely allowed, because it is the state the offline rigs build in and the state nothing may
     * ever be submitted from — {@code YaciConfig} is what makes sure the armed path never sees it.
     * <p>
     * The placeholder values are also the measurement this whole defect rests on: they are constants
     * cardano-client-lib writes when it creates a {@link Redeemer}, three to five orders of magnitude
     * under what the scripts really cost.
     */
    @Test
    void withNoEvaluatorTheRedeemersStillCarryPlaceholdersAndNothingIsThrown() throws Exception {
        AdaScenario scenario = zeroEquityAdaScenario();

        Transaction built = builder(List.of(scenario), Map.of())
                .build(request(List.of(scenario), Map.of(), WALLET_UTXO, MARGIN, VALID_FROM, VALID_TO,
                        LiquidateTransactionBuilder.ReferenceScripts.none()));

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
     * turned "there is no evaluator" into a {@code log.warn} and a transaction full of placeholders in
     * the first place. With an evaluator wired, the builder sets it to {@code false}, so an evaluator
     * that fails — Blockfrost down, the transaction rejected by the evaluation endpoint — refuses the
     * batch under a named reason instead of producing an unsubmittable-but-submitted transaction.
     * <p>
     * The reason has to be its own, and it has to carry the evaluator's own words. "Blockfrost is down"
     * and "this candidate cannot be assembled" call for opposite responses from an operator, and
     * cardano-client-lib flattens both into {@code TxBuildException("Error while evaluating script
     * cost")} with the real message two wrappers down.
     */
    @Test
    void anEvaluatorThatThrowsRefusesTheBatchRatherThanFallingBackToPlaceholders() {
        AdaScenario scenario = zeroEquityAdaScenario();
        TransactionEvaluator exploding = (cbor, inputUtxos) -> {
            throw new ApiException("blockfrost says no");
        };

        LiquidateTransactionBuilder.RefusedException refused =
                assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                        () -> builder(List.of(scenario), Map.of(), exploding).build(
                                request(List.of(scenario), Map.of(), WALLET_UTXO, MARGIN, VALID_FROM,
                                        VALID_TO, LiquidateTransactionBuilder.ReferenceScripts.none())));

        assertEquals(LiquidateTransactionBuilder.Refusal.SCRIPT_COST_EVALUATION_FAILED,
                refused.getReason());
        assertTrue(refused.getMessage().contains("blockfrost says no"),
                "the evaluator's own reason must survive to the operator: " + refused.getMessage());
    }

    /** And the other failure shape: the endpoint answered, but with an error rather than a costing. */
    @Test
    void anEvaluatorThatReturnsAnErrorAlsoRefusesTheBatch() {
        AdaScenario scenario = zeroEquityAdaScenario();
        TransactionEvaluator rejecting = (cbor, inputUtxos) -> Result.error("ValidationFailure");

        LiquidateTransactionBuilder.RefusedException refused =
                assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                        () -> builder(List.of(scenario), Map.of(), rejecting).build(
                                request(List.of(scenario), Map.of(), WALLET_UTXO, MARGIN, VALID_FROM,
                                        VALID_TO, LiquidateTransactionBuilder.ReferenceScripts.none())));

        assertEquals(LiquidateTransactionBuilder.Refusal.SCRIPT_COST_EVALUATION_FAILED,
                refused.getReason());
        assertTrue(refused.getMessage().contains("ValidationFailure"),
                "the endpoint's response must survive to the operator: " + refused.getMessage());
    }

    /**
     * The third failure shape, and the one that looks like success: HTTP 200 with an <em>empty</em>
     * costing array. Checking only {@code isSuccessful()} would pass this straight through —
     * {@code ScriptCostEvaluators} would write back nothing, every redeemer would keep its 10000-mem
     * placeholder, the build would succeed, and in live mode that transaction goes out and forfeits
     * collateral in phase 2. Exactly the defect this class exists to close, one layer in.
     */
    @Test
    void anEvaluatorThatSucceedsWithNoCostingsAtAllRefusesRatherThanBuilding() {
        AdaScenario scenario = zeroEquityAdaScenario();
        TransactionEvaluator emptySuccess =
                (cbor, inputUtxos) -> Result.success("ok").withValue(List.<EvaluationResult>of());

        LiquidateTransactionBuilder.RefusedException refused =
                assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                        () -> builder(List.of(scenario), Map.of(), emptySuccess).build(
                                request(List.of(scenario), Map.of(), WALLET_UTXO, MARGIN, VALID_FROM,
                                        VALID_TO, LiquidateTransactionBuilder.ReferenceScripts.none())));

        assertEquals(LiquidateTransactionBuilder.Refusal.SCRIPT_COST_EVALUATION_FAILED,
                refused.getReason());
        assertTrue(refused.getMessage().contains("costed 0 of "),
                "the refusal must say how many of how many were costed: " + refused.getMessage());
    }

    /**
     * The subtler half: a costing array that is non-empty but incomplete. A check that only asked
     * "did we get anything back?" would pass this, and every redeemer the evaluator skipped would
     * still be submitted at 10000 mem. Coverage is therefore asserted per {@code (tag, index)} pair,
     * which is also the key cardano-client-lib writes back on.
     */
    @Test
    void anEvaluatorThatCostsOnlySomeRedeemersRefusesRatherThanBuilding() {
        AdaScenario scenario = zeroEquityAdaScenario();
        StubEvaluator complete = new StubEvaluator();
        TransactionEvaluator partial = (cbor, inputUtxos) -> {
            List<EvaluationResult> all = complete.evaluateTx(cbor, inputUtxos).getValue();
            // Cover exactly one redeemer and omit the rest.
            return Result.success("ok").withValue(all.subList(0, 1));
        };

        LiquidateTransactionBuilder.RefusedException refused =
                assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                        () -> builder(List.of(scenario), Map.of(), partial).build(
                                request(List.of(scenario), Map.of(), WALLET_UTXO, MARGIN, VALID_FROM,
                                        VALID_TO, LiquidateTransactionBuilder.ReferenceScripts.none())));

        assertEquals(LiquidateTransactionBuilder.Refusal.SCRIPT_COST_EVALUATION_FAILED,
                refused.getReason());
        assertTrue(refused.getMessage().contains("costed 1 of "),
                "the refusal must name the shortfall: " + refused.getMessage());
    }

    /**
     * The negative control the two tests above need to mean anything: the new reason must fire for
     * evaluator failures and <em>only</em> for them, or it is just a rename of
     * {@code TRANSACTION_NOT_BUILDABLE}.
     * <p>
     * The evaluator here <b>succeeds</b> — it simply reports a cost nobody can pay. Priced at
     * {@code priceMem} 0.0577 that is hundreds of ADA of fee against a wallet holding sixty, so the
     * build fails during balancing, strictly <em>after</em> a successful evaluation. That is the case
     * a string match on cardano-client-lib's wrapper message would get wrong, and the typed marker
     * gets right.
     */
    @Test
    void aBuildThatFailsAfterASuccessfulEvaluationIsNotReportedAsAnEvaluatorFailure() {
        AdaScenario scenario = zeroEquityAdaScenario();
        TransactionEvaluator unaffordable = (cbor, inputUtxos) -> {
            List<EvaluationResult> results = new ArrayList<>();
            try {
                for (Redeemer redeemer : Transaction.deserialize(cbor).getWitnessSet().getRedeemers()) {
                    results.add(EvaluationResult.builder()
                            .redeemerTag(redeemer.getTag())
                            .index(redeemer.getIndex().intValue())
                            .exUnits(ExUnits.builder()
                                    .mem(BigInteger.valueOf(9_000_000_000L))
                                    .steps(BigInteger.valueOf(9_000_000_000_000L))
                                    .build())
                            .build());
                }
            } catch (Exception e) {
                throw new AssertionError("undeserialisable bytes reached the evaluator", e);
            }
            return Result.success("ok").withValue(results);
        };

        LiquidateTransactionBuilder.RefusedException refused =
                assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                        () -> builder(List.of(scenario), Map.of(), unaffordable).build(
                                request(List.of(scenario), Map.of(), WALLET_UTXO, MARGIN, VALID_FROM,
                                        VALID_TO, LiquidateTransactionBuilder.ReferenceScripts.none())));

        assertEquals(LiquidateTransactionBuilder.Refusal.TRANSACTION_NOT_BUILDABLE, refused.getReason(),
                "a build that failed after the evaluator answered is not an evaluator outage: "
                        + refused.getMessage());
    }

    /**
     * Closing the evaluation hole must not open the submission one. A {@code TransactionEvaluator} is
     * the narrowest thing that can price a transaction: one method, taking bytes and returning
     * costings, and no way to transmit anything. This is the falsifiable form — the interface the
     * builder now accepts declares exactly one abstract method, and that method is not a submit.
     * <p>
     * The sibling half of the claim is that the builder never receives or holds anything
     * <em>wider</em>. A constructor is not the only way in — a setter or a mutable field would do just
     * as well — so the sweep is over every declared constructor, method and field, not only the
     * constructors: nothing reachable from outside this class may accept or hold a
     * {@code TransactionProcessor}, a {@code *BackendService} or a {@code TransactionSubmitter}.
     * Private members are swept too, because a private field is what a package-private setter would
     * write into.
     */
    @Test
    void theBuilderNeitherAcceptsNorHoldsAnythingThatCanSubmit() {
        List<Method> abstractMethods = Arrays.stream(TransactionEvaluator.class.getDeclaredMethods())
                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                .toList();
        assertEquals(1, abstractMethods.size(),
                "TransactionEvaluator grew a second operation: " + abstractMethods);
        assertEquals("evaluateTx", abstractMethods.getFirst().getName());
        assertFalse(TransactionProcessor.class.isAssignableFrom(TransactionEvaluator.class),
                "an evaluator must not be a processor — a processor can submit");

        for (Constructor<?> constructor : LiquidateTransactionBuilder.class.getDeclaredConstructors()) {
            for (Class<?> parameter : constructor.getParameterTypes()) {
                assertCannotSubmit(parameter, "constructor " + constructor);
            }
        }
        for (Method method : LiquidateTransactionBuilder.class.getDeclaredMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                assertCannotSubmit(parameter, "method " + method.getName());
            }
            assertCannotSubmit(method.getReturnType(), "the return type of " + method.getName());
        }
        for (Field field : LiquidateTransactionBuilder.class.getDeclaredFields()) {
            assertCannotSubmit(field.getType(), "field " + field.getName());
        }
    }

    /**
     * One member of the builder's surface, checked against the three shapes that can reach the wire.
     * The {@code BackendService} check is by name rather than by type because the point is to catch
     * <em>any</em> backend — Blockfrost's, Ogmios', Koios' — not the one that happens to be on the
     * classpath here.
     */
    private static void assertCannotSubmit(Class<?> type, String where) {
        assertFalse(TransactionProcessor.class.isAssignableFrom(type),
                where + " involves " + type.getName() + ", which can submit");
        assertFalse(type.getName().contains("BackendService"),
                where + " involves " + type.getName() + ", which can submit");
        assertFalse(LiquidationExecutor.TransactionSubmitter.class.isAssignableFrom(type),
                where + " involves a submitter");
    }

    /** 100 ADA of collateral against 110 ADA of debt: under water, so V8 has nothing to refuse. */
    private static AdaScenario zeroEquityAdaScenario() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                100_000_000L, BigInteger.valueOf(500), LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));
        assertEquals(BigInteger.ZERO, scenario.assessment().equity(),
                "the fixture must have zero equity, or V8 refuses it before any evaluation happens");
        return scenario;
    }

    /**
     * Ex-units keyed on the redeemer they belong to, so "every redeemer got its own answer" is
     * checkable. Well inside {@code maxTxExMem}/{@code maxTxExSteps} for the six redeemers a
     * {@code Liquidate} carries, and nowhere near any placeholder.
     */
    private static BigInteger stubMem(RedeemerTag tag, int index) {
        return BigInteger.valueOf(1_000_000L + tag.ordinal() * 100_000L + index * 1_000L);
    }

    private static BigInteger stubSteps(RedeemerTag tag, int index) {
        return BigInteger.valueOf(500_000_000L + tag.ordinal() * 10_000_000L + index * 100_000L);
    }

    /**
     * A {@link TransactionEvaluator} that costs whatever it is shown, at {@link #stubMem}/
     * {@link #stubSteps} per redeemer. It reads the redeemers out of the CBOR it was handed rather
     * than out of the builder, which is also how it proves the builder really serialised the
     * transaction for evaluation.
     */
    private static final class StubEvaluator implements TransactionEvaluator {

        private int calls;

        @Override
        @SuppressWarnings("unchecked")
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
                                    .mem(stubMem(redeemer.getTag(), index))
                                    .steps(stubSteps(redeemer.getTag(), index))
                                    .build())
                            .build());
                }
            } catch (Exception e) {
                throw new AssertionError("the builder handed the evaluator undeserialisable bytes", e);
            }
            return Result.success("ok").withValue(results);
        }
    }

    // ======================================================================================
    // scenario plumbing
    // ======================================================================================

    /** One ada/ada loan, its bond, the assessment, and the UTxO actually handed to the builder. */
    private record AdaScenario(LoanFixtures.LoanUtxo loan,
                               LoanFixtures.BondUtxo bond,
                               LiquidationAssessment assessment,
                               Utxo loanUtxoOverride) {

        Utxo loanUtxo() {
            return loanUtxoOverride != null ? loanUtxoOverride : loan.utxo();
        }

        AdaScenario withAssessment(LiquidationAssessment replacement) {
            return new AdaScenario(loan, bond, replacement, loanUtxoOverride);
        }

        LiquidateTransactionBuilder.LoanLiquidation toLiquidation() {
            return new LiquidateTransactionBuilder.LoanLiquidation(assessment, loanUtxo(), bond.utxo());
        }
    }

    private record TokenScenario(AdaScenario scenario, OracleEntry oracle,
                                 LiquidationAssessment assessment) {
    }

    private static AdaScenario adaScenario(String loanId, String loanTx, String bondTx,
                                           long principal, long interestRate, long collateral,
                                           BigInteger feePerMille, PlutusData stakeCredential) {
        return adaScenario(loanId, loanTx, bondTx, principal, interestRate, collateral, feePerMille,
                stakeCredential, LATE_LEND_DATE);
    }

    private static AdaScenario adaScenario(String loanId, String loanTx, String bondTx,
                                           long principal, long interestRate, long collateral,
                                           BigInteger feePerMille, PlutusData stakeCredential,
                                           long lendDate) {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(principal),
                BigInteger.valueOf(interestRate), LoanFixtures.adaCollateral(), lendDate,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        return adaScenarioFrom(datum, loanId, loanTx, bondTx, collateral, feePerMille, stakeCredential);
    }

    private static AdaScenario adaScenarioFrom(LoanDatum datum, String loanId, String loanTx,
                                               String bondTx, long collateral, BigInteger feePerMille,
                                               PlutusData stakeCredential) {
        return adaScenarioFrom(datum, loanId, loanTx, bondTx, collateral,
                LoanFixtures.bondDatum(feePerMille, stakeCredential, AssetType.ada()));
    }

    /** As above, with the bond datum supplied whole — for the fields {@code bondDatum} pins to false. */
    private static AdaScenario adaScenarioFrom(LoanDatum datum, String loanId, String loanTx,
                                               String bondTx, long collateral,
                                               LenderManagerDatum bondDatum) {
        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(loanTx, 0, loanId, datum, collateral, List.of());
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(bondTx, 0, loanId, bondDatum, 2_000_000L);
        LiquidationAssessment assessment = LoanFixtures.assess(bond.bond(), loan.loan(),
                OraclePriceFeed.unit(), OraclePriceFeed.unit(), VALID_FROM);
        return new AdaScenario(loan, bond, assessment, null);
    }

    /**
     * An ada-principal loan collateralised by 1_000_000 units of a token priced at 100 lovelace by a
     * Charli3 feed:
     * <pre>
     *   principal 50 ADA at 10%                     -> remainingDebt   55_000_000 lovelace
     *   collateral 1_000_000 tok at 100 lovelace    -> 100_000_000 lovelace
     *   penalty 50 per mille of the debt            -> equity             422_500 tok
     *   liquidationFeePerMille 10                   -> fee                 10_000 tok
     * </pre>
     */
    private static TokenScenario tokenScenario(PlutusData stakeCredential, long feedValidFrom,
                                               long feedValidTo) {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(50_000_000),
                BigInteger.valueOf(1000),
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, ORACLE_TOKEN), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);

        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN_A, 0, LOAN_ID_A, datum, 2_000_000L,
                List.of(LoanFixtures.token(COLLATERAL_TOKEN, 1_000_000L)));
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND_A, 0, LOAN_ID_A,
                LoanFixtures.bondDatum(BigInteger.TEN, stakeCredential, AssetType.ada()), 2_000_000L);

        OraclePriceFeed feed = OraclePriceFeed.priceDataCharlie(COLLATERAL_TOKEN,
                BigInteger.valueOf(100), BigInteger.ONE, feedValidFrom, feedValidTo);
        OracleEntry oracle = LoanFixtures.charli3(COLLATERAL_TOKEN, ORACLE_TOKEN, ORACLE_CREDENTIAL, feed,
                LoanFixtures.input(TX_ORACLE_NFT, 0), LoanFixtures.input(TX_ORACLE_SCRIPT, 0),
                LoanFixtures.input(TX_CHARLI3_PROVIDER, 0));

        LiquidationAssessment assessment = LoanFixtures.assess(bond.bond(), loan.loan(),
                OraclePriceFeed.unit(), feed, VALID_FROM);
        return new TokenScenario(new AdaScenario(loan, bond, assessment, null), oracle, assessment);
    }

    /**
     * A loan whose principal <em>and</em> collateral are the same token, so both legs resolve to one
     * oracle entry. Numerically identical to {@link #tokenScenario}: 500_000 TOK of principal at 10%
     * is 550_000 TOK of debt, which at 100 lovelace a unit is the same 55 ADA against the same
     * 1_000_000 TOK of collateral.
     */
    private static TokenScenario sameOracleBothLegsScenario() {
        LoanDatum datum = LoanFixtures.loanDatum(COLLATERAL_TOKEN, ORACLE_TOKEN,
                BigInteger.valueOf(500_000), BigInteger.valueOf(1000),
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, ORACLE_TOKEN), LATE_LEND_DATE,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);

        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(TX_LOAN_A, 0, LOAN_ID_A, datum, 2_000_000L,
                List.of(LoanFixtures.token(COLLATERAL_TOKEN, 1_000_000L)));
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(TX_BOND_A, 0, LOAN_ID_A,
                LoanFixtures.bondDatum(BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY),
                        COLLATERAL_TOKEN), 2_000_000L);

        OraclePriceFeed feed = OraclePriceFeed.priceDataCharlie(COLLATERAL_TOKEN,
                BigInteger.valueOf(100), BigInteger.ONE, VALID_FROM - 60_000L, VALID_FROM + 600_000L);
        OracleEntry oracle = LoanFixtures.charli3(COLLATERAL_TOKEN, ORACLE_TOKEN, ORACLE_CREDENTIAL, feed,
                LoanFixtures.input(TX_ORACLE_NFT, 0), LoanFixtures.input(TX_ORACLE_SCRIPT, 0),
                LoanFixtures.input(TX_CHARLI3_PROVIDER, 0));

        LiquidationAssessment assessment = LoanFixtures.assess(bond.bond(), loan.loan(), feed, feed,
                VALID_FROM);
        return new TokenScenario(new AdaScenario(loan, bond, assessment, null), oracle, assessment);
    }

    private static OracleEntry withReferenceScript(OracleEntry oracle, TransactionInput referenceScript) {
        return new OracleEntry(oracle.token(), oracle.oracleToken(), oracle.rewardAddress(),
                oracle.withdrawCredentialHash(), oracle.referenceInput(), referenceScript,
                List.of(), 0, oracle.feed(), List.of(), oracle.charlieProviderReferenceInput());
    }

    // ---- building ----------------------------------------------------------------------------

    private static LiquidateTransactionBuilder builder(List<AdaScenario> scenarios,
                                                       Map<String, OracleEntry> oracles) {
        return builder(scenarios, oracles, null);
    }

    /** The same builder with a script-cost evaluator, for the ex-units section above. */
    private static LiquidateTransactionBuilder builder(List<AdaScenario> scenarios,
                                                       Map<String, OracleEntry> oracles,
                                                       TransactionEvaluator evaluator) {
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO, WALLET_UTXO,
                BOT_SPARE_UTXO));
        scenarios.forEach(scenario -> {
            universe.add(scenario.loanUtxo());
            universe.add(scenario.bond().utxo());
        });
        return new LiquidateTransactionBuilder(REGISTRY, LoanFixtures.NETWORK, LoanFixtures.converters(),
                LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams(), evaluator);
    }

    private static LiquidateTransactionBuilder.Request request(List<AdaScenario> scenarios,
                                                               Map<String, OracleEntry> oracles,
                                                               Utxo wallet, long margin,
                                                               long validFrom, long validTo,
                                                               LiquidateTransactionBuilder.ReferenceScripts refs) {
        return new LiquidateTransactionBuilder.Request(
                scenarios.stream().map(AdaScenario::toLiquidation).toList(),
                CONFIG_UTXO, LM_CONFIG_UTXO, oracles, wallet, LoanFixtures.botAddress(),
                validFrom, validTo, margin, refs);
    }

    private static Transaction build(List<AdaScenario> scenarios, Map<String, OracleEntry> oracles,
                                     LiquidateTransactionBuilder.ReferenceScripts refs) {
        Transaction transaction = builder(scenarios, oracles).build(
                request(scenarios, oracles, WALLET_UTXO, MARGIN, VALID_FROM, VALID_TO, refs));
        assertNotNull(transaction);
        return transaction;
    }

    /**
     * Builds through {@link LiquidateTransactionBuilder}'s package-private V8 seam, for the anatomy
     * tests whose scenarios have a positive equity.
     * <p>
     * V8 refuses those outright, because no output layout this builder emits satisfies a positive
     * equity on the <em>deployed</em> validators ({@code LiquidateDryEvalTest} is the evidence). The
     * transaction is still the structurally correct one and becomes submittable the day that lifts, so
     * every claim these tests make about its anatomy — the equity output's datum, its value, its place
     * in the body, the indexes that point at it — is worth keeping exactly as it was. Routing them
     * through the seam preserves them without weakening a single assertion; V8 itself is proven by
     * {@link #v8RefusesAPositiveEquityTheDeployedValidatorsCannotSatisfy()} on the public entry point.
     */
    private static Transaction buildIgnoringPositiveEquityVeto(
            List<AdaScenario> scenarios, Map<String, OracleEntry> oracles,
            LiquidateTransactionBuilder.ReferenceScripts refs) {
        Transaction transaction = builder(scenarios, oracles).buildIgnoringPositiveEquityVeto(
                request(scenarios, oracles, WALLET_UTXO, MARGIN, VALID_FROM, VALID_TO, refs));
        assertNotNull(transaction);
        return transaction;
    }

    /** {@link #refusal(AdaScenario)} through the same seam, for a refusal V8 would otherwise mask. */
    private static LiquidateTransactionBuilder.Refusal refusalIgnoringPositiveEquityVeto(
            AdaScenario scenario) {
        List<AdaScenario> scenarios = List.of(scenario);
        LiquidateTransactionBuilder.RefusedException refused =
                assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                        () -> builder(scenarios, Map.of()).buildIgnoringPositiveEquityVeto(
                                request(scenarios, Map.of(), WALLET_UTXO, MARGIN, VALID_FROM, VALID_TO,
                                        LiquidateTransactionBuilder.ReferenceScripts.none())));
        return refused.getReason();
    }

    private static LiquidateTransactionBuilder.Refusal refusal(AdaScenario scenario) {
        return refusal(List.of(scenario), Map.of(), MARGIN);
    }

    private static LiquidateTransactionBuilder.Refusal refusal(List<AdaScenario> scenarios,
                                                               Map<String, OracleEntry> oracles,
                                                               long margin) {
        return refusal(scenarios, oracles, margin, VALID_FROM, VALID_TO);
    }

    private static LiquidateTransactionBuilder.Refusal refusal(List<AdaScenario> scenarios,
                                                               Map<String, OracleEntry> oracles,
                                                               long margin, long validFrom, long validTo) {
        LiquidateTransactionBuilder.RefusedException refused =
                assertThrows(LiquidateTransactionBuilder.RefusedException.class,
                        () -> builder(scenarios, oracles).build(request(scenarios, oracles, WALLET_UTXO,
                                margin, validFrom, validTo,
                                LiquidateTransactionBuilder.ReferenceScripts.none())));
        return refused.getReason();
    }

    // ---- reading the built transaction back ----------------------------------------------------

    private static List<TransactionInput> sortedInputs(Transaction tx) {
        return tx.getBody().getInputs().stream()
                .sorted(new TransactionInputComparator())
                .toList();
    }

    private static List<TransactionInput> sortedRefInputs(Transaction tx) {
        return tx.getBody().getReferenceInputs().stream()
                .sorted(new TransactionInputComparator())
                .toList();
    }

    private static List<Redeemer> redeemers(Transaction tx, RedeemerTag tag) {
        return tx.getWitnessSet().getRedeemers().stream()
                .filter(redeemer -> redeemer.getTag() == tag)
                .toList();
    }

    private static PlutusData withdrawRedeemer(Transaction tx, String rewardAddress) {
        List<Withdrawal> withdrawals = tx.getBody().getWithdrawals();
        int index = IntStream.range(0, withdrawals.size())
                .filter(i -> withdrawals.get(i).getRewardAddress().equals(rewardAddress))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no withdrawal at " + rewardAddress));
        return redeemers(tx, RedeemerTag.Reward).stream()
                .filter(redeemer -> redeemer.getIndex().intValue() == index)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no reward redeemer at index " + index))
                .getData();
    }

    private static String hex(PlutusData data) {
        return data.serializeToHex();
    }

    private static String expectedAssetManagerDatum(AdaScenario scenario, String action, String policyId) {
        return hex(LiquidationTxEncoder.assetManagerDatumWithToken(new AssetManagerDatumWithToken(
                scenario.loanUtxo().getTxHash(), scenario.loanUtxo().getOutputIndex(), action,
                new AssetType(policyId, scenario.loan().loan().loanId()))));
    }

    private static BigInteger quantityOf(TransactionOutput output, AssetType asset) {
        return ValueUtil.toAmountList(output.getValue()).stream()
                .filter(amount -> LoanFixtures.unit(asset).equals(amount.getUnit()))
                .map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static Map<String, BigInteger> amountsByUnit(List<Amount> amounts) {
        Map<String, BigInteger> byUnit = new LinkedHashMap<>();
        amounts.forEach(amount -> byUnit.merge(amount.getUnit(), amount.getQuantity(), BigInteger::add));
        return byUnit;
    }
}
