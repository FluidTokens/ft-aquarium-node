package com.fluidtokens.aquarium.offchain.config;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import com.fluidtokens.aquarium.offchain.service.loans.CompoundTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertLiquidationRouter;
import com.fluidtokens.aquarium.offchain.service.loans.ConvertTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidatePayInAdvanceTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.LiquidateTransactionBuilder;
import com.fluidtokens.aquarium.offchain.service.loans.MinswapPoolResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>Does the CONTAINER actually build every bean the bot needs?</b>
 *
 * <h2>The other half of the boot problem, and the third one to bite</h2>
 * Two startup defects have now shipped past a green suite, and they are different failures:
 * <ul>
 *   <li><b>Configuration binding</b> — {@code loans.liquidation.mode} had no setter, the image failed
 *       to start, and no hand-written property fixture could have caught it. Closed by
 *       {@link ApplicationYamlBindsTest}.</li>
 *   <li><b>Bean wiring</b> — the convert beans were <em>never declared</em>, so
 *       {@code ConvertLiquidationRouter} was {@code null} in production while every unit test passed
 *       against a hand-constructed one. That was the 14-second preview crash.</li>
 * </ul>
 *
 * <p>⚠ <b>{@code YaciConfigWiringTest} does not cover this, deliberately and by its own account</b>: it
 * calls the {@code @Bean} methods <em>directly</em> and reflects on the object they return, which
 * proves each bean is wired <em>correctly</em> and says nothing about whether the container declares
 * it at all. <b>A bean that does not exist passes every assertion about how it is built.</b>
 *
 * <h2>⇒ So this asserts EXISTENCE, through the container, for the whole set</h2>
 * The lending beans are all {@code @ConditionalOnProperty(loans.enabled)}. The condition is what makes
 * their absence legitimate on a node that wants none of it — and it is also what made the absence
 * invisible. Here the flag is on, so every one of them must appear.
 *
 * <h2>Proof-of-harness, run rather than assumed</h2>
 * The {@code @Bean} annotation was removed from {@code convertLiquidationRouter} — reproducing the
 * exact 14-second crash — and this class went <b>red on two assertions naming it</b>: the existence
 * check, and the count (8 declared vs 9 listed). Restored, 11/11 green. <b>Without that run this
 * would be a green decoration</b>, which is what {@link ApplicationYamlBindsTest} turned out to be on
 * its first draft.
 *
 * <p>⚑ <b>Writing it also mapped a dependency graph nobody had written down.</b> Standing the context
 * up failed twice on collaborators {@code YaciConfig} takes from elsewhere — {@code CardanoConverters}
 * and {@code ConvertEconomics} — neither of which any test had ever had to supply. That is the same
 * class of knowledge as the beans themselves: <b>held only by the running container until something
 * forces it into the open.</b>
 */
class ContainerWiringTest {

    /**
     * ⛔ Every bean {@code YaciConfig} must produce when lending is enabled. <b>Adding a
     * {@code @Bean} there and not here is caught</b> by {@link #theLendingBeanSetIsExactlyThisSize()}
     * — otherwise this list would only ever notice removals, and a bean nobody listed is exactly how
     * the convert routing went missing.
     */
    static Stream<Class<?>> requiredBeans() {
        return Stream.of(
                QuickTxBuilder.class,
                UtxoSupplier.class,
                ProtocolParamsSupplier.class,
                LiquidateTransactionBuilder.class,
                CompoundTransactionBuilder.class,
                MinswapPoolResolver.class,
                ConvertTransactionBuilder.class,
                ConvertLiquidationRouter.class,
                LiquidatePayInAdvanceTransactionBuilder.class);
    }

