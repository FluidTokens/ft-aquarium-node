package com.fluidtokens.aquarium.offchain.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-141: {@code blockfrost.key} is stripped and validated ONCE, where it is bound, and every consumer
 * is handed that value.
 *
 * <h2>The defect</h2>
 * {@code BlockfrostConfig} passed the raw property to {@code new BFBackendService(url, key)}. That client
 * sends the key as the {@code project_id} header through OkHttp, which throws
 * {@code IllegalArgumentException("Unexpected char 0x0a at N in project_id value: <the key>")} for a CR or
 * LF — and OkHttp redacts only Authorization and Cookie, so the key lands wherever the caller logs the
 * exception. A trailing newline is the honest case (a k8s {@code --from-file} secret, a CRLF env file).
 * The FAB-108 verifier's lookup already stripped the key and booted clean, while every other Blockfrost
 * call — evaluate, submit, oracle probe, protocol params, scripts — threw with the key in its message.
 *
 * <p>⚠ Every key here is put in through a {@link MapPropertySource}, never {@code withPropertyValues}:
 * {@code TestPropertyValues} parses {@code name=value} text and may trim it, and a trimmed fixture would
 * turn the red at the base green for the wrong reason. The red at the base is the proof that the raw
 * value arrived.
 */
class BlockfrostKeyTest {

    /** A fake project id — never a real key. */
    private static final String SENTINEL = "previewSENTINELprojectid0000000000";
    private static final String TAIL = "tailSENTINELafterthebreak0000";

    /** The text the FAB-108 lookup has always refused with, byte for byte. */
    private static final String REFUSAL =
            "blockfrost.key contains characters not allowed in an HTTP header (value withheld)";

    /** A loopback URL: {@code BFBackendService} does no I/O at construction, so nothing is ever asked. */
    private static final String LOOPBACK = "http://127.0.0.1:9/api/v0/";

    /** Keys no header can carry once stripped: an inner space, a non-ASCII letter, an inner line break. */
    private static final List<String> REFUSED = List.of(SENTINEL + " x", SENTINEL + "é", SENTINEL + "a\nb" + TAIL);

    /** Every part of a refused key a text could echo, the span across the break included. */
    private static final List<String> FRAGMENTS =
            List.of(SENTINEL, TAIL, "a\nb", SENTINEL + "a", "b" + TAIL, SENTINEL + " x", SENTINEL + "é");

