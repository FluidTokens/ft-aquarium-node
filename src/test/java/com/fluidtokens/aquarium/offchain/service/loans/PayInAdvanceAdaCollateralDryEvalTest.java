package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.HashCheckedScriptSupplier;
import com.fluidtokens.aquarium.offchain.config.YaciConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.CollateralAsset;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.ACQUIRED;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.COLLATERAL_LOVELACE;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.EQUITY;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.EQUITY_ZERO_PAYOUT;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.EQUITY_ZERO_PRICE;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.LENDER_SHOULD_RECEIVE;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.LIQUIDATION_FEE;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.PAYOUT;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.PRICE;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.RECORDED_PRICE;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.REMAINING_DEBT;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.T0;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.USDM_V2_ORACLE_HASH;
import static com.fluidtokens.aquarium.offchain.service.loans.AdaCollateralUsdmFixture.USDM_V2_ORACLE_NFT;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.BOT_ADDRESS;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.PRICE_DENOMINATOR;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.REGISTRY;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.STRIKE;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.USDM;
import static com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Blueprint.AQUARIUM_SC;
import static com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Blueprint.LOANS_V4;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * ⛔ FAB-226 — <b>an ADA-collateral, token-principal pay-in-advance, evaluated offline against the deployed
 * validators.</b>
 * <p>
 * A recorded mainnet loan holds 8,100 ADA as collateral against a 551 USDM principal, and its lender bond asks for
 * conversion to principal ({@link AdaCollateralUsdmFixture}). Every build here goes through
 * {@link LiquidatePayInAdvanceTransactionBuilder}'s production constructor with only its leaves supplied: the UTxO
 * set, the protocol parameters, the script bytes behind the production {@link HashCheckedScriptSupplier}, and the
 * real PlutusV3 machine with the mainnet slot config. The one oracle leg comes from {@link TestOracleRig}: the
 * deployed USDM v2 oracle's code, control-hashed and re-parameterised with runtime keys. It travels only as a
 * reference script.
 *
 * <h2>Why one leg</h2>
 * An ADA collateral has no oracle. {@code lib/fluidtokens/oracle.ak}'s {@code retrieve_oracle_data} returns the
 * synthesised 1:1 feed for the empty policy id without reading any reference input or redeemer, so the request's
 * {@code oracle} is {@code null}. Both {@code validators/loan/loan_claim_action.ak} and
 * {@code validators/lender-manager/lm_liquidate_and_pay_in_advance_action.ak} still evaluate
 * {@code safe_list_at(reference_inputs, collateralOracleRefInputIndex)}, so the collateral index must be in range;
 * its content is ignored.
 *
 * <h2>Where the seized ADA goes</h2>
 * The bot takes the collateral less the borrower's equity: about 7,629 ADA, in the loan input's lovelace. It must
 * come back in an ADA-only output, or the wallet's next build has no spendable ADA. The leftover principal tokens of
 * the wallet input go to their own named output at min-ADA, so the change is ADA-only by construction.
 *
 * <h2>Reading a failure</h2>
 * The evaluator reports only the first failing redeemer ({@link EvalFixtures}, harness limitation 1), so every
 * negative here names its refuser by the withdrawal's position in the same body, or is a named refusal with zero
 * evaluations. The random rig credential sorts among the withdrawals, so a position is always derived from the body.
 */
@Slf4j
class PayInAdvanceAdaCollateralDryEvalTest {

    /** The USDM the token output carries beyond the payout, which comes back in the named remainder output. */
    private static final BigInteger LEFTOVER_USDM = BigInteger.valueOf(5_000_000L);
    /** The default token output's lovelace. */
    private static final long TOKEN_OUTPUT_LOVELACE = 10_000_000L;

    private static final long TX_VALID_TO = T0 + 120_000L;
    private static final BigInteger PLACEHOLDER = BigInteger.valueOf(10_000);

    // ======================================================================================
    // (a), (b) the recorded candidate and the deployed oracle
    // ======================================================================================

    @Test
    void theRecordedLoanIsAnAdaCollateralTokenPrincipalPayInAdvanceCandidate() throws Exception {
        LoanDatum loan = AdaCollateralUsdmFixture.loanDatum();
        assertEquals(USDM, loan.principalAsset());
        assertEquals(AdaCollateralUsdmFixture.PRINCIPAL, loan.principalAmount());
        assertEquals(USDM_V2_ORACLE_NFT, loan.principalOracleAsset(), "the principal is priced by USDM v2");
        assertEquals(BigInteger.valueOf(400), loan.interestRate());
        assertEquals(BigInteger.valueOf(1_791_205_500_000L), loan.lendDate());
        assertEquals(new RepaymentMode.PerpetualLoan(BigInteger.valueOf(28), BigInteger.valueOf(5)), loan.repaymentMode());
        assertEquals(new LiquidationMode.Liquidation(BigInteger.valueOf(100), BigInteger.valueOf(125),
                BigInteger.valueOf(100), false), loan.liquidationMode());
        assertFalse(loan.repaymentReceipts());
        assertEquals(new CollateralAsset("", Optional.of(""), AssetType.ada()), loan.collateral(),
                "ADA collateral: the empty policy id, an asset name present, no oracle token");
        assertTrue(loan.collateral().isAda());
        assertEquals(AssetType.ada(), loan.collateral().assetType());

        Utxo loanUtxo = AdaCollateralUsdmFixture.loanUtxo();
        assertEquals(List.of(BigInteger.valueOf(COLLATERAL_LOVELACE)), loanUtxo.getAmount().stream()
                        .filter(a -> AssetType.LOVELACE.equals(a.getUnit())).map(Amount::getQuantity).toList(),
                "the loan UTxO's lovelace is the whole collateral");
        assertEquals(BigInteger.valueOf(COLLATERAL_LOVELACE), AdaCollateralUsdmFixture.loan().collateralAmount());
        assertTrue(AdaCollateralUsdmFixture.loan().botLiquidatable(), "the bot may liquidate it");

        LenderManagerDatum bond = AdaCollateralUsdmFixture.bondDatum();
        assertTrue(bond.shouldLiquidationConvertToPrincipal(), "the bond asks for pay-in-advance");
        assertEquals(BigInteger.valueOf(40), bond.liquidationFeePerMille());
        assertEquals(USDM, bond.principalAsset());

        assertEquals(REGISTRY.getLoanSpendScriptHash(), paymentCredentialOf(AdaCollateralUsdmFixture.LOAN_ADDRESS),
                "the loan UTxO sits at the shipped registry's loan spend");
        assertEquals(REGISTRY.getLenderManagerSpendScriptHash(),
                paymentCredentialOf(AdaCollateralUsdmFixture.BOND_ADDRESS), "the bond at its lender-manager spend");

        OracleEntry usdm = OracleClients.mainnetTwoVersions().findEntryByOracleToken(USDM_V2_ORACLE_NFT).orElseThrow();
        assertEquals(USDM, usdm.token());
        assertEquals(Integer.valueOf(2), usdm.oracleVersion());
        assertEquals(OraclePriceFeed.Variant.AGGREGATED, usdm.feed().variant(), "multisig");
        assertEquals(USDM_V2_ORACLE_HASH, usdm.withdrawCredentialHash());
        assertEquals("stake1780k72fwe6ggxlmn3mc4d7y5pe0e3keqy7e6l2xjqswyjtgqtk3fg", usdm.rewardAddress());
        assertEquals(new TransactionInput("4e4b5381376bae774459510b16b3cf4b52cf7437addf88d5dd24d167080d9dcd", 2),
                usdm.referenceInput());
        assertEquals(new TransactionInput("ac42d186c3d43b02552afe950c0fd924b5220067b85fe46800e63695c3932de4", 1),
                usdm.referenceScript());
        assertEquals(RECORDED_PRICE, usdm.feed().priceInLovelaces());
        assertEquals(PRICE_DENOMINATOR, usdm.feed().priceDenominator());
    }

