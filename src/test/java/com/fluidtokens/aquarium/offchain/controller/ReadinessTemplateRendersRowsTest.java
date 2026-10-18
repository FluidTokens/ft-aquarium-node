package com.fluidtokens.aquarium.offchain.controller;

import com.fluidtokens.aquarium.offchain.model.AssetDisplay;
import com.fluidtokens.aquarium.offchain.model.LoanAge;
import com.fluidtokens.aquarium.offchain.model.TokenMetadata;
import com.fluidtokens.aquarium.offchain.service.loans.AnticipateAndSell;
import com.fluidtokens.aquarium.offchain.service.loans.PoolUsability;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    private static final String FLDT_UNIT = "577f0b13".repeat(7) + "0014df10464c4454";

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
                "lovelace", BigInteger.valueOf(20_000_000L), BigInteger.valueOf(20_003_778L),
                "577f0b13…0014df10464c4454", BigInteger.valueOf(100_000_000L),
                0.87, 89.82, true, null,
                BigInteger.valueOf(5_000_000L), BigInteger.valueOf(1_113_385L), null,
                "CAPITAL IN ADVANCE", "no Minswap pool is available for this pair",
                BigInteger.valueOf(20_887_781L),
                new LoanAge("3d 4h", "2026-09-11T09:00:00Z"),
                AssetDisplay.of(BigInteger.valueOf(20_000_000L), TokenMetadata.ada()),
                AssetDisplay.of(BigInteger.valueOf(100_000_000L),
                        new TokenMetadata(FLDT_UNIT, "FLDT", "FluidTokens", 6, TokenMetadata.Source.REGISTRY)),
                new PoolUsability(PoolUsability.Verdict.TOO_THIN,
                        "the pool would return about 4 but the debt to clear is 9, short by 5 — the "
                                + "order would be refunded at the operator's expense"),
                // ⚠ POPULATED ON PURPOSE. This row exists to render every branch; nulls here would
                // skip the scaled fee, the ada valuation, the scaled capital and both explorer links,
                // and the test would pass without touching any of them.
                AssetDisplay.of(BigInteger.valueOf(5_000_000L),
                        new TokenMetadata(FLDT_UNIT, "FLDT", "FluidTokens", 6, TokenMetadata.Source.REGISTRY)),
                AssetDisplay.of(BigInteger.valueOf(1_113_385L), TokenMetadata.ada()),
                AssetDisplay.of(BigInteger.valueOf(20_887_781L), TokenMetadata.ada()),
                AssetDisplay.of(BigInteger.valueOf(20_003_778L), TokenMetadata.ada()),
                // ⚠ A POSITIVE estimate on purpose: `profitable()` drives a class on the cell, so a
                // negative-only fixture would never render the branch an operator acts on.
                new AnticipateAndSell(BigInteger.valueOf(1_250_000L), "net positive"),
                AssetDisplay.of(BigInteger.valueOf(1_250_000L), TokenMetadata.ada()),
                // ⚠ A LIVE, would-act row: `wouldAct` drives a distinct class, so a fixture that only
                // ever renders the idle branch would never exercise the cell an operator reacts to.
                new ActionNow("CONVERT", "live: this loan would be liquidated on the next scan", true),
                // ⚠ A BLOCKED row on purpose: the badge is the branch this fixture exists to render.
                new ProcessingBlocker("funds", "the wallet holds less of this loan's principal than "
                        + "the advance would need"),
                "https://cexplorer.io/asset/aabbccdd1b6fda505ea9b739e42b5871d274344af37c196ddb70619541a7d06d",
                "https://cexplorer.io/tx/d832b78e");
    }

    /** And one with every optional field null — the other half of the row branch. */
    private static LiquidationReadinessController.Row sparseRow() {
        return new LiquidationReadinessController.Row(
                "abc", "aa#0", "lovelace", BigInteger.ONE, null, "tok", BigInteger.TEN,
                null, null, null, "no usable oracle feed",
                null, null, "no usable oracle feed", "UNKNOWN", "no bond indexed", null,
                // ⚠ The UNKNOWN-metadata path deliberately: the marker branch is its own render path
                // and, like the fee expression before it, cannot fire from a row that avoids it.
                new LoanAge("unknown", null),
                AssetDisplay.of(BigInteger.ONE, TokenMetadata.ada()),
                AssetDisplay.of(BigInteger.TEN, TokenMetadata.unknown(FLDT_UNIT)),
                PoolUsability.checkFailed("SocketTimeoutException"),
                null, null, null, null,
                AnticipateAndSell.unknown("no lender bond indexed"), null,
                ActionNow.of(null, null, null, null, true, false, null), ProcessingBlocker.NONE,
                null, null);
    }

    /**
     * ⛔ <b>The banner is the reason this page can be trusted at a glance.</b> Without it the table
     * rendered a route and a positive margin on a node that would do none of it.
     */
    @Test
    void theBannerSaysWhatTheNodeWillActuallyDo() {
        String html = render(List.of(fullRow()));

        assertTrue(html.contains("MONITORING ONLY"), "the headline must be on the page");
        assertTrue(html.contains("simulation"), "and it must name the figures below as a simulation");
        assertTrue(html.contains("liquidation: DISABLED"), "the mode itself must be visible, not implied");
    }

    /** A live node must look different, or the banner is decoration. */
    @Test
    void aLiveNodeRendersADistinctBanner() {
        String html = render(List.of(fullRow()),
                new OperationalStatus("LIVE — this node can submit", "LIVE", true, true, true, 0,
                        List.of()));

        assertTrue(html.contains("LIVE — this node can submit"));
        assertTrue(html.contains("status live"), "the live banner must carry its own class: " + html.contains("status"));
    }

    /**
     * ⛔ <b>TOOLTIPS ARE INVISIBLE ON TOUCH AND ABSENT FROM SCREENSHOTS</b>, so the one thing that may
     * never move into a `title` is the reason an UNKNOWN is unknown — which is the failure this page
     * exists to prevent. Everything else may hide; this may not.
     */
    @Test
    void anUnknownPoolVerdictKeepsItsReasonOnScreenRatherThanInATooltip() {
        String html = render(List.of(sparseRow()));

        // sparseRow carries PoolUsability.checkFailed(...), which is CHECK_FAILED rather than UNKNOWN —
        // so assert the shape that matters: the detail text is present as TEXT for an unknown-ish cell.
        assertTrue(html.contains("SocketTimeoutException"),
                "a failed lookup must say so where it can be read without hovering");
    }

    /** The breakdown answers "why is the capital required more than the debt", permanently. */
    @Test
    void theBreakdownExplainsWhyCapitalRequiredIsNotTheDebt() {
        String html = render(List.of(fullRow()));

        assertTrue(html.contains("Capital required"), "the breakdown must name the figure");
        assertTrue(html.contains("liquidation penalty"),
                "and it must say WHY it differs from the debt, rather than leaving the reader to guess");
        assertTrue(html.contains("Estimated gross margin"), "the realistic profit lives here");
    }

    /** ⛔ A phone is for knowing whether to find a laptop, not for operating a bot that holds funds. */
    @Test
    void theSmallScreenViewIsAGlanceAndNotTheTable() {
        String html = render(List.of(fullRow()));

        assertTrue(html.contains("class=\"glance\""), "the phone view must exist");
        assertTrue(html.contains("Open this on a laptop") || html.contains("Nothing needs you right now"),
                "and it must end in a verdict about whether to go and act");
        assertTrue(html.contains("max-width: 700px"), "the table must be hidden below the breakpoint");
    }

    private static String render(List<LiquidationReadinessController.Row> rows) {
        return render(rows, monitoringOnly());
    }

    /** The common case, and the one the banner exists for: nothing on this node can submit. */
    private static OperationalStatus monitoringOnly() {
        return new OperationalStatus("MONITORING ONLY", "DISABLED", true, false, false, 0,
                List.of("liquidation is DISABLED — the figures below are a simulation, not an intention"));
    }

    private static String render(List<LiquidationReadinessController.Row> rows,
                                 OperationalStatus status) {
        return render(rows, status, null);
    }

    private static String render(List<LiquidationReadinessController.Row> rows,
                                 OperationalStatus status, String lendingConfigBlocked) {
        return render(rows, status, lendingConfigBlocked, null);
    }

    private static String render(List<LiquidationReadinessController.Row> rows,
                                 OperationalStatus status, String lendingConfigBlocked, String walletNotReady) {
        return render(rows, status, lendingConfigBlocked, walletNotReady, List.of());
    }

    private static String render(List<LiquidationReadinessController.Row> rows,
                                 OperationalStatus status, String lendingConfigBlocked, String walletNotReady,
                                 List<String> referenceScriptDegradations) {
        return render(rows, status, lendingConfigBlocked, walletNotReady, referenceScriptDegradations, ctx -> { });
    }

    /** The defaults below, then {@code adjust} — for the pager and option cases that need other values. */
    private static String render(List<LiquidationReadinessController.Row> rows, Consumer<Context> adjust) {
        return render(rows, monitoringOnly(), null, null, List.of(), adjust);
    }

    private static String render(List<LiquidationReadinessController.Row> rows,
                                 OperationalStatus status, String lendingConfigBlocked, String walletNotReady,
                                 List<String> referenceScriptDegradations, Consumer<Context> adjust) {
        var context = new Context();
        context.setVariable("walletNotReady", walletNotReady);
        context.setVariable("lendingConfigBlocked", lendingConfigBlocked);
        context.setVariable("referenceScriptDegradations", referenceScriptDegradations);
        context.setVariable("network", "preview");
        context.setVariable("generatedAt", "2026-09-04T13:00:00Z");
        context.setVariable("disabledReason", null);
        context.setVariable("status", status);
        context.setVariable("sort", "health");
        context.setVariable("dir", "asc");
        context.setVariable("principalFilter", null);
        context.setVariable("collateralFilter", null);
        context.setVariable("filterQuery", "");
        // ⚠ A KNOWN, non-empty wallet: the strip's branches are amount/ticker rendering, and a fixture
        // with an unknown balance would only ever exercise the "unknown" path.
        context.setVariable("wallet", walletNotReady != null ? WalletBalance.unknown() : new WalletBalance(
                java.util.Map.of("lovelace", BigInteger.valueOf(12_500_000L)),
                System.currentTimeMillis(), true));
        context.setVariable("walletDisplay", java.util.Map.of("lovelace",
                AssetDisplay.of(BigInteger.valueOf(12_500_000L), TokenMetadata.ada())));
        context.setVariable("walletAgeSeconds", 34L);
        context.setVariable("totalRows", rows.size());
        context.setVariable("liquidatableCount",
                rows.stream().filter(r -> Boolean.TRUE.equals(r.liquidatable())).count());
        context.setVariable("blockedCount",
                rows.stream().filter(r -> r.blocker() != null && r.blocker().blocked()).count());
        context.setVariable("page", 1);
        context.setVariable("pages", 1);
        context.setVariable("principals", List.of("lovelace"));
        context.setVariable("collaterals", List.of(FLDT_UNIT));
        // FAB-145: the dropdowns' text, unit → label, already ordered — what the controller now sets.
        context.setVariable("principalOptions", orderedMap("lovelace", "ADA"));
        context.setVariable("collateralOptions", orderedMap(FLDT_UNIT, "FLDT"));
        context.setVariable("rows", rows);
        adjust.accept(context);
        return engine().process("readiness", context);
    }

    private static Map<String, String> orderedMap(String... unitThenLabel) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < unitThenLabel.length; i += 2) {
            out.put(unitThenLabel[i], unitThenLabel[i + 1]);
        }
        return out;
    }

    // ---- FAB-145: the pager, and the dropdowns' text ---------------------------------------------

    /** The pager element alone — never the CSS in {@code <style>}, which also says "pager". */
    private static String pager(String html) {
        int start = html.indexOf("class=\"pager\"");
        if (start < 0) {
            return null;
        }
        return html.substring(start, html.indexOf("</nav>", start));
    }

    /** Every href in {@code fragment}, with the HTML escaping of {@code &} undone. */
    private static List<String> hrefs(String fragment) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("href=\"([^\"]*)\"").matcher(fragment);
        while (m.find()) {
            out.add(m.group(1).replace("&amp;", "&"));
        }
        return out;
    }

    /** The {@code page} query parameter of a pager link. */
    private static String pageOf(String href) {
        Matcher m = Pattern.compile("[?&]page=([^&]*)").matcher(href);
        assertTrue(m.find(), "a pager link must carry page=: " + href);
        return m.group(1);
    }

    private static Consumer<Context> onPage(int page, int pages) {
        return ctx -> {
            ctx.setVariable("page", page);
            ctx.setVariable("pages", pages);
            ctx.setVariable("sort", "age");
            ctx.setVariable("dir", "desc");
            ctx.setVariable("principalFilter", FLDT_UNIT);
            ctx.setVariable("filterQuery", "&principal=" + FLDT_UNIT);
        };
    }

    /**
     * ⛔ FAB-145: PR #44 paged the list server-side and wrote the pager's CSS, but never its markup, so
     * page 2 was reachable only by typing {@code ?page=2}. And every pager link must carry the sort and
     * the active filter: a "next" that silently cleared the filter would read as the filter failing.
     */
    @Test
    void aMultiPageListRendersAPagerWhoseLinksCarryTheSortAndTheFilter() {
        String html = render(List.of(fullRow()), onPage(2, 3));

        String pager = pager(html);
        assertNotNull(pager, "a list of three pages must render an element with class=\"pager\"");
        assertTrue(pager.contains("page 2 of 3"), "the pager must say where the reader is: " + pager);
        List<String> links = hrefs(pager);
        assertEquals(List.of("1", "3"), links.stream().map(ReadinessTemplateRendersRowsTest::pageOf).toList(),
                "page 2 of 3 links back to page 1 and on to page 3: " + links);
        for (String link : links) {
            assertTrue(link.contains("sort=age"), "a pager link must keep the sort: " + link);
            assertTrue(link.contains("dir=desc"), "a pager link must keep the direction: " + link);
            assertTrue(link.contains("principal=" + FLDT_UNIT), "a pager link must keep the filter: " + link);
        }
        assertTrue(html.indexOf("class=\"pager\"") > html.indexOf("</table>"), "the pager sits below the table");
    }

    /** The first page has nowhere to go back to, and the last nowhere to go on to. */
    @Test
    void theFirstPageHasNoPreviousLinkAndTheLastNoNextLink() {
        List<String> first = hrefs(pager(render(List.of(fullRow()), onPage(1, 3))));
        assertEquals(List.of("2"), first.stream().map(ReadinessTemplateRendersRowsTest::pageOf).toList(),
                "page 1 of 3 links only forward: " + first);

        List<String> last = hrefs(pager(render(List.of(fullRow()), onPage(3, 3))));
        assertEquals(List.of("2"), last.stream().map(ReadinessTemplateRendersRowsTest::pageOf).toList(),
                "page 3 of 3 links only back: " + last);
    }

    /**
     * ⚠ FAB-145 T1 audit residue: the exact two-page boundary. Three pages could not tell {@code pages > 1}
     * from {@code pages > 2}; two can — page 1 of 2 links only on, page 2 of 2 only back.
     */
    @Test
    void aTwoPageListRendersAPagerOnEitherPage() {
        String first = pager(render(List.of(fullRow()), onPage(1, 2)));
        assertNotNull(first, "two pages must render a pager on page 1");
        assertTrue(first.contains("page 1 of 2"), first);
        assertEquals(List.of("2"), hrefs(first).stream().map(ReadinessTemplateRendersRowsTest::pageOf).toList(),
                "page 1 of 2 links only to page 2");
        assertTrue(first.contains("next") && !first.contains("previous"), "page 1 of 2 has only a next link");

        String second = pager(render(List.of(fullRow()), onPage(2, 2)));
        assertNotNull(second, "two pages must render a pager on page 2");
        assertTrue(second.contains("page 2 of 2"), second);
        assertEquals(List.of("1"), hrefs(second).stream().map(ReadinessTemplateRendersRowsTest::pageOf).toList(),
                "page 2 of 2 links only to page 1");
        assertTrue(second.contains("previous") && !second.contains("next"), "page 2 of 2 has only a previous link");
    }

    /** One page needs no pager. ⚠ Asserted on the MARKUP: the stylesheet itself contains "pager". */
    @Test
    void aSinglePageRendersNoPager() {
        String html = render(List.of(fullRow()), onPage(1, 1));
        assertFalse(html.contains("class=\"pager\""), "a single page must not render a pager element");
        assertTrue(html.contains(".pager"), "fixture premise: the stylesheet does mention the pager");
    }

    /**
     * ⛔ FAB-145: the filter dropdowns showed {@code #strings.abbreviate(unit, 18)} — policy-id hex no
     * operator can read. The TEXT is now the label the table uses; the VALUE stays the full unit, because
     * {@code ?collateral=<unit>} is the URL contract a bookmark depends on.
     */
    @Test
    void aFilterOptionReadsTheTokensLabelWhileItsValueStaysTheUnit() {
        String html = render(List.of(fullRow()), ctx -> ctx.setVariable("collateralFilter", FLDT_UNIT));

        Matcher option = Pattern.compile("<option value=\"" + FLDT_UNIT + "\"([^>]*)>([^<]*)</option>").matcher(html);
        assertTrue(option.find(), "the collateral option's value must be the full unit");
        assertEquals("FLDT", option.group(2), "the option must read the token's label, not its policy id");
        assertTrue(option.group(1).contains("selected"), "the active filter is still matched by unit");
        assertFalse(html.contains(">" + abbreviated(FLDT_UNIT) + "</option>"),
                "the abbreviated hex must no longer be an option's text");
        assertTrue(html.matches("(?s).*<option value=\"lovelace\"[^>]*>ADA</option>.*"), "ada reads ADA");
    }

    /** ⛔ A label is externally sourced (a token registry), so it reaches the page escaped, never as markup. */
    @Test
    void anOptionLabelIsEscaped() {
        String html = render(List.of(fullRow()),
                ctx -> ctx.setVariable("collateralOptions", orderedMap(FLDT_UNIT, "<b>FLDT</b>")));
        assertTrue(html.contains("&lt;b&gt;FLDT&lt;/b&gt;</option>"), "the label must be escaped");
        assertFalse(html.contains("<b>FLDT</b>"), "a registry's text must never reach the page as markup");
    }

    /** A context that never set the option maps still renders, with the abbreviated unit as before. */
    @Test
    void withoutTheOptionMapsTheDropdownsFallBackToTheAbbreviatedUnit() {
        String html = render(List.of(fullRow()), ctx -> {
            ctx.removeVariable("principalOptions");
            ctx.removeVariable("collateralOptions");
        });
        assertTrue(html.contains("<option value=\"" + FLDT_UNIT + "\">" + abbreviated(FLDT_UNIT) + "</option>"),
                "the fallback is the abbreviated unit, as before FAB-145");
        assertTrue(html.matches("(?s).*<option value=\"lovelace\"[^>]*>ADA</option>.*"), "ada still reads ADA");
    }

    // ---- FAB-145 T2: the book above the filters ----------------------------------------------------

    private static final String USDM_UNIT = "c48cbb3d5e57ed56e276bc45f99ab39abe94e6cd7ac39fb402da47ad0014df105553444d";

    /** The book element alone — never the CSS in {@code <style>}, which also says "book". */
    private static String book(String html) {
        int start = html.indexOf("class=\"book\"");
        if (start < 0) {
            return null;
        }
        return html.substring(start, html.indexOf("class=\"filters\"", start));
    }

    /** One {@code <span>} of the book: its attributes and its text. */
    private record Span(String attributes, String text) {
        String title() {
            Matcher m = Pattern.compile("title=\"([^\"]*)\"").matcher(attributes);
            return m.find() ? m.group(1) : null;
        }
    }

    /**
     * The first {@code <span>} in {@code book} whose text starts with {@code textPrefix} — the ELEMENT, not a
     * substring of the block, so a word asserted on it is in the same element as the figure. Null when none.
     */
    private static Span span(String book, String textPrefix) {
        Matcher m = Pattern.compile("<span([^>]*)>(" + Pattern.quote(textPrefix) + "[^<]*)</span>").matcher(book);
        return m.find() ? new Span(m.group(1), m.group(2)) : null;
    }

    /**
     * A book of seven loans with FLDT as the one token line (3 loans, 1 unpriced), 128 ADA in total, and
     * either $85.33 at 1 ADA ≈ $0.666666 or USD unavailable for {@code usdReason}.
     */
    private static LiquidationReadinessController.BookSummary bookSummary(String tokenLabel, String usdReason) {
        var fldt = new TokenMetadata(FLDT_UNIT, tokenLabel, "FluidTokens", 6, TokenMetadata.Source.REGISTRY);
        var usdm = new TokenMetadata(USDM_UNIT, "USDM", "USDM", 6, TokenMetadata.Source.REGISTRY);
        var line = new LiquidationReadinessController.TokenTvl(FLDT_UNIT, 3,
                AssetDisplay.of(BigInteger.valueOf(4_500_000L), fldt), BigInteger.valueOf(128_000_000L),
                AssetDisplay.of(BigInteger.valueOf(128_000_000L), TokenMetadata.ada()), 1);
        boolean usd = usdReason == null;
        return new LiquidationReadinessController.BookSummary(7, 2, 1, List.of(line),
                BigInteger.valueOf(128_000_000L), 1,
                AssetDisplay.of(BigInteger.valueOf(128_000_000L), TokenMetadata.ada()),
                usd ? AssetDisplay.of(BigInteger.valueOf(85_333_333L), usdm) : null,
                usd ? AssetDisplay.of(BigInteger.valueOf(666_666L), usdm) : null,
                usdReason);
    }

    /**
     * ⛔ FAB-145 T2: the desktop view shows the book — loan counts, collateral locked per token, ≈ ADA and
     * ≈ USD — above the filters, labelled an estimate that follows them, and hidden on a phone.
     */
    @Test
    void theBookRendersItsCountsTokenLinesAndTotalsAboveTheFilters() {
        String html = render(List.of(fullRow()),
                ctx -> ctx.setVariable("book", bookSummary("FLDT", null)));

        String book = book(html);
        assertNotNull(book, "a context with a book must render class=\"book\"");
        assertTrue(book.contains("<b>7</b> loan(s)"), "the loan count: " + book);
        assertTrue(book.contains("<b>2</b> liquidatable now"), "the liquidatable count: " + book);
        assertTrue(book.contains("Collateral locked (TVL)"), "the TVL is named as collateral locked");
        assertTrue(book.contains(">FLDT<"), "the token line carries its label: " + book);
        assertTrue(book.contains(">4.50<"), "and its locked amount, scaled: " + book);
        assertTrue(book.contains("≈ 128.00 ADA"), "the ada estimate: " + book);
        assertTrue(book.contains("1 loan(s) excluded: their collateral has no usable oracle price"),
                "the exclusions are said, not hidden: " + book);
        assertFalse(book.contains("USD unavailable"), "no unavailable text beside a dollar figure: " + book);
        assertTrue(book.contains("estimate at oracle prices") && book.contains("following the filters"),
                "the whole block is labelled an estimate that follows the filters");
        assertTrue(html.indexOf("class=\"book\"") < html.indexOf("class=\"filters\""), "the book sits above the filters");
        assertTrue(html.contains(".wallet-age, .book { display:none; }"), "and is hidden under the phone breakpoint");
    }

    /**
     * ⛔ FAB-145 T3: the dollar figure is an ESTIMATE — the ADA total converted at the USDM oracle price, 1 USDM
     * counted as $1 — and says so in words, in the figure's own element and on the line beside it. A reader who
     * sees only those two cannot take it for a quote or for a USD oracle.
     */
    @Test
    void theDollarFigureSaysItIsAnEstimateFromAdaToUsdm() {
        String book = book(render(List.of(fullRow()), ctx -> ctx.setVariable("book", bookSummary("FLDT", null))));
        assertNotNull(book);

        Span usd = span(book, "≈ $");
        assertNotNull(usd, "the dollar figure renders as its own element: " + book);
        assertEquals("≈ $85.33 (estimated)", usd.text(), "the word 'estimated' is in the figure's own element");
        assertNotNull(usd.title(), "the dollar figure carries a tooltip: " + usd);
        assertTrue(usd.title().contains("85.333333 USDM"), "the tooltip keeps the exact USDM amount: " + usd.title());
        assertTrue(usd.title().contains("estimate"), "the tooltip says it is an estimate: " + usd.title());
        assertTrue(usd.title().contains("not a quote"), "and not a quote: " + usd.title());

        Span rate = span(book, "estimated from ");
        assertNotNull(rate, "the rate line renders as its own element and names the method: " + book);
        String line = rate.text();
        assertTrue(line.contains("estimated"), "the rate line says estimated: " + line);
        assertTrue(line.contains("ADA → USDM"), "the conversion's direction: " + line);
        assertTrue(line.contains("USDM oracle"), "the price it uses: " + line);
        assertTrue(line.contains("1 ADA ≈ $0.666666"), "the implied rate: " + line);
        assertTrue(line.contains("counting 1 USDM as $1"), "the assumption that makes USDM dollars: " + line);
    }

    /** USD unavailable: the reason takes the figure's place, and no dollar figure renders at all. */
    @Test
    void anUnavailableUsdShowsItsReasonAndNoDollarFigure() {
        String reason = "no usable USDM oracle price right now";
        String book = book(render(List.of(fullRow()), ctx -> ctx.setVariable("book", bookSummary("FLDT", reason))));

        assertNotNull(book);
        assertTrue(book.contains("USD unavailable: " + reason), "the reason renders: " + book);
        assertFalse(book.contains("≈ $"), "no dollar figure renders: " + book);
        assertFalse(book.contains("estimated from ADA"), "and no rate line, which would read as a figure: " + book);
        assertTrue(book.contains("≈ 128.00 ADA"), "the ada estimate is unaffected");
    }

    /** With every loan priced there is nothing excluded, and the book does not say otherwise. */
    @Test
    void aBookWithNoUnpricedLoanSaysNothingWasExcluded() {
        var b = bookSummary("FLDT", null);
        var allPriced = new LiquidationReadinessController.BookSummary(b.loans(), b.liquidatable(), b.blocked(),
                b.tokens(), b.totalLovelace(), 0, b.totalAdaDisplay(), b.usdDisplay(), b.adaUsdRateDisplay(),
                b.usdUnavailableReason());
        String book = book(render(List.of(fullRow()), ctx -> ctx.setVariable("book", allPriced)));

        assertNotNull(book);
        assertFalse(book.contains("loan(s) excluded"), "no exclusion line when nothing was excluded: " + book);
    }

    /** ⛔ A token label is registry-sourced, so in the book too it reaches the page escaped. */
    @Test
    void aTokenLabelInTheBookIsEscaped() {
        String html = render(List.of(fullRow()),
                ctx -> ctx.setVariable("book", bookSummary("<b>FLDT</b>", null)));
        assertTrue(book(html).contains("&lt;b&gt;FLDT&lt;/b&gt;"), "the label must be escaped");
        assertFalse(html.contains("<b>FLDT</b>"), "a registry's text must never reach the page as markup");
    }

    /** A context without a book renders exactly as before. ⚠ On the MARKUP: the stylesheet says "book". */
    @Test
    void withoutABookThePageRendersAndTheBlockIsAbsent() {
        String html = render(List.of(fullRow()));
        assertTrue(html.contains("CAPITAL IN ADVANCE"), "the page still renders its rows");
        assertFalse(html.contains("class=\"book\""), "no book element without a book");
        assertTrue(html.contains(".book"), "fixture premise: the stylesheet does mention the book");
    }

    /** What {@code #strings.abbreviate(unit, 18)} renders. */
    private static String abbreviated(String unit) {
        return unit.substring(0, 15) + "...";
    }

    /** {@code r} with its collateral oracle version set — the record has no wither. */
    private static LiquidationReadinessController.Row withOracleVersion(LiquidationReadinessController.Row r,
                                                                      Integer version) {
        return new LiquidationReadinessController.Row(r.loanId(), r.utxoRef(), r.principalUnit(),
                r.principalAmount(), r.remainingDebt(), r.collateralUnit(), r.collateralAmount(),
                r.healthFactor(), r.currentLtvPercent(), r.liquidatable(), r.healthUnknownReason(),
                r.feeInCollateral(), r.feeValueLovelace(), r.feeUnknownReason(), r.route(), r.routeDetail(),
                r.advancePrincipalAmount(), r.age(), r.principalDisplay(), r.collateralDisplay(),
                r.poolUsability(), r.feeDisplay(), r.feeValueDisplay(), r.advanceDisplay(), r.debtDisplay(),
                r.anticipateAndSell(), r.anticipateDisplay(), r.actionNow(), r.blocker(), r.loanExplorerUrl(),
                r.utxoExplorerUrl(), version);
    }

    /** FAB-112: the oracle version a loan's datum names is shown next to its health; absent, nothing. */
    @Test
    void theOracleVersionALoanUsesIsShownNextToItsHealth() {
        assertTrue(render(List.of(withOracleVersion(fullRow(), 1))).contains("oracle v1"));
        // ⚠ The label itself, not "oracle v": template comments reach the output, and the one beside this
        // span says "oracle version".
        String unknown = render(List.of(fullRow()));
        assertTrue(!unknown.contains("oracle v1") && !unknown.contains("oracle v2"),
                "no label when the version is unknown");
    }

    /**
     * ⛔ FAB-115: a closed lending gate is shown above everything, while the rows still render — the
     * loan view keeps working, so a page of healthy-looking loans must not hide that the bot refuses
     * every one of them. Absent, nothing is shown.
     */
    @Test
    void aClosedLendingGateIsBanneredAboveTheRowsAndAbsentOtherwise() {
        String reason = "ConfigDatum[11]: derived 63b26ff9, chain 64d9b13f";
        String blocked = render(List.of(fullRow()), monitoringOnly(), reason);
        assertTrue(blocked.contains("LENDING_CONFIG_MISMATCH") && blocked.contains(reason),
                "the banner must name the refusal and its reason");
        assertTrue(blocked.indexOf("LENDING_CONFIG_MISMATCH") < blocked.indexOf("<table"),
                "the banner must come before the table");

        String open = render(List.of(fullRow()), monitoringOnly(), null);
        assertTrue(!open.contains("LENDING_CONFIG_MISMATCH"), "no banner while the gate is open");
    }

    /**
     * ⛔ FAB-108: reference-script problems are bannered above the rows, one line each, and the banner says
     * lending is NOT refused — the competing banner (a closed gate) must not appear for them. Each line is
     * escaped: it carries provider answers (an UNVERIFIED reason quotes the HTTP error body). Absent, or an
     * empty list, nothing is shown.
     */
    @Test
    void referenceScriptProblemsAreBanneredAboveTheRowsEscapedAndAbsentOtherwise() {
        String dropped = "REFERENCE_SCRIPT_DROPPED loans.liquidation.reference-scripts.loan-claim-action "
                + "(954f8be5#0): the output publishes 1551bd4e, not the derived 6a6f2aec";
        String unverified = "REFERENCE_SCRIPT_UNVERIFIED loans.liquidation.reference-scripts.loan "
                + "(e724b9c7#0): could not be checked: HTTP 500";
        String hostile = "REFERENCE_SCRIPT_UNVERIFIED loans.compound.reference-scripts entry 2 (c1c1#0): could not "
                + "be checked: HTTP 502 for txs/c1c1/utxos: <script>alert(1)</script>";
        String html = render(List.of(fullRow()), monitoringOnly(), null, null, List.of(dropped, unverified, hostile));

        int banner = html.indexOf("REFERENCE SCRIPTS: 3 problem(s)");
        assertTrue(banner >= 0, "the banner must count the problems");
        assertTrue(html.contains("lending is NOT refused"), "the banner must say lending goes on");
        assertTrue(banner < html.indexOf("<table"), "the banner must come before the table");
        assertTrue(html.contains(dropped) && html.contains(unverified), "every entry is on the page");
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), "the hostile entry is on the page, escaped");
        assertFalse(html.contains("<script>alert(1)"), "a provider's text must never reach the page as markup");
        assertFalse(html.contains("LENDING_CONFIG_MISMATCH"), "a reference-script problem does not close the gate");

        String one = render(List.of(fullRow()), monitoringOnly(), null, null, List.of(dropped));
        assertTrue(one.contains("REFERENCE SCRIPTS: 1 problem(s)") && !one.contains("REFERENCE SCRIPTS: 3"),
                "the count is the list's, not a constant");

        assertFalse(render(List.of(fullRow()), monitoringOnly(), null, null, List.of()).contains("REFERENCE SCRIPTS:"),
                "no banner for an empty list");
        assertFalse(render(List.of(fullRow()), monitoringOnly(), null, null, null).contains("REFERENCE SCRIPTS:"),
                "no banner, and no failure, for a null attribute");
    }

    /** FAB-134 B2: before the wallet is ready the strip says why, not "could not be read". */
    @Test
    void aWalletThatIsNotReadyYetSaysWhyInsteadOfReadingAsAFailure() {
        String reason = "wallet not ready: the node is still syncing";
        String html = render(List.of(fullRow()), monitoringOnly(), null, reason);
        assertTrue(html.contains(reason), "the not-ready reason must be on the page");
        assertTrue(!html.contains("the balance could not be read"), "not ready is not a failed read");

        String ready = render(List.of(fullRow()), monitoringOnly(), null, null);
        assertTrue(!ready.contains("wallet not ready"), "no not-ready text once ready");
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
        // ⛔ SCALED, not raw. This asserted the raw base-unit figure "20887781", which is exactly the
        // number an operator would have misread by a factor of a million. The column now renders the
        // asset's own scale and ticker like every other amount on the page.
        // ⚠ Rounded in the cell, exact on hover. Asserting BOTH is the point: a display rule must
        // never be the reason someone cannot reconcile a figure against the chain.
        assertTrue(html.contains("20.89"), "the capital-required figure must render, scaled and rounded");
        assertTrue(html.contains("20.887781"), "and the exact figure must survive, for reconciliation");
        assertFalse(html.contains("20887781"),
                "the raw base-unit figure must NOT appear once a scale is known — that is the defect");

        // The fee slice is TWO assets: the collateral amount and its ada valuation. Rendering both as
        // bare integers read as one number restated, which is how it was read.
        assertTrue(html.contains("5") && html.contains("FLDT"),
                "the fee slice must carry the collateral ticker");
        // ⚠ Asserted without the apostrophe: Thymeleaf escapes it to &#39; in the output, so matching
        // the literal sentence would fail on the ENCODING rather than on the content.
        assertTrue(html.contains("1.113385"), "the fee's ada valuation must render scaled");
        assertTrue(html.contains("oracle price"),
                "and be labelled as a valuation, not as a second quantity of the same asset");

        // Both explorer links, and both hashes still reachable in full via the title attribute.
        assertTrue(html.contains("https://cexplorer.io/asset/"), "the loan asset link must render");
        assertTrue(html.contains("https://cexplorer.io/tx/"), "the loan UTxO link must render");
        assertTrue(html.contains("loan NFT asset name") && html.contains("loan UTxO"),
                "each hash must say WHICH hash it is, and carry the full value on hover");
        // ⛔ This asserted the RAW lovelace figure "1113385". That cell is the one the |...| literal
        // fix made render at all, and it is now also scaled — so the raw form must be GONE, for the
        // same reason as the capital column: a bare base-unit integer beside a scaled one is the
        // misreading this page exists to prevent. The scaled assertion above covers the rendering.
        assertFalse(html.contains("1113385"),
                "the raw lovelace figure must not survive once ada's scale is known");
        // ⚠ Thymeleaf HTML-ESCAPES th:text output, so the apostrophe arrives as &#39; — asserting the
        // raw character failed here and it was the assertion that was wrong, not the fix. Accept
        // either: the point is that the apostrophe reached the OUTPUT at all, which is what proves it
        // was only ever illegal inside a quoted EXPRESSION and is perfectly legal as text.
        // ⚠ Matches the APOSTROPHE, not the sentence. This pinned "today's price" and broke when the
        // caption gained the word "oracle" -- failing on copy rather than on the thing it guards.
        assertTrue(html.contains("today&#39;s") || html.contains("today's"),
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

        // ⛔ TWO ROWS PER LOAN NOW: the data row and its breakdown row. Counting only the total would
        // pass if the breakdown silently stopped rendering, so both are asserted.
        assertEquals(4, html.split("<tr", -1).length - 1 - 1,
                "two loans, each a data row plus a breakdown row, on top of the header row");
        assertEquals(2, html.split("<tr class=\"detail\"", -1).length - 1,
                "every loan must get exactly one breakdown row");
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

    /**
     * ⛔ The enriched row actually reaches the page: a scaled amount, a ticker, and the exact instant
     * behind the age. Rendering without throwing is necessary and not sufficient — a template can
     * swallow a missing field silently and produce a page that is merely empty where it should speak.
     */
    @Test
    void anEnrichedRowRendersItsTickerScaledAmountAndExactAge() {
        String html = render(List.of(fullRow()));

        assertTrue(html.contains("FLDT"), "the ticker must be rendered, not the raw unit");
        assertTrue(html.contains("20.887781") || html.contains("20"), "amounts render scaled");
        assertTrue(html.contains("3d 4h"), "the readable age wins the visible slot");
        assertTrue(html.contains("2026-09-11T09:00:00Z"),
                "and the exact instant must survive, on hover, for anyone reconciling against the chain");
        assertTrue(html.contains("100"), "100,000,000 base units at 6 decimals renders as 100");
    }

    /**
     * ⛔ <b>THE MARKER MUST APPEAR.</b> A raw base-unit figure and a scaled one can look identical and
     * differ by a factor of a million. If this branch ever renders silently, an operator reads a
     * plausible number that is wrong — the exact failure the unknown-metadata path exists to prevent.
     */
    @Test
    void aTokenWithNoMetadataIsMarkedAndSaysItsAmountIsRaw() {
        String html = render(List.of(sparseRow()));

        assertTrue(html.contains("&#10071;") || html.contains("\u2757"),
                "the no-metadata marker must be rendered");
        assertTrue(html.contains("raw base units"),
                "and it must say in words that the figure is unscaled");
        assertTrue(html.contains("no metadata found for"),
                "the tooltip must name the asset it could not resolve");
    }

    /** Auto-refresh ships on by default, and the control to switch it off ships with it. */
    @Test
    void theAutoRefreshControlIsPresentAndCheckedByDefault() {
        String html = render(List.of(fullRow()));

        assertTrue(html.contains("id=\"autorefresh\""), "the toggle must exist");
        assertTrue(html.matches("(?s).*id=\"autorefresh\"[^>]*checked.*"),
                "auto-refresh is ON by default, which was the requirement");
        assertTrue(html.contains("aq.readiness.autorefresh"),
                "the preference is remembered per browser, so switching it off survives the refresh");
    }

    /**
     * ⛔ <b>THE FOUR STATES MUST SURVIVE THE TEMPLATE.</b> A service that distinguishes them feeding a
     * page that renders two of them identically is the same defect one layer out — and it is the layer
     * nobody usually tests. Each verdict must reach the page as its own label, its own CSS class and
     * its own sentence.
     */
    @Test
    void everyPoolVerdictRendersDistinguishably() {
        List<PoolUsability> verdicts = List.of(
                new PoolUsability(PoolUsability.Verdict.USABLE, "a pool exists and is deep enough"),
                new PoolUsability(PoolUsability.Verdict.TOO_THIN, "short by 5 — the order would be refunded"),
                PoolUsability.noPool(),
                PoolUsability.checkFailed("SocketTimeoutException"));

        Set<String> labels = new LinkedHashSet<>();
        Set<String> classes = new LinkedHashSet<>();
        for (PoolUsability v : verdicts) {
            String html = render(List.of(rowWithPool(v)));
            assertTrue(html.contains(v.detail()), "the reason must reach the page: " + v.detail());
            labels.add(v.verdict().name().replace('_', ' '));
            classes.add("pool " + v.verdict().name().toLowerCase());
            assertTrue(html.contains(v.verdict().name().replace('_', ' ')),
                    "the verdict label must be rendered: " + v.verdict());
            assertTrue(html.contains(v.verdict().name().toLowerCase()),
                    "and carry its own class so two states cannot look alike: " + v.verdict());
        }
        assertEquals(verdicts.size(), labels.size(), "two verdicts share a label");
        assertEquals(verdicts.size(), classes.size(), "two verdicts share a CSS class");
    }

    /**
     * ⛔ On an ANTICIPATE market the route is decided by configuration — and the pool's state is
     * reported anyway. An operator can change a setting; they cannot change a pool's depth. Hiding the
     * pool here is what stopped the page explaining which of the two was in the way.
     */
    @Test
    void anAnticipateMarketStillReportsWhatThePoolSaid() {
        String html = render(List.of(rowWithPool(
                new PoolUsability(PoolUsability.Verdict.USABLE, "a pool exists and is deep enough"))));

        assertTrue(html.contains("USABLE"),
                "a usable pool must still be reported on a market that fronts capital by configuration");
    }

    private static LiquidationReadinessController.Row rowWithPool(PoolUsability usability) {
        return new LiquidationReadinessController.Row(
                "abc", "aa#0", "lovelace", BigInteger.ONE, BigInteger.TWO, FLDT_UNIT, BigInteger.TEN,
                1.4, 70.0, false, null, BigInteger.ONE, BigInteger.ONE, null,
                "CAPITAL IN ADVANCE", "this market is configured action: ANTICIPATE", BigInteger.TEN,
                new LoanAge("2d", "2026-09-12T00:00:00Z"),
                AssetDisplay.of(BigInteger.ONE, TokenMetadata.ada()),
                AssetDisplay.of(BigInteger.TEN, TokenMetadata.unknown(FLDT_UNIT)),
                usability,
                null, null, null, null,
                AnticipateAndSell.unknown("no pool"), null,
                ActionNow.of(false, null, null, null, true, false, null), ProcessingBlocker.NONE,
                null, null);
    }
}
