package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenTransactionEvaluator;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.TransactionEvaluator;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.model.loans.LenderManagerDatum;
import com.fluidtokens.aquarium.offchain.model.loans.Loan;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.wallet.WalletShape;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.fluidtokens.aquarium.offchain.service.loans.TestOracleRig.Blueprint.AQUARIUM_SC;

/**
 * FAB-225 — three recorded mainnet loans with STRIKE collateral and a USDM principal, wired for a
 * token-principal pay-in-advance through {@link LiquidatePayInAdvanceTransactionBuilder}'s production
 * constructor and two {@link TestOracleRig} oracles. Test-only; no {@code @Test} here.
 *
 * <h2>What is recorded and what is not</h2>
 * Every DATUM and both deployed oracle scripts are recorded mainnet bytes, and so are the loan, bond and
 * config UTxOs' coordinates, addresses and values ({@code mainnet-strike-usdm-pay-in-advance.PROVENANCE.md}).
 * The registry is the shipped mainnet deployment ({@code ConvertProductionWiringTest}'s seven coordinates).
 * Synthetic: the bot's wallet, the six registry reference-script UTxOs (synthetic coordinates carrying the
 * derived hashes), and both oracle legs, which come from the rig.
 *
 * <h2>Why the prices come from the rig</h2>
 * No recorded signed feed makes these loans liquidatable: at the 2026-10-01 STRIKE price
 * (233,067,799 / 10⁸) loan #0's LTV is under 100/125. A signed feed cannot be edited, so each leg is the
 * deployed oracle's code — control-hashed to {@code d97da4f3} (STRIKE v1) and {@code 3a561e40} (USDM v1)
 * from {@code aquarium-sc.plutus.json} — re-parameterised with runtime keys, at a price this class
 * chooses. Each rig script reaches the transaction only as a reference script served by the script
 * supplier; the universe holds its hash, never its bytes.
 *
 * <h2>Instants</h2>
 * {@link #T0} is 600 s after the block that re-created the loan UTxOs. Every millisecond here is a whole
 * second, so it converts to a mainnet slot exactly.
 */
final class StrikeUsdmFixture {

    private StrikeUsdmFixture() {
    }

    static final Network NETWORK = Networks.mainnet();

    // ---- the shipped mainnet deployment (ConvertProductionWiringTest's coordinates) -----------------

    static final String CONFIG_POLICY_ID = "235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200";
    static final String LM_CONFIG_POLICY_ID = "fb6ae2027358b4a0b62710eb95102d87fa13f66ecf55d8943699c492";
    static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";
    private static final String MS_POOL_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String MS_POOL_SPEND = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MS_ORDER_SPEND = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";

    static final LoansContractRegistry REGISTRY = new LoansContractRegistry(CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID,
            CONFIG_ASSET_NAME, SMART_TOKENS_SPEND, MS_POOL_POLICY, MS_POOL_SPEND, MS_ORDER_SPEND);

    // ---- the assets ---------------------------------------------------------------------------------

    static final AssetType STRIKE = new AssetType("f13ac4d66b3ee19a6aa0f2a22298737bd907cc95121662fc971b5275",
            "535452494b45");
    static final AssetType USDM = new AssetType("c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad",
            "0014df105553444d");
    /** The collateral oracle NFT every recorded loan datum names: v1 "oracleSTRIKE". */
    static final AssetType STRIKE_ORACLE_NFT = new AssetType("93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b",
            "6f7261636c65535452494b45");
    /** The principal oracle NFT every recorded loan datum names: v1 "oracleUSDMC3". */
    static final AssetType USDM_ORACLE_NFT = new AssetType("93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b",
            "6f7261636c655553444d4333");

    /** The deployed STRIKE v1 oracle (registry entry 7) and USDM v1 oracle (entry 4) the rigs are control-hashed to. */
    static final String STRIKE_ORACLE_HASH = "d97da4f3f2c3757971332b9bfb3c46df18715b059e6ab0fe66ddb036";
    static final String USDM_ORACLE_HASH = "3a561e401144af4f5dfeeb28ad7fe16b33e656ad5099a93e434928a4";

    static final String REGISTRY_PAYLOAD = "/loans-v4/mainnet-oracle-registry-2026-10-01.json";

    // ---- the recorded loans (Koios, unspent 2026-11-12) ---------------------------------------------