    private static ApplicationContextRunner runner(String key) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("fab-141", Map.of("blockfrost.url", LOOPBACK, "blockfrost.key", key))))
                .withUserConfiguration(BlockfrostConfig.class);
    }

    private static String shown(String key) {
        return key.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    // ======================================================================================
    // BlockfrostKey — the rule itself
    // ======================================================================================

    /**
     * Surrounding whitespace is stripped — {@code strip()}, not {@code trim()}: a Unicode space such as
     * U+2003 is whitespace to {@code strip()} and a non-ASCII character to {@code trim()}, which would refuse
     * a key the lookup accepts.
     */
    @Test
    void surroundingWhitespaceIsStripped() {
        for (String key : List.of(SENTINEL, SENTINEL + "\n", SENTINEL + "\r\n", " " + SENTINEL + "\t",
                "\u2003" + SENTINEL + "\u2003")) {
            assertEquals(SENTINEL, BlockfrostKey.of(key).value(), "key " + shown(key));
        }
    }

    @Test
    void nullAndBlankAreUnset() {
        for (String blank : new String[]{null, "", "  ", "  \r\n", "\t"}) {
            assertEquals("", BlockfrostKey.of(blank).value(), "key " + (blank == null ? "null" : shown(blank)));
        }
    }

    /** The refusal is the lookup's text exactly, carries no cause, and no text it produces names the key. */
    @Test
    void aKeyThatIsNotVisibleAsciiAfterTheStripIsRefusedWithoutBeingNamed() {
        assertEquals(REFUSAL, BlockfrostKey.NOT_A_HEADER_VALUE, "the refusal text is the lookup's, byte for byte");
        for (String key : REFUSED) {
            IllegalStateException refused = null;
            try {
                BlockfrostKey.of(key);
            } catch (IllegalStateException e) {
                refused = e;
            }
            assertNotNull(refused, "key " + shown(key) + " must be refused");
            assertEquals(BlockfrostKey.NOT_A_HEADER_VALUE, refused.getMessage(), "key " + shown(key));
            assertNull(refused.getCause(), "a cause could carry the value: key " + shown(key));
            assertNoFragment(refused.getMessage(), "the refusal's message");
            assertNoFragment(refused.toString(), "the refusal's toString");
        }
    }

    /**
     * Visible ASCII is 0x21–0x7E exactly: {@code !} and {@code ~}, its two edges, are accepted, and DEL (0x7F),
     * the first character past the top, is refused — OkHttp refuses 0x7F too and echoes the value with it.
     */
    @Test
    void theVisibleAsciiEdgesAreExactBangAndTildeAcceptedDelRefused() {
        assertEquals("!" + SENTINEL + "~", BlockfrostKey.of("!" + SENTINEL + "~").value(),
                "0x21 and 0x7E are visible ASCII");

        IllegalStateException refused = null;
        try {
            BlockfrostKey.of(SENTINEL + "\u007f");
        } catch (IllegalStateException e) {
            refused = e;
        }
        assertNotNull(refused, "a key ending in DEL (0x7F) must be refused");
        assertEquals(BlockfrostKey.NOT_A_HEADER_VALUE, refused.getMessage(), "a key ending in DEL");
        assertNull(refused.getCause(), "a cause could carry the value: a key ending in DEL");
        assertNoFragment(refused.getMessage(), "the refusal's message");
        assertNoFragment(refused.toString(), "the refusal's toString");
    }

    /** A record's {@code toString} would print the key; this one never does, set or unset. */
    @Test
    void toStringNeverContainsTheKey() {
        String shownKey = BlockfrostKey.of(SENTINEL + "\n").toString();
        assertFalse(shownKey.contains(SENTINEL), shownKey);
        assertFalse(BlockfrostKey.of("").toString().isEmpty());
    }

    // ======================================================================================
    // (e) the client every Blockfrost call goes through is handed the stripped key
    // ======================================================================================

    /**
     * ⛔ Surrounding whitespace is stripped before the key reaches {@code BFBackendService}, so the honest
     * trailing-newline key works instead of throwing with itself in the message. Blank stays exactly what it
     * was at the base — {@code ""}, a key-less Blockfrost-compatible URL being a legitimate setup.
     */
    @Test
    void theBackendServiceIsHandedTheStrippedKey() {
        withRootLogs(logs -> {
            for (String key : List.of(SENTINEL + "\n", SENTINEL + "\r\n", " " + SENTINEL + "\t")) {
                runner(key).run(context -> {
                    assertNull(context.getStartupFailure(), "key " + shown(key) + " must boot");
                    assertEquals(SENTINEL, context.getBean(BFBackendService.class).getProjectId(),
                            "the client must be handed the stripped key, not the raw one: key " + shown(key));
                });
            }
            for (String blank : List.of("", "  \r\n")) {
                runner(blank).run(context -> {
                    assertNull(context.getStartupFailure(), "a blank key must still boot: " + shown(blank));
                    assertEquals("", context.getBean(BFBackendService.class).getProjectId(),
                            "a blank key is unset: " + shown(blank));
                });
            }
        });
    }

    // ======================================================================================
    // (f) a key no header can carry refuses boot, and nothing says what it was
    // ======================================================================================

    /**
     * ⛔ The owner's boot decision: every transaction this node builds is evaluated and submitted through
     * Blockfrost, so a key that is still not visible ASCII after the strip cannot work for anything. It
     * refuses the context — and neither the refusal, nor any exception wrapping it, nor any log line names
     * any part of it.
     */
    @Test
    void aKeyNoHeaderCanCarryRefusesBootAndIsNeverEchoed() {
        withRootLogs(logs -> {
            for (String key : REFUSED) {
                runner(key).run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertNotNull(failure, "key " + shown(key) + " cannot travel as a header and must refuse boot");

                    Throwable deepest = failure;
                    while (deepest.getCause() != null) {
                        deepest = deepest.getCause();
                    }
                    assertEquals(IllegalStateException.class, deepest.getClass(), "key " + shown(key));
                    assertEquals(REFUSAL, deepest.getMessage(), "key " + shown(key));
                    for (Throwable t = failure; t != null; t = t.getCause()) {
                        assertNoFragment(t.getMessage(), "a startup-failure message");
                        assertNoFragment(t.toString(), "a startup-failure toString");
                    }
                });
            }
        });
    }

    // ======================================================================================
    // (g) one binding site, and every consumer goes through it
    // ======================================================================================

    private static final Path MAIN = Path.of("src/main/java");
    private static final String BLOCKFROST_CONFIG = "com/fluidtokens/aquarium/offchain/config/BlockfrostConfig.java";
    private static final String VERIFIER = "com/fluidtokens/aquarium/offchain/service/LoansReferenceScriptVerifier.java";

    private static final String QUALIFIER = "(?:[A-Za-z_$][\\w$]*\\s*\\.\\s*)*";
    private static final Pattern PLACEHOLDER = Pattern.compile(Pattern.quote("${blockfrost.key"));
    private static final Pattern NEW_BACKEND_SERVICE = construction("BFBackendService");
    private static final Pattern NEW_LOOKUP = construction("BlockfrostPublishedOutputLookup");
    private static final Pattern GET_PROPERTY = Pattern.compile("\\bgetProperty\\s*\\(\\s*\"blockfrost");
    private static final Pattern CONFIGURATION_PROPERTIES = Pattern.compile("@\\s*" + QUALIFIER
            + "ConfigurationProperties\\s*\\(\\s*(?:(?:prefix|value)\\s*=\\s*)?\\{?\\s*\"blockfrost[.\"]");
    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("BLOCKFROST_KEY");

    /** {@code new X(} or {@code X::new}, the type optionally qualified. */
    private static Pattern construction(String type) {
        return Pattern.compile("\\bnew\\s+" + QUALIFIER + Pattern.quote(type) + "\\s*(?:<[^()]*>\\s*)?\\("
                + "|\\b" + QUALIFIER + Pattern.quote(type) + "\\s*::\\s*new\\b");
    }

    private record Site(String file, int line) {
        @Override
        public String toString() {
            return file + ":" + line;
        }
    }

    /**
     * ⛔ {@code BlockfrostConfig} is the only reader of {@code ${blockfrost.key}}, exactly once; it alone
     * builds the {@code BFBackendService}; the verifier alone builds the lookup; and nothing reads the
     * property by another route. A second reader would be handed the raw value again — which is the
     * defect, one consumer at a time.
     *
     * <p>⚠ Comments are blanked and string literals KEPT, because the placeholder lives inside one. The
     * scan must also find each site it knows exists, so an empty or misdirected scan cannot pass.
     */
    @Test
    void blockfrostConfigIsTheOnlyPlaceTheKeyIsBound() {
        List<String> violations = new ArrayList<>();

        List<Site> placeholders = scan(PLACEHOLDER);
        onlyIn(placeholders, BLOCKFROST_CONFIG, "${blockfrost.key", violations);
        long inConfig = placeholders.stream().filter(site -> site.file().equals(BLOCKFROST_CONFIG)).count();
        if (inConfig != 1) {
            violations.add("${blockfrost.key appears " + inConfig + " times in BlockfrostConfig: " + placeholders
                    + " — it belongs there once, as the BlockfrostKey bean's parameter");
        }
        onlyIn(scan(NEW_BACKEND_SERVICE), BLOCKFROST_CONFIG, "new BFBackendService(", violations);
        onlyIn(scan(NEW_LOOKUP), VERIFIER, "new BlockfrostPublishedOutputLookup(", violations);
        scan(GET_PROPERTY).forEach(site -> violations.add("getProperty(\"blockfrost at " + site
                + " — a second read of the property, handed the raw value"));
        scan(CONFIGURATION_PROPERTIES).forEach(site -> violations.add("@ConfigurationProperties over the "
                + "blockfrost prefix at " + site + " — a second binding of the key, never normalised"));
        scan(ENVIRONMENT_NAME).forEach(site -> violations.add("BLOCKFROST_KEY at " + site
                + " — the environment variable read directly, around the normalised bean"));

        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    /** THE SCAN MUST SEE WHAT IS THERE: each known site is found where it is known to be. */
    @Test
    void theScanFindsTheSitesItKnowsExist() {
        assertTrue(scan(PLACEHOLDER).stream().anyMatch(site -> site.file().equals(BLOCKFROST_CONFIG)),
                "the scan did not find BlockfrostConfig's ${blockfrost.key placeholder: it is not reading the "
                        + "source it guards");
        assertTrue(scan(NEW_BACKEND_SERVICE).stream().anyMatch(site -> site.file().equals(BLOCKFROST_CONFIG)),
                "the scan did not find BlockfrostConfig's new BFBackendService(");
        assertTrue(scan(NEW_LOOKUP).stream().anyMatch(site -> site.file().equals(VERIFIER)),
                "the scan did not find LoansReferenceScriptVerifier's new BlockfrostPublishedOutputLookup(");
    }

    /** Each pattern, and the comment blanker, on inputs whose answer is known. */
    @Test
    void thePatternsMatchEveryFormAndNoNearMiss() {
        assertEquals(1, matches("@Value(\"${blockfrost.key}\") String raw", PLACEHOLDER), "a literal is kept");
        assertEquals(1, matches("@Value(\"${blockfrost.key:}\") String raw", PLACEHOLDER));
        assertEquals(0, matches("/** @Value(\"${blockfrost.key}\") */ // ${blockfrost.key}\n", PLACEHOLDER),
                "a placeholder quoted in a comment is not a read");
        assertEquals(1, matches("String s = \"// not a comment\"; @Value(\"${blockfrost.key}\")", PLACEHOLDER),
                "a comment marker inside a literal does not start a comment");

        for (String code : List.of("new BFBackendService(url, key)",
                "new com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService (url, key)",
                "Function<String, BFBackendService> f = BFBackendService::new;")) {
            assertEquals(1, matches(code, NEW_BACKEND_SERVICE), code);
        }
        assertEquals(0, matches("new BFBackendServiceFactory(url); new MyBFBackendService(url);",
                NEW_BACKEND_SERVICE));
        assertEquals(1, matches("new BlockfrostPublishedOutputLookup(url, key)", NEW_LOOKUP));

        assertEquals(1, matches("environment.getProperty(\"blockfrost.key\")", GET_PROPERTY));
        assertEquals(1, matches("env.getProperty( \"blockfrost.url\", String.class)", GET_PROPERTY));
        for (String code : List.of("@ConfigurationProperties(\"blockfrost\")",
                "@ConfigurationProperties(prefix = \"blockfrost\")",
                "@ConfigurationProperties(value = {\"blockfrost.extra\"})",
                "@org.springframework.boot.context.properties.ConfigurationProperties(prefix=\"blockfrost\")")) {
            assertEquals(1, matches(code, CONFIGURATION_PROPERTIES), code);
        }
        assertEquals(0, matches("@ConfigurationProperties(prefix = \"blockfrostish\")", CONFIGURATION_PROPERTIES));
        assertEquals(1, matches("System.getenv(\"BLOCKFROST_KEY\")", ENVIRONMENT_NAME));
    }

    private static void onlyIn(List<Site> sites, String expected, String what, List<String> violations) {
        for (Site site : sites) {
            if (!site.file().equals(expected)) {
                violations.add(what + " at " + site + " — it belongs only in " + expected);
            }
        }
    }

    private static int matches(String code, Pattern pattern) {
        return scanSource("x.java", code, pattern).size();
    }

    private static List<Site> scan(Pattern pattern) {
        assertTrue(Files.isDirectory(MAIN), "not run from the project root: " + MAIN.toAbsolutePath());
        List<Site> sites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String relative = MAIN.relativize(file).toString().replace('\\', '/');
                sites.addAll(scanSource(relative, Files.readString(file, StandardCharsets.UTF_8), pattern));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return sites;
    }

    private static List<Site> scanSource(String file, String source, Pattern pattern) {
        String code = blankComments(source);
        Matcher matcher = pattern.matcher(code);
        List<Site> sites = new ArrayList<>();
        while (matcher.find()) {
            int line = 1 + (int) code.substring(0, matcher.start()).chars().filter(c -> c == '\n').count();
            sites.add(new Site(file, line));
        }
        return sites;
    }

    /**
     * Comments become spaces (newlines survive, so lines still count). String, char and text-block literals
     * are copied as they are — they are stepped over only so a {@code //} inside one is not read as a comment.
     */
    private static String blankComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (int k = i; k < end; k++) {
                    out.append(source.charAt(k) == '\n' ? '\n' : ' ');
                }
                i = end;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
                out.append(source, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                int end = Math.min(n, j + 1);
                out.append(source, i, end);
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    // ======================================================================================

    private static void assertNoFragment(String text, String where) {
        for (String fragment : FRAGMENTS) {
            assertFalse(text != null && text.contains(fragment), "the key leaked into " + where + ": " + text);
        }
    }

    /** Runs {@code body} with every log event captured at the root, then searches them all for the key. */
    private static void withRootLogs(Consumer<List<ILoggingEvent>> body) {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            body.accept(appender.list);
        } finally {
            root.detachAppender(appender);
        }
        for (ILoggingEvent event : appender.list) {
            assertNoFragment(event.getFormattedMessage(), "a log line");
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                assertNoFragment(t.getMessage(), "a logged exception");
            }
        }
    }
}
