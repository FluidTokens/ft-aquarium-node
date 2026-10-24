package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Final-body controls for the token-principal compound kernel. */
class CompoundLedgerValidationTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.previewDeploymentRegistry();
    private static final String BOT = LoanFixtures.botAddress();
    private static final String LOAN_ID = "e833a769ea3a480343175e253eab799ec0b058c99de30cc17160dc37";
    private static final String POOL_ID = "00d3513725536642b6fe985ce9ec87d1ebb880497d92e0a8495bc6d0bf";
    private static final String PRINCIPAL = "ab".repeat(28) + "01";
    private static final String UNRELATED = "cd".repeat(28) + "02";
    private static final BigInteger Q = BigInteger.valueOf(1_000_000L);
    private static final BigInteger F = BigInteger.valueOf(5_000L);

    @Test
    void tokenPrincipalIsAddedInRawUnitsWithoutChangingPoolAda() {
        Fixture fixture = tokenFixture();

        Transaction tx = fixture.builder().build(fixture.request());
        TransactionOutput pool = outputWithDatum(tx, fixture.pool());

        assertEquals(lovelace(fixture.pool()), pool.getValue().getCoin(),
                "token-principal compounding must not reinterpret raw token units as lovelace");
        assertEquals(quantity(fixture.pool(), PRINCIPAL).add(Q).subtract(F),
                quantity(pool, PRINCIPAL), "the pool gains exactly Q-F raw principal units");
    }

    @Test
    void unrelatedPoolTokenMutationIsRefusedByTheFinalBodyGuard() {
        Fixture fixture = adaFixture();
        Transaction tx = fixture.builder().build(fixture.request());
        TransactionOutput pool = outputWithDatum(tx, fixture.pool());
        pool.setValue(pool.getValue().add(asset(UNRELATED, BigInteger.ONE)));

        assertThrows(CompoundTransactionBuilder.RefusedException.class,
                () -> assertStructure(fixture, tx));
    }

    @Test
    void unrelatedBondTokenMutationIsRefusedByTheFinalBodyGuard() {
        Fixture fixture = adaFixture();
        Transaction tx = fixture.builder().build(fixture.request());
        TransactionOutput bond = outputWithDatum(tx, fixture.bondUtxo());
        bond.setValue(bond.getValue().add(asset(UNRELATED, BigInteger.ONE)));

        assertThrows(CompoundTransactionBuilder.RefusedException.class,
                () -> assertStructure(fixture, tx));
    }

    @Test
    void tokenPoolPrincipalPlusOrMinusOneIsRefused() {
        for (BigInteger mutation : List.of(BigInteger.ONE, BigInteger.ONE.negate())) {
            Fixture fixture = tokenFixture();
            Transaction tx = fixture.builder().build(fixture.request());
            TransactionOutput pool = outputWithDatum(tx, fixture.pool());
            pool.setValue(pool.getValue().add(asset(PRINCIPAL, mutation)));

            assertRefused(CompoundTransactionBuilder.Refusal.POOL_OUTPUT_MISMATCH,
                    () -> assertStructure(fixture, tx));
        }
    }

    @Test
    void underMinimumTokenPoolGetsExactlyTheStableCurrentMinimum() throws Exception {
        Utxo pool = withLovelace(tokenPool(), BigInteger.ONE);
        Fixture fixture = tokenFixture(pool, withLovelace(tokenEscrow(), BigInteger.valueOf(8_000_000L)));

        Transaction tx = fixture.builder().build(fixture.request());
        TransactionOutput output = outputWithDatum(tx, pool);
        ProtocolParams params = EvalFixtures.protocolParams().getProtocolParams();

        assertTrue(output.getValue().getCoin().compareTo(BigInteger.ONE) > 0,
                "a legacy under-minimum token pool requires an operator-funded top-up");
        assertEquals(new MinAdaCalculator(params).calculateMinAda(output), output.getValue().getCoin(),
                "the fixed point must carry exactly the current complete-output minimum");
    }

    @Test
    void tokenPoolMinimumStabilisesAcrossACoinWidthBoundary() throws Exception {
        Utxo pool = withLovelace(tokenPool(), BigInteger.valueOf(999_999L));
        Fixture fixture = tokenFixture(pool, withLovelace(tokenEscrow(), BigInteger.valueOf(8_000_000L)));

        TransactionOutput output = outputWithDatum(fixture.builder().build(fixture.request()), pool);
        BigInteger minimum = new MinAdaCalculator(EvalFixtures.protocolParams().getProtocolParams())
                .calculateMinAda(output);

        assertEquals(minimum.max(BigInteger.valueOf(999_999L)), output.getValue().getCoin());
    }

    @Test
    void omittedOrDoubledPoolTopUpIsRefused() {
        for (boolean omit : List.of(true, false)) {
            Utxo pool = withLovelace(tokenPool(), BigInteger.ONE);
            Fixture fixture = tokenFixture(pool, withLovelace(tokenEscrow(), BigInteger.valueOf(8_000_000L)));
            Transaction tx = fixture.builder().build(fixture.request());
            TransactionOutput output = outputWithDatum(tx, pool);
            BigInteger topUp = output.getValue().getCoin().subtract(BigInteger.ONE);
            output.getValue().setCoin(omit ? BigInteger.ONE : output.getValue().getCoin().add(topUp));

            assertRefused(CompoundTransactionBuilder.Refusal.POOL_OUTPUT_MISMATCH,
                    () -> assertStructure(fixture, tx));
        }
    }

    @Test
    void unrelatedManagerAndWalletTokenMutationsAreRefused() {
        Fixture managerFixture = tokenFixture();
        Transaction managerTx = managerFixture.builder().build(managerFixture.request());
        outputWithDatum(managerTx, managerFixture.request().candidate().poolManager())
                .setValue(outputWithDatum(managerTx, managerFixture.request().candidate().poolManager())
                        .getValue().add(asset(UNRELATED, BigInteger.ONE)));
        assertRefused(CompoundTransactionBuilder.Refusal.ECHO_NOT_BYTE_IDENTICAL,
                () -> assertStructure(managerFixture, managerTx));

        Fixture walletFixture = tokenFixture();
        Transaction walletTx = walletFixture.builder().build(walletFixture.request());
        walletOutput(walletTx).setValue(walletOutput(walletTx).getValue()
                .add(asset(UNRELATED, BigInteger.ONE)));
        assertRefused(CompoundTransactionBuilder.Refusal.VALUE_NOT_CONSERVED,
                () -> walletFixture.builder().validateLedgerShape(walletTx, walletFixture.request()));
    }

    @Test
    void aConservedWalletDonationIsStillRefusedByEconomicAccounting() {
        Fixture fixture = tokenFixture();
        Transaction tx = fixture.builder().build(fixture.request());
        TransactionOutput pool = outputWithDatum(tx, fixture.pool());
        TransactionOutput wallet = walletOutput(tx);
        pool.getValue().setCoin(pool.getValue().getCoin().add(BigInteger.ONE));
        wallet.getValue().setCoin(wallet.getValue().getCoin().subtract(BigInteger.ONE));

        assertRefused(CompoundTransactionBuilder.Refusal.BOT_NET_MISMATCH,
                () -> fixture.builder().validateLedgerShape(tx, fixture.request()));
    }

    @Test
    void unresolvedBalancerAddedInputIsRefused() {
        Fixture fixture = tokenFixture();
        Transaction tx = fixture.builder().build(fixture.request());
        tx.getBody().getInputs().add(new TransactionInput("ef".repeat(32), 7));

        assertRefused(CompoundTransactionBuilder.Refusal.INPUT_UNRESOLVED,
                () -> fixture.builder().validateLedgerShape(tx, fixture.request()));
    }

    @Test
    void balancerAddedUnrelatedTokenCannotBeDivertedFromWalletChange() {
        Utxo added = Utxo.builder().txHash("a7".repeat(32)).outputIndex(0).address(BOT)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)),
                        Amount.asset(UNRELATED, BigInteger.valueOf(7L)))).build();
        Fixture fixture = tokenFixture(tokenPool(), tokenEscrow(), List.of(added));
        Transaction tx = fixture.builder().build(fixture.request());
        tx.getBody().getInputs().add(new TransactionInput(added.getTxHash(), added.getOutputIndex()));
        TransactionOutput wallet = walletOutput(tx);
        wallet.setValue(wallet.getValue().addCoin(BigInteger.valueOf(2_000_000L)));
        TransactionOutput pool = outputWithDatum(tx, fixture.pool());
        pool.setValue(pool.getValue().add(asset(UNRELATED, BigInteger.valueOf(7L))));

        assertRefused(CompoundTransactionBuilder.Refusal.BOT_NET_MISMATCH,
                () -> fixture.builder().validateLedgerShape(tx, fixture.request()));
    }

    @Test
    void reusedTokenChangeIsRefused() {
        Fixture tokenFixture = tokenFixture();
        Transaction reused = tokenFixture.builder().build(tokenFixture.request());
        walletOutput(reused).setValue(walletOutput(reused).getValue()
                .add(asset(PRINCIPAL, BigInteger.ONE)));
        assertEquals(F.add(BigInteger.ONE), quantity(walletOutput(reused), PRINCIPAL),
                "the mutant must really duplicate one raw principal unit in change");
        assertEquals(F.add(BigInteger.ONE), quantity(walletOutput(deserialise(reused)), PRINCIPAL),
                "the duplicate must survive exact final-body serialization");
        assertEquals(F.add(BigInteger.ONE), ValueUtil.toAmountList(walletOutput(deserialise(reused)).getValue())
                        .stream().filter(a -> PRINCIPAL.equals(a.getUnit())).map(Amount::getQuantity)
                        .reduce(BigInteger.ZERO, BigInteger::add),
                "the validator's unit representation must see the duplicate");
        assertEquals(BigInteger.valueOf(9_000_000L).add(Q).subtract(F),
                quantity(outputWithDatum(deserialise(reused), tokenFixture.pool()), PRINCIPAL),
                "the mutant must not also alter the pool");
        assertRefused(CompoundTransactionBuilder.Refusal.VALUE_NOT_CONSERVED,
                () -> tokenFixture.builder().validateLedgerShape(reused, tokenFixture.request()));
    }

    @Test
    void nonzeroMintOrWithdrawalIsRefused() {
        Fixture withdrawalFixture = tokenFixture();
        Transaction withdrawal = withdrawalFixture.builder().build(withdrawalFixture.request());
        var first = withdrawal.getBody().getWithdrawals().getFirst();
        withdrawal.getBody().getWithdrawals().set(0,
                new com.bloxbean.cardano.client.transaction.spec.Withdrawal(
                        first.getRewardAddress(), BigInteger.ONE));
        assertRefused(CompoundTransactionBuilder.Refusal.MINT_OR_WITHDRAWAL_NOT_ZERO,
                () -> withdrawalFixture.builder().validateLedgerShape(withdrawal, withdrawalFixture.request()));

        Fixture mintFixture = tokenFixture();
        Transaction mint = mintFixture.builder().build(mintFixture.request());
        mint.getBody().getMint().add(new com.bloxbean.cardano.client.transaction.spec.MultiAsset(
                UNRELATED.substring(0, 56), List.of(new com.bloxbean.cardano.client.transaction.spec.Asset(
                "0x" + UNRELATED.substring(56), BigInteger.ONE))));
        assertRefused(CompoundTransactionBuilder.Refusal.MINT_OR_WITHDRAWAL_NOT_ZERO,
                () -> mintFixture.builder().validateLedgerShape(mint, mintFixture.request()));
    }

    @Test
    void wrongPrincipalUnitIsRefusedBeforeBalancing() {
        Utxo wrong = copyWithAmounts(tokenEscrow(), List.of(Amount.lovelace(BigInteger.valueOf(8_000_000L)),
                Amount.asset(UNRELATED, Q)));
        Fixture fixture = tokenFixture(tokenPool(), wrong);

        assertRefused(CompoundTransactionBuilder.Refusal.PRINCIPAL_MISMATCH,
                () -> fixture.builder().build(fixture.request()));
    }

    @Test
    void finalBodyHasLedgerShapeAndDistinctPureAdaCollateral() throws Exception {
        Fixture fixture = tokenFixture();
        Transaction tx = fixture.builder().build(fixture.request());
        fixture.builder().validateLedgerShape(tx, fixture.request());
        Transaction reread = Transaction.deserialize(tx.serialize());

        assertEquals(reread.getBody().getInputs().size(), new HashSet<>(reread.getBody().getInputs()).size());
        assertTrue(reread.getBody().getInputs().stream().allMatch(i -> fixture.universe().stream()
                .anyMatch(u -> u.getTxHash().equals(i.getTransactionId()) && u.getOutputIndex() == i.getIndex())));
        assertTrue(reread.getBody().getWithdrawals().stream().allMatch(w -> w.getCoin().signum() == 0));
        assertTrue(reread.getBody().getCollateral().stream()
                .noneMatch(new HashSet<>(reread.getBody().getInputs())::contains));
        assertEquals(Set.of("9f".repeat(32)), reread.getBody().getCollateral().stream()
                .map(TransactionInput::getTransactionId).collect(java.util.stream.Collectors.toSet()));
        BigInteger capacity = BigInteger.valueOf(5_000_000L);
        BigInteger actualRequired = reread.getBody().getFee()
                .multiply(EvalFixtures.protocolParams().getProtocolParams().getCollateralPercent().toBigIntegerExact())
                .add(BigInteger.valueOf(99L)).divide(BigInteger.valueOf(100L));
        assertTrue(capacity.compareTo(actualRequired) >= 0,
                "5 ADA covers the final measured fee's collateral requirement");
        assertTrue(capacity.compareTo(LiquidateTransactionBuilder.maxPossibleCollateral(
                EvalFixtures.protocolParams().getProtocolParams())) < 0,
                "this control must stay below the protocol-wide ceiling or it cannot kill the over-refusal");
        MinAdaCalculator minAda = new MinAdaCalculator(EvalFixtures.protocolParams().getProtocolParams());
        assertTrue(reread.getBody().getOutputs().stream()
                .allMatch(o -> o.getValue().getCoin().compareTo(minAda.calculateMinAda(o)) >= 0));
        assertTrue(reread.serialize().length < EvalFixtures.protocolParams().getProtocolParams().getMaxTxSize());
        assertEquals(11, reread.getWitnessSet().getRedeemers().size());
        assertTrue(reread.getWitnessSet().getRedeemers().stream().allMatch(r ->
                r.getExUnits().getMem().signum() > 0 && r.getExUnits().getSteps().signum() > 0));
        assertFalse(reread.getBody().getInputs().stream().map(i -> fixture.universe().stream()
                        .filter(u -> u.getTxHash().equals(i.getTransactionId()) && u.getOutputIndex() == i.getIndex())
                        .findFirst().orElseThrow().getAddress())
                .anyMatch(LoanFixtures.entAddress(REGISTRY.getLoanSpendScriptHash())::equals),
                "ordinary compound must contain zero loan spends");
    }

    @Test
    void collateralBelowTheActualFinalFeeRequirementIsRefused() {
        Fixture fixture = tokenFixture();
        Transaction tx = fixture.builder().build(fixture.request());
        BigInteger raisedFee = BigInteger.valueOf(4_000_000L);
        BigInteger delta = raisedFee.subtract(tx.getBody().getFee());
        TransactionOutput wallet = walletOutput(tx);
        wallet.getValue().setCoin(wallet.getValue().getCoin().subtract(delta));
        tx.getBody().setFee(raisedFee);

        assertRefused(CompoundTransactionBuilder.Refusal.COLLATERAL_NOT_DISTINCT_PURE_ADA,
                () -> fixture.builder().validateLedgerShape(tx, fixture.request()));
    }

    @Test
    void serializedFinalCollateralReturnBelowMinimumAdaIsRefused() throws Exception {
        Fixture fixture = tokenFixture();
        Transaction tx = fixture.builder().build(fixture.request());
        TransactionOutput collateralReturn = tx.getBody().getCollateralReturn();
        MinAdaCalculator calculator = new MinAdaCalculator(EvalFixtures.protocolParams().getProtocolParams());
        BigInteger minimum = calculator.calculateMinAda(collateralReturn);
        BigInteger below = minimum.subtract(BigInteger.ONE);
        BigInteger capacity = tx.getBody().getCollateral().stream()
                .map(input -> fixture.universe().stream()
                        .filter(u -> u.getTxHash().equals(input.getTransactionId())
                                && u.getOutputIndex() == input.getIndex())
                        .findFirst().orElseThrow())
                .map(CompoundLedgerValidationTest::lovelace)
                .reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger declared = capacity.subtract(below);
        BigInteger actualRequired = tx.getBody().getFee()
                .multiply(EvalFixtures.protocolParams().getProtocolParams().getCollateralPercent().toBigIntegerExact())
                .add(BigInteger.valueOf(99L)).divide(BigInteger.valueOf(100L));
        assertTrue(declared.compareTo(actualRequired) >= 0,
                "the mutant must retain enough declared collateral for the actual final fee");
        collateralReturn.getValue().setCoin(below);
        tx.getBody().setTotalCollateral(declared);
        Transaction serializedFinal = Transaction.deserialize(tx.serialize());
        assertEquals(below, serializedFinal.getBody().getCollateralReturn().getValue().getCoin(),
                "the below-minimum collateral return must survive final-body serialization");

        assertRefused(CompoundTransactionBuilder.Refusal.OUTPUT_BELOW_MIN_ADA,
                () -> fixture.builder().validateLedgerShape(serializedFinal, fixture.request()));
    }

    private static void assertStructure(Fixture fixture, Transaction tx) {
        List<String> order = fixture.builder().withdrawalOrder();
        fixture.builder().assertStructure(tx, fixture.request(), order,
                CompoundTransactionBuilder.rewardRedeemerIndex(order, REGISTRY.getPoolPolicyId()),
                CompoundTransactionBuilder.rewardRedeemerIndex(
                        order, REGISTRY.getLenderManagerWithdrawScriptHash()));
    }

    private static void assertRefused(CompoundTransactionBuilder.Refusal reason,
                                      org.junit.jupiter.api.function.Executable executable) {
        CompoundTransactionBuilder.RefusedException refused = assertThrows(
                CompoundTransactionBuilder.RefusedException.class, executable);
        assertEquals(reason, refused.getReason(), refused.getMessage());
    }

    private static Fixture tokenFixture() {
        return tokenFixture(tokenPool(), tokenEscrow());
    }

    private static Utxo tokenPool() {
        Utxo original = utxo("pool");
        return copyWithAmounts(original, append(original.getAmount(),
                Amount.asset(PRINCIPAL, BigInteger.valueOf(9_000_000L))));
    }

    private static Utxo tokenEscrow() {
        return copyWithAmounts(utxo("escrow"), List.of(
                Amount.lovelace(Q), Amount.asset(PRINCIPAL, Q)));
    }

    private static Fixture tokenFixture(Utxo pool, Utxo escrow) {
        return tokenFixture(pool, escrow, List.of());
    }

    private static Fixture tokenFixture(Utxo pool, Utxo escrow, List<Utxo> extras) {
        Utxo originalBond = utxo("bond");
        LenderManagerDatum old = new LenderManagerDatumConverter().deserialize(originalBond.getInlineDatum());
        LenderManagerDatum tokenDatum = new LenderManagerDatum(old.lenderAuth(), old.lenderStakeCredential(),
                old.shouldLiquidationConvertToPrincipal(), old.liquidationFeePerMille(), old.poolId(),
                AssetType.fromUnit(PRINCIPAL));
        LenderBond bond = new LenderBond(originalBond.getTxHash(), originalBond.getOutputIndex(),
                originalBond.getAddress(), LOAN_ID, originalBond.getInlineDatum(), tokenDatum);
        CompoundCandidate candidate = new CompoundCandidate(LOAN_ID, escrow,
                new AssetManagerDatumConverter().deserialize(escrow.getInlineDatum()), Q, bond, POOL_ID,
                pool, utxo("poolManager"), 5L, false, null, "synthetic token-principal candidate");
        return fixture(candidate, originalBond, pool, F, extras);
    }

    private static Fixture adaFixture() {
        Utxo bondUtxo = utxo("bond");
        LenderBond bond = new LenderBond(bondUtxo.getTxHash(), bondUtxo.getOutputIndex(),
                bondUtxo.getAddress(), LOAN_ID, bondUtxo.getInlineDatum(),
                new LenderManagerDatumConverter().deserialize(bondUtxo.getInlineDatum()));
        Utxo pool = utxo("pool");
        CompoundCandidate candidate = new CompoundCandidate(LOAN_ID, utxo("escrow"),
                new AssetManagerDatumConverter().deserialize(utxo("escrow").getInlineDatum()),
                BigInteger.valueOf(29_109_268L), bond, POOL_ID, pool, utxo("poolManager"),
                0L, true, null, "recorded ADA candidate");
        return fixture(candidate, bondUtxo, pool, BigInteger.ZERO);
    }

    private static Fixture fixture(CompoundCandidate candidate, Utxo bondUtxo, Utxo pool,
                                   BigInteger fee) {
        return fixture(candidate, bondUtxo, pool, fee, List.of());
    }

    private static Fixture fixture(CompoundCandidate candidate, Utxo bondUtxo, Utxo pool,
                                   BigInteger fee, List<Utxo> extras) {
        Utxo wallet = Utxo.builder().txHash("9e".repeat(32)).outputIndex(0).address(BOT)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(60_000_000L)))).build();
        Utxo collateral = Utxo.builder().txHash("9f".repeat(32)).outputIndex(0).address(BOT)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000L)))).build();
        Utxo config = config();
        Utxo lmConfig = lmConfig();
        List<Utxo> universe = new ArrayList<>(List.of(candidate.escrow(), bondUtxo, pool,
                candidate.poolManager(), config, lmConfig, wallet, collateral));
        universe.addAll(extras);
        CompoundTransactionBuilder builder = new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(), null);
        var request = new CompoundTransactionBuilder.Request(candidate, Map.of(), bondUtxo, config,
                lmConfig, wallet, BOT, fee, 70_000_000L, 70_000_300L);
        return new Fixture(builder, request, pool, bondUtxo, List.copyOf(universe));
    }

    private static TransactionOutput walletOutput(Transaction tx) {
        return tx.getBody().getOutputs().stream()
                .filter(o -> BOT.equals(o.getAddress()))
                .filter(o -> o.getInlineDatum() == null && o.getDatumHash() == null)
                .findFirst().orElseThrow();
    }

    private static Transaction deserialise(Transaction tx) {
        try {
            return Transaction.deserialize(tx.serialize());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static TransactionOutput outputWithDatum(Transaction tx, Utxo input) {
        return tx.getBody().getOutputs().stream()
                .filter(o -> input.getAddress().equals(o.getAddress()))
                .filter(o -> o.getInlineDatum() != null
                        && input.getInlineDatum().equalsIgnoreCase(o.getInlineDatum().serializeToHex()))
                .findFirst().orElseThrow();
    }

    private static Value asset(String unit, BigInteger quantity) {
        return Value.from(unit.substring(0, 56), "0x" + unit.substring(56), quantity);
    }

    private static BigInteger quantity(Utxo utxo, String unit) {
        return utxo.getAmount().stream().filter(a -> unit.equals(a.getUnit()))
                .map(Amount::getQuantity).findFirst().orElse(BigInteger.ZERO);
    }

    private static BigInteger quantity(TransactionOutput output, String unit) {
        return output.getValue().amountOf(unit.substring(0, 56), "0x" + unit.substring(56));
    }

    private static BigInteger lovelace(Utxo utxo) {
        return quantity(utxo, "lovelace");
    }

    private static List<Amount> append(List<Amount> amounts, Amount amount) {
        List<Amount> result = new ArrayList<>(amounts);
        result.add(amount);
        return result;
    }

    private static Utxo copyWithAmounts(Utxo source, List<Amount> amounts) {
        return Utxo.builder().txHash(source.getTxHash()).outputIndex(source.getOutputIndex())
                .address(source.getAddress()).amount(amounts).inlineDatum(source.getInlineDatum()).build();
    }

    private static Utxo withLovelace(Utxo source, BigInteger lovelace) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(lovelace));
        source.getAmount().stream().filter(a -> !"lovelace".equals(a.getUnit())).forEach(amounts::add);
        return copyWithAmounts(source, amounts);
    }

    private static Utxo utxo(String key) {
        try (InputStream is = CompoundLedgerValidationTest.class
                .getResourceAsStream("/loans-v4/compound-candidate-e833a769.json")) {
            JsonNode node = new ObjectMapper().readTree(is).get(key);
            List<Amount> amounts = new ArrayList<>();
            node.get("amount").forEach(a -> amounts.add(Amount.builder().unit(a.get("unit").asText())
                    .quantity(new BigInteger(a.get("quantity").asText())).build()));
            return Utxo.builder().txHash(node.get("txHash").asText())
                    .outputIndex(node.get("outputIndex").asInt()).address(node.get("address").asText())
                    .amount(amounts).inlineDatum(node.get("inlineDatum").isNull()
                            ? null : node.get("inlineDatum").asText()).build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Utxo config() {
        Utxo captured = utxo("config");
        return LoanFixtures.syntheticConfigUtxoFor(REGISTRY, captured.getTxHash(), captured.getOutputIndex());
    }

    private static Utxo lmConfig() {
        Utxo captured = utxo("lmConfig");
        return LoanFixtures.syntheticLmConfigUtxoFor(REGISTRY, captured.getTxHash(), captured.getOutputIndex());
    }

    private record Fixture(CompoundTransactionBuilder builder,
                           CompoundTransactionBuilder.Request request,
                           Utxo pool,
                           Utxo bondUtxo,
                           List<Utxo> universe) {
    }
}
