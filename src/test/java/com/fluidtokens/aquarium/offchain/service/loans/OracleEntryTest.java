package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The oracle deployment details a liquidation transaction needs, as parsed from the registry.
 * <p>
 * A price alone is not enough to build a {@code Liquidate}: the transaction has to place a
 * withdrawal at the oracle's own credential, reference the UTxO holding its NFT, and supply
 * signatures at the right {@code key_position}. All of that is per asset, because the registry
 * publishes one oracle script per asset.
 */
class OracleEntryTest {

    private static final String MAINNET_FLDT =
            "577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e";

    private static FluidOracleClient clientFromFixture() throws Exception {
        var client = new FluidOracleClient("http://unused.invalid");
        try (InputStream in = OracleEntryTest.class.getResourceAsStream("/loans-v4/oracle-registry.json")) {
            JsonNode payload = new ObjectMapper().readTree(in);
            client.load(payload);
        }
        return client;
    }

    private static OracleEntry fldt() throws Exception {
        return clientFromFixture().findEntry(new AssetType(MAINNET_FLDT, "0014df10464c4454")).orElseThrow();
    }

    /**
     * The reason this task existed: one oracle script per asset, so a token/token loan needs two
     * withdrawals. If these ever collapsed to a single credential, the whole two-leg design would
     * be wrong.
     */
    @Test
    void everyAssetHasItsOwnOracleCredential() throws Exception {
        var entries = clientFromFixture().entries();
        var credentials = entries.stream().map(OracleEntry::withdrawCredentialHash).distinct().toList();

        assertEquals(entries.size(), credentials.size(),
                "19 assets must resolve to 19 distinct credentials — a token/token loan needs two withdrawals");
        credentials.forEach(c -> assertEquals(56, c.length(), "a credential hash is 28 bytes"));
    }

    /**
     * Decoded independently of cardano-client-lib: the bech32 payload of
     * {@code stake1799y3huw4j0n4weehlx3t6xvst50ge0vuv32ghk50mm7hwg5ucz8p} is header {@code 0xf1}
     * (script stake, mainnet) followed by this 28-byte credential.
     */
    @Test
    void theRewardAddressDecodesToItsScriptCredential() {
        assertEquals("4a48df8eac9f3abb39bfcd15e8cc82e8f465ece322a45ed47ef7ebb9",
                FluidOracleClient.withdrawCredentialHash(
                        "stake1799y3huw4j0n4weehlx3t6xvst50ge0vuv32ghk50mm7hwg5ucz8p"));
    }

    @Test
    void aBlankRewardAddressYieldsNoCredentialRatherThanThrowing() {
        assertNull(FluidOracleClient.withdrawCredentialHash(""));
        assertNull(FluidOracleClient.withdrawCredentialHash(null));
    }

    @Test
    void referenceUtxosParseIntoTransactionInputs() throws Exception {
        var entry = fldt();
        assertEquals("e874273fd5a4765920837d3ec0d0a3bdcb6e689911335de971a38b5ca4a881f5",
                entry.referenceInput().getTransactionId());
        assertEquals(0, entry.referenceInput().getIndex());
        assertEquals("e5943f9241ceaaae437c35d8d5e769cbae60e2eebb0f5077ac9ebd54aa23a3fb",
                entry.referenceScript().getTransactionId());
        assertNull(FluidOracleClient.utxoRef("no-hash-marker"), "a malformed ref must not throw");
    }

    /**
     * The oracle NFT is how a loan datum names its oracle, so the entry has to be reachable that
     * way and not only by the asset it prices.
     */
    @Test
    void anEntryIsReachableByItsOracleNft() throws Exception {
        var client = clientFromFixture();
        var entry = fldt();
        assertEquals("93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b",
                entry.oracleToken().policyId());
        assertEquals("6f7261636c65464c44544333", entry.oracleToken().assetName());

        var byNft = client.findEntryByOracleToken(entry.oracleToken()).orElseThrow();
        assertEquals(entry.token(), byNft.token(), "both indexes must resolve to the same entry");
    }

    /** Signatures must arrive as key positions, because that is what the redeemer carries. */
    @Test
    void signaturesAreResolvedToKeyPositions() throws Exception {
        var entry = fldt();
        assertEquals(1, entry.verificationKeys().size());
        assertEquals(1, entry.signatures().size());
        assertEquals(0, entry.signatures().getFirst().keyPosition());
        assertFalse(entry.signatures().getFirst().signatureHex().isBlank());
        assertTrue(entry.hasEnoughSignatures());
    }