    @Test
    void theDeployedUsdmV2OracleIsTheLoansV4OracleAppliedToItsOwnParameters() throws Exception {
        TestOracleRig.Deployed deployed = TestOracleRig.controlHashed("df6f292e", LOANS_V4);
        assertEquals(USDM_V2_ORACLE_HASH, deployed.scriptHash());
        AssertionError other = assertThrows(AssertionError.class, () -> TestOracleRig.controlHashed("df6f292e", AQUARIUM_SC),
                "df6f292e must not reproduce from aquarium-sc.plutus.json");
        assertTrue(other.getMessage().contains(deployed.scriptHash()), other.getMessage());

        OracleEntry entry = OracleClients.mainnetTwoVersions().findEntryByOracleToken(USDM_V2_ORACLE_NFT).orElseThrow();
        assertEquals(entry.verificationKeys().stream().map(String::toLowerCase).toList(),
                deployed.verificationKeysHex().stream().map(String::toLowerCase).toList(),
                "the applied keys are registry entry 21's");
        assertEquals(entry.threshold(), deployed.threshold(), "the applied threshold");
        assertEquals(1, deployed.threshold());

        assertFalse(TestOracleRig.DEPLOYED_ORACLE_HASHES.contains(USDM_V2_ORACLE_HASH),
                "fixture: the rig's known-hash set does not list df6f292e, so this test asserts the difference itself");
        AdaCollateralUsdmFixture.Oracle oracle = AdaCollateralUsdmFixture.oracle(AdaCollateralUsdmFixture.feed(PRICE));
        assertNotEquals(USDM_V2_ORACLE_HASH, oracle.rig().scriptHash(), "a rig is never the deployed USDM v2 oracle");
    }

    // ======================================================================================
    // (c) the deliverable
    // ======================================================================================

    @Test
    void anAdaToUsdmPayInAdvanceEvaluatesAgainstTheDeployedValidatorsThroughTheRigOracle() throws Exception {
        Built b = build(PRICE, tokenOutput(TOKEN_OUTPUT_LOVELACE, PAYOUT.add(LEFTOVER_USDM)));
        Transaction body = b.body();

        // The arithmetic the chain recomputes.
        LiquidatePayInAdvanceTransactionBuilder.Numbers numbers = b.numbers();
        assertEquals(REMAINING_DEBT, numbers.remainingDebt());
        assertEquals(EQUITY, numbers.equity(), "positive equity, in lovelace");
        assertEquals(LIQUIDATION_FEE, numbers.liquidationFee(), "8,100,000,000 lovelace at 40 per mille");
        assertEquals(LENDER_SHOULD_RECEIVE, numbers.collateralLenderShouldReceive());
        assertEquals(PAYOUT, numbers.convertedLoanCollateralToPrincipalAmount(), "ceil(7,304,849,003 / 12.5) USDM");

        // Liquidatable only at the rig's price, against the 1:1 collateral feed.
        LiquidationMode.Liquidation mode = (LiquidationMode.Liquidation) AdaCollateralUsdmFixture.loanDatum().liquidationMode();
        Rational debt = Rational.fromInt(numbers.remainingDebt());
        Rational collateral = Rational.fromInt(BigInteger.valueOf(COLLATERAL_LOVELACE));
        OraclePriceFeed recorded = OracleClients.mainnetTwoVersions().findEntryByOracleToken(USDM_V2_ORACLE_NFT)
                .orElseThrow().feed();
        assertEquals(RECORDED_PRICE, recorded.priceInLovelaces());
        assertFalse(LoanFinance.canLiquidate(debt, collateral, LoanFinance.liquidationLtv(mode), recorded,
                OraclePriceFeed.unit()), "not liquidatable at the recorded USDM price");
        assertTrue(LoanFinance.canLiquidate(debt, collateral, LoanFinance.liquidationLtv(mode),
                b.oracle().leg().signedFeed(), OraclePriceFeed.unit()), "liquidatable at the rig's USDM price");

        // Real ex-units, read off the BUILT transaction.
        assertEquals(1, b.evaluator().calls.get(), "exactly one evaluation per build");
        List<Redeemer> redeemers = body.getWitnessSet().getRedeemers();
        assertEquals(8, redeemers.size(), "two spends, one burn, five withdrawals: " + redeemers);
        assertEquals(2, redeemers.stream().filter(r -> r.getTag() == RedeemerTag.Spend).count(), "the loan, the bond");
        assertEquals(1, redeemers.stream().filter(r -> r.getTag() == RedeemerTag.Mint).count(), "the loan-NFT burn");
        assertEquals(5, redeemers.stream().filter(r -> r.getTag() == RedeemerTag.Reward).count(),
                "loan, loan_claim_action, lenderManager, the pay-in-advance action, ONE oracle leg");
        BigInteger mem = BigInteger.ZERO;
        BigInteger steps = BigInteger.ZERO;
        for (Redeemer redeemer : redeemers) {
            String key = redeemer.getTag() + "#" + redeemer.getIndex();
            EvaluationResult costing = costingFor(b.evaluator().last.get(), redeemer);
            assertEquals(costing.getExUnits().getMem(), redeemer.getExUnits().getMem(), key + " mem");
            assertEquals(costing.getExUnits().getSteps(), redeemer.getExUnits().getSteps(), key + " steps");
            assertTrue(redeemer.getExUnits().getMem().compareTo(PLACEHOLDER) > 0, key + " carries a placeholder mem");
            assertTrue(redeemer.getExUnits().getSteps().compareTo(PLACEHOLDER) > 0, key + " carries placeholder steps");
            mem = mem.add(redeemer.getExUnits().getMem());
            steps = steps.add(redeemer.getExUnits().getSteps());
        }
        int size = body.serialize().length;
        log.info("FAB-226 ADA→USDM pay-in-advance: {} bytes, fee {}, mem {}, steps {}, withdrawals {}, outputs {}",
                size, body.getBody().getFee(), mem, steps, rewardAddresses(body), body.getBody().getOutputs());
        assertTrue(mem.compareTo(BigInteger.valueOf(14_000_000L)) <= 0, "total mem " + mem);
        assertTrue(steps.compareTo(BigInteger.valueOf(10_000_000_000L)) <= 0, "total steps " + steps);
        assertTrue(size < 16_384, "under maxTxSize: " + size);

        // The five withdraw-0 invocations: no collateral oracle among them.
        assertEquals(Set.of(
                        StrikeUsdmFixture.rewardAddress(REGISTRY.getLoanPolicyId()),
                        StrikeUsdmFixture.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()),
                        StrikeUsdmFixture.rewardAddress(REGISTRY.getLenderManagerWithdrawScriptHash()),
                        StrikeUsdmFixture.rewardAddress(REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash()),
                        StrikeUsdmFixture.rewardAddress(b.oracle().rig().scriptHash())),
                new HashSet<>(rewardAddresses(body)), "the five withdrawals");
        assertEquals(5, rewardAddresses(body).size());

