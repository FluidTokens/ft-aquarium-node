package com.fluidtokens.aquarium.offchain.controller;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ <b>The readiness template, rendered WITH A ROW.</b>
 *
 * <h2>Why this exists, and it is the §2c class exactly</h2>
 * The readiness page has <b>two render paths</b> — an empty-state branch and a {@code th:each} row
 * branch — and until 2026-09-04 <b>only the empty one had ever been exercised.</b> The page was
 * checked live, returned 200, and was declared working. It had been broken since it shipped.
 *
 * <p>The row branch contained {@code th:text="'≈ ' + ${…} + ' lovelace at today&amp;apos;s price'"}.
 * <b>{@code &amp;apos;} is unescaped to a bare apostrophe BEFORE Thymeleaf parses the expression</b>,
 * which terminates the single-quoted literal and throws {@code TemplateProcessingException}. It
 * cannot fire while {@code rows} is empty, because the expression is never evaluated — so the page
 * served 200 for as long as preview had no loans, and <b>500 on every request the moment two
 * appeared.</b>
 *
 * <p>⇒ <b>A 200 on a page with an empty-state branch proves nothing about the page.</b> It proves the
 * branch that renders nothing renders nothing. The other path is untested until something puts a row
 * through it, and "we looked at it and it was fine" is exactly how it stayed broken.
 *
 * <p>⚠ {@code ApplicationYamlBindsTest} and {@code ContainerWiringTest} cannot catch this — it is a
 * template expression, not configuration or wiring. <b>A third render-time seam, and the same
 * lesson each time: the green thing was never asked the question.</b>
 */
class ReadinessTemplateRendersRowsTest {

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

    /**
     * A row with EVERY optional field populated. ⚠ Deliberately not a minimal one: the defect lived
     * behind {@code th:if="${r.feeValueLovelace != null}"}, so a row with nulls would have skipped
     * the broken expression and passed — a fixture that renders a row is not enough, it has to render
     * <b>this</b> row.
     */
    private static LiquidationReadinessController.Row fullRow() {
        return new LiquidationReadinessController.Row(
                "1b6fda505ea9b739e42b5871d274344af37c196ddb70619541a7d06d", "d832b78e…#1",
                "lovelace", BigInteger.valueOf(20_000_000L),
                "577f0b13…0014df10464c4454", BigInteger.valueOf(100_000_000L),
                0.87, 89.82, true, null,
                BigInteger.valueOf(5_000_000L), BigInteger.valueOf(1_113_385L), null,
                "CAPITAL IN ADVANCE", "no Minswap pool is available for this pair",
                BigInteger.valueOf(20_887_781L));
    }

    /** And one with every optional field null — the other half of the row branch. */
    private static LiquidationReadinessController.Row sparseRow() {
        return new LiquidationReadinessController.Row(
                "abc", "aa#0", "lovelace", BigInteger.ONE, "tok", BigInteger.TEN,
                null, null, null, "no usable oracle feed",
                null, null, "no usable oracle feed", "UNKNOWN", "no bond indexed", null);
    }

    private static String render(List<LiquidationReadinessController.Row> rows) {
        var context = new Context();
        context.setVariable("network", "preview");
        context.setVariable("generatedAt", "2026-09-04T13:00:00Z");
        context.setVariable("disabledReason", null);
        context.setVariable("rows", rows);
        return engine().process("readiness", context);
    }

    /**
     * ⛔ THE ASSERTION THAT WOULD HAVE CAUGHT IT. Rendering a fully-populated row must not throw.
     *
     * <p>Proof-of-harness, run 2026-09-04: restoring {@code today&amp;apos;s} makes this throw
     * {@code TemplateProcessingException} with the column of the stray apostrophe — the same failure
     * Giovanni's browser got as a 500.
     */
    @Test
    void aFullyPopulatedRowRendersWithoutThrowing() {
        String html = render(List.of(fullRow()));

        assertTrue(html.contains("CAPITAL IN ADVANCE"), "the route pill must render");
        assertTrue(html.contains("20887781"), "the capital-needed figure must render");
        assertTrue(html.contains("1113385"), "the fee value must render — this is the broken cell");
        // ⚠ Thymeleaf HTML-ESCAPES th:text output, so the apostrophe arrives as &#39; — asserting the
        // raw character failed here and it was the assertion that was wrong, not the fix. Accept
        // either: the point is that the apostrophe reached the OUTPUT at all, which is what proves it
        // was only ever illegal inside a quoted EXPRESSION and is perfectly legal as text.
        assertTrue(html.contains("today&#39;s price") || html.contains("today's price"),
                "the apostrophe must survive to the output, escaped or not — that is the whole point: "
                        + "it is legal TEXT and was only ever illegal inside a quoted Thymeleaf "
                        + "literal. Rendered fragment absent entirely means the cell did not render.");
    }

    /** ⚠ The sparse row exercises every {@code th:if} the full row skips. Both halves, or neither. */
    @Test
    void aRowWithEveryOptionalFieldNullAlsoRenders() {
        String html = render(List.of(sparseRow()));

        assertTrue(html.contains("unknown"), "the unknown markers must render");
        assertTrue(html.contains("no usable oracle feed"), "and their reasons");
    }

    /** Two rows together — {@code th:each} over more than one, which is what production had. */
    @Test
    void severalRowsRenderTogether() {
        String html = render(List.of(fullRow(), sparseRow()));

        assertEquals(2, html.split("<tr", -1).length - 1 - 1,
                "two body rows plus the header row");
    }

    /**
     * ⚠ And the empty branch still works — the one that WAS covered. Kept so a fix to the row path
     * cannot quietly break the path that was fine.
     */
    @Test
    void theEmptyStateStillRendersAndNamesTheThreeNumbers() {
        String html = render(List.of());

        assertTrue(html.contains("No loans indexed"));
        assertTrue(html.contains("unreadable"),
                "the empty state must name the number that separates a quiet market from a blind node");
    }
}
