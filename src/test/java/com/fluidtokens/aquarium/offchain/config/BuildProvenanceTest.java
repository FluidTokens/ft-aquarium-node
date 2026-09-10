package com.fluidtokens.aquarium.offchain.config;

import com.fluidtokens.aquarium.offchain.AcquariumOffchainApp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ResourceBanner;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
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
 * <h2>Why the last two tests do not touch the container</h2>
 * Everything above them supplies the exposure property by hand, which means the feature could be
 * switched off in the shipped {@code application.yaml} and they would all keep passing. So the file
 * itself is asserted, parsed by Spring's own loader — the {@link ApplicationYamlBindsTest} discipline
 * applied to a one-word key.
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
            assertTrue(commit.matches("[0-9a-f]{40}") || AcquariumOffchainApp.UNKNOWN.equals(commit),
                    "build.commit must be a full 40-char sha or exactly 'unknown', was: " + commit);
            assertTrue(Set.of("true", "false", AcquariumOffchainApp.UNKNOWN).contains(build.get("dirty")),
                    "build.dirty must be true, false or unknown -- an empty or absent value reads as "
                            + "'clean' to anyone skimming, which is the one thing it must never do. "
                            + "Was: " + build.get("dirty"));
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
     * {@code SpringApplicationBannerPrinter} uses for {@code banner.txt}, over the same default
     * properties {@code main()} supplies.
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
     * ⚠ And the banner with <b>no provenance at all</b> — the shape a jar built without
     * {@code bootBuildInfo} has. It must render {@code unknown}, not a literal {@code ${...}} and not
     * a hole. Note {@code ${application.version}} is deliberately absent from {@code banner.txt}: it
     * comes from the jar MANIFEST and renders EMPTY outside a boot jar, so asserting on it here would
     * be a false red waiting to happen.
     */
    @Test
    void theBannerDegradesToUnknownRatherThanToAHole() {
        String firstLine = renderBanner(Map.of());
        assertFalse(firstLine.contains("${"), "unresolved placeholder with no provenance: " + firstLine);
        assertTrue(firstLine.contains(AcquariumOffchainApp.UNKNOWN),
                "with no build info the banner must SAY unknown: " + firstLine);
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

        try (URLClassLoader withoutBuildInfo = new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader()) {
            @Override
            public URL getResource(String name) {
                return name.endsWith("build-info.properties") ? null : super.getResource(name);
            }

            @Override
            public InputStream getResourceAsStream(String name) {
                return name.endsWith("build-info.properties") ? null : super.getResourceAsStream(name);
            }
        }) {
            Class<?> app = withoutBuildInfo.loadClass(AcquariumOffchainApp.class.getName());
            assertNotSame(AcquariumOffchainApp.class, app,
                    "the isolated classloader delegated back to the app loader, so the file was "
                            + "never actually hidden and this test proves nothing");

            @SuppressWarnings("unchecked")
            Map<String, Object> provenance =
                    (Map<String, Object>) app.getMethod("buildProvenance").invoke(null);

            assertEquals(Set.of("aquarium.build.commit", "aquarium.build.commitShort",
                            "aquarium.build.dirty", "aquarium.build.time"),
                    new LinkedHashSet<>(provenance.keySet()),
                    "with no build info the property set must still be complete -- a missing key "
                            + "leaves the banner placeholder unresolved");
            provenance.forEach((key, value) -> assertEquals(AcquariumOffchainApp.UNKNOWN, value,
                    key + " must read 'unknown' when there is no build info, never blank and never "
                            + "'false'"));
        }
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
