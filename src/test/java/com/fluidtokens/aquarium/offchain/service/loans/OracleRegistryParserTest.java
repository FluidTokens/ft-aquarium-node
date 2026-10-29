package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClientLoadPinTest.SYNTHETIC_REGISTRY;
import static com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClientLoadPinTest.resource;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OracleRegistryParser} on its own: that it reads what {@link FluidOracleClient} reads, that a
 * WebSocket push and the API snapshot taken after it parse to the same thing, and that it has no side
 * effects.
 *
 * <p>The push fixtures and why each API snapshot was chosen are in
 * {@code src/test/resources/loans-v4/oracle-ws/PROVENANCE.md}.
 */
class OracleRegistryParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PUSH_1 = "/loans-v4/oracle-ws/preview-push-20261029T130459Z.json";
    private static final String PUSH_2 = "/loans-v4/oracle-ws/preview-push-20261029T130959Z.json";
    private static final String API_BEFORE_PUSH_1 = "/loans-v4/oracle-ws/preview-api-20261029T130505Z.json";
    private static final String API_AFTER_PUSH_1 = "/loans-v4/oracle-ws/preview-api-20261029T130525Z.json";
    private static final String API_AFTER_PUSH_2 = "/loans-v4/oracle-ws/preview-api-20261029T131027Z.json";

    private static Set<OracleEntry> parsed(JsonNode payload) {
        return Set.copyOf(OracleRegistryParser.parse(payload).byOracleToken().values());
    }

    /**
     * (a) The parser holds what the client holds, for every payload the characterization pin feeds the
     * client and both pushes. Last-wins on a duplicated oracle NFT included: the synthetic registry
     * names one NFT twice with different reward addresses.
     */
    @Test
    void theParserHoldsExactlyWhatTheClientHolds() throws Exception {
        var payloads = new ArrayList<>(FluidOracleClientLoadPinTest.loadSequence());
        payloads.add(MAPPER.readTree(SYNTHETIC_REGISTRY));
        payloads.add(resource(PUSH_1).path("tokens"));
        payloads.add(resource(PUSH_2).path("tokens"));

        for (JsonNode payload : payloads) {
            var client = new FluidOracleClient("http://unused.invalid");
            client.load(payload);
            assertFalse(client.entries().isEmpty(), "a payload that parses to nothing proves nothing");
            assertEquals(Set.copyOf(client.entries()), parsed(payload));
        }
    }

    /**
     * (b) A push is the registry, delivered: once parsed it equals the API snapshot taken just after
     * it. And NOT the one taken just before it, whose c3 windows had not yet moved — without that half
     * the equality could hold because the comparison sees nothing.
     */
    @Test
    void aPushParsesEqualToTheApiSnapshotThatFollowsItAndNotTheOneBefore() throws Exception {
        assertEquals(parsed(resource(API_AFTER_PUSH_1)), parsed(resource(PUSH_1).path("tokens")));
        assertEquals(parsed(resource(API_AFTER_PUSH_2)), parsed(resource(PUSH_2).path("tokens")));

        assertNotEquals(parsed(resource(API_BEFORE_PUSH_1)), parsed(resource(PUSH_1).path("tokens")),
                "push 1 differs from the 13:05:05Z snapshot in its three c3 windows");
    }

    /**
     * (c) No side effects: nothing is logged under {@code com.fluidtokens}, two parses are equal, and
     * every condition the parse used to log comes back as a diagnostic instead. Duplicate NFTs and
     * version-price disagreement are judged across a whole load, by the client — not here.
     */
    @Test
    void parsingLogsNothingAndReturnsItsConditionsAsDiagnostics() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("com.fluidtokens");
        Level previous = logger.getLevel();
        logger.setLevel(Level.TRACE);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        OracleRegistryParser.Parsed first;
        OracleRegistryParser.Parsed second;
        try {
            first = OracleRegistryParser.parse(MAPPER.readTree(SYNTHETIC_REGISTRY));
            second = OracleRegistryParser.parse(MAPPER.readTree(SYNTHETIC_REGISTRY));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }

        assertEquals(List.of(), appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertEquals(first, second);
        assertEquals(EnumSet.of(OracleRegistryParser.Kind.NO_PUBLIC_KEYS, OracleRegistryParser.Kind.UNRESOLVED_KEYS,
                        OracleRegistryParser.Kind.UNKNOWN_PROVIDER, OracleRegistryParser.Kind.PARSE_FAILURE),
                first.diagnostics().stream().map(OracleRegistryParser.Diagnostic::kind)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(OracleRegistryParser.Kind.class))));
    }

    /**
     * The synthetic registry's 14 entries parse to 8, in registry order: the inactive, blank-provider,
     * missing-provider, zero-denominator, unknown-provider and unparseable ones are dropped. Of the 8,
     * two name one oracle NFT, so 7 are reachable by NFT.
     */
    @Test
    void theSyntheticRegistryKeepsOnlyItsUsableEntries() throws Exception {
        var parsed = OracleRegistryParser.parse(MAPPER.readTree(SYNTHETIC_REGISTRY));

        assertEquals(8, parsed.entries().size());
        assertEquals(7, parsed.byOracleToken().size());
        var inactiveNft = new AssetType("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "6f7261636c65");
        assertTrue(parsed.entries().stream().noneMatch(e -> e.oracleToken().equals(inactiveNft)),
                "an entry with \"active\": false must never be priced");
    }

    /**
     * (d) Anything but an array parses to nothing, without throwing — including a whole push message,
     * whose entries sit under {@code tokens} and must not be found by walking the object's values.
     */
    @Test
    void aNonArrayParsesToNothing() {
        var parsed = assertDoesNotThrow(() -> OracleRegistryParser.parse(resource(PUSH_1)));

        assertTrue(parsed.entries().isEmpty());
        assertTrue(parsed.byOracleToken().isEmpty());
        assertTrue(parsed.diagnostics().isEmpty());
    }
}