    /** Re-created 2026-11-10 14:20:06Z, block 14,182,796; outputs #0, #1, #2 are the three loans. */
    static final String LOAN_TX = "8554c4d188ee1349d75a0cf3d4b8421e055d84de186ee994abaf43c2bc5ee939";
    /** 2026-09-25 15:08:44Z, block 13,986,878; outputs #9, #10, #11 are the bonds of loans #0, #1, #2. */
    static final String BOND_TX = "165d232288e590fae56e8565c6c191b9572916bf6032d2a60ef163c40c10b496";
    static final List<Integer> LOAN_INDEXES = List.of(0, 1, 2);
    static final List<Integer> BOND_INDEXES = List.of(9, 10, 11);

    /** Payment credential {@code b5763e3c…}, the registry's loan spend. */
    static final String LOAN_ADDRESS =
            "addr1zx6hv03unjmlrgsk0a60wm9kxhfy6yu2fnzul8v8vkwxvtfce6fgzplu6he2pqf5qych0lywt7eydcz7vw7p5n2nkhhs29nd6a";
    /** Payment credential {@code 6743f4b6…}, the registry's lender-manager spend; one stake key per lender bond. */
    static final List<String> BOND_ADDRESSES = List.of(
            "addr1z9n58a9kj3rtfcrxe7ufm23aq8hsgxmdyerfdzvnhexwcz22vs8kxsfh3xy66ndw8yz4xh4j2xw3s0q449um7uqtcxtqkrt52s",
            "addr1z9n58a9kj3rtfcrxe7ufm23aq8hsgxmdyerfdzvnhexwczff488uagw68fv50kjxv3wrx38829tay6zszthnccsradgqvya8cr",
            "addr1z9n58a9kj3rtfcrxe7ufm23aq8hsgxmdyerfdzvnhexwcz2jeyv3rqufwy3qpnhlcz44nzfrf2dagzv54lfsqx3du86q9qvfuc");
    /** The asset name of each loan's NFT and of its lender bond. */
    static final List<String> LOAN_IDS = List.of(
            "086c06fdfb0b700be5070c30a2bca96a60472304cda09e2e2f37cbbe",
            "eff57e8c18494ecc8b287b8dd4ed019284d6cbbfda44d425a6328137",
            "c979fc8a04e68835a70c07f6bee0f6d959e55be04c728c41491c44fd");
    /** The STRIKE each loan UTxO holds. */
    static final List<Long> COLLATERAL_AMOUNTS = List.of(2_458_146_804L, 6_388_132_400L, 1_153_720_796L);
    static final long LOAN_LOVELACE = 3_000_000L;
    static final long BOND_LOVELACE = 1_969_670L;

    // ---- the config reference inputs (live coordinates, unspent and byte-identical 2026-11-12) ---------

    static final TransactionInput CONFIG_REF =
            new TransactionInput("75e459a0e97d3808031d97b8360308782f197bc778471d3dbc4aa51245886dc5", 0);
    static final String CONFIG_ADDRESS = "addr1wy34kvsypls3wlqrk8f5l67ywpzqcm425g3g48qmpcm4yqqy7cfve";
    static final long CONFIG_LOVELACE = 4_249_660L;
    static final TransactionInput LM_CONFIG_REF =
            new TransactionInput("c0f0d8219ca1ab77e55163cc9c75858815793b38b2167cbee113bea034e6ef36", 0);
    static final String LM_CONFIG_ADDRESS = "addr1w8ak4cszwdvtfg9kyugwh9gs9krl5ylkdm84tky5x6vufysp3u5d6";
    static final long LM_CONFIG_LOVELACE = 2_150_690L;

    // ---- the instant and the rig's prices -----------------------------------------------------------

    /** The loan UTxOs' block time (2026-11-10 14:20:06Z) + 600 s; mainnet slot 202,754,715. */
    static final long T0 = 1_794_321_006_000L;
    /** STRIKE at 1.4 ADA: every recorded loan is liquidatable with positive equity. */
    static final BigInteger COLLATERAL_PRICE = BigInteger.valueOf(140_000_000L);
    /** USDM at 4 ADA. */
    static final BigInteger PRINCIPAL_PRICE = BigInteger.valueOf(400_000_000L);
    static final BigInteger PRICE_DENOMINATOR = BigInteger.valueOf(100_000_000L);
    /** The operator default, {@code loans.liquidation.oracle-window-margin-seconds} = 30. */
    static final long ORACLE_WINDOW_MARGIN_MILLIS = 30_000L;

    /** The bot: a mainnet base address, payment key {@code 11…}, stake key {@code 22…}. Synthetic. */
    static final String BOT_ADDRESS = AddressProvider.getBaseAddress(
            Credential.fromKey(HexUtil.decodeHexString("11".repeat(28))),
            Credential.fromKey(HexUtil.decodeHexString("22".repeat(28))), NETWORK).getAddress();

