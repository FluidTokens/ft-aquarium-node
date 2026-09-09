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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What an operator gets if they change nothing.
 *
 * <h2>Why this test exists</h2>
 * Nothing else in the suite binds {@code application.yaml}. Every liquidation test builds its
 * configuration by hand, which is what makes those tests fast and readable — and also means that
 * flipping the preview {@code mode} to
 * {@code live} in the shipped file would leave the whole suite green while arming a bot on every
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
     * ⛔ <b>THE SEPARATE ARMING FLAG IS GONE, and this now pins its ABSENCE.</b>
     *
     * <p>{@code loans.liquidation.enabled} shipped {@code false} beside the mode until 2026-09-04,
     * when Giovanni removed it as redundant: <i>"mode == disabled already IS off, so a separate
     * enabled boolean makes no sense."</i>
     *
     * <p>⚠ The inverted assertion is worth as much as the original was. A key re-appearing in the
     * shipped file is how a removed gate comes back — and it would come back <b>bound to nothing</b>,
     * since the {@code @Value} that read it is gone. A node would then ship a flag an operator can
     * set, that reads as arming, and that changes nothing at all: <b>strictly worse than either
     * having it or not.</b> The mode is pinned {@code disabled} by the test above; this pins that
     * nothing sits beside it.
     */
    @Test
    void noDocumentShipsASecondArmingFlagBesideTheMode() throws IOException {
        for (Map<String, Object> document : documents()) {
            Object liquidation = liquidationBlockOf(document);
            if (liquidation instanceof Map<?, ?> map) {
                assertTrue(map.get("enabled") == null,
                        profileOf(document) + " ships loans.liquidation.enabled. That property was "
                                + "REMOVED from the app, so the key binds to nothing: an operator "
                                + "would set it, read no error, and arm nothing. The mode is the "
                                + "whole node-level dial");
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
    void theGatingValueIsEnvOverridableWithItsDefaultInline() throws IOException {
        List<Map<String, Object>> documents = documents();

        for (String value : List.of(
                (String) at(base(documents), "loans.liquidation.mode"),
                (String) at(preview(documents), "loans.liquidation.mode"))) {
            assertTrue(value.startsWith("${") && value.endsWith("}") && value.contains(":"),
                    "'" + value + "' is not a ${VAR:default} placeholder");
        }
    }

    /**
     * ⛔ <b>EVERY reference-script key the BASE document bakes must be blanked in the preview
     * document — and this is a generator guard, not a spot check.</b>
     *
     * <p>The base document now ships the <b>verified mainnet coordinates</b> as its defaults, so an
     * operator never types one. ⚠ <b>A preview node inherits every base key it does not override</b>,
     * and a mainnet coordinate reaching a preview node is not inert:
     * {@code LoansReferenceScriptVerifier} resolves it, derives a different hash, and <b>HARD-FAILS AT
     * BOOT</b>. Two keys — {@code asset-manager} and the convert action — were previously absent from
     * the preview document and harmless <em>only</em> because the base default was empty too. Baking
     * turned that omission into a crash-loop for every preview node, including any operator running
     * the public image with the preview profile.
     *
     * <p>⇒ So the assertion is over the base document's OWN key set rather than a list written here:
     * <b>add a key to the base and forget the preview blank, and this goes red</b> — which is the only
     * form of this check that survives the next key being added.
     */
    @Test
    void everyBakedReferenceScriptKeyIsBlankedInThePreviewDocument() throws IOException {
        List<Map<String, Object>> documents = documents();
        Object liq = at(base(documents), "loans.liquidation.reference-scripts");
        assertTrue(liq instanceof Map, "the base document must still carry the block");

        for (Map.Entry<?, ?> entry : ((Map<?, ?>) liq).entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object previewValue = at(preview(documents),
                    "loans.liquidation.reference-scripts." + key);
            assertNotNull(previewValue, "the preview document does not override "
                    + "loans.liquidation.reference-scripts." + key + ", so it INHERITS the baked "
                    + "mainnet coordinate and the node hard-fails at boot on the hash mismatch");
            assertTrue(String.valueOf(previewValue).endsWith(":}"),
                    "preview's " + key + " must be a placeholder with an EMPTY default; it is "
                            + previewValue);
        }

        Object compound = at(preview(documents), "loans.compound.reference-scripts");
        assertNotNull(compound, "preview does not override loans.compound.reference-scripts, so it "
                + "inherits the eleven baked mainnet coordinates");
        assertTrue(String.valueOf(compound).endsWith(":}"),
                "preview's compound coordinate list must default empty; it is " + compound);
    }

    /**
     * The six preview reference-script keys, each shipped <b>deliberately blank</b>, and the proof
     * that blank is a supported path rather than a parse error.
     *
     * <h2>Why they are blank, and why that is not staleness</h2>
     * The third preview deployment (2026-08-17, config NFTs in tx {@code 7374a985…e781}) moved the
     * config NFT policy ids. Every v4 script hash is derived by applying those policy ids as
     * parameters, so new policy ids move the hashes, moving hashes move the script <em>addresses</em>,
     * and the reference-script UTxOs published for the second deployment therefore hold scripts this
     * deployment never invokes. They are <b>dead, not stale</b>: there is no version of them that is
     * merely out of date and still usable.
     * <p>
     * Leaving the old values in place would be strictly worse than leaving them empty.
     * {@code LoansReferenceScriptVerifier} compares each coordinate's on-chain
     * {@code reference_script_hash} against the freshly derived hash and hard-fails at boot on a
     * mismatch — so the old coordinates would block startup outright, whereas an empty value is a
     * supported path that skips the check. Hence blank, on purpose.
     *
     * <h2>What being blank costs</h2>
     * <b>Submission is disabled until the coordinates are re-discovered.</b> Indexing, health, the
     * scanner and shadow mode all work; a real {@code Liquidate} cannot be submitted, because the six
     * applied validators total ~18.6 KB against a 16_384-byte {@code maxTxSize} and cannot travel in
     * the witness set. The new scripts <em>are</em> on chain (Blockfrost {@code /scripts/<hash>}
     * resolves them), so the coordinates exist and need discovering rather than creating — that is a
     * separate ticket, and this test goes back to pinning literal coordinates when it lands.
     *
     * <h2>What is asserted</h2>
     * This test guards a deliberate temporary state, so it asserts that state exactly: the same six
     * keys are still present, in order, with {@code asset-manager} still absent; every one is still an
     * env-overridable placeholder, so an operator can supply a coordinate without editing the jar; and
     * every shipped default is empty and resolves through the real parser to "nothing published"
     * rather than throwing. A coordinate reappearing here without this test being rewritten fails, and
     * so does a key going missing.
     */
    /**
     * The reference script we published on 2026-08-17 for the THIRD deployment, now <b>DEAD</b>.
     * <p>
     * It was pinned here because it had been verified — its on-chain {@code reference_script_hash}
     * was read back and compared to the derived {@code loanClaimActionScriptHash} before the literal
     * was written down. The fourth deployment (2026-08-25) re-minted the config NFTs, which moves the
     * derived hash, which moves the script address, so the UTxO now holds a script this deployment
     * never invokes. Exactly the fate its own comment predicted for "the next redeploy".
     * <p>
     * It is kept as a NEGATIVE pin: the test below asserts it does not reappear. That is a stronger
     * guard than simply requiring blanks, because the realistic mistake is not inventing a
     * coordinate — it is pasting this one back from a comment, a commit message, or an old .env.
     */
    private static final String DEAD_THIRD_DEPLOYMENT_LOAN_CLAIM_ACTION =
            "48c102c0034b04558c640df211045fdd7511dc7046b55942ca5909372eab24cd#0";

    @Test
    void thePreviewProfileShipsNineBlankedReferenceScriptKeys() throws IOException {
        Object block = at(preview(documents()), "loans.liquidation.reference-scripts");
        assertTrue(block instanceof Map, "reference-scripts is not a block");
        @SuppressWarnings("unchecked")
        Map<String, Object> scripts = (Map<String, Object>) block;

        assertEquals(List.of("loan", "loan-spend", "lender-manager", "lender-manager-spend",
                        "loan-claim-action", "lm-liquidate-action",
                        "lm-liquidate-and-pay-in-advance-action", "asset-manager",
                        "lm-liquidate-and-convert-action"),
                List.copyOf(scripts.keySet()),
                "⚠ SEVEN became NINE on 2026-09-05, and the REASON for the two additions is the "
                        + "opposite of the reason the old seven were here. asset-manager used to be "
                        + "deliberately ABSENT — 'a plain Liquidate never spends an asset-manager "
                        + "output, so its script is neither attached nor needed' — which was safe only "
                        + "while the BASE default was also empty. The base document now BAKES the "
                        + "verified mainnet coordinates, so an absent key here means preview INHERITS a "
                        + "mainnet one and LoansReferenceScriptVerifier hard-fails at boot on the hash "
                        + "mismatch. Absence stopped meaning 'not needed' and started meaning "
                        + "'inherited'");

        for (Map.Entry<String, Object> entry : scripts.entrySet()) {
            String value = (String) entry.getValue();
            String key = "loans.liquidation.reference-scripts." + entry.getKey();

            // Still ${VAR:default}, so an operator can supply or override a coordinate by env.
            assertTrue(value.startsWith("${") && value.endsWith("}"), key + " is '" + value + "'");
            String shippedDefault = value.substring(value.indexOf(':') + 1, value.length() - 1);

            assertEquals("", shippedDefault,
                    key + " ships a coordinate. All six are blank for the FOURTH deployment "
                            + "(2026-08-25): re-minted config NFTs move every derived script hash, so "
                            + "any previously published UTxO holds a script this deployment never "
                            + "invokes. If one has genuinely been republished, verify its on-chain "
                            + "reference_script_hash against the freshly derived hash and pin it here "
                            + "— and rewrite this test rather than loosening it.");

            // The empty case is the supported skip path: null, not an exception.
            assertNull(AppConfig.LiquidationConfiguration.referenceInput(key, shippedDefault),
                    key + " must resolve to 'nothing published' rather than a coordinate");

            // Negative pin: the realistic mistake is pasting the dead coordinate back, not inventing one.
            assertNotEquals(DEAD_THIRD_DEPLOYMENT_LOAN_CLAIM_ACTION, shippedDefault,
                    key + " ships the THIRD deployment's dead loan-claim-action coordinate. That UTxO "
                            + "holds a script this deployment never invokes, and pinning it would "
                            + "hard-fail LoansReferenceScriptVerifier at boot rather than degrade.");
        }

        // And blank is the *only* thing that skips silently: a malformed coordinate is still rejected
        // loudly. Without this, "empty means not published" could be satisfied by a parser that
        // shrugged at everything, and the skip path would prove nothing.
        String loan = "loans.liquidation.reference-scripts.loan";
        assertNull(AppConfig.LiquidationConfiguration.referenceInput(loan, "   "),
                "whitespace is also 'not published'");
        assertNull(AppConfig.LiquidationConfiguration.referenceInput(loan, null),
                "an unset value is also 'not published'");
        // A bare hash with no #index — the YAML comment hazard that eats the index — must not pass.
        assertThrows(IllegalStateException.class, () -> AppConfig.LiquidationConfiguration
                        .referenceInput(loan,
                                "00a4e9f69c6ce80b8cb4fe7008a40a2f007aa53b25ec52ae30f11e701f7aa693"),
                "a coordinate with no output index must be rejected, not treated as unpublished");
    }

    /**
     * ⛔ EVERY operator-facing default in the base document, pinned BY VALUE.
     *
     * <h2>Why this exists, and it is not hypothetical</h2>
     * On 2026-08-27 {@code profit-margin-lovelace} was changed from {@code 1500000} to
     * {@code 5000000} — a <b>3.3× move in the number that decides whether the bot will move
     * someone's collateral</b> — and the suite reported <b>79 files / 712 tests / 38 failures /
     * 20 skipped / 0 errors, identical before and after</b>. Nothing owned that value. The two
     * gating flags above were pinned because someone reasoned about arming; the economics were not,
     * and they are the same class of promise.
     * <p>
     * <b>A default is a promise to an operator you will never meet.</b> They will run the image
     * without reading this file, and every number here is a decision made on their behalf.
     *
     * <h2>Pinned by literal, deliberately</h2>
     * The whole {@code ${VAR:default}} string is pinned, not just the default, so that renaming the
     * environment variable — which silently breaks every operator's existing deployment while
     * leaving the shipped behaviour identical — also fails here.
     */
    private static final Map<String, String> SHIPPED_LIQUIDATION_DEFAULTS = Map.ofEntries(
            Map.entry("mode", "${AQUARIUM_LIQUIDATION_MODE:disabled}"),
            Map.entry("delay-seconds", "${AQUARIUM_LIQUIDATION_DELAY_SECONDS:60}"),
            Map.entry("validity-window-seconds", "${AQUARIUM_LIQUIDATION_VALIDITY_WINDOW_SECONDS:120}"),
            Map.entry("oracle-window-margin-seconds", "${AQUARIUM_LIQUIDATION_ORACLE_MARGIN_SECONDS:30}"),
            Map.entry("profit-margin-lovelace", "${AQUARIUM_LIQUIDATION_PROFIT_MARGIN_LOVELACE:5000000}"),
            Map.entry("ignore-profit-check", "${AQUARIUM_LIQUIDATION_IGNORE_PROFIT_CHECK:false}"),
            Map.entry("min-profit-absolute-lovelace", "${AQUARIUM_LIQUIDATION_MIN_PROFIT_ABSOLUTE_LOVELACE:0}"),
            // Must ship at 0. A negative default would make every node loss-tolerant on upgrade,
            // which is the opposite of "not the default setting but it must be possible".
            Map.entry("min-expected-profit-lovelace", "${AQUARIUM_LIQUIDATION_MIN_EXPECTED_PROFIT_LOVELACE:0}"),
            Map.entry("check-profitability", "${AQUARIUM_LIQUIDATION_CHECK_PROFITABILITY:true}"),
            Map.entry("decision-log-size", "${AQUARIUM_LIQUIDATION_DECISION_LOG_SIZE:200}"),
            Map.entry("quarantine-minutes", "${AQUARIUM_LIQUIDATION_QUARANTINE_MINUTES:30}"));

    /**
     * ⚠ {@code mode} and {@code enabled} are pinned again here on purpose. They have their own tests
     * above, for their own reasons, but the completeness check below is only exhaustive if this table
     * is the whole set — and an exemption list is exactly how a set stops being exhaustive.
     */
    @Test
    void everyShippedLiquidationDefaultIsPinnedByValue() throws IOException {
        Map<String, Object> base = base(documents());
        for (Map.Entry<String, String> pinned : SHIPPED_LIQUIDATION_DEFAULTS.entrySet()) {
            assertEquals(pinned.getValue(), at(base, "loans.liquidation." + pinned.getKey()),
                    "the shipped default for loans.liquidation." + pinned.getKey() + " moved. If that "
                            + "was deliberate, change it here and say in the commit what an operator "
                            + "running the image will now do differently.");
        }
    }

    /**
     * ⛔ THE HALF THAT MAKES THE TABLE ABOVE MEAN SOMETHING.
     *
     * <p>A table pins what its author remembered. This asserts the table is the <b>whole set</b>: every
     * scalar under {@code loans.liquidation} must appear in it, so <b>adding a new operator knob
     * without pinning it fails here</b> rather than shipping unasserted. Nested blocks
     * ({@code reference-scripts}) are excluded — they have their own test above.
     *
     * <p>⚑ Set equality in <b>both</b> directions, not {@code containsAll}: a key removed from the
     * yaml but left in the table would otherwise pass while pinning something that no longer ships.
     */
    @Test
    void noLiquidationDefaultShipsWithoutAPin() throws IOException {
        Object block = liquidationBlockOf(base(documents()));
        assertTrue(block instanceof Map, "loans.liquidation is not a block in the base document");
        @SuppressWarnings("unchecked")
        Map<String, Object> liquidation = (Map<String, Object>) block;

        java.util.Set<String> shipped = new java.util.TreeSet<>();
        for (Map.Entry<String, Object> entry : liquidation.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                shipped.add(entry.getKey());
            }
        }
        assertEquals(new java.util.TreeSet<>(SHIPPED_LIQUIDATION_DEFAULTS.keySet()), shipped,
                "the set of operator-facing liquidation defaults changed. A new knob must be pinned in "
                        + "SHIPPED_LIQUIDATION_DEFAULTS, and a removed one deleted from it — otherwise "
                        + "the pins above stop being exhaustive and start being a sample.");
    }

    private static Object liquidationBlockOf(Map<String, Object> document) {
        Object loans = document.get("loans");
        if (!(loans instanceof Map<?, ?> loansMap)) {
            return null;
        }
        return loansMap.get("liquidation");
    }
}
