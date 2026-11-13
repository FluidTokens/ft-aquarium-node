package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.YaciConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.ConvertAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.Recording;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Feed;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-223: a TOKEN-principal convert, offline, against the deployed validators — the recorded mainnet
 * NIGHT→USDM loan {@code 328e44f9…#1} ({@link ConvertTokenToTokenFixture}), its two oracle legs each signed by
 * its own test-oracle rig.
 * <p>
 * Until FAB-223 the convert builder emitted only the collateral oracle leg and hard-coded the principal's
 * reference-input index to 0, so every token-principal convert failed at {@code loan_claim_action}'s second
 * {@code retrieve_oracle_data}. Here the positive build evaluates through the production constructor and the
 * real Aiken evaluator; the shape the builder used to build, a tampered order amount, a healthy signed price and
 * a tx window past either of two offset feed windows are each refused by the named validator — identified by its
 * withdrawal's POSITION, read off a positive control built in the same test with the same rigs (trap 14).
 * <p>
 * <b>Oracle variants proven (trap 25):</b> both legs SIGNED ({@code AGGREGATED}, multisig, threshold 1), as the
 * NIGHT v2 and USDM v2 mainnet entries are. Charli3 / Orcfax / Pooled legs are refused by name before assembly
 * ({@code ConvertTransactionBuilderGuardTest}).
 */
@Slf4j
class ConvertTokenToTokenRigTest {

    private static final BigInteger MAX_TX_MEM = BigInteger.valueOf(14_000_000L);
    private static final BigInteger MAX_TX_STEPS = BigInteger.valueOf(10_000_000_000L);
    /** The mainnet {@code maxTxSize}. The rig's params raise it to 1,000,000, so it is asserted here. */
    private static final int MAINNET_MAX_TX_SIZE = 16_384;

    // ======================================================================================

    /** (a) The NIGHT→USDM convert liquidates through two rig oracles, each leg bound at its own credential. */
    @Test
    void theNightUsdmConvertLiquidatesThroughTwoRigOracles() throws Exception {
        ConvertTokenToTokenFixture f = spec().build();

        // The fixture's figures, against the hand-derived ones.
        assertEquals(BigInteger.valueOf(108_941_394L), f.debt, "remaining debt at T0");
        assertFalse(LoanFinance.isRepaymentLate(f.loan, T0), "perpetual, period 0: never late");
        assertTrue(liquidatable(f, f.collateralLeg.signedFeed(), f.principalLeg.signedFeed()),
                "liquidatable at the rig feeds");
        assertEquals(BigInteger.valueOf(165_262_931L), f.equity, "equity in the collateral currency");
        assertTrue(f.plan.aToBDirection(), "NIGHT is the pool's asset_a: a → b");
        assertEquals(BigInteger.valueOf(3_674_737_069L), f.plan.swappableCollateralAmount(), "swappable");
        assertEquals(BigInteger.valueOf(160_000_000L), f.plan.liquidationFee(), "the bot's fee, 40‰ of 4,000 NIGHT");
        BigInteger quote = ConvertOrderPlan.constantProductOut(f.plan.swappableCollateralAmount(), f.pool.reserveA(),
                f.pool.reserveB(), f.pool.baseFeeANumerator());
        assertTrue(quote.compareTo(f.debt) >= 0, "the pool delivers the debt: quote " + quote);

        Recording evaluator = new Recording(f.aiken());
        Transaction reread = Transaction.deserialize(f.production(evaluator).build(f.request).serialize());
        assertEquals(1, evaluator.calls.get(), "exactly one evaluation: pass 2's (CCL trap 23)");

        // Ex-units off the BUILT body, each the evaluator's own (CCL traps 8 and 13).
        List<Redeemer> redeemers = reread.getWitnessSet().getRedeemers();
        assertEquals(9, redeemers.size(), "two spends, one burn, six withdrawals (both oracles included)");
        BigInteger mem = BigInteger.ZERO;
        BigInteger steps = BigInteger.ZERO;
        StringBuilder table = new StringBuilder();
        for (Redeemer redeemer : redeemers) {
            String key = redeemer.getTag() + "#" + redeemer.getIndex();
            EvaluationResult costing = evaluator.last.get().stream()
                    .filter(r -> r.getRedeemerTag() == redeemer.getTag()
                            && r.getIndex() == redeemer.getIndex().intValue())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no evaluation for " + key));
            ExUnits declared = redeemer.getExUnits();
            assertEquals(costing.getExUnits().getMem(), declared.getMem(), "declared mem for " + key);
            assertEquals(costing.getExUnits().getSteps(), declared.getSteps(), "declared steps for " + key);
            assertTrue(declared.getMem().compareTo(PLACEHOLDER) > 0 && declared.getSteps().compareTo(PLACEHOLDER) > 0,
                    key + " carries CCL's placeholder ex-units: " + declared);
            mem = mem.add(declared.getMem());
            steps = steps.add(declared.getSteps());
            table.append(String.format("%n  %-8s mem %,12d steps %,16d", key, declared.getMem(), declared.getSteps()));
        }
        int size = reread.serialize().length;
        log.info("FAB-223 NIGHT→USDM convert: fee {}, {} bytes, total mem {} steps {}, collateral oracle withdrawal #{} "
                        + "principal #{}{}", reread.getBody().getFee(), size, mem, steps,
                withdrawalIndex(reread, f.collateralRig.scriptHash()), withdrawalIndex(reread, f.principalRig.scriptHash()),
                table);
        assertTrue(size <= MAINNET_MAX_TX_SIZE, "over mainnet's maxTxSize: " + size);
        assertTrue(mem.compareTo(MAX_TX_MEM) <= 0, "total mem " + mem + " over the per-tx maximum");
        assertTrue(steps.compareTo(MAX_TX_STEPS) <= 0, "total steps " + steps + " over the per-tx maximum");

        // Both rig scripts travel by reference only.
        Set<String> witnessed = witnessedScriptHashes(reread.getWitnessSet());
        assertFalse(witnessed.contains(f.collateralRig.scriptHash()), "the collateral rig's script is witnessed");
        assertFalse(witnessed.contains(f.principalRig.scriptHash()), "the principal rig's script is witnessed");
        assertTrue(reread.getBody().getReferenceInputs().contains(f.collateralLeg.entry().referenceScript()),
                "the collateral rig's reference-script out-ref is not a reference input");
        assertTrue(reread.getBody().getReferenceInputs().contains(f.principalLeg.entry().referenceScript()),
                "the principal rig's reference-script out-ref is not a reference input");

        // H1, per leg, independently of the builder.
        String collateralCredential = assertLegBoundAtItsCredential(f, reread, NIGHT_V2_NFT, f.collateralRig);
        String principalCredential = assertLegBoundAtItsCredential(f, reread, USDM_V2_NFT, f.principalRig);
        assertNotEquals(collateralCredential, principalCredential, "the two legs share one credential");

        // The order, the equity and the fee, exactly.
        List<TransactionOutput> outs = reread.getBody().getOutputs();
        TransactionOutput order = only(outs, f.request.orderAddress(), "the Minswap order");
        assertEquals(Set.of("lovelace", NIGHT.toUnit()), unitsOf(order), "the order holds lovelace and NIGHT only");
        assertEquals(BigInteger.valueOf(4_000_000L), order.getValue().getCoin(), "the order's lovelace");
        assertEquals(BigInteger.valueOf(3_674_737_069L), quantityOf(order, NIGHT), "the order's NIGHT");
        TransactionOutput equityOut = only(outs, entAddress(REGISTRY.getAssetManagerSpendScriptHash()), "the equity");
        assertEquals(Set.of("lovelace", NIGHT.toUnit()), unitsOf(equityOut), "the equity holds lovelace and NIGHT only");
        assertEquals(BigInteger.valueOf(2_000_000L), equityOut.getValue().getCoin(), "the equity output's lovelace");
        assertEquals(BigInteger.valueOf(165_262_931L), quantityOf(equityOut, NIGHT), "the borrower's equity");
        BigInteger toBot = outs.stream().filter(o -> BOT_ADDRESS.equals(o.getAddress()))
                .map(o -> quantityOf(o, NIGHT)).reduce(BigInteger.ZERO, BigInteger::add);
        assertEquals(BigInteger.valueOf(160_000_000L), toBot, "the bot's outputs hold exactly its NIGHT fee");
        f.assertConservation(reread);
    }

    /**
     * (b) NIGHT signed at a healthy 20,000,000 while the plan and the claim stay at 12,500,000: both oracles
     * accept their own signatures, and {@code loan_claim_action} refuses the claim.
     */
    @Test
    void aHealthySignedCollateralPriceIsRefusedByLoanClaimAction() throws Exception {
        TestOracleRig c = newRig();
        TestOracleRig p = newRig();
        int claimAction = positiveControlIndex(c, p, REGISTRY.getLoanClaimActionScriptHash());

        Feed healthy = new Feed(BigInteger.valueOf(20_000_000L), DENOMINATOR, NIGHT_FEED.validFrom(),
                NIGHT_FEED.validTo());
        ConvertTokenToTokenFixture f = spec(c, p).collateralFeed(healthy).plannedCollateralFeed(NIGHT_FEED).build();
        assertFalse(liquidatable(f, f.collateralLeg.signedFeed(), f.principalLeg.signedFeed()),
                "the loan must be healthy at the signed NIGHT price");
        Recording evaluator = new Recording(f.aiken());
        Exception refused = assertThrows(Exception.class, () -> f.production(evaluator).build(f.request));
        assertRefusedAt(refused, claimAction, "healthy signed NIGHT price (loan_claim_action)");
    }

    /**
     * (c) The order planned at equity + 1 — one NIGHT unit less swapped — while the claim carries the true equity:
     * {@code lm_liquidate_and_convert_action} refuses the order amount.
     */
    @Test
    void aTamperedOrderAmountIsRefusedByTheConvertAction() throws Exception {
        TestOracleRig c = newRig();
        TestOracleRig p = newRig();
        int convertAction = positiveControlIndex(c, p, REGISTRY.getLmLiquidateAndConvertActionScriptHash());

        ConvertTokenToTokenFixture f = spec(c, p).planEquityDelta(1).build();
        assertEquals(BigInteger.valueOf(3_674_737_068L), f.plan.swappableCollateralAmount(), "one unit less swapped");
        assertEquals(BigInteger.valueOf(165_262_931L), f.claim.equity(), "the claim keeps the true equity");
        Exception refused = assertThrows(Exception.class,
                () -> f.production(new Recording(f.aiken())).build(f.request));
        assertRefusedAt(refused, convertAction, "order amount off by one (lm_liquidate_and_convert_action)");
    }

    /**
     * (d) The single-oracle shape of before FAB-223: the principal declared ada with no entry, so no principal
     * reference input, no principal withdrawal, and index 0 in the claim. {@code loan_claim_action} refuses it.
     * <p>
     * That body has one withdrawal fewer than the positive control, so {@code loan_claim_action}'s position in it
     * is the control's, minus one when the principal rig's withdrawal sorts ahead of it — both read off the
     * same-test positive control, never a constant (the rigs' random credentials decide the order).
     */
    @Test
    void theSingleOracleShapeOfBeforeThisChangeIsRefusedByLoanClaimAction() throws Exception {
        TestOracleRig c = newRig();
        TestOracleRig p = newRig();
        Transaction control = positiveControl(c, p);
        int claimInControl = withdrawalIndex(control, REGISTRY.getLoanClaimActionScriptHash());
        int principalInControl = withdrawalIndex(control, p.scriptHash());
        int claimAction = principalInControl < claimInControl ? claimInControl - 1 : claimInControl;

        ConvertTokenToTokenFixture f = spec(c, p).principalAsAda().build();
        assertTrue(f.request.principal().isAda() && f.request.principalOracle() == null, "the old shape");
        Exception refused = assertThrows(Exception.class,
                () -> f.production(new Recording(f.aiken())).build(f.request));
        assertRefusedAt(refused, claimAction, "principal declared ada (loan_claim_action)");
    }

    static Stream<Arguments> offsetWindows() {
        Feed wide = new Feed(BigInteger.ONE, BigInteger.ONE, T0 - 300_000L, T0 + 1_500_000L);
        Feed narrow = new Feed(BigInteger.ONE, BigInteger.ONE, T0 - 600_000L, T0 + 600_000L);
        return Stream.of(
                Arguments.of("inside both", wide, narrow, T0 + 120_000L, true),
                Arguments.of("past the principal feed's valid_to only", wide, narrow, T0 + 660_000L, false),
                Arguments.of("past the collateral feed's valid_to only", narrow, wide, T0 + 660_000L, false));
    }

    /**
     * (e) Two OFFSET feed windows, one per leg: inside both builds with real ex-units; a tx window past either
     * leg's {@code valid_to} alone is refused by {@code loan_claim_action}, so each leg's window is checked.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("offsetWindows")
    void offsetFeedWindowsAreEachHonouredByLoanClaimAction(String label, Feed collateralWindow, Feed principalWindow,
                                                           long txTo, boolean builds) throws Exception {
        TestOracleRig c = newRig();
        TestOracleRig p = newRig();
        int claimAction = positiveControlIndex(c, p, REGISTRY.getLoanClaimActionScriptHash());

        Feed collateral = new Feed(NIGHT_PRICE, DENOMINATOR, collateralWindow.validFrom(), collateralWindow.validTo());
        Feed principal = new Feed(USDM_PRICE, DENOMINATOR, principalWindow.validFrom(), principalWindow.validTo());
        ConvertTokenToTokenFixture f = spec(c, p).collateralFeed(collateral).principalFeed(principal)
                .txWindow(T0, txTo).build();
        Recording evaluator = new Recording(f.aiken());
        if (builds) {
            Transaction reread = Transaction.deserialize(f.production(evaluator).build(f.request).serialize());
            assertEquals(1, evaluator.calls.get(), label + ": exactly one evaluation");
            for (Redeemer redeemer : reread.getWitnessSet().getRedeemers()) {
                assertTrue(redeemer.getExUnits().getMem().compareTo(PLACEHOLDER) > 0
                                && redeemer.getExUnits().getSteps().compareTo(PLACEHOLDER) > 0,
                        label + ": placeholder ex-units on " + redeemer.getTag() + "#" + redeemer.getIndex());
            }
        } else {
            long pastFeed = Math.min(collateral.validTo(), principal.validTo());
            assertTrue(txTo > pastFeed && txTo <= Math.max(collateral.validTo(), principal.validTo()),
                    label + ": the window must end past exactly one feed");
            Exception refused = assertThrows(Exception.class, () -> f.production(evaluator).build(f.request));
            assertRefusedAt(refused, claimAction, label + " (loan_claim_action)");
        }
    }

    /**
     * (f) ⛔ M1: one rig makes both legs — two NFTs pricing two tokens, one credential. One reward address
     * carries one withdrawal, so {@code get_first} would hand the principal leg the NIGHT feed. Refused by name,
     * before the probe is asked and before anything is evaluated.
     */
    @Test
    void twoLegsOnOneOracleCredentialAreRefusedBeforeAnythingIsProbedOrEvaluated() throws Exception {
        ConvertTokenToTokenFixture f = spec().oneRig().build();
        assertEquals(f.collateralEntry.withdrawCredentialHash(), f.principalEntry.withdrawCredentialHash(),
                "one rig: one credential");
        OracleReferenceInputProbe probe = mock(OracleReferenceInputProbe.class);
        Recording evaluator = new Recording(f.aiken());

        ConvertTransactionBuilder.RefusedException refused = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> f.production(evaluator, probe).build(f.request));
        assertEquals(ConvertTransactionBuilder.Refusal.ORACLE_LEGS_SHARE_ONE_CREDENTIAL, refused.reason(),
                refused.getMessage());
        assertTrue(refused.getMessage().startsWith("ORACLE_LEGS_SHARE_ONE_CREDENTIAL:"), refused.getMessage());
        for (String named : List.of(NIGHT_V2_NFT.toUnit(), USDM_V2_NFT.toUnit(), NIGHT.toUnit(), USDM.toUnit(),
                f.collateralRig.scriptHash())) {
            assertTrue(refused.getMessage().contains(named), "the refusal names " + named + ": " + refused.getMessage());
        }
        verifyNoInteractions(probe);
        assertEquals(0, evaluator.calls.get(), "nothing may be evaluated");
    }

    /**
     * (g) ⛔ H1: the principal entry withdraws at a THIRD rig's credential while its reference input still sits at
     * the principal rig's NFT holder. {@code retrieve_oracle_data} would look for a withdrawal at the holder's
     * credential; refused by name on the layout probe's body, before anything is evaluated.
     */
    @Test
    void aLegWhoseReferenceInputSitsAtAnotherCredentialIsRefusedBeforeEvaluation() throws Exception {
        TestOracleRig third = newRig();
        ConvertTokenToTokenFixture f = spec().principalEntry(e -> new OracleEntry(e.token(), e.oracleToken(),
                third.rewardAddress(NETWORK), third.scriptHash(), e.referenceInput(), e.referenceScript(),
                e.verificationKeys(), e.threshold(), e.feed(), e.signatures(), e.charlieProviderReferenceInput(),
                e.oracleVersion(), e.charlieProviderNft())).build();
        assertNotEquals(f.principalRig.scriptHash(), f.principalEntry.withdrawCredentialHash());
        Recording evaluator = new Recording(f.aiken());

        ConvertTransactionBuilder.RefusedException refused = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> f.production(evaluator).build(f.request));
        assertEquals(ConvertTransactionBuilder.Refusal.ORACLE_LEG_CREDENTIAL_MISMATCH, refused.reason(),
                refused.getMessage());
        assertNotEquals(ConvertTransactionBuilder.Refusal.ORACLE_LEGS_SHARE_ONE_CREDENTIAL, refused.reason());
        assertTrue(refused.getMessage().contains("principal"), "the refusal names the leg: " + refused.getMessage());
        assertEquals(0, evaluator.calls.get(), "refused before any evaluation");
    }

    /**
     * (h) The finished-body check itself: on (a)'s deserialised body with the canonical indexes computed here, it
     * passes; with the principal oracle's withdrawal removed, and with the principal index moved by one, it
     * refuses {@code ORACLE_LEG_CREDENTIAL_MISMATCH}.
     */
    @Test
    void theFinishedBodyCheckRefusesAMissingOracleWithdrawalAndAWrongIndex() throws Exception {
        ConvertTokenToTokenFixture f = spec().build();
        ConvertTransactionBuilder builder = f.production(new Recording(f.aiken()));
        Transaction reread = Transaction.deserialize(builder.build(f.request).serialize());

        List<TransactionInput> sorted = new ArrayList<>(reread.getBody().getReferenceInputs());
        sorted.sort(java.util.Comparator.comparing(TransactionInput::getTransactionId)
                .thenComparingInt(TransactionInput::getIndex));
        long collateralIndex = sorted.indexOf(f.collateralEntry.referenceInput());
        long principalIndex = sorted.indexOf(f.principalEntry.referenceInput());
        assertTrue(collateralIndex >= 0 && principalIndex >= 0 && collateralIndex != principalIndex,
                "both NFT holders are reference inputs");
        builder.assertOracleLegs(reread, f.request, collateralIndex, principalIndex);

        Transaction noPrincipalWithdrawal = Transaction.deserialize(reread.serialize());
        List<Withdrawal> kept = new ArrayList<>(noPrincipalWithdrawal.getBody().getWithdrawals());
        assertTrue(kept.removeIf(w -> w.getRewardAddress().equals(f.principalEntry.rewardAddress())),
                "the principal oracle's withdrawal is in the body");
        noPrincipalWithdrawal.getBody().setWithdrawals(kept);
        ConvertTransactionBuilder.RefusedException missing = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> builder.assertOracleLegs(noPrincipalWithdrawal, f.request, collateralIndex, principalIndex));
        assertEquals(ConvertTransactionBuilder.Refusal.ORACLE_LEG_CREDENTIAL_MISMATCH, missing.reason());

        long wrong = (principalIndex + 1) % sorted.size();
        ConvertTransactionBuilder.RefusedException moved = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> builder.assertOracleLegs(reread, f.request, collateralIndex, wrong));
        assertEquals(ConvertTransactionBuilder.Refusal.ORACLE_LEG_CREDENTIAL_MISMATCH, moved.reason());
    }

    /** (i) The probe is asked once per build, about both token legs, collateral then principal. */
    @Test
    void theProbeIsAskedAboutBothLegsOncePerBuild() throws Exception {
        ConvertTokenToTokenFixture f = spec().build();
        OracleReferenceInputProbe probe = mock(OracleReferenceInputProbe.class);
        Recording evaluator = new Recording(f.aiken());

        f.production(evaluator, probe).build(f.request);

        verify(probe, times(1)).requireLive(List.of(f.collateralEntry, f.principalEntry));
        verifyNoMoreInteractions(probe);
        assertEquals(1, evaluator.calls.get(), "exactly one evaluation: pass 2's (CCL trap 23)");
    }

    /**
     * (j) The router, wired as {@code YaciConfig.convertLiquidationRouter} wires it, hands the builder both legs —
     * the transaction withdraws at both rig credentials — and the build clears {@code ConvertEconomics}, whose
     * figures are pinned independently.
     */
    @Test
    void theRouterHandsTheBuilderBothLegsAndTheBuildClearsConvertEconomics() throws Exception {
        ConvertTokenToTokenFixture f = spec().build();
        Map<String, TransactionInput> refs = f.referenceScripts;
        var references = new LiquidateTransactionBuilder.ReferenceScripts(refs.get(REGISTRY.getLoanPolicyId()),
                refs.get(REGISTRY.getLoanSpendScriptHash()), refs.get(REGISTRY.getLenderManagerWithdrawScriptHash()),
                refs.get(REGISTRY.getLenderManagerSpendScriptHash()), refs.get(REGISTRY.getLoanClaimActionScriptHash()),
                null, null, null, refs.get(REGISTRY.getLmLiquidateAndConvertActionScriptHash()));
        var liq = new AppConfig.LiquidationConfiguration(AppConfig.LiquidationConfiguration.Mode.LIVE, 60, 120, 30,
                BigInteger.valueOf(5_000_000L), 200, references);
        AppConfig.Network mainnet = new AppConfig.Network();
        mainnet.setNetworkForTest("mainnet");
        ConvertEconomics economics = new ConvertEconomics(new AppConfig.ConvertConfiguration(true), liq, mainnet);
        UtxoRepository poolIndex = mock(UtxoRepository.class);
        when(poolIndex.findUnspentByOwnerPaymentCredential(eq(MS_POOL_SPEND), any()))
                .thenReturn(Optional.of(List.of(nightUsdmPool())));
        Recording evaluator = new Recording(f.aiken());
        ConvertLiquidationRouter router = new YaciConfig().convertLiquidationRouter(REGISTRY, minswapConfigured(),
                new MinswapPoolResolver(poolIndex, MS_POOL_SPEND, MS_POOL_POLICY), economics,
                f.production(evaluator), liq, CONVERTERS, mainnet);

        Loan loan = new Loan(LOAN_TX, LOAN_IX, LOAN_ADDRESS, LOAN_ID, COLLATERAL_AMOUNT, LOAN_LOVELACE, f.loan);
        LenderBond bond = new LenderBond(LOAN_TX, BOND_IX, BOND_ADDRESS, LOAN_ID,
                LoanFixtures.fixture("mainnet-lender-bond-datum-328e44f9.hex"), f.bond);
        LiquidationAssessment assessment = LoanFixtures.assess(bond, loan, f.principalEntry.feed(),
                f.collateralEntry.feed(), T0);
        List<BigInteger> asked = new CopyOnWriteArrayList<>();
        Transaction built = router.buildConvertLiquidation(assessment, f.request.loanUtxo(), f.request.bondUtxo(),
                f.request.configUtxo(), f.request.lmConfigUtxo(), lovelace -> {
                    asked.add(lovelace);
                    return Optional.of(WALLET_UTXO);
                }, COLLATERAL_UTXO,
                Map.of(NIGHT_V2_NFT.toUnit(), f.collateralEntry, USDM_V2_NFT.toUnit(), f.principalEntry),
                BOT_ADDRESS, T0, T0 + 120_000L);

        assertEquals(List.of(BigInteger.valueOf(4_000_000L)), asked, "the selector is asked for the order's 4 ADA");
        Transaction reread = Transaction.deserialize(built.serialize());
        withdrawalIndex(reread, f.collateralRig.scriptHash());
        withdrawalIndex(reread, f.principalRig.scriptHash());
        assertEquals(1, evaluator.calls.get(), "exactly one evaluation: pass 2's (CCL trap 23)");

        BigInteger fee = reread.getBody().getFee();
        ConvertAssessment verdict = economics.assess(true, COLLATERAL_AMOUNT, 40, false, f.collateralEntry.feed(), fee);
        BigInteger outlay = fee.add(BigInteger.valueOf(4_000_000L)).max(BigInteger.valueOf(5_000_000L));
        log.info("FAB-223 router-built NIGHT→USDM convert: fee {}, verdict {}", fee, verdict);
        assertTrue(verdict.approved(), verdict.toString());
        assertEquals(BigInteger.valueOf(160_000_000L), verdict.liquidationFee(), "the fee in NIGHT units");
        assertEquals(BigInteger.valueOf(20_000_000L), verdict.feeValueLovelace(), "160 NIGHT at 0.125 ADA");
        assertEquals(outlay, verdict.outlay(), "outlay = max(F + 4,000,000, 5,000,000)");
        assertEquals(BigInteger.valueOf(20_000_000L).subtract(outlay), verdict.net(), "net = 20,000,000 − outlay");
    }

    // ======================================================================================
    // The leaves
    // ======================================================================================

    /** A positive control with these rigs, at the default feeds and window. */
    private static Transaction positiveControl(TestOracleRig c, TestOracleRig p) throws Exception {
        ConvertTokenToTokenFixture control = spec(c, p).build();
        return Transaction.deserialize(control.production(new Recording(control.aiken())).build(control.request)
                .serialize());
    }

    /** The position of {@code credentialHash}'s withdrawal in a same-test positive control. Never a constant. */
    private static int positiveControlIndex(TestOracleRig c, TestOracleRig p, String credentialHash) throws Exception {
        return withdrawalIndex(positiveControl(c, p), credentialHash);
    }

    private static boolean liquidatable(ConvertTokenToTokenFixture f, OraclePriceFeed collateralFeed,
                                        OraclePriceFeed principalFeed) {
        LiquidationMode.Liquidation liquidation = (LiquidationMode.Liquidation) f.loan.liquidationMode();
        return LoanFinance.canLiquidate(Rational.fromInt(f.debt), Rational.fromInt(COLLATERAL_AMOUNT),
                LoanFinance.liquidationLtv(liquidation), principalFeed, collateralFeed);
    }

    /**
     * H1 off the BUILT body: the reference input holding {@code nft} sits at {@code rig}'s script credential, and a
     * body withdrawal is keyed to that credential. Returns it.
     */
    private static String assertLegBoundAtItsCredential(ConvertTokenToTokenFixture f, Transaction body,
                                                        AssetType nft, TestOracleRig rig) {
        List<String> holders = new ArrayList<>();
        for (TransactionInput input : body.getBody().getReferenceInputs()) {
            Utxo utxo = f.utxoAt(input);
            if (utxo.getAmount().stream().anyMatch(a -> nft.toUnit().equals(a.getUnit()))) {
                holders.add(HexUtil.encodeHexString(new Address(utxo.getAddress()).getPaymentCredentialHash()
                        .orElseThrow()));
            }
        }
        assertEquals(List.of(rig.scriptHash()), holders, "exactly one reference input holds " + nft.toUnit()
                + ", at its rig's credential");
        Set<String> withdrawing = new HashSet<>();
        for (Withdrawal w : body.getBody().getWithdrawals()) {
            withdrawing.add(HexUtil.encodeHexString(new Address(w.getRewardAddress()).getDelegationCredentialHash()
                    .orElseThrow()));
        }
        assertTrue(withdrawing.contains(rig.scriptHash()), "no withdrawal keyed to " + nft.toUnit()
                + "'s holder credential " + rig.scriptHash());
        return rig.scriptHash();
    }

    private static TransactionOutput only(List<TransactionOutput> outs, String address, String what) {
        List<TransactionOutput> at = outs.stream().filter(o -> address.equals(o.getAddress())).toList();
        assertEquals(1, at.size(), "exactly one output for " + what + " at " + address);
        return at.get(0);
    }

    private static Set<String> witnessedScriptHashes(TransactionWitnessSet witnesses) {
        Set<String> hashes = new HashSet<>();
        Stream.of(witnesses.getPlutusV1Scripts(), witnesses.getPlutusV2Scripts(), witnesses.getPlutusV3Scripts())
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream)
                .forEach(script -> hashes.add(hashOf((PlutusScript) script)));
        return hashes;
    }

    private static AppConfig.LoansConfiguration minswapConfigured() {
        return new AppConfig.LoansConfiguration() {
            @Override
            public String getMinswapPoolAddress() {
                return POOL_ADDRESS;
            }

            @Override
            public String getMinswapPoolPolicyId() {
                return MS_POOL_POLICY;
            }

            @Override
            public String getMinswapPoolSpendScriptHash() {
                return MS_POOL_SPEND;
            }

            @Override
            public String getMinswapOrderSpendScriptHash() {
                return MS_ORDER_SPEND;
            }
        };
    }

    /** The recorded NIGHT/USDM pool, as the index row the resolver reads: recorded coordinate and full value. */
    private static AddressUtxoEntity nightUsdmPool() {
        var row = new AddressUtxoEntity();
        row.setTxHash(POOL_REF.getTransactionId());
        row.setOutputIndex(POOL_REF.getIndex());
        row.setOwnerAddr(POOL_ADDRESS);
        row.setOwnerPaymentCredential(MS_POOL_SPEND);
        row.setInlineDatum(LoanFixtures.fixture("mainnet-minswap-pool-night-usdm.hex"));
        List<Amt> amounts = new ArrayList<>();
        for (var amount : POOL_VALUE) {
            var amt = new Amt();
            amt.setUnit(amount.getUnit());
            amt.setQuantity(amount.getQuantity());
            amounts.add(amt);
        }
        row.setAmounts(amounts);
        return row;
    }
}