        // Every script by reference, none in the witness set; no collateral-oracle reference input.
        assertTrue(witnessedScriptHashes(body).isEmpty(), "no witness-set script: " + witnessedScriptHashes(body));
        Set<String> expectedReferenced = new HashSet<>();
        StrikeUsdmFixture.registryReferenceScripts().forEach(s -> expectedReferenced.add(StrikeUsdmFixture.hashOf(s)));
        expectedReferenced.add(b.oracle().rig().scriptHash());
        assertEquals(expectedReferenced, referencedScriptHashes(body, b.universe()),
                "the six registry scripts and the rig oracle, by reference");
        assertEquals(10, body.getBody().getReferenceInputs().size(),
                "two configs, six registry scripts, the rig's NFT holder and script holder: "
                        + body.getBody().getReferenceInputs());

        // The asset-manager outputs.
        List<TransactionOutput> assetOutputs = assetManagerOutputs(body);
        assertEquals(2, assetOutputs.size(), "a borrower compensation and the lender's payout");
        TransactionOutput borrower = assetOutputs.get(0);
        assertTrue(borrower.getValue().getCoin().compareTo(EQUITY) >= 0,
                "the borrower's equity, in lovelace: " + borrower.getValue().getCoin());
        assertEquals(1, flattenedCount(borrower), "ADA-only (dosProtection flatten == 1 for the ada policy)");
        TransactionOutput lender = assetOutputs.get(payInAdvanceAssetOutputIndexes(body).get(0).intValueExact());
        assertTrue(quantityOf(lender, USDM).compareTo(PAYOUT) >= 0, "the lender's USDM: " + quantityOf(lender, USDM));
        assertEquals(2, flattenedCount(lender), "USDM plus its min-ada rider (dosProtection flatten == 2)");
        TransactionOutput echo = onlyOutputAt(body, REGISTRY.getLenderManagerSpendScriptHash());
        assertEquals(AdaCollateralUsdmFixture.bondDatumHex(), echo.getInlineDatum().serializeToHex(),
                "the bond echo datum is byte-identical to its input");

        // The bot's outputs: the leftover USDM in its own output, the seized ADA ADA-only.
        List<TransactionOutput> tokenBearing = botOutputs(body).stream().filter(o -> !adaOnly(o)).toList();
        assertEquals(1, tokenBearing.size(), "exactly one token-bearing bot output: " + botOutputs(body));
        TransactionOutput remainder = tokenBearing.get(0);
        assertEquals(Map.of(USDM.toUnit(), LEFTOVER_USDM), assetsOf(remainder),
                "the remainder holds exactly the 5,000,000 leftover USDM and nothing else");
        assertTrue(remainder.getValue().getCoin().compareTo(lovelaceOf(b.token())) <= 0,
                "the remainder holds no more lovelace than the token output did: " + remainder.getValue().getCoin());
        BigInteger adaOnlyToBot = botOutputs(body).stream().filter(PayInAdvanceAdaCollateralDryEvalTest::adaOnly)
                .map(o -> o.getValue().getCoin()).reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger assetManagerCoin = assetOutputs.stream().map(o -> o.getValue().getCoin())
                .reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger seizedFloor = BigInteger.valueOf(COLLATERAL_LOVELACE).subtract(assetManagerCoin)
                .subtract(body.getBody().getFee());
        assertTrue(adaOnlyToBot.compareTo(seizedFloor) >= 0, "the seized ADA came back ADA-only: "
                + adaOnlyToBot + " ADA-only lovelace to the bot against " + seizedFloor);
        assertTrue(seizedFloor.compareTo(ACQUIRED.subtract(BigInteger.valueOf(10_000_000L))) > 0,
                "the floor is the acquired lovelace less riders and fee: " + seizedFloor);

