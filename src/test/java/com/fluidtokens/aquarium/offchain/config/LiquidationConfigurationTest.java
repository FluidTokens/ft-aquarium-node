package com.fluidtokens.aquarium.offchain.config;

import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code loans.liquidation.mode} binding: the one configuration value in this slice whose wrong
 * answer is silent.
 * <p>
 * Every other knob here is a number — a bad one produces a visibly bad decision. The mode decides
 * whether the loop runs at all, so a typo that quietly resolved to a default would leave an operator
 * reading an empty endpoint and concluding there was nothing to liquidate. Hence the fail-fast, and
 * hence these tests: they drive the real {@code @PostConstruct} method on a real instance, not a
 * parsing helper written for the occasion.
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
}
