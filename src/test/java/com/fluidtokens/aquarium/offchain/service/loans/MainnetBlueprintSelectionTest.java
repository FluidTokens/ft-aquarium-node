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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves the shipped profile selects the artifact whose bytes derive that profile's live datums. */
class MainnetBlueprintSelectionTest {

    private static final String LEGACY = "loans-v4.plutus.json";
    private static final String MAINNET = "loans-v4-mainnet.plutus.json";
    private static final String CONFIG = "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416";
    private static final String LM_CONFIG = "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4";
    private static final String ASSET = "706172616d6574657273";
    private static final String SMART = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";
    private static final String MS_POLICY = "f5808c2c990d86da54bfc97d89cee6efa20cd8461616359478d96b4c";
    private static final String MS_POOL = "ea07b733d932129c378af627436e7cbc2ef0bf96e0036bb51b3bde6b";
    private static final String MS_ORDER = "c3e28c36c3447315ba5a56f33da6a6ddc1770a876a8d9f0cb3a97c4c";
    private static final String CONVERT = "c3f51e55dd156a4c29a41df0d630b0d8f1c96f396f5a317788a94b70";

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

    private static LoansConfigVerifier verifier(LoansContractRegistry registry, String network) {
        var configuredNetwork = new AppConfig.Network();
        configuredNetwork.setNetworkForTest(network);
        return new LoansConfigVerifier(registry, SMART, configuredNetwork, null, true);
    }

    private static LoansContractRegistry mainnet(String artifact) {
        return new LoansContractRegistry(artifact, CONFIG, LM_CONFIG, ASSET, SMART,
                MS_POLICY, MS_POOL, MS_ORDER);
    }

    @Test
    void shippedMainnetBindsItsArtifactAndVerifiesCurrentDatums() throws IOException {
        profile(0).run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null, () -> "mainnet context failed: " + ctx.getStartupFailure());
            var cfg = ctx.getBean(AppConfig.LoansConfiguration.class);
            var registry = ctx.getBean(LoansContractRegistry.class);
            assertEquals(MAINNET, cfg.getBlueprintResource());
            assertEquals(MAINNET, registry.getBlueprintResource());
            assertTrue(verifier(registry, "mainnet").verifyAgainst(
                    fixture("mainnet-config-datum.hex"), fixture("mainnet-lm-config-datum.hex")).isEmpty());
        });
    }

    @Test
    void shippedPreviewBindsLegacyArtifactAndStillVerifiesPreviewDatums() throws IOException {
        profile(1).run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null, () -> "preview context failed: " + ctx.getStartupFailure());
            var registry = ctx.getBean(LoansContractRegistry.class);
            assertEquals(LEGACY, ctx.getBean(AppConfig.LoansConfiguration.class).getBlueprintResource());
            assertEquals(LEGACY, registry.getBlueprintResource());
            assertTrue(verifier(registry, "preview").verifyAgainst(
                    fixture("fourth-deployment-config-datum.hex"),
                    fixture("fourth-deployment-lm-config-datum.hex")).isEmpty());
        });
    }

    @Test
    void oldArtifactFailsCurrentMainnetAtExactlyTheTwoChangedFields() throws IOException {
        assertEquals(List.of(
                        "ConfigDatum[24]: derived e3fbf7d5ab00c63bd8107782d3804ff93a9d327ef0896c744b43f672, chain 12773eaf55b80546be4e6afb87cc6e1073049be5f8271ac0c117a006",
                        "LMConfigDatum[3]: derived 1551bd4efdef76f3184798331e1c74f6a1cef51955b0c96b8db18d1f, chain ad34c3db53d20c1e368d7fea64724a0b0249b603a57c8f2a1670bda6"),
                verifier(mainnet(LEGACY), "mainnet").verifyAgainst(
                        fixture("mainnet-config-datum.hex"), fixture("mainnet-lm-config-datum.hex")));
    }

    @Test
    void mainnetArtifactCannotSilentlyReplacePreviewArtifact() throws IOException {
        var previewWithMainnetArtifact = new LoansContractRegistry(MAINNET,
                LoanFixtures.CONFIG_POLICY_ID, LoanFixtures.LM_CONFIG_POLICY_ID,
                LoanFixtures.CONFIG_ASSET_NAME, LoanFixtures.SMART_TOKENS_SPEND,
                null, null, null);
        assertFalse(verifier(previewWithMainnetArtifact, "preview").verifyAgainst(
                fixture("fourth-deployment-config-datum.hex"),
                fixture("fourth-deployment-lm-config-datum.hex")).isEmpty(),
                "forcing the new artifact into preview must be detected");
    }

    @Test
    void invalidArtifactSelectionFailsClearlyWithoutFallback() {
        for (String invalid : List.of("", "missing.plutus.json")) {
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> mainnet(invalid));
            assertTrue(failure.getMessage().contains("loans.blueprint-resource"), failure::getMessage);
        }
    }

    @Test
    void selectedArtifactMatchesPublishedCompoundBytesAndPreservesConvertHash() throws Exception {
        LoansContractRegistry registry = mainnet(MAINNET);
        assertEquals(fixture("mainnet-compound-script.hex"),
                HexUtil.encodeHexString(registry.getLmCompoundActionScript().serializeScriptBody()));
        assertEquals(CONVERT, registry.getLmLiquidateAndConvertActionScriptHash());
    }
}
