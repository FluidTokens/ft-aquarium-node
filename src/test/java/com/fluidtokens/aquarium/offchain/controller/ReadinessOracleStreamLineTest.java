package com.fluidtokens.aquarium.offchain.controller;

import com.fluidtokens.aquarium.offchain.model.AssetDisplay;
import com.fluidtokens.aquarium.offchain.model.TokenMetadata;
import com.fluidtokens.aquarium.offchain.service.loans.OracleStreamClient;
import com.fluidtokens.aquarium.offchain.service.loans.OracleStreamLauncher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-174: the readiness page's one shadow-oracle-stream line, RENDERED through the same Thymeleaf setup as
 * {@link ReadinessTemplateRendersRowsTest} — one text per state, each saying that no decision reads it — and
 * the wiring that puts it there: a REQUIRED setter (optional injection would leave the line silently absent)
 * on a launcher the container can find. And {@code /healthcheck} does not know the stream exists.
 */
class ReadinessOracleStreamLineTest {

    private static final Instant SINCE = Instant.parse("2026-10-29T12:00:00Z");
    private static final Instant PUSHED = Instant.parse("2026-10-29T12:04:59Z");
    private static final Instant NEXT = Instant.parse("2026-10-29T12:01:00Z");
    private static final String NOTE = " · no decision reads it";

    /** The words that tell each state apart; every other state's must be absent from a state's line. */
    private static final Map<OracleStreamClient.Status.State, String> MARKER = Map.of(
            OracleStreamClient.Status.State.OFF, "(shadow): off",
            OracleStreamClient.Status.State.CONNECTING, "(shadow): connecting",
            OracleStreamClient.Status.State.CONNECTED, "(shadow): connected",
            OracleStreamClient.Status.State.RETRYING, "(shadow): disconnected",
            OracleStreamClient.Status.State.REFUSED, "(shadow): refused");

