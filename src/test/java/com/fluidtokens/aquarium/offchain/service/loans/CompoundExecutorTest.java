package com.fluidtokens.aquarium.offchain.service.loans;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundCandidate;
import com.fluidtokens.aquarium.offchain.model.loans.CompoundExclusion;
import com.fluidtokens.aquarium.offchain.model.loans.LenderBond;
import com.fluidtokens.aquarium.offchain.service.AppUtxoService;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.LoansContractRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The compound loop, driven end to end over the REAL recorded candidate {@code e833a769…} — the same
 * on-chain objects {@link CompoundDryEvalTest} evaluates. The builder is the real one, so these tests
 * exercise scan → build → price → submit rather than a mock of it.
 *
 * <p>The submitter is the only thing faked at the wire, and it records instead of transmitting.
 */
class CompoundExecutorTest {

    private static final LoansContractRegistry REGISTRY = LoanFixtures.shippedPreviewRegistry();
    private static final Account ACCOUNT = new Account(LoanFixtures.NETWORK);
    private static final String LOAN_ID = "e833a769ea3a480343175e253eab799ec0b058c99de30cc17160dc37";

    /** Every candidate here is ada-principal, so this never actually reaches the oracle. */
    private static PricingService pricingService() {
        return new PricingService(new FluidOracleClient("http://unused.invalid"));
    }
    private static final String POOL_ID = "00d3513725536642b6fe985ce9ec87d1ebb880497d92e0a8495bc6d0bf";
    private static final BigInteger ESCROW = BigInteger.valueOf(29_109_268L);

    // ---- the recorded universe ---------------------------------------------------------------

    private static Utxo utxo(String key) {
        try (InputStream is = CompoundExecutorTest.class
                .getResourceAsStream("/loans-v4/compound-candidate-e833a769.json")) {
            JsonNode n = new ObjectMapper().readTree(is).get(key);
            List<Amount> amounts = new ArrayList<>();
            n.get("amount").forEach(a -> amounts.add(Amount.builder()
                    .unit(a.get("unit").asText())
                    .quantity(new BigInteger(a.get("quantity").asText())).build()));
            return Utxo.builder().txHash(n.get("txHash").asText())
                    .outputIndex(n.get("outputIndex").asInt())
                    .address(n.get("address").asText()).amount(amounts)
                    .inlineDatum(n.get("inlineDatum").isNull() ? null : n.get("inlineDatum").asText())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("compound candidate fixture missing", e);
        }
    }

