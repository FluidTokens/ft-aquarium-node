package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
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
 * The pool-creation transaction handed to the real PlutusV3 machine and run against the
 * <em>real deployed</em> {@code pool.pool} validator, in the discipline of
 * {@link RequestMintDryEvalTest}. The applied compiled code comes from
 * {@link LoansContractRegistry}'s derivation over the committed {@code loans-v4.plutus.json}; the
 * config reference input carries the {@code ConfigDatum} recorded off preview. Everything else is
 * synthetic, so the test runs cold: no network, no key, no wallet. See {@link EvalFixtures} for the
 * rig and its <em>Harness limitations</em>.
 *
 * <h2>Consequence B — a green run here does NOT say the datum is right</h2>
 * {@code pool.pool}'s {@code mint} handler ({@code validators/pool.ak:65-124}, deployed pin
 * {@code bbe9c1a}) checks the config NFT presence, that {@code redeemer.inputRef} is spent, and that
 * each minted pool token is named {@code index ‖ blake2b_224(serialise_data(inputRef))} and sits
 * alone in the correspondingly indexed output at the pool spend credential. <b>It never looks at the
 * {@code PoolDatum}.</b> So this rig cannot and does not arbitrate the datum — the schema pins and
 * the goldens in {@link PoolTxEncoderTest} are its only defences until a Borrow or Cancel spends the
 * pool.
 *
 * <h2>Assertions read the artefact, never the report</h2>
 * The {@link EvaluationResult} list is used only for the redeemer count and tag. Every structural
 * claim is read off the <em>deserialised</em> {@link Transaction}.
 *
 * <h2>The rig can fail</h2>
 * Six mutations, each of which must be refused by the {@code Mint} redeemer specifically: a bare "it
 * failed" would be satisfied by any fault anywhere, including one in our own assembly. Each was
 * confirmed red during development by producing exactly that shape through the builder's mutation
 * seam or a byte-level surgery on the honest build, and the evaluator's refusal is asserted to name
 * {@code RedeemerError { tag: "Mint", index: 0 }}.
 */
