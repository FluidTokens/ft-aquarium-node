package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fluidtokens.aquarium.offchain.config.HashCheckedScriptSupplier;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.ClaimData;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationMode;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.MinswapPoolDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.Rational;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Blueprint;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Deployed;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Feed;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Leg;
import com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Signer;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletShape;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-222: the {@link TestOracleRig} — FluidTokens' oracle code, control-hashed to the deployed mainnet
 * oracles and re-parameterised with keys generated at runtime — proven on its own and through the d832b78e
 * FLDT→ADA convert.
 *
 * <ul>
 *   <li><b>Control hash.</b> Each deployed oracle is its blueprint's code applied to its own parameters, and
 *       the other blueprint or a changed parameter is not. Keyless and offline, so a drift of a deployed hash
 *       or of a blueprint fails on every build.</li>
 *   <li><b>The rewritten registry entry.</b> Only the named fields of one recorded entry move, and production's
 *       {@link FluidOracleClient} reads the result back as the rig's oracle.</li>
 *   <li><b>Threshold controls</b> on the oracle's own withdraw-0: the validator counts valid signatures against
 *       the threshold APPLIED to the script.</li>
 *   <li><b>The convert</b>, through {@link ConvertTransactionBuilder}'s production constructor and the real Aiken
 *       evaluator: liquidates at a rig-signed price that the recorded feed never carried, and each negative
 *       control is refused by the validator it names — by the position of that validator's withdrawal in a
 *       positive control built with the same rig, never by a bare "it failed" ({@link EvalFixtures} harness
 *       limitation 1; CCL trap 14).</li>
 * </ul>
 */
@Slf4j
class TestOracleRigTest {

    private static final Network NETWORK = Networks.mainnet();
    private static final String REGISTRY_PAYLOAD = "/loans-v4/mainnet-oracle-registry-2026-10-01.json";

    private static final String V1_ORACLE_POLICY = "93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b";
    private static final String V2_ORACLE_POLICY = "26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26";
    private static final String NIGHT_ORACLE_NAME = "6f7261636c654e69676874";
    private static final String FLDT_ORACLE_NAME = "6f7261636c65464c44544333";
    private static final AssetType NIGHT_V1 = new AssetType(V1_ORACLE_POLICY, NIGHT_ORACLE_NAME);
    private static final AssetType NIGHT_V2 = new AssetType(V2_ORACLE_POLICY, NIGHT_ORACLE_NAME);
    private static final AssetType FLDT_V1 = new AssetType(V1_ORACLE_POLICY, FLDT_ORACLE_NAME);
    private static final AssetType FLDT_V2 = new AssetType(V2_ORACLE_POLICY, FLDT_ORACLE_NAME);

    private static final String DEPLOYED_FLDT_ORACLE = "d81a8bea722dc9487e7e693487f6948983af45c3f7897bf1d37916c8";

    /** CCL's placeholder ex-units (CCL trap 8): a redeemer still carrying them was never costed. */
    private static final BigInteger PLACEHOLDER = BigInteger.valueOf(10_000);

    // ---- the shipped mainnet deployment (as ConvertProductionWiringTest) -----------------------------

    private static final String CONFIG_POLICY_ID = "235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200";
    private static final String LM_CONFIG_POLICY_ID = "fb6ae2027358b4a0b62710eb95102d87fa13f66ecf55d8943699c492";
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";
    private static final String MS_POOL_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String MS_POOL_SPEND = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MS_ORDER_SPEND = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";

    private static final LoansContractRegistry REGISTRY = new LoansContractRegistry(CONFIG_POLICY_ID,
            LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME, SMART_TOKENS_SPEND, MS_POOL_POLICY, MS_POOL_SPEND,
            MS_ORDER_SPEND);

    // ---- the d832b78e candidate (recorded datums, reconstructed shells: ConvertProductionWiringTest) ---

    private static final String LOAN_TX = "d832b78e3d4a9ff99dfa8f238ae378b37dbd36b30efd24d68e5786f99786cf99";
    private static final int LOAN_IX = 1;
    private static final int BOND_IX = 3;
    private static final String LOAN_ID = "1b6fda505ea9b739e42b5871d274344af37c196ddb70619541a7d06d";
    private static final AssetType FLDT =
            new AssetType("577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454");
    private static final long COLLATERAL_AMOUNT = 100_000_000L;
    private static final long LOAN_LOVELACE = 2_000_000L;
    private static final long BOND_LOVELACE = 2_000_000L;

    /** The 2026-10-01 Config / LMConfig holders and the ADA/FLDT pool: recorded coordinates. */
    private static final TransactionInput CONFIG_REF = new TransactionInput(
            "3d800e98a4da21dc9abcce30c145729406fef7db4d5cd3b4ecd6813aa228a75c", 0);
    private static final TransactionInput LM_CONFIG_REF = new TransactionInput(
            "ab3e3aafe7ea0e6fec24d9ac9249e01edb242dee55aeaa2399da097a21177620", 0);
    private static final TransactionInput POOL_REF = new TransactionInput(
            "665195ca95aac79331ce9d83f2902849999e0f2ba98f663df37191ecda3d03c6", 1);
    private static final String POOL_ADDRESS =
            "addr1z84q0denmyep98ph3tmzwsmw0j7zau9ljmsqx6a4rvaau66j2c79gy9l76sdg0xwhd7r0c0kna0tycz4y5s6mlenh8pq777e2a";

