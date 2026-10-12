package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.ScriptSupplier;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.TransactionService;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.model.SlotConfigs;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.common.domain.Amt;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.converter.DatumParametersConverter;
import com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.converter.DatumTankConverter;
import com.fluidtokens.aquarium.offchain.blueprint.types.redeemer.model.impl.ScheduledTransactionData;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.config.EpochProtocolParamsSupplier;
import com.fluidtokens.aquarium.offchain.config.YaciConfig;
import com.fluidtokens.aquarium.offchain.util.AddressUtil;
import com.fluidtokens.aquarium.offchain.util.AssetAmountUtil;
import org.cardanofoundation.conversions.CardanoConverters;
import org.cardanofoundation.conversions.ClasspathConversionsFactory;
import org.cardanofoundation.conversions.domain.NetworkType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ⛔ FAB-134 B3b-5 r2: the tank transaction, built through the PRODUCTION wiring — the
 * {@code YaciConfig.quickTxBuilder} bean and {@code ScheduledTransactionService.balanceTankTx}, exactly as
 * the processor calls them — over a wallet that holds reference-script UTxOs.
 *
 * <h2>Why this exists next to {@code TankTransactionDryEvalTest}</h2>
 * The dry-eval rig builds its own {@code QuickTxBuilder} with a null processor and supplies its own
 * evaluator: it proves the VALIDATOR, and by construction it cannot see what the bean supplies. This
 * repo's 2026-08-21 incident was exactly that gap — a rig supplying what production must earn. Here the
 * builder is the bean, so:
 * <ul>
 *   <li><b>(a) fail-closed.</b> With the bean's own evaluator (Blockfrost, unreachable on loopback) the
 *       build must THROW. Shipping placeholder ex-units would be accepted by the mempool and fail in
 *       phase 2, forfeiting the collateral (CCL trap 8).</li>
 *   <li><b>(b) real ex-units.</b> With an evaluator override (Scalus, the real tank validator), the
 *       ex-units read off the DESERIALISED transaction cover what a second evaluation of the final
 *       bytes measures — never off the evaluator's report.</li>
 *   <li><b>(c) no reference script is ever spent</b>, as an input or as collateral, whichever order the
 *       index returns the operator's UTxOs in (wallet first, scripts first, interleaved), whether or not
 *       selection has to pull extra inputs, and a wallet with nothing safe to select is REFUSED rather
 *       than drained of a reference script (CCL trap 9b).</li>
 * </ul>
 * And, driving the processor's own cycle: the tank build is STRICT outside dump mode (a failed
 * evaluation never reaches submit), and a {@code PPViewHashesDontMatch} rejection from
 * {@code complete()} reaches the epoch protocol-params cache.
 */
class TankProductionWiringTest {

    private static final String OPERATOR_PAYMENT = "ec900701dc71ef420bc24bda6c484f5f9276836aae73c4f70b7d4493";
    private static final String OPERATOR_STAKE = "682fec1c867c6aa59918ef6f25c6bdd38c1c7d03d71c0b4fa23c5bac";
    private static final String TANK_REF_TX = "354ffe7958d62a8a2bf0b0bd97a06694d59dc49b6d02f1ab40165a3955257168";
    private static final String PARAMS_REF_TX = "b79f33b820dd572394cf93e8a4ad1a67ee2d46dbf69ac6ceca33de0d6ff56476";
    private static final String STAKER_REF_TX = "3d640e597bd7470abf9efd15ed45c60f5e1d71dc038a5ffa7795ecfe97786dc7";

    /** The tank validator's hash, as its published reference script carries it. */
    private static final String TANK_SCRIPT_HASH = "f9724c47299e745cb4f50f9d36cbbadcdf87e015a9d99d927dc4e866";

    /** Loopback discard port: a real Blockfrost client whose every call is refused, without leaving the host. */
    private static final String UNREACHABLE_BLOCKFROST = "http://127.0.0.1:9/api/v0/";

    private static final BigInteger PLACEHOLDER_CEILING = BigInteger.valueOf(10_000L);

    /** Where the index puts the operator's reference-script UTxOs relative to its ordinary ones. */
    enum Ordering { WALLET_FIRST, SCRIPTS_FIRST, SPLIT }

