package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
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
     * {@code PriceDataCharlie} redeemer against a Charli3 reference input using a
     * {@code provider_ref_input_index} we do not model. It must be priceable for reporting and
     * honestly unbuildable for liquidation — labelling it {@code Aggregated} would produce a
     * redeemer that fails for want of signatures.
     */
    @Test
    void charli3BackedFeedsArePriceableButNotYetLiquidatable() throws Exception {
        var oada = clientFromFixture()
                .findEntry(new AssetType("f6099832f9563e4cf59602b3351c3c5a8a7dda2d44575ef69b82cf8d", ""))
                .orElseThrow();

        assertEquals(OraclePriceFeed.Variant.PRICE_DATA_CHARLIE, oada.feed().variant());
        assertTrue(oada.signatures().isEmpty(), "a c3 feed carries no signatures");
        assertFalse(oada.hasEnoughSignatures());
        assertFalse(oada.usableForLiquidation(), "we cannot build a PriceDataCharlie redeemer yet");
        assertNotNull(oada.feed().price(), "but it still has a usable price for reporting");
    }
}