    /** The bot. Synthetic, as in every sibling rig. */
    private static final String BOT_ADDRESS = AddressProvider.getEntAddress(
            Credential.fromKey(HexUtil.decodeHexString("11".repeat(28))), NETWORK).getAddress();
    private static final Utxo WALLET_UTXO = LoanFixtures.adaUtxo("e0".repeat(32), 0, BOT_ADDRESS, 50_000_000L);
    private static final Utxo COLLATERAL_UTXO = LoanFixtures.adaUtxo("c5".repeat(32), 0, BOT_ADDRESS,
            WalletShape.collateralLovelace(EvalFixtures.protocolParams().getProtocolParams()).longValueExact());

    /** The six loans-v4 validators a mainnet convert references (ConvertLiquidationRouter.referenceScripts()). */
    private static final List<PlutusScript> REGISTRY_SCRIPTS = List.of(REGISTRY.getLoanSpendScript(),
            REGISTRY.getLenderManagerSpendScript(), REGISTRY.getLoanScript(), REGISTRY.getLoanClaimActionScript(),
            REGISTRY.getLenderManagerScript(), REGISTRY.getLmLiquidateAndConvertActionScript());

    // ---- the rig's scenario ---------------------------------------------------------------------------

    /**
     * T0: the recorded FLDT v1 {@code validFrom} (1,790,839,501,224) rounded up to the next second, as
     * {@code ConvertProductionWiringTest} anchors its build; re-derived in {@link #anchor()}.
     */
    private static final long T0 = 1_790_839_502_000L;
    private static final BigInteger DENOMINATOR = BigInteger.valueOf(100_000_000L);
    /** Liquidatable with equity > 0 — a path the recorded price 19,837,135 (equity 0) never exercised. */
    private static final BigInteger LIQUIDATABLE_PRICE = BigInteger.valueOf(23_000_000L);
    /** Not liquidatable. */
    private static final BigInteger HEALTHY_PRICE = BigInteger.valueOf(30_000_000L);
    private static final Feed RIG_FEED = new Feed(LIQUIDATABLE_PRICE, DENOMINATOR, T0 - 300_000L, T0 + 1_500_000L);
    private static final long TX_FROM = T0;
    private static final long TX_TO = T0 + 120_000L;

    private static final CardanoConverters CONVERTERS = ClasspathConversionsFactory.createConverters(NetworkType.MAINNET);

    /** A synthetic wallet for the withdraw-0-only builds: it pays the fee and backs the collateral. */
    private static final String WITHDRAW_WALLET = AddressProvider.getEntAddress(
            Credential.fromKey(HexUtil.decodeHexString("ac".repeat(28))), NETWORK).getAddress();
    private static final List<Utxo> WITHDRAW_UNIVERSE = List.of(
            LoanFixtures.adaUtxo("ad".repeat(32), 0, WITHDRAW_WALLET, 50_000_000L),
            LoanFixtures.adaUtxo("ad".repeat(32), 1, WITHDRAW_WALLET, 10_000_000L));

    // ======================================================================================
    // Control hash
    // ======================================================================================

    static Stream<Arguments> deployedOracles() {
        return Stream.of(
                Arguments.of("a0bb657d", Blueprint.AQUARIUM_SC, List.of(NIGHT_V1)),
                Arguments.of("756897fd", Blueprint.LOANS_V4, List.of(NIGHT_V2)),
                Arguments.of("d81a8bea", Blueprint.LOANS_V4, List.of(FLDT_V1, FLDT_V2)));
    }

    /**
     * (a) Each deployed mainnet oracle is its blueprint's {@code oracle.oracle.withdraw} applied to the eight
     * parameters extracted from its own applied script, and those parameters' keys and threshold are the ones
     * every registry entry withdrawing from that hash publishes.
     */
    @ParameterizedTest(name = "{0} from {1}")
    @MethodSource("deployedOracles")
    void eachDeployedMainnetOracleIsItsBlueprintAppliedToItsOwnParameters(String hash8, Blueprint blueprint,
                                                                         List<AssetType> nfts) throws Exception {
        Deployed deployed = TestOracleRig.controlHashed(hash8, blueprint);

        assertTrue(deployed.scriptHash().startsWith(hash8), deployed.scriptHash());
        assertEquals(blueprint.parameterTitles().size(), deployed.parameters().size());
        assertEquals(deployed.scriptHash(), TestOracleRig.hashOf(blueprint, deployed.parameters()));

        FluidOracleClient registry = OracleClients.mainnetTwoVersions();
        Set<AssetType> withdrawingFromIt = registry.entries().stream()
                .filter(e -> deployed.scriptHash().equals(e.withdrawCredentialHash()))
                .map(OracleEntry::oracleToken)
                .collect(Collectors.toSet());
        assertEquals(Set.copyOf(nfts), withdrawingFromIt,
                hash8 + ": the registry entries withdrawing from the deployed oracle");
        for (AssetType nft : nfts) {
            OracleEntry entry = registry.findEntryByOracleToken(nft)
                    .orElseThrow(() -> new AssertionError("no registry entry for " + nft.toUnit()));
            assertEquals(lower(entry.verificationKeys()), deployed.verificationKeysHex(),
                    hash8 + ": the applied verification_keys are the keys " + nft.toUnit() + " publishes");
            assertEquals(entry.threshold(), deployed.threshold(),
                    hash8 + ": the applied threshold is the one " + nft.toUnit() + " publishes");
        }
    }