    enum Mode {
        /** Production's own wallet input (the large ada-only UTxO): balancing needs nothing more. */
        NORMAL,
        /** A 0.5 ada wallet input, so balancing must SELECT more from the operator's address. */
        FORCED_SELECTION,
        /** The same, with nothing safe left to select: only the pinned collateral and reference scripts. */
        NO_SAFE_UTXO
    }

    private record Rig(QuickTxBuilder bean, UtxoSupplier supplier, ScriptTx tx, Utxo tank, Utxo collateral,
                       String operator, long slot, long firstValidSlot, List<Utxo> referenceScriptUtxos,
                       scalus.bloxbean.ScalusTransactionEvaluator scalus) {

        QuickTxBuilder.TxContext balanced() {
            // ⛔ THE CALL PRODUCTION MAKES: balanceTankTx over the bean's compose, then strict evaluation
            // (ScheduledTransactionService sets ignoreScriptCostEvaluationError(dumpCbor), false outside
            // dump mode — pinned by aFailedEvaluationIsNeverSubmittedByTheTankCycle below).
            return ScheduledTransactionService.balanceTankTx(bean.compose(tx), tank, collateral, operator,
                            supplier, slot, firstValidSlot)
                    .ignoreScriptCostEvaluationError(false);
        }
    }

    // ---- (a) fail-closed with the bean's own evaluator -------------------------------------------

    @Test
    void withTheBeansOwnUnreachableEvaluatorTheBuildFailsClosed() throws Exception {
        for (Ordering ordering : Ordering.values()) {
            for (Mode mode : List.of(Mode.NORMAL, Mode.FORCED_SELECTION)) {
                Rig rig = rig(ordering, mode, new BFBackendService(UNREACHABLE_BLOCKFROST, "dummy"));
                Exception refused = assertThrows(Exception.class, () -> rig.balanced().build(),
                        ordering + "/" + mode + ": the bean's evaluator could not be reached and the build "
                                + "still produced a transaction — one carrying PLACEHOLDER ex-units");
                assertTrue(chain(refused).toLowerCase().contains("evaluat"),
                        ordering + "/" + mode + ": the build failed, but not at script-cost evaluation: "
                                + chain(refused));
                // ...and at the BEAN's evaluator trying to reach Blockfrost — not at a missing evaluator, which
                // fails with the same word ("Transaction evaluator is not set").
                assertTrue(chain(refused).contains("Connection refused") || chain(refused).contains("ConnectException"),
                        ordering + "/" + mode + ": the failure is not the bean's evaluator calling out: "
                                + chain(refused));
            }
        }
    }

    // ---- (b) real ex-units, read off the deserialised transaction ----------------------------------

    @Test
    void withAnEvaluatorOverrideTheDeserialisedTransactionCarriesMeasuredExUnits() throws Exception {
        for (Ordering ordering : Ordering.values()) {
            for (Mode mode : List.of(Mode.NORMAL, Mode.FORCED_SELECTION)) {
                Rig rig = rig(ordering, mode, new BFBackendService(UNREACHABLE_BLOCKFROST, "dummy"));
                Transaction built = rig.balanced().withTxEvaluator(rig.scalus()).build();
                Transaction shipped = Transaction.deserialize(built.serialize());

                List<Redeemer> redeemers = shipped.getWitnessSet().getRedeemers();
                assertFalse(redeemers == null || redeemers.isEmpty(), ordering + "/" + mode + ": no redeemer");
                var finalEval = rig.scalus().evaluateTx(shipped.serialize());
                assertTrue(finalEval.isSuccessful(), ordering + "/" + mode
                        + ": the shipped bytes do not satisfy the tank validator: " + finalEval.getResponse());
                for (Redeemer redeemer : redeemers) {
                    ExUnits declared = redeemer.getExUnits();
                    assertTrue(declared.getMem().compareTo(PLACEHOLDER_CEILING) > 0
                                    && declared.getSteps().compareTo(PLACEHOLDER_CEILING) > 0,
                            ordering + "/" + mode + ": the shipped transaction declares placeholder ex-units "
                                    + declared);
                    ExUnits measured = finalEval.getValue().stream()
                            .filter(r -> r.getRedeemerTag() == redeemer.getTag()
                                    && r.getIndex() == redeemer.getIndex().intValue())
                            .findFirst().orElseThrow().getExUnits();
                    assertTrue(declared.getMem().compareTo(measured.getMem()) >= 0
                                    && declared.getSteps().compareTo(measured.getSteps()) >= 0,
                            ordering + "/" + mode + ": declares " + declared + " but the shipped bytes cost "
                                    + measured + " — a phase-2 failure and forfeited collateral");
                }
            }
        }
    }