    // ---- feeds and legs ---------------------------------------------------------------------------

    /** STRIKE at the rig price over [T0 − 300 s, T0 + 1,500 s]. */
    static TestOracleRig.Feed collateralFeed() {
        return new TestOracleRig.Feed(COLLATERAL_PRICE, PRICE_DENOMINATOR, T0 - 300_000L, T0 + 1_500_000L);
    }

    /** USDM at the rig price over [T0 − 200 s, T0 + 600 s] — the earlier-ending of the two windows. */
    static TestOracleRig.Feed principalFeed() {
        return new TestOracleRig.Feed(PRINCIPAL_PRICE, PRICE_DENOMINATOR, T0 - 200_000L, T0 + 600_000L);
    }

    /** The two oracle legs and the rig that made each; the two rigs are one object in {@link #sharedLegs}. */
    record Legs(TestOracleRig collateralRig, TestOracleRig principalRig, TestOracleRig.Leg collateral,
                TestOracleRig.Leg principal) {
    }

    /**
     * One rig per leg: STRIKE v1's code for the collateral leg and USDM v1's for the principal leg, each
     * with three runtime keys at the deployed threshold (1) and signed by the key at position 0. Each leg
     * rewrites the recorded registry entry for the NFT the loan datum names for it.
     */
    static Legs legs(TestOracleRig.Feed collateral, TestOracleRig.Feed principal) {
        TestOracleRig collateralRig = TestOracleRig.of(TestOracleRig.controlHashed("d97da4f3", AQUARIUM_SC), 3);
        TestOracleRig principalRig = TestOracleRig.of(TestOracleRig.controlHashed("3a561e40", AQUARIUM_SC), 3);
        return new Legs(collateralRig, principalRig,
                collateralRig.leg(REGISTRY_PAYLOAD, collateralOracleNft(), collateral,
                        List.of(new TestOracleRig.Signer(0, false)), NETWORK),
                principalRig.leg(REGISTRY_PAYLOAD, principalOracleNft(), principal,
                        List.of(new TestOracleRig.Signer(0, false)), NETWORK));
    }

    /**
     * ONE rig — the STRIKE base — making both legs, so both entries withdraw from one credential. The shape
     * the builder must refuse by name: one withdraw-0 redeemer carries one feed.
     */
    static Legs sharedLegs(TestOracleRig.Feed collateral, TestOracleRig.Feed principal) {
        TestOracleRig rig = TestOracleRig.of(TestOracleRig.controlHashed("d97da4f3", AQUARIUM_SC), 3);
        return new Legs(rig, rig,
                rig.leg(REGISTRY_PAYLOAD, collateralOracleNft(), collateral,
                        List.of(new TestOracleRig.Signer(0, false)), NETWORK),
                rig.leg(REGISTRY_PAYLOAD, principalOracleNft(), principal,
                        List.of(new TestOracleRig.Signer(0, false)), NETWORK));
    }

    private static AssetType collateralOracleNft() {
        return loanDatum(0).collateral().oracleTokenAsset();
    }

    private static AssetType principalOracleNft() {
        return loanDatum(0).principalOracleAsset();
    }

    // ---- the recorded loans, bonds and configs ------------------------------------------------------

    static String loanDatumHex(int i) {
        return LoanFixtures.fixture("mainnet-loan-datum-8554c4d1-" + LOAN_INDEXES.get(i) + ".hex");
    }

    static String bondDatumHex(int i) {
        return LoanFixtures.fixture("mainnet-lender-bond-datum-165d2322-" + BOND_INDEXES.get(i) + ".hex");
    }

    static LoanDatum loanDatum(int i) {
        return new LoanDatumConverter().deserialize(loanDatumHex(i));
    }

    static LenderManagerDatum bondDatum(int i) {
        return new LenderManagerDatumConverter().deserialize(bondDatumHex(i));
    }

    static Loan loan(int i) {
        return new Loan(LOAN_TX, LOAN_INDEXES.get(i), LOAN_ADDRESS, LOAN_IDS.get(i),
                BigInteger.valueOf(COLLATERAL_AMOUNTS.get(i)), BigInteger.valueOf(LOAN_LOVELACE), loanDatum(i));
    }

    static LenderBond bond(int i) {
        return new LenderBond(BOND_TX, BOND_INDEXES.get(i), BOND_ADDRESSES.get(i), LOAN_IDS.get(i),
                bondDatumHex(i), bondDatum(i));
    }

