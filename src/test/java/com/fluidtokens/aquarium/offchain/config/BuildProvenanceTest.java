package com.fluidtokens.aquarium.offchain.config;

import com.fluidtokens.aquarium.offchain.AcquariumOffchainApp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ResourceBanner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.info.InfoContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.info.InfoEndpointAutoConfiguration;
import org.springframework.boot.actuate.info.InfoEndpoint;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>Can a running node say what it was built from?</b>
 *
 * <h2>The gap this closes</h2>
 * The CI image tag is {@code date +'%Y.%m.%d'}. <b>Two pushes on the same day produce the same tag
 * over different code</b>, and until now nothing in the running process could tell them apart: an
 * operator holding a misbehaving container had no way to name the commit inside it. The tag is still
 * a date — closing that is a separate ticket — but the process itself now answers, twice over: on
 * {@code /actuator/info}, and on the first line of the boot banner, so {@code docker logs} read from
 * the top identifies the build with no HTTP call at all.
 *
 * <h2>⚠ The assertion that matters is about a WORD, not a feature</h2>
 * {@code build.dirty} has <b>three</b> states — {@code true}, {@code false}, {@code unknown} — and
 * the third one is the reason this class exists. When git cannot be reached (a source tarball, a
 * Docker context with no {@code .git}, git not installed) the naive implementation takes the empty
 * fallback, finds it empty, and records {@code dirty=false}. That is not a missing feature; it is
 * <b>a false statement on an unauthenticated endpoint</b>, and it is false in the direction that
 * makes an untrustworthy image look trustworthy. Both branches were run for this slice — see the
 * build.gradle comment on {@code dirty} — and the assertion here is that {@code unknown} is a value
 * the pipeline can actually carry end to end.
 *
 * <h2>Why some tests here do not touch the container at all</h2>
 * Everything that hands the exposure property to an {@link ApplicationContextRunner} by hand would
 * keep passing with the feature switched off in the shipped {@code application.yaml}; and everything
 * that renders the banner over a map it built itself would keep passing with nothing in production
 * installing that map. So both wirings are asserted directly — the shipped file parsed by Spring's
 * own loader (the {@link ApplicationYamlBindsTest} discipline applied to a one-word key), and the
 * startup wiring's own default properties.
 */
class BuildProvenanceTest {

    /** Exactly what {@code src/main/resources/application.yaml} ships. Asserted below, not assumed. */
    private static final String PRODUCTION_EXPOSURE = "health,prometheus,info";