    // ---- (c) no reference script is spent, as input or as collateral -------------------------------

    @Test
    void noReferenceScriptUtxoIsSpentAsAnInputOrAsCollateralInAnyOrdering() throws Exception {
        for (Ordering ordering : Ordering.values()) {
            for (Mode mode : List.of(Mode.NORMAL, Mode.FORCED_SELECTION)) {
                Rig rig = rig(ordering, mode, new BFBackendService(UNREACHABLE_BLOCKFROST, "dummy"));
                Transaction shipped = Transaction.deserialize(
                        rig.balanced().withTxEvaluator(rig.scalus()).build().serialize());
                Set<String> scripts = rig.referenceScriptUtxos().stream().map(TankProductionWiringTest::ref)
                        .collect(Collectors.toSet());
                Set<String> inputs = shipped.getBody().getInputs().stream().map(TankProductionWiringTest::ref)
                        .collect(Collectors.toSet());
                Set<String> collateral = shipped.getBody().getCollateral().stream()
                        .map(TankProductionWiringTest::ref).collect(Collectors.toSet());
                String where = ordering + "/" + mode + ": inputs " + inputs + ", collateral " + collateral;

                assertTrue(inputs.stream().noneMatch(scripts::contains),
                        where + " — a REFERENCE-SCRIPT UTxO is spent as an input; a published script "
                                + "destroyed is not recoverable");
                assertTrue(collateral.stream().noneMatch(scripts::contains),
                        where + " — a REFERENCE-SCRIPT UTxO is pledged as collateral");
                assertEquals(Set.of(ref(rig.collateral())), collateral,
                        where + " — the collateral must be exactly the pinned one");
                assertFalse(inputs.contains(ref(rig.collateral())),
                        where + " — the pinned collateral is spent as an input");
                if (mode == Mode.FORCED_SELECTION) {
                    assertTrue(inputs.size() > 2, where + " — selection never ran, so this mode proved nothing");
                }
            }
        }
    }

    @Test
    void withNothingSafeToSelectTheBuildIsRefusedRatherThanSpendingAReferenceScript() throws Exception {
        for (Ordering ordering : Ordering.values()) {
            Rig rig = rig(ordering, Mode.NO_SAFE_UTXO, new BFBackendService(UNREACHABLE_BLOCKFROST, "dummy"));
            Exception refused = assertThrows(Exception.class,
                    () -> rig.balanced().withTxEvaluator(rig.scalus()).build(),
                    ordering + ": only reference-script UTxOs could fund this, and the build did not refuse");
            assertTrue(chain(refused).contains("Not enough funds"),
                    ordering + ": refused, but not for lack of a spendable UTxO: " + chain(refused));
        }
    }

    // ---- the processor's own cycle: strict evaluation, and the params-rejection refresh -------------

    /**
     * ⛔ The tank build is STRICT: {@code ignoreScriptCostEvaluationError} is {@code dumpCbor}, false
     * outside dump mode. Set it true and a failed evaluation builds anyway, on placeholder ex-units, and
     * {@code complete()} SUBMITS it — which is what this asserts never happens. Driven through
     * {@code processPayments()} with the bean, a real signing account and a Blockfrost transaction service
     * whose evaluation fails.
     */
    @Test
    void aFailedEvaluationIsNeverSubmittedByTheTankCycle() throws Exception {
        TransactionService transactionService = mock(TransactionService.class);
        when(transactionService.evaluateTx(any(byte[].class))).thenThrow(new ApiException("evaluator unreachable"));
        when(transactionService.submitTransaction(any(byte[].class))).thenReturn(Result.<String>success("submitted"));

        new Cycle(transactionService).run();

        verify(transactionService, atLeastOnce()).evaluateTx(any(byte[].class));
        verify(transactionService, never()).submitTransaction(any(byte[].class));
    }

