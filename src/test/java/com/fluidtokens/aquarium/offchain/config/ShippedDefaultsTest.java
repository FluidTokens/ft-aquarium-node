package com.fluidtokens.aquarium.offchain.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an operator gets if they change nothing.
 *
 * <h2>Why this test exists</h2>
 * Nothing else in the suite binds {@code application.yaml}. Every liquidation test builds its
 * configuration by hand, which is what makes those tests fast and readable — and also means that
 * flipping {@code loans.liquidation.enabled} to {@code true}, or the preview {@code mode} to
 * {@code live}, in the shipped file would leave the whole suite green while arming a bot on every
 * node that pulls the image. Those are exactly the two values the submit path gates on.
 * <p>
 * So this test reads the shipped resource — the real file, parsed as YAML rather than grepped, so a
 * value that moved to a different nesting cannot pass by coincidence — and pins the defaults. It
 * fails if either is flipped, which is the point: arming is a deliberate act by an operator at
 * deployment time, never a commit.
 *
 * <h2>What it does not do</h2>
 * It does not start a Spring context. The claim is about the bytes shipped in the jar, and a context
 * would additionally need a database, a relay and a Blockfrost key to say the same thing.
 */
class ShippedDefaultsTest {

    private static final String RESOURCE = "application.yaml";

