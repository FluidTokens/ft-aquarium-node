package com.fluidtokens.aquarium.offchain.service.wallet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.UtxoId;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.service.BlockEventListener;
import com.fluidtokens.aquarium.offchain.service.loans.LoanFixtures;
import org.cardanofoundation.conversions.CardanoConverters;
import org.junit.jupiter.api.Test;

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
    private final BlockEventListener listener = new BlockEventListener(null);
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
    /** How long one Blockfrost page call takes on the test clock (zero: instantaneous). */
    private Duration listingLatency = Duration.ZERO;

    private ProtocolParamsSupplier params = LoanFixtures.protocolParams();
    private List<AppConfig.LiquidationConfiguration.Market> markets = List.of(anticipate(UNIT_A));

    private WalletSweepService service() {
        return new WalletSweepService(listener, index(), readiness, ACCOUNT, Networks.preview(), () -> markets,
                () -> params.getProtocolParams(), CONVERTERS, this::page, this::submit, clock);
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
        if (submitThrows != null) {
            throw submitThrows;
        }
        String hash = TransactionUtil.getTxHash(bytes);
        if (submitLands) {
            land(tx);
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

    private void synced() {
        listener.getIsSyncing().set(false);
    }

    /** One tick, then the clock and the index's last applied block move past it: the next tick is settled. */
    private void tickAndSettle(WalletSweepService service) {
        service.tick();
        clock.advance(Duration.ofSeconds(30));
        listener.getLastAppliedSlot().set(slotNow());
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

    // ---- (a)–(k) -------------------------------------------------------------------------------

    @Test
    void a_whileSyncingNothingIsListedAndNothingSubmitted() {
        Utxo missing = utxo(WALLET, 200_000_000);
        onChain(missing);
        listener.getLastAppliedSlot().set(Long.MAX_VALUE);   // the settle condition would hold
        WalletSweepService service = service();

        for (int i = 0; i < 4; i++) {
            service.tick();
            clock.advance(Duration.ofMinutes(11));
        }

        assertEquals(List.of(), listingCalls, "a syncing node must not even list the wallet");
        assertEquals(0, submitted.size());
        assertFalse(readiness.isWalletReady());
        assertEquals("pending", readiness.sweepState());
    }

    @Test
    void b_beforeTheIndexReachesTheListingsSlotNothingIsComparedOrSubmitted() {
        synced();
        Utxo missing = utxo(WALLET, 200_000_000);
        onChain(missing);
        listener.getLastAppliedSlot().set(slotNow() - 1);
        WalletSweepService service = service();

        for (int i = 0; i < 5; i++) {
            service.tick();
            clock.advance(Duration.ofMinutes(1));   // wall clock moves; the index does not
        }

        assertTrue(listingCalls.size() >= 5, "every tick lists afresh: " + listingCalls);
        assertEquals(0, findByIdCalls.get(), "nothing may be compared before the index has settled");
        assertEquals(0, submitted.size());
        assertFalse(readiness.isWalletReady());
    }

    @Test
    void c_everythingAlreadyIndexedMakesTheWalletReadyWithoutASubmit() {
        synced();
        Utxo a = utxo(WALLET, 200_000_000);
        Utxo b = utxo(WALLET, 10_000_000, UNIT_A, 5L);
        onChain(a, b);
        indexed(a, b);
        WalletSweepService service = service();

        tickAndSettle(service);
        assertFalse(readiness.isWalletReady(), "the first tick only observes");
        service.tick();

        assertTrue(readiness.isWalletReady());
        assertEquals("ready", readiness.sweepState());
        assertEquals(0, submitted.size());

        int calls = listingCalls.size();
        service.tick();
        assertEquals(calls, listingCalls.size(), "once ready the poller does nothing");
    }

    @Test
    void d_oneMissingUtxoSubmitsExactlyOneShapedSweepOfTheWholeListing() {
        synced();
        Utxo a = utxo(WALLET, 3_000_000, UNIT_A, 300L, JUNK, 7L);
        Utxo b = utxo(WALLET, 150_000_000);
        Utxo c = utxo(WALLET, 40_000_000);
        Utxo missing = utxo(WALLET, 25_000_000);
        onChain(a, b, c, missing);
        indexed(a, b, c);
        Set<String> wholeListing = listedRefs();
        WalletSweepService service = service();

        tickAndSettle(service);
        service.tick();

        assertEquals(1, submitted.size());
        Transaction tx = submitted.getFirst();
        assertEquals(wholeListing, inputRefs(tx), "the sweep spends the WHOLE listing, not only what is missing");

        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        assertEquals(4, outputs.size(), "A + 10, junk + 10, collateral, change");
        assertEquals(Map.of("lovelace", TEN_ADA, UNIT_A, BigInteger.valueOf(300)), units(outputs.get(0)));
        assertEquals(Map.of("lovelace", TEN_ADA, JUNK, BigInteger.valueOf(7)), units(outputs.get(1)));
        assertEquals(Map.of("lovelace", FIVE_ADA), units(outputs.get(2)));
        assertTrue(readiness.sweepState().startsWith("swept "), readiness.sweepState());
        assertFalse(readiness.isWalletReady());
    }

    @Test
    void e_aRefusedShapeFallsBackToTheConsolidation() {
        synced();
        // The engine's small wallet: the change would not be the largest output, so the shape is refused.
        Utxo tokens = utxo(WALLET, 1_500_000, UNIT_A, 300L, JUNK, 5L);
        Utxo ada = utxo(WALLET, 26_000_000);
        onChain(tokens, ada);
        indexed(tokens);
        Set<String> wholeListing = listedRefs();
        WalletSweepService service = service();

        tickAndSettle(service);
        service.tick();

        assertEquals(1, submitted.size(), readiness.sweepState());
        Transaction tx = submitted.getFirst();
        assertEquals(wholeListing, inputRefs(tx));
        List<TransactionOutput> outputs = tx.getBody().getOutputs();
        assertEquals(2, outputs.size(), "the consolidation: every asset in one output, plus change");
        Map<String, BigInteger> assets = new java.util.TreeMap<>(units(outputs.get(0)));
        assets.remove("lovelace");
        assertEquals(Map.of(UNIT_A, BigInteger.valueOf(300), JUNK, BigInteger.valueOf(5)), assets);
        assertEquals(Set.of("lovelace"), units(outputs.get(1)).keySet());
    }

    @Test
    void f_bothBuildsRefusedSubmitsNothingAndKeepsTheGateClosed() {
        synced();
        // The whole listing is one unindexed 0.1-ADA UTxO: too small to fund the shape's outputs, and too
        // small to pay the consolidation's fee, so both builders refuse.
        Utxo dust = utxo(WALLET, 100_000);
        onChain(dust);
        WalletSweepService service = service();

        tickAndSettle(service);
        tickAndSettle(service);
        tickAndSettle(service);

        assertEquals(0, submitted.size());
        assertFalse(readiness.isWalletReady());
        assertTrue(readiness.sweepState().startsWith("refused "), readiness.sweepState());
        assertTrue(listingCalls.stream().filter(c -> c.startsWith(WALLET + "@")).count() >= 3,
                "a refusal is retried on the next tick: " + listingCalls);
    }

    @Test
    void enterpriseAddressIsListedAndAnUnusedOneReadsAsEmpty() {
        synced();
        Utxo base = utxo(WALLET, 200_000_000);
        onChain(base);
        indexed(base);
        WalletSweepService service = service();
        assertEquals(List.of(WALLET, ENTERPRISE), service.listedAddresses());

        tickAndSettle(service);
        service.tick();

        assertTrue(listingCalls.contains(ENTERPRISE + "@1"), "the enterprise address must be listed: " + listingCalls);
        assertTrue(readiness.isWalletReady(), "an enterprise address Blockfrost 404s is empty, not an outage");
    }

    @Test
    void anUnindexedEnterpriseUtxoIsSweptIntoTheBaseAddressAndThenTheWalletIsReady() {
        synced();
        Utxo base = utxo(WALLET, 150_000_000);
        Utxo enterprise = utxo(ENTERPRISE, 20_000_000);
        onChain(base, enterprise);
        indexed(base);
        Set<String> wholeListing = listedRefs();
        WalletSweepService service = service();

        tickAndSettle(service);
        tickAndSettle(service);

        assertEquals(1, submitted.size(), readiness.sweepState());
        Transaction tx = submitted.getFirst();
        assertEquals(wholeListing, inputRefs(tx), "the sweep spends the whole listing, enterprise UTxO included");
        assertTrue(inputRefs(tx).contains(ref(enterprise)));
        tx.getBody().getOutputs().forEach(o -> assertEquals(WALLET, o.getAddress(), "every output at the BASE address"));
        assertEquals(1, tx.getWitnessSet().getVkeyWitnesses().size(), "one key spends base and enterprise inputs");
        assertFalse(readiness.isWalletReady(), "submitted is not indexed");
        assertFalse(chain.containsKey(ENTERPRISE) && !chain.get(ENTERPRISE).isEmpty(),
                "after the sweep lands the enterprise address holds nothing");

        listedRefs().forEach(index::add);
        tickAndSettle(service);
        tickAndSettle(service);
        assertTrue(readiness.isWalletReady(), readiness.sweepState());
        assertEquals("ready", readiness.sweepState());
        assertEquals(1, submitted.size());
    }

    @Test
    void g_afterASubmitTheWalletIsReadyOnlyOnceTheOutputsAreIndexed() {
        synced();
        Utxo a = utxo(WALLET, 150_000_000);
        Utxo missing = utxo(WALLET, 25_000_000);
        onChain(a, missing);
        indexed(a);
        WalletSweepService service = service();

        tickAndSettle(service);
        tickAndSettle(service);
        assertEquals(1, submitted.size());
        assertFalse(readiness.isWalletReady(), "submitted is not indexed");

        // Blockfrost now lists the sweep's outputs; the index has not seen them yet.
        tickAndSettle(service);
        tickAndSettle(service);
        assertFalse(readiness.isWalletReady(), "the outputs are not in the index yet");
        assertEquals(1, submitted.size(), "no resubmit inside the window");

        listedRefs().forEach(index::add);
        tickAndSettle(service);
        tickAndSettle(service);
        assertTrue(readiness.isWalletReady());
        assertEquals("ready", readiness.sweepState());
        assertEquals(1, submitted.size());
    }

    @Test
    void sweptOutputsNotYetIndexedKeepTheGateClosedEvenWhenBlockfrostListsNothingMissing() {
        synced();
        Utxo a = utxo(WALLET, 150_000_000);
        Utxo missing = utxo(WALLET, 25_000_000);
        onChain(a, missing);
        indexed(a);
        submitLands = false;
        WalletSweepService service = service();

        tickAndSettle(service);
        tickAndSettle(service);
        assertEquals(1, submitted.size());

        // Blockfrost's lag: it lists neither the spent inputs nor the new outputs.
        chain.clear();
        chain.put(WALLET, new ArrayList<>());
        tickAndSettle(service);
        tickAndSettle(service);
        assertFalse(readiness.isWalletReady(), "the sweep's own outputs must reach the index first");
    }

    @Test
    void h_notConvergedAfterTenMinutesRebuildsAndResubmits() {
        synced();
        Utxo a = utxo(WALLET, 150_000_000);
        Utxo missing = utxo(WALLET, 25_000_000);
        onChain(a, missing);
        indexed(a);
        WalletSweepService service = service();

        tickAndSettle(service);
        Instant submitTime = clock.instant();
        service.tick();
        assertEquals(1, submitted.size());

        // Nine and a half minutes of settled ticks: the outputs never reach the index, no resubmit.
        while (clock.instant().isBefore(submitTime.plus(Duration.ofSeconds(570)))) {
            clock.advance(Duration.ofSeconds(30));
            listener.getLastAppliedSlot().set(slotNow());
            service.tick();
        }
        assertEquals(1, submitted.size(), "a resubmit fired inside the ten-minute window");

        while (clock.instant().isBefore(submitTime.plus(Duration.ofSeconds(660)))) {
            clock.advance(Duration.ofSeconds(30));
            listener.getLastAppliedSlot().set(slotNow());
            service.tick();
        }
        assertEquals(2, submitted.size(), "not converged after ten minutes: rebuild and resubmit");
        assertEquals(inputRefs(submitted.get(1)).size(), submitted.get(0).getBody().getOutputs().size(),
                "the resubmit spends the fresh listing — the first sweep's outputs");
    }

    @Test
    void i_aListingFailureSubmitsNothingAndIsRetriedOnTheNextTick() {
        synced();
        Utxo a = utxo(WALLET, 150_000_000);
        Utxo missing = utxo(WALLET, 25_000_000);
        onChain(a, missing);
        indexed(a);
        WalletSweepService service = service();

        tickAndSettle(service);
        forcedListingResult = Result.<List<Utxo>>error("Internal Server Error").code(500);
        tickAndSettle(service);
        tickAndSettle(service);
        assertEquals(0, submitted.size());
        assertFalse(readiness.isWalletReady());

        forcedListingResult = null;
        service.tick();
        assertEquals(1, submitted.size(), "the next good listing proceeds");
    }

    @Test
    void anExceptionNeverEscapesTheScheduledTick() {
        synced();
        onChain(utxo(WALLET, 150_000_000));
        forcedListingException = new IllegalStateException("blockfrost exploded");
        WalletSweepService service = service();

        assertDoesNotThrow(service::tick);
        listener.getLastAppliedSlot().set(Long.MAX_VALUE);
        assertDoesNotThrow(service::tick);
        assertEquals(0, submitted.size());
        assertFalse(readiness.isWalletReady());
    }

    @Test
    void j_aFullPageIsFollowedAndTheShortPageIsRead() {
        synced();
        List<Utxo> utxos = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            utxos.add(utxo(WALLET, 2_000_000));
        }
        Utxo missingOnPageTwo = utxo(WALLET, 100_000_000);
        utxos.add(missingOnPageTwo);
        onChain(utxos.toArray(Utxo[]::new));
        indexed(utxos.subList(0, 100).toArray(Utxo[]::new));
        WalletSweepService service = service();

        tickAndSettle(service);
        service.tick();

        assertTrue(listingCalls.contains(WALLET + "@2"), "page 2 was never read: " + listingCalls);
        assertFalse(listingCalls.contains(WALLET + "@3"), "a short page ends the address: " + listingCalls);
        assertFalse(readiness.isWalletReady(), "the UTxO on page 2 is missing from the index");
        assertEquals(1, submitted.size());
        assertEquals(101, inputRefs(submitted.getFirst()).size());
    }

    @Test
    void k_anEmptyWalletIsReadyWithoutASubmit() {
        synced();
        chain.put(WALLET, new ArrayList<>());
        WalletSweepService service = service();

        tickAndSettle(service);
        service.tick();

        assertTrue(readiness.isWalletReady());
        assertEquals(0, submitted.size());
    }

    @Test
    void aSignedTransactionOverMaxTxSizeIsRefusedEvenWhenTheUnsignedBodyFits() throws Exception {
        synced();
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
        ProtocolParams tight = LoanFixtures.protocolParams().getProtocolParams();
        ProtocolParams copy = ProtocolParams.builder()
                .minFeeA(tight.getMinFeeA()).minFeeB(tight.getMinFeeB()).maxTxSize(unsignedSize + 10)
                .maxValSize(tight.getMaxValSize()).coinsPerUtxoSize(tight.getCoinsPerUtxoSize())
                .priceMem(tight.getPriceMem()).priceStep(tight.getPriceStep()).maxTxExMem(tight.getMaxTxExMem())
                .maxTxExSteps(tight.getMaxTxExSteps()).collateralPercent(tight.getCollateralPercent())
                .maxCollateralInputs(tight.getMaxCollateralInputs())
                .minFeeRefScriptCostPerByte(tight.getMinFeeRefScriptCostPerByte())
                .protocolMajorVer(tight.getProtocolMajorVer()).protocolMinorVer(tight.getProtocolMinorVer())
                .costModelsRaw(tight.getCostModelsRaw()).build();
        params = () -> copy;
        WalletSweepService service = service();

        tickAndSettle(service);
        service.tick();

        assertEquals(0, submitted.size(), "an oversize SIGNED transaction must never reach the wire");
        assertTrue(readiness.sweepState().startsWith("refused signed transaction too large"), readiness.sweepState());
        assertFalse(readiness.isWalletReady());
    }

    // ---- revision 3 ----------------------------------------------------------------------------

    /** Settled ticks every 30 s until {@code until}: the index keeps up with the wall clock. */
    private void settledTicksUntil(WalletSweepService service, Instant until) {
        while (clock.instant().isBefore(until)) {
            clock.advance(Duration.ofSeconds(30));
            listener.getLastAppliedSlot().set(slotNow());
            service.tick();
        }
    }

    @Test
    void aSweepLandingAfterTheWindowClosesKeepsTheGateClosedUntilItsOutputsAreIndexed() throws Exception {
        synced();
        Utxo a = utxo(WALLET, 150_000_000);
        Utxo missing = utxo(WALLET, 25_000_000);
        onChain(a, missing);
        indexed(a);
        submitLands = false;
        WalletSweepService service = service();

        tickAndSettle(service);
        Instant submitTime = clock.instant();
        service.tick();
        assertEquals(1, submitted.size());

        // Settled ticks up to 9:30 after the submit; Blockfrost still lists the pre-sweep wallet.
        settledTicksUntil(service, submitTime.plus(Duration.ofSeconds(570)));
        assertEquals(1, submitted.size());

        // The sweep lands now, after the 9:30 tick: Blockfrost lists ONLY its outputs, the index none of them.
        land(submitted.getFirst());
        settledTicksUntil(service, submitTime.plus(Duration.ofSeconds(600)));   // window closed at this tick
        assertFalse(readiness.isWalletReady(),
                "Blockfrost lists the sweep's outputs and the index holds none of them: " + readiness.sweepState());
        assertEquals(1, submitted.size(), "freshly landed outputs are not a reason to resubmit");

        listedRefs().forEach(index::add);
        settledTicksUntil(service, submitTime.plus(Duration.ofSeconds(630)));
        assertTrue(readiness.isWalletReady(), readiness.sweepState());
        assertEquals(1, submitted.size());
    }

    @Test
    void aSubmitThatThrowsHoldsBackAResubmitForTheWholeWindow() {
        synced();
        Utxo a = utxo(WALLET, 150_000_000);
        Utxo missing = utxo(WALLET, 25_000_000);
        onChain(a, missing);
        indexed(a);
        submitThrows = new java.io.IOException("connection reset mid-submit");
        WalletSweepService service = service();

        tickAndSettle(service);
        Instant submitTime = clock.instant();
        assertDoesNotThrow(service::tick);
        assertEquals(1, submitted.size());
        assertTrue(readiness.sweepState().startsWith("refused submit outcome unknown for "), readiness.sweepState());

        // Nineteen settled ticks inside the window: the bytes may be on chain, so nothing is resubmitted.
        settledTicksUntil(service, submitTime.plus(Duration.ofSeconds(570)));
        assertEquals(1, submitted.size(), "a submit of unknown outcome must hold back a resubmit for the window");
        assertTrue(readiness.sweepState().startsWith("refused submit outcome unknown for "), readiness.sweepState());
        assertFalse(readiness.isWalletReady());

        // Past the window: one rebuild and resubmit, and then the window holds again.
        settledTicksUntil(service, submitTime.plus(Duration.ofSeconds(900)));
        assertEquals(2, submitted.size(), "after the window exactly one resubmit");
        assertFalse(readiness.isWalletReady());
    }

    @Test
    void protocolParametersWithoutMaxTxSizeAtSignTimeRefuseTheSubmit() {
        synced();
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

        tickAndSettle(service);
        service.tick();

        assertEquals(0, submitted.size(), "no limit to check against means no submit");
        assertTrue(readiness.sweepState().startsWith("refused protocol parameters carry no maxTxSize"),
                readiness.sweepState());
        assertFalse(readiness.isWalletReady());
    }

    @Test
    void theSettleSlotIsTakenWhenTheListingCompletesNotWhenItStarts() {
        synced();
        Utxo a = utxo(WALLET, 150_000_000);
        onChain(a);
        indexed(a);
        listingLatency = Duration.ofSeconds(60);   // one page call; the listing is base + enterprise
        WalletSweepService service = service();

        Instant started = clock.instant();
        service.tick();
        assertEquals(started.plus(Duration.ofSeconds(120)), clock.instant());
        // The index has passed the listing's START, but not the moment Blockfrost answered.
        listener.getLastAppliedSlot().set(CONVERTERS.time().toSlot(
                LocalDateTime.ofInstant(started.plus(Duration.ofSeconds(60)), ZoneOffset.UTC)));
        service.tick();

        assertEquals(0, findByIdCalls.get(), "the listing is not settled until the index reaches its completion");
        assertFalse(readiness.isWalletReady());
    }

    @Test
    void aListingFailureIsVisibleOnTheStateAndKeepsTheGateClosed() {
        synced();
        Utxo a = utxo(WALLET, 150_000_000);
        onChain(a);
        indexed(a);
        WalletSweepService service = service();

        forcedListingResult = Result.<List<Utxo>>error("Internal Server Error").code(500);
        tickAndSettle(service);
        tickAndSettle(service);
        assertTrue(readiness.sweepState().startsWith("refused listing failed"), readiness.sweepState());
        assertFalse(readiness.isWalletReady());

        forcedListingResult = null;
        forcedListingException = new IllegalStateException("blockfrost exploded");
        readiness.markSwept("x");   // any non-failure state: the exception path must overwrite it too
        tickAndSettle(service);
        assertTrue(readiness.sweepState().startsWith("refused listing failed"), readiness.sweepState());
        assertFalse(readiness.isWalletReady());
        assertEquals(0, submitted.size());
    }
}
