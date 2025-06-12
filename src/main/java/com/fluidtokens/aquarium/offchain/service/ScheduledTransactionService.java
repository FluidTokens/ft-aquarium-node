package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static java.math.BigInteger.ZERO;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledTransactionService {

    record RefInputIndexes(BigInteger paramsIndex, BigInteger stakingIndex) {

    }

    private record DatumTankUtxo(DatumTank datumTank, AddressUtxoEntity addressUtxoEntity) {

    }

    private final AppConfig.Network network;

    private final AppConfig.AquariumConfiguration aquariumConfiguration;

    private final Account account;

    private final BFBackendService bfBackendService;

    private final QuickTxBuilder quickTxBuilder;

    private final UtxoRepository utxoRepository;

    private final StakerService service;

    private final CardanoConverters cardanoConverters;

    private final ParametersService parametersService;

    private final TankContractService tankContractService;

    private final DatumTankConverter datumConverter = new DatumTankConverter();

    private RefInputIndexes resolveRefIndexes(TransactionInput parametersRefInput, TransactionInput stakingRefInput) {
        var sortedRefInputs = Stream.of(parametersRefInput, stakingRefInput, aquariumConfiguration.getTankRefInput())
                .sorted(new TransactionInputComparator())
                .toList();
        var parametersRefInputIndex = sortedRefInputs.indexOf(parametersRefInput);
        var stakingRefInputIndex = sortedRefInputs.indexOf(stakingRefInput);
        return new RefInputIndexes(BigInteger.valueOf(parametersRefInputIndex), BigInteger.valueOf(stakingRefInputIndex));
    }


    @Scheduled(timeUnit = TimeUnit.MINUTES, fixedDelay = 5)
    public void processPayments() {

        log.info("Starting Process Payments RUN");

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
                .flatMap(addressUtxoEntity -> {
                    try {
                        var inlineDatum = addressUtxoEntity.getInlineDatum();
                        var tankDatum = datumConverter.deserialize(inlineDatum);
                        return Stream.of(new DatumTankUtxo(tankDatum, addressUtxoEntity));
                    } catch (Exception e) {
                        log.warn("could not deserialise datum for: {}", addressUtxoEntity);
                        return Stream.empty();
                    }
                })
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

            var utxoEntity = datumTankUtxo.addressUtxoEntity();
            var tankDatum = datumTankUtxo.datumTank();

            try {

                List<Utxo> walletUtxos;
                try {
                    walletUtxos = bfBackendService.getUtxoService().getUtxos(account.baseAddress(), 10, 1).getValue();
                } catch (ApiException e) {
                    log.warn("error: ", e);
                    return;
                }

                // Ensure to use utxo with just ada
                var walletUtxoOpt = walletUtxos
                        .stream()
                        .filter(utxo -> utxo.getAmount().size() == 1 && utxo.getReferenceScriptHash() == null)
                        .findFirst();

                if (walletUtxoOpt.isEmpty()) {
                    log.warn("no valid utxos found. please ensure wallet has at least one utxo which contains ONLY ADA");
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

                var tankUtxoResponse = bfBackendService.getUtxoService().getTxOutput(utxoEntity.getTxHash(), utxoEntity.getOutputIndex());
                if (!tankUtxoResponse.isSuccessful()) {
                    log.warn("could not resolve tank utxo {}:{}, skipping...", utxoEntity.getTxHash(), utxoEntity.getOutputIndex());
                    return;
                }

                var tankPaymentUtxo = tankUtxoResponse.getValue();

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
                        .withSigner(SignerProviders.signerFrom(account))
                        .withSigner(SignerProviders.stakeKeySignerFrom(account))
                        .withRequiredSigners(account.getBaseAddress().getDelegationCredentialHash().get())
                        .validFrom(slot - 30)
                        .validTo(slot + 180)
                        .feePayer(account.baseAddress())
                        .collateralPayer(account.baseAddress())
                        .mergeOutputs(false)
                        .ignoreScriptCostEvaluationError(false)
                        .completeAndWait();

            } catch (Exception e) {
                log.warn("Could not process Tank utxo: {}:{}", utxoEntity.getTxHash(), utxoEntity.getOutputIndex());
                log.warn("Error", e);
            }

        });
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