    /**
     * Positive control for the test above — the same cycle with an evaluation that succeeds DOES reach
     * submit, so "never submitted" there is not the cycle stopping short — and FAB-134 B5a's seam: a
     * {@code PPViewHashesDontMatch} answer from {@code complete()} invalidates the epoch cache, so the next
     * {@code getProtocolParams()} asks the delegate again. A rejection naming anything else does not.
     */
    @Test
    void aSubmittedTankRejectedForItsProtocolParamsRefreshesTheEpochCache() throws Exception {
        assertEquals(1, delegateFetchesAfterTheCycleRejects(
                        "{\"message\":\"ConwayUtxowFailure (PPViewHashesDontMatch (SJust (SafeHash \\\"ab\\\")))\"}"),
                "a PPViewHashesDontMatch rejection from the tank's complete() did not reach the epoch cache");
        assertEquals(0, delegateFetchesAfterTheCycleRejects(
                        "{\"message\":\"ConwayUtxowFailure (UtxoFailure (BadInputsUTxO (fromList [])))\"}"),
                "a rejection that does not name the protocol parameters refreshed them anyway");
    }

    /** Delegate fetches caused by the one getProtocolParams() call made after a cycle whose submit was rejected. */
    private static int delegateFetchesAfterTheCycleRejects(String rejection) throws Exception {
        TransactionService transactionService = mock(TransactionService.class);
        when(transactionService.evaluateTx(any(byte[].class))).thenReturn(Result.<List<EvaluationResult>>success("ok").withValue(List.of(
                EvaluationResult.builder().redeemerTag(RedeemerTag.Spend).index(0)
                        .exUnits(ExUnits.builder().mem(BigInteger.valueOf(500_000L))
                                .steps(BigInteger.valueOf(200_000_000L)).build())
                        .build())));
        when(transactionService.submitTransaction(any(byte[].class))).thenReturn(Result.<String>error(rejection));

        Cycle cycle = new Cycle(transactionService);
        cycle.run();
        verify(transactionService, times(1)).submitTransaction(any(byte[].class));

        int before = cycle.delegateFetches.get();
        cycle.epochParams.getProtocolParams();
        return cycle.delegateFetches.get() - before;
    }

    /** One {@code processPayments()} over the fixture tank, with the bean and a real (random) signing account. */
    private static final class Cycle {
        final AtomicInteger delegateFetches = new AtomicInteger();
        final EpochProtocolParamsSupplier epochParams;
        private final ScheduledTransactionService processor;

        Cycle(TransactionService transactionService) throws Exception {
            JsonNode fx = fixture();
            ProtocolParams params = protocolParams(fx.get("protocol_params"));
            CardanoConverters converters = ClasspathConversionsFactory.createConverters(NetworkType.MAINNET);
            MovableClock clock = new MovableClock(converters.epoch().beginningOfEpochToUTCTime(900)
                    .toInstant(ZoneOffset.UTC).plusSeconds(3600));
            epochParams = new EpochProtocolParamsSupplier(() -> {
                delegateFetches.incrementAndGet();
                return params;
            }, converters, clock);
            clock.now = clock.now.plusSeconds(120); // past the invalidation guard

            Account account = new Account(Networks.mainnet());
            String operator = account.baseAddress();
            Utxo tank = utxo(fx.get("tank"));
            List<Utxo> universe = new ArrayList<>();
            fx.withArray("refs").forEach(r -> universe.add(utxo(r)));
            universe.add(tank);
            List<Utxo> wallet = new ArrayList<>();
            fx.withArray("wallet").forEach(w -> {
                Utxo u = utxo(w);
                u.setAddress(operator);
                wallet.add(u);
            });
            universe.addAll(wallet);
            UtxoSupplier supplier = supplier(universe);
            PlutusV3Script validator = tankValidator();

            BFBackendService backend = mock(BFBackendService.class);
            when(backend.getTransactionService()).thenReturn(transactionService);
            QuickTxBuilder bean = new YaciConfig().quickTxBuilder(supplier, epochParams,
                    scriptSupplier(validator), backend);

            AddressUtxoEntity tankEntity = new AddressUtxoEntity();
            tankEntity.setTxHash(tank.getTxHash());
            tankEntity.setOutputIndex(tank.getOutputIndex());
            tankEntity.setOwnerAddr(tank.getAddress());
            tankEntity.setInlineDatum(tank.getInlineDatum());
            tankEntity.setAmounts(tank.getAmount().stream()
                    .map(a -> Amt.builder().unit(a.getUnit()).quantity(a.getQuantity()).build()).toList());
            UtxoRepository utxoRepository = mock(UtxoRepository.class);
            when(utxoRepository.findUnspentByOwnerPaymentCredential(anyString(), any()))
                    .thenReturn(Optional.of(List.of(tankEntity)));

            TransactionInput paramsRef = TransactionInput.builder().transactionId(PARAMS_REF_TX).index(0).build();
            TransactionInput stakerRef = TransactionInput.builder().transactionId(STAKER_REF_TX).index(0).build();
            TransactionInput tankRef = TransactionInput.builder().transactionId(TANK_REF_TX).index(0).build();
            StakerService staker = mock(StakerService.class);
            when(staker.findStakerRefInput()).thenReturn(List.of(stakerRef));
            ParametersService parametersService = mock(ParametersService.class);
            when(parametersService.loadParametersRefInput()).thenReturn(paramsRef);
            when(parametersService.loadParameters()).thenReturn(new DatumParametersConverter().deserialize(
                    paramsDatumHex(fx)));
            AppConfig.AquariumConfiguration aquarium = mock(AppConfig.AquariumConfiguration.class);
            when(aquarium.getTankRefInput()).thenReturn(tankRef);
            AppUtxoService appUtxoService = mock(AppUtxoService.class);
            when(appUtxoService.listWalletUtxo()).thenReturn(wallet);
            BlockEventListener blockEventListener = mock(BlockEventListener.class);
            when(blockEventListener.getIsSyncing()).thenReturn(new AtomicBoolean(false));
            TankContractService tankContract = mock(TankContractService.class);
            when(tankContract.getScriptHashHex()).thenReturn(TANK_SCRIPT_HASH);
            AppConfig.Network network = new AppConfig.Network();
            network.setNetworkForTest("mainnet");

            processor = new ScheduledTransactionService(network, aquarium, account, bean, backend, epochParams,
                    supplier, utxoRepository, staker, converters, parametersService,
                    tankContract, appUtxoService, blockEventListener);
        }