    /** The base document and the {@code preview}-profile document, in file order. */
    private static List<Map<String, Object>> documents() throws IOException {
        String yaml;
        try (InputStream in = ShippedDefaultsTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, RESOURCE + " is not on the classpath — this test would be vacuous");
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Proof that the classpath copy is the file in src/main/resources, and not a stale or
        // rewritten one: otherwise this test could pin a document nobody ships.
        String onDisk = Files.readString(Path.of("src/main/resources", RESOURCE));
        assertEquals(onDisk, yaml,
                "the classpath " + RESOURCE + " differs from src/main/resources/" + RESOURCE);

        List<Map<String, Object>> documents = new ArrayList<>();
        for (Object document : new Yaml().loadAll(yaml)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) document;
            documents.add(map);
        }
        return documents;
    }

    /** Walks a dotted path, e.g. {@code loans.liquidation.mode}. */
    private static Object at(Map<String, Object> document, String path) {
        Object current = document;
        for (String segment : path.split("\\.")) {
            assertTrue(current instanceof Map, path + " does not exist: stopped at '" + segment + "'");
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) current;
            current = map.get(segment);
            assertNotNull(current, path + " is not set (missing at '" + segment + "')");
        }
        return current;
    }

    private static Map<String, Object> base(List<Map<String, Object>> documents) {
        return documents.getFirst();
    }

    private static Map<String, Object> preview(List<Map<String, Object>> documents) {
        for (Map<String, Object> document : documents) {
            if ("preview".equals(profileOf(document))) {
                return document;
            }
        }
        throw new AssertionError("no document activates the preview profile");
    }

    private static Object profileOf(Map<String, Object> document) {
        Object spring = document.get("spring");
        if (!(spring instanceof Map<?, ?> springMap)) {
            return null;
        }
        Object config = springMap.get("config");
        if (!(config instanceof Map<?, ?> configMap)) {
            return null;
        }
        Object activate = configMap.get("activate");
        if (!(activate instanceof Map<?, ?> activateMap)) {
            return null;
        }
        return activateMap.get("on-profile");
    }

    // ======================================================================================

    /**
     * The base document ships the bot switched off entirely. This is what a mainnet operator runs,
     * where {@code loans.enabled} is false as well — but the mode is the value that would decide if
     * lending were ever switched on there.
     */
    @Test
    void theBaseDocumentShipsTheModeDisabled() throws IOException {
        assertEquals("${AQUARIUM_LIQUIDATION_MODE:disabled}", at(base(documents()), "loans.liquidation.mode"),
                "the shipped default mode is not 'disabled' — arming must never be a commit");
    }

    /**
     * The arming flag, shipped off. There is no per-profile override of it anywhere in the file: the
     * preview document deliberately does not set it, so shadow is as far as a stock node can get.
     */
    @Test
    void theBaseDocumentShipsTheArmingFlagFalse() throws IOException {
        List<Map<String, Object>> documents = documents();

        assertEquals("${AQUARIUM_LIQUIDATION_ENABLED:false}",
                at(base(documents), "loans.liquidation.enabled"),
                "the shipped arming flag is not 'false' — arming must never be a commit");

        for (Map<String, Object> document : documents) {
            if (document == base(documents)) {
                continue;
            }
            Object liquidation = liquidationBlockOf(document);
            if (liquidation instanceof Map<?, ?> map) {
                assertTrue(map.get("enabled") == null,
                        "the " + profileOf(document) + " profile overrides loans.liquidation.enabled; "
                                + "the arming flag must be set by an operator, not by a profile");
            }
        }
    }

    /**
     * Preview is where the bot runs, and it runs in shadow: it builds, prices and records, and the
     * mode veto stops every candidate before the wire. {@code live} here would arm every preview node
     * that also set the enabled flag.
     */
    @Test
    void thePreviewProfileShipsTheModeShadow() throws IOException {
        assertEquals("${AQUARIUM_LIQUIDATION_MODE:shadow}",
                at(preview(documents()), "loans.liquidation.mode"),
                "the shipped preview mode is not 'shadow'");
    }

    /**
     * The env-var defaults are what the assertions above actually pin, so the form has to be the one
     * Spring resolves: {@code ${VAR:default}}. A value that stopped being a placeholder would make
     * those assertions pin a literal that no longer has a default behind it.
     */
    @Test
    void bothGatingValuesAreEnvOverridableWithTheirDefaultInline() throws IOException {
        List<Map<String, Object>> documents = documents();

        for (String value : List.of(
                (String) at(base(documents), "loans.liquidation.mode"),
                (String) at(base(documents), "loans.liquidation.enabled"),
                (String) at(preview(documents), "loans.liquidation.mode"))) {
            assertTrue(value.startsWith("${") && value.endsWith("}") && value.contains(":"),
                    "'" + value + "' is not a ${VAR:default} placeholder");
        }
    }

    /**
     * The exact preview reference-script coordinates, pinned literally.
     * <p>
     * Parseability is not enough. A coordinate can be perfectly well-formed and point at the wrong
     * output — which is what a FluidTokens redeploy produces, and what a fat-fingered edit produces
     * — and the only other thing that would catch it is
     * {@code LoansReferenceScriptVerifier}, which needs a live chain. So the shipped values are
     * pinned here the same way the config NFT policy ids are pinned elsewhere: as deployment
     * coordinates that cannot change without someone deliberately changing this test too.
     * <p>
     * Each of these was verified on chain: the UTxO's {@code reference_script_hash} equals the hash
     * this repo derives for that validator.
     */
    private static final Map<String, String> PREVIEW_REFERENCE_SCRIPTS = Map.of(
            "loan", "00a4e9f69c6ce80b8cb4fe7008a40a2f007aa53b25ec52ae30f11e701f7aa693#0",
            "loan-spend", "5c10900c23d16538bc518fa982f0d59a15908f0bb821860ddbef086346b669da#0",
            "lender-manager", "fe791b232b8ffcd31c72001a0a6345bc36101eac4d87133b0cf1a101024ffc07#0",
            "lender-manager-spend", "13dd33290f62fe42dbbe7afc1d28505c025955bc55bd9b0a0ddff438663c2571#0",
            "loan-claim-action", "b09e23dc5639642a4cbf112d39753c96ed0528115a8468b688b0e8cb19f243fe#0",
            "lm-liquidate-action", "549b438c3a579a31cc4b7595f43c3af75bd02b237026583b834fc64349a47fe0#0");

    /**
     * The six preview reference-script coordinates, taken from the shipped file, checked against the
     * pinned values above and run through the real parser.
     * <p>
     * A YAML hazard worth pinning: these values contain a {@code #}, and a {@code #} that follows
     * whitespace starts a comment. If one of them were ever reformatted or quoted differently, the
     * index would be silently eaten and the coordinate would arrive as a bare 64-character hash —
     * which {@link AppConfig.LiquidationConfiguration#referenceInput} rejects, so this test would
     * fail rather than the node starting with six half-read coordinates.
     */
    @Test
    void thePreviewProfileShipsSixParseableReferenceScriptCoordinates() throws IOException {
        Object block = at(preview(documents()), "loans.liquidation.reference-scripts");
        assertTrue(block instanceof Map, "reference-scripts is not a block");
        @SuppressWarnings("unchecked")
        Map<String, Object> scripts = (Map<String, Object>) block;

        assertEquals(List.of("loan", "loan-spend", "lender-manager", "lender-manager-spend",
                        "loan-claim-action", "lm-liquidate-action"),
                List.copyOf(scripts.keySet()),
                "asset-manager is deliberately absent on preview: a plain Liquidate never spends an "
                        + "asset-manager output, so its script is neither attached nor needed");

        for (Map.Entry<String, Object> entry : scripts.entrySet()) {
            String value = (String) entry.getValue();
            String key = "loans.liquidation.reference-scripts." + entry.getKey();
            // The shipped form is ${VAR:txHash#index}; the default is what a stock node resolves to.
            assertTrue(value.startsWith("${") && value.endsWith("}"), key + " is '" + value + "'");
            String shippedDefault = value.substring(value.indexOf(':') + 1, value.length() - 1);

            assertEquals(PREVIEW_REFERENCE_SCRIPTS.get(entry.getKey()), shippedDefault,
                    key + " no longer ships the coordinate that was verified on chain. If FluidTokens "
                            + "redeployed, update both this test and application.yaml — and re-verify "
                            + "the new utxo's reference_script_hash against the derived one.");

            var input = AppConfig.LiquidationConfiguration.referenceInput(key, shippedDefault);
            assertNotNull(input, key + " resolves to nothing published");
            assertEquals(64, input.getTransactionId().length(), key);
            assertEquals(0, input.getIndex(), key + " — every published script sits at output 0");
        }
    }

    private static Object liquidationBlockOf(Map<String, Object> document) {
        Object loans = document.get("loans");
        if (!(loans instanceof Map<?, ?> loansMap)) {
            return null;
        }
        return loansMap.get("liquidation");
    }
}
