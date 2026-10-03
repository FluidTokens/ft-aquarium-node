package com.fluidtokens.aquarium.offchain.service.wallet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletShape.Reason;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletShapeTransactions.Outcome;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletShapeTransactions.Status;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline builds with {@code LoanFixtures.protocolParams()} and MULTI-ASSET wallets (CCL trap 26: an
 * ada-only fixture hides an unbalanced body). Every assertion reads the DESERIALISED body, never the
 * calls that were made (trap 21).
 */
class WalletShapeTransactionsTest {

    private static final Account ACCOUNT = new Account(Networks.preview());
    private static final String WALLET = ACCOUNT.baseAddress();
    private static final BigInteger C = BigInteger.valueOf(5_000_000L);
    private static final BigInteger TEN_ADA = WalletShape.SHAPED_TOKEN_LOVELACE;

    private static final String UNIT_A = "aa".repeat(28) + "41";
    private static final String UNIT_B = "bb".repeat(28) + "42";
    private static final String JUNK_1 = "cc".repeat(28) + "4a31";
    private static final String JUNK_2 = "dd".repeat(28) + "4a32";
    private static final String NIGHT = "ee".repeat(28) + "4e49474854";
    private static final Set<String> RELEVANT = Set.of(UNIT_A, UNIT_B, NIGHT);

    private static int counter = 0;

    private final AtomicInteger submissions = new AtomicInteger();

    private final WalletShapeTransactions engine = new WalletShapeTransactions(ACCOUNT, LoanFixtures.protocolParams(),
            bytes -> {
                submissions.incrementAndGet();
                return Result.success("ok").withValue("hash");
            });