        void run() {
            processor.processPayments();
        }
    }

    private static final class MovableClock extends Clock {
        Instant now;

        MovableClock(Instant now) {
            this.now = now;
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

    // ---- the rig ------------------------------------------------------------------------------------

    private static Rig rig(Ordering ordering, Mode mode, BFBackendService backend) throws Exception {
        JsonNode fx = fixture();
        Utxo tank = utxo(fx.get("tank"));
        List<Utxo> refs = new ArrayList<>();
        fx.withArray("refs").forEach(r -> refs.add(utxo(r)));
        ProtocolParams params = protocolParams(fx.get("protocol_params"));
        PlutusV3Script validator = tankValidator();

        String operator = AddressProvider.getBaseAddress(
                Credential.fromKey(HexUtil.decodeHexString(OPERATOR_PAYMENT)),
                Credential.fromKey(HexUtil.decodeHexString(OPERATOR_STAKE)),
                Networks.mainnet()).getAddress();
        List<Utxo> fixtureWallet = new ArrayList<>();
        fx.withArray("wallet").forEach(w -> fixtureWallet.add(utxo(w)));

        // Three ada-only reference-script UTxOs at the operator's own address: exactly what a default
        // selector takes first, and what the guard must never let it take.
        List<Utxo> scripts = Stream.of("a1", "a2", "a3")
                .map(h -> Utxo.builder().txHash(h.repeat(32)).outputIndex(0).address(operator)
                        .amount(List.of(Amount.lovelace(BigInteger.valueOf(20_000_000L))))
                        .referenceScriptHash(TANK_SCRIPT_HASH).build())
                .toList();
        Utxo small = Utxo.builder().txHash("b1".repeat(32)).outputIndex(0).address(operator)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(500_000L)))).build();

        var required = ScheduledTransactionService.requiredWalletLovelace(params);
        Utxo collateral = ScheduledTransactionService.walletPool(fixtureWallet, required).poll();
        assertNotNull(collateral, "the fixture wallet must yield production's collateral");

        List<Utxo> plain = switch (mode) {
            case NORMAL -> fixtureWallet;
            case FORCED_SELECTION -> Stream.concat(fixtureWallet.stream(), Stream.of(small)).toList();
            case NO_SAFE_UTXO -> List.of(collateral, small);
        };
        Utxo walletInput = mode == Mode.NORMAL
                ? ScheduledTransactionService.walletPool(plain.stream()
                        .filter(u -> !ref(u).equals(ref(collateral))).toList(), required).poll()
                : small;
        assertNotNull(walletInput);

