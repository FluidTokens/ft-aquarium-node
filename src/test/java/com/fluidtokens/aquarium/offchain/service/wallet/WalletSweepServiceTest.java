package com.fluidtokens.aquarium.offchain.service.wallet;

import static org.junit.jupiter.api.Assertions.assertThrows;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.bloxbean.cardano.yaci.store.events.internal.CommitEvent;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import org.cardanofoundation.conversions.CardanoConverters;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup wallet sweep, driven offline: a fake Blockfrost (pages, 404s, failures), a stub index
 * answering {@code findById}, a counting submitter that also moves the fake chain, and a mutable clock.
 * Every assertion about a submitted transaction reads the DESERIALISED bytes that reached the submitter.
 */
class WalletSweepServiceTest {

    private static final Account ACCOUNT = new Account(Networks.preview());
    private static final String WALLET = ACCOUNT.baseAddress();
    private static final String ENTERPRISE = AddressProvider.getEntAddress(
            ACCOUNT.getBaseAddress().getPaymentCredential().orElseThrow(), Networks.preview()).toBech32();
    private static final CardanoConverters CONVERTERS = LoanFixtures.converters();

    private static final String UNIT_A = "aa".repeat(28) + "41";
    private static final String JUNK = "cc".repeat(28) + "4a31";
    private static final BigInteger TEN_ADA = WalletShape.SHAPED_TOKEN_LOVELACE;
    private static final BigInteger FIVE_ADA = BigInteger.valueOf(5_000_000L);

    private static int counter = 0;

    // ---- the fakes -----------------------------------------------------------------------------

    /** A clock a test can move. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-01T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final WalletReadiness readiness = new WalletReadiness();

    /** Blockfrost's view: address → UTxOs. An address absent from the map answers 404, as Blockfrost does. */
    private final Map<String, List<Utxo>> chain = new LinkedHashMap<>();
    private final List<String> listingCalls = new ArrayList<>();
    private Result<List<Utxo>> forcedListingResult;
    private RuntimeException forcedListingException;

    /** The local index: refs it holds a row for (spent or not). */
    private final Set<String> index = new HashSet<>();
    private final AtomicInteger findByIdCalls = new AtomicInteger();

    private final List<Transaction> submitted = new ArrayList<>();
    /** When true, a submit lands on the fake chain: inputs leave the listing, outputs join it. */
    private boolean submitLands = true;
    /** When set, every submit is recorded and then throws it: the bytes' fate is unknown. */
    private Exception submitThrows;
    /** When set, every submit is recorded and then throws this ERROR (not an Exception). */
    private Error submitError;
    /** When set, every findById throws it: the local index fails during the comparison. */
    private RuntimeException indexThrows;
    /** When set, a submit lands (if {@link #submitLands}) and THEN reports this HTTP error code. */
    private Integer submitFailsWithCode;
    /** How long one Blockfrost page call takes on the test clock (zero: instantaneous). */
    private Duration listingLatency = Duration.ZERO;

    private ProtocolParamsSupplier params = LoanFixtures.protocolParams();
    private List<AppConfig.LiquidationConfiguration.Market> markets = List.of(anticipate(UNIT_A));

    /**
     * The three things that spend from the wallet. The tank processor is on by default, so every test
     * that does not ask about the "something spends" gate drives a node whose sweep runs.
     */
    private boolean tankProcessorEnabled = true;
    private AppConfig.LiquidationConfiguration liquidation = liquidation(AppConfig.LiquidationConfiguration.Mode.DISABLED);
    private AppConfig.CompoundConfiguration compound = compound(false);

    private WalletSweepService service() {
        return new WalletSweepService(index(), readiness, ACCOUNT, Networks.preview(), () -> markets,
                () -> params.getProtocolParams(), CONVERTERS, this::page, this::submit, clock,
                () -> WalletSweepService.somethingSpends(tankProcessorEnabled, liquidation, compound));
    }

    private static AppConfig.LiquidationConfiguration liquidation(AppConfig.LiquidationConfiguration.Mode mode) {
        return new AppConfig.LiquidationConfiguration(mode, 60, 120, 30, BigInteger.valueOf(1_500_000L), 50);
    }

    private static AppConfig.CompoundConfiguration compound(boolean enabled) {
        return new AppConfig.CompoundConfiguration(enabled, 60, BigInteger.ZERO);
    }

    private Result<List<Utxo>> page(String address, int page) {
        listingCalls.add(address + "@" + page);
        clock.advance(listingLatency);
        if (forcedListingException != null) {
            throw forcedListingException;
        }
        if (forcedListingResult != null) {
            return forcedListingResult;
        }
        List<Utxo> all = chain.get(address);
        if (all == null) {
            return Result.<List<Utxo>>error("Not Found").code(404);
        }
        int from = Math.min((page - 1) * 100, all.size());
        int to = Math.min(from + 100, all.size());
        return Result.<List<Utxo>>success("ok").withValue(new ArrayList<>(all.subList(from, to))).code(200);
    }

