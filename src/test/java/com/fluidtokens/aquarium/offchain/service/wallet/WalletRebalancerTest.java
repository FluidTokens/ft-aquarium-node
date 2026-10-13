package com.fluidtokens.aquarium.offchain.service.wallet;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.yaci.store.events.EventMetadata;
import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Action;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Market;
import com.fluidtokens.aquarium.offchain.config.AppConfig.LiquidationConfiguration.Mode;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FAB-131: the rebalancer's gates, cheapest first, and what it does once they pass — driven with no Spring,
 * through the package-private seams. The engine is the REAL {@link WalletShapeTransactions} over
 * {@code LoanFixtures.protocolParams()}; only its submitter and its account's {@code sign} are instrumented,
 * so every submitted byte is a transaction the real engine built and verified.
 *
 * <p>Every gating case uses a wallet that DOES need rebalancing (the NIGHT case: 15,000 NIGHT on 1.18 ADA plus
 * 200 ADA), so a blocked gate is the only reason nothing happens, and counts both the wallet reads and the
 * submissions: a blocked gate reads nothing.
 */
class WalletRebalancerTest {

    private static final String NIGHT = "ee".repeat(28) + "4e49474854";
    private static final String USDM = "cc".repeat(28) + "5553444d";
    private static final String JUNK = "dd".repeat(28) + "4a554e4b";
    private static final BigInteger TEN_ADA = WalletShape.SHAPED_TOKEN_LOVELACE;
    private static final BigInteger C =
            WalletShape.collateralLovelace(LoanFixtures.protocolParams().getProtocolParams());

    private static int counter = 0;

    private final AtomicInteger signs = new AtomicInteger();
    private final AtomicInteger submits = new AtomicInteger();
    private final AtomicInteger reads = new AtomicInteger();
    private final List<byte[]> submitted = new ArrayList<>();

    /** The bot account, with {@code sign} counted. */
    private final Account account = new Account(Networks.preview()) {
        @Override
        public Transaction sign(Transaction transaction) {
            signs.incrementAndGet();
            return super.sign(transaction);
        }
    };
    private final String wallet = account.baseAddress();

    private final AtomicReference<WalletShapeTransactions.TransactionSubmitter> submitterBehaviour =
            new AtomicReference<>(bytes -> Result.success("ok").withValue("f".repeat(64)));

    private final WalletShapeTransactions engine = new WalletShapeTransactions(account, LoanFixtures.protocolParams(),
            bytes -> {
                submits.incrementAndGet();
                submitted.add(bytes);
                return submitterBehaviour.get().submit(bytes);
            });