    /**
     * fGLD is the only multi-key oracle in the registry, and therefore the only entry that carries
     * any evidence at all about the position mapping — everything else has a single key, where
     * position 0 is true by construction.
     */
    @Test
    void theMultiKeyOracleMapsEachSignatureToItsOwnPosition() throws Exception {
        var fgld = clientFromFixture().findEntryByOracleToken(new AssetType(
                        "93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b", "6f7261636c6546676c64"))
                .orElseThrow();

        assertEquals(3, fgld.verificationKeys().size());
        assertEquals(List.of(0, 1, 2), fgld.signatures().stream().map(s -> s.keyPosition()).toList());
    }

    /** A silent hole in the parse would show up as a missing deployment field, not an exception. */
    @Test
    void everyParsedEntryCarriesAFullDeployment() throws Exception {
        var client = clientFromFixture();
        assertEquals(19, client.trackedAssets());

        for (OracleEntry entry : client.entries()) {
            String where = entry.token().toUnit();
            assertNotNull(entry.rewardAddress(), where);
            assertNotNull(entry.withdrawCredentialHash(), where);
            assertNotNull(entry.referenceInput(), where);
            assertNotNull(entry.referenceScript(), where);
            assertFalse(entry.oracleToken().policyId().isBlank(), where);

            // Charli3-backed feeds carry no signatures at all — they are verified against a
            // reference input instead — so only signed feeds owe us keys.
            if (entry.feed().variant() == OraclePriceFeed.Variant.AGGREGATED) {
                assertFalse(entry.verificationKeys().isEmpty(), where);
                assertFalse(entry.signatures().isEmpty(), where);
                assertTrue(entry.hasEnoughSignatures(), where + " cannot meet its own threshold");
                assertTrue(entry.usableForLiquidation(), where);
            }
        }
    }

    /**
     * OADA is served by Charli3, and the difference is not cosmetic: there is no
     * {@code multisigOracle} to sign anything, and {@code validators/oracle.ak} checks a
     * {@code PriceDataCharlie} redeemer structurally against a Charli3 reference input instead —
     * which the registry publishes at {@code supportedOracle.c3.referenceInput} and this entry
     * captures as {@link OracleEntry#charlieProviderReferenceInput()}. That, not a signature count,
     * is what makes it usable for liquidation.
     */
    @Test
    void charli3BackedFeedsAreUsableForLiquidationViaTheirReferenceInput() throws Exception {
        var oada = clientFromFixture()
                .findEntry(new AssetType("f6099832f9563e4cf59602b3351c3c5a8a7dda2d44575ef69b82cf8d", ""))
                .orElseThrow();

        assertEquals(OraclePriceFeed.Variant.PRICE_DATA_CHARLIE, oada.feed().variant());
        assertTrue(oada.signatures().isEmpty(), "a c3 feed carries no signatures");
        assertFalse(oada.hasEnoughSignatures());
        assertTrue(oada.usableForLiquidation(), "a c3 feed is validated against its reference input, not signatures");
        assertNotNull(oada.feed().price(), "and it still has a usable price for reporting");

        assertNotNull(oada.charlieProviderReferenceInput());
        assertEquals("b9e73039012d9ce57ce92348e578b608d13a09aa6afd8c98cf7c4cf2d32f219f",
                oada.charlieProviderReferenceInput().getTransactionId());
        assertEquals(0, oada.charlieProviderReferenceInput().getIndex());
    }

    /**
     * A c3 entry whose registry node omits {@code supportedOracle.c3.referenceInput} still parses
     * and still prices — but must refuse to claim liquidatability, mirroring the missing-publicKeys
     * rule for multisig feeds: guess and a real transaction is rejected with nothing to debug, so
     * we say no here instead.
     */
    @Test
    void aC3EntryWithoutAReferenceInputIsPriceableAndNotLiquidatable() throws Exception {
        String json = """
                [{
                  "active": true,
                  "preferredOracle": "c3",
                  "token": { "policyId": "dddddddddddddddddddddddddddddddddddddddddddddddddddddddd", "assetName": "" },
                  "fluidOracle": {
                    "policyId": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                    "assetName": "6f7261636c65",
                    "rewardAddress": "stake1799y3huw4j0n4weehlx3t6xvst50ge0vuv32ghk50mm7hwg5ucz8p",
                    "referenceInput": "aa00000000000000000000000000000000000000000000000000000000000000#0",
                    "referenceScript": "bb00000000000000000000000000000000000000000000000000000000000000#0"
                  },
                  "supportedOracle": {
                    "c3": {
                      "validFrom": 1786000000000,
                      "validTo": 1786000600000,
                      "tokenPriceInLovelaces": 1,
                      "tokenPriceDenominator": 1
                    }
                  }
                }]
                """;
        var client = new FluidOracleClient("http://unused.invalid");
        client.load(new ObjectMapper().readTree(json));
        var entry = client.findEntry(
                new AssetType("dddddddddddddddddddddddddddddddddddddddddddddddddddddddd", "")).orElseThrow();

        assertEquals(OraclePriceFeed.Variant.PRICE_DATA_CHARLIE, entry.feed().variant());
        assertNotNull(entry.feed().price(), "still priceable without a reference input");
        assertNull(entry.charlieProviderReferenceInput(), "the registry node omitted referenceInput");
        assertFalse(entry.usableForLiquidation(), "refuse rather than guess at a missing reference input");
    }

