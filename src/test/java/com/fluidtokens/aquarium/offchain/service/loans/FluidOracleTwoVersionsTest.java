package com.fluidtokens.aquarium.offchain.service.loans;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ⛔ <b>Two oracle versions per token, and the liquidation builder must see BOTH (FAB-110).</b>
 *
 * <p>On 2026-09-30 FluidTokens split their oracles: the registry lists 16 tokens twice —
 * {@code oracleVersion 1} (NFT policy {@code 93794f9b…}, Lending v3) and {@code oracleVersion 2}
 * (NFT policy {@code 26e60b20…}, Lending v4) — same asset names, both permanent. A loan names its
 * oracle NFT in its datum, and every live v4 loan on 2026-10-01 named v1.
 *
 * <p>{@code FluidOracleClient.entries()} returned one entry per priced TOKEN (last wins = v2), and
 * {@link LiquidationExecutor#oracleSnapshot()} — the NFT-keyed map every builder resolves a loan's
 * oracle from — is built from it. So every v1 oracle was missing and every live v4 loan refused
 * {@code ORACLE_ENTRY_MISSING} at build, while the scanner (which looks up by NFT) still selected it.
 *
 * <p>This reads the REAL mainnet registry payload of 2026-10-01 through the REAL client and the REAL
 * executor snapshot — never a map assembled by the test, which would supply exactly what production
 * failed to build.
 */
class FluidOracleTwoVersionsTest {

    private static final String NIGHT_V1 = "93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b"
            + "6f7261636c654e69676874";
    private static final String NIGHT_V2 = "26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26"
            + "6f7261636c654e69676874";

    private static FluidOracleClient clientFromMainnetPayload() throws Exception {
        JsonNode payload;
        try (InputStream in = FluidOracleTwoVersionsTest.class
                .getResourceAsStream("/loans-v4/mainnet-oracle-registry-2026-10-01.json")) {
            assertNotNull(in, "registry fixture missing");
            payload = new ObjectMapper().readTree(in);
        }
        FluidOracleClient client = new FluidOracleClient("http://unused.invalid");
        client.load(payload);
        return client;
    }

    private static LiquidationExecutor executorOver(FluidOracleClient client) {
        var network = new AppConfig.Network();
        network.setNetworkForTest("mainnet");
        var configuration = new AppConfig.LiquidationConfiguration(
                AppConfig.LiquidationConfiguration.Mode.SHADOW, 60, 120, 30, BigInteger.ZERO, 200);
        ObjectProvider<FluidOracleClient> provider = new ObjectProvider<>() {
            @Override public FluidOracleClient getObject() { return client; }
            @Override public FluidOracleClient getObject(Object... args) { return client; }
            @Override public FluidOracleClient getIfAvailable() { return client; }
            @Override public FluidOracleClient getIfUnique() { return client; }
        };
        return new LiquidationExecutor(configuration, (com.fluidtokens.aquarium.offchain.service.BlockEventListener) null,
                (com.fluidtokens.aquarium.offchain.service.AppUtxoService) null,
                (com.bloxbean.cardano.client.account.Account) null, (LiquidationCandidateScanner) null,
                (LiquidationUtxoResolver) null, (LiquidateTransactionBuilder) null,
                (PayInAdvanceLiquidationRouter) null, (ConvertLiquidationRouter) null,
                (com.fluidtokens.aquarium.offchain.service.LoansContractRegistry) null,
                (LiquidationDecisionLog) null, (MarketCoverageReporter) null, provider, network,
                (com.bloxbean.cardano.client.api.ProtocolParamsSupplier) null,
                (org.cardanofoundation.conversions.CardanoConverters) null,
                (LiquidationExecutor.TransactionSubmitter) null);
    }

    @Test
    void theClientHoldsEveryRegistryEntryNotOnePerToken() throws Exception {
        FluidOracleClient client = clientFromMainnetPayload();

        assertEquals(35, client.entries().size(),
                "the 2026-10-01 mainnet registry lists 35 oracles across 19 tokens; entries() must "
                        + "return all of them, not one per token");
    }

    @Test
    void theBuilderSnapshotResolvesBothTheV1AndTheV2OracleOfOneToken() throws Exception {
        Map<String, OracleEntry> snapshot = executorOver(clientFromMainnetPayload()).oracleSnapshot();

        OracleEntry v1 = snapshot.get(NIGHT_V1);
        OracleEntry v2 = snapshot.get(NIGHT_V2);
        assertNotNull(v1, "the NIGHT v1 oracle — the one every live v4 NIGHT loan names — is missing "
                + "from the builder's snapshot, so those loans refuse ORACLE_ENTRY_MISSING");
        assertNotNull(v2, "the NIGHT v2 oracle — the one new v4 loans name — is missing");

        assertEquals("5f6bbacd3da81e917812049192fffba44e0c6b2bbd397f8c2a296d7afbee9d6b",
                v1.referenceInput().getTransactionId(), "v1 must carry v1's own reference input");
        assertEquals(11, v1.referenceInput().getIndex());
        assertEquals("4e4b5381376bae774459510b16b3cf4b52cf7437addf88d5dd24d167080d9dcd",
                v2.referenceInput().getTransactionId(), "v2 must carry v2's own reference input");
        assertEquals(12, v2.referenceInput().getIndex());

        assertEquals(35, snapshot.size(), "every registry oracle must be resolvable by its NFT");
    }
}
