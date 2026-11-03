package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.UtxoService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.LendingConfigGate;
import com.fluidtokens.aquarium.offchain.service.LoansConfigVerifier;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>FAB-194 D5 / D5b: the boot says where a {@code CONVERT_BEFORE_ANTICIPATE} market pays in advance — proven
 * through the CONTAINER, never by calling {@code verify()} or {@code announceAndGuard()} by hand.</b>
 *
 * <p>Both lines are {@code @PostConstruct} output fed by a setter- or constructor-injected
 * {@link AppConfig.LiquidationConfiguration} whose market list is bound by {@link AppConfig.MarketProperties}. A test
 * that constructed the verifier and called {@code verify()} would prove the METHOD prints, not that the container
 * ever hands it the markets (CLAUDE.md, "Promoting test code to src/main requires a production-wiring test"). So the
 * verifier is a bean here (an instance supplier, as {@code ExecutorContextResolutionTest} registers it) beside a
 * container-built {@code LiquidationConfiguration}, and the markets bind from {@code loans.liquidation.markets[i].*}
 * — with {@code @EnableConfigurationProperties}, without which the binder never runs and an empty list passes every
 * "no line" assertion (officina {@code spring-boot-config-binding-traps} §2). Each test that expects silence also
 * asserts the list bound, so silence cannot be read off an empty world.
 *
 * <p>Market 0 is the {@code CONVERT_BEFORE_ANTICIPATE} token market; market 1 is an {@code ANTICIPATE} market, which
 * must never get the line. Neither carries a mode of its own, so each line's mode is the node's {@code SHADOW} — read
 * through {@code effectiveMode}, not the market's own (null) field.
 */
class ConvertBeforeAnticipateBootLinesTest {

    // The recorded mainnet deployment, as ExecutorContextResolutionTest serves it.
    private static final String CONFIG = "235b32040fe1177c03b1d34febc470440c6eaaa2228a9c1b0e375200";
    private static final String LM_CONFIG = "fb6ae2027358b4a0b62710eb95102d87fa13f66ecf55d8943699c492";
    private static final String ASSET = "706172616d6574657273";
    private static final String SMART = "fca77bcce1e5e73c97a0bfa8c90f7cd2faff6fd6ed5b6fec1c04eefa";

    private static final String CBA_UNIT = "ee".repeat(28) + "4e49474854";
    private static final String ANTICIPATE_UNIT = "cc".repeat(28) + "5553444d";

    private static final String[] MARKETS = {
            "loans.liquidation.mode=shadow",
            "loans.liquidation.profit-margin-lovelace=5000000",
            "loans.liquidation.markets[0].unit=" + CBA_UNIT,
            "loans.liquidation.markets[0].action=CONVERT_BEFORE_ANTICIPATE",
            "loans.liquidation.markets[0].cap=1000000000",
            "loans.liquidation.markets[1].unit=" + ANTICIPATE_UNIT,
            "loans.liquidation.markets[1].action=ANTICIPATE",
            "loans.liquidation.markets[1].cap=2000000"};

    private static final String VERIFIER_LINE = "market " + CBA_UNIT + " is action: CONVERT_BEFORE_ANTICIPATE on a node "
            + "that cannot convert: a loan in it whose lender allows conversion is paid in advance (cap 1000000000 per "
            + "loan, in the token's smallest unit) when the convert route finds no Minswap pool or one too thin to repay "
            + "the debt — with loans.minswap.pool-address blank, that is every such loan; any other convert refusal is "
            + "refused by name, never paid in advance; the market's mode (SHADOW) still decides whether anything is "
            + "built or submitted";

    private static final String ECONOMICS_LINE = "INIT - market " + CBA_UNIT + " is action: CONVERT_BEFORE_ANTICIPATE "
            + "and loans.liquidation.convert.enabled is false: a loan in it whose lender allows conversion goes to "
            + "pay-in-advance (cap 1000000000 per loan, in the token's smallest unit), decided before any build, and is "
            + "never converted; the market's mode (SHADOW) still decides whether anything is built or submitted";

    private static final String NOT_SET = "CONVERT UNAVAILABLE: loans.minswap.* is not set";
    private static final String MISMATCH = "⛔ CONVERT UNAVAILABLE: lm_liquidate_and_convert_action derives ";

    /** The markets, bound by the real binder into a container-built LiquidationConfiguration. */
    @Configuration
    @EnableConfigurationProperties(AppConfig.MarketProperties.class)
    @Import(AppConfig.LiquidationConfiguration.class)
    static class MarketsCtx {
    }

