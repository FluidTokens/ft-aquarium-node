package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ⛔ <b>A characterization pin: {@link FluidOracleClient#load} must keep doing exactly what it did before
 * its parsing moved into {@link OracleRegistryParser} (FAB-174).</b>
 *
 * <p>Every literal below was RECORDED by running this test against the unmodified base, where all
 * parsing still lived inside {@code FluidOracleClient}, and it passed there unchanged. It only uses API
 * that existed at that base, so it can be run there again to prove it.
 *
 * <p>What is pinned, across one client fed a fixed sequence of payloads: the entries it holds (as a
 * digest of their {@code toString()}), {@code trackedAssets()}, {@code trackedOracles()}, and <b>every
 * log event at DEBUG and above, level and formatted message, in order</b>. The order matters as much as
 * the content: a parse warning, a latched warning and the duplicate-NFT warning for one entry must still
 * interleave the way they did, and the latch ({@link OracleWarningLatchTest}) must still drop a repeat to
 * DEBUG on the second load of the same registry.
 *
 * <p>The synthetic registry's windows (0 .. 99999999999) are wider than the one-hour protocol maximum,
 * so no feed in it is ever usable and the version-price-disagreement check cannot fire on the clock.
 * Every committed registry fixture is long expired for the same reason, so nothing here depends on
 * when it runs.
 */
class FluidOracleClientLoadPinTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * One entry per parser branch, labelled by {@code _id} (which the parser ignores, as it ignores the
     * real registry's): (a) inactive, (b) blank {@code preferredOracle}, (c) a {@code preferredOracle}
     * absent from {@code supportedOracle}, (d) a zero denominator, (e) an unknown provider, (f) signatures
     * without {@code publicKeys}, (g) a signature from a key outside {@code publicKeys}, (h) a well-formed
     * multisig v1, (i) h's token at v2 under another NFT and price, (j) h's NFT again with another
     * {@code rewardAddress} and a signature from a key outside its {@code publicKeys}, (k) c3 with a blank
     * c3 policy and a malformed reference input, (l) a well-formed c3, (m) a {@code rewardAddress} that is
     * not bech32, which throws inside the parse, and (n) {@code oracleVersion} as a string.
     *
     * <p>(m) was checked at the base to reach the parse's catch: {@code new Address("not-a-bech32-address")}
     * throws {@code StringIndexOutOfBoundsException}, and the recorded WARN carries the JDK 21 wording of it.
     *
     * <p>⚠ (j) is the one entry that carries BOTH a parse condition and a duplicate NFT, which is what pins
     * their interleaving: its unresolved-key WARN must come before the duplicate-NFT line, on the first load
     * and again (both at DEBUG, latched) on the second. Its latch (h's NFT, unresolved keys) is shared with
     * no other entry, so no other entry's state can mask or reopen it.
     */
    static final String SYNTHETIC_REGISTRY = """
            [
            {"_id":"a inactive","token":{"policyId":"11111111111111111111111111111111111111111111111111111111","assetName":"546f6b656e"},"active":false,"preferredOracle":"multisig","oracleVersion":1,
             "fluidOracle":{"policyId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","assetName":"6f7261636c65","rewardAddress":"stake_test17pqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqwavks2","referenceInput":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa#0","referenceScript":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb#0"},
             "multisigOracle":{"publicKeys":["1111111111111111111111111111111111111111111111111111111111111111","2222222222222222222222222222222222222222222222222222222222222222"],"requiredSignatures":2},
             "supportedOracle":{"multisig":{"tokenPriceInLovelaces":1000,"tokenPriceDenominator":1000000,"validFrom":0,"validTo":99999999999,"multisigOracle":{"signatures":[{"publicKey":"1111111111111111111111111111111111111111111111111111111111111111","signature":"s1"},{"publicKey":"2222222222222222222222222222222222222222222222222222222222222222","signature":"s2"}]}}}},
            {"_id":"b blank preferredOracle","token":{"policyId":"22222222222222222222222222222222222222222222222222222222","assetName":"546f6b656e"},"active":true,"preferredOracle":"","oracleVersion":1,
             "fluidOracle":{"policyId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","assetName":"6f7261636c65","rewardAddress":"stake_test17pqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqwavks2","referenceInput":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa#0","referenceScript":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb#0"},
             "supportedOracle":{"multisig":{"tokenPriceInLovelaces":1000,"tokenPriceDenominator":1000000,"validFrom":0,"validTo":99999999999}}},
            {"_id":"c preferredOracle not in supportedOracle","token":{"policyId":"33333333333333333333333333333333333333333333333333333333","assetName":"546f6b656e"},"active":true,"preferredOracle":"c3","oracleVersion":1,
             "fluidOracle":{"policyId":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccc","assetName":"6f7261636c65","rewardAddress":"stake_test17pqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqwavks2","referenceInput":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa#0","referenceScript":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb#0"},
             "supportedOracle":{"multisig":{"tokenPriceInLovelaces":1000,"tokenPriceDenominator":1000000,"validFrom":0,"validTo":99999999999}}},
            {"_id":"d zero denominator","token":{"policyId":"44444444444444444444444444444444444444444444444444444444","assetName":"546f6b656e"},"active":true,"preferredOracle":"multisig","oracleVersion":1,
             "fluidOracle":{"policyId":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddd","assetName":"6f7261636c65","rewardAddress":"stake_test17pqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqwavks2","referenceInput":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa#0","referenceScript":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb#0"},
             "supportedOracle":{"multisig":{"tokenPriceInLovelaces":1000,"tokenPriceDenominator":0,"validFrom":0,"validTo":99999999999}}},
            {"_id":"e unknown provider","token":{"policyId":"55555555555555555555555555555555555555555555555555555555","assetName":"546f6b656e"},"active":true,"preferredOracle":"orcfax","oracleVersion":1,
             "fluidOracle":{"policyId":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee","assetName":"6f7261636c65","rewardAddress":"stake_test17pqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqwavks2","referenceInput":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa#0","referenceScript":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb#0"},
             "supportedOracle":{"orcfax":{"tokenPriceInLovelaces":1000,"tokenPriceDenominator":1000000,"validFrom":0,"validTo":99999999999}}},
            {"_id":"f signatures without publicKeys","token":{"policyId":"66666666666666666666666666666666666666666666666666666666","assetName":"546f6b656e"},"active":true,"preferredOracle":"multisig","oracleVersion":1,
             "fluidOracle":{"policyId":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffff","assetName":"6f7261636c65","rewardAddress":"stake_test17pqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqwavks2","referenceInput":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa#0","referenceScript":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb#0"},
             "supportedOracle":{"multisig":{"tokenPriceInLovelaces":1000,"tokenPriceDenominator":1000000,"validFrom":0,"validTo":99999999999,"multisigOracle":{"signatures":[{"publicKey":"1111111111111111111111111111111111111111111111111111111111111111","signature":"s1"},{"publicKey":"2222222222222222222222222222222222222222222222222222222222222222","signature":"s2"}]}}}},
            {"_id":"g signature from a key outside publicKeys","token":{"policyId":"77777777777777777777777777777777777777777777777777777777","assetName":"546f6b656e"},"active":true,"preferredOracle":"multisig","oracleVersion":1,
             "fluidOracle":{"policyId":"00000000000000000000000000000000000000000000000000000000","assetName":"6f7261636c65","rewardAddress":"stake_test17pqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqwavks2","referenceInput":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa#0","referenceScript":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb#0"},
             "multisigOracle":{"publicKeys":["1111111111111111111111111111111111111111111111111111111111111111","2222222222222222222222222222222222222222222222222222222222222222"],"requiredSignatures":2},
             "supportedOracle":{"multisig":{"tokenPriceInLovelaces":1000,"tokenPriceDenominator":1000000,"validFrom":0,"validTo":99999999999,"multisigOracle":{"signatures":[{"publicKey":"1111111111111111111111111111111111111111111111111111111111111111","signature":"s1"},{"publicKey":"9999999999999999999999999999999999999999999999999999999999999999","signature":"s9"}]}}}},
            {"_id":"h well-formed multisig v1","token":{"policyId":"88888888888888888888888888888888888888888888888888888888","assetName":"546f6b656e"},"active":true,"preferredOracle":"multisig","oracleVersion":1,
             "fluidOracle":{"policyId":"88888888888888888888888888888888888888888888888888888888","assetName":"6f7261636c65","rewardAddress":"stake_test17pqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqwavks2","referenceInput":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa#0","referenceScript":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb#0"},
             "multisigOracle":{"publicKeys":["1111111111111111111111111111111111111111111111111111111111111111","2222222222222222222222222222222222222222222222222222222222222222"],"requiredSignatures":2},
             "supportedOracle":{"multisig":{"tokenPriceInLovelaces":1000,"tokenPriceDenominator":1000000,"validFrom":0,"validTo":99999999999,"multisigOracle":{"signatures":[{"publicKey":"1111111111111111111111111111111111111111111111111111111111111111","signature":"s1"},{"publicKey":"2222222222222222222222222222222222222222222222222222222222222222","signature":"s2"}]}}}},
            {"_id":"i same token as h, v2","token":{"policyId":"88888888888888888888888888888888888888888888888888888888","assetName":"546f6b656e"},"active":true,"preferredOracle":"multisig","oracleVersion":2,
             "fluidOracle":{"policyId":"99999999999999999999999999999999999999999999999999999999","assetName":"6f7261636c65","rewardAddress":"stake_test17pqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqwavks2","referenceInput":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa#0","referenceScript":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb#0"},
             "multisigOracle":{"publicKeys":["1111111111111111111111111111111111111111111111111111111111111111","2222222222222222222222222222222222222222222222222222222222222222"],"requiredSignatures":2},
             "supportedOracle":{"multisig":{"tokenPriceInLovelaces":5000,"tokenPriceDenominator":1000000,"validFrom":0,"validTo":99999999999,"multisigOracle":{"signatures":[{"publicKey":"1111111111111111111111111111111111111111111111111111111111111111","signature":"t1"},{"publicKey":"2222222222222222222222222222222222222222222222222222222222222222","signature":"t2"}]}}}},
            {"_id":"j h's oracle NFT again, with a signature from a key outside publicKeys","token":{"policyId":"88888888888888888888888888888888888888888888888888888888","assetName":"546f6b656e"},"active":true,"preferredOracle":"multisig","oracleVersion":1,
             "fluidOracle":{"policyId":"88888888888888888888888888888888888888888888888888888888","assetName":"6f7261636c65","rewardAddress":"stake178tzp8jzvtts5t59f7q2vgrn49p2smpaq9kgvfekup359fqle74mk","referenceInput":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa#0","referenceScript":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb#0"},
             "multisigOracle":{"publicKeys":["1111111111111111111111111111111111111111111111111111111111111111","2222222222222222222222222222222222222222222222222222222222222222"],"requiredSignatures":2},
             "supportedOracle":{"multisig":{"tokenPriceInLovelaces":1000,"tokenPriceDenominator":1000000,"validFrom":0,"validTo":99999999999,"multisigOracle":{"signatures":[{"publicKey":"1111111111111111111111111111111111111111111111111111111111111111","signature":"s1"},{"publicKey":"abababababababababababababababababababababababababababababababab","signature":"sA"}]}}}},
            {"_id":"k c3 with blank policyId","token":{"policyId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","assetName":"546f6b656e"},"active":true,"preferredOracle":"c3","oracleVersion":1,
             "fluidOracle":{"policyId":"11111111111111111111111111111111111111111111111111111111","assetName":"6f7261636c65","rewardAddress":"stake_test17pqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqwavks2","referenceInput":"abc#x","referenceScript":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb#0"},
             "supportedOracle":{"c3":{"policyId":"","assetName":"4f7261636c6546656564","referenceInput":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd#0","tokenPriceInLovelaces":2000,"tokenPriceDenominator":1000000,"validFrom":0,"validTo":99999999999}}},
            {"_id":"l well-formed c3","token":{"policyId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","assetName":"546f6b656e"},"active":true,"preferredOracle":"c3","oracleVersion":1,
             "fluidOracle":{"policyId":"22222222222222222222222222222222222222222222222222222222","assetName":"6f7261636c65","rewardAddress":"stake_test17pqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqwavks2","referenceInput":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa#0","referenceScript":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb#0"},
             "supportedOracle":{"c3":{"policyId":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccc","assetName":"4f7261636c6546656564","referenceInput":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd#0","tokenPriceInLovelaces":2000,"tokenPriceDenominator":1000000,"validFrom":0,"validTo":99999999999}}},
            {"_id":"m rewardAddress not bech32","token":{"policyId":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccc","assetName":"546f6b656e"},"active":true,"preferredOracle":"multisig","oracleVersion":1,
             "fluidOracle":{"policyId":"33333333333333333333333333333333333333333333333333333333","assetName":"6f7261636c65","rewardAddress":"not-a-bech32-address","referenceInput":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa#0","referenceScript":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb#0"},
             "supportedOracle":{"multisig":{"tokenPriceInLovelaces":1000,"tokenPriceDenominator":1000000,"validFrom":0,"validTo":99999999999}}},
            {"_id":"n oracleVersion as a string","token":{"policyId":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddd","assetName":"546f6b656e"},"active":true,"preferredOracle":"multisig","oracleVersion":"2",
             "fluidOracle":{"policyId":"44444444444444444444444444444444444444444444444444444444","assetName":"6f7261636c65","rewardAddress":"stake_test17pqzexzdvwtl2zxw6pn5v34m9lxk0avnckmemy0puhqtzfqwavks2","referenceInput":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa#0","referenceScript":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb#0"},
             "supportedOracle":{"multisig":{"tokenPriceInLovelaces":1000,"tokenPriceDenominator":1000000,"validFrom":0,"validTo":99999999999}}}
            ]
            """;

    /** The order a client is fed in. A repeat of the synthetic registry exercises the latch. */
    static List<JsonNode> loadSequence() throws Exception {
        return List.of(
                MAPPER.readTree(SYNTHETIC_REGISTRY),
                MAPPER.readTree(SYNTHETIC_REGISTRY),
                resource("/loans-v4/mainnet-oracle-registry-2026-10-01.json"),
                resource("/loans-v4/oracle-registry-preview.json"),
                resource("/loans-v4/oracle-registry.json"),
                resource("/loans-v4/oracle-ws/preview-push-20261029T130459Z.json").path("tokens"),
                resource("/loans-v4/oracle-ws/preview-api-20261029T130525Z.json"));
    }

    static JsonNode resource(String path) throws Exception {
        try (InputStream in = FluidOracleClientLoadPinTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new AssertionError("missing test resource " + path);
            }
            return MAPPER.readTree(in);
        }
    }

    /** SHA-256 of the entries' {@code toString()}s, sorted and joined with {@code \n}. */
    static String digest(Collection<OracleEntry> entries) throws Exception {
        String joined = String.join("\n", entries.stream().map(OracleEntry::toString).sorted().toList());
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void loadingTheSequenceHoldsAndLogsExactlyWhatTheBaseDid() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(FluidOracleClient.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var client = new FluidOracleClient("http://unused.invalid");
            var held = new ArrayList<String>();
            for (JsonNode payload : loadSequence()) {
                client.load(payload);
                held.add(client.trackedAssets() + " assets / " + client.trackedOracles() + " oracles / "
                        + digest(client.entries()));
            }
            List<String> events = appender.list.stream()
                    .map(event -> event.getLevel() + "|" + event.getFormattedMessage())
                    .toList();
            assertEquals(EXPECTED_EVENTS, events, "the log a load emits, in order");
            assertEquals(EXPECTED_HELD, held, "what the client holds after each load");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }

    /**
     * Recorded at the base (95e67f3): every event of the {@code FluidOracleClient} logger at DEBUG and above.
     * Re-recorded there when (j) gained its unresolved key — see its two lines just before each duplicate-NFT
     * line, which is the interleaving this list exists to hold.
     */
    private static final List<String> EXPECTED_EVENTS = List.of(
            "INFO|FluidTokens oracle registry: http://unused.invalid",
            "WARN|oracle 55555555555555555555555555555555555555555555555555555555546f6b656e uses unknown provider 'orcfax'; skipping it rather than guessing a variant",
            "WARN|oracle 66666666666666666666666666666666666666666666666666666666546f6b656e publishes 2 signature(s) but no publicKeys; cannot resolve key positions, so it stays priceable but not liquidatable",
            "WARN|oracle 77777777777777777777777777777777777777777777777777777777546f6b656e published signatures from keys outside its publicKeys [9999999999999999999999999999999999999999999999999999999999999999]; dropping them",
            "WARN|oracle 88888888888888888888888888888888888888888888888888888888546f6b656e published signatures from keys outside its publicKeys [abababababababababababababababababababababababababababababababab]; dropping them",
            "WARN|oracle registry lists oracle NFT 888888888888888888888888888888888888888888888888888888886f7261636c65 TWICE (token 88888888888888888888888888888888888888888888888888888888546f6b656e) — keeping the last. A loan names its oracle by this NFT, so which entry it gets is now arbitrary.",
            "WARN|could not parse a FluidTokens oracle entry: java.lang.StringIndexOutOfBoundsException: Range [0, -1) out of bounds for length 20",
            "INFO|FluidTokens oracle registry: 7 oracles across 6 tokens, 1 tokens with more than one oracle version",
            "WARN|oracle 55555555555555555555555555555555555555555555555555555555546f6b656e uses unknown provider 'orcfax'; skipping it rather than guessing a variant",
            "DEBUG|oracle 66666666666666666666666666666666666666666666666666666666546f6b656e publishes 2 signature(s) but no publicKeys; cannot resolve key positions, so it stays priceable but not liquidatable",
            "DEBUG|oracle 77777777777777777777777777777777777777777777777777777777546f6b656e published signatures from keys outside its publicKeys [9999999999999999999999999999999999999999999999999999999999999999]; dropping them",
            "DEBUG|oracle 88888888888888888888888888888888888888888888888888888888546f6b656e published signatures from keys outside its publicKeys [abababababababababababababababababababababababababababababababab]; dropping them",
            "DEBUG|oracle registry lists oracle NFT 888888888888888888888888888888888888888888888888888888886f7261636c65 TWICE (token 88888888888888888888888888888888888888888888888888888888546f6b656e) — keeping the last. A loan names its oracle by this NFT, so which entry it gets is now arbitrary.",
            "WARN|could not parse a FluidTokens oracle entry: java.lang.StringIndexOutOfBoundsException: Range [0, -1) out of bounds for length 20",
            "INFO|FluidTokens oracle registry: 35 oracles across 19 tokens, 16 tokens with more than one oracle version",
            "WARN|oracle 4f4e7bb17c0e7201cc82f0177ab22695fbcee2d99735d1c3fdc44eac66476f6c64 publishes 3 signature(s) but no publicKeys; cannot resolve key positions, so it stays priceable but not liquidatable",
            "INFO|FluidTokens oracle registry: 5 oracles across 5 tokens, 0 tokens with more than one oracle version",
            "INFO|FluidTokens oracle registry: 19 oracles across 19 tokens, 0 tokens with more than one oracle version",
            "DEBUG|oracle 4f4e7bb17c0e7201cc82f0177ab22695fbcee2d99735d1c3fdc44eac66476f6c64 publishes 3 signature(s) but no publicKeys; cannot resolve key positions, so it stays priceable but not liquidatable",
            "INFO|FluidTokens oracle registry: 5 oracles across 5 tokens, 0 tokens with more than one oracle version",
            "DEBUG|oracle 4f4e7bb17c0e7201cc82f0177ab22695fbcee2d99735d1c3fdc44eac66476f6c64 publishes 3 signature(s) but no publicKeys; cannot resolve key positions, so it stays priceable but not liquidatable");

    /** Recorded at the base (95e67f3): trackedAssets, trackedOracles and the entries digest after each load. */
    private static final List<String> EXPECTED_HELD = List.of(
            "6 assets / 7 oracles / 4dc0dcb6f688e4a033b4aae2e7eb7ff5b73793bacadb1b5382818d7e8d841009",
            "6 assets / 7 oracles / 4dc0dcb6f688e4a033b4aae2e7eb7ff5b73793bacadb1b5382818d7e8d841009",
            "19 assets / 35 oracles / cfe89af0e4ed25260220bccfb26af5c82ebe27976e3e6bac52d8718e84a7b65f",
            "5 assets / 5 oracles / 5723d238a55c6d98b08b5be22ca351333aa99a62a013f6b4b00c91b671564636",
            "19 assets / 19 oracles / 07bf9ba1cd384cae29ebbbeb5c4808ed91199d572308ab2c699780f03e4a6aee",
            "5 assets / 5 oracles / c9081cf7cc6b2d9dde9724914585bd4f7e707a77eded36070e952b022219f4e5",
            "5 assets / 5 oracles / c9081cf7cc6b2d9dde9724914585bd4f7e707a77eded36070e952b022219f4e5");
}