    /**
     * {@code parse} wraps the whole registry entry in one {@code catch(Exception)}, so a thrown
     * exception from deep inside {@code utxoRef} would not just null the reference input — it would
     * discard the entire entry, taking its price down with it. A malformed index (non-numeric) must
     * therefore degrade to a null reference input, not vanish the asset.
     */
    @Test
    void aC3EntryWithAMalformedIndexIsPriceableAndNotLiquidatable() throws Exception {
        String json = """
                [{
                  "active": true,
                  "preferredOracle": "c3",
                  "token": { "policyId": "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "assetName": "" },
                  "fluidOracle": {
                    "policyId": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                    "assetName": "6f7261636c65",
                    "rewardAddress": "stake1799y3huw4j0n4weehlx3t6xvst50ge0vuv32ghk50mm7hwg5ucz8p",
                    "referenceInput": "aa00000000000000000000000000000000000000000000000000000000000000#0",
                    "referenceScript": "bb00000000000000000000000000000000000000000000000000000000000000#0"
                  },
                  "supportedOracle": {
                    "c3": {
                      "referenceInput": "aa00000000000000000000000000000000000000000000000000000000000000#abc",
                      "validFrom": 1786000000000,
                      "validTo": 1786000600000,
                      "tokenPriceInLovelaces": 1,
                      "tokenPriceDenominator": 1
                    }
                  }
                }]
                """;
        var client = new FluidOracleClient("http://unused.invalid");
        client.load(new ObjectMapper().readTree(json));
        var entry = client.findEntry(
                new AssetType("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", ""))
                .orElseThrow(() -> new AssertionError(
                        "a malformed c3 referenceInput must not discard the whole entry"));

        assertEquals(OraclePriceFeed.Variant.PRICE_DATA_CHARLIE, entry.feed().variant());
        assertNotNull(entry.feed().price(), "still priceable despite the malformed index");
        assertNull(entry.charlieProviderReferenceInput(), "a non-numeric index cannot resolve to a UTxO");
        assertFalse(entry.usableForLiquidation());
    }

