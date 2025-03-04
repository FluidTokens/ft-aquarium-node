package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.util.ValueUtil;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.blueprint.types.redeemer.model.impl.RedeemerStakerData;
import com.fluidtokens.aquarium.offchain.config.AppConfig;
import com.fluidtokens.aquarium.offchain.util.AddressUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class StakerService {

    private final AppConfig.Network network;

    private final AppConfig.AquariumConfiguration aquariumConfiguration;

    private final Account account;

    private final BFBackendService bfBackendService;

    private final QuickTxBuilder quickTxBuilder;

    private final UtxoRepository utxoRepository;

    private final StakerContractService stakerContractService;

    private final ParametersService parametersService;

    public List<TransactionInput> findStakerRefInput() {

        var paymentCredentials = stakerContractService.getPaymentCredentials();
        var delegationCredentials = account.getBaseAddress().getDelegationCredential().get();

        var cardanoNetwork = network.getCardanoNetwork();

        var stakerAddress = AddressProvider.getBaseAddress(paymentCredentials, delegationCredentials, cardanoNetwork);

        var fldtStakes = utxoRepository.findUnspentByOwnerPaymentCredential(stakerContractService.getScriptHashHex(), Pageable.unpaged());
        var stakes = fldtStakes.stream().flatMap(Collection::stream)
                .filter(foo -> foo.getOwnerAddr().equals(stakerAddress.getAddress()))
                .toList();

        if (stakes.isEmpty()) {
            log.warn("It was not possible to find staked tokens.");
        }

        return stakes.stream()
                .map(utxo -> TransactionInput.builder().transactionId(utxo.getTxHash()).index(utxo.getOutputIndex()).build())
                .toList();

    }

    @PostConstruct
    public void init() {

        var autoStake = aquariumConfiguration.getAutoStake();
        log.info("INIT Auto Stake? {}", autoStake);

        var autoUnstake = aquariumConfiguration.getAutoUnstake();
        log.info("INIT Auto Unstake? {}", autoUnstake);

        var autoStakerThread = new Thread(() -> {

            var stakerRefInputs = findStakerRefInput();
            log.info("INIT Found {} staker inputs", stakerRefInputs.size());
            stakerRefInputs.forEach(stakerRefInput -> log.info("INFO Staker Ref Input: {}:{}", stakerRefInput.getTransactionId(), stakerRefInput.getIndex()));

            if (stakerRefInputs.isEmpty() && autoStake) {
                log.info("INIT Attempting to stake tokens");

                var attempts = 3;
                boolean autoStakeSucceeded = false;

                while (stakerRefInputs.isEmpty() && !autoStakeSucceeded && attempts >= 0) {
                    var stakeTxOpt = executeStakeTransaction();
                    if (stakeTxOpt.isPresent()) {
                        autoStakeSucceeded = true;
                        log.info("INIT Successfully staked your tokens. You can now process Scheduled Transactions");
                    } else {
                        log.warn("INIT It was not possible to complete auto-staking transaction. Reasons: (1) you might not have enough FLDT Tokens or ada " +
                                "in the wallet you want to stake, (2) Yaci syncing process is still ongoing and Parameters utxo could not be found. Retrying shortly...");
                        attempts -= 1;
                        try {
                            Thread.sleep(60_000L);
                            stakerRefInputs = findStakerRefInput();
                        } catch (InterruptedException e) {
                            log.warn("Shutting down auto-staking process", e);
                        }
                    }
                }

                if (!stakerRefInputs.isEmpty()) {
                    log.info("INIT Found {} staker inputs", stakerRefInputs.size());
                } else if (!autoStakeSucceeded) {
                    log.warn("It was not possible to complete auto-staking transaction. Please wait for the node to be completely synced, than restart. Thank you.");
                }

            } else if (!stakerRefInputs.isEmpty() && autoUnstake) {

                log.info("about to attempt auto unstake transaction");
                var autoUnstakeTxHashOpt = executeUnstakeTransaction();

                var attempts = 3;
                while (autoUnstakeTxHashOpt.isEmpty() && attempts >= 0) {
                    attempts -= 1;
                    try {
                        Thread.sleep(60_000L);
                        autoUnstakeTxHashOpt = executeUnstakeTransaction();
                    } catch (InterruptedException e) {
                        log.warn("Shutting down auto-staking process", e);
                    }

                }


            } else {
                log.info("INIT AutoStake disabled or you've already staked your tokens");
            }
        });

        autoStakerThread.start();

    }


    private Optional<String> executeStakeTransaction() {

        try {

            log.info("bot operator address: {}", account.getBaseAddress());

            var parameters = parametersService.loadParameters();

            var minAmountToStake = parameters.getMinToStake();
            log.info("minAmountToStake: {}", minAmountToStake);

            var scriptAddress = AddressProvider.getBaseAddress(Credential.fromScript(stakerContractService.getScriptHash()),
                    Credential.fromKey(account.getBaseAddress().getDelegationCredentialHash().get()),
                    network.getCardanoNetwork());

            var stakingNft = Asset.builder()
                    .name("0x" + HexUtil.encodeHexString(account.getBaseAddress().getDelegationCredentialHash().get()))
                    .value(BigInteger.ONE)
                    .build();

            var stakingNftAmount = Value.builder()
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(stakerContractService.getScriptHashHex())
                                    .assets(List.of(stakingNft))
                                    .build()
                    ))
                    .build();
            log.info("stakingNftAmount: {}", stakingNftAmount);

            var stake = Value.builder()
                    .multiAssets(List.of(
                            MultiAsset.builder()
                                    .policyId(aquariumConfiguration.getStakingTokenPolicy())
                                    .assets(List.of(Asset.builder()
                                            .name("0x" + aquariumConfiguration.getStakingTokenName())
                                            .value(minAmountToStake)
                                            .build()))
                                    .build()
                    ))
                    .build();
            log.info("stake: {}", stake);

            var valueToSend = stakingNftAmount.plus(stake);
            valueToSend.setCoin(parameters.getMinAda());
            log.info("valueToSend: {}", valueToSend);

            var redeemer = new RedeemerStakerData();
            redeemer.setSigner(AddressUtil.toOnchainAddress(account.getBaseAddress()));
            redeemer.setReferenceIndex(BigInteger.ZERO);
            redeemer.setOutputStaking(BigInteger.ZERO);

            var tx = new ScriptTx()
                    .mintAsset(stakerContractService.getPlutusScript(), stakingNft, redeemer.toPlutusData())
                    .payToContract(scriptAddress.getAddress(), ValueUtil.toAmountList(valueToSend), (PlutusData) null)
                    .readFrom(parametersService.loadParametersRefInput());

            var transaction = quickTxBuilder.compose(tx)
                    .feePayer(account.baseAddress())
                    .collateralPayer(account.baseAddress())
                    .withSigner(SignerProviders.signerFrom(account))
                    .withSigner(SignerProviders.stakeKeySignerFrom(account))
                    .withRequiredSigners(account.getBaseAddress().getDelegationCredentialHash().get())
                    .completeAndWait(Duration.of(5, ChronoUnit.MINUTES));


            if (transaction.isSuccessful()) {
                return Optional.of(transaction.getValue());
            } else {
                return Optional.empty();
            }

        } catch (Exception e) {
            log.warn("error: ", e);
            return Optional.empty();
        }

    }

    private Optional<String> executeUnstakeTransaction() {

        try {

            var stakingUtxos = findStakerRefInput();
            log.info("stakingUtxos size: {}", stakingUtxos.size());

            var stakingUtxoOpt = stakingUtxos.stream()
                    .flatMap(transactionInput -> {
                        try {
                            var utxoResponse = bfBackendService.getUtxoService().getTxOutput(transactionInput.getTransactionId(), transactionInput.getIndex());
                            if (utxoResponse.isSuccessful()) {
                                return Stream.of(utxoResponse.getValue());
                            } else {
                                return Stream.empty();
                            }
                        } catch (ApiException e) {
                            log.warn("Error while fetching staking utxo", e);
                            return Stream.empty();
                        }
                    })
                    .findFirst();

            if (stakingUtxoOpt.isEmpty()) {
                log.info("No FLDT staking utxos found, terminating...");
                return Optional.empty();
            }

            var stakingUtxo = stakingUtxoOpt.get();
            log.info("stakingUtxo: {}:{}", stakingUtxo.getTxHash(), stakingUtxo.getOutputIndex());

            var stakingNft = Asset.builder()
                    .name("0x" + HexUtil.encodeHexString(account.getBaseAddress().getDelegationCredentialHash().get()))
                    .value(BigInteger.ONE.negate())
                    .build();

            var redeemer = new RedeemerStakerData();
            redeemer.setSigner(AddressUtil.toOnchainAddress(account.getBaseAddress()));
            redeemer.setReferenceIndex(BigInteger.ZERO);
            redeemer.setOutputStaking(BigInteger.ZERO);

            var tx = new ScriptTx()
                    .collectFrom(stakingUtxo, redeemer.toPlutusData())
                    .mintAsset(stakerContractService.getPlutusScript(), stakingNft, redeemer.toPlutusData())
                    .readFrom(parametersService.loadParametersRefInput())
                    .withChangeAddress(account.baseAddress());

            var unstakingResult = quickTxBuilder.compose(tx)
                    .feePayer(account.baseAddress())
                    .collateralPayer(account.baseAddress())
                    .withSigner(SignerProviders.signerFrom(account))
                    .withSigner(SignerProviders.stakeKeySignerFrom(account))
                    .withRequiredSigners(account.getBaseAddress().getDelegationCredentialHash().get())
                    .completeAndWait();
            if (unstakingResult.isSuccessful()) {
                return Optional.of(unstakingResult.getValue());
            } else {
                return Optional.empty();
            }
        } catch (Exception e) {
            log.warn("could not perform unstake", e);
            return Optional.empty();
        }

    }

}
