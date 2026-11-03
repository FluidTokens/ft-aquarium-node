package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import com.fluidtokens.aquarium.offchain.config.YaciConfig;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.ConvertAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.ConvertExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationAssessment;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OracleEntry;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.SubmitFence;
import com.fluidtokens.aquarium.offchain.service.WorkResult;
import com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.BuildingConvertRouter;
import com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.Run;
import com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.Scenario;
import com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.ThrowingConvertRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.ACCOUNT;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.COLLATERAL_TOKEN;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.NOW;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.PRINCIPAL_TOKEN;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.TOKEN_WALLET_UTXO;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.U_UTXO;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.accepting;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.adaPrincipalTokenCollateralConvertBond;
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
 * FAB-194-3 — <b>a {@code CONVERT_BEFORE_ANTICIPATE} market falls back to pay-in-advance in the same check,
 * and only when a convert is impossible.</b>
 *
 * <p>The triggers are exactly three: no pool ({@code NoPoolException}: none indexed for the pair, or no pool
 * address configured), a pool too thin ({@code POOL_TOO_THIN}), and convert switched off
 * ({@code loans.liquidation.convert.enabled=false}, decided before any build). The fallback then runs the
 * pay-in-advance route exactly as an {@code ANTICIPATE} market runs it — cap, balance, the token output (or U
 * for an ADA principal), F1, the S-15 rider, profitability — and the one row the candidate ends in carries a
 * fixed prefix naming the cause. Every other convert outcome is refused exactly as a {@code CONVERT} market
 * refuses it, and a missing U on a convert that IS possible is its one {@code WALLET_ROLE_MISSING} row.
 *
 * <p>Every case is ONE {@code cycle(...)} of a real {@link LiquidationExecutor} through
 * {@link RouteStageHarness}: the plain builder and the pay-in-advance router are real, the convert router is a
 * stand-in unless a case says otherwise, and every case asserts ONE row per candidate. Unless stated, the
 * candidate is {@link RouteStageHarness#tokenPrincipalConvertBond}, the cap is ample, the node is LIVE and the
 * submitter accepts.
 *
 * <p>(m) is the production-wiring shape CLAUDE.md asks for: a REAL {@link ConvertLiquidationRouter} with no
 * pool address configured, and the router the {@code YaciConfig} bean method builds over a disabled economics
 * gate — so the fallback is proven against what the real router throws, not only against fakes.
 */
class ConvertBeforeAnticipateFallbackTest {

    private static final long CAP = 1_000_000_000L;

    /** What the building stand-ins ask the executor's selector for: a token collateral's order overhead. */
    private static final BigInteger ORDER_LOVELACE = BigInteger.valueOf(4_000_000L);

    private static final String ACCEPTED = "ab".repeat(32);

    private static final String NO_POOL = "no pool fixture";

    /** The fallback prefix, spelled out: the contract's text, not the constant it is built from. */
    private static String prefix(String cause) {
        return "fallback to pay-in-advance (no convert: " + cause + "): ";
    }

    private static String fallbackLine(String ref, String cause, String message) {
        return ref + " falls back to pay-in-advance in this check — no convert: " + cause + " (" + message + ")";
    }

    // ---- the markets ----------------------------------------------------------------------------------------

    private static AppConfig.LiquidationConfiguration cba(Mode node, Mode market, AssetType principal, long cap) {
        return node(node, market(principal, market, Action.CONVERT_BEFORE_ANTICIPATE, cap));
    }

    private static AppConfig.LiquidationConfiguration cba(long cap) {
        return cba(Mode.LIVE, null, PRINCIPAL_TOKEN, cap);
    }

    private static AppConfig.LiquidationConfiguration anticipate(AssetType principal, long cap) {
        return node(Mode.LIVE, market(principal, null, Action.ANTICIPATE, cap));
    }

    private static AppConfig.LiquidationConfiguration convert() {
        return node(Mode.LIVE, market(PRINCIPAL_TOKEN, null, Action.CONVERT, null));
    }

    private static Run stage(AppConfig.LiquidationConfiguration configuration, List<Scenario> scenarios,
                             List<Utxo> walletUtxos, ConvertLiquidationRouter router) {
        return run(configuration, scenarios, walletUtxos, oracles(), router, accepting(ACCEPTED), SubmitFence.OPEN);
    }

    private static Run stage(AppConfig.LiquidationConfiguration configuration, Scenario scenario,
                             ConvertLiquidationRouter router) {
        return stage(configuration, List.of(scenario), wallet(U_UTXO, TOKEN_WALLET_UTXO), router);
    }

    // ---- reading a run -------------------------------------------------------------------------------------

    private static LiquidationDecision onlyRow(Run run) {
        List<LiquidationDecision> rows = run.log().newestFirst(10);
        assertEquals(1, rows.size(), "exactly one row: " + rows);
        return rows.getFirst();
    }

    private static LiquidationDecision rowFor(Run run, Scenario scenario) {
        List<LiquidationDecision> rows = run.log().newestFirst(10).stream()
                .filter(row -> row.loanUtxoRef().equals(scenario.loanRef())).toList();
        assertEquals(1, rows.size(), "exactly one row for " + scenario.loanRef() + ": " + run.log().newestFirst(10));
        return rows.getFirst();
    }

    private static Set<String> inputsOf(LiquidationDecision row) throws Exception {
        Transaction transaction = Transaction.deserialize(HexUtil.decodeHexString(row.txCborHex()));
        return transaction.getBody().getInputs().stream()
                .map(in -> in.getTransactionId() + "#" + in.getIndex()).collect(Collectors.toSet());
    }

    private static String ref(Utxo utxo) {
        return utxo.getTxHash() + "#" + utxo.getOutputIndex();
    }

    private static List<String> all(Run run) {
        return run.events().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static List<ILoggingEvent> at(Run run, Level level) {
        return run.events().stream().filter(event -> event.getLevel() == level).toList();
    }

    /** No line of any level that a convert attempt, announced or refused, would leave behind. */
    private static void assertNoConvertTrace(Run run, String ref) {
        for (String line : all(run)) {
            assertFalse(line.contains("the convert liquidation of " + ref + " was refused"), line);
            assertFalse(line.contains("is a CONVERT liquidation"), line);
            assertFalse(line.contains("(role U,"), "no U funding line: " + line);
        }
    }

    /** {@code K}: the transaction an ANTICIPATE-market run of the candidate builds, off its row. */
    private static Transaction k() throws Exception {
        Run run = stage(anticipate(PRINCIPAL_TOKEN, CAP), tokenPrincipalConvertBond(1),
                new ThrowingConvertRouter(new IllegalStateException("an ANTICIPATE market never asks")));
        return Transaction.deserialize(HexUtil.decodeHexString(onlyRow(run).txCborHex()));
    }

    // ---- stand-ins of its own -------------------------------------------------------------------------------

    private static ConvertEconomics economics(boolean enabled) {
        return new ConvertEconomics(new AppConfig.ConvertConfiguration(enabled), null, null);
    }

    /** A router whose economics gate is switched OFF; asked to build, it counts and throws. */
    static final class DisabledConvertRouter extends ConvertLiquidationRouter {
        private final AtomicInteger calls = new AtomicInteger();

        DisabledConvertRouter() {
            super(null, null, null, null, economics(false), null, null, null);
        }

        @Override
        public Transaction buildConvertLiquidation(LiquidationAssessment assessment, Utxo loanUtxo, Utxo bondUtxo,
                                                    Utxo configUtxo, Utxo lmConfigUtxo,
                                                    Function<BigInteger, Optional<Utxo>> walletSelector,
                                                    Utxo collateralUtxo, Map<String, OracleEntry> oracles,
                                                    String changeAddress, long validFromMillis, long validToMillis) {
            calls.incrementAndGet();
            throw new IllegalStateException("convert is disabled: this router must never be asked to build");
        }
    }

    /**
     * Per loan: {@code POOL_TOO_THIN} for the loans in {@code thin}, before asking the selector (as the real
     * router does); otherwise asks the selector for {@link #ORDER_LOVELACE} and returns {@code canned}.
     */
    static final class PerLoanConvertRouter extends ConvertLiquidationRouter {
        private final Transaction canned;
        private final Set<String> thin;
        private final List<Utxo> offered = Collections.synchronizedList(new ArrayList<>());

        PerLoanConvertRouter(Transaction canned, Set<String> thin) {
            super(null, null, null, null, economics(true), null, null, null);
            this.canned = canned;
            this.thin = thin;
        }

        @Override
        public Transaction buildConvertLiquidation(LiquidationAssessment assessment, Utxo loanUtxo, Utxo bondUtxo,
                                                    Utxo configUtxo, Utxo lmConfigUtxo,
                                                    Function<BigInteger, Optional<Utxo>> walletSelector,
                                                    Utxo collateralUtxo, Map<String, OracleEntry> oracles,
                                                    String changeAddress, long validFromMillis, long validToMillis) {
            if (thin.contains(assessment.loan().utxoRef())) {
                throw new ConvertOrderPlan.RefusedException(ConvertOrderPlan.Refusal.POOL_TOO_THIN, "thin fixture");
            }
            Optional<Utxo> selected = walletSelector.apply(ORDER_LOVELACE);
            selected.ifPresent(offered::add);
            return selected.map(u -> canned).orElseThrow(() ->
                    new ConvertLiquidationRouter.WalletInputTooSmallException("no utxo for the order"));
        }

        List<Utxo> offered() {
            return List.copyOf(offered);
        }
    }

    // ---- (a) / (b) — the two IMPOSSIBLE verdicts fall back -----------------------------------------------------

    /**
     * (a) — no pool: the pay-in-advance transaction is built on the TOKEN output (never U) and transmitted, in
     * the same check. ONE row: {@code LiquidateAndPayInAdvance}, the prefix, then exactly the row an ANTICIPATE
     * market records for the loan — the F1 bracket included. No trace of the abandoned convert.
     */
    @Test
    void aNoPoolFallsBackToPayInAdvanceFromTheTokenOutputInTheSameCheck() throws Exception {
        Scenario scenario = tokenPrincipalConvertBond(1);
        ThrowingConvertRouter router = new ThrowingConvertRouter(new ConvertLiquidationRouter.NoPoolException(NO_POOL));

        Run run = stage(cba(CAP), scenario, router);
        Run anticipate = stage(anticipate(PRINCIPAL_TOKEN, CAP), scenario,
                new ThrowingConvertRouter(new IllegalStateException("an ANTICIPATE market never asks")));

        LiquidationDecision row = onlyRow(run);
        LiquidationDecision expected = onlyRow(anticipate);
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, row.outcome(), row.detail());
        assertEquals("SUBMITTED", row.reason());
        assertEquals("LiquidateAndPayInAdvance", row.variant());
        assertTrue(row.detail().startsWith(prefix("NO_MINSWAP_POOL")), row.detail());
        assertTrue(row.detail().contains("[REPLACED (F1, token principal): acquired"), row.detail());
        assertEquals(prefix("NO_MINSWAP_POOL") + expected.detail(), row.detail(),
                "the pay-in-advance an ANTICIPATE market would have made, under the prefix");
        assertEquals(expected.txHash(), row.txHash());

        Set<String> inputs = inputsOf(row);
        assertTrue(inputs.contains(ref(TOKEN_WALLET_UTXO)), "funded from the token output: " + inputs);
        assertFalse(inputs.contains(ref(U_UTXO)), "never from U: " + inputs);

        String loan = scenario.loanRef();
        assertTrue(run.messages(Level.INFO).contains(fallbackLine(loan, "NO_MINSWAP_POOL", NO_POOL)),
                run.messages(Level.INFO).toString());
        assertTrue(run.messages(Level.INFO).contains(loan + " is a PAY-IN-ADVANCE liquidation: the bot PAYS the "
                + "lender in " + PRINCIPAL_TOKEN.toUnit() + " and acquires the collateral, rather than earning a fee"));
        assertNoConvertTrace(run, loan);
        assertEquals(1, router.calls());
        assertEquals(1, run.convertBuilds());
        assertEquals(1, run.submitted().size());
        assertEquals(WorkResult.SUBMITTED, run.result());
    }

    /** (b) — a pool too thin: the same fallback, its cause {@code POOL_TOO_THIN}. */
    @Test
    void aPoolTooThinFallsBackToPayInAdvance() throws Exception {
        Scenario scenario = tokenPrincipalConvertBond(1);
        ThrowingConvertRouter router = new ThrowingConvertRouter(
                new ConvertOrderPlan.RefusedException(ConvertOrderPlan.Refusal.POOL_TOO_THIN, "thin fixture"));

        Run run = stage(cba(CAP), scenario, router);

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, row.outcome(), row.detail());
        assertEquals("LiquidateAndPayInAdvance", row.variant());
        assertTrue(row.detail().startsWith(prefix("POOL_TOO_THIN")), row.detail());
        assertTrue(row.detail().contains("[REPLACED (F1, token principal): acquired"), row.detail());
        assertTrue(inputsOf(row).contains(ref(TOKEN_WALLET_UTXO)));
        assertFalse(inputsOf(row).contains(ref(U_UTXO)));
        assertTrue(run.messages(Level.INFO).contains(fallbackLine(scenario.loanRef(), "POOL_TOO_THIN",
                "POOL_TOO_THIN: thin fixture")), run.messages(Level.INFO).toString());
        assertNoConvertTrace(run, scenario.loanRef());
        assertEquals(1, router.calls());
        assertEquals(1, run.submitted().size());
        assertEquals(WorkResult.SUBMITTED, run.result());
    }

    // ---- (c) — every other convert outcome is refused exactly as CONVERT refuses it ------------------------------

    static Stream<Arguments> nonTriggers() {
        List<Arguments> cases = new ArrayList<>();
        cases.add(Arguments.of("UnprofitableException", (Supplier<RuntimeException>) () ->
                new ConvertLiquidationRouter.UnprofitableException(ConvertAssessment.refused(ConvertExclusion.NOT_ARMED),
                        "convert refused as NOT_ARMED: unprofitable fixture")));
        for (ConvertOrderPlan.Refusal refusal : ConvertOrderPlan.Refusal.values()) {
            if (refusal != ConvertOrderPlan.Refusal.POOL_TOO_THIN) {
                cases.add(Arguments.of("ConvertOrderPlan " + refusal, (Supplier<RuntimeException>) () ->
                        new ConvertOrderPlan.RefusedException(refusal, "plan fixture")));
            }
        }
        cases.add(Arguments.of("LOOKUP_FAILED", (Supplier<RuntimeException>) () ->
                new MinswapPoolResolver.RefusedException(MinswapPoolResolver.Refusal.LOOKUP_FAILED,
                        "the pool index query failed: db fixture", new IllegalStateException("db fixture"))));
        cases.add(Arguments.of("AMBIGUOUS_POOL", (Supplier<RuntimeException>) () ->
                new MinswapPoolResolver.RefusedException(MinswapPoolResolver.Refusal.AMBIGUOUS_POOL,
                        "index integrity: two pools fixture")));
        cases.add(Arguments.of("FeedWindowException", (Supplier<RuntimeException>) () ->
                new ConvertLiquidationRouter.FeedWindowException("window fixture")));
        cases.add(Arguments.of("an oracle out-ref not yet visible", (Supplier<RuntimeException>) () ->
                new IllegalStateException("wrapped", new OracleReferenceInputProbe.OracleReferenceInputNotLiveException(
                        "out-ref fixture not yet visible", true))));
        cases.add(Arguments.of("IllegalStateException", (Supplier<RuntimeException>) () ->
                new IllegalStateException("broken fixture")));
        return cases.stream();
    }

    /**
     * (c) — the row equals what a {@code CONVERT} market records for the same throw (outcome, reason, variant,
     * detail; no prefix), every line the candidate leaves is the CONVERT market's at the same level (bar the
     * CONVERT announcement, which a {@code CONVERT_BEFORE_ANTICIPATE} market makes only once U is designated),
     * there is no PAY-IN-ADVANCE line, and nothing is transmitted.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("nonTriggers")
    void everyOtherConvertOutcomeIsRefusedExactlyAsAConvertMarketRefusesIt(String name,
                                                                          Supplier<RuntimeException> failure) {
        Scenario scenario = tokenPrincipalConvertBond(1);
        String loan = scenario.loanRef();

        Run convert = stage(convert(), scenario, new ThrowingConvertRouter(failure.get()));
        ThrowingConvertRouter router = new ThrowingConvertRouter(failure.get());
        Run cba = stage(cba(CAP), scenario, router);

        LiquidationDecision expected = onlyRow(convert);
        LiquidationDecision row = onlyRow(cba);
        assertEquals(expected.outcome(), row.outcome(), name);
        assertEquals(expected.reason(), row.reason(), name);
        assertEquals("LiquidateAndConvert", row.variant(), name);
        assertEquals(expected.variant(), row.variant(), name);
        assertEquals(expected.detail(), row.detail(), name);
        assertFalse(row.detail().startsWith("fallback to pay-in-advance"), row.detail());

        String announcement = loan + " is a CONVERT liquidation (LiquidateAndConvert): the bot creates a Minswap "
                + "order, fronts nothing, and keeps the liquidation fee in collateral";
        List<String> expectedLines = convert.events().stream()
                .filter(event -> event.getFormattedMessage().contains(loan))
                .filter(event -> !event.getFormattedMessage().equals(announcement))
                .map(event -> event.getLevel() + " " + event.getFormattedMessage()).toList();
        List<String> lines = cba.events().stream()
                .filter(event -> event.getFormattedMessage().contains(loan))
                .map(event -> event.getLevel() + " " + event.getFormattedMessage()).toList();
        assertFalse(expectedLines.isEmpty(), name);
        assertEquals(expectedLines, lines, name);
        assertTrue(all(cba).stream().noneMatch(line -> line.contains("PAY-IN-ADVANCE")
                || line.contains("falls back to pay-in-advance")), all(cba).toString());

        assertEquals(1, router.calls(), name);
        assertEquals(0, cba.plainBuilds(), name);
        assertTrue(cba.submitted().isEmpty(), name);
        assertEquals(WorkResult.DEFINITE_FAILURE, cba.result(), name);
    }

    // ---- (d) — D12: U is designated after the pool verdict -------------------------------------------------------

    /** (d) — no U, and no pool: the fallback needs no U, so it builds; there is no WALLET_ROLE_MISSING row. */
    @Test
    void aMissingUNeverHidesAFallbackThatNeedsNoU() {
        Scenario scenario = tokenPrincipalConvertBond(1);

        Run run = stage(cba(CAP), List.of(scenario), wallet(TOKEN_WALLET_UTXO),
                new ThrowingConvertRouter(new ConvertLiquidationRouter.NoPoolException(NO_POOL)));

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, row.outcome(), row.detail());
        assertTrue(row.detail().startsWith(prefix("NO_MINSWAP_POOL")), row.detail());
        assertEquals(1, run.submitted().size());
        assertTrue(all(run).stream().noneMatch(line -> line.contains("WALLET_ROLE_MISSING")
                || line.contains("role U is missing")), all(run).toString());
    }

    /**
     * (d) — no U, and a convert that IS possible: designated()'s ONE {@code WALLET_ROLE_MISSING} row, raised
     * when the router asks for U; no prefix, no pay-in-advance, no ERROR line.
     */
    @Test
    void aMissingUOnAPossibleConvertIsOneWalletRoleMissingRowAndNoFallback() throws Exception {
        Scenario scenario = tokenPrincipalConvertBond(1);
        BuildingConvertRouter router = new BuildingConvertRouter(k(), ORDER_LOVELACE);

        Run run = stage(cba(CAP), List.of(scenario), wallet(TOKEN_WALLET_UTXO), router);

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertEquals("WALLET_ROLE_MISSING", row.reason());
        assertTrue(row.detail().startsWith("role U is missing: "), row.detail());
        assertEquals("LiquidateAndConvert", row.variant());
        assertTrue(at(run, Level.ERROR).isEmpty(), "no ERROR line: " + at(run, Level.ERROR));
        assertTrue(all(run).stream().noneMatch(line -> line.contains("PAY-IN-ADVANCE")
                || line.contains("falls back to pay-in-advance")), all(run).toString());
        assertEquals(1, router.calls(), "the router asked for U: that is when it was found missing");
        assertTrue(router.offered().isEmpty());
        assertTrue(run.submitted().isEmpty());
        assertEquals(WorkResult.DEFINITE_FAILURE, run.result());
    }

    /** (d) — U smaller than the order: {@code WALLET_INPUT_TOO_SMALL} naming U, no fallback. */
    @Test
    void aUTooSmallForThePossibleConvertIsRefusedByNameAndNeverFallsBack() throws Exception {
        Scenario scenario = tokenPrincipalConvertBond(1);
        BuildingConvertRouter router = new BuildingConvertRouter(k(), BigInteger.valueOf(500_000_000L));

        Run run = stage(cba(CAP), scenario, router);

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertEquals("WALLET_INPUT_TOO_SMALL", row.reason());
        assertTrue(row.detail().startsWith("role U, wallet output " + ref(U_UTXO) + ", holds 200000000 lovelace"),
                row.detail());
        assertEquals("LiquidateAndConvert", row.variant());
        assertTrue(all(run).stream().noneMatch(line -> line.contains("PAY-IN-ADVANCE")
                || line.contains("falls back to pay-in-advance")), all(run).toString());
        assertTrue(run.submitted().isEmpty());
    }

    // ---- (e) — D5b: convert switched off ---------------------------------------------------------------------

    /** (e) — {@code loans.liquidation.convert.enabled=false}: falls back {@code CONVERT_DISABLED}, nothing built. */
    @Test
    void convertSwitchedOffFallsBackBeforeAnyConvertBuild() {
        Scenario scenario = tokenPrincipalConvertBond(1);
        DisabledConvertRouter router = new DisabledConvertRouter();

        Run run = stage(cba(CAP), scenario, router);

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, row.outcome(), row.detail());
        assertEquals("LiquidateAndPayInAdvance", row.variant());
        assertTrue(row.detail().startsWith(prefix("CONVERT_DISABLED")), row.detail());
        assertTrue(run.messages(Level.INFO).contains(fallbackLine(scenario.loanRef(), "CONVERT_DISABLED",
                "loans.liquidation.convert.enabled is false")), run.messages(Level.INFO).toString());
        assertEquals(0, router.calls.get(), "the router's build is never called");
        assertEquals(0, run.convertBuilds());
        assertEquals(1, run.submitted().size());
    }

    /** (e) — a CONVERT market never asks whether convert is enabled: its convert is built and gated as before. */
    @Test
    void aConvertMarketNeverAsksWhetherConvertIsEnabled() throws Exception {
        Scenario scenario = tokenPrincipalConvertBond(1);
        DisabledConvertRouter router = new DisabledConvertRouter();

        Run run = stage(convert(), scenario, router);

        LiquidationDecision row = onlyRow(run);
        assertEquals("IllegalStateException", row.reason(), "the build was asked, and its throw recorded");
        assertEquals(1, router.calls.get());
        assertTrue(all(run).stream().noneMatch(line -> line.contains("falls back")), all(run).toString());
    }

    // ---- (f) — D11: the fallback's economics are pay-in-advance's -------------------------------------------------

    /**
     * (f) — a loan whose liquidation fee is 1 per mille: its F1 floor (acquired − outlay − fees) is negative, so
     * the fallback is UNPROFITABLE, and its detail is the prefix followed by EXACTLY the detail the same loan
     * records in an ANTICIPATE market — the F1 bracket included.
     */
    @Test
    void aFallbackWhoseF1FloorIsNegativeIsRefusedUnprofitableExactlyAsAnticipate() {
        Scenario scenario = smallFeeTokenPrincipalConvertBond();

        Run run = stage(cba(CAP), scenario,
                new ThrowingConvertRouter(new ConvertLiquidationRouter.NoPoolException(NO_POOL)));
        Run anticipate = stage(anticipate(PRINCIPAL_TOKEN, CAP), scenario,
                new ThrowingConvertRouter(new IllegalStateException("an ANTICIPATE market never asks")));

        LiquidationDecision expected = onlyRow(anticipate);
        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.UNPROFITABLE, expected.outcome(), expected.detail());
        assertTrue(expected.detail().contains("[REPLACED (F1, token principal): acquired"), expected.detail());
        assertTrue(expected.detail().contains("= floor -"), "the F1 floor is negative: " + expected.detail());
        assertEquals(LiquidationDecision.Outcome.UNPROFITABLE, row.outcome());
        assertEquals(expected.reason(), row.reason());
        assertEquals("LiquidateAndPayInAdvance", row.variant());
        assertEquals(prefix("NO_MINSWAP_POOL") + expected.detail(), row.detail());
        assertEquals(LiquidationExecutor.SubmitVeto.NOT_PROFITABLE.name(), row.submitVeto());
        assertTrue(run.submitted().isEmpty());
    }

    // ---- (g) — D7: the fallback is capped, balanced and funded as ANTICIPATE ------------------------------------

    /** (g) — the cap below the payout: {@code ABOVE_MARKET_CAP}, ANTICIPATE's row under the prefix. */
    @Test
    void aFallbackAboveTheCapIsRefusedAboveMarketCapUnderThePrefix() {
        Scenario scenario = tokenPrincipalConvertBond(1);

        Run run = stage(cba(1L), scenario, new ThrowingConvertRouter(new ConvertLiquidationRouter.NoPoolException(NO_POOL)));
        Run anticipate = stage(anticipate(PRINCIPAL_TOKEN, 1L), scenario,
                new ThrowingConvertRouter(new IllegalStateException("an ANTICIPATE market never asks")));

        LiquidationDecision expected = onlyRow(anticipate);
        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertTrue(row.reason().startsWith("ABOVE_MARKET_CAP: "), row.reason());
        assertEquals(expected.reason(), row.reason(), "the reason is untouched by the prefix");
        assertEquals(prefix("NO_MINSWAP_POOL") + expected.detail(), row.detail());
        assertEquals("LiquidateAndPayInAdvance", row.variant());
        assertTrue(run.submitted().isEmpty());
    }

    /** (g) — a token output holding less than the payout: {@code INSUFFICIENT_BALANCE} under the prefix. */
    @Test
    void aFallbackTheTokenOutputCannotFundIsRefusedInsufficientBalanceUnderThePrefix() {
        Scenario scenario = tokenPrincipalConvertBond(1);
        Utxo shortToken = LoanFixtures.utxo("e9".repeat(32), 0, ACCOUNT.baseAddress(),
                List.of(Amount.lovelace(BigInteger.valueOf(60_000_000L)),
                        Amount.asset(PRINCIPAL_TOKEN.toUnit(), BigInteger.valueOf(10_000_000L))),
                null);

        Run run = stage(cba(CAP), List.of(scenario), wallet(U_UTXO, shortToken),
                new ThrowingConvertRouter(new ConvertLiquidationRouter.NoPoolException(NO_POOL)));

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertTrue(row.reason().startsWith("INSUFFICIENT_BALANCE: "), row.reason());
        assertTrue(row.detail().startsWith(prefix("NO_MINSWAP_POOL") + "INSUFFICIENT_BALANCE: "), row.detail());
        assertTrue(run.submitted().isEmpty());
    }

    /** (g) — no token output at all: {@code WALLET_ROLE_MISSING} for the token role, under the prefix. */
    @Test
    void aFallbackWithNoTokenOutputIsRefusedWalletRoleMissingUnderThePrefix() {
        Scenario scenario = tokenPrincipalConvertBond(1);

        Run run = stage(cba(CAP), List.of(scenario), wallet(U_UTXO),
                new ThrowingConvertRouter(new ConvertLiquidationRouter.NoPoolException(NO_POOL)));

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertEquals("WALLET_ROLE_MISSING", row.reason());
        assertTrue(row.detail().startsWith(prefix("NO_MINSWAP_POOL") + "role TOKEN " + PRINCIPAL_TOKEN.toUnit()
                + " is missing: "), row.detail());
        assertEquals("LiquidateAndPayInAdvance", row.variant());
        assertTrue(run.submitted().isEmpty());
    }

    // ---- (h) — D8: shadow ------------------------------------------------------------------------------------

    /** (h) — a SHADOW node: WOULD_SUBMIT {@code MODE_NOT_LIVE} under the prefix, dumped as pay-in-advance. */
    @Test
    void aFallbackOnAShadowNodeIsAWouldSubmitDumpedAsPayInAdvance() {
        Scenario scenario = tokenPrincipalConvertBond(1);

        Run run = stage(cba(Mode.SHADOW, null, PRINCIPAL_TOKEN, CAP), scenario,
                new ThrowingConvertRouter(new ConvertLiquidationRouter.NoPoolException(NO_POOL)));

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, row.outcome(), row.detail());
        assertEquals("MODE_NOT_LIVE", row.submitVeto());
        assertEquals("LiquidateAndPayInAdvance", row.variant());
        assertTrue(row.detail().startsWith(prefix("NO_MINSWAP_POOL")), row.detail());
        assertTrue(run.messages(Level.INFO).stream().anyMatch(line -> line.startsWith(
                "SHADOW TX " + scenario.loanRef() + " variant=LiquidateAndPayInAdvance held-by=MODE_NOT_LIVE")),
                run.messages(Level.INFO).toString());
        assertTrue(run.submitted().isEmpty());
        assertEquals(WorkResult.WOULD_SUBMIT, run.result());
    }

    /** (h) — a SHADOW market on a LIVE node: WOULD_SUBMIT {@code MARKET_NOT_LIVE} under the prefix. */
    @Test
    void aFallbackInAShadowMarketIsAWouldSubmitMarketNotLive() {
        Scenario scenario = tokenPrincipalConvertBond(1);

        Run run = stage(cba(Mode.LIVE, Mode.SHADOW, PRINCIPAL_TOKEN, CAP), scenario,
                new ThrowingConvertRouter(new ConvertLiquidationRouter.NoPoolException(NO_POOL)));

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, row.outcome(), row.detail());
        assertEquals("MARKET_NOT_LIVE", row.submitVeto());
        assertEquals("LiquidateAndPayInAdvance", row.variant());
        assertTrue(row.detail().startsWith(prefix("NO_MINSWAP_POOL")), row.detail());
        assertTrue(run.messages(Level.INFO).stream().anyMatch(line -> line.startsWith(
                "SHADOW TX " + scenario.loanRef() + " variant=LiquidateAndPayInAdvance held-by=MARKET_NOT_LIVE")),
                run.messages(Level.INFO).toString());
        assertTrue(run.submitted().isEmpty());
    }

    // ---- (i) / (j) — several candidates in one check -----------------------------------------------------------

    /**
     * A token output of the principal that the canned convert {@code K} does NOT spend — {@code K} spends
     * {@link RouteStageHarness#TOKEN_WALLET_UTXO} — so an accepted convert marks no role output but U's, and U
     * is never marked (D3).
     */
    private static final Utxo OTHER_TOKEN_OUTPUT = LoanFixtures.utxo("e8".repeat(32), 0, ACCOUNT.baseAddress(),
            List.of(Amount.lovelace(BigInteger.valueOf(60_000_000L)),
                    Amount.asset(PRINCIPAL_TOKEN.toUnit(), BigInteger.valueOf(100_000_000L))),
            null);

    /**
     * (i) — the walkthrough: A and B convert on U (both transmitted, D3), C's pool is too thin and it pays in
     * advance from the token output, under the prefix. Three transmissions, one row each.
     */
    @Test
    void threeCandidatesTwoConvertOnUAndTheThinOneFallsBackFromTheTokenOutput() throws Exception {
        Scenario a = tokenPrincipalConvertBond(1);
        Scenario b = tokenPrincipalConvertBond(2);
        Scenario c = tokenPrincipalConvertBond(3);
        PerLoanConvertRouter router = new PerLoanConvertRouter(k(), Set.of(c.loanRef()));

        Run run = stage(cba(CAP), List.of(a, b, c), wallet(U_UTXO, OTHER_TOKEN_OUTPUT), router);

        assertEquals(3, run.log().newestFirst(10).size(), run.log().newestFirst(10).toString());
        for (Scenario converted : List.of(a, b)) {
            LiquidationDecision row = rowFor(run, converted);
            assertEquals(LiquidationDecision.Outcome.SUBMITTED, row.outcome(), row.detail());
            assertEquals("LiquidateAndConvert", row.variant());
            assertFalse(row.detail().startsWith("fallback"), row.detail());
            assertTrue(row.detail().endsWith(LiquidationExecutor.U_STAYS_NOTE), "funded from U: " + row.detail());
        }
        assertEquals(List.of(U_UTXO, U_UTXO), router.offered(), "A and B both on U");

        LiquidationDecision thin = rowFor(run, c);
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, thin.outcome(), thin.detail());
        assertEquals("LiquidateAndPayInAdvance", thin.variant());
        assertTrue(thin.detail().startsWith(prefix("POOL_TOO_THIN")), thin.detail());
        assertTrue(inputsOf(thin).contains(ref(OTHER_TOKEN_OUTPUT)), "C from the token output");
        assertFalse(inputsOf(thin).contains(ref(U_UTXO)), "C never from U");

        assertEquals(3, run.submitted().size(), "three transmissions");
        assertEquals(WorkResult.SUBMITTED, run.result());
    }

    /** (j) — a CONVERT market with two buildable loans: both transmitted on U, nothing falls back. */
    @Test
    void aConvertMarketTransmitsTwoBuildableLoansBothOnU() throws Exception {
        Scenario a = tokenPrincipalConvertBond(1);
        Scenario b = tokenPrincipalConvertBond(2);
        PerLoanConvertRouter router = new PerLoanConvertRouter(k(), Set.of());

        Run run = stage(convert(), List.of(a, b), wallet(U_UTXO, OTHER_TOKEN_OUTPUT), router);

        for (Scenario converted : List.of(a, b)) {
            LiquidationDecision row = rowFor(run, converted);
            assertEquals(LiquidationDecision.Outcome.SUBMITTED, row.outcome(), row.detail());
            assertEquals("LiquidateAndConvert", row.variant());
        }
        assertEquals(List.of(U_UTXO, U_UTXO), router.offered());
        assertEquals(2, run.submitted().size());
        assertTrue(all(run).stream().noneMatch(line -> line.contains("falls back")), all(run).toString());
    }

    // ---- (k) — D14: a refusal before the convert verdict is the convert route's ----------------------------------

    /** (k) — refused {@code ORACLE_FEED_NOT_YET_VALID} before any build: {@code LiquidateAndConvert}, no prefix. */
    @Test
    void aCandidateWaitingForTheChainIsLabelledByThePlannedConvertRoute() {
        Scenario scenario = tokenPrincipalConvertBond(1);
        ThrowingConvertRouter router = new ThrowingConvertRouter(new ConvertLiquidationRouter.NoPoolException(NO_POOL));

        Run run = run(cba(CAP), List.of(scenario), wallet(U_UTXO, TOKEN_WALLET_UTXO), futureOracles(), router,
                accepting(ACCEPTED), SubmitFence.OPEN);

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.REFUSED, row.outcome());
        assertEquals("ORACLE_FEED_NOT_YET_VALID", row.reason());
        assertEquals("LiquidateAndConvert", row.variant());
        assertFalse(row.detail().startsWith("fallback"), row.detail());
        assertEquals(0, router.calls());
    }

    // ---- (l) — an ADA principal falls back onto U --------------------------------------------------------------

    /** (l) — an ADA principal's fallback is funded from U, as its ANTICIPATE pay-in-advance is. */
    @Test
    void anAdaPrincipalFallbackIsFundedFromU() throws Exception {
        Scenario scenario = adaPrincipalTokenCollateralConvertBond(1);

        Run run = stage(cba(Mode.LIVE, null, AssetType.ada(), CAP), scenario,
                new ThrowingConvertRouter(new ConvertLiquidationRouter.NoPoolException(NO_POOL)));
        Run anticipate = stage(anticipate(AssetType.ada(), CAP), scenario,
                new ThrowingConvertRouter(new IllegalStateException("an ANTICIPATE market never asks")));

        LiquidationDecision expected = onlyRow(anticipate);
        LiquidationDecision row = onlyRow(run);
        assertEquals("LiquidateAndPayInAdvance", row.variant());
        assertTrue(row.detail().startsWith(prefix("NO_MINSWAP_POOL")), row.detail());
        assertEquals(prefix("NO_MINSWAP_POOL") + expected.detail(), row.detail());
        assertEquals(expected.outcome(), row.outcome());
        assertTrue(inputsOf(row).contains(ref(U_UTXO)), "funded from U: " + inputsOf(row));
    }

    // ---- (m) — the production shape ----------------------------------------------------------------------------

    /**
     * (m) — a REAL {@link ConvertLiquidationRouter} with no pool address configured (a fresh
     * {@code LoansConfiguration}) and a real enabled economics gate: it throws its own {@code NoPoolException},
     * before asking for U, and the candidate falls back and is built.
     */
    @Test
    void aRealRouterWithNoPoolAddressFallsBackAndBuilds() throws Exception {
        Scenario scenario = tokenPrincipalConvertBond(1);
        ConvertLiquidationRouter real = new ConvertLiquidationRouter(LoanFixtures.registry(),
                new AppConfig.LoansConfiguration(), null, null, economics(true), null, LoanFixtures.converters(),
                LoanFixtures.NETWORK);

        Run run = stage(cba(CAP), scenario, real);

        LiquidationDecision row = onlyRow(run);
        String message = "convert unavailable: no Minswap pool address configured for this network, so there is "
                + "no pool to swap " + COLLATERAL_TOKEN.toUnit() + " against; set loans.minswap.pool-address, or "
                + "set this market to action: CONVERT_BEFORE_ANTICIPATE with a cap if the loan should still be "
                + "liquidated";
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, row.outcome(), row.detail());
        assertEquals("LiquidateAndPayInAdvance", row.variant());
        assertTrue(row.detail().startsWith(prefix("NO_MINSWAP_POOL")), row.detail());
        assertTrue(run.messages(Level.INFO).contains(fallbackLine(scenario.loanRef(), "NO_MINSWAP_POOL", message)),
                run.messages(Level.INFO).toString());
        assertTrue(inputsOf(row).contains(ref(TOKEN_WALLET_UTXO)));
        assertNoConvertTrace(run, scenario.loanRef());
        assertEquals(1, run.convertBuilds());
        assertEquals(1, run.submitted().size());
    }

    /**
     * (m) — the router the {@code YaciConfig.convertLiquidationRouter} bean method builds, over an economics gate
     * with conversion OFF: it answers {@code convertEnabled() == false}, and a candidate on it falls back
     * {@code CONVERT_DISABLED} without its build being called.
     */
    @Test
    void theBeanMethodsRouterOverADisabledGateFallsBackWithoutBuilding() {
        AppConfig.Network network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        AppConfig.LiquidationConfiguration configuration = cba(CAP);
        ConvertLiquidationRouter bean = new YaciConfig().convertLiquidationRouter(LoanFixtures.registry(),
                new AppConfig.LoansConfiguration(), null, economics(false), null, configuration,
                LoanFixtures.converters(), network);
        assertFalse(bean.convertEnabled());

        Scenario scenario = tokenPrincipalConvertBond(1);
        Run run = stage(configuration, scenario, bean);

        LiquidationDecision row = onlyRow(run);
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, row.outcome(), row.detail());
        assertTrue(row.detail().startsWith(prefix("CONVERT_DISABLED")), row.detail());
        assertTrue(run.messages(Level.INFO).contains(fallbackLine(scenario.loanRef(), "CONVERT_DISABLED",
                "loans.liquidation.convert.enabled is false")), run.messages(Level.INFO).toString());
        assertEquals(0, run.convertBuilds(), "the bean's build is never called");
        assertEquals(1, run.submitted().size());
    }

    // ---- fixtures this class needs beyond the harness ------------------------------------------------------------

    // The harness's oracle tokens and credentials, restated: the loan datum names these, so a scenario or an
    // oracle client built here must use exactly them.
    private static final AssetType ORACLE_TOKEN = new AssetType("b0".repeat(28), "4f52434c");
    private static final AssetType PRINCIPAL_ORACLE_TOKEN = new AssetType("e0".repeat(28), "504f5243");
    private static final long FEED_VALID_TO = NOW + 600_000L;

    private static OraclePriceFeed principalFeed(long validFrom) {
        return OraclePriceFeed.priceDataCharlie(PRINCIPAL_TOKEN, BigInteger.ONE, BigInteger.ONE, validFrom,
                FEED_VALID_TO);
    }

    private static OraclePriceFeed collateralFeed(long validFrom) {
        return OraclePriceFeed.priceDataCharlie(COLLATERAL_TOKEN, BigInteger.valueOf(50), BigInteger.ONE, validFrom,
                FEED_VALID_TO);
    }

    /** The harness's two feeds, but opening two minutes after {@code NOW}: the chain has not reached them. */
    private static FluidOracleClient futureOracles() {
        long future = NOW + 120_000L;
        return new RouteStageHarness.LegPricingOracleClient(List.of(
                LoanFixtures.charli3(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_TOKEN, "a1".repeat(28), principalFeed(future),
                        LoanFixtures.input("8a".repeat(32), 0), LoanFixtures.input("8b".repeat(32), 0),
                        LoanFixtures.input("8c".repeat(32), 0)),
                LoanFixtures.charli3(COLLATERAL_TOKEN, ORACLE_TOKEN, "a0".repeat(28), collateralFeed(future),
                        LoanFixtures.input("9a".repeat(32), 0), LoanFixtures.input("9b".repeat(32), 0),
                        LoanFixtures.input("9c".repeat(32), 0))));
    }

    /**
     * {@link RouteStageHarness#tokenPrincipalConvertBond}'s loan, its bond converting at 1 per mille instead of
     * 500: the bot acquires 1,000,000 TOK (50 ADA) and pays out 999,000 TOK's worth (49.95 ADA), so the F1 floor
     * is 50,000 lovelace less the transaction fee and riders — negative.
     */
    private static Scenario smallFeeTokenPrincipalConvertBond() {
        String loanId = "a1b2c3d4e5f60099";
        LoanDatum datum = LoanFixtures.loanDatum(PRINCIPAL_TOKEN, PRINCIPAL_ORACLE_TOKEN,
                BigInteger.valueOf(100_000_000), BigInteger.valueOf(1000),
                LoanFixtures.tokenCollateral(COLLATERAL_TOKEN, ORACLE_TOKEN), NOW - 30L * 24 * 3_600_000L,
                LoanFixtures.liquidation(), new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo("99".repeat(32), 0, loanId, datum, 2_000_000L,
                List.of(LoanFixtures.token(COLLATERAL_TOKEN, 1_000_000L)));
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo("98".repeat(32), 0, loanId,
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.ONE,
                        LoanFixtures.inlineKeyStakeCredential("33".repeat(28)), PRINCIPAL_TOKEN),
                2_000_000L);
        return new Scenario(loan, bond, LoanFixtures.assess(bond.bond(), loan.loan(), principalFeed(NOW - 60_000L),
                collateralFeed(NOW - 60_000L), NOW - 30_000L), true);
    }
}