    private static Utxo wallet() {
        return Utxo.builder().txHash("9e".repeat(32)).outputIndex(0)
                .address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L)))).build();
    }

    private static Utxo collateral() {
        return Utxo.builder().txHash("c0".repeat(32)).outputIndex(0)
                .address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000L)))).build();
    }

    private static List<Utxo> universe() {
        List<Utxo> universe = new ArrayList<>(baseUniverse());
        universe.add(wallet());
        return List.copyOf(universe);
    }

    private static List<Utxo> baseUniverse() {
        return List.of(utxo("escrow"), utxo("bond"), utxo("pool"), utxo("poolManager"),
                utxo("config"), utxo("lmConfig"), collateral());
    }

    private static CompoundCandidate ready(long feePerMille) {
        Utxo bondUtxo = utxo("bond");
        LenderBond bond = new LenderBond(bondUtxo.getTxHash(), bondUtxo.getOutputIndex(),
                bondUtxo.getAddress(), LOAN_ID, bondUtxo.getInlineDatum(),
                new LenderManagerDatumConverter().deserialize(bondUtxo.getInlineDatum()));
        return new CompoundCandidate(LOAN_ID, utxo("escrow"),
                new AssetManagerDatumConverter().deserialize(utxo("escrow").getInlineDatum()),
                ESCROW, bond, POOL_ID, utxo("pool"), utxo("poolManager"),
                feePerMille, true, null, "recorded preview candidate");
    }

    private static CompoundCandidate refused() {
        return new CompoundCandidate(LOAN_ID, utxo("escrow"), null, null, null, POOL_ID, null, null,
                0L, true, CompoundExclusion.POOL_NOT_LIVE, "the pool NFT is burned");
    }

    // ---- wiring ------------------------------------------------------------------------------

    private record Wiring(CompoundExecutor executor, List<byte[]> submitted) {
    }

    private static Wiring wiringWithWallet(List<CompoundCandidate> candidates, List<Utxo> walletUtxos) {
        return wiring(candidates, true, -2_000_000L, walletUtxos, "preview");
    }

    private static Wiring wiringOnNetwork(List<CompoundCandidate> candidates, String network) {
        return wiring(candidates, true, -2_000_000L, List.of(wallet()), network);
    }

    private static Wiring wiring(List<CompoundCandidate> candidates, boolean armed, long floor) {
        return wiring(candidates, armed, floor, List.of(wallet()), "preview");
    }

    private static Wiring wiring(List<CompoundCandidate> candidates, boolean armed, long floor,
                                 List<Utxo> walletUtxos, String networkName) {
        var configuration = new AppConfig.CompoundConfiguration(armed, 60L, BigInteger.valueOf(floor));
        var network = new AppConfig.Network();
        network.setNetworkForTest(networkName);

        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);

        List<byte[]> submitted = new ArrayList<>();
        CompoundExecutor.TransactionSubmitter submitter = bytes -> {
            submitted.add(bytes);
            return Result.success("deadbeefcafe").withValue("deadbeefcafe");
        };

        List<Utxo> universe = new java.util.ArrayList<>(baseUniverse());
        universe.addAll(walletUtxos);
        var builder = new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                LoanFixtures.utxoSupplier(universe), EvalFixtures.protocolParams(), null);
        var executor = new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(walletUtxos), ACCOUNT,
                new FakeScanner(candidates), new CompoundEconomics(configuration, network, pricingService()),
                builder, new FakeResolver(), LoanFixtures.utxoSupplier(universe), LoanFixtures.converters(), submitter);
        return new Wiring(executor, submitted);
    }

    // ---- the tests ---------------------------------------------------------------------------

    /**
     * ⛔ Disarmed is the default, and a READY candidate must still be reported. Silence here is the
     * failure mode an operator cannot distinguish from "nothing to do".
     */
    @Test
    void aReadyCandidateIsNotProcessedWhileDisarmed() {
        Wiring w = wiring(List.of(ready(0L)), false, 0L);
        w.executor().cycle();
        assertTrue(w.submitted().isEmpty(), "a disarmed loop must transmit nothing");
    }

    /** ⛔ The zero-fee pool at the safe default: built, priced, and declined on the numbers. */
    @Test
    void aZeroFeePoolIsDeclinedAtTheDefaultFloor() {
        Wiring w = wiring(List.of(ready(0L)), true, 0L);
        w.executor().cycle();
        assertTrue(w.submitted().isEmpty(),
                "expectedFee 0 minus a real tx fee is negative, and the default floor refuses it");
    }

    /**
     * ⛔ AND THE RULED CASE. The same candidate, the same zero fee, with the operator's stated
     * negative bound — this is the transaction that will actually fire on preview.
     */
    @Test
    void aStatedNegativeFloorProcessesTheZeroFeeCandidate() {
        Wiring w = wiring(List.of(ready(0L)), true, -2_000_000L);
        w.executor().cycle();
        assertEquals(1, w.submitted().size(),
                "with the loss stated and bounded, the armed loop compounds");
        assertTrue(w.submitted().getFirst().length > 0, "signed bytes reached the submitter");
    }

    /** A structurally refused candidate never reaches the builder. */
    @Test
    void aStructurallyRefusedCandidateIsReportedAndNotBuilt() {
        Wiring w = wiring(List.of(refused()), true, -2_000_000L);
        w.executor().cycle();
        assertTrue(w.submitted().isEmpty());
    }

    /** An empty scan is not an error and transmits nothing. */
    @Test
    void anEmptyScanIsQuietAndHarmless() {
        Wiring w = wiring(List.of(), true, -2_000_000L);
        w.executor().cycle();
        assertTrue(w.submitted().isEmpty());
    }

    /** Syncing is a hard skip: the index is not yet a view of the chain. */
    @Test
    void nothingRunsWhileTheIndexerIsSyncing() {
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.valueOf(-2_000_000L));
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(true);

        AtomicInteger scans = new AtomicInteger();
        var scanner = new FakeScanner(List.of(ready(0L))) {
            @Override
            public Scan scan() {
                scans.incrementAndGet();
                return super.scan();
            }
        };
        var executor = new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(List.of(wallet())), ACCOUNT, scanner,
                new CompoundEconomics(configuration, network, pricingService()),
                new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                        LoanFixtures.utxoSupplier(universe()), EvalFixtures.protocolParams(), null),
                new FakeResolver(), LoanFixtures.utxoSupplier(universe()), LoanFixtures.converters(),
                bytes -> { throw new AssertionError("a syncing node must not submit"); });

        executor.cycle();
        assertEquals(0, scans.get(), "a syncing node must not even scan");
    }

    /** ⛔ FAB-115: compound is a Lending v4 transaction path, so a closed gate stops it too. */
    @Test
    void aClosedLendingConfigGateStopsCompoundBeforeTheScan() {
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.valueOf(-2_000_000L));
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);

        AtomicInteger scans = new AtomicInteger();
        var scanner = new FakeScanner(List.of(ready(0L))) {
            @Override
            public Scan scan() {
                scans.incrementAndGet();
                return super.scan();
            }
        };
        var executor = new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(List.of(wallet())), ACCOUNT, scanner,
                new CompoundEconomics(configuration, network, pricingService()),
                new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                        LoanFixtures.utxoSupplier(universe()), EvalFixtures.protocolParams(), null),
                new FakeResolver(), LoanFixtures.utxoSupplier(universe()), LoanFixtures.converters(),
                bytes -> { throw new AssertionError("a closed lending gate must not submit"); });

        var gate = new com.fluidtokens.aquarium.offchain.service.LendingConfigGate();
        gate.block("ConfigDatum[11]: derived 63b26ff9…, chain 64d9b13f…");
        executor.setLendingConfigGate(gate);

        executor.cycle();
        assertEquals(0, scans.get(), "FAB-115: a closed lending gate must stop compound before the scan");
    }

    @Test
    void anOpenLendingConfigGateLetsCompoundScan() {
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.valueOf(-2_000_000L));
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);

        AtomicInteger scans = new AtomicInteger();
        var scanner = new FakeScanner(List.of(ready(0L))) {
            @Override
            public Scan scan() {
                scans.incrementAndGet();
                return super.scan();
            }
        };
        var executor = new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(List.of(wallet())), ACCOUNT, scanner,
                new CompoundEconomics(configuration, network, pricingService()),
                new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                        LoanFixtures.utxoSupplier(universe()), EvalFixtures.protocolParams(), null),
                new FakeResolver(), LoanFixtures.utxoSupplier(universe()), LoanFixtures.converters(),
                bytes -> com.bloxbean.cardano.client.api.model.Result.<String>success("ok").withValue("deadbeef"));

        // FAB-115 audit finding 3: the control. An OPEN gate must not stop compound — a condition
        // that refused whenever a gate bean merely existed would refuse forever in production.
        executor.setLendingConfigGate(new com.fluidtokens.aquarium.offchain.service.LendingConfigGate());

        executor.cycle();
        assertEquals(1, scans.get(), "an open lending gate must let compound scan");
    }

    @Test
    void aClosedLendingConfigGateIsLoggedOnceAtErrorNotEveryCycle() {
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.valueOf(-2_000_000L));
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);

        AtomicInteger scans = new AtomicInteger();
        var scanner = new FakeScanner(List.of(ready(0L))) {
            @Override
            public Scan scan() {
                scans.incrementAndGet();
                return super.scan();
            }
        };
        var executor = new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(List.of(wallet())), ACCOUNT, scanner,
                new CompoundEconomics(configuration, network, pricingService()),
                new CompoundTransactionBuilder(REGISTRY, Networks.preview(),
                        LoanFixtures.utxoSupplier(universe()), EvalFixtures.protocolParams(), null),
                new FakeResolver(), LoanFixtures.utxoSupplier(universe()), LoanFixtures.converters(),
                bytes -> { throw new AssertionError("a closed lending gate must not submit"); });

        var gate = new com.fluidtokens.aquarium.offchain.service.LendingConfigGate();
        gate.block("ConfigDatum[11]: derived 63b26ff9…, chain 64d9b13f…");
        executor.setLendingConfigGate(gate);

        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(CompoundExecutor.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            executor.cycle();
            executor.cycle();
        } finally {
            logger.detachAppender(appender);
        }
        assertEquals(1, appender.list.stream()
                        .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR)
                        .filter(e -> e.getFormattedMessage().contains("LENDING_CONFIG_MISMATCH"))
                        .count(),
                "FAB-115: the refusal is said ONCE at ERROR, naming LENDING_CONFIG_MISMATCH — not every cycle");
    }

    /**
     * ⛔ THE PRODUCTION SHAPE, 2026-09-02. The wallet held 9,898 ada and could build nothing, because
     * its ONE utxo also carried a native token — CCL trap 17: a successful transaction's change
     * output disables the builder that made it. The publications that put the reference scripts on
     * chain consumed every ada-only utxo and swept the remainder, token included, into a single
     * change output.
     *
     * <p>The predicate is RIGHT — collateral must be pure ada (trap 16) — so this must SKIP, and the
     * log must say the wallet is the wrong SHAPE rather than empty.
     */
    @Test
    void aWalletWhoseOnlyUtxoCarriesATokenIsSkippedNotSpent() {
        Utxo tokenBearing = Utxo.builder().txHash("2e".repeat(32)).outputIndex(1)
                .address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(9_898_050_234L)),
                        Amount.builder().unit("0b77d150c275bd0a600633e4be7d09f83c4b9f00981e22ac9c9d3f620014df1074464c4454")
                                .quantity(BigInteger.valueOf(20_000_000L)).build()))
                .build();

        Wiring w = wiringWithWallet(List.of(ready(0L)), List.of(tokenBearing));
        w.executor().cycle();

        assertTrue(w.submitted().isEmpty(),
                "9,898 ada on a token-bearing utxo is not spendable as collateral; it must skip");
    }

    /**
     * ⚠ And the LARGEST nominable is taken, not the first. A 1 ada utxo cannot cover the collateral
     * the ledger requires (150% of a ~0.84 ada fee), and nominating it produces a NEGATIVE collateral
     * return — trap 16, a transaction the node cannot even parse. Picking first-in-list would have
     * done exactly that here.
     */
    @Test
    void theLargestAdaOnlyUtxoIsNominatedNotTheFirst() {
        Utxo tiny = Utxo.builder().txHash("01".repeat(32)).outputIndex(0)
                .address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(1_000_000L)))).build();
        Utxo ample = Utxo.builder().txHash("02".repeat(32)).outputIndex(0)
                .address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L)))).build();

        // tiny FIRST in the list — first-in-list selection would take it and fail on collateral.
        Wiring w = wiringWithWallet(List.of(ready(0L)), List.of(tiny, ample));
        w.executor().cycle();

        assertEquals(1, w.submitted().size(),
                "the ample utxo must be nominated; taking the 1 ada one first would produce a "
                        + "negative collateral return and an unparseable transaction");
    }

    /**
     * ⛔ <b>THE INVERSE OF WHAT THESE THREE TESTS USED TO ASSERT.</b> Until 2026-09-04 this path read
     * {@code loans.submittable-network} and refused to submit unless it named the node's own network;
     * three tests here pinned that gate, its {@code preview} default, and the fact that stating
     * mainnet lifted it.
     *
     * <p>The key was removed on Giovanni's ruling — <i>"a barrier that silently blocks submission
     * even when everything else is armed is a bug, not a safeguard"</i> — so an armed compound node
     * now submits on whichever network it is pointed at. Kept as the regression guard: any
     * re-introduced network check inside {@code submit} turns this red.
     *
     * <p>⚑ <b>And the asymmetry it leaves is RULED, not outstanding.</b> Liquidation has
     * {@code mode == live} plus the market's effective mode plus two floors; compound has
     * {@code compound.enabled} plus one floor. That was put to Giovanni directly on 2026-09-04 and
     * he answered it: compound stays <b>ON/OFF plus a minimum margin</b> — <i>"shadow doesn't make
     * sense, it's a low-risk operation."</i> It advances no capital and moves already-repaid
     * principal into the pool that is owed it.
     *
     * <p>⛔ <b>So do not add a compound mode or a second switch.</b> It was considered and declined.
     * The same ruling removed liquidation's own redundant second boolean
     * ({@code loans.liquidation.enabled}) for the same reason: a gate that restates another gate is
     * cost without safety. <b>The paths differ because the risks differ, deliberately.</b>
     */
    @Test
    void anArmedNodeSubmitsOnWhicheverNetworkItIsPointedAt() {
        for (String networkName : List.of("mainnet", "preprod", "preview")) {
            Wiring w = wiringOnNetwork(List.of(ready(0L)), networkName);
            w.executor().cycle();
            assertEquals(1, w.submitted().size(),
                    "on " + networkName + ": armed, funded and profitable under the stated floor "
                            + "must submit — there is no second network value left to disagree with "
                            + "config.network");
        }
    }

    /**
     * ⚠ The one thing that DOES still hold this path back, asserted beside the removal so the pair
     * reads together: {@code loans.compound.enabled}. It is the whole list.
     */
    @Test
    void anUnarmedNodeStillSubmitsNothingOnAnyNetwork() {
        for (String networkName : List.of("mainnet", "preview")) {
            Wiring w = wiring(List.of(ready(0L)), false, -2_000_000L, List.of(wallet()), networkName);
            w.executor().cycle();
            assertTrue(w.submitted().isEmpty(),
                    "on " + networkName + ": loans.compound.enabled=false is the ONLY policy switch "
                            + "on this path — ruled sufficient because compound is low-risk — so it "
                            + "had better hold");
        }
    }

    /**
     * ⛔ FAB-108 OWN-B: the executor reads the compound reference scripts when it BUILDS, not when it is
     * constructed. The executor bean may exist before {@code LoansReferenceScriptVerifier}'s
     * {@code @PostConstruct} narrows the list, so the narrowing here happens AFTER construction, as it does
     * there. Both coordinates publish a script in the universe the executor resolves against, so a
     * construction-time read would still reference B; only a call-time read references A alone.
     */
    @Test
    void theBuilderIsHandedTheReferenceScriptsAsNarrowedAfterTheExecutorWasBuilt() {
        String txA = "5a".repeat(32);
        String txB = "5b".repeat(32);
        String hashA = "a5".repeat(28);
        String hashB = "b5".repeat(28);
        var configuration = new AppConfig.CompoundConfiguration(true, 60L, BigInteger.valueOf(-2_000_000L));
        org.springframework.test.util.ReflectionTestUtils.setField(configuration, "referenceScripts",
                txA + "#0," + txB + "#0");
        java.util.concurrent.atomic.AtomicReference<CompoundTransactionBuilder.Request> handed =
                new java.util.concurrent.atomic.AtomicReference<>();
        CompoundTransactionBuilder recording = org.mockito.Mockito.mock(CompoundTransactionBuilder.class);
        org.mockito.Mockito.when(recording.build(org.mockito.ArgumentMatchers.any(CompoundTransactionBuilder.Request.class)))
                .thenAnswer(invocation -> {
                    handed.set(invocation.getArgument(0));
                    throw new IllegalStateException("recorded; the transaction itself is not under test here");
                });
        List<Utxo> universe = new ArrayList<>(universe());
        universe.add(referenceScriptAt(txA, hashA));
        universe.add(referenceScriptAt(txB, hashB));
        CompoundExecutor executor = executorWithBuilder(configuration, recording, universe);

        configuration.narrowReferenceScripts(List.of(
                new com.bloxbean.cardano.client.transaction.spec.TransactionInput(txA, 0)));
        executor.cycle();

        assertNotNull(handed.get(), "the cycle never reached the builder, so this test says nothing");
        assertEquals(java.util.Map.of(hashA, new com.bloxbean.cardano.client.transaction.spec.TransactionInput(txA, 0)),
                handed.get().referenceScripts(),
                "the builder must be handed only the coordinate the boot narrowing kept, not the list the "
                        + "executor saw when it was constructed");
    }

    /** An output at {@code txHash#0} that publishes the reference script {@code scriptHash}. */
    private static Utxo referenceScriptAt(String txHash, String scriptHash) {
        return Utxo.builder().txHash(txHash).outputIndex(0).address(ACCOUNT.baseAddress())
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                .referenceScriptHash(scriptHash).build();
    }

    /** The {@code wiring(...)} executor, with the builder stated and the universe the executor resolves against. */
    private static CompoundExecutor executorWithBuilder(AppConfig.CompoundConfiguration configuration,
                                                        CompoundTransactionBuilder builder, List<Utxo> universe) {
        var network = new AppConfig.Network();
        network.setNetworkForTest("preview");
        var blockEventListener = new BlockEventListener(null);
        blockEventListener.getIsSyncing().set(false);
        CompoundExecutor.TransactionSubmitter submitter = bytes -> {
            throw new AssertionError("nothing is built, so nothing may be submitted");
        };
        return new CompoundExecutor(configuration, network, blockEventListener,
                new FakeAppUtxoService(List.of(wallet())), ACCOUNT,
                new FakeScanner(List.of(ready(0L))), new CompoundEconomics(configuration, network, pricingService()),
                builder, new FakeResolver(), LoanFixtures.utxoSupplier(universe), LoanFixtures.converters(), submitter);
    }

    // ---- FAB-144: the most profitable compound first ------------------------------------------

    /** {@link #ready(long)} at its own escrow ref, so two ready candidates can be told apart. */
    private static CompoundCandidate readyAt(String escrowTxHash, long feePerMille) {
        CompoundCandidate base = ready(feePerMille);
        Utxo escrow = utxo("escrow");
        escrow.setTxHash(escrowTxHash);
        return new CompoundCandidate(base.loanId(), escrow, base.escrowDatum(), base.addedLiquidity(),
                base.bond(), base.poolId(), base.pool(), base.poolManager(), base.feePerMille(),
                base.principalIsAda(), base.exclusion(), base.detail());
    }

    /** One disarmed cycle's INFO lines, in the order the executor logged them. */
    private static List<String> infoLinesOfOneCycle(Wiring w) {
        var logger = (Logger) LoggerFactory.getLogger(CompoundExecutor.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            w.executor().cycle();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * FAB-144, cycle level. Scanned refused-first, then the 10/1000 escrow, then the 20/1000 one: the
     * cycle must walk the 20/1000 escrow first (582,185 lovelace on 29,109,268), then the 10/1000
     * (291,092), and the structurally refused one last — still reported, never dropped. Disarmed, so
     * each ready candidate says READY BUT NOT ARMED and nothing is built.
     */
    @Test
    void aCycleWalksTheMostProfitableCompoundFirstAndARefusedOneLast() {
        CompoundCandidate tenPerMille = readyAt("01".repeat(32), 10L);
        CompoundCandidate twentyPerMille = readyAt("02".repeat(32), 20L);
        String cheap = tenPerMille.escrowRef();
        String rich = twentyPerMille.escrowRef();
        Wiring w = wiring(List.of(refused(), tenPerMille, twentyPerMille), false, 0L);

        List<String> info = infoLinesOfOneCycle(w);

        List<String> walked = info.stream()
                .filter(line -> line.startsWith("compound READY but NOT ARMED")
                        || line.startsWith("compound REFUSED"))
                .toList();
        assertEquals(3, walked.size(), "every candidate is reported, each once: " + info);
        assertTrue(walked.get(0).contains(rich), "the 20/1000 escrow first: " + walked);
        assertTrue(walked.get(1).contains(cheap), "the 10/1000 escrow second: " + walked);
        assertTrue(walked.get(2).startsWith("compound REFUSED"), "the refused one last: " + walked);
        assertEquals(List.of("compound order (most profitable first): " + rich + " ≈582185 lovelace, "
                        + cheap + " ≈291092 lovelace, " + refused().escrowRef() + " unpriced"),
                info.stream().filter(line -> line.startsWith("compound order")).toList(),
                "one INFO line naming the order: " + info);
        assertTrue(w.submitted().isEmpty(), "a disarmed loop transmits nothing");
    }

    /** {@link #readyAt} with no {@code addedLiquidity} — ready on paper, but nothing to price. */
    private static CompoundCandidate readyWithoutAmountAt(String escrowTxHash) {
        CompoundCandidate base = readyAt(escrowTxHash, 20L);
        return new CompoundCandidate(base.loanId(), base.escrow(), base.escrowDatum(), null, base.bond(),
                base.poolId(), base.pool(), base.poolManager(), base.feePerMille(), base.principalIsAda(),
                null, base.detail());
    }

    /** {@link #readyAt} whose bond names a non-ada principal — the scanner refuses these today. */
    private static CompoundCandidate tokenPrincipalAt(String escrowTxHash) {
        CompoundCandidate base = readyAt(escrowTxHash, 20L);
        var token = new com.fluidtokens.aquarium.offchain.model.AssetType("d0".repeat(28), "5553444d");
        LenderBond bond = new LenderBond(base.bond().txHash(), base.bond().outputIndex(), base.bond().address(),
                base.bond().loanId(), base.bond().inlineDatum(),
                LoanFixtures.bondDatum(BigInteger.valueOf(50), LoanFixtures.noStakeCredential(), token));
        return new CompoundCandidate(base.loanId(), base.escrow(), base.escrowDatum(), base.addedLiquidity(),
                bond, base.poolId(), base.pool(), base.poolManager(), base.feePerMille(), false, null,
                base.detail());
    }

    /**
     * FAB-144: the compound ranking key is {@code expectedFee} — 29,109,268 × 20 / 1000 = 582,185.36,
     * truncated to 582,185 lovelace — and empty, never thrown, for whatever cannot be priced.
     */
    @Test
    void theCompoundRankingKeyIsTheExpectedFeeAndEmptyForWhatCannotBePriced() {
        assertEquals(Optional.of(BigInteger.valueOf(582_185L)),
                CompoundExecutor.expectedFeeLovelace(readyAt("02".repeat(32), 20L)));
        assertEquals(Optional.of(BigInteger.ZERO), CompoundExecutor.expectedFeeLovelace(ready(0L)),
                "a zero-fee pool is priced at zero, not unpriced");
        assertEquals(Optional.empty(), CompoundExecutor.expectedFeeLovelace(refused()),
                "structurally unready");
        assertEquals(Optional.empty(), CompoundExecutor.expectedFeeLovelace(readyWithoutAmountAt("03".repeat(32))),
                "no addedLiquidity");
        assertEquals(Optional.empty(), CompoundExecutor.expectedFeeLovelace(tokenPrincipalAt("05".repeat(32))),
                "a non-ada principal: expectedFee is not lovelace there");
        assertEquals(Optional.empty(), CompoundExecutor.expectedFeeLovelace(null));
    }

    /**
     * FAB-144: descending by expected fee; a tie breaks on {@code escrowRef} ascending; the structurally
     * unready, the amount-less and the non-ada candidates all go after every priced one, by escrow ref.
     * A permutation of the input, never a filter.
     */
    @Test
    void mostProfitableFirstOrdersCompoundsWithTheUnpricedLastByEscrowRef() {
        CompoundCandidate refused = refused();                          // fb48…#2, unpriced
        CompoundCandidate noAmount = readyWithoutAmountAt("03".repeat(32)); // unpriced
        CompoundCandidate nonAda = tokenPrincipalAt("05".repeat(32));     // unpriced
        CompoundCandidate ten = readyAt("01".repeat(32), 10L);            // 291,092
        CompoundCandidate twentyB = readyAt("04".repeat(32), 20L);        // 582,185
        CompoundCandidate twentyA = readyAt("02".repeat(32), 20L);        // 582,185
        List<CompoundCandidate> scanned = List.of(refused, noAmount, nonAda, ten, twentyB, twentyA);

        List<CompoundCandidate> ordered = CompoundExecutor.mostProfitableFirst(scanned,
                CompoundExecutor::expectedFeeLovelace);

        assertEquals(List.of(twentyA.escrowRef(), twentyB.escrowRef(), ten.escrowRef(),
                        noAmount.escrowRef(), nonAda.escrowRef(), refused.escrowRef()),
                ordered.stream().map(CompoundCandidate::escrowRef).toList());
        assertEquals(scanned.size(), ordered.size(), "nothing dropped, nothing duplicated");
        for (CompoundCandidate candidate : scanned) {
            assertEquals(1, ordered.stream().filter(o -> o == candidate).count(), candidate.escrowRef());
        }
        assertEquals(List.of(refused, noAmount, nonAda, ten, twentyB, twentyA), scanned,
                "a NEW list: the scan's own list is left as it was");
    }

    /** A key function that throws makes that candidate unpriced — it is never lost. */
    @Test
    void aThrowingCompoundKeyMakesThatCandidateUnpricedNotLost() {
        CompoundCandidate ten = readyAt("01".repeat(32), 10L);
        CompoundCandidate twenty = readyAt("02".repeat(32), 20L);

        List<CompoundCandidate> ordered = CompoundExecutor.mostProfitableFirst(List.of(twenty, ten),
                candidate -> {
                    if (candidate == twenty) {
                        throw new IllegalStateException("boom");
                    }
                    return CompoundExecutor.expectedFeeLovelace(candidate);
                });

        assertEquals(List.of(ten, twenty), ordered);
    }

    /**
     * {@link #readyAt} carrying an exclusion: structurally UNREADY, yet with everything else a price
     * needs — an {@code addedLiquidity}, a bond and an ada principal — so only readiness can refuse it.
     */
    private static CompoundCandidate unreadyButPriceableAt(String escrowTxHash) {
        CompoundCandidate base = readyAt(escrowTxHash, 20L);
        return new CompoundCandidate(base.loanId(), base.escrow(), base.escrowDatum(), base.addedLiquidity(),
                base.bond(), base.poolId(), base.pool(), base.poolManager(), base.feePerMille(),
                base.principalIsAda(), CompoundExclusion.POOL_NOT_LIVE, "the pool NFT is burned");
    }

    /**
     * FAB-144: a structurally unready candidate is unpriced BECAUSE it is unready. {@link #refused()}
     * cannot show that — it has no amount and no bond, so it is empty for those reasons too; this one
     * has both and an ada principal, and would be priced at 582,185 lovelace were readiness not checked.
     */
    @Test
    void aStructurallyUnreadyCompoundIsUnpricedEvenWithAnAmountABondAndAnAdaPrincipal() {
        CompoundCandidate unready = unreadyButPriceableAt("06".repeat(32));
        assertFalse(unready.structurallyReady(), "the fixture is unready");
        assertNotNull(unready.addedLiquidity(), "the fixture carries an amount");
        assertNotNull(unready.bond(), "the fixture carries a bond");
        assertTrue(unready.bond().datum().principalAsset().isAda(), "the fixture's principal is ada");

        assertEquals(Optional.empty(), CompoundExecutor.expectedFeeLovelace(unready),
                "unready: unpriced, whatever else it carries");
    }

    /**
     * FAB-144: the order is logged only when there was a choice to make. A cycle with exactly ONE
     * candidate logs no {@code compound order} line — and still reports it — while a cycle with TWO
     * logs exactly one.
     */
    @Test
    void theCompoundOrderIsNotLoggedForOneCandidateAndLoggedExactlyOnceForTwo() {
        CompoundCandidate only = readyAt("01".repeat(32), 10L);
        List<String> single = infoLinesOfOneCycle(wiring(List.of(only), false, 0L));
        assertEquals(1, single.stream().filter(line -> line.startsWith("compound READY but NOT ARMED")).count(),
                "the one candidate is still reported: " + single);
        assertEquals(List.of(), single.stream().filter(line -> line.startsWith("compound order")).toList(),
                "one candidate: no choice was made, so no order is logged");

        List<String> pair = infoLinesOfOneCycle(wiring(
                List.of(readyAt("01".repeat(32), 10L), readyAt("02".repeat(32), 20L)), false, 0L));
        assertEquals(1, pair.stream().filter(line -> line.startsWith("compound order")).count(),
                "two candidates: exactly one order line: " + pair);
    }

    // ---- fakes -------------------------------------------------------------------------------

    private static class FakeScanner extends CompoundCandidateScanner {
        private final List<CompoundCandidate> candidates;

        FakeScanner(List<CompoundCandidate> candidates) {
            super(null, REGISTRY, null, null);
            this.candidates = candidates;
        }

        @Override
        public Scan scan() {
            return new Scan(candidates);
        }
    }

    private static final class FakeResolver extends LiquidationUtxoResolver {
        FakeResolver() {
            super(null, null, null);
        }

        @Override
        public Optional<Utxo> resolveBondUtxo(LenderBond bond) {
            return Optional.of(utxo("bond"));
        }

        @Override
        public Optional<Utxo> resolveConfigUtxo() {
            return Optional.of(utxo("config"));
        }

        @Override
        public Optional<Utxo> resolveLmConfigUtxo() {
            return Optional.of(utxo("lmConfig"));
        }
    }

    private static final class FakeAppUtxoService extends AppUtxoService {
        private final List<Utxo> utxos;

        FakeAppUtxoService(List<Utxo> utxos) {
            super(null, null);
            this.utxos = utxos;
        }

        @Override
        public List<Utxo> listWalletUtxo() {
            return utxos;
        }
    }
}