    private final AtomicReference<List<Utxo>> walletContents = new AtomicReference<>();
    private final AtomicReference<RuntimeException> walletFailure = new AtomicReference<>();
    private final AtomicBoolean syncing = new AtomicBoolean(false);
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.LIVE);
    private final AtomicReference<List<Market>> markets = new AtomicReference<>(List.of(anticipate(NIGHT)));

    /**
     * A market's effective mode by the REAL rule, {@code LiquidationConfiguration.effectiveMode} — the global mode
     * is a ceiling over the market's own — evaluated against whatever {@link #mode} holds at the call.
     */
    private final Function<Market, Mode> effectiveMode = market -> new AppConfig.LiquidationConfiguration(
            mode.get() == null ? Mode.DISABLED : mode.get(), 60, 120, 30, BigInteger.ZERO, 200).effectiveMode(market);

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(WalletRebalancer.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        walletContents.set(nightWallet());
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    private WalletRebalancer rebalancer(boolean envEnabled) {
        return new WalletRebalancer(engine,
                () -> {
                    reads.incrementAndGet();
                    if (walletFailure.get() != null) {
                        throw walletFailure.get();
                    }
                    return walletContents.get();
                },
                syncing::get, mode::get, markets::get, effectiveMode, envEnabled);
    }

    private static Market market(String unit, Action action) {
        Market m = new Market();
        m.setUnit(unit);
        m.setAction(action);
        if (action == Action.ANTICIPATE) {
            m.setCap(BigInteger.valueOf(1_000_000_000_000L));
        }
        return m;
    }

    private static Market anticipate(String unit) {
        return market(unit, Action.ANTICIPATE);
    }

    private static Market anticipate(String unit, Mode marketMode) {
        Market m = anticipate(unit);
        m.setMode(marketMode);
        return m;
    }

    private Utxo utxo(long lovelace, Object... unitQuantityPairs) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(BigInteger.valueOf(lovelace)));
        for (int i = 0; i < unitQuantityPairs.length; i += 2) {
            amounts.add(Amount.asset((String) unitQuantityPairs[i], BigInteger.valueOf((Long) unitQuantityPairs[i + 1])));
        }
        Utxo u = new Utxo();
        u.setTxHash(String.format("%064x", 0x2000 + ++counter));
        u.setOutputIndex(counter % 3);
        u.setAddress(wallet);
        u.setAmount(amounts);
        return u;
    }

    /** The production incident shape: 15,000 NIGHT (6 decimals) on 1.18 ADA, plus 200 ADA. */
    private List<Utxo> nightWallet() {
        return List.of(utxo(1_180_000, NIGHT, 15_000_000_000L), utxo(200_000_000));
    }

    private static CommitEvent<?> event() {
        return new CommitEvent<>(EventMetadata.builder().slot(1L).build(), List.of());
    }

    private List<ILoggingEvent> at(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    private List<ILoggingEvent> atOrAbove(Level level) {
        return logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(level)).toList();
    }

    private void assertBlocked(WalletRebalancer rebalancer, String why) {
        rebalancer.onCommit();
        assertEquals(0, reads.get(), why + ": a blocked gate must not read the wallet (cheapest first)");
        assertEquals(0, submits.get(), why + ": a blocked gate must not submit");
        assertEquals(0, signs.get(), why + ": a blocked gate must not sign");
        assertEquals(List.of(), atOrAbove(Level.INFO), why + ": a blocked gate logs at DEBUG only");
    }

    private void assertRebalancedOnce(WalletRebalancer rebalancer, String why) {
        rebalancer.onCommit();
        assertEquals(1, reads.get(), why + ": the wallet is read once");
        assertEquals(1, signs.get(), why + ": the built transaction is signed once");
        assertEquals(1, submits.get(), why + ": the built transaction is submitted once");
    }

    // ---- the gating matrix ---------------------------------------------------------------------------------

    @Test
    void allGatesOpenRebalancesTheNightWallet() {
        assertRebalancedOnce(rebalancer(true), "every gate open (positive control for the matrix)");
    }

    @Test
    void envOffBlocks() {
        WalletRebalancer rebalancer = rebalancer(false);
        assertBlocked(rebalancer, "loans.wallet.rebalance.enabled=false");
        assertEquals(new WalletRebalancer.State(false, false, false), rebalancer.state());
    }

    @Test
    void suspendedBlocksAndResumeReleases() {
        WalletRebalancer rebalancer = rebalancer(true);
        assertEquals(new WalletRebalancer.State(true, false, true), rebalancer.state());

        rebalancer.suspend();
        assertEquals(new WalletRebalancer.State(true, true, false), rebalancer.state());
        rebalancer.onCommit();
        assertEquals(0, reads.get(), "suspended: no wallet read");
        assertEquals(0, submits.get(), "suspended: no submission");

        rebalancer.resume();
        assertEquals(new WalletRebalancer.State(true, false, true), rebalancer.state());
        assertRebalancedOnce(rebalancer, "resumed");
    }

    @Test
    void resumeWhileEnvOffStaysOff() {
        WalletRebalancer rebalancer = rebalancer(false);
        rebalancer.suspend();
        rebalancer.resume();
        assertEquals(new WalletRebalancer.State(false, false, false), rebalancer.state(),
                "resume can never override env=false");
        rebalancer.onCommit();
        assertEquals(0, reads.get(), "resume after env=false must not read the wallet");
        assertEquals(0, submits.get(), "resume after env=false must not submit");
    }

    @Test
    void modeDisabledBlocks() {
        mode.set(Mode.DISABLED);
        assertBlocked(rebalancer(true), "mode DISABLED");
    }

    @Test
    void aNullModeBlocks() {
        mode.set(null);
        assertBlocked(rebalancer(true), "mode not yet parsed");
    }

    /** SHADOW submits: a rebalance is wallet housekeeping, not a liquidation (Giovanni's ruling). */
    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"SHADOW", "LIVE"})
    void modeShadowAndLiveBothSubmit(Mode armed) {
        mode.set(armed);
        assertRebalancedOnce(rebalancer(true), "mode " + armed);
    }

    @Test
    void noAnticipateMarketBlocks() {
        markets.set(List.of(market(NIGHT, Action.CONVERT), market("lovelace", Action.CONVERT)));
        assertBlocked(rebalancer(true), "only CONVERT markets");
    }

    @Test
    void noMarketAtAllBlocks() {
        markets.set(List.of());
        assertBlocked(rebalancer(true), "no market configured");
    }

    /**
     * An ADA-principal ANTICIPATE market opens G4 (the bot fronts ADA from the change, so its wallet still has
     * to be in shape) while contributing nothing to the relevant set: lovelace is not a token to isolate.
     */
    @Test
    void aLovelaceAnticipateMarketOpensTheGateWithAnEmptyRelevantSet() {
        markets.set(List.of(anticipate("lovelace")));
        assertRebalancedOnce(rebalancer(true), "lovelace ANTICIPATE");
        List<ILoggingEvent> info = at(Level.INFO);
        assertEquals(1, info.size(), "one INFO line for the submission: " + logs.list);
        assertTrue(info.get(0).getFormattedMessage().contains("relevant []"),
                "lovelace must not reach the relevant set: " + info.get(0).getFormattedMessage());
    }

    @Test
    void theRelevantSetIsTheAnticipatePrincipalsLowerCased() {
        markets.set(List.of(anticipate(NIGHT.toUpperCase()), market("aa".repeat(28) + "41", Action.CONVERT)));
        assertRebalancedOnce(rebalancer(true), "an upper-case ANTICIPATE unit");
        assertTrue(at(Level.INFO).get(0).getFormattedMessage().contains("relevant [" + NIGHT + "]"),
                at(Level.INFO).get(0).getFormattedMessage());
    }

    // ---- the per-market mode (FAB-131 r2) -------------------------------------------------------------------

    /**
     * An ANTICIPATE market whose OWN mode is DISABLED can never liquidate ({@code effectiveMode} is DISABLED
     * whatever the node's mode), so it must not open G4: nothing read, nothing submitted.
     */
    @Test
    void anAnticipateMarketWhoseOwnModeIsDisabledDoesNotOpenTheGate() {
        mode.set(Mode.SHADOW);
        markets.set(List.of(anticipate(NIGHT, Mode.DISABLED), market(USDM, Action.CONVERT)));
        assertBlocked(rebalancer(true), "the only ANTICIPATE market has mode: DISABLED");
    }

    /**
     * ... and it must not tie up 10 ADA in an isolated UTxO either: with another ANTICIPATE market enabled, the
     * DISABLED market's token is NOT relevant, so the built transaction leaves it in the non-relevant bundle
     * (beside JUNK), while the enabled market's token gets its own exactly-10-ADA output.
     */
    @Test
    void aDisabledAnticipateMarketsTokenStaysInTheNonRelevantBundle() throws Exception {
        mode.set(Mode.SHADOW);
        markets.set(List.of(anticipate(NIGHT, Mode.DISABLED), anticipate(USDM)));
        walletContents.set(List.of(utxo(1_180_000, NIGHT, 15_000_000_000L), utxo(1_500_000, USDM, 1_000_000L),
                utxo(1_300_000, JUNK, 5L), utxo(200_000_000)));

        assertRebalancedOnce(rebalancer(true), "one enabled ANTICIPATE market beside a disabled one");
        Transaction tx = Transaction.deserialize(submitted.get(0));
        List<Map<String, BigInteger>> outputs = new ArrayList<>();
        for (TransactionOutput output : tx.getBody().getOutputs()) {
            outputs.add(WalletShapeTransactions.unitsOf(output.getValue()));
        }
        assertTrue(outputs.contains(Map.of("lovelace", TEN_ADA, USDM, BigInteger.valueOf(1_000_000L))),
                "the enabled market's token is isolated on exactly 10 ADA: " + outputs);
        assertTrue(outputs.contains(Map.of("lovelace", TEN_ADA, NIGHT, BigInteger.valueOf(15_000_000_000L),
                JUNK, BigInteger.valueOf(5L))), "the DISABLED market's token stays in the non-relevant bundle: " + outputs);
        assertTrue(at(Level.INFO).get(0).getFormattedMessage().contains("relevant [" + USDM + "]"),
                at(Level.INFO).get(0).getFormattedMessage());
    }

    /** A market with no mode of its own inherits the node's, and counts. */
    @ParameterizedTest
    @EnumSource(value = Mode.class, names = {"SHADOW", "LIVE"})
    void aMarketWithNoModeInheritsTheGlobalModeAndCounts(Mode global) {
        mode.set(global);
        markets.set(List.of(anticipate(NIGHT, null)));
        assertRebalancedOnce(rebalancer(true), "market mode null under global " + global);
        assertTrue(at(Level.INFO).get(0).getFormattedMessage().contains("relevant [" + NIGHT + "]"),
                at(Level.INFO).get(0).getFormattedMessage());
    }

    /** A market MORE permissive than the node is capped at the node's mode (LIVE under SHADOW is SHADOW): it counts. */
    @Test
    void aLiveMarketUnderAShadowNodeCountsAsShadow() {
        mode.set(Mode.SHADOW);
        markets.set(List.of(anticipate(NIGHT, Mode.LIVE)));
        assertRebalancedOnce(rebalancer(true), "market LIVE under global SHADOW");
    }

    @Test
    void syncingBlocks() {
        syncing.set(true);
        assertBlocked(rebalancer(true), "syncing");
    }

    @Test
    void notSyncingPasses() {
        syncing.set(false);
        assertRebalancedOnce(rebalancer(true), "not syncing");
    }

    // ---- what happens once every gate is open -----------------------------------------------------------------

    @Test
    void aShapedWalletBuildsNothingAndLogsAtDebugOnly() {
        walletContents.set(List.of(utxo(TEN_ADA.longValueExact(), NIGHT, 15_000_000_000L),
                utxo(C.longValueExact()), utxo(150_000_000)));
        assertEquals(Set.of(), WalletShape.reasons(walletContents.get(), Set.of(NIGHT), C),
                "the fixture must already be in shape");

        rebalancer(true).onCommit();
        assertEquals(1, reads.get());
        assertEquals(0, signs.get(), "a shaped wallet is not signed for");
        assertEquals(0, submits.get(), "a shaped wallet is not submitted for");
        assertEquals(List.of(), atOrAbove(Level.INFO), "a shaped wallet logs at DEBUG only");
    }

    @Test
    void aTooSmallWalletIsRefusedWithAWarnOnEveryEventAndNeverSigned() {
        // 6.18 ADA cannot fund a 10-ADA NIGHT output, C of collateral, a fee and a change output.
        walletContents.set(List.of(utxo(1_180_000, NIGHT, 15_000_000_000L), utxo(5_000_000)));
        assertEquals(Set.of(WalletShape.Reason.RELEVANT_UTXO_LOVELACE_NOT_EXACT),
                WalletShape.reasons(walletContents.get(), Set.of(NIGHT), C), "the fixture's one reason");
        WalletRebalancer rebalancer = rebalancer(true);

        rebalancer.onCommit();
        rebalancer.onCommit();

        assertEquals(2, reads.get());
        assertEquals(0, signs.get(), "a refusal is never signed");
        assertEquals(0, submits.get(), "a refusal is never submitted");
        List<ILoggingEvent> warns = at(Level.WARN);
        assertEquals(2, warns.size(), "unthrottled: one WARN per CommitEvent: " + logs.list);
        for (ILoggingEvent warn : warns) {
            String message = warn.getFormattedMessage();
            assertTrue(message.startsWith("[REBALANCE]"), message);
            assertTrue(message.contains("REFUSED"), message);
            assertTrue(message.contains("the plan needs"), "the engine's detail is logged: " + message);
            assertTrue(message.contains("RELEVANT_UTXO_LOVELACE_NOT_EXACT"), "the reasons are logged: " + message);
        }
        assertEquals(List.of(), at(Level.ERROR));
    }

    @Test
    void theNightReproductionSubmitsTheShapedSelfSpendOfTheWholeWallet() throws Exception {
        List<Utxo> walletUtxos = walletContents.get();
        WalletRebalancer rebalancer = rebalancer(true);
        rebalancer.onCommit();
        assertEquals(1, submitted.size());

        Transaction tx = Transaction.deserialize(submitted.get(0));
        List<Map<String, BigInteger>> outputs = new ArrayList<>();
        for (TransactionOutput output : tx.getBody().getOutputs()) {
            assertEquals(wallet, output.getAddress(), "every output goes back to the wallet");
            outputs.add(WalletShapeTransactions.unitsOf(output.getValue()));
        }
        assertTrue(outputs.contains(Map.of("lovelace", TEN_ADA, NIGHT, BigInteger.valueOf(15_000_000_000L))),
                "15,000 NIGHT + exactly 10 ADA at the wallet: " + outputs);
        assertTrue(outputs.contains(Map.of("lovelace", C)), "the exact collateral output: " + outputs);

        Set<String> spent = tx.getBody().getInputs().stream().map(i -> i.getTransactionId() + "#" + i.getIndex())
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> whole = walletUtxos.stream().map(u -> u.getTxHash() + "#" + u.getOutputIndex())
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(whole, spent, "the inputs are exactly the wallet");
        assertEquals(1, tx.getWitnessSet().getVkeyWitnesses().size(), "submitted signed, by the bot key");

        List<ILoggingEvent> info = at(Level.INFO);
        assertEquals(1, info.size(), String.valueOf(logs.list));
        String message = info.get(0).getFormattedMessage();
        assertTrue(message.contains("f".repeat(64)), "the tx hash is logged: " + message);
        assertTrue(message.contains("NO_EXACT_COLLATERAL"), "the reasons are logged: " + message);
        assertTrue(message.contains("fee " + tx.getBody().getFee()), "the fee is logged: " + message);
    }

    @Test
    void aWalletReadFailureIsAnErrorAndNeverAnEmptyWallet() {
        walletFailure.set(new IllegalStateException("database down"));
        WalletRebalancer rebalancer = rebalancer(true);

        assertDoesNotThrow(rebalancer::onCommit);
        assertDoesNotThrow(() -> rebalancer.processCommit(event()));

        assertEquals(2, reads.get());
        assertEquals(0, signs.get());
        assertEquals(0, submits.get());
        assertEquals(2, at(Level.ERROR).size(), String.valueOf(logs.list));
        assertTrue(at(Level.ERROR).get(0).getFormattedMessage().contains("database down"),
                at(Level.ERROR).get(0).getFormattedMessage());
        assertEquals(List.of(), at(Level.WARN), "a failed read is not a refusal of an empty wallet");
    }

    @Test
    void anUnsuccessfulSubmissionIsLoggedAndNothingEscapes() {
        submitterBehaviour.set(bytes -> Result.error("BadInputsUTxO"));
        WalletRebalancer rebalancer = rebalancer(true);

        assertDoesNotThrow(() -> rebalancer.processCommit(event()));
        assertEquals(1, submits.get());
        assertEquals(1, at(Level.ERROR).size(), String.valueOf(logs.list));
        assertTrue(at(Level.ERROR).get(0).getFormattedMessage().contains("BadInputsUTxO"),
                at(Level.ERROR).get(0).getFormattedMessage());
        assertEquals(List.of(), at(Level.INFO), "a rejection is not reported as a submission");
    }

    @Test
    void aThrowingSubmitterIsLoggedAndNothingEscapes() {
        submitterBehaviour.set(bytes -> {
            throw new java.io.IOException("connection reset");
        });
        WalletRebalancer rebalancer = rebalancer(true);

        assertDoesNotThrow(() -> rebalancer.processCommit(event()));
        assertDoesNotThrow(rebalancer::onCommit);
        assertEquals(2, submits.get());
        assertEquals(2, at(Level.ERROR).size(), String.valueOf(logs.list));
        assertTrue(at(Level.ERROR).get(0).getFormattedMessage().contains("connection reset"),
                at(Level.ERROR).get(0).getFormattedMessage());
    }

    @Test
    void aRuntimeFailureAnywhereInTheBodyNeverReachesTheYaciPublisher() {
        submitterBehaviour.set(bytes -> {
            throw new IllegalStateException("unexpected");
        });
        WalletRebalancer gateFailure = new WalletRebalancer(engine, walletContents::get, syncing::get, mode::get,
                () -> {
                    throw new IllegalStateException("markets unreadable");
                }, effectiveMode, true);
        assertDoesNotThrow(() -> gateFailure.processCommit(event()));
        assertTrue(at(Level.ERROR).stream().anyMatch(e -> e.getFormattedMessage().contains("markets unreadable")),
                String.valueOf(logs.list));

        markets.set(List.of(anticipate(NIGHT)));
        assertDoesNotThrow(() -> rebalancer(true).processCommit(event()));
        assertEquals(1, submits.get());
        assertTrue(at(Level.ERROR).stream().anyMatch(e -> e.getFormattedMessage().contains("unexpected")),
                String.valueOf(logs.list));
    }

    @Test
    void stateIsAlwaysEnvAndNotSuspended() {
        for (boolean env : new boolean[]{true, false}) {
            WalletRebalancer rebalancer = rebalancer(env);
            for (boolean suspend : new boolean[]{false, true, false}) {
                if (suspend) {
                    rebalancer.suspend();
                } else {
                    rebalancer.resume();
                }
                WalletRebalancer.State state = rebalancer.state();
                assertEquals(env, state.envEnabled());
                assertEquals(suspend, state.suspended());
                assertEquals(env && !suspend, state.effective());
            }
        }
        assertFalse(rebalancer(false).state().effective());
    }
}
