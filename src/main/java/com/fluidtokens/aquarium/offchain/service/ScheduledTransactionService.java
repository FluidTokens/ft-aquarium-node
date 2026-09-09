package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.fluidtokens.aquarium.offchain.service.loans.ReferenceScriptSafeUtxoSelection;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import java.util.Comparator;
import com.fluidtokens.aquarium.offchain.util.LedgerCeilings;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.model.AddressUtxoEntity;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.DatumTank;
import com.fluidtokens.aquarium.offchain.blueprint.types.datum.model.converter.DatumTankConverter;
import com.fluidtokens.aquarium.offchain.blueprint.types.redeemer.model.impl.ScheduledTransactionData;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.util.AddressUtil;
import com.fluidtokens.aquarium.offchain.util.AssetAmountUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.cardanofoundation.conversions.CardanoConverters;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.fluidtokens.aquarium.offchain.util.UtxoUtil.toUtxo;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import static java.math.BigInteger.ZERO;

/**
 * ⛔ <b>OFF BY DEFAULT.</b> {@code scheduling.transaction-processor.enabled} must be set to
 * {@code true} for this service to exist at all — there is no {@code matchIfMissing}, so an operator
 * who says nothing gets a node that indexes and serves but never builds or submits a transaction.
 *
 * <p>⚠ <b>This is a deliberate change of default for an operator-facing image</b>, and it is the
 * safe direction: the previous behaviour was that pulling the image and starting it began
 * <em>spending from the configured wallet</em> on the next scheduling tick. Arming is now something
 * an operator does on purpose. ⇒ An operator upgrading across this change and expecting the
 * processor to keep running <b>must set the flag</b>; nothing else in the node will complain,
 * because a quiet processor and a disabled one look identical from outside — which is exactly why
 * this logs its own absence at startup rather than staying silent.
 *
 * <p>The gate is on the BEAN, not on the scheduled method, so a disabled processor costs nothing and
 * cannot be re-armed by a stray call. Nothing injects this class — its many mentions elsewhere are
 * javadoc cross-references — so removing it from the context is safe.
 */