        List<Utxo> wallet = new ArrayList<>();
        switch (ordering) {
            case WALLET_FIRST -> {
                wallet.addAll(plain);
                wallet.addAll(scripts);
            }
            case SCRIPTS_FIRST -> {
                wallet.addAll(scripts);
                wallet.addAll(plain);
            }
            case SPLIT -> {
                for (int i = 0; i < Math.max(plain.size(), scripts.size()); i++) {
                    if (i < scripts.size()) {
                        wallet.add(scripts.get(i));
                    }
                    if (i < plain.size()) {
                        wallet.add(plain.get(i));
                    }
                }
            }
        }

        List<Utxo> universe = new ArrayList<>(refs);
        universe.add(tank);
        universe.addAll(wallet);
        UtxoSupplier supplier = supplier(universe);

        // ⛔ THE BEAN, not a QuickTxBuilder built here: whatever YaciConfig wires is what this exercises.
        QuickTxBuilder bean = new YaciConfig().quickTxBuilder(supplier, () -> params, scriptSupplier(validator),
                backend);

        var tankDatum = new DatumTankConverter().deserialize(fx.get("tank").get("inline_datum").asText());
        TransactionInput paramsRef = TransactionInput.builder().transactionId(PARAMS_REF_TX).index(0).build();
        TransactionInput stakerRef = TransactionInput.builder().transactionId(STAKER_REF_TX).index(0).build();
        TransactionInput tankRef = TransactionInput.builder().transactionId(TANK_REF_TX).index(0).build();
        List<TransactionInput> sorted = Stream.of(paramsRef, stakerRef, tankRef)
                .sorted(new TransactionInputComparator()).toList();
        var redeemerData = new ScheduledTransactionData();
        redeemerData.setInputtankindex(BigInteger.ZERO);
        redeemerData.setBatcher(AddressUtil.toOnchainAddress(new com.bloxbean.cardano.client.address.Address(operator)));
        redeemerData.setReferenceParamsIndex(BigInteger.valueOf(sorted.indexOf(paramsRef)));
        redeemerData.setReferenceStakingIndex(BigInteger.valueOf(sorted.indexOf(stakerRef)));
        redeemerData.setWhitelistIndex(BigInteger.ZERO);
        var parameters = new DatumParametersConverter().deserialize(paramsDatumHex(fx));

        ScriptTx tx = ScheduledTransactionService.tankScriptTx(
                walletInput, tank, redeemerData.toPlutusData(),
                AddressUtil.toAddress(tankDatum.getDestionationaaddress(), Networks.mainnet()).getAddress(),
                AssetAmountUtil.toValue(List.of(tankDatum.getScheduledamount())),
                AddressUtil.toAddress(parameters.getAddressRewards(), Networks.mainnet()).getAddress(),
                AssetAmountUtil.toValue(List.of(tankDatum.getReward())),
                operator, paramsRef, stakerRef, tankRef);

        // The moment production tried this tank: the first slot strictly after its execution time, plus one.
        java.util.function.ToLongFunction<java.time.LocalDateTime> toSlot = t -> SlotConfigs.mainnet().getZeroSlot()
                + (t.toInstant(ZoneOffset.UTC).toEpochMilli() - SlotConfigs.mainnet().getZeroTime()) / 1000;
        long firstValidSlot = ScheduledTransactionService.firstValidSlot(tankDatum.getExecutiontime(), toSlot);

        var scalusEvaluator = new scalus.bloxbean.ScalusTransactionEvaluator(
                scalus.cardano.ledger.SlotConfig$.MODULE$.mainnet(), params, supplier,
                (scalus.bloxbean.ScriptSupplier) scriptHash -> validator,
                scalus.bloxbean.EvaluatorMode.EVALUATE_AND_COMPUTE_COST, false);

