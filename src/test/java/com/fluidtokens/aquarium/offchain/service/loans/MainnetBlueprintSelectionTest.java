package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.LoansConfigVerifier;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the image ships and constructs one latest Lending v4 blueprint on every profile. */
class MainnetBlueprintSelectionTest {

    private static final String BLUEPRINT = "loans-v4.plutus.json";
    private static final String BLUEPRINT_SHA256 =
            "a638e71ca047b668f74eeeff1b80e76695626ddfe506a9ca1d2721e9a1e72704"; // 2026-10-01 hybrid, see blueprint PROVENANCE
    private static final String CONFIG = "235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200";
    private static final String LM_CONFIG = "fb6ae2027358b4a0b62710eb95102d87fa13f66ecf55d8943699c492";
    private static final String ASSET = "706172616d6574657273";
    private static final String SMART = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";
    private static final String MS_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String MS_POOL = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MS_ORDER = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";
    /** LMConfigDatum[5] since FluidTokens' 2026-10-01 redeploy (was 2432ab45…, parameterised by the old claim). */
    private static final String CONVERT = "cbf3e8c5a42e6d0f505540d5aa2104b7e29a9c3a7ade581df74ba774";
    // The captured fourth-deployment preview datum against the shipped artefact. Since FluidTokens'
    // 2026-10-01 mainnet redeploy (FTAI-001) it also differs at [11] claim, [23] borrow, [24] sell and the
    // LenderManager actions parameterised by the claim or changed in code (LM[2], [3], [4], [6]) --
    // preview was not redeployed, so each of these is the artefact moving, not the chain.
    private static final List<String> OLD_PREVIEW_MISMATCHES = List.of(
            "ConfigDatum[2]: derived 1c330cfbd58d994945d29c7c52ec001d054b93f733317ec59d9a0537, "
                    + "chain a33aee4034165f1772e57af5fb975f26c35f7e9080b7e44b4634f227",
            "ConfigDatum[8]: derived 515009399bc0fd2bb204b0a50973a1285415159bed4213e315d739b7, "
                    + "chain bf8c4378bab7de15baddbb5d8805255d89174c08bf36179c21cad685",
            "ConfigDatum[11]: derived 66cdb92b631592ac08c32ff194c735c408b5b3776253f517b75c2024, "
                    + "chain c6e0c4395cf22e08f918ca996d7db49faba793dbd6b647160168ff39",
            "ConfigDatum[14]: derived 21f4bde7524bbab159eb0293dac262f1193c6266385d983ee761e363, "
                    + "chain 1628910a5fbdba415c3b1bf7304672106659ac527442f10701472753",
            "ConfigDatum[23]: derived 2ecae5102d682a7bb8f622e357b2129112b5d50a39abf0f9a3fa1f08, "
                    + "chain 344755c30db0617ff43cb41e5212379b729985352a213371b15c90cd",
            "ConfigDatum[24]: derived 5dcebd73c56d86ab0c8049d09748dba14a6c19734aab121eb0613d25, "
                    + "chain db9a5bf043f37e744bbb43b96ec89a3e175f7c5523d02dd563ed9c56",
            "ConfigDatum[26]: derived 1322b6d1e7e46ac543769a8fcfb43840606f040d2e9efa2e59389d86, "
                    + "chain b4ad9a6f2710d68067177e0de5a4378ebe4fcdfdc929c7488479c313",
            "ConfigDatum[27]: derived c2023c909f66d50886e82ba86a03382313bbb8e994789e0aba588b3d, "
                    + "chain 45ce890c9bcf70f6eed629b5db7c0622e44ca1003e001a2cf951518f",
            "ConfigDatum[28]: derived f74b887491c86b1a1b7785c01f15cb7551f4520174589e22efb0df02, "
                    + "chain d815766d61c1241742ff78164cdf8edaef1746a99a242a7fb7938aa6",
            "LMConfigDatum[2]: derived f3c7a201440b39458111ab44c26863c4d089e66e71ae847fb87233bd, "
                    + "chain e0a13838d176cea9de466afe2075f38f682603013604021a3959700f",
            "LMConfigDatum[3]: derived f9be2926201f9ed40da47cc77a40db0ad8b39d21842511116ddb644d, "
                    + "chain dd4709091734af2dc36321e774cf496222a1f92377ad6c5bef100457",
            "LMConfigDatum[4]: derived 3a155105a91c371b74b96f480114ef1d4ec80389d27902426f553c99, "
                    + "chain 00b8a30bd2f18962e527d7c03712e86077a688bfce7e2934ef70034d",
            "LMConfigDatum[6]: derived 1760b3c462d707870b904eca90145dc48619d0e770253728412f3bf3, "
                    + "chain 70b149e7c84a4cf47fb273d87ed2fe97562f0148bfce0b4681afa480");

