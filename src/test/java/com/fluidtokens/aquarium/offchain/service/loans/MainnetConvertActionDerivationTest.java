package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.aiken.AikenScriptUtil;
import com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil;
import com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * ⛔ <b>Is FluidTokens' fix, `bb4349c`, the code they actually deployed to mainnet?</b>
 *
 * <h2>Why this question and not "did they deploy"</h2>
 * At <b>11:19:16 UTC on 2026-09-04</b> the mainnet LenderManager config was updated <b>in place</b> —
 * the config UTxO moved `7b9f20db…#1` → `8296a2fe…#0`, <b>field 5 alone</b> changed from
 * {@code ed8d41e4…} to {@code dc715410…}, every other field byte-identical, and a reference script
 * {@code e4e47ab1…#0} publishing the new hash appeared. That is a deployment.
 *
 * <p><b>It is not, by itself, evidence of WHAT was deployed.</b> A hash on chain says a script exists;
 * it says nothing about which source produced it. Findings §51 and §53 are both instances of one
 * failure — <b>a compiled artefact meeting data from a different build</b> — and accepting
 * {@code dc715410…} as "the fix is live" because it appeared after the fix was pushed would be that
 * same mistake, made by us, in the credulous direction.
 *
 * <h2>What this asserts</h2>
 * Apply the <b>eleven mainnet parameters</b>, in {@link LoansContractRegistry}'s own order and by its
 * own utility, to the unapplied {@code compiledCode} FluidTokens committed at {@code bb4349c} — and
 * require the result to be the hash the chain now publishes.
 *
 * <p>⇒ <b>Green means {@code bb4349c} IS the deployed commit</b>, so re-vendoring it is correct rather
 * than invariant-breaking, and the convert path is genuinely live on mainnet.
 * <b>Red means something else was deployed</b>, and nothing about convert may be believed until that
 * is explained.
 *
 * <p>⚠ <b>No Aiken build is involved and none is needed.</b> The parameter application is the
 * production path ({@code AikenScriptUtil.applyParamToScript}, the same call {@code derive()} makes),
 * so a green here also means the node's own runtime derivation would produce the deployed hash. It
 * also leaves the shared box's Aiken toolchain untouched, which two other sessions depend on.
 */
class MainnetConvertActionDerivationTest {

    /** Quoted first-hand from FluidTokens' tooling; the same constants application.yaml ships. */
    private static final String CONFIG_POLICY_ID = "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416";
    private static final String LM_CONFIG_POLICY_ID = "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4";
    private static final String CONFIG_ASSET_NAME = "706172616d6574657273";
    private static final String SMART_TOKENS_SPEND = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    /** The three Minswap V2 mainnet credentials FluidTokens parameterise the action with (§25.1). */
    private static final String MINSWAP_POOL_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String MINSWAP_POOL_SPEND = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MINSWAP_ORDER_SPEND = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";

    /** Read from the LIVE mainnet LMConfigDatum, field 5, after the 11:19 UTC in-place update. */
    private static final String DEPLOYED_CONVERT_ACTION =
            "dc71541066c95303794863f0a2889fb217a6cc5498e53ad3e077339a";

    /** What the LMConfigDatum published BEFORE it — the broken action (§51). */
    private static final String SUPERSEDED_CONVERT_ACTION =
            "ed8d41e48d2b48c23c1673493e133b2e5a3300555026cab6c729683b";

    private static String fixture(String name) throws IOException {
        try (InputStream is = MainnetConvertActionDerivationTest.class
                .getResourceAsStream("/loans-v4/" + name)) {
            if (is == null) {
                throw new IllegalStateException("fixture not on the classpath: " + name);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static BytesPlutusData b(String hex) {
        return BytesPlutusData.of(HexUtil.decodeHexString(hex));
    }

    private static LoansContractRegistry mainnetRegistry() {
        return new LoansContractRegistry(CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME,
                SMART_TOKENS_SPEND);
    }

    /** The eleven, in the registry's order. The two empty ones are the absent CIP-113 counterparts. */
    @lombok.SneakyThrows
    private static String applyMainnetParameters(String unapplied, LoansContractRegistry registry) {
        ListPlutusData params = ListPlutusData.builder().build();
        for (PlutusData p : new PlutusData[]{
                b(CONFIG_POLICY_ID),
                b(CONFIG_ASSET_NAME),
                b(registry.getLenderManagerSpendScriptHash()),
                b(registry.getAssetManagerSpendScriptHash()),
                b(registry.getAssetManagerWithdrawScriptHash()),
                b(MINSWAP_POOL_POLICY),
                b(MINSWAP_POOL_SPEND),
                b(""),
                b(MINSWAP_ORDER_SPEND),
                b(""),
                // loanClaimPaymentCredential is a Credential, so the Script constructor — rule 3.
                ConstrPlutusData.of(1, b(registry.getLoanClaimActionScriptHash()))}) {
            params.add(p);
        }
        String applied = AikenScriptUtil.applyParamToScript(params, unapplied);
        return HexUtil.encodeHexString(PlutusBlueprintUtil
                .getPlutusScriptFromCompiledCode(applied, PlutusVersion.v3).getScriptHash());
    }

    /**
     * ⛔ THE VERDICT. FluidTokens' committed fix, parameterised for mainnet, must hash to what the
     * chain publishes.
     */
    @Test
    void fluidTokensCommittedFixDerivesTheHashMainnetNowPublishes() throws IOException {
        LoansContractRegistry registry = mainnetRegistry();
        String derived = applyMainnetParameters(
                fixture("ft-bb4349c-convert-action-unapplied.hex"), registry);

        assertEquals(DEPLOYED_CONVERT_ACTION, derived,
                "bb4349c does NOT derive the convert action deployed at 11:19 UTC. Something other "
                        + "than the committed fix is on chain, and nothing about the convert path may "
                        + "be believed until that is explained — this is exactly the §51/§53 shape, a "
                        + "compiled artefact meeting data from a different build. Do NOT re-vendor.");
    }

    /**
     * ⚠ The control, and it is not decoration. Without it a bug that made
     * {@code applyMainnetParameters} return the expected constant by any route would pass silently.
     * The SAME parameters over the SAME code must NOT yield the superseded hash.
     */
    @Test
    void andItIsNotStillDerivingTheSupersededAction() throws IOException {
        String derived = applyMainnetParameters(
                fixture("ft-bb4349c-convert-action-unapplied.hex"), mainnetRegistry());

        assertNotEquals(SUPERSEDED_CONVERT_ACTION, derived,
                "the fixed source derives the OLD hash — which would mean the committed change does "
                        + "not affect the compiled output at all");
    }

    /**
     * ⛔ <b>INVERTED 2026-09-04, and the inversion is the receipt.</b>
     *
     * <p>This assertion was written the other way round: <i>our vendored blueprint still derives the
     * SUPERSEDED hash</i> — the gap the re-vendor existed to close, measured rather than assumed, so
     * that closing it would produce a visible red. <b>It did, and this is what it turned into.</b>
     *
     * <p>⇒ Now it asserts the thing that has to stay true from here: <b>the artefact we SHIP derives
     * the action mainnet actually runs.</b> Together with
     * {@link #fluidTokensCommittedFixDerivesTheHashMainnetNowPublishes()} that is two independent
     * paths to one hash — FluidTokens' committed source, and the file in {@code src/main/resources} —
     * and <b>they are checked separately on purpose</b>. A single test covering both would go green if
     * the vendored file were quietly replaced by anything that happened to derive the same value, and
     * "a compiled artefact meeting data from a different build" is precisely the failure class
     * findings §51 and §53 are both instances of.
     */
    @Test
    void ourVendoredBlueprintNowDerivesTheDeployedAction() {
        LoansContractRegistry registry = new LoansContractRegistry(
                CONFIG_POLICY_ID, LM_CONFIG_POLICY_ID, CONFIG_ASSET_NAME, SMART_TOKENS_SPEND,
                MINSWAP_POOL_POLICY, MINSWAP_POOL_SPEND, MINSWAP_ORDER_SPEND);

        assertEquals(DEPLOYED_CONVERT_ACTION, registry.getLmLiquidateAndConvertActionScriptHash(),
                "the SHIPPED loans-v4.plutus.json no longer derives the convert action mainnet runs. "
                        + "Either the vendored file drifted from FluidTokens' bb4349c, or they moved "
                        + "the deployment again — and the node cannot build a convert liquidation "
                        + "either way, because lender_manager.withdraw only authorises the hash the "
                        + "LMConfigDatum names.");
        assertNotEquals(SUPERSEDED_CONVERT_ACTION,
                registry.getLmLiquidateAndConvertActionScriptHash(),
                "and it is not the pre-11:19-UTC action, which could not decode a live Minswap pool");
    }
}