@Service
@ConditionalOnProperty(prefix = "scheduling.transaction-processor", name = "enabled",
        havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class ScheduledTransactionService {

    record RefInputIndexes(BigInteger paramsIndex, BigInteger stakingIndex) {

    }

    private record DatumTankUtxo(DatumTank datumTank, Utxo utxo) {

    }

    private final AppConfig.Network network;

    private final AppConfig.AquariumConfiguration aquariumConfiguration;

    private final Account account;

    private final QuickTxBuilder quickTxBuilder;


    /**
     * Only used to build the reference-script-safe coin selection; see the guard at compose().
     * <p>
     * <b>Deliberately the backend and not the {@code UtxoSupplier} bean.</b> That bean is
     * {@code @ConditionalOnProperty(loans.enabled=true)}, and this service is the Aquarium tank
     * subsystem, which runs on mainnet where lending is disabled by default — injecting it here
     * would have failed startup on exactly the production deployment this repo ships. The backend
     * is unconditional, and this is the same construction {@code YaciConfig} performs.
     */
    private final BFBackendService bfBackendService;

    private final UtxoRepository utxoRepository;

    private final StakerService service;

    private final CardanoConverters cardanoConverters;

    private final ParametersService parametersService;

    private final TankContractService tankContractService;

    private final AppUtxoService appUtxoService;

    private final BlockEventListener blockEventListener;

    private final Vector<TransactionInput> unprocessableScheduledTransactions = new Vector<>();

    private final DatumTankConverter datumConverter = new DatumTankConverter();

    private RefInputIndexes resolveRefIndexes(TransactionInput parametersRefInput, TransactionInput stakingRefInput) {
        var sortedRefInputs = Stream.of(parametersRefInput, stakingRefInput, aquariumConfiguration.getTankRefInput())
                .sorted(new TransactionInputComparator())
                .toList();
        var parametersRefInputIndex = sortedRefInputs.indexOf(parametersRefInput);
        var stakingRefInputIndex = sortedRefInputs.indexOf(stakingRefInput);
        return new RefInputIndexes(BigInteger.valueOf(parametersRefInputIndex), BigInteger.valueOf(stakingRefInputIndex));
    }


    /** The supplier the selection guard reads through; see the field javadoc for why it is built here. */
    private UtxoSupplier referenceScriptSafeSupplier() {
        return new DefaultUtxoSupplier(bfBackendService.getUtxoService());
    }

    @Scheduled(timeUnit = TimeUnit.MINUTES, fixedDelayString = "${scheduling.transaction-processor.delay-minutes}")
    public void processPayments() {

        log.info("Starting Process Payments RUN");

        if (blockEventListener.getIsSyncing().get()) {
            log.info("node is syncing, skipping...");
            return;
        }

        var stakerRefInputOpt = service.findStakerRefInput();
        if (stakerRefInputOpt.isEmpty()) {
            log.info("Cannot find stake for {}", account.baseAddress());
            return;
        }

        final var stakerRefInput = stakerRefInputOpt.getFirst();

        final var parametersRefInput = parametersService.loadParametersRefInput();
        final var parameters = parametersService.loadParameters();

        var tankUtxos = utxoRepository
                .findUnspentByOwnerPaymentCredential(tankContractService.getScriptHashHex(), Pageable.unpaged())
                .stream()
                .flatMap(List::stream)
                .toList();


        final var tankContractRefInput = aquariumConfiguration.getTankRefInput();

        var refInputIndexes = resolveRefIndexes(parametersRefInput, stakerRefInput);

        var scheduledTank = tankUtxos
                .stream()
                .filter(filterUnprocessableScheduledTransactions(unprocessableScheduledTransactions))
                .flatMap(getAddressUtxoEntityStreamFunction())
                .filter(isScheduledTankTransaction())
                .toList();

        var processableScheduledTransactions = scheduledTank.stream()
                .filter(isScheduledTxTimeValid())
                .toList();

        log.info("Found {} Tank Utxos of which {} Scheduled Transactions and {} Processable Scheduled Transactions",
                tankUtxos.size(),
                scheduledTank.size(),
                processableScheduledTransactions.size());

        processableScheduledTransactions.forEach(datumTankUtxo -> {

            var tankPaymentUtxo = datumTankUtxo.utxo();
            var tankDatum = datumTankUtxo.datumTank();

            try {

                List<Utxo> walletUtxos = appUtxoService.listWalletUtxo();
                if (walletUtxos.isEmpty()) {
                    log.warn("No wallet UTXOs found for account: {}", account.baseAddress());
                    return;
                }

                // The wallet input must be ada-only, must not carry a reference script, and must
                // PROVABLY COVER what this transaction can cost (T-053).
                //
                // It used to be `findFirst()` with no size floor, which takes an ARBITRARY ada-only
                // utxo — dust included. That is the shape that starved the liquidation path on a
                // 1 ADA output on 2026-08-25.
                //
                // ⚠ SMALLEST THAT SUFFICES, not largest-first. Largest-first would spend the biggest
                // utxo to pay a fee and fragment the wallet against the case where a large one is
                // genuinely needed; Giovanni's own words are "a 5 ada utxo would be perfect". The
                // requirement is DERIVED from the protocol parameters, never assumed — see
                // LedgerCeilings, which exists because cardano-client-lib answers this same question
                // with a hardcoded Amount.ada(5.0).
                // ⚠ Derived from the BACKEND, not from a ProtocolParamsSupplier bean: that bean is
                // @ConditionalOnProperty(loans.enabled) and lending is DISABLED ON MAINNET, so
                // depending on it here would break the context on the one path operators run.
                var protocolParams = new DefaultProtocolParamsSupplier(bfBackendService.getEpochService())
                        .getProtocolParams();
                var required = LedgerCeilings.maxPossibleFee(protocolParams);

                var walletUtxoOpt = walletUtxos
                        .stream()
                        .filter(utxo -> utxo.getAmount().size() == 1 && utxo.getReferenceScriptHash() == null)
                        .filter(utxo -> LedgerCeilings.lovelaceOf(utxo).compareTo(required) >= 0)
                        .min(Comparator.comparing(LedgerCeilings::lovelaceOf));

                if (walletUtxoOpt.isEmpty()) {
                    var largest = walletUtxos.stream()
                            .filter(utxo -> utxo.getAmount().size() == 1 && utxo.getReferenceScriptHash() == null)
                            .map(LedgerCeilings::lovelaceOf)
                            .max(java.math.BigInteger::compareTo);
                    log.warn("no ada-only wallet utxo covers the {} lovelace this ledger could charge; "
                                    + "largest available is {}. Fund the wallet with a single ada-only "
                                    + "utxo of at least that amount.",
                            required, largest.map(Object::toString).orElse("none at all"));
                    return;
                }

                var walletUtxo = walletUtxoOpt.get();

                var batcher = AddressUtil.toOnchainAddress(account.getBaseAddress());

                var scheduledTransactionData = new ScheduledTransactionData();
                scheduledTransactionData.setInputtankindex(ZERO);
                scheduledTransactionData.setBatcher(batcher);
                scheduledTransactionData.setReferenceParamsIndex(refInputIndexes.paramsIndex());
                scheduledTransactionData.setReferenceStakingIndex(refInputIndexes.stakingIndex());
                scheduledTransactionData.setWhitelistIndex(ZERO);

                var redeemer = scheduledTransactionData.toPlutusData();

                var amountToSend = AssetAmountUtil.toValue(List.of(tankDatum.getScheduledamount()));

                var payeeAddress = AddressUtil.toAddress(tankDatum.getDestionationaaddress(), network.getCardanoNetwork());

                var now = LocalDateTime.now(ZoneOffset.UTC);
                var slot = cardanoConverters.time().toSlot(now);

                var reward = AssetAmountUtil.toValue(List.of(tankDatum.getReward()));

                var rewardsAddress = AddressUtil.toAddress(parameters.getAddressRewards(), network.getCardanoNetwork());

                var tx = new ScriptTx()
                        .collectFrom(walletUtxo)
                        .collectFrom(tankPaymentUtxo, redeemer)
                        .payToAddress(payeeAddress.getAddress(), ValueUtil.toAmountList(amountToSend))
                        .payToAddress(rewardsAddress.getAddress(), ValueUtil.toAmountList(reward))
                        .withChangeAddress(account.baseAddress())
                        .readFrom(parametersRefInput)
                        .readFrom(stakerRefInput)
                        .readFrom(tankContractRefInput);

                quickTxBuilder.compose(tx)
                        // ⛔ NEVER SPEND A UTxO CARRYING A REFERENCE SCRIPT.
                        //
                        // This service reaches coin selection through the SHARED QuickTxBuilder bean
                        // (YaciConfig), so there is no tx.from(...) here to grep for — the hazard
                        // arrives by injection and is invisible to a search for the dangerous call.
                        // That is how it was missed: the two liquidation builders construct their own
                        // builders and were guarded, and this third site was not.
                        //
                        // It spends from the same wallet as the liquidation bot. Nothing with a
                        // scriptRef is in that wallet today, but a guard whose absence depends on a
                        // wallet staying empty of a particular UTxO shape is not a guard -- and this
                        // repo published a reference script to its own address on 2026-08-17 and had
                        // it consumed by an unguarded builder on 2026-08-25.
                        .withUtxoSelectionStrategy(
                                ReferenceScriptSafeUtxoSelection.strategy(referenceScriptSafeSupplier()))
                        .preBalanceTx((ctx, txn) -> ctx.setUtxoSelector(
                                ReferenceScriptSafeUtxoSelection.selector(referenceScriptSafeSupplier())))
                        .withSigner(SignerProviders.signerFrom(account))
                        .withSigner(SignerProviders.stakeKeySignerFrom(account))
                        .withRequiredSigners(account.getBaseAddress().getDelegationCredentialHash().get())
                        .validFrom(slot - 30)
                        .validTo(slot + 180)
                        .feePayer(account.baseAddress())
                        .collateralPayer(account.baseAddress())
                        .mergeOutputs(false)
                        .ignoreScriptCostEvaluationError(false)
                        // T-059 — THE ONLY MAINNET PATH NOW ASSERTS ITS OWN STRUCTURE.
                        //
                        // Every guarantee the lending-v4 review added lives on the PREVIEW paths;
                        // this one had none. And `withVerifier` is genuinely reached here because the
                        // tank submits through completeAndWait() — the one place in that whole arc
                        // where the obviously-named API is the right one, after three that were not.
                        //
                        // ⚠ It COMPOSES (QuickTxBuilder:863-868 uses andThen), unlike preBalanceTx
                        // above, which is a SETTER whose second call silently discards the first.
                        // Two hooks on one builder with opposite semantics.
                        .withVerifier(TankStructureVerifier.of(
                                tankPaymentUtxo,
                                parametersRefInput, stakerRefInput,
                                refInputIndexes.paramsIndex(), refInputIndexes.stakingIndex(),
                                ZERO,
                                payeeAddress.getAddress(), amountToSend.getCoin(),
                                rewardsAddress.getAddress(), reward.getCoin()))
                        .completeAndWait();

            } catch (Exception e) {
                unprocessableScheduledTransactions.add(TransactionInput.builder()
                        .transactionId(tankPaymentUtxo.getTxHash())
                        .index(tankPaymentUtxo.getOutputIndex())
                        .build());
                log.warn("Could not process Tank utxo: {}:{}", tankPaymentUtxo.getTxHash(), tankPaymentUtxo.getOutputIndex());
                log.warn("Error", e);
            }

        });
    }

    /**
     * transform a AddressUtxoEntity into an "optional" DatumTankUtxo stream.
     * @return an utxo object and the Tank Datum if it can be deserialized, otherwise an empty stream.
     */
    private Function<AddressUtxoEntity, Stream<DatumTankUtxo>> getAddressUtxoEntityStreamFunction() {
        return addressUtxoEntity -> {
            try {
                var inlineDatum = addressUtxoEntity.getInlineDatum();
                var tankDatum = datumConverter.deserialize(inlineDatum);
                return Stream.of(new DatumTankUtxo(tankDatum, toUtxo(addressUtxoEntity)));
            } catch (Exception e) {
                log.warn("could not deserialise datum for: {}", addressUtxoEntity);
                return Stream.empty();
            }
        };
    }

    /**
     * Checks whether the current Scheduled Tx schedule time has been reached.
     *
     * @return
     */
    private static Predicate<DatumTankUtxo> isScheduledTxTimeValid() {
        return datumTankUtxo -> {
            var tankDatum = datumTankUtxo.datumTank();
            var timestamp = tankDatum.getExecutiontime();
            var startTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp.longValue()), ZoneOffset.UTC);
            return LocalDateTime.now(ZoneOffset.UTC).isAfter(startTime);
        };
    }

    /**
     * Checks whether the current Scheduled Tx schedule time has been reached.
     *
     * @return
     */
    private static Predicate<AddressUtxoEntity> filterUnprocessableScheduledTransactions(Vector<TransactionInput> unprocessableScheduledTransactions) {
        return addressUtxoEntity -> !unprocessableScheduledTransactions.contains(TransactionInput.builder()
                        .transactionId(addressUtxoEntity.getTxHash())
                        .index(addressUtxoEntity.getOutputIndex())
                .build());
    }

    /**
     * Not all tank utxos are Scheduled Tx. In order to be one, allowed tokens list must be empty and there must be
     * some operator rewards.
     *
     * @return
     */
    private static Predicate<DatumTankUtxo> isScheduledTankTransaction() {
        return datumTankUtxo -> {
            var datumTank = datumTankUtxo.datumTank();
            var reward = AssetAmountUtil.toValue(List.of(datumTank.getReward()));
            return datumTank.getAllowedtokens().isEmpty() && !reward.isZero() && reward.isPositive();
        };
    }



}
