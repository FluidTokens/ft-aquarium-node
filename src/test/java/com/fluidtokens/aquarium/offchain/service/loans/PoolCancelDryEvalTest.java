package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
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
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pool-cancel transaction handed to the real PlutusV3 machine and run against the
 * <em>real deployed</em> {@code pool_cancel_action}, {@code pool.pool} (both its {@code withdraw} and
 * its {@code mint} handlers) and {@code general_spend} validators, chained onto S1's own
 * {@link PoolCreateTransactionBuilder} output so there is one pool. The applied compiled code comes from
 * {@link com.fluidtokens.aquarium.offchain.service.LoansContractRegistry}'s derivation over the committed
 * {@code loans-v4.plutus.json}; the config reference input carries the {@code ConfigDatum} recorded off
 * preview. Everything else is synthetic, so the test runs cold: no network, no key, no wallet.
 *
 * <h2>Why this gates funding</h2>
 * {@code pool_cancel_action} is the only recovery path for a pool's unlent ada. A green evaluation here
 * is the chain-level proof that a pool this factory creates can be cancelled — the escape hatch built
 * before any pool is funded (PLAN.md, 2026-08-17).
 *
 * <h2>What the source says the accept path executes (verified at {@code ff005fb})</h2>
 * Four redeemers fire. In the machine's report they are:
 * <ul>
 *   <li><b>Mint index {@value #MINT_BURN}</b> — {@code pool.pool}'s {@code check_mint}, the pool-NFT
 *       burn; a pure burn reduces it to {@code isInputRefSpent};</li>
 *   <li><b>Spend index {@value #SPEND_POOL}</b> — {@code general_spend} over the pool UTxO; requires a
 *       withdrawal at {@code pool.pool};</li>
 *   <li><b>Withdraw index {@value #WITHDRAW_CANCEL_ACTION}</b> — {@code pool_cancel_action}
 *       ({@code 4e4c5ed0…} sorts before {@code pool.pool}'s {@code 65a0bc5e…}, so it is the lower reward
 *       index); its {@code and{A,B,C}} asserts the pool NFT is (A) held on the input, (B) burnt, and
 *       (C) the lender authorised — <b>no conjunct reads an output</b>;</li>
 *   <li><b>Withdraw index {@value #WITHDRAW_POOL_POLICY}</b> — {@code pool.pool}'s {@code withdraw}
 *       {@code Cancel} branch; requires a withdrawal at {@code pool_cancel_action}.</li>
 * </ul>
 *
 * <h2>The three burn/auth mutations collide on Withdraw {@value #WITHDRAW_CANCEL_ACTION} by design</h2>
 * {@code pool.pool}'s {@code check_mint} does <b>not</b> police burns ({@code mintedTokens} filters
 * {@code quantity > 0}), so the <em>only</em> on-chain check that the correct pool NFT is burnt is
 * {@code pool_cancel_action}'s conjunct B. Its conjuncts A, B, C therefore all report under Withdraw
 * {@value #WITHDRAW_CANCEL_ACTION}. Per the {@link EvalFixtures} harness rules, the collision is resolved
 * <em>by construction</em>: each mutation satisfies every conjunct of the predicate except the one it
 * targets, so the short-circuiting {@code and} makes the named conjunct the sole false one — see each
 * test's javadoc for which conjunct it isolates and why the others hold.
 *
 * <h2>Falsifiability</h2>
 * Each adversarial case is produced by breaking a fresh copy of the honest build (byte-surgery) or by a
 * knob-turned {@link PoolCancelTransactionBuilder#buildWithMutation rebuild} for the two leg-omission
 * cases, so the honest transaction is never mutated, and each mutant is asserted to differ from it. Every
 * refusal names the exact {@code RedeemerError { tag, index }} (see {@link EvalFixtures}'s
 * <em>Harness limitations</em>: a failure proves the named redeemer refused and nothing about any redeemer
 * after it).
 */
@Slf4j
class PoolCancelDryEvalTest {

    private static final com.fluidtokens.aquarium.offchain.service.LoansContractRegistry REGISTRY =
            LoanFixtures.registry();
    private static final String FUNDER = LoanFixtures.botAddress();
    private static final PoolFixtures.PoolParameters PARAMS = PoolFixtures.defaults();

    private static final long POOL_LOVELACE = PARAMS.poolLiquidityLovelace();   // 52 ADA of unlent liquidity
    private static final long FUNDER_LOVELACE = 100_000_000L;

    /** S1's own pool-create transaction; its output 0 becomes the cancel's pool input. */
    private static final Transaction POOL_CREATE = poolCreate();
    private static final String POOL_CREATE_TX = TransactionUtil.getTxHash(POOL_CREATE);
    private static final String POOL_DATUM_HEX =
            PoolTxEncoder.poolDatum(PoolFixtures.factoryPoolDatum(PARAMS)).serializeToHex();

    private static final String FUNDER_TX = "ee".repeat(32);
    private static final String COLLATERAL_TX = "ef".repeat(32);

    /** The single Mint redeemer (the pool-NFT burn) is index 0. */
    private static final BigInteger MINT_BURN = BigInteger.ZERO;
    /** {@code pool_cancel_action} 4e4c5ed0… sorts before {@code pool.pool} 65a0bc5e… among the rewards. */
    private static final BigInteger WITHDRAW_CANCEL_ACTION = BigInteger.ZERO;
    private static final BigInteger WITHDRAW_POOL_POLICY = BigInteger.ONE;
    /** The pool input's position among the canonically sorted inputs (confirmed by the accept test log). */
    private static final BigInteger SPEND_POOL = BigInteger.ZERO;

    // ======================================================================================
    // The positive case
    // ======================================================================================

    /**
     * The honest pool cancel evaluates against all four deployed validator invocations. Logs each
     * redeemer's tag, index and real ex-units, the total, and the serialised size against the real
     * 16,384-byte ceiling.
     */
    @Test
    void thePoolCancelEvaluatesAgainstTheDeployedValidators() throws Exception {
        Transaction tx = honest();

        List<EvaluationResult> results = EvalFixtures.evaluate(tx, universe(), REGISTRY, extraScripts());
        assertEquals(4, results.size(),
                "four scripts run: the pool-NFT burn, the pool spend, and the two withdraws");

        long mem = 0;
        long steps = 0;
        for (EvaluationResult r : results) {
            log.info("Pool cancel ex-units: tag={} index={} mem={} steps={}",
                    r.getRedeemerTag(), r.getIndex(), r.getExUnits().getMem(), r.getExUnits().getSteps());
            mem += r.getExUnits().getMem().longValue();
            steps += r.getExUnits().getSteps().longValue();
        }
        int size = tx.serialize().length;
        log.info("Pool cancel TOTAL ex-units: mem={} steps={}; serialised size {} of 16384 bytes",
                mem, steps, size);
        assertTrue(size <= 16_384, "the reference-script transaction must fit the real max tx size");
    }

    /** The witness set carries no Plutus scripts — every validator travels by reference input. */
    @Test
    void everyValidatorTravelsByReferenceInput() {
        Transaction tx = honest();
        assertTrue(tx.getWitnessSet().getPlutusV3Scripts() == null
                        || tx.getWitnessSet().getPlutusV3Scripts().isEmpty(),
                "the three validators must be resolved from reference inputs, not the witness set");
        assertEquals(4, tx.getBody().getReferenceInputs().size(),
                "four reference inputs: the config and the three published reference scripts");
    }

    /**
     * The recovered pool lovelace comes back to the lender. {@code pool_cancel_action} constrains no
     * output, so this is a property of the builder's {@code withChangeAddress}, not of any script — it is
     * asserted on the finished body here rather than trusted. No output sits at the pool address (there is
     * no continuation), and every output is at the funder/lender, carrying the pool input's lovelace minus
     * the fee.
     */
    @Test
    void theRecoveredLovelaceReturnsToTheLender() {
        Transaction tx = honest();
        List<TransactionOutput> outputs = tx.getBody().getOutputs();

        assertTrue(outputs.stream().noneMatch(o -> PoolFixtures.poolAddress().equals(o.getAddress())),
                "there must be no pool continuation — the pool is cancelled, not re-created");
        assertTrue(outputs.stream().allMatch(o -> FUNDER.equals(o.getAddress())),
                "every output must go to the lender (funder) change address");

        BigInteger returned = outputs.stream()
                .map(o -> o.getValue().getCoin()).reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger fee = tx.getBody().getFee();
        assertTrue(fee.signum() > 0, "the balanced body must carry a positive fee");
        assertEquals(BigInteger.valueOf(POOL_LOVELACE + FUNDER_LOVELACE).subtract(fee), returned,
                "the lender must recover the pool lovelace plus the funder input, less the fee");
        assertTrue(returned.compareTo(BigInteger.valueOf(POOL_LOVELACE)) > 0,
                "the recovered lovelace must exceed the pool's own liquidity — it came back");
    }

    // ======================================================================================
    // Falsifiability — the pool_cancel_action conjuncts A, B, C
    // ======================================================================================

    /**
     * <b>Conjunct A (pool NFT held on input).</b> The {@code CancelData.poolId} and the burnt token name
     * are renamed together to a wrong name. Conjunct B ({@code quantity_of(self.mint, poolPolicy, poolId)
     * == -1}) is thus <em>satisfied</em> — the renamed token is burnt −1 under that same wrong name — so
     * the first false conjunct is A: the pool input holds the real name, not the wrong one, so
     * {@code quantity_of(input.value, poolPolicy, wrongName) == 1} is {@code 0 == 1}.
     */
    @Test
    void aPoolNftNotHeldOnTheInputIsRefused() {
        Transaction m = copy();
        String wrongName = "00" + "9d".repeat(28); // 29 bytes, index-prefixed, never the pool NFT
        // rename the burnt token (keep pool policy, keep −1)
        renameSoleBurn(m, wrongName);
        // rename the CancelData.poolId to match, so conjunct B passes and only A can fail
        setCancelActionPoolId(m, wrongName);
        assertMutated(m, "pool NFT not held on input (poolId + burn renamed)");
        assertRefused(m, "Withdraw", WITHDRAW_CANCEL_ACTION,
                "pool_cancel_action conjunct A: the input does not hold the named pool NFT");
    }

    /**
     * <b>Conjunct B (pool NFT burnt), construction 1: the burn removed.</b> The pool NFT is dropped from
     * the mint field and its Mint redeemer removed, so {@code quantity_of(self.mint, poolPolicy, poolId)}
     * is {@code 0 != -1}. Conjunct A still holds (the input holds the real NFT and {@code poolId} is
     * unchanged), so B is the first false conjunct. {@code pool.pool}'s own {@code check_mint} is not the
     * refuser here — with no Mint redeemer it does not run.
     */
    @Test
    void aPoolNftNotBurntIsRefused() {
        Transaction m = copy();
        removePoolMintAndRedeemer(m);
        assertMutated(m, "pool NFT not burnt (mint + Mint redeemer removed)");
        assertRefused(m, "Withdraw", WITHDRAW_CANCEL_ACTION,
                "pool_cancel_action conjunct B: the pool NFT is not burnt −1");
    }

    /**
     * <b>Conjunct B (pool NFT burnt), construction 2: burnt under the wrong name.</b> The burn is renamed
     * (same pool policy, still −1), and the Mint redeemer is kept — {@code check_mint} passes it, because
     * a burn ({@code quantity < 0}) is filtered out of {@code mintedTokens} and never policed. But
     * {@code pool_cancel_action}'s B reads {@code quantity_of(self.mint, poolPolicy, realPoolId)}, which is
     * now {@code 0 != -1}. Conjunct A holds (the input holds the real NFT and {@code CancelData.poolId} is
     * unchanged), so B is the sole false conjunct — proving that renaming the burn, which {@code pool.pool}
     * itself waves through, is caught only here.
     */
    @Test
    void aPoolNftBurntUnderTheWrongNameIsRefused() {
        Transaction m = copy();
        renameSoleBurn(m, "00" + "7c".repeat(28));
        assertMutated(m, "pool NFT burnt under the wrong name");
        assertRefused(m, "Withdraw", WITHDRAW_CANCEL_ACTION,
                "pool_cancel_action conjunct B: the burnt name is not the pool NFT");
    }

    /**
     * <b>Conjunct C (lender authorised).</b> The lender's key hash is dropped from {@code required_signers}.
     * Conjuncts A and B hold (the NFT is held on the input and burnt −1), so C —
     * {@code authorize_action}'s {@code list.has(extra_signatories, lenderHash)} — is the sole false
     * conjunct.
     */
    @Test
    void anAbsentLenderSignerIsRefused() {
        Transaction m = copy();
        m.getBody().setRequiredSigners(new ArrayList<>());
        assertMutated(m, "lender required-signer absent");
        assertRefused(m, "Withdraw", WITHDRAW_CANCEL_ACTION,
                "pool_cancel_action conjunct C: the lender did not authorise the cancel");
    }

    // ======================================================================================
    // Falsifiability — the indirection legs (distinct refusers)
    // ======================================================================================

    /**
     * The {@code pool.pool(Cancel)} withdrawal is omitted, so {@code general_spend}'s else-branch finds no
     * withdrawal at its {@code withdrawScriptHash} ({@code pool.pool}) and returns False — the pool UTxO
     * cannot be spent. Refuser: Spend {@value #SPEND_POOL}, a distinct validator and tag from the
     * conjunct cases.
     */
    @Test
    void omittingThePoolPolicyWithdrawalIsRefused() {
        Transaction m = builder().buildWithMutation(request(),
                new PoolCancelTransactionBuilder.Mutation(true));
        assertMutated(m, "pool.pool withdrawal omitted");
        assertRefused(m, "Spend", SPEND_POOL,
                "general_spend finds no pool.pool withdrawal and refuses the pool spend");
    }

    /**
     * {@code pool.pool}'s withdraw {@code action} is flipped from {@code Cancel} to {@code Borrow} — both
     * withdrawal legs stay present (so no reference script dangles), but {@code pool.pool} now routes to
     * {@code poolBorrowActionScriptHash} (config index 23) and requires a withdrawal there. The transaction
     * has none, so {@code pool.pool}'s {@code list.any(withdrawals, .. == actionWithdrawScriptHash)} returns
     * False. This is the gate that makes {@code pool.pool} demand the {@code pool_cancel_action} withdrawal
     * for a Cancel. Refuser: Withdraw {@value #WITHDRAW_POOL_POLICY} — {@code pool.pool}, at a distinct
     * index from {@code pool_cancel_action}. {@code pool_cancel_action} (Withdraw
     * {@value #WITHDRAW_CANCEL_ACTION}) itself still passes, so the lower-indexed redeemer does not mask
     * this refusal.
     */
    @Test
    void aPoolPolicyWithdrawRoutedToBorrowIsRefused() {
        Transaction m = copy();
        Redeemer r = m.getWitnessSet().getRedeemers().stream()
                .filter(red -> red.getTag() == RedeemerTag.Reward
                        && PoolCancelTransactionBuilder.isPoolPolicyWithdrawRedeemer(red))
                .findFirst().orElseThrow(() -> new AssertionError("no pool.pool withdraw redeemer"));
        List<PlutusData> fields = ((ConstrPlutusData) r.getData()).getData().getPlutusDataList();
        fields.set(1, ConstrPlutusData.builder()
                .alternative(PoolTxEncoder.ACTION_BORROW).data(ListPlutusData.of()).build());
        assertMutated(m, "pool.pool withdraw action Cancel -> Borrow");
        assertRefused(m, "Withdraw", WITHDRAW_POOL_POLICY,
                "pool.pool routed to Borrow finds no pool_borrow_action withdrawal and refuses");
    }

    // ======================================================================================
    // plumbing
    // ======================================================================================

    private static Transaction HONEST;

    private static Transaction honest() {
        if (HONEST == null) {
            HONEST = builder().build(request());
        }
        return HONEST;
    }

    private static Transaction copy() {
        try {
            return Transaction.deserialize(honest().serialize());
        } catch (Exception e) {
            throw new AssertionError("cannot copy the honest transaction", e);
        }
    }

    private static PoolCancelTransactionBuilder builder() {
        return new PoolCancelTransactionBuilder(REGISTRY, LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(universe()), LoanFixtures.protocolParams());
    }

    private static PoolCancelTransactionBuilder.Request request() {
        return new PoolCancelTransactionBuilder.Request(
                poolUtxo(), funderUtxo(), PoolFixtures.configUtxo(), referenceScriptUtxos(),
                FUNDER, lenderKeyHash(), PoolFixtures.poolAssetName());
    }

    private static byte[] lenderKeyHash() {
        return new Address(FUNDER).getPaymentCredentialHash().orElseThrow();
    }

    private static Utxo poolUtxo() {
        return LoanFixtures.utxo(POOL_CREATE_TX, 0, PoolFixtures.poolAddress(),
                List.of(Amount.lovelace(BigInteger.valueOf(POOL_LOVELACE)),
                        Amount.asset(REGISTRY.getPoolPolicyId() + PoolFixtures.poolAssetName(), BigInteger.ONE)),
                POOL_DATUM_HEX);
    }

    private static Utxo funderUtxo() {
        return LoanFixtures.adaUtxo(FUNDER_TX, 0, FUNDER, FUNDER_LOVELACE);
    }

    private static List<Utxo> universe() {
        List<Utxo> universe = new ArrayList<>();
        universe.add(poolUtxo());
        universe.add(funderUtxo());
        universe.add(LoanFixtures.adaUtxo(COLLATERAL_TX, 1, FUNDER, 60_000_000L));
        universe.add(PoolFixtures.configUtxo());
        universe.addAll(referenceScriptUtxos());
        return universe;
    }

    private static List<Utxo> referenceScriptUtxos() {
        List<Utxo> utxos = new ArrayList<>();
        for (String hash : List.of(REGISTRY.getPoolSpendScriptHash(), REGISTRY.getPoolPolicyId(),
                REGISTRY.getPoolCancelActionScriptHash())) {
            String coord = PoolFixtures.PUBLISHED_REFERENCE_SCRIPTS.get(hash);
            String txHash = coord.substring(0, coord.indexOf('#'));
            int index = Integer.parseInt(coord.substring(coord.indexOf('#') + 1));
            utxos.add(Utxo.builder()
                    .txHash(txHash).outputIndex(index)
                    .address(LoanFixtures.entAddress(hash))
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                    .referenceScriptHash(hash)
                    .build());
        }
        return utxos;
    }

    private static List<PlutusScript> extraScripts() {
        return List.of(REGISTRY.getPoolSpendScript(), REGISTRY.getPoolScript(),
                REGISTRY.getPoolCancelActionScript());
    }

    private static Transaction poolCreate() {
        PoolCreateTransactionBuilder poolBuilder = new PoolCreateTransactionBuilder(REGISTRY,
                LoanFixtures.utxoSupplier(PoolFixtures.universe(FUNDER)), LoanFixtures.protocolParams());
        PoolCreateTransactionBuilder.Request request = new PoolCreateTransactionBuilder.Request(
                PoolFixtures.seedUtxo(FUNDER), PoolFixtures.configUtxo(),
                PoolFixtures.poolPolicyRefScriptUtxo(), FUNDER, PoolFixtures.poolAddress(),
                PoolFixtures.poolAssetName(),
                PoolTxEncoder.poolDatum(PoolFixtures.factoryPoolDatum(PoolFixtures.defaults())),
                PoolFixtures.defaults().poolLiquidityLovelace());
        return poolBuilder.build(request);
    }

    // ---- assertions and surgery helpers ----------------------------------------------------------

    private static void assertRefused(Transaction mutated, String tag, BigInteger index, String what) {
        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(mutated, universe(), REGISTRY, extraScripts());
        log.info("MUTATION [{}] refusal: {}", what, outcome.detail().replace("\n", " | "));
        assertFalse(outcome.successful(), what + " must be refused");
        String expected = "tag: \"" + tag + "\", index: " + index;
        assertTrue(outcome.detail().contains(expected),
                "expected [" + expected + "] refusing " + what + ", got: " + outcome.detail());
    }

    private static void assertMutated(Transaction mutated, String what) {
        try {
            assertNotEquals(HexUtil.encodeHexString(honest().serialize()),
                    HexUtil.encodeHexString(mutated.serialize()),
                    what + " did not change the transaction bytes");
        } catch (Exception e) {
            throw new AssertionError("cannot serialise for the mutation check", e);
        }
    }

    /** Rewrite the sole minted/burnt asset name under the pool policy, preserving its (negative) quantity. */
    private static void renameSoleBurn(Transaction m, String newNameHex) {
        MultiAsset poolMint = m.getBody().getMint().stream()
                .filter(ma -> ma.getPolicyId().equalsIgnoreCase(REGISTRY.getPoolPolicyId()))
                .findFirst().orElseThrow(() -> new AssertionError("no pool-policy mint entry"));
        Asset asset = poolMint.getAssets().get(0);
        poolMint.getAssets().set(0, new Asset("0x" + newNameHex, asset.getValue()));
    }

    /** Drop the pool-policy mint entry and its Mint redeemer, so nothing burns the pool NFT. */
    private static void removePoolMintAndRedeemer(Transaction m) {
        m.getBody().getMint().removeIf(ma -> ma.getPolicyId().equalsIgnoreCase(REGISTRY.getPoolPolicyId()));
        if (m.getBody().getMint().isEmpty()) {
            m.getBody().setMint(null);
        }
        m.getWitnessSet().getRedeemers().removeIf(r -> r.getTag() == RedeemerTag.Mint);
    }

    /** Overwrite the {@code CancelData.poolId} in the pool_cancel_action withdraw redeemer. */
    private static void setCancelActionPoolId(Transaction m, String newPoolIdHex) {
        Redeemer r = m.getWitnessSet().getRedeemers().stream()
                .filter(red -> red.getTag() == RedeemerTag.Reward
                        && PoolCancelTransactionBuilder.isCancelActionRedeemer(red))
                .findFirst().orElseThrow(() -> new AssertionError("no pool_cancel_action redeemer"));
        List<PlutusData> fields = ((ConstrPlutusData) r.getData()).getData().getPlutusDataList();
        ListPlutusData actions = (ListPlutusData) fields.get(1);
        ConstrPlutusData cancelData = (ConstrPlutusData) actions.getPlutusDataList().get(0);
        cancelData.getData().getPlutusDataList().set(0,
                BytesPlutusData.of(HexUtil.decodeHexString(newPoolIdHex)));
    }
}