    @Configuration(proxyBeanMethods = false)
    @Import({AppConfig.LoansConfiguration.class, LoansContractRegistry.class})
    static class Ctx { }

    private static List<PropertySource<?>> yaml() throws IOException {
        return new YamlPropertySourceLoader().load("application.yaml", new ClassPathResource("application.yaml"));
    }

    private static ApplicationContextRunner profile(int document) throws IOException {
        List<PropertySource<?>> docs = yaml();
        return new ApplicationContextRunner().withUserConfiguration(Ctx.class)
                .withInitializer(ctx -> {
                    ctx.getEnvironment().getPropertySources().addLast(docs.getFirst());
                    if (document > 0) {
                        ctx.getEnvironment().getPropertySources().addFirst(docs.get(document));
                    }
                });
    }

    private static String fixture(String name) throws IOException {
        try (InputStream in = MainnetBlueprintSelectionTest.class.getResourceAsStream("/loans-v4/" + name)) {
            if (in == null) throw new IllegalStateException("missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = MainnetBlueprintSelectionTest.class.getResourceAsStream("/" + name)) {
            if (in == null) return null;
            return in.readAllBytes();
        }
    }

    private static LoansConfigVerifier verifier(LoansContractRegistry registry, String network) {
        var configuredNetwork = new AppConfig.Network();
        configuredNetwork.setNetworkForTest(network);
        return new LoansConfigVerifier(registry, SMART, configuredNetwork, null, true);
    }

    private static LoansContractRegistry mainnet() {
        return new LoansContractRegistry(CONFIG, LM_CONFIG, ASSET, SMART,
                MS_POLICY, MS_POOL, MS_ORDER);
    }

    @Test
    void exactlyOneLatestProductionBlueprintIsOnTheClasspath() throws Exception {
        byte[] latest = resource(BLUEPRINT);
        assertTrue(latest != null && latest.length > 0, "the fixed production blueprint is absent");
        assertEquals(BLUEPRINT_SHA256,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(latest)));
        assertNull(resource("loans-v4-mainnet.plutus.json"),
                "a second full production blueprint must not be packaged");
    }

    @Test
    void shippedMainnetConstructsTheFixedRegistryAndVerifiesCurrentDatums() throws IOException {
        profile(0).run(ctx -> {
            assertNull(ctx.getStartupFailure(), () -> "mainnet context failed: " + ctx.getStartupFailure());
            var registry = ctx.getBean(LoansContractRegistry.class);
            // Since 2026-10-01 the live datum carries ONE advisory difference by FluidTokens' design: recast
            // [14] points at their unpublished pause hash. Nothing the node uses may differ.
            var findings = verifier(registry, "mainnet").verifyAgainstBySeverity(
                    fixture("mainnet-config-datum-2026-10-01.hex"), fixture("mainnet-lm-config-datum-2026-10-01.hex"));
            assertEquals(List.of(), findings.enforced(), "nothing the node uses may differ from the live datums");
            assertEquals(1, findings.advisory().size(), () -> "advisory: " + findings.advisory());
            assertTrue(findings.advisory().getFirst().startsWith("ConfigDatum[14]:")
                    && findings.advisory().getFirst().endsWith("64d9b13f973be664a05c22365f90b222b0f9018b94918d3cb5d0220f"),
                    () -> "the only difference must be FluidTokens' recast pause: " + findings.advisory());
        });
    }

