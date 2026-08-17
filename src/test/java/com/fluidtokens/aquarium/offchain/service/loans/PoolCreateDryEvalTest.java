package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        List<EvaluationResult> results = EvalFixtures.evaluate(tx, universe, REGISTRY);

        assertEquals(1, results.size(), "exactly one script runs: the pool minting policy");
        assertEquals(RedeemerTag.Mint, results.get(0).getRedeemerTag());
        log.info("Pool create mint ex-units: mem={} steps={}",
                results.get(0).getExUnits().getMem(), results.get(0).getExUnits().getSteps());

        Transaction onChain = roundTrip(tx);

        // --- the mint field ---
        List<MultiAsset> mint = onChain.getBody().getMint();
        assertEquals(1, mint.size(), "one policy in the mint field");
        assertEquals(REGISTRY.getPoolPolicyId(), mint.get(0).getPolicyId());
        assertEquals(1, mint.get(0).getAssets().size(), "one asset name under the pool policy");
        Asset minted = mint.get(0).getAssets().get(0);
        assertEquals(BigInteger.ONE, minted.getValue(), "an NFT is minted, not burnt or doubled");
        String mintedName = strip(minted.getNameAsHex());
        assertEquals(PoolFixtures.poolAssetName(), mintedName);
        assertEquals(29, mintedName.length() / 2,
                "the pool NFT asset name is 29 bytes: 1-byte index prefix + 28-byte hash");
        assertEquals("00", mintedName.substring(0, 2), "the index prefix of the only minted token");

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

        // --- the reference inputs: config + published pool-policy ref script ---
        assertEquals(2, onChain.getBody().getReferenceInputs().size(),
                "two reference inputs: the config and the pool-policy reference script");
        assertTrue(onChain.getBody().getReferenceInputs()
                        .contains(new TransactionInput(RequestFixtures.TX_CONFIG, 0)),
                "the config reference input must be present");
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
        Transaction mutated = builder().buildWith(request(), unspent, consistentName,
                PoolFixtures.poolAddress());
        assertRefusedByTheMint(mutated, "an inputRef the transaction does not spend");
    }

    /** M2 — a 0x01 index prefix on the only minted token, hash and redeemer left correct. */
    @Test
    void aWrongIndexPrefixIsRejectedByThePoolPolicy() {
        String wrongName = "01" + RequestFixtures.hashOutputRef(PoolFixtures.seed());
        Transaction mutated = builder().buildWith(request(), PoolFixtures.seed(), wrongName,
                PoolFixtures.poolAddress());
        assertRefusedByTheMint(mutated, "a 0x01 index prefix on the only minted token");
    }

    /** M3 — the token is named after {@code blake2b_224} of a different output reference. */
    @Test
    void namingTheTokenAfterADifferentOutputReferenceIsRejectedByThePoolPolicy() {
        TransactionInput other = new TransactionInput("ab".repeat(32), 9);
        String wrongName = "00" + RequestFixtures.hashOutputRef(other);
        assertFalse(wrongName.equals(PoolFixtures.poolAssetName()),
                "the mutation must really change the name, or it proves nothing");

        Transaction mutated = builder().buildWith(request(), PoolFixtures.seed(), wrongName,
                PoolFixtures.poolAddress());
        assertRefusedByTheMint(mutated, "a token named after the wrong output reference");
    }

    /** M4 — the pool output is paid to the funder's own address, not the pool spend credential. */
    @Test
    void aPoolOutputPaidToTheWrongAddressIsRejectedByThePoolPolicy() {
        Transaction mutated = builder().buildWith(request(), PoolFixtures.seed(),
                PoolFixtures.poolAssetName(), FUNDER);
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
                POOL_LOVELACE);

        List<Utxo> universe = new ArrayList<>(List.of(configWithoutNft,
                PoolFixtures.seedUtxo(FUNDER), PoolFixtures.poolPolicyRefScriptUtxo()));
        Transaction mutated = builder(universe).build(request);
        assertRefusedByTheMint(mutated, universe, "a config reference input with no config NFT");
    }

    // ======================================================================================
    // plumbing
    // ======================================================================================

    private static void assertRefusedByTheMint(Transaction mutated, String what) {
        assertRefusedByTheMint(mutated, PoolFixtures.universe(FUNDER), what);
    }

    private static void assertRefusedByTheMint(Transaction mutated, List<Utxo> universe, String what) {
        EvalFixtures.Outcome outcome = EvalFixtures.evaluateRaw(mutated, universe, REGISTRY);
        log.info("MUTATION [{}] refusal: {}", what, outcome.detail().replace("\n", " | "));
        assertFalse(outcome.successful(), "the pool policy must reject " + what);
        assertTrue(outcome.detail().contains("RedeemerError { tag: \"Mint\", index: 0"),
                "expected pool.pool's mint to be the rejecting script, got: " + outcome.detail());
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
                POOL_LOVELACE);
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
