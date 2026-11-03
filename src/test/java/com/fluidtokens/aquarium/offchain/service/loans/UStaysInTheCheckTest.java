package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import com.fluidtokens.aquarium.offchain.model.loans.LiquidationDecision;
import com.fluidtokens.aquarium.offchain.model.loans.LoanDatum;
import com.fluidtokens.aquarium.offchain.model.loans.OraclePriceFeed;
import com.fluidtokens.aquarium.offchain.model.loans.RepaymentMode;
import com.fluidtokens.aquarium.offchain.service.SubmitFence;
import com.fluidtokens.aquarium.offchain.service.WorkResult;
import com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.BuildingConvertRouter;
import com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.Run;
import com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.Scenario;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.NOW;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.PRINCIPAL_TOKEN;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.TOKEN_WALLET_UTXO;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.U_UTXO;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.accepting;
import static com.fluidtokens.aquarium.offchain.service.loans.RouteStageHarness.adaPrincipalPlain;
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
 * FAB-194-2 — <b>U stays in the check</b> (Giovanni's D3, 2026-11-02). An accepted submit does not mean the
 * transaction lands, so every candidate funded from U — plain, convert or ADA-principal pay-in-advance — is
 * built and submitted on U in profit order, however many went before it in the same check. A token output
 * keeps FAB-186's rule: once an accepted (or node-shadow) body spends it, the next candidate on it is skipped.
 *
 * <p>The expected cost, accepted by Giovanni: a later candidate on U is usually refused by a mempool that
 * already holds the first (CCL traps 12a, 13) — one not-accepted INFO line, never an ERROR — and a backend
 * that has not seen the first can accept it too, which is what a U-funded SUBMITTED row's
 * {@link LiquidationExecutor#U_STAYS_NOTE} says.
 *
 * <p>Every run is one stage call through {@link RouteStageHarness}, on a LIVE node with an accepting
 * submitter unless the test says otherwise.
 */
class UStaysInTheCheckTest {

    private static final long AMPLE_CAP = 1_000_000_000L;

    /** What the CONVERT stand-in asks the executor's selector for: a token collateral's order overhead. */
    private static final BigInteger ORDER_LOVELACE = BigInteger.valueOf(4_000_000L);

    private static final String ACCEPTED = "ab".repeat(32);

    private static final String U_REF = U_UTXO.getTxHash() + "#" + U_UTXO.getOutputIndex();

    private static final String TOKEN_REF = TOKEN_WALLET_UTXO.getTxHash() + "#" + TOKEN_WALLET_UTXO.getOutputIndex();

    private static AppConfig.LiquidationConfiguration anticipate(Mode nodeMode, AssetType principal, Mode marketMode) {
        return node(nodeMode, market(principal, marketMode, Action.ANTICIPATE, AMPLE_CAP));
    }

    private static Run stage(AppConfig.LiquidationConfiguration configuration, List<Scenario> scenarios,
                             ConvertLiquidationRouter router, LiquidationExecutor.TransactionSubmitter submitter) {
        return run(configuration, scenarios, wallet(U_UTXO, TOKEN_WALLET_UTXO), oracles(), router, submitter,
                SubmitFence.OPEN);
    }

    private static Run stage(AppConfig.LiquidationConfiguration configuration, List<Scenario> scenarios,
                             ConvertLiquidationRouter router) {
        return stage(configuration, scenarios, router, accepting(ACCEPTED));
    }

    /** The call's rows, in the order they were recorded. */
    private static List<LiquidationDecision> oldestFirst(Run run) {
        List<LiquidationDecision> rows = new ArrayList<>(run.log().newestFirst(10));
        Collections.reverse(rows);
        return rows;
    }

    private static List<LiquidationDecision.Outcome> outcomes(Run run) {
        return oldestFirst(run).stream().map(LiquidationDecision::outcome).toList();
    }

    private static List<TransactionInput> inputsOf(byte[] signed) throws Exception {
        return Transaction.deserialize(signed).getBody().getInputs();
    }

    private static boolean spends(byte[] signed, Utxo output) throws Exception {
        return inputsOf(signed).contains(new TransactionInput(output.getTxHash(), output.getOutputIndex()));
    }

    /** Whether any line says a candidate was skipped because U already went out. */
    private static boolean anyUSkip(Run run) {
        return run.events().stream().anyMatch(event -> event.getFormattedMessage().contains("(role U) already went out"));
    }

    /** The INFO line a candidate designated to the token output is skipped with once that output is used. */
    private static String tokenSkip(Scenario scenario) {
        return "liquidation of " + scenario.loanRef() + " skipped: wallet output " + TOKEN_REF + " (role TOKEN "
                + PRINCIPAL_TOKEN.toUnit() + ") already went out in this check";
    }

    // ---- (a) two candidates funded from U are both built and transmitted, on U ------------------------

    /** (a) plain: two ADA-principal plain candidates — two builds, two transmissions, each spending U. */
    @Test
    void twoPlainCandidatesOnUAreBothBuiltAndTransmittedOnU() throws Exception {
        Run run = stage(node(Mode.LIVE), List.of(adaPrincipalPlain(1), adaPrincipalPlain(2)), null);

        assertEquals(2, run.plainBuilds(), "both are built: " + run.events());
        assertEquals(2, run.submitted().size(), "both are transmitted: " + run.events());
        assertTrue(spends(run.submitted().get(0), U_UTXO), inputsOf(run.submitted().get(0)).toString());
        assertTrue(spends(run.submitted().get(1), U_UTXO), inputsOf(run.submitted().get(1)).toString());
        assertEquals(List.of(LiquidationDecision.Outcome.SUBMITTED, LiquidationDecision.Outcome.SUBMITTED),
                outcomes(run));
        assertFalse(anyUSkip(run), run.events().toString());
        assertTrue(oldestFirst(run).getFirst().detail().endsWith(LiquidationExecutor.U_STAYS_NOTE),
                oldestFirst(run).getFirst().detail());
        assertEquals(WorkResult.SUBMITTED, run.result());
    }

    /**
     * (a) CONVERT market: two convert candidates. The stand-in's canned body is not built from the wallet, so
     * what proves U funded both is the selector: it was handed U on both calls.
     */
    @Test
    void twoConvertCandidatesOnUAreBothBuiltAndTransmittedAndTheSelectorHandsUToBoth() throws Exception {
        Transaction k = MarketActionRoutePinTest.k();
        BuildingConvertRouter router = new BuildingConvertRouter(k, ORDER_LOVELACE);

        Run run = stage(node(Mode.LIVE, market(PRINCIPAL_TOKEN, null, Action.CONVERT, null)),
                List.of(tokenPrincipalConvertBond(1), tokenPrincipalConvertBond(2)), router);

        assertEquals(2, run.convertBuilds(), "both are built: " + run.events());
        assertEquals(List.of(U_UTXO, U_UTXO), router.offered(), "the selector hands U to both");
        assertEquals(2, run.submitted().size(), "both are transmitted: " + run.events());
        assertEquals(List.of(LiquidationDecision.Outcome.SUBMITTED, LiquidationDecision.Outcome.SUBMITTED),
                outcomes(run));
        assertFalse(anyUSkip(run), run.events().toString());
        assertTrue(oldestFirst(run).getFirst().detail().endsWith(LiquidationExecutor.U_STAYS_NOTE),
                oldestFirst(run).getFirst().detail());
        assertEquals(WorkResult.SUBMITTED, run.result());
    }

    /**
     * {@code node(LIVE, markets)} with NO profit margin. The harness's ADA-principal pay-in-advance candidate
     * clears the absolute floor by 273,865 lovelace, under the harness's 1.5 ADA margin, so on the harness's
     * own node it is UNPROFITABLE and never reaches the submitter; everything else is the harness's node.
     */
    private static AppConfig.LiquidationConfiguration liveWithoutMargin(
            AppConfig.LiquidationConfiguration.Market... markets) {
        var configuration = new AppConfig.LiquidationConfiguration(Mode.LIVE, 60, 120, 30, BigInteger.ZERO, 200,
                node(Mode.LIVE).getReferenceScripts());
        configuration.setMarkets(List.of(markets));
        return configuration;
    }

    /** (a) ADA-principal ANTICIPATE: two pay-in-advance candidates funded from U, both transmitted on U. */
    @Test
    void twoAdaPrincipalAnticipateCandidatesOnUAreBothBuiltAndTransmittedOnU() throws Exception {
        Run run = stage(liveWithoutMargin(market(AssetType.ada(), null, Action.ANTICIPATE, AMPLE_CAP)),
                List.of(adaPrincipalTokenCollateralConvertBond(1), adaPrincipalTokenCollateralConvertBond(2)), null);

        assertEquals(2, run.submitted().size(), "both are built and transmitted: " + run.events());
        assertTrue(spends(run.submitted().get(0), U_UTXO), inputsOf(run.submitted().get(0)).toString());
        assertTrue(spends(run.submitted().get(1), U_UTXO), inputsOf(run.submitted().get(1)).toString());
        assertEquals(List.of(LiquidationDecision.Outcome.SUBMITTED, LiquidationDecision.Outcome.SUBMITTED),
                outcomes(run), oldestFirst(run).toString());
        assertTrue(oldestFirst(run).stream().allMatch(row -> "LiquidateAndPayInAdvance".equals(row.variant())),
                oldestFirst(run).toString());
        assertFalse(anyUSkip(run), run.events().toString());
        assertTrue(oldestFirst(run).getFirst().detail().endsWith(LiquidationExecutor.U_STAYS_NOTE),
                oldestFirst(run).getFirst().detail());
        assertEquals(WorkResult.SUBMITTED, run.result());
    }

    // ---- (b) a token output still goes out with its first accepted body (FAB-186) ----------------------

    /** (b) token-principal ANTICIPATE: two candidates on one token output — the second is skipped by name. */
    @Test
    void twoTokenPrincipalAnticipateCandidatesOnOneTokenOutputTheSecondIsSkipped() throws Exception {
        Scenario first = tokenPrincipalConvertBond(1);
        Scenario second = tokenPrincipalConvertBond(2);

        Run run = stage(anticipate(Mode.LIVE, PRINCIPAL_TOKEN, null), List.of(first, second), null);

        assertEquals(1, run.submitted().size(), "one transmission: " + run.events());
        assertTrue(spends(run.submitted().getFirst(), TOKEN_WALLET_UTXO),
                inputsOf(run.submitted().getFirst()).toString());
        List<LiquidationDecision> rows = oldestFirst(run);
        assertEquals(1, rows.size(), "no row for the skipped candidate: " + rows);
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, rows.getFirst().outcome());
        assertFalse(rows.getFirst().detail().contains("U stays"), rows.getFirst().detail());
        String skipped = rows.getFirst().loanUtxoRef().equals(first.loanRef()) ? tokenSkip(second) : tokenSkip(first);
        assertTrue(run.messages(Level.INFO).contains(skipped), "the skip names role TOKEN: " + run.messages(Level.INFO));
        assertEquals(WorkResult.SUBMITTED, run.result());
    }

    // ---- (c) a node-shadow WOULD_SUBMIT marks exactly what an accept marks -----------------------------

    /** (c) node SHADOW, U half: two U candidates — both built, two WOULD_SUBMIT rows, no U skip. */
    @Test
    void aNodeShadowWouldSubmitLeavesUInTheCheck() {
        Run run = stage(node(Mode.SHADOW), List.of(adaPrincipalPlain(1), adaPrincipalPlain(2)), null);

        assertEquals(2, run.plainBuilds(), "both are built: " + run.events());
        assertTrue(run.submitted().isEmpty(), "shadow transmits nothing");
        assertEquals(List.of(LiquidationDecision.Outcome.WOULD_SUBMIT, LiquidationDecision.Outcome.WOULD_SUBMIT),
                outcomes(run), oldestFirst(run).toString());
        assertTrue(oldestFirst(run).stream()
                        .allMatch(row -> LiquidationExecutor.SubmitVeto.MODE_NOT_LIVE.name().equals(row.submitVeto())),
                oldestFirst(run).toString());
        assertFalse(anyUSkip(run), run.events().toString());
        assertEquals(WorkResult.WOULD_SUBMIT, run.result());
    }

    /** (c) node SHADOW, token half: two candidates on one token output — the second is skipped by name. */
    @Test
    void aNodeShadowWouldSubmitStillTakesItsTokenOutputOutOfTheCheck() {
        Scenario first = tokenPrincipalConvertBond(1);
        Scenario second = tokenPrincipalConvertBond(2);

        Run run = stage(anticipate(Mode.SHADOW, PRINCIPAL_TOKEN, null), List.of(first, second), null);

        assertTrue(run.submitted().isEmpty(), "shadow transmits nothing");
        List<LiquidationDecision> rows = oldestFirst(run);
        assertEquals(1, rows.size(), "no row for the skipped candidate: " + rows);
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, rows.getFirst().outcome(), rows.getFirst().detail());
        assertEquals(LiquidationExecutor.SubmitVeto.MODE_NOT_LIVE.name(), rows.getFirst().submitVeto());
        String skipped = rows.getFirst().loanUtxoRef().equals(first.loanRef()) ? tokenSkip(second) : tokenSkip(first);
        assertTrue(run.messages(Level.INFO).contains(skipped), "the skip names role TOKEN: " + run.messages(Level.INFO));
        assertEquals(WorkResult.WOULD_SUBMIT, run.result());
    }

    // ---- (d) a market-shadow WOULD_SUBMIT marks nothing --------------------------------------------------

    /** (d) a market held at SHADOW on a LIVE node: the next candidate on the same token output is built. */
    @Test
    void aMarketShadowWouldSubmitMarksNothingAndTheNextCandidateOnTheSameTokenOutputIsBuilt() {
        Scenario first = tokenPrincipalConvertBond(1);
        Scenario second = tokenPrincipalConvertBond(2);

        Run run = stage(anticipate(Mode.LIVE, PRINCIPAL_TOKEN, Mode.SHADOW), List.of(first, second), null);

        assertTrue(run.submitted().isEmpty(), "a shadow market transmits nothing");
        List<LiquidationDecision> rows = oldestFirst(run);
        assertEquals(List.of(LiquidationDecision.Outcome.WOULD_SUBMIT, LiquidationDecision.Outcome.WOULD_SUBMIT),
                outcomes(run), "both are built and recorded: " + rows);
        assertTrue(rows.stream()
                        .allMatch(row -> LiquidationExecutor.SubmitVeto.MARKET_NOT_LIVE.name().equals(row.submitVeto())),
                rows.toString());
        assertFalse(run.messages(Level.INFO).contains(tokenSkip(first)), run.messages(Level.INFO).toString());
        assertFalse(run.messages(Level.INFO).contains(tokenSkip(second)), run.messages(Level.INFO).toString());
        assertEquals(WorkResult.WOULD_SUBMIT, run.result());
    }

    // ---- (e) the expected cost: the later candidate on U is refused, said once at INFO --------------------

    /** A submitter that accepts the first submission and refuses every later one with an HTTP 400. */
    private static LiquidationExecutor.TransactionSubmitter acceptingThenRefusing(String response) {
        AtomicInteger calls = new AtomicInteger();
        return bytes -> calls.getAndIncrement() == 0
                ? Result.success(ACCEPTED).withValue(ACCEPTED)
                : Result.<String>error(response).code(400);
    }

    /** (e) the second candidate on U is refused by the backend: one not-accepted INFO line, SUBMIT_FAILED, no ERROR. */
    @Test
    void aLaterCandidateOnURefusedByTheBackendIsOneNotAcceptedInfoLineAndNoError() throws Exception {
        String refusal = "All inputs are spent. Transaction has probably already been included";

        Run run = stage(node(Mode.LIVE), List.of(adaPrincipalPlain(1), adaPrincipalPlain(2)), null,
                acceptingThenRefusing(refusal));

        assertEquals(2, run.submitted().size(), "both are transmitted: " + run.events());
        assertTrue(spends(run.submitted().get(1), U_UTXO), inputsOf(run.submitted().get(1)).toString());
        List<LiquidationDecision> rows = oldestFirst(run);
        assertEquals(List.of(LiquidationDecision.Outcome.SUBMITTED, LiquidationDecision.Outcome.SUBMIT_FAILED),
                outcomes(run), rows.toString());
        String second = rows.get(1).loanUtxoRef();
        // The shape of the non-accepted line, at any level — the row's DEBUG summary carries the phrase too.
        java.util.regex.Pattern shape = java.util.regex.Pattern.compile(
                "^liquidation of \\S+ via (plain|convert|pay-in-advance) on \\S+ not accepted: HTTP \\S+ ");
        List<String> notAccepted = run.events().stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> shape.matcher(message).find())
                .toList();
        assertEquals(List.of("liquidation of " + second + " via plain on " + U_REF + " not accepted: HTTP 400 "
                + refusal), notAccepted, run.events().toString());
        assertTrue(run.messages(Level.INFO).contains(notAccepted.getFirst()), "said at INFO");
        assertTrue(run.messages(Level.ERROR).isEmpty(), run.messages(Level.ERROR).toString());
        assertEquals(WorkResult.SUBMITTED, run.result());
    }

    // ---- (f) FAB-186 (f) unchanged: a plain accept on U, then an anticipate on its token output -----------

    /** (f) a plain accept on U followed by a token-principal anticipate on its token output: both transmitted. */
    @Test
    void aPlainAcceptOnUIsFollowedByATokenAnticipateOnItsTokenOutput() throws Exception {
        Scenario plain = adaPrincipalPlain(1);
        Scenario anticipate = tokenPrincipalConvertBond(2);

        Run run = stage(anticipate(Mode.LIVE, PRINCIPAL_TOKEN, null), List.of(plain, anticipate), null);

        assertEquals(2, run.submitted().size(), "both are transmitted: " + run.events());
        List<LiquidationDecision> rows = oldestFirst(run);
        assertEquals(List.of(plain.loanRef(), anticipate.loanRef()),
                rows.stream().map(LiquidationDecision::loanUtxoRef).toList(), "the plain goes first: " + rows);
        assertEquals(List.of(LiquidationDecision.Outcome.SUBMITTED, LiquidationDecision.Outcome.SUBMITTED),
                outcomes(run), rows.toString());
        assertTrue(spends(run.submitted().get(0), U_UTXO), "the plain is accepted on U");
        assertTrue(spends(run.submitted().get(1), TOKEN_WALLET_UTXO), "the anticipate spends its token output");
        assertTrue(rows.get(0).detail().endsWith(LiquidationExecutor.U_STAYS_NOTE), rows.get(0).detail());
        assertFalse(rows.get(1).detail().contains("U stays"), rows.get(1).detail());
        assertEquals(WorkResult.SUBMITTED, run.result());
    }

    // ---- (g) FAB-186 r2 for a token output: marked the instant it is accepted, before anything can throw ---

    /**
     * An accepting submitter whose answer gives the hash on its FIRST read and throws on every later one, so an
     * exception escapes after the accept — in the SUBMITTED log line or row, not in the transmission.
     */
    private static LiquidationExecutor.TransactionSubmitter acceptingThenThrowingOnTheSecondRead() {
        return bytes -> {
            AtomicInteger reads = new AtomicInteger();
            return new Result<String>(true, ACCEPTED) {
                @Override
                public String getValue() {
                    if (reads.getAndIncrement() == 0) {
                        return ACCEPTED;
                    }
                    throw new IllegalStateException("the accepted answer can no longer be read");
                }
            };
        };
    }

    /**
     * (g) FAB-186 r2: an accepted submit marks its token output BEFORE anything that can throw. The answer throws
     * after the accept, so the first candidate ends in the last-net ERROR — and the second candidate, on the same
     * token output, is still skipped by name: one transmission, never two.
     */
    @Test
    void anExceptionAfterAnAcceptStillTakesItsTokenOutputOutOfTheCheck() throws Exception {
        Scenario first = tokenPrincipalConvertBond(1);
        Scenario second = tokenPrincipalConvertBond(2);

        Run run = stage(anticipate(Mode.LIVE, PRINCIPAL_TOKEN, null), List.of(first, second), null,
                acceptingThenThrowingOnTheSecondRead());

        assertEquals(1, run.submitted().size(), "one transmission: " + run.events());
        assertTrue(spends(run.submitted().getFirst(), TOKEN_WALLET_UTXO),
                inputsOf(run.submitted().getFirst()).toString());
        List<String> errors = run.messages(Level.ERROR).stream()
                .filter(message -> message.contains("could not consider bond")).toList();
        assertEquals(1, errors.size(), run.messages(Level.ERROR).toString());
        boolean firstWentOut = errors.getFirst().contains(first.assessment().bond().utxoRef());
        assertTrue(firstWentOut || errors.getFirst().contains(second.assessment().bond().utxoRef()),
                errors.getFirst());
        String skipped = firstWentOut ? tokenSkip(second) : tokenSkip(first);
        assertTrue(run.messages(Level.INFO).contains(skipped),
                "the skip names the token output: " + run.messages(Level.INFO));
        assertEquals(WorkResult.SUBMITTED, run.result());
    }

    // ---- (h) a non-accepted answer on a TOKEN output marks nothing ---------------------------------------

    /** A submitter whose FIRST call is answered by {@code first} and whose every later call accepts. */
    private static LiquidationExecutor.TransactionSubmitter firstThenAccepting(
            LiquidationExecutor.TransactionSubmitter first) {
        AtomicInteger calls = new AtomicInteger();
        return bytes -> calls.getAndIncrement() == 0
                ? first.submit(bytes)
                : Result.success(ACCEPTED).withValue(ACCEPTED);
    }

    /** Whether any line, at any level, says a candidate was skipped because the token output went out. */
    private static boolean anyTokenSkip(Run run) {
        return run.events().stream()
                .anyMatch(event -> event.getFormattedMessage().contains("skipped: wallet output " + TOKEN_REF));
    }

    /**
     * Two token-principal anticipates on one token output; the first submit is not accepted ({@code status}),
     * the second is. Nothing was marked, so the second is built and transmitted on the same token output.
     */
    private static void aNonAcceptedAnswerOnTheTokenOutputMarksNothing(
            LiquidationExecutor.TransactionSubmitter firstAnswer, String status) throws Exception {
        Run run = stage(anticipate(Mode.LIVE, PRINCIPAL_TOKEN, null),
                List.of(tokenPrincipalConvertBond(1), tokenPrincipalConvertBond(2)), null,
                firstThenAccepting(firstAnswer));

        assertEquals(2, run.submitted().size(), "both are transmitted: " + run.events());
        assertTrue(spends(run.submitted().get(0), TOKEN_WALLET_UTXO), inputsOf(run.submitted().get(0)).toString());
        assertTrue(spends(run.submitted().get(1), TOKEN_WALLET_UTXO), inputsOf(run.submitted().get(1)).toString());
        List<LiquidationDecision> rows = oldestFirst(run);
        assertEquals(List.of(LiquidationDecision.Outcome.SUBMIT_FAILED, LiquidationDecision.Outcome.SUBMITTED),
                outcomes(run), rows.toString());
        String notAccepted = "liquidation of " + rows.getFirst().loanUtxoRef() + " via pay-in-advance on "
                + TOKEN_REF + " not accepted: HTTP " + status;
        assertEquals(1, run.messages(Level.INFO).stream().filter(message -> message.startsWith(notAccepted)).count(),
                "one not-accepted INFO line: " + run.messages(Level.INFO));
        assertTrue(run.messages(Level.ERROR).isEmpty(), run.messages(Level.ERROR).toString());
        assertFalse(anyTokenSkip(run), run.events().toString());
        assertEquals(WorkResult.SUBMITTED, run.result());
    }

    /**
     * (h1) a REFUSED answer (HTTP 400) on the token output marks nothing. (h1)–(h3) are the token-output
     * defenders of "every non-accepted outcome marks nothing": the U tests can no longer defend it, because U
     * is never marked whatever the answer.
     */
    @Test
    void aRefusedAnswerOnATokenOutputMarksNothingAndTheNextCandidateOnItIsTransmitted() throws Exception {
        aNonAcceptedAnswerOnTheTokenOutputMarksNothing(
                bytes -> Result.<String>error("BadInputsUTxO: the backend refused this one").code(400), "400");
    }

    /** (h2) a THROWN submit on the token output marks nothing; see (h1). */
    @Test
    void aThrownSubmitOnATokenOutputMarksNothingAndTheNextCandidateOnItIsTransmitted() throws Exception {
        aNonAcceptedAnswerOnTheTokenOutputMarksNothing(
                bytes -> {
                    throw new IllegalStateException("the backend connection was reset");
                }, "threw");
    }

    /** (h3) a NULL answer on the token output marks nothing; see (h1). */
    @Test
    void aNullAnswerOnATokenOutputMarksNothingAndTheNextCandidateOnItIsTransmitted() throws Exception {
        aNonAcceptedAnswerOnTheTokenOutputMarksNothing(bytes -> null, "none");
    }

    // ---- (i) a failed pay-in-advance build on a TOKEN output marks nothing --------------------------------

    /**
     * A token-principal loan with ADA collateral and a convert-to-principal bond, on index-1 refs, built from
     * {@link RouteStageHarness#tokenPrincipalConvertBond}'s recipe. The principal oracle token, lend date,
     * valid-from, stake key and fee per mille are the harness's own literals, so {@link RouteStageHarness#oracles()}
     * prices it and it reaches the router.
     */
    private static Scenario tokenPrincipalAdaCollateralConvertBond() {
        LoanDatum datum = LoanFixtures.loanDatum(PRINCIPAL_TOKEN, new AssetType("e0".repeat(28), "504f5243"),
                BigInteger.valueOf(100_000_000), BigInteger.valueOf(1000), LoanFixtures.adaCollateral(),
                NOW - 30L * 24 * 3_600_000L, LoanFixtures.liquidation(),
                new RepaymentMode.PrincipalAndInterestOnInstallments(), false);
        LoanFixtures.LoanUtxo loan = LoanFixtures.loanUtxo("41".repeat(32), 0, "a1b2c3d4e5f60001", datum,
                100_000_000L, List.of());
        LoanFixtures.BondUtxo bond = LoanFixtures.bondUtxo("61".repeat(32), 0, "a1b2c3d4e5f60001",
                LoanFixtures.convertToPrincipalBondDatum(BigInteger.valueOf(500),
                        LoanFixtures.inlineKeyStakeCredential("3".repeat(56)), PRINCIPAL_TOKEN),
                2_000_000L);
        return new Scenario(loan, bond, LoanFixtures.assess(bond.bond(), loan.loan(),
                OraclePriceFeed.priceDataCharlie(PRINCIPAL_TOKEN, BigInteger.ONE, BigInteger.ONE,
                        NOW - 60_000L, NOW + 600_000L),
                OraclePriceFeed.unit(), NOW - 30_000L), true);
    }

    /**
     * (i) a failed pay-in-advance build on the token output marks nothing. Why this loan: an ADA-collateral
     * loan is the only machinery failure the harness can reach on the token route (FAB-117) — the
     * pay-in-advance router refuses it with an IllegalStateException once the executor has designated the
     * token output, and it ranks first, so the token-principal candidate behind it is on the same output. The
     * ERROR assertion keeps this from passing vacuously: if that refusal ever moved before the build, the
     * candidate would never reach the build-failure path this test defends, and the ERROR line would be gone.
     */
    @Test
    void aFailedPayInAdvanceBuildOnATokenOutputMarksNothingAndTheNextCandidateOnItIsTransmitted() throws Exception {
        Scenario adaCollateral = tokenPrincipalAdaCollateralConvertBond();
        Scenario next = tokenPrincipalConvertBond(2);

        Run run = stage(anticipate(Mode.LIVE, PRINCIPAL_TOKEN, null), List.of(adaCollateral, next), null);

        List<LiquidationDecision> rows = oldestFirst(run);
        assertEquals(List.of(LiquidationDecision.Outcome.REFUSED, LiquidationDecision.Outcome.SUBMITTED),
                outcomes(run), rows.toString());
        assertEquals(adaCollateral.loanRef(), rows.getFirst().loanUtxoRef(), rows.toString());
        List<String> errors = run.messages(Level.ERROR);
        assertEquals(1, errors.size(), errors.toString());
        assertTrue(errors.getFirst().startsWith("building the pay-in-advance liquidation of " + adaCollateral.loanRef()
                + " via pay-in-advance failed"), errors.getFirst());
        assertEquals(1, run.submitted().size(), "one transmission: " + run.events());
        assertTrue(spends(run.submitted().getFirst(), TOKEN_WALLET_UTXO),
                inputsOf(run.submitted().getFirst()).toString());
        assertFalse(anyTokenSkip(run), run.events().toString());
        assertEquals(WorkResult.SUBMITTED, run.result());
    }

    // ---- (j) D3: an accepted body marks every role output among its inputs, not only the designated one ---

    /**
     * (j) a convert designated to U whose accepted body also spends the token output: the token output goes
     * out with it, and the anticipate behind it on that output is skipped by name. The stand-in's canned body
     * ({@code K}) is not built from the wallet — that is what lets a body spend a role output it was not
     * designated, exactly the case D3's "any other wallet output among an accepted transaction's inputs is still
     * marked" covers.
     */
    @Test
    void anAcceptedBodyOnUThatAlsoSpendsTheTokenOutputTakesTheTokenOutputOutOfTheCheck() throws Exception {
        Transaction k = MarketActionRoutePinTest.k();
        assertTrue(k.getBody().getInputs().contains(
                        new TransactionInput(TOKEN_WALLET_UTXO.getTxHash(), TOKEN_WALLET_UTXO.getOutputIndex())),
                "precondition: K spends the token output: " + k.getBody().getInputs());
        BuildingConvertRouter router = new BuildingConvertRouter(k, ORDER_LOVELACE);
        Scenario convert = adaPrincipalTokenCollateralConvertBond(1);
        Scenario anticipate = tokenPrincipalConvertBond(2);

        Run run = stage(anticipate(Mode.LIVE, PRINCIPAL_TOKEN, null), List.of(convert, anticipate), router);

        assertEquals(List.of(U_UTXO), router.offered(), "the convert is funded from U");
        assertEquals(1, run.submitted().size(), "one transmission: " + run.events());
        List<LiquidationDecision> rows = oldestFirst(run);
        assertEquals(1, rows.size(), "no row for the skipped candidate: " + rows);
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, rows.getFirst().outcome(), rows.getFirst().detail());
        assertEquals(convert.loanRef(), rows.getFirst().loanUtxoRef());
        assertTrue(rows.getFirst().detail().endsWith(LiquidationExecutor.U_STAYS_NOTE), rows.getFirst().detail());
        assertTrue(run.messages(Level.INFO).contains(tokenSkip(anticipate)),
                "the skip names the token output: " + run.messages(Level.INFO));
        assertEquals(WorkResult.SUBMITTED, run.result());
    }

    /**
     * (j-shadow) the node-shadow twin of (j) (D13): a node-shadow WOULD_SUBMIT of a convert designated to U
     * whose body also spends the token output takes the token output out of the check, and the anticipate
     * behind it on that output is skipped by name. Without it a node-shadow WOULD_SUBMIT that marked only its
     * designated output passes every other test, because (c)'s token half designates the token output itself
     * and (j) exercises only the accept.
     */
    @Test
    void aNodeShadowWouldSubmitOnUWhoseBodyAlsoSpendsTheTokenOutputTakesTheTokenOutputOutOfTheCheck()
            throws Exception {
        Transaction k = MarketActionRoutePinTest.k();
        assertTrue(k.getBody().getInputs().contains(
                        new TransactionInput(TOKEN_WALLET_UTXO.getTxHash(), TOKEN_WALLET_UTXO.getOutputIndex())),
                "precondition: K spends the token output: " + k.getBody().getInputs());
        BuildingConvertRouter router = new BuildingConvertRouter(k, ORDER_LOVELACE);
        Scenario convert = adaPrincipalTokenCollateralConvertBond(1);
        Scenario anticipate = tokenPrincipalConvertBond(2);

        Run run = stage(anticipate(Mode.SHADOW, PRINCIPAL_TOKEN, null), List.of(convert, anticipate), router);

        assertEquals(List.of(U_UTXO), router.offered(), "the convert is funded from U");
        assertTrue(run.submitted().isEmpty(), "shadow transmits nothing");
        List<LiquidationDecision> rows = oldestFirst(run);
        assertEquals(1, rows.size(), "no row for the skipped candidate: " + rows);
        assertEquals(LiquidationDecision.Outcome.WOULD_SUBMIT, rows.getFirst().outcome(), rows.getFirst().detail());
        assertEquals(LiquidationExecutor.SubmitVeto.MODE_NOT_LIVE.name(), rows.getFirst().submitVeto());
        assertEquals(convert.loanRef(), rows.getFirst().loanUtxoRef());
        assertTrue(run.messages(Level.INFO).contains(tokenSkip(anticipate)),
                "the skip names the token output: " + run.messages(Level.INFO));
        assertEquals(WorkResult.WOULD_SUBMIT, run.result());
    }

    // ---- (k) no U, no note ---------------------------------------------------------------------------------

    /** (k) a wallet with no U: a token-funded SUBMITTED row ends with its hash and says nothing about U. */
    @Test
    void aWalletWithNoUGivesASubmittedRowWithoutTheUNote() {
        Run run = run(anticipate(Mode.LIVE, PRINCIPAL_TOKEN, null), List.of(tokenPrincipalConvertBond(1)),
                wallet(TOKEN_WALLET_UTXO), oracles(), null, accepting(ACCEPTED), SubmitFence.OPEN);

        assertEquals(1, run.submitted().size(), "one transmission: " + run.events());
        List<LiquidationDecision> rows = oldestFirst(run);
        assertEquals(1, rows.size(), rows.toString());
        assertEquals(LiquidationDecision.Outcome.SUBMITTED, rows.getFirst().outcome(), rows.getFirst().detail());
        assertTrue(rows.getFirst().detail().endsWith("; submitted as " + ACCEPTED), rows.getFirst().detail());
        assertFalse(rows.getFirst().detail().contains("U stays"), rows.getFirst().detail());
    }

    // ---- (l) a node-shadow row that is not WOULD_SUBMIT marks nothing ------------------------------------

    /**
     * (l) two token-principal anticipates on one token output, on a SHADOW node whose 100 ADA profit margin
     * neither clears: both are designated the token output, both are built, both are recorded UNPROFITABLE, and
     * neither is skipped. A live node would not submit an unprofitable candidate, so its node-shadow row marks
     * nothing — the node-shadow twin of (h1)–(h3), which defend "a non-accepted outcome marks nothing" on a LIVE
     * node only.
     */
    @Test
    void aNodeShadowRowThatIsNotWouldSubmitMarksNothingAndTheNextCandidateOnTheSameTokenOutputIsBuilt() {
        var configuration = new AppConfig.LiquidationConfiguration(Mode.SHADOW, 60, 120, 30,
                BigInteger.valueOf(100_000_000L), 200, node(Mode.SHADOW).getReferenceScripts());
        configuration.setMarkets(List.of(market(PRINCIPAL_TOKEN, null, Action.ANTICIPATE, AMPLE_CAP)));
        List<Scenario> scenarios = List.of(tokenPrincipalConvertBond(1), tokenPrincipalConvertBond(2));

        Run run = stage(configuration, scenarios, null);

        for (Scenario scenario : scenarios) {
            String designated = scenario.loanRef() + " is funded from wallet output " + TOKEN_REF + " (role TOKEN ";
            assertTrue(run.messages(Level.INFO).stream().anyMatch(message -> message.contains(designated)),
                    "precondition: " + scenario.loanRef() + " is designated the token output: "
                            + run.messages(Level.INFO));
        }
        assertTrue(run.submitted().isEmpty(), "shadow transmits nothing");
        List<LiquidationDecision> rows = oldestFirst(run);
        assertEquals(List.of(LiquidationDecision.Outcome.UNPROFITABLE, LiquidationDecision.Outcome.UNPROFITABLE),
                outcomes(run), rows.toString());
        assertTrue(rows.stream()
                        .allMatch(row -> LiquidationExecutor.SubmitVeto.MODE_NOT_LIVE.name().equals(row.submitVeto())),
                rows.toString());
        assertFalse(anyTokenSkip(run), run.events().toString());
        assertEquals(WorkResult.DEFINITE_FAILURE, run.result());
    }
}