    /** The engine Boot builds for this app: classpath templates, HTML mode, same resolver. */
    private static TemplateEngine engine() {
        var resolver = new SpringResourceTemplateResolver();
        resolver.setApplicationContext(new StaticApplicationContext());
        resolver.setPrefix("classpath:/templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        var engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    @Test
    void eachStateReadsAsItsOwnLine() {
        Map<OracleStreamClient.Status, String> expected = new LinkedHashMap<>();
        expected.put(OracleStreamClient.Status.off("disabled (loans.oracle.stream.enabled=false)", SINCE),
                "Oracle stream (shadow): off — disabled (loans.oracle.stream.enabled=false)" + NOTE);
        expected.put(status(OracleStreamClient.Status.State.CONNECTING, null, null, 0, null, null, null),
                "Oracle stream (shadow): connecting…" + NOTE);
        expected.put(status(OracleStreamClient.Status.State.CONNECTED, null, null, 0, null, null, null),
                "Oracle stream (shadow): connected 2026-10-29T12:00:00Z — no push yet (pushes arrive only when a "
                        + "price changes)" + NOTE);
        expected.put(status(OracleStreamClient.Status.State.CONNECTED, null, PUSHED, 7, null, null, null),
                "Oracle stream (shadow): connected 2026-10-29T12:00:00Z · last push 2026-10-29T12:04:59Z · 7 pushes"
                        + NOTE);
        expected.put(status(OracleStreamClient.Status.State.RETRYING, "no-pong", PUSHED, 7, null, null, NEXT),
                "Oracle stream (shadow): disconnected (no-pong) — retrying at 2026-10-29T12:01:00Z" + NOTE);
        expected.put(status(OracleStreamClient.Status.State.REFUSED, null, null, 0, 429,
                        "connection limit reached: 1 per bond held", NEXT),
                "Oracle stream (shadow): refused 429 — connection limit reached: 1 per bond held — retrying at "
                        + "2026-10-29T12:01:00Z" + NOTE);

        expected.forEach((status, text) -> {
            String line = line(render(ctx -> ctx.setVariable("oracleStream", status)));
            assertEquals(text, text(line), status.toString());
            assertTrue(line.contains("title=\"shadow only — no decision reads it\""), line);
            MARKER.forEach((state, marker) -> {
                if (state != status.state()) {
                    assertFalse(text(line).contains(marker), status.state() + " reads as " + state + ": " + line);
                }
            });
            boolean connected = status.state() == OracleStreamClient.Status.State.CONNECTED;
            assertEquals(connected && status.lastPushAt() == null, text(line).contains("no push yet"), line);
            assertEquals(connected && status.lastPushAt() != null, text(line).contains("last push"), line);
        });
    }

    @Test
    void aRefusalMessageIsEscaped() {
        String html = render(ctx -> ctx.setVariable("oracleStream", status(OracleStreamClient.Status.State.REFUSED,
                null, null, 0, 403, "<script>alert(1)</script>", NEXT)));

        assertTrue(line(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;"), line(html));
        assertFalse(html.contains("<script>alert(1)"), "server text must never reach the page as markup");
    }

    @Test
    void withNoAttributeNoLineRenders() {
        String html = render(ctx -> { });
        assertFalse(html.contains("Oracle stream (shadow)"), "no line without the attribute");
        assertFalse(html.contains("oracle-stream"), "not even an empty element");
    }

    /**
     * The controller sets the attribute from the launcher it was handed — and, constructed directly with no
     * launcher, sets it to null so the page renders no line.
     */
    @Test
    void theControllerPutsTheLaunchersStatusOnThePageAndNothingWithoutOne() {
        OracleStreamLauncher launcher = new OracleStreamLauncher(null, null, null, null, null, "false", null);
        launcher.start();
        assertEquals(OracleStreamClient.Status.State.OFF, launcher.status().state(), "fixture: a disabled stream");

        LiquidationReadinessController with = controller();
        with.setOracleStreamLauncher(launcher);
        var model = new org.springframework.ui.ConcurrentModel();
        with.readiness(model, null, null, null, null, null);
        OracleStreamClient.Status shown = (OracleStreamClient.Status) model.getAttribute("oracleStream");
        assertNotNull(shown);
        assertEquals(OracleStreamClient.Status.State.OFF, shown.state());
        assertEquals("disabled (loans.oracle.stream.enabled=false)", shown.detail());
        var context = new Context();
        context.setVariables(model.asMap());
        assertEquals("Oracle stream (shadow): off — disabled (loans.oracle.stream.enabled=false)" + NOTE,
                text(line(engine().process("readiness", context))));

        var without = new org.springframework.ui.ConcurrentModel();
        controller().readiness(without, null, null, null, null, null);
        assertNull(without.getAttribute("oracleStream"), "no status without a launcher");
        var withoutContext = new Context();
        withoutContext.setVariables(without.asMap());
        assertFalse(engine().process("readiness", withoutContext).contains("Oracle stream (shadow)"), "and no line");
    }

    /** ⛔ Optional injection fails SILENT: the container would hand no launcher and the line would never show. */
    @Test
    void theLauncherIsInjectedRequiredAndIsAComponent() throws Exception {
        var setter = LiquidationReadinessController.class.getMethod("setOracleStreamLauncher", OracleStreamLauncher.class);
        Autowired autowired = setter.getAnnotation(Autowired.class);
        assertNotNull(autowired, "LiquidationReadinessController.setOracleStreamLauncher is not @Autowired — the "
                + "container would leave it holding no launcher, and the line would never render");
        assertTrue(autowired.required(), "LiquidationReadinessController.setOracleStreamLauncher must be REQUIRED: "
                + "optional injection fails silent when the launcher bean is missing");
        assertTrue(OracleStreamLauncher.class.isAnnotationPresent(Component.class),
                "OracleStreamLauncher must be a scanned @Component, or a required setter fails the boot");
    }

    /** ⛔ {@code /healthcheck}'s verdict is untouched: the stream is not one of its components. */
    @Test
    void theHealthcheckVerdictDoesNotIncludeTheStream() {
        assertEquals(List.of("dbOk", "parametersOk", "parametersRefInputOk", "walletOk", "stakingOk", "lendingGate",
                        "lendingGateReason"),
                Arrays.stream(Healthcheck.HealthCheck.class.getRecordComponents()).map(c -> c.getName()).toList());
    }

    // ======================================================================================
    // Helpers
    // ======================================================================================

    private static OracleStreamClient.Status status(OracleStreamClient.Status.State state, String detail,
                                                    Instant lastPushAt, long pushes, Integer refusalStatus,
                                                    String refusalMessage, Instant nextAttemptAt) {
        return new OracleStreamClient.Status(state, detail, SINCE, lastPushAt, pushes, refusalStatus, refusalMessage,
                nextAttemptAt);
    }

    private static LiquidationReadinessController controller() {
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        return new LiquidationReadinessController(
                beans.getBeanProvider(com.fluidtokens.aquarium.offchain.service.loans.LiquidationCandidateScanner.class),
                beans.getBeanProvider(com.fluidtokens.aquarium.offchain.service.loans.LoanService.class),
                beans.getBeanProvider(com.fluidtokens.aquarium.offchain.service.loans.LoanHealthService.class),
                beans.getBeanProvider(com.fluidtokens.aquarium.offchain.service.loans.FluidOracleClient.class),
                beans.getBeanProvider(com.fluidtokens.aquarium.offchain.service.loans.MinswapPoolResolver.class),
                beans.getBeanProvider(com.fluidtokens.aquarium.offchain.service.loans.TokenMetadataService.class),
                beans.getBeanProvider(com.fluidtokens.aquarium.offchain.service.LoansContractRegistry.class),
                beans.getBeanProvider(com.fluidtokens.aquarium.offchain.service.AppUtxoService.class),
                new com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration(
                        com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode.SHADOW,
                        60, 120, 30, BigInteger.ZERO, 200),
                null);
    }

    /** The page with no rows, the harness's defaults, then {@code adjust}. */
    private static String render(Consumer<Context> adjust) {
        var context = new Context();
        context.setVariable("walletNotReady", null);
        context.setVariable("lendingConfigBlocked", null);
        context.setVariable("referenceScriptDegradations", List.of());
        context.setVariable("network", "preview");
        context.setVariable("generatedAt", "2026-10-29T12:00:00Z");
        context.setVariable("disabledReason", null);
        context.setVariable("status", new OperationalStatus("MONITORING ONLY", "DISABLED", true, false, false, 0,
                List.of("liquidation is DISABLED — the figures below are a simulation, not an intention")));
        context.setVariable("sort", "health");
        context.setVariable("dir", "asc");
        context.setVariable("principalFilter", null);
        context.setVariable("collateralFilter", null);
        context.setVariable("filterQuery", "");
        context.setVariable("wallet", new WalletBalance(Map.of("lovelace", BigInteger.valueOf(12_500_000L)),
                System.currentTimeMillis(), true));
        context.setVariable("walletDisplay", Map.of("lovelace",
                AssetDisplay.of(BigInteger.valueOf(12_500_000L), TokenMetadata.ada())));
        context.setVariable("walletAgeSeconds", 34L);
        context.setVariable("totalRows", 0);
        context.setVariable("liquidatableCount", 0L);
        context.setVariable("blockedCount", 0L);
        context.setVariable("page", 1);
        context.setVariable("pages", 1);
        context.setVariable("principals", List.of());
        context.setVariable("collaterals", List.of());
        context.setVariable("principalOptions", new LinkedHashMap<String, String>());
        context.setVariable("collateralOptions", new LinkedHashMap<String, String>());
        context.setVariable("rows", List.of());
        adjust.accept(context);
        return engine().process("readiness", context);
    }

    /** The stream's element, markup included. */
    private static String line(String html) {
        int start = html.indexOf("<div class=\"note oracle-stream\"");
        assertTrue(start >= 0, "the page carries no oracle stream line");
        return html.substring(start, html.indexOf("</div>", start) + "</div>".length());
    }

    /** What the line READS: tags dropped, whitespace collapsed. */
    private static String text(String fragment) {
        return fragment.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}
