package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.MinAdaCalculator;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.HashCheckedScriptSupplier;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-167: the compound path, end to end, against <b>what mainnet runs since 2026-10-26</b>.
 *
 * <p>FluidTokens' {@code lm_compound_action} {@code 4713a925…} (upstream {@code a02d4e2}, vendored by
 * FAB-166) computes the pool owner's fee with {@code finance.get_compounding_fee}: an ADA principal pays
 * {@code max(Q*c/1000, 1_000_000)}, any other principal {@code Q*c/1000}. The pool output must equal the
 * pool input plus {@code Q - fee} exactly. Every compound rig before this class derived from
 * {@code loans-v4-third-deployment.plutus.json} (preview), so none of them ran that script.
 *
 * <h2>What is real here, and what is the rig's</h2>
 * <ul>
 *   <li><b>Production code, end to end:</b> the real {@link CompoundCandidateScanner} classification and
 *       admission, the real {@link CompoundEconomics}, the real {@link CompoundExecutor} cycle (fee,
 *       reference-script resolution, funding, pre-sign recheck, signing), and
 *       {@link CompoundTransactionBuilder}'s PRODUCTION constructor with a {@link HashCheckedScriptSupplier}
 *       and a real evaluator. No fee is computed by this class: whatever the node computes is what is
 *       built, evaluated and signed.</li>
 *   <li><b>Mainnet:</b> the SHIPPED artefact ({@code loans-v4.plutus.json}) under the mainnet coordinates
 *       read from {@code application.yaml}; the live ConfigDatum / LMConfigDatum captures of 2026-10-23/26;
 *       the eleven shipped reference coordinates, so all eleven validators travel by reference; the
 *       {@code lm_compound_action} bytes served from {@code mainnet-compound-script-2026-10-26.hex}, the
 *       5,246-byte reference script at {@code 588695…#0}; mainnet addresses and slot configuration.</li>
 *   <li><b>The rig's:</b> the escrow / lender bond / pool / pool manager are the recorded preview candidate
 *       {@code compound-candidate-e833a769.json} re-targeted onto the mainnet credentials (a same-length
 *       substitution of each deployment hash, each asserted to occur exactly once), with the escrow amount,
 *       the fee rate and — for the native case — the principal varied. Nothing on mainnet has been
 *       compounded yet, so there is no recorded mainnet candidate to replay. The evaluator is Aiken with
 *       cardano-client-lib's bundled PlutusV3 cost model (CCL trap 7: that model can lag the chain's).
 *       Protocol parameters are {@link LoanFixtures#protocolParams()}: the real 16,384-byte
 *       {@code maxTxSize}.</li>
 * </ul>
 *
 * <h2>Fail-first (FAB-167 task 1)</h2>
 * On the base commit {@code 8829a68}, where the node still computed {@code Q*c/1000} for every principal,
 * {@link #adaZeroRatePaysTheOneAdaFloor()} and {@link #adaFivePerMilleBelowTheCrossoverPaysTheFloor()}
 * fail: the builder's own strict evaluation refuses the body at {@code lm_compound_action}, nothing is
 * signed. {@link #theOldPerMilleOnlyFeeIsRefusedByTheNewValidator()} keeps that refusal as a standing
 * negative control.
 */
@Slf4j
class CompoundMainnetGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ProtocolParams PARAMS = LoanFixtures.protocolParams().getProtocolParams();

    private static final String LOAN_ID = "e833a769ea3a480343175e253eab799ec0b058c99de30cc17160dc37";
    private static final String POOL_ID = "00d3513725536642b6fe985ce9ec87d1ebb880497d92e0a8495bc6d0bf";

    /** The deployment the recorded candidate sits at; its hashes are what gets substituted. */
    private static final LoansContractRegistry RECORDED = LoanFixtures.previewDeploymentRegistry();

    /** The SHIPPED artefact under the mainnet coordinates — exactly what a mainnet node derives. */
    private static final LoansContractRegistry REGISTRY = new LoansContractRegistry(
            shippedMainnet("config", "policy-id"), shippedMainnet("lm-config", "policy-id"),
            shippedMainnet("config", "asset-name"), shippedMainnetLoans("smart-tokens-spend-script-hash"));

    /** The live config NFT outputs and their captured datums (mainnet-config-datums-2026-10-26.PROVENANCE.md). */
    private static final TransactionInput CONFIG_AT = new TransactionInput(
            "75e459a0e97d3808031d97b8360308782f197bc778471d3dbc4aa51245886dc5", 0);
    private static final TransactionInput LM_CONFIG_AT = new TransactionInput(
            "c0f0d8219ca1ab77e55163cc9c75858815793b38b2167cbee113bea034e6ef36", 0);

    private static final String LM_COMPOUND_HASH = "4713a925302692076b686eee3de80963c94e464108522afacf19039c";

    /** A synthetic native principal: nothing about this gate depends on which token it is. */
    private static final String TOKEN_UNIT = "c0ffee".repeat(9) + "c0" + "4741544531";

    private static final BigInteger ADA = BigInteger.valueOf(1_000_000L);

    // ======================================================================================
    // The cases (FAB-167 task 6)
    // ======================================================================================

    /** ADA, {@code c = 0}: the per-mille fee is 0, the floor binds — the pool gets {@code Q - 1 ADA}. */
    @Test
    void adaZeroRatePaysTheOneAdaFloor() throws Exception {
        Outcome o = run(Case.ada(BigInteger.valueOf(29_109_268L), 0L));
        o.assertFee(ADA);
    }

    /** ADA, {@code c = 5}, {@code Q = 150 ADA}: {@code Q*c/1000 = 0.75 ADA}, below the floor, so 1 ADA. */
    @Test
    void adaFivePerMilleBelowTheCrossoverPaysTheFloor() throws Exception {
        Outcome o = run(Case.ada(BigInteger.valueOf(150_000_000L), 5L));
        o.assertFee(ADA);
    }

    /** ADA, {@code c = 5}, {@code Q = 300 ADA}: {@code 1.5 ADA} is above the floor and binds (crossover 200 ADA). */
    @Test
    void adaFivePerMilleAboveTheCrossoverPaysThePerMilleFee() throws Exception {
        Outcome o = run(Case.ada(BigInteger.valueOf(300_000_000L), 5L));
        o.assertFee(BigInteger.valueOf(1_500_000L));
    }

    /** Native, {@code c = 5}: {@code Q*c/1000} raw units and NO floor — a token pool is unchanged. */
    @Test
    void nativeFivePerMillePaysThePerMilleFeeWithNoFloor() throws Exception {
        Outcome o = run(Case.token(BigInteger.valueOf(100_000L), 5L));
        o.assertFee(BigInteger.valueOf(500L));
    }

    /**
     * ⛔ The standing negative control: the same ADA candidate built with the fee the node computed
     * before FAB-167 ({@code Q*c/1000}, with no floor) is refused by the builder's strict evaluation —
     * the new validator requires the pool to receive {@code Q - 1 ADA}, not {@code Q}.
     */
    @Test
    void theOldPerMilleOnlyFeeIsRefusedByTheNewValidator() throws Exception {
        Case c = Case.ada(BigInteger.valueOf(29_109_268L), 0L);
        Rig rig = rig(c);
        CompoundCandidate candidate = rig.scanner().scan().candidates().getFirst();
        assertTrue(candidate.structurallyReady(), candidate.exclusion() + ": " + candidate.detail());
        BigInteger legacyFee = c.q().multiply(BigInteger.valueOf(c.feePerMille())).divide(BigInteger.valueOf(1000L));
        assertEquals(BigInteger.ZERO, legacyFee);

        Map<String, TransactionInput> refs = rig.executor().referenceScripts();
        Exception refused = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> rig.builder().build(new CompoundTransactionBuilder.Request(candidate, refs,
                        rig.bond(), rig.config(), rig.lmConfig(), rig.wallet(), rig.bot(), legacyFee,
                        180_000_000L, 180_000_300L)));
        String why = causeChain(refused);
        log.info("FAB-167 legacy fee refused: {}", why);
        // The evaluator names a reward redeemer by its WITHDRAWAL position, not its self.redeemers position.
        int lmCompoundWithdrawal = rig.builder().withdrawalOrder().indexOf(LM_COMPOUND_HASH);
        assertTrue(lmCompoundWithdrawal >= 0);
        assertTrue(why.contains("tag: \"Withdraw\", index: " + lmCompoundWithdrawal + ","),
                "refused for another reason than lm_compound_action (withdrawal " + lmCompoundWithdrawal
                        + "): " + why);
    }

    /** The rig is mainnet's: the eleven scripts are the shipped ones and lm_compound is the 588695…#0 bytes. */
    @Test
    void theRigReferencesTheElevenShippedMainnetValidators() throws Exception {
        assertEquals(LM_COMPOUND_HASH, hashOf(REGISTRY.getLmCompoundActionScript()));
        assertEquals(HexUtil.encodeHexString(REGISTRY.getLmCompoundActionScript().serializeScriptBody()),
                fixture("mainnet-compound-script-2026-10-26.hex"));
        assertEquals(5_246, publishedLmCompound().serializeScriptBody().length,
                "the reference script at 588695…#0 is 5,246 bytes");
        List<String> coordinates = shippedCompoundCoordinates();
        assertEquals(11, coordinates.size());
        assertEquals("588695471b9f865ceac88066883fad118a5a1800c9c69a0663e5ff560d656b1a#0", coordinates.get(6));
        // application.yaml's own table, in the coordinate order: what each coordinate publishes.
        List<String> published = List.of("b256da41", "6743f4b6", "ecd0c0fc", "dfb1d21e", "addd060d",
                "247065fa", "4713a925", "20f765d2", "fef12ea6", "1e0bf58a", "96f1e889");
        List<String> derived = compoundScripts().stream().map(CompoundMainnetGateTest::hashOf)
                .map(h -> h.substring(0, 8)).toList();
        assertEquals(published, derived, "the shipped artefact must derive the published mainnet set");
    }

    // ======================================================================================
    // The run and its assertions
    // ======================================================================================

    private record Case(String name, BigInteger q, long feePerMille, boolean ada) {
        static Case ada(BigInteger q, long c) {
            return new Case("ADA Q=" + q + " c=" + c, q, c, true);
        }

        static Case token(BigInteger q, long c) {
            return new Case("native Q=" + q + " c=" + c, q, c, false);
        }

        String principalUnit() {
            return ada ? "lovelace" : TOKEN_UNIT;
        }
    }

    private Outcome run(Case c) throws Exception {
        Rig rig = rig(c);
        rig.executor().cycle();
        assertEquals(1, rig.submitted().size(), c.name() + ": the armed loop must sign exactly one compound "
                + "(nothing signed means the build or a gate refused it — see the log)");
        byte[] signed = rig.submitted().getFirst();
        Transaction tx = Transaction.deserialize(signed);
        Outcome o = new Outcome(c, rig, tx, signed);
        o.assertEveryLedgerProperty();
        return o;
    }

    private record Outcome(Case c, Rig rig, Transaction tx, byte[] signed) {

        void assertFee(BigInteger expectedFee) throws Exception {
            String unit = c.principalUnit();
            TransactionOutput poolOut = outputWithDatum(tx, rig.pool());
            BigInteger added = units(poolOut).getOrDefault(unit, BigInteger.ZERO)
                    .subtract(units(rig.pool().getAmount()).getOrDefault(unit, BigInteger.ZERO));
            assertEquals(c.q().subtract(expectedFee), added,
                    c.name() + ": the pool must receive exactly Q - fee in the principal unit");

            Map<String, BigInteger> bot = botDelta();
            BigInteger txFee = tx.getBody().getFee();
            if (c.ada()) {
                assertEquals(Map.of("lovelace", expectedFee.subtract(txFee)), nonZero(bot),
                        c.name() + ": BOT_NET is fee - txFee and nothing else");
            } else {
                BigInteger topUp = poolOut.getValue().getCoin().subtract(lovelace(rig.pool().getAmount()));
                Map<String, BigInteger> expected = new TreeMap<>();
                expected.put(unit, expectedFee);
                expected.put("lovelace", lovelace(rig.escrow().getAmount()).subtract(txFee).subtract(topUp));
                assertEquals(nonZero(expected), nonZero(bot),
                        c.name() + ": the bot keeps the raw-unit fee and E - L - T in lovelace");
            }
            log.info("FAB-167 {}: fee {} (pool +{}), tx fee {}, signed {} B, bot delta {}",
                    c.name(), expectedFee, added, txFee, signed.length, bot);
        }

        void assertEveryLedgerProperty() throws Exception {
            // 1. Declared ex-units equal an independent evaluation of the exact signed bytes.
            AikenTransactionEvaluator evaluator = new AikenTransactionEvaluator(
                    LoanFixtures.utxoSupplier(rig.universe()), LoanFixtures.protocolParams(),
                    rig.scripts(), SlotConfigs.mainnet());
            Result<List<EvaluationResult>> evaluated = evaluator.evaluateTx(signed, new LinkedHashSet<>(rig.universe()));
            assertTrue(evaluated.isSuccessful(), c.name() + ": " + evaluated.getResponse());
            List<Redeemer> redeemers = tx.getWitnessSet().getRedeemers();
            assertEquals(11, redeemers.size(), "four general_spend spends and seven withdraw-0 invocations");
            assertEquals(11, evaluated.getValue().size());
            for (Redeemer r : redeemers) {
                EvaluationResult e = evaluated.getValue().stream()
                        .filter(x -> x.getRedeemerTag() == r.getTag() && x.getIndex() == r.getIndex().intValue())
                        .findFirst().orElseThrow(() -> new AssertionError("no evaluation for " + r.getTag()
                                + "#" + r.getIndex()));
                assertEquals(e.getExUnits().getMem(), r.getExUnits().getMem(), r.getTag() + "#" + r.getIndex());
                assertEquals(e.getExUnits().getSteps(), r.getExUnits().getSteps(), r.getTag() + "#" + r.getIndex());
            }

            // 2. All eleven by reference, none witnessed; and the fee covers the ledger's tiered floor.
            Set<String> referenced = referencedScriptHashes();
            assertEquals(compoundScripts().stream().map(CompoundMainnetGateTest::hashOf).collect(Collectors.toSet()),
                    referenced, "all eleven compound validators travel by reference");
            assertTrue(tx.getWitnessSet().getPlutusV3Scripts() == null || tx.getWitnessSet().getPlutusV3Scripts().isEmpty(),
                    "nothing travels inline on mainnet");
            long refBytes = 0;
            Map<String, Long> bytesByHash = new LinkedHashMap<>();
            for (String hash : referenced) {
                long b = rig.scripts().getScript(hash).orElseThrow().serializeScriptBody().length;
                bytesByHash.put(hash, b);
                refBytes += b;
            }
            BigInteger refFee = tierRefScriptFee(refBytes);
            BigInteger floor = BigInteger.valueOf(PARAMS.getMinFeeA()).multiply(BigInteger.valueOf(signed.length))
                    .add(BigInteger.valueOf(PARAMS.getMinFeeB())).add(scriptFee(redeemers)).add(refFee);
            BigInteger slack = tx.getBody().getFee().subtract(floor);
            BigInteger smallestShare = bytesByHash.values().stream()
                    .map(b -> refFee.subtract(tierRefScriptFee(bytesByHash.values().stream()
                            .mapToLong(Long::longValue).sum() - b)))
                    .min(BigInteger::compareTo).orElseThrow();
            log.info("FAB-167 {}: {} reference bytes (tier ladder crossed: {}), ref fee {}, floor {}, fee {}, slack {}",
                    c.name(), refBytes, refBytes > 25_600L, refFee, floor, tx.getBody().getFee(), slack);
            assertTrue(slack.signum() >= 0, c.name() + ": fee below the ledger floor " + floor);
            assertTrue(slack.compareTo(smallestShare) < 0, c.name() + ": slack " + slack
                    + " could hide an unpriced reference script (smallest share " + smallestShare + ")");

            // 3. The SIGNED one-witness transaction fits the real maxTxSize.
            assertEquals(1, tx.getWitnessSet().getVkeyWitnesses().size(), "one signature, the bot's");
            assertTrue(signed.length < PARAMS.getMaxTxSize(),
                    c.name() + ": signed " + signed.length + " B >= maxTxSize " + PARAMS.getMaxTxSize());

            // 4. Ledger min-ADA on every output, collateral return included.
            MinAdaCalculator minAda = new MinAdaCalculator(PARAMS);
            List<TransactionOutput> outputs = new ArrayList<>(tx.getBody().getOutputs());
            if (tx.getBody().getCollateralReturn() != null) {
                outputs.add(tx.getBody().getCollateralReturn());
            }
            for (TransactionOutput output : outputs) {
                assertTrue(output.getValue().getCoin().compareTo(minAda.calculateMinAda(output)) >= 0,
                        c.name() + ": output below min-ADA: " + output);
            }

            // 5. Per-asset conservation: inputs = outputs + fee, for every unit.
            Map<String, BigInteger> in = new TreeMap<>();
            for (TransactionInput input : tx.getBody().getInputs()) {
                add(in, units(resolve(input).getAmount()));
            }
            Map<String, BigInteger> out = new TreeMap<>();
            tx.getBody().getOutputs().forEach(o -> add(out, units(o)));
            out.merge("lovelace", tx.getBody().getFee(), BigInteger::add);
            assertEquals(nonZero(in), nonZero(out), c.name() + ": value is not conserved");

            // 6. Collateral: distinct, pure-ADA, the bot's, enough for ceil(fee * 150%), exact return.
            List<TransactionInput> collateral = tx.getBody().getCollateral();
            assertNotNull(collateral);
            assertFalse(collateral.isEmpty());
            BigInteger capacity = BigInteger.ZERO;
            for (TransactionInput input : collateral) {
                assertFalse(tx.getBody().getInputs().contains(input), "collateral is also spent");
                Utxo u = resolve(input);
                assertEquals(Set.of("lovelace"), units(u.getAmount()).keySet(), "collateral is pure ADA");
                assertTrue(rig.bot().equals(u.getAddress()), "collateral is the bot's");
                capacity = capacity.add(lovelace(u.getAmount()));
            }
            BigInteger required = new BigDecimal(tx.getBody().getFee()).multiply(PARAMS.getCollateralPercent())
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING).toBigIntegerExact();
            assertTrue(tx.getBody().getTotalCollateral().compareTo(required) >= 0, "total collateral below "
                    + required);
            BigInteger returned = capacity.subtract(tx.getBody().getTotalCollateral());
            assertEquals(returned, tx.getBody().getCollateralReturn() == null ? BigInteger.ZERO
                    : tx.getBody().getCollateralReturn().getValue().getCoin(), "collateral return");
        }

        /** Everything the bot's credential received minus everything it spent, per unit. */
        Map<String, BigInteger> botDelta() {
            Map<String, BigInteger> delta = new TreeMap<>();
            String botCredential = HexUtil.encodeHexString(
                    new Address(rig.bot()).getPaymentCredentialHash().orElseThrow());
            for (TransactionOutput o : tx.getBody().getOutputs()) {
                if (botCredential.equals(paymentCredential(o.getAddress()))) {
                    add(delta, units(o));
                }
            }
            for (TransactionInput input : tx.getBody().getInputs()) {
                Utxo u = resolve(input);
                if (botCredential.equals(paymentCredential(u.getAddress()))) {
                    units(u.getAmount()).forEach((unit, q) -> delta.merge(unit, q.negate(), BigInteger::add));
                }
            }
            return delta;
        }

        Set<String> referencedScriptHashes() {
            Set<String> hashes = new HashSet<>();
            for (TransactionInput input : tx.getBody().getReferenceInputs()) {
                Utxo u = resolve(input);
                if (u.getReferenceScriptHash() != null) {
                    hashes.add(u.getReferenceScriptHash());
                }
            }
            return hashes;
        }

        Utxo resolve(TransactionInput input) {
            return rig.universe().stream()
                    .filter(u -> u.getTxHash().equals(input.getTransactionId())
                            && u.getOutputIndex() == input.getIndex())
                    .findFirst().orElseThrow(() -> new AssertionError("unresolved " + input));
        }
    }

    // ======================================================================================
    // The rig: production wiring with only the leaves supplied
    // ======================================================================================

    private record Rig(CompoundExecutor executor, CompoundTransactionBuilder builder, CompoundCandidateScanner scanner,
                       List<byte[]> submitted, List<Utxo> universe, ScriptSupplier scripts, String bot,
                       Utxo escrow, Utxo bond, Utxo pool, Utxo poolManager, Utxo config, Utxo lmConfig,
                       Utxo wallet) {
    }

    private static Rig rig(Case c) throws Exception {
        Account account = new Account(Networks.mainnet());
        String bot = account.baseAddress();

        String bondDatum = bondDatum(c);
        Utxo escrow = retarget(recorded("escrow"), REGISTRY.getAssetManagerSpendScriptHash(),
                RECORDED.getAssetManagerSpendScriptHash(), escrowAmounts(c), recorded("escrow").getInlineDatum());
        Utxo bond = retarget(recorded("bond"), REGISTRY.getLenderManagerSpendScriptHash(),
                RECORDED.getLenderManagerSpendScriptHash(), bondAmounts(c), bondDatum);
        Utxo pool = retarget(recorded("pool"), REGISTRY.getPoolSpendScriptHash(), RECORDED.getPoolSpendScriptHash(),
                poolAmounts(c), poolDatum(c, bondDatum));
        Utxo poolManager = retarget(recorded("poolManager"), REGISTRY.getPoolManagerSpendScriptHash(),
                RECORDED.getPoolManagerSpendScriptHash(), retargetUnits(recorded("poolManager").getAmount()),
                poolManagerDatum(c.feePerMille()));
        Utxo config = configUtxo(CONFIG_AT, REGISTRY.getConfigPolicyId(), fixture("mainnet-config-datum-2026-10-23.hex"));
        Utxo lmConfig = configUtxo(LM_CONFIG_AT, REGISTRY.getLmConfigPolicyId(),
                fixture("mainnet-lm-config-datum-2026-10-26.hex"));
        Utxo wallet = Utxo.builder().txHash("9e".repeat(32)).outputIndex(0).address(bot)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L)))).build();
        Utxo collateral = Utxo.builder().txHash("c0".repeat(32)).outputIndex(0).address(bot)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000L)))).build();

        List<Utxo> universe = new ArrayList<>(List.of(escrow, bond, pool, poolManager, config, lmConfig,
                wallet, collateral));
        List<String> coordinates = shippedCompoundCoordinates();
        List<PlutusScript> scripts = compoundScripts();
        for (int i = 0; i < coordinates.size(); i++) {
            String[] at = coordinates.get(i).split("#");
            String hash = hashOf(scripts.get(i));
            universe.add(Utxo.builder().txHash(at[0]).outputIndex(Integer.parseInt(at[1]))
                    .address(AddressProvider.getEntAddress(Credential.fromScript(hash), Networks.mainnet()).getAddress())
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(25_000_000L))))
                    .referenceScriptHash(hash).build());
        }
        List<Utxo> frozen = List.copyOf(universe);

        ScriptSupplier provider = scriptSupplier();
        ScriptSupplier scriptsForBuilder = new HashCheckedScriptSupplier(provider);
        var utxoSupplier = LoanFixtures.utxoSupplier(frozen);
        CompoundTransactionBuilder builder = new CompoundTransactionBuilder(REGISTRY, Networks.mainnet(),
                utxoSupplier, LoanFixtures.protocolParams(), scriptsForBuilder,
                new AikenTransactionEvaluator(utxoSupplier, LoanFixtures.protocolParams(), provider,
                        SlotConfigs.mainnet()));

        LenderBond lenderBond = new LenderBond(bond.getTxHash(), bond.getOutputIndex(), bond.getAddress(), LOAN_ID,
                bond.getInlineDatum(), new LenderManagerDatumConverter().deserialize(bond.getInlineDatum()));
        LiquidationUtxoResolver resolver = new LiquidationUtxoResolver(null, null, null) {
            @Override
            public Optional<Utxo> resolveBondUtxo(LenderBond b) {
                return Optional.of(bond);
            }

            @Override
            public Optional<Utxo> resolveConfigUtxo() {
                return Optional.of(config);
            }

            @Override
            public Optional<Utxo> resolveLmConfigUtxo() {
                return Optional.of(lmConfig);
            }

            @Override
            public PoolPair resolvePool(String poolId) {
                return POOL_ID.equals(poolId)
                        ? new PoolPair(PoolLookup.RESOLVED, pool, poolManager, "rig")
                        : new PoolPair(PoolLookup.NOT_VISIBLE, null, null, "rig: unknown pool " + poolId);
            }
        };
        LenderBondService bonds = new LenderBondService(null, null) {
            @Override
            public List<LenderBond> findAll() {
                return List.of(lenderBond);
            }
        };
        CompoundCandidateScanner scanner = new CompoundCandidateScanner(null, REGISTRY, bonds, resolver) {
            @Override
            public List<Utxo> escrows() {
                return List.of(escrow);
            }
        };

        // A stated loss bound wide enough that every case is SUBMITTED: this gate is about what is built.
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.valueOf(-5_000_000L));
        ReflectionTestUtils.setField(configuration, "referenceScripts", String.join(",", coordinates));
        var network = new AppConfig.Network();
        network.setNetworkForTest("mainnet");
        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);
        List<byte[]> submitted = new ArrayList<>();
        // The native case prices its raw-unit fee: one fixed, usable observation (1 lovelace per raw unit),
        // so the executor's pre-sign re-read sees the same observation it priced with.
        long now = System.currentTimeMillis();
        OraclePriceFeed feed = OraclePriceFeed.aggregated(AssetType.fromUnit(TOKEN_UNIT), BigInteger.ONE,
                BigInteger.ONE, now - 600_000L, now + 600_000L);
        CompoundExecutor executor = new CompoundExecutor(configuration, network, blockEventListener,
                new AppUtxoService(null, null) {
                    @Override
                    public List<Utxo> listWalletUtxo() {
                        return List.of(wallet, collateral);
                    }
                }, account, scanner,
                new CompoundEconomics(configuration, network,
                        new PricingService(new FluidOracleClient("http://unused.invalid") {
                            @Override
                            public Optional<OraclePriceFeed> findFeed(AssetType asset, long atMillis) {
                                return TOKEN_UNIT.equals(asset.toUnit()) ? Optional.of(feed) : Optional.empty();
                            }
                        })),
                builder, resolver, utxoSupplier,
                ClasspathConversionsFactory.createConverters(NetworkType.MAINNET),
                bytes -> {
                    submitted.add(bytes);
                    return Result.success("rig").withValue("rig");
                });
        return new Rig(executor, builder, scanner, submitted, frozen, provider, bot, escrow, bond, pool,
                poolManager, config, lmConfig, wallet);
    }

    // ---- the candidate, re-targeted onto mainnet ------------------------------------------------

    private static List<Amount> escrowAmounts(Case c) {
        return c.ada()
                ? List.of(Amount.lovelace(c.q()))
                : List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)), Amount.asset(TOKEN_UNIT, c.q()));
    }

    /**
     * The recorded bond's value — in the native case with 2 ADA, because the native datum is 29 bytes longer
     * and the recorded 1,810,200 lovelace is below its min-ADA. A bond under min-ADA would have the balancer
     * top up its echo, which {@code lm_compound_action}'s {@code equals_data} on the bond refuses: no real
     * native bond can sit below the minimum it was created with, so the rig must not either.
     */
    private static List<Amount> bondAmounts(Case c) {
        List<Amount> amounts = new ArrayList<>(retargetUnits(recorded("bond").getAmount()));
        if (!c.ada()) {
            amounts.replaceAll(a -> "lovelace".equals(a.getUnit())
                    ? Amount.lovelace(BigInteger.valueOf(2_000_000L)) : a);
        }
        return amounts;
    }

    private static List<Amount> poolAmounts(Case c) {
        List<Amount> amounts = new ArrayList<>(retargetUnits(recorded("pool").getAmount()));
        if (!c.ada()) {
            amounts.replaceAll(a -> "lovelace".equals(a.getUnit())
                    ? Amount.lovelace(BigInteger.valueOf(2_500_000L)) : a);
            amounts.add(Amount.asset(TOKEN_UNIT, BigInteger.valueOf(9_000_000_000L)));
        }
        return amounts;
    }

    /** The recorded bond datum, its principal swapped for the token in the native case. */
    private static String bondDatum(Case c) {
        String recordedHex = recorded("bond").getInlineDatum();
        LenderManagerDatum d = new LenderManagerDatumConverter().deserialize(recordedHex);
        assertEquals(recordedHex, LoanFixtures.encode(d).serializeToHex(), "the bond datum re-encodes byte-identically");
        if (c.ada()) {
            return recordedHex;
        }
        return LoanFixtures.encode(new LenderManagerDatum(d.lenderAuth(), d.lenderStakeCredential(),
                d.shouldLiquidationConvertToPrincipal(), d.liquidationFeePerMille(), d.poolId(),
                AssetType.fromUnit(TOKEN_UNIT))).serializeToHex();
    }

    /**
     * The recorded pool datum with the two deployment hashes it carries — {@code lenderAuth}'s pool-manager
     * policy and the lender-bond address's LenderManager spend credential — substituted, its bond-datum hash
     * recomputed, and in the native case its principal swapped.
     */
    private static String poolDatum(Case c, String bondDatum) {
        String hex = recorded("pool").getInlineDatum();
        hex = replaceOnce(hex, RECORDED.getPoolManagerPolicyId(), REGISTRY.getPoolManagerPolicyId());
        hex = replaceOnce(hex, RECORDED.getLenderManagerSpendScriptHash(), REGISTRY.getLenderManagerSpendScriptHash());
        String recordedBondHash = blake2b256(recorded("bond").getInlineDatum());
        hex = replaceOnce(hex, "5820" + recordedBondHash, "5820" + blake2b256(bondDatum));
        if (!c.ada()) {
            AssetType token = AssetType.fromUnit(TOKEN_UNIT);
            hex = replaceFirst(hex, "d8799f4040ff", "d8799f581c" + token.policyId()
                    + Integer.toHexString(0x40 + token.assetName().length() / 2) + token.assetName() + "ff");
        }
        return hex;
    }

    /** {@code PoolManagerDatum { poolOwnerAuth (recorded), compoudingFeePerMille = c }}. */
    private static String poolManagerDatum(long feePerMille) {
        String hex = recorded("poolManager").getInlineDatum();
        assertTrue(hex.endsWith("00ff"), "the recorded pool manager publishes c = 0 as its last field");
        return hex.substring(0, hex.length() - 4) + BigIntPlutusData.of(feePerMille).serializeToHex() + "ff";
    }

    private static Utxo retarget(Utxo recorded, String mainnetPayment, String recordedPayment, List<Amount> amounts,
                                 String datum) {
        Address address = new Address(recorded.getAddress());
        assertEquals(recordedPayment, HexUtil.encodeHexString(address.getPaymentCredentialHash().orElseThrow()),
                "the recorded UTxO sits at the deployment being substituted");
        String stake = HexUtil.encodeHexString(address.getDelegationCredentialHash().orElseThrow());
        return Utxo.builder().txHash(recorded.getTxHash()).outputIndex(recorded.getOutputIndex())
                .address(AddressProvider.getBaseAddress(Credential.fromScript(mainnetPayment),
                        Credential.fromKey(stake), Networks.mainnet()).getAddress())
                .amount(amounts).inlineDatum(datum).build();
    }

    private static List<Amount> retargetUnits(List<Amount> amounts) {
        assertEquals(RECORDED.getLenderBondPolicyId(), REGISTRY.getLenderBondPolicyId(),
                "the lender-bond policy is deployment-independent");
        List<Amount> out = new ArrayList<>();
        for (Amount a : amounts) {
            String unit = a.getUnit();
            if (unit.startsWith(RECORDED.getPoolPolicyId())) {
                unit = REGISTRY.getPoolPolicyId() + unit.substring(56);
            } else if (unit.startsWith(RECORDED.getPoolManagerPolicyId())) {
                unit = REGISTRY.getPoolManagerPolicyId() + unit.substring(56);
            }
            out.add(Amount.builder().unit(unit).quantity(a.getQuantity()).build());
        }
        return out;
    }

    private static Utxo configUtxo(TransactionInput at, String policyId, String datumHex) {
        return Utxo.builder().txHash(at.getTransactionId()).outputIndex(at.getIndex())
                .address(AddressProvider.getEntAddress(Credential.fromScript(policyId), Networks.mainnet()).getAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000L)),
                        Amount.asset(policyId + shippedMainnet("config", "asset-name"), BigInteger.ONE)))
                .inlineDatum(datumHex).build();
    }

    // ---- scripts -------------------------------------------------------------------------------

    /** The eleven, in {@code application.yaml}'s presentation order (which is its coordinate order). */
    private static List<PlutusScript> compoundScripts() {
        return List.of(REGISTRY.getAssetManagerSpendScript(), REGISTRY.getLenderManagerSpendScript(),
                REGISTRY.getPoolSpendScript(), REGISTRY.getPoolManagerSpendScript(), REGISTRY.getAssetManagerScript(),
                REGISTRY.getLenderManagerScript(), REGISTRY.getLmCompoundActionScript(), REGISTRY.getPoolScript(),
                REGISTRY.getPoolCompoundActionScript(), REGISTRY.getPoolManagerScript(),
                REGISTRY.getPmCompoundLiquidityScript());
    }

    /** The provider leaf: the applied scripts, except lm_compound, which is the PUBLISHED bytes. */
    private static ScriptSupplier scriptSupplier() throws Exception {
        Map<String, PlutusScript> byHash = new HashMap<>();
        for (PlutusScript script : compoundScripts()) {
            byHash.put(hashOf(script), script);
        }
        PlutusScript published = publishedLmCompound();
        assertEquals(LM_COMPOUND_HASH, hashOf(published), "588695…#0 publishes 4713a925…");
        byHash.put(LM_COMPOUND_HASH, published);
        return hash -> Optional.ofNullable(byHash.get(hash));
    }

    private static PlutusScript publishedLmCompound() throws Exception {
        byte[] body = HexUtil.decodeHexString(fixture("mainnet-compound-script-2026-10-26.hex"));
        return PlutusV3Script.builder().cborHex(HexUtil.encodeHexString(
                CborSerializationUtil.serialize(new co.nstant.in.cbor.model.ByteString(body)))).build();
    }

    // ---- ledger arithmetic, independent of the builder -----------------------------------------

    private static BigInteger scriptFee(List<Redeemer> redeemers) {
        BigDecimal total = BigDecimal.ZERO;
        for (Redeemer r : redeemers) {
            total = total.add(PARAMS.getPriceMem().multiply(new BigDecimal(r.getExUnits().getMem())))
                    .add(PARAMS.getPriceStep().multiply(new BigDecimal(r.getExUnits().getSteps())));
        }
        return total.setScale(0, RoundingMode.CEILING).toBigIntegerExact();
    }

    /** The Conway {@code tierRefScriptFee}: 25,600-byte tiers, x1.2 per tier, floored. */
    private static BigInteger tierRefScriptFee(long bytes) {
        BigDecimal price = PARAMS.getMinFeeRefScriptCostPerByte();
        BigDecimal fee = BigDecimal.ZERO;
        long remaining = bytes;
        while (remaining >= 25_600L) {
            fee = fee.add(price.multiply(BigDecimal.valueOf(25_600L)));
            price = price.multiply(new BigDecimal("1.2"));
            remaining -= 25_600L;
        }
        return fee.add(price.multiply(BigDecimal.valueOf(remaining))).setScale(0, RoundingMode.FLOOR)
                .toBigIntegerExact();
    }

    // ---- primitives ----------------------------------------------------------------------------

    private static Utxo recorded(String key) {
        try (InputStream is = CompoundMainnetGateTest.class
                .getResourceAsStream("/loans-v4/compound-candidate-e833a769.json")) {
            JsonNode n = JSON.readTree(is).get(key);
            List<Amount> amounts = new ArrayList<>();
            n.get("amount").forEach(a -> amounts.add(Amount.builder().unit(a.get("unit").asText())
                    .quantity(new BigInteger(a.get("quantity").asText())).build()));
            return Utxo.builder().txHash(n.get("txHash").asText()).outputIndex(n.get("outputIndex").asInt())
                    .address(n.get("address").asText()).amount(amounts)
                    .inlineDatum(n.get("inlineDatum").isNull() ? null : n.get("inlineDatum").asText()).build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String fixture(String name) {
        try (InputStream is = CompoundMainnetGateTest.class.getResourceAsStream("/loans-v4/" + name)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            throw new IllegalStateException("fixture " + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shippedLoans() {
        try (InputStream in = CompoundMainnetGateTest.class.getClassLoader().getResourceAsStream("application.yaml")) {
            Iterable<Object> documents = new Yaml().loadAll(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            // The FIRST document only: later ones are profiles, and preview overrides these keys.
            return (Map<String, Object>) ((Map<String, Object>) documents.iterator().next()).get("loans");
        } catch (Exception e) {
            throw new IllegalStateException("application.yaml", e);
        }
    }

    /** The default of a {@code ${ENV:default}} placeholder. */
    private static String defaultOf(Object placeholder) {
        String s = String.valueOf(placeholder);
        int colon = s.indexOf(':');
        assertTrue(s.startsWith("${") && colon > 1 && s.endsWith("}"), "not an env-overridable default: " + s);
        return s.substring(colon + 1, s.length() - 1);
    }

    @SuppressWarnings("unchecked")
    private static String shippedMainnet(String section, String key) {
        return defaultOf(((Map<String, Object>) shippedLoans().get(section)).get(key));
    }

    private static String shippedMainnetLoans(String key) {
        return defaultOf(shippedLoans().get(key));
    }

    @SuppressWarnings("unchecked")
    private static List<String> shippedCompoundCoordinates() {
        return List.of(defaultOf(((Map<String, Object>) shippedLoans().get("compound")).get("reference-scripts"))
                .split(","));
    }

    private static String replaceOnce(String hex, String from, String to) {
        int first = hex.indexOf(from);
        assertTrue(first >= 0 && hex.indexOf(from, first + 1) < 0,
                "expected exactly one occurrence of " + from);
        return hex.replace(from, to);
    }

    private static String replaceFirst(String hex, String from, String to) {
        int first = hex.indexOf(from);
        assertTrue(first >= 0, "no occurrence of " + from);
        return hex.substring(0, first) + to + hex.substring(first + from.length());
    }

    private static String blake2b256(String hex) {
        return HexUtil.encodeHexString(Blake2bUtil.blake2bHash256(HexUtil.decodeHexString(hex)));
    }

    private static String hashOf(PlutusScript script) {
        try {
            return HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String paymentCredential(String address) {
        return new Address(address).getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse("");
    }

    private static TransactionOutput outputWithDatum(Transaction tx, Utxo input) {
        return tx.getBody().getOutputs().stream()
                .filter(o -> input.getAddress().equals(o.getAddress()))
                .filter(o -> o.getInlineDatum() != null
                        && input.getInlineDatum().equalsIgnoreCase(o.getInlineDatum().serializeToHex()))
                .findFirst().orElseThrow(() -> new AssertionError("no output echoes " + input.getAddress()));
    }

    private static Map<String, BigInteger> units(List<Amount> amounts) {
        Map<String, BigInteger> out = new TreeMap<>();
        amounts.forEach(a -> out.merge(a.getUnit(), a.getQuantity(), BigInteger::add));
        return out;
    }

    private static Map<String, BigInteger> units(TransactionOutput output) {
        return units(ValueUtil.toAmountList(output.getValue()));
    }

    private static BigInteger lovelace(List<Amount> amounts) {
        return units(amounts).getOrDefault("lovelace", BigInteger.ZERO);
    }

    private static void add(Map<String, BigInteger> target, Map<String, BigInteger> more) {
        more.forEach((k, v) -> target.merge(k, v, BigInteger::add));
    }

    private static Map<String, BigInteger> nonZero(Map<String, BigInteger> units) {
        Map<String, BigInteger> out = new TreeMap<>(units);
        out.values().removeIf(v -> v.signum() == 0);
        return out;
    }

    private static String causeChain(Throwable e) {
        StringBuilder s = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            s.append(t).append(" <- ");
        }
        return s.toString();
    }
}
