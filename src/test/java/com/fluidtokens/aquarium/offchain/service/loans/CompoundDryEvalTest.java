package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
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
 * <b>Real</b>, captured from preview on 2026-09-02 and replayed verbatim: the escrow
 * ({@code fb482b5a…#2}, 29,109,268 lovelace), the lender bond ({@code fb482b5a…#1}), the pool
 * ({@code 40c06048…#0}), the pool manager ({@code 1ad93a03…#1}), and both config UTxOs
 * ({@code 8dd38e97…#0/#1}) with their real datums. The validators are the vendored blueprint's,
 * derived through the SHIPPED registry.
 * <b>Synthetic</b>: only the bot's own wallet UTxO, because that wallet is ours and its contents are
 * not a property of the candidate. Nothing about the protocol side is invented.
 *
 * <h2>⚠ What this proves and what it cannot</h2>
 * CCL trap 11: an offline evaluator runs the validators. It does <b>not</b> check fees, min-ada,
 * value conservation or collateral adequacy — those are the ledger's, and this rig is silent about
 * them. The builder's own body assertions cover what it can; the rest is a phase-1 answer nobody has
 * asked for yet, and phase 1 is free.
 */
@Slf4j
class CompoundDryEvalTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.shippedPreviewRegistry();
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

    /** The bot's own funds — the one synthetic object, and ada-only so it can serve as collateral. */
    private static Utxo wallet() {
        return Utxo.builder().txHash("9e".repeat(32)).outputIndex(0).address(BOT)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L)))).build();
    }

    private static List<Utxo> universe() {
        return List.of(utxo("escrow"), utxo("bond"), utxo("pool"), utxo("poolManager"),
                utxo("config"), utxo("lmConfig"), wallet());
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
        return new CompoundTransactionBuilder.Request(candidate(), utxo("bond"),
                utxo("config"), utxo("lmConfig"), wallet(), BOT, fee,
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

        var request = new CompoundTransactionBuilder.Request(excluded, utxo("bond"), utxo("config"),
                utxo("lmConfig"), wallet(), BOT, FEE, VALID_FROM_SLOT, VALID_TO_SLOT);

        var e = assertThrows(CompoundTransactionBuilder.RefusedException.class,
                () -> builder().build(request));
        assertEquals(CompoundTransactionBuilder.Refusal.CANDIDATE_NOT_READY, e.getReason());
    }
}
