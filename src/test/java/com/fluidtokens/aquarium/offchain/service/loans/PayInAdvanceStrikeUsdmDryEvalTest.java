package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
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
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.HashCheckedScriptSupplier;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletRoles;
import com.fluidtokens.aquarium.offchain.util.LedgerCeilings;
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

import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.BOT_ADDRESS;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.COLLATERAL_AMOUNTS;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.PRICE_DENOMINATOR;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.PRINCIPAL_PRICE;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.REGISTRY;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.STRIKE;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.STRIKE_ORACLE_HASH;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.STRIKE_ORACLE_NFT;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.T0;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.USDM;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.USDM_ORACLE_HASH;
import static com.fluidtokens.aquarium.offchain.service.loans.StrikeUsdmFixture.USDM_ORACLE_NFT;
import static com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Blueprint.AQUARIUM_SC;
import static com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Blueprint.LOANS_V4;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-225 — <b>a TOKEN-principal pay-in-advance, evaluated offline against the deployed validators.</b>
 * <p>
 * Three recorded mainnet loans carry STRIKE collateral and a USDM principal, each with a lender bond that
 * asks for conversion to principal ({@link StrikeUsdmFixture}). Every build here goes through
 * {@link LiquidatePayInAdvanceTransactionBuilder}'s production constructor with only its leaves supplied:
 * the UTxO set, the protocol parameters, the script bytes behind the production
 * {@link HashCheckedScriptSupplier}, and the real PlutusV3 machine with the mainnet slot config. Both
 * oracle legs come from {@link TestOracleRig}: FluidTokens' oracle code control-hashed to the deployed
 * STRIKE v1 and USDM v1 oracles, re-parameterised with runtime keys, so the price that makes these loans
 * liquidatable can be signed at all. Each rig script travels only as a reference script.
 *
 * <h2>Why two legs</h2>
 * {@code validators/lender-manager/lm_liquidate_and_pay_in_advance_action.ak} prices the lender's payout
 * through BOTH feeds — {@code convertFromAToBWithOracles(collateralFeed, principalFeed, …)} — and
 * {@code lib/fluidtokens/oracle.ak}'s {@code retrieve_oracle_data} finds each feed by
 * {@code Withdraw(<the reference input's payment credential>)}, requiring {@code valid_from <= txValidFrom},
 * {@code valid_to >= txValidTo} and the feed token equal to the leg's. So each leg needs its own
 * withdraw-0, at the credential its NFT holder sits at — and one credential cannot carry two legs' feeds,
 * which the builder now refuses by name ({@code ORACLE_LEGS_SHARE_ONE_CREDENTIAL}).
 *
 * <h2>Reading a failure</h2>
 * The evaluator reports only the first failing redeemer ({@link EvalFixtures}, harness limitation 1), so
 * every negative here names its refuser by the withdrawal's position in the same body, or is a named
 * refusal with zero evaluations. Random rig credentials sort among the withdrawals, so a position is
 * always derived from the body, never a constant.
 */
@Slf4j
class PayInAdvanceStrikeUsdmDryEvalTest {

    // ---- loan #0 at T0 and the rig prices (measured, then frozen) ------------------------------------

    private static final BigInteger REMAINING_DEBT = BigInteger.valueOf(747_341_770L);
    private static final BigInteger EQUITY = BigInteger.valueOf(109_358_384L);
    private static final BigInteger LIQUIDATION_FEE = BigInteger.valueOf(98_325_872L);
    private static final BigInteger LENDER_SHOULD_RECEIVE = BigInteger.valueOf(2_250_462_548L);
    private static final BigInteger PAYOUT = BigInteger.valueOf(787_661_892L);
    /** Collateral − equity: what the bot acquires, the fee slice included. */
    private static final BigInteger BOT_STRIKE = BigInteger.valueOf(2_348_788_420L);

    /**
     * {@code WalletRoles.tokenOutputRequiredAda(LedgerCeilings.maxPossibleFee(LoanFixtures.protocolParams()))}:
     * 2,405,077 + 5,500,000.
     */
    private static final long TOKEN_OUTPUT_LOVELACE = 7_905_077L;
    /** The USDM the token output carries beyond the payout, which comes back in change. */
    private static final BigInteger LEFTOVER_USDM = BigInteger.valueOf(5_000_000L);

    private static final long TX_VALID_TO = T0 + 120_000L;
    private static final BigInteger PLACEHOLDER = BigInteger.valueOf(10_000);

    // ======================================================================================
    // (a), (b) the recorded candidates and the two deployed oracles
    // ======================================================================================

    @Test
    void theRecordedLoansAreTokenPrincipalPayInAdvanceCandidates() throws Exception {
        List<BigInteger> principals = List.of(BigInteger.valueOf(737_444_041L), BigInteger.valueOf(1_916_439_720L),
                BigInteger.valueOf(346_116_239L));
        List<BigInteger> rates = List.of(BigInteger.valueOf(757), BigInteger.valueOf(400), BigInteger.valueOf(400));
        for (int i = 0; i < 3; i++) {
            LoanDatum loan = StrikeUsdmFixture.loanDatum(i);
            String which = "loan #" + i;
            assertEquals(USDM, loan.principalAsset(), which);
            assertEquals(USDM_ORACLE_NFT, loan.principalOracleAsset(), which);
            assertEquals(STRIKE, loan.collateral().assetType(), which);
            assertEquals(STRIKE_ORACLE_NFT, loan.collateral().oracleTokenAsset(), which);
            assertEquals(BigInteger.valueOf(1_790_349_901_000L), loan.lendDate(), which);
            assertEquals(BigInteger.ZERO, loan.installmentPeriod(), which + ": never late");
            assertEquals(new RepaymentMode.PerpetualLoan(BigInteger.valueOf(28), BigInteger.valueOf(5)),
                    loan.repaymentMode(), which);
            assertEquals(new LiquidationMode.Liquidation(BigInteger.valueOf(100), BigInteger.valueOf(125),
                    BigInteger.valueOf(100), false), loan.liquidationMode(), which);
            assertFalse(loan.repaymentReceipts(), which);
            assertEquals(principals.get(i), loan.principalAmount(), which);
            assertEquals(rates.get(i), loan.interestRate(), which);

            LenderManagerDatum bond = StrikeUsdmFixture.bondDatum(i);
            assertTrue(bond.shouldLiquidationConvertToPrincipal(), which + ": the bond asks for pay-in-advance");
            assertEquals(BigInteger.valueOf(40), bond.liquidationFeePerMille(), which);
            assertEquals(USDM, bond.principalAsset(), which);

            assertEquals(REGISTRY.getLoanSpendScriptHash(), paymentCredentialOf(StrikeUsdmFixture.LOAN_ADDRESS),
                    "the loan UTxOs sit at the shipped registry's loan spend");
            assertEquals(REGISTRY.getLenderManagerSpendScriptHash(),
                    paymentCredentialOf(StrikeUsdmFixture.BOND_ADDRESSES.get(i)), which + "'s bond");
        }
        assertEquals("20256f557cec14f61cc662468f7812a1e6531e628af257a5fc5dd0c5", REGISTRY.getLoanPolicyId());
        assertEquals("bcd713bb7858d4b08738bed90ee7068d8f9b38d02e0cae0b45ac7a9b", REGISTRY.getLenderBondPolicyId());
        assertEquals("0e000364ffae7e6768f719248858cfd439541ae7eeb2e7f49b2aa380",
                REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash());

        FluidOracleClient registry = OracleClients.mainnetTwoVersions();
        OracleEntry strike = registry.findEntryByOracleToken(STRIKE_ORACLE_NFT).orElseThrow();
        OracleEntry usdm = registry.findEntryByOracleToken(USDM_ORACLE_NFT).orElseThrow();
        assertEquals(STRIKE, strike.token());
        assertEquals(USDM, usdm.token());
        assertEquals(Integer.valueOf(1), strike.oracleVersion());
        assertEquals(Integer.valueOf(1), usdm.oracleVersion());
        assertEquals(OraclePriceFeed.Variant.AGGREGATED, strike.feed().variant(), "multisig");
        assertEquals(OraclePriceFeed.Variant.AGGREGATED, usdm.feed().variant(), "multisig");
        assertEquals(STRIKE_ORACLE_HASH, strike.withdrawCredentialHash());
        assertEquals(USDM_ORACLE_HASH, usdm.withdrawCredentialHash());
        assertNotEquals(strike.withdrawCredentialHash(), usdm.withdrawCredentialHash(),
                "the two legs withdraw from different credentials");
    }

    @Test
    void bothDeployedOraclesAreTheAquariumScOracleAppliedToTheirOwnParameters() throws Exception {
        FluidOracleClient registry = OracleClients.mainnetTwoVersions();
        Map<String, AssetType> nftByHash8 = Map.of("d97da4f3", STRIKE_ORACLE_NFT, "3a561e40", USDM_ORACLE_NFT);
        Map<String, String> hashByHash8 = Map.of("d97da4f3", STRIKE_ORACLE_HASH, "3a561e40", USDM_ORACLE_HASH);
        for (String hash8 : List.of("d97da4f3", "3a561e40")) {
            TestOracleRig.Deployed deployed = TestOracleRig.controlHashed(hash8, AQUARIUM_SC);
            assertEquals(hashByHash8.get(hash8), deployed.scriptHash());
            AssertionError other = assertThrows(AssertionError.class, () -> TestOracleRig.controlHashed(hash8, LOANS_V4),
                    hash8 + " must not reproduce from loans-v4.plutus.json");
            assertTrue(other.getMessage().contains(deployed.scriptHash()), other.getMessage());

            OracleEntry entry = registry.findEntryByOracleToken(nftByHash8.get(hash8)).orElseThrow();
            assertEquals(entry.verificationKeys().stream().map(String::toLowerCase).toList(),
                    deployed.verificationKeysHex().stream().map(String::toLowerCase).toList(),
                    hash8 + ": the applied keys are the registry entry's");
            assertEquals(entry.threshold(), deployed.threshold(), hash8 + ": the applied threshold");
            assertEquals(1, deployed.threshold());
        }
        StrikeUsdmFixture.Legs legs = StrikeUsdmFixture.legs(StrikeUsdmFixture.collateralFeed(),
                StrikeUsdmFixture.principalFeed());
        for (TestOracleRig rig : List.of(legs.collateralRig(), legs.principalRig())) {
            assertNotEquals(STRIKE_ORACLE_HASH, rig.scriptHash(), "a rig is never the deployed STRIKE oracle");
            assertNotEquals(USDM_ORACLE_HASH, rig.scriptHash(), "a rig is never the deployed USDM oracle");
        }
    }

    // ======================================================================================
    // (c) the deliverable
    // ======================================================================================

    @Test
    void aStrikeUsdmPayInAdvanceEvaluatesAgainstTheDeployedValidatorsThroughTheRigOracles() throws Exception {
        Built b = buildLoan0();
        Transaction body = b.body();

        // The arithmetic the chain recomputes.
        LiquidatePayInAdvanceTransactionBuilder.Numbers numbers =
                StrikeUsdmFixture.production(b.universe(), b.scripts(), b.evaluator()).numbers(b.request());
        assertEquals(REMAINING_DEBT, numbers.remainingDebt());
        assertEquals(EQUITY, numbers.equity(), "positive equity, in STRIKE");
        assertEquals(LIQUIDATION_FEE, numbers.liquidationFee(), "2,458,146,804 STRIKE at 40 per mille");
        assertEquals(LENDER_SHOULD_RECEIVE, numbers.collateralLenderShouldReceive());
        assertEquals(PAYOUT, numbers.convertedLoanCollateralToPrincipalAmount(),
                "ceil(2,250,462,548 × 1.4 / 4) USDM");

        // Liquidatable only at the rig's price: the recorded STRIKE feed does not make it so.
        OraclePriceFeed recordedStrike = OracleClients.mainnetTwoVersions()
                .findEntryByOracleToken(STRIKE_ORACLE_NFT).orElseThrow().feed();
        assertEquals(BigInteger.valueOf(233_067_799L), recordedStrike.priceInLovelaces());
        assertEquals(PRICE_DENOMINATOR, recordedStrike.priceDenominator());
        LiquidationMode.Liquidation mode = (LiquidationMode.Liquidation) StrikeUsdmFixture.loanDatum(0).liquidationMode();
        Rational debt = Rational.fromInt(numbers.remainingDebt());
        Rational collateral = Rational.fromInt(BigInteger.valueOf(COLLATERAL_AMOUNTS.get(0)));
        OraclePriceFeed principalFeed = b.legs().principal().signedFeed();
        assertFalse(LoanFinance.canLiquidate(debt, collateral, LoanFinance.liquidationLtv(mode), principalFeed,
                recordedStrike), "not liquidatable at the recorded 2026-10-01 STRIKE price");
        assertTrue(LoanFinance.canLiquidate(debt, collateral, LoanFinance.liquidationLtv(mode), principalFeed,
                b.legs().collateral().signedFeed()), "liquidatable at the rig's STRIKE price");

        // Real ex-units, read off the BUILT transaction.
        assertEquals(1, b.evaluator().calls.get(), "exactly one evaluation per build");
        List<Redeemer> redeemers = body.getWitnessSet().getRedeemers();
        assertEquals(9, redeemers.size(), "two spends, one burn, six withdrawals: " + redeemers);
        assertEquals(2, redeemers.stream().filter(r -> r.getTag() == RedeemerTag.Spend).count(), "the loan, the bond");
        assertEquals(1, redeemers.stream().filter(r -> r.getTag() == RedeemerTag.Mint).count(), "the loan-NFT burn");
        assertEquals(6, redeemers.stream().filter(r -> r.getTag() == RedeemerTag.Reward).count(),
                "loan, loan_claim_action, lenderManager, the pay-in-advance action, two oracle legs");
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
        log.info("FAB-225 STRIKE→USDM pay-in-advance: {} bytes, fee {}, mem {}, steps {}, withdrawals {}", size,
                body.getBody().getFee(), mem, steps, rewardAddresses(body));
        assertTrue(mem.compareTo(BigInteger.valueOf(14_000_000L)) <= 0, "total mem " + mem);
        assertTrue(steps.compareTo(BigInteger.valueOf(10_000_000_000L)) <= 0, "total steps " + steps);
        assertTrue(size < 16_384, "under maxTxSize: " + size);

        // The six withdraw-0 invocations.
        Set<String> expectedWithdrawals = Set.of(
                StrikeUsdmFixture.rewardAddress(REGISTRY.getLoanPolicyId()),
                StrikeUsdmFixture.rewardAddress(REGISTRY.getLoanClaimActionScriptHash()),
                StrikeUsdmFixture.rewardAddress(REGISTRY.getLenderManagerWithdrawScriptHash()),
                StrikeUsdmFixture.rewardAddress(REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash()),
                StrikeUsdmFixture.rewardAddress(b.legs().collateralRig().scriptHash()),
                StrikeUsdmFixture.rewardAddress(b.legs().principalRig().scriptHash()));
        assertEquals(expectedWithdrawals, new HashSet<>(rewardAddresses(body)), "the six withdrawals");

        // Every script by reference, none in the witness set.
        assertTrue(witnessedScriptHashes(body).isEmpty(), "no witness-set script: " + witnessedScriptHashes(body));
        Set<String> expectedReferenced = new HashSet<>();
        StrikeUsdmFixture.registryReferenceScripts().forEach(s -> expectedReferenced.add(StrikeUsdmFixture.hashOf(s)));
        expectedReferenced.add(b.legs().collateralRig().scriptHash());
        expectedReferenced.add(b.legs().principalRig().scriptHash());
        assertEquals(expectedReferenced, referencedScriptHashes(body, b.universe()),
                "the six registry scripts and both rig oracles, by reference");

        // The outputs.
        List<TransactionOutput> assetOutputs = assetManagerOutputs(body);
        assertEquals(2, assetOutputs.size(), "a borrower compensation and the lender's payout");
        TransactionOutput borrower = assetOutputs.get(0);
        assertEquals(EQUITY, quantityOf(borrower, STRIKE), "the borrower's equity, in STRIKE");
        assertEquals(2, flattenedCount(borrower), "STRIKE plus its min-ada rider");
        TransactionOutput lender = assetOutputs.get(payInAdvanceAssetOutputIndexes(body).get(0).intValueExact());
        assertTrue(quantityOf(lender, USDM).compareTo(PAYOUT) >= 0, "the lender's USDM: " + quantityOf(lender, USDM));
        assertEquals(2, flattenedCount(lender), "USDM plus its min-ada rider (dosProtection flatten == 2)");
        List<TransactionOutput> toBot = body.getBody().getOutputs().stream()
                .filter(o -> BOT_ADDRESS.equals(o.getAddress())).toList();
        assertEquals(1, toBot.stream().filter(o -> quantityOf(o, STRIKE).equals(BOT_STRIKE)).count(),
                "exactly one bot output carries the acquired 2,348,788,420 STRIKE: " + toBot);
        assertTrue(toBot.stream().anyMatch(PayInAdvanceStrikeUsdmDryEvalTest::adaOnly),
                "at least one ADA-only output to the bot: " + toBot);
        assertTrue(toBot.stream().anyMatch(o -> quantityOf(o, USDM).equals(LEFTOVER_USDM)),
                "a change output carries the 5,000,000 leftover USDM: " + toBot);
        TransactionOutput echo = onlyOutputAt(body, REGISTRY.getLenderManagerSpendScriptHash());
        assertEquals(StrikeUsdmFixture.bondDatumHex(0), echo.getInlineDatum().serializeToHex(),
                "the bond echo datum is byte-identical to its input");

        // The wallet: the token output is the only wallet input, C the only collateral.
        assertEquals(List.of(ref(b.token())), walletInputs(body, b.universe()), "the wallet spends its token output alone");
        assertEquals(List.of(ref(b.c())), collateralRefs(body), "the collateral is exactly C");
        assertFalse(inputRefs(body).contains(ref(b.c())), "C is never spent");
        assertFalse(inputRefs(body).contains(ref(b.u())), "U is never spent");

        assertConserved(body, b.universe());
        assertEachOracleLegReadsTheCredentialItWithdrawsFrom(body, b.universe(), b.legs());
    }

    // ======================================================================================
    // (d), (e) the shipped token-output requirement
    // ======================================================================================

    /**
     * The requirement the executor sizes a token output with, {@link WalletRoles#tokenOutputRequiredAda} at
     * {@link LedgerCeilings#maxPossibleFee}, funds this shape on its own for every recorded loan.
     * <p>
     * Measured for this ticket (base-address bot, loans #0–#2, 250,000-lovelace steps from 3.0 ADA), the
     * smallest token-output ADA that builds:
     * <table>
     *   <tr><th>price</th><th>change</th><th>smallest that builds</th></tr>
     *   <tr><td>STRIKE 1.40 (equity &gt; 0)</td><td>ADA-only</td><td>4,500,000 (#0, #2), 4,750,000 (#1)</td></tr>
     *   <tr><td>STRIKE 1.40</td><td>7 USDM in change</td><td>4,750,000</td></tr>
     *   <tr><td>STRIKE 1.20 (equity 0)</td><td>ADA-only</td><td>≤ 3,000,000</td></tr>
     *   <tr><td>STRIKE 1.20</td><td>USDM in change</td><td>3,250,000</td></tr>
     * </table>
     * The shipped requirement is 7,905,077 lovelace and built in all twelve combinations with at least
     * 3.15 ADA of headroom, so <b>no raise is needed for this shape</b>.
     */
    @Test
    void theShippedTokenOutputRequirementFundsThisShapeForEveryRecordedLoan() throws Exception {
        assertEquals(BigInteger.valueOf(TOKEN_OUTPUT_LOVELACE), WalletRoles.tokenOutputRequiredAda(
                        LedgerCeilings.maxPossibleFee(LoanFixtures.protocolParams().getProtocolParams())),
                "the shipped token-output requirement moved; re-measure this shape's funding");
        StrikeUsdmFixture.Legs legs = StrikeUsdmFixture.legs(StrikeUsdmFixture.collateralFeed(),
                StrikeUsdmFixture.principalFeed());
        for (int i = 0; i < 3; i++) {
            Utxo c = StrikeUsdmFixture.collateral(BOT_ADDRESS);
            Utxo u = StrikeUsdmFixture.u(BOT_ADDRESS);
            LiquidatePayInAdvanceTransactionBuilder.Numbers numbers = numbersOf(i, legs, c);
            assertTrue(numbers.equity().signum() > 0, "loan #" + i + " has positive equity at the rig prices");
            Utxo token = StrikeUsdmFixture.tokenOutput(BOT_ADDRESS, TOKEN_OUTPUT_LOVELACE,
                    numbers.convertedLoanCollateralToPrincipalAmount().add(LEFTOVER_USDM));
            List<Utxo> universe = StrikeUsdmFixture.universe(i, legs, List.of(c, u, token));
            Recording evaluator = new Recording(StrikeUsdmFixture.aiken(universe, legs));
            Transaction body = Transaction.deserialize(StrikeUsdmFixture.production(universe,
                            new HashCheckedScriptSupplier(StrikeUsdmFixture.rawScripts(legs)), evaluator)
                    .build(StrikeUsdmFixture.request(i, legs, token, c, BOT_ADDRESS, T0, TX_VALID_TO)).serialize());
            log.info("FAB-225 loan #{}: equity {}, payout {}, fee {}", i, numbers.equity(),
                    numbers.convertedLoanCollateralToPrincipalAmount(), body.getBody().getFee());

            assertEquals(List.of(ref(token)), walletInputs(body, universe), "loan #" + i + ": the token output alone");
            assertEquals(List.of(ref(c)), collateralRefs(body), "loan #" + i + ": the collateral is exactly C");
            assertConserved(body, universe);
            assertTrue(body.getBody().getOutputs().stream()
                            .anyMatch(o -> BOT_ADDRESS.equals(o.getAddress()) && adaOnly(o)),
                    "loan #" + i + ": an ADA-only output to the bot");
        }
    }

    /**
     * A token output of 4,000,000 lovelace and exactly the payout in USDM is too thin for this shape: the
     * build refuses for want of funds and never reaches for U (100 ADA, right beside it) or C. The value
     * DISCRIMINATES, measured: with the designated-input view bypassed the same build succeeds by spending
     * the token output AND C.
     */
    @Test
    void aTokenOutputTooThinForThisShapeIsRefusedAndNoSecondWalletOutputIsPulled() throws Exception {
        StrikeUsdmFixture.Legs legs = StrikeUsdmFixture.legs(StrikeUsdmFixture.collateralFeed(),
                StrikeUsdmFixture.principalFeed());
        Utxo c = StrikeUsdmFixture.collateral(BOT_ADDRESS);
        Utxo u = StrikeUsdmFixture.u(BOT_ADDRESS);
        Utxo thin = StrikeUsdmFixture.tokenOutput(BOT_ADDRESS, 4_000_000L, PAYOUT);
        List<Utxo> universe = StrikeUsdmFixture.universe(0, legs, List.of(c, u, thin));
        Recording evaluator = new Recording(StrikeUsdmFixture.aiken(universe, legs));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> StrikeUsdmFixture.production(universe,
                                new HashCheckedScriptSupplier(StrikeUsdmFixture.rawScripts(legs)), evaluator)
                        .build(StrikeUsdmFixture.request(0, legs, thin, c, BOT_ADDRESS, T0, TX_VALID_TO)));

        String chain = LiquidationExecutor.causeChain(refused);
        log.info("FAB-225 token output of 4 ADA refused after {} evaluation(s): {}", evaluator.calls.get(), chain);
        assertTrue(chain.contains("Not enough funds"), "refused for want of funds in the token output: " + chain);
        for (Transaction body : evaluator.bodies) {
            assertEquals(List.of(ref(thin)), walletInputs(body, universe),
                    "a body that reached the evaluator spends the token output alone, never U or C");
        }
    }

    // ======================================================================================
    // (f), (g), (h) negative controls, each naming its refuser
    // ======================================================================================

    @Test
    void aLenderPayoutOneUnitShortIsRefusedByThePayInAdvanceAction() throws Exception {
        Built b = buildLoan0();
        Transaction mutated = Transaction.deserialize(b.body().serialize());
        TransactionOutput lender = assetManagerOutputs(mutated)
                .get(payInAdvanceAssetOutputIndexes(mutated).get(0).intValueExact());
        Asset usdm = assetOf(lender, USDM);
        assertEquals(PAYOUT, usdm.getValue(), "the built body pays the lender exactly the payout");
        usdm.setValue(usdm.getValue().subtract(BigInteger.ONE));
        assertEquals(PAYOUT.subtract(BigInteger.ONE), quantityOf(lender, USDM), "one unit short");

        String detail = refusal(mutated, b);
        String expected = redeemerError(mutated, REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash());
        assertTrue(detail.contains(expected), "expected " + expected + " to be the refuser, got: " + detail);
    }

    /**
     * M2, the two-leg form: the collateral feed still covers the transaction window and the principal
     * feed does not. Moved to slot(T0 + 660 s), 60 s past the principal feed's {@code valid_to}, the body is
     * refused at the pay-in-advance action's withdrawal; moved to slot(T0 + 540 s) it still evaluates.
     */
    @Test
    void aTxWindowPastThePrincipalFeedsValidToIsRefusedWhileTheCollateralFeedStillCoversIt() throws Exception {
        Built b = buildLoan0();
        long principalValidTo = b.legs().principal().signedFeed().validTo();
        long collateralValidTo = b.legs().collateral().signedFeed().validTo();
        assertTrue(T0 + 660_000L > principalValidTo && T0 + 660_000L < collateralValidTo,
                "the moved window must end between the two feeds' valid_to");

        Transaction late = Transaction.deserialize(b.body().serialize());
        late.getBody().setTtl(StrikeUsdmFixture.slot(T0 + 660_000L));
        String detail = refusal(late, b);
        String expected = redeemerError(late, REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash());
        assertTrue(detail.contains(expected), "expected " + expected + " to be the refuser, got: " + detail);

        Transaction inside = Transaction.deserialize(b.body().serialize());
        inside.getBody().setTtl(StrikeUsdmFixture.slot(T0 + 540_000L));
        Result<List<EvaluationResult>> control = evaluate(inside, b);
        assertTrue(control.isSuccessful(), "the control inside the principal feed must evaluate: "
                + control.getResponse());
    }

    @Test
    void anHonestRequestWhosePrincipalFeedEndsInsideTheTxWindowIsRefusedByNameBeforeEvaluation() throws Exception {
        TestOracleRig.Feed shortPrincipal = new TestOracleRig.Feed(PRINCIPAL_PRICE, PRICE_DENOMINATOR,
                T0 - 200_000L, T0 + 90_000L);
        StrikeUsdmFixture.Legs legs = StrikeUsdmFixture.legs(StrikeUsdmFixture.collateralFeed(), shortPrincipal);
        Wallet w = wallet();
        List<Utxo> universe = StrikeUsdmFixture.universe(0, legs, w.all());
        Recording evaluator = new Recording(StrikeUsdmFixture.aiken(universe, legs));

        PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException refused = assertThrows(
                PayInAdvanceLiquidationRouter.PayInAdvanceNotModelledException.class,
                () -> StrikeUsdmFixture.production(universe,
                                new HashCheckedScriptSupplier(StrikeUsdmFixture.rawScripts(legs)), evaluator)
                        .build(StrikeUsdmFixture.request(0, legs, w.token(), w.c(), BOT_ADDRESS, T0, TX_VALID_TO)));

        assertTrue(refused.getMessage().contains("principal oracle feed window"), refused.getMessage());
        assertEquals(0, evaluator.calls.get(), "refused before anything is evaluated");
    }

    // ======================================================================================
    // (i) M1 — two legs sharing one withdrawal credential
    // ======================================================================================

    /**
     * ⛔ M1. One rig making both legs gives both entries one withdrawal credential. One withdraw-0
     * redeemer carries one feed, and {@code retrieve_oracle_data} resolves each leg's feed by its reference
     * input's credential, so one withdrawal cannot price two legs. The builder refuses by name, before
     * anything is priced or evaluated.
     * <p>
     * <b>Fail-first, measured on the untouched builder:</b> it silently dropped the second withdraw-0,
     * built a five-withdrawal body and failed at evaluation, as
     * {@code IllegalStateException: cannot build the pay-in-advance transaction} with
     * {@code RedeemerError { tag: "Withdraw", index: 0, …} (the pay-in-advance action) in its cause chain.
     */
    @Test
    void twoLegsSharingOneWithdrawalCredentialAreRefusedByNameBeforeAnythingIsPriced() throws Exception {
        StrikeUsdmFixture.Legs legs = StrikeUsdmFixture.sharedLegs(StrikeUsdmFixture.collateralFeed(),
                StrikeUsdmFixture.principalFeed());
        assertEquals(legs.collateral().entry().withdrawCredentialHash(), legs.principal().entry().withdrawCredentialHash(),
                "fixture: both legs withdraw from one credential");
        Wallet w = wallet();
        List<Utxo> universe = StrikeUsdmFixture.universe(0, legs, w.all());
        Recording evaluator = new Recording(StrikeUsdmFixture.aiken(universe, legs));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> StrikeUsdmFixture.production(universe,
                                new HashCheckedScriptSupplier(StrikeUsdmFixture.rawScripts(legs)), evaluator)
                        .build(StrikeUsdmFixture.request(0, legs, w.token(), w.c(), BOT_ADDRESS, T0, TX_VALID_TO)));

        log.info("FAB-225 shared oracle credential: {}", LiquidationExecutor.causeChain(refused));
        assertTrue(refused.getMessage().startsWith("ORACLE_LEGS_SHARE_ONE_CREDENTIAL:"),
                "refused by name: " + LiquidationExecutor.causeChain(refused));
        assertTrue(refused.getMessage().contains(legs.collateralRig().scriptHash()),
                "the refusal names the shared credential: " + refused.getMessage());
        assertEquals(0, evaluator.calls.get(), "refused before anything is priced or evaluated");
    }

    // ======================================================================================
    // (j) H2 — both oracle reference scripts are priced
    // ======================================================================================

    /**
     * The production wiring prices every referenced script, BOTH rig oracles included. The fee is bounded
     * below by an independent reconstruction — size, constant, execution budget and the Conway tiered
     * reference-script fee over all eight referenced scripts — and above by 256 bytes' worth of size fee
     * (the vkey witness the unsigned body does not yet carry). Against the same fixture built with a
     * script supplier that serves nothing, the fee falls by at least the whole tiered fee; and each rig
     * script's marginal share exceeds the 88-lovelace tolerance, so neither could go unpriced unseen.
     */
    @Test
    void theProductionWiringPricesBothOracleReferenceScripts() throws Exception {
        Built b = buildLoan0();
        Transaction priced = b.body();
        ScriptSupplier servesNothing = hash -> Optional.empty();
        Transaction unpriced = Transaction.deserialize(StrikeUsdmFixture.production(b.universe(), servesNothing,
                new Recording(StrikeUsdmFixture.aiken(b.universe(), b.legs()))).build(b.request()).serialize());

        BigDecimal perByte = LoanFixtures.protocolParams().getProtocolParams().getMinFeeRefScriptCostPerByte();
        assertEquals(0, new BigDecimal("15").compareTo(perByte), "minFeeRefScriptCostPerByte");
        List<PlutusScript> referenced = new ArrayList<>(StrikeUsdmFixture.registryReferenceScripts());
        referenced.add(b.legs().collateralRig().script());
        referenced.add(b.legs().principalRig().script());
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
        log.info("FAB-225 H2: fee {} lower {} (size {} B, mem {}, steps {}, ref {} B tiered {}) unpriced {} delta {}",
                fee, lower, size, mem, steps, refBytes, tiered, unpriced.getBody().getFee(), delta);

        assertTrue(refBytes > 25_600L, "the shape must exercise the tier ladder: " + refBytes + " B");
        BigInteger slack = fee.subtract(lower);
        assertTrue(slack.signum() >= 0, "the fee " + fee + " is below the independent floor " + lower);
        assertTrue(slack.compareTo(BigInteger.valueOf(44L * 256)) <= 0,
                "the fee " + fee + " exceeds the floor " + lower + " by more than 256 bytes of size fee");
        BigInteger tolerance = BigInteger.valueOf(2 * 44);
        assertTrue(delta.compareTo(tiered.subtract(tolerance)) >= 0, "the reference-script fee charged (" + delta
                + ") is below the tiered fee for all eight referenced scripts (" + tiered + ")");
        for (TestOracleRig rig : List.of(b.legs().collateralRig(), b.legs().principalRig())) {
            List<PlutusScript> without = new ArrayList<>(referenced);
            without.remove(rig.script());
            BigInteger share = tiered.subtract(tieredRefScriptFee(perByte, bytesOf(without)));
            assertTrue(share.compareTo(tolerance) > 0, "rig " + rig.scriptHash() + "'s share " + share
                    + " would hide under the tolerance");
        }
    }

    // ======================================================================================
    // the positive build and its helpers
    // ======================================================================================

    /** The positive build's ingredients and its deserialised body. */
    private record Built(StrikeUsdmFixture.Legs legs, List<Utxo> universe, Utxo token, Utxo c, Utxo u,
                         ScriptSupplier scripts, Recording evaluator,
                         LiquidatePayInAdvanceTransactionBuilder.Request request, Transaction body) {
    }

    private record Wallet(Utxo c, Utxo u, Utxo token) {
        List<Utxo> all() {
            return List.of(c, u, token);
        }
    }

    /** C, U and a token output of the shipped requirement + (payout + 5,000,000) USDM, at the bot. */
    private static Wallet wallet() {
        return new Wallet(StrikeUsdmFixture.collateral(BOT_ADDRESS), StrikeUsdmFixture.u(BOT_ADDRESS),
                StrikeUsdmFixture.tokenOutput(BOT_ADDRESS, TOKEN_OUTPUT_LOVELACE, PAYOUT.add(LEFTOVER_USDM)));
    }

    /** Loan #0, both rig legs at their default feeds, a [T0, T0 + 120 s] window, the production constructor. */
    private static Built buildLoan0() throws Exception {
        StrikeUsdmFixture.Legs legs = StrikeUsdmFixture.legs(StrikeUsdmFixture.collateralFeed(),
                StrikeUsdmFixture.principalFeed());
        Wallet w = wallet();
        List<Utxo> universe = StrikeUsdmFixture.universe(0, legs, w.all());
        ScriptSupplier scripts = new HashCheckedScriptSupplier(StrikeUsdmFixture.rawScripts(legs));
        Recording evaluator = new Recording(StrikeUsdmFixture.aiken(universe, legs));
        var request = StrikeUsdmFixture.request(0, legs, w.token(), w.c(), BOT_ADDRESS, T0, TX_VALID_TO);
        Transaction built = StrikeUsdmFixture.production(universe, scripts, evaluator).build(request);
        return new Built(legs, universe, w.token(), w.c(), w.u(), scripts, evaluator, request,
                Transaction.deserialize(built.serialize()));
    }

    /** {@code numbers()} reads neither the wallet nor the evaluator; the request carries C as a stand-in wallet. */
    private static LiquidatePayInAdvanceTransactionBuilder.Numbers numbersOf(int i, StrikeUsdmFixture.Legs legs,
                                                                             Utxo c) {
        List<Utxo> universe = StrikeUsdmFixture.universe(i, legs, List.of(c));
        return StrikeUsdmFixture.production(universe, StrikeUsdmFixture.rawScripts(legs),
                        StrikeUsdmFixture.aiken(universe, legs))
                .numbers(StrikeUsdmFixture.request(i, legs, c, c, BOT_ADDRESS, T0, TX_VALID_TO));
    }

    /** The same real evaluator over the same universe, on a body edited after the build. */
    private static Result<List<EvaluationResult>> evaluate(Transaction tx, Built b) throws Exception {
        AikenTransactionEvaluator aiken = StrikeUsdmFixture.aiken(b.universe(), b.legs());
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

    /**
     * H1 — each oracle leg reads the credential it withdraws from. The claim redeemer's collateral and
     * principal oracle reference-input indexes point, in the canonically ordered reference inputs the
     * validators see, at UTxOs whose payment credentials are the collateral and the principal rig's script
     * hashes. Each such credential is the delegation credential of exactly one withdrawal, the two differ,
     * and the pay-in-advance redeemer's {@code [principal, collateral]} pair cites the same two indexes.
     */
    private static void assertEachOracleLegReadsTheCredentialItWithdrawsFrom(Transaction body, List<Utxo> universe,
                                                                             StrikeUsdmFixture.Legs legs) {
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

        String collateralCredential = paymentCredentialOf(resolve(refInputs.get(collateralIndex), universe).getAddress());
        String principalCredential = paymentCredentialOf(resolve(refInputs.get(principalIndex), universe).getAddress());
        assertEquals(legs.collateralRig().scriptHash(), collateralCredential,
                "the collateral leg's reference input sits at the collateral rig's credential");
        assertEquals(legs.principalRig().scriptHash(), principalCredential,
                "the principal leg's reference input sits at the principal rig's credential");
        assertNotEquals(collateralCredential, principalCredential, "the two legs read different credentials");
        for (String credential : List.of(collateralCredential, principalCredential)) {
            String reward = StrikeUsdmFixture.rewardAddress(credential);
            assertEquals(1, rewardAddresses(body).stream().filter(reward::equals).count(),
                    credential + " must be the delegation credential of exactly one withdrawal");
        }

        ConstrPlutusData action = (ConstrPlutusData) rewardRedeemer(body,
                REGISTRY.getLmLiquidateAndPayInAdvanceActionScriptHash()).getData();
        ListPlutusData pairs = (ListPlutusData) action.getData().getPlutusDataList().get(3);
        assertEquals(1, pairs.getPlutusDataList().size(), "one loan input, one oracle pair");
        List<PlutusData> pair = ((ListPlutusData) pairs.getPlutusDataList().get(0)).getPlutusDataList();
        assertEquals(List.of(BigInteger.valueOf(principalIndex), BigInteger.valueOf(collateralIndex)),
                pair.stream().map(p -> ((BigIntPlutusData) p).getValue()).toList(),
                "the pay-in-advance action's [principal, collateral] pair is the claim's");
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

    private static Asset assetOf(TransactionOutput output, AssetType asset) {
        return output.getValue().getMultiAssets().stream()
                .filter(multiAsset -> multiAsset.getPolicyId().equals(asset.policyId()))
                .flatMap(multiAsset -> multiAsset.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(a.getNameAsBytes()).equals(asset.assetName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + asset.toUnit() + " in " + output));
    }

    private static BigInteger quantityOf(TransactionOutput output, AssetType asset) {
        if (output.getValue().getMultiAssets() == null) {
            return BigInteger.ZERO;
        }
        return output.getValue().getMultiAssets().stream()
                .filter(multiAsset -> multiAsset.getPolicyId().equals(asset.policyId()))
                .flatMap(multiAsset -> multiAsset.getAssets().stream())
                .filter(a -> HexUtil.encodeHexString(a.getNameAsBytes()).equals(asset.assetName()))
                .map(Asset::getValue)
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static int flattenedCount(TransactionOutput output) {
        int tokens = output.getValue().getMultiAssets() == null ? 0 : output.getValue().getMultiAssets().stream()
                .mapToInt(multiAsset -> multiAsset.getAssets().size()).sum();
        return tokens + (output.getValue().getCoin().signum() > 0 ? 1 : 0);
    }
}