    /**
     * (b) The control hash refuses the OTHER blueprint — loudly, naming both hashes and the blueprint — and,
     * with the right blueprint, a threshold one higher or one flipped byte of the verification key is a
     * different script.
     */
    @Test
    void theControlHashRefusesTheOtherBlueprintAndAChangedParameter() {
        Map<String, Blueprint> right = new LinkedHashMap<>();
        right.put("a0bb657d", Blueprint.AQUARIUM_SC);
        right.put("756897fd", Blueprint.LOANS_V4);
        right.put("d81a8bea", Blueprint.LOANS_V4);
        for (Map.Entry<String, Blueprint> e : right.entrySet()) {
            String hash8 = e.getKey();
            Blueprint other = e.getValue() == Blueprint.LOANS_V4 ? Blueprint.AQUARIUM_SC : Blueprint.LOANS_V4;
            Deployed deployed = TestOracleRig.controlHashed(hash8, e.getValue());
            String otherHash = TestOracleRig.hashOf(other, TestOracleRig.appliedParameters(
                    LoanFixtures.fixture("mainnet-oracle-script-" + hash8 + ".hex"), other));
            assertNotEquals(deployed.scriptHash(), otherHash, hash8 + ": the two blueprints' code is the same");

            AssertionError refused = assertThrows(AssertionError.class,
                    () -> TestOracleRig.controlHashed(hash8, other),
                    hash8 + ": the control hash accepted " + other);
            assertTrue(refused.getMessage().contains(deployed.scriptHash()),
                    "the refusal names the deployed hash: " + refused.getMessage());
            assertTrue(refused.getMessage().contains(otherHash),
                    "the refusal names the reproduced hash: " + refused.getMessage());
            assertTrue(refused.getMessage().contains(other.name()),
                    "the refusal names the blueprint: " + refused.getMessage());

            List<PlutusData> higherThreshold = new ArrayList<>(deployed.parameters());
            higherThreshold.set(1, BigIntPlutusData.of(deployed.threshold() + 1));
            assertNotEquals(deployed.scriptHash(), TestOracleRig.hashOf(e.getValue(), higherThreshold),
                    hash8 + ": threshold + 1 is the same script");

            assertEquals(1, deployed.verificationKeysHex().size(), hash8 + ": a single verification key");
            byte[] key = HexUtil.decodeHexString(deployed.verificationKeysHex().get(0));
            key[0] ^= 0x01;
            ListPlutusData flippedKeys = ListPlutusData.builder().build();
            flippedKeys.add(BytesPlutusData.of(key));
            List<PlutusData> flipped = new ArrayList<>(deployed.parameters());
            flipped.set(0, flippedKeys);
            assertNotEquals(deployed.scriptHash(), TestOracleRig.hashOf(e.getValue(), flipped),
                    hash8 + ": a flipped key byte is the same script");
        }
    }

    // ======================================================================================
    // The rewritten registry entry
    // ======================================================================================