        assertAdaCollateralBuild(body, b.universe(), b.token(), b.c(), b.u());
        assertTheOneOracleLegReadsTheCredentialItWithdrawsFrom(body, b.universe(), b.oracle());
    }

    // ======================================================================================
    // (d), (e) funding and the equity-zero shape
    // ======================================================================================

    /**
     * The seized ADA funds every ADA need of this shape — the fee, the lender's min-ada rider, the remainder's
     * min-ada and the change — so a token output carrying little more than its own ledger min-ada builds.
     * <p>
     * Measured for this ticket (rig price 12.5): a token output of 1,100,000, 1,200,000, 1,300,000, 1,500,000,
     * 2,000,000 or 3,000,000 lovelace, each with exactly the payout or the payout + 5 USDM, builds, and spends only
     * the token output from the wallet. The ledger min-ada of that token output is about 1.17 ADA, so this test
     * uses 1,500,000.
     */
    @Test
    void theSeizedAdaFundsThisShapeSoAThinTokenOutputBuilds() throws Exception {
        for (BigInteger usdm : List.of(PAYOUT, PAYOUT.add(LEFTOVER_USDM))) {
            Built b = build(PRICE, tokenOutput(1_500_000L, usdm));
            Transaction body = b.body();
            log.info("FAB-226 thin token output of 1.5 ADA + {} USDM: fee {}, outputs {}", usdm, body.getBody().getFee(),
                    botOutputs(body));
            assertAdaCollateralBuild(body, b.universe(), b.token(), b.c(), b.u());
            List<TransactionOutput> tokenBearing = botOutputs(body).stream().filter(o -> !adaOnly(o)).toList();
            if (usdm.equals(PAYOUT)) {
                assertEquals(List.of(), tokenBearing, "no leftover, so every bot output is ADA-only");
            } else {
                assertEquals(1, tokenBearing.size(), "the leftover USDM alone in its own output");
                assertEquals(Map.of(USDM.toUnit(), LEFTOVER_USDM), assetsOf(tokenBearing.get(0)));
            }
        }
    }

    @Test
    void theEquityZeroCaseEmitsNoCompensationAndEvaluates() throws Exception {
        Built b = build(EQUITY_ZERO_PRICE, tokenOutput(TOKEN_OUTPUT_LOVELACE, EQUITY_ZERO_PAYOUT.add(LEFTOVER_USDM)));
        assertEquals(BigInteger.ZERO, b.numbers().equity(), "underwater at 15 ADA per USDM: equity 0");
        assertEquals(EQUITY_ZERO_PAYOUT, b.numbers().convertedLoanCollateralToPrincipalAmount(),
                "(8,100,000,000 − 324,000,000) / 15 USDM");
        assertEquals(1, b.evaluator().calls.get(), "exactly one evaluation");
        for (Redeemer redeemer : b.body().getWitnessSet().getRedeemers()) {
            assertTrue(redeemer.getExUnits().getMem().compareTo(PLACEHOLDER) > 0, redeemer + " carries a placeholder");
        }
        List<TransactionOutput> assetOutputs = assetManagerOutputs(b.body());
        assertEquals(1, assetOutputs.size(), "the lender's payout alone: " + assetOutputs);
        assertTrue(quantityOf(assetOutputs.get(0), USDM).compareTo(EQUITY_ZERO_PAYOUT) >= 0);
        assertEquals(List.of(BigInteger.ZERO), payInAdvanceAssetOutputIndexes(b.body()), "the lender at filtered slot 0");
        assertAdaCollateralBuild(b.body(), b.universe(), b.token(), b.c(), b.u());
    }

    // ======================================================================================
    // (f), (g), (h) negative controls, each naming its refuser
    // ======================================================================================

    @Test
    void aLenderPayoutOneUnitShortIsRefusedByThePayInAdvanceAction() throws Exception {
        Built b = build(PRICE, tokenOutput(TOKEN_OUTPUT_LOVELACE, PAYOUT.add(LEFTOVER_USDM)));
        Transaction mutated = Transaction.deserialize(b.body().serialize());
        TransactionOutput lender = assetManagerOutputs(mutated)
                .get(payInAdvanceAssetOutputIndexes(mutated).get(0).intValueExact());
        Asset usdm = assetOf(lender, USDM);
        assertEquals(PAYOUT, usdm.getValue(), "the built body pays the lender exactly the payout");
        usdm.setValue(usdm.getValue().subtract(BigInteger.ONE));

        String detail = refusal(mutated, b);
        String expected = redeemerError(mutated, REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash());
        assertTrue(detail.contains(expected), "expected " + expected + " to be the refuser, got: " + detail);
    }

    @Test
    void aBorrowerCompensationOneLovelaceShortIsRefusedByLoanClaimAction() throws Exception {
        Built b = build(PRICE, tokenOutput(TOKEN_OUTPUT_LOVELACE, PAYOUT.add(LEFTOVER_USDM)));
        Transaction mutated = Transaction.deserialize(b.body().serialize());
        TransactionOutput borrower = assetManagerOutputs(mutated).get(0);
        assertEquals(EQUITY, borrower.getValue().getCoin(), "the built body pays the borrower exactly the equity");
        borrower.getValue().setCoin(EQUITY.subtract(BigInteger.ONE));

        String detail = refusal(mutated, b);
        String expected = redeemerError(mutated, REGISTRY.getLoanClaimActionScriptHash());
        assertTrue(detail.contains(expected), "expected " + expected + " to be the refuser, got: " + detail);
    }

    /**
     * The USDM feed covers [T0 − 200 s, T0 + 600 s]. Moved to slot(T0 + 660 s) the body is refused at the
     * pay-in-advance action's withdrawal; moved to slot(T0 + 540 s) it still evaluates.
     */
    @Test
    void aTxWindowPastThePrincipalFeedsValidToIsRefusedWhileOneInsideItEvaluates() throws Exception {
        Built b = build(PRICE, tokenOutput(TOKEN_OUTPUT_LOVELACE, PAYOUT.add(LEFTOVER_USDM)));
        long validTo = b.oracle().leg().signedFeed().validTo();
        assertTrue(T0 + 660_000L > validTo && T0 + 540_000L < validTo, "the two TTLs straddle the feed's valid_to");

        Transaction late = Transaction.deserialize(b.body().serialize());
        late.getBody().setTtl(StrikeUsdmFixture.slot(T0 + 660_000L));
        String detail = refusal(late, b);
        String expected = redeemerError(late, REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash());
        assertTrue(detail.contains(expected), "expected " + expected + " to be the refuser, got: " + detail);

        Transaction inside = Transaction.deserialize(b.body().serialize());
        inside.getBody().setTtl(StrikeUsdmFixture.slot(T0 + 540_000L));
        Result<List<EvaluationResult>> control = evaluate(inside, b);
        assertTrue(control.isSuccessful(), "the control inside the feed must evaluate: " + control.getResponse());
    }

    // ======================================================================================
    // (i) the collateral oracle must match the collateral
    // ======================================================================================

    @Test
    void aCollateralOracleMismatchIsRefusedByNameBeforeAnythingIsPriced() throws Exception {
        // This ADA-collateral loan, handed the rig entry as its collateral oracle.
        AdaCollateralUsdmFixture.Oracle oracle = AdaCollateralUsdmFixture.oracle(AdaCollateralUsdmFixture.feed(PRICE));
        Utxo c = StrikeUsdmFixture.collateral(BOT_ADDRESS);
        Utxo token = tokenOutput(TOKEN_OUTPUT_LOVELACE, PAYOUT.add(LEFTOVER_USDM));
        List<Utxo> universe = AdaCollateralUsdmFixture.universe(oracle, List.of(c, StrikeUsdmFixture.u(BOT_ADDRESS), token));
        Recording evaluator = new Recording(AdaCollateralUsdmFixture.aiken(universe, oracle));
        var adaWithOracle = withCollateralOracle(
                AdaCollateralUsdmFixture.request(oracle, token, c, BOT_ADDRESS, T0, TX_VALID_TO), oracle.leg().entry());
        IllegalStateException ada = assertThrows(IllegalStateException.class,
                () -> StrikeUsdmFixture.production(universe,
                        new HashCheckedScriptSupplier(AdaCollateralUsdmFixture.rawScripts(oracle)), evaluator)
                        .build(adaWithOracle));
        log.info("FAB-226 ADA collateral with an oracle: {}", ada.getMessage());
        assertTrue(ada.getMessage().startsWith("COLLATERAL_ORACLE_MISMATCH:"), "refused by name: "
                + LiquidationExecutor.causeChain(ada));
        assertEquals(0, evaluator.calls.get(), "refused before anything is evaluated");

        // StrikeUsdmFixture's token-collateral loan #0, handed no collateral oracle.
        StrikeUsdmFixture.Legs legs = StrikeUsdmFixture.legs(StrikeUsdmFixture.collateralFeed(),
                StrikeUsdmFixture.principalFeed());
        Utxo strikeToken = StrikeUsdmFixture.tokenOutput(BOT_ADDRESS, 7_905_077L, BigInteger.valueOf(800_000_000L));
        List<Utxo> strikeUniverse = StrikeUsdmFixture.universe(0, legs,
                List.of(c, StrikeUsdmFixture.u(BOT_ADDRESS), strikeToken));
        Recording strikeEvaluator = new Recording(StrikeUsdmFixture.aiken(strikeUniverse, legs));
        var tokenWithoutOracle = withCollateralOracle(
                StrikeUsdmFixture.request(0, legs, strikeToken, c, BOT_ADDRESS, StrikeUsdmFixture.T0,
                        StrikeUsdmFixture.T0 + 120_000L), null);
        IllegalStateException strike = assertThrows(IllegalStateException.class,
                () -> StrikeUsdmFixture.production(strikeUniverse,
                        new HashCheckedScriptSupplier(StrikeUsdmFixture.rawScripts(legs)), strikeEvaluator)
                        .build(tokenWithoutOracle));
        log.info("FAB-226 token collateral without an oracle: {}", strike.getMessage());
        assertTrue(strike.getMessage().startsWith("COLLATERAL_ORACLE_MISMATCH:"), "refused by name: "
                + LiquidationExecutor.causeChain(strike));
        assertEquals(0, strikeEvaluator.calls.get(), "refused before anything is evaluated");
    }

    // ======================================================================================
    // (j) H2 — the production wiring
    // ======================================================================================

    /**
     * Through {@code YaciConfig}'s bean method, the Spring-wired shape: the probe is asked once, about the principal
     * leg alone — an ADA collateral has no oracle out-ref to probe, and a null element would be a defect. The fee is
     * bounded below by an independent reconstruction (size, constant, execution budget and the Conway tiered
     * reference-script fee over all seven referenced scripts) and above by 256 bytes of size fee; against a supplier
     * that serves nothing it falls by at least the whole tiered fee, and the rig script's marginal share exceeds
     * the 88-lovelace tolerance.
     */
    @Test
    void theProductionWiringPricesTheOracleReferenceScriptAndProbesOnlyThePrincipalLeg() throws Exception {
        AdaCollateralUsdmFixture.Oracle oracle = AdaCollateralUsdmFixture.oracle(AdaCollateralUsdmFixture.feed(PRICE));
        Utxo c = StrikeUsdmFixture.collateral(BOT_ADDRESS);
        Utxo u = StrikeUsdmFixture.u(BOT_ADDRESS);
        Utxo token = tokenOutput(TOKEN_OUTPUT_LOVELACE, PAYOUT.add(LEFTOVER_USDM));
        List<Utxo> universe = AdaCollateralUsdmFixture.universe(oracle, List.of(c, u, token));
        var request = AdaCollateralUsdmFixture.request(oracle, token, c, BOT_ADDRESS, T0, TX_VALID_TO);

        OracleReferenceInputProbe probe = mock(OracleReferenceInputProbe.class);
        Recording evaluator = new Recording(AdaCollateralUsdmFixture.aiken(universe, oracle));
        Transaction priced = Transaction.deserialize(bean(universe,
                new HashCheckedScriptSupplier(AdaCollateralUsdmFixture.rawScripts(oracle)), evaluator, probe)
                .build(request).serialize());
        verify(probe).requireLive(List.of(oracle.leg().entry()));
        verifyNoMoreInteractions(probe);
        assertEquals(1, evaluator.calls.get(), "exactly one evaluation");
        assertAdaCollateralBuild(priced, universe, token, c, u);

        ScriptSupplier servesNothing = hash -> Optional.empty();
        Transaction unpriced = Transaction.deserialize(bean(universe, servesNothing,
                new Recording(AdaCollateralUsdmFixture.aiken(universe, oracle)), mock(OracleReferenceInputProbe.class))
                .build(request).serialize());

        BigDecimal perByte = LoanFixtures.protocolParams().getProtocolParams().getMinFeeRefScriptCostPerByte();
        assertEquals(0, new BigDecimal("15").compareTo(perByte), "minFeeRefScriptCostPerByte");
        List<PlutusScript> referenced = new ArrayList<>(StrikeUsdmFixture.registryReferenceScripts());
        referenced.add(oracle.rig().script());
        long refBytes = bytesOf(referenced);
        BigInteger tiered = tieredRefScriptFee(perByte, refBytes);

        BigInteger mem = BigInteger.ZERO;
        BigInteger steps = BigInteger.ZERO;
        for (Redeemer redeemer : priced.getWitnessSet().getRedeemers()) {
            mem = mem.add(redeemer.getExUnits().getMem());
            steps = steps.add(redeemer.getExUnits().getSteps());
        }
        BigInteger exUnitsFee = new BigDecimal("0.0577").multiply(new BigDecimal(mem))
                .add(new BigDecimal("0.0000721").multiply(new BigDecimal(steps)))
                .setScale(0, RoundingMode.CEILING).toBigIntegerExact();
        int size = priced.serialize().length;
        BigInteger lower = BigInteger.valueOf(44L * size + 155_381L).add(exUnitsFee).add(tiered);
        BigInteger fee = priced.getBody().getFee();
        BigInteger delta = fee.subtract(unpriced.getBody().getFee());
        log.info("FAB-226 H2: fee {} lower {} (size {} B, mem {}, steps {}, ref {} B tiered {}) unpriced {} delta {}",
                fee, lower, size, mem, steps, refBytes, tiered, unpriced.getBody().getFee(), delta);

        assertTrue(refBytes > 25_600L, "the shape must exercise the tier ladder: " + refBytes + " B");
        BigInteger slack = fee.subtract(lower);
        assertTrue(slack.signum() >= 0, "the fee " + fee + " is below the independent floor " + lower);
        assertTrue(slack.compareTo(BigInteger.valueOf(44L * 256)) <= 0,
                "the fee " + fee + " exceeds the floor " + lower + " by more than 256 bytes of size fee");
        BigInteger tolerance = BigInteger.valueOf(2 * 44);
        assertTrue(delta.compareTo(tiered.subtract(tolerance)) >= 0, "the reference-script fee charged (" + delta
                + ") is below the tiered fee for all seven referenced scripts (" + tiered + ")");
        List<PlutusScript> without = new ArrayList<>(referenced);
        without.remove(oracle.rig().script());
        BigInteger share = tiered.subtract(tieredRefScriptFee(perByte, bytesOf(without)));
        assertTrue(share.compareTo(tolerance) > 0, "the rig's share " + share + " would hide under the tolerance");
    }

    // ======================================================================================
    // (k), (l) the change shape
    // ======================================================================================

    /**
     * The change-shape check on its own: the leftover USDM folded back into the change — the body CCL would build
     * without the named remainder output, or with {@code mergeOutputs} on — puts the seized ADA in a token-bearing
     * output, which the next build cannot spend as an ADA input. {@code assertStructure} refuses it by name.
     */
    @Test
    void aRemainderFoldedIntoTheChangeIsRefusedByTheChangeShapeCheck() throws Exception {
        Built b = build(PRICE, tokenOutput(TOKEN_OUTPUT_LOVELACE, PAYOUT.add(LEFTOVER_USDM)));
        LiquidatePayInAdvanceTransactionBuilder builder = StrikeUsdmFixture.production(b.universe(), b.scripts(),
                b.evaluator());
        // Control: the built body passes the check as it stands.
        builder.assertStructure(Transaction.deserialize(b.body().serialize()), b.request(), b.numbers(),
                OutputLayout.CCL_PREPENDED_OUTPUTS, 1L);

        Transaction folded = Transaction.deserialize(b.body().serialize());
        List<TransactionOutput> outputs = new ArrayList<>(folded.getBody().getOutputs());
        TransactionOutput remainder = outputs.stream()
                .filter(o -> BOT_ADDRESS.equals(o.getAddress()) && !adaOnly(o)).findFirst().orElseThrow();
        TransactionOutput change = outputs.stream()
                .filter(o -> BOT_ADDRESS.equals(o.getAddress()) && adaOnly(o))
                .max(Comparator.comparing(o -> o.getValue().getCoin())).orElseThrow();
        change.getValue().setMultiAssets(new ArrayList<>(remainder.getValue().getMultiAssets()));
        outputs.remove(outputs.indexOf(remainder));
        folded.getBody().setOutputs(outputs);

        IllegalStateException refused = assertThrows(IllegalStateException.class, () -> builder.assertStructure(
                folded, b.request(), b.numbers(), OutputLayout.CCL_PREPENDED_OUTPUTS, 1L));
        log.info("FAB-226 folded remainder: {}", refused.getMessage());
        assertTrue(refused.getMessage().startsWith("STRUCTURAL_ASSERTION_FAILED"), refused.getMessage());
        assertTrue(refused.getMessage().contains("seized ada"), refused.getMessage());
    }

    @Test
    void aTokenOutputCarryingASecondTokenReturnsBothInTheNamedRemainder() throws Exception {
        Utxo token = LoanFixtures.utxo("e1".repeat(32), 0, BOT_ADDRESS, List.of(
                Amount.lovelace(BigInteger.valueOf(3_000_000L)),
                Amount.asset(USDM.toUnit(), PAYOUT.add(LEFTOVER_USDM)),
                Amount.asset(STRIKE.toUnit(), BigInteger.valueOf(1_000L))), null);
        Built b = build(PRICE, token);
        log.info("FAB-226 two-token output: bot outputs {}", botOutputs(b.body()));

        List<TransactionOutput> tokenBearing = botOutputs(b.body()).stream().filter(o -> !adaOnly(o)).toList();
        assertEquals(1, tokenBearing.size(), "one remainder: " + botOutputs(b.body()));
        assertEquals(Map.of(USDM.toUnit(), LEFTOVER_USDM, STRIKE.toUnit(), BigInteger.valueOf(1_000L)),
                assetsOf(tokenBearing.get(0)), "the remainder carries both tokens, no unit dropped");
        assertAdaCollateralBuild(b.body(), b.universe(), b.token(), b.c(), b.u());
    }

    // ======================================================================================
    // the positive build and its helpers
    // ======================================================================================

    /** The positive build's ingredients and its deserialised body. */
    private record Built(AdaCollateralUsdmFixture.Oracle oracle, List<Utxo> universe, Utxo token, Utxo c, Utxo u,
                         ScriptSupplier scripts, Recording evaluator,
                         LiquidatePayInAdvanceTransactionBuilder.Request request,
                         LiquidatePayInAdvanceTransactionBuilder.Numbers numbers, Transaction body) {
    }

    /** The token output at the bot: {@code lovelace} plus {@code usdm} units of USDM. */
    private static Utxo tokenOutput(long lovelace, BigInteger usdm) {
        return StrikeUsdmFixture.tokenOutput(BOT_ADDRESS, lovelace, usdm);
    }

    /** The rig leg at {@code price}, a wallet {C, U, token} at the bot, a [T0, T0 + 120 s] window, the production constructor. */
    private static Built build(BigInteger price, Utxo token) throws Exception {
        AdaCollateralUsdmFixture.Oracle oracle = AdaCollateralUsdmFixture.oracle(AdaCollateralUsdmFixture.feed(price));
        Utxo c = StrikeUsdmFixture.collateral(BOT_ADDRESS);
        Utxo u = StrikeUsdmFixture.u(BOT_ADDRESS);
        List<Utxo> universe = AdaCollateralUsdmFixture.universe(oracle, List.of(c, u, token));
        ScriptSupplier scripts = new HashCheckedScriptSupplier(AdaCollateralUsdmFixture.rawScripts(oracle));
        Recording evaluator = new Recording(AdaCollateralUsdmFixture.aiken(universe, oracle));
        var request = AdaCollateralUsdmFixture.request(oracle, token, c, BOT_ADDRESS, T0, TX_VALID_TO);
        LiquidatePayInAdvanceTransactionBuilder builder = StrikeUsdmFixture.production(universe, scripts, evaluator);
        LiquidatePayInAdvanceTransactionBuilder.Numbers numbers = builder.numbers(request);
        Transaction built = builder.build(request);
        return new Built(oracle, universe, token, c, u, scripts, evaluator, request, numbers,
                Transaction.deserialize(built.serialize()));
    }

    /** {@code YaciConfig}'s pay-in-advance builder bean method, as the container calls it. */
    private static LiquidatePayInAdvanceTransactionBuilder bean(List<Utxo> universe, ScriptSupplier scripts,
                                                                TransactionEvaluator evaluator,
                                                                OracleReferenceInputProbe probe) {
        AppConfig.Network mainnet = new AppConfig.Network();
        mainnet.setNetworkForTest("mainnet");
        return new YaciConfig().liquidatePayInAdvanceTransactionBuilder(REGISTRY, mainnet,
                LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams(), scripts, evaluator, probe);
    }

    /** {@code r} with its collateral oracle replaced; every other field as it was. */
    private static LiquidatePayInAdvanceTransactionBuilder.Request withCollateralOracle(
            LiquidatePayInAdvanceTransactionBuilder.Request r, OracleEntry oracle) {
        return new LiquidatePayInAdvanceTransactionBuilder.Request(r.loan(), r.loanUtxo(), r.bond(), r.bondUtxo(),
                r.walletUtxo(), r.configUtxo(), r.lmConfigUtxo(), oracle, r.principalOracle(), r.validFromMillis(),
                r.validToMillis(), r.validFromSlot(), r.validToSlot(), r.changeAddress(), r.referenceScripts(),
                r.oracleWindowMarginMillis(), r.collateralUtxo());
    }

    /**
     * Every successful ADA-collateral build: the token output is the only wallet input; C is the only collateral and
     * is never spent, nor is U; per-asset conservation holds; no token-bearing bot output holds more lovelace than
     * the token output did; and at least one ADA-only output returns to the bot.
     */
    private static void assertAdaCollateralBuild(Transaction body, List<Utxo> universe, Utxo token, Utxo c, Utxo u) {
        assertEquals(List.of(ref(token)), walletInputs(body, universe), "the wallet spends its token output alone");
        assertEquals(List.of(ref(c)), collateralRefs(body), "the collateral is exactly C");
        assertFalse(inputRefs(body).contains(ref(c)), "C is never spent");
        assertFalse(inputRefs(body).contains(ref(u)), "U is never spent");
        assertConserved(body, universe);
        for (TransactionOutput output : botOutputs(body)) {
            if (!adaOnly(output)) {
                assertTrue(output.getValue().getCoin().compareTo(lovelaceOf(token)) <= 0,
                        "a token-bearing bot output holds more lovelace than the token output did: " + output);
            }
        }
        assertTrue(botOutputs(body).stream().anyMatch(PayInAdvanceAdaCollateralDryEvalTest::adaOnly),
                "at least one ADA-only output to the bot: " + botOutputs(body));
    }

    /**
     * H1 — the one oracle leg reads the credential it withdraws from. The claim redeemer's principal oracle index
     * points, in the canonically ordered reference inputs, at the leg's NFT holder, whose payment credential is the
     * rig's script hash and the delegation credential of exactly one withdrawal. The collateral index is 0 and in
     * range — the validators evaluate {@code safe_list_at} on it and ignore the content for the ada policy — and
     * the pay-in-advance redeemer's {@code [principal, collateral]} pair cites the same two indexes.
     */
    private static void assertTheOneOracleLegReadsTheCredentialItWithdrawsFrom(Transaction body, List<Utxo> universe,
                                                                               AdaCollateralUsdmFixture.Oracle oracle) {
        List<TransactionInput> refInputs = body.getBody().getReferenceInputs();
        List<TransactionInput> canonical = refInputs.stream()
                .sorted(Comparator.comparing(TransactionInput::getTransactionId)
                        .thenComparingInt(TransactionInput::getIndex)).toList();
        assertEquals(canonical, refInputs, "the body's reference inputs are in the ledger's canonical order");

        ConstrPlutusData claimRedeemer = (ConstrPlutusData) rewardRedeemer(body,
                REGISTRY.getLoanClaimActionScriptHash()).getData();
        ListPlutusData claims = (ListPlutusData) claimRedeemer.getData().getPlutusDataList().get(1);
        assertEquals(1, claims.getPlutusDataList().size(), "one loan input, one claim");
        List<PlutusData> claim = ((ConstrPlutusData) claims.getPlutusDataList().get(0)).getData().getPlutusDataList();
        int collateralIndex = ((BigIntPlutusData) claim.get(2)).getValue().intValueExact();
        int principalIndex = ((BigIntPlutusData) claim.get(3)).getValue().intValueExact();

        assertEquals(0, collateralIndex, "the ADA collateral's oracle index is 0");
        assertTrue(collateralIndex < refInputs.size(), "and in range");
        assertEquals(oracle.leg().entry().referenceInput(), refInputs.get(principalIndex),
                "the principal index points at the rig leg's NFT holder");
        String credential = paymentCredentialOf(resolve(refInputs.get(principalIndex), universe).getAddress());
        assertEquals(oracle.rig().scriptHash(), credential, "the principal leg's reference input sits at the rig");
        String reward = StrikeUsdmFixture.rewardAddress(credential);
        assertEquals(1, rewardAddresses(body).stream().filter(reward::equals).count(),
                credential + " must be the delegation credential of exactly one withdrawal");

        ConstrPlutusData action = (ConstrPlutusData) rewardRedeemer(body,
                REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash()).getData();
        ListPlutusData pairs = (ListPlutusData) action.getData().getPlutusDataList().get(3);
        assertEquals(1, pairs.getPlutusDataList().size(), "one loan input, one oracle pair");
        List<PlutusData> pair = ((ListPlutusData) pairs.getPlutusDataList().get(0)).getPlutusDataList();
        assertEquals(List.of(BigInteger.valueOf(principalIndex), BigInteger.ZERO),
                pair.stream().map(p -> ((BigIntPlutusData) p).getValue()).toList(),
                "the pay-in-advance action's [principal, collateral] pair is the claim's");
    }

    /** The same real evaluator over the same universe, on a body edited after the build. */
    private static Result<List<EvaluationResult>> evaluate(Transaction tx, Built b) throws Exception {
        AikenTransactionEvaluator aiken = AdaCollateralUsdmFixture.aiken(b.universe(), b.oracle());
        return aiken.evaluateTx(tx.serialize(), new LinkedHashSet<>(b.universe()));
    }

    /** The evaluator's refusal text; fails the test if the edited body evaluates. */
    private static String refusal(Transaction tx, Built b) {
        try {
            Result<List<EvaluationResult>> result = evaluate(tx, b);
            assertFalse(result.isSuccessful(), "the edited body must be refused, and evaluated: "
                    + result.getResponse());
            return String.valueOf(result.getResponse());
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            return LiquidationExecutor.causeChain(e);
        }
    }

    /** CCL trap 26: for every unit, inputs (+ mint) == outputs (+ fee), and no output holds a negative quantity. */
    private static void assertConserved(Transaction body, List<Utxo> universe) {
        Map<String, BigInteger> supply = new HashMap<>();
        for (TransactionInput input : body.getBody().getInputs()) {
            resolve(input, universe).getAmount()
                    .forEach(amount -> supply.merge(amount.getUnit(), amount.getQuantity(), BigInteger::add));
        }
        if (body.getBody().getMint() != null) {
            body.getBody().getMint().forEach(multiAsset -> multiAsset.getAssets().forEach(asset -> supply.merge(
                    multiAsset.getPolicyId() + HexUtil.encodeHexString(asset.getNameAsBytes()), asset.getValue(),
                    BigInteger::add)));
        }
        Map<String, BigInteger> demand = new HashMap<>();
        demand.merge(AssetType.LOVELACE, body.getBody().getFee(), BigInteger::add);
        for (TransactionOutput output : body.getBody().getOutputs()) {
            assertTrue(output.getValue().getCoin().signum() >= 0, "an output holds negative ada: " + output);
            demand.merge(AssetType.LOVELACE, output.getValue().getCoin(), BigInteger::add);
            if (output.getValue().getMultiAssets() != null) {
                output.getValue().getMultiAssets().forEach(multiAsset -> multiAsset.getAssets().forEach(asset -> {
                    assertTrue(asset.getValue().signum() >= 0, "an output holds a negative quantity: " + output);
                    demand.merge(multiAsset.getPolicyId() + HexUtil.encodeHexString(asset.getNameAsBytes()),
                            asset.getValue(), BigInteger::add);
                }));
            }
        }
        supply.values().removeIf(v -> v.signum() == 0);
        demand.values().removeIf(v -> v.signum() == 0);
        assertEquals(supply, demand, "inputs (+ mint) must equal outputs (+ fee), per unit");
    }

    /** Records how often it was asked, every body it was shown, and what it last returned. */
    private static final class Recording implements TransactionEvaluator {
        final TransactionEvaluator delegate;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<List<EvaluationResult>> last = new AtomicReference<>();
        final List<Transaction> bodies = new CopyOnWriteArrayList<>();

        Recording(TransactionEvaluator delegate) {
            this.delegate = delegate;
        }

        @Override
        public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos)
                throws com.bloxbean.cardano.client.api.exception.ApiException {
            calls.incrementAndGet();
            try {
                bodies.add(Transaction.deserialize(cbor));
            } catch (Exception e) {
                throw new AssertionError("the evaluator was handed bytes that do not deserialise", e);
            }
            Result<List<EvaluationResult>> result = delegate.evaluateTx(cbor, inputUtxos);
            last.set(result.getValue());
            return result;
        }
    }

    private static EvaluationResult costingFor(List<EvaluationResult> results, Redeemer redeemer) {
        return results.stream()
                .filter(r -> r.getRedeemerTag() == redeemer.getTag() && r.getIndex() == redeemer.getIndex().intValue())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no evaluation for " + redeemer.getTag() + "#" + redeemer.getIndex()));
    }

    /**
     * The Conway reference-script fee ladder: {@code perByte} for each of the first 25,600 bytes, then the
     * rate × 1.2 for each further 25,600-byte tier, rounded up.
     */
    private static BigInteger tieredRefScriptFee(BigDecimal perByte, long bytes) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal rate = perByte;
        long remaining = bytes;
        while (remaining > 0) {
            long tier = Math.min(25_600L, remaining);
            total = total.add(rate.multiply(BigDecimal.valueOf(tier)));
            rate = rate.multiply(new BigDecimal("1.2"));
            remaining -= tier;
        }
        return total.setScale(0, RoundingMode.CEILING).toBigIntegerExact();
    }

    private static long bytesOf(List<PlutusScript> scripts) throws Exception {
        long bytes = 0;
        for (PlutusScript script : scripts) {
            bytes += script.scriptRefBytes().length;
        }
        return bytes;
    }

    // ---- reading the body ------------------------------------------------------------------------

    private static Utxo resolve(TransactionInput input, List<Utxo> universe) {
        return universe.stream()
                .filter(u -> u.getTxHash().equals(input.getTransactionId()) && u.getOutputIndex() == input.getIndex())
                .findFirst()
                .orElseThrow(() -> new AssertionError("an out-ref the universe does not hold: " + input));
    }

    private static String ref(Utxo utxo) {
        return utxo.getTxHash() + "#" + utxo.getOutputIndex();
    }

    private static List<String> inputRefs(Transaction body) {
        return body.getBody().getInputs().stream().map(in -> in.getTransactionId() + "#" + in.getIndex()).toList();
    }

    private static List<String> collateralRefs(Transaction body) {
        return body.getBody().getCollateral() == null ? List.of() : body.getBody().getCollateral().stream()
                .map(in -> in.getTransactionId() + "#" + in.getIndex()).toList();
    }

    /** The refs of {@code body}'s inputs whose universe UTxO is at the bot's payment credential, in body order. */
    private static List<String> walletInputs(Transaction body, List<Utxo> universe) {
        String wallet = paymentCredentialOf(BOT_ADDRESS);
        List<String> spent = new ArrayList<>();
        for (TransactionInput input : body.getBody().getInputs()) {
            if (wallet.equals(paymentCredentialOf(resolve(input, universe).getAddress()))) {
                spent.add(input.getTransactionId() + "#" + input.getIndex());
            }
        }
        return spent;
    }

    private static List<TransactionOutput> botOutputs(Transaction body) {
        return body.getBody().getOutputs().stream().filter(o -> BOT_ADDRESS.equals(o.getAddress())).toList();
    }

    private static Set<String> referencedScriptHashes(Transaction body, List<Utxo> universe) {
        Set<String> hashes = new HashSet<>();
        for (TransactionInput input : body.getBody().getReferenceInputs()) {
            String hash = resolve(input, universe).getReferenceScriptHash();
            if (hash != null) {
                hashes.add(hash);
            }
        }
        return hashes;
    }

    private static Set<String> witnessedScriptHashes(Transaction tx) {
        Set<String> hashes = new HashSet<>();
        Stream.of(tx.getWitnessSet().getPlutusV1Scripts(), tx.getWitnessSet().getPlutusV2Scripts(),
                        tx.getWitnessSet().getPlutusV3Scripts())
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream)
                .forEach(script -> hashes.add(StrikeUsdmFixture.hashOf(script)));
        if (tx.getWitnessSet().getNativeScripts() != null && !tx.getWitnessSet().getNativeScripts().isEmpty()) {
            hashes.add("native:" + tx.getWitnessSet().getNativeScripts().size());
        }
        return hashes;
    }

    private static List<String> rewardAddresses(Transaction body) {
        return body.getBody().getWithdrawals().stream().map(Withdrawal::getRewardAddress).toList();
    }

    private static int withdrawalIndexOf(Transaction body, String scriptHash) {
        List<String> rewards = rewardAddresses(body);
        int index = rewards.indexOf(StrikeUsdmFixture.rewardAddress(scriptHash));
        if (index < 0) {
            throw new AssertionError("no withdrawal at " + scriptHash + ": " + rewards);
        }
        return index;
    }

    private static Redeemer rewardRedeemer(Transaction body, String scriptHash) {
        int index = withdrawalIndexOf(body, scriptHash);
        return body.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == RedeemerTag.Reward && r.getIndex().intValue() == index)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no reward redeemer for " + scriptHash));
    }

    /** The prefix the evaluator reports for a refusal by the withdrawal of {@code scriptHash} in {@code body}. */
    private static String redeemerError(Transaction body, String scriptHash) {
        return "RedeemerError { tag: \"Withdraw\", index: " + withdrawalIndexOf(body, scriptHash) + ",";
    }

    /** {@code assetOutputIndexes} — field 4 of the {@code lm_liquidate_and_pay_in_advance_action} redeemer. */
    private static List<BigInteger> payInAdvanceAssetOutputIndexes(Transaction body) {
        ConstrPlutusData constr = (ConstrPlutusData) rewardRedeemer(body,
                REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash()).getData();
        ListPlutusData indexes = (ListPlutusData) constr.getData().getPlutusDataList().get(4);
        return indexes.getPlutusDataList().stream().map(item -> ((BigIntPlutusData) item).getValue()).toList();
    }

    /** The outputs at the asset-manager spend credential, in body order: the list the validators index. */
    private static List<TransactionOutput> assetManagerOutputs(Transaction body) {
        String assetManagerSpend = REGISTRY.getAssetManagerSpendScriptHash();
        return body.getBody().getOutputs().stream()
                .filter(output -> assetManagerSpend.equals(paymentCredentialOf(output.getAddress()))).toList();
    }

    private static TransactionOutput onlyOutputAt(Transaction body, String paymentScriptHash) {
        List<TransactionOutput> matches = body.getBody().getOutputs().stream()
                .filter(output -> paymentScriptHash.equals(paymentCredentialOf(output.getAddress()))).toList();
        assertEquals(1, matches.size(), "expected exactly one output at " + paymentScriptHash);
        return matches.getFirst();
    }

    private static String paymentCredentialOf(String address) {
        return new com.bloxbean.cardano.client.address.Address(address)
                .getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse("");
    }

    private static boolean adaOnly(TransactionOutput output) {
        return output.getValue().getMultiAssets() == null || output.getValue().getMultiAssets().stream()
                .allMatch(multiAsset -> multiAsset.getAssets() == null || multiAsset.getAssets().isEmpty());
    }

    private static BigInteger lovelaceOf(Utxo utxo) {
        return utxo.getAmount().stream().filter(a -> AssetType.LOVELACE.equals(a.getUnit())).map(Amount::getQuantity)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    /** Every native-asset unit an output carries, with its quantity. */
    private static Map<String, BigInteger> assetsOf(TransactionOutput output) {
        Map<String, BigInteger> units = new HashMap<>();
        if (output.getValue().getMultiAssets() != null) {
            for (MultiAsset multiAsset : output.getValue().getMultiAssets()) {
                for (Asset asset : multiAsset.getAssets()) {
                    units.merge(multiAsset.getPolicyId() + HexUtil.encodeHexString(asset.getNameAsBytes()),
                            asset.getValue(), BigInteger::add);
                }
            }
        }
        return units;
    }

    private static Asset assetOf(TransactionOutput output, AssetType asset) {
        return output.getValue().getMultiAssets().stream()
                .filter(multiAsset -> multiAsset.getPolicyId().equals(asset.policyId()))
                .flatMap(multiAsset -> multiAsset.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(a.getNameAsBytes()).equals(asset.assetName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + asset.toUnit() + " in " + output));
    }

    private static BigInteger quantityOf(TransactionOutput output, AssetType asset) {
        return assetsOf(output).getOrDefault(asset.toUnit(), BigInteger.ZERO);
    }

    private static int flattenedCount(TransactionOutput output) {
        return assetsOf(output).size() + (output.getValue().getCoin().signum() > 0 ? 1 : 0);
    }
}
