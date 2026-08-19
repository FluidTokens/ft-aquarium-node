package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.AuthorizationMethod;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LoanFactory} exercised end to end against the <em>real deployed</em> validators through
 * {@link EvalFixtures}: a create → borrow → recovery-cancel pipeline over the factory's own preview
 * pool, entirely synthetic, so the test runs cold — no network, no key, no wallet. The lender and the
 * borrower are one wallet ({@link LoanFixtures#botAddress()}).
 *
 * <h2>What each case proves</h2>
 * <ul>
 *   <li><b>Positive</b> — create, borrow and recovery-cancel all build and dry-evaluate green; the
 *       emitted lender bond decodes to fee 100 off the finished borrow body, and the recovery returns
 *       every lovelace to the lender.</li>
 *   <li><b>F-a</b> — a fee-0 pool makes {@link LoanFactory#buildBorrow} throw the fee gate; a green
 *       suite with a fee-0 pool is therefore impossible.</li>
 *   <li><b>F-b</b> — a healthy collateral price makes the born-liquidatable gate fire.</li>
 *   <li><b>F-c</b> — a recovery cancel whose change address is not the lender is refused by the tool's
 *       own destination gate.</li>
 *   <li><b>F-e1 / F-e2</b> — an evaluator that reports the placeholder budget, and one that merely
 *       halves the real one, are both refused by the ex-units gate — the fifth gate, and the two arms it
 *       is made of. Alongside them, the no-evaluator create build is pinned byte-for-byte and the
 *       real-evaluator one is shown to carry measured budgets and a higher fee.</li>
 * </ul>
 * Each falsifiability case asserts on the <em>named</em> {@link LoanFactory.GateFailure} message, not on
 * an incidental exception, so it proves the specific gate fired.
 */
@Slf4j
class LoanFactoryDryEvalTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();
    private static final String FUNDER = LoanFixtures.botAddress();
    private static final Address LENDER = new Address(FUNDER);

    private static final long LOAN_OUTPUT_LOVELACE = 2_500_000L;
    private static final long BOND_LOVELACE = 1_500_000L;
    private static final long VALID_FROM_SLOT = 70_000_000L;
    private static final long VALID_TO_SLOT = 70_000_100L;   // a 100-second window

    private static final String FUNDER_TX = "ee".repeat(32);
    private static final String COLLATERAL_TX = "ef".repeat(32);

    /**
     * The create transaction the no-evaluator {@link LoanFactory} builds, pinned by fee and by the
     * sha256 of its serialised bytes, so the null path is checked against drift rather than merely
     * restated.
     *
     * <h2>Why these numbers moved once, and what the old ones were</h2>
     * They were first measured on the tree as it stood before the optional evaluator existed (baseline
     * {@code 8d4a9b3}): <b>fee 269,523</b>, sha256
     * <b>{@code e88f966e8be1df4b1ab2ba3387d1216ee427ebb14c59f7091914e2e3e832aec5}</b>. That shape carried
     * {@code pool.pool} in the witness set <em>and</em> on a reference input and paid no reference-script
     * fee — the transaction preview refused with {@code ExtraneousScriptWitnessesUTXOW} and
     * {@code FeeTooSmallUTxO}. Pinning it made a known-broken shape the thing this test defended, so
     * {@link PoolCreateTransactionBuilder}'s reference-script wiring was made unconditional and the pin
     * re-taken over the corrected shape. The whole difference is the Conway reference-script charge:
     * {@code 293,853 − 269,523 = 24,330 = 1,622 bytes × 15}, the same arithmetic
     * {@link #withAnEvaluatorTheCreateStripsTheWitnessCopyAndPaysTheReferenceScriptFee} checks. The pin
     * keeps its drift-detection job; it now pins a submittable shape.
     *
     * <h2>And a second time, in T-024, for a reason of substance</h2>
     * The immediately preceding numbers were <b>fee 293,853</b>, sha256
     * <b>{@code 46721cef0b9ed793cb4f5c6d2fde3994e0b18890016d63315f5dae1d0c469988}</b>. They pinned a create that minted a pool NFT and
     * <em>no PoolManager NFT</em> — the shape FluidTokens reviewed and rejected, and the shape that
     * produces a pool nobody can ever compound. The transaction now carries a second mint (the
     * PoolManager policy, in the witness set), a second redeemer, a second output with its own
     * {@link PoolFixtures#POOL_MANAGER_LOVELACE} and datum, and a {@code lenderAuth} that delegates to
     * the PoolManager withdraw script instead of naming a key. Every one of those is a deliberate change
     * to what this transaction <em>is</em>, so the bytes had to move; the pin is re-taken over the new
     * shape and goes on doing its job. <b>A pin is re-taken when the artefact was meant to change and
     * never to make a red test green.</b>
     *
     * <h2>And a third time, in T-026 — the sha alone, and the fee deliberately not</h2>
     * The immediately preceding sha256 was
     * <b>{@code c6176f030651cf63fa5436ac02e00d0521a8e711d6027dc5906113b014b2e921}</b>. It pinned a create
     * whose {@code lenderBondInlineDatumHash} committed to a lender bond carrying
     * {@link LoanFixtures#PLACEHOLDER_AUTH_HASH} — a hand-written test constant with no private key
     * behind it — as the bond's {@code lenderAuth}. A pool created from that commitment can never
     * release its bond, because {@code lm_withdraw_bonds_action} demands exactly that authorisation and
     * {@code PoolDatum.lenderBondInlineDatumHash} freezes the choice at creation. The bond now names the
     * recipe lender's own payment key hash, so the 32-byte hash the pool commits to has a different
     * value and the transaction has different bytes. Same reason as the two re-takes above: <b>the
     * artefact was meant to change.</b>
     * <p>
     * <b>{@link #NO_EVALUATOR_CREATE_FEE} did not move, and that is not an oversight.</b> A payment key
     * hash and the ramp constant are both 28 bytes, and the pool datum carries only the bond datum's
     * 32-byte blake2b hash in either case — so the create transaction's <em>size</em> is identical and
     * its fee is unchanged at 411,119. The pin that had to be re-taken is the sha256; leaving the fee
     * alone is what keeps it a measurement.
     */
    private static final long NO_EVALUATOR_CREATE_FEE = 411_119L;
    private static final String NO_EVALUATOR_CREATE_SHA256 =
            "0ce6e01e042bd76499fe6ec13b66af260d5efefb5a51b6d06e2e85118ef8e2f2";

    /**
     * {@code minFeeRefScriptCostPerByte}, as {@link EvalFixtures#protocolParams()} carries it and as
     * preview's live parameters report it (read from Blockfrost 2026-08-18, epoch 1393: 15).
     */
    private static final int MIN_FEE_REF_SCRIPT_COST_PER_BYTE = 15;

    /**
     * Conway's reference-script fee is tiered: the first 25 KiB are charged at
     * {@link #MIN_FEE_REF_SCRIPT_COST_PER_BYTE}, every further increment at 1.2x the previous tier. Our
     * validators are far below it, so a flat multiplication is the right expectation — but only while
     * that stays true, which the assertion using this constant checks rather than assumes.
     */
    private static final int CONWAY_REF_SCRIPT_TIER_BYTES = 25_600;

    // ======================================================================================
    // The positive case
    // ======================================================================================

    @Test
    void createBorrowAndRecoveryCancelAllEvaluateGreen() {
        PoolFixtures.PoolParameters params = PoolFixtures.defaults();
        LoanFactory factory = factory(baseUniverse());
        LoanFactory.Recipe recipe = recipe(params, 1, 2);   // born liquidatable at 0.5 lovelace/unit

        Transaction create = factory.buildCreateWithUnpublishedPoolManagerScripts(recipe);
        Transaction borrow = factory.buildBorrow(recipe, create);
        Transaction cancel = factory.buildRecoveryCancel(recipe, create);

        // The emitted lender bond decodes to fee 100 off the finished borrow body.
        String bondAssetName = PoolTxEncoder.bondAssetName(
                new TransactionInput(TransactionUtil.getTxHash(create), 0));
        TransactionOutput bondOutput = outputWithNft(borrow, LoanFixtures.bondAddress(),
                REGISTRY.getLenderBondPolicyId(), bondAssetName);
        LenderManagerDatum bondDatum = new LenderManagerDatumConverter()
                .deserialize(bondOutput.getInlineDatum().serializeToHex());
        assertEquals(BigInteger.valueOf(100), bondDatum.liquidationFeePerMille(),
                "the factory must emit a fee-100 lender bond");

        // The recovery returns value to the lender: no pool continuation, every output at the lender.
        List<TransactionOutput> recovered = cancel.getBody().getOutputs();
        assertTrue(recovered.stream().noneMatch(o -> PoolFixtures.poolAddress().equals(o.getAddress())),
                "the recovery must leave no pool continuation");
        assertTrue(recovered.stream().allMatch(o -> FUNDER.equals(o.getAddress())),
                "every recovery output must return to the lender");
        BigInteger returned = recovered.stream()
                .map(o -> o.getValue().getCoin()).reduce(BigInteger.ZERO, BigInteger::add);
        assertTrue(returned.compareTo(BigInteger.valueOf(params.poolLiquidityLovelace())) > 0,
                "the lender must recover more than the pool's own liquidity — it came back");

        log.info("LoanFactory pipeline green: create {} bytes, borrow, recovery-cancel; emitted bond fee {}",
                sizeOf(create), bondDatum.liquidationFeePerMille());
    }

    // ======================================================================================
    // The pool-id gate — the bond's poolId, the pool NFT and the PoolManager NFT are one name
    // ======================================================================================

    /**
     * <b>The three names really are one name, read off the finished body.</b>
     * <p>
     * {@code LenderManagerDatum.poolId} is what a compounding bot follows back to a PoolManager
     * (README item 5). This factory has always written the <em>pool</em> NFT's name there, which until
     * T-024 advertised a PoolManager that was never minted — the defect FluidTokens reported. It is
     * correct now, but only because {@code pool_manager.ak:173} forces the two minted names equal, so it
     * is asserted rather than assumed: {@code LoanFactory}'s {@code POOL_ID_GATE} refuses the create if
     * the pool NFT, the PoolManager NFT and the advertised {@code poolId} are not all the same 29 bytes.
     * <p>
     * This checks the same three off the emitted transaction and the emitted bond datum, so the gate and
     * the test cannot both be wrong in the same direction: the gate reads the mint field, this also reads
     * the bond datum the borrow actually stamps.
     */
    @Test
    void thePoolNftThePoolManagerNftAndTheBondPoolIdAreTheSameName() {
        LoanFactory.Recipe recipe = recipe(PoolFixtures.defaults(), 1, 2);
        LoanFactory factory = factory(baseUniverse());
        Transaction create = factory.buildCreateWithUnpublishedPoolManagerScripts(recipe);

        String poolName = null;
        String poolManagerName = null;
        for (var multiAsset : create.getBody().getMint()) {
            String name = strip(multiAsset.getAssets().get(0).getNameAsHex());
            if (multiAsset.getPolicyId().equalsIgnoreCase(REGISTRY.getPoolPolicyId())) {
                poolName = name;
            } else if (multiAsset.getPolicyId().equalsIgnoreCase(REGISTRY.getPoolManagerPolicyId())) {
                poolManagerName = name;
            }
        }
        assertEquals(poolName, poolManagerName,
                "the pool NFT and the PoolManager NFT must carry the same asset name");

        Transaction borrow = factory.buildBorrow(recipe, create);
        TransactionOutput bond = outputWithNft(borrow,
                AddressProvider.getEntAddress(
                        Credential.fromScript(REGISTRY.getLenderManagerSpendScriptHash()),
                        LoanFixtures.NETWORK).getAddress(),
                REGISTRY.getLenderBondPolicyId(),
                PoolTxEncoder.bondAssetName(new TransactionInput(TransactionUtil.getTxHash(create), 0)));
        LenderManagerDatum bondDatum = new LenderManagerDatumConverter()
                .deserialize(bond.getInlineDatum().serializeToHex());

        assertEquals(poolName, bondDatum.poolId(),
                "the lender bond's poolId must be the PoolManager NFT that now really exists");
    }

    /**
     * <b>The pool-id gate refuses when the names disagree.</b>
     * <p>
     * Through {@code buildCreate} that branch is unreachable — {@code pool_manager.ak} forces the two
     * minted names equal and the builder writes the advertised one into both — so neutering the gate's
     * body would leave every other test green and the gate would be a name with nothing behind it. It is
     * therefore called directly, on a body whose PoolManager mint has been renamed.
     */
    @Test
    void thePoolIdGateRefusesAMintWhoseNamesDisagree() {
        LoanFactory.Recipe recipe = recipe(PoolFixtures.defaults(), 1, 2);
        LoanFactory factory = factory(baseUniverse());
        Transaction create = factory.buildCreateWithUnpublishedPoolManagerScripts(recipe);

        assertDoesNotThrow(() -> factory.assertPoolIdsAgree(recipe, create),
                "the honest create must pass its own pool-id gate");

        Transaction doctored = deserialise(create);
        doctored.getBody().getMint().stream()
                .filter(ma -> ma.getPolicyId().equalsIgnoreCase(REGISTRY.getPoolManagerPolicyId()))
                .findFirst().orElseThrow(() -> new AssertionError("no PoolManager mint entry"))
                .getAssets().set(0, new Asset("0x00" + "5b".repeat(28), BigInteger.ONE));

        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.assertPoolIdsAgree(recipe, doctored),
                "a PoolManager NFT named differently from the pool NFT must be refused");
        assertTrue(failure.getMessage().contains("POOL_ID_GATE")
                        && failure.getMessage().contains("pool_manager.ak requires them equal"),
                "the refusal must be the pool-id gate's name-equality arm: " + failure.getMessage());
        log.info("pool-id gate refusal (names disagree): {}", failure.getMessage());

        // The gate's second arm, which the first case cannot reach: rename BOTH mints to the same wrong
        // name. They now agree with each other and disagree with the poolId the lender bond advertises —
        // which is the failure mode that shipped before T-024, only in the other direction. Neutering
        // this arm alone must not leave the test green.
        Transaction bothRenamed = deserialise(create);
        for (var multiAsset : bothRenamed.getBody().getMint()) {
            multiAsset.getAssets().set(0, new Asset("0x00" + "5c".repeat(28), BigInteger.ONE));
        }
        LoanFactory.GateFailure advertised = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.assertPoolIdsAgree(recipe, bothRenamed),
                "a mint that agrees with itself but not with the advertised poolId must be refused");
        assertTrue(advertised.getMessage().contains("POOL_ID_GATE")
                        && advertised.getMessage().contains("advertises poolId"),
                "the refusal must be the pool-id gate's advertised-name arm: " + advertised.getMessage());
        log.info("pool-id gate refusal (advertised mismatch): {}", advertised.getMessage());
    }

    // ======================================================================================
    // T-026 — the lender bond's authorisation, and the two gates that keep it real
    // ======================================================================================

    /**
     * <b>The emitted bond names the recipe lender's own key, and provably not the fixture's constant.</b>
     * <p>
     * This is the field a factory-created pool got wrong on chain: the bond's {@code lenderAuth} was
     * {@link LoanFixtures#PLACEHOLDER_AUTH_HASH}, 28 bytes of arithmetic ramp with no private key behind
     * them, while the <em>pool's</em> was correctly derived from the wallet. Only
     * {@code lm_withdraw_bonds_action.ak} reads this field, and it feeds it straight to
     * {@code authorizer.authorize_action}, so a value nobody can sign for is a bond nobody can ever
     * release — and {@code PoolDatum.lenderBondInlineDatumHash} pins the datum by hash at pool creation,
     * so the choice cannot be revisited.
     * <p>
     * Read off the finished borrow body with the production {@link LenderManagerDatumConverter}, so this
     * checks the bytes an output would really carry rather than the object the factory composed.
     */
    @Test
    void theEmittedBondNamesTheRecipeLendersOwnKeyHash() {
        LoanFactory.Recipe recipe = recipe(PoolFixtures.defaults(), 1, 2);
        LoanFactory factory = factory(baseUniverse());
        Transaction create = factory.buildCreateWithUnpublishedPoolManagerScripts(recipe);
        Transaction borrow = factory.buildBorrow(recipe, create);

        LenderManagerDatum bond = emittedBond(borrow, create);
        String lenderKeyHash =
                HexUtil.encodeHexString(LENDER.getPaymentCredentialHash().orElseThrow());

        assertEquals(new AuthorizationMethod.CardanoSignature(lenderKeyHash), bond.lenderAuth(),
                "the emitted bond must be releasable by the recipe's lender and nobody else");
        assertFalse(LoanFixtures.hex(bond).toLowerCase()
                        .contains(LoanFixtures.PLACEHOLDER_AUTH_HASH.toLowerCase()),
                "no LoanFixtures placeholder may appear anywhere in the committed bond datum");
        log.info("emitted bond lenderAuth: {}", bond.lenderAuth());
    }

    /**
     * <b>The mutant: revert the bond to the fixture constant and the factory refuses it by name.</b>
     * <p>
     * {@link LoanFactory#buildCreateWithFixtureBondAuth} and
     * {@link LoanFactory#buildBorrowWithFixtureBondAuth} compose the bond exactly as the pre-fix factory
     * did — {@code LoanFixtures.bondDatum(...)}, placeholder auth and all — and change nothing else.
     * Both are refused while the datum is being composed, so no assemblable body exists at any point on
     * either path. Neuter {@code assertBondAuthIsTheRecipeLender}'s body and both halves go green, which
     * is what makes this a pin rather than a restatement.
     * <p>
     * The create half deliberately goes through the seam that bypasses
     * {@code POOL_MANAGER_PUBLICATION_GATE}, so the refusal it asserts can only be the lender-auth
     * gate's.
     */
    @Test
    void theLenderAuthGateRefusesABondBuiltFromTheFixtureConstant() {
        LoanFactory.Recipe recipe = recipe(PoolFixtures.defaults(), 1, 2);
        LoanFactory factory = factory(baseUniverse());

        LoanFactory.GateFailure atCreate = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.buildCreateWithFixtureBondAuth(recipe),
                "a pool must not commit to a bond datum whose lenderAuth is a fixture constant");
        assertTrue(atCreate.getMessage().contains("LENDER_AUTH_GATE"),
                "the refusal must be the lender-auth gate and not another: " + atCreate.getMessage());
        assertTrue(atCreate.getMessage().contains(LoanFixtures.PLACEHOLDER_AUTH_HASH),
                "the refusal must name the value it refused: " + atCreate.getMessage());
        log.info("lender-auth gate refusal (create): {}", atCreate.getMessage());

        // The borrow half needs a real pool to borrow against, so it is built honestly first — which
        // also shows the two paths are independently gated rather than one covering for the other.
        Transaction create = factory.buildCreateWithUnpublishedPoolManagerScripts(recipe);
        LoanFactory.GateFailure atBorrow = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.buildBorrowWithFixtureBondAuth(recipe, create),
                "a borrow must not emit a bond datum whose lenderAuth is a fixture constant");
        assertTrue(atBorrow.getMessage().contains("LENDER_AUTH_GATE"),
                "the refusal must be the lender-auth gate and not another: " + atBorrow.getMessage());
        log.info("lender-auth gate refusal (borrow): {}", atBorrow.getMessage());
    }

    /**
     * <b>The gate's other input, doctored: an honest bond checked against a different lender.</b>
     * <p>
     * {@code assertBondAuthIsTheRecipeLender} reads two things — the bond datum and the {@link
     * LoanFactory.Recipe} — and the mutant above only doctors the first. This doctors the second: the
     * bond composed for our lender is checked against a recipe naming {@link #wrongAddress()}, which is
     * the shape of the defect where the bond is fine and the pool was created for somebody else. The
     * gate refuses it and names both credentials, so a reader of the failure can see which one it kept.
     */
    @Test
    void theLenderAuthGateRefusesARecipeNamingAnotherLender() {
        LoanFactory.Recipe ours = recipe(PoolFixtures.defaults(), 1, 2);
        LoanFactory factory = factory(baseUniverse());
        LenderManagerDatum ourBond = LoanFixtures.bondDatum(
                new AuthorizationMethod.CardanoSignature(
                        HexUtil.encodeHexString(LENDER.getPaymentCredentialHash().orElseThrow())),
                BigInteger.valueOf(PoolFixtures.LIQUIDATION_FEE_PER_MILLE),
                LoanFixtures.noStakeCredential(), AssetType.ada(), PoolFixtures.poolAssetName());

        assertDoesNotThrow(() -> factory.assertBondAuthIsTheRecipeLender(ours, ourBond),
                "the honest pairing must pass its own gate");

        LoanFactory.Recipe someoneElses = recipeLentBy(wrongAddress());
        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.assertBondAuthIsTheRecipeLender(someoneElses, ourBond),
                "a bond naming a lender other than the recipe's must be refused");
        assertTrue(failure.getMessage().contains("LENDER_AUTH_GATE"),
                "the refusal must be the lender-auth gate: " + failure.getMessage());
        assertTrue(failure.getMessage().contains(
                        HexUtil.encodeHexString(wrongAddress().getPaymentCredentialHash().orElseThrow())),
                "the refusal must name the lender the recipe asked for: " + failure.getMessage());
        log.info("lender-auth gate refusal (wrong lender): {}", failure.getMessage());
    }

    /**
     * <b>The right hash under the wrong constructor: refused, and this case is the only thing pinning
     * that.</b>
     * <p>
     * {@code assertBondAuthIsTheRecipeLender} matches on {@code CardanoSignature} before it compares
     * hashes, and until this case existed nothing tested it: relaxing the match to accept the hash from
     * <em>any</em> constructor left the whole suite green, because both cases above already differ in
     * the hash. So the clause that carries the entire point of the gate was unpinned.
     * <p>
     * What it prevents, read off the validators at upstream {@code ff005fb}, is not a stranded bond but
     * a public one. {@code CardanoWithdrawScript(h)} is discharged by {@code authorizer.authorize_action}
     * with {@code pairs.has_key(withdrawals, Script(h))} alone — a withdrawal, never a signature — so a
     * bond delegating to a script anyone can make withdraw is a bond anyone can withdraw, along with the
     * asset-manager vault its NFT gates. The hash here is the recipe lender's own, which is exactly the
     * shape a plausible refactor produces, and the gate must still refuse it.
     */
    @Test
    void theLenderAuthGateRefusesTheRightHashUnderTheWrongConstructor() {
        LoanFactory.Recipe recipe = recipe(PoolFixtures.defaults(), 1, 2);
        LoanFactory factory = factory(baseUniverse());
        String lenderKeyHash = HexUtil.encodeHexString(LENDER.getPaymentCredentialHash().orElseThrow());

        LenderManagerDatum delegated = LoanFixtures.bondDatum(
                new AuthorizationMethod.CardanoWithdrawScript(lenderKeyHash),
                BigInteger.valueOf(PoolFixtures.LIQUIDATION_FEE_PER_MILLE),
                LoanFixtures.noStakeCredential(), AssetType.ada(), PoolFixtures.poolAssetName());

        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.assertBondAuthIsTheRecipeLender(recipe, delegated),
                "a bond delegating its lenderAuth to a withdraw script must be refused even when the "
                        + "hash is the recipe lender's own");
        assertTrue(failure.getMessage().contains("LENDER_AUTH_GATE"),
                "the refusal must be the lender-auth gate: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("CardanoWithdrawScript"),
                "the refusal must name the constructor it refused: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("CardanoSignature"),
                "the refusal must name the constructor it demanded: " + failure.getMessage());
        log.info("lender-auth gate refusal (right hash, wrong constructor): {}", failure.getMessage());
    }

    /**
     * <b>The fixture-origin gate: a placeholder anywhere in a committed datum's bytes is refused.</b>
     * <p>
     * The lender-auth gate defends one field of one datum; this one defends every field of every datum
     * the factory commits, by scanning the serialised CBOR for {@link LoanFixtures#PLACEHOLDER_CONSTANTS}.
     * Through the honest builders it never fires — the lender-auth gate stops the only path that
     * currently reaches a placeholder — so it is driven directly, with the encoded fixture bond datum on
     * one side and the honest one on the other.
     */
    @Test
    void theFixtureOriginGateRefusesADatumCarryingAPlaceholder() {
        LoanFactory factory = factory(baseUniverse());
        PlutusData placeholderBearing = LoanFixtures.encode(LoanFixtures.bondDatum(
                BigInteger.valueOf(PoolFixtures.LIQUIDATION_FEE_PER_MILLE),
                LoanFixtures.noStakeCredential(), AssetType.ada(), PoolFixtures.poolAssetName()));

        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.assertNoFixtureOriginConstants("the lender bond datum", placeholderBearing),
                "a datum carrying a LoanFixtures placeholder must not be committed");
        assertTrue(failure.getMessage().contains("FIXTURE_ORIGIN_GATE"),
                "the refusal must be the fixture-origin gate: " + failure.getMessage());
        assertTrue(failure.getMessage().contains(LoanFixtures.PLACEHOLDER_AUTH_HASH),
                "the refusal must name the placeholder it found: " + failure.getMessage());
        log.info("fixture-origin gate refusal: {}", failure.getMessage());

        PlutusData honest = LoanFixtures.encode(LoanFixtures.bondDatum(
                new AuthorizationMethod.CardanoSignature(
                        HexUtil.encodeHexString(LENDER.getPaymentCredentialHash().orElseThrow())),
                BigInteger.valueOf(PoolFixtures.LIQUIDATION_FEE_PER_MILLE),
                LoanFixtures.noStakeCredential(), AssetType.ada(), PoolFixtures.poolAssetName()));
        assertDoesNotThrow(() -> factory.assertNoFixtureOriginConstants("the lender bond datum", honest),
                "a datum built from the recipe's own credential must pass");

        assertTrue(LoanFixtures.PLACEHOLDER_CONSTANTS.contains(LoanFixtures.PLACEHOLDER_AUTH_HASH),
                "the gate is driven by the named set, so the known placeholder must be in it");
    }

    /** The emitted lender bond of a borrow, decoded with the production converter. */
    private static LenderManagerDatum emittedBond(Transaction borrow, Transaction create) {
        String bondAssetName = PoolTxEncoder.bondAssetName(
                new TransactionInput(TransactionUtil.getTxHash(create), 0));
        TransactionOutput bondOutput = outputWithNft(borrow, LoanFixtures.bondAddress(),
                REGISTRY.getLenderBondPolicyId(), bondAssetName);
        return new LenderManagerDatumConverter()
                .deserialize(bondOutput.getInlineDatum().serializeToHex());
    }

    // ======================================================================================
    // The publication gate — T-024's seventh gate: never create value you cannot release
    // ======================================================================================

    /**
     * <b>{@link LoanFactory#buildCreate} refuses today, by name, and that is the point.</b>
     * <p>
     * Every pool this factory now creates carries a PoolManager NFT, and only the pool-cancel
     * transaction can burn it. That cancel invokes three validators — the PoolManager
     * {@code general_spend}, {@code pool_manager.poolManager} and {@code pm_cancel_pool_manager} — for
     * which preview publishes no reference script. Create a pool before they exist and the PoolManager
     * is stranded: {@code pool_cancel_action} constrains no mint but its own, so the pool can still be
     * cancelled without the PoolManager, after which {@code pm_cancel_pool_manager} wants a live pool
     * holding the matching NFT and there is none. Roughly two ADA locked forever, per pool, with no
     * recovery at any price.
     * <p>
     * So the public entry point refuses. It is the same rule this repo applied at T-016-K when it built
     * the cancel before funding a pool: <b>never create value you have no code to release</b>. Here the
     * code exists and its <em>publication prerequisite</em> does not, which is the same hole.
     * <p>
     * <b>Every other {@code LoanFactory} test in this class goes through
     * {@link LoanFactory#buildCreateWithUnpublishedPoolManagerScripts} instead</b>, which bypasses this
     * gate and only this gate. That seam proves the transaction shape against the real validators; it
     * licenses nothing about submission, and it is package-private with no production caller. The two
     * on-chain runners ({@code LoanFactoryOnChainRunnerTest}, {@code LoanFactoryRunnerTest}) still call
     * {@link LoanFactory#buildCreate} and are therefore refused — intended, not a regression.
     */
    @Test
    void creatingAPoolManagerBearingPoolIsRefusedWhileItsReferenceScriptsAreUnpublished() {
        LoanFactory factory = factory(baseUniverse());

        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.buildCreate(recipe(PoolFixtures.defaults(), 1, 2)),
                "a pool whose PoolManager could never be burnt must not be created");

        assertTrue(failure.getMessage().contains("POOL_MANAGER_PUBLICATION_GATE"),
                "the refusal must name this gate and not another: " + failure.getMessage());
        for (String hash : PoolFixtures.poolManagerCancelScriptHashes()) {
            assertTrue(failure.getMessage().contains(hash),
                    "the refusal must name every unpublished script — " + hash + " is missing from: "
                            + failure.getMessage());
        }
        log.info("publication gate refusal: {}", failure.getMessage());
    }

    /**
     * The gate is <b>closed by a fact, not by a constant</b>: it reads
     * {@link PoolFixtures#PUBLISHED_REFERENCE_SCRIPTS}, so the day FluidTokens publishes the three
     * coordinates and they are recorded there, it falls silent on its own and the factory may create
     * real PoolManager-bearing pools.
     * <p>
     * This asserts that wiring rather than the outcome: the set the gate refuses over is exactly the set
     * of pool-manager validators absent from the published table. Were the gate hardcoded to "always
     * refuse", this would still pass — which is why the test above additionally requires each hash to
     * appear in the message, and why {@link #creatingAPoolManagerBearingPoolIsRefusedWhileItsReferenceScriptsAreUnpublished}
     * turns green the moment the table is completed.
     */
    @Test
    void thePublicationGateIsDrivenByThePublishedCoordinateTable() {
        assertEquals(3, PoolFixtures.poolManagerCancelScriptHashes().size(),
                "the cancel invokes three pool-manager validators");
        assertEquals(PoolFixtures.poolManagerCancelScriptHashes(),
                PoolFixtures.unpublishedPoolManagerScripts(),
                "all three are unpublished today, which is why the gate is closed");
        for (String hash : PoolFixtures.poolManagerCancelScriptHashes()) {
            assertFalse(PoolFixtures.PUBLISHED_REFERENCE_SCRIPTS.containsKey(hash),
                    hash + " must not appear in the verified published table");
        }
    }

    // ======================================================================================
    // The ex-units gate — the fifth gate, and the null path it must not disturb
    // ======================================================================================

    /**
     * <b>The null path is pinned.</b> A {@link LoanFactory} constructed without an evaluator is the
     * build-only tool it has always been: the create build's serialised bytes hash to
     * {@link #NO_EVALUATOR_CREATE_SHA256}, its fee is {@link #NO_EVALUATOR_CREATE_FEE}, and its Mint#0
     * redeemer still carries {@code ScriptTx}'s placeholder {@code mem 10000 / steps 10000} — the
     * evaluator is what writes real budgets, and there is none here. Any drift in the no-evaluator path
     * — including the ex-units gate firing where it must stay silent — turns this red. See
     * {@link #NO_EVALUATOR_CREATE_FEE} for the one time these numbers moved and why.
     */
    @Test
    void withoutAnEvaluatorTheCreateBuildIsUnchanged() throws Exception {
        LoanFactory factory = factory(baseUniverse());
        Transaction create = factory.buildCreateWithUnpublishedPoolManagerScripts(recipe(PoolFixtures.defaults(), 1, 2));

        assertEquals(BigInteger.valueOf(NO_EVALUATOR_CREATE_FEE), create.getBody().getFee(),
                "the no-evaluator create fee must not move");
        assertEquals(NO_EVALUATOR_CREATE_SHA256, sha256Of(create),
                "the no-evaluator create transaction must be byte-identical to the pre-evaluator tree");

        List<Redeemer> redeemers = create.getWitnessSet().getRedeemers();
        assertEquals(2, redeemers.size(),
                "since T-024 the create carries two redeemers: the pool mint and the PoolManager mint");
        for (Redeemer redeemer : redeemers) {
            assertEquals(BigInteger.valueOf(10_000), redeemer.getExUnits().getMem(),
                    "without an evaluator the placeholder mem must survive");
            assertEquals(BigInteger.valueOf(10_000), redeemer.getExUnits().getSteps(),
                    "without an evaluator the placeholder steps must survive");
        }
    }

    /**
     * <b>The evaluator path writes real budgets.</b> The same recipe, through a {@link LoanFactory} that
     * holds a real {@link AikenTransactionEvaluator} over the same synthetic universe, comes back with
     * Mint#0 carrying the budget the UPLC machine measured — orders of magnitude above the placeholder —
     * and a fee that rose to pay for it. This is the transaction shape that can actually pass phase-2
     * validation.
     */
    @Test
    void withAnEvaluatorTheCreateBuildCarriesRealExUnits() throws Exception {
        List<Utxo> universe = baseUniverse();
        LoanFactory factory = factoryWith(universe, evaluatorOver(createEvalUniverse()));
        Transaction create = factory.buildCreateWithUnpublishedPoolManagerScripts(recipe(PoolFixtures.defaults(), 1, 2));

        for (Redeemer redeemer : create.getWitnessSet().getRedeemers()) {
            assertTrue(redeemer.getExUnits().getMem().longValueExact() > 10_000L
                            && redeemer.getExUnits().getSteps().longValueExact() > 10_000L,
                    "the evaluator must replace every placeholder budget: " + redeemer.getExUnits());
        }
        Redeemer mint = create.getWitnessSet().getRedeemers().get(0);
        assertTrue(create.getBody().getFee().longValueExact() > NO_EVALUATOR_CREATE_FEE,
                "a real budget costs real lovelace: fee " + create.getBody().getFee()
                        + " must exceed the placeholder-budget fee " + NO_EVALUATOR_CREATE_FEE);

        log.info("evaluator path: Mint#0 mem={} steps={}, fee {} (placeholder path: 10000/10000, fee {})",
                mint.getExUnits().getMem(), mint.getExUnits().getSteps(), create.getBody().getFee(),
                NO_EVALUATOR_CREATE_FEE);
    }

    /**
     * <b>F-e1 — the ex-units gate has teeth against a placeholder budget.</b> An evaluator that
     * <em>succeeds</em> but reports {@code mem 10000 / steps 10000} is exactly the failure this gate
     * exists for: cardano-client-lib writes back whatever the evaluator says, so the transaction builds
     * cleanly, dry-evaluates green, would pass every other gate, and would still be rejected in phase 2
     * on chain. This is also the shape the swallowed-evaluator defect produced, so the gate refuses the
     * very thing whose absence let it hide.
     * <p>
     * Nothing but the gate can refuse here: the evaluator lies about the cost, not about the shape, so
     * the body handed to the validators is the same one the positive case returns.
     *
     * <h2>It asserts the placeholder arm's own wording, not just the gate's name</h2>
     * A placeholder budget also under-declares, so the gate's <em>comparison</em> arm would refuse this
     * transaction too — and a bare {@code contains("EX_UNITS_GATE")} cannot tell the two apart. Under
     * that weaker assertion the placeholder arm could be deleted outright and both this case and F-e2
     * would stay green, which is to say the arm was undefended. Asserting the phrase only that arm emits
     * is what makes deleting it redden this test.
     */
    @Test
    void anEvaluatorReportingThePlaceholderBudgetIsRefusedByTheExUnitsGate() {
        LoanFactory factory = factoryWith(baseUniverse(),
                understatingEvaluator(createEvalUniverse(), AS_PLACEHOLDER));

        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.buildCreateWithUnpublishedPoolManagerScripts(recipe(PoolFixtures.defaults(), 1, 2)),
                "a transaction still carrying the placeholder budget must not be returned");
        assertTrue(failure.getMessage().contains("EX_UNITS_GATE"),
                "the refusal must be the ex-units gate, not another gate: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("still carries the placeholder budget"),
                "the refusal must be the ex-units gate's PLACEHOLDER arm, not its comparison arm — "
                        + "otherwise deleting that arm would leave this test green: " + failure.getMessage());
        log.info("F-e1 ex-units gate refusal: {}", failure.getMessage());
    }

    /**
     * <b>F-e2 — and against a merely understated one.</b> Halving the measured budget clears the
     * placeholder signature ({@code mem 101,080 / steps 35,291,940} is nowhere near 10,000) yet is still
     * only half of what the scripts really cost, so this drives the gate's <em>comparison</em> arm rather
     * than its placeholder arm. Without that arm a plausible-looking but insufficient budget would pass.
     */
    @Test
    void anEvaluatorThatHalvesTheBudgetIsRefusedByTheExUnitsGate() {
        LoanFactory factory = factoryWith(baseUniverse(), understatingEvaluator(createEvalUniverse(), HALVED));

        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.buildCreateWithUnpublishedPoolManagerScripts(recipe(PoolFixtures.defaults(), 1, 2)),
                "a transaction declaring half of what it really costs must not be returned");
        assertTrue(failure.getMessage().contains("EX_UNITS_GATE")
                        && failure.getMessage().contains("really costs"),
                "the refusal must be the ex-units gate's comparison arm: " + failure.getMessage());
        log.info("F-e2 ex-units gate refusal: {}", failure.getMessage());
    }

    // ======================================================================================
    // The ledger-preflight gate — the sixth gate, and the two ledger rules behind it
    // ======================================================================================

    /**
     * <b>The submittable create satisfies both rules the preview ledger enforced.</b> Built through a
     * {@link LoanFactory} that holds a real evaluator, the create transaction comes back with
     * <ul>
     *   <li>an <b>empty</b> Plutus witness set — {@code pool.pool} travels by reference input only, so
     *       Conway's {@code ExtraneousScriptWitnessesUTXOW} rule has nothing to fire on; and</li>
     *   <li>a fee that is higher than the same build with the reference-script wiring omitted by exactly
     *       the Conway reference-script charge, {@code scriptRefBytes × minFeeRefScriptCostPerByte}.</li>
     * </ul>
     * The two builds differ only in that wiring, and cardano-client-lib strips the witness copy
     * <em>after</em> balancing, so the base fee and the script fee are identical between them and the
     * whole difference is the reference-script charge. That difference is what
     * {@code FeeTooSmallUTxO Mismatch (RelGTEQ) {supplied: Coin 290451, expected: Coin 314662}} was: the
     * charge was never levied at all.
     */
    @Test
    void withAnEvaluatorTheCreateStripsTheWitnessCopyAndPaysTheReferenceScriptFee() throws Exception {
        LoanFactory factory = factoryWith(baseUniverse(), evaluatorOver(createEvalUniverse()));
        LoanFactory.Recipe recipe = recipe(PoolFixtures.defaults(), 1, 2);

        Transaction wired = factory.buildCreateWithUnpublishedPoolManagerScripts(recipe);
        Transaction unwired = factory.assembleCreateWithoutReferenceScriptWiring(recipe);

        // T-024: the witness set is no longer empty and must not be asserted empty. The PoolManager
        // policy legitimately travels there — preview publishes no reference script for it — so the
        // property that matters is narrower and sharper: pool.pool, the one script that ALSO sits on a
        // reference input, must not be in the witness set. That is the whole of the Conway rule this
        // wiring exists to satisfy.
        assertEquals(List.of(REGISTRY.getPoolManagerPolicyId()), witnessScriptHashes(wired),
                "the submittable create must witness the PoolManager policy and nothing else — above "
                        + "all not pool.pool, which travels by reference input");
        assertEquals(List.of(REGISTRY.getPoolManagerPolicyId(), REGISTRY.getPoolPolicyId()).stream()
                        .sorted().toList(),
                witnessScriptHashes(unwired).stream().sorted().toList(),
                "the unwired build must still carry the duplicate pool.pool witness copy — otherwise "
                        + "this comparison proves nothing");

        int refScriptBytes = REGISTRY.getPoolScript().scriptRefBytes().length;
        assertTrue(refScriptBytes < CONWAY_REF_SCRIPT_TIER_BYTES,
                "pool.pool is " + refScriptBytes + " bytes; past " + CONWAY_REF_SCRIPT_TIER_BYTES
                        + " Conway's tiered multiplier applies and the flat rate below is wrong");
        BigInteger expectedRefScriptFee =
                BigInteger.valueOf((long) refScriptBytes * MIN_FEE_REF_SCRIPT_COST_PER_BYTE);

        assertEquals(expectedRefScriptFee, wired.getBody().getFee().subtract(unwired.getBody().getFee()),
                "the wired build must pay exactly the Conway reference-script charge more than the "
                        + "unwired one: " + wired.getBody().getFee() + " vs " + unwired.getBody().getFee());

        log.info("create reference-script fee: {} bytes x {} = {} lovelace (fee {} wired, {} unwired)",
                refScriptBytes, MIN_FEE_REF_SCRIPT_COST_PER_BYTE, expectedRefScriptFee,
                wired.getBody().getFee(), unwired.getBody().getFee());
    }

    /**
     * <b>The ledger-preflight gate has teeth.</b> The seam rebuilds the create exactly as the builder did
     * before this defect was fixed — {@code pool.pool} in the witness set <em>and</em> on a reference
     * input — which is the transaction preview refused in phase 1 with
     * {@code ExtraneousScriptWitnessesUTXOW (NonEmptySet (fromList [ScriptHash "65a0bc5e…"]))}. Nothing
     * else about the body differs: it dry-evaluates green and clears the ex-units gate, exactly as it did
     * on the day the chain was the first thing to notice. Only the sixth gate can refuse it.
     */
    @Test
    void aCreateCarryingItsPolicyBothWaysIsRefusedByTheLedgerPreflightGate() {
        LoanFactory factory = factoryWith(baseUniverse(), evaluatorOver(createEvalUniverse()));

        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.buildCreateWithoutReferenceScriptWiring(recipe(PoolFixtures.defaults(), 1, 2)),
                "a transaction whose policy travels both ways must not be returned");
        assertTrue(failure.getMessage().contains("LEDGER_PREFLIGHT"),
                "the refusal must be the ledger-preflight gate, not another gate: " + failure.getMessage());
        assertTrue(failure.getMessage().contains(REGISTRY.getPoolPolicyId()),
                "the refusal must name the duplicated script, pool.pool " + REGISTRY.getPoolPolicyId()
                        + ": " + failure.getMessage());
        log.info("ledger-preflight gate refusal: {}", failure.getMessage());
    }

    /**
     * <b>All three builders, on the submittable path, keep their witness sets clean.</b> The borrow and
     * the cancel already declared their six and three validators to {@code withReferenceScripts} and
     * asked for the duplicate copies to be stripped — including the three mint policies the borrow's
     * {@code mintAsset} calls attach — so the defect the create had was theirs to have and they did not
     * have it. This drives the whole pipeline through an evaluator-armed factory and lets the sixth gate
     * judge each transaction, which is the only way to know rather than assume.
     * <p>
     * Two factories, as the on-chain rehearsal uses: the borrow and the cancel spend the create's
     * continuation, which does not exist in the create's own evaluation universe.
     */
    @Test
    void borrowAndCancelAlsoPassTheLedgerPreflightGate() {
        LoanFactory.Recipe recipe = recipe(PoolFixtures.defaults(), 1, 2);
        Transaction create = factoryWith(baseUniverse(), evaluatorOver(createEvalUniverse()))
                .buildCreateWithUnpublishedPoolManagerScripts(recipe);

        List<Utxo> poolUniverse = new ArrayList<>(baseUniverse());
        poolUniverse.add(poolContinuationOf(create));
        poolUniverse.add(poolManagerContinuationOf(create));
        poolUniverse.addAll(borrowReferenceScriptUtxos());
        poolUniverse.addAll(cancelReferenceScriptUtxos());
        LoanFactory poolFactory = factoryWith(baseUniverse(), evaluatorOverReferencing(poolUniverse));

        Transaction borrow = poolFactory.buildBorrow(recipe, create);
        Transaction cancel = poolFactory.buildRecoveryCancel(recipe, create);

        assertEquals(List.of(REGISTRY.getPoolManagerPolicyId()), witnessScriptHashes(create),
                "the create witnesses only the PoolManager policy, which is on no reference input");
        for (Transaction tx : List.of(borrow, cancel)) {
            assertTrue(tx.getWitnessSet().getPlutusV3Scripts() == null
                            || tx.getWitnessSet().getPlutusV3Scripts().isEmpty(),
                    "the borrow and the cancel must carry an empty Plutus witness set");
        }
        log.info("ledger-preflight green on create, borrow and cancel; fees {} / {} / {}",
                create.getBody().getFee(), borrow.getBody().getFee(), cancel.getBody().getFee());
    }

    /**
     * <b>F-p1 — the ledger-preflight gate refuses when it cannot see its own input.</b> The gate builds
     * its {@code referenced} set from {@code Utxo.getReferenceScriptHash()}. Hand it a universe where
     * that is null everywhere — which is precisely what {@link PoolFixtures#poolPolicyRefScriptUtxo()},
     * a plain ada UTxO, produces — and the set is empty, no witnessed script can ever be found in it,
     * and the gate returns green on <em>anything</em>. Reproduced before this precondition existed: a
     * create carrying {@code pool.pool} both in the witness set and on a reference input passed all six
     * gates. Blindness is not innocence, and this asserts the gate now says so.
     *
     * <h2>It is the auditor's transaction, exactly</h2>
     * The build is the <em>unwired</em> one — {@code pool.pool} in the witness set <em>and</em> on a
     * reference input, the shape preview refused with {@code ExtraneousScriptWitnessesUTXOW} — paired
     * with a reference-script UTxO that does not publish its hash. Both halves are load-bearing. The
     * witness copy is what keeps the earlier gates honest: the dry-evaluator resolves the policy straight
     * out of the witness set, so DRY_EVAL and EX_UNITS go green on their own merits and the refusal can
     * only come from the sixth gate. And the blind UTxO is what used to make that sixth gate wave the
     * transaction through. Before the precondition, this method returned a transaction.
     */
    @Test
    void aPreflightGateThatCannotSeeAnyReferenceScriptRefusesRatherThanPass() {
        LoanFactory factory = factoryWith(baseUniverse(), evaluatorOver(createEvalUniverse()));
        LoanFactory.Recipe recipe = recipeReferencing(blindPoolPolicyRefScriptUtxo());

        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.buildCreateWithoutReferenceScriptWiring(recipe),
                "a preflight gate with no reference-script hash in sight must refuse, not pass");
        assertTrue(failure.getMessage().contains("LEDGER_PREFLIGHT"),
                "the refusal must be the ledger-preflight gate: " + failure.getMessage());
        assertTrue(failure.getMessage().contains("cannot see its input"),
                "the refusal must name blindness, not a duplicate it did not find: " + failure.getMessage());
        log.info("F-p1 blind-gate refusal: {}", failure.getMessage());
    }

    /**
     * <b>F-p2 — the ledger-preflight gate lets a witnessed-but-not-referenced script through.</b> The
     * gate's correlation clause ({@code if (referenced.contains(witnessed))}) is what makes it a
     * duplicate detector rather than a blanket ban on witness-set scripts. Widened to refuse every
     * witnessed script the whole suite stays green, because every evaluator-armed build produces an empty
     * witness set and the loop body never executes — the clause was asserted by nothing.
     *
     * <h2>The failure this guards</h2>
     * A future phase that legitimately witnesses a script nobody published — a native timelock, an
     * unpublished validator, a policy too small to be worth a reference UTxO — would be refused with a
     * message accusing it of travelling both ways when it travels exactly one. That refusal would be
     * unfalsifiable from the message and would send the reader hunting a duplicate that does not exist.
     *
     * <h2>How the shape is produced</h2>
     * The unwired build puts {@code pool.pool} in the witness set, and the recipe's reference-script UTxO
     * publishes {@code general_spend} instead — so the gate sees a non-empty {@code referenced} set that
     * simply does not contain the witnessed hash. That non-emptiness is also what keeps this case clear
     * of {@link #aPreflightGateThatCannotSeeAnyReferenceScriptRefusesRatherThanPass}'s precondition: the
     * two tests drive opposite sides of the same gate and must not mask each other, so if that
     * precondition is ever restated in terms of something other than "the referenced set is empty", this
     * test is where the coupling shows up.
     */
    @Test
    void aWitnessedScriptThatIsNotAlsoAReferenceScriptPassesTheLedgerPreflightGate() {
        LoanFactory factory = factoryWith(baseUniverse(), evaluatorOver(createEvalUniverse()));
        LoanFactory.Recipe recipe =
                recipeReferencing(refScriptUtxoPublishing(REGISTRY.getPoolSpendScriptHash()));

        Transaction create = factory.buildCreateWithoutReferenceScriptWiring(recipe);

        // The clause is only exercised if the witness set really carries a script the gate had to judge.
        List<String> witnessed = create.getWitnessSet().getPlutusV3Scripts().stream()
                .map(script -> {
                    try {
                        return HexUtil.encodeHexString(script.getScriptHash());
                    } catch (Exception e) {
                        throw new AssertionError("cannot hash a witness-set script", e);
                    }
                })
                .toList();
        assertTrue(witnessed.contains(REGISTRY.getPoolPolicyId()),
                "the witness set must carry pool.pool, or the correlation clause never ran and this "
                        + "test proves nothing: " + witnessed);
        assertFalse(REGISTRY.getPoolSpendScriptHash().equals(REGISTRY.getPoolPolicyId()),
                "the referenced and witnessed hashes must differ, or this is the duplicate case");

        log.info("F-p2 preflight allowed a witnessed-only script: witnessed {}, referenced {}",
                REGISTRY.getPoolPolicyId(), REGISTRY.getPoolSpendScriptHash());
    }

    /**
     * A reference-script UTxO at the pool policy's published coordinate that declares it publishes
     * {@code scriptHash} — used to give the preflight gate a non-empty {@code referenced} set whose
     * contents are under the test's control.
     */
    private static Utxo refScriptUtxoPublishing(String scriptHash) {
        Utxo publishing = poolPolicyRefScriptUtxo();
        return Utxo.builder()
                .txHash(publishing.getTxHash())
                .outputIndex(publishing.getOutputIndex())
                .address(publishing.getAddress())
                .amount(publishing.getAmount())
                .referenceScriptHash(scriptHash)
                .build();
    }

    /**
     * The published {@code pool.pool} reference-script UTxO with its {@code referenceScriptHash} left
     * <b>null</b> — same coordinate, same address, same value as {@link #poolPolicyRefScriptUtxo()}, so
     * the transaction it produces is byte-identical. This is the shape
     * {@link PoolFixtures#poolPolicyRefScriptUtxo()} has (and correctly so, for a rig where the policy
     * travelled in the witness set); the point is that {@link LoanFactory} must not be fooled by it.
     */
    private static Utxo blindPoolPolicyRefScriptUtxo() {
        Utxo publishing = poolPolicyRefScriptUtxo();
        return Utxo.builder()
                .txHash(publishing.getTxHash())
                .outputIndex(publishing.getOutputIndex())
                .address(publishing.getAddress())
                .amount(publishing.getAmount())
                .build();
    }

    /**
     * The PoolManager output the create produced, found by address rather than by a pinned position:
     * the pool is output 0 by the create builder's proven placement, the PoolManager is not pinned.
     */
    private static Utxo poolManagerContinuationOf(Transaction poolTx) {
        TransactionOutput output = poolTx.getBody().getOutputs().stream()
                .filter(o -> PoolFixtures.poolManagerAddress().equals(o.getAddress()))
                .findFirst().orElseThrow(() -> new AssertionError("the create minted no PoolManager"));
        return continuationAt(poolTx, poolTx.getBody().getOutputs().indexOf(output), output);
    }

    /** Output 0 of a pool-bearing transaction, as a {@link Utxo} the evaluator can resolve. */
    private static Utxo poolContinuationOf(Transaction poolTx) {
        return continuationAt(poolTx, 0, poolTx.getBody().getOutputs().get(0));
    }

    private static Utxo continuationAt(Transaction poolTx, int index, TransactionOutput output) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(output.getValue().getCoin()));
        for (var multiAsset : output.getValue().getMultiAssets()) {
            for (Asset asset : multiAsset.getAssets()) {
                amounts.add(Amount.asset(multiAsset.getPolicyId() + strip(asset.getNameAsHex()),
                        asset.getValue()));
            }
        }
        return LoanFixtures.utxo(TransactionUtil.getTxHash(poolTx), index, output.getAddress(), amounts,
                output.getInlineDatum() == null ? null : output.getInlineDatum().serializeToHex());
    }

    // ======================================================================================
    // The build-only claim, made checkable
    // ======================================================================================

    /**
     * <b>"{@link LoanFactory} holds no backend and cannot submit" is a guard, not prose.</b> That
     * sentence appears in four class javadocs and was enforced by nothing: a public method added to
     * {@link LoanFactory} that opens a {@code BFBackendService} and calls {@code submitTransaction}
     * left the entire suite green. This is the same guard
     * {@code LiquidateTransactionBuilderTest#theBuilderHoldsABackendAndNeverSubmitsThroughIt} applies
     * to the production builder, extended over the origination tool and the three pool builders it
     * composes.
     *
     * <h2>Two halves, because neither is sufficient</h2>
     * Reflection catches a class that <em>hands out</em> something able to submit — a
     * {@code TransactionProcessor}, a {@code BackendService}, a {@code TransactionSubmitter} — which no
     * source scan can see through a type alias. The comment-stripped source scan catches a class that
     * submits <em>itself</em>, which reflection cannot see because such a method returns an ordinary
     * {@code Result<String>}. The mutant this finding names is of the second kind.
     *
     * <h2>Why {@code BFBackendService} is forbidden here and not there</h2>
     * {@code LiquidateTransactionBuilder} legitimately holds a backend — its guard bans only leaking and
     * submitting. These four claim something stronger in their own javadocs ({@link LoanFactory}: "holds
     * none of its own, opens no backend, signs nothing"), so merely naming the class is a breach of the
     * documented contract and is banned outright.
     *
     * <h2>The runner is excluded, deliberately</h2>
     * {@code LoanFactoryOnChainRunnerTest} is the one class here that <em>may</em> submit: it is a manual
     * script, gated behind {@code WALLET_B_MNEMONIC} + {@code BLOCKFROST_KEY} and then behind an exact
     * {@code AQUARIUM_X_SUBMIT=true}, and submitting under that gate is its entire purpose. Banning
     * {@code submitTransaction} there would ban the feature. It is named in {@link #BUILD_ONLY_CLASSES}'s
     * absence on purpose, so that a reader asking "why is the runner not in this list" finds the answer
     * rather than assuming an oversight.
     */
    @Test
    void theOriginationToolAndItsThreeBuildersCanNeverSubmit() throws Exception {
        for (Class<?> buildOnly : BUILD_ONLY_CLASSES) {
            // Nothing any of them RETURNS can submit, or hand a caller something that can.
            for (Method method : buildOnly.getDeclaredMethods()) {
                Class<?> returned = method.getReturnType();
                assertFalse(TransactionProcessor.class.isAssignableFrom(returned),
                        buildOnly.getSimpleName() + "." + method.getName() + " returns a processor");
                assertFalse(returned.getName().contains("BackendService"),
                        buildOnly.getSimpleName() + "." + method.getName() + " returns a backend");
                assertFalse(LiquidationExecutor.TransactionSubmitter.class.isAssignableFrom(returned),
                        buildOnly.getSimpleName() + "." + method.getName() + " returns a submitter");
            }

            // And none of them submits itself. Comments and javadoc are stripped first, so the prose
            // these classes are full of — "cannot submit", "never submits" — does not count as a call.
            Path source = Path.of("src/test/java/com/fluidtokens/aquarium/offchain/service/loans/"
                    + buildOnly.getSimpleName() + ".java");
            assertTrue(Files.exists(source), "no source found for " + buildOnly.getSimpleName()
                    + " at " + source + " — a moved file would silently stop being guarded");
            String withoutComments = Files.readString(source)
                    .replaceAll("(?s)/\\*.*?\\*/", "")
                    .replaceAll("//.*", "");
            for (String forbidden : List.of(".submit(", ".submitTransaction(", ".signAndSubmit(",
                    "getTransactionService()", "BFBackendService")) {
                assertFalse(withoutComments.contains(forbidden),
                        buildOnly.getSimpleName() + "'s source contains " + forbidden
                                + " — it is documented as build-only and must hold no backend and no "
                                + "submit path, not even an off-by-default one");
            }
        }
        log.info("build-only guard green over {}", BUILD_ONLY_CLASSES.stream()
                .map(Class::getSimpleName).toList());
    }

    /**
     * The four classes whose javadocs claim they cannot submit. {@code LoanFactoryOnChainRunnerTest} is
     * deliberately <b>not</b> here — see {@link #theOriginationToolAndItsThreeBuildersCanNeverSubmit}.
     */
    private static final List<Class<?>> BUILD_ONLY_CLASSES = List.of(
            LoanFactory.class,
            PoolCreateTransactionBuilder.class,
            PoolBorrowTransactionBuilder.class,
            PoolCancelTransactionBuilder.class);

    // ======================================================================================
    // F-a — a fee-0 pool is refused by the fee gate
    // ======================================================================================

    @Test
    void aFeeZeroPoolIsRefusedByTheFeeGate() {
        PoolFixtures.PoolParameters feeZero = withFee(PoolFixtures.defaults(), 0L);
        LoanFactory factory = factory(baseUniverse());
        LoanFactory.Recipe recipe = recipe(feeZero, 1, 2);  // a liquidatable price, so only the fee gate can fire

        Transaction create = factory.buildCreateWithUnpublishedPoolManagerScripts(recipe);
        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.buildBorrow(recipe, create),
                "a fee-0 pool must not yield a returned borrow transaction");
        assertTrue(failure.getMessage().contains("FEE_GATE"),
                "the refusal must be the fee gate, not an incidental failure: " + failure.getMessage());
        log.info("F-a fee gate refusal: {}", failure.getMessage());
    }

    // ======================================================================================
    // F-d — a transaction the real validators reject is refused by the dry-eval gate
    // ======================================================================================

    /**
     * The dry-eval gate is the tool's first line: every returned transaction must have passed the real
     * PlutusV3 machine. This drives it in the refusing direction with a transaction that <em>builds</em>
     * cleanly (it passes the borrow builder's own structural assertion) but that the deployed
     * {@code pool_borrow_action} rejects — a validity window wider than one hour, which
     * {@link PoolBorrowDryEvalTest} pinned as a genuine {@code Withdraw#0} refusal. Because the dry-eval
     * gate runs <em>before</em> the fee and born-liquidatable gates, an otherwise-fee-100, liquidatable
     * recipe still fails here, proving this gate — not a later one — is what refuses. Without the gate the
     * tool would hand back a transaction the chain will not accept.
     */
    @Test
    void aTransactionTheValidatorsRejectIsRefusedByTheDryEvalGate() {
        PoolFixtures.PoolParameters params = PoolFixtures.defaults();   // fee 100, liquidatable at 1/2
        LoanFactory factory = factory(baseUniverse());
        // A 3,700-slot (≈1h2m) window — wider than the validator's one-hour ceiling, but otherwise honest.
        LoanFactory.Recipe recipe = recipe(params, 1, 2, VALID_FROM_SLOT + 3_700L);

        Transaction create = factory.buildCreateWithUnpublishedPoolManagerScripts(recipe);
        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.buildBorrow(recipe, create),
                "a borrow the real validators reject must not be returned");
        assertTrue(failure.getMessage().contains("DRY_EVAL_GATE"),
                "the refusal must be the dry-eval gate, not a later gate: " + failure.getMessage());
        log.info("F-d dry-eval gate refusal: {}", failure.getMessage());
    }

    // ======================================================================================
    // F-b — a healthy price is refused by the born-liquidatable gate
    // ======================================================================================

    @Test
    void aHealthyCollateralPriceIsRefusedByTheBornLiquidatableGate() {
        PoolFixtures.PoolParameters params = PoolFixtures.defaults();   // fee 100, so the fee gate passes
        LoanFactory factory = factory(baseUniverse());
        LoanFactory.Recipe recipe = recipe(params, 1, 1);  // 1 lovelace/unit: the borrower has equity

        Transaction create = factory.buildCreateWithUnpublishedPoolManagerScripts(recipe);
        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.buildBorrow(recipe, create),
                "a loan that is not born liquidatable must be refused");
        assertTrue(failure.getMessage().contains("BORN_LIQUIDATABLE_GATE"),
                "the refusal must be the born-liquidatable gate: " + failure.getMessage());
        log.info("F-b born-liquidatable gate refusal: {}", failure.getMessage());
    }

    // ======================================================================================
    // F-c — a recovery to the wrong address is refused by the destination gate
    // ======================================================================================

    @Test
    void aRecoveryToTheWrongAddressIsRefusedByTheDestinationGate() {
        PoolFixtures.PoolParameters params = PoolFixtures.defaults();
        Address wrong = wrongAddress();

        // The seam sends the change to `wrong` while the required signer stays the lender, so the cancel
        // dry-evaluates green and only the destination gate can refuse it. Coin selection needs a
        // collateral UTxO at `wrong`, so the construction supplier carries one.
        List<Utxo> universe = new ArrayList<>(baseUniverse());
        universe.add(LoanFixtures.adaUtxo("ea".repeat(32), 0, wrong.getAddress(), 60_000_000L));
        LoanFactory factory = factory(universe);
        LoanFactory.Recipe recipe = recipe(params, 1, 2);

        Transaction create = factory.buildCreateWithUnpublishedPoolManagerScripts(recipe);
        LoanFactory.GateFailure failure = assertThrows(LoanFactory.GateFailure.class,
                () -> factory.buildRecoveryCancel(recipe, create, wrong),
                "a recovery whose change is not the lender must be refused");
        assertTrue(failure.getMessage().contains("RECOVERY_DESTINATION_GATE"),
                "the refusal must be the recovery-destination gate: " + failure.getMessage());
        log.info("F-c destination gate refusal: {}", failure.getMessage());
    }

    // ======================================================================================
    // plumbing
    // ======================================================================================

    private static LoanFactory factory(List<Utxo> universe) {
        return new LoanFactory(REGISTRY, LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams());
    }

    private static LoanFactory factoryWith(List<Utxo> universe, TransactionEvaluator evaluator) {
        return new LoanFactory(REGISTRY, LoanFixtures.NETWORK,
                LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams(), evaluator);
    }

    /**
     * What a {@link TransactionEvaluator} wired into the create builder must be able to resolve: the
     * construction universe plus the pool-policy reference-script UTxO. That UTxO is deliberately absent
     * from {@link #baseUniverse()} (the factory resolves it from the recipe's own coordinates), and it
     * sits at the funder's address, so putting it in the construction supplier would change coin
     * selection. cardano-client-lib calls {@code evaluateTx(cbor)} with no explicit input set, so the
     * evaluator resolves every input through its <em>own</em> supplier — which is why this one is
     * separate from the factory's.
     */
    private static List<Utxo> createEvalUniverse() {
        List<Utxo> universe = new ArrayList<>(baseUniverse());
        universe.add(poolPolicyRefScriptUtxo());
        return universe;
    }

    /**
     * The published {@code pool.pool} reference-script UTxO, at its real preview coordinate and — unlike
     * {@link PoolFixtures#poolPolicyRefScriptUtxo()} — <b>publishing its reference-script hash</b>, as
     * the chain's own does. That hash is load-bearing twice over on the submittable path: it is how the
     * dry-evaluator learns the policy is reachable by reference once the witness copy is stripped, and it
     * is what {@code LoanFactory}'s ledger-preflight gate reads to decide whether a witnessed script is a
     * duplicate. The coordinate is unchanged, and a reference input contributes only its coordinate to
     * the body, so the pinned no-evaluator bytes are untouched.
     */
    private static Utxo poolPolicyRefScriptUtxo() {
        return referenceScriptUtxos(List.of(REGISTRY.getPoolPolicyId())).get(0);
    }

    /**
     * The real UPLC machine over the synthetic universe — the same rig {@link EvalFixtures} evaluates
     * with, here wired into the builders so the budgets it measures land in the redeemers.
     */
    private static TransactionEvaluator evaluatorOver(List<Utxo> universe) {
        return new AikenTransactionEvaluator(LoanFixtures.utxoSupplier(universe),
                EvalFixtures.protocolParams(), EvalFixtures.scriptSupplier(REGISTRY),
                SlotConfigs.preview());
    }

    /**
     * As above, but with every validator this pipeline can carry by <b>reference input</b> handed to the
     * script supplier. A referenced validator reaches the evaluator through neither the witness set nor
     * the UTxO — a reference-script UTxO publishes only a hash — so a borrow or a cancel evaluated
     * without these answers {@code RequiredRedeemersMismatch}. Extras a given phase does not use are
     * inert: the supplier is a lookup by hash.
     */
    private static TransactionEvaluator evaluatorOverReferencing(List<Utxo> universe) {
        return new AikenTransactionEvaluator(LoanFixtures.utxoSupplier(universe),
                EvalFixtures.protocolParams(),
                EvalFixtures.scriptSupplier(REGISTRY, List.of(
                        REGISTRY.getPoolScript(), REGISTRY.getPoolSpendScript(),
                        REGISTRY.getPoolBorrowActionScript(), REGISTRY.getPoolCancelActionScript(),
                        REGISTRY.getLoanScript(), REGISTRY.getLenderBondScript(),
                        REGISTRY.getBorrowerBondScript(),
                        // T-024: the cancel resolves these three by reference input too
                        REGISTRY.getPoolManagerSpendScript(), REGISTRY.getPoolManagerScript(),
                        REGISTRY.getPmCancelPoolManagerScript())),
                SlotConfigs.preview());
    }

    /** Reports {@code ScriptTx}'s placeholder budget — the shape the swallowed-evaluator defect produced. */
    private static final UnaryOperator<BigInteger> AS_PLACEHOLDER = budget -> BigInteger.valueOf(10_000);

    /** Reports half of what the scripts really cost — plausible-looking, and still not enough. */
    private static final UnaryOperator<BigInteger> HALVED = budget -> budget.divide(BigInteger.TWO);

    /**
     * An evaluator that reports a <em>successful</em> evaluation with every measured budget passed
     * through {@code lie}. cardano-client-lib writes back whatever the evaluator says, so this produces
     * exactly the understated-budget transaction the chain would reject in phase 2 — and nothing else
     * about the body differs from the honest build, so only the ex-units gate can refuse it.
     */
    private static TransactionEvaluator understatingEvaluator(List<Utxo> universe,
                                                              UnaryOperator<BigInteger> lie) {
        TransactionEvaluator honest = evaluatorOver(universe);
        return new TransactionEvaluator() {
            @Override
            public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos)
                    throws ApiException {
                Result<List<EvaluationResult>> result = honest.evaluateTx(cbor, inputUtxos);
                if (!result.isSuccessful()) {
                    return result;
                }
                for (EvaluationResult evaluated : result.getValue()) {
                    evaluated.getExUnits().setMem(lie.apply(evaluated.getExUnits().getMem()));
                    evaluated.getExUnits().setSteps(lie.apply(evaluated.getExUnits().getSteps()));
                }
                return result;
            }
        };
    }

    private static String sha256Of(Transaction tx) throws Exception {
        return HexUtil.encodeHexString(
                MessageDigest.getInstance("SHA-256").digest(tx.serialize()));
    }

    private static LoanFactory.Recipe recipe(PoolFixtures.PoolParameters params,
                                             long priceNumerator, long priceDenominator) {
        return recipe(params, priceNumerator, priceDenominator, VALID_TO_SLOT);
    }

    private static LoanFactory.Recipe recipe(PoolFixtures.PoolParameters params,
                                             long priceNumerator, long priceDenominator, long validToSlot) {
        return new LoanFactory.Recipe(
                params, LENDER, LENDER,
                priceNumerator, priceDenominator,
                seedUtxo(), funderUtxo(), PoolFixtures.configUtxo(), poolPolicyRefScriptUtxo(),
                borrowReferenceScriptUtxos(), cancelReferenceScriptUtxos(),
                PoolFixtures.TFLDT, params.principalLovelace(), 0L,
                LOAN_OUTPUT_LOVELACE, BOND_LOVELACE, BOND_LOVELACE,
                VALID_FROM_SLOT, validToSlot);
    }

    /**
     * The default recipe with only its lender swapped — the seam
     * {@link #theLenderAuthGateRefusesARecipeNamingAnotherLender} turns, so the gate is driven by a
     * doctored {@link LoanFactory.Recipe} and not only by a doctored datum.
     */
    private static LoanFactory.Recipe recipeLentBy(Address lender) {
        return new LoanFactory.Recipe(
                PoolFixtures.defaults(), lender, lender,
                1, 2,
                seedUtxo(), funderUtxo(), PoolFixtures.configUtxo(), poolPolicyRefScriptUtxo(),
                borrowReferenceScriptUtxos(), cancelReferenceScriptUtxos(),
                PoolFixtures.TFLDT, PoolFixtures.defaults().principalLovelace(), 0L,
                LOAN_OUTPUT_LOVELACE, BOND_LOVELACE, BOND_LOVELACE,
                VALID_FROM_SLOT, VALID_TO_SLOT);
    }

    /**
     * The default recipe with only its pool-policy reference-script UTxO swapped. That UTxO reaches
     * {@code LoanFactory#universeOf} as the create phase's hash-bearing coordinate, so it is exactly and
     * only the ledger-preflight gate's view of the world that changes; the body, the evaluator's own
     * universe and every other gate are untouched. The seam both preflight cases below turn.
     */
    private static LoanFactory.Recipe recipeReferencing(Utxo poolPolicyRefScriptUtxo) {
        return new LoanFactory.Recipe(
                PoolFixtures.defaults(), LENDER, LENDER,
                1, 2,
                seedUtxo(), funderUtxo(), PoolFixtures.configUtxo(), poolPolicyRefScriptUtxo,
                borrowReferenceScriptUtxos(), cancelReferenceScriptUtxos(),
                PoolFixtures.TFLDT, PoolFixtures.defaults().principalLovelace(), 0L,
                LOAN_OUTPUT_LOVELACE, BOND_LOVELACE, BOND_LOVELACE,
                VALID_FROM_SLOT, VALID_TO_SLOT);
    }

    private static PoolFixtures.PoolParameters withFee(PoolFixtures.PoolParameters p, long feePerMille) {
        return new PoolFixtures.PoolParameters(p.principalLovelace(), p.collateralPerPrincipalNumerator(),
                p.collateralPerPrincipalDivider(), p.interestRate(), p.liquidationLtv(),
                p.liquidationLtvDivider(), p.partialLiquidationPenaltyPerMille(), feePerMille,
                p.poolLiquidityLovelace(), p.loanOutputLovelace());
    }

    private static Utxo seedUtxo() {
        return PoolFixtures.seedUtxo(FUNDER);
    }

    /** The funder input: ada for fees and collateral, plus the tFLDT the loan output posts. */
    private static Utxo funderUtxo() {
        return LoanFixtures.utxo(FUNDER_TX, 0, FUNDER,
                List.of(Amount.lovelace(BigInteger.valueOf(100_000_000L)),
                        Amount.asset(LoanFixtures.unit(PoolFixtures.TFLDT), BigInteger.valueOf(20_000_000L))),
                null);
    }

    /**
     * The construction supplier's universe: the spend inputs coin selection draws on (the seed, the
     * funder input and a collateral UTxO), plus the config the reference-input resolution falls back to.
     * The published reference scripts are resolved from the recipe's own hash-bearing coordinates (see
     * {@code LoanFactory#universeOf}), so they are deliberately not added here.
     */
    private static List<Utxo> baseUniverse() {
        List<Utxo> universe = new ArrayList<>();
        universe.add(seedUtxo());
        universe.add(funderUtxo());
        universe.add(LoanFixtures.adaUtxo(COLLATERAL_TX, 1, FUNDER, 60_000_000L));
        universe.add(PoolFixtures.configUtxo());
        return universe;
    }

    private static List<Utxo> borrowReferenceScriptUtxos() {
        return referenceScriptUtxos(List.of(REGISTRY.getPoolPolicyId(), REGISTRY.getPoolSpendScriptHash(),
                REGISTRY.getPoolBorrowActionScriptHash(), REGISTRY.getLoanPolicyId(),
                REGISTRY.getLenderBondPolicyId(), REGISTRY.getBorrowerBondPolicyId()));
    }

    /**
     * The cancel's six reference scripts. The three pool ones carry published preview coordinates; the
     * three pool-manager ones carry synthesised coordinates, because preview publishes none — the very
     * fact {@code LoanFactory}'s publication gate refuses to create a pool over. See
     * {@link PoolFixtures#SYNTHETIC_POOL_MANAGER_REFERENCE_SCRIPTS}.
     */
    private static List<Utxo> cancelReferenceScriptUtxos() {
        List<String> hashes = new ArrayList<>(List.of(REGISTRY.getPoolSpendScriptHash(),
                REGISTRY.getPoolPolicyId(), REGISTRY.getPoolCancelActionScriptHash()));
        hashes.addAll(PoolFixtures.poolManagerCancelScriptHashes());
        return referenceScriptUtxos(hashes);
    }

    private static List<Utxo> referenceScriptUtxos(List<String> hashes) {
        List<Utxo> utxos = new ArrayList<>();
        for (String hash : hashes) {
            String coord = PoolFixtures.PUBLISHED_REFERENCE_SCRIPTS.containsKey(hash)
                    ? PoolFixtures.PUBLISHED_REFERENCE_SCRIPTS.get(hash)
                    : PoolFixtures.SYNTHETIC_POOL_MANAGER_REFERENCE_SCRIPTS.get(hash);
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

    /** A base address controlled by neither the lender's payment nor stake key — the "wrong" destination. */
    private static Address wrongAddress() {
        return new Address(AddressProvider.getBaseAddress(
                Credential.fromKey("33".repeat(28)), Credential.fromKey("44".repeat(28)),
                LoanFixtures.NETWORK).getAddress());
    }

    private static TransactionOutput outputWithNft(Transaction tx, String address, String policyId,
                                                   String assetNameHex) {
        return tx.getBody().getOutputs().stream()
                .filter(o -> address.equals(o.getAddress())
                        && o.getValue().getMultiAssets().stream()
                        .filter(ma -> ma.getPolicyId().equalsIgnoreCase(policyId))
                        .flatMap(ma -> ma.getAssets().stream())
                        .anyMatch(a -> strip(a.getNameAsHex()).equalsIgnoreCase(assetNameHex)))
                .findFirst().orElseThrow(() -> new AssertionError("no output at " + address + " holding "
                        + policyId + assetNameHex));
    }

    /** Every Plutus V3 script hash in the finished witness set, in witness-set order. */
    private static List<String> witnessScriptHashes(Transaction tx) {
        if (tx.getWitnessSet() == null || tx.getWitnessSet().getPlutusV3Scripts() == null) {
            return List.of();
        }
        return tx.getWitnessSet().getPlutusV3Scripts().stream()
                .map(script -> {
                    try {
                        return HexUtil.encodeHexString(script.getScriptHash());
                    } catch (Exception e) {
                        throw new AssertionError("cannot hash a witness-set script", e);
                    }
                })
                .toList();
    }

    /** A detached copy of a finished transaction, for surgery that must not touch the original. */
    private static Transaction deserialise(Transaction tx) {
        try {
            return Transaction.deserialize(tx.serialize());
        } catch (Exception e) {
            throw new AssertionError("cannot copy the transaction", e);
        }
    }

    private static int sizeOf(Transaction tx) {
        try {
            return tx.serialize().length;
        } catch (Exception e) {
            throw new AssertionError("cannot serialise", e);
        }
    }

    private static String strip(String maybePrefixed) {
        return maybePrefixed.startsWith("0x") ? maybePrefixed.substring(2) : maybePrefixed;
    }
}