    /**
     * (c) The rig rewrites only {@link TestOracleRig#REWRITTEN_FIELDS} of the ONE recorded FLDT v1 entry; every
     * other NFT parses exactly as the plain registry; and production's reader turns the rewritten entry into the
     * rig's oracle — the rig's credential, keys and threshold, the signed feed, usable for liquidation.
     */
    @Test
    void theRigRewritesOnlyTheNamedFieldsOfOneRecordedEntry() throws Exception {
        TestOracleRig rig = TestOracleRig.of(TestOracleRig.controlHashed("d81a8bea", Blueprint.LOANS_V4), 3);
        Leg leg = rig.leg(REGISTRY_PAYLOAD, FLDT_V1, RIG_FEED, List.of(Signer.at(0)), NETWORK);

        Set<String> differing = new TreeSet<>();
        differingPaths(leg.recordedEntry(), leg.rewrittenEntry(), "", differing);
        log.info("FAB-222 rig entry: rewritten fields {}", differing);
        assertTrue(TestOracleRig.REWRITTEN_FIELDS.containsAll(differing),
                "fields rewritten outside REWRITTEN_FIELDS: " + differing);
        assertTrue(differing.contains("multisigOracle.publicKeys"), "the keys were not rewritten: " + differing);
        assertTrue(differing.contains("supportedOracle.multisig.multisigOracle.signatures"),
                "the signatures were not rewritten: " + differing);

        FluidOracleClient plain = OracleClients.mainnetTwoVersions();
        assertEquals(plain.entries().size(), leg.client().entries().size(), "the rig's payload lost or gained an entry");
        int others = 0;
        for (OracleEntry recorded : plain.entries()) {
            if (recorded.oracleToken().equals(FLDT_V1)) {
                continue;
            }
            assertEquals(Optional.of(recorded), leg.client().findEntryByOracleToken(recorded.oracleToken()),
                    "the rig moved another oracle's entry: " + recorded.oracleToken().toUnit());
            others++;
        }
        assertEquals(plain.entries().size() - 1, others);

        OracleEntry recorded = plain.findEntryByOracleToken(FLDT_V1).orElseThrow();
        OracleEntry entry = leg.entry();
        assertEquals(recorded.token(), entry.token(), "the priced token is the recorded one");
        assertEquals(FLDT, entry.token());
        assertEquals(FLDT_V1, entry.oracleToken());
        assertEquals(recorded.oracleVersion(), entry.oracleVersion());
        assertEquals(rig.scriptHash(), entry.withdrawCredentialHash(), "the oracle withdraws from the rig's script");
        assertNotEquals(DEPLOYED_FLDT_ORACLE, entry.withdrawCredentialHash());
        assertEquals(rig.publicKeysHex(), lower(entry.verificationKeys()), "the rig's applied keys");
        assertEquals(rig.threshold(), entry.threshold());
        assertEquals(leg.signedFeed(), entry.feed(), "the parsed feed is exactly the signed one");
        assertTrue(entry.usableForLiquidation(), "the rig's entry is not usable for liquidation");
        assertEquals(new TransactionInput(leg.referenceInputUtxo().getTxHash(), leg.referenceInputUtxo().getOutputIndex()),
                entry.referenceInput());
        assertEquals(new TransactionInput(leg.referenceScriptUtxo().getTxHash(), leg.referenceScriptUtxo().getOutputIndex()),
                entry.referenceScript());
    }

    // ======================================================================================
    // Withdraw-0-only threshold controls
    // ======================================================================================

    /** (d) k of n at the deployed threshold (1): accepted, with real ex-units on the BUILT transaction. */
    @Test
    void kOfNAtTheDeployedThresholdIsAccepted() throws Exception {
        Deployed base = TestOracleRig.controlHashed("d81a8bea", Blueprint.LOANS_V4);
        assertEquals(1, base.threshold(), "the deployed FLDT oracle's threshold");
        TestOracleRig rig = TestOracleRig.of(base, 3);
        assertEquals(1, rig.threshold());

        assertAcceptedWithRealExUnits(withdrawZero(rig, List.of(Signer.at(0))), "threshold 1, k=1");
    }

    /** (e) Below the deployed threshold: no signature at all is refused by the oracle script itself. */
    @Test
    void belowTheDeployedThresholdIsRefusedByTheScript() throws Exception {
        TestOracleRig rig = TestOracleRig.of(TestOracleRig.controlHashed("d81a8bea", Blueprint.LOANS_V4), 3);
        Exception refused = assertThrows(Exception.class, () -> withdrawZero(rig, List.of()),
                "threshold 1, k=0 must be refused");
        assertRefusedAt(refused, 0, "threshold 1, k=0");
    }

    /** (f) The threshold APPLIED to the script is the one the validator counts against. */
    @Test
    void theAppliedThresholdIsHonoured() throws Exception {
        TestOracleRig rig = TestOracleRig.of(TestOracleRig.controlHashed("d81a8bea", Blueprint.LOANS_V4), 3, 2);
        assertEquals(2, rig.threshold());
        Exception refused = assertThrows(Exception.class, () -> withdrawZero(rig, List.of(Signer.at(0))),
                "threshold 2, k=1 must be refused");
        assertRefusedAt(refused, 0, "threshold 2, k=1");

        assertAcceptedWithRealExUnits(withdrawZero(rig, List.of(Signer.at(0), Signer.at(1))), "threshold 2, k=2");
    }

    // ======================================================================================
    // The d832b78e convert through the rig's oracle
    // ======================================================================================