    /**
     * The collaborators {@code YaciConfig} takes from elsewhere in the context. Supplied as real
     * objects, not mocks: a mock would satisfy the wiring while telling us nothing about whether the
     * real types are constructible together.
     */
    private static ApplicationContextRunner runner() {
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");

        return new ApplicationContextRunner()
                .withUserConfiguration(YaciConfig.class)
                .withPropertyValues("loans.enabled=true")
                .withBean(BFBackendService.class,
                        () -> new BFBackendService("https://cardano-preview.blockfrost.io/api/v0/", "test"))
                .withBean(AppConfig.Network.class, () -> network)
                .withBean(LoansContractRegistry.class, ContainerWiringTest::previewRegistry)
                .withBean(AppConfig.LoansConfiguration.class, AppConfig.LoansConfiguration::new)
                .withBean(com.fluidtokens.aquarium.offchain.service.loans.ConvertEconomics.class,
                        () -> new com.fluidtokens.aquarium.offchain.service.loans.ConvertEconomics(
                                new AppConfig.ConvertConfiguration(), network))
                .withBean(org.cardanofoundation.conversions.CardanoConverters.class,
                        () -> org.cardanofoundation.conversions.ClasspathConversionsFactory
                                .createConverters(org.cardanofoundation.conversions.domain.NetworkType.PREVIEW));
    }

    /** The fourth preview deployment, the one application.yaml ships. */
    private static LoansContractRegistry previewRegistry() {
        return new LoansContractRegistry(
                "d46f626fc11750409cf44f3d202f48d1b5df41ad35d62a7364b8e22e",
                "a7d4b762c5a6197ab3b169c2ff1945fdcd4c21cc5f4c180e75441a13",
                "706172616d6574657273",
                "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa");
    }

    /**
     * ⛔ THE ASSERTION. Each bean must exist in the running container — not be constructible in
     * principle, not be correct once constructed. <b>Exist.</b>
     */
    @ParameterizedTest(name = "{0} is wired when lending is enabled")
    @MethodSource("requiredBeans")
    void everyLendingBeanIsDeclaredByTheContainer(Class<?> type) {
        runner().run(ctx -> {
            assertTrue(ctx.getStartupFailure() == null,
                    "the container did not start at all: " + ctx.getStartupFailure());
            assertEquals(1, ctx.getBeanNamesForType(type).length,
                    type.getSimpleName() + " is NOT wired. A bean that does not exist passes every "
                            + "test about how it is built, and the caller sees null — which is how "
                            + "the convert routing was absent in production while the suite was "
                            + "green, and how the preview pod crashed in 14 seconds.");
        });
    }

    /**
     * ⚠ The count, so a bean ADDED to {@code YaciConfig} and not listed above is noticed. A list that
     * only detects removals decays into a stale subset, and the thing it stops noticing is exactly the
     * newest bean — the one least likely to be covered elsewhere.
     */
    @Test
    void theLendingBeanSetIsExactlyThisSize() {
        runner().run(ctx -> {
            List<Class<?>> declared = requiredBeans().toList();
            long beansFromYaciConfig = Stream.of(ctx.getBeanDefinitionNames())
                    .filter(n -> ctx.getBeanFactory().getBeanDefinition(n).getFactoryBeanName() != null)
                    .count();
            assertEquals(declared.size(), beansFromYaciConfig,
                    "YaciConfig declares " + beansFromYaciConfig + " beans and this test lists "
                            + declared.size() + ". If a bean was added, add it to requiredBeans() — an "
                            + "unlisted one is covered by nothing, and that is the shape the convert "
                            + "routing had.");
        });
    }

