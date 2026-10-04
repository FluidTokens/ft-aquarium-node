package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.config.HashCheckedScriptSupplier;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-134 B3b-4: compounds built through {@link CompoundTransactionBuilder}'s <b>PRODUCTION
 * constructor</b> — the one {@code YaciConfig} calls — with only its LEAVES supplied by the rig: the UTxO
 * set, the protocol parameters, the script bytes ({@link HashCheckedScriptSupplier} over
 * {@link EvalFixtures#scriptSupplier}) and the evaluator (Aiken). Every other compound rig builds through
 * the OFFLINE constructor, which is handed a no-op script supplier and so cannot see what production wires.
 *
 * <h2>The fixture</h2>
 * The recorded preview candidate {@code compound-candidate-e833a769.json} (escrow, bond, pool, pool
 * manager) with configs synthesised from the same registry, exactly as {@code CompoundDryEvalTest} and
 * {@code CompoundReferenceScriptTest} do. What is referenced is <b>what mainnet references</b>: the
 * shipped {@code loans.compound.reference-scripts} default publishes ELEVEN coordinates, one per
 * validator a compound runs (asserted below against {@code application.yaml}), so every validator
 * travels by reference and the witness set carries no script at all. Compound reads no oracle, so there
 * is no third-party script to reference: the referenced set is asserted to be exactly the registry's
 * eleven on this build — and a build carrying a stale twelfth is checked separately.
 *
 * <h2>Why the fee is checked against the ledger's own formula</h2>
 * Production declares no reference-script list (FAB-134 B3b-4, Amendment r2): a declared list is priced
 * INSTEAD of asking the supplier ({@code FeeCalculators:125-145}), and it can only name registry
 * validators. So the injected supplier is what prices every referenced script, and the floor is computed
 * from the DESERIALISED body —
 * {@code minFeeA·size + minFeeB + ⌈prices·exUnits⌉ + tierRefScriptFee(referenced bytes)} — and the
 * slack above it is required to be smaller than any single script's marginal share, so one unpriced
 * script cannot hide inside it.
 */
@Slf4j
class CompoundProductionWiringTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.previewDeploymentRegistry();
    private static final String LOAN_ID = "e833a769ea3a480343175e253eab799ec0b058c99de30cc17160dc37";
    private static final String POOL_ID = "00d3513725536642b6fe985ce9ec87d1ebb880497d92e0a8495bc6d0bf";
    private static final BigInteger ESCROW = BigInteger.valueOf(29_109_268L);
    private static final String BOT = LoanFixtures.botAddress();
    private static final long VALID_FROM_SLOT = 70_000_000L;
    private static final long VALID_TO_SLOT = 70_000_300L;

    /**
     * The eleven validators a compound runs — four {@code general_spend} spends and seven withdraw-0
     * invocations — in the order {@code CompoundTransactionBuilder.referencedScripts} enumerates them.
     */
    private static final List<PlutusScript> COMPOUND_SCRIPTS = List.of(REGISTRY.getAssetManagerSpendScript(),
            REGISTRY.getLenderManagerSpendScript(), REGISTRY.getPoolSpendScript(),
            REGISTRY.getPoolManagerSpendScript(), REGISTRY.getAssetManagerScript(),
            REGISTRY.getLenderManagerScript(), REGISTRY.getLmCompoundActionScript(),
            REGISTRY.getPoolScript(), REGISTRY.getPoolCompoundActionScript(),
            REGISTRY.getPoolManagerScript(), REGISTRY.getPmCompoundLiquidityScript());

    /** CCL's placeholder mem (CCL trap 8): a redeemer still carrying it was never costed. */
    private static final BigInteger PLACEHOLDER_MEM = BigInteger.valueOf(10_000);

    // ======================================================================================

    /**
     * Ex-units off the deserialised body equal the evaluator's; no script both witnessed and referenced;
     * every reference input that publishes a script names one of the declared eleven; assertStructure
     * holds on the deserialised body.
     */
    @Test
    void theProductionConstructorShipsTheEvaluatedExUnitsAndNoDuplicateScript() throws Exception {
        Fixture f = fixture(wallet(60_000_000L), null);
        Recording evaluator = new Recording(aiken(f.universe));
        CompoundTransactionBuilder builder = production(f.universe, new HashCheckedScriptSupplier(rigScripts()),
                evaluator);
        Transaction built = builder.build(f.request);

        assertEquals(1, evaluator.calls.get(), "exactly one evaluation per compound");
        Transaction reread = Transaction.deserialize(built.serialize());
        List<Redeemer> redeemers = reread.getWitnessSet().getRedeemers();
        assertEquals(11, redeemers.size(), "four general_spend spends and seven withdraw-0 invocations");
        for (Redeemer redeemer : redeemers) {
            String key = redeemer.getTag() + "#" + redeemer.getIndex();
            EvaluationResult costing = evaluator.last.get().stream()
                    .filter(r -> r.getRedeemerTag() == redeemer.getTag()
                            && r.getIndex() == redeemer.getIndex().intValue())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no evaluation for " + key));
            assertEquals(costing.getExUnits().getMem(), redeemer.getExUnits().getMem(),
                    "declared mem for " + key + " is not the evaluated one");
            assertEquals(costing.getExUnits().getSteps(), redeemer.getExUnits().getSteps(),
                    "declared steps for " + key + " is not the evaluated one");
            assertTrue(redeemer.getExUnits().getMem().compareTo(PLACEHOLDER_MEM) > 0,
                    key + " carries CCL's 10000-mem placeholder: the evaluator was not load-bearing");
        }

        // The post-assert ran inside the build; it is re-run here on the DESERIALISED body.
        List<String> order = builder.withdrawalOrder();
        builder.assertStructure(reread, f.request, order,
                CompoundTransactionBuilder.rewardRedeemerIndex(order, REGISTRY.getPoolPolicyId()),
                CompoundTransactionBuilder.rewardRedeemerIndex(order, REGISTRY.getLenderManagerWithdrawScriptHash()));

        // Completeness of the declared list: every reference input whose UTxO publishes a script names a
        // validator the builder DECLARES (CCL prices the declared list and nothing else).
        Set<String> referenced = referencedScriptHashes(reread, f.universe);
        assertEquals(compoundHashes(), referenced,
                "the reference inputs must publish exactly the eleven compound validators — a script outside "
                        + "the declared list is priced at ZERO by cardano-client-lib (FeeTooSmallUTxO)");
        assertEquals(f.request.referenceScripts().keySet(), referenced,
                "every requested reference script reaches the body, and nothing else publishing a script does");

        Set<String> witnessed = witnessedScriptHashes(reread.getWitnessSet());
        Set<String> both = new HashSet<>(witnessed);
        both.retainAll(referenced);
        assertTrue(both.isEmpty(),
                "scripts both witnessed and referenced (ExtraneousScriptWitnessesUTXOW): " + both);
        assertTrue(witnessed.isEmpty(), "every validator travels by reference on mainnet, so the witness set "
                + "carries no script; it carries " + witnessed);
    }

    /**
     * ⛔ The rig references what MAINNET references: the shipped {@code loans.compound.reference-scripts}
     * default names eleven distinct coordinates, and {@code application.yaml} records them as one per
     * compound validator (each verified against the chain, MainnetReferenceScriptsTest). Eleven validators,
     * eleven coordinates — so this rig's eleven references are the production shape, not a choice.
     */
    @Test
    void theRigReferencesExactlyWhatTheShippedMainnetDefaultReferences() throws Exception {
        String yaml;
        try (InputStream is = CompoundProductionWiringTest.class.getResourceAsStream("/application.yaml")) {
            yaml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("\\$\\{AQUARIUM_COMPOUND_REFERENCE_SCRIPTS:([^}]*)}").matcher(yaml);
        assertTrue(m.find(), "application.yaml carries no compound reference-scripts default");
        List<String> coordinates = Arrays.stream(m.group(1).split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
        assertEquals(11, coordinates.size(), "the shipped mainnet compound default: " + coordinates);
        assertEquals(11, new HashSet<>(coordinates).size(), "duplicate coordinates in " + coordinates);
        assertEquals(11, compoundHashes().size(), "eleven distinct compound validators");
        Set<String> registryReach = new HashSet<>(CompoundTransactionBuilder.withdrawCredentials(REGISTRY));
        registryReach.addAll(List.of(REGISTRY.getAssetManagerSpendScriptHash(),
                REGISTRY.getLenderManagerSpendScriptHash(), REGISTRY.getPoolSpendScriptHash(),
                REGISTRY.getPoolManagerSpendScriptHash()));
        assertEquals(registryReach, compoundHashes(),
                "the eleven scripts are the four spends plus the seven withdraw credentials");
        assertEquals(compoundHashes(), fixture(wallet(60_000_000L), null).request.referenceScripts().keySet());
    }

    /**
     * Every referenced script is priced, checked against the LEDGER's formula off the deserialised body,
     * with the Conway tier ladder applied. The slack must be below every script's marginal share, so a
     * single unpriced script (a gap in the declared list) cannot pass.
     */
    @Test
    void theFeeCoversTheLedgerFloorForEveryReferencedScript() throws Exception {
        Fixture f = fixture(wallet(60_000_000L), null);
        Transaction reread = Transaction.deserialize(production(f.universe,
                new HashCheckedScriptSupplier(rigScripts()), new Recording(aiken(f.universe)))
                .build(f.request).serialize());

        Map<String, Long> bytesByHash = new LinkedHashMap<>();
        for (String hash : referencedScriptHashes(reread, f.universe)) {
            PlutusScript script = rigScripts().getScript(hash)
                    .orElseThrow(() -> new AssertionError("the rig cannot serve " + hash));
            bytesByHash.put(hash, (long) script.serializeScriptBody().length);
        }
        long total = bytesByHash.values().stream().mapToLong(Long::longValue).sum();
        BigInteger refFee = refScriptFee(total);
        BigInteger floor = baseAndScriptFee(reread).add(refFee);
        BigInteger fee = reread.getBody().getFee();
        BigInteger slack = fee.subtract(floor);

        BigInteger smallestShare = null;
        String smallest = null;
        for (var e : bytesByHash.entrySet()) {
            BigInteger share = refFee.subtract(refScriptFee(total - e.getValue()));
            if (smallestShare == null || share.compareTo(smallestShare) < 0) {
                smallestShare = share;
                smallest = e.getKey();
            }
        }
        log.info("FAB-134 B3b-4 compound ref-script fee: fee {} floor {} slack {} | {} scripts, {} B "
                        + "(tier ladder crossed: {}), ref-script fee {}, smallest share {} ({})",
                fee, floor, slack, bytesByHash.size(), total, total >= 25_600L, refFee, smallestShare, smallest);

        assertEquals(11, bytesByHash.size(), "all eleven compound validators travel by reference");
        assertTrue(fee.compareTo(floor) >= 0, "the fee " + fee + " is below the ledger floor " + floor
                + " (reference-script fee " + refFee + " for " + total + " B): a referenced script went "
                + "unpriced, which the ledger rejects as FeeTooSmallUTxO (CCL trap 9)");
        assertTrue(slack.compareTo(smallestShare) < 0, "the slack above the floor (" + slack + ") is not below "
                + "the smallest script's share (" + smallestShare + "), so an unpriced script could hide in it");
    }

    /**
     * ⛔ The stale coordinate. {@code CompoundExecutor} references any configured coordinate that publishes
     * a script, so an operator override still naming a superseded validator after a redeploy adds a
     * reference input whose script no registry validator names. Beside the eleven, a declared registry
     * list priced it at ZERO — measured 32,716 lovelace under this floor, FeeTooSmallUTxO. With nothing
     * declared, the supplier prices all twelve.
     */
    @Test
    void aStaleCoordinateBesideTheElevenIsPricedThroughTheSupplier() throws Exception {
        PlutusScript foreign = REGISTRY.getLoanScript();
        String foreignHash = HexUtil.encodeHexString(foreign.getScriptHash());
        assertFalse(compoundHashes().contains(foreignHash), "the foreign script must not be a compound one");
        Utxo wallet = wallet(60_000_000L);
        Fixture eleven = fixture(wallet, null);
        List<Utxo> universe = new ArrayList<>(eleven.universe());
        Utxo published = Utxo.builder().txHash("f0".repeat(32)).outputIndex(0)
                .address(LoanFixtures.entAddress(foreignHash))
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                .referenceScriptHash(foreignHash).build();
        universe.add(published);
        Map<String, TransactionInput> refs = new LinkedHashMap<>(eleven.request().referenceScripts());
        refs.put(foreignHash, new TransactionInput(published.getTxHash(), 0));
        var request = request(refs, wallet);

        // Not Aiken: it rejects a reference script no redeemer needs (RequiredRedeemersMismatch, extra),
        // which the ledger does not. This test is about PRICING, so the evaluator only has to return
        // non-placeholder ex-units for every redeemer of the body it is handed.
        TransactionEvaluator fixedCost = (cbor, inputs) -> {
            try {
                return Result.<List<EvaluationResult>>success("fixed").withValue(Transaction.deserialize(cbor)
                        .getWitnessSet().getRedeemers().stream()
                        .map(r -> EvaluationResult.builder().redeemerTag(r.getTag()).index(r.getIndex().intValue())
                                .exUnits(com.bloxbean.cardano.client.plutus.spec.ExUnits.builder()
                                        .mem(BigInteger.valueOf(500_000)).steps(BigInteger.valueOf(200_000_000))
                                        .build()).build())
                        .toList());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        Transaction reread = Transaction.deserialize(production(universe, new HashCheckedScriptSupplier(rigScripts()),
                fixedCost).build(request).serialize());

        Set<String> referenced = referencedScriptHashes(reread, universe);
        Set<String> twelve = compoundHashes();
        twelve.add(foreignHash);
        assertEquals(twelve, referenced, "the eleven and the stale twelfth all travel as reference inputs");
        long bytes = 0;
        for (String hash : referenced) {
            bytes += rigScripts().getScript(hash).orElseThrow().serializeScriptBody().length;
        }
        long foreignBytes = foreign.serializeScriptBody().length;
        BigInteger floor = baseAndScriptFee(reread).add(refScriptFee(bytes));
        BigInteger foreignShare = refScriptFee(bytes).subtract(refScriptFee(bytes - foreignBytes));
        BigInteger slack = reread.getBody().getFee().subtract(floor);
        log.info("FAB-134 B3b-4 compound stale coordinate: fee {} floor {} slack {} | 12 scripts {} B (tier ladder "
                        + "crossed: {}), stale script {} B, share {}", reread.getBody().getFee(), floor, slack,
                bytes, bytes >= 25_600L, foreignBytes, foreignShare);
        assertTrue(slack.signum() >= 0, "the fee " + reread.getBody().getFee() + " is below the ledger floor "
                + floor + ": a referenced script went unpriced — the stale script's share is " + foreignShare
                + " (CCL trap 9: a declared list prices only what it declares)");
        assertTrue(slack.compareTo(foreignShare) < 0, "the slack " + slack + " would hide the stale script's "
                + "share " + foreignShare);
    }

    /** A failed evaluation on the production path refuses the build, never placeholder ex-units. */
    @Test
    void anEvaluatorErrorRefusesTheBuildOnTheProductionPath() throws Exception {
        Fixture f = fixture(wallet(60_000_000L), null);
        TransactionEvaluator failing = (cbor, inputs) -> Result.error("ScriptFailures: {FAB-134-6d marker}");
        Exception refused = assertThrows(Exception.class,
                () -> production(f.universe, new HashCheckedScriptSupplier(rigScripts()), failing).build(f.request),
                "a failed evaluation on the production path must refuse, not ship placeholder ex-units");
        String chain = Stream.iterate((Throwable) refused, t -> t != null, Throwable::getCause)
                .map(String::valueOf).collect(Collectors.joining(" <- "));
        assertTrue(chain.contains("FAB-134-6d marker"), "refused for another reason: " + chain);
    }

    /**
     * CCL trap 9b, the SELECTOR seam. The bot address holds the eleven published compound reference
     * scripts (ada-only, 20 ADA each) beside ONE short wallet utxo. When the wallet cannot cover the build,
     * cardano-client-lib's ChangeOutputAdjustments tops the change up through the context's UtxoSelector,
     * which checks no reference script — so without the selector installed in {@code preBalanceTx} it
     * consumes a published script. For every wallet value the build either refuses or spends none.
     */
    @Test
    void aShortWalletNeverSpendsAPublishedReferenceScriptHeldAtTheBotAddress() throws Exception {
        long[] walletLovelace = {1_000_000L, 1_500_000L, 2_000_000L, 2_500_000L, 3_000_000L, 3_500_000L,
                4_000_000L, 5_000_000L, 6_000_000L, 8_000_000L, 12_000_000L};
        int built = 0;
        List<String> outcomes = new ArrayList<>();
        for (long lovelace : walletLovelace) {
            Fixture f = fixture(wallet(lovelace), BOT);
            Set<TransactionInput> published = new HashSet<>();
            f.universe.stream().filter(u -> u.getReferenceScriptHash() != null && BOT.equals(u.getAddress()))
                    .forEach(u -> published.add(new TransactionInput(u.getTxHash(), u.getOutputIndex())));
            assertEquals(11, published.size(), "the eleven compound reference scripts sit at the bot address");

            Transaction transaction;
            try {
                transaction = production(f.universe, new HashCheckedScriptSupplier(rigScripts()),
                        new Recording(aiken(f.universe))).build(f.request);
            } catch (RuntimeException refused) {
                outcomes.add(lovelace + ": refused " + refused.getClass().getSimpleName());
                continue;
            }
            Transaction reread = Transaction.deserialize(transaction.serialize());
            List<TransactionInput> spent = new ArrayList<>(reread.getBody().getInputs());
            if (reread.getBody().getCollateral() != null) {
                spent.addAll(reread.getBody().getCollateral());
            }
            for (TransactionInput input : spent) {
                assertFalse(published.contains(input), "wallet " + lovelace + " lovelace: the build spends "
                        + "the published reference-script utxo " + input.getTransactionId() + "#"
                        + input.getIndex() + " (CCL trap 9b — the selector seam is unguarded)");
            }
            built++;
            outcomes.add(lovelace + ": built, " + reread.getBody().getInputs().size() + " inputs");
        }
        log.info("FAB-134 B3b-4 compound short-wallet sweep: {}", outcomes);
        assertTrue(built > 0, "no wallet value built at all, so the sweep proves nothing: " + outcomes);
    }

    /** The production constructor has no null-evaluator form (CCL trap 8). */
    @Test
    void theProductionConstructorRefusesANullEvaluator() {
        NullPointerException npe = assertThrows(NullPointerException.class,
                () -> production(List.of(), new HashCheckedScriptSupplier(rigScripts()), null));
        assertTrue(npe.getMessage() != null && npe.getMessage().contains("trap 8"),
                "the refusal must say why: " + npe.getMessage());
    }

    // ======================================================================================
    // The rig's leaves
    // ======================================================================================

    /** The production constructor, exactly the one YaciConfig calls. */
    private static CompoundTransactionBuilder production(List<Utxo> universe, ScriptSupplier scripts,
                                                         TransactionEvaluator evaluator) {
        return new CompoundTransactionBuilder(REGISTRY, Networks.preview(), LoanFixtures.utxoSupplier(universe),
                EvalFixtures.protocolParams(), scripts, evaluator);
    }

    /** The provider leaf: every applied compound validator (and the rig's usual set), served by hash. */
    private static ScriptSupplier rigScripts() {
        return EvalFixtures.scriptSupplier(REGISTRY, COMPOUND_SCRIPTS);
    }

    private static AikenTransactionEvaluator aiken(List<Utxo> universe) {
        return new AikenTransactionEvaluator(LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(),
                rigScripts(), SlotConfigs.preview());
    }

    /** {@code minFeeA·size + minFeeB + ⌈priceMem·Σmem + priceStep·Σsteps⌉} for the given body. */
    private static BigInteger baseAndScriptFee(Transaction reread) throws Exception {
        ProtocolParams pp = EvalFixtures.protocolParams().getProtocolParams();
        long size = reread.serialize().length;
        BigInteger base = BigInteger.valueOf(pp.getMinFeeA()).multiply(BigInteger.valueOf(size))
                .add(BigInteger.valueOf(pp.getMinFeeB()));
        BigDecimal scripts = BigDecimal.ZERO;
        for (Redeemer r : reread.getWitnessSet().getRedeemers()) {
            scripts = scripts.add(pp.getPriceMem().multiply(new BigDecimal(r.getExUnits().getMem())))
                    .add(pp.getPriceStep().multiply(new BigDecimal(r.getExUnits().getSteps())));
        }
        return base.add(scripts.setScale(0, RoundingMode.CEILING).toBigIntegerExact());
    }

    /**
     * The Conway reference-script fee for {@code bytes}: {@code minFeeRefScriptCostPerByte} for the first
     * 25,600 bytes, then x1.2 per further 25,600-byte tier (the ledger's {@code tierRefScriptFee}, floored).
     */
    private static BigInteger refScriptFee(long bytes) {
        final long tier = 25_600L;
        BigDecimal price = EvalFixtures.protocolParams().getProtocolParams().getMinFeeRefScriptCostPerByte();
        BigDecimal fee = BigDecimal.ZERO;
        long remaining = bytes;
        while (remaining >= tier) {
            fee = fee.add(price.multiply(BigDecimal.valueOf(tier)));
            price = price.multiply(new BigDecimal("1.2"));
            remaining -= tier;
        }
        fee = fee.add(price.multiply(BigDecimal.valueOf(remaining)));
        return fee.setScale(0, RoundingMode.FLOOR).toBigIntegerExact();
    }

    private static Set<String> compoundHashes() {
        return COMPOUND_SCRIPTS.stream().map(CompoundProductionWiringTest::hashOf)
                .collect(Collectors.toCollection(HashSet::new));
    }

    private static Set<String> referencedScriptHashes(Transaction reread, List<Utxo> universe) {
        Map<TransactionInput, Utxo> byRef = new HashMap<>();
        universe.forEach(u -> byRef.put(new TransactionInput(u.getTxHash(), u.getOutputIndex()), u));
        Set<String> referenced = new HashSet<>();
        for (TransactionInput input : reread.getBody().getReferenceInputs()) {
            Utxo utxo = byRef.get(input);
            if (utxo != null && utxo.getReferenceScriptHash() != null) {
                referenced.add(utxo.getReferenceScriptHash());
            }
        }
        return referenced;
    }

    /** Records how often it was asked and what it last returned. */
    private static final class Recording implements TransactionEvaluator {
        final TransactionEvaluator delegate;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<List<EvaluationResult>> last = new AtomicReference<>();

        Recording(TransactionEvaluator delegate) {
            this.delegate = delegate;
        }

        @Override
        public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos)
                throws com.bloxbean.cardano.client.api.exception.ApiException {
            calls.incrementAndGet();
            Result<List<EvaluationResult>> result = delegate.evaluateTx(cbor, inputUtxos);
            last.set(result.getValue());
            return result;
        }
    }

    private record Fixture(List<Utxo> universe, CompoundTransactionBuilder.Request request) {
    }

    /**
     * @param referenceScriptAddress where the eleven reference-script UTxOs sit; {@code null} puts each at
     *                               its own script's enterprise address
     */
    private static Fixture fixture(Utxo wallet, String referenceScriptAddress) {
        List<Utxo> universe = new ArrayList<>(baseUniverse(wallet));
        Map<String, TransactionInput> refs = new LinkedHashMap<>();
        for (int i = 0; i < COMPOUND_SCRIPTS.size(); i++) {
            String hash = hashOf(COMPOUND_SCRIPTS.get(i));
            TransactionInput at = new TransactionInput(String.format("%02x", 0xa1 + i).repeat(32), 0);
            refs.put(hash, at);
            universe.add(Utxo.builder().txHash(at.getTransactionId()).outputIndex(at.getIndex())
                    .address(referenceScriptAddress != null ? referenceScriptAddress : LoanFixtures.entAddress(hash))
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                    .referenceScriptHash(hash).build());
        }
        return new Fixture(universe, request(refs, wallet));
    }

    private static List<Utxo> baseUniverse(Utxo wallet) {
        return List.of(utxo("escrow"), utxo("bond"), utxo("pool"), utxo("poolManager"), config(), lmConfig(),
                wallet);
    }

    private static CompoundTransactionBuilder.Request request(Map<String, TransactionInput> refs, Utxo wallet) {
        return new CompoundTransactionBuilder.Request(candidate(), refs, utxo("bond"), config(), lmConfig(),
                wallet, BOT, BigInteger.ZERO, VALID_FROM_SLOT, VALID_TO_SLOT);
    }

    /** The bot's own funds — ada-only, so it can serve as collateral. */
    private static Utxo wallet(long lovelace) {
        return Utxo.builder().txHash("9e".repeat(32)).outputIndex(0).address(BOT)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(lovelace)))).build();
    }

    private static Utxo utxo(String key) {
        try (InputStream is = CompoundProductionWiringTest.class
                .getResourceAsStream("/loans-v4/compound-candidate-e833a769.json")) {
            JsonNode n = new ObjectMapper().readTree(is).get(key);
            List<Amount> amounts = new ArrayList<>();
            n.get("amount").forEach(a -> amounts.add(Amount.builder()
                    .unit(a.get("unit").asText())
                    .quantity(new BigInteger(a.get("quantity").asText())).build()));
            return Utxo.builder().txHash(n.get("txHash").asText())
                    .outputIndex(n.get("outputIndex").asInt())
                    .address(n.get("address").asText()).amount(amounts)
                    .inlineDatum(n.get("inlineDatum").isNull() ? null : n.get("inlineDatum").asText())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Synthesised from the same registry the builder uses — see CompoundDryEvalTest#recordedConfig. */
    private static Utxo config() {
        Utxo captured = utxo("config");
        return LoanFixtures.syntheticConfigUtxoFor(REGISTRY, captured.getTxHash(), captured.getOutputIndex());
    }

    private static Utxo lmConfig() {
        Utxo captured = utxo("lmConfig");
        return LoanFixtures.syntheticLmConfigUtxoFor(REGISTRY, captured.getTxHash(), captured.getOutputIndex());
    }

    private static CompoundCandidate candidate() {
        Utxo b = utxo("bond");
        return new CompoundCandidate(LOAN_ID, utxo("escrow"),
                new AssetManagerDatumConverter().deserialize(utxo("escrow").getInlineDatum()),
                ESCROW, new LenderBond(b.getTxHash(), b.getOutputIndex(), b.getAddress(), LOAN_ID,
                        b.getInlineDatum(), new LenderManagerDatumConverter().deserialize(b.getInlineDatum())),
                POOL_ID, utxo("pool"), utxo("poolManager"), 0L, true, null, "recorded");
    }

    private static Set<String> witnessedScriptHashes(TransactionWitnessSet witnesses) {
        Set<String> hashes = new HashSet<>();
        Stream.of(witnesses.getPlutusV1Scripts(), witnesses.getPlutusV2Scripts(), witnesses.getPlutusV3Scripts())
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream)
                .forEach(script -> hashes.add(hashOf(script)));
        return hashes;
    }

    private static String hashOf(PlutusScript script) {
        try {
            return HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new AssertionError("cannot hash a script", e);
        }
    }
}