        return new Rig(bean, supplier, tx, tank, collateral, operator, firstValidSlot + 1, firstValidSlot,
                scripts, scalusEvaluator);
    }

    private static ScriptSupplier scriptSupplier(PlutusV3Script validator) {
        return hash -> TANK_SCRIPT_HASH.equals(hash) ? Optional.of(validator) : Optional.empty();
    }

    private static String ref(Utxo utxo) {
        return utxo.getTxHash() + "#" + utxo.getOutputIndex();
    }

    private static String ref(TransactionInput input) {
        return input.getTransactionId() + "#" + input.getIndex();
    }

    private static String chain(Throwable t) {
        StringBuilder out = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            out.append(c.getClass().getSimpleName()).append(": ").append(c.getMessage()).append(" <- ");
        }
        return out.toString();
    }

    // ---- fixture readers (the same recorded mainnet tank as TankTransactionDryEvalTest) -------------

    private static JsonNode fixture() throws Exception {
        try (InputStream in = TankProductionWiringTest.class.getResourceAsStream("/tank-eval-fixture.json")) {
            assertNotNull(in, "/tank-eval-fixture.json missing from test resources");
            return new ObjectMapper().readTree(in);
        }
    }

    private static String paramsDatumHex(JsonNode fx) {
        for (JsonNode r : fx.withArray("refs")) {
            if (PARAMS_REF_TX.equals(r.get("tx_hash").asText())) {
                return r.get("inline_datum").asText();
            }
        }
        return fail("parameters reference input missing from the fixture");
    }

    private static Utxo utxo(JsonNode n) {
        List<Amount> amounts = new ArrayList<>();
        amounts.add(Amount.lovelace(new BigInteger(n.get("value").asText())));
        for (JsonNode a : n.withArray("asset_list")) {
            amounts.add(Amount.asset(a.get("policy_id").asText() + a.get("asset_name").asText(),
                    new BigInteger(a.get("quantity").asText())));
        }
        var b = Utxo.builder()
                .txHash(n.get("tx_hash").asText())
                .outputIndex(n.get("tx_index").asInt())
                .address(n.get("address").asText())
                .amount(amounts);
        if (n.hasNonNull("inline_datum")) {
            b.inlineDatum(n.get("inline_datum").asText());
        }
        if (n.hasNonNull("reference_script_hash")) {
            b.referenceScriptHash(n.get("reference_script_hash").asText());
        }
        return b.build();
    }

    private static ProtocolParams protocolParams(JsonNode p) {
        LinkedHashMap<String, List<Long>> costModels = new LinkedHashMap<>();
        List<Long> v3 = new ArrayList<>();
        p.withArray("plutus_v3_cost_model").forEach(x -> v3.add(x.asLong()));
        costModels.put("PlutusV3", v3);
        return ProtocolParams.builder()
                .minFeeA(p.get("min_fee_a").asInt())
                .minFeeB(p.get("min_fee_b").asInt())
                .maxTxSize(p.get("max_tx_size").asInt())
                .maxValSize(String.valueOf(p.get("max_val_size").asInt()))
                .coinsPerUtxoSize(p.get("coins_per_utxo_size").asText())
                .priceMem(new BigDecimal(p.get("price_mem").asText()))
                .priceStep(new BigDecimal(p.get("price_step").asText()))
                .maxTxExMem(p.get("max_tx_ex_mem").asText())
                .maxTxExSteps(p.get("max_tx_ex_steps").asText())
                .collateralPercent(new BigDecimal(p.get("collateral_percent").asText()))
                .maxCollateralInputs(p.get("max_collateral_inputs").asInt())
                .minFeeRefScriptCostPerByte(new BigDecimal(p.get("min_fee_ref_script_cost_per_byte").asText()))
                .protocolMajorVer(p.get("protocol_major").asInt())
                .protocolMinorVer(p.get("protocol_minor").asInt())
                .costModelsRaw(costModels)
                .build();
    }

    private static UtxoSupplier supplier(List<Utxo> universe) {
        return new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
                return page != null && page > 0 ? List.of()
                        : universe.stream().filter(u -> u.getAddress().equals(address)).toList();
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return universe.stream()
                        .filter(u -> u.getTxHash().equals(txHash) && u.getOutputIndex() == outputIndex)
                        .findFirst();
            }
        };
    }

    private static PlutusV3Script tankValidator() throws Exception {
        JsonNode blueprint = new ObjectMapper().readTree(
                TankProductionWiringTest.class.getResourceAsStream("/plutus.json"));
        for (JsonNode v : blueprint.withArray("validators")) {
            if ("tank.tank.spend".equals(v.get("title").asText())) {
                return (PlutusV3Script) com.bloxbean.cardano.client.plutus.blueprint.PlutusBlueprintUtil
                        .getPlutusScriptFromCompiledCode(v.get("compiledCode").asText(),
                                com.bloxbean.cardano.client.plutus.blueprint.model.PlutusVersion.v3);
            }
        }
        return fail("tank.tank.spend not found in plutus.json");
    }
}
