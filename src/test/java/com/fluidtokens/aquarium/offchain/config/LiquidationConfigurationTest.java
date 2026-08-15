package com.fluidtokens.aquarium.offchain.config;

import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two configuration values whose wrong answer is silent: {@code loans.liquidation.mode} and the
 * seven {@code loans.liquidation.reference-scripts.*} coordinates.
 * <p>
 * Every other knob here is a number — a bad one produces a visibly bad decision. The mode decides
 * whether the loop runs at all, so a typo that quietly resolved to a default would leave an operator
 * reading an empty endpoint and concluding there was nothing to liquidate. A malformed reference-script
 * coordinate that quietly became "not published" would move a 3 kB validator back into the witness
 * set and blow the transaction past maxTxSize, with no symptom but a repeated size veto. Hence the
 * fail-fast in both cases, and hence these tests: they drive the real {@code @PostConstruct} methods
 * on real instances, not parsing helpers written for the occasion.
 */
class LiquidationConfigurationTest {

    private static LiquidationConfiguration withModeName(String modeName) {
        LiquidationConfiguration configuration = new LiquidationConfiguration();
        configuration.setModeName(modeName);
        return configuration;
    }

    private static IllegalStateException rejects(String modeName) {
        return assertThrows(IllegalStateException.class, () -> withModeName(modeName).parseMode(),
                "'" + modeName + "' must not be accepted as a mode");
    }

    // ---- what is accepted ---------------------------------------------------------------------

    @Test
    void eachLegalModeBindsToItsOwnValue() {
        for (Mode mode : Mode.values()) {
            LiquidationConfiguration configuration = withModeName(mode.name());
            configuration.parseMode();
            assertEquals(mode, configuration.getMode());
        }
    }

    /**
     * Operators write these in an env var, where {@code shadow} is the natural spelling and
     * {@code SHADOW} is the enum's. Both have to work, or the yaml default and the documented
     * override disagree.
     */
    @Test
    void theModeIsCaseInsensitiveAndTrimmed() {
        for (String spelling : new String[]{"shadow", "SHADOW", "ShAdOw", "  shadow  ", "\tshadow\n"}) {
            LiquidationConfiguration configuration = withModeName(spelling);
            configuration.parseMode();
            assertEquals(Mode.SHADOW, configuration.getMode(), "rejected the spelling '" + spelling + "'");
        }
    }

    /** The yaml default, spelled exactly as {@code application.yaml} spells it. */
    @Test
    void theShippedDefaultSpellingBinds() {
        LiquidationConfiguration configuration = withModeName("disabled");
        configuration.parseMode();

        assertEquals(Mode.DISABLED, configuration.getMode());
        assertFalse(configuration.isArmed());
    }

    // ---- what is refused ----------------------------------------------------------------------

    /**
     * The failure this whole mechanism exists for. Note what is <em>not</em> asserted: that the mode
     * ends up DISABLED, or SHADOW, or anything at all. There is no acceptable resolution of a typo.
     */
    @Test
    void anUnrecognisedModeAbortsStartupAndNamesEveryLegalValue() {
        IllegalStateException thrown = rejects("shaddow");

        assertTrue(thrown.getMessage().contains("shaddow"), thrown.getMessage());
        for (Mode mode : Mode.values()) {
            assertTrue(thrown.getMessage().contains(mode.name()),
                    "the message must name " + mode + ": " + thrown.getMessage());
        }
    }

    /**
     * Empty and null are the two shapes a "missing" mode actually takes — an env var set to the empty
     * string, and a property that never bound. Neither may fall back to a default: silently disabling
     * a bot an operator meant to run, or silently running one they meant to disable, are both worse
     * than refusing to start.
     */
    @Test
    void emptyBlankAndNullAreRefusedRatherThanDefaulted() {
        rejects("");
        rejects("   ");
        rejects(null);

        // The instance is left with no mode at all, so nothing downstream can read a fabricated one.
        LiquidationConfiguration unparsed = withModeName(null);
        assertThrows(IllegalStateException.class, unparsed::parseMode);
        assertEquals(null, unparsed.getMode());
    }

    /** A value that merely contains a legal one is still a typo, not a mode. */
    @Test
    void aModeIsMatchedWholeNotBySubstring() {
        rejects("shadow-mode");
        rejects("not-live");
        rejects("live!");
    }

    // ---- the arming conjunct ------------------------------------------------------------------

    /**
     * {@code isArmed()} is what the endpoint reports and what slice 3 will gate on, so its truth
     * table is pinned here rather than only through the controller.
     */
    @Test
    void armingRequiresBothLiveModeAndTheEnabledFlag() {
        for (Mode mode : Mode.values()) {
            for (boolean enabled : new boolean[]{false, true}) {
                LiquidationConfiguration configuration = new LiquidationConfiguration(mode, enabled,
                        60, 120, 30, BigInteger.valueOf(1_500_000), 200, 30);
                assertEquals(mode == Mode.LIVE && enabled, configuration.isArmed(),
                        "mode=" + mode + " enabled=" + enabled);
            }
        }
    }

    /** Three values and no more: a fourth would be a mode nothing in the loop knows how to run. */
    @Test
    void thereAreExactlyThreeModes() {
        assertEquals("[DISABLED, SHADOW, LIVE]", Arrays.toString(Mode.values()));
    }