    static Utxo loanUtxo(int i) {
        return LoanFixtures.utxo(LOAN_TX, LOAN_INDEXES.get(i), LOAN_ADDRESS, List.of(
                        Amount.lovelace(BigInteger.valueOf(LOAN_LOVELACE)),
                        Amount.asset(REGISTRY.getLoanPolicyId() + LOAN_IDS.get(i), BigInteger.ONE),
                        Amount.asset(STRIKE.toUnit(), BigInteger.valueOf(COLLATERAL_AMOUNTS.get(i)))),
                loanDatumHex(i));
    }

    static Utxo bondUtxo(int i) {
        return LoanFixtures.utxo(BOND_TX, BOND_INDEXES.get(i), BOND_ADDRESSES.get(i), List.of(
                        Amount.lovelace(BigInteger.valueOf(BOND_LOVELACE)),
                        Amount.asset(REGISTRY.getLenderBondPolicyId() + LOAN_IDS.get(i), BigInteger.ONE)),
                bondDatumHex(i));
    }

    static Utxo configUtxo() {
        return LoanFixtures.utxo(CONFIG_REF.getTransactionId(), CONFIG_REF.getIndex(), CONFIG_ADDRESS, List.of(
                        Amount.lovelace(BigInteger.valueOf(CONFIG_LOVELACE)),
                        Amount.asset(CONFIG_POLICY_ID + CONFIG_ASSET_NAME, BigInteger.ONE)),
                LoanFixtures.fixture("mainnet-config-datum-2026-10-23.hex"));
    }

    static Utxo lmConfigUtxo() {
        return LoanFixtures.utxo(LM_CONFIG_REF.getTransactionId(), LM_CONFIG_REF.getIndex(), LM_CONFIG_ADDRESS,
                List.of(Amount.lovelace(BigInteger.valueOf(LM_CONFIG_LOVELACE)),
                        Amount.asset(LM_CONFIG_POLICY_ID + CONFIG_ASSET_NAME, BigInteger.ONE)),
                LoanFixtures.fixture("mainnet-lm-config-datum-2026-10-26.hex"));
    }

    // ---- the six registry reference scripts ---------------------------------------------------------

    /**
     * The six loans-v4 validators a mainnet pay-in-advance references, in this order: loan, loan-spend,
     * lender-manager, lender-manager-spend, loan-claim-action, the pay-in-advance action.
     */
    static List<PlutusScript> registryReferenceScripts() {
        return List.of(REGISTRY.getLoanScript(), REGISTRY.getLoanSpendScript(), REGISTRY.getLenderManagerScript(),
                REGISTRY.getLenderManagerSpendScript(), REGISTRY.getLoanClaimActionScript(),
                REGISTRY.getLmLiquidateAndPayInAdvanceActionScript());
    }

    /** Synthetic coordinate {@code a<i+1>…#0} of {@link #registryReferenceScripts()}' i-th script. */
    static TransactionInput referenceScriptCoordinate(int i) {
        return new TransactionInput(String.format("a%d", i + 1).repeat(32), 0);
    }

    /** The six coordinates in the builder's slots, the pay-in-advance action in its own. */
    static final LiquidateTransactionBuilder.ReferenceScripts REFERENCE_SCRIPTS =
            new LiquidateTransactionBuilder.ReferenceScripts(referenceScriptCoordinate(0),
                    referenceScriptCoordinate(1), referenceScriptCoordinate(2), referenceScriptCoordinate(3),
                    referenceScriptCoordinate(4), null, null, referenceScriptCoordinate(5), null);

    /** Each at its script's enterprise address with 20 ADA, carrying the script's HASH and never its bytes. */
    static List<Utxo> referenceScriptUtxos() {
        List<PlutusScript> scripts = registryReferenceScripts();
        List<Utxo> utxos = new ArrayList<>();
        for (int i = 0; i < scripts.size(); i++) {
            String hash = hashOf(scripts.get(i));
            TransactionInput at = referenceScriptCoordinate(i);
            utxos.add(Utxo.builder().txHash(at.getTransactionId()).outputIndex(at.getIndex())
                    .address(entAddress(hash))
                    .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                    .referenceScriptHash(hash).build());
        }
        return utxos;
    }

    // ---- the wallet roles -------------------------------------------------------------------------

    /** Wallet role C: ada-only, exactly {@code WalletShape.collateralLovelace} of {@link LoanFixtures#protocolParams()}. */
    static Utxo collateral(String address) {
        return LoanFixtures.adaUtxo("c5".repeat(32), 0, address, WalletShape.collateralLovelace(
                LoanFixtures.protocolParams().getProtocolParams()).longValueExact());
    }

    /** Wallet role U: 100 ADA, ada-only. */
    static Utxo u(String address) {
        return LoanFixtures.adaUtxo("e2".repeat(32), 0, address, 100_000_000L);
    }