@Slf4j
class PoolCreateDryEvalTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.registry();

    private static final String FUNDER = LoanFixtures.botAddress();

    /**
     * The one validator this rig must hand the machine explicitly. {@code pool.pool} travels by
     * <b>reference input only</b>: {@link PoolCreateTransactionBuilder} declares it to
     * {@code withReferenceScripts} and has {@code removeDuplicateScriptWitnesses} strip the witness copy
     * {@code mintAsset} left behind, so the finished body carries no Plutus script at all. A
     * reference-script UTxO publishes a script's <em>hash</em>, never its bytes, so nothing in the
     * universe can give the evaluator the policy and it answers
     * {@code RequiredRedeemersMismatch { missing: [65a0bc5e…] }}. Exactly the reason
     * {@code LoanFactory#createExtraScripts} already gives for its own copy of this argument.
     * <p>
     * The bytes are only half of it — see {@link #resolvable}, which supplies the other half.
     *
     * <h2>This makes the six mutations below stronger, not weaker</h2>
     * Until the policy resolved unconditionally, a mutation that happened to break script resolution
     * would fail with {@code RequiredRedeemersMismatch} and still satisfy a bare "it was refused"
     * expectation. With the policy always resolvable, a {@code RedeemerError { tag: "Mint", index: 0 }}
     * is the deployed validator genuinely refusing the mutation rather than the rig failing to find a
     * script — which is what {@link #assertRefusedByTheMint} has always claimed to be asserting.
     */
    private static final List<com.bloxbean.cardano.client.plutus.spec.PlutusScript> POOL_POLICY =
            List.of(LoanFixtures.registry().getPoolScript());

    /** The pool liquidity leg, from the default pool parameters. */
    private static final long POOL_LOVELACE = PoolFixtures.defaults().poolLiquidityLovelace();

    // ======================================================================================
    // The positive case
    // ======================================================================================

    /**
     * One pool NFT minted, one pool output carrying the liquidity and that NFT, at the pool spend
     * address, with the factory {@code PoolDatum} inline. The evaluated ex-units for the pool mint are
     * logged (a real budget number from the UPLC machine, not a placeholder).
     */
    @Test
    void thePoolCreateEvaluatesAgainstTheDeployedPoolValidator() {
        Transaction tx = build();
        List<Utxo> universe = PoolFixtures.universe(FUNDER);

        List<EvaluationResult> results =
                EvalFixtures.evaluate(tx, resolvable(universe), REGISTRY, POOL_POLICY);

        assertEquals(2, results.size(),
                "two scripts run: the pool minting policy and the PoolManager minting policy");
        for (EvaluationResult r : results) {
            assertEquals(RedeemerTag.Mint, r.getRedeemerTag());
            log.info("Pool create mint ex-units: index={} mem={} steps={}",
                    r.getIndex(), r.getExUnits().getMem(), r.getExUnits().getSteps());
        }

        Transaction onChain = roundTrip(tx);

        // --- the mint field ---
        List<MultiAsset> mint = onChain.getBody().getMint();
        assertEquals(2, mint.size(), "two policies in the mint field: the pool and the PoolManager");
        String mintedName = soleMintedName(onChain, REGISTRY.getPoolPolicyId());
        assertEquals(PoolFixtures.poolAssetName(), mintedName);
        assertEquals(29, mintedName.length() / 2,
                "the pool NFT asset name is 29 bytes: 1-byte index prefix + 28-byte hash");
        assertEquals("00", mintedName.substring(0, 2), "the index prefix of the only minted token");

        // --- the PoolManager NFT: same name, same quantity (pool_manager.ak:173) ---
        assertEquals(mintedName, soleMintedName(onChain, REGISTRY.getPoolManagerPolicyId()),
                "the PoolManager NFT must carry the pool NFT's own asset name");

        // --- the seed really is spent ---
        assertTrue(onChain.getBody().getInputs().contains(PoolFixtures.seed()),
                "check_mint's isInputRefSpent needs the redeemer's inputRef among the inputs");

        // --- the pool output ---
        List<TransactionOutput> atPoolAddress = onChain.getBody().getOutputs().stream()
                .filter(output -> PoolFixtures.poolAddress().equals(output.getAddress()))
                .toList();
        assertEquals(1, atPoolAddress.size(), "exactly one output at the pool spend address");
        TransactionOutput poolOutput = atPoolAddress.get(0);
        assertEquals(BigInteger.valueOf(POOL_LOVELACE), poolOutput.getValue().getCoin());
        assertEquals(1, poolOutput.getValue().getMultiAssets().size(),
                "exactly one policy beside lovelace: the pool NFT");
        assertEquals(BigInteger.ONE, quantityOf(poolOutput, REGISTRY.getPoolPolicyId(), mintedName));

        // --- the PoolManager output ---
        List<TransactionOutput> atPoolManagerAddress = onChain.getBody().getOutputs().stream()
                .filter(output -> PoolFixtures.poolManagerAddress().equals(output.getAddress()))
                .toList();
        assertEquals(1, atPoolManagerAddress.size(),
                "exactly one output at the PoolManager spend address");
        TransactionOutput poolManagerOutput = atPoolManagerAddress.get(0);
        assertEquals(1, poolManagerOutput.getValue().getMultiAssets().size(),
                "exactly one policy beside lovelace: the PoolManager NFT");
        assertEquals(BigInteger.ONE,
                quantityOf(poolManagerOutput, REGISTRY.getPoolManagerPolicyId(), mintedName));
        assertEquals(HexUtil.encodeHexString(serialise(
                        PoolTxEncoder.poolManagerDatum(PoolFixtures.poolManagerDatum()))),
                poolManagerOutput.getInlineDatum().serializeToHex(),
                "the PoolManager output carries the factory PoolManagerDatum, unchanged through CBOR");

        // --- the reference inputs: config + published pool-policy ref script ---
        assertEquals(2, onChain.getBody().getReferenceInputs().size(),
                "two reference inputs: the config and the pool-policy reference script");
        assertTrue(onChain.getBody().getReferenceInputs()
                        .contains(new TransactionInput(RequestFixtures.TX_CONFIG, 0)),
                "the config reference input must be present");
    }

    /**
     * The two policies travel by different routes, and each route is asserted rather than assumed:
     * {@code pool.pool} by reference input with its witness copy stripped, and
     * {@code pool_manager.poolManager} in the witness set, because preview publishes no reference script
     * for it. The witness set therefore holds <b>exactly one</b> script, and it is the PoolManager's.
     * <p>
     * This is what keeps the create transaction free of Conway's {@code ExtraneousScriptWitnessesUTXOW}:
     * no script travels both ways. {@code LoanFactory}'s {@code LEDGER_PREFLIGHT} gate checks the same
     * property over the resolved universe; this checks it over the artefact.
     */
    @Test
    void thePoolPolicyTravelsByReferenceAndThePoolManagerPolicyInTheWitnessSet() {
        Transaction onChain = roundTrip(build());
        List<com.bloxbean.cardano.client.plutus.spec.PlutusScript> witnessed =
                onChain.getWitnessSet().getPlutusV3Scripts() == null ? List.of()
                        : List.copyOf(onChain.getWitnessSet().getPlutusV3Scripts());

        assertEquals(1, witnessed.size(),
                "only the PoolManager policy travels in the witness set");
        assertEquals(REGISTRY.getPoolManagerPolicyId(),
                HexUtil.encodeHexString(scriptHash(witnessed.get(0))),
                "the witnessed script must be pool_manager.poolManager, not the pool policy");
    }

    /**
     * <b>Inverse-polarity mutant.</b> The PoolManager mint redeemer's {@code poolWithdrawRedeemerIndex}
     * is set to a wild value, and the transaction must still evaluate <b>green</b>.
     * <p>
     * That is the only honest evidence for the claim {@link PoolCreateTransactionBuilder} makes about
     * the field: {@code pool_manager.ak}'s {@code check_mint} reads it only inside
     * {@code if length(poolManagerBurntNFTs) > 0}, and a creation burns nothing, so neither the
     * {@code safe_list_at} nor its {@code expect index >= 0} is ever evaluated. A refusal-based mutant
     * cannot prove inertness — an index nobody reads has no refusal to produce — so the assertion runs
     * the other way: break it as hard as possible and watch nothing happen. {@code 9999} would be out of
     * range for any redeemer list this transaction could have, so if the guard ever did open, this would
     * abort.
     */
    @Test
    void thePoolManagerMintRedeemersPoolWithdrawIndexIsUnreadOnCreate() {
        Transaction mutated = roundTrip(build());
        Redeemer poolManagerMint = mutated.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == RedeemerTag.Mint)
                .filter(r -> r.getIndex().intValueExact() == poolManagerMintIndex(mutated))
                .findFirst().orElseThrow(() -> new AssertionError("no PoolManager mint redeemer"));
        List<PlutusData> fields = ((ConstrPlutusData) poolManagerMint.getData()).getData()
                .getPlutusDataList();
        assertEquals(BigInteger.ZERO, ((BigIntPlutusData) fields.get(1)).getValue(),
                "the builder encodes 0 there — if that changed, this mutant is testing the wrong thing");
        fields.set(1, BigIntPlutusData.of(9999));

        Transaction reserialised = reserialise(mutated);
        assertNotEquals(HexUtil.encodeHexString(serialiseTx(build())),
                HexUtil.encodeHexString(serialiseTx(reserialised)),
                "the mutation must really change the transaction bytes");

        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(reserialised,
                resolvable(PoolFixtures.universe(FUNDER)), REGISTRY, POOL_POLICY);
        assertTrue(outcome.successful(),
                "poolWithdrawRedeemerIndex is unread on the create path, so a wild value must still "
                        + "evaluate green; got: " + outcome.detail());
    }

    /**
     * A structural pin, not chain arbitration: output 0 is the pool output, and its inline datum
     * survives a full CBOR round trip unchanged (the exact factory {@code PoolDatum}).
     */
    @Test
    void thePoolOutputIsOutputZeroAndItsInlineDatumRoundTrips() {
        Transaction onChain = roundTrip(build());
        TransactionOutput output0 = onChain.getBody().getOutputs().get(0);
        assertEquals(PoolFixtures.poolAddress(), output0.getAddress(), "output 0 is the pool output");

        String expected = HexUtil.encodeHexString(serialise(
                PoolTxEncoder.poolDatum(PoolFixtures.factoryPoolDatum(PoolFixtures.defaults()))));
        assertEquals(expected, output0.getInlineDatum().serializeToHex(),
                "the pool output carries the factory PoolDatum, unchanged through CBOR");
    }

    /**
     * The factory pool is born liquidatable at a collateral price of 0.5 lovelace per tFLDT unit, and
     * a healthier price is refused — a direct exercise of {@link PoolFixtures#assertBornLiquidatable}
     * so the property the later liquidation slices depend on is proven, not assumed.
     */
    @Test
    void theFactoryPoolIsBornLiquidatableAtTheDesignPrice() {
        PoolFixtures.assertBornLiquidatable(PoolFixtures.defaults(), 1, 2);
        assertThrows(AssertionError.class,
                () -> PoolFixtures.assertBornLiquidatable(PoolFixtures.defaults(), 1, 1),
                "at 1 lovelace/unit the borrower has equity and the loan is not born liquidatable");
    }

    // ======================================================================================
    // Falsifiability — six mutations, each of which must be refused by the Mint redeemer
    // ======================================================================================

    /** M1 — the redeemer names an {@code inputRef} the transaction does not spend. */
    @Test
    void anUnspentInputRefIsRejectedByThePoolPolicy() {
        TransactionInput unspent = new TransactionInput("cd".repeat(32), 3);
        assertFalse(roundTrip(build()).getBody().getInputs().contains(unspent),
                "the mutation's inputRef must really be absent from the inputs");

        String consistentName = "00" + RequestFixtures.hashOutputRef(unspent);
        Transaction mutated = builder().buildWith(request(),
                PoolCreateTransactionBuilder.Overrides.pool(unspent, consistentName,
                        PoolFixtures.poolAddress()));
        assertRefusedByTheMint(mutated, "an inputRef the transaction does not spend");
    }

    /** M2 — a 0x01 index prefix on the only minted token, hash and redeemer left correct. */
    @Test
    void aWrongIndexPrefixIsRejectedByThePoolPolicy() {
        String wrongName = "01" + RequestFixtures.hashOutputRef(PoolFixtures.seed());
        Transaction mutated = builder().buildWith(request(),
                PoolCreateTransactionBuilder.Overrides.pool(PoolFixtures.seed(), wrongName,
                        PoolFixtures.poolAddress()));
        assertRefusedByTheMint(mutated, "a 0x01 index prefix on the only minted token");
    }

    /** M3 — the token is named after {@code blake2b_224} of a different output reference. */
    @Test
    void namingTheTokenAfterADifferentOutputReferenceIsRejectedByThePoolPolicy() {
        TransactionInput other = new TransactionInput("ab".repeat(32), 9);
        String wrongName = "00" + RequestFixtures.hashOutputRef(other);
        assertFalse(wrongName.equals(PoolFixtures.poolAssetName()),
                "the mutation must really change the name, or it proves nothing");

        Transaction mutated = builder().buildWith(request(),
                PoolCreateTransactionBuilder.Overrides.pool(PoolFixtures.seed(), wrongName,
                        PoolFixtures.poolAddress()));
        assertRefusedByTheMint(mutated, "a token named after the wrong output reference");
    }

    /** M4 — the pool output is paid to the funder's own address, not the pool spend credential. */
    @Test
    void aPoolOutputPaidToTheWrongAddressIsRejectedByThePoolPolicy() {
        Transaction mutated = builder().buildWith(request(),
                PoolCreateTransactionBuilder.Overrides.pool(PoolFixtures.seed(),
                        PoolFixtures.poolAssetName(), FUNDER));
        assertRefusedByTheMint(mutated, "a pool output that is not at the pool spend credential");
    }

    /**
     * M5 — the pool output carries a second token of the pool policy, so
     * {@code dict.size(tokens(output.value, policy_id)) == 1} is false. Produced by byte-level surgery
     * on the honest build: a second pool-policy asset is added to the pool output. Nothing else about
     * the transaction changes, so the only broken conjunct is {@code outputHasUniqueToken}.
     */
    @Test
    void aSecondPoolPolicyTokenInThePoolOutputIsRejectedByThePoolPolicy() {
        Transaction mutated = roundTrip(build());
        TransactionOutput poolOutput = poolOutputOf(mutated);
        MultiAsset poolAssets = poolOutput.getValue().getMultiAssets().stream()
                .filter(ma -> ma.getPolicyId().equals(REGISTRY.getPoolPolicyId()))
                .findFirst().orElseThrow();
        poolAssets.getAssets().add(new Asset("0xdeadbeef", BigInteger.ONE));

        assertRefusedByTheMint(reserialise(mutated),
                "a pool output holding two tokens of the pool policy");
    }

    /**
     * M6 — the config reference input carries no config NFT, so {@code get_config_as_data_list}'s
     * {@code expect quantity_of(configNFT) > 0} aborts inside {@code check_mint}. Produced by handing
     * the builder — and the universe — a config UTxO with only ada at the config address.
     */
    @Test
    void aConfigReferenceInputWithoutTheConfigNftIsRejectedByThePoolPolicy() {
        Utxo configWithoutNft = LoanFixtures.utxo(RequestFixtures.TX_CONFIG, 0,
                LoanFixtures.entAddress(LoanFixtures.CONFIG_POLICY_ID),
                List.of(Amount.lovelace(BigInteger.valueOf(5_000_000L))),
                LoanFixtures.fixture("preview-config-datum.hex"));

        PoolCreateTransactionBuilder.Request request = new PoolCreateTransactionBuilder.Request(
                PoolFixtures.seedUtxo(FUNDER), configWithoutNft, PoolFixtures.poolPolicyRefScriptUtxo(),
                FUNDER, PoolFixtures.poolAddress(), PoolFixtures.poolAssetName(),
                PoolTxEncoder.poolDatum(PoolFixtures.factoryPoolDatum(PoolFixtures.defaults())),
                POOL_LOVELACE,
                PoolFixtures.poolManagerAddress(),
                PoolTxEncoder.poolManagerDatum(PoolFixtures.poolManagerDatum()),
                PoolFixtures.POOL_MANAGER_LOVELACE);

        List<Utxo> universe = new ArrayList<>(List.of(configWithoutNft,
                PoolFixtures.seedUtxo(FUNDER), PoolFixtures.poolPolicyRefScriptUtxo()));
        Transaction mutated = builder(universe).build(request);
        assertRefusedByTheMint(mutated, universe, "a config reference input with no config NFT");
    }

    /**
     * The other half of the unread claim: {@link PoolCreateTransactionBuilder}'s guard <b>refuses</b> a
     * body that does carry a {@code pool.pool} withdraw redeemer.
     * <p>
     * The test above proves the index is inert on every transaction the builder emits. That is exactly
     * why the guard's refusing branch is unreachable through {@code build()} — and an unreachable branch
     * is an unproven one: neutering its body would leave every other test in this class green. So the
     * guard is called here directly, on a body doctored to carry the withdrawal and redeemer a pool
     * creation never has. If a future change ever gives the create a {@code pool.pool} withdrawal, the
     * encoded {@code 0} stops being safe and the guard is what says so.
     */
    @Test
    void theUnreadClaimRefusesABodyCarryingAPoolWithdrawRedeemer() {
        Transaction honest = roundTrip(build());
        assertDoesNotThrow(() -> builder().assertPoolWithdrawRedeemerIsUnread(honest),
                "the honest create must pass its own guard");

        Transaction doctored = roundTrip(build());
        String poolPolicyRewardAddress = AddressProvider.getRewardAddress(
                Credential.fromScript(REGISTRY.getPoolPolicyId()), LoanFixtures.NETWORK).getAddress();
        doctored.getBody().setWithdrawals(new ArrayList<>(List.of(
                new Withdrawal(poolPolicyRewardAddress, BigInteger.ZERO))));
        doctored.getWitnessSet().getRedeemers().add(Redeemer.builder()
                .tag(RedeemerTag.Reward)
                .index(BigInteger.ZERO)
                .data(PoolTxEncoder.poolWithdrawRedeemer(0, PoolTxEncoder.ACTION_CANCEL))
                .exUnits(ExUnits.builder()
                        .mem(BigInteger.valueOf(10_000)).steps(BigInteger.valueOf(10_000)).build())
                .build());

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> builder().assertPoolWithdrawRedeemerIsUnread(doctored),
                "a create carrying a pool.pool withdraw redeemer must be refused");
        assertTrue(refusal.getMessage().contains("no longer unread"),
                "the refusal must name the claim it defends: " + refusal.getMessage());
        log.info("unread-claim guard refusal: {}", refusal.getMessage());
    }

    // ======================================================================================
    // Falsifiability — the PoolManager mint (T-024), refused by the PoolManager policy
    // ======================================================================================

    /**
     * M7 — the PoolManager NFT is minted under a name that is not the pool NFT's.
     * {@code pool_manager.ak:173} compares the two minted-token lists with {@code Pairs} equality, so
     * {@code poolManagerMintedNFTs == poolMintedNFTs} is false the moment the names differ. The pool
     * mint itself is untouched and still correct, so this isolates the PoolManager policy.
     * <p>
     * The name used is a well-formed 29-byte, {@code 0x00}-prefixed name — not obviously junk — so the
     * refusal is the equality failing, not a length or prefix check somewhere else.
     */
    @Test
    void aPoolManagerNftUnderTheWrongNameIsRejectedByThePoolManagerPolicy() {
        String wrongName = "00" + "8e".repeat(28);
        assertNotEquals(PoolFixtures.poolAssetName(), wrongName, "the mutation must change the name");
        Transaction mutated = builder().buildWith(request(),
                PoolCreateTransactionBuilder.Overrides.poolManagerAssetName(wrongName));
        assertRefusedByThePoolManagerMint(mutated, "a PoolManager NFT named differently from the pool NFT");
    }

    /**
     * M8 — the PoolManager output is paid to the funder's own address instead of
     * {@code Script(poolManagerSpendScriptHash)}. {@code check_mint}'s {@code correctAddress} conjunct
     * refuses it.
     * <p>
     * This is the mutation that falsifies the <b>stale comment</b> at {@code pool_manager.ak:166}
     * — <em>"we don't check the destination of the minted NFTs"</em>. The code at lines 121-127 does
     * check it, and this proves the code rather than the comment is what runs.
     */
    @Test
    void aPoolManagerOutputPaidToTheWrongAddressIsRejectedByThePoolManagerPolicy() {
        Transaction mutated = builder().buildWith(request(),
                PoolCreateTransactionBuilder.Overrides.poolManagerAddress(FUNDER));
        assertRefusedByThePoolManagerMint(mutated,
                "a PoolManager output that is not at the PoolManager spend credential");
    }

    /**
     * M9 — the PoolManager output's inline datum is not a {@code PoolManagerDatum}.
     * {@code check_mint} does {@code expect PoolManagerDatum { .. } = outputDatum}, which aborts on a
     * datum of the wrong shape.
     * <p>
     * The mutant uses a bare {@code Int}, which cannot be read as a two-field constructor under any
     * interpretation. It is the one datum check in this whole slice that the chain genuinely arbitrates:
     * {@code pool.pool}'s mint never reads the {@code PoolDatum} at all (consequence B above), so this
     * is the first pool-creation datum field that a dry-evaluation can defend.
     */
    @Test
    void anIllTypedPoolManagerDatumIsRejectedByThePoolManagerPolicy() {
        Transaction mutated = builder().buildWith(request(),
                PoolCreateTransactionBuilder.Overrides.poolManagerDatum(BigIntPlutusData.of(42)));
        assertRefusedByThePoolManagerMint(mutated,
                "a PoolManager output whose inline datum is not a PoolManagerDatum");
    }

    // ======================================================================================
    // plumbing
    // ======================================================================================

    /**
     * As {@link #assertRefusedByTheMint}, but naming the <b>PoolManager</b> policy's Mint redeemer.
     * Which index that is depends on where {@code poolManagerPolicyId} sorts among the minted policy
     * ids, so it is derived from the mutated body rather than written down — and it must be a
     * {@code RedeemerError} at that exact index, so a fault in the pool policy (index 0) or anywhere
     * else cannot satisfy the expectation.
     */
    private static void assertRefusedByThePoolManagerMint(Transaction mutated, String what) {
        int index = poolManagerMintIndex(roundTrip(mutated));
        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(mutated,
                resolvable(PoolFixtures.universe(FUNDER)), REGISTRY, POOL_POLICY);
        log.info("MUTATION [{}] refusal: {}", what, outcome.detail().replace("\n", " | "));
        assertFalse(outcome.successful(), "the PoolManager policy must reject " + what);
        String expected = "RedeemerError { tag: \"Mint\", index: " + index;
        assertTrue(outcome.detail().contains(expected),
                "expected [" + expected + "] rejecting " + what + ", got: " + outcome.detail());
    }

    /** Where the PoolManager policy sits among the canonically sorted policy ids of the mint field. */
    private static int poolManagerMintIndex(Transaction tx) {
        List<String> policies = tx.getBody().getMint().stream()
                .map(MultiAsset::getPolicyId)
                .map(String::toLowerCase)
                .sorted()
                .toList();
        int index = policies.indexOf(REGISTRY.getPoolManagerPolicyId().toLowerCase());
        if (index < 0) {
            throw new AssertionError("the mint field carries nothing under the PoolManager policy");
        }
        return index;
    }

    /** The one asset name minted under {@code policyId}, refusing anything but exactly one. */
    private static String soleMintedName(Transaction tx, String policyId) {
        List<Asset> assets = tx.getBody().getMint().stream()
                .filter(ma -> ma.getPolicyId().equalsIgnoreCase(policyId))
                .flatMap(ma -> ma.getAssets().stream())
                .toList();
        assertEquals(1, assets.size(), "exactly one asset name under " + policyId);
        assertEquals(BigInteger.ONE, assets.get(0).getValue(),
                "an NFT is minted, not burnt or doubled, under " + policyId);
        return strip(assets.get(0).getNameAsHex());
    }

    private static byte[] scriptHash(com.bloxbean.cardano.client.plutus.spec.PlutusScript script) {
        try {
            return script.getScriptHash();
        } catch (Exception e) {
            throw new AssertionError("cannot hash a witness-set script", e);
        }
    }

    private static byte[] serialiseTx(Transaction tx) {
        try {
            return tx.serialize();
        } catch (Exception e) {
            throw new AssertionError("cannot serialise the transaction", e);
        }
    }

    private static void assertRefusedByTheMint(Transaction mutated, String what) {
        assertRefusedByTheMint(mutated, PoolFixtures.universe(FUNDER), what);
    }

    private static void assertRefusedByTheMint(Transaction mutated, List<Utxo> universe, String what) {
        EvalFixtures.Outcome outcome =
                EvalFixtures.evaluateRaw(mutated, resolvable(universe), REGISTRY, POOL_POLICY);
        log.info("MUTATION [{}] refusal: {}", what, outcome.detail().replace("\n", " | "));
        assertFalse(outcome.successful(), "the pool policy must reject " + what);
        assertTrue(outcome.detail().contains("RedeemerError { tag: \"Mint\", index: 0"),
                "expected pool.pool's mint to be the rejecting script, got: " + outcome.detail());
    }

    /**
     * The <b>evaluation</b> universe: {@code universe} with the pool-policy reference-script UTxO
     * replaced by a copy that publishes {@code pool.pool}'s {@code reference_script_hash}, as the chain's
     * own does.
     *
     * <h2>Why {@link #POOL_POLICY} alone is not enough</h2>
     * The Aiken evaluator only consults its {@code ScriptSupplier} for a reference input that
     * <em>declares</em> it publishes a script; a UTxO with a null {@code referenceScriptHash} is just a
     * coordinate it reads and moves past. {@link PoolFixtures#poolPolicyRefScriptUtxo()} is a plain ada
     * UTxO — correct while the policy travelled in the witness set, and the reason its own javadoc gives
     * for being plain — so the extra script would never be asked for. The hash says <em>which</em> script
     * is reachable by reference; {@link #POOL_POLICY} supplies its bytes. Both halves are needed and
     * neither is sufficient.
     *
     * <h2>It does not perturb the transaction</h2>
     * This rewrites only the set handed to the evaluator, never the builder's {@code UtxoSupplier}: a
     * reference input contributes nothing but its coordinate to the body, and the coordinate is
     * unchanged, so every transaction under test is byte-for-byte what it was.
     * <p>
     * Refuses if the substitution matched nothing — a fixture rename would otherwise turn this into a
     * silent no-op and take all seven cases back to {@code RequiredRedeemersMismatch}.
     */
    private static List<Utxo> resolvable(List<Utxo> universe) {
        Utxo refScript = PoolFixtures.poolPolicyRefScriptUtxo();
        List<Utxo> resolved = new ArrayList<>();
        int substituted = 0;
        for (Utxo utxo : universe) {
            if (utxo.getTxHash().equals(refScript.getTxHash())
                    && utxo.getOutputIndex() == refScript.getOutputIndex()) {
                resolved.add(Utxo.builder()
                        .txHash(utxo.getTxHash())
                        .outputIndex(utxo.getOutputIndex())
                        .address(utxo.getAddress())
                        .amount(utxo.getAmount())
                        .inlineDatum(utxo.getInlineDatum())
                        .referenceScriptHash(REGISTRY.getPoolPolicyId())
                        .build());
                substituted++;
            } else {
                resolved.add(utxo);
            }
        }
        assertEquals(1, substituted,
                "the evaluation universe must carry exactly one pool-policy reference-script UTxO to "
                        + "make resolvable; found " + substituted);
        return resolved;
    }

    private static PoolCreateTransactionBuilder builder() {
        return builder(PoolFixtures.universe(FUNDER));
    }

    private static PoolCreateTransactionBuilder builder(List<Utxo> universe) {
        return new PoolCreateTransactionBuilder(REGISTRY,
                LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams());
    }

    private static PoolCreateTransactionBuilder.Request request() {
        return new PoolCreateTransactionBuilder.Request(
                PoolFixtures.seedUtxo(FUNDER),
                PoolFixtures.configUtxo(),
                PoolFixtures.poolPolicyRefScriptUtxo(),
                FUNDER,
                PoolFixtures.poolAddress(),
                PoolFixtures.poolAssetName(),
                PoolTxEncoder.poolDatum(PoolFixtures.factoryPoolDatum(PoolFixtures.defaults())),
                POOL_LOVELACE,
                PoolFixtures.poolManagerAddress(),
                PoolTxEncoder.poolManagerDatum(PoolFixtures.poolManagerDatum()),
                PoolFixtures.POOL_MANAGER_LOVELACE);
    }

    private static Transaction build() {
        return builder().build(request());
    }

    private static TransactionOutput poolOutputOf(Transaction tx) {
        return tx.getBody().getOutputs().stream()
                .filter(o -> PoolFixtures.poolAddress().equals(o.getAddress()))
                .findFirst().orElseThrow(() -> new AssertionError("no pool output"));
    }

    private static Transaction roundTrip(Transaction tx) {
        try {
            return Transaction.deserialize(tx.serialize());
        } catch (Exception e) {
            throw new AssertionError("cannot round-trip the built transaction", e);
        }
    }

    private static Transaction reserialise(Transaction tx) {
        try {
            return Transaction.deserialize(tx.serialize());
        } catch (Exception e) {
            throw new AssertionError("cannot re-serialise the mutated transaction", e);
        }
    }

    private static byte[] serialise(com.bloxbean.cardano.client.plutus.spec.PlutusData data) {
        try {
            return data.serializeToBytes();
        } catch (Exception e) {
            throw new AssertionError("cannot serialise the pool datum", e);
        }
    }

    private static BigInteger quantityOf(TransactionOutput output, String policyId, String assetNameHex) {
        for (MultiAsset multiAsset : output.getValue().getMultiAssets()) {
            if (!multiAsset.getPolicyId().equals(policyId)) {
                continue;
            }
            for (Asset asset : multiAsset.getAssets()) {
                if (strip(asset.getNameAsHex()).equalsIgnoreCase(assetNameHex)) {
                    return asset.getValue();
                }
            }
        }
        return BigInteger.ZERO;
    }

    private static String strip(String maybePrefixed) {
        return maybePrefixed.startsWith("0x") ? maybePrefixed.substring(2) : maybePrefixed;
    }
}