    /**
     * (g) The d832b78e FLDT→ADA convert, at a price the recorded feed never carried, built through the production
     * constructor and evaluated by the real validators — the rig's oracle reached by reference only, at the
     * credential the loan side resolves its redeemer by.
     */
    @Test
    void theD832b78eConvertLiquidatesThroughTheRigOracle() throws Exception {
        TestOracleRig rig = TestOracleRig.of(TestOracleRig.controlHashed("d81a8bea", Blueprint.LOANS_V4), 3);
        Convert c = convert(rig, RIG_FEED, RIG_FEED, List.of(Signer.at(0)), TX_FROM, TX_TO);

        assertFalse(LoanFinance.isRepaymentLate(c.loan, T0), "d832b78e is never late (perpetual)");
        assertTrue(liquidatable(c, c.leg.signedFeed()), "the loan is liquidatable at the rig's feed");
        assertTrue(c.equity.signum() > 0, "equity at the rig's feed is positive: " + c.equity);

        Transaction reread = Transaction.deserialize(build(c, rig).serialize());
        List<Redeemer> redeemers = reread.getWitnessSet().getRedeemers();
        assertEquals(8, redeemers.size(), "two spends, one burn, five withdrawals (the rig oracle's included)");
        for (Redeemer redeemer : redeemers) {
            String key = redeemer.getTag() + "#" + redeemer.getIndex();
            assertTrue(redeemer.getExUnits().getMem().compareTo(PLACEHOLDER) > 0
                            && redeemer.getExUnits().getSteps().compareTo(PLACEHOLDER) > 0,
                    key + " carries CCL's placeholder ex-units: " + redeemer.getExUnits());
        }
        log.info("FAB-222 rig convert: fee {}, equity {}, oracle withdrawal #{} {}", reread.getBody().getFee(),
                c.equity, withdrawalIndex(reread, rig.scriptHash()), rig);

        assertFalse(witnessedScriptHashes(reread.getWitnessSet()).contains(rig.scriptHash()),
                "the rig's oracle script travels in the witness set");
        TransactionInput scriptRef = c.leg.entry().referenceScript();
        assertTrue(reread.getBody().getReferenceInputs().contains(scriptRef),
                "the rig's reference-script UTxO is not a reference input");
        assertEquals(rig.scriptHash(), utxoAt(c.universe, scriptRef).getReferenceScriptHash(),
                "the reference-script UTxO does not publish the rig's script hash");

        // H1: the NFT holder's payment credential is the delegation credential of one body withdrawal.
        String nftUnit = c.leg.entry().oracleToken().toUnit();
        List<String> holderCredentials = new ArrayList<>();
        for (TransactionInput input : reread.getBody().getReferenceInputs()) {
            Utxo utxo = utxoAt(c.universe, input);
            if (utxo.getAmount().stream().anyMatch(a -> nftUnit.equals(a.getUnit()))) {
                holderCredentials.add(HexUtil.encodeHexString(
                        new Address(utxo.getAddress()).getPaymentCredentialHash().orElseThrow()));
            }
        }
        assertEquals(List.of(rig.scriptHash()), holderCredentials,
                "exactly one reference input holds the oracle NFT, at the rig's credential");
        Set<String> withdrawing = new HashSet<>();
        for (Withdrawal w : reread.getBody().getWithdrawals()) {
            withdrawing.add(HexUtil.encodeHexString(new Address(w.getRewardAddress()).getDelegationCredentialHash()
                    .orElseThrow()));
        }
        assertTrue(withdrawing.contains(holderCredentials.get(0)),
                "no body withdrawal is keyed to the NFT holder's credential " + holderCredentials.get(0));
    }

    /**
     * (h) A transaction window ending past the feed's {@code valid_to}: the oracle still accepts its own
     * signatures, and {@code loan_claim_action} refuses ({@code valid_to >= txValidTo}).
     */
    @Test
    void aTxWindowPastTheFeedsValidToIsRefusedByLoanClaimAction() throws Exception {
        TestOracleRig rig = TestOracleRig.of(TestOracleRig.controlHashed("d81a8bea", Blueprint.LOANS_V4), 3);
        int claimAction = positiveControlIndex(rig, REGISTRY.getLoanClaimActionScriptHash());

        long txTo = RIG_FEED.validTo() + 60_000L;
        Convert c = convert(rig, RIG_FEED, RIG_FEED, List.of(Signer.at(0)), TX_FROM, txTo);
        assertTrue(c.leg.signedFeed().validTo() < txTo, "the tx window must end past the feed's valid_to");
        Exception refused = assertThrows(Exception.class, () -> build(c, rig),
                "a tx window past the feed's valid_to must be refused");
        assertRefusedAt(refused, claimAction, "tx validTo = feed validTo + 60 s (loan_claim_action)");
    }

    /** (i) A signature by a key that is NOT applied, claiming position 0: refused by the rig's oracle. */
    @Test
    void aSignatureFromAKeyNotAppliedIsRefusedByTheOracle() throws Exception {
        TestOracleRig rig = TestOracleRig.of(TestOracleRig.controlHashed("d81a8bea", Blueprint.LOANS_V4), 3);
        int oracle = positiveControlIndex(rig, rig.scriptHash());

        Convert c = convert(rig, RIG_FEED, RIG_FEED, List.of(Signer.outsiderAt(0)), TX_FROM, TX_TO);
        assertTrue(c.leg.entry().usableForLiquidation(), "the outsider's signature resolves to position 0");
        Exception refused = assertThrows(Exception.class, () -> build(c, rig),
                "a signature by a key not applied must be refused");
        assertRefusedAt(refused, oracle, "outsider key at position 0 (the rig's oracle)");
    }

