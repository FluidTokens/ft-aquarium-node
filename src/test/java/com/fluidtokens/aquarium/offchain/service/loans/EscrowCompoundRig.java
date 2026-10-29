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
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.springframework.test.util.ReflectionTestUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-168: the compound half of every escrow round trip, against <b>what mainnet runs since 2026-10-26</b>.
 *
 * <p>The production wiring of {@link CompoundMainnetGateTest} (FAB-167), generalised from one recorded
 * candidate to <b>any</b> escrow / lender bond / pool / pool manager a caller supplies: the real
 * {@link CompoundCandidateScanner}, the real {@link CompoundEconomics}, the real {@link CompoundExecutor}
 * cycle and {@link CompoundTransactionBuilder}'s PRODUCTION constructor with a
 * {@link HashCheckedScriptSupplier} and an Aiken evaluator; the SHIPPED artefact under the mainnet
 * coordinates of {@code application.yaml}; the live ConfigDatum / LMConfigDatum captures of 2026-10-23/26;
 * all eleven validators by reference, {@code lm_compound_action} served as the published 5,246 bytes of
 * {@code 588695…#0} ({@code 4713a925…}). Nothing here computes a fee: what the node computes is what is
 * built, evaluated and signed.
 *
 * <p>Copied rather than shared with the gate on purpose: the gate is an accepted FAB-167 artefact, and a
 * refactor of it is not this slice's to make.
 */
final class EscrowCompoundRig {

    private EscrowCompoundRig() {
    }

    static final ProtocolParams PARAMS = LoanFixtures.protocolParams().getProtocolParams();
    static final ObjectMapper JSON = new ObjectMapper();

    /** The SHIPPED artefact under the mainnet coordinates — exactly what a mainnet node derives. */
    static final LoansContractRegistry REGISTRY = new LoansContractRegistry(
            shippedMainnet("config", "policy-id"), shippedMainnet("lm-config", "policy-id"),
            shippedMainnet("config", "asset-name"), shippedMainnetLoans("smart-tokens-spend-script-hash"));

    /** The live config NFT outputs and their captured datums (mainnet-config-datums-2026-10-26.PROVENANCE.md). */
    static final TransactionInput CONFIG_AT = new TransactionInput(
            "75e459a0e97d3808031d97b8360308782f197bc778471d3dbc4aa51245886dc5", 0);
    static final TransactionInput LM_CONFIG_AT = new TransactionInput(
            "c0f0d8219ca1ab77e55163cc9c75858815793b38b2167cbee113bea034e6ef36", 0);

    static final String LM_COMPOUND_HASH = "4713a925302692076b686eee3de80963c94e464108522afacf19039c";

    static final BigInteger ADA = BigInteger.valueOf(1_000_000L);

    /**
     * The four UTxOs a compound spends, plus the decoded bond the scanner joins through.
     *
     * @param escrow      the asset-manager escrow, at the MAINNET asset-manager spend credential
     * @param bond        the lender bond UTxO, at the MAINNET lender-manager spend credential
     * @param pool        the pool UTxO for the bond's {@code poolId}
     * @param poolManager its pool-manager UTxO
     * @param tokenUnit   the principal unit when it is a native token (priced 1 lovelace per raw unit for
     *                    the executor's economics), else {@code null}
     */
    record Parts(Utxo escrow, Utxo bond, Utxo pool, Utxo poolManager, String tokenUnit) {
        LenderBond lenderBond() {
            var datum = new LenderManagerDatumConverter().deserialize(bond.getInlineDatum());
            String loanId = bond.getAmount().stream()
                    .filter(a -> a.getUnit().startsWith(REGISTRY.getLenderBondPolicyId()))
                    .map(a -> a.getUnit().substring(56)).findFirst()
                    .orElseThrow(() -> new AssertionError("the bond UTxO carries no lender-bond NFT"));
            return new LenderBond(bond.getTxHash(), bond.getOutputIndex(), bond.getAddress(), loanId,
                    bond.getInlineDatum(), datum);
        }

        Parts withEscrow(Utxo other) {
            return new Parts(other, bond, pool, poolManager, tokenUnit);
        }
    }

    record Rig(Parts parts, CompoundExecutor executor, CompoundTransactionBuilder builder,
               CompoundCandidateScanner scanner, List<byte[]> submitted, List<Utxo> universe,
               ScriptSupplier scripts, String bot, Utxo config, Utxo lmConfig, Utxo wallet) {
    }

    /** One compound the armed loop signed, with the ledger facts measured off its bytes. */
    record Compounded(Rig rig, Transaction tx, byte[] signed, BigInteger q, long feePerMille,
                      BigInteger fee, BigInteger poolAdded) {
    }

    // ======================================================================================
    // The three verbs the round-trip test uses
    // ======================================================================================

    /** What the REAL scanner says about this escrow, with nothing else in the census. */
    static CompoundCandidate classify(Parts parts) throws Exception {
        List<CompoundCandidate> candidates = rig(parts).scanner().scan().candidates();
        assertEquals(1, candidates.size(), "one escrow in, one classification out");
        return candidates.getFirst();
    }

    /**
     * The armed loop end to end: scan → economics → build (strict evaluation) → sign. Exactly one compound
     * must be signed, and its bytes must re-evaluate independently with the declared ex-units, fit the real
     * {@code maxTxSize}, keep every output above min-ADA and conserve value — then the pool must have
     * received exactly {@code Q - CompoundEconomics.compoundingFee(Q, c, principal)}.
     */
    static Compounded compound(Parts parts, String label) throws Exception {
        Rig rig = rig(parts);
        CompoundCandidate candidate = rig.scanner().scan().candidates().getFirst();
        assertTrue(candidate.structurallyReady(), label + ": the scanner refused it as " + candidate.exclusion()
                + ": " + candidate.detail());
        rig.executor().cycle();
        assertEquals(1, rig.submitted().size(), label + ": the armed loop must sign exactly one compound "
                + "(nothing signed means the build or a gate refused it — see the log)");
        byte[] signed = rig.submitted().getFirst();
        Transaction tx = Transaction.deserialize(signed);

        assertIndependentlyValid(rig, tx, signed, label);

        String unit = candidate.principalIsAda() ? "lovelace" : parts.tokenUnit();
        BigInteger q = candidate.addedLiquidity();
        AssetType principal = candidate.bond().datum().principalAsset();
        BigInteger fee = CompoundEconomics.compoundingFee(q, candidate.feePerMille(), principal);
        TransactionOutput poolOut = outputEchoing(tx, parts.pool());
        BigInteger added = units(poolOut).getOrDefault(unit, BigInteger.ZERO)
                .subtract(units(parts.pool().getAmount()).getOrDefault(unit, BigInteger.ZERO));
        assertEquals(q.subtract(fee), added, label + ": the pool must receive exactly Q - F in " + unit);
        return new Compounded(rig, tx, signed, q, candidate.feePerMille(), fee, added);
    }

    /**
     * ⛔ The validator's own verdict on an escrow the scanner refuses, isolated to that escrow alone.
     *
     * <p>{@code control} is the same escrow — same coordinates, address and datum bytes — carrying the value
     * shape the validator accepts; it is compounded through {@link #compound}. The signed bytes of THAT
     * compound are then re-evaluated with the escrow input resolving to {@code real} instead. The transaction
     * is byte-identical, so a refusal is about the escrow's value and nothing else. Phase 2 only sees the
     * resolved inputs; value conservation is phase 1 and does not run here, which is the point: the question
     * is what {@code lm_compound_action} says, not whether the ledger would have let us ask.
     *
     * @return the evaluator's response, which the caller has already seen name {@code lm_compound_action}'s
     *         withdrawal
     */
    static String refusedByLmCompound(Parts control, Utxo real, String label) throws Exception {
        assertEquals(control.escrow().getTxHash() + "#" + control.escrow().getOutputIndex(),
                real.getTxHash() + "#" + real.getOutputIndex(), "the control is the same coordinate");
        assertEquals(control.escrow().getAddress(), real.getAddress(), "and the same address");
        assertEquals(control.escrow().getInlineDatum(), real.getInlineDatum(), "and the same datum bytes");

        Compounded accepted = compound(control, label + " (control)");
        List<Utxo> swapped = new ArrayList<>();
        for (Utxo u : accepted.rig().universe()) {
            swapped.add(u == control.escrow() ? real : u);
        }
        assertTrue(swapped.contains(real), "the escrow was swapped into the universe");
        String why;
        try {
            // Aiken reports a phase-2 failure either as an unsuccessful Result or as an ApiException
            // wrapping the machine's message; both are read the same way.
            Result<List<EvaluationResult>> evaluated = evaluator(swapped, accepted.rig().scripts())
                    .evaluateTx(accepted.signed(), new LinkedHashSet<>(swapped));
            assertFalse(evaluated.isSuccessful(),
                    label + ": lm_compound_action accepted an escrow the scanner refuses");
            why = String.valueOf(evaluated.getResponse());
        } catch (com.bloxbean.cardano.client.api.exception.ApiException e) {
            why = causeChain(e);
        }
        int lmCompoundWithdrawal = accepted.rig().builder().withdrawalOrder().indexOf(LM_COMPOUND_HASH);
        assertTrue(lmCompoundWithdrawal >= 0);
        assertTrue(why.contains("tag: \"Withdraw\", index: " + lmCompoundWithdrawal + ","),
                label + ": refused, but not by lm_compound_action (withdrawal " + lmCompoundWithdrawal + "): " + why);
        return why;
    }

    // ======================================================================================
    // Independent ledger checks on the signed bytes (the gate's, restated)
    // ======================================================================================

    private static void assertIndependentlyValid(Rig rig, Transaction tx, byte[] signed, String label)
            throws Exception {
        Result<List<EvaluationResult>> evaluated = evaluator(rig.universe(), rig.scripts())
                .evaluateTx(signed, new LinkedHashSet<>(rig.universe()));
        assertTrue(evaluated.isSuccessful(), label + ": " + evaluated.getResponse());
        List<Redeemer> redeemers = tx.getWitnessSet().getRedeemers();
        assertEquals(11, redeemers.size(), "four general_spend spends and seven withdraw-0 invocations");
        for (Redeemer r : redeemers) {
            EvaluationResult e = evaluated.getValue().stream()
                    .filter(x -> x.getRedeemerTag() == r.getTag() && x.getIndex() == r.getIndex().intValue())
                    .findFirst().orElseThrow(() -> new AssertionError("no evaluation for " + r.getTag()
                            + "#" + r.getIndex()));
            assertEquals(e.getExUnits().getMem(), r.getExUnits().getMem(), r.getTag() + "#" + r.getIndex());
            assertEquals(e.getExUnits().getSteps(), r.getExUnits().getSteps(), r.getTag() + "#" + r.getIndex());
        }
        assertTrue(tx.getWitnessSet().getPlutusV3Scripts() == null || tx.getWitnessSet().getPlutusV3Scripts().isEmpty(),
                "nothing travels inline on mainnet");
        assertTrue(signed.length < PARAMS.getMaxTxSize(), label + ": signed " + signed.length + " B");

        MinAdaCalculator minAda = new MinAdaCalculator(PARAMS);
        for (TransactionOutput output : tx.getBody().getOutputs()) {
            assertTrue(output.getValue().getCoin().compareTo(minAda.calculateMinAda(output)) >= 0,
                    label + ": output below min-ADA: " + output);
        }

        Map<String, BigInteger> in = new TreeMap<>();
        for (TransactionInput input : tx.getBody().getInputs()) {
            Utxo u = rig.universe().stream()
                    .filter(x -> x.getTxHash().equals(input.getTransactionId()) && x.getOutputIndex() == input.getIndex())
                    .findFirst().orElseThrow(() -> new AssertionError("unresolved " + input));
            units(u.getAmount()).forEach((k, v) -> in.merge(k, v, BigInteger::add));
        }
        Map<String, BigInteger> out = new TreeMap<>();
        tx.getBody().getOutputs().forEach(o -> units(o).forEach((k, v) -> out.merge(k, v, BigInteger::add)));
        out.merge("lovelace", tx.getBody().getFee(), BigInteger::add);
        in.values().removeIf(v -> v.signum() == 0);
        out.values().removeIf(v -> v.signum() == 0);
        assertEquals(in, out, label + ": value is not conserved");
    }

    private static AikenTransactionEvaluator evaluator(List<Utxo> universe, ScriptSupplier scripts) {
        return new AikenTransactionEvaluator(LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams(),
                scripts, SlotConfigs.mainnet());
    }

    // ======================================================================================
    // The rig: production wiring with only the leaves supplied
    // ======================================================================================

    static Rig rig(Parts parts) throws Exception {
        Account account = new Account(Networks.mainnet());
        String bot = account.baseAddress();

        Utxo escrow = parts.escrow();
        Utxo bond = parts.bond();
        Utxo pool = parts.pool();
        Utxo poolManager = parts.poolManager();
        assertEquals(REGISTRY.getAssetManagerSpendScriptHash(), paymentCredential(escrow.getAddress()),
                "the escrow sits at the mainnet asset-manager spend credential");
        assertEquals(REGISTRY.getLenderManagerSpendScriptHash(), paymentCredential(bond.getAddress()),
                "the bond sits at the mainnet lender-manager spend credential");
        assertEquals(REGISTRY.getPoolSpendScriptHash(), paymentCredential(pool.getAddress()));
        assertEquals(REGISTRY.getPoolManagerSpendScriptHash(), paymentCredential(poolManager.getAddress()));

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
        var utxoSupplier = LoanFixtures.utxoSupplier(frozen);
        CompoundTransactionBuilder builder = new CompoundTransactionBuilder(REGISTRY, Networks.mainnet(),
                utxoSupplier, LoanFixtures.protocolParams(), new HashCheckedScriptSupplier(provider),
                new AikenTransactionEvaluator(utxoSupplier, LoanFixtures.protocolParams(), provider,
                        SlotConfigs.mainnet()));

        LenderBond lenderBond = parts.lenderBond();
        String poolId = lenderBond.datum().poolId();
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
            public PoolPair resolvePool(String id) {
                return poolId.equals(id)
                        ? new PoolPair(PoolLookup.RESOLVED, pool, poolManager, "rig")
                        : new PoolPair(PoolLookup.NOT_VISIBLE, null, null, "rig: unknown pool " + id);
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

        // A stated loss bound wide enough that every admitted case is SUBMITTED: this rig is about what is built.
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.valueOf(-5_000_000L));
        ReflectionTestUtils.setField(configuration, "referenceScripts", String.join(",", coordinates));
        var network = new AppConfig.Network();
        network.setNetworkForTest("mainnet");
        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);
        List<byte[]> submitted = new ArrayList<>();
        long now = System.currentTimeMillis();
        String token = parts.tokenUnit();
        OraclePriceFeed feed = token == null ? null : OraclePriceFeed.aggregated(AssetType.fromUnit(token),
                BigInteger.ONE, BigInteger.ONE, now - 600_000L, now + 600_000L);
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
                                return token != null && token.equals(asset.toUnit())
                                        ? Optional.of(feed) : Optional.empty();
                            }
                        })),
                builder, resolver, utxoSupplier,
                ClasspathConversionsFactory.createConverters(NetworkType.MAINNET),
                bytes -> {
                    submitted.add(bytes);
                    return Result.success("rig").withValue("rig");
                });
        return new Rig(parts, executor, builder, scanner, submitted, frozen, provider, bot, config, lmConfig,
                wallet);
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
    static List<PlutusScript> compoundScripts() {
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
        byte[] body = HexUtil.decodeHexString(fixture("mainnet-compound-script-2026-10-26.hex"));
        PlutusScript published = PlutusV3Script.builder().cborHex(HexUtil.encodeHexString(
                CborSerializationUtil.serialize(new co.nstant.in.cbor.model.ByteString(body)))).build();
        assertEquals(LM_COMPOUND_HASH, hashOf(published), "588695…#0 publishes 4713a925…");
        byHash.put(LM_COMPOUND_HASH, published);
        return hash -> Optional.ofNullable(byHash.get(hash));
    }

    // ---- turning recorded and produced outputs into mainnet UTxOs ------------------------------

    /** A UTxO read from a dated JSON fixture, in the shape {@code compound-candidate-e833a769.json} uses. */
    static Utxo utxo(JsonNode n) {
        List<Amount> amounts = new ArrayList<>();
        n.get("amount").forEach(a -> amounts.add(Amount.builder().unit(a.get("unit").asText())
                .quantity(new BigInteger(a.get("quantity").asText())).build()));
        return Utxo.builder().txHash(n.get("txHash").asText()).outputIndex(n.get("outputIndex").asInt())
                .address(n.get("address").asText()).amount(amounts)
                .inlineDatum(n.get("inlineDatum").isNull() ? null : n.get("inlineDatum").asText()).build();
    }

    static JsonNode json(String name) {
        try (InputStream is = EscrowCompoundRig.class.getResourceAsStream("/loans-v4/" + name)) {
            return JSON.readTree(is);
        } catch (Exception e) {
            throw new IllegalStateException("fixture " + name, e);
        }
    }

    /**
     * The output a transaction built under another deployment's credentials BECOMES under mainnet's: same
     * coordinates, value and datum bytes; payment credential replaced; stake part (if any) kept.
     */
    static Utxo onMainnet(String txHash, int index, TransactionOutput output, String mainnetPayment) {
        Address address = new Address(output.getAddress());
        Optional<byte[]> stake = address.getDelegationCredentialHash();
        String retargeted = stake.isPresent()
                ? AddressProvider.getBaseAddress(Credential.fromScript(mainnetPayment),
                        Credential.fromKey(stake.get()), Networks.mainnet()).getAddress()
                : AddressProvider.getEntAddress(Credential.fromScript(mainnetPayment), Networks.mainnet()).getAddress();
        return Utxo.builder().txHash(txHash).outputIndex(index).address(retargeted)
                .amount(ValueUtil.toAmountList(output.getValue()))
                .inlineDatum(output.getInlineDatum() == null ? null : output.getInlineDatum().serializeToHex())
                .build();
    }

    /** {@code utxo} with its value replaced. */
    static Utxo withAmounts(Utxo utxo, List<Amount> amounts) {
        return Utxo.builder().txHash(utxo.getTxHash()).outputIndex(utxo.getOutputIndex())
                .address(utxo.getAddress()).amount(new ArrayList<>(amounts)).inlineDatum(utxo.getInlineDatum())
                .build();
    }

    /**
     * A recorded pool or pool manager re-named for another {@code poolId}: its one NFT under {@code policyId}
     * gets the new asset name, nothing else changes. Neither datum carries the pool id (the pool NFT and the
     * pool-manager NFT are the only places it lives), so this is the whole of the substitution.
     */
    static Utxo renamedNft(Utxo utxo, String policyId, String poolId) {
        List<Amount> amounts = new ArrayList<>();
        int renamed = 0;
        for (Amount a : utxo.getAmount()) {
            if (a.getUnit().startsWith(policyId)) {
                amounts.add(Amount.builder().unit(policyId + poolId).quantity(a.getQuantity()).build());
                renamed++;
            } else {
                amounts.add(a);
            }
        }
        assertEquals(1, renamed, "exactly one NFT under " + policyId);
        assertFalse(utxo.getInlineDatum().contains(poolId), "the datum does not name the pool id");
        return withAmounts(utxo, amounts);
    }

    // ---- primitives ----------------------------------------------------------------------------

    static String fixture(String name) {
        try (InputStream is = EscrowCompoundRig.class.getResourceAsStream("/loans-v4/" + name)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            throw new IllegalStateException("fixture " + name, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shippedLoans() {
        try (InputStream in = EscrowCompoundRig.class.getClassLoader().getResourceAsStream("application.yaml")) {
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
    static String shippedMainnet(String section, String key) {
        return defaultOf(((Map<String, Object>) shippedLoans().get(section)).get(key));
    }

    static String shippedMainnetLoans(String key) {
        return defaultOf(shippedLoans().get(key));
    }

    @SuppressWarnings("unchecked")
    static List<String> shippedCompoundCoordinates() {
        return List.of(defaultOf(((Map<String, Object>) shippedLoans().get("compound")).get("reference-scripts"))
                .split(","));
    }

    static String hashOf(PlutusScript script) {
        try {
            return HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String causeChain(Throwable e) {
        StringBuilder s = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            s.append(t).append(" <- ");
        }
        return s.toString();
    }

    static String paymentCredential(String address) {
        return new Address(address).getPaymentCredentialHash().map(HexUtil::encodeHexString).orElse("");
    }

    private static TransactionOutput outputEchoing(Transaction tx, Utxo input) {
        return tx.getBody().getOutputs().stream()
                .filter(o -> input.getAddress().equals(o.getAddress()))
                .filter(o -> o.getInlineDatum() != null
                        && input.getInlineDatum().equalsIgnoreCase(o.getInlineDatum().serializeToHex()))
                .findFirst().orElseThrow(() -> new AssertionError("no output echoes " + input.getAddress()));
    }

    static Map<String, BigInteger> units(List<Amount> amounts) {
        Map<String, BigInteger> out = new TreeMap<>();
        amounts.forEach(a -> out.merge(a.getUnit(), a.getQuantity(), BigInteger::add));
        return out;
    }

    static Map<String, BigInteger> units(TransactionOutput output) {
        return units(ValueUtil.toAmountList(output.getValue()));
    }
}