    private Result<String> submit(byte[] bytes) throws Exception {
        Transaction tx = Transaction.deserialize(bytes);
        submitted.add(tx);
        if (submitError != null) {
            throw submitError;
        }
        if (submitThrows != null) {
            throw submitThrows;
        }
        String hash = TransactionUtil.getTxHash(bytes);
        if (submitLands) {
            land(tx);
        }
        if (submitFailsWithCode != null) {
            return Result.<String>error("Bad Gateway").code(submitFailsWithCode);
        }
        return Result.<String>success("ok").withValue(hash);
    }

    /** The transaction lands on the fake chain: its inputs leave the listing, its outputs join it. */
    private void land(Transaction tx) throws Exception {
        String hash = TransactionUtil.getTxHash(tx);
        Set<String> spent = tx.getBody().getInputs().stream()
                .map(i -> i.getTransactionId() + "#" + i.getIndex()).collect(Collectors.toSet());
        chain.replaceAll((address, utxos) -> utxos.stream().filter(u -> !spent.contains(ref(u))).collect(
                Collectors.toCollection(ArrayList::new)));
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        for (int i = 0; i < outputs.size(); i++) {
            Utxo u = new Utxo();
            u.setTxHash(hash);
            u.setOutputIndex(i);
            u.setAddress(outputs.get(i).getAddress());
            List<Amount> amounts = new ArrayList<>();
            amounts.add(Amount.lovelace(outputs.get(i).getValue().getCoin()));
            units(outputs.get(i)).forEach((unit, quantity) -> {
                if (!"lovelace".equals(unit)) {
                    amounts.add(Amount.asset(unit, quantity));
                }
            });
            u.setAmount(amounts);
            chain.computeIfAbsent(u.getAddress(), a -> new ArrayList<>()).add(u);
        }
    }

    private UtxoRepository index() {
        return (UtxoRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{UtxoRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "findById" -> {
                        findByIdCalls.incrementAndGet();
                        if (indexThrows != null) {
                            throw indexThrows;
                        }
                        UtxoId id = (UtxoId) args[0];
                        yield index.contains(id.getTxHash() + "#" + id.getOutputIndex())
                                ? Optional.of(new AddressUtxoEntity()) : Optional.empty();
                    }
                    case "toString" -> "stub UtxoRepository";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(
                            "the sweep called " + method.getName() + "; it must only ask findById");
                });
    }

    // ---- fixtures ------------------------------------------------------------------------------

    private static AppConfig.LiquidationConfiguration.Market anticipate(String unit) {
        var market = new AppConfig.LiquidationConfiguration.Market();
        market.setUnit(unit);
        market.setAction(AppConfig.LiquidationConfiguration.Action.ANTICIPATE);
        market.setCap(BigInteger.ONE);
        return market;
    }