    /**
     * The actuator slice of the container. {@code InfoEndpoint} is
     * {@code @ConditionalOnAvailableEndpoint}, so the exposure property is not decoration here: with
     * the production value absent, <b>the bean does not exist</b> and every assertion about its
     * content would be vacuous.
     */
    private static ApplicationContextRunner actuator(String exposure) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ProjectInfoAutoConfiguration.class,
                        InfoContributorAutoConfiguration.class,
                        EndpointAutoConfiguration.class,
                        InfoEndpointAutoConfiguration.class))
                .withPropertyValues("management.endpoints.web.exposure.include=" + exposure);
    }

    /**
     * (a) The provenance actually reached the jar. {@code bootBuildInfo} is in the task graph of both
     * {@code test} and {@code bootJar}, so a cold clone has this file with no {@code dependsOn}
     * wiring — if it is ever missing, this is where that is noticed.
     *
     * <p>⚠ The commit is asserted as <b>40 hex characters OR exactly {@code unknown}</b>, and not as
     * "the sha of HEAD": a test that shells out to git to compute its own expectation proves only
     * that git agrees with git, and goes red on a checkout that has no {@code .git} — a false red on
     * a legitimate build.
     */
    @Test
    void theJarKnowsWhichCommitItWasBuiltFrom() {
        actuator(PRODUCTION_EXPOSURE).run(context -> {
            assertTrue(context.getBeanNamesForType(BuildProperties.class).length == 1,
                    "no BuildProperties bean: META-INF/build-info.properties did not reach the "
                            + "classpath, so the running node cannot name its own commit");
            BuildProperties build = context.getBean(BuildProperties.class);
            String commit = build.get("commit");
            assertNotNull(commit, "build.commit was not recorded at all");
            // The literal, not AcquariumOffchainApp.UNKNOWN: this value is written by Gradle, and
            // coupling the assertion to the Java constant would let the two drift apart unnoticed.
            assertTrue(commit.matches("[0-9a-f]{40}") || "unknown".equals(commit),
                    "build.commit must be a full 40-char sha or exactly 'unknown', was: " + commit);
            String dirty = build.get("dirty");
            assertTrue(Set.of("true", "false", "unknown").contains(dirty),
                    "build.dirty must be true, false or unknown -- an empty or absent value reads as "
                            + "'clean' to anyone skimming, which is the one thing it must never do. "
                            + "Was: " + dirty);

            // ⛔ THE CORRELATION, not the set. Set membership ADMITS the defect: the naive two-state
            // implementation writes commit=unknown beside dirty=false, and 'false' is in the set, so
            // the assertion above passes on exactly the value its own message forbids. The three
            // fields answer ONE question -- is git usable and is it this repository -- so the
            // property with content is that they agree.
            assertEquals("unknown".equals(commit), "unknown".equals(dirty),
                    "build.commit and build.dirty disagree about whether git was readable: commit="
                            + commit + ", dirty=" + dirty + ". 'commit=unknown, dirty=false' is a "
                            + "build that could not read its own tree asserting a clean one, on an "
                            + "unauthenticated endpoint an operator uses to decide whether an image "
                            + "is trustworthy.");
            assertEquals("unknown".equals(commit), "unknown".equals(build.get("commitShort")),
                    "build.commit and build.commitShort disagree about whether git was readable: "
                            + commit + " / " + build.get("commitShort"));
        });
    }

    /** (b) With the production exposure, the endpoint exists and serves the build section. */
    @Test
    void theInfoEndpointServesTheBuildSection() {
        actuator(PRODUCTION_EXPOSURE).run(context -> {
            assertEquals(1, context.getBeanNamesForType(InfoEndpoint.class).length,
                    "InfoEndpoint is @ConditionalOnAvailableEndpoint and did not appear even with "
                            + "'info' exposed -- /actuator/info would 404");
            Map<String, Object> info = context.getBean(InfoEndpoint.class).info();
            assertTrue(info.containsKey("build"), "no build section on /actuator/info: " + info.keySet());
            @SuppressWarnings("unchecked")
            Map<String, Object> build = (Map<String, Object>) info.get("build");
            assertTrue(build.containsKey("commit"),
                    "the build section carries no commit, which is the only field an operator "
                            + "holding two same-day image tags actually needs: " + build.keySet());
            assertTrue(build.containsKey("dirty"), "the build section carries no dirty flag");
        });
    }

    /**
     * ⛔ And <b>nothing but</b> the build section. This endpoint is unauthenticated on an
     * operator-run node that holds a funded wallet mnemonic, a Blockfrost key and a database
     * password; {@code management.info.env.*} would put configuration on it. The mirror-image
     * assertion against the shipped file is {@link #theShippedYamlEnablesNoOtherInfoContributor()} —
     * this one proves the default wiring is closed, that one proves the file did not open it.
     */
    @Test
    void theInfoEndpointServesNothingBesidesBuild() {
        actuator(PRODUCTION_EXPOSURE).run(context -> {
            Map<String, Object> info = context.getBean(InfoEndpoint.class).info();
            assertEquals(Set.of("build"), info.keySet(),
                    "/actuator/info must serve the build section and nothing else; anything extra "
                            + "here is a value an unauthenticated caller can now read");
        });
    }

    /** The control: with 'info' not exposed the bean is genuinely absent, so the test above is not vacuous. */
    @Test
    void withoutTheExposureThereIsNoEndpointAtAll() {
        actuator("health,prometheus").run(context ->
                assertEquals(0, context.getBeanNamesForType(InfoEndpoint.class).length,
                        "InfoEndpoint appeared without being exposed -- then the exposure property "
                                + "proves nothing and the tests above pass whatever the yaml says"));
    }

    /**
     * (c) The banner. Rendered through {@link ResourceBanner}, the same class
     * {@code SpringApplicationBannerPrinter} uses for {@code banner.txt}, over the same map
     * {@code main()} supplies.
     *
     * <p>⚠ <b>"The same map" is not "the map main() supplies"</b> — this test builds its own
     * {@link MapPropertySource} and would pass unchanged if nothing in production ever installed one.
     * {@link #theStartupWiringInstallsTheProvenanceAsDefaultProperties()} is the half that closes
     * that; neither is sufficient alone.
     */
    @Test
    void theBannerFirstLineCarriesTheResolvedCommitAndBuildTime() {
        Map<String, Object> provenance = AcquariumOffchainApp.buildProvenance();
        String firstLine = renderBanner(provenance);

        assertFalse(firstLine.contains("${"),
                "an unresolved placeholder survived to the banner: " + firstLine);
        assertTrue(firstLine.contains(String.valueOf(provenance.get("aquarium.build.commitShort"))),
                "the banner's first line does not carry the short commit: " + firstLine);
        assertTrue(firstLine.contains(String.valueOf(provenance.get("aquarium.build.time"))),
                "the banner's first line does not carry the build time: " + firstLine);
        assertTrue(firstLine.contains(String.valueOf(provenance.get("aquarium.build.dirty"))),
                "the banner's first line does not carry the dirty flag: " + firstLine);
    }

    /**
     * ⚠ The banner with the provenance keys <b>entirely absent</b> from the environment. This is
     * {@code banner.txt}'s own {@code ${...:unknown}} defaults firing, and nothing else — it does not
     * exercise {@link AcquariumOffchainApp#UNKNOWN} at all. Note {@code ${application.version}} is
     * deliberately absent from {@code banner.txt}: it comes from the jar MANIFEST and renders EMPTY
     * outside a boot jar, so asserting on it here would be a false red waiting to happen.
     *
     * <p>⛔ The assertion is on the <b>literal word</b>, never on the constant. {@code contains(UNKNOWN)}
     * says "the banner contains whatever that constant happens to be", which is trivially true the
     * moment the constant becomes {@code ""} — the exact regression the constant's javadoc forbids.
     * An assertion that reads its expectation out of the code under test asserts nothing.
     */
    @Test
    void theBannerDegradesToUnknownRatherThanToAHole() {
        String firstLine = renderBanner(Map.of());
        assertFalse(firstLine.contains("${"), "unresolved placeholder with no provenance: " + firstLine);
        assertTrue(firstLine.contains("unknown"),
                "with no build info the banner must SAY unknown: " + firstLine);
    }

    /**
     * ⛔ <b>And the production shape of the same failure, which the test above cannot reach.</b>
     *
     * <p>A jar built without {@code bootBuildInfo} does not leave the keys absent — {@code main()}
     * installs {@link AcquariumOffchainApp#buildProvenance()} as default properties, so the keys are
     * <b>present with whatever that method fell back to</b>. {@code banner.txt}'s {@code ${...:unknown}}
     * defaults never fire on that path; they only cover a key that is missing. So if the fallback ever
     * became the empty string, the banner an operator actually reads would render
     * {@code commit  (dirty=) :: built} — a line with holes in it, reading as "fine" rather than as
     * "this build cannot say" — while every other test in this class stayed green.
     *
     * <p>Rendered over the map produced under the hidden-build-info classloader, and asserted against
     * the literal word.
     */
    @Test
    void theBannerSaysUnknownWhenTheJarCarriesNoBuildInfo() throws Exception {
        try (URLClassLoader isolated = hidingBuildInfo()) {
            Map<String, Object> provenance = provenanceFrom(isolated);
            String firstLine = renderBanner(provenance);

            assertFalse(firstLine.contains("${"),
                    "unresolved placeholder with no build info: " + firstLine);
            assertTrue(firstLine.contains("unknown"),
                    "with no build info the banner must SAY the word 'unknown'; a present-but-empty "
                            + "fallback renders a hole that reads as 'fine': " + firstLine);
            provenance.forEach((key, value) -> assertEquals("unknown", value,
                    key + " must be the literal word 'unknown' with no build info -- an empty "
                            + "fallback is present-but-blank, which banner.txt's ${...:unknown} "
                            + "default cannot rescue because the key is not missing"));
        }
    }

    /**
     * ⛔ <b>THE OTHER DISCONNECTED TEST, and it guards the banner half of this feature.</b>
     *
     * <p>Every banner test above renders over a map it built itself. Delete the
     * {@code setDefaultProperties} call from the startup wiring and all of them stay green, while
     * every banner an operator ever reads says {@code commit unknown (dirty=unknown) :: built unknown}
     * — the half of this slice that exists so {@code docker logs} identifies the build, silently off,
     * with CI reporting success. {@link #theShippedYamlExposesInfo()} does exactly this job for the
     * {@code /actuator/info} half; this is its counterpart, and its absence was the asymmetry.
     *
     * <p>Reached reflectively because {@code configuredApplication()} is package-private in
     * {@code com.fluidtokens.aquarium.offchain} and this class is not, and because
     * {@code defaultProperties} has no getter on {@link SpringApplication}. If a future Spring version
     * renames that field the test goes red loudly rather than passing quietly, which is the correct
     * direction for a fragile read.
     */
    @Test
    void theStartupWiringInstallsTheProvenanceAsDefaultProperties() throws Exception {
        Method seam = AcquariumOffchainApp.class.getDeclaredMethod("configuredApplication");
        seam.setAccessible(true);
        SpringApplication application = (SpringApplication) seam.invoke(null);

        Object defaults = ReflectionTestUtils.getField(application, "defaultProperties");
        assertNotNull(defaults,
                "the startup wiring installed NO default properties at all, so banner.txt's "
                        + "${aquarium.build.*} placeholders resolve to nothing in production and "
                        + "every banner reads 'commit unknown (dirty=unknown) :: built unknown'");
        assertInstanceOf(Map.class, defaults, "SpringApplication.defaultProperties is not a Map: " + defaults);
        @SuppressWarnings("unchecked")
        Map<String, Object> installed = (Map<String, Object>) defaults;

        Map<String, Object> expected = AcquariumOffchainApp.buildProvenance();
        assertEquals(4, expected.size(), "buildProvenance() no longer offers four fields: " + expected.keySet());
        expected.forEach((key, value) -> {
            assertTrue(key.startsWith("aquarium.build."), "unexpected provenance key: " + key);
            assertEquals(value, installed.get(key),
                    "the default properties the startup wiring installs do not carry " + key
                            + ", so banner.txt's ${" + key + "} resolves to nothing in production. "
                            + "Installed: " + installed.keySet());
        });
    }

    private static String renderBanner(Map<String, Object> provenance) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("provenance", provenance));
        ByteArrayOutputStream rendered = new ByteArrayOutputStream();
        new ResourceBanner(new ClassPathResource("banner.txt")).printBanner(
                environment, AcquariumOffchainApp.class,
                new PrintStream(rendered, true, StandardCharsets.UTF_8));
        return rendered.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElseThrow(() -> new AssertionError("banner.txt rendered nothing"));
    }

    /**
     * ⛔ <b>The node must start with no {@code build-info.properties}.</b> Not "should" — an operator
     * building from a source export, or any jar assembled without {@code bootBuildInfo}, gets exactly
     * this classpath. Making the process refuse to boot because it cannot describe itself would turn
     * a cosmetic gap into an outage.
     *
     * <p>The file is hidden with a classloader rather than deleted, so the real one survives for
     * every other test in this run.
     */
    @Test
    void aMissingBuildInfoFileIsNotAStartupCondition() throws Exception {
        try (URLClassLoader isolated = hidingBuildInfo()) {
            Map<String, Object> provenance = provenanceFrom(isolated);

            assertEquals(Set.of("aquarium.build.commit", "aquarium.build.commitShort",
                            "aquarium.build.dirty", "aquarium.build.time"),
                    new LinkedHashSet<>(provenance.keySet()),
                    "with no build info the property set must still be complete -- a missing key "
                            + "leaves the banner placeholder unresolved");
            // The literal word, not AcquariumOffchainApp.UNKNOWN: comparing the fallback against the
            // constant that defines it is a tautology that survives the constant becoming "".
            provenance.forEach((key, value) -> assertEquals("unknown", value,
                    key + " must read 'unknown' when there is no build info, never blank and never "
                            + "'false'"));
        }
    }

    /**
     * A classloader over this JVM's own classpath that answers {@code null} for
     * {@code build-info.properties}. The file is hidden rather than deleted, so the real one survives
     * for every other test in this run.
     */
    private static URLClassLoader hidingBuildInfo() {
        URL[] classpath = Arrays.stream(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
                .map(entry -> Paths.get(entry).toUri())
                .map(uri -> {
                    try {
                        return uri.toURL();
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toArray(URL[]::new);

        return new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader()) {
            @Override
            public URL getResource(String name) {
                return name.endsWith("build-info.properties") ? null : super.getResource(name);
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                return name.endsWith("build-info.properties") ? null : super.getResourceAsStream(name);
            }
        };
    }

    /** {@code buildProvenance()} as a jar with no build info would compute it. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> provenanceFrom(URLClassLoader isolated) throws Exception {
        Class<?> app = isolated.loadClass(AcquariumOffchainApp.class.getName());
        assertNotSame(AcquariumOffchainApp.class, app,
                "the isolated classloader delegated back to the app loader, so the file was "
                        + "never actually hidden and this test proves nothing");
        return (Map<String, Object>) app.getMethod("buildProvenance").invoke(null);
    }

    /**
     * (d) ⛔ <b>THE DISCONNECTED TEST.</b> Everything above hands the exposure list to the container
     * by hand. Delete {@code info} from {@code application.yaml} and every one of them still passes
     * while {@code /actuator/info} 404s in production. So: the shipped file, parsed by Spring's own
     * loader.
     */
    @Test
    void theShippedYamlExposesInfo() throws IOException {
        assertEquals(PRODUCTION_EXPOSURE, shippedProperty("management.endpoints.web.exposure.include"),
                "the shipped application.yaml no longer exposes exactly " + PRODUCTION_EXPOSURE
                        + ". Dropping 'info' 404s the endpoint with every test in this class still "
                        + "green; dropping 'prometheus' silently blinds operator monitoring.");
    }

    /**
     * ⛔ The other half of the unauthenticated-endpoint invariant: the file must not switch on the
     * contributors that would publish configuration, the JVM or the host. {@code management.info.env}
     * in particular would expose environment-derived properties on a node that holds a wallet
     * mnemonic and a Blockfrost key.
     */
    @Test
    void theShippedYamlEnablesNoOtherInfoContributor() throws IOException {
        for (PropertySource<?> document : shippedYaml()) {
            if (document instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    assertFalse(name.startsWith("management.info."),
                            "application.yaml sets '" + name + "'. /actuator/info is unauthenticated "
                                    + "on an operator-run node: it serves the build section and "
                                    + "nothing else, deliberately.");
                }
            }
        }
    }

    /** The shipped file, every document, parsed by Spring's own YAML loader. */
    private static List<PropertySource<?>> shippedYaml() throws IOException {
        return new YamlPropertySourceLoader().load("application.yaml",
                new ClassPathResource("application.yaml"));
    }

    private static Object shippedProperty(String key) throws IOException {
        for (PropertySource<?> document : shippedYaml()) {
            if (document.containsProperty(key)) {
                return document.getProperty(key);
            }
        }
        throw new AssertionError("no document in application.yaml defines " + key);
    }
}
