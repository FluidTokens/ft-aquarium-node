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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * <b>Eight</b> redeemers fire since T-024 — the pool's four, and the PoolManager's four beside them. In
 * the machine's report they are:
 * <ul>
 *   <li><b>Mint index {@value #MINT_BURN}</b> — {@code pool.pool}'s {@code check_mint}, the pool-NFT
 *       burn; a pure burn reduces it to {@code isInputRefSpent};</li>
 *   <li><b>Mint index {@value #MINT_POOL_MANAGER_BURN}</b> — {@code pool_manager.poolManager}'s
 *       {@code check_mint}, the PoolManager-NFT burn. Unlike {@code pool.pool} it <em>does</em> police
 *       burns, and it also reaches back into {@code self.redeemers} to require that the pool's own
 *       withdraw is a {@code Cancel};</li>
 *   <li><b>Spend index {@value #SPEND_POOL}</b> — {@code general_spend} over the pool UTxO; requires a
 *       withdrawal at {@code pool.pool}. The PoolManager's own {@code general_spend} is the other
 *       Spend;</li>
 *   <li><b>Withdraw index {@value #WITHDRAW_CANCEL_ACTION}</b> — {@code pool_cancel_action}
 *       ({@code 4e4c5ed0…} is the lowest of the four reward accounts); its {@code and{A,B,C}} asserts
 *       the pool NFT is (A) held on the input, (B) burnt, and (C) the lender authorised —
 *       <b>no conjunct reads an output</b>;</li>
 *   <li><b>Withdraw index {@value #WITHDRAW_POOL_POLICY}</b> — {@code pool.pool}'s {@code withdraw}
 *       {@code Cancel} branch; requires a withdrawal at {@code pool_cancel_action};</li>
 *   <li><b>Withdraw index {@value #WITHDRAW_POOL_MANAGER}</b> — {@code pool_manager.poolManager}'s
 *       {@code withdraw}, routing {@code CancelPoolManager} to {@code pm_cancel_pool_manager};</li>
 *   <li><b>Withdraw index {@value #WITHDRAW_PM_CANCEL}</b> — {@code pm_cancel_pool_manager}: pool inputs
 *       and PoolManager inputs counted equal, the NFT held on both sides, burnt −1, and the
 *       <em>PoolManager owner</em> authorised.</li>
 * </ul>
 *
 * <h2>Two mutations changed refuser in T-024, and that is a finding, not an accident</h2>
 * <ul>
 *   <li><b>The missing lender signature</b> used to be refused by {@code pool_cancel_action} (Withdraw
 *       {@value #WITHDRAW_CANCEL_ACTION}) and is now refused by {@code pm_cancel_pool_manager} (Withdraw
 *       {@value #WITHDRAW_PM_CANCEL}). That is the direct consequence of {@code lenderAuth} delegating to
 *       the PoolManager withdraw script: the key no longer authorises the pool, it authorises the
 *       PoolManager. The test kept passing through this change while the reason underneath it moved,
 *       which is exactly how a suite starts lying — hence the rewritten prose rather than a bumped
 *       constant.</li>
 *   <li><b>Renaming the pool burn</b> used to be caught only by {@code pool_cancel_action}'s conjunct B.
 *       It is now caught earlier, at Mint {@value #MINT_POOL_MANAGER_BURN}, because
 *       {@code pool_manager.ak} compares the two burn lists with {@code Pairs} equality. The
 *       conjunct-B and conjunct-A cases below therefore rename the PoolManager burn <em>with</em> the
 *       pool burn, so the earlier check stays satisfied and cannot mask the one under test.</li>
 * </ul>
 *
 * <h2>The remaining burn/auth mutations collide on Withdraw {@value #WITHDRAW_CANCEL_ACTION} by design</h2>
 * {@code pool.pool}'s {@code check_mint} does <b>not</b> police burns ({@code mintedTokens} filters
 * {@code quantity > 0}), so conjuncts A and B of {@code pool_cancel_action} both report under Withdraw
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
    private static final String POOL_MANAGER_DATUM_HEX =
            PoolTxEncoder.poolManagerDatum(PoolFixtures.poolManagerDatum()).serializeToHex();

    private static final String FUNDER_TX = "ee".repeat(32);
    private static final String COLLATERAL_TX = "ef".repeat(32);

    /** The pool-NFT burn: {@code pool.pool} 65a0bc5e… sorts before {@code pool_manager} b2324fbd…. */
    private static final BigInteger MINT_BURN = BigInteger.ZERO;
    /** The PoolManager-NFT burn — {@code pool_manager.poolManager}'s own {@code check_mint}. */
    private static final BigInteger MINT_POOL_MANAGER_BURN = BigInteger.ONE;
    /**
     * The four withdrawals, in reward-account order:
     * {@code pool_cancel_action} 4e4c5ed0… &lt; {@code pool.pool} 65a0bc5e… &lt;
     * {@code pool_manager.poolManager} b2324fbd… &lt; {@code pm_cancel_pool_manager} e1945d09….
     */
    private static final BigInteger WITHDRAW_CANCEL_ACTION = BigInteger.ZERO;
    private static final BigInteger WITHDRAW_POOL_POLICY = BigInteger.ONE;
    private static final BigInteger WITHDRAW_POOL_MANAGER = BigInteger.TWO;
    private static final BigInteger WITHDRAW_PM_CANCEL = BigInteger.valueOf(3);
    /** The pool input's position among the canonically sorted inputs (confirmed by the accept test log). */
    private static final BigInteger SPEND_POOL = BigInteger.ZERO;

    // ======================================================================================
    // The positive case
    // ======================================================================================

    /**
     * The honest pool cancel evaluates against all eight deployed validator invocations. Logs each
     * redeemer's tag, index and real ex-units, the total, and the serialised size against the real
     * 16,384-byte ceiling.
     */
    @Test
    void thePoolCancelEvaluatesAgainstTheDeployedValidators() throws Exception {
        Transaction tx = honest();

        List<EvaluationResult> results = EvalFixtures.evaluate(tx, universe(), REGISTRY, extraScripts());
        assertEquals(8, results.size(),
                "eight scripts run: the two burns, the two spends, and the four withdraws");

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

    /**
     * The witness set carries no Plutus scripts — all six validators travel by reference input. Three of
     * the coordinates are published preview ones and three are synthesised; the evaluator cannot tell the
     * difference and the chain can, which is what
     * {@link #theSyntheticCoordinatesAreNotClaimedToBePublished} exists to keep visible.
     */
    @Test
    void everyValidatorTravelsByReferenceInput() {
        Transaction tx = honest();
        assertTrue(tx.getWitnessSet().getPlutusV3Scripts() == null
                        || tx.getWitnessSet().getPlutusV3Scripts().isEmpty(),
                "all six validators must be resolved from reference inputs, not the witness set");
        assertEquals(7, tx.getBody().getReferenceInputs().size(),
                "seven reference inputs: the config, the three published pool reference scripts and "
                        + "the three synthesised pool-manager ones");
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
        assertTrue(outputs.stream().noneMatch(
                        o -> PoolFixtures.poolManagerAddress().equals(o.getAddress())),
                "there must be no PoolManager continuation either — it is burnt, not re-created");
        assertTrue(outputs.stream().allMatch(o -> FUNDER.equals(o.getAddress())),
                "every output must go to the lender (funder) change address");

        BigInteger returned = outputs.stream()
                .map(o -> o.getValue().getCoin()).reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger fee = tx.getBody().getFee();
        assertTrue(fee.signum() > 0, "the balanced body must carry a positive fee");
        assertEquals(BigInteger.valueOf(
                        POOL_LOVELACE + PoolFixtures.POOL_MANAGER_LOVELACE + FUNDER_LOVELACE)
                        .subtract(fee), returned,
                "the lender must recover the pool lovelace, the PoolManager's own lovelace and the "
                        + "funder input, less the fee — the ~2 ADA the orphan lock would have stranded "
                        + "comes back too");
        assertTrue(returned.compareTo(BigInteger.valueOf(POOL_LOVELACE)) > 0,
                "the recovered lovelace must exceed the pool's own liquidity — it came back");
    }

    // ======================================================================================
    // Falsifiability — the pool_cancel_action conjuncts A, B, C
    // ======================================================================================

    /**
     * <b>Conjunct A (pool NFT held on input).</b> Every name in the transaction is renamed together —
     * both burns and both redeemer name fields — to a wrong name. Conjunct B
     * ({@code quantity_of(self.mint, poolPolicy, poolId) == -1}) is thus <em>satisfied</em> — the renamed
     * token is burnt −1 under that same wrong name — and so is {@code pool_manager}'s burn-list equality
     * at the lower Mint {@value #MINT_POOL_MANAGER_BURN}. So the first false conjunct is A: the pool input
     * holds the real name, not the wrong one, so
     * {@code quantity_of(input.value, poolPolicy, wrongName) == 1} is {@code 0 == 1}.
     */
    @Test
    void aPoolNftNotHeldOnTheInputIsRefused() {
        Transaction m = copy();
        String wrongName = "00" + "9d".repeat(28); // 29 bytes, index-prefixed, never the pool NFT
        // rename the burnt token (keep pool policy, keep −1)
        renameSoleBurn(m, wrongName);
        // rename the PoolManager burn to match, so pool_manager's check_mint — which compares the two
        // burn lists with Pairs equality and would otherwise refuse first, at a LOWER redeemer index —
        // still passes and cannot mask the conjunct under test
        renameSolePoolManagerBurn(m, wrongName);
        // rename the CancelData.poolId to match, so conjunct B passes and only A can fail
        setCancelActionPoolId(m, wrongName);
        // …and the pm_cancel_pool_manager name list, so that validator is consistent too
        setCancelPoolManagerNames(m, wrongName);
        assertMutated(m, "pool NFT not held on input (every name renamed together)");
        assertRefused(m, "Withdraw", WITHDRAW_CANCEL_ACTION,
                "pool_cancel_action conjunct A: the input does not hold the named pool NFT");
    }

    /**
     * <b>Conjunct B (pool NFT burnt), construction 1: the burns removed.</b> Both mint entries and both
     * Mint redeemers are dropped, so {@code quantity_of(self.mint, poolPolicy, poolId)} is
     * {@code 0 != -1}. Conjunct A still holds (the input holds the real NFT and {@code poolId} is
     * unchanged), so B is the first false conjunct. Neither {@code check_mint} is the refuser here — with
     * no Mint redeemer neither runs. {@code pm_cancel_pool_manager} would refuse too, on its own burn
     * conjunct, but it sits at the higher Withdraw {@value #WITHDRAW_PM_CANCEL}.
     */
    @Test
    void aPoolNftNotBurntIsRefused() {
        Transaction m = copy();
        removeAllMintsAndRedeemers(m);
        assertMutated(m, "nothing burnt (both mint entries + both Mint redeemers removed)");
        assertRefused(m, "Withdraw", WITHDRAW_CANCEL_ACTION,
                "pool_cancel_action conjunct B: the pool NFT is not burnt −1");
    }

    /**
     * <b>Conjunct B (pool NFT burnt), construction 2: burnt under the wrong name.</b> Both burns are
     * renamed (same policies, still −1) and both Mint redeemers are kept. {@code pool.pool}'s
     * {@code check_mint} passes, because a burn ({@code quantity < 0}) is filtered out of
     * {@code mintedTokens} and never policed; {@code pool_manager}'s passes because the two burn lists
     * were renamed <em>together</em> and its {@code Pairs} equality still holds. But
     * {@code pool_cancel_action}'s B reads {@code quantity_of(self.mint, poolPolicy, realPoolId)}, which is
     * now {@code 0 != -1}. Conjunct A holds (the input holds the real NFT and {@code CancelData.poolId} is
     * unchanged), so B is the sole false conjunct.
     * <p>
     * <b>Renaming the pool burn ALONE no longer reaches here</b> — {@code pool_manager}'s burn-list
     * equality catches it at Mint {@value #MINT_POOL_MANAGER_BURN}, a second and independent guard on the
     * burnt name that did not exist before T-024. That is why this mutation must rename both.
     */
    @Test
    void aPoolNftBurntUnderTheWrongNameIsRefused() {
        Transaction m = copy();
        String wrongName = "00" + "7c".repeat(28);
        renameSoleBurn(m, wrongName);
        // the PoolManager burn is renamed with it, purely so pool_manager's check_mint (a LOWER redeemer
        // index) stays satisfied and the refusal that arrives is the one under test
        renameSolePoolManagerBurn(m, wrongName);
        assertMutated(m, "pool NFT burnt under the wrong name");
        assertRefused(m, "Withdraw", WITHDRAW_CANCEL_ACTION,
                "pool_cancel_action conjunct B: the burnt name is not the pool NFT");
    }

    /**
     * <b>The owner authorisation.</b> The lender's key hash is dropped from {@code required_signers}.
     * <p>
     * <b>The refuser moved in T-024 and the reason moved with it.</b> It used to be
     * {@code pool_cancel_action}'s conjunct C, because the pool's {@code lenderAuth} named the key
     * directly. Now {@code lenderAuth} delegates to the PoolManager withdraw script — which <em>is</em>
     * present — so {@code pool_cancel_action} passes, and the key is read one level in, as
     * {@code PoolManagerDatum.poolOwnerAuth}, by {@code pm_cancel_pool_manager}'s
     * {@code authorize_action}. Everything at a lower redeemer index still passes, so the refusal at
     * Withdraw {@value #WITHDRAW_PM_CANCEL} is that check and nothing else.
     */
    @Test
    void anAbsentLenderSignerIsRefused() {
        Transaction m = copy();
        m.getBody().setRequiredSigners(new ArrayList<>());
        assertMutated(m, "lender required-signer absent");
        assertRefused(m, "Withdraw", WITHDRAW_PM_CANCEL,
                "pm_cancel_pool_manager: the PoolManager owner did not authorise the cancel");
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
                new PoolCancelTransactionBuilder.Mutation(true, false, false));
        assertMutated(m, "pool.pool withdrawal omitted");
        assertRefused(m, "Spend", SPEND_POOL,
                "general_spend finds no pool.pool withdrawal and refuses the pool spend");
    }

    /**
     * {@code pool.pool}'s withdraw {@code action} is flipped from {@code Cancel} to {@code Borrow} — every
     * withdrawal leg stays present (so no reference script dangles), but the action is now wrong in two
     * places at once.
     * <p>
     * <b>The refuser moved in T-024.</b> It used to be {@code pool.pool} itself (Withdraw
     * {@value #WITHDRAW_POOL_POLICY}), which routes {@code Borrow} to {@code poolBorrowActionScriptHash}
     * and finds no withdrawal there. That check still exists and still fails — but
     * {@code pool_manager.ak}'s {@code check_mint} gets there first, at Mint
     * {@value #MINT_POOL_MANAGER_BURN}: to allow a burn at all it resolves
     * {@code safe_list_at(self.redeemers, poolWithdrawRedeemerIndex)}, expects it to be
     * {@code Withdraw(Script(poolWithdrawScriptHash))}, decodes it as a {@code PoolWithdrawRedeemer} and
     * requires {@code pw.Cancel}. So the PoolManager burn is a second, independent guard that the pool is
     * genuinely being cancelled. Mint {@value #MINT_BURN} ({@code pool.pool}'s own burn) still passes, so
     * the refusal is the PoolManager's and not a rig fault.
     */
    @Test
    void aPoolPolicyWithdrawRoutedToBorrowIsRefused() {
        Transaction m = copy();
        Redeemer r = PoolCancelTransactionBuilder.rewardRedeemerAt(m, REGISTRY.getPoolPolicyId());
        List<PlutusData> fields = ((ConstrPlutusData) r.getData()).getData().getPlutusDataList();
        fields.set(1, ConstrPlutusData.builder()
                .alternative(PoolTxEncoder.ACTION_BORROW).data(ListPlutusData.of()).build());
        assertMutated(m, "pool.pool withdraw action Cancel -> Borrow");
        assertRefused(m, "Mint", MINT_POOL_MANAGER_BURN,
                "pool_manager's check_mint reads the pool withdraw redeemer and requires a Cancel");
    }

    // ======================================================================================
    // Falsifiability — the PoolManager burn (T-024), and the orphan lock
    // ======================================================================================

    /**
     * <b>The orphan proof.</b> The PoolManager legs are dropped entirely — no PoolManager input, no
     * PoolManager burn, neither PoolManager withdrawal — which is <em>exactly</em> the cancel this
     * builder emitted before T-024. If that shape were accepted, the pool would be gone and the
     * PoolManager would remain, and no transaction could ever spend it again:
     * {@code pm_cancel_pool_manager} requires {@code list.length(poolInputs) > 0} and a pool holding the
     * matching NFT, and after this cancel no such pool exists. That is ~2 ADA locked forever, per pool.
     * <p>
     * <b>It is not accepted.</b> The pool datum's {@code lenderAuth} is
     * {@code CardanoWithdrawScript(poolManagerPolicyId)}, so {@code pool_cancel_action}'s third conjunct
     * is {@code pairs.has_key(withdrawals, Script(poolManagerPolicyId))} and the missing
     * {@code pool_manager.poolManager} withdrawal makes it false. So the orphan cannot be created through
     * this path at all, and the {@code lenderAuth} delegation of task 2 is what closes the hole that task
     * 4 opened — the refusal is the evidence the contract asked for, in place of demonstrating an
     * unspendable UTxO.
     * <p>
     * Conjuncts A and B hold (the pool NFT is held on the input and still burnt −1), so C is the sole
     * false conjunct.
     */
    @Test
    void omittingThePoolManagerLegsIsRefused() {
        Transaction m = builder().buildWithMutation(request(),
                new PoolCancelTransactionBuilder.Mutation(false, true, false));
        assertMutated(m, "every PoolManager leg omitted (the pre-T-024 cancel)");
        assertRefused(m, "Withdraw", WITHDRAW_CANCEL_ACTION,
                "pool_cancel_action conjunct C: lenderAuth delegates to the PoolManager withdraw script "
                        + "and there is no such withdrawal, so the pool cannot be cancelled without its "
                        + "PoolManager");
    }

    /**
     * Every PoolManager leg is kept except the burn itself: the PoolManager is still spent, both
     * PoolManager withdrawals are still present, but the mint field carries no PoolManager entry. This
     * isolates {@code pm_cancel_pool_manager}'s <em>"The pool manager NFT must be burnt"</em> conjunct
     * ({@code quantity_of(self.mint, poolManagerPolicyId, name) == -1}).
     * <p>
     * Everything at a lower redeemer index still passes: {@code pool.pool}'s burn (Mint
     * {@value #MINT_BURN}) is untouched, both {@code general_spend}s find their withdrawals, and
     * {@code pool_cancel_action} (Withdraw {@value #WITHDRAW_CANCEL_ACTION}) sees the pool NFT held,
     * burnt and the PoolManager withdrawal present. So the refusal at Withdraw
     * {@value #WITHDRAW_PM_CANCEL} is that conjunct and nothing else.
     */
    @Test
    void aCancelThatDoesNotBurnThePoolManagerIsRefused() {
        Transaction m = builder().buildWithMutation(request(),
                new PoolCancelTransactionBuilder.Mutation(false, false, true));
        assertMutated(m, "the PoolManager burn omitted from the mint field");
        assertRefused(m, "Withdraw", WITHDRAW_PM_CANCEL,
                "pm_cancel_pool_manager: the PoolManager NFT is not burnt −1");
    }

    /**
     * <b>The index is arbitrated by the machine, not by our arithmetic.</b> Both copies of
     * {@code poolWithdrawRedeemerIndex} are moved off by one, and the deployed validators must refuse.
     * <p>
     * {@link PoolCancelTransactionBuilder#poolWithdrawRedeemerIndexIn} derives the index as
     * {@code (#Mint + #Spend) + rewardIndex} from a reading of how {@code self.redeemers} is ordered.
     * A reading is not a proof; this is. If the derivation were off, the honest cancel would not
     * evaluate — and if the validators did not police the value, this mutant would pass. It reports at
     * Mint {@value #MINT_POOL_MANAGER_BURN}, the lower of the two consumers: {@code check_mint} finds a
     * redeemer at index+1 that is not {@code Withdraw(Script(poolPolicyId))} and its {@code expect}
     * aborts.
     */
    @Test
    void anOffByOnePoolWithdrawRedeemerIndexIsRefused() {
        Transaction m = copy();
        long honest = poolManagerMintPoolWithdrawIndex(m);
        setPoolManagerMintPoolWithdrawIndex(m, honest + 1);
        setCancelPoolManagerPoolWithdrawIndex(m, honest + 1);
        assertMutated(m, "poolWithdrawRedeemerIndex off by one in both redeemers");
        assertRefused(m, "Mint", MINT_POOL_MANAGER_BURN,
                "pool_manager's check_mint resolves poolWithdrawRedeemerIndex to something that is not "
                        + "the pool.pool withdraw and aborts");
    }

    /**
     * The three pool-manager coordinates this rig uses are <b>synthetic</b>, and the test suite says so
     * out loud rather than letting a green cancel imply a submittable one.
     * <p>
     * If FluidTokens publishes them and someone moves the coordinates into
     * {@code PUBLISHED_REFERENCE_SCRIPTS}, this test goes red — which is the intended signal: at that
     * point {@code LoanFactory}'s publication gate falls silent, the factory may create real
     * PoolManager-bearing pools, and this test should be deleted along with
     * {@code SYNTHETIC_POOL_MANAGER_REFERENCE_SCRIPTS}.
     */
    @Test
    void theSyntheticCoordinatesAreNotClaimedToBePublished() {
        assertEquals(PoolFixtures.poolManagerCancelScriptHashes(),
                PoolFixtures.unpublishedPoolManagerScripts(),
                "all three pool-manager validators are still unpublished on preview; if that changed, "
                        + "move their coordinates into PUBLISHED_REFERENCE_SCRIPTS and delete this test");
        for (String hash : PoolFixtures.poolManagerCancelScriptHashes()) {
            assertFalse(PoolFixtures.PUBLISHED_REFERENCE_SCRIPTS.containsKey(hash),
                    hash + " must not be presented as a verified published coordinate");
            assertTrue(PoolFixtures.SYNTHETIC_POOL_MANAGER_REFERENCE_SCRIPTS.containsKey(hash),
                    hash + " needs a synthetic coordinate for the offline rig");
        }
    }

    // ======================================================================================
    // The builder's own structural checks — pinned by their bodies, not their names
    // ======================================================================================

    /**
     * {@link PoolCancelTransactionBuilder}'s structural checks are unreachable through {@code build()}:
     * the builder only ever hands them a body it just assembled correctly. An unreachable check is an
     * unproven one — neuter its body and every test stays green — so the checks below are called here
     * directly, with exactly one input doctored to break exactly one claim.
     *
     * <h2>Doctor the input the claim actually reads</h2>
     * Cases 1–4 doctor the <b>body</b>. Cases 5–6 doctor the <b>{@link
     * PoolCancelTransactionBuilder.Request}</b>, and they have to: those two claims read the request's
     * UTxOs and never look at the body at all, so no doctored body can reach them. Passing the honest
     * request with a doctored body left both of them surviving neutering with this suite green — a guard
     * whose name was tested and whose body was not.
     * <p>
     * The honest body must pass first, or a doctored input failing would prove nothing.
     * <p>
     * <b>This is not the whole of {@link PoolCancelTransactionBuilder#assertStructureOf}.</b> The claims
     * pinned here are the six below; the remaining structural checks are still unpinned and would survive
     * neutering.
     */
    @Test
    void theStructuralChecksRefuseEachClaimTheyMake() {
        assertDoesNotThrow(() -> builder().assertStructureOf(request(), copy()),
                "the honest cancel must pass its own structural checks");

        // 1. the PoolManager burn is missing from the mint field
        Transaction noPoolManagerBurn = copy();
        noPoolManagerBurn.getBody().getMint().removeIf(
                ma -> ma.getPolicyId().equalsIgnoreCase(REGISTRY.getPoolManagerPolicyId()));
        assertStructuralRefusal(noPoolManagerBurn, "must burn exactly one PoolManager NFT");

        // 2. the PoolManager mint redeemer's poolWithdrawRedeemerIndex is wrong
        Transaction wrongMintIndex = copy();
        setPoolManagerMintPoolWithdrawIndex(wrongMintIndex,
                poolManagerMintPoolWithdrawIndex(wrongMintIndex) + 1);
        assertStructuralRefusal(wrongMintIndex,
                "the PoolManager mint redeemer claims poolWithdrawRedeemerIndex");

        // 3. pm_cancel_pool_manager's independent copy of the same index is wrong. It is checked
        //    separately precisely because the two are derived separately: breaking either alone must be
        //    caught, or one could quietly disagree with the other.
        Transaction wrongCancelIndex = copy();
        setCancelPoolManagerPoolWithdrawIndex(wrongCancelIndex,
                poolManagerMintPoolWithdrawIndex(wrongCancelIndex) + 1);
        assertStructuralRefusal(wrongCancelIndex,
                "the pm_cancel_pool_manager redeemer claims poolWithdrawRedeemerIndex");

        // 4. the PoolManager is not spent. pm_cancel_pool_manager counts PoolManager inputs against pool
        //    inputs and indexes poolManagerNFTAssetNames by position in that list, so a body that burns
        //    the NFT without spending the UTxO is a shape the builder must never emit.
        Transaction poolManagerNotSpent = copy();
        poolManagerNotSpent.getBody().getInputs().remove(new TransactionInput(
                poolManagerUtxo().getTxHash(), poolManagerUtxo().getOutputIndex()));
        assertStructuralRefusal(poolManagerNotSpent,
                "input(s) at the PoolManager spend credential");

        // 5. the PoolManager input does not hold its NFT. The claim reads request.poolManagerUtxo(), so
        //    the REQUEST is what gets doctored and the body stays honest: a PoolManager UTxO carrying
        //    lovelace and nothing else. The universe the builder resolves inputs against is untouched,
        //    so the input still resolves to the PoolManager credential and case 4's count still passes —
        //    this claim is the only one broken.
        assertStructuralRefusal(requestWith(poolUtxo(), strippedOfAssets(poolManagerUtxo())), copy(),
                "the PoolManager input does not hold exactly one");

        // 6. the pool input does not hold its NFT — the same shape, one UTxO along. CancelData.poolId is
        //    still the honest pool asset name, so the check before it still passes.
        assertStructuralRefusal(requestWith(strippedOfAssets(poolUtxo()), poolManagerUtxo()), copy(),
                "the pool input does not hold exactly one");
    }

    /** The honest request with its two script UTxOs replaced. */
    private static PoolCancelTransactionBuilder.Request requestWith(Utxo pool, Utxo poolManager) {
        PoolCancelTransactionBuilder.Request honest = request();
        return new PoolCancelTransactionBuilder.Request(pool, poolManager, honest.funderUtxo(),
                honest.configUtxo(), honest.referenceScriptUtxos(), honest.funderAddress(),
                honest.lenderPaymentKeyHash(), honest.poolAssetNameHex());
    }

    /**
     * The same UTxO — same coordinate, same address, same datum — carrying only its lovelace. Everything
     * the structural checks read apart from the NFT is preserved, so a refusal can only be the NFT claim.
     */
    private static Utxo strippedOfAssets(Utxo utxo) {
        return LoanFixtures.utxo(utxo.getTxHash(), utxo.getOutputIndex(), utxo.getAddress(),
                utxo.getAmount().stream().filter(a -> "lovelace".equals(a.getUnit())).toList(),
                utxo.getInlineDatum());
    }

    private static void assertStructuralRefusal(Transaction doctored, String expectedFragment) {
        assertStructuralRefusal(request(), doctored, expectedFragment);
    }

    private static void assertStructuralRefusal(PoolCancelTransactionBuilder.Request request,
                                                Transaction doctored, String expectedFragment) {
        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> builder().assertStructureOf(request, doctored),
                "the structural checks must refuse: " + expectedFragment);
        assertTrue(refusal.getMessage().contains("POOL_CANCEL_STRUCTURE_ASSERTION_FAILED")
                        && refusal.getMessage().contains(expectedFragment),
                "expected a structural refusal mentioning [" + expectedFragment + "], got: "
                        + refusal.getMessage());
        log.info("structural refusal: {}", refusal.getMessage());
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
                poolUtxo(), poolManagerUtxo(), funderUtxo(), PoolFixtures.configUtxo(),
                referenceScriptUtxos(), FUNDER, lenderKeyHash(), PoolFixtures.poolAssetName());
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

    /**
     * The PoolManager input — the output the create transaction paid to the PoolManager address, found
     * by address and NFT rather than by a position this slice has not pinned.
     */
    private static Utxo poolManagerUtxo() {
        TransactionOutput output = POOL_CREATE.getBody().getOutputs().stream()
                .filter(o -> PoolFixtures.poolManagerAddress().equals(o.getAddress()))
                .findFirst().orElseThrow(() -> new AssertionError("the create minted no PoolManager"));
        return LoanFixtures.utxo(POOL_CREATE_TX, POOL_CREATE.getBody().getOutputs().indexOf(output),
                PoolFixtures.poolManagerAddress(),
                List.of(Amount.lovelace(BigInteger.valueOf(PoolFixtures.POOL_MANAGER_LOVELACE)),
                        Amount.asset(REGISTRY.getPoolManagerPolicyId()
                                + PoolFixtures.poolManagerAssetName(), BigInteger.ONE)),
                POOL_MANAGER_DATUM_HEX);
    }

    private static List<Utxo> universe() {
        List<Utxo> universe = new ArrayList<>();
        universe.add(poolUtxo());
        universe.add(poolManagerUtxo());
        universe.add(funderUtxo());
        universe.add(LoanFixtures.adaUtxo(COLLATERAL_TX, 1, FUNDER, 60_000_000L));
        universe.add(PoolFixtures.configUtxo());
        universe.addAll(referenceScriptUtxos());
        return universe;
    }

    /**
     * The six reference-script UTxOs. The first three carry <b>published</b> preview coordinates; the
     * last three carry <b>synthesised</b> ones, because FluidTokens publishes no reference script for
     * the pool-manager family — see {@link PoolFixtures#SYNTHETIC_POOL_MANAGER_REFERENCE_SCRIPTS}. The
     * distinction is invisible to the evaluator (a reference input contributes only its coordinate, and
     * the bytes come from {@link #extraScripts()}) and decisive on chain, which is why
     * {@link #theSyntheticCoordinatesAreNotClaimedToBePublished} states it as an assertion rather than
     * leaving it to prose.
     */
    private static List<Utxo> referenceScriptUtxos() {
        List<Utxo> utxos = new ArrayList<>();
        List<String> hashes = new ArrayList<>(List.of(REGISTRY.getPoolSpendScriptHash(),
                REGISTRY.getPoolPolicyId(), REGISTRY.getPoolCancelActionScriptHash()));
        hashes.addAll(PoolFixtures.poolManagerCancelScriptHashes());
        for (String hash : hashes) {
            String coord = PoolFixtures.PUBLISHED_REFERENCE_SCRIPTS.containsKey(hash)
                    ? PoolFixtures.PUBLISHED_REFERENCE_SCRIPTS.get(hash)
                    : PoolFixtures.SYNTHETIC_POOL_MANAGER_REFERENCE_SCRIPTS.get(hash);
            if (coord == null) {
                throw new AssertionError("no coordinate, published or synthetic, for " + hash);
            }
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
                REGISTRY.getPoolCancelActionScript(), REGISTRY.getPoolManagerSpendScript(),
                REGISTRY.getPoolManagerScript(), REGISTRY.getPmCancelPoolManagerScript());
    }

    private static Transaction poolCreate() {
        PoolCreateTransactionBuilder poolBuilder = new PoolCreateTransactionBuilder(REGISTRY,
                LoanFixtures.utxoSupplier(PoolFixtures.universe(FUNDER)), LoanFixtures.protocolParams());
        PoolCreateTransactionBuilder.Request request = new PoolCreateTransactionBuilder.Request(
                PoolFixtures.seedUtxo(FUNDER), PoolFixtures.configUtxo(),
                PoolFixtures.poolPolicyRefScriptUtxo(), FUNDER, PoolFixtures.poolAddress(),
                PoolFixtures.poolAssetName(),
                PoolTxEncoder.poolDatum(PoolFixtures.factoryPoolDatum(PoolFixtures.defaults())),
                PoolFixtures.defaults().poolLiquidityLovelace(),
                PoolFixtures.poolManagerAddress(),
                PoolTxEncoder.poolManagerDatum(PoolFixtures.poolManagerDatum()),
                PoolFixtures.POOL_MANAGER_LOVELACE);
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

    /**
     * Drop <b>both</b> mint entries and both Mint redeemers, so nothing is burnt at all.
     * <p>
     * Removing only the pool's would leave the PoolManager mint entry with no redeemer and the evaluator
     * would answer {@code RequiredRedeemersMismatch} — a rig failure, not a validator refusal, and one
     * that would still satisfy a bare "it was refused". Removing both keeps every redeemer accounted for,
     * so the refusal that arrives is a real one. {@code pool_cancel_action}'s conjunct B is the
     * lowest-indexed check that notices, which is why this still reports at Withdraw
     * {@value #WITHDRAW_CANCEL_ACTION}.
     */
    private static void removeAllMintsAndRedeemers(Transaction m) {
        m.getBody().setMint(null);
        m.getWitnessSet().getRedeemers().removeIf(r -> r.getTag() == RedeemerTag.Mint);
    }

    /** The {@code poolWithdrawRedeemerIndex} the PoolManager mint redeemer currently carries. */
    private static long poolManagerMintPoolWithdrawIndex(Transaction m) {
        return ((com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData)
                poolManagerMintFields(m).get(1)).getValue().longValueExact();
    }

    private static void setPoolManagerMintPoolWithdrawIndex(Transaction m, long value) {
        poolManagerMintFields(m).set(1,
                com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(value));
    }

    /**
     * The PoolManager mint redeemer's fields, selected by <b>mint index</b>: both mint redeemers are
     * two-field constructor 0 records, so shape does not tell them apart.
     */
    private static List<PlutusData> poolManagerMintFields(Transaction m) {
        List<String> policies = m.getBody().getMint().stream()
                .map(MultiAsset::getPolicyId).map(String::toLowerCase).sorted().toList();
        int index = policies.indexOf(REGISTRY.getPoolManagerPolicyId().toLowerCase());
        Redeemer r = m.getWitnessSet().getRedeemers().stream()
                .filter(red -> red.getTag() == RedeemerTag.Mint
                        && red.getIndex().intValueExact() == index)
                .findFirst().orElseThrow(() -> new AssertionError("no PoolManager Mint redeemer"));
        return ((ConstrPlutusData) r.getData()).getData().getPlutusDataList();
    }

    private static void setCancelPoolManagerPoolWithdrawIndex(Transaction m, long value) {
        Redeemer r = m.getWitnessSet().getRedeemers().stream()
                .filter(red -> red.getTag() == RedeemerTag.Reward
                        && PoolCancelTransactionBuilder.isCancelPoolManagerRedeemer(red))
                .findFirst().orElseThrow(() -> new AssertionError("no pm_cancel_pool_manager redeemer"));
        ((ConstrPlutusData) r.getData()).getData().getPlutusDataList().set(1,
                com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(value));
    }

    /** Rewrite the sole burnt asset name under the PoolManager policy, preserving its quantity. */
    private static void renameSolePoolManagerBurn(Transaction m, String newNameHex) {
        MultiAsset poolManagerMint = m.getBody().getMint().stream()
                .filter(ma -> ma.getPolicyId().equalsIgnoreCase(REGISTRY.getPoolManagerPolicyId()))
                .findFirst().orElseThrow(() -> new AssertionError("no PoolManager-policy mint entry"));
        Asset asset = poolManagerMint.getAssets().get(0);
        poolManagerMint.getAssets().set(0, new Asset("0x" + newNameHex, asset.getValue()));
    }

    /** Overwrite the sole {@code poolManagerNFTAssetNames} entry in the pm_cancel_pool_manager redeemer. */
    private static void setCancelPoolManagerNames(Transaction m, String newNameHex) {
        Redeemer r = m.getWitnessSet().getRedeemers().stream()
                .filter(red -> red.getTag() == RedeemerTag.Reward
                        && PoolCancelTransactionBuilder.isCancelPoolManagerRedeemer(red))
                .findFirst().orElseThrow(() -> new AssertionError("no pm_cancel_pool_manager redeemer"));
        List<PlutusData> fields = ((ConstrPlutusData) r.getData()).getData().getPlutusDataList();
        ListPlutusData names = (ListPlutusData) fields.get(2);
        names.getPlutusDataList().set(0, BytesPlutusData.of(HexUtil.decodeHexString(newNameHex)));
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