    /** As ConvertEconomicsPostConstructBootTest wires ConvertEconomics, plus the market binder. */
    @Configuration
    @EnableConfigurationProperties(AppConfig.MarketProperties.class)
    @Import({AppConfig.ConvertConfiguration.class, AppConfig.LiquidationConfiguration.class,
            AppConfig.Network.class, ConvertEconomics.class})
    static class EconomicsCtx {
    }

    // ---- (a) / (b) / (c): LoansConfigVerifier ----------------------------------------------------------------

    /** (a) Minswap parameters null: the convert hash cannot be derived. */
    @Test
    void withNoMinswapCoordinatesTheVerifierWarnsOncePerConvertBeforeAnticipateMarket() throws Exception {
        verifierRunner(null, null, null).withUserConfiguration(MarketsCtx.class).withPropertyValues(MARKETS)
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "the context must start: " + context.getStartupFailure());
                    assertBound(context.getBean(AppConfig.LiquidationConfiguration.class));
                });
        assertOnePerMarketWarnAfter(NOT_SET);
    }

    /** (b) Minswap parameters that derive a hash the served LMConfigDatum does not publish. */
    @Test
    void withAnotherNetworksMinswapCoordinatesTheVerifierWarnsOncePerConvertBeforeAnticipateMarket() throws Exception {
        verifierRunner("11".repeat(28), "22".repeat(28), "33".repeat(28)).withUserConfiguration(MarketsCtx.class)
                .withPropertyValues(MARKETS)
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "the context must start: " + context.getStartupFailure());
                    assertBound(context.getBean(AppConfig.LiquidationConfiguration.class));
                });
        assertOnePerMarketWarnAfter(MISMATCH);
    }

    /** (c) No LiquidationConfiguration bean at all: the verifier starts, says CONVERT UNAVAILABLE, and no more. */
    @Test
    void aVerifierWithNoLiquidationConfigurationStartsAndPrintsNoPerMarketLine() throws Exception {
        verifierRunner(null, null, null).withPropertyValues(MARKETS)
                .run(context -> {
                    assertNull(context.getStartupFailure(), () -> "the context must start: " + context.getStartupFailure());
                    assertEquals(0, context.getBeanNamesForType(AppConfig.LiquidationConfiguration.class).length,
                            "this container must hold no LiquidationConfiguration");
                });
        List<String> warns = messages(verifierLogs, Level.WARN);
        assertTrue(warns.stream().anyMatch(w -> w.startsWith(NOT_SET)),
                "the CONVERT UNAVAILABLE branch must have run, or silence proves nothing: " + warns);
        assertEquals(List.of(), perMarketLines(warns), "no configuration, no per-market line");
    }

    // ---- (d): ConvertEconomics -------------------------------------------------------------------------------

    @Test
    void withConvertDisabledConvertEconomicsAnnouncesEachConvertBeforeAnticipateMarketOnce() {
        List<String> infos = runEconomics("false");
        assertTrue(infos.stream().anyMatch(i -> i.startsWith("CONVERT ECONOMICS enabled=false")),
                "the container must have run announceAndGuard: " + infos);
        List<String> lines = infos.stream().filter(i -> i.startsWith("INIT - market ")).toList();
        assertEquals(List.of(ECONOMICS_LINE), lines, "exactly one line, for market 0, exact text");
    }

    @Test
    void withConvertEnabledConvertEconomicsAnnouncesNoMarket() {
        List<String> infos = runEconomics("true");
        assertTrue(infos.stream().anyMatch(i -> i.startsWith("CONVERT ECONOMICS enabled=true")),
                "the container must have run announceAndGuard: " + infos);
        assertEquals(List.of(), infos.stream().filter(i -> i.startsWith("INIT - market ")).toList(),
                "convert enabled: no market falls back before a build");
    }

    // ---- plumbing --------------------------------------------------------------------------------------------

    private final ListAppender<ILoggingEvent> verifierLogs = new ListAppender<>();

    @AfterEach
    void releaseVerifierLogs() {
        ((Logger) LoggerFactory.getLogger(LoansConfigVerifier.class)).detachAppender(verifierLogs);
    }

    private ApplicationContextRunner verifierRunner(String minswapPoolPolicy, String minswapPoolSpend,
                                                    String minswapOrderSpend) throws Exception {
        BFBackendService bf = blockfrostServingTheRecordedDatums();
        AppConfig.Network mainnet = new AppConfig.Network();
        mainnet.setNetworkForTest("mainnet");
        Logger logger = (Logger) LoggerFactory.getLogger(LoansConfigVerifier.class);
        verifierLogs.list.clear();
        verifierLogs.start();
        logger.addAppender(verifierLogs);
        return new ApplicationContextRunner()
                .withBean(LendingConfigGate.class)
                .withBean(LoansConfigVerifier.class, () -> new LoansConfigVerifier(
                        new LoansContractRegistry(CONFIG, LM_CONFIG, ASSET, SMART,
                                minswapPoolPolicy, minswapPoolSpend, minswapOrderSpend),
                        SMART, mainnet, bf, false));
    }

    private void assertOnePerMarketWarnAfter(String unavailablePrefix) {
        List<String> warns = messages(verifierLogs, Level.WARN);
        int unavailable = indexOfPrefix(warns, unavailablePrefix);
        assertTrue(unavailable >= 0, "the '" + unavailablePrefix + "' line must be printed: " + warns);
        assertEquals(List.of(VERIFIER_LINE), perMarketLines(warns),
                "exactly one per-market WARN, for market 0, exact text; none for the ANTICIPATE market");
        assertTrue(warns.indexOf(VERIFIER_LINE) > unavailable,
                "the per-market WARN follows the CONVERT UNAVAILABLE line: " + warns);
    }

    private static List<String> perMarketLines(List<String> warns) {
        return warns.stream().filter(w -> w.startsWith("market ")).toList();
    }

    private static int indexOfPrefix(List<String> lines, String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static void assertBound(AppConfig.LiquidationConfiguration configuration) {
        assertEquals(2, configuration.getMarkets().size(), "both markets must bind: " + configuration.getMarkets());
        assertEquals(AppConfig.LiquidationConfiguration.Action.CONVERT_BEFORE_ANTICIPATE,
                configuration.getMarkets().get(0).getAction());
        assertEquals(AppConfig.LiquidationConfiguration.Action.ANTICIPATE, configuration.getMarkets().get(1).getAction());
    }

    private List<String> runEconomics(String convertEnabled) {
        Logger logger = (Logger) LoggerFactory.getLogger(ConvertEconomics.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            new ApplicationContextRunner()
                    .withUserConfiguration(EconomicsCtx.class)
                    .withPropertyValues(MARKETS)
                    .withPropertyValues("network=preview",
                            "loans.liquidation.convert.enabled=" + convertEnabled,
                            "loans.liquidation.convert.dex-cost-floor-lovelace=5000000",
                            "loans.liquidation.convert.minswap-order-cost-lovelace=4000000")
                    .run(context -> {
                        assertNull(context.getStartupFailure(),
                                () -> "the context must start: " + context.getStartupFailure());
                        assertBound(context.getBean(AppConfig.LiquidationConfiguration.class));
                        assertTrue(context.getBean(ConvertEconomics.class) != null);
                    });
        } finally {
            logger.detachAppender(logs);
        }
        return messages(logs, Level.INFO);
    }

    private static List<String> messages(ListAppender<ILoggingEvent> logs, Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** Blockfrost serving the recorded mainnet ConfigDatum and LMConfigDatum, as ExecutorContextResolutionTest does. */
    private BFBackendService blockfrostServingTheRecordedDatums() throws Exception {
        UtxoService utxos = Mockito.mock(UtxoService.class);
        for (String[] served : new String[][]{
                {CONFIG, "mainnet-config-datum-2026-09-30.hex"}, {LM_CONFIG, "mainnet-lm-config-datum.hex"}}) {
            String datum;
            try (InputStream in = getClass().getResourceAsStream("/loans-v4/" + served[1])) {
                datum = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            String address = AddressProvider.getEntAddress(
                    Credential.fromScript(HexUtil.decodeHexString(served[0])), Networks.mainnet()).getAddress();
            Utxo utxo = Utxo.builder()
                    .txHash("00".repeat(32)).outputIndex(0)
                    .amount(List.of(Amount.lovelace(BigInteger.TWO), Amount.asset(served[0] + ASSET, BigInteger.ONE)))
                    .inlineDatum(datum).build();
            Mockito.when(utxos.getUtxos(address, 100, 1))
                    .thenReturn(Result.<List<Utxo>>success("ok").withValue(List.of(utxo)));
        }
        BFBackendService bf = Mockito.mock(BFBackendService.class);
        Mockito.when(bf.getUtxoService()).thenReturn(utxos);
        return bf;
    }
}
