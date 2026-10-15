package com.fluidtokens.aquarium.offchain.service;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.yaci.store.utxo.storage.impl.repository.UtxoRepository;
import com.fluidtokens.aquarium.offchain.model.AssetType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@Slf4j
public class StakerService {

    private enum StakeStatus { UNKNOWN, PRESENT, ABSENT }

    private final Account account;

    private final UtxoRepository utxoRepository;

    private final StakerContractService stakerContractService;

    /**
     * Last observed stake status. Called from the boot hook, the tank cycle and healthcheck threads,
     * so the status is logged loudly once per change rather than once per caller per cycle.
     */
    private final AtomicReference<StakeStatus> lastStatus = new AtomicReference<>(StakeStatus.UNKNOWN);

    @PostConstruct
    public void init() {
        var stakerRefInput = findStakerRefInput();

        log.info("found {} staker inputs", stakerRefInput.size());

        if (!stakerRefInput.isEmpty()) {
            var stakerInput = stakerRefInput.getFirst();
            log.info("Staker Input: {}:{}", stakerInput.getTransactionId(), stakerInput.getIndex());
        }

    }

    public List<TransactionInput> findStakerRefInput() {

        var stakingToken = AssetType.fromPlutusData(stakerContractService.getScriptHash(), account.getBaseAddress().getDelegationCredentialHash().get());

        var fldtStakes = utxoRepository.findUnspentByOwnerPaymentCredential(stakerContractService.getScriptHashHex(), Pageable.unpaged());
        var stakes = fldtStakes.stream().flatMap(Collection::stream)
                .filter(addressUtxoEntity -> addressUtxoEntity.getAmounts().stream().anyMatch(amount -> stakingToken.toUnit().equals(amount.getUnit())))
                .toList();

        var refInputs = stakes.stream()
                .map(utxo -> TransactionInput.builder().transactionId(utxo.getTxHash()).index(utxo.getOutputIndex()).build())
                .toList();

        logStakeStatus(refInputs);

        return refInputs;

    }

    private void logStakeStatus(List<TransactionInput> refInputs) {
        var status = refInputs.isEmpty() ? StakeStatus.ABSENT : StakeStatus.PRESENT;
        var previous = lastStatus.getAndSet(status);
        if (previous == status) {
            log.debug("stake status unchanged: {}", status);
        } else if (status == StakeStatus.ABSENT) {
            log.warn("No 30k FLDT stake found for this wallet's stake key {} — tank processing is DISABLED "
                    + "until the FLDT stake is delegated to this wallet.", account.stakeAddress());
        } else {
            var refInput = refInputs.getFirst();
            log.info("FLDT stake found for this wallet's stake key {} at {}:{} — tank processing is enabled.",
                    account.stakeAddress(), refInput.getTransactionId(), refInput.getIndex());
        }
    }

}
