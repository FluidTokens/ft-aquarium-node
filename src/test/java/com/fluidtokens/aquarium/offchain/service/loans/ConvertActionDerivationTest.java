package com.fluidtokens.aquarium.offchain.service.loans;

import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The last underivable field in the deployment, derived.</b>
 *
 * <p>{@code LoansConfigVerifier} carried the comment <i>"Field 5 … is deliberately unchecked: it takes
 * five Minswap parameters we do not have"</i> until 2026-09-03. FluidTokens supplied them; applied to
 * the vendored blueprint they must reproduce <b>exactly</b> what the mainnet LMConfigDatum publishes.
 *
 * <p>Green means every credential in the deployment is now knowable from the artefact we ship. Red
 * would mean the quoted parameters are not the ones FluidTokens built with — a finding about what is
 * possible, not a misconfiguration.
 */
class ConvertActionDerivationTest {

    private static final String MAINNET_CONFIG = "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416";
    private static final String MAINNET_LM_CONFIG = "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4";
    private static final String PREVIEW_CONFIG = "d46f626fc11750409cf44f3d202f48d1b5df41ad35d62a7364b8e22e";
    private static final String PREVIEW_LM_CONFIG = "a7d4b762c5a6197ab3b169c2ff1945fdcd4c21cc5f4c180e75441a13";
    private static final String ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    /** The published mainnet parameterisation, as it ships in application.yaml. */
    private static final String POOL_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String POOL_SPEND = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String ORDER_SPEND = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";

    /**
     * Field 5 of the mainnet LMConfigDatum, read off chain.
     *
     * <p>⛔ <b>MOVED 2026-09-04 11:19:16 UTC</b>, when FluidTokens updated the config IN PLACE to point
     * at their fixed convert action — the config UTxO went {@code 7b9f20db…#1} → {@code 8296a2fe…#0},
     * this field alone changed, and a reference script {@code e4e47ab1…#0} publishing the new hash
     * appeared. The previous value was {@code ed8d41e48d2b48c23c1673493e133b2e5a3300555026cab6c729683b},
     * the action that could not decode a live Minswap pool (findings §51).
     *
     * <p>⚠ This test now passes because {@code loans-v4.plutus.json} was re-vendored to
     * {@code bb4349c} — <b>and the two facts are checked separately on purpose</b>.
     * {@code MainnetConvertActionDerivationTest} proves FluidTokens' committed source derives this
     * hash; this one proves the ARTEFACT WE SHIP does. They would disagree if the vendored file were
     * ever something other than what FluidTokens built, which is the whole §51/§53 failure class.
     */
    private static final String MAINNET_CONVERT_ACTION =
            "dc71541066c95303794863f0a2889fb217a6cc5498e53ad3e077339a";
    /** Field 5 of the live preview LMConfigDatum — a DIFFERENT Minswap deployment's. */
    private static final String PREVIEW_CONVERT_ACTION =
            "aa3628d86e3f16b7d797d0633087859c11e3d200a5defc8ff0fc920e";

    private static LoansContractRegistry registry(String config, String lmConfig,
                                                  String poolPolicy, String poolSpend, String orderSpend) {
        return new LoansContractRegistry(config, lmConfig, ASSET_NAME, SMART_TOKENS,
                poolPolicy, poolSpend, orderSpend);
    }

    /** ⛔ THE VERDICT: FluidTokens' eleven parameters derive their mainnet convert action. */
    @Test
    void theQuotedMinswapParametersDeriveTheMainnetConvertAction() {
        assertEquals(MAINNET_CONVERT_ACTION,
                registry(MAINNET_CONFIG, MAINNET_LM_CONFIG, POOL_POLICY, POOL_SPEND, ORDER_SPEND)
                        .getLmLiquidateAndConvertActionScriptHash(),
                "this is what the mainnet LMConfigDatum publishes at field 5; a mismatch would mean "
                        + "the quoted parameters are not the ones FluidTokens built with");
    }