    /**
     * ⛔ <b>THE CONTROL, INVERTED — and the inversion is the point.</b>
     *
     * <p>Until 2026-09-04 this asserted the opposite: with {@code loans.enabled} unset the same beans
     * had to be ABSENT, proving the switch that let an operator run the node with no lending at all.
     * <b>That switch was removed</b> — it was the cause of a permanent indexing gap, because
     * {@code TankUtxoStorage} fixes its credential set once at startup and a flag flipped later never
     * re-reads the blocks that passed meanwhile.
     *
     * <p>⇒ <b>So the beans must now be present with NOTHING configured at all</b> — no
     * {@code loans.enabled}, no config policy ids, no coordinates. That is the bare-install shape: a
     * public chart ships every contract coordinate empty, and with no flag left to switch off, this
     * context IS what such an install gets. If it cannot be built, every bare install crash-loops.
     *
     * <p>⚠ What makes that safe is not a bean being absent but
     * {@link LoansContractRegistry#isConfigured()} being false: the registry derives nothing usable
     * from blank coordinates, so it hands the indexer an EMPTY credential list rather than the seven
     * real-looking hashes a blank policy id otherwise produces. Asserted here beside the wiring,
     * because the two claims are only meaningful together — beans that exist, indexing that does not.
     */
    @Test
    void withNoCoordinatesConfiguredTheBeansStillWireAndNothingIsIndexed() {
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        new ApplicationContextRunner()
                .withUserConfiguration(YaciConfig.class)
                .withBean(BFBackendService.class,
                        () -> new BFBackendService("https://cardano-preview.blockfrost.io/api/v0/", "test"))
                .withBean(AppConfig.Network.class, () -> network)
                // ⛔ THE BARE-INSTALL REGISTRY: built through the same path Spring uses, from a
                // LoansConfiguration whose coordinates are all empty — which is exactly what a public
                // chart ships by default. Not a stub; the real class, given nothing.
                .withBean(LoansContractRegistry.class,
                        () -> new LoansContractRegistry(new AppConfig.LoansConfiguration()))
                .withBean(AppConfig.LoansConfiguration.class, AppConfig.LoansConfiguration::new)
                .withBean(com.fluidtokens.aquarium.offchain.service.loans.ConvertEconomics.class,
                        () -> new com.fluidtokens.aquarium.offchain.service.loans.ConvertEconomics(
                                new AppConfig.ConvertConfiguration(), network))
                .withBean(org.cardanofoundation.conversions.CardanoConverters.class,
                        () -> org.cardanofoundation.conversions.ClasspathConversionsFactory
                                .createConverters(org.cardanofoundation.conversions.domain.NetworkType.PREVIEW))
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(),
                            () -> "a bare install — no flag, no coordinates — must START. There is no "
                                    + "longer any switch that could turn this off: "
                                    + ctx.getStartupFailure());
                    requiredBeans().forEach(type ->
                            assertEquals(1, ctx.getBeanNamesForType(type).length,
                                    type.getSimpleName() + " must be wired unconditionally now that "
                                            + "loans.enabled is gone"));
                });
    }

    /**
     * ⛔ <b>And the claim the one above rests on, isolated so it cannot pass for the wrong reason:
     * blank coordinates index NOTHING.</b>
     *
     * <p>Measured 2026-09-04, and it is the reason this guard exists at all: a registry built from
     * two blank policy ids <b>does not fail</b>. {@code b("")} is a legal empty bytestring, every
     * {@code applyParamToScript} succeeds, and the object comes up holding a full set of plausible
     * 56-hex hashes derived from nothing — plus the blank policy id itself, which
     * {@code AddressProvider} turns into the perfectly valid {@code addr1wy22xa6y}.
     *
     * <p>⚠ Handing those seven credentials to the write-time filter would give a bare install the
     * exact pathology of findings §12/§39: boots clean, verifies nothing, indexes at credentials no
     * UTxO will ever carry, and reports an empty world that looks identical to a quiet market.
     * <b>"Not configured" has to be a visible state, not a silently wrong one.</b>
     */
    @Test
    void aRegistryWithBlankCoordinatesDerivesNothingItWillIndex() {
        var blank = new LoansContractRegistry("", "", "706172616d6574657273", "");
        assertFalse(blank.isConfigured(),
                "two blank policy ids are NOT a configured deployment");
        assertTrue(blank.indexedPaymentCredentials().isEmpty(),
                "an unconfigured registry must offer the indexer NO credentials; it derived "
                        + blank.indexedPaymentCredentials().size() + " of them, which would be "
                        + "indexed forever and match nothing");

        var configured = new LoansContractRegistry(
                "db2c498e1b93da91e6a79f58526a1e66591d97ace3f8e43d2619b416",
                "a56b0ac2654663f395601601a7825649e5488905648747e912d870e4",
                "706172616d6574657273", "");
        assertTrue(configured.isConfigured(), "the real mainnet coordinates ARE configured");
        assertFalse(configured.indexedPaymentCredentials().isEmpty(),
                "and a configured registry must still produce credentials — otherwise the guard "
                        + "above would pass by disabling indexing everywhere");
    }
}
