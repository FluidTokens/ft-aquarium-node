package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import scalus.uplc.Constant;
import scalus.uplc.DeBruijnedProgram$;
import scalus.uplc.Term;

import java.io.InputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * ⛔ <b>FluidTokens' oracle validator code RE-PARAMETERISED WITH RUNTIME TEST KEYS — not the deployed
 * oracle — and every script it produces has a hash different from every deployed oracle.</b>
 *
 * <h2>Why it exists</h2>
 * A liquidation rig can only replay a price someone has already signed: a recorded signed feed cannot be
 * edited, because {@code validators/oracle.ak} verifies {@code serialise_data(redeemer.data)} against the
 * keys APPLIED to the script, and those keys are FluidTokens'. So no recorded feed can be moved to another
 * price or another window. The loan validators, on the other hand, bind the oracle only through the oracle
 * NFT the loan datum names and through the reference input's payment credential, which must equal the
 * credential of a withdrawal in the same transaction ({@code retrieve_oracle_data}). They never pin the
 * oracle's script hash. So the deployed code, applied to keys this rig generates, is an oracle the loan
 * validators accept as long as the NFT holder sits at its credential — and every price, window and signer
 * set becomes something a test can choose.
 *
 * <h2>The control hash: the code is FluidTokens', not a rebuild</h2>
 * {@link #controlHashed} extracts the eight parameters a deployed mainnet oracle was applied with (by
 * decoding the applied script to a term tree, never by slicing bytes) and re-applies them to the unapplied
 * {@code oracle.oracle.withdraw} of a committed blueprint. That must reproduce the deployed hash, which is
 * what proves the code this rig re-parameterises is the deployed code:
 * <ul>
 *   <li>{@code a0bb657d} (NIGHT v1) from {@code aquarium-sc.plutus.json};</li>
 *   <li>{@code 756897fd} (NIGHT v2) and {@code d81a8bea} (FLDT v1 and v2, one shared script) from
 *       {@code loans-v4.plutus.json}.</li>
 * </ul>
 * The other blueprint gives a different hash for each, so a drift of a deployed oracle or of a blueprint
 * fails as an {@link AssertionError} naming both hashes and the blueprint.
 *
 * <h2>Keys exist only in memory</h2>
 * The n applied keys and the one outsider key are generated from a {@link SecureRandom} when the rig is
 * built. Nothing returns a private key, {@link #toString()} prints only public material, and nothing is
 * written anywhere: every run signs with keys no other run has seen.
 */
final class TestOracleRig {

    /** The deployed mainnet oracles this rig knows; a rig script equal to any of them is refused. */
    static final Set<String> DEPLOYED_ORACLE_HASHES = Set.of(
            "a0bb657d97d04dc66abee40da7ef94b92fea67ef892aff22c153448e",
            "756897fd5f7f838ae49b244efd813207ef525e6c5ab85c3e2fdc79a5",
            "d81a8bea722dc9487e7e693487f6948983af45c3f7897bf1d37916c8");

    /**
     * Exactly the registry-entry fields {@link #leg} rewrites, as dotted paths; every other field of the
     * entry, and every other entry, keeps its recorded value. {@code multisigOracle.requiredSignatures} is
     * written as the rig's threshold.
     */
    static final Set<String> REWRITTEN_FIELDS = Set.of(
            "multisigOracle.publicKeys",
            "multisigOracle.requiredSignatures",
            "fluidOracle.rewardAddress",
            "fluidOracle.referenceInput",
            "fluidOracle.referenceScript",
            "supportedOracle.multisig.tokenPriceInLovelaces",
            "supportedOracle.multisig.tokenPriceDenominator",
            "supportedOracle.multisig.validFrom",
            "supportedOracle.multisig.validTo",
            "supportedOracle.multisig.multisigOracle.signatures");

    private static final String VALIDATOR = "oracle.oracle.withdraw";

    private static final List<String> PARAMETER_TITLES = List.of("verification_keys", "threshold",
            "_oracle_asset_policy_id", "_oracle_asset_asset_name", "governance_token_policy_id",
            "governance_token_asset_name", "charlie_specs", "orcfax_specs");

    /** A committed blueprint carrying FluidTokens' unapplied {@code oracle.oracle.withdraw}. */
    enum Blueprint {
        AQUARIUM_SC("/aquarium-sc.plutus.json"),
        LOANS_V4("/loans-v4.plutus.json");

        private final String resource;
        private volatile String code;
        private volatile List<String> parameterTitles;

        Blueprint(String resource) {
            this.resource = resource;
        }

        String resource() {
            return resource;
        }

        /** The unapplied {@code compiledCode} of {@code oracle.oracle.withdraw}. */
        String code() {
            load();
            return code;
        }

        /** The validator's parameter titles, in application order. */
        List<String> parameterTitles() {
            load();
            return parameterTitles;
        }

        private synchronized void load() {
            if (code != null) {
                return;
            }
            try (InputStream in = TestOracleRig.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new AssertionError("no blueprint on the classpath at " + resource);
                }
                JsonNode found = null;
                for (JsonNode v : new ObjectMapper().readTree(in).path("validators")) {
                    if (VALIDATOR.equals(v.path("title").asText())) {
                        if (found != null) {
                            throw new AssertionError(resource + " carries " + VALIDATOR + " twice");
                        }
                        found = v;
                    }
                }
                if (found == null) {
                    throw new AssertionError(resource + " carries no " + VALIDATOR);
                }
                List<String> titles = new ArrayList<>();
                found.path("parameters").forEach(p -> titles.add(p.path("title").asText()));
                if (!PARAMETER_TITLES.equals(titles)) {
                    throw new AssertionError(resource + "'s " + VALIDATOR + " parameters are " + titles
                            + ", not " + PARAMETER_TITLES + ": validators/oracle.ak changed its parameters");
                }
                parameterTitles = List.copyOf(titles);
                code = found.path("compiledCode").asText();
            } catch (AssertionError e) {
                throw e;
            } catch (Exception e) {
                throw new AssertionError("cannot read " + VALIDATOR + " from " + resource, e);
            }
        }
    }

    /**
     * A deployed oracle whose parameters, re-applied to {@code blueprint}'s code, reproduced its hash.
     *
     * @param parameters the eight parameters in application order
     */
    record Deployed(String scriptHash, Blueprint blueprint, List<PlutusData> parameters) {

        Deployed {
            parameters = List.copyOf(parameters);
        }

        /** Parameter 0, {@code verification_keys}, as lower-case hex. */
        List<String> verificationKeysHex() {
            List<String> keys = new ArrayList<>();
            for (PlutusData key : ((ListPlutusData) parameters.get(0)).getPlutusDataList()) {
                keys.add(HexUtil.encodeHexString(((BytesPlutusData) key).getValue()));
            }
            return keys;
        }

        /** Parameter 1, {@code threshold}. */
        int threshold() {
            return ((BigIntPlutusData) parameters.get(1)).getValue().intValueExact();
        }
    }

    /** A feed's price and window, for one leg. */
    record Feed(BigInteger priceInLovelaces, BigInteger priceDenominator, long validFrom, long validTo) {
    }

    /**
     * One published signature: by the applied key at {@code position}, or — {@code outsider} — by the
     * rig's one key that is NOT applied, claiming that position.
     */
    record Signer(int position, boolean outsider) {

        static Signer at(int position) {
            return new Signer(position, false);
        }

        static Signer outsiderAt(int position) {
            return new Signer(position, true);
        }
    }

    /**
     * One oracle leg through production's registry reader.
     *
     * @param entry               what production's {@link FluidOracleClient} parsed out of the rewritten payload
     *                            for the leg's NFT
     * @param signedFeed          exactly the feed the rig signed
     * @param recordedEntry       the recorded registry entry, untouched
     * @param rewrittenEntry      the same entry as the rig loaded it
     * @param referenceInputUtxo  the fabricated NFT holder, at the rig script's enterprise address
     * @param referenceScriptUtxo the fabricated reference-script UTxO: the rig script's HASH, never its bytes
     * @param client              the client the WHOLE rewritten payload was loaded into
     */
    record Leg(OracleEntry entry, OraclePriceFeed signedFeed, ObjectNode recordedEntry, ObjectNode rewrittenEntry,
               Utxo referenceInputUtxo, Utxo referenceScriptUtxo, FluidOracleClient client) {

        List<Utxo> utxos() {
            return List.of(referenceInputUtxo, referenceScriptUtxo);
        }
    }

    // ---- the control hash ---------------------------------------------------------------------------

    /**
     * The parameters an applied oracle script was applied with, in application order. The script is
     * decoded to a term tree and EXACTLY as many outer applications as {@code blueprint} declares
     * parameters are peeled — the unapplied body may itself begin with an application.
     */
    static List<PlutusData> appliedParameters(String appliedHex, Blueprint blueprint) {
        int count = blueprint.parameterTitles().size();
        Term term = DeBruijnedProgram$.MODULE$.fromCborHex(appliedHex).term();
        List<PlutusData> peeled = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (!(term instanceof Term.Apply apply)) {
                throw new AssertionError("the applied script has " + i + " outer applications, "
                        + blueprint + " declares " + count + " parameters");
            }
            if (!(apply.arg() instanceof Term.Const constant) || !(constant._1() instanceof Constant.Data data)) {
                throw new AssertionError("outer application " + i + " of the applied script is not a Data "
                        + "constant: " + apply.arg());
            }
            try {
                peeled.add(PlutusData.deserialize(scalus.uplc.builtin.Data$.MODULE$.toCbor(data.value())));
            } catch (Exception e) {
                throw new AssertionError("cannot convert applied parameter " + i, e);
            }
            term = apply.f();
        }
        // Peeled outermost first, i.e. the LAST parameter applied comes out first.
        Collections.reverse(peeled);
        return List.copyOf(peeled);
    }

    /** The script hash of {@code blueprint}'s oracle code applied to {@code parameters}, as production applies. */
    static String hashOf(Blueprint blueprint, List<PlutusData> parameters) {
        return hashOf(applied(blueprint, parameters));
    }

    /**
     * The deployed oracle {@code mainnet-oracle-script-<hash8>.hex}, control-hashed: its parameters
     * re-applied to {@code blueprint}'s code must reproduce its own hash.
     *
     * @throws AssertionError naming both hashes and the blueprint when they differ
     */
    static Deployed controlHashed(String hash8, Blueprint blueprint) {
        String appliedHex = LoanFixtures.fixture("mainnet-oracle-script-" + hash8 + ".hex");
        String deployedHash;
        try {
            deployedHash = hashOf(PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(appliedHex, PlutusVersion.v3));
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("cannot load the deployed oracle script " + hash8, e);
        }
        if (!deployedHash.startsWith(hash8)) {
            throw new AssertionError("mainnet-oracle-script-" + hash8 + ".hex hashes to " + deployedHash
                    + ", not to a hash starting " + hash8);
        }
        List<PlutusData> parameters = appliedParameters(appliedHex, blueprint);
        String reproduced = hashOf(blueprint, parameters);
        if (!reproduced.equals(deployedHash)) {
            throw new AssertionError("CONTROL HASH FAILED: the deployed oracle " + deployedHash + "'s parameters, "
                    + "re-applied to " + blueprint + " (" + blueprint.resource() + ") " + VALIDATOR + ", give "
                    + reproduced + " — that blueprint's code is not the code deployed at " + deployedHash);
        }
        return new Deployed(deployedHash, blueprint, parameters);
    }

    // ---- the rig ------------------------------------------------------------------------------------

    private final Deployed base;
    private final int threshold;
    private final List<Ed25519PrivateKeyParameters> keys;
    private final Ed25519PrivateKeyParameters outsider;
    private final List<String> publicKeysHex;
    private final PlutusScript script;
    private final String scriptHash;
    private int legsMade;

    private TestOracleRig(Deployed base, int n, int threshold) {
        if (n < 1) {
            throw new IllegalArgumentException("a rig needs at least one key, not " + n);
        }
        SecureRandom random = new SecureRandom();
        List<Ed25519PrivateKeyParameters> generated = new ArrayList<>();
        List<String> publics = new ArrayList<>();
        ListPlutusData appliedKeys = ListPlutusData.builder().build();
        for (int i = 0; i < n; i++) {
            Ed25519PrivateKeyParameters key = new Ed25519PrivateKeyParameters(random);
            byte[] publicKey = key.generatePublicKey().getEncoded();
            generated.add(key);
            publics.add(HexUtil.encodeHexString(publicKey));
            appliedKeys.add(BytesPlutusData.of(publicKey));
        }
        this.base = base;
        this.threshold = threshold;
        this.keys = List.copyOf(generated);
        this.outsider = new Ed25519PrivateKeyParameters(random);
        this.publicKeysHex = List.copyOf(publics);

        List<PlutusData> parameters = new ArrayList<>(base.parameters());
        parameters.set(0, appliedKeys);
        parameters.set(1, BigIntPlutusData.of(threshold));
        try {
            this.script = PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(applied(base.blueprint(), parameters),
                    PlutusVersion.v3);
        } catch (Exception e) {
            throw new AssertionError("cannot build the rig's oracle script", e);
        }
        this.scriptHash = hashOf(script);
        if (DEPLOYED_ORACLE_HASHES.contains(scriptHash)) {
            throw new AssertionError("the rig's oracle script hashes to the DEPLOYED oracle " + scriptHash
                    + ": its parameters were not replaced");
        }
    }

    /** n runtime keys, at the deployed oracle's own threshold. */
    static TestOracleRig of(Deployed base, int n) {
        return of(base, n, base.threshold());
    }

    /** n runtime keys, at an explicit threshold. */
    static TestOracleRig of(Deployed base, int n, int threshold) {
        return new TestOracleRig(base, n, threshold);
    }

    /** The rig's applied oracle script. */
    PlutusScript script() {
        return script;
    }

    String scriptHash() {
        return scriptHash;
    }

    int threshold() {
        return threshold;
    }

    /** The applied verification keys, in key-position order, lower-case hex. */
    List<String> publicKeysHex() {
        return publicKeysHex;
    }

    /** The script stake address of {@link #scriptHash()}: the credential the oracle withdraws from. */
    String rewardAddress(Network network) {
        return AddressProvider.getRewardAddress(Credential.fromScript(HexUtil.decodeHexString(scriptHash)), network)
                .toBech32();
    }

    /** Ed25519 by the applied key at {@code keyIndex} over exactly the bytes {@code validators/oracle.ak} verifies. */
    String sign(int keyIndex, OraclePriceFeed feed) {
        return sign(keys.get(keyIndex), feed);
    }

    /** As {@link #sign}, by the one key that is NOT applied to the script. */
    String signAsOutsider(OraclePriceFeed feed) {
        return sign(outsider, feed);
    }

    private static String sign(Ed25519PrivateKeyParameters key, OraclePriceFeed feed) {
        byte[] message;
        try {
            message = OracleFeedConverter.toPlutusData(feed).serializeToBytes();
        } catch (Exception e) {
            throw new AssertionError("cannot serialise the feed", e);
        }
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, key);
        signer.update(message, 0, message.length);
        return HexUtil.encodeHexString(signer.generateSignature());
    }

    /**
     * The rig's oracle for {@code oracleNft}'s recorded registry entry, read back through production's
     * {@link FluidOracleClient}. Only the {@link #REWRITTEN_FIELDS} of that ONE entry are rewritten; the
     * whole array is loaded, so every other entry parses as recorded. The NFT holder and the
     * reference-script UTxO are fabricated at the rig script's enterprise address under tx id
     * {@code scriptHash + "00000000"}: the script at index 0, the NFT holder at {@code 1 + legs already
     * made by this rig}.
     */
    Leg leg(String registryResource, AssetType oracleNft, Feed feed, List<Signer> signers, Network network) {
        ArrayNode recorded;
        try (InputStream in = TestOracleRig.class.getResourceAsStream(registryResource)) {
            if (in == null) {
                throw new AssertionError("no registry payload at " + registryResource);
            }
            recorded = (ArrayNode) new ObjectMapper().readTree(in);
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("cannot read " + registryResource, e);
        }
        ArrayNode payload = recorded.deepCopy();
        ObjectNode rewritten = null;
        int matches = 0;
        for (JsonNode node : payload) {
            JsonNode oracle = node.path("fluidOracle");
            if (oracleNft.policyId().equalsIgnoreCase(oracle.path("policyId").asText(""))
                    && oracleNft.assetName().equalsIgnoreCase(oracle.path("assetName").asText(""))) {
                rewritten = (ObjectNode) node;
                matches++;
            }
        }
        if (matches != 1) {
            throw new AssertionError(registryResource + " lists the oracle NFT " + oracleNft.toUnit() + " "
                    + matches + " times, not exactly once");
        }
        if (!"multisig".equals(rewritten.path("preferredOracle").asText())) {
            throw new AssertionError("the oracle " + oracleNft.toUnit() + " prefers '"
                    + rewritten.path("preferredOracle").asText() + "', not multisig: the rig signs Aggregated feeds only");
        }
        ObjectNode recordedEntry = rewritten.deepCopy();

        JsonNode token = rewritten.path("token");
        AssetType pricedToken = AssetType.fromUnit(token.path("policyId").asText("") + token.path("assetName").asText(""));
        OraclePriceFeed signedFeed = OraclePriceFeed.aggregated(pricedToken, feed.priceInLovelaces(),
                feed.priceDenominator(), feed.validFrom(), feed.validTo());

        JsonNodeFactory json = JsonNodeFactory.instance;
        ArrayNode publicKeys = json.arrayNode();
        publicKeysHex.forEach(k -> publicKeys.add(k.toUpperCase()));
        ArrayNode signatures = json.arrayNode();
        for (Signer signer : signers) {
            String signature = signer.outsider() ? signAsOutsider(signedFeed) : sign(signer.position(), signedFeed);
            signatures.add(json.objectNode()
                    .put("publicKey", publicKeysHex.get(signer.position()).toUpperCase())
                    .put("signature", signature));
        }
        String txId = scriptHash + "00000000";
        int nftIndex = 1 + legsMade;

        rewrite(rewritten, "multisigOracle.publicKeys", publicKeys);
        rewrite(rewritten, "multisigOracle.requiredSignatures", json.numberNode(threshold));
        rewrite(rewritten, "fluidOracle.rewardAddress", json.textNode(rewardAddress(network)));
        rewrite(rewritten, "fluidOracle.referenceInput", json.textNode(txId + "#" + nftIndex));
        rewrite(rewritten, "fluidOracle.referenceScript", json.textNode(txId + "#0"));
        rewrite(rewritten, "supportedOracle.multisig.tokenPriceInLovelaces", json.numberNode(signedFeed.priceInLovelaces()));
        rewrite(rewritten, "supportedOracle.multisig.tokenPriceDenominator", json.numberNode(signedFeed.priceDenominator()));
        rewrite(rewritten, "supportedOracle.multisig.validFrom", json.numberNode(signedFeed.validFrom()));
        rewrite(rewritten, "supportedOracle.multisig.validTo", json.numberNode(signedFeed.validTo()));
        rewrite(rewritten, "supportedOracle.multisig.multisigOracle.signatures", signatures);

        FluidOracleClient client = new FluidOracleClient("http://unused.invalid");
        client.load(payload);
        OracleEntry entry = client.findEntryByOracleToken(oracleNft)
                .orElseThrow(() -> new AssertionError("production's registry reader dropped the rewritten entry "
                        + oracleNft.toUnit()));

        String address = AddressProvider.getEntAddress(Credential.fromScript(HexUtil.decodeHexString(scriptHash)),
                network).getAddress();
        Utxo nftHolder = LoanFixtures.utxo(txId, nftIndex, address,
                List.of(Amount.lovelace(BigInteger.valueOf(2_000_000L)), Amount.asset(oracleNft.toUnit(), BigInteger.ONE)),
                null);
        // A Blockfrost Utxo carries the reference script's HASH, never its bytes: those come only from the
        // script supplier (CCL trap 9).
        Utxo scriptHolder = Utxo.builder().txHash(txId).outputIndex(0).address(address)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                .referenceScriptHash(scriptHash).build();
        legsMade++;
        return new Leg(entry, signedFeed, recordedEntry, rewritten, nftHolder, scriptHolder, client);
    }

    /** Replaces the field at a dotted {@code path} of {@code entry}; only {@link #REWRITTEN_FIELDS} may be named. */
    private static void rewrite(ObjectNode entry, String path, JsonNode value) {
        if (!REWRITTEN_FIELDS.contains(path)) {
            throw new AssertionError("the rig rewrites only " + REWRITTEN_FIELDS + ", not " + path);
        }
        String[] parts = path.split("\\.");
        JsonNode parent = entry;
        for (int i = 0; i < parts.length - 1; i++) {
            parent = parent.path(parts[i]);
        }
        if (!(parent instanceof ObjectNode object) || !object.has(parts[parts.length - 1])) {
            throw new AssertionError("the recorded entry has no field " + path + " to rewrite");
        }
        object.set(parts[parts.length - 1], value);
    }

    private static String applied(Blueprint blueprint, List<PlutusData> parameters) {
        ListPlutusData list = ListPlutusData.builder().build();
        parameters.forEach(list::add);
        return AikenScriptUtil.applyParamToScript(list, blueprint.code());
    }

    private static String hashOf(String compiledCode) {
        try {
            return hashOf(PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(compiledCode, PlutusVersion.v3));
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            throw new AssertionError("cannot hash compiled code", e);
        }
    }

    private static String hashOf(PlutusScript script) {
        try {
            return HexUtil.encodeHexString(script.getScriptHash());
        } catch (Exception e) {
            throw new AssertionError("cannot hash a script", e);
        }
    }

    /** Public material only: no private key is ever printed. */
    @Override
    public String toString() {
        return "TestOracleRig{script " + scriptHash + ", threshold " + threshold + " of " + publicKeysHex.size()
                + " keys " + publicKeysHex + ", code of " + base.scriptHash() + " from " + base.blueprint() + "}";
    }
}