    /**
     * {@code "#0"} on its own has a well-formed index but an empty transaction id — not a real
     * UTxO reference. Accepting it would make {@link OracleEntry#usableForLiquidation()} claim a
     * reference input that resolves to nothing.
     */
    @Test
    void aC3EntryWithAnEmptyTransactionIdIsPriceableAndNotLiquidatable() throws Exception {
        String json = """
                [{
                  "active": true,
                  "preferredOracle": "c3",
                  "token": { "policyId": "ffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "assetName": "" },
                  "fluidOracle": {
                    "policyId": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                    "assetName": "6f7261636c65",
                    "rewardAddress": "stake1799y3huw4j0n4weehlx3t6xvst50ge0vuv32ghk50mm7hwg5ucz8p",
                    "referenceInput": "aa00000000000000000000000000000000000000000000000000000000000000#0",
                    "referenceScript": "bb00000000000000000000000000000000000000000000000000000000000000#0"
                  },
                  "supportedOracle": {
                    "c3": {
                      "referenceInput": "#0",
                      "validFrom": 1786000000000,
                      "validTo": 1786000600000,
                      "tokenPriceInLovelaces": 1,
                      "tokenPriceDenominator": 1
                    }
                  }
                }]
                """;
        var client = new FluidOracleClient("http://unused.invalid");
        client.load(new ObjectMapper().readTree(json));
        var entry = client.findEntry(
                new AssetType("ffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "")).orElseThrow();

        assertEquals(OraclePriceFeed.Variant.PRICE_DATA_CHARLIE, entry.feed().variant());
        assertNotNull(entry.feed().price(), "still priceable despite the empty transaction id");
        assertNull(entry.charlieProviderReferenceInput(), "an empty transaction id resolves to no UTxO");
        assertFalse(entry.usableForLiquidation());
    }

    /**
     * {@code POOLED} and {@code PRICE_DATA_ORCFAX} are not modelled at all — no converter, no
     * signature scheme understood here — so they must stay unusable regardless of what else the
     * entry carries. {@code POOLED.price()} throws, so this test must never call it.
     */
    @Test
    void unmodelledVariantsAreNeverUsableForLiquidation() {
        var token = new AssetType("11111111111111111111111111111111111111111111111111111111", "");
        var pooled = new OracleEntry(token, token, "reward", "cred", null, null,
                List.of(), 0,
                new OraclePriceFeed(OraclePriceFeed.Variant.POOLED, token, BigInteger.ONE, BigInteger.ONE, 0L, 0L),
                List.of(), null);
        var orcfax = new OracleEntry(token, token, "reward", "cred", null, null,
                List.of(), 0,
                new OraclePriceFeed(OraclePriceFeed.Variant.PRICE_DATA_ORCFAX, token, BigInteger.ONE,
                        BigInteger.ONE, 0L, 0L),
                List.of(), null);

        assertFalse(pooled.usableForLiquidation(), "Pooled feeds are not modelled and must stay unusable");
        assertFalse(orcfax.usableForLiquidation(), "Orcfax feeds are not modelled and must stay unusable");
    }

    /**
     * The {@code "c3".equals(preferred)} guard must be exact: a multisig-preferred entry that
     * happens to also carry a {@code supportedOracle.multisig.referenceInput} field (not a real
     * registry shape, but worth guarding against) must not have it picked up as a Charli3 reference
     * input.
     */
    @Test
    void aMultisigEntryNeverCapturesAReferenceInputAsCharlieS() throws Exception {
        String json = """
                [{
                  "active": true,
                  "preferredOracle": "multisig",
                  "token": { "policyId": "22222222222222222222222222222222222222222222222222222222", "assetName": "" },
                  "fluidOracle": {
                    "policyId": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                    "assetName": "6f7261636c65",
                    "rewardAddress": "stake1799y3huw4j0n4weehlx3t6xvst50ge0vuv32ghk50mm7hwg5ucz8p",
                    "referenceInput": "aa00000000000000000000000000000000000000000000000000000000000000#0",
                    "referenceScript": "bb00000000000000000000000000000000000000000000000000000000000000#0"
                  },
                  "multisigOracle": { "publicKeys": [], "requiredSignatures": 1 },
                  "supportedOracle": {
                    "multisig": {
                      "referenceInput": "cc00000000000000000000000000000000000000000000000000000000000000#0",
                      "validFrom": 1786000000000,
                      "validTo": 1786000600000,
                      "tokenPriceInLovelaces": 1,
                      "tokenPriceDenominator": 1
                    }
                  }
                }]
                """;
        var client = new FluidOracleClient("http://unused.invalid");
        client.load(new ObjectMapper().readTree(json));
        var entry = client.findEntry(
                new AssetType("22222222222222222222222222222222222222222222222222222222", "")).orElseThrow();

        assertEquals(OraclePriceFeed.Variant.AGGREGATED, entry.feed().variant());
        assertNull(entry.charlieProviderReferenceInput(),
                "only a c3-preferred entry may capture a Charli3 reference input");
    }

    /**
     * A multisig entry can meet its signature threshold and still be unbuildable: {@code
     * retrieve_oracle_data} requires the oracle NFT reference input, and a malformed {@code
     * fluidOracle.referenceInput} ({@code "#0"} — an empty transaction id) parses to a null
     * reference input. {@link OracleEntry#usableForLiquidation()} must fail closed on that null even
     * though every signature is present.
     */
    @Test
    void aMultisigEntryWithAnUnparseableReferenceInputIsNotUsableForLiquidation() throws Exception {
        String key = "0".repeat(64);
        String json = """
                [{
                  "active": true,
                  "preferredOracle": "multisig",
                  "token": { "policyId": "33333333333333333333333333333333333333333333333333333333", "assetName": "" },
                  "fluidOracle": {
                    "policyId": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                    "assetName": "6f7261636c65",
                    "rewardAddress": "stake1799y3huw4j0n4weehlx3t6xvst50ge0vuv32ghk50mm7hwg5ucz8p",
                    "referenceInput": "#0",
                    "referenceScript": "bb00000000000000000000000000000000000000000000000000000000000000#0"
                  },
                  "multisigOracle": { "publicKeys": ["%s"], "requiredSignatures": 1 },
                  "supportedOracle": {
                    "multisig": {
                      "validFrom": 1786000000000,
                      "validTo": 1786000600000,
                      "tokenPriceInLovelaces": 1,
                      "tokenPriceDenominator": 1,
                      "multisigOracle": {
                        "requiredSignatures": 1,
                        "signatures": [{ "publicKey": "%s", "signature": "ff" }]
                      }
                    }
                  }
                }]
                """.formatted(key, key);
        var client = new FluidOracleClient("http://unused.invalid");
        client.load(new ObjectMapper().readTree(json));
        var entry = client.findEntry(
                new AssetType("33333333333333333333333333333333333333333333333333333333", "")).orElseThrow();

        assertNotNull(entry.feed().price(), "still priceable despite the malformed reference input");
        assertNull(entry.referenceInput(), "an empty transaction id resolves to no reference UTxO");
        assertTrue(entry.hasEnoughSignatures(), "the signature threshold is met");
        assertFalse(entry.usableForLiquidation(),
                "no parseable oracle reference input means no liquidation can be built, signatures notwithstanding");
    }

    /**
     * The c3 twin of {@link #aMultisigEntryWithAnUnparseableReferenceInputIsNotUsableForLiquidation()}:
     * a c3 entry whose Charli3 provider reference input parses fine (so {@link
     * OracleEntry#charlieProviderReferenceInput()} is non-null) but whose {@code
     * fluidOracle.referenceInput} is malformed. The oracle NFT reference input is required for a c3
     * feed too, so a null one must fail closed — the guard is one invariant over every variant.
     */
    @Test
    void aC3EntryWithAnUnparseableFluidOracleReferenceInputIsNotUsableForLiquidation() throws Exception {
        String json = """
                [{
                  "active": true,
                  "preferredOracle": "c3",
                  "token": { "policyId": "44444444444444444444444444444444444444444444444444444444", "assetName": "" },
                  "fluidOracle": {
                    "policyId": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                    "assetName": "6f7261636c65",
                    "rewardAddress": "stake1799y3huw4j0n4weehlx3t6xvst50ge0vuv32ghk50mm7hwg5ucz8p",
                    "referenceInput": "#0",
                    "referenceScript": "bb00000000000000000000000000000000000000000000000000000000000000#0"
                  },
                  "supportedOracle": {
                    "c3": {
                      "referenceInput": "aa00000000000000000000000000000000000000000000000000000000000000#0",
                      "validFrom": 1786000000000,
                      "validTo": 1786000600000,
                      "tokenPriceInLovelaces": 1,
                      "tokenPriceDenominator": 1
                    }
                  }
                }]
                """;
        var client = new FluidOracleClient("http://unused.invalid");
        client.load(new ObjectMapper().readTree(json));
        var entry = client.findEntry(
                new AssetType("44444444444444444444444444444444444444444444444444444444", "")).orElseThrow();

        assertEquals(OraclePriceFeed.Variant.PRICE_DATA_CHARLIE, entry.feed().variant());
        assertNotNull(entry.charlieProviderReferenceInput(),
                "the Charli3 provider reference input parsed fine — only fluidOracle.referenceInput is malformed");
        assertNull(entry.referenceInput(), "an empty transaction id resolves to no reference UTxO");
        assertFalse(entry.usableForLiquidation(),
                "the oracle NFT reference input is required for a c3 feed too, so a null one fails closed");
    }

    /**
     * T-012(b): the transaction id must be exactly 64 hex characters. A shorter, longer, or non-hex
     * id resolves to no UTxO; a well-formed 64-hex id parses in either case — the pattern is
     * {@code [0-9a-fA-F]}, so case must not be rejected.
     */
    @Test
    void utxoRefRequiresExactlySixtyFourHexForTheTransactionId() {
        assertNull(FluidOracleClient.utxoRef("a".repeat(63) + "#0"), "63 hex chars is too short");
        assertNull(FluidOracleClient.utxoRef("a".repeat(65) + "#0"), "65 hex chars is too long");
        assertNull(FluidOracleClient.utxoRef("a".repeat(63) + "g#0"),
                "64 chars with a non-hex char is not a transaction id");

        String lower = "ab".repeat(32); // 64 lowercase hex chars
        var fromLower = FluidOracleClient.utxoRef(lower + "#0");
        assertNotNull(fromLower, "a valid lowercase 64-hex id parses");
        assertEquals(lower, fromLower.getTransactionId());
        assertEquals(0, fromLower.getIndex());

        String mixed = "AbCdEf0123456789".repeat(4); // 64 mixed/upper-case hex chars
        var fromMixed = FluidOracleClient.utxoRef(mixed + "#0");
        assertNotNull(fromMixed, "case must not be rejected — the pattern accepts [0-9a-fA-F]");
        assertEquals(mixed, fromMixed.getTransactionId());
        assertEquals(0, fromMixed.getIndex());
    }
}
