package com.fluidtokens.aquarium.offchain.service.loans;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-194 — <b>a {@code CONVERT_BEFORE_ANTICIPATE} market converts exactly as a {@code CONVERT} market
 * does</b>: the same route, the same record, the same pricing, and the same refusals before any build.
 *
 * <p>Every assertion here stays true once the pay-in-advance fallback lands (FAB-194-3), because each one
 * is about a candidate convert can serve, or a refusal decided before the convert verdict is ever asked
 * for: a convert that builds, a DISABLED market, a spent loan, and a node that cannot convert at all. What
 * the fallback changes — what happens after convert says it is impossible — is not asserted here.
 *
 * <p>The candidate is {@link RouteStageHarness#tokenPrincipalConvertBond}, on a LIVE node, as in
 * {@code MarketActionRoutePinTest}, whose P1, P6 and P9 these mirror.
 */
class ConvertBeforeAnticipateConvertsLikeConvertTest {

    private static final long CAP = 1_000_000_000L;

    private static final BigInteger ORDER_LOVELACE = BigInteger.valueOf(4_000_000L);

    private static final String ACCEPTED = "ab".repeat(32);

    private static AppConfig.LiquidationConfiguration marketOf(Action action, Mode mode) {
        return node(Mode.LIVE, market(PRINCIPAL_TOKEN, mode, action,
                action == Action.CONVERT ? null : CAP));
    }

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

    /** The transaction an ANTICIPATE-market run of the candidate builds: the canned convert result. */
    private static Transaction k() throws Exception {
        Run anticipate = stage(marketOf(Action.ANTICIPATE, null), tokenPrincipalConvertBond(1),
                new ThrowingConvertRouter(new IllegalStateException("an ANTICIPATE market never asks")));
        return Transaction.deserialize(HexUtil.decodeHexString(onlyRow(anticipate).txCborHex()));
    }

    /**
     * N1 — with the same canned transaction, a CONVERT_BEFORE_ANTICIPATE market's row is the CONVERT market's
     * row: same outcome, reason, variant, detail and transaction. No F1 bracket and no rider, because it is
     * not the pay-in-advance route.
     */
    @Test
    void n1ItsRowIsTheConvertMarketsRow() throws Exception {
        Transaction k = k();

        Run convert = stage(marketOf(Action.CONVERT, null), tokenPrincipalConvertBond(1),
                new BuildingConvertRouter(k, ORDER_LOVELACE));
        BuildingConvertRouter router = new BuildingConvertRouter(k, ORDER_LOVELACE);
        Run cba = stage(marketOf(Action.CONVERT_BEFORE_ANTICIPATE, null), tokenPrincipalConvertBond(1), router);

        LiquidationDecision expected = onlyRow(convert);
        LiquidationDecision row = onlyRow(cba);
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, expected.outcome(), expected.detail());
        assertEquals(expected.outcome(), row.outcome(), row.detail());
        assertEquals(expected.reason(), row.reason());
        assertEquals(expected.variant(), row.variant());
        assertEquals(LiquidationDecision.VARIANT_CONVERT_MINSWAP, row.variant());
        assertEquals(expected.detail(), row.detail());
        assertEquals(expected.txHash(), row.txHash());
        assertFalse(row.detail().contains("[REPLACED (F1"), row.detail());
        assertEquals(1, router.calls());
        assertEquals(List.of(U_UTXO), router.offered(), "funded from U, and only U");
        assertEquals(WorkResult.SUBMITTED, cba.result());
    }

    /** N2 — DISABLED: refused before any build, exactly as a DISABLED CONVERT market is. */
    @Test
    void n2ADisabledMarketIsRefusedBeforeAnyBuildExactlyAsConvert() {
        ThrowingConvertRouter router = new ThrowingConvertRouter(
                new IllegalStateException("a DISABLED market never asks the convert router"));

        Run run = stage(marketOf(Action.CONVERT_BEFORE_ANTICIPATE, Mode.DISABLED), tokenPrincipalConvertBond(1),
                router);

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertEquals("MARKET_DISABLED", row.reason());
        assertEquals("market " + PRINCIPAL_TOKEN.toUnit() + " is DISABLED (node mode LIVE), so the bot does "
                + "nothing in it — refused before any build", row.detail());
        assertEquals("LiquidateAndConvert", row.variant());
        assertEquals(0, router.calls());
        assertEquals(0, run.convertBuilds());
        assertEquals(0, run.plainBuilds());
    }

    /** N3 — a spent loan's NO_UTXO row is labelled with the convert route. */
    @Test
    void n3ItsNoUtxoRowIsLabelledLiquidateAndConvert() {
        Run run = stage(marketOf(Action.CONVERT_BEFORE_ANTICIPATE, null), tokenPrincipalConvertBond(1).unresolvable(),
                new ThrowingConvertRouter(new IllegalStateException("nothing is built for a spent loan")));

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.NO_UTXO, row.outcome());
        assertEquals("LiquidateAndConvert", row.variant());
    }

    /**
     * N4 — on a node that cannot convert (the null-router test constructor) it is refused
     * {@code CONVERT_UNAVAILABLE}, naming its own action, and nothing is built.
     */
    @Test
    void n4OnANodeThatCannotConvertItIsRefusedConvertUnavailableAndNothingIsBuilt() {
        Run run = stage(marketOf(Action.CONVERT_BEFORE_ANTICIPATE, null), tokenPrincipalConvertBond(1), null);

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertEquals("CONVERT_UNAVAILABLE", row.reason());
        assertTrue(row.detail().contains("is action: CONVERT_BEFORE_ANTICIPATE but this node cannot convert"),
                row.detail());
        assertEquals("LiquidateAndConvert", row.variant());
        assertEquals(0, run.plainBuilds());
        assertTrue(run.submitted().isEmpty());
        assertEquals(WorkResult.DEFINITE_FAILURE, run.result());
    }
}