    /**
     * (j) A healthy price, correctly signed, while the plan and the claim stay at the liquidatable one: the
     * oracle accepts it and {@code loan_claim_action} refuses the claim.
     */
    @Test
    void aHealthyPriceReachesLoanClaimActionAndIsRefused() throws Exception {
        TestOracleRig rig = TestOracleRig.of(TestOracleRig.controlHashed("d81a8bea", Blueprint.LOANS_V4), 3);
        int claimAction = positiveControlIndex(rig, REGISTRY.getLoanClaimActionScriptHash());

        Feed healthy = new Feed(HEALTHY_PRICE, DENOMINATOR, RIG_FEED.validFrom(), RIG_FEED.validTo());
        Convert c = convert(rig, healthy, RIG_FEED, List.of(Signer.at(0)), TX_FROM, TX_TO);
        assertFalse(liquidatable(c, c.leg.signedFeed()), "the loan is liquidatable at the healthy signed price");
        Exception refused = assertThrows(Exception.class, () -> build(c, rig),
                "a claim at a healthy signed price must be refused");
        assertRefusedAt(refused, claimAction, "healthy signed price (loan_claim_action)");
    }

    /** T0 is the recorded FLDT v1 feed's {@code validFrom} rounded up to the next second. */
    @Test
    void theAnchorIsTheRecordedFeedsValidFromRoundedUp() throws Exception {
        assertEquals(T0, anchor());
    }

    private static long anchor() throws Exception {
        OraclePriceFeed recorded = OracleClients.mainnetTwoVersions().findEntryByOracleToken(FLDT_V1).orElseThrow().feed();
        return (recorded.validFrom() / 1000L + 1L) * 1000L;
    }

    // ======================================================================================
    // The rig's leaves
    // ======================================================================================

    /** The rig oracle's withdraw-0 on the FLDT v1 leg, built with the real evaluator wired in, strictly. */
    private static Transaction withdrawZero(TestOracleRig rig, List<Signer> signers) {
        Leg leg = rig.leg(REGISTRY_PAYLOAD, FLDT_V1, RIG_FEED, signers, NETWORK);
        PlutusScript script = rig.script();
        var supplier = LoanFixtures.utxoSupplier(WITHDRAW_UNIVERSE);
        var evaluator = new AikenTransactionEvaluator(supplier, EvalFixtures.protocolParams(),
                hash -> Optional.of(script), SlotConfigs.mainnet());
        ScriptTx tx = new ScriptTx()
                .withdraw(leg.entry().rewardAddress(), BigInteger.ZERO,
                        LiquidationTxEncoder.oracleRedeemer(leg.entry().feed(), leg.entry().signatures()), WITHDRAW_WALLET)
                .attachRewardValidator(script);
        return new QuickTxBuilder(supplier, EvalFixtures.protocolParams(), (ScriptSupplier) hash -> Optional.of(script),
                (TransactionProcessor) null)
                .compose(tx)
                .feePayer(WITHDRAW_WALLET)
                .collateralPayer(WITHDRAW_WALLET)
                .withTxEvaluator(evaluator)
                // ⛔ FALSE: a refused redeemer must fail the build, never ship placeholders (CCL trap 8).
                .ignoreScriptCostEvaluationError(false)
                .build();
    }

    private static void assertAcceptedWithRealExUnits(Transaction built, String label) throws Exception {
        Transaction reread = Transaction.deserialize(built.serialize());
        List<Redeemer> rewards = reread.getWitnessSet().getRedeemers().stream()
                .filter(r -> r.getTag() == RedeemerTag.Reward).toList();
        assertEquals(1, rewards.size(), label + ": one withdraw redeemer");
        Redeemer reward = rewards.get(0);
        assertTrue(reward.getExUnits().getMem().compareTo(PLACEHOLDER) > 0
                        && reward.getExUnits().getSteps().compareTo(PLACEHOLDER) > 0,
                label + ": ex-units are placeholders, so nothing was evaluated: " + reward.getExUnits());
    }