    private static Utxo utxo(String address, long lovelace, Object... unitQuantityPairs) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(BigInteger.valueOf(lovelace)));
        for (int i = 0; i < unitQuantityPairs.length; i += 2) {
            amounts.add(Amount.asset((String) unitQuantityPairs[i], BigInteger.valueOf((Long) unitQuantityPairs[i + 1])));
        }
        Utxo u = new Utxo();
        u.setTxHash(String.format("%064x", 0x5000 + ++counter));
        u.setOutputIndex(counter % 3);
        u.setAddress(address);
        u.setAmount(amounts);
        return u;
    }

    private static String ref(Utxo u) {
        return u.getTxHash() + "#" + u.getOutputIndex();
    }

    private void onChain(Utxo... utxos) {
        for (Utxo u : utxos) {
            chain.computeIfAbsent(u.getAddress(), a -> new ArrayList<>()).add(u);
        }
    }

    private void indexed(Utxo... utxos) {
        for (Utxo u : utxos) {
            index.add(ref(u));
        }
    }

    private long slotNow() {
        return CONVERTERS.time().toSlot(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /** The slot of a block minted {@code behind} before the test clock. */
    private long slotBehind(Duration behind) {
        return CONVERTERS.time().toSlot(LocalDateTime.ofInstant(clock.instant().minus(behind), ZoneOffset.UTC));
    }

    /** A commit event for a block minted now: near tip. */
    private void atTip(WalletSweepService service) {
        service.onCommit(slotNow());
    }

    /** The near-tip event that lists, then 20 s later the next block, which is past the settle slot. */
    private void runOneShot(WalletSweepService service) {
        atTip(service);
        clock.advance(Duration.ofSeconds(20));
        atTip(service);
    }

    /** How many times the wallet was listed: each listing starts with page 1 of the base address. */
    private long listings() {
        return listingCalls.stream().filter(c -> c.equals(WALLET + "@1")).count();
    }

    /**
     * Once done, twenty-five more blocks change nothing: no listing, no comparison, no submit, the gate
     * stays open and the state stays put.
     */
    private void assertDoneForGood(WalletSweepService service) {
        assertTrue(readiness.isWalletReady(), "the one-shot finished: the gate must be open: " + readiness.sweepState());
        assertTrue(readiness.sweepState().startsWith("done: "), readiness.sweepState());
        String state = readiness.sweepState();
        int calls = listingCalls.size();
        int finds = findByIdCalls.get();
        int submits = submitted.size();
        for (int i = 0; i < 25; i++) {
            clock.advance(Duration.ofSeconds(20));
            assertDoesNotThrow(() -> atTip(service));
        }
        assertEquals(calls, listingCalls.size(), "no listing after the one-shot: " + listingCalls);
        assertTrue(listings() <= 1, "at most ONE listing per process: " + listingCalls);
        assertEquals(finds, findByIdCalls.get(), "no comparison after the one-shot");
        assertEquals(submits, submitted.size(), "no submit after the one-shot");
        assertTrue(readiness.isWalletReady(), "the gate never re-closes");
        assertEquals(state, readiness.sweepState());
    }

    private static Set<String> inputRefs(Transaction tx) {
        return tx.getBody().getInputs().stream().map(i -> i.getTransactionId() + "#" + i.getIndex())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private Set<String> listedRefs() {
        return chain.values().stream().flatMap(List::stream).map(WalletSweepServiceTest::ref)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Map<String, BigInteger> units(TransactionOutput output) {
        return WalletShapeTransactions.unitsOf(output.getValue());
    }

    // ---- waiting for tip ------------------------------------------------------------------------

    @Test
    void behindTipNothingIsListedOrComparedHoweverManyBlocksArrive() {
        onChain(utxo(WALLET, 200_000_000));
        WalletSweepService service = service();

        for (int minutes = 600; minutes > 5; minutes--) {   // a sync from ten hours behind to 6 minutes behind
            service.onCommit(slotBehind(Duration.ofMinutes(minutes)));
        }
        service.onCommit(slotBehind(Duration.ofMinutes(5).plusSeconds(1)));

        assertEquals(List.of(), listingCalls, "behind tip the sweep must not call Blockfrost");
        assertEquals(0, findByIdCalls.get(), "behind tip the sweep must not read the index");
        assertEquals(0, submitted.size());
        assertFalse(readiness.isWalletReady(), "the gate never opens before the node is near tip");
        assertEquals(WalletReadiness.WAITING_FOR_TIP, readiness.sweepState());
    }

    @Test
    void aBlockExactlyFiveMinutesOldIsNearTip() {
        onChain(utxo(WALLET, 200_000_000));
        WalletSweepService service = service();

        service.onCommit(slotBehind(Duration.ofMinutes(5).plusSeconds(1)));
        assertEquals(0, listings(), "one second past the boundary is behind tip");
        service.onCommit(slotBehind(Duration.ofMinutes(5)));
        assertEquals(1, listings(), "a block exactly five minutes old is near tip");
        assertEquals(WalletReadiness.SETTLING, readiness.sweepState());
    }

    @Test
    void aBlockJustInsideFiveMinutesIsNearTip() {
        onChain(utxo(WALLET, 200_000_000));
        WalletSweepService service = service();

        service.onCommit(slotBehind(Duration.ofMinutes(4).plusSeconds(59)));
        assertEquals(1, listings());
        assertFalse(readiness.isWalletReady(), "listed, not yet compared");
    }

    @Test
    void theNearTipCheckUsesTheBlocksTimeNotItsSlotNumberAgainstAnotherClock() {
        onChain(utxo(WALLET, 200_000_000));
        WalletSweepService service = service();
        // The block time is read through CardanoConverters: a block minted 4 minutes ago by the test clock.
        long slot = slotBehind(Duration.ofMinutes(4));
        assertEquals(clock.instant().minus(Duration.ofMinutes(4)),
                CONVERTERS.slot().slotToTime(slot).toInstant(ZoneOffset.UTC));
        service.onCommit(slot);
        assertEquals(1, listings());
    }

    // ---- the one Blockfrost read, and settling --------------------------------------------------

    @Test
    void nothingIsComparedBeforeABlockAtOrAfterTheSettleSlotTakenWhenTheListingCompleted() {
        Utxo a = utxo(WALLET, 150_000_000);
        onChain(a);
        indexed(a);
        listingLatency = Duration.ofSeconds(60);   // one page call; the listing is base + enterprise
        WalletSweepService service = service();

        Instant started = clock.instant();
        atTip(service);
        Instant completed = started.plus(Duration.ofSeconds(120));
        assertEquals(completed, clock.instant());
        assertEquals(WalletReadiness.SETTLING, readiness.sweepState());

        long settleSlot = CONVERTERS.time().toSlot(LocalDateTime.ofInstant(completed, ZoneOffset.UTC));
        // Blocks past the listing's START, but before the moment Blockfrost answered.
        service.onCommit(slotNow() - 60);
        service.onCommit(settleSlot - 1);
        assertEquals(0, findByIdCalls.get(), "nothing may be compared before the settle slot");
        assertFalse(readiness.isWalletReady());
        assertEquals(1, listings(), "settling makes no further Blockfrost call");

        service.onCommit(settleSlot);
        assertTrue(findByIdCalls.get() > 0, "the block AT the settle slot compares");
        assertEquals(WalletReadiness.NOTHING_TO_REBALANCE, readiness.sweepState());
        assertDoneForGood(service);
    }

    @Test
    void nothingMissingOpensTheGateWithoutASubmit() {
        Utxo a = utxo(WALLET, 200_000_000);
        Utxo b = utxo(WALLET, 10_000_000, UNIT_A, 5L);
        onChain(a, b);
        indexed(a, b);
        WalletSweepService service = service();

        atTip(service);
        assertFalse(readiness.isWalletReady(), "the listing event only lists");
        clock.advance(Duration.ofSeconds(20));
        atTip(service);

        assertEquals(0, submitted.size());
        assertEquals(WalletReadiness.NOTHING_TO_REBALANCE, readiness.sweepState());
        assertDoneForGood(service);
    }

    @Test
    void oneMissingUtxoSubmitsExactlyOneShapedSweepOfTheWholeListingAndOpensTheGate() throws Exception {
        Utxo a = utxo(WALLET, 3_000_000, UNIT_A, 300L, JUNK, 7L);
        Utxo b = utxo(WALLET, 150_000_000);
        Utxo c = utxo(WALLET, 40_000_000);
        Utxo missing = utxo(WALLET, 25_000_000);
        onChain(a, b, c, missing);
        indexed(a, b, c);
        Set<String> wholeListing = listedRefs();
        WalletSweepService service = service();

        runOneShot(service);

        assertEquals(1, submitted.size());
        Transaction tx = submitted.getFirst();
        assertEquals(wholeListing, inputRefs(tx), "the sweep spends the WHOLE listing, not only what is missing");

        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        assertEquals(4, outputs.size(), "A + 10, junk + 10, collateral, change");
        assertEquals(Map.of("lovelace", TEN_ADA, UNIT_A, BigInteger.valueOf(300)), units(outputs.get(0)));
        assertEquals(Map.of("lovelace", TEN_ADA, JUNK, BigInteger.valueOf(7)), units(outputs.get(1)));
        assertEquals(Map.of("lovelace", FIVE_ADA), units(outputs.get(2)));
        assertEquals("done: rebalanced " + TransactionUtil.getTxHash(tx), readiness.sweepState());
        assertTrue(readiness.isWalletReady(), "the gate opens as soon as the rebalance is submitted");
        assertDoneForGood(service);
    }

    @Test
    void aRefusedShapeFallsBackToTheConsolidation() {
        // The engine's small wallet: the change would not be the largest output, so the shape is refused.
        Utxo tokens = utxo(WALLET, 1_500_000, UNIT_A, 300L, JUNK, 5L);
        Utxo ada = utxo(WALLET, 26_000_000);
        onChain(tokens, ada);
        indexed(tokens);
        Set<String> wholeListing = listedRefs();
        WalletSweepService service = service();

        runOneShot(service);

        assertEquals(1, submitted.size(), readiness.sweepState());
        Transaction tx = submitted.getFirst();
        assertEquals(wholeListing, inputRefs(tx));
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        assertEquals(2, outputs.size(), "the consolidation: every asset in one output, plus change");
        Map<String, BigInteger> assets = new java.util.TreeMap<>(units(outputs.get(0)));
        assets.remove("lovelace");
        assertEquals(Map.of(UNIT_A, BigInteger.valueOf(300), JUNK, BigInteger.valueOf(5)), assets);
        assertEquals(Set.of("lovelace"), units(outputs.get(1)).keySet());
        assertTrue(readiness.sweepState().startsWith("done: rebalanced "), readiness.sweepState());
        assertDoneForGood(service);
    }

    @Test
    void enterpriseAddressIsListedAndAnUnusedOneReadsAsEmpty() {
        Utxo base = utxo(WALLET, 200_000_000);
        onChain(base);
        indexed(base);
        WalletSweepService service = service();
        assertEquals(List.of(WALLET, ENTERPRISE), service.listedAddresses());

        runOneShot(service);

        assertTrue(listingCalls.contains(ENTERPRISE + "@1"), "the enterprise address must be listed: " + listingCalls);
        assertEquals(WalletReadiness.NOTHING_TO_REBALANCE, readiness.sweepState(),
                "an enterprise address Blockfrost 404s is empty, not an outage");
        assertDoneForGood(service);
    }

    @Test
    void anUnindexedEnterpriseUtxoIsSweptIntoTheBaseAddress() {
        Utxo base = utxo(WALLET, 150_000_000);
        Utxo enterprise = utxo(ENTERPRISE, 20_000_000);
        onChain(base, enterprise);
        indexed(base);
        Set<String> wholeListing = listedRefs();
        WalletSweepService service = service();

        runOneShot(service);

        assertEquals(1, submitted.size(), readiness.sweepState());
        Transaction tx = submitted.getFirst();
        assertEquals(wholeListing, inputRefs(tx), "the sweep spends the whole listing, enterprise UTxO included");
        assertTrue(inputRefs(tx).contains(ref(enterprise)));
        tx.getBody().getOutputs().forEach(o -> assertEquals(WALLET, o.getAddress(), "every output at the BASE address"));
        assertEquals(1, tx.getWitnessSet().getVkeyWitnesses().size(), "one key spends base and enterprise inputs");
        assertTrue(readiness.sweepState().startsWith("done: rebalanced "), readiness.sweepState());
        assertDoneForGood(service);
    }

    @Test
    void aFullPageIsFollowedAndTheShortPageIsRead() {
        List<Utxo> utxos = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            utxos.add(utxo(WALLET, 2_000_000));
        }
        Utxo missingOnPageTwo = utxo(WALLET, 100_000_000);
        utxos.add(missingOnPageTwo);
        onChain(utxos.toArray(Utxo[]::new));
        indexed(utxos.subList(0, 100).toArray(Utxo[]::new));
        WalletSweepService service = service();

        runOneShot(service);

        assertTrue(listingCalls.contains(WALLET + "@2"), "page 2 was never read: " + listingCalls);
        assertFalse(listingCalls.contains(WALLET + "@3"), "a short page ends the address: " + listingCalls);
        assertEquals(1, submitted.size(), "the UTxO on page 2 is missing from the index");
        assertEquals(101, inputRefs(submitted.getFirst()).size());
        assertDoneForGood(service);
    }

    @Test
    void anEmptyWalletHasNothingToRebalance() {
        chain.put(WALLET, new ArrayList<>());
        WalletSweepService service = service();

        runOneShot(service);

        assertEquals(0, submitted.size());
        assertEquals(WalletReadiness.NOTHING_TO_REBALANCE, readiness.sweepState());
        assertDoneForGood(service);
    }

    // ---- every failure: logged, the gate opens, nothing is tried again ---------------------------

    @Test
    void aListingErrorOpensTheGateAndIsNeverRetried() {
        onChain(utxo(WALLET, 150_000_000));
        forcedListingResult = Result.<List<Utxo>>error("Internal Server Error").code(500);
        WalletSweepService service = service();

        atTip(service);

        assertTrue(readiness.sweepState().startsWith("done: listing failed"), readiness.sweepState());
        assertTrue(readiness.sweepState().contains("500"), readiness.sweepState());
        assertEquals(0, findByIdCalls.get());
        forcedListingResult = null;   // Blockfrost recovers: still no second listing
        assertDoneForGood(service);
        assertEquals(1, listings());
        assertEquals(0, submitted.size());
    }

    @Test
    void aListingThatThrowsOpensTheGateAndIsNeverRetried() {
        onChain(utxo(WALLET, 150_000_000));
        forcedListingException = new IllegalStateException("blockfrost exploded");
        WalletSweepService service = service();

        assertDoesNotThrow(() -> atTip(service));

        assertTrue(readiness.sweepState().startsWith("done: listing failed"), readiness.sweepState());
        assertTrue(readiness.sweepState().contains("blockfrost exploded"), readiness.sweepState());
        forcedListingException = null;
        assertDoneForGood(service);
        assertEquals(1, listings());
        assertEquals(0, submitted.size());
    }

    @Test
    void aListingThatAnswersNothingOpensTheGateAndIsNeverRetried() {
        onChain(utxo(WALLET, 150_000_000));
        WalletSweepService service = new WalletSweepService(index(), readiness, ACCOUNT, Networks.preview(),
                () -> markets, () -> params.getProtocolParams(), CONVERTERS, (address, page) -> {
                    listingCalls.add(address + "@" + page);
                    return null;
                }, this::submit, clock, () -> true);

        atTip(service);

        assertTrue(readiness.sweepState().startsWith("done: listing failed"), readiness.sweepState());
        assertDoneForGood(service);
        assertEquals(1, listings());
    }

    @Test
    void bothBuildsRefusedSubmitsNothingOpensTheGateAndNeverRetries() {
        // The whole listing is one unindexed 0.1-ADA UTxO: too small to fund the shape's outputs, and too
        // small to pay the consolidation's fee, so both builders refuse.
        onChain(utxo(WALLET, 100_000));
        WalletSweepService service = service();

        runOneShot(service);

        assertEquals(0, submitted.size());
        assertTrue(readiness.sweepState().startsWith("done: rebalance failed: "), readiness.sweepState());
        assertDoneForGood(service);
        assertEquals(1, listings());
    }

    private static ProtocolParams withMaxTxSize(Integer maxTxSize) {
        ProtocolParams p = LoanFixtures.protocolParams().getProtocolParams();
        return ProtocolParams.builder()
                .minFeeA(p.getMinFeeA()).minFeeB(p.getMinFeeB()).maxTxSize(maxTxSize)
                .maxValSize(p.getMaxValSize()).coinsPerUtxoSize(p.getCoinsPerUtxoSize())
                .priceMem(p.getPriceMem()).priceStep(p.getPriceStep()).maxTxExMem(p.getMaxTxExMem())
                .maxTxExSteps(p.getMaxTxExSteps()).collateralPercent(p.getCollateralPercent())
                .maxCollateralInputs(p.getMaxCollateralInputs())
                .minFeeRefScriptCostPerByte(p.getMinFeeRefScriptCostPerByte())
                .protocolMajorVer(p.getProtocolMajorVer()).protocolMinorVer(p.getProtocolMinorVer())
                .costModelsRaw(p.getCostModelsRaw()).build();
    }

    @Test
    void aSignedTransactionOverMaxTxSizeIsRefusedEvenWhenTheUnsignedBodyFits() throws Exception {
        Utxo a = utxo(WALLET, 150_000_000);
        Utxo missing = utxo(WALLET, 25_000_000);
        onChain(a, missing);
        indexed(a);

        // Measure the unsigned build, then set maxTxSize between it and the signed size.
        var engine = new WalletShapeTransactions(ACCOUNT, LoanFixtures.protocolParams(), b -> null);
        var unsigned = engine.buildShaped(List.of(a, missing), WalletShape.relevantUnits(markets));
        assertTrue(unsigned.isBuilt(), unsigned.detail());
        int unsignedSize = unsigned.transaction().serialize().length;
        int signedSize = engine.sign(unsigned.transaction()).serialize().length;
        assertTrue(signedSize > unsignedSize + 50, "a vkey witness should add ~100 bytes");
        ProtocolParams tight = withMaxTxSize(unsignedSize + 10);
        params = () -> tight;
        WalletSweepService service = service();

        runOneShot(service);

        assertEquals(0, submitted.size(), "an oversize SIGNED transaction must never reach the wire");
        assertTrue(readiness.sweepState().startsWith("done: rebalance failed: signed transaction too large"),
                readiness.sweepState());
        assertDoneForGood(service);
        assertEquals(1, listings());
    }

    @Test
    void protocolParametersWithoutMaxTxSizeAtSignTimeRefuseTheSubmit() {
        Utxo a = utxo(WALLET, 150_000_000);
        Utxo missing = utxo(WALLET, 25_000_000);
        onChain(a, missing);
        indexed(a);
        // The engine builds with a real limit; the parameters read at sign time carry none (refreshed
        // between the build and the size check).
        ProtocolParams full = LoanFixtures.protocolParams().getProtocolParams();
        ProtocolParams noLimit = LoanFixtures.protocolParams().getProtocolParams();
        noLimit.setMaxTxSize(null);
        params = () -> StackWalker.getInstance().walk(frames -> frames.anyMatch(
                f -> f.getMethodName().equals("signedSizeProblem"))) ? noLimit : full;
        WalletSweepService service = service();

        runOneShot(service);

        assertEquals(0, submitted.size(), "no limit to check against means no submit");
        assertTrue(readiness.sweepState().startsWith("done: rebalance failed: protocol parameters carry no maxTxSize"),
                readiness.sweepState());
        assertDoneForGood(service);
    }

    @Test
    void aRejectedSubmitIsNeverRetried() {
        Utxo a = utxo(WALLET, 150_000_000);
        Utxo missing = utxo(WALLET, 25_000_000);
        onChain(a, missing);
        indexed(a);
        submitLands = false;
        submitFailsWithCode = 400;
        WalletSweepService service = service();

        runOneShot(service);

        assertEquals(1, submitted.size());
        assertTrue(readiness.sweepState().startsWith("done: rebalance failed: submit rejected"), readiness.sweepState());
        submitFailsWithCode = null;   // the wire recovers: still no second submit
        assertDoneForGood(service);
        assertEquals(1, submitted.size());
        assertEquals(1, listings());
    }

    @Test
    void aSubmitThatThrowsIsNeverRetriedAndNothingEscapes() {
        Utxo a = utxo(WALLET, 150_000_000);
        Utxo missing = utxo(WALLET, 25_000_000);
        onChain(a, missing);
        indexed(a);
        submitThrows = new java.io.IOException("connection reset mid-submit");
        WalletSweepService service = service();

        atTip(service);
        clock.advance(Duration.ofSeconds(20));
        assertDoesNotThrow(() -> atTip(service));

        assertEquals(1, submitted.size());
        assertTrue(readiness.sweepState().startsWith("done: rebalance failed: "), readiness.sweepState());
        assertTrue(readiness.sweepState().contains("connection reset mid-submit"), readiness.sweepState());
        submitThrows = null;
        assertDoneForGood(service);
        assertEquals(1, submitted.size());
        assertEquals(1, listings());
    }

    @Test
    void anErrorAfterTheSubmitEndsTheOneShotBeforeItPropagatesSoNothingIsReplayed() {
        Utxo a = utxo(WALLET, 150_000_000);
        Utxo missing = utxo(WALLET, 25_000_000);
        onChain(a, missing);
        indexed(a);
        submitError = new LinkageError("an Error escaping the submit");
        WalletSweepService service = service();

        atTip(service);
        clock.advance(Duration.ofSeconds(20));
        // The Error reaches Yaci (which may roll the block back) — but only after the one-shot is DONE.
        assertThrows(LinkageError.class, () -> atTip(service));

        assertEquals(1, submitted.size());
        assertTrue(readiness.sweepState().startsWith("done: rebalance failed: "), readiness.sweepState());
        submitError = null;
        assertDoneForGood(service);
        assertEquals(1, submitted.size(), "a replayed block must not compare or submit again");
    }

    @Test
    void aClockThatThrowsBeforeTipKeepsWaitingWithTheGateClosed() {
        aWalletThatNeedsASweep();
        Clock broken = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                throw new IllegalStateException("clock unavailable");
            }
        };
        WalletSweepService service = new WalletSweepService(index(), readiness, ACCOUNT, Networks.preview(),
                () -> markets, () -> params.getProtocolParams(), CONVERTERS, this::page, this::submit, broken,
                () -> true);

        assertDoesNotThrow(() -> service.onCommit(slotNow()));

        assertFalse(readiness.isWalletReady(), "a near-tip check that cannot be made must not release anything");
        assertEquals(WalletReadiness.WAITING_FOR_TIP, readiness.sweepState());
        assertEquals(0, listings());
    }

    @Test
    void aCommitEventWithoutMetadataNeverReachesYaciAsAnException() {
        aWalletThatNeedsASweep();
        WalletSweepService service = service();
        CommitEvent<?> broken = Mockito.mock(CommitEvent.class);
        Mockito.when(broken.getMetadata()).thenReturn(null);

        assertDoesNotThrow(() -> service.onCommitEvent(broken));
        assertEquals(WalletReadiness.WAITING_FOR_TIP, readiness.sweepState());
        assertFalse(readiness.isWalletReady());
    }

    @Test
    void anIndexThatThrowsDuringTheComparisonOpensTheGateAndNothingEscapes() {
        onChain(utxo(WALLET, 150_000_000));
        indexThrows = new IllegalStateException("database gone");
        WalletSweepService service = service();

        atTip(service);
        clock.advance(Duration.ofSeconds(20));
        assertDoesNotThrow(() -> atTip(service));

        assertEquals(0, submitted.size());
        assertTrue(readiness.sweepState().startsWith("done: rebalance failed: "), readiness.sweepState());
        assertTrue(readiness.sweepState().contains("database gone"), readiness.sweepState());
        indexThrows = null;
        assertDoneForGood(service);
        assertEquals(1, listings());
    }

    // ---- only on a node where something spends (FAB-134-3c), decided once near tip --------------

    /** A wallet with one UTxO the index does not hold: a node that sweeps at all must sweep it. */
    private void aWalletThatNeedsASweep() {
        onChain(utxo(WALLET, 200_000_000));
    }

    @Test
    void withEverySpendingProcessorOffNothingIsListedAndTheGateOpensNearTip() {
        tankProcessorEnabled = false;
        aWalletThatNeedsASweep();
        WalletSweepService service = service();

        service.onCommit(slotBehind(Duration.ofMinutes(30)));
        assertFalse(readiness.isWalletReady(), "even an idle node opens the gate only near tip");
        assertEquals(WalletReadiness.WAITING_FOR_TIP, readiness.sweepState());

        atTip(service);
        assertEquals(WalletReadiness.IDLE, readiness.sweepState());
        assertTrue(readiness.isWalletReady(), "an idle node opens the gate so the readiness page shows the wallet");

        // Decided once: a processor enabled afterwards changes nothing in this process.
        ReflectionTestUtils.setField(compound, "enabled", true);
        assertDoneForGood(service);
        assertEquals(List.of(), listingCalls, "a node that spends nothing must not even list the wallet");
        assertEquals(0, findByIdCalls.get());
        assertEquals(0, submitted.size());
    }

    /** One spending processor alone on: the one-shot runs all the way to its submit. */
    private void sweepsWith(String which) {
        aWalletThatNeedsASweep();
        WalletSweepService service = service();

        runOneShot(service);

        assertEquals(1, listings(), which + " alone must make the sweep list the wallet");
        assertEquals(1, submitted.size(), which + " alone must make the sweep run: " + readiness.sweepState());
        assertTrue(readiness.sweepState().startsWith("done: rebalanced "), readiness.sweepState());
    }

    @Test
    void theTankProcessorAloneMakesTheSweepRun() {
        tankProcessorEnabled = true;
        sweepsWith("the tank processor");
    }

    @Test
    void liquidationInShadowAloneMakesTheSweepRun() {
        tankProcessorEnabled = false;
        liquidation = liquidation(AppConfig.LiquidationConfiguration.Mode.SHADOW);
        sweepsWith("liquidation SHADOW");
    }

    @Test
    void liquidationLiveAloneMakesTheSweepRun() {
        tankProcessorEnabled = false;
        liquidation = liquidation(AppConfig.LiquidationConfiguration.Mode.LIVE);
        sweepsWith("liquidation LIVE");
    }

    @Test
    void compoundAloneMakesTheSweepRun() {
        tankProcessorEnabled = false;
        compound = compound(true);
        sweepsWith("compound");
    }

    @Test
    void theSpringEventListenerDelegatesTheCommitSlot() {
        aWalletThatNeedsASweep();
        WalletSweepService service = service();

        CommitEvent<?> behind = Mockito.mock(CommitEvent.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(behind.getMetadata().getSlot()).thenReturn(slotBehind(Duration.ofMinutes(30)));
        service.onCommitEvent(behind);
        assertEquals(0, listings());

        CommitEvent<?> tip = Mockito.mock(CommitEvent.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(tip.getMetadata().getSlot()).thenReturn(slotNow());
        service.onCommitEvent(tip);
        assertEquals(1, listings());
    }

    /**
     * The bean Spring builds: every input resolves, and the predicate it was given reads the tank flag
     * property, the liquidation mode and the compound flag off the very objects the executors read. The
     * two configuration beans are bound by Spring from properties, exactly as in production. With all
     * three off, a near-tip block touches no Blockfrost service at all and opens the gate.
     */
    @Test
    void theSpringWiredSweepReadsEveryInput() {
        record Case(String tank, AppConfig.LiquidationConfiguration.Mode mode, boolean compound, boolean spends) {
        }
        for (Case c : List.of(
                new Case("false", AppConfig.LiquidationConfiguration.Mode.DISABLED, false, false),
                new Case("true", AppConfig.LiquidationConfiguration.Mode.DISABLED, false, true),
                new Case("false", AppConfig.LiquidationConfiguration.Mode.SHADOW, false, true),
                new Case("false", AppConfig.LiquidationConfiguration.Mode.LIVE, false, true),
                new Case("false", AppConfig.LiquidationConfiguration.Mode.DISABLED, true, true))) {
            BFBackendService blockfrost = Mockito.mock(BFBackendService.class);
            AppConfig.Network network = new AppConfig.Network();
            network.setNetworkForTest("preview");
            new ApplicationContextRunner()
                    .withPropertyValues("scheduling.transaction-processor.enabled=" + c.tank(),
                            "loans.liquidation.mode=" + c.mode().name().toLowerCase(),
                            "loans.liquidation.profit-margin-lovelace=1500000",
                            "loans.compound.enabled=" + c.compound())
                    .withBean(UtxoRepository.class, this::index)
                    .withBean(WalletReadiness.class)
                    .withBean(Account.class, () -> ACCOUNT)
                    .withBean(AppConfig.Network.class, () -> network)
                    .withBean(AppConfig.LiquidationConfiguration.class)
                    .withBean(AppConfig.CompoundConfiguration.class)
                    .withBean(ProtocolParamsSupplier.class, () -> () -> params.getProtocolParams())
                    .withBean(CardanoConverters.class, () -> CONVERTERS)
                    .withBean(BFBackendService.class, () -> blockfrost)
                    .withBean(WalletSweepService.class)
                    .run(ctx -> {
                        assertNull(ctx.getStartupFailure(), () -> c + ": " + ctx.getStartupFailure());
                        WalletSweepService sweep = ctx.getBean(WalletSweepService.class);
                        assertEquals(c.mode(), ctx.getBean(AppConfig.LiquidationConfiguration.class).getMode());
                        assertEquals(c.spends(), sweep.somethingSpends(), c.toString());
                        if (!c.spends()) {
                            sweep.onCommit(CONVERTERS.time().toSlot(LocalDateTime.now(ZoneOffset.UTC)));
                            Mockito.verifyNoInteractions(blockfrost);
                            WalletReadiness wired = ctx.getBean(WalletReadiness.class);
                            assertEquals(WalletReadiness.IDLE, wired.sweepState());
                            assertTrue(wired.isWalletReady());
                            // The same compound object, switched on, is what the predicate reads.
                            ReflectionTestUtils.setField(ctx.getBean(AppConfig.CompoundConfiguration.class),
                                    "enabled", true);
                            assertTrue(sweep.somethingSpends(), "the wired predicate must read compound live");
                        }
                    });
        }
    }
}
