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
            "63f5fcf395c5a3e76c211e71e8a327aeb1009205e0773b2bdb732ab8020904a5";
    private static final String CONFIG = "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416";
    private static final String LM_CONFIG = "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4";
    private static final String ASSET = "706172616d6574657273";
    private static final String SMART = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";
    private static final String MS_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String MS_POOL = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MS_ORDER = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";
    private static final String CONVERT = "c3f51e55dd156a4c29a41df0d630b0d8f1c96f396f5a317788a94b70";
    private static final List<String> OLD_PREVIEW_MISMATCHES = List.of(
            "ConfigDatum[24]: derived cdfa58c27aee3458983247dcde6419e6e8ca13b30e5ba34f95feccb0, "
                    + "chain db9a5bf043f37e744bbb43b96ec89a3e175f7c5523d02dd563ed9c56",
            "LMConfigDatum[3]: derived 7dbcad0e76f5c639c96dd7ffc52f730d1ac0290c46c04fe343edc49b, "
                    + "chain dd4709091734af2dc36321e774cf496222a1f92377ad6c5bef100457");

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
        assertEquals(List.of(1, 4, 7), Arrays.stream(LoansContractRegistry.class.getConstructors())
                .map(constructor -> constructor.getParameterCount()).sorted().toList(),
                "the fixed configuration, four-parameter and seven-parameter constructors are the whole API");
    }

    @Test
    void currentMainnetDatumMutationAddsOneSpecificMismatch() throws IOException {
        String current = fixture("mainnet-config-datum.hex");
        String published = "12773eaf55b80546be4e6afb87cc6e1073049be5f8271ac0c117a006";
        String corrupted = "12773eaf55b80546be4e6afb87cc6e1073049be5f8271ac0c117a000";
        String mutant = current.replace(published, corrupted);
        assertNotEquals(current, mutant, "the captured mainnet mutation did not apply");

        List<String> mismatches = verifier(mainnet(), "mainnet")
                .verifyAgainst(mutant, fixture("mainnet-lm-config-datum.hex"));
        assertEquals(1, mismatches.size(), "one corrupted credential must add one mismatch: " + mismatches);
        assertTrue(mismatches.getFirst().contains("ConfigDatum[24]")
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
