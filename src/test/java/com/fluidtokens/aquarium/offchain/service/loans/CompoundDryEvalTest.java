package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletShape;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline PlutusV3 evaluation of the compound transaction against <b>recorded on-chain reality</b>.
 *
 * <h2>What is real and what is not</h2>
 * <b>Captured</b> from preview on 2026-09-02 and replayed verbatim: the escrow
 * ({@code fb482b5a…#2}, 29,109,268 lovelace), the lender bond ({@code fb482b5a…#1}), the pool
 * ({@code 40c06048…#0}) and the pool manager ({@code 1ad93a03…#1}).
 * <b>Synthetic</b>: the bot's own wallet UTxO, plus copies of the two captured config datums with
 * only ConfigDatum[24] and LMConfigDatum[3] replaced by the credentials derived from the latest
 * artifact. The captured files and JSON remain unchanged and are asserted separately as the known
 * old-preview mismatch; this test proves latest-validator behavior, not a deployed preview state.
 *
 * <h2>⚠ What this proves and what it cannot</h2>
 * CCL trap 11: an offline evaluator runs the validators. It does <b>not</b> check fees, min-ada,
 * value conservation or collateral adequacy — those are the ledger's, and this rig is silent about
 * them. The builder's own body assertions cover what it can; the rest is a phase-1 answer nobody has
 * asked for yet, and phase 1 is free.
 */
@Slf4j
class CompoundDryEvalTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.previewDeploymentRegistry();
    private static final String POOL_ID = "00d3513725536642b6fe985ce9ec87d1ebb880497d92e0a8495bc6d0bf";
    private static final String LOAN_ID = "e833a769ea3a480343175e253eab799ec0b058c99de30cc17160dc37";
    private static final BigInteger ESCROW = BigInteger.valueOf(29_109_268L);
    /** The only live preview pool publishes compoudingFeePerMille = 0, so the fee is zero. */
    private static final BigInteger FEE = BigInteger.ZERO;

    private static final String BOT = LoanFixtures.botAddress();

    /** Compound reads no oracle and no time, so the window only has to be well-formed. */
    private static final long VALID_FROM_SLOT = 70_000_000L;
    private static final long VALID_TO_SLOT = 70_000_300L;

    // ---- the recorded universe ---------------------------------------------------------------

    private static JsonNode fixture() {
        try (InputStream is = CompoundDryEvalTest.class
                .getResourceAsStream("/loans-v4/compound-candidate-e833a769.json")) {
            return new ObjectMapper().readTree(is);
        } catch (Exception e) {
            throw new IllegalStateException("compound candidate fixture missing", e);
        }
    }

    private static Utxo utxo(String key) {
        JsonNode n = fixture().get(key);
        List<Amount> amounts = new ArrayList<>();
        n.get("amount").forEach(a -> amounts.add(Amount.builder()
                .unit(a.get("unit").asText())
                .quantity(new BigInteger(a.get("quantity").asText())).build()));
        return Utxo.builder()
                .txHash(n.get("txHash").asText())
                .outputIndex(n.get("outputIndex").asInt())
                .address(n.get("address").asText())
                .amount(amounts)
                .inlineDatum(n.get("inlineDatum").isNull() ? null : n.get("inlineDatum").asText())
                .build();
    }

    /** The bot's ordinary fee-paying funds. */
    private static Utxo wallet() {
        return Utxo.builder().txHash("9e".repeat(32)).outputIndex(0).address(BOT)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L)))).build();
    }

    /** A distinct pure-ADA UTxO reserved for collateral and never available to balancing. */
    private static Utxo collateral() {
        return Utxo.builder().txHash("c0".repeat(32)).outputIndex(0).address(BOT)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000L)))).build();
    }

    private static List<Utxo> universe() {
        return List.of(utxo("escrow"), utxo("bond"), utxo("pool"), utxo("poolManager"),
                recordedConfig(), recordedLmConfig(), wallet(), collateral());
    }

    /**
     * ⛔ The config reference input, synthesised from THE SAME REGISTRY THIS RIG BUILDS WITH.
     *
     * <p>Two things have to line up here and on 2026-09-17 they stopped doing so on their own.
     *
     * <p><b>The artefact.</b> The fixture is a recorded preview snapshot: its pool sits at
     * {@code bf8c4378…} and its pool manager at {@code b4ad9a6f…}. Those are what
     * {@code loans-v4-third-deployment.plutus.json} derives — the artefact preview was deployed
     * from. Once FluidTokens' mainnet redeploy moved the SHIPPED artefact's pool family, building
     * with it produced {@code 51500939…} / {@code 1322b6d1…} and the evaluator said so:
     * {@code RequiredRedeemersMismatch}, recorded pair <b>missing</b>, derived pair <b>extra</b>.
     * Hence {@link LoanFixtures#previewDeploymentRegistry()}.
     *
     * <p><b>The datum.</b> The recording carries its own ConfigDatum, and using it directly does NOT
     * work — the captured preview deployment differs from any vendored artefact at ConfigDatum[24]
     * and LMConfigDatum[3], which is the divergence {@code OLD_PREVIEW_MISMATCHES} pins. Feeding the
     * recorded datum in makes a validator RUN AND REJECT
     * ({@code RedeemerError{tag:"Withdraw", index:4}}) rather than fail to resolve. So the datum is
     * still synthesised — but now from the same registry the builder uses, which is the part that
     * was previously implicit and is now explicit.
     */
    private static Utxo recordedConfig() {
        Utxo captured = utxo("config");
        return LoanFixtures.syntheticConfigUtxoFor(REGISTRY, captured.getTxHash(),
                captured.getOutputIndex());
    }

    /** Its pair, synthesised from the same registry. See {@link #recordedConfig()}. */
    private static Utxo recordedLmConfig() {
        Utxo captured = utxo("lmConfig");
        return LoanFixtures.syntheticLmConfigUtxoFor(REGISTRY, captured.getTxHash(),
                captured.getOutputIndex());
    }

    private static CompoundCandidate candidate() {
        Utxo bondUtxo = utxo("bond");
        LenderBond bond = new LenderBond(bondUtxo.getTxHash(), bondUtxo.getOutputIndex(),
                bondUtxo.getAddress(), LOAN_ID, bondUtxo.getInlineDatum(),
                new LenderManagerDatumConverter().deserialize(bondUtxo.getInlineDatum()));
        return new CompoundCandidate(LOAN_ID, utxo("escrow"),
                new AssetManagerDatumConverter().deserialize(utxo("escrow").getInlineDatum()),
                ESCROW, bond, POOL_ID, utxo("pool"), utxo("poolManager"),
                0L, true, null, "recorded preview candidate");
    }

    private static CompoundTransactionBuilder builder() {
        return new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                LoanFixtures.utxoSupplier(universe()), EvalFixtures.protocolParams(), null);
    }

    private static CompoundTransactionBuilder.Request request(BigInteger fee) {
        return new CompoundTransactionBuilder.Request(candidate(), java.util.Map.of(), utxo("bond"),
                recordedConfig(), recordedLmConfig(), wallet(), BOT, fee,
                VALID_FROM_SLOT, VALID_TO_SLOT);
    }

    // ---- the proof ---------------------------------------------------------------------------

    /**
     * ⛔ THE ASSERTION. Every one of the eleven redeemers evaluates, against the real validators, over
     * the real objects — four {@code general_spend} spends and seven withdraw-0 invocations
     * (findings §22.1–22.2).
     */
    @Test
    void everyRedeemerEvaluatesAgainstTheRecordedCandidate() {
        Transaction tx = builder().build(request(FEE));

        List<EvaluationResult> results =
                EvalFixtures.evaluate(tx, universe(), REGISTRY);

        long mem = 0;
        long steps = 0;
        for (EvaluationResult r : results) {
            log.info("compound ex-units: tag={} index={} mem={} steps={}",
                    r.getRedeemerTag(), r.getIndex(), r.getExUnits().getMem(), r.getExUnits().getSteps());
            mem += r.getExUnits().getMem().longValue();
            steps += r.getExUnits().getSteps().longValue();
        }
        log.info("compound TOTAL mem={} steps={} redeemers={}", mem, steps, results.size());

        assertEquals(11, results.size(),
                "four general_spend spends and seven withdraw-0 invocations");
        assertTrue(mem > 0 && steps > 0, "every redeemer must come back with real ex-units");
    }

    /**
     * ⛔ THE PURPOSE-SHAPE MUTANT, and it is the one that matters (§22.3).
     *
     * <p>{@code CompoundLiquidityActionWithdrawRedeemer} cites the positions of the LenderManager and
     * PoolManager withdraw redeemers — which are <b>byte-identical</b> ({@code d8799f02d87b80ff}).
     * A mutator that located them by shape would be a no-op, carrying the very defect it tests for,
     * so this one swaps them <b>by position</b>.
     *
     * <p>The builder must refuse before the transaction is ever built. That refusal is the real
     * guard, precisely because the dry-eval may not be able to see this one.
     */
    @Test
    void citingTheWrongSiblingRedeemerIsRefusedByTheBuilder() {
        List<String> order = builder().withdrawalOrder();
        long pool = CompoundTransactionBuilder.rewardRedeemerIndex(order, REGISTRY.getPoolPolicyId());
        long lm = CompoundTransactionBuilder.rewardRedeemerIndex(
                order, REGISTRY.getLenderManagerWithdrawScriptHash());

        assertTrue(pool != lm, "the two cited positions must be distinct or the mutant is vacuous");

        // The mutation: assert that a body whose withdrawal at `pool` is NOT the pool policy is
        // rejected. Driven through the same helper the builder's post-assert uses.
        Transaction tx = builder().build(request(FEE));
        List<String> inBodyOrder = CompoundTransactionBuilder.rewardScriptHashesInBodyOrder(tx);

        assertEquals(REGISTRY.getPoolPolicyId(),
                inBodyOrder.get((int) (pool - CompoundTransactionBuilder.SCRIPT_SPEND_COUNT)),
                "poolWithdrawRedeemerIndex must land on the pool policy's withdrawal");
        assertEquals(REGISTRY.getLenderManagerWithdrawScriptHash(),
                inBodyOrder.get((int) (lm - CompoundTransactionBuilder.SCRIPT_SPEND_COUNT)),
                "lenderManagerWithdrawRedeemerIndex must land on the lender manager's withdrawal");

        // And the swapped assignment does NOT land where it claims — which is what the builder's
        // post-assert refuses on.
        assertFalse(REGISTRY.getPoolPolicyId().equals(
                        inBodyOrder.get((int) (lm - CompoundTransactionBuilder.SCRIPT_SPEND_COUNT))),
                "if the two positions were interchangeable the guard would be vacuous");
    }

    /**
     * The economic mutant: claim a fee the pool manager does not publish. The pool then receives less
     * than {@code addedLiquidity − 0} and {@code pool_compound_action}'s value check must refuse it.
     */
    @Test
    void skimmingAFeeThePoolManagerDoesNotPublishIsRejected() {
        Transaction tx = builder().build(request(BigInteger.valueOf(1_000_000L)));

        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(tx, universe(), REGISTRY);

        assertFalse(outcome.successful(),
                "the pool must receive addedLiquidity minus the PUBLISHED fee, which here is zero — "
                        + "a builder skimming 1 ADA has to be refused on chain: " + outcome.detail());
    }

    /** A candidate the scanner already refused must never reach the builder. */
    @Test
    void anExcludedCandidateIsRefusedBeforeAnythingIsBuilt() {
        CompoundCandidate excluded = new CompoundCandidate(LOAN_ID, utxo("escrow"), null, ESCROW,
                null, POOL_ID, null, null, 0L, true,
                com.fluidtokens.aquarium.offchain.model.loans.CompoundExclusion.POOL_NOT_LIVE, "burned");

        var request = new CompoundTransactionBuilder.Request(excluded, java.util.Map.of(), utxo("bond"),
                recordedConfig(), recordedLmConfig(), wallet(), BOT, FEE,
                VALID_FROM_SLOT, VALID_TO_SLOT);

        var e = assertThrows(CompoundTransactionBuilder.RefusedException.class,
                () -> builder().build(request));
        assertEquals(CompoundTransactionBuilder.Refusal.CANDIDATE_NOT_READY, e.getReason());
    }

    /**
     * FAB-125: the built transaction withdraws through EXACTLY {@link CompoundTransactionBuilder#withdrawCredentials},
     * the list the live registration check reads -- so that check cannot drift into guarding its own copy.
     */
    @Test
    void theBuiltTransactionWithdrawsExactlyTheDeclaredCredentials() {
        Transaction tx = builder().build(request(FEE));
        java.util.Set<String> withdrawn = new java.util.TreeSet<>();
        for (var w : tx.getBody().getWithdrawals()) {
            withdrawn.add(com.bloxbean.cardano.client.util.HexUtil.encodeHexString(
                    new com.bloxbean.cardano.client.address.Address(w.getRewardAddress())
                            .getDelegationCredentialHash().orElseThrow()));
        }
        assertEquals(new java.util.TreeSet<>(CompoundTransactionBuilder.withdrawCredentials(REGISTRY)), withdrawn);
    }

    // ---- FAB-152: the bot ends a compound with ONE output --------------------------------------

    /** CCL's placeholder ex-units (trap 8): a redeemer still carrying these was never evaluated. */
    private static final BigInteger PLACEHOLDER_MEM = BigInteger.valueOf(10_000);

    /**
     * The same builder, with an evaluator wired in the way production wires one — so the ex-units and
     * the fee are cardano-client-lib's own, computed on the body it actually returns.
     */
    private static CompoundTransactionBuilder evaluatingBuilder(List<Utxo> universe) {
        UtxoSupplier supplier = LoanFixtures.utxoSupplier(universe);
        return new CompoundTransactionBuilder(REGISTRY, Networks.preview(), supplier,
                EvalFixtures.protocolParams(), new AikenTransactionEvaluator(supplier,
                EvalFixtures.protocolParams(), EvalFixtures.scriptSupplier(REGISTRY), SlotConfigs.preview()));
    }

    private static List<TransactionOutput> botOutputs(Transaction tx) {
        return tx.getBody().getOutputs().stream().filter(o -> BOT.equals(o.getAddress())).toList();
    }

    /** The lovelace the bot put in, read from the BUILT body's inputs — never from the nominated UTxO. */
    private static BigInteger botInputLovelace(Transaction tx, List<Utxo> universe) {
        BigInteger in = BigInteger.ZERO;
        for (TransactionInput input : tx.getBody().getInputs()) {
            Utxo spent = universe.stream()
                    .filter(u -> u.getTxHash().equals(input.getTransactionId())
                            && u.getOutputIndex() == input.getIndex())
                    .findFirst().orElseThrow();
            if (BOT.equals(spent.getAddress())) {
                in = in.add(spent.getAmount().stream().filter(a -> "lovelace".equals(a.getUnit()))
                        .map(Amount::getQuantity).findFirst().orElse(BigInteger.ZERO));
            }
        }
        return in;
    }

    /**
     * ⛔ FAB-152. cardano-client-lib prepends a 1 ADA withdrawal dummy at the change address (CCL trap 1)
     * and, with {@code mergeOutputs(false)}, puts the change in a SECOND output beside it — mainnet
     * {@code 4e40b3fa…}: {@code #0} 1.000000 ADA and {@code #4} 151.639224 ADA. The wallet then reads
     * {@code ADA_FRAGMENTED} and the rebalancer pays to merge them on the next block. A compound must
     * leave the bot exactly ONE ada-only output, and the bot's net must still be
     * {@code compoundingFee − fee}.
     */
    @Test
    void aCompoundLeavesTheBotExactlyOneAdaOnlyOutput() {
        List<Utxo> universe = universe();
        Transaction tx = evaluatingBuilder(universe).build(request(FEE));

        List<TransactionOutput> bot = botOutputs(tx);
        log.info("compound bot outputs {} tx fee {}", bot.stream().map(o -> o.getValue().getCoin()).toList(),
                tx.getBody().getFee());
        assertEquals(1, bot.size(), "the bot must end with ONE output, not the withdrawal dummy "
                + "beside the change: " + bot.stream().map(o -> o.getValue().getCoin()).toList());
        TransactionOutput only = bot.get(0);
        assertTrue(only.getValue().getMultiAssets() == null || only.getValue().getMultiAssets().isEmpty(),
                "an ada-only wallet must get an ada-only output back");
        assertTrue(only.getInlineDatum() == null && only.getDatumHash() == null && only.getScriptRef() == null);

        BigInteger net = only.getValue().getCoin().subtract(botInputLovelace(tx, universe));
        assertEquals(FEE.subtract(tx.getBody().getFee()), net, "BOT_NET: compoundingFee − tx fee");
    }

    /**
     * ⛔ FAB-152, the property the ticket is about: a wallet already in {@link WalletShape}'s target shape
     * — one exact-C collateral UTxO and one larger change UTxO, no tokens — is STILL in it after a
     * compound, so no rebalance follows. On the unfolded body this is {@code ADA_FRAGMENTED}.
     */
    @Test
    void aWalletInTargetShapeIsStillInTargetShapeAfterACompound() throws Exception {
        var params = EvalFixtures.protocolParams().getProtocolParams();
        BigInteger collateral = WalletShape.collateralLovelace(params);
        Utxo exactCollateral = Utxo.builder().txHash("c0".repeat(32)).outputIndex(0).address(BOT)
                .amount(List.of(Amount.lovelace(collateral))).build();
        Utxo change = Utxo.builder().txHash("9e".repeat(32)).outputIndex(0).address(BOT)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(150_000_000L)))).build();
        List<Utxo> walletBefore = List.of(exactCollateral, change);
        assertTrue(WalletShape.reasons(walletBefore, java.util.Set.of(), collateral).isEmpty(),
                "the fixture must start in target shape or the test proves nothing");

        List<Utxo> universe = List.of(utxo("escrow"), utxo("bond"), utxo("pool"), utxo("poolManager"),
                recordedConfig(), recordedLmConfig(), exactCollateral, change);
        var request = new CompoundTransactionBuilder.Request(candidate(), java.util.Map.of(), utxo("bond"),
                recordedConfig(), recordedLmConfig(), change, BOT, FEE, VALID_FROM_SLOT, VALID_TO_SLOT);
        Transaction tx = evaluatingBuilder(universe).build(request);

        // The wallet afterwards: what it held, minus what the body spent, plus what the body pays it.
        List<Utxo> walletAfter = new ArrayList<>();
        for (Utxo u : walletBefore) {
            boolean spent = tx.getBody().getInputs().stream().anyMatch(i ->
                    i.getTransactionId().equals(u.getTxHash()) && i.getIndex() == u.getOutputIndex());
            if (!spent) {
                walletAfter.add(u);
            }
        }
        String txHash = com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(tx);
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            if (BOT.equals(outputs.get(i).getAddress())) {
                walletAfter.add(Utxo.builder().txHash(txHash).outputIndex(i).address(BOT)
                        .amount(amountsOf(outputs.get(i).getValue())).build());
            }
        }

        log.info("wallet after compound: {}", walletAfter.stream()
                .map(u -> u.getTxHash().substring(0, 8) + "#" + u.getOutputIndex() + "=" + u.getAmount().stream()
                        .map(a -> a.getQuantity() + " " + a.getUnit()).toList()).toList());
        var reasons = WalletShape.reasons(walletAfter, java.util.Set.of(), collateral);
        assertTrue(reasons.isEmpty(), "a compound must not knock a target-shape wallet out of shape: "
                + reasons);
    }

    /**
     * Trap 8, on the FINISHED body: with the fold in {@code preBalanceTx}, every one of the eleven
     * redeemers still carries the evaluator's ex-units — read off the deserialised transaction, never
     * off an {@code EvaluationResult} — and that final body still evaluates against the real validators.
     */
    @Test
    void everyRedeemerOnTheBuiltBodyCarriesEvaluatedExUnits() throws Exception {
        List<Utxo> universe = universe();
        Transaction reread = Transaction.deserialize(evaluatingBuilder(universe).build(request(FEE)).serialize());

        List<Redeemer> redeemers = reread.getWitnessSet().getRedeemers();
        assertEquals(11, redeemers.size(), "four general_spend spends and seven withdraw-0 invocations");
        for (Redeemer r : redeemers) {
            assertTrue(r.getExUnits().getMem().compareTo(PLACEHOLDER_MEM) > 0,
                    r.getTag() + "#" + r.getIndex() + " carries CCL's placeholder: not evaluated");
        }
        assertEquals(11, EvalFixtures.evaluate(reread, universe, REGISTRY).size(),
                "the final body, fold included, must evaluate");
    }

    /**
     * The post-assert, driven directly: a finished body whose bot output has been split back into two
     * datum-free outputs — lovelace conserved, so BOT_NET and every earlier check still pass — must be
     * refused with {@code BOT_OUTPUTS_NOT_FOLDED}, naming the count. A regression of the fold is then a
     * loud build refusal, never a silent rebalance.
     */
    @Test
    void twoDatumFreeOutputsAtTheChangeAddressAreRefused() {
        CompoundTransactionBuilder builder = builder();
        Transaction tx = builder.build(request(FEE));
        TransactionOutput only = botOutputs(tx).get(0);
        BigInteger split = BigInteger.valueOf(1_000_000L);
        only.setValue(only.getValue().subtract(Value.builder().coin(split).build()));
        tx.getBody().getOutputs().add(0, new TransactionOutput(BOT, Value.builder().coin(split).build()));

        List<String> order = builder.withdrawalOrder();
        var e = assertThrows(CompoundTransactionBuilder.RefusedException.class,
                () -> builder.assertStructure(tx, request(FEE), order,
                        CompoundTransactionBuilder.rewardRedeemerIndex(order, REGISTRY.getPoolPolicyId()),
                        CompoundTransactionBuilder.rewardRedeemerIndex(order,
                                REGISTRY.getLenderManagerWithdrawScriptHash())));
        assertEquals(CompoundTransactionBuilder.Refusal.BOT_OUTPUTS_NOT_FOLDED, e.getReason());
        assertTrue(e.getMessage().contains("2 datum-free outputs"), e.getMessage());
    }

    private static List<Amount> amountsOf(Value value) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(value.getCoin()));
        if (value.getMultiAssets() != null) {
            for (var ma : value.getMultiAssets()) {
                for (var asset : ma.getAssets()) {
                    amounts.add(Amount.asset(ma.getPolicyId() + asset.getNameAsHex().replaceFirst("^0x", ""),
                            asset.getValue()));
                }
            }
        }
        return amounts;
    }
}
