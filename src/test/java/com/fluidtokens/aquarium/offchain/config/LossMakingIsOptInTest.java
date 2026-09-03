package com.fluidtokens.aquarium.offchain.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>Operating at a loss must be reachable only by STATING it — never by copying a config.</b>
 *
 * <h2>Why this test now carries the whole protection</h2>
 * Until 2026-09-03 a negative profit margin was a <b>hard startup failure on mainnet</b> on all three
 * paths. Giovanni removed that, first-hand: <i>"it's fundamental to allow operators to operate at a
 * loss. Protocol must be kept bad-loss-free at all costs. So you can expect our bot to be used by
 * FluidTeam to clean up loans non-profitable for other operators but still need cleanup … operating at
 * a loss MUST be implemented even on mainnet."</i> The bot is a protocol-health tool and a stated-loss
 * liquidation is its intended public-good function.
 *
 * <p><b>So the guard is gone and the DEFAULT is what remains.</b> That makes "every shipped default is
 * non-negative" the load-bearing invariant rather than a nicety: if any one of them ever ships negative,
 * a node that stated nothing would liquidate, compound or convert at a loss on mainnet, and <b>nothing
 * else in the build would notice</b> — the boot line would fire and read like configuration working.
 *
 * <p>It reads the {@code @Value} defaults out of the source, because that is where the promise lives —
 * a constructed object could be built with anything, and a bound context proves only what that context
 * was told.
 */
class LossMakingIsOptInTest {

    private static final Path APP_CONFIG =
            Path.of("src/main/java/com/fluidtokens/aquarium/offchain/config/AppConfig.java");
    private static final Path YAML = Path.of("src/main/resources/application.yaml");

    /** Every {@code @Value("${…profit-margin-lovelace:X}")} default in the config source. */
    private static final Pattern MARGIN_DEFAULT = Pattern.compile(
            "\\$\\{(loans\\.[a-z.]*profit-margin-lovelace):(-?\\d+)\\}");

    /** The same idea in the yaml, where an env-var default is the operator-visible promise. */
    private static final Pattern YAML_MARGIN_DEFAULT = Pattern.compile(
            "profit-margin-lovelace:\\s*\\$\\{[A-Z_]+:(-?\\d+)\\}");

    @Test
    void noShippedMarginDefaultOperatesAtALoss() throws IOException {
        String source = Files.readString(APP_CONFIG, StandardCharsets.UTF_8);
        Matcher m = MARGIN_DEFAULT.matcher(source);

        int found = 0;
        while (m.find()) {
            found++;
            BigInteger value = new BigInteger(m.group(2));
            assertTrue(value.signum() >= 0,
                    ("%s ships a NEGATIVE default (%s). Since the mainnet hard-fail was removed on "
                            + "Giovanni's ruling, this default is the only thing standing between an "
                            + "operator who configured nothing and a bot that works at a loss on "
                            + "mainnet.").formatted(m.group(1), value));
        }
        assertTrue(found >= 3, "expected a shipped margin default for liquidation, compound and "
                + "convert; found " + found + ". A path whose margin stopped being declared here is a "
                + "path this invariant no longer covers");
    }

    @Test
    void noShippedYamlMarginDefaultOperatesAtALossEither() throws IOException {
        String yaml = Files.readString(YAML, StandardCharsets.UTF_8);
        Matcher m = YAML_MARGIN_DEFAULT.matcher(yaml);

        int found = 0;
        while (m.find()) {
            found++;
            BigInteger value = new BigInteger(m.group(1));
            assertTrue(value.signum() >= 0,
                    "application.yaml ships a NEGATIVE margin default (" + value + "); the yaml "
                            + "overrides the code default, so it is the promise an operator actually "
                            + "receives");
        }
        assertTrue(found >= 1, "no yaml margin default found — the pattern went stale and this test is "
                + "asserting nothing");
    }

    /**
     * ⚠ The DEX-cost floor is NOT a margin and is deliberately excluded from the ruling: it states what
     * the work costs, not what the operator is willing to lose. A negative one is a typo with no
     * meaningful reading, so it stays fatal — and it must stay shipped non-negative too.
     */
    @Test
    void theDexCostFloorAlsoShipsNonNegative() throws IOException {
        String source = Files.readString(APP_CONFIG, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\$\\{loans\\.liquidation\\.convert\\.dex-cost-floor-lovelace:(-?\\d+)\\}")
                .matcher(source);
        assertTrue(m.find(), "the dex-cost-floor default is no longer declared where this can read it");
        assertTrue(new BigInteger(m.group(1)).signum() >= 0);
    }
}