    private static Utxo utxo(long lovelace, Object... unitQuantityPairs) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(BigInteger.valueOf(lovelace)));
        for (int i = 0; i < unitQuantityPairs.length; i += 2) {
            amounts.add(Amount.asset((String) unitQuantityPairs[i], BigInteger.valueOf((Long) unitQuantityPairs[i + 1])));
        }
        Utxo u = new Utxo();
        u.setTxHash(String.format("%064x", 0x1000 + ++counter));
        u.setOutputIndex(counter % 3);
        u.setAddress(WALLET);
        u.setAmount(amounts);
        return u;
    }

    /** A messy multi-asset wallet: relevant tokens scattered with junk, ADA fragmented, no collateral. */
    private static List<Utxo> messyWallet() {
        return List.of(
                utxo(1_500_000, UNIT_A, 300L, JUNK_1, 5L),
                utxo(2_200_000, UNIT_B, 40L, UNIT_A, 200L),
                utxo(1_300_000, JUNK_2, 11L),
                utxo(80_000_000),
                utxo(45_000_000),
                utxo(7_000_000));
    }

    private static Transaction decoded(Outcome outcome) throws Exception {
        return Transaction.deserialize(outcome.transaction().serialize());
    }

    private static Map<String, BigInteger> units(TransactionOutput output) {
        return WalletShapeTransactions.unitsOf(output.getValue());
    }

    /** Test-side conservation, independent of the engine's own check. */
    private static void assertConserved(List<Utxo> inputs, Transaction tx) {
        Map<String, BigInteger> in = new TreeMap<>();
        inputs.forEach(u -> u.getAmount().forEach(a -> in.merge(a.getUnit(), a.getQuantity(), BigInteger::add)));
        Map<String, BigInteger> out = new TreeMap<>();
        tx.getBody().getOutputs().forEach(o -> units(o).forEach((unit, qty) -> {
            assertTrue(qty.signum() >= 0, "negative " + unit);
            out.merge(unit, qty, BigInteger::add);
        }));
        out.merge("lovelace", tx.getBody().getFee(), BigInteger::add);
        assertEquals(in, out);
    }

    @Test
    void messyWalletIsReshapedToExactPlanValues() throws Exception {
        List<Utxo> inputs = messyWallet();
        assertEquals(EnumSet.of(Reason.NO_EXACT_COLLATERAL, Reason.ADA_FRAGMENTED, Reason.RELEVANT_UNITS_SHARE_A_UTXO,
                        Reason.RELEVANT_UTXO_LOVELACE_NOT_EXACT, Reason.NON_RELEVANT_NOT_ONE_EXACT_UTXO,
                        Reason.RELEVANT_MIXED_WITH_NON_RELEVANT),
                WalletShape.reasons(inputs, RELEVANT, engine.collateralLovelace()));

        Outcome outcome = engine.buildShaped(inputs, RELEVANT);
        assertEquals(Status.BUILT, outcome.status(), outcome.detail());
        Transaction tx = decoded(outcome);

        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        assertEquals(5, outputs.size());
        assertEquals(Map.of("lovelace", TEN_ADA, UNIT_A, BigInteger.valueOf(500)), units(outputs.get(0)));
        assertEquals(Map.of("lovelace", TEN_ADA, UNIT_B, BigInteger.valueOf(40)), units(outputs.get(1)));
        assertEquals(Map.of("lovelace", TEN_ADA, JUNK_1, BigInteger.valueOf(5), JUNK_2, BigInteger.valueOf(11)),
                units(outputs.get(2)));
        assertEquals(Map.of("lovelace", C), units(outputs.get(3)));
        BigInteger inputLovelace = BigInteger.valueOf(1_500_000 + 2_200_000 + 1_300_000 + 80_000_000 + 45_000_000 + 7_000_000);
        assertEquals(Map.of("lovelace", inputLovelace.subtract(TEN_ADA.multiply(BigInteger.valueOf(3))).subtract(C)
                .subtract(tx.getBody().getFee())), units(outputs.get(4)));
        outputs.forEach(o -> assertEquals(WALLET, o.getAddress()));
        assertEquals(inputs.size(), tx.getBody().getInputs().size());
        assertEquals(0, submissions.get(), "a builder must never submit");
        assertTrue(tx.getWitnessSet() == null || tx.getWitnessSet().getVkeyWitnesses() == null
                || tx.getWitnessSet().getVkeyWitnesses().isEmpty(), "a builder must never sign");
    }

    @Test
    void builtOutputsFedBackAsUtxosAreAFixedPoint() throws Exception {
        Outcome outcome = engine.buildShaped(messyWallet(), RELEVANT);
        assertEquals(Status.BUILT, outcome.status(), outcome.detail());
        Transaction tx = decoded(outcome);
        String txHash = "f".repeat(64);

        List<Utxo> after = new ArrayList<>();
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            List<Amount> amounts = new ArrayList<>();
            units(outputs.get(i)).forEach((unit, qty) -> amounts.add(
                    "lovelace".equals(unit) ? Amount.lovelace(qty) : Amount.asset(unit, qty)));
            Utxo u = new Utxo();
            u.setTxHash(txHash);
            u.setOutputIndex(i);
            u.setAddress(outputs.get(i).getAddress());
            u.setAmount(amounts);
            after.add(u);
        }
        assertEquals(EnumSet.noneOf(Reason.class), WalletShape.reasons(after, RELEVANT, engine.collateralLovelace()));
    }

    @Test
    void shapedBuildConservesEveryUnit() throws Exception {
        List<Utxo> inputs = messyWallet();
        Outcome outcome = engine.buildShaped(inputs, RELEVANT);
        assertEquals(Status.BUILT, outcome.status(), outcome.detail());
        assertConserved(inputs, decoded(outcome));
    }

    @Test
    void aTamperedChangeOutputFailsTheConservationCheck() throws Exception {
        List<Utxo> inputs = messyWallet();
        Outcome outcome = engine.buildShaped(inputs, RELEVANT);
        assertEquals(Status.BUILT, outcome.status(), outcome.detail());
        Transaction tx = decoded(outcome);

        // Only the change's value is free in the shape check, so only conservation can see this.
        TransactionOutput change = tx.getBody().getOutputs().get(tx.getBody().getOutputs().size() - 1);
        change.getValue().setCoin(change.getValue().getCoin().add(BigInteger.ONE));

        String deviation = WalletShapeTransactions.shapeDeviation(tx, inputs, outcome.plan(), WALLET);
        assertNotNull(deviation);
        assertTrue(deviation.startsWith("not conserved: lovelace"), deviation);
    }

    /** The last output of a decoded shaped build — the change. */
    private static TransactionOutput changeOf(Transaction tx) {
        return tx.getBody().getOutputs().get(tx.getBody().getOutputs().size() - 1);
    }

    @Test
    void anInputCclAddedBeyondTheGivenListFailsTheInputsCheck() throws Exception {
        // Plan for the whole wallet, but hand CCL only the token UTxOs (5 ADA) through collectFrom, with a
        // supplier that also knows the ADA-only ones: the plan's 35 ADA cannot be met from the given list, so
        // CCL's coin selection reaches into the supplier and spends inputs nobody gave it.
        List<Utxo> wallet = messyWallet();
        List<Utxo> given = wallet.subList(0, 3);
        WalletShape.Plan plan = WalletShape.plan(wallet, RELEVANT, C);

        Tx tx = new Tx().from(WALLET).collectFrom(given);
        plan.outputs().forEach(output -> tx.payToAddress(WALLET, output.amounts()));
        tx.withChangeAddress(WALLET);
        Transaction built = new QuickTxBuilder(WalletShapeTransactions.inMemorySupplierOf(wallet),
                LoanFixtures.protocolParams(), (TransactionProcessor) null)
                .compose(tx).mergeOutputs(false).feePayer(WALLET).build();
        assertTrue(built.getBody().getInputs().size() > given.size(),
                "CCL must actually have added an input for this test to mean anything: " + built.getBody().getInputs());

        String deviation = WalletShapeTransactions.shapeDeviation(built, given, plan, WALLET);
        assertNotNull(deviation);
        assertTrue(deviation.startsWith("built inputs "), deviation);
    }

    @Test
    void anExtraAdaOnlyOutputFailsTheOutputCountCheck() throws Exception {
        List<Utxo> inputs = messyWallet();
        Outcome outcome = engine.buildShaped(inputs, RELEVANT);
        assertEquals(Status.BUILT, outcome.status(), outcome.detail());
        Transaction tx = decoded(outcome);

        // Split one ADA off the change into a trailing ADA-only output: still conserved, still at the
        // wallet, every planned output intact — only the count can see it.
        BigInteger oneAda = BigInteger.valueOf(1_000_000L);
        TransactionOutput change = changeOf(tx);
        change.getValue().setCoin(change.getValue().getCoin().subtract(oneAda));
        tx.getBody().getOutputs().add(new TransactionOutput(WALLET, Value.builder().coin(oneAda).build()));

        String deviation = WalletShapeTransactions.shapeDeviation(tx, inputs, outcome.plan(), WALLET);
        assertNotNull(deviation);
        assertTrue(deviation.startsWith("built body has 6 outputs"), deviation);
    }

    @Test
    void anOutputAtAForeignAddressFailsTheAddressCheck() throws Exception {
        List<Utxo> inputs = messyWallet();
        Outcome outcome = engine.buildShaped(inputs, RELEVANT);
        assertEquals(Status.BUILT, outcome.status(), outcome.detail());
        Transaction tx = decoded(outcome);

        // Same value, someone else's address: conservation and the shape both still hold.
        changeOf(tx).setAddress(LoanFixtures.botAddress());

        String deviation = WalletShapeTransactions.shapeDeviation(tx, inputs, outcome.plan(), WALLET);
        assertNotNull(deviation);
        assertTrue(deviation.startsWith("output 4 is at " + LoanFixtures.botAddress()), deviation);
    }

    @Test
    void aZeroFeeFailsTheFeeCheck() throws Exception {
        List<Utxo> inputs = messyWallet();
        Outcome outcome = engine.buildShaped(inputs, RELEVANT);
        assertEquals(Status.BUILT, outcome.status(), outcome.detail());
        Transaction tx = decoded(outcome);

        // Move the fee into the change: inputs == outputs + 0 still balances, so only the fee check sees it.
        TransactionOutput change = changeOf(tx);
        change.getValue().setCoin(change.getValue().getCoin().add(tx.getBody().getFee()));
        tx.getBody().setFee(BigInteger.ZERO);

        String deviation = WalletShapeTransactions.shapeDeviation(tx, inputs, outcome.plan(), WALLET);
        assertNotNull(deviation);
        assertTrue(deviation.startsWith("built body has no positive fee"), deviation);
    }

    @Test
    void anEmptyChangeFailsTheChangeCheck() throws Exception {
        List<Utxo> inputs = messyWallet();
        Outcome outcome = engine.buildShaped(inputs, RELEVANT);
        assertEquals(Status.BUILT, outcome.status(), outcome.detail());
        Transaction tx = decoded(outcome);

        // Burn the whole change as fee: conserved, positive fee, ADA-only change — of zero lovelace.
        TransactionOutput change = changeOf(tx);
        tx.getBody().setFee(tx.getBody().getFee().add(change.getValue().getCoin()));
        change.getValue().setCoin(BigInteger.ZERO);

        String deviation = WalletShapeTransactions.shapeDeviation(tx, inputs, outcome.plan(), WALLET);
        assertNotNull(deviation);
        assertTrue(deviation.startsWith("change output 4 holds no lovelace"), deviation);
    }

    @Test
    void sevenHundredInputsExceedMaxTxSizeInBothBuilders() {
        // Measured in the round-1 audit: 700 inputs build a ~25 KB transaction; the fixture's maxTxSize is 16,384.
        List<Utxo> inputs = new ArrayList<>();
        for (int i = 0; i < 700; i++) {
            inputs.add(utxo(2_000_000));
        }

        Outcome shaped = engine.buildShaped(inputs, RELEVANT);
        assertEquals(Status.REFUSED, shaped.status(), shaped.detail());
        assertNull(shaped.transaction());
        assertTrue(shaped.detail().startsWith("transaction too large: "), shaped.detail());
        assertTrue(shaped.detail().endsWith(" > maxTxSize 16384"), shaped.detail());

        Outcome consolidation = engine.buildConsolidation(inputs);
        assertEquals(Status.REFUSED, consolidation.status(), consolidation.detail());
        assertNull(consolidation.transaction());
        assertTrue(consolidation.detail().startsWith("transaction too large: "), consolidation.detail());
        assertTrue(consolidation.detail().endsWith(" > maxTxSize 16384"), consolidation.detail());
        assertEquals(0, submissions.get());
    }

    @Test
    void aHundredAndFiftyPolicyBundleExceedsMaxValSizeInTheConsolidation() {
        // 150 distinct policies spread over three UTxOs (each of which could exist on chain), consolidated
        // into ONE output whose value alone is ~10 KB; the fixture's maxValSize is 5,000.
        List<Utxo> inputs = new ArrayList<>();
        for (int u = 0; u < 3; u++) {
            List<Object> pairs = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                int n = u * 50 + i;
                pairs.add(String.format("%056x", 0xdef000 + n) + String.format("%064x", n));
                pairs.add(1_000_000L + n);
            }
            inputs.add(utxo(40_000_000, pairs.toArray()));
        }
        inputs.add(utxo(200_000_000));

        Outcome consolidation = engine.buildConsolidation(inputs);
        assertEquals(Status.REFUSED, consolidation.status(), consolidation.detail());
        assertNull(consolidation.transaction());
        assertTrue(consolidation.detail().startsWith("output value too large: output 0 value is "),
                consolidation.detail());
        assertTrue(consolidation.detail().endsWith(" > maxValSize 5000"), consolidation.detail());
    }

    @Test
    void smallWalletWhoseChangeIsNotTheLargestOutputIsRefusedButConsolidates() throws Exception {
        // Planned: A + 10 ADA, junk + 10 ADA, collateral 5 ADA. Change ≈ 2.5 ADA minus fee: smaller than the
        // 10-ADA token output, so CCL takes the fee out of THAT (FeeCalculators:153-172) and the shape breaks.
        List<Utxo> inputs = List.of(utxo(1_500_000, UNIT_A, 300L, JUNK_1, 5L), utxo(26_000_000));

        Outcome shaped = engine.buildShaped(inputs, RELEVANT);
        assertEquals(Status.REFUSED, shaped.status());
        assertNull(shaped.transaction());
        assertTrue(shaped.detail().startsWith("output 0 (RELEVANT_TOKEN)"), shaped.detail());

        Outcome consolidation = engine.buildConsolidation(inputs);
        assertEquals(Status.BUILT, consolidation.status(), consolidation.detail());
        Transaction tx = decoded(consolidation);
        assertConserved(inputs, tx);
        assertEquals(2, tx.getBody().getOutputs().size());
        Map<String, BigInteger> assetOutput = units(tx.getBody().getOutputs().get(0));
        assertEquals(BigInteger.valueOf(300), assetOutput.get(UNIT_A));
        assertEquals(BigInteger.valueOf(5), assetOutput.get(JUNK_1));
        assertEquals(Set.of("lovelace"), units(tx.getBody().getOutputs().get(1)).keySet());
    }

    @Test
    void consolidationConservesAndPutsEveryNativeAssetInOneOutput() throws Exception {
        List<Utxo> inputs = messyWallet();
        Outcome outcome = engine.buildConsolidation(inputs);
        assertEquals(Status.BUILT, outcome.status(), outcome.detail());
        Transaction tx = decoded(outcome);
        assertConserved(inputs, tx);

        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        assertEquals(2, outputs.size());
        Map<String, BigInteger> assets = new TreeMap<>(units(outputs.get(0)));
        BigInteger minAda = assets.remove("lovelace");
        assertEquals(Map.of(UNIT_A, BigInteger.valueOf(500), UNIT_B, BigInteger.valueOf(40),
                JUNK_1, BigInteger.valueOf(5), JUNK_2, BigInteger.valueOf(11)), assets);
        assertTrue(minAda.compareTo(TEN_ADA) < 0, "min-ADA, not a shaped 10 ADA: " + minAda);
        assertEquals(Set.of("lovelace"), units(outputs.get(1)).keySet());
    }

    @Test
    void adaOnlyConsolidationIsOneChangeOutput() throws Exception {
        List<Utxo> inputs = List.of(utxo(30_000_000), utxo(12_000_000));
        Outcome outcome = engine.buildConsolidation(inputs);
        assertEquals(Status.BUILT, outcome.status(), outcome.detail());
        Transaction tx = decoded(outcome);
        assertEquals(1, tx.getBody().getOutputs().size());
        assertConserved(inputs, tx);
    }

    @Test
    void junkBundleNeedingMoreThanTenAdaOfMinAdaIsRefused() {
        List<Object> pairs = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            pairs.add(String.format("%056x", 0xabc000 + i) + String.format("%064x", i));
            pairs.add(1_000_000L + i);
        }
        List<Utxo> inputs = List.of(utxo(30_000_000, pairs.toArray()), utxo(200_000_000));

        Outcome outcome = engine.buildShaped(inputs, RELEVANT);
        assertEquals(Status.REFUSED, outcome.status());
        assertNull(outcome.transaction());
        assertTrue(outcome.detail().startsWith("output 0 (NON_RELEVANT_TOKENS)"), outcome.detail());
    }

    @Test
    void nightUtxoGetsExactlyTenAda() throws Exception {
        // 15,000 NIGHT (6 decimals) sitting on 1.18 ADA, plus 200 ADA.
        List<Utxo> inputs = List.of(utxo(1_180_000, NIGHT, 15_000_000_000L), utxo(200_000_000));

        Outcome outcome = engine.buildShaped(inputs, RELEVANT);
        assertEquals(Status.BUILT, outcome.status(), outcome.detail());
        Transaction tx = decoded(outcome);
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        assertEquals(3, outputs.size());
        assertEquals(Map.of("lovelace", TEN_ADA, NIGHT, BigInteger.valueOf(15_000_000_000L)), units(outputs.get(0)));
        assertEquals(Map.of("lovelace", C), units(outputs.get(1)));
        assertConserved(inputs, tx);
    }

    @Test
    void emptyInputListIsNothingToDo() {
        Outcome shaped = engine.buildShaped(List.of(), RELEVANT);
        assertEquals(Status.NOTHING_TO_DO, shaped.status());
        assertNull(shaped.transaction());
        Outcome consolidation = engine.buildConsolidation(List.of());
        assertEquals(Status.NOTHING_TO_DO, consolidation.status());
        assertNull(consolidation.transaction());
    }

    @Test
    void anInputAtAnotherAddressIsRefused() {
        Utxo foreign = utxo(50_000_000);
        foreign.setAddress(LoanFixtures.botAddress());
        Outcome outcome = engine.buildShaped(List.of(utxo(30_000_000), foreign), RELEVANT);
        assertEquals(Status.REFUSED, outcome.status());
    }

    @Test
    void signAndSubmitAreSeparateCalls() throws Exception {
        Outcome outcome = engine.buildShaped(messyWallet(), RELEVANT);
        assertEquals(Status.BUILT, outcome.status(), outcome.detail());
        assertEquals(0, submissions.get());

        Transaction signed = engine.sign(outcome.transaction());
        assertEquals(1, signed.getWitnessSet().getVkeyWitnesses().size());
        assertEquals(0, submissions.get());

        assertTrue(engine.submit(signed).isSuccessful());
        assertEquals(1, submissions.get());
    }
}