    /**
     * The refusal came from the SCRIPT whose withdrawal sits at {@code index}: the evaluator's
     * {@code RedeemerError} for that withdraw purpose, somewhere in the cause chain.
     */
    private static void assertRefusedAt(Throwable refused, int index, String label) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = refused; t != null; t = t.getCause()) {
            chain.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
            if (t.getCause() == t) {
                break;
            }
        }
        String expected = "RedeemerError { tag: \"Withdraw\", index: " + index + ",";
        assertTrue(chain.toString().contains(expected),
                label + ": not refused by the withdrawal at index " + index + " (" + expected + "): " + chain);
    }

    /**
     * The position of {@code credentialHash}'s withdrawal in a positive control built with {@code rig} — the
     * index the evaluator names that withdrawal's redeemer by. Never a constant: the rig's random keys move
     * its own credential, and with it every position.
     */
    private static int positiveControlIndex(TestOracleRig rig, String credentialHash) throws Exception {
        Convert control = convert(rig, RIG_FEED, RIG_FEED, List.of(Signer.at(0)), TX_FROM, TX_TO);
        return withdrawalIndex(Transaction.deserialize(build(control, rig).serialize()), credentialHash);
    }

    private static int withdrawalIndex(Transaction transaction, String credentialHash) {
        List<Withdrawal> withdrawals = transaction.getBody().getWithdrawals();
        int found = -1;
        for (int i = 0; i < withdrawals.size(); i++) {
            String credential = HexUtil.encodeHexString(new Address(withdrawals.get(i).getRewardAddress())
                    .getDelegationCredentialHash().orElseThrow());
            if (credential.equals(credentialHash)) {
                assertEquals(-1, found, "two withdrawals keyed to " + credentialHash);
                found = i;
            }
        }
        assertTrue(found >= 0, "no withdrawal keyed to " + credentialHash);
        return found;
    }

    private static boolean liquidatable(Convert c, OraclePriceFeed feed) {
        LiquidationMode.Liquidation liquidation = (LiquidationMode.Liquidation) c.loan.liquidationMode();
        return LoanFinance.canLiquidate(Rational.fromInt(c.remainingDebt),
                Rational.fromInt(BigInteger.valueOf(COLLATERAL_AMOUNT)), LoanFinance.liquidationLtv(liquidation),
                OraclePriceFeed.unit(), feed);
    }

    /** {@code ConvertTransactionBuilder}'s production constructor, with only its leaves supplied. */
    private static Transaction build(Convert c, TestOracleRig rig) throws Exception {
        ScriptSupplier scripts = EvalFixtures.scriptSupplier(REGISTRY, List.of(rig.script()));
        AikenTransactionEvaluator evaluator = new AikenTransactionEvaluator(LoanFixtures.utxoSupplier(c.universe),
                EvalFixtures.protocolParams(), scripts, SlotConfigs.mainnet());
        return new ConvertTransactionBuilder(REGISTRY, NETWORK, LoanFixtures.utxoSupplier(c.universe),
                EvalFixtures.protocolParams(), new HashCheckedScriptSupplier(scripts), evaluator).build(c.request);
    }

    private record Convert(List<Utxo> universe, ConvertTransactionBuilder.Request request, LoanDatum loan,
                           BigInteger remainingDebt, BigInteger equity, Leg leg) {
    }

    /**
     * {@code ConvertProductionWiringTest.fixture}, except that the oracle entry and its two UTxOs are a rig
     * {@link Leg} signed at {@code signed}, the plan and the claim are computed at {@code planned}, and the
     * transaction window is {@code [txFromMillis, txToMillis]}.
     */
    private static Convert convert(TestOracleRig rig, Feed signed, Feed planned, List<Signer> signers,
                                   long txFromMillis, long txToMillis) throws Exception {
        String loanDatumHex = LoanFixtures.fixture("mainnet-loan-datum-d832b78e.hex");
        String bondDatumHex = LoanFixtures.fixture("mainnet-lender-bond-datum-d832b78e.hex");
        String poolDatumHex = LoanFixtures.fixture("mainnet-minswap-pool-ada-fldt.hex");
        LoanDatum loan = new LoanDatumConverter().deserialize(loanDatumHex);
        LenderManagerDatum bond = new LenderManagerDatumConverter().deserialize(bondDatumHex);
        MinswapPoolDatum pool = new MinswapPoolDatumConverter().deserialize(poolDatumHex);

        AssetType oracleNft = loan.collateral().oracleTokenAsset();
        assertEquals(FLDT_V1, oracleNft, "the loan names the FLDT v1 oracle");
        Leg leg = rig.leg(REGISTRY_PAYLOAD, oracleNft, signed, signers, NETWORK);
        OracleEntry entry = leg.entry();
        assertEquals(FLDT, entry.token());
        OraclePriceFeed planFeed = OraclePriceFeed.aggregated(entry.token(), planned.priceInLovelaces(),
                planned.priceDenominator(), planned.validFrom(), planned.validTo());

        long validFromSlot = CONVERTERS.time().toSlot(LocalDateTime.ofEpochSecond(txFromMillis / 1000L, 0, ZoneOffset.UTC));
        long validToSlot = CONVERTERS.time().toSlot(LocalDateTime.ofEpochSecond(txToMillis / 1000L, 0, ZoneOffset.UTC));

        BigInteger collateralAmount = BigInteger.valueOf(COLLATERAL_AMOUNT);
        BigInteger remainingDebt = LoanFinance.remainingDebt(loan, txFromMillis);
        LiquidationMode.Liquidation liquidation = (LiquidationMode.Liquidation) loan.liquidationMode();
        BigInteger equity = LoanFinance.redeemerEquity(liquidation, Rational.fromInt(collateralAmount),
                Rational.fromInt(remainingDebt), OraclePriceFeed.unit(), planFeed);

        AssetType lenderBond = new AssetType(REGISTRY.getLenderBondPolicyId(), LOAN_ID);
        ConvertOrderPlan plan = ConvertOrderPlan.plan(FLDT, AssetType.ada(), collateralAmount, equity,
                remainingDebt, bond.liquidationFeePerMille().longValueExact(),
                bond.shouldLiquidationConvertToPrincipal(), liquidation.equityInPrincipalCurrency(),
                pool, MS_POOL_POLICY, lenderBond, bond.lenderAuth(),
                ConvertTxEncoder.plainScriptAddress(REGISTRY.getAssetManagerSpendScriptHash()),
                LOAN_TX, LOAN_IX);
        ClaimData claim = new ClaimData(liquidation, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO,
                bond.lenderAuth(), equity, LOAN_ID, remainingDebt);

        Utxo loanUtxo = LoanFixtures.utxo(LOAN_TX, LOAN_IX, entAddress(REGISTRY.getLoanSpendScriptHash()),
                List.of(Amount.lovelace(BigInteger.valueOf(LOAN_LOVELACE)),
                        Amount.asset(FLDT.toUnit(), collateralAmount),
                        Amount.asset(REGISTRY.getLoanPolicyId() + LOAN_ID, BigInteger.ONE)), loanDatumHex);
        Utxo bondUtxo = LoanFixtures.utxo(LOAN_TX, BOND_IX, entAddress(REGISTRY.getLenderManagerSpendScriptHash()),
                List.of(Amount.lovelace(BigInteger.valueOf(BOND_LOVELACE)),
                        Amount.asset(REGISTRY.getLenderBondPolicyId() + LOAN_ID, BigInteger.ONE)), bondDatumHex);
        Utxo configUtxo = LoanFixtures.utxo(CONFIG_REF.getTransactionId(), CONFIG_REF.getIndex(),
                entAddress(CONFIG_POLICY_ID), List.of(Amount.lovelace(BigInteger.valueOf(5_000_000L)),
                        Amount.asset(CONFIG_POLICY_ID + CONFIG_ASSET_NAME, BigInteger.ONE)),
                LoanFixtures.fixture("mainnet-config-datum-2026-10-01.hex"));
        Utxo lmConfigUtxo = LoanFixtures.utxo(LM_CONFIG_REF.getTransactionId(), LM_CONFIG_REF.getIndex(),
                entAddress(LM_CONFIG_POLICY_ID), List.of(Amount.lovelace(BigInteger.valueOf(5_000_000L)),
                        Amount.asset(LM_CONFIG_POLICY_ID + CONFIG_ASSET_NAME, BigInteger.ONE)),
                LoanFixtures.fixture("mainnet-lm-config-datum-2026-10-01.hex"));
        Utxo poolUtxo = LoanFixtures.utxo(POOL_REF.getTransactionId(), POOL_REF.getIndex(), POOL_ADDRESS,
                List.of(Amount.lovelace(pool.reserveA()), Amount.asset(FLDT.toUnit(), pool.reserveB()),
                        Amount.asset(MS_POOL_POLICY + ConvertTxEncoder.POOL_NFT_ASSET_NAME, BigInteger.ONE)),
                poolDatumHex);

        List<Utxo> universe = new ArrayList<>(List.of(loanUtxo, bondUtxo, configUtxo, lmConfigUtxo, poolUtxo,
                WALLET_UTXO, COLLATERAL_UTXO));
        // The rig oracle's NFT holder and reference-script UTxO: the script's hash, never its bytes.
        universe.addAll(leg.utxos());
        Map<String, TransactionInput> referenceScripts = new LinkedHashMap<>();
        for (int i = 0; i < REGISTRY_SCRIPTS.size(); i++) {
            String hash = hashOf(REGISTRY_SCRIPTS.get(i));
            TransactionInput at = new TransactionInput(String.format("a%d", i + 1).repeat(32), 0);
            referenceScripts.put(hash, at);
            universe.add(Utxo.builder().txHash(at.getTransactionId()).outputIndex(at.getIndex())
                    .address(entAddress(hash))
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                    .referenceScriptHash(hash).build());
        }

        String orderAddress = ConvertLiquidationRouter.minswapOrderAddress(MS_ORDER_SPEND,
                bond.lenderStakeCredential(), LOAN_ID, NETWORK);
        var request = new ConvertTransactionBuilder.Request(loanUtxo, bondUtxo, poolUtxo, entry, configUtxo,
                lmConfigUtxo, WALLET_UTXO, referenceScripts, plan, claim, FLDT, lenderBond, loan.repaymentReceipts(),
                orderAddress, BOT_ADDRESS, validFromSlot, validToSlot, COLLATERAL_UTXO, AssetType.ada(), null);
        return new Convert(universe, request, loan, remainingDebt, equity, leg);
    }

    private static Utxo utxoAt(List<Utxo> universe, TransactionInput input) {
        return universe.stream()
                .filter(u -> u.getTxHash().equals(input.getTransactionId()) && u.getOutputIndex() == input.getIndex())
                .findFirst()
                .orElseThrow(() -> new AssertionError("an input outside the universe: " + input));
    }

    /** Minimal dotted paths at which two JSON trees differ; arrays and scalars are compared whole. */
    private static void differingPaths(JsonNode a, JsonNode b, String prefix, Set<String> out) {
        if (a != null && b != null && a.isObject() && b.isObject()) {
            Set<String> names = new TreeSet<>();
            a.fieldNames().forEachRemaining(names::add);
            b.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                differingPaths(a.get(name), b.get(name), prefix.isEmpty() ? name : prefix + "." + name, out);
            }
        } else if (a == null || !a.equals(b)) {
            out.add(prefix);
        }
    }

    private static List<String> lower(List<String> hex) {
        return hex.stream().map(String::toLowerCase).toList();
    }

    private static String entAddress(String scriptHash) {
        return AddressProvider.getEntAddress(Credential.fromScript(HexUtil.decodeHexString(scriptHash)), NETWORK)
                .getAddress();
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
