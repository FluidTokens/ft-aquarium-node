package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.Recording;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Feed;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.COLLATERAL_AMOUNT;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.LOAN_IX;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.LOAN_TX;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.BOND_IX;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.T0;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.USDM_FEED;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertAdaCollateralFixture.spec;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.BOT_ADDRESS;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.COLLATERAL_UTXO;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.DENOMINATOR;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.PLACEHOLDER;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.REGISTRY;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.USDM_V2_NFT;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.WALLET_UTXO;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.assertRefusedAt;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.entAddress;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.newRig;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.unitsOf;
import static com.fluidtokens.aquarium.offchain.service.loans.ConvertTokenToTokenFixture.withdrawalIndex;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * ⛔ FAB-224: an ADA-collateral convert, offline, against the deployed validators — the recorded mainnet ADA→USDM
 * loan {@code 2d067dae…#1} ({@link ConvertAdaCollateralFixture}), its one oracle leg (the USDM principal) signed by a
 * test-oracle rig.
 * <p>
 * Until FAB-224 the convert builder refused an ada collateral as {@code COLLATERAL_ORACLE_MISSING}, and unrefused it
 * would have spent the wallet utxo U and built the equity output as "the collateral token + 2 ADA", which
 * {@code equity_sent_to_borrower}'s ada DoS guard ({@code length(flatten(value)) == 1}) refuses. Here the build is
 * paid from the loan's own lovelace with NO wallet input, evaluates through the production constructor and the real
 * Aiken evaluator, tops a small equity up to the ledger minimum exactly, and spends exactly one wallet utxo when one is
 * handed; a healthy signed price and a tampered order amount are each refused by the named validator, identified by
 * its withdrawal's POSITION, read off a positive control built in the same test with the same rig (trap 14).
 * <p>
 * <b>Oracle variants proven (trap 25):</b> the principal leg SIGNED ({@code AGGREGATED}, multisig, threshold 1), as
 * the USDM v2 mainnet entry is; the collateral leg is ada and has no oracle.
 */
@Slf4j
class ConvertAdaCollateralRigTest {

    private static final BigInteger MAX_TX_MEM = BigInteger.valueOf(14_000_000L);
    private static final BigInteger MAX_TX_STEPS = BigInteger.valueOf(10_000_000_000L);
    /** The mainnet {@code maxTxSize}. The rig's params raise it to 1,000,000, so it is asserted here. */
    private static final int MAINNET_MAX_TX_SIZE = 16_384;
    private static final BigInteger ORDER_OVERHEAD = BigInteger.valueOf(4_000_000L);

    // ======================================================================================

    /** (a) The ADA→USDM convert is paid from the collateral: inputs {loan, bond}, no wallet input, C the collateral. */
    @Test
    void theAdaCollateralConvertIsPaidFromTheCollateralWithNoWalletInput() throws Exception {
        ConvertAdaCollateralFixture f = spec().build();

        // The fixture's figures, against the hand-derived ones.
        assertEquals(BigInteger.valueOf(554_841_420L), f.debt, "remaining debt at T0");
        assertFalse(LoanFinance.isRepaymentLate(f.loan, T0), "perpetual, period 0: never late");
        assertTrue(liquidatable(f, f.leg.signedFeed()), "liquidatable at the rig feed");
        assertEquals(BigInteger.valueOf(470_930_475L), f.equity, "equity in the collateral currency (lovelace)");
        assertTrue(f.plan.aToBDirection(), "ada is the pool's asset_a: a → b");
        assertEquals(BigInteger.valueOf(324_000_000L), f.plan.liquidationFee(), "the bot's fee, 40‰ of 8,100 ADA");
        assertEquals(BigInteger.valueOf(7_305_069_525L), f.plan.swappableCollateralAmount(), "swappable");
        assertEquals(BigInteger.valueOf(7_309_069_525L), f.plan.orderLovelace(), "swappable + the 4 ADA overhead");
        BigInteger quote = ConvertOrderPlan.constantProductOut(f.plan.swappableCollateralAmount(), f.pool.reserveA(),
                f.pool.reserveB(), f.pool.baseFeeANumerator());
        assertTrue(quote.compareTo(f.debt) >= 0, "the pool delivers the debt: quote " + quote);
        assertNull(f.request.walletUtxo(), "no wallet utxo is handed over");
        assertNull(f.request.collateralOracle(), "an ada collateral carries no oracle entry");

        Recording evaluator = new Recording(f.aiken());
        Transaction reread = Transaction.deserialize(f.production(evaluator).build(f.request).serialize());
        assertEquals(1, evaluator.calls.get(), "exactly one evaluation: pass 2's (CCL trap 23)");
        BigInteger fee = reread.getBody().getFee();

        // Inputs: exactly the loan and the bond — nothing from the wallet, though WALLET_UTXO sits in the universe.
        assertEquals(Set.of(new TransactionInput(LOAN_TX, LOAN_IX), new TransactionInput(LOAN_TX, BOND_IX)),
                Set.copyOf(reread.getBody().getInputs()), "the inputs are the loan and the bond only");
        assertEquals(2, reread.getBody().getInputs().size(), "two inputs");
        for (TransactionInput input : reread.getBody().getInputs()) {
            assertNotEquals(BOT_ADDRESS, f.utxoAt(input).getAddress(), "a wallet input was spent: " + input);
        }

        // Collateral: exactly C, its return non-negative, at C's address, and its total covering the fee.
        assertCollateralIsC(reread);

        // Ex-units off the BUILT body, each the evaluator's own (CCL traps 8 and 13).
        List<Redeemer> redeemers = reread.getWitnessSet().getRedeemers();
        assertEquals(8, redeemers.size(), "two spends, one burn, five withdrawals (the USDM oracle included)");
        assertEquals(2, redeemers.stream().filter(r -> r.getTag() == RedeemerTag.Spend).count(), "two spends");
        assertEquals(1, redeemers.stream().filter(r -> r.getTag() == RedeemerTag.Mint).count(), "one burn");
        assertEquals(5, redeemers.stream().filter(r -> r.getTag() == RedeemerTag.Reward).count(), "five withdrawals");
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
        log.info("FAB-224 ADA→USDM convert (no wallet input): fee {}, {} bytes, {} reference inputs, {} outputs, total "
                        + "mem {} steps {}, USDM oracle withdrawal #{}{}", fee, size,
                reread.getBody().getReferenceInputs().size(), reread.getBody().getOutputs().size(), mem, steps,
                withdrawalIndex(reread, f.rig.scriptHash()), table);
        assertTrue(size <= MAINNET_MAX_TX_SIZE, "over mainnet's maxTxSize: " + size);
        assertTrue(mem.compareTo(MAX_TX_MEM) <= 0, "total mem " + mem + " over the per-tx maximum");
        assertTrue(steps.compareTo(MAX_TX_STEPS) <= 0, "total steps " + steps + " over the per-tx maximum");

        // The order and the equity, exactly: ada and nothing else.
        List<TransactionOutput> outs = reread.getBody().getOutputs();
        TransactionOutput order = only(outs, f.request.orderAddress(), "the Minswap order");
        assertEquals(Set.of("lovelace"), unitsOf(order), "the order holds lovelace only");
        assertEquals(BigInteger.valueOf(7_309_069_525L), order.getValue().getCoin(), "the order's lovelace");
        TransactionOutput equityOut = only(outs, entAddress(REGISTRY.getAssetManagerSpendScriptHash()), "the equity");
        assertEquals(Set.of("lovelace"), unitsOf(equityOut), "the equity holds lovelace only");
        assertEquals(BigInteger.valueOf(470_930_475L), equityOut.getValue().getCoin(), "the borrower's equity");

        // The bot's lovelace: the fee, less the order overhead and the transaction fee, to the lovelace.
        assertEquals(BigInteger.valueOf(324_000_000L).subtract(ORDER_OVERHEAD).subtract(fee), coinAtBot(outs),
                "Σ coin(bot outputs) == liquidationFee − 4,000,000 − fee");

        // The principal leg, independently of the builder.
        assertPrincipalLegBoundAtTheRig(f, reread);

        f.assertConservation(reread);
    }

    /**
     * (b) USDM signed at a healthy 1,000,000,000 / 10⁸ while the plan and the claim stay at 1,250,000,000: the oracle
     * accepts its own signature, and {@code loan_claim_action} refuses the claim.
     */
    @Test
    void aHealthySignedPriceIsRefusedByLoanClaimAction() throws Exception {
        TestOracleRig rig = newRig();
        int claimAction = withdrawalIndex(positiveControl(rig), REGISTRY.getLoanClaimActionScriptHash());

        Feed healthy = new Feed(BigInteger.valueOf(1_000_000_000L), DENOMINATOR, USDM_FEED.validFrom(),
                USDM_FEED.validTo());
        ConvertAdaCollateralFixture f = spec(rig).usdmFeed(healthy).plannedUsdmFeed(USDM_FEED).build();
        assertFalse(liquidatable(f, f.leg.signedFeed()), "the loan must be healthy at the signed USDM price");
        Recording evaluator = new Recording(f.aiken());
        Exception refused = assertThrows(Exception.class, () -> f.production(evaluator).build(f.request));
        assertRefusedAt(refused, claimAction, "healthy signed USDM price (loan_claim_action)");
    }

    /**
     * (c) The order planned at equity + 1 — one lovelace less swapped — while the claim carries the true equity: the
     * ada branch's {@code >=} admits extra order lovelace but not a different {@code swap_amount}, and
     * {@code lm_liquidate_and_convert_action} refuses the order.
     */
    @Test
    void aTamperedOrderAmountIsRefusedByTheConvertAction() throws Exception {
        TestOracleRig rig = newRig();
        int convertAction = withdrawalIndex(positiveControl(rig), REGISTRY.getLmLiquidateAndConvertActionScriptHash());

        ConvertAdaCollateralFixture f = spec(rig).planEquityDelta(1).build();
        assertEquals(BigInteger.valueOf(7_305_069_524L), f.plan.swappableCollateralAmount(), "one lovelace less swapped");
        assertEquals(BigInteger.valueOf(470_930_475L), f.claim.equity(), "the claim keeps the true equity");
        Exception refused = assertThrows(Exception.class,
                () -> f.production(new Recording(f.aiken())).build(f.request));
        assertRefusedAt(refused, convertAction, "order amount off by one (lm_liquidate_and_convert_action)");
    }

    /**
     * (d) USDM at 1,327,000,000 / 10⁸ leaves an equity of 979,792 lovelace, below the ledger minimum for the equity
     * output. cardano-client-lib raises it to exactly that minimum (1,448,160) out of the bot's change, and the bot's
     * lovelace is short by exactly that top-up (CCL trap 6, made visible on the body).
     */
    @Test
    void anEquityBelowTheLedgerMinimumIsToppedUpExactlyAndChargedToTheBot() throws Exception {
        Feed thin = new Feed(BigInteger.valueOf(1_327_000_000L), DENOMINATOR, USDM_FEED.validFrom(), USDM_FEED.validTo());
        ConvertAdaCollateralFixture f = spec().usdmFeed(thin).build();
        assertEquals(BigInteger.valueOf(979_792L), f.equity, "a small equity");
        assertEquals(BigInteger.valueOf(7_775_020_208L), f.plan.swappableCollateralAmount(), "swappable");
        assertEquals(BigInteger.valueOf(7_779_020_208L), f.plan.orderLovelace(), "order lovelace");

        Recording evaluator = new Recording(f.aiken());
        Transaction reread = Transaction.deserialize(f.production(evaluator).build(f.request).serialize());
        assertEquals(1, evaluator.calls.get(), "exactly one evaluation: pass 2's (CCL trap 23)");
        BigInteger fee = reread.getBody().getFee();

        List<TransactionOutput> outs = reread.getBody().getOutputs();
        TransactionOutput equityOut = only(outs, entAddress(REGISTRY.getAssetManagerSpendScriptHash()), "the equity");
        assertEquals(Set.of("lovelace"), unitsOf(equityOut), "the equity holds lovelace only");
        BigInteger minAda = new MinAdaCalculator(EvalFixtures.protocolParams().getProtocolParams())
                .calculateMinAda(equityOut);
        assertEquals(BigInteger.valueOf(1_448_160L), minAda, "the equity output's ledger minimum");
        assertEquals(minAda, equityOut.getValue().getCoin(), "topped up to exactly the minimum, never beyond");
        BigInteger topUp = BigInteger.valueOf(1_448_160L - 979_792L);
        assertEquals(BigInteger.valueOf(324_000_000L).subtract(ORDER_OVERHEAD).subtract(fee).subtract(topUp),
                coinAtBot(outs), "Σ coin(bot outputs) == liquidationFee − 4,000,000 − fee − top-up");
        log.info("FAB-224 ADA→USDM convert, equity {} topped up by {} to {}: fee {}", f.equity, topUp, minAda, fee);
        f.assertConservation(reread);
    }

    /**
     * (e) A SYNTHETIC 1‰ bond (fee 8,100,000 lovelace) with the wallet utxo U handed over: exactly {loan, bond, U}
     * are spent, C stays the collateral, and the bot nets the fee less the overhead and the transaction fee.
     */
    @Test
    void aRequestWithOneWalletUtxoSpendsExactlyThatOne() throws Exception {
        ConvertAdaCollateralFixture f = spec().bondFeePerMille(1).walletUtxo(WALLET_UTXO).build();
        assertEquals(BigInteger.ONE, f.bond.liquidationFeePerMille(), "the synthetic bond's fee");
        assertEquals(BigInteger.valueOf(8_100_000L), f.plan.liquidationFee(), "1‰ of 8,100 ADA");
        assertEquals(BigInteger.valueOf(7_620_969_525L), f.plan.swappableCollateralAmount(), "swappable");

        Recording evaluator = new Recording(f.aiken());
        Transaction reread = Transaction.deserialize(f.production(evaluator).build(f.request).serialize());
        assertEquals(1, evaluator.calls.get(), "exactly one evaluation: pass 2's (CCL trap 23)");
        BigInteger fee = reread.getBody().getFee();

        assertEquals(Set.of(new TransactionInput(LOAN_TX, LOAN_IX), new TransactionInput(LOAN_TX, BOND_IX),
                        new TransactionInput(WALLET_UTXO.getTxHash(), WALLET_UTXO.getOutputIndex())),
                Set.copyOf(reread.getBody().getInputs()), "the loan, the bond and U, nothing else");
        assertEquals(3, reread.getBody().getInputs().size(), "three inputs");
        assertCollateralIsC(reread);
        for (Redeemer redeemer : reread.getWitnessSet().getRedeemers()) {
            assertTrue(redeemer.getExUnits().getMem().compareTo(PLACEHOLDER) > 0
                            && redeemer.getExUnits().getSteps().compareTo(PLACEHOLDER) > 0,
                    "placeholder ex-units on " + redeemer.getTag() + "#" + redeemer.getIndex());
        }
        BigInteger u = WALLET_UTXO.getAmount().get(0).getQuantity();
        assertEquals(BigInteger.valueOf(50_000_000L), u, "U is 50 ADA");
        assertEquals(BigInteger.valueOf(8_100_000L).subtract(ORDER_OVERHEAD).subtract(fee),
                coinAtBot(reread.getBody().getOutputs()).subtract(u),
                "Σ coin(bot outputs) − U == liquidationFee − 4,000,000 − fee");
        log.info("FAB-224 ADA→USDM convert, 1‰ bond, one wallet utxo: fee {}", fee);
        f.assertConservation(reread);
    }

    /** (f) The probe is asked once per build, about the principal leg only: an ada collateral adds no entry. */
    @Test
    void theProbeIsAskedAboutThePrincipalLegOnly() throws Exception {
        ConvertAdaCollateralFixture f = spec().build();
        OracleReferenceInputProbe probe = mock(OracleReferenceInputProbe.class);
        Recording evaluator = new Recording(f.aiken());

        f.production(evaluator, probe).build(f.request);

        verify(probe, times(1)).requireLive(List.of(f.principalEntry));
        verifyNoMoreInteractions(probe);
        assertEquals(1, evaluator.calls.get(), "exactly one evaluation: pass 2's (CCL trap 23)");
    }

    /**
     * (g) An ada collateral handed an oracle entry (the principal leg's): {@code retrieve_oracle_data} never reads an
     * oracle for an ada leg, so it is refused by name, before the probe is asked and before anything is evaluated.
     */
    @Test
    void anAdaCollateralHandedAnOracleIsRefusedByNameBeforeAnythingIsProbedOrEvaluated() throws Exception {
        ConvertAdaCollateralFixture f = spec().collateralHandedPrincipalEntry().build();
        assertEquals(f.principalEntry, f.request.collateralOracle(), "the ada collateral carries an entry");
        OracleReferenceInputProbe probe = mock(OracleReferenceInputProbe.class);
        Recording evaluator = new Recording(f.aiken());

        ConvertTransactionBuilder.RefusedException refused = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> f.production(evaluator, probe).build(f.request));
        assertEquals(ConvertTransactionBuilder.Refusal.COLLATERAL_ORACLE_MISSING, refused.reason(), refused.getMessage());
        assertNotEquals(ConvertTransactionBuilder.Refusal.PRINCIPAL_ORACLE_MISSING, refused.reason());
        assertTrue(refused.getMessage().contains("ada"), "the refusal names ada: " + refused.getMessage());
        verifyNoInteractions(probe);
        assertEquals(0, evaluator.calls.get(), "nothing may be evaluated");
    }

    /**
     * (h) The finished-body check itself, on (a)'s deserialised body: it passes as built, and on fresh copies it
     * refuses an equity coin one lovelace over, an equity output carrying a token, and a lovelace that leaked from the
     * bot's change to another address.
     */
    @Test
    void theFinishedBodyCheckRefusesAnAdaEquityOrFeeThatLeaked() throws Exception {
        ConvertAdaCollateralFixture f = spec().build();
        ConvertTransactionBuilder builder = f.production(new Recording(f.aiken()));
        Transaction reread = Transaction.deserialize(builder.build(f.request).serialize());
        byte[] bytes = reread.serialize();
        List<TransactionOutput> outs = reread.getBody().getOutputs();
        long success = indexOfDatum(outs, f.plan.successDatum().serializeToHex());
        long refund = indexOfDatum(outs, f.plan.refundDatum().serializeToHex());
        long bondEcho = indexOfDatum(outs, f.bondUtxo.getInlineDatum());
        builder.assertStructure(reread, f.request, success, refund, bondEcho);

        String am = entAddress(REGISTRY.getAssetManagerSpendScriptHash());

        // (i) the equity output's coin + 1: over max(equity, min-ada).
        Transaction overpaid = Transaction.deserialize(bytes);
        TransactionOutput equityOver = only(overpaid.getBody().getOutputs(), am, "the equity");
        equityOver.getValue().setCoin(equityOver.getValue().getCoin().add(BigInteger.ONE));
        ConvertTransactionBuilder.RefusedException over = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> builder.assertStructure(overpaid, f.request, success, refund, bondEcho));
        assertEquals(ConvertTransactionBuilder.Refusal.EQUITY_OUTPUT_MISMATCH, over.reason(), over.getMessage());

        // (ii) an extra token in the equity output: the ada DoS guard demands exactly 1 entry.
        Transaction withToken = Transaction.deserialize(bytes);
        TransactionOutput equityToken = only(withToken.getBody().getOutputs(), am, "the equity");
        equityToken.getValue().setMultiAssets(new ArrayList<>(List.of(new MultiAsset("ab".repeat(28),
                new ArrayList<>(List.of(new Asset("0x01", BigInteger.ONE)))))));
        ConvertTransactionBuilder.RefusedException token = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> builder.assertStructure(withToken, f.request, success, refund, bondEcho));
        assertEquals(ConvertTransactionBuilder.Refusal.EQUITY_OUTPUT_MISMATCH, token.reason(), token.getMessage());
        assertTrue(token.getMessage().contains("exactly 1"), token.getMessage());
        assertFalse(token.getMessage().contains("exactly 2"), token.getMessage());

        // (iii) 1,000,000 lovelace moved from the bot's change to an address that is not the bot's.
        Transaction leaked = Transaction.deserialize(bytes);
        List<TransactionOutput> leakedOuts = leaked.getBody().getOutputs();
        TransactionOutput change = leakedOuts.get(leakedOuts.size() - 1);
        assertEquals(BOT_ADDRESS, change.getAddress(), "the change is the last output, at the bot");
        assertNull(change.getInlineDatum(), "the change carries no datum");
        BigInteger moved = BigInteger.valueOf(1_000_000L);
        change.getValue().setCoin(change.getValue().getCoin().subtract(moved));
        String elsewhere = entAddress("77".repeat(28));
        assertNotEquals(BOT_ADDRESS, elsewhere);
        leakedOuts.add(new TransactionOutput(elsewhere, Value.builder().coin(moved).build()));
        ConvertTransactionBuilder.RefusedException fee = assertThrows(ConvertTransactionBuilder.RefusedException.class,
                () -> builder.assertStructure(leaked, f.request, success, refund, bondEcho));
        assertEquals(ConvertTransactionBuilder.Refusal.FEE_NOT_PAID_TO_BOT, fee.reason(), fee.getMessage());
    }

    /**
     * (i) A SYNTHETIC 0‰ bond and no wallet utxo: the loan's lovelace cannot fund the order overhead, the carriers and
     * the fee, and the build fails in cardano-client-lib's balancing at the bot address — it does not reach into the
     * wallet, whose U and C both sit in the universe, and nothing is evaluated.
     */
    @Test
    void aLoanThatCannotFundTheBuildFailsInBalancingWithoutReachingTheWallet() throws Exception {
        ConvertAdaCollateralFixture f = spec().bondFeePerMille(0).build();
        assertEquals(BigInteger.ZERO, f.plan.liquidationFee(), "the synthetic bond's fee");
        Recording evaluator = new Recording(f.aiken());

        Exception refused = assertThrows(Exception.class, () -> f.production(evaluator).build(f.request));
        StringBuilder chain = new StringBuilder();
        for (Throwable t = refused; t != null; t = t.getCause()) {
            chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
        }
        assertTrue(chain.toString().contains("Not enough funds"), "not a balancing failure: " + chain);
        assertEquals(0, evaluator.calls.get(), "nothing may be evaluated");
    }

    // ======================================================================================
    // The leaves
    // ======================================================================================

    /** A positive control with this rig, at the default feed and window. */
    private static Transaction positiveControl(TestOracleRig rig) throws Exception {
        ConvertAdaCollateralFixture control = spec(rig).build();
        return Transaction.deserialize(control.production(new Recording(control.aiken())).build(control.request)
                .serialize());
    }

    private static boolean liquidatable(ConvertAdaCollateralFixture f, OraclePriceFeed principalFeed) {
        LiquidationMode.Liquidation liquidation = (LiquidationMode.Liquidation) f.loan.liquidationMode();
        return LoanFinance.canLiquidate(Rational.fromInt(f.debt), Rational.fromInt(COLLATERAL_AMOUNT),
                LoanFinance.liquidationLtv(liquidation), principalFeed, OraclePriceFeed.unit());
    }

    /** Collateral is exactly C; its return sits at C's address, holds C − total_collateral ≥ 0; the total covers the fee. */
    private static void assertCollateralIsC(Transaction body) {
        assertEquals(List.of(new TransactionInput(COLLATERAL_UTXO.getTxHash(), COLLATERAL_UTXO.getOutputIndex())),
                body.getBody().getCollateral(), "the collateral is exactly C");
        BigInteger c = COLLATERAL_UTXO.getAmount().get(0).getQuantity();
        BigInteger total = body.getBody().getTotalCollateral();
        TransactionOutput ret = body.getBody().getCollateralReturn();
        assertEquals(COLLATERAL_UTXO.getAddress(), ret.getAddress(), "the collateral return goes back to C's address");
        assertEquals(c.subtract(total), ret.getValue().getCoin(), "collateral_return == C − total_collateral");
        assertTrue(ret.getValue().getCoin().signum() >= 0, "a negative collateral return (CCL trap 16)");
        BigDecimal percent = EvalFixtures.protocolParams().getProtocolParams().getCollateralPercent();
        BigInteger required = new BigDecimal(body.getBody().getFee()).multiply(percent)
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING).toBigIntegerExact();
        assertTrue(total.compareTo(required) >= 0, "total_collateral " + total + " under ceil(fee × "
                + percent + " / 100) = " + required);
    }

    /**
     * The principal leg off the BUILT body: the reference input holding the USDM v2 NFT sits at the rig's script
     * credential, a withdrawal is keyed to that credential, and no other withdrawal is at an oracle's: the others are
     * exactly the four loans-v4 withdraw-0s.
     */
    private static void assertPrincipalLegBoundAtTheRig(ConvertAdaCollateralFixture f, Transaction body) {
        List<String> holders = new ArrayList<>();
        for (TransactionInput input : body.getBody().getReferenceInputs()) {
            Utxo utxo = f.utxoAt(input);
            if (utxo.getAmount().stream().anyMatch(a -> USDM_V2_NFT.toUnit().equals(a.getUnit()))) {
                holders.add(HexUtil.encodeHexString(new Address(utxo.getAddress()).getPaymentCredentialHash()
                        .orElseThrow()));
            }
        }
        assertEquals(List.of(f.rig.scriptHash()), holders, "exactly one reference input holds the USDM v2 NFT, at "
                + "the rig's credential");
        Set<String> withdrawing = new HashSet<>();
        for (Withdrawal w : body.getBody().getWithdrawals()) {
            withdrawing.add(HexUtil.encodeHexString(new Address(w.getRewardAddress()).getDelegationCredentialHash()
                    .orElseThrow()));
        }
        assertEquals(Set.of(f.rig.scriptHash(), REGISTRY.getLoanPolicyId(), REGISTRY.getLoanClaimActionScriptHash(),
                        REGISTRY.getLenderManagerWithdrawScriptHash(), REGISTRY.getLmLiquidateAndConvertActionScriptHash()),
                withdrawing, "the rig's withdrawal and the four loans-v4 withdraw-0s, nothing else");
        for (String credential : withdrawing) {
            assertFalse(TestOracleRig.DEPLOYED_ORACLE_HASHES.contains(credential),
                    "a withdrawal at a deployed oracle's credential: " + credential);
        }
    }

    private static BigInteger coinAtBot(List<TransactionOutput> outs) {
        return outs.stream().filter(o -> BOT_ADDRESS.equals(o.getAddress()))
                .map(o -> o.getValue().getCoin()).reduce(BigInteger.ZERO, BigInteger::add);
    }

    private static TransactionOutput only(List<TransactionOutput> outs, String address, String what) {
        List<TransactionOutput> at = outs.stream().filter(o -> address.equals(o.getAddress())).toList();
        assertEquals(1, at.size(), "exactly one output for " + what + " at " + address);
        return at.get(0);
    }

    private static long indexOfDatum(List<TransactionOutput> outs, String datumHex) {
        long found = -1;
        for (int i = 0; i < outs.size(); i++) {
            if (outs.get(i).getInlineDatum() != null
                    && datumHex.equalsIgnoreCase(outs.get(i).getInlineDatum().serializeToHex())) {
                assertEquals(-1, found, "two outputs carry the datum " + datumHex);
                found = i;
            }
        }
        assertTrue(found >= 0, "no output carries the datum " + datumHex);
        return found;
    }
}