    @Test
    void shippedPreviewConstructsButItsOldDatumsHaveTwoKnownMismatches() throws IOException {
        profile(1).run(ctx -> {
            assertNull(ctx.getStartupFailure(), () -> "preview context failed: " + ctx.getStartupFailure());
            List<String> mismatches = verifier(ctx.getBean(LoansContractRegistry.class), "preview")
                    .verifyAgainst(fixture("fourth-deployment-config-datum.hex"),
                            fixture("fourth-deployment-lm-config-datum.hex"));
            assertEquals(OLD_PREVIEW_MISMATCHES, mismatches,
                    "the accepted old-preview divergence changed");
        });
    }

    @Test
    void productionSurfaceHasNoBlueprintSelector() {
        assertFalse(Arrays.stream(AppConfig.LoansConfiguration.class.getDeclaredFields())
                .anyMatch(field -> field.getName().toLowerCase().contains("blueprint")));
        // ⛔ 5 and 8 ARE THE SAME TWO CONSTRUCTORS AS 4 AND 7, each with a leading blueprint-resource
        // argument, and they are test-only by contract rather than by visibility (the rigs live in
        // another package). The guard this test enforces is unchanged: NO production PATH selects a
        // blueprint -- the field check above is what enforces that, and AppConfig still exposes
        // nothing blueprint-shaped, so a deployment cannot reach them.
        //
        // ⚠ They exist because a rig that replays RECORDED on-chain publications must derive from
        // the artefact those publications were built from. Once the shipped artefact moved ahead of
        // preview on 2026-09-17, four pool rigs died on a null coordinate; re-keying their verified
        // coordinate table to the new hashes was the alternative, and it would have paired a new
        // hash with a script that was never published at that address.
        //
        // The set is pinned exactly, so ANOTHER constructor still fails here.
        assertEquals(List.of(1, 4, 5, 7, 8), Arrays.stream(LoansContractRegistry.class.getConstructors())
                .map(constructor -> constructor.getParameterCount()).sorted().toList(),
                "the fixed-configuration, four/seven-parameter and blueprint-pinned five/eight-parameter "
                        + "constructors are the whole API");
    }

    @Test
    void currentMainnetDatumMutationAddsOneSpecificMismatch() throws IOException {
        String current = fixture("mainnet-config-datum-2026-10-01.hex");
        // ConfigDatum[2], the pool policy: present in the CURRENT mainnet datum. The previous
        // target (12773eaf...) belonged to the pre-redeploy datum and no longer appears.
        String published = "20f765d25da3a36644371f7619d97bdccf034f3067921921d9dce0f7";
        String corrupted = "20f765d25da3a36644371f7619d97bdccf034f3067921921d9dce000";
        String mutant = current.replace(published, corrupted);
        assertNotEquals(current, mutant, "the captured mainnet mutation did not apply");

        List<String> mismatches = verifier(mainnet(), "mainnet")
                .verifyAgainstBySeverity(mutant, fixture("mainnet-lm-config-datum-2026-10-01.hex")).enforced();
        assertEquals(1, mismatches.size(), "one corrupted credential must add one mismatch: " + mismatches);
        assertTrue(mismatches.getFirst().contains("ConfigDatum[2]")
                        && mismatches.getFirst().contains(corrupted),
                "the mismatch must identify the corrupted published field: " + mismatches);
    }

    @Test
    void fixedArtifactMatchesPublishedCompoundBytesAndPreservesConvertHash() throws Exception {
        LoansContractRegistry registry = mainnet();
        assertEquals(fixture("mainnet-compound-script-2026-10-01.hex"),
                HexUtil.encodeHexString(registry.getLmCompoundActionScript().serializeScriptBody()));
        assertEquals(CONVERT, registry.getLmLiquidateAndConvertActionScriptHash());
    }
}
