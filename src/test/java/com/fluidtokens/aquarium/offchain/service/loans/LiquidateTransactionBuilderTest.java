package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AssetManagerDatumWithToken;
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.TransactionInputComparator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        Transaction tx = build(List.of(scenario), Map.of(), LiquidateTransactionBuilder.ReferenceScripts.none());

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
        assertEquals(hex(LiquidationTxEncoder.lmLiquidateWithdrawRedeemer(0, List.of(0L), List.of(LOAN_ID_A))),
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
        Transaction tx = build(List.of(scenario), Map.of(), LiquidateTransactionBuilder.ReferenceScripts.none());

        assertEquals(hex(LiquidationTxEncoder.loanMintRedeemer(0, false, 0)),
                hex(redeemers(tx, RedeemerTag.Mint).getFirst().getData()));
    }

    /** {@code None} in the datum means the collateral lands at an enterprise asset-manager address. */
    @Test
    void anAbsentStakeCredentialProducesAnEnterpriseAssetManagerAddress() {
        AdaScenario scenario = adaScenario(LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 100_000_000L, 1000L,
                200_000_000L, BigInteger.TEN, LoanFixtures.noStakeCredential());
        Transaction tx = build(List.of(scenario), Map.of(), LiquidateTransactionBuilder.ReferenceScripts.none());

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

        Transaction tx = build(List.of(a, b), Map.of(), LiquidateTransactionBuilder.ReferenceScripts.none());

        List<TransactionInput> inputs = sortedInputs(tx);
        assertEquals(List.of(new TransactionInput(TX_LOAN_A, 0), new TransactionInput(TX_BOND_B, 0),
                        new TransactionInput(TX_LOAN_B, 0), new TransactionInput(TX_BOND_A, 0),
                        new TransactionInput(TX_WALLET, 0)),
                inputs, "the fixture must actually interleave loans and bonds");

        // loan order is [A, B]; bond order is [B(bb), A(dd)] — so A pairs with bond index 1.
        assertEquals(hex(LiquidationTxEncoder.lmLiquidateWithdrawRedeemer(0, List.of(1L, 0L),
                        List.of(LOAN_ID_A, LOAN_ID_B))),
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

        Transaction tx = build(List.of(scenario.scenario()),
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

        Transaction tx = build(List.of(scenario), Map.of(), published);

        assertTrue(tx.getWitnessSet().getPlutusV3Scripts().isEmpty(),
                "a published script must not also travel in the witness set");
        List<TransactionInput> refInputs = sortedRefInputs(tx);
        IntStream.range(0, 6).forEach(index ->
                assertTrue(refInputs.contains(new TransactionInput(TX_REF_SCRIPTS, index)),
                        "reference script " + index + " is missing"));
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
    // V4 — the health guards, at both ends of the window
    // ======================================================================================

    /**
     * A perpetual loan's debt grows with time, so an assessment taken at {@code validFrom} is already
     * stale at {@code validTo}. The chain's evaluation point is unknown, so the batch is refused
     * rather than gambled on.
     */
    @Test
    void v4RefusesADebtThatMovesInsideTheValidityWindow() {
        LoanDatum datum = LoanFixtures.loanDatum(AssetType.ada(), BigInteger.valueOf(100_000_000),
                BigInteger.valueOf(1000), LoanFixtures.adaCollateral(), LATE_LEND_DATE,
                LoanFixtures.liquidation(),
                new RepaymentMode.PerpetualLoan(BigInteger.ZERO, BigInteger.ZERO), false);
        AdaScenario scenario = adaScenarioFrom(datum, LOAN_ID_A, TX_LOAN_A, TX_BOND_A, 200_000_000L,
                BigInteger.TEN, LoanFixtures.inlineKeyStakeCredential(STAKE_KEY));

        // A 50-minute window is enough for the perpetual interest to move by more than a lovelace.
        assertEquals(LiquidateTransactionBuilder.Refusal.REMAINING_DEBT_NOT_INVARIANT,
                refusal(List.of(scenario), Map.of(), MARGIN, VALID_FROM, VALID_FROM + 3_000_000L));
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

        assertEquals(LiquidateTransactionBuilder.Refusal.STRUCTURAL_ASSERTION_FAILED, refusal(scenario));
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

        Transaction tx = build(List.of(scenario.scenario()),
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
        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo(loanTx, 0, loanId, datum, collateral, List.of());
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo(bondTx, 0, loanId,
                LoanFixtures.bondDatum(feePerMille, stakeCredential, AssetType.ada()), 2_000_000L);
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
        List<Utxo> universe = new ArrayList<>(List.of(CONFIG_UTXO, LM_CONFIG_UTXO, WALLET_UTXO,
                BOT_SPARE_UTXO));
        scenarios.forEach(scenario -> {
            universe.add(scenario.loanUtxo());
            universe.add(scenario.bond().utxo());
        });
        return new LiquidateTransactionBuilder(REGISTRY, LoanFixtures.NETWORK, LoanFixtures.converters(),
                LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams());
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