    /**
     * ⚠ <b>And the same coordinates do NOT derive preview</b> — which is the whole reason convert is
     * unavailable there and is reported rather than treated as a fault. Preview is parameterised for a
     * Minswap deployment we cannot identify, and none exists on preview anyway (findings §28.1).
     */
    @Test
    void thoseSameCoordinatesDoNotDerivePreviewAndThatIsExpected() {
        String derived = registry(PREVIEW_CONFIG, PREVIEW_LM_CONFIG, POOL_POLICY, POOL_SPEND, ORDER_SPEND)
                .getLmLiquidateAndConvertActionScriptHash();

        assertNotEquals(PREVIEW_CONVERT_ACTION, derived,
                "if these ever DID match, preview would have gained a Minswap deployment and §28.1's "
                        + "no-rehearsal-on-preview finding would need re-reading");
        // ⚠ RE-MEASURED 2026-09-04 from 52b778c8…, when loans-v4.plutus.json was re-vendored to
        // FluidTokens' bb4349c. It moved because the convert action's compiled code changed, which is
        // the whole point of the re-vendor — and it is pinned again rather than dropped, because an
        // inequality that stops being checked against a known value stops noticing anything.
        assertEquals("04540b1465a0c1134d43e440583fff1804e934e52013f208e15bd0fe", derived,
                "the measured value, pinned so a change in the derivation is visible rather than "
                        + "hidden behind the inequality above");
    }

    /** Without the coordinates there is no hash, and no guess either. */
    @Test
    void withoutTheMinswapCoordinatesTheHashIsNullRatherThanApproximated() {
        assertNull(registry(MAINNET_CONFIG, MAINNET_LM_CONFIG, null, null, null)
                .getLmLiquidateAndConvertActionScriptHash());
        assertNull(registry(MAINNET_CONFIG, MAINNET_LM_CONFIG, POOL_POLICY, "", ORDER_SPEND)
                .getLmLiquidateAndConvertActionScriptHash(),
                "partial coordinates are as unusable as none — a hash derived from two of three is a "
                        + "real-looking hash for nothing");
    }

    /** Every Minswap parameter must move the hash, or one of them is not reaching the derivation. */
    @Test
    void eachMinswapParameterActuallyParticipates() {
        String base = registry(MAINNET_CONFIG, MAINNET_LM_CONFIG, POOL_POLICY, POOL_SPEND, ORDER_SPEND)
                .getLmLiquidateAndConvertActionScriptHash();
        String other = "aa".repeat(28);

        assertNotEquals(base, registry(MAINNET_CONFIG, MAINNET_LM_CONFIG, other, POOL_SPEND, ORDER_SPEND)
                .getLmLiquidateAndConvertActionScriptHash(), "pool policy id");
        assertNotEquals(base, registry(MAINNET_CONFIG, MAINNET_LM_CONFIG, POOL_POLICY, other, ORDER_SPEND)
                .getLmLiquidateAndConvertActionScriptHash(), "pool spend script hash");
        assertNotEquals(base, registry(MAINNET_CONFIG, MAINNET_LM_CONFIG, POOL_POLICY, POOL_SPEND, other)
                .getLmLiquidateAndConvertActionScriptHash(), "order spend script hash");
    }

    /** The shipped defaults ARE the verified mainnet parameterisation, not placeholders. */
    @Test
    void theShippedDefaultsAreTheVerifiedMainnetCoordinates() {
        var cfg = new AppConfig.LoansConfiguration();
        assertEquals(POOL_POLICY, cfg.getMinswapPoolPolicyId());
        assertEquals(POOL_SPEND, cfg.getMinswapPoolSpendScriptHash());
        assertEquals(ORDER_SPEND, cfg.getMinswapOrderSpendScriptHash());
    }

    /** The published field this whole test is about, read from the recorded mainnet datum. */
    @Test
    void theMainnetLmConfigDatumReallyPublishesThatHashAtFieldFive() throws IOException {
        try (InputStream is = ConvertActionDerivationTest.class
                .getResourceAsStream("/loans-v4/mainnet-lm-config-datum.hex")) {
            String hex = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
            assertTrue(hex.contains(MAINNET_CONVERT_ACTION),
                    "the fixture must actually carry the hash this test asserts against, or the "
                            + "assertion is comparing a constant with itself");
        }
    }
}
