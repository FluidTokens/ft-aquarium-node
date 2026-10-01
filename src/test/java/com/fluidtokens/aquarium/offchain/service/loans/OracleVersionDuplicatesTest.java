package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ⛔ <b>Two oracle versions per token are the registry's NORMAL shape (FAB-113).</b>
 *
 * <p>Since 2026-09-30 FluidTokens publish most tokens twice — v1 for Lending v3, v2 for Lending v4,
 * permanently. {@code FluidOracleClient.load} WARNed "returned TWO feeds" for each such token on every
 * 30-second refresh: 16 tokens, ~46,000 lines a day on mainnet, about nothing. Real conflicts still warn:
 * two entries naming the SAME oracle NFT, and two versions disagreeing on price while both are valid.
 */
class OracleVersionDuplicatesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN_POLICY = "0691b2fecca1ac4f53cb6dfb00b7013e561d1f34403b957cbb5af1fa";
    private static final String V1 = "93794f9b7f3dc632cb889c7aec7d334f016f532e64f16141b6895f5b";
    private static final String V2 = "26e60b2083c14b849e622f8e05dd46ab01a7986fe5d72eeba8680d26";

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    private ListAppender<ILoggingEvent> attach() {
        logger = (Logger) LoggerFactory.getLogger(FluidOracleClient.class);
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    @AfterEach
    void detach() {
        if (logger != null) {
            logger.detachAppender(appender);
        }
    }

    private long warns(String fragment) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains(fragment))
                .count();
    }

    private long allWarns() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
    }

    /**
     * One NIGHT entry under the given oracle NFT policy, at the given price, valid NOW over the 50-minute
     * window FluidTokens publish (a wider one is refused by the validator's maximum range).
     */
    private static String entry(String oraclePolicy, int version, long price, String publicKeysJson) {
        return """
                {"token":{"policyId":"%s","assetName":"4e49474854"},
                 "fluidOracle":{"policyId":"%s","assetName":"6f7261636c654e69676874"},
                 "active":true,"oracleVersion":%d,"preferredOracle":"multisig",
                 "supportedOracle":{"multisig":{"tokenPriceInLovelaces":%d,"tokenPriceDenominator":1000000,
                     "validFrom":%d,"validTo":%d,
                     "multisigOracle":{%s"signatures":[{"publicKey":"aa","signature":"s"}]}}}}
                """.formatted(TOKEN_POLICY, oraclePolicy, version, price,
                System.currentTimeMillis() - 25 * 60_000L, System.currentTimeMillis() + 25 * 60_000L,
                publicKeysJson);
    }

    @Test
    void todaysMainnetRegistryWarnsNothingAcrossRefreshesAndReportsItsShapeOnce() throws Exception {
        var client = new FluidOracleClient("http://unused.invalid");
        attach();
        var payload = MAPPER.readTree(readFixture());

        client.load(payload);
        client.load(payload);

        assertEquals(0, warns("TWO feeds"), "two versions of one token are normal, not a warning");
        assertEquals(0, warns("TWICE"));
        assertEquals(0, warns("DISAGREE"));
        assertEquals(1, appender.list.stream()
                        .filter(e -> e.getLevel() == Level.INFO)
                        .filter(e -> e.getFormattedMessage().contains("35 oracles across 19 tokens, 16 tokens with more than one"))
                        .count(),
                "the registry's shape is said once, not on every refresh");
    }

    @Test
    void twoValidVersionsDisagreeingOnPriceWarnOnceUntilTheDisagreementChanges() throws Exception {
        var client = new FluidOracleClient("http://unused.invalid");
        attach();
        String disagreeing = "[" + entry(V1, 1, 9_000, "\"publicKeys\":[\"aa\"],") + ","
                + entry(V2, 2, 9_500, "\"publicKeys\":[\"aa\"],") + "]";

        client.load(MAPPER.readTree(disagreeing));
        client.load(MAPPER.readTree(disagreeing));
        assertEquals(1, warns("DISAGREE"), "a real conflict is warned, once");

        String agreeing = "[" + entry(V1, 1, 9_000, "\"publicKeys\":[\"aa\"],") + ","
                + entry(V2, 2, 9_000, "\"publicKeys\":[\"aa\"],") + "]";
        client.load(MAPPER.readTree(agreeing));
        assertEquals(1, warns("DISAGREE"), "agreeing versions are silent");
    }

    /** A LAPSED version differing in price is not a disagreement — only two VALID ones are (finding 7). */
    @Test
    void aLapsedVersionWithAnotherPriceIsNotADisagreement() throws Exception {
        var client = new FluidOracleClient("http://unused.invalid");
        attach();
        String lapsedV2 = entry(V2, 2, 9_500, "\"publicKeys\":[\"aa\"],")
                .replaceFirst("\"validFrom\":\\d+,\"validTo\":\\d+",
                        "\"validFrom\":1000,\"validTo\":2000");
        client.load(MAPPER.readTree("[" + entry(V1, 1, 9_000, "\"publicKeys\":[\"aa\"],") + "," + lapsedV2 + "]"));

        assertEquals(0, warns("DISAGREE"), "a lapsed version is not one feed disagreeing with itself");
    }

    /** A duplicated NFT is one oracle, not a second version of its token (finding 9). */
    @Test
    void aDuplicatedNftDoesNotCountAsASecondVersion() throws Exception {
        var client = new FluidOracleClient("http://unused.invalid");
        attach();
        String twice = "[" + entry(V1, 1, 9_000, "\"publicKeys\":[\"aa\"],") + ","
                + entry(V1, 1, 9_000, "\"publicKeys\":[\"aa\"],") + "]";

        client.load(MAPPER.readTree(twice));

        assertEquals(1, appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO)
                .filter(e -> e.getFormattedMessage().contains("1 oracles across 1 tokens, 0 tokens with more than one"))
                .count(), "the shape must count one oracle, not two versions");
    }

    @Test
    void theSameOracleNftListedTwiceIsAConflict() throws Exception {
        var client = new FluidOracleClient("http://unused.invalid");
        attach();
        String twice = "[" + entry(V1, 1, 9_000, "\"publicKeys\":[\"aa\"],") + ","
                + entry(V1, 1, 9_000, "\"publicKeys\":[\"aa\"],") + "]";

        client.load(MAPPER.readTree(twice));
        client.load(MAPPER.readTree(twice));

        assertEquals(1, warns("TWICE"), "one NFT, two entries: the oracle a loan names is ambiguous");
    }

    /**
     * ⚠ The latch is per ORACLE NFT. Two versions of one token, each with its own static condition
     * (no publicKeys, different signature counts): with a per-TOKEN latch the two states overwrite
     * each other and the warning repeats on every refresh — six lines here instead of two.
     */
    @Test
    void eachVersionsStaticConditionIsWarnedOnceNotOnEveryRefresh() throws Exception {
        var client = new FluidOracleClient("http://unused.invalid");
        attach();
        String v1NoKeys = entry(V1, 1, 9_000, "");
        String v2NoKeys = entry(V2, 2, 9_000, "").replace(
                "\"signatures\":[{\"publicKey\":\"aa\",\"signature\":\"s\"}]",
                "\"signatures\":[{\"publicKey\":\"aa\",\"signature\":\"s\"},{\"publicKey\":\"bb\",\"signature\":\"t\"}]");
        String both = "[" + v1NoKeys + "," + v2NoKeys + "]";

        for (int i = 0; i < 3; i++) {
            client.load(MAPPER.readTree(both));
        }

        assertEquals(2, warns("no publicKeys"), "one warning per oracle, not one per refresh");
        assertEquals(2, allWarns(), "and nothing else warned");
    }

    private static String readFixture() throws Exception {
        try (InputStream in = OracleVersionDuplicatesTest.class
                .getResourceAsStream("/loans-v4/mainnet-oracle-registry-2026-10-01.json")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