    // ======================================================================================
    // the reference-script coordinates
    // ======================================================================================
    //
    // Same fail-fast reasoning as the mode, for the same reason: a malformed coordinate that
    // silently became "not published" would move that validator back into the witness set, and the
    // only symptom would be every candidate refusing on a size the operator believed they had
    // already fixed.

    private static final String TX = "b09e23dc5639642a4cbf112d39753c96ed0528115a8468b688b0e8cb19f243fe";

    private static LiquidationConfiguration withReferenceScripts(String... seven) {
        LiquidationConfiguration configuration = new LiquidationConfiguration();
        configuration.setReferenceScriptCoordinates(seven[0], seven[1], seven[2], seven[3], seven[4],
                seven[5], seven[6]);
        return configuration;
    }

    /** One coordinate, driven through the real {@code @PostConstruct}. */
    private static IllegalStateException rejectsCoordinate(String coordinate) {
        LiquidationConfiguration configuration =
                withReferenceScripts(coordinate, "", "", "", "", "", "");
        return assertThrows(IllegalStateException.class, configuration::parseReferenceScripts,
                "'" + coordinate + "' must not be accepted as a reference-script coordinate");
    }

    @Test
    void everyValidCoordinateBindsToItsOwnTransactionInput() {
        LiquidationConfiguration configuration = withReferenceScripts(
                TX + "#0", TX + "#1", TX + "#2", TX + "#3", TX + "#4", TX + "#5", TX + "#6");
        configuration.parseReferenceScripts();

        var scripts = configuration.getReferenceScripts();
        assertEquals(TX, scripts.loan().getTransactionId());
        assertEquals(0, scripts.loan().getIndex());
        assertEquals(1, scripts.loanSpend().getIndex());
        assertEquals(2, scripts.lenderManager().getIndex());
        assertEquals(3, scripts.lenderManagerSpend().getIndex());
        assertEquals(4, scripts.loanClaimAction().getIndex());
        assertEquals(5, scripts.lmLiquidateAction().getIndex());
        assertEquals(6, scripts.assetManager().getIndex());
    }

    /**
     * Empty is the shipped default and means "not published": that validator travels in the witness
     * set. It has to be a legal configuration, or a node with no published scripts could not start
     * at all — which is precisely the state {@code main} is in.
     */
    @Test
    void emptyAndBlankAndNullCoordinatesMeanNotPublished() {
        LiquidationConfiguration configuration =
                withReferenceScripts("", "   ", null, "", "", "", "");
        configuration.parseReferenceScripts();

        var scripts = configuration.getReferenceScripts();
        assertNull(scripts.loan());
        assertNull(scripts.loanSpend());
        assertNull(scripts.lenderManager());
        assertNull(scripts.lenderManagerSpend());
        assertNull(scripts.loanClaimAction());
        assertNull(scripts.lmLiquidateAction());
        assertNull(scripts.assetManager());
    }

    /** Whitespace around a real coordinate is an env-file artefact, not a typo. */
    @Test
    void aCoordinateIsTrimmed() {
        LiquidationConfiguration configuration =
                withReferenceScripts("  " + TX + "#3\n", "", "", "", "", "", "");
        configuration.parseReferenceScripts();

        assertEquals(TX, configuration.getReferenceScripts().loan().getTransactionId());
        assertEquals(3, configuration.getReferenceScripts().loan().getIndex());
    }

    @Test
    void everyMalformedShapeIsRefused() {
        rejectsCoordinate(TX);                       // no index at all
        rejectsCoordinate(TX + "#");                 // an empty index
        rejectsCoordinate(TX + "#0#1");              // two separators
        rejectsCoordinate(TX + "#-1");               // a negative index
        rejectsCoordinate(TX + "#one");              // a non-numeric index
        rejectsCoordinate(TX.substring(0, 62) + "#0");   // a short hash
        rejectsCoordinate(TX + "ab#0");              // a long hash
        rejectsCoordinate("z".repeat(64) + "#0");    // not hex
        rejectsCoordinate("#0");                     // no hash
    }

    /**
     * The message has to name the <em>key</em>. There are seven near-identical lines in
     * {@code application.yaml}; the offending value alone does not tell an operator which one to fix.
     */
    @Test
    void aMalformedCoordinateNamesTheKeyAndTheValue() {
        LiquidationConfiguration configuration =
                withReferenceScripts("", "", "", "", "", "not-a-coordinate", "");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                configuration::parseReferenceScripts);

        assertTrue(thrown.getMessage().contains("loans.liquidation.reference-scripts.lm-liquidate-action"),
                thrown.getMessage());
        assertTrue(thrown.getMessage().contains("not-a-coordinate"), thrown.getMessage());
    }

    /**
     * A hand-built configuration — the shape every liquidation test uses — must not come back with a
     * null {@code ReferenceScripts}: the executor dereferences it on every candidate.
     */
    @Test
    void aHandBuiltConfigurationPublishesNothingRatherThanNull() {
        LiquidationConfiguration configuration = new LiquidationConfiguration(Mode.SHADOW, false,
                60, 120, 30, BigInteger.valueOf(1_500_000), 200, 30);

        assertNotNull(configuration.getReferenceScripts());
        assertNull(configuration.getReferenceScripts().loan());
    }
}
