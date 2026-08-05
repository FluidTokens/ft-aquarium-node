package com.fluidtokens.aquarium.offchain;

import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Proves that applying the per-network derivation inputs to the *unapplied*
 * ft-cardano-aquarium-sc blueprint reproduces the exact script hashes that are
 * baked into the pre-applied blueprints on the {@code main} (mainnet) and
 * {@code origin/preview} (preview) branches.
 * <p>
 * If this test is green, runtime parameterisation is provably correct and we can
 * drop the "one branch per network" approach.
 */
class ContractDerivationTest {

    // Unapplied compiled codes, keyed by validator title, loaded from the aiken-sc blueprint.
    private static Map<String, String> unappliedCode() throws Exception {
        Map<String, String> m = new HashMap<>();
        try (InputStream is = ContractDerivationTest.class.getResourceAsStream("/aquarium-sc.plutus.json")) {
            JsonNode root = new ObjectMapper().readTree(is);
            for (JsonNode v : root.get("validators")) {
                m.put(v.get("title").asText(), v.get("compiledCode").asText());
            }
        }
        return m;
    }

    private static String hashOf(String compiledCode) throws Exception {
        return HexUtil.encodeHexString(
                PlutusBlueprintUtil.getPlutusScriptFromCompiledCode(compiledCode, PlutusVersion.v3).getScriptHash());
    }

    private static String apply(String unapplied, PlutusData... params) {
        ListPlutusData list = ListPlutusData.builder().build();
        for (PlutusData p : params) {
            list.add(p);
        }
        return AikenScriptUtil.applyParamToScript(list, unapplied);
    }

    private static BytesPlutusData b(String hex) {
        return BytesPlutusData.of(HexUtil.decodeHexString(hex));
    }

    private record Network(String name,
                           String tx0, long index0,
                           String fldtPolicy, String fldtAsset,
                           String expectedParams, String expectedStaker, String expectedTank) {
    }

    private static final Network MAINNET = new Network("mainnet",
            "45f379b3436263146ab3a5423506ce11555113384d45655b50c77dab8a3473ff", 0L,
            "577f0b1342f8f8f4aed3388b80a8535812950c7a892495c0ecdf0f1e", "0014df10464c4454",
            "f0e403df77b2bfee7c0799ef927b3763165033cbe38bddc802934883",
            "bae773ecdbabb746d2dcd7d1630a5761180b19766803b8a65fa52901",
            "f9724c47299e745cb4f50f9d36cbbadcdf87e015a9d99d927dc4e866");

    private static final Network PREVIEW = new Network("preview",
            "d35f81f6bc88babe5dcf088e3a800ecbb4d75373df6b144f7561d393cb5d9b2f", 1L,
            "0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f62", "0014df1074464c4454",
            "1dc94155a9550a0a0c9b6a0f4d609265e0e7d385a2e4535c9d4cba76",
            "655bacb11218d31f42bb88400cf733846843d0850d8c109396e34ae4",
            "421e18527ff74170b8c6bd780c71417fa2a70953e150a037a985008f");

    private void assertNetworkDerives(Network net) throws Exception {
        Map<String, String> code = unappliedCode();

        // parameters(tx0, index0) -> H_params (also = config-NFT policy)
        String paramsApplied = apply(code.get("parameters.parameters.spend"),
                b(net.tx0()), BigIntPlutusData.of(BigInteger.valueOf(net.index0())));
        String hParams = hashOf(paramsApplied);
        assertEquals(net.expectedParams(), hParams, net.name() + " H_params");

        // staker(H_params, fldt_policy, fldt_asset) -> H_staker
        String stakerApplied = apply(code.get("staker.staker.spend"),
                b(hParams), b(net.fldtPolicy()), b(net.fldtAsset()));
        String hStaker = hashOf(stakerApplied);
        assertEquals(net.expectedStaker(), hStaker, net.name() + " H_staker");

        // tank(stakingContract=H_staker, params_contract_hash=H_params) -> H_tank
        String tankApplied = apply(code.get("tank.tank.spend"), b(hStaker), b(hParams));
        String hTank = hashOf(tankApplied);
        assertEquals(net.expectedTank(), hTank, net.name() + " H_tank");
    }

    @Test
    void mainnetDerivesToKnownHashes() throws Exception {
        assertNetworkDerives(MAINNET);
    }

    @Test
    void previewDerivesToKnownHashes() throws Exception {
        assertNetworkDerives(PREVIEW);
    }
}
