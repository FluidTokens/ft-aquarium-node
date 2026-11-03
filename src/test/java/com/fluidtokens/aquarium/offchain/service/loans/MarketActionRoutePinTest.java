package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.service.SubmitFence;
import com.fluidtokens.aquarium.offchain.service.WorkResult;
import com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.BuildingConvertRouter;
import com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.Run;
import com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.Scenario;
import com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.ThrowingConvertRouter;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.PRINCIPAL_TOKEN;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.TOKEN_WALLET_UTXO;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.U_UTXO;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.accepting;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.market;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.node;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.oracles;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.run;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.tokenPrincipalConvertBond;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.wallet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ⛔ FAB-194-1 — <b>what a {@code CONVERT} and an {@code ANTICIPATE} market record and log TODAY</b>, pinned
 * before {@code CONVERT_BEFORE_ANTICIPATE} touched a line of {@code src/main}.
 *
 * <p>Every assertion is an exact value — outcome, reason, variant, the whole detail, the log line's level
 * and formatted text, and how many times each builder was asked — taken from a run of the unmodified code.
 * FAB-194-3 rewrites the routing under these pins, and the promise it makes is that a market which is not
 * {@code CONVERT_BEFORE_ANTICIPATE} behaves exactly as before. This class is how that promise is checked,
 * so it is <b>frozen from FAB-194-1 on</b>, together with {@link RouteStageHarness}: no later slice lists
 * either file. A pin that has to move is a behaviour change, and is escalated rather than edited.
 *
 * <p>Two log lines are deliberately pinned no further than {@code contains("was refused by the market
 * gate")} — FAB-194-3 rewords the pay-in-advance gate refusal (its D10), and that wording is not what any
 * pin here is about.
 *
 * <p>Unless a test says otherwise the candidate is {@link RouteStageHarness#tokenPrincipalConvertBond}: a
 * token-collateral, token-principal loan whose bond allows conversion, on a LIVE node whose wallet holds U,
 * a token output of the principal, and C. {@code K} is the transaction the ANTICIPATE run of that candidate
 * builds — the canned transaction a CONVERT run's router stand-in hands back.
 */
class MarketActionRoutePinTest {

    private static final BigInteger AMPLE_CAP = BigInteger.valueOf(1_000_000_000L);

    /** What the CONVERT stand-in asks the executor's selector for: a token collateral's order overhead. */
    private static final BigInteger ORDER_LOVELACE = BigInteger.valueOf(4_000_000L);

    private static final String ACCEPTED = "ab".repeat(32);

    private static AppConfig.LiquidationConfiguration convertMarket(Mode mode) {
        return node(Mode.LIVE, market(PRINCIPAL_TOKEN, mode, Action.CONVERT, null));
    }

    private static AppConfig.LiquidationConfiguration anticipateMarket(Mode mode, long cap) {
        return node(Mode.LIVE, market(PRINCIPAL_TOKEN, mode, Action.ANTICIPATE, cap));
    }

    /** One stage call on {@code scenario}, with U, the principal's token output and C in the wallet. */
    private static Run stage(AppConfig.LiquidationConfiguration configuration, Scenario scenario,
                             ConvertLiquidationRouter router) {
        return run(configuration, List.of(scenario), wallet(U_UTXO, TOKEN_WALLET_UTXO), oracles(), router,
                accepting(ACCEPTED), SubmitFence.OPEN);
    }

    private static LiquidationDecision onlyRow(Run run) {
        List<LiquidationDecision> rows = run.log().newestFirst(10);
        assertEquals(1, rows.size(), "exactly one row: " + rows);
        return rows.getFirst();
    }

    /** The ANTICIPATE run P2 pins, from which {@code K} is taken. */
    private static Run anticipateRun(ThrowingConvertRouter router) {
        return stage(anticipateMarket(null, AMPLE_CAP.longValueExact()), tokenPrincipalConvertBond(1), router);
    }

    /** {@code K}: the transaction an ANTICIPATE-market run of the candidate builds, off its row. */
    static Transaction k() throws Exception {
        LiquidationDecision row = onlyRow(anticipateRun(new ThrowingConvertRouter(
                new IllegalStateException("an ANTICIPATE market never asks the convert router"))));
        return Transaction.deserialize(HexUtil.decodeHexString(row.txCborHex()));
    }

    private static String announcement(String ref) {
        return ref + " is a CONVERT liquidation (LiquidateAndConvert): the bot creates a Minswap order, "
                + "fronts nothing, and keeps the liquidation fee in collateral";
    }

    private static String funded(String ref, Utxo output, long lovelace, long requirement) {
        return ref + " is funded from wallet output " + output.getTxHash() + "#" + output.getOutputIndex()
                + " (role U, " + lovelace + " lovelace) for a requirement of " + requirement + " lovelace";
    }

    private static List<String> info(Run run) {
        return run.messages(Level.INFO);
    }

    // ---- P1 ----------------------------------------------------------------------------------------

    /** The CONVERT detail P1 pins; {@code ConvertBeforeAnticipateConvertsLikeConvertTest} N1 compares to it. */
    static final String P1_DETAIL = "fee slice 25000000 lovelace - tx fee 590858 - min-ada 0 = floor 24409142; "
            + "- margin 1500000 = 22909142; submitted as " + "ab".repeat(32);

    /**
     * P1 — a CONVERT market (an explicit entry, mode inherited): announced, funded from U and only then built,
     * recorded as {@code LiquidateAndConvert} with no F1 bracket and no rider, and submitted.
     */
    @Test
    void p1AConvertMarketIsAnnouncedFundedFromUAndRecordedAsLiquidateAndConvert() throws Exception {
        Transaction k = k();
        Scenario scenario = tokenPrincipalConvertBond(1);
        BuildingConvertRouter router = new BuildingConvertRouter(k, ORDER_LOVELACE);

        Run run = stage(convertMarket(null), scenario, router);

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, row.outcome());
        assertEquals("SUBMITTED", row.reason());
        assertEquals(LiquidationDecision.VARIANT_CONVERT_MINSWAP, row.variant());
        assertEquals("LiquidateAndConvert", row.variant());
        assertEquals(P1_DETAIL, row.detail());
        assertFalse(row.detail().contains("[REPLACED (F1"), row.detail());
        assertEquals(TransactionUtil.getTxHash(k), row.txHash(), "the row is K's");
        assertNull(row.submitVeto());

        String ref = scenario.loanRef();
        List<String> pinned = info(run).stream()
                .filter(line -> line.equals(announcement(ref)) || line.startsWith(ref + " is funded from"))
                .toList();
        assertEquals(List.of(announcement(ref), funded(ref, U_UTXO, 200_000_000L, 6_405_077L)), pinned,
                "the announcement, then U's funding line: " + info(run));

        assertEquals(1, run.convertBuilds());
        assertEquals(1, router.calls());
        assertEquals(List.of(U_UTXO), router.offered(), "the selector offers U, and only U");
        assertEquals(0, run.plainBuilds());
        assertEquals(1, run.submitted().size());
        assertEquals(WorkResult.SUBMITTED, run.result());
    }

    // ---- P2 ----------------------------------------------------------------------------------------

    /**
     * P2 — an ANTICIPATE market with an ample cap: the real pay-in-advance, recorded as
     * {@code LiquidateAndPayInAdvance} with the F1 token-principal bracket, and the convert router untouched.
     */
    @Test
    void p2AnAnticipateMarketPaysInAdvanceAndNeverAsksTheConvertRouter() {
        Scenario scenario = tokenPrincipalConvertBond(1);
        ThrowingConvertRouter router = new ThrowingConvertRouter(
                new IllegalStateException("an ANTICIPATE market never asks the convert router"));

        Run run = anticipateRun(router);

        LiquidationDecision row = onlyRow(run);
        String unit = PRINCIPAL_TOKEN.toUnit();
        String collateral = RouteStageHarness.COLLATERAL_TOKEN.toUnit();
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, row.outcome());
        assertEquals("SUBMITTED", row.reason());
        assertEquals(LiquidationDecision.VARIANT_CONVERT, row.variant());
        assertEquals("LiquidateAndPayInAdvance", row.variant());
        assertEquals("fee slice 25000000 lovelace - tx fee 590858 - min-ada 702370 [REPLACED (F1, token "
                + "principal): acquired 1000000 " + collateral + " (50000000 lovelace) - outlay 25000000 " + unit
                + " (25000001 lovelace, ceil-biased)] = floor 23706771; - margin 1500000 = 22206771; submitted as "
                + ACCEPTED, row.detail());
        assertTrue(row.detail().contains("[REPLACED (F1, token principal): acquired"), row.detail());

        String ref = scenario.loanRef();
        List<String> info = info(run);
        String announced = ref + " is a PAY-IN-ADVANCE liquidation: the bot PAYS the lender in " + unit
                + " and acquires the collateral, rather than earning a fee";
        String pays = ref + " pays the lender 25000000 " + unit + " and acquires the collateral";
        assertTrue(info.contains(announced), info.toString());
        assertTrue(info.contains(pays), info.toString());
        assertTrue(info.indexOf(announced) < info.indexOf(pays), info.toString());
        assertTrue(info.stream().noneMatch(line -> line.contains("is a CONVERT liquidation")), info.toString());

        assertEquals(0, router.calls(), "the convert router is never called");
        assertEquals(0, run.convertBuilds());
        assertEquals(0, run.plainBuilds());
        assertEquals(1, run.submitted().size());
        assertEquals(WorkResult.SUBMITTED, run.result());
    }

    // ---- P3 / P4 — a CONVERT market's pool verdicts are refused by name ----------------------------

    /** P3 — no pool: refused {@code NO_MINSWAP_POOL} at INFO, after the announcement, never paid in advance. */
    @Test
    void p3AConvertMarketWithNoPoolIsRefusedByNameAndNeverPaysInAdvance() {
        Scenario scenario = tokenPrincipalConvertBond(1);
        ThrowingConvertRouter router = new ThrowingConvertRouter(
                new ConvertLiquidationRouter.NoPoolException("no pool fixture"));

        Run run = stage(convertMarket(null), scenario, router);

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertEquals("NO_MINSWAP_POOL", row.reason());
        assertEquals("no pool fixture", row.detail());
        assertEquals("LiquidateAndConvert", row.variant());

        String ref = scenario.loanRef();
        List<String> info = info(run);
        String refused = "the convert liquidation of " + ref + " was refused: no pool fixture";
        assertTrue(info.contains(refused), info.toString());
        assertTrue(info.indexOf(announcement(ref)) >= 0 && info.indexOf(announcement(ref)) < info.indexOf(refused),
                "the CONVERT announcement is logged BEFORE the refusal: " + info);
        assertTrue(run.events().stream().noneMatch(event -> event.getFormattedMessage().contains("PAY-IN-ADVANCE")),
                "no PAY-IN-ADVANCE line");
        assertEquals(1, router.calls());
        assertEquals(1, run.convertBuilds());
        assertTrue(run.submitted().isEmpty());
        assertEquals(WorkResult.DEFINITE_FAILURE, run.result());
    }

    /**
     * P4 — the pool too thin: refused {@code POOL_TOO_THIN} at INFO. ⚠ The detail carries the refusal's name
     * twice over: {@code ConvertOrderPlan.RefusedException} prefixes its message with the refusal, and the
     * log line names the refusal again before that message. That is today's text, and it is what is pinned.
     */
    @Test
    void p4AConvertMarketWhosePoolIsTooThinIsRefusedByName() {
        Scenario scenario = tokenPrincipalConvertBond(1);
        ThrowingConvertRouter router = new ThrowingConvertRouter(new ConvertOrderPlan.RefusedException(
                ConvertOrderPlan.Refusal.POOL_TOO_THIN, "thin fixture"));

        Run run = stage(convertMarket(null), scenario, router);

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertEquals("POOL_TOO_THIN", row.reason());
        assertEquals("POOL_TOO_THIN: thin fixture", row.detail());
        assertEquals("LiquidateAndConvert", row.variant());
        assertTrue(info(run).contains("the convert liquidation of " + scenario.loanRef()
                + " was refused as POOL_TOO_THIN: POOL_TOO_THIN: thin fixture"), info(run).toString());
        assertEquals(1, router.calls());
        assertEquals(WorkResult.DEFINITE_FAILURE, run.result());
    }

    // ---- P5 — U is designated BEFORE the pool verdict -----------------------------------------------

    /**
     * P5 — a wallet holding C and a token output but no U: refused {@code WALLET_ROLE_MISSING} and the router
     * is never asked. In a CONVERT market U is designated BEFORE the pool verdict, so a missing U answers
     * first.
     */
    @Test
    void p5AConvertMarketDesignatesUBeforeThePoolVerdict() {
        Scenario scenario = tokenPrincipalConvertBond(1);
        ThrowingConvertRouter router = new ThrowingConvertRouter(
                new ConvertLiquidationRouter.NoPoolException("no pool fixture"));

        Run run = run(convertMarket(null), List.of(scenario), wallet(TOKEN_WALLET_UTXO), oracles(), router,
                accepting(ACCEPTED), SubmitFence.OPEN);

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertEquals("WALLET_ROLE_MISSING", row.reason());
        assertTrue(row.detail().startsWith("role U is missing: "), row.detail());
        assertEquals("LiquidateAndConvert", row.variant());
        assertEquals(0, router.calls(), "U is designated before the router is asked");
        assertEquals(0, run.convertBuilds());
        assertEquals(WorkResult.DEFINITE_FAILURE, run.result());
    }

    // ---- P6 / P7 — a DISABLED market ----------------------------------------------------------------

    /** P6 — a DISABLED CONVERT market on a LIVE node is refused before any build: nothing is asked to build. */
    @Test
    void p6ADisabledConvertMarketIsRefusedBeforeAnyBuild() {
        Scenario scenario = tokenPrincipalConvertBond(1);
        ThrowingConvertRouter router = new ThrowingConvertRouter(
                new IllegalStateException("a DISABLED market never asks the convert router"));

        Run run = stage(convertMarket(Mode.DISABLED), scenario, router);

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertEquals("MARKET_DISABLED", row.reason());
        assertEquals("market " + PRINCIPAL_TOKEN.toUnit() + " is DISABLED (node mode LIVE), so the bot does "
                + "nothing in it — refused before any build", row.detail());
        assertEquals("LiquidateAndConvert", row.variant());
        assertEquals(0, router.calls());
        assertEquals(0, run.convertBuilds());
        assertEquals(0, run.plainBuilds());
        assertEquals(WorkResult.DEFINITE_FAILURE, run.result());
    }

    /**
     * P7 — a DISABLED ANTICIPATE market is NOT refused by the pre-build exclusion: the pay-in-advance router's
     * own gate refuses it, with its own text, and the convert router is never asked.
     */
    @Test
    void p7ADisabledAnticipateMarketIsRefusedByTheRoutersOwnGate() {
        Scenario scenario = tokenPrincipalConvertBond(1);
        ThrowingConvertRouter router = new ThrowingConvertRouter(
                new IllegalStateException("an ANTICIPATE market never asks the convert router"));

        Run run = stage(anticipateMarket(Mode.DISABLED, AMPLE_CAP.longValueExact()), scenario, router);

        LiquidationDecision row = onlyRow(run);
        String expected = "MARKET_DISABLED: market " + PRINCIPAL_TOKEN.toUnit() + " is DISABLED (node mode LIVE), "
                + "so the bot does nothing in it (anticipatable 0 of a required 25000000)";
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertTrue(row.reason().startsWith("MARKET_DISABLED: market " + PRINCIPAL_TOKEN.toUnit() + " is DISABLED"),
                row.reason());
        assertEquals(expected, row.reason());
        assertEquals(expected, row.detail());
        assertFalse(row.detail().contains("refused before any build"), row.detail());
        assertEquals("LiquidateAndPayInAdvance", row.variant());
        assertTrue(info(run).stream().anyMatch(line -> line.contains("was refused by the market gate")),
                info(run).toString());
        assertEquals(0, router.calls());
        assertEquals(0, run.convertBuilds());
        assertEquals(WorkResult.DEFINITE_FAILURE, run.result());
    }

    // ---- P8 — the cap ---------------------------------------------------------------------------------

    /** P8 — an ANTICIPATE market capped at 1 refuses the candidate it cannot front whole. */
    @Test
    void p8AnAnticipateMarketCappedBelowTheRequirementRefusesAboveMarketCap() {
        Scenario scenario = tokenPrincipalConvertBond(1);
        ThrowingConvertRouter router = new ThrowingConvertRouter(
                new IllegalStateException("an ANTICIPATE market never asks the convert router"));

        Run run = stage(anticipateMarket(null, 1L), scenario, router);

        LiquidationDecision row = onlyRow(run);
        String expected = "ABOVE_MARKET_CAP: market " + PRINCIPAL_TOKEN.toUnit() + " is capped at 1 but this "
                + "candidate requires 25000000 to be fronted; the protocol does not allow fronting part of a loan, "
                + "so it is refused rather than reduced (anticipatable 1 of a required 25000000)";
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertTrue(row.reason().startsWith("ABOVE_MARKET_CAP: "), row.reason());
        assertEquals(expected, row.reason());
        assertEquals(expected, row.detail());
        assertEquals("LiquidateAndPayInAdvance", row.variant());
        assertTrue(info(run).stream().anyMatch(line -> line.contains("was refused by the market gate")),
                info(run).toString());
        assertEquals(0, router.calls());
        assertEquals(WorkResult.DEFINITE_FAILURE, run.result());
    }

    // ---- P9 — a NO_UTXO row is labelled by the market's route ---------------------------------------

    /** P9 — an unresolvable loan UTxO: the NO_UTXO row's variant is the route the market would have taken. */
    @Test
    void p9ANoUtxoRowCarriesTheVariantOfTheMarketsRoute() {
        Scenario scenario = tokenPrincipalConvertBond(1).unresolvable();
        String detail = "loan utxo present=false, bond utxo present=true — spent since the scan";

        Run convert = stage(convertMarket(null), scenario, new ThrowingConvertRouter(
                new IllegalStateException("nothing is built for a spent loan")));
        LiquidationDecision convertRow = onlyRow(convert);
        assertEquals(LiquidationDecision.Outcome.NO_UTXO, convertRow.outcome());
        assertEquals("NO_UTXO", convertRow.reason());
        assertEquals(detail, convertRow.detail());
        assertEquals("LiquidateAndConvert", convertRow.variant());
        assertEquals(0, convert.convertBuilds());

        Run anticipate = stage(anticipateMarket(null, AMPLE_CAP.longValueExact()), scenario,
                new ThrowingConvertRouter(new IllegalStateException("nothing is built for a spent loan")));
        LiquidationDecision anticipateRow = onlyRow(anticipate);
        assertEquals(LiquidationDecision.Outcome.NO_UTXO, anticipateRow.outcome());
        assertEquals("NO_UTXO", anticipateRow.reason());
        assertEquals(detail, anticipateRow.detail());
        assertEquals("LiquidateAndPayInAdvance", anticipateRow.variant());
    }

    // ---- P10 — a machinery fault on the convert route ------------------------------------------------

    /** P10 — a genuine fault in the convert router: refused under the exception's class, ONE ERROR line. */
    @Test
    void p10AConvertRouterFaultIsRefusedUnderItsClassWithExactlyOneErrorLine() {
        Scenario scenario = tokenPrincipalConvertBond(1);
        ThrowingConvertRouter router = new ThrowingConvertRouter(new IllegalStateException("broken fixture"));

        Run run = stage(convertMarket(null), scenario, router);

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertEquals("IllegalStateException", row.reason());
        assertEquals("IllegalStateException: broken fixture", row.detail());
        assertEquals("LiquidateAndConvert", row.variant());
        List<ILoggingEvent> errors = run.events().stream().filter(event -> event.getLevel() == Level.ERROR).toList();
        assertEquals(1, errors.size(), "exactly one ERROR line: " + errors);
        assertTrue(errors.getFirst().getFormattedMessage().startsWith(
                "building the convert liquidation of " + scenario.loanRef() + " via convert failed: "),
                errors.getFirst().getFormattedMessage());
        assertEquals(1, router.calls());
        assertEquals(WorkResult.DEFINITE_FAILURE, run.result());
    }

    // ---- P11 — a node that cannot convert ------------------------------------------------------------

    /**
     * P11 — a CONVERT market on a node built with no convert router: refused {@code CONVERT_UNAVAILABLE} by
     * name, at WARN, and nothing is built — never a fall back to pay-in-advance.
     */
    @Test
    void p11AConvertMarketOnANodeThatCannotConvertIsRefusedByName() {
        Scenario scenario = tokenPrincipalConvertBond(1);

        Run run = stage(convertMarket(null), scenario, null);

        LiquidationDecision row = onlyRow(run);
        String expected = "market " + PRINCIPAL_TOKEN.toUnit() + " is action: CONVERT but this node cannot convert "
                + "(loans.minswap.* is unset or belongs to another network — see the CONVERT UNAVAILABLE line at "
                + "boot). NOT falling back to pay-in-advance: that would front capital the operator did not "
                + "authorise";
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertEquals("CONVERT_UNAVAILABLE", row.reason());
        assertEquals(expected, row.detail());
        assertEquals("LiquidateAndConvert", row.variant());
        assertEquals(List.of("the liquidation of " + scenario.loanRef() + " was refused: " + expected),
                run.messages(Level.WARN));
        assertEquals(0, run.plainBuilds());
        assertTrue(run.submitted().isEmpty());
        assertEquals(WorkResult.DEFINITE_FAILURE, run.result());
    }
}
