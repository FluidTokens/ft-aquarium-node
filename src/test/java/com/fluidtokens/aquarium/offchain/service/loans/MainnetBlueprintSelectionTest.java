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
            "ef1064fd0e7e1b8045a50d1d889388750ce8e23dc8402970be973b73a3a55d62";
    private static final String CONFIG = "235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200";
    private static final String LM_CONFIG = "fb6ae2027358b4a0b62710eb95102d87fa13f66ecf55d8943699c492";
    private static final String ASSET = "706172616d6574657273";
    private static final String SMART = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";
    private static final String MS_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String MS_POOL = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MS_ORDER = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";
    private static final String CONVERT = "2432ab45c54570998ad5379c46fec2276ea72a94c7ffb6510bcd3aa8";
    private static final List<String> OLD_PREVIEW_MISMATCHES = List.of(
            "ConfigDatum[2]: derived 1c330cfbd58d994945d29c7c52ec001d054b93f733317ec59d9a0537, "
                    + "chain a33aee4034165f1772e57af5fb975f26c35f7e9080b7e44b4634f227",
            "ConfigDatum[8]: derived 515009399bc0fd2bb204b0a50973a1285415159bed4213e315d739b7, "
                    + "chain bf8c4378bab7de15baddbb5d8805255d89174c08bf36179c21cad685",
            "ConfigDatum[14]: derived 21f4bde7524bbab159eb0293dac262f1193c6266385d983ee761e363, "
                    + "chain 1628910a5fbdba415c3b1bf7304672106659ac527442f10701472753",
            "ConfigDatum[24]: derived cdfa58c27aee3458983247dcde6419e6e8ca13b30e5ba34f95feccb0, "
                    + "chain db9a5bf043f37e744bbb43b96ec89a3e175f7c5523d02dd563ed9c56",
            "ConfigDatum[26]: derived 1322b6d1e7e46ac543769a8fcfb43840606f040d2e9efa2e59389d86, "
                    + "chain b4ad9a6f2710d68067177e0de5a4378ebe4fcdfdc929c7488479c313",
            "ConfigDatum[27]: derived c2023c909f66d50886e82ba86a03382313bbb8e994789e0aba588b3d, "
                    + "chain 45ce890c9bcf70f6eed629b5db7c0622e44ca1003e001a2cf951518f",
            "ConfigDatum[28]: derived f74b887491c86b1a1b7785c01f15cb7551f4520174589e22efb0df02, "
                    + "chain d815766d61c1241742ff78164cdf8edaef1746a99a242a7fb7938aa6",
            "LMConfigDatum[3]: derived 7e7563dd1753d0a933922a8da698154eead46f662ccb7c65f748f2c3, "
                    + "chain dd4709091734af2dc36321e774cf496222a1f92377ad6c5bef100457",
            "LMConfigDatum[6]: derived 190a6c685dd2cb61fc2e542b5c43382b649ce98d7a7c97d746d7aa12, "
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
            assertTrue(verifier(registry, "mainnet").verifyAgainst(
                    fixture("mainnet-config-datum.hex"), fixture("mainnet-lm-config-datum.hex")).isEmpty());
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
        String current = fixture("mainnet-config-datum.hex");
        // ConfigDatum[2], the pool policy: present in the CURRENT mainnet datum. The previous
        // target (12773eaf...) belonged to the pre-redeploy datum and no longer appears.
        String published = "20f765d25da3a36644371f7619d97bdccf034f3067921921d9dce0f7";
        String corrupted = "20f765d25da3a36644371f7619d97bdccf034f3067921921d9dce000";
        String mutant = current.replace(published, corrupted);
        assertNotEquals(current, mutant, "the captured mainnet mutation did not apply");

        List<String> mismatches = verifier(mainnet(), "mainnet")
                .verifyAgainst(mutant, fixture("mainnet-lm-config-datum.hex"));
        assertEquals(1, mismatches.size(), "one corrupted credential must add one mismatch: " + mismatches);
        assertTrue(mismatches.getFirst().contains("ConfigDatum[2]")
                        && mismatches.getFirst().contains(corrupted),
                "the mismatch must identify the corrupted published field: " + mismatches);
    }

    @Test
    void fixedArtifactMatchesPublishedCompoundBytesAndPreservesConvertHash() throws Exception {
        LoansContractRegistry registry = mainnet();
        assertEquals(fixture("mainnet-compound-script.hex"),
                HexUtil.encodeHexString(registry.getLmCompoundActionScript().serializeScriptBody()));
        assertEquals(CONVERT, registry.getLmLiquidateAndConvertActionScriptHash());
    }
}
