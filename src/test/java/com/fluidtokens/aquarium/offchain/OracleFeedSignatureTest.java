package com.fluidtokens.aquarium.offchain;

import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.service.loans.OracleFeedConverter;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@link OracleFeedConverter} produces the exact bytes FluidTokens' oracle keys sign.
 * <p>
 * {@code validators/oracle.ak} verifies a liquidation's price feed with
 * <pre>
 *   let serialise_data = builtin.serialise_data(redeemer.data)
 *   verify_ed25519_signature(verification_key, serialise_data, redem.signature)
 * </pre>
 * An ed25519 signature is a total function of the message, so this test cannot pass by accident:
 * if any part of our encoding were wrong — constructor index, field order, the nesting of
 * {@code CommonFeedData}, the {@code Asset} inside it, or definite- versus indefinite-length
 * CBOR lists — every signature would fail. Getting 18 live feeds to verify is a proof of the
 * encoding rather than an assertion about it.
 * <p>
 * Offline: the payload is a captured response, not a live call. Expiry does not matter because
 * a signature stays valid over its message forever; only the prices go stale, and nothing here
 * looks at prices.
 */
class OracleFeedSignatureTest {

    private record Feed(String symbol, OraclePriceFeed feed, List<String> publicKeys,
                        List<String> sigPublicKeys, List<String> signatures) {
    }

    private static List<Feed> multisigFeeds() throws Exception {
        List<Feed> feeds = new ArrayList<>();
        try (InputStream in = OracleFeedSignatureTest.class
                .getResourceAsStream("/loans-v4/oracle-registry.json")) {
            for (JsonNode entry : new ObjectMapper().readTree(in)) {
                // c3-preferred entries are validated against a Charli3 reference input rather than
                // by signature, so they carry nothing this test can check.
                if (!"multisig".equals(entry.path("preferredOracle").asText())) {
                    continue;
                }
                JsonNode supported = entry.path("supportedOracle").path("multisig");
                JsonNode signatures = supported.path("multisigOracle").path("signatures");
                if (!signatures.isArray() || signatures.isEmpty()) {
                    continue;
                }
                JsonNode token = entry.path("token");
                var feed = OraclePriceFeed.aggregated(
                        new AssetType(token.path("policyId").asText(), token.path("assetName").asText()),
                        supported.path("tokenPriceInLovelaces").bigIntegerValue(),
                        supported.path("tokenPriceDenominator").bigIntegerValue(),
                        supported.path("validFrom").asLong(),
                        supported.path("validTo").asLong());
                feeds.add(new Feed(
                        token.path("name").asText(),
                        feed,
                        textList(entry.path("multisigOracle").path("publicKeys")),
                        signatures.findValuesAsText("publicKey"),
                        signatures.findValuesAsText("signature")));
            }
        }
        return feeds;
    }

    private static List<String> textList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static boolean verify(String publicKeyHex, byte[] message, String signatureHex) {
        var signer = new Ed25519Signer();
        signer.init(false, new Ed25519PublicKeyParameters(HexUtil.decodeHexString(publicKeyHex), 0));
        signer.update(message, 0, message.length);
        return signer.verifySignature(HexUtil.decodeHexString(signatureHex));
    }

    private static byte[] serialise(OraclePriceFeed feed) throws Exception {
        return OracleFeedConverter.toPlutusData(feed).serializeToBytes();
    }

    /** The whole point: our bytes are the bytes that were signed. */
    @Test
    void everyPublishedSignatureVerifiesOverOurEncoding() throws Exception {
        var feeds = multisigFeeds();
        assertFalse(feeds.isEmpty(), "fixture has no multisig feeds");

        int checked = 0;
        for (Feed f : feeds) {
            byte[] message = serialise(f.feed());
            for (int i = 0; i < f.signatures().size(); i++) {
                assertTrue(verify(f.sigPublicKeys().get(i), message, f.signatures().get(i)),
                        () -> "signature did not verify for " + f.symbol()
                                + " — OracleFeedConverter is not producing serialise_data bytes");
                checked++;
            }
        }
        assertEquals(20, checked, "fixture should carry 20 signatures across 18 multisig feeds");
    }

    /**
     * Guards against a vacuous pass: a verifier that accepted anything would make the test above
     * meaningless, so perturb one field and require every signature to break.
     */
    @Test
    void aOneFieldChangeBreaksEverySignature() throws Exception {
        for (Feed f : multisigFeeds()) {
            var tampered = new OraclePriceFeed(f.feed().variant(), f.feed().token(),
                    f.feed().priceInLovelaces().add(BigInteger.ONE), f.feed().priceDenominator(),
                    f.feed().validFrom(), f.feed().validTo());
            byte[] message = serialise(tampered);
            for (int i = 0; i < f.signatures().size(); i++) {
                assertFalse(verify(f.sigPublicKeys().get(i), message, f.signatures().get(i)),
                        "a changed price must invalidate " + f.symbol());
            }
        }
    }

    /**
     * {@code Signature.key_position} indexes the oracle validator's {@code verification_keys}
     * parameter. The registry publishes a {@code publicKeys} list per asset and attaches the
     * signing key to each signature; this pins the mapping we rely on — position = index in that
     * list — and would fail loudly if FluidTokens ever published a signature from a key outside
     * it.
     * <p>
     * Caveat worth keeping: only fGLD has more than one key, so this shows the mapping is
     * <em>consistent</em> with the published data. It does not prove {@code publicKeys} is in the
     * same order as the on-chain parameter; that needs the applied oracle script.
     */
    @Test
    void everySigningKeyAppearsInTheEntrysPublicKeyList() throws Exception {
        for (Feed f : multisigFeeds()) {
            var keys = f.publicKeys().stream().map(String::toUpperCase).toList();
            for (String signingKey : f.sigPublicKeys()) {
                assertTrue(keys.contains(signingKey.toUpperCase()),
                        "signing key missing from publicKeys for " + f.symbol());
            }
        }
    }

    /** Ada is priced 1:1 with no oracle at all, so its feed must still encode. */
    @Test
    void theSynthesisedAdaFeedEncodes() throws Exception {
        assertTrue(serialise(OraclePriceFeed.unit()).length > 0);
    }
}