    /** The token output: {@code lovelace} plus {@code usdm} units of USDM. */
    static Utxo tokenOutput(String address, long lovelace, BigInteger usdm) {
        return LoanFixtures.utxo("e1".repeat(32), 0, address, List.of(Amount.lovelace(BigInteger.valueOf(lovelace)),
                Amount.asset(USDM.toUnit(), usdm)), null);
    }

    // ---- the universe, the scripts, the evaluator, the builder ----------------------------------------

    /**
     * Loan {@code i}, its bond, both configs, both legs' fabricated UTxOs, the six reference-script UTxOs
     * and {@code wallet}. An out-ref that two legs of one rig share (its script holder) is listed once.
     */
    static List<Utxo> universe(int i, Legs legs, List<Utxo> wallet) {
        Map<String, Utxo> byRef = new LinkedHashMap<>();
        List<Utxo> all = new ArrayList<>(List.of(loanUtxo(i), bondUtxo(i), configUtxo(), lmConfigUtxo()));
        all.addAll(legs.collateral().utxos());
        all.addAll(legs.principal().utxos());
        all.addAll(referenceScriptUtxos());
        all.addAll(wallet);
        for (Utxo utxo : all) {
            byRef.putIfAbsent(utxo.getTxHash() + "#" + utxo.getOutputIndex(), utxo);
        }
        return List.copyOf(byRef.values());
    }

    /**
     * The provider leaf: the registry's applied scripts, both rig scripts and the pay-in-advance action
     * (which {@link EvalFixtures#scriptSupplier} does not carry), served by hash.
     */
    static ScriptSupplier rawScripts(Legs legs) {
        return EvalFixtures.scriptSupplier(REGISTRY, List.of(legs.collateralRig().script(),
                legs.principalRig().script(), REGISTRY.getLmLiquidateAndPayInAdvanceActionScript()));
    }

    /** The real PlutusV3 machine, mainnet slot config, over {@code universe}. */
    static AikenTransactionEvaluator aiken(List<Utxo> universe, Legs legs) {
        return new AikenTransactionEvaluator(LoanFixtures.utxoSupplier(universe), LoanFixtures.protocolParams(),
                rawScripts(legs), SlotConfigs.mainnet());
    }

    /** The production constructor (registry, network, utxo supplier, params, script supplier, evaluator). */
    static LiquidatePayInAdvanceTransactionBuilder production(List<Utxo> universe, ScriptSupplier scriptSupplier,
                                                              TransactionEvaluator evaluator) {
        return new LiquidatePayInAdvanceTransactionBuilder(REGISTRY, NETWORK, LoanFixtures.utxoSupplier(universe),
                LoanFixtures.protocolParams(), scriptSupplier, evaluator);
    }

    /**
     * The request for loan {@code i} with the legs' entries, {@code wallet} as the designated input,
     * {@code collateral} as C and {@code changeAddress} as fee payer and change; the window in mainnet slots.
     */
    static LiquidatePayInAdvanceTransactionBuilder.Request request(int i, Legs legs, Utxo wallet, Utxo collateral,
                                                                   String changeAddress, long validFromMillis,
                                                                   long validToMillis) {
        return new LiquidatePayInAdvanceTransactionBuilder.Request(loan(i), loanUtxo(i), bond(i), bondUtxo(i),
                wallet, configUtxo(), lmConfigUtxo(), legs.collateral().entry(), legs.principal().entry(),
                validFromMillis, validToMillis, slot(validFromMillis), slot(validToMillis), changeAddress,
                REFERENCE_SCRIPTS, ORACLE_WINDOW_MARGIN_MILLIS, collateral);
    }

    /** The mainnet slot of a whole-second instant. */
    static long slot(long millis) {
        if (millis % 1000L != 0) {
            throw new IllegalArgumentException("not a whole second: " + millis);
        }
        CardanoConverters converters = ClasspathConversionsFactory.createConverters(NetworkType.MAINNET);
        return converters.time().toSlot(LocalDateTime.ofEpochSecond(millis / 1000L, 0, ZoneOffset.UTC));
    }

    static String entAddress(String scriptHash) {
        return AddressProvider.getEntAddress(Credential.fromScript(HexUtil.decodeHexString(scriptHash)), NETWORK)
                .getAddress();
    }

    static String rewardAddress(String scriptHash) {
        return AddressProvider.getRewardAddress(Credential.fromScript(HexUtil.decodeHexString(scriptHash)), NETWORK)
                .getAddress();
    }

    static String hashOf(PlutusScript script) {
        try {
            return HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new AssertionError("cannot hash a script", e);
        }
    }
}
