package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>A warning about a STATIC condition, on a 30-second refresh, is not a loud signal — it is a
 * quiet one.</b>
 *
 * <h2>What this exists to stop</h2>
 * Measured on the live preview pod, 2026-09-04: <b>109 identical WARNs in 56 minutes</b>, one per
 * oracle refresh, all saying that FluidTokens' {@code fGold} preview feed publishes signatures with no
 * {@code publicKeys}. The condition is real and correctly reported — and it belongs to a third party's
 * registry that is not about to change, so the log repeats twice a minute indefinitely.
 *
 * <p><b>That teaches whoever reads these logs that WARN means nothing here</b>, and the next WARN may
 * be the one that matters. The fix is not to lower the severity or delete the line: the condition
 * still makes that oracle <em>priceable but not liquidatable</em>, which an operator must know once.
 * <b>Say it once; say it again when it changes.</b>
 *
 * <h2>⚠ The trap in what the latch keys on</h2>
 * The obvious key is the signatures themselves — and it would be <b>useless</b>: an oracle re-signs
 * its payload on every publication, so a content-keyed latch re-warns every refresh while looking
 * exactly like a working one. It keys on the signature <b>count</b> and the <b>set of offending
 * keys</b>, which are stable while the condition is, and move when a signer does.
 */
class OracleWarningLatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** An entry with signatures and NO publicKeys — the shipped preview shape for {@code fGold}. */
    private static String registry(String signature, int count) {
        StringBuilder sigs = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sigs.append(sigs.isEmpty() ? "" : ",")
                    .append("{\"publicKey\":\"aa\",\"signature\":\"").append(signature).append(i).append("\"}");
        }
        // ⚠ The real registry shape, not an approximation: supportedOracle.<preferredOracle>, with the
        // signatures nested under multisigOracle INSIDE that. A fixture that misses it parses to
        // nothing, signatures() is never reached, and the test passes for want of any warning at all.
        return """
                [{"token":{"policyId":"4f4e7bb1aabbccddeeff00112233445566778899aabbccddeeff0011",
                           "assetName":"476f6c64"},
                  "active":true,
                  "preferredOracle":"multisig",
                  "supportedOracle":{"multisig":{
                      "tokenPriceInLovelaces":1000,
                      "tokenPriceDenominator":1000000,
                      "validFrom":0,
                      "validTo":99999999999,
                      "multisigOracle":{"signatures":[%s]}}}}]
                """.formatted(sigs);
    }

    private static ListAppender<ILoggingEvent> attach() {
        Logger logger = (Logger) LoggerFactory.getLogger(FluidOracleClient.class);
        logger.setLevel(Level.DEBUG);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static long warnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("no publicKeys"))
                .count();
    }

    /**
     * ⛔ THE PROPERTY: refreshing an unchanged registry warns exactly ONCE, however many times it is
     * read. Twelve refreshes is half an hour on the production cadence — which produced 109 lines.
     */
    @Test
    void anUnchangedConditionIsWarnedAboutOnceNoMatterHowOftenTheRegistryIsRead() throws Exception {
        var client = new FluidOracleClient("http://localhost:0/unused");
        var appender = attach();

        for (int i = 0; i < 12; i++) {
            client.load(MAPPER.readTree(registry("sig", 3)));
        }

        assertEquals(1, warnings(appender),
                "the condition is static and the registry is re-read every 30s; a warning per refresh "
                        + "is what trained an operator to ignore WARN in the first place");
    }

    /**
     * ⛔ AND THE HALF THAT MAKES THE LATCH SAFE RATHER THAN MERELY QUIET: a change is still reported.
     * A latch that never speaks again is a deleted warning with extra steps.
     */
    @Test
    void aChangedSignerSetIsWarnedAboutAgain() throws Exception {
        var client = new FluidOracleClient("http://localhost:0/unused");
        var appender = attach();

        client.load(MAPPER.readTree(registry("sig", 3)));
        client.load(MAPPER.readTree(registry("sig", 3)));
        assertEquals(1, warnings(appender), "still the same condition");

        client.load(MAPPER.readTree(registry("sig", 4)));   // FluidTokens add a signer

        assertEquals(2, warnings(appender),
                "a signer was added and the operator was not told — the latch has become a mute");
    }

    /**
     * ⚠ THE TRAP, pinned so it cannot be reintroduced. An oracle RE-SIGNS its payload on every
     * publication, so a latch keyed on signature CONTENT would re-warn every refresh while looking
     * exactly like a working one. Same condition, same signer count, different signature bytes: still
     * one warning.
     */
    @Test
    void freshSignaturesOverTheSameSignerSetDoNotReopenTheLatch() throws Exception {
        var client = new FluidOracleClient("http://localhost:0/unused");
        var appender = attach();

        client.load(MAPPER.readTree(registry("first", 3)));
        client.load(MAPPER.readTree(registry("second", 3)));
        client.load(MAPPER.readTree(registry("third", 3)));

        assertEquals(1, warnings(appender),
                "the signatures changed and the CONDITION did not; a content-keyed latch would warn "
                        + "three times here and be indistinguishable from having no latch at all");
    }

    /** ⚠ And the information is not lost — the repeats drop to DEBUG rather than vanishing. */
    @Test
    void theSuppressedRepeatsAreStillAvailableAtDebug() throws Exception {
        var client = new FluidOracleClient("http://localhost:0/unused");
        var appender = attach();

        client.load(MAPPER.readTree(registry("sig", 3)));
        client.load(MAPPER.readTree(registry("sig", 3)));

        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.DEBUG
                        && e.getFormattedMessage().contains("no publicKeys")),
                "a suppressed repeat must still be reachable by turning the logger up, or the latch "
                        + "has hidden evidence rather than deduplicated it");
    }
}
